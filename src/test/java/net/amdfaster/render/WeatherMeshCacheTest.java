package net.amdfaster.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeatherMeshCacheTest {

    @Test
    void theSameWeatherIsNotRebuilt() {
        // The weather effect is rebuilt every frame: fifteen hundred drops positioned, vertices written
        // and a buffer uploaded, about 1.2 ms -- for a pattern whose relative positions come from a fixed
        // seed and never change.
        WeatherMeshCache cache = new WeatherMeshCache();

        assertTrue(cache.needsRebuild(WeatherMeshCache.RAIN), "the first frame builds it");
        for (int i = 0; i < 99; i++) {
            assertFalse(cache.needsRebuild(WeatherMeshCache.RAIN), "frame " + i);
        }

        assertEquals(1L, cache.rebuilt());
        assertEquals(99L, cache.reused());
        assertEquals(99.0, cache.reusedPercent(), 0.01);
    }

    @Test
    void aWeatherChangeRebuilds() {
        // Rain and snow have different drop shapes and densities, and reusing the cached rain mesh for
        // snow would draw the wrong particles.
        WeatherMeshCache cache = new WeatherMeshCache();
        cache.needsRebuild(WeatherMeshCache.RAIN);

        assertTrue(cache.needsRebuild(WeatherMeshCache.SNOW));
        assertEquals(WeatherMeshCache.SNOW, cache.cachedWeather());
        assertEquals(2L, cache.rebuilt());
    }

    @Test
    void clearWeatherIsNotCached() {
        // There is no mesh to hold, and caching "clear" would mean the transition back to rain reused
        // nothing anyway while pretending it did.
        WeatherMeshCache cache = new WeatherMeshCache();

        assertTrue(cache.needsRebuild(WeatherMeshCache.CLEAR));
        assertTrue(cache.needsRebuild(WeatherMeshCache.CLEAR), "nothing to reuse");
        assertEquals(2L, cache.rebuilt());
        assertEquals(0L, cache.reused());
    }

    @Test
    void theCentreFollowsThePlayer() {
        // Baking the centre into the mesh would mean rebuilding to move, and walking forward would leave
        // the rain behind so the player walked out of their own downpour.
        assertEquals(100.5, WeatherMeshCache.centreX(100.5), 1e-9);
        assertTrue(WeatherMeshCache.centreX(200.0) != WeatherMeshCache.centreX(100.0),
                "the centre moves with the player, as a uniform rather than a rebuild");
    }

    @Test
    void theDropsKeepFalling() {
        // Frozen rain is as wrong as absent rain, and it is the kind of wrongness that makes a scene feel
        // dead without the player being able to point at it.
        double a = WeatherMeshCache.fallOffset(100L, 0.5);
        double b = WeatherMeshCache.fallOffset(101L, 0.5);

        assertTrue(b > a, "the drops are still falling");
        assertEquals(0.5, b - a, 1e-9);
    }

    @Test
    void theMeshDoesNotChangeWhileThePlayerMoves() {
        // Both things that change often are uniforms; the expensive thing -- the pattern -- does not
        // change at all.
        WeatherMeshCache cache = new WeatherMeshCache();
        cache.needsRebuild(WeatherMeshCache.RAIN);

        for (int i = 0; i < 100; i++) {
            assertFalse(cache.needsRebuild(WeatherMeshCache.RAIN));
            assertTrue(WeatherMeshCache.centreX(i) != WeatherMeshCache.centreX(i + 1));
            assertTrue(WeatherMeshCache.fallOffset(i, 0.5) != WeatherMeshCache.fallOffset(i + 1, 0.5));
        }
    }

    @Test
    void invalidatingForcesTheNextRebuild() {
        WeatherMeshCache cache = new WeatherMeshCache();
        cache.needsRebuild(WeatherMeshCache.RAIN);

        cache.invalidate();

        assertEquals(-1, cache.cachedWeather());
        assertTrue(cache.needsRebuild(WeatherMeshCache.RAIN));
    }

    @Test
    void theSavingIsWhatTheFormulasSay() {
        WeatherMeshCache cache = new WeatherMeshCache();
        for (int i = 0; i < 100; i++) {
            cache.needsRebuild(WeatherMeshCache.RAIN);
        }

        assertEquals(1.202, WeatherMeshCache.msPerFrameWithoutCache(), 1e-9);
        // One rebuild in a hundred frames, so the cost is mostly the uniforms.
        assertTrue(cache.msPerFrame() < 0.015, "was " + cache.msPerFrame());
        assertTrue(cache.savedPercent() > 98.0, "was " + cache.savedPercent());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        WeatherMeshCache cache = new WeatherMeshCache();

        assertThrows(IllegalArgumentException.class, () -> cache.needsRebuild(-1));
        assertThrows(IllegalArgumentException.class, () -> cache.needsRebuild(3));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        WeatherMeshCache cache = new WeatherMeshCache();

        assertEquals(0.0, cache.reusedPercent());
        assertEquals(0.0, cache.msPerFrame());
        assertEquals(0L, cache.frames());
        assertEquals(-1, cache.cachedWeather());
        assertEquals(1500, WeatherMeshCache.RAIN_DROPS);
    }
}
