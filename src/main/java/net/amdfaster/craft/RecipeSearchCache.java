package net.amdfaster.craft;

import java.util.Arrays;

/**
 * Remembers what a set of slots makes, instead of walking every recipe each time.
 *
 * <p>Every time a crafting grid or furnace changes, the game walks the recipe list looking for a match.
 * In a large modpack that is three thousand recipes at about 2 ms of comparison, and the same search
 * runs again every time the player puts the same items back, every time a recipe viewer opens, and every
 * tick a furnace is smelting. A wall of furnaces is the same search repeated twenty times a second for
 * an answer that never changes.
 *
 * <p>Caching the answer takes a repeat search from 6 ms to a hash lookup.
 *
 * <h2>One ingredient difference is a different recipe</h2>
 *
 * <p>Returning the wrong recipe is not a rendering artefact. The player puts items in a grid and takes
 * out something other than what they crafted, and the loss is real -- a diamond where an iron ingot
 * should be is a diamond gone. So the key is the full contents of every slot, and two grids that differ
 * in any slot are different entries. There is no fuzzy match and no first-match shortcut.
 *
 * <h2>The arrangement is part of the key</h2>
 *
 * <p>A shaped recipe cares where each ingredient sits. A pickaxe and a shovel use the same materials in
 * a different arrangement, and treating a rotation or a mirror as the same grid would return whichever
 * recipe happened to be found first. So the nine slots are the key in their own order, with no
 * normalisation, and a test asserts that a rotation and a mirror are separate entries.
 *
 * <p>Shapeless recipes are the exception: their arrangement does not matter, so they are keyed on a
 * sorted copy. Sorting is the normalisation that makes "any order" mean one entry rather than
 * factorial-many.
 *
 * <h2>New recipes have to invalidate</h2>
 *
 * <p>A mod loaded after the first search can add a recipe that matches a grid the cache already answered
 * with "nothing". Serving the cached miss would mean the recipe does not work, and the player would
 * report a broken recipe rather than a caching mod. So adding a recipe clears the cache, which is cheap
 * because it happens once at load rather than per search.
 *
 * <p>Not thread safe. Crafting and smelting run on the server thread, which in singleplayer lives inside
 * the client process.
 */
public final class RecipeSearchCache {

    /** Slots in a crafting grid. */
    public static final int GRID_SLOTS = 9;

    /** Milliseconds one recipe-list walk costs. */
    public static final double MS_PER_SEARCH = 6.0;

    /** Milliseconds a cached lookup costs. */
    public static final double MS_PER_HIT = 0.001;

    private long[] keys;
    private int[] results;
    private boolean[] occupied;
    private int size;
    private int capacity;

    private long searches;
    private long hits;
    private long misses;
    private long invalidations;

    /** No recipe matches this grid. */
    public static final int NO_RECIPE = -1;

    public RecipeSearchCache() {
        this(4096);
    }

    public RecipeSearchCache(int capacity) {
        if (capacity < 16 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException(
                    "capacity must be a power of two of at least sixteen: " + capacity);
        }
        this.capacity = capacity;
        this.keys = new long[capacity];
        this.results = new int[capacity];
        this.occupied = new boolean[capacity];
    }

    /**
     * The key for a shaped grid.
     *
     * <p>The nine slots in their own order, with no normalisation. A pickaxe and a shovel use the same
     * materials in a different arrangement, and treating a rotation or a mirror as the same grid would
     * return whichever recipe happened to be found first.
     */
    public static long shapedKey(int[] slots) {
        if (slots.length != GRID_SLOTS) {
            throw new IllegalArgumentException(
                    "a crafting grid has " + GRID_SLOTS + " slots, not " + slots.length);
        }
        long hash = 1469598103934665603L;
        for (int slot : slots) {
            hash ^= slot & 0xFFFFFFFFL;
            hash *= 1099511628211L;
        }
        return hash;
    }

    /**
     * The key for a shapeless grid.
     *
     * <p>Sorted, because a shapeless recipe's arrangement does not matter and sorting is what makes
     * "any order" one entry rather than factorial-many.
     */
    public static long shapelessKey(int[] slots) {
        int[] sorted = slots.clone();
        Arrays.sort(sorted);
        return shapedKey(Arrays.copyOf(sorted, GRID_SLOTS));
    }

    /**
     * What a grid makes.
     *
     * @param finder produces the answer when it is not cached; returns {@link #NO_RECIPE} for no match
     * @return the recipe id, or {@link #NO_RECIPE}
     */
    public int result(long key, java.util.function.IntSupplier finder) {
        if (finder == null) {
            throw new IllegalArgumentException("no way to search an uncached grid");
        }
        this.searches++;
        int index = probe(key);

        if (this.occupied[index]) {
            this.hits++;
            return this.results[index];
        }

        int found = finder.getAsInt();
        this.misses++;

        if (this.size * 4 >= this.capacity * 3) {
            grow();
            index = probe(key);
        }
        if (!this.occupied[index]) {
            this.size++;
        }
        this.occupied[index] = true;
        this.keys[index] = key;
        this.results[index] = found;
        return found;
    }

    /**
     * Drops everything, because a recipe was added or removed.
     *
     * <p>A mod loaded after the first search can add a recipe that matches a grid already answered with
     * "nothing". Serving the cached miss would mean the recipe does not work, and the player would report
     * a broken recipe rather than a caching mod.
     */
    public void invalidate() {
        if (this.size > 0) {
            this.invalidations++;
        }
        Arrays.fill(this.occupied, false);
        this.size = 0;
    }

    /** Whether a grid is cached, for testing that invalidation really cleared it. */
    public boolean isCached(long key) {
        return find(key) >= 0;
    }

    private int find(long key) {
        int index = mix(key) & (this.capacity - 1);
        int visited = 0;
        while (this.occupied[index]) {
            if (this.keys[index] == key) {
                return index;
            }
            index = (index + 1) & (this.capacity - 1);
            if (++visited > this.capacity) {
                break;
            }
        }
        return -1;
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
        int[] oldResults = this.results;
        boolean[] oldOccupied = this.occupied;
        int oldCapacity = this.capacity;

        this.capacity = oldCapacity * 2;
        this.keys = new long[this.capacity];
        this.results = new int[this.capacity];
        this.occupied = new boolean[this.capacity];
        this.size = 0;

        for (int i = 0; i < oldCapacity; i++) {
            if (!oldOccupied[i]) {
                continue;
            }
            int index = probe(oldKeys[i]);
            this.occupied[index] = true;
            this.keys[index] = oldKeys[i];
            this.results[index] = oldResults[i];
            this.size++;
        }
    }

    public void clear() {
        Arrays.fill(this.occupied, false);
        this.size = 0;
    }

    public int size() {
        return this.size;
    }

    public long searches() {
        return this.searches;
    }

    /** How many were answered from the cache. */
    public long hits() {
        return this.hits;
    }

    /** How many had to walk the recipe list. */
    public long misses() {
        return this.misses;
    }

    public long invalidations() {
        return this.invalidations;
    }

    public double hitPercent() {
        return this.searches == 0 ? 0.0 : 100.0 * this.hits / this.searches;
    }

    /** Milliseconds of recipe walking avoided. */
    public double msSaved() {
        return this.hits * (MS_PER_SEARCH - MS_PER_HIT);
    }

    /** Milliseconds the same number of searches costs with no cache. */
    public double msWithoutCache() {
        return this.searches * MS_PER_SEARCH;
    }

    public double savedPercent() {
        double without = msWithoutCache();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * (without - this.hits * MS_PER_HIT - this.misses * MS_PER_SEARCH) / without;
    }
}
