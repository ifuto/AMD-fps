package dev.ifuto.amdfaster.mixin;

import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Neutralizes the final GL blit-to-screen while the overlay is active.
 *
 * <p>Vanilla ends the frame with {@code Framebuffer.blitToScreen()} (the
 * window framebuffer blitting itself onto the default framebuffer). In overlay
 * mode the Vulkan backend presents its own swapchain, so the GL blit would
 * either fight our present or write into a framebuffer nobody shows. Cancelling
 * it keeps the GL side quiet.
 */
@Mixin(Framebuffer.class)
public abstract class RenderTargetMixin {
	@Inject(method = "blitToScreen", at = @At("HEAD"), cancellable = true)
	private void amdfaster$skipBlit(CallbackInfo ci) {
		if (dev.ifuto.amdfaster.overlay.OverlayController.isActive()) {
			ci.cancel();
		}
	}
}
