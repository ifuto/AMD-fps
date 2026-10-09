package net.amdfaster.mesh.job;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import net.amdfaster.dirty.SectionCoord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshWorkerPoolTest {

    @Test
    void everyQueuedSectionIsBuiltExactlyOnce() throws InterruptedException {
        // The property that matters and the one threading makes hard to check: no job lost, none built
        // twice. A build run twice wastes a frame; a build lost leaves a hole in the world that stays
        // until the player walks away and back.
        int cores = 4;
        int jobs = 400;
        Set<Long> built = Collections.newSetFromMap(new ConcurrentHashMap<>());
        CountDownLatch done = new CountDownLatch(jobs);
        AtomicLong duplicates = new AtomicLong();

        MeshWorkerPool pool = MeshWorkerPool.forCores(cores, (key, kind) -> {
            if (!built.add(key)) {
                duplicates.incrementAndGet();
            }
            done.countDown();
        });
        try {
            pool.start();
            assertTrue(pool.isRunning());
            assertEquals(cores - 1, pool.threadCount(), "one core left to the render thread");

            for (int i = 0; i < jobs; i++) {
                while (!pool.queue().push(i & 15, 4 + (i >> 8), (i >> 4) & 15, MeshJobQueue.KIND_GEOMETRY)) {
                    Thread.yield();
                }
            }
            pool.signal();

            assertTrue(done.await(30, TimeUnit.SECONDS), "all jobs built within the timeout");
            assertEquals(jobs, built.size(), "every section built");
            assertEquals(0, duplicates.get(), "and none built twice");
        } finally {
            assertTrue(pool.stop(5000), "workers stopped");
        }
        assertFalse(pool.isRunning());
    }

    @Test
    void workIsSpreadAcrossThreadsRatherThanDoneByOne() throws InterruptedException {
        // The point of the pool. If one worker did everything, the others would sit idle and the queue
        // would be no better than a serial loop with extra threads attached.
        int cores = 5;
        int jobs = 200;
        Set<String> threadNames = Collections.newSetFromMap(new ConcurrentHashMap<>());
        CountDownLatch done = new CountDownLatch(jobs);

        MeshWorkerPool pool = MeshWorkerPool.forCores(cores, (key, kind) -> {
            threadNames.add(Thread.currentThread().getName());
            done.countDown();
        });
        try {
            pool.start();
            for (int i = 0; i < jobs; i++) {
                while (!pool.queue().push(i & 15, 4, (i >> 4) & 15, MeshJobQueue.KIND_GEOMETRY)) {
                    Thread.yield();
                }
            }
            pool.signal();
            assertTrue(done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.stop(5000);
        }
        assertEquals(cores - 1, pool.threadCount());
        assertTrue(threadNames.size() >= 2,
                "at least two workers took part, saw " + threadNames.size() + ": " + threadNames);
        assertEquals(jobs, pool.buildsCompleted());
    }

    @Test
    void anInvalidatedBacklogIsNotBuilt() throws InterruptedException {
        // A teleport invalidates everything queued. The workers must not build a thousand sections for a
        // camera position that no longer holds -- which is precisely the hitch a teleport causes today.
        MeshWorkerPool pool = MeshWorkerPool.forCores(4, (key, kind) -> { });
        try {
            // Queue a large backlog before starting, so nothing can have been built yet.
            for (int i = 0; i < 500; i++) {
                pool.queue().push(i & 15, 4, (i >> 4) & 15, MeshJobQueue.KIND_GEOMETRY);
            }
            assertEquals(500, pool.queue().pending());

            pool.queue().invalidateAll();
            assertEquals(0, pool.queue().pending());

            pool.start();
            pool.queue().push(0, 99, 0, MeshJobQueue.KIND_GEOMETRY);
            pool.signal();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (pool.buildsCompleted() < 1 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            // Wait well past what a build needs, so that if the stale backlog were going to be built it
            // would have been built by now. Without this the test would pass on a pool that simply had
            // not got to the stale jobs yet.
            Thread.sleep(300);
            assertEquals(1, pool.buildsCompleted(), "only the job queued after the invalidation");
        } finally {
            pool.stop(5000);
        }
    }

    @Test
    void stopWaitsForWorkersAndIsIdempotent() {
        MeshWorkerPool pool = MeshWorkerPool.forCores(3, (key, kind) -> { });
        pool.start();
        pool.start();
        assertEquals(2, pool.threadCount(), "starting twice does not double the threads");
        assertTrue(pool.stop(5000));
        assertFalse(pool.isRunning());
        assertTrue(pool.stop(1000), "stopping an already stopped pool is fine");
    }

    @Test
    void aPoolWithNoThreadsStillHoldsItsQueue() {
        // Degenerate but reachable: a caller that sizes the pool from a config value of zero. The queue
        // has to stay usable so the caller can drain it itself rather than lose the work.
        MeshJobQueue queue = new MeshJobQueue(1, 16);
        MeshWorkerPool pool = new MeshWorkerPool(queue, (key, kind) -> { }, 0);
        assertEquals(0, pool.threadCount());
        queue.push(1, 4, 1, MeshJobQueue.KIND_GEOMETRY);
        assertEquals(SectionCoord.key(1, 4, 1), queue.poll(0), "the caller can still drive it by hand");
        assertTrue(pool.stop(100));
    }
}
