package dev.ifuto.amdfaster.vk;

import dev.ifuto.amdfaster.AmdFaster;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/** Shared helpers around raw LWJGL Vulkan calls. */
public final class VkUtil {
	private VkUtil() {
	}

	/** Thrown for any non-success VkResult we cannot recover from. */
	public static final class VkException extends RuntimeException {
		public final int result;

		public VkException(String what, int result) {
			super(what + " failed: " + resultName(result) + " (" + result + ")");
			this.result = result;
		}
	}

	public static void check(int result, String what) {
		if (result != VK10.VK_SUCCESS) {
			throw new VkException(what, result);
		}
	}

	/** Like {@link #check} but tolerates the usual swapchain "please recreate me" results. */
	public static boolean isOutdated(int result) {
		return result == VK10.VK_ERROR_OUT_OF_DATE_KHR || result == VK10.VK_SUBOPTIMAL_KHR
				|| result == VK10.VK_ERROR_SURFACE_LOST_KHR;
	}

	public static String resultName(int result) {
		switch (result) {
			case VK10.VK_SUCCESS: return "VK_SUCCESS";
			case VK10.VK_NOT_READY: return "VK_NOT_READY";
			case VK10.VK_TIMEOUT: return "VK_TIMEOUT";
			case VK10.VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
			case VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
			case VK10.VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
			case VK10.VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
			case VK10.VK_ERROR_MEMORY_MAP_FAILED: return "VK_ERROR_MEMORY_MAP_FAILED";
			case VK10.VK_ERROR_LAYER_NOT_PRESENT: return "VK_ERROR_LAYER_NOT_PRESENT";
			case VK10.VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
			case VK10.VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
			case VK10.VK_ERROR_INCOMPATIBLE_DRIVER: return "VK_ERROR_INCOMPATIBLE_DRIVER";
			case VK10.VK_ERROR_SURFACE_LOST_KHR: return "VK_ERROR_SURFACE_LOST_KHR";
			case VK10.VK_ERROR_OUT_OF_DATE_KHR: return "VK_ERROR_OUT_OF_DATE_KHR";
			case VK10.VK_SUBOPTIMAL_KHR: return "VK_SUBOPTIMAL_KHR";
			default: return "VK_RESULT_" + result;
		}
	}

	/** Reads a classpath resource into a freshly allocated direct buffer (SPIR-V, cache files…). */
	public static ByteBuffer loadResourceDirect(String resourcePath) {
		ClassLoader cl = AmdFaster.class.getClassLoader();
		try (InputStream in = cl.getResourceAsStream(resourcePath)) {
			if (in == null) {
				throw new VkException("resource not found: " + resourcePath, VK10.VK_ERROR_INITIALIZATION_FAILED);
			}
			ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(in.available(), 4096));
			byte[] chunk = new byte[8192];
			int n;
			while ((n = in.read(chunk)) > 0) {
				bos.write(chunk, 0, n);
			}
			byte[] bytes = bos.toByteArray();
			ByteBuffer direct = MemoryUtil.memAlloc(bytes.length);
			direct.put(bytes).flip();
			return direct;
		} catch (IOException e) {
			throw new VkException("resource read failed: " + resourcePath, VK10.VK_ERROR_INITIALIZATION_FAILED);
		}
	}

	public static int vkMakeVersion(int major, int minor, int patch) {
		return (major << 22) | (minor << 12) | patch;
	}

	public static String apiVersionString(int version) {
		return VK10.VK_VERSION_MAJOR(version) + "." + VK10.VK_VERSION_MINOR(version) + "." + VK10.VK_VERSION_PATCH(version);
	}
}
