package com.jrpetty.mcassistant.pa.voice;

import com.jrpetty.mcassistant.pa.PaLines;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [voice37] How a line is performed by the expressive voice: which of its sounds go where (a laugh after a punchline, a
 * sigh before a gloomy line, a chuckle after a roast) and how freely it reads (the sampling temperature), from its
 * personality corner (Serious, Funny, Gloomy), its sense of humour (Dry, Daft, Sincere), the mood of the line and what
 * kind of line it is. Pure Java.
 *
 * <p>Dry, the default, is understated: at most one small sound, a chuckle rather than a laugh, never on a warning, a
 * death played deadpan. Daft is bigger: real laughs, up to two sounds, a looser read. Sincere is warm: no mockery in the
 * voice, a gasp of concern at a death, a chuckle at most. Serious says it straight. The player's Expressiveness slider
 * scales all of it, from none (the words only) to the most the humour allows.
 */
public final class PaVoiceStyle {

    private PaVoiceStyle() {}

    /** What a line is, for the voice. */
    public enum Kind { PLAIN, JOKE, ROAST, QUIP, DEATH, WARNING, LAUGH, AFFECTION, GREETING }

    /** 0 Dry, 1 Daft, 2 Sincere (PaPersona.Humour's order). */
    public static final int DRY = 0, DAFT = 1, SINCERE = 2;

    /** The model's own sounds this mapping uses (all are single tokens of its tokenizer). */
    public static final List<String> TAGS = List.of("[laugh]", "[chuckle]", "[sigh]", "[groan]", "[gasp]");

    /** The line to say (with its sounds in place) and how freely to read it. */
    public record Style(String text, float temperature) {}

    /**
     * Perform a line. gloom and fun are the corner's weights (0..1, serious is the rest), humour 0..2, expressiveness
     * 0..100 (50 the default), gloomyLine when the line itself is a gloomy one (its key ends "@gloomy").
     */
    public static Style perform(String line, Kind kind, double gloom, double fun, int humour, int expressiveness, boolean gloomyLine) {
        String t = strip(line);
        double e = Math.max(0, Math.min(100, expressiveness)) / 100.0;
        double serious = Math.max(0, 1 - gloom - fun);
        int h = Math.max(0, Math.min(2, humour));
        float temp = (float) (0.62 + 0.22 * e + (h == DAFT ? 0.08 : 0) - (kind == Kind.WARNING ? 0.12 : 0) - (serious > 0.6 ? 0.05 : 0));
        temp = Math.max(0.5f, Math.min(0.95f, temp));
        if (e < 0.15 || t.isEmpty()) return new Style(t, temp);
        int budget = h == DAFT ? 2 : 1;
        boolean gloomy = gloomyLine || gloom >= 0.55;
        boolean funny = fun >= 0.35 || h == DAFT;
        boolean straight = serious >= 0.7 && h != DAFT;                 // a professional says it straight
        String laugh = h == DAFT && e >= 0.35 ? "[laugh]" : "[chuckle]";
        String out = t;
        switch (kind) {
            case WARNING -> { return new Style(t, temp); }               // never a sound on a warning
            case JOKE -> {
                if (straight && e < 0.6) break;
                out = afterPunchline(t, laugh);
                budget--;
            }
            case LAUGH -> {
                out = laugh + " " + t;
                budget--;
            }
            case ROAST, QUIP -> {
                if (h == SINCERE || straight) break;
                if (funny && e >= (kind == Kind.ROAST ? 0.35 : 0.55)) { out = afterSentence(t, 1, h == DAFT ? "[laugh]" : "[chuckle]"); budget--; }
            }
            case DEATH -> {
                if (gloomy && e >= 0.3) { out = "[sigh] " + t; budget--; }
                else if (h == SINCERE && e >= 0.45) { out = "[gasp] " + t; budget--; }
                else if (h == DAFT && e >= 0.35) { out = afterSentence(t, 1, "[laugh]"); budget--; }
                else if (h == DRY && funny && e >= 0.8) { out = afterSentence(t, 1, "[chuckle]"); budget--; }
            }
            case AFFECTION -> {
                if (h != DRY && e >= 0.5 && !gloomy) { out = t + " [chuckle]"; budget--; }
                else if (h == DRY && e >= 0.85) { out = t + " [chuckle]"; budget--; }
            }
            case GREETING, PLAIN -> { }
        }
        // a gloomy line sighs first (understated enough for Dry); the gloomiest groan, if the humour has room
        if (budget > 0 && gloomy && kind != Kind.LAUGH && !out.startsWith("[") && e >= 0.3 && !straight) {
            out = (gloom >= 0.8 && e >= 0.75 && h == DAFT ? "[groan] " : "[sigh] ") + out;
        }
        return new Style(out.replaceAll("\\s+", " ").trim(), temp);
    }

    /** The same line for a voice without these sounds: the tags taken out. */
    public static String strip(String text) {
        if (text == null) return "";
        String t = text;
        for (String tag : TAGS) t = t.replace(tag, " ");
        return t.replaceAll("\\[(?:sarcastic|happy|angry|fear|surprised|whispering|dramatic|crying|cough|sniff|shush|clear throat|narration|advertisement)]", " ")
            .replaceAll("\\s+", " ").trim();
    }

    /** A joke's laugh goes after the punchline: after the answer when it opens with a question, else after the first sentence. */
    static String afterPunchline(String t, String tag) {
        List<int[]> ends = sentenceEnds(t);
        if (ends.isEmpty()) return t + " " + tag;
        int first = ends.get(0)[0];
        boolean question = t.charAt(first) == '?';
        return afterSentence(t, question && ends.size() > 1 ? 2 : 1, tag);
    }

    /** The tag after the n-th sentence (at the end when there are fewer). */
    static String afterSentence(String t, int n, String tag) {
        List<int[]> ends = sentenceEnds(t);
        if (ends.size() < n || ends.get(n - 1)[0] >= t.length() - 1) return t + " " + tag;
        int at = ends.get(n - 1)[0] + 1;
        return t.substring(0, at) + " " + tag + " " + t.substring(at).trim();
    }

    /**
     * A performed line cut into the pieces it is made in, a piece at a time while the last one plays: whole sentences, a
     * short first piece (the first words start sooner), later ones joined up to a natural length (very short inputs read
     * oddly), and a sound left on its own joined to the sentence before.
     */
    public static List<String> chunks(String text) {
        List<String> sentences = new java.util.ArrayList<>();
        for (String s : text.trim().split("(?<=[.!?])\\s+")) {
            if (s.isBlank()) continue;
            if (!sentences.isEmpty() && strip(s).isEmpty()) sentences.set(sentences.size() - 1, sentences.get(sentences.size() - 1) + " " + s.trim());
            else sentences.add(s.trim());
        }
        List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String s : sentences) {
            boolean firstPiece = out.isEmpty();
            if (cur.length() > 0 && (cur.length() + 1 + s.length() > (firstPiece ? 60 : 240))) { out.add(cur.toString()); cur.setLength(0); }
            if (cur.length() > 0) cur.append(' ');
            cur.append(s);
            if (cur.length() >= (out.isEmpty() ? 25 : 80)) { out.add(cur.toString()); cur.setLength(0); }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static final Pattern END = Pattern.compile("[.?!](?=\\s|$)");

    static List<int[]> sentenceEnds(String t) {
        java.util.ArrayList<int[]> out = new java.util.ArrayList<>();
        Matcher m = END.matcher(t);
        while (m.find()) out.add(new int[]{m.start()});
        return out;
    }

    // ------------------------------------------------------------------ what kind of line it is

    /** The kind of line from its key in the phrase banks ("joke", "death.lava", "stranger.roast.clean@gloomy"). */
    public static Kind kindOf(String key) {
        if (key == null || key.isEmpty()) return Kind.PLAIN;
        String k = key.toLowerCase(Locale.ROOT);
        int at = k.indexOf('@');
        if (at >= 0) k = k.substring(0, at);
        if (k.equals("joke") || k.equals("comeback.joke") || k.startsWith("jm.")) return Kind.JOKE;
        if (k.startsWith("death.") || k.startsWith("say.died") || k.startsWith("respawn")) return Kind.DEATH;
        if (k.contains("roast") || k.startsWith("comeback.insult") || k.startsWith("tone.rude") || k.startsWith("stranger.defend")
            || k.startsWith("tone.comeback.insult") || k.startsWith("say.mem.friend_tease")) return Kind.ROAST;
        if (k.startsWith("comeback.laugh") || k.startsWith("tone.comeback.laugh")) return Kind.LAUGH;
        if (k.startsWith("quip.low_health") || k.startsWith("quip.creeper") || k.endsWith("_warn") || k.endsWith(".warn")
            || k.equals("ping.look.lava") || k.startsWith("say.mem.rule.fired")) return Kind.WARNING;
        if (k.startsWith("say.bond.gift") || k.startsWith("say.bond.share") || k.startsWith("say.bond.food") || k.startsWith("say.bond.tool")
            || k.startsWith("say.bond.favourite") || k.startsWith("say.bond.anniversary") || k.startsWith("say.gear.kept") || k.startsWith("comeback.love")
            || k.startsWith("say.thanks") || k.startsWith("say.bond.tier_up")) return Kind.AFFECTION;
        if (k.startsWith("say.greet") || k.startsWith("say.hello") || k.startsWith("say.intro") || k.startsWith("quip.back") || k.startsWith("quip.morning")
            || k.startsWith("quip.nightlogin")) return Kind.GREETING;
        if (k.startsWith("quip.") || k.startsWith("tone.")) return Kind.QUIP;
        return Kind.PLAIN;
    }

    /** With no key: a guess from the words (a warning by its first words, a joke by a question answered at once). */
    public static Kind guess(String text) {
        if (text == null) return Kind.PLAIN;
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.matches("^(stop|careful|watch out|look out|behind you|run|get back|move|creeper)\\b.*")) return Kind.WARNING;
        if (t.matches("^(ha|haha|heh|hah)\\b.*")) return Kind.LAUGH;
        return Kind.PLAIN;
    }

    // ------------------------------------------------------------------ the bank, read backwards

    private static volatile List<Object[]> index;

    /**
     * The key a spoken line came from, by matching it against every line in the phrase banks (their {placeholders} match
     * any words), or null. The server only sends the words; this finds what they were.
     */
    public static String keyOf(String spoken) {
        if (spoken == null || spoken.isBlank()) return null;
        String want = norm(spoken);
        List<Object[]> idx = index;
        if (idx == null) idx = build(PaLines.all());
        String best = null;
        int bestLiteral = Math.max(6, (int) (want.replace(" ", "").length() * 0.4)) - 1;   // a template mostly {placeholders} proves nothing
        for (Object[] e : idx) {
            if (e[2] instanceof String exact) { if (exact.equals(want)) return (String) e[0]; }
            else if ((int) e[3] > bestLiteral && ((Pattern) e[2]).matcher(want).matches()) { best = (String) e[0]; bestLiteral = (int) e[3]; }
        }
        return best;                                                        // the template with the most of its own words wins
    }

    /** Build the reverse index from banks (key to lines); kept for every later lookup. */
    static synchronized List<Object[]> build(Map<String, List<String>> banks) {
        if (index != null) return index;
        java.util.ArrayList<Object[]> out = new java.util.ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        for (Map.Entry<String, List<String>> b : banks.entrySet()) {
            for (String line : b.getValue()) {
                String n = norm(line.replaceAll("\\{[a-z_]+}", "\u0001"));
                if (n.length() < 3 || seen.putIfAbsent(n, true) != null) continue;
                if (n.indexOf('\u0001') < 0) out.add(new Object[]{b.getKey(), line, n, n.length()});
                else {
                    StringBuilder re = new StringBuilder();
                    for (String part : n.split("\u0001", -1)) { if (re.length() > 0) re.append(".{1,60}?"); re.append(Pattern.quote(part)); }
                    out.add(new Object[]{b.getKey(), line, Pattern.compile(re.toString()), n.replace("\u0001", "").replace(" ", "").length()});
                }
            }
        }
        index = out;
        return out;
    }

    /** For tests: forget the index. */
    static synchronized void reset() { index = null; }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("\\[[^\\]]*]", " ").replaceAll("[^a-z0-9\u0001]+", " ").strip();   // strip, not trim: trim eats the \u0001 marks
    }
}
