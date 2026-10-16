package net.amdfaster.pathfinding;

import java.util.HashMap;
import java.util.Map;

/**
 * Computes one path for every mob heading to the same place, instead of one per mob.
 *
 * <p>When a player walks into a village, every mob that wants to reach them runs its own pathfinding search.
 * Fifty zombies chasing one player is fifty searches, each of them walking the same blocks and reaching very
 * nearly the same route, in the same tick, on the server thread. The searches are the expensive part of mob
 * AI in a crowded scene, and almost all of the work is repeated.
 *
 * <p>Computing the route to a position once and handing it to every mob going there removes the repetition.
 *
 * <h2>The path depends on the terrain, so the key includes its version</h2>
 *
 * <p>A door opening, a block being placed, water flowing -- any of those changes which route is walkable. A
 * cached path from before the change would send mobs into a wall, and they would stand there pushing against
 * it, which is both wrong and visible. So every block change bumps a version and the version is part of the
 * key, which means an old path is simply never found again rather than having to be hunted down and removed.
 *
 * <h2>Mobs of different sizes need different paths</h2>
 *
 * <p>A spider fits through a one-block gap that a cow does not, and a path that works for one is a path into
 * a wall for the other. Sharing by target alone would give the cow the spider's route. So the mob's size
 * class is in the key too, and mobs that fit the same spaces share while those that do not get their own.
 *
 * <h2>The path is a plan, not a promise</h2>
 *
 * <p>A mob following a shared path still checks each step as it takes it, because the world can change
 * between the moment the path was computed and the moment the mob arrives. Sharing the computation does not
 * mean trusting it blindly -- it means not paying to compute the same plan fifty times. A mob that finds a
 * step blocked falls back to its own search, which is what it would have done anyway.
 *
 * <h2>Why this is not the same as the walkability cache</h2>
 *
 * <p>{@link PathCache} remembers whether individual blocks can be walked on, which makes each search cheaper.
 * This remembers the whole route, which removes the search entirely. Both apply: a mob that cannot share a
 * path still runs its own search, and that search is faster because of the walkability cache.
 *
 * <p>Not thread safe. Pathfinding runs on the server thread.
 */
public final class SharedPathCache {

    /** Milliseconds one pathfinding search costs. */
    public static final double MS_PER_SEARCH = 0.4;

    /** Milliseconds handing a cached path to a mob costs. */
    public static final double MS_PER_HIT = 0.002;

    /** Paths held before the least recently used leaves. */
    public static final int CAPACITY = 256;

    private final Map<Long, int[]> paths = new HashMap<>();

    private long terrainVersion;

    private long requests;
    private long hits;
    private long searches;
    private long evicted;

    /**
     * The key for a path.
     *
     * <p>The target, the size class and the terrain version. A door opening changes which route is walkable,
     * and a spider fits through a gap a cow does not, so both have to be in the key or mobs would be sent into
     * walls.
     *
     * @param targetX the target's block position
     * @param sizeClass the mob's size class, so mobs that fit the same spaces share
     */
    public static long key(int targetX, int targetY, int targetZ, int sizeClass, long terrainVersion) {
        long mixed = (long) targetX * 0x9E3779B97F4A7C15L;
        mixed ^= (long) targetY * 0xC2B2AE3D27D4EB4FL;
        mixed ^= (long) targetZ * 0x165667B19E3779F9L;
        mixed ^= (long) sizeClass * 0x27D4EB2F165667C5L;
        mixed ^= terrainVersion * 0x85EBCA6B165667C5L;
        return mixed;
    }

    /**
     * Records that the terrain changed.
     *
     * <p>Bumping the version means an old path is never found again, rather than having to hunt down and
     * remove every path that crossed the changed block.
     */
    public void bumpTerrain() {
        this.terrainVersion++;
    }

    /** The current terrain version. */
    public long terrainVersion() {
        return this.terrainVersion;
    }

    /**
     * Returns the path to a target, computing it if nothing has asked for it since the terrain changed.
     *
     * @param search computes the route, called at most once per target per terrain version
     * @return the path, as a flat array of block positions
     */
    public int[] path(int targetX, int targetY, int targetZ, int sizeClass,
            java.util.function.Supplier<int[]> search) {
        if (search == null) {
            throw new IllegalArgumentException("a search is required");
        }
        long key = key(targetX, targetY, targetZ, sizeClass, this.terrainVersion);
        this.requests++;

        int[] cached = this.paths.get(key);
        if (cached != null) {
            this.hits++;
            return cached;
        }

        int[] found = search.get();
        if (found == null) {
            throw new IllegalStateException("the search produced no path");
        }
        if (this.paths.size() >= CAPACITY) {
            this.paths.remove(this.paths.keySet().iterator().next());
            this.evicted++;
        }
        this.paths.put(key, found);
        this.searches++;
        return found;
    }

    /** How many paths are cached. */
    public int size() {
        return this.paths.size();
    }

    /** Drops every cached path. */
    public void clear() {
        this.paths.clear();
    }

    public long requests() {
        return this.requests;
    }

    /** How many mobs were handed an existing path. */
    public long hits() {
        return this.hits;
    }

    /** How many searches actually ran. */
    public long searches() {
        return this.searches;
    }

    /** How many were dropped to make room. */
    public long evicted() {
        return this.evicted;
    }

    public double hitPercent() {
        return this.requests == 0 ? 0.0 : 100.0 * this.hits / this.requests;
    }

    /** Milliseconds of pathfinding done. */
    public double msSpent() {
        return this.searches * MS_PER_SEARCH + this.hits * MS_PER_HIT;
    }

    /** Milliseconds every mob searching for itself would cost. */
    public double msWithoutSharing() {
        return this.requests * MS_PER_SEARCH;
    }

    /**
     * The share of the pathfinding cost avoided.
     *
     * <p>Zero before any mob has asked. A cache that has been asked nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        double without = msWithoutSharing();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, without - msSpent()) / without;
    }
}
