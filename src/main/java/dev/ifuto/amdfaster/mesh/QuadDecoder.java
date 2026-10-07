package dev.ifuto.amdfaster.mesh;

import dev.ifuto.amdfaster.mixin.BakedQuadAccessor;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.core.Direction;
import org.joml.Vector3fc;

/**
 * Decodes a vanilla {@link BakedQuad} into AMD-Faster vertex inputs.
 *
 * <p>1.21.11 BakedQuads expose, per vertex, a {@link Vector3fc} position via
 * {@code getPosition(int)} and a <i>packed</i> {@code long} of two 32-bit
 * float UVs via {@code getTexcoords(int)}. Both are public in Yarn, so most
 * of the decoder needs no mixin. The three fields that <b>are</b> private —
 * {@code sprite}, {@code shade}, {@code tintIndex} — go through the
 * {@link BakedQuadAccessor} mixin (same approach as Sodium's
 * {@code BakedQuadMixin}, kept as a pure accessor so this class stays a
 * plain static helper).
 */
public final class QuadDecoder {
	/** Number of vertices per quad. */
	public static final int QUAD_VERTS = 4;

	/** Vanilla winding order (used to reproduce the triangle index buffer). */
	public static final int[] QUAD_INDICES = {0, 1, 2, 2, 3, 0};

	private QuadDecoder() {
	}

	/** Reads the 4 positions of {@code quad} into {@code out} (length &gt;= 12, xyz per vertex). */
	public static void positions(BakedQuad quad, float[] out) {
		for (int i = 0; i < QUAD_VERTS; i++) {
			Vector3fc p = quad.getPosition(i);
			out[i * 3] = p.x();
			out[i * 3 + 1] = p.y();
			out[i * 3 + 2] = p.z();
		}
	}

	/**
	 * Reads the 4 packed UV pairs into {@code out} (length &gt;= 8, u/v per vertex).
	 * The packed long holds two 32-bit IEEE floats (u in the low word, v in the high).
	 */
	public static void uvs(BakedQuad quad, float[] out) {
		for (int i = 0; i < QUAD_VERTS; i++) {
			long packed = quad.getTexcoords(i);
			out[i * 2] = Float.intBitsToFloat((int) (packed & 0xFFFFFFFFL));
			out[i * 2 + 1] = Float.intBitsToFloat((int) (packed >>> 32));
		}
	}

	/** The quad's tint index, or -1 when it carries no tint. */
	public static int tintIndex(BakedQuad quad) {
		return ((BakedQuadAccessor) quad).amdfaster$getTintIndex();
	}

	/** Whether the quad has shade (diffuse lighting) applied. */
	public static boolean hasShade(BakedQuad quad) {
		return ((BakedQuadAccessor) quad).amdfaster$getShade();
	}

	/** The quad's face direction, used for face culling &amp; AO. */
	public static Direction face(BakedQuad quad) {
		return quad.face();
	}

	/** The sprite (atlas sub-texture) referenced by the quad. */
	public static Sprite sprite(BakedQuad quad) {
		return ((BakedQuadAccessor) quad).amdfaster$getSprite();
	}
}
