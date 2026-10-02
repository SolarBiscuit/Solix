package ca.spottedleaf.starlight.common.blockstate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.extensions.IForgeBlock;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Method;

/**
 * Solix: detects block classes whose light emission depends on the position (Forge's
 * {@code IForgeBlock#getLightEmission(BlockState, BlockGetter, BlockPos)} is overridden).
 * <p>
 * For such blocks the position-less {@code BlockState#getLightEmission()} is not the real emission, so the engine
 * must not use the cached fast paths for them. Forge's own check (calling the method at {@code BlockPos.ZERO})
 * is not safe: several mods (for example Mekanism fluid tanks, Modular Routers camouflage) return 0 there.
 * Idea from ScalableLux's NeoForge port (reflection-based positional emission detection).
 * </p>
 */
public final class PositionalEmission {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String ZETA_EXTENSIONS = "org.violetmoon.zeta.block.ext.IZetaBlockExtensions";

    private static final ClassValue<Boolean> POSITION_AWARE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> clazz) {
            return Boolean.valueOf(compute(clazz));
        }
    };

    public static boolean isPositionAware(final BlockState state) {
        return POSITION_AWARE.get(state.getBlock().getClass()).booleanValue();
    }

    public static boolean isPositionAware(final Class<?> blockClass) {
        return POSITION_AWARE.get(blockClass).booleanValue();
    }

    private static boolean compute(final Class<?> clazz) {
        try {
            final Method method = clazz.getMethod("getLightEmission", BlockState.class, BlockGetter.class, BlockPos.class);
            final Class<?> declarer = method.getDeclaringClass();
            if (declarer == IForgeBlock.class) {
                return false;
            }
            // Zeta and Quark mix a getLightEmission override into their own block classes (IZetaBlockMixin_FAKE) that
            // only forwards to IZetaBlockExtensions#getLightEmissionZeta. If that is still the interface default, the
            // emission is the position-less one. Only trust this when the override itself comes from a Zeta/Quark
            // (org.violetmoon.*) class: a subclass elsewhere that overrides getLightEmission is still position-aware.
            if (declarer.getName().startsWith("org.violetmoon.")) {
                // looked up by name only, there is no dependency on Zeta
                final Class<?> zetaExtensions = findClass(ZETA_EXTENSIONS, clazz.getClassLoader());
                if (zetaExtensions != null && zetaExtensions.isAssignableFrom(clazz)) {
                    final Method zeta = clazz.getMethod("getLightEmissionZeta", BlockState.class, BlockGetter.class, BlockPos.class);
                    if (zeta.getDeclaringClass() == zetaExtensions) {
                        return false;
                    }
                }
            }
            return true;
        } catch (final Throwable thr) {
            // be safe: unknown means position-aware (slow but correct path)
            LOGGER.warn("Solix: could not inspect light emission of block class " + clazz.getName() + ", treating it as position-aware", thr);
            return true;
        }
    }

    private static Class<?> findClass(final String name, final ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (final Throwable thr) {
            return null;
        }
    }

    private PositionalEmission() {}
}
