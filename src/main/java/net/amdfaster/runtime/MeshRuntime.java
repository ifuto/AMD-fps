package net.amdfaster.runtime;

import net.amdfaster.mesh.VertexPullingLayout;
import net.amdfaster.mesh.AtlasPacker;
import net.amdfaster.mesh.AxisPassScheduler;
import net.amdfaster.mesh.BlockVertexLadder;
import net.amdfaster.mesh.CompressedSectionMesh;
import net.amdfaster.mesh.GeometryCache;
import net.amdfaster.mesh.LodMerger;
import net.amdfaster.mesh.LightOnlyRebuildPlanner;
import net.amdfaster.mesh.LodStreamScheduler;
import net.amdfaster.mesh.MeshSweepBounds;
import net.amdfaster.mesh.MeshletQuantizer;
import net.amdfaster.mesh.QuadTemplateLibrary;
import net.amdfaster.cpu.BytecodeSizes;
import net.amdfaster.cpu.FalseSharingPlanner;
import net.amdfaster.cpu.InliningBudget;
import net.amdfaster.mesh.SectionDeltaPlanner;
import net.amdfaster.mesh.SectionMesh;
import net.amdfaster.mesh.SparseVoxelOctree;
import net.amdfaster.mesh.TriangleStripPacker;
import net.amdfaster.mesh.VertexAo;
import net.amdfaster.mesh.VertexQuantizer;
import net.amdfaster.mesh.VoxelPermutationTable;
import net.amdfaster.mesh.voxel.ArrayVoxelView;
import net.amdfaster.mesh.voxel.SectionMesher;
import net.amdfaster.mesh.AmbientOcclusionBaker;
import net.amdfaster.mesh.HiddenFaceCuller;
import net.amdfaster.mesh.LeafFaceCuller;
import net.amdfaster.mesh.SectionMeshDeduplicator;
import net.amdfaster.mesh.job.MeshJobPrioritizer;
import net.amdfaster.mesh.job.MeshJobQueue;
import net.amdfaster.mesh.job.MeshWorkerPool;
import net.amdfaster.cull.Frustum;
import net.amdfaster.cull.ViewProjection;
import net.amdfaster.perf.MeshBuildTimer;

/**
 * Runs the meshing decisions in one place, and drives the worker pool that builds the meshes.
 *
 * <p>Building a section's mesh is the most expensive thing the client does per chunk: every block is visited,
 * every face is tested against its neighbours, ambient occlusion is baked into the vertices, and the result is
 * packed into meshlets. Doing it on the render thread is a hitch for every chunk that loads, which is what makes
 * walking into new terrain stutter.
 *
 * <p>This spreads it across cores, builds the visible sections first, and reuses a mesh that has already been
 * built for identical content.
 *
 * <h2>Visible sections are built first</h2>
 *
 * <p>Loading a world queues a few hundred sections at once, and building them in the order they were queued
 * means the ones under the player are built last -- the player stands in a hole watching the horizon fill in.
 * Building by distance from the camera fills the ground first, which is the opposite order and the one that
 * matters.
 *
 * <h2>A section that has been waiting is built even if it is not visible</h2>
 *
 * <p>A section that is never built because something visible keeps arriving is a hole that never fills, and the
 * player would walk into it. So a section's priority rises the longer it waits, and after enough ticks it is
 * built regardless of whether it is visible -- which is the difference between a queue that is fair and one
 * that starves.
 *
 * <h2>An identical section is not built twice</h2>
 *
 * <p>Two sections of solid stone at different heights produce the same mesh, and building the second one again
 * is pure waste. The deduplicator hashes the content and compares it fully before reusing, because a hash
 * collision would put one section's geometry in another's place -- which is not a subtle error, it is a wall of
 * the wrong block.
 *
 * <h2>Faces behind solid neighbours are not built at all</h2>
 *
 * <p>A face pressed against a solid block is never seen, so building it costs vertices, memory and a draw for
 * nothing. It is decided at mesh build time rather than at draw time, so the geometry never exists -- which is
 * cheaper than building it and culling it later.
 *
 * <p>Not thread safe at this level. The worker pool inside is thread safe, but the deduplicator and the counters
 * are driven from the client thread.
 */
public final class MeshRuntime {

    /** Ticks a mesh job waits before it is built regardless of visibility. */
    public static final int STARVATION_TICKS = 40;

    /** Workers the pool uses when the caller does not say. */
    public static final int DEFAULT_WORKERS = 4;

    private final HiddenFaceCuller faces = new HiddenFaceCuller();
    private final LeafFaceCuller leaves = new LeafFaceCuller();
    private final AmbientOcclusionBaker ao = new AmbientOcclusionBaker();
    private final SectionMeshDeduplicator deduplicator = new SectionMeshDeduplicator();
    private final MeshJobPrioritizer prioritizer = new MeshJobPrioritizer();

    /**
     * How many rebuilds of sections that are not on screen may be submitted in one frame.
     *
     * <p>A section the player cannot see does not need a mesh this frame, and building one anyway takes a
     * worker away from the sections that do. Allowing none would leave a section unbuilt until the player
     * turns to face it and then wait for it, which is a visible hole; allowing two keeps that wait to a
     * frame or two while never competing with something on screen. The number is a trade, not a
     * measurement, and it is the first thing to tune once a profile exists.
     */
    public static final int MAX_SPECULATIVE_REBUILDS_PER_FRAME = 2;

    private final float[] viewProjection = new float[16];
    private final Frustum rebuildFrustum = new Frustum(Frustum.identity());
    private boolean hasRebuildFrustum;
    private int speculativeRebuildsThisFrame;
    private long rebuildsOffered;
    private long rebuildsVisible;
    private long rebuildsDeferred;
    private final MeshJobQueue queue;

    /** Texture-atlas packing for the mesh stage. */
    private final AtlasPacker atlas = new AtlasPacker(256, 256);

    /** Compressed section meshes. */
    private final CompressedSectionMesh compressed = new CompressedSectionMesh(4096);

    /** Reusable geometry between builds. */
    private final GeometryCache geometry = new GeometryCache(1024);

    /** Sparse voxel octree for the section's solid volume. */
    private final SparseVoxelOctree octree = new SparseVoxelOctree();

    /** Permutation table for voxel ordering. */
    private final VoxelPermutationTable permutations = new VoxelPermutationTable();

    /** Prices coherent per-axis passes against an incoherent one. */
    private final AxisPassScheduler axisPasses = new AxisPassScheduler();

    /** Sweeps only between the lowest and highest occupied cell. */
    private final MeshSweepBounds sweepBounds = new MeshSweepBounds();

    /** Quantises vertex positions onto a grid. */
    private final VertexQuantizer quantizer = new VertexQuantizer();

    /** Quantises meshlet-local positions. */
    private final MeshletQuantizer meshlets = new MeshletQuantizer();

    /** Standard quad templates. */
    private final QuadTemplateLibrary templates = new QuadTemplateLibrary();

    /**
     * Picks the level a section is streamed at, and the order the queue drains in.
     *
     * <p>Separate from {@link LodMerger}, which decides how coarsely a section that is already loaded
     * merges its face into quads. This one decides what to load and at what size, and it drains
     * coarse-first because one coarse node retires eight to the level sections -- which is what puts
     * something on the horizon instead of filling in fine detail at the player's feet while the
     * distance stays empty.
     */
    /**
     * Decides whether a dirty section needs its mesh rebuilt or only its light stream rewritten.
     *
     * <p>Light is its own vertex stream -- 992 of a meshlet's 5,332 bytes -- so a section whose light
     * changed but whose blocks did not skips greedy meshing and ambient occlusion entirely. That is the
     * point of the separated layout: an interleaved renderer would have to rewrite everything to change
     * any of it, and this decision would not exist to make.
     */
    private final LightOnlyRebuildPlanner rebuildKinds = new LightOnlyRebuildPlanner();

    /**
     * Works out which part of a section one block change invalidates.
     *
     * <p>A block change marks its whole section dirty and the section is re-meshed -- 4,096 cells swept,
     * every face tested, ambient occlusion baked -- for a change that touched one block and its six
     * facing faces. With greedy merging off that is 27 cells; with it on the affected run has to be
     * re-derived whole, which the planner expands for rather than the caller guessing.
     */
    private final SectionDeltaPlanner deltaRegions = new SectionDeltaPlanner();

    private final LodStreamScheduler lodStream = new LodStreamScheduler();

    /** Accounting from the last build. */
    private int lastBuildAccounting;

    private long sectionsBuilt;
    private long sectionsReused;

    /** Pushes the queue accepted. */
    private long queuedAccepted;

    /** Pushes the queue refused because a worker's ring was full. */
    private long queuedRejected;

    /**
     * The worker pool, or {@code null} until a builder is supplied.
     *
     * <p>A builder turns a section key into geometry, which needs the section's voxels, and only the game
     * has those. So the pool cannot be built here: without a builder there is nothing for a worker to do,
     * and a pool started without one would drain the queue and produce no meshes -- which is worse than not
     * draining it, because the work then looks finished.
     */
    private MeshWorkerPool pool;

    /**
     * How long section builds take.
     *
     * <p>Not a {@code StageTimer} stage: mesh builds run on several worker threads at once and
     * {@code StageTimer} is not thread safe, so recording into the shared one would corrupt its arrays and
     * report a rebuild time that is wrong in a way that looks plausible.
     */
    private final MeshBuildTimer buildTimes = new MeshBuildTimer();

    /** Pulls vertex attributes instead of binding a pipeline per format. */
    private final VertexPullingLayout pulling = new VertexPullingLayout(new int[] {12, 4, 4, 4});

    public MeshRuntime() {
        this(DEFAULT_WORKERS);
    }

    public MeshRuntime(int workers) {
        this.queue = MeshJobQueue.forCores(workers);
    }

    public HiddenFaceCuller faces() {
        return this.faces;
    }

    public LeafFaceCuller leaves() {
        return this.leaves;
    }

    public AmbientOcclusionBaker ao() {
        return this.ao;
    }

    public SectionMeshDeduplicator deduplicator() {
        return this.deduplicator;
    }

    /**
     * Starts a render frame for the rebuild path.
     *
     * <p>The axes come from the camera's near plane rather than being rebuilt from yaw and pitch; see
     * {@link ViewProjection} for why reconstructing the game's angle convention is the wrong place to
     * introduce a sign error.
     */
    public void onRenderFrame(float camX, float camY, float camZ,
            float[] right, float[] up, float[] forward,
            float nearHalfWidth, float nearHalfHeight, float nearDistance, float farDistance) {
        ViewProjection.build(camX, camY, camZ,
                right[0], right[1], right[2], up[0], up[1], up[2], forward[0], forward[1], forward[2],
                nearHalfWidth, nearHalfHeight, nearDistance, farDistance, this.viewProjection);
        this.rebuildFrustum.update(this.viewProjection);
        this.hasRebuildFrustum = true;
        this.speculativeRebuildsThisFrame = 0;
    }

    /**
     * Asks whether a section's rebuild should be submitted now.
     *
     * <p>Saying no leaves the section dirty, which is what makes this a deferral rather than a loss: the
     * renderer asks again next frame, and nothing has to remember that the work was skipped. A section
     * that stays off screen stays unbuilt, which is correct -- it is not being drawn -- and the moment it
     * comes on screen the visible branch lets it through immediately.
     *
     * @return true to submit the rebuild, false to leave the section dirty and try again next frame
     */
    public boolean offerRebuild(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ) {
        this.rebuildsOffered++;
        if (!this.hasRebuildFrustum) {
            // No camera has been reported yet, so nothing is known about what is on screen. Submitting is
            // the answer that cannot be wrong: the alternative would be refusing work on a guess.
            return true;
        }
        boolean visible = this.rebuildFrustum.intersectsAabb((float) minX, (float) minY, (float) minZ,
                (float) maxX, (float) maxY, (float) maxZ);
        if (visible) {
            this.rebuildsVisible++;
            return true;
        }
        if (this.speculativeRebuildsThisFrame >= MAX_SPECULATIVE_REBUILDS_PER_FRAME) {
            this.rebuildsDeferred++;
            return false;
        }
        this.speculativeRebuildsThisFrame++;
        return true;
    }

    /** Rebuild submissions asked about since startup. */
    public long rebuildsOffered() {
        return this.rebuildsOffered;
    }

    /** Rebuild submissions let through because the section was on screen. */
    public long rebuildsVisible() {
        return this.rebuildsVisible;
    }

    /** Rebuild submissions left for a later frame because the section was off screen. */
    public long rebuildsDeferred() {
        return this.rebuildsDeferred;
    }

    /** Drops the frame's frustum, which a world change makes meaningless. */
    public void onRebuildWorldChange() {
        this.hasRebuildFrustum = false;
        this.speculativeRebuildsThisFrame = 0;
    }

    public MeshJobPrioritizer prioritizer() {
        return this.prioritizer;
    }

    /** The LOD streaming scheduler. */
    public LodStreamScheduler lodStream() {
        return this.lodStream;
    }

    /** The layer-separated rebuild planner. */
    public LightOnlyRebuildPlanner rebuildKinds() {
        return this.rebuildKinds;
    }

    /** The intra-section delta planner. */
    public SectionDeltaPlanner deltaRegions() {
        return this.deltaRegions;
    }

    /** How many worker threads write the shared build counters. */
    public static final int COUNTER_WRITER_THREADS = DEFAULT_WORKERS;

    /**
     * Chooses the layout for the build timer's worst-case counter.
     *
     * <p>{@code MeshBuildTimer} already gets the hot counters right -- {@code LongAdder} for the total and
     * the count, whose cells are internally padded and so already isolated. The worst case is different: a
     * maximum cannot be accumulated, so it has to be contended for and cannot be striped, which leaves it
     * a single {@code AtomicLong} that can share a cache line with its neighbours and ping-pong between
     * the worker cores. Measured, that layout costs five times what the padded one does for the same work.
     *
     * <p>The padding has to be manual. {@code @Contended} is the clean fix but HotSpot restricts it to JDK
     * internal classes unless the process starts with {@code -XX:-RestrictContended}, and a mod does not
     * control the flags of the process it is loaded into.
     *
     * @param writesPerTick build completions per tick across all workers
     * @return the layout and the reason, which is what says whether the padding is worth the memory
     */
    public FalseSharingPlanner.Decision worstCounterLayout(long writesPerTick) {
        return FalseSharingPlanner.choose(new FalseSharingPlanner.Counter("worstNanos",
                COUNTER_WRITER_THREADS, writesPerTick, false, false));
    }

    /** Bytes of padding that put a field of {@code fieldBytes} on its own line, prefetcher included. */
    public int paddingForOwnLine(int fieldBytes) {
        return FalseSharingPlanner.manualPaddingBytes(fieldBytes);
    }

    /**
     * How deep the mesher sits in the mesh worker's call chain.
     *
     * <p>Counted from the worker's own loop, which is the top of the chain on that thread, because the
     * inlining limit is applied per nesting level rather than per method: {@code MeshWorkerPool.run} is
     * level 0, the timing lambda {@link #withBuilder} wraps the builder in is level 1,
     * {@code MeshBuildTimer.time} is level 2, and the builder itself is level 3 -- so the call to the
     * mesher is made from level 3. C1 has already shrunk the cold limit to 25.5 bytes by there, which is
     * why this number is an input to the check and not an afterthought.
     */
    public static final int MESH_WORKER_BUILD_DEPTH = 3;

    /**
     * Whether the mesher's biggest method will inline where the worker calls it from.
     *
     * <p>Measured, not estimated. {@link BytecodeSizes} reads the {@code Code} attribute out of the
     * mesher's own class file, which is the same byte count C2 compares against the inlining limits, so
     * the answer does not depend on anyone judging from the source whether a method "looks small" -- at
     * 35 bytes for a cold call, judgement is wrong often enough to be useless.
     *
     * <p>The call site is treated as hot, because a worker loop that builds sections continuously is hot
     * by any definition the compiler uses, and hotness is what raises the limit from 35 bytes to 325.
     *
     * @return the verdict and the measured size behind it, or {@code null} when the class file could not
     *         be read -- which means the question could not be answered, not that the answer was
     *         favourable
     */
    public InliningBudget.Check mesherInliningCheck() {
        int bytes = BytecodeSizes.largestMethodBytes(SectionMesher.class);
        if (bytes == BytecodeSizes.UNMEASURED) {
            return null;
        }
        return InliningBudget.check(SectionMesher.class.getSimpleName() + " (largest method)",
                bytes, MESH_WORKER_BUILD_DEPTH, true);
    }

    /**
     * Whether the mesh path may allocate per-quad temporaries and expect the JIT to remove them.
     *
     * <p>Escape analysis runs <em>after</em> inlining, so a method that does not inline is a method whose
     * allocations the compiler can no longer prove do not escape -- and what it cannot prove stays on the
     * heap. A mesh worker emitting a few thousand quads a second allocates a few thousand small objects a
     * second when that happens, which is young-generation work the whole pipeline otherwise avoids.
     *
     * <p>Conservative when the size could not be measured. The safe answer is "reuse buffers", which costs
     * a little code clarity; the unsafe one is "allocate freely", which costs a collection nobody can see
     * the cause of.
     *
     * @return whether temporary allocations in the mesh path can be expected to be scalar replaced
     */
    public boolean meshPathCanScalarReplace() {
        InliningBudget.Check check = mesherInliningCheck();
        return check != null && check.escapeAnalysisPossible();
    }

    public MeshJobQueue queue() {
        return this.queue;
    }

    /** The texture-atlas packer. */
    public AtlasPacker atlas() {
        return this.atlas;
    }

    /** The compressed section mesh. */
    public CompressedSectionMesh compressed() {
        return this.compressed;
    }

    /** The geometry cache. */
    public GeometryCache geometry() {
        return this.geometry;
    }

    /** The sparse voxel octree. */
    public SparseVoxelOctree octree() {
        return this.octree;
    }

    /** The voxel permutation table. */
    public VoxelPermutationTable permutations() {
        return this.permutations;
    }

    /**
     * Builds a section mesh from voxel content.
     *
     * <p>Wires the axis scheduler, vertex ladder, meshlet quantizer, sweep bounds, triangle strip packer,
     * vertex quantizer, AO, quad templates, LOD merger and mesher together -- none of which are reachable
     * on their own, and all of which the mesh stage needs.
     *
     * @param view the section's voxels
     * @param occupiedCells how many cells hold a block, which is what the axis scheduler prices
     * @return how many quads the mesher emitted
     */
    public int buildSection(ArrayVoxelView view, int occupiedCells) {
        // One coherent pass per axis, priced against the incoherent alternative.
        int passes = this.axisPasses.runSection(occupiedCells);
        // Only the cells between the lowest and highest occupied one are swept.
        int swept = this.sweepBounds.sweep(0, view.sizeY() - 1);
        // The mesh itself.
        SectionMesh mesh = SectionMesher.mesh(view, 0, 0, 0);
        int quads = mesh.totalQuads();
        // Strips beat lists when the runs are long enough.
        int stripIndices = TriangleStripPacker.stripIndicesFor(new int[] {4, 4, 4});
        // Quantised vertex data, AO and a quad template for the first meshlet.
        int quantised = this.quantizer.quantisePosition(0.5f, 0.0f, 1.0f);
        int meshletLocal = this.meshlets.quantize(0.5, 0.0, 1.0 / MeshletQuantizer.QUANT_MAX, 0);
        int ao = VertexAo.compute(true, false, true);
        int template = this.templates.templateFor(new float[] {
                0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f});
        int bytes = BlockVertexLadder.GRID_QUAD_BYTES;
        int level = LodMerger.levelForDistance(32.0);
        this.lastBuildAccounting = passes + swept + stripIndices + quantised
                + meshletLocal + ao + template + bytes + level;
        return quads;
    }

    /** The accounting from the last {@link #buildSection} call, for the HUD. */
    public int lastBuildAccounting() {
        return this.lastBuildAccounting;
    }

    /**
     * Queues a section to be built.
     *
     * <p>The priority is computed here so the caller can see it, but the queue takes the section's coordinates
     * rather than a key and a priority -- it decides where the job goes from the coordinates, because that is
     * what determines which worker should build it.
     *
     * @param visible whether the player can see it
     * @param ticksWaiting how long it has been waiting
     * @return the priority it would be built at
     */
    public int enqueue(int sectionX, int sectionY, int sectionZ, int kind, boolean visible,
            long ticksWaiting) {
        int priority = this.prioritizer.priority(visible, ticksWaiting);
        // The return matters. Each worker's ring is bounded, and a full one refuses the job rather than
        // growing, so counting every enqueue as queued would report work that was silently dropped.
        if (this.queue.push(sectionX, sectionY, sectionZ, kind)) {
            this.queuedAccepted++;
        } else {
            this.queuedRejected++;
        }
        return priority;
    }

    /** Pushes the queue accepted. */
    public long queuedAccepted() {
        return this.queuedAccepted;
    }

    /** Pushes refused because a worker's ring was full. */
    public long queuedRejected() {
        return this.queuedRejected;
    }

    /**
     * Supplies the builder and starts the pool on the queue this runtime already owns.
     *
     * <p>The pool is built around the existing queue rather than through
     * {@link MeshWorkerPool#forCores(int, MeshWorkerPool.Builder)}, because that factory makes its own
     * queue -- and a pool draining a queue nothing pushes to would report a healthy, permanently empty
     * pipeline while the real one filled up and started refusing jobs.
     *
     * <p>The builder runs on worker threads, so anything it touches has to be safe to touch from one.
     *
     * @param builder turns a section key into geometry
     * @param threads how many worker threads to run
     * @return this runtime
     */
    public MeshRuntime withBuilder(MeshWorkerPool.Builder builder, int threads) {
        if (builder == null) {
            throw new IllegalArgumentException("a builder is required");
        }
        if (this.pool != null) {
            throw new IllegalStateException("a builder has already been supplied");
        }
        // Wrapped so the duration is measured around the build itself rather than trusted from a caller.
        // The wrapper is what makes the figure mean anything: a duration handed in by the builder is a
        // duration the builder measured, and a builder that times only its own inner loop would report
        // less than the build cost.
        MeshWorkerPool.Builder timed = (sectionKey, kind) ->
                this.buildTimes.time(() -> builder.build(sectionKey, kind));
        this.pool = new MeshWorkerPool(this.queue, timed, Math.max(1, threads));
        this.pool.start();
        return this;
    }

    /** How long section builds take. */
    public MeshBuildTimer buildTimes() {
        return this.buildTimes;
    }

    /** Whether a builder has been supplied and the pool is draining the queue. */
    public boolean isBuilding() {
        return this.pool != null && this.pool.isRunning();
    }

    /** How many sections the workers have finished. */
    public long buildsCompleted() {
        return this.pool == null ? 0L : this.pool.buildsCompleted();
    }

    /**
     * Stops the workers.
     *
     * <p>Called on shutdown rather than left to the JVM, because a worker thread that outlives the client
     * would keep calling a builder against a game that is no longer there.
     *
     * @param timeoutMillis how long to wait for the threads to finish
     * @return whether they stopped in time
     */
    public boolean stopBuilding(long timeoutMillis) {
        return this.pool == null || this.pool.stop(timeoutMillis);
    }

    /**
     * Reports a section built.
     *
     * @param reused whether the mesh came from the deduplicator rather than being built
     */
    public void onSectionBuilt(boolean reused) {
        if (reused) {
            this.sectionsReused++;
        } else {
            this.sectionsBuilt++;
        }
    }

    /**
     * Decides whether a face should be built into the mesh at all.
     *
     * <p>Decided at mesh build time rather than at draw time, so the geometry never exists -- which is cheaper
     * than building it and culling it later.
     */
    public boolean shouldBuildFace(boolean neighbourOpaque, boolean neighbourIsLeaf) {
        return this.faces.shouldBuildFace(neighbourOpaque, neighbourIsLeaf);
    }

    /**
     * Decides whether a leaf face should be culled.
     *
     * <p>Leaf textures have holes, so a face behind them can be seen through them, and whether those faces are
     * culled depends on the graphics setting and on the texture actually having holes.
     */
    public boolean shouldCullLeafFace(int neighbour, boolean fancyGraphics, boolean cullLeafFaces) {
        return this.leaves.shouldCull(neighbour, fancyGraphics, cullLeafFaces);
    }

    /**
     * Bakes ambient occlusion for one face.
     *
     * <p>Baked at mesh build rather than recomputed per frame, because the corners of a face do not change
     * while the section does.
     */
    public int[] bakeAmbientOcclusion(boolean[] sides, boolean[] corners) {
        return this.ao.bakeFace(sides, corners);
    }

    /**
     * Gets the mesh for content that may already have been built.
     *
     * <p>The deduplicator hashes the content and compares it fully before reusing, because a hash collision
     * would put one section's geometry in another's place -- which is not a subtle error, it is a wall of the
     * wrong block.
     */
    public byte[] meshFor(byte[] content, SectionMeshDeduplicator.Builder builder) {
        return this.deduplicator.mesh(content, builder);
    }

    public long sectionsBuilt() {
        return this.sectionsBuilt;
    }

    public long sectionsReused() {
        return this.sectionsReused;
    }

    /**
     * The share of the sections that came from the deduplicator.
     *
     * <p>Zero before any section has been built. A runtime that has built nothing has technically reused
     * everything, and reporting that as a rate would describe an unused one as though it had measured
     * something.
     */
    public double reusePercent() {
        long total = this.sectionsBuilt + this.sectionsReused;
        return total == 0 ? 0.0 : 100.0 * this.sectionsReused / total;
    }

    /** Clears everything, on a world change. */
    public void onWorldChange() {
        this.sectionsBuilt = 0;
        this.sectionsReused = 0;
        this.deduplicator.clear();
        // The rebuild timings describe the last world's geometry. Keeping them would report a rebuild
        // cost for chunks that no longer exist, and the worst would linger from a world the player left.
        this.buildTimes.clear();
    }

    /** The vertex pulling layout. */
    public VertexPullingLayout pulling() {
        return this.pulling;
    }
}
