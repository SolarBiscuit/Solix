package ca.spottedleaf.starlight.common.light;

import ca.spottedleaf.starlight.common.config.SolixConfig;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Solix: light engine counters for benchmarking, enabled with {@code -Dsolix.debug.lightStats=true}.
 * Engines count into plain fields while an update runs and add them here when it finishes; a daemon thread logs the
 * totals of the last interval every 10 seconds, separately for server and client engines, when anything happened.
 */
public final class LightStats {

    public static final boolean ENABLED = Boolean.getBoolean("solix.debug.lightStats");

    public static final int BLOCK_CHANGES = 0;
    public static final int CHUNK_LIGHT = 1;
    public static final int EDGE_CHECKS = 2;
    public static final int RELIGHT = 3;
    private static final String[] KIND_NAMES = {"block changes", "chunk lights", "edge checks", "relights"};

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long INTERVAL_MS = 10_000L;

    private static final Counters SERVER = new Counters("server");
    private static final Counters CLIENT = new Counters("client");

    static {
        if (ENABLED) {
            final Thread thread = new Thread(LightStats::logLoop, "Solix Light Stats");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static final class Counters {

        final String side;
        final LongAdder[] calls = new LongAdder[KIND_NAMES.length];
        final LongAdder[] nanos = new LongAdder[KIND_NAMES.length];
        final LongAccumulator[] maxNanos = new LongAccumulator[KIND_NAMES.length];
        final LongAdder increasePops = new LongAdder();
        final LongAdder decreasePops = new LongAdder();
        final LongAdder candidatesKept = new LongAdder();
        final LongAdder candidatesDarkened = new LongAdder();
        final LongAdder lightWrites = new LongAdder();
        final LongAdder sectionsReported = new LongAdder();
        final LongAdder sectionsSuppressed = new LongAdder();

        Counters(final String side) {
            this.side = side;
            for (int i = 0; i < KIND_NAMES.length; ++i) {
                this.calls[i] = new LongAdder();
                this.nanos[i] = new LongAdder();
                this.maxNanos[i] = new LongAccumulator(Math::max, 0L);
            }
        }

        void log() {
            final StringBuilder kinds = new StringBuilder();
            long totalCalls = 0L;
            for (int i = 0; i < KIND_NAMES.length; ++i) {
                final long calls = this.calls[i].sumThenReset();
                final long nanos = this.nanos[i].sumThenReset();
                final long max = this.maxNanos[i].getThenReset();
                if (calls == 0L) {
                    continue;
                }
                totalCalls += calls;
                kinds.append(String.format("%s %d (avg %.1f us, max %.1f us, total %.1f ms), ",
                        KIND_NAMES[i], calls, nanos / 1.0E3 / calls, max / 1.0E3, nanos / 1.0E6));
            }
            final long increasePops = this.increasePops.sumThenReset();
            final long decreasePops = this.decreasePops.sumThenReset();
            final long kept = this.candidatesKept.sumThenReset();
            final long darkened = this.candidatesDarkened.sumThenReset();
            final long writes = this.lightWrites.sumThenReset();
            final long reported = this.sectionsReported.sumThenReset();
            final long suppressed = this.sectionsSuppressed.sumThenReset();
            if (totalCalls == 0L) {
                return;
            }
            LOGGER.info("Solix light stats [{}, propagation={}, last {} s]: {}increase pops {}, decrease pops {}, "
                            + "candidates kept {} / darkened {}, light writes {}, sections reported {} / unchanged {}",
                    this.side, SolixConfig.PROPAGATION, INTERVAL_MS / 1000L, kinds, increasePops, decreasePops,
                    kept, darkened, writes, reported, suppressed);
        }
    }

    private static void logLoop() {
        for (;;) {
            try {
                Thread.sleep(INTERVAL_MS);
                SERVER.log();
                CLIENT.log();
            } catch (final InterruptedException ex) {
                return;
            } catch (final Throwable thr) {
                LOGGER.warn("Solix: light stats logging failed", thr);
            }
        }
    }

    /** Adds the engine's counters (and resets them) for one finished update of the given kind. */
    static void record(final int kind, final long startNanos, final StarLightEngine engine) {
        final long nanos = System.nanoTime() - startNanos;
        final Counters counters = engine.isClientSide ? CLIENT : SERVER;
        counters.calls[kind].increment();
        counters.nanos[kind].add(nanos);
        counters.maxNanos[kind].accumulate(nanos);
        counters.increasePops.add(engine.statIncreasePops);
        counters.decreasePops.add(engine.statDecreasePops);
        counters.candidatesKept.add(engine.statCandidatesKept);
        counters.candidatesDarkened.add(engine.statCandidatesDarkened);
        counters.lightWrites.add(engine.statLightWrites);
        counters.sectionsReported.add(engine.statSectionsReported);
        counters.sectionsSuppressed.add(engine.statSectionsSuppressed);
        engine.statIncreasePops = 0L;
        engine.statDecreasePops = 0L;
        engine.statCandidatesKept = 0L;
        engine.statCandidatesDarkened = 0L;
        engine.statLightWrites = 0L;
        engine.statSectionsReported = 0L;
        engine.statSectionsSuppressed = 0L;
    }

    private LightStats() {}
}
