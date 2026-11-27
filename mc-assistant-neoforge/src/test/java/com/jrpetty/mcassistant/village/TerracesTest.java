package com.jrpetty.mcassistant.village;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** [city26] A hillside town's terraces: arithmetic, checked on made-up ground. */
class TerracesTest {

    private static final int FOLK = 70;

    private static FoundingPlan.Ground ground(IntBinaryOperator height) {
        int radius = FoundingPlan.coreRadius(FOLK);
        FoundingPlan.Ground g = new FoundingPlan.Ground(radius + FoundingPlan.BAND_MAX);
        for (int i = 0; i < g.side * g.side; i++) {
            g.ground[i] = height.applyAsInt(g.dx(i), g.dz(i));
            g.kind[i] = FoundingPlan.reach(g.dx(i), g.dz(i)) > g.outer ? FoundingPlan.OUTSIDE : FoundingPlan.LAND;
        }
        return g;
    }

    private static int plan(FoundingPlan.Ground g, long seed) {
        int radius = FoundingPlan.coreRadius(FOLK);
        int level = FoundingPlan.level(g, radius, 63);
        FoundingPlan.make(g, radius, level, FoundingPlan.campRadius(FOLK), seed);
        return level;
    }

    /** The earth moved: cut and fill over the whole worked ground. */
    private static long moved(FoundingPlan.Ground g) {
        long n = 0;
        for (int i = 0; i < g.side * g.side; i++) if (g.kind[i] == FoundingPlan.LAND) n += Math.abs(g.target[i] - g.ground[i]);
        return n;
    }

    @Test
    @DisplayName("gentle ground is levelled to one height as before; a hillside is terraced")
    void onlyHillsAreTerraced() {
        FoundingPlan.Ground flat = ground((dx, dz) -> 70 + dx / 12);
        plan(flat, 1L);
        assertTrue(flat.terrace == null, "gentle ground: one level");
        FoundingPlan.Ground hill = ground((dx, dz) -> 70 + (int) Math.round(dx * 0.6));
        plan(hill, 1L);
        assertTrue(hill.terrace != null && hill.terraced > 1000, "a slope of three in five: terraced (" + hill.terraced + " columns)");
    }

    @Test
    @DisplayName("the heart is level; every lot is flat at its street's height; the streets climb a block at a time; less earth moved")
    void terracesAreSound() {
        IntBinaryOperator slope = (dx, dz) -> 70 + (int) Math.round(dx * 0.6 + dz * 0.25);
        FoundingPlan.Ground g = ground(slope);
        int level = plan(g, 7L);
        int radius = FoundingPlan.coreRadius(FOLK);
        assertTrue(g.terrace != null, "terraced");
        // the heart
        for (int dx = -Terraces.CORE; dx <= Terraces.CORE; dx++) {
            for (int dz = -Terraces.CORE; dz <= Terraces.CORE; dz++) {
                int i = g.index(dx, dz);
                if (g.terrace[i] == Terraces.NONE) continue;
                assertEquals(level, g.target[i], "the heart is level at " + dx + "," + dz);
            }
        }
        // each lot flat (where it lies wholly in the terraced ground), and level with its street at the door
        int lots = 0, stepped = 0;
        Map<Integer, Integer> seen = new HashMap<>();
        for (TownPlan.Lot l : TownPlan.lots()) {
            if (l.kind() != TownPlan.Kind.LOT) continue;
            int[] b = TownPlan.box(l);
            boolean whole = true;
            Integer h = null;
            boolean flat = true;
            for (int x = b[0]; x <= b[2] && whole; x++) {
                for (int z = b[1]; z <= b[3]; z++) {
                    if (Math.abs(x) > g.outer || Math.abs(z) > g.outer) { whole = false; break; }
                    int i = g.index(x, z);
                    if (g.terrace[i] == Terraces.NONE) { whole = false; break; }
                    if (h == null) h = g.target[i];
                    else if (g.target[i] != h) flat = false;
                }
            }
            if (!whole || h == null) continue;
            boolean underLong = false;
            for (TownPlan.Lot o : TownPlan.lots()) {
                if (o.kind() != TownPlan.Kind.LONG) continue;
                for (long c : o.cells()) if (c == l.cells()[0]) underLong = true;
            }
            if (underLong) continue;
            lots++;
            assertTrue(flat, "the lot at " + l.x() + "," + l.z() + " is flat");
            int[] f = TownPlan.front(l);
            int fi = g.index(f[0], f[1]);
            if (g.terrace[fi] != Terraces.NONE) assertTrue(Math.abs(g.target[fi] - h) <= 1, "its door level with its street at " + l.x() + "," + l.z());
            seen.merge(h, 1, Integer::sum);
            if (h != level) stepped++;
        }
        assertTrue(lots >= 20 && stepped >= 10 && seen.size() >= 5, lots + " lots, " + stepped + " off the heart's level, " + seen.size() + " heights");
        // the streets: no step of more than a block between neighbouring street cells on the terraces
        for (int dx = -radius; dx < radius; dx++) {
            for (int dz = -radius; dz < radius; dz++) {
                if (!TownPlan.isStreet(dx, dz) && !TownPlan.isSquare(dx, dz)) continue;
                int i = g.index(dx, dz);
                if (g.terrace[i] == Terraces.NONE) continue;
                for (int[] n : new int[][]{ { 1, 0 }, { 0, 1 } }) {
                    int x = dx + n[0], z = dz + n[1];
                    if (!TownPlan.isStreet(x, z) && !TownPlan.isSquare(x, z)) continue;
                    int j = g.index(x, z);
                    if (g.terrace[j] == Terraces.NONE) continue;
                    assertTrue(Math.abs(g.target[j] - g.target[i]) <= 1, "the street climbs a block at a time at " + dx + "," + dz
                        + " (" + g.target[i] + " to " + g.target[j] + ")");
                }
            }
        }
        // less earth moved than one level
        Terraces.class.getName();
        FoundingPlan.Ground one = ground(slope);
        one.hill = false;
        int r1 = FoundingPlan.coreRadius(FOLK);
        FoundingPlan.make(one, r1, FoundingPlan.level(one, r1, 63), FoundingPlan.campRadius(FOLK), 7L);
        assertTrue(moved(g) * 10 < moved(one) * 8, "terraces move less earth: " + moved(g) + " against " + moved(one));
    }

    @Test
    @DisplayName("terraced, the edge still meets the land in a walkable slope: no step over two past the square")
    void theEdgeStillSlopes() {
        int folk = 100, radius = FoundingPlan.coreRadius(folk);
        FoundingPlan.Ground g = new FoundingPlan.Ground(radius + FoundingPlan.BAND_MAX);
        for (int i = 0; i < g.side * g.side; i++) {
            int dx = g.dx(i), dz = g.dz(i);
            g.ground[i] = 64 + (int) Math.max(0, 60 - Math.max(0, Math.hypot(dx - 70, dz) - 10));
            g.kind[i] = FoundingPlan.reach(dx, dz) > g.outer ? FoundingPlan.OUTSIDE : FoundingPlan.LAND;
        }
        int level = FoundingPlan.level(g, radius, 63);
        FoundingPlan.make(g, radius, level, FoundingPlan.campRadius(folk), 7L);
        assertTrue(g.terrace != null, "a mountain at the edge of the square: terraced");
        int worst = 0;
        for (int i = 0; i < g.side * g.side; i++) {
            int dx = g.dx(i), dz = g.dz(i);
            double r = FoundingPlan.reach(dx, dz);
            if (r <= radius + 3 || r > g.outer - 1) continue;
            if (FoundingPlan.reach(dx + 1, dz) <= g.outer - 1 && FoundingPlan.reach(dx + 1, dz) > radius + 3) worst = Math.max(worst, Math.abs(g.target[i + 1] - g.target[i]));
            if (FoundingPlan.reach(dx, dz + 1) <= g.outer - 1 && FoundingPlan.reach(dx, dz + 1) > radius + 3) worst = Math.max(worst, Math.abs(g.target[i + g.side] - g.target[i]));
        }
        String where = "";
        for (int i = 0; i < g.side * g.side && where.isEmpty(); i++) {
            int dx = g.dx(i), dz = g.dz(i);
            double r = FoundingPlan.reach(dx, dz);
            if (r <= radius + 3 || r > g.outer - 1 || FoundingPlan.reach(dx + 1, dz) > g.outer - 1 || FoundingPlan.reach(dx + 1, dz) <= radius + 3) continue;
            if (Math.abs(g.target[i + 1] - g.target[i]) > 2) where = dx + "," + dz + " r " + r + " targets " + g.target[i] + "/" + g.target[i + 1] + " ground " + g.ground[i] + "/" + g.ground[i + 1];
            if (FoundingPlan.reach(dx, dz + 1) <= g.outer - 1 && FoundingPlan.reach(dx, dz + 1) > radius + 3 && Math.abs(g.target[i + g.side] - g.target[i]) > 2)
                where = dx + "," + dz + " (z) r " + r + " targets " + g.target[i] + "/" + g.target[i + g.side] + " ground " + g.ground[i] + "/" + g.ground[i + g.side];
        }
        assertTrue(worst <= 2, "no cliff in the edge: " + worst + " at " + where + " (radius " + radius + ")");
    }

    @Test
    @DisplayName("walls only where a terrace stands two or more over the one beside it")
    void wallsWhereTheyHoldSomething() {
        FoundingPlan.Ground g = ground((dx, dz) -> 70 + (int) Math.round(dx * 0.7));
        plan(g, 3L);
        int walls = 0, wrong = 0;
        for (int i = 0; i < g.side * g.side; i++) {
            int foot = Terraces.wallFoot(g, i);
            if (foot == Terraces.NONE) continue;
            walls++;
            if (g.target[i] - foot < 1) wrong++;
        }
        assertTrue(walls > 50, "walls stand between the terraces: " + walls);
        assertEquals(0, wrong);
        assertFalse(Terraces.wanted(ground((dx, dz) -> 64), FoundingPlan.coreRadius(FOLK)), "flat ground wants no terraces");
    }
}
