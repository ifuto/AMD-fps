package net.amdfaster.compat;

/**
 * What AMD-Faster yields when Sodium is actually rendering, and what it keeps.
 *
 * <h2>Why this exists</h2>
 *
 * <p>AMD-Faster is an independent renderer. Sodium is also a terrain renderer. Two mods that both own
 * section meshing do not compose -- whichever applies its mixins last wins, and the loser's work is done
 * and then thrown away, which is slower than either mod alone. So when Sodium is present and running,
 * AMD-Faster has to give up the subsystems Sodium replaces and keep the ones it does not touch.
 *
 * <p>Sodium replaces vanilla's {@code SectionBuilder} and {@code BlockRenderManager} path with its own
 * chunk meshing pipeline. A terrain hook aimed at the vanilla path therefore never fires under Sodium,
 * which is the failure mode that looks like "the mod does nothing" rather than like a conflict.
 *
 * <h2>The part that is easy to get wrong</h2>
 *
 * <p>Sodium's mixin plugin <b>disables itself when Embeddium is present</b>. So a loaded Sodium jar is not
 * the same as a running Sodium renderer: with Embeddium installed, Sodium is on the classpath, its mixins
 * are off, and vanilla's terrain path is live again. Yielding terrain rendering in that case would leave
 * <i>nobody</i> optimising it -- AMD-Faster standing aside for a renderer that disabled itself. Presence
 * and effect are therefore two separate questions, and this class answers both.
 *
 * <h2>Version</h2>
 *
 * <p>Sodium's internal renderer API changes every minor release, so a build validated against 0.8 says
 * nothing reliable about 0.9. An unrecognised minor is reported rather than assumed compatible, because
 * guessing "probably still the same" is how a mod ends up meshing sections twice on a version nobody
 * tested.
 *
 * <p>Immutable.
 */
public final class SodiumCoexistence {

    /** The Sodium minor this build was validated against. */
    public static final int VALIDATED_MINOR = 8;

    /** The Sodium major. Sodium has never left 0.x. */
    public static final int VALIDATED_MAJOR = 0;

    /** Embeddium's mod id, whose presence switches Sodium's mixins off. */
    public static final String EMBEDDIUM_ID = "embeddium";

    /** Sodium's mod id. */
    public static final String SODIUM_ID = "sodium";

    /**
     * Which of AMD-Faster's subsystems are in question.
     *
     * <p>Split by whether Sodium replaces the code path. The ones Sodium does not touch stay on whatever
     * the hardware wants, because yielding those would cost performance and buy no compatibility.
     */
    public enum Subsystem {
        /** Section meshing. Sodium has its own pipeline. */
        TERRAIN_MESHING(true),

        /** Frustum and occlusion culling of sections. Sodium culls as part of its own submission. */
        TERRAIN_CULLING(true),

        /** Deciding which sections to rebuild and when. Sodium owns its build queue. */
        CHUNK_BUILD_SCHEDULING(true),

        /** Entities. Sodium does not render entities. */
        ENTITIES(false),

        /** Block entities. Sodium leaves these to vanilla's renderers. */
        BLOCK_ENTITIES(false),

        /** Particles. Sodium does not batch or cull them. */
        PARTICLES(false),

        /** Sound. Not a renderer's business. */
        SOUND(false),

        /** Packet pacing and chunk reuse. Not a renderer's business. */
        NETWORK(false),

        /** Model and state memory. Sodium does not deduplicate models. */
        MEMORY(false),

        /** Noise, biomes and structure layout. Sodium does not generate terrain. */
        WORLDGEN(false),

        /** Light propagation. Sodium consumes light; it does not compute it. */
        LIGHT(false);

        private final boolean sodiumReplaces;

        Subsystem(boolean sodiumReplaces) {
            this.sodiumReplaces = sodiumReplaces;
        }

        /** Whether Sodium has its own implementation of this path. */
        public boolean sodiumReplaces() {
            return this.sodiumReplaces;
        }
    }

    /**
     * What was detected, and what to do about it.
     *
     * @param sodiumPresent whether a Sodium jar is loaded
     * @param embeddiumPresent whether Embeddium is loaded, which switches Sodium's mixins off
     * @param major the detected Sodium major, or -1 when not detected
     * @param minor the detected Sodium minor, or -1 when not detected
     */
    public record Detection(boolean sodiumPresent, boolean embeddiumPresent, int major, int minor) {

        /**
         * Whether Sodium is really rendering terrain.
         *
         * <p>Both conditions, not just the first. A Sodium jar alongside Embeddium is a mod that turned
         * itself off, and treating it as active would leave the terrain path unoptimised by anyone.
         */
        public boolean sodiumActive() {
            return this.sodiumPresent && !this.embeddiumPresent;
        }

        /** Whether the detected version is one this build was validated against. */
        public boolean versionValidated() {
            return this.major == VALIDATED_MAJOR && this.minor == VALIDATED_MINOR;
        }

        /** Whether a version was detected at all. */
        public boolean versionKnown() {
            return this.major >= 0 && this.minor >= 0;
        }
    }

    private SodiumCoexistence() {
    }

    /**
     * Whether AMD-Faster should run a subsystem itself.
     *
     * <p>False only where Sodium is active <i>and</i> replaces that path. Everything else stays on, which
     * is the whole point of the add-on mode: co-installing Sodium should not cost the player the entity,
     * sound, network and memory work, none of which Sodium does.
     *
     * @param detection what was detected
     * @param subsystem the subsystem in question
     * @return whether AMD-Faster owns it
     */
    public static boolean amdFasterOwns(Detection detection, Subsystem subsystem) {
        if (detection == null) {
            throw new IllegalArgumentException("a detection is required");
        }
        if (subsystem == null) {
            throw new IllegalArgumentException("a subsystem is required");
        }
        return !(detection.sodiumActive() && subsystem.sodiumReplaces());
    }

    /**
     * How many subsystems AMD-Faster keeps under a detection.
     *
     * <p>Reported rather than derived at the call site so the HUD and the log agree on one number.
     *
     * @param detection what was detected
     * @return how many subsystems stay on
     */
    public static int subsystemsKept(Detection detection) {
        int kept = 0;
        for (Subsystem subsystem : Subsystem.values()) {
            if (amdFasterOwns(detection, subsystem)) {
                kept++;
            }
        }
        return kept;
    }

    /**
     * Whether the player should be warned.
     *
     * <p>True only for the case where guessing would be wrong in a way the player cannot see: Sodium is
     * active but on a version this build was not validated against. A known-good version needs no warning,
     * and Sodium being absent is not a problem either.
     *
     * @param detection what was detected
     * @return whether to warn
     */
    public static boolean shouldWarn(Detection detection) {
        return detection.sodiumActive() && detection.versionKnown() && !detection.versionValidated();
    }

    /**
     * Parses the leading numeric part of a Fabric version string.
     *
     * <p>Fabric versions are free-form: {@code 0.8.1+mc1.21.11}, {@code 0.6.0-beta.1} and {@code 0.8.12}
     * are all real. Only the leading digits before the first separator are meaningful as a release number,
     * and the {@code +mc1.21.11} suffix has to be dropped rather than parsed -- reading the build suffix as
     * a minor would report 21 and reject a version that is in fact supported.
     *
     * @param version the raw version string, which may be null
     * @return major and minor, or {-1, -1} when the string does not start with a number
     */
    public static int[] parseVersion(String version) {
        if (version == null) {
            return new int[] {-1, -1};
        }
        // Cut at the first character that cannot be part of a dotted number.
        int end = 0;
        while (end < version.length()) {
            char c = version.charAt(end);
            if ((c < '0' || c > '9') && c != '.') {
                break;
            }
            end++;
        }
        if (end == 0) {
            return new int[] {-1, -1};
        }
        String[] parts = version.substring(0, end).split("\\.");
        try {
            int major = parts[0].isEmpty() ? -1 : Integer.parseInt(parts[0]);
            int minor = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(parts[1]) : -1;
            return new int[] {major, minor};
        } catch (NumberFormatException e) {
            return new int[] {-1, -1};
        }
    }

    /**
     * Builds a detection from raw facts.
     *
     * <p>The version string is parsed here so the caller only has to supply what the loader reported.
     *
     * @param sodiumPresent whether Sodium is loaded
     * @param embeddiumPresent whether Embeddium is loaded
     * @param sodiumVersion the raw Sodium version string, or null when Sodium is absent
     * @return the detection
     */
    public static Detection detect(boolean sodiumPresent, boolean embeddiumPresent, String sodiumVersion) {
        int[] parsed = parseVersion(sodiumVersion);
        return new Detection(sodiumPresent, embeddiumPresent, parsed[0], parsed[1]);
    }

    /**
     * One line for the log, saying what was decided and why.
     *
     * <p>The reason is included because "AMD-Faster is not meshing terrain" is indistinguishable from a bug
     * unless the log says it stood aside for Sodium on purpose.
     *
     * @param detection what was detected
     * @return a human-readable line
     */
    public static String describe(Detection detection) {
        if (!detection.sodiumPresent()) {
            return "Sodium absent; AMD-Faster owns all " + Subsystem.values().length + " subsystems";
        }
        if (!detection.sodiumActive()) {
            return "Sodium present but Embeddium disables its mixins; vanilla terrain path is live, "
                    + "AMD-Faster owns all " + Subsystem.values().length + " subsystems";
        }
        String version = detection.versionKnown()
                ? detection.major() + "." + detection.minor()
                : "unknown";
        return "Sodium " + version + " is rendering terrain; AMD-Faster keeps "
                + subsystemsKept(detection) + " of " + Subsystem.values().length
                + " subsystems" + (shouldWarn(detection) ? " (unvalidated version)" : "");
    }
}
