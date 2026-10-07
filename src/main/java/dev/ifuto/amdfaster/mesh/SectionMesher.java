package dev.ifuto.amdfaster.mesh;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModels;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.block.BlockRenderType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.chunk.ChunkSection;

import java.nio.ByteBuffer;

/**
 * Meshes one 16&times;16&times;16 chunk section into the AMD-Faster vertex format.
 *
 * <p>Reads the block palette straight from the {@link LevelChunkSection} (no
 * per-block {@code getBlockState} indirection into a cloned slice — the overlay
 * runs once per activation, not per frame, so we trade a little CPU for a much
 * smaller, allocation-free mesher). Face culling and lighting use the vanilla
 * predicates through {@link MeshBuilder}.
 *
 * <p>Output is region-relative: the caller passes the section's world origin
 * and every vertex is written relative to it, which keeps float32 positions
 * exact inside a region (the same trick Sodium/Nvidium use — see
 * docs/research/07-sodium-architecture.md).
 */
public final class SectionMesher {
	private final Minecraft client = Minecraft.getInstance();
	private final MeshBuilder builder = new MeshBuilder();
	private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

	/**
	 * Meshes {@code section} (at {@code sectionPos}) into {@code vertices}/{@code indices}.
	 *
	 * @return number of indices written, or 0 when the section is empty / has no model blocks
	 */
	public int mesh(ClientWorld level, ChunkSectionPos sectionPos, ChunkSection section,
	                ByteBuffer vertices, ByteBuffer indices) {
		if (section == null || section.isEmpty()) {
			return 0;
		}

		int originX = sectionPos.getMinX();
		int originY = sectionPos.getMinY();
		int originZ = sectionPos.getMinZ();

		builder.begin(vertices, indices, originX, originY, originZ);

		BlockModels blockModels = client.getBakedModelManager().getBlockModels();

		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					BlockState state = section.getBlockState(x, y, z);
					if (state.isAir() && !state.hasBlockEntity()) {
						continue;
					}
					if (state.getRenderType() != BlockRenderType.MODEL) {
						continue; // v0.1: solid model blocks only (no fluids / block entities)
					}
					cursor.set(originX + x, originY + y, originZ + z);
					BlockStateModel model = blockModels.getModel(state);
					builder.emitBlock(level, state, cursor, model);
				}
			}
		}

		return builder.indexCount();
	}
}
