package net.amdfaster.mixin;

import net.amdfaster.runtime.AmdFasterRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Asks the cull before the game decides for itself.
 *
 * <p>This is the one place an entity can be dropped before anything is spent on it. Answering no here
 * means no renderer is looked up, no bounding box is built, no frustum test the game would have run, no
 * model matrices, no vertices. The same decision made later saves part of that; made here it saves all of
 * it.
 *
 * <p>What is added over what the game already does is a budget across the whole frame and, once a depth
 * pyramid is supplied, occlusion. The distance filter follows the game's render distance rather than
 * inventing a shorter one, and the camera entity is never culled, so nothing this removes is something
 * the player would otherwise have seen.
 *
 * <p>The hook is deliberately thin. It hands the entity over and uses the answer; the frame's camera, the
 * level's entity list and every rule live in the runtime, where they run on a plain JVM and can be
 * tested. See {@link net.amdfaster.runtime.EntityCullRuntime}.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void amdfaster$cull(Entity entity,
            net.minecraft.client.renderer.culling.Frustum frustum,
            double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> info) {
        // The game's own frustum is not used. Reading it would mean reconstructing its six planes from a
        // class that does not expose them, and the pass builds its own from the camera's near plane
        // instead -- which it needs anyway for the depth pyramid projection.
        if (!AmdFasterRuntime.shouldRenderEntity(Minecraft.getInstance(), entity)) {
            info.setReturnValue(false);
        }
    }
}
