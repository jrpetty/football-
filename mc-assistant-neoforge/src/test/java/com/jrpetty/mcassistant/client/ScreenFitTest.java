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

    @Test
    void aPanelNeverOutgrowsItsScreen() {
        for (int[] size : ScreenFit.GUI_SIZES) {
            assertTrue(ScreenFit.side(600, size[0], 12) <= size[0] - 12);
            assertTrue(ScreenFit.side(360, size[1], 12) <= size[1] - 12);
        }
    }
}
