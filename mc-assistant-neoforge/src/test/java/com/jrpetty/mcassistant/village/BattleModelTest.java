package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [war] The battle reckoned on paper (BattleModel): the stronger side wins most battles, two equal ones about
 * half each, a battle always ends, a side breaks before it is cut to pieces, and deaths stay rare.
 */
class BattleModelTest {

    private static final int TRIALS = 600;

    private static Random rng(int seed) {
        return new Random(seed * 0x9E3779B97F4A7C15L + 12345L);
    }

    private static BattleModel.Side side(String name, int size, BattleModel.Tactic t) {
        return new BattleModel.Side(name, size, size * 9, size / 4, BattleModel.startMorale(0, false, 0, false), t);
    }

    /** How many battles of TRIALS the first side wins. */
    private static int wins(int a, int b, BattleModel.Tactic ta, BattleModel.Tactic tb, int deaths) {
        int won = 0;
        for (int i = 0; i < TRIALS; i++) {
            BattleModel.Result r = BattleModel.resolve(side("A", a, ta), side("B", b, tb), 0, 0, 9, 9, deaths, rng(i));
            if (r.winner() == 0) won++;
        }
        return won;
    }

    @Test
    @DisplayName("the stronger army wins most battles; two equal ones, about half each")
    void strengthDecides() {
        int strong = wins(15, 10, BattleModel.Tactic.STRAIGHT, BattleModel.Tactic.STRAIGHT, 5);
        assertTrue(strong > TRIALS * 0.85, "fifteen against ten won only " + strong + " of " + TRIALS);
        int even = wins(12, 12, BattleModel.Tactic.STRAIGHT, BattleModel.Tactic.STRAIGHT, 5);
        assertTrue(even > TRIALS * 0.4 && even < TRIALS * 0.6, "twelve against twelve won " + even + " of " + TRIALS);
    }

    @Test
    @DisplayName("every battle ends, and the loser breaks before it is cut to pieces")
    void battlesEndInABreak() {
        for (int i = 0; i < TRIALS; i++) {
            BattleModel.Side a = side("A", 12, BattleModel.Tactic.STRAIGHT), b = side("B", 12, BattleModel.Tactic.SHIELD_WALL);
            BattleModel.Result r = BattleModel.resolve(a, b, 1, 0, 9, 9, 5, rng(i));
            assertTrue(r.rounds() <= BattleModel.ROUNDS, "a battle ran " + r.rounds() + " rounds");
            if (r.winner() < 0) continue;
            BattleModel.Side loser = r.loser() == 0 ? a : b;
            assertTrue(loser.standing() > 0 || r.outcome() == BattleModel.Outcome.SURRENDER,
                "the loser was cut down to the last before it broke (" + r.outcome() + ")");
        }
    }

    @Test
    @DisplayName("deaths are rare: with the chance at five in a hundred, a few of those who go down")
    void deathsAreRare() {
        int down = 0, dead = 0;
        for (int i = 0; i < TRIALS; i++) {
            BattleModel.Result r = BattleModel.resolve(side("A", 12, BattleModel.Tactic.STRAIGHT), side("B", 12, BattleModel.Tactic.STRAIGHT),
                0, 0, 9, 9, 5, rng(i));
            down += r.yieldedA() + r.yieldedB();
            dead += r.deadA() + r.deadB();
            assertTrue(r.deadA() <= r.yieldedA() && r.deadB() <= r.yieldedB(), "more dead than went down");
        }
        assertTrue(dead * 10 < down, dead + " dead of " + down + " down");
        int none = 0;
        for (int i = 0; i < 50; i++) {
            BattleModel.Result r = BattleModel.resolve(side("A", 12, BattleModel.Tactic.STRAIGHT), side("B", 12, BattleModel.Tactic.STRAIGHT),
                0, 0, 9, 9, 0, rng(i));
            none += r.deadA() + r.deadB();
        }
        assertEquals(0, none, "deaths switched off, and yet some died");
    }

    @Test
    @DisplayName("a side breaks in good order with its Captain up, routs with it down, surrenders when surrounded")
    void howASideBreaks() {
        BattleModel.Side us = side("A", 12, BattleModel.Tactic.STRAIGHT), them = side("B", 12, BattleModel.Tactic.STRAIGHT);
        assertNull(BattleModel.breaks(us, them), "a fresh side does not break");
        us.morale = BattleModel.BREAK - 1;
        assertEquals(BattleModel.Outcome.RETREAT, BattleModel.breaks(us, them));
        us.captainDown = true;
        assertEquals(BattleModel.Outcome.ROUT, BattleModel.breaks(us, them));
        us.yielded = 10;
        assertEquals(BattleModel.Outcome.SURRENDER, BattleModel.breaks(us, them));
    }

    @Test
    @DisplayName("a Captain's tactics follow the ground, the odds and its temper")
    void tactics() {
        Random r = rng(1);
        assertEquals(BattleModel.Tactic.CAVALRY, BattleModel.choose(1, false, 1.2, false, false, 3, 2, 12, r));
        assertEquals(BattleModel.Tactic.HILL_ARCHERS, BattleModel.choose(0, false, 1.0, true, false, 0, 4, 12, r));
        assertEquals(BattleModel.Tactic.SHIELD_WALL, BattleModel.choose(1, false, 0.6, false, false, 0, 1, 12, r));
        assertEquals(BattleModel.Tactic.FEIGNED_RETREAT, BattleModel.choose(0, true, 1.0, false, false, 0, 1, 12, r));
        assertNotNull(BattleModel.choose(-2, false, 2.0, false, true, 0, 0, 3, r));
    }

    @Test
    @DisplayName("each formation has what beats it, and a Captain draws up to its own heart and to the day")
    void formations() {
        assertTrue(BattleModel.edge(BattleModel.Formation.WEDGE, BattleModel.Formation.WINGED_LINE) > 1.0, "a wedge breaks a winged line");
        assertTrue(BattleModel.edge(BattleModel.Formation.CRESCENT, BattleModel.Formation.WEDGE) > 1.0, "a crescent folds round a wedge");
        assertTrue(BattleModel.edge(BattleModel.Formation.WINGED_LINE, BattleModel.Formation.SCHILTRON) > 1.0, "archers ruin a schiltron");
        assertTrue(BattleModel.flankShock(BattleModel.Formation.SCHILTRON) < 0.5, "a schiltron has no flank");
        assertEquals(BattleModel.Formation.SHIELD_WALL, BattleModel.prefer(true, -1, false, 0, 1.0, 1, 0, 12, false, null), "a cautious Guardian");
        assertEquals(BattleModel.Formation.WEDGE, BattleModel.prefer(false, 2, false, 0, 1.0, 1, 0, 12, false, null), "a bold one");
        BattleModel.Formation shrewd = BattleModel.prefer(false, 1, true, 0, 1.0, 5, 0, 12, true, null);
        assertTrue(shrewd == BattleModel.Formation.WINGED_LINE || shrewd == BattleModel.Formation.SKIRMISH, "a shrewd one with bows: " + shrewd);
        assertEquals(BattleModel.Formation.CRESCENT, BattleModel.prefer(false, 2, false, 2, 1.0, 1, 0, 12, false, BattleModel.Formation.WEDGE),
            "a veteran Captain draws up to beat the wedge its scouts saw");
    }

    @Test
    @DisplayName("only the hopelessly outnumbered offer to yield at the parley, and the vengeful seldom take it")
    void parley() {
        int offered = 0, offeredHopeless = 0, taken = 0, takenVengeful = 0;
        for (int i = 0; i < TRIALS; i++) {
            if (BattleModel.offersSurrender(0.6, -1, false, rng(i))) offered++;
            if (BattleModel.offersSurrender(0.15, -1, false, rng(i))) offeredHopeless++;
            if (BattleModel.acceptsSurrender(0, true, false, false, rng(i))) taken++;
            if (BattleModel.acceptsSurrender(1, false, true, true, rng(i))) takenVengeful++;
        }
        assertEquals(0, offered, "somewhat weaker is no reason to yield");
        assertTrue(offeredHopeless > TRIALS / 2, "hopeless and timid, most yield: " + offeredHopeless);
        assertTrue(taken > takenVengeful * 3, "a kindly victor takes a surrender far oftener than a vengeful, ambitious one: " + taken + " / " + takenVengeful);
    }

    @Test
    @DisplayName("[war-polish] every formation draws its line in even ranks and files: no two marks closer than its spacing or a rank's depth, no gap in a rank")
    void formationsDrawEvenRanks() {
        for (BattleModel.Formation f : BattleModel.Formation.values()) {
            for (int n : new int[]{ 5, 12, 20, 33 }) {
                double[][] s = new double[n][];
                for (int k = 0; k < n; k++) s[k] = f.slot(k, n);
                for (int i = 0; i < n; i++) {
                    double best = Double.MAX_VALUE;
                    for (int j = 0; j < n; j++) {
                        if (i == j) continue;
                        best = Math.min(best, Math.hypot(s[i][0] - s[j][0], s[i][1] - s[j][1]));
                    }
                    assertTrue(best >= Math.min(f.spacing, BattleModel.RANK_GAP) - 1.0E-9, f + " of " + n + ": mark " + i + " crowded (" + best + ")");
                    if (f.bend == BattleModel.Formation.Bend.STRAIGHT || f.bend == BattleModel.Formation.Bend.WEDGE) {
                        assertTrue(best <= Math.max(f.spacing, BattleModel.RANK_GAP) + 1.0E-9, f + " of " + n + ": mark " + i + " off on its own (" + best + ")");
                    }
                }
                if (f.bend == BattleModel.Formation.Bend.STRAIGHT) {
                    int ranks = f.ranksFor(n), per = (n + ranks - 1) / ranks;
                    for (int k = per; k < n; k++) {
                        assertEquals(s[0][1] - BattleModel.RANK_GAP * (k / per), s[k][1], 1.0E-9, f + ": rank " + k / per + " a rank's depth behind");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("[war-bows] the missile arm: a Captain's liking, a mix by turns, a third of the arrows gathered, the words")
    void missileArm() {
        assertEquals(BattleModel.Ranged.CROSSBOWS, BattleModel.Ranged.parse(" Crossbows "));
        assertNull(BattleModel.Ranged.parse("slings"));
        for (int k = 0; k < 6; k++) {
            assertTrue(BattleModel.Ranged.CROSSBOWS.crossbowFor(k) && !BattleModel.Ranged.BOWS.crossbowFor(k), "all of one kind");
            assertEquals(k % 2 == 1, BattleModel.Ranged.MIX.crossbowFor(k), "a mix by turns");
        }
        assertNotNull(BattleModel.Ranged.liking(-7), "any nature has a liking");
        assertEquals(10, BattleModel.recovered(31));
        assertEquals(0, BattleModel.recovered(-4));
        assertEquals("12 bows, 8 crossbows", BattleModel.missileWords(12, 8));
        assertEquals("1 crossbow", BattleModel.missileWords(0, 1));
        assertEquals("no bows", BattleModel.missileWords(0, 0));
    }
}
