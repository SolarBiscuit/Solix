package ca.spottedleaf.starlight;

import ca.spottedleaf.starlight.common.blockstate.PositionalEmission;
import com.mojang.logging.LogUtils;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.slf4j.Logger;
import java.util.EnumMap;
import java.util.TreeSet;

/**
 * Solix (Fabric): no entrypoint is needed, everything is done by mixins (like upstream Starlight Fabric). Fabric has no
 * server/client mod handshake, so Solix stays network compatible both ways without any registration.
 * <p>
 * {@code -Dsolix.debug.sectionTypes=true} logs the light section types around spawn and
 * {@code -Dsolix.debug.positionalEmission=true} the blocks with position-aware (Porting Lib) light emission, once the
 * levels are loaded (MinecraftServerDebugMixin, applied only with one of these properties).
 * </p>
 */
public final class Solix {

    public static final String MOD_ID = "solix";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void logSectionTypes(final ServerLevel level) {
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

    public static void logPositionalEmission() {
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

    private Solix() {}
}
