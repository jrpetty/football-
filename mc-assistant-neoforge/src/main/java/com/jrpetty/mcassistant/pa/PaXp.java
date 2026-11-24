package com.jrpetty.mcassistant.pa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [pa35] The grind, tuned: what each level costs (roughly exponential: level 2 in about twenty minutes of ordinary play,
 * level 10 after about fifty hours), what it earns for varied work and what for doing one thing over and over (less,
 * until it does something else), and a bonus the first time it does something big (its first diamond, Nether trip,
 * boss). A simulation of an ordinary hour's mix sets the estimate the Progress tab shows ("about 3 h of mixed work")
 * and is unit-tested against the target band. Pure Java.
 */
public final class PaXp {

    private PaXp() {}

    // ------------------------------------------------------------------ the curve

    /** What each level costs over the one before (level 2 .. 10): each about two thirds again the last. */
    static final int[] COST = {150, 250, 418, 698, 1166, 1947, 3252, 5430, 9068};

    /** Experience to reach a level, all told (level 1 takes none): 150 for level 2, about 22,400 for level 10. */
    public static int xpFor(int level) {
        int l = Math.max(1, Math.min(PaLevels.MAX, level)), sum = 0;
        for (int k = 2; k <= l; k++) sum += COST[k - 2];
        return sum;
    }

    // ------------------------------------------------------------------ repetition: less for the same thing over and over

    /**
     * How tired it is of each kind of work. Each award of a kind adds one; doing anything else eases every other kind a
     * little; time eases all of them (half every five minutes). The award is multiplied by 1 / (1 + tiredness / 25), never
     * under a fifth: twenty-five of the same in a row earn half; something else straight after earns in full.
     */
    public static final class Fatigue {
        private final Map<String, Double> tired = new HashMap<>();
        private String last = "";
        private long at = Long.MIN_VALUE;
        /** Recent awards for the Progress tab: when, what, how much, at what share. */
        private final Deque<Object[]> recent = new ArrayDeque<>();

        public double take(String key, long tick) {
            if (at != Long.MIN_VALUE && tick > at) {
                double ease = Math.pow(0.5, (tick - at) / 6000.0);
                tired.replaceAll((k, v) -> v * ease);
            }
            at = tick;
            if (!key.equals(last)) tired.replaceAll((k, v) -> k.equals(key) ? v : v * 0.9);
            last = key;
            double t = tired.getOrDefault(key, 0.0);
            double mult = Math.max(0.2, 1.0 / (1.0 + t / 25.0));
            tired.put(key, t + 1);
            return mult;
        }

        /** Note an award (for "where your XP comes from"). */
        public void noted(String key, int xp, double mult, long tick) {
            recent.addLast(new Object[]{tick, key, xp, mult});
            while (recent.size() > 200 || !recent.isEmpty() && tick - (long) recent.peekFirst()[0] > 36000) recent.pollFirst();
        }

        /** The last half hour by kind: total experience and the share it's earning now (1 fresh .. 0.2 tired of it), best first. */
        public List<Source> sources(long tick) {
            Map<String, int[]> xp = new LinkedHashMap<>();
            Map<String, Double> share = new HashMap<>();
            for (Object[] o : recent) {
                if (tick - (long) o[0] > 36000) continue;
                xp.computeIfAbsent((String) o[1], k -> new int[1])[0] += (int) o[2];
                share.put((String) o[1], (double) o[3]);
            }
            List<Source> out = new ArrayList<>();
            for (Map.Entry<String, int[]> e : xp.entrySet()) out.add(new Source(e.getKey(), e.getValue()[0], share.getOrDefault(e.getKey(), 1.0)));
            out.sort((a, b) -> b.xp() - a.xp());
            return out;
        }

        public double tiredness(String key) { return tired.getOrDefault(key, 0.0); }
    }

    /** One kind of work's recent experience, and the share it earns now. */
    public record Source(String key, int xp, double share) {
        /** What the Progress tab says about it. */
        public String says() {
            String what = label(key);
            if (share < 0.45) return what + ": tired of it, try something else";
            if (share < 0.8) return what + ": less each time; mix it up";
            return what + ": full rate";
        }
    }

    /** A kind of work as said. */
    public static String label(String key) {
        return switch (key) {
            case "BLOCKS_MINED" -> "Mining";
            case "ORE_FOUND" -> "Finding ore";
            case "TREES_FELLED" -> "Felling trees";
            case "SAPLINGS_PLANTED" -> "Planting saplings";
            case "CROPS_HARVESTED" -> "Harvesting";
            case "CROPS_PLANTED" -> "Planting crops";
            case "ANIMALS_BRED" -> "Breeding";
            case "ANIMALS_SHEARED" -> "Shearing";
            case "FISH_CAUGHT" -> "Fishing";
            case "ITEMS_SMELTED" -> "Smelting";
            case "LOADS_HAULED" -> "Hauling";
            case "CHESTS_SORTED" -> "Sorting chests";
            case "ITEMS_STASHED" -> "Stashing";
            case "THINGS_MADE" -> "Crafting";
            case "BLOCKS_BUILT" -> "Building";
            case "fight" -> "Fighting";
            case "job" -> "Jobs done";
            case "build" -> "Buildings finished";
            case "craft" -> "Things made unasked";
            case "trick" -> "Tricks and moves";
            case "challenge" -> "Challenges";
            case "first" -> "Firsts";
            default -> key.isEmpty() ? "Work" : Character.toUpperCase(key.charAt(0)) + key.substring(1).replace('_', ' ');
        };
    }

    // ------------------------------------------------------------------ firsts

    /** The once-only bonuses, and what its story calls them. */
    public static final Map<String, Integer> FIRSTS = new LinkedHashMap<>();
    static {
        FIRSTS.put("diamond", 150);
        FIRSTS.put("nether", 200);
        FIRSTS.put("boss", 500);
        FIRSTS.put("end", 400);
        FIRSTS.put("build", 60);
        FIRSTS.put("iron", 40);
        FIRSTS.put("village", 50);
    }

    // ------------------------------------------------------------------ an ordinary hour, simulated

    /**
     * One ordinary hour at a player's pace, by kind: (key, how many, experience each). A mix: some mining and its ore,
     * felling, a little farming, smelting and making things, jobs finished, fights, now and then a building, fishing,
     * hauling. The figures are the game's own (AssistantEntity's deed rates, PaLevels.baseFor, vanilla kill experience).
     */
    public static final Object[][] ORDINARY_HOUR = {
        {"BLOCKS_MINED", 260, 0.07}, {"ORE_FOUND", 6, 5.0}, {"TREES_FELLED", 16, 3.0}, {"CROPS_HARVESTED", 40, 2.0}, {"CROPS_PLANTED", 40, 1.0},
        {"ITEMS_SMELTED", 32, 1.0}, {"THINGS_MADE", 10, 4.0}, {"job", 12, 6.0}, {"fight", 10, 5.0}, {"build", 1, 30.0}, {"FISH_CAUGHT", 4, 8.0},
        {"LOADS_HAULED", 4, 4.0}, {"BLOCKS_BUILT", 120, 0.2},
    };

    /** The experience one ordinary hour earns, with the repetition rule applied as the hour interleaves its work. */
    public static double hourXp(Fatigue f, long startTick) {
        // the hour's work, interleaved in runs as a player gives it: each kind in runs of up to 20, round the kinds in turn
        List<Object[]> runs = new ArrayList<>();
        int[] left = new int[ORDINARY_HOUR.length];
        for (int i = 0; i < left.length; i++) left[i] = (int) ORDINARY_HOUR[i][1];
        boolean any = true;
        while (any) {
            any = false;
            for (int i = 0; i < left.length; i++) {
                if (left[i] <= 0) continue;
                int n = Math.min(20, left[i]);
                left[i] -= n;
                runs.add(new Object[]{i, n});
                any = true;
            }
        }
        int total = 0;
        for (Object[] r : runs) total += (int) r[1];
        double xp = 0;
        long tick = startTick;
        for (Object[] r : runs) {
            Object[] kind = ORDINARY_HOUR[(int) r[0]];
            for (int k = 0; k < (int) r[1]; k++) {
                tick += 72000 / Math.max(1, total);
                xp += (double) kind[2] * f.take((String) kind[0], tick);
            }
        }
        return xp;
    }

    /** Hours of ordinary play to reach a level from nothing (the simulation the estimate and its test use). */
    public static double hoursTo(int level) {
        Fatigue f = new Fatigue();
        double xp = 0, hours = 0;
        int need = xpFor(level);
        while (xp < need && hours < 500) {
            double h = hourXp(f, (long) (hours * 72000));
            if (xp + h >= need) return hours + (need - xp) / h;
            xp += h;
            hours += 1;
        }
        return hours;
    }

    /** The experience an ordinary hour earns (the estimate's rate). */
    public static double perHour() {
        return hourXp(new Fatigue(), 0);
    }
}
