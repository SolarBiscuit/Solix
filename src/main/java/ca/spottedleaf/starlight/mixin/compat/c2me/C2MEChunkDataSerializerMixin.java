package ca.spottedleaf.starlight.mixin.compat.c2me;

import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * C2ME's gc-free chunk serializer only writes Starlight's light format when "starlight" is loaded.
 * Fabric branch: Solix provides the id "starlight" (fabric.mod.json), so {@code FabricLoader#isModLoaded("starlight")}
 * is already true; this redirect only makes sure of it if that check is reached through a different loader instance.
 */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunk_serializer.common.ChunkDataSerializer", remap = false)
public abstract class C2MEChunkDataSerializerMixin {

    @Redirect(
            method = "<clinit>",
            at = @At(value = "INVOKE", target = "Lnet/fabricmc/loader/api/FabricLoader;isModLoaded(Ljava/lang/String;)Z"),
            require = 0
    )
    private static boolean solix$starlightIsLoaded(final FabricLoader loader, final String modId) {
        if ("starlight".equals(modId)) {
            return true;
        }
        return loader.isModLoaded(modId);
    }

    // Solix (0.3.4) - 0.3.2 made C2ME write Solix's light version 9 instead of its hard-coded 8. Reverted: C2ME's Starlight
    // writer also saves the light sections below and above the world (minLightSection, maxLightSection) without their
    // own section compound, so they end up merged into a neighbouring tag. With version 8 Solix relights such chunks on
    // load, which repairs them; with version 9 the broken edge sections were kept. Only relevant with C2ME's
    // ioSystem.gcFreeChunkSerializer.
}
