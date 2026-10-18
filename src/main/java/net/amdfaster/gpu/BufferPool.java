package net.amdfaster.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Reuses GPU buffers instead of asking the driver for a new one every time a section is remeshed.
 *
 * <p>Remeshing a section retires its old buffers and needs new ones, and during fast streaming or a big
 * edit that traffic scales with the section churn rather than with anything the frame is doing. Each cycle
 * is a driver memory allocation and free, and those are not cheap in the way a heap allocation is cheap:
 * the count of individual allocations a driver must support can be as low as 1024, each one may be sized
 * for the worst case alignment, and freeing one on RADV walks libdrm's process-wide VA hole list. A
 * measured case put 85 to 96 per cent of all allocation calls in a renderer behind small per-object
 * buffers of exactly this shape -- a few kilobytes, created and destroyed constantly -- with the free path
 * alone accounting for most of the reported cost.
 *
 * <p>So buffers are handed back rather than destroyed, and the next request of a similar size takes one
 * back. Requests are rounded up to a power of two so that a pool holds interchangeable sizes rather than
 * accumulating one class per exact byte count, which is what lets a request take a buffer that is somewhat
 * larger than it asked for instead of finding nothing and allocating.
 *
 * <p>The part that cannot be skipped is the fence. A buffer whose contents are still queued for upload or
 * draw cannot be written to again, so a retired buffer goes on a pending list keyed by the frame it was
 * retired in and only becomes reusable once that frame's fence has signalled. Recycling one early is not a
 * slowdown, it is corruption: the new upload overwrites data a queued transfer has not read yet, and the
 * geometry that comes out belongs to neither section. The caller retires by frame and releases by fence
 * order, and this refuses to hand out anything that has not been released.
 *
 * <p>Above the largest class a buffer is not pooled. Caching one would pin its whole size for the life of
 * the pool, and for something that large a dedicated allocation that is destroyed on retire costs less
 * than the memory held idle waiting for a request that may not come.
 *
 * @see <a href="https://zeux.io/2020-02-27/writing-an-efficient-vulkan-renderer/">Writing an efficient
 *      Vulkan renderer -- drivers are only required to support 4096 allocations, they can be slow, and they
 *      waste memory on worst-case alignment, so suballocation is necessary</a>
 * @see <a href="https://github.com/joske/yserver/pull/179">a measured version of this exact fix -- power of
 *      two classes from 4 KiB to 256 KiB, a dedicated buffer above that, and a buffer returning only from
 *      the retire walk after the frame's fence has signalled</a>
 */
public final class BufferPool {

    /** The smallest class, in bytes. Anything below it rounds up to this. */
    public static final int MIN_CLASS_BYTES = 4 * 1024;

    /** The largest class. Above this a buffer is not pooled. */
    public static final int MAX_CLASS_BYTES = 256 * 1024;

    /** How many classes that is: 4, 8, 16, 32, 64, 128 and 256 KiB. */
    public static final int CLASS_COUNT = 7;

    /**
     * How much larger than the request a pooled buffer may be and still be handed out.
     *
     * <p>A quarter. Tight enough that a 4 KiB request does not hold a 256 KiB buffer idle, loose enough
     * that varying sizes reuse each other instead of each accumulating its own pool.
     */
    public static final double MAX_SLACK = 1.25;

    private final List<ArrayDeque<Slot>> classes = new ArrayList<>(CLASS_COUNT);
    private final List<ArrayDeque<Slot>> pending = new ArrayList<>(CLASS_COUNT);

    private long acquired;
    private long reused;
    private long allocatedFresh;
    private long retired;
    private long released;
    private long beyondMaxClass;

    /**
     * A pooled buffer.
     *
     * @param bytes        the class size, which is at least what was asked for
     * @param retiredFrame the frame it was handed back in, or -1 while it is in use
     */
    public record Slot(int bytes, long retiredFrame) {

        /** Whether this slot's contents may still be queued on the GPU. */
        public boolean pending(long releasedThroughFrame) {
            return this.retiredFrame > releasedThroughFrame;
        }
    }

    public BufferPool() {
        for (int i = 0; i < CLASS_COUNT; i++) {
            this.classes.add(new ArrayDeque<>());
            this.pending.add(new ArrayDeque<>());
        }
    }

    /** The class size a request of {@code bytes} is rounded up to, or -1 if it is above the largest. */
    public static int classFor(int bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("a buffer has a positive size: " + bytes);
        }
        int size = MIN_CLASS_BYTES;
        while (size < bytes) {
            if (size > MAX_CLASS_BYTES) {
                return -1;
            }
            size <<= 1;
        }
        return size > MAX_CLASS_BYTES ? -1 : size;
    }

    /** The index of a class size, which is how the pools are addressed internally. */
    public static int classIndex(int classBytes) {
        int index = Integer.numberOfTrailingZeros(classBytes / MIN_CLASS_BYTES);
        if (classBytes < MIN_CLASS_BYTES || index < 0 || index >= CLASS_COUNT
                || (MIN_CLASS_BYTES << index) != classBytes) {
            throw new IllegalArgumentException("not a class size: " + classBytes);
        }
        return index;
    }

    /**
     * Takes a buffer for {@code bytes}.
     *
     * <p>Looks first for a pooled buffer of the exact class, then for one up to {@link #MAX_SLACK} times
     * the request, and only allocates when nothing fits. A request above the largest class is not pooled
     * at all and reports that, so the caller can give it a dedicated allocation instead.
     *
     * @param releasedThroughFrame the newest frame whose fence has signalled; nothing retired after it is
     *                             available however long it has been sitting in the pool
     * @return the buffer to use, or null when the request is above the largest class
     */
    public Slot acquire(int bytes, long releasedThroughFrame) {
        int size = classFor(bytes);
        if (size < 0) {
            this.beyondMaxClass++;
            return null;
        }
        this.acquired++;
        int index = classIndex(size);
        for (int i = index; i < CLASS_COUNT; i++) {
            ArrayDeque<Slot> pool = this.classes.get(i);
            if (pool.isEmpty()) {
                continue;
            }
            Slot candidate = pool.peek();
            // The slack is against the class the request rounded up to, not the raw byte count. Measuring
            // it against the raw count would let a small request refuse the very buffer it was rounded up
            // for, which is a pool that never serves anything.
            if (candidate.bytes() > size * MAX_SLACK) {
                // A larger class would serve the request but would sit idle holding memory the frame
                // needs, so it stays pooled and this one is allocated instead.
                break;
            }
            this.reused++;
            return new Slot(pool.poll().bytes(), -1L);
        }
        this.allocatedFresh++;
        return new Slot(size, -1L);
    }

    /**
     * Hands a buffer back.
     *
     * <p>It does not become reusable here. Its contents may still be queued, so it waits until
     * {@link #releaseThrough} passes the frame it was retired in. Recycling it before then would let the
     * next upload overwrite data a queued transfer has not read, which shows up as geometry belonging to
     * neither section rather than as an error.
     *
     * @param bytes        the size it was acquired with
     * @param retiredFrame the frame being retired
     */
    public void retire(int bytes, long retiredFrame) {
        int size = classFor(bytes);
        if (size < 0) {
            // Not pooled, so the caller's dedicated allocation is destroyed instead. Nothing to track
            // beyond the count, which is what says how often that path is being taken.
            this.beyondMaxClass++;
            return;
        }
        this.retired++;
        this.pending.get(classIndex(size)).addLast(new Slot(size, retiredFrame));
    }

    /**
     * Makes everything retired up to and including {@code releasedThroughFrame} reusable.
     *
     * <p>Called once per frame after the fences are checked. The pending queues are in retirement order
     * and frames retire in order, so the walk stops at the first slot that is still in flight rather than
     * scanning the whole queue.
     *
     * @return how many buffers became reusable
     */
    public int releaseThrough(long releasedThroughFrame) {
        int freed = 0;
        for (int i = 0; i < CLASS_COUNT; i++) {
            ArrayDeque<Slot> queue = this.pending.get(i);
            ArrayDeque<Slot> pool = this.classes.get(i);
            Slot head;
            while ((head = queue.peek()) != null && head.retiredFrame() <= releasedThroughFrame) {
                queue.poll();
                pool.addLast(head);
                freed++;
                this.released++;
            }
        }
        return freed;
    }

    /**
     * Reports how many buffers are still waiting on a fence, and refuses to make them available.
     *
     * <p>Worth counting separately from the pooled total: a pool that looks full but is entirely pending
     * will allocate fresh buffers every frame, and the two counts together are what distinguishes that
     * from a pool that is genuinely too small.
     */
    public int pendingCount() {
        int total = 0;
        for (ArrayDeque<Slot> queue : this.pending) {
            total += queue.size();
        }
        return total;
    }

    /** Buffers currently reusable, across every class. */
    public int pooledCount() {
        int total = 0;
        for (ArrayDeque<Slot> pool : this.classes) {
            total += pool.size();
        }
        return total;
    }

    /** Bytes held by reusable buffers, which is the memory the pool is keeping warm. */
    public long pooledBytes() {
        long total = 0;
        for (ArrayDeque<Slot> pool : this.classes) {
            for (Slot slot : pool) {
                total += slot.bytes();
            }
        }
        return total;
    }

    /** How many acquisitions were served from the pool rather than from the driver. */
    public double reusePercent() {
        return this.acquired == 0 ? 0.0 : 100.0 * this.reused / this.acquired;
    }

    public long acquisitions() {
        return this.acquired;
    }

    public long reused() {
        return this.reused;
    }

    public long allocatedFresh() {
        return this.allocatedFresh;
    }

    public long retires() {
        return this.retired;
    }

    public long releases() {
        return this.released;
    }

    /** Retirements of buffers too large to pool, which is the dedicated-allocation path. */
    public long beyondMaxClass() {
        return this.beyondMaxClass;
    }

    /** Empties every pool and pending queue, dropping the buffers rather than recycling them. */
    public void clear() {
        for (ArrayDeque<Slot> pool : this.classes) {
            pool.clear();
        }
        for (ArrayDeque<Slot> queue : this.pending) {
            queue.clear();
        }
    }
}
