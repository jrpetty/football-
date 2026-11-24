package com.jrpetty.mcassistant.pa.voice;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [pa35] Words to the phonemes Kokoro speaks, with no other program: the pronouncing dictionaries Kokoro itself was
 * trained with (misaki's gold and silver lexicons, British and American, Apache-2.0, downloaded with the voices), read
 * the way misaki reads them: the word, its lower-case or capitalised form, a possessive, a plural, a past tense or an
 * -ing form built on a known stem (with the right ending sound), a compound of two known words ("redstone"), letters for
 * an initialism, and a small lexicon of game words. Only a word none of that covers goes to espeak-ng (if the player has
 * it) or to plain English letter-to-sound rules. Before this the natural voice needed espeak-ng installed by hand, so on
 * most Windows computers it never spoke. Pure Java, unit-tested.
 */
public final class PaG2p {

    private final Map<String, String> gold, silver;
    private final boolean british;
    private final Function<String, String> fallback;

    /** The vowels misaki's phonemes use (a stress mark goes before the first of a word's). */
    static final String VOWELS = "AIOQWYaiuæɑɒɔəɛɜɪʊʌᵻᵊ";

    public PaG2p(Map<String, String> gold, Map<String, String> silver, boolean british, Function<String, String> fallback) {
        this.gold = gold == null ? Map.of() : gold;
        this.silver = silver == null ? Map.of() : silver;
        this.british = british;
        this.fallback = fallback;
    }

    // ------------------------------------------------------------------ the dictionaries (misaki's JSON: word -> phonemes, or {"DEFAULT": ...})

    /** Read one of misaki's lexicons. A word with several pronunciations by part of speech keeps its DEFAULT one. */
    public static Map<String, String> read(InputStream in) throws IOException {
        String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> out = new HashMap<>(Math.max(16, s.length() / 24));
        int[] at = {skip(s, 0)};
        if (at[0] >= s.length() || s.charAt(at[0]) != '{') throw new IOException("not a lexicon");
        at[0]++;
        while (true) {
            at[0] = skip(s, at[0]);
            if (at[0] >= s.length()) break;
            char c = s.charAt(at[0]);
            if (c == '}') break;
            if (c == ',') { at[0]++; continue; }
            String key = string(s, at);
            at[0] = skip(s, at[0]);
            if (s.charAt(at[0]) != ':') throw new IOException("bad lexicon near " + key);
            at[0] = skip(s, at[0] + 1);
            char v = s.charAt(at[0]);
            if (v == '"') out.put(key, string(s, at));
            else if (v == '{') {
                at[0]++;
                String def = null, first = null;
                while (true) {
                    at[0] = skip(s, at[0]);
                    char d = s.charAt(at[0]);
                    if (d == '}') { at[0]++; break; }
                    if (d == ',') { at[0]++; continue; }
                    String tag = string(s, at);
                    at[0] = skip(s, at[0]);
                    at[0] = skip(s, at[0] + 1);
                    String val = null;
                    if (s.charAt(at[0]) == '"') val = string(s, at);
                    else if (s.startsWith("null", at[0])) at[0] += 4;
                    if (tag.equals("DEFAULT")) def = val;
                    if (first == null) first = val;
                }
                String keep = def != null ? def : first;
                if (keep != null) out.put(key, keep);
            } else if (s.startsWith("null", at[0])) at[0] += 4;
            else throw new IOException("bad lexicon value near " + key);
        }
        return out;
    }

    private static int skip(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static String string(String s, int[] at) {
        StringBuilder b = new StringBuilder();
        int i = at[0] + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'u' && i + 4 < s.length()) { b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
                else b.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else b.append(c);
            i++;
        }
        at[0] = i + 1;
        return b.toString();
    }

    // ------------------------------------------------------------------ game words its dictionaries don't have

    /** British and American phonemes for the game's own words (misaki's symbols). */
    static final Map<String, String[]> GAME = new HashMap<>();
    static {
        String[][] g = {
            {"minecraft", "mˈInkɹɑːft", "mˈInkɹæft"}, {"netherite", "nˈɛðəɹIt", "nˈɛðəɹIt"}, {"enderman", "ˈɛndəmən", "ˈɛndəɹmən"},
            {"endermen", "ˈɛndəmən", "ˈɛndəɹmən"}, {"piglin", "pˈɪɡlɪn", "pˈɪɡlɪn"}, {"piglins", "pˈɪɡlɪnz", "pˈɪɡlɪnz"}, {"ghast", "ɡˈast", "ɡˈæst"},
            {"ghasts", "ɡˈasts", "ɡˈæsts"}, {"redstone", "ɹˈɛdstQn", "ɹˈɛdstOn"}, {"glowstone", "ɡlˈQstQn", "ɡlˈOstOn"}, {"deepslate", "dˈiːpslAt", "dˈipslAt"},
            {"nether", "nˈɛðə", "nˈɛðəɹ"}, {"axolotl", "ˈaksəlɒtᵊl", "ˈæksəlɑɾᵊl"}, {"hoglin", "hˈɒɡlɪn", "hˈɑɡlɪn"}, {"strider", "stɹˈIdə", "stɹˈIdəɹ"},
            {"wither", "wˈɪðə", "wˈɪðəɹ"}, {"elytra", "ˈɛlɪtɹə", "ˈɛlᵻtɹə"}, {"lol", "lˈɒl", "lˈɑl"}, {"xp", "ˌɛksˈpiː", "ˌɛksˈpi"},
            {"okay", "ˌQkˈA", "ˌOkˈA"}, {"ok", "ˌQkˈA", "ˌOkˈA"}, {"mob", "mˈɒb", "mˈɑb"}, {"mobs", "mˈɒbz", "mˈɑbz"}, {"respawn", "ɹiːspˈɔːn", "ɹispˈɔn"},
        };
        for (String[] e : g) GAME.put(e[0], new String[]{e[1], e[2]});
    }

    // ------------------------------------------------------------------ text to phonemes

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z]+(?:['’][A-Za-z]+)*|[.,!?;:—…\"()]|-");

    /** A line (already made speakable: numbers in words) as phonemes, words spaced, punctuation kept for the pauses. */
    public String phonemize(String text) {
        List<String> toks = new ArrayList<>();
        Matcher m = TOKEN.matcher(text == null ? "" : text.replace('’', '\''));
        while (m.find()) toks.add(m.group());
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < toks.size(); i++) {
            String t = toks.get(i);
            if (t.equals("-")) continue;
            if (!Character.isLetter(t.charAt(0))) {
                // punctuation joins the word before it
                while (b.length() > 0 && b.charAt(b.length() - 1) == ' ') b.setLength(b.length() - 1);
                b.append(t.equals("—") ? " —" : t).append(' ');
                continue;
            }
            String next = i + 1 < toks.size() && Character.isLetter(toks.get(i + 1).charAt(0)) ? toks.get(i + 1) : null;
            b.append(word(t, next)).append(' ');
        }
        return b.toString().replaceAll("\\s+", " ").trim();
    }

    /** One word's phonemes (the next word, for "the apple" and "to eat"). */
    public String word(String w, String next) {
        String low = w.toLowerCase(Locale.ROOT);
        boolean vowelNext = next != null && startsWithVowelSound(next);
        switch (low) {
            case "a": return w.equals("A") && next == null ? "ˈA" : "ɐ";
            case "an": return "ɐn";
            case "the": return vowelNext ? "ði" : "ðə";
            case "to": return vowelNext ? "tʊ" : "tə";
            case "i": return "ˌI";
            default: break;
        }
        String[] game = GAME.get(low);
        if (game != null) return british ? game[0] : game[1];
        String ps = lookup(w);
        if (ps != null) return ps;
        ps = stems(low);
        if (ps != null) return ps;
        if (w.length() <= 4 && w.equals(w.toUpperCase(Locale.ROOT)) && w.length() > 1) {    // an initialism: its letters
            StringBuilder b = new StringBuilder();
            for (char c : w.toCharArray()) { String l = lookup(String.valueOf(c)); if (l == null) { b.setLength(0); break; } b.append(l.replace("ˈ", "ˌ")); }
            if (b.length() > 0) return primaryLast(b.toString());
        }
        ps = compound(low);
        if (ps != null) return ps;
        if (fallback != null) {
            String f = fallback.apply(low);
            if (f != null && !f.isBlank()) return f.trim();
        }
        return rules(low, british);
    }

    /** The word as said, in lower case, or capitalised, from gold then silver. */
    String lookup(String w) {
        for (String k : new String[]{w, w.toLowerCase(Locale.ROOT), cap(w.toLowerCase(Locale.ROOT))}) {
            String g = gold.get(k);
            if (g != null) return g;
        }
        for (String k : new String[]{w, w.toLowerCase(Locale.ROOT), cap(w.toLowerCase(Locale.ROOT))}) {
            String s = silver.get(k);
            if (s != null) return s;
        }
        return null;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** A known stem with an ending: 's, -s/-es/-ies, -ed, -ing (misaki's rules for the ending's sound). */
    String stems(String w) {
        if (w.endsWith("'s") && w.length() > 3) { String st = lookup(w.substring(0, w.length() - 2)); if (st != null) return s(st); }
        if (w.endsWith("s'") && w.length() > 3) { String st = lookup(w.substring(0, w.length() - 1)); if (st != null) return st; }
        if (w.length() >= 3 && w.endsWith("s") && !w.endsWith("ss")) {
            String st = lookup(w.substring(0, w.length() - 1));
            if (st == null && w.length() > 4 && w.endsWith("es") && !w.endsWith("ies")) st = lookup(w.substring(0, w.length() - 2));
            if (st == null && w.length() > 4 && w.endsWith("ies")) st = lookup(w.substring(0, w.length() - 3) + "y");
            if (st != null) return s(st);
        }
        if (w.length() >= 4 && w.endsWith("d") && !w.endsWith("dd")) {
            String st = lookup(w.substring(0, w.length() - 1));
            if (st == null && w.length() > 4 && w.endsWith("ed") && !w.endsWith("eed")) st = lookup(w.substring(0, w.length() - 2));
            if (st != null) return ed(st);
        }
        if (w.length() >= 5 && w.endsWith("ing")) {
            String st = w.length() > 5 ? lookup(w.substring(0, w.length() - 3)) : null;
            if (st == null) st = lookup(w.substring(0, w.length() - 3) + "e");
            if (st == null && w.length() > 5 && w.matches(".*([bcdgklmnprstvxz])\\1ing$")) st = lookup(w.substring(0, w.length() - 4));
            if (st != null) return ing(st);
        }
        return null;
    }

    private String s(String stem) {
        char c = last(stem);
        if ("ptkfθ".indexOf(c) >= 0) return stem + "s";
        if ("szʃʒʧʤ".indexOf(c) >= 0) return stem + (british ? "ɪ" : "ᵻ") + "z";
        return stem + "z";
    }

    private String ed(String stem) {
        char c = last(stem);
        if ("pkfθʃsʧ".indexOf(c) >= 0) return stem + "t";
        if (c == 'd' || c == 't') return stem + (british ? "ɪ" : "ᵻ") + "d";
        return stem + "d";
    }

    private String ing(String stem) {
        return stem + "ɪŋ";
    }

    private static char last(String s) {
        for (int i = s.length() - 1; i >= 0; i--) if (s.charAt(i) != 'ˈ' && s.charAt(i) != 'ˌ' && s.charAt(i) != 'ː') return s.charAt(i);
        return ' ';
    }

    /** Two known words run together ("redstone", "pickaxes" aside): the longest known first part that leaves a known rest. */
    String compound(String w) {
        for (int cut = w.length() - 3; cut >= 3; cut--) {
            String a = w.substring(0, cut), b = w.substring(cut);
            String pa = lookup(a), pb = lookup(b);
            if (pb == null) pb = stems(b);
            if (pa != null && pb != null) return pa + pb.replace("ˈ", "ˌ");
        }
        return null;
    }

    private static boolean startsWithVowelSound(String w) {
        String l = w.toLowerCase(Locale.ROOT);
        if (l.startsWith("hour") || l.startsWith("honest") || l.startsWith("heir")) return true;
        if (l.startsWith("uni") || l.startsWith("use") || l.startsWith("eu") || l.startsWith("one")) return false;
        return "aeiou".indexOf(l.charAt(0)) >= 0;
    }

    private static String primaryLast(String s) {
        int i = s.lastIndexOf('ˌ');
        return i < 0 ? s : s.substring(0, i) + "ˈ" + s.substring(i + 1);
    }

    // ------------------------------------------------------------------ letter-to-sound, the last resort (names it's never heard)

    private static final String[][] RULES_GB = {
        {"tion", "ʃən"}, {"sion", "ʒən"}, {"ough", "ɒf"}, {"augh", "ɔː"}, {"igh", "I"}, {"tch", "ʧ"}, {"dge", "ʤ"},
        {"sh", "ʃ"}, {"ch", "ʧ"}, {"th", "θ"}, {"ph", "f"}, {"ck", "k"}, {"ng", "ŋ"}, {"qu", "kw"}, {"wh", "w"}, {"kn", "n"}, {"wr", "ɹ"},
        {"ee", "iː"}, {"ea", "iː"}, {"oo", "uː"}, {"ou", "W"}, {"ow", "Q"}, {"ai", "A"}, {"ay", "A"}, {"oa", "Q"}, {"oi", "Y"}, {"oy", "Y"},
        {"au", "ɔː"}, {"aw", "ɔː"}, {"ar", "ɑː"}, {"or", "ɔː"}, {"er", "ɜː"}, {"ir", "ɜː"}, {"ur", "ɜː"}, {"ie", "iː"}, {"ey", "A"},
        {"a", "a"}, {"e", "ɛ"}, {"i", "ɪ"}, {"o", "ɒ"}, {"u", "ʌ"}, {"y", "ɪ"},
        {"b", "b"}, {"c", "k"}, {"d", "d"}, {"f", "f"}, {"g", "ɡ"}, {"h", "h"}, {"j", "ʤ"}, {"k", "k"}, {"l", "l"}, {"m", "m"}, {"n", "n"},
        {"p", "p"}, {"q", "k"}, {"r", "ɹ"}, {"s", "s"}, {"t", "t"}, {"v", "v"}, {"w", "w"}, {"x", "ks"}, {"z", "z"},
    };

    /** Plain English spelling rules: digraphs, a silent final e that lengthens the vowel before, soft c and g, a stress. */
    static String rules(String w, boolean british) {
        String s = w.replaceAll("[^a-z]", "");
        if (s.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        boolean magicE = s.length() >= 3 && s.endsWith("e") && "aeiou".indexOf(s.charAt(s.length() - 2)) < 0 && "aeiou".indexOf(s.charAt(s.length() - 3)) >= 0;
        int end = magicE ? s.length() - 1 : s.length();
        int i = 0;
        while (i < end) {
            if (magicE && i == end - 2 && "aeiou".indexOf(s.charAt(i)) >= 0) {
                char v = s.charAt(i);
                out.append(switch (v) { case 'a' -> "A"; case 'e' -> british ? "iː" : "i"; case 'i' -> "I"; case 'o' -> british ? "Q" : "O"; default -> "juː"; });
                i++;
                continue;
            }
            char c = s.charAt(i);
            char n = i + 1 < end ? s.charAt(i + 1) : ' ';
            if (c == 'c' && "eiy".indexOf(n) >= 0) { out.append('s'); i++; continue; }
            if (c == 'g' && "eiy".indexOf(n) >= 0 && i > 0) { out.append('ʤ'); i++; continue; }
            if (c == 'y' && i == end - 1 && i > 0) { out.append(british ? "i" : "i"); i++; continue; }
            if (c == 'e' && i == end - 1 && i > 1) { i++; continue; }              // a final e is silent
            String[] hit = null;
            for (String[] r : RULES_GB) if (s.startsWith(r[0], i) && i + r[0].length() <= end) { hit = r; break; }
            if (hit == null) { i++; continue; }
            String ph = hit[1];
            if (!british) ph = ph.replace("ɒ", "ɑ").replace("Q", "O").replace("ɑː", "ɑɹ").replace("ɔː", "ɔɹ").replace("ɜː", "ɜɹ").replace("iː", "i").replace("uː", "u").replace("a", "æ");
            if (british && hit[0].equals("r") && (i + 1 >= end || "aeiouy".indexOf(s.charAt(i + 1)) < 0)) ph = "";   // non-rhotic
            out.append(ph);
            i += hit[0].length();
        }
        String ps = out.toString();
        for (int k = 0; k < ps.length(); k++) if (VOWELS.indexOf(ps.charAt(k)) >= 0) return ps.substring(0, k) + "ˈ" + ps.substring(k);
        return ps;
    }

    /** espeak-ng's IPA mapped to misaki's symbols (misaki's own table), for a word nothing else knew. */
    public static String fromEspeak(String ipa, boolean british) {
        String ps = ipa.trim().replace("_", " ").replaceAll("\\s+", " ");
        String[][] e2m = {{"ʔˌn̩", "ʔn"}, {"ʔn̩", "ʔn"}, {"aɪ", "I"}, {"aʊ", "W"}, {"dʒ", "ʤ"}, {"eɪ", "A"}, {"tʃ", "ʧ"}, {"ɔɪ", "Y"}, {"əl", "ᵊl"},
            {"ʲo", "jo"}, {"ʲə", "jə"}, {"ʲ", ""}, {"ɚ", "əɹ"}, {"r", "ɹ"}, {"x", "k"}, {"ç", "k"}, {"ɐ", "ə"}, {"ɬ", "l"}, {"̃", ""}};
        for (String[] r : e2m) ps = ps.replace(r[0], r[1]);
        if (british) ps = ps.replace("eə", "ɛː").replace("iə", "ɪə").replace("əʊ", "Q");
        else ps = ps.replace("oʊ", "O").replace("ɜːɹ", "ɜɹ").replace("ɜː", "ɜɹ").replace("ɪə", "iə").replace("ː", "");
        return ps.replace("e", "A").replace("o", "ɔ").replace("ɾ", "T").replace("ʔ", "t");
    }
}
