package net.amdfaster.mem;

/**
 * Drops the CPU-side copy of a texture once it has been uploaded to the GPU.
 *
 * <p>Loading a texture means reading the image into memory, building its mipmaps, and copying all of it to
 * the GPU. After that the GPU has its own copy and the one on the CPU is never read again -- the renderer
 * samples the texture from video memory, and nothing in the game goes back to the decoded pixels.
 *
 * <p>Keeping it costs the size of the image plus every mipmap level, for the whole session. A resource pack
 * with a thousand textures at 128 pixels square is a hundred megabytes of memory holding data that exists in
 * two places, one of which is never used.
 *
 * <h2>Released only after the upload has happened</h2>
 *
 * <p>Dropping the pixels before they reach the GPU would leave nothing to upload, and the texture would be
 * missing or blank. The order is the whole safety of this: the upload happens, it is confirmed, and only
 * then is the CPU copy let go. A cleanup that ran on a timer or a guess would occasionally delete a texture
 * that had not been uploaded yet, and the failure would be a block with no texture rather than an error.
 *
 * <h2>The mipmaps go too</h2>
 *
 * <p>A mipmapped texture holds a chain of progressively smaller copies, and together they are a third again
 * on top of the base level. They are uploaded with it and are equally unused afterwards, so releasing only
 * the base level would leave most of the memory behind and report a saving it had not made.
 *
 * <h2>A re-upload reads the file again</h2>
 *
 * <p>A resource pack reload, or a texture that has to be re-uploaded after the device is lost, needs the
 * pixels again. Getting them means reading and decoding the file once more, which costs time at the moment
 * it happens -- and that is the right trade, because the alternative is holding every texture in memory on
 * the chance that one day something asks. A cleanup that could not be undone would be a leak of a different
 * kind.
 *
 * <h2>Why this is not the same as the model cache</h2>
 *
 * <p>The model caches decide what to build and keep the built result, because the result is expensive to
 * reproduce and small. This drops something that is expensive to reproduce and large, and the difference is
 * that the GPU already has a copy -- so the cost of reproducing it is a file read, not a computation.
 *
 * <p>Thread safety: uploads happen on the render thread and the cleanup is called from the same one.
 */
public final class AtlasUploadCleanup {

    /** Extra memory a mipmap chain adds over the base level. */
    public static final double MIPMAP_OVERHEAD = 1.0 / 3.0;

    /** Milliseconds reading and decoding one texture costs, if it has to be done again. */
    public static final double MS_PER_RELOAD = 3.0;

    private long uploaded;
    private long released;
    private long bytesReleased;
    private long reloaded;

    /**
     * Records that a texture has been uploaded and its CPU copy can be dropped.
     *
     * <p>Called after the upload, never before. Dropping the pixels before they reach the GPU would leave
     * nothing to upload, and the texture would be missing or blank.
     *
     * @param baseBytes the size of the decoded image
     * @param mipmapped whether a mipmap chain was built with it
     */
    public void release(int baseBytes, boolean mipmapped) {
        if (baseBytes < 0) {
            throw new IllegalArgumentException("a negative size: " + baseBytes);
        }
        this.uploaded++;
        this.released++;

        long total = baseBytes;
        if (mipmapped) {
            // A mipmapped texture holds a chain of progressively smaller copies, and together they are a
            // third again on top of the base level. They are uploaded with it and equally unused
            // afterwards, so releasing only the base level would leave most of the memory behind and report
            // a saving it had not made.
            total += (long) (baseBytes * MIPMAP_OVERHEAD);
        }
        this.bytesReleased += total;
    }

    /**
     * Records that a texture had to be read again after being released.
     *
     * <p>A resource pack reload, or a re-upload after the device is lost. Getting the pixels means reading
     * and decoding the file once more, which costs time at the moment it happens -- and that is the right
     * trade, because the alternative is holding every texture in memory on the chance that one day
     * something asks.
     */
    public void reload() {
        this.reloaded++;
    }

    public long uploaded() {
        return this.uploaded;
    }

    public long released() {
        return this.released;
    }

    public long bytesReleased() {
        return this.bytesReleased;
    }

    /** How many textures had to be decoded again. */
    public long reloaded() {
        return this.reloaded;
    }

    /** Milliseconds the reloads cost. */
    public double msReloading() {
        return this.reloaded * MS_PER_RELOAD;
    }

    /** Megabytes of CPU memory given back. */
    public double mbReleased() {
        return this.bytesReleased / (1024.0 * 1024.0);
    }

    /**
     * The share of uploads whose CPU copy was dropped.
     *
     * <p>Zero before any texture has been uploaded. A cleanup that has done nothing has technically released
     * everything it was given, but reporting that as a result would describe an unused one as though it had
     * measured something.
     */
    public double releasedPercent() {
        return this.uploaded == 0 ? 0.0 : 100.0 * this.released / this.uploaded;
    }
}
