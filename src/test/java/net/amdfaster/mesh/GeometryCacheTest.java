package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryCacheTest {

    @Test
    void aMissThenAHitForTheSameSection() {
        // The case the cache exists for: a light change asks for geometry that did not change, and the
        // answer has to be the mesh that is already built rather than a rebuild.
        GeometryCache<String> cache = GeometryCache.withDefaultCapacity();
        assertNull(cache.get(1, 4, 1));
        assertEquals(1, cache.misses(), "first ask is a miss");

        cache.put(1, 4, 1, "mesh");
        assertSame("mesh", cache.get(1, 4, 1));
        assertEquals(1, cache.hits());
        assertEquals(0.5, cache.hitRate(), 1e-9, "one hit out of two lookups");
        assertTrue(cache.contains(1, 4, 1));
    }

    @Test
    void evictionTakesTheLeastRecentlyUsedNotTheOldest() {
        // Insertion order would be wrong here. A section the player keeps looking at is used every
        // frame, and it is exactly the one that must not be dropped -- evicting it means rebuilding the
        // chunk in front of the camera, which is the most visible rebuild there is.
        GeometryCache<String> cache = new GeometryCache<>(3);
        cache.put(0, 4, 0, "a");
        cache.put(1, 4, 0, "b");
        cache.put(2, 4, 0, "c");
        assertEquals(3, cache.size());

        assertSame("a", cache.get(0, 4, 0), "touching a promotes it");
        cache.put(3, 4, 0, "d");

        assertEquals(3, cache.size(), "still at capacity");
        assertFalse(cache.contains(1, 4, 0), "b was the least recently used and went");
        assertTrue(cache.contains(0, 4, 0), "a survived because it was read");
        assertTrue(cache.contains(2, 4, 0));
        assertTrue(cache.contains(3, 4, 0));
        assertEquals(1, cache.evictions());
    }

    @Test
    void capacityIsHeldUnderPressure() {
        GeometryCache<String> cache = new GeometryCache<>(8);
        for (int i = 0; i < 100; i++) {
            cache.put(i, 4, 0, "mesh" + i);
        }
        assertEquals(8, cache.size(), "never exceeds capacity");
        assertEquals(92, cache.evictions(), "100 stored, 8 kept");
        assertTrue(cache.contains(99, 4, 0), "the most recent survived");
        assertFalse(cache.contains(0, 4, 0), "the oldest did not");
    }

    @Test
    void invalidatingOneSectionLeavesTheRest() {
        GeometryCache<String> cache = GeometryCache.withDefaultCapacity();
        cache.put(1, 4, 1, "a");
        cache.put(2, 4, 1, "b");

        assertTrue(cache.invalidateSection(1, 4, 1));
        assertFalse(cache.contains(1, 4, 1));
        assertTrue(cache.contains(2, 4, 1), "its neighbour is untouched");
        assertEquals(1, cache.invalidations());

        assertFalse(cache.invalidateSection(1, 4, 1), "nothing left to invalidate");
        assertEquals(1, cache.invalidations(), "and it is not counted again");
    }

    @Test
    void invalidatingAColumnDropsEveryHeight() {
        // Light spreads vertically without bound: a torch at the bottom of a shaft lights sections all
        // the way up it, and each has stale baked light even though none of their blocks moved. Dropping
        // only the section the torch is in would leave the shaft lit wrongly above it.
        GeometryCache<String> cache = GeometryCache.withDefaultCapacity();
        for (int y = 0; y < 8; y++) {
            cache.put(3, y, 3, "mesh" + y);
        }
        cache.put(4, 0, 3, "other column");
        cache.put(3, 0, 4, "other column too");
        assertEquals(10, cache.size());

        assertEquals(8, cache.invalidateColumn(3, 3), "the whole column");
        assertEquals(2, cache.size(), "the two neighbouring columns survived");
        assertTrue(cache.contains(4, 0, 3));
        assertTrue(cache.contains(3, 0, 4));
        assertFalse(cache.contains(3, 4, 3));
    }

    @Test
    void sectionCoordinatesAreNotConfusedAcrossAxes() {
        // A key built by swapping two axes would alias (1,2,3) with (3,2,1), and the cache would hand
        // back the wrong section's mesh -- the right type, the right size, and completely wrong.
        GeometryCache<String> cache = GeometryCache.withDefaultCapacity();
        cache.put(1, 2, 3, "one-two-three");
        cache.put(3, 2, 1, "three-two-one");

        assertEquals("one-two-three", cache.get(1, 2, 3));
        assertEquals("three-two-one", cache.get(3, 2, 1));
        assertEquals(2, cache.size(), "two distinct entries, not one overwritten");
    }

    @Test
    void negativeSectionCoordinatesWork() {
        GeometryCache<String> cache = GeometryCache.withDefaultCapacity();
        cache.put(-1, -2, -3, "negative");
        assertEquals("negative", cache.get(-1, -2, -3));
        assertNull(cache.get(-3, -2, -1), "and it does not alias the reversed coordinates");
        assertTrue(cache.invalidateSection(-1, -2, -3));
        assertFalse(cache.contains(-1, -2, -3));
    }

    @Test
    void clearEmptiesTheCacheButKeepsTheRunTotals() {
        GeometryCache<String> cache = new GeometryCache<>(4);
        cache.put(0, 4, 0, "a");
        cache.get(0, 4, 0);
        cache.get(9, 4, 9);

        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get(0, 4, 0));
        assertEquals(1, cache.hits(), "the session's totals survive a clear");
        assertEquals(2, cache.misses());
    }

    @Test
    void anEmptyCacheReportsNoHitRate() {
        GeometryCache<String> cache = GeometryCache.withDefaultCapacity();
        assertEquals(0.0, cache.hitRate(), 1e-9, "no lookups, no rate -- not a divide by zero");
        assertEquals(GeometryCache.DEFAULT_CAPACITY, cache.capacity());
        assertThrows(IllegalArgumentException.class, () -> new GeometryCache<String>(0));
    }
}
