package net.amdfaster.light;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightCacheTest {

    /** A sampler backed by a map, so a test can describe a handful of blocks and nothing else. */
    private static final class MapSampler implements LightSampler {
        private final Map<Long, int[]> cells = new HashMap<>();
        private final int defaultSky;

        MapSampler(int defaultSky) {
            this.defaultSky = defaultSky;
        }

        private static long key(int x, int y, int z) {
            return ((long) (x + 64) << 24) | ((long) (y + 64) << 12) | (long) (z + 64);
        }

        MapSampler put(int x, int y, int z, int block, int sky, boolean occludes) {
            this.cells.put(key(x, y, z), new int[] {block, sky, occludes ? 1 : 0});
            return this;
        }

        private int[] at(int x, int y, int z) {
            return this.cells.getOrDefault(key(x, y, z), new int[] {0, this.defaultSky, 0});
        }

        @Override
        public int sky(int x, int y, int z) {
            return at(x, y, z)[1];
        }

        @Override
        public int block(int x, int y, int z) {
            return at(x, y, z)[0];
        }

        @Override
        public boolean occludes(int x, int y, int z) {
            return at(x, y, z)[2] != 0;
        }
    }

    @Test
    void theCacheIsEighteenCubedWithAOneBlockBorder() {
        assertEquals(18, LightCache.SIDE, "L55");
        assertEquals(1, LightCache.BORDER, "L56");
        assertEquals(5832, LightCache.CELLS, "L57");
        assertEquals(-1, LightCache.MIN, "L58");
        assertEquals(16, LightCache.MAX_INCLUSIVE, "L59");
    }

    @Test
    void theBorderIsReachableAndOneBeyondItIsNot() {
        assertTrue(LightCache.inCache(-1, -1, -1), "L64");
        assertTrue(LightCache.inCache(16, 16, 16), "L65");
        assertFalse(LightCache.inCache(-2, 0, 0), "L66");
        assertFalse(LightCache.inCache(17, 0, 0), "L67");
        assertFalse(LightCache.inCache(0, 17, 0), "L68");
    }

    @Test
    void readingOutsideTheCacheIsAnErrorNotASilentDefault() {
        LightCache cache = new LightCache();
        cache.fill(new MapSampler(15), 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> cache.sky(-2, 0, 0));
    }

    @Test
    void fillReadsEveryCellOfTheNeighbourhood() {
        // A counter sampler proves the fill covers exactly 18^3 cells and translates coordinates.
        int[] calls = {0};
        int[] minX = {Integer.MAX_VALUE};
        int[] maxX = {Integer.MIN_VALUE};
        LightSampler counting = new LightSampler() {
            @Override
            public int sky(int x, int y, int z) {
                calls[0]++;
                minX[0] = Math.min(minX[0], x);
                maxX[0] = Math.max(maxX[0], x);
                return 7;
            }

            @Override
            public int block(int x, int y, int z) {
                return 3;
            }

            @Override
            public boolean occludes(int x, int y, int z) {
                return false;
            }
        };
        new LightCache().fill(counting, 100, 200, 300);
        assertEquals(5832, calls[0], "L104");
        assertEquals(99, minX[0], "the border reaches one block below the section origin");
        assertEquals(116, maxX[0], "and one block past its far edge");
    }

    @Test
    void valuesSurviveTheRoundTrip() {
        MapSampler sampler = new MapSampler(0);
        sampler.put(3, 4, 5, 11, 9, true);
        LightCache cache = new LightCache();
        cache.fill(sampler, 0, 0, 0);

        assertEquals(11, cache.block(3, 4, 5), "L116");
        assertEquals(9, cache.sky(3, 4, 5), "L117");
        assertTrue(cache.occludes(3, 4, 5), "L118");
        assertEquals(LightValue.pack(11, 9), cache.light(3, 4, 5), "L119");
    }

    @Test
    void theWorldOffsetIsApplied() {
        MapSampler sampler = new MapSampler(0);
        sampler.put(10, 20, 30, 6, 8, true);
        LightCache cache = new LightCache();
        cache.fill(sampler, 8, 16, 24);

        assertEquals(8, cache.originX(), "L129");
        assertEquals(16, cache.originY(), "L130");
        assertEquals(24, cache.originZ(), "L131");
        // World (10,20,30) is local (2,4,6).
        assertEquals(6, cache.block(2, 4, 6), "L133");
        assertEquals(8, cache.sky(2, 4, 6), "L134");
        assertTrue(cache.occludes(2, 4, 6), "L135");
    }

    @Test
    void outOfRangeLevelsFromTheSamplerAreClamped() {
        LightCache cache = new LightCache();
        cache.fill(new LightSampler() {
            @Override
            public int sky(int x, int y, int z) {
                return 99;
            }

            @Override
            public int block(int x, int y, int z) {
                return -4;
            }

            @Override
            public boolean occludes(int x, int y, int z) {
                return false;
            }
        }, 0, 0, 0);
        assertEquals(15, cache.sky(0, 0, 0), "L157");
        assertEquals(0, cache.block(0, 0, 0), "L158");
    }

    @Test
    void uniformDetectsASectionWithNothingAroundIt() {
        LightCache uniform = new LightCache();
        uniform.fill(new MapSampler(15), 0, 0, 0);
        assertTrue(uniform.isUniform(), "L165");

        LightCache not = new LightCache();
        not.fill(new MapSampler(15).put(0, 0, 0, 0, 15, true), 0, 0, 0);
        assertFalse(not.isUniform(), "L169");
    }

    @Test
    void theRawSampleAgreesWithTheIndividualAccessors() {
        MapSampler sampler = new MapSampler(2);
        sampler.put(1, 2, 3, 13, 7, true);
        sampler.put(4, 5, 6, 0, 15, false);
        LightCache cache = new LightCache();
        cache.fill(sampler, 0, 0, 0);

        for (int[] p : new int[][] {{1, 2, 3}, {4, 5, 6}, {0, 0, 0}}) {
            int cell = cache.sample(p[0], p[1], p[2]);
            assertEquals(cache.block(p[0], p[1], p[2]), LightCache.blockOf(cell), "L182");
            assertEquals(cache.sky(p[0], p[1], p[2]), LightCache.skyOf(cell), "L183");
            assertEquals(cache.occludes(p[0], p[1], p[2]), LightCache.occludesOf(cell), "L184");
            assertEquals(cache.light(p[0], p[1], p[2]), LightCache.lightOf(cell), "L185");
        }
    }

    @Test
    void lightOfAgreesWithLightValuePackForEveryCombination() {
        // The cell keeps the block channel in bits 0..3 and LightValue keeps it in 4..7. This is the
        // invariant that catches a lightOf which returns the cell's own layout: it would read
        // plausibly, and every light value in the game would be wrong by a factor of sixteen.
        for (int block = 0; block <= LightValue.MAX; block++) {
            for (int sky = 0; sky <= LightValue.MAX; sky++) {
                for (boolean occludes : new boolean[] {false, true}) {
                    int cell = block | (sky << 4) | (occludes ? 256 : 0);
                    assertEquals(LightValue.pack(block, sky), LightCache.lightOf(cell),
                            "block=" + block + " sky=" + sky + " occludes=" + occludes);
                    assertEquals(block, LightCache.blockOf(cell), "L200");
                    assertEquals(sky, LightCache.skyOf(cell), "L201");
                    assertEquals(occludes, LightCache.occludesOf(cell), "L202");
                }
            }
        }
    }

    @Test
    void aCacheCanBeRefilledForANewSection() {
        LightCache cache = new LightCache();
        cache.fill(new MapSampler(15), 0, 0, 0);
        assertEquals(15, cache.sky(0, 0, 0), "L212");

        cache.fill(new MapSampler(0), 16, 0, 0);
        assertEquals(0, cache.sky(0, 0, 0), "L215");
        assertEquals(16, cache.originX(), "L216");
    }
}
