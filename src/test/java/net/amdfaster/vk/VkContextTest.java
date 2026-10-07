package net.amdfaster.vk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The failure path is the one that runs on every machine this mod is not tuned for, so it is the
 * path worth pinning down: it has to report a reason and leave Minecraft alone. The success path
 * needs a GPU and is exercised by {@code /amdfaster vk}.
 */
class VkContextTest {

    @Test
    void aFailedContextCarriesItsReason() {
        assertEquals("no Vulkan physical devices", VkContext.failed("no Vulkan physical devices").failure());
    }

    @Test
    void aFailedContextIsNotAvailable() {
        assertFalse(VkContext.failed("anything").isAvailable());
    }

    @Test
    void aFailedContextSaysSoInItsDescription() {
        String description = VkContext.failed("glfwCreateWindowSurface failed").describe();
        assertTrue(description.contains("Vulkan unavailable"), description);
        assertTrue(description.contains("glfwCreateWindowSurface failed"), description);
    }

    @Test
    void closingAFailedContextDoesNotThrow() {
        // No instance, no device, no surface: close has nothing to destroy and must not care.
        assertDoesNotThrow(() -> VkContext.failed("nothing").close());
    }

    @Test
    void closingTwiceIsSafe() {
        VkContext context = VkContext.failed("nothing");
        context.close();
        assertDoesNotThrow(context::close);
        assertFalse(context.isAvailable());
    }

    @Test
    void swapchainIsRequired() {
        // Without it the renderer cannot present, so the mod must refuse to start rather than
        // produce a device that cannot draw to the window.
        assertTrue(VkContext.REQUIRED_DEVICE_EXTENSIONS.contains("VK_KHR_swapchain"));
        assertTrue(VkContext.WANTED_DEVICE_EXTENSIONS.contains("VK_KHR_swapchain"));
    }

    @Test
    void everythingRequiredIsAlsoWanted() {
        for (String required : VkContext.REQUIRED_DEVICE_EXTENSIONS) {
            assertTrue(VkContext.WANTED_DEVICE_EXTENSIONS.contains(required),
                    required + " is required but not in the wanted list");
        }
    }

    @Test
    void theExtensionsThe8BitIndexPathNeedsAreAskedFor() {
        // Stage 2 emits six 8-bit indices per quad. Without this the index buffer has to be
        // widened to 16 bits, which doubles the index traffic for no benefit.
        assertTrue(VkContext.WANTED_DEVICE_EXTENSIONS.contains("VK_EXT_index_type_uint8"));
    }

    @Test
    void theExtensionsTheGpuDrivenPathNeedsAreAskedFor() {
        assertTrue(VkContext.WANTED_DEVICE_EXTENSIONS.contains("VK_KHR_synchronization2"));
        assertTrue(VkContext.WANTED_DEVICE_EXTENSIONS.contains("VK_KHR_dynamic_rendering"));
        assertTrue(VkContext.WANTED_DEVICE_EXTENSIONS.contains("VK_EXT_descriptor_buffer"));
    }
}
