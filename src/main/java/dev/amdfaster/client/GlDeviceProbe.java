package dev.amdfaster.client;

import dev.amdfaster.core.arch.AmdGpuIdentifier;
import dev.amdfaster.core.arch.GpuIdentity;
import dev.amdfaster.core.arch.GpuVendor;
import dev.amdfaster.core.backend.DriverKind;
import dev.amdfaster.core.backend.GpuCapabilities;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.Platform;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Reads everything the OpenGL driver is willing to say about the device.
 *
 * <p>This runs once, on the render thread, after the game has created its GL context. Everything is
 * defensive: an unexpected string, a missing extension or a driver that rejects a query must leave
 * the mod in a state that is indistinguishable from the vanilla renderer, never in a state where it
 * believes in capabilities the machine does not have.</p>
 */
public final class GlDeviceProbe {

    /** Device memory queried through {@code GL_ATI_meminfo}: free texture memory in KiB. */
    private static final int GL_TEXTURE_FREE_MEMORY_ATI = 0x87FB;
    /** Device memory queried through {@code GL_NVX_gpu_memory_info}: total video memory in KiB. */
    private static final int GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX = 0x9048;

    private GlDeviceProbe() {
    }

    /** What the probe produced. */
    public record Result(GpuIdentity identity, GpuCapabilities capabilities) {
    }

    /**
     * Probes the current OpenGL context, enriched with whatever Vulkan reported.
     *
     * <p>OpenGL is the authoritative source for the extensions the GL path can use; Vulkan is the
     * authoritative source for the PCI identity and the real memory sizes. Whichever is available
     * contributes its half, and neither is required.</p>
     *
     * @param vkFacts what {@link VkDeviceProbe} learned, or an empty optional when Vulkan is absent
     * @return the device description, or empty when there is no current context on this thread
     *         (Minecraft creates and owns its context, and in recent versions it may live on a
     *         different thread)
     */
    public static Optional<Result> probe(Optional<VkDeviceProbe.Facts> vkFacts) {
        GLCapabilities caps;
        try {
            // LWJGL 3 exposes the capabilities of the *current* context through GL; there is no
            // GLContext class. Without a current context this throws, which is exactly the case the
            // caller has to handle (Minecraft may create its device on a different thread).
            caps = GL.getCapabilities();
        } catch (IllegalStateException | NullPointerException noContext) {
            return Optional.empty();
        } catch (LinkageError missingNatives) {
            return Optional.empty();
        }
        if (caps == null) {
            return Optional.empty();
        }

        String vendorString = safeString(GL11.GL_VENDOR);
        String rendererString = safeString(GL11.GL_RENDERER);
        String versionString = safeString(GL11.GL_VERSION);

        boolean onWindows = Platform.get() == Platform.WINDOWS;
        DriverKind driver = DriverKind.classify(vendorString, vendorString + ' ' + rendererString + ' '
                + versionString, false, onWindows);
        GpuVendor vendor = GpuVendor.classify(vendorString, rendererString, -1);

        // Vulkan knows the PCI identity even for the driver names OpenGL reports as a bare
        // "AMD Radeon(TM) Graphics", so it takes precedence when present.
        VkDeviceProbe.Facts vk = vkFacts.orElse(null);
        String name = vk != null ? vk.deviceName() : rendererString;
        int deviceId = vk != null ? vk.deviceId() : -1;
        AmdGpuIdentifier.Match match = AmdGpuIdentifier.classifyAmd(name, deviceId);
        int vramMiB = vk != null && vk.dedicatedVideoMemoryMiB() > 0
                ? vk.dedicatedVideoMemoryMiB() : probeVideoMemoryMiB(caps);
        int barMiB = vk != null && vk.hostVisibleDeviceMiB() > 0 ? vk.hostVisibleDeviceMiB() : -1;
        boolean integrated = vk != null ? vk.integrated() : match.arch().isIntegrated();

        String driverDescription = vk != null
                ? driver.label() + " / GL " + versionString + " / Vulkan " + vk.apiVersionMajor() + '.'
                        + vk.apiVersionMinor() + " driver " + vk.driverVersion()
                : driver.label() + " / GL " + versionString;

        GpuIdentity identity = new GpuIdentity(vendor, name, match.arch(), match.confidence(),
                deviceId, driverDescription, integrated, vramMiB, barMiB);

        GpuCapabilities.Builder builder = GpuCapabilities.builder(driver)
                .glBufferStorage(caps.GL_ARB_buffer_storage)
                .glMultiDrawIndirect(caps.GL_ARB_multi_draw_indirect)
                .glIndirectParameters(caps.GL_ARB_indirect_parameters)
                .glShaderStorageBuffer(caps.GL_ARB_shader_storage_buffer_object)
                .glComputeShader(caps.GL_ARB_compute_shader)
                .glClipControl(caps.GL_ARB_clip_control)
                .glBindlessTexture(caps.GL_ARB_bindless_texture)
                .glAmdPinnedMemory(caps.GL_AMD_pinned_memory)
                .glDirectStateAccess(caps.GL_ARB_direct_state_access)
                .dedicatedVideoMemoryMiB(vramMiB)
                .barSizeMiB(barMiB)
                .systemRamMiB(systemMemoryMiB())
                .apiVersion("OpenGL " + versionString)
                .vkAvailable(vk != null);
        if (vk != null) {
            builder.vkMeshShader(vk.meshShader())
                    .vkDescriptorIndexing(vk.descriptorIndexing())
                    .vkDynamicRendering(vk.dynamicRendering())
                    .vkDrawIndirectCount(vk.drawIndirectCount())
                    .vkSubgroupSizeControl(vk.subgroupSizeControl())
                    .vkSynchronization2(vk.synchronization2());
        }

        return Optional.of(new Result(identity, builder.build()));
    }

    private static String safeString(int name) {
        try {
            String value = GL11.glGetString(name);
            return value == null ? "unknown" : value;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /**
     * Video memory, as far as OpenGL will tell us.
     *
     * <p>Mesa reports the frame buffer size through {@code GL_NVX_gpu_memory_info}; AMD's Windows
     * driver reports free memory through {@code GL_ATI_meminfo}. When neither is present the value
     * stays -1 ("unknown") and the tuner uses the conservative sizes, because a wrong VRAM number
     * would produce wrong heap sizes.</p>
     */
    private static int probeVideoMemoryMiB(GLCapabilities caps) {
        try {
            if (caps.GL_NVX_gpu_memory_info) {
                int kib = GL11.glGetInteger(GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX);
                if (kib > 0) {
                    return kib / 1024;
                }
            }
            if (caps.GL_ATI_meminfo) {
                int kib = GL11.glGetInteger(GL_TEXTURE_FREE_MEMORY_ATI);
                if (kib > 0) {
                    return kib / 1024;
                }
            }
        } catch (RuntimeException ignored) {
            // A driver that dislikes the query must not decide whether the mod loads.
        }
        return -1;
    }

    /** Total physical memory of the machine, used to size shared-memory heaps on APUs. */
    private static int systemMemoryMiB() {
        try {
            Object bean = ManagementFactory.getOperatingSystemMXBean();
            Method method = bean.getClass().getMethod("getTotalMemorySize");
            Object value = method.invoke(bean);
            if (value instanceof Long bytes && bytes > 0) {
                return (int) Math.min(Integer.MAX_VALUE, bytes / (1024 * 1024));
            }
        } catch (ReflectiveOperationException ignored) {
            // Not a JDK that exposes the extended bean; the tuner falls back to conservative sizes.
        }
        return -1;
    }
}
