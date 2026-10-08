package net.amdfaster.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups entities so that many of the same model draw as one call.
 *
 * <p>The win is concentrated where entities pile up. A hopper farm, a mob grinder's drop chute, a
 * player's spilled shulker: hundreds of instances of one model inside a few blocks. Each is
 * otherwise its own draw, and on AMD a draw is command-buffer space plus a wave of setup, so the
 * cost is per entity rather than per triangle -- which is backwards for geometry this simple.
 *
 * <p>Instances only share a batch when they share a {@code modelKey}, because one instanced draw
 * binds one model and one texture. A batch is additionally capped on instance count and on how far
 * its merged bounds may stretch, because the merged box is what the frustum test uses: an unbounded
 * box spanning a whole farm is never culled, which throws away the other half of the saving.
 *
 * <p>Assignment is greedy and searches only the most recent {@code searchWindow} batches of the same
 * model. That is an approximation, chosen deliberately -- an optimal grouping is a clustering
 * problem, and running one per frame on the client's render thread to save a handful of draws is a
 * bad trade. The window bounds the cost at a constant per entity regardless of how many are present.
 *
 * <p>Not thread-safe, and not meant to be: this runs on the render thread while building a frame.
 */
public final class EntityBatcher {

    /** Instances per draw. Comfortably inside any driver's limit and small enough to stay cache hot. */
    public static final int DEFAULT_MAX_INSTANCES = 1024;

    /** How far a batch's merged bounds may stretch, in blocks, on any axis. */
    public static final float DEFAULT_MAX_SPAN = 32.0f;

    /** How many recent same-model batches an instance considers joining. */
    public static final int DEFAULT_SEARCH_WINDOW = 8;

    private final int maxInstances;
    private final float maxSpan;
    private final int searchWindow;
    private final Map<Integer, List<Mutable>> byModel = new LinkedHashMap<>();
    private int instanceCount;

    public EntityBatcher() {
        this(DEFAULT_MAX_INSTANCES, DEFAULT_MAX_SPAN, DEFAULT_SEARCH_WINDOW);
    }

    public EntityBatcher(int maxInstances, float maxSpan, int searchWindow) {
        if (maxInstances <= 0) {
            throw new IllegalArgumentException("maxInstances must be positive: " + maxInstances);
        }
        if (maxSpan <= 0f) {
            throw new IllegalArgumentException("maxSpan must be positive: " + maxSpan);
        }
        if (searchWindow <= 0) {
            throw new IllegalArgumentException("searchWindow must be positive: " + searchWindow);
        }
        this.maxInstances = maxInstances;
        this.maxSpan = maxSpan;
        this.searchWindow = searchWindow;
    }

    /** A batch under construction. */
    private static final class Mutable {
        private final List<EntityInstance> instances = new ArrayList<>();
        private float minX = Float.POSITIVE_INFINITY;
        private float minY = Float.POSITIVE_INFINITY;
        private float minZ = Float.POSITIVE_INFINITY;
        private float maxX = Float.NEGATIVE_INFINITY;
        private float maxY = Float.NEGATIVE_INFINITY;
        private float maxZ = Float.NEGATIVE_INFINITY;

        private boolean fits(EntityInstance e, int maxInstances, float maxSpan) {
            if (this.instances.size() >= maxInstances) {
                return false;
            }
            return within(max(this.minX, e.minX()), min(this.maxX, e.maxX()), maxSpan)
                    && within(max(this.minY, e.minY()), min(this.maxY, e.maxY()), maxSpan)
                    && within(max(this.minZ, e.minZ()), min(this.maxZ, e.maxZ()), maxSpan);
        }

        private static boolean within(float lo, float hi, float maxSpan) {
            return hi - lo <= maxSpan;
        }

        private void add(EntityInstance e) {
            this.instances.add(e);
            this.minX = min(this.minX, e.minX());
            this.minY = min(this.minY, e.minY());
            this.minZ = min(this.minZ, e.minZ());
            this.maxX = max(this.maxX, e.maxX());
            this.maxY = max(this.maxY, e.maxY());
            this.maxZ = max(this.maxZ, e.maxZ());
        }

        private static float min(float a, float b) {
            return a < b ? a : b;
        }

        private static float max(float a, float b) {
            return a > b ? a : b;
        }
    }

    public void add(EntityInstance instance) {
        List<Mutable> batches = this.byModel.computeIfAbsent(instance.modelKey(),
                key -> new ArrayList<>());
        int from = Math.max(0, batches.size() - this.searchWindow);
        for (int i = batches.size() - 1; i >= from; i--) {
            Mutable candidate = batches.get(i);
            if (candidate.fits(instance, this.maxInstances, this.maxSpan)) {
                candidate.add(instance);
                this.instanceCount++;
                return;
            }
        }
        Mutable fresh = new Mutable();
        fresh.add(instance);
        batches.add(fresh);
        this.instanceCount++;
    }

    public void addAll(Iterable<EntityInstance> instances) {
        for (EntityInstance instance : instances) {
            add(instance);
        }
    }

    /** Instances added so far. */
    public int instanceCount() {
        return this.instanceCount;
    }

    public void clear() {
        this.byModel.clear();
        this.instanceCount = 0;
    }

    /**
     * Flattens the groups into contiguous instance runs plus the batch records that address them.
     *
     * <p>Order is deterministic: models in the order first seen, and within a model the batches in
     * the order they were opened. A frame that renders identically must produce an identical buffer,
     * or the upload cannot be skipped when nothing changed.
     */
    public EntityBatchSet build() {
        List<EntityInstance> instances = new ArrayList<>(this.instanceCount);
        List<EntityBatch> batches = new ArrayList<>(this.byModel.size());
        for (Map.Entry<Integer, List<Mutable>> entry : this.byModel.entrySet()) {
            for (Mutable mutable : entry.getValue()) {
                int first = instances.size();
                instances.addAll(mutable.instances);
                batches.add(new EntityBatch(entry.getKey(), first, mutable.instances.size(),
                        mutable.minX, mutable.minY, mutable.minZ,
                        mutable.maxX, mutable.maxY, mutable.maxZ));
            }
        }
        return new EntityBatchSet(List.copyOf(instances), List.copyOf(batches));
    }
}
