package net.amdfaster.worldgen;

import java.util.Arrays;

/**
 * Asks the biome source once per biome cell instead of once per block.
 *
 * <p>Biomes are not stored per block. They are sampled on a grid four blocks wide, four deep and four
 * tall, and every block in a cell gets the same answer. World generation asks per block anyway: a
 * chunk is 16 by 16 by 384, which is 98,304 questions, and at a render distance of ten the initial
 * load is 441 chunks and 43 million of them.
 *
 * <p>So the cache is keyed on the cell, not the block. That is what makes it worth having: 98,304
 * questions from one chunk collapse to 1,536 distinct cells, a factor of 64, and the table holds the
 * small number rather than the large one. Keying it on block coordinates would work and would need
 * sixty-four times the memory to answer the same questions.
 *
 * <p>Surface block choice gets the same treatment. What a biome puts at the surface depends on the
 * biome and how far below the surface the block is, which is a pair of small numbers rather than a
 * position. Across a chunk that is a handful of distinct answers, not 98,304.
 *
 * <h2>Why the cell key is safe here and would not be elsewhere</h2>
 *
 * <p>Because the sub-sampling is a property of how the biome is stored, not an approximation this
 * cache makes. Two blocks in the same cell do not merely tend to have the same biome, they are
 * answered from the same stored value. If that ever changed the cache would be wrong in a way that
 * shows up as a biome boundary in the wrong place, which is visible and reportable, so the constant
 * is worth a comment rather than an assumption.
 *
 * <p>Not thread safe.
 */
public final class BiomeLookupCache {

    /**
     * Biomes are sampled on a four block grid, so two bits of each coordinate are below the
     * resolution of the answer.
     */
    public static final int CELL_BITS = 2;

    /** Blocks per cell edge. */
    public static final int CELL_SIZE = 1 << CELL_BITS;

    /** Positions in a cell that share one answer. */
    public static final int POSITIONS_PER_CELL = CELL_SIZE * CELL_SIZE * CELL_SIZE;

    /** Supplies a biome id for a position. Called once per cell rather than once per block. */
    public interface BiomeSource {
        /** @return a biome id, never negative */
        int biomeAt(int x, int y, int z);
    }

    /** Supplies the block a biome places at a given depth below its surface. */
    public interface SurfaceResolver {
        /**
         * @param biomeId as returned by {@link BiomeSource}
         * @param depthBelowSurface 0 at the surface, increasing downwards
         * @return a block state id
         */
        int surfaceBlockFor(int biomeId, int depthBelowSurface);
    }

    private static final int DEFAULT_CAPACITY = 2048;

    private static final int LOAD_NUMERATOR = 3;
    private static final int LOAD_DENOMINATOR = 4;

    /** A biome id is never negative, so this marks an empty slot. */
    private static final int ABSENT_BIOME = -1;

    /** A block state id is never negative, so this marks an empty slot. */
    private static final int ABSENT_BLOCK = -1;

    private int[] biomeKeys;
    private int[] biomeIds;
    private int biomeCapacity;
    private int biomeCount;

    private int[] surfaceKeys;
    private int[] surfaceBlocks;
    private int surfaceCapacity;
    private int surfaceCount;

    private long biomeReads;
    private long biomeHits;
    private long surfaceReads;
    private long surfaceHits;

    public BiomeLookupCache() {
        this(DEFAULT_CAPACITY, 256);
    }

    public BiomeLookupCache(int biomeCapacity, int surfaceCapacity) {
        if (biomeCapacity < 16 || surfaceCapacity < 16) {
            throw new IllegalArgumentException(
                    "capacities must be at least sixteen: " + biomeCapacity + ", " + surfaceCapacity);
        }
        this.biomeCapacity = roundUp(biomeCapacity);
        this.biomeKeys = new int[this.biomeCapacity];
        this.biomeIds = new int[this.biomeCapacity];
        Arrays.fill(this.biomeIds, ABSENT_BIOME);
        this.surfaceCapacity = roundUp(surfaceCapacity);
        this.surfaceKeys = new int[this.surfaceCapacity];
        this.surfaceBlocks = new int[this.surfaceCapacity];
        Arrays.fill(this.surfaceBlocks, ABSENT_BLOCK);
    }

    private static int roundUp(int capacity) {
        int rounded = Integer.highestOneBit(capacity - 1) << 1;
        return rounded <= 0 ? 16 : rounded;
    }

    /**
     * Resolves the biome at a block position.
     *
     * @throws IllegalArgumentException if the source returns a negative id, which would collide with
     *     the empty marker
     */
    public int biomeAt(BiomeSource source, int x, int y, int z) {
        this.biomeReads++;
        int key = packCell(x >> CELL_BITS, y >> CELL_BITS, z >> CELL_BITS);
        int index = biomeProbe(key);
        int cached = this.biomeIds[index];
        if (cached != ABSENT_BIOME) {
            this.biomeHits++;
            return cached;
        }
        int biome = source.biomeAt(x, y, z);
        if (biome < 0) {
            throw new IllegalArgumentException(
                    "biome id " + biome + " at " + x + "," + y + "," + z
                            + " collides with the empty marker");
        }
        // The value is written before the load is checked. Writing it afterwards means a growth
        // triggered here copies the table while the slot still reads as absent, and the entry in
        // flight is dropped. Three other classes in this project shipped that.
        this.biomeKeys[index] = key;
        this.biomeIds[index] = biome;
        this.biomeCount++;
        if (this.biomeCount * LOAD_DENOMINATOR >= this.biomeCapacity * LOAD_NUMERATOR) {
            growBiomes();
        }
        return biome;
    }

    /** Resolves the block a biome places at a depth below its surface. */
    public int surfaceBlockFor(SurfaceResolver resolver, int biomeId, int depthBelowSurface) {
        if (biomeId < 0) {
            throw new IllegalArgumentException("biomeId must not be negative: " + biomeId);
        }
        if (depthBelowSurface < 0) {
            throw new IllegalArgumentException(
                    "depth must not be negative: " + depthBelowSurface);
        }
        this.surfaceReads++;
        int key = (biomeId << 8) | (depthBelowSurface & 0xFF);
        int index = surfaceProbe(key);
        int cached = this.surfaceBlocks[index];
        if (cached != ABSENT_BLOCK) {
            this.surfaceHits++;
            return cached;
        }
        int block = resolver.surfaceBlockFor(biomeId, depthBelowSurface);
        if (block < 0) {
            throw new IllegalArgumentException(
                    "block id " + block + " collides with the empty marker");
        }
        this.surfaceKeys[index] = key;
        this.surfaceBlocks[index] = block;
        this.surfaceCount++;
        if (this.surfaceCount * LOAD_DENOMINATOR >= this.surfaceCapacity * LOAD_NUMERATOR) {
            growSurface();
        }
        return block;
    }

    /** Drops both caches, for a dimension switch. */
    public void clear() {
        Arrays.fill(this.biomeIds, ABSENT_BIOME);
        this.biomeCount = 0;
        Arrays.fill(this.surfaceBlocks, ABSENT_BLOCK);
        this.surfaceCount = 0;
    }

    /**
     * Packs cell coordinates into an int.
     *
     * <p>Ten bits each of cell X and Z and eight of cell Y. A cell is four blocks, so that is a
     * 4096 by 1024 by 4096 block volume, and a chunk being generated sits far inside it. Cell
     * coordinates are block coordinates shifted right by two, so the range is generous.
     */
    public static int packCell(int cellX, int cellY, int cellZ) {
        return ((cellX & 0x3FF) << 18) | ((cellY & 0xFF) << 10) | (cellZ & 0x3FF);
    }

    private int biomeProbe(int key) {
        int index = spread(key) & (this.biomeCapacity - 1);
        while (this.biomeIds[index] != ABSENT_BIOME) {
            if (this.biomeKeys[index] == key) {
                return index;
            }
            index = (index + 1) & (this.biomeCapacity - 1);
        }
        return index;
    }

    private int surfaceProbe(int key) {
        int index = spread(key) & (this.surfaceCapacity - 1);
        while (this.surfaceBlocks[index] != ABSENT_BLOCK) {
            if (this.surfaceKeys[index] == key) {
                return index;
            }
            index = (index + 1) & (this.surfaceCapacity - 1);
        }
        return index;
    }

    static int spread(int key) {
        int h = key ^ (key >>> 16);
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }

    private void growBiomes() {
        int[] oldKeys = this.biomeKeys;
        int[] oldIds = this.biomeIds;
        int oldCapacity = this.biomeCapacity;
        this.biomeCapacity = oldCapacity * 2;
        this.biomeKeys = new int[this.biomeCapacity];
        this.biomeIds = new int[this.biomeCapacity];
        Arrays.fill(this.biomeIds, ABSENT_BIOME);
        this.biomeCount = 0;
        for (int i = 0; i < oldCapacity; i++) {
            if (oldIds[i] == ABSENT_BIOME) {
                continue;
            }
            int index = biomeProbe(oldKeys[i]);
            this.biomeKeys[index] = oldKeys[i];
            this.biomeIds[index] = oldIds[i];
            this.biomeCount++;
        }
    }

    private void growSurface() {
        int[] oldKeys = this.surfaceKeys;
        int[] oldBlocks = this.surfaceBlocks;
        int oldCapacity = this.surfaceCapacity;
        this.surfaceCapacity = oldCapacity * 2;
        this.surfaceKeys = new int[this.surfaceCapacity];
        this.surfaceBlocks = new int[this.surfaceCapacity];
        Arrays.fill(this.surfaceBlocks, ABSENT_BLOCK);
        this.surfaceCount = 0;
        for (int i = 0; i < oldCapacity; i++) {
            if (oldBlocks[i] == ABSENT_BLOCK) {
                continue;
            }
            int index = surfaceProbe(oldKeys[i]);
            this.surfaceKeys[index] = oldKeys[i];
            this.surfaceBlocks[index] = oldBlocks[i];
            this.surfaceCount++;
        }
    }

    /** Distinct biome cells currently cached. */
    public int biomeCacheSize() {
        return this.biomeCount;
    }

    /** Distinct biome and depth pairs currently cached. */
    public int surfaceCacheSize() {
        return this.surfaceCount;
    }

    public long biomeReads() {
        return this.biomeReads;
    }

    public long biomeHits() {
        return this.biomeHits;
    }

    public long surfaceReads() {
        return this.surfaceReads;
    }

    public long surfaceHits() {
        return this.surfaceHits;
    }

    public double biomeHitRatePercent() {
        return this.biomeReads == 0 ? 0.0 : 100.0 * this.biomeHits / this.biomeReads;
    }

    public double surfaceHitRatePercent() {
        return this.surfaceReads == 0 ? 0.0 : 100.0 * this.surfaceHits / this.surfaceReads;
    }
}
