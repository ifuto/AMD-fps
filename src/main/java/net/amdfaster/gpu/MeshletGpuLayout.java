package net.amdfaster.gpu;

import net.amdfaster.mesh.Meshlet;
import net.amdfaster.mesh.Orientation;
import net.amdfaster.mesh.SectionMesh;

/**
 * How a section's meshlets are laid out in the buffer the culling shader reads, and where each
 * orientation's run starts.
 *
 * <p>The culling dispatch walks one flat array, so the layout has to be derivable from a meshlet
 * index alone. Keeping the meshlets of one orientation together is what makes back-face rejection
 * free: the bucket is a range, and a bucket the camera cannot see is skipped without looking at a
 * single record.
 *
 * <p>Indices in the indirect draw buffer follow the same order, so a meshlet at slot {@code i}
 * draws from {@code i * 372} indices and {@code i * 248} vertices. That is the assumption the
 * shader makes when it writes its own draw command.
 */
public record MeshletGpuLayout(int meshletCount, int[] runStart, int[] runCount) {

    /** Bytes per record in the cull data buffer. */
    public static final int RECORD_BYTES = CullBindings.MESHLET_RECORD_BYTES;

    /** Indices per meshlet: 62 quads x 6. */
    public static final int INDICES_PER_MESHLET = Meshlet.MAX_QUADS * Meshlet.INDEX_BYTES_PER_QUAD;

    /** Vertices per meshlet: 62 quads x 4. */
    public static final int VERTICES_PER_MESHLET = Meshlet.MAX_QUADS * 4;

    /** Bytes of light data per meshlet at its worst case: 248 vertices x 4. */
    public static final int LIGHT_BYTES_PER_MESHLET = VERTICES_PER_MESHLET * Meshlet.LIGHT_STRIDE;

    private static final MeshletGpuLayout EMPTY =
            new MeshletGpuLayout(0, new int[Orientation.values().length],
                    new int[Orientation.values().length]);

    public static MeshletGpuLayout forSection(SectionMesh mesh) {
        Orientation[] orientations = Orientation.values();
        int[] starts = new int[orientations.length];
        int[] counts = new int[orientations.length];
        int cursor = 0;
        for (Orientation o : orientations) {
            Meshlet[] meshlets = mesh.meshlets(o);
            starts[o.ordinal()] = cursor;
            counts[o.ordinal()] = meshlets.length;
            cursor += meshlets.length;
        }
        return cursor == 0 ? EMPTY : new MeshletGpuLayout(cursor, starts, counts);
    }

    public boolean isEmpty() {
        return this.meshletCount == 0;
    }

    /** First slot belonging to an orientation's run. */
    public int runStart(Orientation orientation) {
        return this.runStart[orientation.ordinal()];
    }

    /** How many meshlets the run holds; a bucket with none contributes nothing. */
    public int runCount(Orientation orientation) {
        return this.runCount[orientation.ordinal()];
    }

    /** Bytes of cull data the whole section needs. */
    public int cullDataBytes() {
        return this.meshletCount * RECORD_BYTES;
    }

    /** Bytes of index buffer the whole section's meshlets occupy at their worst case. */
    public int indexBytes() {
        return this.meshletCount * INDICES_PER_MESHLET;
    }

    /** Vertex slots the whole section's meshlets occupy at their worst case. */
    public int vertexSlots() {
        return this.meshletCount * VERTICES_PER_MESHLET;
    }

    /** Bytes of light buffer the whole section's meshlets occupy at their worst case. */
    public int lightBytes() {
        return this.meshletCount * LIGHT_BYTES_PER_MESHLET;
    }

    /** Bytes for the per-meshlet orientation buffer the back-face test reads. */
    public int orientationBytes() {
        return this.meshletCount * CullBindings.ORIENTATION_BYTES_PER_MESHLET;
    }

    /** Work groups a culling dispatch needs, at the work group size the shaders use. */
    public int cullWorkGroups() {
        return CullBindings.workGroups(this.meshletCount);
    }
}
