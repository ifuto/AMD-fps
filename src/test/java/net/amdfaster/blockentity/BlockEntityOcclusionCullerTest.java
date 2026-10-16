package net.amdfaster.blockentity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockEntityOcclusionCullerTest {

    @Test
    void aChestBehindSolidTerrainIsSkipped() {
        // In a base built into a hillside, every chest in the storeroom behind the player is assembled and
        // submitted, then entirely covered by the terrain in front of it.
        BlockEntityOcclusionCuller culler = new BlockEntityOcclusionCuller();

        assertFalse(culler.shouldDraw(true));
        assertEquals(1L, culler.culled());
        assertEquals(0L, culler.drawn());
    }

    @Test
    void aChestInTheOpenIsAlwaysDrawn() {
        // A chest on a balcony, in a doorway or on the surface is visible from almost any angle, and
        // culling it would make it vanish when the player looks at it -- which is not an optimisation, it
        // is the object disappearing.
        BlockEntityOcclusionCuller culler = new BlockEntityOcclusionCuller();

        assertTrue(culler.shouldDraw(false));
        assertEquals(1L, culler.drawn());
    }

    @Test
    void aStoreroomInsideAMountainCullsAlmostEverything() {
        // A hundred chests behind a wall, and one on the surface.
        BlockEntityOcclusionCuller culler = new BlockEntityOcclusionCuller();

        for (int i = 0; i < 100; i++) {
            culler.shouldDraw(true);
        }
        culler.shouldDraw(false);

        assertEquals(101L, culler.tested());
        assertEquals(100L, culler.culled());
        assertEquals(1L, culler.drawn());
        assertEquals(99.01, culler.culledPercent(), 0.01);
    }

    @Test
    void theSavingIsNetOfTheTests() {
        // A culler whose test cost as much as the draw would save nothing and should report that.
        BlockEntityOcclusionCuller culler = new BlockEntityOcclusionCuller();

        for (int i = 0; i < 100; i++) {
            culler.shouldDraw(true);
        }

        assertEquals(100 * BlockEntityOcclusionCuller.MS_PER_DRAW, culler.msSaved(), 1e-9);
        assertEquals(100 * BlockEntityOcclusionCuller.MS_PER_TEST, culler.msTesting(), 1e-9);
        assertEquals(100 * BlockEntityOcclusionCuller.MS_PER_DRAW, culler.msWithoutCulling(), 1e-9);
        assertEquals(90.0, culler.savedPercent(), 1e-9);
    }

    @Test
    void cullingNothingStillCostsTheTests() {
        // Every block entity is in the open, so nothing is culled but every one was tested. The saving is
        // zero rather than negative, and the honest number is that the tests cost something for nothing.
        BlockEntityOcclusionCuller culler = new BlockEntityOcclusionCuller();

        for (int i = 0; i < 100; i++) {
            culler.shouldDraw(false);
        }

        assertEquals(0L, culler.culled());
        assertEquals(0.0, culler.msSaved(), 1e-9);
        assertEquals(1.0, culler.msTesting(), 1e-9);
        assertEquals(0.0, culler.savedPercent(), 1e-9, "nothing saved, and not reported as a loss");
    }

    @Test
    void aFreshCullerReportsZeroNotNaN() {
        // A culler that has tested nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        BlockEntityOcclusionCuller culler = new BlockEntityOcclusionCuller();

        assertEquals(0.0, culler.culledPercent());
        assertEquals(0.0, culler.savedPercent());
        assertEquals(0.0, culler.msSaved());
        assertEquals(0.0, culler.msTesting());
        assertEquals(0L, culler.tested());
        assertEquals(0.01, BlockEntityOcclusionCuller.MS_PER_DRAW, 1e-12);
    }
}
