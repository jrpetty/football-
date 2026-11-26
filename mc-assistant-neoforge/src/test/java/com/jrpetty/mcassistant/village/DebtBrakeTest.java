package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [econ51] The brake on a town's works in debt, checked without a world. */
class DebtBrakeTest {

    @Test
    @DisplayName("bonds up to a week's revenue weigh nothing; past it, every penny counts with the IOUs")
    void load() {
        assertEquals(500, DebtBrake.load(500, 900, 1000), "a week's bonds are ordinary borrowing");
        assertEquals(800, DebtBrake.load(500, 1300, 1000), "the bonds over it are a load like the IOUs they paid off");
        assertEquals(0, DebtBrake.load(-5, 0, 1000));
    }

    @Test
    @DisplayName("no bound at or under what it can carry; a slope down to the least share past it, never a wall")
    void share() {
        assertTrue(Double.isInfinite(DebtBrake.share(1000, 1000)));
        assertEquals(DebtBrake.TOP_SHARE, DebtBrake.share(1001, 1000), 0.01, "just over the line, most of a day's revenue");
        assertTrue(DebtBrake.TOP_SHARE < 1.0, "never all of it: the debt would sit on the line");
        double mid = DebtBrake.share(1300, 1000);
        assertTrue(mid < DebtBrake.TOP_SHARE && mid > DebtBrake.LOW_SHARE, "half way down the slope: " + mid);
        assertEquals(DebtBrake.LOW_SHARE, DebtBrake.share(1600, 1000), 1e-9);
        assertEquals(DebtBrake.LOW_SHARE, DebtBrake.share(100000, 1000), 1e-9, "however deep, the builders keep a share");
        double last = 2;
        for (int load = 1001; load < 2000; load += 50) {
            double s = DebtBrake.share(load, 1000);
            assertTrue(s <= last, "never more as the debt grows: " + load);
            last = s;
        }
    }

    @Test
    @DisplayName("over the line the works spend less than comes in, so the debt comes down day by day")
    void paysDown() {
        long carry = 10000, revenue = 3000, floor = 500;
        long load = 25000;
        for (int day = 0; day < 60 && load > carry; day++) {
            long spend = DebtBrake.allowance(load, carry, revenue, floor);
            assertTrue(spend < revenue, "spends under its revenue on day " + day + ": " + spend);
            load += spend - revenue;
        }
        assertTrue(load <= carry, "back under what it can carry: " + load);
    }

    @Test
    @DisplayName("the floor keeps the builders at work even on a day with nothing coming in")
    void floor() {
        assertEquals(200, DebtBrake.allowance(5000, 1000, 0, 200));
        assertEquals(Long.MAX_VALUE, DebtBrake.allowance(900, 1000, 0, 200));
    }

    @Test
    @DisplayName("what the room left buys, no more")
    void fits() {
        assertEquals(3, DebtBrake.fits(350, 100, 64));
        assertEquals(64, DebtBrake.fits(Long.MAX_VALUE, 100, 64));
        assertEquals(0, DebtBrake.fits(0, 100, 64));
        assertEquals(10, DebtBrake.fits(100000, 5, 10));
    }
}
