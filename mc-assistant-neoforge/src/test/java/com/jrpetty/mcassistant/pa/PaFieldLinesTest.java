package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa38] Its rod, throws, mace, table, trades and slips: six or more at every corner, dry by default, never dark. */
class PaFieldLinesTest {

    static Set<String> keys() {
        Set<String> k = new TreeSet<>();
        for (String key : PaLines.keys()) if (key.startsWith("field.")) k.add(key.contains("@") ? key.substring(0, key.indexOf('@')) : key);
        return k;
    }

    @Test
    void everyMomentHasSixAtEveryCorner() {
        Set<String> k = keys();
        assertTrue(k.size() >= 45, "field moments: " + k.size());
        for (String key : k) for (String corner : List.of("", "gloomy", "serious")) {
            List<String> l = PaLogiTalk.lines(key, corner, false);
            assertTrue(l.size() >= 6, key + "@" + (corner.isEmpty() ? "funny" : corner) + " has " + l.size() + " plain lines");
        }
    }

    @Test
    void dryByDefaultAndCleanAlways() {
        int daft = 0, all = 0;
        for (String key : keys()) for (String corner : List.of("", "gloomy", "serious")) {
            List<String> withDaft = PaLogiTalk.lines(key, corner, true), plain = PaLogiTalk.lines(key, corner, false);
            all += withDaft.size();
            daft += withDaft.size() - plain.size();
            for (String s : withDaft) {
                assertFalse(s.contains("!"), "no shouting: " + s);
                String l = s.toLowerCase();
                for (String bad : List.of("suicide", "kill myself", "self-harm", "self harm", "want to die", "end it all", "depress", "cut myself", "hang myself", "hurt myself", "kill me"))
                    assertFalse(l.contains(bad), "kept clean: " + s);
            }
            for (String s : plain) assertFalse(PaPersona.corny(s), "corny line in the plain list: " + s);
        }
        assertTrue(daft > 0 && daft * 100 / all <= 10, "a few daft lines, behind the setting: " + daft + " of " + all);
    }

    @Test
    void theRefusedChestSaysHowToAllowIt() {
        for (String key : List.of("logi.not_allowed", "logi.not_allowed_valuable", "logi.not_allowed_stock"))
            for (String corner : List.of("", "gloomy", "serious")) {
                List<String> l = PaLogiTalk.lines(key, corner, false);
                assertTrue(l.size() >= 6, key + "@" + corner);
                for (String s : l) assertFalse(s.toLowerCase().contains("had no"), "never 'had no': " + s);
            }
        for (String s : PaLogiTalk.lines("logi.not_allowed", "serious", false))
            assertTrue(s.contains("take from this chest") || s.contains("Take") || s.contains("Storage"), "says how to allow it: " + s);
    }

    @Test
    void theStepAsideHasItsSincereCornerToo() {
        for (String corner : List.of("", "gloomy", "serious", "sincere")) {
            List<String> l = PaLogiTalk.lines("field.sidestep", corner, false);
            assertTrue(l.size() >= 6, "field.sidestep@" + corner + " has " + l.size());
        }
    }
}
