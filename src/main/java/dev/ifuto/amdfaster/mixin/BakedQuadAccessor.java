package dev.ifuto.amdfaster.mixin;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin for the {@code BakedQuad} fields that are not public in Yarn.
 *
 * <p>Needed for:
 * <ul>
 *   <li>{@code sprite} — the atlas {@link Sprite} a quad samples from; we use it
 *       to remap quad UVs into the sprite's atlas sub-rect (see {@code MeshBuilder}),</li>
 *   <li>{@code shade} — whether diffuse shading is baked in (drives the AO/face
 *       shade byte in the vertex color),</li>
 *   <li>{@code tintIndex} — drives biome/foliage color lookups.</li>
 * </ul>
 *
 * This mirrors what Sodium does with {@code BakedQuadMixin}, but kept as a
 * pure accessor so {@code QuadDecoder} can stay a plain static helper.
 */
@Mixin(BakedQuad.class)
public interface BakedQuadAccessor {
	@Accessor("sprite")
	Sprite amdfaster$getSprite();

	@Accessor("shade")
	boolean amdfaster$getShade();

	@Accessor("tintIndex")
	int amdfaster$getTintIndex();
}
