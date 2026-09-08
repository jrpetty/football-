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

    private static final int MAX_FOLK = 500;

    @Test
    @DisplayName("a village either sees its own output, or has carriers to fetch it")
    void nothingIsEverStranded() {
        // THE BUG THIS EXISTS FOR: stores were read from 32 blocks while plots
        // were staked 60+ out, so a village could not see its own harvest, the
        // plan read "short of food" for ever and the ages never advanced.
        // A town too big to see its own edges is allowed — but only if it has
        // somebody whose job is carrying the far fields' output to the middle.
        for (int folk = 1; folk <= MAX_FOLK; folk++) {
            if (VillageMath.storesRadius(folk) >= VillageMath.plotReach(folk)) continue;
            assertTrue(VillageMath.shapeOf(folk)[VillageMath.HAUL] >= 1,
                "at " + folk + " folk the plots reach " + VillageMath.plotReach(folk)
                + " past stores of " + VillageMath.storesRadius(folk)
                + " and there is no carrier");
        }
    }

    @Test
    @DisplayName("a settlement small enough to be watched over entirely, is")
    void smallVillagesRunUnattended() {
        // Up to the point where a place outgrows the ring, every plot must sit
        // inside it — that is what "grows while you are away" means.
        for (int folk = 1; folk <= MAX_FOLK; folk++) {
            if (VillageMath.loadedRadiusChunks(folk) >= VillageMath.MAX_LOADED_RADIUS) continue;
            assertTrue(VillageMath.loadedRadiusBlocks(folk) >= VillageMath.plotReach(folk),
                "at " + folk + " folk the ring is " + VillageMath.loadedRadiusBlocks(folk)
                + " but plots go out to " + VillageMath.plotReach(folk)
                + " and the ring is not even at its cap");
        }
    }

    @Test
    @DisplayName("there is room on the ground for everybody's plot")
    void everyPlotHasSomewhereToGo() {
        // Ground is claimed whole and never shared, so the disc a village
        // searches has to hold every plot in it with room to spare for the
        // ground that suits nobody. Three times the bare footprint.
        for (int folk = 8; folk <= MAX_FOLK; folk++) {
            double disc = Math.PI * Math.pow(VillageMath.plotReach(folk), 2);
            double needed = 3.0 * folk * averagePlotFootprint();
            assertTrue(disc >= needed,
                "at " + folk + " folk the search disc is " + (long) disc
                + " but the plots want " + (long) needed);
        }
    }

    /** Mean plot footprint with elbow room, weighted by the trade shares. */
    private static double averagePlotFootprint() {
        int totalWeight = 0;
        for (VillageMath.Slot s : VillageMath.SLOTS) totalWeight += s.weight();
        // farm 8, mine 8, wood 14, the rest 6 — plus two blocks of elbow.
        int[] radii = { 8, 8, 14, 6, 6, 6, 6, 6, 6 };
        double sum = 0;
        for (int i = 0; i < VillageMath.SLOTS.length; i++) {
            double side = 2 * (radii[i] + 2) + 1;
            sum += VillageMath.SLOTS[i].weight() * side * side;
        }
        return sum / totalWeight;
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
        // The point of breeding: by twenty, no role in the mod is unreachable.
        int[] shape = VillageMath.shapeOf(20);
        for (int i = 0; i < shape.length; i++) {
            assertTrue(shape[i] > 0,
                "slot " + i + " is still empty in a village of twenty");
        }
    }

    @Test
    @DisplayName("a town of a hundred keeps the shape a village of ten had")
    void theShapeSurvivesScale() {
        // Four farmers to three miners to two woodcutters is the ratio that
        // was asked for. It has to still read that way at a hundred, or the
        // ratio was only ever a special case of ten.
        int[] shape = VillageMath.shapeOf(100);
        assertTrue(shape[0] > shape[1] && shape[1] > shape[2],
            "farmers/miners/woodcutters out of order at a hundred: "
            + shape[0] + "/" + shape[1] + "/" + shape[2]);
        double farmToMine = shape[0] / (double) shape[1];
        double mineToWood = shape[1] / (double) shape[2];
        assertTrue(Math.abs(farmToMine - 4.0 / 3.0) < 0.25,
            "farmers to miners is " + farmToMine + ", wanted about 1.33");
        assertTrue(Math.abs(mineToWood - 1.5) < 0.25,
            "miners to woodcutters is " + mineToWood + ", wanted about 1.5");
        for (int i = 0; i < shape.length; i++) {
            assertTrue(shape[i] > 0, "slot " + i + " empty in a town of a hundred");
        }
    }

    @Test
    @DisplayName("the larder is sized to the mouths that eat out of it")
    void foodCoversThePopulation() {
        // A flat sixty-four is a fortnight for ten and twenty minutes for a
        // hundred. Whatever the number is, it has to be at least a day's real
        // meals or a town declares itself well fed and starves.
        for (int folk = 1; folk <= MAX_FOLK; folk++) {
            int aDay = folk * VillageMath.MEALS_PER_DAY;
            assertTrue(VillageMath.foodWanted(folk) >= aDay,
                "at " + folk + " folk the larder target is " + VillageMath.foodWanted(folk)
                + " but they eat " + aDay + " in a day");
        }
    }

    @Test
    @DisplayName("the iron target actually pays for the armour it promises")
    void ironCoversTheWatchAndTheTools() {
        for (int folk = 1; folk <= MAX_FOLK; folk++) {
            int guards = Math.max(1, VillageMath.shapeOf(folk)[VillageMath.GUARD]);
            assertTrue(VillageMath.ironForTheWatch(folk) >= guards * VillageMath.ARMOUR_SET,
                "at " + folk + " folk the watch's armour is under-funded");
            assertTrue(
                VillageMath.ironWanted(folk)
                    >= VillageMath.ironForTheWatch(folk) + VillageMath.ironForTools(folk),
                "at " + folk + " folk the iron target does not cover watch + tools");
            assertTrue(VillageMath.ironForEveryone(folk) >= VillageMath.ironForTheWatch(folk),
                "arming everybody costs less than arming the watch at " + folk);
        }
    }

    @Test
    @DisplayName("what the place needs only ever grows with the place")
    void needsAreMonotonic() {
        for (int folk = 2; folk <= MAX_FOLK; folk++) {
            assertTrue(VillageMath.foodWanted(folk) >= VillageMath.foodWanted(folk - 1),
                "food target shrank at " + folk);
            assertTrue(VillageMath.ironWanted(folk) >= VillageMath.ironWanted(folk - 1),
                "iron target shrank at " + folk);
            assertTrue(VillageMath.diamondsWanted(folk) >= VillageMath.diamondsWanted(folk - 1),
                "diamond target shrank at " + folk);
            assertTrue(VillageMath.stoneWanted(folk) >= VillageMath.stoneWanted(folk - 1),
                "stone target shrank at " + folk);
            assertTrue(VillageMath.timberWanted(folk) >= VillageMath.timberWanted(folk - 1),
                "timber target shrank at " + folk);
        }
    }

    @Test
    @DisplayName("there are enough houses for everyone to have a bed in one")
    void housesCoverThePopulation() {
        for (int folk = 1; folk <= MAX_FOLK; folk++) {
            assertTrue(VillageMath.housesWanted(folk, true) * 3 >= folk - 2,
                "at " + folk + " folk the crowded target of "
                + VillageMath.housesWanted(folk, true) + " houses sleeps too few");
            assertTrue(VillageMath.housesWanted(folk, true)
                    >= VillageMath.housesWanted(folk, false),
                "the crowded target is smaller than the roomy one at " + folk);
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
