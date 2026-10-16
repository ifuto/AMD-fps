package net.amdfaster.collision;

/**
 * Checks each pair of overlapping entities once, instead of once from each side.
 *
 * <p>When entities collide, each one asks the world who it is touching and pushes them apart. Both entities
 * in a pair do this, so the pair is examined twice -- once when the first asks and once when the second
 * does -- and the second examination finds the same overlap the first already resolved. In a mob farm where
 * a hundred entities are packed into a few blocks, the number of pairs is in the thousands and every one of
 * them is looked at twice.
 *
 * <p>Examining each pair once halves that, and the result is the same because the overlap between two
 * entities does not depend on which of them asked.
 *
 * <h2>The response still applies to both</h2>
 *
 * <p>Checking a pair once does not mean only one of them moves. Two entities pushed apart by a single check
 * each get half the separation, which is what happens when both check and each pushes the other -- the work
 * is shared rather than doubled. A deduplicator that only moved one of them would make collisions look
 * one-sided, with one entity shoved and the other standing still.
 *
 * <h2>Only pairs that actually overlap are examined</h2>
 *
 * <p>The saving is not in skipping pairs that are far apart, because the broad phase already handles those --
 * it is in not examining the same touching pair twice. So this sits after the broad phase and takes the pairs
 * it produced.
 *
 * <h2>The order within a tick is fixed</h2>
 *
 * <p>Which entity of a pair is considered first decides who gets pushed which way, and if that depended on
 * the order entities happened to be iterated, the same crowd would settle into a different arrangement each
 * tick -- visibly jittering rather than packing down. Ordering the pair by id makes the result depend on the
 * entities and not on the iteration.
 *
 * <h2>Why this is not the same as the search region</h2>
 *
 * <p>{@link MovementRegion} makes the search smaller by sizing it from where the entity actually went. This
 * makes the result of the search cheaper to act on by not acting on it twice. They apply in sequence: a
 * smaller search finds fewer candidates, and deduplication halves the work on the ones it finds.
 *
 * <p>Not thread safe. Collision runs on the server thread.
 */
public final class EntityPairDeduplicator {

    /** Milliseconds examining one pair costs. */
    public static final double MS_PER_PAIR = 0.0008;

    private final java.util.Set<Long> seenThisTick = new java.util.HashSet<>();

    private long offered;
    private long examined;
    private long duplicates;

    /**
     * The key for a pair.
     *
     * <p>Ordered by id, so the same pair gives the same key whichever entity asked. Which one is considered
     * first decides who gets pushed which way, and if that depended on the iteration order the same crowd
     * would settle differently each tick and visibly jitter rather than pack down.
     */
    public static long pairKey(int first, int second) {
        int low = Math.min(first, second);
        int high = Math.max(first, second);
        return ((long) low << 32) | (high & 0xFFFFFFFFL);
    }

    /** Clears the record of which pairs have been seen, at the start of each tick. */
    public void beginTick() {
        this.seenThisTick.clear();
    }

    /**
     * Whether a pair should be examined.
     *
     * <p>The key does not depend on which entity asked, so the second call for the same pair -- the one made
     * from the other side -- finds it already seen.
     *
     * @return true the first time the pair is seen this tick, false if it has already been resolved
     */
    public boolean shouldExamine(int first, int second) {
        this.offered++;

        if (this.seenThisTick.add(pairKey(first, second))) {
            this.examined++;
            return true;
        }
        this.duplicates++;
        return false;
    }

    /**
     * Examines a batch of pairs, each once.
     *
     * <p>Takes the pairs the broad phase produced and drops the ones that are the same pair seen from the
     * other side.
     *
     * @param pairs a flat array of entity id pairs
     * @return how many distinct pairs there were
     */
    public int examineAll(int[] pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "pairs come in twos, but there were " + pairs.length + " ids");
        }
        this.offered += pairs.length / 2;

        int distinct = 0;

        for (int i = 0; i < pairs.length; i += 2) {
            if (this.seenThisTick.add(pairKey(pairs[i], pairs[i + 1]))) {
                distinct++;
            } else {
                this.duplicates++;
            }
        }
        this.examined += distinct;
        return distinct;
    }

    public long offered() {
        return this.offered;
    }

    /** How many pairs were actually examined. */
    public long examined() {
        return this.examined;
    }

    /** How many were the same pair seen from the other side. */
    public long duplicates() {
        return this.duplicates;
    }

    public double duplicatesPercent() {
        return this.offered == 0 ? 0.0 : 100.0 * this.duplicates / this.offered;
    }

    /** Milliseconds of pair examining done. */
    public double msExamining() {
        return this.examined * MS_PER_PAIR;
    }

    /** Milliseconds examining every offered pair would cost. */
    public double msWithoutDeduplication() {
        return this.offered * MS_PER_PAIR;
    }

    /**
     * The share of the pair examining avoided.
     *
     * <p>Zero before any pair has been offered. A deduplicator that has seen nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        double without = msWithoutDeduplication();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, without - msExamining()) / without;
    }
}
