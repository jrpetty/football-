package com.jrpetty.mcassistant.pa;

/** [pa35] A hand's pace, the pure sums (PaPace uses them in game; the Progress tab and tests read them plain). */
public final class PaHand {

    private PaHand() {}

    /** A hand action's gap, in ticks: never under 10 (0.5 s); 10 to 15 at level 10; up to 22 at level 1 (hesitation). */
    public static final int MIN = 10, MAX = 15, MAX_L1 = 22;

    /** The slowest a hand action's gap can draw at this level (never the quickest: that's the cap, at every level). */
    public static int top(int level) {
        int lv = Math.max(1, Math.min(10, level));
        return MAX + Math.round((MAX_L1 - MAX) * (10 - lv) / 9.0F);
    }

    /** The average gap, in seconds, at this level. */
    public static double seconds(int level) {
        return (MIN + top(level)) / 2.0 / 20.0;
    }
}
