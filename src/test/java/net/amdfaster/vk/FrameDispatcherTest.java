package net.amdfaster.vk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.amdfaster.platform.AmdArchitecture;
import net.amdfaster.vk.FrameDispatcher.Barrier;
import net.amdfaster.vk.FrameDispatcher.Frame;
import net.amdfaster.vk.FrameDispatcher.Queue;
import net.amdfaster.vk.FrameDispatcher.Stage;
import net.amdfaster.vk.FrameDispatcher.Submission;
import org.junit.jupiter.api.Test;

class FrameDispatcherTest {

    @Test
    void theCullToDrawBarrierCoversBothReadsTheDrawMakes() {
        // The draw reads the commands AND the side data that supplies indexCount. Naming only the command
        // read leaves the quad count unsynchronised, and a stale one draws the wrong number of indices --
        // a wrong length, not a failure.
        Frame frame = FrameDispatcher.plan(false, false);
        Barrier barrier = frame.between(Stage.CULL, Stage.DRAW);

        assertNotNull(barrier);
        assertEquals(FrameDispatcher.CULL_SRC_STAGE, barrier.srcStage());
        assertEquals(FrameDispatcher.CULL_SRC_ACCESS, barrier.srcAccess());
        assertEquals(FrameDispatcher.CULL_DST_STAGE, barrier.dstStage());
        assertTrue(barrier.coversIndirectCommand());
        assertTrue(barrier.coversUniformRead());
        assertNull(FrameDispatcher.validate(frame));
    }

    @Test
    void aBarrierMissingEitherReadFailsValidation() {
        // Both halves are independent requirements, so both are checked rather than one standing in for
        // the other.
        Frame base = FrameDispatcher.plan(false, false);
        List<Barrier> commandsOnly = new ArrayList<>();
        commandsOnly.add(new Barrier(Stage.CULL, Stage.DRAW, FrameDispatcher.CULL_SRC_STAGE,
                FrameDispatcher.CULL_SRC_ACCESS, FrameDispatcher.CULL_DST_STAGE,
                org.lwjgl.vulkan.VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT));
        Frame noUniform = new Frame(base.submissions(), commandsOnly, false);
        assertTrue(FrameDispatcher.validate(noUniform).contains("indexCount"),
                FrameDispatcher.validate(noUniform));

        List<Barrier> neither = new ArrayList<>();
        neither.add(new Barrier(Stage.CULL, Stage.DRAW, FrameDispatcher.CULL_SRC_STAGE,
                FrameDispatcher.CULL_SRC_ACCESS, FrameDispatcher.CULL_DST_STAGE,
                org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT));
        Frame noCommand = new Frame(base.submissions(), neither, false);
        assertTrue(FrameDispatcher.validate(noCommand).contains("indirect command read"),
                FrameDispatcher.validate(noCommand));

        Frame noBarrier = new Frame(base.submissions(), List.of(), false);
        assertTrue(FrameDispatcher.validate(noBarrier).contains("no barrier"),
                FrameDispatcher.validate(noBarrier));
    }

    @Test
    void exactlyOneSubmissionWaitsAcquiresSignalsAndFences() {
        // Getting any of these wrong hangs rather than draws badly: no wait writes an image that is not
        // ready, no signal presents one that is not drawn, and no fence means the slot is never reusable
        // and the loop stalls on the second frame.
        Frame frame = FrameDispatcher.plan(false, false);

        assertEquals(1, frame.submissions().stream().filter(Submission::waitsImageAvailable).count());
        assertEquals(1, frame.submissions().stream().filter(Submission::signalsPresent).count());
        assertEquals(1, frame.submissions().stream().filter(Submission::fence).count());

        // The draw is the one that does all three, and the present is a separate queue operation.
        Submission draw = frame.submissions().get(2);
        assertEquals(Stage.DRAW, draw.stage());
        assertTrue(draw.waitsImageAvailable());
        assertTrue(draw.signalsPresent());
        assertTrue(draw.fence());
        assertEquals(Stage.PRESENT, frame.submissions().get(3).stage());
    }

    @Test
    void theGraphicsQueuePlanCollapsesToTwoSubmissions() {
        // No hand-off, so no semaphore between the cull and the draw -- the barrier inside one command
        // buffer does the memory work and submission order does the rest.
        Frame frame = FrameDispatcher.plan(false, false);

        assertFalse(frame.asyncCompute());
        assertEquals(3, frame.submissionCount(), "cull, draw, present");
        assertEquals(Queue.GRAPHICS, frame.submissions().get(0).queue());
        assertEquals(Stage.CULL, frame.submissions().get(0).stage());
        assertTrue(frame.submissions().get(0).reason().contains("no semaphore"),
                frame.submissions().get(0).reason());
        assertNull(FrameDispatcher.validate(frame));
    }

    @Test
    void asyncComputePutsTheCullOnItsOwnQueue() {
        Frame frame = FrameDispatcher.plan(true, false);

        assertTrue(frame.asyncCompute());
        assertEquals(Queue.COMPUTE, frame.submissions().get(0).queue());
        assertTrue(frame.submissions().get(0).reason().contains("overlap"),
                frame.submissions().get(0).reason());
        // The cull-to-draw barrier is still required: a semaphore orders execution but does not flush.
        assertNotNull(frame.between(Stage.CULL, Stage.DRAW));
        assertNull(FrameDispatcher.validate(frame));
    }

    @Test
    void anUploadAddsASubmissionAndItsOwnBarrier() {
        Frame frame = FrameDispatcher.plan(false, true);

        assertEquals(4, frame.submissionCount(), "upload, cull, draw, present");
        assertEquals(Stage.UPLOAD, frame.submissions().get(0).stage());
        assertEquals(Queue.GRAPHICS, frame.submissions().get(0).queue());

        Barrier upload = frame.between(Stage.UPLOAD, Stage.CULL);
        assertNotNull(upload);
        assertEquals(FrameDispatcher.UPLOAD_SRC_STAGE, upload.srcStage());
        assertEquals(FrameDispatcher.UPLOAD_DST_ACCESS, upload.dstAccess());
        assertFalse(upload.coversIndirectCommand(), "the cull reads data, not commands");
        assertNull(FrameDispatcher.validate(frame));
    }

    @Test
    void noUploadMeansNoTransferBarrier() {
        // An unneeded transfer barrier serialises the queue for no reason, so it is left out when nothing
        // was uploaded.
        Frame frame = FrameDispatcher.plan(false, false);

        assertNull(frame.between(Stage.UPLOAD, Stage.CULL));
        assertEquals(1, frame.barriers().size(), "only the cull-to-draw barrier");
    }

    @Test
    void asyncComputeOnlyPaysWhenTheCullOutlastsTheStall() {
        // A dedicated compute queue costs a driver memory fence stall at the hand-off, so a short cull
        // split across two queues is strictly worse than the same cull with a barrier in one buffer.
        assertFalse(FrameDispatcher.useAsyncCompute(0.02, 0.05, true), "cull shorter than the stall");
        assertFalse(FrameDispatcher.useAsyncCompute(0.05, 0.05, true), "equal does not pay either");
        assertTrue(FrameDispatcher.useAsyncCompute(0.2, 0.05, true));
        assertFalse(FrameDispatcher.useAsyncCompute(5.0, 0.05, false), "no separate queue to use");
    }

    @Test
    void theStallIsReportedPerGenerationRatherThanInvented() {
        assertEquals(0.05, FrameDispatcher.handoffStallMillis(AmdArchitecture.RDNA4), 1e-12);
        assertEquals(0.05, FrameDispatcher.handoffStallMillis(AmdArchitecture.RDNA1), 1e-12);
        assertEquals(0.1, FrameDispatcher.handoffStallMillis(AmdArchitecture.GCN5_VEGA), 1e-12,
                "an unrecognised generation gets the conservative figure");
        assertEquals(0.1, FrameDispatcher.handoffStallMillis(AmdArchitecture.UNKNOWN), 1e-12);
    }

    @Test
    void theBarrierBitsAreTheVulkanConstantsNotHandSpelled() {
        // These come from LWJGL's VK10 rather than being typed in, because a hand-spelled enum value is
        // wrong in a way nothing here would catch.
        assertEquals(org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                FrameDispatcher.CULL_SRC_STAGE);
        assertEquals(org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT, FrameDispatcher.CULL_SRC_ACCESS);
        assertEquals(org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
                FrameDispatcher.CULL_DST_STAGE);
        assertEquals(org.lwjgl.vulkan.VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT
                        | org.lwjgl.vulkan.VK10.VK_ACCESS_UNIFORM_READ_BIT,
                FrameDispatcher.CULL_DST_ACCESS);
    }

    @Test
    void nonsenseInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> FrameDispatcher.useAsyncCompute(-1.0, 0.05, true));
        assertThrows(IllegalArgumentException.class,
                () -> FrameDispatcher.useAsyncCompute(1.0, -0.05, true));
        assertThrows(IllegalArgumentException.class, () -> FrameDispatcher.validate(null));
        assertThrows(IllegalArgumentException.class, () -> FrameDispatcher.handoffStallMillis(null));
    }
}
