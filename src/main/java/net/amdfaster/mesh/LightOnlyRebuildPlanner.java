package net.amdfaster.mesh;

/**
 * Decides what a dirty section actually needs rebuilt, and what that costs.
 *
 * <p>Light lives in its own vertex stream. A meshlet is 5,332 bytes of mesh and light together, of which
 * 992 -- 18.60 per cent -- is light. So a section whose light changed but whose blocks did not needs only
 * that stream rewritten, and skips greedy meshing and ambient occlusion entirely. That is 81.40 per cent
 * of the upload and, more importantly, all of the CPU work that produces the mesh in the first place.
 *
 * <h2>Why the layout is what makes this possible</h2>
 *
 * <p>A renderer that interleaves position, attributes and light into one word per vertex has to rewrite
 * everything to change any of it. The separated stream is not an optimisation on top of the layout, it is
 * the layout -- which is why this cannot be retrofitted to an interleaved renderer and why the choice was
 * made when the mesh format was.
 *
 * <h2>Geometry subsumes light</h2>
 *
 * <p>Rebuilding the geometry recomputes the light too, because ambient occlusion is baked from the light
 * and the mesh is rebuilt from both. So a section marked dirty for both reasons is one geometry rebuild,
 * not a geometry rebuild and a light rewrite. Doing both would upload the light stream twice and the
 * second upload would carry the same bytes. {@link net.amdfaster.dirty.RebuildScheduler} already keeps the
 * two dirty sets separate and drops the light entry when the geometry one covers it; this class is the
 * consumer of that decision and refuses to plan the redundant work if it is offered.
 *
 * <h2>Drained before built, not after</h2>
 *
 * <p>A section comes off the pending set before its mesh is built, not after. A block change that arrives
 * while the build is running re-marks the section, and if it is still on the pending set at that moment
 * the mark is discarded along with the entry -- leaving a section whose GPU mesh disagrees with its
 * blocks, with nothing to notice until some unrelated change dirties it again. That is a hole that does
 * not heal on its own.
 *
 * <p>Not thread safe.
 */
public final class LightOnlyRebuildPlanner {

    /** What a drained section needs. */
    public enum Work {
        /** Blocks changed. The mesh is rebuilt, which recomputes the light as a side effect. */
        GEOMETRY_AND_LIGHT,
        /** Only light changed. The light stream is rewritten and the mesh is left alone. */
        LIGHT_ONLY,
    }

    /** Bytes of light per meshlet, matching {@code MeshletGpuLayout.LIGHT_BYTES_PER_MESHLET}. */
    public static final int LIGHT_BYTES_PER_MESHLET = 992;

    /** Bytes of mesh and light per meshlet, matching {@code SectionMeshDeduplicator.MESH_BYTES}. */
    public static final int TOTAL_BYTES_PER_MESHLET = 5332;

    /** What a light-only rewrite does not upload, per meshlet. */
    public static final int BYTES_AVOIDED_PER_MESHLET = TOTAL_BYTES_PER_MESHLET - LIGHT_BYTES_PER_MESHLET;

    private long planned;
    private long geometryRebuilds;
    private long lightOnly;
    private long bytesPlanned;
    private long bytesAvoided;
    private long redundantRefused;

    /**
     * Decides what a section needs.
     *
     * @param geometryDirty true if the section's blocks changed
     * @param lightDirty true if its light changed
     * @return the work to do. A section dirty for both reasons is one geometry rebuild, because the
     *     rebuild recomputes the light and a separate light rewrite would upload the same bytes twice.
     */
    public Work plan(boolean geometryDirty, boolean lightDirty) {
        this.planned++;
        if (geometryDirty) {
            if (lightDirty) {
                // Not an error to be offered both, but planning both would upload the light stream twice.
                // Counted so a caller that keeps offering the pair can be seen doing it.
                this.redundantRefused++;
            }
            this.geometryRebuilds++;
            return Work.GEOMETRY_AND_LIGHT;
        }
        if (lightDirty) {
            this.lightOnly++;
            return Work.LIGHT_ONLY;
        }
        throw new IllegalArgumentException(
                "a section marked dirty for neither reason should not have been drained");
    }

    /**
     * Bytes this work uploads.
     *
     * @param work what {@link #plan} returned
     * @param meshletCount how many meshlets the section has
     * @return the upload size
     */
    public int uploadBytes(Work work, int meshletCount) {
        if (work == null) {
            throw new IllegalArgumentException("work is required");
        }
        if (meshletCount < 0) {
            throw new IllegalArgumentException("meshlet count cannot be negative: " + meshletCount);
        }
        int bytes = meshletCount * (work == Work.LIGHT_ONLY
                ? LIGHT_BYTES_PER_MESHLET
                : TOTAL_BYTES_PER_MESHLET);
        this.bytesPlanned += bytes;
        if (work == Work.LIGHT_ONLY) {
            this.bytesAvoided += (long) meshletCount * BYTES_AVOIDED_PER_MESHLET;
        }
        return bytes;
    }

    /**
     * Bytes a light-only rewrite saves against rebuilding the whole section.
     *
     * @param meshletCount how many meshlets the section has
     */
    public static int bytesSaved(int meshletCount) {
        if (meshletCount < 0) {
            throw new IllegalArgumentException("meshlet count cannot be negative: " + meshletCount);
        }
        return meshletCount * BYTES_AVOIDED_PER_MESHLET;
    }

    /**
     * The share of a section's bytes a light-only rewrite does not touch.
     *
     * <p>The same at any meshlet count, because both terms scale with it. Reported as a percentage of the
     * section rather than of the frame, since what it describes is the per-section saving.
     */
    public static double savedPercent() {
        return 100.0 * BYTES_AVOIDED_PER_MESHLET / TOTAL_BYTES_PER_MESHLET;
    }

    /** How many sections have been planned. */
    public long planned() {
        return this.planned;
    }

    public long geometryRebuilds() {
        return this.geometryRebuilds;
    }

    public long lightOnly() {
        return this.lightOnly;
    }

    /** Bytes all the planned work will upload. */
    public long bytesPlanned() {
        return this.bytesPlanned;
    }

    /** Bytes the light-only plans did not upload. */
    public long bytesAvoided() {
        return this.bytesAvoided;
    }

    /**
     * How many times a section was offered as dirty for both reasons.
     *
     * <p>Non-zero means the caller is not dropping the light entry when geometry covers it, which wastes
     * a decision here and an upload if the caller acts on its own bookkeeping rather than this one.
     */
    public long redundantRefused() {
        return this.redundantRefused;
    }
}
