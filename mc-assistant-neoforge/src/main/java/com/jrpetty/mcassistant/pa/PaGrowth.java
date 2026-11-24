package com.jrpetty.mcassistant.pa;

import java.util.List;
import java.util.Locale;
import java.util.function.IntToDoubleFunction;

/**
 * [pa35] Everything that grows with its level, in one table: what the Progress tab shows (now, next level, level 10),
 * what the guide says, and what the game actually uses. Break speed and hand speed are a player's at every level
 * (PaPace); levels buy skill instead: steadier hands between actions, reach, reactions, aim, harder and more frequent
 * critical hits, dodging, a bigger pack, eating less for the same work, healing quicker, noticing more, smarter routes,
 * and more frequent, sharper help. Things that aren't a number (the elytra at 8) are unlocks: the skill list and the
 * mining and gear ladders. Pure Java; PaLevelsTest checks every metric only ever improves.
 */
public final class PaGrowth {

    private PaGrowth() {}

    /** One metric: its id, what it's called, how to show a value, whether more is better, and its value at a level. */
    public record Metric(String id, String label, String unit, boolean higherBetter, IntToDoubleFunction at, String tip) {
        public double value(int level) { return at.applyAsDouble(Math.max(1, Math.min(PaLevels.MAX, level))); }

        public String show(int level) {
            double v = value(level);
            return switch (unit) {
                case "hp" -> (int) v + " (" + (int) (v / 2) + " hearts)";
                case "s" -> String.format(Locale.ROOT, "%.2f s", v);
                case "ms" -> (int) Math.round(v) + " ms";
                case "%" -> (int) Math.round(v) + "%";
                case "%." -> String.format(Locale.ROOT, "%.1f%%", v);                    // [pa38] small shares
                case "n" -> String.valueOf((int) Math.round(v));                        // [pa38] a count
                case "x" -> String.format(Locale.ROOT, "x%.2f", v);
                case "blocks" -> (v == Math.floor(v) ? String.valueOf((int) v) : String.format(Locale.ROOT, "%.1f", v)) + " blocks";
                case "slots" -> (int) v + " slots";
                case "+hp" -> "+" + String.format(Locale.ROOT, "%.1f", v);
                default -> String.format(Locale.ROOT, "%.1f", v);
            };
        }
    }

    public static double crit(int l) { return 5 + (l - 1) * 2.5; }

    public static double dodge(int l) { return (l - 1) * 2.0; }

    public static double hunger(int l) { return 100 - (l - 1) * 4.0; }

    public static double healing(int l) { return 1.0 + (l - 1) * 0.06; }

    public static double perception(int l) { return 12 + (l - 1) * 2.0; }

    public static double autoHelpSeconds(int l) { return 15 - (l - 1); }

    public static final List<Metric> METRICS = List.of(
        new Metric("health", "Health", "hp", true, l -> PaHunger.maxHealth(l, 10, 20), "Ten hearts at level 1, like a player; twenty in two rows at level 10"),
        new Metric("hands", "Between hand actions", "s", false, PaHand::seconds, "Planting, placing, a stack at a chest: never quicker than a hand (0.5 s), but steadier with practice"),
        new Metric("reach", "Reach", "blocks", true, PaLevels::reach, "How far it reaches for a block"),
        new Metric("react", "Reaction", "ms", false, l -> PaLevels.reactTicks(l) * 50.0, "How long it takes to notice a change: a target turning, a threat"),
        new Metric("range", "Bow range", "blocks", true, PaLevels::range, "How far it will shoot"),
        new Metric("aim", "Bow shake", "", false, l -> PaLevels.spread(l), "Its hands' shake on a full draw, in degrees (smaller is steadier)"),
        new Metric("damage", "Melee bonus", "+hp", true, PaLevels::damage, "Extra damage on each hit"),
        new Metric("crit", "Critical hits", "%", true, PaGrowth::crit, "The chance a hit lands half again as hard"),
        new Metric("dodge", "Dodging", "%", true, PaGrowth::dodge, "The chance it steps out of a melee blow"),
        new Metric("pack", "Pack", "slots", true, l -> PaPackRules.open(l), "Slots it can carry in (rows open with its level)"),
        new Metric("hunger", "Hunger for the work", "%", false, PaGrowth::hunger, "How hungry the same work makes it (less with practice)"),
        new Metric("healing", "Healing", "x", true, PaGrowth::healing, "How quickly it heals while fed"),
        new Metric("perception", "Notices things within", "blocks", true, PaGrowth::perception, "How far off it notices danger near you and things worth a look"),
        new Metric("routes", "Plans routes up to", "blocks", true, PaLevels::followRange, "How far ahead it plans a path (fewer dead ends)"),
        new Metric("help", "Helps unasked every", "s", false, PaGrowth::autoHelpSeconds, "How often it checks what you need, with Auto-help on"),
        // [pa38] fishing, enchanting, trading, and the learner's slips (PaKnack): nudges on the game's own rules
        new Metric("fishwait", "Waits for a bite", "s", false, PaKnack::meanWaitSeconds, "The hook's real bite times, open water, no Lure on the rod: a quiet line and a good spot take a little off (half a level of Lure at most)"),
        new Metric("treasure", "Treasure on a catch", "%.", true, l -> 100 * PaKnack.treasureChance(PaKnack.luck(l)), "The game's fishing loot: practice is worth a little Luck of the Sea (one level at most)"),
        new Metric("lostbites", "Bites it loses", "%.", false, l -> 100 * PaKnack.lostBites(l), "Striking too soon, or looking away when the float dips"),
        new Metric("enchant", "Enchant offer error", "%", false, l -> 100 * PaKnack.enchantSigma(l), "How far off its sense of the table's three offers is: it reads the clue better and wastes fewer levels"),
        new Metric("trade", "Trade price error", "%", false, l -> 100 * PaKnack.tradeSigma(l), "How far off its sense of a villager's price is"),
        new Metric("villagers", "Villagers compared", "n", true, l -> PaKnack.villagersCompared(l), "How many villagers near the one you sent it to it checks for a better price"),
        new Metric("misplace", "Misplaced blocks", "%.", false, l -> 100 * PaKnack.misplace(l), "A block set one cell off now and then, put right straight away (never your builds, never anything rare)"),
        new Metric("wrongturn", "Wrong turns", "%", false, l -> 100 * PaKnack.wrongTurn(l), "On a long walk: a few steps the wrong way, then it re-paths"),
        new Metric("wrongtool", "Wrong tool first", "%", false, l -> 100 * PaKnack.wrongTool(l), "For a new job: one swing with the wrong tool before it switches"));

    /** Break speed reads as a player's at every level, and says so. */
    public static final String BREAKING = "As a player, by your tool";

    public static Metric metric(String id) {
        for (Metric m : METRICS) if (m.id().equals(id)) return m;
        return null;
    }
}
