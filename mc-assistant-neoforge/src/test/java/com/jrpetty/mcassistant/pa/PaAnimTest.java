package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa33c-2b] Gestures with its words: "[anim:x]" tags on written lines, and the guesses for the rest. */
class PaAnimTest {

    @Test
    void tagsComeFirstAndMustBeKnown() {
        assertEquals("wave", PaAnimWords.tagged(Set.of("anim:wave")));
        assertEquals("think", PaAnimWords.tagged(Set.of("bad", "anim:think")));
        assertNull(PaAnimWords.tagged(Set.of("anim:backflip")), "only gestures it has");
        assertNull(PaAnimWords.tagged(Set.of("bad")));
    }

    @Test
    void guesses() {
        assertEquals("shrug", PaAnimWords.guess("What's a skeleton's least favourite room. The living room.", true), "an owned bad joke: a shrug");
        assertEquals("wave", PaAnimWords.guess("Hello again.", false));
        assertEquals("bow", PaAnimWords.guess("Thanks, that hit the spot.", false));
        assertEquals("cheer", PaAnimWords.guess("Done. Sixteen logs.", false));
        assertNull(PaAnimWords.guess("Creeper near you. Back away from it.", false), "a warning: no flourish");
        assertNull(PaAnimWords.guess("", false));
    }

    @Test
    void writtenTagsAreRealGestures() {
        int tagged = 0;
        for (var e : PaLines.all().entrySet()) for (String l : e.getValue()) {
            for (String t : PaLines.tags(l)) if (t.startsWith("anim:")) {
                tagged++;
                assertTrue(PaAnimWords.KNOWN.contains(t.substring(5)), e.getKey() + ": " + t);
                assertTrue(!l.contains("[anim"), "tag stripped from the words: " + l);
            }
        }
        assertTrue(tagged >= 12, "jokes and quips linked to gestures: " + tagged);
    }
}
