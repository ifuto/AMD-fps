package dev.amdfaster.core.arch;

import java.util.Locale;

/** Graphics hardware vendor, inferred from the API's vendor/renderer strings. */
public enum GpuVendor {
    AMD("Advanced Micro Devices, Inc.", 0x1002),
    NVIDIA("NVIDIA Corporation", 0x10DE),
    INTEL("Intel Corporation", 0x8086),
    APPLE("Apple", 0x106B),
    QUALCOMM("Qualcomm", 0x5143),
    ARM("ARM", 0x13B5),
    /** Software rasteriser (llvmpipe, SwiftShader, WARP, ...). */
    SOFTWARE("Software", -1),
    UNKNOWN("Unknown", -1);

    private final String glVendorString;
    private final int pciVendorId;

    GpuVendor(String glVendorString, int pciVendorId) {
        this.glVendorString = glVendorString;
        this.pciVendorId = pciVendorId;
    }

    public String glVendorString() {
        return glVendorString;
    }

    public int pciVendorId() {
        return pciVendorId;
    }

    /**
     * Classifies a device from the OpenGL {@code GL_VENDOR}/{@code GL_RENDERER} pair or the Vulkan
     * {@code VkPhysicalDeviceProperties.vendorID}/{@code deviceName} pair.
     *
     * @param vendorString  GL_VENDOR, or {@code null} when only Vulkan data is available
     * @param rendererString GL_RENDERER / deviceName, or {@code null}
     * @param vulkanVendorId PCI vendor id from Vulkan, or {@code -1}
     */
    public static GpuVendor classify(String vendorString, String rendererString, int vulkanVendorId) {
        if (vulkanVendorId > 0) {
            for (GpuVendor v : values()) {
                if (v.pciVendorId == vulkanVendorId) {
                    return v;
                }
            }
        }
        String haystack = ((vendorString == null ? "" : vendorString) + ' '
                + (rendererString == null ? "" : rendererString)).toLowerCase(Locale.ROOT);
        if (haystack.isBlank()) {
            return UNKNOWN;
        }
        // Software rasterisers must be detected before the vendor check, because llvmpipe and
        // Mesa's softpipe report "Mesa" / "Intel" style vendor strings on some hosts.
        if (haystack.contains("llvmpipe") || haystack.contains("softpipe") || haystack.contains("swiftshader")
                || haystack.contains("warp") || haystack.contains("software rasterizer")
                || haystack.contains("mesa offscreen") || haystack.contains("zink")) {
            return SOFTWARE;
        }
        if (haystack.contains("advanced micro devices") || haystack.contains("amd") || haystack.contains("ati ")
                || haystack.contains("radeon") || haystack.contains("gpuopen")) {
            return AMD;
        }
        if (haystack.contains("nvidia") || haystack.contains("geforce") || haystack.contains("quadro")
                || haystack.contains("rtx") || haystack.contains("gtx")) {
            return NVIDIA;
        }
        if (haystack.contains("intel") || haystack.contains("iris") || haystack.contains("arc graphics")) {
            return INTEL;
        }
        if (haystack.contains("apple")) {
            return APPLE;
        }
        if (haystack.contains("qualcomm") || haystack.contains("adreno")) {
            return QUALCOMM;
        }
        if (haystack.contains("arm") || haystack.contains("mali")) {
            return ARM;
        }
        return UNKNOWN;
    }
}
