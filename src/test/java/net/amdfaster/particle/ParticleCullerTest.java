package net.amdfaster.particle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleCullerTest {

    private static final int W = 1920;
    private static final int H = 1080;

    /** Reversed Z: larger is nearer. */
    private static final float FAR = 0.1f;
    private static final float NEAR = 0.9f;

    @Test
    void aFullyTransparentParticleIsDropped() {
        // Every blend with alpha zero is a no-op, so it cannot contribute a pixel.
        ParticleCuller culler = new ParticleCuller(W, H);

        assertFalse(culler.keep(100, 100, 108, 108, FAR, 0.0f));
        assertTrue(culler.keep(100, 100, 108, 108, FAR, 0.001f), "any alpha at all is kept");
        assertEquals(1L, culler.culledTransparent());
    }

    @Test
    void aParticleOffScreenIsDropped() {
        ParticleCuller culler = new ParticleCuller(W, H);

        assertFalse(culler.keep(-20, 100, -12, 108, FAR, 1.0f), "left of the screen");
        assertFalse(culler.keep(100, -20, 108, -12, FAR, 1.0f), "above it");
        assertFalse(culler.keep(W, 100, W + 8, 108, FAR, 1.0f), "right of it");
        assertFalse(culler.keep(100, H, 108, H + 8, FAR, 1.0f), "below it");
        assertEquals(4L, culler.culledOffScreen());

        assertTrue(culler.keep(0, 0, 8, 8, FAR, 1.0f), "touching the corner is on screen");
        assertTrue(culler.keep(W - 8, H - 8, W, H, FAR, 1.0f), "and so is the far corner");
    }

    @Test
    void aParticleEntirelyBehindOpaqueGeometryIsDropped() {
        ParticleCuller culler = new ParticleCuller(W, H);
        // A wall across the middle of the screen, nearer than the particle.
        culler.markOpaque(0, H / 2, W, H, NEAR);

        assertFalse(culler.keep(100, H / 2 + 100, 108, H / 2 + 108, FAR, 1.0f));
        assertEquals(1L, culler.culledBehind());

        assertTrue(culler.keep(100, 100, 108, 108, FAR, 1.0f), "above the wall is visible");
    }

    @Test
    void aParticlePartlyBehindIsKept() {
        // The case that must not be got wrong. One uncovered cell means some pixel of the particle
        // survives the depth test, and dropping it would remove a particle the player can see.
        ParticleCuller culler = new ParticleCuller(W, H);
        culler.markOpaque(0, H / 2, W, H, NEAR);

        // Straddling the wall, and tall enough to span more than one cell row. An eight pixel particle
        // at the boundary sits inside a single 8-pixel cell, which the wall marks opaque, and the cell
        // is conservative by design: it cannot tell that part of the particle is in clear air.
        assertTrue(culler.keep(100, H / 2 - 16, 108, H / 2 + 16, FAR, 1.0f));
        assertEquals(0L, culler.culledBehind());

        // The same particle, wholly inside the wall's cell row, is culled. That is the conservatism
        // showing: it costs a particle that was actually visible at the edge of the wall.
        assertTrue(culler.keep(100, H / 2 + 16, 108, H / 2 + 24, FAR, 1.0f) == false);
    }

    @Test
    void aParticleNearerThanTheWallIsKept() {
        ParticleCuller culler = new ParticleCuller(W, H);
        culler.markOpaque(0, H / 2, W, H, 0.5f);

        assertTrue(culler.keep(100, H / 2 + 100, 108, H / 2 + 108, 0.8f, 1.0f),
                "0.8 is nearer than 0.5 under reversed Z");
        assertEquals(0L, culler.culledBehind());
    }

    @Test
    void overlappingTranslucentParticlesAreBothKept() {
        // The reason this class does not do the big win. At half a million particles there are fifteen
        // per screen cell and keeping only the front one would discard 93.5%, but particles blend: two
        // overlapping smoke particles make a colour neither makes alone. Dropping one changes what is
        // on screen, in exactly the dense case the optimisation would be aimed at.
        ParticleCuller culler = new ParticleCuller(W, H);

        for (int i = 0; i < 50; i++) {
            assertTrue(culler.keep(100, 100, 108, 108, FAR + i * 0.0001f, 0.3f),
                    "particle " + i + " contributes to the blend");
        }
        assertEquals(50L, culler.kept());
        assertEquals(0L, culler.culledBehind());
    }

    @Test
    void markingMoreGeometryOnlyMakesACellMoreConservative() {
        ParticleCuller culler = new ParticleCuller(W, H);
        // Reversed Z: the larger value is nearer, so a particle at 0.3 is behind a wall at 0.4.
        culler.markOpaque(0, 0, 100, 100, 0.2f);
        assertTrue(culler.keep(10, 10, 18, 18, 0.3f, 1.0f), "0.3 is nearer than 0.2");

        culler.markOpaque(0, 0, 100, 100, 0.9f);
        assertFalse(culler.keep(10, 10, 18, 18, 0.3f, 1.0f), "and now the wall is nearer");
    }

    @Test
    void resetClearsTheDepthCells() {
        ParticleCuller culler = new ParticleCuller(W, H);
        culler.markOpaque(0, 0, W, H, NEAR);
        assertFalse(culler.keep(10, 10, 18, 18, FAR, 1.0f));

        culler.reset();

        assertTrue(culler.keep(10, 10, 18, 18, FAR, 1.0f), "a cleared cell accepts everything");
    }

    @Test
    void halfTheParticlesBehindAWallAreDropped() {
        // The shape of the saving: a wall across half the screen and particles scattered over all of
        // it. Half of them are behind it and provably invisible.
        ParticleCuller culler = new ParticleCuller(W, H);
        culler.markOpaque(0, H / 2, W, H, NEAR);

        java.util.Random random = new java.util.Random(2L);
        for (int i = 0; i < 100_000; i++) {
            int x = random.nextInt(W - 8);
            int y = random.nextInt(H - 8);
            culler.keep(x, y, x + 8, y + 8, FAR, 1.0f);
        }

        assertEquals(100_000L, culler.submitted());
        assertTrue(culler.culledBehind() > 45_000 && culler.culledBehind() < 55_000,
                "about half, got " + culler.culledBehind());
        assertEquals(100_000L - culler.culledBehind(), culler.kept());
        assertTrue(culler.culledPercent() > 45.0);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ParticleCuller(16, 1080),
                "narrower than one cell");
        assertThrows(IllegalArgumentException.class, () -> new ParticleCuller(1920, 4));
    }

    @Test
    void aFreshCullerReportsZeroNotNaN() {
        ParticleCuller culler = new ParticleCuller(W, H);
        assertEquals(0.0, culler.culledPercent());
        assertEquals(0L, culler.submitted());
        assertEquals(32, ParticleCuller.TILE_W);
        assertEquals(8, ParticleCuller.TILE_H);
    }
}
