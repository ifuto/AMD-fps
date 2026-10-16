package net.amdfaster.mem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScratchObjectPoolTest {

    /** Stands in for a scratch vector: a value the previous user would have left behind. */
    private static final class Vec {
        double x;
    }

    private static ScratchObjectPool<Vec> pool() {
        return new ScratchObjectPool<>(Vec::new, vec -> vec.x = 0.0);
    }

    @Test
    void theFirstTakeAllocates() {
        ScratchObjectPool<Vec> pool = pool();

        Vec vec = pool.take();

        assertEquals(1L, pool.allocated());
        assertEquals(0L, pool.reused());
        assertEquals(0.0, vec.x, 1e-12);
    }

    @Test
    void aReturnedObjectIsReused() {
        ScratchObjectPool<Vec> pool = pool();

        Vec first = pool.take();
        pool.give(first);
        Vec second = pool.take();

        assertSame(first, second, "the same object, not a new one");
        assertEquals(1L, pool.reused());
        assertEquals(1L, pool.allocated());
    }

    @Test
    void aReusedObjectIsResetBeforeItIsHandedOut() {
        // An object returned to the pool still holds whatever its last user left in it, and handing it out
        // unchanged would give the next user data from somewhere else in the game -- a vector already
        // pointing at another entity.
        ScratchObjectPool<Vec> pool = pool();

        Vec first = pool.take();
        first.x = 42.0;
        pool.give(first);

        Vec second = pool.take();

        assertSame(first, second);
        assertEquals(0.0, second.x, 1e-12, "cleaned on the way out, not on the way in");
    }

    @Test
    void exhaustionAllocatesRatherThanFailing() {
        // A burst can need more objects than the pool holds -- a hundred entities appearing at once when a
        // spawner is uncovered. Throwing would turn a busy frame into a crash.
        ScratchObjectPool<Vec> pool = pool();

        List<Vec> taken = new ArrayList<>();
        for (int i = 0; i < ScratchObjectPool.CAPACITY + 100; i++) {
            taken.add(pool.take());
        }

        assertEquals(ScratchObjectPool.CAPACITY + 100L, pool.allocated());
        assertEquals(0L, pool.reused());
    }

    @Test
    void aFullPoolDropsExtraReturns() {
        // A burst that allocates a thousand objects should not keep a thousand forever, so the memory cost
        // of pooling is the size of the working set rather than the size of the worst burst.
        ScratchObjectPool<Vec> pool = pool();

        for (int i = 0; i < ScratchObjectPool.CAPACITY + 50; i++) {
            pool.give(new Vec());
        }

        assertEquals(ScratchObjectPool.CAPACITY, pool.size());
        assertEquals(50L, pool.dropped());
    }

    @Test
    void aSteadyStateReusesEverything() {
        ScratchObjectPool<Vec> pool = pool();
        Vec scratch = pool.take();

        for (int frame = 0; frame < 1000; frame++) {
            pool.give(scratch);
            scratch = pool.take();
        }

        assertEquals(1001L, pool.taken());
        assertEquals(1000L, pool.reused());
        assertEquals(1L, pool.allocated());
        assertEquals(99.9, pool.reusePercent(), 1e-9);
    }

    @Test
    void theCollectionSavingIsTheLargerPart() {
        // The allocations themselves are cheap; what they cost is the collections they cause, and those are
        // what show up as small frequent hitches rather than one long pause.
        ScratchObjectPool<Vec> pool = pool();
        Vec scratch = pool.take();

        for (int i = 0; i < 100_000; i++) {
            pool.give(scratch);
            scratch = pool.take();
        }

        assertEquals(100_000L, pool.reused());
        assertEquals(10.0, pool.msSaved(), 1e-9);
        assertEquals(1.5, pool.msCollectionSaved(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ScratchObjectPool<>(null, v -> { }));
        assertThrows(IllegalArgumentException.class, () -> new ScratchObjectPool<>(Vec::new, null));

        ScratchObjectPool<Vec> pool = pool();
        assertThrows(IllegalArgumentException.class, () -> pool.give(null));
    }

    @Test
    void clearingDropsEveryPooledObject() {
        ScratchObjectPool<Vec> pool = pool();
        pool.give(new Vec());

        pool.clear();

        assertEquals(0, pool.size());
        assertTrue(pool.take() != null);
    }

    @Test
    void aFreshPoolReportsZeroNotNaN() {
        // A pool that has handed out nothing has technically avoided every allocation, but reporting that
        // as a saving would describe an unused one as though it had done something.
        ScratchObjectPool<Vec> pool = pool();

        assertEquals(0.0, pool.reusePercent());
        assertEquals(0.0, pool.savedPercent());
        assertEquals(0.0, pool.msSaved());
        assertEquals(0.0, pool.msCollectionSaved());
        assertEquals(0L, pool.taken());
        assertEquals(0, pool.size());
        assertEquals(256, ScratchObjectPool.CAPACITY);
    }
}
