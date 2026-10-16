package net.amdfaster.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudMeshCacheTest {

    @Test
    void theSameShapeIsNotRebuilt() {
        // Fancy clouds are rebuilt from the noise every frame: four thousand quads, about 2 ms, 120 ms a
        // second -- for a shape that changes on the order of thousands of ticks.
        CloudMeshCache cache = new CloudMeshCache();

        assertTrue(cache.needsRebuild(7L), "the first frame builds it");
        for (int i = 0; i < 99; i++) {
            assertFalse(cache.needsRebuild(7L), "frame " + i);
        }

        assertEquals(1L, cache.rebuilt());
        assertEquals(99L, cache.reused());
        assertEquals(99.0, cache.reusedPercent(), 0.01);
    }

    @Test
    void aDifferentShapeRebuilds() {
        // Sampling the noise at the wrong position gives a different sky. Clouds are large,
        // high-contrast and constantly in view, so a wrong shape is immediately obvious and reads as
        // broken rendering rather than as an optimisation.
        CloudMeshCache cache = new CloudMeshCache();
        cache.needsRebuild(7L);

        assertTrue(cache.needsRebuild(8L));
        assertEquals(2L, cache.rebuilt());
    }

    @Test
    void theShapeChangesOnALongPeriod() {
        assertEquals(0L, CloudMeshCache.shapeAt(0L));
        assertEquals(0L, CloudMeshCache.shapeAt(CloudMeshCache.SHAPE_PERIOD - 1), "still the same shape");
        assertEquals(1L, CloudMeshCache.shapeAt(CloudMeshCache.SHAPE_PERIOD));
        assertEquals(4000, CloudMeshCache.SHAPE_PERIOD);
    }

    @Test
    void mostFramesShareOneShape() {
        CloudMeshCache cache = new CloudMeshCache();

        for (long tick = 0; tick < CloudMeshCache.SHAPE_PERIOD; tick++) {
            cache.needsRebuild(CloudMeshCache.shapeAt(tick));
        }

        assertEquals(1L, cache.rebuilt(), "one rebuild over four thousand frames");
        assertEquals(4000L, cache.frames());
    }

    @Test
    void theOffsetMovesEveryFrame() {
        // A cloud layer that stops drifting makes the sky look painted on, and it is the kind of
        // wrongness players notice without being able to say what it is.
        double a = CloudMeshCache.offsetAt(100L, 0.03);
        double b = CloudMeshCache.offsetAt(101L, 0.03);

        assertTrue(b > a, "the layer drifts");
        assertEquals(0.03, b - a, 1e-9);
    }

    @Test
    void theOffsetIsNotBakedIntoTheMesh() {
        // Moving is a uniform, so the mesh does not have to change for the clouds to drift. That is what
        // makes the cache safe: the thing that changes often is cheap to change.
        CloudMeshCache cache = new CloudMeshCache();
        cache.needsRebuild(0L);

        for (long tick = 1; tick < 100; tick++) {
            assertFalse(cache.needsRebuild(CloudMeshCache.shapeAt(tick)));
            assertTrue(CloudMeshCache.offsetAt(tick, 0.03) != CloudMeshCache.offsetAt(tick - 1, 0.03));
        }
    }

    @Test
    void negativeWorldTimeIsHandled() {
        assertEquals(-1L, CloudMeshCache.shapeAt(-1L));
        assertEquals(-1L, CloudMeshCache.shapeAt(-4000L));
        assertEquals(-2L, CloudMeshCache.shapeAt(-4001L));
        assertTrue(CloudMeshCache.offsetAt(-5L, 0.03) < 0.0);
    }

    @Test
    void invalidatingForcesTheNextRebuild() {
        // For a render distance or cloud setting change, where the cached mesh is for the wrong scene.
        CloudMeshCache cache = new CloudMeshCache();
        cache.needsRebuild(3L);
        assertFalse(cache.needsRebuild(3L));

        cache.invalidate();

        assertFalse(cache.hasCache());
        assertTrue(cache.needsRebuild(3L));
    }

    @Test
    void theSavingIsWhatTheFormulasSay() {
        CloudMeshCache cache = new CloudMeshCache();
        for (long tick = 0; tick < 4000; tick++) {
            cache.needsRebuild(CloudMeshCache.shapeAt(tick));
        }

        // One rebuild in four thousand frames, so the cost is essentially the offset uniform.
        assertEquals(2.001, CloudMeshCache.msPerFrameWithoutCache(), 1e-9);
        assertTrue(cache.msPerFrame() < 0.002, "was " + cache.msPerFrame());
        assertTrue(cache.savedPercent() > 99.9, "was " + cache.savedPercent());
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        CloudMeshCache cache = new CloudMeshCache();

        assertEquals(0.0, cache.reusedPercent());
        assertEquals(0.0, cache.msPerFrame());
        assertEquals(0L, cache.frames());
        assertFalse(cache.hasCache());
        assertEquals(4000, CloudMeshCache.CLOUD_QUADS);
    }
}
