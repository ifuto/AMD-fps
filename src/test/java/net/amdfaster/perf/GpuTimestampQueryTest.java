package net.amdfaster.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * The arithmetic behind GPU timestamp queries.
 *
 * <p>Only the arithmetic is tested. The Vulkan resource needs a device, and there is none here, so the pool
 * creation and the result reads are exercised nowhere -- which is worth saying plainly rather than leaving a
 * green build to imply otherwise.
 *
 * <p>Every figure was printed by a model of the same arithmetic before the test was written.
 */
class GpuTimestampQueryTest {

    private static final long SPAN_36 = 1L << 36;

    @Test
    void anOrdinaryDeltaIsPlainSubtraction() {
        assertEquals(4000L, GpuTimestampQuery.tickDelta(1000L, 5000L, 36));
        assertEquals(0L, GpuTimestampQuery.tickDelta(5000L, 5000L, 36), "no time passed");
    }

    @Test
    void aCounterThatWrapsStillGivesTheShortInterval() {
        // 36 bits wraps roughly every thirty-five minutes at a gigahertz, so this happens in a long
        // session rather than being a theoretical case. Without the modulo the start reads as being after
        // the end and the duration comes out as most of the counter's range.
        long start = SPAN_36 - 1000L;
        long end = 500L;

        assertEquals(1500L, GpuTimestampQuery.tickDelta(start, end, 36), "across the wrap");
    }

    @Test
    void aSixtyFourBitCounterUsesThePlainDifference() {
        // A 64-bit mask does not fit in a long, and two's complement subtraction already wraps at 2^64,
        // so the modulo and the plain difference are the same operation at that width.
        assertEquals(4000L, GpuTimestampQuery.tickDelta(1000L, 5000L, 64));
        assertEquals(1500L, GpuTimestampQuery.tickDelta(Long.MAX_VALUE - 999L, Long.MIN_VALUE + 500L, 64),
                "and wraps the same way");
    }

    @Test
    void ticksConvertUsingTheDevicesOwnPeriod() {
        // 32 ns per tick is typical, but it is a device property and not a constant.
        assertEquals(128_000L, GpuTimestampQuery.toNanos(4000L, 32.0f));
        assertEquals(48_000L, GpuTimestampQuery.toNanos(1500L, 32.0f));
        assertEquals(32_000_000L, GpuTimestampQuery.toNanos(1_000_000L, 32.0f), "32 ms");
        assertEquals(1_000_000L, GpuTimestampQuery.toNanos(1_000_000L, 1.0f), "a 1 ns tick is 1:1");
        assertEquals(1_000_000L, GpuTimestampQuery.toNanos(31_250L, 32.0f), "1 ms");
        assertEquals(16_600_000L, GpuTimestampQuery.toNanos(518_750L, 32.0f), "a 60 fps frame");
    }

    @Test
    void zeroTicksIsZeroTime() {
        assertEquals(0L, GpuTimestampQuery.toNanos(0L, 32.0f));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> GpuTimestampQuery.tickDelta(0L, 1L, 0), "a counter of no bits is not a counter");
        assertThrows(IllegalArgumentException.class,
                () -> GpuTimestampQuery.tickDelta(0L, 1L, 65), "nor one wider than a long");
        assertThrows(IllegalArgumentException.class,
                () -> GpuTimestampQuery.toNanos(-1L, 32.0f), "a negative tick count");
        assertThrows(IllegalArgumentException.class,
                () -> GpuTimestampQuery.toNanos(10L, 0.0f), "a period of zero would divide by zero later");
        assertThrows(IllegalArgumentException.class,
                () -> GpuTimestampQuery.toNanos(10L, Float.NaN), "and an unknown period is not a duration");
    }
}
