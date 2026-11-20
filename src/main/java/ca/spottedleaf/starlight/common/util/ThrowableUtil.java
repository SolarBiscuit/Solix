package ca.spottedleaf.starlight.common.util;

/**
 * Solix (0.3.9): helpers for the catch-all handlers around light code.
 */
public final class ThrowableUtil {

    /**
     * Rethrows errors the JVM cannot recover from ({@link OutOfMemoryError}, {@link InternalError}, ...), so that
     * handlers which log and carry on do not swallow them. Replaces the old {@code ThreadDeath} checks: that class is
     * deprecated for removal since Java 20 and {@code Thread#stop} no longer throws it, so the check was dead code that
     * would fail to link once the class is removed.
     */
    public static void rethrowIfFatal(final Throwable throwable) {
        if (throwable instanceof VirtualMachineError) {
            throw (VirtualMachineError)throwable;
        }
    }

    private ThrowableUtil() {}
}
