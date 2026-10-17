package net.amdfaster.runtime;

import java.nio.file.Path;

import net.amdfaster.AmdFasterClient;
import net.amdfaster.compat.SodiumCoexistence;
import net.amdfaster.mc.BlockStateCache;
import net.amdfaster.mc.HeightmapIndex;
import net.amdfaster.mc.SectionCoords;
import net.amdfaster.mesh.job.MeshJobQueue;
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

    /**
     * The sound decision pipeline.
     *
     * <p>Owned here rather than created per sound because it holds per-tick state that has to be cleared
     * exactly once per tick, and two instances would each keep their own counts and each refuse things the
     * other allowed.
     */
    private final SoundRuntime sound = new SoundRuntime();

    /**
     * The multiplayer network decisions.
     *
     * <p>Owned here for the same reason as the sound pipeline: it holds per-tick state that has to be cleared
     * once per tick, and a connection lifecycle it has to be told about. A second instance would keep its own
     * pending entities and apply them twice.
     */
    private final NetworkRuntime network = new NetworkRuntime();

    /**
     * The particle decisions.
     *
     * <p>Owned here because it holds a pool that has to be the same one for the whole session -- two
     * instances would each hand out the same slot numbers and two particles would share a position.
     */
    private final ParticleRuntime particles = new ParticleRuntime();

    /**
     * The entity decisions.
     *
     * <p>Owned here because it holds one worker per entity and one shared exclusion list. Two instances would
     * each keep their own list, so a player adding a beacon to one would not have added it to the other, and
     * the entity path would silently disagree with the block entity path.
     */
    private final EntityRuntime entities = new EntityRuntime();

    /**
     * The memory caches.
     *
     * <p>Owned here because they have to be cleared together on a world change, and clearing some of them and
     * not others is worse than clearing none.
     */
    private final AssetRuntime assets = new AssetRuntime();

    /**
     * The block entity decisions.
     *
     * <p>Owned here because it holds the shared static geometry baker -- two instances would each bake the same
     * chest body and each claim the draw calls it removed.
     */
    private final BlockEntityRuntime blockEntities = new BlockEntityRuntime();

    /**
     * The render-side decisions.
     *
     * <p>Owned here because the HUD, cloud, weather and text caches all have to be invalidated at the same
     * moment, and missing one means the player sees a stale HUD over a fresh world.
     */
    private final RenderRuntime render = new RenderRuntime();

    /**
     * The world generation decisions.
     *
     * <p>Owned here because the caches hold data derived from a seed, and one that keeps the last world's noise
     * while the block cache holds the new one generates terrain that does not match the world the player is
     * standing in.
     */
    private final WorldGenRuntime worldGen = new WorldGenRuntime();

    /**
     * The Minecraft-facing caches.
     *
     * <p>Owned here because a block that changes has to invalidate every one of them that cached that position,
     * and missing one means the player sees a block that is not there or a hole where a block is.
     */
    private final MinecraftRuntime minecraft = new MinecraftRuntime();

    /**
     * The lighting decisions.
     *
     * <p>Owned here because a light change has to reach the meshes that draw the blocks it changed, and a
     * batcher that is never processed leaves every light update pending forever.
     */
    private final LightRuntime light = new LightRuntime();

    /**
     * What the hardware turned out to be.
     *
     * <p>Owned here because every other component asks it rather than guessing, and the answer does not change
     * while the game runs -- so it is decided once, here, from the device the game actually picked.
     */
    private final PlatformRuntime platform = new PlatformRuntime();

    /**
     * The culling decisions.
     *
     * <p>Owned here because the reuse tracker has to be told when the cull runs, and a tracker that is never
     * told can never reuse anything -- which would make the walk run every frame and the saving silently
     * absent.
     */
    private final CullRuntime cull = new CullRuntime();

    /**
     * The meshing decisions and the worker pool that builds the meshes.
     *
     * <p>Owned here because building a section's mesh on the render thread is a hitch for every chunk that
     * loads, which is what makes walking into new terrain stutter.
     */
    private final MeshRuntime mesh = new MeshRuntime();

    /**
     * The entity-world decisions.
     *
     * <p>Owned here because an entity that despawns has to be removed from every grid that tracks it, and a
     * ghost entry answers for an entity that is no longer there.
     */
    private final EntityWorldRuntime entityWorld = new EntityWorldRuntime();

    /**
     * The indirect-draw and GPU-culling decisions.
     *
     * <p>Owned here because a frame's draw list has to be cleared between worlds: the slots, groups and
     * uniform values all describe geometry from a world that is gone.
     */
    private final GpuRuntime gpu = new GpuRuntime();

    private final int tickStage;

    private long lastFrameAtMs = -1L;
    private long framesClosed;

    /** Chunk columns left to Sodium rather than meshed here. */
    private long meshingDeferredToSodium;

    /** Chunk columns not queued because the game has not supplied a section builder yet. */
    private long meshingWaitingOnBuilder;

    /** Heap threshold crossings reported. */
    private long heapWarnings;

    /** Where the player was last tick, so a movement packet can be priced against a real delta. */
    private double lastPlayerX;
    private double lastPlayerY;
    private double lastPlayerZ;
    private float lastPlayerYaw;
    private float lastPlayerPitch;
    private boolean hasLastPlayer;
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

    /**
     * The sound decision pipeline, for the sound engine to consult before starting a sound.
     *
     * <p>Exposed rather than called from inside a hook, because deciding whether a sound plays has to happen
     * where the sound is about to start -- a hook that ran on a tick boundary would decide too late, after
     * the channel was already allocated.
     */
    public SoundRuntime sound() {
        return this.sound;
    }

    /**
     * The multiplayer network decisions, for the connection handlers and the chunk and entity paths to use.
     *
     * <p>Exposed rather than driven from a hook, because the decisions have to happen where the packet
     * arrives -- a hook on a tick boundary would decide after the packet had already been processed, which is
     * the cost these exist to remove.
     */
    public NetworkRuntime network() {
        return this.network;
    }

    /**
     * The particle decisions, for the particle spawner and the renderer to use.
     *
     * <p>The frame end has to be driven from the render path rather than from the tick, because particles are
     * drawn every frame even when they do not move, and flushing the draw batch on the tick would draw the
     * last particles of a frame with the first particles of the next.
     */
    public ParticleRuntime particles() {
        return this.particles;
    }

    /**
     * The entity decisions, for the entity tick and the entity render path to use.
     *
     * <p>The exclusion list inside it is the one the block entity culling should also consult, so that a
     * player adding a type to it has added it everywhere rather than to one of two lists that silently
     * disagree.
     */
    public EntityRuntime entities() {
        return this.entities;
    }

    /**
     * The memory caches, and the registry other caches can register with.
     *
     * <p>Exposed so a cache added later is cleared on a world change by registering itself, rather than by
     * someone remembering to add it to the list of things this class clears.
     */
    public AssetRuntime assets() {
        return this.assets;
    }

    /**
     * The block entity decisions, for the block entity tick and render paths to use.
     *
     * <p>The baker inside it is the shared one, so a chest body baked by one path is not baked again by
     * another -- which is the whole point of baking it rather than drawing it.
     */
    public BlockEntityRuntime blockEntities() {
        return this.blockEntities;
    }

    /**
     * The render-side decisions, for the HUD, GUI and render paths to drive.
     *
     * <p>The frame has to be driven from the render path rather than from the tick, because the HUD is drawn
     * every frame and the cloud and weather meshes are rebuilt on the frame that needs them.
     */
    public RenderRuntime render() {
        return this.render;
    }

    /**
     * The world generation decisions, for the chunk generation path to use.
     *
     * <p>The distant terrain inside it is a picture rather than a world, and the accessor that says whether it
     * may be used for gameplay returns false unconditionally -- a player who walks into ground that is only a
     * picture falls through the world.
     */
    public WorldGenRuntime worldGen() {
        return this.worldGen;
    }

    /**
     * The Minecraft-facing caches, for the block lookup and chunk load paths to use.
     *
     * <p>The level block sampler inside it is absent until the game supplies one, because it needs a level and
     * a block face source which only the game can provide. Until then it is absent rather than wrong -- a
     * sampler built against the wrong level would answer for a world that is not loaded.
     */
    public MinecraftRuntime minecraft() {
        return this.minecraft;
    }

    /**
     * The lighting decisions, for the block change and chunk load paths to use.
     *
     * <p>The batcher inside it has to be processed on the client thread, because a batcher that is never
     * processed leaves every light update pending forever and the world stays dark where blocks were placed.
     */
    public LightRuntime light() {
        return this.light;
    }

    /**
     * What the hardware is, for every component that has to decide something hardware-dependent.
     *
     * <p>Asked rather than guessed, because a rule keyed on a vendor would be wrong twice -- some Intel parts
     * have capabilities an AMD rule assumes and some AMD parts do not have the ones an Intel rule assumes.
     * When the probe fails this answers with safe defaults rather than throwing, because a mod that cannot
     * start without a GPU is worse than one that starts and runs its CPU-side work.
     */
    public PlatformRuntime platform() {
        return this.platform;
    }

    /**
     * The culling decisions, for the render path to drive once per frame.
     *
     * <p>Driven from the render path rather than the tick, because culling depends on the camera and the depth
     * pyramid, both of which are per frame -- and the reuse tracker has to be told the cull ran, which is what
     * makes the reuse path do anything rather than be dead code.
     */
    public CullRuntime cull() {
        return this.cull;
    }

    /**
     * The meshing decisions, for the chunk load path to queue sections through.
     *
     * <p>Queued by distance from the camera so the sections under the player are built first, and a section
     * that has waited long enough is built regardless of visibility so a hole cannot fail to fill.
     */
    public MeshRuntime mesh() {
        return this.mesh;
    }

    /**
     * The entity-world decisions, for the entity tick and despawn paths to use.
     *
     * <p>Cleared on world change because entity identities are meaningless across worlds -- entity 42 in one
     * world is a different entity from entity 42 in the next, and a grid left uncleared would make mobs in the
     * new world collide with entities from the last one.
     */
    public EntityWorldRuntime entityWorld() {
        return this.entityWorld;
    }

    /**
     * The indirect-draw and GPU-culling pipeline.
     *
     * <p>Cleared on world change, because the uniform values and draw slots describe the last world's
     * geometry and would be uploaded for geometry that no longer exists.
     */
    public GpuRuntime gpu() {
        return this.gpu;
    }

    /** How many frames have had their per-frame windows closed. */
    public long framesClosed() {
        return this.framesClosed;
    }

    /**
     * How many sections the queue actually accepted.
     *
     * <p>Read from the mesh runtime rather than counted here, because each worker's ring is bounded and a
     * full one refuses the job. A counter incremented per attempt would report sections as queued that were
     * silently dropped, which is the kind of number that makes a stall look like progress.
     */
    public long sectionsQueued() {
        return this.mesh.queuedAccepted();
    }

    /** How many sections the queue refused because a worker's ring was full. */
    public long sectionsQueueRejected() {
        return this.mesh.queuedRejected();
    }

    /** How many chunk columns were not queued for want of a section builder. */
    public long meshingWaitingOnBuilder() {
        return this.meshingWaitingOnBuilder;
    }

    /** How many times the heap crossed the warning threshold. */
    public long heapWarnings() {
        return this.heapWarnings;
    }

    /** How many chunk columns were left to Sodium instead of being meshed here. */
    public long meshingDeferredToSodium() {
        return this.meshingDeferredToSodium;
    }

    private void register() {
        ClientTickEvents.START_CLIENT_TICK.register(this::onStartTick);
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        HudRenderCallback.EVENT.register((graphics, tickCounter) -> onHudRender(Minecraft.getInstance(), graphics));
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> onChunkLoaded(chunk.getPos().x, chunk.getPos().z));
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> onChunkUnloaded(chunk.getPos().x, chunk.getPos().z));
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
        // Clears every per-tick window in the sound pipeline. Missing this would make the sound limiters
        // accumulate for the whole session and eventually refuse everything, which the player would
        // experience as the world going quiet for no reason.
        this.sound.endTick();
        // Applies the entity updates the coalescer held. Missing this would leave every entity a tick behind
        // where the server says it is, forever, because nothing would ever apply the held updates.
        this.network.endTick();
        // Clears the occlusion counters. Missing this would let last tick's terrain decide this tick's
        // particles, which is a culler that is wrong in a way that looks like particles disappearing.
        this.particles.endTick();
        // Refills the activation scheduler's wake-up budget and advances the visibility schedule's phase.
        // Missing the first would let every distant entity wake on the same tick, which is the spike the
        // tiers exist to avoid; missing the second would test the same entities every tick and none of the
        // others, which is a staggered schedule that does not stagger.
        this.entities.endTick();
        onPlayerMoved();
        sampleHeap();
    }

    /**
     * Samples the heap once a tick.
     *
     * <p>Once a tick rather than once a frame because the number does not move faster than allocation does,
     * and the monitor's whole purpose is to warn before the collector starts working hard -- which is a
     * trend over seconds, not a per-frame jitter.
     *
     * <p>{@code totalMemory() - freeMemory()} is the used heap: {@code totalMemory} is what the runtime has
     * taken from the OS so far and {@code freeMemory} is the part of that not yet handed out, so the
     * difference is what is actually live. Using {@code totalMemory} alone would report the reservation
     * rather than the use, and would read as a heap nearly full from the moment the game started.
     */
    private void sampleHeap() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long used = runtime.totalMemory() - runtime.freeMemory();
        if (this.assets.sampleHeap(used, max)) {
            AmdFasterClient.LOGGER.warn("{}: heap at {}% of {} MB -- the collector is about to start "
                            + "working hard, which shows up as stutter before it shows up as a crash",
                    AmdFasterClient.MOD_NAME,
                    Math.round(100.0 * used / max), max >> 20);
            this.heapWarnings++;
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
                // The same measurement that feeds the percentile window feeds the controller that trades
                // render distance for frame time. Two sources of truth here would let the overlay report a
                // frame time the controller is not acting on.
                onFrameTimed(client, elapsedMs);
            }
        }
        this.lastFrameAtMs = nowMs;

        if (this.config.bool(AmdFasterConfig.KEY_GOVERNOR_ENABLED, true)) {
            applyGovernor(client, nowMs);
        }

        if (this.config.bool(AmdFasterConfig.KEY_OVERLAY_ENABLED, true)) {
            drawOverlay(client, graphics);
        }

        endFrame();
    }

    /**
     * Closes the per-frame windows.
     *
     * <p>Called from the HUD callback rather than from a tick, because frames and ticks are not one-to-one:
     * a frame can run without a tick and a tick can run without a frame. Closing these on a tick would
     * either skip a frame's worth of accounting or close a window that was never opened.
     *
     * <p>Order matters. The renderer's frame closes first, because it is what decides which draws were
     * issued; the GPU frame closes next, since the bindless table's retirement is counted against what was
     * drawn; particles close last, as their batching is consumed by the renderer's decision.
     */
    /**
     * Feeds a measured frame into the pacing and quality controllers.
     *
     * <p>Everything here comes from something already known: the elapsed wall time between HUD callbacks,
     * the framerate limit the player asked for, and whether the window has focus. No value is invented,
     * because a controller driven by a guessed number converges on the wrong answer and looks like it is
     * working while it does.
     *
     * <p>The target interval is derived from the framerate limit rather than set to a constant, so a player
     * who asks for 144 fps gets a 6.9 ms target and one who asks for 30 gets 33 ms. A limit of zero means
     * uncapped, which has no interval, so the pacer is left alone rather than told to pace at infinity.
     *
     * @param client the client, for the framerate limit and window focus
     * @param elapsedMs the measured frame time, already guarded positive and sane by the caller
     */
    private void onFrameTimed(Minecraft client, long elapsedMs) {
        this.platform.quality().record(elapsedMs);

        int limit = client.options.framerateLimit().get();
        if (limit > 0) {
            this.platform.pacer().setTargetInterval(1_000_000_000L / limit);
        }

        boolean focused = client.isWindowActive();
        this.platform.unfocused().setFocused(focused);
        this.render.updateGate().setWindowActive(focused);
    }

    /**
     * Prices this tick's movement against last tick's.
     *
     * <p>A movement packet is 16 bytes and a rotation-only one is 5, so a player standing still who turns
     * their head costs three times what it needs to. The suppressor decides which of the four cases this
     * tick is, and the decision is only as good as the delta fed to it -- hence positions and angles are
     * both taken, and the first tick after a world change is skipped because there is no previous position
     * to measure against and a delta from zero would read as a teleport.
     */
    private void onPlayerMoved() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            this.hasLastPlayer = false;
            return;
        }
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        if (!this.hasLastPlayer) {
            this.hasLastPlayer = true;
        } else {
            double dx = x - this.lastPlayerX;
            double dy = y - this.lastPlayerY;
            double dz = z - this.lastPlayerZ;
            double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double dYaw = Math.abs(yaw - this.lastPlayerYaw);
            double dPitch = Math.abs(pitch - this.lastPlayerPitch);
            this.network.movement().decide(moved, dYaw + dPitch);
        }
        this.lastPlayerX = x;
        this.lastPlayerY = y;
        this.lastPlayerZ = z;
        this.lastPlayerYaw = yaw;
        this.lastPlayerPitch = pitch;
    }

    private void endFrame() {
        this.render.endFrame();
        this.gpu.endFrame();
        this.particles.endFrame();
        this.framesClosed++;
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
        Runtime runtime = Runtime.getRuntime();
        var lines = AmdFasterHud.build(this.recorder, this.stages, this.blockCache, this.governor,
                runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(),
                this.mesh.buildTimes(), this.gpu.gpuTime());
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
    /**
     * A chunk the client now holds.
     *
     * <p>Split from unload because the two mean different things to the pipelines that count chunks. A
     * chunk arriving is one the server sent and the client stored, which is what the network pacer and the
     * worldgen counters measure; counting an unload as an arrival would inflate both, and the pacer would
     * then ask the server for a faster rate than the connection is actually delivering.
     */
    private void onChunkLoaded(int chunkX, int chunkZ) {
        invalidateAround(chunkX, chunkZ);
        this.network.chunkReceived();
        this.network.chunkStored();
        this.worldGen.chunkArrived();
        enqueueChunkMeshing(chunkX, chunkZ);
    }

    /**
     * Queues the sections of a newly arrived chunk column for meshing.
     *
     * <p>Gated on owning terrain meshing, which is the gate that actually matters when Sodium is installed:
     * Sodium has its own chunk meshing pipeline, so queueing here as well would build every section twice
     * and throw one of the two away. That is slower than either mod alone, which is the opposite of what
     * co-installing them is for.
     *
     * <p>The column is twenty-four sections, from {@code WORLD_MIN_Y} -64 to {@code WORLD_MAX_Y} 319 at
     * sixteen blocks each. Both bounds are the ones the heightmap index already uses, so the meshing range
     * and the light range agree; inventing a range here would mesh sections the light engine never
     * populated, and those would come out black.
     *
     * <p>Each section is queued as visible with no wait, because a chunk that has just arrived is by
     * definition where the player is looking. Priority is returned per section and the lowest kept, so the
     * HUD can report the worst case rather than an average that hides a starved column.
     */
    private void enqueueChunkMeshing(int chunkX, int chunkZ) {
        if (!AmdFasterClient.owns(SodiumCoexistence.Subsystem.TERRAIN_MESHING)) {
            this.meshingDeferredToSodium++;
            return;
        }
        // No builder means no worker has anything to do, so queueing would fill a bounded ring that
        // nobody drains until it started refusing jobs -- and every one of those would have been
        // counted as queued. Reporting that honestly is the difference between a mod that is waiting
        // for the game to hand it section data and one that silently does nothing.
        if (!this.mesh.isBuilding()) {
            this.meshingWaitingOnBuilder++;
            return;
        }
        int lowest = SectionCoords.sectionOf(HeightmapIndex.WORLD_MIN_Y);
        int highest = SectionCoords.sectionOf(HeightmapIndex.WORLD_MAX_Y);
        for (int sectionY = lowest; sectionY <= highest; sectionY++) {
            this.mesh.enqueue(chunkX, sectionY, chunkZ,
                    MeshJobQueue.KIND_GEOMETRY, true, 0L);
        }
    }

    /** A chunk the client no longer holds. */
    private void onChunkUnloaded(int chunkX, int chunkZ) {
        invalidateAround(chunkX, chunkZ);
    }

    private void invalidateAround(int chunkX, int chunkZ) {
        if (this.blockCache != null) {
            this.blockCache.clear();
            this.chunksInvalidated++;
        }
        // A chunk arriving or leaving changes the light in it and in its eight neighbours, so the cull
        // decisions that were made from the old light have to be dropped with it. Without this the
        // culler keeps answering from light levels that belong to a chunk that is no longer loaded.
        this.cull.onLightChange();

        // The column's geometry has to be re-meshed, but only if this mod is the one meshing it. Sodium
        // owns its own build queue, and feeding ours as well would schedule work that Sodium then does
        // anyway.
        if (AmdFasterClient.owns(SodiumCoexistence.Subsystem.CHUNK_BUILD_SCHEDULING)) {
            int lowest = SectionCoords.sectionOf(HeightmapIndex.WORLD_MIN_Y);
            int highest = SectionCoords.sectionOf(HeightmapIndex.WORLD_MAX_Y);
            for (int sectionY = lowest; sectionY <= highest; sectionY++) {
                this.render.rebuilds().markGeometry(chunkX, sectionY, chunkZ);
            }
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
        this.sound.reset();
        this.network.disconnect();
        this.entities.reset();
        // Clears the world-specific caches. This is the thing that makes a cache a cache rather than a source
        // of blocks from a world that no longer exists.
        this.assets.onWorldChange();
        this.blockEntities.reset();
        // Invalidates the HUD, cloud, weather, icon and text caches together. Missing one means a stale HUD
        // over a fresh world, or yesterday's clouds over today's weather.
        this.render.invalidateAll();
        // The beacon beam, breaking overlay, end portal and stale LOD entries are keyed on things that
        // mean nothing in the next world. invalidateAll covers the HUD and mesh caches; these are separate
        // because they are not per-frame state and survive an ordinary invalidation.
        this.render.invalidateWorldCaches();
        // Clears the world generation caches. Re-seeding happens when the next world is joined and its seed is
        // known; inventing a seed here would key the noise cache to a world that does not exist, and the first
        // lookup in the real world would then be answered from it.
        this.worldGen.onDisconnect();
        // Invalidates the block, sampler, model, section and heightmap caches. The positions are meaningless
        // across worlds, so a cache keyed on position would otherwise answer for a world that no longer exists.
        this.minecraft.onWorldChange();
        this.light.onWorldChange();
        this.cull.onWorldChange();
        // The mesh queue is per-world: sections from the last world are coordinates that no longer mean
        // anything, so the queue and the deduplicator go with them.
        this.mesh.onWorldChange();
        this.entityWorld.onWorldChange();
        this.gpu.onWorldChange();
        this.lastFrameAtMs = -1L;
        this.hasLastPlayer = false;
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
