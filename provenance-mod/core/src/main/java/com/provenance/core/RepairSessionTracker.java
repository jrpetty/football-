package com.provenance.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Turns durability restoration into a sensible "repairs performed" count.
 *
 * <p>An anvil or smithing repair is one discrete act and counts immediately.
 * Mending is not: it restores a few points per experience orb, and counting
 * each orb would report a pickaxe as having been repaired forty thousand times.
 *
 * <p>Continuous restoration is therefore aggregated into sessions. The first
 * restoration opens a session and counts as one repair; further restoration
 * within the configured window extends that session and adds only to the
 * durability-restored total. A gap longer than the window starts a new session.
 */
public final class RepairSessionTracker {

    private record SessionKey(UUID recordId, UUID playerId) {
    }

    private final Map<SessionKey, Long> lastRestorationEpochMilli = new HashMap<>();
    private final long windowMillis;

    public RepairSessionTracker(int windowSeconds) {
        this.windowMillis = Math.max(0L, windowSeconds) * 1000L;
    }

    /** Result of folding a restoration event into the session history. */
    public record Outcome(boolean countsAsNewRepair, long durabilityRestored) {
    }

    /**
     * Records a discrete repair — anvil, smithing table, or a pack's custom
     * repair station. Always one repair, and it closes any open Mending session
     * so a later orb does not merge into it.
     */
    public Outcome discreteRepair(UUID recordId, UUID playerId, long durabilityRestored, long nowEpochMilli) {
        lastRestorationEpochMilli.remove(new SessionKey(recordId, playerId));
        return new Outcome(true, Math.max(0L, durabilityRestored));
    }

    /**
     * Records continuous restoration, such as a Mending orb.
     *
     * @return an outcome whose {@code countsAsNewRepair} is true only for the
     *         first restoration of a session
     */
    public Outcome continuousRestoration(UUID recordId, UUID playerId, long durabilityRestored, long nowEpochMilli) {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(playerId, "playerId");
        SessionKey key = new SessionKey(recordId, playerId);
        Long last = lastRestorationEpochMilli.get(key);
        boolean newSession = last == null || (nowEpochMilli - last) > windowMillis;
        lastRestorationEpochMilli.put(key, nowEpochMilli);
        return new Outcome(newSession, Math.max(0L, durabilityRestored));
    }

    /**
     * Drops sessions that can no longer be extended. Called periodically so a
     * long-running server does not retain an entry per item ever repaired.
     */
    public void pruneExpired(long nowEpochMilli) {
        lastRestorationEpochMilli.entrySet()
                .removeIf(e -> (nowEpochMilli - e.getValue()) > windowMillis);
    }

    /** Test and diagnostics hook. */
    public int openSessionCount() {
        return lastRestorationEpochMilli.size();
    }
}
