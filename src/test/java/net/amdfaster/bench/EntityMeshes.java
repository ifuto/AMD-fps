package net.amdfaster.bench;

/**
 * Bakes an entity model into a local-space mesh, once, the way a real renderer uploads a model once.
 *
 * <p>Two meshes come out per model: the full one and the coarse one {@code EntityLodSelector} switches
 * distant entities to. Building the coarse mesh ahead of time rather than skipping cubes at draw time is
 * what makes the level of detail a saving rather than a branch -- the triangles that are not there cost
 * nothing at all, not even a test.
 */
final class EntityMeshes {

    private EntityMeshes() {
    }

    static Mesh build(Assets.EntityGeometry geometry, int cubeLimit) {
        return build(geometry, 0, cubeLimit);
    }

    /**
     * Bakes a range of a model's cubes, which is how the chest splits into a box that can be baked into
     * the section mesh and a lid that cannot.
     */
    static Mesh build(Assets.EntityGeometry geometry, int first, int cubeLimit) {
        MeshBuilder builder = new MeshBuilder();
        // Centre the model on x and z so it stands on its own origin, and drop it to y = 0 at the feet.
        float minX = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        int start = Math.max(0, first);
        int limit = Math.min(cubeLimit, geometry.cubes.length);
        for (int i = start; i < limit; i++) {
            Assets.Cube cube = geometry.cubes[i];
            if (cube.originX < minX) {
                minX = cube.originX;
            }
            if (cube.originX + cube.sizeX > maxX) {
                maxX = cube.originX + cube.sizeX;
            }
            if (cube.originZ < minZ) {
                minZ = cube.originZ;
            }
            if (cube.originZ + cube.sizeZ > maxZ) {
                maxZ = cube.originZ + cube.sizeZ;
            }
            if (cube.originY < minY) {
                minY = cube.originY;
            }
        }
        if (limit <= start) {
            return builder.build();
        }
        float centreX = (minX + maxX) * 0.5f;
        float centreZ = (minZ + maxZ) * 0.5f;

        for (int i = start; i < limit; i++) {
            Assets.Cube cube = geometry.cubes[i];
            // Sixteenths of a block, which is the unit both model formats use.
            float x0 = (cube.originX - centreX) / 16.0f;
            float x1 = (cube.originX + cube.sizeX - centreX) / 16.0f;
            float y0 = (cube.originY - minY) / 16.0f;
            float y1 = (cube.originY + cube.sizeY - minY) / 16.0f;
            float z0 = (cube.originZ - centreZ) / 16.0f;
            float z1 = (cube.originZ + cube.sizeZ - centreZ) / 16.0f;
            addCube(builder, geometry, cube, x0, y0, z0, x1, y1, z1);
        }
        return builder.build();
    }

    private static void addCube(MeshBuilder builder, Assets.EntityGeometry geometry, Assets.Cube cube,
            float x0, float y0, float z0, float x1, float y1, float z1) {
        float uBase;
        float vBase;
        float uSpan;
        float vSpan;
        if (geometry.boxUvs) {
            // Block model UVs are sixteenths of the whole texture.
            uBase = cube.uvU / 16.0f;
            vBase = cube.uvV / 16.0f;
            uSpan = cube.sizeX / 16.0f;
            vSpan = cube.sizeY / 16.0f;
        } else {
            // Entity model UVs are texels of an unwrap. The exact unwrap layout differs per face in
            // vanilla; what matters here is that every face reads a different part of the texture, so
            // the fetches are as scattered as the real ones.
            uBase = cube.uvU / geometry.textureWidth;
            vBase = cube.uvV / geometry.textureHeight;
            uSpan = (cube.sizeX + cube.sizeZ) / geometry.textureWidth;
            vSpan = (cube.sizeY + cube.sizeZ) / geometry.textureHeight;
        }
        float u1 = uBase + uSpan;
        float v1 = vBase + vSpan;

        builder.quad(x0, y1, z0, uBase, v1, x1, y1, z0, u1, v1,
                x0, y1, z1, uBase, vBase, x1, y1, z1, u1, vBase, cube.texture, Assets.FACE_SHADE[Assets.UP]);
        builder.quad(x0, y0, z1, uBase, v1, x1, y0, z1, u1, v1,
                x0, y0, z0, uBase, vBase, x1, y0, z0, u1, vBase, cube.texture, Assets.FACE_SHADE[Assets.DOWN]);
        builder.quad(x1, y0, z0, uBase, v1, x0, y0, z0, u1, v1,
                x1, y1, z0, uBase, vBase, x0, y1, z0, u1, vBase, cube.texture, Assets.FACE_SHADE[Assets.NORTH]);
        builder.quad(x0, y0, z1, uBase, v1, x1, y0, z1, u1, v1,
                x0, y1, z1, uBase, vBase, x1, y1, z1, u1, vBase, cube.texture, Assets.FACE_SHADE[Assets.SOUTH]);
        builder.quad(x0, y0, z0, uBase, v1, x0, y0, z1, u1, v1,
                x0, y1, z0, uBase, vBase, x0, y1, z1, u1, vBase, cube.texture, Assets.FACE_SHADE[Assets.WEST]);
        builder.quad(x1, y0, z1, uBase, v1, x1, y0, z0, u1, v1,
                x1, y1, z1, uBase, vBase, x1, y1, z0, u1, vBase, cube.texture, Assets.FACE_SHADE[Assets.EAST]);
    }
}
