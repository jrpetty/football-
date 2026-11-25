package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [tp43] A way to the mine on foot.
 *
 * <p>A town's miners walk from the town to the pithead every morning and home every evening. Where a river lay between and
 * no bridge crossed it, they walked to the bank and could go no further; half a minute later each was put on its plot
 * (the pithead floor) in a blink, and in a player's sight they stood at the water, swam, and were pulled out by the divers
 * instead. So:
 * <ul>
 * <li><b>The look.</b> Every couple of minutes, for a town with a mine: can a folk walk from the town's heart to the
 *     pithead? A flood over the top of the ground between them, a step up or down at a time (a bridge's deck and its ramps
 *     are ground; water, lava, a cliff and a wall are not).</li>
 * <li><b>The crossing.</b> If it cannot, and the straight way out to the pithead crosses water three wide or more, the
 *     town bridges it there before anything else at the mine: the road gang's kit (BridgeKit, timber, a block clear over
 *     the water, ramps at the ends), out of the stores, a dozen blocks a visit by a hand at the town's works (on top of the
 *     town's share of hands for its works: the mine waits on it).</li>
 * <li><b>Meanwhile.</b> Till the way is open the mine is cut off: its miners do not commute (no shift, no walk back to
 *     the plot): they help build the bridge or take up the town's other work.</li>
 * <li><b>Not water.</b> No way, and no water on the straight way (a cliff, a wall), or the bridge down and still no way:
 *     nothing is built and the miners go as they always did.</li>
 * </ul>
 * The site of a new mine counts the water on the way to it against the site (TownMine, MineSites).
 */
public final class MineWay {

    private MineWay() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The narrowest water bridged (narrower is stepped through, as the ferry's survey counts it). */
    static final int LEAST_WIDE = 3;
    /** Blocks round the heart and the pithead the flood may go. */
    static final int MARGIN = 40;
    /** How near the pithead the flood must come. */
    static final int AT_PITHEAD = 8;
    /** Ticks between looks, and between visits to the bridge. */
    static final long LOOK_EVERY = 2400L, BUILD_EVERY = 100L;
    /** Blocks of the bridge a visit. */
    static final int PER_VISIT = 12;
    /** The note the bridge's crossings are kept in (BridgeKit). */
    static final String ROAD = "mineway";

    static final class Way {
        BlockPos site;
        long looked = -1, built = -1;
        boolean known, walkable = true, gaveUp, wasCut, open;
        int crossings;
        final List<BridgeKit.Plan> plans = new ArrayList<>();
        String status = "";
        Way(BlockPos site) { this.site = site; }
    }

    private static final Map<UUID, Way> WAYS = new ConcurrentHashMap<>();
    /** Bridges finished, by town (the tests). */
    private static final Map<UUID, Integer> BRIDGED = new ConcurrentHashMap<>();

    /** Tests: bridges this town has finished on the way to its mine. */
    public static int bridgedForTests(UUID village) {
        return BRIDGED.getOrDefault(village, 0);
    }

    /**
     * Every second, for each folk of a town whose mine is cut off: nothing takes it over the water to the mine meanwhile
     * (a load for the chest at the pithead, a walk to its plot): that errand dropped and the walk stopped on this side.
     */
    public static void keepOff(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (!cutOff(id)) return;
        Way w = WAYS.get(id);
        if (w == null) return;
        if (!atTheMine(f, w)) {
            Job j = f.peekJob();
            BlockPos at = j == null ? null : posOf(j.arg());
            if (at != null && near(at, w.site, 48)) {
                f.clearQueue();
                f.brain("the mine is cut off by water: that errand waits for the bridge");
            }
            BlockPos to = f.getNavigation().getTargetPos();
            if (to != null && near(to, w.site, 48) && !f.getNavigation().isDone()) {
                f.getNavigation().stop();
                if (f.peekJob() != null) f.clearQueue();                  // the errand that sent it (a load for the pithead's chest)
            }
        }
        // A miner's walk that would take it into the water (round to its plot by some way of its own, or home from the mine
        // over the water at a shift's end): not while it waits; one at the mine stays on its side till the bridge is done.
        net.minecraft.world.level.pathfinder.Path p = f.getNavigation().getPath();
        if (f.stationTask() == AssistantEntity.StationTask.MINE && p != null && !p.isDone() && !f.isInWater()) {
            for (int i = p.getNextNodeIndex(); i < p.getNodeCount(); i++) {
                BlockPos n = p.getNodePos(i);
                if (f.level().getFluidState(n).is(net.minecraft.tags.FluidTags.WATER)) {
                    f.getNavigation().stop();
                    break;
                }
            }
        }
    }

    /** Is this folk over the water at its town's mine while the mine is cut off (it works there; it cannot come over to help)? */
    public static boolean farSide(AssistantEntity f) {
        UUID id = f.ownerId();
        if (!cutOff(id)) return false;
        Way w = WAYS.get(id);
        return w != null && atTheMine(f, w);
    }

    /** At the mine: on its ground round the pithead, or down in its works (well under the ground there, not far off). */
    private static boolean atTheMine(AssistantEntity f, Way w) {
        BlockPos p = f.blockPosition();
        if (near(p, w.site, 20)) return true;
        if (!near(p, w.site, 64)) return false;
        int ground = f.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, w.site.getX(), w.site.getZ());
        return p.getY() < ground - 4;
    }

    private static boolean near(BlockPos a, BlockPos b, int r) {
        return Math.abs(a.getX() - b.getX()) <= r && Math.abs(a.getZ() - b.getZ()) <= r;
    }

    @Nullable
    private static BlockPos posOf(@Nullable String arg) {
        if (arg == null) return null;
        String[] p = arg.trim().split("\\s+");
        if (p.length < 3) return null;
        try {
            return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Is this town's mine cut off (no way on foot, a bridge to build first)? Its miners do not commute meanwhile. */
    public static boolean cutOff(@Nullable UUID village) {
        Way w = village == null ? null : WAYS.get(village);
        return w != null && w.known && !w.walkable && !w.plans.isEmpty();
    }

    /** For the books and the tests: how the way to the mine stands. */
    public static String status(@Nullable UUID village) {
        Way w = village == null ? null : WAYS.get(village);
        if (w == null || !w.known) return "the way to the mine not looked at yet";
        if (w.walkable) return "the mine is reached on foot" + (w.wasCut ? " (over the town's new bridge" + (w.plans.isEmpty() ? ")" : ", its railings going on)") : "");
        if (w.gaveUp) return "no way on foot to the mine, and none the town can bridge";
        return "the mine is cut off by water: the town is bridging it (" + w.crossings + " crossing(s)" + (w.status.isEmpty() ? "" : "; " + w.status) + ")";
    }

    /** Tests: looked at again on the next tick. */
    public static void lookAgainForTests(UUID village) {
        Way w = WAYS.get(village);
        if (w != null) { w.looked = -1; w.built = -1; }
    }

    // ------------------------------------------------------------------ the town's day

    /** Every second for each town (Transport): looked at now and then, and the bridge built. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos site = TownMine.siteOf(id);
        if (site == null) { WAYS.remove(id); return; }
        Way w = WAYS.get(id);
        if (w == null || !w.site.equals(site)) { w = new Way(site); WAYS.put(id, w); }
        long now = level.getGameTime();
        if (w.looked < 0 || now - w.looked >= LOOK_EVERY || now < w.looked) look(level, v, w);
        if (!w.plans.isEmpty() && (cutOff(id) || w.walkable) && (w.built < 0 || now - w.built >= BUILD_EVERY || now < w.built)) {
            w.built = now;
            build(level, v, w);
        }
    }

    private static void look(ServerLevel level, Villages.Village v, Way w) {
        UUID id = v.id();
        Boolean ok = walkable(level, v.centre(), w.site);
        if (ok == null) return;                                         // its ground not all loaded: another time
        w.looked = level.getGameTime();
        w.known = true;
        boolean was = w.walkable;
        w.walkable = ok;
        if (ok) {
            if (!w.plans.isEmpty() && !w.open) {
                // Its deck across: the way is open (the railings and lights go on as the stores run to them).
                w.open = true;
                BRIDGED.merge(id, 1, Integer::sum);
                Villages.tell(id, level.getDayTime() / 24000L, "the way to the mine is open on foot, over the new bridge");
                LOG.info("[MCA-MINEWAY] {}: the way to the mine is open", Villages.name(id));
            }
            return;
        }
        if (w.gaveUp) return;
        // Bridged already (by this town, on an earlier look, or its road gang): the water was what stood between, and it is
        // crossed; what else the flood finds (the pithead's own diggings) the miners know their way round.
        if (w.plans.isEmpty() && w.wasCut) {
            w.walkable = true;
            return;
        }
        if (w.plans.isEmpty()) {
            // The cheapest way over: the walk from the heart to the pithead that crosses the least water (a river crossed
            // once on a short walk round, rather than four times on the straight line); the straight line if that cannot be had.
            List<int[]> line = route(level, v.centre(), w.site);
            if (line == null) line = Roads.bresenham(v.centre().getX(), v.centre().getZ(), w.site.getX(), w.site.getZ());
            List<BridgeKit.Plan> drawn = new ArrayList<>();
            for (int[] run : waterRuns(level, line)) {
                BridgeKit.Span s = BridgeKit.spanAt(level, id, ROAD, line, run[0] + run[1] / 2, 1);
                if (s == null) continue;
                // The cheapest that serves: planks through (no log beams to wait on), unless the town drew it in timber already.
                String fam = "b18.fam/" + ROAD + "/" + s.a() + "/w";
                if (com.jrpetty.mcassistant.village.Ledger.note(id, fam) == null || com.jrpetty.mcassistant.village.Ledger.note(id, fam).isEmpty())
                    com.jrpetty.mcassistant.village.Ledger.note(id, fam, BridgeKit.PLANKS.name());
                BridgeKit.Plan p = BridgeKit.plan(level, v, id, ROAD, line, s, false, false);
                if (p != null && !drawn.contains(p)) drawn.add(p);
            }
            // Every deck across first, nearest the town first, and only then the railings, end posts and lights: a town with
            // four rivers between it and its mine had its first bridge's railings waiting on planks while the other three
            // decks waited on them (and its miners on all four).
            List<BridgeKit.Plan> finish = new ArrayList<>();
            for (BridgeKit.Plan p : drawn) {
                List<BridgeKit.Piece> deck = new ArrayList<>(), rest = new ArrayList<>();
                for (BridgeKit.Piece pc : p.pieces) ("REQ".indexOf(pc.kind()) >= 0 ? rest : deck).add(pc);
                w.plans.add(new BridgeKit.Plan(p.key, p.shape, p.family, deck));
                if (!rest.isEmpty()) finish.add(new BridgeKit.Plan(p.key, p.shape, p.family, rest));
            }
            w.plans.addAll(finish);
            w.crossings = drawn.size();
            if (w.plans.isEmpty()) {
                if (was) LOG.info("[MCA-MINEWAY] {}: no way on foot to the mine at {}, and no water on the way to bridge", Villages.name(id), w.site.toShortString());
                w.gaveUp = true;
                return;
            }
            w.wasCut = true;
            Villages.tell(id, level.getDayTime() / 24000L, "the mine is cut off from the town by water: the town is to bridge it before its miners go out to it");
            LOG.info("[MCA-MINEWAY] {}: the mine at {} cut off by water; {} crossing(s) to bridge", Villages.name(id), w.site.toShortString(), w.crossings);
        } else if (decksDown(level, w)) {
            // Every deck across, and the flood still finds no way (a cliff past the river, the pithead dug about, a ramp onto
            // an island between two spans): the water is crossed, and that is what the town could do; the miners go as they
            // always did (the railings and lights still going on).
            w.walkable = true;
            if (allDown(level, w)) w.plans.clear();
            LOG.info("[MCA-MINEWAY] {}: the decks are across (the flood finds no way still, stopping at {}: left to the miners)", Villages.name(id), floodEnd);
        }
    }

    private static boolean allDown(ServerLevel level, Way w) {
        for (BridgeKit.Plan p : w.plans) {
            skipDone(level, p);
            if (p.cursor < p.pieces.size()) return false;
        }
        return true;
    }

    /** The bank by a bridge's end: from this place along it, outward till the ground is dry (a hand stood in the river before). */
    private static BlockPos dry(ServerLevel level, BridgeKit.Shape s, int major, int out) {
        BlockPos first = Visitors.surface(level, s.at(major, 0, s.deckY()));
        for (int k = 0; k <= 8; k++) {
            BlockPos p = Visitors.surface(level, s.at(major + k * out, 0, s.deckY()));
            if (level.getFluidState(p).isEmpty() && level.getFluidState(p.below()).isEmpty()) return p;
        }
        return first;
    }

    /** Every deck of the crossings across (the railings and lights may be to come). */
    private static boolean decksDown(ServerLevel level, Way w) {
        for (BridgeKit.Plan p : w.plans) {
            boolean deck = false;
            for (BridgeKit.Piece pc : p.pieces) if (pc.kind() == 'D') { deck = true; break; }
            if (!deck) continue;
            skipDone(level, p);
            if (p.cursor < p.pieces.size()) return false;
        }
        return true;
    }

    private static void skipDone(ServerLevel level, BridgeKit.Plan p) {
        while (p.cursor < p.pieces.size()) {
            BridgeKit.Piece pc = p.pieces.get(p.cursor);
            if (!level.isLoaded(pc.pos())) return;
            BlockState st = level.getBlockState(pc.pos());
            if (!BridgeKit.done(st, pc) && !BridgeKit.theirs(st)) return;
            p.cursor++;
        }
    }

    /** A visit to the bridge: a hand at the works on the near bank, and a dozen blocks of it laid out of the stores. */
    private static void build(ServerLevel level, Villages.Village v, Way w) {
        for (BridgeKit.Plan p : w.plans) {
            skipDone(level, p);
            if (p.cursor >= p.pieces.size()) continue;
            BridgeKit.Shape s = p.shape();
            // The gang stands on the bank at the town's end of it.
            BlockPos a = dry(level, s, s.w0() - 2, -1), b = dry(level, s, s.w1() + 2, 1);
            BlockPos stand = a.distSqr(v.centre()) <= b.distSqr(v.centre()) ? a : b;
            if (!TownJobs.atWork(level, v, "mineway", stand, "building the bridge on the way to the mine", AssistantEntity.StationTask.HAUL)) {
                w.status = "waiting for a hand";
                return;
            }
            String out = BridgeKit.lay(level, v, p, PER_VISIT, false);
            if (out == null) {
                boolean deck = false;
                for (BridgeKit.Piece pc : p.pieces) if (pc.kind() == 'D') { deck = true; break; }
                if (deck && !w.open && decksDown(level, w)) { w.open = true; BRIDGED.merge(v.id(), 1, Integer::sum); }
                w.status = "";
                w.looked = -1;                                                 // looked at again at once
                String what = deck ? "deck was laid across the water" : "railings and lights were finished";
                Villages.tell(v.id(), level.getDayTime() / 24000L, "a " + p.family().name().toLowerCase() + " bridge's " + what + " on the way to the mine");
                LOG.info("[MCA-MINEWAY] {}: a {} bridge's {} on the way to the mine", Villages.name(v.id()), p.family().name(), what);
            } else {
                w.status = BridgeKit.MORE.equals(out) ? "building" : "waiting for " + out;
            }
            return;
        }
        w.plans.clear();                                                    // all of it down
        w.looked = -1;
    }

    // ------------------------------------------------------------------ the look

    /** The runs of water three wide or more on this line, beyond the first few steps: {start index, length}. */
    static List<int[]> waterRuns(ServerLevel level, List<int[]> line) {
        List<int[]> out = new ArrayList<>();
        int run = 0, first = -1;
        for (int i = 0; i < line.size(); i++) {
            int[] p = line.get(i);
            BridgeKit.Column c = level.hasChunk(p[0] >> 4, p[1] >> 4) ? BridgeKit.column(level, p[0], p[1]) : null;
            if (c != null && c.water()) {
                if (run == 0) first = i;
                run++;
                continue;
            }
            if (run >= LEAST_WIDE) out.add(new int[]{ first, run });
            run = 0;
        }
        return out;
    }

    /** [TownMine, MineSites] How much open water a walk from the heart straight out to this site would have to cross. */
    public static int waterOnTheWay(ServerLevel level, BlockPos heart, BlockPos site) {
        int n = 0;
        for (int[] r : waterRuns(level, Roads.bresenham(heart.getX(), heart.getZ(), site.getX(), site.getZ()))) n += r[1];
        return n;
    }

    /**
     * Can a folk walk from here to within a few blocks of there? A flood over the top of the ground (the heightmap's,
     * through leaves), a step of a block up or down at a time, never onto water or lava, in the box round both with a
     * margin. Null if some of that ground is not loaded.
     */
    @Nullable
    static Boolean walkable(ServerLevel level, BlockPos from, BlockPos to) {
        int x0 = Math.min(from.getX(), to.getX()) - MARGIN, x1 = Math.max(from.getX(), to.getX()) + MARGIN;
        int z0 = Math.min(from.getZ(), to.getZ()) - MARGIN, z1 = Math.max(from.getZ(), to.getZ()) + MARGIN;
        int w = x1 - x0 + 1, h = z1 - z0 + 1;
        if ((long) w * h > 400L * 400L) return null;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) if (!level.hasChunk(cx, cz)) return null;
        int[] top = new int[w * h];
        boolean[] ok = new boolean[w * h];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < w; i++) {
            for (int k = 0; k < h; k++) {
                int x = x0 + i, z = z0 + k;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                top[i * h + k] = y;
                m.set(x, y - 1, z);
                BlockState st = level.getBlockState(m);
                ok[i * h + k] = st.getFluidState().isEmpty() && !st.is(net.minecraft.tags.BlockTags.FENCES) && !st.is(net.minecraft.tags.BlockTags.WALLS);
            }
        }
        int start = nearestOk(ok, w, h, from.getX() - x0, from.getZ() - z0);
        if (start < 0) return false;
        boolean[] seen = new boolean[w * h];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        q.add(start);
        seen[start] = true;
        int tx = to.getX() - x0, tz = to.getZ() - z0;
        int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        int nearest = Integer.MAX_VALUE;
        while (!q.isEmpty()) {
            int c = q.poll();
            int ci = c / h, ck = c % h;
            int off = Math.max(Math.abs(ci - tx), Math.abs(ck - tz));
            if (off < nearest) { nearest = off; floodEnd = (x0 + ci) + ", " + top[c] + ", " + (z0 + ck); }
            if (off <= AT_PITHEAD) return true;
            for (int[] d : dirs) {
                int ni = ci + d[0], nk = ck + d[1];
                if (ni < 0 || nk < 0 || ni >= w || nk >= h) continue;
                int n = ni * h + nk;
                if (seen[n] || !ok[n] || Math.abs(top[n] - top[c]) > 1) continue;
                seen[n] = true;
                q.add(n);
            }
        }
        return false;
    }

    /** Where the last flood that found no way got nearest the pithead (the log). */
    private static volatile String floodEnd = "-";

    /** A block of water on the way costs as much as this many on land (a bridge's length against a walk round). */
    static final int WATER_COST = 12;

    /**
     * The cheapest walk from the heart to the pithead with the water on it to bridge: over the same ground as the flood,
     * where a block of water may be crossed too (at {@link #WATER_COST}) and a bank a block or two over the water stepped
     * off and onto. The cells of the walk, {x, z}, heart first; null if the ground is not all loaded or there is no such walk.
     */
    @Nullable
    static List<int[]> route(ServerLevel level, BlockPos from, BlockPos to) {
        int x0 = Math.min(from.getX(), to.getX()) - MARGIN, x1 = Math.max(from.getX(), to.getX()) + MARGIN;
        int z0 = Math.min(from.getZ(), to.getZ()) - MARGIN, z1 = Math.max(from.getZ(), to.getZ()) + MARGIN;
        int w = x1 - x0 + 1, h = z1 - z0 + 1;
        if ((long) w * h > 400L * 400L) return null;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) if (!level.hasChunk(cx, cz)) return null;
        int n = w * h;
        int[] top = new int[n];
        byte[] kind = new byte[n];                                       // 0 land, 1 water, 2 neither (lava, a fence, a wall)
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < w; i++) {
            for (int k = 0; k < h; k++) {
                int x = x0 + i, z = z0 + k;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                top[i * h + k] = y;
                m.set(x, y - 1, z);
                BlockState st = level.getBlockState(m);
                if (st.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) kind[i * h + k] = 1;
                else if (!st.getFluidState().isEmpty() || st.is(net.minecraft.tags.BlockTags.FENCES) || st.is(net.minecraft.tags.BlockTags.WALLS)) kind[i * h + k] = 2;
            }
        }
        boolean[] ok = new boolean[n];
        for (int c = 0; c < n; c++) ok[c] = kind[c] == 0;
        int start = nearestOk(ok, w, h, from.getX() - x0, from.getZ() - z0);
        if (start < 0) return null;
        int tx = to.getX() - x0, tz = to.getZ() - z0;
        int[] dist = new int[n], prev = new int[n];
        java.util.Arrays.fill(dist, Integer.MAX_VALUE);
        dist[start] = 0;
        prev[start] = -1;
        java.util.PriorityQueue<long[]> q = new java.util.PriorityQueue<>(java.util.Comparator.comparingLong(a -> a[0]));
        q.add(new long[]{ 0, start });
        int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        int end = -1;
        while (!q.isEmpty()) {
            long[] e = q.poll();
            int c = (int) e[1];
            if (e[0] > dist[c]) continue;
            int ci = c / h, ck = c % h;
            if (kind[c] == 0 && Math.abs(ci - tx) <= AT_PITHEAD && Math.abs(ck - tz) <= AT_PITHEAD) { end = c; break; }
            for (int[] d : dirs) {
                int ni = ci + d[0], nk = ck + d[1];
                if (ni < 0 || nk < 0 || ni >= w || nk >= h) continue;
                int nb = ni * h + nk;
                if (kind[nb] == 2) continue;
                int dy = Math.abs(top[nb] - top[c]);
                if (dy > (kind[nb] == 1 || kind[c] == 1 ? 2 : 1)) continue;
                int nd = dist[c] + (kind[nb] == 1 ? WATER_COST : 1);
                if (nd >= dist[nb]) continue;
                dist[nb] = nd;
                prev[nb] = c;
                q.add(new long[]{ nd, nb });
            }
        }
        if (end < 0) return null;
        List<int[]> out = new ArrayList<>();
        for (int c = end; c >= 0; c = prev[c]) out.add(new int[]{ x0 + c / h, z0 + c % h });
        java.util.Collections.reverse(out);
        return out;
    }

    private static int nearestOk(boolean[] ok, int w, int h, int i0, int k0) {
        for (int r = 0; r <= 4; r++)
            for (int di = -r; di <= r; di++) for (int dk = -r; dk <= r; dk++) {
                if (Math.max(Math.abs(di), Math.abs(dk)) != r) continue;
                int i = i0 + di, k = k0 + dk;
                if (i >= 0 && k >= 0 && i < w && k < h && ok[i * h + k]) return i * h + k;
            }
        return -1;
    }
}
