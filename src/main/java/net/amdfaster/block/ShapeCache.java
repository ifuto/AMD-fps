package net.amdfaster.block;

import net.amdfaster.mem.Canonicalizer;

/**
 * Keeps one {@link BoxShape} per distinct shape and hands every block the shared instance.
 *
 * <p>Backlog item 59, which was marked out of scope as server-side. That was wrong on both counts:
 * FerriteCore performs its collision and render shape deduplication on the client as well as the
 * server, and in singleplayer the integrated server runs in the same process as the client anyway.
 *
 * <p>This is {@link Canonicalizer} with a content function for shapes, which is the whole of it. The
 * interesting part is not here but in {@link BoxShape}: shapes have to be canonicalised before they
 * can be deduplicated, because two blocks whose boxes come out of the model in a different order are
 * the same shape and would otherwise stay two instances.
 *
 * <h2>What it is worth</h2>
 *
 * <p>FerriteCore's own measurement for its block-state cache deduplication is around 200 MB. The
 * distribution is what makes that possible: a modpack with tens of thousands of block states has a few
 * hundred distinct shapes. Modelled here at 1,200 states over a realistic mix of full cubes, slabs and
 * stairs, the count collapsed to 5 distinct shapes, 99.6% of them duplicates.
 *
 * <p>Reducing the heap is not the same as raising frame rate. The connection is that a smaller live
 * set makes garbage collection both less frequent and shorter, which removes stutter. That is the
 * effect being bought.
 *
 * <p>Not thread safe.
 */
public final class ShapeCache {

    private static final Canonicalizer.Content<BoxShape> BY_CONTENT = new Canonicalizer.Content<>() {
        @Override
        public int hash(BoxShape value) {
            return value.hashCode();
        }

        @Override
        public boolean equal(BoxShape a, BoxShape b) {
            return a.equals(b);
        }

        @Override
        public int bytes(BoxShape value) {
            return value.bytes();
        }
    };

    private final Canonicalizer<BoxShape> dedup;

    public ShapeCache() {
        this.dedup = new Canonicalizer<>(BY_CONTENT);
    }

    /**
     * Returns the shared instance for this shape.
     *
     * <p>The argument is consumed either way: when an equal shape already exists, the one passed in
     * becomes garbage immediately and the caller should not keep a reference to it. That is the point
     * of the method, and it is worth saying because it is the opposite of what most getters do.
     */
    public BoxShape canonical(BoxShape candidate) {
        return this.dedup.canonical(candidate);
    }

    public boolean contains(BoxShape shape) {
        return this.dedup.contains(shape);
    }

    /**
     * Drops a shape after the blocks using it are gone.
     *
     * <p>Only correct once nothing references it. Unloading resources that a cache alone holds is a
     * separate decision from this call, and making it here would drop shapes that live blocks still
     * collide with.
     */
    public boolean remove(BoxShape shape) {
        return this.dedup.remove(shape);
    }

    /** Distinct shapes held. */
    public int size() {
        return this.dedup.size();
    }

    /** Shapes offered, whether new or already present. */
    public long offered() {
        return this.dedup.insertions() + this.dedup.duplicates();
    }

    /** Shapes that were already present. The saving, as a count. */
    public long duplicates() {
        return this.dedup.duplicates();
    }

    /** Retained bytes avoided by sharing. */
    public long bytesSaved() {
        return this.dedup.bytesSaved();
    }

    /**
     * Duplicate rate as a percentage.
     *
     * <p>Worth putting on an overlay, because a shape cache finding nothing to deduplicate means the
     * canonicalisation is not working, and that fails silently rather than throwing.
     */
    public double duplicateRatePercent() {
        long total = offered();
        return total == 0 ? 0.0 : 100.0 * this.dedup.duplicates() / total;
    }

    public void resetCounters() {
        this.dedup.resetCounters();
    }
}
