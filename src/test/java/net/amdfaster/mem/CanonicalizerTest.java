package net.amdfaster.mem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalizerTest {

    /** Deduplicates by content, so two instances with the same text are interchangeable. */
    private static final Canonicalizer.Content<String> BY_VALUE = new Canonicalizer.Content<>() {
        @Override
        public int hash(String value) {
            return value.hashCode();
        }

        @Override
        public boolean equal(String a, String b) {
            return a.equals(b);
        }

        @Override
        public int bytes(String value) {
            return 40 + 2 * value.length();
        }
    };

    private static Canonicalizer<String> newCanonicalizer() {
        return new Canonicalizer<>(BY_VALUE);
    }

    @Test
    void anEqualValueReturnsTheInstanceAlreadyStored() {
        Canonicalizer<String> dedup = newCanonicalizer();
        String first = new String("minecraft:oak_planks");
        String second = new String("minecraft:oak_planks");

        assertTrue(first != second, "the test needs two distinct instances of equal content");
        assertSame(first, dedup.canonical(first));
        assertSame(first, dedup.canonical(second), "and the second is folded onto the first");
        assertEquals(1, dedup.size());
    }

    @Test
    void distinctValuesAreKeptApart() {
        Canonicalizer<String> dedup = newCanonicalizer();
        dedup.canonical("minecraft:stone");
        dedup.canonical("minecraft:dirt");
        dedup.canonical("minecraft:oak_planks");

        assertEquals(3, dedup.size());
        assertTrue(dedup.contains("minecraft:dirt"));
        assertTrue(!dedup.contains("minecraft:glass"));
    }

    @Test
    void theSavingsAreReportedInBytesNotJustCounts() {
        // FerriteCore's figures are quoted in megabytes because that is what the heap cares about.
        // A count of duplicates says nothing about whether deduplicating them was worth the table.
        Canonicalizer<String> dedup = newCanonicalizer();
        dedup.canonical("minecraft:oak_planks");
        dedup.canonical("minecraft:oak_planks");
        dedup.canonical("minecraft:oak_planks");

        assertEquals(1, dedup.insertions());
        assertEquals(2, dedup.duplicates());
        assertEquals(2 * (40 + 2 * 20), dedup.bytesSaved(), "two copies of a 20 character value");
    }

    @Test
    void growthPreservesEveryEntry() {
        Canonicalizer<String> dedup = newCanonicalizer();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            String value = "minecraft:block_" + i;
            values.add(value);
            dedup.canonical(value);
        }

        assertEquals(5000, dedup.size(), "the table grew from 16 several times on the way");
        for (String value : values) {
            assertTrue(dedup.contains(value), value);
        }
        assertTrue(!dedup.contains("minecraft:block_5000"));
    }

    @Test
    void removalDoesNotLoseTheEntriesBehindTheHole() {
        // This is the case backshift exists for. Deleting from a linear probing table strands every
        // entry whose probe walked past the deleted slot, and a lookup for them then reports absent.
        Canonicalizer<Integer> dedup = new Canonicalizer<>(new Canonicalizer.Content<>() {
            @Override
            public int hash(Integer value) {
                return value;
            }

            @Override
            public boolean equal(Integer a, Integer b) {
                return a.equals(b);
            }

            @Override
            public int bytes(Integer value) {
                return 16;
            }
        });
        for (int i = 0; i < 200; i++) {
            dedup.canonical(i);
        }
        for (int i = 0; i < 200; i += 2) {
            assertTrue(dedup.remove(i), "value " + i);
        }

        assertEquals(100, dedup.size());
        for (int i = 1; i < 200; i += 2) {
            assertTrue(dedup.contains(i), "odd value " + i + " must survive the deletions around it");
        }
        for (int i = 0; i < 200; i += 2) {
            assertTrue(!dedup.contains(i), "deleted value " + i + " must not come back");
        }
    }

    @Test
    void mixedInsertAndRemoveMatchesAReferenceMap() {
        // The whole table is checked against a HashMap on every checkpoint, because the failure mode
        // is silent: an entry that cannot be found, or one that reappears after being removed.
        Canonicalizer<String> dedup = newCanonicalizer();
        Map<String, Integer> reference = new HashMap<>();
        Random random = new Random(20261012L);
        long inserts = 0;
        long deletes = 0;

        for (int step = 0; step < 20000; step++) {
            String key = "minecraft:block_" + random.nextInt(2000);
            if (random.nextDouble() < 0.6 || !reference.containsKey(key)) {
                reference.put(key, step);
                dedup.canonical(key);
                inserts++;
            } else {
                reference.remove(key);
                assertTrue(dedup.remove(key), "removing " + key + " at step " + step);
                deletes++;
            }
            if (step % 2500 == 0) {
                assertEquals(reference.size(), dedup.size(), "size at step " + step);
                for (String expected : reference.keySet()) {
                    assertTrue(dedup.contains(expected), "missing " + expected + " at step " + step);
                }
            }
        }

        assertEquals(reference.size(), dedup.size());
        assertTrue(inserts > 10000, "the test exercised inserts, got " + inserts);
        assertTrue(deletes > 1000, "and deletes, got " + deletes);
        for (String expected : reference.keySet()) {
            assertNotNull(dedup.canonical(expected), expected);
        }
    }

    @Test
    void removalLeavesNoTombstones() {
        // A tombstoned table would keep every dead slot, so load would track total insertions rather
        // than live entries, and probes would grow for the whole session. Checking the load factor
        // after a churn is the cheap way to catch that regression.
        Canonicalizer<String> dedup = newCanonicalizer();
        for (int round = 0; round < 20; round++) {
            for (int i = 0; i < 500; i++) {
                dedup.canonical("minecraft:temp_" + i);
            }
            for (int i = 0; i < 500; i++) {
                dedup.remove("minecraft:temp_" + i);
            }
        }

        assertEquals(0, dedup.size(), "everything was removed");
        assertTrue(dedup.loadPercent() < 5.0,
                "load should reflect live entries, not the 10000 that were inserted: " + dedup.loadPercent());
        assertTrue(dedup.averageProbes() < 4.0,
                "probes should stay bounded by load factor: " + dedup.averageProbes());
    }

    @Test
    void removingSomethingAbsentIsReportedAndChangesNothing() {
        Canonicalizer<String> dedup = newCanonicalizer();
        dedup.canonical("minecraft:stone");

        assertTrue(!dedup.remove("minecraft:glass"));
        assertEquals(1, dedup.size());
        assertEquals(0, dedup.removals());
    }

    @Test
    void aHashWhoseLowBitsAreIdenticalStillDistributes() {
        // Using the low bits of the content hash directly would pile all of these into one slot and
        // turn the table into a linked list. This is the case the spread step exists for, and it is
        // worth pinning because ordinary string hashes happen to distribute well enough on their own
        // that a test built from them would pass with the spread step removed entirely.
        Set<Integer> naive = new HashSet<>();
        Set<Integer> spread = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            int hash = 0x1000 + i * 65536;
            naive.add(hash & 15);
            spread.add(Canonicalizer.spread(hash) & 15);
        }
        assertEquals(1, naive.size(), "every one of these shares its low sixteen bits");
        assertEquals(14, spread.size(), "and spreading puts them across fourteen of sixteen slots");
    }

    @Test
    void aFreshTableReportsZeroNotNaN() {
        Canonicalizer<String> dedup = newCanonicalizer();
        assertEquals(0.0, dedup.averageProbes(), "no operations yet");
        assertEquals(0.0, dedup.loadPercent());
    }

    @Test
    void countersResetBetweenSessions() {
        Canonicalizer<String> dedup = newCanonicalizer();
        dedup.canonical("minecraft:stone");
        dedup.canonical("minecraft:stone");
        assertEquals(1, dedup.duplicates());

        dedup.resetCounters();
        assertEquals(0, dedup.duplicates());
        assertEquals(0, dedup.insertions());
        assertEquals(1, dedup.size(), "resetting counters must not drop entries");
    }

    @Test
    void aNegativeByteEstimateIsTreatedAsZero() {
        // An unknown retained size must not turn the reported saving negative and hide a real one.
        Canonicalizer<String> dedup = new Canonicalizer<>(new Canonicalizer.Content<>() {
            @Override
            public int hash(String value) {
                return value.hashCode();
            }

            @Override
            public boolean equal(String a, String b) {
                return a.equals(b);
            }

            @Override
            public int bytes(String value) {
                return -1;
            }
        });
        dedup.canonical("minecraft:stone");
        dedup.canonical("minecraft:stone");

        assertEquals(0, dedup.bytesSaved());
        assertEquals(1, dedup.duplicates(), "the duplicate was still found");
    }

    @Test
    void nullIsRejectedRatherThanStored() {
        Canonicalizer<String> dedup = newCanonicalizer();
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> dedup.canonical(null));
        assertEquals(0, dedup.size());
    }
}
