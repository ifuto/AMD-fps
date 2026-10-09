package net.amdfaster.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The mod's settings, held as a flat map of strings and written as simple {@code key=value} lines.
 *
 * <p>No JSON parser, no config library, no dependency. The file is something a player can open in a
 * text editor and change by hand, which matters for a performance mod: the person most likely to need
 * to turn a feature off is the one whose game broke, and making them install a config API to do it is
 * the wrong trade.
 *
 * <p>Every setting has a default in code, so a missing file, a missing key, a corrupt line, or a file
 * the player cannot write all fall back to working rather than failing. A performance mod that refuses
 * to start because it could not parse its own config is worse than no mod.
 *
 * <p>Not thread safe; loaded once at startup and read from the client thread thereafter.
 */
public final class AmdFasterConfig {

    public static final String FILE_NAME = "amdfaster.properties";

    /** Whether the frame governor caps an unfocused window. */
    public static final String KEY_GOVERNOR_ENABLED = "governor.enabled";

    /** Frames per second allowed when the window does not have focus. */
    public static final String KEY_UNFOCUSED_FPS = "governor.unfocusedFps";

    /** Frames per second allowed when focused but idle. */
    public static final String KEY_IDLE_FPS = "governor.idleFps";

    /** Milliseconds without input before the client counts as idle. */
    public static final String KEY_IDLE_DELAY_MS = "governor.idleDelayMs";

    /** Whether the statistics overlay is drawn. */
    public static final String KEY_OVERLAY_ENABLED = "overlay.enabled";

    /** Whether block state lookups go through the cache. */
    public static final String KEY_BLOCK_CACHE_ENABLED = "cache.blockStates";

    /** Entries in the block state cache. A power of two. */
    public static final String KEY_BLOCK_CACHE_ENTRIES = "cache.blockStateEntries";

    /** Whether particles are culled before they are drawn. */
    public static final String KEY_PARTICLE_CULLING = "particle.culling";

    /** Maximum live particles. */
    public static final String KEY_PARTICLE_LIMIT = "particle.limit";

    private final Map<String, String> values = new LinkedHashMap<>();
    private Path file;
    private boolean loadedFromDisk;

    /** Defaults only. Used when the game has not told us where to write yet. */
    public static AmdFasterConfig defaults() {
        return new AmdFasterConfig();
    }

    /**
     * Loads from disk, or falls back to defaults on any problem.
     *
     * <p>Deliberately never throws. A config file is not worth a crash, and every failure mode here --
     * absent, unreadable, half-written by a crashed process -- has the same correct answer, which is to
     * run with defaults.
     */
    public static AmdFasterConfig load(Path configDirectory) {
        AmdFasterConfig config = new AmdFasterConfig();
        if (configDirectory == null) {
            return config;
        }
        config.file = configDirectory.resolve(FILE_NAME);
        try {
            if (Files.isReadable(config.file)) {
                for (String line : Files.readAllLines(config.file, StandardCharsets.UTF_8)) {
                    config.acceptLine(line);
                }
                config.loadedFromDisk = true;
            }
        } catch (RuntimeException | IOException e) {
            // Keep whatever parsed before the bad line and fall back to defaults for the rest.
            // UncheckedIOException is not listed alongside IOException because it is a subclass of
            // RuntimeException and a multi-catch may not name both a type and its supertype; catching
            // RuntimeException covers it.
        }
        return config;
    }

    /** Parses one line. Blank lines and lines starting with '#' are ignored. */
    void acceptLine(String line) {
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) == '#' || trimmed.charAt(0) == '!') {
            return;
        }
        int equals = trimmed.indexOf('=');
        if (equals <= 0 || equals == trimmed.length() - 1) {
            return;
        }
        this.values.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1).trim());
    }

    /** Whether the file on disk was read successfully. */
    public boolean loadedFromDisk() {
        return this.loadedFromDisk;
    }

    public Path file() {
        return this.file;
    }

    public boolean bool(String key, boolean fallback) {
        String value = this.values.get(key);
        if (value == null) {
            return fallback;
        }
        if (value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("on")) {
            return true;
        }
        if (value.equalsIgnoreCase("false") || value.equals("0") || value.equalsIgnoreCase("no")
                || value.equalsIgnoreCase("off")) {
            return false;
        }
        return fallback;
    }

    public int integer(String key, int fallback) {
        String value = this.values.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public long longValue(String key, long fallback) {
        String value = this.values.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Sets a value in memory. Does not write to disk. */
    public void set(String key, Object value) {
        this.values.put(key, String.valueOf(value));
    }

    public boolean has(String key) {
        return this.values.containsKey(key);
    }

    public int size() {
        return this.values.size();
    }

    /**
     * Writes the file, creating it with every default listed so a player can see what is available.
     *
     * @return true if the file was written
     */
    public boolean save() {
        if (this.file == null) {
            return false;
        }
        StringBuilder out = new StringBuilder();
        out.append("# AMD-Faster configuration\n");
        out.append("# Every line is optional; anything missing here uses the built-in default.\n");
        out.append("# Delete a line to reset that setting.\n\n");
        appendDocumented(out, KEY_GOVERNOR_ENABLED, "Cap the frame rate when the window is not being watched");
        appendDocumented(out, KEY_UNFOCUSED_FPS, "Frame rate while another application has focus");
        appendDocumented(out, KEY_IDLE_FPS, "Frame rate while focused but idle");
        appendDocumented(out, KEY_IDLE_DELAY_MS, "Milliseconds without input before counting as idle");
        appendDocumented(out, KEY_OVERLAY_ENABLED, "Draw the statistics overlay");
        appendDocumented(out, KEY_BLOCK_CACHE_ENABLED, "Cache block state lookups");
        appendDocumented(out, KEY_BLOCK_CACHE_ENTRIES, "Block state cache entries (power of two)");
        appendDocumented(out, KEY_PARTICLE_CULLING, "Cull particles before drawing them");
        appendDocumented(out, KEY_PARTICLE_LIMIT, "Maximum live particles");
        try {
            if (this.file.getParent() != null) {
                Files.createDirectories(this.file.getParent());
            }
            Files.writeString(this.file, out.toString(), StandardCharsets.UTF_8);
            this.loadedFromDisk = true;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void appendDocumented(StringBuilder out, String key, String comment) {
        out.append("# ").append(comment).append('\n');
        out.append(key).append('=').append(defaultFor(key)).append("\n\n");
    }

    /**
     * The effective value for a key, which is what gets written out.
     *
     * <p>Written from the live value rather than a separate defaults table, so the file a player reads
     * always matches what the mod is actually doing. Two tables drift; one does not.
     */
    public String defaultFor(String key) {
        String current = this.values.get(key);
        return current != null ? current : switch (key) {
            case KEY_GOVERNOR_ENABLED, KEY_OVERLAY_ENABLED, KEY_BLOCK_CACHE_ENABLED, KEY_PARTICLE_CULLING -> "true";
            case KEY_UNFOCUSED_FPS -> String.valueOf(FrameGovernor.DEFAULT_UNFOCUSED_FPS);
            case KEY_IDLE_FPS -> String.valueOf(FrameGovernor.DEFAULT_IDLE_FPS);
            case KEY_IDLE_DELAY_MS -> String.valueOf(FrameGovernor.DEFAULT_IDLE_DELAY_MS);
            case KEY_BLOCK_CACHE_ENTRIES -> "16384";
            case KEY_PARTICLE_LIMIT -> "16384";
            default -> "";
        };
    }
}
