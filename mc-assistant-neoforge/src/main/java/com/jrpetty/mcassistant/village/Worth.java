package com.jrpetty.mcassistant.village;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * [market] The arithmetic of what things are worth and what they fetch, with no Minecraft in it.
 *
 * <p>Three things live here, so that one test can hold every one of them to account:
 * <ol>
 *   <li><b>Worth.</b> What a thing is worth anywhere, in village coin. Gathered things (what is mined, grown, caught,
 *       shorn) are worth what the effort and the rarity of getting them is (BaseWorth). Made things are worth their
 *       cheapest makings and the maker's work on top: a crafted thing is always worth a little more than what went into
 *       it. The market's board names a price for the things it deals in; for a made thing that is an anchor, raised to
 *       its makings when it was set under them, never an override. {@link #derive} works it all out from the recipes.</li>
 *   <li><b>A town's price.</b> Each town's price for a thing is its worth times a factor of how its stock stands against
 *       what it wants of it (supply and demand), kept in a band (half to three times) and moved a little each day:
 *       {@link #target}, {@link #step}.</li>
 *   <li><b>One spread for everybody.</b> The town asks a little over its price ({@link #SELL}) and pays a little under
 *       it ({@link #BUY}); folk and players alike. Whatever a player's discounts, a sale never goes under
 *       {@link #FLOOR} of the price, and the town never pays more for a thing than that same floor of the cheapest it
 *       sells the thing or its makings for ({@link #bidLot}, {@link #replacement}). So no round of buying from a town,
 *       making or unmaking, and selling back to it can come out ahead.</li>
 * </ol>
 *
 * <p>Deliberately free of Minecraft types, like VillageMath, so the plain tests check it on an ordinary machine.
 */
public final class Worth {

    private Worth() {}

    // ------------------------------------------------------------------ the spread

    /** What the town asks over its own price for a thing: everybody, folk and players, at every counter. */
    public static final double SELL = 1.10;
    /** What the town pays under its own price for what it buys. */
    public static final double BUY = 0.80;
    /** The least any sale comes to, against the town's price, whatever the buyer's discounts. Over BUY on every day. */
    public static final double FLOOR = 0.90;
    /** Market day: the asking price a little kinder, the paying price a little more generous (still under FLOOR). */
    public static final double MARKET_DAY_ASK = 0.95, MARKET_DAY_BID = 1.05;
    /** The keeper's margin over the town's price, about SELL: raised when a ware sells out, lowered when it sits. */
    public static final double LEAST_MARGIN = 1.05, MOST_MARGIN = 1.25, FOOD_MOST_MARGIN = 1.20;

    /** What the town asks for a lot of a thing it prices at {@code each} today, at this margin over it. Never under
     *  the least a lot may go for. */
    public static int askLot(double each, int lot, double margin, boolean marketDay) {
        double p = Math.max(0, each) * Math.max(1, lot) * margin * (marketDay ? MARKET_DAY_ASK : 1.0);
        return Math.max(leastLot(each, lot), (int) Math.round(p));
    }

    /** The least a lot of a thing priced {@code each} is ever sold for, every discount taken: FLOOR of its price, a
     *  coin at the least. Always more than the town would pay for the same lot (bidLot). */
    public static int leastLot(double each, int lot) {
        return Math.max(1, (int) Math.ceil(Math.max(0, each) * Math.max(1, lot) * FLOOR - 1e-9));
    }

    /**
     * What the town pays for a lot of a thing it prices at {@code each} today, when the cheapest it sells the thing or
     * its makings for is {@code replacement} a piece: BUY of its price (a little more on market day), never more than
     * FLOOR of the replacement, in whole coins rounded down. Zero when the lot is not worth a coin to it: it does not
     * buy what it would pay nothing for (a coin a lot of cookies was a coin out of nothing).
     */
    public static int bidLot(double each, double replacement, int lot, boolean marketDay) {
        return Math.max(0, (int) Math.floor(bidEach(each, replacement, marketDay) * Math.max(1, lot) + 1e-9));
    }

    /** What the town pays for one, to the hundredth (bidLot before the rounding down): BUY of its price, a little more on
     *  market day, never more than FLOOR of the replacement (a negative replacement: none known). */
    public static double bidEach(double each, double replacement, boolean marketDay) {
        double per = Math.max(0, each) * BUY * (marketDay ? MARKET_DAY_BID : 1.0);
        return replacement >= 0 ? Math.min(per, replacement * FLOOR) : per;
    }

    // ------------------------------------------------------------------ a town's price

    /** The band a town's price stays in, about the thing's worth. */
    public static final double LOWEST = 0.5, DEAREST = 3.0;
    /** The most a price moves in a day, and the least it bothers to (a move smaller than this is made whole). */
    public static final double MOST_A_DAY = 0.15, LEAST_A_DAY = 0.03;
    /** How much of the way to where it is heading a price goes in a day. */
    public static final double PACE = 0.35;
    /** The days of want a town's stock and day's making cover at the usual price. */
    public static final double DAYS_COVER = 3.0;

    public static double clamp(double f) {
        return Math.max(LOWEST, Math.min(DEAREST, f));
    }

    /** The stores' count against four lots of a thing (the usual), as a factor of its worth: half to three times. */
    public static double scarcity(int bundle, int stock) {
        int b = Math.max(1, bundle);
        int target = b * 4;
        double s = Math.sqrt((target + b) / (double) (Math.max(0, stock) + b));
        return clamp(s);
    }

    /**
     * Where a town's price is heading, against the thing's worth: by the days of want the town can meet from what it
     * holds and makes in a day (three days' cover the usual; less dear, more cheap), and for a thing little wanted by
     * what lies in the stores (four lots the usual); brought down by what folk would not pay. Within the band.
     */
    public static double target(int stock, int bundle, double output, double demand, double refused) {
        double scarcity = scarcity(bundle, stock);
        double t;
        if (demand < 0.5 && output < 0.5) {
            t = scarcity;
        } else {
            double cover = (Math.max(0, stock) + Math.max(0, output)) / Math.max(0.25, demand);
            double byDemand = Math.sqrt(DAYS_COVER / Math.max(0.1, cover));
            // A thing wanted by the dozen a day goes by its cover; one wanted now and then mostly by the stores' count.
            double w = Math.min(1.0, Math.max(0, demand) / (Math.max(0, demand) + Math.max(1, bundle)));
            t = Math.pow(scarcity, 1 - w) * Math.pow(byDemand, w);
        }
        if (refused > 0) t *= 1.0 - 0.5 * refused / (refused + Math.max(0, demand) + 1.0);
        return clamp(t);
    }

    /** A day's move of a price from where it was toward its target: a third of the way, three to fifteen in the hundred. */
    public static double step(double was, double target) {
        double gap = target - was;
        double step = gap * PACE;
        double most = was * MOST_A_DAY, least = was * LEAST_A_DAY;
        if (Math.abs(gap) <= least) step = gap;                                         // near enough: there
        else if (Math.abs(step) < least) step = Math.signum(gap) * least;                // a few in the hundred at the least
        step = Math.max(-most, Math.min(most, step));
        return clamp(was + step);
    }

    /** As step, for a thing held to a narrower band (the emerald) or none at all (the gold bar: lo = hi = 1). */
    public static double step(double was, double target, double lo, double hi) {
        double t = Math.max(lo, Math.min(hi, target));
        return Math.max(lo, Math.min(hi, step(Math.max(lo, Math.min(hi, was)), t)));
    }

    // ------------------------------------------------------------------ worth

    /** The work in a crafted thing: a tenth on top of what went in, and a little for each part. */
    public static final double CRAFT_MARKUP = 1.10, CRAFT_PER_PART = 0.02;
    /** A smelt: the fuel and the time. */
    public static final double SMELT_COST = 0.06;
    /** The stonecutter: next to nothing. */
    public static final double CUT_MARKUP = 1.02;

    /** How a thing is made: at the crafting table, in a furnace, at the stonecutter, at the smithing table; or had by
     *  emptying another (the bucket a bucket of milk leaves when it is drunk), which prices nothing and is a way to the
     *  empty thing all the same (replacement); or salvaged (an iron sword melted down to a nugget), which is a way to
     *  the scrap but never what the scrap is held to be worth more than. */
    public enum Way { CRAFT, SMELT, CUT, SMITH, EMPTY, SALVAGE }

    /** One part of a recipe: any one of these things, so many of it. */
    public record Part(List<String> options, int n) {
        public Part {
            options = List.copyOf(options);
        }

        public static Part of(String item, int n) {
            return new Part(List.of(item), n);
        }
    }

    /** A recipe: so many of {@code out} from these parts, made this way, giving these things back (a cake's buckets). */
    public record Recipe(String out, int count, List<Part> parts, Way way, List<String> back) {
        public Recipe {
            parts = List.copyOf(parts);
            back = List.copyOf(back);
        }

        public static Recipe of(String out, int count, Way way, Part... parts) {
            return new Recipe(out, count, List.of(parts), way, List.of());
        }

        /** How many things go in, all told. */
        public int pieces() {
            int n = 0;
            for (Part p : parts) n += p.n();
            return n;
        }
    }

    /** Is this a making (what a thing is held to be worth at least), not an emptying or a salvage? */
    public static boolean makes(Recipe r) {
        return r.way() != Way.EMPTY && r.way() != Way.SALVAGE;
    }

    /** What a batch is worth made this way out of makings worth {@code makings}, of so many pieces. */
    public static double made(Way way, double makings, int pieces) {
        return switch (way) {
            case SMELT -> makings + SMELT_COST;
            case CUT -> makings * CUT_MARKUP;
            case EMPTY -> makings;
            case SALVAGE -> makings + SMELT_COST;
            case CRAFT, SMITH -> makings * CRAFT_MARKUP + CRAFT_PER_PART * pieces;
        };
    }

    /** What the makings of one batch of this recipe come to at these values (the cheapest of each part's choices), less
     *  what it gives back; NaN if any part has no value yet. */
    public static double makings(Recipe r, ToDoubleFunction<String> value) {
        double cost = 0;
        for (Part p : r.parts()) {
            double best = Double.NaN;
            for (String o : p.options()) {
                double v = value.applyAsDouble(o);
                if (!Double.isNaN(v) && (Double.isNaN(best) || v < best)) best = v;
            }
            if (Double.isNaN(best)) return Double.NaN;
            cost += best * p.n();
        }
        for (String b : r.back()) {
            double v = value.applyAsDouble(b);
            if (!Double.isNaN(v)) cost -= v;
        }
        return Math.max(0, cost);
    }

    /** What one of a recipe's output comes to, made this way at these values, or NaN. */
    public static double cost(Recipe r, ToDoubleFunction<String> value) {
        double m = makings(r, value);
        if (Double.isNaN(m)) return Double.NaN;
        return made(r.way(), m, r.pieces()) / Math.max(1, r.count());
    }

    /**
     * Is this recipe the undoing of another (a block of iron back into its nine ingots, an ingot into nuggets): one
     * kind of thing in, and that thing made out of what comes out? It may price what it gives (nuggets have no other
     * way), but never raises what it undoes, and is no loss to be held to "worth at least its makings".
     */
    public static boolean unmaking(Recipe r, Map<String, List<Recipe>> byOut) {
        if (r.parts().size() != 1) return false;
        for (String in : r.parts().get(0).options()) {
            for (Recipe back : byOut.getOrDefault(in, List.of())) {
                for (Part p : back.parts()) if (p.options().contains(r.out())) return true;
            }
        }
        return false;
    }

    /** The recipes by what they make. */
    public static Map<String, List<Recipe>> byOut(Collection<Recipe> recipes) {
        Map<String, List<Recipe>> out = new HashMap<>();
        for (Recipe r : recipes) out.computeIfAbsent(r.out(), k -> new ArrayList<>()).add(r);
        return out;
    }

    /** What a derivation came to: every thing's worth, and how many were made from recipes and raised to their makings. */
    public record Derived(Map<String, Double> values, int fromRecipes, Set<String> raised) {}

    /**
     * Every thing's worth. {@code gathered}: what is gathered, at its own worth, never changed. {@code anchors}: the
     * board's named prices; a made one under its cheapest makings and the work is raised to them (an anchor that is
     * also gathered is gathered). Everything else made is worth its cheapest recipe, the work on top.
     */
    public static Derived derive(Map<String, Double> gathered, Map<String, Double> anchors, Collection<Recipe> recipes) {
        Map<String, List<Recipe>> byOut = byOut(recipes);
        Map<String, Double> anchor = new HashMap<>(anchors);
        anchor.keySet().removeAll(gathered.keySet());
        Set<String> raised = new HashSet<>();
        Map<String, Double> v = new HashMap<>();
        int fromRecipes = 0;
        // Raised anchors change what is made from them, so the whole is worked out again (four rounds at the most: a
        // raise feeds a raise only through a chain of board goods, and the board's chains are short).
        for (int round = 0; round < 4; round++) {
            v.clear();
            v.putAll(gathered);
            v.putAll(anchor);
            fromRecipes = relax(v, recipes, gathered.keySet(), anchor.keySet());
            boolean changed = false;
            for (Map.Entry<String, Double> e : anchor.entrySet()) {
                double floor = Double.NaN;
                for (Recipe r : byOut.getOrDefault(e.getKey(), List.of())) {
                    if (!makes(r) || unmaking(r, byOut)) continue;
                    double c = cost(r, k -> v.getOrDefault(k, Double.NaN));
                    if (!Double.isNaN(c) && (Double.isNaN(floor) || c < floor)) floor = c;
                }
                if (!Double.isNaN(floor) && floor > e.getValue() + 1e-9) {
                    e.setValue(floor);
                    raised.add(e.getKey());
                    changed = true;
                }
            }
            if (!changed) break;
        }
        v.putAll(anchor);
        return new Derived(v, fromRecipes, raised);
    }

    /** The cheapest way of making everything not fixed, as far as the recipes reach. Returns how many were priced. */
    private static int relax(Map<String, Double> v, Collection<Recipe> recipes, Set<String> gathered, Set<String> anchored) {
        Set<String> derived = new HashSet<>();
        for (int pass = 0; pass < 32; pass++) {
            boolean changed = false;
            for (Recipe r : recipes) {
                String out = r.out();
                if (r.way() == Way.EMPTY || gathered.contains(out) || anchored.contains(out)) continue;
                double c = cost(r, k -> v.getOrDefault(k, Double.NaN));
                if (Double.isNaN(c)) continue;
                Double have = v.get(out);
                if (have == null || c < have - 1e-9) {
                    v.put(out, c);
                    derived.add(out);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return derived.size();
    }

    /**
     * The least a thing can be had for from a town, a piece: its own price there, or its makings bought there (each
     * the cheapest way again), whichever is less; {@code local} is the town's price of a thing (NaN if it has none).
     * Unmakings count: a block bought and broken into ingots is a way to ingots. Looked down four recipes at most.
     */
    public static double replacement(String item, ToDoubleFunction<String> local, Map<String, List<Recipe>> byOut) {
        return replacement(item, local, byOut, 4, new HashSet<>(), new HashMap<>());
    }

    private static double replacement(String item, ToDoubleFunction<String> local, Map<String, List<Recipe>> byOut, int depth,
                                      Set<String> open, Map<String, Double> memo) {
        String key = item + "#" + depth;
        Double kept = open.contains(item) ? null : memo.get(key);
        if (kept != null) return kept;
        double best = local.applyAsDouble(item);
        if (depth > 0 && open.add(item)) {
            for (Recipe r : byOut.getOrDefault(item, List.of())) {
                double sum = 0;
                boolean ok = true;
                for (Part p : r.parts()) {
                    double cheapest = Double.NaN;
                    for (String o : p.options()) {
                        double c = replacement(o, local, byOut, depth - 1, open, memo);
                        if (!Double.isNaN(c) && (Double.isNaN(cheapest) || c < cheapest)) cheapest = c;
                    }
                    if (Double.isNaN(cheapest)) { ok = false; break; }
                    sum += cheapest * p.n();
                }
                if (!ok) continue;
                for (String b : r.back()) {
                    double v = local.applyAsDouble(b);
                    if (!Double.isNaN(v)) sum -= v;
                }
                double each = Math.max(0, sum) / Math.max(1, r.count());
                if (Double.isNaN(best) || each < best) best = each;
            }
            open.remove(item);
            memo.put(key, best);
        }
        return best;
    }
}
