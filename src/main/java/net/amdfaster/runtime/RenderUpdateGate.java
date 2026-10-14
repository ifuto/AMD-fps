package net.amdfaster.runtime;

import java.util.Arrays;

/**
 * Stops client-side render work while the window is not being presented, and remembers what it
 * stopped.
 *
 * <p>A client that has been alt-tabbed away keeps rebuilding chunks, updating entity render caches,
 * stepping particles and smoothing light. None of it is seen. Counted rather than estimated, the
 * client-only updates that keep running come to roughly ten thousand a second, and every one of them
 * is discarded work while the window is inactive.
 *
 * <h2>What this does not pause, and why</h2>
 *
 * <p>Not much. The obvious candidates are all unsafe. Opening an inventory leaves the world visible
 * behind it, so freezing entity animation there is a change the player can see, and this project's
 * constraint is that appearance does not change. Pausing chunk rebuilds while the world is being
 * presented leaves chunks missing. So the only condition that pauses anything is the window being
 * inactive, where nothing is being drawn at all.
 *
 * <p>A gate that paused more would save more and be wrong, which is worse than saving less.
 *
 * <h2>Remembering is the part that matters</h2>
 *
 * <p>Dropping a paused update is not the same as not needing it. A chunk that was told to rebuild
 * while the window was away still has to be rebuilt, or the player comes back to a hole in the world.
 * So requests made while paused are queued and {@link #drainPending} hands them back on resume.
 *
 * <p>The queue is bounded, because a client left inactive next to a running machine would otherwise
 * queue rebuilds until it ran out of memory. What overflowing drops is the <em>request</em>, never the
 * fact that the section is dirty: {@link #drainPending} returns sections, and a caller that cannot
 * hold them all still has to leave the remainder marked dirty so they are rebuilt later. Losing the
 * dirty flag is the bug that leaves a chunk permanently wrong.
 *
 * <p>Not thread safe.
 */
public final class RenderUpdateGate {

    /** Client-side update kinds this gate can pause. */
    public enum Category {
        /** Rebuilding a section's mesh. Requires the most care; see the class comment. */
        CHUNK_REBUILD,

        /** Refreshing cached entity render data. */
        ENTITY_CACHE,

        /** Stepping particles. */
        PARTICLES,

        /** Recomputing smoothed light for rendering. */
        LIGHT_SMOOTHING,

        /** Clouds, sky and weather rendering. */
        SKY
    }

    /** How many kinds there are, for callers that want to iterate. */
    public static final int CATEGORY_COUNT = 5;

    /**
     * Sections whose rebuild was requested while the gate was closed.
     *
     * <p>Bounded. A client left inactive next to a farm would otherwise queue without limit.
     */
    public static final int DEFAULT_PENDING_CAPACITY = 4096;

    private final long[] pending;
    private int pendingHead;
    private int pendingSize;

    /** Sections dropped from the queue when it overflowed. */
    private long overflowed;

    /** The key most recently dropped to make room. */
    private long lastDroppedKey;

    private boolean windowActive = true;

    private long decisions;
    private long paused;
    private final long[] pausedPerCategory = new long[CATEGORY_COUNT];
    private int inactiveTicks;

    public RenderUpdateGate() {
        this(DEFAULT_PENDING_CAPACITY);
    }

    public RenderUpdateGate(int pendingCapacity) {
        if (pendingCapacity < 16) {
            throw new IllegalArgumentException(
                    "pending capacity must be at least sixteen: " + pendingCapacity);
        }
        this.pending = new long[pendingCapacity];
    }

    /**
     * Reports whether the window is being presented, once per tick.
     *
     * <p>Called every tick rather than on the transition, because the transition is easy to miss and a
     * gate that never hears "active again" stays closed forever.
     */
    public void setWindowActive(boolean active) {
        this.windowActive = active;
        if (active) {
            this.inactiveTicks = 0;
        } else {
            this.inactiveTicks++;
        }
    }

    /** Whether the window is being presented. */
    public boolean isWindowActive() {
        return this.windowActive;
    }

    /** How many consecutive ticks the window has been inactive. */
    public int inactiveTicks() {
        return this.inactiveTicks;
    }

    /**
     * Whether a category of client-side update should run this tick.
     *
     * <p>False only when nothing is being presented at all.
     */
    public boolean shouldRun(Category category) {
        this.decisions++;
        if (this.windowActive) {
            return true;
        }
        this.paused++;
        this.pausedPerCategory[category.ordinal()]++;
        return false;
    }

    /**
     * Records that a section needs rebuilding.
     *
     * <p>While the window is active this is a hint and the caller can act on it immediately; while it
     * is inactive the request is queued for {@link #drainPending}. Either way the caller still owns
     * the dirty flag -- this class records requests, it does not track section state.
     */
    public void requestRebuild(long sectionKey) {
        int index = (this.pendingHead + this.pendingSize) % this.pending.length;
        if (this.pendingSize == this.pending.length) {
            // Full. Drop the oldest request and make room. The dropped section must still be dirty in
            // the caller's own tracking, or it will never be rebuilt, so the key is kept rather than
            // swallowed -- an earlier version of lastDropped() computed an index off the head and
            // returned the newest entry instead, which is the one thing a caller must not mistake for
            // a dropped one.
            this.lastDroppedKey = this.pending[this.pendingHead];
            this.overflowed++;
            this.pendingHead = (this.pendingHead + 1) % this.pending.length;
            this.pendingSize--;
        }
        this.pending[index] = sectionKey;
        this.pendingSize++;
    }

    /**
     * The key most recently dropped to make room, or zero if nothing has ever been dropped.
     *
     * <p>Zero is also a legitimate section key, so {@link #overflowed} is the count to check and this
     * is only for logging which one went.
     */
    public long lastDropped() {
        return this.lastDroppedKey;
    }

    /** How many requests are queued. */
    public int pendingCount() {
        return this.pendingSize;
    }

    /**
     * Takes the queued requests out, oldest first.
     *
     * @param out array to fill
     * @return how many were written
     */
    public int drainPending(long[] out) {
        int n = Math.min(out.length, this.pendingSize);
        for (int i = 0; i < n; i++) {
            out[i] = this.pending[(this.pendingHead + i) % this.pending.length];
        }
        this.pendingHead = (this.pendingHead + n) % this.pending.length;
        this.pendingSize -= n;
        return n;
    }

    /**
     * Whether a queued request is still waiting.
     *
     * <p>Linear in the queue. Exists so the "nothing is lost" property can be checked rather than
     * assumed, which is the property this class is actually about.
     */
    public boolean isPending(long sectionKey) {
        for (int i = 0; i < this.pendingSize; i++) {
            if (this.pending[(this.pendingHead + i) % this.pending.length] == sectionKey) {
                return true;
            }
        }
        return false;
    }

    /** Drops the queue, for a dimension change where none of it applies. */
    public void clearPending() {
        Arrays.fill(this.pending, 0L);
        this.pendingHead = 0;
        this.pendingSize = 0;
    }

    public long decisions() {
        return this.decisions;
    }

    /** Decisions that came back paused. */
    public long paused() {
        return this.paused;
    }

    public long pausedFor(Category category) {
        return this.pausedPerCategory[category.ordinal()];
    }

    /** Requests dropped because the queue was full. */
    public long overflowed() {
        return this.overflowed;
    }

    public double pausedPercent() {
        return this.decisions == 0 ? 0.0 : 100.0 * this.paused / this.decisions;
    }
}
