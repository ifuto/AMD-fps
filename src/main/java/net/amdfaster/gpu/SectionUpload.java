package net.amdfaster.gpu;

import net.amdfaster.mesh.Meshlet;
import net.amdfaster.mesh.Orientation;
import net.amdfaster.mesh.SectionMesh;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Fills the six buffers a section's mesh data occupies.
 *
 * <p>This is the layer between a {@link SectionMesh} and a mapped {@link GpuBuffer}: everything
 * here is plain {@link ByteBuffer} writes, so the packing is testable without a device, and the
 * caller hands the results to Vulkan without reinterpretation.
 *
 * <p>The reason this cannot be a straight sequential write of each meshlet's own data is the fixed
 * stride. A meshlet at slot {@code i} draws from {@code i * }{@value MeshletGpuLayout#INDICES_PER_MESHLET}
 * {@code } indices and {@code i * }{@value MeshletGpuLayout#VERTICES_PER_MESHLET}{@code } vertices,
 * which is what lets the cull shader derive its own draw command from a meshlet index with no
 * lookup table. Most meshlets hold far fewer than
 * {@value Meshlet#MAX_QUADS} quads, so each one has to be written into its own slot and the rest of
 * the slot zero-filled. Writing them back to back instead would leave every meshlet after the first
 * short one starting at the wrong offset, and every draw would read the previous meshlet's
 * vertices -- visible as corrupt geometry, not as a crash.
 *
 * <p>Zero-filling is safe because the shader writes an exact {@code indexCount} from the quad count
 * in the side data word, so the padding is never indexed. It is still written rather than left as
 * whatever the mapping held, because a mapped buffer reused across rebuilds would otherwise carry
 * the previous section's data in every unused tail.
 */
public final class SectionUpload {

    /** Bytes of position data the section occupies, at the fixed stride. */
    public static int positionBytes(MeshletGpuLayout layout) {
        return layout.vertexSlots() * Meshlet.POSITION_STRIDE;
    }

    /** Bytes of attribute data the section occupies, at the fixed stride. */
    public static int attributeBytes(MeshletGpuLayout layout) {
        return layout.vertexSlots() * Meshlet.ATTRIBUTE_STRIDE;
    }

    /** The six destinations, each positioned at the start of the region to fill. */
    public record Buffers(ByteBuffer cullData, ByteBuffer sideData, ByteBuffer positions,
                          ByteBuffer attributes, ByteBuffer lights, ByteBuffer indices) {
    }

    /**
     * Allocates the six buffers for a section.
     *
     * <p>Direct and little-endian, matching what {@link GpuBuffer#mapForWrite()} returns: GPU
     * buffers are little-endian on every architecture this targets, and the writers assert it
     * rather than silently byte-swapping into a corrupt mesh.
     */
    public static Buffers allocate(MeshletGpuLayout layout) {
        return new Buffers(
                direct(layout.cullDataBytes()),
                direct(layout.orientationBytes()),
                direct(positionBytes(layout)),
                direct(attributeBytes(layout)),
                direct(layout.lightBytes()),
                direct(layout.indexBytes()));
    }

    private static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private SectionUpload() {
    }

    /**
     * Writes every meshlet of {@code mesh} into {@code out}, in the slot order
     * {@link MeshletGpuLayout#forSection} assigns.
     *
     * <p>All six buffers are filled in one walk. They have to agree on slot order: the cull shader
     * reads bounds from one buffer and the orientation from another, and the draw command it writes
     * addresses the index and vertex streams by slot. A walk that reordered any one of them would
     * pair a meshlet's bounds with another's normal or draw it from another's vertices.
     *
     * @throws IllegalStateException if the section no longer holds the meshlet count the layout was
     *                               built from, which means it was re-meshed in between
     */
    public static void write(SectionMesh mesh, MeshletGpuLayout layout, Buffers out) {
        layout.writeCullData(mesh, out.cullData(), out.sideData());

        int slot = 0;
        for (Orientation orientation : Orientation.values()) {
            for (Meshlet meshlet : mesh.meshlets(orientation)) {
                writeSlotted(out.positions(), meshlet::writePositions,
                        MeshletGpuLayout.VERTICES_PER_MESHLET * Meshlet.POSITION_STRIDE);
                writeSlotted(out.attributes(), meshlet::writeAttributes,
                        MeshletGpuLayout.VERTICES_PER_MESHLET * Meshlet.ATTRIBUTE_STRIDE);
                writeSlotted(out.lights(), meshlet::writeLights,
                        MeshletGpuLayout.LIGHT_BYTES_PER_MESHLET);
                writeSlotted(out.indices(), meshlet::writeIndices,
                        MeshletGpuLayout.INDICES_PER_MESHLET);
                slot++;
            }
        }

        // writeCullData already checked the count against the layout; checking the byte positions
        // here catches a stride that disagrees with it, which the count check cannot see.
        requireFull("positions", out.positions(), positionBytes(layout));
        requireFull("attributes", out.attributes(), attributeBytes(layout));
        requireFull("lights", out.lights(), layout.lightBytes());
        requireFull("indices", out.indices(), layout.indexBytes());
        requireFull("cullData", out.cullData(), layout.cullDataBytes());
        requireFull("sideData", out.sideData(), layout.orientationBytes());
    }

    private interface StreamWriter {
        void write(ByteBuffer out);
    }

    /** Writes one meshlet's stream into its fixed-stride slot and zeroes the unused remainder. */
    private static void writeSlotted(ByteBuffer out, StreamWriter writer, int strideBytes) {
        int start = out.position();
        writer.write(out);
        int written = out.position() - start;
        if (written > strideBytes) {
            throw new IllegalStateException("a meshlet wrote " + written
                    + " bytes into a " + strideBytes + "-byte slot");
        }
        for (int i = written; i < strideBytes; i++) {
            out.put((byte) 0);
        }
    }

    private static void requireFull(String name, ByteBuffer buffer, int expected) {
        if (buffer.position() != expected) {
            throw new IllegalStateException(name + " holds " + buffer.position()
                    + " bytes but the layout reserves " + expected);
        }
    }
}
