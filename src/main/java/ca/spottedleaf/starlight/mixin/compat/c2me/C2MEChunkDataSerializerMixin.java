package ca.spottedleaf.starlight.mixin.compat.c2me;

import net.minecraftforge.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * C2ME's gc-free chunk serializer only writes Starlight's light format when {@code ModList.isLoaded("starlight")}.
 * Solix uses the same format and the same classes under a different mod id, so report "starlight" as loaded there.
 */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunk_serializer.common.ChunkDataSerializer", remap = false)
public abstract class C2MEChunkDataSerializerMixin {

    @Redirect(
            method = "<clinit>",
            at = @At(value = "INVOKE", target = "Lnet/minecraftforge/fml/ModList;isLoaded(Ljava/lang/String;)Z"),
            require = 0
    )
    private static boolean solix$starlightIsLoaded(final ModList modList, final String modId) {
        if ("starlight".equals(modId)) {
            return true;
        }
        return modList.isLoaded(modId);
    }
}
