package net.amdfaster.gpu;

import net.amdfaster.entity.BonePalette;
import net.amdfaster.entity.EntityGpuLayout;

/**
 * Where one frame's entity data sits inside a single staging reservation, and how many bytes it needs.
 *
 * <p>The entity analogue of {@link MeshUploadLayout}, following the same reasoning. Instanced entity
 * rendering rewrites two streams every frame -- the per-instance transform records and the bone
 * palette -- and they are always uploaded together, so they belong in one reservation. Three map/unmap
 * pairs or three ring reservations for data that is written in one pass is three chances to get the
 * synchronisation wrong and no benefit.
 *
 * <p>The staging memory is host-visible and not host-cached, which on this hardware means
 * write-combined. Write-combined memory wants sequential writes and punishes random access, so the
 * streams are laid out end to end and filled in one pass in the order they are declared. Neither
 * stream is ever read back.
 *
 * <h2>Order and alignment</h2>
 *
 * <p>Instance records first, then the bone palette. The records are 32 bytes each and the palette is
 * 64-byte aligned anyway because a bone matrix is a {@code mat4}, so putting the smaller stream first
 * means the padding lands in one place rather than between every pair.
 *
 * <p>Padding appears only when the instance count is odd: 32 times an odd number is not a multiple of
 * 64, so 32 bytes are skipped before the palette starts. At an even count the two streams are
 * contiguous. The padding is counted in the reservation but never written, which is fine -- it is
 * inside one allocation, and skipping 32 bytes forward in a sequential write costs nothing the way
 * seeking backwards would.
 *
 * <p>Immutable value type, thread safe.
 */
public final class EntityUploadLayout {

    /** Bytes per instance in the transform stream. */
    public static final int INSTANCE_STRIDE = EntityGpuLayout.INSTANCE_BYTES;

    /** Bytes per instance in the bone stream. */
    public static final int BONE_STRIDE = BonePalette.BYTES_PER_INSTANCE;

    /**
     * Alignment of the bone stream.
     *
     * <p>64 because a bone matrix is a {@code mat4} and a {@code mat4} in std430 is four vec4, which
     * wants 16-byte alignment at minimum -- but a 64-byte start also puts each instance's palette on
     * its own cache line boundary, and at 1.5 KB per instance that is 24 whole lines either way.
     */
    public static final int BONE_ALIGNMENT = 64;

    private final int instanceCount;
    private final int instanceOffset;
    private final int boneOffset;
    private final int totalBytes;

    private EntityUploadLayout(int instanceCount, int instanceOffset, int boneOffset, int totalBytes) {
        this.instanceCount = instanceCount;
        this.instanceOffset = instanceOffset;
        this.boneOffset = boneOffset;
        this.totalBytes = totalBytes;
    }

    /**
     * Lays out one frame's entity upload.
     *
     * @param instanceCount how many entities are visible this frame
     */
    public static EntityUploadLayout of(int instanceCount) {
        if (instanceCount < 0) {
            throw new IllegalArgumentException("instance count must not be negative: " + instanceCount);
        }
        int instanceOffset = 0;
        int instanceBytes = instanceCount * INSTANCE_STRIDE;
        int boneOffset = align(instanceBytes, BONE_ALIGNMENT);
        int boneBytes = instanceCount * BONE_STRIDE;
        return new EntityUploadLayout(instanceCount, instanceOffset, boneOffset,
                boneOffset + boneBytes);
    }

    /**
     * Rounds up to a multiple of the alignment.
     *
     * <p>Written as {@code (value + alignment - 1) / alignment * alignment} rather than with a mask,
     * because the alignment is a field here and a mask would need it to be a power of two known at
     * compile time.
     */
    static int align(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    public int instanceCount() {
        return this.instanceCount;
    }

    /** Where the transform stream starts. Always zero, since it is laid out first. */
    public int instanceOffset() {
        return this.instanceOffset;
    }

    /** Where the bone stream starts. */
    public int boneOffset() {
        return this.boneOffset;
    }

    /** Bytes to reserve, padding included. */
    public int totalBytes() {
        return this.totalBytes;
    }

    /**
     * Bytes skipped between the two streams.
     *
     * <p>Non-zero exactly when the instance count is odd. Worth being able to ask, because a layout
     * that padded on every count would be wasting 32 bytes per frame for no reason and this is how
     * that would be noticed.
     */
    public int paddingBytes() {
        return this.boneOffset - this.instanceCount * INSTANCE_STRIDE;
    }

    /** Where one instance's transform record goes. */
    public int instanceOffsetOf(int index) {
        checkIndex(index);
        return this.instanceOffset + index * INSTANCE_STRIDE;
    }

    /** Where one instance's bone palette goes. */
    public int boneOffsetOf(int index) {
        checkIndex(index);
        return this.boneOffset + index * BONE_STRIDE;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= this.instanceCount) {
            throw new IndexOutOfBoundsException(
                    "instance " + index + " of " + this.instanceCount);
        }
    }

    @Override
    public String toString() {
        return "EntityUploadLayout[instances=" + this.instanceCount
                + ", instanceOffset=" + this.instanceOffset
                + ", boneOffset=" + this.boneOffset
                + ", total=" + this.totalBytes + "]";
    }
}
