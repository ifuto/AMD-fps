package net.amdfaster.runtime;

import net.amdfaster.mesh.AmbientOcclusionBaker;
import net.amdfaster.mesh.HiddenFaceCuller;
import net.amdfaster.mesh.LeafFaceCuller;
import net.amdfaster.mesh.SectionMeshDeduplicator;
import net.amdfaster.mesh.job.MeshJobPrioritizer;
import net.amdfaster.mesh.job.MeshJobQueue;
import net.amdfaster.mesh.job.MeshWorkerPool;

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

    private long sectionsBuilt;
    private long sectionsReused;

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
        this.queue.push(sectionX, sectionY, sectionZ, kind);
        return priority;
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
    }
}
