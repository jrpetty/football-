package com.jrpetty.mcassistant.village;

import com.jrpetty.mcassistant.village.InventionTree.Invention;
import com.jrpetty.mcassistant.village.InventionTreeLayout.Layout;
import com.jrpetty.mcassistant.village.InventionTreeLayout.Metrics;
import com.jrpetty.mcassistant.village.InventionTreeLayout.Place;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [screens13] The research tree's drawing (InventionTreeLayout, drawn by client/InventionsPage) at its three zooms. */
class InventionTreeLayoutTest {

    /** The page's zooms: compact, normal, large (InventionsPage's CARD_W, CARD_H, GAP_X, GAP_Y and STEP_HEAD). */
    private static final Metrics[] ZOOMS = {
        new Metrics(66, 13, 10, 4, 10), new Metrics(80, 22, 10, 4, 10), new Metrics(112, 26, 16, 5, 10) };

    @Test
    void everyInventionHasAPlaceAndNoTwoCardsOverlap() {
        for (Metrics m : ZOOMS) {
            Layout l = InventionTreeLayout.layout(m);
            assertEquals(Invention.values().length, l.places().size(), "every invention placed");
            for (Place a : l.places().values()) {
                assertTrue(a.x() >= 0 && a.y() >= 0 && a.x() + m.cardW() <= l.width() && a.y() + m.cardH() <= l.height(),
                    a.inv() + " inside the tree");
                for (Place b : l.places().values()) {
                    if (a == b) continue;
                    boolean apart = a.x() + m.cardW() <= b.x() || b.x() + m.cardW() <= a.x()
                        || a.y() + m.cardH() <= b.y() || b.y() + m.cardH() <= a.y();
                    assertTrue(apart, a.inv() + " and " + b.inv() + " overlap at zoom " + m);
                }
            }
        }
    }

    @Test
    void whatIsNeededStandsInABandAboveAndTheLinesRunInTheChannels() {
        for (Metrics m : ZOOMS) {
            Layout l = InventionTreeLayout.layout(m);
            for (Invention inv : Invention.values()) {
                Place to = l.places().get(inv);
                for (Invention need : inv.needs()) {
                    Place from = l.places().get(need);
                    assertTrue(from.y() + m.cardH() < to.y(), need + " above " + inv);
                    // The channel the line goes down lies between columns, clear of every card.
                    int cx = l.channelX(need, inv);
                    for (Place p : l.places().values()) {
                        assertFalse(cx >= p.x() && cx < p.x() + m.cardW(), "the line " + need + " to " + inv + " runs through " + p.inv());
                    }
                    // Its run along under the thing needed stays in the gap beneath it.
                    int runY = from.y() + m.cardH() + Math.max(1, m.gapY() / 2);
                    int x0 = Math.min(cx, from.x() + m.cardW() / 2), x1 = Math.max(cx, from.x() + m.cardW() / 2);
                    for (Place p : l.places().values()) {
                        boolean crosses = runY >= p.y() && runY < p.y() + m.cardH() && x1 > p.x() && x0 < p.x() + m.cardW();
                        assertFalse(crosses, "the run from " + need + " to " + inv + " crosses " + p.inv());
                    }
                }
            }
        }
    }

    @Test
    void theNormalZoomFitsTheBooksOnTheSmallestScreen() {
        // The books' page at 427 by 240 GUI pixels (GUI 2 at 854x480, GUI 3 at 1280x720) leaves the tree about 369 across (the page less its well and scroll bar).
        assertTrue(InventionTreeLayout.width(ZOOMS[1]) <= 369, "normal zoom " + InventionTreeLayout.width(ZOOMS[1]));
        assertTrue(InventionTreeLayout.width(ZOOMS[0]) < InventionTreeLayout.width(ZOOMS[1]));
        assertTrue(InventionTreeLayout.width(ZOOMS[1]) < InventionTreeLayout.width(ZOOMS[2]));
    }
}
