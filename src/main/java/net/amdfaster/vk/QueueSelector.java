package net.amdfaster.vk;

import java.util.List;

/**
 * Picks which queue families the renderer submits to.
 *
 * <p>Three rules, all from AMD's own guidance:
 * <ul>
 *   <li><b>One graphics queue.</b> Splitting frame work across several graphics queues adds
 *       synchronisation that costs more than the overlap gains.</li>
 *   <li><b>Async compute goes on a queue that has no graphics bit.</b> AMD exposes dedicated
 *       compute engines; using them lets a compute dispatch overlap the graphics work instead of
 *       serialising behind it. Note the guide's caveat that async compute performs poorly against
 *       export-bound shaders, so this is a capability, not a policy.</li>
 *   <li><b>Never the transfer-only queue for on-GPU copies.</b> A transfer-only family is an SDMA
 *       engine built for host-to-device traffic; a device-local to device-local copy is faster on
 *       the graphics or compute queue. The transfer family is used for staging uploads only.</li>
 * </ul>
 */
public final class QueueSelector {

    // Mirrors VkQueueFlagBits.
    public static final int GRAPHICS = 0x00000001;
    public static final int COMPUTE = 0x00000002;
    public static final int TRANSFER = 0x00000004;
    public static final int SPARSE_BINDING = 0x00000008;

    /** One entry of the device's queue family list. */
    public record QueueFamily(int index, int flags, int queueCount, boolean supportsPresent) {

        public boolean has(int flags) {
            return (this.flags & flags) == flags;
        }

        public boolean isTransferOnly() {
            return has(TRANSFER) && !has(GRAPHICS) && !has(COMPUTE);
        }
    }

    /**
     * @param graphics      family the frame's draws go to
     * @param compute       family for meshlet culling and the GPU sort
     * @param hostTransfer  family for staging uploads; equals {@code graphics} when the device has
     *                      no transfer-only family, which is the normal case on AMD
     * @param asyncCompute  true when {@code compute} is a separate engine from {@code graphics}
     */
    public record QueueSelection(int graphics, int compute, int hostTransfer,
                                 boolean asyncCompute, String note) {
    }

    private QueueSelector() {
    }

    public static QueueSelection select(List<QueueFamily> families) {
        int graphics = -1;
        int graphicsNoPresent = -1;
        int dedicatedCompute = -1;
        int transferOnly = -1;

        for (QueueFamily f : families) {
            if (f.queueCount() <= 0) {
                continue;
            }
            if (f.has(GRAPHICS)) {
                // Prefer a graphics family that can also present, so the swapchain does not need a
                // second queue and an extra synchronisation edge.
                if (f.supportsPresent() && graphics == -1) {
                    graphics = f.index();
                } else if (graphicsNoPresent == -1) {
                    graphicsNoPresent = f.index();
                }
            } else if (f.has(COMPUTE) && dedicatedCompute == -1) {
                dedicatedCompute = f.index();
            } else if (f.isTransferOnly() && transferOnly == -1) {
                transferOnly = f.index();
            }
        }

        if (graphics == -1) {
            graphics = graphicsNoPresent;
        }
        if (graphics == -1) {
            throw new IllegalStateException("device exposes no graphics queue family among "
                    + families.size() + " families");
        }

        int compute = dedicatedCompute != -1 ? dedicatedCompute : graphics;
        int hostTransfer = transferOnly != -1 ? transferOnly : graphics;
        String note = compute != graphics
                ? "dedicated async compute engine on family " + compute
                : "no dedicated compute family; compute shares the graphics queue";
        return new QueueSelection(graphics, compute, hostTransfer, compute != graphics, note);
    }
}
