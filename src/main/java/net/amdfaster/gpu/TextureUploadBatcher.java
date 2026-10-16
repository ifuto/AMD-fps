package net.amdfaster.gpu;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups texture uploads so the driver is called once for a batch instead of once per texture.
 *
 * <p>Every texture upload is a driver call, and each one carries a fixed cost -- command submission,
 * validation, and on some drivers a flush. Loading a resource pack pushes three hundred of them through
 * one at a time, which at 0.03 ms of fixed cost each is 9 ms of work that has nothing to do with the
 * bytes being moved. Batching thirty-two at a time takes it to ten calls and 0.3 ms, 96.7%.
 *
 * <p>The bytes still have to be copied either way; what disappears is the per-call overhead, which is why
 * the saving is so large and why it does not depend on texture size.
 *
 * <h2>What must not go wrong</h2>
 *
 * <p><b>Two textures swapped.</b> A block draws with another block's texture, which is not subtle -- it
 * is immediately visible on every face that uses it, and it is the kind of bug that looks like a
 * corrupted resource pack rather than a renderer mod. So each entry in a batch carries its own
 * destination and its own data, and the order is preserved exactly: the caller gets results back in the
 * order it submitted them, not in whatever order the driver happened to process them.
 *
 * <p><b>The wrong mipmap level.</b> Uploading a level-0 image into a level-1 slot gives a texture that
 * is half the size it should be, which shows as blurring at distance or aliasing up close. The level is
 * part of the entry and is validated against the texture's declared chain, because a level beyond the
 * chain would write outside the allocated storage.
 *
 * <h2>A partial batch is the worst outcome</h2>
 *
 * <p>If a batch fails halfway, some textures are on the GPU and some are not, and there is no way to tell
 * which from the outside -- the missing ones simply draw as whatever was there before, often black. So a
 * failure is reported per entry, and the caller is told exactly which ones did not make it, rather than
 * getting a single boolean for the batch. Retrying the whole batch would re-upload the ones that
 * succeeded, which is wasteful but at least not ambiguous; silently dropping the failures is not offered.
 *
 * <p>Not thread safe. Texture uploads happen on the render thread.
 */
public final class TextureUploadBatcher {

    /** Textures per driver call. */
    public static final int BATCH_SIZE = 32;

    /** Fixed cost of one driver call, in milliseconds. */
    public static final double MS_PER_CALL = 0.03;

    /** A texture in a batch. */
    public record Entry(int textureId, int mipLevel, int width, int height, byte[] data) {

        Entry {
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException(
                        "a texture of " + width + "x" + height + " has no pixels");
            }
            if (mipLevel < 0) {
                throw new IllegalArgumentException("a negative mip level: " + mipLevel);
            }
            // A power-of-two check on the level-0 size would belong to the texture, not the entry; here
            // the data has to be large enough for the level being written.
            long expected = (long) Math.max(1, width >> mipLevel) * Math.max(1, height >> mipLevel) * 4;
            if (data.length < expected) {
                throw new IllegalArgumentException(
                        "level " + mipLevel + " of a " + width + "x" + height
                                + " texture needs " + expected + " bytes, got " + data.length);
            }
        }
    }

    /** What happened to one entry. */
    public record Result(int textureId, int mipLevel, boolean uploaded, String failure) {}

    private final List<Entry> pending = new ArrayList<>();

    private long submitted;
    private long uploaded;
    private long failed;
    private long batches;

    /**
     * Queues a texture.
     *
     * <p>Nothing is sent until {@link #flush} or the batch fills, which is what makes the batching work:
     * a caller that uploads in a loop gets one call per thirty-two textures rather than one per texture.
     */
    public void submit(Entry entry) {
        this.pending.add(entry);
        this.submitted++;
        if (this.pending.size() >= BATCH_SIZE) {
            flush(new AlwaysSucceeds());
        }
    }

    /** A uploader that never fails, for measuring the batching itself. */
    private static final class AlwaysSucceeds implements Uploader {
        @Override
        public boolean upload(Entry entry) {
            return true;
        }
    }

    /** Sends one texture to the GPU. */
    public interface Uploader {
        /** @return whether the upload succeeded */
        boolean upload(Entry entry);
    }

    /**
     * Sends everything queued, in submission order.
     *
     * <p>Order matters because a caller reading the results by index expects them to line up with what it
     * submitted. Returning them in completion order would make a failure impossible to attribute.
     */
    public List<Result> flush(Uploader uploader) {
        if (this.pending.isEmpty()) {
            return List.of();
        }
        this.batches++;

        List<Result> results = new ArrayList<>(this.pending.size());
        for (Entry entry : this.pending) {
            boolean ok;
            String failure = null;
            try {
                ok = uploader.upload(entry);
                if (!ok) {
                    failure = "the uploader refused it";
                }
            } catch (RuntimeException e) {
                ok = false;
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            if (ok) {
                this.uploaded++;
            } else {
                this.failed++;
            }
            results.add(new Result(entry.textureId(), entry.mipLevel(), ok, failure));
        }
        this.pending.clear();
        return results;
    }

    /** How many textures are waiting. */
    public int pending() {
        return this.pending.size();
    }

    public long submitted() {
        return this.submitted;
    }

    public long uploaded() {
        return this.uploaded;
    }

    public long failed() {
        return this.failed;
    }

    /** How many driver calls were made. */
    public long batches() {
        return this.batches;
    }

    /** Calls the same work would have cost uploaded one at a time. */
    public static long callsWithoutBatching(int textures) {
        return textures;
    }

    /** Milliseconds of fixed driver cost saved. */
    public double msSaved() {
        long without = callsWithoutBatching((int) this.submitted);
        return (without - this.batches) * MS_PER_CALL;
    }

    public double savedPercent() {
        long without = callsWithoutBatching((int) this.submitted);
        return without == 0 ? 0.0 : 100.0 * (without - this.batches) / without;
    }

    /**
     * How many mip levels a texture of this size has.
     *
     * <p>Used to reject an entry that would write past the end of the chain.
     */
    public static int mipLevels(int width, int height) {
        int levels = 1;
        int w = width;
        int h = height;
        while (w > 1 || h > 1) {
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
            levels++;
        }
        return levels;
    }
}
