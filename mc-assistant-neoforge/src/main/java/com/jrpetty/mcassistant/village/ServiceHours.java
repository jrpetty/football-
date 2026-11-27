package com.jrpetty.mcassistant.village;

/**
 * [shops-homes] When a service point (a shop's counter, the bank's desk, the tavern's bar) is open: from {@code open}
 * to {@code close} in the time of day (0 is six in the morning, 24000 a day), wrapping past midnight when close is
 * before open (the tavern). Plain arithmetic, no game classes, so the unit tests read it.
 */
public record ServiceHours(int open, int close) {

    /** A day's ticks, and an hour's. */
    public static final int DAY = 24000, HOUR = 1000;

    public ServiceHours {
        open = Math.floorMod(open, DAY);
        close = Math.floorMod(close, DAY);
    }

    /** Open all day and night (a stall nobody keeps hours at). */
    public static final ServiceHours ALWAYS = new ServiceHours(0, 0);

    /** Is it open at this time of day? */
    public boolean isOpen(long timeOfDay) {
        if (open == close) return true;
        int t = (int) Math.floorMod(timeOfDay, (long) DAY);
        return open < close ? t >= open && t < close : t >= open || t < close;
    }

    /** Ticks until it next opens (0 if open now). */
    public int untilOpen(long timeOfDay) {
        if (isOpen(timeOfDay)) return 0;
        int t = (int) Math.floorMod(timeOfDay, (long) DAY);
        return Math.floorMod(open - t, DAY);
    }

    /** "7am", "6:30pm": a time of day as a clock reads it. */
    public static String clock(int timeOfDay) {
        int minutes = (int) Math.round(Math.floorMod(timeOfDay + 6 * HOUR, DAY) * 60.0 / HOUR);
        int h = (minutes / 60) % 24, m = minutes % 60;
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return h12 + (m == 0 ? "" : String.format(":%02d", m)) + (h < 12 ? "am" : "pm");
    }

    /** Ticks since midnight by the clock for this time of day. */
    private static int clockTicks(long timeOfDay) {
        return (int) Math.floorMod(timeOfDay + 6L * HOUR, (long) DAY);
    }

    /** "7am to 7pm", or "Open all hours". */
    public String words() {
        return open == close ? "Open all hours" : clock(open) + " to " + clock(close);
    }

    /** "Opens at 7am", "Opens at 7am tomorrow": when it opens next, said from this time of day. */
    public String nextOpening(long timeOfDay) {
        if (isOpen(timeOfDay)) return "Open now";
        // "Tomorrow" by the clock on the wall (midnight), not by the game's day, which turns at six in the morning.
        boolean tomorrow = clockTicks(timeOfDay) > clockTicks(open);
        return "Opens at " + clock(open) + (tomorrow ? " tomorrow" : "");
    }
}
