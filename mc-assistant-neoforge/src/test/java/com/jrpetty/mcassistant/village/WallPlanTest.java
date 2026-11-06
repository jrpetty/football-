package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [defence] The town wall's plan is arithmetic over the town plan, and can be checked without a world. */
class WallPlanTest {

    private static int[] box(TownPlan.Lot l) {
        boolean turned = l.back() == TownPlan.EAST || l.back() == TownPlan.WEST;
        return new int[]{ l.x(), l.z(), turned ? l.halfDeep() : l.halfAcross(), turned ? l.halfAcross() : l.halfDeep() };
    }

    private static List<int[]> lotsWithin(int reach) {
        List<int[]> out = new ArrayList<>();
        for (TownPlan.Lot l : TownPlan.lots()) {
            int[] b = box(l);
            if (Math.max(Math.abs(b[0]) + b[2], Math.abs(b[1]) + b[3]) <= reach) out.add(b);
        }
        return out;
    }

    @Test
    @DisplayName("the first wall stands past the first block of lots with its outer street clear inside it")
    void firstWall() {
        WallPlan.Sides s = WallPlan.around(lotsWithin(38));
        assertEquals(WallPlan.first(), s);
        assertEquals(42, s.n());
        for (int[] b : lotsWithin(38)) {
            assertTrue(WallPlan.encloses(s, b[0], b[1], b[2], b[3]), "lot at " + b[0] + "," + b[1] + " inside the wall");
            assertFalse(WallPlan.onRing(s, b[0], b[1], b[2], b[3]), "lot at " + b[0] + "," + b[1] + " not on the wall's ring");
        }
        // The outer street inside the wall: no wall on it.
        for (int a = -41; a <= 41; a++) {
            for (int u = 39; u <= 41; u++) {
                assertTrue(WallPlan.cell(s, WallPlan.towers(s), a, -u) == null, "no wall on the street at " + a + "," + -u);
            }
        }
    }

    @Test
    @DisplayName("the lots on the wall's ring are kept off; those past it are not, and are outside")
    void theRing() {
        WallPlan.Sides s = WallPlan.first();
        int kept = 0, past = 0;
        for (TownPlan.Lot l : TownPlan.lots()) {
            int[] b = box(l);
            int near = Math.max(Math.abs(b[0]) - b[2], Math.abs(b[1]) - b[3]);
            if (near >= 42 && near <= 48) {
                assertTrue(WallPlan.onRing(s, b[0], b[1], b[2], b[3]), "lot at " + b[0] + "," + b[1] + " on the ring");
                kept++;
            }
            if (near >= 49) {
                assertFalse(WallPlan.onRing(s, b[0], b[1], b[2], b[3]), "lot at " + b[0] + "," + b[1] + " past the ring");
                assertFalse(WallPlan.encloses(s, b[0], b[1], b[2], b[3]));
                past++;
            }
        }
        assertTrue(kept > 0 && past > 0, kept + " kept, " + past + " past");
    }

    @Test
    @DisplayName("a lot past the wall moves that side out a ring, and only that side")
    void growing() {
        List<int[]> boxes = lotsWithin(38);
        boxes.add(new int[]{ 58, 0, 5, 5 });                    // an outward lot of the next ring, to the east
        WallPlan.Sides s = WallPlan.around(boxes);
        assertEquals(67, s.e());
        assertEquals(42, s.n());
        assertEquals(42, s.w());
        assertTrue(WallPlan.encloses(s, 58, 0, 5, 5));
        assertEquals(67, WallPlan.first().max(s).e());
        assertEquals(s, WallPlan.Sides.decode(s.encode()));
    }

    @Test
    @DisplayName("towers at the corners, two at each gate, none more than thirty-two apart and none on a street")
    void towers() {
        for (WallPlan.Sides s : new WallPlan.Sides[]{ WallPlan.first(), new WallPlan.Sides(42, 67, 92, 67), new WallPlan.Sides(92, 92, 92, 92) }) {
            List<WallPlan.Tower> ts = WallPlan.towers(s);
            assertEquals(4, ts.stream().filter(t -> t.kind() == WallPlan.TowerKind.CORNER).count());
            assertEquals(8, ts.stream().filter(t -> t.kind() == WallPlan.TowerKind.FLANK).count());
            for (int side = 0; side < 4; side++) {
                List<Integer> along = new ArrayList<>();
                for (WallPlan.Tower t : ts) {
                    int[] m = t.middle();
                    if (t.kind() != WallPlan.TowerKind.CORNER && t.side() == side) along.add(WallPlan.across(side, m[0], m[1]));
                }
                along.add(WallPlan.hi(s, side) + 4);
                along.add(WallPlan.lo(s, side) - 4);
                along.sort(Integer::compare);
                for (int i = 1; i < along.size(); i++) {
                    int gap = along.get(i) - along.get(i - 1);
                    assertTrue(gap <= 32 && gap >= WallPlan.TOWER, "towers " + gap + " apart on side " + side + " of " + s);
                }
            }
            Set<Long> seen = new HashSet<>();
            for (WallPlan.Tower t : ts) {
                for (int x = t.x0(); x <= t.x1(); x++) {
                    for (int z = t.z0(); z <= t.z1(); z++) {
                        assertTrue(seen.add(TownPlan.cellKey(x, z)), "towers overlap at " + x + "," + z);
                        assertFalse(WallPlan.inside(s, x, z), "a tower stands into the town at " + x + "," + z);
                        if (t.kind() == WallPlan.TowerKind.MID) {
                            assertFalse(WallPlan.onCrossStreet(WallPlan.across(t.side(), x, z)), "a tower on a cross street");
                        }
                        assertFalse(Math.abs(WallPlan.across(t.side(), x, z)) <= TownPlan.AVENUE && t.kind() != WallPlan.TowerKind.CORNER,
                            "a tower on an avenue");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("the wall is one closed ring: every inside column next to the wall, and the gate on each avenue")
    void closed() {
        WallPlan.Sides s = new WallPlan.Sides(42, 67, 42, 42);
        List<WallPlan.Tower> ts = WallPlan.towers(s);
        // Walking out from the heart in any straight line along a row or a column meets the wall.
        for (int a = -41; a <= 41; a++) {
            boolean met = false;
            for (int u = 1; u < 100 && !met; u++) met = WallPlan.cell(s, ts, a, -u) != null;
            assertTrue(met, "the wall closes the north at " + a);
            met = false;
            for (int u = 1; u < 100 && !met; u++) met = WallPlan.cell(s, ts, u, a) != null;
            assertTrue(met, "the wall closes the east at " + a);
        }
        // The corners closed: diagonally out of the town meets it too.
        for (int[] d : new int[][]{ { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 } }) {
            boolean met = false;
            for (int u = 1; u < 100 && !met; u++) met = WallPlan.cell(s, ts, d[0] * u, d[1] * u) != null;
            assertTrue(met, "the corner closed toward " + d[0] + "," + d[1]);
        }
        for (int side = 0; side < 4; side++) {
            int[] g = WallPlan.offset(side, s.of(side) + 1, 0);
            WallPlan.Cell c = WallPlan.cell(s, ts, g[0], g[1]);
            assertTrue(c != null && c.kind() == WallPlan.Kind.GATE, "a gate on side " + side);
        }
        assertTrue(WallPlan.columns(s, ts).size() > 4 * 80 * 3);
    }

    @Test
    @DisplayName("the steps up: a flight each side of each gate, on the wall's inner face, clear of towers and streets")
    void stairs() {
        for (int steps : new int[]{ 5, 6, 7 }) {
            WallPlan.Sides s = WallPlan.first();
            List<WallPlan.Tower> ts = WallPlan.towers(s);
            List<int[]> flights = WallPlan.stairs(s, ts, steps);
            assertEquals(8, flights.size(), steps + " steps");
            for (int[] f : flights) {
                for (int a = -100; a <= 100; a++) {
                    if (WallPlan.stepAt(f, steps, a) < 0) continue;
                    int[] p = WallPlan.offset(f[0], s.of(f[0]), a);
                    WallPlan.Cell c = WallPlan.cell(s, ts, p[0], p[1]);
                    assertTrue(c != null && c.kind() == WallPlan.Kind.CURTAIN && c.layer() == 0, "a step on the inner face at " + a);
                    assertFalse(WallPlan.onCrossStreet(a));
                }
            }
        }
    }
}
