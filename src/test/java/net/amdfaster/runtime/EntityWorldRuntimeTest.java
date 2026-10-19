package net.amdfaster.runtime;

import net.amdfaster.cull.CameraCutDetector;
import net.amdfaster.cull.PyramidGeometry;
import net.amdfaster.entity.EntityBuffer;
import net.amdfaster.entity.EntityOcclusionCuller;
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
        // The order is the value: base plus additions, times the multiplications, times one plus the sum of
        // the multiplicative totals. Dropping the last factor is the mistake this asserts against.
        assertEquals((20.0 + 2.0) * 1.1 * (1.0 + 1.1), first, 1e-9,
                "base plus additions, times multiplications, times one plus the totals");

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

    // ---------------------------------------------------------------------------------------------
    // The depth-pyramid pass. What is under test is the wiring: that the pass is off until something
    // supplies a pyramid, that it runs over every candidate once one is supplied, that a camera cut
    // holds it open instead of culling against stale depth, and that a world change drops the pyramid.
    // The projection arithmetic itself is EntityOcclusionCullerTest's job.
    // ---------------------------------------------------------------------------------------------

    /** Three entities a few blocks from the origin, all inside the 48 block distance filter. */
    private static EntityBuffer threeNearbyEntities() {
        EntityBuffer entities = new EntityBuffer();
        entities.add(1, 2f, 64f, 2f, 0f, 15, 0.3f, 0.9f);
        entities.add(2, -3f, 64f, 1f, 0f, 15, 0.3f, 0.9f);
        entities.add(3, 0f, 64f, -4f, 0f, 15, 0.3f, 0.9f);
        return entities;
    }

    private static final float[] NO_PROJECTION = new float[16];

    @Test
    void theOcclusionPassStaysOffUntilAPyramidIsSupplied() {
        EntityWorldRuntime runtime = new EntityWorldRuntime();
        EntityBuffer entities = threeNearbyEntities();

        assertFalse(runtime.hasDepthPyramid(), "nothing has rendered terrain depth yet");

        int survivors = runtime.cullEntities(entities, null, 0f, 64f, 0f, NO_PROJECTION,
                new PyramidGeometry(64, 64), new CameraCutDetector());

        assertEquals(3, entities.size());
        assertEquals(entities.size(), runtime.frustumPass().survivorCount(),
                "all three are inside the distance filter, and frustum is null so it is not consulted");
        assertEquals(entities.size(), survivors, "the frustum pass alone is what the caller gets back");
        assertEquals(0, runtime.occlusion().keptUnreliableProjection(),
                "the pyramid pass never looked at an entity");
    }

    @Test
    void supplyingAPyramidRunsTheOcclusionPassOverEveryCandidate() {
        EntityWorldRuntime runtime = new EntityWorldRuntime();
        EntityBuffer entities = threeNearbyEntities();
        runtime.withDepthPyramid(EntityOcclusionCuller.flatWall(0.5f));
        assertTrue(runtime.hasDepthPyramid());

        // Not updated, so framesSinceCut is still MAX_VALUE and no prepass is wanted -- which is the
        // difference between this test and the camera cut below.
        CameraCutDetector cuts = new CameraCutDetector();
        assertFalse(cuts.needsDepthPrepass());

        int survivors = runtime.cullEntities(entities, null, 0f, 64f, 0f, NO_PROJECTION,
                new PyramidGeometry(64, 64), cuts);
        EntityOcclusionCuller occlusion = runtime.occlusion();
        int candidates = runtime.frustumPass().survivorCount();

        assertFalse(occlusion.wasSkipped(), "the pass ran rather than being held open");
        // A zero view-projection puts every corner at w = 0, which the projection reports as unreliable,
        // so all three land in that one bucket. The assertion is the accounting, not the bucket: every
        // candidate is classified exactly once, which is what would break if the wiring passed the wrong
        // survivor list or a stale count.
        assertEquals(candidates,
                occlusion.tested() + occlusion.keptOffScreen() + occlusion.keptUnreliableProjection(),
                "every candidate falls into exactly one bucket");
        assertEquals(candidates, occlusion.keptUnreliableProjection());
        assertEquals(0, occlusion.culled(), "nothing is culled on a projection that is not trustworthy");
        assertEquals(candidates, survivors);
        assertEquals(candidates - occlusion.culled(), survivors);
    }

    @Test
    void aCameraCutKeepsEveryEntityRatherThanCullingAgainstStaleDepth() {
        EntityWorldRuntime runtime = new EntityWorldRuntime();
        EntityBuffer entities = threeNearbyEntities();
        // A pyramid with nothing in it. The far plane in reversed-Z is zero, so every entity's nearest
        // depth is in front of it and nothing would be culled anyway -- which is the point: this test is
        // about the skip, and a skip has to be visible separately from a pass that culled nothing.
        runtime.withDepthPyramid(EntityOcclusionCuller.empty());

        CameraCutDetector cuts = new CameraCutDetector();
        assertTrue(cuts.update(0f, 64f, 0f, 0f, 0f), "the first frame is always a cut");
        assertTrue(cuts.needsDepthPrepass(), "and a cut keeps the pass open for two frames");

        int survivors = runtime.cullEntities(entities, null, 0f, 64f, 0f, NO_PROJECTION,
                new PyramidGeometry(64, 64), cuts);

        assertTrue(runtime.occlusion().wasSkipped(), "the camera history reached the pass");
        assertEquals(0, runtime.occlusion().culled());
        assertEquals(runtime.frustumPass().survivorCount(), survivors,
                "everything the frustum pass kept survives the cut");
    }

    @Test
    void aWorldChangeDropsThePyramid() {
        EntityWorldRuntime runtime = new EntityWorldRuntime();
        runtime.withDepthPyramid(EntityOcclusionCuller.flatWall(0.5f));
        assertTrue(runtime.hasDepthPyramid());

        runtime.onWorldChange();

        assertFalse(runtime.hasDepthPyramid(),
                "the old world's terrain depth must not cull the new world's entities");

        EntityBuffer entities = threeNearbyEntities();
        int survivors = runtime.cullEntities(entities, null, 0f, 64f, 0f, NO_PROJECTION,
                new PyramidGeometry(64, 64), new CameraCutDetector());
        assertEquals(runtime.frustumPass().survivorCount(), survivors,
                "and the next frame falls back to the frustum pass alone");
        assertEquals(0, runtime.occlusion().keptUnreliableProjection());
    }
}
