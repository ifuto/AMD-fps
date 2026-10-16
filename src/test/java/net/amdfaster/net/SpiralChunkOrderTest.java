package net.amdfaster.net;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpiralChunkOrderTest {

    /** Unpacks an order into {@code x * 1000 + z} keys, so duplicates and gaps can be found. */
    private static Set<Integer> keys(int[] order) {
        Set<Integer> keys = new HashSet<>();
        for (int i = 0; i < order.length; i += 2) {
            keys.add(order[i] * 1000 + order[i + 1]);
        }
        return keys;
    }

    @Test
    void theCentreIsLoadedFirst() {
        // Scanning the area in a fixed direction fills the far corner first and the ground under the player
        // last, so the player stands in a hole looking at a world complete everywhere except where they are.
        SpiralChunkOrder order = new SpiralChunkOrder();

        int[] chunks = order.order(8);

        assertEquals(0, chunks[0]);
        assertEquals(0, chunks[1], "the chunk the player is standing on");
    }

    @Test
    void everyChunkAppearsExactlyOnce() {
        // A spiral that skips a column or visits one twice is not a slower load, it is a broken one: a
        // missing chunk is a hole that never fills, and the player would have to walk away and back.
        for (int radius = 0; radius <= 12; radius++) {
            SpiralChunkOrder order = new SpiralChunkOrder();

            int[] chunks = order.order(radius);
            int side = 2 * radius + 1;

            assertEquals(side * side * 2, chunks.length, "radius " + radius);
            assertEquals(side * side, keys(chunks).size(),
                    "radius " + radius + ": no duplicates and no gaps");
        }
    }

    @Test
    void theOrderWorksOutwardFromTheCentre() {
        SpiralChunkOrder order = new SpiralChunkOrder();

        int[] chunks = order.order(8);

        int previous = 0;
        for (int i = 0; i < chunks.length; i += 2) {
            int distance = Math.max(Math.abs(chunks[i]), Math.abs(chunks[i + 1]));
            assertTrue(distance >= previous,
                    "distance never decreases: " + previous + " then " + distance);
            previous = distance;
        }
        assertEquals(8, previous, "and the last chunk is on the outer ring");
    }

    @Test
    void theSameRadiusGivesTheSameOrder() {
        // If the order depended on which worker finished first, the same world would load in a different
        // pattern each launch, which makes a loading bug unreproducible -- the worst thing a load-order bug
        // can be.
        SpiralChunkOrder first = new SpiralChunkOrder();
        SpiralChunkOrder second = new SpiralChunkOrder();

        int[] a = first.order(8);
        int[] b = second.order(8);

        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i], "index " + i);
        }
    }

    @Test
    void distanceIsMeasuredFromTheCentre() {
        SpiralChunkOrder order = new SpiralChunkOrder();
        order.order(8);

        assertEquals(0, order.distance(0, 0));
        assertEquals(3, order.distance(3, -2), "the larger of the two, so a ring is a square");
        assertEquals(8, order.distance(-8, 5));
    }

    @Test
    void theGroundUnderThePlayerIsReadyAlmostImmediately() {
        SpiralChunkOrder order = new SpiralChunkOrder();
        order.order(8);

        assertEquals(25.0, order.msBeforeCentreReady(), 1e-9);
        assertEquals(3625.0, order.msBeforeCentreReadyRowByRow(8), 1e-9,
                "145 chunks loaded before the centre, out of 289");
        assertEquals(99.31, order.savedPercent(8), 0.01);
    }

    @Test
    void aZeroRadiusIsJustTheCentre() {
        SpiralChunkOrder order = new SpiralChunkOrder();

        int[] chunks = order.order(0);

        assertEquals(2, chunks.length);
        assertEquals(0, chunks[0]);
        assertEquals(0, chunks[1]);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        SpiralChunkOrder order = new SpiralChunkOrder();

        assertThrows(IllegalArgumentException.class, () -> order.order(-1));
    }

    @Test
    void aFreshOrderReportsZeroNotNaN() {
        // A scheduler that has loaded nothing has technically made the player wait for nothing, but
        // reporting that as a saving would describe an unused one as though it had done something.
        SpiralChunkOrder order = new SpiralChunkOrder();

        assertEquals(0.0, order.savedPercent(8));
        assertEquals(0L, order.computed());
        assertEquals(0, order.centreX());
        assertEquals(0, order.centreZ());
        assertEquals(25.0, SpiralChunkOrder.MS_PER_CHUNK, 1e-12);
    }
}
