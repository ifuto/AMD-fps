package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriangleStripPackerTest {

    @Test
    void aLongRunCostsOneIndexPerTriangleAfterTheFirst() {
        // A triangle list stores three indices per triangle. A strip stores three for the first and one for
        // every triangle after it.
        assertEquals(64, TriangleStripPacker.stripIndicesFor(new int[] {62}));
        assertEquals(186, 62 * TriangleStripPacker.INDICES_PER_TRIANGLE);
    }

    @Test
    void aFullMeshletCompressesRoughlyThreeToOne() {
        TriangleStripPacker packer = new TriangleStripPacker();

        assertTrue(packer.pack(64, 62));

        assertEquals(62L, packer.triangles());
        assertEquals(186L, packer.listIndices());
        assertEquals(64L, packer.stripIndices());
        assertEquals(65.59, packer.savedPercent(), 0.01);
    }

    @Test
    void eachExtraRunCostsABridge() {
        // The bridge is a triangle with a repeated vertex, which the rasteriser discards because it has no
        // area. That is not free: the bridge is an index too.
        assertEquals(40, TriangleStripPacker.stripIndicesFor(new int[] {10, 10, 10}));
        assertEquals(34, TriangleStripPacker.stripIndicesFor(new int[] {30}), "one run, no bridges");
    }

    @Test
    void shortRunsCanCostMoreThanTheList() {
        // A meshlet made of many short runs can end up with more bridges than it saved indices, and the honest
        // answer then is to keep the list.
        int strip = TriangleStripPacker.stripIndicesFor(new int[] {1, 1, 1, 1});
        int list = 4 * TriangleStripPacker.INDICES_PER_TRIANGLE;

        assertTrue(strip > list, "four separate triangles strip worse than they list");

        TriangleStripPacker packer = new TriangleStripPacker();
        assertFalse(packer.pack(strip, 4));
        assertEquals(1L, packer.keptAsList());
        assertEquals(0L, packer.packed());
    }

    @Test
    void emptyRunsAreSkipped() {
        assertEquals(12, TriangleStripPacker.stripIndicesFor(new int[] {10, 0, 0}));
        assertEquals(0, TriangleStripPacker.stripIndicesFor(new int[0]));
    }

    @Test
    void aSceneOfMeshletsIsMeasuredNotAssumed() {
        TriangleStripPacker packer = new TriangleStripPacker();

        for (int i = 0; i < 100; i++) {
            packer.pack(64, 62);
        }
        for (int i = 0; i < 20; i++) {
            packer.pack(16, 4);
        }

        assertEquals(120L, packer.packed());
        assertEquals(0L, packer.keptAsList());
        assertEquals(100 * 64L + 20 * 16L, packer.bytes());
        assertEquals(100 * 186L + 20 * 12L, packer.bytesAsList());
        assertEquals(64.33, packer.savedPercent(), 0.01,
                "6 720 bytes against 18 840 -- the twenty short meshlets barely strip");
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        TriangleStripPacker packer = new TriangleStripPacker();

        assertThrows(IllegalArgumentException.class, () -> packer.pack(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> packer.pack(10, -1));
        assertThrows(IllegalArgumentException.class,
                () -> TriangleStripPacker.stripIndicesFor(new int[] {-1}));
    }

    @Test
    void aFreshPackerReportsZeroNotNaN() {
        // A packer that has packed nothing has technically avoided everything, but reporting that as a saving
        // would describe an unused one as though it had done something.
        TriangleStripPacker packer = new TriangleStripPacker();

        assertEquals(0.0, packer.savedPercent());
        assertEquals(0L, packer.bytes());
        assertEquals(0L, packer.triangles());
        assertEquals(0L, packer.packed());
        assertEquals(0L, packer.keptAsList());
        assertEquals(62, TriangleStripPacker.MAX_MESHLET_VERTICES);
        assertEquals(1, TriangleStripPacker.BYTES_PER_INDEX);
    }
}
