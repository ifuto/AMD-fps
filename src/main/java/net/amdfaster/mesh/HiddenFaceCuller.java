package net.amdfaster.mesh;

/**
 * Leaves a face out of the mesh when the block beside it cannot be seen through.
 *
 * <p>Every solid block in the world has six faces, and almost all of them are pressed against another
 * solid block. A wall of stone has one visible face per block and five that are buried, and a chunk of
 * solid rock has almost none. Building all of them means the mesh holds six times the geometry it needs,
 * the vertex fetch reads six times the data, and the rasteriser discards most of what it was given.
 *
 * <p>Deciding this at mesh build time removes the faces before anything is uploaded, so the saving is in
 * memory, bandwidth and vertex work rather than only in pixels.
 *
 * <h2>Only an opaque neighbour hides a face</h2>
 *
 * <p>Glass beside stone does not hide the stone -- the player sees the stone through the glass, and
 * removing that face would leave a hole in the wall. Water does not hide it either, nor does a slab, nor a
 * stair. The rule is whether the neighbour blocks the view completely, and only a full opaque cube does.
 *
 * <h2>A different block still hides it if it is opaque</h2>
 *
 * <p>Stone beside dirt is still a buried face. The faces are not matched by type -- two different blocks
 * pressed together still have no gap between them for the player to see into. Matching by type would keep
 * every face at a material boundary, which is most of the faces in a built structure.
 *
 * <h2>Leaves and cutout blocks are their own question</h2>
 *
 * <p>A leaf block is not opaque in the sense that matters here -- its texture has holes, so the face behind
 * it can be seen through them. Whether those faces are culled depends on the graphics setting and on the
 * texture actually having holes, which is what {@link LeafFaceCuller} decides. This class only answers the
 * simple case, and defers the complicated one rather than guessing at it.
 *
 * <h2>Why this is not the same as frustum culling</h2>
 *
 * <p>Frustum culling decides whether a section is on screen, which changes every frame as the camera moves.
 * A buried face is buried whether or not anything is looking at it, so the decision is made once when the
 * mesh is built and never revisited. Doing it per frame would cost more than the faces it saved.
 *
 * <p>Not thread safe. Mesh building runs on worker threads, so a caller using this from several needs one
 * instance per worker.
 */
public final class HiddenFaceCuller {

    /** Faces on a block. */
    public static final int FACES = 6;

    /** Milliseconds building one face into the mesh costs. */
    public static final double MS_PER_FACE = 0.00005;

    /** Bytes one face occupies in the mesh. */
    public static final int BYTES_PER_FACE = 36;

    private long faces;
    private long culled;
    private long built;
    private long deferred;

    /**
     * Whether a face should be built into the mesh.
     *
     * @param neighbourOpaque whether the block beside this face blocks the view completely
     * @param neighbourIsLeaf whether that block is leaves, which is a separate question
     * @return true if the face should be built
     */
    public boolean shouldBuildFace(boolean neighbourOpaque, boolean neighbourIsLeaf) {
        this.faces++;

        if (neighbourIsLeaf) {
            // A leaf block's texture has holes, so the face behind it can be seen through them. Whether
            // those faces are culled depends on the graphics setting and on the texture actually having
            // holes, which is a different decision and a different class.
            this.deferred++;
            return true;
        }
        if (neighbourOpaque) {
            // A different block still hides it. Two blocks pressed together have no gap between them for
            // the player to see into, and matching by type would keep every face at a material boundary,
            // which is most of the faces in a built structure.
            this.culled++;
            return false;
        }
        this.built++;
        return true;
    }

    public long faces() {
        return this.faces;
    }

    public long culled() {
        return this.culled;
    }

    public long built() {
        return this.built;
    }

    /** How many faces were left to the leaf rules to decide. */
    public long deferred() {
        return this.deferred;
    }

    public double culledPercent() {
        return this.faces == 0 ? 0.0 : 100.0 * this.culled / this.faces;
    }

    /** Bytes of mesh data not uploaded. */
    public long bytesSaved() {
        return this.culled * (long) BYTES_PER_FACE;
    }

    /** Milliseconds of mesh building avoided. */
    public double msSaved() {
        return this.culled * MS_PER_FACE;
    }

    /** Milliseconds building every face would cost. */
    public double msWithoutCulling() {
        return this.faces * MS_PER_FACE;
    }

    /**
     * The share of the mesh building avoided.
     *
     * <p>Zero before any face has been offered. A culler that has been offered nothing has technically
     * avoided everything, but reporting that as a saving would describe an unused one as though it had
     * done something.
     */
    public double savedPercent() {
        double without = msWithoutCulling();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * msSaved() / without;
    }
}
