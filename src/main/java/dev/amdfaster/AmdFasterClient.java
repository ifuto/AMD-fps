package dev.amdfaster;

import dev.amdfaster.client.GlDeviceProbe;
import dev.amdfaster.core.TuningSession;
import dev.amdfaster.core.backend.ActiveFeatures;
import dev.amdfaster.core.plan.AmdTuner;
import dev.amdfaster.core.plan.TuningPlan;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client entry point.
 *
 * <p>The device has to exist before anything can be tuned, and Fabric's initialisers run before the
 * window is created, so the probe is deferred to {@code CLIENT_STARTED}: by then the render device
 * and its capabilities are real. Everything the probe learns goes into a {@link TuningSession},
 * which is the single source of truth the rendering code reads later.</p>
 */
public class AmdFasterClient implements ClientModInitializer {

    public static final String MOD_ID = "amdfaster";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final AtomicReference<TuningSession> SESSION = new AtomicReference<>();

    @Override
    public void onInitializeClient() {
        String version = FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        LOGGER.info("AMD-Faster {} starting (client side, Minecraft 1.21.11)", version);

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            try {
                Optional<GlDeviceProbe.Result> probed = GlDeviceProbe.probe();
                if (probed.isEmpty()) {
                    LOGGER.warn("AMD-Faster: no OpenGL context on this thread; device tuning is skipped "
                            + "and the vanilla renderer stays active");
                    return;
                }
                tune(probed.get().identity(), probed.get().capabilities());
            } catch (Throwable failure) {
                // A tuning mod that prevents the game from starting is worse than no tuning mod.
                LOGGER.error("AMD-Faster: device probe failed, staying vanilla", failure);
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            TuningSession session = SESSION.get();
            if (session != null) {
                LOGGER.info("AMD-Faster: session ended ({})", session.plan().summary());
            }
        });
    }

    private static void tune(dev.amdfaster.core.arch.GpuIdentity identity,
                             dev.amdfaster.core.backend.GpuCapabilities capabilities) {
        TuningPlan plan = AmdTuner.plan(identity, capabilities, AmdTuner.Options.defaults());
        TuningSession session = new TuningSession(identity, capabilities, plan, ActiveFeatures.inactive());
        SESSION.set(session);
        LOGGER.info("AMD-Faster: {}", plan.summary());
        LOGGER.info("AMD-Faster session report:\n{}", session.report());
    }

    /** The session built at startup, or {@code null} before the render device exists. */
    public static TuningSession session() {
        return SESSION.get();
    }
}
