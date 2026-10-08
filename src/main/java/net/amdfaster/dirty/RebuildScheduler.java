package net.amdfaster.dirty;

import java.util.Arrays;

/**
 * Collects the sections that need their mesh rebuilt and hands them out nearest first, within a
 * per-frame budget.
 *
 * <p>Three things make this worth having rather than rebuilding on the spot.
 *
 * <p><b>Coalescing.</b> A block change does not cost one rebuild. Light propagates up to fifteen
 * blocks and every block it touches marks its own section dirty, which is how vanilla reaches
 * roughly fifty {@code rebuildChunk} calls for a single placed block. A TNT explosion changes
 * hundreds of blocks in one tick and multiplies that. Here every section is rebuilt at most once
 * per drain, however many times it was marked.
 *
 * <p><b>Splitting geometry from light.</b> Most of those fifty marks are light changes, not shape
 * changes. Because the light stream is its own vertex buffer, a light-only change rewrites that
 * stream and leaves positions, texture coordinates and indices untouched -- and skips greedy
 * meshing and ambient occlusion entirely, which is the expensive part of a rebuild. That split is
 * only possible because of the vertex layout; an interleaved one would have to rewrite everything
 * to change one word per vertex.
 *
 * <p><b>Budgeting.</b> Rebuilds are bounded per frame, so a storm produces a steady stream of work
 * rather than a stall. What does not fit stays pending.
 *
 * <h2>Ordering requirement</h2>
 * A drained section is removed from the pending set by {@link #drain} <em>before</em> the caller
 * builds its mesh. That order is load-bearing. If the removal happened after the build, a block
 * change arriving during the build would be discarded along with the section, and the mesh would
 * stay wrong until something else happened to dirty it again -- a hole in the world that never
 * heals. Removing first means a change during the build re-marks the section and it is rebuilt next
 * frame.
 *
 * <p>Not thread-safe; call from the render thread.
 */
public final class RebuildScheduler {

    /** Sections handed out per drain. Bounds the per-frame cost of a change storm. */
    public static final int DEFAULT_BUDGET = 64;

    /**
     * Distance, in blocks, at which the priority bands run out. Sections further away all share the
     * last band and are drained in table order among themselves.
     */
    public static final float DEFAULT_PRIORITY_RANGE = 256.0f;

    /**
     * Distance bands, equal steps in <em>linear</em> distance.
     *
     * <p>Banding by squared distance would be the cheaper test, but it is the wrong shape: with a
     * 256-block range the first band would cover eight blocks of distance while the last covers
     * thirty-two, and every section inside the innermost eight blocks would collapse into band zero
     * in hash order. The sections the player is looking at are exactly the ones that must not be
     * ordered arbitrarily, so the square root is worth its few nanoseconds. At the default range each
     * band is eight blocks, half a section.
     */
    static final int BANDS = 32;

    private final LongSet geometry;
    private final LongSet light;
    private final int budget;
    private final float priorityRange;

    private final int[] bandCounts = new int[BANDS];
    private final int[] bandOffsets = new int[BANDS];

    private long[] order = new long[256];
    private int[] drainedX = new int[DEFAULT_BUDGET];
    private int[] drainedY = new int[DEFAULT_BUDGET];
    private int[] drainedZ = new int[DEFAULT_BUDGET];
    private boolean[] drainedGeometry = new boolean[DEFAULT_BUDGET];
    private int drainedCount;

    public RebuildScheduler() {
        this(DEFAULT_BUDGET, DEFAULT_PRIORITY_RANGE);
    }

    public RebuildScheduler(int budget, float priorityRange) {
        this(budget, priorityRange, 256);
    }

    public RebuildScheduler(int budget, float priorityRange, int initialCapacity) {
        if (budget <= 0) {
            throw new IllegalArgumentException("budget must be positive: " + budget);
        }
        if (!(priorityRange > 0f)) {
            throw new IllegalArgumentException("priorityRange must be positive: " + priorityRange);
        }
        int capacity = Integer.highestOneBit(Math.max(16, initialCapacity - 1)) << 1;
        this.geometry = new LongSet(capacity);
        this.light = new LongSet(capacity);
        this.budget = budget;
        this.priorityRange = priorityRange;
    }

    public int budget() {
        return this.budget;
    }

    /**
     * Marks a section as needing its geometry rebuilt.
     *
     * <p>A geometry rebuild recomputes light as well, so any pending light-only mark for the same
     * section is dropped. Leaving both would rebuild the section twice in one drain and upload the
     * light stream a second time for no reason.
     */
    public void markGeometry(int sectionX, int sectionY, int sectionZ) {
        long key = SectionCoord.key(sectionX, sectionY, sectionZ);
        this.light.remove(key);
        this.geometry.add(key);
    }

    /**
     * Marks a section as needing only its light stream rewritten.
     *
     * <p>Ignored when a geometry rebuild is already pending, for the reason above.
     */
    public void markLight(int sectionX, int sectionY, int sectionZ) {
        long key = SectionCoord.key(sectionX, sectionY, sectionZ);
        if (this.geometry.contains(key)) {
            return;
        }
        this.light.add(key);
    }

    /** Convenience for the common case: a block changed, so mark everything it can invalidate. */
    public void markBlockChanged(int x, int y, int z, SectionSet scratch) {
        int count = scratch.fillForBlockChange(x, y, z);
        for (int i = 0; i < count; i++) {
            markGeometry(scratch.x(i), scratch.y(i), scratch.z(i));
        }
    }

    public int pending() {
        return this.geometry.size() + this.light.size();
    }

    public int pendingGeometry() {
        return this.geometry.size();
    }

    public int pendingLight() {
        return this.light.size();
    }

    /**
     * Takes up to {@link #budget()} sections out of the pending sets, geometry before light and
     * nearest before furthest within each.
     *
     * <p>The result is read through {@link #drainedCount()} and the {@code drained*} accessors, and
     * is valid until the next drain.
     *
     * @return how many sections were taken
     */
    public int drain(float cameraX, float cameraY, float cameraZ) {
        this.drainedCount = 0;
        drainSet(this.geometry, cameraX, cameraY, cameraZ, true);
        if (this.drainedCount < this.budget) {
            drainSet(this.light, cameraX, cameraY, cameraZ, false);
        }
        return this.drainedCount;
    }

    private void drainSet(LongSet set, float cameraX, float cameraY, float cameraZ,
            boolean isGeometry) {
        if (this.budget - this.drainedCount <= 0 || set.size() == 0) {
            return;
        }

        // The scratch array holds the collected entries in [0, found) and the counting-sorted result
        // in [found, 2 * found). Both regions are needed at once, which is why the array is sized for
        // twice the pending count.
        int found = 0;
        ensureOrderCapacity(set.size() * 2);
        Arrays.fill(this.bandCounts, 0);
        for (long key : set.table()) {
            if (key == SectionCoord.EMPTY) {
                continue;
            }
            this.order[found++] = key;
            this.bandCounts[bandOf(key, cameraX, cameraY, cameraZ)]++;
        }

        int running = 0;
        for (int b = 0; b < BANDS; b++) {
            this.bandOffsets[b] = running;
            running += this.bandCounts[b];
        }
        for (int i = 0; i < found; i++) {
            long key = this.order[i];
            this.order[found + this.bandOffsets[bandOf(key, cameraX, cameraY, cameraZ)]++] = key;
        }

        // Nearest band first. Each emitted section leaves the pending set immediately, which is what
        // makes the ordering requirement in the class documentation hold: a block change arriving
        // while the mesh is being built re-marks the section instead of being swallowed by it.
        for (int i = 0; i < found && this.drainedCount < this.budget; i++) {
            long key = this.order[found + i];
            emit(key, isGeometry);
            set.remove(key);
        }
    }

    private void emit(long key, boolean isGeometry) {
        if (this.drainedCount == this.drainedX.length) {
            int next = this.drainedX.length << 1;
            this.drainedX = Arrays.copyOf(this.drainedX, next);
            this.drainedY = Arrays.copyOf(this.drainedY, next);
            this.drainedZ = Arrays.copyOf(this.drainedZ, next);
            this.drainedGeometry = Arrays.copyOf(this.drainedGeometry, next);
        }
        this.drainedX[this.drainedCount] = SectionCoord.x(key);
        this.drainedY[this.drainedCount] = SectionCoord.y(key);
        this.drainedZ[this.drainedCount] = SectionCoord.z(key);
        this.drainedGeometry[this.drainedCount] = isGeometry;
        this.drainedCount++;
    }

    private void ensureOrderCapacity(int needed) {
        if (this.order.length < needed) {
            this.order = new long[Math.max(needed, this.order.length << 1)];
        }
    }

    private int bandOf(long key, float cameraX, float cameraY, float cameraZ) {
        // The section's centre, in blocks. Using the corner would bias every section by eight blocks
        // in one direction, which is enough to reorder sections near a band boundary.
        float dx = (SectionCoord.x(key) << 4) + 8.0f - cameraX;
        float dy = (SectionCoord.y(key) << 4) + 8.0f - cameraY;
        float dz = (SectionCoord.z(key) << 4) + 8.0f - cameraZ;
        float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        int band = (int) (distance / this.priorityRange * BANDS);
        return band < 0 ? 0 : Math.min(BANDS - 1, band);
    }

    public int drainedCount() {
        return this.drainedCount;
    }

    public int drainedX(int i) {
        return this.drainedX[i];
    }

    public int drainedY(int i) {
        return this.drainedY[i];
    }

    public int drainedZ(int i) {
        return this.drainedZ[i];
    }

    /**
     * Whether the i-th drained section needs a full geometry rebuild.
     *
     * <p>When this is false the caller may rewrite only the light stream, skipping meshing and
     * ambient occlusion and leaving the position, attribute and index buffers untouched.
     */
    public boolean drainedIsGeometry(int i) {
        return this.drainedGeometry[i];
    }

    public void clear() {
        this.geometry.clear();
        this.light.clear();
        this.drainedCount = 0;
    }
}
