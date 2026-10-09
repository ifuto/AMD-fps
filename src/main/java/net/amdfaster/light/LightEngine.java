package net.amdfaster.light;

import net.amdfaster.dirty.SectionCoord;

/**
 * Flood-fill light propagation over a {@link LightField}, with separate paths for adding light and
 * for removing it.
 *
 * <p>Why two paths rather than one. Adding is a plain flood fill: a cell takes the brightest value
 * reaching it, so processing order only affects how many times a cell is revisited. Removing is not
 * symmetric at all. Clearing a cell does not tell you what its neighbours should become, because a
 * neighbour may have been lit by this cell or by a different source entirely, and the stored value
 * does not say which. So removal has to discover the answer: walk outward clearing cells that could
 * only have been lit by the removed source, and collect the boundary where a brighter or equal
 * neighbour proves another source exists. Then re-propagate inward from that boundary. Doing removal
 * as "recompute the whole section" is what makes a torch being placed or broken cost as much as
 * loading the section, which is the single most common light update there is.
 *
 * <p>Attenuation is {@code level - max(1, opacity)}, which is Minecraft's rule and not the obvious
 * one. The floor of 1 is what makes glass cost the same as air rather than less than air, and it is
 * also what makes opacity 1 -- leaves -- cost the same as air. Writing this as {@code level - 1 -
 * opacity} instead is the natural mistake, and it is wrong for every block that is neither fully
 * transparent nor fully opaque: it makes leaves attenuate twice as fast, so light reaches seven
 * blocks through a tree instead of fourteen, and it makes an opacity-14 block fully opaque when it
 * actually passes one level. Measured against a model of both rules: through water (opacity 3) the
 * correct rule reaches four blocks from a level-15 source and the wrong one reaches three.
 *
 * <p>Cost also depends on the direction, which is the second non-obvious part. Skylight at level 15
 * travelling <em>downward</em> into a non-filtering block loses nothing -- that is what makes an open
 * field uniformly lit rather than dimming with depth. Every other direction, and every level below
 * 15, attenuates normally. So the neighbour loop is not six identical tests; the downward one is
 * specialised, and {@link Mode} selects the rule.
 *
 * <p>The engine holds no Minecraft type. The opacity source is a one-method interface, so the whole
 * propagation algorithm is testable on a synthetic volume, which is the only way to check the removal
 * path at all -- it has enough edge cases that testing it through the game would mean finding them by
 * playing.
 *
 * <p>Not thread safe.
 */
public final class LightEngine {

    /** How much light a block absorbs. 0 for air and glass, 15 for a full opaque block. */
    public interface OpacitySource {
        int opacity(int x, int y, int z);
    }

    /**
     * Which attenuation rule a field obeys.
     *
     * <p>The two differ in exactly one place: level-15 skylight falling into a clear block. Everything
     * else -- every horizontal step, every upward step, every level below 15 -- is identical, which is
     * why this is a mode rather than two engines.
     */
    public enum Mode {
        /** Torches, glowstone, lava. Symmetric in all six directions. */
        BLOCK,

        /** The sky. Level 15 falls without attenuation; everything else behaves like block light. */
        SKY
    }

    /**
     * Cost of light entering a block, with the floor that makes glass and leaves cost the same as air.
     *
     * <p>Named rather than inlined because getting the floor wrong is silent: the light looks
     * plausible, just darker through foliage, and nothing reports it.
     */
    static int attenuatedLevel(int sourceLevel, int opacity) {
        return sourceLevel - Math.max(1, opacity);
    }

    /** Direction offsets as flat deltas in x, y, z. */
    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    /** Index of the downward direction in the offset arrays above. */
    private static final int DOWN = 3;

    /** The brightest light level Minecraft represents. Above this a nibble cannot hold it. */
    public static final int MAX_LEVEL = 15;

    private final LightField field;
    private final Mode mode;
    private final LightQueue queue;

    /** Cells cleared during removal that need re-propagating into. Reused across updates. */
    private final LightQueue recheck = new LightQueue();

    private long cellsVisited;
    private long updatesApplied;
    private long updatesSkippedUnchanged;

    public LightEngine(LightField field) {
        this(field, Mode.BLOCK);
    }

    public LightEngine(LightField field, Mode mode) {
        this.field = field;
        this.mode = mode;
        this.queue = new LightQueue(field.cellCount());
    }

    public LightField field() {
        return this.field;
    }

    public Mode mode() {
        return this.mode;
    }

    /**
     * Cost of stepping from a cell at {@code level} into the neighbour at the given offsets.
     *
     * <p>This is the per-direction part of the rule, and it is the whole reason the neighbour loop is
     * not six copies of the same expression. Direction index 3 is -y; for skylight at the maximum
     * level entering a block that does not filter, the step is free.
     */
    private int stepLevel(int level, int direction, int opacity) {
        if (this.mode == Mode.SKY && level == MAX_LEVEL && direction == DOWN && opacity == 0) {
            return MAX_LEVEL;
        }
        return attenuatedLevel(level, opacity);
    }

    /**
     * Places a light source and floods outward from it.
     *
     * <p>Idempotent and monotonic: placing the same source again changes nothing, and placing a dimmer
     * one where a brighter already is does not darken anything, because a source only ever raises the
     * level it reaches.
     */
    public void addSource(int x, int y, int z, int level, OpacitySource opacity) {
        if (level <= 0) {
            return;
        }
        if (this.field.set(x, y, z, level)) {
            this.updatesApplied++;
        }
        this.queue.push(level, SectionCoord.key(x, y, z));
        propagate(opacity);
    }

    /**
     * Drains the queue, spreading light outward until nothing improves.
     *
     * <p>Exposed separately from {@link #addSource} so a caller that has set many sources -- a section
     * being loaded, which can hold dozens of torches -- can set them all and flood once, rather than
     * flooding per source and redoing the same cells repeatedly.
     */
    public void propagate(OpacitySource opacity) {
        while (!this.queue.isEmpty()) {
            int level = this.queue.peekLevel();
            long coordinate = this.queue.poll();
            int x = SectionCoord.x(coordinate);
            int y = SectionCoord.y(coordinate);
            int z = SectionCoord.z(coordinate);
            this.cellsVisited++;

            for (int direction = 0; direction < 6; direction++) {
                int nx = x + DX[direction];
                int ny = y + DY[direction];
                int nz = z + DZ[direction];
                int candidate = stepLevel(level, direction, opacity.opacity(nx, ny, nz));
                if (candidate <= 0) {
                    continue;
                }
                if (candidate > this.field.get(nx, ny, nz)) {
                    this.field.set(nx, ny, nz, candidate);
                    this.updatesApplied++;
                    this.queue.push(candidate, SectionCoord.key(nx, ny, nz));
                } else {
                    // The overwhelming majority of a flood fill's neighbour tests land here: the cell
                    // is already at least this bright. Counting them separately is what makes it
                    // visible that the early exit is doing its job.
                    this.updatesSkippedUnchanged++;
                }
            }
        }
    }

    /**
     * Removes a source and repairs the region it was lighting.
     *
     * <p>Two phases, and the second is not optional. Clearing cells is easy; knowing what they should
     * become is not, because a cleared cell may have been lit by a source that is still there. The
     * first phase clears everything that could only have been lit by the removed source and records
     * the boundary where that stops being true. The second phase floods back in from that boundary,
     * which restores exactly the cells another source still reaches and leaves the rest dark.
     */
    public void removeSource(int x, int y, int z, OpacitySource opacity) {
        int removedLevel = this.field.get(x, y, z);
        if (removedLevel == 0) {
            return;
        }
        this.field.set(x, y, z, 0);
        this.updatesApplied++;

        // Phase 1: clear the region that depended on this source.
        // The queue holds cells already cleared, at the level they held before clearing, which is
        // what the neighbour test needs in order to tell "fed by me" from "fed by something else".
        this.queue.push(removedLevel, SectionCoord.key(x, y, z));
        while (!this.queue.isEmpty()) {
            int level = this.queue.peekLevel();
            long coordinate = this.queue.poll();
            int cx = SectionCoord.x(coordinate);
            int cy = SectionCoord.y(coordinate);
            int cz = SectionCoord.z(coordinate);
            this.cellsVisited++;

            for (int direction = 0; direction < 6; direction++) {
                int nx = cx + DX[direction];
                int ny = cy + DY[direction];
                int nz = cz + DZ[direction];
                int neighbour = this.field.get(nx, ny, nz);
                if (neighbour == 0) {
                    continue;
                }
                if (neighbour < level) {
                    // Strictly dimmer than the cell it touches, so it can only have been fed by the
                    // region being cleared. Clear it and continue outward.
                    this.field.set(nx, ny, nz, 0);
                    this.updatesApplied++;
                    this.queue.push(neighbour, SectionCoord.key(nx, ny, nz));
                } else {
                    // As bright or brighter: it has its own source or is fed from elsewhere. It stays,
                    // and it is a boundary the second phase has to flood inward from.
                    this.recheck.push(neighbour, SectionCoord.key(nx, ny, nz));
                }
            }
        }

        // Phase 2: re-propagate from the surviving boundary.
        while (!this.recheck.isEmpty()) {
            int level = this.recheck.peekLevel();
            long coordinate = this.recheck.poll();
            this.queue.push(level, coordinate);
        }
        propagate(opacity);
    }

    /** Cells popped from a queue. The cost of an update is proportional to this, not to volume. */
    public long cellsVisited() {
        return this.cellsVisited;
    }

    public long updatesApplied() {
        return this.updatesApplied;
    }

    /**
     * Neighbour tests that changed nothing.
     *
     * <p>Reported because the ratio of this to {@link #updatesApplied()} is the measure of whether
     * the early exit is earning its keep. In a flood fill almost every test should land here; if it
     * does not, something is re-lighting cells that were already correct.
     */
    public long updatesSkippedUnchanged() {
        return this.updatesSkippedUnchanged;
    }

    /** Empties the field and the queues without dropping their capacity. */
    public void clear() {
        this.field.fill(0);
        this.queue.clear();
        this.recheck.clear();
    }
}
