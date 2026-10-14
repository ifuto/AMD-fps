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
 * uniform storage are per frame, so a frame owns a slot and the slot is one of a small ring.
 *
 * <h2>How many slots</h2>
 *
 * <p>At least {@code 1 + gpuTime / cpuTime}. Frame {@code f}'s GPU work finishes at
 * {@code (f+1)*cpu + gpu}, and frame {@code f+K} needs the same slot at {@code (f+K)*cpu}, so the slot
 * is free in time only when {@code K*cpu >= cpu + gpu}.
 *
 * <p>Simulated rather than reasoned about, because the intuition that two slots always overlap one
 * frame against one is wrong. At 4 ms of CPU against 6 ms of GPU, two slots stall on 99 frames out of
 * 200 and three stall on none. Two slots are enough only when the GPU is no slower than the CPU.
 * {@link #slotsFor} computes it.
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

    /**
     * Enough to overlap when the GPU is no slower than the CPU, which is the common case for a
     * CPU-bound game like Minecraft. A GPU-bound frame needs more; see {@link #slotsFor}.
     */
    public static final int DEFAULT_SLOTS = 2;

    /** The most this project uses. Three lets the CPU run ahead of a slow GPU frame. */
    public static final int MAX_SLOTS = 4;

    private final int slots;

    /** Per slot: true from acquire until complete. */
    private final boolean[] inFlight;

    /**
     * Per slot: true once the caller has submitted.
     *
     * <p>Separate from {@link #inFlight} because a slot is in flight from the moment it is acquired and
     * stays in flight until the GPU is done, while "submitted" is a one-way transition the caller makes
     * once. Checking only inFlight let a slot be submitted twice, which would mean two frames sharing
     * one command buffer.
     */
    private final boolean[] submitted;

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
        this.submitted = new boolean[slots];
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
        if (this.submitted[slot]) {
            throw new IllegalStateException("slot " + slot + " was already submitted");
        }
        this.submitted[slot] = true;
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
        this.submitted[slot] = false;
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

    /**
     * How many slots a workload needs to avoid stalling.
     *
     * @param cpuNanos the frame's CPU time
     * @param gpuNanos the frame's GPU time
     * @return a slot count between 1 and {@link #MAX_SLOTS}
     */
    public static int slotsFor(long cpuNanos, long gpuNanos) {
        if (cpuNanos <= 0) {
            throw new IllegalArgumentException("cpuNanos must be positive: " + cpuNanos);
        }
        if (gpuNanos < 0) {
            throw new IllegalArgumentException("gpuNanos must not be negative: " + gpuNanos);
        }
        // Ceiling of 1 + gpu/cpu, done in integers so there is no float rounding at the boundary.
        long needed = 1 + (gpuNanos + cpuNanos - 1) / cpuNanos;
        if (needed < 1) {
            return 1;
        }
        return (int) Math.min(needed, MAX_SLOTS);
    }

    /** Releases everything, for a device loss or a resize. */
    public void reset() {
        for (int i = 0; i < this.slots; i++) {
            this.inFlight[i] = false;
            this.submitted[i] = false;
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
     * How often the CPU had to wait for a slot, per frame actually issued.
     *
     * <p>The denominator is successful acquisitions rather than calls to {@link #acquire}, because a
     * caller that waits and retries calls acquire twice for one frame. Dividing by the call count made
     * a single-slot pipeline -- which stalls on every frame -- report about 50%, which reads as a
     * pipeline that mostly works.
     *
     * <p>Zero means the pipeline is genuinely pipelined. One hundred means the CPU waits for the GPU
     * every frame and the slots bought nothing, which usually means too many sync points rather than a
     * slow GPU.
     */
    public double stallRatePercent() {
        long issued = this.acquires - this.stalls;
        return issued == 0 ? 0.0 : 100.0 * this.stalls / issued;
    }
}
