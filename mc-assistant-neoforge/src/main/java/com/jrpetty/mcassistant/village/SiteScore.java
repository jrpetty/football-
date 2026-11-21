package com.jrpetty.mcassistant.village;

import java.util.Arrays;
import java.util.Locale;

/**
 * [found23] How good a spot is for a town's heart, from what was read of the ground round it (entity/SiteFinder reads
 * it): flat ground first, dry under the square, water and wood near but not on it, solid underfoot, above the sea, and
 * out of other towns' way. A score out of a hundred and the reason in plain words ("flat meadow by a river, oak wood
 * 20 blocks"). Plain numbers only, so the unit tests can hand it made-up ground.
 */
public final class SiteScore {

    private SiteScore() {}

    /** What was read of the ground round one spot. */
    public static final class Ground {
        /** Heights of the dry columns under the square and its first ring (no trees, no water). */
        public int[] heights = new int[0];
        /** How many of the columns read under the ground a village would level were water (or lava), of how many read. */
        public int wetColumns, columns;
        /** Of the columns read for hollows, how many had a cave or a pocket of air within five of the top. */
        public int hollowColumns, hollowRead;
        /** The nearest water outside the square, in blocks (-1: none in reach), and what it is ("river", "lake", "sea"). */
        public int waterAt = -1;
        public String water = "";
        /** The nearest trees, in blocks (-1: none), what wood they are ("oak", "birch"...), and how many columns of them. */
        public int woodAt = -1;
        public String wood = "";
        public int treeColumns;
        /** The land's word ("meadow", "forest", "desert"...) and whether the middle stands in the sea or a river. */
        public String land = "meadow";
        public boolean centreWet;
        /** The middle's height, and the sea's. */
        public int centreY = 64, seaLevel = 63;
        /** Blocks to spare before the nearest town would be too near to found beside (negative: it is too near
         *  already; Integer.MAX_VALUE: none near), and its name. */
        public int townAt = Integer.MAX_VALUE;
        public String town = "";
    }

    /** The verdict: out of a hundred, the words, and why not, if it may not be founded there at all. */
    public record Verdict(int score, String words, String no) {
        public boolean ok() {
            return no.isEmpty();
        }
    }

    /** Within this much of the nearest a town may be founded beside another, a spot loses a little. */
    public static final int TOWN_CLEAR = 48;

    public static Verdict judge(Ground g) {
        if (g.centreWet) return new Verdict(0, "in the water", "it stands in the water");
        if (g.townAt < 0) {
            return new Verdict(0, "too near " + (g.town.isEmpty() ? "a town" : g.town), "it is too near " + (g.town.isEmpty() ? "a town" : g.town));
        }
        double wet = g.columns == 0 ? 0 : g.wetColumns / (double) g.columns;
        if (wet > 0.35) return new Verdict(0, "mostly water", "most of the square would be water");
        int[] h = g.heights.length == 0 ? new int[]{ g.centreY } : g.heights.clone();
        Arrays.sort(h);
        int lo = h[(int) (h.length * 0.1)], hi = h[Math.min(h.length - 1, (int) (h.length * 0.9))];
        int spread = hi - lo;
        double mean = 0;
        for (int y : h) mean += y;
        mean /= h.length;
        double var = 0;
        for (int y : h) var += (y - mean) * (y - mean);
        double sd = Math.sqrt(var / h.length);

        double score = 0;
        score += 45 * Math.max(0, 1 - sd / 5.0);                              // flat ground: most of it
        score += 15 * Math.max(0, 1 - wet / 0.35);                            // dry under the square
        if (g.waterAt >= 0) score += 15 * Math.max(0, 1 - Math.max(0, g.waterAt - 12) / 60.0);   // water near, not on it
        if (g.woodAt >= 0) score += 12 * Math.max(0, 1 - Math.max(0, g.woodAt - 8) / 64.0);       // wood to build with
        double hollow = g.hollowRead == 0 ? 0 : g.hollowColumns / (double) g.hollowRead;
        score += 8 * Math.max(0, 1 - hollow * 3);                            // solid underfoot
        score += g.centreY >= g.seaLevel + 1 ? 5 : g.centreY >= g.seaLevel ? 2 : 0;   // above the floods
        if (g.townAt < TOWN_CLEAR) score -= 10 * (1 - g.townAt / (double) TOWN_CLEAR);
        int out = (int) Math.round(Math.max(1, Math.min(100, score)));

        StringBuilder w = new StringBuilder();
        w.append(sd < 1.2 && spread <= 2 ? "flat" : sd < 2.5 ? "gentle" : sd < 4.5 ? "rolling" : "steep").append(' ').append(g.land);
        if (g.waterAt >= 0 && g.waterAt <= 64) w.append(" by ").append(article(g.water)).append(g.water.isEmpty() ? "water" : g.water);
        if (g.woodAt >= 0 && g.woodAt <= 72) w.append(", ").append(g.wood.isEmpty() ? "trees " : g.wood + " wood ").append(Math.max(4, round5(g.woodAt))).append(" blocks");
        else w.append(", no wood near");
        if (hollow > 0.2) w.append(", caves under it");
        if (wet > 0.1) w.append(", wet ground");
        if (g.centreY < g.seaLevel) w.append(", below the sea");
        return new Verdict(out, w.toString(), "");
    }

    private static String article(String what) {
        if (what.isEmpty()) return "";
        return what.equals("sea") ? "the " : "a ";
    }

    private static int round5(int n) {
        return n < 10 ? n : (n + 2) / 5 * 5;
    }

    /** The land's word from a biome's name ("minecraft:sunflower_plains" -> "meadow"). */
    public static String landWord(String biome) {
        String b = biome.toLowerCase(Locale.ROOT);
        int c = b.indexOf(':');
        if (c >= 0) b = b.substring(c + 1);
        if (b.contains("cherry")) return "cherry grove";
        if (b.contains("taiga")) return b.contains("snow") ? "snowy pine woods" : "pine woods";
        if (b.contains("snow") || b.contains("ice") || b.contains("frozen")) return "snowfield";
        if (b.contains("meadow") || b.contains("plains")) return "meadow";
        if (b.contains("birch")) return "birch wood";
        if (b.contains("dark_forest")) return "dark wood";
        if (b.contains("forest")) return "woodland";
        if (b.contains("jungle")) return "jungle";
        if (b.contains("savanna")) return "savanna";
        if (b.contains("desert")) return "desert";
        if (b.contains("badlands")) return "badlands";
        if (b.contains("swamp")) return "marsh";
        if (b.contains("beach") || b.contains("shore")) return "shore";
        if (b.contains("hill") || b.contains("peak") || b.contains("slope") || b.contains("windswept") || b.contains("grove")) return "upland";
        if (b.contains("river")) return "riverbank";
        if (b.contains("ocean")) return "sea";
        return "ground";
    }

    /** The wood's word from a log's name ("minecraft:dark_oak_log" -> "dark oak"). */
    public static String woodWord(String log) {
        String b = log.toLowerCase(Locale.ROOT);
        int c = b.indexOf(':');
        if (c >= 0) b = b.substring(c + 1);
        b = b.replace("stripped_", "").replace("_log", "").replace("_stem", "").replace("_wood", "");
        return b.replace('_', ' ');
    }
}
