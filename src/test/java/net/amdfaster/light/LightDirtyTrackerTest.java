package net.amdfaster.light;

import java.util.List;

import net.amdfaster.dirty.SectionCoord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightDirtyTrackerTest {

    @Test
    void anUnchangedSectionIsNotReportedAndThatIsTheWholePoint() {
        // A light update walks through far more sections than it changes: propagation crosses a border
        // and finds every cell already correct. Before this, each of those sections was re-meshed
        // anyway, and placing one block in Minecraft causes on the order of fifty chunk rebuilds for
        // exactly that reason -- rebuild being the dominant client stutter there is.
        LightField centre = LightField.forSection(0, 4, 0);
        LightField neighbour = LightField.forSection(1, 4, 0);
        centre.fill(7);
        neighbour.fill(7);

        LightDirtyTracker tracker = new LightDirtyTracker();
        tracker.snapshot(centre);
        tracker.snapshot(neighbour);
        assertEquals(2, tracker.sectionsTracked(), "L28");

        // Only the centre actually changes.
        centre.set(8, 64, 8, 15);

        List<Long> dirty = tracker.collectDirty(centre, neighbour);
        assertEquals(1, dirty.size(), "L34");
        assertEquals(SectionCoord.key(0, 4, 0), dirty.get(0), "L35");
        assertEquals(1, tracker.sectionsReportedDirty(), "L36");
        assertEquals(1, tracker.rebuildsAvoided(), "the neighbour's meshing was skipped");
    }

    @Test
    void checksumsAgreeForIdenticalLightAndDifferForAnyChange() {
        LightField a = LightField.forSection(0, 0, 0);
        LightField b = LightField.forSection(1, 0, 0);
        a.fill(9);
        b.fill(9);
        assertEquals(LightDirtyTracker.checksum(a), LightDirtyTracker.checksum(b),
                "the same levels anywhere hash the same");

        b.set(16, 0, 0, 10);
        assertTrue(LightDirtyTracker.checksum(a) != LightDirtyTracker.checksum(b),
                "one cell brighter is a different section as far as meshing is concerned");
    }

    @Test
    void theChecksumDependsOnWhereTheLightIsNotJustHowMuch() {
        // Two sections with the same levels in different cells must not collide, or a change would be
        // reported as no change and the section would keep stale vertex data -- a permanently wrong
        // image, which is worse than an unnecessary rebuild.
        LightField a = LightField.forSection(0, 0, 0);
        LightField b = LightField.forSection(0, 0, 0);
        a.set(0, 0, 0, 15);
        b.set(15, 15, 15, 15);

        assertEquals(1, a.litCellCount(), "L64");
        assertEquals(1, b.litCellCount(), "L65");
        assertTrue(LightDirtyTracker.checksum(a) != LightDirtyTracker.checksum(b), "L66");
    }

    @Test
    void everyCellPositionIsCoveredByTheChecksum() {
        // A rolling hash can quietly ignore part of the volume, which would make changes there
        // invisible. Sweeping one cell at a time over the whole section proves none of it is.
        LightField base = LightField.forSection(0, 0, 0);
        long empty = LightDirtyTracker.checksum(base);
        int differing = 0;
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    base.set(x, y, z, 1);
                    if (LightDirtyTracker.checksum(base) != empty) {
                        differing++;
                    }
                    base.set(x, y, z, 0);
                }
            }
        }
        assertEquals(LightField.SECTION_CELLS, differing, "every cell is visible to the checksum");
    }

    @Test
    void snapshottingTheSameSectionTwiceCountsItOnce() {
        LightField field = LightField.forSection(0, 0, 0);
        LightDirtyTracker tracker = new LightDirtyTracker();
        tracker.snapshot(field);
        tracker.snapshot(field);
        assertEquals(1, tracker.sectionsTracked(), "L96");
        assertEquals(1, tracker.snapshottedKeys().size(), "L97");
    }

    @Test
    void aNullSectionIsIgnoredRatherThanThrowing() {
        // Callers ask to snapshot every section an update might touch, and the dark ones have no field
        // at all. Refusing to track those would force the caller to check first, which is the kind of
        // guard that gets forgotten.
        LightDirtyTracker tracker = new LightDirtyTracker();
        tracker.snapshot(null);
        assertEquals(0, tracker.sectionsTracked(), "L107");
        assertFalse(tracker.hasSnapshots(), "L108");
        assertTrue(tracker.collectDirty().isEmpty(), "L109");
    }

    @Test
    void aSectionGoneByCollectTimeCountsAsChanged() {
        // A section that held light and no longer exists has definitely changed as far as the mesh is
        // concerned, and treating a missing field as a checksum of zero is what says so.
        LightField field = LightField.forSection(0, 4, 0);
        field.fill(7);
        LightDirtyTracker tracker = new LightDirtyTracker();
        tracker.snapshot(field);

        List<Long> dirty = tracker.collectDirty();
        assertEquals(1, dirty.size(), "no field supplied, so it reads as dark now");
    }

    @Test
    void aFreshSectionIsNotReportedWhenItHeldNothingBefore() {
        LightField field = LightField.forSection(0, 4, 0);
        LightDirtyTracker tracker = new LightDirtyTracker();
        tracker.snapshot(field);
        // The checksum of an all-zero field is not zero: the hash is seeded and mixes every cell.
        // Comparing it to zero would be the wrong test; comparing before to after is the right one.
        assertTrue(tracker.collectDirty(field).isEmpty(), "L132");
        assertEquals(1, tracker.rebuildsAvoided(), "L133");
    }

    @Test
    void clearSnapshotsKeepsTheRunTotals() {
        LightField field = LightField.forSection(0, 0, 0);
        LightDirtyTracker tracker = new LightDirtyTracker();
        tracker.snapshot(field);
        tracker.collectDirty(field);

        tracker.clearSnapshots();
        assertFalse(tracker.hasSnapshots(), "L144");
        assertEquals(1, tracker.sectionsTracked(), "L145");
        assertEquals(1, tracker.rebuildsAvoided(), "L146");

        tracker.clear();
        assertEquals(0, tracker.sectionsTracked(), "L149");
        assertEquals(0, tracker.rebuildsAvoided(), "L150");
    }
}
