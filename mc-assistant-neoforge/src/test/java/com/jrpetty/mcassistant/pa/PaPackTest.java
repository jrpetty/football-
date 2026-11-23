package com.jrpetty.mcassistant.pa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [pa33c-2b] The Pack tab's rules: rows that open with its level, what counts as junk, the sorting order. */
class PaPackTest {

    @Test
    void rowsOpenWithLevel() {
        assertEquals(18, PaPackRules.open(0), "two rows to start");
        assertEquals(18, PaPackRules.open(2));
        assertEquals(27, PaPackRules.open(3), "the third at level 3");
        assertEquals(27, PaPackRules.open(50));
        assertEquals(0, PaPackRules.rowLevel(0));
        assertEquals(3, PaPackRules.rowLevel(2));
        assertEquals(PaPackRules.SIZE, PaPackRules.ROW * 3);
    }

    @Test
    void junkIsJunk() {
        assertTrue(PaPackRules.junk("minecraft:dirt", 64));
        assertTrue(PaPackRules.junk("minecraft:rotten_flesh", 1));
        assertTrue(PaPackRules.junk("minecraft:wheat_seeds", 40), "more seeds than a field takes");
        assertFalse(PaPackRules.junk("minecraft:wheat_seeds", 10), "a field's worth is kept");
        assertFalse(PaPackRules.junk("minecraft:cobblestone", 64), "building stone is not junk");
        assertFalse(PaPackRules.junk("minecraft:diamond", 1));
        assertFalse(PaPackRules.junk("minecraft:oak_log", 64));
    }

    @Test
    void sortingOrder() {
        int tool = PaPackRules.group(true, false, false, "iron_pickaxe", false);
        int armour = PaPackRules.group(false, true, false, "iron_chestplate", false);
        int food = PaPackRules.group(false, false, true, "bread", false);
        int ore = PaPackRules.group(false, false, false, "iron_ingot", false);
        int block = PaPackRules.group(false, false, false, "oak_planks", true);
        int other = PaPackRules.group(false, false, false, "stick", false);
        assertTrue(tool < armour && armour < food && food < ore && ore < block && block < other);
        assertEquals(ore, PaPackRules.group(false, false, false, "raw_iron", false));
        assertEquals(ore, PaPackRules.group(false, false, false, "diamond", false));
    }
}
