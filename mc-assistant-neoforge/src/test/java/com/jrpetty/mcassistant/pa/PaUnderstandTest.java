package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa33] The Player AI Assistant's reader, over a few hundred phrasings with typos and slang (src/test/resources/pa). */
class PaUnderstandTest {

    static boolean matches(PaUnderstand.Reading r, String exp) {
        exp = exp.trim();
        if (exp.equals("?")) return r.clarify() != null;
        if (r.clarify() != null) return false;
        String[] want = exp.split(" > ");
        if (want.length != r.steps().size()) return false;
        for (int i = 0; i < want.length; i++) {
            String w = want[i];
            PaIntent got = r.steps().get(i);
            String place = null;
            int at = w.indexOf('@');
            if (at >= 0) { place = w.substring(at + 1); w = w.substring(0, at); }
            String[] p = w.split(":");
            if (!got.kind().name().equals(p[0])) return false;
            if (p.length > 1 && !p[1].isEmpty() && !got.item().equals(p[1])) return false;
            if (p.length > 2 && got.amount() != Integer.parseInt(p[2])) return false;
            if (place != null && !got.place().name().equals(place)) return false;
        }
        return true;
    }

    /** Accuracy over a file; the misses are printed. */
    static double score(String file, List<String> misses) throws IOException {
        int n = 0, ok = 0;
        for (String line : Files.readAllLines(Path.of("src/test/resources/pa/" + file), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\\|\\|");
            n++;
            PaUnderstand.Reading r = PaUnderstand.read(f[0].trim(), "pip");
            if (matches(r, f[1])) ok++;
            else misses.add(f[0].trim() + "  want " + f[1].trim() + "  got " + r.brief());
        }
        double acc = 100.0 * ok / n;
        System.out.printf("[pa33] %s: %d/%d = %.1f%%%n", file, ok, n, acc);
        for (String m : misses) System.out.println("[pa33]   miss: " + m);
        return acc;
    }

    /** [pa38] Its rod, throws, mace, cure and bookshelves, as players say them (every one read). */
    @Test
    void fieldOrdersAreRead() throws IOException {
        List<String> misses = new ArrayList<>();
        assertTrue(score("field.txt", misses) >= 100.0, "field orders: " + misses);
    }

    @Test
    void phrasingsAreRead() throws IOException {
        List<String> misses = new ArrayList<>();
        double acc = score("phrasings.txt", misses);
        assertTrue(acc >= 97.0, "phrasings accuracy " + acc + ": " + misses);
    }

    @Test
    void heldOutPhrasingsAreRead() throws IOException {
        List<String> misses = new ArrayList<>();
        double acc = score("heldout.txt", misses);
        assertTrue(acc >= 90.0, "held-out accuracy " + acc + ": " + misses);
    }

    /** [pa33b] British English phrasings (first, untuned pass 29/53 = 54.7%). */
    @Test
    void britishPhrasingsAreRead() throws IOException {
        List<String> misses = new ArrayList<>();
        double acc = score("en_gb.txt", misses);
        assertTrue(acc >= 95.0, "British phrasings accuracy " + acc + ": " + misses);
    }

    /** [pa33b] Every British saying in the recognisers' phrase list is itself understood. */
    @Test
    void britishSayingsInTheGrammarAreUnderstood() {
        for (String b : PaUnderstand.BRITISH_SAYINGS) {
            assertTrue(PaUnderstand.read(b, "pip").understood(), "British saying not understood: " + b + " -> " + PaUnderstand.read(b, "pip").brief());
        }
    }

    @Test
    void neverSilent() {
        for (String s : List.of("", "   ", "?", "blargh", "the", "and then", "zzzz zzzz", "build", "mine", "gather unobtainium")) {
            PaUnderstand.Reading r = PaUnderstand.read(s, "pip");
            assertTrue(!r.steps().isEmpty() || r.clarify() != null, "silent on '" + s + "'");
            if (r.clarify() != null) {
                assertFalse(r.clarify().choices().isEmpty(), "a question with no choices for '" + s + "'");
                for (PaUnderstand.Choice c : r.clarify().choices()) {
                    PaUnderstand.Reading again = PaUnderstand.read(c.say(), "pip");
                    assertTrue(again.understood(), "the choice '" + c.say() + "' is not itself understood");
                }
            }
        }
    }

    @Test
    void everyExampleIsUnderstood() {
        for (String e : PaUnderstand.EXAMPLES) {
            PaUnderstand.Reading r = PaUnderstand.read(e, "pip");
            assertTrue(r.understood(), "example not understood: " + e + " -> " + r.brief());
        }
        for (PaUnderstand.Choice c : PaUnderstand.starters()) assertTrue(PaUnderstand.read(c.say(), "pip").understood());
    }

    @Test
    void addressedByName() {
        assertEquals("FOLLOW", PaUnderstand.read("Pip, follow me", "pip").brief());
        assertEquals("COME@ME", PaUnderstand.read("hey pip come here", "pip").brief());
    }

    @Test
    void multiStepKeepsOrderAndPlaces() {
        PaUnderstand.Reading r = PaUnderstand.read(
            "chop 64 logs, then make planks and sticks, then build a crafting table by my house", "pip");
        assertEquals("GATHER:logs:64 > CRAFT:planks > CRAFT:sticks > CRAFT:crafting table@HOME", r.brief());
    }

    @Test
    void autocomplete() {
        List<String> c = PaUnderstand.complete("bui", 4);
        assertFalse(c.isEmpty());
        assertTrue(c.get(0).startsWith("build"));
        assertTrue(PaUnderstand.complete("", 4).isEmpty());
        assertNotNull(PaUnderstand.complete("zzzz", 4));
    }

    @Test
    void wordsMadePlain() {
        assertEquals("give me 20 logs", PaWords.normalise("Gimme twenty WOOD!!").replace(" ,", "").trim());
        assertEquals("pickaxe", PaWords.fix("pickaxe"));
        assertEquals("gather", PaWords.fix("gahter"));
        assertEquals("mind", PaWords.fix("mind"));
        assertEquals("125", PaWords.numbers("one hundred and twenty five"));
        assertEquals("32 logs", PaWords.numbers("half a stack logs"));
    }

    /** [pa33c-2b] Its bed: use this one, put one down, where is it; and "go to bed" is still bedtime. */
    @org.junit.jupiter.api.Test
    void bedOrders() {
        String[][] c = {{"use this bed", "BED_USE"}, {"sleep in this bed", "BED_USE"}, {"that's your bed", "BED_USE"}, {"make this your spawn", "BED_USE"},
            {"set your spawn at this bed", "BED_USE"}, {"place a bed here", "BED_PLACE"}, {"put your bed by mine", "BED_PLACE"}, {"put your bed down", "BED_PLACE"},
            {"make yourself a bed", "BED_PLACE"}, {"where's your bed", "BED_WHERE"}, {"where is your bed", "BED_WHERE"}, {"where do you sleep", "BED_WHERE"},
            {"go to bed", "SLEEP"}};
        for (String[] t : c) {
            PaUnderstand.Reading r = PaUnderstand.read(t[0], "pip");
            org.junit.jupiter.api.Assertions.assertTrue(!r.steps().isEmpty() && r.steps().get(0).kind().name().equals(t[1]), t[0] + " -> " + r.brief());
        }
    }

    @Test
    void sleepOrders() {                                                   // [pa33d]
        String[][] c = {{"go to bed", "SLEEP"}, {"time for bed", "SLEEP"}, {"bedtime", "SLEEP"}, {"go to sleep", "SLEEP"}, {"wake up", "WAKE"},
            {"rise and shine", "WAKE"}, {"get up", "WAKE"}, {"wakey wakey", "WAKE"}};
        for (String[] t : c) {
            PaUnderstand.Reading r = PaUnderstand.read(t[0], "pip");
            org.junit.jupiter.api.Assertions.assertTrue(!r.steps().isEmpty() && r.steps().get(0).kind().name().equals(t[1]), t[0] + " -> " + r.brief());
        }
    }

    @Test
    void theRestOfTheGame() {                                              // [pa33d]
        String[][] c = {{"enchant my sword", "ENCHANT:sword"}, {"enchant your stuff", "ENCHANT:items"}, {"get 20 quartz from the nether", "NETHER:quartz:20"},
            {"come to the nether", "NETHER:netherrack:32"}, {"tame that wolf", "TAME:wolf@LOOKED"}, {"tame that", "TAME:any@LOOKED"},
            {"buy 5 bread from that villager", "VTRADE:buy:bread:5@LOOKED"}, {"sell 20 wheat to that villager", "VTRADE:sell:wheat:20@LOOKED"},
            {"trade with that villager", "VTRADE:buy:anything@LOOKED"}, {"upgrade your gear", "UPGRADE"}, {"gear up", "UPGRADE"},
            {"place a lever there", "PLACE_THING:lever:1@LOOKED"}, {"put a door here", "PLACE_THING:door:1@LOOKED"}, {"map this area", "MAP@HERE"},
            {"pillar up 8", "PILLAR:pillar:8"}, {"shoot that target", "SHOOT_AT@LOOKED"}, {"trick shot", "SHOOT_AT@LOOKED"},
            {"race me to that tree", "CHALLENGE:race@LOOKED"}, {"who can mine more in a minute", "CHALLENGE:mine"}, {"shooting contest", "CHALLENGE:shoot@LOOKED"},
            {"shoot that", "FIGHT:monsters@LOOKED"}, {"where's the closest village", "FIND:village"}, {"brew a healing potion", "BREW:healing:3"},
            {"make me some swiftness potions", "BREW:swiftness:3"}, {"brew potions", "BREW:healing:3"}};
        for (String[] t : c) org.junit.jupiter.api.Assertions.assertEquals(t[1], PaUnderstand.read(t[0], "pip").brief(), t[0]);
    }
}
