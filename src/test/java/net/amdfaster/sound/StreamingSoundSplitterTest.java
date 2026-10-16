package net.amdfaster.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingSoundSplitterTest {

    @Test
    void aShortSoundIsHeldDecoded() {
        // A click that is streamed has to be read before it can be heard, and the delay is long enough to be
        // noticeable on a sound that is supposed to be instant.
        assertFalse(StreamingSoundSplitter.shouldStream(0.5, false));
        assertTrue(StreamingSoundSplitter.shouldStream(
                StreamingSoundSplitter.STREAM_ABOVE_SECONDS + 0.1, false));
    }

    @Test
    void musicIsStreamedHoweverShortItIs() {
        // A music track is the longest thing in the game's audio and the least often played. Decoding all of
        // them at startup would spend memory on samples almost never used.
        assertTrue(StreamingSoundSplitter.shouldStream(1.0, true));
        assertTrue(StreamingSoundSplitter.shouldStream(240.0, true));
    }

    @Test
    void theTwoPoolsAreCountedSeparately() {
        // A scene with a hundred sound effects can exhaust the static pool while every streaming channel
        // sits idle, so counting them together would report a limit that is not the one being hit.
        StreamingSoundSplitter splitter = new StreamingSoundSplitter();

        for (int i = 0; i < StreamingSoundSplitter.STATIC_SOURCES; i++) {
            assertTrue(splitter.start(0.5, false));
        }
        assertFalse(splitter.start(0.5, false), "the static pool is full");

        assertEquals(1L, splitter.staticRefused());
        assertEquals(0L, splitter.streamingRefused());
        assertTrue(splitter.start(60.0, true), "and the streaming pool is untouched");
    }

    @Test
    void theStreamingPoolIsMuchSmaller() {
        // Music and jukeboxes are few at once, while sound effects are many.
        assertEquals(200, StreamingSoundSplitter.STATIC_SOURCES);
        assertEquals(32, StreamingSoundSplitter.STREAMING_SOURCES);
        assertTrue(StreamingSoundSplitter.STATIC_SOURCES > StreamingSoundSplitter.STREAMING_SOURCES);
    }

    @Test
    void aFullStreamingPoolRefusesMusic() {
        StreamingSoundSplitter splitter = new StreamingSoundSplitter();

        for (int i = 0; i < StreamingSoundSplitter.STREAMING_SOURCES; i++) {
            assertTrue(splitter.start(120.0, true));
        }
        assertFalse(splitter.start(120.0, true));

        assertEquals(1L, splitter.streamingRefused());
        assertEquals(0L, splitter.staticRefused());
    }

    @Test
    void stoppingFreesTheRightPool() {
        StreamingSoundSplitter splitter = new StreamingSoundSplitter();

        splitter.start(0.5, false);
        splitter.start(120.0, true);
        assertEquals(1, splitter.staticInUse());
        assertEquals(1, splitter.streamingInUse());

        splitter.stop(false);
        splitter.stop(true);

        assertEquals(0, splitter.staticInUse());
        assertEquals(0, splitter.streamingInUse());
    }

    @Test
    void stoppingAnEmptyPoolDoesNotGoNegative() {
        StreamingSoundSplitter splitter = new StreamingSoundSplitter();

        splitter.stop(false);
        splitter.stop(true);

        assertEquals(0, splitter.staticInUse());
        assertEquals(0, splitter.streamingInUse());
    }

    @Test
    void streamingMusicKeepsMegabytesOutOfMemory() {
        // Compared against holding every sound decoded, which is what the alternative would cost.
        StreamingSoundSplitter splitter = new StreamingSoundSplitter();

        for (int i = 0; i < 10; i++) {
            splitter.start(180.0, true);
        }

        assertEquals(10L, splitter.streamed());
        assertEquals(10 * 180 * StreamingSoundSplitter.KB_PER_SECOND, splitter.kbNotHeld(180.0));
        assertEquals(309_600L, splitter.kbNotHeld(180.0));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        StreamingSoundSplitter splitter = new StreamingSoundSplitter();

        assertThrows(IllegalArgumentException.class,
                () -> StreamingSoundSplitter.shouldStream(-1.0, false));
        assertThrows(IllegalArgumentException.class, () -> splitter.kbNotHeld(-1.0));
    }

    @Test
    void aFreshSplitterReportsZeroNotNaN() {
        // A splitter that has been offered nothing has technically refused nothing, and reporting that as a
        // rate would describe an unused one as though it had a result.
        StreamingSoundSplitter splitter = new StreamingSoundSplitter();

        assertEquals(0.0, splitter.refusedPercent());
        assertEquals(0L, splitter.kbNotHeld(180.0));
        assertEquals(0L, splitter.offered());
        assertEquals(0, splitter.staticInUse());
        assertEquals(0, splitter.streamingInUse());
        assertEquals(4.0, StreamingSoundSplitter.STREAM_ABOVE_SECONDS, 1e-12);
    }
}
