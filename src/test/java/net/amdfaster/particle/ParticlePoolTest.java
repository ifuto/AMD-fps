package net.amdfaster.particle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticlePoolTest {

    @Test
    void slotsAreRecycledSoNothingIsAllocated() {
        // The whole point. A TNT chain spawns thousands of particles a second; with at most tens of
        // thousands alive, a pool of that size never has to allocate again after the first fill.
        ParticlePool pool = new ParticlePool(1000);

        for (int round = 0; round < 50; round++) {
            for (int i = 0; i < 1000; i++) {
                assertNotEquals(-1, pool.acquire(), "round " + round + " particle " + i);
            }
            assertEquals(1000, pool.inUse());
            for (int i = 0; i < 1000; i++) {
                pool.release(i);
            }
        }

        assertEquals(50_000L, pool.acquisitions());
        assertEquals(50_000L, pool.releases());
        assertEquals(0, pool.inUse());
        // The first 1000 would have allocated anyway; the 49,000 after are reuse.
        assertEquals(49_000L * 64, pool.bytesAvoided(64));
    }

    @Test
    void theSameSlotIsNeverLentTwice() {
        // Lending a slot twice means two live particles share state, and moving one moves the other.
        // That shows up as particles teleporting in pairs, which is not a subtle artefact.
        ParticlePool pool = new ParticlePool(16);

        boolean[] seen = new boolean[16];
        for (int i = 0; i < 16; i++) {
            int slot = pool.acquire();
            assertFalse(seen[slot], "slot " + slot + " handed out twice");
            seen[slot] = true;
        }

        assertEquals(-1, pool.acquire(), "an exhausted pool refuses rather than doubling up");
        assertEquals(1L, pool.refused());
    }

    @Test
    void aDoubleReleaseIsRefused() {
        // Accepting it would put the slot on the free list twice, so two later acquisitions would both
        // receive it and the double-lending bug would appear far from its cause.
        ParticlePool pool = new ParticlePool(8);
        int slot = pool.acquire();

        assertTrue(pool.release(slot));
        assertFalse(pool.release(slot), "already returned");
        assertEquals(1L, pool.releases(), "and the count did not move either");
    }

    @Test
    void aSlotThatWasNeverLentCannotBeReleased() {
        ParticlePool pool = new ParticlePool(8);

        assertFalse(pool.release(0), "nothing was lent");
        assertFalse(pool.release(-1));
        assertFalse(pool.release(999));
        assertEquals(0L, pool.releases());
    }

    @Test
    void aReleasedSlotMustNotBeUsed() {
        // Using one is the double-release bug surfacing. It is far easier to find here than as a
        // particle that silently belongs to someone else.
        ParticlePool pool = new ParticlePool(4);
        int slot = pool.acquire();
        pool.release(slot);

        assertThrows(IllegalStateException.class, () -> pool.get(slot, ParticlePool.AGE));
        assertThrows(IllegalStateException.class, () -> pool.set(slot, ParticlePool.AGE, 1.0f));
    }

    @Test
    void everyFieldIsClearedOnReuse() {
        // A recycled slot carries the previous particle's age, lifetime, colour and velocity. Handing
        // it over without clearing them gives a particle that starts half-faded, or one that expires on
        // the first tick because its inherited age already exceeded its lifetime.
        ParticlePool pool = new ParticlePool(1);

        int slot = pool.acquire();
        for (int f = 0; f < ParticlePool.FIELDS; f++) {
            pool.set(slot, f, 1234.5f);
        }
        pool.release(slot);

        int reused = pool.acquire();
        assertEquals(slot, reused, "the only slot, recycled");

        for (int f = 0; f < ParticlePool.FIELDS; f++) {
            if (f == ParticlePool.LIFETIME) {
                continue;
            }
            assertEquals(0.0f, pool.get(reused, f), "field " + f + " still holds the old particle");
        }
    }

    @Test
    void lifetimeResetsToOneNotZero() {
        // Zero would make a caller that forgot to set it expire the particle immediately -- correct
        // behaviour, but a missing particle is much harder to debug than a brief flash.
        ParticlePool pool = new ParticlePool(1);

        int slot = pool.acquire();
        pool.set(slot, ParticlePool.LIFETIME, 200.0f);
        pool.release(slot);

        assertEquals(1.0f, pool.get(pool.acquire(), ParticlePool.LIFETIME));
    }

    @Test
    void exhaustionIsReportedAndRecovers() {
        ParticlePool pool = new ParticlePool(4);

        for (int i = 0; i < 4; i++) {
            pool.acquire();
        }
        assertEquals(-1, pool.acquire());
        assertEquals(-1, pool.acquire());
        assertEquals(2L, pool.refused());

        pool.release(2);
        assertEquals(2, pool.acquire(), "and it serves again as soon as one comes back");
        assertEquals(0L, pool.refused() - 2);
    }

    @Test
    void fieldAccessIsBoundsChecked() {
        ParticlePool pool = new ParticlePool(4);
        int slot = pool.acquire();

        assertThrows(IndexOutOfBoundsException.class, () -> pool.get(slot, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> pool.get(slot, ParticlePool.FIELDS));
        assertThrows(IndexOutOfBoundsException.class, () -> pool.get(99, 0));
    }

    @Test
    void fieldIndicesDoNotOverlap() {
        // Eight fields, eight distinct indices. A duplicated constant would mean two logical fields
        // writing the same storage, so setting velocity would move the particle's position.
        boolean[] used = new boolean[ParticlePool.FIELDS];
        for (int f : new int[] {
            ParticlePool.POSITION_X, ParticlePool.POSITION_Y, ParticlePool.POSITION_Z,
            ParticlePool.VELOCITY_X, ParticlePool.VELOCITY_Y, ParticlePool.VELOCITY_Z,
            ParticlePool.AGE, ParticlePool.LIFETIME
        }) {
            assertFalse(used[f], "field index " + f + " is used twice");
            used[f] = true;
        }
        assertEquals(8, ParticlePool.FIELDS);
    }

    @Test
    void aPoolOfZeroIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ParticlePool(0));
        assertThrows(IllegalArgumentException.class, () -> new ParticlePool(-1));
    }

    @Test
    void aFreshPoolHasAllocatedNothingToSaveYet() {
        ParticlePool pool = new ParticlePool(100);

        assertEquals(0, pool.inUse());
        assertEquals(100, pool.capacity());
        assertEquals(0L, pool.bytesAvoided(64), "nothing has been reused yet");
    }
}
