package net.amdfaster.blockentity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModRendererCullerTest {

    @Test
    void aHiddenMachineIsNotRendered() {
        // A machine inside a wall is drawn every frame for as long as the chunk is loaded, because the game's
        // own idea of "in view" is the chunk.
        ModRendererCuller culler = new ModRendererCuller();

        assertFalse(culler.shouldRender(true, false, false));
        assertEquals(1L, culler.culled());
    }

    @Test
    void aVisibleMachineIsRendered() {
        ModRendererCuller culler = new ModRendererCuller();

        assertTrue(culler.shouldRender(false, false, false));
        assertEquals(1L, culler.drawn());
        assertEquals(0L, culler.culled());
    }

    @Test
    void anExcludedTypeIsAlwaysRendered() {
        // A beacon's beam, a portal's glow, a marker a player placed to find their way back. Culling those is
        // not an optimisation, it is removing something the player is relying on.
        ModRendererCuller culler = new ModRendererCuller();

        assertTrue(culler.shouldRender(true, true, false));
        assertEquals(1L, culler.exempt());
        assertEquals(0L, culler.culled());
    }

    @Test
    void oversizedGeometryIsNotCulled() {
        // A machine whose model is three blocks tall would be culled as a single block while its geometry is
        // actually visible, and the player would see it half missing with no way to know why.
        ModRendererCuller culler = new ModRendererCuller();

        assertTrue(culler.shouldRender(true, false, true));
        assertEquals(1L, culler.oversized());
        assertEquals(0L, culler.culled());
    }

    @Test
    void aFailedTestDrawsTheRenderer() {
        // Failing open means a block entity is drawn when the answer is unknown. Drawing something hidden
        // costs a frame; not drawing something visible costs the player their machine.
        ModRendererCuller culler = new ModRendererCuller();

        culler.failedTest();

        assertEquals(1L, culler.failedOpen());
        assertEquals(1L, culler.drawn());
    }

    @Test
    void itDoesNotNeedToKnowTheMod() {
        // Position and occlusion are properties of the world rather than of the mod, so the same test covers a
        // machine from a mod added yesterday and a chest from the vanilla game.
        ModRendererCuller culler = new ModRendererCuller();

        assertFalse(culler.shouldRender(true, false, false), "an unknown mod's machine");
        assertFalse(culler.shouldRender(true, false, false), "and any other");
        assertEquals(2L, culler.culled());
    }

    @Test
    void aModpackSavesMostOfTheRendererCost() {
        // Twenty mods with fifteen machines each is three hundred renderers a frame, most of them drawing into
        // a wall.
        ModRendererCuller culler = new ModRendererCuller();

        for (int i = 0; i < 300; i++) {
            culler.shouldRender(i < 210, false, false);
        }

        assertEquals(300L, culler.tested());
        assertEquals(210L, culler.culled());
        assertEquals(70.0, culler.culledPercent(), 1e-9);
        assertEquals(210 * ModRendererCuller.MS_PER_RENDER, culler.msSaved(), 1e-9);
        assertEquals(6.3, culler.msSaved(), 1e-9);
        assertEquals(0.3, culler.msTesting(), 1e-9);
        assertEquals(66.6667, culler.savedPercent(), 0.001,
                "6.3 saved minus 0.3 of testing, out of 9.0");
    }

    @Test
    void theSavingIsNetOfTheTests() {
        // A culler whose test cost as much as the render would save nothing and should report that.
        ModRendererCuller culler = new ModRendererCuller();

        for (int i = 0; i < 10; i++) {
            culler.shouldRender(true, false, false);
        }

        assertEquals(10 * ModRendererCuller.MS_PER_RENDER, culler.msWithoutCulling(), 1e-9);
        assertEquals(96.6667, culler.savedPercent(), 0.001);
    }

    @Test
    void aFreshCullerReportsZeroNotNaN() {
        // A culler that has tested nothing has technically avoided everything, but reporting that as a saving
        // would describe an unused one as though it had done something.
        ModRendererCuller culler = new ModRendererCuller();

        assertEquals(0.0, culler.culledPercent());
        assertEquals(0.0, culler.savedPercent());
        assertEquals(0.0, culler.msSaved(), 1e-12);
        assertEquals(0.0, culler.msTesting(), 1e-12);
        assertEquals(0L, culler.tested());
        assertEquals(0L, culler.failedOpen());
        assertEquals(0.03, ModRendererCuller.MS_PER_RENDER, 1e-12);
    }
}
