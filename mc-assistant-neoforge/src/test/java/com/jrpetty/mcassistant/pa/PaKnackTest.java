package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa38] Fishing, enchanting, trading and the learner's slips: better with level, modest at the top, never a cheat. */
class PaKnackTest {

    @Test
    void fishingIsANudgeOnTheGamesOwnNumbers() {
        assertEquals(0, PaKnack.lureTicks(1));
        assertTrue(PaKnack.lureTicks(10) <= PaKnack.LURE_LEVEL_TICKS / 2, "at most half a level of Lure: " + PaKnack.lureTicks(10));
        assertTrue(PaKnack.luck(10) <= 1.0 + 1e-9 && PaKnack.luck(1) == 0, "at most one level of Luck of the Sea");
        assertEquals(0.05, PaKnack.treasureChance(0), 1e-9, "the game's 5% treasure with no luck");
        assertTrue(PaKnack.treasureChance(PaKnack.luck(10)) < 0.075, "modest at the top");
        assertEquals(20.0, PaKnack.meanWaitSeconds(1), 1e-9, "the game's average wait, open water, no Lure");
        double last = 1;
        for (int l = 1; l <= 10; l++) {
            double lost = PaKnack.lostBites(l);
            assertTrue(lost <= last + 1e-9, "fewer bites lost at " + l + ": " + lost);
            last = lost;
        }
        assertTrue(PaKnack.lostBites(1) > 0.10 && PaKnack.lostBites(1) < 0.25, "a learner loses some: " + PaKnack.lostBites(1));
        assertTrue(PaKnack.lostBites(10) < 0.02, "a master hardly any: " + PaKnack.lostBites(10));
        assertEquals(0.0, PaKnack.earlyStrike(8), 1e-9);
    }

    @Test
    void aCastLandsWhereItIsAimed() {
        for (double horiz : new double[]{3, 4.5, 6, 9}) for (double dy : new double[]{-1.6, -2.6}) {
            double p = PaKnack.hookPitch(horiz, dy);
            assertEquals(horiz, PaKnack.hookLands(p, dy), 0.35, "a cast " + horiz + " out, " + dy + " down, at pitch " + p);
        }
    }

    // ------------------------------------------------------------------ enchanting

    static final String[] SWORD_CLUES = {"sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect", "looting", "sweeping_edge", "unbreaking"};

    static PaEnchantJudge.Offer[] randomOffers(Random r) {
        PaEnchantJudge.Offer[] o = new PaEnchantJudge.Offer[3];
        int c0 = 1 + r.nextInt(8), c1 = c0 + 2 + r.nextInt(10), c2 = Math.max(c1 + 1, 15 + r.nextInt(16));
        int[] c = {c0, c1, c2};
        for (int k = 0; k < 3; k++) o[k] = new PaEnchantJudge.Offer(c[k], SWORD_CLUES[r.nextInt(SWORD_CLUES.length)], 1 + r.nextInt(3) + k);
        return o;
    }

    static double agreement(int level, int n, long seed) {
        Random r = new Random(seed);
        int same = 0, total = 0;
        for (int i = 0; i < n; i++) {
            PaEnchantJudge.Offer[] o = randomOffers(r);
            int ideal = PaEnchantJudge.ideal("sword", o, 30, 64);
            if (ideal < 0) continue;
            total++;
            if (PaEnchantJudge.pick(level, "sword", o, 30, 64, r) == ideal) same++;
        }
        return same / (double) total;
    }

    @Test
    void itJudgesTheTablesOffersBetterWithLevel() {
        double l1 = agreement(1, 2000, 38), l5 = agreement(5, 2000, 38), l10 = agreement(10, 2000, 38);
        System.out.printf("[pa38] enchant offers: the expert's pick at L1 %.0f%%, L5 %.0f%%, L10 %.0f%%%n", l1 * 100, l5 * 100, l10 * 100);
        assertTrue(l1 < 0.5, "a learner often misjudges: " + l1);
        assertTrue(l5 > l1 && l10 > l5, "better with level");
        assertTrue(l10 > 0.85, "a master mostly agrees with the expert: " + l10);
        // a learner takes the requirement for the price: the cheap-looking top offer, most of the time
        Random r = new Random(1);
        int top = 0;
        for (int i = 0; i < 1000; i++) if (PaEnchantJudge.pick(1, "sword", randomOffers(r), 30, 64, r) == 0) top++;
        assertTrue(top > 500, "a learner goes for the top offer: " + top);
        // what it can't pay for, it never takes
        PaEnchantJudge.Offer[] o = {new PaEnchantJudge.Offer(3, "sharpness", 1), new PaEnchantJudge.Offer(12, "sharpness", 2), new PaEnchantJudge.Offer(25, "sharpness", 3)};
        for (int i = 0; i < 200; i++) assertTrue(PaEnchantJudge.pick(10, "sword", o, 13, 64, r) <= 1, "25 levels needed, 13 had");
        assertEquals(-1, PaEnchantJudge.pick(10, "sword", o, 30, 0, r), "no lapis, no enchanting");
        assertTrue(PaEnchantJudge.worth("sword", "sharpness", 5) > PaEnchantJudge.worth("sword", "bane_of_arthropods", 5));
        assertTrue(!PaEnchantJudge.poor(6, "sword", o, 30, 64) && PaEnchantJudge.poor(7, "sword",
            new PaEnchantJudge.Offer[]{new PaEnchantJudge.Offer(2, "knockback", 1), new PaEnchantJudge.Offer(8, "bane_of_arthropods", 1), new PaEnchantJudge.Offer(14, "knockback", 2)}, 30, 64),
            "turning over a poor set only from level 7");
    }

    @Test
    void levelsCountAsAPlayersBar() {
        assertEquals(30, PaEnchantJudge.levelOf(1395), "the game's 1395 points for level 30");
        assertEquals(29, PaEnchantJudge.levelOf(1394));
        assertEquals(15, PaEnchantJudge.levelOf(315));
        assertEquals(27, PaEnchantJudge.levelOf(PaEnchantJudge.afterSpending(1395, 3)), "three levels off thirty");
        assertEquals(0, PaEnchantJudge.levelOf(PaEnchantJudge.afterSpending(10, 5)), "never below level 0 (the bar keeps its share)");
    }

    // ------------------------------------------------------------------ trading

    @Test
    void itPicksBetterTradesWithLevel() {
        Random r = new Random(5);
        int[] right = new int[11];
        for (int i = 0; i < 1500; i++) {
            List<PaTradeSense.Seen> offers = new ArrayList<>();
            for (int k = 0; k < 5; k++) { int base = 1 + r.nextInt(20); offers.add(new PaTradeSense.Seen(base, base, base * (0.6 + r.nextDouble()))); }
            int ideal = PaTradeSense.ideal(offers);
            for (int l : new int[]{1, 5, 10}) if (PaTradeSense.choose(l, offers, r) == ideal) right[l]++;
        }
        assertTrue(right[1] < right[5] && right[5] < right[10], "better with level: " + right[1] + " " + right[5] + " " + right[10]);
        assertTrue(right[10] > 1500 * 0.8, "a master mostly right: " + right[10]);
        assertEquals(1, PaKnack.villagersCompared(1));
        assertEquals(10, PaKnack.villagersCompared(10));
        assertTrue(PaTradeSense.bargain(1, new PaTradeSense.Seen(5, 8, 8)), "a discount anyone can see");
        assertTrue(!PaTradeSense.bargain(5, new PaTradeSense.Seen(20, 20, 50)) && PaTradeSense.bargain(6, new PaTradeSense.Seen(20, 20, 50)), "a book's worth from level 6");
        assertEquals(7, PaKnack.CURE_LEVEL);
    }

    // ------------------------------------------------------------------ the learner's slips, with a seeded RNG

    static int slips(java.util.function.IntToDoubleFunction rate, int level, int tries, long seed) {
        Random r = new Random(seed);
        int n = 0;
        for (int i = 0; i < tries; i++) if (PaKnack.roll(rate.applyAsDouble(level), r)) n++;
        return n;
    }

    @Test
    void aLearnersSlipsFadeToNothingByLevelEight() {
        // with the same dice every run: a learner slips now and then, a level-8 assistant never
        assertEquals(slips(PaKnack::misplace, 1, 10_000, 38), slips(PaKnack::misplace, 1, 10_000, 38), "seeded: the same every run");
        int m1 = slips(PaKnack::misplace, 1, 10_000, 38), t1 = slips(PaKnack::wrongTurn, 1, 1_000, 38), w1 = slips(PaKnack::wrongTool, 1, 1_000, 38);
        assertTrue(m1 > 80 && m1 < 160, "about one block in eighty-odd, misplaced: " + m1);
        assertTrue(t1 > 50 && t1 < 110, "a wrong turn on about one long walk in twelve: " + t1);
        assertTrue(w1 > 70 && w1 < 135, "the wrong tool for about one job in ten: " + w1);
        for (int l = 8; l <= 10; l++) {
            assertEquals(0, slips(PaKnack::misplace, l, 100_000, l), "no misplaced blocks at " + l);
            assertEquals(0, slips(PaKnack::wrongTurn, l, 100_000, l), "no wrong turns at " + l);
            assertEquals(0, slips(PaKnack::wrongTool, l, 100_000, l), "no wrong tools at " + l);
        }
        for (int l = 2; l <= 10; l++) {
            assertTrue(PaKnack.misplace(l) <= PaKnack.misplace(l - 1) && PaKnack.wrongTurn(l) <= PaKnack.wrongTurn(l - 1) && PaKnack.wrongTool(l) <= PaKnack.wrongTool(l - 1),
                "never more slips with a level: " + l);
        }
        assertTrue(PaKnack.SLIP_GAP >= 1200, "no two slips close together");
    }
}
