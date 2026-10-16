package net.amdfaster.village;

import net.amdfaster.village.PoiIndex.Poi;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoiIndexTest {

    private static PoiIndex withVillage(int beds) {
        PoiIndex index = new PoiIndex();
        for (int i = 0; i < beds; i++) {
            index.add(new Poi(i, PoiIndex.KIND_BED, (i % 6) * 8, 64, (i / 6) * 8));
        }
        return index;
    }

    @Test
    void theSpanCoversTheWholeSearchRadius() {
        // A 32-block radius over 16-block cells has to reach two cells out. Hard-coding one would drop
        // every bed just past the first ring, which is most of a village, and the villager would stand
        // in the road all night instead of sleeping.
        assertEquals(2, PoiIndex.span(), "ceil(32 / 16)");
    }

    @Test
    void aBedAtTheEdgeOfTheRadiusIsFound() {
        PoiIndex index = new PoiIndex();
        index.add(new Poi(1, PoiIndex.KIND_BED, 31, 64, 0));

        assertTrue(index.near(0, 64, 0, PoiIndex.KIND_BED).stream().anyMatch(p -> p.id() == 1),
                "31 blocks is inside a 32-block radius");
    }

    @Test
    void onlyTheRequestedKindIsReturned() {
        PoiIndex index = new PoiIndex();
        index.add(new Poi(1, PoiIndex.KIND_BED, 0, 64, 0));
        index.add(new Poi(2, PoiIndex.KIND_WORKSTATION, 1, 64, 0));

        List<Poi> beds = index.near(0, 64, 0, PoiIndex.KIND_BED);

        assertEquals(1, beds.size());
        assertEquals(1, beds.get(0).id());
    }

    @Test
    void negativeCoordinatesShareABucketWithTheirNeighbours() {
        // floorDiv, not truncation. Truncating pulls everything near the origin toward zero and splits
        // the area, which is where villages are usually built.
        assertEquals(PoiIndex.bucketOf(-1, 64, -1), PoiIndex.bucketOf(-8, 64, -8));
    }

    @Test
    void aPointCannotBeClaimedTwice() {
        // Two villagers reserved to one bed means one of them does not sleep, and the symptom is a
        // villager wandering at night with no obvious cause.
        PoiIndex index = withVillage(1);

        assertTrue(index.claim(0, 100));
        assertFalse(index.claim(0, 101), "already held");
        assertEquals(100, index.holderOf(0));
        assertEquals(1L, index.refusedClaims());
        assertEquals(1L, index.claims(), "the refused attempt did not count as a claim");
    }

    @Test
    void reClaimingYourOwnPointIsAllowed() {
        // A villager re-asserting its bed every tick is normal; refusing that would make it hunt for a
        // new bed on every tick.
        PoiIndex index = withVillage(1);

        assertTrue(index.claim(0, 100));
        assertTrue(index.claim(0, 100));
        assertEquals(1L, index.claims(), "and it is still one claim");
    }

    @Test
    void releasingLetsTheNextVillagerHaveIt() {
        PoiIndex index = withVillage(1);
        index.claim(0, 100);

        index.release(0);

        assertTrue(index.claim(0, 101));
        assertEquals(101, index.holderOf(0));
    }

    @Test
    void aDeadVillagerReleasesEverythingItHeld() {
        // Otherwise its bed and workstation stay reserved forever and the replacement villager cannot
        // take the job.
        PoiIndex index = new PoiIndex();
        index.add(new Poi(1, PoiIndex.KIND_BED, 0, 64, 0));
        index.add(new Poi(2, PoiIndex.KIND_WORKSTATION, 8, 64, 0));
        index.claim(1, 100);
        index.claim(2, 100);

        index.releaseAllBy(100);

        assertFalse(index.isClaimed(1));
        assertFalse(index.isClaimed(2));
        assertTrue(index.claim(1, 101), "and the replacement can take the bed");
    }

    @Test
    void nearestFreeSkipsClaimedPoints() {
        PoiIndex index = new PoiIndex();
        index.add(new Poi(1, PoiIndex.KIND_BED, 1, 64, 0));
        index.add(new Poi(2, PoiIndex.KIND_BED, 10, 64, 0));
        index.claim(1, 100);

        Poi found = index.nearestFree(index.near(0, 64, 0, PoiIndex.KIND_BED), 0, 64, 0);

        assertEquals(2, found.id(), "the nearer bed is taken, so the farther free one");
    }

    @Test
    void nearestFreeReturnsNullWhenEverythingIsTaken() {
        PoiIndex index = withVillage(2);
        index.claim(0, 100);
        index.claim(1, 101);

        assertNull(index.nearestFree(index.near(0, 64, 0, PoiIndex.KIND_BED), 0, 64, 0));
    }

    @Test
    void removingAPointAlsoReleasesItsClaim() {
        // A bed that burns down must not stay reserved, or the villager keeps a bed it cannot use and
        // never looks for another.
        PoiIndex index = new PoiIndex();
        Poi bed = new Poi(1, PoiIndex.KIND_BED, 0, 64, 0);
        index.add(bed);
        index.claim(1, 100);

        index.remove(bed);

        assertFalse(index.isClaimed(1));
        assertEquals(0, index.poiCount());
    }

    @Test
    void aVillageSearchIsMuchCheaperThanScanningEveryPoint() {
        PoiIndex index = withVillage(90);

        for (int v = 0; v < 30; v++) {
            index.near((v % 6) * 8, 64, (v / 6) * 8, PoiIndex.KIND_BED);
        }

        assertEquals(90, index.poiCount());
        long without = index.checksWithoutIndex(30);
        assertEquals(2700L, without, "30 villagers over 90 points");
        assertTrue(index.checks() < without,
                "bucketing has to help: " + index.checks() + " vs " + without);
        assertTrue(index.savedPercent(30) > 0.0);
    }

    @Test
    void aFreshIndexReportsZeroNotNaN() {
        PoiIndex index = new PoiIndex();
        assertEquals(0.0, index.savedPercent(30));
        assertEquals(0, index.poiCount());
        assertEquals(-1, index.holderOf(999));
        assertFalse(index.isClaimed(999));
    }
}
