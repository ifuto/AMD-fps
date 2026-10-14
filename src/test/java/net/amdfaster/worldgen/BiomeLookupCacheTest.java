package net.amdfaster.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeLookupCacheTest {

    private static final class Biomes implements BiomeLookupCache.BiomeSource {
        int calls;

        @Override
        public int biomeAt(int x, int y, int z) {
            this.calls++;
            // A plausible biome field: varies slowly, so neighbouring cells often agree too.
            return ((x >> 6) & 3) * 4 + ((z >> 6) & 3);
        }
    }

    private static final class Surfaces implements BiomeLookupCache.SurfaceResolver {
        int calls;

        @Override
        public int surfaceBlockFor(int biomeId, int depthBelowSurface) {
            this.calls++;
            if (depthBelowSurface == 0) {
                return 100 + biomeId;
            }
            return depthBelowSurface < 4 ? 200 + biomeId : 300;
        }
    }

    @Test
    void everyBlockInACellSharesOneLookup() {
        // Biomes are sampled four by four by four, so 64 positions are answered from one stored
        // value. Asking per position anyway is what world generation does.
        Biomes source = new Biomes();
        BiomeLookupCache cache = new BiomeLookupCache();

        int first = cache.biomeAt(source, 8, 64, 8);
        for (int dx = 0; dx < 4; dx++) {
            for (int dy = 0; dy < 4; dy++) {
                for (int dz = 0; dz < 4; dz++) {
                    assertEquals(first, cache.biomeAt(source, 8 + dx, 64 + dy, 8 + dz),
                            "cell member " + dx + "," + dy + "," + dz);
                }
            }
        }
        assertEquals(1, source.calls, "64 positions, one lookup");
        assertEquals(1, cache.biomeCacheSize());
        assertEquals(63L, cache.biomeHits());
    }

    @Test
    void aWholeChunkCollapsesToItsCellCount() {
        // 16 by 16 by 384 is 98,304 positions. On a four block cell grid that is 1,536 distinct
        // lookups, and the table holds the small number rather than the large one.
        Biomes source = new Biomes();
        BiomeLookupCache cache = new BiomeLookupCache(2048, 64);

        int positions = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = -64; y < 320; y++) {
                    cache.biomeAt(source, x, y, z);
                    positions++;
                }
            }
        }
        assertEquals(16 * 16 * 384, positions, "98,304 positions");
        assertEquals(1536, source.calls, "one per four by four by four cell");
        assertEquals(1536, cache.biomeCacheSize());
        assertEquals(positions - 1536, cache.biomeHits());
        assertEquals(64, BiomeLookupCache.POSITIONS_PER_CELL);
        assertEquals(98.4, cache.biomeHitRatePercent(), 0.1);
    }

    @Test
    void adjacentCellsAreLookedUpSeparately() {
        // Crossing a cell boundary is a real change of answer, and a cache that smoothed over it
        // would put a biome boundary in the wrong place.
        Biomes source = new Biomes();
        BiomeLookupCache cache = new BiomeLookupCache();

        cache.biomeAt(source, 127, 64, 127);
        cache.biomeAt(source, 128, 64, 127);

        assertEquals(2, source.calls);
        assertEquals(2, cache.biomeCacheSize());
    }

    @Test
    void surfaceBlocksConvergeOnAFewAnswers() {
        // What a biome puts at its surface depends on the biome and the depth, both small numbers,
        // not on the position. Across a chunk that is a handful of answers.
        Surfaces resolver = new Surfaces();
        BiomeLookupCache cache = new BiomeLookupCache(64, 256);

        for (int i = 0; i < 20000; i++) {
            int biome = i % 16;
            int depth = i % 8;
            cache.surfaceBlockFor(resolver, biome, depth);
        }
        assertEquals(16 * 8, resolver.calls, "128 distinct biome and depth pairs");
        assertEquals(128, cache.surfaceCacheSize());
        assertEquals(20000 - 128, cache.surfaceHits());
        assertTrue(cache.surfaceHitRatePercent() > 99.0);
    }

    @Test
    void depthZeroAndOtherDepthsAreDifferentAnswers() {
        Surfaces resolver = new Surfaces();
        BiomeLookupCache cache = new BiomeLookupCache(64, 256);

        int grass = cache.surfaceBlockFor(resolver, 3, 0);
        int dirt = cache.surfaceBlockFor(resolver, 3, 1);
        int stone = cache.surfaceBlockFor(resolver, 3, 9);

        assertEquals(103, grass);
        assertEquals(203, dirt);
        assertEquals(300, stone);
        assertEquals(3, resolver.calls);
    }

    @Test
    void negativeIdsAreRejectedBecauseTheyAreTheEmptyMarker() {
        // A negative value stored in the table would read back as an empty slot, so the lookup would
        // run again every time and the cache would report a hit rate it is not earning.
        BiomeLookupCache cache = new BiomeLookupCache();

        assertThrows(IllegalArgumentException.class, () -> cache.biomeAt((x, y, z) -> -1, 0, 64, 0));
        assertThrows(IllegalArgumentException.class,
                () -> cache.surfaceBlockFor((b, d) -> -5, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> cache.surfaceBlockFor((b, d) -> 1, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> cache.surfaceBlockFor((b, d) -> 1, 0, -1));
    }

    @Test
    void clearDropsBothCaches() {
        Biomes source = new Biomes();
        Surfaces resolver = new Surfaces();
        BiomeLookupCache cache = new BiomeLookupCache();

        cache.biomeAt(source, 0, 64, 0);
        cache.surfaceBlockFor(resolver, 1, 0);
        cache.clear();

        assertEquals(0, cache.biomeCacheSize());
        assertEquals(0, cache.surfaceCacheSize());
        cache.biomeAt(source, 0, 64, 0);
        cache.surfaceBlockFor(resolver, 1, 0);
        assertEquals(2, source.calls);
        assertEquals(2, resolver.calls);
    }

    @Test
    void cellCoordinatesDoNotPackAlike() {
        java.util.Set<Integer> keys = new java.util.HashSet<>();
        long expected = 0;
        for (int cx = -200; cx < 200; cx++) {
            for (int cy = -8; cy < 24; cy++) {
                for (int cz = -200; cz < 200; cz++) {
                    keys.add(BiomeLookupCache.packCell(cx, cy, cz));
                    expected++;
                }
            }
        }
        assertEquals(expected, keys.size(), "every cell in a generous volume packs distinctly");
    }

    @Test
    void fillingPastTheCapacityGrowsRatherThanHanging() {
        // probe on a full table with a missing key walks every slot and returns to its start.
        // Another class here did that and hung the thread; CI reported it as a timeout.
        BiomeLookupCache cache = new BiomeLookupCache(16, 16);

        for (int i = 0; i < 4000; i++) {
            // Captured, because a lambda cannot close over a loop variable that is still changing.
            final int id = i % 64;
            cache.biomeAt((x, y, z) -> id, i % 64 - 32, 64, i / 64 - 32);
        }
        for (int i = 0; i < 4000; i++) {
            cache.surfaceBlockFor((b, d) -> b + d, i % 256, i % 256);
        }

        assertEquals(4000, cache.biomeCacheSize());
        assertEquals(256, cache.surfaceCacheSize());
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        BiomeLookupCache cache = new BiomeLookupCache();
        assertEquals(0.0, cache.biomeHitRatePercent());
        assertEquals(0.0, cache.surfaceHitRatePercent());
        assertEquals(0L, cache.biomeReads());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BiomeLookupCache(8, 64));
        assertThrows(IllegalArgumentException.class, () -> new BiomeLookupCache(64, 8));
    }
}
