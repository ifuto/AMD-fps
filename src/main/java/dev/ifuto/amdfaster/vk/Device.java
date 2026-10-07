package dev.ifuto.amdfaster.vk;

import dev.ifuto.amdfaster.AmdFaster;
import dev.ifuto.amdfaster.config.AmdFasterConfig;
import dev.ifuto.amdfaster.platform.AmdArchitecture;
import dev.ifuto.amdfaster.platform.AmdTuning;
import dev.ifuto.amdfaster.platform.GpuProbe;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.KHRTimelineSemaphore;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceDriverProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceDynamicRenderingFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceSynchronization2FeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceTimelineSemaphoreFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.ifuto.amdfaster.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Physical device selection + logical device.
 *
 * <p>Selection policy ("auto" backend): among present-capable devices, AMD wins
 * (this mod exists to make the red team fast — Intel/NVIDIA still work, the
 * tuning tables simply go quiet), then discrete over integrated, then the
 * higher Vulkan version. Hybrid laptops therefore land on the AMD dGPU.
 *
 * <p>Requires Vulkan 1.2 minimum; 1.3 is used when available (dynamic rendering,
 * synchronization2 and timeline semaphores straight from core). On 1.2 the same
 * features come from their KHR extension structs while the promoted entry points
 * are still called through VK12/VK13 — the loader trampolines the aliases.
 */
public final class Device {
	public final VkPhysicalDevice physicalDevice;
	public final VkDevice handle;
	public final VkQueue graphicsQueue;
	public final VkQueue presentQueue;
	public final int graphicsFamily;
	public final int presentFamily;
	public final int apiVersion;
	public final boolean has13;

	/** Heap-allocated so they outlive the creating stack frame; freed in {@link #close()}. */
	public final VkPhysicalDeviceMemoryProperties memoryProperties;
	public final VkPhysicalDeviceProperties properties;

	public final AmdTuning tuning;
	public final AmdArchitecture architecture;

	/** True when VK_EXT_memory_budget is enabled (live VRAM readouts for the overlay). */
	public final boolean hasMemoryBudget;

	public Device(Instance instance, AmdFasterConfig cfg) {
		List<VkPhysicalDevice> gpus = enumerate(instance);
		if (gpus.isEmpty()) {
			throw new VkUtil.VkException("no Vulkan physical devices", VK10.VK_ERROR_INITIALIZATION_FAILED);
		}

		VkPhysicalDevice best = null;
		long bestScore = Long.MIN_VALUE;
		try (MemoryStack stack = stackPush()) {
			VkPhysicalDeviceProperties probe = VkPhysicalDeviceProperties.malloc(stack);
			for (VkPhysicalDevice gpu : gpus) {
				VK10.vkGetPhysicalDeviceProperties(gpu, probe);
				if (probe.apiVersion() < VkUtil.vkMakeVersion(1, 2, 0)) {
					continue; // no timeline semaphores etc. — too old to be worth the special-casing
				}
				if (!hasPresentSupport(gpu, instance.surface, stack)) {
					continue;
				}
				long score = score(probe, cfg);
				if (score > bestScore) {
					bestScore = score;
					best = gpu;
				}
			}
		}
		if (best == null) {
			throw new VkUtil.VkException("no present-capable Vulkan 1.2+ device found", VK10.VK_ERROR_INITIALIZATION_FAILED);
		}
		this.physicalDevice = best;

		// Persistent property structs (heap, not stack).
		this.properties = VkPhysicalDeviceProperties.malloc();
		VK10.vkGetPhysicalDeviceProperties(best, this.properties);
		this.memoryProperties = VkPhysicalDeviceMemoryProperties.malloc();
		VK10.vkGetPhysicalDeviceMemoryProperties(best, this.memoryProperties);

		this.apiVersion = properties.apiVersion();
		this.has13 = apiVersion >= VkUtil.vkMakeVersion(1, 3, 0);

		// Subgroup size (wave32 on RDNA vs wave64 on GCN) and driver identity (RADV vs AMDVLK).
		try (MemoryStack stack = stackPush()) {
			VkPhysicalDeviceSubgroupProperties subgroup = VkPhysicalDeviceSubgroupProperties.calloc(stack)
					.sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES);
			VkPhysicalDeviceDriverProperties driver = VkPhysicalDeviceDriverProperties.calloc(stack)
					.sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES);
			subgroup.pNext(driver.address());
			VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack)
					.sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
					.pNext(subgroup.address())
					.properties(properties);
			VK11.vkGetPhysicalDeviceProperties2(best, props2);

			GpuProbe.vkVendorId = properties.vendorID();
			GpuProbe.vkDeviceId = properties.deviceID();
			GpuProbe.vkDeviceName = properties.deviceNameString();
			GpuProbe.vkSubgroupSize = subgroup.subgroupSize();
			GpuProbe.vkApiVersion = VkUtil.apiVersionString(apiVersion);
			GpuProbe.vkDriverName = driver.driverNameString();
			GpuProbe.vkDriverInfo = driver.driverInfoString();
			if (apiVersion < VkUtil.vkMakeVersion(1, 2, 0)) {
				// DriverProperties is a 1.2 struct; blank it out on 1.2-less drivers (we require 1.2 anyway).
				GpuProbe.vkDriverName = "";
			}
		}

		this.architecture = AmdArchitecture.classify(GpuProbe.vkVendorId, GpuProbe.vkDeviceId,
				GpuProbe.glRenderer, GpuProbe.vkDeviceName);

		try (MemoryStack stack = stackPush()) {
			// --- queue families -------------------------------------------------
			int graphicsFamily = -1;
			int presentFamily = -1;
			IntBuffer count = stack.mallocInt(1);
			VK10.vkGetPhysicalDeviceQueueFamilyProperties(best, count, null);
			VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
			VK10.vkGetPhysicalDeviceQueueFamilyProperties(best, count, families);
			IntBuffer supported = stack.mallocInt(1);
			for (int i = 0; i < families.capacity(); i++) {
				boolean graphics = (families.get(i).queueFlags() & VK10.VK_QUEUE_GRAPHICS_BIT) != 0;
				supported.position(0);
				check(KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(best, i, instance.surface, supported),
						"vkGetPhysicalDeviceSurfaceSupportKHR");
				boolean present = supported.get(0) == VK10.VK_TRUE;
				if (graphics && present && graphicsFamily == -1) {
					graphicsFamily = i;
					presentFamily = i;
					break; // AMD's universal queue — one family does everything
				}
				if (graphics && graphicsFamily == -1) {
					graphicsFamily = i;
				}
				if (present && presentFamily == -1) {
					presentFamily = i;
				}
			}
			if (graphicsFamily == -1 || presentFamily == -1) {
				throw new VkUtil.VkException("no suitable queue families", VK10.VK_ERROR_INITIALIZATION_FAILED);
			}
			this.graphicsFamily = graphicsFamily;
			this.presentFamily = presentFamily;

			// --- device extensions ----------------------------------------------
			Set<String> available = deviceExtensions(best, stack);
			List<String> wanted = new ArrayList<>();
			wanted.add(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME);
			if (!has13) {
				wanted.add(KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME);
				wanted.add(KHRSynchronization2.VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME);
			}
			if (available.contains("VK_EXT_memory_budget")) {
				wanted.add("VK_EXT_memory_budget");
			}
			if (cfg.experimentalAmdOverallocation && GpuProbe.isRadv()
					&& available.contains("VK_AMD_memory_overallocation_behavior")) {
				wanted.add("VK_AMD_memory_overallocation_behavior");
			}
			this.hasMemoryBudget = wanted.contains("VK_EXT_memory_budget");

			PointerBuffer extBuf = stack.mallocPointer(wanted.size());
			for (String ext : wanted) {
				extBuf.put(stack.UTF8(ext));
			}
			extBuf.flip();

			// --- features --------------------------------------------------------
			VkPhysicalDeviceFeatures supportedFeatures = VkPhysicalDeviceFeatures.calloc(stack);
			VK10.vkGetPhysicalDeviceFeatures(best, supportedFeatures);
			VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack)
					.samplerAnisotropy(supportedFeatures.samplerAnisotropy())
					.multiDrawIndirect(supportedFeatures.multiDrawIndirect());

			long featureChain;
			if (has13) {
				VkPhysicalDeviceVulkan13Features f13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
						.sType(VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
						.synchronization2(true)
						.dynamicRendering(true);
				VkPhysicalDeviceVulkan12Features f12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
						.sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES)
						.timelineSemaphore(true)
						.pNext(f13.address());
				featureChain = f12.address();
			} else {
				VkPhysicalDeviceDynamicRenderingFeaturesKHR dr = VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack)
						.sType(KHRDynamicRendering.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES_KHR)
						.dynamicRendering(true);
				VkPhysicalDeviceSynchronization2FeaturesKHR sync2 = VkPhysicalDeviceSynchronization2FeaturesKHR.calloc(stack)
						.sType(KHRSynchronization2.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES_KHR)
						.synchronization2(true)
						.pNext(dr.address());
				VkPhysicalDeviceTimelineSemaphoreFeaturesKHR ts = VkPhysicalDeviceTimelineSemaphoreFeaturesKHR.calloc(stack)
						.sType(KHRTimelineSemaphore.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES_KHR)
						.timelineSemaphore(true)
						.pNext(sync2.address());
				featureChain = ts.address();
			}

			VkDeviceQueueCreateInfo.Buffer queueCIs;
			if (graphicsFamily == presentFamily) {
				queueCIs = VkDeviceQueueCreateInfo.calloc(1, stack)
						.sType(VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
						.queueFamilyIndex(graphicsFamily)
						.pQueuePriorities(stack.floats(1.0f));
			} else {
				queueCIs = VkDeviceQueueCreateInfo.calloc(2, stack);
				queueCIs.get(0)
						.sType(VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
						.queueFamilyIndex(graphicsFamily)
						.pQueuePriorities(stack.floats(1.0f));
				queueCIs.get(1)
						.sType(VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
						.queueFamilyIndex(presentFamily)
						.pQueuePriorities(stack.floats(1.0f));
			}

			VkDeviceCreateInfo ci = VkDeviceCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
					.pQueueCreateInfos(queueCIs)
					.ppEnabledExtensionNames(extBuf)
					.pEnabledFeatures(features)
					.pNext(featureChain);

			LongBuffer pDevice = stack.mallocLong(1);
			check(VK10.vkCreateDevice(best, ci, null, pDevice), "vkCreateDevice");
			this.handle = new VkDevice(pDevice.get(0), best, ci);

			LongBuffer pQueue = stack.mallocLong(1);
			VK10.vkGetDeviceQueue(handle, graphicsFamily, 0, pQueue);
			this.graphicsQueue = new VkQueue(pQueue.get(0), handle);
			if (presentFamily == graphicsFamily) {
				this.presentQueue = this.graphicsQueue;
			} else {
				VK10.vkGetDeviceQueue(handle, presentFamily, 0, pQueue);
				this.presentQueue = new VkQueue(pQueue.get(0), handle);
			}
		}

		this.tuning = AmdTuning.derive(architecture, cfg,
				largestHeapBytes(VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT),
				largestHeapBytes(VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));

		AmdFaster.LOGGER.info("[AMD-Faster] device '{}' vendor=0x{} device=0x{} api={} driver='{}' wave={}",
				GpuProbe.vkDeviceName, Integer.toHexString(GpuProbe.vkVendorId), Integer.toHexString(GpuProbe.vkDeviceId),
				GpuProbe.vkApiVersion, GpuProbe.vkDriverName, GpuProbe.vkSubgroupSize);
		AmdFaster.LOGGER.info("[AMD-Faster] arch={} | {}", architecture.label, tuning.summary);
	}

	/** Biggest heap size among memory types carrying all of {@code requiredProps}. */
	public long largestHeapBytes(int requiredProps) {
		long best = 0;
		Set<Integer> seen = new HashSet<>();
		for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
			int flags = memoryProperties.memoryTypes(i).propertyFlags();
			if ((flags & requiredProps) != requiredProps) {
				continue;
			}
			int heap = memoryProperties.memoryTypes(i).heapIndex();
			if (!seen.add(heap)) {
				continue;
			}
			long size = memoryProperties.memoryHeaps(heap).size();
			if (size > best) {
				best = size;
			}
		}
		return best;
	}

	/** First memory type index matching {@code typeBits} and containing all {@code requiredProps}, or -1. */
	public int findMemoryType(int typeBits, int requiredProps) {
		for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
			if ((typeBits & (1 << i)) != 0
					&& (memoryProperties.memoryTypes(i).propertyFlags() & requiredProps) == requiredProps) {
				return i;
			}
		}
		return -1;
	}

	private static long score(VkPhysicalDeviceProperties props, AmdFasterConfig cfg) {
		long score = 0;
		if (cfg.preferAmdDevice && props.vendorID() == GpuProbe.VENDOR_ID_AMD) {
			score += 100_000;
		}
		switch (props.deviceType()) {
			case VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU:
				score += 10_000;
				break;
			case VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU:
				score += 5_000;
				break;
			case VK10.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU:
				score += 1_000;
				break;
			default:
				break;
		}
		score += VK10.VK_VERSION_MAJOR(props.apiVersion()) * 100L + VK10.VK_VERSION_MINOR(props.apiVersion());
		return score;
	}

	private static boolean hasPresentSupport(VkPhysicalDevice gpu, long surface, MemoryStack stack) {
		IntBuffer count = stack.mallocInt(1);
		VK10.vkGetPhysicalDeviceQueueFamilyProperties(gpu, count, null);
		VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
		VK10.vkGetPhysicalDeviceQueueFamilyProperties(gpu, count, families);
		IntBuffer supported = stack.mallocInt(1);
		for (int i = 0; i < families.capacity(); i++) {
			if (KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(gpu, i, surface, supported) == VK10.VK_SUCCESS
					&& supported.get(0) == VK10.VK_TRUE) {
				return true;
			}
		}
		return false;
	}

	private static List<VkPhysicalDevice> enumerate(Instance instance) {
		List<VkPhysicalDevice> out = new ArrayList<>();
		try (MemoryStack stack = stackPush()) {
			IntBuffer count = stack.mallocInt(1);
			check(VK10.vkEnumeratePhysicalDevices(instance.handle, count, null), "vkEnumeratePhysicalDevices");
			if (count.get(0) == 0) {
				return out;
			}
			PointerBuffer devices = stack.mallocPointer(count.get(0));
			check(VK10.vkEnumeratePhysicalDevices(instance.handle, count, devices), "vkEnumeratePhysicalDevices");
			for (int i = 0; i < count.get(0); i++) {
				out.add(new VkPhysicalDevice(devices.get(i), instance.handle));
			}
		}
		return out;
	}

	private static Set<String> deviceExtensions(VkPhysicalDevice gpu, MemoryStack stack) {
		Set<String> out = new HashSet<>();
		IntBuffer count = stack.mallocInt(1);
		check(VK10.vkEnumerateDeviceExtensionProperties(gpu, (String) null, count, null), "vkEnumerateDeviceExtensionProperties");
		if (count.get(0) == 0) {
			return out;
		}
		VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0), stack);
		check(VK10.vkEnumerateDeviceExtensionProperties(gpu, (String) null, count, props), "vkEnumerateDeviceExtensionProperties");
		for (int i = 0; i < props.capacity(); i++) {
			out.add(props.get(i).extensionNameString());
		}
		return out;
	}

	public void close() {
		VK10.vkDestroyDevice(handle, null);
		memoryProperties.free();
		properties.free();
	}
}
