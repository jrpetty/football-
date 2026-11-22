package com.jrpetty.mcassistant.village;

import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * [city26] A town founded on a hillside laid out on terraces, not cut into one great flat.
 *
 * <p>A founding levels its ground to one height (FoundingPlan). On a mountain that meant a flat a hundred blocks across
 * cut thirty blocks into the hill on one side and built thirty up on the other, with a long raw face all round, and the
 * houses past it stood on their own stilts of fill. A real hill town steps down the slope: each street a little higher
 * than the last, the houses along it on their own level, a wall of dressed stone at the back of each row holding up the
 * next row above.
 *
 * <p>So where the land under the levelled ground spreads {@link #SPREAD} blocks or more from its low tenth to its high
 * tenth, the plan's ground is terraced instead:
 * <ul>
 * <li>the heart (the square, its ring street, the lots round it and the great lots by it) is level, as before;</li>
 * <li>the streets out from it follow the land as closely as a street may climb, a block in every {@link #RUN} (a
 *     surface worked out once over the whole ground: smoothed land, held within that climb of itself everywhere and of
 *     the heart), so every street is a walkable slope or a flight of shallow steps, never a cliff;</li>
 * <li>each lot of the plan is flat at the height of the street before its door (a long lot as one), so a house's door
 *     opens level onto its street;</li>
 * <li>where a lot or a street stands two or more above the ground next to it, a retaining wall of dressed stone faces the
 *     drop (entity/Terraces builds it as the ground is worked), rather than a bank of loose earth or a raw cut;</li>
 * <li>the edge of the worked ground meets the land from the terrace beside it, not from the heart's height.</li>
 * </ul>
 * Only arithmetic, like the rest of the founding's plan: no Minecraft in it.
 */
public final class Terraces {

    private Terraces() {}

    /** The land under the levelled ground spreads this much (its low tenth to its high tenth) or more: terraced. */
    public static final int SPREAD = 12;
    /** A street climbs at most a block in this many across. */
    public static final double RUN = 2.0;
    /** The heart kept level: the square, the ring street and the lots facing it, to their back line. */
    public static final int CORE = TownPlan.RING + TownPlan.STREET + TownPlan.LOT - 1;
    /** The land smoothed over this many blocks each way before the streets are fitted to it. */
    static final int SMOOTH = 4;
    /** The wall is built where a column stands this much or more over the one beside it. */
    public static final int WALL_FROM = 2;

    /** Not terraced. */
    public static final int NONE = Integer.MIN_VALUE;

    /** Is this ground steep enough under its levelled square (radius {@code radius}) to be terraced? */
    public static boolean wanted(FoundingPlan.Ground g, int radius) {
        if (radius <= CORE + 6) return false;
        int[] h = new int[g.side * g.side];
        int n = 0;
        for (int i = 0; i < h.length; i++) {
            if (g.kind[i] != FoundingPlan.LAND || FoundingPlan.reach(g.dx(i), g.dz(i)) > radius) continue;
            h[n++] = g.ground[i];
        }
        if (n < 64) return false;
        Arrays.sort(h, 0, n);
        return h[(int) (n * 0.9)] - h[(int) (n * 0.1)] >= SPREAD;
    }

    /** The middle height of the dry land round the heart (its level part): what a terraced town's square is brought to. */
    public static int heartLevel(FoundingPlan.Ground g) {
        int[] h = new int[g.side * g.side];
        int n = 0;
        for (int i = 0; i < h.length; i++) {
            if (g.kind[i] != FoundingPlan.LAND || Math.max(Math.abs(g.dx(i)), Math.abs(g.dz(i))) > CORE) continue;
            h[n++] = g.ground[i];
        }
        if (n == 0) return NONE;
        Arrays.sort(h, 0, n);
        return h[n / 2];
    }

    /** What a column is in the terraces: the level heart, a street or the square, or a lot (its own level). */
    static final byte HEART = 1, WAY = 2, PLOT = 3;

    /**
     * The terraces for this ground: for every column of the levelled square (FoundingPlan.levelled), the height it is to
     * be, or {@link #NONE} outside it; and {@code surface} filled with the smooth surface the streets follow there (for
     * the edge to meet the land from). {@code level} is the heart's.
     */
    public static int[] plan(FoundingPlan.Ground g, int radius, int level, long seed, double[] surface) {
        int side = g.side, n = side * side;
        boolean[] in = new boolean[n];
        for (int i = 0; i < n; i++) in[i] = FoundingPlan.levelled(g.dx(i), g.dz(i), radius, seed);
        // ---- what each column is: the heart, a way (street or square), or a lot (by the lot it belongs to)
        byte[] what = new byte[n];
        int[] unit = new int[n];
        Arrays.fill(unit, -1);
        java.util.List<int[]> fronts = new java.util.ArrayList<>();      // per unit: the front cell {dx, dz}
        java.util.List<TownPlan.Lot> lots = TownPlan.lots();
        // Long lots first (a long building stands on the pair as one), then the ordinary lots not under one.
        for (int pass = 0; pass < 2; pass++) {
            for (TownPlan.Lot l : lots) {
                if ((pass == 0) != (l.kind() == TownPlan.Kind.LONG)) continue;
                int[] b = TownPlan.box(l);
                if (b[2] < -g.outer || b[0] > g.outer || b[3] < -g.outer || b[1] > g.outer) continue;
                boolean heart = TownPlan.ringOf(l) == 0 && (l.use().equals("great") || l.distance() + TownPlan.LOT / 2 <= CORE);
                int id = fronts.size();
                fronts.add(TownPlan.front(l));
                for (int x = b[0]; x <= b[2]; x++) {
                    for (int z = b[1]; z <= b[3]; z++) {
                        if (Math.abs(x) > g.outer || Math.abs(z) > g.outer) continue;
                        int i = g.index(x, z);
                        if (what[i] != 0) continue;
                        what[i] = heart ? HEART : PLOT;
                        unit[i] = id;
                    }
                }
            }
        }
        for (int i = 0; i < n; i++) {
            if (what[i] != 0) continue;
            int dx = g.dx(i), dz = g.dz(i);
            boolean heart = Math.max(Math.abs(dx), Math.abs(dz)) <= CORE;
            what[i] = heart ? HEART : WAY;
        }
        // ---- the land, smoothed (the top of the water for water, the ground for anything else)
        double[] land = new double[n];
        for (int i = 0; i < n; i++) {
            byte k = g.kind[i];
            land[i] = (k == FoundingPlan.WATER || k == FoundingPlan.FILL || k == FoundingPlan.KEEP) && g.fluid[i] != Integer.MIN_VALUE
                ? g.fluid[i] : g.ground[i];
        }
        double[] sm = smooth(land, side, SMOOTH);
        // ---- the climb from the heart: blocks walked out from it over the levelled ground
        int[] d0 = new int[n];
        Arrays.fill(d0, Integer.MAX_VALUE);
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        for (int i = 0; i < n; i++) if (in[i] && what[i] == HEART) { d0[i] = 0; q.add(i); }
        int[] nb = new int[4];
        while (!q.isEmpty()) {
            int i = q.poll();
            int c = neighbours(i, side, nb);
            for (int k = 0; k < c; k++) {
                int j = nb[k];
                if (!in[j] || d0[j] != Integer.MAX_VALUE) continue;
                d0[j] = d0[i] + 1;
                q.add(j);
            }
        }
        // ---- the surface: the smoothed land held to a climb of a block in RUN, both ways, and the middle of the two; first
        // within a short way of each column (so a peak a hundred blocks off does not lift a meadow), then over the whole
        double w = 1.0 / RUN;
        double[] near = new double[n];
        double[] hiN = local(sm, side, w, NEAR, false), loN = local(sm, side, w, NEAR, true);
        for (int i = 0; i < n; i++) near[i] = (hiN[i] + loN[i]) / 2;
        double[] hi = envelope(near, in, side, w, false);
        double[] lo = envelope(near, in, side, w, true);
        Arrays.fill(surface, Double.NaN);
        for (int i = 0; i < n; i++) {
            if (!in[i]) continue;
            if (d0[i] == Integer.MAX_VALUE) { surface[i] = level; continue; }   // cut off from the heart: kept at its level
            double mid = (hi[i] + lo[i]) / 2;
            double reach = w * d0[i];
            surface[i] = Math.max(level - reach, Math.min(level + reach, mid));
        }
        // ---- the streets: each the same height right across (each lane at the height of its middle lane; a junction at
        // the height of its middle), so its steps run straight across it; then the heart level, and each lot at the
        // height of the street at its door
        int[] out = new int[n];
        Arrays.fill(out, NONE);
        g.street = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (!in[i] || what[i] != WAY) continue;
            g.street[i] = true;
            int dx = g.dx(i), dz = g.dz(i);
            int mx = middle(dx), mz = middle(dz);
            int sx = mz != NONE && mx == NONE ? dx : mx == NONE ? dx : mx;
            int sz = mx != NONE && mz == NONE ? dz : mz == NONE ? dz : mz;
            int j = Math.abs(sx) <= g.outer && Math.abs(sz) <= g.outer ? g.index(sx, sz) : i;
            double v = in[j] && !Double.isNaN(surface[j]) ? surface[j] : surface[i];
            out[i] = (int) Math.round(v);
        }
        // no lone column of a lot left standing out into the edge (a lot's corner where the levelled ground ends): a column
        // of a lot with three or four of its neighbours off the terraces is left to the edge
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < n; i++) {
                if (!in[i] || what[i] != PLOT) continue;
                int c = neighbours(i, side, nb), off = 0;
                for (int k = 0; k < c; k++) if (!in[nb[k]]) off++;
                off += 4 - c;
                if (off >= 3) { in[i] = false; g.trimmed = g.trimmed == null ? new boolean[n] : g.trimmed; g.trimmed[i] = true; }
            }
        }
        // never more than a block from one street cell to the next (where a junction meets a lane): the higher brought down
        java.util.ArrayDeque<Integer> fix = new java.util.ArrayDeque<>();
        for (int i = 0; i < n; i++) if (g.street[i]) fix.add(i);
        int[] nb2 = new int[4];
        while (!fix.isEmpty()) {
            int i = fix.poll();
            int c = neighbours(i, side, nb2);
            for (int k = 0; k < c; k++) {
                int j = nb2[k];
                if (!g.street[j] || out[j] <= out[i] + 1) continue;
                out[j] = out[i] + 1;
                fix.add(j);
            }
        }
        int[] unitH = new int[fronts.size()];
        for (int u = 0; u < unitH.length; u++) {
            int[] f = fronts.get(u);
            unitH[u] = NONE;
            if (f == null || Math.abs(f[0]) > g.outer || Math.abs(f[1]) > g.outer) continue;
            int fi = g.index(f[0], f[1]);
            if (!in[fi] || Double.isNaN(surface[fi])) continue;
            unitH[u] = what[fi] == HEART ? level : out[fi] != NONE ? out[fi] : (int) Math.round(surface[fi]);
        }
        for (int i = 0; i < n; i++) {
            if (!in[i]) continue;
            if (what[i] == HEART) out[i] = level;
            else if (what[i] == PLOT && unit[i] >= 0 && unitH[unit[i]] != NONE) out[i] = unitH[unit[i]];
            else if (out[i] == NONE) out[i] = (int) Math.round(surface[i]);
        }
        return out;
    }

    /** The middle lane of the street or avenue this far across (in x or in z) lies on, or NONE if none. */
    static int middle(int u) {
        int a = Math.abs(u);
        if (a <= TownPlan.AVENUE) return 0;
        if (a < TownPlan.RING || (a - TownPlan.RING) % TownPlan.PERIOD >= TownPlan.STREET) return NONE;
        return Integer.signum(u) * (TownPlan.RING + (a - TownPlan.RING) / TownPlan.PERIOD * TownPlan.PERIOD + 1);
    }

    /**
     * Where a street climbs a block from the street beside it: the riser, faced up the climb. The way up from the lower
     * street ({@code -1} if this column is no riser: not a street, or level with its neighbours, or a peak or a corner).
     * As {0 north, 1 east, 2 south, 3 west}: the way the stair faces, its high side.
     */
    public static int riser(FoundingPlan.Ground g, int i) {
        if (g.street == null || g.terrace == null || g.target == null || !g.street[i] || g.terrace[i] == NONE) return -1;
        int side = g.side, x = i % side, z = i / side, t = g.target[i];
        int[][] ways = { { 0, -1 }, { 1, 0 }, { 0, 1 }, { -1, 0 } };
        int from = -1, lower = 0;
        for (int w = 0; w < 4; w++) {
            int xx = x + ways[w][0], zz = z + ways[w][1];
            if (xx < 0 || zz < 0 || xx >= side || zz >= side) continue;
            int j = zz * side + xx;
            if (g.terrace[j] == NONE || !g.street[j]) continue;
            if (g.target[j] < t) {
                lower++;
                if (g.target[j] == t - 1) from = w;
            }
        }
        if (lower != 1 || from < 0) return -1;
        int opp = (from + 2) % 4;                                     // the high side is away from the lower street
        int xx = x + ways[opp][0], zz = z + ways[opp][1];
        if (xx >= 0 && zz >= 0 && xx < side && zz < side && g.target[zz * side + xx] < t) return -1;
        return opp;
    }

    /** How far round a column the land is looked at for the first fitting of the streets' surface. */
    static final int NEAR = 16;

    /**
     * Within {@code r} blocks each way of each column: the least of a(y) + w times the blocks from it to y (across plus
     * along), or ({@code low}) the greatest of a(y) less that. Worked a row and then a column at a time.
     */
    static double[] local(double[] a, int side, double w, int r, boolean low) {
        double sg = low ? -1 : 1;
        double[] one = new double[a.length], two = new double[a.length];
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                double best = Double.POSITIVE_INFINITY;
                for (int k = Math.max(0, x - r); k <= Math.min(side - 1, x + r); k++) best = Math.min(best, sg * a[z * side + k] + w * Math.abs(k - x));
                one[z * side + x] = best;
            }
        }
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                double best = Double.POSITIVE_INFINITY;
                for (int k = Math.max(0, z - r); k <= Math.min(side - 1, z + r); k++) best = Math.min(best, one[k * side + x] + w * Math.abs(k - z));
                two[z * side + x] = sg * best;
            }
        }
        return two;
    }

    /** The four neighbours of a column on the grid; how many there are. */
    static int neighbours(int i, int side, int[] out) {
        int x = i % side, z = i / side, c = 0;
        if (x > 0) out[c++] = i - 1;
        if (x < side - 1) out[c++] = i + 1;
        if (z > 0) out[c++] = i - side;
        if (z < side - 1) out[c++] = i + side;
        return c;
    }

    /** The mean of each column's square of (2r+1) by (2r+1) round it. */
    static double[] smooth(double[] a, int side, int r) {
        double[] pre = new double[(side + 1) * (side + 1)];
        for (int z = 0; z < side; z++) {
            double row = 0;
            for (int x = 0; x < side; x++) {
                row += a[z * side + x];
                pre[(z + 1) * (side + 1) + (x + 1)] = pre[z * (side + 1) + (x + 1)] + row;
            }
        }
        double[] out = new double[a.length];
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                int x0 = Math.max(0, x - r), x1 = Math.min(side - 1, x + r), z0 = Math.max(0, z - r), z1 = Math.min(side - 1, z + r);
                double s = pre[(z1 + 1) * (side + 1) + (x1 + 1)] - pre[z0 * (side + 1) + (x1 + 1)]
                    - pre[(z1 + 1) * (side + 1) + x0] + pre[z0 * (side + 1) + x0];
                out[z * side + x] = s / ((x1 - x0 + 1) * (z1 - z0 + 1));
            }
        }
        return out;
    }

    /**
     * Over the columns {@code in}: the highest surface climbing no more than {@code w} a block that stays at or under
     * {@code a} everywhere ({@code low} false: min over y of a(y) + w·d), or the lowest at or over it ({@code low} true).
     */
    static double[] envelope(double[] a, boolean[] in, int side, double w, boolean low) {
        int n = a.length;
        double[] d = new double[n];
        Arrays.fill(d, Double.POSITIVE_INFINITY);
        PriorityQueue<double[]> pq = new PriorityQueue<>((p, r) -> Double.compare(p[0], r[0]));
        for (int i = 0; i < n; i++) {
            if (!in[i]) continue;
            d[i] = low ? -a[i] : a[i];
            pq.add(new double[]{ d[i], i });
        }
        int[] nb = new int[4];
        while (!pq.isEmpty()) {
            double[] p = pq.poll();
            int i = (int) p[1];
            if (p[0] > d[i]) continue;
            int c = neighbours(i, side, nb);
            for (int k = 0; k < c; k++) {
                int j = nb[k];
                if (!in[j] || d[i] + w >= d[j]) continue;
                d[j] = d[i] + w;
                pq.add(new double[]{ d[j], j });
            }
        }
        if (low) for (int i = 0; i < n; i++) d[i] = -d[i];
        return d;
    }

    /**
     * How far down from a column's planned top its retaining wall goes: to one over the lowest of its four neighbours
     * that is terraced too and stands {@link #WALL_FROM} or more under it; {@code NONE} if none does. {@code terr} is
     * the plan's terraced heights (FoundingPlan.Ground.terrace).
     */
    public static int wallFoot(FoundingPlan.Ground g, int i) {
        if (g.terrace == null || g.target == null) return NONE;
        byte ki = g.kind[i];
        if (ki != FoundingPlan.LAND && ki != FoundingPlan.FILL) return NONE;
        boolean mine = g.terrace[i] != NONE;
        int side = g.side, low = Integer.MAX_VALUE;
        int[] nb = new int[4];
        int c = neighbours(i, side, nb);
        for (int k = 0; k < c; k++) {
            int j = nb[k];
            byte kj = g.kind[j];
            // A drop on the terraces, or between a terrace and the edge's bank beside it (either way up).
            if (kj != FoundingPlan.LAND && kj != FoundingPlan.FILL) continue;
            // On the edge alone, only a drop of three or more the plan made steeper than the land had it (where a street
            // leaves the terraces up the hill): faced too, not left a raw face of earth.
            if (!mine && g.terrace[j] == NONE) {
                int drop = g.target[i] - g.target[j];
                if (drop < WALL_FROM + 1 || drop <= Math.abs(g.ground[i] - g.ground[j])) continue;
            }
            low = Math.min(low, g.target[j]);
        }
        if (low == Integer.MAX_VALUE || g.target[i] - low < WALL_FROM) return NONE;
        return low + 1;
    }
}
