package net.amdfaster.explosion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlastDamageBufferTest {

    private static long at(int x, int y, int z) {
        return ExplosionBlockCache.pack(x, y, z);
    }

    @Test
    void aBlastsListIsTenTimesSmallerThanTheBoxedPath() {
        // 823 distinct blocks a blast, at eighty bytes boxed in a hash set against eight packed in a
        // flat array. The saving is not the memory, which is transient either way -- it is the
        // collector's work, which lands on the thread the explosion runs on.
        assertEquals(823, BlastDamageBuffer.BLOCKS_PER_BLAST);
        assertEquals(80, BlastDamageBuffer.VANILLA_BYTES_PER_ENTRY);
        assertEquals(8, BlastDamageBuffer.PACKED_BYTES_PER_ENTRY);

        BlastDamageBuffer buffer = new BlastDamageBuffer();
        buffer.clear();
        for (int i = 0; i < BlastDamageBuffer.BLOCKS_PER_BLAST; i++) {
            buffer.add(at(i % 32 - 16, 64 + i / 32, i / 1024));
        }

        assertEquals(823, buffer.size());
        assertEquals(823 * 8, buffer.bytesUsed());
        assertEquals(823 * 80, buffer.bytesVanilla());
        assertEquals(6584, buffer.bytesUsed());
        assertEquals(65840, buffer.bytesVanilla());
    }

    @Test
    void aHundredBlastChainAllocatesOnceRatherThanAHundredTimes() {
        // The array survives clear and is only grown, so the first blast sizes it and the rest reuse it.
        // Shrinking on clear would reallocate on the next add and give back the only thing this saves.
        BlastDamageBuffer buffer = new BlastDamageBuffer();

        for (int blast = 0; blast < 100; blast++) {
            buffer.clear();
            for (int i = 0; i < 900; i++) {
                buffer.add(at(i % 32 - 16, 64 + i / 32, blast));
            }
        }

        assertEquals(100L, buffer.blasts());
        // One growth, on the first blast: 900 positions cross the three quarter load mark of the
        // initial 1024, and at 2048 the same 900 never crosses it again. Ninety-nine blasts allocate
        // nothing, which is the whole point of keeping the array.
        assertEquals(1L, buffer.growths(), "grown once on the first blast and never again");
    }

    @Test
    void theSameBlockIsRecordedOnceHoweverManyRaysCrossIt() {
        // Two rays cross the same block constantly. A list that allowed duplicates would destroy it
        // twice, and the second destruction would find air -- harmless for blocks, but it spawns item
        // entities for nothing, and item entities are the expensive half of an explosion.
        BlastDamageBuffer buffer = new BlastDamageBuffer();
        buffer.clear();

        assertTrue(buffer.add(at(1, 64, 1)), "first ray to reach it");
        assertFalse(buffer.add(at(1, 64, 1)), "second ray finds it already recorded");
        assertTrue(buffer.add(at(2, 64, 1)), "a different block is new");

        assertEquals(2, buffer.size());
        assertEquals(3L, buffer.adds());
        assertEquals(1L, buffer.duplicates());
    }

    @Test
    void clearEmptiesTheListWithoutGivingUpTheArray() {
        BlastDamageBuffer buffer = new BlastDamageBuffer();
        buffer.clear();
        buffer.add(at(1, 64, 1));
        long[] first = buffer.positions();

        buffer.clear();

        assertEquals(0, buffer.size());
        assertSame(first, buffer.positions(), "the array is reused, not reallocated");
        assertTrue(buffer.add(at(1, 64, 1)), "and it accepts the same position again in the next blast");
    }

    @Test
    void onlyTheFirstSizeEntriesAreLive() {
        // positions() hands back the backing array, because copying it would allocate the thing this
        // class exists not to allocate. The caller has to respect the length.
        BlastDamageBuffer buffer = new BlastDamageBuffer();
        buffer.clear();
        buffer.add(at(1, 64, 1));
        buffer.add(at(2, 64, 1));
        buffer.add(at(3, 64, 1));

        long[] positions = buffer.positions();

        assertTrue(positions.length >= 3);
        assertEquals(at(1, 64, 1), positions[0], "insertion order, so a caller can walk it");
        assertEquals(at(2, 64, 1), positions[1]);
        assertEquals(at(3, 64, 1), positions[2]);
    }

    @Test
    void growingKeepsEveryRecordedPosition() {
        // Growth rehashes the dedup table. A rehash that lost entries would not throw -- it would start
        // accepting duplicates, and the symptom is a block destroyed twice and a stray item entity.
        //
        // This is also the regression test for a hang. The first version checked the capacity AFTER
        // probing, so the add that filled the 1024 slot table succeeded and the next one spun forever
        // looking for a free slot that did not exist -- which is why a build sat in progress rather
        // than failing. 5,000 positions is well past that boundary. Growth now happens before probing,
        // at three quarters full, so open addressing always has somewhere to stop.
        BlastDamageBuffer buffer = new BlastDamageBuffer();
        buffer.clear();
        int total = 5000;

        for (int i = 0; i < total; i++) {
            assertTrue(buffer.add(at(i % 200 - 100, 64 + i / 200, i / 40000)), "position " + i);
        }
        assertEquals(total, buffer.size());
        assertEquals(3L, buffer.growths(), "1024 -> 2048 -> 4096 -> 8192, each at three quarters full");

        for (int i = 0; i < total; i++) {
            assertFalse(buffer.add(at(i % 200 - 100, 64 + i / 200, i / 40000)),
                    "position " + i + " is still recorded after the rehash");
        }
        assertEquals(total, buffer.size(), "nothing was added twice");
        assertEquals((long) total, buffer.duplicates());
    }

    @Test
    void aFreshBufferReportsZeroNotNaN() {
        BlastDamageBuffer buffer = new BlastDamageBuffer();

        assertEquals(0, buffer.size());
        assertEquals(0, buffer.bytesUsed());
        assertEquals(0, buffer.bytesVanilla());
        assertEquals(0L, buffer.blasts());
        assertEquals(0L, buffer.adds());
        assertEquals(0L, buffer.duplicates());
        assertEquals(0L, buffer.growths());
    }
}
