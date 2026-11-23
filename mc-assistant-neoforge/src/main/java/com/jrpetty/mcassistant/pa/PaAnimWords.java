package com.jrpetty.mcassistant.pa;

import java.util.Locale;
import java.util.Set;

/** [pa33c-2b] Which gesture goes with a line: its "[anim:x]" tag, or a guess from the words. Plain Java (tested on its own). */
public final class PaAnimWords {

    private PaAnimWords() {}

    /** The gestures a line may ask for (the folk's acts that suit a companion). */
    public static final Set<String> KNOWN = Set.of("wave", "shrug", "bow", "cheer", "clap", "think", "point", "salute", "beckon", "dance", "talk",
        "listen", "mourn", "kneel", "quarrel", "protest", "lost", "eat", "drink", "fall", "handshake");

    /** The "[anim:x]" among a line's tags, or null. */
    public static String tagged(Set<String> tags) {
        if (tags == null) return null;
        for (String t : tags) if (t.startsWith("anim:") && KNOWN.contains(t.substring(5))) return t.substring(5);
        return null;
    }

    /** A gesture for words with no tag of their own, or null for none. */
    public static String guess(String text, boolean badJoke) {
        if (text == null || text.isBlank()) return null;
        if (badJoke) return "shrug";
        String t = text.toLowerCase(Locale.ROOT).trim();
        if (t.matches("^(?:hello|hi|hey|hiya|morning|good morning|evening|good evening|right then|there you are|welcome back|oh, it's you)\\b.*")) return "wave";
        if (t.matches("^(?:thanks|thank you|cheers|ta)\\b.*")) return "bow";
        if (t.matches("^(?:done|all done|finished|that's done|job done|there we go|ta-da|sorted)\\b.*")) return "cheer";
        if (t.matches("^(?:bye|goodbye|see you|night|good night)\\b.*")) return "wave";
        if (t.matches("^(?:sorry|my bad|oops)\\b.*")) return "shrug";
        if (t.matches("^(?:hmm|let me think|good question|well, )\\b.*")) return "think";
        return null;
    }
}
