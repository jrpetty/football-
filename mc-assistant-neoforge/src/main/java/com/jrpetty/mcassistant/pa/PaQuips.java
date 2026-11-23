package com.jrpetty.mcassistant.pa;

import java.util.Locale;

/**
 * [pa33b] The pure parts of its sense of humour (no game classes, so they test on their own): recognising lip, praise
 * and joke requests (typos allowed), and choosing which death lines fit.
 */
public final class PaQuips {

    private PaQuips() {}

    public enum Comeback { INSULT, SHUTUP, PRAISE, LOVE, JOKE }
    

    private static final Object[][] COMEBACKS = {
        {PaQuips.Comeback.JOKE, "tell me a joke|tell us a joke|tell a joke|say something funny|make me laugh|cheer me up|got any jokes|know any jokes|a joke|joke please|another joke"},
        {PaQuips.Comeback.LOVE, "love you|love ya|luv you|luv u|luv ya|i love you|you're the best|youre the best|ur the best|best mate|marry me|my favourite|you're my favourite|adore you"},
        {PaQuips.Comeback.PRAISE, "good lad|good boy|good girl|good job|well done|nice work|great job|great work|legend|absolute legend|top man|top lad|star|you're a star|brilliant work|cracking job|proper job|good effort|nice one mate|clever clogs|smashing|you're amazing|youre amazing|ace work|good work"},
        {PaQuips.Comeback.INSULT, "useless|rubbish|garbage|terrible|awful|pathetic|hopeless|worst|bad at this|you suck|muppet|numpty|plonker|idiot|stupid|dumb|thick|donkey|waste of space|pillock|wally|div|nitwit|clown|you're a joke|youre a joke|get lost|naff|dozy|daft|slow coach|lazy"},
    };

    /** What its player's words are, if they're lip or a kind word or a joke request (typos allowed); else null. */
    public static PaQuips.Comeback comeback(String text) {
        if (text == null) return null;
        String c = " " + PaWords.clean(text).toLowerCase(Locale.ROOT).replace(" , ", " ").replaceAll("\\s+", " ").trim() + " ";
        String[] words = c.trim().split(" ");
        for (Object[] row : COMEBACKS) {
            for (String phrase : ((String) row[1]).split("\\|")) {
                if (c.contains(" " + phrase + " ")) return (PaQuips.Comeback) row[0];
            }
        }
        // a typo of a one-word lip ("usless", "rubish", "legnd")
        for (Object[] row : COMEBACKS) {
            for (String phrase : ((String) row[1]).split("\\|")) {
                if (phrase.contains(" ") || phrase.length() < 6) continue;   // short words have too many honest neighbours ("think")
                for (String w : words) if (w.length() >= 4 && near(w, phrase)) return (PaQuips.Comeback) row[0];
            }
        }
        // a typo inside a two-word lip ("shut uo", "good ladd", "tel me a joke")
        for (Object[] row : COMEBACKS) {
            for (String phrase : ((String) row[1]).split("\\|")) {
                String[] p = phrase.split(" ");
                if (p.length < 2 || phrase.length() < 7) continue;
                for (int i = 0; i + p.length <= words.length; i++) {
                    String span = String.join(" ", java.util.Arrays.copyOfRange(words, i, i + p.length));
                    if (near(span, phrase)) return (PaQuips.Comeback) row[0];
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ [pa33b] "shut up" and "no more jokes", and back on

    /** JOKES: no jokes (the Serious corner's plain words). ALL: only what's needed and warnings. ON: back to itself. */
    public enum Mute { JOKES, ALL, ON }

    private static final String MUTE_JOKES = "stop making jokes|stop making the jokes|stop joking|stop the jokes|no more jokes|no jokes|no jokes please|enough jokes|enough with the jokes|"
        + "enough of the jokes|cut the jokes|cut out the jokes|less jokes|fewer jokes|stop with the jokes|jokes off|turn the jokes off|no banter|stop the banter|enough banter|"
        + "be serious|get serious|stop being funny|stop trying to be funny|serious mode|no more banter|knock off the jokes|drop the jokes|leave out the jokes|keep it serious";
    private static final String MUTE_ALL = "shut up|shutup|shut it|shut your mouth|be quiet|quiet please|keep quiet|stop talking|stop chatting|stop yapping|stop nattering|pipe down|"
        + "zip it|give it a rest|put a sock in it|wind your neck in|button it|belt up|can it|keep it down|enough talking|less talking|quiet mode|silent mode|go quiet|hush up|"
        + "stop going on|stop rabbiting|stop rabbiting on|give it a break|stop your chatter|enough chatter|leave it out|not another word";
    private static final String MUTE_ALL_ALONE = "quiet|hush|shush|shh|sh|silence|enough|enough now|ssh|zip|oi quiet";
    private static final String MUTE_ON = "you can talk again|you can speak again|you can talk now|talk again|speak again|jokes back on|jokes on|jokes on again|you can joke again|"
        + "unmute|unmuted|banter back on|banter on|you can be funny again|be funny again|carry on talking|start talking again|talk to me again|tell jokes again|"
        + "back to normal|you can chat again|chat again|speak freely|ok you can talk|alright you can talk|i miss your jokes|i miss the jokes";
    private static final String MUTE_ON_ALONE = "go on then|go on|talk|unmute|chat|alright then";
    private static final String[] TRAIL = {" please", " mate", " now", " pal", " buddy", " then", " thanks", " cheers", " ok", " okay"};

    /** Is this its player telling it to hush (and how much), or to talk again? Typos allowed; null when it's neither. */
    public static Mute mute(String text, boolean muted) {
        if (text == null) return null;
        String c = PaWords.clean(text).toLowerCase(Locale.ROOT).replace("'", "").replace(" , ", " ").replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
        if (c.startsWith("oi ")) c = c.substring(3);
        if (c.startsWith("please ")) c = c.substring(7);
        String alone = c;
        for (boolean again = true; again; ) {
            again = false;
            for (String t : TRAIL) if (alone.endsWith(t) && alone.length() > t.length()) { alone = alone.substring(0, alone.length() - t.length()); again = true; }
        }
        if (muted) for (String p : MUTE_ON_ALONE.split("\\|")) if (alone.equals(p)) return Mute.ON;
        for (String p : MUTE_ALL_ALONE.split("\\|")) if (alone.equals(p)) return Mute.ALL;
        String padded = " " + c + " ";
        Object[][] rows = {{Mute.ON, MUTE_ON}, {Mute.JOKES, MUTE_JOKES}, {Mute.ALL, MUTE_ALL}};
        for (Object[] row : rows) {
            if (row[0] == Mute.ON && !muted) continue;            // "jokes on" means nothing to a talking assistant
            for (String p : ((String) row[1]).split("\\|")) if (padded.contains(" " + p + " ")) return (Mute) row[0];
        }
        // a typo ("shut uo", "stop makin jokes", "be quite", "you can tlak again")
        String[] words = c.split(" ");
        for (Object[] row : rows) for (String p : ((String) row[1]).split("\\|")) {
            if (row[0] == Mute.ON && !muted) continue;
            String[] pw = p.split(" ");
            if (pw.length < 2 || p.length() < 6) continue;
            for (int i = 0; i + pw.length <= words.length; i++) {
                String span = String.join(" ", java.util.Arrays.copyOfRange(words, i, i + pw.length));
                if (near2(span, p, p.length() >= 12 ? 2 : 1)) return (Mute) row[0];
            }
        }
        return null;
    }

    /** At most so many edits apart (a swapped pair of letters counts as one). */
    static boolean near2(String a, String b, int most) {
        if (Math.abs(a.length() - b.length()) > most) return false;
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) for (int j = 1; j <= b.length(); j++) {
            int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
            d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
            if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
        }
        return d[a.length()][b.length()] <= most;
    }

    /**
     * Ticks between remarks nobody asked for, by chattiness (0 Quiet .. 4 Won't shut up): at the usual setting one every
     * three minutes or so, a little sooner around something happening; only "Won't shut up" keeps going.
     */
    public static int gap(int chatty, boolean event) {
        int g = switch (chatty) { case 4 -> 60; case 3 -> 1200; case 2 -> 3600; case 1 -> 7200; default -> 12000; };
        return event && chatty < 4 ? g / 6 : g;
    }

    /** One edit apart (a typo), for words of five letters or more. */
    static boolean near(String a, String b) {
        if (a.equals(b)) return true;
        if (Math.abs(a.length() - b.length()) > 1) return false;
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()] <= 1;
    }


    /** Which death lines: a repeat first, then near the bed or with diamonds (sometimes), else the cause. */
    public static String deathKey(String cause, boolean repeat, boolean nearBed, boolean diamonds, PaPhrases.Dice d) {
        if (repeat) return "repeat";
        if (nearBed && d.nextInt(100) < 40) return "bed";
        if (diamonds && d.nextInt(100) < 50) return "diamonds";
        return cause;
    }

    /** Things that went where nobody can fetch them back from. */
    public static boolean lost(String cause) {
        return cause.equals("lava") || cause.equals("void");
    }
}
