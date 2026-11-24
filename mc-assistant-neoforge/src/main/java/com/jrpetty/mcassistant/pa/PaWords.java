package com.jrpetty.mcassistant.pa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * [pa33] The Player AI Assistant's ear for words: what a player types or says, made plain before it is read.
 * Lower case, no punctuation, slang opened out ("gimme" is "give me"), number words made digits ("twenty" is
 * "20"), and a misspelt word put right against the words the assistant knows ("pickax", "chiken", "iorn").
 * Pure Java: no game classes, so the whole of it is unit-tested.
 */
public final class PaWords {

    private PaWords() {}

    /** Slang and shorthand, opened out. Whole words only. */
    private static final Map<String, String> SLANG = new HashMap<>();
    static {
        String[][] s = {
            {"gimme", "give me"}, {"lemme", "let me"}, {"wanna", "want to"}, {"gonna", "going to"}, {"gotta", "have to"},
            {"plz", ""}, {"pls", ""}, {"please", ""}, {"pleas", ""}, {"kindly", ""}, {"u", "you"}, {"ur", "your"},
            {"r", "are"}, {"thx", "thanks"}, {"ty", "thanks"}, {"tysm", "thanks"}, {"cheers", "thanks"},
            {"cya", "bye"}, {"brb", "bye"}, {"k", "ok"}, {"kk", "ok"}, {"okay", "ok"}, {"okey", "ok"}, {"yep", "yes"},
            {"yeah", "yes"}, {"yea", "yes"}, {"ya", "yes"}, {"sure", "yes"}, {"nope", "no"}, {"nah", "no"},
            {"im", "i am"}, {"dont", "do not"}, {"don't", "do not"}, {"cant", "can not"}, {"can't", "can not"},
            {"whats", "what is"}, {"what's", "what is"}, {"wheres", "where is"}, {"where's", "where is"},
            {"hows", "how is"}, {"how's", "how is"}, {"it's", "it is"}, {"lets", "let us"}, {"let's", "let us"},
            {"i'm", "i am"}, {"you're", "you are"}, {"youre", "you are"}, {"y'all", "you"}, {"tryna", "trying to"},
            {"bc", "because"}, {"cuz", "because"}, {"rn", "now"}, {"asap", "now"}, {"pronto", "now"},
            {"tho", "though"}, {"w", "with"}, {"n", "and"}, {"&", "and"}, {"+", "and"}, {"2nite", "tonight"},
            {"tmrw", "tomorrow"}, {"pic", "pickaxe"}, {"pick", "pickaxe"}, {"pickax", "pickaxe"},
            {"pickaxes", "pickaxe"}, {"picaxe", "pickaxe"}, {"pikaxe", "pickaxe"}, {"axes", "axe"},
            {"wood", "logs"}, {"timber", "logs"}, {"lumber", "logs"}, {"log", "logs"}, {"trees", "logs"}, {"tree", "logs"},
            {"cobble", "cobblestone"}, {"rock", "stone"}, {"rocks", "stone"}, {"stones", "stone"},
            {"diamonds", "diamond"}, {"dias", "diamond"}, {"dia", "diamond"}, {"irons", "iron"},
            {"golds", "gold"}, {"coals", "coal"}, {"torchs", "torches"}, {"torch", "torches"},
            {"hostiles", "monsters"}, {"mobs", "monsters"}, {"mob", "monster"}, {"baddies", "monsters"},
            {"zombies", "zombie"}, {"skeletons", "skeleton"}, {"spiders", "spider"}, {"creepers", "creeper"},
            {"chests", "chest"}, {"crate", "chest"}, {"box", "chest"}, {"stash", "deposit"},
            {"grub", "food"}, {"snacks", "food"}, {"snack", "food"}, {"eats", "food"}, {"meal", "food"},
            {"hi", "hello"}, {"hey", "hello"}, {"heya", "hello"}, {"hiya", "hello"}, {"howdy", "hello"},
            {"sup", "hello"}, {"wassup", "hello"}, {"yo", "hello"}, {"greetings", "hello"},
            {"tp", "teleport"}, {"cmon", "come on"}, {"c'mon", "come on"}, {"inv", "inventory"}, {"stuff", "items"}, {"loot", "items"},
        };
        for (String[] p : s) SLANG.put(p[0], p[1]);
    }

    /** Small number words. */
    private static final Map<String, Integer> NUM = new HashMap<>();
    static {
        String[] small = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
        for (int i = 0; i < small.length; i++) NUM.put(small[i], i);
        String[] tens = {"twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
        for (int i = 0; i < tens.length; i++) NUM.put(tens[i], 20 + i * 10);
        NUM.put("fourty", 40);
    }

    /** Every word the assistant reads orders by: the dictionary misspellings are put right against. */
    public static final Set<String> VOCAB = new LinkedHashSet<>(List.of(
        // verbs
        "follow", "stay", "come", "guard", "protect", "defend", "gather", "collect", "chop", "mine", "dig", "farm",
        "harvest", "plant", "build", "construct", "craft", "make", "smelt", "cook", "deposit", "store", "unload",
        "fetch", "bring", "give", "hand", "fight", "attack", "kill", "hunt", "fish", "breed", "shear", "sort",
        "organise", "organize", "light", "stop", "cancel", "undo", "pause", "resume", "continue", "wait", "hold",
        "return", "home", "teleport", "sleep", "rest", "help", "explore", "repair", "clear", "flatten", "tidy",
        "trade", "sell", "buy", "fuel", "refuel", "put", "take", "grab", "withdraw", "find", "look", "eat",
        "rename", "call", "remember", "keep", "tend", "replant", "till", "strip", "branch", "tunnel", "wall", "path",
        // things
        "logs", "planks", "sticks", "stone", "cobblestone", "dirt", "sand", "gravel", "clay", "iron", "coal",
        "gold", "diamond", "copper", "redstone", "lapis", "emerald", "obsidian", "wheat", "carrots", "potatoes",
        "beetroot", "seeds", "pumpkin", "melon", "sugar", "cane", "food", "bread", "meat", "beef", "pork",
        "chicken", "mutton", "fish", "cod", "salmon", "wool", "leather", "string", "arrows", "bow", "sword",
        "pickaxe", "axe", "shovel", "hoe", "shears", "bucket", "torches", "crafting", "table", "furnace", "chest",
        "barrel", "bed", "door", "ladder", "fence", "boat", "armor", "armour", "helmet", "chestplate", "leggings",
        "boots", "shield", "glass", "charcoal", "ingots", "ore", "house", "cottage", "cabin", "shelter", "hut",
        "smeltery", "storage", "workshop", "watchtower", "tower", "farmhouse", "storehouse", "well", "pen",
        "animals", "cows", "pigs", "sheep", "chickens", "zombie", "skeleton", "spider", "creeper", "monsters",
        "monster", "wooden", "stone", "iron", "golden", "diamond", "netherite", "items", "inventory", "base",
        "field", "crops", "vein", "area", "spot", "here", "there", "me", "you", "chests", "level", "down", "deep",
        "stack", "stacks", "until", "then", "after", "and", "with", "from", "into", "near", "next", "around",
        "doing", "status", "health", "hungry", "need", "needs", "what", "where", "how", "why", "when", "thanks",
        "hello", "goodbye", "bye", "yes", "no", "ok", "night", "dark", "torch", "small", "big", "little", "tiny",
        "quick", "simple", "basic", "nice", "everything", "anything", "something", "auto", "yourself", "own",
        "market", "shop", "coins", "money",
        "ping", "pings", "mark", "marker", "bridge", "waypoint", "route", "skip", "closest", "nearest", "died",   // [pa33c-2b]
        "tame", "lever", "button", "wolf", "pillar", "enchant", "quartz", "parrot", "ocelot", "upgrade", "netherrack",   // [pa33d]
        "mace", "smash", "snowball", "snowballs", "eggs", "hook", "rod", "fishing", "aggro", "distract", "bookshelves", "bookshelf", "cure",
        "ravager", "brute", "pound", "ground", "pelt", "lob", "reel", "ledge"));   // [pa38]

    /** Plain English the assistant must not "correct" into an order word ("mind" is not "mine", "your" is not "you"). */
    public static final Set<String> COMMON = new java.util.HashSet<>(List.of(("gear target trick shot armor armour villager race contest nether bell wake bedtime that this those these them they their there then than with have want need some when "
        + "where which while your yours ours from into onto over under good nice job thing things also just only much many more most less very "
        + "really sure keep going never mind take back feed done been were will would could should shall might must does gone came gave went "
        + "work works name call called once before again around about above below next left right front behind inside outside each every other "
        + "another know think like love hate tell show look looking pointing point stand standing still move moving fast slow quickly soon "
        + "later today tonight tomorrow morning evening ready sorry ahead full empty half whole lots plenty enough bunch heap pile load loads "
        + "stick everyone everybody nobody someone people player friend buddy mate thank thanks well great awesome perfect cool amazing "
        + "alright fine okay kind kinda bit little lot please able sometime time hour minute second while until till soon here there "
        + "whats wanna make sure along close near nearby away down up off out over through across behind beside between grab hand pass "
        + "toss throw drop stop halt wait hold stay come follow guard protect defend fight attack hunt fish breed shear sort light clear").split(" ")));

    /** Lower case, apostrophes kept, everything else that is not a letter, digit or space made a space. */
    public static String clean(String text) {
        if (text == null) return "";
        String t = text.toLowerCase(Locale.ROOT).replace('’', '\'').replace('‘', '\'');
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '\'' || c == '-') b.append(c);
            else if (c == '&' || c == '+') b.append(' ').append(c).append(' ');
            else if (c == ',' || c == ';' || c == '.' || c == '!' || c == '?') b.append(" , ");
            else b.append(' ');
        }
        return b.toString().replaceAll("\\s+", " ").trim();
    }

    /**
     * [pa33b] British English, said or typed: phrasings opened out into the reader's own ("bung it in the chest" is
     * "put it in the chest", "nip over here" is "come here", "ta" is "thanks"). Whole phrases, in order.
     */
    private static final String[][] BRITISH = {
        {"\\b(?:be a (?:love|dear|star|pal|gem)|do us a favour) and ", ""},
        {"\\b(?:pop|bung|chuck|shove|stick|lob) (it|them|everything|it all|them all|all of it|your stuff|your things|your items|all your stuff|the lot) (?:all )?(in|into)\\b", "put $1 $2"},
        {"\\bnip (?:over |back )?(?:here|to me)\\b", "come here"},
        {"\\bnip back\\b", "come back"},
        {"^oi\\b", "hey"},
        {"^(?:ta|ta very much|ta muchly|ta mate|ta love|sorted|lovely|lovely stuff|brilliant|brill|champion|nice one|nice one mate|cheers then|smashing|ace)$", "thanks"},
        {"\\b(?:hang on|hold on|hold up|half a mo|just a mo|wait a mo|wait a tick|hang about)(?: a (?:sec|second|minute|min|mo|moment|tick|bit))?\\b", "hold on"},
        {"\\bhave a go at mining$", "dig a mine"},
        {"\\bhave a go at mining\\b", "mine"},
        {"\\bhave a go at (\\w+)ing\\b", "$1"},
        {"\\bhave a (?:look|nose|scout) (?:round|around)\\b", "look around"},
        {"\\bhave a (?:look|nose|dig|hunt) for\\b", "look for"},
        {"\\bfancy (?:getting|grabbing|fetching)\\b", "get"},
        {"\\bfancy (\\w+)ing\\b", "$1"},
        {"^(?:go on then|go on|come on then|ok then|okay then|alright then|right then|right|well then|so)\\s*,?\\s+(?=\\S)", ""},
        {"\\bright then\\b", "right"},
        {"\\bstop there\\b", "stay there"},
        {"\\bstay put\\b", "stay here"},
        {"^(?:crack on|crack on then|get on with it|get cracking|on you go|off you go then)$", "carry on"},
        {"^(?:pack it in|leave it|leave it out|knock it off|give over|that'll do|that will do|enough|that's enough|thats enough)$", "stop"},
        {"^(?:you alright|you all right|are you alright|are you all right|you ok|alright mate|how's it going|hows it going|how you doing)$", "how are you"},
        {"\\bwhereabouts\\b", "where"},
        {"\\bgo and (get|fetch|grab|chop|mine|find|bring)\\b", "$1"},
        {"\\b(?:colour|color)\\b", "colour"},
        {"\\bstoreys?\\b", "storey"},
        {"\\b(\\d+|one|two|three|four|five|six|seven|eight|nine|ten) metres? (?:away|off|over)\\b", "over there"},
    };

    /**
     * [pa33b] What a speech recogniser tends to hear for British vowels, put right. Only for spoken orders (a typed
     * "stun" stays a typo for the reader's own fixer), and only where the slip can't be a real order of its own.
     */
    private static final String[][] MISHEARD = {
        {"\\b(?:lugs|lags|logues|lodges)\\b", "logs"},
        {"\\b(?:fallow|fellow|hollow|follo)( me| us|$)", "follow$1"},
        {"\\b(?:stun|stoan|stern)\\b", "stone"},
        {"\\b(?:ion|ions|irony|iran)\\b", "iron"},
        {"\\b(?:god|gourd|gaud)( me| us| here| this| that| the| my| it)\\b", "guard$1"},
        {"(?<!thanks |cheers |ta )\\b(?:fam|firm|fan)( this| the| that| here| some| wheat| for)\\b", "farm$1"},
        {"\\b(?:build|make|plant) (?:a )?(?:fam|firm)\\b", "build a farm"},
        {"\\b(?:parth|pars|paths)\\b", "path"},
        {"\\b(?:the|that|this|a) chess\\b", "the chest"},
        {"\\b(some|more|the|\\d+|sixteen|thirty two|sixty four) would\\b", "$1 wood"},
        {"^mind (iron|coal|gold|diamonds?|for|some)\\b", "mine $1"},
        {"\\b(?:coal ore|cole|kohl)\\b", "coal"},
        {"\\bstay hair\\b", "stay here"},
        {"\\bcome hair\\b", "come here"},
    };

    /** [pa33b] A spoken order with the recogniser's usual British-vowel slips put right (see MISHEARD). */
    public static String voiceFix(String heard) {
        if (heard == null) return "";
        String c = heard.toLowerCase(java.util.Locale.ROOT).trim();
        for (String[] r : MISHEARD) c = c.replaceAll(r[0], r[1]);
        return c.replaceAll("\\s+", " ").trim();
    }

    /** [pa33b] The British phrasings opened out (see BRITISH). */
    public static String british(String cleaned) {
        String c = cleaned;
        for (String[] r : BRITISH) c = c.replaceAll(r[0], r[1]);
        return c.replaceAll("\\s+", " ").trim();
    }

    /** The whole pass: clean, slang, typos, numbers. Commas survive as " , " so the clause splitter can see them. */
    public static String normalise(String text) {
        String c = british(clean(text));
        if (c.isEmpty()) return c;
        List<String> out = new ArrayList<>();
        String[] words = c.split(" ");
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.isEmpty()) continue;
            if (w.equals("-")) continue;
            String next = i + 1 < words.length ? words[i + 1] : "";
            // "pick up the items" is not a pickaxe; "light" is not a torch when it is "light up"
            if (w.equals("pick") && (next.equals("up") || next.equals("it") || next.equals("them"))) { out.add(w); continue; }
            String s = SLANG.get(w);
            if (s == null && w.endsWith("'s")) s = SLANG.get(w.substring(0, w.length() - 2));
            if (s == null) s = SLANG.get(w.replace("'", ""));
            if (s != null) {
                for (String x : s.split(" ")) if (!x.isEmpty()) out.add(x);
            } else {
                out.add(fix(w.replace("'", "")));
            }
        }
        return numbers(String.join(" ", out)).replaceAll("\\s+", " ").trim();
    }

    /** A misspelt word put right, when one known word is near enough; else the word as it was. */
    public static String fix(String w) {
        if (w.length() < 4 || VOCAB.contains(w) || COMMON.contains(w) || w.chars().allMatch(Character::isDigit)) return w;
        if (NUM.containsKey(w)) return w;
        // a plain plural of a known word is that word ("swords", "pickaxes", "furnaces")
        if (w.endsWith("s") && VOCAB.contains(w.substring(0, w.length() - 1))) return w.substring(0, w.length() - 1);
        if (w.endsWith("es") && VOCAB.contains(w.substring(0, w.length() - 2))) return w.substring(0, w.length() - 2);
        int most = w.length() <= 5 ? 1 : 2;
        String best = null;
        int bestD = most + 1;
        boolean tie = false;
        for (String v : VOCAB) {
            if (Math.abs(v.length() - w.length()) > most) continue;
            int d = distance(w, v, most);
            if (d < bestD) { bestD = d; best = v; tie = false; }
            else if (d == bestD && best != null && !best.equals(v)) tie = true;
        }
        // two words equally near: the one sharing the first letter wins, else leave the word alone
        if (best != null && tie) {
            String firstMatch = null;
            for (String v : VOCAB) {
                if (Math.abs(v.length() - w.length()) > most) continue;
                if (distance(w, v, most) == bestD && v.charAt(0) == w.charAt(0)) { firstMatch = v; break; }
            }
            best = firstMatch;
        }
        return best != null && bestD <= most ? best : w;
    }

    /** Number words to digits: "twenty five" 25, "a hundred" 100, "a dozen" 12, "half a stack" 32. */
    public static String numbers(String text) {
        String[] tok = text.split(" ");
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < tok.length) {
            String w = tok[i];
            if (w.equals("half") && i + 2 < tok.length && tok[i + 1].equals("a") && tok[i + 2].startsWith("stack")) {
                out.add("32"); i += 3; continue;
            }
            if ((w.equals("a") || w.equals("one")) && i + 1 < tok.length && tok[i + 1].equals("dozen")) { out.add("12"); i += 2; continue; }
            if (w.equals("dozen")) { out.add("12"); i++; continue; }
            if (w.equals("a") && i + 1 < tok.length && (tok[i + 1].equals("couple") || tok[i + 1].equals("pair"))) {
                out.add("2"); i += 2;
                if (i < tok.length && tok[i].equals("of")) i++;
                continue;
            }
            if (w.equals("a") && i + 1 < tok.length && tok[i + 1].equals("few")) { out.add("4"); i += 2; continue; }
            long value = 0, current = 0;
            int j = i;
            boolean any = false;
            while (j < tok.length) {
                String t = tok[j];
                if (NUM.containsKey(t)) { current += NUM.get(t); any = true; j++; }
                else if (t.equals("hundred") && (any || (j > i && tok[j - 1].equals("a")))) { current = Math.max(1, current) * 100; any = true; j++; }
                else if (t.equals("a") && j + 1 < tok.length && tok[j + 1].equals("hundred")) { current += 0; j++; }
                else if (t.equals("and") && any && j + 1 < tok.length && NUM.containsKey(tok[j + 1])) j++;
                else break;
            }
            if (any) {
                value += current;
                out.add(Long.toString(value));
                i = j;
            } else {
                out.add(w);
                i++;
            }
        }
        return String.join(" ", out);
    }

    /** Edit distance with a swap of neighbours counting one, giving up past {@code most}. */
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
