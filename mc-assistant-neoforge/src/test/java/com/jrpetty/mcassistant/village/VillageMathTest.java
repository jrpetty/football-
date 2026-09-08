package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relationships between a settlement's numbers, which is where every bug
 * in this system has actually lived. Not one of these checks a value — values
 * are a matter of taste. They check that the parts agree with each other,
 * because the parts disagreeing is what broke villages twice.
 */
class VillageMathTest {

    private static final int MAX_FOLK = 60;

    @Test
    @DisplayName("the stores reach at least as far as the plots do")
    void storesKeepUpWithPlots() {
        // THE BUG THIS EXISTS FOR: stores were read from 32 blocks while plots
        // were staked 60+ out, so a village could not see its own harvest, the
        // plan read "short of food" for ever and the ages never advanced.
        for (int folk = 1; folk <= MAX_FOLK; folk++) {
            int plots = VillageMath.plotReach(folk);
            int stores = VillageMath.storesRadius(folk);
            assertTrue(stores >= plots,
                "at " + folk + " folk the stores reach " + stores
                + " but plots go out to " + plots);
        }
    }

    @Test
    @DisplayName("the loaded ring covers where the plots actually are")
    void ringCoversPlots() {
        for (int folk = 1; folk <= MAX_FOLK; folk++) {
            int ring = VillageMath.loadedRadiusBlocks(folk);
            int plots = VillageMath.plotReach(folk);
            assertTrue(ring >= plots,
                "at " + folk + " folk the ring is " + ring
                + " blocks but plots go out to " + plots);
        }
    }

    @Test
    @DisplayName("the ring never grows past what the release covers")
    void ringNeverOutgrowsItsRelease() {
        // A ring released one chunk short of what was taken leaves those
        // chunks ticking for the life of the world.
        for (int folk = 1; folk <= MAX_FOLK; folk++) {
            assertTrue(VillageMath.loadedRadiusChunks(folk) <= VillageMath.MAX_LOADED_RADIUS,
                "at " + folk + " folk the ring outgrew MAX_LOADED_RADIUS");
        }
    }

    @Test
    @DisplayName("every number grows with the village and never shrinks")
    void everythingIsMonotonic() {
        for (int folk = 2; folk <= MAX_FOLK; folk++) {
            assertTrue(VillageMath.searchReach(folk) >= VillageMath.searchReach(folk - 1),
                "search reach shrank at " + folk);
            assertTrue(VillageMath.storesRadius(folk) >= VillageMath.storesRadius(folk - 1),
                "stores radius shrank at " + folk);
            assertTrue(VillageMath.loadedRadiusChunks(folk)
                    >= VillageMath.loadedRadiusChunks(folk - 1),
                "loaded ring shrank at " + folk);
        }
    }

    @Test
    @DisplayName("ten folk come out four farmers, three miners, two woodcutters, one smelter")
    void theStatedShapeHolds() {
        // The one number in this file that IS a promise: it is what was asked
        // for, in those words, and nothing since is allowed to have moved it.
        int[] shape = VillageMath.shapeOf(10);
        assertEquals(4, shape[0], "farmers");
        assertEquals(3, shape[1], "miners");
        assertEquals(2, shape[2], "woodcutters");
        assertEquals(1, shape[3], "smelter");
        assertEquals(0, shape[4], "no watch in a village of ten");
    }

    @Test
    @DisplayName("a village never assigns a trade it is too small for")
    void noTradeOpensEarly() {
        for (int folk = 1; folk <= MAX_FOLK; folk++) {
            int[] shape = VillageMath.shapeOf(folk);
            for (int i = 0; i < shape.length; i++) {
                if (shape[i] > 0) {
                    assertTrue(folk >= VillageMath.SLOTS[i].from(),
                        "slot " + i + " staffed at " + folk
                        + " folk but opens at " + VillageMath.SLOTS[i].from());
                }
            }
        }
    }

    @Test
    @DisplayName("everybody gets a trade — nobody is left over")
    void everyHandIsEmployed() {
        for (int folk = 1; folk <= MAX_FOLK; folk++) {
            int[] shape = VillageMath.shapeOf(folk);
            int placed = 0;
            for (int n : shape) placed += n;
            assertEquals(folk, placed, "at " + folk + " folk, " + placed + " were given trades");
        }
    }

    @Test
    @DisplayName("growing far enough opens every trade there is")
    void growthReachesEveryTrade() {
        // The point of breeding: at the default cap of twenty, no role in the
        // mod should still be unreachable.
        int[] shape = VillageMath.shapeOf(20);
        for (int i = 0; i < shape.length; i++) {
            assertTrue(shape[i] > 0,
                "slot " + i + " is still empty in a village of twenty");
        }
    }

    @Test
    @DisplayName("the last hand in a trade is never spare")
    void neverStripsTheLastOfATrade() {
        // Re-badging must never take a village's only smelter to staff
        // something else — that is the failure it was written to fix.
        for (int folk = 1; folk <= MAX_FOLK; folk++) {
            for (int slot = 0; slot < VillageMath.SLOTS.length; slot++) {
                assertTrue(!VillageMath.overStaffed(folk, slot, 1),
                    "a lone hand in slot " + slot + " read as spare at " + folk + " folk");
            }
        }
    }

    @Test
    @DisplayName("a trade at exactly its share is not over-staffed")
    void shareIsNotSurplus() {
        for (int folk = 1; folk <= MAX_FOLK; folk++) {
            int[] shape = VillageMath.shapeOf(folk);
            for (int i = 0; i < shape.length; i++) {
                if (shape[i] == 0) continue;
                assertTrue(!VillageMath.overStaffed(folk, i, shape[i]),
                    "slot " + i + " read as over-staffed at its own share ("
                    + shape[i] + " of " + folk + ")");
            }
        }
    }
}
