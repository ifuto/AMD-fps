package net.amdfaster.runtime;

import net.amdfaster.mc.HeightmapIndex;
import net.amdfaster.mc.SectionSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftRuntimeTest {

    @Test
    void theSamplerIsAbsentUntilTheGameSuppliesOne() {
        // It needs a level and a block face source, which only the game can provide. A sampler built against
        // the wrong level would answer for a world that is not loaded.
        MinecraftRuntime runtime = new MinecraftRuntime();

        assertNull(runtime.sampler());
    }

    @Test
    void aSectionSnapshotIsCreatedOnceAndReused() {
        MinecraftRuntime runtime = new MinecraftRuntime();

        SectionSnapshot first = runtime.section(0, 4, 0);
        SectionSnapshot second = runtime.section(0, 4, 0);

        assertSame(first, second);
        assertEquals(1, runtime.sectionsHeld());
    }

    @Test
    void differentSectionsGetDifferentSnapshots() {
        MinecraftRuntime runtime = new MinecraftRuntime();

        runtime.section(0, 4, 0);
        runtime.section(1, 4, 0);
        runtime.section(0, 5, 0);

        assertEquals(3, runtime.sectionsHeld());
    }

    @Test
    void aHeightmapIsCreatedOncePerChunk() {
        MinecraftRuntime runtime = new MinecraftRuntime();

        HeightmapIndex first = runtime.heightmap(0, 0);
        HeightmapIndex second = runtime.heightmap(0, 0);

        assertSame(first, second);
        assertEquals(1, runtime.heightmapsHeld());
    }

    @Test
    void aBlockChangeInvalidatesTheSectionAndItsNeighbours() {
        // Light and height cross section boundaries, so a block placed at the edge of a section changes the
        // heightmap of the section beside it. Invalidating only the section it is in would leave the neighbour
        // with a heightmap that says the sky is visible where it is not.
        MinecraftRuntime runtime = new MinecraftRuntime();

        SectionSnapshot centre = runtime.section(1, 4, 1);
        SectionSnapshot beside = runtime.section(2, 4, 1);
        runtime.section(9, 9, 9);

        runtime.onBlockChange(16, 64, 16);

        assertFalse(centre.isCaptured(), "the section the block is in");
        assertFalse(beside.isCaptured(), "and the one beside it");
        assertEquals(1L, runtime.blockChanges());
        assertTrue(runtime.invalidations() >= 2);
    }

    @Test
    void aBlockChangeLeavesDistantSectionsAlone() {
        // Clearing everything on every block change would throw away the cache the game is actively using,
        // which is worse than not having one -- the cache would never be warm.
        MinecraftRuntime runtime = new MinecraftRuntime();

        SectionSnapshot far = runtime.section(50, 4, 50);
        runtime.onBlockChange(0, 64, 0);

        assertEquals(1, runtime.sectionsHeld(), "the snapshot is still held");
        assertTrue(runtime.sectionsHeld() > 0);
        assertSame(far, runtime.section(50, 4, 50));
    }

    @Test
    void aWorldChangeClearsThePositionCaches() {
        // The positions are meaningless across worlds, so a cache keyed on position would otherwise answer for
        // a world that no longer exists.
        MinecraftRuntime runtime = new MinecraftRuntime();

        runtime.section(0, 4, 0);
        runtime.heightmap(0, 0);

        runtime.onWorldChange();

        assertEquals(0, runtime.sectionsHeld());
        assertEquals(0, runtime.heightmapsHeld());
        assertEquals(0L, runtime.blockChanges());
    }

    @Test
    void aWorldChangeDoesNotEvictAModelSomethingStillReferences() {
        // Evicting a referenced model would leave the block holding it with no geometry, which is worse than
        // keeping a model from the last world -- the block is going away too, and it releases on the way.
        MinecraftRuntime runtime = new MinecraftRuntime();

        runtime.models().acquire(7, id -> new Object());
        runtime.models().acquire(8, id -> new Object());
        runtime.models().release(8);

        runtime.onWorldChange();

        assertEquals(1, runtime.modelsResident(), "the referenced one survives");
        assertTrue(runtime.models().isResident(7));
        assertFalse(runtime.models().isResident(8), "and the unreferenced one goes");
    }

    @Test
    void modelsAreSharedAndReferenceCounted() {
        // Two hundred blocks with the same model need one copy of it, and evicting it while a block still uses
        // it would leave that block with no geometry.
        MinecraftRuntime runtime = new MinecraftRuntime();

        Object first = runtime.models().acquire(3, id -> new Object());
        Object second = runtime.models().acquire(3, id -> new Object());

        assertSame(first, second);
        assertEquals(1, runtime.modelsResident());
        assertEquals(2, runtime.models().referenceCount(3));
    }

    @Test
    void anUnreferencedModelIsEvictedAndAReferencedOneIsNot() {
        MinecraftRuntime runtime = new MinecraftRuntime();

        runtime.models().acquire(1, id -> new Object());
        Object kept = runtime.models().acquire(2, id -> new Object());
        runtime.models().release(1);

        int evicted = runtime.evictUnusedModels();

        assertEquals(1, evicted);
        assertTrue(runtime.models().isResident(2), "still referenced, so kept");
        assertSame(kept, runtime.models().acquire(2, id -> new Object()));
    }

    @Test
    void aBlockChangeWithoutASamplerStillWorks() {
        MinecraftRuntime runtime = new MinecraftRuntime();

        runtime.section(1, 4, 1);
        runtime.onBlockChange(16, 64, 16);

        assertEquals(1L, runtime.blockChanges());
    }

    @Test
    void aFreshRuntimeReportsZeroNotNaN() {
        // A runtime that has looked nothing up has technically cached everything, and reporting that as a rate
        // would describe an unused one as though it had measured something.
        MinecraftRuntime runtime = new MinecraftRuntime();

        assertEquals(0.0, runtime.blockCacheHitPercent());
        assertEquals(0.0, runtime.modelReusePercent());
        assertEquals(0L, runtime.blockChanges());
        assertEquals(0L, runtime.invalidations());
        assertEquals(0, runtime.sectionsHeld());
        assertEquals(0, runtime.heightmapsHeld());
        assertEquals(4096, MinecraftRuntime.DEFAULT_MODEL_CAPACITY);
    }
}
