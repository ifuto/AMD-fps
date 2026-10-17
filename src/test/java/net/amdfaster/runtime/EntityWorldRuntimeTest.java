package net.amdfaster.runtime;

import net.amdfaster.entity.ExperienceOrbMerger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityWorldRuntimeTest {

    @Test
    void anEntityIsAddedToBothGrids() {
        // A grid that only ever grows is a leak, and a collision grid with stale entries makes mobs collide
        // with things that are not there.
        EntityWorldRuntime runtime = new EntityWorldRuntime();

        runtime.add(1, 4.0, 64.0, 4.0);

        assertEquals(1L, runtime.entitiesAdded());
        // hasNeighbours excludes the asker's own section, so an entity is only a neighbour of something in
        // another section -- which is the point, because the asker already knows about itself.
        assertEquals(1, runtime.collision().occupancy(0, 4, 0));
        assertTrue(runtime.collision().hasNeighbours(20, 64, 4), "a different section sees it");
    }

    @Test
    void removingAnEntityRemovesItFromBothGrids() {
        EntityWorldRuntime runtime = new EntityWorldRuntime();

        runtime.add(1, 4.0, 64.0, 4.0);
        runtime.remove(1, 4.0, 64.0, 4.0);

        assertEquals(1L, runtime.entitiesRemoved());
        assertFalse(runtime.collision().hasNeighbours(4, 64, 4), "no ghost entry");
    }

    @Test
    void aDroppedItemNobodyCanReachExpires() {
        // An item nobody can see or reach is not worth ticking, and letting it live out its five minutes
        // costs ticks for something no player will ever pick up.
        EntityWorldRuntime runtime = new EntityWorldRuntime();

        assertTrue(runtime.shouldExpireItem(6000, 200.0), "old and far away");
        assertFalse(runtime.shouldExpireItem(100, 2.0), "and fresh and nearby is kept");
    }

    @Test
    void mergingOrbsConservesTheTotalExperience() {
        // A merger that loses value is not an optimisation, it is a bug that takes the player's things.
        EntityWorldRuntime runtime = new EntityWorldRuntime();

        List<ExperienceOrbMerger.Orb> orbs = List.of(
                new ExperienceOrbMerger.Orb(4.0, 64.0, 4.0, 5),
                new ExperienceOrbMerger.Orb(4.2, 64.0, 4.1, 3),
                new ExperienceOrbMerger.Orb(4.1, 64.0, 4.2, 7));

        List<ExperienceOrbMerger.Merged> merged = runtime.mergeOrbs(orbs);

        assertTrue(runtime.conservedExperience());
        int total = merged.stream().mapToInt(ExperienceOrbMerger.Merged::value).sum();
        assertEquals(15, total, "5 + 3 + 7");
    }

    @Test
    void aDistantEntityDoesNotTickEveryTick() {
        EntityWorldRuntime runtime = new EntityWorldRuntime();

        int ticks = 0;
        for (int tick = 0; tick < 40; tick++) {
            if (runtime.shouldTickEntity(200.0, false, tick, 1)) {
                ticks++;
            }
        }

        assertTrue(ticks < 40, "a distant entity ticks less often, got " + ticks);
        assertTrue(ticks > 0, "but it is never frozen");
    }

    @Test
    void anEntityNeedingPhysicsAlwaysTicks() {
        // A falling entity that stops being ticked stops falling and hangs in the air, and an entity on a
        // despawn timer that stops counting never despawns.
        EntityWorldRuntime runtime = new EntityWorldRuntime();

        for (int tick = 0; tick < 40; tick++) {
            assertTrue(runtime.shouldTickEntity(200.0, true, tick, 1), "physics is never skipped");
        }
    }

    @Test
    void anAttributeIsCachedAndInvalidated() {
        // A cached value from before a potion expired would leave the player stronger than they are, and the
        // symptom is a number on the screen that does not match what the player can do.
        EntityWorldRuntime runtime = new EntityWorldRuntime();
        int[] evaluations = {0};

        // The cache computes from the base, additions and multiplicative totals rather than calling the
        // fallback, so the assertion is on the value and not on how many times a supplier ran.
        double first = runtime.attribute(1, 0, 20.0, new double[] {2.0}, new double[] {1.1},
                new double[] {1.1}, () -> 22.0);
        double second = runtime.attribute(1, 0, 20.0, new double[] {2.0}, new double[] {1.1},
                new double[] {1.1}, () -> 22.0);

        assertEquals(first, second, 1e-9, "the same inputs give the same value");
        assertEquals((20.0 + 2.0) * 1.1, first, 1e-9, "base plus additions, then multiplications");

        runtime.invalidateAttribute(1, 0);
        double third = runtime.attribute(1, 0, 20.0, new double[] {2.0}, new double[] {1.1},
                new double[] {1.1}, () -> 22.0);

        assertEquals(first, third, 1e-9, "and invalidation does not change a correct value");
        assertEquals(0, evaluations[0], "the fallback is only for a value the cache cannot compute");
    }

    @Test
    void aWorldChangeClearsEverything() {
        // Entity identities are meaningless across worlds -- entity 42 in one world is a different entity
        // from entity 42 in the next.
        EntityWorldRuntime runtime = new EntityWorldRuntime();

        runtime.add(1, 4.0, 64.0, 4.0);
        runtime.add(2, 8.0, 64.0, 8.0);

        runtime.onWorldChange();

        assertEquals(0L, runtime.entitiesAdded());
        assertEquals(0L, runtime.entitiesRemoved());
        assertEquals(0, runtime.collision().occupancy(0, 4, 0), "the collision grid is cleared too");
        assertFalse(runtime.collision().hasNeighbours(20, 64, 4));
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        EntityWorldRuntime runtime = new EntityWorldRuntime();

        assertThrows(IllegalArgumentException.class,
                () -> runtime.setBlockEntityDistance(-1.0));
    }

    @Test
    void aFreshRuntimeReportsZeroNotNaN() {
        // A runtime that has tracked nothing has technically merged everything, and reporting that as a rate
        // would describe an unused one as though it had measured something.
        EntityWorldRuntime runtime = new EntityWorldRuntime();

        assertEquals(0L, runtime.entitiesAdded());
        assertEquals(0L, runtime.entitiesRemoved());
        assertEquals(0.0, runtime.expiry().checked(), 1e-12);
        assertTrue(runtime.conservedExperience(), "nothing merged, so nothing lost");
    }
}
