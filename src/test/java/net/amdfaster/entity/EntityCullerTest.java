package net.amdfaster.entity;

import net.amdfaster.cull.Frustum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The culler is what makes entity count stop mattering, so the properties under test are the ones
 * that bound the work: everything past the distance is gone before anything expensive happens, the
 * budget caps the survivors no matter how many there were, and the ones it keeps are the near ones.
 *
 * <p>The budget test places one entity per distance band, so "keeps the nearest" is exact rather
 * than approximate and the expected cut distance can be written down. The distances were computed
 * separately before being used here.
 */
class EntityCullerTest {

    /** An entity on the +X axis at exactly {@code distance} from a camera at the origin. */
    private static void atDistance(EntityBuffer buffer, float distance) {
        // Height 0 keeps the box centre on the axis, so the distance is exact rather than offset by
        // half an entity height.
        buffer.add(1, distance, 0f, 0f, 0f, 0, 0.25f, 0f);
    }

    @Test
    void everythingWithinRangeSurvives() {
        EntityBuffer buffer = new EntityBuffer();
        atDistance(buffer, 1f);
        atDistance(buffer, 20f);
        atDistance(buffer, 47.9f);

        EntityCuller culler = new EntityCuller();
        culler.cull(buffer, null, 0f, 0f, 0f);

        assertEquals(3, culler.survivorCount());
        assertEquals(0, culler.culledByDistance());
        assertEquals(0, culler.culledByFrustum());
        assertEquals(0, culler.droppedByBudget());
        assertEquals(culler.getMaxDistance(), culler.cutDistance(), 0f,
                "nothing was dropped, so the cut is the full range");
    }

    @Test
    void entitiesPastTheMaxDistanceAreCulled() {
        EntityBuffer buffer = new EntityBuffer();
        atDistance(buffer, 48.0f);   // exactly at the limit is kept; the test is strictly greater
        atDistance(buffer, 48.1f);
        atDistance(buffer, 5000f);

        EntityCuller culler = new EntityCuller();
        culler.cull(buffer, null, 0f, 0f, 0f);

        assertEquals(1, culler.survivorCount());
        assertEquals(2, culler.culledByDistance());
        assertEquals(0, culler.survivor(0), "the survivor is the one at the limit");
    }

    @Test
    void theDistanceIsMeasuredToTheBoxCentreNotTheFeet() {
        // y is the entity's feet, so measuring from there puts a tall entity's distance half a body
        // too far, and tall entities would vanish slightly early.
        EntityBuffer buffer = new EntityBuffer();
        // Feet at y = -4, two blocks tall, so the centre is at y = -3: three blocks from a camera
        // at the origin rather than four.
        buffer.add(1, 0f, -4f, 0f, 0f, 0, 0.25f, 2f);

        EntityCuller culler = new EntityCuller(3.5f, 100);
        culler.cull(buffer, null, 0f, 0f, 0f);
        assertEquals(1, culler.survivorCount(), "the centre is 3 blocks away, inside 3.5");

        EntityCuller strict = new EntityCuller(2.5f, 100);
        strict.cull(buffer, null, 0f, 0f, 0f);
        assertEquals(0, strict.survivorCount(), "and outside 2.5");
    }

    @Test
    void entitiesOutsideTheFrustumAreCulled() {
        // An identity view-projection makes the visible volume [-1,1] x [-1,1] x [0,1], so both
        // expectations can be read off directly.
        EntityBuffer buffer = new EntityBuffer();
        buffer.add(1, 0f, 0.25f, 0.5f, 0f, 0, 0.25f, 0.5f);   // inside the cube
        buffer.add(1, 5f, 0.25f, 0.5f, 0f, 0, 0.25f, 0.5f);   // past the right plane

        EntityCuller culler = new EntityCuller();
        culler.cull(buffer, new Frustum(Frustum.identity()), 0f, 0f, 0f);

        assertEquals(1, culler.survivorCount());
        assertEquals(1, culler.culledByFrustum());
        assertEquals(0, culler.survivor(0));
    }

    @Test
    void aNullFrustumSkipsTheFrustumTest() {
        // For a debug overlay that draws entity boxes, and for tests that want the distance filter
        // on its own.
        EntityBuffer buffer = new EntityBuffer();
        buffer.add(1, 5f, 0.25f, 0.5f, 0f, 0, 0.25f, 0.5f);

        EntityCuller culler = new EntityCuller();
        culler.cull(buffer, null, 0f, 0f, 0f);
        assertEquals(1, culler.survivorCount());
        assertEquals(0, culler.culledByFrustum());
    }

    @Test
    void theBudgetKeepsTheNearestEntities() {
        // One entity per distance band, so the budget's choice is exact: it must keep bands 0 to 9
        // and drop the rest. The band midpoints are 48 * sqrt((b + 0.5) / 64).
        EntityBuffer buffer = new EntityBuffer();
        for (int band = 0; band < 64; band++) {
            atDistance(buffer, (float) (48.0 * Math.sqrt((band + 0.5) / 64.0)));
        }

        EntityCuller culler = new EntityCuller(48f, 10);
        culler.cull(buffer, null, 0f, 0f, 0f);

        assertEquals(64, culler.tested());
        assertEquals(0, culler.culledByDistance(), "every band midpoint is inside the range");
        assertEquals(10, culler.survivorCount());
        assertEquals(54, culler.droppedByBudget());
        for (int i = 0; i < 10; i++) {
            assertEquals(i, culler.survivor(i), "survivor " + i + " should be the " + i + "-th nearest");
        }
    }

    @Test
    void theBudgetReportsRoughlyWhereItCut() {
        EntityBuffer buffer = new EntityBuffer();
        for (int band = 0; band < 64; band++) {
            atDistance(buffer, (float) (48.0 * Math.sqrt((band + 0.5) / 64.0)));
        }
        EntityCuller culler = new EntityCuller(48f, 10);
        culler.cull(buffer, null, 0f, 0f, 0f);

        // The cut is the outer edge of band 9: sqrt(10 / 64 * 48^2) = sqrt(360).
        assertEquals((float) Math.sqrt(360.0), culler.cutDistance(), 1e-3f);
        // And it has to sit between the furthest thing kept and the nearest thing dropped, or the
        // number shown on a debug overlay would be a lie.
        float furthestKept = buffer.x(culler.survivor(culler.survivorCount() - 1));
        assertTrue(furthestKept <= culler.cutDistance(),
                "cut " + culler.cutDistance() + " is nearer than a kept entity at " + furthestKept);
        assertTrue(culler.cutDistance() < 48f, "the cut must be inside the maximum range");
    }

    @Test
    void theBudgetIsNotAppliedWhenNothingNeedsDropping() {
        EntityBuffer buffer = new EntityBuffer();
        for (int i = 0; i < 100; i++) {
            atDistance(buffer, 1f + i * 0.1f);
        }
        EntityCuller culler = new EntityCuller(48f, 1000);
        culler.cull(buffer, null, 0f, 0f, 0f);
        assertEquals(100, culler.survivorCount());
        assertEquals(0, culler.droppedByBudget());
        assertEquals(48f, culler.cutDistance(), 0f);
        // Order is untouched when there is no budget pass, which keeps the upload identical between
        // frames that differ only in how many entities were present.
        for (int i = 0; i < 100; i++) {
            assertEquals(i, culler.survivor(i));
        }
    }

    @Test
    void theCountersAccountForEveryEntity() {
        EntityBuffer buffer = new EntityBuffer();
        buffer.add(1, 0f, 0.25f, 0.5f, 0f, 0, 0.25f, 0.5f);   // survives
        buffer.add(1, 5f, 0.25f, 0.5f, 0f, 0, 0.25f, 0.5f);   // outside the frustum
        atDistance(buffer, 9000f);                            // outside the range

        EntityCuller culler = new EntityCuller();
        culler.cull(buffer, new Frustum(Frustum.identity()), 0f, 0f, 0f);

        assertEquals(3, culler.tested());
        assertEquals(culler.tested(),
                culler.survivorCount() + culler.culledByDistance() + culler.culledByFrustum());
        assertEquals(1, culler.culledByDistance(), "distance is tested first, so it gets the credit");
        assertEquals(1, culler.culledByFrustum());
    }

    @Test
    void cullingTwiceResetsThePreviousFrame() {
        EntityCuller culler = new EntityCuller();

        EntityBuffer first = new EntityBuffer();
        atDistance(first, 1f);
        atDistance(first, 2f);
        culler.cull(first, null, 0f, 0f, 0f);
        assertEquals(2, culler.survivorCount());

        EntityBuffer second = new EntityBuffer();
        atDistance(second, 1f);
        culler.cull(second, null, 0f, 0f, 0f);

        assertEquals(1, culler.survivorCount(), "the first frame's survivors are gone");
        assertEquals(1, culler.tested());
        assertEquals(0, culler.culledByDistance());
        assertEquals(0, culler.survivor(0));
    }

    @Test
    void aLargeFieldIsBoundedByTheBudgetWhateverItsSize() {
        // The point of the class. Fifty thousand entities, and the survivors are the budget.
        EntityBuffer buffer = new EntityBuffer();
        int total = 50000;
        for (int i = 0; i < total; i++) {
            buffer.add(1, (i % 1000) * 0.04f, 0f, (i / 1000) * 0.04f, 0f, 0, 0.01f, 0f);
        }
        EntityCuller culler = new EntityCuller(48f, 512);
        culler.cull(buffer, null, 0f, 0f, 0f);
        assertEquals(total, culler.tested());
        assertEquals(512, culler.survivorCount());
        assertEquals(total - 512 - culler.culledByDistance(), culler.droppedByBudget());
    }

    @Test
    void theConstructorRejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> new EntityCuller(0f, 100));
        assertThrows(IllegalArgumentException.class, () -> new EntityCuller(-1f, 100));
        assertThrows(IllegalArgumentException.class, () -> new EntityCuller(Float.NaN, 100));
        assertThrows(IllegalArgumentException.class, () -> new EntityCuller(48f, 0));
        assertThrows(IllegalArgumentException.class, () -> new EntityCuller(48f, -1));
    }

    @Test
    void theDefaultsAreTheDocumentedOnes() {
        EntityCuller culler = new EntityCuller();
        assertEquals(48f, culler.getMaxDistance(), 0f);
        assertEquals(16384, culler.getBudget());
    }
}
