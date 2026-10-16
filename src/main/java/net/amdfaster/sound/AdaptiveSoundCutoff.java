package net.amdfaster.sound;

/**
 * Tightens the distance at which sounds are dropped as more of them are playing.
 *
 * <p>A quiet scene can afford to play distant sounds: there is nothing competing with them and the player
 * hears the world. A busy scene cannot, because the channels are being taken by things the player is not
 * looking at while the ones they are looking at go silent. The right cut-off is not one number -- it depends
 * on how much else is going on.
 *
 * <p>Raising the cut-off as the scene gets busier means the sounds that are dropped are the distant ones, and
 * the ones that are kept are the ones near the player, which is the correct trade in exactly the situation
 * where a trade has to be made.
 *
 * <h2>Nothing near the player is ever dropped</h2>
 *
 * <p>A sound within eight blocks is something the player can see and is probably acting on. Dropping it to
 * relieve the pool would cost the feedback for the thing in front of them in order to keep the noise of
 * something far away, which is backwards. So that band always plays, however busy the scene is.
 *
 * <h2>Nothing past the sound's own range is ever added</h2>
 *
 * <p>The engine attenuates a sound to silence at its range limit, so a sound past it would produce nothing
 * and cost a channel to do it. Refusing it costs the player nothing at all, and it is the cheapest saving
 * available because the decision needs no information about how busy the scene is.
 *
 * <h2>Sounds attached to the player are exempt</h2>
 *
 * <p>Music, interface clicks and the player's own voice have no position in the world -- they are played
 * relative to the listener. Applying a distance cut-off to them would make the menu silent or the music stop
 * when the scene got busy, which is not a performance trade but a broken interface.
 *
 * <h2>Why this is not the same as the region budget</h2>
 *
 * <p>{@link SoundCuller} caps how many sounds one region can hold, which is about concentration in space.
 * This decides how far away a sound can be before it is not worth a channel, which is about the scene as a
 * whole. A single distant sound in an empty region passes the first and can still fail the second.
 *
 * <p>Not thread safe. Sounds are evaluated on the sound thread.
 *
 * <p>Reference: Audition (client-side Fabric mod) drops distant and duplicate sounds, and raises its cut-off
 * as more sounds play at once. This is an independent implementation of the same idea.
 */
public final class AdaptiveSoundCutoff {

    /** Blocks within which every sound plays, however busy the scene is. */
    public static final double ALWAYS_AUDIBLE = 8.0;

    /** Blocks beyond which no sound is worth a channel. */
    public static final double MAX_DISTANCE = 32.0;

    /** How many playing sounds make the cut-off its strictest. */
    public static final int STRICTEST_AT = 64;

    /** Milliseconds starting one audio channel costs. */
    public static final double MS_PER_CHANNEL = 0.05;

    private long offered;
    private long played;
    private long tooFar;
    private long beyondCutoff;
    private long exempt;

    /**
     * The distance beyond which a sound is dropped, given how busy the scene is.
     *
     * <p>At the always-audible distance when the scene is at its strictest, and at the maximum when it is
     * quiet, so the whole band between them is used and the tightening is gradual rather than a switch.
     *
     * @param playing how many sounds are currently playing
     */
    public double cutoff(int playing) {
        if (playing < 0) {
            throw new IllegalArgumentException("a negative count: " + playing);
        }
        double pressure = Math.min(1.0, (double) playing / STRICTEST_AT);
        return MAX_DISTANCE - pressure * (MAX_DISTANCE - ALWAYS_AUDIBLE);
    }

    /**
     * Whether a sound should be given a channel.
     *
     * @param distance how far the source is from the player
     * @param playing how many sounds are currently playing
     * @param attached whether the sound is attached to the player rather than placed in the world
     * @return true if it should play
     */
    public boolean shouldPlay(double distance, int playing, boolean attached) {
        if (distance < 0.0) {
            throw new IllegalArgumentException("a negative distance: " + distance);
        }
        this.offered++;

        if (attached) {
            // Music, interface clicks and the player's own voice have no position in the world. Applying a
            // distance cut-off to them would make the menu silent or the music stop when the scene got busy,
            // which is not a performance trade but a broken interface.
            this.exempt++;
            this.played++;
            return true;
        }
        if (distance > MAX_DISTANCE) {
            // The engine attenuates a sound to silence at its range limit, so this would produce nothing and
            // cost a channel to do it. Refusing it costs the player nothing at all.
            this.tooFar++;
            return false;
        }
        if (distance <= ALWAYS_AUDIBLE) {
            // Something the player can see and is probably acting on. Dropping it to relieve the pool would
            // cost the feedback for the thing in front of them in order to keep the noise of something far
            // away, which is backwards.
            this.played++;
            return true;
        }
        if (distance > cutoff(playing)) {
            this.beyondCutoff++;
            return false;
        }
        this.played++;
        return true;
    }

    public long offered() {
        return this.offered;
    }

    public long played() {
        return this.played;
    }

    /** How many were dropped for being past the engine's own range. */
    public long tooFar() {
        return this.tooFar;
    }

    /** How many were dropped for being past the current cut-off. */
    public long beyondCutoff() {
        return this.beyondCutoff;
    }

    /** How many were exempt as attached to the player. */
    public long exempt() {
        return this.exempt;
    }

    public double droppedPercent() {
        return this.offered == 0 ? 0.0 : 100.0 * (this.tooFar + this.beyondCutoff) / this.offered;
    }

    /** Milliseconds of channel starts avoided. */
    public double msSaved() {
        return (this.tooFar + this.beyondCutoff) * MS_PER_CHANNEL;
    }

    /**
     * The share of the channel work avoided.
     *
     * <p>Zero before any sound has been offered. A culler that has been offered nothing has technically
     * avoided everything, but reporting that as a saving would describe an unused one as though it had
     * done something.
     */
    public double savedPercent() {
        if (this.offered == 0) {
            return 0.0;
        }
        return 100.0 * (this.tooFar + this.beyondCutoff) / this.offered;
    }
}
