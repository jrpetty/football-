package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The town plan is arithmetic, and arithmetic can be checked without a world. */
class TownPlanTest {

    private static List<TownPlan.Lot> ordinary() {
        return TownPlan.lots().stream().filter(l -> l.kind() == TownPlan.Kind.LOT).toList();
    }

    @Test
    @DisplayName("no lot stands on a street, on the square, or on another lot")
    void lotsAreClear() {
        Map<Long, TownPlan.Lot> seen = new HashMap<>();
        for (TownPlan.Lot l : ordinary()) {
            for (int dx = -TownPlan.LOT / 2; dx <= TownPlan.LOT / 2; dx++) {
                for (int dz = -TownPlan.LOT / 2; dz <= TownPlan.LOT / 2; dz++) {
                    int x = l.x() + dx, z = l.z() + dz;
                    assertFalse(TownPlan.isStreet(x, z), "lot at " + l.x() + "," + l.z() + " covers a street at " + x + "," + z);
                    assertFalse(TownPlan.isSquare(x, z), "lot at " + l.x() + "," + l.z() + " covers the square at " + x + "," + z);
                    TownPlan.Lot other = seen.put(TownPlan.cellKey(x, z), l);
                    assertTrue(other == null, "lots at " + l.x() + "," + l.z() + " and " + (other == null ? "" : other.x() + "," + other.z()) + " overlap");
                }
            }
        }
    }

    @Test
    @DisplayName("every door opens onto a street")
    void everyDoorIsOnAStreet() {
        for (TownPlan.Lot l : TownPlan.lots()) {
            int reach = (l.kind() == TownPlan.Kind.LONG ? l.halfDeep() : TownPlan.LOT / 2) + 1;
            int dx = 0, dz = 0;
            switch (l.back()) {
                case TownPlan.NORTH -> dz = reach;          // back to the north: the door is on the south side
                case TownPlan.SOUTH -> dz = -reach;
                case TownPlan.EAST -> dx = -reach;
                default -> dx = reach;
            }
            int x = l.x() + dx, z = l.z() + dz;
            assertTrue(TownPlan.isStreet(x, z) || TownPlan.isSquare(x, z),
                "the door of the lot at " + l.x() + "," + l.z() + " (" + l.use() + ") opens onto " + x + "," + z + ", not a street");
        }
    }

    @Test
    @DisplayName("the square has its trades, its great lots and its watchtowers round it")
    void theSquareIsFramed() {
        long civic = TownPlan.lots().stream().filter(l -> l.use().equals("civic")).count();
        long great = TownPlan.lots().stream().filter(l -> l.use().equals("great")).count();
        long corner = TownPlan.lots().stream().filter(l -> l.use().equals("corner")).count();
        assertEquals(4, civic, "four lots face the square for the trades");
        assertEquals(4, great, "four long lots by the square for the great buildings");
        assertEquals(4, corner, "a watchtower lot at each corner of the square");
        assertTrue(ordinary().size() > 150, "room for a town: " + ordinary().size() + " lots");
    }

    @Test
    @DisplayName("each building has somewhere to go, its own kind of place first")
    void everyBuildingHasAPlace() {
        for (String b : List.of("storage", "house", "hall", "chapel", "barracks", "market", "watchtower", "lighthouse",
                "well", "monument", "gateway", "pen", "granary", "guesthouse", "shelter", "smeltery", "workshop")) {
            List<TownPlan.Lot> c = TownPlan.candidates(b);
            assertFalse(c.isEmpty(), b + " has nowhere to go");
            String want = TownPlan.placeFor(b);
            if (!want.equals("home") && !want.equals("edge")) {
                assertEquals(want, c.get(0).use(), b + " goes first to a " + want + " place, not " + c.get(0).use());
            }
        }
    }
}
