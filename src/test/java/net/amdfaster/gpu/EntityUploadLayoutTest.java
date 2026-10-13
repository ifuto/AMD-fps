package net.amdfaster.gpu;

import net.amdfaster.entity.BonePalette;
import net.amdfaster.entity.EntityGpuLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityUploadLayoutTest {

    @Test
    void theStridesMatchTheClassesThatFillThem() {
        // If these drift, the layout reserves the wrong size and the upload overruns the
        // reservation, which corrupts whatever the ring handed to the next caller.
        assertEquals(32, EntityUploadLayout.INSTANCE_STRIDE);
        assertEquals(EntityGpuLayout.INSTANCE_BYTES, EntityUploadLayout.INSTANCE_STRIDE);
        assertEquals(1536, EntityUploadLayout.BONE_STRIDE);
        assertEquals(BonePalette.BYTES_PER_INSTANCE, EntityUploadLayout.BONE_STRIDE);
    }

    @Test
    void anEvenInstanceCountLaysTheStreamsContiguously() {
        EntityUploadLayout layout = EntityUploadLayout.of(500);

        assertEquals(0, layout.instanceOffset());
        assertEquals(16000, layout.boneOffset(), "500 * 32 is already a multiple of 64");
        assertEquals(784000, layout.totalBytes());
        assertEquals(0, layout.paddingBytes());
    }

    @Test
    void anOddInstanceCountPadsOnceBetweenTheStreams() {
        EntityUploadLayout layout = EntityUploadLayout.of(501);

        assertEquals(16064, layout.boneOffset(), "501 * 32 = 16032, rounded up to 64");
        assertEquals(785600, layout.totalBytes());
        assertEquals(32, layout.paddingBytes(), "and only the one gap, not one per instance");
    }

    @Test
    void aSingleInstanceStillGetsAnAlignedPalette() {
        EntityUploadLayout layout = EntityUploadLayout.of(1);

        assertEquals(64, layout.boneOffset());
        assertEquals(1600, layout.totalBytes());
    }

    @Test
    void noEntitiesNeedsNoBytes() {
        EntityUploadLayout layout = EntityUploadLayout.of(0);

        assertEquals(0, layout.totalBytes());
        assertEquals(0, layout.boneOffset());
        assertEquals(0, layout.paddingBytes());
    }

    @Test
    void theBoneStreamIsAlwaysAligned() {
        // A mat4 read from an unaligned offset is either slow or, on some drivers, undefined.
        for (int count = 0; count < 200; count++) {
            EntityUploadLayout layout = EntityUploadLayout.of(count);
            assertEquals(0, layout.boneOffset() % EntityUploadLayout.BONE_ALIGNMENT,
                    "count " + count + " gives bone offset " + layout.boneOffset());
            assertEquals(0, layout.boneOffsetOf(0) % EntityUploadLayout.BONE_ALIGNMENT);
            if (count > 0) {
                // Every instance's palette starts on a 64 byte boundary too, since the stride is a
                // multiple of the alignment.
                assertEquals(0, layout.boneOffsetOf(count - 1) % EntityUploadLayout.BONE_ALIGNMENT);
            }
        }
    }

    @Test
    void theStreamsNeverOverlap() {
        for (int count = 1; count < 200; count++) {
            EntityUploadLayout layout = EntityUploadLayout.of(count);
            int instanceEnd = layout.instanceOffset() + count * EntityUploadLayout.INSTANCE_STRIDE;
            assertTrue(layout.boneOffset() >= instanceEnd,
                    "count " + count + ": bone stream starts inside the instance stream");
            assertEquals(layout.boneOffset() + count * EntityUploadLayout.BONE_STRIDE,
                    layout.totalBytes());
        }
    }

    @Test
    void perInstanceOffsetsStepByTheStride() {
        EntityUploadLayout layout = EntityUploadLayout.of(10);

        assertEquals(0, layout.instanceOffsetOf(0));
        assertEquals(32, layout.instanceOffsetOf(1));
        assertEquals(288, layout.instanceOffsetOf(9));
        assertEquals(64, layout.boneOffsetOf(0));
        assertEquals(64 + 1536, layout.boneOffsetOf(1));
    }

    @Test
    void theLayoutIsWrittenInOneSequentialPass() {
        // The property the write-combined memory actually cares about: filling the reservation in
        // index order never moves backwards. A layout that interleaved the streams would not have
        // this property for a caller writing one stream at a time.
        EntityUploadLayout layout = EntityUploadLayout.of(64);
        int cursor = 0;
        for (int i = 0; i < 64; i++) {
            assertTrue(layout.instanceOffsetOf(i) >= cursor, "instance " + i + " moves backwards");
            cursor = layout.instanceOffsetOf(i);
        }
        cursor = 0;
        for (int i = 0; i < 64; i++) {
            assertTrue(layout.boneOffsetOf(i) >= cursor, "bone " + i + " moves backwards");
            cursor = layout.boneOffsetOf(i);
        }
    }

    @Test
    void nonsenseArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> EntityUploadLayout.of(-1));
        EntityUploadLayout layout = EntityUploadLayout.of(4);
        assertThrows(IndexOutOfBoundsException.class, () -> layout.instanceOffsetOf(4));
        assertThrows(IndexOutOfBoundsException.class, () -> layout.boneOffsetOf(-1));
    }

    @Test
    void ceilingDivisionIsUsedNotFloor() {
        assertEquals(64, EntityUploadLayout.align(32, 64));
        assertEquals(64, EntityUploadLayout.align(33, 64));
        assertEquals(0, EntityUploadLayout.align(0, 64));
        assertEquals(128, EntityUploadLayout.align(65, 64));
    }

    @Test
    void toStringNamesTheCountsForTheOverlay() {
        assertTrue(EntityUploadLayout.of(500).toString().contains("instances=500"));
    }
}
