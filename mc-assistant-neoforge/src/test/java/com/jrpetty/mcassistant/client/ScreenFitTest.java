package com.jrpetty.mcassistant.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [ui29] The emote wheel laid out at every GUI size a player meets (1280x720 at GUI 2 and 3, 1920x1080 at 2, 3 and 4):
 * no box off the screen, none on the hotbar, none on another. Before, the long names at the bottom ("9 Shake hands",
 * "8 Salute") were drawn over each other and the hint line over the hotbar.
 */
class ScreenFitTest {

    /** The wheel's words, numbered as it numbers them; a glyph is at most six pixels wide in the game's font. */
    private static final String[] WORDS = { "1 Wave", "2 Bow", "3 Cheer", "4 Clap", "5 Point", "6 Follow me", "7 Shrug",
        "8 Salute", "9 Shake hands", "Dance", "Sit", "Kneel", "Pray", "Think" };

    private static int[] widths(String[] words) {
        int[] w = new int[words.length];
        for (int i = 0; i < words.length; i++) w[i] = Math.max(66, words[i].length() * 6 + 10);
        return w;
    }

    @Test
    void wheelFitsAtEveryScale() {
        int[] bw = widths(WORDS);
        int boxH = 16;
        for (int[] size : ScreenFit.GUI_SIZES) {
            int width = size[0], height = size[1];
            int[][] at = ScreenFit.ring(width, height, bw, boxH, 150, 96);
            for (int i = 0; i < at.length; i++) {
                int x = at[i][0], y = at[i][1];
                String where = WORDS[i] + " at " + width + "x" + height;
                assertTrue(x - bw[i] / 2 >= 0 && x + bw[i] / 2 <= width, where + ": off the side");
                assertTrue(y - boxH / 2 >= 0, where + ": off the top");
                assertTrue(y + boxH / 2 <= height - ScreenFit.HOTBAR + 8, where + ": on the hotbar");
                // The middle box ("Emotes", 64 wide, 22 high) is the hover readout: a ring box must not sit on it.
                assertFalse(Math.abs(x - width / 2) * 2 < bw[i] + 64 && Math.abs(y - height / 2) < (boxH + 22) / 2, where + ": on the middle");
                for (int k = 0; k < i; k++) {
                    assertFalse(ScreenFit.overlaps(at[k][0], at[k][1], bw[k], x, y, bw[i], boxH - 2), where + " lands on " + WORDS[k]);
                }
            }
        }
    }

    /** [wpa] The Town Hall's tabs, all seven (a leader's Office too), in one row at every GUI size; the longest rows of
     *  buttons on its pages and on the arrival card inside the page. */
    @Test
    void theTownHallFitsAtEveryScale() {
        for (int[] size : ScreenFit.GUI_SIZES) {
            int page = HallLayout.pageWidth(size[0]);
            assertTrue(HallLayout.stripWidth(HallLayout.TABS) <= page - 8, "the hall's tabs at " + size[0] + "x" + size[1] + ": "
                + HallLayout.stripWidth(HallLayout.TABS) + " in " + page);
            String[][] rows = {
                { "Show me the Town Hall", "Your life in town", "The Guide" },
                { "Elections and the ballot", "Stand for election" },
                { "Guilty", "Not guilty", "Show me the court" },
                { "Save", "Withdraw", "Borrow", "Repay" },
                { "Tithe -2", "Tithe +2", "Wages -5", "Wages +5" },
                { "Hire a man (2c a day)", "Release them all" },
                { "Where's my manor?", "A grander manor" },
                { "Not now", "Your life in town" },
                { "Show my name over my head", "The Town Hall" } };
            for (String[] r : rows) {
                assertTrue(HallLayout.buttonsWidth(r) <= HallLayout.rowRoom(size[0]), String.join(" | ", r) + " at " + size[0] + ": "
                    + HallLayout.buttonsWidth(r) + " in " + HallLayout.rowRoom(size[0]));
            }
        }
    }

    /** [wpe] The Record page ("You and the law"): its five tabs in one row, and its longest rows of buttons, at every GUI size. */
    @Test
    void theRecordPageFitsAtEveryScale() {
        String[][] tabs = { { "notice", "Notice" }, { "case", "Case" }, { "jail", "Jail" }, { "record", "Record" }, { "how", "How it works" } };
        String[][] rows = {
            { "Show me the court", "Show me the jail", "Show me the watch house" },
            { "It was an accident", "I wasn't there" },
            { "I was provoked", "I ask for mercy" },
            { "Hire a lawyer (15c)", "Public defender" },
            { "Your case", "Got it" },
            { "Give myself up" },
            { "Pay 120c" } };
        for (int[] size : ScreenFit.GUI_SIZES) {
            int page = HallLayout.pageWidth(size[0]);
            assertTrue(HallLayout.stripWidth(tabs) <= page - 8, "the record's tabs at " + size[0] + ": " + HallLayout.stripWidth(tabs) + " in " + page);
            for (String[] r : rows) {
                assertTrue(HallLayout.buttonsWidth(r) <= HallLayout.rowRoom(size[0]), String.join(" | ", r) + " at " + size[0] + ": "
                    + HallLayout.buttonsWidth(r) + " in " + HallLayout.rowRoom(size[0]));
            }
        }
    }

    @Test
    void aPanelNeverOutgrowsItsScreen() {
        for (int[] size : ScreenFit.GUI_SIZES) {
            assertTrue(ScreenFit.side(600, size[0], 12) <= size[0] - 12);
            assertTrue(ScreenFit.side(360, size[1], 12) <= size[1] - 12);
        }
    }
}
