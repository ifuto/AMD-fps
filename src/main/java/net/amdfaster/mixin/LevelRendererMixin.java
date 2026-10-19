package net.amdfaster.mixin;

import net.amdfaster.runtime.AmdFasterRuntime;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops the culling result when the terrain it was computed against is thrown away.
 *
 * <p>{@code allChanged} is the renderer saying that every chunk it knows about is about to be rebuilt:
 * a resource reload, a render distance change, a graphics setting change, a respawn. The culling
 * pipeline reuses last frame's visible set when nothing relevant moved, and terrain being replaced is
 * the largest possible change to what is relevant -- but it is not a camera movement, so the reuse
 * tracker would not notice it on its own.
 *
 * <p>Left alone, the reuse path would keep answering from a visible set built out of chunks that no
 * longer exist. That is not a small error and it does not correct itself: the cull result decides what
 * gets meshed, so the sections that would replace the old ones are never asked for, and the world stays
 * half built until the player moves the camera enough to invalidate the reuse for a different reason.
 *
 * <p>Reports only. Nothing here is cancelled.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void amdfaster$onTerrainInvalidated(CallbackInfo info) {
        AmdFasterRuntime.onTerrainInvalidated();
    }
}
