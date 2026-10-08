package net.amdfaster.entity;

import net.amdfaster.light.LightValue;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityGpuLayoutTest {

    private static EntityInstance sample() {
        return new EntityInstance(1, 1.5f, 64.25f, -3.75f, 0.5f, LightValue.pack(7, 12),
                0.25f, 0.5f);
    }

    private static ByteBuffer buffer(int instances) {
        return ByteBuffer.allocateDirect(instances * EntityGpuLayout.INSTANCE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    void theRecordIsThirtyTwoBytesWithSevenFieldsAndOnePad() {
        assertEquals(32, EntityGpuLayout.INSTANCE_BYTES);
        assertEquals(16, EntityGpuLayout.ALIGNMENT);
        assertEquals(7, EntityGpuLayout.FIELDS);
        assertEquals(0, EntityGpuLayout.OFFSET_X);
        assertEquals(4, EntityGpuLayout.OFFSET_Y);
        assertEquals(8, EntityGpuLayout.OFFSET_Z);
        assertEquals(12, EntityGpuLayout.OFFSET_YAW);
        assertEquals(16, EntityGpuLayout.OFFSET_LIGHT);
        assertEquals(20, EntityGpuLayout.OFFSET_HALF_WIDTH);
        assertEquals(24, EntityGpuLayout.OFFSET_HEIGHT);
    }

    @Test
    void theSizeMathIsStrideTimesCount() {
        assertEquals(0, EntityGpuLayout.bytesFor(0));
        assertEquals(32, EntityGpuLayout.bytesFor(1));
        assertEquals(320, EntityGpuLayout.bytesFor(10));
        assertEquals(0, EntityGpuLayout.offsetOf(0));
        assertEquals(96, EntityGpuLayout.offsetOf(3));
        assertThrows(IllegalArgumentException.class, () -> EntityGpuLayout.bytesFor(-1));
        assertThrows(IllegalArgumentException.class, () -> EntityGpuLayout.offsetOf(-1));
    }

    @Test
    void everyFieldSurvivesTheRoundTrip() {
        EntityInstance instance = sample();
        ByteBuffer buf = buffer(1);
        EntityGpuLayout.write(buf, 0, instance);

        assertEquals(instance.x(), EntityGpuLayout.readX(buf, 0), 0f);
        assertEquals(instance.y(), EntityGpuLayout.readY(buf, 0), 0f);
        assertEquals(instance.z(), EntityGpuLayout.readZ(buf, 0), 0f);
        assertEquals(instance.yaw(), EntityGpuLayout.readYaw(buf, 0), 0f);
        assertEquals(instance.halfWidth(), EntityGpuLayout.readHalfWidth(buf, 0), 0f);
        assertEquals(instance.height(), EntityGpuLayout.readHeight(buf, 0), 0f);
        // Exact, not approximate: light is stored as raw bits and unpacked by the shader.
        assertEquals(instance.light(), EntityGpuLayout.readLight(buf, 0));
        assertEquals(7, LightValue.block(EntityGpuLayout.readLight(buf, 0)));
        assertEquals(12, LightValue.sky(EntityGpuLayout.readLight(buf, 0)));
    }

    @Test
    void thePaddingIsZeroed() {
        // A reused buffer holding last frame's data would otherwise make the output depend on
        // whatever was there, which is untestable and, on a driver that reads the pad, undefined.
        ByteBuffer buf = buffer(2);
        buf.putInt(28, 0xDEADBEEF);
        EntityGpuLayout.write(buf, 0, sample());
        assertEquals(0, EntityGpuLayout.readPadding(buf, 0));
    }

    @Test
    void consecutiveRecordsDoNotOverlap() {
        // This is the test that catches a wrong stride, which is the classic bug in this kind of
        // layout: the first record reads back fine and every later one is shifted.
        EntityInstance a = new EntityInstance(1, 1f, 2f, 3f, 0f, 0, 0.1f, 0.2f);
        EntityInstance b = new EntityInstance(1, 4f, 5f, 6f, 0f, 0, 0.3f, 0.4f);
        ByteBuffer buf = buffer(2);
        EntityGpuLayout.write(buf, 0, a);
        EntityGpuLayout.write(buf, 1, b);

        assertEquals(1f, EntityGpuLayout.readX(buf, 0), 0f);
        assertEquals(2f, EntityGpuLayout.readY(buf, 0), 0f);
        assertEquals(3f, EntityGpuLayout.readZ(buf, 0), 0f);
        assertEquals(4f, EntityGpuLayout.readX(buf, 1), 0f);
        assertEquals(5f, EntityGpuLayout.readY(buf, 1), 0f);
        assertEquals(6f, EntityGpuLayout.readZ(buf, 1), 0f);
    }

    @Test
    void writingPastTheEndIsRejected() {
        ByteBuffer buf = buffer(2);
        assertThrows(IllegalArgumentException.class, () -> EntityGpuLayout.write(buf, 2, sample()));
        assertThrows(IllegalArgumentException.class, () -> EntityGpuLayout.write(buf, 99, sample()));
    }

    @Test
    void aBigEndianBufferIsRejected() {
        ByteBuffer buf = ByteBuffer.allocateDirect(EntityGpuLayout.INSTANCE_BYTES);
        // allocateDirect defaults to big-endian, which is exactly the mistake this guards against.
        assertEquals(ByteOrder.BIG_ENDIAN, buf.order());
        assertThrows(IllegalArgumentException.class, () -> EntityGpuLayout.write(buf, 0, sample()));
    }

    @Test
    void writeAllWritesEveryInstanceInOrder() {
        EntityBatcher batcher = new EntityBatcher();
        for (int i = 0; i < 5; i++) {
            batcher.add(new EntityInstance(1, i, 64f, 0f, 0f, 0, 0.25f, 0.5f));
        }
        EntityBatchSet set = batcher.build();
        ByteBuffer buf = buffer(set.instanceCount());
        assertEquals(5, EntityGpuLayout.writeAll(buf, set));

        for (int i = 0; i < set.instanceCount(); i++) {
            assertEquals(set.instances().get(i).x(), EntityGpuLayout.readX(buf, i), 0f,
                    "instance " + i);
        }
    }

    @Test
    void theBatchSetIsContiguousAndItsSizeMatchesTheUpload() {
        EntityBatcher batcher = new EntityBatcher();
        for (int i = 0; i < 30; i++) {
            batcher.add(new EntityInstance(1 + (i % 2), i * 0.1f, 64f, 0f, 0f, 0, 0.25f, 0.5f));
        }
        EntityBatchSet set = batcher.build();
        assertEquals(30, set.instanceCount());
        assertEquals(2, set.drawCallCount());
        assertTrue(set.isContiguous());
        assertEquals(30 * EntityGpuLayout.INSTANCE_BYTES, set.instanceDataBytes());
        assertEquals(EntityGpuLayout.bytesFor(30), set.instanceDataBytes());
    }

}
