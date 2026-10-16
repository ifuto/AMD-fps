package net.amdfaster.sound;

/**
 * Decides which sounds are decoded into memory and which are read from disk as they play.
 *
 * <p>A sound can be held two ways. Decoded, it sits in memory ready to start, which costs the size of the
 * sample but nothing at the moment it plays. Streamed, it is read from the resource pack as it plays, which
 * costs almost no memory but has to seek and decode at the moment the sound starts.
 *
 * <p>Neither is right for everything. A click that is streamed has to be read before it can be heard, and
 * the delay is long enough to be noticeable on a sound that is supposed to be instant. A four-minute music
 * track that is decoded sits in memory taking up more than the rest of the audio combined, for a sound that
 * starts once and plays for minutes.
 *
 * <p>Splitting them by length gives each the treatment that suits it.
 *
 * <h2>The threshold is a length, not a size</h2>
 *
 * <p>What makes streaming wrong for a sound is that the read happens at the moment the player expects to
 * hear it, and that is about how soon the sound needs to start relative to how long it plays. A short sound
 * cannot hide the read; a long one can, because the read is a small part of its total time. So the decision
 * is made on duration, and a very quiet long track and a loud short one are treated the same way, which is
 * what the trade actually depends on.
 *
 * <h2>Music is streamed even when it would fit</h2>
 *
 * <p>A music track is the longest thing in the game's audio and the least often played -- most sessions hear
 * a handful of tracks once each. Decoding all of them at startup would spend memory on samples that are
 * almost never used, and the one time one plays, the read is inaudible against four minutes of music. So
 * length is not the only consideration: something long enough to stream is streamed whether or not it would
 * have fitted.
 *
 * <h2>The counts are separate pools</h2>
 *
 * <p>Static and streaming sources are different resources in the audio engine, and filling one does not fill
 * the other. A scene with a hundred sound effects can exhaust the static pool while every streaming channel
 * sits idle, so counting them together would report a limit that is not the one actually being hit, and the
 * player would see sounds missing for a reason the statistics do not show.
 *
 * <h2>Why this is not the same as the cut-off</h2>
 *
 * <p>{@link AdaptiveSoundCutoff} decides whether a sound plays at all. This decides how it is held once that
 * is settled, and both apply: a sound that passes the cut-off is then placed in one pool or the other.
 *
 * <p>Not thread safe. Sounds are evaluated on the sound thread.
 *
 * <p>Reference: Raise Sound Limit Simplified (ishland) allows a configurable split between static and
 * streaming sources. This is an independent implementation of the same idea.
 */
public final class StreamingSoundSplitter {

    /** Seconds of playback above which a sound is streamed rather than decoded. */
    public static final double STREAM_ABOVE_SECONDS = 4.0;

    /** Static sources in the pool. */
    public static final int STATIC_SOURCES = 200;

    /** Streaming sources in the pool. */
    public static final int STREAMING_SOURCES = 32;

    /** Kilobytes a second of decoded audio occupies. */
    public static final int KB_PER_SECOND = 172;

    private int staticInUse;
    private int streamingInUse;

    private long offered;
    private long streamed;
    private long decoded;
    private long staticRefused;
    private long streamingRefused;

    /**
     * Whether a sound should be streamed from disk rather than held decoded.
     *
     * @param seconds how long the sound plays for
     * @param music whether it is a music track, which is streamed however short it is
     */
    public static boolean shouldStream(double seconds, boolean music) {
        if (seconds < 0.0) {
            throw new IllegalArgumentException("a negative length: " + seconds);
        }
        // A music track is the longest thing in the game's audio and the least often played. Decoding all of
        // them at startup would spend memory on samples almost never used, and the one time one plays the
        // read is inaudible against minutes of music.
        return music || seconds > STREAM_ABOVE_SECONDS;
    }

    /**
     * Places a sound in one of the two pools.
     *
     * @param seconds how long the sound plays for
     * @param music whether it is a music track
     * @return true if a source was available for it
     */
    public boolean start(double seconds, boolean music) {
        this.offered++;

        if (shouldStream(seconds, music)) {
            if (this.streamingInUse >= STREAMING_SOURCES) {
                // Static and streaming are different resources in the audio engine, and filling one does not
                // fill the other. Counting them together would report a limit that is not the one being hit.
                this.streamingRefused++;
                return false;
            }
            this.streamingInUse++;
            this.streamed++;
            return true;
        }
        if (this.staticInUse >= STATIC_SOURCES) {
            this.staticRefused++;
            return false;
        }
        this.staticInUse++;
        this.decoded++;
        return true;
    }

    /** Releases a source when its sound finishes. */
    public void stop(boolean wasStreamed) {
        if (wasStreamed) {
            if (this.streamingInUse > 0) {
                this.streamingInUse--;
            }
        } else if (this.staticInUse > 0) {
            this.staticInUse--;
        }
    }

    public int staticInUse() {
        return this.staticInUse;
    }

    public int streamingInUse() {
        return this.streamingInUse;
    }

    public long offered() {
        return this.offered;
    }

    public long streamed() {
        return this.streamed;
    }

    public long decoded() {
        return this.decoded;
    }

    /** How many were refused because the static pool was full. */
    public long staticRefused() {
        return this.staticRefused;
    }

    /** How many were refused because the streaming pool was full. */
    public long streamingRefused() {
        return this.streamingRefused;
    }

    /**
     * Kilobytes of memory not held, because the streamed sounds were never decoded.
     *
     * <p>Compared against holding every sound decoded, which is what the alternative would cost.
     *
     * @param secondsPerSound the average length of the sounds that were streamed
     */
    public long kbNotHeld(double secondsPerSound) {
        if (secondsPerSound < 0.0) {
            throw new IllegalArgumentException("a negative length: " + secondsPerSound);
        }
        return (long) (this.streamed * secondsPerSound * KB_PER_SECOND);
    }

    /**
     * The share of the offers that were refused.
     *
     * <p>Zero before any sound has been offered. A splitter that has been offered nothing has technically
     * refused nothing, and reporting that as a rate would describe an unused one as though it had a result.
     */
    public double refusedPercent() {
        if (this.offered == 0) {
            return 0.0;
        }
        return 100.0 * (this.staticRefused + this.streamingRefused) / this.offered;
    }
}
