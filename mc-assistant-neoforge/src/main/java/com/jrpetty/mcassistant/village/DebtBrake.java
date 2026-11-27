package com.jrpetty.mcassistant.village;

/**
 * [econ51] How hard a town in debt brakes its works: pure arithmetic, kept apart so it can be checked without a world.
 *
 * <p>The town's budget (entity/TownBudget) used to let its works write IOUs at the market freely up to one and three fifths
 * of what it could carry, and then stop dead: past that line it started nothing, its homes included, until the IOUs came
 * down. In a long run the line was crossed and stayed crossed (a colony owing four times what it could carry, its works'
 * roads and its builders' bricks still written on paper through doors the halt never shut), and the town stood still with
 * households waiting for a roof. And the IOUs it did pay off it paid by selling bonds, so the debt only changed its name and
 * climbed on, the brake never looking at it.
 *
 * <p>So the brake is a slope, not a wall, and it reads the whole debt:
 * <ul>
 * <li><b>The load</b> is the IOUs and the bonds over a week's revenue (a week's worth of bonds is ordinary borrowing).</li>
 * <li>Under what the town can carry, its works spend as they like (only its luxuries wait, as before).</li>
 * <li>Over it, its works may spend a share of a day's revenue each day: {@link #TOP_SHARE} of it just over the line (all
 * of it, and the debt would sit on the line for ever), sliding to {@link #LOW_SHARE} at {@link #HIGH} times what it can
 * carry and beyond. The rest of the day's money pays the debt down,
 * so the load always falls once it is over the line, and the builders never stop altogether (a day's floor).</li>
 * </ul>
 */
public final class DebtBrake {

    private DebtBrake() {}

    /** Bonds up to this many weeks' revenue are the town's ordinary borrowing, not a load on its works. */
    public static final double EASY_BOND_WEEKS = 1.0;
    /** At this many times what it can carry, and beyond, the works get the least share of the day's revenue. */
    public static final double HIGH = 1.6;
    /** The share of a day's revenue the works keep just over the line: a fifth of it at least pays the debt down. */
    public static final double TOP_SHARE = 0.8;
    /** The least share of a day's revenue the works keep, however deep the debt: the rest pays it down. */
    public static final double LOW_SHARE = 0.35;

    /** The debt that weighs on the works, pennies: the IOUs, and the bonds over a week's revenue. */
    public static long load(long ious, long bonds, long weekRevenue) {
        return Math.max(0, ious) + Math.max(0, bonds - Math.round(EASY_BOND_WEEKS * Math.max(0, weekRevenue)));
    }

    /**
     * The share of a day's revenue the works may spend at this load against what the town can carry: unbounded
     * (positive infinity) at or under it; {@link #TOP_SHARE} just over it, sliding to {@link #LOW_SHARE} at {@link #HIGH} times it.
     */
    public static double share(long load, long carry) {
        double r = load / (double) Math.max(1, carry);
        if (r <= 1.0) return Double.POSITIVE_INFINITY;
        if (r >= HIGH) return LOW_SHARE;
        return TOP_SHARE - (r - 1.0) / (HIGH - 1.0) * (TOP_SHARE - LOW_SHARE);
    }

    /** What the works may spend in a day, pennies ({@link Long#MAX_VALUE}: no bound): its share of the day's revenue, and
     *  never under the floor (the builders never stop altogether). */
    public static long allowance(long load, long carry, long dayRevenue, long floor) {
        double s = share(load, carry);
        if (Double.isInfinite(s)) return Long.MAX_VALUE;
        return Math.max(Math.max(0, floor), Math.round(s * Math.max(0, dayRevenue)));
    }

    /** How many of a thing at so many pennies each the room left will run to, at most {@code want}. */
    public static int fits(long room, double eachPence, int want) {
        if (want <= 0 || room <= 0) return 0;
        if (room == Long.MAX_VALUE || eachPence <= 0) return want;
        return (int) Math.min(want, (long) Math.floor(room / eachPence));
    }
}
