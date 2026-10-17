package net.amdfaster.runtime;

import net.amdfaster.gpu.BindlessTextureTable;
import net.amdfaster.gpu.BufferArena;
import net.amdfaster.gpu.FrameSlotScheduler;
import net.amdfaster.gpu.InstanceDeduplicator;
import net.amdfaster.gpu.TextureUploadBatcher;
import net.amdfaster.gpu.TileBinner;
import net.amdfaster.gpu.UberShaderSelector;
import net.amdfaster.gpu.UniformCache;
import net.amdfaster.gpu.UploadRing;
import net.amdfaster.gpu.UpscalerBackendSelector;
import net.amdfaster.gpu.VramBudget;

/**
 * Runs the GPU-side decisions in one place, and releases what a frame no longer needs.
 *
 * <p>These are the components that decide how data reaches the GPU and how it is drawn: an arena so buffers are
 * carved out of one allocation rather than created one at a time, an upload ring so writes are staged ahead of
 * the frame that reads them, a frame slot scheduler so the CPU does not overwrite a buffer the GPU is still
 * reading, a uniform cache so a value that has not changed is not sent again, and the selectors that decide
 * whether to use one shader with branches or several without.
 *
 * <p>Each is correct on its own, but they all hold GPU-side state with a lifetime tied to a frame, and a buffer
 * released while the GPU is still reading it is not a slow frame -- it is a corrupted one.
 *
 * <h2>A slot is not reused until the GPU is finished with it</h2>
 *
 * <p>The CPU runs ahead of the GPU, so a buffer written this frame may still be being read from a frame ago.
 * Overwriting it is not a performance problem, it is a frame that draws garbage, and the symptom is geometry
 * that flickers between two positions. So a slot is acquired, submitted, and only marked complete when the GPU
 * signals it is done.
 *
 * <h2>The VRAM budget decides what stays resident</h2>
 *
 * <p>An APU shares its memory with the CPU, so filling it with textures takes memory the game also needs. The
 * budget is what decides what stays resident and what is evicted, and it is a decision rather than a limit --
 * evicting the wrong texture means re-uploading it every frame, which is worse than not having cached it.
 *
 * <h2>The uniform cache has to be invalidated</h2>
 *
 * <p>A uniform that has not changed does not need to be sent again, but a value that changed and was not
 * invalidated leaves the shader using the old one -- and the symptom is a camera matrix from last frame, which
 * is a world that lags the player's view. So the cache is invalidated per program and slot, and on a world
 * change.
 *
 * <p>Not thread safe at this level. The frame slot scheduler is designed for the render thread, and the upload
 * staging happens there too.
 */
public final class GpuRuntime {

    /** Sections the arena holds when the caller does not say. */
    public static final int DEFAULT_SECTIONS = 4096;

    /** Bytes per section in the arena. */
    public static final int BYTES_PER_SECTION = 65536;

    private final BufferArena arena =
            BufferArena.forSections(DEFAULT_SECTIONS, BYTES_PER_SECTION);
    private final FrameSlotScheduler slots = new FrameSlotScheduler();
    private final UniformCache uniforms = new UniformCache();
    private final InstanceDeduplicator instances = new InstanceDeduplicator();
    private final TextureUploadBatcher textures = new TextureUploadBatcher();
    private final TileBinner binner = new TileBinner();
    private final UberShaderSelector shaders = new UberShaderSelector();
    private final BindlessTextureTable bindless = new BindlessTextureTable();
    private final VramBudget vram = new VramBudget();

    private long frames;

    public BufferArena arena() {
        return this.arena;
    }

    public FrameSlotScheduler slots() {
        return this.slots;
    }

    public UniformCache uniforms() {
        return this.uniforms;
    }

    public InstanceDeduplicator instances() {
        return this.instances;
    }

    public TextureUploadBatcher textures() {
        return this.textures;
    }

    public TileBinner binner() {
        return this.binner;
    }

    public UberShaderSelector shaders() {
        return this.shaders;
    }

    public BindlessTextureTable bindless() {
        return this.bindless;
    }

    public VramBudget vram() {
        return this.vram;
    }

    /**
     * Acquires a frame slot.
     *
     * <p>A slot is not reused until the GPU is finished with it, because overwriting a buffer the GPU is still
     * reading is not a performance problem, it is a frame that draws garbage -- and the symptom is geometry
     * that flickers between two positions.
     *
     * @return the slot, or a negative number if none is free
     */
    public int acquireSlot() {
        return this.slots.acquire();
    }

    /** Reports that a slot's work has been submitted to the GPU. */
    public void submitSlot(int slot) {
        this.slots.submit(slot);
    }

    /** Reports that the GPU is finished with a slot. */
    public void completeSlot(int slot) {
        this.slots.complete(slot);
    }

    /**
     * Sets a uniform, sending it only if it changed.
     *
     * <p>A value that changed and was not invalidated leaves the shader using the old one -- and the symptom is
     * a camera matrix from last frame, which is a world that lags the player's view.
     */
    public boolean setUniform(int program, int slot, float[] value, UniformCache.Setter setter) {
        return this.uniforms.set(program, slot, value, setter);
    }

    /** Invalidates one uniform, because something changed it. */
    public void invalidateUniform(int program, int slot) {
        this.uniforms.invalidate(program, slot);
    }

    /**
     * Decides whether to use one shader with branches or several without.
     *
     * <p>One shader with branches costs the branches on every fragment, and several shaders cost a pipeline
     * switch per draw. Which is cheaper depends on how many draws there are and how mixed the variants are.
     */
    public boolean useUberShader(int drawCount, int variants, boolean mixed) {
        return this.shaders.useUberShader(drawCount, variants, mixed);
    }

    /**
     * Chooses the upscaler backend.
     *
     * <p>Keyed on what the device reports rather than on the vendor, because a rule keyed on a vendor would be
     * wrong twice -- some Intel parts have capabilities an AMD rule assumes and some AMD parts do not have the
     * ones an Intel rule assumes.
     */
    public UpscalerBackendSelector.Backend upscalerBackend(boolean hasMatrixExtensions) {
        return UpscalerBackendSelector.choose(hasMatrixExtensions);
    }

    /** Whether upscaling is worth enabling at a given saving. */
    public boolean upscalingWorthIt(boolean hasMatrixExtensions, double msSavedByLowerResolution) {
        return UpscalerBackendSelector.worthEnabling(upscalerBackend(hasMatrixExtensions),
                msSavedByLowerResolution);
    }

    /** Ends a frame, releasing what the GPU is finished with. */
    public void endFrame() {
        this.frames++;
        this.bindless.endFrame();
    }

    public long frames() {
        return this.frames;
    }

    /** Clears everything, on a world change. */
    public void onWorldChange() {
        this.frames = 0;
        this.instances.clear();
    }
}
