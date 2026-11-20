package ca.spottedleaf.starlight.common.light;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Solix (0.3.7): block classes whose light code does not accept the server light engine's {@link LightBlockGetter}
 * (it casts the BlockGetter to Level / LevelReader / ServerLevel). The first failure per class is logged and the class
 * is then given the real level, like before 0.3.6. Those classes can still deadlock the way Age of Fate #149 did if
 * they ask the level for an unloaded chunk; for every other block the safe view stays in use.
 */
final class LightViewFallback {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Set<Class<?>> REAL_LEVEL = ConcurrentHashMap.newKeySet();
    /** Cheap check for the hot path: nothing has failed yet, the set is not consulted. */
    private static volatile boolean any;

    static boolean useRealLevel(final BlockState state) {
        return any && REAL_LEVEL.contains(state.getBlock().getClass());
    }

    private static final String VIEW_CLASS_NAME = LightBlockGetter.class.getName();

    /**
     * Solix (0.3.9): records the block class only when the exception comes from casting Solix's light view, and
     * returns whether it did. Any other ClassCastException in a mod's light code (its own casts, or a HashMap read
     * racing the server thread) used to send the block class to the real level for good, the path that can deadlock,
     * with a warning blaming the view. Since Java 11 the message of a failed cast names both classes ("class X cannot be
     * cast to class Y", and Class#cast: "Cannot cast X to Y"). A null message means the JIT threw a preallocated
     * exception (OmitStackTraceInFastThrow) and the cause is unknown, so the view is assumed, as before.
     */
    static boolean recordIfViewCast(final BlockState state, final ClassCastException ex) {
        final String message = ex.getMessage();
        if (message != null && !message.contains(VIEW_CLASS_NAME)) {
            return false;
        }
        record(state, ex);
        return true;
    }

    private static void record(final BlockState state, final ClassCastException ex) {
        final Class<?> blockClass = state.getBlock().getClass();
        if (REAL_LEVEL.add(blockClass)) {
            any = true;
            LOGGER.warn("Solix: light code of " + blockClass.getName() + " does not accept Solix's light view (" + ex.getMessage()
                    + "); it is given the real level from now on. Set safe_light_view = false in config/solix.toml to do this for every block.", ex);
        }
    }

    private LightViewFallback() {}
}
