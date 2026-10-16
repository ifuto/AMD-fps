package net.amdfaster.mob;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreedingDensityCapTest {

    @Test
    void anUncrowdedPenBreedsNormally() {
        BreedingDensityCap cap = new BreedingDensityCap();

        assertTrue(cap.canBreed(10));
        assertEquals(1L, cap.bred());
        assertEquals(0L, cap.refused());
    }

    @Test
    void aFullPenRefusesToBreed() {
        // A pen with a hundred cows is ten thousand comparisons a tick for a pen that is already full, and
        // the offspring make the next tick's search larger.
        BreedingDensityCap cap = new BreedingDensityCap();

        assertFalse(cap.canBreed(BreedingDensityCap.DENSITY_CAP));
        assertFalse(cap.canBreed(100));
        assertEquals(2L, cap.refused());
    }

    @Test
    void theBoundaryIsAtTheCap() {
        BreedingDensityCap cap = new BreedingDensityCap();

        assertTrue(cap.canBreed(BreedingDensityCap.DENSITY_CAP - 1));
        assertFalse(cap.canBreed(BreedingDensityCap.DENSITY_CAP));
    }

    @Test
    void theSearchCostGrowsWithTheSquare() {
        // Every animal searches every other, so the cost grows with the square of the count -- which is why an
        // uncapped pen gets worse faster than it gets bigger.
        BreedingDensityCap cap = new BreedingDensityCap();

        assertEquals(0.08, cap.msAtCap(), 1e-9);
        assertEquals(0.5, cap.msUncapped(100), 1e-9);
        assertEquals(2.0, cap.msUncapped(200), 1e-9, "four times the animals, four times the cost");
    }

    @Test
    void aCrowdedPenIsBoundedAtTheCap() {
        BreedingDensityCap cap = new BreedingDensityCap();

        cap.canBreed(100);

        assertEquals(84.0, cap.savedPercent(100), 1e-9);
        assertEquals(100.0, cap.refusedPercent(), 1e-9);
    }

    @Test
    void aPenThatWasNeverCrowdedSavesNothing() {
        // A pen below the cap has nothing to save, and reporting a saving for it would describe an unused one
        // as though it had done something.
        BreedingDensityCap cap = new BreedingDensityCap();

        cap.canBreed(30);

        assertEquals(0.0, cap.savedPercent(30), 1e-9, "the cap is never reached");
        assertEquals(0L, cap.refused());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        BreedingDensityCap cap = new BreedingDensityCap();

        assertThrows(IllegalArgumentException.class, () -> cap.canBreed(-1));
        assertThrows(IllegalArgumentException.class, () -> cap.msPerTick(-1));
    }

    @Test
    void aFreshCapReportsZeroNotNaN() {
        // Zero before any attempt has been made, rather than the saving it would make if a crowded pen
        // arrived.
        BreedingDensityCap cap = new BreedingDensityCap();

        assertEquals(0.0, cap.savedPercent(100));
        assertEquals(0.0, cap.refusedPercent());
        assertEquals(0L, cap.attempts());
        assertEquals(0L, cap.refused());
        assertEquals(0L, cap.bred());
        assertEquals(40, BreedingDensityCap.DENSITY_CAP);
        assertEquals(16.0, BreedingDensityCap.SEARCH_RANGE, 1e-12);
    }
}
