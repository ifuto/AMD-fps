package dev.ifuto.amdfaster;

import dev.ifuto.amdfaster.config.AmdFasterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Static facade of the mod. Holds configuration, the backend lifecycle state and
 * the logging channel. Everything else hangs off this class.
 *
 * <p>Design note: AMD-Faster is intentionally dependency-light (Fabric Loader +
 * Mixin + LWJGL only, no Fabric API) so that it can coexist with Sodium/Iris
 * installs without fighting over the same event buses. It ships its own Vulkan
 * backend (jar-in-jar {@code lwjgl-vulkan}) instead of patching the OpenGL
 * renderer — see docs/design/architecture.md for the reasoning.
 */
public final class AmdFaster {
	public static final String MOD_ID = "amdfaster";
	public static final String NAME = "AMD-Faster";
	public static final Logger LOGGER = LoggerFactory.getLogger(NAME);

	/** Lifecycle state of the Vulkan backend. */
	public enum BackendState {
		/** Nothing attempted yet. */
		NOT_STARTED,
		/** Initialization requested; runs on the render thread. */
		INITIALIZING,
		/** Backend alive: instance/device/swapchain ready. */
		ACTIVE,
		/** Disabled by config or because the machine is not suitable. */
		DISABLED,
		/** Initialization failed; the mod stays out of the way. */
		FAILED
	}

	public static final AtomicReference<BackendState> STATE = new AtomicReference<>(BackendState.NOT_STARTED);

	private static AmdFasterConfig config;
	private static String statusMessage = "";

	private AmdFaster() {
	}

	public static AmdFasterConfig config() {
		if (config == null) {
			config = AmdFasterConfig.load();
		}
		return config;
	}

	public static String statusMessage() {
		return statusMessage;
	}

	public static void setStatus(BackendState state, String message) {
		STATE.set(state);
		statusMessage = message == null ? "" : message;
		if (state == BackendState.FAILED || state == BackendState.DISABLED) {
			LOGGER.warn("[{}] backend {}: {}", NAME, state, statusMessage);
		} else {
			LOGGER.info("[{}] backend {}: {}", NAME, state, statusMessage);
		}
	}

	public static boolean isActive() {
		return STATE.get() == BackendState.ACTIVE;
	}
}
