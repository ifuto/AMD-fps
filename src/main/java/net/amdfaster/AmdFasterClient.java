package net.amdfaster;

import net.amdfaster.command.AmdFasterCommand;
import net.amdfaster.compat.SodiumCoexistence;
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

    /**
     * What was decided about Sodium, or {@code null} before the entry point has run.
     *
     * <p>Held rather than recomputed because the answer cannot change during a session: the mod list is
     * fixed once the loader has resolved it, and re-asking would let a subsystem change owner mid-game.
     */
    private static SodiumCoexistence.Detection sodium;

    @Override
    public void onInitializeClient() {
        LOGGER.info("{} initialising — probing Vulkan adapters", MOD_NAME);

        report = GpuReport.probe();
        report.logTo(LOGGER);
        report.writeToFile();

        sodium = detectSodium();
        // Logged rather than kept quiet, because "AMD-Faster is not meshing terrain" is indistinguishable
        // from a bug unless the log says it stood aside for Sodium on purpose.
        LOGGER.info("{}: {}", MOD_NAME, SodiumCoexistence.describe(sodium));
        if (SodiumCoexistence.shouldWarn(sodium)) {
            LOGGER.warn("{}: Sodium {} is active but this build was validated against {}.{}; "
                            + "the terrain paths are yielded anyway, so a rendering problem here may be "
                            + "a version mismatch rather than a bug in either mod",
                    MOD_NAME, sodium.major() + "." + sodium.minor(),
                    SodiumCoexistence.VALIDATED_MAJOR, SodiumCoexistence.VALIDATED_MINOR);
        }

        AmdFasterCommand.register();

        // The runtime is what makes the rest of the mod do anything. Everything else in this codebase
        // is a component that waits to be called; this is the thing that calls it.
        net.amdfaster.runtime.AmdFasterRuntime.install(configDirectory());
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING
                .register(client -> net.amdfaster.runtime.AmdFasterRuntime.shutdown());
    }

    /** What was decided about Sodium, or {@code null} before the entry point has run. */
    public static SodiumCoexistence.Detection sodium() {
        return sodium;
    }

    /**
     * Whether AMD-Faster should run a subsystem itself.
     *
     * <p>Returns true before the entry point has run, which is the safe default: a subsystem that is
     * switched on and should not have been costs performance, whereas one left off when it should have
     * run costs a rendering path nobody is driving.
     *
     * @param subsystem the subsystem in question
     * @return whether AMD-Faster owns it
     */
    public static boolean owns(SodiumCoexistence.Subsystem subsystem) {
        return sodium == null || SodiumCoexistence.amdFasterOwns(sodium, subsystem);
    }

    /**
     * Reads the mod list.
     *
     * <p>Kept in the entry point rather than in {@link SodiumCoexistence}, so the decision logic stays free
     * of the loader and can be tested without one. Both halves of the question are asked: whether Sodium is
     * loaded, and whether Embeddium is loaded, because Embeddium switches Sodium's mixins off and a mod
     * that turned itself off is not one to stand aside for.
     *
     * @return what was detected
     */
    private static SodiumCoexistence.Detection detectSodium() {
        var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        boolean sodiumPresent = loader.isModLoaded(SodiumCoexistence.SODIUM_ID);
        boolean embeddiumPresent = loader.isModLoaded(SodiumCoexistence.EMBEDDIUM_ID);
        String version = loader.getModContainer(SodiumCoexistence.SODIUM_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse(null);
        return SodiumCoexistence.detect(sodiumPresent, embeddiumPresent, version);
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
