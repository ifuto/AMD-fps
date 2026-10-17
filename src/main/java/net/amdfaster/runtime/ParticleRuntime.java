package net.amdfaster.runtime;

import net.amdfaster.particle.CrossModParticleBudget;
import net.amdfaster.particle.OffscreenParticleCuller;
import net.amdfaster.particle.ParticleCuller;
import net.amdfaster.particle.ParticleDrawBatcher;
import net.amdfaster.particle.ParticleLod;
import net.amdfaster.particle.ParticlePool;

/**
 * Runs the particle decisions in one place, in the order that makes each stage worth doing.
 *
 * <p>Particles are the one thing in the game that can appear in the thousands in a single tick, and every one
 * of them would otherwise cost a slot, a budget share, a frustum test, an occlusion test and a draw call. The
 * stages that remove them are all cheap, but they are not equally cheap, and doing them in the wrong order
 * spends the expensive ones on particles the cheap ones would have dropped.
 *
 * <p>This is the order:
 *
 * <ol>
 *   <li><b>Budget.</b> Whether the frame has room for another particle at all, counted across every mod. This
 *       is first because it is the only stage whose answer does not depend on the particle.</li>
 *   <li><b>Slot.</b> A place in the pool to hold it. Refused here means nothing else was spent on it.</li>
 *   <li><b>Size.</b> A particle too small to see at this distance is dropped, which is a comparison rather
 *       than a test.</li>
 *   <li><b>Off-screen.</b> Behind the camera, judged by the whole box so a large one straddling the edge is
 *       kept.</li>
 *   <li><b>Occlusion.</b> Behind solid terrain, which is the most expensive test and so comes last.</li>
 *   <li><b>Batch.</b> What survives is added to the draw batch, grouped by texture.</li>
 * </ol>
 *
 * <h2>A refused particle gives its slot and its budget share back</h2>
 *
 * <p>A particle that is culled after taking a slot would hold it until it expired, so a frame full of hidden
 * particles would exhaust the pool and refuse the visible ones. The refusal path releases both, which is what
 * makes the order safe rather than merely fast.
 *
 * <h2>The batch is flushed per frame, not per tick</h2>
 *
 * <p>Particles are drawn every frame even when they do not move, so the batch has to be flushed every frame or
 * the last particles of a frame would be drawn with the first particles of the next. The tick-driven
 * components are the budget and the occlusion counters, which are cleared on the tick.
 *
 * <h2>Why the LOD comes before the culling</h2>
 *
 * <p>The size test is a comparison of two numbers and the occlusion test walks terrain. A particle that is two
 * pixels across at this distance is not worth an occlusion test, and in a scene with thousands of distant
 * particles that is most of them.
 *
 * <p>Not thread safe. Particles are spawned and drawn on the client thread.
 */
public final class ParticleRuntime {

    /** Why a particle was not drawn. */
    public enum Verdict {
        /** Drawn. */
        DRAWN,
        /** Refused: the frame's particle budget is full. */
        BUDGET_FULL,
        /** Refused: the pool has no free slot. */
        NO_SLOT,
        /** Refused: too small to see at this distance. */
        TOO_SMALL,
        /** Refused: entirely behind the camera. */
        OFF_SCREEN,
        /** Refused: behind solid terrain. */
        OCCLUDED
    }

    private final CrossModParticleBudget budget = new CrossModParticleBudget();
    private final ParticlePool pool;
    private final ParticleLod lod = new ParticleLod();
    private final OffscreenParticleCuller offscreen = new OffscreenParticleCuller();
    private final ParticleCuller occlusion = new ParticleCuller();
    private final ParticleDrawBatcher batcher = new ParticleDrawBatcher();

    private long spawned;
    private long drawn;
    private long budgetFull;
    private long noSlot;
    private long tooSmall;
    private long offScreen;
    private long occluded;
    private long frames;

    /** How many particles the pool holds by default. */
    public static final int DEFAULT_POOL_CAPACITY = 4096;

    public ParticleRuntime() {
        this(DEFAULT_POOL_CAPACITY);
    }

    public ParticleRuntime(int poolCapacity) {
        this.pool = new ParticlePool(poolCapacity);
    }

    /**
     * Spawns a particle and decides whether it will be drawn.
     *
     * @param source what is spawning it, such as a mod's namespace
     * @param texture the texture it draws with
     * @param x the centre x
     * @param y the centre y
     * @param z the centre z
     * @param size the particle's radius in blocks
     * @param distanceSquared how far it is, squared
     * @param pixelsPerBlock how many screen pixels one block covers at that distance
     * @param angleFromCentre how far it is from the centre of the view, in degrees
     * @param halfFieldOfView half the field of view, in degrees
     * @return the verdict, and the slot if it was drawn
     */
    public Spawn spawn(String source, String texture, double x, double y, double z, float size,
            float distanceSquared, float pixelsPerBlock, double angleFromCentre,
            double halfFieldOfView) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("a source is required");
        }
        if (texture == null || texture.isEmpty()) {
            throw new IllegalArgumentException("a texture is required");
        }
        this.spawned++;

        // 1. Budget, across every mod. First because its answer does not depend on the particle.
        if (!this.budget.allow(source)) {
            this.budgetFull++;
            return new Spawn(Verdict.BUDGET_FULL, -1);
        }

        // 2. Slot.
        int slot = this.pool.acquire();
        if (slot < 0) {
            this.budget.release(source);
            this.noSlot++;
            return new Spawn(Verdict.NO_SLOT, -1);
        }

        // 3. Size. A comparison rather than a test, so it comes before both culling stages.
        ParticleLod.Tier tier = this.lod.tierFor(distanceSquared, size, pixelsPerBlock);
        if (tier == ParticleLod.Tier.DROPPED) {
            this.pool.release(slot);
            this.budget.release(source);
            this.tooSmall++;
            return new Spawn(Verdict.TOO_SMALL, -1);
        }

        // 4. Off-screen, judged by the whole box.
        if (!this.offscreen.shouldProcess(angleFromCentre, halfFieldOfView)) {
            this.pool.release(slot);
            this.budget.release(source);
            this.offScreen++;
            return new Spawn(Verdict.OFF_SCREEN, -1);
        }

        // 5. Occlusion, the most expensive test, so it comes last.
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        float depth = (float) Math.sqrt(distanceSquared);
        if (!this.occlusion.keep(bx, by, bz, bx, by, bz, depth, 1.0f)) {
            this.pool.release(slot);
            this.budget.release(source);
            this.occluded++;
            return new Spawn(Verdict.OCCLUDED, -1);
        }

        // 6. Batch.
        this.batcher.add(texture);
        this.drawn++;
        return new Spawn(Verdict.DRAWN, slot);
    }

    /** The outcome of one spawn. */
    public record Spawn(Verdict verdict, int slot) {
        /** Whether the particle will be drawn. */
        public boolean drawn() {
            return this.verdict == Verdict.DRAWN;
        }
    }

    /**
     * Ends a frame, flushing the draw batch.
     *
     * <p>Per frame rather than per tick, because particles are drawn every frame even when they do not move.
     * Flushing on the tick would draw the last particles of a frame with the first particles of the next.
     */
    public void endFrame() {
        this.frames++;
        this.batcher.endFrame();
        this.lod.resetCounters();
    }

    /** Ends a tick, clearing the occlusion counters. */
    public void endTick() {
        this.occlusion.reset();
    }

    /** Marks terrain opaque, so the occlusion test has something to test against. */
    public void markOpaque(int x0, int y0, int x1, int y1, float depth) {
        this.occlusion.markOpaque(x0, y0, x1, y1, depth);
    }

    /** Releases a particle's slot and its share of the budget when it expires. */
    public void expire(int slot, String source) {
        this.pool.release(slot);
        this.budget.release(source);
    }

    /** Sets the distances at which particles simplify and drop. */
    public void setLodDistances(float simplify, float drop) {
        this.lod.setDistances(simplify, drop);
    }

    public long spawned() {
        return this.spawned;
    }

    public long drawn() {
        return this.drawn;
    }

    public long budgetFull() {
        return this.budgetFull;
    }

    public long noSlot() {
        return this.noSlot;
    }

    public long tooSmall() {
        return this.tooSmall;
    }

    public long offScreen() {
        return this.offScreen;
    }

    public long occluded() {
        return this.occluded;
    }

    public long frames() {
        return this.frames;
    }

    /** How many were refused in total. */
    public long refused() {
        return this.budgetFull + this.noSlot + this.tooSmall + this.offScreen + this.occluded;
    }

    public CrossModParticleBudget budget() {
        return this.budget;
    }

    public ParticlePool pool() {
        return this.pool;
    }

    public ParticleDrawBatcher batcher() {
        return this.batcher;
    }

    /** Milliseconds of draw calls the batching removed. */
    public double msSavedOnDrawCalls() {
        return this.batcher.msWithoutBatching() - this.batcher.msCalls();
    }

    /**
     * The share of the particles spawned that were refused.
     *
     * <p>Zero before anything has been spawned. A runtime that has spawned nothing has technically refused
     * nothing, and reporting that as a rate would describe an unused one as though it had measured something.
     */
    public double refusedPercent() {
        return this.spawned == 0 ? 0.0 : 100.0 * refused() / this.spawned;
    }
}
