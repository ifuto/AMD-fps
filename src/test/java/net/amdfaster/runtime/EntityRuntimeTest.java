package net.amdfaster.runtime;

import net.amdfaster.entity.EntityLodSelector;
import net.amdfaster.runtime.EntityRuntime.Plan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityRuntimeTest {

    private static final String ZOMBIE = "minecraft:zombie";
    private static final String BEACON = "minecraft:beacon_beam";

    /** An entity close by, in view, on the ground, and large on screen. */
    private static Plan planNear(EntityRuntime runtime, int id) {
        return runtime.plan(id, ZOMBIE, 8.0, false, false, 0.0, 0.0, false, 0.0, 0.0, 200.0);
    }

    @Test
    void aVisibleEntityIsDrawn() {
        EntityRuntime runtime = new EntityRuntime();

        Plan plan = planNear(runtime, 1);

        assertTrue(plan.drawn());
        assertTrue(plan.ticks());
        assertEquals(1L, runtime.drawn());
        assertEquals(1, runtime.tracked());
    }

    @Test
    void anEntityWithNoVisibilityAnswerYetIsDrawn() {
        // The line-of-sight test runs on a worker thread, so for the first few ticks there is no answer.
        // Guessing "hidden" would make entities pop in as the answers arrive, which is exactly the artefact
        // culling exists to remove.
        EntityRuntime runtime = new EntityRuntime();

        Plan plan = planNear(runtime, 1);

        assertTrue(plan.drawn(), "occlusionKnown was false");
        assertFalse(runtime.workerFor(1).isVisible() == false && plan.drawn() == false);
    }

    @Test
    void aHiddenEntityIsNotDrawn() {
        EntityRuntime runtime = new EntityRuntime();

        runtime.workerFor(1).answer(0, true);
        Plan plan = runtime.plan(1, ZOMBIE, 8.0, false, false, 0.0, 0.0, true, 0.0, 0.0, 200.0);

        assertFalse(plan.drawn());
        assertEquals(EntityLodSelector.Tier.CULL, plan.tier());
        assertEquals(1L, runtime.culled());
    }

    @Test
    void anExcludedTypeIsDrawnEvenWhenHidden() {
        // A beacon beam or a portal is drawn whatever the occlusion test says, so checking it first means
        // nothing downstream has to know about it.
        EntityRuntime runtime = new EntityRuntime();
        runtime.exclusions().exclude(BEACON);

        runtime.workerFor(1).answer(0, true);
        Plan plan = runtime.plan(1, BEACON, 8.0, false, false, 0.0, 0.0, true, 0.0, 0.0, 200.0);

        assertTrue(plan.drawn());
    }

    @Test
    void eachEntityGetsItsOwnWorker() {
        // A single shared answer would cull everything or nothing, and the schedule that staggers the tests
        // would have nothing to stagger.
        EntityRuntime runtime = new EntityRuntime();

        runtime.workerFor(1).answer(0, true);
        runtime.workerFor(2).answer(0, false);

        assertFalse(runtime.workerFor(1).isVisible());
        assertTrue(runtime.workerFor(2).isVisible());
        assertEquals(2, runtime.tracked());
    }

    @Test
    void theSameWorkerIsReturnedForTheSameEntity() {
        EntityRuntime runtime = new EntityRuntime();

        assertSame(runtime.workerFor(7), runtime.workerFor(7));
        assertEquals(1, runtime.tracked());
    }

    @Test
    void forgettingAnEntityRemovesItsWorker() {
        EntityRuntime runtime = new EntityRuntime();

        runtime.workerFor(1);
        runtime.workerFor(2);
        runtime.forget(1);

        assertEquals(1, runtime.tracked());
    }

    @Test
    void aDistantEntityDoesNotTickEveryTick() {
        EntityRuntime runtime = new EntityRuntime();

        int ticks = 0;
        for (int i = 0; i < 40; i++) {
            Plan plan = runtime.plan(1, ZOMBIE, 100.0, false, false, 0.0, 0.0, false,
                    0.0, 0.0, 200.0);
            if (plan.ticks()) {
                ticks++;
            }
            runtime.endTick();
        }

        assertTrue(ticks < 40, "an inactive entity ticks less often, got " + ticks);
        assertTrue(ticks > 0, "but it is never frozen");
    }

    @Test
    void anEntityNeedingPhysicsAlwaysTicks() {
        // A falling entity that stops being ticked stops falling and hangs in the air, and an entity on a
        // despawn timer that stops counting never despawns.
        EntityRuntime runtime = new EntityRuntime();

        for (int i = 0; i < 40; i++) {
            Plan plan = runtime.plan(1, ZOMBIE, 200.0, false, true, 0.0, 1.5, false,
                    0.0, 0.0, 200.0);
            assertTrue(plan.ticks(), "physics is never skipped");
            runtime.endTick();
        }
    }

    @Test
    void aPlayerAlwaysTicks() {
        EntityRuntime runtime = new EntityRuntime();

        for (int i = 0; i < 20; i++) {
            Plan plan = runtime.plan(1, "minecraft:player", 200.0, true, false, 0.0, 0.0, false,
                    0.0, 0.0, 200.0);
            assertTrue(plan.ticks());
            runtime.endTick();
        }
    }

    @Test
    void aVisibilityAnswerReachesTheRightWorker() {
        EntityRuntime runtime = new EntityRuntime();

        runtime.workerFor(3);
        runtime.visibilityAnswer(3, 0, true);
        runtime.visibilityAnswer(99, 0, true);

        assertFalse(runtime.workerFor(3).isVisible());
        assertTrue(runtime.workerFor(99).isVisible(), "an unknown entity is ignored, not created hidden");
        assertEquals(1, runtime.tracked());
    }

    @Test
    void theCountersAddUpToTheEntitiesSeen() {
        EntityRuntime runtime = new EntityRuntime();

        for (int i = 0; i < 50; i++) {
            runtime.plan(i, ZOMBIE, 8.0, false, false, 0.0, 0.0, false, 0.0, 0.0, 200.0);
        }

        assertEquals(50L, runtime.entitiesSeen());
        assertEquals(runtime.entitiesSeen(), runtime.drawn() + runtime.culled(),
                "every entity is either drawn or culled, with no double counting");
    }

    @Test
    void resetClearsEverything() {
        EntityRuntime runtime = new EntityRuntime();

        for (int i = 0; i < 10; i++) {
            planNear(runtime, i);
        }
        runtime.reset();

        assertEquals(0L, runtime.entitiesSeen());
        assertEquals(0L, runtime.drawn());
        assertEquals(0L, runtime.culled());
        assertEquals(0L, runtime.tick());
        assertEquals(0, runtime.tracked());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        EntityRuntime runtime = new EntityRuntime();

        assertThrows(IllegalArgumentException.class,
                () -> runtime.plan(1, null, 8.0, false, false, 0.0, 0.0, false, 0, 0, 200.0));
        assertThrows(IllegalArgumentException.class,
                () -> runtime.plan(1, "", 8.0, false, false, 0.0, 0.0, false, 0, 0, 200.0));
    }

    @Test
    void aFreshRuntimeReportsZeroNotNaN() {
        // A runtime that has seen nothing has technically culled nothing, and reporting that as a rate would
        // describe an unused one as though it had measured something.
        EntityRuntime runtime = new EntityRuntime();

        assertEquals(0.0, runtime.culledPercent());
        assertEquals(0L, runtime.entitiesSeen());
        assertEquals(0L, runtime.drawn());
        assertEquals(0L, runtime.culled());
        assertEquals(0, runtime.tracked());
        assertEquals(0, runtime.exclusions().size());
    }
}
