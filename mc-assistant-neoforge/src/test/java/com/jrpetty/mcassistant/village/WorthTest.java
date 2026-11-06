package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [market] The value engine and the spread, checked without a world: a small economy of real recipes (the farm, the
 * woods, the forge), and the town's whole base list.
 */
class WorthTest {

    private static Worth.Part p(String item, int n) {
        return Worth.Part.of(item, n);
    }

    /** A little world: wheat to bread and cookies, logs to planks to sticks to a pickaxe, raw iron to ingots to a block
     *  and back, nuggets out of an ingot, sand to glass, milk into a cake that gives its buckets back. */
    private static List<Worth.Recipe> recipes() {
        List<Worth.Recipe> r = new ArrayList<>();
        r.add(Worth.Recipe.of("bread", 1, Worth.Way.CRAFT, p("wheat", 1), p("wheat", 1), p("wheat", 1)));
        r.add(Worth.Recipe.of("cookie", 8, Worth.Way.CRAFT, p("wheat", 1), p("cocoa", 1), p("wheat", 1)));
        r.add(Worth.Recipe.of("hay", 1, Worth.Way.CRAFT, p("wheat", 9)));
        r.add(Worth.Recipe.of("wheat", 9, Worth.Way.CRAFT, p("hay", 1)));
        r.add(Worth.Recipe.of("planks", 4, Worth.Way.CRAFT, p("log", 1)));
        r.add(Worth.Recipe.of("stick", 4, Worth.Way.CRAFT, p("planks", 1), p("planks", 1)));
        r.add(Worth.Recipe.of("iron_ingot", 1, Worth.Way.SMELT, p("raw_iron", 1)));
        r.add(Worth.Recipe.of("iron_block", 1, Worth.Way.CRAFT, p("iron_ingot", 9)));
        r.add(Worth.Recipe.of("iron_ingot", 9, Worth.Way.CRAFT, p("iron_block", 1)));
        r.add(Worth.Recipe.of("iron_nugget", 9, Worth.Way.CRAFT, p("iron_ingot", 1)));
        r.add(Worth.Recipe.of("iron_ingot", 1, Worth.Way.CRAFT, p("iron_nugget", 9)));
        r.add(Worth.Recipe.of("iron_pickaxe", 1, Worth.Way.CRAFT, p("iron_ingot", 3), p("stick", 2)));
        r.add(Worth.Recipe.of("bucket", 1, Worth.Way.CRAFT, p("iron_ingot", 3)));
        r.add(Worth.Recipe.of("glass", 1, Worth.Way.SMELT, p("sand", 1)));
        r.add(Worth.Recipe.of("stone", 1, Worth.Way.SMELT, p("cobblestone", 1)));
        r.add(Worth.Recipe.of("stone_bricks", 4, Worth.Way.CRAFT, p("stone", 4)));
        r.add(Worth.Recipe.of("stone_bricks", 1, Worth.Way.CUT, p("stone", 1)));
        r.add(new Worth.Recipe("cake", 1, List.of(p("milk_bucket", 3), p("sugar", 2), p("egg", 1), p("wheat", 3)), Worth.Way.CRAFT,
            List.of("bucket", "bucket", "bucket")));
        r.add(Worth.Recipe.of("sugar", 1, Worth.Way.CRAFT, p("sugar_cane", 1)));
        r.add(Worth.Recipe.of("bucket", 1, Worth.Way.EMPTY, p("milk_bucket", 1)));
        r.add(Worth.Recipe.of("iron_nugget", 1, Worth.Way.SALVAGE, p("iron_pickaxe", 1)));
        r.add(new Worth.Recipe("torch", 4, List.of(new Worth.Part(List.of("coal", "charcoal"), 1), p("stick", 1)), Worth.Way.CRAFT, List.of()));
        r.add(Worth.Recipe.of("charcoal", 1, Worth.Way.SMELT, p("log", 1)));
        return r;
    }

    private static Map<String, Double> gathered() {
        Map<String, Double> g = new HashMap<>();
        g.put("wheat", 0.1);
        g.put("cocoa", 0.3);
        g.put("log", 0.25);
        g.put("raw_iron", 1.0);
        g.put("sand", 0.05);
        g.put("cobblestone", 0.04);
        g.put("egg", 0.2);
        g.put("sugar_cane", 0.15);
        g.put("milk_bucket", 5.3);
        g.put("coal", 0.4);
        return g;
    }

    /** The board's named prices, some under their makings as the old board had them (bread, cookies, sugar). */
    private static Map<String, Double> anchors() {
        Map<String, Double> a = new HashMap<>();
        a.put("bread", 0.3);
        a.put("cookie", 0.2);
        a.put("sugar", 0.1);
        a.put("iron_ingot", 1.5);
        a.put("bucket", 5.0);
        a.put("glass", 0.2);
        a.put("cake", 4.0);
        a.put("wheat", 0.1);           // a gathered thing on the board stays gathered
        a.put("planks", 0.07);
        a.put("torch", 0.1);
        return a;
    }

    private static Worth.Derived derived() {
        return Worth.derive(gathered(), anchors(), recipes());
    }

    @Test
    @DisplayName("every thing in reach of the recipes is priced, above nothing")
    void everythingPriced() {
        Map<String, Double> v = derived().values();
        Set<String> all = new HashSet<>(gathered().keySet());
        for (Worth.Recipe r : recipes()) {
            all.add(r.out());
            for (Worth.Part p : r.parts()) all.addAll(p.options());
        }
        for (String item : all) {
            Double x = v.get(item);
            assertTrue(x != null && x > 0 && x < 1000, item + " is priced sanely: " + x);
        }
    }

    @Test
    @DisplayName("a made thing is worth at least its cheapest makings and a little for the work; unmakings excepted")
    void madeOverMakings() {
        Worth.Derived d = derived();
        Map<String, Double> v = d.values();
        Map<String, List<Worth.Recipe>> byOut = Worth.byOut(recipes());
        for (Map.Entry<String, List<Worth.Recipe>> e : byOut.entrySet()) {
            if (gathered().containsKey(e.getKey())) continue;
            double cheapest = Double.MAX_VALUE, cheapestMakings = 0;
            for (Worth.Recipe r : e.getValue()) {
                if (!Worth.makes(r) || Worth.unmaking(r, byOut)) continue;
                double c = Worth.cost(r, k -> v.getOrDefault(k, Double.NaN));
                if (c < cheapest) {
                    cheapest = c;
                    cheapestMakings = Worth.makings(r, k -> v.getOrDefault(k, Double.NaN)) / r.count();
                }
            }
            if (cheapest == Double.MAX_VALUE) continue;
            double worth = v.get(e.getKey());
            assertTrue(worth >= cheapest - 1e-9, e.getKey() + " is worth its makings and the work: " + worth + " against " + cheapest);
            assertTrue(worth > cheapestMakings, e.getKey() + " is worth a little more than what went into it: " + worth + " against "
                + cheapestMakings);
        }
        assertTrue(d.raised().contains("bread") && d.raised().contains("sugar") && d.raised().contains("planks"),
            "the board's prices under their makings are raised: " + d.raised());
        assertFalse(d.raised().contains("iron_ingot") || d.raised().contains("cookie") || d.raised().contains("glass"),
            "and the rest stand: " + d.raised());
        assertTrue(Math.abs(v.get("iron_ingot") - 1.5) < 1e-9 && Math.abs(v.get("cookie") - 0.2) < 1e-9, "over their makings, as named");
    }

    @Test
    @DisplayName("gathered things keep their worth; an unmaking never raises what it undoes")
    void gatheredStand() {
        Map<String, Double> v = derived().values();
        for (Map.Entry<String, Double> e : gathered().entrySet()) {
            assertTrue(Math.abs(v.get(e.getKey()) - e.getValue()) < 1e-9, e.getKey() + " stays " + e.getValue() + ": " + v.get(e.getKey()));
        }
        Map<String, List<Worth.Recipe>> byOut = Worth.byOut(recipes());
        Worth.Recipe split = null;
        for (Worth.Recipe r : byOut.get("iron_ingot")) if (r.parts().get(0).options().contains("iron_block")) split = r;
        assertTrue(split != null && Worth.unmaking(split, byOut), "a block back into ingots is an unmaking");
        assertTrue(v.get("iron_nugget") > 0 && v.get("iron_nugget") < v.get("iron_ingot"), "nuggets priced out of the ingot: " + v.get("iron_nugget"));
        assertTrue(v.get("iron_block") >= 9 * v.get("iron_ingot"), "a block is its nine ingots and more: " + v.get("iron_block"));
    }

    @Test
    @DisplayName("what a recipe gives back is taken off its makings (the cake's buckets)")
    void remaindersCount() {
        Map<String, Double> v = derived().values();
        double cake = v.get("cake");
        assertTrue(cake < 4.0 + 1e-9 && cake > 3 * 0.3, "a cake is its milk, sugar, egg and wheat, not three full buckets: " + cake);
    }

    @Test
    @DisplayName("a town's price keeps to its band and moves a little a day")
    void bandAndPace() {
        for (int stock = 0; stock < 5000; stock += 37) {
            for (double demand : new double[]{ 0, 0.4, 3, 40, 900 }) {
                double t = Worth.target(stock, 8, demand / 2, demand, demand / 5);
                assertTrue(t >= Worth.LOWEST - 1e-9 && t <= Worth.DEAREST + 1e-9, "target in the band: " + t);
            }
        }
        assertTrue(Worth.target(0, 8, 0, 40, 0) > 1.5, "nothing in store and much wanted: dear");
        assertTrue(Worth.target(4000, 8, 50, 2, 0) < 0.7, "a glut: cheap");
        double f = 1.0;
        for (int d = 0; d < 60; d++) {
            double next = Worth.step(f, 3.0);
            assertTrue(next <= f * (1 + Worth.MOST_A_DAY) + 1e-9, "never more than fifteen in the hundred a day");
            f = next;
        }
        assertTrue(Math.abs(f - 3.0) < 1e-6, "but it gets there: " + f);
        assertTrue(Worth.step(1.0, 3.0, 1.0, 1.0) == 1.0, "the gold bar does not move");
        assertTrue(Worth.step(1.0, 3.0, 0.8, 1.25) <= 1.25 + 1e-9, "the emerald keeps to its narrow band");
    }

    @Test
    @DisplayName("the spread: asked over, paid under, the least a sale goes for always over what the town pays")
    void spread() {
        Random rnd = new Random(7);
        for (int i = 0; i < 20000; i++) {
            double each = Math.exp(rnd.nextDouble() * 9 - 6);          // a thousandth of a coin to a few hundred
            int lot = 1 + rnd.nextInt(64);
            boolean md = rnd.nextBoolean();
            double margin = Worth.LEAST_MARGIN + rnd.nextDouble() * (Worth.MOST_MARGIN - Worth.LEAST_MARGIN);
            int ask = Worth.askLot(each, lot, margin, md), least = Worth.leastLot(each, lot), bid = Worth.bidLot(each, each, lot, md);
            assertTrue(ask >= least, "asked at least the least: " + ask + " " + least);
            assertTrue(least > bid, "the least a lot sells for is over what the town pays for it: " + least + " against " + bid
                + " (" + each + " x " + lot + ")");
            assertTrue(bid <= each * lot, "the town never pays over a thing's price");
        }
        assertTrue(Worth.bidLot(0.08, 0.08, 8, true) == 0, "a lot not worth a coin is not bought for one");
    }

    @Test
    @DisplayName("no round of buying from a town, making or unmaking, and selling back comes out ahead")
    void noLoops() {
        Map<String, Double> v = derived().values();
        Map<String, List<Worth.Recipe>> byOut = Worth.byOut(recipes());
        Set<String> items = new HashSet<>(v.keySet());
        Random rnd = new Random(11);
        for (int trial = 0; trial < 400; trial++) {
            // Each town's own factors, anywhere in the band, and the day.
            Map<String, Double> local = new HashMap<>();
            for (String it : items) local.put(it, v.get(it) * (Worth.LOWEST + rnd.nextDouble() * (Worth.DEAREST - Worth.LOWEST)));
            boolean md = rnd.nextBoolean();
            for (Worth.Recipe r : recipes()) {
                // Buy the makings at the least the town ever sells them for (every discount taken), lot by lot of one.
                double paid = 0;
                for (Worth.Part p : r.parts()) {
                    double cheapest = Double.MAX_VALUE;
                    for (String o : p.options()) cheapest = Math.min(cheapest, Worth.leastLot(local.get(o), p.n()));
                    paid += cheapest;
                }
                for (String b : r.back()) paid -= Worth.bidLot(local.get(b), Worth.replacement(b, local::get, byOut), 1, md);
                // And sell what they made back to it.
                double c = Worth.replacement(r.out(), local::get, byOut);
                int got = Worth.bidLot(local.get(r.out()), c, r.count(), md);
                assertTrue(got <= paid + 1e-9, "making " + r.out() + " from what the town sells: paid " + paid + ", got " + got);
            }
            // Two steps: sticks from planks from logs, into a pickaxe with ingots from raw iron.
            double paid = Worth.leastLot(local.get("log"), 1) * 1.0 / 2 + 3 * Worth.leastLot(local.get("raw_iron"), 1);
            double c = Worth.replacement("iron_pickaxe", local::get, byOut);
            int got = Worth.bidLot(local.get("iron_pickaxe"), c, 1, md);
            assertTrue(got <= paid + 1e-9, "the long way round pays no better: paid " + paid + ", got " + got);
        }
    }

    @Test
    @DisplayName("the town's base list: every line reads, every worth above nothing (but the friend nobody sells), none twice")
    void baseList() {
        Set<String> seen = new HashSet<>();
        int lines = BaseWorth.LIST.length;
        List<BaseWorth.Entry> all = BaseWorth.entries();
        assertTrue(all.size() == lines, "every line reads: " + all.size() + " of " + lines);
        for (BaseWorth.Entry e : all) {
            assertTrue(seen.add(e.id()), e.id() + " is listed once");
            assertTrue("FTSOAPC".indexOf(e.kind()) >= 0, e.id() + " has a kind: " + e.kind());
            if (e.id().equals("mc_assistant:memory_core")) continue;
            assertTrue(e.worth() > 0 && e.worth() <= 500, e.id() + " is worth something sane: " + e.worth());
        }
        Map<String, Double> w = new HashMap<>();
        for (BaseWorth.Entry e : all) w.put(e.id(), e.worth());
        // Effort and rarity: an ore is worth at least what it gives, deeper is dearer, a diamond more than an emerald.
        assertTrue(w.get("minecraft:iron_ore") >= w.get("minecraft:raw_iron"), "iron ore over raw iron");
        assertTrue(w.get("minecraft:deepslate_diamond_ore") >= w.get("minecraft:diamond_ore"), "deepslate over stone");
        assertTrue(w.get("minecraft:diamond") > w.get("minecraft:emerald"), "a diamond over an emerald");
        assertTrue(w.get("minecraft:cobblestone") < w.get("minecraft:coal") && w.get("minecraft:coal") < w.get("minecraft:raw_iron"),
            "stone under coal under iron");
        assertTrue(Math.abs(w.get("mc_assistant:village_coin") - 1.0) < 1e-9, "a coin is worth a coin");
    }
}
