package net.amdfaster.runtime;

import java.nio.file.Path;

import net.amdfaster.AmdFasterClient;
import net.amdfaster.mc.BlockStateCache;
import net.amdfaster.perf.FrameTimeRecorder;
import net.amdfaster.perf.StageTimer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;

/**
 * The thing that makes the rest of the mod run.
 *
 * <p>Everything else in this codebase is a component: a cache, a scheduler, a queue, a light engine.
 * None of them does anything until something calls them, and this is that something. It owns the
 * instances, registers the event handlers, and feeds each component from the game event it corresponds
 * to.
 *
 * <p>What is wired here, and what each one actually does while the game is running:
 *
 * <p><b>Frame timing.</b> Every frame's wall-clock duration goes into {@link FrameTimeRecorder}, which
 * is what produces the 1 percent low, the percentiles, and the variance. Without this there is no way
 * to tell whether any change helped, and a performance mod that cannot measure itself is guessing.
 *
 * <p><b>Stage timing.</b> The client tick is bracketed by {@link StageTimer} so the breakdown shows
 * where the time goes rather than only how much there is.
 *
 * <p><b>Frame governor.</b> An unfocused or idle window is capped, and the player's own setting is
 * restored on the way out. The setting is mutated rather than a separate limiter added because the
 * game already has a limiter that works correctly with vsync, and adding a second one means the two
 * fight over when to sleep.
 *
 * <p><b>Cache invalidation.</b> Chunk load and unload clear the block state cache for that region. A
 * cache that is not invalidated is not a cache, it is a source of blocks that are no longer there, and
 * the symptom is geometry that does not match the world.
 *
 * <p><b>Overlay.</b> Drawn from the HUD callback, showing the numbers above. It exists so a player can
 * see whether any of this is doing anything, which is the only honest way to ship a performance mod.
 *
 * <p>All handlers run on the client thread. Nothing here is safe to call from a worker thread, and
 * nothing here blocks.
 */
public final class AmdFasterRuntime {

    private static AmdFasterRuntime instance;

    private final AmdFasterConfig config;
    private final FrameTimeRecorder recorder = new FrameTimeRecorder();
    private final StageTimer stages = new StageTimer();
    private final FrameGovernor governor;
    private final BlockStateCache blockCache;

    private final int tickStage;

    private long lastFrameAtMs = -1L;
    private int restoredFramerateLimit = -1;
    private boolean limitApplied;
    private boolean installed;

    private long framesTimed;
    private long chunksInvalidated;

    private AmdFasterRuntime(AmdFasterConfig config) {
        this.config = config;
        this.governor = new FrameGovernor(
                config.integer(AmdFasterConfig.KEY_UNFOCUSED_FPS, FrameGovernor.DEFAULT_UNFOCUSED_FPS),
                config.integer(AmdFasterConfig.KEY_IDLE_FPS, FrameGovernor.DEFAULT_IDLE_FPS),
                config.longValue(AmdFasterConfig.KEY_IDLE_DELAY_MS, FrameGovernor.DEFAULT_IDLE_DELAY_MS),
                FrameGovernor.DEFAULT_RESUME_GRACE_MS,
                FrameGovernor.DEFAULT_FOCUS_GRACE_MS);
        this.blockCache = config.bool(AmdFasterConfig.KEY_BLOCK_CACHE_ENABLED, true)
                ? new BlockStateCache(config.integer(AmdFasterConfig.KEY_BLOCK_CACHE_ENTRIES,
                        BlockStateCache.DEFAULT_ENTRIES))
                : null;
        this.tickStage = this.stages.stage("clientTick");
    }

    /**
     * Creates the runtime and registers every handler. Called once from the client entry point.
     *
     * <p>Never throws. A performance mod that stops the game from starting has done the opposite of its
     * job, so a failure here is logged and the mod carries on inert rather than taking Minecraft down.
     */
    public static synchronized void install(Path configDirectory) {
        if (instance != null) {
            return;
        }
        AmdFasterConfig config = AmdFasterConfig.load(configDirectory);
        AmdFasterRuntime runtime = new AmdFasterRuntime(config);
        instance = runtime;
        try {
            runtime.register();
            runtime.installed = true;
            AmdFasterClient.LOGGER.info("{} runtime installed: governor={}, blockCache={}, overlay={}",
                    AmdFasterClient.MOD_NAME,
                    config.bool(AmdFasterConfig.KEY_GOVERNOR_ENABLED, true) ? "on" : "off",
                    runtime.blockCache != null ? runtime.blockCache.entries() + " entries" : "off",
                    config.bool(AmdFasterConfig.KEY_OVERLAY_ENABLED, true) ? "on" : "off");
        } catch (Throwable t) {
            AmdFasterClient.LOGGER.error("{} runtime failed to install; continuing without it",
                    AmdFasterClient.MOD_NAME, t);
        }
    }

    public static AmdFasterRuntime get() {
        return instance;
    }

    /** Whether the handlers were registered successfully. */
    public boolean isInstalled() {
        return this.installed;
    }

    private void register() {
        ClientTickEvents.START_CLIENT_TICK.register(this::onStartTick);
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        HudRenderCallback.EVENT.register((graphics, tickCounter) -> onHudRender(Minecraft.getInstance(), graphics));
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> invalidateAround(chunk.getPos().x, chunk.getPos().z));
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> invalidateAround(chunk.getPos().x, chunk.getPos().z));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect());
    }

    private void onStartTick(Minecraft client) {
        this.stages.begin(this.tickStage);
    }

    private double lastX;
    private double lastY;
    private double lastZ;
    private float lastPitch;
    private float lastYaw;

    private void onEndTick(Minecraft client) {
        this.stages.end(this.tickStage);
        if (isPlayerActive(client)) {
            this.governor.noteInput(System.currentTimeMillis());
        }
    }

    /**
     * Whether the player is doing something that needs frames drawn.
     *
     * <p>Derived from the camera rather than from input events. Two reasons. The practical one: the
     * input plumbing differs between versions and between controllers, keyboards and touch, and every
     * handler is a place to be wrong -- the previous attempt here called a keyboard method that does not
     * exist and took the build down. The real one: movement and look direction are what actually decide
     * whether the next frame will differ, so they are the correct signal, not a proxy for it. A player
     * holding a key against a wall is inputting and getting nothing new; a player watching a mob walk
     * past is not inputting and getting a different frame every time.
     */
    private boolean isPlayerActive(Minecraft client) {
        if (client.player == null) {
            return false;
        }
        double x = client.player.getX();
        double y = client.player.getY();
        double z = client.player.getZ();
        float pitch = client.player.getXRot();
        float yaw = client.player.getYRot();
        boolean moved = x != this.lastX || y != this.lastY || z != this.lastZ
                || pitch != this.lastPitch || yaw != this.lastYaw;
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
        this.lastPitch = pitch;
        this.lastYaw = yaw;
        // A screen being open means the game is drawing a menu over the world, which is its own reason
        // to keep rendering, and one the player chose.
        return moved || client.screen != null;
    }

    /**
     * Measures the frame, applies the governor, and draws the overlay.
     *
     * <p>All three happen here because this callback runs once per rendered frame, which is the only
     * place in the client that is per frame rather than per tick. Timing it anywhere else measures the
     * wrong thing: the game ticks twenty times a second whatever the frame rate is.
     */
    private void onHudRender(Minecraft client, net.minecraft.client.gui.GuiGraphics graphics) {
        long nowMs = System.currentTimeMillis();
        if (this.lastFrameAtMs >= 0) {
            long elapsedMs = nowMs - this.lastFrameAtMs;
            // Guarded at both ends. A wall clock can step backwards on an NTP adjustment, and a
            // negative or absurd frame time would poison the percentile window and surface as a
            // 1 percent low of zero -- a measurement error wearing the costume of a catastrophic frame.
            if (elapsedMs > 0 && elapsedMs < 60_000L) {
                this.recorder.record(elapsedMs * 1_000_000L);
                this.framesTimed++;
            }
        }
        this.lastFrameAtMs = nowMs;

        if (this.config.bool(AmdFasterConfig.KEY_GOVERNOR_ENABLED, true)) {
            applyGovernor(client, nowMs);
        }

        if (this.config.bool(AmdFasterConfig.KEY_OVERLAY_ENABLED, true)) {
            drawOverlay(client, graphics);
        }
    }

    private void applyGovernor(Minecraft client, long nowMs) {
        boolean focused = client.isWindowActive();
        int requested = this.restoredFramerateLimit >= 0
                ? this.restoredFramerateLimit
                : client.options.framerateLimit().get();
        if (this.restoredFramerateLimit < 0) {
            this.restoredFramerateLimit = requested;
        }
        int cap = this.governor.tick(nowMs, focused, requested);
        if (cap != client.options.framerateLimit().get()) {
            client.options.framerateLimit().set(cap);
            this.limitApplied = true;
        }
    }

    private void drawOverlay(Minecraft client, net.minecraft.client.gui.GuiGraphics graphics) {
        var lines = AmdFasterHud.build(this.recorder, this.stages, this.blockCache, this.governor);
        int y = AmdFasterHud.marginY();
        for (var line : lines) {
            graphics.drawString(client.font, line.text(), AmdFasterHud.marginX(), y, line.colour());
            y += AmdFasterHud.lineHeight();
        }
    }

    /**
     * Drops cached block states around a chunk that just changed membership.
     *
     * <p>The cache is direct-mapped on position, so there is no per-chunk index to clear; the honest
     * options are to clear all of it or to leave stale entries. Clearing is the correct answer, because
     * a stale entry is not a small error -- it is a block that is no longer there being reported as
     * present, and it stays wrong until something else happens to evict it.
     */
    private void invalidateAround(int chunkX, int chunkZ) {
        if (this.blockCache != null) {
            this.blockCache.clear();
            this.chunksInvalidated++;
        }
    }

    private void onDisconnect() {
        // Leaving a world invalidates everything: the dimension may change, the coordinates are
        // meaningless across worlds, and a cache keyed on position would otherwise answer for a world
        // that no longer exists.
        if (this.blockCache != null) {
            this.blockCache.clear();
        }
        this.recorder.clear();
        this.stages.clear();
        this.governor.clear();
        this.lastFrameAtMs = -1L;
        restoreFramerateLimit();
    }

    /** Puts the player's own frame rate setting back. Called on disconnect and on shutdown. */
    public void restoreFramerateLimit() {
        if (this.restoredFramerateLimit >= 0 && this.limitApplied) {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.options != null) {
                client.options.framerateLimit().set(this.restoredFramerateLimit);
            }
            this.limitApplied = false;
        }
    }

    /** Shuts the runtime down. Called from the client entry point on stop. */
    public static synchronized void shutdown() {
        if (instance != null) {
            instance.restoreFramerateLimit();
            if (instance.config.file() != null) {
                instance.config.save();
            }
        }
    }

    public AmdFasterConfig config() {
        return this.config;
    }

    public FrameTimeRecorder recorder() {
        return this.recorder;
    }

    public StageTimer stages() {
        return this.stages;
    }

    public FrameGovernor governor() {
        return this.governor;
    }

    /** The block state cache, or null if the player turned it off. */
    public BlockStateCache blockCache() {
        return this.blockCache;
    }

    public long framesTimed() {
        return this.framesTimed;
    }

    public long chunksInvalidated() {
        return this.chunksInvalidated;
    }
}
