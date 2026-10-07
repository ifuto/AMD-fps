package dev.ifuto.amdfaster.mixin;

import dev.ifuto.amdfaster.AmdFaster;
import dev.ifuto.amdfaster.vk.Backend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame lifecycle hooks.
 *
 * <ul>
 *   <li>{@code runTick(boolean)} HEAD — lazily initializes the Vulkan backend
 *       (the GL context + GLFW window exist by the first tick), then calls
 *       {@link Backend#beginFrame()} to acquire the next swapchain image.</li>
 *   <li>{@code render(boolean)} HEAD — when the overlay is active we skip the
 *       vanilla GL render entirely; the backend owns the frame.</li>
 *   <li>{@code stop()} — saves the pipeline cache and tears the backend down.</li>
 * </ul>
 *
 * <p>Why {@code runTick} and not {@code render}: {@code runTick} runs before
 * {@code render} in the same frame and is where VulkanMod hooks
 * ({@code mixin/render/frame/MinecraftMixin.java}) — same battle-tested spot.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Shadow
	public Window window;

	@Shadow
	public abstract void tick();

	@Inject(method = "runTick", at = @At("HEAD"))
	private void amdfaster$beginFrame(boolean tick, CallbackInfo ci) {
		if (Backend.get() == null) {
			Backend.initIfNeeded(window.getHandle());
		}
		Backend backend = Backend.get();
		if (backend != null && backend.isFrameActive()) {
			return; // previous frame still recording (shouldn't happen, but be safe)
		}
		if (backend != null) {
			backend.beginFrame();
		}
		// Poll the overlay toggle key on the render thread.
		dev.ifuto.amdfaster.overlay.OverlayController.get().tick();
	}

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void amdfaster$skipVanillaRender(boolean tick, CallbackInfo ci) {
		// When the overlay owns the frame, the vanilla GL renderer must not run:
		// its output would never be presented (we present our own swapchain).
		if (dev.ifuto.amdfaster.overlay.OverlayController.isActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "stop", at = @At("HEAD"))
	private void amdfaster$shutdown(CallbackInfo ci) {
		Backend backend = Backend.get();
		if (backend != null) {
			backend.shutdown();
		}
		AmdFaster.setStatus(AmdFaster.BackendState.DISABLED, "game stopping");
	}
}
