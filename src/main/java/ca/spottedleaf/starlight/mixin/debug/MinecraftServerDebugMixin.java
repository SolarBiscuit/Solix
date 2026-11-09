package ca.spottedleaf.starlight.mixin.debug;

import ca.spottedleaf.starlight.Solix;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Solix (Fabric): replaces the Forge branch's ServerStartedEvent/FMLLoadCompleteEvent debug listeners
 * ({@code -Dsolix.debug.sectionTypes=true}, {@code -Dsolix.debug.positionalEmission=true}).
 * SolixMixinPlugin only applies this mixin when one of these properties is set.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerDebugMixin {

    @Shadow
    public abstract ServerLevel overworld();

    @Inject(method = "loadLevel", at = @At("TAIL"), require = 0)
    private void solix$logSectionTypes(final CallbackInfo ci) {
        if (Boolean.getBoolean("solix.debug.positionalEmission")) {
            Solix.logPositionalEmission();
        }
        final ServerLevel overworld = this.overworld();
        if (overworld != null && Boolean.getBoolean("solix.debug.sectionTypes")) {
            Solix.logSectionTypes(overworld);
        }
    }
}
