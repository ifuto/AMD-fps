package net.amdfaster.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LdsBankConflictAvoiderTest {

    private static final int LANES = 32;
    private static final int BANKS = LdsBankConflictAvoider.BANKS_RDNA4;

    @Test
    void aConsecutiveStrideHasNoConflict() {
        // A wave reads one value per lane per cycle when the lanes land on different banks.
        assertEquals(32, LdsBankConflictAvoider.distinctBanks(1, LANES, BANKS));
        assertEquals(1, LdsBankConflictAvoider.cycles(1, LANES, BANKS));
        assertFalse(LdsBankConflictAvoider.conflicts(1, LANES, BANKS));
    }

    @Test
    void aStrideEqualToTheBankCountConflictsBadly() {
        // A kernel that reads a shared array with a stride equal to the bank count puts every lane on the
        // same bank every time, which turns one cycle into thirty-two and is invisible in the source.
        assertEquals(1, LdsBankConflictAvoider.distinctBanks(64, LANES, BANKS));
        assertEquals(32, LdsBankConflictAvoider.cycles(64, LANES, BANKS));
        assertTrue(LdsBankConflictAvoider.conflicts(64, LANES, BANKS));
    }

    @Test
    void aStrideThatIsHalfTheBankCountStillConflicts() {
        assertEquals(2, LdsBankConflictAvoider.distinctBanks(32, LANES, BANKS));
        assertEquals(16, LdsBankConflictAvoider.cycles(32, LANES, BANKS));
    }

    @Test
    void oneElementOfPaddingRemovesTheConflict() {
        // Adding one element changes the stride by exactly one bank, which is the smallest change that
        // spreads the lanes, and adding more would waste memory without helping.
        assertEquals(33, LdsBankConflictAvoider.paddedStride(32, LANES, BANKS));
        assertEquals(65, LdsBankConflictAvoider.paddedStride(64, LANES, BANKS));
        assertFalse(LdsBankConflictAvoider.conflicts(33, LANES, BANKS));
        assertFalse(LdsBankConflictAvoider.conflicts(65, LANES, BANKS));
    }

    @Test
    void aStrideThatAlreadyWorksIsNotPadded() {
        assertEquals(1, LdsBankConflictAvoider.paddedStride(1, LANES, BANKS));
        assertEquals(33, LdsBankConflictAvoider.paddedStride(33, LANES, BANKS));
    }

    @Test
    void theSpeedupIsReportedOnlyWhenThePaddingWasNeeded() {
        // A change that was not needed is not reported as a win.
        assertEquals(16.0, LdsBankConflictAvoider.speedup(32, 33, LANES, BANKS), 1e-12);
        assertEquals(32.0, LdsBankConflictAvoider.speedup(64, 65, LANES, BANKS), 1e-12);
        assertEquals(0.0, LdsBankConflictAvoider.speedup(1, 1, LANES, BANKS), 1e-12);
    }

    @Test
    void thePaddingCostsMemory() {
        // A padded array is bigger, and local memory limits how many workgroups fit on a unit, so removing a
        // bank conflict can lower occupancy.
        assertEquals(256, LdsBankConflictAvoider.extraBytes(64, 1, 4));
        assertEquals(1024, LdsBankConflictAvoider.extraBytes(64, 4, 4));
        assertEquals(0, LdsBankConflictAvoider.extraBytes(64, 0, 4));
    }

    @Test
    void aPowerOfTwoStrideAgainstAPowerOfTwoBankCountAlwaysConflicts() {
        // Arrays in shared memory are almost always sized in powers of two, because that is how tiles are
        // shaped, so the conflict is the common case rather than the accident.
        for (int stride : new int[] {2, 4, 8, 16, 32, 64, 128}) {
            assertTrue(LdsBankConflictAvoider.conflicts(stride, LANES, BANKS),
                    "stride " + stride + " conflicts");
        }
    }

    @Test
    void theBankCountIsTheArchitectures() {
        assertEquals(64, LdsBankConflictAvoider.BANKS_RDNA4);
        assertEquals(4, LdsBankConflictAvoider.BYTES_PER_BANK);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LdsBankConflictAvoider.distinctBanks(0, LANES, BANKS));
        assertThrows(IllegalArgumentException.class,
                () -> LdsBankConflictAvoider.distinctBanks(1, 0, BANKS));
        assertThrows(IllegalArgumentException.class,
                () -> LdsBankConflictAvoider.distinctBanks(1, LANES, 0));
        assertThrows(IllegalArgumentException.class,
                () -> LdsBankConflictAvoider.extraBytes(-1, 1, 4));
        assertThrows(IllegalArgumentException.class,
                () -> LdsBankConflictAvoider.extraBytes(1, 1, 0));
    }
}
