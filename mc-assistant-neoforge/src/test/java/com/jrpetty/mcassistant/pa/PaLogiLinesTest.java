package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa36] The logistics lines: six or more at every corner for every moment, dry by default (cheese only behind [daft]), never shouting. */
class PaLogiLinesTest {

    static Set<String> keys() {
        Set<String> k = new TreeSet<>();
        for (String key : PaLines.keys()) {
            if (!(key.startsWith("logi.") || key.startsWith("craft."))) continue;
            k.add(key.contains("@") ? key.substring(0, key.indexOf('@')) : key);
        }
        return k;
    }

    @Test
    void everyMomentHasSixAtEveryCorner() {
        Set<String> k = keys();
        assertTrue(k.size() >= 40, "logistics moments: " + k.size());
        for (String key : k) {
            for (String corner : List.of("", "gloomy", "serious")) {
                List<String> l = PaLogiTalk.lines(key, corner, false);
                assertTrue(l.size() >= 6, key + "@" + (corner.isEmpty() ? "funny" : corner) + " has " + l.size() + " plain lines");
            }
        }
    }

    @Test
    void dryByDefault() {
        int daft = 0, all = 0;
        for (String key : keys()) {
            for (String corner : List.of("", "gloomy", "serious")) {
                List<String> withDaft = PaLogiTalk.lines(key, corner, true), plain = PaLogiTalk.lines(key, corner, false);
                all += withDaft.size();
                daft += withDaft.size() - plain.size();
                for (String s : plain) {
                    assertFalse(s.contains("!"), "no shouting: " + s);
                    assertFalse(PaLines.tags(s).contains("daft"), "daft line in the plain list: " + s);
                }
            }
        }
        assertTrue(daft > 0 && daft * 100 / all <= 10, "a few daft lines, kept behind the setting: " + daft + " of " + all);
    }

    @Test
    void nothingDark() {
        for (String key : keys()) for (String corner : List.of("", "gloomy", "serious"))
            for (String s : PaLogiTalk.lines(key, corner, true)) {
                String l = s.toLowerCase();
                for (String bad : List.of("suicide", "kill myself", "self-harm", "self harm", "want to die", "end it all", "depress", "cut myself", "hang myself"))
                    assertFalse(l.contains(bad), "kept clean: " + s);
            }
    }
}
