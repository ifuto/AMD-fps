package net.amdfaster.cull;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sort key decides draw order, and draw order for translucent geometry decides whether the
 * scene looks right. A key that sorts the wrong way round does not crash anything: the water is
 * simply drawn over the glass in front of it.
 *
 * <p>Expectations were produced by a separate implementation of the same transform and diffed
 * before committing, including a 20,000-key sort whose result was checked bucket by bucket rather
 * than spot-checked.
 */
class SortKeyTest {

    @Test
    void opaqueGeometrySortsFrontToBack() {
        // Reversed Z puts the near plane at depth 1.0, so "front" is the larger number and has to
        // produce the smaller key.
        assertTrue(SortKey.compare(SortKey.key(SortKey.BUCKET_OPAQUE, 1.0f, 0),
                SortKey.key(SortKey.BUCKET_OPAQUE, 0.0f, 0)) < 0, "near before far");
        assertTrue(SortKey.compare(SortKey.key(SortKey.BUCKET_OPAQUE, 0.9f, 0),
                SortKey.key(SortKey.BUCKET_OPAQUE, 0.1f, 0)) < 0);
    }

    @Test
    void cutoutGeometrySortsFrontToBackLikeOpaque() {
        assertTrue(SortKey.compare(SortKey.key(SortKey.BUCKET_CUTOUT, 1.0f, 0),
                SortKey.key(SortKey.BUCKET_CUTOUT, 0.0f, 0)) < 0);
    }

    @Test
    void translucentGeometrySortsBackToFront() {
        // The opposite direction, because it blends and whatever is drawn last wins.
        assertTrue(SortKey.compare(SortKey.key(SortKey.BUCKET_TRANSLUCENT, 0.0f, 0),
                SortKey.key(SortKey.BUCKET_TRANSLUCENT, 1.0f, 0)) < 0, "far before near");
        assertTrue(SortKey.compare(SortKey.key(SortKey.BUCKET_TRANSLUCENT, 0.1f, 0),
                SortKey.key(SortKey.BUCKET_TRANSLUCENT, 0.9f, 0)) < 0);
    }

    @Test
    void theBucketDominatesDepth() {
        // Opaque geometry is drawn before translucent geometry no matter how far away either is,
        // because the two are not interchangeable: one writes depth, the other blends against it.
        assertTrue(SortKey.compare(SortKey.key(SortKey.BUCKET_OPAQUE, 0.0f, 0),
                SortKey.key(SortKey.BUCKET_TRANSLUCENT, 1.0f, 0)) < 0,
                "the furthest opaque thing still draws before the nearest translucent thing");
    }

    @Test
    void theBucketsComeOutInDeclaredOrder() {
        long opaque = SortKey.key(SortKey.BUCKET_OPAQUE, 0.0f, 0);
        long cutout = SortKey.key(SortKey.BUCKET_CUTOUT, 1.0f, 0);
        long translucent = SortKey.key(SortKey.BUCKET_TRANSLUCENT, 0.0f, 0);
        assertTrue(SortKey.compare(opaque, cutout) < 0, "cutout after opaque");
        assertTrue(SortKey.compare(cutout, translucent) < 0, "translucent last");
        // Cutout is after opaque because the Z pre-pass it relies on must have finished writing
        // depth first.
    }

    @Test
    void equalDepthsFallBackToIdSoTheOrderDoesNotFlicker() {
        // Two coplanar translucent quads would otherwise swap relative order between frames as the
        // sort sees them in a different order, which reads as flicker rather than as a sorting bug.
        assertTrue(SortKey.compare(SortKey.key(SortKey.BUCKET_TRANSLUCENT, 0.5f, 5),
                SortKey.key(SortKey.BUCKET_TRANSLUCENT, 0.5f, 7)) < 0);
        assertEquals(5, SortKey.id(SortKey.key(SortKey.BUCKET_TRANSLUCENT, 0.5f, 5)));
        assertEquals(SortKey.MAX_ID, SortKey.id(SortKey.key(SortKey.BUCKET_OPAQUE, 0.5f,
                SortKey.MAX_ID)));
    }

    @Test
    void theDepthTransformIsMonotonicAcrossTheWholeRange() {
        // The whole scheme rests on this: for non-negative floats the IEEE-754 bit pattern already
        // increases with the value, which is what makes a comparison-free radix sort possible.
        float previous = -1f;
        for (int i = 0; i <= 1000; i++) {
            float depth = i / 1000f;
            assertTrue(SortKey.compare(SortKey.key(SortKey.BUCKET_TRANSLUCENT, previous, 0),
                            SortKey.key(SortKey.BUCKET_TRANSLUCENT, depth, 0)) <= 0,
                    "depth " + previous + " must not sort after " + depth);
            previous = depth;
        }
    }

    @Test
    void outOfRangeAndNonFiniteDepthsAreClamped() {
        // Depth is clipped later in the pipeline than this key is built. A negative depth has an
        // inverted bit pattern, so trusting it would put geometry behind the camera in front of
        // everything else in the frame.
        assertEquals(SortKey.key(SortKey.BUCKET_OPAQUE, 0f, 0),
                SortKey.key(SortKey.BUCKET_OPAQUE, -1f, 0), "below the range clamps to 0");
        assertEquals(SortKey.key(SortKey.BUCKET_OPAQUE, 1f, 0),
                SortKey.key(SortKey.BUCKET_OPAQUE, 2f, 0), "above the range clamps to 1");
        assertEquals(SortKey.key(SortKey.BUCKET_OPAQUE, 0f, 0),
                SortKey.key(SortKey.BUCKET_OPAQUE, Float.NaN, 0), "NaN clamps to 0");
        assertEquals(SortKey.key(SortKey.BUCKET_TRANSLUCENT, 0f, 0),
                SortKey.key(SortKey.BUCKET_TRANSLUCENT, Float.NaN, 0));
    }

    @Test
    void outOfRangeBucketsAndIdsAreRejected() {
        // Silently masking an out-of-range id would alias two draws onto one key and one of them
        // would disappear. A bucket out of range would collide with a depth field.
        assertThrows(IllegalArgumentException.class, () -> SortKey.key(-1, 0.5f, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SortKey.key(SortKey.BUCKET_COUNT, 0.5f, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SortKey.key(SortKey.BUCKET_OPAQUE, 0.5f, -1));
        assertThrows(IllegalArgumentException.class,
                () -> SortKey.key(SortKey.BUCKET_OPAQUE, 0.5f, SortKey.MAX_ID + 1));
    }

    @Test
    void theFieldsRoundTrip() {
        assertEquals(SortKey.BUCKET_TRANSLUCENT,
                SortKey.bucket(SortKey.key(SortKey.BUCKET_TRANSLUCENT, 0.5f, 12345)));
        assertEquals(12345, SortKey.id(SortKey.key(SortKey.BUCKET_TRANSLUCENT, 0.5f, 12345)));

        // The three fields have to partition the word exactly. Too narrow and a field overflows
        // into its neighbour, which shows up as depth affecting the bucket.
        assertEquals(64, SortKey.ID_BITS + SortKey.DEPTH_BITS + SortKey.BUCKET_BITS);
        long maxed = SortKey.key(SortKey.BUCKET_TRANSLUCENT, 1.0f, SortKey.MAX_ID);
        assertEquals(SortKey.BUCKET_TRANSLUCENT, SortKey.bucket(maxed),
                "a maximum depth must not carry into the bucket field");
        assertEquals(SortKey.MAX_ID, SortKey.id(maxed));
    }

    @Test
    void aLargeSortPutsEveryBucketInItsOwnDirection() {
        // Not a spot check: 20,000 keys including the clamping edge cases, sorted, then verified
        // bucket by bucket. This is the test that would catch a direction that is right for the
        // two values the other tests happen to use and wrong elsewhere.
        Random random = new Random(7);
        record Item(int bucket, float depth, int id) {
        }
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 20000; i++) {
            items.add(new Item(random.nextInt(SortKey.BUCKET_COUNT), random.nextFloat(),
                    random.nextInt(1 << 20)));
        }
        items.add(new Item(SortKey.BUCKET_OPAQUE, -0.5f, 1));
        items.add(new Item(SortKey.BUCKET_OPAQUE, 1.5f, 2));
        items.add(new Item(SortKey.BUCKET_TRANSLUCENT, Float.NaN, 3));

        List<Item> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> SortKey.compare(
                SortKey.key(a.bucket(), a.depth(), a.id()),
                SortKey.key(b.bucket(), b.depth(), b.id())));

        int previousBucket = -1;
        for (Item item : sorted) {
            assertTrue(item.bucket() >= previousBucket, "buckets must not go backwards");
            previousBucket = item.bucket();
        }

        for (int bucket = 0; bucket < SortKey.BUCKET_COUNT; bucket++) {
            List<Float> depths = new ArrayList<>();
            for (Item item : sorted) {
                if (item.bucket() == bucket) {
                    float d = item.depth();
                    depths.add(Float.isNaN(d) ? 0f : Math.min(1f, Math.max(0f, d)));
                }
            }
            boolean frontToBack = bucket != SortKey.BUCKET_TRANSLUCENT;
            for (int i = 1; i < depths.size(); i++) {
                float a = depths.get(i - 1);
                float b = depths.get(i);
                assertTrue(frontToBack ? a >= b : a <= b,
                        "bucket " + bucket + " out of order at " + i + ": " + a + " then " + b);
            }
        }
    }

    @Test
    void theComparisonIsUnsignedSoAHundredAndTwentyEightBucketsWouldStillSort() {
        // With three buckets the sign bit is never set, so this is not reachable today. It is here
        // because the bucket field is 8 bits wide and the reason for that width is future material
        // classes; a signed compare would silently invert the order the day bucket 128 appears.
        long low = (127L << 56) | 1L;
        long high = (128L << 56) | 1L;
        assertTrue(Long.compare(high, low) < 0, "a signed compare really would get this backwards");
        assertTrue(SortKey.compare(low, high) < 0, "and the unsigned one does not");
    }
}
