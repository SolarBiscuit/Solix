package ca.spottedleaf.starlight.common.light;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Solix (0.3.6): the {@link BlockGetter} the server light engines hand to mods' position-aware light callbacks
 * ({@code getLightEmission}, {@code getLightBlock}, {@code getFaceOcclusionShape}) instead of the {@code ServerLevel}.
 * <p>
 * Those callbacks run on a light worker thread (a Solix worker, or a vanilla worker when lighting is serial). Mods
 * read block entities and neighbouring blocks from them. With the real level such a read goes through
 * {@code ServerChunkCache#getChunk}, which, called off the server thread, queues the lookup on the server thread and
 * waits for it. That wait deadlocks when the server thread is itself waiting for a chunk whose light the worker is
 * computing (Age of Fate #149: Ender IO's machine blocks look up their own block entity from getLightEmission, the
 * server thread sits in {@code getChunkBlocking}, the world never finishes loading). It also made every such
 * callback a round trip to the server thread.
 * </p>
 * <p>
 * This view never loads a chunk and never touches the server thread: it reads the chunks the light engine itself
 * works with ({@link LightChunkGetter#getChunkForLighting}, the same lookup the vanilla light engine uses). A chunk
 * that is not there reads as air, no block entity and no fluid.
 * </p>
 */
public final class LightBlockGetter implements BlockGetter {

    private final LightChunkGetter chunks;
    private final LevelHeightAccessor heights;

    public LightBlockGetter(final LightChunkGetter chunks, final LevelHeightAccessor heights) {
        this.chunks = chunks;
        this.heights = heights;
    }

    private ChunkAccess chunkAt(final BlockPos pos) {
        final BlockGetter chunk = this.chunks.getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk instanceof ChunkAccess ? (ChunkAccess)chunk : null;
    }

    @Override
    public BlockEntity getBlockEntity(final BlockPos pos) {
        if (this.isOutsideBuildHeight(pos)) {
            return null;
        }
        final ChunkAccess chunk = this.chunkAt(pos);
        if (chunk == null) {
            return null;
        }
        // Solix (0.3.7) - lookup of an already registered block entity (Forge: getExistingBlockEntity; Fabric: the
        // chunk's map directly, which is what Forge's method reads). LevelChunk#getBlockEntity would also promote a
        // pending block entity (registering a ticker with the level) and drop removed ones from the map, which only
        // the server thread may do.
        final BlockEntity blockEntity = chunk.blockEntities.get(pos);
        return blockEntity == null || blockEntity.isRemoved() ? null : blockEntity;
    }

    @Override
    public BlockState getBlockState(final BlockPos pos) {
        if (this.isOutsideBuildHeight(pos)) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        final ChunkAccess chunk = this.chunkAt(pos);
        return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(final BlockPos pos) {
        if (this.isOutsideBuildHeight(pos)) {
            return Fluids.EMPTY.defaultFluidState();
        }
        final ChunkAccess chunk = this.chunkAt(pos);
        return chunk == null ? Fluids.EMPTY.defaultFluidState() : chunk.getFluidState(pos);
    }

    @Override
    public int getHeight() {
        return this.heights.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return this.heights.getMinBuildHeight();
    }
}
