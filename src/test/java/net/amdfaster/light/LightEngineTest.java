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
        assertEquals(16, engine.field().litCellCount(), "fifteen levels plus the source itself");
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
        assertTrue(engine.field().litCellCount() > 0);

        engine.removeSource(0, 0, 0, AIR);

        for (int x = 0; x < LENGTH; x++) {
            assertEquals(0, levelAt(engine, x), "dark at x=" + x);
        }
        assertEquals(0, engine.field().litCellCount());
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
        assertEquals(15, levelAt(engine, 0));
        assertEquals(11, levelAt(engine, 6), "lit by the source at 10, not the one at 0");
        assertEquals(15, levelAt(engine, 10));

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

        assertEquals(10, engine.field().get(3, 3, 3));
        assertEquals(9, engine.field().get(4, 3, 3), "+x");
        assertEquals(9, engine.field().get(2, 3, 3), "-x");
        assertEquals(9, engine.field().get(3, 4, 3), "+y");
        assertEquals(9, engine.field().get(3, 2, 3), "-y");
        assertEquals(9, engine.field().get(3, 3, 4), "+z");
        assertEquals(9, engine.field().get(3, 3, 2), "-z");
        assertEquals(6, engine.field().get(6, 3, 3), "three blocks along +x");
        assertEquals(0, engine.field().get(0, 0, 0), "a corner is six blocks away, beyond level 10");
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

        engine.addSource(5, 0, 0, 3, AIR);
        for (int x = 0; x < LENGTH; x++) {
            assertTrue(levelAt(engine, x) >= Math.max(0, 15 - Math.abs(5 - x)),
                    "a dimmer source must not darken x=" + x);
        }
    }

    @Test
    void aZeroLevelSourceDoesNothing() {
        LightEngine engine = corridor();
        engine.addSource(4, 0, 0, 0, AIR);
        assertEquals(0, engine.field().litCellCount());
        assertEquals(0, engine.cellsVisited(), "and it does not even start a flood fill");

        engine.removeSource(4, 0, 0, AIR);
        assertEquals(0, engine.cellsVisited(), "removing from a dark cell is a no-op");
    }

    @Test
    void mostNeighbourTestsChangeNothingAndThatIsThePoint() {
        // The early exit is what makes a light update cheap, so its effectiveness is worth asserting
        // rather than assuming. In a flood fill almost every neighbour is already at least as bright
        // as the value being offered; if that ratio ever inverts, something is re-lighting cells that
        // were already correct.
        LightEngine engine = corridor();
        engine.addSource(0, 0, 0, 15, AIR);

        assertTrue(engine.updatesSkippedUnchanged() > engine.updatesApplied(),
                "skipped " + engine.updatesSkippedUnchanged()
                        + " versus applied " + engine.updatesApplied());
        assertTrue(engine.cellsVisited() < engine.field().cellCount() * 2,
                "the fill visits each cell a small number of times, not once per neighbour");
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
        batched.field().set(2, 0, 0, 12);
        batched.field().set(8, 0, 0, 12);
        batched.field().set(14, 0, 0, 12);
        batched.propagate(AIR);

        for (int x = 0; x < LENGTH; x++) {
            assertEquals(levelAt(perSource, x), levelAt(batched, x), "same result at x=" + x);
        }
        assertTrue(batched.cellsVisited() < perSource.cellsVisited(),
                "batched visited " + batched.cellsVisited()
                        + " cells, per source visited " + perSource.cellsVisited());
    }

    @Test
    void clearResetsTheFieldWithoutDroppingCapacity() {
        LightEngine engine = corridor();
        engine.addSource(0, 0, 0, 15, AIR);
        engine.clear();

        assertEquals(0, engine.field().litCellCount());
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

        assertEquals(15, engine.field().get(1, 0, 0));
        assertEquals(14, engine.field().get(0, 0, 0));
        assertEquals(14, engine.field().get(2, 0, 0));
        assertEquals(0, engine.field().get(-1, 0, 0), "outside reads as no light");
        assertEquals(0, engine.field().get(3, 0, 0));
        assertFalse(engine.field().set(-1, 0, 0, 15), "and writes outside are ignored");
    }
}
