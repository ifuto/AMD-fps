package dev.ifuto.amdfaster.vk;

import dev.ifuto.amdfaster.AmdFaster;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormat;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkFormatProperties;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static dev.ifuto.amdfaster.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Swapchain + depth attachment with lazy recreation.
 *
 * <p>Present mode: MAILBOX when available (uncapped benchmark throughput, low
 * latency — pairs well with VK_AMD_anti_lag later), FIFO otherwise. Image
 * count is {@code min+1} clamped, the classic "don't over-queue on AMD"
 * choice: RADV's presentation path is happiest with 2–3 images.
 */
public final class Swapchain {
	private static final long UINT64_MAX = 0xFFFFFFFFFFFFFFFFL;

	private final Device device;
	private final MemoryManager memory;
	private final long surface;

	public long handle;
	public long[] images = new long[0];
	public long[] views = new long[0];
	public int imageCount;
	public int width;
	public int height;
	public int colorFormat;
	public int depthFormat;

	public long depthImage;
	public long depthView;
	private long depthMemory;

	private boolean destroyed;

	public Swapchain(Device device, MemoryManager memory, long surface) {
		this.device = device;
		this.memory = memory;
		this.surface = surface;
	}

	public void create(int reqWidth, int reqHeight) {
		try (MemoryStack stack = stackPush()) {
			VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
			check(KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device.physicalDevice, surface, caps),
					"vkGetPhysicalDeviceSurfaceCapabilitiesKHR");

			int w = caps.currentExtent().width();
			int h = caps.currentExtent().height();
			if (w == 0xFFFFFFFF) {
				w = Math.max(caps.minImageExtent().width(), Math.min(reqWidth, caps.maxImageExtent().width()));
				h = Math.max(caps.minImageExtent().height(), Math.min(reqHeight, caps.maxImageExtent().height()));
			}
			w = Math.max(1, w);
			h = Math.max(1, h);

			// --- surface format ---
			IntBuffer count = stack.mallocInt(1);
			check(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(device.physicalDevice, surface, count, null),
					"vkGetPhysicalDeviceSurfaceFormatsKHR(count)");
			int format = VK10.VK_FORMAT_B8G8R8A8_UNORM;
			int colorSpace = KHRSurface.VK_COLORSPACE_SRGB_NONLINEAR_KHR;
			if (count.get(0) > 0) {
				VkSurfaceFormat.Buffer formats = VkSurfaceFormat.malloc(count.get(0), stack);
				check(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(device.physicalDevice, surface, count, formats),
						"vkGetPhysicalDeviceSurfaceFormatsKHR");
				format = formats.get(0).format();
				colorSpace = formats.get(0).colorSpace();
				for (int i = 0; i < formats.capacity(); i++) {
					int f = formats.get(i).format();
					if (f == VK10.VK_FORMAT_B8G8R8A8_UNORM || f == VK10.VK_FORMAT_R8G8B8A8_UNORM) {
						format = f;
						colorSpace = formats.get(i).colorSpace();
						break;
					}
				}
			}

			// --- present mode ---
			check(KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(device.physicalDevice, surface, count, null),
					"vkGetPhysicalDeviceSurfacePresentModesKHR(count)");
			int presentMode = KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
			if (count.get(0) > 0) {
				IntBuffer modes = stack.mallocInt(count.get(0));
				check(KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(device.physicalDevice, surface, count, modes),
						"vkGetPhysicalDeviceSurfacePresentModesKHR");
				for (int i = 0; i < modes.capacity(); i++) {
					if (modes.get(i) == KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR) {
						presentMode = KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
						break;
					}
				}
			}

			int imageCount = caps.minImageCount() + 1;
			if (caps.maxImageCount() > 0) {
				imageCount = Math.min(imageCount, caps.maxImageCount());
			}
			imageCount = Math.max(imageCount, 2);

			int compositeAlpha = (caps.supportedCompositeAlpha() & KHRSurface.VK_SURFACE_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) != 0
					? KHRSurface.VK_SURFACE_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
					: KHRSurface.VK_SURFACE_COMPOSITE_ALPHA_INHERIT_BIT_KHR;

			VkSwapchainCreateInfoKHR ci = VkSwapchainCreateInfoKHR.calloc(stack)
					.sType(KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
					.surface(surface)
					.minImageCount(imageCount)
					.imageFormat(format)
					.imageColorSpace(colorSpace)
					.imageExtent(it -> it.set(w, h))
					.imageArrayLayers(1)
					.imageUsage(VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
					.imageSharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
					.preTransform((caps.supportedTransforms() & caps.currentTransform()) != 0
							? caps.currentTransform() : KHRSurface.VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
					.compositeAlpha(compositeAlpha)
					.presentMode(presentMode)
					.clipped(true)
					.oldSwapchain(destroyed ? 0L : handle);

			if (device.graphicsFamily != device.presentFamily) {
				ci.imageSharingMode(VK10.VK_SHARING_MODE_CONCURRENT)
						.pQueueFamilyIndices(stack.ints(device.graphicsFamily, device.presentFamily));
			}

			LongBuffer pSwapchain = stack.mallocLong(1);
			check(KHRSwapchain.vkCreateSwapchainKHR(device.handle, ci, null, pSwapchain), "vkCreateSwapchainKHR");
			long old = handle;
			handle = pSwapchain.get(0);
			destroyed = false;

			destroyImageViews();
			if (old != 0L && old != handle) {
				KHRSwapchain.vkDestroySwapchainKHR(device.handle, old, null);
			}

			// --- images + views ---
			check(KHRSwapchain.vkGetSwapchainImagesKHR(device.handle, handle, count, null), "vkGetSwapchainImagesKHR(count)");
			imageCount = count.get(0);
			LongBuffer pImages = stack.mallocLong(imageCount);
			check(KHRSwapchain.vkGetSwapchainImagesKHR(device.handle, handle, count, pImages), "vkGetSwapchainImagesKHR");
			images = new long[imageCount];
			views = new long[imageCount];
			for (int i = 0; i < imageCount; i++) {
				images[i] = pImages.get(i);
				views[i] = createColorView(images[i], format, stack);
			}

			this.width = w;
			this.height = h;
			this.colorFormat = format;

			createDepthAttachment(w, h, stack);
		}
	}

	private long createColorView(long image, int format, MemoryStack stack) {
		VkImageViewCreateInfo ci = VkImageViewCreateInfo.calloc(stack)
				.sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
				.image(image)
				.viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
				.format(format);
		ci.subresourceRange()
				.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
				.baseMipLevel(0).levelCount(1)
				.baseArrayLayer(0).layerCount(1);
		LongBuffer pView = stack.mallocLong(1);
		check(VK10.vkCreateImageView(device.handle, ci, null, pView), "vkCreateImageView(swapchain)");
		return pView.get(0);
	}

	private void createDepthAttachment(int w, int h, MemoryStack stack) {
		destroyDepth(stack);

		int chosen = 0;
		int[] candidates = {
				VK10.VK_FORMAT_D32_SFLOAT,
				VK10.VK_FORMAT_D24_UNORM_S8_UINT,
				VK10.VK_FORMAT_D32_SFLOAT_S8_UINT,
				VK10.VK_FORMAT_D16_UNORM
		};
		VkFormatProperties fp = VkFormatProperties.calloc(stack);
		for (int candidate : candidates) {
			VK10.vkGetPhysicalDeviceFormatProperties(device.physicalDevice, candidate, fp);
			if ((fp.optimalTilingFeatures() & VK10.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
				chosen = candidate;
				break;
			}
		}
		if (chosen == 0) {
			throw new VkUtil.VkException("no supported depth format", VK10.VK_ERROR_FORMAT_NOT_SUPPORTED);
		}
		this.depthFormat = chosen;

		VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack)
				.sType(VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
				.imageType(VK10.VK_IMAGE_TYPE_2D)
				.format(chosen)
				.extent(e -> e.set(w, h, 1))
				.mipLevels(1)
				.arrayLayers(1)
				.samples(VK10.VK_SAMPLE_COUNT_1_BIT)
				.tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
				.usage(VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)
				.initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
		LongBuffer pImage = stack.mallocLong(1);
		check(VK10.vkCreateImage(device.handle, ici, null, pImage), "vkCreateImage(depth)");
		depthImage = pImage.get(0);

		VkMemoryRequirements reqs = VkMemoryRequirements.malloc(stack);
		VK10.vkGetImageMemoryRequirements(device.handle, depthImage, reqs);
		int type = device.findMemoryType(reqs.memoryTypeBits(), VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(stack)
				.sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
				.allocationSize(reqs.size())
				.memoryTypeIndex(type);
		LongBuffer pMem = stack.mallocLong(1);
		check(VK10.vkAllocateMemory(device.handle, ai, null, pMem), "vkAllocateMemory(depth)");
		depthMemory = pMem.get(0);
		check(VK10.vkBindImageMemory(device.handle, depthImage, depthMemory, 0), "vkBindImageMemory(depth)");

		int aspect = chosen == VK10.VK_FORMAT_D24_UNORM_S8_UINT || chosen == VK10.VK_FORMAT_D32_SFLOAT_S8_UINT
				? VK10.VK_IMAGE_ASPECT_DEPTH_BIT | VK10.VK_IMAGE_ASPECT_STENCIL_BIT
				: VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
		VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(stack)
				.sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
				.image(depthImage)
				.viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
				.format(chosen);
		vci.subresourceRange()
				.aspectMask(aspect)
				.baseMipLevel(0).levelCount(1)
				.baseArrayLayer(0).layerCount(1);
		LongBuffer pView = stack.mallocLong(1);
		check(VK10.vkCreateImageView(device.handle, vci, null, pView), "vkCreateImageView(depth)");
		depthView = pView.get(0);
	}

	/** @return acquired image index, or -1 when the swapchain was out-of-date and has been recreated (skip the frame). */
	public int acquire(long imageAvailableSemaphore, int fallbackWidth, int fallbackHeight) {
		try (MemoryStack stack = stackPush()) {
			IntBuffer pIndex = stack.mallocInt(1);
			int result = KHRSwapchain.vkAcquireNextImageKHR(device.handle, handle, UINT64_MAX,
					imageAvailableSemaphore, 0L, pIndex);
			if (VkUtil.isOutdated(result)) {
				recreate(fallbackWidth, fallbackHeight);
				return -1;
			}
			check(result, "vkAcquireNextImageKHR");
			return pIndex.get(0);
		}
	}

	public boolean present(org.lwjgl.vulkan.VkQueue queue, int imageIndex, long waitSemaphore) {
		try (MemoryStack stack = stackPush()) {
			VkPresentInfoKHR pi = VkPresentInfoKHR.calloc(stack)
					.sType(KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
					.pWaitSemaphores(stack.longs(waitSemaphore))
					.pSwapchains(stack.longs(handle))
					.pImageIndices(stack.ints(imageIndex));
			int result = KHRSwapchain.vkQueuePresentKHR(queue, pi);
			if (VkUtil.isOutdated(result)) {
				return false; // caller recreates at the next frame boundary
			}
			check(result, "vkQueuePresentKHR");
			return true;
		}
	}

	public void recreate(int w, int h) {
		try {
			VK10.vkDeviceWaitIdle(device.handle);
		} catch (Throwable ignored) {
		}
		create(Math.max(1, w), Math.max(1, h));
	}

	private void destroyImageViews() {
		for (long view : views) {
			if (view != 0L) {
				VK10.vkDestroyImageView(device.handle, view, null);
			}
		}
		views = new long[0];
		images = new long[0];
	}

	private void destroyDepth(MemoryStack stack) {
		if (depthView != 0L) {
			VK10.vkDestroyImageView(device.handle, depthView, null);
			depthView = 0L;
		}
		if (depthImage != 0L) {
			VK10.vkDestroyImage(device.handle, depthImage, null);
			depthImage = 0L;
		}
		if (depthMemory != 0L) {
			VK10.vkFreeMemory(device.handle, depthMemory, null);
			depthMemory = 0L;
		}
	}

	public void destroy() {
		try (MemoryStack stack = stackPush()) {
			VK10.vkDeviceWaitIdle(device.handle);
			destroyImageViews();
			destroyDepth(stack);
			if (handle != 0L) {
				KHRSwapchain.vkDestroySwapchainKHR(device.handle, handle, null);
				handle = 0L;
			}
			destroyed = true;
		} catch (Throwable t) {
			AmdFaster.LOGGER.warn("[AMD-Faster] swapchain teardown issue: {}", t.toString());
		}
	}
}
