package com.jrpetty.mcassistant.pa;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * [pa33b] Match my energy: how an order was said. Polite gets warm, blunt gets dry, rude (swearing, or an insult aimed at
 * it) gets a sharp, witty comeback, and the job is done straight away all the same; it never sulks or refuses. The
 * attitude is cut out before the order is read, so "go get my wheat you fucking loser" fetches the wheat. Pure Java,
 * offline: word lists and patterns, with typos and creative spellings (f*cking, fkn, sh1t, fuuuck).
 *
 * <p>How strong its own language may be is the player's Language setting (Clean, Cheeky, Unfiltered), never past the
 * server's cap (PaConfig maxLanguage), and nothing at all while it is hushed.
 */
public final class PaTone {

    private PaTone() {}

    public enum Tone { POLITE, NEUTRAL, BLUNT, RUDE }

    /** Its own language: 0 Clean (never a swear), 1 Cheeky (mild: bloody, damn, arse, git), 2 Unfiltered. */
    public static final String[] LANGUAGE = {"Clean", "Cheeky", "Unfiltered"};

    /** What was heard: the tone, how rude (0 none, 1 rude, 2 rude and aimed at it), polite, and the order with the attitude cut out. */
    public record Read(Tone tone, int rudeness, boolean polite, boolean thanks, String order) {}

    // swears, by their stems; creative spellings are folded first (fold)
    private static final Pattern STRONG = Pattern.compile(
        "f+u*c*k+(ing|in|ed|er|ers|s|face|wit|head)?|fk+(n|ng|ing|in)?|fu+k+(ing|in|er|ed)?|fck+(ing|in|er)?|fuc+(ing|in)?|motherfuck\\w*|"
        + "sh+i+t+(e|ty|ting|head|s)?|sh+te|bullshit|horseshit|"
        + "bastards?|wank(er|ers|ing)?|tossers?|twats?|pricks?|dicks?|dickheads?|bitch(es|y)?|piss(ed|ing)?|pisshead|"
        + "arseholes?|assholes?|dumbass|jackass|bollocks|bellend|knobhead|cocks?");
    private static final Pattern MILD = Pattern.compile(
        "bloody|damn(ed|it)?|dammit|arse|ass|crap(py)?|bugger(ed)?|sod|feck(ing)?|frick(ing|in)?|freaking|flipping|blooming|git|gits|hell|heck");
    private static final String[] INSULTS = {"loser", "losers", "idiot", "idiots", "moron", "morons", "muppet", "numpty", "plonker", "pillock", "wally",
        "donkey", "clown", "prat", "nitwit", "halfwit", "dimwit", "dope", "dummy", "dunce", "useless", "rubbish", "garbage", "trash", "pathetic",
        "lazy", "slow", "slowcoach", "noob", "nub", "scrub", "bot", "waste", "weapon", "melt", "mug", "wazzock", "berk", "div", "doughnut", "tit",
        "dumb", "stupid", "thick", "hopeless", "worthless", "imbecile", "cretin", "buffoon", "goon", "goober", "nonce"};
    private static final String[] POLITE = {"please", "pls", "plz", "pleaseee", "kindly", "thanks", "thank", "thankyou", "ta", "cheers", "appreciate",
        "would you mind", "could you", "would you", "can you please", "if you could", "if you don't mind", "if you dont mind", "when you get a chance",
        "be a dear", "be a love", "you're a star", "youre a star", "lovely", "much appreciated"};
    private static final String[] THANKS = {"thanks", "thank you", "thankyou", "ta", "cheers", "appreciate it", "much appreciated", "nice one", "good job",
        "well done", "sorry"};
    /** Whole swearing phrases, taken out first ("for fuck's sake", "ffs", "damn it"). */
    private static final Pattern PHRASES = Pattern.compile("(?i)\\b(for (fuck'?s|fucks|feck'?s|pete'?s|god'?s|crying out loud) sake|ffs|fs|omfg|wtf|stfu|"
        + "damn it|dammit|bloody hell|what the (fuck|hell|heck)|oh my god|for the love of god)\\b[,.!]?");
    /** Words that come with an insult and go with it ("you absolute moron", "you lazy git"). */
    private static final java.util.Set<String> INTENS = java.util.Set.of("absolute", "total", "complete", "utter", "massive", "proper", "little", "big",
        "fat", "dirty", "stupid", "useless", "lazy", "pathetic", "right", "such", "a", "an");
    private static final String[] YOU = {"you", "ya", "u", "ye", "yer", "youre", "you're", "ur", "your", "yo"};

    /** Creative spellings folded: f*ck, f**king, sh1t, $hit, fuuuuck, a$$. */
    static String fold(String w) {
        String t = w.toLowerCase(Locale.ROOT).replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a').replace('5', 's')
            .replace('$', 's').replace('@', 'a').replace('!', 'i');
        if (t.matches("f[*#%]+k(ing|in|er|ed)?")) t = "fuck" + t.replaceAll("^f[*#%]+k", "");
        t = t.replaceAll("^f[*#%]+c?k", "fuck").replaceAll("^sh[*#%]+t", "shit").replaceAll("^a[*#%]+(se|s)", "arse");
        t = t.replaceAll("[^a-z']", "");
        t = t.replaceAll("(.)\\1{2,}", "$1$1");                // fuuuuck -> fuuck
        return t.replace("uu", "u").replace("ii", "i");
    }

    /** A strong swear. */
    public static boolean strong(String word) {
        String f = fold(word);
        return !f.isEmpty() && STRONG.matcher(f).matches();
    }

    /** A mild one (Cheeky allows these). */
    public static boolean mild(String word) {
        String f = fold(word);
        return !f.isEmpty() && MILD.matcher(f).matches() && !strong(word);
    }

    /** Any swear at all. */
    public static boolean swear(String word) {
        return strong(word) || mild(word);
    }

    /** How an order was said, and the order with the attitude taken out. */
    public static Read read(String text) {
        if (text == null) return new Read(Tone.NEUTRAL, 0, false, false, "");
        String low = text.toLowerCase(Locale.ROOT).replace('’', '\'');
        int phrases = 0;
        java.util.regex.Matcher pm = PHRASES.matcher(low);
        while (pm.find()) phrases++;
        String cut = PHRASES.matcher(low).replaceAll(" ").replaceAll("\\s+", " ").trim();
        String[] raw = cut.isEmpty() ? new String[0] : cut.split("\\s+");
        List<String> keep = new ArrayList<>();
        int swears = phrases, aimed = 0;
        boolean polite = false;
        for (int i = 0; i < raw.length; i++) {
            String w = raw[i];
            String bare = w.replaceAll("^[^a-z0-9*$@#!]+|[^a-z0-9*$@#!']+$", "");
            String punct = w.endsWith(",") || w.endsWith(".") || w.endsWith("!") || w.endsWith("?") ? w.substring(w.length() - 1) : "";
            String f = fold(bare);
            boolean sw = swear(bare);
            boolean ins = isInsult(f);
            if (sw) swears++;
            // an insult aimed at it: "you (fucking) loser", "useless bot", or a name-call at the end ("..., idiot")
            if (ins) {
                boolean youBefore = false;
                for (int k = Math.max(0, i - 3); k < i; k++) if (isYou(fold(raw[k]))) youBefore = true;
                boolean atEnd = i == raw.length - 1 || (i == raw.length - 2 && swear(raw[raw.length - 1]));
                boolean afterSwear = i > 0 && swear(raw[i - 1]);
                if (youBefore || atEnd || afterSwear) aimed++;
                else ins = false;                               // "the slow way", "lazy river": not an insult
            }
            if (sw || ins) {
                // "you" right before a cut insult goes with it ("you fucking loser")
                while (!keep.isEmpty()) {
                    String last = fold(keep.get(keep.size() - 1));
                    if (!(isYou(last) || swear(keep.get(keep.size() - 1)) || (ins && INTENS.contains(last)))) break;
                    keep.remove(keep.size() - 1);
                }
                if (!punct.isEmpty() && !keep.isEmpty()) keep.set(keep.size() - 1, keep.get(keep.size() - 1) + punct);
                continue;
            }
            keep.add(w);
        }
        String rest = String.join(" ", keep);
        String padded = " " + low.replaceAll("[^a-z' ]", " ").replaceAll("\\s+", " ") + " ";
        for (String p : POLITE) if (padded.contains(" " + p + " ")) polite = true;
        boolean thanks = false;
        for (String p : THANKS) if (padded.contains(" " + p + " ")) thanks = true;
        // politeness words come out too, so the reader sees just the order
        String order = (" " + rest + " ").replaceAll("(?i)\\b(please|pls|plz|kindly|cheers|thanks|thank you|ta)\\b[,.!]?", " ")
            .replaceAll("(?i)^\\s*(would you mind|do you mind|could you|would you|can you|will you|if you could|if you don't mind|be a dear and|be a love and)( please)?\\b", " ").replaceAll("\\s+", " ").replaceAll("^[ ,.]+|[ ,]+$", "").trim();
        int rudeness = swears == 0 && aimed == 0 ? 0 : (aimed > 0 && swears > 0) || aimed > 1 || swears > 1 ? 2 : 1;
        Tone tone = rudeness > 0 ? Tone.RUDE : polite ? Tone.POLITE : blunt(order) ? Tone.BLUNT : Tone.NEUTRAL;
        return new Read(tone, rudeness, polite, thanks, order.isEmpty() ? rest.trim() : order);
    }

    private static boolean isInsult(String f) {
        for (String s : INSULTS) if (f.equals(s)) return true;
        return false;
    }

    private static boolean isYou(String f) {
        for (String s : YOU) if (f.equals(s.replace("'", ""))) return true;
        return false;
    }

    /** Short and bare: "wheat. now." "get logs" (no please, no name, four words or fewer). */
    static boolean blunt(String order) {
        String[] w = order.trim().split("\\s+");
        String o = order.trim().toLowerCase(Locale.ROOT);
        return !o.isBlank() && w.length <= 4 && (o.endsWith(".") || o.endsWith("!") || o.endsWith(" now") || o.equals("now"));
    }

    // ------------------------------------------------------------------ the owner's recent tone

    /** A run of abuse warms it up (a little sharper each time); a please or a thanks cools it. Kept per assistant. */
    public static final class Heat {
        private int heat;
        private long at = Long.MIN_VALUE;

        /** Take in one order's tone (now in ticks); returns the heat before it, for the "softened" line. */
        public int take(Read r, long now) {
            cool(now);
            int before = heat;
            if (r.tone() == Tone.RUDE) heat = Math.min(3, heat + r.rudeness());
            else if (r.polite() || r.thanks()) heat = Math.max(-2, heat - (heat > 0 ? 2 : 1));
            at = now;
            return before;
        }

        /** Two minutes of peace and it lets one step go. */
        private void cool(long now) {
            if (at == Long.MIN_VALUE) return;
            long steps = (now - at) / 2400L;
            for (long i = 0; i < steps && heat != 0; i++) heat += heat > 0 ? -1 : 1;
        }

        public int heat() { return heat; }
    }

    // ------------------------------------------------------------------ which lines

    /** The language it may use: the player's choice, never past the server's cap. */
    public static int level(int player, int serverCap) {
        return Math.max(0, Math.min(Math.min(2, player), Math.min(2, serverCap)));
    }

    /** The server cap from config ("clean", "cheeky", "unfiltered"); cheeky when unreadable. */
    public static int cap(String config) {
        String c = config == null ? "" : config.trim().toLowerCase(Locale.ROOT);
        return c.equals("clean") ? 0 : c.equals("unfiltered") ? 2 : 1;
    }

    /** Its reply's line list for this tone (null: just the order's own reply). Hushed: never a roast. */
    public static String key(Read r, int heatBefore, int heatNow, int level, int mute) {
        if (mute > 0) return null;
        String lang = level >= 2 ? "unfiltered" : level == 1 ? "cheeky" : "clean";
        if (r.tone() == Tone.RUDE) return "tone.rude" + (heatNow >= 3 ? "_again" : "") + "." + lang;
        if ((r.polite() || r.thanks()) && heatBefore >= 2) return "tone.softened";
        if (r.tone() == Tone.POLITE) return "tone.polite";
        if (r.tone() == Tone.BLUNT) return "tone.blunt";
        return null;
    }
}
