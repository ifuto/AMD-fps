package dev.ifuto.amdfaster.overlay;

import dev.ifuto.amdfaster.AmdFaster;
import dev.ifuto.amdfaster.config.AmdFasterConfig;
import dev.ifuto.amdfaster.platform.GpuProbe;
import dev.ifuto.amdfaster.vk.Backend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;

/**
 * Owns the F6 diagnostics/benchmark overlay.
 *
 * <p>Pressing the configured key (default F6) while in-game toggles the
 * overlay. While active:
 * <ul>
 *   <li>the vanilla world renderer is skipped (see {@code GameRendererMixin}
 *       / {@code MinecraftMixin}),</li>
 *   <li>the Vulkan backend meshes a configurable radius of sections around the
 *       camera and draws them with the terrain pipeline,</li>
 *   <li>a line overlay shows the frame-time graph, region wireframes and a
 *       small text readout (GPU, arch, FPS, VRAM budget).</li>
 * </ul>
 *
 * <p>This is the "prove it works" surface: it is the first thing a user sees
 * after installing, and it doubles as a benchmark harness (fixed camera,
 * fixed scene, frame-time histogram).
 */
public final class OverlayController {
	private static final OverlayController INSTANCE = new OverlayController();

	private boolean active;
	private boolean keyWasDown;
	private OverlayScene scene;

	private OverlayController() {
	}

	public static OverlayController get() {
		return INSTANCE;
	}

	public static boolean isActive() {
		return INSTANCE.active;
	}

	/** Called every tick from the render thread (via {@code MinecraftMixin}). */
	public void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null || mc.world == null) {
			return; // not in a world yet
		}
		Window window = mc.getWindow();
		AmdFasterConfig cfg = AmdFaster.config();
		boolean down = InputUtil.isKeyPressed(window, cfg.benchmarkKey);
		if (down && !keyWasDown) {
			toggle();
		}
		keyWasDown = down;
	}

	public void toggle() {
		if (active) {
			deactivate();
		} else {
			activate();
		}
	}

	private void activate() {
		Backend backend = Backend.get();
		if (backend == null) {
			AmdFaster.LOGGER.warn("[AMD-Faster] overlay requested but the Vulkan backend is not active");
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.world == null || mc.player == null) {
			return;
		}
		if (scene == null) {
			scene = new OverlayScene(mc);
		}
		scene.build();
		backend.setFrameHook(() -> scene.renderFrame());
		active = true;
		AmdFaster.LOGGER.info("[AMD-Faster] overlay ON — {} ({})", GpuProbe.describe(), backend.device.tuning.summary);
	}

	private void deactivate() {
		Backend backend = Backend.get();
		if (backend != null) {
			backend.setFrameHook(null);
		}
		if (scene != null) {
			scene.close();
			scene = null;
		}
		active = false;
		AmdFaster.LOGGER.info("[AMD-Faster] overlay OFF");
	}

	/** Per-frame stats sink; the scene reads these for the readout. */
	public static final class Stats {
		public volatile float fps;
		public volatile float frameMs;
		public volatile long vramUsedBytes;
		public volatile long vramBudgetBytes;
		public volatile int sectionsMeshed;
		public volatile int drawCalls;

		public static String format(Stats s, Backend backend) {
			StringBuilder sb = new StringBuilder();
			sb.append("GPU: ").append(GpuProbe.describe()).append('\n');
			if (backend != null) {
				sb.append("Arch: ").append(backend.device.architecture.label)
						.append(" (wave").append(backend.device.architecture.nativeWave).append(")\n");
				sb.append("Driver: ").append(GpuProbe.vkDriverName.isEmpty() ? "?" : GpuProbe.vkDriverName).append('\n');
				sb.append("Memory: ").append(s.vramUsedBytes >> 20).append(" / ")
						.append(s.vramBudgetBytes >> 20).append(" MiB\n");
			}
			sb.append(String.format("FPS: %.0f (%.2f ms)%n", s.fps, s.frameMs));
			sb.append("Sections: ").append(s.sectionsMeshed).append('\n');
			sb.append("Draw calls: ").append(s.drawCalls).append('\n');
			sb.append("Key: F6 toggles overlay, ESC exits to game");
			return sb.toString();
		}
	}

	private final Stats stats = new Stats();

	public Stats stats() {
		return stats;
	}

	/** ESC exits the overlay (handled from the scene's input poll). */
	public void requestExit() {
		if (active) {
			deactivate();
		}
	}
}
