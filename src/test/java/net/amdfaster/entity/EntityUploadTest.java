package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The upload stage is where the allocation-free path could quietly stop being equivalent to the
 * record-based one. Both write the same 32 bytes, so the check is that they write the *same* 32
 * bytes, byte for byte -- a difference here is invisible in the test that produced it and shows up
 * as entities drawn in the wrong place.
 */
class EntityUploadTest {

    private static EntityBuffer sample() {
        EntityBuffer buffer = new EntityBuffer();
        buffer.add(3, 1.5f, 64f, -2.25f, 0.75f, 0x00F00030, 0.3f, 1.8f);
        buffer.add(3, 4.5f, 65f, 3.25f, -0.25f, 0x0000F0F0, 0.5f, 0.5f);
        buffer.add(7, -8.125f, 30f, 100.5f, 3.1f, 0, 0.25f, 2f);
        return buffer;
    }

    @Test
    void theBufferWriteMatchesTheRecordWriteByteForByte() {
        EntityBuffer source = sample();
        ByteBuffer viaBuffer = ByteBuffer.allocate(EntityGpuLayout.bytesFor(source.size()))
                .order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer viaRecord = ByteBuffer.allocate(EntityGpuLayout.bytesFor(source.size()))
                .order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < source.size(); i++) {
            EntityGpuLayout.write(viaBuffer, i, source, i);
            EntityGpuLayout.write(viaRecord, i, source.instance(i));
        }
        assertArrayEquals(viaRecord.array(), viaBuffer.array(),
                "the two write paths must produce identical bytes");
    }

    @Test
    void everyFieldRoundTripsThroughTheBuffer() {
        EntityBuffer source = sample();
        ByteBuffer buffer = ByteBuffer.allocate(EntityGpuLayout.bytesFor(source.size()))
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < source.size(); i++) {
            EntityGpuLayout.write(buffer, i, source, i);
        }
        for (int i = 0; i < source.size(); i++) {
            assertEquals(source.x(i), EntityGpuLayout.readX(buffer, i), 0f, "x at " + i);
            assertEquals(source.y(i), EntityGpuLayout.readY(buffer, i), 0f, "y at " + i);
            assertEquals(source.z(i), EntityGpuLayout.readZ(buffer, i), 0f, "z at " + i);
            assertEquals(source.yaw(i), EntityGpuLayout.readYaw(buffer, i), 0f, "yaw at " + i);
            assertEquals(source.light(i), EntityGpuLayout.readLight(buffer, i), "light at " + i);
            assertEquals(source.halfWidth(i), EntityGpuLayout.readHalfWidth(buffer, i), 0f,
                    "halfWidth at " + i);
            assertEquals(source.height(i), EntityGpuLayout.readHeight(buffer, i), 0f, "height at " + i);
        }
    }

    @Test
    void thePaddingIsZeroedEvenOverStaleBytes() {
        // The buffer is reused across frames. Left alone, the padding would hold last frame's
        // garbage and two frames with identical entities would upload different bytes.
        ByteBuffer buffer = ByteBuffer.allocate(EntityGpuLayout.bytesFor(2))
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(EntityGpuLayout.OFFSET_PADDING, 0xDEADBEEF);

        EntityGpuLayout.write(buffer, 0, sample(), 0);

        assertEquals(0, EntityGpuLayout.readPadding(buffer, 0));
        assertEquals(28, EntityGpuLayout.OFFSET_PADDING);
    }

    @Test
    void aBatchedSetUploadsInBatchOrderWithContiguousRuns() {
        // Two cells, one model, so two batches. The point is that batch i's instances occupy
        // [firstInstance, firstInstance + count) in the uploaded buffer with no gaps, which is what
        // lets the draw call use firstInstance directly.
        EntityBuffer source = new EntityBuffer();
        source.add(1, 4f, 64f, 4f, 0.1f, 111, 0.25f, 0.5f);
        source.add(1, 40f, 64f, 4f, 0.2f, 222, 0.25f, 0.5f);
        source.add(1, 4.5f, 64f, 4f, 0.3f, 333, 0.25f, 0.5f);

        SpatialBatchSet set = new SpatialEntityBatcher().build(source);
        assertEquals(2, set.drawCallCount());

        ByteBuffer buffer = ByteBuffer.allocate(EntityGpuLayout.bytesFor(set.instanceCount()))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(set.instanceCount(), EntityGpuLayout.writeAll(buffer, source, set));

        for (int b = 0; b < set.drawCallCount(); b++) {
            EntityBatch batch = set.batch(b);
            for (int i = 0; i < batch.instanceCount(); i++) {
                int slot = batch.firstInstance() + i;
                int entity = set.instanceIndex(slot);
                assertEquals(source.light(entity), EntityGpuLayout.readLight(buffer, slot),
                        "batch " + b + " instance " + i + " is in the wrong slot");
                assertEquals(source.x(entity), EntityGpuLayout.readX(buffer, slot), 0f);
            }
        }
        assertEquals(EntityGpuLayout.bytesFor(set.instanceCount()), set.instanceDataBytes());
    }

    @Test
    void anEmptySetUploadsNothing() {
        EntityBuffer source = new EntityBuffer();
        SpatialBatchSet set = new SpatialEntityBatcher().build(source);
        ByteBuffer buffer = ByteBuffer.allocate(EntityGpuLayout.INSTANCE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, EntityGpuLayout.writeAll(buffer, source, set));
    }

    @Test
    void writingPastTheEndOfTheBufferIsRejected() {
        // Silently truncating would upload half an entity, and the GPU would read the second half
        // out of whatever follows in memory.
        EntityBuffer source = sample();
        ByteBuffer oneSlot = ByteBuffer.allocate(EntityGpuLayout.bytesFor(1))
                .order(ByteOrder.LITTLE_ENDIAN);
        EntityGpuLayout.write(oneSlot, 0, source, 0);   // the one slot it has is fine
        assertThrows(IndexOutOfBoundsException.class,
                () -> EntityGpuLayout.write(oneSlot, 1, source, 1));
        // A negative index is rejected by offsetOf, before the capacity check, and as an
        // IllegalArgumentException because it is a nonsense argument rather than a buffer that ran
        // out of room. Asserting the type pins which of the two it is.
        assertThrows(IllegalArgumentException.class,
                () -> EntityGpuLayout.write(oneSlot, -1, source, 0));
        ByteBuffer nothing = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN);
        assertThrows(IndexOutOfBoundsException.class,
                () -> EntityGpuLayout.write(nothing, 0, source, 0));
    }

    @Test
    void theCullAndBatchAndUploadPathAgreesOnHowMuchItWrites() {
        // The whole pipeline in one: fifty thousand entities, most of them out of range, and the
        // upload is sized by what survived rather than by what exists.
        EntityBuffer source = new EntityBuffer();
        for (int i = 0; i < 50000; i++) {
            source.add(i % 3, 400f + i * 0.01f, 64f, 400f, 0f, i, 0.25f, 0.5f);
        }
        for (int i = 0; i < 40; i++) {
            source.add(1, 4f + (i % 8) * 0.5f, 64f, 4f + (i / 8) * 0.5f, 0f, 1000 + i, 0.25f, 0.5f);
        }

        EntityCuller culler = new EntityCuller();
        culler.cull(source, null, 0f, 64f, 0f);
        SpatialBatchSet set = new SpatialEntityBatcher().build(source, culler);

        assertEquals(40, set.instanceCount(), "only the entities near the camera survive");
        assertEquals(1, set.drawCallCount(), "all forty are one model in one cell");
        assertEquals(40 * EntityGpuLayout.INSTANCE_BYTES, set.instanceDataBytes());

        ByteBuffer buffer = ByteBuffer.allocate(set.instanceDataBytes())
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(40, EntityGpuLayout.writeAll(buffer, source, set));
        for (int i = 0; i < 40; i++) {
            assertEquals(1000 + i, EntityGpuLayout.readLight(buffer, i),
                    "instance " + i + " kept its own light value");
        }
    }
}
