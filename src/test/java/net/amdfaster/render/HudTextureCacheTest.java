package net.amdfaster.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudTextureCacheTest {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int HUD_W = 400;
    private static final int HUD_H = 120;

    @Test
    void theFirstFrameBuildsAndTheRestBlit() {
        // Blitting one textured quad is a single draw call, so a HUD that costs a dozen calls to draw costs
        // one to display.
        HudTextureCache cache = new HudTextureCache();

        assertFalse(cache.canBlit(WIDTH, HEIGHT, false, HUD_W, HUD_H), "nothing to blit yet");
        assertTrue(cache.canBlit(WIDTH, HEIGHT, false, HUD_W, HUD_H));

        assertEquals(1L, cache.rebuilt());
        assertEquals(1L, cache.blitted());
    }

    @Test
    void anUpdatedHudRedrawsTheTexture() {
        HudTextureCache cache = new HudTextureCache();

        cache.canBlit(WIDTH, HEIGHT, false, HUD_W, HUD_H);
        assertFalse(cache.canBlit(WIDTH, HEIGHT, true, HUD_W, HUD_H), "the HUD changed");
        assertEquals(2L, cache.rebuilt());
    }

    @Test
    void aResolutionChangeRedrawsTheTexture() {
        // The HUD is drawn in scaled units, so its pixel extent depends on the resolution. A texture built
        // at the old size would be stretched or cropped, so the health bar would be the wrong width or part
        // of the hotbar would be missing.
        HudTextureCache cache = new HudTextureCache();

        cache.canBlit(WIDTH, HEIGHT, false, HUD_W, HUD_H);
        assertFalse(cache.canBlit(2560, 1440, false, HUD_W, HUD_H));
        assertEquals(2L, cache.rebuilt());
    }

    @Test
    void theTextureIsTheSizeOfTheHudNotTheScreen() {
        // A texture covering the whole window would waste most of its pixels on empty space, and on a large
        // window that is a great deal of memory for nine quads of hotbar.
        HudTextureCache cache = new HudTextureCache();

        assertEquals(HUD_W, cache.textureWidth(HUD_W));
        assertEquals(HUD_H, cache.textureHeight(HUD_H));
        assertTrue(cache.textureWidth(HUD_W) < WIDTH);
    }

    @Test
    void invalidatingForcesARedraw() {
        HudTextureCache cache = new HudTextureCache();

        cache.canBlit(WIDTH, HEIGHT, false, HUD_W, HUD_H);
        cache.invalidate();

        assertFalse(cache.isValid());
        assertFalse(cache.canBlit(WIDTH, HEIGHT, false, HUD_W, HUD_H));
    }

    @Test
    void anIdleSessionBlitsAlmostEveryFrame() {
        HudTextureCache cache = new HudTextureCache();

        cache.canBlit(WIDTH, HEIGHT, false, HUD_W, HUD_H);
        for (int frame = 0; frame < 999; frame++) {
            cache.canBlit(WIDTH, HEIGHT, false, HUD_W, HUD_H);
        }

        assertEquals(1000L, cache.frames());
        assertEquals(999L, cache.blitted());
        assertEquals(99.9, cache.blitPercent(), 1e-9);
        assertEquals(999 * (HudTextureCache.MS_PER_FRAME - HudTextureCache.MS_PER_BLIT),
                cache.msSaved(), 1e-9);
        assertEquals(93.24, cache.savedPercent(), 0.01,
                "279.72 saved of 300.0 -- the blit still costs something");
    }

    @Test
    void aHudWithNoSizeIsRejected() {
        HudTextureCache cache = new HudTextureCache();

        assertThrows(IllegalArgumentException.class,
                () -> cache.canBlit(WIDTH, HEIGHT, false, 0, HUD_H));
        assertThrows(IllegalArgumentException.class,
                () -> cache.canBlit(WIDTH, HEIGHT, false, HUD_W, -1));
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        // A cache that has drawn nothing has technically avoided everything, but reporting that as a saving
        // would describe an unused one as though it had done something.
        HudTextureCache cache = new HudTextureCache();

        assertEquals(0.0, cache.blitPercent());
        assertEquals(0.0, cache.savedPercent());
        assertEquals(0.0, cache.msSaved());
        assertEquals(0L, cache.frames());
        assertFalse(cache.isValid());
        assertEquals(14, HudTextureCache.ELEMENT_CALLS);
    }
}
