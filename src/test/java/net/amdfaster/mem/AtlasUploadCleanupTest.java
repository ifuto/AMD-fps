package net.amdfaster.mem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasUploadCleanupTest {

    private static final int BASE = 128 * 128 * 4;

    @Test
    void anUploadedTextureReleasesItsCpuCopy() {
        // After the upload the GPU has its own copy and the one on the CPU is never read again -- the
        // renderer samples from video memory and nothing goes back to the decoded pixels.
        AtlasUploadCleanup cleanup = new AtlasUploadCleanup();

        cleanup.release(BASE, false);

        assertEquals(1L, cleanup.released());
        assertEquals(BASE, cleanup.bytesReleased());
    }

    @Test
    void theMipmapChainIsReleasedToo() {
        // Together the mipmaps are a third again on top of the base level, and releasing only the base level
        // would leave most of the memory behind and report a saving it had not made.
        AtlasUploadCleanup cleanup = new AtlasUploadCleanup();

        cleanup.release(BASE, true);

        assertEquals(BASE + (long) (BASE * AtlasUploadCleanup.MIPMAP_OVERHEAD),
                cleanup.bytesReleased());
        assertEquals(87381L, cleanup.bytesReleased());
    }

    @Test
    void aThousandTexturesIsAHundredMegabytesGivenBack() {
        // A resource pack with a thousand textures at 128 pixels square holds data that exists in two
        // places, one of which is never used.
        AtlasUploadCleanup cleanup = new AtlasUploadCleanup();

        for (int i = 0; i < 1000; i++) {
            cleanup.release(BASE, true);
        }

        assertEquals(1000L, cleanup.released());
        assertEquals(83.33, cleanup.mbReleased(), 0.01);
        assertEquals(100.0, cleanup.releasedPercent(), 1e-9);
    }

    @Test
    void aReloadReadsTheFileAgain() {
        // Getting the pixels means reading and decoding once more, which costs time at the moment it happens
        // -- and that is the right trade, because the alternative is holding every texture in memory on the
        // chance that one day something asks.
        AtlasUploadCleanup cleanup = new AtlasUploadCleanup();

        cleanup.release(BASE, true);
        cleanup.reload();
        cleanup.reload();

        assertEquals(2L, cleanup.reloaded());
        assertEquals(6.0, cleanup.msReloading(), 1e-9);
    }

    @Test
    void releasingNothingIsFree() {
        AtlasUploadCleanup cleanup = new AtlasUploadCleanup();

        cleanup.release(0, false);

        assertEquals(1L, cleanup.released());
        assertEquals(0L, cleanup.bytesReleased());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        AtlasUploadCleanup cleanup = new AtlasUploadCleanup();

        assertThrows(IllegalArgumentException.class, () -> cleanup.release(-1, false));
    }

    @Test
    void aFreshCleanupReportsZeroNotNaN() {
        // A cleanup that has done nothing has technically released everything it was given, but reporting
        // that as a result would describe an unused one as though it had measured something.
        AtlasUploadCleanup cleanup = new AtlasUploadCleanup();

        assertEquals(0.0, cleanup.releasedPercent());
        assertEquals(0.0, cleanup.mbReleased(), 1e-12);
        assertEquals(0.0, cleanup.msReloading(), 1e-12);
        assertEquals(0L, cleanup.uploaded());
        assertEquals(0L, cleanup.bytesReleased());
        assertTrue(AtlasUploadCleanup.MIPMAP_OVERHEAD > 0.0);
    }
}
