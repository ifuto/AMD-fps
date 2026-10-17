package net.amdfaster.runtime;

import net.amdfaster.entity.AsyncVisibilityWorker;
import net.amdfaster.entity.CullingExclusionList;
import net.amdfaster.entity.EntityLodSelector;
import net.amdfaster.entity.NameplateCuller;
import net.amdfaster.entity.ShadowCuller;
import net.amdfaster.entity.TieredActivationScheduler;
import net.amdfaster.entity.VisibilityReevaluationScheduler;

import java.util.HashMap;
import java.util.Map;

/**
 * Runs the entity decisions in one place, in the order that makes each stage worth doing.
 *
 * <p>An entity costs four separate things every frame: a tick of AI, a line-of-sight test, a nameplate, a
 * shadow and a skinned mesh. Each has a component that decides whether it is needed, and each is correct on
 * its own -- but they are not independent, and asking them in the wrong order spends work on an entity a
 * cheaper stage was about to drop.
 *
 * <p>This is the order:
 *
 * <ol>
 *   <li><b>Exclusion.</b> A beacon beam or a portal is drawn whatever the occlusion test says, so checking it
 *       first means nothing downstream has to know about it.</li>
 *   <li><b>Visibility.</b> Whether the entity is behind solid terrain, asked on a staggered schedule and
 *       answered on a worker thread rather than the render one.</li>
 *   <li><b>Tick.</b> Whether the entity's AI runs this tick at all, which nothing else matters for if it does
 *       not.</li>
 *   <li><b>Nameplate and shadow.</b> Both use the visibility answer, so they come after it.</li>
 *   <li><b>Level of detail.</b> How many bones the mesh is skinned with, which is the most expensive thing
 *       left and so comes last.</li>
 * </ol>
 *
 * <h2>An entity with no visibility answer yet is drawn</h2>
 *
 * <p>The line-of-sight test runs on a worker thread, so for the first few ticks there is no answer. Guessing
 * "hidden" would make entities pop in as the answers arrive, which is exactly the artefact culling exists to
 * remove. Guessing "visible" means an entity behind a wall is drawn for a few ticks longer than necessary,
 * which costs a little and looks correct.
 *
 * <h2>The visibility answer is per entity, not global</h2>
 *
 * <p>Each entity has its own worker and its own answer, because they are in different places. A single
 * shared answer would cull everything or nothing, and the schedule that staggers the tests would have nothing
 * to stagger.
 *
 * <h2>The exclusion list is shared with the rest of the mod</h2>
 *
 * <p>A player who adds a beacon to it has added it everywhere, so this owns one instance and exposes it
 * rather than keeping a private copy that would silently disagree.
 *
 * <p>Not thread safe at this level. The worker inside is safe by construction, but the decisions here are made
 * on the client thread.
 */
public final class EntityRuntime {

    private final CullingExclusionList exclusions = new CullingExclusionList();
    private final TieredActivationScheduler activation = new TieredActivationScheduler();
    private final VisibilityReevaluationScheduler reevaluation = new VisibilityReevaluationScheduler();
    private final NameplateCuller nameplates = new NameplateCuller();
    private final ShadowCuller shadows = new ShadowCuller();
    private final EntityLodSelector lod = new EntityLodSelector();
    private final Map<Integer, AsyncVisibilityWorker> workers = new HashMap<>();

    private long tick;
    private long entitiesSeen;
    private long drawn;
    private long culled;

    /**
     * The worker holding one entity's visibility answer, creating it on first sight.
     *
     * <p>Per entity rather than shared, because entities are in different places and a single answer would
     * cull everything or nothing.
     */
    public AsyncVisibilityWorker workerFor(int entityId) {
        return this.workers.computeIfAbsent(entityId, id -> new AsyncVisibilityWorker());
    }

    /** Forgets an entity, so its worker and its answer go with it. */
    public void forget(int entityId) {
        this.workers.remove(entityId);
    }

    /** How many entities are being tracked. */
    public int tracked() {
        return this.workers.size();
    }

    /**
     * Decides what an entity costs this tick.
     *
     * @param entityId which entity
     * @param type its type, for the exclusion list
     * @param distance how far it is
     * @param player whether it is a player
     * @param physics whether it needs physics or a timer advanced, which is never skipped
     * @param moved how far it has moved since its last visibility test
     * @param heightAboveGround how far it is above the block below, for the shadow
     * @param occlusionKnown whether the worker has answered yet
     * @param pitchDegrees the camera pitch
     * @param yawDegrees the camera yaw
     * @param screenPixels how many pixels it covers, for the level of detail
     * @return what to do with it
     */
    public Plan plan(int entityId, String type, double distance, boolean player, boolean physics,
            double moved, double heightAboveGround, boolean occlusionKnown,
            double pitchDegrees, double yawDegrees, double screenPixels) {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("a type is required");
        }
        this.entitiesSeen++;

        AsyncVisibilityWorker worker = workerFor(entityId);

        // 1. Exclusion, first. An excluded type is drawn whatever the occlusion test says, and that has to
        // carry through to the level-of-detail stage below -- otherwise the exclusion would be honoured here
        // and then undone by the tier, which is the exact failure the ordering was meant to prevent.
        boolean hidden = occlusionKnown && !worker.isVisible();
        boolean exempt = this.exclusions.isExcluded(type);
        if (!this.exclusions.shouldDraw(type, hidden)) {
            this.culled++;
            return new Plan(false, false, false, false, EntityLodSelector.Tier.CULL);
        }

        // 2. Visibility, on a staggered schedule.
        if (this.reevaluation.shouldTest(entityId, moved)) {
            worker.issue(this.tick);
        }

        // 3. Tick.
        boolean ticks = this.activation.shouldTick(distance, player, physics, entityId, this.tick);

        // 4. Nameplate and shadow, both using the visibility answer.
        boolean nameplate = this.nameplates.shouldDraw(distance, hidden, occlusionKnown,
                pitchDegrees, yawDegrees);
        boolean shadow = this.shadows.shouldDraw(heightAboveGround, distance, hidden, occlusionKnown,
                pitchDegrees, yawDegrees);

        // 5. Level of detail, the most expensive thing left. An excluded type is never culled here, because
        // a beacon beam behind terrain is the whole reason it is on the list.
        EntityLodSelector.Tier tier = hidden && !exempt
                ? EntityLodSelector.Tier.CULL
                : this.lod.select(screenPixels, false);

        if (tier == EntityLodSelector.Tier.CULL) {
            this.culled++;
            return new Plan(ticks, false, nameplate, shadow, tier);
        }
        this.drawn++;
        return new Plan(ticks, true, nameplate, shadow, tier);
    }

    /** What to do with one entity this tick. */
    public record Plan(boolean ticks, boolean drawn, boolean nameplate, boolean shadow,
            EntityLodSelector.Tier tier) { }

    /**
     * Ends a tick.
     *
     * <p>Both schedulers have to be told, and they count different things: the activation scheduler's wake-up
     * budget refills, and the visibility schedule's phase advances. Missing the first would let every distant
     * entity wake at once; missing the second would test the same entities every tick and none of the others.
     */
    public void endTick() {
        this.tick++;
        this.activation.endTick();
        this.reevaluation.tick();
    }

    /** Feeds a worker its answer, from the thread that ran the line-of-sight test. */
    public void visibilityAnswer(int entityId, long issuedTick, boolean hidden) {
        AsyncVisibilityWorker worker = this.workers.get(entityId);
        if (worker != null) {
            worker.answer(issuedTick, hidden);
        }
    }

    /** The shared exclusion list, so a player adding to it adds everywhere. */
    public CullingExclusionList exclusions() {
        return this.exclusions;
    }

    public TieredActivationScheduler activation() {
        return this.activation;
    }

    public VisibilityReevaluationScheduler reevaluation() {
        return this.reevaluation;
    }

    public NameplateCuller nameplates() {
        return this.nameplates;
    }

    public ShadowCuller shadows() {
        return this.shadows;
    }

    public EntityLodSelector lod() {
        return this.lod;
    }

    public long tick() {
        return this.tick;
    }

    public long entitiesSeen() {
        return this.entitiesSeen;
    }

    public long drawn() {
        return this.drawn;
    }

    public long culled() {
        return this.culled;
    }

    /**
     * The share of the entities seen that were not drawn.
     *
     * <p>Zero before any entity has been seen. A runtime that has seen nothing has technically culled
     * nothing, and reporting that as a rate would describe an unused one as though it had measured something.
     */
    public double culledPercent() {
        return this.entitiesSeen == 0 ? 0.0 : 100.0 * this.culled / this.entitiesSeen;
    }

    /** Clears everything, on a world change. */
    public void reset() {
        this.tick = 0;
        this.entitiesSeen = 0;
        this.drawn = 0;
        this.culled = 0;
        this.workers.clear();
    }
}
