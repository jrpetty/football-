package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [house34] The terraces' places on the plan (TerraceLots): every row stands on the plan's own ground (two lots, never
 * a street or the square), every house's front door opens straight onto a street, and the two kinds of long street
 * front are both offered, for the narrow row (twenty-one across) and the wide (twenty-two).
 */
class TerraceLotsTest {

    private static int[] facing(int back) {
        return switch (back) {
            case TownPlan.NORTH -> new int[]{ 0, -1 };
            case TownPlan.EAST -> new int[]{ 1, 0 };
            case TownPlan.SOUTH -> new int[]{ 0, 1 };
            default -> new int[]{ -1, 0 };
        };
    }

    private static int[] right(int back) {
        int[] f = facing(back);
        return new int[]{ -f[1], f[0] };            // the back turned clockwise: north to east, east to south...
    }

    /** The lot squares a cell of the plan covers, as cellKeys of their middles. */
    private static Set<Long> ground(TownPlan.Lot l) {
        Set<Long> out = new HashSet<>();
        for (long c : l.cells()) out.add(c);
        return out;
    }

    private static long lotOf(int x, int z) {
        for (TownPlan.Lot l : TownPlan.lots()) {
            if (l.kind() != TownPlan.Kind.LOT) continue;
            if (Math.abs(x - l.x()) <= TownPlan.LOT / 2 && Math.abs(z - l.z()) <= TownPlan.LOT / 2) return l.cells()[0];
        }
        return Long.MIN_VALUE;
    }

    @Test
    void rowsStandOnTheirLotsWithEveryDoorOnAStreet() {
        for (int across : new int[]{ 21, 22 }) {
            List<TownPlan.Lot> lots = TerraceLots.lots(across);
            int longs = 0, pairs = 0;
            assertTrue(lots.size() > 40, "rows offered for a row " + across + " across: " + lots.size());
            for (TownPlan.Lot l : lots) {
                assertTrue(TerraceLots.isRow(l), "a row's place: " + l);
                if (l.use().equals("row-long")) longs++; else pairs++;
                assertEquals(across - 11, l.halfAcross(), "its half across");
                assertEquals(5, l.halfDeep(), "its half depth");
                int[] f = facing(l.back()), r = right(l.back());
                Set<Long> own = ground(l);
                for (int dx = -10; dx <= across - 11; dx++) {
                    for (int dz = -5; dz <= 5; dz++) {
                        int x = l.x() + dx * r[0] + dz * f[0], z = l.z() + dx * r[1] + dz * f[1];
                        assertTrue(!TownPlan.isStreet(x, z) && !TownPlan.isSquare(x, z), "row " + l + " stands on no street: " + x + ", " + z);
                        long lot = lotOf(x, z);
                        assertTrue(own.contains(lot), "row " + l + " stands on its own two lots: " + x + ", " + z);
                    }
                    // straight out of the front, the street
                    int sx = l.x() + dx * r[0] - 6 * f[0], sz = l.z() + dx * r[1] - 6 * f[1];
                    assertTrue(TownPlan.isStreet(sx, sz), "row " + l + " fronts a street at dx " + dx + ": " + sx + ", " + sz);
                }
                int[] front = TownPlan.front(l);
                assertTrue(front != null && TownPlan.isStreet(front[0], front[1]), "the plan finds its street before it: " + l);
            }
            assertTrue(longs >= 8 && pairs >= 8, "both kinds of street front offered: " + longs + " long lots, " + pairs + " pairs");
        }
    }

    @Test
    void theTerracesAskForARow() {
        assertEquals("row", TownPlan.placeFor("terrace"));
        assertEquals("row", TownPlan.placeFor("terrace2"));
        assertEquals("row", TownPlan.placeFor("terrace3"));
        assertEquals(TerraceLots.lots(21), TownPlan.candidates("terrace"));
        assertEquals(TerraceLots.lots(22), TownPlan.candidates("terrace3"));
    }

    /** [house34c] A corner terrace's place: three home lots of one block, a pair along its street and the lot behind the
     *  pair's right-hand one, with a street down that side; a crescent's, the whole block. */
    @Test
    void theCornersAndTheBlocks() {
        assertEquals("row", TownPlan.placeFor("terrace4"));
        assertEquals("row", TownPlan.placeFor("terrace5"));
        assertEquals(TerraceLots.corners(), TownPlan.candidates("terrace4"));
        assertEquals(TerraceLots.blocks(), TownPlan.candidates("terrace5"));
        assertTrue(TerraceLots.corners().size() >= 8, "corners enough: " + TerraceLots.corners().size());
        assertTrue(TerraceLots.blocks().size() >= 4, "blocks enough: " + TerraceLots.blocks().size());
        java.util.Map<Long, TownPlan.Lot> home = new java.util.HashMap<>();
        for (TownPlan.Lot l : TownPlan.lots()) if (l.kind() == TownPlan.Kind.LOT && l.use().equals("home")) home.put(l.cells()[0], l);
        for (TownPlan.Lot c : TerraceLots.corners()) {
            assertEquals(3, c.cells().length, "three lots");
            assertEquals(3, new HashSet<>(List.of(c.cells()[0], c.cells()[1], c.cells()[2])).size(), "three different lots");
            for (long cell : c.cells()) assertTrue(home.containsKey(cell), "each a home lot");
            int[] f = facing(c.back()), r = right(c.back());
            TownPlan.Lot third = home.get(c.cells()[2]);
            // the third lot behind the pair, on its right: its middle 5.5 to the right of the row's middle and a lot back
            assertEquals(c.x() + 6 * r[0] + 11 * f[0], third.x(), c + " third " + third.x() + "," + third.z());
            assertEquals(c.z() + 6 * r[1] + 11 * f[1], third.z(), c + " third " + third.x() + "," + third.z());
            // the side street down its right, the first street before it
            assertTrue(TownPlan.isStreet(c.x() + 12 * r[0], c.z() + 12 * r[1]), "the side street: " + c);
            assertTrue(TownPlan.isStreet(c.x() - 6 * f[0], c.z() - 6 * f[1]), "the street before it: " + c);
            // nothing of the plan's streets under it
            for (int dx = -10; dx <= 11; dx++) for (int dz = -5; dz <= 15; dz++) {
                if (dx <= 0 && dz >= 6) continue;
                int x = c.x() + dx * r[0] + dz * f[0], z = c.z() + dx * r[1] + dz * f[1];
                assertTrue(!TownPlan.isStreet(x, z), "no street under the corner at " + dx + "," + dz + " of " + c);
            }
        }
        for (TownPlan.Lot b : TerraceLots.blocks()) {
            assertEquals(4, b.cells().length, "a whole block");
            int[] f = facing(b.back()), r = right(b.back());
            for (int dx = -10; dx <= 11; dx++) for (int dz = -5; dz <= 16; dz++) {
                int x = b.x() + dx * r[0] + dz * f[0], z = b.z() + dx * r[1] + dz * f[1];
                assertTrue(!TownPlan.isStreet(x, z), "no street under the crescent at " + dx + "," + dz + " of " + b);
            }
        }
    }
}
