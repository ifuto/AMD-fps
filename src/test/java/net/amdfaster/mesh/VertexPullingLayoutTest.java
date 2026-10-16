package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VertexPullingLayoutTest {

    /** Position, texture coordinate, light, and one byte of flags. */
    private static final int[] SIZES = {4, 2, 2, 1};

    private static VertexPullingLayout layout() {
        return new VertexPullingLayout(SIZES);
    }

    @Test
    void everyAttributeStartsWithinAWord() {
        // One that straddles a word boundary has to be read as two loads and reassembled, and getting that
        // wrong produces a position made of the wrong bytes.
        VertexPullingLayout layout = layout();

        for (int attribute = 0; attribute < SIZES.length; attribute++) {
            assertEquals(0, layout.offset(attribute) % VertexPullingLayout.WORD_BYTES,
                    "attribute " + attribute + " is aligned");
        }
    }

    @Test
    void theStrideIncludesThePadding() {
        VertexPullingLayout layout = layout();

        assertEquals(0, layout.offset(0));
        assertEquals(4, layout.offset(1));
        assertEquals(8, layout.offset(2), "padded past the two-byte texture coordinate");
        assertEquals(12, layout.offset(3));
        assertEquals(13, layout.stride());
    }

    @Test
    void theAddressIsTheVertexTimesTheStride() {
        VertexPullingLayout layout = layout();

        assertEquals(13 * 7 + 8, layout.addressOf(7, 2));
        assertEquals(0, layout.addressOf(0, 0));
    }

    @Test
    void aDepthOnlyPassLoadsOneAttributeOfFour() {
        // A depth-only or shadow pass needs nothing but positions, so the other loads are branched over and
        // never issued, and the fixed-function unit that would have fetched them is free.
        VertexPullingLayout layout = layout();

        assertEquals(1, layout.fetch(0, new boolean[] {true, false, false, false}));
        assertEquals(1L, layout.loadsIssued());
        assertEquals(3L, layout.loadsSkipped());
        assertEquals(75.0, layout.skippedPercent(), 1e-9);
    }

    @Test
    void aDepthOnlyPassIsCheaperThanTheAssembler() {
        VertexPullingLayout layout = layout();

        for (int vertex = 0; vertex < 1000; vertex++) {
            layout.fetch(vertex, new boolean[] {true, false, false, false});
        }

        assertEquals(0.0006, layout.msLoading(), 1e-12);
        assertEquals(0.0016, layout.msAssembler(), 1e-12);
        assertEquals(62.5, layout.savedPercent(), 1e-9);
    }

    @Test
    void aPassThatNeedsEverythingIsSlowerAndSaysSo() {
        // The per-load cost is higher than the per-fetch cost, so a draw that needs every attribute is slower
        // through this path and reports that rather than a saving.
        VertexPullingLayout layout = layout();

        for (int vertex = 0; vertex < 1000; vertex++) {
            layout.fetch(vertex, new boolean[] {true, true, true, true});
        }

        assertEquals(0L, layout.loadsSkipped());
        assertTrue(layout.savedPercent() < 0.0, "slower, and reported as such");
        assertEquals(-50.0, layout.savedPercent(), 1e-9);
    }

    @Test
    void theStrideIsAValueNotAPipeline() {
        // A section whose vertices are nine bytes and one whose are thirty six use the same shader, and the
        // difference is one constant.
        VertexPullingLayout small = new VertexPullingLayout(new int[] {4, 2, 2, 1});
        VertexPullingLayout large = new VertexPullingLayout(new int[] {12, 8, 8, 8});

        assertTrue(small.stride() != large.stride());
        assertEquals(13, small.stride());
        assertEquals(36, large.stride());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VertexPullingLayout(new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new VertexPullingLayout(new int[] {4, 0}));

        VertexPullingLayout layout = layout();
        assertThrows(IllegalArgumentException.class, () -> layout.offset(-1));
        assertThrows(IllegalArgumentException.class, () -> layout.offset(4));
        assertThrows(IllegalArgumentException.class,
                () -> layout.fetch(0, new boolean[] {true}));
    }

    @Test
    void aFreshLayoutReportsZeroNotNaN() {
        // A layout that has fetched nothing has technically avoided everything, but reporting that as a saving
        // would describe an unused one as though it had done something.
        VertexPullingLayout layout = layout();

        assertEquals(0.0, layout.savedPercent());
        assertEquals(0.0, layout.skippedPercent());
        assertEquals(0.0, layout.msLoading(), 1e-12);
        assertEquals(0L, layout.vertices());
        assertEquals(0L, layout.loadsSkipped());
        assertEquals(4, VertexPullingLayout.WORD_BYTES);
    }
}
