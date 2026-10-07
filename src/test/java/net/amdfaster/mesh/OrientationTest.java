package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bucket mask is what removes roughly half the triangles at draw time. */
class OrientationTest {

    private static int maskOf(double cx, double cy, double cz) {
        return Orientation.visibleMask(cx, cy, cz, 0, 0, 0, SectionMesh.SIZE);
    }

    @Test
    void cameraInsideTheSectionNeedsEveryBucket() {
        int mask = maskOf(8, 8, 8);
        assertEquals(0b111111, mask);
        assertEquals(6, Orientation.countBits(mask));
    }

    @Test
    void cameraFarAwayOnAllAxesNeedsThreeBuckets() {
        assertEquals(Orientation.POS_X.bit() | Orientation.POS_Y.bit() | Orientation.POS_Z.bit(),
                maskOf(100, 100, 100));
        assertEquals(Orientation.NEG_X.bit() | Orientation.NEG_Y.bit() | Orientation.NEG_Z.bit(),
                maskOf(-100, -100, -100));
        assertEquals(3, Orientation.countBits(maskOf(100, 100, 100)));
    }

    @Test
    void cameraFarAwayOnOneAxisDropsOneBucket() {
        int mask = maskOf(100, 8, 8);
        assertEquals(5, Orientation.countBits(mask));
        assertTrue((mask & Orientation.POS_X.bit()) != 0);
        assertTrue((mask & Orientation.NEG_X.bit()) == 0, "-X faces cannot be seen from +X");
    }

    @Test
    void cameraExactlyOnTheFarCornerPlane() {
        // cameraX == size: still outside on X, so NEG_X is gone.
        assertEquals(3, Orientation.countBits(maskOf(16, 16, 16)));
    }

    @Test
    void cameraJustOutsideTheNearPlane() {
        assertEquals(3, Orientation.countBits(maskOf(-0.5, -0.5, -0.5)));
    }

    @Test
    void sectionOriginIsHonoured() {
        int mask = Orientation.visibleMask(24, 24, 24, 16, 16, 16, SectionMesh.SIZE);
        assertEquals(0b111111, mask, "camera at 24 is inside the section starting at 16");

        int far = Orientation.visibleMask(40, 24, 24, 16, 16, 16, SectionMesh.SIZE);
        assertEquals(5, Orientation.countBits(far));
    }

    @Test
    void bitsAreDistinct() {
        int all = 0;
        for (Orientation o : Orientation.values()) {
            assertEquals(0, all & o.bit(), o + " bit collides");
            all |= o.bit();
        }
        assertEquals(0b111111, all);
    }
}
