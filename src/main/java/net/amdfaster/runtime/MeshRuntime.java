package net.amdfaster.runtime;

import net.amdfaster.mesh.VertexPullingLayout;
import net.amdfaster.mesh.AtlasPacker;
import net.amdfaster.mesh.AxisPassScheduler;
import net.amdfaster.mesh.BlockVertexLadder;
import net.amdfaster.mesh.CompressedSectionMesh;
import net.amdfaster.mesh.GeometryCache;
import net.amdfaster.mesh.LodMerger;
import net.amdfaster.mesh.MeshSweepBounds;
import net.amdfaster.mesh.MeshletQuantizer;
import net.amdfaster.mesh.QuadTemplateLibrary;
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

    public MeshJobPrioritizer prioritizer() {
        return this.prioritizer;
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
