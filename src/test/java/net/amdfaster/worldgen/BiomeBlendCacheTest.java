package net.amdfaster.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeBlendCacheTest {

    private static final int PLAINS = 1;
    private static final int FOREST = 2;
    private static final int GRASS = 0x7CBD6B;
    private static final int DIRT = 0x8FBC8F;

    private static int[] biomes(int... ids) {
        return ids;
    }

    private static int[] colours(int colour, int count) {
        int[] out = new int[count];
        java.util.Arrays.fill(out, colour);
        return out;
    }

    @Test
    void aUniformNeighbourhoodSkipsTheAverage() {
        // In most of a section the nine blocks around a vertex are all the same biome, so the average is
        // the colour itself.
        BiomeBlendCache cache = new BiomeBlendCache();

        int colour = cache.blend(biomes(PLAINS, PLAINS, PLAINS, PLAINS, PLAINS,
                PLAINS, PLAINS, PLAINS, PLAINS), colours(GRASS, 9));

        assertEquals(GRASS, colour);
        assertEquals(1L, cache.shortcut());
        assertEquals(0L, cache.averaged());
    }

    @Test
    void aBoundaryAverages() {
        // Smooth blending is the whole point: without it, a biome border is a hard line where the grass
        // changes colour in one block. Skipping the average there would reintroduce exactly that.
        BiomeBlendCache cache = new BiomeBlendCache();

        int[] mixed = {GRASS, GRASS, GRASS, GRASS, DIRT, DIRT, DIRT, DIRT, DIRT};
        int colour = cache.blend(biomes(PLAINS, PLAINS, PLAINS, PLAINS,
                FOREST, FOREST, FOREST, FOREST, FOREST), mixed);

        assertEquals(0x86BC7F, colour, "the per-channel mean of the two");
        assertEquals(1L, cache.averaged());
        assertEquals(0L, cache.shortcut());
    }

    @Test
    void aSingleDifferingBiomeForcesTheAverage() {
        BiomeBlendCache cache = new BiomeBlendCache();

        int colour = cache.blend(biomes(PLAINS, PLAINS, PLAINS, PLAINS, PLAINS,
                PLAINS, PLAINS, PLAINS, FOREST), colours(GRASS, 9));

        assertEquals(GRASS, colour, "all nine colours are the same, so the mean is that colour");
        assertEquals(1L, cache.averaged(), "but the biomes differ, so the average was computed");
    }

    @Test
    void eachChannelIsAveragedSeparately() {
        // Averaging packed colours would let one channel carry into the next and shift the hue.
        int mean = BiomeBlendCache.average(new int[] {0x000000, 0xFF0000});

        assertEquals(0x7F0000, mean, "red halves, green and blue untouched");
    }

    @Test
    void equalColoursInDifferentBiomesStillAverage() {
        // Two biomes can share a grass colour. Comparing biome ids is the honest test: it is the game's
        // own rule, and it does not have to explain why a shortcut based on colour is safe when the
        // colour happens to coincide.
        BiomeBlendCache cache = new BiomeBlendCache();

        cache.blend(biomes(PLAINS, PLAINS, PLAINS, PLAINS,
                FOREST, FOREST, FOREST, FOREST, FOREST), colours(GRASS, 9));

        assertEquals(1L, cache.averaged());
        assertEquals(0L, cache.shortcut());
    }

    @Test
    void aMeasuredSectionSavesAboutTwoThirds() {
        // 70% of vertices are inside a single biome.
        BiomeBlendCache cache = new BiomeBlendCache();

        for (int i = 0; i < 700; i++) {
            cache.blend(biomes(PLAINS, PLAINS, PLAINS, PLAINS, PLAINS,
                    PLAINS, PLAINS, PLAINS, PLAINS), colours(GRASS, 9));
        }
        for (int i = 0; i < 300; i++) {
            cache.blend(biomes(PLAINS, PLAINS, PLAINS, PLAINS,
                    FOREST, FOREST, FOREST, FOREST, FOREST), colours(GRASS, 9));
        }

        assertEquals(1000L, cache.vertices());
        assertEquals(70.0, cache.shortcutPercent(), 0.01);
        assertEquals(0.6, cache.msWithoutShortcut(), 1e-9);
        assertEquals(0.215, cache.msForSection(), 1e-9);
        assertEquals(64.17, cache.savedPercent(), 0.01);
    }

    @Test
    void theSampleCountIsFixed() {
        BiomeBlendCache cache = new BiomeBlendCache();

        assertThrows(IllegalArgumentException.class,
                () -> cache.blend(biomes(PLAINS, PLAINS, PLAINS), colours(GRASS, 3)));
        assertThrows(IllegalArgumentException.class,
                () -> cache.blend(biomes(PLAINS, PLAINS, PLAINS, PLAINS, PLAINS,
                        PLAINS, PLAINS, PLAINS, PLAINS), colours(GRASS, 8)));
        assertEquals(9, BiomeBlendCache.BLEND_SAMPLES);
        assertEquals(3, BiomeBlendCache.CHANNELS);
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        // A cache that has done nothing has technically avoided all of it, but reporting a saving for an
        // unused one reads as a result when it is the absence of one.
        BiomeBlendCache cache = new BiomeBlendCache();

        assertEquals(0.0, cache.shortcutPercent());
        assertEquals(0.0, cache.savedPercent(), "no vertices blended, so nothing saved");
        assertEquals(0.0, cache.msForSection());
        assertEquals(0L, cache.vertices());
    }
}
