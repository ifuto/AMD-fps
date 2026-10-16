package net.amdfaster.entity;

import net.amdfaster.entity.ExperienceOrbMerger.Merged;
import net.amdfaster.entity.ExperienceOrbMerger.Orb;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceOrbMergerTest {

    private static Orb orb(double x, double y, double z, int value) {
        return new Orb(x, y, z, value);
    }

    private static int total(List<Orb> orbs) {
        int sum = 0;
        for (Orb orb : orbs) {
            sum += orb.value();
        }
        return sum;
    }

    @Test
    void nearbyOrbsBecomeOneCarryingTheSum() {
        // Five orbs of three experience are fifteen experience. Not fourteen, not sixteen -- players
        // count experience, it is the currency of enchanting.
        ExperienceOrbMerger merger = new ExperienceOrbMerger();

        List<Orb> orbs = List.of(
                orb(0.0, 64.0, 0.0, 3),
                orb(0.2, 64.0, 0.1, 3),
                orb(0.4, 64.0, 0.2, 3),
                orb(0.6, 64.0, 0.3, 3),
                orb(0.8, 64.0, 0.4, 3));
        List<Merged> out = merger.merge(orbs);

        assertEquals(1, out.size());
        assertEquals(15, out.get(0).value(), "the exact sum");
        assertEquals(5, out.get(0).absorbed());
        assertTrue(merger.conservedExperience());
    }

    @Test
    void orbsTooFarApartDoNotMerge() {
        // Merging two orbs ten blocks apart would move experience to the player from a place they did
        // not earn it, and it would look like the orb teleported.
        ExperienceOrbMerger merger = new ExperienceOrbMerger();

        List<Merged> out = merger.merge(List.of(
                orb(0.0, 64.0, 0.0, 3),
                orb(10.0, 64.0, 0.0, 3)));

        assertEquals(2, out.size());
        assertEquals(3, out.get(0).value());
        assertEquals(3, out.get(1).value());
    }

    @Test
    void theMergedOrbSitsWhereOneAlreadyWas() {
        // An average would put experience in a place no orb ever was.
        ExperienceOrbMerger merger = new ExperienceOrbMerger();

        List<Merged> out = merger.merge(List.of(
                orb(5.0, 64.0, 5.0, 3),
                orb(5.5, 64.0, 5.5, 3)));

        assertEquals(5.0, out.get(0).x(), 1e-9);
        assertEquals(5.0, out.get(0).z(), 1e-9);
    }

    @Test
    void everyOrbIsCountedExactlyOnce() {
        // An orb counted twice is free experience, and one dropped is a loss. Both are the same bug in
        // different directions: the merger losing track of which orbs it already absorbed.
        ExperienceOrbMerger merger = new ExperienceOrbMerger();

        List<Orb> orbs = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            orbs.add(orb((i % 20) * 0.2, 64.0, (i / 20) * 0.2, 1 + (i % 7)));
        }
        List<Merged> out = merger.merge(orbs);

        assertEquals(total(orbs), merger.experienceIn());
        assertEquals(merger.experienceIn(), merger.experienceOut(), "nothing gained, nothing lost");
        assertTrue(merger.conservedExperience());

        int absorbed = 0;
        for (Merged merged : out) {
            absorbed += merged.absorbed();
        }
        assertEquals(400, absorbed, "every orb accounted for");
    }

    @Test
    void aDenseClusterCollapsesToAlmostNothing() {
        // An XP farm drops hundreds of orbs in a few seconds, each an entity the client draws and the
        // server tracks, for what the player experiences as one number going up.
        ExperienceOrbMerger merger = new ExperienceOrbMerger();

        List<Orb> orbs = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            orbs.add(orb((i % 5) * 0.1, 64.0, (i / 5 % 5) * 0.1, 3));
        }
        List<Merged> out = merger.merge(orbs);

        assertTrue(out.size() < 20, "was " + out.size());
        assertEquals(1200, merger.experienceOut());
        assertTrue(merger.reducedPercent() > 90.0, "was " + merger.reducedPercent());
    }

    @Test
    void orbsOnACellBoundaryStillMerge() {
        // Math.floor, not a cast: a cast truncates toward zero, which would put -0.5 in the same cell
        // as 0.5 and then -1.5 in a different one, splitting a group that should be together.
        ExperienceOrbMerger merger = new ExperienceOrbMerger();

        List<Merged> out = merger.merge(List.of(
                orb(-0.4, 64.0, 0.0, 3),
                orb(0.4, 64.0, 0.0, 3)));

        assertEquals(1, out.size(), "either side of the origin, still one group");
        assertEquals(6, out.get(0).value());
    }

    @Test
    void verticalDistanceCounts() {
        // An orb above the player and one below are not in the same place.
        ExperienceOrbMerger merger = new ExperienceOrbMerger();

        List<Merged> out = merger.merge(List.of(
                orb(0.0, 64.0, 0.0, 3),
                orb(0.0, 70.0, 0.0, 3)));

        assertEquals(2, out.size());
    }

    @Test
    void anEmptySceneMergesToNothing() {
        ExperienceOrbMerger merger = new ExperienceOrbMerger();

        assertTrue(merger.merge(List.of()).isEmpty());
        assertEquals(0.0, merger.reducedPercent());
        assertTrue(merger.conservedExperience(), "zero equals zero");
    }

    @Test
    void aSingleOrbIsUnchanged() {
        ExperienceOrbMerger merger = new ExperienceOrbMerger();

        List<Merged> out = merger.merge(List.of(orb(0.0, 64.0, 0.0, 7)));

        assertEquals(1, out.size());
        assertEquals(7, out.get(0).value());
        assertEquals(1, out.get(0).absorbed());
        assertEquals(0.0, merger.reducedPercent(), 0.01);
    }

    @Test
    void theNaiveComparisonIsWhatItSays() {
        // 400 orbs compared pairwise, every tick an XP farm is running.
        assertEquals(79_800L, ExperienceOrbMerger.naiveComparisons(400));
        assertEquals(0L, ExperienceOrbMerger.naiveComparisons(1));
    }

    @Test
    void theSavingIsReportedInMilliseconds() {
        ExperienceOrbMerger merger = new ExperienceOrbMerger();
        merger.merge(List.of(
                orb(0.0, 64.0, 0.0, 3),
                orb(0.1, 64.0, 0.0, 3),
                orb(20.0, 64.0, 0.0, 3)));

        assertEquals(3, merger.orbsIn());
        assertEquals(2, merger.orbsOut());
        assertEquals(2 * ExperienceOrbMerger.MS_PER_ORB, merger.msSavedPerTick(), 1e-9);
        assertEquals(3 * ExperienceOrbMerger.MS_PER_ORB, merger.msWithoutMerging(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> orb(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> orb(0, 0, 0, -3));
    }

    @Test
    void aFreshMergerReportsZeroNotNaN() {
        ExperienceOrbMerger merger = new ExperienceOrbMerger();

        assertEquals(0.0, merger.reducedPercent());
        assertEquals(0L, merger.orbsIn());
        assertEquals(0.0, merger.msSavedPerTick());
        assertEquals(1.0, ExperienceOrbMerger.MERGE_RADIUS, 1e-9);
        assertFalse(ExperienceOrbMerger.MERGE_RADIUS <= 0.0);
    }
}
