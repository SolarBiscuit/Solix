package ca.spottedleaf.starlight.common.thread;

import ca.spottedleaf.starlight.common.util.CoordinateUtils;
import ca.spottedleaf.starlight.interndep.flowsched.LockToken;

/**
 * From ScalableLux (RelativityMC): schedules a light task that locks the (2 * radius + 1)^2 chunks around it.
 */
public final class SchedulingUtil {

    public static void scheduleTask(final int ownerTag, final Runnable task, final int x, final int z, final int radius,
                                    final int priority) {
        final int size = radius * 2 + 1;
        final LockToken[] lockTokens = new LockToken[size * size];
        int i = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                lockTokens[i++] = new LockTokenImpl(ownerTag, CoordinateUtils.getChunkKey(x + dx, z + dz));
            }
        }
        final int clamped = Math.max(0, Math.min(GlobalExecutors.PRIORITY_COUNT - 1, priority));
        GlobalExecutors.executor().schedule(new LightTask(task, lockTokens), clamped);
    }

    private SchedulingUtil() {}
}
