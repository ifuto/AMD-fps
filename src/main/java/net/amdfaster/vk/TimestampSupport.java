package net.amdfaster.vk;

/**
 * The device's timestamp counter: how long one tick is, and how many bits the counter has.
 *
 * <p>Vulkan reports these per device and per queue family rather than fixing them, because they are not
 * constants. {@code timestampPeriod} is the number of nanoseconds in one tick of the counter, and it varies
 * between vendors and between driver versions; on real hardware it is commonly 32 ns, which is a 31.25 MHz
 * counter, but 1 ns parts exist. {@code timestampValidBits} is how many bits the counter actually has, and a
 * queue family that does not support timestamps at all reports zero.
 *
 * <p>Neither number can be assumed, and assuming either one is not a rounding error. A wrong period scales
 * every GPU figure by the ratio -- reporting 32 ns as 1 ns makes a 2 ms frame read as 64 ms -- and a wrong
 * bit count breaks the wraparound correction, which turns an occasional frame into a nonsense figure rather
 * than a slightly wrong one.
 *
 * <p>The wraparound matters more than it looks. At 36 bits and 32 ns the counter wraps roughly every 37
 * minutes, so a long play session crosses it, and a delta computed across a crossing is negative by the
 * width of the counter unless it is corrected. {@link #wrapNanos()} exists so a caller can decide whether a
 * measurement is short enough to trust rather than finding out after the fact.
 *
 * <p>This is a value with no Vulkan calls in it, so the arithmetic can be checked without a device.
 */
public final class TimestampSupport {

    /** The bit width most desktop drivers report, used only to describe a typical case in tests. */
    public static final int TYPICAL_VALID_BITS = 36;

    /** A support value that is not usable, for a device or queue family that reports no timestamps. */
    public static final TimestampSupport NONE = new TimestampSupport(0.0f, 0);

    private final float periodNanos;
    private final int validBits;

    private TimestampSupport(float periodNanos, int validBits) {
        this.periodNanos = periodNanos;
        this.validBits = validBits;
    }

    /**
     * Reads the reported values, keeping them even when they are unusable.
     *
     * <p>Deliberately does not throw. These numbers come from a driver, and a driver reporting something
     * strange is a condition to fall back from, not a reason to fail initialisation. The caller checks
     * {@link #usable()} and measures nothing if it is false.
     *
     * @param periodNanos nanoseconds per counter tick, from {@code VkPhysicalDeviceLimits.timestampPeriod}
     * @param validBits the counter width, from {@code VkQueueFamilyProperties.timestampValidBits}
     * @return the values as reported, usable or not
     */
    public static TimestampSupport of(float periodNanos, int validBits) {
        return new TimestampSupport(periodNanos, validBits);
    }

    /** Nanoseconds in one tick of the device's counter. */
    public float periodNanos() {
        return this.periodNanos;
    }

    /** How many bits the counter has, or zero if the queue family has no timestamp support. */
    public int validBits() {
        return this.validBits;
    }

    /**
     * Whether these values can produce a measurement at all.
     *
     * <p>A period that is zero, negative, NaN or infinite cannot convert ticks to time, and a bit count
     * outside 1..64 cannot be masked. Both are reported by real drivers: zero bits is how Vulkan says a
     * queue family does not support timestamps, which is legal for a transfer-only family.
     */
    public boolean usable() {
        return this.periodNanos > 0.0f
                && !Float.isNaN(this.periodNanos)
                && !Float.isInfinite(this.periodNanos)
                && this.validBits >= 1
                && this.validBits <= 64;
    }

    /**
     * The mask that folds a counter reading back into range.
     *
     * <p>At 64 bits this is all ones rather than {@code (1 << 64) - 1}, which overflows to zero and would
     * mask every reading away. Two's complement already is the modulo at that width, so -1 is the correct
     * mask and also the one that costs nothing.
     *
     * @return the mask, or zero if the values are not usable
     */
    public long mask() {
        if (!usable()) {
            return 0L;
        }
        return this.validBits == 64 ? -1L : (1L << this.validBits) - 1L;
    }

    /**
     * How long the counter runs before it wraps, in nanoseconds.
     *
     * <p>Capped at {@link Long#MAX_VALUE} rather than wrapped, because at 64 bits the true span is larger
     * than a {@code long} of nanoseconds can hold and a wrapped answer would say the counter wraps almost
     * immediately. A cap says "longer than anything worth measuring", which is the true statement.
     *
     * @return the wrap interval, or zero if the values are not usable
     */
    public long wrapNanos() {
        if (!usable()) {
            return 0L;
        }
        double span = Math.pow(2.0, this.validBits) * (double) this.periodNanos;
        if (!(span < (double) Long.MAX_VALUE)) {
            return Long.MAX_VALUE;
        }
        return (long) span;
    }

    /**
     * Whether a duration of this length can be measured without the counter wrapping inside it.
     *
     * <p>A delta taken across a wrap is still correct once it is masked, provided the true duration is
     * shorter than the wrap interval. Past that the measurement is genuinely ambiguous -- a two-wrap
     * interval and nothing at all read identically -- so the caller should drop it rather than report it.
     *
     * @param nanos the duration to be measured
     * @return true if it fits inside one wrap
     */
    public boolean canMeasure(long nanos) {
        return usable() && nanos >= 0L && nanos < wrapNanos();
    }

    @Override
    public String toString() {
        if (!usable()) {
            return "no timestamp support (period " + this.periodNanos + " ns, " + this.validBits + " bits)";
        }
        return this.periodNanos + " ns/tick, " + this.validBits + " bits, wraps every "
                + (wrapNanos() / 1_000_000_000L) + " s";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TimestampSupport that)) {
            return false;
        }
        return Float.compare(this.periodNanos, that.periodNanos) == 0 && this.validBits == that.validBits;
    }

    @Override
    public int hashCode() {
        return 31 * Float.hashCode(this.periodNanos) + this.validBits;
    }
}
