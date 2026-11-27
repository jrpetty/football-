package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [pa33c-2b] No text runs out of its box: the panel's tabs, its Settings sections and the Pack and Advanced buttons,
 * at every GUI scale the game allows on 1280x720, 1600x900, 1920x1080 and 2560x1440, and each panel size.
 */
class PaLayoutTest {

    private static final int[][] SCREENS = {{1280, 720}, {1600, 900}, {1920, 1080}, {2560, 1440}};

    /** The GUI scales the game offers a screen (each leaves at least 320x240). */
    private static List<int[]> guis() {
        List<int[]> out = new ArrayList<>();
        for (int[] s : SCREENS) {
            int max = Math.min(s[0] / 320, s[1] / 240);
            for (int k = 1; k <= Math.min(4, max); k++) out.add(new int[]{(int) Math.ceil(s[0] / (double) k), (int) Math.ceil(s[1] / (double) k), k, s[0], s[1]});
        }
        return out;
    }

    @Test
    void everyLabelFitsItsBox() {
        List<String> bad = new ArrayList<>();
        int cases = 0;
        for (int[] g : guis()) for (String size : new String[]{"s", "m", "l"}) {
            cases++;
            String at = g[3] + "x" + g[4] + " GUI " + g[2] + " size " + size;
            int w = PaLayout.panelW(g[0], size), h = PaLayout.panelH(g[1], size);
            if (w > g[0] - 8 || h > g[1] - 8) bad.add(at + ": panel off screen");
            if (PaLayout.tabsW(PaLayout.TABS) > w - 12) bad.add(at + ": tabs " + PaLayout.tabsW(PaLayout.TABS) + " > " + (w - 12));
            int list = PaLayout.sectionListW(w);
            for (String sct : PaLayout.SECTIONS) if (PaLayout.textW(sct) > list - 14) bad.add(at + ": section " + sct);
            int half = PaLayout.halfButton(PaLayout.settingsW(w));
            for (String b : PaLayout.ADVANCED_BUTTONS) if (PaLayout.textW(b) > half - 4) bad.add(at + ": " + b + " in " + half);
            int pb = Math.abs(PaLayout.packButton(w));
            for (String b : PaLayout.PACK_BUTTONS) if (PaLayout.textW(b) > pb - 4) bad.add(at + ": " + b + " in " + pb);
            // the Pack tab's grids and gear: 84 + 9 slots + gap inside the page
            if (84 + 9 * 18 > w - 22) bad.add(at + ": pack grids wider than the page");
            // [pa35] the Progress tab: ten level columns, each wide enough for an icon (16) and its padlock, inside the page
            int page = w - 22, col = PaLayout.trackColumn(page);
            if (col * 10 > page) bad.add(at + ": the ladder track " + col * 10 + " > " + page);
            if (col < 18) bad.add(at + ": a level column too narrow for an icon: " + col);
            // [pa35] the Memory tab's buttons, and each auto-help switch under Settings > Behaviour (indented, beside its On/Off)
            int sw = PaLayout.settingsW(w);
            for (String[] k : PaLayout.AUTO_HELP) if (PaLayout.textW(k[1]) > sw - 8 - 44) bad.add(at + ": auto-help " + k[1] + " in " + (sw - 52));
            for (String b : PaLayout.MEMORY_BUTTONS) if (PaLayout.textW(b) + 10 > page / 3) bad.add(at + ": memory " + b);
        }
        assertTrue(cases >= 30, "cases " + cases);
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    @Test
    void fontWidthsAreTheGames() {
        assertTrue(PaLayout.textW("Settings") == 40, "S6 e6 t4 t4 i2 n6 g6 s6: " + PaLayout.textW("Settings"));
        assertTrue(PaLayout.textW("iiii") < PaLayout.textW("mmmm"));
    }
}
