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

    /** [till] The checkout at every GUI size (GUI 2 and 3 on 1280x720, and the rest): the window on the screen, the list of
     *  wares and the basket inside it side by side, room for five rows and a name beside the − and +, the Pay button and its
     *  reason inside the basket, and the stepper's buttons inside the row. */
    @Test
    void theCheckoutFitsAtEveryScale() {
        for (int[] size : ScreenFit.GUI_SIZES) {
            for (boolean tabs : new boolean[]{ false, true }) {
                TillLayout.Layout l = TillLayout.of(size[0], size[1], tabs);
                String at = size[0] + "x" + size[1] + (tabs ? " with tabs" : "");
                TillLayout.Box screen = new TillLayout.Box(0, 0, size[0], size[1]);
                assertTrue(l.panel().inside(screen), "the checkout off the screen at " + at);
                for (TillLayout.Box b : new TillLayout.Box[]{ l.header(), l.list(), l.basket(), l.pay(), l.footer() }) {
                    assertTrue(b.inside(l.panel()), b + " outside the window at " + at);
                }
                assertTrue(l.list().right() <= l.basket().x(), "the list runs under the basket at " + at);
                assertTrue(l.rows() >= 5, "only " + l.rows() + " rows of wares at " + at);
                assertTrue(l.basket().w() >= 128, "a cramped basket at " + at);
                int[] st = TillLayout.stepper(l.list().right());
                assertTrue(st[0] - (l.list().x() + 24) >= 90, "no room for a name beside the buttons at " + at);
                assertTrue(st[2] + TillLayout.BTN <= l.list().right(), "the + off the row at " + at);
                assertTrue(l.pay().bottom() + 12 <= l.basket().bottom(), "no room for Pay's reason at " + at);
                assertTrue(l.pay().y() - 3 * 17 - 40 >= l.basket().y() + 14, "the total and its buttons crowd the basket at " + at);
            }
        }
    }
}
