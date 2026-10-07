package dev.amdfaster.core.arch;

/**
 * A detected graphics device, as far as AMD-Faster could determine it.
 *
 * @param vendor           hardware vendor
 * @param name             raw device name as reported by the driver ({@code GL_RENDERER} or
 *                         {@code VkPhysicalDeviceProperties.deviceName})
 * @param arch             AMD generation, or {@link AmdArch#UNKNOWN} for non-AMD parts
 * @param confidence       how sure we are about {@code arch} (0.0 - 1.0). Anything below
 *                         {@link #CONFIDENT} only enables conservative tuning.
 * @param deviceId         PCI device id, or {@code -1} when the driver did not expose it
 * @param driverVersion    driver/API description string
 * @param isIntegratedGpu  true for APUs/iGPUs sharing system memory with the CPU
 * @param dedicatedVideoMemoryMiB dedicated VRAM in MiB, or {@code -1} when unknown
 * @param barSizeMiB       size of the PCIe BAR window in MiB (256 without ReBAR, up to VRAM with it)
 */
public record GpuIdentity(
        GpuVendor vendor,
        String name,
        AmdArch arch,
        double confidence,
        int deviceId,
        String driverVersion,
        boolean isIntegratedGpu,
        int dedicatedVideoMemoryMiB,
        int barSizeMiB) {

    /** Confidence threshold above which a classification may be used for aggressive tuning. */
    public static final double CONFIDENT = 0.75;

    public GpuIdentity {
        if (name == null || name.isBlank()) {
            name = "unknown device";
        }
        if (arch == null) {
            arch = AmdArch.UNKNOWN;
        }
        if (driverVersion == null) {
            driverVersion = "unknown";
        }
    }

    public boolean isAmd() {
        return vendor == GpuVendor.AMD;
    }

    /** True when the architecture is known well enough to drive architecture specific tuning. */
    public boolean isArchConfident() {
        return vendor == GpuVendor.AMD && arch != AmdArch.UNKNOWN && confidence >= CONFIDENT;
    }

    /** True when the PCIe BAR covers the whole frame buffer, i.e. Resizable BAR / AMD Smart Access Memory. */
    public boolean hasResizableBar(int dedicatedVramMiB) {
        int vram = dedicatedVideoMemoryMiB > 0 ? dedicatedVideoMemoryMiB : dedicatedVramMiB;
        return barSizeMiB > 0 && vram > 0 && barSizeMiB >= vram * 9 / 10;
    }

    public GpuIdentity withDriver(String driverVersion) {
        return new GpuIdentity(vendor, name, arch, confidence, deviceId, driverVersion,
                isIntegratedGpu, dedicatedVideoMemoryMiB, barSizeMiB);
    }

    /** One-line summary for logs / F3 HUD / issue reports. */
    public String describe() {
        return name + " (" + vendor + (isAmd() ? ", " + arch.describe() : "")
                + ", confidence " + Math.round(confidence * 100) + "%)";
    }
}
