package com.jrpetty.mcassistant.pa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * [pa33b] Its personality triangle and how it picks what to say. The three corners are Serious (plain, few words),
 * Funny (dry British banter) and Gloomy (a comically bleak pessimist who still does every job). The player puts a
 * dot anywhere in the triangle; its weights add to 1. Each line's corner is drawn by those weights, or, near an
 * edge, from lines written for that blend. A line said lately is not said again (a long memory). A look's own lines
 * come in now and then, never more than one in five. Pure Java.
 */
public final class PaPersona {

    private PaPersona() {}

    /** Weights of the three corners (they add to 1). */
    public record Tri(double serious, double funny, double gloomy) {
        public Tri {
            double s = Math.max(0, serious), f = Math.max(0, funny), g = Math.max(0, gloomy);
            double sum = s + f + g;
            if (sum <= 0) { s = 1; f = 0; g = 0; sum = 1; }
            serious = s / sum;
            funny = f / sum;
            gloomy = g / sum;
        }

        /** From the stored pair (funny, gloomy); serious is what is left. */
        public static Tri of(double funny, double gloomy) {
            double f = clamp(funny), g = clamp(gloomy);
            if (f + g > 1) { double s = f + g; f /= s; g /= s; }
            return new Tri(1 - f - g, f, g);
        }

        /** The region's name under the triangle. */
        public String label() {
            double s = serious, f = funny, g = gloomy;
            if (s > 0.25 && f > 0.25 && g > 0.25) return "Balanced";
            if (f >= 0.8) return "Joker";
            if (g >= 0.8) return "Doom merchant";
            if (s >= 0.8) return "Professional";
            if (f >= 0.55 && s < 0.25 && g < 0.25) return "Banter";
            if (f > 0.3 && g > 0.3) return "Sardonic";
            if (s > 0.3 && g > 0.3) return "Weary";
            if (s > 0.3 && f > 0.3) return "Deadpan";
            if (g >= f && g >= s) return "Gloomy";
            if (f >= s) return "Cheeky";
            return "Sensible";
        }
    }

    static double clamp(double v) {
        return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }

    /** The preset buttons: name, (funny, gloomy). */
    public static final String[] PRESETS = {"Professional", "Banter", "Joker", "Doom", "Balanced"};

    public static Tri preset(String name) {
        return switch (name) {
            case "Professional" -> Tri.of(0.0, 0.0);
            case "Banter" -> Tri.of(0.7, 0.1);
            case "Joker" -> Tri.of(1.0, 0.0);
            case "Doom" -> Tri.of(0.0, 1.0);
            default -> Tri.of(1.0 / 3, 1.0 / 3);
        };
    }

    /** New assistants start at Banter. */
    public static final double DEFAULT_FUNNY = 0.7, DEFAULT_GLOOMY = 0.1;

    // ------------------------------------------------------------------ barycentric picker geometry

    /**
     * A point in the picker (corners: Serious top, Funny bottom left, Gloomy bottom right) as weights. Points outside
     * are pulled to the nearest inside.
     */
    public static Tri fromPoint(double px, double py, double ax, double ay, double bx, double by, double cx, double cy) {
        double det = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
        double wa = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / det;
        double wb = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / det;
        double wc = 1 - wa - wb;
        return new Tri(Math.max(0, wa), Math.max(0, wb), Math.max(0, wc));
    }

    /** Where a Tri sits in the picker. */
    public static double[] toPoint(Tri t, double ax, double ay, double bx, double by, double cx, double cy) {
        return new double[]{t.serious() * ax + t.funny() * bx + t.gloomy() * cx, t.serious() * ay + t.funny() * by + t.gloomy() * cy};
    }

    // ------------------------------------------------------------------ choosing a line

    public enum Corner { SERIOUS, FUNNY, GLOOMY }

    /** The corner to speak from, drawn by the weights. */
    public static Corner corner(Tri t, PaPhrases.Dice d) {
        double r = d.nextInt(10000) / 10000.0;
        if (r < t.funny()) return Corner.FUNNY;
        if (r < t.funny() + t.gloomy()) return Corner.GLOOMY;
        return Corner.SERIOUS;
    }

    /** The edge the dot is near ("sf", "sg", "fg"), or null in a corner or the middle. */
    public static String edge(Tri t) {
        double s = t.serious(), f = t.funny(), g = t.gloomy();
        if (s >= 0.3 && f >= 0.3 && g <= 0.2) return "sf";
        if (s >= 0.3 && g >= 0.3 && f <= 0.2) return "sg";
        if (f >= 0.3 && g >= 0.3 && s <= 0.2) return "fg";
        return null;
    }

    /**
     * [pa35] Its sense of humour, the player's pick in Settings > Personality. Dry (the default): understated, British,
     * affection shown sideways; the puns and cheese (lines tagged [bad] or [corny]) are never said. Daft: those come back,
     * and more often. Sincere: no cheese either, and warmer (its own "@sincere" lines where written, else now and then the
     * plain kind word instead of the joke), never syrupy.
     */
    public enum Humour {
        DRY("Dry", "Understated. Affection shown sideways."), DAFT("Daft", "Puns and cheese. You asked for this."), SINCERE("Sincere", "Warmer. Still not soppy.");

        public final String label, tip;

        Humour(String label, String tip) { this.label = label; this.tip = tip; }

        public static Humour of(int i) { return values()[Math.floorMod(i, values().length)]; }
    }

    /** [pa35] A pun or a cheesy line: only ever said with a Daft sense of humour. */
    public static boolean corny(String line) {
        java.util.Set<String> t = PaLines.tags(line);
        return t.contains("bad") || t.contains("corny");
    }

    /** [pa35] The lines this sense of humour may say (Dry and Sincere: no corny ones). */
    public static List<String> allowed(List<String> list, Humour h) {
        if (list == null || h == Humour.DAFT) return list;
        List<String> out = new ArrayList<>(list.size());
        for (String s : list) if (!corny(s)) out.add(s);
        return out;
    }

    /** Lines said lately, so they are not said again soon. */
    public static final class Recent {
        private final ArrayDeque<String> lines = new ArrayDeque<>();
        private final int size;
        /** [pa35] Its owner's chosen sense of humour (Dry unless set). */
        public volatile Humour humour = Humour.DRY;

        public Recent(int size) { this.size = size; }

        public boolean has(String s) { return lines.contains(s); }

        /** How long ago it was said: 0 the longest ago of those remembered (or never). */
        int age(String s) {
            int i = 0;
            for (String l : lines) { if (l.equals(s)) return i; i++; }
            return -1;
        }

        public void add(String s) {
            lines.remove(s);
            lines.addLast(s);
            while (lines.size() > size) lines.removeFirst();
        }
    }

    /** A line from a list, not one said lately (else the one said longest ago). */
    public static String from(List<String> list, PaPhrases.Dice d, Recent recent) {
        Humour h = recent == null ? Humour.DRY : recent.humour;
        list = allowed(list, h);   // [pa35] Dry and Sincere never say the corny ones
        if (list == null || list.isEmpty()) return null;
        if (h == Humour.DAFT && d.nextInt(100) < 40) {   // [pa35] Daft: the cheese, more often
            List<String> cheese = new ArrayList<>();
            for (String s : list) if (corny(s) && (recent == null || !recent.has(s))) cheese.add(s);
            if (!cheese.isEmpty()) { String pick = cheese.get(d.nextInt(cheese.size())); if (recent != null) recent.add(pick); return pick; }
        }
        List<String> fresh = new ArrayList<>();
        for (String s : list) if (recent == null || !recent.has(s)) fresh.add(s);
        String pick;
        if (!fresh.isEmpty()) pick = fresh.get(d.nextInt(fresh.size()));
        else {
            // [pa33b] all said lately: one of the older half, so never the same twice running and rarely within the last few
            List<String> old = new ArrayList<>(list);
            old.sort(java.util.Comparator.comparingInt(recent::age));
            pick = old.get(d.nextInt(Math.max(1, old.size() / 2)));
        }
        if (recent != null) recent.add(pick);
        return pick;
    }

    /** Lines that may only be used at the Funny corner's far end (mild words like "damn"). */
    static boolean cheeky(String s) {
        return s.toLowerCase(Locale.ROOT).contains("damn");
    }

    /**
     * The lines for a key at this blend: near an edge sometimes that edge's own lines, else the drawn corner's
     * ("key" is Funny, "key@gloomy", "key@serious"). Null when the corner has nothing (the caller then says the plain line,
     * or for a remark, nothing).
     */
    public static List<String> linesFor(String key, Tri t, PaPhrases.Dice d) {
        return linesFor(key, t, d, null);
    }

    /** [pa35] As above, with its sense of humour (from the recent lines' owner): Sincere swaps some jokes for warmth. */
    public static List<String> linesFor(String key, Tri t, PaPhrases.Dice d, Recent recent) {
        Humour h = recent == null ? Humour.DRY : recent.humour;
        String e = edge(t);
        if (e != null && PaLines.has(key + "@" + e) && d.nextInt(100) < 60) return PaLines.get(key + "@" + e);
        Corner c = corner(t, d);
        List<String> l = switch (c) {
            case FUNNY -> PaLines.get(key);
            case GLOOMY -> PaLines.has(key + "@gloomy") ? PaLines.get(key + "@gloomy") : PaLines.get(key + "@fg");
            case SERIOUS -> PaLines.get(key + "@serious");
        };
        if (c == Corner.FUNNY && h == Humour.SINCERE) {   // [pa35] warmer: its own sincere lines, else sometimes the plain kind one
            if (PaLines.has(key + "@sincere") && d.nextInt(100) < 65) return PaLines.get(key + "@sincere");
            if (PaLines.has(key + "@serious") && d.nextInt(100) < 35) return PaLines.get(key + "@serious");
        }
        if (l.isEmpty()) return null;
        if (c == Corner.FUNNY && t.funny() < 0.8) {
            List<String> mild = new ArrayList<>();
            for (String s : l) if (!cheeky(s)) mild.add(s);
            return mild.isEmpty() ? null : mild;
        }
        return l;
    }

    /** The look's names, as the lines know them (index = look number). */
    public static final String[] LOOK_KEYS = {"explorer", "engineer", "ranger", "scholar", "knight", "farmer", "miner", "pirate",
        "butler", "wizard", "chef", "lumberjack", "scientist", "plain", "skeleton", "zombie", "creeper", "enderman"};

    public static String lookKey(int look) {
        return LOOK_KEYS[Math.floorMod(look, LOOK_KEYS.length)];
    }

    /** How often a remark comes from the look's own lines instead (at most one in five). */
    public static final int LOOK_SHARE_PERCENT = 18;

    /**
     * A remark (a quip, a death line, a comeback): from the look's lines now and then (a moment's own first, e.g.
     * "on.skeleton"), else the situation's lines at this blend. Null when there is nothing to say at this corner.
     */
    public static String remark(String key, Tri t, int look, String moment, PaPhrases.Dice d, Recent recent) {
        String lk = lookKey(look);
        if (moment != null && PaLines.has("self." + lk + "@on." + moment) && d.nextInt(100) < 50) {
            List<String> on = new ArrayList<>(PaLines.get("self." + lk + "@on." + moment));
            if (recent != null) on.removeIf(recent::has);
            if (recent != null && recent.humour != Humour.DAFT) on.removeIf(PaPersona::corny);   // [pa35]
            if (!on.isEmpty()) return from(on, d, recent);
        }
        if (t.serious() < 0.9 && !key.startsWith("comeback.") && d.nextInt(100) < LOOK_SHARE_PERCENT) {   // an answer answers: no look aside
            boolean gloomy = corner(t, d) == Corner.GLOOMY;
            List<String> pool = new ArrayList<>();
            if (gloomy) {
                pool.addAll(PaLines.get("self." + lk + "@gloomy"));
                pool.addAll(PaLines.get("look." + lk + "@gloomy"));
            }
            if (pool.isEmpty() && !gloomy) {
                pool.addAll(PaLines.get("look." + lk));
                pool.addAll(PaLines.get("self." + lk));
            }
            if (t.funny() < 0.8) pool.removeIf(PaPersona::cheeky);
            if (recent != null) pool.removeIf(recent::has);      // a look's lines, used up, give way to the situation's
            if (recent == null || recent.humour != Humour.DAFT) pool.removeIf(PaPersona::corny);   // [pa35]
            if (!pool.isEmpty()) return from(pool, d, recent);
        }
        List<String> l = allowed(linesFor(key, t, d, recent), recent == null ? Humour.DRY : recent.humour);   // [pa35] what this humour may say
        // the Joke Machine never runs dry: a used-up list gives way to its general commentary
        if (key.startsWith("jm.") && l != null && recent != null && l.stream().allMatch(recent::has)) {
            List<String> more = new ArrayList<>(PaLines.get("jm.any"));
            more.addAll(PaLines.get("look." + lk));
            more.addAll(PaLines.get("self." + lk));
            more.addAll(PaLines.get("quip.idle"));
            if (t.funny() < 0.8) more.removeIf(PaPersona::cheeky);
            more = allowed(more, recent.humour);
            if (more.stream().anyMatch(s -> !recent.has(s))) l = more;
        }
        return from(l, d, recent);
    }

    /** Fill the blanks; any left over read plainly. */
    public static String fill(String raw, Map<String, String> kv) {
        if (raw == null) return null;
        String s = raw;
        for (Map.Entry<String, String> e : kv.entrySet()) s = s.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        return s.replace("{owner}", "you").replace("{name}", "I").replace("{did}", "Done").replace("{what}", "").replace("{item}", "it")
            .replaceAll("\\s+", " ").trim();
    }
}
