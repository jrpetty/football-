package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa33d] The ten-level grind: the curve, the pay, what each level does and opens. */
class PaLevelsTest {

    @Test
    void theCurveIsAGrind() {
        assertEquals(0, PaLevels.xpFor(1));
        for (int l = 2; l <= 10; l++) {
            assertTrue(PaLevels.xpFor(l) > PaLevels.xpFor(l - 1), "climbs at " + l);
            if (l > 2) assertTrue(PaLevels.xpFor(l) - PaLevels.xpFor(l - 1) > PaLevels.xpFor(l - 1) - PaLevels.xpFor(l - 2), "each level costs more than the last at " + l);
        }
        assertEquals(1, PaLevels.level(0));
        assertEquals(10, PaLevels.level(PaLevels.xpFor(10)));
        assertEquals(9, PaLevels.level(PaLevels.xpFor(10) - 1));
        assertEquals(10, PaLevels.level(Long.MAX_VALUE / 4));
        // [pa35] a real grind, simulated: an ordinary hour's mixed work at a player's pace, with less for repetition
        double h2 = PaXp.hoursTo(2), h10 = PaXp.hoursTo(10);
        System.out.printf("[pa35] simulated hours: level 2 %.2f, level 10 %.1f (%.0f xp an ordinary hour)%n", h2, h10, PaXp.perHour());
        assertTrue(h10 >= 40 && h10 <= 60, "level 10 after 40 to 60 hours of ordinary play: " + h10);
        assertTrue(h2 >= 0.15 && h2 <= 0.6, "level 2 in about twenty minutes: " + h2 * 60 + " min");
        for (int l = 3; l <= 10; l++) assertTrue(PaXp.hoursTo(l) / PaXp.hoursTo(l - 1) > 1.3, "each level a good deal longer than the last at " + l);
        assertEquals(0.0F, PaLevels.progress(PaLevels.xpFor(4)), 1e-6);
        assertEquals(1.0F, PaLevels.progress(PaLevels.xpFor(10)), 1e-6);
    }

    @Test
    void payScalesWithDifficultyAndTheServer() {
        assertEquals(10, PaLevels.pay(10, 2, 1.0));
        assertEquals(5, PaLevels.pay(10, 0, 1.0), "peaceful teaches half");
        assertTrue(PaLevels.pay(10, 3, 1.0) > PaLevels.pay(10, 2, 1.0), "hard teaches more");
        assertEquals(30, PaLevels.pay(10, 2, 3.0));
        assertEquals(1, PaLevels.pay(1, 0, 1.0), "anything pays at least one");
        assertEquals(0, PaLevels.pay(0, 2, 5.0));
        assertTrue(PaLevels.baseFor("build") > PaLevels.baseFor("job") && PaLevels.baseFor("job") > PaLevels.baseFor("block"));
    }

    @Test
    void eachLevelIsBetter() {
        for (int l = 2; l <= 10; l++) {
            assertTrue(PaLevels.workPercent(l) > PaLevels.workPercent(l - 1));
            assertTrue(PaLevels.spread(l) < PaLevels.spread(l - 1));
            assertEquals(0, PaLevels.speedPercent(l), "[pa35] a player's legs at every level");
            assertTrue(PaLevels.damage(l) > PaLevels.damage(l - 1));
            assertTrue(PaLevels.range(l) > PaLevels.range(l - 1));
        }
        assertEquals(40, PaLevels.workPercent(10));
        assertFalse(PaLevels.leads(3));
        assertTrue(PaLevels.leads(4));
        assertEquals(27, PaPackRules.open(10));
        assertEquals(18, PaPackRules.open(1));
    }

    @Test
    void skillsOpenInOrderWithAClearNextLine() {
        int last = 0;
        for (PaLevels.Skill s : PaLevels.SKILLS) {
            assertTrue(s.level() >= last && s.level() >= 1 && s.level() <= 10, s.id());
            last = s.level();
        }
        for (int l = 1; l <= 10; l++) assertFalse(PaLevels.at(l).isEmpty(), "something opens at level " + l);
        assertTrue(PaLevels.has(8, "clutch") && !PaLevels.has(7, "clutch"));
        assertTrue(PaLevels.has(9, "pearl") && !PaLevels.has(8, "pearl"));
        assertTrue(PaLevels.next(3).startsWith("Next at L4: "), PaLevels.next(3));
        assertTrue(PaLevels.next(3).contains("shield"));
        assertEquals("", PaLevels.next(10));
        assertEquals(0, PaLevels.levelOf("nothing"));
    }

    @Test
    void everyMetricOnlyEverImproves() {
        for (PaGrowth.Metric m : PaGrowth.METRICS) {
            for (int l = 2; l <= PaLevels.MAX; l++) {
                double a = m.value(l - 1), b = m.value(l);
                assertTrue(m.higherBetter() ? b >= a : b <= a, "[pa35] " + m.id() + " never gets worse from level " + (l - 1) + " to " + l + ": " + a + " -> " + b);
            }
            assertTrue(m.higherBetter() ? m.value(10) > m.value(1) : m.value(10) < m.value(1), "[pa35] " + m.id() + " grows over the ten levels");
            assertFalse(m.show(1).isBlank());
        }
        assertTrue(PaGrowth.metric("hands").value(10) >= PaHand.MIN / 20.0, "never quicker than a hand at any level");
        assertEquals(40.0, PaGrowth.metric("health").value(10), 1e-9);
    }

    @Test
    void theMiningAndGearLaddersAreCompleteAndInOrder() {
        String[][] ores = {{"coal_ore", "2"}, {"deepslate_coal_ore", "2"}, {"copper_ore", "3"}, {"nether_quartz_ore", "3"}, {"iron_ore", "4"}, {"deepslate_iron_ore", "4"},
            {"gold_ore", "5"}, {"nether_gold_ore", "5"}, {"redstone_ore", "5"}, {"lapis_ore", "5"}, {"diamond_ore", "6"}, {"deepslate_diamond_ore", "6"},
            {"emerald_ore", "7"}, {"obsidian", "7"}, {"crying_obsidian", "7"}, {"ancient_debris", "8"}, {"stone", "1"}, {"dirt", "1"}, {"oak_log", "1"}, {"netherrack", "1"}};
        for (String[] o : ores) assertEquals(Integer.parseInt(o[1]), PaMineLadder.levelFor(o[0], true, java.util.Map.of()), o[0]);
        assertEquals(1, PaMineLadder.levelFor("diamond_ore", false, java.util.Map.of()), "the ladder off: no gating");
        assertEquals(3, PaMineLadder.levelFor("iron_ore", true, java.util.Map.of("iron", 3)), "an operator's override");
        int last = 0;
        for (PaMineLadder.Rung r : PaMineLadder.RUNGS) { assertTrue(r.level() >= last, "in order: " + r.key()); last = r.level(); }
        assertEquals(5, PaGearLadder.levelFor("crossbow", true, java.util.Map.of()));
        assertEquals(2, PaGearLadder.levelFor("shield", true, java.util.Map.of()));
        assertEquals(6, PaGearLadder.levelFor("diamond_sword", true, java.util.Map.of()));
        assertEquals(9, PaGearLadder.levelFor("netherite_pickaxe", true, java.util.Map.of()));
        assertEquals(1, PaGearLadder.levelFor("iron_sword", true, java.util.Map.of()));
        assertEquals(1, PaGearLadder.levelFor("bow", true, java.util.Map.of()));
        assertEquals("iron ore", PaMineLadder.opens(4, true, java.util.Map.of()));
    }

    @Test
    void repetitionEarnsLessAndVarietyRestoresIt() {
        PaXp.Fatigue f = new PaXp.Fatigue();
        double first = f.take("BLOCKS_MINED", 0), share = first;
        for (int i = 1; i <= 25; i++) share = f.take("BLOCKS_MINED", i * 20);
        assertEquals(1.0, first, 1e-9);
        assertTrue(share < 0.6, "the same work over and over earns less: " + share);
        for (int i = 0; i < 300; i++) share = f.take("BLOCKS_MINED", 1000 + i * 20);
        assertTrue(share >= 0.2 - 1e-9, "never under a fifth: " + share);
        assertEquals(1.0, f.take("FISH_CAUGHT", 9000), 1e-9, "something else straight after earns in full");
        assertTrue(f.take("BLOCKS_MINED", 9000 + 6000 * 6) > 0.8, "time eases it");
        assertTrue(PaXp.FIRSTS.get("diamond") > 0 && PaXp.FIRSTS.get("boss") > PaXp.FIRSTS.get("diamond"), "firsts pay a bonus");
    }
}
