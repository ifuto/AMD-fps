package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BonePaletteTest {

    private static final float EPS = 1e-5f;

    /** root -> body -> head, root -> legFL, root -> legFR. Mirrors a quadruped. */
    private static final BoneRig QUADRUPED = new BoneRig(
            new int[] {-1, 0, 1, 0, 0},
            new float[] {0f, 0f, 0f, 2f, -2f},
            new float[] {0f, 0f, 4f, -6f, -6f},
            new float[] {0f, 0f, 8f, 6f, 6f},
            new int[] {BoneRig.AXIS_NONE, BoneRig.AXIS_NONE, BoneRig.AXIS_X,
                       BoneRig.AXIS_X, BoneRig.AXIS_X});

    @Test
    void aRootBoneIsItsOwnLocalMatrix() {
        BonePalette palette = new BonePalette();
        palette.writeInstance(0, QUADRUPED, new float[] {0f, 0f, 0.3f, -0.6f, 0.6f});

        assertEquals(1f, palette.matrix(0, 0, 0, 0), EPS);
        assertEquals(0f, palette.matrix(0, 0, 3, 0), EPS, "the root has no pivot offset here");
    }

    @Test
    void aChildInheritsItsParentsTranslation() {
        BonePalette palette = new BonePalette();
        palette.writeInstance(0, QUADRUPED, new float[] {0f, 0f, 0.3f, -0.6f, 0.6f});

        // body sits at the origin and head at (0, 4, 8), so head's world translation is the sum.
        assertEquals(0f, palette.matrix(0, 2, 3, 0), EPS);
        assertEquals(4f, palette.matrix(0, 2, 3, 1), EPS);
        assertEquals(8f, palette.matrix(0, 2, 3, 2), EPS);
    }

    @Test
    void aBoneRotatesAboutItsPivotWithoutMovingIt() {
        // The convention, pinned. translate then rotate means the pivot itself is fixed, so the
        // translation column is the plain sum of base offsets and only the rotational block animates.
        // Getting this the other way round rotates the whole model about the origin and every limb
        // swings away from the body as the animation advances.
        BonePalette palette = new BonePalette();
        palette.writeInstance(0, QUADRUPED, new float[] {0f, 0f, 0.3f, -0.6f, 0.6f});

        assertEquals(2f, palette.matrix(0, 3, 3, 0), EPS);
        assertEquals(-6f, palette.matrix(0, 3, 3, 1), EPS, "not -6*cos(-0.6)");
        assertEquals(6f, palette.matrix(0, 3, 3, 2), EPS);

        assertEquals(0f, palette.matrix(0, 3, 1, 0), EPS);
        assertEquals(0.825336f, palette.matrix(0, 3, 1, 1), EPS);
        assertEquals(-0.564642f, palette.matrix(0, 3, 1, 2), EPS);
        assertEquals(0f, palette.matrix(0, 3, 1, 3), EPS);
    }

    @Test
    void aParentedRotationIsTheProductNotTheLocalMatrix() {
        // The head hangs off the body, and the body is at the origin unrotated, so the head's
        // rotational block is its own rotation carried through unchanged.
        BonePalette palette = new BonePalette();
        palette.writeInstance(0, QUADRUPED, new float[] {0f, 0f, 0.3f, -0.6f, 0.6f});

        assertEquals(1f, palette.matrix(0, 2, 0, 0), EPS);
        assertEquals(0.955336f, palette.matrix(0, 2, 1, 1), EPS);
        assertEquals(0.295520f, palette.matrix(0, 2, 1, 2), EPS);
        assertEquals(-0.295520f, palette.matrix(0, 2, 2, 1), EPS);
    }

    @Test
    void bonesTheRigDoesNotUseStayIdentity() {
        // The shader indexes uniformly across MAX_BONES, so a rig with five bones must leave the
        // other nineteen as identity rather than as whatever the last frame wrote. A stale matrix
        // there would put vertices at a position no bone ever asked for.
        BonePalette palette = new BonePalette();
        palette.writeInstance(0, QUADRUPED, new float[] {0f, 0f, 90f, 90f, 90f});

        for (int bone = QUADRUPED.boneCount(); bone < BonePalette.MAX_BONES; bone++) {
            assertEquals(1f, palette.matrix(0, bone, 0, 0), EPS, "bone " + bone);
            assertEquals(1f, palette.matrix(0, bone, 1, 1), EPS, "bone " + bone);
            assertEquals(1f, palette.matrix(0, bone, 2, 2), EPS, "bone " + bone);
            assertEquals(1f, palette.matrix(0, bone, 3, 3), EPS, "bone " + bone);
            assertEquals(0f, palette.matrix(0, bone, 3, 0), EPS, "bone " + bone + " must not translate");
        }
    }

    @Test
    void instancesDoNotShareAPalette() {
        BonePalette palette = new BonePalette();
        palette.writeInstance(0, QUADRUPED, new float[] {0f, 0f, 0.3f, -0.6f, 0.6f});
        palette.writeInstance(1, QUADRUPED, new float[] {0f, 0f, 0.0f, 0.0f, 0.0f});

        assertEquals(0.295520f, palette.matrix(0, 2, 1, 2), EPS, "instance 0 keeps its pose");
        assertEquals(0f, palette.matrix(1, 2, 1, 2), EPS, "instance 1 has its own");
        assertEquals(BonePalette.FLOATS_PER_INSTANCE, 384, "24 bones of 16 floats");
    }

    @Test
    void writingPastCapacityGrowsWithoutLosingEarlierInstances() {
        BonePalette palette = new BonePalette(2);
        palette.writeInstance(0, QUADRUPED, new float[] {0f, 0f, 0.3f, 0f, 0f});
        palette.writeInstance(5, QUADRUPED, new float[] {0f, 0f, 0.9f, 0f, 0f});

        assertTrue(palette.capacity() >= 6);
        assertEquals(0.295520f, palette.matrix(0, 2, 1, 2), EPS, "instance 0 survived the growth");
        assertEquals(5, palette.writeInstance(5, QUADRUPED, new float[] {0f, 0f, 0.9f, 0f, 0f})
                / BonePalette.FLOATS_PER_INSTANCE);
    }

    @Test
    void aParentAfterItsChildIsRejected() {
        // The evaluator walks forward and reads the parent's finished matrix, so this would not
        // throw at evaluation time -- it would quietly read stale data instead.
        assertThrows(IllegalArgumentException.class, () -> new BoneRig(
                new int[] {1, -1},
                new float[] {0f, 0f}, new float[] {0f, 0f}, new float[] {0f, 0f},
                new int[] {BoneRig.AXIS_NONE, BoneRig.AXIS_NONE}));
    }

    @Test
    void aBoneThatIsItsOwnParentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BoneRig(
                new int[] {0}, new float[] {0f}, new float[] {0f}, new float[] {0f},
                new int[] {BoneRig.AXIS_NONE}));
    }

    @Test
    void aRigWithMoreBonesThanThePaletteHoldsIsRejected() {
        int n = BoneRig.MAX_BONES + 1;
        int[] parent = new int[n];
        float[] zeros = new float[n];
        int[] axes = new int[n];
        parent[0] = -1;
        assertThrows(IllegalArgumentException.class,
                () -> new BoneRig(parent, zeros, zeros, zeros, axes));
    }

    @Test
    void tooFewAnglesIsRejected() {
        BonePalette palette = new BonePalette();
        assertThrows(IllegalArgumentException.class,
                () -> palette.writeInstance(0, QUADRUPED, new float[] {0f, 0f}));
    }

    @Test
    void matrixEvaluationsCountsOnePerNonRootBone() {
        BonePalette palette = new BonePalette();
        palette.writeInstance(0, QUADRUPED, new float[] {0f, 0f, 0.3f, -0.6f, 0.6f});

        // Four of the five bones have a parent. This number against the vertex count is the whole
        // argument for evaluating here rather than in the vertex shader.
        assertEquals(4L, palette.matrixEvaluations());
    }

    @Test
    void evaluatingHereIsCheaperThanEvaluatingPerVertex() {
        // The claim the design rests on, stated as a test so it cannot drift. A 120 vertex model with
        // 500 entities evaluates the rig once per vertex in the shader, against once per entity here.
        int vertices = 120;
        int entities = 500;
        int bones = 12;
        long perVertex = (long) entities * vertices * bones;
        BonePalette palette = new BonePalette(entities);
        for (int i = 0; i < entities; i++) {
            palette.writeInstance(i, QUADRUPED, new float[] {0f, 0f, 0.3f, 0f, 0f});
        }
        long here = palette.matrixEvaluations();

        assertEquals((long) entities * 4, here);
        assertTrue(perVertex / here >= vertices / 2,
                "expected at least a " + vertices / 2 + " fold reduction, got " + perVertex / here);
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BonePalette(0));
        BonePalette palette = new BonePalette();
        assertThrows(IllegalArgumentException.class,
                () -> palette.writeInstance(-1, QUADRUPED, new float[] {0f, 0f, 0f, 0f, 0f}));
    }
}
