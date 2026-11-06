package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [wagons] A wagon's route: where one number puts it, the trip's steps it has passed, a detour, and its writing down. */
class WagonRouteTest {

    /** An L: ten east, then ten south; the corner is step 1 of the trip's way, the end step 2. */
    private static WagonRoute ell() {
        return new WagonRoute(new double[]{ 0, 10, 10 }, new double[]{ 0, 0, 10 }, new int[]{ -1, 1, 2 });
    }

    @Test
    void distanceAlongPutsItOnTheLine() {
        WagonRoute r = ell();
        assertEquals(20.0, r.length(), 1e-9, "two legs of ten");
        assertEquals(5.0, r.xAt(5), 1e-9);
        assertEquals(0.0, r.zAt(5), 1e-9);
        assertEquals(10.0, r.xAt(15), 1e-9);
        assertEquals(5.0, r.zAt(15), 1e-9);
        assertEquals(10.0, r.zAt(99), 1e-9, "clamped at the end");
        assertEquals(0.0, r.xAt(-3), 1e-9, "clamped at the start");
    }

    @Test
    void stepsPassedKeepUpWithTheWagon() {
        WagonRoute r = ell();
        assertEquals(-1, r.stepPassed(4), "nothing of the way passed in the yard");
        assertEquals(1, r.stepPassed(12), "the corner passed");
        assertEquals(2, r.stepPassed(20), "at the end");
        assertEquals(10.0, r.alongOfStep(1), 1e-9, "a restart puts it back at the corner");
    }

    @Test
    void headingFollowsTheRoadAndRoundsTheCorner() {
        WagonRoute r = ell();
        assertEquals(0.0, r.heading(2, 1), 1e-9, "east");
        assertEquals(Math.PI / 2, r.heading(18, 1), 1e-9, "south");
        double mid = r.heading(10, 3);
        assertTrue(mid > 0.1 && mid < Math.PI / 2 - 0.1, "half turned at the corner: " + mid);
    }

    @Test
    void aDetourIsSplicedInAndTheStepsKept() {
        WagonRoute r = ell();
        WagonRoute d = r.spliced(4, 14, new double[]{ 6, 12 }, new double[]{ -3, 4 });
        assertTrue(d.length() > r.length(), "a way round is longer");
        assertEquals(10.0, d.xAt(d.length()), 1e-9);
        assertEquals(10.0, d.zAt(d.length()), 1e-9, "and still ends where it did");
        assertEquals(2, d.stepPassed(d.length()), "the end's step kept");
        assertEquals(Math.hypot(3, 1.5), d.nearest(3, -1.5), 1e-6, "the wagon found on it again, half way to the first turn");
    }

    @Test
    void writtenDownAndReadBack() {
        WagonRoute r = ell();
        WagonRoute back = WagonRoute.decode(r.encode());
        assertNotNull(back);
        assertEquals(r.length(), back.length(), 1e-6);
        assertEquals(1, back.stepPassed(11));
        assertEquals(null, WagonRoute.decode("rubbish;;"), "a line that will not read is nothing, not a crash");
    }
}
