package net.amdfaster.runtime;

import net.amdfaster.collision.EntityPairDeduplicator;
import net.amdfaster.cull.CameraCutDetector;
import net.amdfaster.cull.Frustum;
import net.amdfaster.cull.PyramidGeometry;
import net.amdfaster.collision.MovementRegion;
import net.amdfaster.entity.ActivationRangeScheduler;
import net.amdfaster.entity.AttributeCache;
import net.amdfaster.entity.BlockEntityCuller;
import net.amdfaster.entity.BonePalette;
import net.amdfaster.entity.DroppedItemExpiry;
import net.amdfaster.entity.EntityAnimation;
import net.amdfaster.entity.EntityCell;
import net.amdfaster.entity.EntityBuffer;
import net.amdfaster.entity.EntityCuller;
import net.amdfaster.entity.EntityCollisionGrid;
import net.amdfaster.entity.EntityOcclusionCuller;
import net.amdfaster.entity.ExperienceOrbMerger;
import net.amdfaster.entity.ItemMergeIndex;
import net.amdfaster.entity.TrackerVisibilityCache;

/**
 * Runs the entity-world decisions in one place, and clears them when the world changes.
 *
 * <p>These are the components that track where entities are and what they are doing: a spatial grid so a
 * lookup is a bucket rather than a scan, a collision grid so a mob does not test every other mob, an
 * attribute cache so a value is computed once, a visibility cache so a tracker does not re-test every tick,
 * and the mergers that collapse dropped items and experience orbs into fewer entities.
 *
 * <p>Each is correct on its own, but they all hold state keyed on entity identity and position, and an entity
 * that despawns has to be removed from every one of them -- missing one means a ghost entry that answers for
 * an entity that is no longer there.
 *
 * <h2>Removal is as important as addition</h2>
 *
 * <p>A grid that only ever grows is a leak, and a collision grid with stale entries makes mobs collide with
 * things that are not there. So every add has a matching remove, and the runtime clears everything on a world
 * change because entity identities are meaningless across worlds -- entity 42 in one world is a different
 * entity from entity 42 in the next.
 *
 * <h2>The mergers conserve what they merge</h2>
 *
 * <p>Merging ten experience orbs into one has to leave the same total experience, and merging ten dropped
 * items has to leave the same item count. A merger that loses value is not an optimisation, it is a bug that
 * takes the player's things -- and it is the kind of bug that is only noticed when it has already happened.
 *
 * <h2>The attribute cache has to be invalidated</h2>
 *
 * <p>An attribute is a computed value: a base plus additions and multiplications, in an order that matters. A
 * cached value from before a potion expired would leave the player stronger than they are, and the symptom is
 * a number on the screen that does not match what the player can do. So the cache is invalidated per entity
 * and attribute, and on a world change.
 *
 * <p>Not thread safe. Entity ticking happens on the server thread and rendering reads on the client thread, so
 * the caches that are read from both need the caller to synchronise.
 */
public final class EntityWorldRuntime {

    private final EntityCollisionGrid collision = new EntityCollisionGrid();
    private final ItemMergeIndex items = new ItemMergeIndex();
    private final DroppedItemExpiry expiry = new DroppedItemExpiry();
    private final ExperienceOrbMerger orbs = new ExperienceOrbMerger();
    private final TrackerVisibilityCache visibility = new TrackerVisibilityCache();
    private final ActivationRangeScheduler activation = new ActivationRangeScheduler();
    private final AttributeCache attributes = new AttributeCache();
    private final BlockEntityCuller blockEntities = new BlockEntityCuller();

    /**
     * The distance and frustum pass, which the occlusion pass takes its candidates from.
     *
     * <p>Owned here rather than passed in per frame so that its budget and band counters describe one
     * continuous stream of frames. Two instances would each keep their own budget and each drop a
     * different set of entities.
     */
    private final EntityCuller frustumPass = new EntityCuller();

    /**
     * The depth-pyramid pass, which removes the entities the frustum pass kept but the terrain hides.
     *
     * <p>Owned for the same reason, and because its survivor list is what the draw path reads.
     */
    private final EntityOcclusionCuller occlusion = new EntityOcclusionCuller();

    /**
     * The terrain's hierarchical depth, supplied rather than built here.
     *
     * <p>Nothing in this mod produces it yet -- it comes from the terrain pass on the GPU -- so it is
     * {@code null} until something supplies it, and the culling pass degrades to the frustum result
     * rather than testing against a buffer that does not exist.
     */
    private EntityOcclusionCuller.DepthPyramid depthPyramid;

    private long entitiesAdded;
    private long entitiesRemoved;

    /** Stops one entity pair being examined twice in a tick. */
    private final EntityPairDeduplicator pairs = new EntityPairDeduplicator();
    /** Confines collision queries to the sections an entity can reach. */
    private final MovementRegion movement = new MovementRegion();

    public EntityCollisionGrid collision() {
        return this.collision;
    }

    public ItemMergeIndex items() {
        return this.items;
    }

    public DroppedItemExpiry expiry() {
        return this.expiry;
    }

    public ExperienceOrbMerger orbs() {
        return this.orbs;
    }

    public TrackerVisibilityCache visibility() {
        return this.visibility;
    }

    public ActivationRangeScheduler activation() {
        return this.activation;
    }

    public AttributeCache attributes() {
        return this.attributes;
    }

    public BlockEntityCuller blockEntities() {
        return this.blockEntities;
    }

    /**
     * Registers an entity at a position.
     *
     * <p>Added to both grids, because a grid that only ever grows is a leak and a collision grid with stale
     * entries makes mobs collide with things that are not there.
     */
    public void add(int entityId, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        this.collision.add(bx, by, bz);
        this.items.add(entityId, x, y, z);
        this.entitiesAdded++;
    }

    /**
     * Removes an entity.
     *
     * <p>Removed from both grids and from the attribute cache, because entity identities are meaningless
     * across worlds and a ghost entry answers for an entity that is no longer there.
     */
    public void remove(int entityId, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        this.collision.remove(bx, by, bz);
        this.items.remove(entityId, x, y, z);
        this.entitiesRemoved++;
    }

    /** The bucket a position falls in, for the item merge index. */
    public static long bucketOf(double x, double y, double z) {
        return ItemMergeIndex.bucketOf(x, y, z);
    }

    /** The cell a coordinate falls in. */
    public static int cellOf(float v) {
        return EntityCell.cellOf(v);
    }

    /**
     * Whether a dropped item should expire.
     *
     * <p>An item nobody can see or reach is not worth ticking, and letting it live out its five minutes costs
     * ticks for something no player will ever pick up.
     */
    public boolean shouldExpireItem(int ageTicks, double nearestPlayer) {
        return this.expiry.shouldExpire(ageTicks, nearestPlayer);
    }

    /**
     * Merges experience orbs that are close together.
     *
     * <p>The total experience has to be conserved -- a merger that loses value is not an optimisation, it is a
     * bug that takes the player's things.
     */
    public java.util.List<ExperienceOrbMerger.Merged> mergeOrbs(
            java.util.List<ExperienceOrbMerger.Orb> orbsToMerge) {
        return this.orbs.merge(orbsToMerge);
    }

    /** Whether the merged orbs conserved the total experience. */
    public boolean conservedExperience() {
        return this.orbs.conservedExperience();
    }

    /**
     * Whether an entity should tick this tick.
     *
     * <p>A distant entity that is not moving does not need to think every tick, but one that needs physics or
     * a timer advanced always ticks -- a falling entity that stops being ticked stops falling and hangs in the
     * air, and an entity on a despawn timer that stops counting never despawns.
     */
    public boolean shouldTickEntity(double distance, boolean physicsOrTimer, long tick, int entityId) {
        return this.activation.shouldTick(distance, physicsOrTimer, tick, entityId);
    }

    /**
     * Gets a cached attribute value.
     *
     * <p>An attribute is a base plus additions and multiplications in an order that matters, so a cached value
     * from before a potion expired would leave the player stronger than they are -- and the symptom is a number
     * on the screen that does not match what the player can do.
     *
     * @param multiplyTotals the pre-summed multiplicative totals, in the order the game applies them
     * @param fallback computes the value when the cache has no entry
     */
    public double attribute(int entityId, int attributeId, double base, double[] additions,
            double[] multiplications, double[] multiplyTotals,
            java.util.function.DoubleSupplier fallback) {
        return this.attributes.value(entityId, attributeId, base, additions, multiplications,
                multiplyTotals, fallback);
    }

    /** Invalidates one attribute, because something changed it. */
    public void invalidateAttribute(int entityId, int attributeId) {
        this.attributes.invalidate(entityId, attributeId);
    }

    /** Invalidates an entity's visibility, because it moved. */
    public void invalidateVisibility(int entityId) {
        this.visibility.invalidateEntity(entityId);
    }

    /** Sets how far away block entities stop being drawn. */
    public void setBlockEntityDistance(double blocks) {
        this.blockEntities.setMaxDistance(blocks);
    }

    public long entitiesAdded() {
        return this.entitiesAdded;
    }

    public long entitiesRemoved() {
        return this.entitiesRemoved;
    }

    /** Clears everything, on a world change. */
    public void onWorldChange() {
        this.entitiesAdded = 0;
        this.entitiesRemoved = 0;
        this.items.clear();
        // The collision grid holds positions from the old world, and entity identities are meaningless
        // across worlds -- a grid left uncleared would make mobs in the new world collide with entities
        // from the last one.
        this.collision.clear();
        this.collision.resetCounters();
        // The pyramid holds the depth of the previous world's terrain. A stale one is worse than none:
        // it culls entities that are in plain view in the new world, and the symptom is mobs that are
        // simply not there rather than a visible artefact. Dropping it makes the next frame fall back to
        // the frustum pass until the terrain pass supplies a fresh one.
        this.depthPyramid = null;
    }

    /** The entity pair deduplicator. */
    public EntityPairDeduplicator pairs() {
        return this.pairs;
    }

    /** The movement region. */
    public MovementRegion movement() {
        return this.movement;
    }

    /** The distance and frustum pass. */
    public EntityCuller frustumPass() {
        return this.frustumPass;
    }

    /** The depth-pyramid pass. */
    public EntityOcclusionCuller occlusion() {
        return this.occlusion;
    }

    /**
     * Supplies the hierarchical depth the occlusion pass tests against.
     *
     * <p>Supplied rather than built, because the pyramid is a GPU resource from the terrain pass and this
     * runtime is not the thing that renders terrain. A {@code null} pyramid turns the occlusion pass off,
     * which is the correct behaviour for a frame that has no depth to test against -- the alternative is
     * culling against whatever the buffer happened to hold.
     *
     * <p>The pyramid has to be built from terrain only. If entities write depth into it, an entity that
     * was visible last frame leaves its own depth behind and this frame's test compares its new position
     * against where it used to be -- mobs that walk away from the camera vanish and stay vanished.
     *
     * @param pyramid the terrain's hierarchical depth, or {@code null} to run the frustum pass alone
     * @return this runtime
     */
    public EntityWorldRuntime withDepthPyramid(EntityOcclusionCuller.DepthPyramid pyramid) {
        this.depthPyramid = pyramid;
        return this;
    }

    /** Whether a depth pyramid has been supplied, so the occlusion pass will actually run. */
    public boolean hasDepthPyramid() {
        return this.depthPyramid != null;
    }

    /**
     * Runs the entity visibility passes in order and returns how many entities survive.
     *
     * <p>Distance and frustum first, because those are cheap and they cut the candidate list the
     * expensive pass has to walk; then the depth pyramid, one fetch per surviving entity. The order is
     * not interchangeable: testing the pyramid before the frustum spends a depth fetch on every entity in
     * the world including the ones behind the camera.
     *
     * <p>With no pyramid supplied this is the frustum pass alone, and the count returned is that pass's --
     * so the caller reads one number either way and the draw path does not have to know which ran.
     *
     * @param entities       the frame's entities
     * @param frustum        the view frustum, or {@code null} for the distance filter alone
     * @param camX           camera position
     * @param camY           camera position
     * @param camZ           camera position
     * @param viewProjection the 16-float view-projection matrix the pyramid fetch is projected with
     * @param pyramid        the pyramid's level geometry, matching the supplied depth
     * @param cuts           camera history, which holds the pass open while it wants a depth prepass
     * @return how many entities survived, indexing into {@code entities} through the pass that ran
     */
    public int cullEntities(EntityBuffer entities, Frustum frustum, float camX, float camY, float camZ,
            float[] viewProjection, PyramidGeometry pyramid, CameraCutDetector cuts) {
        if (this.depthPyramid == null) {
            this.frustumPass.cull(entities, frustum, camX, camY, camZ);
            return this.frustumPass.survivorCount();
        }
        EntityOcclusionCuller.cullAll(entities, this.frustumPass, frustum, camX, camY, camZ,
                viewProjection, pyramid, this.depthPyramid, cuts, this.occlusion);
        return this.occlusion.survivorCount();
    }

    /**
     * The i-th survivor of the pass that just ran, as an index into the buffer it was given.
     *
     * <p>Which pass that is depends on whether a pyramid was supplied, and the draw path should not have
     * to know -- it asks for the survivors of "the cull" either way.
     */
    public int survivorAt(int i) {
        return this.depthPyramid == null ? this.frustumPass.survivor(i) : this.occlusion.survivor(i);
    }

    /** The distance limit currently in force, which the draw path sets from the game's own. */
    public float cullDistance() {
        return this.frustumPass.getMaxDistance();
    }

    /** How many entities the pass that just ran tested, for the overlay. */
    public int cullTested() {
        return this.depthPyramid == null ? this.frustumPass.tested() : this.occlusion.tested();
    }

    /** How many the pass that just ran removed. */
    public int cullCulled() {
        if (this.depthPyramid == null) {
            return this.frustumPass.culledByDistance() + this.frustumPass.culledByFrustum()
                    + this.frustumPass.droppedByBudget();
        }
        return this.occlusion.tested() - this.occlusion.survivorCount();
    }

    /**
     * Moves the distance limit, which the draw path sets from the game's own render distance every frame.
     *
     * <p>See {@link net.amdfaster.entity.EntityCuller#setMaxDistance(float)}: a fixed limit would remove
     * entities the player can see, and this pass is supposed to be invisible.
     */
    public void setMaxCullDistance(float blocks) {
        this.frustumPass.setMaxDistance(blocks);
    }
}
