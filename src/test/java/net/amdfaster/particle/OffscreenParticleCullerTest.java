package net.amdfaster.particle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OffscreenParticleCullerTest {

    private static final double HALF_FOV = 35.0;

    @Test
    void aParticleBehindTheCameraIsSkipped() {
        // In a redstone farm or during heavy rain there are thousands of particles and most are behind the
        // player. Particles are one of the few things where the update is as expensive as the draw.
        OffscreenParticleCuller culler = new OffscreenParticleCuller();

        assertFalse(culler.shouldProcess(180.0, HALF_FOV));
        assertEquals(1L, culler.culled());
    }

    @Test
    void theAgeAdvancesEvenWhenTheUpdateIsSkipped() {
        // A particle that is never aged would live forever, and turning around would reveal a field of
        // raindrops that should have fallen minutes ago and are hanging in the air. What is skipped is the
        // integration, not the clock.
        OffscreenParticleCuller culler = new OffscreenParticleCuller();

        culler.shouldProcess(180.0, HALF_FOV);

        assertEquals(1L, culler.aged(), "aged despite being culled");
        assertEquals(1L, culler.culled());
    }

    @Test
    void aParticleStraddlingTheEdgeIsKept() {
        // A particle has a size, and testing its centre would drop it so the player sees particles vanishing
        // at the edge of the screen -- where new ones enter and so where it is most noticeable.
        OffscreenParticleCuller culler = new OffscreenParticleCuller();

        assertTrue(culler.shouldProcess(HALF_FOV + 2.0, HALF_FOV), "within the margin");
        assertEquals(1L, culler.drawn());
    }

    @Test
    void aParticleClearlyOutsideIsCulled() {
        OffscreenParticleCuller culler = new OffscreenParticleCuller();

        assertFalse(culler.shouldProcess(
                HALF_FOV + OffscreenParticleCuller.EDGE_MARGIN_DEGREES + 0.1, HALF_FOV));
    }

    @Test
    void heavyRainIsMostlyBehindThePlayer() {
        OffscreenParticleCuller culler = new OffscreenParticleCuller();

        for (int i = 0; i < 2000; i++) {
            culler.shouldProcess(i % 360, HALF_FOV);
        }

        assertEquals(2000L, culler.tested());
        assertEquals(2000L, culler.aged(), "every one aged");
        assertEquals(234L, culler.drawn(),
                "39 of every 360 degrees, over five full cycles and a partial one");
        assertEquals(1766L, culler.culled());
        assertEquals(88.3, culler.culledPercent(), 0.01);
    }

    @Test
    void theSavingExcludesTheAgeingThatStillHappens() {
        // The ageing cost is paid either way, so only the integration and the draw are saved.
        OffscreenParticleCuller culler = new OffscreenParticleCuller();

        for (int i = 0; i < 1000; i++) {
            culler.shouldProcess(180.0, HALF_FOV);
        }

        assertEquals(1000 * (OffscreenParticleCuller.MS_PER_UPDATE
                + OffscreenParticleCuller.MS_PER_DRAW), culler.msWithoutCulling(), 1e-9);
        assertEquals(1000 * (OffscreenParticleCuller.MS_PER_UPDATE
                + OffscreenParticleCuller.MS_PER_DRAW
                - OffscreenParticleCuller.MS_PER_AGE), culler.msSaved(), 1e-9);
        assertEquals(97.5, culler.savedPercent(), 1e-9);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        OffscreenParticleCuller culler = new OffscreenParticleCuller();

        assertThrows(IllegalArgumentException.class,
                () -> culler.shouldProcess(-1.0, HALF_FOV));
        assertThrows(IllegalArgumentException.class,
                () -> culler.shouldProcess(10.0, 0.0));
    }

    @Test
    void aFreshCullerReportsZeroNotNaN() {
        // A culler that has tested nothing has technically avoided everything, but reporting that as a
        // saving would describe an unused one as though it had done something.
        OffscreenParticleCuller culler = new OffscreenParticleCuller();

        assertEquals(0.0, culler.culledPercent());
        assertEquals(0.0, culler.savedPercent());
        assertEquals(0.0, culler.msSaved());
        assertEquals(0L, culler.tested());
        assertEquals(0L, culler.aged());
        assertEquals(3.0, OffscreenParticleCuller.EDGE_MARGIN_DEGREES, 1e-12);
    }
}
