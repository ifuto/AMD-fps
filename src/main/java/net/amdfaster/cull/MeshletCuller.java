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

    /** The same test against an already-decoded box, for callers that have one to hand. */
    public static boolean isVisible(Meshlet meshlet, int originX, int originY, int originZ,
                                    Frustum frustum) {
        return frustum.intersectsAabb(
                originX + meshlet.minX(), originY + meshlet.minY(), originZ + meshlet.minZ(),
                originX + meshlet.maxX(), originY + meshlet.maxY(), originZ + meshlet.maxZ());
    }
}
