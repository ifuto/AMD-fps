package net.amdfaster.gpu;

/**
 * Sizes and validates the {@code vkCmdDrawIndexedIndirectCount} call that the cull shader's output is
 * drawn with.
 *
 * <p>The cull shader already writes dense draw commands. Each surviving meshlet atomically takes a slot and
 * fills the five words of a {@code VkDrawIndexedIndirectCommand} -- index count, instance count, first
 * index, vertex offset, first instance -- and increments a separate visible count, so the buffer ends up
 * packed with no holes and the count says how many entries are real. That is the whole point of writing it
 * that way: the draw call reads the count from the device instead of the host having to read the cull
 * results back to find out how much survived, which would stall the pipeline every frame on a value that
 * is only known after the compute pass.
 *
 * <p>What was missing is the call itself and the arithmetic that has to be right before it. Two of the
 * constraints are the kind that fail as a validation error rather than as a wrong image, and one fails as
 * nothing at all, which makes it worth writing down.
 *
 * <p>The buffer has to hold the draws it claims. The rule is that
 * {@code stride x (maxDrawCount - 1) + offset + sizeof(command)} fits inside the buffer, and the
 * off-by-one in it is easy to get backwards: {@code maxDrawCount} draws occupy
 * {@code stride x (maxDrawCount - 1) + 20} bytes, not {@code stride x maxDrawCount}, because the last
 * command ends at its own end rather than a stride past it. Overstating the count is a validation error
 * before anything draws.
 *
 * <p>The count is clamped, not trusted. The device executes
 * {@code min(countInBuffer, maxDrawCount)}, so a {@code maxDrawCount} sized to the buffer is also the cap
 * on what can ever run -- which is the safety property, since a cull shader that went wrong and wrote a
 * huge count cannot make the host draw past the end of the buffer it allocated.
 *
 * <p>And the one that fails silently: without the {@code multiDrawIndirect} feature a draw count above one
 * is invalid. That is not a slow path, it is undefined, and it is the difference between
 * {@code vkCmdDrawIndexedIndirectCount} being usable at all and having to issue one draw per meshlet, which
 * is the thing this exists to avoid.
 *
 * @see <a href="https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdDrawIndexedIndirectCount.html">
 *      vkCmdDrawIndexedIndirectCount -- the count is the minimum of the buffer value and maxDrawCount, and
 *      the buffer must hold stride x (maxDrawCount - 1) + offset + sizeof(command)</a>
 * @see <a href="https://vkguide.dev/docs/gpudriven/draw_indirect/">Vulkan Guide, Draw Indirect -- culling
 *      is a compute shader that writes the commands, and the buffer can be read back by the draw</a>
 */
public final class IndirectCountDraw {

    /**
     * Bytes in one {@code VkDrawIndexedIndirectCommand}: five 32 bit words.
     *
     * <p>Index count, instance count, first index, vertex offset, first instance.
     */
    public static final int COMMAND_BYTES = 20;

    /**
     * The smallest legal stride.
     *
     * <p>The spec requires a multiple of four that is at least the command size, and 20 is both, so there
     * is no reason to pad. Padding to 24 would cost a fifth of the buffer for nothing: at 13,824 draws --
     * a twelve chunk render distance's worth of sections -- that is 55 KB more.
     */
    public static final int STRIDE_BYTES = 20;

    /** Words per command, which is how the shader indexes the buffer. */
    public static final int COMMAND_WORDS = 5;

    /** The count field's offset within the count buffer, in bytes. */
    public static final int COUNT_OFFSET_BYTES = 0;

    private IndirectCountDraw() {
    }

    /**
     * The validated parameters for one indirect count draw.
     *
     * @param maxDrawCount the cap passed to the call, which is also the most the device will execute
     * @param stride       bytes between successive commands
     * @param offset       byte offset of the first command
     * @param bytesNeeded  bytes the command buffer must hold
     */
    public record Call(int maxDrawCount, int stride, int offset, long bytesNeeded) {

        /** Whether the buffer is large enough for the count being claimed. */
        public boolean fitsIn(long bufferBytes) {
            return this.offset + this.bytesNeeded <= bufferBytes;
        }
    }

    /**
     * The most draws a buffer can hold.
     *
     * <p>The last command ends at {@code offset + stride x (n - 1) + 20}, so {@code n} commands need
     * {@code stride x (n - 1) + 20} bytes and not {@code stride x n}. Getting that backwards overstates
     * the count by one, which is a validation error at the draw rather than a slightly smaller batch.
     */
    public static int maxDrawCount(long bufferBytes, int stride, long offset) {
        require(stride >= COMMAND_BYTES, "a stride is at least one command");
        require(stride % 4 == 0, "a stride must be a multiple of 4");
        require(offset >= 0, "an offset must not be negative");
        require(offset % 4 == 0, "an offset must be a multiple of 4");
        long usable = bufferBytes - offset;
        if (usable < COMMAND_BYTES) {
            return 0;
        }
        return (int) ((usable - COMMAND_BYTES) / stride + 1);
    }

    /** Bytes needed for {@code drawCount} commands at a stride, which is what the buffer must hold. */
    public static long bytesFor(int drawCount, int stride) {
        require(drawCount >= 0, "a draw count must not be negative");
        require(stride >= COMMAND_BYTES, "a stride is at least one command");
        return drawCount == 0 ? 0 : (long) stride * (drawCount - 1) + COMMAND_BYTES;
    }

    /**
     * Builds a validated call.
     *
     * @param drawCount    how many draws the buffer may hold, which caps what the device will execute
     * @param bufferBytes  how large the command buffer is
     * @param offset       where the commands start
     * @return the call, or null when the buffer cannot hold even one command
     */
    public static Call prepare(int drawCount, long bufferBytes, int offset) {
        require(drawCount >= 0, "a draw count must not be negative");
        long needed = bytesFor(drawCount, STRIDE_BYTES);
        if (offset + needed > bufferBytes) {
            return null;
        }
        return new Call(drawCount, STRIDE_BYTES, offset, needed);
    }

    /**
     * How many draws actually execute.
     *
     * <p>The device takes the smaller of what the cull shader wrote and the cap, so a cull shader that
     * misbehaved and wrote an enormous count cannot make the host draw past the buffer it allocated. Worth
     * having as a method rather than a {@code Math.min} at the call site because the clamping is the safety
     * property and it should be visible that it is deliberate.
     */
    public static int executed(int countInBuffer, int maxDrawCount) {
        require(countInBuffer >= 0, "a count must not be negative");
        require(maxDrawCount >= 0, "a max must not be negative");
        return Math.min(countInBuffer, maxDrawCount);
    }

    /**
     * Whether the plain multi-draw path is available.
     *
     * <p>Without {@code multiDrawIndirect} a draw count above one is invalid, not merely unsupported, and
     * the fallback is one {@code vkCmdDrawIndexedIndirect} per meshlet -- which is the submission cost this
     * exists to remove. The feature is near universal on desktop and rare on mobile, so it has to be
     * checked rather than assumed.
     *
     * @param multiDrawIndirect the device feature
     * @param drawCount         how many draws would be issued
     */
    public static boolean multiDrawAllowed(boolean multiDrawIndirect, int drawCount) {
        require(drawCount >= 0, "a draw count must not be negative");
        return drawCount <= 1 || multiDrawIndirect;
    }

    /**
     * Whether the device's own limit on indirect draws is respected.
     *
     * <p>{@code maxDrawIndirectCount} is a physical device limit and can be as low as one, in which case
     * every draw has to be issued separately no matter what else is supported.
     */
    public static boolean withinDeviceLimit(int drawCount, int maxDrawIndirectCount) {
        require(drawCount >= 0, "a draw count must not be negative");
        require(maxDrawIndirectCount >= 1, "a device allows at least one indirect draw");
        return drawCount <= maxDrawIndirectCount;
    }

    /** The byte offset of command {@code index} within the command buffer. */
    public static long commandOffset(int index, int stride) {
        require(index >= 0, "a command index must not be negative");
        require(stride >= COMMAND_BYTES, "a stride is at least one command");
        return (long) index * stride;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
