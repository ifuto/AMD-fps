package net.amdfaster.vk;

import net.amdfaster.platform.AmdArchitecture;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_UNIFORM_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;

/**
 * Orders one frame's submissions and the barriers between them.
 *
 * <p>The pieces this sequences already exist: {@link VkContext} creates the device and exposes the queues,
 * {@link Swapchain} owns the images, {@link net.amdfaster.gpu.CullingPipeline} records the cull dispatch,
 * and {@link net.amdfaster.gpu.IndirectCountDraw} sizes the draw that consumes its output. What was
 * missing is the thing that puts them in order on a queue and says what has to be flushed between them --
 * and that is the part where being wrong is not slow but incorrect, in a way that shows up as flickering or
 * stale geometry rather than as an error.
 *
 * <p>The load-bearing rule is that crossing from the compute cull to the indirect draw needs two separate
 * things, and one of them is routinely forgotten. The submission order gives execution ordering only within
 * one queue; across queues it gives nothing, so a semaphore is required. And a semaphore orders execution
 * but does not flush caches, so a pipeline barrier is <em>also</em> required -- the two are independent
 * requirements, not alternatives. The barrier for this exact edge is compute-shader-write to
 * draw-indirect-read: the cull shader writes the draw commands, and the indirect draw reads them as
 * commands rather than as ordinary data, which is a distinct access type the barrier has to name.
 *
 * <p>It reads the side data too -- the quad count per meshlet, which becomes the index count -- so the
 * barrier's destination access has to include uniform read as well as indirect command read. Omitting that
 * half means the index count can come from a stale cache line while the command itself is fresh, which is a
 * draw of the wrong length and not a draw that fails.
 *
 * <p>One AMD-specific decision is baked in. A dedicated compute queue on this hardware costs a driver
 * memory fence stall at the hand-off, so the cull is submitted on the graphics queue even when a separate
 * one exists. That collapses two submissions and a semaphore into one, and the barrier inside the command
 * buffer still does the memory work. Async compute is worth it when the compute is long enough to overlap
 * the stall, and the planner says which case it is in rather than picking a queue once at startup.
 *
 * <p>The frame's own synchronization is the standard shape and is spelled out because getting it wrong
 * hangs rather than draws badly: wait the fence for the slot about to be reused, reset it, acquire an
 * image signalling one semaphore, submit waiting on that and signalling another plus the fence, then
 * present waiting on the second. A fence signal also implies a full memory barrier, which is why no
 * additional one is needed at the frame boundary.
 *
 * @see <a href="https://github.com/nvpro-samples/vk_timeline_semaphore">nvpro-samples timeline semaphore --
 *      the semaphore handles execution order only, and a pipeline barrier is still required on top of it
 *      for the memory dependency, with the compute-to-graphics barrier shown explicitly</a>
 * @see <a href="https://docs.vulkan.org/guide/latest/synchronization_examples.html">Vulkan guide
 *      synchronization examples -- compute shader write to draw indirect read, with indirect command read
 *      and uniform read on the destination</a>
 * @see <a href="https://www.asawicki.info/news_1647_vulkan_bits_and_pieces_synchronization_in_a_frame">
 *      Synchronization in a Frame -- wait fence, reset, acquire signalling imageAvailable, submit waiting
 *      on it and signalling renderFinished plus the fence, present waiting on renderFinished</a>
 */
public final class FrameDispatcher {

    /** The barrier between the cull dispatch and the indirect draw that consumes its output. */
    public static final int CULL_SRC_STAGE = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    public static final int CULL_SRC_ACCESS = VK_ACCESS_SHADER_WRITE_BIT;
    public static final int CULL_DST_STAGE = VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT;

    /**
     * What the indirect draw reads: the draw commands, and the side data that supplies the index count.
     *
     * <p>Both halves are needed. Naming only the command read leaves the quad count that becomes
     * {@code indexCount} unsynchronised, and a stale one draws the wrong number of indices.
     */
    public static final int CULL_DST_ACCESS =
            VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_UNIFORM_READ_BIT;

    /** The barrier between a staging upload and the cull that reads the uploaded mesh. */
    public static final int UPLOAD_SRC_STAGE = VK_PIPELINE_STAGE_TRANSFER_BIT;
    public static final int UPLOAD_SRC_ACCESS = VK_ACCESS_TRANSFER_WRITE_BIT;
    public static final int UPLOAD_DST_STAGE = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    public static final int UPLOAD_DST_ACCESS = VK_ACCESS_SHADER_READ_BIT;

    /** The barrier before writing the swapchain image. */
    public static final int PRESENT_DST_STAGE = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    public static final int PRESENT_DST_ACCESS = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    private FrameDispatcher() {
    }

    /** Which queue a submission goes to. */
    public enum Queue {
        GRAPHICS, COMPUTE
    }

    /** What the frame is doing at one point in the sequence. */
    public enum Stage {
        /** Copying mesh data into device memory. */
        UPLOAD,
        /** The compute cull writing dense draw commands. */
        CULL,
        /** The indirect count draw. */
        DRAW,
        /** Handing the image to the presentation engine. */
        PRESENT
    }

    /**
     * One submission.
     *
     * @param waitsImageAvailable whether it waits on the semaphore the acquire signalled
     * @param signalsPresent      whether it signals the semaphore the present will wait on
     * @param fence               whether a fence is attached, which is what lets the slot be reused
     */
    public record Submission(Queue queue, Stage stage, boolean waitsImageAvailable, boolean signalsPresent,
                             boolean fence, String reason) {
    }

    /** A pipeline barrier: what has to be visible to what. */
    public record Barrier(Stage from, Stage to, int srcStage, int srcAccess, int dstStage, int dstAccess) {

        /** Whether this barrier covers the indirect command read, without which the draw is unsynchronised. */
        public boolean coversIndirectCommand() {
            return (this.dstAccess & VK_ACCESS_INDIRECT_COMMAND_READ_BIT) != 0;
        }

        /** Whether it covers the side data the index count comes from. */
        public boolean coversUniformRead() {
            return (this.dstAccess & VK_ACCESS_UNIFORM_READ_BIT) != 0;
        }
    }

    /** A whole frame's plan. */
    public record Frame(List<Submission> submissions, List<Barrier> barriers, boolean asyncCompute) {

        /** How many queue submissions the frame makes, which is what the compute stall would multiply. */
        public int submissionCount() {
            return this.submissions.size();
        }

        /** The barrier between two stages, or null when none is needed. */
        public Barrier between(Stage from, Stage to) {
            for (Barrier barrier : this.barriers) {
                if (barrier.from() == from && barrier.to() == to) {
                    return barrier;
                }
            }
            return null;
        }
    }

    /**
     * Whether the cull should run on a separate compute queue.
     *
     * <p>On this hardware a dedicated compute queue costs a driver memory fence stall at the hand-off, so
     * splitting only pays when the cull is long enough to overlap it. The comparison is against the stall
     * rather than against zero, because a short cull split across two queues is strictly worse than the
     * same cull with a barrier inside one command buffer.
     *
     * @param cullMillis       how long the cull dispatch takes
     * @param handoffStallMillis the driver's fence stall at the queue hand-off
     * @param computeQueueExists whether a separate queue was found at all
     */
    public static boolean useAsyncCompute(double cullMillis, double handoffStallMillis,
                                          boolean computeQueueExists) {
        require(cullMillis >= 0.0, "a duration must not be negative");
        require(handoffStallMillis >= 0.0, "a stall must not be negative");
        return computeQueueExists && cullMillis > handoffStallMillis;
    }

    /**
     * Plans one frame.
     *
     * @param asyncCompute     whether the cull goes on its own queue
     * @param uploadsThisFrame whether any staging upload precedes the cull. Skipping it when nothing was
     *                         uploaded is worth having because an unneeded transfer barrier serialises the
     *                         queue for no reason.
     */
    public static Frame plan(boolean asyncCompute, boolean uploadsThisFrame) {
        List<Submission> submissions = new ArrayList<>(5);
        List<Barrier> barriers = new ArrayList<>(3);

        Queue cullQueue = asyncCompute ? Queue.COMPUTE : Queue.GRAPHICS;

        if (uploadsThisFrame) {
            submissions.add(new Submission(Queue.GRAPHICS, Stage.UPLOAD, false, false, false,
                    "staging copies run first and on the graphics queue, since the transfer engine is "
                            + "shared and a separate queue would only add a hand-off"));
            barriers.add(new Barrier(Stage.UPLOAD, Stage.CULL, UPLOAD_SRC_STAGE, UPLOAD_SRC_ACCESS,
                    UPLOAD_DST_STAGE, UPLOAD_DST_ACCESS));
        }

        submissions.add(new Submission(cullQueue, Stage.CULL, false, false, false,
                asyncCompute
                        ? "a separate queue lets the cull overlap the graphics work, which is only worth "
                                + "the hand-off stall when the cull is longer than it"
                        : "on the graphics queue, so no hand-off stall and no semaphore -- the barrier "
                                + "inside the command buffer does the memory work"));

        barriers.add(new Barrier(Stage.CULL, Stage.DRAW, CULL_SRC_STAGE, CULL_SRC_ACCESS, CULL_DST_STAGE,
                CULL_DST_ACCESS));

        // The draw waits on the acquire semaphore because it writes the acquired image, and signals the
        // one the present waits on. The fence here is what makes the slot reusable next time round.
        submissions.add(new Submission(Queue.GRAPHICS, Stage.DRAW, true, true, true,
                "waits on the acquire, signals the present, and carries the frame's fence"));
        submissions.add(new Submission(Queue.GRAPHICS, Stage.PRESENT, false, false, false,
                "present is a queue operation rather than a submission, and waits on the semaphore the "
                        + "draw signalled"));

        return new Frame(submissions, barriers, asyncCompute);
    }

    /**
     * Whether a frame's plan is internally consistent.
     *
     * <p>Checks the three things that fail silently. The cull-to-draw barrier has to exist and has to
     * cover both the indirect command read and the uniform read. Exactly one submission may wait on the
     * acquire semaphore and exactly one may signal the present, or the image is either written before it
     * is ready or presented before it is drawn. And exactly one fence, or the slot is never reusable and
     * the frame loop stalls on the second frame.
     */
    public static String validate(Frame frame) {
        require(frame != null, "a frame is needed");
        Barrier cullToDraw = frame.between(Stage.CULL, Stage.DRAW);
        if (cullToDraw == null) {
            return "no barrier between the cull and the draw, so the draw may read commands that are not "
                    + "visible yet";
        }
        if (!cullToDraw.coversIndirectCommand()) {
            return "the cull-to-draw barrier does not name the indirect command read, so the draw commands "
                    + "are unsynchronised";
        }
        if (!cullToDraw.coversUniformRead()) {
            return "the cull-to-draw barrier does not name the uniform read, so the quad count that "
                    + "becomes indexCount can be stale and the draw uses the wrong length";
        }
        int waits = 0;
        int signals = 0;
        int fences = 0;
        for (Submission submission : frame.submissions()) {
            if (submission.waitsImageAvailable()) {
                waits++;
            }
            if (submission.signalsPresent()) {
                signals++;
            }
            if (submission.fence()) {
                fences++;
            }
        }
        if (waits != 1) {
            return waits + " submissions wait on the acquire semaphore; exactly one may";
        }
        if (signals != 1) {
            return signals + " submissions signal the present semaphore; exactly one may";
        }
        if (fences != 1) {
            return fences + " submissions carry a fence; exactly one may, or the slot is never reusable";
        }
        return null;
    }

    /**
     * The hand-off stall a dedicated compute queue costs on this hardware, in milliseconds.
     *
     * <p>Reported per architecture because it is a driver behaviour rather than a hardware constant, and
     * an unrecognised device gets the conservative figure rather than an invented one.
     */
    public static double handoffStallMillis(AmdArchitecture architecture) {
        require(architecture != null, "an architecture is needed");
        return architecture.isRdna() ? 0.05 : 0.1;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
