package ca.spottedleaf.starlight.common.light;

import ca.spottedleaf.starlight.common.blockstate.ExtendedAbstractBlockState;
import ca.spottedleaf.starlight.common.blockstate.PositionalEmission;
import ca.spottedleaf.starlight.common.config.SolixConfig;
import ca.spottedleaf.starlight.common.util.CoordinateUtils;
import ca.spottedleaf.starlight.common.util.IntegerUtil;
import ca.spottedleaf.starlight.common.util.WorldUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortCollection;
import it.unimi.dsi.fastutil.shorts.ShortIterator;
import ca.spottedleaf.starlight.common.config.SolixConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public abstract class StarLightEngine {

    protected static final BlockState AIR_BLOCK_STATE = Blocks.AIR.defaultBlockState();

    protected static final AxisDirection[] DIRECTIONS = AxisDirection.values();
    protected static final AxisDirection[] AXIS_DIRECTIONS = DIRECTIONS;
    protected static final AxisDirection[] ONLY_HORIZONTAL_DIRECTIONS = new AxisDirection[] {
            AxisDirection.POSITIVE_X, AxisDirection.NEGATIVE_X,
            AxisDirection.POSITIVE_Z, AxisDirection.NEGATIVE_Z
    };

    protected static enum AxisDirection {

        // Declaration order is important and relied upon. Do not change without modifying propagation code.
        POSITIVE_X(1, 0, 0), NEGATIVE_X(-1, 0, 0),
        POSITIVE_Z(0, 0, 1), NEGATIVE_Z(0, 0, -1),
        POSITIVE_Y(0, 1, 0), NEGATIVE_Y(0, -1, 0);

        static {
            POSITIVE_X.opposite = NEGATIVE_X; NEGATIVE_X.opposite = POSITIVE_X;
            POSITIVE_Z.opposite = NEGATIVE_Z; NEGATIVE_Z.opposite = POSITIVE_Z;
            POSITIVE_Y.opposite = NEGATIVE_Y; NEGATIVE_Y.opposite = POSITIVE_Y;
        }

        protected AxisDirection opposite;

        public final int x;
        public final int y;
        public final int z;
        public final Direction nms;
        public final long everythingButThisDirection;
        public final long everythingButTheOppositeDirection;

        AxisDirection(final int x, final int y, final int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.nms = Direction.fromDelta(x, y, z);
            this.everythingButThisDirection = (long)(ALL_DIRECTIONS_BITSET ^ (1 << this.ordinal()));
            // positive is always even, negative is always odd. Flip the 1 bit to get the negative direction.
            this.everythingButTheOppositeDirection = (long)(ALL_DIRECTIONS_BITSET ^ (1 << (this.ordinal() ^ 1)));
        }

        public AxisDirection getOpposite() {
            return this.opposite;
        }
    }

    // I'd like to thank https://www.seedofandromeda.com/blogs/29-fast-flood-fill-lighting-in-a-blocky-voxel-game-pt-1
    // for explaining how light propagates via breadth-first search

    // While the above is a good start to understanding the general idea of what the general principles are, it's not
    // exactly how the vanilla light engine should behave for minecraft.

    // similar to the above, except the chunk section indices vary from [-1, 1], or [0, 2]
    // for the y chunk section it's from [minLightSection, maxLightSection] or [0, maxLightSection - minLightSection]
    // index = x + (z * 5) + (y * 25)
    // null index indicates the chunk section doesn't exist (empty or out of bounds)
    protected final LevelChunkSection[] sectionCache;

    // the exact same as above, except for storing fast access to SWMRNibbleArray
    // for the y chunk section it's from [minLightSection, maxLightSection] or [0, maxLightSection - minLightSection]
    // index = x + (z * 5) + (y * 25)
    protected final SWMRNibbleArray[] nibbleCache;

    // the exact same as above, except for storing fast access to nibbles to call change callbacks for
    // for the y chunk section it's from [minLightSection, maxLightSection] or [0, maxLightSection - minLightSection]
    // index = x + (z * 5) + (y * 25)
    protected final boolean[] notifyUpdateCache;

    // always initialsed during start of lighting.
    // index = x + (z * 5)
    protected final ChunkAccess[] chunkCache = new ChunkAccess[5 * 5];

    // index = x + (z * 5)
    protected final boolean[][] emptinessMapCache = new boolean[5 * 5][];

    protected final BlockPos.MutableBlockPos mutablePos1 = new BlockPos.MutableBlockPos();
    protected final BlockPos.MutableBlockPos mutablePos2 = new BlockPos.MutableBlockPos();
    protected final BlockPos.MutableBlockPos mutablePos3 = new BlockPos.MutableBlockPos();

    protected int encodeOffsetX;
    protected int encodeOffsetY;
    protected int encodeOffsetZ;

    protected int coordinateOffset;

    protected int chunkOffsetX;
    protected int chunkOffsetY;
    protected int chunkOffsetZ;

    protected int chunkIndexOffset;
    protected int chunkSectionIndexOffset;

    protected final boolean skylightPropagator;
    protected final int emittedLightMask;
    protected final boolean isClientSide;

    protected final Level world;
    protected final BlockGetter blockAccess; // Solix (0.3.6), see the constructor
    protected final int minLightSection;
    protected final int maxLightSection;
    protected final int minSection;
    protected final int maxSection;

    // Solix (0.3.3) - isClientSide comes from StarLightInterface, so readers and engines agree for levels that are
    // neither a ServerLevel nor client side (fake/virtual levels of mods)
    protected StarLightEngine(final boolean skylightPropagator, final Level world, final boolean isClientSide, final LightChunkGetter lightAccess) {
        this.skylightPropagator = skylightPropagator;
        this.emittedLightMask = skylightPropagator ? 0 : 0xF;
        this.isClientSide = isClientSide;
        this.world = world;
        // Solix (0.3.6) - what mods' position-aware light callbacks (getLightEmission/getLightBlock/getFaceOcclusionShape)
        // get to read. Server side that is a view that never loads a chunk or waits for the server thread (the real
        // level does, from a light worker, and deadlocks against a server thread that waits for this chunk's light).
        // Only for real server levels: the client light engine and fake/virtual levels of mods run on the thread that
        // owns the level, where the real level is safe and stays exactly as before.
        this.blockAccess = (SolixConfig.SAFE_LIGHT_VIEW && !isClientSide && lightAccess != null && world instanceof net.minecraft.server.level.ServerLevel)
                ? new LightBlockGetter(lightAccess, world) : world;
        this.minLightSection = WorldUtil.getMinLightSection(world);
        this.maxLightSection = WorldUtil.getMaxLightSection(world);
        this.minSection = WorldUtil.getMinSection(world);
        this.maxSection = WorldUtil.getMaxSection(world);

        this.sectionCache = new LevelChunkSection[5 * 5 * ((this.maxLightSection - this.minLightSection + 1) + 2)]; // add two extra sections for buffer
        this.nibbleCache = new SWMRNibbleArray[5 * 5 * ((this.maxLightSection - this.minLightSection + 1) + 2)]; // add two extra sections for buffer
        this.notifyUpdateCache = new boolean[5 * 5 * ((this.maxLightSection - this.minLightSection + 1) + 2)]; // add two extra sections for buffer
    }

    // Solix (0.3.7) - all calls into mods' position-aware light code go through these three. If a mod's code casts the
    // BlockGetter it is given to Level (or similar) and fails with a ClassCastException, that block class is
    // remembered and is given the real level from then on, as in 0.3.5 and before (see LightViewFallback).
    // Solix (Fabric) - vanilla/Fabric light emission is per block state; only blocks overriding Porting Lib's positional
    // emission (Forge's API ported to Fabric, see PositionalEmission) have mod code to call here.
    protected final int callEmission(final BlockState state, final BlockPos pos) {
        if (!PositionalEmission.isPositionAware(state)) {
            return state.getLightEmission();
        }
        final BlockGetter access = this.blockAccess;
        if (access != this.world) {
            if (LightViewFallback.useRealLevel(state)) {
                return PositionalEmission.getLightEmission(state, this.world, pos);
            }
            try {
                return PositionalEmission.getLightEmission(state, access, pos);
            } catch (final ClassCastException ex) {
                LightViewFallback.record(state, ex);
                return PositionalEmission.getLightEmission(state, this.world, pos);
            }
        }
        return PositionalEmission.getLightEmission(state, access, pos);
    }

    protected final int callLightBlock(final BlockState state, final BlockPos pos) {
        final BlockGetter access = this.blockAccess;
        if (access != this.world) {
            if (LightViewFallback.useRealLevel(state)) {
                return state.getLightBlock(this.world, pos);
            }
            try {
                return state.getLightBlock(access, pos);
            } catch (final ClassCastException ex) {
                LightViewFallback.record(state, ex);
                return state.getLightBlock(this.world, pos);
            }
        }
        return state.getLightBlock(access, pos);
    }

    protected final VoxelShape callFaceShape(final BlockState state, final BlockPos pos, final Direction direction) {
        final BlockGetter access = this.blockAccess;
        if (access != this.world) {
            if (LightViewFallback.useRealLevel(state)) {
                return state.getFaceOcclusionShape(this.world, pos, direction);
            }
            try {
                return state.getFaceOcclusionShape(access, pos, direction);
            } catch (final ClassCastException ex) {
                LightViewFallback.record(state, ex);
                return state.getFaceOcclusionShape(this.world, pos, direction);
            }
        }
        return state.getFaceOcclusionShape(access, pos, direction);
    }

    protected final void setupEncodeOffset(final int centerX, final int centerY, final int centerZ) {
        // 31 = center + encodeOffset
        this.encodeOffsetX = 31 - centerX;
        this.encodeOffsetY = (-(this.minLightSection - 1) << 4); // we want 0 to be the smallest encoded value
        this.encodeOffsetZ = 31 - centerZ;

        // coordinateIndex = x | (z << 6) | (y << 12)
        this.coordinateOffset = this.encodeOffsetX + (this.encodeOffsetZ << 6) + (this.encodeOffsetY << 12);

        // 2 = (centerX >> 4) + chunkOffset
        this.chunkOffsetX = 2 - (centerX >> 4);
        this.chunkOffsetY = -(this.minLightSection - 1); // lowest should be 0
        this.chunkOffsetZ = 2 - (centerZ >> 4);

        // chunk index = x + (5 * z)
        this.chunkIndexOffset = this.chunkOffsetX + (5 * this.chunkOffsetZ);

        // chunk section index = x + (5 * z) + ((5*5) * y)
        this.chunkSectionIndexOffset = this.chunkIndexOffset + ((5 * 5) * this.chunkOffsetY);
    }

    protected final void setupCaches(final LightChunkGetter chunkProvider, final int centerX, final int centerY, final int centerZ,
                                     final boolean relaxed, final boolean tryToLoadChunksFor2Radius) {
        final int centerChunkX = centerX >> 4;
        final int centerChunkY = centerY >> 4;
        final int centerChunkZ = centerZ >> 4;

        this.setupEncodeOffset(centerChunkX * 16 + 7, centerChunkY * 16 + 7, centerChunkZ * 16 + 7);

        final int radius = tryToLoadChunksFor2Radius ? 2 : 1;

        for (int dz = -radius; dz <= radius; ++dz) {
            for (int dx = -radius; dx <= radius; ++dx) {
                final int cx = centerChunkX + dx;
                final int cz = centerChunkZ + dz;
                final boolean isTwoRadius = Math.max(IntegerUtil.branchlessAbs(dx), IntegerUtil.branchlessAbs(dz)) == 2;
                final ChunkAccess chunk = (ChunkAccess)chunkProvider.getChunkForLighting(cx, cz);

                if (chunk == null) {
                    if (relaxed | isTwoRadius) {
                        continue;
                    }
                    throw new IllegalArgumentException("Trying to propagate light update before 1 radius neighbours ready");
                }

                if (!this.canUseChunk(chunk)) {
                    continue;
                }

                this.setChunkInCache(cx, cz, chunk);
                this.setEmptinessMapCache(cx, cz, this.getEmptinessMap(chunk));
                if (!isTwoRadius) {
                    this.setBlocksForChunkInCache(cx, cz, chunk.getSections());
                    this.setNibblesForChunkInCache(cx, cz, this.getNibblesOnChunk(chunk));
                }
            }
        }
    }

    protected final ChunkAccess getChunkInCache(final int chunkX, final int chunkZ) {
        return this.chunkCache[chunkX + 5*chunkZ + this.chunkIndexOffset];
    }

    protected final void setChunkInCache(final int chunkX, final int chunkZ, final ChunkAccess chunk) {
        this.chunkCache[chunkX + 5*chunkZ + this.chunkIndexOffset] = chunk;
    }

    protected final LevelChunkSection getChunkSection(final int chunkX, final int chunkY, final int chunkZ) {
        return this.sectionCache[chunkX + 5*chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset];
    }

    protected final void setChunkSectionInCache(final int chunkX, final int chunkY, final int chunkZ, final LevelChunkSection section) {
        this.sectionCache[chunkX + 5*chunkZ + 5*5*chunkY + this.chunkSectionIndexOffset] = section;
    }

    protected final void setBlocksForChunkInCache(final int chunkX, final int chunkZ, final LevelChunkSection[] sections) {
        for (int cy = this.minLightSection; cy <= this.maxLightSection; ++cy) {
            this.setChunkSectionInCache(chunkX, cy, chunkZ,
                    sections == null ? null : (cy >= this.minSection && cy <= this.maxSection ? sections[cy - this.minSection] : null));
        }
    }

    protected final SWMRNibbleArray getNibbleFromCache(final int chunkX, final int chunkY, final int chunkZ) {
        return this.nibbleCache[chunkX + 5*chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset];
    }

    protected final SWMRNibbleArray[] getNibblesForChunkFromCache(final int chunkX, final int chunkZ) {
        final SWMRNibbleArray[] ret = new SWMRNibbleArray[this.maxLightSection - this.minLightSection + 1];

        for (int cy = this.minLightSection; cy <= this.maxLightSection; ++cy) {
            ret[cy - this.minLightSection] = this.nibbleCache[chunkX + 5*chunkZ + (cy * (5 * 5)) + this.chunkSectionIndexOffset];
        }

        return ret;
    }

    protected final void setNibbleInCache(final int chunkX, final int chunkY, final int chunkZ, final SWMRNibbleArray nibble) {
        this.nibbleCache[chunkX + 5*chunkZ + (5 * 5) * chunkY + this.chunkSectionIndexOffset] = nibble;
    }

    protected final void setNibblesForChunkInCache(final int chunkX, final int chunkZ, final SWMRNibbleArray[] nibbles) {
        for (int cy = this.minLightSection; cy <= this.maxLightSection; ++cy) {
            this.setNibbleInCache(chunkX, cy, chunkZ, nibbles == null ? null : nibbles[cy - this.minLightSection]);
        }
    }

    protected final void updateVisible(final LightChunkGetter lightAccess) {
        for (int index = 0, max = this.nibbleCache.length; index < max; ++index) {
            final SWMRNibbleArray nibble = this.nibbleCache[index];
            if (!this.notifyUpdateCache[index] && (nibble == null || !nibble.isDirty())) {
                continue;
            }

            final int chunkX = (index % 5) - this.chunkOffsetX;
            final int chunkZ = ((index / 5) % 5) - this.chunkOffsetZ;
            final int ySections = this.maxSection - this.minSection + 1;
            final int chunkY = ((index / (5*5)) % (ySections + 2 + 2)) - this.chunkOffsetY;
            // Solix - updateVisible() is false for a section whose light ended up unchanged (see SWMRNibbleArray)
            if ((nibble != null && nibble.updateVisible()) || this.notifyUpdateCache[index]) {
                if (LightStats.ENABLED) {
                    ++this.statSectionsReported;
                }
                lightAccess.onLightUpdate(this.skylightPropagator ? LightLayer.SKY : LightLayer.BLOCK, SectionPos.of(chunkX, chunkY, chunkZ));
            } else if (LightStats.ENABLED) {
                ++this.statSectionsSuppressed;
            }
        }
    }

    protected final void destroyCaches() {
        Arrays.fill(this.sectionCache, null);
        Arrays.fill(this.nibbleCache, null);
        Arrays.fill(this.chunkCache, null);
        Arrays.fill(this.emptinessMapCache, null);
        if (this.isClientSide || this.forcedReports) {
            Arrays.fill(this.notifyUpdateCache, false);
            this.forcedReports = false; // Solix (0.3.3)
        }
        this.clearQueues(); // Solix
    }

    // Solix start (0.3.3) - always send the light of sections holding changed blocks
    // Since 0.3.0 sections whose light ends up unchanged are not reported (SWMRNibbleArray#updateVisible). Clients also
    // compute light for the block changes they receive, and before 0.3.0 the server re-sent the changed block's section
    // whenever it was rewritten, which corrected the client where its own result differed (e.g. at the edge of its view
    // distance). Keep that for the sections the changed blocks are in; unchanged sections elsewhere stay unsent.
    // On the server notifyUpdateCache is otherwise unused, it marks the sections to report here.
    protected boolean forcedReports;

    protected final void forceReportChangedSections(final Set<BlockPos> positions) {
        if (this.isClientSide) {
            return;
        }
        for (final BlockPos pos : positions) {
            final int index = (pos.getX() >> 4) + 5 * (pos.getZ() >> 4) + (5 * 5) * (pos.getY() >> 4) + this.chunkSectionIndexOffset;
            if (this.nibbleCache[index] != null) {
                this.notifyUpdateCache[index] = true;
                this.forcedReports = true;
            }
        }
    }
    // Solix end (0.3.3)

    protected final BlockState getBlockState(final int worldX, final int worldY, final int worldZ) {
        final LevelChunkSection section = this.sectionCache[(worldX >> 4) + 5 * (worldZ >> 4) + (5 * 5) * (worldY >> 4) + this.chunkSectionIndexOffset];

        if (section != null) {
            return section.hasOnlyAir() ? AIR_BLOCK_STATE : section.getBlockState(worldX & 15, worldY & 15, worldZ & 15);
        }

        return AIR_BLOCK_STATE;
    }

    protected final BlockState getBlockState(final int sectionIndex, final int localIndex) {
        final LevelChunkSection section = this.sectionCache[sectionIndex];

        if (section != null) {
            return section.hasOnlyAir() ? AIR_BLOCK_STATE : section.states.get(localIndex);
        }

        return AIR_BLOCK_STATE;
    }

    protected final int getLightLevel(final int worldX, final int worldY, final int worldZ) {
        final SWMRNibbleArray nibble = this.nibbleCache[(worldX >> 4) + 5 * (worldZ >> 4) + (5 * 5) * (worldY >> 4) + this.chunkSectionIndexOffset];

        return nibble == null ? 0 : nibble.getUpdating((worldX & 15) | ((worldZ & 15) << 4) | ((worldY & 15) << 8));
    }

    protected final int getLightLevel(final int sectionIndex, final int localIndex) {
        final SWMRNibbleArray nibble = this.nibbleCache[sectionIndex];

        return nibble == null ? 0 : nibble.getUpdating(localIndex);
    }

    protected final void setLightLevel(final int worldX, final int worldY, final int worldZ, final int level) {
        final int sectionIndex = (worldX >> 4) + 5 * (worldZ >> 4) + (5 * 5) * (worldY >> 4) + this.chunkSectionIndexOffset;
        final SWMRNibbleArray nibble = this.nibbleCache[sectionIndex];

        if (nibble != null) {
            nibble.set((worldX & 15) | ((worldZ & 15) << 4) | ((worldY & 15) << 8), level);
            if (this.isClientSide) {
                int cx1 = (worldX - 1) >> 4;
                int cx2 = (worldX + 1) >> 4;
                int cy1 = (worldY - 1) >> 4;
                int cy2 = (worldY + 1) >> 4;
                int cz1 = (worldZ - 1) >> 4;
                int cz2 = (worldZ + 1) >> 4;
                for (int x = cx1; x <= cx2; ++x) {
                    for (int y = cy1; y <= cy2; ++y) {
                        for (int z = cz1; z <= cz2; ++z) {
                            this.notifyUpdateCache[x + 5 * z + (5 * 5) * y + this.chunkSectionIndexOffset] = true;
                        }
                    }
                }
            }
        }
    }

    protected final void postLightUpdate(final int worldX, final int worldY, final int worldZ) {
        if (this.isClientSide) {
            int cx1 = (worldX - 1) >> 4;
            int cx2 = (worldX + 1) >> 4;
            int cy1 = (worldY - 1) >> 4;
            int cy2 = (worldY + 1) >> 4;
            int cz1 = (worldZ - 1) >> 4;
            int cz2 = (worldZ + 1) >> 4;
            for (int x = cx1; x <= cx2; ++x) {
                for (int y = cy1; y <= cy2; ++y) {
                    for (int z = cz1; z <= cz2; ++z) {
                        this.notifyUpdateCache[x + (5 * z) + (5 * 5 * y) + this.chunkSectionIndexOffset] = true;
                    }
                }
            }
        }
    }

    protected final void setLightLevel(final int sectionIndex, final int localIndex, final int worldX, final int worldY, final int worldZ, final int level) {
        final SWMRNibbleArray nibble = this.nibbleCache[sectionIndex];

        if (nibble != null) {
            nibble.set(localIndex, level);
            if (this.isClientSide) {
                int cx1 = (worldX - 1) >> 4;
                int cx2 = (worldX + 1) >> 4;
                int cy1 = (worldY - 1) >> 4;
                int cy2 = (worldY + 1) >> 4;
                int cz1 = (worldZ - 1) >> 4;
                int cz2 = (worldZ + 1) >> 4;
                for (int x = cx1; x <= cx2; ++x) {
                    for (int y = cy1; y <= cy2; ++y) {
                        for (int z = cz1; z <= cz2; ++z) {
                            this.notifyUpdateCache[x + (5 * z) + (5 * 5 * y) + this.chunkSectionIndexOffset] = true;
                        }
                    }
                }
            }
        }
    }

    protected final boolean[] getEmptinessMap(final int chunkX, final int chunkZ) {
        return this.emptinessMapCache[chunkX + 5*chunkZ + this.chunkIndexOffset];
    }

    protected final void setEmptinessMapCache(final int chunkX, final int chunkZ, final boolean[] emptinessMap) {
        this.emptinessMapCache[chunkX + 5*chunkZ + this.chunkIndexOffset] = emptinessMap;
    }

    public static SWMRNibbleArray[] getFilledEmptyLight(final LevelHeightAccessor world) {
        return getFilledEmptyLight(WorldUtil.getTotalLightSections(world));
    }

    private static SWMRNibbleArray[] getFilledEmptyLight(final int totalLightSections) {
        final SWMRNibbleArray[] ret = new SWMRNibbleArray[totalLightSections];

        for (int i = 0, len = ret.length; i < len; ++i) {
            ret[i] = new SWMRNibbleArray(null, true);
        }

        return ret;
    }

    protected abstract boolean[] getEmptinessMap(final ChunkAccess chunk);

    protected abstract void setEmptinessMap(final ChunkAccess chunk, final boolean[] to);

    protected abstract SWMRNibbleArray[] getNibblesOnChunk(final ChunkAccess chunk);

    protected abstract void setNibbles(final ChunkAccess chunk, final SWMRNibbleArray[] to);

    protected abstract boolean canUseChunk(final ChunkAccess chunk);

    public final void blocksChangedInChunk(final LightChunkGetter lightAccess, final int chunkX, final int chunkZ,
                                           final Set<BlockPos> positions, final Boolean[] changedSections) {
        final long statStart = LightStats.ENABLED ? System.nanoTime() : 0L; // Solix
        this.setupCaches(lightAccess, chunkX * 16 + 7, 128, chunkZ * 16 + 7, true, true);
        try {
            final ChunkAccess chunk = this.getChunkInCache(chunkX, chunkZ);
            if (chunk == null) {
                return;
            }
            if (changedSections != null) {
                final boolean[] ret = this.handleEmptySectionChanges(lightAccess, chunk, changedSections, false);
                if (ret != null) {
                    this.setEmptinessMap(chunk, ret);
                }
            }
            if (!positions.isEmpty()) {
                this.propagateBlockChanges(lightAccess, chunk, positions);
                this.forceReportChangedSections(positions); // Solix (0.3.3)
            }
            this.updateVisible(lightAccess);
        } finally {
            this.destroyCaches();
            if (LightStats.ENABLED) {
                LightStats.record(LightStats.BLOCK_CHANGES, statStart, this); // Solix
            }
        }
    }

    // subclasses should not initialise caches, as this will always be done by the super call
    // subclasses should not invoke updateVisible, as this will always be done by the super call
    protected abstract void propagateBlockChanges(final LightChunkGetter lightAccess, final ChunkAccess atChunk, final Set<BlockPos> positions);

    protected final BlockPos.MutableBlockPos checkBlockPos = new BlockPos.MutableBlockPos(); // Forge

    protected abstract void checkBlock(final LightChunkGetter lightAccess, final int worldX, final int worldY, final int worldZ);

    // if ret > expect, then the real value is at least ret (early returns if ret > expect, rather than calculating actual)
    // if ret == expect, then expect is the correct light value for pos
    // if ret < expect, then ret is the real light value
    protected abstract int calculateLightValue(final LightChunkGetter lightAccess, final int worldX, final int worldY, final int worldZ,
                                               final int expect);

    protected final int[] chunkCheckDelayedUpdatesCenter = new int[16 * 16];
    protected final int[] chunkCheckDelayedUpdatesNeighbour = new int[16 * 16];

    protected void checkChunkEdge(final LightChunkGetter lightAccess, final ChunkAccess chunk,
                                  final int chunkX, final int chunkY, final int chunkZ) {
        final SWMRNibbleArray currNibble = this.getNibbleFromCache(chunkX, chunkY, chunkZ);
        if (currNibble == null) {
            return;
        }

        for (final AxisDirection direction : ONLY_HORIZONTAL_DIRECTIONS) {
            final int neighbourOffX = direction.x;
            final int neighbourOffZ = direction.z;

            final SWMRNibbleArray neighbourNibble = this.getNibbleFromCache(chunkX + neighbourOffX,
                    chunkY, chunkZ + neighbourOffZ);

            if (neighbourNibble == null) {
                continue;
            }

            if (!currNibble.isInitialisedUpdating() && !neighbourNibble.isInitialisedUpdating()) {
                // both are zero, nothing to check.
                continue;
            }

            // this chunk
            final int incX;
            final int incZ;
            final int startX;
            final int startZ;

            if (neighbourOffX != 0) {
                // x direction
                incX = 0;
                incZ = 1;

                if (direction.x < 0) {
                    // negative
                    startX = chunkX << 4;
                } else {
                    startX = chunkX << 4 | 15;
                }
                startZ = chunkZ << 4;
            } else {
                // z direction
                incX = 1;
                incZ = 0;

                if (neighbourOffZ < 0) {
                    // negative
                    startZ = chunkZ << 4;
                } else {
                    startZ = chunkZ << 4 | 15;
                }
                startX = chunkX << 4;
            }

            int centerDelayedChecks = 0;
            int neighbourDelayedChecks = 0;
            for (int currY = chunkY << 4, maxY = currY | 15; currY <= maxY; ++currY) {
                for (int i = 0, currX = startX, currZ = startZ; i < 16; ++i, currX += incX, currZ += incZ) {
                    final int neighbourX = currX + neighbourOffX;
                    final int neighbourZ = currZ + neighbourOffZ;

                    final int currentIndex = (currX & 15) |
                            ((currZ & 15)) << 4 |
                            ((currY & 15) << 8);
                    final int currentLevel = currNibble.getUpdating(currentIndex);

                    final int neighbourIndex =
                            (neighbourX & 15) |
                            ((neighbourZ & 15)) << 4 |
                            ((currY & 15) << 8);
                    final int neighbourLevel = neighbourNibble.getUpdating(neighbourIndex);

                    // the checks are delayed because the checkBlock method clobbers light values - which then
                    // affect later calculate light value operations. While they don't affect it in a behaviourly significant
                    // way, they do have a negative performance impact due to simply queueing more values

                    if (this.calculateLightValue(lightAccess, currX, currY, currZ, currentLevel) != currentLevel) {
                        this.chunkCheckDelayedUpdatesCenter[centerDelayedChecks++] = currentIndex;
                    }

                    if (this.calculateLightValue(lightAccess, neighbourX, currY, neighbourZ, neighbourLevel) != neighbourLevel) {
                        this.chunkCheckDelayedUpdatesNeighbour[neighbourDelayedChecks++] = neighbourIndex;
                    }
                }
            }

            final int currentChunkOffX = chunkX << 4;
            final int currentChunkOffZ = chunkZ << 4;
            final int neighbourChunkOffX = (chunkX + direction.x) << 4;
            final int neighbourChunkOffZ = (chunkZ + direction.z) << 4;
            final int chunkOffY = chunkY << 4;
            for (int i = 0, len = Math.max(centerDelayedChecks, neighbourDelayedChecks); i < len; ++i) {
                // try to queue neighbouring data together
                // index = x | (z << 4) | (y << 8)
                if (i < centerDelayedChecks) {
                    final int value = this.chunkCheckDelayedUpdatesCenter[i];
                    this.checkBlock(lightAccess, currentChunkOffX | (value & 15),
                            chunkOffY | (value >>> 8),
                            currentChunkOffZ | ((value >>> 4) & 0xF));
                }
                if (i < neighbourDelayedChecks) {
                    final int value = this.chunkCheckDelayedUpdatesNeighbour[i];
                    this.checkBlock(lightAccess, neighbourChunkOffX | (value & 15),
                            chunkOffY | (value >>> 8),
                            neighbourChunkOffZ | ((value >>> 4) & 0xF));
                }
            }
        }
    }

    protected void checkChunkEdges(final LightChunkGetter lightAccess, final ChunkAccess chunk, final ShortCollection sections) {
        final ChunkPos chunkPos = chunk.getPos();
        final int chunkX = chunkPos.x;
        final int chunkZ = chunkPos.z;

        for (final ShortIterator iterator = sections.iterator(); iterator.hasNext();) {
            this.checkChunkEdge(lightAccess, chunk, chunkX, iterator.nextShort(), chunkZ);
        }

        this.performLightDecrease(lightAccess);
    }

    // subclasses should not initialise caches, as this will always be done by the super call
    // subclasses should not invoke updateVisible, as this will always be done by the super call
    // verifies that light levels on this chunks edges are consistent with this chunk's neighbours
    // edges. if they are not, they are decreased (effectively performing the logic in checkBlock).
    // This does not resolve skylight source problems.
    protected void checkChunkEdges(final LightChunkGetter lightAccess, final ChunkAccess chunk, final int fromSection, final int toSection) {
        final ChunkPos chunkPos = chunk.getPos();
        final int chunkX = chunkPos.x;
        final int chunkZ = chunkPos.z;

        for (int currSectionY = toSection; currSectionY >= fromSection; --currSectionY) {
            this.checkChunkEdge(lightAccess, chunk, chunkX, currSectionY, chunkZ);
        }

        this.performLightDecrease(lightAccess);
    }

    // pulls light from neighbours, and adds them into the increase queue. does not actually propagate.
    protected final void propagateNeighbourLevels(final LightChunkGetter lightAccess, final ChunkAccess chunk, final int fromSection, final int toSection) {
        final ChunkPos chunkPos = chunk.getPos();
        final int chunkX = chunkPos.x;
        final int chunkZ = chunkPos.z;

        for (int currSectionY = toSection; currSectionY >= fromSection; --currSectionY) {
            final SWMRNibbleArray currNibble = this.getNibbleFromCache(chunkX, currSectionY, chunkZ);
            if (currNibble == null) {
                continue;
            }
            for (final AxisDirection direction : ONLY_HORIZONTAL_DIRECTIONS) {
                final int neighbourOffX = direction.x;
                final int neighbourOffZ = direction.z;

                final SWMRNibbleArray neighbourNibble = this.getNibbleFromCache(chunkX + neighbourOffX,
                        currSectionY, chunkZ + neighbourOffZ);

                if (neighbourNibble == null || !neighbourNibble.isInitialisedUpdating()) {
                    // can't pull from 0
                    continue;
                }

                // neighbour chunk
                final int incX;
                final int incZ;
                final int startX;
                final int startZ;

                if (neighbourOffX != 0) {
                    // x direction
                    incX = 0;
                    incZ = 1;

                    if (direction.x < 0) {
                        // negative
                        startX = (chunkX << 4) - 1;
                    } else {
                        startX = (chunkX << 4) + 16;
                    }
                    startZ = chunkZ << 4;
                } else {
                    // z direction
                    incX = 1;
                    incZ = 0;

                    if (neighbourOffZ < 0) {
                        // negative
                        startZ = (chunkZ << 4) - 1;
                    } else {
                        startZ = (chunkZ << 4) + 16;
                    }
                    startX = chunkX << 4;
                }

                final long propagateDirection = 1L << direction.getOpposite().ordinal(); // we only want to check in this direction towards this chunk
                final int encodeOffset = this.coordinateOffset;

                for (int currY = currSectionY << 4, maxY = currY | 15; currY <= maxY; ++currY) {
                    for (int i = 0, currX = startX, currZ = startZ; i < 16; ++i, currX += incX, currZ += incZ) {
                        final int level = neighbourNibble.getUpdating(
                                (currX & 15)
                                        | ((currZ & 15) << 4)
                                        | ((currY & 15) << 8)
                        );

                        if (level <= 1) {
                            // nothing to propagate
                            continue;
                        }

                        this.appendToIncreaseQueue(
                                ((currX + (currZ << 6) + (currY << (6 + 6)) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                        | ((level & 0xFL) << (6 + 6 + 16))
                                        | (propagateDirection << (6 + 6 + 16 + 4))
                                        | FLAG_HAS_SIDED_TRANSPARENT_BLOCKS // don't know if the current block is transparent, must check.
                        );
                    }
                }
            }
        }
    }

    public static Boolean[] getEmptySectionsForChunk(final ChunkAccess chunk) {
        final LevelChunkSection[] sections = chunk.getSections();
        final Boolean[] ret = new Boolean[sections.length];

        for (int i = 0; i < sections.length; ++i) {
            if (sections[i] == null || sections[i].hasOnlyAir()) {
                ret[i] = Boolean.TRUE;
            } else {
                ret[i] = Boolean.FALSE;
            }
        }

        return ret;
    }

    public final void forceHandleEmptySectionChanges(final LightChunkGetter lightAccess, final ChunkAccess chunk, final Boolean[] emptinessChanges) {
        final int chunkX = chunk.getPos().x;
        final int chunkZ = chunk.getPos().z;
        this.setupCaches(lightAccess, chunkX * 16 + 7, 128, chunkZ * 16 + 7, true, true);
        try {
            // force current chunk into cache
            this.setChunkInCache(chunkX, chunkZ, chunk);
            this.setBlocksForChunkInCache(chunkX, chunkZ, chunk.getSections());
            this.setNibblesForChunkInCache(chunkX, chunkZ, this.getNibblesOnChunk(chunk));
            this.setEmptinessMapCache(chunkX, chunkZ, this.getEmptinessMap(chunk));

            final boolean[] ret = this.handleEmptySectionChanges(lightAccess, chunk, emptinessChanges, false);
            if (ret != null) {
                this.setEmptinessMap(chunk, ret);
            }
            this.updateVisible(lightAccess);
        } finally {
            this.destroyCaches();
        }
    }

    public final void handleEmptySectionChanges(final LightChunkGetter lightAccess, final int chunkX, final int chunkZ,
                                                final Boolean[] emptinessChanges) {
        this.setupCaches(lightAccess, chunkX * 16 + 7, 128, chunkZ * 16 + 7, true, true);
        try {
            final ChunkAccess chunk = this.getChunkInCache(chunkX, chunkZ);
            if (chunk == null) {
                return;
            }
            final boolean[] ret = this.handleEmptySectionChanges(lightAccess, chunk, emptinessChanges, false);
            if (ret != null) {
                this.setEmptinessMap(chunk, ret);
            }
            this.updateVisible(lightAccess);
        } finally {
            this.destroyCaches();
        }
    }

    protected abstract void initNibble(final int chunkX, final int chunkY, final int chunkZ, final boolean extrude, final boolean initRemovedNibbles);

    protected abstract void setNibbleNull(final int chunkX, final int chunkY, final int chunkZ);

    // Solix - section emptiness bitmasks of the 5x5 chunk columns, see handleEmptySectionChanges
    protected final long[] nonEmptyMasks = new long[5 * 5];

    // Solix - the original per-section search: whether every section in the 3x3x3 around (chunkX, sectionY, chunkZ) is
    // empty. Only used when a world has too many light sections for the bitmasks
    private boolean areNeighbourSectionsEmpty(final int chunkX, final int sectionY, final int chunkZ) {
        for (int dy2 = -1; dy2 <= 1; ++dy2) {
            for (int dz2 = -1; dz2 <= 1; ++dz2) {
                for (int dx2 = -1; dx2 <= 1; ++dx2) {
                    final int y = sectionY + dy2;
                    if (y < this.minSection || y > this.maxSection) {
                        // empty
                        continue;
                    }
                    final boolean[] emptinessMap = this.getEmptinessMap(dx2 + chunkX, dz2 + chunkZ);
                    if (emptinessMap != null) {
                        if (!emptinessMap[y - this.minSection]) {
                            return false;
                        }
                    } else {
                        final LevelChunkSection section = this.getChunkSection(dx2 + chunkX, y, dz2 + chunkZ);
                        if (section != null && !section.hasOnlyAir()) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    // subclasses should not initialise caches, as this will always be done by the super call
    // subclasses should not invoke updateVisible, as this will always be done by the super call
    // subclasses are guaranteed that this is always called before a changed block set
    // newChunk specifies whether the changes describe a "first load" of a chunk or changes to existing, already loaded chunks
    // rets non-null when the emptiness map changed and needs to be updated
    protected final boolean[] handleEmptySectionChanges(final LightChunkGetter lightAccess, final ChunkAccess chunk,
                                                        final Boolean[] emptinessChanges, final boolean unlit) {
        final Level world = (Level)lightAccess.getLevel();
        final int chunkX = chunk.getPos().x;
        final int chunkZ = chunk.getPos().z;

        boolean[] chunkEmptinessMap = this.getEmptinessMap(chunkX, chunkZ);
        boolean[] ret = null;
        final boolean needsInit = unlit || chunkEmptinessMap == null;
        if (needsInit) {
            this.setEmptinessMapCache(chunkX, chunkZ, ret = chunkEmptinessMap = new boolean[WorldUtil.getTotalSections(world)]);
        }

        // update emptiness map
        for (int sectionIndex = (emptinessChanges.length - 1); sectionIndex >= 0; --sectionIndex) {
            Boolean valueBoxed = emptinessChanges[sectionIndex];
            if (valueBoxed == null) {
                if (!needsInit) {
                    continue;
                }
                final LevelChunkSection section = this.getChunkSection(chunkX, sectionIndex + this.minSection, chunkZ);
                emptinessChanges[sectionIndex] = valueBoxed = section == null || section.hasOnlyAir() ? Boolean.TRUE : Boolean.FALSE;
            }
            chunkEmptinessMap[sectionIndex] = valueBoxed.booleanValue();
        }

        // now init neighbour nibbles
        for (int sectionIndex = (emptinessChanges.length - 1); sectionIndex >= 0; --sectionIndex) {
            final Boolean valueBoxed = emptinessChanges[sectionIndex];
            final int sectionY = sectionIndex + this.minSection;
            if (valueBoxed == null) {
                continue;
            }

            final boolean empty = valueBoxed.booleanValue();

            if (empty) {
                continue;
            }

            for (int dz = -1; dz <= 1; ++dz) {
                for (int dx = -1; dx <= 1; ++dx) {
                    // if we're not empty, we also need to initialise nibbles
                    // note: if we're unlit, we absolutely do not want to extrude, as light data isn't set up
                    final boolean extrude = (dx | dz) != 0 || !unlit;
                    for (int dy = 1; dy >= -1; --dy) {
                        this.initNibble(dx + chunkX, dy + sectionY, dz + chunkZ, extrude, false);
                    }
                }
            }
        }

        // Solix start - section emptiness as bitmasks
        // For every chunk column within 2 of this chunk, bit (y - minLightSection) of nonEmptyMasks is set when section y
        // is not empty (same sources as the per-section search: the emptiness map, or the section itself when the chunk
        // has no map yet). Whether all sections in the 3x3x3 around a light section are empty is then one bit of the OR
        // of 9 column masks smeared one section up and down, instead of up to 27 lookups for every light section.
        // The emptiness maps and sections do not change below, so the masks can be built up front.
        final boolean useMasks = this.maxLightSection - this.minLightSection + 1 <= 63;
        if (useMasks) {
            for (int dz = -2; dz <= 2; ++dz) {
                for (int dx = -2; dx <= 2; ++dx) {
                    final boolean[] emptinessMap = this.getEmptinessMap(dx + chunkX, dz + chunkZ);
                    long mask = 0L;
                    for (int y = this.minSection; y <= this.maxSection; ++y) {
                        final boolean nonEmpty;
                        if (emptinessMap != null) {
                            nonEmpty = !emptinessMap[y - this.minSection];
                        } else {
                            final LevelChunkSection section = this.getChunkSection(dx + chunkX, y, dz + chunkZ);
                            nonEmpty = section != null && !section.hasOnlyAir();
                        }
                        if (nonEmpty) {
                            mask |= 1L << (y - this.minLightSection);
                        }
                    }
                    this.nonEmptyMasks[(dx + 2) + 5 * (dz + 2)] = mask;
                }
            }
        }
        // Solix end

        // check for de-init and lazy-init
        // lazy init is when chunks are being lit, so at the time they weren't loaded when their neighbours were running
        // init checks.
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                // does this neighbour have 1 radius loaded?
                boolean neighboursLoaded = true;
                neighbour_loaded_search:
                for (int dz2 = -1; dz2 <= 1; ++dz2) {
                    for (int dx2 = -1; dx2 <= 1; ++dx2) {
                        if (this.getEmptinessMap(dx + dx2 + chunkX, dz + dz2 + chunkZ) == null) {
                            neighboursLoaded = false;
                            break neighbour_loaded_search;
                        }
                    }
                }

                // Solix start - non-empty sections in the 3x3 columns around this one, smeared one section up and down
                long nearNonEmpty = 0L;
                if (useMasks) {
                    for (int dz2 = -1; dz2 <= 1; ++dz2) {
                        for (int dx2 = -1; dx2 <= 1; ++dx2) {
                            nearNonEmpty |= this.nonEmptyMasks[(dx + dx2 + 2) + 5 * (dz + dz2 + 2)];
                        }
                    }
                    nearNonEmpty |= (nearNonEmpty << 1) | (nearNonEmpty >>> 1);
                }
                // Solix end

                for (int sectionY = this.maxLightSection; sectionY >= this.minLightSection; --sectionY) {
                    // check neighbours to see if we need to de-init this one
                    // Solix - bitmask test, the per-section search is kept for worlds with more than 63 light sections
                    final boolean allEmpty = useMasks ? ((nearNonEmpty >>> (sectionY - this.minLightSection)) & 1L) == 0L
                            : this.areNeighbourSectionsEmpty(dx + chunkX, sectionY, dz + chunkZ);

                    if (allEmpty & neighboursLoaded) {
                        // can only de-init when neighbours are loaded
                        // de-init is fine to delay, as de-init is just an optimisation - it's not required for lighting
                        // to be correct

                        // all were empty, so de-init
                        this.setNibbleNull(dx + chunkX, sectionY, dz + chunkZ);
                    } else if (!allEmpty) {
                        // must init
                        final boolean extrude = (dx | dz) != 0 || !unlit;
                        this.initNibble(dx + chunkX, sectionY, dz + chunkZ, extrude, false);
                    }
                }
            }
        }

        return ret;
    }

    public final void checkChunkEdges(final LightChunkGetter lightAccess, final int chunkX, final int chunkZ) {
        final long statStart = LightStats.ENABLED ? System.nanoTime() : 0L; // Solix
        this.setupCaches(lightAccess, chunkX * 16 + 7, 128, chunkZ * 16 + 7, true, false);
        try {
            final ChunkAccess chunk = this.getChunkInCache(chunkX, chunkZ);
            if (chunk == null) {
                return;
            }
            this.checkChunkEdges(lightAccess, chunk, this.minLightSection, this.maxLightSection);
            this.updateVisible(lightAccess);
        } finally {
            this.destroyCaches();
            if (LightStats.ENABLED) {
                LightStats.record(LightStats.EDGE_CHECKS, statStart, this); // Solix
            }
        }
    }

    public final void checkChunkEdges(final LightChunkGetter lightAccess, final int chunkX, final int chunkZ, final ShortCollection sections) {
        final long statStart = LightStats.ENABLED ? System.nanoTime() : 0L; // Solix
        this.setupCaches(lightAccess, chunkX * 16 + 7, 128, chunkZ * 16 + 7, true, false);
        try {
            final ChunkAccess chunk = this.getChunkInCache(chunkX, chunkZ);
            if (chunk == null) {
                return;
            }
            this.checkChunkEdges(lightAccess, chunk, sections);
            this.updateVisible(lightAccess);
        } finally {
            this.destroyCaches();
            if (LightStats.ENABLED) {
                LightStats.record(LightStats.EDGE_CHECKS, statStart, this); // Solix
            }
        }
    }

    // subclasses should not initialise caches, as this will always be done by the super call
    // subclasses should not invoke updateVisible, as this will always be done by the super call
    // needsEdgeChecks applies when possibly loading vanilla data, which means we need to validate the current
    // chunks light values with respect to neighbours
    // subclasses should note that the emptiness changes are propagated BEFORE this is called, so this function
    // does not need to detect empty chunks itself (and it should do no handling for them either!)
    protected abstract void lightChunk(final LightChunkGetter lightAccess, final ChunkAccess chunk, final boolean needsEdgeChecks);

    public final void light(final LightChunkGetter lightAccess, final ChunkAccess chunk, final Boolean[] emptySections) {
        final int chunkX = chunk.getPos().x;
        final int chunkZ = chunk.getPos().z;
        final long statStart = LightStats.ENABLED ? System.nanoTime() : 0L; // Solix
        this.setupCaches(lightAccess, chunkX * 16 + 7, 128, chunkZ * 16 + 7, true, true);

        try {
            final SWMRNibbleArray[] nibbles = getFilledEmptyLight(this.maxLightSection - this.minLightSection + 1);
            // force current chunk into cache
            this.setChunkInCache(chunkX, chunkZ, chunk);
            this.setBlocksForChunkInCache(chunkX, chunkZ, chunk.getSections());
            this.setNibblesForChunkInCache(chunkX, chunkZ, nibbles);
            this.setEmptinessMapCache(chunkX, chunkZ, this.getEmptinessMap(chunk));

            final boolean[] ret = this.handleEmptySectionChanges(lightAccess, chunk, emptySections, true);
            if (ret != null) {
                this.setEmptinessMap(chunk, ret);
            }
            this.lightChunk(lightAccess, chunk, true);
            this.setNibbles(chunk, nibbles);
            this.updateVisible(lightAccess);
        } finally {
            this.destroyCaches();
            if (LightStats.ENABLED) {
                LightStats.record(LightStats.CHUNK_LIGHT, statStart, this); // Solix
            }
        }
    }

    public final void relightChunks(final LightChunkGetter lightAccess, final Set<ChunkPos> chunks,
                                    final Consumer<ChunkPos> chunkLightCallback, final IntConsumer onComplete) {
        // it's recommended for maximum performance that the set is ordered according to a BFS from the center of
        // the region of chunks to relight
        // it's required that tickets are added for each chunk to keep them loaded
        final Long2ObjectOpenHashMap<SWMRNibbleArray[]> nibblesByChunk = new Long2ObjectOpenHashMap<>();
        final Long2ObjectOpenHashMap<boolean[]> emptinessMapByChunk = new Long2ObjectOpenHashMap<>();

        final int[] neighbourLightOrder = new int[] {
                // d = 0
                0, 0,
                // d = 1
                -1, 0,
                0, -1,
                1, 0,
                0, 1,
                // d = 2
                -1, 1,
                1, 1,
                -1, -1,
                1, -1,
        };

        int lightCalls = 0;
        final long statStart = LightStats.ENABLED ? System.nanoTime() : 0L; // Solix

        for (final ChunkPos chunkPos : chunks) {
            final int chunkX = chunkPos.x;
            final int chunkZ = chunkPos.z;
            final ChunkAccess chunk = (ChunkAccess)lightAccess.getChunkForLighting(chunkX, chunkZ);
            if (chunk == null || !this.canUseChunk(chunk)) {
                throw new IllegalStateException();
            }

            for (int i = 0, len = neighbourLightOrder.length; i < len; i += 2) {
                final int dx = neighbourLightOrder[i];
                final int dz = neighbourLightOrder[i + 1];
                final int neighbourX = dx + chunkX;
                final int neighbourZ = dz + chunkZ;

                final ChunkAccess neighbour = (ChunkAccess)lightAccess.getChunkForLighting(neighbourX, neighbourZ);
                if (neighbour == null || !this.canUseChunk(neighbour)) {
                    continue;
                }

                if (nibblesByChunk.get(CoordinateUtils.getChunkKey(neighbourX, neighbourZ)) != null) {
                    // lit already called for neighbour, no need to light it now
                    continue;
                }

                // light neighbour chunk
                this.setupEncodeOffset(neighbourX * 16 + 7, 128, neighbourZ * 16 + 7);
                try {
                    // insert all neighbouring chunks for this neighbour that we have data for
                    for (int dz2 = -1; dz2 <= 1; ++dz2) {
                        for (int dx2 = -1; dx2 <= 1; ++dx2) {
                            final int neighbourX2 = neighbourX + dx2;
                            final int neighbourZ2 = neighbourZ + dz2;
                            final long key = CoordinateUtils.getChunkKey(neighbourX2, neighbourZ2);
                            final ChunkAccess neighbour2 = (ChunkAccess)lightAccess.getChunkForLighting(neighbourX2, neighbourZ2);
                            if (neighbour2 == null || !this.canUseChunk(neighbour2)) {
                                continue;
                            }

                            final SWMRNibbleArray[] nibbles = nibblesByChunk.get(key);
                            if (nibbles == null) {
                                // we haven't lit this chunk
                                continue;
                            }

                            this.setChunkInCache(neighbourX2, neighbourZ2, neighbour2);
                            this.setBlocksForChunkInCache(neighbourX2, neighbourZ2, neighbour2.getSections());
                            this.setNibblesForChunkInCache(neighbourX2, neighbourZ2, nibbles);
                            this.setEmptinessMapCache(neighbourX2, neighbourZ2, emptinessMapByChunk.get(key));
                        }
                    }

                    final long key = CoordinateUtils.getChunkKey(neighbourX, neighbourZ);

                    // now insert the neighbour chunk and light it
                    final SWMRNibbleArray[] nibbles = getFilledEmptyLight(this.world);
                    nibblesByChunk.put(key, nibbles);

                    this.setChunkInCache(neighbourX, neighbourZ, neighbour);
                    this.setBlocksForChunkInCache(neighbourX, neighbourZ, neighbour.getSections());
                    this.setNibblesForChunkInCache(neighbourX, neighbourZ, nibbles);

                    final boolean[] neighbourEmptiness = this.handleEmptySectionChanges(lightAccess, neighbour, getEmptySectionsForChunk(neighbour), true);
                    emptinessMapByChunk.put(key, neighbourEmptiness);
                    if (chunks.contains(new ChunkPos(neighbourX, neighbourZ))) {
                        this.setEmptinessMap(neighbour, neighbourEmptiness);
                    }

                    this.lightChunk(lightAccess, neighbour, false);
                } finally {
                    this.destroyCaches();
                }
            }

            // done lighting all neighbours, so the chunk is now fully lit

            // make sure nibbles are fully updated before calling back
            final SWMRNibbleArray[] nibbles = nibblesByChunk.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
            for (final SWMRNibbleArray nibble : nibbles) {
                nibble.updateVisible();
            }

            this.setNibbles(chunk, nibbles);

            for (int y = this.minLightSection; y <= this.maxLightSection; ++y) {
                lightAccess.onLightUpdate(this.skylightPropagator ? LightLayer.SKY : LightLayer.BLOCK, SectionPos.of(chunkX, y, chunkZ));
            }

            // now do callback
            if (chunkLightCallback != null) {
                chunkLightCallback.accept(chunkPos);
            }
            ++lightCalls;
        }

        if (LightStats.ENABLED) {
            LightStats.record(LightStats.RELIGHT, statStart, this); // Solix
        }

        if (onComplete != null) {
            onComplete.accept(lightCalls);
        }
    }

    // contains:
    // lower (6 + 6 + 16) = 28 bits: encoded coordinate position (x | (z << 6) | (y << (6 + 6))))
    // next 4 bits: propagated light level (0, 15]
    // next 6 bits: propagation direction bitset
    // next 4 bits: (Solix, decrease candidates only) the block's attenuation, max(1, opacity)
    // next 4 bits: (Solix, decrease candidates only) the block's emitted light
    // next 13 bits: unused
    // last 5 bits: state flags
    // state flags:
    // whether the increase propagator needs to write the propagated level to the position, used to avoid cascading light
    // updates for block sources
    protected static final long FLAG_WRITE_LEVEL = Long.MIN_VALUE >>> 2;
    // whether the propagation needs to check if its current level is equal to the expected level
    // used only in increase propagation
    protected static final long FLAG_RECHECK_LEVEL = Long.MIN_VALUE >>> 1;
    // whether the propagation needs to consider if its block is conditionally transparent
    protected static final long FLAG_HAS_SIDED_TRANSPARENT_BLOCKS = Long.MIN_VALUE;
    // Solix - targeted decrease: the entry is a block that may have lost its light. It is only darkened when neither its
    // own emission nor a neighbour still provides its level, see performLightDecrease
    protected static final long FLAG_CHECK_SUPPORT = Long.MIN_VALUE >>> 3;
    // Solix - targeted decrease: darken every block this entry's light could have reached, like the classic decrease,
    // without support checks. Set on seeds whose block was a light source itself (see getSeedDecreaseFlags) and passed on
    // to the blocks they darken: below a removed source almost nothing keeps its light, so support checks only cost time
    protected static final long FLAG_DARKEN_ALL = Long.MIN_VALUE >>> 4;

    // Solix start - per-level queues
    // Both queues are split into 16 buckets by light level. With ordered propagation the increase queue is processed from
    // bucket 15 down to 0 (Dial's algorithm): a propagated level is always lower than the level it came from, so each
    // block is written once at its final level, instead of being lit by a weaker source first and overwritten by a
    // stronger one later. The targeted decrease relies on the same order. In classic mode every entry goes into
    // FIFO_BUCKET, which is Starlight's plain FIFO queue.
    protected static final boolean ORDERED = SolixConfig.ORDERED;
    protected static final boolean TARGETED = SolixConfig.TARGETED;
    protected static final int FIFO_BUCKET = 0;

    protected final long[][] increaseQueues = new long[16][];
    protected final int[] increaseQueueLengths = new int[16];
    protected final long[][] decreaseQueues = new long[16][];
    protected final int[] decreaseQueueLengths = new int[16];
    {
        for (int bucket = 0; bucket < 16; ++bucket) {
            this.increaseQueues[bucket] = new long[initialQueueLength(ORDERED, bucket)];
            this.decreaseQueues[bucket] = new long[initialQueueLength(TARGETED, bucket)];
        }
    }

    // counters for LightStats, only written when it is enabled
    long statIncreasePops;
    long statDecreasePops;
    long statCandidatesKept;
    long statCandidatesDarkened;
    long statLightWrites;
    long statSectionsReported;
    long statSectionsSuppressed;

    protected static int increaseBucket(final int level) {
        return ORDERED ? level : FIFO_BUCKET;
    }

    protected static int decreaseBucket(final int level) {
        return TARGETED ? level : FIFO_BUCKET;
    }

    protected final long[] resizeIncreaseQueue(final int bucket) {
        return this.increaseQueues[bucket] = Arrays.copyOf(this.increaseQueues[bucket], this.increaseQueues[bucket].length * 2);
    }

    protected final long[] resizeDecreaseQueue(final int bucket) {
        return this.decreaseQueues[bucket] = Arrays.copyOf(this.decreaseQueues[bucket], this.decreaseQueues[bucket].length * 2);
    }

    protected final void appendToIncreaseQueue(final long value) {
        final int bucket = increaseBucket((int)((value >>> (6 + 6 + 16)) & 0xFL));
        final int idx = this.increaseQueueLengths[bucket]++;
        long[] queue = this.increaseQueues[bucket];
        if (idx >= queue.length) {
            queue = this.resizeIncreaseQueue(bucket);
        }
        queue[idx] = value;
    }

    protected final void appendToDecreaseQueue(final long value) {
        final int bucket = decreaseBucket((int)((value >>> (6 + 6 + 16)) & 0xFL));
        final int idx = this.decreaseQueueLengths[bucket]++;
        long[] queue = this.decreaseQueues[bucket];
        if (idx >= queue.length) {
            queue = this.resizeDecreaseQueue(bucket);
        }
        queue[idx] = value;
    }

    // removes the increase entry that was appended last at this level
    protected final void removeLastFromIncreaseQueue(final int level) {
        --this.increaseQueueLengths[increaseBucket(level)];
    }

    // drops queued work left behind by an update that threw, so a pooled engine does not replay it
    protected final void clearQueues() {
        Arrays.fill(this.increaseQueueLengths, 0);
        Arrays.fill(this.decreaseQueueLengths, 0);
        // Solix (0.3.3) - engines are pooled for the world's lifetime: do not keep a huge queue from one big update
        for (int bucket = 0; bucket < 16; ++bucket) {
            if (this.increaseQueues[bucket].length > MAX_RETAINED_QUEUE) {
                this.increaseQueues[bucket] = new long[initialQueueLength(ORDERED, bucket)];
            }
            if (this.decreaseQueues[bucket].length > MAX_RETAINED_QUEUE) {
                this.decreaseQueues[bucket] = new long[initialQueueLength(TARGETED, bucket)];
            }
        }
    }

    protected static final int MAX_RETAINED_QUEUE = 1 << 16;

    protected static int initialQueueLength(final boolean bucketed, final int bucket) {
        return bucketed ? 512 : (bucket == FIFO_BUCKET ? 16 * 16 * 16 : 16);
    }
    // Solix end - per-level queues

    protected static final AxisDirection[][] OLD_CHECK_DIRECTIONS = new AxisDirection[1 << 6][];
    protected static final int ALL_DIRECTIONS_BITSET = (1 << 6) - 1;
    static {
        for (int i = 0; i < OLD_CHECK_DIRECTIONS.length; ++i) {
            final List<AxisDirection> directions = new ArrayList<>();
            for (int bitset = i, len = Integer.bitCount(i), index = 0; index < len; ++index, bitset ^= IntegerUtil.getTrailingBit(bitset)) {
                directions.add(AXIS_DIRECTIONS[IntegerUtil.trailingZeros(bitset)]);
            }
            OLD_CHECK_DIRECTIONS[i] = directions.toArray(new AxisDirection[0]);
        }
    }

    protected final void performLightIncrease(final LightChunkGetter lightAccess) {
        final BlockGetter world = this.blockAccess;
        final long[][] queues = this.increaseQueues;
        final int[] queueLengths = this.increaseQueueLengths;
        final int decodeOffsetX = -this.encodeOffsetX;
        final int decodeOffsetY = -this.encodeOffsetY;
        final int decodeOffsetZ = -this.encodeOffsetZ;
        final int encodeOffset = this.coordinateOffset;
        final int sectionOffset = this.chunkSectionIndexOffset;

        // Solix - brightest bucket first. Children always go into a lower bucket, in classic mode everything is in
        // FIFO_BUCKET (which grows while it is processed, so the length and array are re-read for every entry)
        for (int bucket = 15; bucket >= 0; --bucket) {
            for (int queueReadIndex = 0; queueReadIndex < queueLengths[bucket];) {
                final long queueValue = queues[bucket][queueReadIndex++];
                if (LightStats.ENABLED) {
                    ++this.statIncreasePops;
                }

                final int posX = ((int)queueValue & 63) + decodeOffsetX;
                final int posZ = (((int)queueValue >>> 6) & 63) + decodeOffsetZ;
                final int posY = (((int)queueValue >>> 12) & ((1 << 16) - 1)) + decodeOffsetY;
                final int propagatedLightLevel = (int)((queueValue >>> (6 + 6 + 16)) & 0xFL);
                final AxisDirection[] checkDirections = OLD_CHECK_DIRECTIONS[(int)((queueValue >>> (6 + 6 + 16 + 4)) & 63L)];

                if ((queueValue & FLAG_RECHECK_LEVEL) != 0L) {
                    if (this.getLightLevel(posX, posY, posZ) != propagatedLightLevel) {
                        // not at the level we expect, so something changed.
                        continue;
                    }
                } else if ((queueValue & FLAG_WRITE_LEVEL) != 0L) {
                    // these are used to restore block sources after a propagation decrease
                    // Solix - never lower a block that a brighter neighbour already lit: that neighbour queued it at the
                    // higher level (brightest-first order makes this the normal case, before it was only order dependent)
                    if (this.getLightLevel(posX, posY, posZ) >= propagatedLightLevel) {
                        continue;
                    }
                    this.setLightLevel(posX, posY, posZ, propagatedLightLevel);
                    if (LightStats.ENABLED) {
                        ++this.statLightWrites;
                    }
                }

                if ((queueValue & FLAG_HAS_SIDED_TRANSPARENT_BLOCKS) == 0L) {
                    // we don't need to worry about our state here.
                    for (final AxisDirection propagate : checkDirections) {
                        final int offX = posX + propagate.x;
                        final int offY = posY + propagate.y;
                        final int offZ = posZ + propagate.z;

                        final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;
                        final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);

                        final SWMRNibbleArray currentNibble = this.nibbleCache[sectionIndex];
                        final int currentLevel;
                        if (currentNibble == null || (currentLevel = currentNibble.getUpdating(localIndex)) >= (propagatedLightLevel - 1)) {
                            continue; // already at the level we want or unloaded
                        }

                        final BlockState blockState = this.getBlockState(sectionIndex, localIndex);
                        if (blockState == null) {
                            continue;
                        }
                        final int opacityCached = ((ExtendedAbstractBlockState)blockState).getOpacityIfCached();
                        if (opacityCached != -1) {
                            final int targetLevel = propagatedLightLevel - Math.max(1, opacityCached);
                            if (targetLevel > currentLevel) {
                                currentNibble.set(localIndex, targetLevel);
                                this.postLightUpdate(offX, offY, offZ);
                                if (LightStats.ENABLED) {
                                    ++this.statLightWrites;
                                }

                                if (targetLevel > 1) {
                                    this.appendToIncreaseQueue(
                                            ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                    | ((targetLevel & 0xFL) << (6 + 6 + 16))
                                                    | (propagate.everythingButTheOppositeDirection << (6 + 6 + 16 + 4))
                                    );
                                    continue;
                                }
                            }
                            continue;
                        } else {
                            this.mutablePos1.set(offX, offY, offZ);
                            long flags = 0;
                            if (((ExtendedAbstractBlockState)blockState).isConditionallyFullOpaque()) {
                                final VoxelShape cullingFace = this.callFaceShape(blockState, this.mutablePos1, propagate.getOpposite().nms);

                                if (Shapes.faceShapeOccludes(Shapes.empty(), cullingFace)) {
                                    continue;
                                }
                                flags |= FLAG_HAS_SIDED_TRANSPARENT_BLOCKS;
                            }

                            final int opacity = this.callLightBlock(blockState, this.mutablePos1);
                            final int targetLevel = propagatedLightLevel - Math.max(1, opacity);
                            if (targetLevel <= currentLevel) {
                                continue;
                            }

                            currentNibble.set(localIndex, targetLevel);
                            this.postLightUpdate(offX, offY, offZ);
                            if (LightStats.ENABLED) {
                                ++this.statLightWrites;
                            }

                            if (targetLevel > 1) {
                                this.appendToIncreaseQueue(
                                        ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                | ((targetLevel & 0xFL) << (6 + 6 + 16))
                                                | (propagate.everythingButTheOppositeDirection << (6 + 6 + 16 + 4))
                                                | (flags)
                                );
                            }
                            continue;
                        }
                    }
                } else {
                    // we actually need to worry about our state here
                    final BlockState fromBlock = this.getBlockState(posX, posY, posZ);
                    this.mutablePos2.set(posX, posY, posZ);
                    for (final AxisDirection propagate : checkDirections) {
                        final int offX = posX + propagate.x;
                        final int offY = posY + propagate.y;
                        final int offZ = posZ + propagate.z;

                        final VoxelShape fromShape = (((ExtendedAbstractBlockState)fromBlock).isConditionallyFullOpaque()) ? this.callFaceShape(fromBlock, this.mutablePos2, propagate.nms) : Shapes.empty();

                        if (fromShape != Shapes.empty() && Shapes.faceShapeOccludes(Shapes.empty(), fromShape)) {
                            continue;
                        }

                        final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;
                        final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);

                        final SWMRNibbleArray currentNibble = this.nibbleCache[sectionIndex];
                        final int currentLevel;

                        if (currentNibble == null || (currentLevel = currentNibble.getUpdating(localIndex)) >= (propagatedLightLevel - 1)) {
                            continue; // already at the level we want
                        }

                        final BlockState blockState = this.getBlockState(sectionIndex, localIndex);
                        if (blockState == null) {
                            continue;
                        }
                        final int opacityCached = ((ExtendedAbstractBlockState)blockState).getOpacityIfCached();
                        if (opacityCached != -1) {
                            final int targetLevel = propagatedLightLevel - Math.max(1, opacityCached);
                            if (targetLevel > currentLevel) {
                                currentNibble.set(localIndex, targetLevel);
                                this.postLightUpdate(offX, offY, offZ);
                                if (LightStats.ENABLED) {
                                    ++this.statLightWrites;
                                }

                                if (targetLevel > 1) {
                                    this.appendToIncreaseQueue(
                                            ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                    | ((targetLevel & 0xFL) << (6 + 6 + 16))
                                                    | (propagate.everythingButTheOppositeDirection << (6 + 6 + 16 + 4))
                                    );
                                    continue;
                                }
                            }
                            continue;
                        } else {
                            this.mutablePos1.set(offX, offY, offZ);
                            long flags = 0;
                            if (((ExtendedAbstractBlockState)blockState).isConditionallyFullOpaque()) {
                                final VoxelShape cullingFace = this.callFaceShape(blockState, this.mutablePos1, propagate.getOpposite().nms);

                                if (Shapes.faceShapeOccludes(fromShape, cullingFace)) {
                                    continue;
                                }
                                flags |= FLAG_HAS_SIDED_TRANSPARENT_BLOCKS;
                            }

                            final int opacity = this.callLightBlock(blockState, this.mutablePos1);
                            final int targetLevel = propagatedLightLevel - Math.max(1, opacity);
                            if (targetLevel <= currentLevel) {
                                continue;
                            }

                            currentNibble.set(localIndex, targetLevel);
                            this.postLightUpdate(offX, offY, offZ);
                            if (LightStats.ENABLED) {
                                ++this.statLightWrites;
                            }

                            if (targetLevel > 1) {
                                this.appendToIncreaseQueue(
                                        ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                | ((targetLevel & 0xFL) << (6 + 6 + 16))
                                                | (propagate.everythingButTheOppositeDirection << (6 + 6 + 16 + 4))
                                                | (flags)
                                );
                            }
                            continue;
                        }
                    }
                }
            }
            queueLengths[bucket] = 0;
        }
    }

    // Solix start - targeted decrease
    // The classic (Seed of Andromeda) decrease darkens every neighbour whose level could have come from the decreased
    // block and then re-lights the area from its border. Placing an opaque block in a lit room therefore darkens and
    // re-lights the whole region behind it, although nearly every block there is also lit along another path of the
    // same length. With TARGETED such neighbours are queued as candidates (FLAG_CHECK_SUPPORT) at their own level, and
    // the decrease queue is processed brightest bucket first. A candidate is only darkened when neither its own emission
    // nor any face-open neighbour still provides its level; otherwise it keeps its light and is queued to re-light its
    // darkened neighbours. This is sound because candidates only ever queue dimmer candidates: when a bucket is
    // processed, every brighter block is final, blocks that lost their light already read 0, and any other non-zero
    // neighbour is a valid light path. The block a light level came from is never stored, it follows from the light
    // values themselves.
    // Below a removed light source nearly every block loses its light, so there the support checks would only cost time:
    // such seeds carry FLAG_DARKEN_ALL and darken classically. Mixing both kinds in one pass is safe, as a classic entry
    // only darkens blocks dimmer than its own bucket, which never invalidates a support decision already made.

    // decrease flags for a changed block whose light was `level` before the change: without a brighter neighbour the
    // block was a light source itself (or the brightest point around it), so darken below it classically
    protected final long getSeedDecreaseFlags(final int worldX, final int worldY, final int worldZ, final int level) {
        if (!TARGETED || level == 0) {
            return 0L;
        }
        for (final AxisDirection direction : AXIS_DIRECTIONS) {
            if (this.getLightLevel(worldX + direction.x, worldY + direction.y, worldZ + direction.z) > level) {
                return 0L;
            }
        }
        return FLAG_DARKEN_ALL;
    }

    protected final boolean hasSupportingNeighbour(final BlockGetter world, final long queueValue, final int posX,
                                                   final int posY, final int posZ, final int neededLevel) {
        if (neededLevel > 15) {
            return false;
        }
        final int sectionOffset = this.chunkSectionIndexOffset;
        // the candidate is conditionally opaque when it carries the sided flag, its faces then matter too
        final BlockState centerState = (queueValue & FLAG_HAS_SIDED_TRANSPARENT_BLOCKS) != 0L ? this.getBlockState(posX, posY, posZ) : null;
        final boolean centerConditional = centerState != null && ((ExtendedAbstractBlockState)centerState).isConditionallyFullOpaque();

        for (final AxisDirection direction : AXIS_DIRECTIONS) {
            final int offX = posX + direction.x;
            final int offY = posY + direction.y;
            final int offZ = posZ + direction.z;

            final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;
            final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);

            if (this.getLightLevel(sectionIndex, localIndex) < neededLevel) {
                continue;
            }

            final BlockState neighbourState = this.getBlockState(sectionIndex, localIndex);
            final boolean neighbourConditional = ((ExtendedAbstractBlockState)neighbourState).isConditionallyFullOpaque();
            if (centerConditional | neighbourConditional) {
                // same face rules as the increase propagating from the neighbour into this block
                final VoxelShape neighbourFace;
                if (neighbourConditional) {
                    this.mutablePos1.set(offX, offY, offZ);
                    neighbourFace = this.callFaceShape(neighbourState, this.mutablePos1, direction.getOpposite().nms);
                } else {
                    neighbourFace = Shapes.empty();
                }
                final VoxelShape centerFace;
                if (centerConditional) {
                    this.mutablePos2.set(posX, posY, posZ);
                    centerFace = this.callFaceShape(centerState, this.mutablePos2, direction.nms);
                } else {
                    centerFace = Shapes.empty();
                }
                if (Shapes.faceShapeOccludes(neighbourFace, centerFace)) {
                    continue;
                }
            }

            return true;
        }

        return false;
    }
    // Solix end - targeted decrease

    protected final void performLightDecrease(final LightChunkGetter lightAccess) {
        final BlockGetter world = this.blockAccess;
        final long[][] queues = this.decreaseQueues;
        final int[] queueLengths = this.decreaseQueueLengths;
        final int decodeOffsetX = -this.encodeOffsetX;
        final int decodeOffsetY = -this.encodeOffsetY;
        final int decodeOffsetZ = -this.encodeOffsetZ;
        final int encodeOffset = this.coordinateOffset;
        final int sectionOffset = this.chunkSectionIndexOffset;
        final int emittedMask = this.emittedLightMask;

        // Solix - brightest bucket first (targeted), or the single FIFO_BUCKET (classic/ordered)
        for (int bucket = 15; bucket >= 0; --bucket) {
            for (int queueReadIndex = 0; queueReadIndex < queueLengths[bucket];) {
                final long queueValue = queues[bucket][queueReadIndex++];
                if (LightStats.ENABLED) {
                    ++this.statDecreasePops;
                }

                final int posX = ((int)queueValue & 63) + decodeOffsetX;
                final int posZ = (((int)queueValue >>> 6) & 63) + decodeOffsetZ;
                final int posY = (((int)queueValue >>> 12) & ((1 << 16) - 1)) + decodeOffsetY;
                final int propagatedLightLevel = (int)((queueValue >>> (6 + 6 + 16)) & 0xF);
                final AxisDirection[] checkDirections = OLD_CHECK_DIRECTIONS[(int)((queueValue >>> (6 + 6 + 16 + 4)) & 63)];

                // Solix start - targeted decrease: decide whether a candidate lost its light
                if (TARGETED && (queueValue & FLAG_CHECK_SUPPORT) != 0L) {
                    final int sectionIndex = (posX >> 4) + 5 * (posZ >> 4) + (5 * 5) * (posY >> 4) + sectionOffset;
                    final int localIndex = (posX & 15) | ((posZ & 15) << 4) | ((posY & 15) << 8);
                    final SWMRNibbleArray nibble = this.nibbleCache[sectionIndex];
                    if (nibble == null || nibble.getUpdating(localIndex) != propagatedLightLevel) {
                        // already darkened through another neighbour
                        continue;
                    }
                    final int emittedLight = (int)((queueValue >>> (6 + 6 + 16 + 4 + 6 + 4)) & 0xFL);
                    final int attenuation = (int)((queueValue >>> (6 + 6 + 16 + 4 + 6)) & 0xFL);
                    if (emittedLight >= propagatedLightLevel
                            || this.hasSupportingNeighbour(world, queueValue, posX, posY, posZ, propagatedLightLevel + attenuation)) {
                        // still lit: keep the level, and re-light the block that queued it (the one direction the
                        // entry does not check). Other darkened neighbours either queue this block again or re-check it
                        // in all directions, and a seed that queued it with light left has its own source entry
                        if (LightStats.ENABLED) {
                            ++this.statCandidatesKept;
                        }
                        this.appendToIncreaseQueue(
                                (queueValue & ((1L << (6 + 6 + 16 + 4)) - 1)) // position and level
                                        | ((~queueValue) & (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4)))
                                        | FLAG_RECHECK_LEVEL
                                        | (queueValue & FLAG_HAS_SIDED_TRANSPARENT_BLOCKS)
                        );
                        continue;
                    }
                    if (LightStats.ENABLED) {
                        ++this.statCandidatesDarkened;
                        ++this.statLightWrites;
                    }
                    if (emittedLight != 0) {
                        // re-propagate source
                        // note: do not set recheck level, or else the propagation will fail
                        this.appendToIncreaseQueue(
                                (queueValue & ((1L << (6 + 6 + 16)) - 1)) // position
                                        | ((emittedLight & 0xFL) << (6 + 6 + 16))
                                        | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
                                        | FLAG_WRITE_LEVEL
                                        | (queueValue & FLAG_HAS_SIDED_TRANSPARENT_BLOCKS)
                        );
                    }
                    nibble.set(localIndex, 0);
                    this.postLightUpdate(posX, posY, posZ);
                    // now darken its neighbours like any decrease entry at this level
                }
                // Solix end - targeted decrease

                if ((queueValue & FLAG_HAS_SIDED_TRANSPARENT_BLOCKS) == 0L) {
                    // we don't need to worry about our state here.
                    for (final AxisDirection propagate : checkDirections) {
                        final int offX = posX + propagate.x;
                        final int offY = posY + propagate.y;
                        final int offZ = posZ + propagate.z;

                        final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;
                        final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);

                        final SWMRNibbleArray currentNibble = this.nibbleCache[sectionIndex];
                        final int lightLevel;

                        if (currentNibble == null || (lightLevel = currentNibble.getUpdating(localIndex)) == 0) {
                            // already at lowest (or unloaded), nothing we can do
                            continue;
                        }

                        final BlockState blockState = this.getBlockState(sectionIndex, localIndex);
                        if (blockState == null) {
                            continue;
                        }
                        final int opacityCached = ((ExtendedAbstractBlockState)blockState).getOpacityIfCached();
                        if (opacityCached != -1) {
                            final int targetLevel = Math.max(0, propagatedLightLevel - Math.max(1, opacityCached));
                            if (lightLevel > targetLevel) {
                                // it looks like another source propagated here, so re-propagate it
                                this.appendToIncreaseQueue(
                                        ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                | ((lightLevel & 0xFL) << (6 + 6 + 16))
                                                | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
                                                | FLAG_RECHECK_LEVEL
                                );
                                continue;
                            }
                            // Solix - opacity is only cached (!= -1) for blocks without positional Forge emission
                            // (see BlockStateBaseMixin), so the position-less emission is the real one here
                            final int emittedLight = blockState.getLightEmission() & emittedMask;
                            // Solix start - targeted decrease: queue as a candidate at its own level instead of darkening it now
                            if (TARGETED && (queueValue & FLAG_DARKEN_ALL) == 0L) {
                                this.appendToDecreaseQueue(
                                        ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                | ((lightLevel & 0xFL) << (6 + 6 + 16))
                                                | ((propagate.everythingButTheOppositeDirection) << (6 + 6 + 16 + 4))
                                                | ((long)Math.min(15, Math.max(1, opacityCached)) << (6 + 6 + 16 + 4 + 6)) // Solix (0.3.4) - 4-bit field, mods may report > 15
                                                | ((long)emittedLight << (6 + 6 + 16 + 4 + 6 + 4))
                                                | FLAG_CHECK_SUPPORT
                                );
                                continue;
                            }
                            // Solix end - targeted decrease
                            if (emittedLight != 0) {
                                // re-propagate source
                                // note: do not set recheck level, or else the propagation will fail
                                this.appendToIncreaseQueue(
                                        ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                | ((emittedLight & 0xFL) << (6 + 6 + 16))
                                                | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
                                                | (((ExtendedAbstractBlockState)blockState).isConditionallyFullOpaque() ? (FLAG_WRITE_LEVEL | FLAG_HAS_SIDED_TRANSPARENT_BLOCKS) : FLAG_WRITE_LEVEL)
                                );
                            }

                            currentNibble.set(localIndex, 0);
                            this.postLightUpdate(offX, offY, offZ);
                            if (LightStats.ENABLED) {
                                ++this.statLightWrites;
                            }

                            if (targetLevel > 0) { // we actually need to propagate 0 just in case we find a neighbour...
                                this.appendToDecreaseQueue(
                                        ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                | ((targetLevel & 0xFL) << (6 + 6 + 16))
                                                | ((propagate.everythingButTheOppositeDirection) << (6 + 6 + 16 + 4))
                                                | (queueValue & FLAG_DARKEN_ALL) // Solix - targeted decrease
                                );
                                continue;
                            }
                            continue;
                        } else {
                            this.mutablePos1.set(offX, offY, offZ);
                            long flags = 0;
                            if (((ExtendedAbstractBlockState)blockState).isConditionallyFullOpaque()) {
                                final VoxelShape cullingFace = this.callFaceShape(blockState, this.mutablePos1, propagate.getOpposite().nms);

                                if (Shapes.faceShapeOccludes(Shapes.empty(), cullingFace)) {
                                    continue;
                                }
                                flags |= FLAG_HAS_SIDED_TRANSPARENT_BLOCKS;
                            }

                            final int opacity = this.callLightBlock(blockState, this.mutablePos1);
                            final int targetLevel = Math.max(0, propagatedLightLevel - Math.max(1, opacity));
                            if (lightLevel > targetLevel) {
                                // it looks like another source propagated here, so re-propagate it
                                this.appendToIncreaseQueue(
                                        ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                | ((lightLevel & 0xFL) << (6 + 6 + 16))
                                                | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
                                                | (FLAG_RECHECK_LEVEL | flags)
                                );
                                continue;
                            }
                            final int emittedLight = this.callEmission(blockState, this.mutablePos1) & emittedMask; // Forge
                            // Solix start - targeted decrease: queue as a candidate at its own level instead of darkening it now
                            if (TARGETED && (queueValue & FLAG_DARKEN_ALL) == 0L) {
                                this.appendToDecreaseQueue(
                                        ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                | ((lightLevel & 0xFL) << (6 + 6 + 16))
                                                | ((propagate.everythingButTheOppositeDirection) << (6 + 6 + 16 + 4))
                                                | ((long)Math.min(15, Math.max(1, opacity)) << (6 + 6 + 16 + 4 + 6)) // Solix (0.3.4) - 4-bit field, mods may report > 15
                                                | ((long)emittedLight << (6 + 6 + 16 + 4 + 6 + 4))
                                                | flags
                                                | FLAG_CHECK_SUPPORT
                                );
                                continue;
                            }
                            // Solix end - targeted decrease
                            if (emittedLight != 0) {
                                // re-propagate source
                                // note: do not set recheck level, or else the propagation will fail
                                this.appendToIncreaseQueue(
                                        ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                | ((emittedLight & 0xFL) << (6 + 6 + 16))
                                                | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
                                                | (flags | FLAG_WRITE_LEVEL)
                                );
                            }

                            currentNibble.set(localIndex, 0);
                            this.postLightUpdate(offX, offY, offZ);
                            if (LightStats.ENABLED) {
                                ++this.statLightWrites;
                            }

                            if (targetLevel > 0) {
                                this.appendToDecreaseQueue(
                                        ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                | ((targetLevel & 0xFL) << (6 + 6 + 16))
                                                | ((propagate.everythingButTheOppositeDirection) << (6 + 6 + 16 + 4))
                                                | (queueValue & FLAG_DARKEN_ALL) // Solix - targeted decrease
                                                | flags
                                );
                            }
                            continue;
                        }
                    }
                } else {
                    // we actually need to worry about our state here
                    final BlockState fromBlock = this.getBlockState(posX, posY, posZ);
                    this.mutablePos2.set(posX, posY, posZ);
                    for (final AxisDirection propagate : checkDirections) {
                        final int offX = posX + propagate.x;
                        final int offY = posY + propagate.y;
                        final int offZ = posZ + propagate.z;

                        final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;
                        final int localIndex = (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8);

                        final VoxelShape fromShape = (((ExtendedAbstractBlockState)fromBlock).isConditionallyFullOpaque()) ? this.callFaceShape(fromBlock, this.mutablePos2, propagate.nms) : Shapes.empty();

                        if (fromShape != Shapes.empty() && Shapes.faceShapeOccludes(Shapes.empty(), fromShape)) {
                            continue;
                        }

                        final SWMRNibbleArray currentNibble = this.nibbleCache[sectionIndex];
                        final int lightLevel;

                        if (currentNibble == null || (lightLevel = currentNibble.getUpdating(localIndex)) == 0) {
                            // already at lowest (or unloaded), nothing we can do
                            continue;
                        }

                        final BlockState blockState = this.getBlockState(sectionIndex, localIndex);
                        if (blockState == null) {
                            continue;
                        }
                        final int opacityCached = ((ExtendedAbstractBlockState)blockState).getOpacityIfCached();
                        if (opacityCached != -1) {
                            final int targetLevel = Math.max(0, propagatedLightLevel - Math.max(1, opacityCached));
                            if (lightLevel > targetLevel) {
                                // it looks like another source propagated here, so re-propagate it
                                this.appendToIncreaseQueue(
                                        ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                | ((lightLevel & 0xFL) << (6 + 6 + 16))
                                                | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
                                                | FLAG_RECHECK_LEVEL
                                );
                                continue;
                            }
                            // Solix - opacity is only cached (!= -1) for blocks without positional Forge emission
                            // (see BlockStateBaseMixin), so the position-less emission is the real one here
                            final int emittedLight = blockState.getLightEmission() & emittedMask;
                            // Solix start - targeted decrease: queue as a candidate at its own level instead of darkening it now
                            if (TARGETED && (queueValue & FLAG_DARKEN_ALL) == 0L) {
                                this.appendToDecreaseQueue(
                                        ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                | ((lightLevel & 0xFL) << (6 + 6 + 16))
                                                | ((propagate.everythingButTheOppositeDirection) << (6 + 6 + 16 + 4))
                                                | ((long)Math.min(15, Math.max(1, opacityCached)) << (6 + 6 + 16 + 4 + 6)) // Solix (0.3.4) - 4-bit field, mods may report > 15
                                                | ((long)emittedLight << (6 + 6 + 16 + 4 + 6 + 4))
                                                | FLAG_CHECK_SUPPORT
                                );
                                continue;
                            }
                            // Solix end - targeted decrease
                            if (emittedLight != 0) {
                                // re-propagate source
                                // note: do not set recheck level, or else the propagation will fail
                                this.appendToIncreaseQueue(
                                        ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                | ((emittedLight & 0xFL) << (6 + 6 + 16))
                                                | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
                                                | (((ExtendedAbstractBlockState)blockState).isConditionallyFullOpaque() ? (FLAG_WRITE_LEVEL | FLAG_HAS_SIDED_TRANSPARENT_BLOCKS) : FLAG_WRITE_LEVEL)
                                );
                            }

                            currentNibble.set(localIndex, 0);
                            this.postLightUpdate(offX, offY, offZ);
                            if (LightStats.ENABLED) {
                                ++this.statLightWrites;
                            }

                            if (targetLevel > 0) { // we actually need to propagate 0 just in case we find a neighbour...
                                this.appendToDecreaseQueue(
                                        ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                | ((targetLevel & 0xFL) << (6 + 6 + 16))
                                                | ((propagate.everythingButTheOppositeDirection) << (6 + 6 + 16 + 4))
                                                | (queueValue & FLAG_DARKEN_ALL) // Solix - targeted decrease
                                );
                                continue;
                            }
                            continue;
                        } else {
                            this.mutablePos1.set(offX, offY, offZ);
                            long flags = 0;
                            if (((ExtendedAbstractBlockState)blockState).isConditionallyFullOpaque()) {
                                final VoxelShape cullingFace = this.callFaceShape(blockState, this.mutablePos1, propagate.getOpposite().nms);

                                if (Shapes.faceShapeOccludes(fromShape, cullingFace)) {
                                    continue;
                                }
                                flags |= FLAG_HAS_SIDED_TRANSPARENT_BLOCKS;
                            }

                            final int opacity = this.callLightBlock(blockState, this.mutablePos1);
                            final int targetLevel = Math.max(0, propagatedLightLevel - Math.max(1, opacity));
                            if (lightLevel > targetLevel) {
                                // it looks like another source propagated here, so re-propagate it
                                this.appendToIncreaseQueue(
                                        ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                | ((lightLevel & 0xFL) << (6 + 6 + 16))
                                                | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
                                                | (FLAG_RECHECK_LEVEL | flags)
                                );
                                continue;
                            }
                            final int emittedLight = this.callEmission(blockState, this.mutablePos1) & emittedMask; // Forge
                            // Solix start - targeted decrease: queue as a candidate at its own level instead of darkening it now
                            if (TARGETED && (queueValue & FLAG_DARKEN_ALL) == 0L) {
                                this.appendToDecreaseQueue(
                                        ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                | ((lightLevel & 0xFL) << (6 + 6 + 16))
                                                | ((propagate.everythingButTheOppositeDirection) << (6 + 6 + 16 + 4))
                                                | ((long)Math.min(15, Math.max(1, opacity)) << (6 + 6 + 16 + 4 + 6)) // Solix (0.3.4) - 4-bit field, mods may report > 15
                                                | ((long)emittedLight << (6 + 6 + 16 + 4 + 6 + 4))
                                                | flags
                                                | FLAG_CHECK_SUPPORT
                                );
                                continue;
                            }
                            // Solix end - targeted decrease
                            if (emittedLight != 0) {
                                // re-propagate source
                                // note: do not set recheck level, or else the propagation will fail
                                this.appendToIncreaseQueue(
                                        ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                | ((emittedLight & 0xFL) << (6 + 6 + 16))
                                                | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
                                                | (flags | FLAG_WRITE_LEVEL)
                                );
                            }

                            currentNibble.set(localIndex, 0);
                            this.postLightUpdate(offX, offY, offZ);
                            if (LightStats.ENABLED) {
                                ++this.statLightWrites;
                            }

                            if (targetLevel > 0) { // we actually need to propagate 0 just in case we find a neighbour...
                                this.appendToDecreaseQueue(
                                        ((offX + (offZ << 6) + (offY << 12) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                                                | ((targetLevel & 0xFL) << (6 + 6 + 16))
                                                | ((propagate.everythingButTheOppositeDirection) << (6 + 6 + 16 + 4))
                                                | (queueValue & FLAG_DARKEN_ALL) // Solix - targeted decrease
                                                | flags
                                );
                            }
                            continue;
                        }
                    }
                }
            }
            queueLengths[bucket] = 0;
        }

        // propagate sources we clobbered
        this.performLightIncrease(lightAccess);
    }
}
