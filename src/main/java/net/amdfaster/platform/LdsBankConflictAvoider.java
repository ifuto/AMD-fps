package net.amdfaster.platform;

/**
 * Pads a shared-memory stride so the lanes of a wave do not land on the same bank.
 *
 * <p>Local memory is split into banks, and a wave reads one value per lane per cycle when the lanes land on
 * different banks. When several lanes address the same bank the reads are serialised, and a kernel that reads
 * a shared array with a stride equal to the bank count puts every lane on the same bank every time -- which
 * turns one cycle into thirty-two and is invisible in the source, because the code looks like an ordinary
 * array read.
 *
 * <p>Adding one element to the stride spreads the lanes across the banks and the conflict disappears.
 *
 * <h2>The padding is one element, not one bank</h2>
 *
 * <p>What matters is that consecutive lanes land on consecutive banks, and that is a property of the stride
 * relative to the bank count. Adding one element changes the stride by exactly one bank, which is the
 * smallest change that removes the conflict, and adding more would waste memory without helping.
 *
 * <h2>The padding costs memory, which costs occupancy</h2>
 *
 * <p>A padded array is bigger, and local memory limits how many workgroups fit on a unit. So removing a bank
 * conflict can lower occupancy, and a kernel that was already limited by local memory rather than by the
 * conflict would come out slower. The padding is worth it when the conflict is on the critical path and the
 * extra bytes still fit.
 *
 * <h2>The bank count is the architecture's, not a guess</h2>
 *
 * <p>RDNA4 has sixty-four banks of four bytes; earlier RDNA parts differ. A stride padded for one is not
 * padded for the other, and a kernel written against a guessed count would be conflict-free on the machine it
 * was tested on and conflicted everywhere else.
 *
 * <h2>Why a power-of-two stride is the trap</h2>
 *
 * <p>Arrays in shared memory are almost always sized in powers of two, because that is how tiles are shaped,
 * and every power of two is a multiple of the bank count or a divisor of it. So the conflict is the common
 * case rather than the accident, and the fix is the exception that has to be applied deliberately.
 *
 * <p>Thread safety: the padding is computed at pipeline creation, on the thread that builds the pipelines.
 *
 * <p>Reference: AMD's gfx1201 ISA notes give sixty-four banks of four bytes each, and the RDNA performance
 * guide describes bank conflicts in shared memory. This is an independent implementation of the padding those
 * facts imply.
 */
public final class LdsBankConflictAvoider {

    /** Banks in an RDNA4 compute unit's local memory. */
    public static final int BANKS_RDNA4 = 64;

    /** Bytes per bank. */
    public static final int BYTES_PER_BANK = 4;

    /**
     * How many distinct banks the lanes of a wave land on.
     *
     * <p>Thirty-two means no conflict at all; one means every lane is on the same bank and the reads are
     * serialised thirty-two deep.
     *
     * @param strideElements the stride between consecutive lanes, in elements
     * @param lanes how many lanes the wave has
     * @param banks how many banks the local memory has
     */
    public static int distinctBanks(int strideElements, int lanes, int banks) {
        if (strideElements < 1) {
            throw new IllegalArgumentException("a stride of at least one: " + strideElements);
        }
        if (lanes < 1) {
            throw new IllegalArgumentException("at least one lane: " + lanes);
        }
        if (banks < 1) {
            throw new IllegalArgumentException("at least one bank: " + banks);
        }
        boolean[] used = new boolean[banks];
        int count = 0;

        for (int lane = 0; lane < lanes; lane++) {
            int bank = Math.floorMod(lane * strideElements, banks);
            if (!used[bank]) {
                used[bank] = true;
                count++;
            }
        }
        return count;
    }

    /**
     * How many cycles a wave's read takes, given how the lanes land.
     *
     * <p>One cycle per lane that shares the busiest bank, so a conflict-free read costs one and a fully
     * conflicted one costs the lane count.
     */
    public static int cycles(int strideElements, int lanes, int banks) {
        int distinct = distinctBanks(strideElements, lanes, banks);
        return (lanes + distinct - 1) / distinct;
    }

    /**
     * Whether a stride causes any conflict at all.
     *
     * <p>A power-of-two stride against a power-of-two bank count always does, which is why the conflict is
     * the common case rather than the accident -- arrays in shared memory are sized in powers of two because
     * that is how tiles are shaped.
     */
    public static boolean conflicts(int strideElements, int lanes, int banks) {
        return distinctBanks(strideElements, lanes, banks) < lanes;
    }

    /**
     * The padded stride that removes the conflict.
     *
     * <p>One element more than the original, which changes the stride by exactly one bank -- the smallest
     * change that spreads the lanes. Adding more would waste memory without helping.
     *
     * @return the smallest padding of up to four elements that removes the conflict, or the original stride
     *     if none does
     */
    public static int paddedStride(int strideElements, int lanes, int banks) {
        for (int padding = 0; padding <= 4; padding++) {
            int candidate = strideElements + padding;
            if (!conflicts(candidate, lanes, banks)) {
                return candidate;
            }
        }
        // Nothing small works, so the caller keeps the original and eats the conflict rather than padding
        // blindly, which would cost memory for nothing.
        return strideElements;
    }

    /**
     * The extra bytes the padding costs for an array of a given number of rows.
     *
     * <p>A padded array is bigger, and local memory limits how many workgroups fit on a unit, so removing a
     * bank conflict can lower occupancy. A kernel already limited by local memory rather than by the conflict
     * would come out slower.
     *
     * @param rows how many rows the array has
     * @param paddingElements how many elements were added to the stride
     * @param bytesPerElement the size of one element
     */
    public static int extraBytes(int rows, int paddingElements, int bytesPerElement) {
        if (rows < 0 || paddingElements < 0 || bytesPerElement < 1) {
            throw new IllegalArgumentException(
                    "a non-negative size is required: " + rows + ", " + paddingElements + ", "
                            + bytesPerElement);
        }
        return rows * paddingElements * bytesPerElement;
    }

    /**
     * The speedup the padding gives, as a factor.
     *
     * <p>Zero when the stride did not conflict, so a change that was not needed is not reported as a win.
     */
    public static double speedup(int originalStride, int paddedStride, int lanes, int banks) {
        int before = cycles(originalStride, lanes, banks);
        int after = cycles(paddedStride, lanes, banks);
        if (before == after) {
            return 0.0;
        }
        return (double) before / after;
    }
}
