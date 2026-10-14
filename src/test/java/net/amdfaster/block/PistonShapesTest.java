package net.amdfaster.block;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PistonShapesTest {

    @Test
    void theTwelveStatesCollapseToSevenShapes() {
        // The number this class exists to produce. Six retracted facings are all a full cube, so
        // they collapse to one; six extended pistons are genuinely different because the shaft
        // points somewhere. Counted in a model first, and pinned here so a change to the geometry
        // shows up as a wrong count rather than as a subtly different piston.
        PistonShapes pistons = PistonShapes.instance();

        assertEquals(12L, pistons.statesOffered());
        assertEquals(7, pistons.distinctShapes());
        assertEquals(5L, pistons.statesCollapsed());
    }

    @Test
    void everyRetractedFacingIsTheSameObject() {
        PistonShapes pistons = PistonShapes.instance();
        BoxShape first = pistons.shape(PistonShapes.AXIS_X, true, false);

        for (int axis = 0; axis < 3; axis++) {
            for (boolean positive : new boolean[] {false, true}) {
                assertSame(first, pistons.shape(axis, positive, false),
                        "axis " + axis + " positive " + positive);
            }
        }
        assertEquals(1, first.boxCount(), "and it is a plain full cube");
    }

    @Test
    void extendedPistonsDifferByFacing() {
        PistonShapes pistons = PistonShapes.instance();
        Set<BoxShape> seen = new HashSet<>();
        for (int axis = 0; axis < 3; axis++) {
            for (boolean positive : new boolean[] {false, true}) {
                seen.add(pistons.shape(axis, positive, true));
            }
        }
        assertEquals(6, seen.size(), "six facings, six distinct extended shapes");
    }

    @Test
    void anExtendedPistonIsABaseAndAShaft() {
        BoxShape shape = PistonShapes.build(PistonShapes.AXIS_Y, true, true);

        assertEquals(2, shape.boxCount());
        // Sorted by minX first, so the base (which spans the whole X extent from 0) comes before the
        // shaft (which starts at 0.25). An earlier version of this test had them the other way round.
        assertEquals(0.0f, shape.min(0, 0), 0.0f, "base first");
        assertEquals(1.0f, shape.max(0, 0), 0.0f);
        assertEquals(0.0f, shape.min(0, 1), 0.0f);
        assertEquals(0.75f, shape.max(0, 1), 0.0f, "the base is three quarters deep");

        assertEquals(0.25f, shape.min(1, 0), 0.0f, "shaft second");
        assertEquals(0.75f, shape.max(1, 0), 0.0f);
        assertEquals(0.75f, shape.min(1, 1), 0.0f, "the shaft starts where the base ends");
        assertEquals(1.0f, shape.max(1, 1), 0.0f);
    }

    @Test
    void theShaftRunsTheFullRemainingQuarter() {
        // Facing negative Y, so the shaft occupies the bottom quarter and the base sits above it.
        BoxShape shape = PistonShapes.build(PistonShapes.AXIS_Y, false, true);

        boolean foundShaft = false;
        boolean foundBase = false;
        for (int b = 0; b < shape.boxCount(); b++) {
            if (shape.min(b, 1) == 0.0f && shape.max(b, 1) == 0.25f) {
                foundShaft = true;
                assertEquals(0.25f, shape.min(b, 0), 0.0f);
                assertEquals(0.75f, shape.max(b, 0), 0.0f);
            }
            if (shape.min(b, 1) == 0.25f && shape.max(b, 1) == 1.0f) {
                foundBase = true;
                assertEquals(0.0f, shape.min(b, 0), 0.0f);
                assertEquals(1.0f, shape.max(b, 0), 0.0f);
            }
        }
        assertTrue(foundShaft, "no shaft box in " + shape);
        assertTrue(foundBase, "no base box in " + shape);
    }

    @Test
    void everyShapeStaysInsideTheBlockAndIsNotEmpty() {
        // A box reaching past 1.0 would collide with the neighbouring block's space, and one with
        // no volume would let an entity fall through the piston head.
        PistonShapes pistons = PistonShapes.instance();
        for (int axis = 0; axis < 3; axis++) {
            for (boolean positive : new boolean[] {false, true}) {
                for (boolean extended : new boolean[] {false, true}) {
                    BoxShape shape = pistons.shape(axis, positive, extended);
                    for (int b = 0; b < shape.boxCount(); b++) {
                        for (int a = 0; a < 3; a++) {
                            assertTrue(shape.min(b, a) >= 0.0f, "axis " + axis + " box " + b);
                            assertTrue(shape.max(b, a) <= 1.0f, "axis " + axis + " box " + b);
                            assertTrue(shape.max(b, a) > shape.min(b, a),
                                    "degenerate box at axis " + axis + " box " + b);
                        }
                    }
                }
            }
        }
    }

    @Test
    void opposingFacingsAreMirrorImagesNotTheSameShape() {
        // Positive and negative Y pistons put the base at opposite ends. Treating them as one shape
        // would let a player stand on a piston head that is not there.
        PistonShapes pistons = PistonShapes.instance();
        BoxShape up = pistons.shape(PistonShapes.AXIS_Y, true, true);
        BoxShape down = pistons.shape(PistonShapes.AXIS_Y, false, true);

        assertFalse(up.equals(down));
        // Both reach both faces, because the base spans the full non-facing extent and starts at the
        // block boundary. What differs is where the base sits, so that is what gets checked.
        assertTrue(up.touchesFace(1, true));
        assertTrue(up.touchesFace(1, false));
        boolean upHasHighBase = false;
        boolean downHasLowBase = false;
        for (int b = 0; b < up.boxCount(); b++) {
            if (up.min(b, 0) == 0.0f && up.max(b, 1) == 0.75f) {
                upHasHighBase = true;
            }
        }
        for (int b = 0; b < down.boxCount(); b++) {
            if (down.min(b, 0) == 0.0f && down.min(b, 1) == 0.25f) {
                downHasLowBase = true;
            }
        }
        assertTrue(upHasHighBase, "the upward piston's base sits low and its head is high");
        assertTrue(downHasLowBase, "the downward one is the other way round");
    }

    @Test
    void boxOrderDoesNotMakeTwoIdenticalShapesDiffer() {
        // BoxShape sorts, so a builder that emits base-then-shaft and one that emits shaft-then-base
        // produce the same shape. Without the sort this is where the cache quietly stops collapsing.
        BoxShape a = new BoxShape(0f, 0f, 0f, 1f, 0.75f, 1f, 0.25f, 0.75f, 0.25f, 0.75f, 1f, 0.75f);
        BoxShape b = new BoxShape(0.25f, 0.75f, 0.25f, 0.75f, 1f, 0.75f, 0f, 0f, 0f, 1f, 0.75f, 1f);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void theSingletonIsOneInstance() {
        assertSame(PistonShapes.instance(), PistonShapes.instance());
    }

    @Test
    void anInvalidAxisIsRejected() {
        PistonShapes pistons = PistonShapes.instance();
        assertThrows(IllegalArgumentException.class, () -> pistons.shape(3, true, false));
        assertThrows(IllegalArgumentException.class, () -> pistons.shape(-1, true, false));
        assertThrows(IllegalArgumentException.class, () -> PistonShapes.build(9, true, false));
    }

    @Test
    void everyOneOfTheTwelveSlotsResolvesToAShape() {
        PistonShapes pistons = PistonShapes.instance();
        Set<BoxShape> distinct = new HashSet<>();
        int count = 0;
        for (int axis = 0; axis < 3; axis++) {
            for (boolean positive : new boolean[] {false, true}) {
                for (boolean extended : new boolean[] {false, true}) {
                    BoxShape shape = pistons.shape(axis, positive, extended);
                    assertTrue(shape != null, "axis " + axis);
                    assertTrue(shape.boxCount() > 0);
                    distinct.add(shape);
                    count++;
                }
            }
        }
        assertEquals(12, count, "six facings times two states");
        assertEquals(7, distinct.size(), "and seven distinct objects among them");
        assertEquals(distinct.size(), pistons.distinctShapes(),
                "which is what the cache reports, so the two counts agree");
    }
}
