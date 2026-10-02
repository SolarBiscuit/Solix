/*
 * Vendored from FlowSched (https://github.com/RelativityMC/FlowSched), commit a60f952, package
 * com.ishland.flowsched.executor, relocated for Solix. Copyright (c) 2023 ishland. MIT License, see LICENSE-FlowSched.txt.
 */
package ca.spottedleaf.starlight.interndep.flowsched;

import java.util.Objects;

public class SimpleTask extends Task {
    private static final LockToken[] EMPTY_TOKENS = new LockToken[0];

    private final Runnable wrapped;

    public SimpleTask(Runnable wrapped) {
        this.wrapped = Objects.requireNonNull(wrapped);
    }

    @Override
    public void run(Runnable releaseLocks) {
        try {
            wrapped.run();
        } finally {
            releaseLocks.run();
        }
    }

    @Override
    public void propagateException(Throwable t) {
        t.printStackTrace();
    }

    @Override
    public LockToken[] lockTokens() {
        return EMPTY_TOKENS;
    }
}
