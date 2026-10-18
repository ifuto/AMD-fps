package net.amdfaster.vk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The numbers here come from the device, so what is under test is the arithmetic that turns them into
 * something a frame time can be computed from -- and the cases where a driver reports something that cannot
 * be used at all.
 */
class TimestampSupportTest {

    @Test
    void aTypicalCounterWrapsInAboutThirtySixMinutes() {
        // 32 ns per tick is a 31.25 MHz counter, which is what desktop drivers commonly report, and 36 bits
        // is the width that goes with it. This is the case the wraparound correction exists for: it is long
        // enough to look like it never happens and short enough to happen in a play session.
        TimestampSupport support = TimestampSupport.of(32.0f, 36);

        assertTrue(support.usable());
        assertEquals(32.0f, support.periodNanos());
        assertEquals(36, support.validBits());
        assertEquals(2_199_023_255_552L, support.wrapNanos(), "2^36 ticks at 32 ns");
        assertEquals(2_199L, support.wrapNanos() / 1_000_000_000L, "about 36 minutes");
    }

    @Test
    void aFasterCounterWrapsSooner() {
        // A 1 ns tick is a GHz counter. Same bit count, sixty-odd seconds of headroom, so a caller that
        // assumed the 36-minute figure would keep measurements it should have dropped.
        TimestampSupport support = TimestampSupport.of(1.0f, 36);

        assertEquals(68_719_476_736L, support.wrapNanos());
        assertEquals(68L, support.wrapNanos() / 1_000_000_000L);
    }

    @Test
    void aFrameLengthMeasurementAlwaysFits() {
        TimestampSupport support = TimestampSupport.of(32.0f, 36);

        assertTrue(support.canMeasure(0L), "an idle frame is measurable");
        assertTrue(support.canMeasure(16_600_000L), "one 60 fps frame");
        assertTrue(support.canMeasure(2_199_023_255_551L), "one nanosecond short of a wrap");
        assertFalse(support.canMeasure(2_199_023_255_552L), "exactly one wrap is ambiguous");
        assertFalse(support.canMeasure(-1L), "a negative duration came from a bad delta");
    }

    @Test
    void theMaskIsAllOnesAtSixtyFourBits() {
        // (1 << 64) - 1 overflows to zero, and masking with zero would discard every reading rather than
        // keeping all of it. Two's complement is already the modulo at that width.
        assertEquals(-1L, TimestampSupport.of(1.0f, 64).mask());
        assertEquals(Long.MAX_VALUE, TimestampSupport.of(1.0f, 63).mask());
        assertEquals(68_719_476_735L, TimestampSupport.of(1.0f, 36).mask());
        assertEquals(1L, TimestampSupport.of(1.0f, 1).mask(), "a one-bit counter only says odd or even");
    }

    @Test
    void aSixtyFourBitWrapIsCappedRatherThanWrapped() {
        // The true span at 64 bits does not fit in a long of nanoseconds. Wrapping it would say the counter
        // expires almost immediately, which would drop every measurement.
        assertEquals(Long.MAX_VALUE, TimestampSupport.of(1.0f, 64).wrapNanos());
        assertEquals(Long.MAX_VALUE, TimestampSupport.of(32.0f, 64).wrapNanos());
        assertTrue(TimestampSupport.of(1.0f, 64).canMeasure(16_600_000L));
    }

    @Test
    void zeroBitsMeansTheQueueFamilyHasNoTimestamps() {
        // Legal in Vulkan: a transfer-only family may report zero. It has to read as "measure nothing"
        // rather than as a counter that wraps on every tick.
        TimestampSupport none = TimestampSupport.of(32.0f, 0);

        assertFalse(none.usable());
        assertEquals(0L, none.mask());
        assertEquals(0L, none.wrapNanos());
        assertFalse(none.canMeasure(16_600_000L), "nothing is measurable without a counter");
        assertTrue(none.toString().contains("no timestamp support"));
    }

    @Test
    void aPeriodThatCannotConvertTicksIsUnusable() {
        // A zero period would divide by zero on the way back to ticks, and a NaN would silently poison
        // every figure downstream -- it compares false against everything, so a bounds check never fires.
        assertFalse(TimestampSupport.of(0.0f, 36).usable());
        assertFalse(TimestampSupport.of(-1.0f, 36).usable());
        assertFalse(TimestampSupport.of(Float.NaN, 36).usable());
        assertFalse(TimestampSupport.of(Float.POSITIVE_INFINITY, 36).usable());
        assertFalse(TimestampSupport.of(32.0f, 65).usable(), "no counter is wider than a long");
        assertFalse(TimestampSupport.of(32.0f, -1).usable());
    }

    @Test
    void theSharedNoneConstantIsUnusable() {
        assertFalse(TimestampSupport.NONE.usable());
        assertEquals(0.0f, TimestampSupport.NONE.periodNanos());
        assertEquals(0, TimestampSupport.NONE.validBits());
    }

    @Test
    void valuesThatAreEqualCompareEqual() {
        // Kept in a context and handed to a query pool, so two reads of the same device have to agree.
        assertEquals(TimestampSupport.of(32.0f, 36), TimestampSupport.of(32.0f, 36));
        assertEquals(TimestampSupport.of(32.0f, 36).hashCode(), TimestampSupport.of(32.0f, 36).hashCode());
        assertFalse(TimestampSupport.of(32.0f, 36).equals(TimestampSupport.of(1.0f, 36)));
        assertFalse(TimestampSupport.of(32.0f, 36).equals(TimestampSupport.of(32.0f, 32)));
        assertFalse(TimestampSupport.of(32.0f, 36).equals(null));
        assertFalse(TimestampSupport.of(32.0f, 36).equals("32 ns"));
        assertEquals(TimestampSupport.of(32.0f, 36), TimestampSupport.of(32.0f, 36));
    }
}
