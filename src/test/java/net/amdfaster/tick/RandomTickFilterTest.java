package net.amdfaster.tick;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomTickFilterTest {

    @Test
    void anUnreportedSectionIsSkipped() {
        // The default that makes the whole thing work. A chunk with nothing tickable placed in it
        // since it loaded has none, and 80 to 95 percent of sections are in this state.
        RandomTickFilter filter = new RandomTickFilter();

        assertFalse(filter.shouldSample(0, 4, 0));
        assertEquals(1L, filter.samplesRequested());
        assertEquals(1L, filter.samplesSkipped());
        assertEquals(100.0, filter.skipRatePercent(), 0.0);
    }

    @Test
    void aSectionWithATickableBlockIsSampled() {
        RandomTickFilter filter = new RandomTickFilter();
        filter.onBlockChanged(5, 70, 5, false, true);

        assertTrue(filter.shouldSample(0, 4, 0), "block at 5,70,5 is in section 0,4,0");
        assertEquals(1, filter.tickableCount(0, 4, 0));
        assertEquals(0L, filter.samplesSkipped());
    }

    @Test
    void blocksInOtherSectionsDoNotLeak() {
        RandomTickFilter filter = new RandomTickFilter();
        filter.onBlockChanged(5, 70, 5, false, true);

        assertTrue(filter.shouldSample(0, 4, 0));
        assertFalse(filter.shouldSample(1, 4, 0), "x 16 and up is the next section");
        assertFalse(filter.shouldSample(0, 5, 0), "y 80 and up is the one above");
        assertFalse(filter.shouldSample(0, 4, 1));
        assertFalse(filter.shouldSample(-1, 4, 0), "and the one to the west");
    }

    @Test
    void removingTheLastTickableBlockStopsTheSampling() {
        RandomTickFilter filter = new RandomTickFilter();
        filter.onBlockChanged(0, 64, 0, false, true);
        assertTrue(filter.shouldSample(0, 4, 0));

        filter.onBlockChanged(0, 64, 0, true, false);
        assertFalse(filter.shouldSample(0, 4, 0), "and the entry is dropped rather than left at zero");
        assertEquals(0, filter.trackedSections());
    }

    @Test
    void aSectionStaysTrackedWhileAnyBlockTicks() {
        RandomTickFilter filter = new RandomTickFilter();
        filter.onBlockChanged(0, 64, 0, false, true);
        filter.onBlockChanged(1, 64, 0, false, true);
        filter.onBlockChanged(2, 64, 0, false, true);
        assertEquals(3, filter.tickableCount(0, 4, 0));

        filter.onBlockChanged(1, 64, 0, true, false);
        assertEquals(2, filter.tickableCount(0, 4, 0));
        assertTrue(filter.shouldSample(0, 4, 0), "two left, so it still samples");

        filter.onBlockChanged(0, 64, 0, true, false);
        filter.onBlockChanged(2, 64, 0, true, false);
        assertFalse(filter.shouldSample(0, 4, 0), "and only stops at zero");
    }

    @Test
    void aChangeBetweenTwoNonTickableBlocksDoesNothing() {
        RandomTickFilter filter = new RandomTickFilter();
        filter.onBlockChanged(0, 64, 0, false, false);
        filter.onBlockChanged(0, 64, 0, true, true);

        assertEquals(0, filter.trackedSections(), "no entry is created for a section with nothing in it");
        assertFalse(filter.shouldSample(0, 4, 0));
    }

    @Test
    void countsStayCorrectUnderRandomChurn() {
        // The invariant that matters. A count that drifts high only loses the optimisation, but one
        // that drifts low stops blocks ticking -- grass does not spread, crops do not grow -- and
        // nothing announces it. So it is checked against a reference on every checkpoint.
        RandomTickFilter filter = new RandomTickFilter();
        Map<Long, Integer> reference = new HashMap<>();
        // State per actual block position. An earlier version used a boolean[8][8][8] indexed by the
        // low three bits while drawing coordinates up to 127, so "was" came back from whichever
        // other block shared those bits and the filter was told about changes that never happened.
        Map<Long, Boolean> blockState = new HashMap<>();
        Random random = new Random(20261013L);

        for (int step = 0; step < 20000; step++) {
            int x = random.nextInt(128);
            int y = random.nextInt(128);
            int z = random.nextInt(128);
            boolean now = random.nextBoolean();
            long blockKey = ((long) x << 42) | ((long) y << 21) | z;
            boolean was = blockState.getOrDefault(blockKey, false);
            blockState.put(blockKey, now);
            filter.onBlockChanged(x, y, z, was, now);

            // The reference has to apply the same rule the filter does: a block that did not change
            // contributes nothing. Applying a delta unconditionally here made the reference drift by
            // one on every no-op, which is the filter being right and the test being wrong.
            if (was != now) {
                long key = RandomTickFilter.sectionKey(x >> 4, y >> 4, z >> 4);
                reference.merge(key, now ? 1 : -1, Integer::sum);
                if (reference.get(key) == 0) {
                    reference.remove(key);
                }
            }

            if (step % 2000 == 0) {
                assertEquals(reference.size(), filter.trackedSections(), "sections at step " + step);
                for (long tracked : reference.keySet()) {
                    int sx = (int) (tracked >> 42);
                    int sy = (int) ((tracked >> 21) & 0x1FFFFF);
                    int sz = (int) (tracked & 0x1FFFFF);
                    assertTrue(filter.shouldSample(sx, sy, sz),
                            "section " + sx + "," + sy + "," + sz + " lost at step " + step);
                }
            }
        }
        assertEquals(reference.size(), filter.trackedSections());
    }

    @Test
    void growthKeepsEveryTrackedSection() {
        RandomTickFilter filter = new RandomTickFilter();
        for (int i = 0; i < 20000; i++) {
            filter.onBlockChanged(i, 64, i * 7, false, true);
        }

        assertTrue(filter.trackedSections() > 1000, "got " + filter.trackedSections());
        for (int i = 0; i < 20000; i += 997) {
            assertTrue(filter.shouldSample(i >> 4, 4, (i * 7) >> 4), "position " + i);
        }
    }

    @Test
    void sectionKeyIsInjective() {
        java.util.Set<Long> keys = new java.util.HashSet<>();
        int[][] points = {{0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {-1, 0, 0}, {0, -1, 0},
            {0, 0, -1}, {100, -50, 25}, {-100, 50, -25}};
        for (int[] point : points) {
            keys.add(RandomTickFilter.sectionKey(point[0], point[1], point[2]));
        }
        assertEquals(points.length, keys.size());
    }

    @Test
    void blockCoordinatesMapToTheRightSection() {
        // 5 >> 4 is 0 and 16 >> 4 is 1. Getting this wrong puts every block in section zero and the
        // filter looks like it is working perfectly while skipping everything.
        assertEquals(0, 5 >> 4);
        assertEquals(1, 16 >> 4);
        assertEquals(-1, -1 >> 4, "arithmetic shift keeps the sign, which is what section math wants");
    }

    @Test
    void aFreshFilterReportsZeroNotNaN() {
        RandomTickFilter filter = new RandomTickFilter();
        assertEquals(0.0, filter.skipRatePercent());
        assertEquals(0L, filter.samplesRequested());
    }

    @Test
    void countersResetWithoutLosingTheWorld() {
        RandomTickFilter filter = new RandomTickFilter();
        filter.onBlockChanged(0, 64, 0, false, true);
        filter.shouldSample(0, 4, 0);
        filter.shouldSample(9, 9, 9);
        assertEquals(1L, filter.samplesSkipped());

        filter.resetCounters();
        assertEquals(0L, filter.samplesSkipped());
        assertEquals(1, filter.trackedSections(), "and the tracked sections survive");
        assertTrue(filter.shouldSample(0, 4, 0));
    }

    @Test
    void theCostOfSkippingIsDocumentedInNumbers() {
        // Render distance 10 is 441 chunks, 24 sections each, three draws a tick, twenty ticks a
        // second. Pinned so the claim in the class comment can be checked against it.
        int chunks = 21 * 21;
        int sections = chunks * 24;
        assertEquals(10584, sections);
        assertEquals(31752, sections * RandomTickFilter.SAMPLES_PER_SECTION_PER_TICK);
        assertEquals(635040, sections * RandomTickFilter.SAMPLES_PER_SECTION_PER_TICK * 20);
        assertEquals(4096, RandomTickFilter.BLOCKS_PER_SECTION);
    }
}
