package net.amdfaster.resource;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LazyDataFixerTest {

    private static final int CURRENT = 3465;
    private static final int OLD = 1451;

    @Test
    void nothingIsBuiltUntilAWorldIsOpened() {
        // Starting the game builds the whole chain, every version transition since the beginning, for work
        // only used when a world saved by an older version is opened.
        LazyDataFixer fixer = new LazyDataFixer();

        assertEquals(0, fixer.builtCount());
        assertEquals(0L, fixer.builds());
    }

    @Test
    void currentVersionDataIsNotConverted() {
        // A world saved by this version needs no fixer at all. Running it through a chain that does nothing
        // would cost time and risk changing data that was already correct.
        LazyDataFixer fixer = new LazyDataFixer();
        AtomicInteger built = new AtomicInteger();

        assertNull(fixer.fixerFor(CURRENT, CURRENT, () -> { built.incrementAndGet(); return new Object(); }));

        assertEquals(0, built.get(), "the builder was never called");
        assertEquals(1L, fixer.skipped());
        assertEquals(0L, fixer.builds());
    }

    @Test
    void anOldWorldBuildsItsChain() {
        LazyDataFixer fixer = new LazyDataFixer();
        Object chain = new Object();

        Object result = fixer.fixerFor(OLD, CURRENT, () -> chain);

        assertSame(chain, result);
        assertTrue(fixer.isBuilt(OLD));
        assertEquals(1L, fixer.builds());
    }

    @Test
    void openingTheSameWorldTwiceReusesTheChain() {
        LazyDataFixer fixer = new LazyDataFixer();
        AtomicInteger built = new AtomicInteger();
        Object chain = new Object();

        fixer.fixerFor(OLD, CURRENT, () -> { built.incrementAndGet(); return chain; });
        fixer.fixerFor(OLD, CURRENT, () -> { built.incrementAndGet(); return chain; });

        assertEquals(1, built.get());
        assertEquals(1L, fixer.builds());
        assertEquals(1L, fixer.reused());
    }

    @Test
    void aChainThatCannotBeBuiltThrowsRatherThanLoading() {
        // Continuing would mean reading old data with new code, which does not fail loudly -- it produces a
        // world that is subtly wrong: items in the wrong slots, blocks with the wrong state, inventories
        // that have lost things.
        LazyDataFixer fixer = new LazyDataFixer();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> fixer.fixerFor(OLD, CURRENT, () -> null));

        assertTrue(failure.getMessage().contains(String.valueOf(OLD)),
                "the message names the version that could not be converted");
        assertFalse(fixer.isBuilt(OLD));
    }

    @Test
    void aNewerWorldIsRejected() {
        // There is no fixer that goes backwards, and inventing one would corrupt the world.
        LazyDataFixer fixer = new LazyDataFixer();

        assertThrows(IllegalStateException.class,
                () -> fixer.fixerFor(CURRENT + 1, CURRENT, Object::new));
    }

    @Test
    void aPlayerOnTheCurrentVersionSavesAllOfIt() {
        // Most startups never open an old world, so for those the work is simply removed.
        LazyDataFixer fixer = new LazyDataFixer();

        fixer.fixerFor(CURRENT, CURRENT, Object::new);

        assertEquals(270.0, fixer.msIfBuiltAtStartup(), 1e-9);
        assertEquals(0.0, fixer.msBuilt(), 1e-9);
        assertEquals(100.0, fixer.savedPercent(), 1e-9);
    }

    @Test
    void clearingDropsEveryChain() {
        LazyDataFixer fixer = new LazyDataFixer();
        fixer.fixerFor(OLD, CURRENT, Object::new);

        fixer.clear();

        assertEquals(0, fixer.builtCount());
        assertFalse(fixer.isBuilt(OLD));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        LazyDataFixer fixer = new LazyDataFixer();

        assertThrows(IllegalArgumentException.class, () -> fixer.fixerFor(OLD, CURRENT, null));
    }

    @Test
    void aFreshFixerReportsZeroNotNaN() {
        // A fixer registry asked for nothing has technically deferred everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        LazyDataFixer fixer = new LazyDataFixer();

        assertEquals(0.0, fixer.savedPercent());
        assertEquals(0.0, fixer.msBuilt());
        assertEquals(0L, fixer.requests());
        assertEquals(0L, fixer.builds());
        assertEquals(0L, fixer.skipped());
        assertEquals(0, fixer.builtCount());
        assertEquals(90, LazyDataFixer.CHAIN_LENGTH);
    }
}
