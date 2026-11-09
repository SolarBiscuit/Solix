package ca.spottedleaf.starlight.common.config;

import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    /** Light propagation algorithm: "targeted", "ordered" or "classic". */
    public static final String PROPAGATION;
    /** Process light queues brightest level first (true for "ordered" and "targeted"). */
    public static final boolean ORDERED;
    /** Only darken blocks that lost every light path (true for "targeted"). */
    public static final boolean TARGETED;

    /** Give mods' light code a view that never loads chunks or waits for the server thread (server only). */
    public static final boolean SAFE_LIGHT_VIEW;

    private static final String SAFE_VIEW_BLOCK = String.join("\n", List.of(
            "# Give other mods' position-aware light code (block light emission / opacity callbacks) a light-only view of the",
            "# world instead of the real server level. Fixes world-load hangs with mods that look up block entities or",
            "# neighbouring blocks from those callbacks (Ender IO, Age of Fate #149). A block class that cannot work with the",
            "# view falls back to the real level on its own. false = always hand over the real level (Solix 0.3.5 behaviour).",
            "safe_light_view = true",
            ""
    ));

    private static final String PROPAGATION_DEFAULT = "targeted";
    private static final String PROPAGATION_BLOCK = String.join("\n", List.of(
            "# Light propagation algorithm (server and client).",
            "# targeted = brightest-first queues, and placing a block only darkens the blocks that really lost their light",
            "#            instead of darkening and re-lighting the whole area behind it.",
            "# ordered  = brightest-first queues only: every block is lit once, at its final level.",
            "# classic  = Starlight's original FIFO propagation (use it if you suspect a lighting bug).",
            "propagation = " + PROPAGATION_DEFAULT,
            ""
    ));

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
            "",
            PROPAGATION_BLOCK,
            SAFE_VIEW_BLOCK
    ));

    static {
        final Map<String, String> values = new HashMap<>();
        Path path = null;
        boolean readExisting = false;
        String existingText = null;
        try {
            path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
            if (Files.isRegularFile(path)) {
                // Solix (0.3.4) - decoded leniently: a file saved in another encoding (e.g. an accented comment written
                // by an ANSI editor) used to throw and drop every setting back to the default
                existingText = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                readExisting = true;
                for (final String rawLine : existingText.split("\r?\n|\r", -1)) {
                    parseLine(rawLine, values);
                }
            } else {
                Files.createDirectories(path.getParent());
                Files.writeString(path, DEFAULT_FILE, StandardCharsets.UTF_8);
            }
        } catch (final IOException | RuntimeException ex) {
            LOGGER.warn("Solix: could not read or create config " + path + ", using defaults", ex);
        }
        if (readExisting && (!values.containsKey("propagation") || !values.containsKey("safe_light_view"))) {
            // config from an older Solix: add the new keys with their documentation, in the file's own line endings
            // Solix (0.3.4) - separate from reading: a failure here (read-only file) does not affect the parsed values
            try {
                final String separator = existingText.contains("\r\n") ? "\r\n" : "\n";
                final StringBuilder added = new StringBuilder(existingText.isEmpty() || existingText.endsWith("\n") ? "" : separator);
                if (!values.containsKey("propagation")) {
                    added.append(separator).append(PROPAGATION_BLOCK.replace("\n", separator));
                }
                if (!values.containsKey("safe_light_view")) {
                    added.append(separator).append(SAFE_VIEW_BLOCK.replace("\n", separator));
                }
                Files.writeString(path, added.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            } catch (final IOException | RuntimeException ex) {
                LOGGER.warn("Solix: could not add new settings to " + path + " (the other settings were read)", ex);
            }
        }

        PARALLEL = parseBoolean(values.get("parallel"), true);
        SAFE_LIGHT_VIEW = parseBoolean(values.get("safe_light_view"), true);
        int parallelism = parseInt(values.get("parallelism"), 0);
        final int cpuThreads = Runtime.getRuntime().availableProcessors();
        if (parallelism < 1) {
            parallelism = Math.max(1, cpuThreads / 4);
        } else if (parallelism > cpuThreads) {
            // Solix (0.3.4) - more light workers than CPU threads cannot help; huge values would start that many threads
            LOGGER.warn("Solix: parallelism " + parallelism + " in " + FILE_NAME + " is more than the " + cpuThreads
                    + " CPU threads, using " + cpuThreads);
            parallelism = cpuThreads;
        }
        PARALLELISM = parallelism;
        THROTTLE_NANOS = Math.max(0L, parseInt(values.get("throttle_ms"), 10)) * 1_000_000L;
        String propagation = values.get("propagation");
        propagation = propagation == null ? PROPAGATION_DEFAULT : propagation.toLowerCase(Locale.ROOT);
        if (!propagation.equals("targeted") && !propagation.equals("ordered") && !propagation.equals("classic")) {
            LOGGER.warn("Solix: invalid propagation '" + propagation + "' in " + FILE_NAME + ", using " + PROPAGATION_DEFAULT);
            propagation = PROPAGATION_DEFAULT;
        }
        PROPAGATION = propagation;
        ORDERED = !propagation.equals("classic");
        TARGETED = propagation.equals("targeted");
        LOGGER.info("Solix: light propagation = {}, safe light view = {}", PROPAGATION, SAFE_LIGHT_VIEW);
    }

    public static void init() {}

    /**
     * Solix (0.3.4): parses one {@code key = value} line. Handles a UTF-8 byte order mark, quoted keys, values in
     * double or single quotes (a {@code #} inside them is kept), and {@code #} comments after unquoted values.
     */
    private static void parseLine(final String rawLine, final Map<String, String> into) {
        String line = rawLine;
        if (!line.isEmpty() && line.charAt(0) == '﻿') {
            line = line.substring(1);
        }
        line = line.trim();
        if (line.isEmpty() || line.charAt(0) == '#') {
            return;
        }
        final int eq = line.indexOf('=');
        if (eq <= 0) {
            return;
        }
        final String key = unquote(line.substring(0, eq).trim());
        String value = line.substring(eq + 1).trim();
        if (!value.isEmpty() && (value.charAt(0) == '"' || value.charAt(0) == '\'')) {
            final int close = value.indexOf(value.charAt(0), 1);
            value = close > 0 ? value.substring(1, close) : value.substring(1);
        } else {
            final int comment = value.indexOf('#');
            if (comment >= 0) {
                value = value.substring(0, comment);
            }
        }
        into.put(key.toLowerCase(Locale.ROOT), value.trim());
    }

    private static String unquote(final String text) {
        if (text.length() >= 2 && (text.charAt(0) == '"' || text.charAt(0) == '\'') && text.charAt(text.length() - 1) == text.charAt(0)) {
            return text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

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
