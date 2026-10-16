package net.amdfaster.hopper;

/**
 * Skips looking for items in a position that cannot contain one.
 *
 * <p>A hopper searches the space above it for item entities every few ticks. The search asks the world for
 * whatever entities are inside a box, and the world answers by checking every entity whose bounding box
 * overlaps it. That answer is correct, and most of the time it is "nothing" -- because the space above a
 * hopper in a storage system is usually solid stone, and an item cannot be inside solid stone.
 *
 * <p>Checking the block first and skipping the entity search when it fully encloses the space removes the
 * query entirely, which is the whole cost.
 *
 * <h2>Only a block that fully encloses</h2>
 *
 * <p>A slab, a stair, a fence and a snow layer all leave room, and an item can sit on or inside the space
 * they occupy. Treating them as occluding would stop a hopper under a slab from ever picking anything up,
 * which is not a saving -- it is the hopper being broken. So the test is whether the block fills the whole
 * cube, not whether it is solid to the touch.
 *
 * <h2>The block has to be checked, not assumed</h2>
 *
 * <p>A hopper under a chest is not under stone, and a player can place a block or break one at any moment. So
 * the block is read each time the decision is made rather than remembered from when the hopper was placed,
 * and a change is picked up on the next tick. Remembering it would mean a hopper that stops working when
 * something is built over it and starts working again when that is removed -- with no way for the player to
 * see why.
 *
 * <h2>What is skipped is the query, not the hopper</h2>
 *
 * <p>The hopper still ticks, still pulls from the container above it if there is one, and still pushes into
 * whatever is below. Only the search for loose item entities in an enclosed space is skipped, because that
 * is the part that cannot find anything. A skipper that stopped the hopper ticking would break the machines
 * built out of them.
 *
 * <h2>Why this matters more than it looks</h2>
 *
 * <p>Hoppers are the single most common block entity in a built world -- a storage room has hundreds, and a
 * farm has dozens more. Each one asks the same question every few ticks, and in the common case the answer is
 * the same every time. The saving is not in one hopper, it is in a thousand of them agreeing that there is
 * nothing above them.
 *
 * <p>Not thread safe. Hoppers tick on the server thread.
 */
public final class OccludedPositionSkipper {

    /** Milliseconds the entity search costs, which is what skipping avoids. */
    public static final double MS_PER_SEARCH = 0.01;

    /** Milliseconds reading the block costs, which happens either way. */
    public static final double MS_PER_CHECK = 0.0005;

    private long checks;
    private long skipped;
    private long searched;
    private long partialBlocks;

    /**
     * Whether the entity search can be skipped.
     *
     * @param fullyEnclosed whether the block at that position fills the whole cube
     * @return true if the search would find nothing and can be skipped
     */
    public boolean canSkip(boolean fullyEnclosed) {
        this.checks++;

        if (fullyEnclosed) {
            // An item cannot be inside solid stone, so the answer to the search is already known.
            this.skipped++;
            return true;
        }
        this.partialBlocks++;
        this.searched++;
        return false;
    }

    /**
     * Whether a block fills the whole cube.
     *
     * <p>A slab, a stair, a fence and a snow layer all leave room and an item can sit in the space they
     * occupy. Treating them as occluding would stop a hopper under a slab from ever picking anything up,
     * which is not a saving -- it is the hopper being broken.
     *
     * @param fullCube whether the block's shape is the full cube
     * @param air whether the position is empty
     */
    public static boolean encloses(boolean fullCube, boolean air) {
        return fullCube && !air;
    }

    public long checks() {
        return this.checks;
    }

    /** How many searches were skipped. */
    public long skipped() {
        return this.skipped;
    }

    /** How many had to run. */
    public long searched() {
        return this.searched;
    }

    /** How many positions were not fully enclosed. */
    public long partialBlocks() {
        return this.partialBlocks;
    }

    public double skippedPercent() {
        return this.checks == 0 ? 0.0 : 100.0 * this.skipped / this.checks;
    }

    /** Milliseconds of entity searching avoided, net of the block reads. */
    public double msSaved() {
        return this.skipped * MS_PER_SEARCH - this.checks * MS_PER_CHECK;
    }

    /** Milliseconds the searches would have cost. */
    public double msWithoutSkipping() {
        return this.checks * MS_PER_SEARCH;
    }

    /**
     * The share of the search cost avoided, net of the block reads.
     *
     * <p>Zero before any position has been checked. A skipper that has checked nothing has technically
     * avoided everything, but reporting that as a saving would describe an unused one as though it had
     * done something.
     *
     * <p>The block reads are subtracted, because checking costs something -- a skipper whose check cost as
     * much as the search would save nothing and should report that.
     */
    public double savedPercent() {
        double without = msWithoutSkipping();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, msSaved()) / without;
    }
}
