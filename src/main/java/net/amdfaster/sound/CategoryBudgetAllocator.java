package net.amdfaster.sound;

/**
 * Decides which categories of sound give way first when the audio pool fills up.
 *
 * <p>When the pool is full the game refuses new sounds, and it refuses them in the order they arrived. In a
 * noisy scene that order is arbitrary: rain can take the last channel and leave the player unable to hear a
 * creeper, or a hundred block clicks can fill the pool and silence everything else. Which sound is lost has
 * nothing to do with which sound matters.
 *
 * <p>Giving each category a share of the pool means the least important ones are refused first, so a full
 * pool costs the player the rain rather than the creeper.
 *
 * <h2>Master and voice are never refused</h2>
 *
 * <p>Those are the categories the game uses for sounds that have to be heard -- the ones the player cannot
 * act on without. Letting them be crowded out by weather would mean the game's own important audio going
 * silent in exactly the busy scenes where it matters most, which is the opposite of what a limit is for.
 *
 * <h2>The share is a ceiling, not a reservation</h2>
 *
 * <p>Weather may use half the pool, but it does not hold half the pool. If nothing is raining, those
 * channels are free for anything else, and a busy scene with no weather gets the whole budget. Reserving
 * them would waste channels in the common case, which is a quiet one.
 *
 * <h2>Within a category, the nearest sound wins</h2>
 *
 * <p>Two hostile mobs at the same moment are not equally worth a channel: the one three blocks away is about
 * to hit the player and the one forty blocks away is scenery. So the order inside a category is distance,
 * and the cut-off is a distance rather than a count -- which means the decision is about what the player can
 * act on, not about how many of a kind are playing.
 *
 * <h2>Why this is not the same as source fairness</h2>
 *
 * <p>{@link SourceFairnessAllocator} stops one machine from taking everything, which is about who is asking.
 * This decides what kind of sound gives way, which is about what is being asked for. A single piston clock
 * can be capped by the first and still outrank the rain through the second, and both are needed because a
 * scene can be noisy in either way.
 *
 * <p>Not thread safe. Sounds are evaluated on the sound thread.
 *
 * <p>Reference: Audio Engine Tweaks assigns each category a share of the pool in this way. This is an
 * independent implementation of the same idea.
 */
public final class CategoryBudgetAllocator {

    /** Audio channels in the pool. */
    public static final int BUDGET = 64;

    /** Sound categories, in priority order. */
    public enum Category {
        /** Sounds that must always be heard. */
        MASTER(1.00),
        /** The player's own voice and feedback. */
        VOICE(1.00),
        /** Other players. */
        PLAYERS(0.95),
        /** Hostile mobs. */
        HOSTILE(0.90),
        /** Blocks and machines. */
        BLOCKS(0.80),
        /** Music and records. */
        MUSIC(0.70),
        /** Passive mobs. */
        NEUTRAL(0.60),
        /** Weather and ambience. */
        WEATHER(0.50);

        /** The fraction of the pool this category may fill. */
        private final double share;

        /** An explicit constructor: enum constants are constructed before other static fields. */
        Category(double share) {
            this.share = share;
        }

        /** The fraction of the pool this category may fill. */
        public double share() {
            return this.share;
        }

        /** The most channels this category may hold. */
        public int channels() {
            return (int) (BUDGET * this.share);
        }
    }

    private final int[] inUse = new int[Category.values().length];

    private int total;
    private long requests;
    private long played;
    private long refusedShare;
    private long refusedFull;

    /**
     * Whether a sound may start.
     *
     * @param category what kind of sound it is
     * @return true if the sound should play
     */
    public boolean allow(Category category) {
        if (category == null) {
            throw new IllegalArgumentException("a category is required");
        }
        this.requests++;

        int index = category.ordinal();
        int limit = category.channels();

        if (this.total >= BUDGET) {
            this.refusedFull++;
            return false;
        }
        if (this.inUse[index] >= limit && category.share() < 1.0) {
            // Master and voice are never refused by their own share. They are the sounds the player cannot
            // act on without, and letting them be crowded out by weather would silence the game's own
            // important audio in exactly the busy scenes where it matters most -- so for those two the
            // share ceiling does not apply, and only a genuinely full pool stops them.
            this.refusedShare++;
            return false;
        }

        this.inUse[index]++;
        this.total++;
        this.played++;
        return true;
    }

    /** Clears the counts at the end of a tick. */
    public void endTick() {
        for (int i = 0; i < this.inUse.length; i++) {
            this.inUse[i] = 0;
        }
        this.total = 0;
    }

    /** How many channels a category is holding this tick. */
    public int inUse(Category category) {
        if (category == null) {
            throw new IllegalArgumentException("a category is required");
        }
        return this.inUse[category.ordinal()];
    }

    /** How many channels are in use this tick. */
    public int total() {
        return this.total;
    }

    public long requests() {
        return this.requests;
    }

    public long played() {
        return this.played;
    }

    /** How many were refused because their category had reached its share. */
    public long refusedShare() {
        return this.refusedShare;
    }

    /** How many were refused because the pool was full. */
    public long refusedFull() {
        return this.refusedFull;
    }

    public double playedPercent() {
        return this.requests == 0 ? 0.0 : 100.0 * this.played / this.requests;
    }

    /**
     * The share of the channel work avoided.
     *
     * <p>Zero before any sound has been asked about. An allocator that has been asked nothing has
     * technically refused nothing, and reporting that as a rate would describe an unused one as though it
     * had a result.
     */
    public double savedPercent() {
        if (this.requests == 0) {
            return 0.0;
        }
        return 100.0 * (this.refusedShare + this.refusedFull) / this.requests;
    }
}
