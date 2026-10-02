package ca.spottedleaf.starlight;

import ca.spottedleaf.starlight.common.blockstate.PositionalEmission;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkConstants;
import org.slf4j.Logger;
import java.util.TreeSet;

@Mod(Solix.MOD_ID)
public final class Solix {

    public static final String MOD_ID = "solix";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Solix() {
        // like starlight, solix is network compatible both ways
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class, () -> {
            return new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (a, b) -> true);
        });
        if (Boolean.getBoolean("solix.debug.positionalEmission")) {
            FMLJavaModLoadingContext.get().getModEventBus().addListener((final FMLLoadCompleteEvent event) -> logPositionalEmission());
        }
    }

    private static void logPositionalEmission() {
        final TreeSet<String> aware = new TreeSet<>();
        int blocks = 0;
        for (final Block block : BuiltInRegistries.BLOCK) {
            ++blocks;
            if (PositionalEmission.isPositionAware(block.getClass())) {
                aware.add(block.getClass().getName() + " (" + BuiltInRegistries.BLOCK.getKey(block) + ")");
            }
        }
        LOGGER.info("Solix: {} of {} blocks have position-aware light emission", aware.size(), blocks);
        for (final String s : aware) {
            LOGGER.info("Solix: position-aware emission: {}", s);
        }
    }
}
