package net.amdfaster.runtime;

import net.amdfaster.gpu.BlockBindings;
import net.amdfaster.gpu.CoalescingVerifier;
import net.amdfaster.gpu.CullingPipeline;
import net.amdfaster.gpu.EntityUploadLayout;
import net.amdfaster.gpu.GpuBuffer;
import net.amdfaster.gpu.IndirectCountDraw;
import net.amdfaster.gpu.IndirectDrawBatcher;
import net.amdfaster.gpu.MeshUploadLayout;
import net.amdfaster.gpu.OccupancyPlanner;
import net.amdfaster.gpu.MeshletGpuLayout;
import net.amdfaster.gpu.SectionUpload;
import net.amdfaster.gpu.SpecializationPlanner;
import net.amdfaster.mesh.SectionMesh;
import net.amdfaster.perf.GpuTimestampQuery;
import net.amdfaster.vk.FrameDispatcher;
import net.amdfaster.gpu.BindlessTextureTable;
import net.amdfaster.gpu.BufferArena;
import net.amdfaster.gpu.BufferPool;
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

    /**
     * Recycles GPU buffers between remeshes instead of asking the driver for a new one each time.
     *
     * <p>Remeshing a section retires its buffers and needs new ones, so the allocation traffic scales with
     * section churn rather than with anything the frame is doing. Freeing one on RADV walks libdrm's
     * process-wide VA hole list, which in a measured renderer of this exact shape put 85 to 96 per cent of
     * all allocation calls behind small per-object buffers.
     */
    private final BufferPool bufferPool = new BufferPool();
    private final UniformCache uniforms = new UniformCache();
    private final InstanceDeduplicator instances = new InstanceDeduplicator();
    /**
     * The texture upload batcher, or {@code null} until the caller supplies an uploader.
     *
     * <p>It needs somewhere for textures to go, which only a device can provide, so this class cannot build it
     * -- it is handed one when the wiring layer has a device, and until then the batcher is absent rather than
     * sending textures nowhere. A batcher built against the wrong device would upload to a device that is not
     * being drawn to, which is a texture that never arrives.
     */
    private TextureUploadBatcher textures;

    /**
     * The GPU timestamp query, or {@code null} until a device supplies one.
     *
     * <p>It needs a {@code VkDevice} and the device's own {@code timestampPeriod} and
     * {@code timestampValidBits}, none of which exist before the adapter is chosen. Building it early would
     * mean inventing a tick resolution, and a wrong one does not round off -- it scales every GPU figure by
     * a factor.
     */
    private GpuTimestampQuery gpuTime;
    private final TileBinner binner = new TileBinner();
    private final UberShaderSelector shaders = new UberShaderSelector();
    private final BindlessTextureTable bindless = new BindlessTextureTable();
    private final VramBudget vram = new VramBudget();

    /** Batches draws into indirect commands instead of one call each. */
    private final IndirectDrawBatcher indirect = new IndirectDrawBatcher();

    /**
     * The compute pipeline that culls on the GPU, or {@code null} until a device is supplied.
     *
     * <p>It needs a {@code VkContext}, which only the device can provide, so it is handed one rather than
     * built here -- a pipeline created against the wrong device would not be valid for the one in use.
     */
    private CullingPipeline cullingPipeline;

    /**
     * The staging buffer for uploads, or {@code null} until a device is supplied.
     *
     * <p>Same reason as the pipeline: the buffer belongs to a device and cannot be built without one.
     */
    private GpuBuffer staging;

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

    /**
     * Supplies the texture uploader, once the wiring layer has a device to upload to.
     *
     * @return this, so the caller can chain
     */
    public GpuRuntime withUploader(TextureUploadBatcher.Uploader uploader) {
        this.textures = new TextureUploadBatcher(uploader);
        return this;
    }

    /**
     * Supplies the timestamp query once a device exists.
     *
     * @param query the query pool, built against the chosen device and its reported limits
     * @return this runtime
     */
    public GpuRuntime withGpuTime(GpuTimestampQuery query) {
        this.gpuTime = query;
        return this;
    }

    /**
     * The GPU time query, or {@code null} before a device supplied one.
     *
     * <p>Null is a real state here and not a gap: there is no GPU figure to report until a device exists,
     * and reporting zero would say the GPU is idle rather than unmeasured.
     */
    public GpuTimestampQuery gpuTime() {
        return this.gpuTime;
    }

    /**
     * The texture upload batcher, or {@code null} if no uploader has been supplied.
     */
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

    /** The indirect-draw batcher. */
    public IndirectDrawBatcher indirect() {
        return this.indirect;
    }

    /** Supplies the compute pipeline once a device exists. */
    public GpuRuntime withCullingPipeline(CullingPipeline pipeline) {
        this.cullingPipeline = pipeline;
        return this;
    }

    /** The GPU culling pipeline, or {@code null} before a device supplied one. */
    public CullingPipeline cullingPipeline() {
        return this.cullingPipeline;
    }

    /** Supplies the staging buffer once a device exists. */
    public GpuRuntime withStaging(GpuBuffer buffer) {
        this.staging = buffer;
        return this;
    }

    /** The staging buffer, or {@code null} before a device supplied one. */
    public GpuBuffer staging() {
        return this.staging;
    }

    /** The descriptor bindings the pipelines expect. */
    public java.util.List<BlockBindings.Binding> bindings() {
        return BlockBindings.bindings();
    }

    /**
     * Plans the upload for one section.
     *
     * <p>Wires the meshlet layout, the section upload buffers, the mesh upload layout and the entity
     * instance layout together -- the offsets have to agree across all four or the GPU reads one section's
     * data as another's.
     *
     * @param mesh the section's mesh
     * @param instances how many entity instances this frame carries
     * @return how many bytes the section's positions occupy
     */
    public int planUpload(SectionMesh mesh, int instances) {
        MeshletGpuLayout meshlets = MeshletGpuLayout.forSection(mesh);
        SectionUpload.Buffers buffers = SectionUpload.allocate(meshlets);
        MeshUploadLayout layout = MeshUploadLayout.forSection(mesh, 0L);
        EntityUploadLayout entities = EntityUploadLayout.of(instances);
        return SectionUpload.positionBytes(meshlets)
                + SectionUpload.attributeBytes(meshlets)
                + layout.positionsBytes()
                + entities.instanceCount();
    }

    /**
     * Reports how much of the fetched bandwidth a section's mesh buffers actually use.
     *
     * <p>Asked at layout time rather than per frame, because the answer depends only on the strides and
     * those are fixed once the meshlet layout is chosen. The worst stream is the one worth reading: the
     * light and index slots are not whole cache lines, so half the meshlets start partway into one and
     * the fetch brings data the wave does not read.
     *
     * @param mesh a section's mesh, whose meshlet count sets the layout
     * @return the stream with the lowest worst-case efficiency
     */
    public CoalescingVerifier.Verdict worstCoalescing(SectionMesh mesh) {
        MeshletGpuLayout meshlets = MeshletGpuLayout.forSection(mesh);
        CoalescingVerifier.Verdict worst = null;
        for (CoalescingVerifier.Verdict verdict : CoalescingVerifier.verifySection(meshlets)) {
            if (worst == null || verdict.efficiencyWorst() < worst.efficiencyWorst()) {
                worst = verdict;
            }
        }
        return worst;
    }

    /**
     * Plans which shader properties are folded into specialization constants.
     *
     * <p>Asked once per shader rather than per frame, because the answer depends on the property set and
     * the pipeline budget and neither moves during a session. The plan is what keeps the folding from
     * turning into a permutation explosion: three independent toggles want eight pipelines, and the third
     * is dropped rather than compiled, since a fifth variant is more likely to be a rare combination than
     * a common one and costs a hitch to build for one bind.
     *
     * @param shader     the shader being specialised
     * @param properties its candidate properties, most valuable first
     * @return which ones got IDs and how many pipelines that costs
     */
    public SpecializationPlanner.Plan planSpecialization(String shader,
                                                        SpecializationPlanner.Property... properties) {
        return SpecializationPlanner.plan(shader, properties);
    }

    /**
     * Picks the wave size and workgroup size a compute shader should dispatch with.
     *
     * <p>Asked when the pipeline is built rather than per dispatch, because the answer depends on the
     * register count the driver reports for the compiled shader and that does not change between frames.
     * The choice flips on that count: wave64 puts more lanes in flight while the shader is cheap and
     * fewer once it is not, since a wave64 draws twice the lanes from the same register file and so takes
     * twice the slots.
     *
     * @param footprint    the shader's register and LDS cost
     * @param architecture the device it will run on
     * @return the occupancy that follows, at the recommended wave size
     */
    public OccupancyPlanner.Occupancy planOccupancy(OccupancyPlanner.Footprint footprint,
                                                    net.amdfaster.platform.AmdArchitecture architecture) {
        int wave = OccupancyPlanner.recommendWaveSize(footprint, architecture);
        return OccupancyPlanner.occupancy(footprint, wave, architecture);
    }

    /** The buffer pool that recycles GPU buffers between remeshes. */
    public BufferPool bufferPool() {
        return this.bufferPool;
    }

    /**
     * Retires a section's buffers and releases the ones whose fences have signalled.
     *
     * <p>Both halves have to happen together. A retired buffer cannot be reused until the frame that
     * queued it has finished, and releasing is what makes the pool actually serve requests -- without it
     * every acquisition allocates fresh and the pool is just a list of buffers nothing can have.
     *
     * @param bytes                the sizes being retired this frame
     * @param retiredFrame         the frame being retired
     * @param releasedThroughFrame the newest frame whose fence has signalled
     * @return how many buffers became reusable
     */
    public int recycle(int[] bytes, long retiredFrame, long releasedThroughFrame) {
        for (int size : bytes) {
            this.bufferPool.retire(size, retiredFrame);
        }
        return this.bufferPool.releaseThrough(releasedThroughFrame);
    }

    /**
     * Prepares the draw call for the cull shader's output.
     *
     * <p>The cull shader already writes dense {@code VkDrawIndexedIndirectCommand} entries and a separate
     * visible count, so the draw reads how much survived from the device instead of the host reading the
     * cull results back -- which would stall every frame on a value only known after the compute pass.
     *
     * <p>What this adds is the arithmetic that has to be right before the call: the buffer has to hold the
     * count it claims, and {@code n} commands occupy {@code stride x (n - 1) + 20} bytes rather than
     * {@code stride x n}, because the last command ends at its own end. Overstating it is a validation
     * error before anything draws.
     *
     * @param drawCount   how many draws the buffer may hold
     * @param bufferBytes how large the command buffer is
     * @return the validated call, or null when the buffer cannot hold that many
     */
    public IndirectCountDraw.Call prepareIndirectDraw(int drawCount, long bufferBytes) {
        return IndirectCountDraw.prepare(drawCount, bufferBytes, IndirectCountDraw.COUNT_OFFSET_BYTES);
    }

    /**
     * How many draws the device will actually execute.
     *
     * @param countInBuffer      what the cull shader wrote
     * @param maxDrawCount       the cap the call was prepared with
     * @param multiDrawIndirect  whether the device feature is enabled
     * @param maxDrawIndirectCount the device's own limit on indirect draws
     * @return the draws that will run, or 0 when the call is not legal as prepared
     */
    /**
     * Plans one frame's submissions and the barriers between them.
     *
     * <p>The parts already existed -- the context owns the queues, the culling pipeline records the
     * dispatch, the indirect count draw sizes the call -- but nothing put them in order on a queue or said
     * what had to be flushed between them, and that is the part where being wrong is incorrect rather than
     * merely slow.
     *
     * <p>The rule that matters is that crossing from the cull to the indirect draw needs two independent
     * things. Submission order gives execution ordering only within one queue, so a semaphore is needed
     * across queues. And a semaphore orders execution but does not flush caches, so a pipeline barrier is
     * also needed. The barrier has to name both reads the draw makes: the commands themselves, and the side
     * data whose quad count becomes {@code indexCount}. Naming only the first leaves a stale index count
     * and a draw of the wrong length.
     *
     * @param cullMillis       how long the cull dispatch takes
     * @param computeQueueExists whether a separate compute queue was found
     * @param uploadsThisFrame whether staging uploads precede the cull
     * @param architecture     the identified generation, which sets the hand-off stall
     * @return the frame plan, or null when it does not validate
     */
    public FrameDispatcher.Frame planFrame(double cullMillis, boolean computeQueueExists,
                                           boolean uploadsThisFrame,
                                           net.amdfaster.platform.AmdArchitecture architecture) {
        double stall = FrameDispatcher.handoffStallMillis(architecture);
        boolean async = FrameDispatcher.useAsyncCompute(cullMillis, stall, computeQueueExists);
        FrameDispatcher.Frame frame = FrameDispatcher.plan(async, uploadsThisFrame);
        // A plan that does not validate is not returned, because the caller would record it and the
        // failure would be geometry rather than an exception.
        return FrameDispatcher.validate(frame) == null ? frame : null;
    }

    public int indirectDrawsExecuted(int countInBuffer, int maxDrawCount, boolean multiDrawIndirect,
                                     int maxDrawIndirectCount) {
        int executed = IndirectCountDraw.executed(countInBuffer, maxDrawCount);
        if (!IndirectCountDraw.multiDrawAllowed(multiDrawIndirect, executed)
                || !IndirectCountDraw.withinDeviceLimit(executed, maxDrawIndirectCount)) {
            // Either the feature is missing or the device caps indirect draws below the batch, and in
            // both cases the batch cannot be issued as one call. Zero rather than a guess, so the caller
            // falls back to per-meshlet draws instead of issuing an invalid one.
            return 0;
        }
        return executed;
    }
}
