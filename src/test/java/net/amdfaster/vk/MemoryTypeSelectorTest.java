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
import static net.amdfaster.vk.MemoryTypeSelector.LAZILY_ALLOCATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryTypeSelectorTest {

    private static final long GIB = 1L << 30;
    private static final long MIB = 1L << 20;

    // A discrete RDNA2 card with Resizable BAR off: 12 GiB of VRAM, a 256 MiB invisible tail,
    // and two flavours of system memory.
    private static final List<MemoryType> DISCRETE_NO_REBAR = List.of(
            new MemoryType(0, 0, 12 * GIB, DEVICE_LOCAL),
            new MemoryType(1, 1, 32 * GIB, HOST_VISIBLE | HOST_COHERENT),
            new MemoryType(2, 2, 256 * MIB, DEVICE_LOCAL),
            new MemoryType(3, 1, 32 * GIB, HOST_VISIBLE | HOST_COHERENT | HOST_CACHED));

    // The same card with Smart Access Memory on: the whole 24 GiB is host-visible.
    private static final List<MemoryType> DISCRETE_REBAR = List.of(
            new MemoryType(0, 0, 24 * GIB, DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT),
            new MemoryType(1, 1, 32 * GIB, HOST_VISIBLE | HOST_COHERENT),
            new MemoryType(2, 2, 256 * MIB, DEVICE_LOCAL));

    // An APU: one heap, and it is everything.
    private static final List<MemoryType> APU = List.of(
            new MemoryType(0, 0, 6 * GIB, DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT),
            new MemoryType(1, 0, 6 * GIB, HOST_VISIBLE | HOST_COHERENT | HOST_CACHED));

    // An older driver exposing only the classic 256 MiB BAR window.
    private static final List<MemoryType> SMALL_BAR = List.of(
            new MemoryType(0, 0, 8 * GIB, DEVICE_LOCAL),
            new MemoryType(1, 1, 256 * MIB, DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT),
            new MemoryType(2, 2, 32 * GIB, HOST_VISIBLE | HOST_COHERENT));

    @Test
    void meshesGoToDeviceLocalMemory() {
        assertEquals(0, MemoryTypeSelector.select(DISCRETE_NO_REBAR, Usage.GPU_ONLY).typeIndex());
        assertEquals(0, MemoryTypeSelector.select(DISCRETE_REBAR, Usage.GPU_ONLY).typeIndex(),
                "with a large BAR the big visible heap is still where meshes belong");
        assertEquals(0, MemoryTypeSelector.select(APU, Usage.GPU_ONLY).typeIndex());
    }

    @Test
    void gpuOnlyPrefersTheLargestDeviceLocalHeap() {
        // The 256 MiB invisible heap is for transient attachments, not for a mesh buffer.
        Selection s = MemoryTypeSelector.select(DISCRETE_NO_REBAR, Usage.GPU_ONLY);
        assertEquals(0, s.typeIndex());
        assertEquals("device-local, GPU-only", s.reason());
    }

    @Test
    void stagingBuffersAvoidHostCachedMemory() {
        // An uncached mapping is write-combined, which is the fastest path for the sequential
        // memcpy an upload does. A cached mapping would only add coherence traffic we never use.
        Selection s = MemoryTypeSelector.select(DISCRETE_NO_REBAR, Usage.CPU_TO_GPU);
        assertEquals(1, s.typeIndex());
        assertEquals(0, DISCRETE_NO_REBAR.get(s.typeIndex()).propertyFlags() & HOST_CACHED);
    }

    @Test
    void readbackBuffersWantHostCachedMemory() {
        Selection s = MemoryTypeSelector.select(DISCRETE_NO_REBAR, Usage.GPU_TO_CPU);
        assertEquals(3, s.typeIndex(), "readback is the one case that wants a cached mapping");
        assertEquals(HOST_CACHED, DISCRETE_NO_REBAR.get(3).propertyFlags() & HOST_CACHED);
    }

    @Test
    void readbackFallsBackWhenNothingIsCached() {
        List<MemoryType> types = List.of(
                new MemoryType(0, 0, 8 * GIB, DEVICE_LOCAL),
                new MemoryType(1, 1, 32 * GIB, HOST_VISIBLE | HOST_COHERENT));
        assertEquals(1, MemoryTypeSelector.select(types, Usage.GPU_TO_CPU).typeIndex());
    }

    @Test
    void anApuNeedsNoStagingCopyAtAll() {
        Selection s = MemoryTypeSelector.select(APU, Usage.CPU_AND_GPU);
        assertEquals(0, s.typeIndex());
        assertTrue(s.reason().contains("no staging copy needed"), s.reason());
        assertTrue(MemoryTypeSelector.hasUnifiedHostDeviceMemory(APU));
    }

    @Test
    void resizableBarAlsoNeedsNoStagingCopy() {
        Selection s = MemoryTypeSelector.select(DISCRETE_REBAR, Usage.CPU_AND_GPU);
        assertEquals(0, s.typeIndex());
        assertTrue(s.reason().contains("no staging copy needed"), s.reason());
        assertTrue(MemoryTypeSelector.hasUnifiedHostDeviceMemory(DISCRETE_REBAR));
    }

    @Test
    void withoutALargeBarTheRingFallsBackToSystemMemory() {
        Selection s = MemoryTypeSelector.select(DISCRETE_NO_REBAR, Usage.CPU_AND_GPU);
        assertEquals(1, s.typeIndex());
        assertTrue(s.reason().contains("staging copy is required"), s.reason());
        assertFalse(MemoryTypeSelector.hasUnifiedHostDeviceMemory(DISCRETE_NO_REBAR));
    }

    @Test
    void a256MiBBarWindowIsTooSmallToCountAsUnified() {
        // The window exists, but it cannot hold a frame's working set, so taking it would trade a
        // predictable copy for an unpredictable one later.
        assertFalse(MemoryTypeSelector.hasUnifiedHostDeviceMemory(SMALL_BAR));
        Selection s = MemoryTypeSelector.select(SMALL_BAR, Usage.CPU_AND_GPU);
        assertEquals(2, s.typeIndex(), "system memory, not the 256 MiB window");
    }

    @Test
    void aBarWindowIsNotWhereMeshesGoEither() {
        assertEquals(0, MemoryTypeSelector.select(SMALL_BAR, Usage.GPU_ONLY).typeIndex());
    }

    @Test
    void lazilyAllocatedMemoryIsNeverChosen() {
        List<MemoryType> types = List.of(
                new MemoryType(0, 0, 8 * GIB, DEVICE_LOCAL),
                new MemoryType(1, 1, 64 * GIB, DEVICE_LOCAL | LAZILY_ALLOCATED));
        assertEquals(0, MemoryTypeSelector.select(types, Usage.GPU_ONLY).typeIndex(),
                "the huge lazily allocated heap backs attachments, not buffers");
    }

    @Test
    void anImpossibleRequestFailsLoudly() {
        List<MemoryType> onlyDeviceLocal = List.of(new MemoryType(0, 0, 8 * GIB, DEVICE_LOCAL));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> MemoryTypeSelector.select(onlyDeviceLocal, Usage.CPU_TO_GPU));
        assertTrue(e.getMessage().contains("CPU_TO_GPU"), e.getMessage());
    }

    @Test
    void noMemoryTypesAtAllFailsLoudly() {
        assertThrows(IllegalStateException.class,
                () -> MemoryTypeSelector.select(List.of(), Usage.GPU_ONLY));
        assertFalse(MemoryTypeSelector.hasUnifiedHostDeviceMemory(List.of()));
    }
}
