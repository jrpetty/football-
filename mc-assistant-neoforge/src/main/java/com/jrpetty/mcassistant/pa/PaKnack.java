package com.jrpetty.mcassistant.pa;

import java.util.Random;

/**
 * [pa38] The knacks that come with practice: fishing, enchanting, trading, and the learner's slips that fade. Every
 * number here is a nudge on top of the game's own rules, never a replacement for them: the hook's real bite times and the
 * vanilla loot tables (a little of its own Lure and Luck of the Sea, at most half a level and one level), the enchanting
 * table's real offers (it only judges them better), a villager's real prices (it only chooses better, and knows which
 * vanilla discounts exist). Plain Java, so the tests can hold the shape: better with level, modest at the top, and the
 * slips gone by level 8. PaGrowth shows these on the Progress tab.
 */
public final class PaKnack {

    private PaKnack() {}

    static int lv(int l) {
        return Math.max(1, Math.min(10, l));                   // the ten levels (PaLevels.MAX)
    }

    // ------------------------------------------------------------------ fishing

    /** The game's wait before a fish is lured (ticks, uniform), then the time it takes to bite once it's coming. */
    public static final int LURE_MIN = 100, LURE_MAX = 600, HOOK_MIN = 20, HOOK_MAX = 80;
    /** How long the bite lasts before the fish gets away (ticks, uniform: the game's nibble). */
    public static final int NIBBLE_MIN = 20, NIBBLE_MAX = 40;
    /** One level of Lure takes this many ticks off the wait (the game's five seconds). */
    public static final int LURE_LEVEL_TICKS = 100;

    /** Its own knack for a quiet line: ticks off the wait, as if it had a little Lure of its own (half a level at 10). */
    public static int lureTicks(int l) {
        return Math.round((lv(l) - 1) * (LURE_LEVEL_TICKS / 2F) / 9F);
    }

    /** Its own knack for a good spot: luck, as if it had a little Luck of the Sea of its own (one level at 10). */
    public static double luck(int l) {
        return (lv(l) - 1) / 9.0;
    }

    /** The game's fishing table: fish 85 (quality -1), junk 10 (-2), treasure 5 (+2), weight + quality x luck. */
    public static double treasureChance(double luck) {
        double fish = Math.max(0, 85 - luck), junk = Math.max(0, 10 - 2 * luck), treasure = Math.max(0, 5 + 2 * luck);
        return treasure / (fish + junk + treasure);
    }

    /** The share of casts it strikes too soon (at the fish's ripple, before the bite): the cast is wasted. */
    public static double earlyStrike(int l) {
        double[] t = {0.08, 0.06, 0.045, 0.03, 0.02, 0.01, 0.005, 0.0, 0.0, 0.0};
        return t[lv(l) - 1];
    }

    /** The share of bites it isn't watching the float for (eyes elsewhere): it notices late, often too late. */
    public static double lapse(int l) {
        double[] t = {0.10, 0.08, 0.065, 0.05, 0.04, 0.03, 0.025, 0.02, 0.015, 0.01};
        return t[lv(l) - 1];
    }

    /** Its strike once it sees the float dip: its reaction (the archery table's), give or take, and a slow look when it
     *  lapses. Always draws the same four numbers, so the levels can be compared on the same luck. */
    public static int strikeTicks(int l, Random r) {
        int react = PaArchery.row(l).reactionTicks();
        double g = Math.abs(r.nextGaussian()), lapseRoll = r.nextDouble();
        int slow = 15 + r.nextInt(40);
        int t = (int) Math.round(react * 0.6 + 1 + g * (react * 0.25 + 1));
        if (lapseRoll < lapse(l)) t += slow;
        return Math.max(2, t);
    }

    /** Does it strike in time for a bite that lasts so long? (One draw: the same rule the goal uses.) */
    public static boolean landsIt(int l, int nibble, Random r) {
        return strikeTicks(l, r) < nibble;
    }

    /** Bites it loses, on average (early strikes and late ones), worked out by trying (the same draws at every level). */
    public static double lostBites(int l) {
        Random r = new Random(3838L);
        int lost = 0, n = 4000;
        for (int i = 0; i < n; i++) {
            boolean early = r.nextDouble() < earlyStrike(l);
            int nibble = NIBBLE_MIN + r.nextInt(NIBBLE_MAX - NIBBLE_MIN + 1);
            boolean late = !landsIt(l, nibble, r);
            if (early || late) lost++;
        }
        return lost / (double) n;
    }

    /** The average wait for a bite in open water under the sky, no Lure on the rod (seconds). */
    public static double meanWaitSeconds(int l) {
        double lure = Math.max(0, (LURE_MIN + LURE_MAX) / 2.0 - lureTicks(l));
        return (lure + (HOOK_MIN + HOOK_MAX) / 2.0) / 20.0;
    }

    /**
     * Where a cast lands (blocks out from its feet, level with the water dy below its eyes), cast at this pitch (the
     * game's: negative is up), as the game throws a hook: about 0.6 a tick plus half again along its look, 0.03 gravity
     * and 0.92 drag a tick, from 0.3 in front of its eyes. NaN when it never comes down that far.
     */
    public static double hookLands(double pitchDeg, double dy) {
        double t = Math.max(-5, Math.min(5, Math.tan(Math.toRadians(-pitchDeg))));
        double m = 0.6 / Math.sqrt(1 + t * t) + 0.5;
        double vx = m, vy = t * m, x = 0.3, y = 0;
        for (int i = 0; i < 300; i++) {
            vy -= 0.03;
            double nx = x + vx, ny = y + vy;
            if (vy < 0 && ny <= dy) return x + (nx - x) * ((y - dy) / Math.max(1e-9, y - ny));
            x = nx;
            y = ny;
            vx *= 0.92;
            vy *= 0.92;
        }
        return Double.NaN;
    }

    /** The pitch that lands a cast so far out at water dy below its eyes (the nearest it can manage). */
    public static double hookPitch(double horiz, double dy) {
        double best = -30, err = Double.MAX_VALUE;
        for (double p = -70; p <= 70; p += 0.5) {
            double x = hookLands(p, dy);
            if (Double.isNaN(x)) continue;
            if (Math.abs(x - horiz) < err) { err = Math.abs(x - horiz); best = p; }
        }
        return best;
    }

    /** How far off its cast is aimed (degrees, one sigma): its hands, as on a bow, a little rougher for a flick of the wrist. */
    public static double castSway(int l) {
        return PaArchery.row(l).swayDeg() * 1.5;
    }

    // ------------------------------------------------------------------ enchanting

    /** How far off its judgement of an offer's worth is (one sigma, a fraction). */
    public static double enchantSigma(int l) {
        double[] t = {0.45, 0.38, 0.31, 0.25, 0.20, 0.15, 0.11, 0.08, 0.06, 0.04};
        return t[lv(l) - 1];
    }

    /** How much it reads the clue (the one enchantment the table shows) rather than going by the cost: 0..1. */
    public static double clueWeight(int l) {
        return Math.min(1.0, 0.15 + (lv(l) - 1) * 0.1);
    }

    /** Does it know a bookshelf only counts with clear air between it and the table (and fifteen is all that counts)? */
    public static boolean knowsShelfGap(int l) {
        return lv(l) >= 4;
    }

    /** Will it spend a level on a book to turn over offers it doesn't like (the old trick), rather than take a poor one? */
    public static boolean rerolls(int l) {
        return lv(l) >= 7;
    }

    // ------------------------------------------------------------------ trading

    /** How far off its sense of a trade's worth is (one sigma, a fraction). */
    public static double tradeSigma(int l) {
        double[] t = {0.40, 0.34, 0.28, 0.23, 0.18, 0.14, 0.10, 0.08, 0.06, 0.04};
        return t[lv(l) - 1];
    }

    /** How many villagers near the one you sent it to it will compare prices with (itself included). */
    public static int villagersCompared(int l) {
        int[] t = {1, 1, 1, 2, 3, 4, 5, 6, 8, 10};
        return t[lv(l) - 1];
    }

    /** Does it know an enchanted book's worth (Mending, Protection IV...) beyond its price tag? */
    public static boolean knowsBooks(int l) {
        return lv(l) >= 6;
    }

    /** Does it know a price raised by demand comes back down after the villager restocks? */
    public static boolean knowsDemand(int l) {
        return lv(l) >= 5;
    }

    /** From this level it cures a zombie villager when asked (weakness, then a golden apple): the game's own discount. */
    public static final int CURE_LEVEL = 7;

    // ------------------------------------------------------------------ the learner's slips

    /** A block it means to place set one cell off (fixed at once, with a dry word): a share of placements. */
    public static double misplace(int l) {
        double[] t = {0.012, 0.008, 0.005, 0.003, 0.0015, 0.0007, 0.0002, 0.0, 0.0, 0.0};
        return t[lv(l) - 1];
    }

    /** A wrong turn on a long walk (it re-paths after a few steps): a share of long walks. */
    public static double wrongTurn(int l) {
        double[] t = {0.08, 0.06, 0.04, 0.03, 0.02, 0.01, 0.004, 0.0, 0.0, 0.0};
        return t[lv(l) - 1];
    }

    /** A worse tool picked up first for a new job (switched after a swing): a share of jobs. */
    public static double wrongTool(int l) {
        double[] t = {0.10, 0.07, 0.05, 0.035, 0.02, 0.01, 0.005, 0.0, 0.0, 0.0};
        return t[lv(l) - 1];
    }

    /** No two slips closer than this (ticks): a learner errs now and then, not all the time. */
    public static final int SLIP_GAP = 2400;

    /** One roll against a share. */
    public static boolean roll(double p, Random r) {
        return p > 0 && r.nextDouble() < p;
    }

    // ------------------------------------------------------------------ a step aside for a clear shot

    private static final int[] SIDE_NOTICE = {30, 26, 22, 18, 15, 12, 9, 7, 5, 4};
    private static final double[] SIDE_JUDGE = {1.6, 1.3, 1.05, 0.85, 0.65, 0.5, 0.35, 0.22, 0.12, 0.06};

    /** [pa38] How long (ticks) its player stands in its line of fire before it notices and steps aside: a learner stands there a while. */
    public static int sideNotice(int l) {
        return SIDE_NOTICE[Math.max(1, Math.min(10, l)) - 1];
    }

    /** [pa38] How far off its sense of a good spot to step to is (noise on the score, in blocks of walking): a learner picks worse angles. */
    public static double sideJudge(int l) {
        return SIDE_JUDGE[Math.max(1, Math.min(10, l)) - 1];
    }

    /** [pa38] The room it wants between the line and its player (blocks; the game's rule refuses under 1): more with practice. */
    public static double sideMargin(int l) {
        return 1.05 + 0.07 * (Math.max(1, Math.min(10, l)) - 1);
    }
}
