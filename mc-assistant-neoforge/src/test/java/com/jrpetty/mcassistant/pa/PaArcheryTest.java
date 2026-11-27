package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [pa33g] Its archery, tried: hundreds of real shots (the game's own drag and gravity, the archer's stale sight,
 * judged distance, imperfect lead, capped head turn and shaky hands) at a standing and a strafing mob, at 10, 25 and
 * 45 blocks, for levels 1, 5 and 10. Better with level, worse with distance and with movement, and never an aimbot.
 */
class PaArcheryTest {

    static final int N = 600;
    static final int[] LEVELS = {1, 5, 10};
    static final double[] DISTS = {10, 25, 45};

    /** [level][dist][moving] */
    private static double[][][] rates() {
        double[][][] r = new double[3][3][2];
        for (int l = 0; l < 3; l++) for (int d = 0; d < 3; d++) for (int m = 0; m < 2; m++)
            r[l][d][m] = PaArchery.hitRate(LEVELS[l], DISTS[d], m == 1, false, N, 1000L * d + 10L * m + 7);
        return r;
    }

    private static final double[][][] R = rates();

    private static String at(int l, int d, int m) {
        return "L" + LEVELS[l] + " at " + (int) DISTS[d] + (m == 1 ? " (strafing)" : " (standing)") + ": " + Math.round(R[l][d][m] * 100) + "%";
    }

    @Test
    void betterWithEachLevel() {
        for (int d = 0; d < 3; d++) for (int m = 0; m < 2; m++) {
            assertTrue(R[1][d][m] >= R[0][d][m] - 0.02, at(1, d, m) + " vs " + at(0, d, m));
            assertTrue(R[2][d][m] >= R[1][d][m] - 0.02, at(2, d, m) + " vs " + at(1, d, m));
            if (d > 0) {                                                  // where it isn't easy for all, clearly better
                assertTrue(R[1][d][m] > R[0][d][m] + 0.04, at(1, d, m) + " clearly beats " + at(0, d, m));
                assertTrue(R[2][d][m] > R[1][d][m] + 0.04, at(2, d, m) + " clearly beats " + at(1, d, m));
            }
        }
    }

    @Test
    void worseWithDistance() {
        for (int l = 0; l < 3; l++) for (int m = 0; m < 2; m++) {
            assertTrue(R[l][1][m] <= R[l][0][m] + 0.02, at(l, 1, m) + " vs " + at(l, 0, m));
            assertTrue(R[l][2][m] <= R[l][1][m] + 0.02, at(l, 2, m) + " vs " + at(l, 1, m));
            assertTrue(R[l][2][m] < R[l][0][m] - 0.05, at(l, 2, m) + " well below " + at(l, 0, m));
        }
    }

    @Test
    void worseAtAMover() {
        for (int l = 0; l < 3; l++) for (int d = 0; d < 3; d++) {
            if (R[l][d][0] > 0.15) assertTrue(R[l][d][1] < R[l][d][0] - 0.05, at(l, d, 1) + " below " + at(l, d, 0));
        }
    }

    @Test
    void levelOneIsAWildShotLevelTenIsGoodNotPerfect() {
        assertTrue(R[0][1][0] < 0.5, "L1 misses a lot past 20 blocks: " + at(0, 1, 0));
        assertTrue(R[0][2][1] < 0.1, "L1 nearly always misses a mover at range: " + at(0, 2, 1));
        assertTrue(R[0][0][0] > 0.6, "L1 still hits a standing mob up close: " + at(0, 0, 0));
        assertTrue(R[2][2][0] > 0.75, "L10 is very good at 45: " + at(2, 2, 0));
        assertTrue(R[2][2][0] < 0.99, "but still misses sometimes at 45: " + at(2, 2, 0));
        assertTrue(R[2][2][1] < 0.85, "L10 is no aimbot at a strafing target at 45: " + at(2, 2, 1));
        assertTrue(R[2][2][1] > 0.2, "though it does land some: " + at(2, 2, 1));
        assertTrue(R[2][1][1] > R[0][1][1] + 0.3, "L10 leads a mover at 25 far better than L1: " + at(2, 1, 1) + " vs " + at(0, 1, 1));
    }

    @Test
    void walkingSpoilsItsAim() {
        for (int lv : LEVELS) {
            double stand = PaArchery.hitRate(lv, 25, false, false, 400, 77), walk = PaArchery.hitRate(lv, 25, false, true, 400, 77);
            assertTrue(walk < stand || stand > 0.97 && walk > 0.85, "L" + lv + " walking " + walk + " vs standing " + stand);
        }
    }

    @Test
    void theTableReadsStraight() {
        for (int l = 2; l <= 10; l++) {
            PaArchery.Row a = PaArchery.row(l - 1), b = PaArchery.row(l);
            assertEquals(l, b.level());
            assertTrue(b.swayDeg() < a.swayDeg() && b.rangeErr() < a.rangeErr() && b.leadMean() > a.leadMean() && b.reactionTicks() <= a.reactionTicks()
                && b.turnDeg() > a.turnDeg() && b.range() > a.range(), "level " + l + " better than " + (l - 1) + " on every count");
        }
        assertEquals(1, PaArchery.row(-4).level());
        assertEquals(10, PaArchery.row(99).level());
    }

    @Test
    void theHeadTurnsAtItsLevelsPaceNeverSnaps() {
        for (int lv : LEVELS) {
            PaArchery.Shooter s = new PaArchery.Shooter(lv, new Random(1), 90, 0, 20);    // facing away along -x: the target is south
            double before = s.yaw;
            s.see(0, 1.62, 0, 0, 0.9, 20);
            double turned = Math.abs(PaArchery.wrap(s.yaw - before));
            assertTrue(turned <= PaArchery.row(lv).turnDeg() + 1e-9, "L" + lv + " turned " + turned + " in one tick");
            assertTrue(!s.onTarget(), "not on target after one tick from 90 degrees off");
        }
    }

    @Test
    void theArrowFliesAsTheGamesDoes() {
        // the lob found for a mark is the one the game's own flight lands on it
        for (double d : new double[]{10, 30, 50}) {
            double pitch = PaArchery.solvePitch(3.0, d, 0);
            double h = PaArchery.heightAt(3.0, -pitch, d);
            assertEquals(0.0, h, 0.05, "lands on the mark at " + d);
            assertTrue(pitch < 0, "aims up to carry " + d);
        }
        assertEquals(1.0, PaArchery.power(20), 1e-9);
        assertEquals(0.4167, PaArchery.power(10), 1e-3, "half a draw: well under half the power, as the game's bow");
    }

    @Test
    void aShortDrawIsShakierThanAFullOne() {
        PaArchery.Shooter s = new PaArchery.Shooter(5, new Random(3), 0, 0, 20);
        s.ticks = 5;
        double early = s.sway(0);
        s.ticks = 25;
        double full = s.sway(0);
        assertTrue(early > full * 1.3, "five ticks in " + early + " vs a full draw " + full);
        assertTrue(s.sway(0.2) > full, "moving shakes it");
    }

    // ------------------------------------------------------------------ [pa35] the crossbow

    @Test
    void crossbowLoadsInTheGamesTime() {
        assertEquals(25, PaArchery.chargeTicks(0), "a plain crossbow spans in 1.25 s, as a player's");
        assertEquals(20, PaArchery.chargeTicks(1));
        assertEquals(15, PaArchery.chargeTicks(2));
        assertEquals(10, PaArchery.chargeTicks(3), "Quick Charge III: half a second");
    }

    @Test
    void crossbowTableHasItsShape() {
        for (int l = 1; l < PaArchery.CROSSBOW.length; l++) {
            PaArchery.Row a = PaArchery.CROSSBOW[l - 1], b = PaArchery.CROSSBOW[l];
            assertTrue(b.swayDeg() < a.swayDeg() && b.range() > a.range() && b.rangeErr() <= a.rangeErr() && b.drawTicks() <= a.drawTicks(), "steadier, further, quicker each level: " + l);
            assertTrue(a.swayDeg() < PaArchery.row(l).swayDeg(), "braced steadier than a held bowstring at level " + l);
            assertEquals(a.reactionTicks(), PaArchery.row(l).reactionTicks(), "the same eyes as its bow");
        }
    }

    @Test
    void crossbowShootsLikeACrossbow() {
        StringBuilder log = new StringBuilder();
        double[] standing = new double[11], strafing = new double[11], multi = new double[11];
        for (int l : new int[]{1, 5, 10}) {
            standing[l] = PaArchery.crossbowHitRate(l, 30, false, false, false, N, 3500L + l);
            strafing[l] = PaArchery.crossbowHitRate(l, 30, true, false, false, N, 3600L + l);
            multi[l] = PaArchery.crossbowHitRate(l, 30, true, false, true, N, 3600L + l);
            double bow = PaArchery.hitRate(l, 30, false, false, N, 3500L + l);
            log.append(String.format("L%d at 30: crossbow %.0f%% standing (bow %.0f%%), %.0f%% strafing, %.0f%% with Multishot; ", l, standing[l] * 100, bow * 100, strafing[l] * 100, multi[l] * 100));
            assertTrue(standing[l] >= bow - 0.02, "a crossbow is at least as good as its bow on a standing mob at 30: L" + l + " " + standing[l] + " vs " + bow);
            assertTrue(multi[l] >= strafing[l] - 0.01, "Multishot never hurts against a strafer: L" + l);
        }
        System.out.println("[pa35] " + log);
        assertTrue(standing[10] > standing[5] + 0.03 && standing[5] > standing[1] + 0.03, "better with level: " + log);
        assertTrue(strafing[10] > strafing[1] + 0.1, "leads a strafer better with level: " + log);
        assertTrue(multi[5] > strafing[5] + 0.03, "Multishot clearly helps against a strafer at 30: " + log);
        assertTrue(standing[10] < 0.995 || strafing[10] < 0.9, "never an aimbot: " + log);
    }
}
