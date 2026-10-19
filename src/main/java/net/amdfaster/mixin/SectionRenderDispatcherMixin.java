package net.amdfaster.mixin;

import net.amdfaster.runtime.AmdFasterRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads the frame's camera where the game reports it.
 *
 * <p>The dispatcher is handed the camera position once per rendered frame, before it rebuilds any section
 * and before the game draws any entity. That makes it the one point in the frame where a single read of
 * the camera can serve both passes, instead of each of them going back to the game and each getting a
 * slightly different answer.
 *
 * <p>The position argument itself is not used: the passes need the near plane's corners, which carry the
 * screen's orientation and extent, and the camera holds those. This hook is the signal that a frame has
 * started, and it arrives early enough for the rebuild decisions that follow it to be made against the
 * right camera.
 */
@Mixin(SectionRenderDispatcher.class)
public abstract class SectionRenderDispatcherMixin {

    @Inject(method = "setCameraPosition", at = @At("HEAD"))
    private void amdfaster$onRenderFrame(Vec3 cameraPosition, CallbackInfo info) {
        AmdFasterRuntime.onRenderFrameStarted(Minecraft.getInstance());
    }
}
