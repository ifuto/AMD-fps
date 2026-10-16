package net.amdfaster.particle;

/**
 * Hands out particle slots from a fixed pool instead of allocating one object per particle.
 *
 * <p>Vanilla allocates a particle object for every particle it spawns. A TNT chain or a large splash
 * produces thousands a second, and at roughly 64 bytes each that is tens of megabytes of garbage a
 * minute for the collector to trace, in a game that is already CPU bound and where the collector runs
 * on the render thread's timeline. With at most tens of thousands of particles alive at once, a pool of
 * that size recycles them all and the allocation disappears.
 *
 * <h2>The two ways this goes wrong</h2>
 *
 * <p><b>Lending the same slot twice.</b> Two live particles would then share state, and moving one
 * moves the other. That is not a subtle artefact -- it is particles teleporting in pairs. So a slot is
 * marked lent the moment it leaves, and a second request for it is refused rather than served.
 *
 * <p><b>Recycling a slot that is still in use.</b> The same failure from the other direction, and
 * worse, because the caller has no idea its particle changed underneath it.
 *
 * <h2>Resetting completely</h2>
 *
 * <p>A recycled slot carries the previous particle's age, lifetime, colour and velocity. Handing it
 * over without clearing those gives a particle that starts half-faded, or one that expires on the
 * first tick because its inherited age already exceeded its lifetime. The reset therefore writes every
 * field, not just the ones the caller is about to set -- which fields those are is the caller's
 * business, and a pool that assumes otherwise is the bug.
 *
 * <p>Not thread safe. Particles are spawned and ticked on the client thread.
 */
public final class ParticlePool {

    /** Fields a slot carries, all of which must be reset before the slot is reused. */
    public static final int FIELDS = 8;

    public static final int POSITION_X = 0;
    public static final int POSITION_Y = 1;
    public static final int POSITION_Z = 2;
    public static final int VELOCITY_X = 4;
    public static final int VELOCITY_Y = 5;
    public static final int VELOCITY_Z = 6;
    public static final int AGE = 3;
    public static final int LIFETIME = 7;

    private static final int NO_SLOT = -1;

    private final float[][] slots;
    private final boolean[] lent;
    private final int[] freeList;
    private int freeCount;

    private long acquisitions;
    private long releases;
    private long refused;

    /**
     * @param capacity the most particles expected to be alive at once
     */
    public ParticlePool(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("a pool of " + capacity + " holds nothing");
        }
        this.slots = new float[capacity][FIELDS];
        this.lent = new boolean[capacity];
        this.freeList = new int[capacity];
        for (int i = 0; i < capacity; i++) {
            this.freeList[i] = i;
        }
        this.freeCount = capacity;
    }

    /**
     * Takes a slot, or reports that the pool is exhausted.
     *
     * <p>Returning a refusal rather than growing is deliberate. A caller that cannot get a slot drops
     * the particle, which is invisible next to the tens of thousands already on screen. Growing
     * instead would put the allocation back exactly when it is least welcome -- during the TNT chain
     * that caused the pressure in the first place.
     *
     * @return a slot index with every field cleared, or {@link #NO_SLOT}
     */
    public int acquire() {
        this.acquisitions++;
        if (this.freeCount == 0) {
            this.refused++;
            return NO_SLOT;
        }
        int slot = this.freeList[--this.freeCount];
        this.lent[slot] = true;
        reset(slot);
        return slot;
    }

    /**
     * Returns a slot to the pool.
     *
     * @return false if the slot was not lent, which means a double release or an index that was never
     *     handed out. Either is a caller bug, and silently accepting it would put the same slot on the
     *     free list twice so that two later acquisitions both receive it.
     */
    public boolean release(int slot) {
        if (slot < 0 || slot >= this.slots.length) {
            return false;
        }
        if (!this.lent[slot]) {
            return false;
        }
        this.lent[slot] = false;
        this.freeList[this.freeCount++] = slot;
        this.releases++;
        return true;
    }

    /**
     * Writes every field, so nothing from the previous particle survives.
     *
     * <p>The lifetime goes to one tick rather than zero. A slot released with a zero lifetime would
     * expire immediately if a caller forgot to set it, which is the right behaviour but a confusing one
     * to debug; one tick makes the omission visible as a brief flash instead of a missing particle.
     */
    private void reset(int slot) {
        float[] fields = this.slots[slot];
        for (int i = 0; i < FIELDS; i++) {
            fields[i] = 0.0f;
        }
        fields[LIFETIME] = 1.0f;
    }

    public float get(int slot, int field) {
        checkLent(slot);
        if (field < 0 || field >= FIELDS) {
            throw new IndexOutOfBoundsException("field " + field);
        }
        return this.slots[slot][field];
    }

    public void set(int slot, int field, float value) {
        checkLent(slot);
        if (field < 0 || field >= FIELDS) {
            throw new IndexOutOfBoundsException("field " + field);
        }
        this.slots[slot][field] = value;
    }

    private void checkLent(int slot) {
        if (slot < 0 || slot >= this.slots.length) {
            throw new IndexOutOfBoundsException("slot " + slot + " of " + this.slots.length);
        }
        // Reading or writing a slot that is not lent is the double-release bug surfacing, and it is far
        // easier to find here than as a particle that silently belongs to someone else.
        if (!this.lent[slot]) {
            throw new IllegalStateException(
                    "slot " + slot + " is not lent; a released slot must not be used");
        }
    }

    /**
     * @return how many slots are held by callers, which is the live particle count
     */
    public int inUse() {
        return this.slots.length - this.freeCount;
    }

    public int capacity() {
        return this.slots.length;
    }

    public long acquisitions() {
        return this.acquisitions;
    }

    public long releases() {
        return this.releases;
    }

    /** How many acquisitions found the pool empty. */
    public long refused() {
        return this.refused;
    }

    /**
     * Bytes the pool avoids allocating, over its lifetime.
     *
     * <p>Counted on acquisitions that were served from a recycled slot rather than a fresh one, which
     * is what the collector would otherwise have had to trace.
     */
    public long bytesAvoided(int bytesPerParticle) {
        long served = this.acquisitions - this.refused;
        // The first fill of the pool would have allocated anyway, so only the reuse is a saving.
        long reused = Math.max(0L, served - this.slots.length);
        return reused * bytesPerParticle;
    }
}
