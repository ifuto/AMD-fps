package net.amdfaster.mixin;

import net.amdfaster.runtime.AmdFasterRuntime;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the caches that hold block and light data the moment the server says they are wrong.
 *
 * <p>These are the hooks the mod cannot do without. Every cache in here answers a question about a block
 * -- is it air, is it opaque, what does it emit -- and the answer is only valid until the block changes.
 * A cache that is not invalidated is not a cache, it is a source of blocks that are no longer there, and
 * the symptom is geometry that does not match the world: a face culled because its neighbour was stone
 * when the neighbour is now air, and a hole the player can see through.
 *
 * <p>Hooked at {@code HEAD} rather than {@code TAIL} so the caches are cleared before vanilla applies the
 * change, not after. Anything vanilla does while applying it -- a block entity reading its neighbours, a
 * fluid spreading -- has to see the invalidated cache, or it reads the answer the change is about to make
 * wrong.
 *
 * <p>Nothing is cancelled here and no value is replaced. This mixin only reports, which is why it is safe
 * to keep even if a later version renames the handler: the mod loses cache invalidation and gets visibly
 * wrong geometry, but it does not change what the game does.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    /** One block changed. */
    @Inject(method = "handleBlockUpdate", at = @At("HEAD"))
    private void amdfaster$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo info) {
        BlockPos pos = packet.getPos();
        AmdFasterRuntime.onBlockChanged(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * A whole section's worth of blocks changed at once.
     *
     * <p>Pistons, explosions and structure placement all arrive this way, which is the case where a cache
     * matters most: an explosion changes a few hundred blocks in one packet, and a cache holding the
     * pre-explosion world would cull faces against stone that is no longer there for the rest of the
     * session.
     */
    @Inject(method = "handleChunkBlocksUpdate", at = @At("HEAD"))
    private void amdfaster$onSectionBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet,
            CallbackInfo info) {
        // The same iteration vanilla is about to do, so the positions are the ones that will change and
        // not a guess at them. Iterating twice costs a few hundred array reads; getting the positions
        // wrong costs the correctness this hook exists for.
        packet.runUpdates((position, state) ->
                AmdFasterRuntime.onBlockChanged(position.getX(), position.getY(), position.getZ()));
    }

    /**
     * The server sent new light for a chunk column.
     *
     * <p>The packet carries section bitmasks rather than per-block levels, so there is no position and no
     * emission to hand to the light batcher -- inventing them would feed it changes that did not happen.
     * What the packet does say is that the cached light for this column is stale, and the block cache
     * stores emission alongside air and opacity, so that is what gets dropped.
     */
    @Inject(method = "handleLightUpdatePacket", at = @At("HEAD"))
    private void amdfaster$onLightUpdate(ClientboundLightUpdatePacket packet, CallbackInfo info) {
        AmdFasterRuntime.onLightColumnChanged(packet.getX(), packet.getZ());
    }
}
