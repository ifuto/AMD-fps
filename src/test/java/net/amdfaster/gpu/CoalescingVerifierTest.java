package net.amdfaster.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.amdfaster.gpu.CoalescingVerifier.Verdict;
import org.junit.jupiter.api.Test;

/**
 * The line counts here are counted, not estimated: each case enumerates the byte range every lane reads
 * and takes the union of the lines those ranges fall in.
 */
class CoalescingVerifierTest {

    @Test
    void denseReadsAtTheElementStrideCoalesceCompletely() {
        // Thirty-two lanes taking consecutive 8 byte elements want 256 contiguous bytes, which is four
        // 64 byte lines and nothing wasted. This is the layout the position and attribute buffers already
        // have, so the check confirms them rather than finding a problem to fix.
        assertEquals(4, CoalescingVerifier.linesTouched(32, 8, 8, 64, 0));
        assertEquals(8, CoalescingVerifier.linesTouched(64, 8, 8, 64, 0));
        assertEquals(2, CoalescingVerifier.linesTouched(32, 4, 4, 64, 0));
        assertEquals(1, CoalescingVerifier.linesTouched(32, 2, 2, 64, 0));
        assertEquals(8, CoalescingVerifier.linesTouched(32, 16, 16, 64, 0));
    }

    @Test
    void readingPartOfAWiderStrideThrowsHalfOfEveryLineAway() {
        // The same 32 lanes reading 4 bytes each at an 8 byte stride still want 128 useful bytes and
        // still touch four lines, because the gaps between what they read are inside those lines and the
        // fetch is whole-line. Half the bandwidth is gone and the geometry is still correct, which is why
        // nothing else would report it.
        int lines = CoalescingVerifier.linesTouched(32, 8, 4, 64, 0);

        assertEquals(4, lines);
        assertEquals(128, 32 * 4);
        assertEquals(256, lines * 64);
    }

    @Test
    void anUnalignedSlotStartAddsALine() {
        // A read starting 32 bytes into a line and running 128 bytes spans three lines where an aligned
        // one spans two. The bytes fetched went up by half while the bytes wanted did not move.
        assertEquals(2, CoalescingVerifier.linesTouched(32, 4, 4, 64, 0));
        assertEquals(3, CoalescingVerifier.linesTouched(32, 4, 4, 64, 32));
        assertEquals(9, CoalescingVerifier.linesTouched(32, 16, 16, 64, 16), "512 bytes from offset 16");
        assertEquals(2, CoalescingVerifier.linesTouched(32, 2, 2, 64, 4), "64 bytes from offset 4");
    }

    @Test
    void theSlotStrideDecidesHowManyStartOffsetsExist() {
        // Meshlet n starts at n times the slot stride, so the offsets repeat with period
        // lineBytes / gcd(stride, lineBytes). A stride that is a whole number of lines aligns every
        // meshlet; one that is not cycles, and the offsets that straddle a boundary are the costly ones.
        assertEquals(1, CoalescingVerifier.distinctStartOffsets(1984, 64), "position: 1984 = 31 x 64");
        assertEquals(2, CoalescingVerifier.distinctStartOffsets(992, 64), "light: 992 = 15.5 x 64");
        assertEquals(4, CoalescingVerifier.distinctStartOffsets(16, 64), "cull record");
        assertEquals(16, CoalescingVerifier.distinctStartOffsets(4, 64), "orientation");
        assertEquals(16, CoalescingVerifier.distinctStartOffsets(372, 64), "index: 62 quads x 6 bytes");

        assertTrue(CoalescingVerifier.slotAligned(1984, 64));
        assertFalse(CoalescingVerifier.slotAligned(992, 64));
    }

    @Test
    void theLightSlotAlternatesBetweenTwoOffsets() {
        // 992 mod 64 is 32, so even meshlets start on a line and odd ones start halfway into one.
        assertEquals(0, CoalescingVerifier.slotOffset(992, 0, 64));
        assertEquals(32, CoalescingVerifier.slotOffset(992, 1, 64));
        assertEquals(0, CoalescingVerifier.slotOffset(992, 2, 64));
        assertEquals(32, CoalescingVerifier.slotOffset(992, 3, 64));
    }

    /** A layout with {@code meshlets} slots. Only the count matters here: the strides are constants. */
    private static MeshletGpuLayout layout(int meshlets) {
        int faces = net.amdfaster.mesh.Orientation.values().length;
        return new MeshletGpuLayout(meshlets, new int[faces], new int[faces]);
    }

    @Test
    void everyStreamOfASectionIsChecked() {
        MeshletGpuLayout layout = layout(3);
        Verdict[] verdicts = CoalescingVerifier.verifySection(layout);

        assertEquals(6, verdicts.length);
        for (Verdict verdict : verdicts) {
            assertEquals(32, verdict.wave());
            assertEquals(CoalescingVerifier.LINE_L0_BYTES, verdict.lineBytes());
            assertTrue(verdict.efficiency() > 0.0, verdict.stream());
            assertTrue(verdict.efficiencyWorst() <= verdict.efficiency() + 1e-12, verdict.stream());
        }
    }

    @Test
    void thePositionAndAttributeBuffersAreAlreadyPerfect() {
        MeshletGpuLayout layout = layout(3);
        Verdict[] verdicts = CoalescingVerifier.verifySection(layout);

        Verdict position = verdicts[0];
        Verdict attribute = verdicts[1];

        assertEquals("position", position.stream());
        assertEquals(1984, position.slotStride(), "248 vertices x 8 bytes");
        assertEquals(4, position.linesIdeal());
        assertEquals(4, position.linesWorst());
        assertEquals(1, position.distinctStartOffsets(), "1984 is a whole number of 64 byte lines");
        assertEquals(1.0, position.efficiency(), 1e-12);
        assertEquals(1.0, position.efficiencyWorst(), 1e-12);
        assertTrue(position.coalesced());
        assertEquals(0, position.extraLines());

        assertEquals("attribute", attribute.stream());
        assertTrue(attribute.coalesced());
    }

    @Test
    void theLightBufferLosesAThirdOfItsBandwidthOnOddMeshlets() {
        MeshletGpuLayout layout = layout(3);
        Verdict light = CoalescingVerifier.verifySection(layout)[2];

        assertEquals("light", light.stream());
        assertEquals(992, light.slotStride(), "248 vertices x 4 bytes");
        assertEquals(2, light.distinctStartOffsets());
        assertEquals(2, light.linesIdeal());
        assertEquals(3, light.linesWorst());
        assertEquals(1, light.extraLines());
        assertFalse(light.coalesced());
        assertEquals(1.0, light.efficiency(), 1e-12, "even meshlets are aligned");
        assertEquals(2.0 / 3.0, light.efficiencyWorst(), 1e-12, "odd meshlets fetch 192 for 128");
    }

    @Test
    void theIndexBufferIsTheWorstCaseAtHalf() {
        // A 372 byte slot is 5.8125 lines, so fifteen of the sixteen start offsets straddle a boundary
        // and a wave's 64 byte read costs two lines instead of one. The index buffer is also the one
        // stream whose access pattern is not dense to begin with, since a draw reads whichever vertices
        // its quads named, so this is the ceiling on what fixing the stride would recover.
        MeshletGpuLayout layout = layout(3);
        Verdict index = CoalescingVerifier.verifySection(layout)[5];

        assertEquals("index", index.stream());
        assertEquals(372, index.slotStride(), "62 quads x 6 index bytes");
        assertEquals(16, index.distinctStartOffsets());
        assertEquals(1, index.linesIdeal());
        assertEquals(2, index.linesWorst());
        assertFalse(index.coalesced());
        assertEquals(0.5, index.efficiencyWorst(), 1e-12);
    }

    @Test
    void theCullRecordLosesOnlyItsFirstLine() {
        MeshletGpuLayout layout = layout(3);
        Verdict cull = CoalescingVerifier.verifySection(layout)[3];

        assertEquals("cull record", cull.stream());
        assertEquals(8, cull.linesIdeal());
        assertEquals(9, cull.linesWorst());
        assertEquals(8.0 / 9.0, cull.efficiencyWorst(), 1e-12, "512 useful bytes in 576 fetched");
    }

    @Test
    void theWorkgroupIsTwoWave32s() {
        assertEquals(64, CoalescingVerifier.workgroupLanes());
        assertEquals(2, CoalescingVerifier.workgroupLanes() / CoalescingVerifier.WAVE32);
    }

    @Test
    void nonsenseInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CoalescingVerifier.linesTouched(0, 8, 8, 64, 0));
        assertThrows(IllegalArgumentException.class,
                () -> CoalescingVerifier.linesTouched(32, 0, 8, 64, 0));
        assertThrows(IllegalArgumentException.class,
                () -> CoalescingVerifier.linesTouched(32, 8, 0, 64, 0));
        assertThrows(IllegalArgumentException.class,
                () -> CoalescingVerifier.linesTouched(32, 8, 8, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> CoalescingVerifier.linesTouched(32, 8, 8, 64, -1));
        assertThrows(IllegalArgumentException.class,
                () -> CoalescingVerifier.distinctStartOffsets(0, 64));
        assertThrows(IllegalArgumentException.class,
                () -> CoalescingVerifier.slotOffset(992, -1, 64));
        assertThrows(IllegalArgumentException.class,
                () -> CoalescingVerifier.verify(null, 32, 992, 4, 64));
    }
}
