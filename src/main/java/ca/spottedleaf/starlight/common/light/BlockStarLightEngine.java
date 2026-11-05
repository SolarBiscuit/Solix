package ca.spottedleaf.starlight.common.light;

import ca.spottedleaf.starlight.common.blockstate.ExtendedAbstractBlockState;
import ca.spottedleaf.starlight.common.blockstate.PositionalEmission;
import ca.spottedleaf.starlight.common.chunk.ExtendedChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class BlockStarLightEngine extends StarLightEngine {

    public BlockStarLightEngine(final Level world, final boolean isClientSide, final LightChunkGetter lightAccess) {
        super(false, world, isClientSide, lightAccess);
    }

    @Override
    protected boolean[] getEmptinessMap(final ChunkAccess chunk) {
        return ((ExtendedChunk)chunk).getBlockEmptinessMap();
    }

    @Override
    protected void setEmptinessMap(final ChunkAccess chunk, final boolean[] to) {
        ((ExtendedChunk)chunk).setBlockEmptinessMap(to);
    }

    @Override
    protected SWMRNibbleArray[] getNibblesOnChunk(final ChunkAccess chunk) {
        return ((ExtendedChunk)chunk).getBlockNibbles();
    }

    @Override
    protected void setNibbles(final ChunkAccess chunk, final SWMRNibbleArray[] to) {
        ((ExtendedChunk)chunk).setBlockNibbles(to);
    }

    @Override
    protected boolean canUseChunk(final ChunkAccess chunk) {
        return chunk.getStatus().isOrAfter(ChunkStatus.LIGHT) && (this.isClientSide || chunk.isLightCorrect());
    }

    @Override
    protected void setNibbleNull(final int chunkX, final int chunkY, final int chunkZ) {
        final SWMRNibbleArray nibble = this.getNibbleFromCache(chunkX, chunkY, chunkZ);
        if (nibble != null) {
            // de-initialisation is not as straightforward as with sky data, since deinit of block light is typically
            // because a block was removed - which can decrease light. with sky data, block breaking can only result
            // in increases, and thus the existing sky block check will actually correctly propagate light through
            // a null section. so in order to propagate decreases correctly, we can do a couple of things: not remove
            // the data section, or do edge checks on ALL axis (x, y, z). however I do not want edge checks running
            // for clients at all, as they are expensive. so we don't remove the section, but to maintain the appearence
            // of vanilla data management we "hide" them.
            nibble.setHidden();
        }
    }

    @Override
    protected void initNibble(final int chunkX, final int chunkY, final int chunkZ, final boolean extrude, final boolean initRemovedNibbles) {
        if (chunkY < this.minLightSection || chunkY > this.maxLightSection || this.getChunkInCache(chunkX, chunkZ) == null) {
            return;
        }

        final SWMRNibbleArray nibble = this.getNibbleFromCache(chunkX, chunkY, chunkZ);
        if (nibble == null) {
            if (!initRemovedNibbles) {
                throw new IllegalStateException();
            } else {
                this.setNibbleInCache(chunkX, chunkY, chunkZ, new SWMRNibbleArray());
            }
        } else {
            nibble.setNonNull();
        }
    }

    @Override
    protected final void checkBlock(final LightChunkGetter lightAccess, final int worldX, final int worldY, final int worldZ) {
        // blocks can change opacity
        // blocks can change emitted light
        // blocks can change direction of propagation

        final int encodeOffset = this.coordinateOffset;
        final int emittedMask = this.emittedLightMask;

        final int currentLevel = this.getLightLevel(worldX, worldY, worldZ);
        final BlockState blockState = this.getBlockState(worldX, worldY, worldZ);
        this.checkBlockPos.set(worldX, worldY, worldZ);
        final int emittedLevel = this.callEmission(blockState, this.checkBlockPos) & emittedMask; // Forge

        this.setLightLevel(worldX, worldY, worldZ, emittedLevel);
        // this accounts for change in emitted light that would cause an increase
        if (emittedLevel != 0) {
            this.appendToIncreaseQueue(
                    ((worldX + (worldZ << 6) + (worldY << (6 + 6)) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                            | (emittedLevel & 0xFL) << (6 + 6 + 16)
                            | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
                            | (((ExtendedAbstractBlockState)blockState).isConditionallyFullOpaque() ? FLAG_HAS_SIDED_TRANSPARENT_BLOCKS : 0)
            );
        }
        // this also accounts for a change in emitted light that would cause a decrease
        // this also accounts for the change of direction of propagation (i.e old block was full transparent, new block is full opaque or vice versa)
        // as it checks all neighbours (even if current level is 0)
        this.appendToDecreaseQueue(
                ((worldX + (worldZ << 6) + (worldY << (6 + 6)) + encodeOffset) & ((1L << (6 + 6 + 16)) - 1))
                        | (currentLevel & 0xFL) << (6 + 6 + 16)
                        | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
                        | this.getSeedDecreaseFlags(worldX, worldY, worldZ, currentLevel) // Solix - targeted decrease
                        // always keep sided transparent false here, new block might be conditionally transparent which would
                        // prevent us from decreasing sources in the directions where the new block is opaque
                        // if it turns out we were wrong to de-propagate the source, the re-propagate logic WILL always
                        // catch that and fix it.
        );
        // re-propagating neighbours (done by the decrease queue) will also account for opacity changes in this block
    }

    protected final BlockPos.MutableBlockPos recalcCenterPos = new BlockPos.MutableBlockPos();
    protected final BlockPos.MutableBlockPos recalcNeighbourPos = new BlockPos.MutableBlockPos();

    @Override
    protected int calculateLightValue(final LightChunkGetter lightAccess, final int worldX, final int worldY, final int worldZ,
                                      final int expect) {
        final BlockState centerState = this.getBlockState(worldX, worldY, worldZ);
        this.recalcCenterPos.set(worldX, worldY, worldZ); // Forge
        int level = this.callEmission(centerState, this.recalcCenterPos) & 0xF;

        if (level >= (15 - 1) || level > expect) {
            return level;
        }

        final int sectionOffset = this.chunkSectionIndexOffset;
        final BlockState conditionallyOpaqueState;
        int opacity = ((ExtendedAbstractBlockState)centerState).getOpacityIfCached();

        if (opacity == -1) {
            // Forge
            opacity = this.callLightBlock(centerState, this.recalcCenterPos);
            if (((ExtendedAbstractBlockState)centerState).isConditionallyFullOpaque()) {
                conditionallyOpaqueState = centerState;
            } else {
                conditionallyOpaqueState = null;
            }
        } else if (opacity >= 15) {
            return level;
        } else {
            conditionallyOpaqueState = null;
        }
        opacity = Math.max(1, opacity);

        for (final AxisDirection direction : AXIS_DIRECTIONS) {
            final int offX = worldX + direction.x;
            final int offY = worldY + direction.y;
            final int offZ = worldZ + direction.z;

            final int sectionIndex = (offX >> 4) + 5 * (offZ >> 4) + (5 * 5) * (offY >> 4) + sectionOffset;

            final int neighbourLevel = this.getLightLevel(sectionIndex, (offX & 15) | ((offZ & 15) << 4) | ((offY & 15) << 8));

            if ((neighbourLevel - 1) <= level) {
                // don't need to test transparency, we know it wont affect the result.
                continue;
            }

            final BlockState neighbourState = this.getBlockState(offX, offY, offZ);
            if (((ExtendedAbstractBlockState)neighbourState).isConditionallyFullOpaque()) {
                // here the block can be conditionally opaque (i.e light cannot propagate from it), so we need to test that
                // we don't read the blockstate because most of the time this is false, so using the faster
                // known transparency lookup results in a net win
                this.recalcNeighbourPos.set(offX, offY, offZ);
                final VoxelShape neighbourFace = this.callFaceShape(neighbourState, this.recalcNeighbourPos, direction.opposite.nms);
                final VoxelShape thisFace = conditionallyOpaqueState == null ? Shapes.empty() : this.callFaceShape(conditionallyOpaqueState, this.recalcCenterPos, direction.nms);
                if (Shapes.faceShapeOccludes(thisFace, neighbourFace)) {
                    // not allowed to propagate
                    continue;
                }
            }

            // passed transparency,

            final int calculated = neighbourLevel - opacity;
            level = Math.max(calculated, level);
            if (level > expect) {
                return level;
            }
        }

        return level;
    }

    @Override
    protected void propagateBlockChanges(final LightChunkGetter lightAccess, final ChunkAccess atChunk, final Set<BlockPos> positions) {
        for (final BlockPos pos : positions) {
            this.checkBlock(lightAccess, pos.getX(), pos.getY(), pos.getZ());
        }

        this.performLightDecrease(lightAccess);
    }

    // Solix
    private static boolean mayEmitLight(final BlockState state) {
        return state.getLightEmission() != 0 || PositionalEmission.isPositionAware(state);
    }

    /**
     * Solix (0.3.7): finds the chunk's light sources and queues them for propagation, in one pass over the sections.
     * Before, {@code getSources} collected a {@code List<BlockPos>} (one BlockPos per source) that was walked a second
     * time; the order of the sources, the emission values and the light level comparisons are unchanged.
     */
    private void queueSources(final ChunkAccess chunk) {
        final int emittedMask = this.emittedLightMask;
        final int offX = chunk.getPos().x << 4;
        final int offZ = chunk.getPos().z << 4;

        final LevelChunkSection[] sections = chunk.getSections();
        for (int sectionY = this.minSection; sectionY <= this.maxSection; ++sectionY) {
            final LevelChunkSection section = sections[sectionY - this.minSection];
            if (section == null || section.hasOnlyAir()) {
                // no sources in empty sections
                continue;
            }
            // Solix start - palette pre-check: skip sections that cannot contain a light source. A state can only emit
            // light if its position-less emission is non-zero, or if its block overrides Forge's positional emission
            // (then we cannot know without asking at the position, so scan the section as before).
            if (!section.maybeHas(BlockStarLightEngine::mayEmitLight)) {
                continue;
            }
            // Solix end - palette pre-check
            // Forge - need to check each state getLightEmission
            final PalettedContainer<BlockState> states = section.states;
            final int offY = sectionY << 4;

            for (int index = 0; index < (16 * 16 * 16); ++index) {
                final BlockState state = states.get(index);
                // index = x | (z << 4) | (y << 8)
                final int worldX = offX | (index & 15);
                final int worldY = offY | (index >>> 8);
                final int worldZ = offZ | ((index >>> 4) & 15);

                final int emittedLight;
                // Solix (0.3.3) - a cached opacity means the block has no positional emission (see BlockStateBaseMixin),
                // so the plain field is the real emission; only the others need Forge's positional call
                if (((ExtendedAbstractBlockState)state).getOpacityIfCached() != -1) {
                    if (state.getLightEmission() <= 0) {
                        continue;
                    }
                    emittedLight = state.getLightEmission() & emittedMask;
                } else {
                    // Forge start - use correct getLightEmission
                    this.mutablePos1.set(worldX, worldY, worldZ);
                    final int emission = this.callEmission(state, this.mutablePos1);
                    if (emission <= 0) {
                        continue;
                    }
                    emittedLight = emission & emittedMask;
                    // Forge end - use correct getLightEmission
                }

                if (emittedLight <= this.getLightLevel(worldX, worldY, worldZ)) {
                    // some other source is brighter
                    continue;
                }

                this.appendToIncreaseQueue(
                        ((worldX + (worldZ << 6) + (worldY << (6 + 6)) + this.coordinateOffset) & ((1L << (6 + 6 + 16)) - 1))
                                | (emittedLight & 0xFL) << (6 + 6 + 16)
                                | (((long)ALL_DIRECTIONS_BITSET) << (6 + 6 + 16 + 4))
                                | (((ExtendedAbstractBlockState)state).isConditionallyFullOpaque() ? FLAG_HAS_SIDED_TRANSPARENT_BLOCKS : 0)
                );

                // propagation wont set this for us
                this.setLightLevel(worldX, worldY, worldZ, emittedLight);
            }
        }
    }

    @Override
    public void lightChunk(final LightChunkGetter lightAccess, final ChunkAccess chunk, final boolean needsEdgeChecks) {
        // setup sources
        this.queueSources(chunk);

        if (needsEdgeChecks) {
            // not required to propagate here, but this will reduce the hit of the edge checks
            this.performLightIncrease(lightAccess);

            // verify neighbour edges
            this.checkChunkEdges(lightAccess, chunk, this.minLightSection, this.maxLightSection);
        } else {
            this.propagateNeighbourLevels(lightAccess, chunk, this.minLightSection, this.maxLightSection);

            this.performLightIncrease(lightAccess);
        }
    }
}
