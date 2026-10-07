package net.amdfaster.vk;

import java.util.List;

/**
 * Which surface format, present mode, image count and extent to ask for.
 *
 * <p>Kept free of Vulkan types so the reasoning is testable. Two of these choices are AMD-specific
 * and neither is obvious from the spec:
 *
 * <ul>
 *   <li><b>An sRGB swapchain image is free.</b> The colour block applies the transfer function on
 *       write, so a renderer that works in linear light gets correct output without a fullscreen
 *       encode pass. BGRA is preferred over RGBA because it is the order the window system already
 *       uses, so no swizzle is inserted.</li>
 *   <li><b>Mailbox present is the right default on a discrete card and the wrong one on an APU.</b>
 *       It never waits, which on an integrated part means rendering flat out into a thermal and
 *       power budget shared with the CPU -- exactly the machines this mod has to be good on. So a
 *       unified-memory device gets FIFO and a discrete one gets mailbox.</li>
 * </ul>
 */
public final class SurfacePreferences {

    // Mirrors VkFormat, so this class stays testable without a device.
    public static final int VK_FORMAT_B8G8R8A8_SRGB = 50;
    public static final int VK_FORMAT_R8G8B8A8_SRGB = 43;
    public static final int VK_FORMAT_B8G8R8A8_UNORM = 44;
    public static final int VK_FORMAT_R8G8B8A8_UNORM = 37;
    public static final int VK_FORMAT_A2B10G10R10_UNORM_PACK32 = 64;

    /** Swapchain image formats in preference order; the colour space is assumed sRGB-nonlinear. */
    public static final List<Integer> PREFERRED_FORMATS = List.of(
            VK_FORMAT_B8G8R8A8_SRGB,
            VK_FORMAT_R8G8B8A8_SRGB,
            VK_FORMAT_B8G8R8A8_UNORM,
            VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_A2B10G10R10_UNORM_PACK32);

    // Mirrors VkPresentModeKHR.
    public static final int PRESENT_MODE_IMMEDIATE = 0;
    public static final int PRESENT_MODE_MAILBOX = 1;
    public static final int PRESENT_MODE_FIFO = 2;
    public static final int PRESENT_MODE_FIFO_RELAXED = 3;

    /** {@code VK_COLORSPACE_SRGB_NONLINEAR_KHR}. */
    public static final int COLORSPACE_SRGB_NONLINEAR = 0;

    /** How many swapchain images to ask for when the surface allows it. */
    public static final int DESIRED_IMAGE_COUNT = 3;

    /** Sentinel for "the surface does not constrain this axis". */
    public static final int UNCONSTRAINED = 0xFFFFFFFF;

    /** A format the surface offers, as the driver reported it. */
    public record SurfaceFormat(int format, int colorSpace) {
    }

    /** The chosen format, plus why, so a report can show what the surface actually offered. */
    public record FormatChoice(int format, int colorSpace, String reason) {
    }

    private SurfacePreferences() {
    }

    /**
     * @param offered what the surface supports; a single entry with format 0 means "anything"
     */
    public static FormatChoice chooseFormat(List<SurfaceFormat> offered) {
        if (offered.isEmpty()) {
            throw new IllegalStateException("surface offers no formats");
        }
        // A surface that reports format 0 with the sRGB colour space accepts whatever we want.
        if (offered.size() == 1 && offered.get(0).format() == 0) {
            return new FormatChoice(VK_FORMAT_B8G8R8A8_SRGB, COLORSPACE_SRGB_NONLINEAR,
                    "surface accepts any format; taking BGRA sRGB");
        }
        for (int wanted : PREFERRED_FORMATS) {
            for (SurfaceFormat f : offered) {
                if (f.format() == wanted && f.colorSpace() == COLORSPACE_SRGB_NONLINEAR) {
                    return new FormatChoice(f.format(), f.colorSpace(), "preferred format available");
                }
            }
        }
        // Nothing preferred: take the first with the colour space we can encode into, else the
        // very first, and let the shader deal with it.
        for (SurfaceFormat f : offered) {
            if (f.colorSpace() == COLORSPACE_SRGB_NONLINEAR) {
                return new FormatChoice(f.format(), f.colorSpace(),
                        "no preferred format; using the first with an sRGB colour space");
            }
        }
        SurfaceFormat fallback = offered.get(0);
        return new FormatChoice(fallback.format(), fallback.colorSpace(),
                "no preferred format and no sRGB colour space; using " + fallback.format());
    }

    /**
     * @param unifiedMemory true on an APU or a card with Resizable BAR, where the GPU shares a
     *                      power and thermal budget with the CPU
     */
    public static int choosePresentMode(int[] available, boolean unifiedMemory) {
        int want = unifiedMemory ? PRESENT_MODE_FIFO : PRESENT_MODE_MAILBOX;
        for (int mode : available) {
            if (mode == want) {
                return mode;
            }
        }
        for (int mode : available) {
            if (mode == PRESENT_MODE_FIFO) {
                return mode;   // always required to exist by the spec
            }
        }
        if (available.length == 0) {
            throw new IllegalStateException("surface offers no present modes");
        }
        return available[0];
    }

    /** Triple buffering if allowed, otherwise as many as the surface permits. */
    public static int chooseImageCount(int minImageCount, int maxImageCount) {
        if (maxImageCount == 0) {
            // Zero means "no upper bound".
            return Math.max(minImageCount, DESIRED_IMAGE_COUNT);
        }
        return Math.min(Math.max(minImageCount, DESIRED_IMAGE_COUNT), maxImageCount);
    }

    /** Clamps the wanted extent into what the surface allows. */
    public static int chooseExtent(int minExtent, int maxExtent, int desired) {
        if (maxExtent == UNCONSTRAINED) {
            return Math.max(minExtent, desired);
        }
        return Math.min(Math.max(minExtent, desired), maxExtent);
    }
}
