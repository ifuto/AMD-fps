package net.amdfaster.entity;

import java.nio.ByteBuffer;

/**
 * The per-instance record the vertex shader reads.
 *
 * <pre>
 * offset  size  field
 *      0     4  x
 *      4     4  y
 *      8     4  z
 *     12     4  yaw, radians
 *     16     4  packed lightmap coordinate, raw bits
 *     20     4  halfWidth, blocks
 *     24     4  height, blocks
 *     28     4  pad
 * </pre>
 *
 * <p><b>Why 32 and not 28.</b> Seven floats is the actual data. Padding to 32 costs four bytes per
 * instance and buys two things: the record is 16-byte aligned, which is what a {@code vec4} load
 * wants, and two records fit exactly in a 64-byte cache line. At 28 bytes, most records straddle a
 * line boundary, so fetching one instance touches two lines instead of one. On a frame with a
 * thousand instances that is a thousand wasted line fills to save four kilobytes of upload.
 *
 * <p><b>Why light is raw bits.</b> The packed lightmap coordinate is two four-bit levels in a
 * 24-bit-apart layout. Unpacking it on the CPU to upload two floats would cost four bytes more and
 * an arithmetic op per instance per frame; the shader unpacks it once per vertex for free, because
 * the ALU is idle while the instance fetch is in flight.
 *
 * <p>Buffers written through here must be {@link java.nio.ByteOrder#LITTLE_ENDIAN}; x86 and ARM
 * client hardware is little-endian, and the GPU reads bytes, not ints.
 */
public final class EntityGpuLayout {

    /** Bytes per instance, padding included. */
    public static final int INSTANCE_BYTES = 32;

    /** Alignment of one record, and therefore of the buffer that holds them. */
    public static final int ALIGNMENT = 16;

    public static final int OFFSET_X = 0;
    public static final int OFFSET_Y = 4;
    public static final int OFFSET_Z = 8;
    public static final int OFFSET_YAW = 12;
    public static final int OFFSET_LIGHT = 16;
    public static final int OFFSET_HALF_WIDTH = 20;
    public static final int OFFSET_HEIGHT = 24;

    /**
     * The four bytes that pad a record to {@value #INSTANCE_BYTES}. Written as zero on every
     * upload; see {@link #write(ByteBuffer, int, EntityBuffer, int)}.
     */
    public static final int OFFSET_PADDING = 28;

    /** Fields actually written; the record is bigger than this because of the padding. */
    public static final int FIELDS = 7;

    private EntityGpuLayout() {
    }

    /** Bytes needed for {@code count} instances. */
    public static int bytesFor(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("negative instance count: " + count);
        }
        return count * INSTANCE_BYTES;
    }

    /** Byte offset of instance {@code index} in a buffer of these records. */
    public static int offsetOf(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("negative instance index: " + index);
        }
        return index * INSTANCE_BYTES;
    }

    /**
     * Writes one instance at {@code index}.
     *
     * <p>Writes are sequential within a record and records are written in order by the caller, which
     * keeps the pattern write-combining friendly: the buffer is host-visible and uncached on a
     * discrete GPU, and that memory tolerates sequential writes and punishes anything else.
     */
    public static void write(ByteBuffer buffer, int index, EntityInstance instance) {
        requireLittleEndian(buffer);
        int base = offsetOf(index);
        if (base + INSTANCE_BYTES > buffer.capacity()) {
            throw new IllegalArgumentException("buffer holds "
                    + (buffer.capacity() / INSTANCE_BYTES) + " instances, cannot write index " + index);
        }
        buffer.putInt(base + OFFSET_X, Float.floatToRawIntBits(instance.x()));
        buffer.putInt(base + OFFSET_Y, Float.floatToRawIntBits(instance.y()));
        buffer.putInt(base + OFFSET_Z, Float.floatToRawIntBits(instance.z()));
        buffer.putInt(base + OFFSET_YAW, Float.floatToRawIntBits(instance.yaw()));
        buffer.putInt(base + OFFSET_LIGHT, instance.light());
        buffer.putInt(base + OFFSET_HALF_WIDTH, Float.floatToRawIntBits(instance.halfWidth()));
        buffer.putInt(base + OFFSET_HEIGHT, Float.floatToRawIntBits(instance.height()));
        buffer.putInt(base + 28, 0);
    }

    /** Writes a whole batch set, instances in order. Returns the number of instances written. */
    /**
     * Writes one instance straight out of an {@link EntityBuffer}, without materialising an
     * {@link EntityInstance}.
     *
     * <p>This is the overload the scalable path uses. The record-taking {@link #write} above is
     * kept for callers that have a single entity in hand, but routing a whole frame through it
     * would allocate a record per instance, which is exactly what {@link EntityBuffer} avoids.
     */
    public static void write(ByteBuffer buffer, int index, EntityBuffer source, int sourceIndex) {
        int base = offsetOf(index);
        if (base < 0 || base + INSTANCE_BYTES > buffer.capacity()) {
            throw new IndexOutOfBoundsException("buffer holds "
                    + (buffer.capacity() / INSTANCE_BYTES) + " instances, cannot write index " + index);
        }
        // Absolute puts in ascending offset order. The buffer this lands in is host-visible and
        // therefore write-combined, and write-combined memory coalesces only writes that arrive in
        // order -- writing the fields out of order turns one bus transaction into eight.
        buffer.putInt(base + OFFSET_X, Float.floatToRawIntBits(source.x(sourceIndex)));
        buffer.putInt(base + OFFSET_Y, Float.floatToRawIntBits(source.y(sourceIndex)));
        buffer.putInt(base + OFFSET_Z, Float.floatToRawIntBits(source.z(sourceIndex)));
        buffer.putInt(base + OFFSET_YAW, Float.floatToRawIntBits(source.yaw(sourceIndex)));
        buffer.putInt(base + OFFSET_LIGHT, source.light(sourceIndex));
        buffer.putInt(base + OFFSET_HALF_WIDTH, Float.floatToRawIntBits(source.halfWidth(sourceIndex)));
        buffer.putInt(base + OFFSET_HEIGHT, Float.floatToRawIntBits(source.height(sourceIndex)));
        // Written every time rather than left alone: the buffer is reused across frames, and stale
        // padding would make two frames with identical entities upload different bytes, which
        // defeats any attempt to skip an unchanged upload.
        buffer.putInt(base + OFFSET_PADDING, 0);
    }

    /**
     * Writes a {@link SpatialBatchSet} into an instance buffer.
     *
     * <p>The set's indices are already in batch order, so this is one ascending pass and the bytes
     * for a batch are contiguous -- which is what lets the draw use
     * {@code firstInstance = batch.firstInstance()} with no per-batch offset fixups.
     *
     * @return how many instances were written
     */
    public static int writeAll(ByteBuffer buffer, EntityBuffer source, SpatialBatchSet set) {
        for (int i = 0; i < set.instanceCount(); i++) {
            write(buffer, i, source, set.instanceIndex(i));
        }
        return set.instanceCount();
    }

    public static int writeAll(ByteBuffer buffer, EntityBatchSet set) {
        int index = 0;
        for (EntityInstance instance : set.instances()) {
            write(buffer, index++, instance);
        }
        return index;
    }

    public static float readX(ByteBuffer buffer, int index) {
        return readFloat(buffer, index, OFFSET_X);
    }

    public static float readY(ByteBuffer buffer, int index) {
        return readFloat(buffer, index, OFFSET_Y);
    }

    public static float readZ(ByteBuffer buffer, int index) {
        return readFloat(buffer, index, OFFSET_Z);
    }

    public static float readYaw(ByteBuffer buffer, int index) {
        return readFloat(buffer, index, OFFSET_YAW);
    }

    public static int readLight(ByteBuffer buffer, int index) {
        requireLittleEndian(buffer);
        return buffer.getInt(offsetOf(index) + OFFSET_LIGHT);
    }

    public static float readHalfWidth(ByteBuffer buffer, int index) {
        return readFloat(buffer, index, OFFSET_HALF_WIDTH);
    }

    public static float readHeight(ByteBuffer buffer, int index) {
        return readFloat(buffer, index, OFFSET_HEIGHT);
    }

    /**
     * The padding word.
     *
     * <p>Zeroed rather than left alone, because an uninitialised buffer is not deterministic, and a
     * renderer whose output depends on whatever was in reused memory cannot be tested.
     */
    public static int readPadding(ByteBuffer buffer, int index) {
        requireLittleEndian(buffer);
        return buffer.getInt(offsetOf(index) + 28);
    }

    private static float readFloat(ByteBuffer buffer, int index, int offset) {
        requireLittleEndian(buffer);
        return Float.intBitsToFloat(buffer.getInt(offsetOf(index) + offset));
    }

    private static void requireLittleEndian(ByteBuffer buffer) {
        if (buffer.order() != java.nio.ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalArgumentException("instance data must be little-endian, got "
                    + buffer.order());
        }
    }
}
