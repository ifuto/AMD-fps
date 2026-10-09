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
 * <p>Attenuation is {@code level - 1 - opacity}, matching Minecraft: one level per block of distance,
 * plus whatever the block itself absorbs. Glass attenuates by 0 and so costs only the distance; a
 * slab costs more. Reading opacity per neighbour rather than per cell is deliberate -- the same block
 * attenuates differently depending on nothing, but the neighbour's own opacity is what matters for
 * light entering it, and caching it per BlockState is a separate concern (see the backlog item on
 * BlockState light property caching).
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

    /** Direction offsets as flat deltas in x, y, z. */
    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    private final LightField field;
    private final LightQueue queue;

    /** Cells cleared during removal that need re-propagating into. Reused across updates. */
    private final LightQueue recheck = new LightQueue();

    private long cellsVisited;
    private long updatesApplied;
    private long updatesSkippedUnchanged;

    public LightEngine(LightField field) {
        this.field = field;
        this.queue = new LightQueue(field.cellCount());
    }

    public LightField field() {
        return this.field;
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
                int candidate = level - 1 - opacity.opacity(nx, ny, nz);
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
