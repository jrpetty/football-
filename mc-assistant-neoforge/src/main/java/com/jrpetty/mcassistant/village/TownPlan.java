package com.jrpetty.mcassistant.village;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The plan every village is laid out to: a town, not a scatter.
 *
 * <p>At the heart is the square, 27 blocks across, with the founders' camp and the
 * stores in the middle of it, the well to the south and (in time) the monument to
 * the north, and the wall round its edge with a gate onto each avenue. A ring
 * street runs round the outside of the wall. Four avenues, five wide, run out
 * from the gates to the north, south, east and west, and a grid of streets three
 * wide crosses the land every twenty-five blocks. Between the streets are the
 * building lots, eleven blocks square, four to a block, every one of them on a
 * street, and every building's door opens onto one:
 * <ul>
 * <li>on the square's north and south sides, across the ring street, the lots face
 *     it: the storehouse, the market and the workshops go there;</li>
 * <li>on its east and west sides the lots run long, two deep, for the great
 *     buildings: the meeting hall, the chapel, the barracks (further out, along
 *     every avenue, more long lots for the great works that come after);</li>
 * <li>the four corner lots by the square are for the watchtowers;</li>
 * <li>the rest are homes, in rows back to back, each row facing its street.</li>
 * </ul>
 *
 * <p>This is only arithmetic: offsets from the heart, and which way each door
 * faces. Villages fits the plan to the ground (a lot on a cliff or in a lake is
 * passed over), and the builders do the rest.
 *
 * <p>Over the plan lie the town's quarters (Districts): the market round the square,
 * the crafts on a side of their own, the farmland on another, the homes on the two
 * left. A building is offered its own quarter's lots first (entity/Quarters), so the
 * smeltery and the workshops that once took the lots facing the square go out to the
 * craft quarter now, and those lots are the market's.
 */
public final class TownPlan {

    private TownPlan() {}

    /** Half the square's width: the wall stands on this line. */
    public static final int PLAZA = 13;
    /** Half an avenue's width. */
    public static final int AVENUE = 2;
    /** A lot's width. */
    public static final int LOT = 11;
    /** A street's width. */
    public static final int STREET = 3;
    /** From one street to the next: two lots and a street. */
    public static final int PERIOD = 2 * LOT + STREET;
    /** The first street out, round the square. */
    public static final int RING = PLAZA + 1;
    /** How many rings of blocks the plan runs to. */
    public static final int RINGS = 3;

    /** Which way a building's back is, from its door: the door is on the opposite side. */
    public static final int NORTH = 0, EAST = 1, SOUTH = 2, WEST = 3;

    public enum Kind {
        /** A spot on the square itself: the well, the monuments. */
        SQUARE,
        /** An ordinary lot, eleven square. */
        LOT,
        /** Two lots end to end along an avenue: for the hall, the chapel, the barracks. */
        LONG
    }

    /**
     * A place for a building. {@code x, z}: its centre, from the heart. {@code back}: which
     * way the building's back is (its door faces the other way, onto the street).
     * {@code halfAcross, halfDeep}: how far it may spread across and from front to back.
     * {@code cells}: the lot squares it covers (two for a long lot), each as cellKey.
     */
    public record Lot(int x, int z, int back, Kind kind, int halfAcross, int halfDeep, long[] cells, String use) {
        public long key() { return cells.length == 0 ? cellKey(x, z) : cells[0]; }
        public int distance() { return Math.max(Math.abs(x), Math.abs(z)); }
    }

    public static long cellKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    // ------------------------------------------------------------------ the bands

    /** A band of lots along one axis: its centre, and whether its door looks inward (toward the heart). */
    private record Band(int centre, boolean inward, int ring) {}

    /** The bands of lots on one side of the heart (centre > 0), nearest first. */
    private static List<Band> bands() {
        List<Band> out = new ArrayList<>();
        out.add(new Band(AVENUE + 1 + LOT / 2, true, -1));           // beside the avenue: 3..13
        for (int k = 0; k < RINGS; k++) {
            int street = RING + k * PERIOD;                          // 14..16, 39..41, ...
            out.add(new Band(street + STREET + LOT / 2, true, k));   // faces that street, inward
            out.add(new Band(street + STREET + LOT + LOT / 2, false, k)); // back to back with it, faces outward
        }
        return out;
    }

    /** Is this offset on a street, an avenue or the ring street (not the square)? */
    public static boolean isStreet(int x, int z) {
        if (isSquare(x, z)) return false;
        int ax = Math.abs(x), az = Math.abs(z);
        if (ax <= AVENUE || az <= AVENUE) return true;
        return onStreetLine(ax) || onStreetLine(az);
    }

    private static boolean onStreetLine(int u) {
        if (u < RING) return false;
        int m = (u - RING) % PERIOD;
        return m < STREET;
    }

    /** Is this offset on the square (inside the wall)? */
    public static boolean isSquare(int x, int z) {
        return Math.abs(x) < PLAZA && Math.abs(z) < PLAZA;
    }

    /** How far out the plan reaches. */
    public static int reach() {
        return RING + RINGS * PERIOD - 1;
    }

    // ------------------------------------------------------------------ the lots

    private static List<Lot> ALL;

    /** Every lot of the plan, nearest the heart first. */
    public static synchronized List<Lot> lots() {
        if (ALL != null) return ALL;
        List<Lot> out = new ArrayList<>();
        List<Band> one = bands();
        List<int[]> axis = new ArrayList<>();                 // {centre, inward, ring}
        for (Band b : one) {
            axis.add(new int[]{ b.centre(), b.inward() ? 1 : 0, b.ring() });
            axis.add(new int[]{ -b.centre(), b.inward() ? 1 : 0, b.ring() });
        }
        for (int[] bx : axis) {
            for (int[] bz : axis) {
                int x = bx[0], z = bz[0];
                if (Math.abs(x) < PLAZA && Math.abs(z) < PLAZA) continue;       // the square
                boolean alongX = Math.abs(x) > Math.abs(z);       // (a tie: the door faces north or south)
                int[] main = alongX ? bx : bz;
                boolean inward = main[1] == 1;
                int back;
                if (alongX) back = (x > 0) == inward ? EAST : WEST;   // door toward the heart if inward
                else back = (z > 0) == inward ? SOUTH : NORTH;
                boolean byTheSquare = main[2] == 0 && Math.abs(alongX ? z : x) <= PLAZA;   // beside an avenue, first block
                boolean corner = main[2] == 0 && bx[2] == 0 && bz[2] == 0 && bx[1] == 1 && bz[1] == 1;
                // North and south of the square: the trades, facing it. East and west: the two
                // halves of the great lots, kept for them.
                String use = corner ? "corner"
                    : byTheSquare && !alongX && inward ? "civic"
                    : byTheSquare && alongX ? "great-half"
                    : "home";
                out.add(new Lot(x, z, back, Kind.LOT, LOT / 2, LOT / 2, new long[]{ cellKey(x, z) }, use));
            }
        }
        // Long lots: the two lots of a pair end to end, beside an avenue, door inward.
        for (Band in : one) {
            if (in.ring() < 0 || !in.inward()) continue;
            int near = in.centre();
            int far = near + LOT;
            int mid = near + LOT / 2;                                  // centred on the pair
            for (int side : new int[]{ AVENUE + 1 + LOT / 2, -(AVENUE + 1 + LOT / 2) }) {
                for (int sgn : new int[]{ 1, -1 }) {
                    // Along x, east and west of the square: the great lots. Along z (north and
                    // south) only further out: by the square those are the trades' lots.
                    out.add(new Lot(sgn * mid, side, sgn > 0 ? EAST : WEST, Kind.LONG, LOT / 2, LOT - 1,
                        new long[]{ cellKey(sgn * near, side), cellKey(sgn * far, side) },
                        in.ring() == 0 ? "great" : "long"));
                    if (in.ring() > 0) {
                        out.add(new Lot(side, sgn * mid, sgn > 0 ? SOUTH : NORTH, Kind.LONG, LOT / 2, LOT - 1,
                            new long[]{ cellKey(side, sgn * near), cellKey(side, sgn * far) }, "long"));
                    }
                }
            }
        }
        out.sort(Comparator.comparingInt(Lot::distance).thenComparingInt(l -> Math.abs(l.x()) + Math.abs(l.z()))
            .thenComparingInt(Lot::x).thenComparingInt(Lot::z));
        ALL = List.copyOf(out);
        return ALL;
    }

    /** Spots on the square itself: the well to the south of the camp, monuments round it. */
    public static List<Lot> squareSpots() {
        return List.of(
            new Lot(0, 8, NORTH, Kind.SQUARE, 3, 3, new long[]{ cellKey(1000, 8) }, "well"),
            new Lot(0, -8, SOUTH, Kind.SQUARE, 4, 4, new long[]{ cellKey(1000, -8) }, "monument"),
            new Lot(8, 0, WEST, Kind.SQUARE, 4, 4, new long[]{ cellKey(1008, 0) }, "monument"),
            new Lot(-8, 0, EAST, Kind.SQUARE, 4, 4, new long[]{ cellKey(992, 0) }, "monument"));
    }

    /** What sort of place a building wants. */
    public static String placeFor(String structure) {
        return switch (structure) {
            case "well" -> "well";
            case "monument", "fountain", "belltower" -> "monument";
            case "statue" -> "monument";                              // [batchF] on the square (PublicFund)
            case "postoffice" -> "civic";                             // [batchF] facing the square (Post)
            case "hall", "chapel", "barracks", "manor", "townhall", "store" -> "great";   // [econ-store] the store on a long lot
            case "court" -> "court";
            case "storage", "storehouse", "market", "workshop", "smeltery", "granary", "cafe", "shop", "smithy", "brewery", "library",
                 "tavern", "school", "bank", "museum", "stable",
                 "auction",                                                    // [fleet] the auction house (entity/Auctions)
                 "infirmary",                                                  // [batchA] the infirmary (entity/Infirmary)
                 "lodge",                                                      // [caves] the Delvers' Lodge (entity/Lodge)
                 "fletcher",                                                   // [fletcher] the fletcher's hut (entity/Fletchers)
                 "golemyard",                                                  // [golems] the golem yard (entity/Golems)
                 "maproom",                                                    // [cartographer] the map room (entity/Cartographers)
                 "theatre",                                                    // [batchD] the theatre
                 "townlibrary",                                                // [library] the town library
                 "bakery", "inn" -> "civic";                                   // [batchE] the bakery and the inn
            case "windmill", "orchard", "allotments" -> "fields";        // [batchE] by the farm gate (entity/TownLook.fieldLots)
            case "watchtower" -> "corner";
            case "range" -> "corner";                                       // [batchC] the watch's range, by the wall
            case "pitch" -> "field";                                        // [batchC] a long lot for the football pitch
            case "armoury" -> "civic";                // [war-prep] the armoury faces the square with the trades
            case "firestation" -> "civic";            // [disasters] the fire station, among the trades it guards
            case "trainingyard" -> "corner";          // [war-prep] the training yard by the watchtower, at a corner
            case "lighthouse", "pen", "gateway", "graveyard" -> "edge";
            case "powderhut" -> "edge";               // [fireworks] the powder hut, away from the houses (FireworksMaker)
            default -> "home";
        };
    }

    /**
     * The places this building could go, best first: the well and monuments on the square,
     * the great buildings on the long lots by it, the village's trades facing the square,
     * the watchtowers at its corners, homes along the streets, and the lighthouse, the pen
     * and the gateway out at the edge. Anything can go on an ordinary lot if its own kind
     * of place is all spoken for.
     */
    public static List<Lot> candidates(String structure) {
        String want = placeFor(structure);
        List<Lot> out = new ArrayList<>();
        if (want.equals("well") || want.equals("monument")) {
            for (Lot s : squareSpots()) if (s.use().equals(want)) out.add(s);
        }
        List<Lot> all = lots();
        switch (want) {
            case "great" -> {
                for (Lot l : all) if (l.use().equals("great")) out.add(l);
                for (Lot l : all) if (l.use().equals("long")) out.add(l);
            }
            case "civic" -> {
                for (Lot l : all) if (l.use().equals("civic")) out.add(l);
                for (Lot l : all) if (l.use().equals("home")) out.add(l);
            }
            case "corner" -> {
                for (Lot l : all) if (l.use().equals("corner")) out.add(l);
            }
            case "field" -> {
                // [batchC] The football pitch: the long lots out along the avenues, then the great lots by the square.
                for (Lot l : all) if (l.use().equals("long")) out.add(l);
                for (Lot l : all) if (l.use().equals("great")) out.add(l);
            }
            case "edge" -> {
                List<Lot> far = new ArrayList<>();
                for (Lot l : all) if (l.kind() == Kind.LOT && l.use().equals("home")) far.add(l);
                far.sort(Comparator.comparingInt(Lot::distance).reversed());
                // Not the very edge of the plan: the ring of blocks beyond the first.
                for (Lot l : far) if (l.distance() > PERIOD && l.distance() < RING + 2 * PERIOD) out.add(l);
            }
            default -> {
                // Not on a long lot's ground, while there is other ground.
                java.util.Set<Long> longs = new java.util.HashSet<>();
                for (Lot l : all) if (l.kind() == Kind.LONG) for (long c : l.cells()) longs.add(c);
                for (Lot l : all) if (l.use().equals("home") && !longs.contains(l.cells()[0])) out.add(l);
                for (Lot l : all) if (l.use().equals("home")) if (!out.contains(l)) out.add(l);
            }
        }
        // Then any ordinary lot at all, nearest first.
        for (Lot l : all) if (l.kind() == Kind.LOT && !out.contains(l)) out.add(l);
        return out;
    }
}
