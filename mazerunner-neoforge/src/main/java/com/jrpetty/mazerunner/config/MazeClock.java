package com.jrpetty.mazerunner.config;

import java.util.ArrayList;
import java.util.List;

/**
 * The maze's custom day/night clock — pure logic, no Minecraft classes, so the
 * schedule can be unit-tested. A 24000-tick day is stretched over 90 real
 * minutes: daytime (0..12000) runs at 1/6 tick per real tick (60 min) and
 * night at 1/3 (30 min).
 *
 * <p>Daily timeline: 1000 the Glade doors open · 11500 the dusk warning ·
 * 12500 the doors seal · 18000 the maze shifts to tomorrow's layout · 24000
 * dawn, the day counter advances.
 */
public final class MazeClock {

    public static final int DOORS_OPEN_AT = 1000;
    public static final int DOORS_WARN_AT = 11500;
    public static final int DOORS_CLOSE_AT = 12500;
    public static final int SHIFT_AT = 18000;
    public static final int DAY_TICKS = 24000;
    /** Daytime ends here; past it the clock runs at double speed. */
    public static final int NIGHTFALL = 12000;

    public enum Event { DAWN, DOORS_OPEN, DUSK_WARNING, DOORS_CLOSE, MAZE_SHIFT }

    private MazeClock() {}

    /** The event that fires exactly at this day-tick, or null. */
    public static Event eventAt(int dayTick) {
        return switch (dayTick) {
            case 0 -> Event.DAWN;
            case DOORS_OPEN_AT -> Event.DOORS_OPEN;
            case DOORS_WARN_AT -> Event.DUSK_WARNING;
            case DOORS_CLOSE_AT -> Event.DOORS_CLOSE;
            case SHIFT_AT -> Event.MAZE_SHIFT;
            default -> null;
        };
    }

    /**
     * Every event crossed by advancing from {@code fromTick} (exclusive) to
     * {@code toTick} (inclusive), in order. This is what makes a time skip
     * behave like real time passing: jumping to nightfall still seals the
     * doors, and jumping to morning still reshapes the maze on the way.
     */
    public static List<Event> eventsCrossed(long fromTick, long toTick) {
        List<Event> out = new ArrayList<>();
        for (long tk = fromTick + 1; tk <= toTick; tk++) {
            Event e = eventAt((int) Math.floorMod(tk, DAY_TICKS));
            if (e != null) out.add(e);
        }
        return out;
    }

    /** Clock rate at a given day-tick, in sixths of a tick per real tick. */
    public static int sixthsPerTick(int dayTick) {
        return dayTick < NIGHTFALL ? 1 : 2;
    }

    /** True while the Glade doors are meant to be sealed. */
    public static boolean isNight(int dayTick) {
        return dayTick >= DOORS_CLOSE_AT || dayTick < DOORS_OPEN_AT;
    }

    /** Real seconds from day-tick {@code t} until day-tick {@code target}. */
    public static int realSecondsUntil(int t, int target) {
        long realTicks = 0;
        if (t < NIGHTFALL) {
            int dayTicks = Math.min(target, NIGHTFALL) - t;
            realTicks += Math.max(0, dayTicks) * 6L;
            if (target > NIGHTFALL) realTicks += (target - NIGHTFALL) * 3L;
        } else {
            realTicks += (target - t) * 3L;
        }
        return (int) (realTicks / 20);
    }

    /** The next absolute tick at or after {@code absTick} landing on {@code targetTick}. */
    public static long nextOccurrence(long absTick, int targetTick) {
        long base = absTick - Math.floorMod(absTick, (long) DAY_TICKS) + targetTick;
        return base <= absTick ? base + DAY_TICKS : base;
    }
}
