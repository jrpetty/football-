package com.jrpetty.mcassistant.pa;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * [pa33f] Casual remarks read by their shape, not a list of whole sentences: "you're / you are / ur / that was / what a"
 * and any good word or phrase is praise, any bad one an insult; intensifiers ("well funny", "proper good", "dead good",
 * "so", "absolutely"), negation ("not funny" is an insult, "not bad" British praise), British slang ("class", "mint",
 * "sound", "lush", "a lad", "banging"), typos ("hilarius"), and laughter ("lol", "haha", "lmao", an emoji, "I'm dying")
 * as a reaction to what it just said. Also whether a line it can't place reads as chat rather than an order, so it
 * answers in kind instead of offering a menu. Plain Java, tested on its own.
 */
public final class PaRemarks {

    private PaRemarks() {}

    /** Good words and phrases (a single word or a few, after the article if any). */
    static final Set<String> GOOD = Set.of(
        "hilarious", "funny", "legend", "legendary", "class", "classy", "mint", "sound", "ace", "top", "best", "the best", "lad", "a lad",
        "quality", "banging", "banger", "a banger", "lush", "brilliant", "brill", "great", "amazing", "awesome", "fantastic", "fab", "fabulous",
        "genius", "a genius", "star", "a star", "superstar", "champion", "a champion", "champ", "hero", "a hero", "gem", "a gem", "diamond", "a diamond",
        "cracking", "a cracker", "cracker", "smashing", "smart", "clever", "wicked", "sick", "epic", "magic", "wonderful", "lovely", "nice", "good",
        "cool", "boss", "the boss", "the man", "a gent", "gent", "top notch", "spot on", "on fire", "the goat", "goat", "king", "the king", "mvp",
        "a belter", "belter", "a beast", "beast", "unreal", "incredible", "superb", "excellent", "outstanding", "gold", "golden", "the business",
        "mega", "sweet", "fire", "lit", "decent", "solid", "a laugh", "a riot", "a scream", "a hoot", "a card", "a character", "a treasure",
        "cute", "adorable", "a comedian", "comedy gold", "a natural", "a machine", "a legend", "a top lad", "top lad", "bang on", "the bomb",
        "class act", "a class act", "the real deal", "dead good", "proper good", "well good", "sorted", "handy", "useful", "helpful", "kind",
        "a good lad", "a good egg", "good egg", "a sweetheart", "sweetheart", "a darling", "darling", "brave", "a rock", "a lifesaver", "lifesaver",
        "hysterical", "a delight", "delightful", "perfect", "flawless", "elite", "goated", "peng", "buff", "bostin", "canny", "grand", "braw",
        "the dog's", "nice one", "top banter", "good banter", "quality banter", "great banter", "the best ever", "my hero", "a wizard", "wizard", "a pro", "pro");

    /** Bad words and phrases. */
    static final Set<String> BAD = Set.of(
        "useless", "rubbish", "garbage", "trash", "terrible", "awful", "pathetic", "hopeless", "worst", "the worst", "bad", "crap", "naff", "pants",
        "shocking", "dire", "grim", "lame", "boring", "dull", "unfunny", "cringe", "cringey", "cringy", "dreadful", "poor", "stupid", "dumb", "thick",
        "daft", "dozy", "slow", "lazy", "an idiot", "idiot", "a muppet", "muppet", "a numpty", "numpty", "a plonker", "plonker", "a wally", "wally",
        "a pillock", "pillock", "a clown", "clown", "a joke", "a donkey", "donkey", "a div", "div", "a nitwit", "nitwit", "a loser", "loser",
        "a waste of space", "waste of space", "a disgrace", "disgrace", "embarrassing", "annoying", "irritating", "a pain", "a nightmare", "a mess",
        "a liability", "liability", "mid", "meh", "weak", "sad", "tragic", "a moron", "moron", "an absolute weapon", "a weapon", "a melt",
        "a doughnut", "doughnut", "weapon", "melt", "a spanner", "spanner", "a berk", "berk", "a prat", "prat", "a twit", "twit", "a dork", "dork", "a nob", "a bellend",
        "a tool", "a numbskull", "numbskull", "a fool", "foolish", "a let down", "a letdown", "letdown", "not great", "not good", "rank", "minging", "manky",
        "dodgy", "ropey", "a shambles", "shambles", "a car crash", "a disaster", "disaster", "a fail", "fail", "slow as a snail", "thick as mince",
        "thick as two short planks", "dead slow", "so slow", "brain dead", "braindead", "clueless", "a dead loss", "dead loss", "worthless");

    /** Not a verdict when they're the whole message (an item, an order, a question as often). */
    static final Set<String> NOT_ALONE = Set.of("diamond", "gold", "golden", "gem", "rock", "star", "fire", "king", "boss", "beast", "machine",
        "wizard", "card", "top", "pro", "sound", "lit", "handy", "useful", "kind", "brave", "cute", "slow", "poor", "sad", "rank", "fail",
        "weak", "broken", "decent", "solid", "sweet", "grand", "best", "mega", "elite", "the boss", "the king", "clown", "donkey", "spanner", "tool");

    /** Words that only strengthen what follows. */
    static final Set<String> INTENSE = Set.of("so", "very", "really", "well", "proper", "dead", "pure", "totally", "absolutely", "utterly", "bloody",
        "right", "such", "quite", "rather", "pretty", "super", "mega", "properly", "seriously", "honestly", "literally", "truly", "too",
        "an", "a", "the", "absolute", "total", "complete", "real", "massive", "fair", "actually", "just", "genuinely", "kinda", "kind", "of", "lowkey", "highkey", "unreal");

    /** How a remark opens: "you're", "that was", "what a"... (with "ur", "u r", "your" as players type them). */
    static final List<String> OPENERS = List.of("you are", "you're", "youre", "your", "ur", "u r", "u are", "you", "that was", "that is",
        "that's", "thats", "what a", "such a", "you absolute", "you total", "you complete", "you utter",
        "ya", "you're being", "you are being", "that joke was", "the joke was", "your jokes are", "ur jokes are", "you're so", "you are so");

    static final Set<String> LAUGH_WORDS = Set.of("lol", "lool", "loool", "lmao", "lmfao", "rofl", "roflmao", "haha", "hahaha", "hahahaha", "hehe",
        "hehehe", "heh", "ha", "lolol", "kek", "xd", "wheeze", "lmaoo", "lmaooo", "pmsl", "hah", "lolz", "lul");

    /** What a remark is (praise, an insult, a laugh), or null when it isn't one. Typos allowed. */
    public static PaQuips.Comeback read(String text) {
        if (text == null) return null;
        String raw = text;
        if (raw.contains("😂") || raw.contains("🤣") || raw.contains("😆") || raw.contains("😹")) return PaQuips.Comeback.LAUGH;
        String c = norm(text);
        if (c.isEmpty()) return null;
        String[] w = c.split(" ");
        // laughter: the message is mostly laughing ("haha", "lol that's good", "im dying", "i'm dead")
        if (laughs(c, w)) return PaQuips.Comeback.LAUGH;
        // an opener and what follows it
        for (String o : OPENERS) {
            if (!(c.equals(o) || c.startsWith(o + " "))) continue;
            String rest = c.substring(o.length()).trim();
            PaQuips.Comeback got = judge(rest, o, o.equals("you"));
            if (got != null) return got;
        }
        // the whole message is just a verdict ("well funny", "class", "proper good", "not bad", "absolutely useless")
        if (w.length <= 5) {
            PaQuips.Comeback got = judge(c, "", true);
            if (got != null) return got;
        }
        return null;
    }

    /** "well funny", "not bad", "a proper legend", "hilarius": praise or an insult, or null. */
    static PaQuips.Comeback judge(String rest, String opener, boolean strict) {
        if (rest.isEmpty()) return null;
        boolean not = false;
        List<String> words = new ArrayList<>(List.of(rest.split(" ")));
        // trailing chat that doesn't change it ("mate", "honestly", "lol", "tbh")
        while (!words.isEmpty() && Set.of("mate", "pal", "bud", "buddy", "honestly", "tbh", "lol", "haha", "bro", "man", "though", "tho", "innit",
            "today", "now", "you", "there", "m8", "fella", "chief", "boss", "eh", "yeah", "ngl", "fr", "mind", "like").contains(words.get(words.size() - 1))) {
            if (words.size() == 1) break;
            words.remove(words.size() - 1);
        }
        if (words.size() > 2 && words.get(words.size() - 2).equals("at") && words.get(words.size() - 1).equals("all")) { words.remove(words.size() - 1); words.remove(words.size() - 1); }
        // negation at the front ("not", "never", "n't" folded by norm)
        while (!words.isEmpty() && Set.of("not", "never", "no", "hardly", "isnt", "wasnt", "arent", "aint").contains(words.get(0))) { not = !not; words.remove(0); }
        // "that was not funny": "not" may come after the opener's verb
        while (!words.isEmpty() && INTENSE.contains(words.get(0)) && words.size() > 1) words.remove(0);
        while (!words.isEmpty() && Set.of("not", "never").contains(words.get(0))) { not = !not; words.remove(0); while (!words.isEmpty() && INTENSE.contains(words.get(0)) && words.size() > 1) words.remove(0); }
        if (words.isEmpty() || words.size() > 6) return null;
        String phrase = String.join(" ", words);
        if (opener.isEmpty() && NOT_ALONE.contains(phrase)) return null;
        Boolean good = verdict(phrase);
        if (good == null && !strict && words.size() > 1) good = verdict(words.get(0));                   // "a good laugh", "funny one"
        if (good == null && !strict && words.size() > 1) good = verdict(words.get(words.size() - 1));    // "a right proper legend"
        if (good == null) return null;
        // "you" alone as an opener needs a noun-ish verdict ("you legend", "you muppet"), not "you good?" (a question)
        if (opener.equals("you") && words.size() == 1 && Set.of("good", "ok", "alright", "sound", "fine").contains(phrase)) return null;
        boolean praise = good != not;
        if (not && !good && Set.of("bad", "too bad", "half bad", "so bad", "awful", "terrible", "rubbish", "the worst", "worst").contains(phrase)) praise = true;   // "not bad": praise
        return praise ? PaQuips.Comeback.PRAISE : PaQuips.Comeback.INSULT;
    }

    /** True good, false bad, null neither (with a one-letter slip allowed in a word of five letters or more). */
    static Boolean verdict(String phrase) {
        if (GOOD.contains(phrase)) return true;
        if (BAD.contains(phrase)) return false;
        String bare = phrase.startsWith("a ") ? phrase.substring(2) : phrase.startsWith("an ") ? phrase.substring(3) : phrase.startsWith("the ") ? phrase.substring(4) : phrase;
        if (GOOD.contains(bare)) return true;
        if (BAD.contains(bare)) return false;
        if (bare.endsWith("est") && GOOD.contains(bare.substring(0, bare.length() - 3))) return true;    // "greatest", "coolest"
        if (bare.length() >= 5 && !bare.contains(" ")) {
            for (String g : GOOD) if (!g.contains(" ") && g.length() >= 5 && PaQuips.near2(bare, g, 1)) return true;
            for (String b : BAD) if (!b.contains(" ") && b.length() >= 5 && PaQuips.near2(bare, b, 1)) return false;
        }
        return null;
    }

    /** Mostly laughing. */
    static boolean laughs(String c, String[] w) {
        int laugh = 0;
        for (String x : w) if (LAUGH_WORDS.contains(x) || x.matches("(?:ha){2,}h?|(?:he){2,}h?|l+o+l+|lm+f?a+o+|a?ha(?:ha)+|ro+fl")) laugh++;
        if (laugh == 0) return c.matches("(?:(?:i'?m|im|i am) )?(?:dying|dead|crying|in bits|in stitches|wheezing|screaming|creasing|cracking up|crying laughing|on the floor)(?: here| over here| mate| lol)?")
            || c.matches("(?:you'?re|youre|you are|ur) killing me(?: mate)?");
        return laugh * 2 >= w.length || w.length <= 4;
    }

    /** Lower case, punctuation gone, "n't" folded, "you're" spelled the ways players type it kept as words. */
    static String norm(String text) {
        String t = text.toLowerCase(Locale.ROOT).replace('’', '\'').replace('‘', '\'');
        t = t.replace("n't", "nt").replaceAll("[^a-z0-9' ]", " ").replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^(?:oh|aw|aww|wow|mate|oi|ah|ahh|omg|honestly|seriously|man|bro|dude|ngl|tbh|yeah|yes|ok|okay|well)\\s+(?=\\S)", "");
        t = t.replace("you re ", "you're ").replace("that s ", "that's ").replace("it s ", "it's ");
        t = (" " + t + " ").replace(" wasnt ", " was not ").replace(" isnt ", " is not ").replace(" arent ", " are not ").replace(" aint ", " is not ")
            .replace(" werent ", " were not ").trim();
        return t.trim();
    }

    // ------------------------------------------------------------------ chat or an order

    /** Words an order starts with. */
    static final Set<String> ORDER_VERBS = Set.of("get", "bring", "make", "craft", "build", "go", "come", "follow", "mine", "dig", "chop", "cut",
        "gather", "collect", "fetch", "put", "place", "kill", "attack", "guard", "find", "give", "take", "smelt", "cook", "plant", "farm",
        "harvest", "light", "clear", "fill", "stop", "stay", "wait", "shoot", "tame", "trade", "buy", "sell", "brew", "enchant", "map", "pillar",
        "bridge", "help", "show", "tell", "set", "use", "open", "sleep", "wake", "drop", "store", "sort", "equip", "upgrade", "eat", "feed",
        "breed", "race", "ping", "hunt", "fight", "defend", "protect", "move", "walk", "run", "jump", "climb", "swim", "fish", "pick", "grab",
        "leave", "return", "head", "explore", "scout", "patrol", "fix", "repair", "empty", "deposit", "withdraw", "throw", "toss", "say", "call",
        "name", "rename", "dismiss", "remember", "forget", "mark", "save", "load", "start", "begin", "finish", "cancel", "undo", "resume", "pause");

    /**
     * A line it couldn't place that reads as chat (a remark, a feeling, small talk) rather than an order it misheard:
     * short, and not starting with an order's verb (after "please", "can you", "could you"...). Questions about it or
     * the game are left to the parser's questions.
     */
    public static boolean chat(String text) {
        String c = norm(text);
        if (c.isEmpty()) return false;
        String s = c.replaceAll("^(?:please |pls |can you |could you |would you |will you |can u |could u |go and |i want you to |i need you to |id like you to |i'd like you to )+", "");
        String[] w = s.split(" ");
        if (w.length > 10) return false;
        if (ORDER_VERBS.contains(w[0])) return false;
        if (s.matches("^(?:where|how many|how much|what's in|whats in|what is in)\\b.*")) return false;   // questions the parser asks back about
        return true;
    }
}
