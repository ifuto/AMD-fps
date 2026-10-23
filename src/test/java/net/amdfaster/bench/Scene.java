package net.amdfaster.bench;

import java.util.Random;

/**
 * One of the three scenes the benchmark renders, and the camera path through it.
 *
 * <p>The scenes are the ones the project has always quoted numbers for -- open plains, a village over a
 * cave, and a hundred TNT going off -- because a single number is not a result. A technique that wins in
 * a cave and loses on a plain is a technique whose real value depends on where the player is standing,
 * and reporting one average hides that.
 *
 * <p>Generation is from {@link Random} with a fixed seed rather than from the project's
 * {@code FastRandom}, deliberately: the world has to be identical for both pipelines and identical on
 * every CI machine, and a generator chosen for speed is the wrong tool for a fixture.
 */
final class Scene {

    /** The three scenes. */
    static final int PLAINS = 0;
    static final int VILLAGE_CAVE = 1;
    static final int TNT = 2;

    static final String[] NAMES = {"A plains", "B village+cave", "C tnt x100"};

    private static final int SECTIONS_ACROSS = 11;
    private static final int SECTIONS_UP = 4;
    private static final int SEA_LEVEL = 34;
    private static final int CLOUD_HEIGHT = 92;

    final int kind;
    final String name;
    final VoxelWorld world;
    final SectionMeshes[] sections;
    final int sectionsX;
    final int sectionsY;
    final int sectionsZ;

    // Entities. Kept as parallel primitive arrays because that is the layout the batching code wants,
    // and because a benchmark that boxed every entity into an object would spend its time in the GC.
    final float[] entityX;
    final float[] entityY;
    final float[] entityZ;
    final float[] entityYaw;
    final int[] entityModel;
    final float[] entityHalfWidth;
    final float[] entityHeight;
    final int entityCount;

    /** Chests and other block entities, at block coordinates. */
    final int[] blockEntityX;
    final int[] blockEntityY;
    final int[] blockEntityZ;
    final int blockEntityCount;

    /** Particles, repositioned every frame. */
    final float[] particleX;
    final float[] particleY;
    final float[] particleZ;
    final float[] particleU;
    final float[] particleV;
    final int particleCount;

    final boolean raining;
    final long seed;

    private final float camX;
    private final float camY;
    private final float camZ;

    private Scene(int kind, VoxelWorld world, SectionMeshes[] sections, Random random,
            int mobs, int chests, int particles, boolean raining, boolean blast) {
        this.kind = kind;
        this.name = NAMES[kind];
        this.world = world;
        this.sections = sections;
        this.sectionsX = world.sectionsX();
        this.sectionsY = world.sectionsY();
        this.sectionsZ = world.sectionsZ();
        this.seed = random.nextLong();
        this.raining = raining;

        int total = mobs + (blast ? 100 : 0);
        this.entityX = new float[total];
        this.entityY = new float[total];
        this.entityZ = new float[total];
        this.entityYaw = new float[total];
        this.entityModel = new int[total];
        this.entityHalfWidth = new float[total];
        this.entityHeight = new float[total];
        this.entityCount = total;

        float centreX = world.sizeX * 0.5f;
        float centreZ = world.sizeZ * 0.5f;
        for (int i = 0; i < mobs; i++) {
            float x = 8 + random.nextFloat() * (world.sizeX - 16);
            float z = 8 + random.nextFloat() * (world.sizeZ - 16);
            this.entityX[i] = x;
            this.entityY[i] = surfaceY(world, (int) x, (int) z) + 0.05f;
            this.entityZ[i] = z;
            this.entityYaw[i] = random.nextFloat() * 360.0f;
            this.entityModel[i] = (i & 1) == 0 ? 0 : 1;
            this.entityHalfWidth[i] = 0.4f;
            this.entityHeight[i] = this.entityModel[i] == 0 ? 1.7f : 1.95f;
        }
        // TNT is an entity in Minecraft, not a block, and it is drawn with the block's model. Placing it
        // in the air above the blast is what a chain reaction actually looks like.
        for (int i = mobs; i < total; i++) {
            float angle = random.nextFloat() * 6.2831853f;
            float radius = random.nextFloat() * 14.0f;
            this.entityX[i] = centreX + (float) Math.cos(angle) * radius;
            this.entityZ[i] = centreZ + (float) Math.sin(angle) * radius;
            this.entityY[i] = surfaceY(world, (int) this.entityX[i], (int) this.entityZ[i])
                    + 1.0f + random.nextFloat() * 6.0f;
            this.entityYaw[i] = random.nextFloat() * 360.0f;
            this.entityModel[i] = 2;
            this.entityHalfWidth[i] = 0.49f;
            this.entityHeight[i] = 0.98f;
        }

        this.blockEntityX = new int[chests];
        this.blockEntityY = new int[chests];
        this.blockEntityZ = new int[chests];
        this.blockEntityCount = chests;
        for (int i = 0; i < chests; i++) {
            int x = 8 + random.nextInt(world.sizeX - 16);
            int z = 8 + random.nextInt(world.sizeZ - 16);
            this.blockEntityX[i] = x;
            this.blockEntityZ[i] = z;
            this.blockEntityY[i] = surfaceY(world, x, z);
        }

        this.particleX = new float[particles];
        this.particleY = new float[particles];
        this.particleZ = new float[particles];
        this.particleU = new float[particles];
        this.particleV = new float[particles];
        this.particleCount = particles;
        for (int i = 0; i < particles; i++) {
            this.particleX[i] = centreX + (random.nextFloat() - 0.5f) * 40.0f;
            this.particleY[i] = surfaceY(world, (int) (centreX), (int) (centreZ))
                    + random.nextFloat() * 12.0f;
            this.particleZ[i] = centreZ + (random.nextFloat() - 0.5f) * 40.0f;
            // Cells of the particle atlas, chosen once so the same particle keeps its appearance.
            int cell = random.nextInt(16);
            this.particleU[i] = (cell % 4) * 0.25f;
            this.particleV[i] = (cell / 4) * 0.25f;
        }

        this.camX = centreX;
        this.camZ = centreZ;
        this.camY = surfaceY(world, (int) centreX, (int) centreZ) + 6.0f;
    }

    private static int surfaceY(VoxelWorld world, int x, int z) {
        for (int y = world.sizeY - 1; y >= 0; y--) {
            int id = world.get(x, y, z);
            if (id != Assets.AIR && id != Assets.WATER) {
                return y + 1;
            }
        }
        return 1;
    }

    static Scene build(int kind, Assets assets) {
        Random random = new Random(0x5EEDL + kind * 7919L);
        VoxelWorld world = new VoxelWorld(
                SECTIONS_ACROSS * VoxelWorld.SECTION,
                SECTIONS_UP * VoxelWorld.SECTION,
                SECTIONS_ACROSS * VoxelWorld.SECTION);
        terrain(world, random, kind);
        if (kind == VILLAGE_CAVE) {
            village(world, random);
            cave(world, random);
        }
        if (kind == TNT) {
            blast(world, random);
        }
        SectionMeshes[] sections = mesh(world, assets);
        switch (kind) {
            case VILLAGE_CAVE:
                return new Scene(kind, world, sections, random, 120, 48, 260, true, false);
            case TNT:
                return new Scene(kind, world, sections, random, 100, 12, 620, false, true);
            default:
                return new Scene(kind, world, sections, random, 40, 6, 90, false, false);
        }
    }

    private static void terrain(VoxelWorld world, Random random, int kind) {
        int width = world.sizeX;
        int depth = world.sizeZ;
        // Two octaves of value noise on a lattice: enough shape for the horizon to break up, which is
        // what makes frustum and occlusion culling have something to do.
        float[] latticeA = lattice(random, width / 16 + 2, depth / 16 + 2);
        float[] latticeB = lattice(random, width / 4 + 2, depth / 4 + 2);
        int la = width / 16 + 2;
        int lb = width / 4 + 2;

        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < width; x++) {
                float a = sample(latticeA, la, x / 16.0f, z / 16.0f);
                float b = sample(latticeB, lb, x / 4.0f, z / 4.0f);
                int height;
                if (kind == VILLAGE_CAVE) {
                    // Flatter: a village sits on level ground, and a cave underneath is where the
                    // occlusion culling has real work to do.
                    height = 38 + (int) (a * 3.0f + b * 1.0f);
                } else {
                    height = 36 + (int) (a * 8.0f + b * 2.0f);
                }
                if (height < 4) {
                    height = 4;
                }
                if (height > world.sizeY - 6) {
                    height = world.sizeY - 6;
                }
                for (int y = 0; y < height; y++) {
                    int id;
                    if (y < height - 4) {
                        id = y < 10 && random.nextInt(24) == 0 ? Assets.LAVA : Assets.STONE;
                    } else if (y < height - 1) {
                        id = Assets.DIRT;
                    } else {
                        id = height <= SEA_LEVEL + 1 ? Assets.SAND : Assets.GRASS;
                    }
                    world.set(x, y, z, id);
                }
                for (int y = height; y < SEA_LEVEL; y++) {
                    world.set(x, y, z, Assets.WATER);
                }
            }
        }
        // Trees: a trunk plus a canopy, which is the only place in this world where cutout geometry and
        // interior faces both appear in quantity.
        int trees = kind == VILLAGE_CAVE ? 26 : 90;
        for (int i = 0; i < trees; i++) {
            int x = 4 + random.nextInt(width - 8);
            int z = 4 + random.nextInt(depth - 8);
            int ground = surfaceY(world, x, z);
            if (world.get(x, ground - 1, z) != Assets.GRASS) {
                continue;
            }
            int trunk = 4 + random.nextInt(2);
            for (int y = 0; y < trunk; y++) {
                world.set(x, ground + y, z, Assets.LOG);
            }
            int top = ground + trunk;
            for (int dy = -2; dy <= 1; dy++) {
                int radius = dy >= 0 ? 1 : 2;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx == 0 && dz == 0 && dy < 1) {
                            continue;
                        }
                        if (Math.abs(dx) == radius && Math.abs(dz) == radius && random.nextBoolean()) {
                            continue;
                        }
                        if (world.get(x + dx, top + dy, z + dz) == Assets.AIR) {
                            world.set(x + dx, top + dy, z + dz, Assets.LEAVES);
                        }
                    }
                }
            }
        }
    }

    private static float[] lattice(Random random, int side, int sideZ) {
        float[] values = new float[side * sideZ];
        for (int i = 0; i < values.length; i++) {
            values[i] = random.nextFloat();
        }
        return values;
    }

    private static float sample(float[] lattice, int side, float x, float z) {
        int x0 = (int) x;
        int z0 = (int) z;
        float fx = x - x0;
        float fz = z - z0;
        float a = lattice[z0 * side + x0];
        float b = lattice[z0 * side + x0 + 1];
        float c = lattice[(z0 + 1) * side + x0];
        float d = lattice[(z0 + 1) * side + x0 + 1];
        float top = a + (b - a) * fx;
        float bottom = c + (d - c) * fx;
        return top + (bottom - top) * fz;
    }

    private static void village(VoxelWorld world, Random random) {
        int centreX = world.sizeX / 2;
        int centreZ = world.sizeZ / 2;
        for (int h = 0; h < 9; h++) {
            int ox = centreX - 34 + (h % 3) * 26;
            int oz = centreZ - 34 + (h / 3) * 26;
            int ground = surfaceY(world, ox, oz);
            int width = 7 + random.nextInt(3);
            int depth = 7 + random.nextInt(3);
            int tall = 4 + random.nextInt(2);
            for (int x = ox; x < ox + width; x++) {
                for (int z = oz; z < oz + depth; z++) {
                    for (int y = ground; y < ground + tall; y++) {
                        boolean wall = x == ox || z == oz || x == ox + width - 1 || z == oz + depth - 1;
                        if (y == ground + tall - 1) {
                            world.set(x, y, z, Assets.PLANKS);
                        } else if (wall) {
                            // A doorway and windows: holes in the wall, which is what makes the interior
                            // visible and the occlusion culling worth running.
                            boolean door = x == ox + width / 2 && z == oz && y < ground + 3;
                            boolean window = (x + z) % 3 == 0 && y == ground + 2;
                            world.set(x, y, z, door ? Assets.AIR : (window ? Assets.GLASS : Assets.COBBLE));
                        } else {
                            world.set(x, y, z, Assets.AIR);
                        }
                    }
                    world.set(x, ground - 1, z, Assets.PLANKS);
                }
            }
        }
    }

    private static void cave(VoxelWorld world, Random random) {
        // A wandering tunnel: carved air below the surface, so there is geometry the player cannot see
        // from above and geometry they can see only through the entrance.
        float x = world.sizeX * 0.2f;
        float z = world.sizeZ * 0.2f;
        float y = 22.0f;
        for (int step = 0; step < 900; step++) {
            int radius = 2 + random.nextInt(3);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                            int bx = (int) (x + dx);
                            int by = (int) (y + dy);
                            int bz = (int) (z + dz);
                            if (by > 2 && by < 34) {
                                world.set(bx, by, bz, Assets.AIR);
                            }
                        }
                    }
                }
            }
            x += (random.nextFloat() - 0.5f) * 3.0f;
            z += (random.nextFloat() - 0.5f) * 3.0f;
            y += (random.nextFloat() - 0.5f) * 1.2f;
            if (x < 6 || x > world.sizeX - 6) {
                x = Math.max(6, Math.min(world.sizeX - 6, x));
            }
            if (z < 6 || z > world.sizeZ - 6) {
                z = Math.max(6, Math.min(world.sizeZ - 6, z));
            }
            if (y < 8) {
                y = 8;
            }
            if (y > 32) {
                y = 32;
            }
        }
    }

    private static void blast(VoxelWorld world, Random random) {
        // Craters: the geometry an explosion leaves, which is what makes the hundred TNT scene a scene
        // rather than a flat field with entities floating over it.
        int centreX = world.sizeX / 2;
        int centreZ = world.sizeZ / 2;
        for (int c = 0; c < 5; c++) {
            int ox = centreX + random.nextInt(24) - 12;
            int oz = centreZ + random.nextInt(24) - 12;
            int oy = surfaceY(world, ox, oz);
            int radius = 4 + random.nextInt(3);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                            world.set(ox + dx, oy + dy - 1, oz + dz, Assets.AIR);
                        }
                    }
                }
            }
        }
    }

    private static SectionMeshes[] mesh(VoxelWorld world, Assets assets) {
        int sx = world.sectionsX();
        int sy = world.sectionsY();
        int sz = world.sectionsZ();
        SectionMeshes[] sections = new SectionMeshes[sx * sy * sz];
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    sections[(y * sz + z) * sx + x] = SectionMesher.build(world, x, y, z, assets);
                }
            }
        }
        return sections;
    }

    SectionMeshes sectionAt(int x, int y, int z) {
        return this.sections[(y * this.sectionsZ + z) * this.sectionsX + x];
    }

    int sectionIndex(int x, int y, int z) {
        return (y * this.sectionsZ + z) * this.sectionsX + x;
    }

    /** Render distance in sections, which is what the distance cullers are given. */
    int renderDistanceSections() {
        return this.sectionsX / 2;
    }

    float cloudHeight() {
        return CLOUD_HEIGHT;
    }

    int worldBlocks() {
        return this.world.solidBlocks();
    }

    /** The camera state for one frame, filled in place so the loop does not allocate. */
    static final class View {
        float x;
        float y;
        float z;
        /** Degrees, Minecraft's convention: zero is south, positive turns west. */
        float yaw;
        /** Degrees, Minecraft's convention: positive looks down. */
        float pitch;
        float rightX;
        float rightY;
        float rightZ;
        float upX;
        float upY;
        float upZ;
        float fwdX;
        float fwdY;
        float fwdZ;
        float nearHalfWidth;
        float nearHalfHeight;
        float near;
        float far;
        final float[] viewProjection = new float[16];

        void basis() {
            float yawRadians = (float) Math.toRadians(this.yaw);
            float pitchRadians = (float) Math.toRadians(this.pitch);
            float cosPitch = (float) Math.cos(pitchRadians);
            this.fwdX = (float) -Math.sin(yawRadians) * cosPitch;
            this.fwdY = (float) -Math.sin(pitchRadians);
            this.fwdZ = (float) Math.cos(yawRadians) * cosPitch;
            this.rightX = (float) Math.cos(yawRadians);
            this.rightY = 0.0f;
            this.rightZ = (float) Math.sin(yawRadians);
            // up = forward x right, which stays orthonormal at every pitch without a special case.
            this.upX = this.fwdY * this.rightZ - this.fwdZ * this.rightY;
            this.upY = this.fwdZ * this.rightX - this.fwdX * this.rightZ;
            this.upZ = this.fwdX * this.rightY - this.fwdY * this.rightX;
            float length = (float) Math.sqrt(
                    this.upX * this.upX + this.upY * this.upY + this.upZ * this.upZ);
            if (length > 1e-6f) {
                this.upX /= length;
                this.upY /= length;
                this.upZ /= length;
            }
        }
    }

    /**
     * Fills in the camera for one frame.
     *
     * <p>A slow full turn rather than a straight walk: a walk shows the cullers one slice of the world,
     * while a turn shows them every slice, which is the case where a mistake in a mask or a matrix stops
     * being a subtle artefact and becomes a missing chunk.
     */
    void view(int frame, float aspect, View out) {
        float turn = frame * 1.5f;
        out.yaw = turn % 360.0f;
        out.pitch = 12.0f * (float) Math.sin(frame * 0.05);
        out.x = this.camX + 6.0f * (float) Math.cos(Math.toRadians(turn * 0.5));
        out.z = this.camZ + 6.0f * (float) Math.sin(Math.toRadians(turn * 0.5));
        out.y = this.camY;
        out.near = 0.05f;
        out.far = 190.0f;
        float verticalFov = (float) Math.toRadians(70.0);
        out.nearHalfHeight = out.near * (float) Math.tan(verticalFov * 0.5);
        out.nearHalfWidth = out.nearHalfHeight * aspect;
        out.basis();
        net.amdfaster.cull.ViewProjection.build(out.x, out.y, out.z,
                out.rightX, out.rightY, out.rightZ,
                out.upX, out.upY, out.upZ,
                out.fwdX, out.fwdY, out.fwdZ,
                out.nearHalfWidth, out.nearHalfHeight, out.near, out.far,
                out.viewProjection);
    }
}
