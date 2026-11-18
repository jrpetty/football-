package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * [long6] Why a town with nobody near is kept awake all the same (TownLod asks before it puts one to sleep).
 *
 * <p>A sleeping town is simulated at the rates it was measured to work at while it was watched. A town nobody has
 * ever watched has no rates (and on a world where no town has, there is no mean of the others either), so it slept
 * at nothing a day: the four-hundred-day run's town of eight was asleep five seconds after its founding and stood at
 * day one, 0 buildings and 0 food, for four hundred days. And asleep, nothing is ever built. So:
 * <ul>
 * <li><b>Held by an operator</b>: its heart's chunk is force-loaded with {@code /forceload}. Somebody asked for it to
 *     run; it runs in full.</li>
 * <li><b>Young</b>: it has not yet been watched for {@link #YOUNG_DAYS} days, so it has no rates of its own to sleep
 *     at. It stays awake (its own chunk keeping holds its ground) till it has.</li>
 * <li><b>Its own day</b>: asleep for {@code townLodWakeDays} days, it wakes for a day and a quarter in full: its folk
 *     farm, build and raise their children for real, and its rates are measured afresh. At most {@link #ROTA} towns
 *     take their day at once, so a world of many towns pays for two.</li>
 * </ul>
 */
public final class TownWake {

    enum Why { NONE, HELD, YOUNG, OWN_DAY }

    /** Watched for fewer days than this, a town has no rates of its own to sleep at (TownLod's shortest trend). */
    static final double YOUNG_DAYS = 3.0;
    /** A town's own day: a day and a quarter, so one more of its daily counts lands in it (TownLod.learn). */
    static final long OWN_DAY_TICKS = 30000L;
    /** At most this many towns have their own day at once. */
    static final int ROTA = 2;

    /** On the game tests' server none of it is in force unless a test asks (the lod tests and the perf probe put
     *  their towns to sleep by hand, and found them on held ground). */
    private static boolean liveInTests;

    private TownWake() {}

    /** Tests: these rules in force on the test server too, or not. */
    public static void liveForTests(boolean on) {
        liveInTests = on;
    }

    /** Is the town's heart force-loaded by an operator (vanilla's /forceload, not the mod's own chunk keeping)? */
    static boolean held(ServerLevel level, Villages.Village v) {
        return level.getForcedChunks().contains(ChunkPos.asLong(v.centre()));
    }

    /**
     * Why this town, with nobody near, is kept awake: {@code asleep} is it asleep now, {@code ownDays} the days its
     * own rates were measured over, {@code sleptAt} when it last went to sleep, {@code ownDayUntil} the end of its own
     * day (0 for none), {@code onOwnDay} how many towns are on their own day now.
     */
    static Why why(ServerLevel level, Villages.Village v, boolean asleep, double ownDays, long sleptAt, long ownDayUntil,
                   long time, int onOwnDay) {
        if (!liveInTests && level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) return Why.NONE;
        if (held(level, v)) return Why.HELD;
        if (ownDays < YOUNG_DAYS) return Why.YOUNG;
        if (ownDayUntil > time) return Why.OWN_DAY;
        int every = AssistantConfig.townLodWakeDays();
        if (asleep && every > 0 && time - sleptAt >= every * 24000L && onOwnDay < ROTA) return Why.OWN_DAY;
        return Why.NONE;
    }

    /** The status's word on it, after the folk loaded: "; asleep, nobody near", "; awake, too young...", or nothing. */
    public static String statusClause(java.util.UUID village) {
        if (TownLod.away(village)) return "; asleep, nobody near";
        String why = TownLod.keptAwakeWhy(village);
        return why == null ? "" : "; awake with nobody near, " + why;
    }

    /** The reason in words, for the log and the status. */
    static String words(Why why) {
        return switch (why) {
            case HELD -> "its heart is force-loaded";
            case YOUNG -> "too young to sleep: no rates of its own yet";
            case OWN_DAY -> "its own day in full";
            case NONE -> "nothing";
        };
    }
}
