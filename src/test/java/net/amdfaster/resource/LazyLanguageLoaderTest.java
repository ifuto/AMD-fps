package net.amdfaster.resource;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LazyLanguageLoaderTest {

    private static final Map<String, String> JAPANESE =
            Map.of("item.amdfaster.thing", "物", "gui.amdfaster.settings", "設定");

    @Test
    void onlyTheSelectedLanguageIsLoaded() {
        // A pack ships a translation for every language it supports, and the game shows one. The other
        // forty-nine exist only so that someone can switch to them.
        LazyLanguageLoader loader = new LazyLanguageLoader();

        loader.select("ja_jp", JAPANESE);

        assertEquals(1, loader.loadedLanguageCount());
        assertTrue(loader.isLoaded("ja_jp"));
        assertFalse(loader.isLoaded("en_us"));
        assertEquals("物", loader.translate("item.amdfaster.thing"));
    }

    @Test
    void aMissingTranslationShowsTheKey() {
        // That is what the game already does for an untranslated mod string. An unloaded language then
        // shows as its keys rather than as blank labels, which names the string that is missing.
        LazyLanguageLoader loader = new LazyLanguageLoader();

        loader.select("ja_jp", JAPANESE);

        assertEquals("item.amdfaster.unknown", loader.translate("item.amdfaster.unknown"));
        assertEquals(1L, loader.missing());
    }

    @Test
    void translatingWithNoLanguageSelectedShowsTheKey() {
        LazyLanguageLoader loader = new LazyLanguageLoader();

        assertEquals("gui.amdfaster.settings", loader.translate("gui.amdfaster.settings"));
    }

    @Test
    void aLaterPackOverridesAnEarlierOne() {
        // Merging the other way round would silently ignore a pack the player deliberately installed,
        // which reads as the pack being broken rather than the loader being wrong.
        LazyLanguageLoader loader = new LazyLanguageLoader();

        loader.select("ja_jp", JAPANESE);
        loader.merge("ja_jp", Map.of("item.amdfaster.thing", "アイテム"));

        assertEquals("アイテム", loader.translate("item.amdfaster.thing"));
        assertEquals("設定", loader.translate("gui.amdfaster.settings"), "untouched keys survive");
    }

    @Test
    void switchingLanguageLoadsTheNewOne() {
        // The settings screen lets a player change language at any time and the interface redraws in it
        // immediately, so switching cannot require a restart.
        LazyLanguageLoader loader = new LazyLanguageLoader();

        loader.select("ja_jp", JAPANESE);
        loader.select("en_us", Map.of("item.amdfaster.thing", "Thing"));

        assertEquals("en_us", loader.selected());
        assertEquals("Thing", loader.translate("item.amdfaster.thing"));
        assertEquals(2, loader.loadedLanguageCount());

        loader.dropAllButSelected();
        assertEquals(1, loader.loadedLanguageCount(), "keeping several defeats the point of loading one");
        assertFalse(loader.isLoaded("ja_jp"));
    }

    @Test
    void mergingIntoAnUnloadedLanguageIsRejected() {
        LazyLanguageLoader loader = new LazyLanguageLoader();

        assertThrows(IllegalStateException.class,
                () -> loader.merge("de_de", Map.of("a", "b")));
        assertThrows(IllegalArgumentException.class, () -> loader.select(null, JAPANESE));
    }

    @Test
    void loadingOneLanguageSavesNinetyEightPercent() {
        // 40 000 entries in each of fifty languages, and only one of them is ever displayed.
        LazyLanguageLoader loader = new LazyLanguageLoader();
        Map<String, String> entries = new HashMap<>();
        for (int i = 0; i < 40_000; i++) {
            entries.put("key." + i, "value " + i);
        }

        loader.select("ja_jp", entries);

        assertEquals(40_000L, loader.entriesLoaded());
        assertEquals(0.8, loader.msLoaded(), 1e-9);
        assertEquals(40.0, loader.msIfAllLanguagesLoaded(), 1e-9);
        assertEquals(98.0, loader.savedPercent(), 1e-9);
    }

    @Test
    void aFreshLoaderReportsZeroNotNaN() {
        // Nothing has been parsed, so there is no saving to report.
        LazyLanguageLoader loader = new LazyLanguageLoader();

        assertEquals(0.0, loader.savedPercent());
        assertEquals(0.0, loader.msLoaded());
        assertEquals(0.0, loader.msIfAllLanguagesLoaded());
        assertEquals(0L, loader.lookups());
        assertEquals(0, loader.loadedLanguageCount());
        assertEquals(50, LazyLanguageLoader.PACK_LANGUAGES);
    }
}
