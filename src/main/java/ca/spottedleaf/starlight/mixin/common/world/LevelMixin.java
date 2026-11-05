package ca.spottedleaf.starlight.mixin.common.world;

import ca.spottedleaf.starlight.common.world.ExtendedWorld;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Level.class)
public abstract class LevelMixin implements LevelAccessor, AutoCloseable, ExtendedWorld {

    // Solix (0.3.2) - fake/virtual levels from mods may have no chunk source: treat every chunk as absent

    @Override
    public LevelChunk getChunkAtImmediately(final int chunkX, final int chunkZ) {
        final ChunkSource chunkSource = this.getChunkSource();
        return chunkSource == null ? null : chunkSource.getChunk(chunkX, chunkZ, false);
    }

    @Override
    public ChunkAccess getAnyChunkImmediately(final int chunkX, final int chunkZ) {
        final ChunkSource chunkSource = this.getChunkSource();
        return chunkSource == null ? null : chunkSource.getChunk(chunkX, chunkZ, ChunkStatus.EMPTY, false);
    }
}
