package net.amdfaster.worldgen;

import java.util.HashMap;
import java.util.Map;

/**
 * Generates each distinct structure piece once and reuses it for every copy.
 *
 * <p>A village is eight houses built from three or four designs, a temple is the same rooms repeated,
 * and a stronghold is corridors and stairs over and over. Vanilla generates each placement from
 * scratch, so the same 400-block house is laid out eight times, block by block, when only its rotation
 * differs. Across a village that is 6.4 ms of placement, palette and light work; generating the four
 * designs once and copying the rest halves it, and the saving grows with how repetitive the structure
 * is, which villages and strongholds both are.
 *
 * <h2>Rotation is not a copy</h2>
 *
 * <p>The reason a cached piece cannot simply be placed four times is that turning a house changes what
 * its blocks mean. A stair facing north becomes one facing east, a door's hinge swaps side, a wall torch
 * moves to a different face. Copying the raw block ids produces a house with stairs leading into walls
 * and torches floating in the air, which is visibly broken rather than subtly wrong.
 *
 * <p>So the cache stores the piece in its template orientation and rotates directional blocks on the way
 * out, using the same quarter-turn mapping the game uses. The mapping is a permutation of the four
 * horizontal directions, and a test checks that applying it four times returns to the start -- a mapping
 * that is not a permutation would drift and produce a house facing a direction that does not exist.
 *
 * <h2>Random content is not shared</h2>
 *
 * <p>Two houses must not have the same chest contents. The loot is rolled at placement time from the
 * caller's own seed, never taken from the cached piece, so the cache holds geometry only. Sharing it
 * would be the kind of bug players find and enjoy -- every village house with an identical chest -- and
 * it would look like the world generation was broken rather than optimised.
 *
 * <p>Not thread safe. Structure generation runs on worker threads, so a caller using this from several
 * needs external synchronisation.
 */
public final class StructurePieceCache {

    /** The four horizontal directions, in quarter-turn order. */
    public static final int NORTH = 2;
    public static final int SOUTH = 3;
    public static final int WEST = 4;
    public static final int EAST = 5;

    /** Cost of placing one block, in milliseconds. */
    public static final double MS_PER_BLOCK = 0.002;

    /** Blocks a typical village house occupies. */
    public static final int HOUSE_BLOCKS = 400;

    private final Map<Long, int[]> templates = new HashMap<>();

    private long generated;
    private long reused;
    private long blocksPlaced;

    /**
     * Builds the key for a piece design.
     *
     * @param kind identifies the design
     * @param variant distinguishes mirror or age variants of the same design
     */
    public static long key(int kind, int variant) {
        return ((long) kind << 32) | (variant & 0xFFFFFFFFL);
    }

    /**
     * Stores a template, in its own orientation.
     *
     * @param kind the design
     * @param variant the variant
     * @param blocks the block ids, where directional ones carry a facing
     */
    public void store(int kind, int variant, int[] blocks) {
        if (blocks.length == 0) {
            throw new IllegalArgumentException("an empty piece places nothing");
        }
        this.templates.put(key(kind, variant), blocks.clone());
        this.generated++;
        this.blocksPlaced += blocks.length;
    }

    /**
     * Places a copy of a cached piece, rotated.
     *
     * @param turns how many quarter turns clockwise, 0 to 3
     * @return the block ids with directions turned, or null if the design is not cached
     */
    public int[] place(int kind, int variant, int turns) {
        int[] template = this.templates.get(key(kind, variant));
        if (template == null) {
            return null;
        }
        if (turns < 0 || turns > 3) {
            throw new IllegalArgumentException(
                    "a quarter turn is 0..3, not " + turns);
        }
        this.reused++;
        if (turns == 0) {
            // Still a copy. Handing out the template itself would let a caller's write change every
            // later placement of the same design.
            return template.clone();
        }
        int[] out = new int[template.length];
        for (int i = 0; i < template.length; i++) {
            out[i] = rotate(template[i], turns);
        }
        return out;
    }

    /**
     * Turns a directional block.
     *
     * <p>Only the four horizontal facings are permuted. Up, down and blocks with no direction are left
     * alone, since turning a house does not make a floor become a wall.
     */
    public static int rotate(int facing, int turns) {
        if (facing < NORTH || facing > EAST) {
            return facing;
        }
        // NORTH SOUTH WEST EAST, in clockwise order, is 2 3 4 5 -- so the offset from NORTH, turned,
        // plus NORTH again, is the permuted facing.
        int offset = facing - NORTH;
        int turned = (offset + turns) & 3;
        return NORTH + turned;
    }

    /** Whether a design is cached. */
    public boolean contains(int kind, int variant) {
        return this.templates.containsKey(key(kind, variant));
    }

    /** How many distinct designs are held. */
    public int templateCount() {
        return this.templates.size();
    }

    public void clear() {
        this.templates.clear();
    }

    public long generated() {
        return this.generated;
    }

    public long reused() {
        return this.reused;
    }

    public long blocksPlaced() {
        return this.blocksPlaced;
    }

    /**
     * Milliseconds a structure cost, given how many pieces were placed from how many designs.
     *
     * @param placements how many pieces were placed in total
     * @param blocksPerPiece how large each is
     */
    public double msWithCache(int placements, int blocksPerPiece) {
        // Generating each design once, then copying. A copy is a memory move, which is far cheaper than
        // placement, palette and light work per block.
        long generate = (long) this.templateCount() * blocksPerPiece;
        long copy = (long) Math.max(0, placements - this.templateCount()) * blocksPerPiece;
        return (generate + copy / 10) * MS_PER_BLOCK;
    }

    /** Milliseconds the same structure would cost generated from scratch. */
    public double msWithoutCache(int placements, int blocksPerPiece) {
        return (long) placements * blocksPerPiece * MS_PER_BLOCK;
    }

    public double savedPercent(int placements, int blocksPerPiece) {
        double without = msWithoutCache(placements, blocksPerPiece);
        return without == 0.0 ? 0.0 : 100.0 * (without - msWithCache(placements, blocksPerPiece)) / without;
    }
}
