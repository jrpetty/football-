package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How much ground a founding levels, and the shape of its edges: arithmetic, checked without a world. */
class FoundingPlanTest {

    /** Made-up ground round a heart: every column dry land at the height the function gives. */
    private static FoundingPlan.Ground ground(int folk, IntBinaryOperator height) {
        int radius = FoundingPlan.coreRadius(folk);
        FoundingPlan.Ground g = new FoundingPlan.Ground(radius + FoundingPlan.BAND_MAX);
        for (int i = 0; i < g.side * g.side; i++) {
            g.ground[i] = height.applyAsInt(g.dx(i), g.dz(i));
            g.kind[i] = FoundingPlan.reach(g.dx(i), g.dz(i)) > g.outer ? FoundingPlan.OUTSIDE : FoundingPlan.LAND;
        }
        return g;
    }

    private static int plan(FoundingPlan.Ground g, int folk, long seed) {
        int radius = FoundingPlan.coreRadius(folk);
        int level = FoundingPlan.level(g, radius, 63);
        FoundingPlan.make(g, radius, level, FoundingPlan.campRadius(folk), seed);
        return level;
    }

    /** The steepest step between neighbours anywhere from just inside the levelled square out. */
    private static int steepest(FoundingPlan.Ground g, int radius) {
        int worst = 0;
        for (int i = 0; i < g.side * g.side; i++) {
            int dx = g.dx(i), dz = g.dz(i);
            double r = FoundingPlan.reach(dx, dz);
            if (r <= radius - 4 || r > g.outer - 1) continue;
            if (FoundingPlan.reach(dx + 1, dz) <= g.outer - 1) worst = Math.max(worst, Math.abs(g.target[i + 1] - g.target[i]));
            if (FoundingPlan.reach(dx, dz + 1) <= g.outer - 1) worst = Math.max(worst, Math.abs(g.target[i + g.side] - g.target[i]));
        }
        return worst;
    }

    private static int hill(int dx, int dz, int half, int height) {
        int ring = Math.max(Math.abs(dx), Math.abs(dz));
        return ring > half ? 0 : (int) Math.floor(height * (1.0 - ring / (double) (half + 1)));
    }

    @Test
    @DisplayName("more folk level more ground, five hundred the whole of the town's plan, the camp always inside")
    void theGroundGrowsWithTheFolk() {
        int last = 0;
        for (int n = FoundingPlan.MIN_FOLK; n <= FoundingPlan.MAX_FOLK; n++) {
            int r = FoundingPlan.coreRadius(n);
            assertTrue(r >= last, "a bigger party never levels less: " + n + " folk, " + r + " after " + last);
            assertTrue(r >= FoundingPlan.BARE, "the square and the lots facing it at least: " + n + " folk, " + r);
            assertTrue(FoundingPlan.campRadius(n) + 4 < r, "the camp lies inside the levelled ground: " + n + " folk");
            last = r;
        }
        assertTrue(FoundingPlan.coreRadius(FoundingPlan.MAX_FOLK) >= TownPlan.reach(),
            "five hundred level the plan's three rings: " + FoundingPlan.coreRadius(FoundingPlan.MAX_FOLK));
        // Forty percent wider than the plan's first rings strictly need (FoundingPlan.WIDER).
        assertTrue(FoundingPlan.coreRadius(8) <= 45, "a party of eight levels its square and little more: " + FoundingPlan.coreRadius(8));
        assertTrue(FoundingPlan.coreRadius(40) >= 54, "forty level a hundred and nine blocks across or more: " + FoundingPlan.coreRadius(40));
    }

    @Test
    @DisplayName("the square ends flat and the edge slopes: no step over two, however many come")
    void flatInsideSlopedAtTheEdge() {
        for (int folk : new int[]{ 8, 20, 100, 500 }) {
            int radius = FoundingPlan.coreRadius(folk);
            FoundingPlan.Ground g = ground(folk, (dx, dz) -> 70 + (int) Math.round(6 * Math.sin(dx / 13.0) * Math.cos(dz / 17.0))
                + hill(dx - radius, dz, 16, 12));
            int level = plan(g, folk, 99L + folk);
            for (int i = 0; i < g.side * g.side; i++) {
                if (FoundingPlan.reach(g.dx(i), g.dz(i)) <= radius - 3) {
                    assertEquals(level, g.target[i], "inside the square, level: " + g.dx(i) + "," + g.dz(i));
                }
            }
            int worst = steepest(g, radius);
            assertTrue(worst <= 2, folk + " folk: the edge has a step of " + worst);
        }
    }

    @Test
    @DisplayName("a mountain cut through stands back on a slope, and the land within the slope is left alone")
    void mountainsStandBack() {
        int folk = 100, radius = FoundingPlan.coreRadius(folk);
        FoundingPlan.Ground g = ground(folk, (dx, dz) -> 64 + (int) Math.max(0, 60 - Math.max(0, Math.hypot(dx - 70, dz) - 10)));
        int level = plan(g, folk, 7L);
        assertEquals(64, level);
        assertTrue(steepest(g, radius) <= 2, "no cliff in the cut: " + steepest(g, radius));
        // Away from the mountain the land is flat at the level already: nothing there is touched.
        int far = g.index(-radius - 30, 0);
        assertEquals(g.ground[far], g.target[far]);
    }

    @Test
    @DisplayName("a pond inside is filled; a river through is left, its banks never cut below it")
    void pondsAndRivers() {
        int folk = 50, radius = FoundingPlan.coreRadius(folk);
        FoundingPlan.Ground g = ground(folk, (dx, dz) -> 66);
        for (int i = 0; i < g.side * g.side; i++) {
            int dx = g.dx(i), dz = g.dz(i);
            if (g.kind[i] == FoundingPlan.OUTSIDE) continue;
            boolean pond = (dx - 10) * (dx - 10) + (dz - 10) * (dz - 10) <= 6;
            boolean river = Math.abs(dz + 20) <= 2;
            if (pond || river) {
                g.kind[i] = FoundingPlan.WATER;
                g.fluid[i] = 66;
                g.ground[i] = pond ? 65 : 62;
            }
        }
        int level = plan(g, folk, 3L);
        assertEquals(FoundingPlan.FILL, g.kind[g.index(10, 10)], "the pond is filled");
        assertEquals(level, g.target[g.index(10, 10)], "to the level of the square");
        assertEquals(FoundingPlan.KEEP, g.kind[g.index(0, -20)], "the river is left");
        for (int dx = -radius; dx <= radius; dx++) {
            assertTrue(g.target[g.index(dx, -23)] >= 66 && g.target[g.index(dx, -17)] >= 66,
                "the river's banks hold it at " + dx);
        }
        assertTrue(g.filledPonds == 1 && g.keptWater > 0);
    }

    @Test
    @DisplayName("the square is flat whatever water stands about it: a stream up the hillside, a lake below")
    void flatBesideWater() {
        int folk = 40, radius = FoundingPlan.coreRadius(folk);
        // A stream high on the hillside out past the north-west edge, and a lake down past the south-east.
        FoundingPlan.Ground g = ground(folk, (dx, dz) -> 79 + (int) Math.round(4 * Math.sin(dx / 11.0) * Math.cos(dz / 9.0)));
        for (int i = 0; i < g.side * g.side; i++) {
            int dx = g.dx(i), dz = g.dz(i);
            if (g.kind[i] == FoundingPlan.OUTSIDE) continue;
            if (Math.abs(dx + dz + 70) <= 1 && dx < -20) {
                g.kind[i] = FoundingPlan.WATER;
                g.fluid[i] = 95;
                g.ground[i] = 94;
            } else if (dx + dz > 70) {
                g.kind[i] = FoundingPlan.WATER;
                g.fluid[i] = 63;
                g.ground[i] = 58;
            }
        }
        int level = plan(g, folk, 5L);
        int off = 0;
        for (int i = 0; i < g.side * g.side; i++) {
            if (g.kind[i] != FoundingPlan.LAND || !FoundingPlan.levelled(g.dx(i), g.dz(i), radius, 5L) || g.target[i] == level) continue;
            // Only the bank right beside the stream where it comes into the square may stand up, to dam it.
            int x = i % g.side;
            boolean beside = false;
            for (int j : new int[]{ x > 0 ? i - 1 : -1, x < g.side - 1 ? i + 1 : -1, i - g.side, i + g.side }) {
                if (j >= 0 && j < g.kind.length && g.kind[j] == FoundingPlan.KEEP && g.fluid[j] > level) beside = true;
            }
            if (!beside) off++;
        }
        assertEquals(0, off, "every column of the square at the level, the stream's and the lake's sides too");
        // The stream is still held in by its banks out past the square.
        for (int i = 0; i < g.side * g.side; i++) {
            if (g.kind[i] != FoundingPlan.KEEP || g.fluid[i] != 95) continue;
            int x = i % g.side;
            for (int j : new int[]{ x > 0 ? i - 1 : -1, x < g.side - 1 ? i + 1 : -1 }) {
                // (Never cut below the water, nor below what it was where it was lower already.)
                if (j >= 0 && g.kind[j] == FoundingPlan.LAND) {
                    assertTrue(g.target[j] >= Math.min(95, g.ground[j]), "the stream's bank holds at " + g.dx(j) + "," + g.dz(j));
                }
            }
        }
    }

    @Test
    @DisplayName("a stream above the level running through the square is let out there, and dammed where it comes in")
    void streamThroughTheSquare() {
        int folk = 40, radius = FoundingPlan.coreRadius(folk);
        FoundingPlan.Ground g = ground(folk, (dx, dz) -> dx < 0 ? 84 : 76);
        for (int i = 0; i < g.side * g.side; i++) {
            int dx = g.dx(i), dz = g.dz(i);
            if (g.kind[i] == FoundingPlan.OUTSIDE) continue;
            if (Math.abs(dz) <= 1 && dx < 0) {                     // runs in from the west, on the high ground
                g.kind[i] = FoundingPlan.WATER;
                g.fluid[i] = 85;
                g.ground[i] = 83;
            }
        }
        int level = plan(g, folk, 9L);
        assertTrue(level < 85, "the level is under the stream: " + level);
        assertTrue(g.drained > 0, "the stream is let out of the square");
        assertEquals(FoundingPlan.LAND, g.kind[g.index(-5, 0)], "inside it is land now");
        assertEquals(level, g.target[g.index(-5, 0)], "and cut to the level");
        int off = 0;
        for (int i = 0; i < g.side * g.side; i++) {
            if (g.kind[i] != FoundingPlan.LAND || !FoundingPlan.levelled(g.dx(i), g.dz(i), radius, 9L) || g.target[i] == level) continue;
            // Only the bank right beside the stream left outside may stand up, to hold it back.
            int x = i % g.side;
            boolean beside = false;
            for (int j : new int[]{ x > 0 ? i - 1 : -1, x < g.side - 1 ? i + 1 : -1, i - g.side, i + g.side }) {
                if (j >= 0 && j < g.kind.length && g.kind[j] == FoundingPlan.KEEP) beside = true;
            }
            if (!beside) off++;
        }
        assertEquals(0, off, "the rest of the square is flat");
    }

    @Test
    @DisplayName("nothing built is moved, nor the ground about it, and the ground round that slopes to it")
    void buildingsAreLeftAlone() {
        int folk = 20;
        FoundingPlan.Ground g = ground(folk, (dx, dz) -> 70 + (dx > 0 ? 8 : 0) * (Math.abs(dz) < 30 ? 1 : 0));
        for (int dx = 10; dx <= 12; dx++) {
            for (int dz = -1; dz <= 1; dz++) g.kind[g.index(dx, dz)] = FoundingPlan.BUILT;
        }
        plan(g, folk, 11L);
        for (int dx = 8; dx <= 14; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int i = g.index(dx, dz);
                assertEquals(FoundingPlan.PROTECT, g.kind[i], "left alone at " + dx + "," + dz);
                assertEquals(g.ground[i], g.target[i], "and not moved at " + dx + "," + dz);
            }
        }
        // The ground round it is held to it by a slope, so the house does not end on a pillar.
        for (int dz = -3; dz <= 3; dz++) {
            for (int dx = 4; dx < 8; dx++) {
                int step = Math.abs(g.target[g.index(dx + 1, dz)] - g.target[g.index(dx, dz)]);
                assertTrue(step <= 2, "the ground slopes to the building at " + dx + "," + dz + ": " + step);
            }
        }
    }

    @Test
    @DisplayName("by the water, never levelled below the sea; a dry basin keeps its own height; the heart is worked first")
    void neverBelowTheSea() {
        int folk = 12;
        FoundingPlan.Ground g = ground(folk, (dx, dz) -> 55);
        // Dry ground far under the sea's height (a flat world, a deep valley) is not built up into a mound.
        assertEquals(55, FoundingPlan.level(g, FoundingPlan.coreRadius(folk), 63));
        assertEquals(-60, FoundingPlan.level(ground(folk, (dx, dz) -> -60), FoundingPlan.coreRadius(folk), 63));
        // Beside water, a little under the sea's height, it is lifted out of the wet.
        g.kind[g.index(g.outer, 0)] = FoundingPlan.WATER;
        assertEquals(62, FoundingPlan.level(g, FoundingPlan.coreRadius(folk), 63));
        g.kind[g.index(g.outer, 0)] = FoundingPlan.LAND;
        plan(g, folk, 5L);
        assertEquals(g.index(0, 0), g.order[0], "the heart first");
        double before = 0;
        for (int j = 0; j < g.order.length; j++) {
            double r = FoundingPlan.reach(g.dx(g.order[j]), g.dz(g.order[j]));
            assertTrue(r + 1e-3 >= before, "outwards");
            before = r;
            // (Ordered to a thousandth of a block: the camp's own edge may take a sliver more.)
            if (j < g.campIndex) assertTrue(r <= FoundingPlan.campRadius(folk) + 1e-3, "the camp before the rest");
            else assertTrue(r > FoundingPlan.campRadius(folk), "and nothing of the camp after it");
        }
    }
}
