package dev.ifuto.amdfaster.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.ifuto.amdfaster.vk.Backend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Replaces the GL buffer swap with our Vulkan present.
 *
 * <p>{@code RenderSystem.flipFrame(Window, TracyFrameCapturer)} is the single
 * choke point where Minecraft hands the finished frame to the OS. Redirecting
 * the {@code glfwSwapBuffers} call (same technique as VulkanMod's
 * {@code mixin/render/frame/RenderSystemMixin.java}) lets us present our own
 * swapchain instead — and skip the GL present entirely when the overlay is
 * active.
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
	@Redirect(
			method = "flipFrame",
			at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"),
			remap = false
	)
	private static void amdfaster$presentVulkan(long window) {
		Backend backend = Backend.get();
		if (backend != null) {
			backend.endFrame();
		}
		// When the backend is absent (disabled/failed) we deliberately do NOT call
		// glfwSwapBuffers here either — the GL framebuffer was never drawn to in
		// overlay mode, and in non-overlay mode the game still owns presentation.
		// In v0.1 AMD-Faster is always in one of those two states, so skipping the
		// GL swap is correct.
	}
}
