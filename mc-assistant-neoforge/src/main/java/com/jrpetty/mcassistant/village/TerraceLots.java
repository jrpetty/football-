package com.jrpetty.mcassistant.village;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * [house34] Where the town's terraces go: the long street fronts of its plan.
 *
 * <p>A terrace is a row of houses sharing their side walls along a street (entity/TerraceRows), twenty-one or twenty-two
 * across and eleven deep. The plan has two kinds of ground that long:
 * <ul>
 * <li><b>the long lots by the avenues</b> (two lots end to end, out past the first ring of blocks): a block of flats
 *     stands on one end-on, its door to the cross street; a terrace is turned to face the avenue along its length, its
 *     front doors onto the avenue and its yards onto the street behind;</li>
 * <li><b>two lots side by side facing the same street</b>: every block of homes has a pair on its outer street (and a
 *     block turned to its cross street, a pair on that), so a terrace can line an ordinary street of homes too, and the
 *     next block's pair across the street face it.</li>
 * </ul>
 * A row's place is its anchor (the middle of its drawing, so a drawing twenty-two across sits one block off the middle of
 * its twenty-two blocks of ground, toward its drawing's left), with its back away from the street, as every lot's.
 * Only arithmetic, like the rest of the plan.
 */
public final class TerraceLots {

    private TerraceLots() {}

    /** A row's depth: eleven, a lot's. */
    public static final int DEEP = TownPlan.LOT;
    /** The ground a row has along its street: two lots, twenty-two blocks. */
    public static final int RUN = 2 * TownPlan.LOT;

    private static List<TownPlan.Lot> NARROW, WIDE;

    /** The places for a row this many blocks across (21 or 22), nearest the heart first. */
    public static synchronized List<TownPlan.Lot> lots(int across) {
        boolean wide = across > RUN - 1;
        List<TownPlan.Lot> have = wide ? WIDE : NARROW;
        if (have != null) return have;
        List<TownPlan.Lot> out = new ArrayList<>();
        List<TownPlan.Lot> all = TownPlan.lots();
        // The long lots out along the avenues, turned to face them.
        for (TownPlan.Lot l : all) {
            if (l.kind() != TownPlan.Kind.LONG || !l.use().equals("long")) continue;
            boolean alongX = l.back() == TownPlan.EAST || l.back() == TownPlan.WEST;   // the lot runs out along x, beside an east-west avenue
            int side = alongX ? l.z() : l.x();
            int lo = Math.min(cellCentre(l, 0, alongX), cellCentre(l, 1, alongX)) - TownPlan.LOT / 2;
            int back = alongX ? (side > 0 ? TownPlan.SOUTH : TownPlan.NORTH) : (side > 0 ? TownPlan.EAST : TownPlan.WEST);
            out.add(place(alongX, lo, side, back, across, l.cells(), "row-long"));
        }
        // Two home lots side by side, both facing the same street, in the same block (no street between them).
        for (TownPlan.Lot a : all) {
            if (a.kind() != TownPlan.Kind.LOT || !a.use().equals("home")) continue;
            for (TownPlan.Lot b : all) {
                if (b == a || b.kind() != TownPlan.Kind.LOT || !b.use().equals("home") || b.back() != a.back()) continue;
                boolean doorNS = a.back() == TownPlan.NORTH || a.back() == TownPlan.SOUTH;   // the street runs east-west
                // side by side along the street: the same distance from it, a lot apart along it, the first the lower
                if (doorNS ? a.z() != b.z() || b.x() - a.x() != TownPlan.LOT : a.x() != b.x() || b.z() - a.z() != TownPlan.LOT) continue;
                int lo = (doorNS ? a.x() : a.z()) - TownPlan.LOT / 2;
                int depth = doorNS ? a.z() : a.x();
                out.add(place(doorNS, lo, depth, a.back(), across, new long[]{ a.cells()[0], b.cells()[0] }, "row-pair"));
            }
        }
        out.sort(Comparator.comparingInt(TownPlan.Lot::distance).thenComparingInt(l -> Math.abs(l.x()) + Math.abs(l.z()))
            .thenComparingInt(TownPlan.Lot::x).thenComparingInt(TownPlan.Lot::z));
        List<TownPlan.Lot> done = List.copyOf(out);
        if (wide) WIDE = done; else NARROW = done;
        return done;
    }

    private static List<TownPlan.Lot> CORNERS, BLOCKS;

    /** [house34c] The places for a row by its register name: a corner terrace's, a crescent's, or a straight row's. */
    public static List<TownPlan.Lot> places(String structure) {
        return switch (structure) {
            case "terrace4" -> corners();
            case "terrace5" -> blocks();
            case "terrace" -> lots(21);
            default -> lots(22);
        };
    }

    /**
     * [house34c] The places for a corner terrace: where two streets meet, a pair of home lots side by side facing one
     * street (a whole side of their block) and the lot behind the pair's right-hand one (beside the side street, its back
     * to the pair's), three lots of the four; nearest the heart first. Its anchor is the pair's, as a straight row's, its
     * side street houses running back along the cross street on the third lot.
     */
    public static synchronized List<TownPlan.Lot> corners() {
        if (CORNERS == null) CORNERS = deep("row-corner", false);
        return CORNERS;
    }

    /** [house34c] The places for a crescent: a whole block of home lots, its front on the street a pair faces. */
    public static synchronized List<TownPlan.Lot> blocks() {
        if (BLOCKS == null) BLOCKS = deep("row-crescent", true);
        return BLOCKS;
    }

    private static List<TownPlan.Lot> deep(String use, boolean whole) {
        List<TownPlan.Lot> out = new ArrayList<>();
        List<TownPlan.Lot> all = TownPlan.lots();
        for (TownPlan.Lot p : lots(22)) {
            if (!p.use().equals("row-pair")) continue;
            TownPlan.Lot a = lotAt(all, p.cells()[0]), b = lotAt(all, p.cells()[1]);
            if (a == null || b == null) continue;
            // the lot behind a lot: a lot's width on toward its back (its back north: toward -z)
            int bx = a.back() == TownPlan.EAST ? TownPlan.LOT : a.back() == TownPlan.WEST ? -TownPlan.LOT : 0;
            int bz = a.back() == TownPlan.SOUTH ? TownPlan.LOT : a.back() == TownPlan.NORTH ? -TownPlan.LOT : 0;
            // the pair's right-hand lot: the higher one along the street when its right runs that way
            TownPlan.Lot right = rightSign(a.back()) > 0 ? b : a, left = right == a ? b : a;
            TownPlan.Lot behindRight = homeAt(all, right.x() + bx, right.z() + bz), behindLeft = homeAt(all, left.x() + bx, left.z() + bz);
            if (behindRight == null || whole && behindLeft == null) continue;
            long[] cells = whole ? new long[]{ a.cells()[0], b.cells()[0], behindRight.cells()[0], behindLeft.cells()[0] }
                : new long[]{ a.cells()[0], b.cells()[0], behindRight.cells()[0] };
            out.add(new TownPlan.Lot(p.x(), p.z(), p.back(), TownPlan.Kind.LOT, p.halfAcross(), TownPlan.LOT + DEEP / 2, cells, use));
        }
        return List.copyOf(out);
    }

    private static TownPlan.Lot lotAt(List<TownPlan.Lot> all, long cell) {
        for (TownPlan.Lot l : all) if (l.kind() == TownPlan.Kind.LOT && l.cells().length > 0 && l.cells()[0] == cell) return l;
        return null;
    }

    private static TownPlan.Lot homeAt(List<TownPlan.Lot> all, int x, int z) {
        for (TownPlan.Lot l : all) if (l.kind() == TownPlan.Kind.LOT && l.use().equals("home") && l.x() == x && l.z() == z) return l;
        return null;
    }

    private static int cellCentre(TownPlan.Lot l, int i, boolean alongX) {
        long c = l.cells()[i];
        int x = (int) (c >> 32), z = (int) c;
        return alongX ? x : z;
    }

    /**
     * A row's place on a run of ground: along x (a street running east-west) or along z, from {@code lo} for twenty-two
     * blocks, its depth's middle at {@code depth}, its back toward {@code back}. Its anchor is where the drawing's middle
     * column falls: a drawing's columns run dx -10 to +10 (or +11) to its right, and its right is the back turned clockwise.
     */
    static TownPlan.Lot place(boolean alongX, int lo, int depth, int back, int across, long[] cells, String use) {
        int right = rightSign(back);
        int a = right > 0 ? lo + 10 : lo + (across - 11);
        int x = alongX ? a : depth, z = alongX ? depth : a;
        return new TownPlan.Lot(x, z, back, TownPlan.Kind.LOT, (across - 1) - 10, DEEP / 2, cells, use);
    }

    /** Which way a drawing's dx runs along its street, as a sign on the street's axis: its right is its back turned
     *  clockwise (north to east, east to south, south to west, west to north). */
    public static int rightSign(int back) {
        return switch (back) {
            case TownPlan.NORTH -> 1;      // right is east: +x
            case TownPlan.EAST -> 1;       // right is south: +z
            case TownPlan.SOUTH -> -1;     // right is west: -x
            default -> -1;                 // right is north: -z
        };
    }

    /** Is this one of a row's places (not an ordinary lot of the plan)? */
    public static boolean isRow(TownPlan.Lot l) {
        return l.use().startsWith("row-");
    }
}
