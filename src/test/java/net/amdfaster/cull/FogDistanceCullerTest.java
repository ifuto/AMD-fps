package net.amdfaster.cull;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FogDistanceCullerTest {

    private static final double FOG = 128.0;

    @Test
    void aSectionPastTheFogIsCulled() {
        // Every pixel of it is fog, so it contributes nothing to the image -- yet at render distance
        // twelve with fog starting at eight, most of what is drawn is behind the fog.
        FogDistanceCuller culler = new FogDistanceCuller();

        assertTrue(culler.shouldCull(200.0, FOG));
        assertEquals(1L, culler.culled());
        assertEquals(0L, culler.drawn());
    }

    @Test
    void aSectionInsideTheFogIsDrawn() {
        // The fog blends gradually. A section at the fog's start distance is still partly visible, and
        // removing it takes terrain out of the world in a way that is immediately obvious -- the horizon
        // gains a hard edge where the fog should be smooth.
        FogDistanceCuller culler = new FogDistanceCuller();

        assertFalse(culler.shouldCull(100.0, FOG));
        assertEquals(1L, culler.drawn());
    }

    @Test
    void theCullDistanceIsWhereTheFogIsOpaqueNotWhereItStarts() {
        FogDistanceCuller culler = new FogDistanceCuller();

        assertFalse(culler.shouldCull(FOG - 0.1, FOG), "just inside, still partly visible");
        assertTrue(culler.shouldCull(FOG, FOG), "exactly at it, fully fog");
    }

    @Test
    void theNearestCornerDecidesNotTheCentre() {
        // A section is sixteen blocks across, so the near and far corners are at meaningfully different
        // distances, and measuring the centre would cull a section whose near corner is still visible.
        double camera = 0.0;
        double near = FogDistanceCuller.nearestDistance(camera, camera, 120.0, 120.0);
        double centre = FogDistanceCuller.nearestDistance(camera, camera, 128.0, 128.0);

        assertTrue(near < centre, "the near corner is closer than the centre");
        assertEquals(0.0, FogDistanceCuller.nearestDistance(8.0, 8.0, 0.0, 0.0), 1e-9,
                "a camera inside the section is zero away from it");
    }

    @Test
    void aSectionTheCameraIsInsideIsNeverCulled() {
        FogDistanceCuller culler = new FogDistanceCuller();

        double distance = FogDistanceCuller.nearestDistance(8.0, 8.0, 0.0, 0.0);
        assertFalse(culler.shouldCull(distance, FOG));
    }

    @Test
    void theMeasuredSceneCullsAboutTwoThirds() {
        // Render distance twelve, fog opaque at eight chunks. columnsWithin counts distance <= 8 while
        // the culler keeps distance < 8, so the four sections exactly at eight are drawn by one and
        // culled by the other: 197 versus 193.
        assertEquals(625, FogDistanceCuller.sectionColumns(12), "25 by 25");
        assertEquals(197, FogDistanceCuller.columnsWithin(12, 8.0));

        FogDistanceCuller culler = new FogDistanceCuller();
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                double distance = Math.sqrt((double) x * x + (double) z * z) * 16.0;
                culler.shouldCull(distance, 8.0 * 16.0);
            }
        }

        assertEquals(625L, culler.offered());
        assertEquals(432L, culler.culled());
        assertEquals(193L, culler.drawn());
        assertEquals(69.12, culler.culledPercent(), 0.01);
        assertEquals(432 * FogDistanceCuller.MS_PER_SECTION, culler.msSavedPerFrame(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        FogDistanceCuller culler = new FogDistanceCuller();

        assertThrows(IllegalArgumentException.class, () -> culler.shouldCull(-1.0, FOG));
        assertThrows(IllegalArgumentException.class, () -> culler.shouldCull(10.0, -1.0));
    }

    @Test
    void aFreshCullerReportsZeroNotNaN() {
        FogDistanceCuller culler = new FogDistanceCuller();

        assertEquals(0.0, culler.culledPercent());
        assertEquals(0.0, culler.msSavedPerFrame());
        assertEquals(0L, culler.offered());
        assertEquals(16, FogDistanceCuller.SECTION_BLOCKS);
    }
}
