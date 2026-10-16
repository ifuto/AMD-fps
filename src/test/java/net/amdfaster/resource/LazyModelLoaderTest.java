package net.amdfaster.resource;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LazyModelLoaderTest {

    private static final String GLASS = "minecraft:block/glass";

    @Test
    void nothingIsLoadedUntilItIsAskedFor() {
        // A modpack adds models for blocks that only appear in one dimension, for items the player never
        // crafts, and for states that only occur in generated structures.
        LazyModelLoader loader = new LazyModelLoader();

        assertEquals(0, loader.loadedCount());
        assertFalse(loader.isLoaded(GLASS));
    }

    @Test
    void theFirstRequestLoadsSynchronously() {
        // A model requested mid-game has to be there when it is asked for. Returning nothing would make
        // the first appearance of a block a missing-texture cube that fixes itself on the next frame.
        LazyModelLoader loader = new LazyModelLoader();
        Object glass = new Object();

        Object model = loader.model(GLASS, () -> glass);

        assertSame(glass, model, "the real model, not a placeholder");
        assertTrue(loader.isLoaded(GLASS));
        assertEquals(1L, loader.loads());
    }

    @Test
    void aSecondRequestReusesTheLoadedModel() {
        // Unloading one that was just drawn would mean loading it again next frame, which is slower than
        // not having deferred it at all.
        LazyModelLoader loader = new LazyModelLoader();
        AtomicInteger built = new AtomicInteger();
        Object glass = new Object();

        loader.model(GLASS, () -> { built.incrementAndGet(); return glass; });
        loader.model(GLASS, () -> { built.incrementAndGet(); return glass; });

        assertEquals(1, built.get(), "the definition ran once");
        assertEquals(1L, loader.loads());
        assertEquals(1L, loader.reused());
    }

    @Test
    void aDefinitionThatProducesNothingIsRejected() {
        // Falling back to a missing-texture cube would turn every first appearance of a block into a purple
        // square, which reads as a rendering bug rather than as a loading strategy.
        LazyModelLoader loader = new LazyModelLoader();

        assertThrows(IllegalStateException.class, () -> loader.model(GLASS, () -> null));
    }

    @Test
    void deferredLoadingIsOffByDefault() {
        // Deferring moves work from a loading screen, where the player expects to wait, into gameplay,
        // where a hitch is noticeable. For a fast disk and a small pack the trade is clearly bad.
        LazyModelLoader loader = new LazyModelLoader();

        assertFalse(loader.isEnabled());
        loader.enable();
        assertTrue(loader.isEnabled());
    }

    @Test
    void clearingDropsEveryModel() {
        LazyModelLoader loader = new LazyModelLoader();
        loader.model(GLASS, Object::new);

        loader.clear();

        assertEquals(0, loader.loadedCount());
        assertFalse(loader.isLoaded(GLASS));
    }

    @Test
    void requestingAlmostNothingSavesAlmostEverything() {
        // A player who never visits the Nether never loads its models.
        LazyModelLoader loader = new LazyModelLoader();
        for (int i = 0; i < 100; i++) {
            loader.model("block." + i, Object::new);
        }

        assertEquals(100L, loader.loads());
        assertEquals(2.0, loader.msDeferred(), 1e-9);
        assertEquals(4000.0, loader.msIfAllLoadedAtStartup(), 1e-9);
        assertEquals(99.95, loader.savedPercent(), 0.01);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        LazyModelLoader loader = new LazyModelLoader();

        assertThrows(IllegalArgumentException.class, () -> loader.model(null, Object::new));
        assertThrows(IllegalArgumentException.class, () -> loader.model("", Object::new));
        assertThrows(IllegalArgumentException.class, () -> loader.model(GLASS, null));
    }

    @Test
    void aFreshLoaderReportsZeroNotNaN() {
        // A loader asked for nothing has technically deferred everything, but reporting that as a saving
        // would describe an unused one as though it had done something.
        LazyModelLoader loader = new LazyModelLoader();

        assertEquals(0.0, loader.savedPercent());
        assertEquals(0.0, loader.msDeferred());
        assertEquals(0L, loader.requests());
        assertEquals(0L, loader.loads());
        assertEquals(0, loader.loadedCount());
        assertEquals(200_000, LazyModelLoader.PACK_MODELS);
    }
}
