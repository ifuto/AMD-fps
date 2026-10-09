package net.amdfaster.gpu;

import net.amdfaster.mesh.Meshlet;
import net.amdfaster.mesh.Orientation;
import net.amdfaster.mesh.SectionMesh;
import net.amdfaster.mesh.voxel.ArrayVoxelView;
import net.amdfaster.mesh.voxel.SectionMesher;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SectionUploadTest {

    /** One block: six meshlets, one quad each, so every slot is padded almost entirely. */
    private static SectionMesh oneBlock(int originX, int originY, int originZ) {
        ArrayVoxelView view = new ArrayVoxelView(16, 16, 16);
        view.set(4, 4, 4, 1, true);
        return SectionMesher.mesh(view, originX, originY, originZ);
    }

    private static ByteBuffer little(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    void everyBufferIsExactlyFull() {
        SectionMesh mesh = oneBlock(0, 0, 0);
        MeshletGpuLayout layout = MeshletGpuLayout.forSection(mesh);
        SectionUpload.Buffers buffers = SectionUpload.allocate(layout);

        SectionUpload.write(mesh, layout, buffers);

        assertEquals(layout.cullDataBytes(), buffers.cullData().position());
        assertEquals(layout.orientationBytes(), buffers.sideData().position());
        assertEquals(SectionUpload.positionBytes(layout), buffers.positions().position());
        assertEquals(SectionUpload.attributeBytes(layout), buffers.attributes().position());
        assertEquals(layout.lightBytes(), buffers.lights().position());
        assertEquals(layout.indexBytes(), buffers.indices().position());
    }

    @Test
    void eachMeshletStartsAtItsOwnStrideSlot() {
        // The property the whole fixed-stride layout exists for. A meshlet at slot i draws from
        // i * 372 indices and i * 248 vertices, which is what lets the cull shader write its own
        // draw command from a meshlet index with no lookup table. Writing the meshlets back to back
        // instead -- the obvious thing, and what a meshlet's own writers produce -- would leave
        // every meshlet after the first short one starting at the wrong offset, so every draw
        // would read the previous meshlet's vertices.
        SectionMesh mesh = oneBlock(0, 0, 0);
        MeshletGpuLayout layout = MeshletGpuLayout.forSection(mesh);
        SectionUpload.Buffers buffers = SectionUpload.allocate(layout);

        SectionUpload.write(mesh, layout, buffers);

        int slot = 0;
        for (Orientation o : Orientation.values()) {
            for (Meshlet meshlet : mesh.meshlets(o)) {
                // What this meshlet writes on its own, which is what has to appear at its slot.
                ByteBuffer alone = little(MeshletGpuLayout.INDICES_PER_MESHLET);
                meshlet.writeIndices(alone);
                int indexBytes = alone.position();

                int base = slot * MeshletGpuLayout.INDICES_PER_MESHLET;
                for (int i = 0; i < indexBytes; i++) {
                    assertEquals(alone.get(i), buffers.indices().get(base + i),
                            "slot " + slot + " index byte " + i + " is not this meshlet's");
                }
                // And the remainder of the slot is padding, not the next meshlet's data.
                for (int i = indexBytes; i < MeshletGpuLayout.INDICES_PER_MESHLET; i++) {
                    assertEquals(0, buffers.indices().get(base + i),
                            "slot " + slot + " index byte " + i + " should be padding");
                }

                ByteBuffer alonePositions = little(
                        MeshletGpuLayout.VERTICES_PER_MESHLET * Meshlet.POSITION_STRIDE);
                meshlet.writePositions(alonePositions);
                int positionBytes = alonePositions.position();
                int positionBase = slot * MeshletGpuLayout.VERTICES_PER_MESHLET * Meshlet.POSITION_STRIDE;
                for (int i = 0; i < positionBytes; i++) {
                    assertEquals(alonePositions.get(i), buffers.positions().get(positionBase + i),
                            "slot " + slot + " position byte " + i + " is not this meshlet's");
                }

                slot++;
            }
        }
        assertEquals(layout.meshletCount(), slot);
    }

    @Test
    void thePaddingIsZeroAndNotEmpty() {
        // A one-quad meshlet uses 6 of its 372 index bytes and 4 of its 248 vertex slots, so if the
        // slot were not padded this test would be comparing almost nothing. Asserting the padding
        // is both present and zero also pins that a reused mapping cannot carry the previous
        // section's data in an unused tail.
        SectionMesh mesh = oneBlock(0, 0, 0);
        MeshletGpuLayout layout = MeshletGpuLayout.forSection(mesh);
        SectionUpload.Buffers buffers = SectionUpload.allocate(layout);

        // Dirty the allocations first: if write() did not fill the tails, this would survive.
        dirty(buffers.indices());
        dirty(buffers.positions());
        buffers.indices().clear();
        buffers.positions().clear();

        SectionUpload.write(mesh, layout, buffers);

        int quads = mesh.meshlets(Orientation.NEG_X)[0].quadCount();
        int used = quads * Meshlet.INDEX_BYTES_PER_QUAD;
        assertNotEquals(MeshletGpuLayout.INDICES_PER_MESHLET, used,
                "the fixture has to under-fill its slot or this asserts nothing");

        for (int i = used; i < MeshletGpuLayout.INDICES_PER_MESHLET; i++) {
            assertEquals(0, buffers.indices().get(i), "index padding byte " + i);
        }
        int usedPositions = mesh.meshlets(Orientation.NEG_X)[0].vertexCount() * Meshlet.POSITION_STRIDE;
        for (int i = usedPositions;
             i < MeshletGpuLayout.VERTICES_PER_MESHLET * Meshlet.POSITION_STRIDE; i++) {
            assertEquals(0, buffers.positions().get(i), "position padding byte " + i);
        }
    }

    private static void dirty(ByteBuffer buffer) {
        for (int i = 0; i < buffer.capacity(); i++) {
            buffer.put(i, (byte) 0x5A);
        }
    }

    @Test
    void theSideDataWordCarriesBothTheBucketAndTheQuadCount() {
        // The cull shader writes its own draw command, and an exact indexCount is the only way it
        // can avoid shading the padding. Both halves have to survive the packing: the bucket alone
        // would draw every meshlet as a full 62 quads, and the quad count alone would back-face
        // cull against the wrong normal.
        for (Orientation o : Orientation.values()) {
            for (int quads = 1; quads <= Meshlet.MAX_QUADS; quads++) {
                int word = Meshlet.sideData(o.ordinal(), quads);
                assertEquals(o.ordinal(), Meshlet.sideDataOrientation(word),
                        o + " with " + quads + " quads");
                assertEquals(quads, Meshlet.sideDataQuads(word),
                        o + " with " + quads + " quads");
            }
        }
    }

    @Test
    void theWrittenSideDataMatchesTheShaderDecoding() {
        SectionMesh mesh = oneBlock(-32, -16, -48);
        MeshletGpuLayout layout = MeshletGpuLayout.forSection(mesh);
        SectionUpload.Buffers buffers = SectionUpload.allocate(layout);

        SectionUpload.write(mesh, layout, buffers);

        int slot = 0;
        for (Orientation o : Orientation.values()) {
            for (Meshlet meshlet : mesh.meshlets(o)) {
                // sideData[index] & 7u for the bucket, sideData[index] >> 3u for the quad count,
                // exactly as meshlet_cull.comp reads them.
                int word = buffers.sideData().getInt(slot * CullBindings.ORIENTATION_BYTES_PER_MESHLET);
                assertEquals(o.ordinal(), word & Meshlet.ORIENTATION_MASK, "slot " + slot + " bucket");
                assertEquals(meshlet.quadCount(), word >>> Meshlet.SIDE_DATA_QUAD_SHIFT,
                        "slot " + slot + " quad count");
                assertEquals(meshlet.quadCount() * 6,
                        (word >>> Meshlet.SIDE_DATA_QUAD_SHIFT) * 6,
                        "indexCount the shader will write");
                slot++;
            }
        }
    }

    @Test
    void anEmptySectionWritesNothing() {
        SectionMesh mesh = SectionMesher.mesh(new ArrayVoxelView(16, 16, 16), 0, 0, 0);
        MeshletGpuLayout layout = MeshletGpuLayout.forSection(mesh);
        SectionUpload.Buffers buffers = SectionUpload.allocate(layout);

        SectionUpload.write(mesh, layout, buffers);

        assertEquals(0, buffers.cullData().position());
        assertEquals(0, buffers.sideData().position());
        assertEquals(0, buffers.positions().position());
        assertEquals(0, buffers.indices().position());
    }
}
