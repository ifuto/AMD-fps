package net.amdfaster.mob;

/**
 * Stops a crowded pen of one animal type from breeding further.
 *
 * <p>Every animal in love mode looks for a partner every tick, and the search is over the animals near it. In
 * a pen with a hundred cows that is a hundred searches, each of them scanning the same hundred animals, which
 * is ten thousand comparisons a tick for a pen that is already full. The animals keep trying, keep finding
 * each other, and keep producing offspring that make the next tick's search larger.
 *
 * <p>Refusing to breed past a density means the search stops growing, and the pen settles at a size the
 * machine can handle.
 *
 * <h2>No animal is removed</h2>
 *
 * <p>A cap that culled animals to make room would delete something the player put there, and a farm that
 * loses its stock is not a performance adjustment -- it is the player's work disappearing. What is prevented is
 * the next offspring, which is a thing that has not happened yet and that nobody has a claim on. The existing
 * herd is untouched however far over the cap it already is.
 *
 * <h2>The player's breeding attempt is refused, not faked</h2>
 *
 * <p>Feeding two animals at the cap does not produce a calf, and pretending otherwise -- hearts and no calf --
 * would be worse than refusing, because the player would keep feeding and keep seeing the same thing. The
 * refusal is the same as the game's own answer when an animal is not ready: nothing happens.
 *
 * <h2>The cap is per type and per area</h2>
 *
 * <p>A pen of a hundred cows and a pen of a hundred sheep next to each other are two pens, not one crowded
 * area, and capping them together would stop one because of the other. Nor is a hundred cows spread across a
 * field the same problem as a hundred in a pen: the cost is the search, and the search is local. So the count
 * is per type within the area the search actually covers.
 *
 * <h2>Why this is a cap and not a slowdown</h2>
 *
 * <p>Slowing the breeding would still let the herd grow without bound, and the search cost with it -- the
 * tick would just get there later. A cap bounds the thing that makes the cost grow, which is the only fix that
 * actually stops the problem rather than postponing it.
 *
 * <p>Not thread safe. Breeding is evaluated on the server thread.
 */
public final class BreedingDensityCap {

    /** Animals of one type allowed within one search area. */
    public static final int DENSITY_CAP = 40;

    /** Blocks the breeding search covers. */
    public static final double SEARCH_RANGE = 16.0;

    /** Milliseconds one animal's partner search costs per neighbour it scans. */
    public static final double MS_PER_COMPARISON = 0.00005;

    private long attempts;
    private long refused;
    private long bred;

    /**
     * Whether an animal may breed.
     *
     * @param count how many of the same type are within the search range
     * @return true if breeding is allowed
     */
    public boolean canBreed(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("a negative count: " + count);
        }
        this.attempts++;

        if (count >= DENSITY_CAP) {
            // The refusal is the same as the game's own answer when an animal is not ready: nothing happens.
            // Faking it -- hearts and no calf -- would be worse, because the player would keep feeding and
            // keep seeing the same thing.
            this.refused++;
            return false;
        }
        this.bred++;
        return true;
    }

    /**
     * How many comparisons a tick costs, for a pen of this size.
     *
     * <p>Every animal searches every other, so the cost grows with the square of the count -- which is why an
     * uncapped pen gets worse faster than it gets bigger.
     */
    public double msPerTick(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("a negative count: " + count);
        }
        return (long) count * count * MS_PER_COMPARISON;
    }

    public long attempts() {
        return this.attempts;
    }

    /** How many breeding attempts were refused. */
    public long refused() {
        return this.refused;
    }

    /** How many were allowed. */
    public long bred() {
        return this.bred;
    }

    public double refusedPercent() {
        return this.attempts == 0 ? 0.0 : 100.0 * this.refused / this.attempts;
    }

    /** Milliseconds a tick costs at the cap. */
    public double msAtCap() {
        return msPerTick(DENSITY_CAP);
    }

    /** Milliseconds a tick costs for an uncapped herd of this size. */
    public double msUncapped(int count) {
        return msPerTick(count);
    }

    /**
     * The share of the search cost avoided, against an uncapped herd of the given size.
     *
     * <p>Zero before any attempt has been made, and zero for a herd already below the cap -- a pen that was
     * never crowded has nothing to save, and reporting a saving for it would describe an unused one as though
     * it had done something.
     */
    public double savedPercent(int count) {
        double uncapped = msUncapped(count);
        if (this.attempts == 0 || uncapped == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, uncapped - msAtCap()) / uncapped;
    }
}
