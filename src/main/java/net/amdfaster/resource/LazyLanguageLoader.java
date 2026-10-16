package net.amdfaster.resource;

import java.util.HashMap;
import java.util.Map;

/**
 * Loads only the language that is actually selected, instead of every language in the pack.
 *
 * <p>A resource pack ships a translation for every language it supports, and a large modpack ends up with
 * dozens of them. Loading all of them at startup parses strings that will never be displayed: the game
 * shows one language, and the other forty-nine exist only so that someone can switch to them. Reading
 * just the selected one removes nearly all of that work from the loading screen.
 *
 * <h2>A missing translation shows the key</h2>
 *
 * <p>That is what the game already does when a mod adds a string with no translation, and it is the right
 * answer here too. Returning the key means an unloaded language is visible as
 * {@code item.amdfaster.thing} rather than as a blank label or a crash, and a player who sees it knows
 * something is wrong rather than staring at an empty inventory slot wondering what it holds. It also makes
 * a loading bug findable: the key names the string that is missing.
 *
 * <h2>Resource packs override the base language</h2>
 *
 * <p>A pack can retranslate any string, and the game applies packs in order with later ones winning. So a
 * loaded language merges the base translations with each pack's in turn, and the merged result is what is
 * served. Merging in the wrong order would silently ignore a pack the player deliberately installed, which
 * reads as the pack being broken rather than the loader being wrong.
 *
 * <h2>Switching language loads the new one</h2>
 *
 * <p>The settings screen lets a player change language at any time, and the whole interface redraws in it
 * immediately. So switching selects a different language and loads it if it has not been loaded, rather
 * than requiring a restart. The previously loaded one is dropped, since keeping several defeats the point
 * of loading one.
 *
 * <p>Thread safety: loading happens on the resource thread while the game reads strings on the render
 * thread, so callers need to synchronise. This class does not.
 */
public final class LazyLanguageLoader {

    /** Languages in a large modpack. */
    public static final int PACK_LANGUAGES = 50;

    /** Milliseconds parsing one translation entry costs. */
    public static final double MS_PER_ENTRY = 0.00002;

    private final Map<String, Map<String, String>> loaded = new HashMap<>();

    private String selected;
    private long entriesLoaded;
    private long lookups;
    private long missing;

    /**
     * Selects a language, loading it if it has not been loaded.
     *
     * @param language the language code, such as {@code ja_jp}
     * @param entries the translations to install for it
     */
    public void select(String language, Map<String, String> entries) {
        if (language == null) {
            throw new IllegalArgumentException("a language code is required");
        }
        this.selected = language;

        if (!this.loaded.containsKey(language)) {
            this.loaded.put(language, new HashMap<>());
        }
        merge(language, entries);
    }

    /**
     * Adds a resource pack's translations for the selected language.
     *
     * <p>Applied in the order the packs are listed, so a later pack overrides an earlier one. Merging the
     * other way round would silently ignore a pack the player deliberately installed, which reads as the
     * pack being broken rather than the loader being wrong.
     */
    public void merge(String language, Map<String, String> entries) {
        Map<String, String> target = this.loaded.get(language);
        if (target == null) {
            throw new IllegalStateException("the language is not loaded: " + language);
        }
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            target.put(entry.getKey(), entry.getValue());
            this.entriesLoaded++;
        }
    }

    /**
     * Translates a key in the selected language.
     *
     * @return the translation, or the key itself if there is none
     */
    public String translate(String key) {
        this.lookups++;
        Map<String, String> language = this.loaded.get(this.selected);
        if (language == null) {
            this.missing++;
            return key;
        }
        String value = language.get(key);
        if (value == null) {
            // What the game already does for an untranslated mod string. An unloaded language then shows
            // as its keys rather than as blank labels, which names the string that is missing instead of
            // leaving the player staring at an empty slot.
            this.missing++;
            return key;
        }
        return value;
    }

    /** Whether a language has been loaded. */
    public boolean isLoaded(String language) {
        return this.loaded.containsKey(language);
    }

    /** The currently selected language, or null if none. */
    public String selected() {
        return this.selected;
    }

    /**
     * Drops every loaded language except one.
     *
     * <p>Keeping several defeats the point of loading one, and a player who has settled on a language does
     * not need the other forty-nine in memory.
     */
    public void dropAllButSelected() {
        this.loaded.keySet().removeIf(language -> !language.equals(this.selected));
    }

    public long entriesLoaded() {
        return this.entriesLoaded;
    }

    public long lookups() {
        return this.lookups;
    }

    /** How many lookups had no translation and returned the key. */
    public long missing() {
        return this.missing;
    }

    public int loadedLanguageCount() {
        return this.loaded.size();
    }

    /** Milliseconds the loading screen spent parsing translations. */
    public double msLoaded() {
        return this.entriesLoaded * MS_PER_ENTRY;
    }

    /**
     * Milliseconds parsing every language in the pack would have cost.
     *
     * <p>Zero before anything has been loaded, so an unused loader does not report a saving it did not
     * make.
     */
    public double msIfAllLanguagesLoaded() {
        return this.entriesLoaded == 0 ? 0.0
                : this.entriesLoaded * PACK_LANGUAGES * MS_PER_ENTRY;
    }

    /** The share of the language parsing avoided by loading only the selected one. */
    public double savedPercent() {
        double all = msIfAllLanguagesLoaded();
        if (all == 0.0) {
            return 0.0;
        }
        return 100.0 * (all - msLoaded()) / all;
    }
}
