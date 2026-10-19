package net.amdfaster.mixin;

import net.amdfaster.runtime.AmdFasterRuntime;
import net.amdfaster.sound.CategoryBudgetAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Decides whether a sound is started, before it is started.
 *
 * <p>This is the hook the whole sound pipeline was built for and the reason it has to be here rather than
 * on a tick boundary: a sound refused on a tick has already been allocated a channel, decoded and mixed,
 * and by then the only thing left to save is the mixing. Refusing it at {@code play} saves the lot.
 *
 * <p>What gets refused is narrow on purpose. Protected sounds -- the player's own hurt, a creeper's fuse
 * -- are never touched. Then a rate limit and a duplicate check for the same sound from the same block in
 * the same tick, a regional cap so one corner of a machine farm cannot use the whole pool, a per-category
 * share, and fairness between sources. Direction only comes into it when the pool is actually contested,
 * because that is the one filter the player can hear the absence of.
 *
 * <p>Hooked on {@code SoundManager.play} rather than {@code SoundEngine.play}. The engine's returns a
 * {@code PlayResult}, which a cancellable injection would have to supply a value for, and inventing one is
 * a guess about an enum this cannot see. The manager's is void and every caller goes through it.
 */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void amdfaster$decide(SoundInstance sound, CallbackInfo info) {
        ResourceLocation id = sound.getLocation();
        double x = sound.getX();
        double y = sound.getY();
        double z = sound.getZ();
        double angleFromFacing = 0.0;

        // A sound that is not attenuated has no position at all -- its coordinates are zero because they
        // are unused, not because it is at the origin. Treating it as a sound forty blocks away and
        // behind the camera would refuse the interface, the music and every narrated line, which is the
        // whole category this filter is supposed to leave alone.
        if (sound.getAttenuation() != SoundInstance.Attenuation.NONE) {
            angleFromFacing = angleFromFacing(x, y, z);
        }

        if (AmdFasterRuntime.refuseSound(id.toString(), id.getNamespace(),
                categoryOf(sound.getSource()), x, y, z, angleFromFacing, fieldOfView())) {
            info.cancel();
        }
    }

    /**
     * How far the sound is from where the camera is looking, in degrees.
     *
     * <p>Yaw only. Pitch is left out because the filter it feeds is about sounds behind the player, and
     * looking down at the ground does not put a sound at eye level behind anything. Measured on the
     * horizontal plane for the same reason: a sound directly below is not behind you.
     *
     * @return the angle in degrees, 0 when the sound is at the camera
     */
    private static double angleFromFacing(double x, double y, double z) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gameRenderer == null) {
            return 0.0;
        }
        Camera camera = client.gameRenderer.getMainCamera();
        if (camera == null || !camera.isInitialized()) {
            return 0.0;
        }
        Vec3 position = camera.position();
        double dx = x - position.x();
        double dy = y - position.y();
        double dz = z - position.z();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0e-6) {
            // At the camera, so there is no direction to be off by. Answering 180 here would put the
            // player's own footsteps behind them.
            return 0.0;
        }
        // Minecraft's yaw is 0 facing -Z and increases turning toward +X.
        float yawRadians = (float) Math.toRadians(camera.yRot());
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);
        double cosine = (dx * forwardX + dz * forwardZ) / horizontal;
        // Clamped because a float rounding can push the dot product a hair outside [-1, 1], and acos of
        // that is NaN -- which would then compare false against every threshold and refuse the sound.
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosine))));
    }

    /** The player's field of view setting, which is the angle the direction filter is measured against. */
    private static double fieldOfView() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) {
            return 70.0;
        }
        Integer fov = client.options.fov().get();
        return fov == null ? 70.0 : fov.doubleValue();
    }

    /**
     * The mixer category a vanilla sound source belongs to.
     *
     * <p>Exhaustive on purpose. A category a source does not map to would be a sound that shares a budget
     * it should not be drawing from, and a {@code default} branch would hide the omission until a sound
     * started behaving oddly. If a future version adds a source, this stops compiling, which is the
     * outcome that wants.
     */
    private static CategoryBudgetAllocator.Category categoryOf(SoundSource source) {
        return switch (source) {
            case MASTER -> CategoryBudgetAllocator.Category.MASTER;
            case VOICE -> CategoryBudgetAllocator.Category.VOICE;
            case PLAYERS -> CategoryBudgetAllocator.Category.PLAYERS;
            case HOSTILE -> CategoryBudgetAllocator.Category.HOSTILE;
            case BLOCKS -> CategoryBudgetAllocator.Category.BLOCKS;
            case MUSIC, RECORDS -> CategoryBudgetAllocator.Category.MUSIC;
            case NEUTRAL -> CategoryBudgetAllocator.Category.NEUTRAL;
            case WEATHER, AMBIENT -> CategoryBudgetAllocator.Category.WEATHER;
        };
    }
}
