package dev.amdfaster.client;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDescriptorIndexing.VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTSubgroupSizeControl.VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDrawIndirectCount.VK_KHR_DRAW_INDIRECT_COUNT_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRTimelineSemaphore.VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MAJOR;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MINOR;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;

/**
 * Asks Vulkan what the machine really has.
 *
 * <p>OpenGL cannot reliably answer two questions that matter for tuning: which PCI device this is
 * (so an integrated "Radeon Graphics" can be told apart from a 780M) and how much memory the driver
 * exposes, including whether Resizable BAR is active. Vulkan answers both, and it answers them
 * without creating a device: an instance, the physical device survey, and then the instance is
 * destroyed again. Nothing is left allocated, which matters because Minecraft keeps its own GL
 * context alive while this runs.</p>
 *
 * <p>Failure is expected and normal (no Vulkan loader, a blacklisted driver, a headless session),
 * so every failure mode maps to {@link Optional#empty()}.</p>
 */
public final class VkDeviceProbe {

    /** PCI vendor id of AMD/ATI. */
    private static final int AMD_VENDOR_ID = 0x1002;

    private VkDeviceProbe() {
    }

    /**
     * Everything Vulkan revealed about the AMD device, if there is one.
     *
     * @param deviceName              {@code VkPhysicalDeviceProperties.deviceName}
     * @param deviceId                PCI device id
     * @param driverVersion           packed driver version, formatted
     * @param apiVersionMajor         Vulkan major version the device supports
     * @param apiVersionMinor         Vulkan minor version
     * @param integrated              the device reports itself as integrated
     * @param dedicatedVideoMemoryMiB size of the DEVICE_LOCAL heaps
     * @param hostVisibleDeviceMiB    size of the DEVICE_LOCAL | HOST_VISIBLE heaps: the usable part
     *                                of the BAR window, which is what Resizable BAR changes
     * @param meshShader              {@code VK_EXT_mesh_shader}
     * @param descriptorIndexing      {@code VK_EXT_descriptor_indexing}
     * @param dynamicRendering        {@code VK_KHR_dynamic_rendering}
     * @param drawIndirectCount       {@code VK_KHR_draw_indirect_count}
     * @param subgroupSizeControl     {@code VK_EXT_subgroup_size_control}
     * @param timelineSemaphore       {@code VK_KHR_timeline_semaphore}
     * @param synchronization2        {@code VK_KHR_synchronization2}
     */
    public record Facts(String deviceName, int deviceId, String driverVersion, int apiVersionMajor,
                        int apiVersionMinor, boolean integrated, int dedicatedVideoMemoryMiB,
                        int hostVisibleDeviceMiB, boolean meshShader, boolean descriptorIndexing,
                        boolean dynamicRendering, boolean drawIndirectCount, boolean subgroupSizeControl,
                        boolean timelineSemaphore, boolean synchronization2) {
    }

    /** Surveys the physical devices and returns the first AMD one. */
    public static Optional<Facts> probe() {
        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationName(stack.UTF8("AMD-Faster device probe"))
                    .applicationVersion(1)
                    .pEngineName(stack.UTF8("AMD-Faster"))
                    .engineVersion(1)
                    .apiVersion(org.lwjgl.vulkan.VK10.VK_API_VERSION_1_0);
            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationInfo(appInfo);

            PointerBuffer instanceHandle = stack.mallocPointer(1);
            if (vkCreateInstance(createInfo, null, instanceHandle) != VK_SUCCESS) {
                return Optional.empty();
            }
            VkInstance instance = new VkInstance(instanceHandle.get(0), createInfo);
            try {
                return survey(instance, stack);
            } finally {
                vkDestroyInstance(instance, null);
            }
        } catch (Throwable t) {
            // No loader, no device, no permission - all the same to us.
            return Optional.empty();
        }
    }

    private static Optional<Facts> survey(VkInstance instance, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        if (vkEnumeratePhysicalDevices(instance, count, null) != VK_SUCCESS || count.get(0) == 0) {
            return Optional.empty();
        }
        PointerBuffer devices = stack.mallocPointer(count.get(0));
        if (vkEnumeratePhysicalDevices(instance, count, devices) != VK_SUCCESS) {
            return Optional.empty();
        }

        for (int i = 0; i < devices.capacity(); i++) {
            VkPhysicalDevice device = new VkPhysicalDevice(devices.get(i), instance);
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(device, properties);
            if (properties.vendorID() != AMD_VENDOR_ID) {
                continue;
            }

            VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(device, memory);

            long dedicatedBytes = 0;
            long hostVisibleDeviceBytes = 0;
            Set<Integer> deviceLocalTypes = new HashSet<>();
            for (int type = 0; type < memory.memoryTypeCount(); type++) {
                if ((memory.memoryTypes(type).propertyFlags()
                        & org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
                    deviceLocalTypes.add(type);
                }
            }
            for (int heap = 0; heap < memory.memoryHeapCount(); heap++) {
                boolean deviceLocal = false;
                for (int type = 0; type < memory.memoryTypeCount(); type++) {
                    if (memory.memoryTypes(type).heapIndex() == heap && deviceLocalTypes.contains(type)) {
                        deviceLocal = true;
                        break;
                    }
                }
                if (!deviceLocal) {
                    continue;
                }
                long size = memory.memoryHeaps(heap).size();
                dedicatedBytes += size;
                boolean hostVisibleHeap = false;
                for (int type = 0; type < memory.memoryTypeCount(); type++) {
                    if (memory.memoryTypes(type).heapIndex() == heap
                            && (memory.memoryTypes(type).propertyFlags()
                            & org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0) {
                        hostVisibleHeap = true;
                        break;
                    }
                }
                if (hostVisibleHeap) {
                    hostVisibleDeviceBytes += size;
                }
            }

            Set<String> extensions = deviceExtensions(device, stack);
            return Optional.of(new Facts(
                    properties.deviceNameString(),
                    properties.deviceID(),
                    formatDriverVersion(properties.driverVersion()),
                    VK_API_VERSION_MAJOR(properties.apiVersion()),
                    VK_API_VERSION_MINOR(properties.apiVersion()),
                    properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU,
                    toMiB(dedicatedBytes),
                    toMiB(hostVisibleDeviceBytes),
                    extensions.contains(VK_EXT_MESH_SHADER_EXTENSION_NAME),
                    extensions.contains(VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME),
                    extensions.contains(VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME),
                    extensions.contains(VK_KHR_DRAW_INDIRECT_COUNT_EXTENSION_NAME),
                    extensions.contains(VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME),
                    extensions.contains(VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME),
                    extensions.contains(VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME)));
        }
        return Optional.empty();
    }

    private static Set<String> deviceExtensions(VkPhysicalDevice device, MemoryStack stack) {
        Set<String> names = new HashSet<>();
        IntBuffer count = stack.mallocInt(1);
        if (vkEnumerateDeviceExtensionProperties(device, (String) null, count, null) != VK_SUCCESS) {
            return names;
        }
        org.lwjgl.vulkan.VkExtensionProperties.Buffer properties =
                org.lwjgl.vulkan.VkExtensionProperties.malloc(count.get(0), stack);
        if (vkEnumerateDeviceExtensionProperties(device, (String) null, count, properties) != VK_SUCCESS) {
            return names;
        }
        for (int i = 0; i < properties.capacity(); i++) {
            names.add(properties.get(i).extensionNameString());
        }
        return names;
    }

    private static int toMiB(long bytes) {
        return (int) Math.min(Integer.MAX_VALUE, bytes / (1024L * 1024L));
    }

    /**
     * Formats a packed Vulkan driver version.
     *
     * <p>The packing differs per vendor; for AMD this matches how the Radeon driver reports itself
     * (major.minor.patch), which is what a user recognises from the Radeon Software panel.</p>
     */
    private static String formatDriverVersion(int packed) {
        int major = (packed >> 22) & 0x3FF;
        int minor = (packed >> 12) & 0x3FF;
        int patch = packed & 0xFFF;
        return major + "." + minor + "." + patch;
    }
}
