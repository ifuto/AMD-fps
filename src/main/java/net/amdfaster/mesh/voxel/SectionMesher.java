package net.amdfaster.mesh.voxel;

import net.amdfaster.mesh.Quad;
import net.amdfaster.mesh.SectionMesh;
import net.amdfaster.mesh.SectionMeshBuilder;
import net.amdfaster.light.VertexLight;

/**
 * Greedy meshing plus meshlet packing: a {@link VoxelView} in, a {@link SectionMesh} out.
 *
 * <p>Each merged rectangle becomes one {@link Quad} whose texture coordinates span the whole
 * rectangle, which is why the view exposes a uv origin and a per-block scale rather than a fixed
 * uv rect: a 4x4 merged face tiles its sprite four times instead of stretching it.
 */
public final class SectionMesher {

    private SectionMesher() {
    }

    public static SectionMesh mesh(VoxelView view, int originX, int originY, int originZ) {
        SectionMeshBuilder builder = new SectionMeshBuilder(originX, originY, originZ);
        GreedyMesher.mesh(view, face -> builder.add(toQuad(face, view)));
        return builder.build();
    }

    public static Quad toQuad(MergedFace face, VoxelView view) {
        int key = face.key();
        float u0 = view.u0(key);
        float v0 = view.v0(key);
        float u1 = u0 + face.uSize() * view.uScale(key);
        float v1 = v0 + face.vSize() * view.vScale(key);
        int plane = face.plane();
        // Flat for now: the view gives one lightmap value per face. Packing it at full ambient
        // occlusion is what "no shading computed" means, and it is a real value rather than a
        // placeholder, so a mesh built this way renders correctly if plainly.
        int light = VertexLight.packLight(view.light(key), VertexLight.AO_MAX);

        return switch (face.orientation().axis()) {
            case 0 -> Quad.uniform(face.orientation(),
                    plane, face.u0(), face.v0(),
                    plane, face.u1(), face.v1(),
                    u0, v0, u1, v1, view.color(key), light);
            case 1 -> Quad.uniform(face.orientation(),
                    face.u0(), plane, face.v0(),
                    face.u1(), plane, face.v1(),
                    u0, v0, u1, v1, view.color(key), light);
            default -> Quad.uniform(face.orientation(),
                    face.u0(), face.v0(), plane,
                    face.u1(), face.v1(), plane,
                    u0, v0, u1, v1, view.color(key), light);
        };
    }
}
