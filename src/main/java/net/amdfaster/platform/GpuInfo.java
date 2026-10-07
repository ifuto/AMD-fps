package net.amdfaster.platform;

import java.util.List;

/**
 * Everything AMD-Faster needs to know about one Vulkan physical device.
 *
 * <p>Values are captured once at startup from the driver and never re-queried; the renderer
 * treats this as immutable hardware truth.
 */
public record GpuInfo(
        int index,
        String deviceName,
        int vendorId,
        int deviceId,
        int deviceType,
        int apiVersion,
        int driverVersionRaw,
        String driverIdName,
        String driverName,
        String driverInfo,
        AmdArchitecture architecture,
        String architectureToken,
        Limits limits,
        Memory memory,
        List<String> extensionsPresent,
        List<String> extensionsMissing
) {

    /** @param maxComputeWorkGroupSize the driver's per-dimension limit, {x, y, z}. */
    public record Limits(
            int subgroupSize,
            int subgroupSupportedStages,
            int subgroupSupportedOperations,
            boolean quadOperationsInAllStages,
            int maxComputeWorkGroupInvocations,
            int[] maxComputeWorkGroupSize,
            int[] maxComputeWorkGroupCount,
            int maxComputeSharedMemorySize,
            int maxBoundDescriptorSets,
            int maxPushConstantsSize,
            long maxMemoryAllocationCount,
            int maxPerStageDescriptorStorageBuffers,
            int maxPerStageDescriptorSamplers,
            int maxImageDimension2D,
            int maxImageDimension3D,
            int maxUniformBufferRange,
            int maxStorageBufferRange,
            boolean timestampComputeAndGraphics,
            float timestampPeriod,
            long nonCoherentAtomSize,
            long minStorageBufferOffsetAlignment,
            long minUniformBufferOffsetAlignment
    ) {
    }

    /** @param size bytes. @param deviceLocal whether the heap carries {@code DEVICE_LOCAL}. */
    public record Heap(int index, long size, boolean deviceLocal) {
    }

    /** @param propertyFlags the raw {@code VkMemoryPropertyFlags} bitfield. */
    public record Type(int index, int heapIndex, int propertyFlags) {
    }

    public record Memory(
            List<Heap> heaps,
            List<Type> types,
            long largestDeviceLocalBytes,
            long largestCpuVisibleDeviceLocalBytes
    ) {
    }

    public boolean isAmd() {
        return this.vendorId == GpuIdentity.VENDOR_ID_AMD;
    }

    public String deviceTypeName() {
        return switch (this.deviceType) {
            case 1 -> "Integrated GPU";
            case 2 -> "Discrete GPU";
            case 3 -> "Virtual GPU";
            case 4 -> "CPU (software)";
            default -> "Other";
        };
    }

    /**
     * True when the driver exposes a {@code DEVICE_LOCAL | HOST_VISIBLE} memory type backed by a
     * heap large enough to hold whole chunk meshes.
     *
     * <p>On a discrete card this means Resizable BAR is enabled; on an APU it means unified memory
     * (Vega 8 / 680M / 780M parts have no VRAM of their own at all). Either way AMD-Faster can map
     * a persistent buffer and write chunk geometry straight into device memory, skipping the
     * staging copy that an OpenGL driver would force.
     */
    public boolean hasDirectCpuWritableDeviceMemory() {
        return this.memory.largestCpuVisibleDeviceLocalBytes() >= 512L * 1024 * 1024;
    }

    public boolean isUnifiedMemory() {
        // An APU reports essentially the whole system RAM as a single DEVICE_LOCAL heap and has no
        // separate large "VRAM only" heap behind it.
        return this.deviceType == 1 && this.hasDirectCpuWritableDeviceMemory()
                && this.memory.largestCpuVisibleDeviceLocalBytes() == this.memory.largestDeviceLocalBytes();
    }

    public static String formatBytes(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        final String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        double v = bytes;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return (u == 0 ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.2f", v))
                + " " + units[u];
    }

    public static String formatVersion(int version) {
        return (version >> 22) + "." + ((version >> 12) & 0x3FF) + "." + (version & 0xFFF);
    }

    /** Renders this adapter as a JSON object; hand-rolled to avoid a Gson dependency. */
    public String toJson(int pad) {
        String i = " ".repeat(pad);
        String i2 = i + "  ";
        String i3 = i + "    ";

        StringBuilder sb = new StringBuilder(1024);
        sb.append(i).append("{\n");
        sb.append(i2).append("\"index\": ").append(this.index).append(",\n");
        sb.append(i2).append("\"deviceName\": ").append(GpuReport.jsonString(this.deviceName)).append(",\n");
        sb.append(i2).append("\"vendor\": ").append(GpuReport.jsonString(GpuIdentity.vendorName(this.vendorId))).append(",\n");
        sb.append(i2).append("\"vendorId\": \"0x").append(Integer.toHexString(this.vendorId)).append("\",\n");
        sb.append(i2).append("\"deviceId\": \"0x").append(Integer.toHexString(this.deviceId)).append("\",\n");
        sb.append(i2).append("\"deviceType\": ").append(GpuReport.jsonString(this.deviceTypeName())).append(",\n");
        sb.append(i2).append("\"apiVersion\": \"").append(formatVersion(this.apiVersion)).append("\",\n");
        sb.append(i2).append("\"driverVersion\": \"0x").append(Integer.toHexString(this.driverVersionRaw)).append("\",\n");
        sb.append(i2).append("\"driverId\": ").append(GpuReport.jsonString(this.driverIdName)).append(",\n");
        sb.append(i2).append("\"driverName\": ").append(GpuReport.jsonString(this.driverName)).append(",\n");
        sb.append(i2).append("\"driverInfo\": ").append(GpuReport.jsonString(this.driverInfo)).append(",\n");
        sb.append(i2).append("\"architecture\": \"").append(this.architecture.name()).append("\",\n");
        sb.append(i2).append("\"architectureDisplay\": ")
                .append(GpuReport.jsonString(this.architecture.displayName())).append(",\n");
        sb.append(i2).append("\"architectureMatchedOn\": ").append(GpuReport.jsonString(this.architectureToken)).append(",\n");

        sb.append(i2).append("\"tuning\": {\n");
        sb.append(i3).append("\"nativeWaveSize\": ").append(this.architecture.nativeWaveSize()).append(",\n");
        sb.append(i3).append("\"recommendedWorkgroupSize\": ").append(this.architecture.recommendedWorkgroupSize()).append(",\n");
        sb.append(i3).append("\"ldsBytesPerCu\": ").append(this.architecture.ldsBytesPerCu()).append(",\n");
        sb.append(i3).append("\"notes\": ").append(GpuReport.jsonString(this.architecture.notes())).append("\n");
        sb.append(i2).append("},\n");

        sb.append(i2).append("\"limits\": {\n");
        sb.append(i3).append("\"subgroupSize\": ").append(this.limits.subgroupSize()).append(",\n");
        sb.append(i3).append("\"subgroupSupportedStages\": ").append(this.limits.subgroupSupportedStages()).append(",\n");
        sb.append(i3).append("\"subgroupSupportedOperations\": ").append(this.limits.subgroupSupportedOperations()).append(",\n");
        sb.append(i3).append("\"quadOperationsInAllStages\": ").append(this.limits.quadOperationsInAllStages()).append(",\n");
        sb.append(i3).append("\"maxComputeWorkGroupInvocations\": ").append(this.limits.maxComputeWorkGroupInvocations()).append(",\n");
        sb.append(i3).append("\"maxComputeWorkGroupSize\": ").append(intArray(this.limits.maxComputeWorkGroupSize())).append(",\n");
        sb.append(i3).append("\"maxComputeWorkGroupCount\": ").append(intArray(this.limits.maxComputeWorkGroupCount())).append(",\n");
        sb.append(i3).append("\"maxComputeSharedMemorySize\": ").append(this.limits.maxComputeSharedMemorySize()).append(",\n");
        sb.append(i3).append("\"maxBoundDescriptorSets\": ").append(this.limits.maxBoundDescriptorSets()).append(",\n");
        sb.append(i3).append("\"maxPushConstantsSize\": ").append(this.limits.maxPushConstantsSize()).append(",\n");
        sb.append(i3).append("\"maxMemoryAllocationCount\": ").append(this.limits.maxMemoryAllocationCount()).append(",\n");
        sb.append(i3).append("\"maxPerStageDescriptorStorageBuffers\": ").append(this.limits.maxPerStageDescriptorStorageBuffers()).append(",\n");
        sb.append(i3).append("\"maxPerStageDescriptorSamplers\": ").append(this.limits.maxPerStageDescriptorSamplers()).append(",\n");
        sb.append(i3).append("\"maxImageDimension2D\": ").append(this.limits.maxImageDimension2D()).append(",\n");
        sb.append(i3).append("\"maxImageDimension3D\": ").append(this.limits.maxImageDimension3D()).append(",\n");
        sb.append(i3).append("\"maxUniformBufferRange\": ").append(this.limits.maxUniformBufferRange()).append(",\n");
        sb.append(i3).append("\"maxStorageBufferRange\": ").append(this.limits.maxStorageBufferRange()).append(",\n");
        sb.append(i3).append("\"timestampComputeAndGraphics\": ").append(this.limits.timestampComputeAndGraphics()).append(",\n");
        sb.append(i3).append("\"timestampPeriod\": ").append(this.limits.timestampPeriod()).append(",\n");
        sb.append(i3).append("\"nonCoherentAtomSize\": ").append(this.limits.nonCoherentAtomSize()).append(",\n");
        sb.append(i3).append("\"minStorageBufferOffsetAlignment\": ").append(this.limits.minStorageBufferOffsetAlignment()).append(",\n");
        sb.append(i3).append("\"minUniformBufferOffsetAlignment\": ").append(this.limits.minUniformBufferOffsetAlignment()).append("\n");
        sb.append(i2).append("},\n");

        sb.append(i2).append("\"memory\": {\n");
        sb.append(i3).append("\"heaps\": [\n");
        for (int h = 0; h < this.memory.heaps().size(); h++) {
            Heap heap = this.memory.heaps().get(h);
            sb.append(i3).append("  {\"index\": ").append(heap.index())
                    .append(", \"size\": ").append(heap.size())
                    .append(", \"sizeHuman\": ").append(GpuReport.jsonString(formatBytes(heap.size())))
                    .append(", \"deviceLocal\": ").append(heap.deviceLocal()).append('}');
            if (h < this.memory.heaps().size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(i3).append("],\n");
        sb.append(i3).append("\"types\": [\n");
        for (int t = 0; t < this.memory.types().size(); t++) {
            Type type = this.memory.types().get(t);
            sb.append(i3).append("  {\"index\": ").append(type.index())
                    .append(", \"heapIndex\": ").append(type.heapIndex())
                    .append(", \"propertyFlags\": \"0x").append(Integer.toHexString(type.propertyFlags())).append("\"}");
            if (t < this.memory.types().size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(i3).append("],\n");
        sb.append(i3).append("\"largestDeviceLocalBytes\": ").append(this.memory.largestDeviceLocalBytes()).append(",\n");
        sb.append(i3).append("\"largestCpuVisibleDeviceLocalBytes\": ")
                .append(this.memory.largestCpuVisibleDeviceLocalBytes()).append(",\n");
        sb.append(i3).append("\"directCpuWritableDeviceMemory\": ").append(this.hasDirectCpuWritableDeviceMemory()).append(",\n");
        sb.append(i3).append("\"unifiedMemory\": ").append(this.isUnifiedMemory()).append("\n");
        sb.append(i2).append("},\n");

        sb.append(i2).append("\"extensionsPresent\": ").append(stringList(this.extensionsPresent)).append(",\n");
        sb.append(i2).append("\"extensionsMissing\": ").append(stringList(this.extensionsMissing)).append("\n");
        sb.append(i).append('}');
        return sb.toString();
    }

    private static String intArray(int[] values) {
        StringBuilder sb = new StringBuilder(24);
        sb.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }

    private static String stringList(List<String> values) {
        StringBuilder sb = new StringBuilder(64);
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(GpuReport.jsonString(values.get(i)));
        }
        return sb.append(']').toString();
    }
}
