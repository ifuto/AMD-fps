package dev.ifuto.amdfaster.mixin;

import dev.ifuto.amdfaster.overlay.OverlayController;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the vanilla world render while the overlay is active.
 *
 * <p>{@code GameRenderer.renderWorld(RenderTickCounter)} is where the GL world
 * renderer runs. Cancelling it (and {@code Minecraft.render} via
 * {@link MinecraftMixin}) leaves the Vulkan backend as the sole producer of
 * frames while the overlay is up — which is exactly the benchmark condition we
 * want: same world, same camera, only the renderer changes.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "renderWorld", at = @At("HEAD"), cancellable = true)
	private void amdfaster$skipWorldRender(RenderTickCounter tickCounter, CallbackInfo ci) {
		if (OverlayController.isActive()) {
			ci.cancel();
		}
	}
}
