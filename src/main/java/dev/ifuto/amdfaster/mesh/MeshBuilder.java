package dev.ifuto.amdfaster.mesh;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.LightType;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Turns a Minecraft {@link BlockStateModel} into AMD-Faster vertex/index data,
 * written straight into a mapped {@link ByteBuffer}.
 *
 * <p>Pipeline per block (mirrors Sodium's {@code BlockRenderer} flow, trimmed
 * to the overlay's needs — solid blocks only, no fluids/entities):
 * <ol>
 *   <li>collect parts via {@code BlockStateModel.collectParts(random)}</li>
 *   <li>for each part, for each of the 7 cull faces (null + 6 directions),
 *       fetch {@code part.getQuads(face)}; if the face is culled by the
 *       neighbour block, skip</li>
 *   <li>decode each quad's 4 vertices through {@link QuadDecoder}, remap UVs
 *       from the quad's sprite into atlas space, bake the per-face shade factor
 *       and lightmap into the vertex color/light words, write 4 vertices +
 *       6 indices.</li>
 * </ol>
 *
 * <p>Face culling uses the vanilla occlusion predicates ({@code canOcclude},
 * {@code isSolidRender}); for the overlay this is enough and keeps the mesher
 * free of Sodium internals.
 */
public final class MeshBuilder {
	/** Vanilla per-face diffuse shade factors, indexed by {@link Direction#get3DDataValue()}. */
	private static final float[] FACE_SHADE = {0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f};

	private static final Direction[] CULL_FACES = {
			Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
	};

	private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
	private final RandomSource random = RandomSource.create(42L);

	/** Scratch arrays reused across quads (no allocation in the hot loop). */
	private final float[] positions = new float[12];
	private final float[] uvs = new float[8];

	private ByteBuffer vertexOut;
	private ByteBuffer indexOut;
	private int vertexCount;
	private int indexCount;
	private int originX, originY, originZ;

	/** Binds output buffers and resets counters. Vertex positions are region-relative. */
	public void begin(ByteBuffer vertices, ByteBuffer indices, int originX, int originY, int originZ) {
		this.vertexOut = vertices;
		this.indexOut = indices;
		this.vertexCount = 0;
		this.indexCount = 0;
		this.originX = originX;
		this.originY = originY;
		this.originZ = originZ;
	}

	public int vertexCount() {
		return vertexCount;
	}

	public int indexCount() {
		return indexCount;
	}

	/** Meshes one block of {@code state} at {@code pos} (world coords) into the bound buffers. */
	public void emitBlock(BlockRenderView level, BlockState state, BlockPos pos, BlockStateModel model) {
		if (state.isAir()) {
			return;
		}
		List<BlockModelPart> parts = model.collectParts(random);
		for (BlockModelPart part : parts) {
			emitPart(level, state, pos, part);
		}
	}

	private void emitPart(BlockRenderView level, BlockState state, BlockPos pos, BlockModelPart part) {
		// null face = unculled geometry (interior faces, etc.)
		emitFaceQuads(level, state, pos, part, null, 1.0f);
		for (int i = 0; i < CULL_FACES.length; i++) {
			Direction face = CULL_FACES[i];
			if (isFaceCulled(level, state, pos, face)) {
				continue;
			}
			emitFaceQuads(level, state, pos, part, face, FACE_SHADE[i]);
		}
	}

	private void emitFaceQuads(BlockRenderView level, BlockState state, BlockPos pos,
	                           BlockModelPart part, Direction cullFace, float shade) {
		List<BakedQuad> quads = part.getQuads(cullFace);
		if (quads == null || quads.isEmpty()) {
			return;
		}
		int light = lightFor(level, pos);
		int sky = (light >> 4) & 0xF;
		int block = light & 0xF;
		for (BakedQuad quad : quads) {
			emitQuad(quad, pos, shade, sky, block);
		}
	}

	private void emitQuad(BakedQuad quad, BlockPos pos, float shade, int sky, int block) {
		QuadDecoder.positions(quad, positions);
		QuadDecoder.uvs(quad, uvs);

		Sprite sprite = QuadDecoder.sprite(quad);
		float minU = sprite.getMinU();
		float minV = sprite.getMinV();
		float spanU = sprite.getMaxU() - minU;
		float spanV = sprite.getMaxV() - minV;

		int argb = colorFor(quad, shade);

		int baseIndex = vertexCount;
		for (int v = 0; v < QuadDecoder.QUAD_VERTS; v++) {
			float x = positions[v * 3] + (pos.getX() - originX);
			float y = positions[v * 3 + 1] + (pos.getY() - originY);
			float z = positions[v * 3 + 2] + (pos.getZ() - originZ);
			float u = minU + uvs[v * 2] * spanU;
			float vv = minV + uvs[v * 2 + 1] * spanV;
			VertexRecord.write(vertexOut, baseIndex * VertexRecord.SIZE + v * VertexRecord.SIZE,
					x, y, z, u, vv, argb, sky, block);
		}
		vertexCount += QuadDecoder.QUAD_VERTS;

		for (int idx : QuadDecoder.QUAD_INDICES) {
			indexOut.putInt(baseIndex + idx);
			indexCount++;
		}
	}

	/** White tint modulated by the quad's shade flag and the per-face shade factor. */
	private int colorFor(BakedQuad quad, float shade) {
		float s = QuadDecoder.hasShade(quad) ? shade : 1.0f;
		int r = (int) (255 * s);
		int g = (int) (255 * s);
		int b = (int) (255 * s);
		return (0xFF << 24) | (r << 16) | (g << 8) | b;
	}

	/** Lightmap value at {@code pos}: bits 4..7 = sky (0..15), bits 0..3 = block (0..15). */
	public static int lightFor(BlockRenderView level, BlockPos pos) {
		int sky = level.getLightLevel(LightType.SKY, pos);
		int block = level.getLightLevel(LightType.BLOCK, pos);
		return (Math.min(sky, 15) << 4) | Math.min(block, 15);
	}

	/**
	 * Vanilla occlusion test, simplified for the overlay: cull the face when the
	 * neighbour is a full opaque cube ({@code isOpaqueFullCube}) or otherwise a
	 * solid render block ({@code isSolidBlock}). This mirrors the
	 * {@code canOcclude}/{@code isSolidRender} pair Sodium and VulkanMod use
	 * (Sodium's {@code AbstractBlockRenderContext.shouldDrawSide},
	 * VulkanMod's {@code faceNotOccluded}) — both are Mojmap names; in Yarn the
	 * equivalent predicates are {@code isOpaqueFullCube} and {@code isSolidBlock}.
	 */
	public static boolean isFaceCulled(BlockRenderView level, BlockState state, BlockPos pos, Direction face) {
		cursor.set(pos, face);
		BlockState neighbour = level.getBlockState(cursor);
		if (neighbour.isAir()) {
			return false;
		}
		if (neighbour.isOpaqueFullCube() && state.isOpaqueFullCube()) {
			return true;
		}
		return neighbour.isSolidBlock(level, cursor);
	}
}
