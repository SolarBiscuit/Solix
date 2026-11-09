package ca.spottedleaf.starlight.common.blockstate;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Solix: detects block classes whose light emission depends on the position.
 * <p>
 * Fabric branch: vanilla and Fabric API have no positional light emission, but Porting Lib (the Forge API port used by
 * Create Fabric, Farmer's Delight Refabricated, Lodestone and other ports) adds Forge's
 * {@code getLightEmission(BlockState, BlockGetter, BlockPos)} as {@code LightEmissiveBlock} and hooks it into the
 * vanilla block light engine only, which Solix replaces. Blocks that override it are treated exactly like blocks
 * overriding Forge's positional emission on the Forge branch (Forge's {@code IForgeBlock} ↔ {@code LightEmissiveBlock}).
 * Porting Lib is looked up by name only: without it no block is position-aware.
 * </p>
 */
public final class PositionalEmission {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String PORTING_LIB_EMISSIVE = "io.github.fabricators_of_create.porting_lib.block.LightEmissiveBlock";

    private static final MethodType LIGHT_EMISSION = MethodType.methodType(int.class, BlockState.class, BlockGetter.class, BlockPos.class);

    /** Porting Lib's LightEmissiveBlock, or null when Porting Lib is not installed. */
    private static final Class<?> EMISSIVE;
    /** (Object block, BlockState, BlockGetter, BlockPos) int: LightEmissiveBlock#getLightEmission, or null. */
    private static final MethodHandle EMISSION;

    static {
        Class<?> emissive = null;
        MethodHandle emission = null;
        try {
            emissive = Class.forName(PORTING_LIB_EMISSIVE, false, PositionalEmission.class.getClassLoader());
            emission = MethodHandles.publicLookup().findVirtual(emissive, "getLightEmission", LIGHT_EMISSION)
                    .asType(MethodType.methodType(int.class, Object.class, BlockState.class, BlockGetter.class, BlockPos.class));
            LOGGER.info("Solix: Porting Lib found, blocks overriding its positional light emission get it from the light engine");
        } catch (final ClassNotFoundException ex) {
            emissive = null;
        } catch (final Throwable thr) {
            LOGGER.warn("Solix: Porting Lib's LightEmissiveBlock could not be linked, its positional light emission is ignored", thr);
            emissive = null;
            emission = null;
        }
        EMISSIVE = emission == null ? null : emissive;
        EMISSION = EMISSIVE == null ? null : emission;
    }

    private static final ClassValue<Boolean> POSITION_AWARE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> clazz) {
            return Boolean.valueOf(compute(clazz));
        }
    };

    public static boolean isPositionAware(final BlockState state) {
        return EMISSIVE != null && POSITION_AWARE.get(state.getBlock().getClass()).booleanValue();
    }

    public static boolean isPositionAware(final Class<?> blockClass) {
        return EMISSIVE != null && POSITION_AWARE.get(blockClass).booleanValue();
    }

    /**
     * Solix (Fabric): the positional emission of a block for which {@link #isPositionAware(BlockState)} is true.
     * Exceptions from the block's code (e.g. a ClassCastException, see LightViewFallback) are passed on unchanged.
     */
    public static int getLightEmission(final BlockState state, final BlockGetter level, final BlockPos pos) {
        try {
            return (int)EMISSION.invokeExact((Object)state.getBlock(), state, level, pos);
        } catch (final RuntimeException | Error ex) {
            throw ex;
        } catch (final Throwable thr) {
            throw new RuntimeException(thr);
        }
    }

    private static boolean compute(final Class<?> clazz) {
        if (!EMISSIVE.isAssignableFrom(clazz)) {
            return false;
        }
        try {
            // implementing the interface is not enough: the default only returns the position-less emission
            return findDeclarer(clazz, "getLightEmission") != EMISSIVE;
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
     * dedicated server that fails for blocks with a client-only type in any public signature, and with an optional
     * mod missing it fails the same way. Reflection is only the fallback if the lookup is refused.
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

    private PositionalEmission() {}
}
