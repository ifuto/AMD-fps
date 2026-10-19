package net.amdfaster.mixin;

import net.amdfaster.runtime.AmdFasterRuntime;
import net.amdfaster.sound.CategoryBudgetAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Decides whether a sound is started, before it is started.
 *
 * <p>This is the hook the whole sound pipeline was built for, and the reason it has to be here rather
 * than on a tick boundary: a sound refused on a tick has already been given a channel, had its buffer
 * decoded and been mixed, and by then the only thing left to save is the mixing. Refusing it at
 * {@code play} saves all of it.
 *
 * <p>What gets refused is narrow on purpose. Protected sounds -- the player's own hurt, a creeper's fuse
 * -- are never touched. Then a rate limit, a duplicate check for the same sound from the same block in
 * the same tick, a regional cap so one corner of a machine farm cannot use the whole pool, a per-category
 * share, and fairness between sources. Direction only comes into it once the pool is actually contested,
 * because that is the one filter whose absence a player can hear.
 *
 * <p>A refused sound returns {@code NOT_STARTED}, which is the answer this method already gives when a
 * sound is not started for its own reasons. Callers handle it, because they have to.
 */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void amdfaster$decide(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> info) {
        Identifier id = sound.getIdentifier();
        double x = sound.getX();
        double y = sound.getY();
        double z = sound.getZ();
        double angleFromFacing = 0.0;

        // A relative sound has no position at all -- its coordinates are zero because they are unused,
        // not because it is at the origin. Treating it as a sound somewhere out in the world, possibly
        // behind the camera, would refuse the interface, the music and every narrated line: exactly the
        // category the direction filter exists to leave alone.
        if (!sound.isRelative()) {
            angleFromFacing = angleFromFacing(x, y, z);
        }

        if (AmdFasterRuntime.refuseSound(id.toString(), id.getNamespace(),
                categoryOf(sound.getSource()), x, y, z, angleFromFacing, fieldOfView())) {
            info.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
        }
    }

    /**
     * How far the sound is from where the camera is looking, in degrees.
     *
     * <p>Yaw only. Pitch is left out because the filter this feeds is about sounds behind the player, and
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
        double dz = z - position.z();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0e-6) {
            // At the camera, so there is no direction to be off by. Answering 180 here would put the
            // player's own footsteps behind them.
            return 0.0;
        }
        // Minecraft's yaw is 0 facing -Z and increases turning toward +X.
        double yawRadians = Math.toRadians(camera.yRot());
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);
        double cosine = (dx * forwardX + dz * forwardZ) / horizontal;
        // Clamped, because a float rounding can push the dot product a hair outside [-1, 1] and acos of
        // that is NaN -- which then compares false against every threshold and refuses the sound.
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
     * <p>Exhaustive on purpose, with no {@code default}. A source that mapped nowhere would be a sound
     * drawing on a budget it should not be drawing on, and a default branch would hide the omission until
     * a sound started behaving oddly. If a future version adds a source this stops compiling, which is the
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
