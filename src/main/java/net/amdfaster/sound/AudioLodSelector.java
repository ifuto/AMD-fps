package net.amdfaster.sound;

/**
 * Picks the quality tier a distant sound is rendered at, and virtualises the ones that are inaudible.
 *
 * <p>Every sound in the mix costs the same whether the player can pick it out or not: it is resampled,
 * mixed, run through whatever effects are on its bus, and its parameters are recalculated. A voice past
 * the point of audibility is paying all of that for nothing, which is what virtual voices exist to stop.
 * A virtual voice keeps only the volume and elapsed-time calculation -- the two things it needs to come
 * back at the right level and the right position when it becomes audible again -- and skips the rest. At
 * a 64 voice budget with half the voices virtual that is roughly 45 per cent off the mixing cost, and the
 * saving is larger the more crowded the scene is, which is exactly when it is needed.
 *
 * <p>The part worth being careful about is the threshold. Audio middleware defaults to something around
 * -40 dB as the point where a sound counts as inaudible, and that number assumes a logarithmic attenuation
 * curve, where -40 dB is a meaningful chunk of the source's range. Minecraft's is linear: volume falls as
 * {@code 1 - distance / maxDistance}. Under a linear curve -40 dB sits at 99 per cent of the maximum
 * distance, so a -40 dB rule would virtualise almost nothing and the whole mechanism would be dead weight
 * that still cost a volume calculation per voice. The tiers here are therefore chosen on distance
 * fraction and the decibel figure is reported alongside for diagnostics, rather than the decibel figure
 * driving the decision.
 *
 * <p>Virtual is not free either, and pretending otherwise would overstate this. A virtual voice still
 * computes its volume and its elapsed time, and it is still an emitter as far as any spatial processing is
 * concerned, so it still contributes to occlusion and diffraction work. That is why the count of virtual
 * voices is reported separately from the count of physical ones: a scene with 200 virtual voices is not
 * cheap, it is just cheaper than 200 physical ones.
 *
 * <p>The sample rate tiers follow the same logic and have the same caveat. Dropping a distant sound from
 * 48 kHz to 22.05 kHz removes everything above 11 kHz, which for a sound already at -14 dB is not
 * distinguishable -- but it is a real change to the signal, and below about 16 kHz the common codecs stop
 * being a good fit because they are tuned for higher rates. So the lowest tier stops at 16 kHz rather than
 * going further, and the reduction is only applied past the point where the sound is already quiet.
 *
 * <p>One more saving is in here because it is larger than the others and easy to miss. A sound's
 * parameters -- its position relative to the listener, its attenuation, its doppler -- do not need to be
 * recomputed at the sample rate. The listener does not move 48,000 times a second; it moves once a tick.
 * Computing them at 60 Hz instead of 48 kHz is 800 times fewer evaluations for a change no one can hear,
 * and unlike the sample rate tiers it costs nothing in quality at all.
 *
 * @see <a href="https://www.audiokinetic.com/en/community/blog/how-to-get-a-hold-on-your-voices-optimizing-for-cpu-part-1/">
 *      Audiokinetic -- the volume threshold decides what counts as inaudible and should be as high as
 *      tolerable, since there are few reasons to leave it at -96 dB</a>
 * @see <a href="https://www.audiokinetic.com/en/blog/audio-optimization-practices-in-scars-above/">Audio
 *      Optimization Practices in Scars Above -- a virtual voice skips all computation except volume, and
 *      virtual voices are still emitters for spatial audio</a>
 * @see <a href="https://salivity.github.io/game-development/article/building-procedural-dynamic-audio-systems">
 *      Building Procedural Dynamic Audio Systems -- heavy parameters are calculated at control rate, around
 *      60 Hz to match the game tick, rather than at the 48 kHz sample rate</a>
 * @see <a href="https://generalistprogrammer.com/game-audio-development">Game Audio Development -- 22 kHz
 *      instead of 48 kHz for non-critical sounds, and audio LOD reducing processing for distant sounds</a>
 */
public final class AudioLodSelector {

    /** The mix rate everything is resampled to. */
    public static final int NATIVE_SAMPLE_RATE = 48000;

    /** The rate a sound already well into its falloff is rendered at. */
    public static final int REDUCED_SAMPLE_RATE = 22050;

    /**
     * The lowest tier.
     *
     * <p>Not lower on purpose. The common codecs are tuned for higher rates and degrade below about 16
     * kHz, so going further would trade a quality loss the player could hear for a saving they could not.
     */
    public static final int MINIMAL_SAMPLE_RATE = 16000;

    /** Fraction of maximum distance at which the reduced tier starts. */
    public static final double REDUCED_AT = 0.5;

    /** Fraction at which the minimal tier starts. */
    public static final double MINIMAL_AT = 0.8;

    /**
     * Fraction at which a sound becomes virtual.
     *
     * <p>99 per cent of the way out, which is -40 dB under a linear curve. The fraction is what is chosen
     * and the decibel figure is what falls out; picking the decibel figure first would put the threshold
     * somewhere that sounds sensible and virtualises nothing.
     */
    public static final double VIRTUAL_AT = 0.99;

    /** Parameter updates per second, against the sample rate they would otherwise run at. */
    public static final int CONTROL_RATE_HZ = 60;

    /** Milliseconds one physical voice costs a tick. */
    public static final double MS_PER_PHYSICAL_VOICE = 0.05;

    /** Milliseconds one virtual voice costs, for the volume and elapsed-time work it still does. */
    public static final double MS_PER_VIRTUAL_VOICE = 0.005;

    private AudioLodSelector() {
    }

    /** What a sound is rendered as. */
    public enum Tier {
        /** Full rate, full processing. */
        FULL(NATIVE_SAMPLE_RATE),
        /** 22.05 kHz. Past the point where the top octaves are distinguishable. */
        REDUCED(REDUCED_SAMPLE_RATE),
        /** 16 kHz. The floor the codecs are still good at. */
        MINIMAL(MINIMAL_SAMPLE_RATE),
        /** Not mixed at all; volume and elapsed time only. */
        VIRTUAL(0),
        /** Past the range limit, so nothing is owed to it. */
        OFF(0);

        private final int sampleRate;

        Tier(int sampleRate) {
            this.sampleRate = sampleRate;
        }

        /** The rate this tier renders at, or 0 for the tiers that render nothing. */
        public int sampleRate() {
            return this.sampleRate;
        }

        /** Whether this tier is mixed at all. */
        public boolean physical() {
            return this != VIRTUAL && this != OFF;
        }
    }

    /**
     * One sound's placement.
     *
     * @param tier        what it is rendered as
     * @param attenuated  the volume the engine would mix it at
     * @param decibels    that volume in dB, for diagnostics; negative infinity when silent
     * @param controlRate whether its parameters may be updated per tick rather than per sample
     */
    public record Placement(Tier tier, double attenuated, double decibels, boolean controlRate) {
    }

    /**
     * The decibel level of a linear volume.
     *
     * @return negative infinity for silence, since no finite number of decibels describes it
     */
    public static double decibels(double linear) {
        require(linear >= 0.0, "a volume must not be negative");
        return linear <= 0.0 ? Double.NEGATIVE_INFINITY : 20.0 * Math.log10(linear);
    }

    /**
     * Where a sound is placed.
     *
     * <p>Uses {@link SoundCuller#attenuated} rather than restating the curve, because the tier has to
     * agree with the volume the culler will report or the two disagree about whether a sound is audible.
     *
     * @param distance    blocks to the listener
     * @param maxDistance the sound's range; 0 means it does not attenuate, such as a UI click
     * @param volume      the source volume before attenuation
     * @param critical    whether the sound is protected, in which case it is never virtualised. A
     *                    footstep behind the player at 31 blocks can be dropped; the hiss of a creeper
     *                    next to them cannot, even at the same distance.
     */
    public static Placement place(double distance, double maxDistance, double volume, boolean critical) {
        require(distance >= 0.0, "a distance must not be negative");
        require(volume >= 0.0, "a volume must not be negative");
        double attenuated = SoundCuller.attenuated(distance, maxDistance, volume);
        if (attenuated <= 0.0) {
            return new Placement(Tier.OFF, 0.0, Double.NEGATIVE_INFINITY, false);
        }
        if (maxDistance <= 0.0) {
            // Nothing to measure a fraction of, and a non-attenuating sound is heard at full volume
            // anywhere, so it is never a candidate for a lower tier.
            return new Placement(Tier.FULL, attenuated, decibels(attenuated), true);
        }
        double fraction = distance / maxDistance;
        Tier tier;
        if (!critical && fraction >= VIRTUAL_AT) {
            tier = Tier.VIRTUAL;
        } else if (fraction >= MINIMAL_AT) {
            tier = Tier.MINIMAL;
        } else if (fraction >= REDUCED_AT) {
            tier = Tier.REDUCED;
        } else {
            tier = Tier.FULL;
        }
        // Parameters may run at control rate as soon as the sound is not the one the player is listening
        // to. A nearby sound's doppler and pan are perceptible frame to frame; a distant one's are not.
        boolean controlRate = tier != Tier.FULL;
        return new Placement(tier, attenuated, decibels(attenuated), controlRate);
    }

    /**
     * What a mix of placements costs a tick.
     *
     * @param physical voices that are mixed
     * @param virtual  voices that only track their volume and elapsed time
     */
    public static double tickMillis(int physical, int virtual) {
        require(physical >= 0 && virtual >= 0, "voice counts must not be negative");
        return physical * MS_PER_PHYSICAL_VOICE + virtual * MS_PER_VIRTUAL_VOICE;
    }

    /**
     * How many times fewer parameter evaluations control rate is, against the sample rate.
     *
     * <p>The largest saving here and the only one with no quality cost at all, which is why it applies to
     * every tier below FULL rather than only the quiet ones.
     */
    public static int controlRateDivisor() {
        return NATIVE_SAMPLE_RATE / CONTROL_RATE_HZ;
    }

    /**
     * The distance at which a linear curve reaches a given decibel level.
     *
     * <p>Worth having because it is the number that shows why a middleware-style dB threshold does not
     * transfer: -40 dB under a linear curve is 99 per cent of the range, not a useful chunk of it.
     *
     * @param thresholdDb a negative decibel level
     * @return the distance in blocks, or the maximum distance when the level is unreachable
     */
    public static double distanceForDecibels(double thresholdDb, double maxDistance) {
        require(thresholdDb <= 0.0, "an attenuation threshold is not positive");
        require(maxDistance > 0.0, "a maximum distance must be positive");
        double linear = Math.pow(10.0, thresholdDb / 20.0);
        double fraction = 1.0 - linear;
        return Math.min(maxDistance, Math.max(0.0, fraction * maxDistance));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
