package dev.amdfaster;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entry point.
 *
 * <p>AMD-Faster is a client-side renderer mod: it has no server component, registers no blocks or
 * items, and never touches world data. Everything it does happens between the CPU and the GPU.
 *
 * <p>Startup order matters and is deliberate:
 * <ol>
 *   <li>Load the config (cheap, no graphics).</li>
 *   <li>Wait for the GL/Vulkan device to exist, then probe capabilities and identify the GPU
 *       ({@code dev.amdfaster.core.arch} and {@code dev.amdfaster.core.backend}).</li>
 *   <li>Build the {@link dev.amdfaster.core.plan.TuningPlan} from the identification and the
 *       capabilities - pure data, unit testable, no rendering side effects.</li>
 *   <li>Hand the plan to the renderer modules, which decide at runtime what they can actually
 *       engage and report it back as {@link dev.amdfaster.core.backend.ActiveFeatures}.</li>
 * </ol>
 */
public class AmdFasterClient implements ClientModInitializer {

    public static final String MOD_ID = "amdfaster";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        String version = FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        LOGGER.info("AMD-Faster {} starting (client side, Minecraft 1.21.11)", version);
        LOGGER.info("AMD-Faster: GPU detection and tuning plan are built after the render device is created");
    }
}
