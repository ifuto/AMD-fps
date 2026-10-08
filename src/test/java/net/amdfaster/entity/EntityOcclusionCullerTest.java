package net.amdfaster.entity;

import net.amdfaster.cull.CameraCutDetector;
import net.amdfaster.cull.PyramidGeometry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Occlusion culling has one failure mode that matters: keeping too much costs frames, culling too
 * much deletes things the player can see. Every test below is written so that the wrong answer is
 * visible rather than merely slower.
 *
 * <p>The scene is a 1024x1024 viewport with a 90 degree field of view, camera at the origin looking
 * down -Z, near plane 0.1, reversed-Z. A standing entity -- half width 0.5, height 1.8, feet at
 * y = 0 -- placed ten blocks away projects to pixels 485.05,414.99 through 538.95,512.00, which at
 * level 7 is the 2x2 texel span (3,3)-(4,4). Its nearest depth is 0.1 / 9.5 = 0.010526.
 */
class EntityOcclusionCullerTest {

    private static final int SCREEN = 1024;

    /** Column-major reversed-Z infinite projection: near maps to 1.0, the far plane to 0.0. */
    private static float[] projection() {
        float[] m = new float[16];
        m[0] = 1.0f;    // 1 / tan(45 deg), aspect 1
        m[5] = 1.0f;
        m[11] = -1.0f;  // clip w = -view z
        m[14] = 0.1f;   // clip z = near, so z/w = near / -view z
        return m;
    }

    private static EntityBuffer one(float x, float y, float z) {
        EntityBuffer buffer = new EntityBuffer(4);
        buffer.add(1, x, y, z, 0f, 0, 0.5f, 1.8f);
        return buffer;
    }

    /** A depth image with a wall at the given depth over the given level-0 pixel rectangle. */
    private static float[] wall(int x0, int y0, int x1, int y1, float depth) {
        float[] image = new float[SCREEN * SCREEN];
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                image[y * SCREEN + x] = depth;
            }
        }
        return image;
    }

    private static CameraCutDetector settled() {
        // Two frames of the same camera: past the recovery window, so occlusion is actually attempted.
        CameraCutDetector cuts = new CameraCutDetector();
        cuts.update(0f, 0f, 0f, 0f, 0f);
        cuts.update(0f, 0f, 0f, 0f, 0f);
        cuts.update(0f, 0f, 0f, 0f, 0f);
        return cuts;
    }

    @Test
    void anEntityWithNothingInFrontOfItSurvives() {
        EntityBuffer entities = one(0f, 0f, -10f);
        EntityCuller frustumPass = new EntityCuller();
        EntityOcclusionCuller culler = new EntityOcclusionCuller();
        PyramidGeometry pyramid = new PyramidGeometry(SCREEN, SCREEN);

        frustumPass.cull(entities, null, 0f, 0f, 0f);
        culler.cull(entities, frustumPass, projection(), pyramid,
                EntityOcclusionCuller.empty(), settled());

        assertEquals(1, culler.survivorCount());
        assertEquals(0, culler.culled());
        assertEquals(1, culler.tested(), "it was tested, not skipped");
        assertFalse(culler.wasSkipped());
    }

    @Test
    void anEntityFullyBehindAWallIsCulled() {
        // The wall covers the whole 2x2 texel span at depth 0.5, which in reversed-Z is far nearer
        // than the entity's 0.010526. This is the thousand-cows-behind-stone case.
        EntityBuffer entities = one(0f, 0f, -10f);
        EntityCuller frustumPass = new EntityCuller();
        EntityOcclusionCuller culler = new EntityOcclusionCuller();
        PyramidGeometry pyramid = new PyramidGeometry(SCREEN, SCREEN);
        float[] depth = wall(384, 384, 640, 640, 0.5f);

        frustumPass.cull(entities, null, 0f, 0f, 0f);
        culler.cull(entities, frustumPass, projection(), pyramid,
                EntityOcclusionCuller.fromLevelZero(depth, pyramid), settled());

        assertEquals(0, culler.survivorCount(), "hidden behind the wall");
        assertEquals(1, culler.culled());
    }

    @Test
    void anEntityVisibleThroughPartOfItsFootprintSurvives() {
        // The discriminator between a min and a max reduction, and the test the rest of this class
        // exists to protect. The wall covers only texel (3,3); texels (4,3), (3,4) and (4,4) are open
        // sky, and the entity's rectangle straddles the boundary at pixel 512 on both axes.
        //
        // A max-reduced pyramid reads 0.5 for the span and culls the entity, because one near pixel
        // anywhere in the region is enough to satisfy "nearest < pyramid". A min-reduced pyramid
        // reads 0.0 -- the open pixels -- and keeps it, which is correct: the entity is plainly
        // visible through the part of its footprint that has no wall in it.
        EntityBuffer entities = one(0f, 0f, -10f);
        EntityCuller frustumPass = new EntityCuller();
        EntityOcclusionCuller culler = new EntityOcclusionCuller();
        PyramidGeometry pyramid = new PyramidGeometry(SCREEN, SCREEN);
        float[] depth = wall(384, 384, 512, 512, 0.5f);

        frustumPass.cull(entities, null, 0f, 0f, 0f);
        culler.cull(entities, frustumPass, projection(), pyramid,
                EntityOcclusionCuller.fromLevelZero(depth, pyramid), settled());

        assertEquals(1, culler.survivorCount(),
                "a max reduction would cull this; the entity shows through the open pixels");
        assertEquals(0, culler.culled());
    }

    @Test
    void aWallFartherThanTheEntityDoesNotHideIt() {
        // Reversed-Z: 0.005 is farther than 0.010526, so the entity stands in front of the wall.
        // Comparing the depths the wrong way round passes every other test in this file.
        EntityBuffer entities = one(0f, 0f, -10f);
        EntityCuller frustumPass = new EntityCuller();
        EntityOcclusionCuller culler = new EntityOcclusionCuller();
        PyramidGeometry pyramid = new PyramidGeometry(SCREEN, SCREEN);
        float[] depth = wall(384, 384, 640, 640, 0.005f);

        frustumPass.cull(entities, null, 0f, 0f, 0f);
        culler.cull(entities, frustumPass, projection(), pyramid,
                EntityOcclusionCuller.fromLevelZero(depth, pyramid), settled());

        assertEquals(1, culler.survivorCount());
        assertEquals(0, culler.culled());
    }

    @Test
    void aCameraCutKeepsEverythingRatherThanTrustAStalePyramid() {
        // A teleport invalidates the previous frame's depth. Culling against it hides geometry that
        // is in plain view, and because culled geometry is never redrawn it never returns to the
        // pyramid -- so the mistake does not correct itself.
        // The entity has to sit near the camera it is tested from, or the distance pass drops it
        // first and the test passes for the wrong reason.
        EntityBuffer entities = one(0f, 64f, -410f);
        EntityCuller frustumPass = new EntityCuller();
        EntityOcclusionCuller culler = new EntityOcclusionCuller();
        PyramidGeometry pyramid = new PyramidGeometry(SCREEN, SCREEN);
        float[] depth = wall(384, 384, 640, 640, 0.5f);

        CameraCutDetector cuts = new CameraCutDetector();
        cuts.update(0f, 0f, 0f, 0f, 0f);
        cuts.update(0f, 0f, 0f, 0f, 0f);
        cuts.update(0f, 64f, -400f, 0f, 0f);   // teleport
        assertTrue(cuts.needsDepthPrepass());

        frustumPass.cull(entities, null, 0f, 64f, -400f);
        culler.cull(entities, frustumPass, projection(), pyramid,
                EntityOcclusionCuller.fromLevelZero(depth, pyramid), cuts);

        assertTrue(culler.wasSkipped());
        assertEquals(1, culler.survivorCount(), "nothing is culled on a cut frame");
        assertEquals(0, culler.tested(), "and nothing is tested against the stale pyramid");
    }

    @Test
    void anEntityBehindTheCameraIsKeptAndCounted() {
        // A corner behind the camera makes the projection nonsense. Culling on nonsense is how
        // geometry disappears while the player is looking straight at it.
        EntityBuffer entities = one(0f, 0f, 5f);
        EntityCuller frustumPass = new EntityCuller();
        EntityOcclusionCuller culler = new EntityOcclusionCuller();
        PyramidGeometry pyramid = new PyramidGeometry(SCREEN, SCREEN);

        frustumPass.cull(entities, null, 0f, 0f, 0f);
        culler.cull(entities, frustumPass, projection(), pyramid,
                EntityOcclusionCuller.flatWall(0.9f), settled());

        assertEquals(1, culler.survivorCount());
        assertEquals(1, culler.keptUnreliableProjection());
        assertEquals(0, culler.tested(), "an unprojectable box is never compared against depth");
    }

    @Test
    void everyCandidateIsAccountedFor() {
        // survivors + culled + keptOffScreen + keptUnreliableProjection must equal the input, or the
        // pass is losing entities somewhere the counters do not describe.
        EntityBuffer entities = new EntityBuffer(16);
        entities.add(1, 0f, 0f, -10f, 0f, 0, 0.5f, 1.8f);   // behind the wall
        entities.add(1, 30f, 0f, -10f, 0f, 0, 0.5f, 1.8f);  // clear of it
        entities.add(1, 0f, 0f, 5f, 0f, 0, 0.5f, 1.8f);     // behind the camera

        EntityCuller frustumPass = new EntityCuller();
        EntityOcclusionCuller culler = new EntityOcclusionCuller();
        PyramidGeometry pyramid = new PyramidGeometry(SCREEN, SCREEN);
        float[] depth = wall(384, 384, 640, 640, 0.5f);

        frustumPass.cull(entities, null, 0f, 0f, 0f);
        culler.cull(entities, frustumPass, projection(), pyramid,
                EntityOcclusionCuller.fromLevelZero(depth, pyramid), settled());

        int candidates = frustumPass.survivorCount();
        assertEquals(3, candidates, "all three are inside the 48 block distance limit");
        // Two identities, not one. An off-screen or unprojectable box is kept, so those counters sit
        // inside the survivor list rather than beside it.
        assertEquals(candidates, culler.survivorCount() + culler.culled(),
                "every candidate is either drawn or culled");
        assertEquals(candidates,
                culler.tested() + culler.keptOffScreen() + culler.keptUnreliableProjection(),
                "and every candidate reaches exactly one of the three outcomes");
        assertEquals(1, culler.culled(), "the one behind the wall");
        assertEquals(1, culler.keptOffScreen(), "the one thirty blocks to the side, off screen");
        assertEquals(1, culler.keptUnreliableProjection(), "the one behind the camera");
        assertEquals(1, culler.tested(), "only the one in front of the wall reached the pyramid");
        assertEquals(2, culler.survivorCount(), "off screen and behind the camera are both kept");
    }

    @Test
    void theWholePipelineRunsInOneCall() {
        EntityBuffer entities = new EntityBuffer(4);
        entities.add(1, 0f, 0f, -10f, 0f, 0, 0.5f, 1.8f);

        EntityCuller frustumPass = new EntityCuller();
        EntityOcclusionCuller culler = new EntityOcclusionCuller();
        PyramidGeometry pyramid = new PyramidGeometry(SCREEN, SCREEN);

        EntityOcclusionCuller.cullAll(entities, frustumPass, null, 0f, 0f, 0f, projection(),
                pyramid, EntityOcclusionCuller.empty(), settled(), culler);

        assertEquals(1, culler.survivorCount());
        assertEquals(1, culler.tested());
    }
}
