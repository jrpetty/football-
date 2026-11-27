package com.jrpetty.mcassistant.pa;

import java.util.Locale;

/** [pa33c-2b] The Pack tab's rules, in plain Java (tested on their own): open rows by level, junk, the sorting order. */
public final class PaPackRules {

    private PaPackRules() {}

    public static final int ROW = 9, SIZE = 27, REACH = 8;
    /** The level each row opens at: two rows from the start, the third at level 3. */
    private static final int[] ROW_LEVEL = {0, 0, 3};

    /** How many of its 27 slots are open at this level. */
    public static int open(int level) {
        int n = 0;
        for (int lv : ROW_LEVEL) if (level >= lv) n += ROW;
        return n;
    }

    /** The level a row opens at (0, 1, 2). */
    public static int rowLevel(int row) {
        return ROW_LEVEL[Math.max(0, Math.min(ROW_LEVEL.length - 1, row))];
    }

    /** Things nobody wants to carry: dirt, gravel, the rough stones, rotten flesh, poisonous potatoes, more seeds than a field takes. */
    public static boolean junk(String id, int count) {
        String p = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return switch (p) {
            case "dirt", "coarse_dirt", "gravel", "andesite", "diorite", "granite", "tuff", "netherrack", "rotten_flesh", "poisonous_potato",
                "cobbled_deepslate", "flint", "dead_bush", "spider_eye" -> true;
            case "wheat_seeds", "beetroot_seeds" -> count > 16;
            default -> false;
        };
    }

    /** Sorting order: tools and weapons, armour, food, ores and ingots, blocks, the rest. */
    public static int group(boolean tool, boolean armour, boolean food, String id, boolean block) {
        if (tool) return 0;
        if (armour) return 1;
        if (food) return 2;
        String p = id.toLowerCase(Locale.ROOT);
        if (p.endsWith("_ingot") || p.endsWith("_ore") || p.startsWith("raw_") || p.contains("diamond") || p.contains("emerald") || p.endsWith("coal")
            || p.contains("redstone") || p.contains("lapis")) return 3;
        if (block) return 4;
        return 5;
    }

}
