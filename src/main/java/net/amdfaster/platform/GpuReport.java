package net.amdfaster.platform;

import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkMemoryHeap;
import org.lwjgl.vulkan.VkMemoryType;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceDriverProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupProperties;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_1_0;
import static org.lwjgl.vulkan.VK10.VK_MAKE_VERSION;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumerateInstanceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES;
import static org.lwjgl.vulkan.VK11.vkEnumerateInstanceVersion;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;
import static org.lwjgl.vulkan.VK12.VK_DRIVER_ID_AMD_OPEN_SOURCE;
import static org.lwjgl.vulkan.VK12.VK_DRIVER_ID_AMD_PROPRIETARY;
import static org.lwjgl.vulkan.VK12.VK_DRIVER_ID_ARM_PROPRIETARY;
import static org.lwjgl.vulkan.VK12.VK_DRIVER_ID_BROADCOM_PROPRIETARY;
import static org.lwjgl.vulkan.VK12.VK_DRIVER_ID_GGP_PROPRIETARY;
import static org.lwjgl.vulkan.VK12.VK_DRIVER_ID_GOOGLE_SWIFTSHADER;
import static org.lwjgl.vulkan.VK12.VK_DRIVER_ID_IMAGINATION_PROPRIETARY;
import static org.lwjgl.vulkan.VK12.VK_DRIVER_ID_INTEL_OPEN_SOURCE_MESA;
import static org.lwjgl.vulkan.VK12.VK_DRIVER_ID_INTEL_PROPRIETARY_WINDOWS;
import static org.lwjgl.vulkan.VK12.VK_DRIVER_ID_MESA_LLVMPIPE;
import static org.lwjgl.vulkan.VK12.VK_DRIVER_ID_MESA_RADV;
import static org.lwjgl.vulkan.VK12.VK_DRIVER_ID_MOLTENVK;
import static org.lwjgl.vulkan.VK12.VK_DRIVER_ID_NVIDIA_PROPRIETARY;
import static org.lwjgl.vulkan.VK12.VK_DRIVER_ID_QUALCOMM_PROPRIETARY;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES;

/**
 * Creates a throw-away {@code VkInstance}, asks the loader what is attached to the machine, and
 * turns the answer into a {@link GpuInfo} per adapter.
 *
 * <p>The instance is destroyed before this returns: probing must not keep a second Vulkan instance
 * alive next to Minecraft's OpenGL context for any longer than it takes to read the properties.
 */
public final class GpuReport {

    /**
     * Device extensions whose presence changes what the renderer is allowed to do.
     * Checked by name, so a driver that does not know one simply reports it missing.
     */
    public static final List<String> EXTENSIONS_OF_INTEREST = List.of(
            "VK_KHR_swapchain",
            "VK_KHR_dynamic_rendering",
            "VK_KHR_synchronization2",
            "VK_KHR_timeline_semaphore",
            "VK_KHR_push_descriptor",
            "VK_KHR_maintenance5",
            "VK_KHR_cooperative_matrix",
            "VK_EXT_descriptor_buffer",
            "VK_EXT_shader_object",
            "VK_EXT_mesh_shader",
            "VK_EXT_multi_draw",
            "VK_EXT_index_type_uint8",
            "VK_EXT_extended_dynamic_state3",
            "VK_EXT_subgroup_size_control",
            "VK_EXT_conservative_rasterization",
            "VK_EXT_external_memory_host",
            "VK_EXT_host_image_copy",
            "VK_EXT_calibrated_timestamps",
            "VK_EXT_depth_clip_control",
            "VK_EXT_fragment_shader_barycentric",
            "VK_EXT_transform_feedback",
            "VK_AMD_gcn_shader",
            "VK_AMD_shader_explicit_vertex_parameter",
            "VK_AMD_shader_trinary_minmax",
            "VK_AMD_shader_ballot",
            "VK_AMD_texture_gather_bias_lod"
    );

    private final int instanceApiVersion;
    private final List<GpuInfo> devices;
    private final String failure;

    private GpuReport(int instanceApiVersion, List<GpuInfo> devices, String failure) {
        this.instanceApiVersion = instanceApiVersion;
        this.devices = devices;
        this.failure = failure;
    }

    public List<GpuInfo> devices() {
        return this.devices;
    }

    /** {@code null} when the probe succeeded. */
    public String failure() {
        return this.failure;
    }

    public boolean succeeded() {
        return this.failure == null;
    }

    public int instanceApiVersion() {
        return this.instanceApiVersion;
    }

    /** The adapter AMD-Faster would drive: first AMD device, else the first discrete, else the first. */
    public GpuInfo preferred() {
        if (this.devices.isEmpty()) {
            return null;
        }
        for (GpuInfo d : this.devices) {
            if (d.isAmd() && d.deviceType() == 2) {
                return d;
            }
        }
        for (GpuInfo d : this.devices) {
            if (d.isAmd()) {
                return d;
            }
        }
        for (GpuInfo d : this.devices) {
            if (d.deviceType() == 2) {
                return d;
            }
        }
        return this.devices.get(0);
    }

    // ---------------------------------------------------------------------------------------
    // Probing
    // ---------------------------------------------------------------------------------------

    public static GpuReport probe() {
        int instanceApiVersion = 0;
        try (MemoryStack stack = stackPush()) {
            instanceApiVersion = queryInstanceApiVersion(stack);

            Set<String> instanceExtensions = queryInstanceExtensions();
            Set<String> enabled = new LinkedHashSet<>();
            if (instanceApiVersion < VK_API_VERSION_1_1) {
                enabled.add("VK_KHR_get_physical_device_properties2");
            }
            // Portability enumeration is mandatory on macOS/MoltenVK, harmless elsewhere.
            if (instanceExtensions.contains("VK_KHR_portability_enumeration")) {
                enabled.add("VK_KHR_portability_enumeration");
            }
            enabled.retainAll(instanceExtensions);

            VkInstance instance = createInstance(stack, instanceApiVersion, enabled);
            try {
                boolean properties2 = instanceApiVersion >= VK_API_VERSION_1_1
                        || enabled.contains("VK_KHR_get_physical_device_properties2");
                List<GpuInfo> devices = enumerate(instance, stack, properties2);
                return new GpuReport(instanceApiVersion, List.copyOf(devices), null);
            } finally {
                vkDestroyInstance(instance, null);
            }
        } catch (Throwable t) {
            return new GpuReport(instanceApiVersion, List.of(),
                    t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static int queryInstanceApiVersion(MemoryStack stack) {
        IntBuffer version = stack.mallocInt(1);
        int result = vkEnumerateInstanceVersion(version);
        if (result == VK_SUCCESS) {
            return Math.min(version.get(0), VK_API_VERSION_1_2);
        }
        // A loader old enough to lack vkEnumerateInstanceVersion is a Vulkan 1.0 loader.
        return VK_API_VERSION_1_0;
    }

    private static Set<String> queryInstanceExtensions() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            if (vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, null) != VK_SUCCESS) {
                return Set.of();
            }
            VkExtensionProperties.Buffer buffer = VkExtensionProperties.calloc(count.get(0), stack);
            if (vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, buffer) != VK_SUCCESS) {
                return Set.of();
            }
            Set<String> names = new HashSet<>();
            for (int i = 0; i < buffer.capacity(); i++) {
                names.add(buffer.get(i).extensionNameString());
            }
            return names;
        }
    }

    private static VkInstance createInstance(MemoryStack stack, int apiVersion, Set<String> extensions) {
        VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8Safe("AMD-Faster"))
                .applicationVersion(VK_MAKE_VERSION(0, 1, 0))
                .pEngineName(stack.UTF8Safe("AMD-Faster"))
                .engineVersion(VK_MAKE_VERSION(0, 1, 0))
                .apiVersion(apiVersion);

        VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo);

        if (!extensions.isEmpty()) {
            PointerBuffer names = stack.mallocPointer(extensions.size());
            for (String extension : extensions) {
                names.put(stack.UTF8(extension));
            }
            names.flip();
            createInfo.ppEnabledExtensionNames(names);
        }

        PointerBuffer pInstance = stack.mallocPointer(1);
        int result = vkCreateInstance(createInfo, null, pInstance);
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("vkCreateInstance failed with VkResult " + result);
        }
        return new VkInstance(pInstance.get(0), createInfo);
    }

    private static List<GpuInfo> enumerate(VkInstance instance, MemoryStack stack, boolean properties2Available) {
        IntBuffer count = stack.mallocInt(1);
        int result = vkEnumeratePhysicalDevices(instance, count, null);
        if (result != VK_SUCCESS || count.get(0) == 0) {
            return List.of();
        }

        PointerBuffer handles = stack.mallocPointer(count.get(0));
        vkEnumeratePhysicalDevices(instance, count, handles);

        List<GpuInfo> out = new ArrayList<>(handles.capacity());
        for (int i = 0; i < handles.capacity(); i++) {
            VkPhysicalDevice physicalDevice = new VkPhysicalDevice(handles.get(i), instance);
            out.add(describe(i, physicalDevice, stack, properties2Available));
        }
        return out;
    }

    private static GpuInfo describe(int index, VkPhysicalDevice physicalDevice,
                                    MemoryStack stack, boolean properties2Available) {
        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
        vkGetPhysicalDeviceProperties(physicalDevice, props);

        String deviceName = props.deviceNameString();
        int vendorId = props.vendorID();
        int deviceId = props.deviceID();
        int deviceType = props.deviceType();
        int apiVersion = props.apiVersion();
        int driverVersionRaw = props.driverVersion();

        String driverIdName = "unavailable";
        String driverName = null;
        String driverInfo = null;
        int subgroupSize = 0;
        int subgroupStages = 0;
        int subgroupOperations = 0;
        boolean quadOpsInAllStages = false;

        if (properties2Available) {
            try {
                VkPhysicalDeviceSubgroupProperties subgroup = VkPhysicalDeviceSubgroupProperties.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES);
                VkPhysicalDeviceDriverProperties driver = VkPhysicalDeviceDriverProperties.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES);
                driver.pNext(subgroup.address());

                VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2);
                props2.pNext(driver.address());

                vkGetPhysicalDeviceProperties2(physicalDevice, props2);

                driverIdName = driverIdName(driver.driverID());
                driverName = driver.driverNameString();
                driverInfo = driver.driverInfoString();
                subgroupSize = subgroup.subgroupSize();
                subgroupStages = subgroup.supportedStages();
                subgroupOperations = subgroup.supportedOperations();
                quadOpsInAllStages = subgroup.quadOperationsInAllStages();
            } catch (Throwable t) {
                // A driver that advertises Vulkan 1.1 but cannot fill the chain is not worth
                // failing the whole probe over; the base properties are still reported.
                driverIdName = "query failed (" + t.getClass().getSimpleName() + ")";
            }
        }

        GpuIdentity.Match match = GpuIdentity.classify(deviceName, driverInfo, driverName);

        VkPhysicalDeviceLimits limits = props.limits();
        IntBuffer wgSize = limits.maxComputeWorkGroupSize();
        IntBuffer wgCount = limits.maxComputeWorkGroupCount();

        GpuInfo.Limits parsedLimits = new GpuInfo.Limits(
                subgroupSize,
                subgroupStages,
                subgroupOperations,
                quadOpsInAllStages,
                limits.maxComputeWorkGroupInvocations(),
                new int[]{wgSize.get(0), wgSize.get(1), wgSize.get(2)},
                new int[]{wgCount.get(0), wgCount.get(1), wgCount.get(2)},
                limits.maxComputeSharedMemorySize(),
                limits.maxBoundDescriptorSets(),
                limits.maxPushConstantsSize(),
                limits.maxMemoryAllocationCount(),
                limits.maxPerStageDescriptorStorageBuffers(),
                limits.maxPerStageDescriptorSamplers(),
                limits.maxImageDimension2D(),
                limits.maxImageDimension3D(),
                limits.maxUniformBufferRange(),
                limits.maxStorageBufferRange(),
                limits.timestampComputeAndGraphics(),
                limits.timestampPeriod(),
                limits.nonCoherentAtomSize(),
                limits.minStorageBufferOffsetAlignment(),
                limits.minUniformBufferOffsetAlignment()
        );

        GpuInfo.Memory memory = readMemory(physicalDevice);

        Set<String> supported = queryDeviceExtensions(physicalDevice);
        List<String> present = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String extension : EXTENSIONS_OF_INTEREST) {
            (supported.contains(extension) ? present : missing).add(extension);
        }

        return new GpuInfo(index, deviceName, vendorId, deviceId, deviceType, apiVersion, driverVersionRaw,
                driverIdName, driverName, driverInfo,
                match.architecture(), match.token(),
                parsedLimits, memory, List.copyOf(present), List.copyOf(missing));
    }

    private static GpuInfo.Memory readMemory(VkPhysicalDevice physicalDevice) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memory);

            // The buffers are sized VK_MAX_MEMORY_HEAPS / VK_MAX_MEMORY_TYPES, not the real
            // counts, so the counts have to be honoured or the report fills up with empty slots.
            int heapCount = memory.memoryHeapCount();
            VkMemoryHeap.Buffer heaps = memory.memoryHeaps();
            List<GpuInfo.Heap> heapList = new ArrayList<>(heapCount);
            long largestDeviceLocal = 0;
            for (int i = 0; i < heapCount; i++) {
                VkMemoryHeap heap = heaps.get(i);
                boolean deviceLocal = (heap.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0;
                heapList.add(new GpuInfo.Heap(i, heap.size(), deviceLocal));
                if (deviceLocal) {
                    largestDeviceLocal = Math.max(largestDeviceLocal, heap.size());
                }
            }

            int typeCount = memory.memoryTypeCount();
            VkMemoryType.Buffer types = memory.memoryTypes();
            List<GpuInfo.Type> typeList = new ArrayList<>(typeCount);
            long largestCpuVisibleDeviceLocal = 0;
            for (int i = 0; i < typeCount; i++) {
                VkMemoryType type = types.get(i);
                typeList.add(new GpuInfo.Type(i, type.heapIndex(), type.propertyFlags()));

                boolean cpuVisibleDeviceLocal =
                        (type.propertyFlags() & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0
                                && (type.propertyFlags() & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0;
                if (cpuVisibleDeviceLocal) {
                    largestCpuVisibleDeviceLocal =
                            Math.max(largestCpuVisibleDeviceLocal, heapList.get(type.heapIndex()).size());
                }
            }

            return new GpuInfo.Memory(List.copyOf(heapList), List.copyOf(typeList),
                    largestDeviceLocal, largestCpuVisibleDeviceLocal);
        }
    }

    private static Set<String> queryDeviceExtensions(VkPhysicalDevice physicalDevice) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            if (vkEnumerateDeviceExtensionProperties(physicalDevice, (ByteBuffer) null, count, null) != VK_SUCCESS) {
                return Set.of();
            }
            VkExtensionProperties.Buffer buffer = VkExtensionProperties.calloc(count.get(0), stack);
            if (vkEnumerateDeviceExtensionProperties(physicalDevice, (ByteBuffer) null, count, buffer) != VK_SUCCESS) {
                return Set.of();
            }
            Set<String> names = new HashSet<>();
            for (int i = 0; i < buffer.capacity(); i++) {
                names.add(buffer.get(i).extensionNameString());
            }
            return names;
        }
    }

    private static String driverIdName(int driverId) {
        return switch (driverId) {
            case VK_DRIVER_ID_AMD_PROPRIETARY -> "AMD proprietary (AMDVLK / Windows WDDM)";
            case VK_DRIVER_ID_AMD_OPEN_SOURCE -> "AMD open source (RADV)";
            case VK_DRIVER_ID_MESA_RADV -> "Mesa RADV";
            case VK_DRIVER_ID_NVIDIA_PROPRIETARY -> "NVIDIA proprietary";
            case VK_DRIVER_ID_INTEL_PROPRIETARY_WINDOWS -> "Intel proprietary (Windows)";
            case VK_DRIVER_ID_INTEL_OPEN_SOURCE_MESA -> "Intel open source (ANV)";
            case VK_DRIVER_ID_IMAGINATION_PROPRIETARY -> "Imagination proprietary";
            case VK_DRIVER_ID_QUALCOMM_PROPRIETARY -> "Qualcomm proprietary";
            case VK_DRIVER_ID_ARM_PROPRIETARY -> "ARM proprietary";
            case VK_DRIVER_ID_GOOGLE_SWIFTSHADER -> "SwiftShader (software)";
            case VK_DRIVER_ID_GGP_PROPRIETARY -> "GGP proprietary";
            case VK_DRIVER_ID_BROADCOM_PROPRIETARY -> "Broadcom proprietary";
            case VK_DRIVER_ID_MESA_LLVMPIPE -> "Mesa lavapipe (software)";
            case VK_DRIVER_ID_MOLTENVK -> "MoltenVK";
            default -> "driverId " + driverId;
        };
    }

    // ---------------------------------------------------------------------------------------
    // Output
    // ---------------------------------------------------------------------------------------

    public void logTo(Logger logger) {
        if (!this.succeeded()) {
            logger.warn("Vulkan probe failed: {}", this.failure);
            logger.warn("AMD-Faster will not be able to use its Vulkan backend on this machine.");
            return;
        }

        logger.info("Vulkan instance API version {} - {} adapter(s) found",
                GpuInfo.formatVersion(this.instanceApiVersion), this.devices.size());

        for (GpuInfo d : this.devices) {
            GpuInfo.Limits l = d.limits();
            GpuInfo.Memory m = d.memory();

            logger.info("  [{}] {} ({}, {})", d.index(), d.deviceName(),
                    GpuIdentity.vendorName(d.vendorId()), d.deviceTypeName());
            logger.info("      vendorId 0x{} deviceId 0x{} | api {} | driverVersion 0x{}",
                    Integer.toHexString(d.vendorId()), Integer.toHexString(d.deviceId()),
                    GpuInfo.formatVersion(d.apiVersion()), Integer.toHexString(d.driverVersionRaw()));
            logger.info("      driver: {} | {} | {}", d.driverIdName(),
                    d.driverName() == null ? "?" : d.driverName(),
                    d.driverInfo() == null ? "?" : d.driverInfo());
            logger.info("      architecture: {} (matched on '{}')", d.architecture().displayName(),
                    d.architectureToken() == null ? "nothing" : d.architectureToken());
            logger.info("      subgroup size {} | LDS/SMEM {} | max invocations/workgroup {}",
                    l.subgroupSize(), GpuInfo.formatBytes(l.maxComputeSharedMemorySize()),
                    l.maxComputeWorkGroupInvocations());
            logger.info("      maxComputeWorkGroupSize [{}, {}, {}]",
                    l.maxComputeWorkGroupSize()[0], l.maxComputeWorkGroupSize()[1],
                    l.maxComputeWorkGroupSize()[2]);
            logger.info("      largest DEVICE_LOCAL heap {} | largest CPU-writable DEVICE_LOCAL {}",
                    GpuInfo.formatBytes(m.largestDeviceLocalBytes()),
                    GpuInfo.formatBytes(m.largestCpuVisibleDeviceLocalBytes()));
            logger.info("      direct CPU-writable device memory: {} (unified memory: {})",
                    d.hasDirectCpuWritableDeviceMemory() ? "yes" : "no",
                    d.isUnifiedMemory() ? "yes" : "no");
            logger.info("      {} of {} tracked extensions present; missing: {}",
                    d.extensionsPresent().size(), EXTENSIONS_OF_INTEREST.size(),
                    d.extensionsMissing().isEmpty() ? "none" : String.join(", ", d.extensionsMissing()));

            AmdArchitecture arch = d.architecture();
            if (arch != AmdArchitecture.UNKNOWN) {
                logger.info("      tuning: {}", arch.notes());
            } else {
                logger.info("      tuning: architecture not recognised - using driver-reported values only");
            }
        }

        GpuInfo preferred = this.preferred();
        if (preferred != null) {
            logger.info("  AMD-Faster would drive: [{}] {}", preferred.index(), preferred.deviceName());
        }
    }

    public Path writeToFile() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("amdfaster").resolve("gpu-report.json");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, toJson());
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return file;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n");
        sb.append("  \"instanceApiVersion\": \"").append(GpuInfo.formatVersion(this.instanceApiVersion)).append("\",\n");
        if (this.failure != null) {
            sb.append("  \"error\": ").append(jsonString(this.failure)).append(",\n");
        }
        sb.append("  \"devices\": [\n");
        for (int i = 0; i < this.devices.size(); i++) {
            sb.append(this.devices.get(i).toJson(4));
            if (i < this.devices.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
