package net.amdfaster.render;

/**
 * Picks the part of a connected texture to use for a face, from the four blocks beside it.
 *
 * <p>Glass panes, bookshelves and similar blocks are drawn as one continuous surface when several are
 * together, and as separate ones when they are not. The game achieves that by choosing a different region
 * of the texture per block, based on which of the four neighbours are the same block. With four neighbours
 * and two states each there are sixteen combinations, and each maps to one region of the texture.
 *
 * <p>Deciding that is a mesh-building question, not a per-frame one: once a section is built the choice is
 * baked into its vertices and costs nothing to draw. But the choice has to be right, and it has to be made
 * from the same information every time, or two identical walls look different.
 *
 * <h2>Only the four beside it</h2>
 *
 * <p>The diagonals do not take part in ordinary connected textures. Including them would produce the
 * "inner corner" variants that some packs define separately, and a pack that does not define them would
 * show the wrong region at every inside corner. The four-neighbour rule is what standard connected textures
 * mean, and a pack that wants more asks for it explicitly.
 *
 * <h2>A different block does not connect</h2>
 *
 * <p>Glass beside stone is the edge of a window, and drawing it as though the surface continued would show
 * glass running into the wall. The neighbour has to be the same block, and for blocks whose appearance
 * depends on state -- a bookshelf that is open or closed -- the same state as well.
 *
 * <h2>The same face has to face the same way</h2>
 *
 * <p>A pane on the north side of a block and one on the east side are not continuing the same surface; they
 * meet at a corner. Connecting them would smear a texture across an edge that is really a change of plane,
 * so the neighbour's face has to be the one facing back at this block.
 *
 * <h2>Why the choice is cached</h2>
 *
 * <p>It is computed while building a section mesh and stored in the vertex data, so drawing it costs
 * nothing. Recomputing it per frame would be pure waste, and computing it differently at two moments would
 * make the same wall flicker between two textures.
 *
 * <p>Not thread safe. Mesh building runs on worker threads, so a caller using this from several needs one
 * instance per worker.
 */
public final class ConnectedTextureSelector {

    /** Directions that take part, in a fixed order so the index is stable. */
    public static final int NEIGHBOURS = 4;

    /** Texture regions selectable by four neighbours. */
    public static final int VARIANTS = 16;

    private long selections;
    private long connected;

    /**
     * The texture region index for a face.
     *
     * @param connects four flags, one per direction in a fixed order, saying whether the neighbour in
     *     that direction continues the surface
     * @return an index in 0..15
     */
    public int select(boolean[] connects) {
        if (connects.length != NEIGHBOURS) {
            throw new IllegalArgumentException(
                    "connected textures use the " + NEIGHBOURS + " neighbours, not "
                            + connects.length);
        }
        this.selections++;

        int index = 0;
        for (int i = 0; i < NEIGHBOURS; i++) {
            if (connects[i]) {
                index |= 1 << i;
                this.connected++;
            }
        }
        return index;
    }

    /**
     * Whether a neighbour continues the surface.
     *
     * @param sameBlock whether it is the same block, and the same state if the block's appearance depends
     *     on one
     * @param facingBack whether its own face points back at this block
     */
    public static boolean connects(boolean sameBlock, boolean facingBack) {
        // A pane on the north side and one on the east side meet at a corner rather than continuing one
        // surface. Connecting them would smear a texture across a real change of plane.
        return sameBlock && facingBack;
    }

    /** How many faces had a region chosen. */
    public long selections() {
        return this.selections;
    }

    /** How many neighbour links were connected, across all the faces chosen. */
    public long connected() {
        return this.connected;
    }

    /**
     * The share of neighbour links that connected.
     *
     * <p>Zero before any face has been chosen. A selector that has done nothing has technically connected
     * nothing, and reporting that as a share would describe an unused one as though it had a result.
     */
    public double connectedPercent() {
        long links = this.selections * NEIGHBOURS;
        return links == 0 ? 0.0 : 100.0 * this.connected / links;
    }
}
