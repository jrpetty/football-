package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
        int crossings, stairs;
        final List<BridgeKit.Plan> plans = new ArrayList<>();
        /** [tp46] Where the hand stands for each stair (its foot), by the plan's key. */
        final Map<String, BlockPos> stands = new java.util.HashMap<>();
        /** [tp46] Planks bought for the way alone (every plan's purse), and when the traders were last asked. */
        final int[] purse = { 0 };
        long boughtAt = -100000L;
        /** [tp46] When the way was found cut off, and when it opened (the tests, the log). */
        long cutAt = -1, openedAt = -1;
        /** [tp46] The walk the way was planned along, heart first ({x, z}), and its cells' ground; null for one never cut off. */
        @Nullable List<int[]> line;
        boolean lined, relined;
        /** [tp47] The town's ferry is out of this bridge's way (Ferries.giveWay). */
        boolean ferryClear;
        String status = "";
        Way(BlockPos site) { this.site = site; }
    }

    /** [tp46] A water run with no more land than this between it and the next is one crossing over both (a lake's islands). */
    static final int ISLAND = 8;
    /** [tp46] The mine's bridge's deck at most this far over the water (the ramps take it up a higher bank). */
    static final int RISE = 3;
    /** [tp46] A bank too steep to walk, climbed by a stair: the highest, and its cost a block of height against a step of walk. */
    static final int MOST_CLIMB = 24, CLIMB_COST = 10;
    /** [tp46] Blocks of a stair laid (or cut) a visit: digging is quicker than carrying planks out over the water. */
    static final int STAIR_PER_VISIT = 24;
    /** [tp46] Ticks between asks of the traders for the bridge's planks. */
    static final long BUY_EVERY = 600L;

    /** [tp46] Tests and the soak: how long the way took to open (ticks from found cut to open), or -1. */
    public static long openedAfterForTests(UUID village) {
        Way w = WAYS.get(village);
        return w == null || w.openedAt < 0 || w.cutAt < 0 ? -1 : w.openedAt - w.cutAt;
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

    /**
     * [tp46] A miner's next stop on the way its town opened to the mine (over the new bridge, up the cut stair), sixteen steps
     * along it toward where it is going, for a walk too long for the pathfinder in one go (VillageFolkEntity.wayToward): left
     * to the pathfinder a long walk from the pithead went down the bank beside the bridge's end and into the lake. Null when
     * its town has no such way, or it is nowhere near it (it goes as it always did).
     */
    @Nullable
    public static BlockPos waypoint(AssistantEntity f, BlockPos dest) {
        if (f.stationTask() != AssistantEntity.StationTask.MINE) return null;
        UUID id = f.ownerId();
        Way w = id == null ? null : WAYS.get(id);
        if (w == null || w.line == null || !w.walkable || w.line.size() < 2 || !w.plans.isEmpty() && !w.open) return null;
        List<int[]> line = w.line;
        int[] heart = line.get(0), pit = line.get(line.size() - 1);
        long toPit = sq(dest.getX() - pit[0], dest.getZ() - pit[1]), toHeart = sq(dest.getX() - heart[0], dest.getZ() - heart[1]);
        boolean out = toPit <= toHeart;                                      // going out to the mine, or home
        int near = -1;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < line.size(); i++) {
            long d = sq(f.getBlockX() - line.get(i)[0], f.getBlockZ() - line.get(i)[1]);
            if (d < best) { best = d; near = i; }
        }
        if (near < 0 || best > 40L * 40L) return null;
        // Nearly there: the last stretch is the pathfinder's own.
        if (out ? near >= line.size() - 4 : near <= 3) return null;
        if (!(f.level() instanceof ServerLevel level)) return null;
        if (best > 6L * 6L) {
            // [tp47] Off the way: onto it where it can be walked to, not merely where it is nearest (beside an old bridge out over
            // a lake, the nearest stop was out on the bridge across the water, and a miner on the shore swam for it).
            int on = join(level, line, f);
            return on < 0 ? null : dryStop(level, line, on, out ? 1 : -1);
        }
        int to = out ? Math.min(line.size() - 1, near + 16) : Math.max(0, near - 16);
        return dryStop(level, line, to, out ? 1 : -1);
    }

    /**
     * [tp47] Where a folk off the way gets onto it: the nearest stop on it (within forty blocks) it can walk straight to
     * without crossing water (the ground's top between, a column at a time, the heightmap's: an old bridge's deck is ground;
     * a deck high over the water from the shore beside it is no step). -1 if there is none.
     */
    private static int join(ServerLevel level, List<int[]> line, AssistantEntity f) {
        List<long[]> near = new ArrayList<>();
        for (int i = 0; i < line.size(); i++) {
            long d = sq(f.getBlockX() - line.get(i)[0], f.getBlockZ() - line.get(i)[1]);
            if (d <= 40L * 40L) near.add(new long[]{ d, i });
        }
        near.sort((a, b) -> Long.compare(a[0], b[0]));
        for (long[] n : near) {
            int[] c = line.get((int) n[1]);
            if (dryStraight(level, f.getBlockX(), f.getBlockZ(), f.getBlockY(), c[0], c[1])) return (int) n[1];
        }
        return -1;
    }

    /** [tp47] Is the straight walk from here to there over dry ground, never more than a few blocks up or down a step? */
    private static boolean dryStraight(ServerLevel level, int x0, int z0, int y0, int x1, int z1) {
        int n = (int) Math.ceil(Math.sqrt(sq(x1 - x0, z1 - z0)));
        int own = level.hasChunk(x0 >> 4, z0 >> 4) ? Visitors.surface(level, new BlockPos(x0, 0, z0)).getY() : y0;
        int last = Math.abs(own - y0) <= 3 ? y0 : own;                    // (down in the works: from the ground over it)
        for (int k = 1; k <= n; k++) {
            int x = x0 + Math.round((x1 - x0) * k / (float) n), z = z0 + Math.round((z1 - z0) * k / (float) n);
            if (!level.hasChunk(x >> 4, z >> 4)) return false;
            BlockPos top = Visitors.surface(level, new BlockPos(x, 0, z));
            if (wet(level, top)) return false;
            if (Math.abs(top.getY() - last) > 3) return false;            // a cliff or a deck high over the ground: no step
            last = top.getY();
        }
        return true;
    }

    /** [tp46] The stop on the line at {@code i}, or the nearest dry one past it (never a stop out in the water). */
    @Nullable
    private static BlockPos dryStop(ServerLevel level, List<int[]> line, int i, int dir) {
        for (int k = 0; k <= 8; k++) {
            for (int j : new int[]{ i + dir * k, i - dir * k }) {
                if (j < 0 || j >= line.size()) continue;
                BlockPos at = Visitors.surface(level, new BlockPos(line.get(j)[0], 0, line.get(j)[1]));
                if (!wet(level, at)) return at;
            }
        }
        return null;
    }

    private static long sq(long dx, long dz) { return dx * dx + dz * dz; }

    /**
     * [tp46] For any folk of a town whose way to the mine is open over water it bridged: a walk planned through the water
     * (a far errand's way cut short at the river, the nearest the pathfinder got) is a walk to the next stop along the way
     * over the bridge instead, toward the end of it its errand lies at (FolkNavigation). Null when the water is none of the
     * way's (no line, the folk or its errand nowhere near it, both at the same end).
     */
    @Nullable
    public static BlockPos detour(AssistantEntity f, BlockPos target) {
        UUID id = f.ownerId();
        Way w = id == null ? null : WAYS.get(id);
        if (w == null || w.line == null || !w.wasCut || w.line.size() < 8) return null;   // (being built too: the way out to its works)
        List<int[]> line = w.line;
        int nf = -1, nt = -1;
        long bf = Long.MAX_VALUE, bt = Long.MAX_VALUE;
        for (int i = 0; i < line.size(); i++) {
            int[] c = line.get(i);
            long df = sq(f.getBlockX() - c[0], f.getBlockZ() - c[1]), dt = sq(target.getX() - c[0], target.getZ() - c[1]);
            if (df < bf) { bf = df; nf = i; }
            if (dt < bt) { bt = dt; nt = i; }
        }
        if (nf < 0 || bf > 40L * 40L || Math.abs(nt - nf) < 6) return null;
        if (!(f.level() instanceof ServerLevel level)) return null;
        int to = bf > 4L * 4L ? join(level, line, f) : nf + Integer.signum(nt - nf) * Math.min(12, Math.abs(nt - nf));   // [tp47]
        return to < 0 ? null : dryStop(level, line, to, Integer.signum(nt - nf));
    }

    /** [tp47] Steps along the way to try a leg of, the furthest first. */
    private static final int[] LEGS = { 16, 12, 8, 5, 3 };

    /**
     * [tp47] For a folk of a town whose way to the mine is open over water (bridged, or round by a dry walk it planned): stops
     * along the way toward the end of it its errand lies at, the furthest first, each dry (FolkNavigation: a walk planned
     * through the water is a walk to the furthest of these it reaches dry instead). Empty when the way is none of this walk's
     * (no line, the folk or its errand nowhere near it, both at the same end).
     */
    public static List<BlockPos> legs(AssistantEntity f, BlockPos target) {
        int[] at = ends(f, target);
        if (at == null || !(f.level() instanceof ServerLevel level)) return List.of();
        List<int[]> line = WAYS.get(f.ownerId()).line;
        int nf = at[0], nt = at[1], dir = Integer.signum(nt - nf);
        List<BlockPos> out = new ArrayList<>();
        if (at[2] > 4 * 4) {                                              // off the way: onto it first, where it can walk to it
            int j = join(level, line, f);
            BlockPos on = j < 0 ? null : dryStop(level, line, j, dir);
            if (on != null) out.add(on);
            if (j < 0) return out;                                        // (no dry way onto it from here: none along it either)
            nf = j;
        }
        for (int k : LEGS) {
            int i = nf + dir * Math.min(k, Math.abs(nt - nf));
            BlockPos s = dryStop(level, line, i, dir);
            if (s != null && !out.contains(s) && s.distSqr(f.blockPosition()) > 4) out.add(s);
        }
        return out;
    }

    /**
     * [tp47] Is there a dry way between this folk and where it is going by its town's way to the mine: the way open (the bridge
     * down, or none wanted), the folk and its errand each near the way, at its two ends or far along it from each other? Then
     * a walk through the water is never its way (FolkNavigation).
     */
    public static boolean dryWayBetween(AssistantEntity f, BlockPos target) {
        UUID id = f.ownerId();
        Way w = id == null ? null : WAYS.get(id);
        if (w == null || !w.known || !w.walkable || !w.plans.isEmpty() && !w.open) return false;
        return ends(f, target) != null;
    }

    /** [tp47] The way's stops nearest the folk and its errand, and the folk's distance (squared) from its own; null when the
     *  way is none of this walk's. */
    @Nullable
    private static int[] ends(AssistantEntity f, BlockPos target) {
        UUID id = f.ownerId();
        Way w = id == null ? null : WAYS.get(id);
        if (w == null || w.line == null || w.line.size() < 8) return null;
        List<int[]> line = w.line;
        int nf = -1, nt = -1;
        long bf = Long.MAX_VALUE, bt = Long.MAX_VALUE;
        for (int i = 0; i < line.size(); i++) {
            int[] c = line.get(i);
            long df = sq(f.getBlockX() - c[0], f.getBlockZ() - c[1]), dt = sq(target.getX() - c[0], target.getZ() - c[1]);
            if (df < bf) { bf = df; nf = i; }
            if (dt < bt) { bt = dt; nt = i; }
        }
        if (nf < 0 || bf > 40L * 40L || Math.abs(nt - nf) < 6) return null;
        // The errand at the way's far end, or anywhere along it out past the water from the folk.
        if (bt > 40L * 40L && !(nt == 0 || nt == line.size() - 1)) return null;
        return new int[]{ nf, nt, (int) Math.min(Integer.MAX_VALUE, bf) };
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

    /** Has this town bridged the water on the way to its mine (its miners walk over it, not by the ferry)? */
    public static boolean bridged(@Nullable UUID village) {
        Way w = village == null ? null : WAYS.get(village);
        return w != null && w.known && w.walkable && w.wasCut;
    }

    /** [tp46] Is this town's mine reached on foot (looked at, and the flood finds a way)? */
    public static boolean onFoot(@Nullable UUID village) {
        Way w = village == null ? null : WAYS.get(village);
        return w != null && w.known && w.walkable;
    }

    /** For the books and the tests: how the way to the mine stands. */
    public static String status(@Nullable UUID village) {
        Way w = village == null ? null : WAYS.get(village);
        if (w == null || !w.known) return "the way to the mine not looked at yet";
        if (w.walkable) return "the mine is reached on foot" + (w.wasCut ? " (over the town's new bridge" + (w.plans.isEmpty() ? ")" : ", its railings going on)") : "");
        if (w.gaveUp) return "no way on foot to the mine, and none the town can bridge";
        return "the mine is cut off by water: the town is bridging it (" + w.crossings + " crossing(s)" + (w.stairs > 0 ? ", " + w.stairs + " stair(s)" : "")
            + (w.status.isEmpty() ? "" : "; " + w.status) + ")";
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
        // [tp47] A ferry over the water the town is to bridge on the way to its mine, at the same crossing (both put where it is
        // narrowest), gives way to the bridge as soon as it is planned, whenever the ferry was found: its landing would be a
        // pocket under the deck, walled off from the bank by the abutment, and nobody is rowed to the mine meanwhile anyway.
        if (!w.plans.isEmpty()) {
            List<BlockPos> ground = new ArrayList<>();
            for (BridgeKit.Plan p : w.plans) for (BridgeKit.Piece pc : p.pieces) ground.add(pc.pos());
            w.ferryClear = Ferries.giveWay(level, v, ground);
        }
        if (!w.plans.isEmpty() && (cutOff(id) || w.walkable) && (w.built < 0 || now - w.built >= BUILD_EVERY || now < w.built)) {
            w.built = now;
            if (!w.ferryClear) { w.status = "waiting for the ferry's last crossing"; return; }
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
        if (ok && w.line == null && w.plans.isEmpty() && !w.lined) {
            // [tp46] The walk over the ground as it stands (over a footbridge, round a pond), kept for the miners' far walks.
            w.lined = true;
            if (!waterRuns(level, Roads.bresenham(v.centre().getX(), v.centre().getZ(), w.site.getX(), w.site.getZ())).isEmpty())
                w.line = route(level, v.centre(), w.site, false);
        }
        if (ok) {
            // Its decks across, but not its railings yet: still cut off (a deck a block wide between the railings' beams, and
            // six miners crowding over it, shouldered each other off into the river).
            if (!w.plans.isEmpty() && !allDown(level, w)) { w.walkable = false; return; }
            // [tp46] Open: the walk as it now stands (over the new deck, up the new stair) is the way its folk are led by. The way
            // it was planned along crossed the water where the river was narrowest, a cell or two beside where the deck went
            // (square across): led by that, miners walked off the bank into the river beside the bridge.
            if (w.wasCut && !w.relined) {
                w.relined = true;
                List<int[]> dry = route(level, v.centre(), w.site, false);
                if (dry != null) w.line = dry;
            }
            if (w.wasCut && !w.open) {
                w.open = true;
                w.openedAt = level.getGameTime();
                BRIDGED.merge(id, 1, Integer::sum);
                Villages.tell(id, level.getDayTime() / 24000L, "the way to the mine is open on foot, over the new bridge");
                LOG.info("[MCA-MINEWAY] {}: the way to the mine is open ({} ticks after it was found cut off)", Villages.name(id), w.cutAt < 0 ? -1 : w.openedAt - w.cutAt);
            }
            return;
        }
        if (w.gaveUp) return;
        // Bridged already (by this town, on an earlier look, or its road gang): the water was what stood between, and it is
        // crossed; what else the flood finds (the pithead's own diggings) the miners know their way round.
        if (w.plans.isEmpty() && w.wasCut) {
            w.walkable = true;
            if (!w.open) { w.open = true; w.openedAt = level.getGameTime(); BRIDGED.merge(id, 1, Integer::sum); }
            return;
        }
        if (w.plans.isEmpty()) {
            // The cheapest way over: the walk from the heart to the pithead that crosses the least water (a river crossed
            // once on a short walk round, rather than four times on the straight line); the straight line if that cannot be had.
            List<int[]> line = route(level, v.centre(), w.site);
            boolean routed = line != null;
            if (line == null) line = Roads.bresenham(v.centre().getX(), v.centre().getZ(), w.site.getX(), w.site.getZ());
            w.line = routed ? line : null;
            List<BridgeKit.Plan> drawn = new ArrayList<>();
            // [tp46] Along the cheapest walk (an old bridge's deck on it is ground, not water to bridge again), each stretch of
            // open water, a lake's islands and all, is one crossing, its deck low over the water.
            List<int[]> runs = routed ? openRuns(level, line) : waterRuns(level, line);
            for (int[] run : runs) {
                BridgeKit.Span s = routed && run[0] >= 1 && run[0] + run[1] < line.size()
                    ? BridgeKit.spanOver(level, id, ROAD, line, run[0] - 1, run[0] + run[1], 1, RISE)
                    : BridgeKit.spanAt(level, id, ROAD, line, run[0] + run[1] / 2, 1);
                if (s == null) continue;
                // The cheapest that serves: planks through (no log beams to wait on), unless the town drew it in timber already.
                String fam = "b18.fam/" + ROAD + "/" + s.a() + "/w";
                if (com.jrpetty.mcassistant.village.Ledger.note(id, fam) == null || com.jrpetty.mcassistant.village.Ledger.note(id, fam).isEmpty())
                    com.jrpetty.mcassistant.village.Ledger.note(id, fam, BridgeKit.PLANKS.name());
                BridgeKit.Plan p = BridgeKit.plan(level, v, id, ROAD, line, s, false, false);
                if (p != null && !drawn.contains(p)) drawn.add(p);
            }
            // [tp46] And each bank on it too steep to walk (a cliff at an old bridge's end, a bank high over a river): a stair cut
            // up it in cobblestone, the cut stone back into the stores.
            List<BridgeKit.Plan> stairs = new ArrayList<>();
            java.util.Set<Long> cut = new java.util.HashSet<>();               // the ground a stair already climbs
            if (routed) {
                for (int i = 0; i + 1 < line.size(); i++) {
                    int[] a = line.get(i), b = line.get(i + 1);
                    if (cut.contains(BlockPos.asLong(a[0], 0, a[1])) || cut.contains(BlockPos.asLong(b[0], 0, b[1]))) continue;
                    BlockPos pa = Visitors.surface(level, new BlockPos(a[0], 0, a[1])), pb = Visitors.surface(level, new BlockPos(b[0], 0, b[1]));
                    if (Math.abs(pa.getY() - pb.getY()) <= 1 || wet(level, pa) || wet(level, pb)) continue;
                    BlockPos low = pa.getY() < pb.getY() ? pa : pb, high = low == pa ? pb : pa;
                    Direction up = Direction.getNearest(high.getX() - low.getX(), 0, high.getZ() - low.getZ());
                    String key = ROAD + "/stair/" + low.getX() + "," + low.getZ();
                    BridgeKit.Plan st = BridgeKit.bankStair(level, key, low, up, 1, BridgeKit.COBBLE);
                    if (st == null) continue;
                    for (BridgeKit.Piece pc : st.pieces) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                        cut.add(BlockPos.asLong(pc.pos().getX() + dx, 0, pc.pos().getZ() + dz));
                    stairs.add(st);
                    w.stands.put(key, low);
                }
            }
            // Every deck across first, nearest the town first, and only then the railings, end posts and lights: a town with
            // four rivers between it and its mine had its first bridge's railings waiting on planks while the other three
            // decks waited on them (and its miners on all four).
            List<BridgeKit.Plan> finish = new ArrayList<>();
            for (BridgeKit.Plan p : drawn) {
                List<BridgeKit.Piece> deck = new ArrayList<>(), rest = new ArrayList<>();
                for (BridgeKit.Piece pc : p.pieces) ("REQ".indexOf(pc.kind()) >= 0 ? rest : deck).add(pc);
                BridgeKit.Plan d = new BridgeKit.Plan(p.key, p.shape, p.family, deck);
                d.purse = w.purse;
                w.plans.add(d);
                if (!rest.isEmpty()) {
                    BridgeKit.Plan r = new BridgeKit.Plan(p.key, p.shape, p.family, rest);
                    r.purse = w.purse;
                    finish.add(r);
                }
            }
            w.plans.addAll(stairs);
            w.plans.addAll(finish);
            w.crossings = drawn.size();
            w.stairs = stairs.size();
            if (w.plans.isEmpty()) {
                if (was) LOG.info("[MCA-MINEWAY] {}: no way on foot to the mine at {}, and no water on the way to bridge", Villages.name(id), w.site.toShortString());
                w.gaveUp = true;
                return;
            }
            w.wasCut = true;
            if (w.cutAt < 0) w.cutAt = level.getGameTime();
            Villages.tell(id, level.getDayTime() / 24000L, "the mine is cut off from the town" + (w.crossings > 0 ? " by water: the town is to bridge it" : ": the town is to cut a stair up the bank")
                + " before its miners go out to it");
            LOG.info("[MCA-MINEWAY] {}: the mine at {} cut off; {} crossing(s) to bridge, {} stair(s) to cut ({})", Villages.name(id), w.site.toShortString(), w.crossings, w.stairs,
                routed ? "along the cheapest walk" : "on the straight line");
        } else if (allDown(level, w)) {
            // All of it down, and the flood still finds no way (a cliff past the river, the pithead dug about, a ramp onto
            // an island between two spans): the water is crossed, and that is what the town could do; the miners go as they
            // always did.
            w.walkable = true;
            w.plans.clear();
            if (!w.open) { w.open = true; w.openedAt = level.getGameTime(); BRIDGED.merge(id, 1, Integer::sum); }
            LOG.info("[MCA-MINEWAY] {}: the bridge is down (the flood finds no way still, stopping at {}: left to the miners)", Villages.name(id), floodEnd);
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
            BlockPos stand = w.stands.get(p.key);
            boolean stair = stand != null;
            if (stand == null) {
                // The gang stands on the bank at the town's end of it.
                BlockPos a = dry(level, s, s.w0() - 2, -1), b = dry(level, s, s.w1() + 2, 1);
                stand = a.distSqr(v.centre()) <= b.distSqr(v.centre()) ? a : b;
            }
            // [tp46] The bridge's planks bought from the traders for it, ahead of the hand (what the stores and their logs cannot
            // run to, over and above what the town's other works use): a town short of planks had its bridge "waiting for planks"
            // for two days while its houses and carts took every plank that came in.
            if (p.family().wood()) buyPlanks(level, v, w);
            if (!TownJobs.atWork(level, v, "mineway", stand, stair ? "cutting the stair on the way to the mine" : "building the bridge on the way to the mine",
                    AssistantEntity.StationTask.HAUL)) {
                VillageFolkEntity hand = TownJobs.handAt(level, v.id(), "mineway");
                long t = level.getDayTime() % 24000L;
                w.status = "waiting for a hand" + (t >= 12500L && t < 23500L ? " (by day)" : hand == null ? " (none to spare)"
                    : " (" + hand.displayNameCap() + " " + (int) Math.sqrt(hand.blockPosition().distSqr(stand)) + " off, at " + hand.blockPosition().toShortString()
                    + " for " + stand.toShortString() + ")");     // [tp46] the census
                return;
            }
            String out = BridgeKit.lay(level, v, p, stair ? STAIR_PER_VISIT : PER_VISIT, false);
            if (out == null) {
                boolean deck = false;
                for (BridgeKit.Piece pc : p.pieces) if (pc.kind() == 'D') { deck = true; break; }
                w.status = "";
                w.looked = -1;                                                 // looked at again at once
                String what = stair ? "stair was cut up the bank" : deck ? "deck was laid across the water" : "railings and lights were finished";
                Villages.tell(v.id(), level.getDayTime() / 24000L, stair ? "a " + p.family().name().toLowerCase() + " " + what + " on the way to the mine"
                    : "a " + p.family().name().toLowerCase() + " bridge's " + what + " on the way to the mine");
                LOG.info("[MCA-MINEWAY] {}: a {} {} on the way to the mine", Villages.name(v.id()), p.family().name(), stair ? what : "bridge's " + what);
            } else {
                w.status = BridgeKit.MORE.equals(out) ? "building" : "waiting for " + out;
            }
            return;
        }
        w.plans.clear();                                                    // all of it down
        w.looked = -1;
    }

    // ------------------------------------------------------------------ the look

    /** [tp46] Is the ground's top here water (the heightmap's: an old bridge's deck over it is ground)? */
    private static boolean wet(ServerLevel level, BlockPos surface) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, surface.getX(), surface.getZ());
        return level.getFluidState(new BlockPos(surface.getX(), y - 1, surface.getZ())).is(net.minecraft.tags.FluidTags.WATER);
    }

    /**
     * [tp46] The runs of open water on the walk (an old bridge's deck over the water is ground), beyond the first few steps,
     * three wide or more, with runs no more than {@link #ISLAND} of land apart (a lake's islands) taken as one: {start, length}.
     */
    static List<int[]> openRuns(ServerLevel level, List<int[]> line) {
        List<int[]> raw = new ArrayList<>();
        int run = 0, first = -1;
        for (int i = 0; i <= line.size(); i++) {
            boolean w = i < line.size() && i >= 2 && wet(level, new BlockPos(line.get(i)[0], 0, line.get(i)[1]));
            if (w) { if (run == 0) first = i; run++; continue; }
            if (run > 0) raw.add(new int[]{ first, run });
            run = 0;
        }
        List<int[]> out = new ArrayList<>();
        for (int[] r : raw) {
            int[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && r[0] - (last[0] + last[1]) <= ISLAND) last[1] = r[0] + r[1] - last[0];
            else out.add(new int[]{ r[0], r[1] });
        }
        out.removeIf(r -> r[1] < LEAST_WIDE);
        return out;
    }

    /**
     * [tp46] Planks for the way's bridges, bought from the traders into the way's own purse when the stores (with their logs
     * sawn) cannot run to what is left to lay: never more than is wanted, at most a few lots a visit and a visit every half
     * minute (Market.importFor: the town's price, a quarter of what the treasury has free).
     */
    private static void buyPlanks(ServerLevel level, Villages.Village v, Way w) {
        long now = level.getGameTime();
        if (now - w.boughtAt < BUY_EVERY && now >= w.boughtAt) return;
        w.boughtAt = now;
        int want = 0;
        for (BridgeKit.Plan p : w.plans) want += BridgeKit.planksWanted(level, p);
        int have = w.purse[0] + Market.stock(level, v.id(), st -> st.is(net.minecraft.tags.ItemTags.PLANKS))
            + 4 * Market.stock(level, v.id(), st -> st.is(net.minecraft.tags.ItemTags.LOGS)) / 2;   // (half the logs: the houses want theirs)
        int shortBy = want - have;
        if (shortBy <= 0) return;
        int got = Market.importFor(level, v, level.getDayTime() / 24000L, net.minecraft.world.item.Items.SPRUCE_PLANKS, shortBy, "the bridge on the way to the mine", false);
        if (got > 0) {
            w.purse[0] += got;
            LOG.info("[MCA-MINEWAY] {}: bought {} planks for the bridge on the way to the mine ({} wanted, {} to hand)", Villages.name(v.id()), got, want, have);
        }
    }

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
        return route(level, from, to, true);
    }

    /** As above; {@code build} false: the walk as the ground stands (no water crossed, no bank climbed), for a way already open. */
    @Nullable
    static List<int[]> route(ServerLevel level, BlockPos from, BlockPos to, boolean build) {
        int x0 = Math.min(from.getX(), to.getX()) - MARGIN, x1 = Math.max(from.getX(), to.getX()) + MARGIN;
        int z0 = Math.min(from.getZ(), to.getZ()) - MARGIN, z1 = Math.max(from.getZ(), to.getZ()) + MARGIN;
        int w = x1 - x0 + 1, h = z1 - z0 + 1;
        if ((long) w * h > 400L * 400L) return null;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) if (!level.hasChunk(cx, cz)) return null;
        int n = w * h;
        int[] top = new int[n];
        byte[] kind = new byte[n];                                       // 0 land, 1 water, 2 neither (lava, a fence, a wall)
        boolean[] earth = new boolean[n];                                // [tp46] the world's own ground (a bank a stair may be cut up)
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
                else earth[i * h + k] = BridgeKit.earth(st);
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
                int nd;
                if (!build && (kind[nb] != 0 || dy > 1)) continue;
                if (dy <= (kind[nb] == 1 || kind[c] == 1 ? 2 : 1)) nd = dist[c] + (kind[nb] == 1 ? WATER_COST : 1);
                // [tp46] A bank of the world's own ground too steep to walk: a stair cut up it, at a cost a block of height.
                else if (kind[nb] == 0 && kind[c] == 0 && dy <= MOST_CLIMB && (top[nb] > top[c] ? earth[nb] : earth[c])) nd = dist[c] + CLIMB_COST * dy;
                else continue;
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
