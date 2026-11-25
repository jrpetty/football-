package com.jrpetty.mcassistant.pa;

import java.util.List;
import java.util.Random;

/** [pa38] Its judgement of trades, in plain Java (PaTrade uses it in game; the tests try it thousands of times). */
public final class PaTradeSense {

    private PaTradeSense() {}

    /** One offer as it sees it: what it costs in emeralds now, what it would cost with no discount or demand, and its worth. */
    public record Seen(int price, int base, double worth) {}

    /** The offer it chooses among these (the best worth for the price as it judges it), or -1 for none. */
    public static int choose(int level, List<Seen> offers, Random r) {
        double sigma = PaKnack.tradeSigma(level);
        int best = -1;
        double bs = -Double.MAX_VALUE;
        for (int i = 0; i < offers.size(); i++) {
            Seen s = offers.get(i);
            if (s.price() <= 0) continue;
            double v = s.worth() * (1 + r.nextGaussian() * sigma) / s.price();
            if (v > bs) { bs = v; best = i; }
        }
        return best;
    }

    /** The truly best of them (no misjudging): the tests compare against it. */
    public static int ideal(List<Seen> offers) {
        int best = -1;
        double bs = -Double.MAX_VALUE;
        for (int i = 0; i < offers.size(); i++) {
            Seen s = offers.get(i);
            if (s.price() <= 0) continue;
            double v = s.worth() / s.price();
            if (v > bs) { bs = v; best = i; }
        }
        return best;
    }

    /** Which of several villagers' prices for the same thing it takes (it sees a price as it is, give or take its judgement). */
    public static int cheapest(int level, int[] prices, Random r) {
        double sigma = PaKnack.tradeSigma(level) * 0.5;
        int best = -1;
        double bp = Double.MAX_VALUE;
        for (int i = 0; i < prices.length; i++) {
            if (prices[i] <= 0) continue;
            double p = prices[i] * (1 + r.nextGaussian() * sigma);
            if (p < bp) { bp = p; best = i; }
        }
        return best;
    }

    /** A bargain it can spot: a discount it can see, or (from level 6) a book worth well over its price. */
    public static boolean bargain(int level, Seen s) {
        if (s.price() < s.base()) return true;
        return PaKnack.knowsBooks(level) && s.worth() >= s.price() * 2.0;
    }

}
