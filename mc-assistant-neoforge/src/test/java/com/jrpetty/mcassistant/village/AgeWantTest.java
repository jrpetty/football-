package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [growth24] The age's want of a good in its price (AgeWant): a young town short of six hundred food for its larder mark, a
 * food it makes and eats ten of a day with forty in its stores, priced for a week.
 */
class AgeWantTest {

    /** The good's price after a week, from its usual worth, with this much counted as wanted each day. */
    private static double week(int counted) {
        double f = 1.0;
        for (int day = 0; day < 7; day++) f = Worth.step(f, Worth.target(40, 8, 10, 10 + counted, 0));
        return f;
    }

    @Test
    @DisplayName("a stock the age wants is not a meal eaten: the price rises a little, not three times over in a week")
    void damped() {
        int before = (int) Math.ceil(600 / Worth.DAYS_COVER);               // a third of the shortfall, every day
        int after = AgeWant.counted(600, Worth.DAYS_COVER, 10, 10, 8);
        assertEquals(10, after, "counted no more than the good is wanted or made in a day");
        double was = week(before), now = week(after);
        assertTrue(was >= 2.0, "before: up " + Math.round((was - 1) * 100) + "% in a week");
        assertTrue(now <= 1.35 && now >= 1.0, "now: up " + Math.round((now - 1) * 100) + "% in a week");
    }

    @Test
    @DisplayName("no shortfall, no want; a want of a good never seen is a lot")
    void edges() {
        assertEquals(0, AgeWant.counted(0, 3.0, 50, 50, 8));
        assertEquals(8, AgeWant.counted(600, 3.0, 0, 0, 8));
        assertEquals(5, AgeWant.counted(15, 3.0, 50, 50, 8));
    }
}
