package net.amdfaster.collision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementRegionTest {

    /** A player-sized box standing at (8, 64, 8). */
    private static final double MIN_X = 8.0;
    private static final double MIN_Y = 64.0;
    private static final double MIN_Z = 8.0;
    private static final double MAX_X = 8.6;
    private static final double MAX_Y = 65.8;
    private static final double MAX_Z = 8.6;

    private static MovementRegion.Region player(MovementRegion resolver,
                                                double dx, double dy, double dz) {
        return resolver.afterBlocks(MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z, dx, dy, dz);
    }

    @Test
    void aLongRequestedMoveClampedToNothingCoversTwoSectionsNotEight() {
        // The case this exists for. Something asked to travel fifty blocks and a wall stopped it
        // immediately; the search sized from the request covers eight sections, and nothing can be
        // pushed into the seven it does not reach.
        MovementRegion resolver = new MovementRegion();

        MovementRegion.Region requested = resolver.beforeBlocks(
                MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z, 50.0, 0.0, 0.0);
        MovementRegion.Region clamped = player(resolver, 0.0, 0.0, 0.0);

        assertEquals(8, requested.sectionCount());
        assertEquals(2, clamped.sectionCount());
        assertEquals(75.0, resolver.savedSectionPercent(), 0.01);
    }

    @Test
    void anElytraGlideClampedByATerrainHitLosesMostOfItsSearch() {
        MovementRegion resolver = new MovementRegion();

        resolver.beforeBlocks(MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z, 80.0, 0.0, 0.0);
        MovementRegion.Region clamped = player(resolver, 4.0, 0.0, 0.0);

        assertEquals(12, resolver.sectionsFromRequested());
        assertEquals(2, clamped.sectionCount());
        assertEquals(83.3, resolver.savedSectionPercent(), 0.1);
    }

    @Test
    void aFallThatLandedEarlyIsSizedFromWhereItLanded() {
        MovementRegion resolver = new MovementRegion();

        resolver.beforeBlocks(MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z, 0.0, -20.0, 0.0);
        MovementRegion.Region clamped = player(resolver, 0.0, -3.0, 0.0);

        assertEquals(3, resolver.sectionsFromRequested());
        assertEquals(2, clamped.sectionCount());
        assertEquals(33.3, resolver.savedSectionPercent(), 0.1);
    }

    @Test
    void aPlayerStandingStillIsNotConfined() {
        // Worth pinning, because it is not what one would guess. The box bottom at 64.0 widened by
        // the push reach reaches 63.5, which is in the section below, so a stationary player covers
        // two sections and cannot be answered from a single occupancy count.
        MovementRegion resolver = new MovementRegion();
        MovementRegion.Region region = player(resolver, 0.0, 0.0, 0.0);

        assertEquals(2, region.sectionCount());
        assertFalse(resolver.isConfined(region));
        assertEquals(3, region.minY, "the reach pulls it into the section below");
        assertEquals(4, region.maxY);
        assertEquals(0, region.minX);
        assertEquals(0, region.maxX);
    }

    @Test
    void aSmallEntityAwayFromABoundaryIsConfined() {
        // A box well inside one section, with the push reach not crossing an edge, is answered by one
        // occupancy count rather than an iteration.
        MovementRegion resolver = new MovementRegion();
        MovementRegion.Region region = resolver.afterBlocks(
                8.0, 65.0, 8.0, 8.6, 65.6, 8.6, 0.0, 0.0, 0.0);

        assertEquals(1, region.sectionCount());
        assertTrue(resolver.isConfined(region));
        assertEquals(1L, resolver.confinedResolutions());
    }

    @Test
    void confinedDoesNotMeanTheEntityPassCanBeSkipped() {
        // Entities push each other, so a pinned entity can still be shoved. What confined buys is
        // answering from an occupancy count instead of iterating, and the naming should not invite a
        // caller to skip the pass.
        MovementRegion resolver = new MovementRegion();
        MovementRegion.Region region = resolver.afterBlocks(
                8.0, 65.0, 8.0, 8.6, 65.6, 8.6, 0.0, 0.0, 0.0);

        assertTrue(resolver.isConfined(region));
        assertTrue(region.contains(0, 4, 0), "the one section it covers");
        assertEquals(1, resolver.resolutions());
    }

    @Test
    void negativeCoordinatesLandInTheSectionBelowZero() {
        // A shift truncates towards zero and would put -0.5 in section 0, which is not the section
        // containing it. At the world floor that is the difference between searching the right
        // sections and searching none of them.
        assertEquals(-1, MovementRegion.sectionOf(-0.5));
        assertEquals(-1, MovementRegion.sectionOf(-16.0));
        assertEquals(-2, MovementRegion.sectionOf(-16.5));
        assertEquals(0, MovementRegion.sectionOf(0.0));
        assertEquals(0, MovementRegion.sectionOf(15.9));
        assertEquals(1, MovementRegion.sectionOf(16.0));
    }

    @Test
    void aRegionAtTheWorldFloorCoversTheRightSections() {
        MovementRegion resolver = new MovementRegion();
        MovementRegion.Region region = resolver.afterBlocks(
                -8.0, -64.0, -8.0, -7.4, -62.2, -7.4, 0.0, -1.0, 0.0);

        assertEquals(2, region.sectionCount());
        assertEquals(-5, region.minY, "the reach reaches below the world floor's section");
        assertEquals(-4, region.maxY);
        assertEquals(-1, region.minX);
        assertTrue(region.contains(-1, -4, -1), "the region is where the entity is");
        assertFalse(region.contains(0, -4, 0), "and not at the origin");
    }

    @Test
    void thePushReachIsAppliedOnEverySide() {
        // A pusher does not have to overlap the box to move it, so the region is wider than the box.
        // Too narrow here is overlapping mobs; too wide only costs the search.
        MovementRegion resolver = new MovementRegion();
        MovementRegion.Region region = player(resolver, 0.0, 0.0, 0.0);

        assertEquals(0.5, MovementRegion.PUSH_REACH);
        assertTrue(region.contains(0, 4, 0));
        assertFalse(region.contains(0, 6, 0), "and it does not grow without limit");
    }

    @Test
    void aFreshResolverReportsZeroNotNaN() {
        MovementRegion resolver = new MovementRegion();
        assertEquals(0.0, resolver.savedSectionPercent());
        assertEquals(0L, resolver.resolutions());
        assertEquals(0L, resolver.sectionsFromRequested());
        assertEquals(27, MovementRegion.FULL_SEARCH_SECTIONS);
        assertEquals(16, MovementRegion.SECTION_SIZE);
    }

    @Test
    void countsAccumulateAcrossResolutions() {
        MovementRegion resolver = new MovementRegion();

        for (int i = 0; i < 100; i++) {
            resolver.beforeBlocks(MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z, 50.0, 0.0, 0.0);
            player(resolver, 0.0, 0.0, 0.0);
        }
        assertEquals(100L, resolver.resolutions());
        assertEquals(800L, resolver.sectionsFromRequested());
        assertEquals(200L, resolver.sectionsFromClamped());
        assertEquals(75.0, resolver.savedSectionPercent(), 0.01);
    }
}
