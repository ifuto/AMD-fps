package net.amdfaster.mixin;

import net.amdfaster.runtime.AmdFasterRuntime;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Returns a particle's share of the budget when the particle goes away.
 *
 * <p>The other half of {@link ParticleEngineMixin}. The budget is a count of what is alive, so it only
 * means something if every grant comes back. Vanilla removes a particle by calling {@code remove()} --
 * when its lifetime runs out, when it falls out of the world, when the block it is inside changes -- so
 * that is the one place every removal passes through.
 *
 * <p>Without this the failure is not a crash and not a leak that settles down. The count only ever rises,
 * the budget fills, and from that point on every particle in the world is refused. It presents as the
 * game quietly losing its effects some minutes into a session, which is a long way from its cause.
 */
@Mixin(Particle.class)
public abstract class ParticleMixin {

    @Inject(method = "remove", at = @At("HEAD"))
    private void amdfaster$returnBudget(CallbackInfo info) {
        // The same key ParticleEngineMixin took the grant with. It has to be the class rather than
        // anything the particle could report about itself, because the grant and the release have to name
        // the same source or the budget never balances.
        Particle self = (Particle) (Object) this;
        AmdFasterRuntime.releaseParticle(self.getClass().getName());
    }
}
