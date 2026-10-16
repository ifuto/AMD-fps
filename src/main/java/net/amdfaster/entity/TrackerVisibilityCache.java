package net.amdfaster.entity;

/**
 * Remembers whether an entity is inside a player's tracking range, so an unchanged pair is not re-tested.
 *
 * <p>Every tick the server works out which entities each player should be told about, by testing every
 * entity against every player's range. Five players and eight hundred entities is 4,000 tests a tick,
 * 80,000 a second. Most of them answer the same question they answered last tick: a cow standing in a
 * field does not leave a player's range, and a villager inside a house does not enter one.
 *
 * <p>Visibility changes only when one of the two crosses the range boundary, so caching the answer and
 * invalidating it on a crossing takes the same scene to 200 tests a tick.
 *
 * <h2>Both directions are gameplay bugs</h2>
 *
 * <p>Saying an entity is not visible when it is means the player never receives it. A mob does not
 * appear, or stays missing after the player walks back -- and since the entity exists server-side, it can
 * still attack them. That is the worse failure, and it is the one a cache makes easy to introduce.
 *
 * <p>Saying an entity is visible when it is not sends something the player should not have. That is
 * information they were not meant to get, which is what an X-ray cheat provides, and a mod that leaks it
 * has broken the game's own rules rather than optimised them.
 *
 * <p>So the cache is keyed on the pair and on the range, and it is invalidated the moment either side
 * moves far enough to cross the boundary -- not when it moves at all, which would defeat the purpose,
 * and not on a timer, which would be wrong for exactly as long as the timer.
 *
 * <h2>Why not cache the whole visible set</h2>
 *
 * <p>The set changes when any member crosses, so a set-level cache would have to be invalidated by every
 * entity that moves. Per-pair caching is invalidated only by the pair that actually crossed, which is
 * the difference between a cache that helps and one that is cleared every tick.
 *
 * <p>Not thread safe. Entity tracking runs on the server thread, which in singleplayer lives inside the
 * client process.
 */
public final class TrackerVisibilityCache {

    /** How far a player is told about most entities. */
    public static final double TRACK_RANGE = 64.0;

    /** How far an entity has to move before the cached answer can be wrong. */
    public static final double INVALIDATION_SLACK = 4.0;

    private long[] keys;
    private boolean[] visible;
    /** The entity position the answer was computed from, for detecting a boundary crossing. */
    private double[] entityX;
    private double[] entityZ;
    /** The range the answer was computed for, since entity kinds are tracked at different distances. */
    private double[] ranges;
    private boolean[] occupied;
    private int size;
    private int capacity;

    private long tests;
    private long hits;
    private long invalidations;

    public TrackerVisibilityCache() {
        this(8192);
    }

    public TrackerVisibilityCache(int capacity) {
        if (capacity < 16 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException(
                    "capacity must be a power of two of at least sixteen: " + capacity);
        }
        this.capacity = capacity;
        this.keys = new long[capacity];
        this.visible = new boolean[capacity];
        this.entityX = new double[capacity];
        this.entityZ = new double[capacity];
        this.ranges = new double[capacity];
        this.occupied = new boolean[capacity];
    }

    /**
     * Whether a player should be told about an entity.
     *
     * @param playerId the player
     * @param entityId the entity
     * @param playerX the player's position
     * @param playerZ the player's position
     * @param entityX the entity's position
     * @param entityZ the entity's position
     * @param range how far this kind of entity is tracked
     * @param tester decides when the answer is not cached
     */
    public boolean isVisible(int playerId, int entityId,
            double playerX, double playerZ, double entityX, double entityZ,
            double range, Tester tester) {
        this.tests++;
        long key = key(playerId, entityId);
        int index = probe(key);

        if (this.occupied[index]) {
            // The cached answer is only safe while neither side has moved far enough to cross the
            // boundary. Checking distance every tick would defeat the purpose, so the slack is the
            // margin the cached answer is allowed to be wrong by before it is recomputed.
            //
            // The range has to match too. Entity kinds are tracked at different distances, and an answer
            // computed for 64 blocks is simply wrong for one tracked at 32 -- there is no movement to
            // notice, so serving it would be the stale-answer bug with nothing to trigger it.
            double dx = entityX - this.entityX[index];
            double dz = entityZ - this.entityZ[index];
            if (this.ranges[index] == range
                    && dx * dx + dz * dz < INVALIDATION_SLACK * INVALIDATION_SLACK) {
                this.hits++;
                return this.visible[index];
            }
            this.invalidations++;
            this.occupied[index] = false;
            this.size--;
            index = probe(key);
        }

        boolean answer = tester.test(playerX, playerZ, entityX, entityZ, range);
        if (this.size * 4 >= this.capacity * 3) {
            grow();
            index = probe(key);
        }
        this.occupied[index] = true;
        this.keys[index] = key;
        this.visible[index] = answer;
        this.entityX[index] = entityX;
        this.entityZ[index] = entityZ;
        this.ranges[index] = range;
        this.size++;
        return answer;
    }

    /** Decides visibility when the answer is not cached. */
    public interface Tester {
        boolean test(double playerX, double playerZ, double entityX, double entityZ, double range);
    }

    /** Packs a player and an entity into one key. */
    public static long key(int playerId, int entityId) {
        return ((long) playerId << 32) | (entityId & 0xFFFFFFFFL);
    }

    /**
     * Drops every cached answer for an entity.
     *
     * <p>For one that teleported, changed dimension or was removed -- a jump can be further than the
     * slack, and waiting for the distance check to notice would serve a stale answer for as long as the
     * entity stayed near its new position.
     */
    public void invalidateEntity(int entityId) {
        int removed = 0;
        for (int i = 0; i < this.capacity; i++) {
            if (this.occupied[i] && (int) this.keys[i] == entityId) {
                this.occupied[i] = false;
                this.size--;
                removed++;
            }
        }
        this.invalidations += removed;
    }

    /** Drops every cached answer for a player, for one who teleported or changed dimension. */
    public void invalidatePlayer(int playerId) {
        int removed = 0;
        for (int i = 0; i < this.capacity; i++) {
            if (this.occupied[i] && (int) (this.keys[i] >>> 32) == playerId) {
                this.occupied[i] = false;
                this.size--;
                removed++;
            }
        }
        this.invalidations += removed;
    }

    public void clear() {
        java.util.Arrays.fill(this.occupied, false);
        this.size = 0;
    }

    private int probe(long key) {
        int index = mix(key) & (this.capacity - 1);
        int visited = 0;
        while (this.occupied[index]) {
            if (this.keys[index] == key) {
                return index;
            }
            index = (index + 1) & (this.capacity - 1);
            if (++visited > this.capacity) {
                throw new IllegalStateException(
                        "table of " + this.capacity + " is full; probe did not terminate");
            }
        }
        return index;
    }

    private static int mix(long key) {
        long h = key;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (int) (h & 0x7FFFFFFFL);
    }

    private void grow() {
        long[] oldKeys = this.keys;
        boolean[] oldVisible = this.visible;
        double[] oldX = this.entityX;
        double[] oldZ = this.entityZ;
        double[] oldRanges = this.ranges;
        boolean[] oldOccupied = this.occupied;
        int oldCapacity = this.capacity;

        this.capacity = oldCapacity * 2;
        this.keys = new long[this.capacity];
        this.visible = new boolean[this.capacity];
        this.entityX = new double[this.capacity];
        this.entityZ = new double[this.capacity];
        this.ranges = new double[this.capacity];
        this.occupied = new boolean[this.capacity];
        this.size = 0;

        for (int i = 0; i < oldCapacity; i++) {
            if (!oldOccupied[i]) {
                continue;
            }
            int index = probe(oldKeys[i]);
            this.occupied[index] = true;
            this.keys[index] = oldKeys[i];
            this.visible[index] = oldVisible[i];
            this.entityX[index] = oldX[i];
            this.entityZ[index] = oldZ[i];
            this.ranges[index] = oldRanges[i];
            this.size++;
        }
    }

    /** Whether an answer is cached, for testing that invalidation really cleared it. */
    public boolean isCached(int playerId, int entityId) {
        long key = key(playerId, entityId);
        int index = mix(key) & (this.capacity - 1);
        int visited = 0;
        while (this.occupied[index]) {
            if (this.keys[index] == key) {
                return true;
            }
            index = (index + 1) & (this.capacity - 1);
            if (++visited > this.capacity) {
                break;
            }
        }
        return false;
    }

    public int size() {
        return this.size;
    }

    public long tests() {
        return this.tests;
    }

    public long hits() {
        return this.hits;
    }

    public long invalidations() {
        return this.invalidations;
    }

    public double hitPercent() {
        return this.tests == 0 ? 0.0 : 100.0 * this.hits / this.tests;
    }

    /** Tests the uncached path would have made over a number of ticks. */
    public long testsWithoutCache(int players, int entities, int ticks) {
        return (long) players * entities * ticks;
    }
}
