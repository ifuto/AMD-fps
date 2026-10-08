package net.amdfaster.entity;

import net.amdfaster.cull.Frustum;

import java.util.Arrays;

/**
 * Decides which entities a frame draws, and bounds how many that can be.
 *
 * <p>This is the answer to "however many entities there are, it should not lag". The reason entity
 * count hurts is that everything downstream -- batching, uploading, instanced drawing -- costs
 * something per entity. So the number that reaches those stages has to be bounded by what the
 * player can see rather than by what the world contains. A server with a hundred thousand dropped
 * items in a chunk the player cannot see should cost about as much as an empty one.
 *
 * <p>Three filters, cheapest first:
 * <ol>
 *   <li><b>Distance.</b> One squared comparison. Entities past {@link #getMaxDistance()} are never
 *       drawn, which is both a saving and correct -- nothing sees them.</li>
 *   <li><b>Frustum.</b> Six plane tests against the entity's box. Behind the camera is most of the
 *       world.</li>
 *   <li><b>Budget.</b> If more survive than {@link #getBudget()} allows, the nearest survive and the
 *       rest are dropped for this frame. This is the part that makes the worst case a constant.</li>
 * </ol>
 *
 * <p>The budget needs nearest-first order, and sorting to get it would cost O(n log n) on exactly
 * the frames that are already too slow. Instead the survivors are counted into
 * {@value #BANDS} distance bands in the same pass that culls them, a prefix sum turns the counts
 * into offsets, and a second pass buckets them. That is O(n), allocates nothing after the first
 * growth, and is near enough to sorted that the dropped entities are the furthest ones.
 *
 * <p>Not thread-safe. State is kept between calls so a steady-state frame allocates nothing; read
 * the result before culling again.
 */
public final class EntityCuller {

    /**
     * Entities further than this are not drawn, in blocks.
     *
     * <p>Lower than Minecraft's entity distance by design. Item entities -- the ones that pile up
     * by the thousand -- are a few pixels across past about forty blocks, and the model is worth
     * more than the pixels it covers at that range.
     */
    public static final float DEFAULT_MAX_DISTANCE = 48.0f;

    /**
     * Most instances one frame will draw. Beyond this the furthest are dropped.
     *
     * <p>Deliberately generous: an instanced draw of ten thousand simple models is not what makes a
     * frame slow, so the budget is there to stop a pathological case, not to trim an ordinary one.
     */
    public static final int DEFAULT_BUDGET = 16384;

    /** Distance bands the budget buckets into. Enough that the cut is within a block or two. */
    static final int BANDS = 64;

    private final float maxDistance;
    private final float maxDistanceSquared;
    private final int budget;

    private int[] survivors = new int[256];
    private int survivorCount;
    private int[] ordered = new int[256];
    private final int[] bandCounts = new int[BANDS];
    private final int[] bandOffsets = new int[BANDS];

    private int tested;
    private int culledByDistance;
    private int culledByFrustum;
    private int droppedByBudget;
    private float cutDistance;

    public EntityCuller() {
        this(DEFAULT_MAX_DISTANCE, DEFAULT_BUDGET);
    }

    public EntityCuller(float maxDistance, int budget) {
        if (!(maxDistance > 0f)) {
            throw new IllegalArgumentException("maxDistance must be positive: " + maxDistance);
        }
        if (budget <= 0) {
            throw new IllegalArgumentException("budget must be positive: " + budget);
        }
        this.maxDistance = maxDistance;
        this.maxDistanceSquared = maxDistance * maxDistance;
        this.budget = budget;
    }

    public float getMaxDistance() {
        return this.maxDistance;
    }

    public int getBudget() {
        return this.budget;
    }

    /**
     * Culls a frame's entities.
     *
     * <p>The result is read back through {@link #survivorCount()} and {@link #survivor(int)}, which
     * index into {@code entities}. Nothing is allocated here once the arrays have grown to the
     * largest size they will need.
     *
     * @param frustum may be {@code null}, which skips the frustum test. Useful for a debug overlay
     *                that draws entity boxes, and for tests that want the distance filter alone.
     */
    public void cull(EntityBuffer entities, Frustum frustum, float camX, float camY, float camZ) {
        this.survivorCount = 0;
        this.tested = entities.size();
        this.culledByDistance = 0;
        this.culledByFrustum = 0;
        this.droppedByBudget = 0;
        this.cutDistance = this.maxDistance;
        Arrays.fill(this.bandCounts, 0);

        for (int i = 0; i < entities.size(); i++) {
            float dx = entities.x(i) - camX;
            float dy = (entities.y(i) + entities.height(i) * 0.5f) - camY;
            float dz = entities.z(i) - camZ;
            float distanceSquared = dx * dx + dy * dy + dz * dz;

            // Distance first: one comparison against six plane tests.
            if (distanceSquared > this.maxDistanceSquared) {
                this.culledByDistance++;
                continue;
            }
            if (frustum != null && !frustum.intersectsAabb(entities.minX(i), entities.minY(i),
                    entities.minZ(i), entities.maxX(i), entities.maxY(i), entities.maxZ(i))) {
                this.culledByFrustum++;
                continue;
            }
            if (this.survivorCount == this.survivors.length) {
                this.survivors = Arrays.copyOf(this.survivors, this.survivors.length << 1);
            }
            this.survivors[this.survivorCount++] = i;
            this.bandCounts[bandOf(distanceSquared)]++;
        }

        if (this.survivorCount <= this.budget) {
            return;
        }
        applyBudget(camX, camY, camZ, entities);
    }

    /**
     * Which of {@value #BANDS} equal-squared-distance bands a survivor falls in.
     *
     * <p>Equal steps in squared distance, not in distance, because that is what is already computed
     * and taking a square root per entity to bucket it would cost more than the bucketing saves.
     * The bands are therefore narrower near the camera, which is the right way round: that is where
     * the choice of what to drop is most visible.
     */
    private int bandOf(float distanceSquared) {
        int band = (int) (distanceSquared / this.maxDistanceSquared * BANDS);
        return band < 0 ? 0 : Math.min(BANDS - 1, band);
    }

    private void applyBudget(float camX, float camY, float camZ, EntityBuffer entities) {
        // Prefix sum into a separate array. Overwriting bandCounts in place is the usual trick and
        // it would work, except the counts are needed again below to say where the budget cut, and
        // recomputing them means a third pass over the survivors.
        int running = 0;
        for (int b = 0; b < BANDS; b++) {
            this.bandOffsets[b] = running;
            running += this.bandCounts[b];
        }

        if (this.ordered.length < this.survivorCount) {
            this.ordered = new int[Math.max(this.survivorCount, this.ordered.length << 1)];
        }
        for (int i = 0; i < this.survivorCount; i++) {
            int index = this.survivors[i];
            float dx = entities.x(index) - camX;
            float dy = (entities.y(index) + entities.height(index) * 0.5f) - camY;
            float dz = entities.z(index) - camZ;
            int band = bandOf(dx * dx + dy * dy + dz * dz);
            this.ordered[this.bandOffsets[band]++] = index;
        }

        // The bands were filled nearest first, so the budget-th survivor sits in the first band
        // whose cumulative count reaches the budget. That band's outer edge is the cut distance.
        int cumulative = 0;
        int cutBand = BANDS - 1;
        for (int b = 0; b < BANDS; b++) {
            cumulative += this.bandCounts[b];
            if (cumulative >= this.budget) {
                cutBand = b;
                break;
            }
        }
        this.cutDistance = (float) Math.sqrt((cutBand + 1.0) / BANDS * this.maxDistanceSquared);

        System.arraycopy(this.ordered, 0, this.survivors, 0, this.budget);
        this.droppedByBudget = this.survivorCount - this.budget;
        this.survivorCount = this.budget;
    }

    /** Entities that survived every filter and are in {@code survivor(i)}. */
    public int survivorCount() {
        return this.survivorCount;
    }

    /** The index into the source {@link EntityBuffer} of the i-th survivor, in draw order. */
    public int survivor(int i) {
        return this.survivors[i];
    }

    public int tested() {
        return this.tested;
    }

    public int culledByDistance() {
        return this.culledByDistance;
    }

    public int culledByFrustum() {
        return this.culledByFrustum;
    }

    /** How many were dropped for exceeding the budget, and so are missing from this frame only. */
    public int droppedByBudget() {
        return this.droppedByBudget;
    }

    /**
     * The distance the budget cut at, or {@link #getMaxDistance()} when nothing was dropped.
     *
     * <p>Approximate by one band, which is the point: this is a number for a debug overlay, and
     * computing it exactly would mean finding the budget-th nearest survivor, which is the sort
     * this class exists to avoid.
     */
    public float cutDistance() {
        return this.cutDistance;
    }
}
