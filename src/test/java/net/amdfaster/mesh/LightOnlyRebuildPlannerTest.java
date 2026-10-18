package net.amdfaster.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.amdfaster.mesh.LightOnlyRebuildPlanner.Work;
import org.junit.jupiter.api.Test;

/**
 * The numbers here are the mesh format's, not chosen ones: 992 bytes of light inside 5,332 bytes of
 * meshlet, which is what makes a light-only rewrite 81.40 per cent of a section smaller rather than some
 * other fraction.
 */
class LightOnlyRebuildPlannerTest {

    @Test
    void theConstantsAreTheMeshFormats() {
        // 62 quads x 4 vertices x a 4 byte light stride, against 5,332 for the whole meshlet. If the
        // format changes these have to move with it, and a test that hard-codes a percentage would not
        // notice.
        assertEquals(992, LightOnlyRebuildPlanner.LIGHT_BYTES_PER_MESHLET);
        assertEquals(5332, LightOnlyRebuildPlanner.TOTAL_BYTES_PER_MESHLET);
        assertEquals(4340, LightOnlyRebuildPlanner.BYTES_AVOIDED_PER_MESHLET);
        assertEquals(81.40, LightOnlyRebuildPlanner.savedPercent(), 0.01);
    }

    @Test
    void aBlockChangeRebuildsTheGeometryAndTheLightWithIt() {
        LightOnlyRebuildPlanner planner = new LightOnlyRebuildPlanner();

        assertEquals(Work.GEOMETRY_AND_LIGHT, planner.plan(true, false));
        // Geometry subsumes light: ambient occlusion is baked from the light and the mesh is rebuilt from
        // both, so a geometry rebuild already recomputes the light.
        assertEquals(Work.GEOMETRY_AND_LIGHT, planner.plan(true, true));
        assertEquals(2L, planner.geometryRebuilds());
        assertEquals(0L, planner.lightOnly());
    }

    @Test
    void beingOfferedBothIsCountedRatherThanPlannedTwice() {
        // Uploading the light stream twice would carry the same bytes the second time. Refusing is right,
        // but a caller that keeps offering the pair is not dropping its light entry when geometry covers
        // it, and that is worth seeing.
        LightOnlyRebuildPlanner planner = new LightOnlyRebuildPlanner();

        planner.plan(true, true);

        assertEquals(Work.GEOMETRY_AND_LIGHT, planner.plan(true, false));
        assertEquals(1L, planner.redundantRefused());
        assertEquals(2L, planner.geometryRebuilds(), "each planned as one geometry rebuild");
    }

    @Test
    void aLightOnlyChangeSkipsTheMesh() {
        LightOnlyRebuildPlanner planner = new LightOnlyRebuildPlanner();

        assertEquals(Work.LIGHT_ONLY, planner.plan(false, true));
        assertEquals(1L, planner.lightOnly());
        assertEquals(0L, planner.geometryRebuilds());
    }

    @Test
    void aSectionDirtyForNeitherReasonShouldNotHaveBeenDrained() {
        // Reaching here means the pending set handed over a section nothing marked, which is a bug in the
        // scheduler and not a case to answer politely.
        LightOnlyRebuildPlanner planner = new LightOnlyRebuildPlanner();

        assertThrows(IllegalArgumentException.class, () -> planner.plan(false, false));
    }

    @Test
    void theUploadIsTheLightStreamAloneAndNothingElse() {
        LightOnlyRebuildPlanner planner = new LightOnlyRebuildPlanner();

        assertEquals(4 * 992, planner.uploadBytes(Work.LIGHT_ONLY, 4));
        assertEquals(4 * 5332, planner.uploadBytes(Work.GEOMETRY_AND_LIGHT, 4));
        assertEquals(21_328L, planner.bytesPlanned());
        // Only the light-only plan avoids anything; the geometry rebuild uploads the whole meshlet.
        assertEquals(4L * 4340, planner.bytesAvoided());
    }

    @Test
    void theSavingScalesWithTheMeshletCount() {
        assertEquals(0, LightOnlyRebuildPlanner.bytesSaved(0));
        assertEquals(4340, LightOnlyRebuildPlanner.bytesSaved(1));
        assertEquals(17_360, LightOnlyRebuildPlanner.bytesSaved(4));
        assertEquals(34_720, LightOnlyRebuildPlanner.bytesSaved(8));
        // The percentage does not move, because both terms scale with the count.
        assertEquals(81.40, LightOnlyRebuildPlanner.savedPercent(), 0.01);
    }

    @Test
    void nonsenseInputsAreRejected() {
        LightOnlyRebuildPlanner planner = new LightOnlyRebuildPlanner();

        assertThrows(IllegalArgumentException.class, () -> planner.uploadBytes(null, 4));
        assertThrows(IllegalArgumentException.class, () -> planner.uploadBytes(Work.LIGHT_ONLY, -1));
        assertThrows(IllegalArgumentException.class, () -> LightOnlyRebuildPlanner.bytesSaved(-1));
    }

    @Test
    void aFreshPlannerReportsZeroNotNaN() {
        LightOnlyRebuildPlanner planner = new LightOnlyRebuildPlanner();

        assertEquals(0L, planner.planned());
        assertEquals(0L, planner.geometryRebuilds());
        assertEquals(0L, planner.lightOnly());
        assertEquals(0L, planner.bytesPlanned());
        assertEquals(0L, planner.bytesAvoided());
        assertEquals(0L, planner.redundantRefused());
    }

    @Test
    void everyPlannedSectionIsCountedExactlyOnce() {
        LightOnlyRebuildPlanner planner = new LightOnlyRebuildPlanner();

        planner.plan(true, false);
        planner.plan(false, true);
        planner.plan(true, true);

        assertEquals(3L, planner.planned());
        assertEquals(3L, planner.geometryRebuilds() + planner.lightOnly(),
                "nothing falls through uncounted");
    }
}
