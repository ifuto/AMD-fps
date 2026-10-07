package net.amdfaster.vk;

import net.amdfaster.vk.MemoryTypeSelector.MemoryType;
import net.amdfaster.vk.MemoryTypeSelector.Selection;
import net.amdfaster.vk.MemoryTypeSelector.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.amdfaster.vk.MemoryTypeSelector.DEVICE_LOCAL;
import static net.amdfaster.vk.MemoryTypeSelector.HOST_CACHED;
import static net.amdfaster.vk.MemoryTypeSelector.HOST_COHERENT;
import static net.amdfaster.vk.MemoryTypeSelector.HOST_VISIBLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryTypeBitsTest {

    private static final long GIB = 1L << 30;

    private static final List<MemoryType> DISCRETE = List.of(
            new MemoryType(0, 0, 12 * GIB, DEVICE_LOCAL),
            new MemoryType(1, 1, 32 * GIB, HOST_VISIBLE | HOST_COHERENT),
            new MemoryType(2, 2, 256 * GIB, DEVICE_LOCAL));

    private static int bits(int... indices) {
        int mask = 0;
        for (int i : indices) {
            mask |= 1 << i;
        }
        return mask;
    }

    @Test
    void aFullMaskBehavesLikeTheUnrestrictedChoice() {
        assertEquals(MemoryTypeSelector.select(DISCRETE, Usage.GPU_ONLY).typeIndex(),
                MemoryTypeSelector.selectForBits(DISCRETE, Usage.GPU_ONLY, bits(0, 1, 2)).typeIndex());
    }

    @Test
    void theMaskOverridesThePreferredHeap() {
        // The driver has ruled out the big device-local heap for this buffer, so the small
        // invisible one is the only device-local option left.
        Selection s = MemoryTypeSelector.selectForBits(DISCRETE, Usage.GPU_ONLY, bits(1, 2));
        assertEquals(2, s.typeIndex());
    }

    @Test
    void aMaskWithNoDeviceLocalMemoryFailsWithTheMaskInTheMessage() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> MemoryTypeSelector.selectForBits(DISCRETE, Usage.GPU_ONLY, bits(1)));
        assertTrue(e.getMessage().contains("memoryTypeBits 0x2"), e.getMessage());
        assertTrue(e.getMessage().contains("allowed types [1]"), e.getMessage());
    }

    @Test
    void anEmptyMaskFailsImmediately() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> MemoryTypeSelector.selectForBits(DISCRETE, Usage.CPU_TO_GPU, 0));
        assertTrue(e.getMessage().contains("allows none"), e.getMessage());
    }

    @Test
    void stagingStillAvoidsCachedMemoryWhenTheMaskAllowsBoth() {
        List<MemoryType> types = List.of(
                new MemoryType(0, 0, 12 * GIB, DEVICE_LOCAL),
                new MemoryType(1, 1, 32 * GIB, HOST_VISIBLE | HOST_COHERENT),
                new MemoryType(2, 1, 32 * GIB, HOST_VISIBLE | HOST_COHERENT | HOST_CACHED));
        assertEquals(1, MemoryTypeSelector.selectForBits(types, Usage.CPU_TO_GPU, bits(0, 1, 2)).typeIndex());
    }
}
