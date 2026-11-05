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

    static void record(final BlockState state, final ClassCastException ex) {
        final Class<?> blockClass = state.getBlock().getClass();
        if (REAL_LEVEL.add(blockClass)) {
            any = true;
            LOGGER.warn("Solix: light code of " + blockClass.getName() + " does not accept Solix's light view (" + ex.getMessage()
                    + "); it is given the real level from now on. Set safe_light_view = false in config/solix.toml to do this for every block.", ex);
        }
    }

    private LightViewFallback() {}
}
