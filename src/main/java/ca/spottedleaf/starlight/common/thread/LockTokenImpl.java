package ca.spottedleaf.starlight.common.thread;

import ca.spottedleaf.starlight.interndep.flowsched.LockToken;

/**
 * From ScalableLux (RelativityMC). One token per (light engine instance, chunk).
 */
public record LockTokenImpl(int ownerTag, long pos) implements LockToken {
}
