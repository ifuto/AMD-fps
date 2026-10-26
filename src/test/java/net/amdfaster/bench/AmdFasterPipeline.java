package net.amdfaster.bench;

import java.util.ArrayList;
import java.util.List;
import net.amdfaster.blockentity.AnimationSkipper;
import net.amdfaster.blockentity.StaticGeometryBaker;
import net.amdfaster.cull.Frustum;
import net.amdfaster.cull.HierarchicalFrustumCuller;
import net.amdfaster.cull.MeshletFaceMaskCuller;
import net.amdfaster.cull.NormalConeCuller;
import net.amdfaster.cull.SoftwareRasterizer;
import net.amdfaster.cull.SortKey;
import net.amdfaster.cull.TileDepthBuffer;
import net.amdfaster.draw.SortKeyRadixSort;
import net.amdfaster.draw.TranslucentSortCache;
import net.amdfaster.entity.EntityBatcher;
import net.amdfaster.entity.EntityInstance;
import net.amdfaster.entity.EntityLodSelector;
import net.amdfaster.entity.ShadowCuller;
import net.amdfaster.particle.ParticleDrawBatcher;
import net.amdfaster.render.AnimatedTextureScheduler;
import net.amdfaster.render.CloudMeshCache;
import net.amdfaster.render.GuiBatcher;
import net.amdfaster.render.HudUpdateScheduler;
import net.amdfaster.render.WeatherMeshCache;
import net.amdfaster.rng.FastRandom;

/**
 * AMD-Faster, running the classes the mod actually ships.
 *
 * <p>This is the part of the benchmark that is not a model. {@code HierarchicalFrustumCuller},
 * {@code MeshletFaceMaskCuller}, {@code NormalConeCuller}, {@code SoftwareRasterizer},
 * {@code TileDepthBuffer}, {@code SortKey}, {@code SortKeyRadixSort}, {@code TranslucentSortCache},
 * {@code EntityLodSelector}, {@code EntityBatcher}, {@code ShadowCuller}, {@code AnimationSkipper},
 * {@code StaticGeometryBaker}, {@code HudUpdateScheduler}, {@code GuiBatcher},
 * {@code AnimatedTextureScheduler}, {@code CloudMeshCache}, {@code WeatherMeshCache} and
 * {@code FastRandom} are called here, on real geometry, and the frame rate is what falls out. If one of
 * them is inert or its saving is smaller than its documentation claims, that shows up as a number rather
 * than as a paragraph.
 *
 * <p>What it cannot show: the price of a GL draw call. A software rasteriser has no driver, so
 * {@code EntityBatcher} turning three hundred draws into nine is visible here only as a count, not as
 * time. That is stated rather than papered over, because on AMD hardware that count is a large part of
 * the real win.
 */
final class AmdFasterPipeline implements Pipeline, HierarchicalFrustumCuller.Sink {

    /** How close a lid has to move before it is worth animating. */
    private static final double LID_EPSILON = 1e-9;

    private final HierarchicalFrustumCuller hierarchical = new HierarchicalFrustumCuller();
    private final MeshletFaceMaskCuller faceMask = new MeshletFaceMaskCuller();
    private final NormalConeCuller cone = new NormalConeCuller();
    private final Frustum frustum = new Frustum(new float[16]);
    private final SortKeyRadixSort sorter = new SortKeyRadixSort();
    private final TranslucentSortCache translucentSort = new TranslucentSortCache();
    private final EntityLodSelector lod = new EntityLodSelector();
    private final EntityBatcher batcher = new EntityBatcher();
    private final ShadowCuller shadows = new ShadowCuller();
    private final AnimationSkipper animationSkipper = new AnimationSkipper();
    private final StaticGeometryBaker baker = new StaticGeometryBaker();
    private final HudUpdateScheduler hudScheduler = new HudUpdateScheduler();
    private final GuiBatcher guiBatcher = new GuiBatcher();
    private final AnimatedTextureScheduler textureScheduler = new AnimatedTextureScheduler();
    private final CloudMeshCache cloudCache = new CloudMeshCache();
    private final WeatherMeshCache weatherCache = new WeatherMeshCache();
    private final ParticleDrawBatcher rainBatch = new ParticleDrawBatcher();

    /** The same, for particles: one submission for whatever survived the frustum, in the order it was in. */
    private final ParticleDrawBatcher particleBatch = new ParticleDrawBatcher();

    private final List<GuiBatcher.Element> hud = new ArrayList<>();
    private final float[] uv = new float[4];
    private final float[] model = new float[12];
    private final float[] modelView = new float[12];
    private final float[] viewMatrix = new float[12];
    private final float[] box = new float[6];
    private final int[] animatedWater = new int[256];
    private final int[] animatedLava = new int[256];

    private Texture[] textures = new Texture[0];
    private Scene scene;
    private Assets assets;
    private TileDepthBuffer depthTiles;

    /**
     * How many framebuffer pixels one pixel of the occlusion buffer stands for.
     *
     * <p>The buffer was being built at framebuffer resolution, and rasterising occluder boxes into it at
     * that size cost 298 to 364 microseconds a frame -- more than drawing everything that survived it,
     * and close to half of a frame whose baseline costs 815 in total. A hierarchical depth buffer exists
     * to be coarse; this is the mip level, not a compromise on it. At an eighth per axis there are
     * sixty-four times fewer sub-tiles to rasterise.
     *
     * <p>Coarser is safe in one direction only, so both roundings are biased: an occluder's rectangle
     * rounds inward, because claiming coverage for a pixel that is not covered is how something visible
     * stops being drawn, and a candidate's rounds outward, because asking about less than its whole
     * footprint is the same mistake from the other side.
     */
    static final int OCCLUSION_SCALE = 8;

    private int occlusionWidth;

    private int occlusionHeight;
    private SoftwareRasterizer occlusion;
    private Mesh[] entityFull = new Mesh[0];
    private Mesh[] entityCoarse = new Mesh[0];
    private Mesh chestLid;
    private Mesh chestBox;
    private Mesh shadowQuad;
    private Mesh cloudMesh;
    private Mesh rainDrop;
    private float[] rainOffsets = new float[0];
    private Mesh[] particleQuads = new Mesh[0];

    /** The scene the frustum walk is filling {@link #survivors} for; the walk calls back into accept. */
    private Scene cullScene;

    private int[] survivors = new int[0];
    private int survivorCount;
    /** Surviving (section, face) pairs, section index times eight plus the face, decided then batched. */
    private int[] faceQueue = new int[0];
    private int faceQueueCount;
    private int[] cutoutQueue = new int[0];
    private int cutoutQueueCount;
    private long[] sortKeys = new long[0];
    private int[] sortPayload = new int[0];
    private int[] translucentOrder = new int[0];
    private int translucentCount;
    private boolean[] coarseLastFrame = new boolean[0];
    private long lastWaterFrame = -1;
    private long lastLavaFrame = -1;
    private float[] hudQuads;
    private int hudQuadCount;
    private boolean chestBaked;
    private double lidAngle;

    // ---- reported --------------------------------------------------------------------------------
    long sectionsSubmitted;
    long sectionsOccluded;
    long sectionsDrawn;
    long faceMeshesCulled;
    long entitiesDrawn;
    long entitiesOccluded;
    long entitiesCoarse;
    long entityDrawCalls;
    long shadowsDrawn;
    long blockEntitiesAnimated;
    long blockEntitiesSkipped;
    long particlesDrawn;
    long particlesCulled;
    long hudRebuilds;
    long hudElements;
    long guiBatches;
    long rainRebuilds;
    long rainDrawn;
    long rainCulled;
    long rainBatches;
    long textureUploads;

    @Override
    public String name() {
        return "AMD-Faster";
    }

    @Override
    public void prepare(Scene scene, Assets assets) {
        this.textures = assets.textures;
        this.scene = scene;
        this.assets = assets;
        this.occlusionWidth = 1280 / OCCLUSION_SCALE;
        this.occlusionHeight = 720 / OCCLUSION_SCALE;
        this.depthTiles = new TileDepthBuffer(this.occlusionWidth, this.occlusionHeight);
        this.occlusion = new SoftwareRasterizer(this.depthTiles);
        this.entityFull = new Mesh[] {
            EntityMeshes.build(assets.creeper, assets.creeper.cubes.length),
            EntityMeshes.build(assets.zombie, assets.zombie.cubes.length),
            EntityMeshes.build(assets.tntModel, assets.tntModel.cubes.length),
        };
        this.entityCoarse = new Mesh[] {
            EntityMeshes.build(assets.creeper, assets.creeper.cubesAtTier(true)),
            EntityMeshes.build(assets.zombie, assets.zombie.cubesAtTier(true)),
            EntityMeshes.build(assets.tntModel, assets.tntModel.cubesAtTier(true)),
        };
        // The chest is two parts: a box that never moves and a lid that does. Baking the box into the
        // section mesh leaves the renderer with the lid alone.
        this.chestBox = EntityMeshes.build(assets.chestModel, 0, 1);
        this.chestLid = EntityMeshes.build(assets.chestModel, 1, assets.chestModel.cubes.length);
        List<StaticGeometryBaker.Part> staticParts = new ArrayList<>();
        List<StaticGeometryBaker.Part> animatedParts = new ArrayList<>();
        for (Assets.Cube cube : assets.chestModel.cubes) {
            StaticGeometryBaker.Part part = new StaticGeometryBaker.Part(
                    cube.animated ? "lid" : "box", 6, cube.animated);
            if (cube.animated) {
                animatedParts.add(part);
            } else {
                staticParts.add(part);
            }
        }
        this.baker.bake("minecraft:chest", staticParts, animatedParts);
        this.chestBaked = this.baker.isBaked("minecraft:chest");

        MeshBuilder shadow = new MeshBuilder();
        shadow.quad(-0.4f, 0.0f, -0.4f, 0f, 0f, 0.4f, 0.0f, -0.4f, 1f, 0f,
                -0.4f, 0.0f, 0.4f, 0f, 1f, 0.4f, 0.0f, 0.4f, 1f, 1f,
                assets.glassTexture, 0.35f);
        this.shadowQuad = shadow.build();

        this.cloudMesh = Passes.clouds(scene, assets);
        this.rainOffsets = new float[Passes.RAIN_QUADS * 3];
        Passes.rainOffsets(new java.util.Random(12345L), this.rainOffsets);
        this.rainDrop = Passes.rainDrop(assets.waterBase);
        this.particleQuads = Passes.particleQuads(assets);
        this.survivors = new int[scene.sections.length];
        this.sortKeys = new long[scene.sections.length];
        this.sortPayload = new int[scene.sections.length];
        this.translucentOrder = new int[scene.sections.length];
        this.coarseLastFrame = new boolean[scene.entityCount];
    }

    @Override
    public void renderFrame(Scene scene, Assets assets, SoftRenderer renderer, Scene.View view,
            int frame) {
        renderer.clear(0xFF78A7FF);
        this.frustum.update(view.viewProjection);
        renderer.fillViewMatrix(this.viewMatrix);
        // The tile buffer is sized to the framebuffer it stands in for, so it has to follow it.
        int wantedWidth = Math.max(1, renderer.width / OCCLUSION_SCALE);
        int wantedHeight = Math.max(1, renderer.height / OCCLUSION_SCALE);
        if (this.depthTiles.width() != wantedWidth || this.depthTiles.height() != wantedHeight) {
            this.occlusionWidth = wantedWidth;
            this.occlusionHeight = wantedHeight;
            this.depthTiles = new TileDepthBuffer(wantedWidth, wantedHeight);
            this.occlusion = new SoftwareRasterizer(this.depthTiles);
        }
        this.occlusion.beginFrame();

        long at = System.nanoTime();
        terrain(scene, renderer, view);
        long now = System.nanoTime();
        this.nsTerrain += now - at;
        at = now;
        this.occlusion.complete();
        now = System.nanoTime();
        this.nsOcclusion += now - at;
        at = now;
        entities(scene, renderer, view, frame);
        now = System.nanoTime();
        this.nsEntities += now - at;
        at = now;
        blockEntities(scene, renderer, frame);
        particles(scene, renderer, view, frame);
        now = System.nanoTime();
        this.nsParticles += now - at;
        at = now;
        clouds(renderer, frame);
        if (scene.raining) {
            rain(scene, renderer, view, frame);
        }
        now = System.nanoTime();
        this.nsClouds += now - at;
        at = now;
        animateTextures(assets, frame);
        gui(renderer, assets, frame);
        this.nsGui += System.nanoTime() - at;
    }

    /**
     * Nanoseconds per stage, accumulated over the run.
     *
     * <p>Greedy meshing made the geometry cheap enough that this pipeline became slower than the
     * baseline on the CPU-bound path -- 0.75x -- which means the work it does to avoid drawing is
     * costing more than the drawing it avoids. These say which stage does it, rather than leaving it
     * to be inferred from the section counts.
     */
    long nsTerrain;

    long nsOcclusion;

    /** Inside {@code terrain}: the frustum walk and the sort. */
    long nsCullSort;

    /** Inside {@code terrain}: projecting every survivor and the progressive occluder test. */
    long nsOccluder;

    /** Inside {@code terrain}: handing the surviving meshes to the rasteriser. */
    long nsSubmit;

    /** Inside {@code terrain}: the translucent pass and its sort. */
    long nsTranslucent;

    /**
     * Triangles each part of the frame submits, accumulated over the run.
     *
     * <p>On the driver path the frame is vertex bound: 434 of 673 microseconds is handing geometry over,
     * and the whole ratio between the two pipelines is close to the ratio of their triangles. Which part
     * of the frame those triangles belong to decides what is worth cutting, and so far that has been
     * guessed at from the element counts.
     */
    /** Frustum tests the hierarchical walk made, and the leaves it reached, so pruning can be checked. */
    long frustumTests;

    long frustumLeaves;

    long terrainTriangles;

    long cloudTriangles;

    long particleTriangles;

    @Override
    public void accept(int sectionX, int sectionY, int sectionZ) {
        this.survivors[this.survivorCount++] = this.cullScene.sectionIndex(sectionX, sectionY, sectionZ);
    }

    private static int inwardLo(float pixel) {
        return Math.max(0, (int) Math.ceil(pixel / OCCLUSION_SCALE));
    }

    private static int inwardHi(float pixel, int max) {
        return Math.min(max, (int) Math.floor(pixel / OCCLUSION_SCALE));
    }

    private static int outwardLo(float pixel) {
        return Math.max(0, (int) Math.floor(pixel / OCCLUSION_SCALE));
    }

    private static int outwardHi(float pixel, int max) {
        return Math.min(max, (int) Math.ceil(pixel / OCCLUSION_SCALE));
    }

    long nsEntities;

    long nsParticles;

    long nsClouds;

    long nsGui;

    private void terrain(Scene scene, SoftRenderer renderer, Scene.View view) {
        long stageAt = System.nanoTime();
        int cameraMask = MeshletFaceMaskCuller.visibleMask(facing(view), vertical(view));
        this.survivorCount = 0;
        this.faceQueueCount = 0;
        this.cutoutQueueCount = 0;
        if (this.faceQueue.length < scene.sections.length * 6) {
            this.faceQueue = new int[scene.sections.length * 6];
            this.cutoutQueue = new int[scene.sections.length];
        }
        // The pipeline itself is the sink rather than a lambda written here. A lambda that captures
        // scene and this is a new object on every frame, and it is handed to a walk that calls it once
        // per surviving section through an interface -- so the frame pays an allocation and an
        // indirection for something that could be a method it already has.
        this.cullScene = scene;
        this.hierarchical.cull(this.frustum, 0, 0, 0,
                scene.sectionsX, scene.sectionsY, scene.sectionsZ, this);
        this.frustumTests = this.hierarchical.tests();
        this.frustumLeaves = this.hierarchical.leavesReached();
        this.sectionsSubmitted += this.survivorCount;

        // Front to back under reversed Z, which is what SortKey encodes so the caller cannot invert it.
        for (int i = 0; i < this.survivorCount; i++) {
            SectionMeshes section = scene.sections[this.survivors[i]];
            float cx = (section.minX + section.maxX) * 0.5f - view.x;
            float cy = (section.minY + section.maxY) * 0.5f - view.y;
            float cz = (section.minZ + section.maxZ) * 0.5f - view.z;
            float along = cx * view.fwdX + cy * view.fwdY + cz * view.fwdZ;
            float depth = along <= view.near ? 1.0f : Math.min(1.0f, view.near / along);
            this.sortKeys[i] = SortKey.key(SortKey.BUCKET_OPAQUE, depth, this.survivors[i]);
            this.sortPayload[i] = this.survivors[i];
        }
        this.sorter.sort(this.sortKeys, this.sortPayload, this.survivorCount);

        long stageNow = System.nanoTime();
        this.nsCullSort += stageNow - stageAt;
        stageAt = stageNow;
        for (int i = 0; i < this.survivorCount; i++) {
            SectionMeshes section = scene.sections[this.sortPayload[i]];
            boolean projected = renderer.projectBox(section.minX, section.minY, section.minZ,
                    section.maxX, section.maxY, section.maxZ, this.box);
            if (!projected) {
                continue;
            }
            int maxX = this.occlusionWidth - 1;
            int maxY = this.occlusionHeight - 1;
            int ox0 = inwardLo(this.box[0]);
            int oy0 = inwardLo(this.box[1]);
            int ox1 = inwardHi(this.box[2], maxX);
            int oy1 = inwardHi(this.box[3], maxY);
            int tx0 = outwardLo(this.box[0]);
            int ty0 = outwardLo(this.box[1]);
            int tx1 = outwardHi(this.box[2], maxX);
            int ty1 = outwardHi(this.box[3], maxY);
            float nearDepth = this.box[4];

            if (section.solidBox) {
                // Nothing to draw -- a full section has no exposed faces -- but it is the best occluder
                // in the world, and its bounding box is exactly its shape.
                if (ox0 <= ox1 && oy0 <= oy1) {
                    this.occlusion.addOccluder(ox0, oy0, ox1, oy1, nearDepth);
                }
                continue;
            }
            if (section.isEmpty()) {
                continue;
            }
            if (tx0 <= tx1 && ty0 <= ty1 && this.occlusion.test(tx0, ty0, tx1, ty1, nearDepth)) {
                this.sectionsOccluded++;
                continue;
            }

            float centreX = (section.minX + section.maxX) * 0.5f;
            float centreY = (section.minY + section.maxY) * 0.5f;
            float centreZ = (section.minZ + section.maxZ) * 0.5f;
            float toCameraX = view.x - centreX;
            float toCameraY = view.y - centreY;
            float toCameraZ = view.z - centreZ;
            float length = (float) Math.sqrt(
                    toCameraX * toCameraX + toCameraY * toCameraY + toCameraZ * toCameraZ);
            if (length > 1e-4f) {
                toCameraX /= length;
                toCameraY /= length;
                toCameraZ /= length;
            }

            this.sectionsDrawn++;
            for (int face = 0; face < 6; face++) {
                Mesh mesh = section.opaque[face];
                if (mesh.isEmpty()) {
                    continue;
                }
                int bit = 1 << face;
                if (!this.faceMask.shouldDraw(bit, cameraMask)) {
                    this.faceMeshesCulled++;
                    continue;
                }
                float[] normal = FACE_NORMALS[face];
                // A directional mesh's normals all point the same way, so its cone is a single direction
                // and the cutoff is one. The test then reduces to "does this face turn toward the camera",
                // decided once for the whole mesh rather than once per triangle.
                if (!this.cone.shouldDraw(normal[0], normal[1], normal[2], 1.0,
                        toCameraX, toCameraY, toCameraZ)) {
                    this.faceMeshesCulled++;
                    continue;
                }
                // Decided here, submitted after the walk. The occlusion test is progressive -- each
                // section is tested against the ones already accepted -- so the decisions cannot move,
                // but the submission can, and batching it is what a real renderer does with the list.
                this.faceQueue[this.faceQueueCount++] = this.sortPayload[i] * 8 + face;
                this.terrainTriangles += mesh.triangleCount;
            }
            this.cutoutQueue[this.cutoutQueueCount++] = this.sortPayload[i];
            this.terrainTriangles += section.cutout.triangleCount;

            if (ox0 <= ox1 && oy0 <= oy1) {
                this.occlusion.addOccluder(ox0, oy0, ox1, oy1, nearDepth);
            }
            this.translucentOrder[this.translucentCount++] = this.sortPayload[i];
        }

        stageNow = System.nanoTime();
        this.nsOccluder += stageNow - stageAt;
        stageAt = stageNow;
        renderer.beginTerrain(SoftRenderer.OPAQUE);
        for (int i = 0; i < this.faceQueueCount; i++) {
            int entry = this.faceQueue[i];
            renderer.drawMesh(scene.sections[entry / 8].opaque[entry % 8],
                    SoftRenderer.OPAQUE, this.textures);
        }
        renderer.endTerrain();
        renderer.beginTerrain(SoftRenderer.CUTOUT);
        for (int i = 0; i < this.cutoutQueueCount; i++) {
            renderer.drawMesh(scene.sections[this.cutoutQueue[i]].cutout,
                    SoftRenderer.CUTOUT, this.textures);
        }
        renderer.endTerrain();
        stageNow = System.nanoTime();
        this.nsSubmit += stageNow - stageAt;
        stageAt = stageNow;

        translucent(scene, renderer, view);
        this.nsTranslucent += System.nanoTime() - stageAt;
    }

    /**
     * The one table of face normals, owned by the renderer. A second copy here would be a second opinion
     * about which way a face points, and the merged mesh the GL backend builds has to agree with the
     * cone test below or it culls the faces the player is looking at.
     */
    private static final float[][] FACE_NORMALS = SoftRenderer.FACE_NORMALS;

    private void translucent(Scene scene, SoftRenderer renderer, Scene.View view) {
        if (this.translucentCount == 0) {
            return;
        }
        // Two quads swap when the camera crosses the plane between them, so a movement smaller than half
        // the smallest gap cannot have changed the order. A block is the gap in a voxel world.
        if (this.translucentSort.needsSort(view.x, view.y, view.z, 1.0)) {
            long[] keys = new long[this.translucentCount];
            int[] payload = new int[this.translucentCount];
            for (int i = 0; i < this.translucentCount; i++) {
                SectionMeshes section = scene.sections[this.translucentOrder[i]];
                float cx = (section.minX + section.maxX) * 0.5f - view.x;
                float cy = (section.minY + section.maxY) * 0.5f - view.y;
                float cz = (section.minZ + section.maxZ) * 0.5f - view.z;
                float along = cx * view.fwdX + cy * view.fwdY + cz * view.fwdZ;
                float depth = along <= view.near ? 1.0f : Math.min(1.0f, view.near / along);
                keys[i] = SortKey.key(SortKey.BUCKET_TRANSLUCENT, depth, i);
                payload[i] = i;
            }
            this.sorter.sort(keys, payload, this.translucentCount);
            int[] sorted = new int[this.translucentCount];
            for (int i = 0; i < this.translucentCount; i++) {
                sorted[i] = this.translucentOrder[payload[i]];
            }
            System.arraycopy(sorted, 0, this.translucentOrder, 0, this.translucentCount);
        }
        renderer.beginTerrain(SoftRenderer.BLEND);
        for (int i = this.translucentCount - 1; i >= 0; i--) {
            SectionMeshes section = scene.sections[this.translucentOrder[i]];
            if (!section.blend.isEmpty()) {
                renderer.drawMesh(section.blend, SoftRenderer.BLEND, this.textures);
            }
        }
        renderer.endTerrain();
        this.translucentCount = 0;
    }

    private void entities(Scene scene, SoftRenderer renderer, Scene.View view, int frame) {
        this.batcher.clear();
        for (int i = 0; i < scene.entityCount; i++) {
            float x = scene.entityX[i];
            float y = scene.entityY[i];
            float z = scene.entityZ[i];
            float halfWidth = scene.entityHalfWidth[i];
            float height = scene.entityHeight[i];
            if (!this.frustum.intersectsAabb(x - halfWidth, y, z - halfWidth,
                    x + halfWidth, y + height, z + halfWidth)) {
                continue;
            }
            boolean projected = renderer.projectBox(x - halfWidth, y, z - halfWidth,
                    x + halfWidth, y + height, z + halfWidth, this.box);
            if (!projected) {
                continue;
            }
            int x0 = outwardLo(this.box[0]);
            int y0 = outwardLo(this.box[1]);
            int x1 = outwardHi(this.box[2], this.occlusionWidth - 1);
            int y1 = outwardHi(this.box[3], this.occlusionHeight - 1);
            boolean occluded = x0 <= x1 && y0 <= y1
                    && this.occlusion.test(x0, y0, x1, y1, this.box[4]);
            if (occluded) {
                this.entitiesOccluded++;
                continue;
            }

            float viewDepth = this.box[4];
            float screenPixels = height * renderer.pixelsPerUnitAt(viewDepth);
            EntityLodSelector.Tier tier = this.lod.select(screenPixels, this.coarseLastFrame[i]);
            if (tier == EntityLodSelector.Tier.CULL) {
                this.coarseLastFrame[i] = true;
                continue;
            }
            boolean coarse = tier == EntityLodSelector.Tier.COARSE;
            this.coarseLastFrame[i] = coarse;
            if (coarse) {
                this.entitiesCoarse++;
            }

            this.batcher.add(new EntityInstance(scene.entityModel[i], x, y, z,
                    scene.entityYaw[i], 15, halfWidth, height));
            Mat.translateRotateY(x, y, z, scene.entityYaw[i], this.model);
            Mat.multiply(this.viewMatrix, this.model, this.modelView);
            renderer.drawMeshWith(coarse ? this.entityCoarse[scene.entityModel[i]]
                    : this.entityFull[scene.entityModel[i]], SoftRenderer.OPAQUE, this.textures,
                    this.modelView);
            this.entitiesDrawn++;

            // The shadow disc, which the baseline draws for everything and this draws for what can be seen.
            if (this.shadows.shouldDraw(0.0, viewDepth, false, false, view.pitch, 0.0)) {
                Mat.translate(x, y + 0.02f, z, this.model);
                Mat.multiply(this.viewMatrix, this.model, this.modelView);
                renderer.drawMeshWith(this.shadowQuad, SoftRenderer.BLEND, this.textures,
                        this.modelView);
                this.shadowsDrawn++;
            }
        }
        // The batch set is what a real backend would turn into one instanced draw per batch. Here it is
        // counted rather than felt, because a software rasteriser has no draw call to save.
        this.entityDrawCalls += this.batcher.build().drawCallCount();
    }

    private void blockEntities(Scene scene, SoftRenderer renderer, int frame) {
        for (int i = 0; i < scene.blockEntityCount; i++) {
            float x = scene.blockEntityX[i] + 0.5f;
            float y = scene.blockEntityY[i];
            float z = scene.blockEntityZ[i] + 0.5f;
            if (!this.frustum.intersectsAabb(x - 0.5f, y, z - 0.5f, x + 0.5f, y + 1.0f, z + 0.5f)) {
                continue;
            }
            // One chest in the scene is open, so there is something to animate and the skipper has a real
            // decision to make rather than a constant to return.
            double target = i == 0 ? 0.7 : 0.0;
            double previous = this.lidAngle;
            this.lidAngle = target;
            if (!this.animationSkipper.shouldAnimate(this.lidAngle, previous)) {
                this.blockEntitiesSkipped++;
                continue;
            }
            this.blockEntitiesAnimated++;
            Mat.translateRotateY(x, y, z, 0.0f, this.model);
            Mat.multiply(this.viewMatrix, this.model, this.modelView);
            renderer.drawMeshWith(this.chestBaked ? this.chestLid : this.chestBox,
                    SoftRenderer.OPAQUE, this.textures, this.modelView);
        }
    }

    private void particles(Scene scene, SoftRenderer renderer, Scene.View view, int frame) {
        // Built into one mesh and submitted once, the way the rain is. Each particle used to be its own
        // drawMeshWith: its own billboard matrix, its own model-view multiply, and on a driver its own
        // program, uniform, vertex array and draw call. Twenty-four of them cost 64 microseconds a frame
        // against a baseline that pays nothing at all for culling them, which is most of the gap between
        // the two pipelines on the driver path. Baking the billboard into the corners costs three
        // multiplies a corner and removes all of it.
        float size = 0.25f;
        float rsx = view.rightX * size;
        float rsy = view.rightY * size;
        float rsz = view.rightZ * size;
        float usx = view.upX * size;
        float usy = view.upY * size;
        float usz = view.upZ * size;
        MeshBuilder batch = new MeshBuilder();
        boolean any = false;
        for (int i = 0; i < scene.particleCount; i++) {
            float jitter = FastRandom.nextSymmetricFloat(FastRandom.Usage.PARTICLES, 0.4f);
            float x = scene.particleX[i] + jitter;
            float y = scene.particleY[i] + FastRandom.nextFloat(FastRandom.Usage.PARTICLES) * 2.0f;
            float z = scene.particleZ[i] + jitter;
            if (!this.frustum.intersectsAabb(x - size, y - size, z - size,
                    x + size, y + size, z + size)) {
                this.particlesCulled++;
                continue;
            }
            int cell = ((int) (scene.particleU[i] * 4.0f) + (int) (scene.particleV[i] * 4.0f) * 4) & 15;
            Mesh quad = this.particleQuads[cell];
            float[] v = quad.verts;
            this.particleBatch.add("particle");
            for (int t = 0; t < quad.triangleCount; t++) {
                int b = t * 15;
                batch.triangle(
                        x + v[b] * rsx + v[b + 1] * usx,
                        y + v[b] * rsy + v[b + 1] * usy,
                        z + v[b] * rsz + v[b + 1] * usz, v[b + 3], v[b + 4],
                        x + v[b + 5] * rsx + v[b + 6] * usx,
                        y + v[b + 5] * rsy + v[b + 6] * usy,
                        z + v[b + 5] * rsz + v[b + 6] * usz, v[b + 8], v[b + 9],
                        x + v[b + 10] * rsx + v[b + 11] * usx,
                        y + v[b + 10] * rsy + v[b + 11] * usy,
                        z + v[b + 10] * rsz + v[b + 11] * usz, v[b + 13], v[b + 14],
                        quad.triTex[t], quad.triShade[t]);
            }
            any = true;
            this.particlesDrawn++;
            this.particleTriangles += quad.triangleCount;
        }
        if (any) {
            this.particleBatch.endFrame();
            renderer.drawMesh(batch.build(), SoftRenderer.BLEND, this.textures);
        }
    }

    private void clouds(SoftRenderer renderer, int frame) {
        // Vanilla rebuilds the cloud mesh when the shape coordinate changes; asking first is the whole
        // technique, and the answer is no for all but one frame in sixty.
        if (this.cloudCache.needsRebuild(CloudMeshCache.shapeAt(frame))) {
            this.cloudMesh = Passes.clouds(this.scene, this.assets);
        }
        if (!this.frustum.intersectsAabb(this.cloudMesh.minX, this.cloudMesh.minY, this.cloudMesh.minZ,
                this.cloudMesh.maxX, this.cloudMesh.maxY, this.cloudMesh.maxZ)) {
            return;
        }
        this.cloudTriangles += this.cloudMesh.triangleCount;
        renderer.drawMesh(this.cloudMesh, SoftRenderer.BLEND, this.textures);
    }

    private void rain(Scene scene, SoftRenderer renderer, Scene.View view, int frame) {
        if (this.weatherCache.needsRebuild(scene.raining ? 1 : 0)) {
            this.rainRebuilds++;
        }
        // The fall is an offset rather than a rebuild, and every drop is tested on its own so the ones
        // behind the camera can be dropped. Vanilla bakes them into one buffer and cannot.
        float fall = (float) WeatherMeshCache.fallOffset(frame, 0.6) % 16.0f;
        // Culled one at a time and then submitted as one mesh, because the two halves of that sentence
        // pull against each other. Per-drop culling needs the drops to be separate objects, and separate
        // objects are one draw call each -- so without something to put them back together, dropping 492
        // of 600 drops would trade 492 quads for 108 draw calls, and on a driver that is the worse trade.
        // ParticleDrawBatcher is that something: same texture, order preserved, one submission.
        MeshBuilder batch = new MeshBuilder();
        float[] drop = this.rainDrop.verts;
        int[] dropTexture = this.rainDrop.triTex;
        float[] dropShade = this.rainDrop.triShade;
        int dropTriangles = this.rainDrop.triangleCount;
        for (int i = 0; i < Passes.RAIN_QUADS; i++) {
            float x = view.x + this.rainOffsets[i * 3];
            float y = view.y - 8.0f + this.rainOffsets[i * 3 + 1] - fall;
            float z = view.z + this.rainOffsets[i * 3 + 2];
            float halfWidth = Passes.RAIN_HALF_WIDTH;
            if (!this.frustum.intersectsAabb(x - halfWidth, y, z - halfWidth,
                    x + halfWidth, y + Passes.RAIN_HEIGHT, z + halfWidth)) {
                this.rainCulled++;
                continue;
            }
            this.rainBatch.add("rain");
            for (int t = 0; t < dropTriangles; t++) {
                int b = t * 15;
                batch.triangle(
                        x + drop[b], y + drop[b + 1], z + drop[b + 2], drop[b + 3], drop[b + 4],
                        x + drop[b + 5], y + drop[b + 6], z + drop[b + 7], drop[b + 8], drop[b + 9],
                        x + drop[b + 10], y + drop[b + 11], z + drop[b + 12], drop[b + 13], drop[b + 14],
                        dropTexture[t], dropShade[t]);
            }
            this.rainDrawn++;
        }
        this.rainBatch.endFrame();
        // The batcher counts submissions for the whole run, so this is read rather than accumulated.
        this.rainBatches = this.rainBatch.calls();
        renderer.drawMesh(batch.build(), SoftRenderer.BLEND, this.textures);
    }

    private void animateTextures(Assets assets, int frame) {
        // Derived from world time rather than counted, so skipping an upload cannot put two lakes out of
        // step with each other.
        if (this.textureScheduler.shouldUpdate(true, frame, 8, 16.0, this.lastWaterFrame)) {
            this.lastWaterFrame = AnimatedTextureScheduler.frameAt(frame, 8);
            blend(assets.textures[assets.waterBase + (int) (this.lastWaterFrame % Assets.LIQUID_FRAMES)],
                    assets.textures[assets.waterBase
                            + (int) ((this.lastWaterFrame + 1) % Assets.LIQUID_FRAMES)],
                    this.animatedWater);
            this.textureUploads++;
        }
        if (this.textureScheduler.shouldUpdate(true, frame, 8, 16.0, this.lastLavaFrame)) {
            this.lastLavaFrame = AnimatedTextureScheduler.frameAt(frame, 8);
            blend(assets.textures[assets.lavaBase + (int) (this.lastLavaFrame % Assets.LIQUID_FRAMES)],
                    assets.textures[assets.lavaBase
                            + (int) ((this.lastLavaFrame + 1) % Assets.LIQUID_FRAMES)],
                    this.animatedLava);
            this.textureUploads++;
        }
    }

    private static void blend(Texture from, Texture to, int[] out) {
        for (int i = 0; i < out.length; i++) {
            int a = from.argb[i];
            int b = to.argb[i];
            int r = (((a >> 16) & 0xFF) + ((b >> 16) & 0xFF)) / 2;
            int g = (((a >> 8) & 0xFF) + ((b >> 8) & 0xFF)) / 2;
            int blue = ((a & 0xFF) + (b & 0xFF)) / 2;
            out[i] = 0xFF000000 | (r << 16) | (g << 8) | blue;
        }
    }

    private void gui(SoftRenderer renderer, Assets assets, int frame) {
        // Rebuilt when the player acts or the debug overlay opens, which for a stationary camera is never.
        // What is rebuilt is a vertex buffer, not a list of draw calls: the batching happens here, once,
        // and the frame below submits the result rather than deciding anything.
        if (this.hudQuads == null || this.hudScheduler.shouldUpdate(frame % 240 == 0, false)) {
            this.hud.clear();
            this.hudElements += Passes.hudElements(renderer.width, renderer.height,
                    assets.guiAtlas, this.hud);
            List<GuiBatcher.Batch> batches = this.guiBatcher.batch(this.hud);
            this.guiBatches += batches.size();
            int count = this.hud.size();
            this.hudQuads = new float[count * 8];
            this.hudQuadCount = count;
            for (int i = 0; i < count; i++) {
                GuiBatcher.Element element = this.hud.get(i);
                Passes.hudUv(i, this.uv);
                int b = i * 8;
                this.hudQuads[b] = element.x();
                this.hudQuads[b + 1] = element.y();
                this.hudQuads[b + 2] = element.x() + element.width();
                this.hudQuads[b + 3] = element.y() + element.height();
                this.hudQuads[b + 4] = this.uv[0];
                this.hudQuads[b + 5] = this.uv[1];
                this.hudQuads[b + 6] = this.uv[2];
                this.hudQuads[b + 7] = this.uv[3];
            }
            this.hudRebuilds++;
        }
        renderer.drawScreenBatch(this.hudQuads, this.hudQuadCount,
                assets.textures[assets.guiAtlas], 1.0f);
    }

    /** Which way the camera looks horizontally, in the constants the face mask uses. */
    private static int facing(Scene.View view) {
        if (Math.abs(view.fwdZ) >= Math.abs(view.fwdX)) {
            return view.fwdZ > 0.0f ? MeshletFaceMaskCuller.SOUTH : MeshletFaceMaskCuller.NORTH;
        }
        return view.fwdX > 0.0f ? MeshletFaceMaskCuller.EAST : MeshletFaceMaskCuller.WEST;
    }

    /**
     * Whether the camera looks up or down, in Minecraft's pitch convention where positive is down.
     *
     * <p>The convention matters: {@code MeshletFaceMaskCuller.visibleMask} adds UP to the visible set when
     * this is positive, which is right only if positive means the camera is looking at the ground and so
     * at the top faces of what is under it. Passing a sign from the other convention would cull the faces
     * the player is actually looking at.
     */
    private static int vertical(Scene.View view) {
        if (view.pitch > 0.5f) {
            return 1;
        }
        if (view.pitch < -0.5f) {
            return -1;
        }
        return 0;
    }
}
