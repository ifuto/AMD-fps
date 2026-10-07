package dev.ifuto.amdfaster.platform;

import dev.ifuto.amdfaster.AmdFaster;
import org.lwjgl.opengl.GL11;

/**
 * Collects GPU identification from two independent sources:
 *
 * <ul>
 *   <li>the OpenGL context Minecraft already owns (vendor/renderer strings —
 *       on Linux/radeonsi these are unusually informative, they embed the LLVM
 *       chip name: {@code "AMD Radeon RX 7900 XTX (radeonsi, navi31, DRM 3.57, 6.8.0)"}),</li>
 *   <li>the Vulkan physical device chosen by the backend (PCI vendor/device id,
 *       driver name from VK_KHR_driver_properties, subgroup size from Vulkan 1.1
 *       properties — wave32 vs wave64 is a first-class tuning input on RDNA).</li>
 * </ul>
 *
 * See docs/research/05-drivers-radv-aco-amdvlk.md for why driver name matters
 * (RADV vs AMDVLK behave differently around memory and submission batching).
 */
public final class GpuProbe {
	public static final int VENDOR_ID_AMD = 0x1002;

	// --- OpenGL side (probe on render thread, context current) ---
	public static String glVendor = "";
	public static String glRenderer = "";
	public static String glVersion = "";
	public static boolean glProbed = false;

	// --- Vulkan side (filled in by the backend at device selection) ---
	public static int vkVendorId = 0;
	public static int vkDeviceId = 0;
	public static String vkDeviceName = "";
	public static String vkDriverName = "";
	public static String vkDriverInfo = "";
	public static int vkSubgroupSize = 0;
	public static String vkApiVersion = "";

	private GpuProbe() {
	}

	public static void probeGl() {
		if (glProbed) {
			return;
		}
		try {
			String v = GL11.glGetString(GL11.GL_VENDOR);
			String r = GL11.glGetString(GL11.GL_RENDERER);
			String ver = GL11.glGetString(GL11.GL_VERSION);
			glVendor = v == null ? "" : v;
			glRenderer = r == null ? "" : r;
			glVersion = ver == null ? "" : ver;
			glProbed = true;
			AmdFaster.LOGGER.info("[AMD-Faster] GL probe: vendor='{}' renderer='{}' version='{}'", glVendor, glRenderer, glVersion);
		} catch (Throwable t) {
			// No GL context current (headless probe attempt) — stay quiet, the
			// Vulkan side will still identify the GPU.
			glProbed = true;
		}
	}

	/** True when either probe path saw AMD hardware. */
	public static boolean looksLikeAmd() {
		if (vkVendorId == VENDOR_ID_AMD) {
			return true;
		}
		String hay = (glVendor + " " + glRenderer).toLowerCase();
		return hay.contains("amd") || hay.contains("ati") || hay.contains("radeon")
				|| hay.contains("radeonsi") || hay.contains("navi") || hay.contains("gfx");
	}

	/** True when the Vulkan driver is Mesa RADV (vs AMDVLK/proprietary/other). */
	public static boolean isRadv() {
		return vkDriverName.contains("radv") || vkDriverName.contains("Radeon") && vkDriverName.toLowerCase().contains("mesa");
	}

	public static String describe() {
		StringBuilder sb = new StringBuilder();
		if (!vkDeviceName.isEmpty()) {
			sb.append(vkDeviceName);
		} else if (!glRenderer.isEmpty()) {
			sb.append(glRenderer);
		} else {
			sb.append("unknown GPU");
		}
		if (!vkDriverName.isEmpty()) {
			sb.append(" [").append(vkDriverName).append(']');
		}
		if (vkSubgroupSize > 0) {
			sb.append(" wave").append(vkSubgroupSize);
		}
		return sb.toString();
	}
}
