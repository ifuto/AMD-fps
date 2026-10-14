package net.amdfaster.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VramBudgetTest {

    private static int[] tile(int seed) {
        int[] pixels = new int[VramBudget.TILE_PIXELS];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xFF000000 | ((seed * 31 + i * 7) & 0xFFFFFF);
        }
        return pixels;
    }

    @Test
    void identicalTilesAreStoredOnce() {
        VramBudget budget = new VramBudget();

        int first = budget.addTile(tile(1));
        for (int i = 0; i < 99; i++) {
            assertEquals(first, budget.addTile(tile(1)), "the same picture is the same tile");
        }

        assertEquals(100L, budget.tilesSeen());
        assertEquals(1, budget.uniqueCount());
        assertEquals(99L, budget.duplicates());
        assertEquals(99.0, budget.savedPercent(), 0.01);
    }

    @Test
    void aSinglePixelDifferenceMakesItADifferentTile() {
        // The bug this prevents. Merging tiles that look alike gives a block another block's texture,
        // which a player sees immediately and reports as a graphics bug with no idea what caused it.
        VramBudget budget = new VramBudget();

        int[] base = tile(5);
        int[] changed = base.clone();
        changed[137] ^= 0x00010000;

        int a = budget.addTile(base);
        int b = budget.addTile(changed);

        assertNotEquals(a, b);
        assertEquals(2, budget.uniqueCount());
        assertEquals(0L, budget.duplicates());
        assertArrayEquals(changed, budget.tileAt(b));
    }

    @Test
    void manyDistinctTilesAllSurvive() {
        VramBudget budget = new VramBudget(2048);

        for (int i = 0; i < 1000; i++) {
            budget.addTile(tile(i));
        }

        assertEquals(1000, budget.uniqueCount(), "no two seeds collided into one tile");
        assertEquals(0L, budget.duplicates());
        assertEquals(1000L * VramBudget.TILE_BYTES, budget.uniqueBytes());
    }

    @Test
    void anAtlasOfRepeatedTilesSavesWhatItClaims() {
        // Forty percent duplication on a 4,096 tile atlas.
        VramBudget budget = new VramBudget(4096);
        for (int i = 0; i < 4096; i++) {
            budget.addTile(tile(i % 2457));
        }

        assertEquals(2457, budget.uniqueCount());
        assertEquals(1639L, budget.duplicates());
        assertEquals(4096L * VramBudget.TILE_BYTES, budget.bytesWithoutDedup());
        assertEquals(2457L * VramBudget.TILE_BYTES, budget.uniqueBytes());
        assertEquals(1639L * VramBudget.TILE_BYTES, budget.bytesSaved());
        assertEquals(1_678_336L, budget.bytesSaved());
        assertEquals(40.0, budget.savedPercent(), 0.01);
    }

    @Test
    void theMipChainIncludesTheLastLevel() {
        // An earlier version halved before checking for the end and dropped the 1 by 1 level. Four
        // bytes out of 5.6 MB, which reads as correct because the number is close.
        assertEquals(1_364L, VramBudget.bytesWithMipmaps(VramBudget.Format.RGBA8, 16, 16));
        assertEquals(5_592_404L, VramBudget.bytesWithMipmaps(VramBudget.Format.RGBA8, 1024, 1024));
        assertEquals(4_194_304L, VramBudget.Format.RGBA8.bytesFor(1024, 1024), "base level");
        assertEquals(133.3,
                100.0 * VramBudget.bytesWithMipmaps(VramBudget.Format.RGBA8, 1024, 1024)
                        / VramBudget.Format.RGBA8.bytesFor(1024, 1024),
                0.1, "a third more, which is the difference between fitting and not");
        assertEquals(4L, VramBudget.bytesWithMipmaps(VramBudget.Format.RGBA8, 1, 1));
    }

    @Test
    void formatChoiceFollowsWhetherAlphaIsActuallyUsed() {
        // BC1 has no usable alpha, so choosing it for a texture with real alpha throws the alpha away.
        // BC7 for one without doubles the memory for nothing. The choice follows the pixels, not what
        // the resource pack asked for.
        assertEquals(VramBudget.Format.BC1, VramBudget.chooseFormat(false, false));
        assertEquals(VramBudget.Format.BC1, VramBudget.chooseFormat(false, true),
                "quality does not buy anything without alpha");
        assertEquals(VramBudget.Format.BC3, VramBudget.chooseFormat(true, false));
        assertEquals(VramBudget.Format.BC7, VramBudget.chooseFormat(true, true));

        assertFalse(VramBudget.Format.BC1.hasAlpha);
        assertTrue(VramBudget.Format.BC3.hasAlpha);
        assertEquals(0.5, VramBudget.Format.BC1.bytesPerPixel);
        assertEquals(1.0, VramBudget.Format.BC7.bytesPerPixel);
    }

    @Test
    void compressedFormatsCostWhatTheyClaim() {
        assertEquals(524_288L, VramBudget.Format.BC1.bytesFor(1024, 1024), "an eighth of RGBA8");
        assertEquals(1_048_576L, VramBudget.Format.BC3.bytesFor(1024, 1024), "a quarter");
        assertEquals(4_194_304L, VramBudget.Format.RGBA8.bytesFor(1024, 1024));

        // A 16 by 16 light map.
        assertEquals(1_024L, VramBudget.Format.RGBA8.bytesFor(16, 16));
        assertEquals(128L, VramBudget.Format.BC4.bytesFor(16, 16));
    }

    @Test
    void alphaIsDetectedPerPixel() {
        int[] opaque = new int[VramBudget.TILE_PIXELS];
        java.util.Arrays.fill(opaque, 0xFF123456);
        assertFalse(VramBudget.usesAlpha(opaque));

        int[] oneTransparent = opaque.clone();
        oneTransparent[200] = 0x80123456;
        assertTrue(VramBudget.usesAlpha(oneTransparent), "one pixel is enough");

        int[] fullyTransparent = opaque.clone();
        fullyTransparent[0] = 0x00123456;
        assertTrue(VramBudget.usesAlpha(fullyTransparent), "zero alpha is still alpha");
    }

    @Test
    void storedTilesAreCopiedSoTheCallerCannotChangeThem() {
        // A tile stored by reference would change under the deduplicator if the caller reused its
        // array, and every tile aliasing it would silently become something else.
        VramBudget budget = new VramBudget();
        int[] pixels = tile(9);
        int index = budget.addTile(pixels);
        int[] stored = budget.tileAt(index).clone();

        pixels[0] = 0xFFABCDEF;

        assertArrayEquals(stored, budget.tileAt(index), "the stored tile did not move");
        assertEquals(index, budget.addTile(stored), "and the original still matches");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        VramBudget budget = new VramBudget();

        assertThrows(IllegalArgumentException.class, () -> budget.addTile(new int[255]));
        assertThrows(IllegalArgumentException.class,
                () -> VramBudget.bytesWithMipmaps(VramBudget.Format.RGBA8, 0, 16));
        assertThrows(IllegalArgumentException.class,
                () -> VramBudget.bytesWithMipmaps(VramBudget.Format.RGBA8, 16, 0));
        assertThrows(IllegalArgumentException.class, () -> new VramBudget(8));
        assertThrows(IndexOutOfBoundsException.class, () -> budget.tileAt(0), "nothing stored yet");
    }

    @Test
    void aFreshBudgetReportsZeroNotNaN() {
        VramBudget budget = new VramBudget();
        assertEquals(0.0, budget.savedPercent());
        assertEquals(0L, budget.tilesSeen());
        assertEquals(16, VramBudget.TILE_SIZE);
        assertEquals(256, VramBudget.TILE_PIXELS);
        assertEquals(1024, VramBudget.TILE_BYTES);
    }
}
