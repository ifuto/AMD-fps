package net.amdfaster.gpu;

/**
 * Hands out per-frame slots so the CPU can build frame N+1 while the GPU is still drawing frame N.
 *
 * <p>A frame has a CPU half and a GPU half. Run one after the other and a frame costing 4 ms of CPU
 * and 6 ms of GPU takes 10 ms. Run them overlapped and it takes 6, because the CPU is building the
 * next frame while the GPU works through this one -- a saving of 40%, and 50% when the two halves are
 * equal.
 *
 * <p>The overlap needs somewhere to put the next frame's work. Command buffers, descriptors and
 * uniform storage are per frame, so a frame owns a slot and the slot is one of a small ring. Two
 * slots are enough to overlap one frame of GPU work against one of CPU work; three lets the CPU run
 * ahead when the GPU has a slow frame, at the cost of one more frame of latency.
 *
 * <h2>Reusing a slot too early is corruption, not a stall</h2>
 *
 * <p>A slot can only be handed out again once the GPU has finished with it. Handing out a slot whose
 * command buffer is still being executed means the CPU overwrites commands the GPU is reading, which
 * produces a garbled frame or a device loss rather than an error the mod can catch. So
 * {@link #acquire} returns {@link #NO_SLOT} when every slot is in flight and the caller waits; it
 * never hands back a busy slot to save the caller the trouble.
 *
 * <h2>Stalling is the signal</h2>
 *
 * <p>A scheduler that stalls every frame is not overlapping anything, and the usual reasons are too
 * many sync points rather than a slow GPU. Nine fences in a frame is the ceiling this project works
 * to, and each one is a place the CPU waits. {@link #stallRate} reports how often the CPU had to wait
 * for a slot, which is the number that says whether the pipeline is actually pipelined.
 *
 * <p>Not thread safe. The GPU completing work is reported from whatever thread polls the fences, and
 * that thread owns this object for the duration.
 */
public final class FrameSlotScheduler {

    /** Every slot is in flight and the caller has to wait. */
    public static final int NO_SLOT = -1;

    /** Enough to overlap one frame of GPU work against one of CPU work. */
    public static final int DEFAULT_SLOTS = 2;

    /** The most this project uses. Three lets the CPU run ahead of a slow GPU frame. */
    public static final int MAX_SLOTS = 4;

    private final int slots;

    /** Per slot: true once submitted and not yet complete. */
    private final boolean[] inFlight;

    /** The slot acquire will try next, so slots are reused round robin. */
    private int cursor;

    private long acquires;
    private long stalls;
    private long submits;
    private long completes;
    private long rejected;

    public FrameSlotScheduler() {
        this(DEFAULT_SLOTS);
    }

    public FrameSlotScheduler(int slots) {
        if (slots < 1) {
            throw new IllegalArgumentException("a frame needs at least one slot: " + slots);
        }
        if (slots > MAX_SLOTS) {
            throw new IllegalArgumentException(
                    "more than " + MAX_SLOTS + " slots adds latency without adding overlap: " + slots);
        }
        this.slots = slots;
        this.inFlight = new boolean[slots];
    }

    /**
     * Takes a free slot.
     *
     * @return a slot index, or {@link #NO_SLOT} if every slot is in flight and the caller must wait
     */
    public int acquire() {
        this.acquires++;
        for (int i = 0; i < this.slots; i++) {
            int slot = (this.cursor + i) % this.slots;
            if (!this.inFlight[slot]) {
                this.inFlight[slot] = true;
                this.cursor = (slot + 1) % this.slots;
                return slot;
            }
        }
        this.stalls++;
        return NO_SLOT;
    }

    /**
     * Records that a slot's CPU work has been submitted to the GPU.
     *
     * @throws IllegalStateException if the slot was not acquired
     */
    public void submit(int slot) {
        checkSlot(slot);
        if (!this.inFlight[slot]) {
            throw new IllegalStateException("slot " + slot + " was not acquired");
        }
        this.submits++;
    }

    /**
     * Records that the GPU finished with a slot, freeing it.
     *
     * @throws IllegalStateException if the slot was not in flight, which would mean a completion
     *     arrived for work never submitted, or twice
     */
    public void complete(int slot) {
        checkSlot(slot);
        if (!this.inFlight[slot]) {
            throw new IllegalStateException("slot " + slot + " is not in flight");
        }
        this.inFlight[slot] = false;
        this.completes++;
    }

    /** Whether a slot is currently held. */
    public boolean isInFlight(int slot) {
        checkSlot(slot);
        return this.inFlight[slot];
    }

    /** How many slots are held. */
    public int inFlightCount() {
        int n = 0;
        for (boolean flight : this.inFlight) {
            if (flight) {
                n++;
            }
        }
        return n;
    }

    /**
     * Whether the next acquire would stall.
     *
     * <p>For a caller that would rather do something else than block.
     */
    public boolean wouldStall() {
        return inFlightCount() == this.slots;
    }

    /** Releases everything, for a device loss or a resize. */
    public void reset() {
        for (int i = 0; i < this.slots; i++) {
            this.inFlight[i] = false;
        }
        this.cursor = 0;
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= this.slots) {
            throw new IndexOutOfBoundsException("slot " + slot + " outside " + this.slots);
        }
    }

    public int slotCount() {
        return this.slots;
    }

    public long acquires() {
        return this.acquires;
    }

    /** Acquires that found no free slot. */
    public long stalls() {
        return this.stalls;
    }

    public long submits() {
        return this.submits;
    }

    public long completes() {
        return this.completes;
    }

    /** Slots handed out that were never submitted, which is a leak in the caller. */
    public long unsubmitted() {
        return this.acquires - this.stalls - this.submits;
    }

    /**
     * How often the CPU had to wait for a slot.
     *
     * <p>Zero means the pipeline is genuinely pipelined. Near one hundred means the CPU waits for the
     * GPU every frame and the slots bought nothing, which usually means too many sync points rather
     * than a slow GPU.
     */
    public double stallRatePercent() {
        return this.acquires == 0 ? 0.0 : 100.0 * this.stalls / this.acquires;
    }
}
