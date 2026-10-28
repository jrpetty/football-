package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;

import java.util.List;

/**
 * The trade ladders, as the record sheet draws them: every rung a trade can
 * climb, the rank it confers, and what it opens. This mirrors the gates in
 * AssistantEntity.Ability — a level listed here promises exactly what is
 * gated there, nothing more.
 */
public final class Ladder {

    private Ladder() {}

    public record Rung(int level, String title, String gives) {}

    public static List<Rung> forTrade(StationTask t) {
        return switch (t) {
            case FARM -> List.of(
                new Rung(1, "Field hand", "wheat only"),
                new Rung(10, "Gardener", "carrots & potatoes · iron kit"),
                new Rung(20, "Cropwright", "beetroot & bonemeal"),
                new Rung(25, "", "diamond kit"),
                new Rung(30, "Orchardist", "melons, pumpkins & cane"),
                new Rung(40, "Waterwright", "irrigation · netherite kit"),
                new Rung(50, "Master Farmer", "teaches the young"));
            case WOOD -> List.of(
                new Rung(1, "Woodcutter", "oak & birch"),
                new Rung(10, "Forester", "every wood · iron kit"),
                new Rung(25, "", "diamond kit"),
                new Rung(40, "", "netherite kit"),
                new Rung(50, "Master of the Wood", "teaches the young"));
            case MINE -> List.of(
                new Rung(1, "Digger", "shallow ground, to Y32"),
                new Rung(10, "Collier", "iron country, Y16 · iron kit"),
                new Rung(20, "Deep miner", "the deep, as set"),
                new Rung(25, "", "diamond kit"),
                new Rung(30, "Quarrier", "quarry mode"),
                new Rung(40, "Shaftwright", "ladder shafts · netherite kit"),
                new Rung(50, "Master Miner", "teaches the young"));
            case RANCH -> List.of(
                new Rung(1, "Herder", "cows & sheep"),
                new Rung(20, "Stockman", "pigs, chickens & rabbits"),
                new Rung(50, "Master Rancher", "teaches the young"));
            case GUARD -> List.of(
                new Rung(1, "Watchman", "the blade"),
                new Rung(10, "Shieldbearer", "shield work · iron kit"),
                new Rung(20, "Archer", "the bow"),
                new Rung(25, "", "diamond kit"),
                new Rung(30, "Marksman", "the crossbow"),
                new Rung(40, "Sergeant", "escort duty · netherite kit"),
                new Rung(50, "Captain", "teaches the young"));
            case SMELT -> List.of(
                new Rung(1, "Furnace hand", "one furnace"),
                new Rung(10, "Fireman", "three furnaces"),
                new Rung(20, "Founder", "the whole bank"),
                new Rung(30, "Cook", "cooks for the crew"),
                new Rung(50, "Master Smelter", "teaches the young"));
            case FISH -> List.of(
                new Rung(1, "Angler", "rod & pond"),
                new Rung(50, "Master Fisher", "teaches the young"));
            case STORE -> List.of(
                new Rung(1, "Porter", "fetch & bank"),
                new Rung(10, "Sorter", "tidies the stores"),
                new Rung(20, "Clerk", "labels the chests"),
                new Rung(50, "Master of Stores", "teaches the young"));
            case HAUL -> List.of(
                new Rung(1, "Carrier", "half-loads"),
                new Rung(10, "Drayman", "a full pack"),
                new Rung(30, "Railman", "rail routes"),
                new Rung(50, "Master Hauler", "teaches the young"));
            default -> List.of();
        };
    }

    /** The rank this level has earned in this trade, for headings. */
    public static String rank(StationTask t, int lvl) {
        String r = "";
        for (Rung rung : forTrade(t)) {
            if (lvl >= rung.level() && !rung.title().isEmpty()) r = rung.title();
        }
        return r;
    }
}
