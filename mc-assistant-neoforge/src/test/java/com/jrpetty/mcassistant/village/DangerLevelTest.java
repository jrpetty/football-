package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [featbuild] The town's danger and the wall's stages (DangerLevel). */
class DangerLevelTest {

    private static DangerLevel.Reading read(int raids, int alarms, int kills, int war, int coins, int folk) {
        return DangerLevel.read(new DangerLevel.Inputs(raids, alarms, kills, war, coins, folk));
    }

    @Test
    void aQuietTownIsOpen() {
        DangerLevel.Reading r = read(0, 0, 0, 0, 20, 8);
        assertEquals(0, r.score());
        assertEquals(DangerLevel.OPEN, r.stage());
        assertTrue(r.reasons().get(0).startsWith("nothing yet"));
        assertTrue(r.next().contains("12"));
    }

    @Test
    void raidsRaiseTheWallStageByStage() {
        assertEquals(DangerLevel.PALISADE, read(1, 0, 0, 0, 0, 0).stage());
        assertEquals(DangerLevel.STONE, read(3, 0, 0, 0, 0, 0).stage());
        // Raids alone top out at thirty-six: the towers need monsters, a war or wealth as well.
        assertEquals(DangerLevel.STONE, read(10, 0, 0, 0, 0, 0).stage());
        assertEquals(DangerLevel.TOWERS, read(3, 2, 10, 0, 0, 0).stage());
        assertEquals(DangerLevel.TOWERS, read(2, 0, 0, 3, 400, 40).stage());
    }

    @Test
    void eachPartIsCapped() {
        DangerLevel.Reading r = read(99, 99, 999, 3, 1_000_000, 999);
        assertEquals(36 + 10, r.raidPart());
        assertEquals(24, r.monsterPart());
        assertEquals(25, r.warPart());
        assertEquals(15 + 6, r.wealthPart());
        assertEquals(100, r.score());
        assertEquals("grave", DangerLevel.word(r.score()));
    }

    @Test
    void wealthTemptsRaiders() {
        assertEquals(0, read(0, 0, 0, 0, 49, 0).wealthPart());
        assertEquals(4, read(0, 0, 0, 0, 100, 0).wealthPart());
        assertEquals(12, read(0, 0, 0, 0, 400, 0).wealthPart());
    }

    @Test
    void aStageOnceReachedIsKept() {
        assertEquals(2, DangerLevel.kept(2, 0));
        assertEquals(3, DangerLevel.kept(1, 3));
        assertEquals(3, DangerLevel.kept(7, -1));
    }

    @Test
    void theWallsStoneTowersAndPortcullis() {
        assertEquals(0, DangerLevel.tier(DangerLevel.PALISADE, 2));          // a palisade, whatever the age
        assertEquals(1, DangerLevel.tier(DangerLevel.STONE, 0));             // stone even in the Wood Age: cobblestone
        assertEquals(3, DangerLevel.tier(DangerLevel.STONE, 3));             // the age's stone
        assertFalse(DangerLevel.midTowers(DangerLevel.STONE));
        assertTrue(DangerLevel.midTowers(DangerLevel.TOWERS));
        assertFalse(DangerLevel.portcullis(DangerLevel.STONE, 2));
        assertTrue(DangerLevel.portcullis(DangerLevel.TOWERS, 1));
        assertFalse(DangerLevel.portcullis(DangerLevel.TOWERS, 0));          // no portcullis in a palisade
    }
}
