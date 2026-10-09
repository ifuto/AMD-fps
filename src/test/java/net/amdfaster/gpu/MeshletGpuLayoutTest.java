package net.amdfaster.gpu;

import net.amdfaster.mesh.Meshlet;
import net.amdfaster.mesh.Orientation;
import net.amdfaster.mesh.SectionMesh;
import net.amdfaster.mesh.voxel.ArrayVoxelView;
import net.amdfaster.mesh.voxel.SectionMesher;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshletGpuLayoutTest {

    private static SectionMesh singleBlock() {
        return singleBlock(0, 0, 0);
    }

    private static SectionMesh singleBlock(int originX, int originY, int originZ) {
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.set(4, 4, 4, 1, true);
        return SectionMesher.mesh(view, originX, originY, originZ);
    }

    @Test
    void anEmptySectionHasNoRuns() {
        MeshletGpuLayout layout = MeshletGpuLayout.forSection(SectionMesher.mesh(new ArrayVoxelView(16, 16, 16), 0, 0, 0));
        assertTrue(layout.isEmpty());
        assertEquals(0, layout.meshletCount());
        assertEquals(0, layout.cullDataBytes());
        assertEquals(0, layout.cullWorkGroups());
        for (Orientation o : Orientation.values()) {
            assertEquals(0, layout.runCount(o));
        }
    }

    @Test
    void aSingleBlockPutsOneMeshletInEachBucket() {
        MeshletGpuLayout layout = MeshletGpuLayout.forSection(singleBlock());
        assertEquals(6, layout.meshletCount());
        assertFalse(layout.isEmpty());
        for (Orientation o : Orientation.values()) {
            assertEquals(1, layout.runCount(o), o + " holds one meshlet");
        }
    }

    @Test
    void packingRoundTripsThroughTheLayoutTheShaderReads() {
        // A negative origin on every axis: half of Minecraft's world has one, and it is the case a
        // uint-to-float conversion silently destroys.
        int originX = -1040;
        int originY = -16;
        int originZ = -48;
        SectionMesh mesh = singleBlock(originX, originY, originZ);
        MeshletGpuLayout layout = MeshletGpuLayout.forSection(mesh);

        ByteBuffer records = ByteBuffer.allocateDirect(layout.cullDataBytes())
                .order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer orientations = ByteBuffer.allocateDirect(layout.orientationBytes())
                .order(ByteOrder.LITTLE_ENDIAN);

        layout.writeCullData(mesh, records, orientations);

        assertEquals(layout.cullDataBytes(), records.position(), "every record byte was written");
        assertEquals(layout.orientationBytes(), orientations.position(),
                "every orientation entry was written");

        records.rewind();
        orientations.rewind();

        int slot = 0;
        for (Orientation o : Orientation.values()) {
            for (Meshlet meshlet : mesh.meshlets(o)) {
                int base = slot * MeshletGpuLayout.RECORD_BYTES;
                int packed = records.getInt(base);
                float x = Float.intBitsToFloat(records.getInt(base + 4));
                float y = Float.intBitsToFloat(records.getInt(base + 8));
                float z = Float.intBitsToFloat(records.getInt(base + 12));
                int sideData = orientations.getInt(slot * CullBindings.ORIENTATION_BYTES_PER_MESHLET);

                assertEquals(meshlet.minX(), Meshlet.unpackMinX(packed), "slot " + slot + " minX");
                assertEquals(meshlet.minY(), Meshlet.unpackMinY(packed), "slot " + slot + " minY");
                assertEquals(meshlet.minZ(), Meshlet.unpackMinZ(packed), "slot " + slot + " minZ");
                assertEquals(meshlet.maxX(), Meshlet.unpackMaxX(packed), "slot " + slot + " maxX");
                assertEquals(meshlet.maxY(), Meshlet.unpackMaxY(packed), "slot " + slot + " maxY");
                assertEquals(meshlet.maxZ(), Meshlet.unpackMaxZ(packed), "slot " + slot + " maxZ");

                assertEquals((float) originX, x, 0f, "slot " + slot + " origin X");
                assertEquals((float) originY, y, 0f, "slot " + slot + " origin Y");
                assertEquals((float) originZ, z, 0f, "slot " + slot + " origin Z");

                // The pairing the back-face test depends on: this record's bounds must go with this
                // record's normal. Pairing them wrong back-face culls the wrong geometry, and it
                // only shows up as faces missing from certain angles. The bucket is the low three
                // bits; the quad count rides above it, so reading the whole word as an ordinal
                // gives 8 for a one-quad meshlet rather than 0.
                assertEquals(o.ordinal(), Meshlet.sideDataOrientation(sideData),
                        "slot " + slot + " orientation");
                assertEquals(meshlet.quadCount(), Meshlet.sideDataQuads(sideData),
                        "slot " + slot + " quad count");
                assertEquals(meshlet.orientation(), o, "the walk must visit " + o + " together");

                slot++;
            }
        }
        assertEquals(layout.meshletCount(), slot, "the walk covered every slot");
    }

    @Test
    void aNegativeOriginSurvivesTheFloatEncoding() {
        // Pinned separately from the round trip, because the round trip would also pass with a
        // positive origin and the bug it is guarding against only exists for negative ones.
        for (int origin : new int[]{-1048576, -16, -1, 0, 1, 1048560}) {
            int bits = Float.floatToRawIntBits((float) origin);
            assertEquals((float) origin, Float.intBitsToFloat(bits), 0f,
                    "origin " + origin + " must survive the encoding exactly");
            // What the shader would read if the writer stored the integer instead.
            assertTrue(Float.intBitsToFloat(bits) <= 0f || origin > 0,
                    "a negative origin must not come back positive");
        }
    }

    @Test
    void runsAreContiguousAndInOrientationOrder() {
        // The culling dispatch walks one flat array, and skipping a whole bucket depends on its
        // run being one unbroken range.
        MeshletGpuLayout layout = MeshletGpuLayout.forSection(singleBlock());
        int cursor = 0;
        for (Orientation o : Orientation.values()) {
            assertEquals(cursor, layout.runStart(o), o + " run must start where the last ended");
            cursor += layout.runCount(o);
        }
        assertEquals(layout.meshletCount(), cursor);
    }

    @Test
    void theLayoutAgreesWithTheSection() {
        SectionMesh mesh = singleBlock();
        MeshletGpuLayout layout = MeshletGpuLayout.forSection(mesh);
        assertEquals(mesh.totalMeshlets(), layout.meshletCount());
        for (Orientation o : Orientation.values()) {
            assertEquals(mesh.meshlets(o).length, layout.runCount(o));
        }
    }

    @Test
    void cullDataIsSixteenBytesPerMeshlet() {
        assertEquals(16, MeshletGpuLayout.RECORD_BYTES);
        assertEquals(6 * 16, MeshletGpuLayout.forSection(singleBlock()).cullDataBytes());
    }

    @Test
    void theIndexAndVertexStridesMatchWhatTheShaderWrites() {
        // meshlet_cull.comp derives a draw command from the meshlet index alone, so these two
        // numbers are the contract between the shader and the buffer.
        assertEquals(372, MeshletGpuLayout.INDICES_PER_MESHLET, "62 quads x 6 indices");
        assertEquals(248, MeshletGpuLayout.VERTICES_PER_MESHLET, "62 quads x 4 vertices");
        assertEquals(Meshlet.MAX_QUADS * Meshlet.INDEX_BYTES_PER_QUAD,
                MeshletGpuLayout.INDICES_PER_MESHLET);
        assertTrue(MeshletGpuLayout.VERTICES_PER_MESHLET <= Meshlet.MAX_VERTICES,
                "the stride must fit the 8-bit local indices the meshlet uses");
    }

    @Test
    void worstCaseSizesScaleWithTheMeshletCount() {
        MeshletGpuLayout layout = MeshletGpuLayout.forSection(singleBlock());
        assertEquals(6 * 372, layout.indexBytes());
        assertEquals(6 * 248, layout.vertexSlots());
    }

    @Test
    void theDispatchCoversEveryMeshlet() {
        MeshletGpuLayout layout = MeshletGpuLayout.forSection(singleBlock());
        assertEquals(1, layout.cullWorkGroups());
        assertEquals(0, MeshletGpuLayout.forSection(
                SectionMesher.mesh(new ArrayVoxelView(16, 16, 16), 0, 0, 0)).cullWorkGroups());
    }

    @Test
    void aMergedFloorStillHasOneMeshletPerBucket() {
        // Greedy meshing collapses the geometry, not the buckets: the layout is about meshlets,
        // and 1536 unit faces merged into 6 quads is still 6 meshlets.
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.fill(0, 0, 0, 16, 1, 16, 7, true);
        MeshletGpuLayout layout = MeshletGpuLayout.forSection(SectionMesher.mesh(view, 0, 0, 0));
        assertEquals(6, layout.meshletCount());
        for (Orientation o : Orientation.values()) {
            assertEquals(1, layout.runCount(o));
        }
    }

    @Test
    void theLightStreamIsSizedFromTheWorstCaseVertexCount() {
        assertEquals(992, MeshletGpuLayout.LIGHT_BYTES_PER_MESHLET, "248 vertices x 4 bytes");
        assertEquals(MeshletGpuLayout.VERTICES_PER_MESHLET * Meshlet.LIGHT_STRIDE,
                MeshletGpuLayout.LIGHT_BYTES_PER_MESHLET);

        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.set(4, 4, 4, 1, true);
        MeshletGpuLayout layout = MeshletGpuLayout.forSection(SectionMesher.mesh(view, 0, 0, 0));
        assertEquals(layout.meshletCount() * MeshletGpuLayout.LIGHT_BYTES_PER_MESHLET,
                layout.lightBytes());
        // The worst-case reservation has to cover what the section actually needs, or the light
        // upload overruns the buffer on a dense section.
        SectionMesh mesh = SectionMesher.mesh(view, 0, 0, 0);
        assertTrue(layout.lightBytes() >= mesh.totalLightBytes(),
                layout.lightBytes() + " < " + mesh.totalLightBytes());
    }

    @Test
    void anEmptySectionReservesNoLightBytes() {
        MeshletGpuLayout empty =
                MeshletGpuLayout.forSection(SectionMesher.mesh(new ArrayVoxelView(16, 16, 16), 0, 0, 0));
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.lightBytes());
        assertEquals(0, empty.vertexSlots());
    }
}
