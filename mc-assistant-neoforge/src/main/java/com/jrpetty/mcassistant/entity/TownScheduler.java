package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import net.minecraft.server.level.ServerLevel;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [scale] The beat of each town's own rounds, and the server's budget for them in a tick.
 *
 * <p>A town's rounds (its elections, homes, families, bank, the trades it takes up; its streets, lit windows, museum,
 * library, assemblies and contentment) are each kept by their own clock, and were asked after by every one of its folk
 * at each of its thinking beats: a town of five hundred asked five times a tick for what is due once a second or once a
 * day. Here a town's rounds are let through once in {@link #FULL_EVERY} ticks while a player is in it, once in
 * {@link #NEAR_EVERY} while one is only near (TownLod NEAR: the planners at a slower beat), never while it sleeps (its
 * step has it), and in any tick only while the rounds of all towns together have taken less than townTickBudgetMs:
 * past that the next folk to ask, a tick or more later, has them. Every town's beat falls on its own ticks (its first
 * folk to ask after its beat), so towns do not all go on the same tick.
 *
 * <p>On the game tests' server, unless a test asks (FolkLod.liveForTests), every ask is let through as before.
 */
public final class TownScheduler {

    /** The town-wide jobs paced here. */
    public enum Job { ROUNDS, STREETS }

    /** A town with a player in it: its rounds at most once a second. */
    static final int FULL_EVERY = 20;
    /** A town a player is only near: once in five seconds. */
    static final int NEAR_EVERY = 100;

    private static final Map<UUID, long[]> LAST = new ConcurrentHashMap<>();
    private static long tick = Long.MIN_VALUE;
    private static long spentNanos;

    private TownScheduler() {}

    /** Forget every town's beat (a world closing, or a test). */
    public static void reset() {
        LAST.clear();
        tick = Long.MIN_VALUE;
        spentNanos = 0;
    }

    /** Are this town's rounds of this kind due now (and is there budget left in the tick)? If so, they are booked. */
    public static boolean due(ServerLevel level, UUID town, Job job) {
        if (!FolkLod.scaling(level)) return true;
        long serverTick = level.getServer().getTickCount();
        if (serverTick != tick) {
            tick = serverTick;
            spentNanos = 0;
        }
        TownLod.Lod lod = TownLod.lod(town);
        if (lod == TownLod.Lod.AWAY) return false;
        if (spentNanos >= AssistantConfig.townTickBudgetMs() * 1_000_000L) return false;
        long now = level.getGameTime();
        long[] last = LAST.computeIfAbsent(town, k -> {
            long[] a = new long[Job.values().length];
            Arrays.fill(a, Long.MIN_VALUE);
            return a;
        });
        int every = lod == TownLod.Lod.NEAR ? NEAR_EVERY : FULL_EVERY;
        long was = last[job.ordinal()];
        if (was != Long.MIN_VALUE && now >= was && now - was < every) return false;
        last[job.ordinal()] = now;
        return true;
    }

    /** The clock started on a town's rounds (pass to {@link #done}). */
    public static long start() {
        return System.nanoTime();
    }

    /** A town's rounds done: their time counted against the tick's budget. */
    public static void done(long started) {
        spentNanos += System.nanoTime() - started;
    }
}
