package net.amdfaster.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoxShapeTest {

    private static final float[] FULL_CUBE = {0f, 0f, 0f, 1f, 1f, 1f};
    private static final float[] BOTTOM = {0f, 0f, 0f, 1f, 0.5f, 1f};
    private static final float[] TOP_HALF = {0f, 0.5f, 0f, 1f, 1f, 1f};

    @Test
    void boxesAreSortedSoOrderDoesNotDistinguishShapes() {
        // The case the whole design turns on. Vanilla walks a model's elements and the order comes
        // from the JSON, so two blocks a player cannot tell apart routinely emit boxes in different
        // sequences. Without this they stay two instances and nothing gets deduplicated.
        BoxShape a = new BoxShape(BOTTOM[0], BOTTOM[1], BOTTOM[2], BOTTOM[3], BOTTOM[4], BOTTOM[5],
                0f, 0.5f, 0f, 0.5f, 1f, 1f);
        BoxShape b = new BoxShape(0f, 0.5f, 0f, 0.5f, 1f, 1f,
                BOTTOM[0], BOTTOM[1], BOTTOM[2], BOTTOM[3], BOTTOM[4], BOTTOM[5]);

        assertEquals(a, b, "same boxes, different input order");
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentShapesAreNotEqual() {
        assertNotEqualsShapes(new BoxShape(FULL_CUBE), new BoxShape(BOTTOM));
        assertNotEqualsShapes(new BoxShape(BOTTOM), new BoxShape(TOP_HALF));
    }

    private static void assertNotEqualsShapes(BoxShape a, BoxShape b) {
        assertFalse(a.equals(b));
        assertFalse(b.equals(a));
    }

    @Test
    void theCacheReturnsOneInstancePerDistinctShape() {
        ShapeCache cache = new ShapeCache();
        BoxShape first = cache.canonical(new BoxShape(FULL_CUBE));
        BoxShape second = cache.canonical(new BoxShape(FULL_CUBE.clone()));

        assertSame(first, second, "the second full cube is the same object as the first");
        assertEquals(1, cache.size());
        assertEquals(1, cache.duplicates());
    }

    @Test
    void aRealisticDistributionCollapsesAlmostEntirely() {
        // 1,200 block states over the mix a modpack actually has: mostly full cubes, some slabs, a
        // few stair rotations. Modelled first, and the figure here is the model's answer.
        ShapeCache cache = new ShapeCache();
        for (int i = 0; i < 1200; i++) {
            int kind = i % 5;
            BoxShape shape = switch (kind) {
                case 0, 1, 2, 3 -> new BoxShape(FULL_CUBE.clone());
                case 4 -> new BoxShape(BOTTOM.clone());
                default -> throw new AssertionError();
            };
            cache.canonical(shape);
        }

        assertEquals(2, cache.size(), "five distinct shapes in the modelled mix reduce to two here");
        assertEquals(1200L, cache.offered());
        assertEquals(1198L, cache.duplicates());
        assertEquals(99.8, cache.duplicateRatePercent(), 0.05);
        assertTrue(cache.bytesSaved() > 0);
    }

    @Test
    void removingADroppedShapeFreesItAndIsReported() {
        ShapeCache cache = new ShapeCache();
        BoxShape shape = cache.canonical(new BoxShape(BOTTOM));
        assertTrue(cache.contains(shape));

        assertTrue(cache.remove(shape));
        assertEquals(0, cache.size());
        assertFalse(cache.contains(shape));
        assertFalse(cache.remove(shape), "and removing it twice is not an error");
    }

    @Test
    void touchesFaceReportsWhetherAnythingReachesTheBoundary() {
        BoxShape full = new BoxShape(FULL_CUBE);
        assertTrue(full.touchesFace(1, true));
        assertTrue(full.touchesFace(1, false));

        BoxShape bottom = new BoxShape(BOTTOM);
        assertTrue(bottom.touchesFace(1, false), "sits on the floor");
        assertFalse(bottom.touchesFace(1, true), "and stops half way up");
        assertTrue(bottom.touchesFace(0, true), "but spans the full X extent");
    }

    @Test
    void minAndMaxReadBackWhatWentIn() {
        BoxShape shape = new BoxShape(0.25f, 0f, 0f, 0.75f, 1f, 1f);

        assertEquals(0.25f, shape.min(0, 0), 0.0f);
        assertEquals(0.75f, shape.max(0, 0), 0.0f);
        assertEquals(1f, shape.max(0, 1), 0.0f);
        assertEquals(1, shape.boxCount());
    }

    @Test
    void anEmptyShapeIsRejected() {
        // A shape with no boxes collides with nothing, so entities would fall through every block
        // using it. That is worse than an exception at load time.
        assertThrows(IllegalArgumentException.class, () -> new BoxShape());
    }

    @Test
    void anInvertedBoxIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new BoxShape(1f, 0f, 0f, 0f, 1f, 1f), "maxX below minX");
        assertThrows(IllegalArgumentException.class,
                () -> new BoxShape(0f, 1f, 0f, 1f, 0f, 1f), "maxY below minY");
    }

    @Test
    void aNaNCoordinateIsRejected() {
        // NaN comparisons are all false, so an inverted-box check would pass it and it would then
        // poison every intersection test the shape takes part in.
        assertThrows(IllegalArgumentException.class,
                () -> new BoxShape(0f, 0f, 0f, Float.NaN, 1f, 1f));
    }

    @Test
    void aCoordinateCountThatIsNotAMultipleOfSixIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BoxShape(0f, 0f, 0f, 1f, 1f));
    }

    @Test
    void theInputArrayIsCopied() {
        // A shape is shared by every block using it. If it aliased the caller's array, a later edit
        // by that caller would silently change every block's collision.
        float[] input = FULL_CUBE.clone();
        BoxShape shape = new BoxShape(input);
        input[3] = 5f;

        assertEquals(1f, shape.max(0, 0), 0.0f, "the shape kept the value it was built with");
    }

    @Test
    void bytesGrowWithTheBoxCount() {
        BoxShape one = new BoxShape(FULL_CUBE);
        BoxShape two = new BoxShape(FULL_CUBE[0], FULL_CUBE[1], FULL_CUBE[2],
                FULL_CUBE[3], FULL_CUBE[4], FULL_CUBE[5],
                BOTTOM[0], BOTTOM[1], BOTTOM[2], BOTTOM[3], BOTTOM[4], BOTTOM[5]);

        assertTrue(two.bytes() > one.bytes());
        assertEquals(2, two.boxCount());
    }

    @Test
    void aFreshCacheReportsZeroNotNaN() {
        ShapeCache cache = new ShapeCache();
        assertEquals(0.0, cache.duplicateRatePercent());
        assertEquals(0L, cache.offered());
    }

    @Test
    void outOfRangeBoxAccessIsRejected() {
        BoxShape shape = new BoxShape(FULL_CUBE);
        assertThrows(IndexOutOfBoundsException.class, () -> shape.min(1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> shape.max(-1, 0));
    }

    @Test
    void toStringShowsTheBoxes() {
        assertTrue(new BoxShape(FULL_CUBE).toString().contains("BoxShape["));
    }
}
