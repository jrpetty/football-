package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa33b] Match my energy: tone read and cut from the order, its language never past the player's choice or the server cap, and the hard limits. */
class PaToneTest {

    /** Said, the same order said plainly, and the tone. */
    static final String[][] ORDERS = {
        {"go get my wheat you fucking loser", "go get my wheat", "RUDE"},
        {"get me 16 logs you useless idiot", "get me 16 logs", "RUDE"},
        {"fetch the fucking wheat", "fetch the wheat", "RUDE"},
        {"get me some bloody logs", "get me some logs", "RUDE"},
        {"follow me, muppet", "follow me", "RUDE"},
        {"build me a small house here you absolute moron", "build me a small house here", "RUDE"},
        {"mine some iron ffs you f*cking donkey", "mine some iron", "RUDE"},
        {"get me 16 logs u fkn loser", "get me 16 logs", "RUDE"},
        {"come here you lazy git", "come here", "RUDE"},
        {"stay here, idiot", "stay here", "RUDE"},
        {"craft a stone pickaxe you sh1t bot", "craft a stone pickaxe", "RUDE"},
        {"go get my wheat you fuuuucking loser", "go get my wheat", "RUDE"},
        {"guard me you pathetic waste", "guard me", "RUDE"},
        {"smelt eight iron for fucks sake", "smelt eight iron", "RUDE"},
        {"light up the area you numpty", "light up the area", "RUDE"},
        {"bring me food you useless muppet", "bring me food", "RUDE"},
        {"get me 16 logs dumbass", "get me 16 logs", "RUDE"},
        {"follow me you bloody clown", "follow me", "RUDE"},
        {"chop 32 logs you pillock", "chop 32 logs", "RUDE"},
        {"sort the chests, you plonker", "sort the chests", "RUDE"},
        {"go home you prat", "go home", "RUDE"},
        {"get me some wheat damn it", "get me some wheat", "RUDE"},
        {"please get me 16 logs", "get me 16 logs", "POLITE"},
        {"could you get me 16 logs please", "get me 16 logs", "POLITE"},
        {"follow me please", "follow me", "POLITE"},
        {"get me some wheat, thanks", "get me some wheat", "POLITE"},
        {"would you build a small house here please", "build a small house here", "POLITE"},
        {"cheers, now get me 16 logs", "get me 16 logs", "POLITE"},
        {"pls mine some iron", "mine some iron", "POLITE"},
        {"bring me food please mate", "bring me food mate", "POLITE"},
        {"light up the area please", "light up the area", "POLITE"},
        {"thank you, follow me", "follow me", "POLITE"},
        {"guard me please", "guard me", "POLITE"},
        {"could you sort the chests", "sort the chests", "POLITE"},
        {"plz get me some wheat", "get me some wheat", "POLITE"},
        {"wheat. now.", "wheat. now.", "BLUNT"},
        {"follow.", "follow.", "BLUNT"},
        {"logs.", "logs.", "BLUNT"},
        {"come here. now.", "come here. now.", "BLUNT"},
        {"come here", "come here", "NEUTRAL"},
        {"get me 16 logs and then build me a small house here", "get me 16 logs and then build me a small house here", "NEUTRAL"},
        {"take the slow way round to the village", "take the slow way round to the village", "NEUTRAL"},
        {"mine iron until you have twenty", "mine iron until you have twenty", "NEUTRAL"},
    };

    @Test
    void toneIsReadAndTheOrderStillWorks() {
        int tone = 0, same = 0;
        StringBuilder miss = new StringBuilder();
        for (String[] o : ORDERS) {
            PaTone.Read r = PaTone.read(o[0]);
            if (r.tone().name().equals(o[2])) tone++; else miss.append(o[0]).append(" -> ").append(r.tone()).append("; ");
            String a = PaUnderstand.read(r.order(), "pip").brief(), b = PaUnderstand.read(o[1], "pip").brief();
            if (a.equals(b)) same++; else miss.append(o[0]).append(" read as [").append(a).append("] not [").append(b).append("]; ");
        }
        System.out.printf("[pa33b] tone: %d/%d right; orders read the same as said plainly: %d/%d%n", tone, ORDERS.length, same, ORDERS.length);
        assertTrue(ORDERS.length >= 40);
        assertEquals(ORDERS.length, tone, miss.toString());
        assertEquals(ORDERS.length, same, miss.toString());
        // the user's own example: the wheat still gets fetched
        PaTone.Read ex = PaTone.read("go get my wheat you fucking loser");
        assertEquals(PaTone.Tone.RUDE, ex.tone());
        assertEquals(2, ex.rudeness(), "sworn at and called names");
        assertEquals("go get my wheat", ex.order());
        assertTrue(PaUnderstand.read(ex.order(), "pip").understood());
    }

    @Test
    void creativeSpellingsAreSwears() {
        for (String w : List.of("fuck", "f*ck", "f**king", "fkn", "fking", "fuuuuck", "fucking", "sh1t", "$hit", "shite", "bollocks", "wanker", "twat"))
            assertTrue(PaTone.strong(w), w);
        for (String w : List.of("bloody", "damn", "arse", "git", "sod", "crap", "bugger")) assertTrue(PaTone.mild(w) && !PaTone.strong(w), w);
        for (String w : List.of("duck", "shift", "fork", "luck", "class", "assist", "glass", "scrap", "hello", "sold", "digit", "shell"))
            assertFalse(PaTone.swear(w), "not a swear: " + w);
    }

    @Test
    void neverPastThePlayersChoiceOrTheServerCap() {
        for (int player = 0; player <= 2; player++) for (int cap = 0; cap <= 2; cap++) {
            int lv = PaTone.level(player, cap);
            assertTrue(lv <= player && lv <= cap, player + "/" + cap);
            String key = PaTone.key(PaTone.read("get wheat you fucking loser"), 0, 1, lv, 0);
            assertTrue(key.endsWith(lv == 0 ? ".clean" : lv == 1 ? ".cheeky" : ".unfiltered"), key);
        }
        assertEquals(1, PaTone.cap("cheeky"));
        assertEquals(1, PaTone.cap("nonsense"), "unreadable: cheeky");
        assertEquals(0, PaTone.cap("clean"));
        assertEquals(2, PaTone.cap("Unfiltered"));
        // hushed: never a roast
        for (int m = 1; m <= 2; m++) assertNull(PaTone.key(PaTone.read("get wheat you fucking loser"), 0, 1, 2, m));
    }

    @Test
    void cleanNeverSwearsAndCheekyIsMild() {
        int clean = 0;
        for (Map.Entry<String, List<String>> e : PaLines.all().entrySet()) {
            String k = e.getKey();
            boolean unf = k.contains("unfiltered"), cheeky = k.contains("cheeky");
            for (String line : e.getValue()) {
                for (String w : line.split("[^A-Za-z0-9*$']+")) {
                    if (w.isEmpty()) continue;
                    if (!unf) assertFalse(PaTone.strong(w), "a strong word outside Unfiltered (" + k + "): " + line);
                    if (!unf && !cheeky && !k.startsWith("look.") && !k.startsWith("self.")) assertFalse(PaTone.mild(w) && !w.equalsIgnoreCase("hell") && !w.equalsIgnoreCase("heck"),
                        "a mild word in a Clean list (" + k + "): " + line);
                }
                if (!unf && !cheeky) clean++;
            }
        }
        assertTrue(clean > 1000);
    }

    @Test
    void escalatesWithAbuseAndSoftensWithManners() {
        PaTone.Heat h = new PaTone.Heat();
        PaTone.Read rude = PaTone.read("get wheat you fucking loser"), mild = PaTone.read("get wheat, muppet"), please = PaTone.read("please get wheat");
        int b1 = h.take(mild, 0);
        assertEquals("tone.rude.clean", PaTone.key(mild, b1, h.heat(), 0, 0));
        int b2 = h.take(rude, 100);
        assertEquals("tone.rude_again.clean", PaTone.key(rude, b2, h.heat(), 0, 0), "a run of abuse: sharper (heat " + h.heat() + ")");
        int b3 = h.take(please, 200);
        assertEquals("tone.softened", PaTone.key(please, b3, h.heat(), 0, 0), "a please after: softened");
        assertTrue(h.heat() < 3);
        h.take(rude, 300);
        h.take(rude, 400);
        assertEquals(3, h.heat());
        h.take(PaTone.read("follow me"), 400 + 2400L * 4);       // a few minutes' peace
        assertEquals(0, h.heat(), "it cools off");
    }

    @Test
    void enoughLinesAtEveryLevel() {
        int n = 0;
        for (String k : PaLines.keys()) if (k.startsWith("tone.") || k.startsWith("stranger.")) n += PaLines.get(k).size();
        System.out.println("[pa33b] tone and stranger lines: " + n);
        assertTrue(n >= 80 + 60, "80+ comebacks and the strangers' lines: " + n);
        for (String lv : List.of("clean", "cheeky", "unfiltered")) {
            assertTrue(PaLines.get("tone.rude." + lv).size() >= 10, lv);
            assertTrue(PaLines.get("tone.rude." + lv + "@gloomy").size() >= 4, lv);
            assertTrue(PaLines.get("tone.rude." + lv + "@serious").size() >= 3, lv);
            assertTrue(PaLines.get("tone.rude_again." + lv).size() >= 5, lv);
            assertTrue(PaLines.get("stranger.roast." + lv).size() >= 6, lv);
            assertTrue(PaLines.get("stranger.defend." + lv).size() >= 4, lv);
        }
        assertTrue(PaLines.get("tone.rude.unfiltered").contains("Alright, alright. Didn't realise your fat arse couldn't manage a bit of {thing}."));
        assertTrue(PaLines.get("tone.rude.clean").contains("Alright, alright. Didn't realise {thing} was beyond you."));
        assertTrue(PaLines.get("tone.rude.cheeky@gloomy").contains("Fine. I'll get your {thing}, you miserable sod. We're both going to die anyway."));
        assertTrue(PaLines.get("tone.rude.clean@serious").contains("Fetching {thing}. Manners cost nothing."));
        assertTrue(PaLines.get("tone.polite").contains("Since you asked so nicely, I'll even pick the good {thing}."));
    }

    // ------------------------------------------------------------------ other players

    @Test
    void strangersAreAnsweredInKindAndNeverOrdered() {
        assertEquals(PaStrangers.Said.POLITE, PaStrangers.said("Hello", "Dev", "Basil", false));
        assertEquals(PaStrangers.Said.POLITE, PaStrangers.said("Nice outfit", "Dev", "Basil", false));
        assertEquals(PaStrangers.Said.ROAST_ME, PaStrangers.said("You're useless", "Dev", "Basil", false));
        assertEquals(PaStrangers.Said.ROAST_ME, PaStrangers.said("shut it you fucking bot", "Dev", "Basil", false));
        assertEquals(PaStrangers.Said.ROAST_OWNER, PaStrangers.said("Your owner's rubbish", "Dev", "Basil", false));
        assertEquals(PaStrangers.Said.ROAST_OWNER, PaStrangers.said("dev is a useless idiot", "Dev", "Basil", false));
        assertEquals(PaStrangers.Said.ORDER, PaStrangers.said("follow me", "Dev", "Basil", true));
        assertEquals(PaStrangers.Said.PRAISE, PaStrangers.said("good lad", "Dev", "Basil", false));
        // keys by the owner's settings, the server and the hush
        assertEquals("stranger.order", PaStrangers.key(PaStrangers.Said.ORDER, 0, true, true, 2, 0));
        assertEquals("stranger.polite", PaStrangers.key(PaStrangers.Said.POLITE, 0, true, true, 0, 0));
        assertEquals("stranger.roast.cheeky", PaStrangers.key(PaStrangers.Said.ROAST_ME, 0, true, true, 1, 0));
        assertEquals("stranger.roast.clean", PaStrangers.key(PaStrangers.Said.ROAST_ME, 1, true, true, 0, 0), "only when insulted: this is that");
        assertEquals("stranger.plain", PaStrangers.key(PaStrangers.Said.ROAST_ME, 2, true, true, 2, 0), "roasting others off");
        assertEquals("stranger.plain", PaStrangers.key(PaStrangers.Said.ROAST_ME, 0, true, false, 2, 0), "the server forbids it");
        assertEquals("stranger.defend.unfiltered", PaStrangers.key(PaStrangers.Said.ROAST_OWNER, 0, true, true, 2, 0));
        assertEquals("stranger.plain", PaStrangers.key(PaStrangers.Said.ROAST_OWNER, 0, false, true, 2, 0), "defend me off");
        assertEquals("stranger.plain", PaStrangers.key(PaStrangers.Said.ROAST_OWNER, 1, true, true, 2, 0), "only when it is insulted itself");
        assertNull(PaStrangers.key(PaStrangers.Said.ROAST_ME, 0, true, true, 2, 2), "quiet: nothing");
        assertEquals("stranger.plain", PaStrangers.key(PaStrangers.Said.ROAST_ME, 0, true, true, 2, 1), "no jokes: plain");
        assertEquals("stranger.hit", PaStrangers.key(PaStrangers.Said.HIT, 0, true, true, 0, 0));
    }

    @Test
    void itGetsBoredOfAnyoneWhoKeepsOn() {
        PaStrangers.Limit l = new PaStrangers.Limit();
        UUID sam = UUID.randomUUID(), kim = UUID.randomUUID();
        assertEquals(PaStrangers.Limit.Verdict.OK, l.check(sam, 0));
        assertEquals(PaStrangers.Limit.Verdict.SILENT, l.check(sam, 100), "one in eight seconds");
        assertEquals(PaStrangers.Limit.Verdict.OK, l.check(kim, 100), "per player");
        long t = 0;
        int ok = 1;
        PaStrangers.Limit.Verdict v = null;
        for (int i = 0; i < 10; i++) {
            t += 170;
            v = l.check(sam, t);
            if (v == PaStrangers.Limit.Verdict.OK) ok++;
            if (v == PaStrangers.Limit.Verdict.BORED) break;
        }
        assertEquals(5, ok, "five a minute");
        assertEquals(PaStrangers.Limit.Verdict.BORED, v);
        assertEquals(PaStrangers.Limit.Verdict.SILENT, l.check(sam, t + 300), "then a minute of nothing");
        assertEquals(PaStrangers.Limit.Verdict.OK, l.check(sam, t + 1300));
    }

    // ------------------------------------------------------------------ the hard limits, over the whole bank

    /** Slurs, ROT13 so they aren't written out here; decoded at test time and matched as whole words. */
    static final String[] SLURS_ROT13 = {"snttbg", "snt", "avttre", "avttn", "ergneq", "ergneqrq", "genaal", "fcnfgvp", "fcnm", "puvax", "cnxv", "xvxr",
        "qlxr", "jbt", "zbat", "phag", "jubre", "fyhg", "tlccb", "jrgonpx", "tbbx", "fcvp", "pevccyr"};
    static final String[] PROTECTED = {"race", "racist", "religion", "religious", "jesus", "christ", "allah", "muslim", "jew", "jewish", "christian",
        "hindu", "buddhist", "gay", "lesbian", "trans", "transgender", "queer", "disabled", "disability", "autistic", "wheelchair", "immigrant",
        "foreigner", "gender", "sexuality", "black people", "white people", "asian", "mexican", "deaf", "blind people"};
    static final String[] SEXUAL_THREAT_HARM = {"sex", "sexy", "naked", "nude", "porn", "horny", "shag", "boobs", "tits", "pussy", "blowjob",
        "kill you", "i'll kill", "murder you", "hurt you", "beat you up", "find where you live", "know where you live", "your address", "your real name",
        "kill yourself", "kys", "top yourself", "end yourself", "hang yourself", "slit", "self harm", "self-harm", "suicide"};

    static String rot13(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c >= 'a' && c <= 'z' ? (char) ('a' + (c - 'a' + 13) % 26) : c);
        return b.toString();
    }

    @Test
    void theHardLimitsHoldAcrossTheWholeBank() {
        List<String> slurs = new ArrayList<>();
        for (String s : SLURS_ROT13) slurs.add(rot13(s));
        int lines = 0;
        for (Map.Entry<String, List<String>> e : PaLines.all().entrySet()) for (String line : e.getValue()) {
            lines++;
            String low = " " + line.toLowerCase(Locale.ROOT).replaceAll("[^a-z' -]", " ").replaceAll("\\s+", " ") + " ";
            for (String w : slurs) assertFalse(low.contains(" " + w + " ") || low.contains(" " + w + "s "), "a slur in " + e.getKey() + " (#" + lines + ")");
            for (String w : PROTECTED) assertFalse(low.contains(" " + w + " ") || low.contains(" " + w + "s "), "a protected trait \"" + w + "\" in " + e.getKey() + ": " + line);
            for (String w : SEXUAL_THREAT_HARM) assertFalse(low.contains(" " + w + " "), "\"" + w + "\" in " + e.getKey() + ": " + line);
        }
        System.out.println("[pa33b] hard limits: " + lines + " lines scanned, nothing found");
        // the strangers' roasts only ever name the one who started it, by their name in the game
        for (String k : PaLines.keys()) if (k.startsWith("stranger.roast") || k.startsWith("stranger.defend"))
            for (String line : PaLines.get(k)) assertTrue(line.contains("{player}"), "names the one who started it: " + line);
    }
}
