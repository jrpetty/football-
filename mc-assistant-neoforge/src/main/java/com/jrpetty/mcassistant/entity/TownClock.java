package com.jrpetty.mcassistant.entity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * [econ-1c] The towns count their days by the world's time, not by where the player last set the sun.
 *
 * <p>Every town's morning (Market.tick: the census, the wages, the market, the businesses' books, the leases, the
 * budget, the firms) comes once a day, by the world's day number (its time of day over a day's length). The game's own
 * {@code /time set} is absolute: "time set day" on the fifth day puts the world back to the morning of its first, and
 * every town waited five days for a morning it had already had (no wages, no market, no books); a world played with the
 * daylight cycle off, or the clock set back to the same hour again and again, never came to another morning at all, and
 * a young town never reached its Market Opening Day.
 *
 * <p>So the world's day count is kept: the clock set back past the start of the day leaves the hour as the player set it
 * and the days as they were (the sun where it was put, the towns' calendar unbroken); and a whole day of the world's time
 * gone by with the clock held at the same day (the cycle off, or set back over and over) is a day gone by, the count one
 * more, the hour where it was, and the towns hold that morning late, whatever the hour. Nothing else changes: a night
 * slept through, or time moved on, is the game's own.
 *
 * <p>Not on the game tests' server, whose tests put the clock wherever they please, unless a test asks (liveForTests).
 */
public final class TownClock {

    private TownClock() {}

    /** A day's ticks, and the slack before a day held still counts as gone by (past the natural turn of the day). */
    static final long DAY = 24000L, HELD = DAY + 1200L;

    private static MinecraftServer server;
    private static long lastDay = Long.MIN_VALUE;
    /** The game time the day began (or was last counted on while held). */
    private static long mark;
    /** The day the count was moved on to by the world's time while the clock was held: its morning is held late. */
    private static long heldDay = Long.MIN_VALUE;
    private static boolean liveInTests;
    /** How often each was done, for the log and the tests: days kept when set back, days counted while held. */
    private static int keptBack, counted;

    /** Is the clock kept (in a world being played; on the tests' server only when a test asks)? */
    public static boolean active(MinecraftServer s) {
        return s != null && (liveInTests || !(s instanceof net.minecraft.gametest.framework.GameTestServer));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tick(event.getServer());
    }

    static void tick(MinecraftServer s) {
        if (s != server) {
            server = s;
            lastDay = Long.MIN_VALUE;
            heldDay = Long.MIN_VALUE;
        }
        if (!active(s)) {
            lastDay = Long.MIN_VALUE;
            return;
        }
        ServerLevel over = s.overworld();
        if (over == null) return;
        long time = over.getDayTime(), game = over.getGameTime(), day = time / DAY;
        if (lastDay == Long.MIN_VALUE) {
            lastDay = day;
            mark = game;
            return;
        }
        if (day > lastDay) {                     // the day turned (or the night slept through, or time moved on)
            lastDay = day;
            mark = game;
            heldDay = Long.MIN_VALUE;
            return;
        }
        if (day < lastDay) {                     // set back past the start of the day: the hour as set, the days as they were
            set(s, lastDay * DAY + Math.floorMod(time, DAY));
            keptBack++;
            com.mojang.logging.LogUtils.getLogger().info("[MCA-CLOCK] the clock was set back to day {}: kept at day {}, the hour as set", day + 1, lastDay + 1);
            return;
        }
        if (game - mark >= HELD) {               // a whole day of the world's time with the clock held at the same day
            set(s, time + DAY);
            lastDay = day + 1;
            mark = game;
            heldDay = day + 1;
            counted++;
            com.mojang.logging.LogUtils.getLogger().info("[MCA-CLOCK] a day of the world's time went by with the clock held: day {} begun, its morning held late", day + 2);
        }
    }

    private static void set(MinecraftServer s, long time) {
        for (ServerLevel l : s.getAllLevels()) l.setDayTime(time);
    }

    /** May this town's morning be held now whatever the hour: the day was counted on by the world's time while held. */
    public static boolean lateMorning(ServerLevel level, long day, long lastMorning) {
        return active(level.getServer()) && heldDay != Long.MIN_VALUE && day >= heldDay && day > lastMorning;
    }

    /** Tests: the clock kept on the tests' server too, or not. */
    public static void liveForTests(boolean on) {
        liveInTests = on;
        lastDay = Long.MIN_VALUE;
        heldDay = Long.MIN_VALUE;
    }

    public static void resetForTests() {
        liveForTests(false);
        keptBack = 0;
        counted = 0;
    }

    /** Tests: one look at the clock now. */
    public static void tickForTests(MinecraftServer s) {
        tick(s);
    }

    /** Tests: {days kept when set back, days counted while held}. */
    public static int[] countsForTests() {
        return new int[]{ keptBack, counted };
    }
}
