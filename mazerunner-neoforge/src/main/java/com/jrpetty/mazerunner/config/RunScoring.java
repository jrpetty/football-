package com.jrpetty.mazerunner.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Scoring for a runner's escape attempt — pure logic, no Minecraft classes.
 *
 * <p>A run is timed from the moment you first leave the Glade until you reach
 * the exit portal. Dying doesn't end the run, but it costs you: every death
 * adds a fixed penalty to your final time, so the fastest escape and the most
 * reckless one are not the same thing.
 */
public final class RunScoring {

    /** Time added to a run for each death. */
    public static final long DEATH_PENALTY_MS = 30_000L;

    /** One finished run, as it appears on the leaderboard. */
    public record Entry(String playerId, String name, long finalMillis, int deaths) {}

    private RunScoring() {}

    /** Final time = time on the clock plus the penalty for every death. */
    public static long finalMillis(long elapsedMillis, int deaths) {
        long elapsed = Math.max(0, elapsedMillis);
        long penalty = Math.max(0, deaths) * DEATH_PENALTY_MS;
        return elapsed + penalty;
    }

    /** Total penalty carried by a run, for reporting it separately. */
    public static long penaltyMillis(int deaths) {
        return Math.max(0, deaths) * DEATH_PENALTY_MS;
    }

    /** True if {@code candidate} beats {@code incumbent} (a negative incumbent means "none yet"). */
    public static boolean isBetter(long candidate, long incumbent) {
        return incumbent < 0 || candidate < incumbent;
    }

    /**
     * Leaderboard order: fastest final time first; ties broken by fewer deaths,
     * then by name so the ordering is stable and deterministic.
     */
    public static List<Entry> ranked(Collection<Entry> entries, int limit) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingLong(Entry::finalMillis)
            .thenComparingInt(Entry::deaths)
            .thenComparing(Entry::name, Comparator.nullsLast(Comparator.naturalOrder())));
        return limit >= 0 && sorted.size() > limit ? List.copyOf(sorted.subList(0, limit)) : List.copyOf(sorted);
    }

    /** {@code m:ss.t} — the usual speedrun clock. */
    public static String format(long millis) {
        long ms = Math.max(0, millis);
        long tenths = (ms / 100) % 10;
        long seconds = (ms / 1000) % 60;
        long minutes = ms / 60_000;
        return String.format("%d:%02d.%d", minutes, seconds, tenths);
    }
}
