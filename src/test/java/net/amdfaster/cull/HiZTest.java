package net.amdfaster.cull;

import net.amdfaster.cull.HiZ.ScreenRect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiZTest {

    @Test
    void aPyramidHalvesUntilOnePixelRemains() {
        assertEquals(10, HiZ.maxLevel(1920, 1080), "1080 takes ten halvings to reach 1");
        assertEquals(10, HiZ.maxLevel(1024, 1024));
        assertEquals(0, HiZ.maxLevel(1, 1));
        assertEquals(0, HiZ.maxLevel(4096, 1), "the short side limits the pyramid");
    }

    @Test
    void aPyramidNeedsPositiveDimensions() {
        assertThrows(IllegalArgumentException.class, () -> HiZ.maxLevel(0, 1080));
        assertThrows(IllegalArgumentException.class, () -> HiZ.maxLevel(1920, -1));
    }

    @Test
    void aOnePixelBoxSamplesTheFinestLevel() {
        assertEquals(0, HiZ.selectLevel(1920, 1080, 1f, 1f));
        assertEquals(0, HiZ.selectLevel(1920, 1080, 0.5f, 0.5f));
    }

    @Test
    void theLevelIsTheCoarsestOneThatStillCoversTheBox() {
        assertEquals(1, HiZ.selectLevel(1920, 1080, 2f, 2f));
        assertEquals(2, HiZ.selectLevel(1920, 1080, 3f, 3f), "3 pixels needs level 2, not level 1");
        assertEquals(2, HiZ.selectLevel(1920, 1080, 4f, 4f));
        assertEquals(3, HiZ.selectLevel(1920, 1080, 5f, 5f));
        assertEquals(10, HiZ.selectLevel(1920, 1080, 1000f, 1000f));
    }

    @Test
    void theLongerSideChoosesTheLevel() {
        assertEquals(HiZ.selectLevel(1920, 1080, 8f, 8f),
                HiZ.selectLevel(1920, 1080, 1f, 8f));
    }

    @Test
    void theLevelIsClampedToThePyramid() {
        assertEquals(10, HiZ.selectLevel(1920, 1080, 100000f, 1f));
        assertEquals(0, HiZ.selectLevel(1, 1, 500f, 500f));
    }

    @Test
    void aCentredBoxProjectsToTheMiddleOfTheScreen() {
        ScreenRect rect = HiZ.projectToPixels(-0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f,
                Frustum.identity(), 1000, 1000);
        assertTrue(rect.reliable());
        assertEquals(250f, rect.minX(), 1e-3f);
        assertEquals(750f, rect.maxX(), 1e-3f);
        assertEquals(250f, rect.minY(), 1e-3f);
        assertEquals(750f, rect.maxY(), 1e-3f);
        assertEquals(500f, rect.width(), 1e-3f);
        assertEquals(500f, rect.height(), 1e-3f);
    }

    @Test
    void aPointAtTheOriginProjectsToTheCentrePixel() {
        ScreenRect rect = HiZ.projectToPixels(0f, 0f, 0.5f, 0f, 0f, 0.5f,
                Frustum.identity(), 800, 600);
        assertEquals(400f, rect.minX(), 1e-3f);
        assertEquals(300f, rect.minY(), 1e-3f);
    }

    @Test
    void theYAxisFlipsBetweenNdcAndTheFramebuffer() {
        // Vulkan's framebuffer has Y growing downwards, NDC has it growing upwards, so the box's
        // NDC top edge becomes the rectangle's smallest pixel Y.
        ScreenRect rect = HiZ.projectToPixels(0f, 0.25f, 0.5f, 0f, 0.75f, 0.5f,
                Frustum.identity(), 1000, 1000);
        assertEquals(125f, rect.minY(), 1e-3f, "ndcY 0.75 is 125 pixels down");
        assertEquals(375f, rect.maxY(), 1e-3f, "ndcY 0.25 is 375 pixels down");
    }

    @Test
    void aBoxOffToTheRightIsOffScreen() {
        ScreenRect rect = HiZ.projectToPixels(2f, 0f, 0.5f, 3f, 0f, 0.5f,
                Frustum.identity(), 1000, 1000);
        assertTrue(rect.reliable());
        assertTrue(rect.isOffScreen(1000, 1000));
    }

    @Test
    void aBoxStraddlingTheEdgeIsNotOffScreen() {
        ScreenRect rect = HiZ.projectToPixels(0.5f, 0f, 0.5f, 1.5f, 0f, 0.5f,
                Frustum.identity(), 1000, 1000);
        assertFalse(rect.isOffScreen(1000, 1000));
    }

    @Test
    void aCornerBehindTheCameraMakesProjectionUnreliable() {
        // row3 = (0,0,-1,0) makes clipW = -z, so any box at positive z is behind the camera.
        float[] behind = Frustum.identity();
        behind[11] = -1f;
        behind[15] = 0f;
        ScreenRect rect = HiZ.projectToPixels(0f, 0f, 0.5f, 1f, 1f, 1f, behind, 1000, 1000);
        assertFalse(rect.reliable());
        assertFalse(rect.isOffScreen(1000, 1000),
                "an unreliable rectangle must never be culled, or geometry vanishes in view");
    }

    @Test
    void theEverythingRectangleIsUnreliableByConstruction() {
        ScreenRect rect = ScreenRect.everything();
        assertFalse(rect.reliable());
        assertFalse(rect.isOffScreen(1000, 1000));
        assertTrue(rect.width() > 0f);
    }
}
