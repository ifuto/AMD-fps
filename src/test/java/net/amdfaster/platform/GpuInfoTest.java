package net.amdfaster.platform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UMA / Resizable BAR detection decides whether AMD-Faster can write chunk geometry straight
 * into device memory, so the threshold logic is exercised directly rather than on real hardware.
 */
class GpuInfoTest {

    private static final long GiB = 1024L * 1024 * 1024;

    /** Discrete card without Resizable BAR: a 256 MB BAR window and 8 GB of VRAM. */
    private static GpuInfo discreteNoRebar() {
        return device(2, new GpuInfo.Memory(
                List.of(new GpuInfo.Heap(0, 8 * GiB, true), new GpuInfo.Heap(1, 8 * GiB, false)),
                List.of(new GpuInfo.Type(0, 0, 0x1), new GpuInfo.Type(1, 1, 0x6),
                        new GpuInfo.Type(2, 1, 0xE)),
                8 * GiB, 256L * 1024 * 1024));
    }

    /** Discrete card with Resizable BAR: the whole 8 GB is CPU-writable. */
    private static GpuInfo discreteWithRebar() {
        return device(2, new GpuInfo.Memory(
                List.of(new GpuInfo.Heap(0, 8 * GiB, true)),
                List.of(new GpuInfo.Type(0, 0, 0x1), new GpuInfo.Type(1, 0, 0x7)),
                8 * GiB, 8 * GiB));
    }

    /** APU: one 32 GB unified heap, no separate VRAM. */
    private static GpuInfo apu() {
        return device(1, new GpuInfo.Memory(
                List.of(new GpuInfo.Heap(0, 32 * GiB, true)),
                List.of(new GpuInfo.Type(0, 0, 0x1), new GpuInfo.Type(1, 0, 0x7)),
                32 * GiB, 32 * GiB));
    }

    private static GpuInfo device(int deviceType, GpuInfo.Memory memory) {
        return new GpuInfo(0, "test device", GpuIdentity.VENDOR_ID_AMD, 0x744c, deviceType,
                1 << 22 | 3 << 12, 0x2000000, "Mesa RADV", "RADV", "Mesa 24.0",
                AmdArchitecture.RDNA3, "navi3x",
                new GpuInfo.Limits(32, 0, 0, true, 1024, new int[]{1024, 1024, 64},
                        new int[]{65535, 65535, 65535}, 65536, 8, 128, 4294967295L,
                        16, 128, 16384, 16384, 65536, 2147483647, true, 1.0f, 64, 64, 64),
                memory, List.of("VK_KHR_swapchain"), List.of("VK_EXT_mesh_shader"));
    }

    @Test
    void discreteWithoutRebarCannotBeWrittenDirectly() {
        GpuInfo d = discreteNoRebar();
        assertFalse(d.hasDirectCpuWritableDeviceMemory(), "256 MB BAR window is not enough");
        assertFalse(d.isUnifiedMemory());
    }

    @Test
    void discreteWithRebarCanBeWrittenDirectly() {
        GpuInfo d = discreteWithRebar();
        assertTrue(d.hasDirectCpuWritableDeviceMemory());
        assertFalse(d.isUnifiedMemory(), "a discrete card is never unified memory");
    }

    @Test
    void apuIsUnifiedMemory() {
        GpuInfo d = apu();
        assertTrue(d.hasDirectCpuWritableDeviceMemory());
        assertTrue(d.isUnifiedMemory());
        assertEquals(1, d.deviceType());
        assertEquals("Integrated GPU", d.deviceTypeName());
    }

    @Test
    void jsonIsWellFormed() {
        String json = discreteWithRebar().toJson(2);
        assertEquals(json.chars().filter(c -> c == '{').count(),
                json.chars().filter(c -> c == '}').count(), "unbalanced braces");
        assertEquals(json.chars().filter(c -> c == '[').count(),
                json.chars().filter(c -> c == ']').count(), "unbalanced brackets");
        assertTrue(json.contains("\"architecture\": \"RDNA3\""));
        assertTrue(json.contains("\"directCpuWritableDeviceMemory\": true"));
        assertTrue(json.contains("\"unifiedMemory\": false"));
        assertTrue(json.contains("\"subgroupSize\": 32"));
        assertTrue(json.contains("\"index\": 0, \"size\": 8589934592"));
    }
}
