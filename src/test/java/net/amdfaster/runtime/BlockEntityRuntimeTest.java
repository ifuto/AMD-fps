package net.amdfaster.runtime;

import net.amdfaster.blockentity.StaticGeometryBaker;
import net.amdfaster.runtime.BlockEntityRuntime.Plan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockEntityRuntimeTest {

    private static final String CHEST = "minecraft:chest";
    private static final String MACHINE = "testmod:machine";
    private static final double HALF_FOV = 35.0;

    /** A block entity in view, not occluded, and fitting inside its block. */
    private static Plan planVisible(BlockEntityRuntime runtime, String type) {
        return runtime.plan(type, 0.0, HALF_FOV, false, false, false, false, false);
    }

    @Test
    void aVisibleBlockEntityIsDrawn() {
        BlockEntityRuntime runtime = new BlockEntityRuntime();

        Plan plan = planVisible(runtime, CHEST);

        assertTrue(plan.drawn());
        assertTrue(plan.rendered());
        assertEquals(1L, runtime.drawn());
    }

    @Test
    void aBlockEntityBehindTheCameraIsNotDrawn() {
        BlockEntityRuntime runtime = new BlockEntityRuntime();

        Plan plan = runtime.plan(CHEST, 180.0, HALF_FOV, false, false, false, false, false);

        assertFalse(plan.drawn());
        assertEquals(1L, runtime.culledFrustum());
    }

    @Test
    void aBlockEntityBehindTerrainIsNotRendered() {
        BlockEntityRuntime runtime = new BlockEntityRuntime();

        Plan plan = runtime.plan(CHEST, 0.0, HALF_FOV, true, false, false, false, false);

        assertFalse(plan.rendered());
        assertEquals(1L, runtime.culledOcclusion());
    }

    @Test
    void anExcludedTypeIsRenderedEvenWhenOccluded() {
        // A player who adds a beacon to the exclusion list has added it everywhere, so the block entity path
        // cannot silently disagree with the entity path about whether the same thing should be drawn.
        BlockEntityRuntime runtime = new BlockEntityRuntime();

        Plan plan = runtime.plan("minecraft:beacon", 0.0, HALF_FOV, true, true, false, false, false);

        assertTrue(plan.rendered());
        assertEquals(1L, runtime.renderers().exempt());
    }

    @Test
    void oversizedGeometryIsNotCulled() {
        // A machine whose model is three blocks tall would be culled as a single block while its geometry is
        // actually visible, and the player would see it half missing with no way to know why.
        BlockEntityRuntime runtime = new BlockEntityRuntime();

        Plan plan = runtime.plan(MACHINE, 0.0, HALF_FOV, true, false, true, false, false);

        assertTrue(plan.rendered());
        assertEquals(1L, runtime.renderers().oversized());
    }

    @Test
    void anOpenContainerRefreshesOnlyWhenItsContentsChange() {
        BlockEntityRuntime runtime = new BlockEntityRuntime();

        assertTrue(runtime.plan(CHEST, 0.0, HALF_FOV, false, false, false, true, true).refreshScreen());
        assertFalse(runtime.plan(CHEST, 0.0, HALF_FOV, false, false, false, true, false).refreshScreen(),
                "open but unchanged");
        assertFalse(runtime.plan(CHEST, 0.0, HALF_FOV, false, false, false, false, true).refreshScreen(),
                "changed but closed");
    }

    @Test
    void bakingStaticGeometryDoesNotRemoveTheBlockEntity() {
        // A chest body baked into the chunk mesh still has a lid that opens. Treating a baked block entity as
        // gone would drop the lid, the contents and the open animation with it, which is not an optimisation
        // but a chest that does not open.
        BlockEntityRuntime runtime = new BlockEntityRuntime();

        int baked = runtime.bake(CHEST,
                List.of(new StaticGeometryBaker.Part("body", 22, false)),
                List.of(new StaticGeometryBaker.Part("lid", 6, true)));

        assertEquals(22, baked);
        assertTrue(runtime.isBaked(CHEST));

        Plan plan = planVisible(runtime, CHEST);
        assertTrue(plan.drawn(), "the block entity still exists and still draws");
        assertTrue(plan.rendered(), "because the lid still animates");
    }

    @Test
    void aFullyAnimatedBlockEntityKeepsItsRenderer() {
        BlockEntityRuntime runtime = new BlockEntityRuntime();

        int baked = runtime.bake("minecraft:beacon",
                List.of(),
                List.of(new StaticGeometryBaker.Part("beam", 12, true)));

        assertEquals(0, baked);
        assertTrue(runtime.baker().keptRenderer("minecraft:beacon"));
    }

    @Test
    void aStillAnimationIsNotEvaluated() {
        BlockEntityRuntime runtime = new BlockEntityRuntime();

        assertFalse(runtime.shouldAnimate(0.0, 0.0));
        assertTrue(runtime.shouldAnimate(0.5, 0.0), "and a moving one is");
        assertEquals(1L, runtime.animations().skipped());
    }

    @Test
    void theCountersAddUpToTheBlockEntitiesSeen() {
        BlockEntityRuntime runtime = new BlockEntityRuntime();

        for (int i = 0; i < 50; i++) {
            runtime.plan(CHEST, i % 2 == 0 ? 0.0 : 180.0, HALF_FOV, false, false, false, false, false);
        }

        assertEquals(50L, runtime.seen());
        assertEquals(runtime.seen(), runtime.drawn() + runtime.culled(),
                "every block entity is either drawn or culled, with no double counting");
        assertEquals(25L, runtime.drawn());
        assertEquals(25L, runtime.culledFrustum());
    }

    @Test
    void resetClearsTheCounters() {
        BlockEntityRuntime runtime = new BlockEntityRuntime();

        for (int i = 0; i < 10; i++) {
            planVisible(runtime, CHEST);
        }
        runtime.reset();

        assertEquals(0L, runtime.seen());
        assertEquals(0L, runtime.drawn());
        assertEquals(0L, runtime.culled());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        BlockEntityRuntime runtime = new BlockEntityRuntime();

        assertThrows(IllegalArgumentException.class,
                () -> runtime.plan(null, 0.0, HALF_FOV, false, false, false, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> runtime.plan("", 0.0, HALF_FOV, false, false, false, false, false));
    }

    @Test
    void aFreshRuntimeReportsZeroNotNaN() {
        // A runtime that has seen nothing has technically culled nothing, and reporting that as a rate would
        // describe an unused one as though it had measured something.
        BlockEntityRuntime runtime = new BlockEntityRuntime();

        assertEquals(0.0, runtime.culledPercent());
        assertEquals(0.0, runtime.msSaved(), 1e-12);
        assertEquals(0L, runtime.seen());
        assertEquals(0L, runtime.drawn());
        assertEquals(0L, runtime.culled());
        assertFalse(runtime.isBaked(CHEST));
    }
}
