package com.jrpetty.mcassistant.pa;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

import java.util.ArrayList;
import java.util.List;

/**
 * [pa35] What the Progress tab shows, put together on the server: its level and the time to the next, the curve, every
 * unlock by level (skills, ores, gear, each with an item for its icon), the next unlock called out, every metric now,
 * next level and at level 10, where its experience has come from lately (and what's giving less from repetition), and
 * the ladders' rungs for operators to move.
 */
public final class PaProgress {

    private PaProgress() {}

    /** One unlock: its level, what it is, the item drawn for it, and its kind (skill, ore, gear). */
    public record Unlock(int level, String label, String icon, String kind) {}

    /** An item to draw for each skill. */
    static String skillIcon(String id) {
        return switch (id) {
            case "basics" -> "wooden_pickaxe";
            case "pings" -> "compass";
            case "bridge" -> "cobblestone";
            case "tame" -> "bone";
            case "pack3" -> "chest";
            case "trade" -> "emerald";
            case "shield" -> "shield";
            case "lead" -> "bow";
            case "redstone" -> "lever";
            case "potions" -> "potion";
            case "brew" -> "brewing_stand";
            case "explore" -> "map";
            case "enchant" -> "enchanting_table";
            case "nether" -> "netherrack";
            case "upgrade" -> "iron_chestplate";
            case "trick" -> "spectral_arrow";
            case "parkour" -> "leather_boots";
            case "clutch" -> "water_bucket";
            case "elytra" -> "elytra";
            case "pearl" -> "ender_pearl";
            case "end" -> "end_crystal";
            case "fish" -> "fishing_rod";                    // [pa38]
            case "throw" -> "snowball";
            case "cure" -> "golden_apple";
            case "mace" -> "mace";
            default -> "book";
        };
    }

    /** Every unlock, level by level, as the ladders stand on this server. */
    public static List<Unlock> unlocks() {
        List<Unlock> out = new ArrayList<>();
        boolean on = PaConfig.ladderOn();
        var ov = PaConfig.ladderLevels();
        for (PaLevels.Skill s : PaLevels.SKILLS) out.add(new Unlock(s.level(), s.label(), skillIcon(s.id()), "skill"));
        for (PaMineLadder.Rung r : PaMineLadder.RUNGS) out.add(new Unlock(on ? PaMineLadder.level(r, ov) : 1, "Mining " + r.says(), r.blocks().get(0), "ore"));
        for (PaGearLadder.Rung r : PaGearLadder.RUNGS) out.add(new Unlock(PaGearLadder.levelFor(firstItem(r), on, ov), "Using " + r.says(), r.icon(), "gear"));
        out.sort((a, b) -> a.level() - b.level());
        return out;
    }

    private static String firstItem(PaGearLadder.Rung r) {
        return switch (r.key()) {
            case "throwing" -> "fishing_rod";
            case "armour" -> "iron_chestplate";
            case "diamondgear" -> "diamond_sword";
            case "fireworks" -> "elytra";
            default -> r.icon();
        };
    }

    /** "Next: iron ore, armour up to iron at level 4." ("" at the top). */
    public static String next(int level) {
        if (level >= PaLevels.MAX) return "Every unlock is yours. Level 10: mastery.";
        List<String> n = new ArrayList<>();
        for (Unlock u : unlocks()) if (u.level() == level + 1) n.add(u.label().replaceFirst("^(Mining|Using) ", "").replaceFirst("^the ", ""));
        if (n.isEmpty()) return "Level " + (level + 1) + ": better at what it already does.";
        String all = String.join(", ", n);
        return "Next at level " + (level + 1) + ": " + Character.toUpperCase(all.charAt(0)) + all.substring(1) + ".";
    }

    private static final double[] HOURS = new double[PaLevels.MAX + 1];
    static {
        for (int l = 2; l <= PaLevels.MAX; l++) HOURS[l] = PaXp.hoursTo(l);
    }

    /** The tab's state: level, experience, the curve, unlocks, metrics, sources, ladders. */
    public static void state(CompoundTag t, PlayerAssistantEntity a, PaData.Rec r) {
        int lv = a.veteranLevel();
        t.putString("timeNext", lv >= PaLevels.MAX ? "" : PaLevels.timeTo(a.paXp(), lv + 1) + " of mixed work");
        t.putString("nextUnlock", next(lv));
        ListTag curve = new ListTag();
        for (int l = 1; l <= PaLevels.MAX; l++) curve.add(StringTag.valueOf(String.format(java.util.Locale.ROOT, "%.1f", HOURS[l] / Math.max(0.01, PaConfig.xpMultiplier()))));
        t.put("curve", curve);
        ListTag ul = new ListTag();
        for (Unlock u : unlocks()) {
            CompoundTag c = new CompoundTag();
            c.putInt("lv", u.level());
            c.putString("l", u.label());
            c.putString("i", u.icon());
            c.putString("k", u.kind());
            ul.add(c);
        }
        t.put("unlocks", ul);
        ListTag ml = new ListTag();
        CompoundTag br = new CompoundTag();
        br.putString("l", "Breaking blocks");
        br.putString("now", PaGrowth.BREAKING);
        br.putString("tip", "Mining, chopping and digging take exactly a player's time with the same tool: a better pickaxe digs faster, a level never does");
        ml.add(br);
        for (PaGrowth.Metric m : PaGrowth.METRICS) {
            CompoundTag c = new CompoundTag();
            c.putString("l", m.label());
            c.putString("now", m.show(lv));
            c.putString("next", lv >= PaLevels.MAX ? "" : m.show(lv + 1));
            c.putString("top", m.show(PaLevels.MAX));
            c.putString("tip", m.tip());
            c.putBoolean("up", lv < PaLevels.MAX && Math.abs(m.value(lv + 1) - m.value(lv)) > 1e-9);
            ml.add(c);
        }
        t.put("metrics", ml);
        ListTag src = new ListTag();
        for (PaXp.Source s : a.fatigue.sources(a.level().getGameTime())) {
            if (src.size() >= 6) break;
            CompoundTag c = new CompoundTag();
            c.putString("l", s.says());
            c.putInt("xp", s.xp());
            c.putFloat("share", (float) s.share());
            src.add(c);
        }
        t.put("sources", src);
        ListTag lad = new ListTag();
        var ov = PaConfig.ladderLevels();
        for (PaMineLadder.Rung rg : PaMineLadder.RUNGS) { CompoundTag c = new CompoundTag(); c.putString("k", rg.key()); c.putString("l", rg.says()); c.putInt("lv", PaMineLadder.level(rg, ov)); lad.add(c); }
        for (PaGearLadder.Rung rg : PaGearLadder.RUNGS) {
            CompoundTag c = new CompoundTag();
            c.putString("k", rg.key());
            c.putString("l", rg.says());
            Integer o = ov.get(rg.key());
            c.putInt("lv", o == null ? rg.level() : o);
            lad.add(c);
        }
        t.put("ladder", lad);
    }
}
