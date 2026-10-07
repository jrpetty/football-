package com.jrpetty.mcassistant.village;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The town's quarters, laid over its plan (TownPlan): which part of the town each lot belongs to,
 * and so which buildings go where.
 * <ul>
 * <li><b>The square</b> at the heart: the well, the monuments, the wall round it.</li>
 * <li><b>The market quarter</b> round the square: the lots facing the ring street, the great
 *     lots and the corners, where the market, the shops, the café, the tavern and the stores
 *     stand. It grows out toward the farmland, on the lots between the square and the first
 *     field: the farm gate is where a market wants to be.</li>
 * <li><b>The craft quarter</b> on one side, away from the homes: the smeltery, the smithy, the
 *     workshops, the brewery, and toward the mines. Smoke and hammering are its business.</li>
 * <li><b>The farmland</b> on another side, past the town's first block, as before.</li>
 * <li><b>The homes</b> on the two sides left: the houses, and the park among them.</li>
 * <li>And <b>the outskirts</b> past the plan's last street.</li>
 * </ul>
 * Each quarter grows outward on its own side as the town does. A building goes on a lot of its own
 * quarter while there is one to be had; only when every one is spoken for, or no use, does it go on
 * the next best, and never on nothing: the order the plan's lots are offered in changes, never which
 * lots are offered. Nothing already standing is moved.
 *
 * <p>Like the plan, this is only arithmetic: offsets from the heart and the sides the village chose
 * for its fields and its crafts (entity/Quarters keeps those).
 */
public final class Districts {

    private Districts() {}

    public enum District {
        SQUARE("the square", "Square"),
        MARKET("the market quarter", "Market"),
        CRAFTS("the craft quarter", "Crafts"),
        HOMES("the homes quarter", "Homes"),
        FIELDS("the farmland", "Fields"),
        OUTSKIRTS("the outskirts", "Outskirts");

        /** As a folk would say it: "the craft quarter". */
        public final String words;
        /** A word for a list or a map's key. */
        public final String label;

        District(String words, String label) {
            this.words = words;
            this.label = label;
        }

        public static District byName(String s) {
            try {
                return valueOf(s.toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                return OUTSKIRTS;
            }
        }
    }

    /** Round the square: the lots facing the ring street and the great lots beside it (their
     *  middles are no further out than this). */
    public static final int ROUND_THE_SQUARE = TownPlan.RING + TownPlan.STREET + TownPlan.LOT - 1;

    /** Where the farmland's fields begin, out from the heart (as entity/Villages.FIRST_BLOCK). */
    public static final int FIELDS_FROM = TownPlan.RING + TownPlan.PERIOD + 2;

    /** How far out toward the given side this offset is (TownPlan.NORTH..WEST). */
    public static int along(int side, int dx, int dz) {
        return switch (side) {
            case TownPlan.EAST -> dx;
            case TownPlan.WEST -> -dx;
            case TownPlan.SOUTH -> dz;
            default -> -dz;
        };
    }

    /** The side an offset lies on: the one it is furthest out toward. On a diagonal it lies on
     *  two; {@code second} (if not null) is given the other, or -1. */
    public static int sideOf(int dx, int dz, @Nullable int[] second) {
        int best = TownPlan.NORTH, most = Integer.MIN_VALUE, tie = -1;
        for (int s = 0; s < 4; s++) {
            int a = along(s, dx, dz);
            if (a > most) { most = a; best = s; tie = -1; }
            else if (a == most) tie = s;
        }
        if (second != null && second.length > 0) second[0] = tie;
        return best;
    }

    /** The name of a side, as a compass word. */
    public static String sideWord(int side) {
        return switch (side) {
            case TownPlan.NORTH -> "north";
            case TownPlan.EAST -> "east";
            case TownPlan.SOUTH -> "south";
            case TownPlan.WEST -> "west";
            default -> "nowhere yet";
        };
    }

    /**
     * Which quarter this offset from the heart is in, given the sides of the village's farmland and
     * its crafts (-1 for one not chosen yet). On a diagonal between the crafts and the homes, the
     * crafts': the homes are kept the further from the smoke.
     */
    public static District of(int dx, int dz, int farmSide, int craftSide) {
        if (TownPlan.isSquare(dx, dz)) return District.SQUARE;
        int d = Math.max(Math.abs(dx), Math.abs(dz));
        if (d <= ROUND_THE_SQUARE) return District.MARKET;
        if (d > TownPlan.reach() + TownPlan.STREET) return District.OUTSKIRTS;
        int[] other = { -1 };
        int side = sideOf(dx, dz, other);
        boolean crafts = craftSide >= 0 && (side == craftSide || other[0] == craftSide);
        boolean farm = farmSide >= 0 && (side == farmSide || other[0] == farmSide);
        if (farm && d >= FIELDS_FROM) return District.FIELDS;
        if (crafts) return District.CRAFTS;
        if (farm) return District.MARKET;               // the lots between the square and the first field
        return District.HOMES;
    }

    /** Which quarter a lot of the plan is in. */
    public static District of(TownPlan.Lot lot, int farmSide, int craftSide) {
        if (lot.kind() == TownPlan.Kind.SQUARE) return District.SQUARE;
        return of(lot.x(), lot.z(), farmSide, craftSide);
    }

    /** The trades whose buildings smoke, clang or reek: the craft quarter's. */
    private static final java.util.Set<String> INDUSTRY = java.util.Set.of("smeltery", "smithy", "workshop", "brewery",
        "forge", "foundry", "kiln", "sawmill", "mill", "tannery", "quarry", "mine", "minehead", "dyeworks", "ropewalk");

    /**
     * Is this a building of the craft quarter? By name: the smeltery, the smithy, the workshop, the
     * brewery, and anything whose name says smoke or noise (a forge, a kiln, a foundry, a mill, a
     * tannery, the works of anything), so a new trade's building falls into the right quarter.
     */
    public static boolean isIndustry(String structure) {
        String s = structure.toLowerCase(Locale.ROOT);
        if (s.equals("windmill")) return false;            // [batchE] a mill by the fields: sails, no smoke and no din
        if (INDUSTRY.contains(s)) return true;
        return s.contains("smith") || s.contains("forge") || s.contains("smelt") || s.contains("kiln")
            || s.contains("foundry") || s.endsWith("works") || s.endsWith("mill") || s.contains("tanner");
    }

    /**
     * The quarter a kind of building belongs in, or null where the plan's own place for it is kept
     * as it is: the great buildings by the square, the well and the monuments on it, the towers at
     * its corners, the lighthouse, the pen and the gateway out at the edge. The crafts' buildings
     * to the craft quarter; whatever faces the square (the market, the shops, the café, the tavern,
     * the stores, and any new trade the plan puts there) to the market quarter; homes, and anything
     * else the plan puts among them (the park), to the homes.
     */
    @Nullable
    public static District forBuilding(String structure) {
        if (isIndustry(structure)) return District.CRAFTS;
        return switch (TownPlan.placeFor(structure)) {
            case "civic" -> District.MARKET;
            case "home", "field" -> District.HOMES;                         // [batchC] the pitch among the homes
            default -> null;
        };
    }

    /**
     * How badly a building of one quarter fits in another: 0 in its own, more the worse. The market's
     * trades would sooner spill into the homes' streets than the crafts'; the crafts would sooner the
     * market's than anybody's homes; a home would sooner be by the market than in the smoke.
     */
    public static int misfit(@Nullable District want, District at) {
        if (want == null || want == at) return 0;
        return switch (want) {
            case MARKET -> switch (at) { case HOMES -> 1; case SQUARE -> 2; case CRAFTS -> 3; default -> 4; };
            case CRAFTS -> switch (at) { case MARKET -> 2; case OUTSKIRTS -> 2; case HOMES -> 3; default -> 4; };
            case HOMES -> switch (at) { case MARKET -> 1; case OUTSKIRTS -> 2; case CRAFTS -> 3; default -> 4; };
            default -> 1;
        };
    }

    /**
     * A building's candidate lots (TownPlan.candidates), its own quarter's first, then the next best,
     * each in the plan's own order (nearest the heart first, its own kind of lot first). Nothing is
     * left out: a town with no lot left in a quarter still builds.
     */
    public static List<TownPlan.Lot> order(List<TownPlan.Lot> candidates, String structure, int farmSide, int craftSide) {
        District want = forBuilding(structure);
        if (want == null) return candidates;
        List<TownPlan.Lot> out = new ArrayList<>(candidates);
        out.sort(Comparator.comparingInt(l -> rank(l, want, farmSide, craftSide)));   // a stable sort
        return out;
    }

    /** What the plan keeps a lot for, if not this quarter's building: the towers' corners, the great
     *  buildings' halves, and the market's lots facing the square (kept from the crafts and the homes). */
    public static boolean keptFor(TownPlan.Lot lot, @Nullable District want) {
        String use = lot.use();
        return use.equals("corner") || use.equals("great-half") || (use.equals("civic") && want != District.MARKET);
    }

    /** How a lot ranks for a building of this quarter: its misfit (above), and last of all a lot the
     *  plan keeps for something else. */
    public static int rank(TownPlan.Lot lot, @Nullable District want, int farmSide, int craftSide) {
        if (want == null) return 0;
        if (keptFor(lot, want)) return 10;
        return misfit(want, of(lot, farmSide, craftSide));
    }

    /**
     * The side the crafts go on, chosen once: of the sides that are not the farmland's, the one
     * {@code toward} (an offset from the heart: the mines, or a smeltery already standing) lies on,
     * if there is one; else the side with the fewest homes on it already ({@code homes}, by side),
     * the first such going round clockwise from the farmland's (from the north if the village has no
     * farmland yet), so that the homes have the side across from the smoke.
     */
    public static int chooseCraftSide(int farmSide, @Nullable int[] toward, @Nullable int[] homes) {
        if (homes != null && (toward == null || (toward[0] == 0 && toward[1] == 0))) {
            int best = -1, fewest = Integer.MAX_VALUE;
            for (int i = 1; i <= 4; i++) {
                int s = farmSide < 0 ? (i - 1) : (farmSide + i) % 4;
                if (s == farmSide) continue;
                int n = s < homes.length ? homes[s] : 0;
                if (n < fewest) { fewest = n; best = s; }
            }
            if (best >= 0) return best;
        }
        return chooseCraftSide(farmSide, toward);
    }

    /** As above, with no homes to go round. */
    public static int chooseCraftSide(int farmSide, @Nullable int[] toward) {
        if (toward != null && (toward[0] != 0 || toward[1] != 0)) {
            int[] other = { -1 };
            int s = sideOf(toward[0], toward[1], other);
            if (s != farmSide) return s;
            if (other[0] >= 0 && other[0] != farmSide) return other[0];
            // Straight out over the fields: the nearer of the two sides beside them.
            int a = (farmSide + 1) % 4, b = (farmSide + 3) % 4;
            return along(a, toward[0], toward[1]) >= along(b, toward[0], toward[1]) ? a : b;
        }
        return farmSide < 0 ? TownPlan.NORTH : (farmSide + 1) % 4;
    }
}
