package net.amdfaster.gpu;

import net.amdfaster.mesh.Meshlet;
import net.amdfaster.mesh.Orientation;
import net.amdfaster.mesh.SectionMesh;
import net.amdfaster.mesh.voxel.ArrayVoxelView;
import net.amdfaster.mesh.voxel.SectionMesher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshletGpuLayoutTest {

    private static SectionMesh singleBlock() {
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.set(4, 4, 4, 1, true);
        return SectionMesher.mesh(view, 0, 0, 0);
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
