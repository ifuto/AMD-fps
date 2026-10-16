package net.amdfaster.render;

/**
 * Reuses the icon geometry for an inventory slot whose item has not changed.
 *
 * <p>Opening the inventory draws an icon for every slot, and each one is built from scratch each frame: the
 * item's model is fetched, its quads generated, its texture bound and the draw call issued. Forty-six slots
 * in a survival inventory, several hundred in creative, and all of it rebuilt sixty times a second while the
 * screen sits there open and nothing in it moves.
 *
 * <p>Keeping the built geometry and reusing it while the slot holds the same item removes almost all of
 * that. An inventory that is being looked at rather than sorted is the common case, and in it every slot is
 * unchanged.
 *
 * <h2>The count is not part of the icon</h2>
 *
 * <p>A stack of sixty-four cobblestone and a stack of one use the same icon; the number is drawn as text
 * over it in a separate pass. So picking up or dropping a single item from a stack changes the count and
 * nothing about the geometry, and rebuilding the icon for it would discard work for a change the icon does
 * not show.
 *
 * <h2>Damage and enchantment do change the icon</h2>
 *
 * <p>A damaged tool draws differently, and an enchanted one has the glint over it. Those are part of the
 * icon rather than drawn on top of it, so a change to either has to rebuild. Treating them like the count
 * would leave a sword looking brand new after it had been used, or an enchanted bow with no glint, and both
 * are things a player checks by looking.
 *
 * <h2>Swapping the item rebuilds</h2>
 *
 * <p>Moving a different item into a slot is the obvious case, and the one that has to be right or the
 * inventory shows the wrong thing. The identity of the item is part of the cache key, so a swap is a
 * different key and gets its own geometry.
 *
 * <h2>Why the cache is bounded</h2>
 *
 * <p>A creative inventory pages through hundreds of items, and a long session would accumulate an icon for
 * every item ever displayed. The bound is a slot count well above any real screen, and the oldest entry is
 * dropped when it is exceeded.
 *
 * <p>Not thread safe. The inventory is drawn on the render thread.
 */
public final class InventoryIconCache {

    /** Slots on a survival inventory screen. */
    public static final int SURVIVAL_SLOTS = 46;

    /** Icons held before the oldest is dropped. */
    public static final int CAPACITY = 1024;

    /** Milliseconds building one icon costs. */
    public static final double MS_PER_ICON = 0.02;

    private final String[] keys = new String[CAPACITY];
    private final boolean[] used = new boolean[CAPACITY];
    private final int[] order = new int[CAPACITY];

    private int orderCount;
    private long requests;
    private long hits;
    private long built;
    private long evicted;

    /**
     * The cache key for a slot.
     *
     * <p>The item and the things that change how it is drawn, but not the count, which is drawn as text
     * over the icon in a separate pass.
     *
     * @param item the item id
     * @param damage the damage value, which changes a tool's appearance
     * @param enchanted whether the glint is drawn over it
     */
    public static String key(String item, int damage, boolean enchanted) {
        return item + "|" + damage + "|" + enchanted;
    }

    /**
     * Whether an icon has to be built.
     *
     * @return true if the geometry must be rebuilt, false if the cached one is still correct
     */
    public boolean needsRebuild(String item, int damage, boolean enchanted) {
        if (item == null || item.isEmpty()) {
            throw new IllegalArgumentException("an item is required");
        }
        if (damage < 0) {
            throw new IllegalArgumentException("a negative damage value: " + damage);
        }
        String key = key(item, damage, enchanted);
        this.requests++;

        for (int i = 0; i < CAPACITY; i++) {
            if (this.used[i] && this.keys[i].equals(key)) {
                this.hits++;
                return false;
            }
        }
        this.built++;
        return true;
    }

    /**
     * Stores a built icon.
     *
     * <p>Called after the geometry has been built, so the next request for the same slot finds it.
     */
    public void store(String item, int damage, boolean enchanted) {
        String key = key(item, damage, enchanted);

        for (int i = 0; i < CAPACITY; i++) {
            if (this.used[i] && this.keys[i].equals(key)) {
                return;
            }
        }
        if (this.orderCount == CAPACITY) {
            // A creative inventory pages through hundreds of items and a long session would accumulate an
            // icon for every one ever displayed.
            int oldest = this.order[0];
            this.used[oldest] = false;
            this.evicted++;
            compactOrder();
        }
        for (int i = 0; i < CAPACITY; i++) {
            if (!this.used[i]) {
                this.used[i] = true;
                this.keys[i] = key;
                this.order[this.orderCount++] = i;
                return;
            }
        }
    }

    /** Drops every cached icon, when the screen closes. */
    public void clear() {
        for (int i = 0; i < CAPACITY; i++) {
            this.used[i] = false;
            this.keys[i] = null;
        }
        this.orderCount = 0;
    }

    private void compactOrder() {
        int write = 0;
        for (int read = 0; read < this.orderCount; read++) {
            if (this.used[this.order[read]]) {
                this.order[write++] = this.order[read];
            }
        }
        this.orderCount = write;
    }

    public long requests() {
        return this.requests;
    }

    /** How many requests found the icon already built. */
    public long hits() {
        return this.hits;
    }

    /** How many icons were built. */
    public long built() {
        return this.built;
    }

    /** How many were dropped to make room. */
    public long evicted() {
        return this.evicted;
    }

    public double hitPercent() {
        return this.requests == 0 ? 0.0 : 100.0 * this.hits / this.requests;
    }

    /** Milliseconds of icon building avoided. */
    public double msSaved() {
        return this.hits * MS_PER_ICON;
    }

    /** Milliseconds building every icon every frame would cost. */
    public double msWithoutCache() {
        return this.requests * MS_PER_ICON;
    }

    /**
     * The share of the icon building avoided.
     *
     * <p>Zero before any icon has been asked for. A cache that has been asked nothing has technically
     * avoided everything, but reporting that as a saving would describe an unused one as though it had
     * done something.
     */
    public double savedPercent() {
        double without = msWithoutCache();
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * msSaved() / without;
    }
}
