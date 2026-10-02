package ca.spottedleaf.starlight;

import ca.spottedleaf.starlight.common.blockstate.PositionalEmission;
import com.mojang.logging.LogUtils;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkConstants;
import org.slf4j.Logger;
import java.util.EnumMap;
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
        if (Boolean.getBoolean("solix.debug.sectionTypes")) {
            MinecraftForge.EVENT_BUS.addListener((final ServerStartedEvent event) -> logSectionTypes(event.getServer().overworld()));
        }
        if (Boolean.getBoolean("solix.debug.positionalEmission")) {
            FMLJavaModLoadingContext.get().getModEventBus().addListener((final FMLLoadCompleteEvent event) -> logPositionalEmission());
        }
    }

    private static void logSectionTypes(final ServerLevel level) {
        final EnumMap<LayerLightSectionStorage.SectionType, Integer> block = new EnumMap<>(LayerLightSectionStorage.SectionType.class);
        final EnumMap<LayerLightSectionStorage.SectionType, Integer> sky = new EnumMap<>(LayerLightSectionStorage.SectionType.class);
        final ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        final LevelLightEngine engine = level.getChunkSource().getLightEngine();
        for (int dx = -4; dx <= 4; ++dx) {
            for (int dz = -4; dz <= 4; ++dz) {
                for (int y = level.getMinSection() - 1; y <= level.getMaxSection(); ++y) {
                    final SectionPos pos = SectionPos.of(spawn.x + dx, y, spawn.z + dz);
                    block.merge(engine.getDebugSectionType(LightLayer.BLOCK, pos), 1, Integer::sum);
                    sky.merge(engine.getDebugSectionType(LightLayer.SKY, pos), 1, Integer::sum);
                }
            }
        }
        LOGGER.info("Solix: getDebugSectionType around spawn (9x9 chunks): block {} sky {}", block, sky);
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
