package net.amdfaster.mesh.voxel;

import net.amdfaster.light.FaceRef;
import net.amdfaster.light.LightCache;
import net.amdfaster.light.LightSampler;
import net.amdfaster.light.SmoothLight;
import net.amdfaster.light.VertexLight;
import net.amdfaster.mesh.Orientation;
import net.amdfaster.mesh.Quad;
import net.amdfaster.mesh.SectionMesh;
import net.amdfaster.mesh.SectionMeshBuilder;

/**
 * Greedy meshing plus meshlet packing: a {@link VoxelView} in, a {@link SectionMesh} out.
 *
 * <p>Each merged rectangle becomes one {@link Quad} whose texture coordinates span the whole
 * rectangle, which is why the view exposes a uv origin and a per-block scale rather than a fixed
 * uv rect: a 4x4 merged face tiles its sprite four times instead of stretching it.
 *
 * <p>There are two entry points. {@link #mesh} lights every quad flat from the view's merge key,
 * which is cheap and correct but plain. {@link #meshSmooth} computes ambient occlusion and
 * interpolated light per corner from a {@link LightSampler}, which is what the geometry actually
 * wants and what the four corner lights in the vertex stream are for.
 */
public final class SectionMesher {

    /**
     * Maps a quad's corner number onto {@link SmoothLight#face}'s corner order.
     *
     * <p>{@code SmoothLight.face} numbers corners {@code (minU,minV)}, {@code (maxU,minV)},
     * {@code (maxU,maxV)}, {@code (minU,maxV)}, while a quad numbers them by winding. The two
     * orders agree on the first two and swap the last two, which is precisely the sort of
     * off-by-one that renders as a lighting seam down one diagonal of every face and is invisible
     * in a screenshot at a glance.
     *
     * <p>Indexed by {@code cornerA * 2 + cornerB}. {@link SectionMesherTest} asserts the invariant
     * directly -- that the index chosen is the one whose {@code (u,v)} end matches the corner's --
     * rather than asserting the table's contents, so the table can be rewritten without the test
     * becoming a copy of it.
     */
    private static final int[] SMOOTH_CORNER_ORDER = {0, 3, 1, 2};

    private SectionMesher() {
    }

    /**
     * Index into a {@link SmoothLight#face} result for quad corner {@code i} of {@code orientation}.
     */
    public static int smoothCornerIndex(Orientation orientation, int i) {
        return SMOOTH_CORNER_ORDER[orientation.cornerA(i) * 2 + orientation.cornerB(i)];
    }

    /** Meshes a section with flat lighting taken from the view's merge key. */
    public static SectionMesh mesh(VoxelView view, int originX, int originY, int originZ) {
        SectionMeshBuilder builder = new SectionMeshBuilder(originX, originY, originZ);
        GreedyMesher.mesh(view, face -> builder.add(toQuad(face, view),
                view.isSingleSided(face.key())));
        return builder.build();
    }

    /**
     * Meshes a section with per-corner smooth lighting and ambient occlusion.
     *
     * <p>The {@link LightCache} is filled once for the whole section and reused for every face.
     * That is the point of the cache: smooth lighting wants four samples per corner, and answering
     * them straight from the level would make lighting the dominant cost of meshing instead of a
     * rounding error on it.
     *
     * @param sampler reads light and opacity in world coordinates
     */
    public static SectionMesh meshSmooth(VoxelView view, LightSampler sampler,
            int originX, int originY, int originZ) {
        LightCache cache = new LightCache();
        cache.fill(sampler, originX, originY, originZ);
        return meshSmooth(view, cache);
    }

    /** The same, over a cache the caller already filled. Exposed so tests can drive it directly. */
    public static SectionMesh meshSmooth(VoxelView view, LightCache cache) {
        VertexLight[] corners = new VertexLight[SmoothLight.CORNERS];
        SectionMeshBuilder builder =
                new SectionMeshBuilder(cache.originX(), cache.originY(), cache.originZ());
        GreedyMesher.mesh(view, face -> builder.add(toQuadSmooth(face, view, cache, corners),
                view.isSingleSided(face.key())));
        return builder.build();
    }

    public static Quad toQuad(MergedFace face, VoxelView view) {
        int key = face.key();
        // Flat: the view gives one lightmap value per face. Packing it at full ambient occlusion
        // is what "no shading computed" means, and it is a real value rather than a placeholder, so
        // a mesh built this way renders correctly if plainly.
        int light = VertexLight.packLight(view.light(key), VertexLight.AO_MAX);
        return quad(face, view, light, light, light, light);
    }

    /**
     * A quad lit per corner, computed from {@code cache}.
     *
     * @param scratch a four-element array the caller owns, so meshing a section allocates nothing
     */
    public static Quad toQuadSmooth(MergedFace face, VoxelView view, LightCache cache,
            VertexLight[] scratch) {
        FaceRef ref = new FaceRef(face.orientation().axis(), face.orientation().isPositive(),
                face.plane(), face.u0(), face.u1(), face.v0(), face.v1());
        SmoothLight.face(cache, ref, scratch);
        Orientation o = face.orientation();
        return quad(face, view,
                scratch[smoothCornerIndex(o, 0)].packed(),
                scratch[smoothCornerIndex(o, 1)].packed(),
                scratch[smoothCornerIndex(o, 2)].packed(),
                scratch[smoothCornerIndex(o, 3)].packed());
    }

    /** Geometry is identical either way; only the four corner lights differ. */
    private static Quad quad(MergedFace face, VoxelView view, int lightA, int lightB, int lightC,
            int lightD) {
        int key = face.key();
        float u0 = view.u0(key);
        float v0 = view.v0(key);
        float u1 = u0 + face.uSize() * view.uScale(key);
        float v1 = v0 + face.vSize() * view.vScale(key);
        int plane = face.plane();

        return switch (face.orientation().axis()) {
            case 0 -> new Quad(face.orientation(),
                    plane, face.u0(), face.v0(),
                    plane, face.u1(), face.v1(),
                    u0, v0, u1, v1, view.color(key), lightA, lightB, lightC, lightD);
            case 1 -> new Quad(face.orientation(),
                    face.u0(), plane, face.v0(),
                    face.u1(), plane, face.v1(),
                    u0, v0, u1, v1, view.color(key), lightA, lightB, lightC, lightD);
            default -> new Quad(face.orientation(),
                    face.u0(), face.v0(), plane,
                    face.u1(), face.v1(), plane,
                    u0, v0, u1, v1, view.color(key), lightA, lightB, lightC, lightD);
        };
    }
}
