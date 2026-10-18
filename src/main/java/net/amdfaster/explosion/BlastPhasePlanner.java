package net.amdfaster.explosion;

/**
 * Decides when several explosions in one tick may share a block cache, and what has to be invalidated
 * between them.
 *
 * <p>Vanilla already splits an explosion in two, and the split is what makes any of this safe.
 * {@code explode()} casts every ray and builds the list of blocks to destroy; {@code finalizeExplosion()}
 * then destroys them, plays the sound and drops the items. Nothing is written to the world during the ray
 * phase, so within one explosion the block cache cannot go stale under itself -- a ray that reads a
 * block's resistance gets the same answer as the ray after it, because the world has not moved.
 *
 * <p>Between explosions that guarantee is gone. Blast A destroys 823 blocks and then blast B casts its
 * rays, and B has to see A's holes: a block A removed is air now, so it no longer absorbs
 * {@code (resistance + 0.3) x 0.3} of intensity and B's rays travel further through it. Serving B the
 * cached pre-A resistance would stop a ray at a wall that is no longer standing, which means terrain that
 * should have been destroyed survives -- a visible wrong result, and one that looks like the explosion was
 * merely weak rather than incorrect.
 *
 * <p>The useful observation is that this only matters where the two blasts actually overlap. A's destroyed
 * blocks all lie within A's reach of A's centre, and B's rays only ever read blocks within B's reach of
 * B's centre, so if those two spheres do not touch then A cannot have changed anything B reads and the two
 * ray phases may run against one shared snapshot. For TNT that is a separation above 13.8 blocks, which
 * is further apart than a chain reaction ever is, so a chain stays sequential -- but the criterion is
 * computed rather than assumed, and a scattered set of independent detonations in one tick does qualify.
 *
 * <p>One thing worth stating, because it is easy to get backwards: a chain reaction does not produce
 * same-tick explosions. TNT primed by another explosion fuses for a random 10 to 30 ticks rather than the
 * 80 of a direct priming, so the blasts are spread over half a second to a second and a half. What does
 * happen is that several TNT primed by the same blast each draw an independent fuse, and about 4.76 per
 * cent of such pairs land on the same tick. Those are the coincidences this planner is for.
 *
 * @see <a href="https://minecraft.wiki/w/Explosion">minecraft.wiki -- rays from a 16x16x16 grid, 0.3 block
 *      steps, intensity down 0.225 per step and a further (resistance + 0.3) x 0.3 per non-air block</a>
 * @see <a href="https://docs.blamejared.com/1.21.1/en/vanilla/api/world/Explosion/">vanilla 1.21.1 API --
 *      {@code explode()} is the block-destroying first part, {@code finalizeExplosion()} the second</a>
 * @see <a href="https://minecraft.fandom.com/wiki/TNT">TNT -- primed by an explosion the fuse is a random
 *      10 to 30 ticks rather than four seconds</a>
 */
public final class BlastPhasePlanner {

    /** Blocks a ray advances per checkpoint. */
    public static final double RAY_STEP = 0.3;

    /** Intensity lost per step, before any block absorption. */
    public static final double ATTENUATION_PER_STEP = 0.22500001;

    /** The low end of the random multiplier applied to an explosion's power. */
    public static final double MIN_INTENSITY_FACTOR = 0.7;

    /** The high end, which is what sets how far a ray can possibly reach. */
    public static final double MAX_INTENSITY_FACTOR = 1.3;

    /** Rays cast per explosion, from the centre to each point of a 16x16x16 grid's surface. */
    public static final int RAYS_PER_BLAST = 1352;

    /** Distinct blocks a TNT blast destroys, measured for {@code ExplosionBlockCache}. */
    public static final int BLOCKS_PER_BLAST = 823;

    /** Ticks a directly primed TNT fuses for: four seconds. */
    public static final int DIRECT_FUSE_TICKS = 80;

    /** The shortest fuse of TNT primed by another explosion. */
    public static final int CHAIN_FUSE_MIN_TICKS = 10;

    /** The longest, so the chain spreads over 21 possible ticks. */
    public static final int CHAIN_FUSE_MAX_TICKS = 30;

    private BlastPhasePlanner() {
    }

    /** One pending explosion. */
    public record Blast(double x, double y, double z, double power) {
    }

    /**
     * How the tick's explosions have to be ordered.
     *
     * @param groups       how many separate ray phases are needed. One means every blast in the tick read
     *                     the same snapshot; more means some had to wait for an earlier one's destruction.
     * @param shared       blasts that were able to share a phase with another
     * @param invalidations cache entries that have to be dropped between phases
     */
    public record Schedule(int groups, int shared, int invalidations) {

        /** Whether the whole tick ran against one snapshot, which is the case that needs no invalidation. */
        public boolean singlePhase() {
            return this.groups <= 1;
        }

        /** Blast lookups avoided by sharing the resistance values between the grouped blasts. */
        public long lookupsShared() {
            return (long) this.shared * ChainReactionDeduplicator.LOOKUPS_PER_BLAST;
        }
    }

    /**
     * Checkpoints a ray survives before its intensity runs out.
     *
     * <p>The intensity starts at {@code power x factor} and falls by
     * {@value #ATTENUATION_PER_STEP} each step, so the count is that quotient floored. The floor matters:
     * a ray with a sliver of intensity left still takes one more step, and rounding up would let it reach
     * a block the game never tests.
     */
    public static int checkpoints(double power, double factor) {
        require(power > 0.0, "an explosion has positive power");
        require(factor >= MIN_INTENSITY_FACTOR - 1e-9 && factor <= MAX_INTENSITY_FACTOR + 1e-9,
                "the intensity factor is between 0.7 and 1.3");
        return (int) Math.floor(power * factor / ATTENUATION_PER_STEP);
    }

    /**
     * How far a ray can travel, in blocks.
     *
     * <p>At the maximum factor this reproduces the published figures rather than approximating them: TNT
     * at power 4 reaches 6.9 blocks, a creeper at 3 reaches 5.1, an end crystal at 6 reaches 10.2. Those
     * are the numbers the wiki lists for blast radius, and a model that did not land on them would be
     * measuring the wrong thing.
     */
    public static double reach(double power, double factor) {
        return checkpoints(power, factor) * RAY_STEP;
    }

    /** The furthest any ray of this explosion can reach, which bounds the region it can destroy. */
    public static double maxReach(double power) {
        return reach(power, MAX_INTENSITY_FACTOR);
    }

    /** The furthest a ray reaches at the weakest intensity roll, which is the region it always affects. */
    public static double minReach(double power) {
        return reach(power, MIN_INTENSITY_FACTOR);
    }

    /**
     * Whether two blasts may cast their rays against one shared snapshot.
     *
     * <p>True when neither can have destroyed a block the other reads. A's destruction is confined to a
     * sphere of A's maximum reach around A, and B's rays only read inside B's own, so disjoint spheres
     * mean A changed nothing B looks at and the ordering between them cannot matter.
     *
     * <p>The test uses the maximum reach on purpose. At the weakest intensity roll the spheres are smaller
     * and more pairs would qualify, but the roll is drawn per ray and is not known when this is decided,
     * so planning against it would be planning against a guess. Being conservative here costs a shared
     * phase; being wrong costs terrain that survives an explosion it should not have.
     */
    public static boolean canShareSnapshot(Blast a, Blast b) {
        require(a != null && b != null, "both blasts are needed");
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return distance > maxReach(a.power()) + maxReach(b.power());
    }

    /**
     * Works out how many ray phases the tick needs.
     *
     * <p>Blasts are taken in the order given and each joins the first earlier blast it can share with,
     * which is a grouping rather than a true minimum -- finding the fewest groups is a graph colouring
     * problem and the tick does not have time for one. Greedy is enough because the thing being decided is
     * only whether a cache may be reused, and a grouping that is one larger than optimal costs one extra
     * invalidation, not a wrong answer.
     *
     * <p>A blast that shares a phase still needs its own destruction applied afterwards, so the
     * invalidation count is charged for every phase after the first.
     */
    public static Schedule schedule(Blast... blasts) {
        require(blasts != null, "blasts must be given, even if empty");
        int[] leader = new int[blasts.length];
        int groups = 0;
        int shared = 0;
        for (int i = 0; i < blasts.length; i++) {
            require(blasts[i] != null, "a null blast");
            int joined = -1;
            for (int g = 0; g < groups; g++) {
                if (canShareSnapshot(blasts[leader[g]], blasts[i])) {
                    joined = g;
                    break;
                }
            }
            if (joined < 0) {
                leader[groups++] = i;
            } else {
                shared++;
            }
        }
        // Every phase after the first runs against a world the previous one has already destroyed blocks
        // in, so its cached entries for those blocks are stale and have to go.
        int invalidations = groups <= 1 ? 0 : (groups - 1) * BLOCKS_PER_BLAST;
        return new Schedule(groups, shared, invalidations);
    }

    /**
     * Whether a chain reaction produces explosions in the same tick.
     *
     * <p>It does not, on its own: TNT primed by an explosion fuses for 10 to 30 ticks rather than the 80 of
     * a direct priming, so successive blasts land in different ticks and each gets a fresh cache anyway.
     * What puts two in one tick is several TNT primed by the same blast drawing independent fuses that
     * happen to match.
     *
     * @return the chance that two independently primed chain fuses expire on the same tick
     */
    public static double chainFuseCollisionChance() {
        int span = CHAIN_FUSE_MAX_TICKS - CHAIN_FUSE_MIN_TICKS + 1;
        return 1.0 / span;
    }

    /** How many ticks a chain reaction can spread its blasts over. */
    public static int chainFuseSpan() {
        return CHAIN_FUSE_MAX_TICKS - CHAIN_FUSE_MIN_TICKS + 1;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
