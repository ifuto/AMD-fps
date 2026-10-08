package net.amdfaster.cull;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pyramid's shape is not a detail. Two properties here decide whether occlusion culling merely
 * misses opportunities or actively deletes visible geometry, and both are checked exhaustively
 * rather than on one convenient resolution.
 */
class PyramidGeometryTest {

    @Test
    void levelsAreCeilHalvedSoNoPixelIsLeftOut() {
        // The Vulkan floor chain for 1920x1080 makes level 4 sixty-seven rows tall, and 67 * 2 = 134,
        // so row 135 of level 3 belongs to no texel above it. Ceil-halving gives 68 and covers it.
        PyramidGeometry pyramid = new PyramidGeometry(1920, 1080);
        assertEquals(11, pyramid.maxLevel());
        int[][] expected = {
                {1920, 1080}, {960, 540}, {480, 270}, {240, 135}, {120, 68}, {60, 34},
                {30, 17}, {15, 9}, {8, 5}, {4, 3}, {2, 2}, {1, 1},
        };
        for (int level = 0; level < expected.length; level++) {
            assertEquals(expected[level][0], pyramid.levelWidth(level), "width at level " + level);
            assertEquals(expected[level][1], pyramid.levelHeight(level), "height at level " + level);
        }
        assertEquals(68, pyramid.levelHeight(4), "floor-halving would give 67 and drop a row");
    }

    @Test
    void everyLevelCoversEveryBasePixel() {
        // The property the whole scheme rests on: a box projected into level-0 pixels must land inside
        // some texel at every level. 599 base sizes times 20 levels, matching the check that was run
        // before this was written down.
        for (int base = 1; base < 600; base++) {
            PyramidGeometry pyramid = new PyramidGeometry(base, 1);
            for (int level = 0; level <= pyramid.maxLevel(); level++) {
                long covered = (long) pyramid.levelWidth(level) << level;
                assertTrue(covered >= base,
                        "base " + base + " level " + level + " covers only " + covered);
            }
        }
    }

    @Test
    void theDownsampleTouchesEverySourceTexelExactlyOnce() {
        // Clamping the out-of-range tap to the edge duplicates a value already in the reduction, so a
        // min over four taps still equals the min over the real ones. This is what makes ceil-halving
        // exact rather than merely generous.
        int dropped = 0;
        for (int base = 1; base < 600; base++) {
            PyramidGeometry pyramid = new PyramidGeometry(base, 1);
            for (int level = 0; level < pyramid.maxLevel(); level++) {
                int sources = pyramid.levelWidth(level);
                int targets = pyramid.levelWidth(level + 1);
                Set<Integer> touched = new HashSet<>();
                for (int t = 0; t < targets; t++) {
                    touched.add(Math.min(2 * t, sources - 1));
                    touched.add(Math.min(2 * t + 1, sources - 1));
                }
                for (int s = 0; s < sources; s++) {
                    if (!touched.contains(s)) {
                        dropped++;
                    }
                }
            }
        }
        assertEquals(0, dropped, "ceil-halving must not drop a texel at any level");
    }

    @Test
    void floorHalvingWouldDropTexels() {
        // The counterfactual, so the exhaustive test above cannot pass by testing nothing. Vulkan's
        // own mip rule is max(1, dim >> level); counting the steps where it loses a texel is what
        // justifies departing from it.
        int dropped = 0;
        for (int base = 1; base < 600; base++) {
            for (int level = 0; level < 20; level++) {
                int sources = Math.max(1, base >> level);
                int targets = Math.max(1, base >> (level + 1));
                if (sources == 1) {
                    break;
                }
                Set<Integer> touched = new HashSet<>();
                for (int t = 0; t < targets; t++) {
                    touched.add(Math.min(2 * t, sources - 1));
                    touched.add(Math.min(2 * t + 1, sources - 1));
                }
                dropped += sources - touched.size();
            }
        }
        assertEquals(2061, dropped, "floor-halving drops this many texels over the same range");
    }

    @Test
    void selectLevelPicksTheCoarsestTexelThatStillCoversTheBox() {
        PyramidGeometry pyramid = new PyramidGeometry(1024, 1024);
        assertEquals(0, pyramid.selectLevel(1f, 1f));
        assertEquals(0, pyramid.selectLevel(0.5f, 0.5f), "sub-pixel boxes stay at full resolution");
        assertEquals(1, pyramid.selectLevel(2f, 1f));
        assertEquals(7, pyramid.selectLevel(53.89f, 97.01f), "an entity ten blocks away at 1024p");
        assertEquals(10, pyramid.selectLevel(4000f, 4000f), "clamped to the deepest level");
    }

    @Test
    void aBoxSpansAtMostTwoTexelsPerAxisAtItsSelectedLevel() {
        // That is the whole reason for the level choice: it bounds the reduction to four fetches.
        PyramidGeometry pyramid = new PyramidGeometry(1024, 1024);
        for (float size = 1f; size < 1024f; size *= 1.37f) {
            int level = pyramid.selectLevel(size, size);
            // The box can start anywhere inside a texel, so sweeping the offset is what makes this a
            // real bound: anchored at zero it would land on a texel boundary every time and prove
            // nothing about the mid-texel case.
            for (float offset = 0f; offset < 1 << level; offset += 1f) {
                PyramidGeometry.TexelSpan span =
                        pyramid.spanFor(level, offset, offset, offset + size, offset + size);
                assertTrue(span.width() <= 2 && span.height() <= 2,
                        "box " + size + " at offset " + offset + " spans "
                                + span.width() + "x" + span.height() + " at level " + level);
            }
        }
    }

    @Test
    void theSpanCoversTheWholeFootprintAndClampsAtTheEdges() {
        PyramidGeometry pyramid = new PyramidGeometry(1024, 1024);
        // Level 7 is 128 pixels per texel, so the entity rectangle measured above lands on four of them.
        PyramidGeometry.TexelSpan span =
                pyramid.spanFor(7, 485.05f, 414.99f, 538.95f, 512.00f);
        assertEquals(3, span.minX());
        assertEquals(3, span.minY());
        assertEquals(4, span.maxX());
        assertEquals(4, span.maxY());
        assertEquals(4, span.count());

        // Off the far edge: a box hanging off the screen still reads a real texel rather than
        // wrapping or throwing, because the frustum pass owns whether it is off screen.
        PyramidGeometry.TexelSpan offEdge = pyramid.spanFor(7, 900f, 900f, 2000f, 2000f);
        assertEquals(7, offEdge.maxX(), "level 7 is eight texels wide");
        assertEquals(7, offEdge.maxY());

        PyramidGeometry.TexelSpan negative = pyramid.spanFor(7, -500f, -500f, 10f, 10f);
        assertEquals(0, negative.minX());
        assertEquals(0, negative.minY());
    }

    @Test
    void anExactlyAlignedEdgeLandsOnItsOwnTexel() {
        // Pixel 512 is the first pixel of texel 4 at level 7, not the last of texel 3. Flooring after
        // the shift is what gets this right; flooring before it would put 511.9 and 512.0 together.
        PyramidGeometry pyramid = new PyramidGeometry(1024, 1024);
        assertEquals(3, pyramid.spanFor(7, 511f, 0f, 511f, 0f).maxX());
        assertEquals(4, pyramid.spanFor(7, 512f, 0f, 512f, 0f).minX());
    }

    @Test
    void theConstructorAndLevelAccessorsRejectNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new PyramidGeometry(0, 100));
        assertThrows(IllegalArgumentException.class, () -> new PyramidGeometry(100, -1));
        PyramidGeometry pyramid = new PyramidGeometry(64, 32);
        assertEquals(6, pyramid.maxLevel());
        assertThrows(IllegalArgumentException.class, () -> pyramid.levelWidth(-1));
        assertThrows(IllegalArgumentException.class, () -> pyramid.levelHeight(7));
        assertThrows(IllegalArgumentException.class, () -> pyramid.spanFor(9, 0f, 0f, 1f, 1f));
    }

    @Test
    void aOneByOnePyramidHasOneLevel() {
        PyramidGeometry pyramid = new PyramidGeometry(1, 1);
        assertEquals(0, pyramid.maxLevel());
        assertEquals(1, pyramid.levelWidth(0));
        assertEquals(0, pyramid.selectLevel(1000f, 1000f));
    }

    @Test
    void anAsymmetricPyramidRunsUntilBothAxesReachOneTexel() {
        // 1920x1080 needs eleven levels even though the width reaches one at level eleven too; the
        // height gets there at level ten. Stopping at the first axis to finish would leave the other
        // un-reduced and the coarsest tests would have nothing to read.
        PyramidGeometry pyramid = new PyramidGeometry(1920, 1080);
        assertEquals(1, pyramid.levelWidth(11));
        assertEquals(1, pyramid.levelHeight(11));
        // An absurdly large box clamps to the deepest level rather than running off the chain.
        assertEquals(11, pyramid.selectLevel(1e6f, 1e6f));
        assertEquals(1, pyramid.levelWidth(11));
        assertEquals(1, pyramid.levelHeight(11));
    }
}
