package net.amdfaster.mc;

/**
 * A section's block ids read once into a flat array, so meshing never resolves the section again.
 *
 * <p>The problem is not that reading a block is slow. It is that reading it goes through the whole
 * chain every time: coordinate to chunk, chunk to section, section to palette container, container to
 * palette entry. Meshing one section calls that 24 576 times for the six face orientations of 4096
 * cells, and every one of those calls re-derives the same chunk and the same section that the
 * previous call had just derived. The palette is the same object the whole time.
 *
 * <p>So the chain is walked once, into an array indexed by cell, and the 24 576 reads become array
 * loads. This is what a block state cache approximates from the outside; resolving the section once is
 * what removes the work rather than hiding it behind a hit rate.
 *
 * <p>Two things about palettes that a straight copy gets wrong, both of which have bitten real
 * implementations:
 *
 * <p><b>A palette lookup can miss.</b> An index in the packed data can name a palette entry that is
 * not there, and the container answers with its default value rather than failing. A snapshot that
 * stores the raw index and resolves it later has to do the same, or a section that would render fine
 * in vanilla becomes a crash during meshing. {@link #idAt} therefore reports {@link #MISSING} and the
 * caller substitutes the default.
 *
 * <p><b>A uniform section should not have an array.</b> A section that is entirely air or entirely
 * stone holds one distinct value, and palette storage has a single-valued mode for exactly that. Those
 * sections are common -- everything above the terrain and everything below the first cave -- so
 * allocating 16 KB of ids for them is a waste that adds up across the loaded world. The array here is
 * null for a uniform section.
 *
 * <p>Not thread safe. A snapshot is captured on the render thread and is invalid the moment the
 * section changes; callers must recapture or drop it.
 */
public final class SectionSnapshot {

    /** Blocks per section edge. */
    public static final int SIZE = 16;

    public static final int CELL_COUNT = SIZE * SIZE * SIZE;

    /**
     * Returned by {@link #idAt} when the stored index names a palette entry that does not exist.
     *
     * <p>A real block id is never negative, so a negative sentinel cannot collide with data. The caller
     * substitutes the section's default value, which is what the container itself does.
     */
    public static final int MISSING = -1;

    private final int originX;
    private final int originY;
    private final int originZ;

    /** Null when the section is uniform, which is the common case for air and for solid rock. */
    private int[] ids;

    private int uniformId;
    private boolean uniform;

    /**
     * Whether {@link #capture} has run.
     *
     * <p>A separate flag rather than inferring it from the stored values, because a legitimately
     * captured section can be uniformly {@link #MISSING} and would then look uncaptured. Same reason
     * {@code first} below cannot use MISSING as its unset marker: the sentinel is a value the data can
     * hold, and a value that data can hold cannot mark its own absence.
     */
    private boolean captured;

    /** The last id resolved through the caller's resolver, and what it resolved to. */
    private int cachedId = MISSING;
    private int cachedResolved;
    private long cacheHits;
    private long cacheMisses;

    private long captureReads;
    private long idReads;

    public SectionSnapshot(int originX, int originY, int originZ) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.uniform = true;
        this.uniformId = MISSING;
    }

    public static SectionSnapshot forSection(int sectionX, int sectionY, int sectionZ) {
        return new SectionSnapshot(sectionX << 4, sectionY << 4, sectionZ << 4);
    }

    /** Reads a block id from the world. Called once per cell during capture, and never again. */
    public interface SectionReader {
        int read(int x, int y, int z);
    }

    public int originX() {
        return this.originX;
    }

    public int originY() {
        return this.originY;
    }

    public int originZ() {
        return this.originZ;
    }

    /**
     * Reads the whole section once.
     *
     * @return the number of reads performed, which is {@link #CELL_COUNT} -- reported so a caller can
     *         compare it against the reads a meshing pass would have made
     */
    public long capture(SectionReader reader) {
        long before = this.captureReads;
        int first = 0;
        boolean seenFirst = false;
        boolean allSame = true;
        int[] array = new int[CELL_COUNT];
        int index = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    int id = reader.read(this.originX + x, this.originY + y, this.originZ + z);
                    this.captureReads++;
                    array[index++] = id;
                    if (!seenFirst) {
                        first = id;
                        seenFirst = true;
                    } else if (id != first) {
                        allSame = false;
                    }
                }
            }
        }
        if (allSame) {
            // Single valued. Drop the array rather than keep 16 KB of one repeated number, which is
            // what palette storage's own single-valued mode is for.
            this.uniform = true;
            this.uniformId = first;
            this.ids = null;
        } else {
            this.uniform = false;
            this.uniformId = MISSING;
            this.ids = array;
        }
        this.cachedId = MISSING;
        this.captured = true;
        return this.captureReads - before;
    }

    /** Whether every cell in the section holds the same id. */
    public boolean isUniform() {
        return this.uniform;
    }

    /** The single id a uniform section holds, or {@link #MISSING} if it is not uniform. */
    public int uniformId() {
        return this.uniformId;
    }

    /** Bytes held by this snapshot. Zero for a uniform section. */
    public int bytesHeld() {
        return this.ids == null ? 0 : this.ids.length << 2;
    }

    public boolean isCaptured() {
        return this.captured;
    }

    /**
     * The block id at a world coordinate, or {@link #MISSING}.
     *
     * <p>An array load for a non-uniform section and a field read for a uniform one. No coordinate
     * arithmetic beyond the offset, no chunk lookup, no palette walk.
     */
    public int idAt(int x, int y, int z) {
        this.idReads++;
        int lx = x - this.originX;
        int ly = y - this.originY;
        int lz = z - this.originZ;
        // Bounds first, and this order is not a style choice. A uniform section has no array to index,
        // so returning its id without checking would answer for every coordinate in the world -- the
        // whole planet reads as stone. Meshing reaches one block past the border on every face it
        // tests, so it would conclude that every border face is hidden and emit nothing at all.
        if (((lx | ly | lz) < 0) || lx >= SIZE || ly >= SIZE || lz >= SIZE) {
            return MISSING;
        }
        if (this.uniform) {
            return this.uniformId;
        }
        if (this.ids == null) {
            return MISSING;
        }
        return this.ids[(ly * SIZE + lz) * SIZE + lx];
    }

    /**
     * Resolves an id through the caller's resolver, caching the last one.
     *
     * <p>A meshing walk visits cells in a line, and neighbouring cells very often share a block id --
     * a wall of stone, a floor of dirt. Caching the last resolution turns most of those into a field
     * comparison. The hit rate is reported because whether it earns its keep depends entirely on the
     * terrain, and guessing would be worse than measuring.
     *
     * @param resolver called with the block id, returning whatever the caller derives from it
     */
    public int resolve(int x, int y, int z, Resolver resolver) {
        int id = idAt(x, y, z);
        if (id == this.cachedId) {
            this.cacheHits++;
            return this.cachedResolved;
        }
        this.cacheMisses++;
        this.cachedId = id;
        this.cachedResolved = resolver.resolve(id);
        return this.cachedResolved;
    }

    /** Turns a block id into whatever the caller needs. Called once per distinct id in a run. */
    public interface Resolver {
        int resolve(int blockId);
    }

    public long captureReads() {
        return this.captureReads;
    }

    /** Reads served after capture. This is the number that was 24 576 chunk lookups before. */
    public long idReads() {
        return this.idReads;
    }

    public long resolveCacheHits() {
        return this.cacheHits;
    }

    public long resolveCacheMisses() {
        return this.cacheMisses;
    }

    /** Distinct ids present. Used to size a caller's own per-id table. */
    public int distinctIds() {
        if (!this.captured) {
            return 0;
        }
        if (this.uniform) {
            return 1;
        }
        if (this.ids == null) {
            return 0;
        }
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (int id : this.ids) {
            seen.add(id);
        }
        return seen.size();
    }

    public void clear() {
        this.ids = null;
        this.uniform = true;
        this.uniformId = MISSING;
        this.cachedId = MISSING;
        this.captured = false;
    }
}
