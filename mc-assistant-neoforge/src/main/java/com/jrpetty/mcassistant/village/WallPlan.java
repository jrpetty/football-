package com.jrpetty.mcassistant.village;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [defence] The town wall's plan: the curtain wall round the whole town, laid over the town plan (TownPlan).
 *
 * <p>The wall is a rectangle round the town proper, each side on its own line out from the heart: past the last
 * ring of lots the town has taken on that side, with that ring's outer street left clear inside it (the street at
 * 39 to 41 out, so the first wall's inner face is 42 out). It is three thick; a tower seven square stands at each
 * corner, a gatehouse on each avenue (a gate as wide as the avenue between two flanking towers), and more towers along each
 * side, none more than about thirty blocks from the next and none on a cross street. The towers stand out from the
 * wall's outer face, never into the street inside it, so the ring the wall keeps for itself is seven deep from the
 * inner face: the inward lots of the next ring out (TownPlan) lie on it and are never offered while it stands there.
 *
 * <p>As the town grows past a side (a lot is taken beyond the wall), that side's line moves out a ring; the new
 * section goes up and the old one comes down and gives its line back to the lots.
 *
 * <p>Like the town plan, this is only arithmetic: offsets from the heart. entity/CastleWall fits it to the ground,
 * builds it out of the stores and keeps it.
 */
public final class WallPlan {

    private WallPlan() {}

    /** The wall's thickness: inner face, walkway, outer face with its battlements. */
    public static final int THICK = 3;
    /** A tower's width, and how deep it stands from the wall's inner face (four out past the outer face). */
    public static final int TOWER = 7;
    /** How deep the ring the wall keeps for itself is, from its inner face. */
    public static final int BAND = TOWER;
    /** Half the gate's opening: as wide as the avenue it closes (five). */
    public static final int GATE = TownPlan.AVENUE;
    /** Where a gate's flanking towers begin, either side of it. */
    public static final int FLANK = GATE + 1;
    /** The most from one tower to the next along a side. */
    public static final int SPACING = 30;

    // ------------------------------------------------------------------ the lines

    /** The inner face of the wall that closes the town at this ring of the plan: 42 out for the first block. */
    public static int line(int ring) {
        return TownPlan.RING + (ring + 1) * TownPlan.PERIOD + TownPlan.STREET;
    }

    /** The furthest ring the wall goes out to: one past the plan's last ring of lots, for the houses that spill over it. */
    public static int maxRing() {
        return TownPlan.RINGS;
    }

    /** The ring whose wall keeps something reaching this far out inside, with the street before it clear. */
    public static int ringFor(int extent) {
        for (int k = 0; k < maxRing(); k++) if (extent <= line(k) - TownPlan.STREET - 1) return k;
        return maxRing();
    }

    /** Which ring a wall line is (the nearest, for a line that is no ring's). */
    public static int ringOf(int line) {
        for (int k = 0; k <= maxRing(); k++) if (line <= line(k)) return k;
        return maxRing();
    }

    /** The four lines of a wall: how far out its inner face stands to the north, east, south and west. */
    public record Sides(int n, int e, int s, int w) {
        public int of(int side) {
            return switch (side) {
                case TownPlan.EAST -> e;
                case TownPlan.SOUTH -> s;
                case TownPlan.WEST -> w;
                default -> n;
            };
        }

        public Sides with(int side, int line) {
            return switch (side) {
                case TownPlan.EAST -> new Sides(n, line, s, w);
                case TownPlan.SOUTH -> new Sides(n, e, line, w);
                case TownPlan.WEST -> new Sides(n, e, s, line);
                default -> new Sides(line, e, s, w);
            };
        }

        /** Each side the further out of the two: a wall never comes in. */
        public Sides max(Sides o) {
            return new Sides(Math.max(n, o.n), Math.max(e, o.e), Math.max(s, o.s), Math.max(w, o.w));
        }

        public String encode() {
            return n + "," + e + "," + s + "," + w;
        }

        @Nullable
        public static Sides decode(@Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split(",");
            if (p.length != 4) return null;
            try {
                return new Sides(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                    Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** The first wall, round the first block of lots on every side. */
    public static Sides first() {
        int l = line(0);
        return new Sides(l, l, l, l);
    }

    /**
     * The wall round these boxes (each {x, z, halfX, halfZ}, offsets from the heart: the lots the town has built on
     * or taken for building): on each side the line of the ring that keeps the furthest of them inside, the square
     * and the first block always.
     */
    public static Sides around(List<int[]> boxes) {
        int n = TownPlan.PLAZA, e = TownPlan.PLAZA, s = TownPlan.PLAZA, w = TownPlan.PLAZA;
        for (int[] b : boxes) {
            n = Math.max(n, -(b[1] - b[3]));
            e = Math.max(e, b[0] + b[2]);
            s = Math.max(s, b[1] + b[3]);
            w = Math.max(w, -(b[0] - b[2]));
        }
        return new Sides(line(ringFor(n)), line(ringFor(e)), line(ringFor(s)), line(ringFor(w)));
    }

    // ------------------------------------------------------------------ sides and offsets

    /** How far out toward a side (TownPlan.NORTH..WEST) an offset is. */
    public static int out(int side, int dx, int dz) {
        return Districts.along(side, dx, dz);
    }

    /** How far along a side an offset is: east is + along the north and south sides, south along the east and west. */
    public static int across(int side, int dx, int dz) {
        return side == TownPlan.EAST || side == TownPlan.WEST ? dz : dx;
    }

    /** The offset {dx, dz} this far out toward a side and this far along it. */
    public static int[] offset(int side, int out, int across) {
        return switch (side) {
            case TownPlan.EAST -> new int[]{ out, across };
            case TownPlan.WEST -> new int[]{ -out, across };
            case TownPlan.SOUTH -> new int[]{ across, out };
            default -> new int[]{ across, -out };
        };
    }

    /** The first column of a side's curtain along it (the corner tower before it is past this). */
    public static int lo(Sides s, int side) {
        return side == TownPlan.EAST || side == TownPlan.WEST ? -s.n() + 1 : -s.w() + 1;
    }

    /** The last column of a side's curtain along it. */
    public static int hi(Sides s, int side) {
        return side == TownPlan.EAST || side == TownPlan.WEST ? s.s() - 1 : s.e() - 1;
    }

    /** Is this along a cross street of the plan (one running out through the wall)? */
    public static boolean onCrossStreet(int across) {
        int u = Math.abs(across);
        if (u < TownPlan.RING) return false;
        return (u - TownPlan.RING) % TownPlan.PERIOD < TownPlan.STREET;
    }

    // ------------------------------------------------------------------ the towers

    public enum TowerKind { CORNER, FLANK, MID }

    /** A tower: what kind, the side it stands on and its middle along it, and the ground it covers. */
    public record Tower(TowerKind kind, int side, int centre, int x0, int z0, int x1, int z1) {
        public boolean contains(int dx, int dz) {
            return dx >= x0 && dx <= x1 && dz >= z0 && dz <= z1;
        }

        /** The middle of the tower, as an offset. */
        public int[] middle() {
            return new int[]{ (x0 + x1) / 2, (z0 + z1) / 2 };
        }
    }

    private static Tower box(TowerKind kind, int side, int line, int a0, int a1) {
        int[] p = offset(side, line, a0), q = offset(side, line + TOWER - 1, a1);
        return new Tower(kind, side, (a0 + a1) / 2, Math.min(p[0], q[0]), Math.min(p[1], q[1]), Math.max(p[0], q[0]), Math.max(p[1], q[1]));
    }

    /** Every tower of a wall: the four corners, a gate's two flanks on each side, and those between. */
    public static List<Tower> towers(Sides s) {
        List<Tower> out = new ArrayList<>();
        // The corners: their inner corner where the two sides' inner faces meet, standing out from both.
        out.add(new Tower(TowerKind.CORNER, TownPlan.NORTH, s.e() + 3, s.e(), -s.n() - TOWER + 1, s.e() + TOWER - 1, -s.n()));
        out.add(new Tower(TowerKind.CORNER, TownPlan.EAST, s.s() + 3, s.e(), s.s(), s.e() + TOWER - 1, s.s() + TOWER - 1));
        out.add(new Tower(TowerKind.CORNER, TownPlan.SOUTH, -s.w() - 3, -s.w() - TOWER + 1, s.s(), -s.w(), s.s() + TOWER - 1));
        out.add(new Tower(TowerKind.CORNER, TownPlan.WEST, -s.n() - 3, -s.w() - TOWER + 1, -s.n() - TOWER + 1, -s.w(), -s.n()));
        for (int side = 0; side < 4; side++) {
            int line = s.of(side);
            out.add(box(TowerKind.FLANK, side, line, FLANK, FLANK + TOWER - 1));
            out.add(box(TowerKind.FLANK, side, line, -FLANK - TOWER + 1, -FLANK));
            for (int sign : new int[]{ 1, -1 }) {
                int from = FLANK + TOWER / 2;                                     // the flank tower's middle
                int to = (sign > 0 ? hi(s, side) : -lo(s, side)) + 1 + TOWER / 2;   // the corner tower's
                int last = from;
                // Along to the corner, a tower never more than SPACING past the last, each where it stands on no
                // cross street, spread as evenly as the streets let them be.
                while (to - last > SPACING + 2) {
                    int left = to - last;
                    int ideal = last + (left + (left + SPACING - 1) / SPACING - 1) / ((left + SPACING - 1) / SPACING);
                    int c = clearOfStreets(Math.min(ideal, last + SPACING), last + TOWER + 2, Math.min(last + SPACING, to - TOWER - 2));
                    if (c < 0) break;
                    last = c;
                    int a0 = c - TOWER / 2, a1 = c + TOWER / 2;
                    out.add(sign > 0 ? box(TowerKind.MID, side, line, a0, a1) : box(TowerKind.MID, side, line, -a1, -a0));
                }
            }
        }
        return out;
    }

    /** The nearest middle to c (within least..most) for a tower that stands on no cross street, or -1. */
    private static int clearOfStreets(int c, int least, int most) {
        for (int d = 0; d <= TownPlan.PERIOD; d++) {
            for (int cand : new int[]{ c - d, c + d }) {
                if (cand < least || cand > most) continue;
                boolean clear = true;
                for (int a = cand - TOWER / 2 - 1; a <= cand + TOWER / 2 + 1; a++) if (onCrossStreet(a)) clear = false;
                if (clear) return cand;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ the cells

    public enum Kind { CURTAIN, GATE, TOWER }

    /**
     * A column of the wall: the curtain (layer 0 the inner face, 1 the walkway's middle, 2 the outer face with the
     * battlements), the gate's passage through it (the same layers), or a tower (layer: how far out from the inner
     * face, 0 to 6). {@code across}: along the side.
     */
    public record Cell(Kind kind, int side, int layer, int across, @Nullable Tower tower) {}

    /** The wall at this offset, or null if none of it stands there. */
    @Nullable
    public static Cell cell(Sides s, List<Tower> towers, int dx, int dz) {
        for (Tower t : towers) {
            if (!t.contains(dx, dz)) continue;
            return new Cell(Kind.TOWER, t.side(), out(t.side(), dx, dz) - s.of(t.side()), across(t.side(), dx, dz), t);
        }
        for (int side = 0; side < 4; side++) {
            int u = out(side, dx, dz) - s.of(side);
            if (u < 0 || u >= THICK) continue;
            int a = across(side, dx, dz);
            if (a < lo(s, side) || a > hi(s, side)) continue;
            return new Cell(Math.abs(a) <= GATE ? Kind.GATE : Kind.CURTAIN, side, u, a, null);
        }
        return null;
    }

    /** Every column of the wall, as offsets {dx, dz}. */
    public static List<int[]> columns(Sides s, List<Tower> towers) {
        List<int[]> out = new ArrayList<>();
        for (int dx = -s.w() - TOWER + 1; dx <= s.e() + TOWER - 1; dx++) {
            for (int dz = -s.n() - TOWER + 1; dz <= s.s() + TOWER - 1; dz++) {
                if (dx > -s.w() && dx < s.e() && dz > -s.n() && dz < s.s()) {
                    dz = s.s() - 1;                                                // the inside: skip to its far side
                    continue;
                }
                if (cell(s, towers, dx, dz) != null) out.add(new int[]{ dx, dz });
            }
        }
        return out;
    }

    /** Is this offset inside the wall (in the town, short of the wall's inner face)? */
    public static boolean inside(Sides s, int dx, int dz) {
        return dx > -s.w() && dx < s.e() && dz > -s.n() && dz < s.s();
    }

    /** Is this whole box ({x, z, halfX, halfZ}) inside the wall? */
    public static boolean encloses(Sides s, int x, int z, int hx, int hz) {
        return inside(s, x - hx, z - hz) && inside(s, x + hx, z + hz);
    }

    /** Does this box lie on the ring the wall keeps for itself (its own ground and its towers')? */
    public static boolean onRing(Sides s, int x, int z, int hx, int hz) {
        boolean meetsOuter = x + hx >= -s.w() - BAND + 1 && x - hx <= s.e() + BAND - 1
            && z + hz >= -s.n() - BAND + 1 && z - hz <= s.s() + BAND - 1;
        return meetsOuter && !encloses(s, x, z, hx, hz);
    }

    // ------------------------------------------------------------------ the steps up

    /**
     * The flights of steps up to the walkway from inside, cut into the wall's inner face: one on each side of each
     * gate, the first run of curtain along from it where a flight of this many steps fits clear of the towers and
     * the cross streets. Each is {side, the across of its foot, the way it climbs along the side (+1 or -1)}.
     */
    public static List<int[]> stairs(Sides s, List<Tower> towers, int steps) {
        List<int[]> out = new ArrayList<>();
        for (int side = 0; side < 4; side++) {
            for (int sign : new int[]{ 1, -1 }) {
                int far = sign > 0 ? hi(s, side) : -lo(s, side);
                for (int a = FLANK + TOWER + 1; a + steps - 1 <= far - 1; a++) {
                    boolean clear = true;
                    for (int k = -1; k <= steps && clear; k++) {
                        int at = sign * (a + k);
                        if (onCrossStreet(at)) clear = false;
                        int[] p = offset(side, s.of(side), at);
                        for (Tower t : towers) if (t.contains(p[0], p[1])) clear = false;
                    }
                    if (!clear) continue;
                    // The foot at the far end, climbing toward the gate: its top step by the flank tower's side.
                    out.add(new int[]{ side, sign * (a + steps - 1), -sign });
                    break;
                }
            }
        }
        return out;
    }

    /** The step of a flight at this across (0 the foot), or -1 if the flight does not reach it. */
    public static int stepAt(int[] flight, int steps, int across) {
        int k = (across - flight[1]) * flight[2];
        return k >= 0 && k < steps ? k : -1;
    }

    /** How high the wall stands (to its walkway) in each age, Wood to Nether: five, then six, then seven. */
    public static int height(int ageOrdinal) {
        return ageOrdinal <= 1 ? 5 : ageOrdinal == 2 ? 6 : 7;
    }
}
