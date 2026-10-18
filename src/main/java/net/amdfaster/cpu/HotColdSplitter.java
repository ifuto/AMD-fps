package net.amdfaster.cpu;

/**
 * Decides where a record should be split into the fields touched every frame and the ones that are not,
 * and says what the split is worth in cache lines.
 *
 * <p>A cache line is 64 bytes and is fetched whole. A loop that touches two fields of a 32 byte record
 * still pulls the other 30 bytes into L1 with it, so the number of records that fit in a line -- and
 * therefore the number of misses a walk over them costs -- is set by the record's whole size rather than
 * by the part the loop uses. Splitting the rarely touched fields out makes the hot record smaller, more of
 * them fit per line, and the walk over them misses less. The cold fields are still reachable, through an
 * index the hot record carries, and are paid for only on the rare access that needs them.
 *
 * <p>The measured size of the effect is worth stating because it is not marginal. Reducing a per-entity
 * working set from 56 bytes to 20 lowered L1D and L2 miss rates by 17 to 18 per cent and ran 20 per cent
 * faster at small entity counts, rising to twice as fast at large ones. A separate measurement of packing
 * pixels from two to four per cache line was 2.9 times faster on a random access pattern that defeats the
 * prefetcher. Neither is a constant factor you can get back by writing a tighter loop; both come from
 * moving fewer bytes.
 *
 * <p>The counterweight is real too and this does not pretend otherwise. Splitting means two places to keep
 * in step, and a hot record carrying an index into a cold table is a pointer chase on every access that
 * needs the cold half. Where both halves are wanted at similar frequency the split is pure overhead, which
 * is why the decision here is made on the ratio of accesses rather than assumed from the field count. A
 * type with twenty fields all touched every frame should stay one record.
 *
 * <p>One thing the planner refuses to do is claim a saving it has not computed. A split that takes a 32
 * byte record to 24 does not change the records per line -- four fit either way -- and the honest answer
 * there is that the split costs a second array and buys nothing. The threshold is the cache line, not the
 * byte count.
 *
 * @see <a href="https://gameprogrammingpatterns.com/data-locality.html">Game Programming Patterns, Data
 *      Locality -- hot/cold splitting: the hot piece is the main record so there is no pointer to chase,
 *      and the cold piece hangs off it</a>
 * @see <a href="https://www.diva-portal.org/smash/get/diva2:1578616/FULLTEXT01.pdf">Investigating the
 *      effect of implementing Data-Oriented Design -- a working set cut from 56 to 20 bytes per entity
 *      lowered L1D and L2 miss rates 17 to 18 per cent and improved performance 20 to 100 per cent</a>
 * @see <a href="https://amandeepsingh.dev/blog/memory-alignment-performance/">The Hidden Cost of Messy
 *      Structs -- two pixels per cache line against four was 2.92 times slower on a random access
 *      pattern</a>
 */
public final class HotColdSplitter {

    /** Bytes in the cache line everything here is measured against. */
    public static final int CACHE_LINE_BYTES = 64;

    private HotColdSplitter() {
    }

    /**
     * One field's cost and how often it is touched.
     *
     * @param bytes         the field's size including any padding it forces
     * @param accessedEveryTick whether the per-frame path reads it
     */
    public record Field(String name, int bytes, boolean accessedEveryTick) {
    }

    /**
     * What a split is worth.
     *
     * @param hotBytes      the hot record's size
     * @param coldBytes     the cold record's size, including the index the hot record needs to reach it
     * @param combinedBytes what one unsplit record would be
     * @param hotPerLine    how many hot records fit in a cache line
     * @param combinedPerLine how many unsplit records would
     * @param worthSplitting whether the split changes the records per line at all
     */
    public record Split(int hotBytes, int coldBytes, int combinedBytes, int hotPerLine, int combinedPerLine,
                        boolean worthSplitting) {

        /** How many more hot records fit per line, which is the whole benefit. */
        public int extraPerLine() {
            return this.hotPerLine - this.combinedPerLine;
        }

        /**
         * The fraction of a walk's cache lines the split removes.
         *
         * <p>Zero when the records per line did not change, which is the answer for a split that only
         * shuffles bytes around without crossing the line boundary.
         */
        public double linesAvoidedPercent() {
            if (this.combinedPerLine <= 0 || this.hotPerLine <= 0) {
                return 0.0;
            }
            double before = 1.0 / this.combinedPerLine;
            double after = 1.0 / this.hotPerLine;
            return 100.0 * (before - after) / before;
        }
    }

    /**
     * Plans a split.
     *
     * <p>Every field the per-frame path reads goes in the hot record, plus a 4 byte index into the cold
     * table when there is anything cold to reach. The index is an int rather than a reference because an
     * index into a parallel array keeps the cold table dense and lets the two be walked in step, where a
     * reference would scatter the cold records wherever the allocator put them.
     *
     * @param indexBytes bytes the hot record needs to reach its cold half; 4 for an int index, 0 when
     *                   there is nothing cold and so nothing to reach
     */
    public static Split plan(Field[] fields, int indexBytes) {
        require(fields != null, "fields must be given, even if empty");
        require(indexBytes >= 0, "an index size must not be negative");
        int hot = 0;
        int cold = 0;
        for (Field field : fields) {
            require(field != null, "a null field");
            require(field.name() != null, "a field needs a name");
            require(field.bytes() > 0, "a field has a positive size");
            if (field.accessedEveryTick()) {
                hot += field.bytes();
            } else {
                cold += field.bytes();
            }
        }
        int combined = hot + cold;
        // The index only exists to reach the cold half, so a record with nothing cold does not pay for it.
        int hotBytes = cold > 0 ? hot + indexBytes : hot;
        int hotPerLine = CACHE_LINE_BYTES / Math.max(1, hotBytes);
        int combinedPerLine = CACHE_LINE_BYTES / Math.max(1, combined);
        return new Split(hotBytes, cold, combined, hotPerLine, combinedPerLine,
                hotPerLine > combinedPerLine);
    }

    /**
     * Whether a split is worth making.
     *
     * <p>Only when it changes how many records fit in a cache line. A 32 byte record split to 24 still
     * fits four per line, so the split would add a second array to keep in step and buy exactly nothing --
     * and reporting a saving there would be reporting a byte count rather than a cache effect.
     */
    public static boolean worthSplitting(Field[] fields, int indexBytes) {
        return plan(fields, indexBytes).worthSplitting();
    }

    /**
     * The smallest hot record that still fits {@code perLine} records in a cache line.
     *
     * <p>The number to design against. Going below it buys nothing until the next line boundary, so a
     * field is worth moving to the cold half only if moving it crosses one.
     */
    public static int maxHotBytesFor(int perLine) {
        require(perLine >= 1, "at least one record must fit");
        return CACHE_LINE_BYTES / perLine;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
