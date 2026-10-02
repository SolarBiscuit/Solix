package ca.spottedleaf.starlight.common.thread;

import ca.spottedleaf.starlight.interndep.flowsched.LockToken;
import ca.spottedleaf.starlight.interndep.flowsched.Task;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import java.util.Objects;

/**
 * From ScalableLux's SimpleTask (RelativityMC), ported to the current FlowSched executor API.
 */
public final class LightTask extends Task {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Runnable task;
    private final LockToken[] lockTokens;

    public LightTask(final Runnable task, final LockToken[] lockTokens) {
        this.task = Objects.requireNonNull(task, "task");
        this.lockTokens = Objects.requireNonNull(lockTokens, "lockTokens");
    }

    @Override
    public void run(final Runnable releaseLocks) {
        try {
            this.task.run();
        } finally {
            releaseLocks.run();
        }
    }

    @Override
    public void propagateException(final Throwable t) {
        LOGGER.error("Solix: exception in light worker task", t);
    }

    @Override
    public LockToken[] lockTokens() {
        return this.lockTokens;
    }
}
