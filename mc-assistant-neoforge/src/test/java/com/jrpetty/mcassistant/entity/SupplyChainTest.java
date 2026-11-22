package com.jrpetty.mcassistant.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [econ32] The supply chains' rules that need no world: when the stores ask for room, and the bounds on new mines and tools for sale (the new mine itself: SupplyChainGameTests.sc03). */
class SupplyChainTest {

    @Test
    @DisplayName("the stores ask for another chest under a tenth free, or under a chest's worth, never when empty or roomy")
    void storeRoom() {
        assertFalse(StoreRoom.tight(0, 0), "no stores at all: nothing to grow");
        assertTrue(StoreRoom.tight(54, 20), "two chests with less than a chest free");
        assertFalse(StoreRoom.tight(54, 30), "two chests, more than a chest free");
        assertTrue(StoreRoom.tight(729 + 270, 90), "a storehouse and ten chests under a tenth free");
        assertFalse(StoreRoom.tight(729 + 270, 120), "a storehouse and ten chests with an eighth free");
    }

    @Test
    @DisplayName("the further levels of an ore and the old mines stay within what the works can keep")
    void limits() {
        assertTrue(MineSites.MOST >= 2 && MineSites.MOST <= 8, "a few mines a town, not one a week");
        assertTrue(ToolTrade.TRADE_STOCK >= 1 && ToolTrade.TRADE_STOCK <= 4, "a few tools for sale, not the town's iron");
    }
}
