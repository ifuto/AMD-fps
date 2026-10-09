package net.amdfaster;

import net.amdfaster.command.AmdFasterCommand;
import net.amdfaster.platform.GpuReport;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entry point of AMD-Faster.
 *
 * <p>Stage 1 of the project: bring up nothing but a <em>probe</em>. We create a throw-away
 * {@code VkInstance}, ask the loader what is actually attached to the machine, classify the
 * AMD part against the RDNA generation table, and print the result. Everything the later
 * rendering stages tune themselves against (wave size, LDS budget, work-group granularity,
 * UMA/ReBAR, extension availability) is measured here rather than assumed.
 */
public final class AmdFasterClient implements ClientModInitializer {

    public static final String MOD_ID = "amdfaster";
    public static final String MOD_NAME = "AMD-Faster";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private static GpuReport report;

    @Override
    public void onInitializeClient() {
        LOGGER.info("{} initialising — probing Vulkan adapters", MOD_NAME);

        report = GpuReport.probe();
        report.logTo(LOGGER);
        report.writeToFile();

        AmdFasterCommand.register();

        // The runtime is what makes the rest of the mod do anything. Everything else in this codebase
        // is a component that waits to be called; this is the thing that calls it.
        net.amdfaster.runtime.AmdFasterRuntime.install(configDirectory());
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING
                .register(client -> net.amdfaster.runtime.AmdFasterRuntime.shutdown());
    }

    /**
     * Where the config file lives.
     *
     * <p>Resolved lazily rather than at class load, because the client entry point runs before
     * {@link Minecraft#getInstance()} is guaranteed to exist on every load path.
     */
    private static java.nio.file.Path configDirectory() {
        try {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            if (client != null && client.gameDirectory != null) {
                return client.gameDirectory.toPath();
            }
        } catch (Throwable t) {
            // Fall through to a config-less default rather than failing to start.
        }
        return null;
    }

    /** @return the probe result, or {@code null} before initialisation has completed. */
    public static GpuReport report() {
        return report;
    }
}
