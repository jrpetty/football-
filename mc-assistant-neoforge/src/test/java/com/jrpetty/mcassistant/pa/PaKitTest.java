package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [featpa] Blueprints, errands and stances: the lines (six or more at every corner, dry by default, never dark), and the
 *  blueprint's turn-and-mirror sums (every cell lands once, in the box, four turns come home, a turn matches the game's). */
class PaKitTest {

    static Set<String> keys() {
        Set<String> k = new TreeSet<>();
        for (String key : PaLines.keys()) if (key.startsWith("kit.")) k.add(key.contains("@") ? key.substring(0, key.indexOf('@')) : key);
        return k;
    }

    @Test
    void everyMomentHasSixAtEveryCorner() {
        Set<String> k = keys();
        assertTrue(k.size() >= 50, "kit moments: " + k.size());
        for (String key : k) for (String corner : List.of("", "gloomy", "serious")) {
            List<String> l = PaLogiTalk.lines(key, corner, false);
            assertTrue(l.size() >= 6, key + "@" + (corner.isEmpty() ? "funny" : corner) + " has " + l.size() + " plain lines");
            assertEquals(l.size(), new HashSet<>(l).size(), key + "@" + corner + " says the same line twice");
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
                for (String bad : List.of("suicide", "kill myself", "self-harm", "self harm", "want to die", "end it all", "depress", "cut myself", "hang myself",
                        "hurt myself", "kill me", " die", "dead inside"))
                    assertFalse(l.contains(bad), "kept clean: " + s);
            }
            for (String s : plain) assertFalse(PaPersona.corny(s), "corny line in the plain list: " + s);
            if (corner.equals("serious")) for (String s : withDaft) assertFalse(PaPersona.corny(s), "the plain corner is never daft: " + s);
        }
        assertTrue(daft > 0 && daft * 100 / all <= 10, "a few daft lines, behind the setting: " + daft + " of " + all);
    }

    @Test
    void theBlanksAreOnesTheCodeFills() {
        java.util.regex.Pattern blank = java.util.regex.Pattern.compile("\\{(\\w+)}");
        Set<String> known = Set.of("item", "n", "what", "need", "spent", "owner", "name");
        for (String key : keys()) for (String corner : List.of("", "gloomy", "serious"))
            for (String s : PaLogiTalk.lines(key, corner, true)) {
                java.util.regex.Matcher m = blank.matcher(s);
                while (m.find()) assertTrue(known.contains(m.group(1)), key + ": unknown blank {" + m.group(1) + "} in " + s);
            }
    }

    @Test
    void aTurnLandsEveryCellOnceInsideTheBox() {
        for (int sx = 1; sx <= 6; sx++) for (int sz = 1; sz <= 5; sz++) for (int rot = 0; rot < 4; rot++) for (boolean mirror : new boolean[]{false, true}) {
            int[] f = PaTurn.size(sx, sz, rot);
            Set<Long> seen = new HashSet<>();
            for (int x = 0; x < sx; x++) for (int z = 0; z < sz; z++) {
                int[] m = PaTurn.map(x, z, sx, sz, mirror, rot);
                assertTrue(m[0] >= 0 && m[0] < f[0] && m[1] >= 0 && m[1] < f[1], "in the box: " + sx + "x" + sz + " r" + rot + " m" + mirror);
                assertTrue(seen.add(((long) m[0] << 32) | m[1]), "each cell once");
            }
            assertEquals(sx * sz, seen.size());
        }
    }

    @Test
    void aQuarterTurnTakesNorthToEastAsTheGameTurnsAFacing() {
        // a step north (z-1) inside a 5x5 box, from its middle, turned a quarter clockwise: a step east (x+1)
        int[] mid = PaTurn.map(2, 2, 5, 5, false, 1), north = PaTurn.map(2, 1, 5, 5, false, 1);
        assertArrayEquals(new int[]{1, 0}, new int[]{north[0] - mid[0], north[1] - mid[1]});
        // mirrored: east and west swap, north stays north
        int[] m0 = PaTurn.map(2, 2, 5, 5, true, 0), east = PaTurn.map(3, 2, 5, 5, true, 0), nm = PaTurn.map(2, 1, 5, 5, true, 0);
        assertArrayEquals(new int[]{-1, 0}, new int[]{east[0] - m0[0], east[1] - m0[1]});
        assertArrayEquals(new int[]{0, -1}, new int[]{nm[0] - m0[0], nm[1] - m0[1]});
        // four quarter turns: home again
        for (int x = 0; x < 4; x++) for (int z = 0; z < 3; z++) {
            int sx = 4, sz = 3, cx = x, cz = z;
            for (int i = 0; i < 4; i++) {
                int[] m = PaTurn.map(cx, cz, sx, sz, false, 1);
                int t = sx; sx = sz; sz = t;
                cx = m[0]; cz = m[1];
            }
            assertArrayEquals(new int[]{x, z}, new int[]{cx, cz});
        }
    }
}
