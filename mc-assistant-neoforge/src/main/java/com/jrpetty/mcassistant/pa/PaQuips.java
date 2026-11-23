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
        {PaQuips.Comeback.SHUTUP, "shut up|shutup|shut it|be quiet|quiet please|pipe down|zip it|give it a rest|stop talking|hush|put a sock in it|button it|wind your neck in|stop yapping|enough talking"},
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
