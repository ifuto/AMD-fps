package net.amdfaster.particle;

import net.amdfaster.cull.SoaFrustumCuller;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleLodTest {

    /** A frustum that is the box [0,10] on every axis. */
    private static SoaFrustumCuller.Planes boxPlanes() {
        SoaFrustumCuller.Planes planes = new SoaFrustumCuller.Planes();
        planes.set(0, 1, 0, 0, 0);
        planes.set(1, -1, 0, 0, 10);
        planes.set(2, 0, 1, 0, 0);
        planes.set(3, 0, -1, 0, 10);
        planes.set(4, 0, 0, 1, 0);
        planes.set(5, 0, 0, -1, 10);
        return planes;
    }

    private static ParticleLod withBoxFrustum() {
        ParticleLod lod = new ParticleLod(boxPlanes());
        lod.refreshNormals();
        return lod;
    }

    @Test
    void beforeTheNormalsAreRefreshedEverythingPasses() {
        // Rejecting on uninitialised normals would empty the screen, and the failure would look like a
        // renderer that stopped drawing particles rather than like a culler that was never set up.
        ParticleLod lod = new ParticleLod(boxPlanes());

        assertTrue(lod.isVisible(-1000.0f, -1000.0f, -1000.0f, 1.0f));
        assertEquals(1, lod.tested(), "it still counts as a test");
        assertEquals(0, lod.culledByFrustum());
    }

    @Test
    void aSphereInsideTheFrustumIsVisible() {
        ParticleLod lod = withBoxFrustum();

        assertTrue(lod.isVisible(5.0f, 5.0f, 5.0f, 1.0f));
        assertEquals(0, lod.culledByFrustum());
    }

    @Test
    void aSphereStraddlingAPlaneIsKept() {
        // This is what conservative means. The centre is outside the half-space but part of the sphere is
        // inside it, so the particle is visible and culling it would put a hole in the effect.
        ParticleLod lod = withBoxFrustum();

        assertTrue(lod.isVisible(-0.5f, 5.0f, 5.0f, 1.0f),
                "centre half a block out, radius one block, so it crosses the plane");
        assertTrue(lod.isVisible(-1.0f, 5.0f, 5.0f, 1.0f),
                "exactly tangent is still touching");
        assertFalse(lod.isVisible(-1.5f, 5.0f, 5.0f, 1.0f),
                "and past the radius it is genuinely outside");
    }

    @Test
    void theRadiusAllowanceUsesTheNormalLength() {
        // Minecraft's frustum planes are not unit length. Comparing the plane distance against the bare
        // radius would cull spheres that are still visible, by a factor equal to the normal's length.
        SoaFrustumCuller.Planes planes = new SoaFrustumCuller.Planes();
        planes.set(0, 2, 0, 0, 0);      // twice the unit normal, so the allowance doubles
        planes.set(1, -1, 0, 0, 1000);
        planes.set(2, 0, 1, 0, 1000);
        planes.set(3, 0, -1, 0, 1000);
        planes.set(4, 0, 0, 1, 1000);
        planes.set(5, 0, 0, -1, 1000);

        ParticleLod lod = new ParticleLod(planes);
        lod.refreshNormals();

        assertTrue(lod.isVisible(-0.5f, 0.0f, 0.0f, 1.0f),
                "plane distance is -1 and the allowance is 2, so it is inside");
        assertFalse(lod.isVisible(-1.5f, 0.0f, 0.0f, 1.0f),
                "plane distance -3 is beyond the allowance of 2");
    }

    @Test
    void theDistanceTiersSplitAtTheConfiguredBoundaries() {
        ParticleLod lod = withBoxFrustum();

        assertEquals(ParticleLod.Tier.FULL, lod.tierFor(10.0f * 10.0f, 0.2f, 8.0f));
        assertEquals(ParticleLod.Tier.FULL, lod.tierFor(24.0f * 24.0f, 0.2f, 8.0f),
                "exactly at the simplify distance is still full");
        assertEquals(ParticleLod.Tier.SIMPLIFIED, lod.tierFor(25.0f * 25.0f, 0.2f, 8.0f));
        assertEquals(ParticleLod.Tier.SIMPLIFIED, lod.tierFor(48.0f * 48.0f, 0.2f, 8.0f),
                "exactly at the drop distance still draws, cheaply");
        assertEquals(ParticleLod.Tier.DROPPED, lod.tierFor(49.0f * 49.0f, 0.2f, 8.0f));
    }

    @Test
    void aSubPixelParticleIsDroppedEvenWhenClose() {
        // The tier that pays. Particles are fill-rate bound, and one covering a fraction of a pixel
        // contributes nothing to the image and everything to the fragment count. In a rainstorm or a
        // large explosion most particles are in exactly this state.
        ParticleLod lod = withBoxFrustum();

        assertEquals(ParticleLod.Tier.DROPPED, lod.tierFor(100.0f, 0.1f, 2.0f), "0.2 pixel span");
        assertEquals(ParticleLod.Tier.FULL, lod.tierFor(100.0f, 0.1f, 2.5f), "exactly at the threshold");
        assertEquals(ParticleLod.Tier.FULL, lod.tierFor(100.0f, 0.1f, 8.0f), "0.8 pixel span");
    }

    @Test
    void theDistanceTestRunsBeforeTheSizeTest() {
        // A particle beyond the drop distance should be reported dropped without doing the size work, and
        // the counters should say why. Which test is doing the work decides what to tune.
        ParticleLod lod = withBoxFrustum();

        assertEquals(ParticleLod.Tier.DROPPED, lod.tierFor(10000.0f, 0.001f, 0.001f));
        assertEquals(0, lod.droppedBySize(), "the distance test decided it first");
    }

    @Test
    void simplifyingFartherThanDroppingIsRejected() {
        // That ordering means a particle too far to draw is still being asked to draw cheaply, which is a
        // contradiction and a sign the two arguments were swapped at the call site.
        ParticleLod lod = withBoxFrustum();

        assertThrows(IllegalArgumentException.class, () -> lod.setDistances(50.0f, 40.0f));
        assertThrows(IllegalArgumentException.class, () -> lod.setDistances(0.0f, 40.0f));
        assertThrows(IllegalArgumentException.class, () -> lod.setDistances(Float.NaN, 40.0f));
    }

    @Test
    void aNegativePixelSpanIsRejectedButZeroIsAllowed() {
        // Zero means never drop on size, which is a legitimate thing to want while measuring what the
        // size tier would have saved.
        ParticleLod lod = withBoxFrustum();

        assertThrows(IllegalArgumentException.class, () -> lod.setMinPixelSpan(-1.0f));
        lod.setMinPixelSpan(0.0f);
        assertEquals(0.0f, lod.minPixelSpan(), 0.0f);
        assertEquals(ParticleLod.Tier.FULL, lod.tierFor(100.0f, 0.0001f, 0.0001f));
    }

    @Test
    void theDistancesReadBackAsTheyWereSet() {
        ParticleLod lod = withBoxFrustum();

        assertEquals(ParticleLod.DEFAULT_SIMPLIFY_DISTANCE, lod.simplifyDistance(), 1e-4f);
        assertEquals(ParticleLod.DEFAULT_DROP_DISTANCE, lod.dropDistance(), 1e-4f);

        lod.setDistances(8.0f, 32.0f);
        assertEquals(8.0f, lod.simplifyDistance(), 1e-4f);
        assertEquals(32.0f, lod.dropDistance(), 1e-4f);
    }

    @Test
    void itWorksAsAVisibilityTestForTheField() {
        // The point of implementing the interface: the field's own cull builds the draw list and
        // estimates overdraw, and this decides membership.
        ParticleField field = ParticleField.forClient();
        field.spawn(5.0f, 5.0f, 5.0f, 0, 0, 0, 10.0f, 0.2f);
        field.spawn(500.0f, 500.0f, 500.0f, 0, 0, 0, 10.0f, 0.2f);

        ParticleLod lod = withBoxFrustum();
        int visible = field.cull(lod, 8.0f);

        assertEquals(1, visible, "only the one inside the frustum");
        assertEquals(1, lod.culledByFrustum());
        assertTrue(field.overdrawPixels() > 0.0, "and the surviving one still costs pixels");
    }

    @Test
    void theCountersSeparateEveryReasonForNotDrawing() {
        ParticleLod lod = withBoxFrustum();

        lod.isVisible(5.0f, 5.0f, 5.0f, 1.0f);
        lod.isVisible(-100.0f, 5.0f, 5.0f, 1.0f);
        lod.tierFor(25.0f * 25.0f, 0.2f, 8.0f);
        lod.tierFor(100.0f, 0.1f, 1.0f);

        assertEquals(2, lod.tested());
        assertEquals(1, lod.culledByFrustum());
        assertEquals(1, lod.simplified());
        assertEquals(1, lod.droppedBySize());

        lod.resetCounters();
        assertEquals(0, lod.tested());
        assertEquals(0, lod.culledByFrustum());
        assertEquals(0, lod.simplified());
        assertEquals(0, lod.droppedBySize());
    }
}
