package ca.spottedleaf.starlight.common.thread;

import ca.spottedleaf.starlight.common.config.SolixConfig;
import ca.spottedleaf.starlight.interndep.flowsched.ExecutorManager;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import org.slf4j.Logger;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * From ScalableLux (RelativityMC), adapted for Solix: parallel light workers are only created when enabled.
 */
public final class GlobalExecutors {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Parallel scheduling is used when enabled in the config with more than one worker. */
    public static final boolean ENABLED = SolixConfig.PARALLEL && SolixConfig.PARALLELISM > 1;

    /** Number of FlowSched priority buckets: one per vanilla chunk queue level (lower = sooner). */
    public static final int PRIORITY_COUNT = ChunkTaskPriorityQueue.PRIORITY_LEVEL_COUNT;

    static {
        if (ENABLED) {
            LOGGER.info("Solix: parallel server lighting enabled, using {} worker threads", SolixConfig.PARALLELISM);
        } else if (!SolixConfig.PARALLEL) {
            LOGGER.info("Solix: parallel lighting disabled in config, using serial Starlight scheduling");
        } else {
            LOGGER.info("Solix: parallel lighting disabled (parallelism = 1), using serial Starlight scheduling");
        }
    }

    private static final class Holder {
        private static final AtomicInteger COUNTER = new AtomicInteger(0);
        static final ExecutorManager EXECUTOR = new ExecutorManager(SolixConfig.PARALLELISM, thread -> {
            thread.setDaemon(true);
            thread.setName("Solix Light Worker #" + COUNTER.getAndIncrement());
        }, PRIORITY_COUNT);
    }

    public static ExecutorManager executor() {
        return Holder.EXECUTOR;
    }

    private GlobalExecutors() {}
}
