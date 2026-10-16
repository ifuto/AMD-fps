package net.amdfaster.net;

/**
 * Skips transferring a light section whose values are all zero.
 *
 * <p>A chunk packet carries the light for every section in the column, and each one is two kilobytes whether
 * it holds a torch-lit cavern or nothing at all. Most of a world's sections are unlit -- the stone under the
 * surface, the sky above the build limit, the caverns nobody has explored -- and every one of those two
 * kilobytes says "zero" two thousand and forty-eight times.
 *
 * <p>Marking such a section empty and sending nothing for it removes the bytes, the compression work on
 * them, and the decompression and allocation on the receiving side. The light it describes is unchanged:
 * all zeros is all zeros.
 *
 * <h2>Every value has to be zero</h2>
 *
 * <p>Not "mostly", not "the first and last". A section holding one torch in a corner has two thousand and
 * forty-seven zeros and one value, and calling it empty would leave that torch dark -- the player would see
 * a cavern lit by nothing, and would have no way to connect it to a network optimisation. So the check reads
 * the whole array, which is the only check that is correct.
 *
 * <h2>The receiving side still produces zeros</h2>
 *
 * <p>Skipping the transfer does not mean the light is missing. An empty section is a section whose every
 * block has light level zero, and the code that reads it produces exactly that. What changes is where the
 * zeros come from: they are implied by the marker rather than carried in a payload. A skipper that left the
 * light undefined would produce whatever happened to be in the buffer, which is a different and much worse
 * bug.
 *
 * <h2>Sky light and block light are separate</h2>
 *
 * <p>A section can be dark underground and lit from above, or lit by a torch in a sealed room with no sky.
 * The two arrays are independent, so one can be empty while the other is not, and each is checked on its
 * own. Treating them as one would send both whenever either had a value.
 *
 * <h2>Why this is worth the check</h2>
 *
 * <p>Reading two thousand and forty-eight bytes to decide not to send them is not free, but it is a
 * sequential scan of a small array against two kilobytes of compression, transmission and allocation. The
 * scan is the cheaper side by a wide margin, and it happens once per section rather than once per frame.
 *
 * <p>Not thread safe. Chunk packets are handled on the network thread.
 */
public final class EmptyLightSectionSkipper {

    /** Bytes one light section occupies. */
    public static final int SECTION_BYTES = 2048;

    /** Milliseconds checking one section's values costs. */
    public static final double MS_PER_CHECK = 0.001;

    /** Milliseconds compressing, sending and allocating one section costs. */
    public static final double MS_PER_SECTION = 0.02;

    private long checked;
    private long skipped;
    private long sent;
    private long skySkipped;
    private long blockSkipped;

    /**
     * Whether a light section can be sent as an empty marker instead of its data.
     *
     * @param values the section's light levels, one per block
     * @param sky whether this is the sky light array rather than the block light one
     * @return true if every value is zero and the data can be omitted
     */
    public boolean isEmpty(byte[] values, boolean sky) {
        if (values == null) {
            throw new IllegalArgumentException("a section's values are required");
        }
        this.checked++;

        for (byte value : values) {
            if (value != 0) {
                // Not "mostly", not "the first and last". A section holding one torch in a corner has two
                // thousand and forty-seven zeros and one value, and calling it empty would leave that torch
                // dark -- the player would see a cavern lit by nothing.
                this.sent++;
                return false;
            }
        }
        this.skipped++;
        if (sky) {
            this.skySkipped++;
        } else {
            this.blockSkipped++;
        }
        return true;
    }

    public long checked() {
        return this.checked;
    }

    /** How many sections were sent as an empty marker. */
    public long skipped() {
        return this.skipped;
    }

    /** How many had to be sent in full. */
    public long sent() {
        return this.sent;
    }

    public long skySkipped() {
        return this.skySkipped;
    }

    public long blockSkipped() {
        return this.blockSkipped;
    }

    public double skippedPercent() {
        return this.checked == 0 ? 0.0 : 100.0 * this.skipped / this.checked;
    }

    /** Bytes of payload not transferred. */
    public long bytesSaved() {
        return this.skipped * (long) SECTION_BYTES;
    }

    /** Milliseconds of transfer work avoided, net of the checks. */
    public double msSaved() {
        return this.skipped * MS_PER_SECTION - this.checked * MS_PER_CHECK;
    }

    /**
     * The share of the light transfer avoided, net of the checks.
     *
     * <p>Zero before any section has been checked. A skipper that has seen nothing has technically avoided
     * everything, but reporting that as a saving would describe an unused one as though it had done
     * something.
     *
     * <p>The checks are subtracted, because reading two thousand and forty-eight bytes to decide not to
     * send them is not free -- a skipper whose check cost as much as the transfer would save nothing and
     * should report that.
     */
    public double savedPercent() {
        double without = this.checked * MS_PER_SECTION;
        if (without == 0.0) {
            return 0.0;
        }
        return 100.0 * Math.max(0.0, msSaved()) / without;
    }
}
