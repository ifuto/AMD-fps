package net.amdfaster.gpu;

import net.amdfaster.gpu.UpscalerBackendSelector.Backend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpscalerBackendSelectorTest {

    @Test
    void theMatrixPathIsUsedWhereItExists() {
        assertEquals(Backend.MATRIX, UpscalerBackendSelector.choose(true));
    }

    @Test
    void theDotProductIsTheFallback() {
        // Every GPU this project targets can do an eight-bit integer dot product, so that is the path
        // everything can share.
        assertEquals(Backend.DOT_PRODUCT, UpscalerBackendSelector.choose(false));
        assertEquals(8, UpscalerBackendSelector.DOT_LANES);
    }

    @Test
    void theMatrixPathIsFaster() {
        assertEquals(1.6, UpscalerBackendSelector.msPerFrame(Backend.DOT_PRODUCT), 1e-12);
        assertEquals(0.4, UpscalerBackendSelector.msPerFrame(Backend.MATRIX), 1e-12);
        assertEquals(4.0, UpscalerBackendSelector.MATRIX_SPEEDUP, 1e-12);
    }

    @Test
    void theBarIsHigherOnTheFallbackPath() {
        // It is worth it when the frame saved by rendering fewer pixels is worth more than the frame the
        // upscaler costs, and on the dot product path that costs more.
        assertFalse(UpscalerBackendSelector.worthEnabling(Backend.DOT_PRODUCT, 1.0),
                "1.0 ms saved does not cover a 1.6 ms upscaler");
        assertTrue(UpscalerBackendSelector.worthEnabling(Backend.MATRIX, 1.0),
                "and does cover a 0.4 ms one");
        assertTrue(UpscalerBackendSelector.worthEnabling(Backend.DOT_PRODUCT, 3.0));
    }

    @Test
    void theNetGainIsReportedEvenWhenNegative() {
        // A mod that quietly refuses to enable an upscaler leaves the player wondering why the setting does
        // nothing, and the number explains it.
        assertEquals(-0.6, UpscalerBackendSelector.netMsPerFrame(Backend.DOT_PRODUCT, 1.0), 1e-12);
        assertEquals(0.6, UpscalerBackendSelector.netMsPerFrame(Backend.MATRIX, 1.0), 1e-12);
        assertEquals(1.4, UpscalerBackendSelector.netMsPerFrame(Backend.DOT_PRODUCT, 3.0), 1e-12);
    }

    @Test
    void theBreakevenIsOneNumberPerPath() {
        // A caller can compare it against what the resolution scale actually saves rather than trying the
        // setting and measuring.
        assertEquals(1.6, UpscalerBackendSelector.breakevenSaving(Backend.DOT_PRODUCT), 1e-12);
        assertEquals(0.4, UpscalerBackendSelector.breakevenSaving(Backend.MATRIX), 1e-12);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> UpscalerBackendSelector.msPerFrame(null));
        assertThrows(IllegalArgumentException.class,
                () -> UpscalerBackendSelector.worthEnabling(Backend.MATRIX, -1.0));
        assertThrows(IllegalArgumentException.class,
                () -> UpscalerBackendSelector.netMsPerFrame(Backend.MATRIX, -1.0));
    }

    @Test
    void theFallbackIsNotAWorseResult() {
        // Both paths compute the same dot product; they differ in how many instructions it takes. So the
        // output is identical and the choice is a speed choice, not a quality one.
        assertEquals(UpscalerBackendSelector.choose(false), Backend.DOT_PRODUCT);
        assertTrue(UpscalerBackendSelector.msPerFrame(Backend.DOT_PRODUCT)
                > UpscalerBackendSelector.msPerFrame(Backend.MATRIX),
                "slower, but the same image");
    }
}
