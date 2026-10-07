package net.amdfaster.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkComponentMapping;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.VK_COLORSPACE_SRGB_NONLINEAR_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkCreateSwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkDestroySwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkGetSwapchainImagesKHR;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;

/**
 * The window's images, and the choices made about them.
 *
 * <p>Every choice is delegated to {@link SurfacePreferences}, so the reasoning behind picking an
 * sRGB image, or FIFO over mailbox on an APU, is testable without a window. This class only asks
 * the driver what the surface offers and applies the answer.
 *
 * <p>Recreation is not implemented yet. A resize or a {@code VK_SUBOPTIMAL_KHR} present has to
 * rebuild this, and doing it wrong shows up as a hang or a black window rather than an exception,
 * so it is worth its own change rather than being folded in here.
 */
public final class Swapchain implements AutoCloseable {

    private final VkContext context;
    private final long handle;
    private final long[] images;
    private final long[] imageViews;
    private final int width;
    private final int height;
    private final int format;
    private final int colorSpace;
    private final int presentMode;
    private final String formatReason;

    private Swapchain(VkContext context, long handle, long[] images, long[] imageViews, int width,
                      int height, int format, int colorSpace, int presentMode, String formatReason) {
        this.context = context;
        this.handle = handle;
        this.images = images;
        this.imageViews = imageViews;
        this.width = width;
        this.height = height;
        this.format = format;
        this.colorSpace = colorSpace;
        this.presentMode = presentMode;
        this.formatReason = formatReason;
    }

    public static Swapchain create(VkContext context, int desiredWidth, int desiredHeight) {
        try (MemoryStack stack = stackPush()) {
            long surface = context.surface();

            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
            if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(context.physicalDevice(), surface, caps)
                    != VK_SUCCESS) {
                throw new IllegalStateException("vkGetPhysicalDeviceSurfaceCapabilitiesKHR failed");
            }
            if (caps.minImageCount() == 0) {
                throw new IllegalStateException("surface reports no images; is the window minimised?");
            }

            SurfacePreferences.FormatChoice choice = chooseFormat(context, surface, stack);
            int presentMode = choosePresentMode(context, surface, stack);
            int imageCount = SurfacePreferences.chooseImageCount(caps.minImageCount(), caps.maxImageCount());

            int width = caps.currentExtent().width();
            int height = caps.currentExtent().height();
            if (width == SurfacePreferences.UNCONSTRAINED || height == SurfacePreferences.UNCONSTRAINED) {
                // The surface is letting us choose, which on Wayland and X11 means it will accept
                // whatever we render and scale it.
                width = SurfacePreferences.chooseExtent(caps.minImageExtent().width(),
                        caps.maxImageExtent().width(), desiredWidth);
                height = SurfacePreferences.chooseExtent(caps.minImageExtent().height(),
                        caps.maxImageExtent().height(), desiredHeight);
            }

            int transform = (caps.supportedTransforms() & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) != 0
                    ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                    : caps.currentTransform();
            int compositeAlpha = (caps.supportedCompositeAlpha() & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) != 0
                    ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
                    : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .surface(surface)
                    .minImageCount(imageCount)
                    .imageFormat(choice.format())
                    .imageColorSpace(choice.colorSpace())
                    .imageExtent(VkExtent2D.malloc(stack).width(width).height(height))
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(transform)
                    .compositeAlpha(compositeAlpha)
                    .presentMode(presentMode)
                    .clipped(true)
                    .oldSwapchain(0L);

            LongBuffer pSwapchain = stack.mallocLong(1);
            int result = vkCreateSwapchainKHR(context.device(), createInfo, null, pSwapchain);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("vkCreateSwapchainKHR failed with VkResult " + result);
            }
            long handle = pSwapchain.get(0);

            long[] images;
            long[] views;
            try {
                images = swapchainImages(context, handle, stack);
                views = new long[images.length];
                for (int i = 0; i < images.length; i++) {
                    views[i] = createImageView(context, images[i], choice.format(), stack);
                }
            } catch (RuntimeException e) {
                vkDestroySwapchainKHR(context.device(), handle, null);
                throw e;
            }
            return new Swapchain(context, handle, images, views, width, height, choice.format(),
                    choice.colorSpace(), presentMode, choice.reason());
        }
    }

    private static SurfacePreferences.FormatChoice chooseFormat(VkContext context, long surface,
                                                                MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        if (vkGetPhysicalDeviceSurfaceFormatsKHR(context.physicalDevice(), surface, count, null) != VK_SUCCESS
                || count.get(0) == 0) {
            return new SurfacePreferences.FormatChoice(SurfacePreferences.VK_FORMAT_B8G8R8A8_SRGB,
                    VK_COLORSPACE_SRGB_NONLINEAR_KHR, "surface reported no formats; assuming BGRA sRGB");
        }
        VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
        vkGetPhysicalDeviceSurfaceFormatsKHR(context.physicalDevice(), surface, count, formats);

        List<SurfacePreferences.SurfaceFormat> offered = new ArrayList<>(formats.capacity());
        for (int i = 0; i < formats.capacity(); i++) {
            VkSurfaceFormatKHR f = formats.get(i);
            offered.add(new SurfacePreferences.SurfaceFormat(f.format(), f.colorSpace()));
        }
        return SurfacePreferences.chooseFormat(offered);
    }

    private static int choosePresentMode(VkContext context, long surface, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        if (vkGetPhysicalDeviceSurfacePresentModesKHR(context.physicalDevice(), surface, count, null)
                != VK_SUCCESS || count.get(0) == 0) {
            return VK_PRESENT_MODE_FIFO_KHR;
        }
        IntBuffer modes = stack.mallocInt(count.get(0));
        vkGetPhysicalDeviceSurfacePresentModesKHR(context.physicalDevice(), surface, count, modes);
        int[] available = new int[modes.capacity()];
        for (int i = 0; i < modes.capacity(); i++) {
            available[i] = modes.get(i);
        }
        // An APU shares its power and thermal budget with the CPU, so uncapped present is the
        // wrong default there even though mailbox is lower latency.
        boolean unified = MemoryTypeSelector.hasUnifiedHostDeviceMemory(context.memoryTypes());
        return SurfacePreferences.choosePresentMode(available, unified);
    }

    private static long[] swapchainImages(VkContext context, long swapchain, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        if (vkGetSwapchainImagesKHR(context.device(), swapchain, count, null) != VK_SUCCESS
                || count.get(0) == 0) {
            throw new IllegalStateException("vkGetSwapchainImagesKHR returned no images");
        }
        LongBuffer images = stack.mallocLong(count.get(0));
        vkGetSwapchainImagesKHR(context.device(), swapchain, count, images);
        long[] out = new long[images.capacity()];
        for (int i = 0; i < out.length; i++) {
            out[i] = images.get(i);
        }
        return out;
    }

    private static long createImageView(VkContext context, long image, int format, MemoryStack stack) {
        VkImageViewCreateInfo createInfo = VkImageViewCreateInfo.calloc(stack)
                .sType$Default()
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format)
                .components(VkComponentMapping.calloc(stack))
                .subresourceRange(VkImageSubresourceRange.malloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1));
        LongBuffer pView = stack.mallocLong(1);
        int result = vkCreateImageView(context.device(), createInfo, null, pView);
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("vkCreateImageView failed with VkResult " + result);
        }
        return pView.get(0);
    }

    public long handle() {
        return this.handle;
    }

    public int imageCount() {
        return this.images.length;
    }

    public long image(int index) {
        return this.images[index];
    }

    public long imageView(int index) {
        return this.imageViews[index];
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public int format() {
        return this.format;
    }

    public int colorSpace() {
        return this.colorSpace;
    }

    public int presentMode() {
        return this.presentMode;
    }

    /** Why this format was picked, for a report. */
    public String formatReason() {
        return this.formatReason;
    }

    @Override
    public void close() {
        for (long view : this.imageViews) {
            if (view != 0L) {
                vkDestroyImageView(this.context.device(), view, null);
            }
        }
        if (this.handle != 0L) {
            vkDestroySwapchainKHR(this.context.device(), this.handle, null);
        }
    }
}
