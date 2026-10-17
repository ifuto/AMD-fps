package net.amdfaster.runtime;

import net.amdfaster.mesh.SectionMeshDeduplicator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshRuntimeTest {

    @Test
    void aVisibleSectionIsQueuedAtHigherPriorityThanADistantOne() {
        // Loading a world queues a few hundred sections at once, and building them in the order they were
        // queued means the ones under the player are built last -- the player stands in a hole watching the
        // horizon fill in.
        MeshRuntime runtime = new MeshRuntime();

        int visible = runtime.enqueue(0, 4, 0, 0, true, 0);
        int distant = runtime.enqueue(50, 4, 50, 0, false, 0);

        assertTrue(visible > distant, "visible first");
        assertEquals(1, runtime.queue().pending());
    }

    @Test
    void aSectionThatHasWaitedLongEnoughIsBuiltAnyway() {
        // A section that is never built because something visible keeps arriving is a hole that never fills,
        // and the player would walk into it.
        MeshRuntime runtime = new MeshRuntime();

        int starved = runtime.enqueue(50, 4, 50, 0, false, MeshRuntime.STARVATION_TICKS);
        int fresh = runtime.enqueue(51, 4, 50, 0, false, 0);

        assertTrue(runtime.prioritizer().isStarved(MeshRuntime.STARVATION_TICKS));
        assertTrue(starved >= fresh, "a starved section is not below a fresh invisible one");
    }

    @Test
    void aFaceBehindASolidNeighbourIsNotBuilt() {
        // Decided at mesh build time rather than at draw time, so the geometry never exists -- which is
        // cheaper than building it and culling it later.
        MeshRuntime runtime = new MeshRuntime();

        assertFalse(runtime.shouldBuildFace(true, false));
        assertTrue(runtime.shouldBuildFace(false, false));
        assertEquals(1L, runtime.faces().culled());
        assertEquals(1L, runtime.faces().built());
    }

    @Test
    void aLeafFaceIsCulledAccordingToTheGraphicsSetting() {
        // Leaf textures have holes, so a face behind them can be seen through them, and whether those faces
        // are culled depends on the graphics setting.
        MeshRuntime runtime = new MeshRuntime();

        boolean fast = runtime.shouldCullLeafFace(1, false, false);
        boolean fancy = runtime.shouldCullLeafFace(1, true, false);

        assertTrue(fast, "culled in fast graphics");
        assertFalse(fancy, "and kept in fancy, because the texture has holes");
    }

    @Test
    void ambientOcclusionIsBakedPerFace() {
        MeshRuntime runtime = new MeshRuntime();

        int[] levels = runtime.bakeAmbientOcclusion(
                new boolean[] {true, false, false, false},
                new boolean[] {false, false, false, false});

        assertEquals(4, levels.length, "one level per corner");
    }

    @Test
    void anIdenticalSectionIsNotBuiltTwice() {
        // Two sections of solid stone at different heights produce the same mesh, and building the second one
        // again is pure waste.
        MeshRuntime runtime = new MeshRuntime();
        byte[] content = {1, 2, 3, 4};
        int[] builds = {0};

        byte[] first = runtime.meshFor(content, c -> { builds[0]++; return new byte[] {9, 9}; });
        byte[] second = runtime.meshFor(content, c -> { builds[0]++; return new byte[] {9, 9}; });

        assertEquals(1, builds[0], "built once");
        assertEquals(first, second);
        assertEquals(1, runtime.deduplicator().size());
    }

    @Test
    void differentContentIsBuiltSeparately() {
        MeshRuntime runtime = new MeshRuntime();

        runtime.meshFor(new byte[] {1, 2}, c -> new byte[] {9});
        runtime.meshFor(new byte[] {3, 4}, c -> new byte[] {8});

        assertEquals(2, runtime.deduplicator().size());
    }

    @Test
    void aWorldChangeClearsTheDeduplicator() {
        MeshRuntime runtime = new MeshRuntime();

        runtime.meshFor(new byte[] {1, 2}, c -> new byte[] {9});
        runtime.onSectionBuilt(false);

        runtime.onWorldChange();

        assertEquals(0, runtime.deduplicator().size());
        assertEquals(0L, runtime.sectionsBuilt());
        assertEquals(0L, runtime.sectionsReused());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        MeshRuntime runtime = new MeshRuntime();

        assertThrows(IllegalArgumentException.class, () -> new MeshRuntime(0));
        assertThrows(IllegalArgumentException.class,
                () -> runtime.bakeAmbientOcclusion(new boolean[3], new boolean[4]));
    }

    @Test
    void aFreshRuntimeReportsZeroNotNaN() {
        // A runtime that has built nothing has technically reused everything, and reporting that as a rate
        // would describe an unused one as though it had measured something.
        MeshRuntime runtime = new MeshRuntime();

        assertEquals(0.0, runtime.reusePercent());
        assertEquals(0L, runtime.sectionsBuilt());
        assertEquals(0L, runtime.sectionsReused());
        assertEquals(0, runtime.deduplicator().size());
        assertEquals(4, MeshRuntime.DEFAULT_WORKERS);
        assertEquals(40, MeshRuntime.STARVATION_TICKS);
    }
}
