package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [soak40] The living wage and the field hand's day settled together, checked without a world. */
class WageSettleTest {

    @Test
    @DisplayName("a rent at half the wage settles where two meals and the rent are covered")
    void settles() {
        // Two meals at a coin, a house's rent half the field hand's day: wage = 2 + wage / 2 settles at 4.
        int w = WageSettle.settle(1, r -> 2 + (r + 1) / 2, 6);
        assertEquals(4, w, "two meals and the rent covered: " + w);
        assertEquals(4, 2 + (w + 1) / 2, "and it is a resting place");
    }

    @Test
    @DisplayName("a field hand worth more than the cost of living is paid its worth")
    void worthMore() {
        assertEquals(9, WageSettle.settle(9, r -> 2 + (r + 1) / 2, 6));
    }

    @Test
    @DisplayName("a rent dearer than the wage it follows does not run the wage up round on round")
    void noRunaway() {
        // A city's cheapest rent at one and a half times the wage it is reckoned by (a house of two, homes scarce): no
        // resting place. Six rounds of it took the soak's living wage from 6 to 48 overnight.
        java.util.function.IntUnaryOperator dear = r -> 1 + (3 * r + 1) / 2;
        int run = 4;
        for (int i = 0; i < 6; i++) run = Math.max(dear.applyAsInt(run), 4);
        int w = WageSettle.settle(4, dear, 6);
        assertTrue(run >= 8 * 4, "unchecked, six rounds run it up eightfold: " + run);
        assertTrue(w <= dear.applyAsInt(dear.applyAsInt(4)), "checked, it stops within a round or two: " + w);
        assertTrue(w >= dear.applyAsInt(4), "and still covers the rent asked at the market wage: " + w);
    }
}
