package net.amdfaster.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmptyLightSectionSkipperTest {

    private static byte[] zeros() {
        return new byte[EmptyLightSectionSkipper.SECTION_BYTES];
    }

    @Test
    void anAllZeroSectionIsSentAsAMarker() {
        // Most of a world's sections are unlit -- the stone under the surface, the sky above the build
        // limit -- and every one of those two kilobytes says "zero" two thousand and forty-eight times.
        EmptyLightSectionSkipper skipper = new EmptyLightSectionSkipper();

        assertTrue(skipper.isEmpty(zeros(), false));
        assertEquals(1L, skipper.skipped());
        assertEquals(0L, skipper.sent());
    }

    @Test
    void aSingleTorchInTheCornerMeansItIsNotEmpty() {
        // Not "mostly", not "the first and last". Calling it empty would leave that torch dark -- the player
        // would see a cavern lit by nothing, and would have no way to connect it to a network optimisation.
        EmptyLightSectionSkipper skipper = new EmptyLightSectionSkipper();
        byte[] values = zeros();
        values[values.length - 1] = 14;

        assertFalse(skipper.isEmpty(values, false));
        assertEquals(1L, skipper.sent());
        assertEquals(0L, skipper.skipped());
    }

    @Test
    void skyAndBlockLightAreCheckedSeparately() {
        // A section can be dark underground and lit from above, or lit by a torch in a sealed room with no
        // sky. Treating them as one would send both whenever either had a value.
        EmptyLightSectionSkipper skipper = new EmptyLightSectionSkipper();
        byte[] sky = zeros();
        sky[0] = 15;

        assertFalse(skipper.isEmpty(sky, true));
        assertTrue(skipper.isEmpty(zeros(), false));

        assertEquals(1L, skipper.sent());
        assertEquals(1L, skipper.skipped());
        assertEquals(0L, skipper.skySkipped(), "the sky one was sent");
        assertEquals(1L, skipper.blockSkipped());
    }

    @Test
    void anUndergroundColumnIsAlmostAllEmpty() {
        EmptyLightSectionSkipper skipper = new EmptyLightSectionSkipper();

        for (int section = 0; section < 24; section++) {
            skipper.isEmpty(zeros(), false);
            skipper.isEmpty(zeros(), true);
        }
        byte[] lit = zeros();
        lit[100] = 12;
        skipper.isEmpty(lit, false);

        assertEquals(49L, skipper.checked());
        assertEquals(48L, skipper.skipped());
        assertEquals(97.96, skipper.skippedPercent(), 0.01);
        assertEquals(48L * EmptyLightSectionSkipper.SECTION_BYTES, skipper.bytesSaved());
        assertEquals(98_304L, skipper.bytesSaved());
    }

    @Test
    void theSavingIsNetOfTheChecks() {
        // Reading two thousand and forty-eight bytes to decide not to send them is not free, and a skipper
        // whose check cost as much as the transfer would save nothing and should report that.
        EmptyLightSectionSkipper skipper = new EmptyLightSectionSkipper();

        for (int section = 0; section < 100; section++) {
            skipper.isEmpty(zeros(), false);
        }

        assertEquals(100 * EmptyLightSectionSkipper.MS_PER_SECTION
                - 100 * EmptyLightSectionSkipper.MS_PER_CHECK, skipper.msSaved(), 1e-9);
        assertEquals(1.9, skipper.msSaved(), 1e-9);
        assertEquals(95.0, skipper.savedPercent(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        EmptyLightSectionSkipper skipper = new EmptyLightSectionSkipper();

        assertThrows(IllegalArgumentException.class, () -> skipper.isEmpty(null, false));
    }

    @Test
    void aFreshSkipperReportsZeroNotNaN() {
        // A skipper that has seen nothing has technically avoided everything, but reporting that as a saving
        // would describe an unused one as though it had done something.
        EmptyLightSectionSkipper skipper = new EmptyLightSectionSkipper();

        assertEquals(0.0, skipper.skippedPercent());
        assertEquals(0.0, skipper.savedPercent());
        assertEquals(0.0, skipper.msSaved(), 1e-12);
        assertEquals(0L, skipper.checked());
        assertEquals(0L, skipper.bytesSaved());
        assertEquals(2048, EmptyLightSectionSkipper.SECTION_BYTES);
    }
}
