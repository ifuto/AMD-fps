package net.amdfaster.collision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityPairDeduplicatorTest {

    @Test
    void theSamePairIsExaminedOnceFromEitherSide() {
        // Both entities in a pair ask who they are touching, so the pair is examined twice and the second
        // examination finds the same overlap the first already resolved.
        EntityPairDeduplicator deduplicator = new EntityPairDeduplicator();
        deduplicator.beginTick();

        assertTrue(deduplicator.shouldExamine(1, 2));
        assertFalse(deduplicator.shouldExamine(2, 1), "the same pair from the other side");

        assertEquals(1L, deduplicator.examined());
        assertEquals(1L, deduplicator.duplicates());
    }

    @Test
    void theKeyDoesNotDependOnWhichEntityAsked() {
        // Which one is considered first decides who gets pushed which way, and if that depended on the
        // iteration order the same crowd would settle differently each tick and visibly jitter rather than
        // pack down.
        assertEquals(EntityPairDeduplicator.pairKey(1, 2),
                EntityPairDeduplicator.pairKey(2, 1));
        assertEquals(EntityPairDeduplicator.pairKey(7, 3),
                EntityPairDeduplicator.pairKey(3, 7));
    }

    @Test
    void differentPairsAreBothExamined() {
        EntityPairDeduplicator deduplicator = new EntityPairDeduplicator();
        deduplicator.beginTick();

        assertTrue(deduplicator.shouldExamine(1, 2));
        assertTrue(deduplicator.shouldExamine(1, 3));
        assertTrue(deduplicator.shouldExamine(2, 3));

        assertEquals(3L, deduplicator.examined());
        assertEquals(0L, deduplicator.duplicates());
    }

    @Test
    void aNewTickExaminesThePairsAgain() {
        // The overlap between two entities is resolved within a tick, so the next tick has to check again --
        // they may have moved together.
        EntityPairDeduplicator deduplicator = new EntityPairDeduplicator();

        deduplicator.beginTick();
        assertTrue(deduplicator.shouldExamine(1, 2));

        deduplicator.beginTick();
        assertTrue(deduplicator.shouldExamine(1, 2));

        assertEquals(2L, deduplicator.examined());
    }

    @Test
    void aBatchIsDeduplicated() {
        EntityPairDeduplicator deduplicator = new EntityPairDeduplicator();
        deduplicator.beginTick();

        int distinct = deduplicator.examineAll(new int[] {1, 2, 2, 1, 1, 3, 3, 1, 2, 3});

        assertEquals(3, distinct);
        assertEquals(3L, deduplicator.examined());
        assertEquals(2L, deduplicator.duplicates());
    }

    @Test
    void aCrowdedFarmHalvesTheWork() {
        // In a mob farm where a hundred entities are packed into a few blocks, the number of pairs is in the
        // thousands and every one of them is looked at twice.
        EntityPairDeduplicator deduplicator = new EntityPairDeduplicator();
        deduplicator.beginTick();

        for (int a = 0; a < 20; a++) {
            for (int b = a + 1; b < 20; b++) {
                deduplicator.shouldExamine(a, b);
                deduplicator.shouldExamine(b, a);
            }
        }

        assertEquals(380L, deduplicator.offered());
        assertEquals(190L, deduplicator.examined());
        assertEquals(50.0, deduplicator.duplicatesPercent(), 1e-9);
        assertEquals(190 * EntityPairDeduplicator.MS_PER_PAIR, deduplicator.msExamining(), 1e-9);
        assertEquals(50.0, deduplicator.savedPercent(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        EntityPairDeduplicator deduplicator = new EntityPairDeduplicator();

        assertThrows(IllegalArgumentException.class,
                () -> deduplicator.examineAll(new int[] {1, 2, 3}));
    }

    @Test
    void aFreshDeduplicatorReportsZeroNotNaN() {
        // A deduplicator that has seen nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        EntityPairDeduplicator deduplicator = new EntityPairDeduplicator();

        assertEquals(0.0, deduplicator.duplicatesPercent());
        assertEquals(0.0, deduplicator.savedPercent());
        assertEquals(0.0, deduplicator.msExamining(), 1e-12);
        assertEquals(0L, deduplicator.offered());
        assertEquals(0L, deduplicator.examined());
        assertEquals(0.0008, EntityPairDeduplicator.MS_PER_PAIR, 1e-12);
    }
}
