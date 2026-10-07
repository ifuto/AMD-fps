package dev.ifuto.amdfaster.vk;

import dev.ifuto.amdfaster.AmdFaster;
import dev.ifuto.amdfaster.config.AmdFasterConfig;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.ifuto.amdfaster.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memUTF8;

/**
 * Vulkan instance + GLFW window surface + optional debug messenger.
 *
 * <p>The instance targets the highest API version the loader supports, capped
 * at Vulkan 1.3 — dynamic rendering and timeline semaphores are core from 1.3
 * and AMD drivers (RADV since Mesa 22, AMDVLK since 2022, Adrenalin since
 * 22.x) all expose 1.3. Devices stuck on 1.2 fall back to the KHR extension
 * structs in {@link Device}.
 */
public final class Instance {
	public final VkInstance handle;
	public final long surface;
	public final int apiVersion;

	private final boolean debugEnabled;
	private long debugMessenger = 0L;
	private VkDebugUtilsMessengerCallbackEXT debugCallback;

	public Instance(AmdFasterConfig cfg, long windowHandle) {
		int requestedApi = VkUtil.vkMakeVersion(1, 3, 0);
		int loaderApi = VK10.VK_API_VERSION_1_0;
		try (MemoryStack stack = stackPush()) {
			IntBuffer pApi = stack.mallocInt(1);
			if (VK11.vkEnumerateInstanceVersion(pApi) == VK10.VK_SUCCESS) {
				loaderApi = pApi.get(0);
			}
		} catch (Throwable t) {
			// Loader predates Vulkan 1.1 — assume 1.0 and let device creation fail loudly.
			AmdFaster.LOGGER.warn("[AMD-Faster] vkEnumerateInstanceVersion unavailable: {}", t.toString());
		}
		this.apiVersion = Math.min(loaderApi, requestedApi);

		try (MemoryStack stack = stackPush()) {
			VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
					.pApplicationName(stack.UTF8("Minecraft 1.21.11"))
					.applicationVersion(0)
					.pEngineName(stack.UTF8("AMD-Faster"))
					.engineVersion(VkUtil.vkMakeVersion(0, 1, 0))
					.apiVersion(this.apiVersion);

			Set<String> extensions = new HashSet<>();
			PointerBuffer glfwExts = GLFWVulkan.glfwGetRequiredInstanceExtensions();
			if (glfwExts == null) {
				throw new VkUtil.VkException("GLFW reports no Vulkan platform support (glfwGetRequiredInstanceExtensions)", VK10.VK_ERROR_INITIALIZATION_FAILED);
			}
			for (int i = 0; i < glfwExts.remaining(); i++) {
				extensions.add(memUTF8(glfwExts.get(i)));
			}
			this.debugEnabled = cfg.debugUtils || cfg.validationLayer;
			if (this.debugEnabled) {
				extensions.add(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
			}

			PointerBuffer extBuf = stack.mallocPointer(extensions.size());
			for (String ext : extensions) {
				extBuf.put(stack.UTF8(ext));
			}
			extBuf.flip();

			List<String> layers = new ArrayList<>(2);
			if (cfg.validationLayer) {
				layers.add("VK_LAYER_KHRONOS_validation");
			}
			PointerBuffer layerBuf = null;
			if (!layers.isEmpty()) {
				layerBuf = stack.mallocPointer(layers.size());
				for (String l : layers) {
					layerBuf.put(stack.UTF8(l));
				}
				layerBuf.flip();
			}

			VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack)
					.sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
					.pApplicationInfo(appInfo)
					.ppEnabledExtensionNames(extBuf)
					.ppEnabledLayerNames(layerBuf);

			LongBuffer pInstance = stack.mallocLong(1);
			int result = VK10.vkCreateInstance(ci, null, pInstance);
			if (result == VK10.VK_ERROR_LAYER_NOT_PRESENT) {
				// Validation layer requested but not installed — retry without layers.
				AmdFaster.LOGGER.warn("[AMD-Faster] validation layer not present, continuing without it");
				ci.ppEnabledLayerNames(null);
				result = VK10.vkCreateInstance(ci, null, pInstance);
			}
			check(result, "vkCreateInstance");
			this.handle = new VkInstance(pInstance.get(0), ci);
		}

		if (this.debugEnabled) {
			setupDebugMessenger();
		}

		try (MemoryStack stack = stackPush()) {
			LongBuffer pSurface = stack.mallocLong(1);
			check(GLFWVulkan.glfwCreateWindowSurface(this.handle, windowHandle, null, pSurface), "glfwCreateWindowSurface");
			this.surface = pSurface.get(0);
		}

		AmdFaster.LOGGER.info("[AMD-Faster] Vulkan instance created (API {}, {} extensions)",
				VkUtil.apiVersionString(this.apiVersion), debugEnabled ? "debug enabled" : "no debug layers");
	}

	private void setupDebugMessenger() {
		try (MemoryStack stack = stackPush()) {
			VkDebugUtilsMessengerCreateInfoEXT ci = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
					.sType(EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
					.messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
							| EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
					.messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
							| EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
							| EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT);
			// The callback must be heap-allocated (VkDebugUtilsMessengerCallbackEXT.create)
			// so it survives GC; freed in close().
			this.debugCallback = VkDebugUtilsMessengerCallbackEXT.create((severity, types, data, userData) -> {
				String msg = data.pMessageString() == null ? "" : data.pMessageString();
				if ((severity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
					AmdFaster.LOGGER.error("[vk-validation] {}", msg);
				} else {
					AmdFaster.LOGGER.warn("[vk-validation] {}", msg);
				}
				return VK10.VK_FALSE;
			});
			ci.pfnCallback(this.debugCallback);

			LongBuffer pMessenger = stack.mallocLong(1);
			int result = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(this.handle, ci, null, pMessenger);
			if (result == VK10.VK_SUCCESS) {
				this.debugMessenger = pMessenger.get(0);
			} else {
				AmdFaster.LOGGER.warn("[AMD-Faster] VK_EXT_debug_utils messenger unavailable ({})", VkUtil.resultName(result));
			}
		}
	}

	public void close() {
		if (this.debugMessenger != 0L) {
			EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(this.handle, this.debugMessenger, null);
			this.debugMessenger = 0L;
		}
		if (this.debugCallback != null) {
			this.debugCallback.free();
			this.debugCallback = null;
		}
		if (this.surface != 0L) {
			org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR(this.handle, this.surface, null);
		}
		VK10.vkDestroyInstance(this.handle, null);
	}
}
