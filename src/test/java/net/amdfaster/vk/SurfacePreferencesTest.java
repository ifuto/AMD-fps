package net.amdfaster.vk;

import net.amdfaster.vk.SurfacePreferences.FormatChoice;
import net.amdfaster.vk.SurfacePreferences.SurfaceFormat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.amdfaster.vk.SurfacePreferences.COLORSPACE_SRGB_NONLINEAR;
import static net.amdfaster.vk.SurfacePreferences.DESIRED_IMAGE_COUNT;
import static net.amdfaster.vk.SurfacePreferences.PRESENT_MODE_FIFO;
import static net.amdfaster.vk.SurfacePreferences.PRESENT_MODE_FIFO_RELAXED;
import static net.amdfaster.vk.SurfacePreferences.PRESENT_MODE_IMMEDIATE;
import static net.amdfaster.vk.SurfacePreferences.PRESENT_MODE_MAILBOX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfacePreferencesTest {

    /**
     * The mirrored VkFormat values have to be the real ones, or the chooser silently picks a
     * non-preferred format on every machine and nothing ever fails.
     */
    @Test
    void theMirroredFormatConstantsMatchVulkan() {
        assertEquals(org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB,
                SurfacePreferences.VK_FORMAT_B8G8R8A8_SRGB);
        assertEquals(org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB,
                SurfacePreferences.VK_FORMAT_R8G8B8A8_SRGB);
        assertEquals(org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM,
                SurfacePreferences.VK_FORMAT_B8G8R8A8_UNORM);
        assertEquals(org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM,
                SurfacePreferences.VK_FORMAT_R8G8B8A8_UNORM);
        assertEquals(org.lwjgl.vulkan.VK10.VK_FORMAT_A2B10G10R10_UNORM_PACK32,
                SurfacePreferences.VK_FORMAT_A2B10G10R10_UNORM_PACK32);
    }

    @Test
    void theMirroredPresentModesMatchVulkan() {
        assertEquals(org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR, PRESENT_MODE_IMMEDIATE);
        assertEquals(org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR, PRESENT_MODE_MAILBOX);
        assertEquals(org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR, PRESENT_MODE_FIFO);
        assertEquals(org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR, PRESENT_MODE_FIFO_RELAXED);
        assertEquals(org.lwjgl.vulkan.KHRSurface.VK_COLORSPACE_SRGB_NONLINEAR_KHR, COLORSPACE_SRGB_NONLINEAR);
    }

    @Test
    void bgraSrgbWinsWhenTheSurfaceOffersEverything() {
        // BGRA before RGBA because it is the order the window system already uses, so no swizzle
        // is inserted; sRGB before UNORM because the colour block applies the transfer function
        // on write and that is free on AMD.
        FormatChoice choice = SurfacePreferences.chooseFormat(List.of(
                new SurfaceFormat(SurfacePreferences.VK_FORMAT_R8G8B8A8_UNORM, COLORSPACE_SRGB_NONLINEAR),
                new SurfaceFormat(SurfacePreferences.VK_FORMAT_B8G8R8A8_SRGB, COLORSPACE_SRGB_NONLINEAR),
                new SurfaceFormat(SurfacePreferences.VK_FORMAT_R8G8B8A8_SRGB, COLORSPACE_SRGB_NONLINEAR)));
        assertEquals(SurfacePreferences.VK_FORMAT_B8G8R8A8_SRGB, choice.format());
        assertEquals("preferred format available", choice.reason());
    }

    @Test
    void aSurfaceThatAcceptsAnythingGetsThePreferredFormat() {
        FormatChoice choice = SurfacePreferences.chooseFormat(List.of(new SurfaceFormat(0, 0)));
        assertEquals(SurfacePreferences.VK_FORMAT_B8G8R8A8_SRGB, choice.format());
        assertTrue(choice.reason().contains("accepts any format"), choice.reason());
    }

    @Test
    void anSrgbOnlySurfaceStillWins() {
        FormatChoice choice = SurfacePreferences.chooseFormat(List.of(
                new SurfaceFormat(SurfacePreferences.VK_FORMAT_R8G8B8A8_SRGB, COLORSPACE_SRGB_NONLINEAR)));
        assertEquals(SurfacePreferences.VK_FORMAT_R8G8B8A8_SRGB, choice.format());
    }

    @Test
    void anUnormOnlySurfaceFallsBackAndSaysSo() {
        FormatChoice choice = SurfacePreferences.chooseFormat(List.of(
                new SurfaceFormat(SurfacePreferences.VK_FORMAT_R8G8B8A8_UNORM, COLORSPACE_SRGB_NONLINEAR)));
        assertEquals(SurfacePreferences.VK_FORMAT_R8G8B8A8_UNORM, choice.format());
        assertEquals("preferred format available", choice.reason());
    }

    @Test
    void anExoticFormatIsTakenWhenNothingPreferredExists() {
        FormatChoice choice = SurfacePreferences.chooseFormat(List.of(
                new SurfaceFormat(999, COLORSPACE_SRGB_NONLINEAR)));
        assertEquals(999, choice.format());
        assertTrue(choice.reason().contains("no preferred format"), choice.reason());
    }

    @Test
    void aNonSrgbColourSpaceIsTheLastResort() {
        FormatChoice choice = SurfacePreferences.chooseFormat(List.of(
                new SurfaceFormat(SurfacePreferences.VK_FORMAT_B8G8R8A8_UNORM, 7),
                new SurfaceFormat(SurfacePreferences.VK_FORMAT_R8G8B8A8_UNORM, 7)));
        assertTrue(choice.reason().contains("no sRGB colour space"), choice.reason());
    }

    @Test
    void noFormatsAtAllFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> SurfacePreferences.chooseFormat(List.of()));
    }

    @Test
    void aDiscreteCardGetsMailbox() {
        // Mailbox never waits, which is the lowest-latency option when there is thermal headroom.
        assertEquals(PRESENT_MODE_MAILBOX,
                SurfacePreferences.choosePresentMode(new int[] {PRESENT_MODE_IMMEDIATE,
                        PRESENT_MODE_MAILBOX, PRESENT_MODE_FIFO}, false));
    }

    @Test
    void anApuGetsFifo() {
        // On an integrated part, uncapped rendering burns a power and thermal budget shared with
        // the CPU. These are exactly the machines the mod has to be good on.
        assertEquals(PRESENT_MODE_FIFO,
                SurfacePreferences.choosePresentMode(new int[] {PRESENT_MODE_IMMEDIATE,
                        PRESENT_MODE_MAILBOX, PRESENT_MODE_FIFO}, true));
    }

    @Test
    void fifoIsTheFallbackWhenMailboxIsMissing() {
        assertEquals(PRESENT_MODE_FIFO,
                SurfacePreferences.choosePresentMode(new int[] {PRESENT_MODE_IMMEDIATE,
                        PRESENT_MODE_FIFO}, false));
    }

    @Test
    void anythingIsBetterThanNothing() {
        assertEquals(PRESENT_MODE_IMMEDIATE,
                SurfacePreferences.choosePresentMode(new int[] {PRESENT_MODE_IMMEDIATE}, false));
        assertThrows(IllegalStateException.class,
                () -> SurfacePreferences.choosePresentMode(new int[0], false));
    }

    @Test
    void tripleBufferingWhenTheSurfaceAllowsIt() {
        assertEquals(3, SurfacePreferences.chooseImageCount(2, 3));
        assertEquals(3, SurfacePreferences.chooseImageCount(2, 8));
        assertEquals(3, SurfacePreferences.chooseImageCount(3, 0), "zero max means unbounded");
    }

    @Test
    void theSurfaceMinimumIsRespected() {
        assertEquals(4, SurfacePreferences.chooseImageCount(4, 8));
    }

    @Test
    void theSurfaceMaximumIsRespected() {
        assertEquals(2, SurfacePreferences.chooseImageCount(2, 2));
        assertTrue(DESIRED_IMAGE_COUNT >= 2);
    }

    @Test
    void theExtentIsClampedBothWays() {
        assertEquals(1080, SurfacePreferences.chooseExtent(1, 1080, 4000));
        assertEquals(480, SurfacePreferences.chooseExtent(480, 4000, 100));
        assertEquals(1920, SurfacePreferences.chooseExtent(1, 4000, 1920));
    }

    @Test
    void anUnconstrainedExtentTakesWhatWasAskedFor() {
        assertEquals(2560, SurfacePreferences.chooseExtent(0, SurfacePreferences.UNCONSTRAINED, 2560));
    }
}
