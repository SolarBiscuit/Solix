package ca.spottedleaf.starlight.mixin;

import ca.spottedleaf.starlight.Solix;
import ca.spottedleaf.starlight.common.config.SolixConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Solix: reads config/solix.toml before any mixin is applied, and disables all Solix mixins when another light
 * engine is installed.
 * <p>
 * Fabric branch: fabric.mod.json already makes Fabric Loader refuse these combinations before the game starts
 * ({@code provides: starlight} clashes with a real Starlight jar, {@code breaks} covers the others), so this is only a
 * second line of defence. Solix itself provides the id "starlight": a container is only counted when its real id is
 * not Solix's, otherwise Solix would disable itself.
 * </p>
 */
public final class SolixMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("Solix");

    private static final String[] INCOMPATIBLE = {"starlight", "scalablelux", "lucis", "phosphor"};

    private static final String DEBUG_SECTION_TYPES_MIXIN = "ca.spottedleaf.starlight.mixin.debug.MinecraftServerDebugMixin";

    private static final String C2ME_SERIALIZER_MIXIN = "ca.spottedleaf.starlight.mixin.compat.c2me.C2MEChunkDataSerializerMixin";

    private boolean disabled;

    @Override
    public void onLoad(final String mixinPackage) {
        final List<String> found = new ArrayList<>();
        try {
            final FabricLoader loader = FabricLoader.getInstance();
            for (final String id : INCOMPATIBLE) {
                final Optional<ModContainer> container = loader.getModContainer(id);
                if (container.isPresent() && !Solix.MOD_ID.equals(container.get().getMetadata().getId())) {
                    found.add(id);
                }
            }
        } catch (final Throwable thr) {
            LOGGER.warn("Solix: could not check for incompatible light engines", thr);
        }
        if (!found.isEmpty()) {
            this.disabled = true;
            LOGGER.error("Solix replaces the light engine and cannot run together with: {}. Remove one of them. "
                    + "Solix mixins are disabled for this launch.", String.join(", ", found));
            return;
        }
        SolixConfig.init();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        if (this.disabled) {
            return false;
        }
        if (C2ME_SERIALIZER_MIXIN.equals(mixinClassName)) {
            // only with C2ME's serializer module: otherwise Mixin logs a warning for the missing @Pseudo target
            return FabricLoader.getInstance().isModLoaded("c2me-rewrites-chunk-serializer");
        }
        if (DEBUG_SECTION_TYPES_MIXIN.equals(mixinClassName)) {
            return Boolean.getBoolean("solix.debug.sectionTypes") || Boolean.getBoolean("solix.debug.positionalEmission");
        }
        return true;
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {}

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {}
}
