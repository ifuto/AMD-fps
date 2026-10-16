package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshletQuantizerTest {

    private static MeshletQuantizer grid() {
        MeshletQuantizer quantizer = new MeshletQuantizer();
        quantizer.setGlobalGrid(0.0, 0.0, 0.0, 65535.0, 65535.0, 65535.0);
        return quantizer;
    }

    @Test
    void aSharedVertexDequantizesTheSameFromBothMeshlets() {
        // A vertex on the boundary between two meshlets is stored twice, and if the two produce different
        // floats the triangles that shared it no longer share it -- the seam opens into a visible crack.
        MeshletQuantizer quantizer = grid();

        assertTrue(quantizer.agreesAcrossMeshlets(1234.5, 0, 100));
        assertTrue(quantizer.agreesAcrossMeshlets(40000.0, 500, 3000));
        assertTrue(quantizer.agreesAcrossMeshlets(0.0, 0, 65535));
    }

    @Test
    void theRoundTripIsExactOnTheGrid() {
        // Nothing in the round trip is a float operation on a value that was already quantized, so there is no
        // accumulated error to differ between meshlets.
        MeshletQuantizer quantizer = grid();
        int offset = 256;

        for (int value : new int[] {0, 1, 1000, 32768, 65535}) {
            int local = quantizer.quantize(value, 0.0, quantizer.step(), offset);
            double back = quantizer.dequantize(local, offset, 0.0, quantizer.step());
            assertEquals((double) value, back, 1e-9, "value " + value);
        }
    }

    @Test
    void theMeshletOffsetIsAnInteger() {
        // Quantized rather than left as a float, so subtracting it is an integer subtraction. A scheme that
        // computed the offset in floating point would reintroduce exactly the discrepancy the shared grid
        // removes.
        MeshletQuantizer quantizer = grid();

        assertEquals(1000, quantizer.meshletOffset(1000.0, 0.0, quantizer.step()));
        assertEquals(0, quantizer.meshletOffset(0.0, 0.0, quantizer.step()));
    }

    @Test
    void quantizingHalvesThePositionStorage() {
        MeshletQuantizer quantizer = grid();

        quantizer.quantize(100.0, 0.0, quantizer.step(), 0);
        quantizer.endMeshlet();

        assertEquals(6 * 1000L, quantizer.bytesFor(1000));
        assertEquals(12 * 1000L, quantizer.bytesAsFloats(1000));
        assertEquals(50.0, quantizer.savedPercent(1000), 1e-9);
        assertEquals(6, MeshletQuantizer.BYTES_QUANTIZED);
        assertEquals(12, MeshletQuantizer.BYTES_FLOAT);
    }

    @Test
    void theStepIsTheFinestPositionTheGridCanRepresent() {
        MeshletQuantizer quantizer = new MeshletQuantizer();
        quantizer.setGlobalGrid(0.0, 0.0, 0.0, 65535.0, 0.0, 0.0);

        assertEquals(1.0, quantizer.step(), 1e-12, "one unit per step over 65535 steps");
    }

    @Test
    void aCoarserMeshHasACoarserStep() {
        // A grid sized for the whole mesh has a larger step than one sized for a meshlet. That is the trade: a
        // crack is a visible defect and a slightly coarser position is not visible at all.
        MeshletQuantizer coarse = new MeshletQuantizer();
        coarse.setGlobalGrid(0.0, 0.0, 0.0, 655_350.0, 0.0, 0.0);

        MeshletQuantizer fine = grid();

        assertTrue(coarse.step() > fine.step());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        MeshletQuantizer quantizer = new MeshletQuantizer();

        assertThrows(IllegalArgumentException.class,
                () -> quantizer.setGlobalGrid(10.0, 0.0, 0.0, 5.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> quantizer.quantize(1.0, 0.0, 0.0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> quantizer.meshletOffset(1.0, 0.0, 0.0));
    }

    @Test
    void aFreshQuantizerReportsZeroNotNaN() {
        // A quantizer that has quantized nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        MeshletQuantizer quantizer = grid();

        assertEquals(0.0, quantizer.savedPercent(1000));
        assertEquals(0L, quantizer.quantized());
        assertEquals(0L, quantizer.meshlets());
    }
}
