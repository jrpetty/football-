package com.jrpetty.mcassistant.entity;

/**
 * [econ4] The town's trade cycle as its council's reckoners understand it: the credit cycle of the economists (Goodwin's
 * growth cycle with Minsky's debt, as Keen wrote it down), a day a step, with the town's own numbers blended in.
 *
 * <p>Three things move one another, and nothing is rolled on a die:
 * <ul>
 * <li><b>Employment</b> (the share of the town's hands at work): it grows when output grows faster than the town's hands
 *     and their tools improve.</li>
 * <li><b>The wage share</b> (what the hands are paid of what the town makes): wages are bid up when hands are scarce and
 *     fall back when many are idle (the Phillips curve).</li>
 * <li><b>Debt</b> (what the firms owe, against a day's output): investment beyond what profits pay for is borrowed.</li>
 * </ul>
 * Profit is what is left of output after the wages and the interest on the debt. The firms invest by how good profits are
 * (a little at a thin margin, boldly at a fat one), and investment is what makes output grow. So a <b>boom</b>: profits
 * good, investment and credit expanding, hands taken on; then, hands scarce, <b>wages and prices rise</b> and eat the
 * profit, the debt taken on in the boom costs more as the council raises its rate: <b>slowing</b>. Investment falls off,
 * output stalls, hands are laid off and the weakest firms fail: the <b>slump</b>. With hands idle wages fall back, the
 * rate is cut, debts are paid down or written off, profit recovers: <b>recovering</b>, and the next boom.
 *
 * <p>Kept bounded and recoverable: employment never under four fifths, the wage share between a little over half and
 * nineteen twentieths, the debt under four days' output; the council's rate (a Taylor rule on employment, with the town's
 * inflation besides) leans against both the boom and the slump. Real shocks move productivity for a few days: a harvest
 * failed, a mine's vein run out, a raid or a war, a new trade route opened.
 */
public final class CycleModel {

    /** Each day is this much of the economists' year: a full cycle takes a month or so of the town's days. */
    public static final double DT = 0.25;
    static final double NU = 3.0, ALPHA = 0.025, BETA = 0.02, DELTA = 0.01;
    /** The council's neutral rate (a year's), and the employment it aims at. */
    public static final double NEUTRAL = 0.04, AIM = 0.95;

    /** Employment, wage share, debt (over a day's output, scaled), the council's rate, the productivity shock, output index. */
    public double employment = 0.96, wageShare = 0.80, debt = 0.5, rate = NEUTRAL, shock = 0, output = 100;
    /** The last step's output growth (a year's rate), profit share and investment share. */
    public double growth, profit, invest;
    /** Inflation the town measured, a week (0.05 = five in the hundred), for the council's rule. */
    public double inflation;

    public CycleModel() {}

    public CycleModel(double employment, double wageShare, double debt, double rate, double output) {
        this.employment = employment;
        this.wageShare = wageShare;
        this.debt = debt;
        this.rate = rate;
        this.output = output;
    }

    /** Wage growth by employment (Phillips). */
    static double phillips(double l) {
        return -0.04 + 0.0001 / Math.pow(Math.max(0.02, 1 - l), 2);
    }

    /** Investment's share of output by the profit share. */
    static double investment(double p) {
        return Math.max(0.0, Math.min(0.30, -0.0065 + Math.exp(-5) * Math.exp(20 * p)));
    }

    /** The rate the council aims at: neutral, more as employment runs over its aim or prices run up, less in a slump. */
    public double ruleRate() {
        double target = NEUTRAL + 0.5 * (employment - AIM) + 0.3 * Math.max(-0.05, Math.min(0.10, inflation - 0.02));
        return Math.max(0.005, Math.min(0.10, target));
    }

    /** One day on. */
    public void step() {
        rate += 0.4 * (ruleRate() - rate);
        profit = 1 - wageShare - rate * debt;
        invest = investment(profit);
        growth = invest / NU - DELTA + shock;
        double dl = employment * (growth - ALPHA - BETA);
        double dw = wageShare * (phillips(employment) - ALPHA);
        double dd = invest - profit - debt * growth;
        employment = clamp(employment + dl * DT, 0.80, 0.995);
        wageShare = clamp(wageShare + dw * DT, 0.55, 0.95);
        debt = clamp(debt + dd * DT, 0.0, 4.0);
        output = Math.max(1, output * (1 + growth * DT));
    }

    /**
     * The town's own employment moved since yesterday (a share: -0.05, five in the hundred more out of work): the reckoning's
     * employment moved by so much of it. Its changes, not its level: a small town has every grown folk at a trade most days,
     * and its level pinned the reckoning at full employment, where the cycle cannot turn.
     */
    public void observeChange(double change, double weight) {
        if (!Double.isNaN(change)) employment = clamp(employment + Math.max(-0.05, Math.min(0.05, change)) * weight, 0.80, 0.995);
    }

    /** The debt the town's firms took on beyond the model's (new loans), as a share of a day's output: added. */
    public void credit(double moreDebt) {
        if (!Double.isNaN(moreDebt)) debt = clamp(debt + moreDebt, 0.0, 4.0);
    }

    static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    /** The phase: boom, slowing, slump, recovering, by where employment stands and which way output is going. */
    public enum Phase {
        BOOM("boom"), SLOWING("slowing"), SLUMP("slump"), RECOVERING("recovering");

        public final String words;

        Phase(String words) {
            this.words = words;
        }
    }

    /**
     * The phase from employment and its trend: above the aim and rising, a boom; above and falling, slowing; below and
     * falling, a slump; below and rising, recovering. {@code was} keeps a phase through a day's wobble.
     */
    public static Phase phase(double employment, double trend, Phase was) {
        double eps = 0.0015;
        boolean high = employment >= AIM - 0.01;
        if (Math.abs(trend) < eps && was != null) return was;
        if (high) return trend >= 0 ? Phase.BOOM : Phase.SLOWING;
        return trend < 0 ? Phase.SLUMP : Phase.RECOVERING;
    }
}
