package net.amdfaster.draw;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SortKeyRadixSortTest {

    @Test
    void itAgreesWithADifferentAlgorithmOnRandomInput() {
        // Checked against an insertion sort rather than against a second radix implementation, so a
        // shared bug in the passes cannot agree with itself.
        Random random = new Random(20261014L);
        SortKeyRadixSort sorter = new SortKeyRadixSort();

        for (int trial = 0; trial < 200; trial++) {
            int n = 1 + random.nextInt(300);
            long[] keys = new long[n];
            int[] payload = new int[n];
            for (int i = 0; i < n; i++) {
                keys[i] = random.nextLong();
                payload[i] = i;
            }
            long[] wantKeys = keys.clone();
            int[] wantPayload = payload.clone();
            SortKeyRadixSort.referenceSort(wantKeys, wantPayload, n);

            sorter.sort(keys, payload);

            assertArrayEquals(wantKeys, keys, "trial " + trial + " keys");
            assertArrayEquals(wantPayload, payload, "trial " + trial + " payload");
            assertTrue(SortKeyRadixSort.isSortedUnsigned(keys, n), "trial " + trial + " order");
        }
        assertEquals(200L, sorter.sorts());
    }

    @Test
    void theSignBitDoesNotMoveAKeyToTheFront() {
        // The reason this class exists. SortKey puts the bucket in the top eight bits and is compared
        // unsigned, so a bucket at or above 128 sets the sign bit. Arrays.sort(long[]) sorts signed and
        // would draw every one of those batches first.
        long[] keys = {
            (1L << 63) | 5L,
            3L,
            (1L << 63) | 1L,
            2L,
        };
        int[] payload = {0, 1, 2, 3};

        new SortKeyRadixSort().sort(keys, payload);

        assertEquals(2L, keys[0]);
        assertEquals(3L, keys[1]);
        assertEquals((1L << 63) | 1L, keys[2], "the sign bit is just another bit");
        assertEquals((1L << 63) | 5L, keys[3]);
        assertTrue(Long.compareUnsigned(keys[1], keys[2]) < 0);
        assertTrue(keys[1] < keys[2] == false, "and signed comparison would say the opposite");
    }

    @Test
    void equalKeysKeepTheirInputOrder() {
        // Two batches with the same bucket, depth and id are drawn in submission order. A sort that
        // reordered them would make the frame flicker between two equally valid orders.
        long[] keys = {7L, 7L, 7L, 1L, 7L, 1L};
        int[] payload = {0, 1, 2, 3, 4, 5};

        new SortKeyRadixSort().sort(keys, payload);

        assertArrayEquals(new long[] {1L, 1L, 7L, 7L, 7L, 7L}, keys);
        assertArrayEquals(new int[] {3, 5, 0, 1, 2, 4}, payload, "each equal group stays in order");
    }

    @Test
    void thePayloadTravelsWithItsKey() {
        long[] keys = {30L, 10L, 20L};
        int[] payload = {300, 100, 200};

        new SortKeyRadixSort().sort(keys, payload);

        assertArrayEquals(new long[] {10L, 20L, 30L}, keys);
        assertArrayEquals(new int[] {100, 200, 300}, payload);
    }

    @Test
    void aPartialCountLeavesTheRestAlone() {
        long[] keys = {5L, 3L, 1L, 99L, 98L};
        int[] payload = {0, 1, 2, 3, 4};

        new SortKeyRadixSort().sort(keys, payload, 3);

        assertArrayEquals(new long[] {1L, 3L, 5L, 99L, 98L}, keys);
        assertArrayEquals(new int[] {2, 1, 0, 3, 4}, payload);
    }

    @Test
    void degenerateInputsAreHandled() {
        SortKeyRadixSort sorter = new SortKeyRadixSort();

        long[] empty = {};
        int[] emptyPayload = {};
        sorter.sort(empty, emptyPayload);
        assertEquals(0, empty.length);

        long[] one = {42L};
        int[] onePayload = {7};
        sorter.sort(one, onePayload);
        assertEquals(42L, one[0]);
        assertEquals(7, onePayload[0]);

        long[] none = {5L, 5L};
        int[] nonePayload = {1, 0};
        sorter.sort(none, nonePayload, 0);
        assertEquals(5L, none[0], "count 0 sorts nothing");
        assertEquals(1, nonePayload[0]);

        assertEquals(3L, sorter.sorts());
        assertEquals(0L, sorter.passesRun(), "and none of them ran any passes");
    }

    @Test
    void alreadySortedReversedAndUniformInputsAllWork() {
        SortKeyRadixSort sorter = new SortKeyRadixSort();
        int n = 512;

        long[] ascending = new long[n];
        long[] descending = new long[n];
        long[] uniform = new long[n];
        for (int i = 0; i < n; i++) {
            ascending[i] = i;
            descending[i] = n - i;
            uniform[i] = 9L;
        }
        int[] payload = new int[n];
        for (int i = 0; i < n; i++) {
            payload[i] = i;
        }

        sorter.sort(ascending, payload.clone());
        assertTrue(SortKeyRadixSort.isSortedUnsigned(ascending, n));

        sorter.sort(descending, payload.clone());
        assertTrue(SortKeyRadixSort.isSortedUnsigned(descending, n));
        assertEquals(1L, descending[0]);

        int[] uniformPayload = payload.clone();
        sorter.sort(uniform, uniformPayload);
        for (int i = 0; i < n; i++) {
            assertEquals(i, uniformPayload[i], "all keys equal, so nothing moves");
        }
    }

    @Test
    void keysDifferingOnlyInOneByteAreSeparated() {
        // Each pass looks at one byte, so a pair differing only in the top byte is not separated until
        // the last pass. Worth a case of its own because it is the pass that is easiest to get wrong.
        long[] keys = {0x0100000000000000L, 0x0000000000000001L, 0xFF00000000000000L};
        int[] payload = {0, 1, 2};

        new SortKeyRadixSort().sort(keys, payload);

        assertArrayEquals(new long[] {1L, 0x0100000000000000L, 0xFF00000000000000L}, keys);
        assertArrayEquals(new int[] {1, 0, 2}, payload);
    }

    @Test
    void aLargeSortStillAgreesWithTheReference() {
        // Big enough that the scratch buffer has to be grown and the passes have to move real data.
        Random random = new Random(99L);
        int n = 20000;
        long[] keys = new long[n];
        int[] payload = new int[n];
        for (int i = 0; i < n; i++) {
            keys[i] = random.nextLong();
            payload[i] = i;
        }
        long[] wantKeys = keys.clone();
        int[] wantPayload = payload.clone();
        Arrays.sort(wantKeys);
        // Arrays.sort is signed, so compare only the multiset, not the order.
        SortKeyRadixSort sorter = new SortKeyRadixSort();
        sorter.sort(keys, payload);

        assertTrue(SortKeyRadixSort.isSortedUnsigned(keys, n));
        long[] sortedSigned = keys.clone();
        Arrays.sort(sortedSigned);
        long[] wantSorted = wantKeys.clone();
        Arrays.sort(wantSorted);
        assertArrayEquals(wantSorted, sortedSigned, "the same multiset came out");
        assertEquals(20000L, sorter.elements());
        assertEquals(8L * 1L, sorter.passesRun() / sorter.sorts(), "eight passes per sort");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        SortKeyRadixSort sorter = new SortKeyRadixSort();

        assertThrows(IllegalArgumentException.class,
                () -> sorter.sort(new long[4], new int[3]));
        assertThrows(IllegalArgumentException.class,
                () -> sorter.sort(new long[4], new int[4], 5));
        assertThrows(IllegalArgumentException.class,
                () -> sorter.sort(new long[4], new int[4], -1));
    }

    @Test
    void theReferenceAgreesWithUnsignedOrderOnItsOwnTerms() {
        // Guarding the guard. If the reference sorted signed, every differential test above would be
        // checking the wrong thing.
        long[] keys = {(1L << 63) | 2L, 1L};
        int[] payload = {0, 1};

        SortKeyRadixSort.referenceSort(keys, payload, 2);

        assertEquals(1L, keys[0], "unsigned puts the positive key first");
        assertEquals((1L << 63) | 2L, keys[1]);
        assertFalse(Arrays.equals(new long[] {1L, (1L << 63) | 2L}, new long[] {(1L << 63) | 2L, 1L}),
                "which is the opposite of what a signed sort produces");
    }
}
