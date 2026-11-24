package com.jrpetty.mcassistant.pa;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [pa36] How much a player means, read the way a player says it: "100", "a stack", "two stacks", "half a stack",
 * "a few", "loads", "all of it", "a shulker's worth", "enough for a house", "as much as you can carry", "until it's
 * full"; with British turns ("a fair few", "a gross", "the lot"), typos ("3 staks") and the shapes a speech
 * recogniser writes ("for stacks", "to stacks"). Pure Java: a stack is counted in stacks until the item is known
 * (sixteen eggs make a stack, one sword does), and the open-ended ones keep their meaning for the job to settle.
 */
public final class PaQty {

    private PaQty() {}

    /** What sort of amount it is. */
    public enum Mode {
        /** Nothing said: the job's own default. */
        NONE,
        /** A count of items. */
        EXACT,
        /** A count of stacks (fractions allowed): items once the item's stack size is known. */
        STACKS,
        /** All of it: every one there is (in the pack, the chest, the player's inventory). */
        ALL,
        /** Until the container is full. */
        FULL,
        /** As much as it can carry in one go. */
        CARRY,
        /** "Some": a sensible handful, the job decides. */
        SOME
    }

    public record Qty(Mode mode, int n, double stacks) {
        public static final Qty NONE = new Qty(Mode.NONE, 0, 0);
        public static final Qty ALL = new Qty(Mode.ALL, 0, 0);
        public static final Qty FULL = new Qty(Mode.FULL, 0, 0);
        public static final Qty CARRY = new Qty(Mode.CARRY, 0, 0);
        public static final Qty SOME = new Qty(Mode.SOME, 0, 0);

        public static Qty of(int n) { return new Qty(Mode.EXACT, Math.max(1, Math.min(MAX, n)), 0); }
        public static Qty stacks(double s) { return new Qty(Mode.STACKS, 0, Math.max(0.25, Math.min(64, s))); }

        public boolean said() { return mode != Mode.NONE; }

        /** A number of items, the item's stack size given; {@code open} for the open-ended kinds (ALL, FULL, CARRY, NONE). */
        public int items(int maxStack, int open) {
            return switch (mode) {
                case EXACT -> n;
                case STACKS -> Math.max(1, (int) Math.round(stacks * Math.max(1, maxStack)));
                case SOME -> Math.max(1, Math.min(16, maxStack));
                default -> open;
            };
        }

        /** Short form for the tests and the job's spec: "100", "2st", "0.5st", "all", "full", "carry", "some", "-". */
        public String brief() {
            return switch (mode) {
                case NONE -> "-";
                case EXACT -> Integer.toString(n);
                case STACKS -> (stacks == Math.floor(stacks) ? Integer.toString((int) stacks) : Double.toString(stacks)) + "st";
                case ALL -> "all";
                case FULL -> "full";
                case CARRY -> "carry";
                case SOME -> "some";
            };
        }

        public static Qty parseBrief(String b) {
            if (b == null || b.isEmpty() || b.equals("-")) return NONE;
            try {
                if (b.endsWith("st")) return stacks(Double.parseDouble(b.substring(0, b.length() - 2)));
                return switch (b) {
                    case "all" -> ALL;
                    case "full" -> FULL;
                    case "carry" -> CARRY;
                    case "some" -> SOME;
                    default -> of(Integer.parseInt(b));
                };
            } catch (NumberFormatException e) {
                return NONE;
            }
        }

        /** In words, for its replies: "100", "2 stacks", "half a stack", "all of it", "a chest full". */
        public String words() {
            return switch (mode) {
                case NONE -> "some";
                case EXACT -> Integer.toString(n);
                case STACKS -> stacks == 0.5 ? "half a stack" : stacks == 1 ? "a stack"
                    : (stacks == Math.floor(stacks) ? Integer.toString((int) stacks) : Double.toString(stacks)) + " stacks";
                case ALL -> "all of it";
                case FULL -> "until it's full";
                case CARRY -> "as much as I can carry";
                case SOME -> "some";
            };
        }
    }

    /** The most a single order can ask for (a double chest of a 64-stack item). */
    public static final int MAX = 54 * 64;

    /** A quantity and the words that said it (so the rest of the sentence can be read without it). */
    public record Found(Qty qty, int start, int end) {
        public static final Found NONE = new Found(Qty.NONE, -1, -1);
    }

    // stack words, typos and all
    private static final String ST = "(?:stacks?|staks?|stax|stacs?|stcks?|stakcs?|sacks?|stack's)";
    private static final Pattern P_N_AND_HALF_STACKS = Pattern.compile("\\b(\\d{1,2}) and a half " + ST + "\\b");
    private static final Pattern P_STACK_AND_HALF = Pattern.compile("\\b(?:a|one) " + ST + " and a half\\b");
    private static final Pattern P_HALF_STACK = Pattern.compile("\\b(?:half (?:a |an? )?" + ST + "|half " + ST + "|a half " + ST + ")\\b");
    private static final Pattern P_QUARTER_STACK = Pattern.compile("\\b(?:a )?quarter (?:of )?(?:a )?" + ST + "\\b");
    private static final Pattern P_N_STACKS = Pattern.compile("\\b(\\d{1,2}|a couple of|a couple|couple of|a few|few|several) " + ST + "(?: of)?\\b");
    private static final Pattern P_A_STACK = Pattern.compile("\\b(?:a|one|1|a full|a whole|full|whole) " + ST + "(?: of)?\\b");
    private static final Pattern P_STACK_ALONE = Pattern.compile("^" + ST + "(?: of)?\\b");
    private static final Pattern P_SHULKER = Pattern.compile("\\b(?:a |one )?(?:shulker(?: box)?(?:'s|s)?(?: box)? (?:worth|full|load)|shulker(?: box)?ful|full shulker(?: box)?)(?: of)?\\b");
    private static final Pattern P_DOUBLE = Pattern.compile("\\b(?:a |one )?double chest(?:'s|s)? (?:worth|full|load)(?: of)?\\b|\\b(?:a )?double chest ?full(?: of)?\\b");
    private static final Pattern P_CHESTFUL = Pattern.compile("\\b(?:a |one )?(?:chest(?:'s|s)? (?:worth|full|load)|chestful|full chest)(?: of)?\\b");
    private static final Pattern P_HOUSE = Pattern.compile("\\benough (?:for|to (?:build|make)) (?:a |an |the |my |our )?(?:small |little |big |large )?(?:house|home|cottage|cabin|hut|shelter|base)\\b");
    private static final Pattern P_ENOUGH_BUILD = Pattern.compile("\\benough (?:for|to (?:build|make)) (?:a |an |the |my |our )?(?:wall|tower|farm|bridge|path|floor|roof|room|platform|build|building)\\b");
    private static final Pattern P_FULL = Pattern.compile("\\b(?:until|till|til|untill|untl|to|'til) (?:it'?s|it is|its|they'?re|they are|it gets|it'?s all|the chest is|the chest'?s|the barrel is|it be|its all) (?:completely |totally |properly |right )?(?:full|fill|filled|stuffed|packed)(?: up)?\\b"
        + "|\\b(?:until|till|til|untill|to) (?:full|filled|the brim|the top|capacity|it fills(?: up)?|it'?s full up)\\b|\\bto the brim\\b|\\ball the way (?:up|full)\\b|\\bfill(?:ed)? (?:it |them )?(?:all the way |right )?up\\b|\\bfull up\\b");
    private static final Pattern P_CARRY = Pattern.compile("\\b(?:as (?:much|many) as (?:you|u|ya) (?:can|could) (?:carry|hold|fit|manage|take|get)|all (?:you|u) (?:can|could) (?:carry|hold|fit|take)"
        + "|(?:a |one |your )?(?:full pack|pack ?full|packful|full inventory|inventory ?full|backpack ?full|bag ?full|full load|full bag)(?: of)?|max(?:imum)?(?: amount)?|as (?:much|many) as possible|as (?:much|many) as (?:there is|there are|you find|you can find))\\b");
    private static final Pattern P_ALL = Pattern.compile("\\b(?:all of (?:it|them|the|your|my|em)|all (?:the|your|my|our|of|em)|every (?:last |single )?(?:one|bit|piece|item|block)|every last|the lot|the whole lot|everything|all|whatever (?:you|i|we) (?:have|has|got)|any and all)\\b");
    private static final Pattern P_LOADS = Pattern.compile("\\b(?:loads|load|lots|a lot|alot|a load|a ton|tons|tonnes|heaps|a heap|masses|a mass|plenty|a bunch|bunches|a pile|piles|a shed ?load|a ship ?load|a truck ?load|a boat ?load|oodles|stacks and stacks|a whole bunch|a good amount|a decent amount|a fair amount|a fair bit|a good few|a fair few|quite a few|quite a lot|a stack or two|a bucket ?load|a crap ?ton|shit ?loads|a shit ?load|bare|mad amounts|a mountain)(?: of)?\\b");
    private static final Pattern P_FEW = Pattern.compile("\\b(?:a few|few|a handful|handful|a couple|couple|a smattering|a bit|a little|a small amount|a wee bit|a tad|several)(?: of)?\\b");
    private static final Pattern P_SOME = Pattern.compile("\\b(?:some|any|more|a bit more)\\b");
    private static final Pattern P_GROSS = Pattern.compile("\\ba gross(?: of)?\\b");
    private static final Pattern P_SCORE = Pattern.compile("\\b(?:a )?score(?: of)?\\b");
    private static final Pattern P_HALF_DOZEN = Pattern.compile("\\bhalf (?:a )?(?:dozen|12)(?: of)?\\b");
    private static final Pattern P_K = Pattern.compile("\\b(\\d{1,2})k\\b");
    private static final Pattern P_TIMES = Pattern.compile("\\b(?:x ?(\\d{1,4})|(\\d{1,4}) ?x)\\b");
    private static final Pattern P_NUM = Pattern.compile("\\b(\\d{1,4})\\b");
    /** Numbers that are not amounts: a level, a size, a time, an ordinal. */
    private static final Pattern P_NOT_AMOUNT = Pattern.compile("\\b(?:level|y|layer|depth) ?-?\\d{1,3}\\b|\\b\\d{1,2} ?(?:x|by) ?\\d{1,2}\\b"
        + "|\\b\\d{1,4} ?(?:minutes?|mins?|seconds?|secs?|hours?|hrs?|blocks? (?:away|north|south|east|west|over|down|up|deep|wide|long|high)|trips?|times|runs?|loads? (?:of trips)?|o'?clock|am|pm)\\b|\\b\\d{1,4}(?:st|nd|rd|th)\\b");

    /** The first amount in the words (already made plain: lower case, number words as digits). */
    public static Found find(String c) {
        if (c == null || c.isEmpty()) return Found.NONE;
        String s = prep(c);
        Found best = Found.NONE;
        best = earlier(best, s, P_N_AND_HALF_STACKS, m -> Qty.stacks(Integer.parseInt(m.group(1)) + 0.5));
        best = earlier(best, s, P_STACK_AND_HALF, m -> Qty.stacks(1.5));
        best = earlier(best, s, P_HALF_STACK, m -> Qty.stacks(0.5));
        best = earlier(best, s, P_QUARTER_STACK, m -> Qty.stacks(0.25));
        best = earlier(best, s, P_N_STACKS, m -> {
            String g = m.group(1);
            double n = g.matches("\\d+") ? Integer.parseInt(g) : g.contains("couple") ? 2 : 3;
            return Qty.stacks(n);
        });
        best = earlier(best, s, P_A_STACK, m -> Qty.stacks(1));
        best = earlier(best, s, P_STACK_ALONE, m -> Qty.stacks(1));
        best = earlier(best, s, P_SHULKER, m -> Qty.stacks(27));
        best = earlier(best, s, P_DOUBLE, m -> Qty.stacks(54));
        best = earlier(best, s, P_CHESTFUL, m -> Qty.stacks(27));
        best = earlier(best, s, P_HOUSE, m -> Qty.of(128));
        best = earlier(best, s, P_ENOUGH_BUILD, m -> Qty.of(96));
        best = earlier(best, s, P_FULL, m -> Qty.FULL);
        best = earlier(best, s, P_CARRY, m -> Qty.CARRY);
        best = earlier(best, s, P_GROSS, m -> Qty.of(144));
        best = earlier(best, s, P_HALF_DOZEN, m -> Qty.of(6));
        best = earlier(best, s, P_K, m -> Qty.of(Integer.parseInt(m.group(1)) * 1000));
        best = earlier(best, s, P_TIMES, m -> Qty.of(Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2))));
        // a plain number, unless it is a level, a size or a time
        String masked = mask(s, P_NOT_AMOUNT);
        best = earlier(best, masked, P_NUM, m -> Qty.of(Integer.parseInt(m.group(1))));
        best = earlier(best, s, P_SCORE, m -> Qty.of(20));
        best = earlier(best, s, P_LOADS, m -> Qty.of(128));
        if (best == Found.NONE) best = earlier(best, s, P_ALL, m -> Qty.ALL);
        if (best == Found.NONE) best = earlier(best, s, P_FEW, m -> Qty.of(m.group().contains("couple") ? 2 : m.group().contains("several") ? 6 : 4));
        if (best == Found.NONE) best = earlier(best, s, P_SOME, m -> Qty.SOME);
        return best;
    }

    /** Lower case, with a recogniser's "for stacks", "to stacks" (numbers written as words that sound like them) put right. */
    static String prep(String c) {
        return c.toLowerCase(Locale.ROOT)
            .replaceAll("\\benough ((?:[a-z]+ ){1,2})(for|to build|to make) ((?:a |an |the |my |our )?(?:small |little |big |large )?(?:house|home|cottage|cabin|hut|shelter|base|wall|tower|farm|bridge|path|floor|roof|room|platform))\\b", "enough $2 $3 $1")
            .replaceAll("\\bas (much|many) ((?:[a-z]+ ){1,2})as (you|u|ya) (can|could) (carry|hold|fit|manage|take|get)\\b", "as $1 as $3 $4 $5 $2").replaceAll("\\b(?:for|four) (" + ST + ")\\b", "4 $1").replaceAll("\\b(?:to|too|two) (" + ST + ")\\b", "2 $1")
            .replaceAll("\\b(?:won|one) (" + ST + ")\\b", "1 $1").replaceAll("\\bate (" + ST + ")\\b", "8 $1");
    }

    /** Just the amount. */
    public static Qty read(String c) {
        return find(c).qty();
    }

    /** The words with the amount taken out ("2 stacks of oak" -> "oak"). */
    public static String strip(String c) {
        Found f = find(c);
        if (f.start() < 0) return c;
        String s = prep(c);
        if (f.end() <= s.length()) s = s.substring(0, f.start()) + " " + s.substring(f.end());
        return s.replaceAll("^\\s*of\\b", " ").replaceAll("\\s+", " ").trim();
    }

    private interface Make {
        Qty make(Matcher m);
    }

    private static Found earlier(Found best, String s, Pattern p, Make make) {
        Matcher m = p.matcher(s);
        if (!m.find()) return best;
        if (best != Found.NONE && best.start() <= m.start()) return best;
        try {
            return new Found(make.make(m), m.start(), m.end());
        } catch (RuntimeException e) {
            return best;
        }
    }

    /** The words with the matches blanked out (same length, so positions still line up). */
    private static String mask(String s, Pattern p) {
        Matcher m = p.matcher(s);
        StringBuilder b = new StringBuilder(s);
        while (m.find()) for (int i = m.start(); i < m.end(); i++) b.setCharAt(i, '_');
        return b.toString();
    }
}
