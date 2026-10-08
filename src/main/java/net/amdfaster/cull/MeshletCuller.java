package net.amdfaster.cull;

import net.amdfaster.mesh.Meshlet;

/**
 * The per-meshlet frustum test, run on the CPU for the fallback path and mirrored in the compute
 * shader for the GPU-driven one.
 *
 * <p>Bounds come from {@link Meshlet#packedBounds()}: six 5-bit fields, the section-local AABB of
 * everything the meshlet draws. Five bits cover 0..31, which is enough for a 16-block section and
 * leaves room for the merged quads greedy meshing produces to reach the far edge at coordinate 16.
 *
 * <p>Note what this does <em>not</em> do: back-face rejection. Every quad in a meshlet shares its
 * bucket's orientation, so a whole meshlet faces one way, and that is decided once per section by
 * {@code SectionMesh.visibleMask} rather than once per meshlet here. Testing it per meshlet would
 * repeat the same answer a few hundred times a frame.
 */
public final class MeshletCuller {

    private MeshletCuller() {
    }

    /**
     * @param packedBounds {@link Meshlet#packedBounds()}, section-local
     * @param originX      world coordinate of the section's low corner; the frustum must be built
     *                     in the same space, which in this renderer is camera-relative
     */
    public static boolean isVisible(int packedBounds, int originX, int originY, int originZ,
                                    Frustum frustum) {
        float minX = originX + Meshlet.unpackMinX(packedBounds);
        float minY = originY + Meshlet.unpackMinY(packedBounds);
        float minZ = originZ + Meshlet.unpackMinZ(packedBounds);
        float maxX = originX + Meshlet.unpackMaxX(packedBounds);
        float maxY = originY + Meshlet.unpackMaxY(packedBounds);
        float maxZ = originZ + Meshlet.unpackMaxZ(packedBounds);
        return frustum.intersectsAabb(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Whether every face in the meshlet points away from the camera, so the whole meshlet can be
     * dropped before its vertices are ever shaded.
     *
     * <p>A meshlet is built from one orientation bucket, so all of its faces share a normal, and that
     * makes the test a single comparison instead of a per-face one. A face with normal +X is only ever
     * seen from the +X side, so the meshlet is invisible once the camera is at or behind its nearest
     * face plane -- which for a bucket of coplanar-axis faces is the box's own low bound on that axis.
     * Using the near bound is what keeps it conservative: the meshlet is dropped only when even its
     * closest face is turned away.
     *
     * <p>This is on top of the rasteriser's own back-face cull, not instead of it. What it saves is
     * the vertex shading and the draw, which is where the cost actually is; the fragments were never
     * the problem.
     *
     * <p>Returns false -- never cull -- unless the meshlet is flagged single-sided. See
     * {@link Meshlet#singleSided()}.
     *
     * <p>Coordinates are camera-relative, the same space {@link #isVisible} works in, so the camera is
     * at the origin and the comparisons are against zero.
     */
    public static boolean isBackFacing(Meshlet meshlet, int originX, int originY, int originZ) {
        if (!meshlet.singleSided()) {
            return false;
        }
        return isBackFacing(meshlet.orientation().axis(), meshlet.orientation().isPositive(),
                originX + meshlet.minX(), originY + meshlet.minY(), originZ + meshlet.minZ(),
                originX + meshlet.maxX(), originY + meshlet.maxY(), originZ + meshlet.maxZ());
    }

    /**
     * The same test against an already-decoded box, and the exact form the compute shader runs.
     *
     * @param axis     the bucket's axis: 0 = X, 1 = Y, 2 = Z
     * @param positive whether the bucket's faces point along the positive direction of that axis
     */
    public static boolean isBackFacing(int axis, boolean positive,
                                       float minX, float minY, float minZ,
                                       float maxX, float maxY, float maxZ) {
        return switch (axis) {
            // Camera at the origin. A +X face needs the camera at x greater than the face plane to be
            // seen, so once the nearest plane in the meshlet is at or beyond the camera, none of them
            // can be.
            case 0 -> positive ? minX >= 0f : maxX <= 0f;
            case 1 -> positive ? minY >= 0f : maxY <= 0f;
            default -> positive ? minZ >= 0f : maxZ <= 0f;
        };
    }

    /** The same test against an already-decoded box, for callers that have one to hand. */
    public static boolean isVisible(Meshlet meshlet, int originX, int originY, int originZ,
                                    Frustum frustum) {
        return frustum.intersectsAabb(
                originX + meshlet.minX(), originY + meshlet.minY(), originZ + meshlet.minZ(),
                originX + meshlet.maxX(), originY + meshlet.maxY(), originZ + meshlet.maxZ());
    }
}
