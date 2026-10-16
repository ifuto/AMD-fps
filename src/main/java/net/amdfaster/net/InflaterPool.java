package net.amdfaster.net;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reuses inflaters and their output buffers instead of making a new one per packet.
 *
 * <p>{@code java.util.zip.Inflater} allocates native memory in its constructor and frees it in
 * {@code end()}. A client that inflates a packet, ends the inflater, and repeats does a native
 * allocation and free for every packet -- and a load at render distance ten is 10,584 chunk packets
 * before anything else arrives. {@code reset()} is the alternative: it puts the stream back to its
 * initial state while keeping the native allocation, which is exactly what a pool wants.
 *
 * <p>The output buffer matters as much. Chunk data expands several times when decompressed, so a
 * fixed-size buffer would be grown and discarded repeatedly. This one grows and is kept, so the
 * allocation happens once and then never again.
 *
 * <h2>An inflater that failed must not go back in the pool</h2>
 *
 * <p>If a stream throws {@code DataFormatException} part way through, the inflater's internal state is
 * undefined -- {@code reset()} clears the stream state but the caller cannot know how much of the
 * native dictionary is still consistent with what it believes. Returning it risks the next packet
 * producing garbage that looks like valid data. So {@link #release} takes a flag, and a failed
 * inflater is ended rather than pooled. Ending one is cheap next to inflating a chunk, and the
 * alternative is corruption that appears two packets later with no connection to the one that caused
 * it.
 *
 * <h2>What this does not do</h2>
 *
 * <p>It does not decompress faster. The zlib algorithm is the zlib algorithm, and a faster inflater
 * would need native code -- libdeflate is roughly twice the throughput of zlib -- which this project
 * cannot load without shipping a binary per platform. What is available without that is not paying for
 * the same work twice, which is this.
 *
 * <p>Not thread safe as a whole. Each thread that inflates concurrently needs its own pool, or the
 * caller has to synchronise; handing the same inflater to two threads would interleave two streams
 * into one buffer.
 */
public final class InflaterPool {

    /** Starting size of the output buffer, in bytes. */
    public static final int INITIAL_BUFFER = 16 * 1024;

    /**
     * The largest buffer kept between uses.
     *
     * <p>One huge packet should not leave a megabyte allocated forever. Buffers past this are let go
     * after the use rather than kept, which costs a reallocation next time and keeps the steady-state
     * footprint bounded.
     */
    public static final int MAX_RETAINED_BUFFER = 1024 * 1024;

    /** The most inflaters held at once. */
    public static final int MAX_POOLED = 8;

    private final List<Inflater> pool = new ArrayList<>();

    private byte[] buffer = new byte[INITIAL_BUFFER];

    private long acquires;
    private long releases;
    private long created;
    private long ended;
    private long discarded;
    private long bufferGrowths;
    private long bytesInflated;

    /**
     * Takes an inflater, making one only if the pool is empty.
     */
    public Inflater acquire() {
        this.acquires++;
        if (!this.pool.isEmpty()) {
            return this.pool.remove(this.pool.size() - 1);
        }
        this.created++;
        return new Inflater();
    }

    /**
     * Returns an inflater.
     *
     * @param inflater the inflater to return
     * @param clean false if it threw mid-stream, in which case it is ended rather than pooled
     */
    public void release(Inflater inflater, boolean clean) {
        this.releases++;
        if (!clean || this.pool.size() >= MAX_POOLED) {
            inflater.end();
            this.ended++;
            if (!clean) {
                this.discarded++;
            }
            return;
        }
        inflater.reset();
        this.pool.add(inflater);
    }

    /**
     * The shared output buffer, grown if needed.
     *
     * @param needed how many bytes the caller wants room for
     * @return a buffer of at least that many bytes
     */
    public byte[] buffer(int needed) {
        if (needed <= 0) {
            throw new IllegalArgumentException("a buffer holds at least one byte: " + needed);
        }
        if (this.buffer.length >= needed) {
            return this.buffer;
        }
        int size = this.buffer.length;
        while (size < needed) {
            size *= 2;
        }
        this.buffer = new byte[size];
        this.bufferGrowths++;
        return this.buffer;
    }

    /**
     * Inflates a whole payload.
     *
     * @param compressed the compressed bytes
     * @param out filled with the decompressed bytes; grown by {@link #buffer} if too small
     * @return how many bytes were written
     * @throws DataFormatException if the payload is not valid zlib data
     */
    public int inflate(byte[] compressed, byte[] out) throws DataFormatException {
        Inflater inflater = acquire();
        boolean clean = true;
        try {
            inflater.setInput(compressed);
            int total = 0;
            while (!inflater.finished()) {
                if (total == out.length) {
                    // Needs more room. Growing here rather than guessing a size up front is what keeps
                    // a chunk packet from reallocating several times on the way to its real size.
                    byte[] bigger = buffer(Math.max(out.length * 2, total + 1));
                    if (bigger != out) {
                        // The bytes already inflated have to come across. The caller may have passed
                        // their own array, and switching to the shared buffer without copying would
                        // silently drop everything decoded so far.
                        System.arraycopy(out, 0, bigger, 0, total);
                    }
                    out = bigger;
                }
                int n = inflater.inflate(out, total, out.length - total);
                if (n == 0 && inflater.finished()) {
                    break;
                }
                if (n == 0 && inflater.needsInput()) {
                    throw new DataFormatException("the payload ended before the stream did");
                }
                total += n;
            }
            this.bytesInflated += total;
            return total;
        } catch (DataFormatException | RuntimeException e) {
            clean = false;
            throw e;
        } finally {
            release(inflater, clean);
        }
    }

    /** Drops everything, for a disconnect. */
    public void close() {
        for (Inflater inflater : this.pool) {
            inflater.end();
            this.ended++;
        }
        this.pool.clear();
        this.buffer = new byte[INITIAL_BUFFER];
    }

    /** How many inflaters are pooled right now. */
    public int pooled() {
        return this.pool.size();
    }

    /** How large the retained buffer is. */
    public int bufferCapacity() {
        return this.buffer.length;
    }

    /** Lets the buffer go if it grew past what is worth keeping. */
    public void trimBuffer() {
        if (this.buffer.length > MAX_RETAINED_BUFFER) {
            this.buffer = new byte[INITIAL_BUFFER];
        }
    }

    public long acquires() {
        return this.acquires;
    }

    public long releases() {
        return this.releases;
    }

    /** Inflaters constructed, which is the number pooling is meant to keep down. */
    public long created() {
        return this.created;
    }

    /** Inflaters ended, either because they failed or the pool was full. */
    public long ended() {
        return this.ended;
    }

    /** Inflaters discarded because they threw mid-stream. */
    public long discarded() {
        return this.discarded;
    }

    public long bufferGrowths() {
        return this.bufferGrowths;
    }

    public long bytesInflated() {
        return this.bytesInflated;
    }

    /**
     * How many native allocations pooling saved.
     *
     * <p>Without a pool every acquire constructs one.
     */
    public long allocationsAvoided() {
        return Math.max(0, this.acquires - this.created);
    }

    /** A payload that is not valid zlib, for testing the failure path. */
    public static byte[] notZlib() {
        byte[] junk = new byte[32];
        Arrays.fill(junk, (byte) 0x7F);
        return junk;
    }
}
