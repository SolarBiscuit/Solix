package ca.spottedleaf.starlight.mixin;

import ca.spottedleaf.starlight.common.config.SolixConfig;
import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Solix: reads config/solix.toml before any mixin is applied, and disables all Solix mixins when another light
 * engine is installed. Forge then shows its own "incompatible mod" error (declared in mods.toml) instead of both
 * engines' mixins clashing first.
 */
public final class SolixMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("Solix");

    private static final String[] INCOMPATIBLE = {"starlight", "scalablelux", "lucis"};

    private boolean disabled;

    @Override
    public void onLoad(final String mixinPackage) {
        final List<String> found = new ArrayList<>();
        try {
            final LoadingModList modList = LoadingModList.get();
            if (modList != null) {
                for (final String id : INCOMPATIBLE) {
                    if (modList.getModFileById(id) != null) {
                        found.add(id);
                    }
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
        return !this.disabled;
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
