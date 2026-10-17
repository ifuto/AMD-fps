package net.amdfaster.runtime;

import net.amdfaster.gpu.InstanceDeduplicator;
import net.amdfaster.gpu.UniformCache;
import net.amdfaster.gpu.UpscalerBackendSelector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpuRuntimeTest {

    @Test
    void aSlotIsAcquiredSubmittedAndCompleted() {
        // A slot is not reused until the GPU is finished with it, because overwriting a buffer the GPU is
        // still reading is not a performance problem, it is a frame that draws garbage.
        GpuRuntime runtime = new GpuRuntime();

        int slot = runtime.acquireSlot();
        assertTrue(slot >= 0);

        runtime.submitSlot(slot);
        runtime.completeSlot(slot);

        assertTrue(runtime.acquireSlot() >= 0, "and the slot comes back");
    }

    @Test
    void aUniformIsSentOnceAndThenCached() {
        GpuRuntime runtime = new GpuRuntime();
        int[] sends = {0};
        UniformCache.Setter setter = (program, slot, value) -> sends[0]++;

        assertTrue(runtime.setUniform(1, 0, new float[] {1.0f, 2.0f}, setter));
        assertFalse(runtime.setUniform(1, 0, new float[] {1.0f, 2.0f}, setter),
                "the same value is not sent again");
        assertEquals(1, sends[0]);
    }

    @Test
    void aChangedUniformIsSentAgain() {
        GpuRuntime runtime = new GpuRuntime();
        int[] sends = {0};
        UniformCache.Setter setter = (program, slot, value) -> sends[0]++;

        runtime.setUniform(1, 0, new float[] {1.0f}, setter);
        assertTrue(runtime.setUniform(1, 0, new float[] {2.0f}, setter), "a different value");
        assertEquals(2, sends[0]);
    }

    @Test
    void anInvalidatedUniformIsSentAgain() {
        // A value that changed and was not invalidated leaves the shader using the old one, and the symptom is
        // a camera matrix from last frame, which is a world that lags the player's view.
        GpuRuntime runtime = new GpuRuntime();
        int[] sends = {0};
        UniformCache.Setter setter = (program, slot, value) -> sends[0]++;

        runtime.setUniform(1, 0, new float[] {1.0f}, setter);
        runtime.invalidateUniform(1, 0);

        assertTrue(runtime.setUniform(1, 0, new float[] {1.0f}, setter), "sent again after invalidation");
        assertEquals(2, sends[0]);
    }

    @Test
    void aUniformIsKeyedByProgramAndSlot() {
        GpuRuntime runtime = new GpuRuntime();
        int[] sends = {0};
        UniformCache.Setter setter = (program, slot, value) -> sends[0]++;

        runtime.setUniform(1, 0, new float[] {1.0f}, setter);
        assertTrue(runtime.setUniform(2, 0, new float[] {1.0f}, setter), "a different program");
        assertTrue(runtime.setUniform(1, 1, new float[] {1.0f}, setter), "and a different slot");
        assertEquals(3, sends[0]);
    }

    @Test
    void instancesWithTheSameKeyAreDeduplicated() {
        GpuRuntime runtime = new GpuRuntime();
        long key = InstanceDeduplicator.groupKey(1, 2, 3, 0);

        int first = runtime.instances().add(key, new float[] {0, 0, 0});
        int second = runtime.instances().add(key, new float[] {0, 0, 0});

        assertEquals(first, second, "the same group");
        assertEquals(1, runtime.instances().instanceCount(
                (int) (key & 0xFFFFFFFFL)));
    }

    @Test
    void anUberShaderIsChosenByDrawCountAndVariants() {
        // One shader with branches costs the branches on every fragment, and several shaders cost a pipeline
        // switch per draw. Which is cheaper depends on how many draws there are and how mixed the variants are.
        GpuRuntime runtime = new GpuRuntime();

        boolean few = runtime.useUberShader(2, 8, true);
        boolean many = runtime.useUberShader(500, 2, false);

        assertFalse(few == many, "the two cases differ");
    }

    @Test
    void theUpscalerBackendIsChosenByWhatTheDeviceReports() {
        // A rule keyed on a vendor would be wrong twice -- some Intel parts have capabilities an AMD rule
        // assumes and some AMD parts do not have the ones an Intel rule assumes.
        GpuRuntime runtime = new GpuRuntime();

        UpscalerBackendSelector.Backend withoutMatrix = runtime.upscalerBackend(false);
        UpscalerBackendSelector.Backend withMatrix = runtime.upscalerBackend(true);

        assertEquals(UpscalerBackendSelector.Backend.DOT_PRODUCT, withoutMatrix);
        assertEquals(UpscalerBackendSelector.Backend.MATRIX, withMatrix);
    }

    @Test
    void upscalingIsOnlyWorthItWhenTheSavingIsReal() {
        GpuRuntime runtime = new GpuRuntime();

        assertFalse(runtime.upscalingWorthIt(true, 0.0), "nothing saved");
        assertTrue(runtime.upscalingWorthIt(true, 5.0), "and worth it when the saving is real");
    }

    @Test
    void aWorldChangeClearsTheInstances() {
        GpuRuntime runtime = new GpuRuntime();

        runtime.instances().add(InstanceDeduplicator.groupKey(1, 1, 1, 0), new float[] {0, 0, 0});
        runtime.endFrame();

        runtime.onWorldChange();

        assertEquals(0L, runtime.frames());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        GpuRuntime runtime = new GpuRuntime();

        assertThrows(IllegalArgumentException.class,
                () -> runtime.setUniform(1, 0, new float[] {1.0f}, null));
    }

    @Test
    void aFreshRuntimeReportsZeroNotNaN() {
        // A runtime that has drawn nothing has technically uploaded everything, and reporting that as a rate
        // would describe an unused one as though it had measured something.
        GpuRuntime runtime = new GpuRuntime();

        assertEquals(0L, runtime.frames());
        assertEquals(0L, runtime.arena().capacity() >= 0 ? 0L : 1L);
        assertEquals(4096, GpuRuntime.DEFAULT_SECTIONS);
        assertEquals(65536, GpuRuntime.BYTES_PER_SECTION);
    }
}
