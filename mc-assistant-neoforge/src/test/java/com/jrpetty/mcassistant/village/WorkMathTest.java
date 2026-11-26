package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [wpb] A job's targets, its day's stars and the commission's bounds, checked without a world. */
class WorkMathTest {

    @Test
    @DisplayName("a target is three fifths of a competent shift, never nothing")
    void targets() {
        double farm = WorkMath.competent(300, 1, WorkMath.SHIFT);
        assertTrue(farm > 10 && farm < 13, "a competent farm hand brings in a dozen plots or so: " + farm);
        assertEquals(7, WorkMath.target(farm));
        assertEquals(1, WorkMath.target(0.2), "never a target of nothing");
    }

    @Test
    @DisplayName("bad luck lowers the target; skill and rank raise it")
    void scaled() {
        assertTrue(WorkMath.scaled(10, 0, 0, 0.5, false) <= 5, "half the shift with no work halves it");
        assertTrue(WorkMath.scaled(10, 0, 0, 0, true) == 8, "rain outdoors takes a fifth off");
        assertEquals(0, WorkMath.scaled(10, 0, 0, 1.0, false), "no work at all asks nothing");
        assertTrue(WorkMath.scaled(10, 4, 2, 0, false) > 10, "a master head of the trade is asked more");
    }

    @Test
    @DisplayName("the day's stars: none for nothing, three at the target, five well over")
    void dayStars() {
        assertEquals(0, WorkMath.dayStars(0, 7));
        assertEquals(1, WorkMath.dayStars(1, 7));
        assertEquals(3, WorkMath.dayStars(7, 7));
        assertEquals(5, WorkMath.dayStars(12, 7));
        assertEquals(3, WorkMath.dayStars(0, 0), "a day with nothing to do is met by turning up");
    }

    @Test
    @DisplayName("commission is a share of the margin, held so a three-star day pays 0.9 to 1.3 times the going wage")
    void commission() {
        double each = WorkMath.commission(1.0, 0.25, 0.6);
        assertEquals(0.45, each, 1e-9, "a coin's bid, a quarter costs, three fifths of the rest");
        assertEquals(WorkMath.commission(1.0, 0.25, 0.9), WorkMath.commission(1.0, 0.25, 0.7), 1e-9, "the share is held to seven tenths");
        for (double rate : new double[]{ 0.01, 0.5, 3.0, 40.0 }) {
            double[] b = WorkMath.bounded(rate, 1, 10, 12);
            double day = 1 + b[0] * 10;
            assertTrue(day >= 12 * WorkMath.DAY_LO - 1e-9 && day <= 12 * WorkMath.DAY_HI + 1e-9, "a three-star day within bounds: " + day);
        }
        assertEquals(0, WorkMath.bounded(1.1, 0, 10, 12)[1], 1e-9, "a fair rate is left alone");
        assertEquals(1, WorkMath.bounded(40, 0, 10, 12)[1], 1e-9, "a rich one is capped, and says so");
    }

    @Test
    @DisplayName("folk lean to commission by drive, and the coin words read plainly")
    void folkAndWords() {
        assertTrue(WorkMath.folkTakesCommission(4, 0.9, 0.5), "an ambitious, skilled folk takes commission");
        assertTrue(!WorkMath.folkTakesCommission(-3, 0.1, 0.5), "a timid novice takes the salary");
        assertEquals("1.5c", WorkMath.coin(1.5));
        assertEquals("0.25c", WorkMath.coin(0.25));
        assertEquals("12c", WorkMath.coin(12.2));
    }
}
