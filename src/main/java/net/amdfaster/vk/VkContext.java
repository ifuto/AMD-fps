package net.amdfaster.vk;

import net.amdfaster.platform.GpuIdentity;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkMemoryHeap;
import org.lwjgl.vulkan.VkMemoryType;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memUTF8;
import static org.lwjgl.vulkan.KHRSurface.VK_KHR_SURFACE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_1_0;
import static org.lwjgl.vulkan.VK10.VK_MAKE_VERSION;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_TRUE;
import static org.lwjgl.vulkan.VK10.vkCreateDevice;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkDestroyDevice;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkGetDeviceQueue;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFeatures;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.VK11.vkEnumerateInstanceVersion;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/**
 * The instance, the physical device, the logical device and the queues -- everything the renderer
 * needs before it can create a single buffer.
 *
 * <p>Every choice in here is delegated to the small pure classes in this package, which is the
 * point: {@link DeviceScorer} decides which adapter, {@link QueueSelector} which families,
 * {@link MemoryTypeSelector} which heap a buffer binds to. This class only makes the calls and
 * translates the answers, so the decisions can be tested without a GPU.
 *
 * <p>Nothing here may throw into Minecraft. {@link #create} returns {@code null} and fills in
 * {@link #failure()} when Vulkan is unavailable, so the mod stays inert and the game keeps
 * rendering through its own pipeline.
 */
public final class VkContext implements AutoCloseable {

    /** Device extensions AMD-Faster asks for, in priority order. Absent ones are skipped. */
    public static final List<String> WANTED_DEVICE_EXTENSIONS = List.of(
            VK_KHR_SWAPCHAIN_EXTENSION_NAME,
            "VK_KHR_synchronization2",
            "VK_KHR_dynamic_rendering",
            "VK_EXT_index_type_uint8",
            "VK_EXT_descriptor_buffer",
            "VK_KHR_push_descriptor",
            "VK_EXT_shader_object",
            "VK_EXT_mesh_shader");

    /** Without these the renderer cannot work at all. */
    public static final List<String> REQUIRED_DEVICE_EXTENSIONS = List.of(
            VK_KHR_SWAPCHAIN_EXTENSION_NAME);

    private final VkInstance instance;
    private final long surface;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final VkQueue graphicsQueue;
    private final VkQueue computeQueue;
    private final VkQueue hostTransferQueue;
    private final DeviceScorer.Ranked chosen;
    private final QueueSelector.QueueSelection queues;
    private final List<MemoryTypeSelector.MemoryType> memoryTypes;
    private final Set<String> enabledExtensions;
    private final String failure;
    private boolean closed;

    private VkContext(VkInstance instance, long surface, VkPhysicalDevice physicalDevice, VkDevice device,
                      VkQueue graphicsQueue, VkQueue computeQueue, VkQueue hostTransferQueue,
                      DeviceScorer.Ranked chosen, QueueSelector.QueueSelection queues,
                      List<MemoryTypeSelector.MemoryType> memoryTypes, Set<String> enabledExtensions,
                      String failure) {
        this.instance = instance;
        this.surface = surface;
        this.physicalDevice = physicalDevice;
        this.device = device;
        this.graphicsQueue = graphicsQueue;
        this.computeQueue = computeQueue;
        this.hostTransferQueue = hostTransferQueue;
        this.chosen = chosen;
        this.queues = queues;
        this.memoryTypes = memoryTypes;
        this.enabledExtensions = enabledExtensions;
        this.failure = failure;
    }

    /** A context that represents a failure to initialise, carrying the reason. */
    public static VkContext failed(String reason) {
        return new VkContext(null, 0L, null, null, null, null, null, null, null, List.of(), Set.of(), reason);
    }

    /**
     * @param glfwWindow the GLFW window handle to present to; Minecraft's own window works
     * @return a live context, or one whose {@link #failure()} explains why there is none
     */
    public static VkContext create(long glfwWindow) {
        try {
            return createInner(glfwWindow);
        } catch (Throwable t) {
            // Deliberately broad: a driver that throws instead of returning an error code must not
            // take Minecraft down with it.
            return failed("Vulkan initialisation failed: " + t);
        }
    }

    private static VkContext createInner(long glfwWindow) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer instanceExtensions = glfwGetRequiredInstanceExtensions();
            if (instanceExtensions == null) {
                return failed("glfwGetRequiredInstanceExtensions returned null");
            }
            List<String> wantedInstance = new ArrayList<>(instanceExtensions.capacity() + 1);
            for (int i = 0; i < instanceExtensions.capacity(); i++) {
                wantedInstance.add(memUTF8(instanceExtensions.get(i)));
            }
            if (!wantedInstance.contains(VK_KHR_SURFACE_EXTENSION_NAME)) {
                wantedInstance.add(VK_KHR_SURFACE_EXTENSION_NAME);
            }

            VkInstance instance = createInstance(stack, wantedInstance, instanceApiVersion(stack));
            long surface = 0L;
            VkDevice device = null;
            try {
                // Signature per lwjgl3's generated source, modules/lwjgl/glfw/.../GLFWVulkan.java:
                // the instance is the VkInstance wrapper, and the surface out parameter is a
                // LongBuffer. Both halves of this were guessed wrong before, and the only compiler
                // available is CI, so it is now written down rather than remembered.
                LongBuffer pSurface = stack.mallocLong(1);
                if (glfwCreateWindowSurface(instance, glfwWindow, null, pSurface) != VK_SUCCESS) {
                    return failed("glfwCreateWindowSurface failed");
                }
                surface = pSurface.get(0);

                List<VkPhysicalDevice> gpus = enumerateGpus(instance, stack);
                if (gpus.isEmpty()) {
                    return failed("no Vulkan physical devices");
                }

                Adapter best = chooseAdapter(gpus, surface, stack);
                if (best == null) {
                    return failed("no adapter exposes a graphics queue family that can present");
                }

                QueueSelector.QueueSelection queues = best.queues();
                Set<String> supported = deviceExtensions(best.gpu(), stack);
                List<String> missingRequired = new ArrayList<>();
                for (String e : REQUIRED_DEVICE_EXTENSIONS) {
                    if (!supported.contains(e)) {
                        missingRequired.add(e);
                    }
                }
                if (!missingRequired.isEmpty()) {
                    return failed("adapter " + best.ranked().candidate().deviceName()
                            + " lacks required extensions " + missingRequired);
                }
                List<String> enabled = new ArrayList<>();
                for (String e : WANTED_DEVICE_EXTENSIONS) {
                    if (supported.contains(e)) {
                        enabled.add(e);
                    }
                }

                device = createDevice(best.gpu(), queues, enabled, stack);
                List<MemoryTypeSelector.MemoryType> memoryTypes = readMemoryTypes(best.gpu(), stack);

                return new VkContext(instance, surface, best.gpu(), device,
                        queue(device, queues.graphics()), queue(device, queues.compute()),
                        queue(device, queues.hostTransfer()), best.ranked(), queues, memoryTypes,
                        Set.copyOf(enabled), null);
            } catch (RuntimeException e) {
                if (device != null) {
                    vkDeviceWaitIdle(device);
                    vkDestroyDevice(device, null);
                }
                if (surface != 0L) {
                    vkDestroySurfaceKHR(instance, surface, null);
                }
                vkDestroyInstance(instance, null);
                throw e;
            }
        }
    }

    /** The newest version this loader implements, capped at 1.2 -- which is all we need. */
    private static int instanceApiVersion(MemoryStack stack) {
        IntBuffer version = stack.mallocInt(1);
        if (vkEnumerateInstanceVersion(version) != VK_SUCCESS) {
            return VK_API_VERSION_1_0;
        }
        return Math.min(version.get(0), VK_API_VERSION_1_2);
    }

    private static VkInstance createInstance(MemoryStack stack, List<String> extensions, int apiVersion) {
        PointerBuffer names = stack.mallocPointer(extensions.size());
        for (String e : extensions) {
            names.put(stack.UTF8(e));
        }
        names.flip();

        VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8Safe("AMD-Faster"))
                .applicationVersion(VK_MAKE_VERSION(0, 1, 0))
                .pEngineName(stack.UTF8Safe("AMD-Faster"))
                .engineVersion(VK_MAKE_VERSION(0, 1, 0))
                .apiVersion(apiVersion);

        VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo)
                .ppEnabledExtensionNames(names);

        PointerBuffer pInstance = stack.mallocPointer(1);
        int result = vkCreateInstance(ci, null, pInstance);
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("vkCreateInstance failed with VkResult " + result);
        }
        return new VkInstance(pInstance.get(0), ci);
    }

    private static List<VkPhysicalDevice> enumerateGpus(VkInstance instance, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        if (vkEnumeratePhysicalDevices(instance, count, null) != VK_SUCCESS || count.get(0) == 0) {
            return List.of();
        }
        PointerBuffer handles = stack.mallocPointer(count.get(0));
        vkEnumeratePhysicalDevices(instance, count, handles);
        List<VkPhysicalDevice> out = new ArrayList<>(handles.capacity());
        for (int i = 0; i < handles.capacity(); i++) {
            out.add(new VkPhysicalDevice(handles.get(i), instance));
        }
        return out;
    }

    /** A GPU together with the ranking and the queue layout it supports. */
    private record Adapter(VkPhysicalDevice gpu, DeviceScorer.Ranked ranked,
                           QueueSelector.QueueSelection queues) {
    }

    private static Adapter chooseAdapter(List<VkPhysicalDevice> gpus, long surface, MemoryStack stack) {
        List<Adapter> viable = new ArrayList<>(gpus.size());
        List<DeviceScorer.Candidate> candidates = new ArrayList<>(gpus.size());
        List<VkPhysicalDevice> byIndex = new ArrayList<>(gpus.size());

        for (VkPhysicalDevice gpu : gpus) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(gpu, props);
            String name = props.deviceNameString();
            candidates.add(new DeviceScorer.Candidate(name, props.deviceType(),
                    deviceLocalBytes(gpu, stack), props.apiVersion(), isAmd(name, props.vendorID())));
            byIndex.add(gpu);
        }

        List<DeviceScorer.Ranked> ranked = DeviceScorer.rank(candidates);
        for (DeviceScorer.Ranked r : ranked) {
            int idx = indexOf(candidates, r.candidate());
            VkPhysicalDevice gpu = byIndex.get(idx);
            QueueSelector.QueueSelection queues = queueFamilies(gpu, surface, stack);
            if (queues != null) {
                viable.add(new Adapter(gpu, r, queues));
            }
        }
        // The list is already in preference order, and skipping an adapter that cannot present
        // keeps that order intact.
        return viable.isEmpty() ? null : viable.get(0);
    }

    private static int indexOf(List<DeviceScorer.Candidate> candidates, DeviceScorer.Candidate target) {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isAmd(String deviceName, int vendorId) {
        // The PCI vendor id is authoritative, so this does not depend on the name matching a
        // pattern. 0x1022 is the older ATI id that some drivers still report.
        return vendorId == GpuIdentity.VENDOR_ID_AMD || vendorId == 0x1022;
    }

    private static long deviceLocalBytes(VkPhysicalDevice gpu, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties mem = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(gpu, mem);
        long total = 0;
        for (int i = 0; i < mem.memoryHeapCount(); i++) {
            VkMemoryHeap heap = mem.memoryHeaps().get(i);
            if ((heap.flags() & MemoryTypeSelector.DEVICE_LOCAL) != 0) {
                total += heap.size();
            }
        }
        return total;
    }

    private static QueueSelector.QueueSelection queueFamilies(VkPhysicalDevice gpu, long surface,
                                                             MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(gpu, count, null);
        if (count.get(0) == 0) {
            return null;
        }
        VkQueueFamilyProperties.Buffer props = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(gpu, count, props);

        List<QueueSelector.QueueFamily> families = new ArrayList<>(props.capacity());
        IntBuffer supportsPresent = stack.mallocInt(1);
        for (int i = 0; i < props.capacity(); i++) {
            VkQueueFamilyProperties p = props.get(i);
            supportsPresent.put(0, 0);
            if (vkGetPhysicalDeviceSurfaceSupportKHR(gpu, i, surface, supportsPresent) != VK_SUCCESS) {
                return null;
            }
            families.add(new QueueSelector.QueueFamily(i, p.queueFlags(), p.queueCount(),
                    supportsPresent.get(0) == VK_TRUE));
        }
        try {
            return QueueSelector.select(families);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static Set<String> deviceExtensions(VkPhysicalDevice gpu, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        if (vkEnumerateDeviceExtensionProperties(gpu, (ByteBuffer) null, count, null) != VK_SUCCESS
                || count.get(0) == 0) {
            return Set.of();
        }
        VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0), stack);
        vkEnumerateDeviceExtensionProperties(gpu, (ByteBuffer) null, count, props);
        Set<String> out = new HashSet<>(props.capacity() * 2);
        for (int i = 0; i < props.capacity(); i++) {
            out.add(props.get(i).extensionNameString());
        }
        return out;
    }

    private static VkDevice createDevice(VkPhysicalDevice gpu, QueueSelector.QueueSelection queues,
                                         List<String> extensions, MemoryStack stack) {
        // Three families, but they may be the same family -- Vulkan rejects a duplicate
        // VkDeviceQueueCreateInfo for one index, so deduplicate.
        Set<Integer> indices = new LinkedHashSet<>();
        indices.add(queues.graphics());
        indices.add(queues.compute());
        indices.add(queues.hostTransfer());

        VkDeviceQueueCreateInfo.Buffer queueInfos = VkDeviceQueueCreateInfo.malloc(indices.size(), stack);
        int n = 0;
        for (int index : indices) {
            queueInfos.get(n++)
                    .sType$Default()
                    .pNext(0L)
                    .flags(0)
                    .queueFamilyIndex(index)
                    .pQueuePriorities(stack.floats(1.0f));
        }

        PointerBuffer names = stack.mallocPointer(extensions.size());
        for (String e : extensions) {
            names.put(stack.UTF8(e));
        }
        names.flip();

        // Only ask for features the device actually has; requesting one it lacks makes
        // vkCreateDevice return VK_ERROR_FEATURE_NOT_PRESENT and the whole mod fails to load.
        VkPhysicalDeviceFeatures available = VkPhysicalDeviceFeatures.malloc(stack);
        vkGetPhysicalDeviceFeatures(gpu, available);
        VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
        if (available.multiDrawIndirect()) {
            features.multiDrawIndirect(true);
        }
        if (available.samplerAnisotropy()) {
            features.samplerAnisotropy(true);
        }
        if (available.shaderInt16()) {
            features.shaderInt16(true);
        }
        if (available.textureCompressionBC()) {
            features.textureCompressionBC(true);
        }

        VkDeviceCreateInfo ci = VkDeviceCreateInfo.calloc(stack)
                .sType$Default()
                .pQueueCreateInfos(queueInfos)
                .ppEnabledExtensionNames(names)
                .pEnabledFeatures(features);

        PointerBuffer pDevice = stack.mallocPointer(1);
        int result = vkCreateDevice(gpu, ci, null, pDevice);
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("vkCreateDevice failed with VkResult " + result);
        }
        return new VkDevice(pDevice.get(0), gpu, ci);
    }

    private static VkQueue queue(VkDevice device, int familyIndex) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, familyIndex, 0, pQueue);
            return new VkQueue(pQueue.get(0), device);
        }
    }

    private static List<MemoryTypeSelector.MemoryType> readMemoryTypes(VkPhysicalDevice gpu,
                                                                       MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties mem = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(gpu, mem);
        List<MemoryTypeSelector.MemoryType> out = new ArrayList<>(mem.memoryTypeCount());
        for (int i = 0; i < mem.memoryTypeCount(); i++) {
            VkMemoryType type = mem.memoryTypes().get(i);
            VkMemoryHeap heap = mem.memoryHeaps().get(type.heapIndex());
            out.add(new MemoryTypeSelector.MemoryType(i, type.heapIndex(), heap.size(),
                    type.propertyFlags()));
        }
        return out;
    }

    public boolean isAvailable() {
        return this.failure == null && !this.closed;
    }

    public String failure() {
        return this.failure;
    }

    public VkInstance instance() {
        return this.instance;
    }

    public long surface() {
        return this.surface;
    }

    public VkPhysicalDevice physicalDevice() {
        return this.physicalDevice;
    }

    public VkDevice device() {
        return this.device;
    }

    public VkQueue graphicsQueue() {
        return this.graphicsQueue;
    }

    public VkQueue computeQueue() {
        return this.computeQueue;
    }

    public VkQueue hostTransferQueue() {
        return this.hostTransferQueue;
    }

    public QueueSelector.QueueSelection queues() {
        return this.queues;
    }

    public DeviceScorer.Ranked chosenAdapter() {
        return this.chosen;
    }

    public Set<String> enabledExtensions() {
        return this.enabledExtensions;
    }

    public MemoryTypeSelector.Selection memoryTypeFor(MemoryTypeSelector.Usage usage) {
        return MemoryTypeSelector.select(this.memoryTypes, usage);
    }

    /** One line per interesting decision, for {@code /amdfaster vk}. */
    public String describe() {
        if (!isAvailable()) {
            return "Vulkan unavailable: " + this.failure;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(this.chosen.candidate().deviceName())
                .append(" [").append(this.chosen.reason()).append("]\n");
        sb.append("graphics family ").append(this.queues.graphics())
                .append(", compute family ").append(this.queues.compute())
                .append(" (").append(this.queues.note()).append(")\n");
        for (MemoryTypeSelector.Usage usage : MemoryTypeSelector.Usage.values()) {
            MemoryTypeSelector.Selection s = memoryTypeFor(usage);
            sb.append(usage).append(" -> type ").append(s.typeIndex())
                    .append(" (").append(s.reason()).append(")\n");
        }
        sb.append("extensions: ").append(this.enabledExtensions);
        return sb.toString();
    }

    @Override
    public void close() {
        if (this.closed || this.instance == null) {
            this.closed = true;
            return;
        }
        this.closed = true;
        vkDeviceWaitIdle(this.device);
        vkDestroyDevice(this.device, null);
        if (this.surface != 0L) {
            vkDestroySurfaceKHR(this.instance, this.surface, null);
        }
        vkDestroyInstance(this.instance, null);
    }
}
