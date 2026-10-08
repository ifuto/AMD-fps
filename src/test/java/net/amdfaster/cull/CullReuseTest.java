package net.amdfaster.cull;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reuse is only free if the invalidation list is complete. Every test here is one way the list could
 * be wrong, and each of them fails as a world that stops updating rather than as a crash -- which is
 * why they are written out individually rather than rolled into one "it reuses when nothing changed".
 */
class CullReuseTest {

    private static CullReuse settled() {
        CullReuse reuse = new CullReuse();
        reuse.updateCamera(0f, 64f, 0f, 0f, 0f, 70f, 1920, 1080);
        reuse.terrainCullRan();
        reuse.entityCullRan();
        return reuse;
    }

    @Test
    void theFirstFrameCannotReuseAnything() {
        CullReuse reuse = new CullReuse();
        assertFalse(reuse.hasCamera());
        reuse.updateCamera(0f, 64f, 0f, 0f, 0f, 70f, 1920, 1080);
        assertTrue(reuse.hasCamera());
        assertFalse(reuse.canReuseTerrainCull(), "nothing has been culled yet to reuse");
    }

    @Test
    void anUnchangedCameraWithAnUnchangedWorldReuses() {
        CullReuse reuse = settled();
        assertTrue(reuse.updateCamera(0f, 64f, 0f, 0f, 0f, 70f, 1920, 1080));
        assertTrue(reuse.canReuseTerrainCull());
        assertTrue(reuse.canReuseEntityCull());
    }

    @Test
    void aCameraThatMovesByOneUlpInvalidates() {
        // Not a threshold. A threshold would be an approximation, and the whole claim here is that
        // reuse is exact. If the camera moved at all, the answer may be different, so recompute.
        CullReuse reuse = settled();
        float nudged = Math.nextAfter(0f, 1f);
        assertFalse(reuse.updateCamera(nudged, 64f, 0f, 0f, 0f, 70f, 1920, 1080));
        assertFalse(reuse.canReuseTerrainCull());
    }

    @Test
    void negativeZeroIsNotTheSameCameraAsPositiveZero() {
        // The case a plain == comparison gets wrong: 0.0f == -0.0f is true in Java, so a camera that
        // crosses the origin through negative zero would be treated as unmoved. The bit patterns
        // differ, and comparing bits is what catches it.
        assertEquals(0.0f, -0.0f, "the language calls these equal, which is the trap");
        assertTrue(Float.floatToRawIntBits(0.0f) != Float.floatToRawIntBits(-0.0f));

        CullReuse reuse = settled();
        assertFalse(reuse.updateCamera(-0.0f, 64f, 0f, 0f, 0f, 70f, 1920, 1080));
        assertFalse(reuse.canReuseTerrainCull());
    }

    @Test
    void aZoomChangesTheFrustumWithoutMovingTheCamera() {
        CullReuse reuse = settled();
        assertFalse(reuse.updateCamera(0f, 64f, 0f, 0f, 0f, 30f, 1920, 1080),
                "a spyglass narrows the field of view from the same spot");
        assertFalse(reuse.canReuseTerrainCull());
    }

    @Test
    void aResizeChangesTheAspectAndThePyramid() {
        CullReuse reuse = settled();
        assertFalse(reuse.updateCamera(0f, 64f, 0f, 0f, 0f, 70f, 1280, 720));
        assertFalse(reuse.canReuseTerrainCull());
    }

    @Test
    void aGeometryRebuildInvalidatesButALightRebuildDoesNot() {
        // Light lives in its own vertex stream. A torch being placed rewrites it and leaves positions,
        // texture coordinates and indices alone, so nothing about visibility changes. Relighting is
        // most of the rebuild traffic, so invalidating on it would throw the reuse away almost every
        // frame and buy nothing.
        CullReuse lightOnly = settled();
        lightOnly.noteTerrainLightChange();
        assertTrue(lightOnly.updateCamera(0f, 64f, 0f, 0f, 0f, 70f, 1920, 1080));
        assertTrue(lightOnly.canReuseTerrainCull(), "a light rewrite cannot move a meshlet");

        CullReuse geometry = settled();
        geometry.invalidateTerrainGeometry();
        assertTrue(geometry.updateCamera(0f, 64f, 0f, 0f, 0f, 70f, 1920, 1080));
        assertFalse(geometry.canReuseTerrainCull(), "a remesh can create or remove meshlets");
    }

    @Test
    void aGeometryRebuildDoesNotStopReuseForever() {
        // It costs one frame, not every frame after. If this were wrong, a single placed block would
        // disable the optimisation for the rest of the session.
        CullReuse reuse = settled();
        reuse.invalidateTerrainGeometry();
        reuse.updateCamera(0f, 64f, 0f, 0f, 0f, 70f, 1920, 1080);
        assertFalse(reuse.canReuseTerrainCull());
        reuse.terrainCullRan();

        assertTrue(reuse.updateCamera(0f, 64f, 0f, 0f, 0f, 70f, 1920, 1080));
        assertTrue(reuse.canReuseTerrainCull(), "back to reusing the very next frame");
    }

    @Test
    void entitiesAndTerrainHaveIndependentEpochs() {
        // Mobs move every frame. If that invalidated the terrain cull too, the terrain reuse would
        // never engage anywhere there is a mob, which is most of the world.
        CullReuse reuse = settled();
        reuse.invalidateEntitySet();
        assertTrue(reuse.updateCamera(0f, 64f, 0f, 0f, 0f, 70f, 1920, 1080));
        assertFalse(reuse.canReuseEntityCull(), "the entity set changed");
        assertTrue(reuse.canReuseTerrainCull(), "and the terrain did not");
    }

    @Test
    void forgettingToReportTheCullMeansTheReuseNeverEngages() {
        // The failure this guards is silent: everything renders correctly, nothing gets faster, and
        // there is no error to see.
        CullReuse reuse = new CullReuse();
        reuse.updateCamera(0f, 64f, 0f, 0f, 0f, 70f, 1920, 1080);
        reuse.updateCamera(0f, 64f, 0f, 0f, 0f, 70f, 1920, 1080);
        assertFalse(reuse.canReuseTerrainCull(),
                "the camera is unchanged but no cull was ever recorded to reuse");
        assertEquals(0L, reuse.terrainCulledFrames());

        reuse.terrainCullRan();
        reuse.updateCamera(0f, 64f, 0f, 0f, 0f, 70f, 1920, 1080);
        assertTrue(reuse.canReuseTerrainCull());
    }

    @Test
    void askingTwiceInOneFrameGivesTheSameAnswer() {
        CullReuse reuse = settled();
        reuse.updateCamera(0f, 64f, 0f, 0f, 0f, 70f, 1920, 1080);
        assertTrue(reuse.canReuseTerrainCull());
        assertTrue(reuse.canReuseTerrainCull(), "the decision is not consumed by asking");
    }

    @Test
    void theCountersSeparateWorkDoneFromWorkAvoided() {
        CullReuse reuse = settled();
        for (int frame = 0; frame < 10; frame++) {
            reuse.updateCamera(0f, 64f, 0f, 0f, 0f, 70f, 1920, 1080);
            if (reuse.canReuseTerrainCull()) {
                reuse.terrainCullReused();
            } else {
                reuse.terrainCullRan();
            }
        }
        assertEquals(1L, reuse.terrainCulledFrames(), "the one real cull in settled()");
        assertEquals(10L, reuse.terrainReusedFrames(), "ten frames standing still");
    }

    @Test
    void resetForcesARecullEvenWithAnUnchangedCamera() {
        CullReuse reuse = settled();
        reuse.reset();
        assertFalse(reuse.hasCamera());
        reuse.updateCamera(0f, 64f, 0f, 0f, 0f, 70f, 1920, 1080);
        assertFalse(reuse.canReuseTerrainCull());
        assertFalse(reuse.canReuseEntityCull());
    }

    @Test
    void aDegenerateViewportIsRejected() {
        // A zero-size viewport would produce a pyramid with no texels, and every projection onto it
        // would land outside. Better to fail here than to cull the world away.
        CullReuse reuse = new CullReuse();
        assertThrows(IllegalArgumentException.class,
                () -> reuse.updateCamera(0f, 0f, 0f, 0f, 0f, 70f, 0, 1080));
        assertThrows(IllegalArgumentException.class,
                () -> reuse.updateCamera(0f, 0f, 0f, 0f, 0f, 70f, 1920, -1));
    }
}
