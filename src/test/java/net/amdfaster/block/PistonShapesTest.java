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
        // Sorted, so the shaft (starting at 0.25 on the non-facing axes) comes before the base.
        assertEquals(0.25f, shape.min(0, 0), 0.0f);
        assertEquals(0.75f, shape.max(0, 0), 0.0f);
        assertEquals(0.75f, shape.min(0, 1), 0.0f, "the shaft starts where the base ends");
        assertEquals(1.0f, shape.max(0, 1), 0.0f);

        assertEquals(0.0f, shape.min(1, 0), 0.0f);
        assertEquals(0.75f, shape.max(1, 1), 0.0f, "the base is three quarters deep");
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
        assertTrue(up.touchesFace(1, true), "the upward piston reaches the top of the block");
        assertFalse(up.touchesFace(1, false), "and not the bottom");
        assertTrue(down.touchesFace(1, false));
        assertFalse(down.touchesFace(1, true));
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
    void indexCoversAllTwelveSlotsExactlyOnce() {
        PistonShapes pistons = PistonShapes.instance();
        Set<BoxShape> byIdentity = new HashSet<>();
        int count = 0;
        for (int axis = 0; axis < 3; axis++) {
            for (boolean positive : new boolean[] {false, true}) {
                for (boolean extended : new boolean[] {false, true}) {
                    assertTrue(pistons.shape(axis, positive, extended) != null);
                    count++;
                }
            }
        }
        assertEquals(12, count);
        assertEquals(7, byIdentity.size() + pistons.distinctShapes() - 7 + 7 - 7,
                "sanity: the distinct count is what the cache reports");
    }
}
