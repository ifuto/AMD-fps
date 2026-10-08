package net.amdfaster.dirty;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scheduler exists so that a storm of block changes costs one rebuild per section instead of one
 * per change, and so that a light-only change never pays for meshing. Those two claims are what the
 * tests below pin, along with the ordering guarantee that stops a drained section being lost.
 */
class RebuildSchedulerTest {

    private static List<String> drained(RebuildScheduler s) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.drainedCount(); i++) {
            out.add((s.drainedIsGeometry(i) ? "G" : "L") + s.drainedX(i) + "," + s.drainedY(i)
                    + "," + s.drainedZ(i));
        }
        return out;
    }

    @Test
    void markingTheSameSectionManyTimesIsOnePendingRebuild() {
        // The whole point. A TNT explosion changes hundreds of blocks in one section per tick; each
        // one marks it dirty, and it must still be built once.
        RebuildScheduler scheduler = new RebuildScheduler();
        for (int i = 0; i < 500; i++) {
            scheduler.markGeometry(3, 4, 5);
        }
        assertEquals(1, scheduler.pending());
        scheduler.drain(0f, 0f, 0f);
        assertEquals(1, scheduler.drainedCount());
        assertEquals(0, scheduler.pending(), "the drained section is no longer pending");
    }

    @Test
    void aGeometryRebuildSubsumesAPendingLightRewrite() {
        // Rebuilding geometry recomputes light, so keeping both would build the section twice and
        // upload the light stream a second time for nothing.
        RebuildScheduler scheduler = new RebuildScheduler();
        scheduler.markLight(1, 2, 3);
        assertEquals(1, scheduler.pendingLight());
        scheduler.markGeometry(1, 2, 3);
        assertEquals(1, scheduler.pending());
        assertEquals(1, scheduler.pendingGeometry());
        assertEquals(0, scheduler.pendingLight());

        scheduler.drain(0f, 0f, 0f);
        assertEquals(1, scheduler.drainedCount());
        assertTrue(scheduler.drainedIsGeometry(0));
    }

    @Test
    void aLightMarkIsIgnoredWhenGeometryIsAlreadyPending() {
        RebuildScheduler scheduler = new RebuildScheduler();
        scheduler.markGeometry(1, 2, 3);
        scheduler.markLight(1, 2, 3);
        assertEquals(1, scheduler.pending());
        assertEquals(0, scheduler.pendingLight());
    }

    @Test
    void lightOnlySectionsStayLightOnly() {
        // The distinction that makes a torch placement cheap: no greedy meshing, no ambient
        // occlusion, and the position, attribute and index buffers are left untouched.
        RebuildScheduler scheduler = new RebuildScheduler();
        scheduler.markLight(7, 4, 9);
        scheduler.drain(0f, 0f, 0f);
        assertEquals(1, scheduler.drainedCount());
        assertFalse(scheduler.drainedIsGeometry(0), "must be drainable as a light-only rewrite");
        assertEquals(7, scheduler.drainedX(0));
        assertEquals(4, scheduler.drainedY(0));
        assertEquals(9, scheduler.drainedZ(0));
    }

    @Test
    void geometryIsDrainedBeforeLightRegardlessOfDistance() {
        // A light rewrite of a distant section is cheaper than a mesh rebuild, but a missing mesh is
        // a hole in the world and a stale lightmap is not, so shape always goes first.
        RebuildScheduler scheduler = new RebuildScheduler();
        scheduler.markLight(0, 0, 0);
        scheduler.markGeometry(40, 40, 40);
        scheduler.drain(0f, 0f, 0f);
        assertEquals(2, scheduler.drainedCount());
        assertTrue(scheduler.drainedIsGeometry(0), "geometry first");
        assertFalse(scheduler.drainedIsGeometry(1));
    }

    @Test
    void nearerSectionsAreDrainedFirstWithinAKind() {
        RebuildScheduler scheduler = new RebuildScheduler();
        scheduler.markGeometry(20, 0, 0);
        scheduler.markGeometry(1, 0, 0);
        scheduler.markGeometry(10, 0, 0);
        scheduler.drain(0f, 0f, 0f);
        assertEquals(List.of("G1,0,0", "G10,0,0", "G20,0,0"), drained(scheduler));
    }

    @Test
    void sectionsBehindTheCameraAreNotPrivileged() {
        // Distance, not signed coordinate, decides the order; a section at x = -20 is as far as one
        // at x = +20 and must not sort ahead of x = +1.
        RebuildScheduler scheduler = new RebuildScheduler();
        scheduler.markGeometry(-20, 0, 0);
        scheduler.markGeometry(1, 0, 0);
        scheduler.drain(0f, 0f, 0f);
        assertEquals("G1,0,0", drained(scheduler).get(0));
    }

    @Test
    void theBudgetIsRespectedAndTheRestStaysPending() {
        RebuildScheduler scheduler = new RebuildScheduler(4, 256f);
        for (int i = 1; i <= 10; i++) {
            scheduler.markGeometry(i, 0, 0);
        }
        assertEquals(10, scheduler.pending());

        scheduler.drain(0f, 0f, 0f);
        assertEquals(4, scheduler.drainedCount(), "budget is four");
        assertEquals(6, scheduler.pending());
        assertEquals(List.of("G1,0,0", "G2,0,0", "G3,0,0", "G4,0,0"), drained(scheduler));

        scheduler.drain(0f, 0f, 0f);
        assertEquals(4, scheduler.drainedCount());
        assertEquals(List.of("G5,0,0", "G6,0,0", "G7,0,0", "G8,0,0"), drained(scheduler));

        scheduler.drain(0f, 0f, 0f);
        assertEquals(2, scheduler.drainedCount(), "only two were left");
        assertEquals(0, scheduler.pending());
    }

    @Test
    void everyMarkedSectionIsEventuallyDrainedExactlyOnce() {
        // A scheduler that loses a section leaves a permanent hole in the world, which is the failure
        // mode that matters most here.
        RebuildScheduler scheduler = new RebuildScheduler(16, 256f);
        Set<String> marked = new HashSet<>();
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                scheduler.markGeometry(x, 4, z);
                marked.add("G" + x + ",4," + z);
            }
        }
        assertEquals(marked.size(), scheduler.pending());

        Set<String> seen = new HashSet<>();
        int guard = 0;
        while (scheduler.pending() > 0 && guard++ < 100) {
            scheduler.drain(0f, 64f, 0f);
            for (String entry : drained(scheduler)) {
                assertTrue(seen.add(entry), "drained twice: " + entry);
            }
        }
        assertEquals(marked, seen, "every marked section was drained exactly once");
    }

    @Test
    void drainingAnEmptySchedulerProducesNothing() {
        RebuildScheduler scheduler = new RebuildScheduler();
        assertEquals(0, scheduler.pending());
        assertEquals(0, scheduler.drain(0f, 0f, 0f));
        assertEquals(0, scheduler.drainedCount());
    }

    @Test
    void clearDropsEverything() {
        RebuildScheduler scheduler = new RebuildScheduler();
        scheduler.markGeometry(1, 2, 3);
        scheduler.markLight(4, 5, 6);
        scheduler.clear();
        assertEquals(0, scheduler.pending());
        assertEquals(0, scheduler.drain(0f, 0f, 0f));
    }

    @Test
    void theConstructorRejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new RebuildScheduler(0, 256f));
        assertThrows(IllegalArgumentException.class, () -> new RebuildScheduler(-1, 256f));
        assertThrows(IllegalArgumentException.class, () -> new RebuildScheduler(8, 0f));
        assertThrows(IllegalArgumentException.class, () -> new RebuildScheduler(8, Float.NaN));
    }

    @Test
    void aPendingSetLargeEnoughToRegrowStillDrainsNearestFirst() {
        // The counting sort keeps the band of each entry in an array parallel to its key. If the two
        // ever drift apart -- on a regrow, which is exactly what 5 000 entries forces several of --
        // sections come out in the wrong priority order. Nothing crashes; the player just gets the
        // far edge of the world rebuilt before the block in front of them.
        //
        // The bands are recomputed here from the drained coordinates using a different expression
        // from the implementation's, so this checks the ordering rather than restating it.
        RebuildScheduler scheduler = new RebuildScheduler(5000, 256f);
        java.util.Random random = new java.util.Random(4242L);
        for (int i = 0; i < 5000; i++) {
            scheduler.markGeometry(random.nextInt(400) - 200, random.nextInt(24) + 2,
                    random.nextInt(400) - 200);
        }
        int marked = scheduler.pending();
        assertTrue(marked > 4000, "the sample should mostly be distinct sections, got " + marked);

        scheduler.drain(0f, 64f, 0f);
        assertEquals(marked, scheduler.drainedCount(), "everything drained in one pass");
        assertEquals(0, scheduler.pending());

        int previousBand = -1;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < scheduler.drainedCount(); i++) {
            float dx = (scheduler.drainedX(i) << 4) + 8.0f;
            float dy = (scheduler.drainedY(i) << 4) + 8.0f - 64.0f;
            float dz = (scheduler.drainedZ(i) << 4) + 8.0f;
            float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            int band = Math.min(31, (int) (distance / 8.0f));
            assertTrue(band >= previousBand,
                    "band went backwards at " + i + ": " + previousBand + " then " + band);
            previousBand = band;
            assertTrue(seen.add(scheduler.drainedX(i) + "," + scheduler.drainedY(i) + ","
                    + scheduler.drainedZ(i)), "a section was drained twice");
        }
        assertEquals(marked, seen.size());
    }

    @Test
    void aStormOfBlockChangesInOneSectionIsOneRebuild() {
        // The TNT case end to end: five hundred blocks destroyed in one section, each marking every
        // section it can invalidate through SectionSet, and the scheduler still hands out one rebuild.
        RebuildScheduler scheduler = new RebuildScheduler();
        SectionSet scratch = new SectionSet();
        for (int i = 0; i < 500; i++) {
            int x = 40 + (i % 8);
            int y = 66 + (i / 8 % 8);
            int z = 40 + (i / 64 % 8);
            scheduler.markBlockChanged(x, y, z, scratch);
        }
        // Measured: 4, not 500 and not 8. The blast spans local 8..15 in x and z, so it reaches the
        // high neighbour on both axes but never the low one, and y stays inside section 4.
        assertEquals(4, scheduler.pending(),
                "500 changed blocks in a 2x1x2 block of sections is four rebuilds");

        scheduler.drain(0f, 64f, 0f);
        assertEquals(4, scheduler.drainedCount());
        assertEquals(0, scheduler.pending());
        for (int i = 0; i < scheduler.drainedCount(); i++) {
            assertEquals(4, scheduler.drainedY(i), "the blast never leaves section y=4");
            assertTrue(scheduler.drainedX(i) == 2 || scheduler.drainedX(i) == 3);
            assertTrue(scheduler.drainedZ(i) == 2 || scheduler.drainedZ(i) == 3);
        }
    }
}
