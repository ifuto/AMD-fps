package net.amdfaster.gpu;

import net.amdfaster.gpu.TextureUploadBatcher.Entry;
import net.amdfaster.gpu.TextureUploadBatcher.Result;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureUploadBatcherTest {

    private static byte[] pixels(int width, int height) {
        return new byte[width * height * 4];
    }

    private static Entry entry(int id) {
        return new Entry(id, 0, 16, 16, pixels(16, 16));
    }

    /** Records what it was asked to upload, in order. */
    private static final class Recording implements TextureUploadBatcher.Uploader {
        final List<Integer> ids = new ArrayList<>();

        @Override
        public boolean upload(Entry entry) {
            this.ids.add(entry.textureId());
            return true;
        }
    }

    @Test
    void texturesAreSentInBatchesNotOneAtATime() {
        // Every upload is a driver call with a fixed cost. Three hundred of them at 0.03 ms each is 9 ms
        // that has nothing to do with the bytes being moved.
        TextureUploadBatcher batcher = new TextureUploadBatcher();
        Recording uploader = new Recording();

        for (int i = 0; i < 300; i++) {
            batcher.submit(entry(i));
        }
        batcher.flush(uploader);

        assertEquals(300, uploader.ids.size());
        assertEquals(10, batcher.batches(), "300 over batches of 32");
        assertEquals(300L, batcher.uploaded());
        assertEquals(96.7, batcher.savedPercent(), 0.05);
    }

    @Test
    void orderIsPreservedSoAFailureCanBeAttributed() {
        // Returning results in completion order would make a failure impossible to trace back to the
        // texture that caused it.
        TextureUploadBatcher batcher = new TextureUploadBatcher();

        for (int i = 0; i < 5; i++) {
            batcher.submit(entry(i));
        }
        List<Result> results = batcher.flush(new Recording());

        assertEquals(5, results.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(i, results.get(i).textureId(), "result " + i);
        }
    }

    @Test
    void twoTexturesAreNeverSwapped() {
        // A block drawing with another block's texture is not subtle: it is visible on every face that
        // uses it, and it reads as a corrupted resource pack rather than a renderer mod.
        TextureUploadBatcher batcher = new TextureUploadBatcher();
        List<Entry> seen = new ArrayList<>();
        TextureUploadBatcher.Uploader capture = e -> {
            seen.add(e);
            return true;
        };

        Entry a = new Entry(7, 0, 16, 16, pixels(16, 16));
        Entry b = new Entry(9, 0, 32, 32, pixels(32, 32));
        batcher.submit(a);
        batcher.submit(b);
        batcher.flush(capture);

        assertEquals(7, seen.get(0).textureId());
        assertEquals(16, seen.get(0).width());
        assertEquals(9, seen.get(1).textureId());
        assertEquals(32, seen.get(1).width());
    }

    @Test
    void aFailureIsReportedPerEntryNotForTheWholeBatch() {
        // A partial batch is the worst outcome: some textures are on the GPU and some are not, and from
        // the outside the missing ones simply draw as whatever was there before, often black.
        TextureUploadBatcher batcher = new TextureUploadBatcher();
        TextureUploadBatcher.Uploader failsThird = e -> e.textureId() != 2;

        for (int i = 0; i < 5; i++) {
            batcher.submit(entry(i));
        }
        List<Result> results = batcher.flush(failsThird);

        assertEquals(5, results.size());
        assertTrue(results.get(0).uploaded());
        assertTrue(results.get(1).uploaded());
        assertFalse(results.get(2).uploaded(), "the one that failed");
        assertNotNull(results.get(2).failure());
        assertTrue(results.get(3).uploaded(), "and the rest still went");
        assertEquals(4L, batcher.uploaded());
        assertEquals(1L, batcher.failed());
    }

    @Test
    void anUploaderThatThrowsDoesNotLoseTheRestOfTheBatch() {
        TextureUploadBatcher batcher = new TextureUploadBatcher();
        TextureUploadBatcher.Uploader throwsOnSecond = e -> {
            if (e.textureId() == 1) {
                throw new IllegalStateException("device lost");
            }
            return true;
        };

        for (int i = 0; i < 4; i++) {
            batcher.submit(entry(i));
        }
        List<Result> results = batcher.flush(throwsOnSecond);

        assertEquals(4, results.size());
        assertFalse(results.get(1).uploaded());
        assertEquals("device lost", results.get(1).failure());
        assertTrue(results.get(3).uploaded());
    }

    @Test
    void flushingAnEmptyQueueDoesNothing() {
        TextureUploadBatcher batcher = new TextureUploadBatcher();

        assertTrue(batcher.flush(new Recording()).isEmpty());
        assertEquals(0L, batcher.batches());
    }

    @Test
    void theQueueIsClearedAfterFlushing() {
        TextureUploadBatcher batcher = new TextureUploadBatcher();
        batcher.submit(entry(1));

        batcher.flush(new Recording());

        assertEquals(0, batcher.pending());
    }

    @Test
    void aPartialBatchIsSentOnFlush() {
        TextureUploadBatcher batcher = new TextureUploadBatcher();
        for (int i = 0; i < 5; i++) {
            batcher.submit(entry(i));
        }
        assertEquals(5, batcher.pending(), "not yet full");

        batcher.flush(new Recording());

        assertEquals(1L, batcher.batches(), "and the partial batch counts as one call");
    }

    @Test
    void aMipLevelBeyondTheChainIsRejected() {
        // Writing past the end of the chain would write outside the allocated storage.
        assertEquals(5, TextureUploadBatcher.mipLevels(16, 16), "16 8 4 2 1");
        assertEquals(1, TextureUploadBatcher.mipLevels(1, 1));
        assertEquals(7, TextureUploadBatcher.mipLevels(64, 64));

        assertThrows(IllegalArgumentException.class,
                () -> new Entry(1, 5, 16, 16, pixels(16, 16)),
                "a 16x16 texture has no level 5");
    }

    @Test
    void aMipLevelNeedsEnoughDataForItsSize() {
        // A level-1 image of a 16x16 texture is 8x8.
        assertThrows(IllegalArgumentException.class, () -> new Entry(1, 1, 16, 16, new byte[16 * 16 * 4 - 1]));
        assertNotNull(new Entry(1, 1, 16, 16, pixels(8, 8)));
    }

    @Test
    void nonsenseEntriesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Entry(1, 0, 0, 16, pixels(16, 16)));
        assertThrows(IllegalArgumentException.class, () -> new Entry(1, 0, 16, 0, pixels(16, 16)));
        assertThrows(IllegalArgumentException.class, () -> new Entry(1, -1, 16, 16, pixels(16, 16)));
        assertThrows(IllegalArgumentException.class, () -> new Entry(1, 0, 16, 16, new byte[10]));
    }

    @Test
    void aFreshBatcherReportsZeroNotNaN() {
        TextureUploadBatcher batcher = new TextureUploadBatcher();

        assertEquals(0.0, batcher.savedPercent());
        assertEquals(0, batcher.pending());
        assertEquals(0.0, batcher.msSaved());
        assertEquals(32, TextureUploadBatcher.BATCH_SIZE);
    }
}
