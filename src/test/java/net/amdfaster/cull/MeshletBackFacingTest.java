package net.amdfaster.cull;

import net.amdfaster.mesh.Meshlet;
import net.amdfaster.mesh.MeshletBuilder;
import net.amdfaster.mesh.Orientation;
import net.amdfaster.mesh.Quad;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole-meshlet back-face culling drops geometry before its vertices are shaded, so a mistake here
 * is a wall that vanishes when you walk around it. Every test says which side of the plane the
 * camera is on, because that is the whole of the logic.
 *
 * <p>Coordinates are camera-relative, matching {@link MeshletCuller#isVisible}: the camera sits at
 * the origin, so a meshlet's world coordinate on the bucket's axis is {@code sectionOrigin + plane}
 * and each test is a comparison against zero. A positive face is lit when that value is negative --
 * the camera is beyond it -- and a negative face when it is positive.
 *
 * <p>Every plane below is a legal section-local coordinate, 0..16. The camera is moved by moving the
 * section origin, which is how it actually moves.
 */
class MeshletBackFacingTest {

    /** A single 1x1 quad on {@code plane}, in the bucket for {@code orientation}. */
    private static Meshlet quad(Orientation orientation, int plane, boolean singleSided) {
        MeshletBuilder builder = new MeshletBuilder(orientation);
        switch (orientation.axis()) {
            case 0 -> builder.add(Quad.uniform(orientation, plane, 0, 0, plane, 1, 1,
                    0f, 0f, 1f, 1f, 0, 0), singleSided);
            case 1 -> builder.add(Quad.uniform(orientation, 0, plane, 0, 1, plane, 1,
                    0f, 0f, 1f, 1f, 0, 0), singleSided);
            default -> builder.add(Quad.uniform(orientation, 0, 0, plane, 1, 1, plane,
                    0f, 0f, 1f, 1f, 0, 0), singleSided);
        }
        return builder.build();
    }

    /** A section origin offset along the bucket's own axis, which is the only axis the test turns on. */
    private static int[] originOnAxis(Orientation orientation, int offset) {
        int[] origin = new int[3];
        origin[orientation.axis()] = offset;
        return origin;
    }

    private static boolean backFacing(Meshlet meshlet, Orientation orientation, int offset) {
        int[] o = originOnAxis(orientation, offset);
        return MeshletCuller.isBackFacing(meshlet, o[0], o[1], o[2]);
    }

    @Test
    void aMeshletThatWasNeverDeclaredSingleSidedIsNeverCulled() {
        // The opt-in is the safety property. A caller that forgets to declare loses an optimisation;
        // a caller that declares wrongly loses geometry. So the default has to be the cheap mistake.
        for (Orientation orientation : Orientation.values()) {
            for (int plane : new int[] {0, 1, 8, 16}) {
                for (int offset : new int[] {-20, 0, 20}) {
                    assertFalse(backFacing(quad(orientation, plane, false), orientation, offset),
                            orientation + " at plane " + plane + " offset " + offset
                                    + " was culled without being declared");
                }
            }
        }
    }

    @Test
    void aPositiveFaceIsCulledOnceTheCameraIsBehindItsPlane() {
        // A +X face is only ever seen from the +X side. At local plane 5 with the section at the
        // origin the face sits at x = 5 and the camera at x = 0 is behind it. Putting the section at
        // x = -12 moves the face to x = -7, past the camera, where it is lit.
        Meshlet meshlet = quad(Orientation.POS_X, 5, true);
        assertTrue(backFacing(meshlet, Orientation.POS_X, 0),
                "face at x = 5, camera at x = 0: turned away");
        assertFalse(backFacing(meshlet, Orientation.POS_X, -12),
                "face at x = -7, camera at x = 0: lit");
    }

    @Test
    void aNegativeFaceIsCulledOnceTheCameraIsInFrontOfItsPlane() {
        Meshlet meshlet = quad(Orientation.NEG_X, 5, true);
        assertFalse(backFacing(meshlet, Orientation.NEG_X, 0),
                "face at x = 5, camera at x = 0: the camera is on the -X side, which is where it is lit");
        assertTrue(backFacing(meshlet, Orientation.NEG_X, -12),
                "face at x = -7, camera at x = 0: the camera is on the +X side of a -X face");
    }

    @Test
    void allSixBucketsCullOnTheCorrectSide() {
        // The sign convention per axis, spelled out. Getting one of these backwards deletes a wall
        // from one direction and leaves the far side of the world drawn.
        //
        // For every bucket the two offsets give opposite answers, so each line checks both the sign
        // and the axis: swapping either one flips one of the two and fails.
        for (Orientation orientation : Orientation.values()) {
            Meshlet meshlet = quad(orientation, 4, true);
            boolean atOrigin = backFacing(meshlet, orientation, 0);
            boolean pulledBack = backFacing(meshlet, orientation, -12);
            assertEquals(orientation.isPositive(), atOrigin,
                    orientation + " at plane 4: a positive face is turned away from the origin");
            assertEquals(!orientation.isPositive(), pulledBack,
                    orientation + " pulled back to plane -8: the positive face is now lit");
        }
    }

    @Test
    void theTestUsesTheNearBoundSoItStaysConservative() {
        // A meshlet can span a range along its axis. The comparison has to use the bound nearest the
        // camera, because if any face in the meshlet could still be lit the meshlet must survive.
        // Using the far bound would cull meshlets with one visible face in them.
        MeshletBuilder builder = new MeshletBuilder(Orientation.POS_Z);
        builder.add(Quad.uniform(Orientation.POS_Z, 0, 0, 2, 1, 1, 2, 0f, 0f, 1f, 1f, 0, 0), true);
        builder.add(Quad.uniform(Orientation.POS_Z, 0, 0, 10, 1, 1, 10, 0f, 0f, 1f, 1f, 0, 0), true);
        Meshlet spanning = builder.build();

        assertEquals(2, spanning.minZ(), "the near face is at z = 2");
        assertEquals(10, spanning.maxZ(), "the far face is at z = 10");

        // Section at z = -5 puts the near face at z = -3 and the far face at z = 5, so the meshlet
        // straddles the camera. This is the case that separates the two bounds: testing the near one
        // keeps it, because the z = -3 face is lit; testing the far one would cull the whole meshlet
        // and delete that face with it. At z = -12 both faces are past the camera and the test no
        // longer discriminates, which is why the offset is -5 and not a rounder number.
        assertFalse(MeshletCuller.isBackFacing(spanning, 0, 0, -5),
                "the near face is lit, so the meshlet must survive");
        // Section at the origin: both faces are at z >= 2, both turned away.
        assertTrue(MeshletCuller.isBackFacing(spanning, 0, 0, 0),
                "with every face turned away the whole meshlet goes");
    }

    @Test
    void aCameraExactlyInTheFacePlaneCullsIt() {
        // Edge-on is zero area, so culling it is not a visible change. This is also the boundary the
        // inequality has to land on identically in the CPU and the shader.
        assertTrue(backFacing(quad(Orientation.POS_Z, 0, true), Orientation.POS_Z, 0));
        assertTrue(backFacing(quad(Orientation.NEG_Z, 0, true), Orientation.NEG_Z, 0));
        assertFalse(backFacing(quad(Orientation.POS_Z, 0, true), Orientation.POS_Z, -1),
                "one block back puts a +Z face in front of the camera");
        assertFalse(backFacing(quad(Orientation.NEG_Z, 0, true), Orientation.NEG_Z, 1),
                "one block forward puts a -Z face in front of the camera");
    }

    @Test
    void theSectionOriginMovesTheMeshletRelativeToTheCamera() {
        // The bounds are section-local, so the origin is what puts a meshlet on one side of the
        // camera or the other. Dropping it from the test would cull half the world.
        Meshlet meshlet = quad(Orientation.POS_Z, 2, true);
        assertFalse(MeshletCuller.isBackFacing(meshlet, 0, 0, -10), "lands at z = -8, lit");
        assertTrue(MeshletCuller.isBackFacing(meshlet, 0, 0, 10), "lands at z = 12, turned away");
    }

    @Test
    void theSingleSidedFlagRoundTripsThroughThePackedBounds() {
        Meshlet declared = quad(Orientation.NEG_Z, 3, true);
        Meshlet undeclared = quad(Orientation.NEG_Z, 3, false);

        assertTrue(declared.singleSided());
        assertFalse(undeclared.singleSided());

        assertEquals(30, Meshlet.SINGLE_SIDED_BIT);
        assertEquals(1, declared.packedBounds() >>> Meshlet.SINGLE_SIDED_BIT & 1,
                "bit 30 is set for a declared meshlet");
        assertEquals(0, undeclared.packedBounds() >>> Meshlet.SINGLE_SIDED_BIT & 1,
                "and clear for one that was not");

        // The flag must not disturb the bounds, which share the word.
        for (Meshlet meshlet : new Meshlet[] {declared, undeclared}) {
            int packed = meshlet.packedBounds();
            assertEquals(meshlet.minX(), Meshlet.unpackMinX(packed));
            assertEquals(meshlet.minY(), Meshlet.unpackMinY(packed));
            assertEquals(meshlet.minZ(), Meshlet.unpackMinZ(packed));
            assertEquals(meshlet.maxX(), Meshlet.unpackMaxX(packed));
            assertEquals(meshlet.maxY(), Meshlet.unpackMaxY(packed));
            assertEquals(meshlet.maxZ(), Meshlet.unpackMaxZ(packed));
        }
    }

    @Test
    void theBoundsStillFitInThirtyBitsWithTheFlagAboveThem() {
        // Six 5-bit fields occupy bits 0..29 and the flag is bit 30, so bit 31 stays clear and the
        // packed value is never negative. A negative packed bounds would make every unsigned shift
        // the shader does with it wrong.
        Meshlet meshlet = quad(Orientation.POS_Y, 16, true);
        int packed = meshlet.packedBounds();
        assertTrue(packed >= 0, "bit 31 must stay clear, got 0x" + Integer.toHexString(packed));
        assertEquals(1 << 30, packed & ~0x3FFFFFFF, "only bit 30 sits above the bounds");
    }

    @Test
    void theOrientationOrdinalsMatchWhatTheShaderDecodes() {
        // The shader reads one uint per meshlet and derives the axis as ordinal / 2 and the sign as
        // ordinal & 1. If the enum is ever reordered this silently culls the wrong axis, so the
        // mapping is pinned here rather than assumed.
        int[] expectedAxis = {0, 0, 1, 1, 2, 2};
        boolean[] expectedPositive = {false, true, false, true, false, true};
        for (Orientation orientation : Orientation.values()) {
            int ordinal = orientation.ordinal();
            assertEquals(expectedAxis[ordinal], orientation.axis(),
                    orientation + ": axis must be ordinal / 2");
            assertEquals(expectedPositive[ordinal], orientation.isPositive(),
                    orientation + ": sign must be ordinal & 1");
            assertEquals(ordinal / 2, orientation.axis());
            assertEquals((ordinal & 1) == 1, orientation.isPositive());
        }
        assertEquals(6, Orientation.values().length,
                "six buckets need three bits, which is why they are not in the two free ones");
    }

    @Test
    void aReusedBuilderDoesNotCarryTheDeclarationOver() {
        // Builders are reused for every bucket of every section. A declaration that outlived its
        // meshlet would back-face cull a later one that never asked for it.
        MeshletBuilder builder = new MeshletBuilder(Orientation.NEG_Z);
        builder.add(Quad.uniform(Orientation.NEG_Z, 0, 0, 0, 1, 1, 0, 0f, 0f, 1f, 1f, 0, 0), true);
        assertTrue(builder.build().singleSided());

        builder.add(Quad.uniform(Orientation.NEG_Z, 0, 0, 0, 1, 1, 0, 0f, 0f, 1f, 1f, 0, 0), false);
        assertFalse(builder.build().singleSided(), "the next meshlet must not inherit the declaration");
    }

    @Test
    void oneDoubleSidedQuadMakesTheWholeMeshletDoubleSided() {
        // The declarations are AND-ed, so the meshlet is culled only when every quad in it can be.
        // Order must not matter: a sticky flag would give one answer when the alpha-tested quad
        // arrives first and the opposite when it arrives last.
        MeshletBuilder opaqueFirst = new MeshletBuilder(Orientation.POS_Y);
        opaqueFirst.add(Quad.uniform(Orientation.POS_Y, 0, 3, 0, 1, 3, 1, 0f, 0f, 1f, 1f, 0, 0), true);
        opaqueFirst.add(Quad.uniform(Orientation.POS_Y, 0, 3, 1, 1, 3, 2, 0f, 0f, 1f, 1f, 0, 0), false);
        assertFalse(opaqueFirst.build().singleSided());

        MeshletBuilder cutoutFirst = new MeshletBuilder(Orientation.POS_Y);
        cutoutFirst.add(Quad.uniform(Orientation.POS_Y, 0, 3, 0, 1, 3, 1, 0f, 0f, 1f, 1f, 0, 0), false);
        cutoutFirst.add(Quad.uniform(Orientation.POS_Y, 0, 3, 1, 1, 3, 2, 0f, 0f, 1f, 1f, 0, 0), true);
        assertFalse(cutoutFirst.build().singleSided(), "the same meshlet built in the other order");

        MeshletBuilder allOpaque = new MeshletBuilder(Orientation.POS_Y);
        allOpaque.add(Quad.uniform(Orientation.POS_Y, 0, 3, 0, 1, 3, 1, 0f, 0f, 1f, 1f, 0, 0), true);
        allOpaque.add(Quad.uniform(Orientation.POS_Y, 0, 3, 1, 1, 3, 2, 0f, 0f, 1f, 1f, 0, 0), true);
        assertTrue(allOpaque.build().singleSided());
    }

    @Test
    void theSingleArgumentAddDeclaresNothingAndSoIsSafe() {
        // Every caller that has not been updated keeps the conservative behaviour rather than
        // silently opting in.
        MeshletBuilder builder = new MeshletBuilder(Orientation.NEG_Y);
        builder.add(Quad.uniform(Orientation.NEG_Y, 0, 3, 0, 1, 3, 1, 0f, 0f, 1f, 1f, 0, 0));
        assertFalse(builder.build().singleSided());
    }
}
