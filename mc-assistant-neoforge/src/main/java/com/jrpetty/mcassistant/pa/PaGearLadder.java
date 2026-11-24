package com.jrpetty.mcassistant.pa;

import java.util.List;

/**
 * [pa35] The weapons and gear ladder: the level an assistant must reach before it equips or uses a piece of kit. Below
 * it, the item is carried and handed back, never used. Fists, swords, axes and the bow from the start; the shield at 2;
 * snowballs, eggs and the fishing rod at 3; armour up to iron at 4; the crossbow at 5; diamond kit, splash potions and
 * crossbow enchantments at 6; the trident and ender pearls at 7; fireworks in a crossbow and the elytra at 8; the mace
 * and netherite at 9. Operators move rungs from Settings, Advanced. Pure Java: by item registry path.
 */
public final class PaGearLadder {

    private PaGearLadder() {}

    /** One rung: its key (as operators override it), level, the name said, and which items (a test on the path). */
    public record Rung(String key, int level, String says, java.util.function.Predicate<String> items, String icon) {}

    public static final List<Rung> RUNGS = List.of(
        new Rung("shield", 2, "the shield", p -> p.equals("shield"), "shield"),
        new Rung("throwing", 3, "snowballs, eggs and the fishing rod", p -> p.equals("snowball") || p.equals("egg") || p.equals("fishing_rod"), "fishing_rod"),
        new Rung("armour", 4, "armour up to iron", p -> p.matches("(leather|chainmail|iron|golden)_(helmet|chestplate|leggings|boots)|turtle_helmet"), "iron_chestplate"),
        new Rung("crossbow", 5, "the crossbow", p -> p.equals("crossbow"), "crossbow"),
        new Rung("diamondgear", 6, "diamond gear and splash potions", p -> p.startsWith("diamond_") && !p.equals("diamond_block") || p.equals("splash_potion") || p.equals("lingering_potion"), "diamond_sword"),
        new Rung("trident", 7, "the trident and ender pearls", p -> p.equals("trident") || p.equals("ender_pearl"), "trident"),
        new Rung("fireworks", 8, "firework rockets and the elytra", p -> p.equals("firework_rocket") || p.equals("elytra"), "elytra"),
        new Rung("mace", 9, "the mace and netherite gear", p -> p.equals("mace") || p.startsWith("netherite_") && !p.equals("netherite_block") && !p.equals("netherite_scrap")
            && !p.equals("netherite_ingot") && !p.equals("netherite_upgrade_smithing_template"), "mace"));

    /** The rung an item is on, or null when it's free from level 1. */
    public static Rung rung(String path) {
        for (Rung r : RUNGS) if (r.items().test(path)) return r;
        return null;
    }

    /** The level an item (by registry path) needs, after the operators' overrides; 1 when the ladder is off. */
    public static int levelFor(String path, boolean on, java.util.Map<String, Integer> overrides) {
        if (!on) return 1;
        Rung r = rung(path);
        if (r == null) return 1;
        Integer o = overrides == null ? null : overrides.get(r.key());
        return o == null ? r.level() : Math.max(1, Math.min(PaLevels.MAX, o));
    }

    /** What a level opens on the gear ladder ("the crossbow"), "" when nothing. */
    public static String opens(int level, boolean on, java.util.Map<String, Integer> overrides) {
        if (!on) return "";
        StringBuilder b = new StringBuilder();
        for (Rung r : RUNGS) {
            Integer o = overrides == null ? null : overrides.get(r.key());
            if ((o == null ? r.level() : o) == level) { if (b.length() > 0) b.append(", "); b.append(r.says()); }
        }
        return b.toString();
    }
}
