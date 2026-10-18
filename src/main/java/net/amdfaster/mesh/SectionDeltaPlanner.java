package net.amdfaster.mesh;

/**
 * Works out which part of a section one block change actually invalidates.
 *
 * <p>Placing or removing a block marks its whole section dirty, and the section is then re-meshed: every
 * cell swept, every face tested against its neighbours, ambient occlusion baked into every vertex. A
 * section is 4,096 cells. The change touched one.
 *
 * <p>What a change really affects is the block itself and the six faces that look at it -- a 3 by 3 by 3
 * neighbourhood, 27 cells, 0.66 per cent of the section. Everything else in the section has the same
 * neighbours it had before and produces the same quads.
 *
 * <h2>Greedy merging is what stops this being simply 27 cells</h2>
 *
 * <p>A merged quad can span a whole row of identical faces. Changing one block inside such a run does not
 * change one quad, it splits or shrinks a quad that covered sixteen cells, so the entire run has to be
 * re-derived. Along the merge axis the affected region therefore expands to the full run, which at its
 * worst is the whole section on that axis.
 *
 * <p>That expansion is the correctness requirement and not a pessimistic guess. A planner that rebuilt
 * only the 27 cells would leave a merged quad spanning cells that no longer agree with the blocks, and
 * the result is a face drawn where there is no surface or missing where there is one -- visible, and not
 * logged, because nothing went wrong from the mesh builder's point of view. It rebuilt exactly what it was
 * told to.
 *
 * <h2>Why it is still worth doing</h2>
 *
 * <p>Even the worst case is one orientation's slab, and a change usually affects one or two faces rather
 * than all six. A 3 by 3 patch on a single face is nine quads against a full solid section's 1,536, which
 * is one meshlet instead of twenty-five. The saving is not uniform and this class does not pretend
 * otherwise: it reports the region it computed so the caller can decide, and a caller that would rather
 * rebuild the section can ignore it.
 *
 * <p>Not thread safe.
 */
public final class SectionDeltaPlanner {

    /** A section is this many blocks on an edge. */
    public static final int SECTION_SIZE = 16;

    /** Cells in a section. */
    public static final int SECTION_CELLS = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;

    /** Cells a block change touches directly: the block and the six faces looking at it. */
    public static final int NEIGHBOURHOOD_CELLS = 27;

    /** Quads per meshlet, matching {@code Meshlet.MAX_QUADS}. */
    public static final int QUADS_PER_MESHLET = 62;

    /** Quads on one face of a full solid section. */
    public static final int QUADS_PER_FACE = SECTION_SIZE * SECTION_SIZE;

    /** Quads in a full solid section: six faces. */
    public static final int QUADS_PER_SECTION = 6 * QUADS_PER_FACE;

    /** An axis-aligned region of cells, in section-local coordinates. */
    public record Region(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {

        /** How many cells the region covers. */
        public int cells() {
            return this.sizeX * this.sizeY * this.sizeZ;
        }

        /** Whether a section-local coordinate falls inside. */
        public boolean contains(int x, int y, int z) {
            return x >= this.minX && x < this.minX + this.sizeX
                    && y >= this.minY && y < this.minY + this.sizeY
                    && z >= this.minZ && z < this.minZ + this.sizeZ;
        }
    }

    private long changes;
    private long cellsInvalidated;
    private long meshletsPlanned;
    private long meshletsAvoided;

    /**
     * The region one block change invalidates.
     *
     * @param x the changed block's x, in section-local coordinates, 0..15
     * @param y its y
     * @param z its z
     * @param greedyMerging true when the mesher merges runs of identical faces, which is the default and
     *     the reason the region is wider than 3 by 3 by 3
     * @return the region to re-mesh
     */
    public Region affectedRegion(int x, int y, int z, boolean greedyMerging) {
        check(x, y, z);
        this.changes++;

        // The 3x3x3 neighbourhood, clamped to the section. A block on the edge has fewer neighbours
        // inside this section; the ones outside belong to the neighbouring section and are handled by
        // that section's own rebuild.
        int minX = Math.max(0, x - 1);
        int minY = Math.max(0, y - 1);
        int minZ = Math.max(0, z - 1);
        int maxX = Math.min(SECTION_SIZE, x + 2);
        int maxY = Math.min(SECTION_SIZE, y + 2);
        int maxZ = Math.min(SECTION_SIZE, z + 2);

        if (greedyMerging) {
            // A merged run can span the section on any axis, and the change is inside one, so the run has
            // to be re-derived whole. Expanding on all three axes is conservative in the safe direction:
            // it rebuilds more than the minimum, never less, and rebuilding less is what leaves a merged
            // quad spanning cells that no longer agree with the blocks.
            minX = 0;
            minY = 0;
            minZ = 0;
            maxX = SECTION_SIZE;
            maxY = SECTION_SIZE;
            maxZ = SECTION_SIZE;
        }

        Region region = new Region(minX, minY, minZ,
                maxX - minX, maxY - minY, maxZ - minZ);
        this.cellsInvalidated += region.cells();
        return region;
    }

    /**
     * How many meshlets a patch of this size needs, against a whole section.
     *
     * @param quads the quads the affected region produces
     * @return the meshlet count, at least one when there is anything to draw
     */
    public int meshletsFor(int quads) {
        if (quads < 0) {
            throw new IllegalArgumentException("quad count cannot be negative: " + quads);
        }
        int needed = quads == 0 ? 0 : (quads + QUADS_PER_MESHLET - 1) / QUADS_PER_MESHLET;
        this.meshletsPlanned += needed;
        this.meshletsAvoided += meshletsForSection() - needed;
        return needed;
    }

    /** How many meshlets a full solid section needs. */
    public static int meshletsForSection() {
        return (QUADS_PER_SECTION + QUADS_PER_MESHLET - 1) / QUADS_PER_MESHLET;
    }

    /**
     * The share of a section's cells this region does not touch.
     *
     * <p>Zero for a region covering the whole section, which is what greedy merging produces. Reported
     * rather than assumed, because the honest answer varies from 0.66 per cent to nothing depending on
     * whether merging is in play and which faces changed.
     */
    public static double cellsAvoidedPercent(Region region) {
        if (region == null) {
            throw new IllegalArgumentException("a region is required");
        }
        return 100.0 * (SECTION_CELLS - region.cells()) / SECTION_CELLS;
    }

    /** How many block changes have been planned. */
    public long changes() {
        return this.changes;
    }

    /** Cells invalidated across every planned change. */
    public long cellsInvalidated() {
        return this.cellsInvalidated;
    }

    public long meshletsPlanned() {
        return this.meshletsPlanned;
    }

    /** Meshlets not rebuilt, against rebuilding the whole section each time. */
    public long meshletsAvoided() {
        return this.meshletsAvoided;
    }

    private static void check(int x, int y, int z) {
        if (x < 0 || x >= SECTION_SIZE || y < 0 || y >= SECTION_SIZE || z < 0 || z >= SECTION_SIZE) {
            throw new IllegalArgumentException(
                    "section-local coordinates must be 0.." + (SECTION_SIZE - 1) + ": "
                            + x + ", " + y + ", " + z);
        }
    }
}
