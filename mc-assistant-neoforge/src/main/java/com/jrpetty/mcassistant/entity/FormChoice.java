package com.jrpetty.mcassistant.entity;

import java.util.UUID;

/**
 * [featcivic] Which form of government a town takes up at its founding, worked out from its character alone (no world
 * needed, so a unit test can found a few thousand towns and count them).
 *
 * <p>Most towns are ordinary elected democracies: a full representative democracy (parties, a council of members voting
 * the laws, a mayor, regular elections) or the small town's elected leader. A town takes up another form only where its
 * character pulls hard that way: a martial, closed, hierarchical town may end up under its general or a lord; a devout
 * one under its chaplain; a strongly egalitarian, practical one may try a planned state. How hard is the margin by which
 * that form suits it better than an election would, turned into odds with a soft step (so a town on the edge may go
 * either way, the same town always the same way). Over towns as they are founded about three in four come out elected.
 */
public final class FormChoice {

    private FormChoice() {}

    /** Where the odds of an unelected form pass even: its margin over the election's fit. */
    static final double EVEN_AT = 2.0;
    /** How soft the step is: the margin that takes the odds from even to about three in four. */
    static final double SOFTNESS = 6.0;

    /** The forms a town may take up instead of electing its leader. */
    static final Government.Form[] OTHERS = {
        Government.Form.COUNCIL, Government.Form.LORD, Government.Form.GUILD, Government.Form.COMMUNE,
        Government.Form.CHAPLAIN, Government.Form.PLANNED, Government.Form.JUNTA };

    /** Is it an elected democracy (a representative one, or the small town's elected leader)? */
    public static boolean democratic(Government.Form f) {
        return f == Government.Form.DEMOCRACY || f == Government.Form.REEVE;
    }

    /** How well a form suits a town of this character (the seven axes, -100 to 100). */
    public static double fit(Government.Form f, int[] a) {
        double t = a[Ethos.Axis.TRADE.ordinal()], w = a[Ethos.Axis.WAR.ordinal()], fa = a[Ethos.Axis.FAITH.ordinal()],
            l = a[Ethos.Axis.LEARNING.ordinal()], d = a[Ethos.Axis.DOORS.ordinal()], y = a[Ethos.Axis.WAYS.ordinal()],
            k = a[Ethos.Axis.RANK.ordinal()];
        return switch (f) {
            case DEMOCRACY -> 13 + 0.15 * d + 0.1 * l - 0.1 * y + 0.05 * k;
            case REEVE -> 12 + 0.1 * d + 0.1 * t + 0.1 * y;
            case COUNCIL -> 0.35 * y + 0.1 * l - 0.1 * t;
            case LORD -> -4 + 0.4 * -k + 0.2 * w + 0.1 * y;
            case GUILD -> -4 + 0.35 * t + 0.2 * -l + 0.1 * -k;
            case COMMUNE -> -4 + 0.45 * k + 0.1 * -w + 0.1 * -t;
            case CHAPLAIN -> -4 + 0.45 * fa + 0.15 * y;
            case PLANNED -> -6 + 0.4 * k + 0.2 * l - 0.25 * t - 0.1 * fa;
            case JUNTA -> -12 + 0.45 * w - 0.15 * d - 0.15 * k;
        };
    }

    /** The form, of all of them, that suits this character best (no odds: for a reform's question). */
    public static Government.Form best(int[] a) {
        Government.Form best = Government.Form.DEMOCRACY;
        double top = Double.NEGATIVE_INFINITY;
        for (Government.Form f : Government.Form.values()) {
            double x = fit(f, a);
            if (x > top) { top = x; best = f; }
        }
        return best;
    }

    /** The unelected form that suits it best, and by how much it beats an election (its margin). */
    static Object[] strongestOther(int[] a) {
        double dem = Math.max(fit(Government.Form.DEMOCRACY, a), fit(Government.Form.REEVE, a));
        Government.Form alt = OTHERS[0];
        double top = Double.NEGATIVE_INFINITY;
        for (Government.Form f : OTHERS) {
            double x = fit(f, a);
            if (x > top) { top = x; alt = f; }
        }
        return new Object[]{ alt, top - dem };
    }

    /** The odds (0 to 1) that a town of this character takes up its strongest unelected form. */
    public static double othersOdds(int[] a) {
        double margin = (Double) strongestOther(a)[1];
        return 1.0 / (1.0 + Math.exp(-(margin - EVEN_AT) / SOFTNESS));
    }

    /** The form a town of this character takes up at its founding; the same town (seed) always the same. */
    public static Government.Form choose(int[] a, long seed) {
        Object[] o = strongestOther(a);
        double u = unit(seed, 1), v = unit(seed, 2);
        if (u < othersOdds(a)) return (Government.Form) o[0];
        double lean = fit(Government.Form.DEMOCRACY, a) - fit(Government.Form.REEVE, a) + (v - 0.5) * 12.0;
        return lean >= 0 ? Government.Form.DEMOCRACY : Government.Form.REEVE;
    }

    public static Government.Form choose(int[] a, UUID town) {
        return choose(a, town.getMostSignificantBits() * 31L ^ town.getLeastSignificantBits());
    }

    /** A number in [0, 1) from the seed and a salt (a well-mixed hash). */
    static double unit(long seed, int salt) {
        long z = seed + 0x9E3779B97F4A7C15L * (salt + 1);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }
}
