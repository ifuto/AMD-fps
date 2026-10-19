package net.amdfaster.mixin;

import net.amdfaster.runtime.AmdFasterRuntime;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shares the particle budget between whatever is spawning particles, at the point where refusing one
 * still saves something.
 *
 * <p>A particle refused here is never constructed, never ticked and never added to a draw batch. The same
 * decision made at draw time would still have paid for all three, which is most of what a particle costs
 * -- the drawing is one instanced quad either way.
 *
 * <p>What the budget is for is fairness, not a lower particle count. It gives each active source an equal
 * share of a total, and the share grows as sources go quiet, so one mod that covers the world in ambient
 * effects cannot starve another whose particles are the point of what it does. In a scene where nothing is
 * near the limit this hook grants everything it is asked for and changes nothing.
 *
 * <p>Every grant is returned by {@link ParticleMixin}, when the particle is removed. The two have to be
 * read together: taking a grant without returning it is not a leak that settles, it is a budget that
 * fills up and then refuses every particle the world tries to draw afterwards.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void amdfaster$budgetParticles(Particle particle, CallbackInfo info) {
        // The source is the particle's own class. Particle exposes no accessor for the type it was made
        // from, and the class is the better key anyway: it is what actually distinguishes one mod's
        // particles from another's, where two mods could both be spawning something called "flame".
        // Class.getName is cached on the Class, so this allocates nothing after the first call.
        if (!AmdFasterRuntime.offerParticle(particle.getClass().getName())) {
            info.cancel();
        }
    }

    /**
     * The engine dropping everything it holds.
     *
     * <p>A resource reload and a dimension change both come through here, and neither removes the particles
     * one at a time -- so the grants they hold would never be returned and the budget would carry them into
     * whatever comes next.
     */
    @Inject(method = "clearParticles", at = @At("HEAD"))
    private void amdfaster$onParticlesCleared(CallbackInfo info) {
        AmdFasterRuntime.onParticlesCleared();
    }
}
