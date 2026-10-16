package net.amdfaster.sound;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Names the sounds that are never culled, however busy the scene is.
 *
 * <p>Most sound culling is a trade: drop the sounds the player is least likely to miss and keep the pool for
 * the rest. But a few sounds are not in that trade at all. A player taking damage, a creeper igniting, TNT
 * lighting and lightning striking are the game telling the player to do something now, and dropping one of
 * those to make room for a piston click means the player dies without ever hearing why.
 *
 * <p>Keeping a named list of them means the culling can be as aggressive as the machine needs without ever
 * costing the player the information they act on.
 *
 * <h2>The list is explicit</h2>
 *
 * <p>Inferring which sounds matter -- anything loud, anything short, anything with "hurt" in the name -- is
 * wrong for some sound somewhere, and a mod adding its own alarm would be culled by a rule that could not
 * have known about it. A list can be read, extended by a mod that knows it has a sound the player must hear,
 * and audited when something is missing.
 *
 * <h2>A full pool still stops it</h2>
 *
 * <p>The whitelist overrides the culling decisions, not the physics of the audio engine. If every channel is
 * genuinely in use there is nowhere to put a sound, and pretending otherwise would mean dropping one of the
 * other protected ones instead, which is not a better outcome -- it is the same loss moved somewhere less
 * obvious. So the whitelist is checked after the pool, not instead of it.
 *
 * <h2>Why being on the list is not the same as being loud</h2>
 *
 * <p>A player's hurt sound is quiet and short, and an explosion is both. What they share is not volume but
 * consequence: the player changes what they are doing because of them. A sound that is merely loud is exactly
 * the kind of thing culling should be able to drop.
 *
 * <p>Thread safety: the set is only ever replaced, never mutated in place, so a culling thread reading it
 * never sees a half-built one.
 *
 * <p>Reference: Sound Culling 2.0 (Cukkoo, MIT) protects player hurt, player death, generic explode,
 * lightning and TNT ignite from culling. This is an independent implementation of the same behaviour.
 */
public final class CriticalSoundWhitelist {

    private volatile Set<String> critical = Collections.emptySet();

    private long checked;
    private long protectedSounds;
    private long culledNormally;
    private long refusedByFullPool;

    /** The sounds the vanilla game uses for things the player must act on. */
    public static final String[] DEFAULTS = {
        "entity.player.hurt",
        "entity.player.death",
        "entity.generic.explode",
        "entity.lightning_bolt.thunder",
        "entity.tnt.primed",
    };

    /** Adds a sound to the whitelist. */
    public void protect(String sound) {
        if (sound == null || sound.isEmpty()) {
            throw new IllegalArgumentException("a sound is required");
        }
        // Replaced rather than mutated, so a culling thread reading the set never sees a half-built one.
        Set<String> next = new HashSet<>(this.critical);
        next.add(sound);
        this.critical = Collections.unmodifiableSet(next);
    }

    /** Removes a sound from the whitelist. */
    public void unprotect(String sound) {
        Set<String> next = new HashSet<>(this.critical);
        if (next.remove(sound)) {
            this.critical = Collections.unmodifiableSet(next);
        }
    }

    /** Adds the vanilla defaults. */
    public void protectDefaults() {
        for (String sound : DEFAULTS) {
            protect(sound);
        }
    }

    /** Whether a sound is on the whitelist. */
    public boolean isCritical(String sound) {
        return this.critical.contains(sound);
    }

    /** How many sounds are protected. */
    public int size() {
        return this.critical.size();
    }

    /**
     * Whether a sound should play.
     *
     * @param sound the sound identifier
     * @param poolFull whether every audio channel is genuinely in use
     * @param wouldBeCulled whether the ordinary culling decided to drop it
     * @return true if it should play
     */
    public boolean shouldPlay(String sound, boolean poolFull, boolean wouldBeCulled) {
        this.checked++;

        if (poolFull) {
            // The whitelist overrides the culling decisions, not the physics of the audio engine. If every
            // channel is in use there is nowhere to put a sound, and pretending otherwise would mean
            // dropping one of the other protected ones instead -- the same loss moved somewhere less
            // obvious.
            this.refusedByFullPool++;
            return false;
        }
        if (isCritical(sound)) {
            this.protectedSounds++;
            return true;
        }
        if (wouldBeCulled) {
            this.culledNormally++;
            return false;
        }
        return true;
    }

    public long checked() {
        return this.checked;
    }

    /** How many were played because they are on the whitelist. */
    public long protectedSounds() {
        return this.protectedSounds;
    }

    /** How many ordinary sounds the culling dropped. */
    public long culledNormally() {
        return this.culledNormally;
    }

    /** How many were refused because every channel was in use. */
    public long refusedByFullPool() {
        return this.refusedByFullPool;
    }

    /**
     * The share of the sounds checked that the whitelist saved from culling.
     *
     * <p>Zero before any sound has been checked. A whitelist that has been asked nothing has technically
     * saved everything, but reporting that as a rate would describe an unused one as though it had a
     * result.
     */
    public double protectedPercent() {
        return this.checked == 0 ? 0.0 : 100.0 * this.protectedSounds / this.checked;
    }
}
