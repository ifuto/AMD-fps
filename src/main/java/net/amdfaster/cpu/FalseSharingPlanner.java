package net.amdfaster.cpu;

/**
 * Decides how a counter that several threads write should be laid out, and says what the layout costs
 * when it is wrong.
 *
 * <p>Two threads writing different fields that happen to sit in the same cache line are not sharing
 * anything logically, but the line is the unit the coherence protocol moves. Every write invalidates the
 * other core's copy, so the line ping-pongs between them and each write costs a coherence round trip
 * instead of an L1 hit. Measured, that is not a small constant: the same two counters adjacent rather than
 * padded run 5.0 to 6.1 times slower across independent measurements, and a genuinely shared counter that
 * both threads hammer reaches 9.7. An uncontended L1 write is around a nanosecond and a line bounced
 * between cores is 40 to 100, which is where the ratio comes from.
 *
 * <p>What makes it worth a decision rather than a rule is that the fix is not free and one of the two
 * usual fixes is not available here.
 *
 * <p>{@code @Contended} is the clean answer -- the JVM inserts the padding itself, sized for the target
 * architecture -- but HotSpot restricts it to JDK internal classes unless the process starts with
 * {@code -XX:-RestrictContended}, and a mod cannot pass JVM flags to the process it is loaded into. So
 * {@link #contendedAnnotationUsable()} is false and the remaining options are padding by hand, which
 * costs memory and is wrong on any architecture whose line is wider than the padding assumed, or
 * {@link java.util.concurrent.atomic.LongAdder}, whose cells are already annotated internally and so
 * already isolated. That is why the recommendation here lands on a striped counter rather than on a
 * padding width.
 *
 * <p>And the striped counter has its own cost, which is the reason this is a decision. It spreads writes
 * across cells to stop them contending, and reading the total then has to walk every cell. A counter
 * written often and read rarely is a clear win -- under contention a striped counter has been measured at
 * 69 times the throughput of a synchronised one -- but a counter read as often as it is written is a
 * different question, and for a maximum there is no striping at all, since a maximum cannot be
 * accumulated and has to be contended for.
 *
 * @see <a href="https://cong-or.xyz/false-sharing-cache-lines">The Slowdown That Doesn't Show Up in
 *      Profiles -- 50M increments per thread on Zen 4, 924 ms contended against 184 ms padded for the same
 *      work, 56 bytes of padding between the fields</a>
 * @see <a href="https://harrisonsec.com/blog/cache-miss-tlb-false-sharing-coretracer/">TLB Miss, Cache Miss
 *      and False Sharing -- 841 ms padded, 4,284 ms with two adjacent fields, 8,118 ms with both threads on
 *      one shared int</a>
 * @see <a href="https://www.baeldung.com/java-false-sharing-contended">A Guide to False Sharing and
 *      {@code @Contended} -- the annotation adds 128 bytes of padding by default and is restricted to
 *      internal classes without {@code -XX:-RestrictContended}</a>
 * @see <a href="https://salivity.github.io/java/article/how-long-adder-minimizes-contention-in-java">How
 *      LongAdder Minimizes Contention -- its {@code Cell} is annotated {@code @Contended}, so each cell
 *      occupies an isolated cache line</a>
 */
public final class FalseSharingPlanner {

    /** Bytes in the cache line the coherence protocol moves as a unit. */
    public static final int CACHE_LINE_BYTES = 64;

    /**
     * Bytes {@code @Contended} pads by, which is twice the common line size.
     *
     * <p>Double rather than exact because the hardware prefetcher can pull two adjacent lines together, so
     * padding by one line width can leave the neighbour's line being fetched anyway.
     */
    public static final int CONTENDED_PADDING_BYTES = 128;

    /**
     * Measured slowdown from two counters sharing a line, against the same two counters padded apart.
     *
     * <p>The middle of a 5.0 to 6.1 range measured independently on several architectures. Worth carrying
     * as a number because it is what decides whether a given counter is worth isolating at all: a counter
     * written once a second does not need it.
     */
    public static final double MEASURED_FALSE_SHARING_SLOWDOWN = 5.0;

    /** Measured slowdown when threads genuinely contend for one counter, rather than sharing a line. */
    public static final double MEASURED_TRUE_CONTENTION_SLOWDOWN = 9.7;

    private FalseSharingPlanner() {
    }

    /** How a counter written from several threads should be laid out. */
    public enum Layout {
        /**
         * A striped counter. Writes spread across internally padded cells, so no two threads touch the
         * same line, and the total is summed on read.
         */
        STRIPED,
        /**
         * One padded field. Used where striping is not possible -- a maximum, say -- and the padding has
         * to be done by hand because the annotation is not available.
         */
        PADDED_SINGLE,
        /**
         * One unpadded field. Correct when only one thread writes it, or when it is written rarely enough
         * that a coherence round trip does not matter.
         */
        SHARED_LINE
    }

    /**
     * A counter's access pattern.
     *
     * @param writerThreads how many threads write it
     * @param writesPerTick writes per game tick across all of them
     * @param readAsOftenAsWritten whether the total is read about as often as it is written, which is what
     *                             makes striping a poor trade
     * @param accumulable          whether the value can be accumulated at all. A sum can; a maximum
     *                             cannot, and has to be contended for.
     */
    public record Counter(String name, int writerThreads, long writesPerTick, boolean readAsOftenAsWritten,
                          boolean accumulable) {
    }

    /** The chosen layout and the reasoning. */
    public record Decision(Layout layout, String reason) {
    }

    /**
     * Whether {@code @Contended} can be used.
     *
     * <p>It cannot. HotSpot restricts the annotation to JDK internal classes unless the JVM is started
     * with {@code -XX:-RestrictContended}, and a mod is loaded into a process whose flags it does not
     * control. Reporting this as a constant rather than probing keeps the reasoning honest: the padding
     * recommendation below is manual because it has to be, not because manual was preferred.
     */
    public static boolean contendedAnnotationUsable() {
        return false;
    }

    /** How many counters of a given size share one cache line, which is how many can interfere. */
    public static int countersPerLine(int fieldBytes) {
        require(fieldBytes > 0, "a field has a positive size");
        return CACHE_LINE_BYTES / fieldBytes;
    }

    /**
     * Chooses a layout.
     *
     * <p>The order of the checks is the order the costs come in. A counter only one thread writes cannot
     * false-share with itself, so nothing is owed to it. A counter that cannot be accumulated cannot be
     * striped, so it gets a padded field and the padding is manual. A counter whose total is read as often
     * as it is written is a poor fit for striping, because the read then walks every cell -- and that is
     * reported as a trade rather than quietly resolved in favour of one side.
     */
    public static Decision choose(Counter counter) {
        require(counter != null, "a counter is needed");
        require(counter.name() != null, "a counter needs a name");
        require(counter.writerThreads() >= 1, "at least one thread writes it");
        require(counter.writesPerTick() >= 0, "a write count must not be negative");

        if (counter.writerThreads() == 1) {
            return new Decision(Layout.SHARED_LINE,
                    "only one thread writes it, so there is nothing to false-share with");
        }
        if (counter.writesPerTick() == 0) {
            return new Decision(Layout.SHARED_LINE,
                    "it is not written during a tick, so a coherence round trip does not land in one");
        }
        if (!counter.accumulable()) {
            return new Decision(Layout.PADDED_SINGLE,
                    "a maximum cannot be accumulated so it cannot be striped; it needs its own line, and "
                            + "the padding has to be manual because @Contended needs a JVM flag a mod "
                            + "cannot pass");
        }
        if (counter.readAsOftenAsWritten()) {
            return new Decision(Layout.SHARED_LINE,
                    "the total is read about as often as it is written, and a striped counter walks every "
                            + "cell on each read, which gives back what the striping saved");
        }
        return new Decision(Layout.STRIPED,
                counter.writerThreads() + " writers and " + counter.writesPerTick()
                        + " writes a tick, read rarely; striping keeps them off each other's lines at the "
                        + "cost of summing the cells on read");
    }

    /**
     * Bytes of manual padding that put a field on its own line.
     *
     * <p>The contended width rather than the line width, for the prefetcher reason in
     * {@link #CONTENDED_PADDING_BYTES}. Padding by exactly one line is enough for the coherence protocol
     * and not enough for the prefetcher.
     *
     * @param fieldBytes the field's own size
     */
    public static int manualPaddingBytes(int fieldBytes) {
        require(fieldBytes > 0, "a field has a positive size");
        require(fieldBytes <= CONTENDED_PADDING_BYTES, "a field larger than the padding needs no padding");
        return CONTENDED_PADDING_BYTES - fieldBytes;
    }

    /**
     * Bytes a striped counter holds, which is the cost of striping.
     *
     * <p>Each cell is padded to the contended width, so a counter striped across eight threads holds a
     * kilobyte to count one number. That is still the right trade for a hot counter and the wrong one for
     * a cold one, which is what {@link #choose} is deciding.
     */
    public static long stripedBytes(int cells) {
        require(cells >= 1, "a striped counter has at least one cell");
        return (long) cells * CONTENDED_PADDING_BYTES;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
