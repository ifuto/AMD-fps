package net.amdfaster.collision;

/**
 * Sizes the entity search from where the entity is actually going, not where it asked to go.
 *
 * <p>An entity move is resolved in two passes. Block collision is cheap -- a handful of palette
 * lookups over the blocks the box crosses, eight for a player-sized box -- and it clamps the
 * movement. Entity collision is expensive: 27 chunk sections around the entity, each iterated.
 *
 * <p>The waste is that the entity pass is sized from the <em>requested</em> movement. A mob that
 * asked to fall twenty blocks and landed on stone after three is searched over the volume it asked
 * for. An entity pinned against a wall with zero freedom left is searched at all. Nothing can be
 * pushed into a volume the entity does not reach, so the search region is the clamped box, and it is
 * usually much smaller than the requested one.
 *
 * <h2>Why this is safe and skipping the pass is not</h2>
 *
 * <p>Entities push each other, so a zero block movement does not mean no entity can move this one.
 * Skipping the entity pass because the blocks said no would let entities overlap and stay overlapped,
 * which is the bug that shows up as a stack of mobs inside each other. Shrinking the region is
 * different: an entity can only be pushed by something within reach of where it ends up, and the
 * clamped box is where it ends up.
 *
 * <p>The region is grown by the largest entity push the game applies, because a pusher does not have
 * to overlap the box to move it. Getting that constant wrong in the small direction produces
 * overlapping mobs; in the large direction it only costs the search this exists to avoid.
 *
 * <p>Not thread safe, and holds no state between calls.
 */
public final class MovementRegion {

    /**
     * Blocks an entity search is widened by on every side.
     *
     * <p>A pusher reaches slightly past the box it pushes. The value is deliberately generous: too
     * small lets mobs overlap, too large only costs the search this exists to avoid.
     */
    public static final double PUSH_REACH = 0.5;

    /** Blocks along a chunk section edge. */
    public static final int SECTION_SIZE = 16;

    /** Sections searched when the region is taken from the requested movement, for comparison. */
    public static final int FULL_SEARCH_SECTIONS = 27;

    private long resolutions;
    private long sectionsFromRequested;
    private long sectionsFromClamped;
    private long skippedEntirely;

    /** An axis-aligned box, and the section range covering it once widened. */
    public static final class Region {
        /** Inclusive lowest section on each axis. */
        public final int minX;
        public final int minY;
        public final int minZ;

        /** Inclusive highest section on each axis. */
        public final int maxX;
        public final int maxY;
        public final int maxZ;

        Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        /** How many sections this region covers. */
        public int sectionCount() {
            return (this.maxX - this.minX + 1)
                    * (this.maxY - this.minY + 1)
                    * (this.maxZ - this.minZ + 1);
        }

        /** Whether a section falls inside the region. */
        public boolean contains(int sectionX, int sectionY, int sectionZ) {
            return sectionX >= this.minX && sectionX <= this.maxX
                    && sectionY >= this.minY && sectionY <= this.maxY
                    && sectionZ >= this.minZ && sectionZ <= this.maxZ;
        }
    }

    /**
     * The region to search for entities after block collision has clamped the movement.
     *
     * @param minX the entity's box before the move, on the low X side
     * @param minY ditto, low Y
     * @param minZ ditto, low Z
     * @param maxX the entity's box before the move, on the high X side
     * @param maxY ditto, high Y
     * @param maxZ ditto, high Z
     * @param clampedDx the X movement left after block collision
     * @param clampedDy the Y movement left after block collision
     * @param clampedDz the Z movement left after block collision
     */
    public Region afterBlocks(double minX, double minY, double minZ,
                              double maxX, double maxY, double maxZ,
                              double clampedDx, double clampedDy, double clampedDz) {
        this.resolutions++;
        double lowX = Math.min(minX, minX + clampedDx) - PUSH_REACH;
        double highX = Math.max(maxX, maxX + clampedDx) + PUSH_REACH;
        double lowY = Math.min(minY, minY + clampedDy) - PUSH_REACH;
        double highY = Math.max(maxY, maxY + clampedDy) + PUSH_REACH;
        double lowZ = Math.min(minZ, minZ + clampedDz) - PUSH_REACH;
        double highZ = Math.max(maxZ, maxZ + clampedDz) + PUSH_REACH;

        Region region = new Region(
                sectionOf(lowX), sectionOf(lowY), sectionOf(lowZ),
                sectionOf(highX), sectionOf(highY), sectionOf(highZ));
        this.sectionsFromClamped += region.sectionCount();
        return region;
    }

    /**
     * The region a caller searching from the requested movement would use.
     *
     * <p>Exists so the saving can be measured against what the search would otherwise have covered
     * rather than against a guess.
     */
    public Region beforeBlocks(double minX, double minY, double minZ,
                               double maxX, double maxY, double maxZ,
                               double requestedDx, double requestedDy, double requestedDz) {
        double lowX = Math.min(minX, minX + requestedDx) - PUSH_REACH;
        double highX = Math.max(maxX, maxX + requestedDx) + PUSH_REACH;
        double lowY = Math.min(minY, minY + requestedDy) - PUSH_REACH;
        double highY = Math.max(maxY, maxY + requestedDy) + PUSH_REACH;
        double lowZ = Math.min(minZ, minZ + requestedDz) - PUSH_REACH;
        double highZ = Math.max(maxZ, maxZ + requestedDz) + PUSH_REACH;

        Region region = new Region(
                sectionOf(lowX), sectionOf(lowY), sectionOf(lowZ),
                sectionOf(highX), sectionOf(highY), sectionOf(highZ));
        this.sectionsFromRequested += region.sectionCount();
        return region;
    }

    /**
     * Whether the entity can be pushed at all, which is whether its region covers more than its own
     * footprint.
     *
     * <p>This does <em>not</em> mean the entity pass can be skipped when false. Entities push each
     * other, so a pinned entity can still be shoved. It means the search covers one section and can
     * be answered from the occupancy count rather than an iteration.
     */
    public boolean isConfined(Region region) {
        boolean confined = region.sectionCount() == 1;
        if (confined) {
            this.skippedEntirely++;
        }
        return confined;
    }

    /**
     * Section index for a coordinate.
     *
     * <p>Floor division rather than a shift, so negative coordinates land in the section below zero
     * instead of the one above. A shift truncates towards zero and would put -0.5 in section 0,
     * which is the section that does not contain it.
     */
    public static int sectionOf(double coordinate) {
        int section = (int) Math.floor(coordinate / SECTION_SIZE);
        return section;
    }

    public long resolutions() {
        return this.resolutions;
    }

    public long sectionsFromRequested() {
        return this.sectionsFromRequested;
    }

    public long sectionsFromClamped() {
        return this.sectionsFromClamped;
    }

    /** Resolutions where the whole search fitted in one section. */
    public long confinedResolutions() {
        return this.skippedEntirely;
    }

    /** Percentage of sections avoided by sizing from the clamped movement. */
    public double savedSectionPercent() {
        if (this.sectionsFromRequested == 0) {
            return 0.0;
        }
        long saved = this.sectionsFromRequested - this.sectionsFromClamped;
        return 100.0 * saved / this.sectionsFromRequested;
    }
}
