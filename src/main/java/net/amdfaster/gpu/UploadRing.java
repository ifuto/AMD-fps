package net.amdfaster.gpu;

/**
 * A ring of per-frame slices inside one persistently mapped buffer, with in-flight tracking.
 *
 * <p>Two requirements meet here and they pull in opposite directions. Writes must not stall, so the
 * mapping stays open for the lifetime of the buffer rather than being mapped and unmapped per
 * upload -- AMD's guidance is that this memory is write-combined and must be written sequentially
 * and never read back, and a per-upload map/unmap pair is two driver calls on top of that. But the
 * GPU reads a slice asynchronously, so a slice cannot be overwritten until the frame that used it
 * has finished, or the geometry changes under a command buffer that has already been recorded.
 *
 * <p>The ring resolves it: enough slices for the frames in flight, and a slice returns to the free
 * list only when the fence says the GPU is done with it. Nothing waits on the CPU side; the fence is
 * polled, and if every slice is in flight the caller is told rather than blocked, because the right
 * response to "the GPU is behind" is to skip uploading a section this frame, not to stall the render
 * thread on it.
 *
 * <p>Fence values are timeline semaphore values, not binary fences. A timeline is what makes this
 * cheap: one semaphore carries a monotonically increasing value, so "release everything up to N" is
 * a comparison rather than a fence object per frame with its own wait. With binary fences the same
 * logic needs a pool, a reset per frame, and a wait per slice.
 *
 * <p>Not thread safe. Recording happens on the thread that owns the command buffer.
 */
public final class UploadRing {

    /** Slices by default: triple buffering, which covers two frames in flight plus one being written. */
    public static final int DEFAULT_SLICES = 3;

    private final int sliceCount;
    private final long bytesPerSlice;

    /** The timeline value each in-flight slice is waiting for; -1 when the slice is free. */
    private final long[] inFlightUntil;

    private int writeIndex;
    private long totalSubmitted;
    private long totalReleased;
    private int stalledFrames;

    public UploadRing(long bytesPerSlice) {
        this(DEFAULT_SLICES, bytesPerSlice);
    }

    public UploadRing(int sliceCount, long bytesPerSlice) {
        if (sliceCount < 2) {
            // One slice cannot overlap writing and reading at all, which defeats the point; the
            // caller would have to stall every frame.
            throw new IllegalArgumentException("a ring needs at least two slices: " + sliceCount);
        }
        if (bytesPerSlice <= 0) {
            throw new IllegalArgumentException("bytesPerSlice must be positive: " + bytesPerSlice);
        }
        this.sliceCount = sliceCount;
        this.bytesPerSlice = bytesPerSlice;
        this.inFlightUntil = new long[sliceCount];
        java.util.Arrays.fill(this.inFlightUntil, -1L);
    }

    public int sliceCount() {
        return this.sliceCount;
    }

    public long bytesPerSlice() {
        return this.bytesPerSlice;
    }

    /** Bytes in the whole backing buffer. */
    public long capacity() {
        return this.bytesPerSlice * this.sliceCount;
    }

    /**
     * Reserves the next slice for writing, or returns {@code -1} if every slice is still in flight.
     *
     * <p>A returned {@code -1} is not an error. It means the GPU has not caught up, and the caller
     * should defer this frame's uploads rather than block -- a chunk that appears one frame late is
     * invisible next to a hitch, which is what waiting would cause.
     *
     * @param signalledFence the timeline value the GPU has already reached, so slices from finished
     *                       frames are reclaimed here rather than needing a separate call first
     */
    public long acquire(long signalledFence) {
        releaseUpTo(signalledFence);
        // Scan forward from the write index rather than only looking at it. Slices are independent
        // regions, so any free one will do, and testing only writeIndex would stall with a free
        // slice sitting right there -- which also makes freeSliceCount() a liar, reporting space
        // available that acquire() refuses to hand out.
        for (int step = 0; step < this.sliceCount; step++) {
            int index = (this.writeIndex + step) % this.sliceCount;
            if (this.inFlightUntil[index] == -1L) {
                this.writeIndex = index;
                return (long) index * this.bytesPerSlice;
            }
        }
        this.stalledFrames++;
        return -1;
    }

    /**
     * Marks the slice most recently acquired as in flight until {@code fenceValue}.
     *
     * <p>Advances the write index, so the next {@link #acquire} looks at the following slice.
     */
    public void submit(long fenceValue) {
        if (fenceValue < 0) {
            throw new IllegalArgumentException("negative fence value: " + fenceValue);
        }
        if (this.inFlightUntil[this.writeIndex] != -1L) {
            throw new IllegalStateException("slice " + this.writeIndex
                    + " is already in flight; submit called twice without an acquire");
        }
        this.inFlightUntil[this.writeIndex] = fenceValue;
        this.totalSubmitted++;
        this.writeIndex = (this.writeIndex + 1) % this.sliceCount;
    }

    /**
     * Reclaims every slice whose frame has finished, meaning its fence value is at or below the
     * value the GPU has signalled.
     *
     * @param signalledFence the timeline semaphore's current value
     * @return how many slices were reclaimed
     */
    public int releaseUpTo(long signalledFence) {
        int released = 0;
        for (int i = 0; i < this.sliceCount; i++) {
            if (this.inFlightUntil[i] != -1L && this.inFlightUntil[i] <= signalledFence) {
                this.inFlightUntil[i] = -1L;
                released++;
            }
        }
        this.totalReleased += released;
        return released;
    }

    /** Slices currently held by the GPU. */
    public int inFlightCount() {
        int count = 0;
        for (long value : this.inFlightUntil) {
            if (value != -1L) {
                count++;
            }
        }
        return count;
    }

    /** Slices available for writing right now, without reclaiming anything. */
    public int freeSliceCount() {
        return this.sliceCount - inFlightCount();
    }

    /** Times {@link #acquire} found nothing free. A rising count means the GPU is the bottleneck. */
    public int stalledFrames() {
        return this.stalledFrames;
    }

    public long totalSubmitted() {
        return this.totalSubmitted;
    }

    public long totalReleased() {
        return this.totalReleased;
    }

    /**
     * Every submitted slice has been reclaimed.
     *
     * <p>The shutdown and resize condition. Dropping a buffer while a slice is in flight is a use
     * after free on the GPU side, which surfaces as corruption or a device loss long after the code
     * that caused it has run.
     */
    public boolean isIdle() {
        return inFlightCount() == 0;
    }
}
