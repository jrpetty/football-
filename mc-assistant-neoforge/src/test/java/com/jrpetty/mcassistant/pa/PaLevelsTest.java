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
        // a busy game day at Normal pays about sixty (a few jobs, some hundred blocks): level 10 is weeks of play
        int perDay = 60;
        assertTrue(PaLevels.xpFor(10) / perDay >= 60, "many game days to level 10: " + PaLevels.xpFor(10) / perDay);
        assertTrue(PaLevels.xpFor(2) / perDay <= 1, "level 2 within the first day");
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
            assertTrue(PaLevels.speedPercent(l) > PaLevels.speedPercent(l - 1));
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
}
