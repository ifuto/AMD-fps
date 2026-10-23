package net.amdfaster.bench;

import java.util.List;
import java.util.Random;
import net.amdfaster.render.GuiBatcher;

/**
 * The geometry every pass draws, built the same way for both pipelines.
 *
 * <p>Shared on purpose. If the two pipelines built their own clouds or their own rain, a difference in
 * the result could come from the builder rather than from the technique, and the whole measurement would
 * be about nothing. Everything here is identical for both; the pipelines differ only in how much of it
 * they build per frame and how much of it they submit.
 */
final class Passes {

    /** Rain quads in the mesh Minecraft rebuilds around the camera. */
    static final int RAIN_QUADS = 600;

    /** The HUD: nine slots, ten hearts, ten pips, a crosshair, and the debug overlay. */
    static final int DEBUG_LINES = 12;
    static final int DEBUG_GLYPHS = 48;

    private Passes() {
    }

    /**
     * The cloud layer: quads of eight blocks at cloud height, present where a hash says so.
     *
     * <p>Both pipelines cache this. Vanilla caches it too, so there is no win to claim here and none is
     * claimed; it is in the benchmark because clouds cover a large part of the sky when the player looks
     * up, and a benchmark without them would overstate how much of the frame is terrain.
     */
    static Mesh clouds(Scene scene, Assets assets) {
        MeshBuilder builder = new MeshBuilder();
        int step = 8;
        float y = scene.cloudHeight();
        for (int z = 0; z < scene.world.sizeZ; z += step) {
            for (int x = 0; x < scene.world.sizeX; x += step) {
                // Roughly half the cells, in clumps rather than a checkerboard, which is what makes the
                // layer cost what a real one costs instead of a uniform veil.
                if (((x / step) * 7 + (z / step) * 13 + ((x / step) * (z / step)) % 5) % 3 == 0) {
                    continue;
                }
                float x0 = x;
                float x1 = x + step;
                float z0 = z;
                float z1 = z + step;
                builder.quad(x0, y, z0, 0f, 0f, x1, y, z0, 4f, 0f,
                        x0, y, z1, 0f, 4f, x1, y, z1, 4f, 4f,
                        assets.waterBase, 1.0f);
            }
        }
        return builder.build();
    }

    /**
     * The rain mesh, in camera-relative space.
     *
     * <p>Vanilla rebuilds this every frame, because the rain has to fall and the box has to follow the
     * player. That rebuild is the thing {@code WeatherMeshCache} removes: the mesh does not change, only
     * its offset does, and an offset is one number rather than six hundred quads.
     */
    static final float RAIN_HALF_WIDTH = 0.05f;
    static final float RAIN_HEIGHT = 0.9f;

    /**
     * The drop positions, in camera-relative space, three floats each.
     *
     * <p>Both pipelines are handed the same array from the same seed. That is the only way the rain can
     * be a measurement of culling rather than of one pipeline happening to get fewer drops.
     */
    static void rainOffsets(Random random, float[] out) {
        for (int i = 0; i < RAIN_QUADS; i++) {
            out[i * 3] = (random.nextFloat() - 0.5f) * 20.0f;
            out[i * 3 + 1] = random.nextFloat() * 16.0f;
            out[i * 3 + 2] = (random.nextFloat() - 0.5f) * 20.0f;
        }
    }

    /** One drop at the origin: two crossed quads, so it reads from any angle a billboard would not. */
    static Mesh rainDrop(int texture) {
        MeshBuilder builder = new MeshBuilder();
        float halfWidth = RAIN_HALF_WIDTH;
        float height = RAIN_HEIGHT;
        builder.quad(-halfWidth, 0f, 0f, 0f, 0f, halfWidth, 0f, 0f, 1f, 0f,
                -halfWidth, height, 0f, 0f, 1f, halfWidth, height, 0f, 1f, 1f, texture, 0.7f);
        builder.quad(0f, 0f, -halfWidth, 0f, 0f, 0f, 0f, halfWidth, 1f, 0f,
                0f, height, -halfWidth, 0f, 1f, 0f, height, halfWidth, 1f, 1f, texture, 0.7f);
        return builder.build();
    }

    /**
     * Every drop baked into one mesh, which is what vanilla does: it builds a box of rain around the
     * player and submits it as a single buffer.
     *
     * <p>That is also why vanilla cannot cull it. One buffer has one bounding box, the box straddles the
     * whole view, and the half of the drops behind the camera is inside it. Splitting the mesh is what
     * makes per-drop culling possible at all.
     */
    static Mesh rain(float[] offsets, int texture) {
        MeshBuilder builder = new MeshBuilder();
        float halfWidth = RAIN_HALF_WIDTH;
        float height = RAIN_HEIGHT;
        for (int i = 0; i < RAIN_QUADS; i++) {
            float x = offsets[i * 3];
            float y = offsets[i * 3 + 1];
            float z = offsets[i * 3 + 2];
            builder.quad(x - halfWidth, y, z, 0f, 0f, x + halfWidth, y, z, 1f, 0f,
                    x - halfWidth, y + height, z, 0f, 1f, x + halfWidth, y + height, z, 1f, 1f,
                    texture, 0.7f);
            builder.quad(x, y, z - halfWidth, 0f, 0f, x, y, z + halfWidth, 1f, 0f,
                    x, y + height, z - halfWidth, 0f, 1f, x, y + height, z + halfWidth, 1f, 1f,
                    texture, 0.7f);
        }
        return builder.build();
    }

    /**
     * One quad per cell of the particle atlas.
     *
     * <p>Sixteen meshes rather than one with a per-particle texture id, because that is what an atlas is
     * for: the cell is chosen when the particle spawns and never changes, so it belongs in the geometry
     * rather than in a per-frame lookup.
     */
    static Mesh[] particleQuads(Assets assets) {
        Mesh[] quads = new Mesh[16];
        for (int cell = 0; cell < 16; cell++) {
            float u0 = (cell % 4) * 0.25f;
            float v0 = (cell / 4) * 0.25f;
            float u1 = u0 + 0.25f;
            float v1 = v0 + 0.25f;
            MeshBuilder builder = new MeshBuilder();
            builder.quad(-0.5f, -0.5f, 0f, u0, v1, 0.5f, -0.5f, 0f, u1, v1,
                    -0.5f, 0.5f, 0f, u0, v0, 0.5f, 0.5f, 0f, u1, v0,
                    assets.particleAtlas, 1.0f);
            quads[cell] = builder.build();
        }
        return quads;
    }

    /**
     * Builds the HUD element list: every textured quad the overlay is made of.
     *
     * <p>This is the work {@code HudUpdateScheduler} skips when nothing has changed, and it is real work
     * rather than a notional cost: six hundred-odd records allocated and filled, sixty times a second,
     * for an overlay whose contents change when the player eats, not every frame.
     *
     * @return how many elements were added
     */
    static int hudElements(int width, int height, int atlas, List<GuiBatcher.Element> out) {
        int added = 0;
        int barLeft = width / 2 - 91;
        int barTop = height - 22;
        for (int i = 0; i < 9; i++) {
            out.add(new GuiBatcher.Element(atlas, 1, barLeft + i * 20, barTop, 20, 22, 0));
            added++;
        }
        for (int i = 0; i < 10; i++) {
            out.add(new GuiBatcher.Element(atlas, 1, barLeft + i * 9 + 1, barTop - 12, 9, 9, 0));
            added++;
            out.add(new GuiBatcher.Element(atlas, 1, barLeft + i * 9 + 1, barTop - 22, 9, 9, 0));
            added++;
        }
        out.add(new GuiBatcher.Element(atlas, 1, width / 2 - 8, height / 2 - 8, 16, 16, 0));
        added++;
        for (int line = 0; line < DEBUG_LINES; line++) {
            for (int glyph = 0; glyph < DEBUG_GLYPHS; glyph++) {
                out.add(new GuiBatcher.Element(atlas, 1, 2 + glyph * 6, 2 + line * 10, 6, 9, 0));
                added++;
            }
        }
        return added;
    }

    /** The atlas rectangle for the nth HUD element, so each one reads different texels. */
    static void hudUv(int index, float[] out) {
        int cell = index & 255;
        float u0 = (cell % 16) * 0.0625f;
        float v0 = (cell / 16) * 0.0625f;
        out[0] = u0;
        out[1] = v0;
        out[2] = u0 + 0.0625f;
        out[3] = v0 + 0.0625f;
    }
}
