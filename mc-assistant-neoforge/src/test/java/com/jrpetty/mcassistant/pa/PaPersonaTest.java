package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa33b] Its personality triangle, its written lines, and its sense of humour's rules. */
class PaPersonaTest {

    /** Words it must never say (swearing, crude, and anything near self-harm, even in jest). "damn" is allowed only at full banter. */
    static final List<String> BANNED = List.of("fuck", "shit", "bitch", "bastard", "arse", "crap", "bloody", "wank", "piss", "cock", "dick",
        "sex", "sexy", "boob", "naked", "porn", "kill myself", "suicide", "suicidal", "end it all", "end my life", "want to die", "wish i was dead",
        "wish i were dead", "wish i didn't exist", "wish i didnt exist", "don't want to exist", "dont want to exist", "self harm", "self-harm",
        "cut myself", "hurt myself", "no reason to live", "not worth living", "better off dead", "overdose", "hang myself", "kill me",
        "depressed", "depression", "oopsie", "retard", "gay", "fat");

    static List<String> allLines() {
        List<String> out = new java.util.ArrayList<>();
        for (List<String> l : PaLines.all().values()) out.addAll(l);
        return out;
    }

    @Test
    void theLinesLoadAndAreCounted() {
        int total = 0, funny = 0, gloomy = 0, edges = 0, looks = 0;
        for (Map.Entry<String, List<String>> e : PaLines.all().entrySet()) {
            String k = e.getKey();
            int n = e.getValue().size();
            total += n;
            if (k.startsWith("look.") || k.startsWith("self.")) looks += n;
            else if (k.contains("@gloomy")) gloomy += n;
            else if (k.matches(".*@(sf|sg|fg)$")) edges += n;
            else if (!k.contains("@")) funny += n;
        }
        System.out.printf("[pa33b] lines: %d in all; Funny %d, Gloomy %d, edges %d, look and self-aware %d, keys %d%n", total, funny, gloomy, edges, looks, PaLines.keys().size());
        assertTrue(total > 1200, "a big bank: " + total);
        assertTrue(funny >= 600, "600+ Funny lines for the Joke Machine: " + funny);
    }

    @Test
    void everySituationHasEnoughLines() {
        // every reply has 8+ Funny lines; the key moments 8+ Gloomy and 6+ on each edge
        for (String k : PaPhrases.keys()) {
            if (PaPhrases.DANGER.contains(k)) continue;
            assertTrue(PaLines.get("say." + k).size() >= 8, "Funny lines for " + k + ": " + PaLines.get("say." + k).size());
        }
        for (String k : List.of("say.greet", "say.ok", "say.done", "say.night", "say.diamonds", "quip.rain", "quip.idle", "death.generic")) {
            assertTrue(PaLines.get(k + "@gloomy").size() >= 8, "Gloomy lines for " + k);
            for (String e : List.of("sf", "sg", "fg")) assertTrue(PaLines.get(k + "@" + e).size() >= 6, k + "@" + e);
        }
        for (String c : List.of("lava", "fall", "drown", "creeper", "skeleton", "zombie", "generic", "repeat", "diamonds")) {
            assertTrue(PaLines.get("death." + c).size() >= 10, "10+ for death by " + c);
        }
        for (String c : List.of("enderman", "starve", "suffocate", "fire", "cactus", "berry", "void", "warden", "villager", "golem", "bed")) {
            assertTrue(PaLines.get("death." + c).size() >= 8, "8+ for death by " + c);
        }
        for (String q : List.of("back_short", "back_long", "morning", "nightlogin", "fell", "low_health", "wandered", "dirt", "asked_twice",
            "slow_answer", "creeper", "village", "nether", "hit", "rain", "idle")) assertTrue(PaLines.get("quip." + q).size() >= 8, "quip." + q);
        for (String j : List.of("mine", "place", "jump", "eat", "chest", "idle", "weather", "progress")) assertTrue(PaLines.get("jm." + j).size() >= 10, "jm." + j);
        assertTrue(PaLines.get("joke").size() >= 25);
    }

    @Test
    void everyLookHasItsOwnLines() {
        for (String lk : PaPersona.LOOK_KEYS) {
            int themed = PaLines.get("look." + lk).size(), self = PaLines.get("self." + lk).size();
            assertTrue(themed + self >= 15, lk + ": " + (themed + self));
            assertTrue(self >= 6, lk + " self-aware: " + self);
        }
        assertTrue(PaLines.get("self.skeleton").size() >= 20, "the skeleton's 20+ about itself");
        for (String mob : List.of("zombie", "creeper", "enderman")) assertTrue(PaLines.get("self." + mob).size() >= 15, mob);
    }

    @Test
    void nothingBannedAnywhere() {
        for (String s : allLines()) {
            String low = " " + s.toLowerCase(Locale.ROOT).replaceAll("[^a-z' -]", " ") + " ";
            for (String b : BANNED) assertFalse(low.contains(" " + b + " ") || low.contains(" " + b + "s "), "banned \"" + b + "\" in: " + s);
        }
    }

    @Test
    void wittyNotCorny() {
        // no exclamation marks in the bank (the cheesiness rule), and "damn" only in the look lines at full banter
        for (Map.Entry<String, List<String>> e : PaLines.all().entrySet()) {
            for (String s : e.getValue()) {
                assertFalse(s.contains("!"), "an exclamation mark in " + e.getKey() + ": " + s);
                if (s.toLowerCase(Locale.ROOT).contains("damn")) assertTrue(e.getKey().startsWith("self."), "damn outside a look line: " + s);
            }
        }
    }

    @Test
    void barycentricAndPresets() {
        double ax = 50, ay = 0, bx = 0, by = 86.6, cx = 100, cy = 86.6;
        PaPersona.Tri top = PaPersona.fromPoint(ax, ay, ax, ay, bx, by, cx, cy);
        assertEquals(1.0, top.serious(), 1e-6);
        PaPersona.Tri left = PaPersona.fromPoint(bx, by, ax, ay, bx, by, cx, cy);
        assertEquals(1.0, left.funny(), 1e-6);
        PaPersona.Tri mid = PaPersona.fromPoint((ax + bx + cx) / 3, (ay + by + cy) / 3, ax, ay, bx, by, cx, cy);
        assertEquals(1.0 / 3, mid.gloomy(), 1e-6);
        double[] p = PaPersona.toPoint(PaPersona.Tri.of(0.5, 0.5), ax, ay, bx, by, cx, cy);
        PaPersona.Tri back = PaPersona.fromPoint(p[0], p[1], ax, ay, bx, by, cx, cy);
        assertEquals(0.5, back.funny(), 1e-6);
        assertEquals(0.5, back.gloomy(), 1e-6);
        PaPersona.Tri outside = PaPersona.fromPoint(-50, 200, ax, ay, bx, by, cx, cy);
        assertEquals(1.0, outside.serious() + outside.funny() + outside.gloomy(), 1e-6);
        assertEquals("Professional", PaPersona.preset("Professional").label());
        assertEquals("Joker", PaPersona.preset("Joker").label());
        assertEquals("Doom merchant", PaPersona.preset("Doom").label());
        assertEquals("Balanced", PaPersona.preset("Balanced").label());
        assertEquals("Banter", PaPersona.preset("Banter").label());
        assertEquals("Sardonic", PaPersona.Tri.of(0.5, 0.5).label());
        assertEquals("Weary", PaPersona.Tri.of(0.0, 0.5).label());
        assertEquals("Deadpan", PaPersona.Tri.of(0.5, 0.0).label());
    }

    @Test
    void lineSourcesFollowTheWeights() {
        PaPersona.Tri t = new PaPersona.Tri(0.2, 0.5, 0.3);
        Random r = new Random(4);
        int[] n = new int[3];
        for (int i = 0; i < 1000; i++) n[PaPersona.corner(t, r::nextInt).ordinal()]++;
        System.out.printf("[pa33b] corners over 1000 at (0.2, 0.5, 0.3): serious %d funny %d gloomy %d%n", n[0], n[1], n[2]);
        assertEquals(200, n[0], 45);
        assertEquals(500, n[1], 50);
        assertEquals(300, n[2], 45);
    }

    @Test
    void jokeMachineDoesNotRepeatWithin300() {
        PaPersona.Tri joker = PaPersona.preset("Joker");
        PaPersona.Recent recent = new PaPersona.Recent(400);
        Random r = new Random(9);
        List<String> said = new java.util.ArrayList<>();
        String[] keys = {"jm.mine", "jm.place", "jm.jump", "jm.eat", "jm.chest", "jm.idle", "jm.weather", "jm.progress"};
        for (int i = 0; i < 300; i++) said.add(PaPersona.remark(keys[i % keys.length], joker, 13, null, r::nextInt, recent));
        Set<String> uniq = new HashSet<>(said);
        System.out.printf("[pa33b] Joke Machine: %d remarks, %d different%n", said.size(), uniq.size());
        assertEquals(said.size(), uniq.size(), "no repeat within 300 picks");
    }

    @Test
    void noRepeatWithinAListsLength() {
        PaPersona.Recent recent = new PaPersona.Recent(400);
        Random r = new Random(2);
        List<String> l = PaLines.get("death.fall");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < l.size(); i++) assertTrue(seen.add(PaPersona.from(l, r::nextInt, recent)), "repeat inside " + l.size());
    }

    @Test
    void lookLinesKeepToTheirShare() {
        PaPersona.Tri banter = PaPersona.preset("Banter");
        Random r = new Random(5);
        Set<String> lookLines = new HashSet<>(PaLines.get("look.knight"));
        lookLines.addAll(PaLines.get("self.knight"));
        int look = 0;
        for (int i = 0; i < 1000; i++) {
            String s = PaPersona.remark("quip.idle", banter, 4, null, r::nextInt, null);
            if (lookLines.contains(s)) look++;
        }
        System.out.printf("[pa33b] look lines: %d of 1000 remarks%n", look);
        assertTrue(look <= 220 && look >= 80, "about one in five, never more: " + look);
    }

    @Test
    void dangerIsNeverAJoke() {
        // warnings are said plainly at every corner, never from the joke bank
        for (String k : PaPhrases.DANGER) {
            assertTrue(PaLines.get("say." + k).isEmpty(), "no jokes written in place of " + k);
            for (PaPersona.Tri t : List.of(PaPersona.preset("Joker"), PaPersona.preset("Doom"), PaPersona.preset("Balanced"))) {
                String s = PaPhrases.line(k, t, new Random(1)::nextInt, null, "owner", "Alex", "what", "deal with that zombie");
                assertEquals(PaPhrases.line(k, PaPhrases.Personality.CALM, new Random(1)::nextInt, "owner", "Alex", "what", "deal with that zombie"), s);
            }
        }
    }

    @Test
    void theSkeletonKnowsItsCousins() {
        Random r = new Random(3);
        int hits = 0;
        for (int i = 0; i < 40; i++) {
            String s = PaPersona.remark("death.skeleton", PaPersona.preset("Banter"), 14, "skeleton", r::nextInt, null);
            if (PaLines.get("self.skeleton@on.skeleton").contains(s)) hits++;
        }
        assertTrue(hits > 5, "its moment's own lines come up: " + hits);
        // and a gloomy skeleton is a weary one
        PaPersona.Recent rec = new PaPersona.Recent(10);
        boolean weary = false;
        for (int i = 0; i < 400 && !weary; i++) weary = PaLines.get("self.skeleton@gloomy").contains(PaPersona.remark("quip.idle", PaPersona.preset("Doom"), 14, null, r::nextInt, rec));
        assertTrue(weary);
    }

    @Test
    void comebacksAreRecognised() {
        String[][] cases = {
            {"you're useless", "INSULT"}, {"ur usless", "INSULT"}, {"you are rubbish", "INSULT"}, {"youre rubish", "INSULT"}, {"you muppet", "INSULT"},
            {"what a numpty", "INSULT"}, {"absolute plonker", "INSULT"}, {"you're pathetic", "INSULT"}, {"waste of space", "INSULT"}, {"you idiot", "INSULT"},
            {"youre stupid", "INSULT"}, {"you donkey", "INSULT"}, {"you wally", "INSULT"}, {"youre hopeless", "INSULT"}, {"you're terible", "INSULT"},
            {"shut up", "SHUTUP"}, {"shutup", "SHUTUP"}, {"shut uo", "SHUTUP"}, {"pipe down", "SHUTUP"}, {"zip it", "SHUTUP"}, {"give it a rest", "SHUTUP"},
            {"good lad", "PRAISE"}, {"good ladd", "PRAISE"}, {"you absolute legend", "PRAISE"}, {"legnd", "PRAISE"}, {"well done", "PRAISE"},
            {"nice work", "PRAISE"}, {"top man", "PRAISE"}, {"cracking job", "PRAISE"}, {"love you", "LOVE"}, {"luv u", "LOVE"}, {"i love you", "LOVE"},
            {"you're the best", "LOVE"}, {"tell me a joke", "JOKE"}, {"tel me a joke", "JOKE"}, {"make me laugh", "JOKE"}, {"got any jokes", "JOKE"},
        };
        int ok = 0;
        StringBuilder miss = new StringBuilder();
        for (String[] c : cases) {
            PaQuips.Comeback got = PaQuips.comeback(c[0]);
            if (got != null && got.name().equals(c[1])) ok++;
            else miss.append(c[0]).append(" -> ").append(got).append("; ");
        }
        System.out.printf("[pa33b] comebacks: %d/%d%n", ok, cases.length);
        assertTrue(ok >= cases.length - 1, miss.toString());
        for (String plain : List.of("follow me", "i think we need logs", "get me 16 logs", "build a house", "mine some iron", "start mining", "come here"))
            assertNull(PaQuips.comeback(plain), "an order is not lip: " + plain);
    }

    @Test
    void deathCausesPickTheirLines() {
        Random r = new Random(1);
        assertEquals("repeat", PaQuips.deathKey("lava", true, false, true, r::nextInt));
        assertEquals("lava", PaQuips.deathKey("lava", false, false, false, r::nextInt));
        int bed = 0;
        for (int i = 0; i < 200; i++) if (PaQuips.deathKey("fall", false, true, false, r::nextInt).equals("bed")) bed++;
        assertTrue(bed > 40 && bed < 120, "near the bed sometimes: " + bed);
        assertTrue(PaQuips.lost("lava") && PaQuips.lost("void") && !PaQuips.lost("fall"));
    }

    @Test
    void newAssistantsStartAtBanter() {
        assertEquals("Banter", PaPersona.Tri.of(PaPersona.DEFAULT_FUNNY, PaPersona.DEFAULT_GLOOMY).label());
    }

    @Test
    void fillLeavesNoBlanks() {
        for (String s : allLines()) {
            String f = PaPersona.fill(s, Map.of("owner", "Alex", "name", "Pip", "did", "Built a small house", "what", "I'm out of coal.", "item", "iron"));
            assertFalse(f.contains("{") || f.contains("}"), "a blank left in: " + f);
        }
        assertNotNull(PaLines.get("say.ok"));
    }
}
