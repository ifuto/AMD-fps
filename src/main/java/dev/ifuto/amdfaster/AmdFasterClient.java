package dev.ifuto.amdfaster;

import dev.ifuto.amdfaster.config.AmdFasterConfig;
import net.fabricmc.api.ClientModInitializer;

/**
 * Client entrypoint. Deliberately tiny: at this point Minecraft has not created
 * its window or GL context yet, so all GPU work happens lazily from the mixin
 * hooks on the render thread (see {@code dev.ifuto.amdfaster.mixin}).
 */
public final class AmdFasterClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		AmdFasterConfig config = AmdFaster.config();
		AmdFaster.LOGGER.info("[AMD-Faster] v{} loaded. backend={}, validation={}, benchmarkKey=F{}",
				AmdFasterClient.class.getPackage().getImplementationVersion() == null ? "0.1.0" : AmdFasterClient.class.getPackage().getImplementationVersion(),
				config.backend,
				config.validationLayer,
				config.benchmarkKey - 290 /* GLFW_KEY_F1 == 290 */);
		if (!config.enabled) {
			AmdFaster.setStatus(AmdFaster.BackendState.DISABLED, "disabled in amdfaster.json");
		}
	}
}
