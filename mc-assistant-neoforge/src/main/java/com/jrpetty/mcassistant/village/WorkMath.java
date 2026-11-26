package com.jrpetty.mcassistant.village;

/**
 * [wpb] The arithmetic of a job's targets and its two ways of pay, with no Minecraft in it (so the plain tests check it).
 * <ul>
 * <li><b>Targets.</b> What a competent hand gets through in a shift: the shift's length over a task's par time at a
 *     competent pace (nine tenths of par) plus the time it takes to find the next task, times what a task is worth in the
 *     card's units. The target for three stars is three fifths of that: honest effort makes it, real skill makes five.
 *     It grows a little with the worker's skill and rank, and comes down for what the worker could not help: hours with
 *     no work to be had (no ripe crops, no ore, the stores short of makings), and rain on outdoor work.</li>
 * <li><b>Stars for the day.</b> Output against target: nothing done is none, anything is one, three fifths of it two, the
 *     target three, half as much again four, and three fifths over five.</li>
 * <li><b>Commission.</b> Derived from the economy, never invented: what the stores pay for a unit (their bid) less the
 *     employer's running costs on it, and a share of what is left (half to seven tenths) to the worker. Work that makes no
 *     goods (the counter, the watch, a load hauled) is valued at what a competent day of it is worth to the employer: the
 *     going wage and its margin, spread over a competent day's units. Then held so that a three-star day on commission
 *     pays nine tenths to thirteen tenths of the going daily wage; a rate that would break that is capped, and says so.</li>
 * </ul>
 */
public final class WorkMath {

    private WorkMath() {}

    /** A shift's length (ticks), a competent hand's pace against par, the ticks to find the next task. */
    public static final long SHIFT = 4000L;
    public static final double PACE = 0.9;
    public static final long GAP = 80L;
    /** The three-star target as a share of a competent shift. */
    public static final double TARGET_SHARE = 0.6;
    /** Day stars against the target: 2 at three fifths, 3 at the target, 4 at 1.3, 5 at 1.6. */
    public static final double[] STAR_AT = { 0.6, 1.0, 1.3, 1.6 };
    /** A three-star commissioned day against the going wage, the least and most. */
    public static final double DAY_LO = 0.9, DAY_HI = 1.3;
    /** The worker's share of the employer's margin, the least, usual and most. */
    public static final double SHARE_LO = 0.5, SHARE = 0.6, SHARE_HI = 0.7;
    /** What a service hand's day is worth to the employer over its wage. */
    public static final double SERVICE_MARGIN = 1.6;
    /** The base wage a commissioned service hand keeps, as a share of the going wage (produce: none). */
    public static final double SERVICE_BASE = 0.15;

    /** A competent shift's output in units: the shift over the task's time at pace and the gap, times its units. */
    public static double competent(long taskTicks, double unitsPerTask, long shiftTicks) {
        double each = Math.max(20, taskTicks) * PACE + GAP;
        return Math.max(0, shiftTicks) / each * Math.max(0, unitsPerTask);
    }

    /** The three-star target for a competent shift's output: at least one. */
    public static int target(double competent) {
        return (int) Math.max(1, Math.round(competent * TARGET_SHARE));
    }

    /**
     * The target for this worker today: 4% a skill level (0..4), a tenth a rank (0 hand, 1 senior, 2 head); less the
     * share of the shift with no work to be had; less a fifth for outdoor work in the rain. At least one unless there was
     * no work at all (then nothing is asked).
     */
    public static int scaled(int base, int level, int rank, double noWorkShare, boolean rainOutdoors) {
        double t = base * (1 + 0.04 * Math.max(0, Math.min(4, level))) * (1 + 0.1 * Math.max(0, Math.min(2, rank)));
        double nw = Math.max(0, Math.min(1, noWorkShare));
        if (nw >= 0.95) return 0;
        t *= 1 - nw;
        if (rainOutdoors) t *= 0.8;
        return (int) Math.max(1, Math.round(t));
    }

    /** Stars for the day (0 to 5): output against the target; a target of none is met by turning up. */
    public static int dayStars(double units, int target) {
        if (target <= 0) return 3;
        if (units <= 0) return 0;
        double r = units / target;
        int s = 1;
        for (int i = 0; i < STAR_AT.length; i++) if (r >= STAR_AT[i] - 1e-9) s = 2 + i;
        return s;
    }

    /** A produce unit's commission: the bid, less the running costs on it, times the worker's share. */
    public static double commission(double bid, double costShare, double share) {
        double margin = Math.max(0, bid) * (1 - Math.max(0, Math.min(0.9, costShare)));
        return margin * Math.max(SHARE_LO, Math.min(SHARE_HI, share));
    }

    /** A service unit's worth to the employer: a competent day's worth (the wage and its margin) over its units. */
    public static double serviceWorth(double goingWage, double competentUnits) {
        return Math.max(0, goingWage) * SERVICE_MARGIN / Math.max(1, competentUnits);
    }

    /**
     * The rate held to the bounds: a three-star day ({@code target} units, plus the base) between nine and thirteen
     * tenths of the going wage. Returns {rate, 1 if capped from above, -1 if raised from below, else 0}.
     */
    public static double[] bounded(double rate, double base, int target, double goingWage) {
        if (target <= 0 || goingWage <= 0) return new double[]{ Math.max(0, rate), 0 };
        double day = base + rate * target;
        double lo = goingWage * DAY_LO, hi = goingWage * DAY_HI;
        if (day > hi) return new double[]{ Math.max(0, (hi - base) / target), 1 };
        if (day < lo) return new double[]{ Math.max(0, (lo - base) / target), -1 };
        return new double[]{ rate, 0 };
    }

    /** What so many units pay on commission at this rate, in hundredths of a coin (rounded down). */
    public static long payHundredths(double rate, double units) {
        return (long) Math.floor(Math.max(0, rate) * Math.max(0, units) * 100 + 1e-6);
    }

    /**
     * A folk's choice of pay from its character's drive (Temperament: ambitious and reckless lean to commission, timid and
     * easygoing to salary) and its confidence in its skill (0..1). True for commission.
     */
    public static boolean folkTakesCommission(int drive, double skill, double roll) {
        double p = 0.3 + 0.12 * drive + 0.25 * (Math.max(0, Math.min(1, skill)) - 0.5);
        return roll < Math.max(0.05, Math.min(0.9, p));
    }

    /** "1.5c" or "0.25c": a coin figure to the hundredth, trimmed. */
    public static String coin(double c) {
        if (c >= 10) return Math.round(c) + "c";
        String s = String.format(java.util.Locale.ROOT, "%.2f", c);
        if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + "c";
    }
}
