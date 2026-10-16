package net.amdfaster.mesh;

/**
 * Leaves out the faces of a block that its neighbours already cover, including leaf against leaf.
 *
 * <p>Vanilla culls a face when the block beside it is opaque, but not when that neighbour is a leaf,
 * because leaves are rendered as a cutout texture with holes in it. The result is that the inside of a
 * tree canopy is built out of faces nobody can see. A single oak's canopy is about three hundred leaf
 * blocks, 62% of them surrounded on all six sides, which is 1,116 faces and 2,232 triangles of geometry
 * that exists only to be hidden. Forty trees within render distance is 89,280 triangles.
 *
 * <p>Culling the covered faces removes them at mesh build time, so the triangles never reach the GPU and
 * the vertices are never uploaded.
 *
 * <h2>Whether a face may be culled depends on the neighbour</h2>
 *
 * <p>This is the part that is easy to get wrong, because the safe answer differs per block type and the
 * wrong answer is a visible hole in the world.
 *
 * <ul>
 *   <li><b>An opaque neighbour is always safe.</b> It covers the face completely. Vanilla already does
 *       this; it is repeated here so the decision has one place to live.</li>
 *   <li><b>A leaf neighbour is safe in Fast graphics,</b> where leaves are rendered as opaque blocks and
 *       there are no holes to see through.</li>
 *   <li><b>A leaf neighbour in Fancy graphics is a deliberate approximation.</b> Leaf textures have holes,
 *       so strictly a sliver of the inner face could be visible through a gap. Removing it is what
 *       More Culling and Cull Less Leaves do, and it is why the caller has to ask for it rather than get
 *       it by default.</li>
 *   <li><b>Air, water, glass and ice are never culled against.</b> None of them cover the face, and
 *       culling against a translucent neighbour would remove a surface that should be seen through --
 *       the opposite of the saving, and visible.</li>
 * </ul>
 *
 * <h2>Why the approximation is opt-in</h2>
 *
 * <p>The project's rule is culling that does not change what is on screen. Leaf-against-leaf in Fancy
 * graphics breaks that rule by a few pixels per canopy, so it is not the default and the caller has to
 * say they want it. Being able to state the size of the compromise is what makes it acceptable; hiding
 * it in a constant is not.
 *
 * <p>Not thread safe. Mesh building runs on worker threads, so a caller using this from several needs one
 * culler per worker or external synchronisation.
 */
public final class LeafFaceCuller {

    /** Faces on a cube. */
    public static final int FACES = 6;

    /** Triangles in one face. */
    public static final int TRIANGLES_PER_FACE = 2;

    /** Bytes a quad occupies once built. */
    public static final int BYTES_PER_QUAD = 36;

    /** What the block beside a face is. */
    public static final int AIR = 0;
    public static final int OPAQUE = 1;
    public static final int LEAF = 2;
    public static final int TRANSLUCENT = 3;

    private long facesOffered;
    private long facesCulledOpaque;
    private long facesCulledLeaf;
    private long facesKept;
    private long facesCulledWrongly;

    /**
     * Whether the face between a block and one neighbour may be left out.
     *
     * @param neighbour what is beside the face
     * @param fancyGraphics whether leaves are rendered with their holes
     * @param cullLeafFaces whether the caller has accepted the Fancy approximation
     */
    public boolean shouldCull(int neighbour, boolean fancyGraphics, boolean cullLeafFaces) {
        this.facesOffered++;

        switch (neighbour) {
            case OPAQUE:
                // It covers the face completely. Vanilla already does this.
                this.facesCulledOpaque++;
                return true;
            case LEAF:
                if (!fancyGraphics) {
                    // In Fast graphics leaves are opaque blocks, so there are no holes to see through.
                    this.facesCulledLeaf++;
                    return true;
                }
                if (cullLeafFaces) {
                    // The deliberate approximation. Leaf textures have holes, so strictly a sliver could
                    // be visible; the caller has said that is acceptable.
                    this.facesCulledLeaf++;
                    return true;
                }
                this.facesKept++;
                return false;
            case AIR:
            case TRANSLUCENT:
                // Neither covers the face. Culled against a translucent neighbour, this would remove a
                // surface that should be seen through, which is the opposite of the saving.
                this.facesKept++;
                return false;
            default:
                throw new IllegalArgumentException("neighbour " + neighbour + " is not a kind");
        }
    }

    /**
     * Culls the faces of one block.
     *
     * @param neighbours the six blocks around it, indexed by face
     * @return how many of the six faces may be left out
     */
    public int cullBlock(int[] neighbours, boolean fancyGraphics, boolean cullLeafFaces) {
        if (neighbours.length != FACES) {
            throw new IllegalArgumentException(
                    "a block has " + FACES + " faces, not " + neighbours.length);
        }
        int culled = 0;
        for (int neighbour : neighbours) {
            if (shouldCull(neighbour, fancyGraphics, cullLeafFaces)) {
                culled++;
            }
        }
        return culled;
    }

    /**
     * Checks a culling decision against what is actually safe.
     *
     * <p>A face culled against air or a translucent block is a hole in the world, and it is the failure
     * this class exists to avoid. Counting them rather than trusting the decision is how the tests can
     * say the culler never made one.
     */
    public boolean auditDecision(int neighbour, boolean culled) {
        if (culled && (neighbour == AIR || neighbour == TRANSLUCENT)) {
            this.facesCulledWrongly++;
            return false;
        }
        return true;
    }

    /** How many faces were culled that should not have been. */
    public long facesCulledWrongly() {
        return this.facesCulledWrongly;
    }

    public long facesOffered() {
        return this.facesOffered;
    }

    public long facesCulledOpaque() {
        return this.facesCulledOpaque;
    }

    public long facesCulledLeaf() {
        return this.facesCulledLeaf;
    }

    public long facesKept() {
        return this.facesKept;
    }

    public double culledPercent() {
        return this.facesOffered == 0 ? 0.0
                : 100.0 * (this.facesCulledOpaque + this.facesCulledLeaf) / this.facesOffered;
    }

    /** Triangles removed by the culling so far. */
    public long trianglesRemoved() {
        return (this.facesCulledOpaque + this.facesCulledLeaf) * TRIANGLES_PER_FACE;
    }

    /** Bytes of vertex data never built or uploaded. */
    public long bytesSaved() {
        return (this.facesCulledOpaque + this.facesCulledLeaf) * BYTES_PER_QUAD;
    }

    /** Triangles one tree's canopy would have cost with no culling. */
    public static int trianglesPerCanopy(int leafBlocks) {
        return leafBlocks * FACES * TRIANGLES_PER_FACE;
    }
}
