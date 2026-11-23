package com.jrpetty.mcassistant.pa.voice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [pa33b] Making a line sound spoken, not read out: what reads badly aloud goes (symbols, "×2"), coordinates become
 * "about forty blocks south-west" (the numbers stay on screen), game words are said properly (a small lexicon), long
 * replies are split into sentences, and for engines that take SSML (Windows' voices) the mood shapes the prosody: the
 * Gloomy weight slower and lower, the Funny weight a touch brighter and quicker, with a beat before a joke's punchline.
 * Pure Java.
 */
public final class PaSpeech {

    private PaSpeech() {}

    /** Words said the way players say them. */
    static final Map<String, String> LEXICON = new LinkedHashMap<>();
    static {
        LEXICON.put("netherite", "nether-ite");
        LEXICON.put("ghast", "gast");
        LEXICON.put("ghasts", "gasts");
        LEXICON.put("enderman", "ender man");
        LEXICON.put("endermen", "ender men");
        LEXICON.put("piglin", "pig-lin");
        LEXICON.put("redstone", "red stone");
        LEXICON.put("cobblestone", "cobble stone");
        LEXICON.put("elytra", "el-ee-tra");
        LEXICON.put("smp", "S M P");
        LEXICON.put("xp", "X P");
        LEXICON.put("hud", "H U D");
        LEXICON.put("vosk", "vosk");
        LEXICON.put("mc", "M C");
    }

    private static final Pattern COORDS = Pattern.compile("(?:at )?(-?\\d+) (-?\\d+) (-?\\d+)");

    /**
     * The words to say: no symbols, no "×2", coordinates as a distance and a direction from the listener (px, pz;
     * NaN when unknown, then "nearby"), game words from the lexicon, a name with digits or underscores said in parts.
     */
    public static String speakable(String text, double px, double pz) {
        if (text == null) return "";
        String t = text.replace('\u201C', ' ').replace('\u201D', ' ').replaceAll("\\s+\u00D7\\d+", "");
        Matcher m = COORDS.matcher(t);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String where;
            if (Double.isNaN(px)) where = "nearby";
            else {
                double dx = Integer.parseInt(m.group(1)) - px, dz = Integer.parseInt(m.group(3)) - pz;
                int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
                where = dist < 6 ? "right here" : "about " + words(round(dist)) + " blocks " + bearing(dx, dz);
            }
            m.appendReplacement(b, Matcher.quoteReplacement(where));
        }
        m.appendTail(b);
        t = b.toString();
        t = t.replaceAll("[\\p{So}\\p{Cn}\\u2600-\\u27BF#*_~`|<>\\[\\]{}]", " ");
        StringBuilder out = new StringBuilder();
        for (String w : t.split(" ")) {
            String bare = w.replaceAll("[^A-Za-z0-9_-]", "");
            String low = bare.toLowerCase(Locale.ROOT);
            if (LEXICON.containsKey(low)) out.append(w.replace(bare, LEXICON.get(low)));
            else if (bare.matches(".*[A-Za-z].*") && bare.matches(".*(_|\\d).*") && bare.length() > 2) out.append(w.replace(bare, bare.replace('_', ' ').replaceAll("(?<=[A-Za-z])(?=\\d)|(?<=\\d)(?=[A-Za-z])", " ")));
            else out.append(w);
            out.append(' ');
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    static int round(int d) {
        return d < 20 ? d : d < 100 ? Math.round(d / 5f) * 5 : Math.round(d / 10f) * 10;
    }

    public static String bearing(double dx, double dz) {
        double ang = Math.toDegrees(Math.atan2(-dx, dz));
        String[] names = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        return names[(int) Math.round(((ang % 360) + 360) % 360 / 45.0) % 8];
    }

    private static final String[] ONES = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
        "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    static String words(int n) {
        if (n < 0) return "minus " + words(-n);
        if (n < 20) return ONES[n];
        if (n < 100) return TENS[n / 10] + (n % 10 == 0 ? "" : "-" + ONES[n % 10]);
        if (n < 1000) return ONES[n / 100] + " hundred" + (n % 100 == 0 ? "" : " and " + words(n % 100));
        return Integer.toString(n);
    }

    /** Sentences, for engines that say one line at a time (shorter lines sound less run-on). */
    public static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("(?<=[.!?])\\s+")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    /** Rate and pitch offsets for the mood (percent): gloomy slower and lower, funny a touch quicker and brighter. */
    public static int[] prosody(double gloom, double fun) {
        int rate = (int) Math.round(-22 * gloom + 6 * fun);
        int pitch = (int) Math.round(-14 * gloom + 5 * fun);
        return new int[]{rate, pitch};
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    /**
     * SSML for engines that take it: the culture, the mood's prosody (plus the player's own rate and pitch, in percent),
     * a short break at an ellipsis, and before the punchline of a joke (the last sentence of a line that asks a question).
     */
    public static String ssml(String text, String culture, double gloom, double fun, int userRatePct, int userPitchPct) {
        int[] p = prosody(gloom, fun);
        int rate = p[0] + userRatePct, pitch = p[1] + userPitchPct;
        List<String> s = sentences(text);
        StringBuilder b = new StringBuilder();
        b.append("<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"").append(culture).append("\">");
        b.append("<prosody rate=\"").append(rate >= 0 ? "+" : "").append(rate).append("%\" pitch=\"").append(pitch >= 0 ? "+" : "").append(pitch).append("%\">");
        boolean joke = s.size() >= 2 && s.get(s.size() - 2).endsWith("?") || s.size() >= 2 && s.get(0).matches("(?i)^(why|what|how|who)\\b.*");
        for (int i = 0; i < s.size(); i++) {
            if (joke && i == s.size() - 1) b.append("<break time=\"600ms\"/>");
            b.append(esc(s.get(i)).replace("...", "<break time=\"500ms\"/>").replace("\u2026", "<break time=\"500ms\"/>")).append(' ');
        }
        b.append("</prosody></speak>");
        return b.toString();
    }
}
