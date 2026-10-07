package net.amdfaster.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectionMeshBuilderTest {

    private static Quad unit(Orientation o) {
        return WindingTest.unitQuad(o);
    }

    @Test
    void quadsAreRoutedIntoOrientationBuckets() {
        SectionMeshBuilder builder = new SectionMeshBuilder(0, 0, 0);
        builder.add(unit(Orientation.POS_Y));
        builder.add(unit(Orientation.POS_Y));
        builder.add(unit(Orientation.NEG_Z));

        SectionMesh mesh = builder.build();
        assertEquals(2, mesh.quadCount(Orientation.POS_Y));
        assertEquals(1, mesh.quadCount(Orientation.NEG_Z));
        assertEquals(0, mesh.quadCount(Orientation.NEG_X));
        assertEquals(3, mesh.totalQuads());
        assertEquals(6, mesh.totalTriangles());
        assertEquals(2, mesh.totalMeshlets());
    }

    @Test
    void distantCameraDrawsHalfTheTriangles() {
        SectionMeshBuilder builder = new SectionMeshBuilder(0, 0, 0);
        for (Orientation o : Orientation.values()) {
            builder.add(unit(o));
        }
        SectionMesh mesh = builder.build();

        assertEquals(12, mesh.totalTriangles());
        assertEquals(12, mesh.visibleTriangles(8, 8, 8), "inside the section, nothing can be dropped");
        assertEquals(6, mesh.visibleTriangles(100, 100, 100),
                "a section the camera is not inside must draw 3 of 6 buckets");
        assertEquals(6, mesh.visibleTriangles(-100, -100, -100));
    }

    @Test
    void bucketMaskUsesTheSectionOrigin() {
        SectionMeshBuilder builder = new SectionMeshBuilder(32, 0, 0);
        for (Orientation o : Orientation.values()) {
            builder.add(unit(o));
        }
        SectionMesh mesh = builder.build();

        assertEquals(12, mesh.visibleTriangles(40, 8, 8), "camera is inside this section");
        assertEquals(6, mesh.visibleTriangles(200, 100, 100));
    }

    @Test
    void overflowsIntoMultipleMeshlets() {
        SectionMeshBuilder builder = new SectionMeshBuilder(0, 0, 0);
        int total = Meshlet.MAX_QUADS * 2 + 6;
        for (int i = 0; i < total; i++) {
            int x = i % 16;
            int z = (i / 16) % 16;
            int y = 1 + i / 256;
            builder.add(new Quad(Orientation.POS_Y, x, y, z, x + 1, y, z + 1, 0f, 0f, 1f, 1f, 0, 0));
        }
        SectionMesh mesh = builder.build();

        Meshlet[] bucket = mesh.meshlets(Orientation.POS_Y);
        assertEquals(3, bucket.length);
        assertEquals(Meshlet.MAX_QUADS, bucket[0].quadCount());
        assertEquals(Meshlet.MAX_QUADS, bucket[1].quadCount());
        assertEquals(6, bucket[2].quadCount());
        assertEquals(total, mesh.quadCount(Orientation.POS_Y));

        for (Meshlet m : bucket) {
            assertTrue(m.vertexCount() <= Meshlet.MAX_VERTICES);
            assertTrue(m.quadCount() <= Meshlet.MAX_QUADS);
        }
    }

    @Test
    void emptySectionProducesEmptyBuckets() {
        SectionMesh mesh = new SectionMeshBuilder(0, 0, 0).build();
        assertTrue(mesh.isEmpty());
        assertEquals(0, mesh.totalMeshlets());
        for (Orientation o : Orientation.values()) {
            assertEquals(0, mesh.meshlets(o).length);
        }
    }

    @Test
    void degenerateQuadsAreIgnored() {
        SectionMeshBuilder builder = new SectionMeshBuilder(0, 0, 0);
        builder.add(new Quad(Orientation.POS_Y, 0, 16, 0, 0, 16, 1, 0f, 0f, 1f, 1f, 0, 0));
        SectionMesh mesh = builder.build();
        assertTrue(mesh.isEmpty());
    }

    @Test
    void byteBudgetsFollowTheDeclaredStrides() {
        SectionMeshBuilder builder = new SectionMeshBuilder(0, 0, 0);
        for (Orientation o : Orientation.values()) {
            builder.add(unit(o));
        }
        SectionMesh mesh = builder.build();

        assertEquals(mesh.totalVertices() * Meshlet.POSITION_STRIDE, mesh.totalPositionBytes());
        assertEquals(mesh.totalVertices() * Meshlet.ATTRIBUTE_STRIDE, mesh.totalAttributeBytes());
        assertEquals(mesh.totalQuads() * Meshlet.INDEX_BYTES_PER_QUAD, mesh.totalIndexBytes());
    }
}
