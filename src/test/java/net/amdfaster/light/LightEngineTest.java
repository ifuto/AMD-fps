package net.amdfaster.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightEngineTest {

    /** A corridor one block tall and deep, so propagation is effectively one-dimensional. */
    private static final int LENGTH = 24;

    private static final LightEngine.OpacitySource AIR = (x, y, z) -> 0;

    private static LightEngine corridor() {
        return new LightEngine(new LightField(0, 0, 0, LENGTH, 1, 1));
    }

    private static int levelAt(LightEngine engine, int x) {
        return engine.field().get(x, 0, 0);
    }

    @Test
    void lightFallsOffByOnePerBlockInAir() {
        // The baseline every other case is a variation on. Derived from a model of the same
        // algorithm rather than by eye.
        LightEngine engine = corridor();
        engine.addSource(0, 0, 0, 15, AIR);

        for (int x = 0; x < LENGTH; x++) {
            assertEquals(Math.max(0, 15 - x), levelAt(engine, x), "level at x=" + x);
        }
        // The source holds 15 and each step away costs one, so the last lit cell is fourteen blocks
        // out: fifteen cells in total, not sixteen.
        assertEquals(15, engine.field().litCellCount(), "the source plus the fourteen cells it reaches");
    }

    @Test
    void anOpaqueBlockStopsLightEntirely() {
        // Attenuation 15 is what a full opaque block does, and level - 1 - 15 is never positive, so
        // nothing gets through. A wall that leaked one level would light the far side of every
        // house in the game.
        LightEngine.OpacitySource wall = (x, y, z) -> x == 5 ? 15 : 0;
        LightEngine engine = corridor();
        engine.addSource(0, 0, 0, 15, wall);

        for (int x = 0; x < 5; x++) {
            assertEquals(15 - x, levelAt(engine, x), "this side of the wall at x=" + x);
        }
        for (int x = 5; x < LENGTH; x++) {
            assertEquals(0, levelAt(engine, x), "nothing past the wall at x=" + x);
        }
    }

    @Test
    void removingTheOnlySourceDarkensEverythingItLit() {
        LightEngine engine = corridor();
        engine.addSource(0, 0, 0, 15, AIR);
        assertTrue(engine.field().litCellCount() > 0, "L60");

        engine.removeSource(0, 0, 0, AIR);

        for (int x = 0; x < LENGTH; x++) {
            assertEquals(0, levelAt(engine, x), "dark at x=" + x);
        }
        assertEquals(0, engine.field().litCellCount(), "L67");
    }

    @Test
    void removingOneSourcePreservesWhatTheOtherStillReaches() {
        // The case the two-phase removal exists for. Clearing cells is easy; knowing what they should
        // become is not, because a cell in the overlap was lit by both sources and the stored value
        // does not say which one won. Get this wrong and breaking one torch darkens the corridor that
        // a second torch is still lighting -- which is the bug a single-phase removal has.
        LightEngine engine = corridor();
        engine.addSource(0, 0, 0, 15, AIR);
        engine.addSource(10, 0, 0, 15, AIR);

        // The overlap peaks between them: max(15 - x, 15 - (10 - x)).
        assertEquals(15, levelAt(engine, 0), "L81");
        assertEquals(11, levelAt(engine, 6), "lit by the source at 10, not the one at 0");
        assertEquals(15, levelAt(engine, 10), "L83");

        engine.removeSource(0, 0, 0, AIR);

        for (int x = 0; x < LENGTH; x++) {
            assertEquals(Math.max(0, 15 - Math.abs(10 - x)), levelAt(engine, x),
                    "after removal, x=" + x + " should hold exactly what the surviving source gives");
        }
    }

    @Test
    void lightSpreadsInAllSixDirections() {
        LightEngine engine = new LightEngine(new LightField(0, 0, 0, 7, 7, 7));
        engine.addSource(3, 3, 3, 10, AIR);

        assertEquals(10, engine.field().get(3, 3, 3), "L98");
        assertEquals(9, engine.field().get(4, 3, 3), "+x");
        assertEquals(9, engine.field().get(2, 3, 3), "-x");
        assertEquals(9, engine.field().get(3, 4, 3), "+y");
        assertEquals(9, engine.field().get(3, 2, 3), "-y");
        assertEquals(9, engine.field().get(3, 3, 4), "+z");
        assertEquals(9, engine.field().get(3, 3, 2), "-z");
        assertEquals(7, engine.field().get(6, 3, 3), "three blocks along +x, so three levels down");
        assertEquals(1, engine.field().get(0, 0, 0),
                "the far corner is nine blocks away by Manhattan distance, which is one short of the "
                        + "source's reach of ten, so it holds the last level rather than nothing");
        assertEquals(1, engine.field().get(6, 6, 6),
                "the opposite corner is also nine away and so also holds the last level");
        assertEquals(1, engine.field().get(6, 6, 0),
                "and so is this one: 3 + 3 + 3, the same as any other corner");
        // The farthest cell in a 7x7x7 cube from its centre is nine blocks away, which is inside a
        // level-10 source's reach. So there is no unlit cell to point at here -- every one of the 343
        // is reached, and that is the stronger statement anyway.
        assertEquals(343, engine.field().litCellCount(), "the whole volume, all 7x7x7 of it");
    }

    @Test
    void addingASourceIsMonotonicAndIdempotent() {
        // A source only ever raises what it reaches. Adding a dimmer source where a brighter one
        // already is must not darken anything, and adding the same source twice must not change the
        // field -- otherwise a repeated update would flicker the light.
        LightEngine engine = corridor();
        engine.addSource(5, 0, 0, 15, AIR);
        int before = engine.field().litCellCount();

        engine.addSource(5, 0, 0, 15, AIR);
        assertEquals(before, engine.field().litCellCount(), "the same source twice changes nothing");
        for (int x = 0; x < LENGTH; x++) {
            assertEquals(Math.max(0, 15 - Math.abs(5 - x)), levelAt(engine, x), "x=" + x);
        }

        long appliedBefore = engine.updatesApplied();
        long visitedBefore = engine.cellsVisited();
        engine.addSource(5, 0, 0, 3, AIR);
        assertEquals(appliedBefore, engine.updatesApplied(),
                "a dimmer source is rejected before it touches anything");
        assertEquals(visitedBefore, engine.cellsVisited(), "and starts no flood fill");
        for (int x = 0; x < LENGTH; x++) {
            assertEquals(Math.max(0, 15 - Math.abs(5 - x)), levelAt(engine, x),
                    "every cell still holds the brighter source's value at x=" + x);
        }
    }

    @Test
    void aZeroLevelSourceDoesNothing() {
        LightEngine engine = corridor();
        engine.addSource(4, 0, 0, 0, AIR);
        assertEquals(0, engine.field().litCellCount(), "L144");
        assertEquals(0, engine.cellsVisited(), "and it does not even start a flood fill");

        engine.removeSource(4, 0, 0, AIR);
        assertEquals(0, engine.cellsVisited(), "removing from a dark cell is a no-op");
    }

    @Test
    void theFillTouchesOnlyCellsThatExistAndEachOneOnce() {
        // Pinned to exact numbers because the failure mode this replaces was not a wrong answer, it
        // was an answer reached by doing three thousand times the work. A cell outside the field reads
        // as 0, so "brighter than what is there" was true of every out-of-bounds neighbour, and the
        // fill duly enqueued and explored a ball fifteen cells wide in every direction around a
        // one-block-tall corridor -- 43843 updates applied to light 15 cells.
        LightEngine engine = corridor();
        engine.addSource(0, 0, 0, 15, AIR);

        assertEquals(15, engine.updatesApplied(), "the source plus the fourteen cells it reaches");
        assertEquals(15, engine.cellsVisited(), "each lit cell is polled exactly once");
        assertEquals(70, engine.updatesSkippedUnchanged(),
                "fifteen cells times six directions, less the fourteen that landed");
        assertEquals(15, engine.field().litCellCount(), "L165");
    }

    @Test
    void mostNeighbourTestsChangeNothingAndThatIsThePoint() {
        // The early exit is what makes a light update cheap, so its effectiveness is worth asserting
        // rather than assuming. In a flood fill almost every neighbour is already at least as bright
        // as the value being offered, or is outside the field entirely; if that ratio ever inverts,
        // something is re-lighting cells that were already correct.
        LightEngine engine = corridor();
        engine.addSource(0, 0, 0, 15, AIR);

        assertTrue(engine.updatesSkippedUnchanged() > engine.updatesApplied() * 4,
                "skipped " + engine.updatesSkippedUnchanged()
                        + " versus applied " + engine.updatesApplied());
        assertTrue(engine.cellsVisited() < engine.field().cellCount(),
                "the fill visits each cell once, not once per neighbour");
    }

    @Test
    void aNarrowFieldDoesNotCauseWorkProportionalToTheSpaceAroundIt() {
        // The same update in a field one cell tall and one cell deep. The volume of phantom space the
        // buggy version explored was unchanged by how thin the real field was, so this is the case
        // where the blow-up was most extreme: three cells lit, and the work should be about three
        // cells' worth rather than a sphere's.
        LightEngine engine = new LightEngine(new LightField(0, 0, 0, 3, 1, 1));
        engine.addSource(1, 0, 0, 15, AIR);

        assertEquals(3, engine.field().litCellCount(), "L193");
        assertEquals(3, engine.updatesApplied(), "L194");
        assertEquals(3, engine.cellsVisited(), "L195");
        assertTrue(engine.updatesSkippedUnchanged() < 30,
                "eighteen neighbour tests for three cells, so well under thirty: "
                        + engine.updatesSkippedUnchanged());
    }

    @Test
    void settingManySourcesThenFloodingOnceIsCheaperThanFloodingPerSource() {
        // A section being loaded can hold dozens of torches. Flooding per source redoes the same
        // cells repeatedly; setting them all and flooding once does not.
        LightEngine perSource = corridor();
        perSource.addSource(2, 0, 0, 12, AIR);
        perSource.addSource(8, 0, 0, 12, AIR);
        perSource.addSource(14, 0, 0, 12, AIR);

        LightEngine batched = corridor();
        // Staged, not written straight to the field. Writing the level directly enqueues nothing, so a
        // following propagate finds an empty queue and does no work at all -- the sources sit there
        // unspread, which is exactly what this comparison would have been measuring.
        batched.stageSource(2, 0, 0, 12);
        batched.stageSource(8, 0, 0, 12);
        batched.stageSource(14, 0, 0, 12);
        batched.propagate(AIR);

        for (int x = 0; x < LENGTH; x++) {
            assertEquals(levelAt(perSource, x), levelAt(batched, x), "same result at x=" + x);
        }
        // Both numbers from a model of the same algorithm. The three sources overlap heavily, so
        // propagating them separately redoes the shared ground three times.
        assertEquals(24, batched.cellsVisited(), "L224");
        assertEquals(40, perSource.cellsVisited(), "L225");
    }

    @Test
    void clearResetsTheFieldWithoutDroppingCapacity() {
        LightEngine engine = corridor();
        engine.addSource(0, 0, 0, 15, AIR);
        engine.clear();

        assertEquals(0, engine.field().litCellCount(), "L234");
        engine.addSource(0, 0, 0, 15, AIR);
        assertEquals(15, levelAt(engine, 0), "and it works again afterwards");
    }

    @Test
    void outOfBoundsPropagationIsIgnoredRatherThanThrowing() {
        // The fill walks outward and reaches the edge of whatever volume it was given. Treating the
        // edge as "no light" keeps the caller from needing a bounds check at every one of six
        // neighbour steps, which is six branches per block in the hottest loop there is.
        LightEngine engine = new LightEngine(new LightField(0, 0, 0, 3, 1, 1));
        engine.addSource(1, 0, 0, 15, AIR);

        assertEquals(15, engine.field().get(1, 0, 0), "L247");
        assertEquals(14, engine.field().get(0, 0, 0), "L248");
        assertEquals(14, engine.field().get(2, 0, 0), "L249");
        assertEquals(0, engine.field().get(-1, 0, 0), "outside reads as no light");
        assertEquals(0, engine.field().get(3, 0, 0), "L251");
        assertFalse(engine.field().set(-1, 0, 0, 15), "and writes outside are ignored");
    }
}
