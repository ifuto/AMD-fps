package net.amdfaster.runtime;

import net.amdfaster.entity.ActivationRangeScheduler;
import net.amdfaster.entity.AttributeCache;
import net.amdfaster.entity.BlockEntityCuller;
import net.amdfaster.entity.BonePalette;
import net.amdfaster.entity.DroppedItemExpiry;
import net.amdfaster.entity.EntityAnimation;
import net.amdfaster.entity.EntityCell;
import net.amdfaster.entity.EntityCollisionGrid;
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

    private long entitiesAdded;
    private long entitiesRemoved;

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
    }
}
