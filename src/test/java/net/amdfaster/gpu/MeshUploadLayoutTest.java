package net.amdfaster.gpu;

import net.amdfaster.mesh.Meshlet;
import net.amdfaster.mesh.SectionMesh;
import net.amdfaster.mesh.voxel.ArrayVoxelView;
import net.amdfaster.mesh.voxel.SectionMesher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offsets and totals were computed by a separate implementation of the same alignment arithmetic
 * and diffed before committing, not read off the first run.
 */
class MeshUploadLayoutTest {

    @Test
    void anEmptySectionUploadsNothing() {
        SectionMesh empty = SectionMesher.mesh(new ArrayVoxelView(16, 16, 16), 0, 0, 0);
        assertTrue(empty.isEmpty());
        MeshUploadLayout layout = MeshUploadLayout.forSection(empty, 0);
        assertTrue(layout.isEmpty());
        assertEquals(0, layout.totalBytes());
        assertEquals(MeshUploadLayout.EMPTY, layout);
    }

    @Test
    void aSingleBlockLaysOutFourRegionsEndToEnd() {
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.set(4, 4, 4, 1, true);
        SectionMesh mesh = SectionMesher.mesh(view, 0, 0, 0);
        // 6 quads, 4 vertices each, 6 index bytes each.
        assertEquals(24 * Meshlet.POSITION_STRIDE, mesh.totalPositionBytes());
        assertEquals(24 * Meshlet.ATTRIBUTE_STRIDE, mesh.totalAttributeBytes());
        assertEquals(24 * Meshlet.LIGHT_STRIDE, mesh.totalLightBytes());
        assertEquals(36, mesh.totalIndexBytes());

        MeshUploadLayout layout = MeshUploadLayout.forSection(mesh, 0);
        assertEquals(0, layout.positionsOffset());
        assertEquals(192, layout.positionsBytes());
        assertEquals(192, layout.attributesOffset());
        assertEquals(192, layout.attributesBytes());
        assertEquals(384, layout.lightsOffset());
        assertEquals(96, layout.lightsBytes());
        assertEquals(480, layout.indicesOffset());
        assertEquals(36, layout.indicesBytes());
        assertEquals(516, layout.totalBytes());
        assertFalse(layout.isEmpty());
    }

    @Test
    void theLayoutMatchesTheByteCountsDirectly() {
        // Same numbers without going through a section, so the arithmetic is pinned down.
        MeshUploadLayout layout = MeshUploadLayout.of(0, 192, 192, 96, 36);
        assertEquals(0, layout.positionsOffset());
        assertEquals(192, layout.attributesOffset());
        assertEquals(384, layout.lightsOffset());
        assertEquals(480, layout.indicesOffset());
        assertEquals(516, layout.totalBytes());
    }

    @Test
    void regionsArePaddedSoEachOneStartsAligned() {
        // 10 bytes is not a multiple of 4, which is what makes the padding visible.
        MeshUploadLayout layout = MeshUploadLayout.of(0, 10, 10, 10, 10);
        assertEquals(0, layout.positionsOffset());
        assertEquals(12, layout.attributesOffset(), "10 rounds up to 12");
        assertEquals(24, layout.lightsOffset(), "22 rounds up to 24");
        assertEquals(36, layout.indicesOffset(), "34 rounds up to 36");
        assertEquals(48, layout.totalBytes(), "46 rounds up to 48");
    }

    @Test
    void anUnalignedBaseOffsetIsAbsorbedIntoTheTotal() {
        MeshUploadLayout aligned = MeshUploadLayout.of(0, 192, 192, 96, 36);
        MeshUploadLayout shifted = MeshUploadLayout.of(1, 192, 192, 96, 36);
        assertEquals(4, shifted.positionsOffset());
        assertEquals(520, shifted.positionsOffset() + aligned.positionsBytes()
                + aligned.attributesBytes() + aligned.lightsBytes() + aligned.indicesBytes());
        assertEquals(519, shifted.totalBytes(), "the caller reserves one extra byte of padding");
    }

    @Test
    void nothingOverlapsAndEveryOffsetIsAligned() {
        for (long base : new long[] {0, 1, 3, 4, 64, 4095}) {
            for (int[] sizes : new int[][] {{192, 192, 96, 36}, {10, 10, 10, 10}, {1, 1, 1, 1},
                    {0, 8, 4, 6}, {8, 0, 4, 6}, {8, 8, 4, 0}, {0, 0, 4, 6}, {1000, 4, 4, 42}}) {
                MeshUploadLayout layout =
                        MeshUploadLayout.of(base, sizes[0], sizes[1], sizes[2], sizes[3]);
                assertEquals(0, layout.positionsOffset() % MeshUploadLayout.ALIGNMENT,
                        "positions at base " + base);
                assertEquals(0, layout.attributesOffset() % MeshUploadLayout.ALIGNMENT,
                        "attributes at base " + base);
                assertEquals(0, layout.lightsOffset() % MeshUploadLayout.ALIGNMENT,
                        "lights at base " + base);
                assertEquals(0, layout.indicesOffset() % MeshUploadLayout.ALIGNMENT,
                        "indices at base " + base);

                assertTrue(layout.positionsOffset() + layout.positionsBytes()
                                <= layout.attributesOffset(),
                        "positions run into attributes at base " + base);
                assertTrue(layout.attributesOffset() + layout.attributesBytes()
                                <= layout.lightsOffset(),
                        "attributes run into lights at base " + base);
                assertTrue(layout.lightsOffset() + layout.lightsBytes()
                                <= layout.indicesOffset(),
                        "lights run into indices at base " + base);
                assertTrue(layout.indicesOffset() + layout.indicesBytes()
                                <= base + layout.totalBytes(),
                        "indices run past the reservation at base " + base);
                assertEquals(0, (base + layout.totalBytes()) % MeshUploadLayout.ALIGNMENT,
                        "the reservation must end aligned so the next section starts aligned");
            }
        }
    }

    @Test
    void anIndicesOnlyUploadStillAligns() {
        MeshUploadLayout layout = MeshUploadLayout.of(0, 0, 0, 0, 6);
        assertEquals(0, layout.indicesOffset());
        assertEquals(8, layout.totalBytes(), "6 index bytes round up to 8");
    }

    @Test
    void aLightsOnlyUploadIsPossibleOnItsOwn() {
        // The case a light update produces: geometry unchanged, only the light stream rewritten.
        MeshUploadLayout layout = MeshUploadLayout.of(0, 0, 0, 96, 0);
        assertEquals(0, layout.lightsOffset());
        assertEquals(96, layout.lightsBytes());
        assertEquals(96, layout.totalBytes());
        assertFalse(layout.isEmpty());
    }

    @Test
    void consecutiveSectionsTileWithoutGapsInTheReservation() {
        // The uploader reserves totalBytes per section; if that did not include the padding the
        // next section would start misaligned and the driver's map alignment would be violated.
        long cursor = 0;
        for (int i = 0; i < 5; i++) {
            MeshUploadLayout layout = MeshUploadLayout.of(cursor, 192, 192, 96, 36);
            assertEquals(0, cursor % MeshUploadLayout.ALIGNMENT);
            cursor += layout.totalBytes();
        }
        assertEquals(2580, cursor);
        assertEquals(0, cursor % MeshUploadLayout.ALIGNMENT);
    }
}
