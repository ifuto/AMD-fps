package dev.ifuto.amdfaster.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ifuto.amdfaster.AmdFaster;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plain-GSON configuration, stored at {@code config/amdfaster.json}.
 *
 * <p>No config library dependency: the file is small, versioned by
 * {@link #CONFIG_VERSION} and re-created with defaults when unreadable, which
 * keeps the mod installable from the Modrinth App with zero extra downloads.
 */
public final class AmdFasterConfig {
	public static final int CONFIG_VERSION = 1;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// ---- general -----------------------------------------------------------
	/** Master kill-switch. */
	public boolean enabled = true;
	/** {@code auto} (enable on any Vulkan-capable GPU, prefer AMD), {@code vulkan} (force), {@code off}. */
	public String backend = "auto";
	/** Key that toggles the Vulkan diagnostics/benchmark overlay. GLFW code, default F6. */
	public int benchmarkKey = GLFW.GLFW_KEY_F6;

	// ---- vulkan device -----------------------------------------------------
	/** Prefer the AMD device when several GPUs are exposed (hybrid laptops). */
	public boolean preferAmdDevice = true;
	/** Enable VK_LAYER_KHRONOS_validation. Requires the layer to be installed. */
	public boolean validationLayer = false;
	/** Enable VK_EXT_debug_utils messenger output into the log. */
	public boolean debugUtils = false;
	/** Frames in flight (1..3). APUs often prefer 3, dGPUs 2. 0 = let AmdTuning decide. */
	public int framesInFlight = 0;
	/** Save/restore the Vulkan pipeline cache under the config dir. */
	public boolean pipelineCache = true;

	// ---- memory ------------------------------------------------------------
	/** Size of the per-frame host-visible staging ring, in MiB. */
	public int stagingBufferMiB = 64;
	/**
	 * Force-allow DEVICE_LOCAL|HOST_VISIBLE allocations (Resizable BAR / unified APU
	 * memory) even when the exposed heap looks small. {@code null}-ish: -1 = auto.
	 */
	public int deviceLocalHostVisibleMiB = -1;
	/** Experimental: enable VK_AMD_memory_overallocation_behavior on RADV (helps tiled uploads). */
	public boolean experimentalAmdOverallocation = false;
	/** Experimental: enable VK_AMD_anti_lag2 and mark presentation frames. Off until validated on hardware. */
	public boolean experimentalAmdAntiLag = false;

	// ---- benchmark overlay -------------------------------------------------
	/** Section radius around the camera that the overlay meshes (4 = 9x9 chunks). */
	public int benchmarkRadiusSections = 4;
	/** Auto-orbiting camera in the overlay (false = freeze on the in-game camera). */
	public boolean orbitCamera = true;
	/** Draw the frame-time graph + region wireframes in the overlay. */
	public boolean showFrameGraph = true;
	/** Rebuild overlay meshes every activation (false = keep cached until reload key). */
	public boolean rebuildMeshesOnActivate = true;

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("amdfaster.json");
	}

	public static AmdFasterConfig load() {
		Path path = file();
		try {
			if (Files.exists(path)) {
				String json = Files.readString(path, StandardCharsets.UTF_8);
				AmdFasterConfig cfg = GSON.fromJson(json, AmdFasterConfig.class);
				if (cfg != null) {
					cfg.sanitize();
					return cfg;
				}
			}
		} catch (IOException | RuntimeException e) {
			AmdFaster.LOGGER.warn("[AMD-Faster] could not read config ({}), falling back to defaults", e.toString());
		}
		AmdFasterConfig cfg = new AmdFasterConfig();
		cfg.save();
		return cfg;
	}

	public void save() {
		try {
			Files.writeString(file(), GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			AmdFaster.LOGGER.warn("[AMD-Faster] could not write config: {}", e.toString());
		}
	}

	private void sanitize() {
		if (backend == null || !(backend.equals("auto") || backend.equals("vulkan") || backend.equals("off"))) {
			backend = "auto";
		}
		stagingBufferMiB = Math.max(4, Math.min(1024, stagingBufferMiB));
		framesInFlight = Math.max(0, Math.min(3, framesInFlight));
		benchmarkRadiusSections = Math.max(1, Math.min(16, benchmarkRadiusSections));
		if (benchmarkKey < GLFW.GLFW_KEY_SPACE || benchmarkKey > GLFW.GLFW_KEY_LAST) {
			benchmarkKey = GLFW.GLFW_KEY_F6;
		}
	}
}
