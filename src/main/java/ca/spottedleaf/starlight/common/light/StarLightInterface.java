package ca.spottedleaf.starlight.common.light;

import ca.spottedleaf.starlight.common.chunk.ExtendedChunk;
import ca.spottedleaf.starlight.common.thread.GlobalExecutors;
import ca.spottedleaf.starlight.common.thread.SchedulingUtil;
import ca.spottedleaf.starlight.common.util.CoordinateUtils;
import ca.spottedleaf.starlight.common.util.WorldUtil;
import ca.spottedleaf.starlight.common.world.ExtendedWorld;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMaps;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortCollection;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class StarLightInterface {

    private static final Logger LOGGER = LogUtils.getLogger(); // Solix

    public static final TicketType<ChunkPos> CHUNK_WORK_TICKET = TicketType.create("starlight_chunk_work_ticket", (p1, p2) -> Long.compare(p1.toLong(), p2.toLong()));

    /**
     * Can be {@code null}, indicating the light is all empty.
     */
    protected final Level world;
    protected final LightChunkGetter lightAccess;

    protected final ArrayDeque<SkyStarLightEngine> cachedSkyPropagators;
    protected final ArrayDeque<BlockStarLightEngine> cachedBlockPropagators;

    protected final LightQueue lightQueue = new LightQueue(this);

    protected final LayerLightEventListener skyReader;
    protected final LayerLightEventListener blockReader;
    protected final boolean isClientSide;

    protected final int minSection;
    protected final int maxSection;
    protected final int minLightSection;
    protected final int maxLightSection;

    public final LevelLightEngine lightEngine;

    private final boolean hasBlockLight;
    private final boolean hasSkyLight;

    public StarLightInterface(final LightChunkGetter lightAccess, final boolean hasSkyLight, final boolean hasBlockLight, final LevelLightEngine lightEngine) {
        this.lightAccess = lightAccess;
        this.world = lightAccess == null ? null : (Level)lightAccess.getLevel();
        this.cachedSkyPropagators = hasSkyLight && lightAccess != null ? new ArrayDeque<>() : null;
        this.cachedBlockPropagators = hasBlockLight && lightAccess != null ? new ArrayDeque<>() : null;
        this.isClientSide = !(this.world instanceof ServerLevel);
        if (this.world == null) {
            this.minSection = -4;
            this.maxSection = 19;
            this.minLightSection = -5;
            this.maxLightSection = 20;
        } else {
            this.minSection = WorldUtil.getMinSection(this.world);
            this.maxSection = WorldUtil.getMaxSection(this.world);
            this.minLightSection = WorldUtil.getMinLightSection(this.world);
            this.maxLightSection = WorldUtil.getMaxLightSection(this.world);
        }
        this.lightEngine = lightEngine;
        this.hasBlockLight = hasBlockLight;
        this.hasSkyLight = hasSkyLight;
        this.skyReader = !hasSkyLight ? LayerLightEventListener.DummyLightLayerEventListener.INSTANCE : new LayerLightEventListener() {
            @Override
            public void checkBlock(final BlockPos blockPos) {
                StarLightInterface.this.lightEngine.checkBlock(blockPos.immutable());
            }

            @Override
            public void propagateLightSources(final ChunkPos chunkPos) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean hasLightWork() {
                // not really correct...
                return StarLightInterface.this.hasUpdates();
            }

            @Override
            public int runLightUpdates() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setLightEnabled(final ChunkPos chunkPos, final boolean bl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DataLayer getDataLayerData(final SectionPos pos) {
                final ChunkAccess chunk = StarLightInterface.this.getAnyChunkNow(pos.getX(), pos.getZ());
                if (chunk == null || (!StarLightInterface.this.isClientSide && !chunk.isLightCorrect()) || !chunk.getStatus().isOrAfter(ChunkStatus.LIGHT)) {
                    return null;
                }

                final int sectionY = pos.getY();

                if (sectionY > StarLightInterface.this.maxLightSection || sectionY < StarLightInterface.this.minLightSection) {
                    return null;
                }

                if (((ExtendedChunk)chunk).getSkyEmptinessMap() == null) {
                    return null;
                }

                return ((ExtendedChunk)chunk).getSkyNibbles()[sectionY - StarLightInterface.this.minLightSection].toVanillaNibble();
            }

            @Override
            public int getLightValue(final BlockPos blockPos) {
                return StarLightInterface.this.getSkyLightValue(blockPos, StarLightInterface.this.getAnyChunkNow(blockPos.getX() >> 4, blockPos.getZ() >> 4));
            }

            @Override
            public void updateSectionStatus(final SectionPos pos, final boolean notReady) {
                StarLightInterface.this.sectionChange(pos, notReady);
            }
        };
        this.blockReader = !hasBlockLight ? LayerLightEventListener.DummyLightLayerEventListener.INSTANCE : new LayerLightEventListener() {
            @Override
            public void checkBlock(final BlockPos blockPos) {
                StarLightInterface.this.lightEngine.checkBlock(blockPos.immutable());
            }

            @Override
            public void propagateLightSources(final ChunkPos chunkPos) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean hasLightWork() {
                // not really correct...
                return StarLightInterface.this.hasUpdates();
            }

            @Override
            public int runLightUpdates() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setLightEnabled(final ChunkPos chunkPos, final boolean bl) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DataLayer getDataLayerData(final SectionPos pos) {
                final ChunkAccess chunk = StarLightInterface.this.getAnyChunkNow(pos.getX(), pos.getZ());

                if (chunk == null || pos.getY() < StarLightInterface.this.minLightSection || pos.getY() > StarLightInterface.this.maxLightSection) {
                    return null;
                }

                return ((ExtendedChunk)chunk).getBlockNibbles()[pos.getY() - StarLightInterface.this.minLightSection].toVanillaNibble();
            }

            @Override
            public int getLightValue(final BlockPos blockPos) {
                return StarLightInterface.this.getBlockLightValue(blockPos, StarLightInterface.this.getAnyChunkNow(blockPos.getX() >> 4, blockPos.getZ() >> 4));
            }

            @Override
            public void updateSectionStatus(final SectionPos pos, final boolean notReady) {
                StarLightInterface.this.sectionChange(pos, notReady);
            }
        };
    }

    public boolean hasSkyLight() {
        return this.hasSkyLight;
    }

    public boolean hasBlockLight() {
        return this.hasBlockLight;
    }

    public int getSkyLightValue(final BlockPos blockPos, final ChunkAccess chunk) {
        if (!this.hasSkyLight) {
            return 0;
        }
        final int x = blockPos.getX();
        int y = blockPos.getY();
        final int z = blockPos.getZ();

        final int minSection = this.minSection;
        final int maxSection = this.maxSection;
        final int minLightSection = this.minLightSection;
        final int maxLightSection = this.maxLightSection;

        if (chunk == null || (!this.isClientSide && !chunk.isLightCorrect()) || !chunk.getStatus().isOrAfter(ChunkStatus.LIGHT)) {
            return 15;
        }

        int sectionY = y >> 4;

        if (sectionY > maxLightSection) {
            return 15;
        }

        if (sectionY < minLightSection) {
            sectionY = minLightSection;
            y = sectionY << 4;
        }

        final SWMRNibbleArray[] nibbles = ((ExtendedChunk)chunk).getSkyNibbles();
        final SWMRNibbleArray immediate = nibbles[sectionY - minLightSection];

        if (!immediate.isNullNibbleVisible()) {
            return immediate.getVisible(x, y, z);
        }

        final boolean[] emptinessMap = ((ExtendedChunk)chunk).getSkyEmptinessMap();

        if (emptinessMap == null) {
            return 15;
        }

        // are we above this chunk's lowest empty section?
        int lowestY = minLightSection - 1;
        for (int currY = maxSection; currY >= minSection; --currY) {
            if (emptinessMap[currY - minSection]) {
                continue;
            }

            // should always be full lit here
            lowestY = currY;
            break;
        }

        if (sectionY > lowestY) {
            return 15;
        }

        // this nibble is going to depend solely on the skylight data above it
        // find first non-null data above (there does exist one, as we just found it above)
        for (int currY = sectionY + 1; currY <= maxLightSection; ++currY) {
            final SWMRNibbleArray nibble = nibbles[currY - minLightSection];
            if (!nibble.isNullNibbleVisible()) {
                return nibble.getVisible(x, 0, z);
            }
        }

        // should never reach here
        return 15;
    }

    public int getBlockLightValue(final BlockPos blockPos, final ChunkAccess chunk) {
        if (!this.hasBlockLight) {
            return 0;
        }
        final int y = blockPos.getY();
        final int cy = y >> 4;

        final int minLightSection = this.minLightSection;
        final int maxLightSection = this.maxLightSection;

        if (cy < minLightSection || cy > maxLightSection) {
            return 0;
        }

        if (chunk == null) {
            return 0;
        }

        final SWMRNibbleArray nibble = ((ExtendedChunk)chunk).getBlockNibbles()[cy - minLightSection];
        return nibble.getVisible(blockPos.getX(), y, blockPos.getZ());
    }

    public int getRawBrightness(final BlockPos pos, final int ambientDarkness) {
        final ChunkAccess chunk = this.getAnyChunkNow(pos.getX() >> 4, pos.getZ() >> 4);

        final int sky = this.getSkyLightValue(pos, chunk) - ambientDarkness;
        // Don't fetch the block light level if the skylight level is 15, since the value will never be higher.
        if (sky == 15) {
            return 15;
        }
        final int block = this.getBlockLightValue(pos, chunk);
        return Math.max(sky, block);
    }

    public LayerLightEventListener getSkyReader() {
        return this.skyReader;
    }

    public LayerLightEventListener getBlockReader() {
        return this.blockReader;
    }

    // Solix start - from ScalableLux 77b85a8: data for LevelLightEngine#getDebugSectionType (Voxy needs LIGHT_AND_DATA)
    public boolean hasSectionSkyLight(final SectionPos sectionPos) {
        if (!this.hasSkyLight) {
            return false;
        }
        return hasSectionLight(sectionPos, true);
    }

    public boolean hasSectionBlockLight(final SectionPos sectionPos) {
        if (!this.hasBlockLight) {
            return false;
        }
        return hasSectionLight(sectionPos, false);
    }

    private boolean hasSectionLight(final SectionPos sectionPos, final boolean sky) {
        final int index = sectionPos.y() - this.minLightSection;
        if (sectionPos.y() < this.minLightSection || sectionPos.y() > this.maxLightSection) {
            return false;
        }
        final ChunkAccess chunk = this.getAnyChunkNow(sectionPos.x(), sectionPos.z());
        if (chunk == null) {
            return false;
        }
        // Solix (0.3.2) - only report data when getDataLayerData would return it: the readers return null for hidden
        // nibbles, and the sky reader also for chunks that are not lit yet or have no emptiness map
        if (sky && ((!this.isClientSide && !chunk.isLightCorrect()) || !chunk.getStatus().isOrAfter(ChunkStatus.LIGHT)
                || ((ExtendedChunk)chunk).getSkyEmptinessMap() == null)) {
            return false;
        }
        final SWMRNibbleArray[] nibbles = sky ? ((ExtendedChunk)chunk).getSkyNibbles() : ((ExtendedChunk)chunk).getBlockNibbles();
        return nibbles != null && !nibbles[index].isNullNibbleVisible() && !nibbles[index].isHiddenVisible();
    }
    // Solix end

    public boolean isClientSide() {
        return this.isClientSide;
    }

    public ChunkAccess getAnyChunkNow(final int chunkX, final int chunkZ) {
        if (this.world == null) {
            // empty world
            return null;
        }
        return ((ExtendedWorld)this.world).getAnyChunkImmediately(chunkX, chunkZ);
    }

    public boolean hasUpdates() {
        // Solix (0.3.2) - also count work a parallel light worker has taken off the queue but not finished, so that
        // ChunkMap#hasWork (server stop, world close) waits for it instead of saving chunks mid-update
        // (0.3.3: both checks are lock-free, the main thread calls this for every task it polls)
        return !this.lightQueue.isEmpty() || this.inFlightTasks.get() != 0;
    }

    public Level getWorld() {
        return this.world;
    }

    public LightChunkGetter getLightAccess() {
        return this.lightAccess;
    }

    protected final SkyStarLightEngine getSkyLightEngine() {
        if (this.cachedSkyPropagators == null) {
            return null;
        }
        final SkyStarLightEngine ret;
        synchronized (this.cachedSkyPropagators) {
            ret = this.cachedSkyPropagators.pollFirst();
        }

        if (ret == null) {
            return new SkyStarLightEngine(this.world, this.isClientSide, this.lightAccess); // Solix (0.3.3) - one client/server decision
        }
        return ret;
    }

    protected final void releaseSkyLightEngine(final SkyStarLightEngine engine) {
        if (this.cachedSkyPropagators == null) {
            return;
        }
        synchronized (this.cachedSkyPropagators) {
            this.cachedSkyPropagators.addFirst(engine);
        }
    }

    protected final BlockStarLightEngine getBlockLightEngine() {
        if (this.cachedBlockPropagators == null) {
            return null;
        }
        final BlockStarLightEngine ret;
        synchronized (this.cachedBlockPropagators) {
            ret = this.cachedBlockPropagators.pollFirst();
        }

        if (ret == null) {
            return new BlockStarLightEngine(this.world, this.isClientSide, this.lightAccess); // Solix (0.3.3)
        }
        return ret;
    }

    protected final void releaseBlockLightEngine(final BlockStarLightEngine engine) {
        if (this.cachedBlockPropagators == null) {
            return;
        }
        synchronized (this.cachedBlockPropagators) {
            this.cachedBlockPropagators.addFirst(engine);
        }
    }

    public LightQueue.ChunkTasks blockChange(final BlockPos pos) {
        if (this.world == null || pos.getY() < WorldUtil.getMinBlockY(this.world) || pos.getY() > WorldUtil.getMaxBlockY(this.world)) { // empty world
            return null;
        }

        return this.lightQueue.queueBlockChange(pos);
    }

    public LightQueue.ChunkTasks sectionChange(final SectionPos pos, final boolean newEmptyValue) {
        if (this.world == null) { // empty world
            return null;
        }

        return this.lightQueue.queueSectionChange(pos, newEmptyValue);
    }

    public void forceLoadInChunk(final ChunkAccess chunk, final Boolean[] emptySections) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();

        try {
            if (skyEngine != null) {
                skyEngine.forceHandleEmptySectionChanges(this.lightAccess, chunk, emptySections);
            }
            if (blockEngine != null) {
                blockEngine.forceHandleEmptySectionChanges(this.lightAccess, chunk, emptySections);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    // Solix (0.3.4) - not called by Solix or any RatPack mod, but kept: Starlight API in Starlight's packages that other mods may call
    public void loadInChunk(final int chunkX, final int chunkZ, final Boolean[] emptySections) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();

        try {
            if (skyEngine != null) {
                skyEngine.handleEmptySectionChanges(this.lightAccess, chunkX, chunkZ, emptySections);
            }
            if (blockEngine != null) {
                blockEngine.handleEmptySectionChanges(this.lightAccess, chunkX, chunkZ, emptySections);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    public void lightChunk(final ChunkAccess chunk, final Boolean[] emptySections) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();

        try {
            if (skyEngine != null) {
                skyEngine.light(this.lightAccess, chunk, emptySections);
            }
            if (blockEngine != null) {
                blockEngine.light(this.lightAccess, chunk, emptySections);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    // Solix (0.3.4) - not called by Solix or any RatPack mod, but kept: Starlight API in Starlight's packages that other mods may call
    public void relightChunks(final Set<ChunkPos> chunks, final Consumer<ChunkPos> chunkLightCallback,
                              final IntConsumer onComplete) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();

        try {
            if (skyEngine != null) {
                skyEngine.relightChunks(this.lightAccess, chunks, blockEngine == null ? chunkLightCallback : null,
                        blockEngine == null ? onComplete : null);
            }
            if (blockEngine != null) {
                blockEngine.relightChunks(this.lightAccess, chunks, chunkLightCallback, onComplete);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    public void checkChunkEdges(final int chunkX, final int chunkZ) {
        this.checkSkyEdges(chunkX, chunkZ);
        this.checkBlockEdges(chunkX, chunkZ);
    }

    public void checkSkyEdges(final int chunkX, final int chunkZ) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();

        try {
            if (skyEngine != null) {
                skyEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
        }
    }

    public void checkBlockEdges(final int chunkX, final int chunkZ) {
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();
        try {
            if (blockEngine != null) {
                blockEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ);
            }
        } finally {
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    public void checkSkyEdges(final int chunkX, final int chunkZ, final ShortCollection sections) {
        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();

        try {
            if (skyEngine != null) {
                skyEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ, sections);
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
        }
    }

    public void checkBlockEdges(final int chunkX, final int chunkZ, final ShortCollection sections) {
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();
        try {
            if (blockEngine != null) {
                blockEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ, sections);
            }
        } finally {
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    public void scheduleChunkLight(final ChunkPos pos, final Runnable run) {
        this.lightQueue.queueChunkLighting(pos, run);
    }

    // Solix (0.3.4) - not called by Solix or any RatPack mod, but kept: Starlight API in Starlight's packages that other mods may call
    public void removeChunkTasks(final ChunkPos pos) {
        this.lightQueue.removeChunk(pos);
    }

    public void propagateChanges() {
        if (this.lightQueue.isEmpty()) {
            return;
        }

        // Solix start - ScalableLux parallel scheduler (server only; the client keeps the synchronous path)
        // (0.3.3: check the engine type first, so a pure client never initialises GlobalExecutors)
        if (this.lightEngine instanceof ThreadedLevelLightEngine threadedLevelLightEngine && GlobalExecutors.ENABLED && !parallelBroken) {
            this.schedulePropagation0(threadedLevelLightEngine);
            return;
        }
        // Solix end

        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();

        try {
            for (;;) {
                // Solix (0.3.4) - count the task as in flight from before it leaves the queue until it is done, so
                // hasUpdates (server stop, world close) stays true while the last task is still being processed
                this.inFlightTasks.incrementAndGet();
                try {
                    final LightQueue.ChunkTasks task = this.lightQueue.removeFirstTask();
                    if (task == null) {
                        break;
                    }
                    // Solix start (0.3.3) - an exception escaping here would stop vanilla from ever scheduling light
                    // updates for this dimension again (its "scheduled" flag is only reset after a successful run) and
                    // keep the chunk work ticket; retry the chunk once like the parallel path, then drop it
                    try {
                        this.handleUpdateInternal(task, skyEngine, blockEngine);
                    } catch (final Throwable thr) {
                        LOGGER.error("Solix: failed to process light updates for chunk ("
                                + CoordinateUtils.getChunkX(task.chunkCoordinate) + "," + CoordinateUtils.getChunkZ(task.chunkCoordinate) + ")", thr);
                        this.requeueFailedTasks(task);
                    }
                    // Solix end
                } finally {
                    this.inFlightTasks.decrementAndGet();
                }
            }
        } finally {
            this.releaseSkyLightEngine(skyEngine);
            this.releaseBlockLightEngine(blockEngine);
        }
    }

    // Solix start - ScalableLux parallel scheduler (patch 0006 + 0db04dc), adapted to Solix/Forge 1.20.1
    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger(0);
    private static final AtomicBoolean SCHEDULE_FAILURE_LOGGED = new AtomicBoolean(); // Solix (0.3.4)
    // Solix (0.3.7) - set when the worker threads cannot be started; light then runs on the serial path for good
    private static volatile boolean parallelBroken;

    public static boolean isParallelBroken() {
        return parallelBroken;
    }
    private static final CompletableFuture<Void> COMPLETED_FUTURE = CompletableFuture.completedFuture(null);
    private final int instanceId = INSTANCE_COUNTER.getAndIncrement();
    private final Long2ReferenceMap<CompletableFuture<Void>> chunkFutures = Long2ReferenceMaps.synchronize(new Long2ReferenceOpenHashMap<>());
    // Solix (0.3.3) - scheduling passes are serial; their candidate list is reused
    private final Object scheduleLock = new Object();
    private final LongArrayList scheduleCandidates = new LongArrayList();
    // Solix (0.3.3) - worker tasks scheduled and not finished, read without locking by hasUpdates
    private final AtomicInteger inFlightTasks = new AtomicInteger();

    /**
     * Runs on the light thread: schedules one worker task per chunk that has queued light work and no task in flight.
     * Each worker task locks the 5x5 chunks around its chunk (Starlight touches the 3x3 neighbourhood), removes
     * the chunk's queued work at run time and processes it like the serial path.
     */
    private void schedulePropagation0(final ThreadedLevelLightEngine threadedLevelLightEngine) {
        // Solix (0.3.3) - only copy the queued chunk keys under the queue lock (the main thread checks the queue on
        // every task it polls) and schedule outside of it; scheduling passes themselves stay serial
        synchronized (this.scheduleLock) {
            final LongArrayList candidates = this.scheduleCandidates;
            candidates.clear();
            this.lightQueue.copyChunkKeys(candidates);
            for (int i = 0, len = candidates.size(); i < len; ++i) {
                final long pos = candidates.getLong(i);
                if (!this.chunkFutures.getOrDefault(pos, COMPLETED_FUTURE).isDone()) {
                    continue;
                }
                final CompletableFuture<Void> future = new CompletableFuture<>();
                this.inFlightTasks.incrementAndGet(); // Solix (0.3.3) - counted until the future completes
                try { // Solix (0.3.4)
                    SchedulingUtil.scheduleTask(
                            this.instanceId,
                            () -> {
                                LightQueue.ChunkTasks tasks = null;
                                boolean processed = false; // Solix (0.3.4)
                                try {
                                    tasks = this.lightQueue.removeTasks(pos);
                                    if (tasks != null) {
                                        final SkyStarLightEngine skyEngine = this.getSkyLightEngine();
                                        final BlockStarLightEngine blockEngine = this.getBlockLightEngine();
                                        try {
                                            this.handleUpdateInternal(tasks, skyEngine, blockEngine);
                                        } finally {
                                            this.releaseSkyLightEngine(skyEngine);
                                            this.releaseBlockLightEngine(blockEngine);
                                        }
                                    }
                                    processed = true;
                                    // Solix (0.3.2) - finish this chunk's future before asking for the next scheduling
                                    // pass, otherwise that pass can skip work queued for this chunk meanwhile
                                    future.complete(null);
                                    if (tasks != null) {
                                        threadedLevelLightEngine.tryScheduleUpdate();
                                    }
                                } catch (final Throwable thr) {
                                    LOGGER.error("Solix: failed to process light updates for chunk ("
                                            + CoordinateUtils.getChunkX(pos) + "," + CoordinateUtils.getChunkZ(pos) + ")", thr);
                                    // Solix (0.3.2) - retry the chunk's work once instead of dropping its block changes;
                                    // the chunk work ticket is released when it is finally done or given up
                                    // (0.3.4: not when the work itself already finished and only the reschedule threw)
                                    final boolean requeued = !processed && tasks != null && this.requeueFailedTasks(tasks);
                                    future.completeExceptionally(thr);
                                    if (requeued) {
                                        threadedLevelLightEngine.tryScheduleUpdate();
                                    }
                                } finally {
                                    // Solix (0.3.4) - never leave the future open (e.g. if the error handling itself
                                    // throws): an open future keeps hasUpdates true, so server stop would wait forever,
                                    // and the chunk would never be scheduled again
                                    if (!future.isDone()) {
                                        future.completeExceptionally(new IllegalStateException("Solix: light task for chunk ("
                                                + CoordinateUtils.getChunkX(pos) + "," + CoordinateUtils.getChunkZ(pos) + ") did not complete"));
                                    }
                                }
                            },
                            CoordinateUtils.getChunkX(pos),
                            CoordinateUtils.getChunkZ(pos),
                            2,
                            this.getSchedulingPriority(pos)
                    );
                } catch (final Throwable thr) {
                    // Solix (0.3.4) - the chunk could not be handed to a worker (e.g. no new thread could be created):
                    // undo the in-flight count, keep its work queued for the next pass and end this pass
                    this.inFlightTasks.decrementAndGet();
                    this.lightQueue.queueDirty = true;
                    // Solix (0.3.7) - a failing pool (e.g. no native thread can be created, or it never initialised) would fail
                    // every pass again and leave the work queued forever (server stop waits for it): use the serial path
                    parallelBroken = true;
                    if (!SCHEDULE_FAILURE_LOGGED.getAndSet(true)) {
                        LOGGER.error("Solix: could not schedule light work on the light workers, switching to serial lighting (logged once)", thr);
                    }
                    return;
                }
                this.chunkFutures.put(pos, future);
                // ScalableLux 0db04dc: remove finished futures, otherwise the map grows with every chunk ever lit
                future.whenComplete((final Void unused, final Throwable throwable) -> {
                    this.chunkFutures.remove(pos, future);
                    this.inFlightTasks.decrementAndGet(); // Solix (0.3.3)
                });
            }
        }
    }

    /**
     * FlowSched priority of the light task for a chunk, lower runs sooner. Solix: use the chunk's vanilla queue level
     * (ChunkMap#getChunkQueueLevel, the same value vanilla uses to order light tasks), so chunks near players are lit
     * first, instead of ScalableLux's constant priority. Idea from Lucis.
     */
    private int getSchedulingPriority(final long chunkKey) {
        if (this.world instanceof ServerLevel serverLevel) {
            try {
                return serverLevel.getChunkSource().chunkMap.getChunkQueueLevel(chunkKey).getAsInt();
            } catch (final Throwable thr) {
                // fall through to the default
            }
        }
        return GlobalExecutors.PRIORITY_COUNT - 1;
    }

    /**
     * Only relevant on server lighting with parallel scheduling, best-effort check if the queue is dirty.
     */
    public boolean isQueueDirty() {
        return this.lightQueue.queueDirty;
    }

    /**
     * Solix (0.3.2): puts the work of a worker task that threw back into the queue, once. Block changes are merged
     * into work queued for the chunk since (newer section changes win). Returns false and releases the chunk work
     * ticket when the work already failed before.
     */
    private boolean requeueFailedTasks(final LightQueue.ChunkTasks failed) {
        if (failed.failures++ > 0) {
            LOGGER.error("Solix: light updates for chunk (" + CoordinateUtils.getChunkX(failed.chunkCoordinate) + ","
                    + CoordinateUtils.getChunkZ(failed.chunkCoordinate) + ") failed again, dropping them");
            failed.onComplete.complete(null);
            return false;
        }
        // when nothing is queued for the chunk, the failed task goes back with its own chunk work ticket
        if (this.lightQueue.putBackOrMerge(failed)) {
            // the queued task holds its own ticket
            failed.onComplete.complete(null);
        }
        return true;
    }

    private void handleUpdateInternal(final LightQueue.ChunkTasks task, final SkyStarLightEngine skyEngine, final BlockStarLightEngine blockEngine) {
        if (task.lightTasks != null) {
            final List<Runnable> lightTasks = task.lightTasks;
            task.lightTasks = null; // Solix - a retried task must not run them again
            for (final Runnable run : lightTasks) {
                run.run();
            }
        }

        final long coordinate = task.chunkCoordinate;
        final int chunkX = CoordinateUtils.getChunkX(coordinate);
        final int chunkZ = CoordinateUtils.getChunkZ(coordinate);

        final Set<BlockPos> positions = task.changedPositions;
        final Boolean[] sectionChanges = task.changedSectionSet;

        if (skyEngine != null && (!positions.isEmpty() || sectionChanges != null)) {
            skyEngine.blocksChangedInChunk(this.lightAccess, chunkX, chunkZ, positions, sectionChanges);
        }
        if (blockEngine != null && (!positions.isEmpty() || sectionChanges != null)) {
            blockEngine.blocksChangedInChunk(this.lightAccess, chunkX, chunkZ, positions, sectionChanges);
        }

        if (skyEngine != null && task.queuedEdgeChecksSky != null) {
            skyEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ, task.queuedEdgeChecksSky);
        }
        if (blockEngine != null && task.queuedEdgeChecksBlock != null) {
            blockEngine.checkChunkEdges(this.lightAccess, chunkX, chunkZ, task.queuedEdgeChecksBlock);
        }

        task.onComplete.complete(null);
    }
    // Solix end

    public static final class LightQueue {

        protected final Long2ObjectLinkedOpenHashMap<ChunkTasks> chunkTasks = new Long2ObjectLinkedOpenHashMap<>();
        protected final StarLightInterface manager;
        protected volatile boolean queueDirty = false; // Solix - ScalableLux scheduler
        // Solix (0.3.3) - chunkTasks.size(), updated under the lock by every change so isEmpty() needs no lock
        private volatile int size;

        public LightQueue(final StarLightInterface manager) {
            this.manager = manager;
        }

        public boolean isEmpty() {
            return this.size == 0; // Solix (0.3.3) - lock-free, see size
        }

        // Solix start (0.3.3)
        private ChunkTasks getOrCreate(final long chunkKey) {
            final ChunkTasks tasks = this.chunkTasks.computeIfAbsent(chunkKey, ChunkTasks::new);
            this.size = this.chunkTasks.size();
            return tasks;
        }

        /** Removes the queued work of a chunk (a worker task taking it), null when nothing is queued. */
        public synchronized ChunkTasks removeTasks(final long chunkKey) {
            final ChunkTasks tasks = this.chunkTasks.remove(chunkKey);
            this.size = this.chunkTasks.size();
            return tasks;
        }

        /** Adds the keys of all chunks with queued work to the list and clears the dirty flag. */
        public synchronized void copyChunkKeys(final LongArrayList into) {
            into.addAll(this.chunkTasks.keySet());
            this.queueDirty = false;
        }

        /**
         * Puts the work of a failed worker task back: as is when nothing is queued for the chunk, otherwise merged into
         * the queued (newer) work. Returns true when it was merged, the failed task's ticket can then be released.
         */
        public synchronized boolean putBackOrMerge(final ChunkTasks failed) {
            final ChunkTasks queued = this.chunkTasks.get(failed.chunkCoordinate);
            if (queued == null) {
                this.chunkTasks.put(failed.chunkCoordinate, failed);
            } else {
                queued.mergeOlder(failed);
            }
            this.size = this.chunkTasks.size();
            this.queueDirty = true;
            return queued != null;
        }
        // Solix end (0.3.3)

        public synchronized LightQueue.ChunkTasks queueBlockChange(final BlockPos pos) {
            final ChunkTasks tasks = this.getOrCreate(CoordinateUtils.getChunkKey(pos)); // Solix (0.3.3) - keeps size current
            tasks.changedPositions.add(pos.immutable());
            this.queueDirty = true; // Solix
            return tasks;
        }

        public synchronized LightQueue.ChunkTasks queueSectionChange(final SectionPos pos, final boolean newEmptyValue) {
            final ChunkTasks tasks = this.getOrCreate(CoordinateUtils.getChunkKey(pos)); // Solix (0.3.3) - keeps size current

            if (tasks.changedSectionSet == null) {
                tasks.changedSectionSet = new Boolean[this.manager.maxSection - this.manager.minSection + 1];
            }
            tasks.changedSectionSet[pos.getY() - this.manager.minSection] = Boolean.valueOf(newEmptyValue);

            this.queueDirty = true; // Solix
            return tasks;
        }

        public synchronized LightQueue.ChunkTasks queueChunkLighting(final ChunkPos pos, final Runnable lightTask) {
            final ChunkTasks tasks = this.getOrCreate(CoordinateUtils.getChunkKey(pos)); // Solix (0.3.3) - keeps size current
            if (tasks.lightTasks == null) {
                tasks.lightTasks = new ArrayList<>();
            }
            tasks.lightTasks.add(lightTask);

            this.queueDirty = true; // Solix
            return tasks;
        }

        // Solix (0.3.4) - not called by Solix or any RatPack mod, but kept: Starlight API that other mods may call
        public synchronized LightQueue.ChunkTasks queueChunkSkylightEdgeCheck(final SectionPos pos, final ShortCollection sections) {
            final ChunkTasks tasks = this.getOrCreate(CoordinateUtils.getChunkKey(pos)); // Solix (0.3.3) - keeps size current

            ShortOpenHashSet queuedEdges = tasks.queuedEdgeChecksSky;
            if (queuedEdges == null) {
                queuedEdges = tasks.queuedEdgeChecksSky = new ShortOpenHashSet();
            }
            queuedEdges.addAll(sections);

            this.queueDirty = true; // Solix
            return tasks;
        }

        // Solix (0.3.4) - not called by Solix or any RatPack mod, but kept: Starlight API that other mods may call
        public synchronized LightQueue.ChunkTasks queueChunkBlocklightEdgeCheck(final SectionPos pos, final ShortCollection sections) {
            final ChunkTasks tasks = this.getOrCreate(CoordinateUtils.getChunkKey(pos)); // Solix (0.3.3) - keeps size current

            ShortOpenHashSet queuedEdges = tasks.queuedEdgeChecksBlock;
            if (queuedEdges == null) {
                queuedEdges = tasks.queuedEdgeChecksBlock = new ShortOpenHashSet();
            }
            queuedEdges.addAll(sections);

            this.queueDirty = true; // Solix
            return tasks;
        }

        public void removeChunk(final ChunkPos pos) {
            final ChunkTasks tasks;
            synchronized (this) {
                tasks = this.chunkTasks.remove(CoordinateUtils.getChunkKey(pos));
                this.size = this.chunkTasks.size(); // Solix (0.3.3)
            }
            if (tasks != null) {
                tasks.onComplete.complete(null);
            }
            this.queueDirty = true; // Solix
        }

        public synchronized ChunkTasks removeFirstTask() {
            if (this.chunkTasks.isEmpty()) {
                return null;
            }
            final ChunkTasks tasks = this.chunkTasks.removeFirst();
            this.size = this.chunkTasks.size(); // Solix (0.3.3)
            return tasks;
        }

        public static final class ChunkTasks {

            public final Set<BlockPos> changedPositions = new ObjectOpenHashSet<>();
            public Boolean[] changedSectionSet;
            public ShortOpenHashSet queuedEdgeChecksSky;
            public ShortOpenHashSet queuedEdgeChecksBlock;
            public List<Runnable> lightTasks;

            public boolean isTicketAdded = false;
            public final CompletableFuture<Void> onComplete = new CompletableFuture<>();

            public final long chunkCoordinate;

            public int failures; // Solix (0.3.2) - worker failures, see requeueFailedTasks

            public ChunkTasks(final long chunkCoordinate) {
                this.chunkCoordinate = chunkCoordinate;
            }

            // Solix (0.3.2) - adds the work of an older task for the same chunk; this task's newer section changes win
            void mergeOlder(final ChunkTasks older) {
                this.changedPositions.addAll(older.changedPositions);
                if (older.changedSectionSet != null) {
                    if (this.changedSectionSet == null) {
                        this.changedSectionSet = older.changedSectionSet;
                    } else {
                        for (int i = 0; i < this.changedSectionSet.length; ++i) {
                            if (this.changedSectionSet[i] == null) {
                                this.changedSectionSet[i] = older.changedSectionSet[i];
                            }
                        }
                    }
                }
                if (older.queuedEdgeChecksSky != null) {
                    if (this.queuedEdgeChecksSky == null) {
                        this.queuedEdgeChecksSky = new ShortOpenHashSet();
                    }
                    this.queuedEdgeChecksSky.addAll(older.queuedEdgeChecksSky);
                }
                if (older.queuedEdgeChecksBlock != null) {
                    if (this.queuedEdgeChecksBlock == null) {
                        this.queuedEdgeChecksBlock = new ShortOpenHashSet();
                    }
                    this.queuedEdgeChecksBlock.addAll(older.queuedEdgeChecksBlock);
                }
                if (older.lightTasks != null) {
                    if (this.lightTasks == null) {
                        this.lightTasks = new ArrayList<>();
                    }
                    this.lightTasks.addAll(0, older.lightTasks);
                }
                this.failures = Math.max(this.failures, older.failures);
            }
        }
    }
}
