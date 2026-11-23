package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa33] The assistant's phrase bank: every line in every personality, filled, readable, never robotic, never blank. */
class PaPhrasesTest {

    @Test
    void everyLineFillsAndReads() {
        Random r = new Random(7);
        int lines = 0;
        for (String key : PaPhrases.keys()) {
            for (PaPhrases.Personality p : PaPhrases.Personality.values()) {
                assertFalse(PaPhrases.raw(key, p).isEmpty(), key + " has no " + p + " line");
                for (int i = 0; i < 8; i++) {
                    String s = PaPhrases.line(key, p, r::nextInt, "name", "Pip", "owner", "Alex", "job", "gather 16 logs", "what", "I need a pickaxe.", "item", "iron", "n", "3");
                    assertFalse(s.contains("{") || s.contains("}"), key + ": a blank left in " + s);
                    assertTrue(s.length() > 2 && s.length() <= 200, key + ": " + s);
                    assertFalse(s.toLowerCase().matches(".*\\b(error|command|execute|null|undefined|affirmative)\\b.*"), key + " sounds like a machine: " + s);
                }
                lines += PaPhrases.raw(key, p).size();
            }
        }
        System.out.println("[pa33] phrase bank: " + PaPhrases.keys().size() + " keys, " + lines + " lines");
        assertTrue(lines >= 100, "a varied bank: " + lines);
    }

    @Test
    void usesTheNames() {
        String s = PaPhrases.line("intro", PaPhrases.Personality.CHEERFUL, new Random(1)::nextInt, "name", "Pip", "owner", "Alex");
        assertTrue(s.contains("Pip") && s.contains("Alex"), s);
    }

    @Test
    void finishedStepsReadAsDone() {
        org.junit.jupiter.api.Assertions.assertEquals("Made a stone pickaxe", PaPhrases.did("make a stone pickaxe first"));
        org.junit.jupiter.api.Assertions.assertEquals("Gathered 87 stone for the house", PaPhrases.did("gather 87 stone for the house"));
        org.junit.jupiter.api.Assertions.assertEquals("Built a small house", PaPhrases.did("build a small house"));
        String s = PaPhrases.line("step", PaPhrases.Personality.CALM, new Random(2)::nextInt, "did", PaPhrases.did("get 129 planks for the house"));
        assertTrue(s.startsWith("Got 129 planks"), s);
    }
}
