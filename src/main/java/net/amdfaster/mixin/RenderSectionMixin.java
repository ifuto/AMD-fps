package net.amdfaster.mixin;

import net.amdfaster.runtime.AmdFasterRuntime;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds back the rebuild of a section the player cannot see.
 *
 * <p>Building a section's mesh is the most expensive thing the client does per chunk -- every block is
 * visited, every face is tested against its neighbours, ambient occlusion is baked into the vertices --
 * and the game will happily spend its workers doing that for sections behind the player while the ones in
 * front of them wait. Rebuilds are therefore let through by whether the section is on screen, with a small
 * allowance for the ones that are not, so a section is never left unbuilt for long.
 *
 * <p>Saying no here is a deferral and not a loss, which is what makes it safe: the section is still dirty,
 * so the renderer asks again next frame. Nothing has to remember that the work was skipped, and nothing
 * can go permanently stale.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMixin {

    @Inject(method = "rebuildSectionAsync", at = @At("HEAD"), cancellable = true)
    private void amdfaster$deferSpeculativeRebuild(RenderRegionCache cache, CallbackInfo info) {
        SectionRenderDispatcher.RenderSection self = (SectionRenderDispatcher.RenderSection) (Object) this;
        AABB box = self.getBoundingBox();
        if (!AmdFasterRuntime.offerSectionRebuild(box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ)) {
            info.cancel();
        }
    }
}
