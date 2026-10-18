package net.amdfaster.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.platform.AmdArchitecture;
import net.amdfaster.render.MeshShaderBackendSelector.Backend;
import net.amdfaster.render.MeshShaderBackendSelector.Decision;
import net.amdfaster.render.MeshShaderBackendSelector.Inputs;
import org.junit.jupiter.api.Test;

class MeshShaderBackendSelectorTest {

    private static Inputs inputs(int meshlets, boolean perTriangle, boolean computeCull,
                                 boolean available, AmdArchitecture architecture) {
        return new Inputs(meshlets, perTriangle, computeCull, available, architecture);
    }

    @Test
    void noExtensionMeansNoChoice() {
        Decision decision = MeshShaderBackendSelector.decide(
                inputs(1000, true, false, false, AmdArchitecture.RDNA4));

        assertEquals(Backend.COMPUTE_CULL_MDI, decision.backend());
        assertTrue(decision.reason().contains("not exposed"), decision.reason());
    }

    @Test
    void rdna1AndGcnHaveNoMeshHardware() {
        // RDNA1 predates the stage entirely, and GCN 5 is not RDNA at all.
        for (AmdArchitecture architecture : new AmdArchitecture[] {
                AmdArchitecture.RDNA1, AmdArchitecture.GCN5_VEGA, AmdArchitecture.UNKNOWN}) {
            Decision decision = MeshShaderBackendSelector.decide(
                    inputs(1000, true, false, true, architecture));
            assertEquals(Backend.COMPUTE_CULL_MDI, decision.backend(), architecture.displayName());
            assertTrue(decision.reason().contains("no mesh shader hardware"), decision.reason());
        }
    }

    @Test
    void anExistingComputeCullIsNotDuplicated() {
        // Mixing a task or mesh stage with a compute cull does the same meshlet culling twice, and the
        // second one pays the ring buffer for the privilege.
        Decision decision = MeshShaderBackendSelector.decide(
                inputs(1000, false, true, true, AmdArchitecture.RDNA3));

        assertEquals(Backend.COMPUTE_CULL_MDI, decision.backend());
        assertTrue(decision.reason().contains("second time"), decision.reason());
        assertTrue(decision.reason().contains("ring buffer"), decision.reason());
    }

    @Test
    void thePrimitiveCountPutsAVoxelRendererPastTheCrossover() {
        // A single section is 1,536 quads, so even one section is twelve times past the 128 primitives
        // where multi-draw indirect measured faster than mesh shaders on AMD.
        Decision one = MeshShaderBackendSelector.decide(
                inputs(1, false, false, true, AmdArchitecture.RDNA3));
        assertEquals(Backend.COMPUTE_CULL_MDI, one.backend(), "124 triangles is under the crossover");
        assertTrue(one.reason().contains("per meshlet"), one.reason());

        Decision two = MeshShaderBackendSelector.decide(
                inputs(2, false, false, true, AmdArchitecture.RDNA3));
        assertEquals(Backend.COMPUTE_CULL_MDI, two.backend());
        assertTrue(two.reason().contains("248 primitives"), two.reason());
        assertTrue(two.reason().contains("multi-draw indirect measured faster"), two.reason());

        Decision twelveChunks = MeshShaderBackendSelector.decide(
                inputs(13824, false, false, true, AmdArchitecture.RDNA4));
        assertTrue(twelveChunks.reason().contains("1714176 primitives"), twelveChunks.reason());
    }

    @Test
    void perTriangleRejectionWithoutAComputeCullIsTheOneCaseForTheMeshStage() {
        // The indirect path's floor is one meshlet: the cull shader either emits a meshlet's draw command
        // or it does not. Rejecting individual primitives inside a meshlet is the sole thing the mesh
        // stage can do that it cannot, and voxel geometry has interior facing surfaces to reject.
        Decision decision = MeshShaderBackendSelector.decide(
                inputs(1000, true, false, true, AmdArchitecture.RDNA3));

        assertEquals(Backend.MESH_SHADER, decision.backend());
        assertTrue(decision.reason().contains("no task stage"), decision.reason());
        assertTrue(decision.reason().contains("fixed VRAM ring"), decision.reason());
    }

    @Test
    void perTriangleRejectionWithAComputeCullSaysWhatWasGivenUp() {
        // Both answers cost something here, so the reason names the trade instead of pretending the
        // chosen path lost nothing.
        Decision decision = MeshShaderBackendSelector.decide(
                inputs(1000, true, true, true, AmdArchitecture.RDNA4));

        assertEquals(Backend.COMPUTE_CULL_MDI, decision.backend());
        assertTrue(decision.reason().contains("twice"), decision.reason());
        assertTrue(decision.reason().contains("given up"), decision.reason());
    }

    @Test
    void theTaskStageIsNotViableAtMinecraftsMeshletCounts() {
        // A 32K item task configuration was measured losing to manual compute culling on a 7900 GRE, and
        // the ring does not scale with the payload count. Twelve chunk distance is 13,824 meshlets.
        assertTrue(MeshShaderBackendSelector.taskStageViable(1024, 32));
        assertFalse(MeshShaderBackendSelector.taskStageViable(1025, 32));
        assertFalse(MeshShaderBackendSelector.taskStageViable(100, 33), "payload size matters too");
        assertFalse(MeshShaderBackendSelector.taskStageViable(13824, 8));
    }

    @Test
    void amplificationGroupsUseTheRecommendedSize() {
        // Each group should launch at least 32 mesh thread groups to hide the latency the task stage
        // adds; fewer means more commands back to the command processor, which is the same ring.
        assertEquals(32, MeshShaderBackendSelector.MESHLETS_PER_AMP_GROUP);
        assertEquals(0, MeshShaderBackendSelector.amplificationGroups(0));
        assertEquals(1, MeshShaderBackendSelector.amplificationGroups(1));
        assertEquals(1, MeshShaderBackendSelector.amplificationGroups(32));
        assertEquals(2, MeshShaderBackendSelector.amplificationGroups(33));
        assertEquals(432, MeshShaderBackendSelector.amplificationGroups(13824));
    }

    @Test
    void theMeshletIsWiderThanTheHardwareGuidanceAndThatIsReported() {
        // 248 vertices against the 64 to 128 the guidance prefers, because each corner of a block face
        // carries its own ambient occlusion and light and so cannot be shared the way a smooth mesh's
        // can. The triangles are within guidance; the vertices are not.
        assertEquals(124, MeshShaderBackendSelector.TRIANGLES_PER_MESHLET);
        assertEquals(248, MeshShaderBackendSelector.VERTICES_PER_MESHLET);
        assertFalse(MeshShaderBackendSelector.meshletWithinGuidance());
    }

    @Test
    void theConstantsAreTheMeasuredOnes() {
        assertEquals(128, MeshShaderBackendSelector.MDI_CROSSOVER_PRIMITIVES);
        assertEquals(1024, MeshShaderBackendSelector.TASK_RING_SAFE_MESHLETS);
        assertTrue(MeshShaderBackendSelector.TASK_RING_SAFE_MESHLETS < 32768,
                "a 32K configuration was measured losing, so the threshold sits below it");
    }

    @Test
    void nonsenseInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> MeshShaderBackendSelector.decide(null));
        assertThrows(IllegalArgumentException.class,
                () -> MeshShaderBackendSelector.decide(inputs(-1, false, false, true, AmdArchitecture.RDNA3)));
        assertThrows(IllegalArgumentException.class,
                () -> MeshShaderBackendSelector.decide(inputs(1, false, false, true, null)));
        assertThrows(IllegalArgumentException.class,
                () -> MeshShaderBackendSelector.taskStageViable(-1, 8));
        assertThrows(IllegalArgumentException.class,
                () -> MeshShaderBackendSelector.taskStageViable(1, -8));
        assertThrows(IllegalArgumentException.class,
                () -> MeshShaderBackendSelector.amplificationGroups(-1));
    }
}
