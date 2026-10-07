package net.amdfaster.vk;

/**
 * A bump allocator over the per-frame windows of one persistently mapped buffer.
 *
 * <p>The APU and large-BAR path writes uniforms and dynamic indices straight into device-visible
 * memory, which removes the staging copy entirely. That only works if the CPU is never writing a
 * window the GPU is still reading, so the buffer is split into one window per frame in flight and
 * a window is reused only after its frame's fence has signalled.
 *
 * <p>The mapping is created once and never remapped: remapping a HOST_VISIBLE allocation on AMD
 * means the driver has to flush and invalidate, which is exactly the cost this avoids.
 */
public final class FrameRing {

    private final long windowBytes;
    private final int framesInFlight;
    private final long[] frameHighWater;

    private long cursor;
    private long framePeak;
    private int currentFrame = -1;
    private long totalReserved;
    private long totalWasted;

    /**
     * @param windowBytes    bytes each frame gets; the buffer is {@code windowBytes * framesInFlight}
     * @param framesInFlight normally 2 or 3
     */
    public FrameRing(long windowBytes, int framesInFlight) {
        if (windowBytes <= 0 || framesInFlight <= 0) {
            throw new IllegalArgumentException("windowBytes and framesInFlight must be positive");
        }
        this.windowBytes = windowBytes;
        this.framesInFlight = framesInFlight;
        this.frameHighWater = new long[framesInFlight];
        this.cursor = 0;
    }

    public long windowBytes() {
        return this.windowBytes;
    }

    public int framesInFlight() {
        return this.framesInFlight;
    }

    /** Total size of the backing buffer. */
    public long totalBytes() {
        return this.windowBytes * this.framesInFlight;
    }

    /** Bytes this frame has handed out, including alignment padding. */
    public long frameBytesUsed() {
        return this.cursor;
    }

    /** Largest window any frame has needed so far, for sizing the buffer. */
    public long peakWindowUsage() {
        long peak = 0;
        for (long v : this.frameHighWater) {
            peak = Math.max(peak, v);
        }
        return Math.max(peak, this.framePeak);
    }

    public long totalReserved() {
        return this.totalReserved;
    }

    /** Alignment padding given away so far; a large number means the callers are allocating badly. */
    public long totalWasted() {
        return this.totalWasted;
    }

    /**
     * Starts a frame's window. The caller must not call this for a frame whose GPU work is still in
     * flight; that is what the fence is for.
     */
    public void beginFrame(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= this.framesInFlight) {
            throw new IllegalArgumentException("frameIndex " + frameIndex + " outside 0.."
                    + (this.framesInFlight - 1));
        }
        this.currentFrame = frameIndex;
        this.cursor = 0;
        this.framePeak = 0;
    }

    /** Offset of the current window's start inside the backing buffer. */
    public long windowOffset() {
        if (this.currentFrame < 0) {
            throw new IllegalStateException("beginFrame has not been called");
        }
        return (long) this.currentFrame * this.windowBytes;
    }

    /**
     * @param bytes     how much is needed
     * @param alignment power-of-two alignment the offset must satisfy; 0 or 1 means unaligned
     * @return the offset inside the whole backing buffer, ready to write at
     * @throws IllegalStateException if the window cannot hold it, with how much was needed
     */
    public long reserve(long bytes, long alignment) {
        if (this.currentFrame < 0) {
            throw new IllegalStateException("beginFrame has not been called");
        }
        if (bytes <= 0) {
            throw new IllegalArgumentException("bytes must be positive");
        }
        long align = Math.max(1, alignment);
        if ((align & (align - 1)) != 0) {
            throw new IllegalArgumentException("alignment must be a power of two, got " + alignment);
        }

        long aligned = (this.cursor + align - 1) & ~(align - 1);
        this.totalWasted += aligned - this.cursor;
        if (aligned + bytes > this.windowBytes) {
            throw new IllegalStateException("frame " + this.currentFrame + " window overflow: needed "
                    + bytes + " at " + aligned + " in a " + this.windowBytes + " byte window (peak so far "
                    + peakWindowUsage() + ")");
        }
        this.cursor = aligned + bytes;
        this.framePeak = Math.max(this.framePeak, this.cursor);
        this.frameHighWater[this.currentFrame] =
                Math.max(this.frameHighWater[this.currentFrame], this.cursor);
        this.totalReserved += bytes;
        return windowOffset() + aligned;
    }
}
