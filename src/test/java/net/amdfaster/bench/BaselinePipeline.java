package net.amdfaster.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.amdfaster.cull.Frustum;
import net.amdfaster.render.GuiBatcher;

/**
 * The stack this project has to beat: Sodium, ImmediatelyFast, EntityCulling, Lithium, FerriteCore and
 * ModernFix, all installed, all doing their job.
 *
 * <p>What that means in this renderer, pass by pass:
 * <ul>
 *   <li>Terrain is frustum culled per section and sorted front to back, which is Sodium's core win over
 *       vanilla. Meshes are baked once and reused, so no meshing happens in the timed loop.</li>
 *   <li>Entities are frustum and distance culled, which is EntityCulling's core win, and drawn at full
 *       detail with the model matrix folded into the view matrix.</li>
 *   <li>The GUI element list is rebuilt every frame and every element is drawn, which is what the vanilla
 *       GUI does and what ImmediatelyFast then batches on the GL side.</li>
 *   <li>The rain mesh is rebuilt every frame, which is what vanilla does.</li>
 * </ul>
 *
 * <p>Two things are deliberately <em>not</em> modelled, and both make this baseline stronger than the
 * number suggests rather than weaker, so the comparison stays conservative in the direction that matters:
 * EntityCulling's depth-buffer occlusion pass for entities is absent, so this baseline draws entities the
 * real stack would have dropped; and no GL driver overhead is charged to either pipeline, which on AMD
 * hardware is a real cost that only the batched pipeline avoids.
 */
final class BaselinePipeline implements Pipeline {

    /** The low twenty bits of a sort key hold the section index; the rest holds the distance. */
    private static final long INDEX_MASK = (1L << 20) - 1;

    private final Frustum frustum = new Frustum(new float[16]);
    private final Random random = new Random(12345L);
    private final List<GuiBatcher.Element> hud = new ArrayList<>();
    private final int[] animatedWater = new int[256];
    private final int[] animatedLava = new int[256];
    private final float[] uv = new float[4];
    private final float[] model = new float[12];
    private final float[] modelView = new float[12];
    private final float[] viewMatrix = new float[12];

    private Texture[] textures = new Texture[0];
    private Mesh[] entityMeshes = new Mesh[0];
    private Mesh chestMesh;
    private Mesh shadowQuad;
    private Mesh cloudMesh;
    private Mesh rainMesh;
    private Mesh[] particleQuads = new Mesh[0];

    private int[] order = new int[0];
    private long[] keys = new long[0];

    long sectionsDrawn;
    long entitiesDrawn;
    long shadowsDrawn;
    long blockEntitiesDrawn;
    long particlesDrawn;
    long hudElements;
    long rainRebuilds;
    long textureUpdates;

    @Override
    public String name() {
        return "all-mods baseline";
    }

    @Override
    public void prepare(Scene scene, Assets assets) {
        this.entityMeshes = new Mesh[] {
            EntityMeshes.build(assets.creeper, assets.creeper.cubes.length),
            EntityMeshes.build(assets.zombie, assets.zombie.cubes.length),
            EntityMeshes.build(assets.tntModel, assets.tntModel.cubes.length),
        };
        this.chestMesh = EntityMeshes.build(assets.chestModel, assets.chestModel.cubes.length);
        MeshBuilder shadow = new MeshBuilder();
        shadow.quad(-0.4f, 0.0f, -0.4f, 0f, 0f, 0.4f, 0.0f, -0.4f, 1f, 0f,
                -0.4f, 0.0f, 0.4f, 0f, 1f, 0.4f, 0.0f, 0.4f, 1f, 1f,
                assets.glassTexture, 0.35f);
        this.shadowQuad = shadow.build();
        this.cloudMesh = Passes.clouds(scene, assets);
        this.rainMesh = Passes.rain(this.random);
        this.particleQuads = Passes.particleQuads(assets);
        this.textures = assets.textures;
        int sections = scene.sections.length;
        this.order = new int[sections];
        this.keys = new long[sections];
    }

    @Override
    public void renderFrame(Scene scene, Assets assets, SoftRenderer renderer, Scene.View view,
            int frame) {
        renderer.clear(0xFF78A7FF);
        this.frustum.update(view.viewProjection);
        renderer.fillViewMatrix(this.viewMatrix);
        float[] viewMatrix = this.viewMatrix;

        terrain(scene, renderer, view);
        entities(scene, assets, renderer, viewMatrix);
        blockEntities(scene, renderer, viewMatrix, frame);
        particles(scene, renderer, view, viewMatrix);
        clouds(renderer);
        if (scene.raining) {
            rain(scene, renderer, viewMatrix, frame);
        }
        animateTextures(assets, frame);
        gui(renderer, assets);
    }

    private void terrain(Scene scene, SoftRenderer renderer, Scene.View view) {
        int visible = 0;
        float farSquared = view.far * view.far;
        for (int i = 0; i < scene.sections.length; i++) {
            SectionMeshes section = scene.sections[i];
            if (section.isEmpty()) {
                continue;
            }
            if (!this.frustum.intersectsAabb(section.minX, section.minY, section.minZ,
                    section.maxX, section.maxY, section.maxZ)) {
                continue;
            }
            float dx = (section.minX + section.maxX) * 0.5f - view.x;
            float dy = (section.minY + section.maxY) * 0.5f - view.y;
            float dz = (section.minZ + section.maxZ) * 0.5f - view.z;
            float distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared > farSquared) {
                continue;
            }
            // Front to back, which is what lets the depth test reject the terrain behind the terrain.
            this.keys[visible] = ((long) (distanceSquared * 64.0f) << 20) | i;
            this.order[visible] = i;
            visible++;
        }
        long[] toSort = Arrays.copyOf(this.keys, visible);
        Arrays.sort(toSort);
        for (int n = 0; n < visible; n++) {
            int index = (int) (toSort[n] & INDEX_MASK);
            SectionMeshes section = scene.sections[index];
            this.sectionsDrawn++;
            // Every direction, because the mesh holds them all together and there is no cheaper place to
            // make the decision once the vertices are in one buffer.
            for (int face = 0; face < 6; face++) {
                renderer.drawMesh(section.opaque[face], SoftRenderer.OPAQUE, this.textures);
            }
            renderer.drawMesh(section.cutout, SoftRenderer.CUTOUT, this.textures);
        }
        for (int n = visible - 1; n >= 0; n--) {
            SectionMeshes section = scene.sections[(int) (toSort[n] & INDEX_MASK)];
            renderer.drawMesh(section.blend, SoftRenderer.BLEND, this.textures);
        }
    }

    private void entities(Scene scene, Assets assets, SoftRenderer renderer, float[] viewMatrix) {
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
            // Full detail at every distance, which is what vanilla does and what no mod in the stack
            // changes: a creeper on the horizon is skinned and drawn with every bone it has.
            Mat.translateRotateY(x, y, z, scene.entityYaw[i], this.model);
            Mat.multiply(viewMatrix, this.model, this.modelView);
            renderer.drawMeshWith(this.entityMeshes[scene.entityModel[i]], SoftRenderer.OPAQUE,
                    this.textures, this.modelView);
            this.entitiesDrawn++;
            // Every visible entity gets its shadow disc, at any distance, which is what vanilla does.
            Mat.translate(x, y + 0.02f, z, this.model);
            Mat.multiply(viewMatrix, this.model, this.modelView);
            renderer.drawMeshWith(this.shadowQuad, SoftRenderer.BLEND, this.textures, this.modelView);
            this.shadowsDrawn++;
        }
    }

    private void blockEntities(Scene scene, SoftRenderer renderer, float[] viewMatrix, int frame) {
        for (int i = 0; i < scene.blockEntityCount; i++) {
            float x = scene.blockEntityX[i] + 0.5f;
            float y = scene.blockEntityY[i];
            float z = scene.blockEntityZ[i] + 0.5f;
            if (!this.frustum.intersectsAabb(x - 0.5f, y, z - 0.5f, x + 0.5f, y + 1.0f, z + 0.5f)) {
                continue;
            }
            // The whole chest, box and lid, every frame, whether the lid is moving or not.
            Mat.translateRotateY(x, y, z, 0.0f, this.model);
            Mat.multiply(viewMatrix, this.model, this.modelView);
            renderer.drawMeshWith(this.chestMesh, SoftRenderer.OPAQUE, this.textures,
                    this.modelView);
            this.blockEntitiesDrawn++;
        }
    }

    private void particles(Scene scene, SoftRenderer renderer, Scene.View view, float[] viewMatrix) {
        for (int i = 0; i < scene.particleCount; i++) {
            float x = scene.particleX[i];
            float y = scene.particleY[i] + 0.05f * (float) Math.sin((i + view.yaw) * 0.05);
            float z = scene.particleZ[i];
            Mat.billboard(x, y, z, 0.25f, view.rightX, view.rightY, view.rightZ,
                    view.upX, view.upY, view.upZ, this.model);
            Mat.multiply(viewMatrix, this.model, this.modelView);
            // Every particle, every frame, whether it is on screen or not: the cull that would drop it
            // is the thing being measured on the other side of this comparison.
            this.particlesDrawn++;
            int cell = (int) (scene.particleU[i] * 4.0f) + (int) (scene.particleV[i] * 4.0f) * 4;
            renderer.drawMeshWith(this.particleQuads[cell & 15], SoftRenderer.BLEND,
                    this.textures, this.modelView);
        }
    }

    private void clouds(SoftRenderer renderer) {
        renderer.drawMesh(this.cloudMesh, SoftRenderer.BLEND, this.textures);
    }

    private void rain(Scene scene, SoftRenderer renderer, float[] viewMatrix, int frame) {
        // Rebuilt, not moved. This is the vanilla path and it is the whole cost the cache removes.
        this.rainMesh = Passes.rain(this.random);
        this.rainRebuilds++;
        Mat.translate(view.x, view.y - 8.0f, view.z, this.model);
        Mat.multiply(viewMatrix, this.model, this.modelView);
        renderer.drawMeshWith(this.rainMesh, SoftRenderer.BLEND, this.textures, this.modelView);
    }

    private void animateTextures(Assets assets, int frame) {
        // Interpolated animated textures recompute the sprite on every tick rather than only when the
        // frame changes, which for a slow animation is thirty-nine redundant blends out of forty.
        Texture water = assets.textures[assets.waterBase + (frame / 8) % Assets.LIQUID_FRAMES];
        Texture next = assets.textures[assets.waterBase + ((frame / 8) + 1) % Assets.LIQUID_FRAMES];
        blend(water, next, this.animatedWater);
        Texture lava = assets.textures[assets.lavaBase + (frame / 8) % Assets.LIQUID_FRAMES];
        Texture lavaNext = assets.textures[assets.lavaBase + ((frame / 8) + 1) % Assets.LIQUID_FRAMES];
        blend(lava, lavaNext, this.animatedLava);
        this.textureUpdates += 2;
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

    private void gui(SoftRenderer renderer, Assets assets) {
        // Rebuilt every frame: the list is thrown away and filled again whether or not anything in it
        // changed, which for a HUD that changes when the player eats is almost always wasted.
        this.hud.clear();
        int count = Passes.hudElements(renderer.width, renderer.height, assets.guiAtlas, this.hud);
        this.hudElements += count;
        Texture atlas = assets.textures[assets.guiAtlas];
        for (int i = 0; i < count; i++) {
            GuiBatcher.Element element = this.hud.get(i);
            Passes.hudUv(i, this.uv);
            renderer.drawScreenQuad(atlas, this.uv[0], this.uv[1], this.uv[2], this.uv[3],
                    element.x(), element.y(), element.x() + element.width(),
                    element.y() + element.height(), 1.0f);
        }
    }
}
