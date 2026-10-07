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
    }

    /** @return the probe result, or {@code null} before initialisation has completed. */
    public static GpuReport report() {
        return report;
    }
}
