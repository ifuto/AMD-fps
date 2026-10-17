package net.amdfaster.perf;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueryPool;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_NOT_READY;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_64_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUERY_TYPE_TIMESTAMP;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateQueryPool;
import static org.lwjgl.vulkan.VK10.vkDestroyQueryPool;
import static org.lwjgl.vulkan.VK10.vkGetQueryPoolResults;
import static org.lwjgl.vulkan.VK12.vkResetQueryPool;
import static org.lwjgl.vulkan.VK10.vkCmdWriteTimestamp;

/**
 * Measures how long the GPU took, which is the fifth figure the benchmark has to report.
 *
 * <h2>Why CPU timing cannot answer this</h2>
 *
 * <p>Wrapping a draw call in {@code System.nanoTime()} measures how long it took to <i>submit</i> the draw,
 * which is microseconds and says nothing about how long the GPU then spent on it. A frame can submit in half
 * a millisecond and take twenty to render. The only way to know the second number is to ask the GPU, and the
 * way to ask is a timestamp query written into the command buffer itself.
 *
 * <h2>What the numbers mean</h2>
 *
 * <p>A timestamp is a counter reading, not a duration, and the counter's resolution is
 * {@code timestampPeriod} nanoseconds per tick -- a device property, not a constant. Thirty-two nanoseconds
 * is typical, but a build that assumed it would report the wrong time on hardware that differs, and would be
 * wrong by a factor rather than by a rounding error.
 *
 * <h2>Wraparound</h2>
 *
 * <p>The counter is only {@code timestampValidBits} wide, and 36 bits is common, so it wraps roughly every
 * thirty-five minutes at a gigahertz. A start reading near the top and an end reading just past zero would
 * subtract to a huge negative number, which as an unsigned duration reads as a frame that took years. The
 * delta is therefore taken modulo the counter width, which is correct in both directions and costs nothing.
 *
 * <h2>Why the results are read a frame late</h2>
 *
 * <p>A timestamp is not available until the GPU has executed the command that wrote it. Reading it in the
 * same frame means either blocking on {@code VK_QUERY_RESULT_WAIT_BIT} -- which stalls the CPU on the GPU and
 * destroys the overlap that makes a game fast -- or getting {@code VK_NOT_READY}. So the figure is reported
 * one frame behind, which for a per-frame statistic is not a loss: the number is still exactly the GPU time
 * of a real frame, just not the one currently being submitted.
 *
 * <p>The arithmetic is static and tested; the Vulkan resource is not, because there is no GPU here.
 */
public final class GpuTimestampQuery {

    /** Queries per frame: one written at the top of the frame, one at the bottom. */
    public static final int QUERIES_PER_FRAME = 2;

    private final VkDevice device;
    private final long pool;
    private final int slots;
    private final float timestampPeriod;
    private final int validBits;

    /** Last reported GPU time per slot, or -1 when a slot has not produced a reading yet. */
    private final long[] lastNanos;

    /**
     * Creates the query pool.
     *
     * @param device          the logical device
     * @param slots           how many frames in flight; each needs its own pair of queries, because a frame
     *                        still executing would otherwise overwrite the readings being read
     * @param timestampPeriod nanoseconds per tick, from the device limits
     * @param validBits       how many bits the counter is, from the device limits
     */
    public GpuTimestampQuery(VkDevice device, int slots, float timestampPeriod, int validBits) {
        if (device == null) {
            throw new IllegalArgumentException("a device is required");
        }
        if (slots <= 0) {
            throw new IllegalArgumentException("at least one slot is required: " + slots);
        }
        if (!(timestampPeriod > 0.0f) || Float.isNaN(timestampPeriod) || Float.isInfinite(timestampPeriod)) {
            throw new IllegalArgumentException("a positive timestamp period is required: " + timestampPeriod);
        }
        if (validBits <= 0 || validBits > 64) {
            throw new IllegalArgumentException("valid bits outside 1..64: " + validBits);
        }
        this.device = device;
        this.slots = slots;
        this.timestampPeriod = timestampPeriod;
        this.validBits = validBits;
        this.lastNanos = new long[slots];
        java.util.Arrays.fill(this.lastNanos, -1L);

        long handle = 0L;
        try (MemoryStack stack = stackPush()) {
            VkQueryPoolCreateInfo createInfo = VkQueryPoolCreateInfo.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_QUERY_POOL)
                    .queryType(VK_QUERY_TYPE_TIMESTAMP)
                    .queryCount(slots * QUERIES_PER_FRAME);
            VkQueryPool out = VkQueryPool.calloc(stack);
            int result = vkCreateQueryPool(device, createInfo, null, out);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateQueryPool failed: " + result);
            }
            handle = out.get();
        }
        this.pool = handle;
    }

    /** The query pool handle, for recording into. */
    public long pool() {
        return this.pool;
    }

    /** How many frame slots this pool covers. */
    public int slots() {
        return this.slots;
    }

    /**
     * Writes the start-of-frame timestamp.
     *
     * <p>{@code ALL_COMMANDS} rather than {@code TOP_OF_PIPE}, because a stage flag that excludes work
     * actually submitted would measure less than the frame and report a GPU time that looks better than it
     * is -- the one direction a benchmark must not err in.
     *
     * @param commandBuffer the buffer being recorded
     * @param slot          which frame slot
     */
    public void writeStart(long commandBuffer, int slot) {
        checkSlot(slot);
        vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                wrap(this.pool), slot * QUERIES_PER_FRAME);
    }

    /**
     * Writes the end-of-frame timestamp.
     *
     * @param commandBuffer the buffer being recorded
     * @param slot          which frame slot
     */
    public void writeEnd(long commandBuffer, int slot) {
        checkSlot(slot);
        vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                wrap(this.pool), slot * QUERIES_PER_FRAME + 1);
    }

    /**
     * Reads a slot's GPU time, once the frame that wrote it has finished.
     *
     * <p>Returns -1 when the result is not ready, which is the normal case for the frame currently being
     * submitted. A caller has to treat -1 as "no reading" and not as zero, because zero would report the
     * GPU as free.
     *
     * @param slot which frame slot
     * @return the GPU time in nanoseconds, or -1 when not yet available
     */
    public long readNanos(int slot) {
        checkSlot(slot);
        try (MemoryStack stack = stackPush()) {
            LongBuffer out = stack.mallocLong(QUERIES_PER_FRAME);
            int result = vkGetQueryPoolResults(this.device, wrap(this.pool),
                    slot * QUERIES_PER_FRAME, QUERIES_PER_FRAME, out, Long.BYTES, VK_QUERY_RESULT_64_BIT);
            if (result == VK_NOT_READY) {
                return -1L;
            }
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vkGetQueryPoolResults failed: " + result);
            }
            long elapsed = tickDelta(out.get(0), out.get(1), this.validBits);
            long nanos = toNanos(elapsed, this.timestampPeriod);
            this.lastNanos[slot] = nanos;
            // Reset so the next frame using this slot does not read a stale pair. Resetting after the read
            // rather than before the write keeps a frame that never finishes from leaving a half-written
            // pair behind.
            vkResetQueryPool(this.device, wrap(this.pool),
                    slot * QUERIES_PER_FRAME, QUERIES_PER_FRAME);
            return nanos;
        }
    }

    /** The most recent reading for a slot, or -1 when it has not produced one. */
    public long lastNanos(int slot) {
        checkSlot(slot);
        return this.lastNanos[slot];
    }

    /**
     * The mean GPU time across slots that have produced a reading.
     *
     * <p>Zero when no slot has, rather than a mean of the -1 sentinels, which would report a negative
     * duration.
     */
    public long meanNanos() {
        long total = 0L;
        int counted = 0;
        for (long value : this.lastNanos) {
            if (value >= 0) {
                total += value;
                counted++;
            }
        }
        return counted == 0 ? 0L : total / counted;
    }

    /** Releases the pool. */
    public void close() {
        if (this.pool != 0L) {
            vkDestroyQueryPool(this.device, wrap(this.pool), null);
        }
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= this.slots) {
            throw new IllegalArgumentException("slot " + slot + " outside " + this.slots);
        }
    }

    /**
     * Re-wraps a handle for LWJGL, which wants a {@code VkQueryPool} rather than a raw pointer.
     *
     * <p>Kept in one place because doing it inline at four call sites is four chances to get it wrong, and
     * a wrong wrapper here is a crash inside the driver with no Java stack frame to explain it.
     */
    private static VkQueryPool wrap(long handle) {
        return new VkQueryPool(handle, null);
    }

    /**
     * The elapsed ticks between two counter readings, modulo the counter width.
     *
     * <p>Modulo rather than subtraction, because the counter is only {@code validBits} wide and wraps. A
     * start near the top and an end just past zero subtract to a large negative number, which read as an
     * unsigned duration is a frame that took years.
     *
     * @param start     the earlier reading
     * @param end       the later reading
     * @param validBits how many bits the counter is
     * @return the elapsed ticks, always non-negative
     */
    public static long tickDelta(long start, long end, int validBits) {
        if (validBits <= 0 || validBits > 64) {
            throw new IllegalArgumentException("valid bits outside 1..64: " + validBits);
        }
        if (validBits == 64) {
            // A 64-bit mask does not fit in a long, and at that width the plain difference is already the
            // modulo -- two's complement subtraction wraps at exactly 2^64.
            return end - start;
        }
        long mask = (1L << validBits) - 1L;
        return (end - start) & mask;
    }

    /**
     * Converts ticks to nanoseconds.
     *
     * @param ticks           elapsed ticks
     * @param timestampPeriod nanoseconds per tick, from the device limits
     * @return the duration in nanoseconds
     */
    public static long toNanos(long ticks, float timestampPeriod) {
        if (ticks < 0) {
            throw new IllegalArgumentException("a negative tick count: " + ticks);
        }
        if (!(timestampPeriod > 0.0f) || Float.isNaN(timestampPeriod) || Float.isInfinite(timestampPeriod)) {
            throw new IllegalArgumentException("a positive timestamp period is required: " + timestampPeriod);
        }
        return Math.round((double) ticks * timestampPeriod);
    }
}
