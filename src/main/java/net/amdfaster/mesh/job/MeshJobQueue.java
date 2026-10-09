package net.amdfaster.mesh.job;

import net.amdfaster.dirty.SectionCoord;

/**
 * Per-worker job deques with round-robin distribution, work stealing, and bulk cancellation.
 *
 * <p>Why this shape. Chunk meshing is the largest CPU cost in the client and it is embarrassingly
 * parallel -- each section is built from its own blocks and touches no other section's output. The
 * scheduling core is a separate class from the threads for one reason: everything interesting about a
 * job system is which job runs next and which jobs are dropped, and none of that needs a thread to
 * check. Threads make the behaviour nondeterministic; this class makes it reproducible, so a wrong
 * ordering or a job that should have been cancelled shows up as a failing test rather than as an
 * occasional hitch nobody can reproduce.
 *
 * <p><b>Distribution.</b> Pushes go round-robin across the workers rather than all into one queue. A
 * single shared queue needs a lock on every take, and at thousands of sections per second that lock is
 * the whole cost. Round-robin spreads the work without coordination, and the rebalancing is left to
 * stealing, which only happens when a worker runs out.
 *
 * <p><b>Stealing.</b> An idle worker takes from the <em>far</em> end of the longest other deque. Far
 * end because the owner takes from the near end, so the two only contend when the deque is nearly
 * empty -- and by then there is almost nothing left to fight over. Longest deque because that is where
 * the imbalance is; stealing from a short one moves the problem around instead of solving it.
 *
 * <p><b>Cancellation.</b> Two mechanisms, for two different situations. A single section that changed
 * again while its job was queued is cancelled by scanning for it. The whole backlog -- a teleport, a
 * dimension change, a render distance change -- is cancelled by bumping the generation, which is one
 * write. Jobs from an older generation are dropped when polled rather than removed, so invalidating a
 * thousand queued sections costs the same as invalidating one.
 *
 * <p><b>Thread safety.</b> Each deque has its own lock, taken by the owner's {@link #poll}, by a thief's
 * {@link #steal}, and by {@link #push}. The lock cannot be dropped on the argument that a worker only
 * touches its own deque: stealing writes the victim's tail and count, which the victim's poll also
 * writes, so two threads are in the same deque at once by construction. An earlier version relied on
 * exactly that argument, with no locks, and the counters came back with 201 builds for 200 jobs -- one
 * section built twice, which is a wasted frame at best and duplicated geometry at worst.
 *
 * <p>The counters and the last-kind slot are per worker rather than shared, so a worker only ever
 * writes its own slot and needs no lock to do it. Aggregating them is a sum over the array.
 */
public final class MeshJobQueue {

    /** Geometry needs a full rebuild; light only needs the baked light values refreshed. */
    public static final int KIND_GEOMETRY = 0;
    public static final int KIND_LIGHT = 1;

    private final int workerCount;
    private final long[][] keys;
    private final int[][] kinds;
    private final long[][] generations;
    private final boolean[][] cancelled;

    private final int[] head;
    private final int[] tail;
    private final int[] count;

    /** One lock per deque, held by the owner's poll, a thief's steal, and any push. */
    private final Object[] locks;

    private int nextWorker;
    private long generation;

    /**
     * Counters, one slot per worker.
     *
     * <p>Per worker so that a worker only ever writes its own slot. Shared longs incremented from
     * several threads lose updates silently, and a counter that under-reports is worse than no counter
     * because it looks like a measurement.
     */
    private final long[] pushed;
    private final long[] polled;
    private final long[] stolen;
    private final long[] droppedStale;
    private final long[] droppedCancelled;

    /** The kind of the job each worker last took. Per worker for the same reason. */
    private final int[] lastKind;

    public MeshJobQueue(int workerCount, int capacityPerWorker) {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("worker count must be positive: " + workerCount);
        }
        if (capacityPerWorker <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacityPerWorker);
        }
        this.workerCount = workerCount;
        this.keys = new long[workerCount][capacityPerWorker];
        this.kinds = new int[workerCount][capacityPerWorker];
        this.generations = new long[workerCount][capacityPerWorker];
        this.cancelled = new boolean[workerCount][capacityPerWorker];
        this.head = new int[workerCount];
        this.tail = new int[workerCount];
        this.count = new int[workerCount];
        this.locks = new Object[workerCount];
        for (int i = 0; i < workerCount; i++) {
            this.locks[i] = new Object();
        }
        this.pushed = new long[workerCount];
        this.polled = new long[workerCount];
        this.stolen = new long[workerCount];
        this.droppedStale = new long[workerCount];
        this.droppedCancelled = new long[workerCount];
        this.lastKind = new int[workerCount];
    }

    /** One queue slot per available core, sized for a full render distance worth of sections. */
    public static MeshJobQueue forCores(int cores) {
        return new MeshJobQueue(Math.max(1, cores), 1024);
    }

    public int workerCount() {
        return this.workerCount;
    }

    public long generation() {
        return this.generation;
    }

    public long pushed() {
        return sum(this.pushed);
    }

    public long polled() {
        return sum(this.polled);
    }

    public long stolen() {
        return sum(this.stolen);
    }

    /** Jobs skipped on poll because their generation was superseded. */
    public long droppedStale() {
        return sum(this.droppedStale);
    }

    public long droppedCancelled() {
        return sum(this.droppedCancelled);
    }

    private static long sum(long[] values) {
        long total = 0;
        for (long value : values) {
            total += value;
        }
        return total;
    }

    /** Jobs currently queued and still valid, across every worker. */
    public int pending() {
        int live = 0;
        for (int w = 0; w < this.workerCount; w++) {
            synchronized (this.locks[w]) {
                int h = this.head[w];
                for (int i = 0; i < this.count[w]; i++) {
                    int slot = (h + i) % this.keys[w].length;
                    if (this.generations[w][slot] == this.generation && !this.cancelled[w][slot]) {
                        live++;
                    }
                }
            }
        }
        return live;
    }

    /** Slots still occupied, including stale and cancelled ones that have not been polled past yet. */
    public int resident() {
        int total = 0;
        for (int w = 0; w < this.workerCount; w++) {
            synchronized (this.locks[w]) {
                total += this.count[w];
            }
        }
        return total;
    }

    public int pendingFor(int worker) {
        synchronized (this.locks[worker]) {
            return this.count[worker];
        }
    }

    /**
     * Queues a section for the next worker in turn.
     *
     * @return false if that worker's deque is full. A full deque means the builders are falling behind,
     *         and the answer is to drop the job -- it will be requeued the next time the section is
     *         marked dirty, which happens continuously for anything the player can see.
     */
    public boolean push(int sectionX, int sectionY, int sectionZ, int kind) {
        int w;
        synchronized (this) {
            w = this.nextWorker;
            this.nextWorker = (this.nextWorker + 1) % this.workerCount;
        }
        synchronized (this.locks[w]) {
            int capacity = this.keys[w].length;
            if (this.count[w] == capacity) {
                return false;
            }
            int slot = this.tail[w];
            this.keys[w][slot] = SectionCoord.key(sectionX, sectionY, sectionZ);
            this.kinds[w][slot] = kind;
            this.generations[w][slot] = this.generation;
            this.cancelled[w][slot] = false;
            this.tail[w] = (slot + 1) % capacity;
            this.count[w]++;
            this.pushed[w]++;
            return true;
        }
    }

    /**
     * Takes the next job for a worker, or -1 if it has none.
     *
     * <p>Stale and cancelled slots are stepped over rather than reported, so the caller never sees a
     * job it should not run. Stepping over them here rather than removing them at cancellation time is
     * what makes a bulk cancel one write.
     */
    public long poll(int worker) {
        synchronized (this.locks[worker]) {
            int capacity = this.keys[worker].length;
            while (this.count[worker] > 0) {
                int slot = this.head[worker];
                this.head[worker] = (slot + 1) % capacity;
                this.count[worker]--;
                long key = this.keys[worker][slot];
                if (this.cancelled[worker][slot]) {
                    this.droppedCancelled[worker]++;
                    continue;
                }
                if (this.generations[worker][slot] != this.generation) {
                    this.droppedStale[worker]++;
                    continue;
                }
                this.polled[worker]++;
                this.lastKind[worker] = this.kinds[worker][slot];
                return key;
            }
            return -1L;
        }
    }

    /**
     * The kind of the job this worker last took.
     *
     * <p>Per worker rather than one shared slot, because a shared one is written by every worker and
     * read by the one that just polled -- a worker would regularly get another worker's kind back, and
     * building geometry when light was asked for produces a mesh with stale lighting that nothing
     * reports as wrong.
     */
    public int lastKind(int worker) {
        return this.lastKind[worker];
    }

    /**
     * Takes a job from the far end of the longest other deque.
     *
     * @return the stolen key, or -1 if every other worker is empty
     */
    public long steal(int worker) {
        // Choosing the victim reads counts without holding every lock, which is deliberate: the choice
        // only has to be reasonable, not exact, and taking every lock to pick one would cost more than
        // the imbalance it corrects. The take itself is done under the victim's lock.
        int victim = -1;
        int longest = 0;
        for (int w = 0; w < this.workerCount; w++) {
            if (w != worker && this.count[w] > longest) {
                longest = this.count[w];
                victim = w;
            }
        }
        if (victim < 0) {
            return -1L;
        }
        synchronized (this.locks[victim]) {
            int capacity = this.keys[victim].length;
            while (this.count[victim] > 0) {
                this.tail[victim] = (this.tail[victim] - 1 + capacity) % capacity;
                int slot = this.tail[victim];
                this.count[victim]--;
                long key = this.keys[victim][slot];
                if (this.cancelled[victim][slot]) {
                    this.droppedCancelled[worker]++;
                    continue;
                }
                if (this.generations[victim][slot] != this.generation) {
                    this.droppedStale[worker]++;
                    continue;
                }
                this.stolen[worker]++;
                this.polled[worker]++;
                this.lastKind[worker] = this.kinds[victim][slot];
                return key;
            }
            return -1L;
        }
    }

    /**
     * Cancels every queued job for one section.
     *
     * <p>A scan, because it is rare: a section is only cancelled when it changed again while its build
     * was still waiting. Cancelling is cheaper than building -- building a section that is about to
     * change again is pure waste, and a burst of block edits marks the same section over and over.
     *
     * @return how many queued jobs were cancelled
     */
    public int cancelSection(int sectionX, int sectionY, int sectionZ) {
        long key = SectionCoord.key(sectionX, sectionY, sectionZ);
        int found = 0;
        for (int w = 0; w < this.workerCount; w++) {
            synchronized (this.locks[w]) {
                int capacity = this.keys[w].length;
                int h = this.head[w];
                for (int i = 0; i < this.count[w]; i++) {
                    int slot = (h + i) % capacity;
                    if (this.keys[w][slot] == key && !this.cancelled[w][slot]
                            && this.generations[w][slot] == this.generation) {
                        this.cancelled[w][slot] = true;
                        found++;
                    }
                }
            }
        }
        return found;
    }

    /**
     * Invalidates the entire backlog in one write.
     *
     * <p>For a teleport, a dimension change, or a render distance change: everything queued was chosen
     * for a camera position that no longer holds, so none of it is worth building. Doing this by
     * removing jobs would cost a walk over the whole backlog; doing it by generation costs nothing and
     * the slots are reclaimed as they are polled past.
     *
     * @return the new generation
     */
    public synchronized long invalidateAll() {
        this.generation++;
        return this.generation;
    }

    /** Empties every deque. */
    public void clear() {
        for (int w = 0; w < this.workerCount; w++) {
            synchronized (this.locks[w]) {
                this.head[w] = 0;
                this.tail[w] = 0;
                this.count[w] = 0;
            }
        }
        synchronized (this) {
            this.nextWorker = 0;
        }
    }
}
