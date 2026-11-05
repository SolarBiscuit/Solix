package ca.spottedleaf.starlight.common.blockstate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.extensions.IForgeBlock;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Solix: detects block classes whose light emission depends on the position (Forge's
 * {@code IForgeBlock#getLightEmission(BlockState, BlockGetter, BlockPos)} is overridden).
 * <p>
 * For blocks the position-less {@code BlockState#getLightEmission()} is not the real emission, so the engine
 * must not use the cached fast paths for them. Forge's own check (calling the method at {@code BlockPos.ZERO})
 * is not safe: several mods (for example Mekanism fluid tanks, Modular Routers camouflage) return 0 there.
 * Idea from ScalableLux's NeoForge port (reflection-based positional emission detection).
 * </p>
 */
public final class PositionalEmission {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String ZETA_EXTENSIONS = "org.violetmoon.zeta.block.ext.IZetaBlockExtensions";

    private static final MethodType LIGHT_EMISSION = MethodType.methodType(int.class, BlockState.class, BlockGetter.class, BlockPos.class);

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
            final Class<?> declarer = findDeclarer(clazz, "getLightEmission");
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
                    if (findDeclarer(clazz, "getLightEmissionZeta") == zetaExtensions) {
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

    /**
     * Solix: the class (or interface) declaring the public method {@code name} with the getLightEmission signature
     * that a call on {@code clazz} links to, the same one {@code clazz.getMethod(name, ...)} returns.
     * <p>
     * Resolved the way the JVM links an invokespecial, which only loads the types in that method's own descriptor.
     * {@code Class#getMethod} loads the parameter and return types of every public method in the hierarchy: on a
     * dedicated server that fails for blocks with a client-only type in any public signature (for example
     * MineColonies' BlockHutTownHall, ClientLevel) and logs a RuntimeDistCleaner error; with an optional mod
     * missing it fails the same way. Reflection is only the fallback if the lookup is refused.
     * </p>
     */
    private static Class<?> findDeclarer(final Class<?> clazz, final String name) throws Throwable {
        try {
            final MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());
            return lookup.revealDirect(lookup.findSpecial(clazz, name, LIGHT_EMISSION, clazz)).getDeclaringClass();
        } catch (final ReflectiveOperationException | RuntimeException ex) {
            LOGGER.debug("Solix: method lookup refused for " + clazz.getName() + "#" + name + ", using reflection", ex);
            return clazz.getMethod(name, LIGHT_EMISSION.parameterArray()).getDeclaringClass();
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
