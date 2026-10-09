package net.amdfaster.mesh.job;

/**
 * The threads that drive a {@link MeshJobQueue}.
 *
 * <p>Deliberately thin. All the decisions -- what runs next, what gets dropped, who steals from whom --
 * live in the queue, where they can be tested without a thread. What is left here is starting workers,
 * parking them when there is nothing to do, and stopping them, which is the part that cannot be made
 * deterministic and so is kept as small as possible.
 *
 * <p>Worker count is cores minus one, leaving a core for the render thread, which is the thread that
 * has to hit a deadline. A pool sized to all cores competes with the thread that actually produces
 * frames, and the result is smoother chunk loading at the cost of the thing the player is looking at.
 *
 * <p>A worker that finds its own deque empty steals before parking, and parks only when every deque is
 * empty. Parking immediately on an empty own deque would leave cores idle while other workers are
 * still busy, which is the whole imbalance stealing exists to fix.
 */
public final class MeshWorkerPool {

    /** Builds one section. Called on a worker thread, so it must not touch render state. */
    public interface Builder {
        void build(long sectionKey, int kind);
    }

    private final MeshJobQueue queue;
    private final Builder builder;
    private final Thread[] workers;

    private volatile boolean running;
    private final Object idleLock = new Object();

    private long buildsCompleted;
    private long parkEvents;

    public MeshWorkerPool(MeshJobQueue queue, Builder builder, int threadCount) {
        this.queue = queue;
        this.builder = builder;
        this.workers = new Thread[Math.max(0, threadCount)];
    }

    /** One thread per core except one, which is left to the render thread. */
    public static MeshWorkerPool forCores(int cores, Builder builder) {
        int threads = Math.max(1, cores - 1);
        return new MeshWorkerPool(MeshJobQueue.forCores(threads), builder, threads);
    }

    public int threadCount() {
        return this.workers.length;
    }

    public MeshJobQueue queue() {
        return this.queue;
    }

    public long buildsCompleted() {
        return this.buildsCompleted;
    }

    public long parkEvents() {
        return this.parkEvents;
    }

    public boolean isRunning() {
        return this.running;
    }

    public void start() {
        if (this.running) {
            return;
        }
        this.running = true;
        for (int i = 0; i < this.workers.length; i++) {
            final int id = i;
            Thread thread = new Thread(() -> run(id), "amdfaster-mesh-" + i);
            // Below normal: meshing is background work, and a mesh build that preempts the render
            // thread costs a frame, which is worse than the mesh arriving one frame later.
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            thread.setDaemon(true);
            this.workers[i] = thread;
            thread.start();
        }
    }

    private void run(int id) {
        while (this.running) {
            long key = this.queue.poll(id);
            boolean didWork = false;
            if (key >= 0) {
                didWork = true;
            } else {
                // Own deque empty. Try to take someone else's backlog before giving up, which is what
                // keeps every core busy when the work was distributed unevenly.
                key = this.queue.steal(id);
                if (key >= 0) {
                    didWork = true;
                }
            }
            if (didWork) {
                this.builder.build(key, this.queue.lastKind());
                synchronized (this) {
                    this.buildsCompleted++;
                }
                continue;
            }
            // Nothing anywhere. Park rather than spin: a spinning worker burns a core that the render
            // thread needs, and does it for no reason at all.
            synchronized (this.idleLock) {
                if (this.running && this.queue.resident() == 0) {
                    this.parkEvents++;
                    try {
                        this.idleLock.wait(2L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /** Wakes any parked worker, for after a batch of jobs was queued. */
    public void signal() {
        synchronized (this.idleLock) {
            this.idleLock.notifyAll();
        }
    }

    /**
     * Stops the workers and waits for them to finish.
     *
     * @param timeoutMillis how long to wait for each worker to exit
     * @return true if every worker stopped within the timeout
     */
    public boolean stop(long timeoutMillis) {
        this.running = false;
        signal();
        boolean allStopped = true;
        for (Thread worker : this.workers) {
            if (worker == null) {
                continue;
            }
            try {
                worker.join(timeoutMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (worker.isAlive()) {
                allStopped = false;
            }
        }
        return allStopped;
    }
}
