package com.jrpetty.mcassistant.pa;

import java.util.Map;
import java.util.Random;

/**
 * [pa38] How it judges the enchanting table's three offers, as a player does: it sees what the table shows (each offer's
 * level requirement and one enchantment of it, the clue) and nothing more, and pays an offer's slot in levels and lapis
 * (1, 2 or 3), not its requirement. A learner reads the requirement as the price, so it goes for the cheap-looking top
 * offer, and barely reads the clue; with practice it learns the bottom offer costs only three, weighs the clue for the
 * thing in hand (Sharpness on a sword, never Bane of Arthropods), and, from level 7, turns over a poor set of offers by
 * spending a level on a book (the old trick) instead of taking one. Its judgement never touches the dice: the table's
 * own offers and enchantments (vanilla's seed and lists) are what it gets. Plain Java, so the test can hold the shape.
 */
public final class PaEnchantJudge {

    private PaEnchantJudge() {}

    /** One offer as the table shows it: the level it asks you to have (0 when none), and the clue (an enchantment, its level). */
    public record Offer(int cost, String clue, int clueLevel) {}

    /** The highest level of each enchantment the game knows (for scaling its worth). */
    static final Map<String, Integer> MAX = Map.ofEntries(
        Map.entry("sharpness", 5), Map.entry("smite", 5), Map.entry("bane_of_arthropods", 5), Map.entry("knockback", 2), Map.entry("fire_aspect", 2),
        Map.entry("looting", 3), Map.entry("sweeping_edge", 3), Map.entry("efficiency", 5), Map.entry("fortune", 3), Map.entry("silk_touch", 1),
        Map.entry("unbreaking", 3), Map.entry("protection", 4), Map.entry("fire_protection", 4), Map.entry("blast_protection", 4),
        Map.entry("projectile_protection", 4), Map.entry("feather_falling", 4), Map.entry("respiration", 3), Map.entry("aqua_affinity", 1),
        Map.entry("depth_strider", 3), Map.entry("thorns", 3), Map.entry("power", 5), Map.entry("punch", 2), Map.entry("flame", 1), Map.entry("infinity", 1),
        Map.entry("quick_charge", 3), Map.entry("multishot", 1), Map.entry("piercing", 4), Map.entry("loyalty", 3), Map.entry("impaling", 5),
        Map.entry("riptide", 3), Map.entry("channeling", 1), Map.entry("lure", 3), Map.entry("luck_of_the_sea", 3), Map.entry("density", 5),
        Map.entry("breach", 4), Map.entry("wind_burst", 3));

    /** What kind of thing it is, for what suits it ("sword", "pickaxe", "boots", "book"...), from its registry path. */
    public static String kind(String path) {
        for (String k : new String[]{"sword", "pickaxe", "shovel", "hoe", "helmet", "chestplate", "leggings", "boots", "crossbow", "bow", "trident", "fishing_rod", "mace", "book"})
            if (path.endsWith(k) || path.equals(k)) return k;
        if (path.endsWith("_axe")) return "axe";
        return "other";
    }

    /** An expert's worth of an enchantment on this kind of thing, at full level (about what it would pay in levels). */
    static double base(String kind, String e) {
        boolean weapon = kind.equals("sword") || kind.equals("axe");
        boolean tool = kind.equals("pickaxe") || kind.equals("shovel") || kind.equals("hoe") || kind.equals("axe");
        return switch (e) {
            case "sharpness" -> weapon ? 10 : 2;
            case "smite" -> weapon ? 6 : 1;
            case "bane_of_arthropods" -> weapon ? 3 : 1;
            case "knockback" -> 2;
            case "fire_aspect" -> 6;
            case "looting" -> 9;
            case "sweeping_edge" -> 4;
            case "efficiency" -> tool ? 9 : 2;
            case "fortune" -> kind.equals("pickaxe") || kind.equals("shovel") || kind.equals("hoe") ? 9 : 4;
            case "silk_touch" -> 7;
            case "unbreaking" -> 6;
            case "protection" -> 9;
            case "fire_protection", "blast_protection", "projectile_protection" -> 4;
            case "feather_falling" -> kind.equals("boots") ? 8 : 2;
            case "respiration", "aqua_affinity" -> 5;
            case "depth_strider" -> 4;
            case "thorns" -> 2;
            case "power" -> 9;
            case "punch" -> 3;
            case "flame" -> 6;
            case "infinity" -> 10;
            case "quick_charge" -> 8;
            case "multishot" -> 6;
            case "piercing" -> 5;
            case "loyalty" -> 8;
            case "impaling" -> 5;
            case "riptide", "channeling" -> 4;
            case "lure", "luck_of_the_sea" -> 7;
            case "density" -> 6;
            case "breach" -> 7;
            case "wind_burst" -> 8;
            default -> 3;
        };
    }

    /** An expert's worth of one enchantment at a level. */
    public static double worth(String kind, String e, int lvl) {
        int max = MAX.getOrDefault(e, 3);
        return base(kind, e) * (0.4 + 0.6 * Math.min(1.0, lvl / (double) max));
    }

    /** What an offer is worth to an expert before paying for it: the clue, and the rest a requirement that high usually brings. */
    public static double value(String kind, Offer o) {
        if (o.cost() <= 0 || o.clue() == null) return 0;
        return worth(kind, o.clue(), o.clueLevel()) + 0.25 * o.cost();
    }

    /** What a slot costs in levels: its place (1, 2 or 3), never its requirement. */
    public static int price(int slot) {
        return slot + 1;
    }

    /** How much a learner takes the requirement for the price: all of it at 1, none of it from 5. */
    static double misread(int level) {
        return Math.max(0, 1.0 - (PaKnack.lv(level) - 1) * 0.25);
    }

    /** What its levels are worth to it, a level spent (levels are hard won). */
    static final double LEVEL_COST = 0.6;

    /**
     * Its choice: the offer (0..2) it takes, or -1 to take none (none affordable, or, from level 7, a poor set it would
     * rather turn over). Only offers it can pay for count: the requirement in levels, the slot's price in lapis.
     */
    public static int pick(int level, String kind, Offer[] offers, int levels, int lapis, Random r) {
        double sigma = PaKnack.enchantSigma(level), cw = PaKnack.clueWeight(level), mis = misread(level);
        int best = -1;
        double bestScore = -Double.MAX_VALUE;
        for (int k = 0; k < offers.length; k++) {
            Offer o = offers[k];
            if (o == null || o.cost() <= 0 || levels < Math.max(o.cost(), price(k)) || lapis < price(k)) continue;
            double clue = o.clue() == null ? 0 : worth(kind, o.clue(), o.clueLevel());
            double guess = cw * clue + (1 - cw) * 6.0 + 0.25 * o.cost();           // a learner barely reads the clue
            double priceSeen = price(k) + (o.cost() - price(k)) * mis;              // and takes the requirement for the price
            double score = guess * (1 + r.nextGaussian() * sigma) - LEVEL_COST * priceSeen;
            if (score > bestScore) { bestScore = score; best = k; }
        }
        return best;
    }

    /** The offer an expert would take (no noise, no misreading): the tests compare against it. */
    public static int ideal(String kind, Offer[] offers, int levels, int lapis) {
        int best = -1;
        double bestScore = -Double.MAX_VALUE;
        for (int k = 0; k < offers.length; k++) {
            Offer o = offers[k];
            if (o == null || o.cost() <= 0 || levels < Math.max(o.cost(), price(k)) || lapis < price(k)) continue;
            double score = value(kind, o) - LEVEL_COST * price(k);
            if (score > bestScore) { bestScore = score; best = k; }
        }
        return best;
    }

    /** From level 7: would it rather turn these over (every offer it could take is poor for the thing in hand)? */
    public static boolean poor(int level, String kind, Offer[] offers, int levels, int lapis) {
        if (!PaKnack.rerolls(level)) return false;
        int k = ideal(kind, offers, levels, lapis);
        if (k < 0) return false;
        Offer o = offers[k];
        return o.clue() != null && base(kind, o.clue()) <= 3 && o.cost() < 20;
    }

    // ------------------------------------------------------------------ a player's levels, from experience points

    /** Points a player needs to go from this level to the next (the game's own). */
    public static int toNext(int level) {
        return level >= 30 ? 112 + (level - 30) * 9 : level >= 15 ? 37 + (level - 15) * 5 : 7 + level * 2;
    }

    /** The level so many experience points make, as a player's bar counts them. */
    public static int levelOf(int points) {
        int l = 0;
        while (points >= toNext(l) && l < 1000) { points -= toNext(l); l++; }
        return l;
    }

    /** Points left after spending so many levels (the bar keeps its share of the way to the next, as a player's does). */
    public static int afterSpending(int points, int levels) {
        int l = 0, p = points;
        while (p >= toNext(l) && l < 1000) { p -= toNext(l); l++; }
        double share = p / (double) toNext(l);
        int keep = Math.max(0, l - levels), total = 0;
        for (int i = 0; i < keep; i++) total += toNext(i);
        return total + (int) Math.floor(share * toNext(keep));
    }
}
