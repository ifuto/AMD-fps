package net.amdfaster.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The buffer exists so a frame with fifty thousand entities allocates fifty thousand nothing. The
 * behaviour worth pinning is therefore the steady state: after the first frame that reaches a given
 * size, later frames of the same size must not allocate again.
 */
class EntityBufferTest {

    private static int fill(EntityBuffer buffer, int count) {
        for (int i = 0; i < count; i++) {
            buffer.add(i % 7, i * 0.5f, 64f, i * 0.25f, i * 0.01f, i, 0.25f, 0.5f);
        }
        return count;
    }

    @Test
    void anEmptyBufferHoldsNothing() {
        EntityBuffer buffer = new EntityBuffer();
        assertTrue(buffer.isEmpty());
        assertEquals(0, buffer.size());
    }

    @Test
    void everyFieldRoundTrips() {
        EntityBuffer buffer = new EntityBuffer();
        int i = buffer.add(3, 1.5f, 64f, -2.25f, 0.75f, 0x00F00030, 0.3f, 1.8f);
        assertEquals(0, i, "the first entity is index 0");
        assertEquals(3, buffer.modelKey(i));
        assertEquals(1.5f, buffer.x(i), 0f);
        assertEquals(64f, buffer.y(i), 0f);
        assertEquals(-2.25f, buffer.z(i), 0f);
        assertEquals(0.75f, buffer.yaw(i), 0f);
        assertEquals(0x00F00030, buffer.light(i));
        assertEquals(0.3f, buffer.halfWidth(i), 0f);
        assertEquals(1.8f, buffer.height(i), 0f);
    }

    @Test
    void theDerivedBoundsUseTheFeetAsTheFloor() {
        // y is the entity's feet, matching Minecraft, so minY is y and maxY is y + height. Getting
        // this the other way round puts the culling box one entity-height too high.
        EntityBuffer buffer = new EntityBuffer();
        int i = buffer.add(1, 10f, 64f, 10f, 0f, 0, 0.25f, 1.8f);
        assertEquals(9.75f, buffer.minX(i), 0f);
        assertEquals(10.25f, buffer.maxX(i), 0f);
        assertEquals(64f, buffer.minY(i), 0f);
        assertEquals(65.8f, buffer.maxY(i), 1e-5f);
        assertEquals(9.75f, buffer.minZ(i), 0f);
        assertEquals(10.25f, buffer.maxZ(i), 0f);
    }

    @Test
    void indicesAreDenseAndInInsertionOrder() {
        EntityBuffer buffer = new EntityBuffer();
        for (int i = 0; i < 100; i++) {
            assertEquals(i, buffer.add(i % 7, 0f, 0f, 0f, 0f, 0, 0.25f, 0.5f),
                    "indices must be dense");
        }
        assertEquals(100, buffer.size());
        for (int i = 0; i < 100; i++) {
            assertEquals(i % 7, buffer.modelKey(i));
        }
    }

    @Test
    void growingKeepsEarlierEntries() {
        EntityBuffer buffer = new EntityBuffer(4);
        int count = 5000;
        fill(buffer, count);
        assertEquals(count, buffer.size());
        assertTrue(buffer.capacity() >= count);
        for (int i = 0; i < count; i++) {
            assertEquals(i % 7, buffer.modelKey(i), "model at " + i);
            assertEquals(i * 0.5f, buffer.x(i), 0f, "x at " + i);
        }
    }

    @Test
    void aSteadyStateFrameDoesNotAllocate() {
        // The property the class exists for. clear() keeps the arrays, so the second frame of the
        // same size reuses them and capacity is unchanged.
        EntityBuffer buffer = new EntityBuffer(8);
        fill(buffer, 4000);
        int capacity = buffer.capacity();
        buffer.clear();
        assertEquals(0, buffer.size());
        assertEquals(capacity, buffer.capacity(), "clear must not shrink");
        fill(buffer, 4000);
        assertEquals(capacity, buffer.capacity(), "the second frame reused the arrays");
    }

    @Test
    void nonsenseShapesAreRejected() {
        EntityBuffer buffer = new EntityBuffer();
        assertThrows(IllegalArgumentException.class,
                () -> buffer.add(1, 0f, 0f, 0f, 0f, 0, -0.1f, 1f));
        assertThrows(IllegalArgumentException.class,
                () -> buffer.add(1, 0f, 0f, 0f, 0f, 0, 0.1f, -1f));
        assertEquals(0, buffer.size(), "a rejected entity is not appended");
        assertThrows(IllegalArgumentException.class, () -> new EntityBuffer(0));
    }

    @Test
    void anInstanceRecordRoundTripsThroughTheBuffer() {
        EntityInstance original = new EntityInstance(5, 1.5f, 64f, -2.25f, 0.75f, 0x00F00030,
                0.3f, 1.8f);
        EntityBuffer buffer = new EntityBuffer();
        int i = buffer.add(original);
        assertEquals(original, buffer.instance(i));
    }

    @Test
    void theFieldCountMatchesTheGpuRecord() {
        // EntityGpuLayout writes eight fields per instance; if the buffer stops holding eight, one
        // of them is being read from the wrong array.
        assertEquals(8, EntityBuffer.FIELDS);
    }
}
