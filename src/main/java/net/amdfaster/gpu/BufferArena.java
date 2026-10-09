package net.amdfaster.gpu;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sub-allocates one large GPU allocation into many small ones.
 *
 * <p>Why this exists. A section's mesh data is a few hundred kilobyards at most, and a world at
 * render distance 16 holds on the order of a thousand live sections. Allocating a
 * {@code VkDeviceMemory} per section means a thousand allocations that come and go as the player
 * moves, and every one of them is a driver call that can take tens of microseconds and, on some
 * drivers, serialises against everything else the GPU is doing. The visible symptom is a hitch when
 * walking, which is precisely the moment the player is least tolerant of one.
 *
 * <p>So: one big allocation, and this hands out offsets inside it. The GPU sees one buffer; the
 * driver allocates once.
 *
 * <p><b>Alignment is not optional.</b> Vulkan requires a dynamic offset to be a multiple of
 * {@code minUniformBufferOffsetAlignment}, which is 256 on essentially every implementation and is
 * the reason a naive bump allocator that just adds sizes produces offsets the driver rejects. The
 * alignment is a constructor parameter rather than a constant because the value is a device limit
 * and has to be read, not assumed.
 *
 * <p>Freeing coalesces adjacent free blocks, so a section that is rebuilt in place returns its
 * space to the same hole rather than slowly shredding the arena into unusable slivers. Without
 * coalescing, an arena that has seen enough churn reports plenty of free bytes and still fails to
 * satisfy a request larger than any single gap -- which reads as an out-of-memory error in a buffer
 * that is mostly empty.
 *
 * <p>Not thread safe. Chunk rebuilds happen on worker threads; each gets its own arena, or they
 * share one under a lock the caller owns.
 */
public final class BufferArena {

    /** A free block too small to ever be used is not worth keeping in the list. */
    private static final int MIN_FREE_BLOCK = 1;

    private final long capacity;
    private final int alignment;

    /** Free blocks by start offset. Ordered so coalescing only has to look at neighbours. */
    private final TreeMap<Long, Integer> freeBlocks = new TreeMap<>();

    private long allocatedBytes;
    private long highWaterBytes;
    private int allocationCount;
    private int freeCount;
    private int failedAllocations;

    /**
     * @param capacity  size of the backing allocation in bytes
     * @param alignment every returned offset is a multiple of this; must be a power of two
     */
    public BufferArena(long capacity, int alignment) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("alignment must be a positive power of two: " + alignment);
        }
        this.capacity = capacity;
        this.alignment = alignment;
        this.freeBlocks.put(0L, (int) Math.min(capacity, Integer.MAX_VALUE));
    }

    /** An arena sized for a whole section's worth of streams, at the usual 256-byte alignment. */
    public static BufferArena forSections(int sectionCount, int bytesPerSection) {
        return new BufferArena((long) sectionCount * bytesPerSection, 256);
    }

    public long capacity() {
        return this.capacity;
    }

    public int alignment() {
        return this.alignment;
    }

    /**
     * Reserves {@code bytes} and returns the offset to write at, or {@code -1} if no aligned gap is
     * large enough.
     *
     * <p>Returns a sentinel rather than throwing because running out of arena is a normal event
     * rather than a bug: the caller grows the arena or evicts a distant section, and both are
     * ordinary paths. What would be a bug is silently handing back an overlapping offset, which is
     * what makes the invariants below worth asserting.
     */
    public long allocate(int bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("bytes must be positive: " + bytes);
        }
        Iterator<Map.Entry<Long, Integer>> it = this.freeBlocks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Integer> entry = it.next();
            long start = entry.getKey();
            int size = entry.getValue();
            long aligned = alignUp(start);
            long padding = aligned - start;
            if (size - padding < bytes) {
                continue;
            }
            // Remove first, then re-insert the two remainders. Mutating the map through the entry
            // while iterating it would be a ConcurrentModificationException.
            it.remove();
            if (padding > 0) {
                this.freeBlocks.put(start, (int) padding);
            }
            long rest = size - padding - bytes;
            if (rest >= MIN_FREE_BLOCK) {
                this.freeBlocks.put(aligned + bytes, (int) rest);
            }

            this.allocatedBytes += bytes;
            this.allocationCount++;
            long end = aligned + bytes;
            if (end > this.highWaterBytes) {
                this.highWaterBytes = end;
            }
            return aligned;
        }
        this.failedAllocations++;
        return -1;
    }

    /**
     * Returns a previously allocated range to the arena, coalescing it with any free neighbour.
     *
     * @param offset the offset {@link #allocate} returned
     * @param bytes  the size it was allocated with; freeing a different size corrupts the arena
     */
    public void free(long offset, int bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("bytes must be positive: " + bytes);
        }
        if (offset < 0 || offset + bytes > this.capacity) {
            throw new IllegalArgumentException("range " + offset + ".." + (offset + bytes)
                    + " is outside the arena of " + this.capacity);
        }
        long start = offset;
        long end = offset + bytes;

        Map.Entry<Long, Integer> before = this.freeBlocks.lowerEntry(start);
        if (before != null && before.getKey() + before.getValue() == start) {
            start = before.getKey();
            end = start + before.getValue() + bytes;
            this.freeBlocks.remove(before.getKey());
        }
        Map.Entry<Long, Integer> after = this.freeBlocks.higherEntry(start);
        if (after != null && after.getKey() == end) {
            end = after.getKey() + after.getValue();
            this.freeBlocks.remove(after.getKey());
        }

        this.freeBlocks.put(start, (int) (end - start));
        this.allocatedBytes -= bytes;
        this.freeCount++;
    }

    /** Drops every allocation and returns the arena to one contiguous block. */
    public void reset() {
        this.freeBlocks.clear();
        this.freeBlocks.put(0L, (int) Math.min(this.capacity, Integer.MAX_VALUE));
        this.allocatedBytes = 0;
        this.allocationCount = 0;
        this.freeCount = 0;
        this.failedAllocations = 0;
        // highWaterBytes is deliberately kept: it is the peak the arena has ever reached, which is
        // what sizing decisions need, and resetting it would hide the very spike that matters.
    }

    private long alignUp(long offset) {
        long mask = this.alignment - 1L;
        return (offset + mask) & ~mask;
    }

    /** Bytes currently handed out. */
    public long allocatedBytes() {
        return this.allocatedBytes;
    }

    /** Bytes free, including space that is unusable because of alignment padding. */
    public long freeBytes() {
        return this.capacity - this.allocatedBytes;
    }

    /**
     * The largest single contiguous gap that could satisfy a request, after alignment.
     *
     * <p>This is the number that explains an allocation failure in an arena that still reports free
     * bytes. Total free space says whether the arena is full; this says whether it is
     * <em>shredded</em>, and those are different problems with different fixes.
     */
    public long largestFreeBlock() {
        long largest = 0;
        for (Map.Entry<Long, Integer> entry : this.freeBlocks.entrySet()) {
            long usable = entry.getValue() - (alignUp(entry.getKey()) - entry.getKey());
            if (usable > largest) {
                largest = usable;
            }
        }
        return largest;
    }

    /**
     * Free bytes that no aligned allocation can ever use, because they sit before an aligned
     * boundary inside a free block.
     */
    public long alignmentWaste() {
        long waste = 0;
        for (Map.Entry<Long, Integer> entry : this.freeBlocks.entrySet()) {
            waste += alignUp(entry.getKey()) - entry.getKey();
        }
        return waste;
    }

    /** Highest offset ever reached, across resets. Use it to size the arena next time. */
    public long highWaterBytes() {
        return this.highWaterBytes;
    }

    public int allocationCount() {
        return this.allocationCount;
    }

    public int freeCount() {
        return this.freeCount;
    }

    public int failedAllocations() {
        return this.failedAllocations;
    }

    /** Number of separate free gaps. One means the arena is unfragmented. */
    public int freeBlockCount() {
        return this.freeBlocks.size();
    }

    /**
     * Checks the arena's internal consistency: free blocks are ordered, non-overlapping, in bounds,
     * and together with the allocated bytes account for the whole capacity.
     *
     * <p>Exposed rather than private so a test can assert it after an arbitrary sequence of
     * allocations and frees. An allocator whose bookkeeping drifts does not fail loudly -- it
     * eventually returns an offset that overlaps a live one, and two sections draw each other's
     * geometry.
     */
    public boolean isConsistent() {
        long previousEnd = -1;
        long freeTotal = 0;
        for (Map.Entry<Long, Integer> entry : this.freeBlocks.entrySet()) {
            long start = entry.getKey();
            int size = entry.getValue();
            if (size <= 0 || start < previousEnd || start + size > this.capacity) {
                return false;
            }
            previousEnd = start + size;
            freeTotal += size;
        }
        return freeTotal + this.allocatedBytes == this.capacity;
    }
}
