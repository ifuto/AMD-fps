package net.amdfaster.draw;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeltaFramePlannerTest {

    private static final int A = DeltaFramePlanner.pack(0, 4, 0);
    private static final int B = DeltaFramePlanner.pack(1, 4, 0);
    private static final int C = DeltaFramePlanner.pack(-1, 4, 0);

    @Test
    void nothingChangedMeansAnEmptyPlan() {
        DeltaFramePlanner planner = new DeltaFramePlanner();
        planner.setVisible(A, true);

        DeltaFramePlanner.Plan first = planner.plan();
        assertEquals(1, first.drawAddCount, "the draw has to exist once");
        planner.commit(first);

        DeltaFramePlanner.Plan second = planner.plan();
        assertEquals(0, second.touched(), "and then nothing, for as many frames as you like");
        planner.commit(second);

        DeltaFramePlanner.Plan third = planner.plan();
        assertEquals(0, third.touched());
        assertEquals(0, planner.geometryUploads());
    }

    @Test
    void aGeometryChangeUploadsGeometryAndNotLight() {
        DeltaFramePlanner planner = new DeltaFramePlanner();
        planner.setVisible(A, true);
        planner.commit(planner.plan());

        planner.geometryChanged(A);

        DeltaFramePlanner.Plan plan = planner.plan();
        assertEquals(1, plan.geometryUploadCount);
        assertEquals(A, plan.geometryUploads[0]);
        assertEquals(0, plan.lightUploadCount, "the light stream did not change");
        assertEquals(0, plan.drawAddCount, "and it is already drawn");
    }

    @Test
    void aLightChangeDoesNotResendTheMesh() {
        // The common case after placing a block: light propagates fifteen blocks and geometry does not
        // move, so dozens of sections change light and none of them change shape. Re-uploading the
        // whole mesh for each is the waste this avoids.
        DeltaFramePlanner planner = new DeltaFramePlanner();
        for (int i = 0; i < 40; i++) {
            planner.setVisible(DeltaFramePlanner.pack(i, 4, 0), true);
        }
        planner.commit(planner.plan());

        for (int i = 0; i < 40; i++) {
            planner.lightChanged(DeltaFramePlanner.pack(i, 4, 0));
        }
        DeltaFramePlanner.Plan plan = planner.plan();

        assertEquals(40, plan.lightUploadCount);
        assertEquals(0, plan.geometryUploadCount, "no mesh was rebuilt, so no mesh is sent");
        planner.commit(plan);
    }

    @Test
    void aChangeMadeWhileInvisibleIsNotLost() {
        // The case a per-frame dirty flag loses. The section changed while off-screen, the flag was
        // cleared, and when it comes back into view the GPU is sent nothing -- so it draws geometry
        // that no longer matches the blocks.
        DeltaFramePlanner planner = new DeltaFramePlanner();
        planner.geometryChanged(A);
        assertTrue(planner.owesUpload(A), "owed even though it is not visible");

        DeltaFramePlanner.Plan plan = planner.plan();
        assertEquals(1, plan.geometryUploadCount, "uploaded whether or not anything can see it");
        assertEquals(0, plan.drawAddCount);
        planner.commit(plan);

        assertFalse(planner.owesUpload(A), "and now it is not");

        planner.setVisible(A, true);
        DeltaFramePlanner.Plan later = planner.plan();
        assertEquals(1, later.drawAddCount, "becoming visible adds the draw");
        assertEquals(0, later.geometryUploadCount, "without resending data that is already there");
    }

    @Test
    void evictionForcesAResendEvenThoughNothingChanged() {
        // The blocks are the same, but what was on the GPU is gone.
        DeltaFramePlanner planner = new DeltaFramePlanner();
        planner.setVisible(A, true);
        planner.commit(planner.plan());
        assertFalse(planner.owesUpload(A));
        assertTrue(planner.isDrawn(A));

        planner.evict(A);

        assertTrue(planner.owesUpload(A), "the uploaded versions were forgotten");
        assertFalse(planner.isDrawn(A));

        DeltaFramePlanner.Plan plan = planner.plan();
        assertEquals(1, plan.geometryUploadCount);
        assertEquals(1, plan.lightUploadCount);
        assertEquals(1, plan.drawAddCount);
    }

    @Test
    void anUncommittedPlanIsRepeatedNextFrame() {
        // A frame can be dropped after planning -- a resize, a lost device. Committing then would mark
        // work as sent that never was, and the section would never be uploaded.
        DeltaFramePlanner planner = new DeltaFramePlanner();
        planner.setVisible(A, true);
        planner.geometryChanged(A);

        DeltaFramePlanner.Plan planned = planner.plan();
        assertEquals(1, planned.geometryUploadCount);
        // Deliberately not committed.

        DeltaFramePlanner.Plan again = planner.plan();
        assertEquals(1, again.geometryUploadCount, "still owed");
        assertEquals(1, again.drawAddCount);
        assertTrue(planner.owesUpload(A));

        planner.commit(again);
        assertFalse(planner.owesUpload(A));
        assertEquals(0, planner.plan().touched());
    }

    @Test
    void leavingTheVisibleSetRemovesTheDraw() {
        DeltaFramePlanner planner = new DeltaFramePlanner();
        planner.setVisible(A, true);
        planner.commit(planner.plan());
        assertTrue(planner.isDrawn(A));

        planner.setVisible(A, false);
        DeltaFramePlanner.Plan plan = planner.plan();

        assertEquals(1, plan.drawRemoveCount);
        assertEquals(A, plan.drawsToRemove[0]);
        assertEquals(0, plan.geometryUploadCount, "leaving the frustum does not rebuild anything");
        planner.commit(plan);
        assertFalse(planner.isDrawn(A));
    }

    @Test
    void forgettingASectionDropsItEntirely() {
        DeltaFramePlanner planner = new DeltaFramePlanner();
        planner.setVisible(A, true);
        planner.geometryChanged(A);
        planner.commit(planner.plan());
        assertTrue(planner.isTracked(A));

        planner.forget(A);

        assertFalse(planner.isTracked(A));
        assertFalse(planner.owesUpload(A));
        assertEquals(0, planner.plan().touched(), "and it is not planned for again");
    }

    @Test
    void aLightChangeAndAGeometryChangeInOneFrameBothHappen() {
        DeltaFramePlanner planner = new DeltaFramePlanner();
        planner.setVisible(A, true);
        planner.commit(planner.plan());

        planner.geometryChanged(A);
        planner.lightChanged(A);
        planner.geometryChanged(A);

        DeltaFramePlanner.Plan plan = planner.plan();
        assertEquals(1, plan.geometryUploadCount, "two changes, one upload");
        assertEquals(1, plan.lightUploadCount);
    }

    @Test
    void sectionsAreTrackedIndependently() {
        DeltaFramePlanner planner = new DeltaFramePlanner();
        planner.setVisible(A, true);
        planner.setVisible(B, true);
        planner.commit(planner.plan());

        planner.geometryChanged(B);
        planner.lightChanged(C);

        DeltaFramePlanner.Plan plan = planner.plan();
        assertEquals(1, plan.geometryUploadCount);
        assertEquals(B, plan.geometryUploads[0], "only B changed shape");
        assertEquals(1, plan.lightUploadCount);
        assertEquals(C, plan.lightUploads[0], "and only C changed light");
    }

    @Test
    void aFrameWithAFewChangesIsAPercentOfAFullRedraw() {
        // The figure the class is for: 400 visible sections, a handful of them touched.
        DeltaFramePlanner planner = new DeltaFramePlanner(1024);
        for (int i = 0; i < 400; i++) {
            planner.setVisible(DeltaFramePlanner.pack(i, 4, 0), true);
        }
        planner.commit(planner.plan());
        long before = planner.fullRedrawSections();

        for (int i = 0; i < 8; i++) {
            planner.geometryChanged(DeltaFramePlanner.pack(i, 4, 0));
        }
        for (int i = 8; i < 48; i++) {
            planner.lightChanged(DeltaFramePlanner.pack(i, 4, 0));
        }
        DeltaFramePlanner.Plan plan = planner.plan();
        planner.commit(plan);

        // One frame's worth. The counter accumulates across every plan, including the setup one.
        assertEquals(400L, planner.fullRedrawSections() - before,
                "what a full redraw would have visited this frame");
        assertEquals(8L, planner.geometryUploads());
        assertEquals(40L, planner.lightUploads());
        // 8 whole sections plus 40 light streams at a quarter of a section each, over 400.
        assertEquals(4.5, planner.percentOfFullRedraw(), 0.1);
        assertEquals(48, plan.touched());
    }

    @Test
    void growingTheTableKeepsEverySection() {
        DeltaFramePlanner planner = new DeltaFramePlanner(16);

        for (int i = 0; i < 2000; i++) {
            planner.setVisible(DeltaFramePlanner.pack(i % 50, 4, i / 50), true);
        }
        assertEquals(2000, planner.trackedCount());

        DeltaFramePlanner.Plan plan = planner.plan();
        assertEquals(2000, plan.drawAddCount, "nothing was lost while growing");
        planner.commit(plan);

        for (int i = 0; i < 2000; i++) {
            assertTrue(planner.isTracked(DeltaFramePlanner.pack(i % 50, 4, i / 50)),
                    "section " + i + " still tracked");
        }
    }

    @Test
    void growingKeepsTheCountThatDecidesTheNextGrowth() {
        // The bug this catches: grow() called allocate(), which zeroes the count, and the rehash loop
        // did not put it back. The count is what decides when to grow next, so after the first growth
        // it stayed at zero, growth never happened again, the table filled, and probe walked it forever
        // looking for a key that was not there. CI reported that as "in progress" rather than as a
        // failure, which is why the count is asserted after every growth rather than only at the end.
        DeltaFramePlanner planner = new DeltaFramePlanner(16);

        for (int i = 1; i <= 3000; i++) {
            planner.setVisible(i, true);
            assertEquals(i, planner.trackedCount(), "after inserting " + i);
        }
        for (int i = 1; i <= 3000; i++) {
            assertTrue(planner.isTracked(i), "section " + i + " survived every rehash");
        }

        // Fewer than the per-frame cap, since a plan that exceeds it throws rather than clipping.
        // 3,000 still forces several growths out of a table that starts at sixteen.
        DeltaFramePlanner.Plan plan = planner.plan();
        assertEquals(3000, plan.drawAddCount, "every tracked section was visited");
    }

    @Test
    void planVisitsEveryTrackedSectionHoweverTheyHash() {
        // The bug this catches. Entries live wherever the hash put them, so a section can sit at an
        // index well past the number of sections tracked. Scanning the first count slots visited 318
        // of 400 and silently skipped the rest.
        DeltaFramePlanner planner = new DeltaFramePlanner(1024);
        for (int i = 0; i < 400; i++) {
            planner.geometryChanged(i);
        }
        assertEquals(400, planner.trackedCount());

        DeltaFramePlanner.Plan plan = planner.plan();
        assertEquals(400, plan.geometryUploadCount, "no section was skipped");
    }

    @Test
    void sectionKeysPackDistinctly() {
        java.util.Set<Integer> keys = new java.util.HashSet<>();
        long expected = 0;
        for (int x = -40; x < 40; x++) {
            for (int y = -4; y < 20; y++) {
                for (int z = -40; z < 40; z++) {
                    keys.add(DeltaFramePlanner.pack(x, y, z));
                    expected++;
                }
            }
        }
        assertEquals(expected, keys.size());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DeltaFramePlanner(8));
    }

    @Test
    void aFreshPlannerReportsZeroNotNaN() {
        DeltaFramePlanner planner = new DeltaFramePlanner();
        assertEquals(0.0, planner.percentOfFullRedraw());
        assertEquals(0L, planner.frames());
        assertEquals(5332, DeltaFramePlanner.BYTES_PER_SECTION);
    }
}
