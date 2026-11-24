package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.pa.PaMemory.Entry;
import com.jrpetty.mcassistant.pa.PaMemory.Kind;
import com.jrpetty.mcassistant.pa.PaMemory.When;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [pa35] How the assistant hears something meant to be remembered, asked back, or forgotten. Plain English, British
 * and American, typed or from a voice transcript (no punctuation, number words, the recogniser's slips), with the
 * usual typos. Offline and deterministic. Anything that isn't about its memory reads as NONE and goes on to the order
 * reader untouched (PaUnderstand), so nothing it already understood changes. Pure Java (the parser unit test feeds it
 * a few hundred phrasings).
 *
 * <p>Never stores anything that looks like a password or real-world personal data (an address, a phone number, an
 * email, a card number, a date of birth, a school): those read as PRIVATE and it declines, politely.
 */
public final class PaMemoryParse {

    private PaMemoryParse() {}

    /** What was heard. */
    public enum Op {
        NONE, SAVE, PLACE, RENAME, FORGET_LAST, FORGET, FORGET_ALL, RECALL_ALL, RECALL, RECALL_PLACE, RECALL_PERSON, PRAISED,
        PRIVATE, ASK, PAUSE, RESUME
    }

    /**
     * The reading: an op, an entry to keep (and maybe a second, "oak not spruce"), what it is about (a place's name, a
     * question's subject, a new name), whether the place is what the player looks at, and for ASK a question with choices
     * (label, what clicking says).
     */
    public record Heard(Op op, Entry entry, Entry also, String subject, boolean looked, String question, List<String[]> choices) {
        static Heard none() { return new Heard(Op.NONE, null, null, "", false, "", List.of()); }

        static Heard of(Op op, String subject) { return new Heard(op, null, null, subject, false, "", List.of()); }

        static Heard save(Entry e) { return new Heard(Op.SAVE, e, null, e.subject, false, "", List.of()); }

        static Heard save(Entry e, Entry also) { return new Heard(Op.SAVE, e, also, e.subject, false, "", List.of()); }

        static Heard ask(String q, List<String[]> choices) { return new Heard(Op.ASK, null, null, "", false, q, choices); }

        public boolean any() { return op != Op.NONE; }

        /** A short form for the tests: "SAVE FAV food=steak", "PLACE mine@look", "RECALL favourite food". */
        public String brief() {
            StringBuilder b = new StringBuilder(op.name());
            if (entry != null) {
                b.append(' ').append(entry.kind.name()).append(' ').append(entry.subject);
                if (!entry.value.isEmpty()) b.append('=').append(entry.value);
                if (entry.when != When.NONE) b.append(" @").append(entry.when.name());
                if (entry.n != 0) b.append(" #").append(entry.n);
                if (!entry.extra.isEmpty()) b.append(" [").append(entry.extra).append(']');
            } else if (!subject.isEmpty()) b.append(' ').append(subject);
            if (also != null) b.append(" + ").append(also.kind.name()).append(' ').append(also.subject);
            if (looked) b.append("@look");
            return b.toString();
        }
    }

    // ------------------------------------------------------------------ words

    /** Misspellings and shorthand, put right (whole words). */
    private static final Map<String, String> FIX = new HashMap<>();
    static {
        String[][] f = {
            {"im", "i'm"}, {"i m", "i'm"}, {"dont", "don't"}, {"cant", "can't"}, {"wont", "won't"}, {"didnt", "didn't"}, {"doesnt", "doesn't"},
            {"youre", "you're"}, {"u", "you"}, {"ya", "you"}, {"whats", "what's"}, {"wheres", "where's"}, {"whos", "who's"}, {"thats", "that's"},
            {"ive", "i've"}, {"ill", "i'll"}, {"id", "i'd"}, {"hes", "he's"}, {"shes", "she's"}, {"theyre", "they're"}, {"isnt", "isn't"},
            {"favorite", "favourite"}, {"fav", "favourite"}, {"fave", "favourite"}, {"favourit", "favourite"}, {"favrite", "favourite"},
            {"favouite", "favourite"}, {"favorites", "favourites"}, {"color", "colour"}, {"colors", "colours"},
            {"freind", "friend"}, {"frend", "friend"}, {"fren", "friend"}, {"freinds", "friends"}, {"m8", "mate"},
            {"rember", "remember"}, {"remeber", "remember"}, {"remembr", "remember"}, {"rememebr", "remember"}, {"remmeber", "remember"},
            {"remmember", "remember"}, {"rmember", "remember"}, {"remind", "remind"}, {"remid", "remind"}, {"remnd", "remind"}, {"rmind", "remind"},
            {"forgot", "forget"}, {"foget", "forget"}, {"forgt", "forget"}, {"frget", "forget"},
            {"mins", "minutes"}, {"min", "minute"}, {"secs", "seconds"}, {"sec", "second"}, {"hrs", "hours"}, {"hr", "hour"},
            {"tonite", "tonight"}, {"2nite", "tonight"}, {"tmrw", "tomorrow"}, {"tmr", "tomorrow"}, {"morn", "morning"},
            {"plz", ""}, {"pls", ""}, {"please", ""}, {"kindly", ""}, {"btw", ""}, {"fyi", ""}, {"ok", ""}, {"okay", ""},
            {"greifer", "griefer"}, {"griefr", "griefer"}, {"greefer", "griefer"}, {"grifer", "griefer"},
            {"creepa", "creeper"}, {"creepers", "creepers"}, {"stake", "steak"}, {"steaks", "steak"},
            {"rubish", "rubbish"}, {"rubbsh", "rubbish"}, {"terible", "terrible"}, {"awfull", "awful"},
            {"thier", "their"}, {"rly", "really"}, {"realy", "really"}, {"reely", "really"}, {"proper", "really"}, {"teh", "the"}, {"hte", "the"}, {"adn", "and"}, {"wnt", "want"},
        };
        for (String[] p : f) FIX.put(p[0], p[1]);
    }

    /** Words it reads by: a near-miss of one of these (one letter off, or two swapped) is put right. */
    private static final List<String> KEYS = List.of("remember", "favourite", "friend", "griefer", "remind", "reminder", "forget", "minutes",
        "morning", "tonight", "everything", "always", "never", "rubbish", "terrible", "useless", "brilliant", "chests", "flowers",
        "creepers", "zombies", "skeletons", "spiders", "trusted", "orders", "swearing", "favourites", "whenever", "furnace");

    private static final Map<String, Integer> NUMS = new HashMap<>();
    static {
        String[] small = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
            "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
        for (int i = 0; i < small.length; i++) NUMS.put(small[i], i);
        String[] tens = {"twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
        for (int i = 0; i < tens.length; i++) NUMS.put(tens[i], 20 + 10 * i);
        NUMS.put("fourty", 40);
    }

    /** Lower case, plain punctuation, the shorthand and typos put right, number words as digits, fillers off the front. */
    public static String norm(String raw, String selfName) {
        String t = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        t = t.replace('’', '\'').replace('‘', '\'').replace('“', ' ').replace('”', ' ').replace('"', ' ');
        t = t.replaceAll("^\\(voice\\)\\s*", "");
        t = t.replaceAll("[!?;]+", " ").replaceAll("\\.(?=\\s|$)", " ").replaceAll("\\s+", " ").trim();
        // a voice transcript's "i m" and the like, and number words
        StringBuilder b = new StringBuilder();
        String[] w = t.split(" ");
        for (int i = 0; i < w.length; i++) {
            String x = w[i];
            String bare = x.replaceAll("[,:]+$", "");
            String tail = x.substring(bare.length());
            if (bare.isEmpty()) { b.append(x).append(' '); continue; }
            if (i + 1 < w.length && bare.equals("i") && w[i + 1].equals("m")) { b.append("i'm").append(tail).append(' '); i++; continue; }
            String fixed = FIX.getOrDefault(bare, null);
            if (fixed == null) fixed = near(bare);
            // number words: "twenty five" -> 25, "ten" -> 10
            Integer n = NUMS.get(fixed);
            if (n != null) {
                if (n >= 20 && n % 10 == 0 && i + 1 < w.length && NUMS.containsKey(w[i + 1].replaceAll("[,:]+$", "")) && NUMS.get(w[i + 1].replaceAll("[,:]+$", "")) < 10) {
                    n += NUMS.get(w[i + 1].replaceAll("[,:]+$", ""));
                    tail = w[i + 1].substring(w[i + 1].replaceAll("[,:]+$", "").length());
                    i++;
                }
                fixed = Integer.toString(n);
            }
            if (!fixed.isEmpty()) b.append(fixed).append(tail).append(' ');
        }
        t = b.toString().replaceAll("\\s+", " ").trim();
        t = t.replaceAll("\\b(\\d+)-(\\d+)\\b", "$1$2");     // "twenty-five" said as "20-5" by a recogniser stays out of the way
        // its own name, and the fillers, off the front: "hey basil, just so you know, ..."
        String self = selfName == null ? "" : Pattern.quote(selfName.toLowerCase(Locale.ROOT));
        for (int k = 0; k < 4; k++) {
            String before = t;
            t = t.replaceAll("^(?:hey|hi|oi|yo|so|and|also|right|well|listen|look|now|anyway|alright)\\b,? ?", "");
            if (!self.isEmpty()) t = t.replaceAll("^" + self + "\\b,? ?", "");
            t = t.replaceAll("^(?:assistant|buddy|mate|pal)\\b,? ", "");
            t = t.replaceAll("^(?:just so you know|just so you're aware|for the record|for future reference|by the way|for your information|note that|take note|make a note|note|heads up)\\b,? ?(?:that )?", "");
            t = t.replaceAll("^,\\s*", "").trim();
            if (t.equals(before)) break;
        }
        return t.replaceAll(",$", "").trim();
    }

    /** One of the key words, if this is one letter off it (or two letters swapped); else the word itself. */
    static String near(String w) {
        if (w.length() < 5 || KEYS.contains(w)) return w;
        if (w.endsWith("s") && KEYS.contains(w.substring(0, w.length() - 1))) return w;      // a plural is not a slip
        for (String k : KEYS) if (Math.abs(k.length() - w.length()) <= 1 && dist(k, w) <= 1) return k;
        return w;
    }

    /** Edit distance, a swap of neighbours counting one. */
    static int dist(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + c);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
            }
        }
        return d[a.length()][b.length()];
    }

    // ------------------------------------------------------------------ what things are

    static final Set<String> FOODS = Set.of("steak", "beef", "bread", "porkchop", "porkchops", "pork", "chicken", "mutton", "lamb", "rabbit", "rabbit stew",
        "fish", "cod", "salmon", "apple", "apples", "golden apple", "golden apples", "golden carrot", "golden carrots", "carrot", "carrots", "potato",
        "potatoes", "baked potato", "baked potatoes", "cake", "cookie", "cookies", "pumpkin pie", "pie", "melon", "melons", "berries", "sweet berries",
        "glow berries", "stew", "mushroom stew", "soup", "beetroot", "beetroot soup", "honey", "kelp", "dried kelp", "chorus fruit", "bacon", "sushi");
    static final Set<String> WOODS = Set.of("oak", "spruce", "birch", "jungle", "acacia", "dark oak", "mangrove", "cherry", "crimson", "warped", "bamboo", "pale oak");
    static final Set<String> MOBS = Set.of("creeper", "zombie", "skeleton", "spider", "cave spider", "enderman", "endermen", "witch", "phantom", "slime",
        "drowned", "pillager", "ghast", "blaze", "piglin", "hoglin", "husk", "stray", "warden", "silverfish", "guardian", "vindicator", "evoker", "ravager",
        "wither", "ender dragon", "dragon", "bee", "wolf", "wolves", "cat", "fox", "llama", "goat", "chicken", "cow", "pig", "sheep", "horse",
        "parrot", "axolotl", "frog", "villager", "iron golem", "golem", "monster", "mob");
    static final Map<String, String> FLOWERS = new HashMap<>();
    static {
        FLOWERS.put("blue", "minecraft:cornflower,minecraft:blue_orchid");
        FLOWERS.put("red", "minecraft:poppy,minecraft:red_tulip,minecraft:rose_bush");
        FLOWERS.put("yellow", "minecraft:dandelion,minecraft:sunflower");
        FLOWERS.put("white", "minecraft:lily_of_the_valley,minecraft:oxeye_daisy,minecraft:white_tulip,minecraft:azure_bluet");
        FLOWERS.put("pink", "minecraft:pink_tulip,minecraft:peony,minecraft:pink_petals");
        FLOWERS.put("orange", "minecraft:orange_tulip,minecraft:torchflower");
        FLOWERS.put("purple", "minecraft:allium,minecraft:lilac");
        FLOWERS.put("poppy", "minecraft:poppy");
        FLOWERS.put("dandelion", "minecraft:dandelion");
        FLOWERS.put("tulip", "minecraft:red_tulip,minecraft:orange_tulip,minecraft:white_tulip,minecraft:pink_tulip");
        FLOWERS.put("cornflower", "minecraft:cornflower");
        FLOWERS.put("orchid", "minecraft:blue_orchid");
        FLOWERS.put("allium", "minecraft:allium");
        FLOWERS.put("daisy", "minecraft:oxeye_daisy");
        FLOWERS.put("daisie", "minecraft:oxeye_daisy");
        FLOWERS.put("lily", "minecraft:lily_of_the_valley");
        FLOWERS.put("lilie", "minecraft:lily_of_the_valley");
        FLOWERS.put("rose", "minecraft:rose_bush,minecraft:poppy");
        FLOWERS.put("sunflower", "minecraft:sunflower");
        FLOWERS.put("peony", "minecraft:peony");
        FLOWERS.put("lilac", "minecraft:lilac");
        FLOWERS.put("bluet", "minecraft:azure_bluet");
    }

    /** What sort of thing a like or dislike is about: food, wood, mob, flower, or thing. */
    public static String category(String what) {
        String k = PaMemory.Entry.key(what).replaceAll("^(?:the|my|a|an|some|all|any) ", "");
        String s = PaMemory.stem(k);
        String bare = k.replaceAll(" (?:wood|logs?|planks|trees?)$", "");
        if (WOODS.contains(bare) && (!bare.equals(k) || !FOODS.contains(k))) return "wood";
        if (FOODS.contains(k) || FOODS.contains(s)) return "food";
        if (MOBS.contains(k) || MOBS.contains(s)) return "mob";
        if (k.contains("flower") || FLOWERS.containsKey(s)) return "flower";
        return "thing";
    }

    /** The flowers' block ids a phrase means ("the blue flowers", "poppies"), or "" when it isn't flowers. */
    public static String flowerBlocks(String what) {
        String k = PaMemory.Entry.key(what).replaceAll("^(?:the|my|a|an|some|all|any|those|these) ", "");
        Matcher m = Pattern.compile("^(blue|red|yellow|white|pink|orange|purple) (?:flowers?|ones)$").matcher(k);
        if (m.find()) return FLOWERS.get(m.group(1));
        if (k.matches("^flowers?$")) return "#flowers";
        String s = PaMemory.stem(k);
        return FLOWERS.getOrDefault(s, "");
    }

    /** A Minecraft player name as said (letters, digits, underscore; 2-16): not a pronoun or a plain word. */
    private static final Set<String> NOT_NAMES = Set.of("he", "she", "they", "it", "this", "that", "who", "what", "everyone", "everybody", "nobody",
        "someone", "you", "i", "me", "we", "us", "him", "her", "them", "there", "here", "my", "your", "the", "a", "an", "mining", "home", "base", "food",
        "steak", "dinner", "lunch", "breakfast", "night", "morning", "today", "tomorrow", "tonight", "now", "later", "time", "all");

    static boolean name(String w) {
        return w != null && w.matches("[a-z0-9_]{2,16}") && !NOT_NAMES.contains(w) && !MOBS.contains(PaMemory.stem(w)) && !FOODS.contains(w) && !w.matches("\\d+");
    }

    /** The word as it was written (its capitals), found in the raw text; else the word with a capital. */
    static String asSaid(String raw, String word) {
        Matcher m = Pattern.compile("(?i)\\b" + Pattern.quote(word) + "\\b").matcher(raw == null ? "" : raw);
        if (m.find()) {
            String s = m.group();
            return s.equals(s.toLowerCase(Locale.ROOT)) ? PaMemory.cap(s) : s;
        }
        return PaMemory.cap(word);
    }

    // ------------------------------------------------------------------ private things it never keeps

    private static final Pattern PRIVATE = Pattern.compile(
        "\\b(?:pass ?word|passcode|pass code|pin(?: code| number)?|log ?in details?|login|username and password|security question|password is|"
        + "bank|card number|credit card|debit card|sort code|account number|cvv|social security|national insurance|ni number|nhs number|passport|"
        + "driving licen[cs]e|home address|my address|post ?code|zip ?code|phone number|mobile number|my number is|my phone|"
        + "my email|email address|e-mail|date of birth|birthday is|born on|my real name|my full name|my surname|my last name|my school|i go to school|"
        + "school is called|my age is|i live (?:at|on|in) (?!my |the |a |this |here)|i'm \\d{1,2} years? old|i am \\d{1,2} years? old|my ip|ip address|my mum's name|my mother's maiden)\\b");
    private static final Pattern EMAIL = Pattern.compile("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?:\\+?\\d[\\d ()-]{8,}\\d)");
    private static final Pattern POSTCODE = Pattern.compile("\\b[a-z]{1,2}\\d[a-z\\d]? ?\\d[a-z]{2}\\b");
    private static final Pattern STREET = Pattern.compile("\\b\\d{1,4} [a-z]+(?: [a-z]+)? (?:street|st|road|rd|avenue|ave|lane|ln|drive|dr|close|crescent|way|boulevard|blvd|court|terrace|place)\\b");

    /** Does this sentence carry something it must never keep (a password, an address, a phone number, an email...)? */
    public static boolean secret(String raw) {
        String t = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replace('’', '\'');
        if (EMAIL.matcher(t).find() || STREET.matcher(t).find()) return true;
        // a long number not a game coordinate or amount: three or more number groups like a phone number
        Matcher ph = PHONE.matcher(t);
        while (ph.find()) {
            String digits = ph.group().replaceAll("\\D", "");
            boolean coords = ph.group().trim().matches("-?\\d{1,7}[ ,]+-?\\d{1,3}[ ,]+-?\\d{1,7}");
            if (digits.length() >= 9 && !coords) return true;
        }
        if (POSTCODE.matcher(t).find() && t.matches(".*\\b(?:live|address|postcode|post code|house|flat)\\b.*")) return true;
        return PRIVATE.matcher(t).find();
    }

    // ------------------------------------------------------------------ the patterns

    private static Pattern p(String re) { return Pattern.compile("^" + re + "$"); }

    private static final String THE = "(?:the |my |our |a |an |some |all |any |those |these )?";
    private static final String WHAT = "([a-z][a-z' -]{0,30}?)";

    // recall
    private static final Pattern RECALL_ALL = p("(?:what|wot) (?:do|did|else do) you (?:remember|know|recall)(?: about (?:me|us|stuff|things))?|what have i told you(?: so far)?|"
        + "tell me (?:what|everything) you (?:remember|know)(?: about me)?|(?:list|show me|show) (?:your |all your |everything you )?(?:memories|memory|remember)|"
        + "what(?:'s| is) in your (?:memory|head|notes)|what do you know|do you remember anything|what are you remembering|your memories|memories|memory");
    private static final Pattern RECALL_ABOUT = p("(?:what do you (?:remember|know)|what did i (?:tell|say to) you|tell me what you (?:remember|know)) about " + THE + "([a-z0-9_ ]{2,24})");
    private static final Pattern RECALL_FAV = p("(?:what(?:'s| is| was)|tell me|do you (?:know|remember)|remember) (?:what )?my favourite ([a-z ]{2,20}?)(?: is| was)?|what (?:food|colour|mob|wood|block) do i like(?: best| most)?");
    private static final Pattern RECALL_NAME = p("what(?:'s| is) my name|who am i|what do you call me|do you (?:know|remember) (?:my name|who i am)|what did i (?:tell you to|say to) call me|say my name");
    private static final Pattern RECALL_LIKES = p("what do i (?:like|love|enjoy)|what (?:do|don't) i like|what do i (?:hate|dislike|not like|can't stand)|what are my (?:likes|dislikes|favourites)");
    private static final Pattern RECALL_GROUP = p("what (?:are|were) (?:my|your|the) (rules|reminders|people|places|friends|standing orders)|(?:any|got any|do i have any|have i got any) (reminders|rules)|"
        + "(?:list|show me) (?:my |your |the )?(rules|reminders|places|people|friends)|who are my (friends)|where are my (places)|what places do you (?:know|remember)");
    private static final Pattern RECALL_PLACE = p("where(?:'s| is| are|s| was) (?:my|our) ([a-z][a-z ]{1,20})|where(?:'s| is| was|s) the ([a-z][a-z ]{1,20})|"
        + "(?:do you (?:know|remember)|remember) where (?:my|our|the) ([a-z][a-z ]{1,20}) is|which way(?: is)? (?:to )?(?:my|our|the) ([a-z][a-z ]{1,20})");
    private static final Pattern RECALL_PERSON = p("who(?:'s| is|s) ([a-z0-9_]{2,16})|is ([a-z0-9_]{2,16}) (?:my friend|a friend|a griefer|trustworthy|to be trusted|good|bad|ok|okay)|"
        + "(?:what do you think of|do you trust|do you know) ([a-z0-9_]{2,16})");
    private static final Pattern PRAISED = p("(?:you |u )?remembered(?: that| it| my [a-z ]+)?|you remember(?:ed)?(?: that| it)?|(?:wow |oh |nice |good |great )?(?:you remembered|good memory|great memory|nice memory|"
        + "what a memory|you've got a good memory|you have a good memory|you never forget|elephant memory|memory like an elephant|you remembered that|you remembered my [a-z ]+)(?: well done| nice one| mate| buddy)?");

    // forget
    private static final Pattern FORGET_LAST = p("forget (?:that|this|what i (?:just )?said|the last (?:one|thing)|i said that)|scratch that|strike that|(?:don't|do not) remember that|"
        + "unremember that|delete that(?: memory)?|undo that memory|cancel that (?:reminder|rule)|never mind that|ignore what i (?:just )?said|that's wrong forget it|forget it");
    private static final Pattern FORGET_ALL = p("forget (?:everything|it all|all of it|all that|all of that|all you know|all your memories|everything i (?:told|said to) you|everything you know)|"
        + "(?:wipe|clear|erase|reset|delete|empty) (?:your|all your|all of your) (?:memory|memories|mind|head|notes)|clear all memories|start again with a clean slate|fresh start");
    private static final Pattern FORGET_ABOUT = p("(?:cancel|forget|scrap|delete|clear|stop) (?:my |the |all |all the |all my |all of my |any )?(reminders?|rules)|(?:forget|unremember|delete|erase|drop|remove) (?:everything |anything |all |what you know |what i said |what i told you )?(?:about |of )?" + THE + "([a-z0-9_ ']{2,30})|"
        + "(?:stop|cancel|scrap|drop|forget) (?:the |my |that )?(?!my |the )([a-z ]{2,24}) (?:rule|reminder)s?|"
        + "([a-z0-9_]{2,16}) (?:isn't|is not|is no longer|ain't) (?:my friend|a friend|a griefer|bad|an enemy)(?: any ?more)?|i (?:(?:don't|do not) (?:hate|like|love|mind) " + THE + "([a-z ]{2,24}?) any ?more|no longer (?:hate|like|love|mind) " + THE + "([a-z ]{2,24}))");

    // pause
    private static final Pattern PAUSE = p("(?:stop|quit|pause|don't keep|no more) (?:remembering|taking notes|making notes|memorising|memorizing|recording)(?: things| stuff| anything| what i say)?(?: for now| for a bit)?|"
        + "(?:don't|do not) remember anything(?: (?:else|for now|i say|from now on))?|pause (?:your )?memory|memory off|turn (?:your )?memory off|turn off (?:your )?memory|off the record");
    private static final Pattern RESUME = p("(?:start|carry on|go on|keep on|resume|you can (?:start )?) ?(?:remembering|taking notes|making notes)(?: (?:again|things|stuff))*|you can remember (?:things|stuff)?(?: again)?|"
        + "resume (?:your )?memory|memory on|turn (?:your )?memory (?:back )?on|turn on (?:your )?memory|back on the record|on the record");

    // name and self
    private static final Pattern MY_NAME = p("(?:my name(?:'s| is)|(?:you can |just |please )?call me|i'm called|i am called|i go by|(?:you can )?refer to me as|address me as|"
        + "people call me|everyone calls me|my friends call me|name's) (?:the )?([a-z0-9_][a-z0-9_ ]{0,20}?)(?: from now on| now| please| instead| ok| okay)?");
    private static final Pattern RENAME = p("(?:your name(?:'s| is) now|your new name(?:'s| is)|you're (?:now )?called|you are (?:now )?called|from now on you're|from now on you are|"
        + "from now on your name is|i'll call you|i will call you|i'm going to call you|i am going to call you|i'm gonna call you|call yourself|you shall be called|"
        + "i name you|i'm naming you|i am naming you|your name will be|be called) ([a-z0-9_]{2,16})(?: now| from now on| instead)?");
    private static final Pattern SELF_BAD = p("(?:you're|you are|ur|you were|you've been) (?:so |really |honestly |absolutely |totally |pretty |quite |well |such )?"
        + "(?:rubbish|bad|terrible|awful|useless|hopeless|crap|pants|naff|rotten|poor|garbage|trash|dreadful|abysmal|the worst|no good|not good|not very good|a disaster|shocking) at ([a-z ]{3,24})|"
        + "you (?:suck|stink|are bad|fail) at ([a-z ]{3,24})|you can't ([a-z ]{3,20}) (?:for toffee|to save your life|at all)|you're (?:a |an )?(?:rubbish|bad|terrible|awful|useless|hopeless|crap|lousy) ([a-z]+er|archer|cook|chef|shot)");
    private static final Pattern SELF_GOOD = p("(?:you're|you are|ur|you were|you've been) (?:so |really |honestly |absolutely |totally |pretty |quite |well |such )?"
        + "(?:good|great|brilliant|amazing|excellent|fantastic|ace|class|awesome|incredible|the best|a natural|wicked|mint|top) at ([a-z ]{3,24})|"
        + "you're (?:a |an )?(?:good|great|brilliant|amazing|excellent|ace|class|awesome|fantastic) ([a-z]+er|archer|cook|chef|shot)");

    // likes
    private static final Pattern LIKE = p("i (?:really |quite |absolutely |do |totally |kind of |kinda |sort of |just )?(?:like|love|adore|enjoy|prefer|dig|fancy|am a fan of|'m a fan of|'m into|am into|am mad about|am obsessed with|am keen on) " + THE
        + WHAT + "(?:(?:,| and)? (?:not|over|more than|rather than|instead of|better than|to) " + THE + WHAT + ")?(?: (?:best|most|the most|a lot|loads|so much))?");
    private static final Pattern DISLIKE = p("i (?:really |absolutely |just |totally |kind of |kinda |seriously )?(?:hate|dislike|detest|loathe|despise|can't stand|cannot stand|can't bear|am scared of|'m scared of|"
        + "am afraid of|'m afraid of|fear|don't like|do not like|don't love|am not a fan of|'m not a fan of|am not keen on|'m not keen on|can't be doing with|really don't like) " + THE + WHAT + "(?: (?:at all|so much|a lot|the most|any more))?");
    private static final Pattern USE_NOT = p("(?:use|chop|cut|get|gather|pick|build with|make things with|always use) " + THE + "([a-z ]{2,14}?)(?: wood| logs| planks| ones)?,? (?:not|never|rather than|instead of) " + THE + "([a-z ]{2,14})(?: wood| logs| planks| ones)?");
    private static final Pattern FAV_IS = p("my (?:favourite|best|preferred|top) ([a-z ]{2,20}?)(?: is| are| has to be| would be|'s| was) " + THE + "([a-z0-9][a-z0-9' -]{0,30})");
    private static final Pattern IS_FAV = p(THE + "([a-z][a-z' -]{0,30}?) (?:is|are) my (?:favourite|fav|best|top)(?: ([a-z ]{2,20}))?");

    // people
    private static final Pattern FRIEND = p("([a-z0-9_]{2,16})(?: is|'s| s) (?:my |a |an |our )?(?:good |best |old |close |real |true )?(?:friend|mate|pal|buddy|bestie|bff|bro|brother|sister|sis|teammate|team mate|partner|ally|cousin|neighbour|"
        + "girlfriend|boyfriend|wife|husband|missus|other half)(?: of mine)?(?:(?:,| and| so)? (?:please )?(help|look after|protect|defend|watch out for|take care of|listen to|obey|do what|take orders from) (?:him|her|them|out|[a-z0-9_]+)(?: too| as well| for me| says)?)?|"
        + "([a-z0-9_]{2,16}) (?:is|'s) on (?:my|our) (?:team|side)|(?:please )?(?:help|look after|protect|defend|watch out for|take care of) ([a-z0-9_]{2,16})(?: for me| too| as well)?|"
        + "([a-z0-9_]{2,16}) can (?:give you orders|boss you about|boss you around|tell you what to do|order you about|use you)");
    private static final Pattern FOE = p("([a-z0-9_]{2,16})(?: is|'s| s) (?:a |an |my |such a |such an |the )?(?:total |massive |known |real |big )?(?:griefer|thief|enemy|troll|hacker|liar|cheat|cheater|scammer|bad guy|bad person|baddie|menace|nuisance|rat|snake|traitor|"
        + "rival|nemesis|arch ?enemy|bully)(?:,? (?:so )?(?:don't|do not|never) trust (?:him|her|them))?|(?:don't|do not|never) (?:trust|listen to|obey|help|believe) ([a-z0-9_]{2,16})(?: ever| at all| again)?|"
        + "(?:watch out for|keep an eye on|be careful (?:of|with|around)|beware of|look out for) ([a-z0-9_]{2,16})|([a-z0-9_]{2,16}) (?:is not|isn't|is never) to be trusted|"
        + "([a-z0-9_]{2,16}) (?:stole|nicked|griefed|broke|burned|burnt|wrecked|took) (?:my|our|from me)[a-z ]*|i (?:don't|do not) trust ([a-z0-9_]{2,16})");
    private static final Pattern ONLY_ME = p("(?:only|just) (?:take|listen to|obey|follow|accept) (?:orders |commands |instructions )?(?:from )?me|(?:only|just) listen to me|"
        + "(?:don't|do not|never) (?:take|accept|listen to) (?:orders|commands|instructions) from (?:anyone|anybody|other people|others)(?: (?:else|but me|except me))?|"
        + "(?:ignore|don't listen to) (?:everyone|everybody|anyone|other people|others)(?: else| but me| except me)?|i'm the only one (?:you take orders from|who gives you orders)|nobody else gives you orders|you only work for me");
    private static final Pattern CLEAN = p("(?:don't|do not|no|never) (?:swear|swearing|curse|cursing|use bad language|use rude words) (?:around|near|in front of|when|while) ([a-z0-9_]{2,16})(?: is (?:around|here|about|near))?|"
        + "(?:keep it clean|mind your language|watch your language|watch your mouth|be polite|no rude words) (?:around|near|in front of|when|with) ([a-z0-9_]{2,16})(?: is (?:around|here|about))?");

    // things not to touch, things to keep
    private static final Pattern SPARE = p("(?:please )?(?:don't|do not|never|never ever) (?:break|touch|chop|cut|cut down|mine|dig|dig up|trample|pick|harvest|step on|mess with|take|destroy|knock down|remove|hurt|kill|"
        + "go near|go in|go into|open|loot|raid|rob|steal from|use|wreck|damage) " + THE + "([a-z][a-z ]{1,30}?)(?: ever| at all| please| again| for me)?|"
        + "(?:leave|keep away from|stay away from|stay out of|keep out of|keep your hands off|hands off) " + THE + "([a-z][a-z ]{1,30}?)(?: alone)?(?: please)?|"
        + THE + "([a-z][a-z ]{1,30}?) (?:are|is) off limits");
    private static final Pattern STOCK = p("(?:always |please always )?(?:keep|carry|have|hold|make sure you (?:have|keep|carry)) (?:at least |a minimum of |no fewer than |a stack of |about )?(\\d{1,3}|a stack of) "
        + "([a-z][a-z ]{1,24}?)(?: on you| with you| in your pack| in your bag| handy| spare| at all times| around| in stock| always)*|never run out of ([a-z][a-z ]{1,24})|"
        + "always (?:keep|carry|have) (?:some |spare )?([a-z][a-z ]{1,24}?)(?: on you| with you| handy| spare)?");

    // places
    private static final Pattern PLACE_HERE = p("(?:this|here|this spot|this place|right here)(?: is|'s| will be|s) (?:my|our|the|a|an) ([a-z][a-z ]{1,20})|"
        + "(?:my|our|the) ([a-z][a-z ]{1,20}) (?:is|'s|s) (?:right )?here|welcome to (?:my|our|the) ([a-z][a-z ]{1,20})|this is (?:home|where i live)|"
        + "(?:here|this) is where (?:i|we) (?:live|sleep|keep my stuff|keep our stuff|keep things|store things)|remember (?:this|here) as (?:my|our|the) ([a-z][a-z ]{1,20})|"
        + "(?:this spot|here|this place) is (?:my|our|the) ([a-z][a-z ]{1,20})|(?:make|set|mark) (?:this|here) (?:as )?(?:my|our|the) ([a-z][a-z ]{1,20})|"
        + "(?:remember|save|mark|call|name|set) (?:this|that|here|this spot|that spot|this place|that place)(?: spot| place)? as (?:the |my |our |a )?([a-z][a-z ]{0,20})");
    private static final Pattern PLACE_THERE = p("(?:my|our|the) ([a-z][a-z ]{1,20}) (?:is|'s|s) (?:over there|there|that way|over that way|down there|up there|that one|just there|out there)|"
        + "(?:over )?there(?:'s| is) (?:my|our|the) ([a-z][a-z ]{1,20})|that(?:'s| is|s) (?:my|our|the) ([a-z][a-z ]{1,20})(?: over there)?|that (?:place|spot|building|house|one) is (?:my|our|the) ([a-z][a-z ]{1,20})");

    // rules: a trigger and an order, either way round
    private static final String[][] TRIGGERS = {
        {"MORNING", "every morning|each morning|in the morning|in the mornings|of a morning|at dawn|at sunrise|when the sun comes up|when the sun rises|when it's morning|when it gets light|first thing|when i wake up|when we wake up|every day|each day|daily"},
        {"NIGHT", "when it's night|when it is night|at night|every night|each night|at nightfall|when night falls|when it gets dark|when it's dark|at dusk|when the sun goes down|when the sun sets|after dark|at sunset|in the evening|every evening|when night comes"},
        {"DEATH", "if i die|when i die|whenever i die|if i get killed|when i get killed|if i'm killed|if i ever die|every time i die|if i respawn|when i respawn"},
        {"LOW", "whenever i'm low|when i'm low|if i'm low|when i am low|if i am low|whenever i'm low on health|when i'm low on health|if i'm low on health|when my health is low|if my health is low|whenever my health is low|"
            + "when i'm hurt|if i'm hurt|whenever i'm hurt|when i'm hungry|if i'm hungry|whenever i'm hungry|when i'm starving|if i'm starving|when i'm low on food|if i'm low on food|when i'm dying|if i'm dying|when i need food|whenever i need food"},
        {"HOME", "when i get home|when i arrive home|when i come home|whenever i come home|whenever i get home|when i'm home|when i'm back home|when i get back|when we get home|when i'm back|as soon as i'm home|when i reach home|when i'm back at base|when i get back to base"},
        {"RAIN", "when it rains|if it rains|whenever it rains|when it starts raining|when it's raining|if it starts to rain"},
        {"IDLE", "whenever you're free|when you're free|when you've nothing to do|when you have nothing to do|when you're bored|if you're bored|when you're idle|in your spare time|when you get a chance|whenever you can"},
    };
    private static final Pattern NEAR_TRIGGER = Pattern.compile("^(?:when|whenever|if) ([a-z0-9_]{2,16}) (?:is (?:around|about|here|near|nearby|online)|comes(?: round| over| by| near)?|shows up|turns up|appears|gets close|logs on|joins)$");

    // reminders
    private static final Pattern REMIND = Pattern.compile("^(?:can you |could you |please |will you |would you )?(?:remind me|give me a (?:nudge|shout|reminder)|set (?:a|me a) reminder|nudge me|"
        + "(?:don't|do not) let me forget|make sure i (?:remember|don't forget))(?: (?:to|that|about|of|for)\\b)?(.*)$");
    private static final Pattern IN_TIME = Pattern.compile("\\b(?:in|after|for) (?:about |around |roughly |like )?(an?|\\d{1,4}|a couple of|a few|half an?|couple of|few)(?: and a half)? (seconds?|minutes?|hours?|game days?|days?|ticks?)(?: from now| time)?\\b");
    private static final Pattern WHEN_WORDS = Pattern.compile("\\b(?:at dawn|in the morning|tomorrow morning|tomorrow|first thing|at sunrise|when the sun comes up|at night|tonight|at nightfall|at dusk|this evening|when it gets dark|"
        + "when i get home|when i'm home|when i get back|when i arrive home|when i come home|when i'm back home|when i'm back)\\b");

    // general facts
    private static final Pattern REMEMBER_THAT = p("(?:please )?(?:remember|don't forget|do not forget|keep in mind|bear in mind|make a note|note down|write down|memorise|memorize|note)(?: that| this:| this| :)? (.{3,120})");
    private static final Pattern MY_X_IS = p("my ([a-z]{3,16}(?: [a-z]{3,16})?)(?:'s| is| are) (?:called |named )([a-z0-9_ ]{2,24})|my ([a-z]{3,16})'s name is ([a-z0-9_ ]{2,24})");

    // ------------------------------------------------------------------ reading

    /** Read a sentence for memory. NONE when it isn't about memory at all. */
    public static Heard read(String raw, String selfName) {
        if (raw == null || raw.isBlank()) return Heard.none();
        String t = norm(raw, selfName);
        if (t.isEmpty() || t.startsWith("#")) return Heard.none();
        Matcher m;

        // asking back first: questions are never stored
        if (RECALL_NAME.matcher(t).matches()) return Heard.of(Op.RECALL, "name");
        if ((m = RECALL_FAV.matcher(t)).matches()) return Heard.of(Op.RECALL, "favourite " + (m.group(1) == null ? fromLikeQuestion(t) : m.group(1).trim()));
        if (RECALL_LIKES.matcher(t).matches()) return Heard.of(Op.RECALL, t.matches(".*(hate|dislike|not like|can't stand|don't like).*") ? "dislikes" : "likes");
        if ((m = RECALL_GROUP.matcher(t)).matches()) {
            String g = first(m);
            return Heard.of(Op.RECALL, g.equals("friends") ? "people" : g.equals("standing orders") ? "rules" : g);
        }
        if ((m = RECALL_ABOUT.matcher(t)).matches()) return Heard.of(Op.RECALL_ALL, m.group(1).trim());
        if (RECALL_ALL.matcher(t).matches()) return Heard.of(Op.RECALL_ALL, "");
        if ((m = RECALL_PLACE.matcher(t)).matches()) {
            String place = first(m);
            if (!place.matches("^(?:nearest|closest)\\b.*") && !place.matches("(?:your bed|you|bed)")) return Heard.of(Op.RECALL_PLACE, clean(place));
        }
        if ((m = RECALL_PERSON.matcher(t)).matches()) {
            String who = first(m);
            if (name(who)) return Heard.of(Op.RECALL_PERSON, asSaid(raw, who));
        }
        if (PRAISED.matcher(t).matches()) return Heard.of(Op.PRAISED, "");

        // forgetting and pausing
        if (FORGET_ALL.matcher(t).matches()) return Heard.of(Op.FORGET_ALL, "");
        if (FORGET_LAST.matcher(t).matches()) return Heard.of(Op.FORGET_LAST, t.equals("forget it") ? "it" : "");
        if (PAUSE.matcher(t).matches()) return Heard.of(Op.PAUSE, "");
        if (RESUME.matcher(t).matches()) return Heard.of(Op.RESUME, "");
        if ((m = FORGET_ABOUT.matcher(t)).matches()) {
            String what = clean(first(m));
            if (!what.isEmpty() && !what.matches("(?:it|that|this|about it|ping|pings|the ping|my ping|last ping)")) {
                if (what.matches("reminders?")) what = "reminders";
                else if (what.matches("rules")) what = "rules";
                what = what.replaceAll(" (?:rules?|reminders?)$", "").trim();
                boolean capital = !asSaid(raw, what).equals(PaMemory.cap(what)) || raw.matches(".*\\b" + Pattern.quote(PaMemory.cap(what)) + "\\b.*");
                return Heard.of(Op.FORGET, what.matches("[a-z0-9_]{2,16}") && name(what) && capital ? asSaid(raw, what) : what);
            }
        }

        // from here on it would be kept: never a secret
        boolean storing = t.matches(".*\\b(?:my|remember|i live|call me|keep|note|i'm|i am)\\b.*");
        if (storing && secret(raw)) return Heard.of(Op.PRIVATE, "");

        // its own name, and what's said of it
        if ((m = RENAME.matcher(t)).matches()) return Heard.of(Op.RENAME, PaNames.asSaid(raw, t, m.start(1), m.end(1), 16, m.group(1)));   // [pa36b] the name as said
        if ((m = SELF_BAD.matcher(t)).matches()) return selfOpinion(first(m), -1, t);
        if ((m = SELF_GOOD.matcher(t)).matches()) return selfOpinion(first(m), 1, t);

        // the player's name
        if ((m = MY_NAME.matcher(t)).matches()) {
            String n = m.group(1).trim();
            if (!n.matches("(?:back|later|when|if|over|up|on|out|a taxi|an ambulance).*") && n.split(" ").length <= 3 && !secret(raw))
                return Heard.save(new Entry(Kind.NAME, asSaid(raw, n), ""));
        }

        // reminders ("in 10 minutes remind me to ...": the time goes to the end)
        Matcher lead = Pattern.compile("^((?:in|after) [a-z0-9 ]+? (?:seconds?|minutes?|hours?|days?)|at dawn|tonight|tomorrow(?: morning)?|in the morning|when i get home),? (remind me.*|don't let me forget.*)$").matcher(t);
        if (lead.find()) t = lead.group(2) + " " + lead.group(1);
        if ((m = REMIND.matcher(t)).matches()) return reminder(m.group(1).trim(), t);

        // rules: "every morning, refill the furnace", "come home when it's night", "don't swear around sam"
        if ((m = CLEAN.matcher(t)).matches()) {
            String who = first(m);
            if (name(who)) return Heard.save(new Entry(Kind.CLEAN, asSaid(raw, who), "keep it clean").when(When.NEAR).extra(asSaid(raw, who)));
        }
        Heard rule = rule(t, raw);
        if (rule != null) return rule;

        // people
        if ((m = ONLY_ME.matcher(t)).matches()) return Heard.save(new Entry(Kind.ONLY_ME, "only me", ""));
        if ((m = FOE.matcher(t)).matches()) {
            String who = first(m);
            if (name(who)) return Heard.save(new Entry(Kind.FOE, asSaid(raw, who), ""));
        }
        if ((m = FRIEND.matcher(t)).matches()) {
            String who = m.group(1) != null ? m.group(1) : m.group(3) != null ? m.group(3) : m.group(4) != null ? m.group(4) : m.group(5);
            if (name(who)) {
                String verb = m.group(2) == null ? "" : m.group(2);
                String extra = m.group(4) != null || verb.matches("help|look after|protect|defend|watch out for|take care of") ? "help" : "";
                if (m.group(5) != null || verb.matches("listen to|obey|do what|take orders from")) extra = extra.isEmpty() ? "help,orders" : extra + ",orders";
                return Heard.save(new Entry(Kind.FRIEND, asSaid(raw, who), "").extra(extra));
            }
        }

        // places
        if ((m = PLACE_HERE.matcher(t)).matches()) {
            String place = first(m);
            if (place.isEmpty()) place = "home";
            place = clean(place);
            if (!place.isEmpty() && !place.matches("(?:bed|your bed|spawn|ping)")) return new Heard(Op.PLACE, null, null, place, false, "", List.of());
        }
        if ((m = PLACE_THERE.matcher(t)).matches()) {
            String place = clean(first(m));
            if (!place.isEmpty() && !place.matches("(?:bed|your bed|spawn|ping|one|it)")) return new Heard(Op.PLACE, null, null, place, true, "", List.of());
        }

        // favourites, likes and dislikes
        String ti = t.replaceAll("\\bi'm\\b", "i am").replaceAll("^me (favourite|best) ", "my $1 ");
        if ((m = FAV_IS.matcher(ti)).matches()) {
            String cat = clean(m.group(1)), what = clean(m.group(2));
            if (!what.isEmpty() && !secret(raw)) return Heard.save(new Entry(Kind.FAV, what, cat.replaceAll("^(?:kind of|type of|sort of) ", "")));
        }
        if ((m = IS_FAV.matcher(t)).matches() && !m.group(1).matches("(?:this|that|it|he|she|you|which|what).*")) {
            String what = clean(m.group(1));
            String cat = m.group(2) == null ? category(what) : clean(m.group(2));
            if (cat.equals("thing")) cat = "thing";
            return Heard.save(new Entry(Kind.FAV, what, cat));
        }
        if ((m = USE_NOT.matcher(t)).matches()) {
            String a = clean(m.group(1)), b = clean(m.group(2));
            if (WOODS.contains(a) || WOODS.contains(b))
                return Heard.save(new Entry(Kind.LIKE, a, "").extra("wood;over:" + b), new Entry(Kind.DISLIKE, b, "").extra("wood"));
        }
        if ((m = DISLIKE.matcher(ti)).matches()) {
            String what = clean(m.group(1));
            if (what.isEmpty() || what.matches("(?:it|that|this|you|when you .*|when .*|being .*)")) return Heard.none();
            if (what.matches("[a-z0-9_]{2,16}") && name(what) && category(what).equals("thing") && raw.matches(".*\\b" + Pattern.quote(asSaid(raw, what)) + "\\b.*")
                    && Character.isUpperCase(asSaid(raw, what).charAt(0)) && !raw.equals(raw.toLowerCase(Locale.ROOT)))
                return Heard.ask("Shall I keep an eye on " + asSaid(raw, what) + "? Someone not to trust?",
                    List.of(new String[]{"Yes, don't trust " + asSaid(raw, what), "don't trust " + what}, new String[]{"No, it's a thing", "#memdislike " + what}));
            return Heard.save(new Entry(Kind.DISLIKE, what, "").extra(category(what)));
        }
        if ((m = LIKE.matcher(ti)).matches()) {
            String what = clean(m.group(1)), over = m.group(2) == null ? "" : clean(m.group(2));
            if (what.isEmpty() || what.matches("(?:it|that|this|you|your .*|that one|when you .*|when .*|to .*|being .*|the idea)")) return Heard.none();
            if (what.matches("[a-z0-9_]{2,16}") && name(what) && category(what).equals("thing") && !raw.equals(raw.toLowerCase(Locale.ROOT))
                    && Character.isUpperCase(asSaid(raw, what).charAt(0)))
                return Heard.ask("Is " + asSaid(raw, what) + " a friend of yours? I can help them out.",
                    List.of(new String[]{"Yes, a friend", what + " is my friend, help them"}, new String[]{"No, a thing", "#memlike " + what}));
            String cat = category(what);
            Entry like = new Entry(Kind.LIKE, what, "").extra(cat + (over.isEmpty() ? "" : ";over:" + over));
            if (!over.isEmpty()) return Heard.save(like, new Entry(Kind.DISLIKE, over, "").extra(category(over)));
            return Heard.save(like);
        }

        // things not to touch, things to keep
        if ((m = STOCK.matcher(t)).matches()) {
            String nTxt = m.group(1), item = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            item = clean(item);
            if (!item.isEmpty() && !item.matches("(?:it|that|this|going|up|calm|quiet|still|safe|an eye on .*|watch|out of .*|away from .*|clear of .*|following .*|me .*|close|near .*|your distance)")) {
                int n = nTxt == null ? 16 : nTxt.startsWith("a stack") ? 64 : Integer.parseInt(nTxt);
                return Heard.save(new Entry(Kind.STOCK, item, "").n(Math.max(1, Math.min(256, n))));
            }
        }
        if ((m = SPARE.matcher(t)).matches()) {
            String what = clean(first(m));
            if (!what.isEmpty() && !what.matches("(?:it|that|this|me|anything|that one|this one|them|him|her)")) {
                String blocks = flowerBlocks(what);
                if (blocks.isEmpty()) blocks = spareBlocks(what);
                return Heard.save(new Entry(Kind.SPARE, what, "").extra(blocks));
            }
        }

        // a name for something: "my dog's called rex"
        if ((m = MY_X_IS.matcher(t)).matches()) {
            String thing = m.group(1) != null ? m.group(1) : m.group(3), val = m.group(2) != null ? m.group(2) : m.group(4);
            return Heard.save(new Entry(Kind.FACT, "your " + clean(thing) + "'s name", clean(val)));
        }
        // "remember that ..." with nothing else it recognised: kept as said
        if ((m = REMEMBER_THAT.matcher(t)).matches()) {
            String inner = m.group(1).trim();
            Heard again = read(inner, selfName);                 // "remember that I hate creepers" is a dislike
            if (again.any() && again.op != Op.RECALL && again.op != Op.RECALL_ALL) return again;
            if (inner.matches("(?:this|that|it|here|this spot|that spot)")) return new Heard(Op.PLACE, null, null, "", false, "What shall I call this place?", List.of());
            return Heard.save(new Entry(Kind.FACT, sentenceFor(inner), ""));
        }
        return Heard.none();
    }

    /** "what food do i like" -> "food". */
    private static String fromLikeQuestion(String t) {
        Matcher m = Pattern.compile("what (food|colour|mob|wood|block) do i like").matcher(t);
        return m.find() ? m.group(1) : "thing";
    }

    /** The first group that matched. */
    static String first(Matcher m) {
        for (int i = 1; i <= m.groupCount(); i++) if (m.group(i) != null) return m.group(i).trim();
        return "";
    }

    /** Tidy a captured phrase: no leading article, no trailing filler. */
    static String clean(String s) {
        if (s == null) return "";
        String c = s.trim().replaceAll("^(?:the|my|our|a|an|some|all|any|those|these|of) ", "")
            .replaceAll(" (?:please|too|as well|a lot|loads|so much|very much|really|though|then|now|mate|buddy|ok|okay|thanks|cheers|at all|any ?more)$", "")
            .replaceAll("[,.]+$", "").trim();
        return c.replaceAll("^(?:the|my|our|a|an) ", "").trim();
    }

    /** What a "don't break ..." means in blocks: chests, crops, a wood's logs, builds, or any block whose name has the word. */
    static String spareBlocks(String what) {
        String k = PaMemory.stem(PaMemory.Entry.key(what));
        if (k.matches("chest|storage|box|barrel|stuff|thing|item")) return "#chests";
        if (k.matches("crop|wheat|carrot|potato|beetroot|farm|field|plant|garden|veg|vegetable")) return "#crops";
        if (k.matches("house|home|base|build|wall|building|roof|window|door|stuff i built|thing i built|my build")) return "#builds";
        String bare = k.replaceAll(" (?:tree|trees|log|logs|wood)$", "");
        if (WOODS.contains(bare)) return "minecraft:" + bare.replace(' ', '_') + "_log," + "minecraft:" + bare.replace(' ', '_') + "_leaves";
        if (k.matches("tree|log|wood")) return "#logs";
        if (k.matches("animal|cow|pig|sheep|chicken|horse|pet|dog|cat|wolf|villager")) return "#animals";
        return "~" + k.replace(' ', '_');
    }

    /** A remembered sentence in the second person ("I hid diamonds under the oak" -> "you hid diamonds under the oak"). */
    static String sentenceFor(String inner) {
        String s = " " + inner + " ";
        s = s.replace(" i'm ", " you're ").replace(" i am ", " you are ").replace(" i've ", " you've ").replace(" i ", " you ")
            .replace(" my ", " your ").replace(" me ", " you ").replace(" mine ", " yours ").replace(" myself ", " yourself ");
        return s.trim();
    }

    /** "you're rubbish at mining" -> SELF mining -1. */
    private static Heard selfOpinion(String topic, int sign, String t) {
        String k = clean(topic);
        k = switch (k) {
            case "miner", "digger" -> "mining";
            case "builder" -> "building";
            case "fighter", "warrior" -> "fighting";
            case "archer", "shot" -> "archery";
            case "farmer" -> "farming";
            case "cook", "chef" -> "cooking";
            case "lumberjack", "chopper" -> "chopping wood";
            case "joker", "comedian" -> "jokes";
            case "runner" -> "running";
            case "talker" -> "talking";
            case "singer" -> "singing";
            case "mine" -> "mining";
            case "build" -> "building";
            case "fight" -> "fighting";
            case "shooting", "aiming", "bows", "the bow" -> "archery";
            case "telling jokes", "joking", "jokes", "comedy" -> "jokes";
            case "chopping", "chopping trees", "cutting trees", "woodcutting", "trees" -> "chopping wood";
            default -> k;
        };
        if (k.isEmpty() || k.length() > 24) return Heard.none();
        return Heard.save(new Entry(Kind.SELF, k, "").n(sign));
    }

    /** "remind me in 10 minutes to check the furnace", "remind me at dawn to plant", "remind me to eat" (asks when). */
    private static Heard reminder(String rest, String whole) {
        String r = " " + rest + " ";
        if (r.matches("^\\s*(?:an?|\\d{1,4}|a couple of|a few|half an?) (?:seconds?|minutes?|hours?|game days?|days?)\\b.*")) r = " in" + r;
        long ticks = -1;
        When when = When.NONE;
        Matcher tm = IN_TIME.matcher(r);
        if (tm.find()) {
            String q = tm.group(1), unit = tm.group(2);
            double n = q.matches("\\d+") ? Integer.parseInt(q) : q.startsWith("half") ? 0.5 : q.contains("couple") ? 2 : q.contains("few") ? 3 : 1;
            if (whole.contains(" and a half ")) n += 0.5;
            long per = unit.startsWith("second") ? 20 : unit.startsWith("minute") ? 1200 : unit.startsWith("hour") ? 72000 : unit.startsWith("tick") ? 1 : 24000;
            ticks = Math.max(20, Math.round(n * per));
            when = When.TIME;
            r = r.substring(0, tm.start()) + " " + r.substring(tm.end());
        } else {
            Matcher wm = WHEN_WORDS.matcher(r);
            if (wm.find()) {
                String w = wm.group();
                when = w.matches(".*(dawn|morning|tomorrow|first thing|sunrise|sun comes up).*") ? When.MORNING
                    : w.matches(".*(home|get back|i'm back).*") ? When.HOME : When.NIGHT;
                r = r.substring(0, wm.start()) + " " + r.substring(wm.end());
            }
        }
        String what = r.trim().replaceAll("^(?:to|that|about|of|for|me to|me about)\\b ?", "").replaceAll("\\b(?:to|that|about)$", "").trim();
        what = what.replaceAll("^(?:to|that|about|of|for) ", "").trim();
        what = sentenceFor(what).replaceAll("^you (?:need to|have to|must|should) ", "").trim();
        if (when == When.NONE) {
            String w = what.isEmpty() ? "it" : what;
            return Heard.ask("When shall I remind you" + (what.isEmpty() ? "" : " to " + what) + "?",
                List.of(new String[]{"In 5 minutes", "remind me in 5 minutes to " + w}, new String[]{"In 20 minutes", "remind me in 20 minutes to " + w},
                    new String[]{"At dawn", "remind me at dawn to " + w}, new String[]{"When I'm home", "remind me when i get home to " + w}));
        }
        Entry e = new Entry(Kind.REMINDER, what, what).when(when);
        e.n = (int) Math.min(Integer.MAX_VALUE, ticks);
        return Heard.save(e);
    }

    /** A standing rule: a trigger and an order, either way round, or null when this isn't one. */
    private static Heard rule(String t, String raw) {
        for (String[] tr : TRIGGERS) {
            Matcher m = Pattern.compile("^(?:(?:and )?(?:" + tr[1] + ")\\b,? ?(?:then |can you |could you |please |you should |i want you to |always )?(.+)|(.+?),? (?:" + tr[1] + ")(?: please| for me| thanks)?)$").matcher(t);
            if (!m.find()) continue;
            String act = m.group(1) != null ? m.group(1) : m.group(2);
            act = act.replaceAll("^(?:always |please |then )", "").trim();
            if (act.isEmpty() || act.matches("(?:remind me.*|it|that|this)")) continue;
            When when = When.valueOf(tr[0]);
            String order = action(act);
            if (order == null)
                return Heard.ask("I'll remember that, but what should I do " + when.says + "?",
                    List.of(new String[]{"Come home", tr[0].equals("HOME") ? "give me food " + phrase(when) : "come home " + phrase(when)},
                        new String[]{"Give me food", "give me food " + phrase(when)}));
            return Heard.save(new Entry(Kind.RULE, act, order).when(when));
        }
        // "when sam comes round, warn me" / "warn me when sam is about"
        Matcher m = Pattern.compile("^(?:(when|whenever|if) ([a-z0-9_]{2,16}) ((?:is (?:around|about|here|near|nearby|online))|comes(?: round| over| by| near)?|shows up|turns up|appears|gets close|logs on|joins),? (.+)|"
            + "(.+?),? (when|whenever|if) ([a-z0-9_]{2,16}) (is (?:around|about|here|near|nearby|online)|comes(?: round| over| by| near)?|shows up|turns up|appears|gets close|logs on|joins))$").matcher(t);
        if (m.find()) {
            String who = m.group(2) != null ? m.group(2) : m.group(7), act = m.group(4) != null ? m.group(4) : m.group(5);
            if (name(who)) {
                String order = action(act);
                if (act.matches("(?:keep it clean|be polite|don't swear|no swearing|mind your language|watch your language)"))
                    return Heard.save(new Entry(Kind.CLEAN, asSaid(raw, who), "keep it clean").when(When.NEAR).extra(asSaid(raw, who)));
                if (order != null) return Heard.save(new Entry(Kind.RULE, act, order).when(When.NEAR).extra(asSaid(raw, who)));
            }
        }
        return null;
    }

    private static String phrase(When w) {
        return switch (w) {
            case MORNING -> "every morning";
            case NIGHT -> "when it gets dark";
            case DEATH -> "if i die";
            case LOW -> "when i'm low";
            case HOME -> "when i get home";
            case RAIN -> "when it rains";
            case IDLE -> "when you're free";
            default -> "";
        };
    }

    /**
     * What a rule's order means, as the words its order reader takes (or "#..." for the ones it does itself), or null
     * when it isn't something it can do on its own. "come home" is "go home" here (not "come to me").
     */
    public static String action(String act) {
        String a = act.trim().replaceAll("^(?:you |can you |could you |please |go and |always )", "").replaceAll(" (?:please|for me|thanks|again)$", "").trim();
        if (a.matches("(?:come|go|head|get|run|walk) (?:back )?home|(?:come|go|head) back(?: to (?:base|the base|home))?|(?:go|head|come) (?:back )?to (?:the |my )?base|return home|go inside|get inside|come inside")) return "go home";
        if (a.matches("(?:fetch|get|grab|collect|pick up|go and get|go get|recover|bring back|bring me|get back) (?:all )?(?:my|me my) (?:stuff|things|items|gear|loot|kit|bits)(?: back)?|(?:fetch|get|grab) it all back|recover my (?:stuff|items)")) return "#fetchdeath";
        if (a.matches("(?:give|bring|hand|pass|get|toss|throw) (?:me )?(?:some )?(?:food|something to eat|grub|a snack|snacks|dinner|lunch|my food)(?: to me)?|feed me|(?:give|bring) me (?:some )?(?:steak|bread|my favourite food)|keep me fed|look after me|heal me"))
            return "bring me food";
        if (a.matches("(?:warn|tell|alert|let) me(?: know)?|give me a shout|shout|say something|let me know")) return "#warn";
        if (a.matches("(?:keep it clean|be polite|don't swear|no swearing|mind your language|watch your language)")) return "#clean";
        if (a.matches("(?:protect|guard|defend|watch|look after) me|stay close|stick with me|stay with me|follow me")) return "guard me";
        if (a.matches("(?:put|place|light) (?:some )?torches(?: (?:down|up|around|about))?|light (?:the place|it|everything|the area|things) up|light up(?: the area| the base| everything)?")) return "light up the area";
        if (a.matches("(?:go to |get into |get in )?(?:bed|sleep)|go to sleep|sleep")) return "go to bed";
        String[] known = {"refill the furnace", "fuel the furnace", "top up the furnace", "feed the furnace", "refuel the furnace", "sort the chests", "tidy the chests",
            "harvest the crops", "harvest the farm", "tend the farm", "farm", "put everything away", "deposit your items", "stash your stuff", "empty your pack"};
        for (String k : known) if (a.equals(k)) return k;
        // anything else its order reader understands as a job
        PaUnderstand.Reading rd = PaUnderstand.read(a, "");
        if (rd.understood() && rd.steps().stream().allMatch(s -> s.kind().queued() || s.kind() == PaIntent.Kind.GUARD_ME || s.kind() == PaIntent.Kind.FOLLOW
                || s.kind() == PaIntent.Kind.STAY || s.kind() == PaIntent.Kind.SLEEP)) return a;
        return null;
    }
}
