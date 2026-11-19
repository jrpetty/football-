package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * [links12] The routes between towns: where the road between two towns runs, and what going along it costs.
 *
 * <p><b>The survey.</b> When two towns first have a road between them (RoadWorks.road: neighbours with dealings, a
 * colony and its mother), the way is surveyed from the end of the one's avenue to the end of the other's over the land
 * as it lies: a search over the ground a few blocks a square (A*), where every square costs its length, more uphill and
 * down (a slope a cart cannot take is no way at all), more through the woods, a good deal more over water (a bridge to
 * build) and more again over deep water; a cliff, lava, a deep sea, another town's ground and anybody's building
 * (a player's house, a wall, a farm: whatever the world did not put there) are not crossed at all. The way found is
 * then drawn straighter wherever a straight stretch costs no more than the winding one, so the road takes long easy
 * runs and turns where the land makes it. Ground nobody has been near is judged from how the world would make it (the
 * generator's own heights and woods), so the survey never wakes the land up. The survey is kept in the first town's
 * notes (Ledger) and the road is laid on it ever after, whatever is built along it later.
 *
 * <p><b>What it costs.</b> {@link #between} gives the route between two towns as the rest of the game (the market's
 * carriage, the caravans, envoys, folk moving town, armies) reckons it: its length by the road, its grade (a track, a
 * path, gravel, stone: as good as its worse half), whether a railway joins the two, the water it bridges, how long
 * going takes, the road money a load costs, a rival's toll and whether trade is shut by an embargo (Rivalry).
 */
public final class Routes {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Routes() {}

    /** The note on the first town (the lesser id) that keeps a route's survey: its turning points. */
    static final String NOTE = "rt/";
    /** Most squares the search looks at before it gives up (and the road runs straight). */
    static final int BUDGET = 60000;
    /** A folk's walking pace over open ground, blocks a second; a cart's on the rails. */
    public static final double WALK = 3.0, RAIL = 8.0;
    /** Road money for a load: a coin every so many blocks of the road, less on a better road (by grade), less by rail. */
    public static final int COIN_EVERY = 100;
    static final double[] GRADE_COST = { 1.0, 0.9, 0.75, 0.6 };
    static final double RAIL_COST = 0.4;

    /**
     * A route as the trade between two towns sees it.
     *
     * @param length      blocks by the road, heart to heart
     * @param grade       0 track, 1 earth path, 2 gravel, 3 stone (RoadWorks.GRADES), as good as its worse half
     * @param rail        a railway open between the two
     * @param bridges     steps of the road over water
     * @param quality     0 (a track) to 1 (a stone road with a railway beside it)
     * @param toll        coins a load pays a rival town for the use of its half (Rivalry's tariffs), 0 between friends
     * @param travelTicks how long a folk on foot (or a cart, by rail) takes from heart to heart
     * @param roadMoney   the coins a load costs to carry from end to end (the carrier's road money), toll included
     * @param embargo     trade between the two is shut (Rivalry)
     */
    public record Info(UUID a, UUID b, int length, int grade, boolean rail, int bridges, double quality, int toll,
                       long travelTicks, int roadMoney, boolean embargo) {
        public String gradeWords() { return RoadWorks.GRADES[Math.max(0, Math.min(RoadWorks.GRADES.length - 1, grade))]; }
    }

    /** The route between two towns, or null if they are not on a road. */
    @Nullable
    public static Info between(@Nullable UUID x, @Nullable UUID y) {
        if (x == null || y == null) return null;
        Villages.Village vx = Villages.get(x), vy = Villages.get(y);
        RoadWorks.Road r = RoadWorks.road(vx, vy);
        if (r == null) return null;
        int length = r.length() + 2 * Roads.EXIT;
        int grade = RoadWorks.grade(x, y);
        boolean rail = Railways.linkOpen(x, y);
        int bridges = bridges(x, y, r.line());
        double quality = Math.min(1.0, grade / 3.0 * 0.8 + (rail ? 0.2 : 0.0));
        double pace = WALK * RoadWorks.PACE[Math.max(0, Math.min(3, grade))];
        long ticks = rail ? Math.round(length / RAIL * 20.0) : Math.round(length / pace * 20.0);
        if (!rail && grade == 0) ticks += bridges * 20L;                  // no bridge yet: forded, or round by the bank
        int toll = Rivalry.toll(x, y, length);
        double per = rail ? RAIL_COST : GRADE_COST[Math.max(0, Math.min(3, grade))];
        int money = Math.max(1, (int) Math.round(length / (double) COIN_EVERY * per)) + toll;
        return new Info(x, y, length, grade, rail, bridges, quality, toll, ticks, money, Rivalry.embargoed(x, y));
    }

    /** [econ] How long goods take from one town to the other by the best way there is, in ticks; -1 if no road joins them. */
    public static long travelTicks(@Nullable UUID from, @Nullable UUID to) {
        Info i = between(from, to);
        return i == null ? -1 : i.travelTicks();
    }

    /** [econ] What carrying so many loads (lots) from one town to the other costs in coin, toll included; -1 if no road. */
    public static int carriage(@Nullable UUID from, @Nullable UUID to, int lots) {
        Info i = between(from, to);
        if (i == null) return -1;
        return i.roadMoney() + Math.max(0, lots - 1) * Math.max(1, i.roadMoney() / 4);
    }

    /** [Caravans.way] The road's steps from one town's end to the other's: the surveyed road, else straight from a to b. */
    static List<int[]> alongTheRoad(Villages.Village from, Villages.Village to, BlockPos a, BlockPos b) {
        RoadWorks.Road r = RoadWorks.road(from, to);
        if (r == null) return Roads.bresenham(a.getX(), a.getZ(), b.getX(), b.getZ());
        List<int[]> line = new ArrayList<>(r.line());
        if (!r.a().equals(from.id())) java.util.Collections.reverse(line);
        return line;
    }

    /** [Caravans.roadMoney] How far it is from heart to heart by the road (as the crow flies where there is none). */
    static int lengthOr(Villages.Village from, Villages.Village to) {
        Info i = between(from.id(), to.id());
        return i != null ? i.length() : (int) Math.sqrt(from.centre().distSqr(to.centre()));
    }

    /** [Caravans.roadMoney] A load's road money by the road, or so much where there is no road. */
    static int roadMoneyOr(Villages.Village from, Villages.Village to, int otherwise) {
        Info i = between(from.id(), to.id());
        return i != null ? i.roadMoney() : otherwise;
    }

    /**
     * [Campaigns.route] An army's way from its town to its camp: out along the road toward the enemy as far as the road
     * goes nearest the camp, then across to it. Null where no road leads that way (the army goes as the crow flies).
     */
    @Nullable
    public static List<BlockPos> march(@Nullable Villages.Village v, @Nullable Villages.Village enemy, BlockPos from, BlockPos to) {
        if (v == null || enemy == null) return null;
        RoadWorks.Road r = RoadWorks.road(v, enemy);
        if (r == null) return null;
        List<int[]> line = new ArrayList<>(r.line());
        if (!r.a().equals(v.id())) java.util.Collections.reverse(line);
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int k = 0; k < line.size(); k++) {
            double dx = line.get(k)[0] - to.getX(), dz = line.get(k)[1] - to.getZ(), d = dx * dx + dz * dz;
            if (d < bestD) { bestD = d; best = k; }
        }
        double direct = (double) (from.getX() - to.getX()) * (from.getX() - to.getX()) + (double) (from.getZ() - to.getZ()) * (from.getZ() - to.getZ());
        if (best < 8 || bestD > direct) return null;
        List<BlockPos> way = new ArrayList<>();
        way.add(from);
        for (int k = 0; k < best; k += 8) way.add(new BlockPos(line.get(k)[0], from.getY(), line.get(k)[1]));
        way.add(new BlockPos(line.get(best)[0], from.getY(), line.get(best)[1]));
        way.add(to);
        return way;
    }

    /** Every route from this town, nearest first. */
    public static List<Info> from(UUID village) {
        List<Info> out = new ArrayList<>();
        for (RoadWorks.Road r : RoadWorks.roads(village)) {
            Info i = between(village, r.other(village));
            if (i != null) out.add(i);
        }
        return out;
    }

    // ------------------------------------------------------------------ a sleeping town's road gang

    /** The steps a sleeping town's road gang lays a day (laid in the world when the town wakes), and the most owed. */
    static final int OWED_A_DAY = 120, OWED_MOST = 480;
    static final String OWED = "rt.owed";

    /** [TownLod.step] A sleeping town's gang keeps at the road it was on: the steps it would have laid are owed it. */
    public static void slept(UUID village, double days) {
        String w = Ledger.note(village, RoadWorks.WORKING);
        if (w == null || w.isEmpty() || days <= 0) return;
        double owed = Math.min(OWED_MOST, owed(village) + days * OWED_A_DAY);
        Ledger.note(village, OWED, String.format(java.util.Locale.ROOT, "%.2f", owed));
    }

    static double owed(UUID village) {
        String s = Ledger.note(village, OWED);
        try { return s == null || s.isEmpty() ? 0 : Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    /** [RoadWorks.tick] Awake again: the steps its gang laid while it slept are laid now, a stretch a visit, out of the stores as ever. */
    static void catchUp(ServerLevel level, Villages.Village v, @Nullable RoadWorks.Road working) {
        double owed = owed(v.id());
        if (owed < 1) return;
        if (working == null) {
            Ledger.forget(v.id(), OWED);
            return;
        }
        int laid = RoadWorks.work(level, v, working, (int) Math.min(owed, 24), true);
        double left = laid <= 0 ? 0 : owed - laid;                    // stuck (short of stores, the ground asleep): let it be
        if (left < 1) Ledger.forget(v.id(), OWED);
        else Ledger.note(v.id(), OWED, String.format(java.util.Locale.ROOT, "%.2f", left));
    }

    public static double owedForTests(UUID village) { return owed(village); }

    private static final Map<String, Integer> BRIDGES = new HashMap<>();

    /** How many of the road's steps are over water: counted once, where the ground is awake (else from the survey). */
    static int bridges(UUID x, UUID y, List<int[]> line) {
        String k = RoadWorks.key(x, y);
        Integer n = BRIDGES.get(k);
        if (n != null) return n;
        String s = Ledger.note(x.compareTo(y) < 0 ? x : y, NOTE + "w/" + (x.compareTo(y) < 0 ? y : x));
        int w = 0;
        try { w = s == null || s.isEmpty() ? 0 : Integer.parseInt(s); } catch (NumberFormatException ignored) { }
        BRIDGES.put(k, w);
        return w;
    }

    public static void resetForTests() {
        BRIDGES.clear();
    }

    // ------------------------------------------------------------------ the survey

    /**
     * [RoadWorks.road, Roads.line] The road's line between two towns' avenue ends, from s (town a's end) to e (b's): the
     * survey kept in a's notes, or a new one made now and kept; a straight line where nothing can be surveyed.
     */
    static List<int[]> line(UUID a, UUID b, BlockPos s, BlockPos e) {
        UUID first = a.compareTo(b) < 0 ? a : b, second = first == a ? b : a;
        boolean flip = first != a;
        List<int[]> pts = decode(Ledger.note(first, NOTE + second));
        if (pts == null) {
            BlockPos fs = flip ? e : s, fe = flip ? s : e;
            pts = survey(first, second, fs, fe);
            if (pts == null) {
                pts = new ArrayList<>();
                pts.add(new int[]{ fs.getX(), fs.getZ() });
                pts.add(new int[]{ fe.getX(), fe.getZ() });
            }
            Ledger.note(first, NOTE + second, encode(pts));
        }
        List<int[]> out = trace(pts);
        if (flip) java.util.Collections.reverse(out);
        return out;
    }

    /** The turning points of a kept survey, or null. */
    @Nullable
    static List<int[]> decode(@Nullable String s) {
        if (s == null || !s.startsWith("v1;")) return null;
        List<int[]> out = new ArrayList<>();
        try {
            for (String p : s.substring(3).split(";")) {
                if (p.isEmpty()) continue;
                String[] xz = p.split(",");
                out.add(new int[]{ Integer.parseInt(xz[0]), Integer.parseInt(xz[1]) });
            }
        } catch (RuntimeException e) {
            return null;
        }
        return out.size() >= 2 ? out : null;
    }

    static String encode(List<int[]> pts) {
        StringBuilder sb = new StringBuilder("v1;");
        for (int[] p : pts) sb.append(p[0]).append(',').append(p[1]).append(';');
        return sb.toString();
    }

    /** Every step through the turning points, one block at a time. */
    static List<int[]> trace(List<int[]> pts) {
        List<int[]> out = new ArrayList<>();
        for (int k = 1; k < pts.size(); k++) {
            List<int[]> seg = Roads.bresenham(pts.get(k - 1)[0], pts.get(k - 1)[1], pts.get(k)[0], pts.get(k)[1]);
            for (int i = out.isEmpty() ? 0 : 1; i < seg.size(); i++) out.add(seg.get(i));
        }
        return out;
    }

    /** The survey: the turning points of the best way from s to e, or null if none could be made. */
    @Nullable
    static List<int[]> survey(UUID a, UUID b, BlockPos s, BlockPos e) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        Villages.Village va = Villages.get(a), vb = Villages.get(b);
        if (server == null || va == null || vb == null) return null;
        ServerLevel level = server.getLevel(va.dim());
        if (level == null) return null;
        long t0 = System.nanoTime();
        Survey sv = new Survey(level, a, b, s, e);
        List<int[]> pts = sv.run();
        if (pts == null) return null;
        Ledger.note(a, NOTE + "w/" + b, Integer.toString(sv.water));
        BRIDGES.remove(RoadWorks.key(a, b));
        int len = trace(pts).size();
        LOG.info("[MCA-ROUTES] {} - {}: surveyed {} blocks ({} straight), {} turns, {} over water, {} squares looked at in {} ms",
            Villages.name(a), Villages.name(b), len, (int) Math.sqrt(s.distSqr(e)), pts.size() - 2, sv.water, sv.looked,
            (System.nanoTime() - t0) / 1_000_000L);
        long day = level.getDayTime() / 24000L;
        String words = "the road between " + Villages.name(a) + " and " + Villages.name(b) + " was surveyed: " + len
            + " blocks by the lie of the land" + (sv.water > 0 ? ", with a bridge to build over the water" : "")
            + (pts.size() > 2 ? ", turning " + (pts.size() - 2) + (pts.size() == 3 ? " time" : " times") + " round what is in its way" : "");
        Region.event(a, b, day, words);
        return pts;
    }

    /** The search over the land, a square of so many blocks at a time. */
    static final class Survey {
        final ServerLevel level;
        final UUID a, b;
        final BlockPos s, e;
        final int cell, ox, oz, w, h;
        final Map<Long, int[]> memo = new HashMap<>();
        int water, looked;

        Survey(ServerLevel level, UUID a, UUID b, BlockPos s, BlockPos e) {
            this.level = level;
            this.a = a;
            this.b = b;
            this.s = s;
            this.e = e;
            double far = Math.sqrt(s.distSqr(e));
            this.cell = Math.max(4, (int) Math.round(far / 80.0));
            int margin = Math.max(32, (int) (far / 3));
            this.ox = Math.min(s.getX(), e.getX()) - margin;
            this.oz = Math.min(s.getZ(), e.getZ()) - margin;
            this.w = (Math.abs(s.getX() - e.getX()) + 2 * margin) / cell + 2;
            this.h = (Math.abs(s.getZ() - e.getZ()) + 2 * margin) / cell + 2;
        }

        int cx(int i) { return ox + i * cell; }
        int cz(int j) { return oz + j * cell; }

        /** The ground at a column: {y, flags} (1 water, 2 woods, 4 not to be crossed, 8 deep water). */
        int[] at(int x, int z) {
            long k = ((long) x << 32) ^ (z & 0xffffffffL);
            int[] g = memo.get(k);
            if (g == null) {
                g = ground(x, z);
                memo.put(k, g);
            }
            return g;
        }

        int[] ground(int x, int z) {
            int flags = 0;
            if (ownGround(x, z) == 0 && strangersGround(x, z)) flags |= 4;
            if (level.hasChunk(x >> 4, z >> 4)) {
                Roads.Ground g = Roads.ground(level, x, z);
                if (g == null) return new int[]{ level.getSeaLevel(), 4 };
                int y = g.y();
                if (g.water()) {
                    flags |= 1;
                    int d = 0;
                    BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, y, z);
                    while (d < 24 && !level.getBlockState(m.setY(y - d)).getFluidState().isEmpty()) d++;
                    if (d > 6) flags |= 8;
                    if (d >= 24) flags |= 4;
                } else {
                    int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                    BlockState t = level.getBlockState(new BlockPos(x, top, z));
                    if (t.is(BlockTags.LEAVES) || t.is(BlockTags.LOGS)) flags |= 2;
                    if (ownGround(x, z) == 0 && builtNear(x, y, z)) flags |= 4;
                }
                return new int[]{ y, flags };
            }
            // Ground nobody is near: how the world would make it, without waking it; judged every fourth block and
            // drawn smooth between (the world's own lie of the land is smooth at that scale).
            int x0 = Math.floorDiv(x, 4) * 4, z0 = Math.floorDiv(z, 4) * 4;
            if (x0 != x || z0 != z) {
                int[] g00 = at(x0, z0), g10 = at(x0 + 4, z0), g01 = at(x0, z0 + 4), g11 = at(x0 + 4, z0 + 4);
                double fx = (x - x0) / 4.0, fz = (z - z0) / 4.0;
                double y = g00[0] * (1 - fx) * (1 - fz) + g10[0] * fx * (1 - fz) + g01[0] * (1 - fx) * fz + g11[0] * fx * fz;
                int[] near = fx < 0.5 ? (fz < 0.5 ? g00 : g01) : (fz < 0.5 ? g10 : g11);
                return new int[]{ (int) Math.round(y), near[1] | flags };
            }
            var gen = level.getChunkSource().getGenerator();
            var rs = level.getChunkSource().randomState();
            int surface = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, rs) - 1;
            int floor = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, rs) - 1;
            if (surface > floor) {
                flags |= 1;
                if (surface - floor > 6) flags |= 8;
                if (surface - floor >= 24) flags |= 4;
            } else {
                Holder<Biome> biome = level.getBiome(new BlockPos(x, surface, z));
                if (biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_TAIGA) || biome.is(BiomeTags.IS_JUNGLE)) flags |= 2;
            }
            return new int[]{ surface, flags };
        }

        /** Somebody's building here or two blocks off (the road is three wide and keeps its distance). */
        boolean builtNear(int x, int y, int z) {
            if (built(x, y, z)) return true;
            for (int[] d : new int[][]{ { 2, 0 }, { -2, 0 }, { 0, 2 }, { 0, -2 } }) {
                if (!level.hasChunk((x + d[0]) >> 4, (z + d[1]) >> 4)) continue;
                Roads.Ground g = Roads.ground(level, x + d[0], z + d[1]);
                if (g != null && !g.water() && built(x + d[0], g.y(), z + d[1])) return true;
            }
            return false;
        }

        /** Somebody's building on the ground here: what the world did not put there, and is no road or bridge. */
        boolean built(int x, int y, int z) {
            for (int dy = 0; dy <= 3; dy++) {
                BlockState st = level.getBlockState(new BlockPos(x, y + dy, z));
                if (dy == 0 && RoadWorks.soft(st)) continue;
                if (Terraform.natural(st) || roadish(st)) continue;
                if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || st.is(Blocks.SNOW)) continue;
                return true;
            }
            return false;
        }

        /** Which of the two towns' own ground this is in (1 a, 2 b), or 0. */
        int ownGround(int x, int z) {
            for (UUID t : new UUID[]{ a, b }) {
                Villages.Village v = Villages.get(t);
                if (v == null) continue;
                int reach = Villages.townReach(t) + 8;
                if (Math.abs(x - v.centre().getX()) <= reach && Math.abs(z - v.centre().getZ()) <= reach) return t == a ? 1 : 2;
            }
            return 0;
        }

        /** Another town's ground, or a villagers' village. */
        boolean strangersGround(int x, int z) {
            for (Villages.Village t : Villages.every()) {
                if (t.id().equals(a) || t.id().equals(b) || !t.dim().equals(level.dimension())) continue;
                int reach = Villages.townReach(t.id()) + 8;
                if (Math.abs(x - t.centre().getX()) <= reach && Math.abs(z - t.centre().getZ()) <= reach) return true;
            }
            return level.hasChunk(x >> 4, z >> 4) && VanillaVillages.within(level, x, z, 4);
        }

        /** What going from one column to the next costs, or a negative number for no way. */
        double cost(int[] g0, int[] g1, double d) {
            if ((g1[1] & 4) != 0) return -1;
            double c = d;
            boolean wet0 = (g0[1] & 1) != 0, wet1 = (g1[1] & 1) != 0;
            if (!wet0 && !wet1) {
                int dh = Math.abs(g1[0] - g0[0]);
                if (dh >= 3) return -1;                                       // a cliff: no cart goes up that
                c += 3.0 * dh + (dh == 2 ? 20.0 : 0.0);                       // a step up or down at a time; two is a cutting
            } else if (wet0 != wet1) {
                if (Math.abs(g1[0] - g0[0]) > 3) return -1;                   // a bank too high to bridge from
            }
            if (wet1) c += d * ((g1[1] & 8) != 0 ? 16.0 : 5.0);
            if ((g1[1] & 2) != 0) c += d * 0.6;
            return c;
        }

        @Nullable
        List<int[]> run() {
            int si = clamp((s.getX() - ox + cell / 2) / cell, w), sj = clamp((s.getZ() - oz + cell / 2) / cell, h);
            int ei = clamp((e.getX() - ox + cell / 2) / cell, w), ej = clamp((e.getZ() - oz + cell / 2) / cell, h);
            int n = w * h;
            double[] g = new double[n];
            int[] from = new int[n];
            java.util.Arrays.fill(g, Double.MAX_VALUE);
            java.util.Arrays.fill(from, -1);
            boolean[] shut = new boolean[n];
            int start = si * h + sj, goal = ei * h + ej;
            g[start] = 0;
            PriorityQueue<double[]> open = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
            open.add(new double[]{ dist(si, sj, ei, ej), start });
            int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 } };
            boolean found = false;
            while (!open.isEmpty()) {
                int u = (int) open.poll()[1];
                if (shut[u]) continue;
                shut[u] = true;
                if (u == goal) { found = true; break; }
                if (++looked > BUDGET) break;
                int ui = u / h, uj = u % h;
                for (int[] dd : dirs) {
                    int vi = ui + dd[0], vj = uj + dd[1];
                    if (vi < 0 || vj < 0 || vi >= w || vj >= h) continue;
                    int v = vi * h + vj;
                    if (shut[v]) continue;
                    // Every block of the step judged, as the road will lie: no corner of a cliff or a house cut.
                    double c = segment(xy(u, u == start, false), xy(v, false, v == goal));
                    if (c < 0 && v != goal) continue;
                    if (c < 0) c = cell * 20;
                    double ng = g[u] + c;
                    if (ng < g[v]) {
                        g[v] = ng;
                        from[v] = u;
                        open.add(new double[]{ ng + dist(vi, vj, ei, ej), v });
                    }
                }
            }
            if (!found) {
                LOG.info("[MCA-ROUTES] {} - {}: no way found ({} squares looked at); the road runs straight",
                    Villages.name(a), Villages.name(b), looked);
                return null;
            }
            List<Integer> path = new ArrayList<>();
            for (int u = goal; u != -1; u = from[u]) path.add(0, u);
            // Straighter where a straight stretch costs no more than the winding one.
            List<int[]> pts = new ArrayList<>();
            pts.add(new int[]{ s.getX(), s.getZ() });
            int i = 0;
            while (i < path.size() - 1) {
                int best = i + 1;
                for (int j = Math.min(path.size() - 1, i + 100); j > i + 1; j--) {
                    double seg = segment(xy(path.get(i), i == 0, false), xy(path.get(j), false, j == path.size() - 1));
                    if (seg >= 0 && seg <= (g[path.get(j)] - g[path.get(i)]) * 1.02 + 0.5) { best = j; break; }
                }
                i = best;
                pts.add(xy(path.get(i), false, i == path.size() - 1));
            }
            // The water it crosses, for the books.
            for (int[] p : trace(pts)) if ((at(p[0], p[1])[1] & 1) != 0) water++;
            return pts;
        }

        int[] xy(int u, boolean isStart, boolean isEnd) {
            if (isStart) return new int[]{ s.getX(), s.getZ() };
            if (isEnd) return new int[]{ e.getX(), e.getZ() };
            return new int[]{ cx(u / h), cz(u % h) };
        }

        /** The cost of a straight stretch, block by block along the line the road would take; negative if it crosses no way. */
        double segment(int[] p, int[] q) {
            List<int[]> steps = Roads.bresenham(p[0], p[1], q[0], q[1]);
            if (steps.size() < 2) return 0;
            double len = Math.hypot(q[0] - p[0], q[1] - p[1]), each = len / (steps.size() - 1);
            double c = 0;
            int[] prev = at(p[0], p[1]);
            for (int k = 1; k < steps.size(); k++) {
                int[] gk = at(steps.get(k)[0], steps.get(k)[1]);
                double step = cost(prev, gk, each);
                if (step < 0) return -1;
                c += step;
                prev = gk;
            }
            return c;
        }

        double dist(int i0, int j0, int i1, int j1) {
            return cell * Math.hypot(i1 - i0, j1 - j0);
        }

        static int clamp(int v, int n) { return Math.max(0, Math.min(n - 1, v)); }
    }

    /** A road's or a bridge's own blocks: no building of anybody's for a survey to go round. */
    static boolean roadish(BlockState st) {
        return st.is(Blocks.DIRT_PATH) || st.is(Blocks.GRAVEL) || st.is(Blocks.COBBLESTONE) || st.is(Blocks.STONE_BRICKS)
            || st.is(Blocks.STONE_BRICK_SLAB) || st.is(Blocks.COBBLESTONE_WALL) || st.is(BlockTags.RAILS)
            || st.getFluidState().is(FluidTags.WATER) || st.is(com.jrpetty.mcassistant.item.WorkItems.MILESTONE.get());
    }

    // ------------------------------------------------------------------ for the tests

    /** The turning points kept for the road between two towns, or null. */
    @Nullable
    public static List<int[]> turnsForTests(UUID a, UUID b) {
        UUID first = a.compareTo(b) < 0 ? a : b, second = first == a ? b : a;
        return decode(Ledger.note(first, NOTE + second));
    }
}
