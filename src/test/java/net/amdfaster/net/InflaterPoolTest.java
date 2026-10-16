package net.amdfaster.net;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InflaterPoolTest {

    private static byte[] compress(byte[] data) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            while (!deflater.finished()) {
                int n = deflater.deflate(chunk);
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /** Chunk-like data: highly repetitive, so it expands a long way. */
    private static byte[] chunkLike(int size) {
        byte[] data = new byte[size];
        Random random = new Random(7L);
        for (int i = 0; i < size; i += 64) {
            byte value = (byte) random.nextInt(4);
            java.util.Arrays.fill(data, i, Math.min(size, i + 64), value);
        }
        return data;
    }

    @Test
    void aRealPayloadRoundTrips() {
        byte[] original = chunkLike(200_000);
        byte[] compressed = compress(original);
        assertTrue(compressed.length < original.length / 4,
                "repetitive chunk data compresses well: " + compressed.length + " from "
                        + original.length);

        InflaterPool pool = new InflaterPool();
        byte[] out = new byte[InflaterPool.INITIAL_BUFFER];

        int n;
        try {
            n = pool.inflate(compressed, out);
        } catch (DataFormatException e) {
            throw new AssertionError(e);
        }

        assertEquals(original.length, n);
        byte[] result = java.util.Arrays.copyOf(pool.buffer(original.length), n);
        assertArrayEquals(original, result);
        assertEquals(original.length, pool.bytesInflated());
    }

    @Test
    void oneInflaterServesEveryPacket() {
        // The point of the class: without a pool each of these constructs an Inflater, which allocates
        // native memory, and a load at render distance ten is 10,584 chunk packets.
        byte[] original = chunkLike(50_000);
        byte[] compressed = compress(original);

        InflaterPool pool = new InflaterPool();
        for (int i = 0; i < 500; i++) {
            try {
                pool.inflate(compressed, new byte[InflaterPool.INITIAL_BUFFER]);
            } catch (DataFormatException e) {
                throw new AssertionError(e);
            }
        }

        assertEquals(500L, pool.acquires());
        assertEquals(1L, pool.created(), "one inflater, reused 500 times");
        assertEquals(499L, pool.allocationsAvoided());
        assertEquals(1, pool.pooled());
        assertEquals(0L, pool.discarded());
    }

    @Test
    void anInflaterThatThrewIsEndedRatherThanPooled() {
        // reset() clears the stream state, but after a failure mid-stream the caller cannot know that
        // the native state is consistent with what it believes. Returning it risks the next packet
        // producing garbage that looks valid, two packets later, with no connection to the cause.
        InflaterPool pool = new InflaterPool();

        assertThrows(DataFormatException.class,
                () -> pool.inflate(InflaterPool.notZlib(), new byte[1024]));

        assertEquals(1L, pool.discarded());
        assertEquals(1L, pool.ended());
        assertEquals(0, pool.pooled(), "nothing went back in the pool");

        // The next acquire makes a fresh one rather than reusing the broken one, which the following
        // test checks by counting constructions across a good, a bad and another good payload.
        assertEquals(1L, pool.created(), "only the one that was made and then discarded");
    }

    @Test
    void aGoodInflaterIsStillPooledAfterABadOne() {
        InflaterPool pool = new InflaterPool();
        byte[] compressed = compress(chunkLike(10_000));

        try {
            pool.inflate(compressed, new byte[InflaterPool.INITIAL_BUFFER]);
        } catch (DataFormatException e) {
            throw new AssertionError(e);
        }
        assertEquals(1, pool.pooled());

        try {
            pool.inflate(InflaterPool.notZlib(), new byte[1024]);
        } catch (DataFormatException expected) {
            // The bad payload, as intended.
        }
        assertEquals(1, pool.pooled(), "the good one survived");
        assertEquals(1L, pool.discarded());

        try {
            int n = pool.inflate(compressed, new byte[InflaterPool.INITIAL_BUFFER]);
            assertEquals(10_240, n, "and still works");
        } catch (DataFormatException e) {
            throw new AssertionError(e);
        }
        assertEquals(2L, pool.created(), "it was reused, not remade");
    }

    @Test
    void theBufferGrowsOnceAndIsKept() {
        // A fixed buffer would be grown and discarded repeatedly, since chunk data expands several
        // times. Growing once and keeping it is the difference between one allocation and dozens.
        InflaterPool pool = new InflaterPool();
        assertEquals(InflaterPool.INITIAL_BUFFER, pool.bufferCapacity());

        byte[] grown = pool.buffer(100_000);
        assertTrue(grown.length >= 100_000);
        assertTrue(pool.bufferGrowths() >= 1);

        int capacity = pool.bufferCapacity();
        pool.buffer(100_000);
        assertEquals(capacity, pool.bufferCapacity(), "no further growth for the same request");
    }

    @Test
    void aBufferPastTheRetentionLimitIsLetGo() {
        // One huge packet should not leave a megabyte allocated forever.
        InflaterPool pool = new InflaterPool();
        pool.buffer(InflaterPool.MAX_RETAINED_BUFFER * 2);
        assertTrue(pool.bufferCapacity() > InflaterPool.MAX_RETAINED_BUFFER);

        pool.trimBuffer();

        assertEquals(InflaterPool.INITIAL_BUFFER, pool.bufferCapacity());
    }

    @Test
    void concurrentHoldsGetSeparateInflaters() {
        // Two threads inflating at once must not share an inflater, or their streams interleave into
        // one buffer. Each holds its own until it releases.
        InflaterPool pool = new InflaterPool();

        Inflater first = pool.acquire();
        Inflater second = pool.acquire();
        Inflater third = pool.acquire();

        assertTrue(first != second);
        assertTrue(second != third);
        assertEquals(3L, pool.created());
        assertEquals(0, pool.pooled());

        pool.release(first, true);
        pool.release(second, true);
        assertEquals(2, pool.pooled());

        Inflater reused = pool.acquire();
        assertTrue(reused == second || reused == first, "a pooled one comes back");
        assertEquals(3L, pool.created(), "and no new one was made");
    }

    @Test
    void thePoolIsCapped() {
        InflaterPool pool = new InflaterPool();
        List<Inflater> held = new ArrayList<>();
        for (int i = 0; i < InflaterPool.MAX_POOLED + 4; i++) {
            held.add(pool.acquire());
        }
        for (Inflater inflater : held) {
            pool.release(inflater, true);
        }

        assertEquals(InflaterPool.MAX_POOLED, pool.pooled());
        assertEquals(4L, pool.ended(), "the four past the cap were ended");
    }

    @Test
    void closeEndsEverything() {
        InflaterPool pool = new InflaterPool();
        for (int i = 0; i < 4; i++) {
            pool.release(pool.acquire(), true);
        }
        assertEquals(4, pool.pooled());

        pool.close();

        assertEquals(0, pool.pooled());
        assertEquals(4L, pool.ended());
        assertEquals(InflaterPool.INITIAL_BUFFER, pool.bufferCapacity());
    }

    @Test
    void manyPacketsOfDifferentSizesAllRoundTrip() {
        InflaterPool pool = new InflaterPool();

        for (int size : new int[] {1, 100, 4096, 65_536, 300_000}) {
            byte[] original = chunkLike(size);
            byte[] compressed = compress(original);
            try {
                int n = pool.inflate(compressed, new byte[64]);
                assertEquals(size, n, "size " + size);
            } catch (DataFormatException e) {
                throw new AssertionError("size " + size, e);
            }
        }
        assertEquals(1L, pool.created(), "one inflater for all of them");
    }

    @Test
    void growingTheBufferKeepsWhatWasAlreadyInflated() {
        // The caller passes a small array of their own. When the pool switches to its shared buffer the
        // bytes decoded so far have to come across, or the result is silently truncated.
        byte[] original = chunkLike(120_000);
        byte[] compressed = compress(original);

        InflaterPool pool = new InflaterPool();
        int n;
        try {
            n = pool.inflate(compressed, new byte[32]);
        } catch (DataFormatException e) {
            throw new AssertionError(e);
        }

        assertEquals(original.length, n);
        assertArrayEquals(original,
                java.util.Arrays.copyOf(pool.buffer(original.length), n));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        InflaterPool pool = new InflaterPool();
        assertThrows(IllegalArgumentException.class, () -> pool.buffer(0));
        assertThrows(IllegalArgumentException.class, () -> pool.buffer(-1));
        assertEquals(8, InflaterPool.MAX_POOLED);
        assertEquals(16 * 1024, InflaterPool.INITIAL_BUFFER);
    }

    @Test
    void aFreshPoolReportsZeroNotNaN() {
        InflaterPool pool = new InflaterPool();
        assertEquals(0L, pool.acquires());
        assertEquals(0L, pool.created());
        assertEquals(0L, pool.allocationsAvoided());
        assertEquals(0L, pool.bytesInflated());
    }
}
