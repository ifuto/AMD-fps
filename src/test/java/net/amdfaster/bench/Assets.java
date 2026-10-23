package net.amdfaster.bench;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The asset library: the PNGs and model JSON files under {@code bench/assets}, decoded and indexed.
 *
 * <p>Nothing is generated at run time. The files are produced by {@code bench/tools/make_assets.py} and
 * committed, and this class fails loudly if one is missing, because a benchmark that quietly falls back
 * to procedural placeholders would be measuring its own fallback.
 */
final class Assets {

    /** Block ids. Zero is air and every id above it maps to a model file. */
    static final int AIR = 0;
    static final int GRASS = 1;
    static final int DIRT = 2;
    static final int STONE = 3;
    static final int COBBLE = 4;
    static final int PLANKS = 5;
    static final int LOG = 6;
    static final int LEAVES = 7;
    static final int SAND = 8;
    static final int GLASS = 9;
    static final int WATER = 10;
    static final int LAVA = 11;
    static final int TNT = 12;
    static final int CHEST = 13;
    static final int BLOCK_KINDS = 14;

    /** Face indices, matching the bit values {@code MeshletFaceMaskCuller} uses. */
    static final int DOWN = 0;
    static final int UP = 1;
    static final int NORTH = 2;
    static final int SOUTH = 3;
    static final int WEST = 4;
    static final int EAST = 5;

    /** Minecraft's baked face brightness, which is why a cube reads as a cube. */
    static final float[] FACE_SHADE = {0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f};

    /** How many frames each animated texture has. */
    static final int LIQUID_FRAMES = 4;

    /** Texture table index of the particle atlas, addressed by particles rather than by blocks. */
    int particleAtlas;
    /** Texture table index of the GUI atlas. */
    int guiAtlas;
    /** Texture table index of water frame zero; frames are contiguous after it. */
    int waterBase;
    /** Texture table index of lava frame zero. */
    int lavaBase;
    /** Texture table index of the chest unwrap. */
    int chestTexture;
    /** Texture table index of the glass pane, used for the translucent shadow disc. */
    int glassTexture;

    Texture[] textures = new Texture[0];
    final BlockGeometry[] blocks = new BlockGeometry[BLOCK_KINDS];
    EntityGeometry creeper;
    EntityGeometry zombie;
    /** The chest, as a box model: its lid is the animated part the block-entity pass decides about. */
    EntityGeometry chestModel;
    /** Primed TNT is an entity drawn with the block's model, which is why it is a box model here too. */
    EntityGeometry tntModel;

    /** One face of one block model: which texture it reads and which part of it. */
    static final class FaceDef {
        int texture;
        float u0;
        float v0;
        float u1;
        float v1;
    }

    /** A block model, resolved to six faces and a box. */
    static final class BlockGeometry {
        final FaceDef[] faces = new FaceDef[6];
        float fromX;
        float fromY;
        float fromZ;
        float toX = 16f;
        float toY = 16f;
        float toZ = 16f;
        /** {@link SoftRenderer#OPAQUE}, {@code CUTOUT} or {@code BLEND}. */
        int mode = SoftRenderer.OPAQUE;
        /** Whether this block's faces hide their neighbours. */
        boolean solid = true;
        boolean animated;
    }

    /** One cube of an entity model, in the sixteenths space model files use. */
    static final class Cube {
        float originX;
        float originY;
        float originZ;
        float sizeX;
        float sizeY;
        float sizeZ;
        float uvU;
        float uvV;
        /** Texture table index, so a cube can read a different texture from its siblings. */
        int texture;
        boolean animated;
    }

    /** An entity model: a texture and the cubes that sample it. */
    static final class EntityGeometry {
        String textureName;
        int texture;
        int textureWidth;
        int textureHeight;
        Cube[] cubes = new Cube[0];
        int animatedCubes;
        /**
         * Whether the cube UVs are in sixteenths of a whole texture, as block model files give them, or
         * in texels of an unwrap, as entity model files give them. The two conventions differ by a
         * factor of sixteen and getting it wrong draws the same picture at the wrong scale, which is
         * exactly the kind of error a benchmark would not notice.
         */
        boolean boxUvs;

        /**
         * How many cubes the coarse level of detail draws.
         *
         * <p>It keeps the head and the body and drops the limbs, which is the reduction
         * {@code EntityLodSelector} is priced for -- 21 bones down to 6. It is a visible change at the
         * distance it applies at and an invisible one at the distance it does not, which is the whole
         * premise of a level of detail; the selector's hysteresis is what stops it flickering.
         */
        int cubesAtTier(boolean coarse) {
            if (!coarse) {
                return this.cubes.length;
            }
            return Math.max(1, Math.min(2, this.cubes.length));
        }
    }

    private final Map<String, Integer> textureIndex = new LinkedHashMap<>();
    private final List<Texture> loaded = new ArrayList<>();

    private final File root;

    private Assets(File root) {
        this.root = root;
    }

    /** Finds the asset directory relative to the working directory Gradle gives the test task. */
    static File locate() {
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 5 && dir != null; i++) {
            File candidate = new File(dir, "bench/assets/textures");
            if (candidate.isDirectory()) {
                return new File(dir, "bench/assets");
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException(
                "bench/assets not found from " + System.getProperty("user.dir")
                        + "; run bench/tools/make_assets.py and check the working directory");
    }

    static Assets load() throws IOException {
        File root = locate();
        Assets assets = new Assets(root);
        assets.loadTextures();
        assets.loadBlocks();
        assets.creeper = assets.loadEntity("entity/creeper");
        assets.zombie = assets.loadEntity("entity/zombie");
        assets.chestModel = assets.loadBoxModel("block/chest", "chest");
        assets.tntModel = assets.loadBoxModel("block/tnt", "tnt_side");
        return assets;
    }

    private void loadTextures() throws IOException {
        // Order matters: the animated liquids must sit contiguously so a frame index is an offset.
        String[] order = {
            "grass_block_top", "grass_block_side", "dirt", "stone", "cobblestone", "oak_planks",
            "oak_log", "oak_leaves", "sand", "glass", "tnt_side", "tnt_top", "chest",
            "creeper", "zombie",
            "water_0", "water_1", "water_2", "water_3",
            "lava_0", "lava_1", "lava_2", "lava_3",
            "particles", "gui",
        };
        for (String name : order) {
            Texture texture = Texture.load(new File(this.root, "textures/" + name + ".png"), name);
            this.textureIndex.put(name, this.loaded.size());
            this.loaded.add(texture);
        }
        this.waterBase = this.textureIndex.get("water_0").intValue();
        this.lavaBase = this.textureIndex.get("lava_0").intValue();
        this.particleAtlas = this.textureIndex.get("particles").intValue();
        this.guiAtlas = this.textureIndex.get("gui").intValue();
        this.chestTexture = this.textureIndex.get("chest").intValue();
        this.glassTexture = this.textureIndex.get("glass").intValue();
        this.textures = this.loaded.toArray(new Texture[0]);
    }

    private int texture(String name) {
        Integer index = this.textureIndex.get(name);
        if (index == null) {
            throw new IllegalStateException("model refers to a texture that was not loaded: " + name);
        }
        return index.intValue();
    }

    private Map<String, Object> readModel(String path) throws IOException {
        File file = new File(this.root, "models/" + path + ".json");
        if (!file.isFile()) {
            throw new IOException("missing model, run bench/tools/make_assets.py first: " + file);
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return TinyJson.object(TinyJson.parse(text));
    }

    private void loadBlocks() throws IOException {
        this.blocks[GRASS] = blockModel("block/grass_block", SoftRenderer.OPAQUE, true, false);
        this.blocks[DIRT] = blockModel("block/cube_all_dirt", SoftRenderer.OPAQUE, true, false);
        this.blocks[STONE] = blockModel("block/cube_all_stone", SoftRenderer.OPAQUE, true, false);
        this.blocks[COBBLE] = blockModel("block/cube_all_cobblestone", SoftRenderer.OPAQUE, true, false);
        this.blocks[PLANKS] = blockModel("block/cube_all_planks", SoftRenderer.OPAQUE, true, false);
        this.blocks[LOG] = blockModel("block/cube_all_log", SoftRenderer.OPAQUE, true, false);
        this.blocks[SAND] = blockModel("block/cube_all_sand", SoftRenderer.OPAQUE, true, false);
        this.blocks[LAVA] = blockModel("block/cube_all_stone", SoftRenderer.OPAQUE, true, true);
        // Cutout and translucent blocks do not hide their neighbours, which is what makes a face between
        // two leaves or two panes of glass get drawn.
        this.blocks[LEAVES] = blockModel("block/leaves", SoftRenderer.CUTOUT, false, false);
        this.blocks[GLASS] = blockModel("block/glass", SoftRenderer.BLEND, false, false);
        this.blocks[WATER] = blockModel("block/cube_all_stone", SoftRenderer.BLEND, false, true);
        this.blocks[TNT] = blockModel("block/tnt", SoftRenderer.OPAQUE, true, false);
        this.blocks[CHEST] = blockModel("block/cube_all_planks", SoftRenderer.OPAQUE, true, false);
        // The animated blocks point at frame zero of their strip; the pipeline adds the frame offset.
        this.blocks[WATER].faces[0].texture = this.waterBase;
        this.blocks[WATER].faces[1].texture = this.waterBase;
        this.blocks[WATER].faces[2].texture = this.waterBase;
        this.blocks[WATER].faces[3].texture = this.waterBase;
        this.blocks[WATER].faces[4].texture = this.waterBase;
        this.blocks[WATER].faces[5].texture = this.waterBase;
        this.blocks[LAVA].faces[0].texture = this.lavaBase;
        this.blocks[LAVA].faces[1].texture = this.lavaBase;
        this.blocks[LAVA].faces[2].texture = this.lavaBase;
        this.blocks[LAVA].faces[3].texture = this.lavaBase;
        this.blocks[LAVA].faces[4].texture = this.lavaBase;
        this.blocks[LAVA].faces[5].texture = this.lavaBase;
    }

    private BlockGeometry blockModel(String path, int mode, boolean solid, boolean animated)
            throws IOException {
        Map<String, Object> model = readModel(path);
        Map<String, Object> textureMap = TinyJson.object(model.get("textures"));
        List<Object> elements = TinyJson.array(model.get("elements"));
        if (elements.size() != 1) {
            throw new IOException(path + " should have exactly one element, has " + elements.size());
        }
        Map<String, Object> element = TinyJson.object(elements.get(0));
        double[] from = TinyJson.vector(element.get("from"), 3);
        double[] to = TinyJson.vector(element.get("to"), 3);
        Map<String, Object> faces = TinyJson.object(element.get("faces"));

        BlockGeometry geometry = new BlockGeometry();
        geometry.fromX = (float) from[0];
        geometry.fromY = (float) from[1];
        geometry.fromZ = (float) from[2];
        geometry.toX = (float) to[0];
        geometry.toY = (float) to[1];
        geometry.toZ = (float) to[2];
        geometry.mode = mode;
        geometry.solid = solid;
        geometry.animated = animated;

        String[] names = {"down", "up", "north", "south", "west", "east"};
        for (int i = 0; i < 6; i++) {
            Map<String, Object> face = TinyJson.object(faces.get(names[i]));
            double[] uv = TinyJson.vector(face.get("uv"), 4);
            String ref = TinyJson.string(face.get("texture"));
            if (!ref.startsWith("#")) {
                throw new IOException(path + " face " + names[i] + " does not reference a texture: " + ref);
            }
            String resolved = TinyJson.string(textureMap.get(ref.substring(1)));
            FaceDef def = new FaceDef();
            def.texture = texture(resolved);
            // Model files give UVs in sixteenths of a texture; the rasteriser wants zero to one.
            def.u0 = (float) (uv[0] / 16.0);
            def.v0 = (float) (uv[1] / 16.0);
            def.u1 = (float) (uv[2] / 16.0);
            def.v1 = (float) (uv[3] / 16.0);
            geometry.faces[i] = def;
        }
        return geometry;
    }

    /**
     * Reads a block model file's elements as a list of cubes, which is what the block-entity and TNT
     * passes need: geometry that moves, addressed the way an entity model is.
     */
    private EntityGeometry loadBoxModel(String path, String textureName) throws IOException {
        Map<String, Object> model = readModel(path);
        EntityGeometry geometry = new EntityGeometry();
        geometry.textureName = textureName;
        geometry.texture = texture(textureName);
        geometry.textureWidth = 16;
        geometry.textureHeight = 16;
        geometry.boxUvs = true;
        List<Object> elements = TinyJson.array(model.get("elements"));
        geometry.cubes = new Cube[elements.size()];
        int animated = 0;
        for (int i = 0; i < elements.size(); i++) {
            Map<String, Object> element = TinyJson.object(elements.get(i));
            double[] from = TinyJson.vector(element.get("from"), 3);
            double[] to = TinyJson.vector(element.get("to"), 3);
            Map<String, Object> faces = TinyJson.object(element.get("faces"));
            double[] uv = TinyJson.vector(TinyJson.object(faces.get("north")).get("uv"), 4);
            Cube cube = new Cube();
            cube.originX = (float) from[0];
            cube.originY = (float) from[1];
            cube.originZ = (float) from[2];
            cube.sizeX = (float) (to[0] - from[0]);
            cube.sizeY = (float) (to[1] - from[1]);
            cube.sizeZ = (float) (to[2] - from[2]);
            cube.uvU = (float) uv[0];
            cube.uvV = (float) uv[1];
            cube.texture = geometry.texture;
            cube.animated = element.containsKey("animated") && TinyJson.flag(element.get("animated"));
            if (cube.animated) {
                animated++;
            }
            geometry.cubes[i] = cube;
        }
        geometry.animatedCubes = animated;
        return geometry;
    }

    private EntityGeometry loadEntity(String path) throws IOException {
        Map<String, Object> model = readModel(path);
        EntityGeometry geometry = new EntityGeometry();
        geometry.boxUvs = false;
        geometry.textureName = TinyJson.string(model.get("texture"));
        geometry.texture = texture(geometry.textureName);
        geometry.textureWidth = (int) TinyJson.number(model.get("textureWidth"));
        geometry.textureHeight = (int) TinyJson.number(model.get("textureHeight"));
        List<Object> cubes = TinyJson.array(model.get("cubes"));
        geometry.cubes = new Cube[cubes.size()];
        int animated = 0;
        for (int i = 0; i < cubes.size(); i++) {
            Map<String, Object> cube = TinyJson.object(cubes.get(i));
            double[] origin = TinyJson.vector(cube.get("origin"), 3);
            double[] size = TinyJson.vector(cube.get("size"), 3);
            double[] uv = TinyJson.vector(cube.get("uv"), 2);
            Cube parsed = new Cube();
            parsed.originX = (float) origin[0];
            parsed.originY = (float) origin[1];
            parsed.originZ = (float) origin[2];
            parsed.sizeX = (float) size[0];
            parsed.sizeY = (float) size[1];
            parsed.sizeZ = (float) size[2];
            parsed.uvU = (float) uv[0];
            parsed.uvV = (float) uv[1];
            parsed.texture = geometry.texture;
            parsed.animated = TinyJson.flag(cube.get("animated"));
            if (parsed.animated) {
                animated++;
            }
            geometry.cubes[i] = parsed;
        }
        geometry.animatedCubes = animated;
        return geometry;
    }

    int textureCount() {
        return this.textures.length;
    }

    /** Total decoded texels, so a report can show the assets are real content rather than placeholders. */
    long texelCount() {
        long total = 0;
        for (Texture texture : this.textures) {
            total += (long) texture.width * texture.height;
        }
        return total;
    }
}
