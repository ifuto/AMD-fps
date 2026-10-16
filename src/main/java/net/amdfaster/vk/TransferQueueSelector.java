package net.amdfaster.vk;

/**
 * Chooses which queue a copy goes on, because the answer depends on where the memory is.
 *
 * <p>Copying mesh data into video memory is not one kind of work. On a discrete card the source is system
 * memory and the destination is across the PCI Express link, so the copy is a DMA transfer that a dedicated
 * engine does without involving the shader cores at all. On an APU the two are the same physical memory, so
 * there is no link to cross and no transfer to offload.
 *
 * <p>Using the dedicated transfer engine where it helps frees the graphics queue to keep drawing while the
 * upload happens. Using it where it does not help adds a queue crossing and a synchronisation for a copy that
 * was already local.
 *
 * <h2>Never the transfer queue for an on-device copy</h2>
 *
 * <p>A copy between two buffers that are both in video memory has nothing to transfer, and routing it through
 * the transfer engine means submitting to a second queue, waiting for it, and synchronising back -- all for a
 * memcpy the graphics queue could have done inline. It is slower, and it is the kind of mistake that is
 * invisible in a correctness test because the data arrives either way.
 *
 * <h2>An APU has no link to cross</h2>
 *
 * <p>A Vega 8, a 680M and a 780M share system memory with the shader cores, so an upload is not a transfer
 * between two memories and the dedicated engine has nothing to do that the graphics queue does not. Choosing
 * by whether the memory is unified rather than by whether a transfer queue exists is what makes this correct
 * on the hardware this project targets most, because those are exactly the parts where the obvious rule is
 * wrong.
 *
 * <h2>The engine is a property of the device</h2>
 *
 * <p>Which queue to use depends on the card and on where the memory lives, both of which are fixed once the
 * device is created. Deciding per copy would repeat a choice that cannot change, and could put two copies that
 * feed the same buffer on different queues, which would need ordering between them that a single queue gives
 * for free.
 *
 * <p>Thread safety: the choice is made at device creation, on the thread that creates the device.
 *
 * <p>Reference: AMD's Vulkan guidance and the RDNA performance guide describe the dedicated SDMA engines and
 * when they apply; this project's own architecture notes in docs/notes/07 record the rule against using the
 * transfer queue for on-device copies. This is an independent implementation of that reasoning.
 */
public final class TransferQueueSelector {

    /** Megabytes per second a PCI Express 4.0 x16 link carries. */
    public static final double PCIE4_X16_MBPS = 32_000.0;

    /** Megabytes per second a unified APU's memory carries, shared with the CPU. */
    public static final double APU_MBPS = 90_000.0;

    /** Milliseconds one queue crossing costs, in submission and synchronisation. */
    public static final double MS_CROSSING = 0.03;

    /** How a copy is served. */
    public enum Route {
        /** On the graphics queue, inline. */
        GRAPHICS,
        /** On the dedicated transfer engine. */
        TRANSFER
    }

    /**
     * Which route a copy should take.
     *
     * @param sourceInVideoMemory whether the source is already in video memory
     * @param destinationInVideoMemory whether the destination is
     * @param unifiedMemory whether the device shares memory with the CPU, as an APU does
     */
    public static Route choose(boolean sourceInVideoMemory, boolean destinationInVideoMemory,
            boolean unifiedMemory) {
        if (sourceInVideoMemory && destinationInVideoMemory) {
            // A copy between two buffers that are both in video memory has nothing to transfer, and routing
            // it through the transfer engine means submitting to a second queue, waiting for it and
            // synchronising back -- all for a memcpy the graphics queue could have done inline.
            return Route.GRAPHICS;
        }
        if (unifiedMemory) {
            // A Vega 8, a 680M and a 780M share system memory with the shader cores, so an upload is not a
            // transfer between two memories and the dedicated engine has nothing to do that the graphics
            // queue does not.
            return Route.GRAPHICS;
        }
        return Route.TRANSFER;
    }

    /**
     * Milliseconds the copy takes, end to end.
     *
     * <p>The dedicated engine does not make the bytes move faster -- the link's rate is the link's rate --
     * so this is the same for both routes apart from the crossing, which the transfer route pays.
     *
     * @param route how the copy is served
     * @param bytes how many bytes it moves
     * @param unifiedMemory whether the device shares memory with the CPU
     */
    public static double msCost(Route route, long bytes, boolean unifiedMemory) {
        if (route == null) {
            throw new IllegalArgumentException("a route is required");
        }
        if (bytes < 0) {
            throw new IllegalArgumentException("a negative size: " + bytes);
        }
        double mb = bytes / (1024.0 * 1024.0);
        double bandwidth = unifiedMemory ? APU_MBPS : PCIE4_X16_MBPS;
        double crossing = route == Route.TRANSFER ? MS_CROSSING : 0.0;
        return mb / (bandwidth / 1000.0) + crossing;
    }

    /**
     * Milliseconds the graphics queue is occupied by the copy.
     *
     * <p>This is the number the choice is actually about. The dedicated engine's benefit is not that the copy
     * is faster but that the graphics queue is free to keep drawing while it happens, so what matters is how
     * much of that queue the copy takes. On the transfer route that is only the submission and the
     * synchronisation; on the graphics route it is the whole transfer.
     */
    public static double msGraphicsQueueCost(Route route, long bytes, boolean unifiedMemory) {
        if (route == null) {
            throw new IllegalArgumentException("a route is required");
        }
        if (bytes < 0) {
            throw new IllegalArgumentException("a negative size: " + bytes);
        }
        if (route == Route.TRANSFER) {
            return MS_CROSSING;
        }
        double mb = bytes / (1024.0 * 1024.0);
        double bandwidth = unifiedMemory ? APU_MBPS : PCIE4_X16_MBPS;
        return mb / (bandwidth / 1000.0);
    }

    /**
     * Whether the dedicated engine is worth using for a copy of this size.
     *
     * <p>It is not always. The engine's benefit is the graphics-queue time it frees, and the crossing costs a
     * fixed amount, so a small copy pays the crossing to free very little and comes out behind. On a discrete
     * card there is a size below which inline is the better trade.
     *
     * @param bytes how many bytes the copy moves
     * @param unifiedMemory whether the device shares memory with the CPU
     */
    public static boolean transferEngineWorthIt(long bytes, boolean unifiedMemory) {
        if (bytes < 0) {
            throw new IllegalArgumentException("a negative size: " + bytes);
        }
        if (unifiedMemory) {
            // There is no link to cross and the copy is already local, so the engine never helps -- which is
            // the case for the Vega 8, the 680M and the 780M this project targets.
            return false;
        }
        return msGraphicsQueueCost(Route.TRANSFER, bytes, false)
                < msGraphicsQueueCost(Route.GRAPHICS, bytes, false);
    }

    /**
     * The size below which the crossing is not worth it on a discrete card.
     *
     * <p>Useful because it is a fixed number for a given link, and a caller uploading many small meshes can
     * batch them until they pass it rather than paying the crossing for each.
     */
    public static long worthItAboveBytes() {
        // The crossing costs MS_CROSSING of graphics-queue time, which at the link's rate is this many bytes
        // of transfer the graphics queue would otherwise have spent.
        return (long) (MS_CROSSING * (PCIE4_X16_MBPS / 1000.0) * 1024.0 * 1024.0);
    }
}
