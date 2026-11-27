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

    /** [pa33b] Six or more ways to say every reply, at every corner it is said from, so it never feels repetitive. */
    @org.junit.jupiter.api.Test
    void sixOrMoreVariantsEverywhere() {
        int min = Integer.MAX_VALUE, total = 0;
        String smallest = "";
        for (String k : PaLines.keys()) {
            int n = PaLines.get(k).size();
            total += n;
            if (n < min) { min = n; smallest = k; }
            org.junit.jupiter.api.Assertions.assertTrue(n >= 6, "only " + n + " ways to say " + k);
        }
        for (String k : PaLines.keys()) {
            if (!k.startsWith("say.") || k.contains("@")) continue;
            for (String c : new String[]{"@gloomy", "@serious"})
                org.junit.jupiter.api.Assertions.assertTrue(PaLines.get(k + c).size() >= 6, k + c + ": " + PaLines.get(k + c).size());
        }
        for (String k : PaPhrases.DANGER)
            org.junit.jupiter.api.Assertions.assertTrue(PaPhrases.plainCount(k) >= 6, "warning " + k + ": " + PaPhrases.plainCount(k));
        // the same reply never twice running: a short run of picks from one list has no immediate repeat
        PaPersona.Recent recent = new PaPersona.Recent(400);
        java.util.Random r = new java.util.Random(3);
        String last = null;
        for (int i = 0; i < 60; i++) {
            String s = PaPersona.from(PaLines.get("say.done@serious"), r::nextInt, recent);
            org.junit.jupiter.api.Assertions.assertNotEquals(last, s, "the same reply twice running");
            last = s;
        }
        System.out.printf("[pa33b] variants: smallest list %d (%s), %d lists, %d lines%n", min, smallest, PaLines.keys().size(), total);
    }

    /** [pa33c-2b] A speech bubble is always wholly on screen; close up it sits beside the head, never cut off at the top. */
    @org.junit.jupiter.api.Test
    void bubblesStayOnScreen() {
        int w = 427, h = 240;                                   // 1280x720 at GUI 3
        float[][] heads = {{213, 120}, {5, 5}, {420, 2}, {213, -40}, {-30, 300}, {430, 120}, {213, 10}};
        for (float[] hd : heads) for (double d : new double[]{1.5, 3, 8, 30}) {
            int[] b = PaLayout.bubble(hd[0], hd[1], 180, 66, d, w, h);
            org.junit.jupiter.api.Assertions.assertTrue(b[0] >= 4 && b[1] >= 4 && b[0] + b[2] <= w - 4 && b[1] + b[3] <= h - 4,
                java.util.Arrays.toString(hd) + " at " + d + ": " + java.util.Arrays.toString(b));
        }
        int[] near = PaLayout.bubble(213, 30, 180, 66, 2.0, w, h);
        org.junit.jupiter.api.Assertions.assertTrue(near[0] >= 213 + 18 || near[0] + near[2] <= 213 - 18, "close up: beside the head, not over it");
        int[] far = PaLayout.bubble(213, 150, 100, 40, 12.0, w, h);
        org.junit.jupiter.api.Assertions.assertTrue(far[1] + far[3] <= 150 && Math.abs(far[0] + far[2] / 2 - 213) <= 1, "far: centred above the head");
        int[] wide = PaLayout.bubble(213, 120, 600, 400, 8.0, w, h);
        org.junit.jupiter.api.Assertions.assertTrue(wide[2] <= w - 8 && wide[3] <= h - 8, "never bigger than the screen");
    }
}
