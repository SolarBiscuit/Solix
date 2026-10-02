package ca.spottedleaf.starlight.common.config;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Solix: tiny config file {@code config/solix.toml}, read once when the mixin plugin loads (before any world exists).
 * Only flat {@code key = value} lines are supported; unknown keys are ignored.
 */
public final class SolixConfig {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String FILE_NAME = "solix.toml";

    /** Use the parallel (ScalableLux) light scheduler on the server. */
    public static final boolean PARALLEL;
    /** Number of light worker threads (resolved, >= 1). */
    public static final int PARALLELISM;
    /** Minimum time between scheduling passes when no new light work was queued, in nanoseconds. */
    public static final long THROTTLE_NANOS;

    private static final String DEFAULT_FILE = String.join("\n", List.of(
            "# Solix configuration. Read once at game start; restart to apply changes.",
            "",
            "# Run server light updates on several worker threads (ScalableLux's scheduler, 5x5-chunk locks).",
            "# The client always lights synchronously. false = serial Starlight behaviour on the light thread.",
            "parallel = true",
            "",
            "# Number of light worker threads. 0 = automatic: max(1, CPU threads / 4).",
            "# 1 behaves like serial Starlight (use it if you see wrong sky light in sealed rooms, ScalableLux issue #36).",
            "parallelism = 0",
            "",
            "# Minimum milliseconds between light scheduling passes when no new light work was queued.",
            "# Keeps C2ME's light thread from busy-spinning while workers are busy. 0 disables the throttle.",
            "throttle_ms = 10",
            ""
    ));

    static {
        final Map<String, String> values = new HashMap<>();
        Path path = null;
        try {
            path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
            if (Files.isRegularFile(path)) {
                for (final String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String line = rawLine;
                    final int comment = line.indexOf('#');
                    if (comment >= 0) {
                        line = line.substring(0, comment);
                    }
                    final int eq = line.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String value = line.substring(eq + 1).trim();
                    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    values.put(line.substring(0, eq).trim().toLowerCase(Locale.ROOT), value.trim());
                }
            } else {
                Files.createDirectories(path.getParent());
                Files.writeString(path, DEFAULT_FILE, StandardCharsets.UTF_8);
            }
        } catch (final IOException | RuntimeException ex) {
            LOGGER.warn("Solix: could not read or create config " + path + ", using defaults", ex);
        }

        PARALLEL = parseBoolean(values.get("parallel"), true);
        int parallelism = parseInt(values.get("parallelism"), 0);
        if (parallelism < 1) {
            parallelism = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
        }
        PARALLELISM = parallelism;
        THROTTLE_NANOS = Math.max(0L, parseInt(values.get("throttle_ms"), 10)) * 1_000_000L;
    }

    public static void init() {}

    private static boolean parseBoolean(final String value, final boolean def) {
        if (value == null) {
            return def;
        }
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        LOGGER.warn("Solix: invalid boolean '" + value + "' in " + FILE_NAME + ", using " + def);
        return def;
    }

    private static int parseInt(final String value, final int def) {
        if (value == null) {
            return def;
        }
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException ex) {
            LOGGER.warn("Solix: invalid number '" + value + "' in " + FILE_NAME + ", using " + def);
            return def;
        }
    }

    private SolixConfig() {}
}
