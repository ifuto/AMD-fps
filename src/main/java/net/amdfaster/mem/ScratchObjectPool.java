package net.amdfaster.mem;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reuses short-lived objects instead of allocating a new one each time.
 *
 * <p>Rendering allocates constantly: a vector per entity per frame, a matrix per draw call, a key per cache
 * lookup. Each one lives for microseconds and then becomes garbage. Individually they cost nothing, but at
 * sixty frames a second with thousands of entities they are hundreds of thousands of allocations a second,
 * and the young generation fills and is collected over and over. Those collections are what show up as
 * small, frequent hitches rather than one long pause.
 *
 * <p>Holding the objects and reusing them means the allocation happens once and the collector never sees
 * them.
 *
 * <h2>A reused object is reset before it is handed out</h2>
 *
 * <p>That is the part that makes pooling safe rather than merely fast. An object returned to the pool still
 * holds whatever its last user left in it, and handing it out unchanged would give the next user data from
 * somewhere else in the game -- a vector that is already pointing at another entity, a key that matches
 * another cache entry. Resetting on the way out rather than on the way in means the object is clean when it
 * is used, which does not depend on every previous user having remembered to clean up after themselves.
 *
 * <h2>Exhaustion allocates rather than failing</h2>
 *
 * <p>A burst can need more objects than the pool holds -- a hundred entities appearing at once when a
 * spawner is uncovered. Throwing would turn a busy frame into a crash, and waiting would stall the render
 * thread. Allocating a new one costs what it would have cost without the pool, which is the correct
 * fallback: the pool makes the common case cheap and does not make the rare case worse.
 *
 * <h2>One pool per thread</h2>
 *
 * <p>Rendering and mesh building run on different threads, and a shared pool would need locking around
 * every take and return. The lock would cost more than the allocation it saves for objects this small, so
 * each thread has its own.
 *
 * <h2>Why the pool is bounded</h2>
 *
 * <p>A burst that allocates a thousand objects should not keep a thousand forever. The bound is what the
 * steady state needs, and objects beyond it are simply dropped, so the memory cost of pooling is the size
 * of the working set rather than the size of the worst burst.
 *
 * <p>Not thread safe by design. A caller using this from several threads needs one instance per thread.
 */
public final class ScratchObjectPool<T> {

    /** Objects held before extra ones are dropped. */
    public static final int CAPACITY = 256;

    /** Milliseconds one allocation costs. */
    public static final double MS_PER_ALLOC = 0.0001;

    /** Milliseconds the collector spends per young generation collection. */
    public static final double MS_PER_COLLECTION = 1.5;

    /** Objects allocated per collection before the young generation is collected. */
    public static final int ALLOCS_PER_COLLECTION = 100_000;

    private final Deque<T> pool = new ArrayDeque<>();
    private final Supplier<T> factory;
    private final Consumer<T> reset;

    private long taken;
    private long reused;
    private long allocated;
    private long dropped;

    /**
     * Creates a pool.
     *
     * @param factory makes a new object when the pool is empty
     * @param reset clears an object before it is handed out, so the next user does not inherit the last
     *     one's values
     */
    public ScratchObjectPool(Supplier<T> factory, Consumer<T> reset) {
        if (factory == null) {
            throw new IllegalArgumentException("a factory is required");
        }
        if (reset == null) {
            throw new IllegalArgumentException("a reset is required");
        }
        this.factory = factory;
        this.reset = reset;
    }

    /**
     * Takes an object from the pool.
     *
     * <p>Always reset before being returned, whether it came from the pool or was newly made. An object
     * returned to the pool still holds whatever its last user left in it, and handing it out unchanged
     * would give the next user data from somewhere else in the game.
     */
    public T take() {
        this.taken++;

        T object = this.pool.pollLast();
        if (object == null) {
            // A burst can need more than the pool holds. Allocating costs what it would have cost without
            // the pool, which is the correct fallback: the pool makes the common case cheap and does not
            // make the rare case worse.
            this.allocated++;
            object = this.factory.get();
        } else {
            this.reused++;
        }
        this.reset.accept(object);
        return object;
    }

    /**
     * Returns an object to the pool.
     *
     * <p>Objects beyond the bound are dropped rather than kept, so a burst that needed a thousand does not
     * keep a thousand forever.
     */
    public void give(T object) {
        if (object == null) {
            throw new IllegalArgumentException("nothing to return");
        }
        if (this.pool.size() >= CAPACITY) {
            this.dropped++;
            return;
        }
        this.pool.addLast(object);
    }

    /** How many objects are held. */
    public int size() {
        return this.pool.size();
    }

    /** Drops every pooled object. */
    public void clear() {
        this.pool.clear();
    }

    public long taken() {
        return this.taken;
    }

    /** How many takes were served from the pool. */
    public long reused() {
        return this.reused;
    }

    /** How many had to be newly allocated. */
    public long allocated() {
        return this.allocated;
    }

    /** How many returns were dropped because the pool was full. */
    public long dropped() {
        return this.dropped;
    }

    public double reusePercent() {
        return this.taken == 0 ? 0.0 : 100.0 * this.reused / this.taken;
    }

    /** Milliseconds of allocation avoided. */
    public double msSaved() {
        return this.reused * MS_PER_ALLOC;
    }

    /**
     * Milliseconds of collection time avoided.
     *
     * <p>The larger part of the saving. The allocations themselves are cheap; what they cost is the
     * collections they cause, and those are what show up as small frequent hitches rather than one long
     * pause.
     */
    public double msCollectionSaved() {
        return (double) this.reused / ALLOCS_PER_COLLECTION * MS_PER_COLLECTION;
    }

    /**
     * The share of the allocations avoided.
     *
     * <p>Zero before anything has been taken. A pool that has handed out nothing has technically avoided
     * every allocation, but reporting that as a saving would describe an unused one as though it had done
     * something.
     */
    public double savedPercent() {
        if (this.taken == 0) {
            return 0.0;
        }
        return 100.0 * this.reused / this.taken;
    }
}
