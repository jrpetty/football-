package com.jrpetty.mcassistant.client.guide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * [guide31] How the Guide reads words, a question's and an article's alike: lower case, the punctuation gone, a few
 * phrases a player types for one idea run together ("make friends", "like me"), the little words dropped, a light
 * stemmer (shops, shopping, shop), and every word brought to the idea it stands for (villager, npc, townsfolk: folk;
 * money, cash, coins: coin). What kind of question it was (how, why, what, where, can I) is read before the little words
 * go, for the intent's say in the ranking.
 */
public final class GuideText {

    private GuideText() {}

    /** What the question asks for, read off its first words. */
    public enum Intent { HOW, WHY, WHAT, WHERE, NONE }

    /** Little words that carry nothing of the topic. */
    static final Set<String> STOP = Set.copyOf(java.util.Arrays.asList("a", "an", "the", "i", "im", "me", "my", "you", "your", "u", "ur", "to", "of", "in",
        "on", "at", "for", "is", "are", "am", "be", "been", "being", "was", "were", "do", "does", "did", "doing", "done", "can", "could",
        "how", "what", "whats", "why", "where", "wheres", "when", "who", "which", "it", "its", "this", "that", "these", "those",
        "there", "theres", "and", "or", "with", "some", "any", "about", "from", "will", "would", "should", "shall", "have", "has", "had",
        "please", "pls", "plz", "hey", "hi", "hello", "guide", "so", "best", "good", "better", "nice", "cool", "myself", "yourself", "even", "lol", "lmao", "bruh", "pls", "thx", "thanks", "just", "really", "very", "much", "many", "lot",
        "lots", "then", "than", "also", "too", "as", "by", "if", "into", "we", "our", "they", "them", "their", "theyre",
        "he", "she", "him", "her", "his", "one", "ones", "thing", "things", "stuff", "way", "ways", "anyone", "someone", "something",
        "anything", "everything", "all", "need", "needs", "want", "wanna", "gonna", "go", "going", "know", "tell", "explain", "mean",
        "means", "possible", "able", "got", "game", "minecraft", "here", "now", "yet", "still",
        "ever", "even", "only", "us", "kind", "sort", "bit", "actually", "basically", "exactly",
        "doe", "ok", "okay", "well", "um", "uh", "lol", "idk", "tho", "though", "every", "each", "other", "most"));

    /** Words of trouble: a question with one of these is likely a "why won't it" one. */
    static final Set<String> NEGATIVE = Set.copyOf(java.util.Arrays.asList("not", "no", "nobody", "none", "never", "wont", "cant", "cannot", "dont", "doesnt",
        "didnt", "isnt", "arent", "wasnt", "havent", "hasnt", "stuck", "broken", "broke", "bug", "bugged", "wrong", "problem", "issue",
        "help", "stopped", "stop", "fail", "failed", "failing", "missing", "gone", "disappeared", "nothing", "empty"));

    /** Phrases that stand for one idea, joined before the words are split (the left side as normalised). */
    static final String[][] PHRASES = {
        {"make friends", "befriend"}, {"making friends", "befriend"}, {"be friends", "befriend"}, {"become friends", "befriend"},
        {"get along", "befriend"}, {"like me", "befriend"}, {"likes me", "befriend"}, {"hate me", "standing"}, {"hates me", "standing"},
        {"think of me", "standing"}, {"thinks of me", "standing"}, {"key bind", "keybind"}, {"key binds", "keybind"},
        {"key binding", "keybind"}, {"hot key", "keybind"}, {"time lapse", "timelapse"}, {"fly through", "flythrough"},
        {"stand for election", "election"}, {"run for", "election"}, {"running for", "election"}, {"set up", "setup"},
        {"standing around", "idle"}, {"stand around", "idle"}, {"stands around", "idle"}, {"doing nothing", "idle"},
        {"do nothing", "idle"}, {"not working", "idle notwork"}, {"wont work", "idle notwork"}, {"dont work", "idle notwork"},
        {"stopped working", "idle notwork"}, {"no customers", "nocustomer"}, {"nobody buys", "nocustomer"},
        {"no one buys", "nocustomer"}, {"noone buys", "nocustomer"}, {"not selling", "nocustomer"}, {"no sales", "nocustomer"},
        {"wont buy", "nocustomer"}, {"dont buy", "nocustomer"}, {"not buying", "nocustomer"}, {"isnt growing", "nogrow"},
        {"not growing", "nogrow"}, {"wont grow", "nogrow"}, {"stopped growing", "nogrow"}, {"no babies", "nogrow"},
        {"no children", "nogrow"}, {"town hall", "townhall"}, {"village board", "villageboard"}, {"notice board", "villageboard"},
        {"jobs board", "jobsboard"}, {"job board", "jobboard"}, {"quest board", "questboard"}, {"founding kit", "foundingkit"},
        {"shop counter", "shopcounter"}, {"service counter", "servicecounter"}, {"shop sign", "shopsign"},
        {"war room", "warroom"}, {"map table", "maptable"}, {"emote wheel", "emotewheel"}, {"gui scale", "guiscale"},
        {"town books", "townbook"}, {"town s books", "townbook"}, {"city books", "townbook"}, {"the books", "townbook"},
        {"life in town", "lifepage"}, {"u page", "lifepage"}, {"u key", "lifepage"}, {"u menu", "lifepage"},
        {"press u", "lifepage"}, {"u screen", "lifepage"}, {"k key", "camerakey"}, {"press k", "camerakey"},
        {"j key", "journalkey"}, {"press j", "journalkey"}, {"day shift", "shift"}, {"iron golem", "golem"},
        {"nether portal", "nether"}, {"ender pearl", "enderpearl"}, {"own house", "myhouse"}, {"my own house", "myhouse"},
        {"a house of my own", "myhouse"}, {"house of my own", "myhouse"}, {"own home", "myhouse"}, {"my house", "myhouse"},
        {"my home", "myhouse"}, {"own town", "mytown"}, {"my own town", "mytown"}, {"own village", "mytown"},
        {"my own village", "mytown"}, {"own city", "mytown"}, {"my town", "mytown"}, {"my village", "mytown"},
        {"go faster", "faster"}, {"speed up", "faster"}, {"slow down", "slower"}, {"admin panel", "adminpanel"},
        {"server owner", "admin"}, {"server admin", "admin"}, {"village coin", "coin"}, {"village coins", "coin"},
        {"stone age", "stoneage"}, {"wood age", "woodage"}, {"iron age", "ironage"}, {"diamond age", "diamondage"},
        {"nether age", "netherage"}, {"next age", "nextage"}, {"level up", "nextage"}, {"rank up", "nextage"},
        {"look at it", "lookatit"}, {"nobody is there", "noarrive"}, {"nobody came", "noarrive"}, {"no one came", "noarrive"},
        {"nobody arrived", "noarrive"}, {"nobody is in", "noarrive"}, {"nobody in my", "noarrive"}, {"no folk in", "noarrive"}, {"do now", "whatnow"}, {"what now", "whatnow"}, {"what next", "whatnow"},
        {"which town", "whichtown"}, {"what town", "whichtown"}, {"where am i", "whichtown"}, {"does u do", "lifepage"}, {"friend of the village", "friendofvillage"}, {"friend of the town", "friendofvillage"},
        {"honoured guest", "honouredguest"}, {"honored guest", "honouredguest"}, {"who lives", "census"},
        {"other towns", "othertown"}, {"other villages", "othertown"}, {"bell ring", "alarm"}, {"bell rang", "alarm"},
        {"bell rings", "alarm"}, {"bell ringing", "alarm"}, {"coin button", "coinbutton"}, {"book button", "bookbutton"},
    };

    /** Each word to the idea it stands for (both sides as stemmed). Built from {@link #SYNONYMS}. */
    private static final Map<String, String> CANON = new HashMap<>();

    /** Groups of words that mean the same here: the first is the idea every other is brought to. */
    static final String[][] SYNONYMS = {
        {"folk", "villager", "villagers", "npc", "npcs", "townsfolk", "townspeople", "people", "person", "resident", "inhabitant",
            "settler", "dude", "guy", "guys", "villie", "local", "locals", "peasant", "folks"},
        {"befriend", "friend", "friends", "friendship", "friendly", "relationship", "relations", "affinity", "warmth", "fond",
            "bond", "rapport", "liking", "likeable"},
        {"standing", "reputation", "rep", "repute", "opinion", "popularity", "popular", "respect", "trust"},
        {"shop", "store", "storefront", "outlet", "boutique", "retail"},
        {"business", "firm", "company", "enterprise", "biz", "corporation", "startup", "venture"},
        {"coin", "coins", "money", "cash", "currency", "dosh", "funds", "income", "wealth", "rich", "moolah"},
        {"town", "village", "city", "settlement", "hamlet", "towns", "villages", "cities"},
        {"house", "home", "flat", "apartment", "dwelling", "cottage", "residence", "housing"},
        {"leader", "mayor", "chief", "ruler", "king", "president", "governor", "lord", "headman"},
        {"election", "elect", "elected", "vote", "votes", "voting", "campaign", "ballot", "poll", "hustings"},
        {"tax", "taxes", "taxation", "levy", "tithe"},
        {"war", "battle", "warfare", "invade", "invasion", "conquer", "siege", "fight", "fighting", "combat", "enemy", "enemies"},
        {"job", "employment", "career", "occupation", "profession", "employ", "employed", "work"},
        {"camera", "film", "filming", "video", "cinematic", "cinema", "footage", "recording", "cam", "movie"},
        {"keybind", "hotkey", "keybinding", "shortcut", "shortcuts", "controls", "control", "keys", "keyboard", "bind", "binding"},
        {"idle", "lazy", "afk", "inactive", "slacking", "slack", "loafing"},
        {"customer", "customers", "buyer", "buyers", "shopper", "shoppers", "client", "clients"},
        {"grow", "growth", "expand", "expansion", "bigger", "population", "populate", "larger"},
        {"child", "children", "kid", "kids", "baby", "babies", "offspring", "toddler"},
        {"marry", "marriage", "wedding", "wed", "spouse", "husband", "wife", "romance"},
        {"gift", "present", "presents", "gifts", "gifting"},
        {"talk", "speak", "chat", "converse", "conversation", "dialogue", "dialog", "chatting", "talking"},
        {"quest", "quests", "mission", "missions"},
        {"festival", "feast", "party", "celebration", "celebrate", "holiday", "fete"},
        {"admin", "operator", "op", "ops", "moderator"},
        {"defend", "defence", "defense", "protect", "protection", "safety", "secure", "security"},
        {"bank", "banking", "banker"},
        {"loan", "borrow", "mortgage", "credit", "debt", "lend"},
        {"farm", "farmer", "farming", "farms", "crop", "crops", "harvest", "field", "fields"},
        {"mine", "miner", "mining", "mines", "dig", "digging", "quarry", "ore"},
        {"build", "builder", "builders", "building", "construct", "construction", "erect"},
        {"buy", "purchase", "buying", "bought", "purchasing", "afford"},
        {"sell", "selling", "sold", "sale", "sales", "vend"},
        {"price", "prices", "cost", "costs", "pricing", "expensive", "cheap"},
        {"found", "founding", "founded", "establish", "settle"},
        {"guard", "guards", "watchman", "constable", "police", "soldier", "soldiers"},
        {"monster", "monsters", "mob", "mobs", "zombie", "zombies", "skeleton", "creeper", "hostile"},
        {"food", "eat", "eating", "hungry", "hunger", "starving", "starve", "meal", "meals"},
        {"happy", "mood", "moods", "happiness", "sad", "unhappy", "content", "contentment", "morale", "spirits", "feeling", "feelings"},
        {"trading", "barter", "exchange"},
        {"storehouse", "storage", "stash", "warehouse", "stockpile"},
        {"age", "ages", "era", "eras", "epoch", "progression", "progress", "tier", "tiers"},
        {"open", "opening", "access", "launch"},
        {"pet", "pets", "dog", "dogs", "cat", "cats", "puppy", "kitten"},
        {"clothes", "clothing", "outfit", "outfits", "fashion", "garment", "garments", "wear", "dress"},
        {"crime", "steal", "stealing", "theft", "thief", "rob", "robbery", "crimes", "criminal"},
        {"settings", "setting", "config", "configuration", "options", "option", "toml", "gamerule"},
        {"faster", "speedup", "accelerate", "quicker", "fastforward"},
        {"rent", "renting", "lease"},
        {"bug", "bugs", "glitch", "glitched", "broken", "crash", "lag", "laggy"},
        {"raid", "raids", "pillager", "pillagers", "raider", "raiders", "attack", "attacked", "attacking"},
        {"wall", "walls", "fortify", "fortification", "fort", "gate", "gates"},
        {"research", "invent", "invention", "inventions", "technology", "tech", "science", "discover"},
    };

    static {
        for (String[] g : SYNONYMS) {
            String to = stem(clean(g[0]));
            for (String w : g) {
                CANON.put(stem(clean(w)), to);
                CANON.put(clean(w), to);
            }
        }
    }

    private static String clean(String w) {
        return w.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Lower case, apostrophes run in (won't: wont), anything else not a letter or a digit a space, single-spaced. */
    public static String normalise(String text) {
        if (text == null) return "";
        String t = text.toLowerCase(Locale.ROOT).replace("’", "").replace("'", "").replace("`", "");
        StringBuilder sb = new StringBuilder(t.length() + 2);
        boolean space = true;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (keep) {
                sb.append(c);
                space = false;
            } else if (!space) {
                sb.append(' ');
                space = true;
            }
        }
        return sb.toString().trim();
    }

    /** Text-speak to the word it stands for, word by word, before anything else is read. */
    static final java.util.Map<String, String> SLANG = java.util.Map.ofEntries(
        java.util.Map.entry("wat", "what"), java.util.Map.entry("wot", "what"), java.util.Map.entry("wut", "what"),
        java.util.Map.entry("y", "why"), java.util.Map.entry("2", "to"), java.util.Map.entry("4", "for"),
        java.util.Map.entry("ppl", "people"), java.util.Map.entry("vills", "villagers"), java.util.Map.entry("villies", "villagers"),
        java.util.Map.entry("coinz", "coins"), java.util.Map.entry("monies", "money"), java.util.Map.entry("bc", "because"),
        java.util.Map.entry("cuz", "because"), java.util.Map.entry("hav", "have"), java.util.Map.entry("gud", "good"),
        java.util.Map.entry("tho", "though"), java.util.Map.entry("da", "the"), java.util.Map.entry("dat", "that"),
        java.util.Map.entry("dis", "this"), java.util.Map.entry("wanna", "want"), java.util.Map.entry("gotta", "must"),
        java.util.Map.entry("becum", "become"), java.util.Map.entry("becom", "become"), java.util.Map.entry("plz", "please"));

    /** The phrases run together, on a normalised line. */
    static String phrases(String norm) {
        StringBuilder sb = new StringBuilder();
        for (String w : norm.split(" ")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(SLANG.getOrDefault(w, w));
        }
        norm = sb.toString();
        // "how does the camera work": the work is only the question's, not a job
        if ((norm.startsWith("how does ") || norm.startsWith("how do ")) && norm.endsWith(" work") && !norm.contains(" folk ")) {
            norm = norm.substring(0, norm.length() - 5);
        }
        String s = " " + norm + " ";
        for (String[] p : PHRASES) {
            if (s.contains(" " + p[0] + " ")) s = s.replace(" " + p[0] + " ", " " + p[1] + " ");
        }
        return s.trim();
    }

    private static String phrasesOnlySlang(String norm) {
        StringBuilder sb = new StringBuilder();
        for (String w : norm.split(" ")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(SLANG.getOrDefault(w, w));
        }
        return sb.toString();
    }

    /** What kind of question this is, from its words (before they are stemmed or dropped). */
    public static Intent intent(String text) {
        String n = " " + phrasesOnlySlang(normalise(text)) + " ";
        for (String neg : NEGATIVE) if (n.contains(" " + neg + " ")) return Intent.WHY;
        if (n.startsWith(" why ") || n.contains(" why ")) return Intent.WHY;
        if (n.startsWith(" how ") || n.contains(" how do ") || n.contains(" how to ") || n.contains(" how can ")
            || n.startsWith(" can i ") || n.contains(" can i ") || n.startsWith(" is there a way")) return Intent.HOW;
        if (n.startsWith(" where ") || n.contains(" where ")) return Intent.WHERE;
        if (n.startsWith(" what ") || n.startsWith(" whats ") || n.contains(" what is ") || n.contains(" what does ")
            || n.contains(" what are ") || n.startsWith(" who ")) return Intent.WHAT;
        return Intent.NONE;
    }

    /** The words that carry the topic: phrases joined, little words dropped, each stemmed and brought to its idea. */
    public static List<String> terms(String text) {
        List<String> out = new ArrayList<>();
        for (String w : phrases(normalise(text)).split(" ")) {
            if (w.isEmpty() || STOP.contains(w)) continue;
            for (String part : w.split(" ")) {
                String t = term(part);
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    /** One word, stemmed and brought to its idea; "" for a little word. */
    public static String term(String w) {
        if (w.isEmpty() || STOP.contains(w)) return "";
        String c = CANON.get(w);
        if (c != null) return c;
        String s = stem(w);
        c = CANON.get(s);
        return c != null ? c : s;
    }

    /** The idea a stemmed word stands for, or the word. */
    static String canon(String stemmed) {
        String c = CANON.get(stemmed);
        return c != null ? c : stemmed;
    }

    private static boolean vowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y';
    }

    private static boolean hasVowel(String s) {
        for (int i = 0; i < s.length(); i++) if (vowel(s.charAt(i))) return true;
        return false;
    }

    /** A light stemmer: plurals, -ing and -ed off (a doubled end undone: shopping, shop), a final e off. */
    public static String stem(String w) {
        if (w.length() <= 3 || !w.chars().allMatch(Character::isLetter)) return w;
        String s = w;
        if (s.endsWith("ies") && s.length() > 4) s = s.substring(0, s.length() - 3) + "y";
        else if (s.endsWith("ing") && s.length() > 5 && hasVowel(s.substring(0, s.length() - 3))) s = undouble(s.substring(0, s.length() - 3));
        else if (s.endsWith("ed") && s.length() > 4 && !s.endsWith("eed") && hasVowel(s.substring(0, s.length() - 2))) s = undouble(s.substring(0, s.length() - 2));
        else if (s.endsWith("es") && s.length() > 4 && (s.endsWith("sses") || s.endsWith("xes") || s.endsWith("ches") || s.endsWith("shes") || s.endsWith("zes"))) s = s.substring(0, s.length() - 2);
        else if (s.endsWith("s") && s.length() > 3 && !s.endsWith("ss") && !s.endsWith("us") && !s.endsWith("is")) s = s.substring(0, s.length() - 1);
        if (s.length() >= 4 && s.endsWith("e")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String undouble(String s) {
        int n = s.length();
        if (n >= 3 && s.charAt(n - 1) == s.charAt(n - 2) && "bdgmnprt".indexOf(s.charAt(n - 1)) >= 0) return s.substring(0, n - 1);
        return s;
    }

    /** The edit distance between two words, a swap of neighbours counting one (buisness, business). */
    public static int distance(String a, String b, int most) {
        int n = a.length(), m = b.length();
        if (Math.abs(n - m) > most) return most + 1;
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            int rowMin = Integer.MAX_VALUE;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) v = Math.min(v, d[i - 2][j - 2] + 1);
                d[i][j] = v;
                rowMin = Math.min(rowMin, v);
            }
            if (rowMin > most) return most + 1;
        }
        return d[n][m];
    }
}
