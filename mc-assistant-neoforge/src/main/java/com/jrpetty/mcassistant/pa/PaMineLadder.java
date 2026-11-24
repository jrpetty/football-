package com.jrpetty.mcassistant.pa;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [pa35] The mining ladder: which level an assistant must reach before it may break an ore (or a hard block).
 * Everything else is level 1. Vanilla's tool rules still apply on top (diamond still wants an iron pickaxe). Operators
 * can turn the ladder off, or move any rung, from Settings, Advanced (PaConfig). Pure Java: the table, its rungs by
 * block name, and the words it says them with; PaLevels.canMine is the gate every break path asks.
 */
public final class PaMineLadder {

    private PaMineLadder() {}

    /** One rung: its key (as operators override it), the level, the name said, and the blocks (registry paths). */
    public record Rung(String key, int level, String says, List<String> blocks) {}

    public static final List<Rung> RUNGS = List.of(
        new Rung("coal", 2, "coal ore", List.of("coal_ore", "deepslate_coal_ore")),
        new Rung("copper", 3, "copper ore", List.of("copper_ore", "deepslate_copper_ore", "raw_copper_block")),
        new Rung("quartz", 3, "nether quartz", List.of("nether_quartz_ore")),
        new Rung("iron", 4, "iron ore", List.of("iron_ore", "deepslate_iron_ore", "raw_iron_block")),
        new Rung("gold", 5, "gold ore", List.of("gold_ore", "deepslate_gold_ore", "nether_gold_ore", "raw_gold_block", "gilded_blackstone")),
        new Rung("redstone", 5, "redstone ore", List.of("redstone_ore", "deepslate_redstone_ore")),
        new Rung("lapis", 5, "lapis ore", List.of("lapis_ore", "deepslate_lapis_ore")),
        new Rung("diamond", 6, "diamond ore", List.of("diamond_ore", "deepslate_diamond_ore")),
        new Rung("emerald", 7, "emerald ore", List.of("emerald_ore", "deepslate_emerald_ore")),
        new Rung("obsidian", 7, "obsidian", List.of("obsidian", "crying_obsidian")),
        new Rung("debris", 8, "ancient debris", List.of("ancient_debris")),
        new Rung("special", 9, "spawners and budding amethyst (silk touch, when asked)", List.of("spawner", "budding_amethyst", "trial_spawner")));

    private static final Map<String, Rung> BY_BLOCK = new LinkedHashMap<>();
    static {
        for (Rung r : RUNGS) for (String b : r.blocks()) BY_BLOCK.put(b, r);
    }

    /** The rung a block is on (by registry path, "iron_ore"), or null for a level-1 block. */
    public static Rung rung(String path) {
        return BY_BLOCK.get(path);
    }

    /** The rung for an order's word ("iron", "diamonds", "coal"), or null. */
    public static Rung forWord(String word) {
        String w = word == null ? "" : word.toLowerCase(java.util.Locale.ROOT).replaceAll("s$", "").replace(" ore", "").trim();
        for (Rung r : RUNGS) if (r.key().equals(w) || r.says().startsWith(w + " ") || r.says().equals(w)) return r;
        if (w.equals("ancient debri") || w.equals("netherite")) return RUNGS.get(10);
        return null;
    }

    /** The level a rung needs, after the operators' overrides ("iron=3"). */
    public static int level(Rung r, Map<String, Integer> overrides) {
        Integer o = overrides == null ? null : overrides.get(r.key());
        return o == null ? r.level() : Math.max(1, Math.min(PaLevels.MAX, o));
    }

    /** The level a block (by registry path) needs: 1 off the ladder, or when the ladder is off. */
    public static int levelFor(String path, boolean on, Map<String, Integer> overrides) {
        if (!on) return 1;
        Rung r = rung(path);
        return r == null ? 1 : level(r, overrides);
    }

    /** "iron=3,diamond=5" (the config's form) as a map. */
    public static Map<String, Integer> parse(String s) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (s == null) return m;
        for (String part : s.split(",")) {
            String[] kv = part.trim().split("=");
            if (kv.length != 2) continue;
            try { m.put(kv[0].trim(), Integer.parseInt(kv[1].trim())); } catch (NumberFormatException ignored) { }
        }
        return m;
    }

    public static String format(Map<String, Integer> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) { if (b.length() > 0) b.append(','); b.append(e.getKey()).append('=').append(e.getValue()); }
        return b.toString();
    }

    /** What a level opens on the ladder ("iron ore"), "" when nothing. */
    public static String opens(int level, boolean on, Map<String, Integer> overrides) {
        if (!on) return "";
        StringBuilder b = new StringBuilder();
        for (Rung r : RUNGS) if (level(r, overrides) == level) { if (b.length() > 0) b.append(", "); b.append(r.says()); }
        return b.toString();
    }
}
