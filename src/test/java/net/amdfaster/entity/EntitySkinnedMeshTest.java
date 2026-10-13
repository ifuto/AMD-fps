package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitySkinnedMeshTest {

    private static final float EPS = 1e-6f;

    private static EntitySkinnedMesh oneVertex() {
        EntitySkinnedMesh mesh = new EntitySkinnedMesh();
        mesh.addVertex(1f, 2f, 3f, 0f, 1f, 0f, 0.25f, 0.75f, 5);
        return mesh;
    }

    @Test
    void theStrideMatchesTheShaderDeclaration() {
        // The shader declares vec3, vec3, vec2, vec4, ivec4. If these constants drift from that, the
        // attributes read each other's bytes and every entity renders as noise rather than failing.
        assertEquals(64, EntitySkinnedMesh.VERTEX_STRIDE);
        assertEquals(0, EntitySkinnedMesh.OFFSET_POSITION);
        assertEquals(12, EntitySkinnedMesh.OFFSET_NORMAL);
        assertEquals(24, EntitySkinnedMesh.OFFSET_UV);
        assertEquals(32, EntitySkinnedMesh.OFFSET_BONE_WEIGHTS);
        assertEquals(48, EntitySkinnedMesh.OFFSET_BONE_INDICES);
        assertEquals(16, EntitySkinnedMesh.FLOATS_PER_VERTEX);
    }

    @Test
    void fieldsLandAtTheirDeclaredOffsets() {
        EntitySkinnedMesh mesh = oneVertex();

        assertEquals(1f, mesh.field(0, EntitySkinnedMesh.OFFSET_POSITION, 0), EPS);
        assertEquals(2f, mesh.field(0, EntitySkinnedMesh.OFFSET_POSITION, 1), EPS);
        assertEquals(3f, mesh.field(0, EntitySkinnedMesh.OFFSET_POSITION, 2), EPS);
        assertEquals(1f, mesh.field(0, EntitySkinnedMesh.OFFSET_NORMAL, 1), EPS);
        assertEquals(0.25f, mesh.field(0, EntitySkinnedMesh.OFFSET_UV, 0), EPS);
        assertEquals(0.75f, mesh.field(0, EntitySkinnedMesh.OFFSET_UV, 1), EPS);
    }

    @Test
    void aBoneIndexSurvivesTheRoundTripThroughFloatBits() {
        // The index is stored as the float whose bits are the integer, because that is what the
        // shader's ivec4 attribute reads. Storing it as a numeric float would look identical in a
        // test that only checked the value, and break on the GPU for any index above 2^24.
        EntitySkinnedMesh mesh = new EntitySkinnedMesh();
        mesh.addVertex(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f,
                new int[] {0, 1, 23, BoneRig.MAX_BONES - 1},
                new float[] {0.25f, 0.25f, 0.25f, 0.25f});

        assertEquals(0, mesh.boneIndex(0, 0));
        assertEquals(1, mesh.boneIndex(0, 1));
        assertEquals(23, mesh.boneIndex(0, 2));
        assertEquals(BoneRig.MAX_BONES - 1, mesh.boneIndex(0, 3));
    }

    @Test
    void weightsAreNormalisedToExactlyOne() {
        EntitySkinnedMesh mesh = new EntitySkinnedMesh();
        mesh.addVertex(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f,
                new int[] {0, 1, 2, 3}, new float[] {0.1f, 0.2f, 0.3f, 0.4f});

        assertEquals(1.0f, mesh.weightSum(0), EPS);
        assertEquals(0.1f, mesh.field(0, EntitySkinnedMesh.OFFSET_BONE_WEIGHTS, 0), EPS);
        assertEquals(0.4f, mesh.field(0, EntitySkinnedMesh.OFFSET_BONE_WEIGHTS, 3), EPS);
    }

    @Test
    void weightsThatDoNotSumToOneAreScaledRatherThanTrusted() {
        // A set summing to 0.99 makes the vertex drift toward the model origin, which reads as a
        // model slowly collapsing as the animation advances rather than as anything obviously wrong.
        EntitySkinnedMesh mesh = new EntitySkinnedMesh();
        mesh.addVertex(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f,
                new int[] {0, 1, 0, 0}, new float[] {0.5f, 0.49f, 0f, 0f});

        assertEquals(1.0f, mesh.weightSum(0), EPS);
    }

    @Test
    void aSingleInfluenceStaysExactlyOne() {
        // The common case for vanilla box parts. Any rounding here would move every vertex of every
        // box by a fraction of a unit, which accumulates across a model's part hierarchy.
        EntitySkinnedMesh mesh = oneVertex();

        assertEquals(1.0f, mesh.field(0, EntitySkinnedMesh.OFFSET_BONE_WEIGHTS, 0), 0.0f,
                "exactly 1.0, not 0.99999994");
        assertEquals(0f, mesh.field(0, EntitySkinnedMesh.OFFSET_BONE_WEIGHTS, 1), 0.0f);
    }

    @Test
    void allZeroWeightsAreRejected() {
        // Such a vertex belongs to no bone, so the blend produces a zero matrix and it is drawn at
        // the model origin for every entity -- one stray dot that follows each cow around.
        EntitySkinnedMesh mesh = new EntitySkinnedMesh();
        assertThrows(IllegalArgumentException.class, () -> mesh.addVertex(
                0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f,
                new int[] {0, 0, 0, 0}, new float[] {0f, 0f, 0f, 0f}));
        assertEquals(0, mesh.vertexCount(), "and nothing was written");
    }

    @Test
    void aBoneIndexOutsideThePaletteIsRejected() {
        EntitySkinnedMesh mesh = new EntitySkinnedMesh();
        assertThrows(IllegalArgumentException.class, () -> mesh.addVertex(
                0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f,
                new int[] {BoneRig.MAX_BONES, 0, 0, 0}, new float[] {1f, 0f, 0f, 0f}));
        assertThrows(IllegalArgumentException.class, () -> mesh.addVertex(
                0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f,
                new int[] {-1, 0, 0, 0}, new float[] {1f, 0f, 0f, 0f}));
    }

    @Test
    void aNegativeOrNaNWeightIsRejected() {
        EntitySkinnedMesh mesh = new EntitySkinnedMesh();
        assertThrows(IllegalArgumentException.class, () -> mesh.addVertex(
                0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f,
                new int[] {0, 0, 0, 0}, new float[] {-0.5f, 1f, 0f, 0f}));
        assertThrows(IllegalArgumentException.class, () -> mesh.addVertex(
                0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f,
                new int[] {0, 0, 0, 0}, new float[] {Float.NaN, 1f, 0f, 0f}));
    }

    @Test
    void growthPreservesEarlierVertices() {
        EntitySkinnedMesh mesh = new EntitySkinnedMesh(2);
        mesh.addVertex(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0);
        mesh.addVertex(2f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1);
        for (int i = 0; i < 300; i++) {
            mesh.addVertex(i, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0);
        }

        assertEquals(302, mesh.vertexCount());
        assertTrue(mesh.capacity() >= 302);
        assertEquals(1f, mesh.field(0, EntitySkinnedMesh.OFFSET_POSITION, 0), EPS);
        assertEquals(2f, mesh.field(1, EntitySkinnedMesh.OFFSET_POSITION, 0), EPS);
        assertEquals(299f, mesh.field(301, EntitySkinnedMesh.OFFSET_POSITION, 0), EPS);
    }

    @Test
    void aCompleteMeshRefusesMoreVertices() {
        EntitySkinnedMesh mesh = oneVertex();
        assertFalse(mesh.isComplete());
        mesh.complete();

        assertTrue(mesh.isComplete());
        assertThrows(IllegalStateException.class,
                () -> mesh.addVertex(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0));
    }

    @Test
    void usedBytesCoversOnlyTheVerticesWritten() {
        EntitySkinnedMesh mesh = new EntitySkinnedMesh(1024);
        mesh.addVertex(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0);
        mesh.addVertex(0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0);

        assertEquals(128, mesh.usedBytes(), "two vertices of 64 bytes, not the whole 1024 capacity");
        assertEquals(1024, mesh.capacity());
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EntitySkinnedMesh(0));
        EntitySkinnedMesh mesh = oneVertex();
        assertThrows(IndexOutOfBoundsException.class,
                () -> mesh.field(1, EntitySkinnedMesh.OFFSET_POSITION, 0));
        assertThrows(IllegalArgumentException.class, () -> mesh.field(0, 6, 0));
        assertThrows(IllegalArgumentException.class, () -> mesh.field(0, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> mesh.addVertex(
                0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f,
                new int[] {0, 0, 0}, new float[] {1f, 0f, 0f, 0f}));
    }
}
