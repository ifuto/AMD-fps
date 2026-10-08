package net.amdfaster.gpu;

import net.amdfaster.mesh.SectionMesh;

/**
 * Where a section's three streams sit inside one staging allocation, and how many bytes it needs.
 *
 * <p>One allocation per section rather than three: a staging buffer is mapped once and written
 * sequentially, and three separate allocations would mean three map/unmap pairs or three ring
 * reservations for data that is always uploaded together. The regions are laid out end to end with
 * each one starting on an aligned offset, so a following section's reservation is aligned too.
 *
 * <p>The streams stay separate rather than interleaved. Interleaving would save a vertex binding,
 * but the Z pre-pass reads positions and nothing else, and the light stream is rewritten when a
 * torch is placed or the sun goes down without geometry changing at all. Interleaved, that update
 * would rewrite every byte of the section's vertex data to change one word per vertex.
 *
 * <p>Order is positions, attributes, lights, indices -- the order the streams are written in, so a
 * single sequential pass over the staging buffer fills all four without seeking.
 */
public record MeshUploadLayout(long positionsOffset, int positionsBytes,
                               long attributesOffset, int attributesBytes,
                               long lightsOffset, int lightsBytes,
                               long indicesOffset, int indicesBytes,
                               long totalBytes) {

    /**
     * Alignment every region starts on, and that {@link #totalBytes()} is rounded up to.
     *
     * <p>4 covers the widest element written -- a float in the attribute stream. It is not the
     * device's {@code minMemoryMapAlignment}, which is usually 64; the caller aligns the base of
     * the reservation instead, which is where that limit actually applies.
     */
    public static final int ALIGNMENT = 4;

    /** The layout of an empty section: no bytes, and the uploader skips it. */
    public static final MeshUploadLayout EMPTY = new MeshUploadLayout(0, 0, 0, 0, 0, 0, 0, 0, 0);

    public boolean isEmpty() {
        return this.totalBytes == 0;
    }

    /**
     * @param mesh       the section to lay out
     * @param baseOffset where this section's reservation starts inside the staging buffer
     */
    public static MeshUploadLayout forSection(SectionMesh mesh, long baseOffset) {
        return of(baseOffset, mesh.totalPositionBytes(), mesh.totalAttributeBytes(),
                mesh.totalLightBytes(), mesh.totalIndexBytes());
    }

    /**
     * The layout itself, split out from {@link #forSection} so the arithmetic can be tested
     * against byte counts no section happens to produce.
     *
     * @return {@link #EMPTY} when there is nothing to upload
     */
    public static MeshUploadLayout of(long baseOffset, int positionsBytes, int attributesBytes,
                                      int lightsBytes, int indicesBytes) {
        if (positionsBytes == 0 && attributesBytes == 0 && lightsBytes == 0 && indicesBytes == 0) {
            return EMPTY;
        }
        long start = alignUp(baseOffset, ALIGNMENT);
        long attributesOffset = alignUp(start + positionsBytes, ALIGNMENT);
        long lightsOffset = alignUp(attributesOffset + attributesBytes, ALIGNMENT);
        long indicesOffset = alignUp(lightsOffset + lightsBytes, ALIGNMENT);
        long end = alignUp(indicesOffset + indicesBytes, ALIGNMENT);
        return new MeshUploadLayout(start, positionsBytes, attributesOffset, attributesBytes,
                lightsOffset, lightsBytes, indicesOffset, indicesBytes, end - baseOffset);
    }

    private static long alignUp(long value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }
}
