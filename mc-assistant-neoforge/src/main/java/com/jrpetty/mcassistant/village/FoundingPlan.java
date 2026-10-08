package com.jrpetty.mcassistant.village;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * How much ground a founding levels, and how the levelled ground meets the land round it.
 *
 * <p>Only arithmetic, like TownPlan and VillageMath: no Minecraft in it, so the screen that
 * asks how many folk to start with can read the same numbers the server works to, and so the
 * shape of the ground can be checked on an ordinary machine. The world is read and written
 * in entity/Terraform; everything that decides WHAT the ground should be is here.
 *
 * <p>The ground is levelled in a square with rounded corners (|x|⁴ + |z|⁴ = R⁴) round the
 * heart, because the town is laid out square (TownPlan) and a round levelling would leave
 * its corner lots on the slope. How big a square is worked out from the town the founders
 * will build: the homes a village of that many wants, and how far out the plan has to run
 * to hold them.
 *
 * <p>Outside it the levelled ground meets the land as it was. Nothing is cut or filled more
 * than the slope allows: a block of height for every block out at first, rounding off from
 * the flat over the first six, and a block and a half for every block once the edge is
 * twenty-four out, so a hill a few blocks high is met over eight or ten blocks, a knoll of
 * fifteen over twenty, and a mountain cut through stands back from the town on a long steep
 * face rather than a cliff (up to fifty-nine blocks of it, forty-eight out). Where the land
 * is already within the slope it is left exactly as it was. A little noise moves the edge
 * in and out so it does not run like a drawn line. Worked through on made-up ground, no
 * step anywhere on the edge is more than two blocks unless the land itself had one.
 */
public final class FoundingPlan {

    private FoundingPlan() {}

    /** The fewest and the most folk a village can be founded with. */
    public static final int MIN_FOLK = 2, MAX_FOLK = 500;

    /** The town's reach with no homes yet: the square, the ring street and the lots facing it. */
    public static final int BARE = TownPlan.RING + TownPlan.STREET + TownPlan.LOT;

    /** The furthest the edge runs past the levelled ground. */
    public static final int BAND_MAX = 48;

    /** Where the slope rounds off from the flat, where it starts to steepen, and where it is steepest. */
    static final double FILLET = 6.0, STEADY = 16.0, STEEP = 24.0;
    /** The steepest the edge is ever cut or built: a block and a half up for every block across. */
    static final double STEEPEST = 1.5;
    /** Over how many blocks the flat gives way to the land, however gentle the land is. */
    static final double BLEND = 8.0;
    /** How far the noise moves the edge in or out, and how wide its swells are. */
    static final double WOBBLE = 2.0, SWELL = 20.0;

    // ------------------------------------------------------------------ how big

    /** Where each ring of blocks of the plan ends (as Villages.townReach counts it), and its homes. */
    private static int[] ringReach, ringHomes;

    private static synchronized void rings() {
        if (ringReach != null) return;
        int[] reach = new int[TownPlan.RINGS + 1];
        int[] homes = new int[TownPlan.RINGS + 1];
        reach[0] = BARE;
        for (int k = 1; k <= TownPlan.RINGS; k++) {
            reach[k] = TownPlan.RING + k * TownPlan.PERIOD + 2;
            int n = 0;
            for (TownPlan.Lot l : TownPlan.lots()) {
                if (l.use().equals("home") && l.distance() + l.halfAcross() <= reach[k]) n++;
            }
            homes[k] = Math.max(homes[k - 1] + 1, n);
        }
        ringHomes = homes;
        ringReach = reach;
    }

    /**
     * Half the width of the ground levelled for a village of this many: the square and the lots
     * facing it for a handful, and out as far as the plan has to run to hold the homes that many
     * want (VillageMath.housesWanted, crowded) for more. A village of two to eight levels about
     * thirty blocks round its heart; twenty-five, thirty-five; a hundred, forty-nine; two hundred
     * and fifty, sixty-seven; five hundred, eighty-nine, the whole of the plan's three rings. The
     * area, not the width, goes up with the homes, so it grows as the square root of the folk.
     */
    /**
     * The levelled square's radius for so many founders: the town plan's rings it needs for their
     * homes ({@link #planRadius}), forty percent wider, so a founding has room round its first rings
     * to grow on level ground (a party of forty levels a hundred and eleven blocks across, not
     * seventy-nine).
     */
    public static int coreRadius(int folk) {
        return (int) Math.round(planRadius(folk) * WIDER);
    }

    /** How much wider the levelled ground is than the plan's rings strictly need. */
    public static final double WIDER = 1.4;

    /** The rings of the town plan a party this size needs for its homes. */
    private static int planRadius(int folk) {
        rings();
        int homes = VillageMath.housesWanted(Math.max(MIN_FOLK, folk), true);
        for (int k = 1; k < ringReach.length; k++) {
            if (homes <= ringHomes[k] || k == ringReach.length - 1) {
                double a = ringReach[k - 1] * (double) ringReach[k - 1];
                double b = ringReach[k] * (double) ringReach[k];
                double t = Math.min(1.0, (homes - ringHomes[k - 1]) / (double) (ringHomes[k] - ringHomes[k - 1]));
                return (int) Math.round(Math.sqrt(a + (b - a) * Math.max(0.0, t)));
            }
        }
        return ringReach[ringReach.length - 1];
    }

    /**
     * How far round the heart has to be level before anybody stands up: the camp, and the ground
     * the founding party is set down on (the same sunflower spiral VillageFolkSpawnerBlock uses,
     * a block and a half apart). Sixteen for anything up to about eighty; thirty-one for five hundred.
     */
    public static int campRadius(int folk) {
        return Math.max(16, (int) Math.ceil(1.5 + 1.1 * Math.sqrt(Math.max(1, folk))) + 4);
    }

    /** Where the {@code i}th of a party stands, from the heart: {dx, dz}. The first at the heart itself. */
    public static int[] partySpot(int i) {
        if (i <= 0) return new int[]{ 0, 0 };
        double angle = i * 2.399963229728653;
        double reach = 1.5 + 1.1 * Math.sqrt(i);
        return new int[]{ (int) Math.round(Math.cos(angle) * reach), (int) Math.round(Math.sin(angle) * reach) };
    }

    /** The distance that makes the levelled square round at the corners. */
    public static double reach(int dx, int dz) {
        double x = dx * (double) dx, z = dz * (double) dz;
        return Math.sqrt(Math.sqrt(x * x + z * z));
    }

    // ------------------------------------------------------------------ the shape of the edge

    /** How far from level the ground may stand this far out past the levelled ground. */
    public static double allowance(double u) {
        if (u <= 0) return 0;
        if (u <= FILLET) return u * u / (2 * FILLET);
        double atFillet = FILLET / 2;
        if (u <= STEADY) return atFillet + (u - FILLET);
        double atSteady = atFillet + (STEADY - FILLET);
        double span = STEEP - STEADY, rise = STEEPEST - 1.0;
        if (u <= STEEP) return atSteady + (u - STEADY) + rise * (u - STEADY) * (u - STEADY) / (2 * span);
        return atSteady + span * (1.0 + rise / 2) + STEEPEST * (u - STEEP);
    }

    /** How much of the land as it was shows this far out: none at the levelled edge, all of it eight out. */
    static double blend(double u) {
        if (u <= 0) return 0;
        double t = Math.min(1.0, u / BLEND);
        return t * t * (3 - 2 * t);
    }

    /** The height wanted for ground {@code was} high, {@code u} blocks past the levelled ground. */
    public static double target(double was, int level, double u) {
        if (u <= 0) return level;
        if (u >= BAND_MAX) return was;
        double a = allowance(u);
        double kept = Math.max(level - a, Math.min(level + a, was));
        return level + (kept - level) * blend(u);
    }

    /** Smooth noise in -1..1, the same for the same seed and place. */
    public static double noise(long seed, int x, int z) {
        double fx = x / SWELL, fz = z / SWELL;
        int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
        double tx = smooth(fx - ix), tz = smooth(fz - iz);
        double a = lattice(seed, ix, iz), b = lattice(seed, ix + 1, iz);
        double c = lattice(seed, ix, iz + 1), d = lattice(seed, ix + 1, iz + 1);
        double top = a + (b - a) * tx, bottom = c + (d - c) * tx;
        return top + (bottom - top) * tz;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lattice(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return ((h >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    // ------------------------------------------------------------------ the whole plan

    /** What a column is, as read from the world. */
    public static final byte OUTSIDE = 0, LAND = 1, BUILT = 2, WATER = 3, LAVA = 4, BOARD = 5;
    /** And what it becomes in the plan: a pond to fill, water to leave be, ground round a building to leave be. */
    public static final byte FILL = 6, KEEP = 7, PROTECT = 8;

    /** Nothing to hold to (the spreads below start from here). */
    private static final int NONE_LOW = Integer.MIN_VALUE / 4, NONE_HIGH = Integer.MAX_VALUE / 4;

    /** A pond this small, this shallow, and wholly inside the ground being levelled, is filled in. */
    public static final int POND_COLUMNS = 96, POND_DEPTH = 5;

    /** How many columns round anything built are left exactly as they are. */
    public static final int MARGIN = 2;

    /**
     * The ground, read from the world, and what is to be made of it. A square grid of
     * {@code side} by {@code side} columns centred on the heart; {@code ground} is the top of
     * the earth in each (the bed, under water), {@code fluid} the top of the water or lava on
     * it (or {@link Integer#MIN_VALUE}), and {@code kind} what the column is. {@link #make}
     * fills in the rest.
     */
    public static final class Ground {
        public final int outer, side;
        public final int[] ground, fluid;
        public final byte[] kind;
        /** The height each column is to be, once planned. */
        public int[] target;
        /** The columns in the order they are worked: the heart first, outwards. */
        public int[] order;
        /** How many of {@link #order} lie within the camp: they are done before anybody stands up. */
        public int campIndex;
        /** How many columns hold water left as it is, and how many ponds are filled. */
        public int keptWater, filledPonds, protectedColumns;
        /**
         * How many columns of the levelled square itself the plan holds off the level, and by what:
         * the bank right beside water left as it is (never cut below the water, or it runs into the
         * town), or something built beside it. With the first of them, for the log.
         */
        public int heldBank, heldBuilt;
        public String heldFirst = "";
        /** Columns of water standing above the level inside the square, let out so it is cut flat. */
        public int drained;

        public Ground(int outer) {
            this.outer = outer;
            this.side = 2 * outer + 1;
            int n = side * side;
            this.ground = new int[n];
            this.fluid = new int[n];
            this.kind = new byte[n];
            Arrays.fill(fluid, Integer.MIN_VALUE);
        }

        public int index(int dx, int dz) {
            return (dz + outer) * side + (dx + outer);
        }

        public int dx(int i) {
            return i % side - outer;
        }

        public int dz(int i) {
            return i / side - outer;
        }
    }

    /** Lifted to the sea's height by no more than this: a beach town is raised out of the wet, a dry
     *  basin far under the sea's height is not built up into a mound. */
    public static final int MOST_LIFT = 8;

    /**
     * The level the ground is brought to: the middle height of the dry land inside; and beside water a
     * little under the sea's height, it is lifted to just under the top of the sea, so the town does not
     * stand in a pit behind its banks. Dry ground well under the sea's height (a deep valley, a flat
     * world whose ground is far below its sea level) stays at its own height: lifting it meant building
     * a mound a hundred blocks high.
     */
    public static int level(Ground g, int radius, int seaLevel) {
        int[] heights = new int[g.side * g.side];
        int[] tops = new int[g.side * g.side];
        int n = 0, wetIn = 0;
        boolean water = false;
        for (int i = 0; i < heights.length; i++) {
            if (g.kind[i] == WATER) {
                water = true;
                if (reach(g.dx(i), g.dz(i)) <= radius) tops[wetIn++] = g.fluid[i];
            }
            if (g.kind[i] != LAND || reach(g.dx(i), g.dz(i)) > radius) continue;
            heights[n++] = g.ground[i];
        }
        if (n == 0) return seaLevel;
        Arrays.sort(heights, 0, n);
        int middle = heights[n / 2];
        if (water && middle < seaLevel - 1 && seaLevel - 1 - middle <= MOST_LIFT) return seaLevel - 1;
        // An island, or a shore with a lake or the sea over much of the square: the town sits a block over the
        // water, its edge a step down to it, not a quay as high as the hill was. A jungle island's town was
        // levelled to its hilltop, nine over the sea, and stood on a cliff all round.
        if (wetIn > 0 && n * 5 < (n + wetIn) * 3) {
            Arrays.sort(tops, 0, wetIn);
            int low = tops[wetIn / 2] + 1;
            if (middle > low && middle - low <= MOST_ISLAND_CUT) return low;
        }
        return middle;
    }

    /** The most a town by the water is cut down to sit a block over it: a high island keeps its height. */
    public static final int MOST_ISLAND_CUT = 16;

    /**
     * Plan the ground: which ponds are filled and which water is left, what is left alone round
     * anything built, and the height every other column is to be. {@code radius} is the levelled
     * square's, {@code level} the height it is brought to, {@code camp} the camp's radius.
     */
    public static void make(Ground g, int radius, int level, int camp, long seed) {
        int side = g.side, n = side * side;
        // ---- the water: a pond inside is filled, anything bigger, deeper or running out of
        // the ground being levelled (a river, a lake, the sea) is left exactly as it is.
        int[] comp = new int[n];
        Arrays.fill(comp, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int next = 0;
        for (int start = 0; start < n; start++) {
            byte k = g.kind[start];
            if ((k != WATER && k != LAVA) || comp[start] >= 0) continue;
            int id = next++;
            comp[start] = id;
            queue.add(start);
            int size = 0, deepest = 0;
            boolean edge = false, inside = false;
            java.util.List<Integer> cells = new java.util.ArrayList<>();
            while (!queue.isEmpty()) {
                int i = queue.poll();
                cells.add(i);
                size++;
                deepest = Math.max(deepest, g.fluid[i] - g.ground[i]);
                int x = i % side, z = i / side;
                double r = reach(x - g.outer, z - g.outer);
                if (r > g.outer - 2) edge = true;
                if (r <= radius + 2) inside = true;
                int[] nb = { x > 0 ? i - 1 : -1, x < side - 1 ? i + 1 : -1, z > 0 ? i - side : -1, z < side - 1 ? i + side : -1 };
                for (int j : nb) {
                    if (j < 0 || comp[j] >= 0 || g.kind[j] != k) continue;
                    comp[j] = id;
                    queue.add(j);
                }
            }
            boolean pond = inside && !edge && size <= POND_COLUMNS && deepest <= POND_DEPTH;
            for (int i : cells) g.kind[i] = pond ? FILL : KEEP;
            if (pond) g.filledPonds++;
            else g.keptWater += size;
        }
        // ---- anything built, and the ground round it, is left as it is.
        boolean[] built = new boolean[n];
        for (int i = 0; i < n; i++) built[i] = g.kind[i] == BUILT;
        for (int i = 0; i < n; i++) {
            if (!built[i]) continue;
            int x = i % side, z = i / side;
            for (int ddz = -MARGIN; ddz <= MARGIN; ddz++) {
                for (int ddx = -MARGIN; ddx <= MARGIN; ddx++) {
                    int xx = x + ddx, zz = z + ddz;
                    if (xx < 0 || zz < 0 || xx >= side || zz >= side) continue;
                    int j = zz * side + xx;
                    byte k = g.kind[j];
                    if (k == LAND || k == BUILT || k == FILL) {
                        g.kind[j] = PROTECT;
                        g.protectedColumns++;
                    }
                }
            }
        }
        // ---- the square itself is flat, whatever water stands about it. Water above the level
        // inside the square (a stream down the hillside the town is cut into, a tarn on it) is let
        // out there, the square cut flat through it; what is left of it outside is held back by its
        // bank (below). Water at or under the level is left as it is, the square ending in a quay
        // at its edge.
        for (int i = 0; i < n; i++) {
            if (g.kind[i] != KEEP || g.fluid[i] <= level) continue;
            int dx = g.dx(i), dz = g.dz(i);
            if (reach(dx, dz) - radius + WOBBLE * noise(seed, dx, dz) > 0) continue;
            g.kind[i] = LAND;
            g.drained++;
        }
        // ---- what holds the rest: never more than a block a block from what is left alone, and
        // the banks of water that is kept never lower than the water (it would run into the town).
        // Out past the square the hold is spread, a slope; in the square only the bank itself
        // holds, the block beside the water, so it is flat to a wall there and not terraced up to it.
        int[] low = new int[n], wet = new int[n], high = new int[n], shore = new int[n];
        Arrays.fill(low, NONE_LOW);
        Arrays.fill(wet, NONE_LOW);
        Arrays.fill(high, NONE_HIGH);
        Arrays.fill(shore, NONE_HIGH);
        for (int i = 0; i < n; i++) {
            byte k = g.kind[i];
            if (k == PROTECT) {
                low[i] = Math.max(low[i], g.ground[i] * 10);
                high[i] = Math.min(high[i], g.ground[i] * 10);
            } else if (k == KEEP) {
                // The fill beside water slopes down to it, a beach rather than a wall (out past the square).
                shore[i] = (g.fluid[i] - 1) * 10;
                int x = i % side, z = i / side;
                int[] nb = { x > 0 ? i - 1 : -1, x < side - 1 ? i + 1 : -1, z > 0 ? i - side : -1, z < side - 1 ? i + side : -1 };
                for (int j : nb) {
                    if (j < 0) continue;
                    byte kj = g.kind[j];
                    if (kj == LAND || kj == FILL || kj == BOARD) wet[j] = Math.max(wet[j], g.fluid[i] * 10);
                }
            }
        }
        int[] bank = wet.clone();
        spreadLow(low, side);
        spreadLow(wet, side);
        spreadHigh(high, side);
        spreadHigh(shore, side);
        // ---- and the height of every column to be worked.
        g.target = new int[n];
        for (int i = 0; i < n; i++) {
            byte k = g.kind[i];
            int dx = g.dx(i), dz = g.dz(i);
            double r = reach(dx, dz);
            if (k == OUTSIDE || k == KEEP || k == PROTECT || r > g.outer) {
                g.target[i] = g.ground[i];
                continue;
            }
            // A pond is reckoned as land at the top of its water, so where the land round it is
            // left as it is, the pond is filled to the brim and no lower.
            double was = k == FILL ? g.fluid[i] : g.ground[i];
            double u = r - radius + WOBBLE * noise(seed, dx, dz);
            boolean square = u <= 0;
            double want = target(was, level, u);
            // What holds it only ever holds it back: a cut no deeper than it allows and a fill no
            // higher, and never a change where the plan made none. In the square, only something
            // built and the bank right beside water hold it; out past it, the slopes to them too,
            // and the beach down to water.
            int holdLow = Math.max(low[i], square ? bank[i] : wet[i]);
            int holdHigh = square ? high[i] : Math.min(high[i], shore[i]);
            double lower = Math.min(was, holdLow / 10.0);
            double upper = Math.max(was, holdHigh / 10.0);
            want = Math.max(lower, Math.min(upper, want));
            g.target[i] = (int) Math.round(want);
            if (square && g.target[i] != level) {
                String why;
                if (g.target[i] > level && bank[i] > level * 10 && bank[i] >= low[i]) { g.heldBank++; why = "the bank of water"; }
                else { g.heldBuilt++; why = "something built"; }
                if (g.heldFirst.isEmpty()) g.heldFirst = why + " at " + dx + "," + dz + " (y=" + (int) Math.round(was) + " kept at " + g.target[i] + ")";
            }
        }
        // ---- the order: nearest the heart first, so the camp is level first.
        long[] keys = new long[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            double r = reach(g.dx(i), g.dz(i));
            if (r > g.outer) continue;
            keys[m++] = ((long) (r * 1024.0) << 24) | i;
        }
        keys = Arrays.copyOf(keys, m);
        Arrays.sort(keys);
        g.order = new int[m];
        g.campIndex = m;
        for (int j = 0; j < m; j++) {
            g.order[j] = (int) (keys[j] & 0xFFFFFF);
            if (g.campIndex == m && (keys[j] >>> 24) / 1024.0 > camp) g.campIndex = j;
        }
    }

    /** Is this column inside the levelled square itself (not the edge)? With the noise, as the plan reckons it. */
    public static boolean levelled(int dx, int dz, int radius, long seed) {
        return reach(dx, dz) - radius + WOBBLE * noise(seed, dx, dz) <= 0;
    }

    /** Every cell the greatest of the seeds less a block for every block from it (in tenths, 10 across, 14 corner to corner). */
    static void spreadLow(int[] a, int side) {
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int i = z * side + x, v = a[i];
                if (x > 0) v = Math.max(v, a[i - 1] - 10);
                if (z > 0) {
                    v = Math.max(v, a[i - side] - 10);
                    if (x > 0) v = Math.max(v, a[i - side - 1] - 14);
                    if (x < side - 1) v = Math.max(v, a[i - side + 1] - 14);
                }
                a[i] = v;
            }
        }
        for (int z = side - 1; z >= 0; z--) {
            for (int x = side - 1; x >= 0; x--) {
                int i = z * side + x, v = a[i];
                if (x < side - 1) v = Math.max(v, a[i + 1] - 10);
                if (z < side - 1) {
                    v = Math.max(v, a[i + side] - 10);
                    if (x < side - 1) v = Math.max(v, a[i + side + 1] - 14);
                    if (x > 0) v = Math.max(v, a[i + side - 1] - 14);
                }
                a[i] = v;
            }
        }
    }

    /** The same the other way up: every cell the least of the seeds plus a block for every block from it. */
    static void spreadHigh(int[] a, int side) {
        for (int i = 0; i < a.length; i++) a[i] = -a[i];
        spreadLow(a, side);
        for (int i = 0; i < a.length; i++) a[i] = -a[i];
    }

    // ------------------------------------------------------------------ for the screen

    /** The trades a village of this size settles into (VillageMath.shapeOf), in words, the biggest first. */
    public static String trades(int folk) {
        return trades(folk, Integer.MAX_VALUE);
    }

    /** The same, the first {@code shown} of them and how many kinds more. */
    public static String trades(int folk, int shown) {
        int[] shape = VillageMath.shapeOf(Math.max(1, folk));
        String[][] names = {
            { "farmer", "farmers" }, { "miner", "miners" }, { "woodcutter", "woodcutters" }, { "smelter", "smelters" },
            { "of the watch", "of the watch" }, { "carrier", "carriers" }, { "storekeeper", "storekeepers" },
            { "rancher", "ranchers" }, { "fisher", "fishers" }, { "hunter", "hunters" } };
        Integer[] idx = new Integer[shape.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> shape[b] != shape[a] ? Integer.compare(shape[b], shape[a]) : Integer.compare(a, b));
        StringBuilder sb = new StringBuilder();
        int said = 0, more = 0;
        for (int i : idx) {
            if (shape[i] <= 0) continue;
            if (said >= shown) { more++; continue; }
            if (sb.length() > 0) sb.append(", ");
            sb.append(shape[i]).append(' ').append(names[i][shape[i] == 1 ? 0 : 1]);
            said++;
        }
        if (more > 0) sb.append(" and ").append(more).append(more == 1 ? " more" : " more kinds");
        return sb.toString();
    }

    /** How many kinds of work a village of this size has from the start. */
    public static int tradeKinds(int folk) {
        int n = 0;
        for (int c : VillageMath.shapeOf(Math.max(1, folk))) if (c > 0) n++;
        return n;
    }

    /** About what so many folk cost a server every tick, in milliseconds (three hundred, about twenty). */
    public static double msPerTick(int folk) {
        return folk * 20.0 / 300.0;
    }
}
