package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [city9] The streets a town can really walk: a lot is only built on once its street joins the rest.
 *
 * <p>The town plan (village/TownPlan) is drawn on paper: its streets cross a lake or run up a cliff as happily as over a
 * meadow, and its lots lie wherever the arithmetic puts them. The builders used to put a house on any lot that was dry
 * and flat enough, so a town on a lakeshore had a row of houses on the far bank, a street's length of water from the
 * square, with no way to them but to swim. A real town grows along its streets: the next house goes up where the street
 * already runs.
 *
 * <p>So the plan's streets and the square are walked from the heart the way a folk walks them (a block up, three down,
 * never through water: a bridge or a causeway the town lays carries the street on), and a lot is offered to a building
 * only if the street before its door is reached ({@link #reaches}). A town with nothing else left (an island) takes one
 * of the rest after twelve looks in a row that found nothing ({@link #hungry}), and is particular again after it. Worked out again every two minutes at most, and only when a
 * building is being sited: a few thousand columns, never on a folk's tick.
 */
public final class CityPlan {

    private CityPlan() {}

    /** How far out the walk goes: the plan's last street (of its first three rings; [city26] a grown town's walk runs out
     *  over the rings it has laid out since, {@link #radius}). */
    static final int R = TownPlan.reach() + TownPlan.STREET + 1;

    /** [city26] How far out this town's walk goes: the last street of the rings its plan has laid out. */
    static int radius(UUID village) {
        return Math.max(R, CityGrowth.reach(village) + TownPlan.STREET + 1);
    }
    /** Worked out again at most this often (ticks). */
    private static final long EVERY = 2400L;
    /** A step a folk takes: a block up (a jump; two where the folk cut a step into a bank, StuckSpots), three down. */
    private static final int UP = 2, DOWN = 3;
    /** No height known yet (a cell not walked), or ground not loaded (taken as walkable: nothing is known against it). */
    static final int NONE = Integer.MIN_VALUE;

    /** A town's walked streets: the floor height of each reached cell, NONE where not reached; {@code r} how far out. */
    record Net(long at, int[] floor, int reached, int cells, int r) {
        boolean has(int dx, int dz) {
            if (Math.abs(dx) > r || Math.abs(dz) > r) return false;
            return floor[(dx + r) * (2 * r + 1) + (dz + r)] != NONE;
        }

        /** The floor at this offset (NONE if not reached or out of the walk). */
        int at(int dx, int dz) {
            if (Math.abs(dx) > r || Math.abs(dz) > r) return NONE;
            return floor[(dx + r) * (2 * r + 1) + (dz + r)];
        }
    }

    private static final Map<UUID, Net> NETS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        NETS.clear();
        DRY.clear();
        SERVED.clear();
        HUNGRY.clear();
        CityCrossings.resetForTests();
    }

    /** Forget a town's walk, so the next look walks it afresh (a test that changed the ground). */
    public static void forget(UUID village) {
        NETS.remove(village);
        DRY.remove(village);
    }

    /** Looks in a row that found nothing but lots off the streets, by town: twelve, and the next look takes one of them. */
    private static final Map<UUID, Integer> HUNGRY = new ConcurrentHashMap<>();
    static final int PATIENCE = 12;

    /** Has this town gone so many looks with nothing on its streets that it may take a lot off them (an island)? */
    public static boolean hungry(UUID village) {
        return HUNGRY.getOrDefault(village, 0) >= PATIENCE;
    }

    /** A look for a lot is over: found one (the count starts again), or found none with some passed over for their street. */
    public static void looked(UUID village, boolean found, boolean passedOver) {
        if (found) HUNGRY.remove(village);
        else if (passedOver) HUNGRY.merge(village, 1, Integer::sum);
    }

    /** Is this offset one the walk goes over: the square, or a street of the plan? */
    static boolean walkway(int dx, int dz) {
        return TownPlan.isSquare(dx, dz) || TownPlan.isStreet(dx, dz);
    }

    /** The town's walk, worked out now if it is older than two minutes. */
    static Net net(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        Net n = NETS.get(v.id());
        if (n != null && now - n.at() < EVERY && now >= n.at() && n.r() == radius(v.id())) return n;
        n = walk(level, v.centre(), now, UP, DOWN, radius(v.id()));
        NETS.put(v.id(), n);
        return n;
    }

    /** [fixes14] The town's walk with steps cut up and down its banks (four up, six down), never over water. */
    private static final Map<UUID, Net> DRY = new ConcurrentHashMap<>();

    static Net dryNet(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        Net n = DRY.get(v.id());
        if (n != null && now - n.at() < EVERY && now >= n.at() && n.r() == radius(v.id())) return n;
        n = walk(level, v.centre(), now, 4, 6, radius(v.id()));
        DRY.put(v.id(), n);
        return n;
    }

    /**
     * [fixes14] Has the town gone a look or more with lots passed over for want of a street to them (the crossings
     * over its water are laid then: CityCrossings)?
     */
    public static boolean wanting(UUID village) {
        return HUNGRY.getOrDefault(village, 0) > 0;
    }

    /**
     * [fixes14] May a town with nothing on its streets take this lot: its street reached on foot with steps cut into the
     * banks (a mountain top, a terrace up from the town), but never across water. A lot over the water waits for its
     * street's crossing to be laid (CityCrossings): the town grew on the far bank of a lake, a street's length of water
     * from the square, the folk swimming to it.
     */
    public static boolean reachesDry(ServerLevel level, Villages.Village v, TownPlan.Lot lot) {
        int[] f = front(lot);
        if (f == null) return true;
        return reached(dryNet(level, v), f, lot.back());
    }

    /**
     * The square and the streets walked from the heart: each cell's floor is found within a step of the cell it was
     * reached from (an arch over a gate or a lamp's post is not a floor; water is never one).
     */
    static Net walk(ServerLevel level, BlockPos heart, long now) {
        return walk(level, heart, now, UP, DOWN, R);
    }

    static Net walk(ServerLevel level, BlockPos heart, long now, int up, int down, int R) {
        final int SIDE = 2 * R + 1;
        int[] floor = new int[SIDE * SIDE];
        java.util.Arrays.fill(floor, NONE);
        int cells = 0;
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) if (walkway(dx, dz)) cells++;
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        int start = floorNear(level, heart.getX(), heart.getZ(), heart.getY() - 1, 3, 4);
        // [econ32b] The heart's height as it was kept, buried under the square laid since: the floor on top of the ground
        // there. A river town's heart was kept at seventy-nine with its square paved at eighty-seven; the walk began in the
        // rock under the square, reached nothing, and every lot off the square was "a street not walked" for good: a Diamond
        // Age town of fifty never found a lot for its lighthouse or its chapel.
        if ((start == NONE || start == WET) && level.hasChunk(heart.getX() >> 4, heart.getZ() >> 4)) {
            int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, heart.getX(), heart.getZ()) - 1;
            int f = floorNear(level, heart.getX(), heart.getZ(), top, 1, 2);
            if (f != NONE && f != WET) start = f;
        }
        if (start == NONE || start == WET) start = heart.getY() - 1;
        floor[R * SIDE + R] = start;
        queue.add(new int[]{ 0, 0 });
        int reached = 1;
        int[][] steps = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            int h = floor[(c[0] + R) * SIDE + (c[1] + R)];
            for (int[] s : steps) {
                int nx = c[0] + s[0], nz = c[1] + s[1];
                if (Math.abs(nx) > R || Math.abs(nz) > R || !walkway(nx, nz)) continue;
                int i = (nx + R) * SIDE + (nz + R);
                if (floor[i] != NONE) continue;
                int f = floorNear(level, heart.getX() + nx, heart.getZ() + nz, h, up, down);
                if (f == NONE || f == WET) continue;
                floor[i] = f;
                reached++;
                queue.add(new int[]{ nx, nz });
            }
        }
        return new Net(now, floor, reached, cells, R);
    }

    static final int WET = Integer.MIN_VALUE + 1;

    /**
     * The floor a folk standing at height {@code h} (the block it stands on) would step onto in this column: the highest
     * solid block from {@code up} above to {@code down} below with two blocks of room over it. WET if water or lava comes
     * first, NONE if there is no such floor. Ground not loaded is taken as level with the step before.
     */
    static int floorNear(ServerLevel level, int x, int z, int h, int up, int down) {
        if (!level.hasChunk(x >> 4, z >> 4)) return h;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = h + up; y >= h - down; y--) {
            p.set(x, y, z);
            BlockState st = level.getBlockState(p);
            if (!st.getFluidState().isEmpty() && !st.blocksMotion()) {
                if (room(level, p.set(x, y + 1, z)) && room(level, p.set(x, y + 2, z))) return WET;
                continue;
            }
            if (!st.blocksMotion() || opens(st)) continue;
            if (room(level, p.set(x, y + 1, z)) && room(level, p.set(x, y + 2, z))) return y;
        }
        return NONE;
    }

    private static boolean room(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return (!st.blocksMotion() || opens(st)) && st.getFluidState().isEmpty();
    }

    /** A gate or a wooden door: a folk opens it and walks through (a gate shut for the night is still a way). */
    static boolean opens(BlockState st) {
        return st.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock
            || (st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && !st.is(net.minecraft.world.level.block.Blocks.IRON_DOOR));
    }

    /** The plan cell straight out from a lot's door (on its street), as an offset from the heart; null for a square spot. */
    static int[] front(TownPlan.Lot lot) {
        if (lot.kind() == TownPlan.Kind.SQUARE) return null;
        int reach = (lot.kind() == TownPlan.Kind.LONG ? lot.halfDeep() : TownPlan.LOT / 2) + 1;
        return switch (lot.back()) {
            case TownPlan.NORTH -> new int[]{ lot.x(), lot.z() + reach };
            case TownPlan.SOUTH -> new int[]{ lot.x(), lot.z() - reach };
            case TownPlan.EAST -> new int[]{ lot.x() - reach, lot.z() };
            default -> new int[]{ lot.x() + reach, lot.z() };
        };
    }

    /** Is the street before this lot's door reached from the heart (any of the cells a step or two either side of the
     *  middle of its front, so a lamp post there does not cut it off)? A spot on the square always is. */
    public static boolean reaches(ServerLevel level, Villages.Village v, TownPlan.Lot lot) {
        int[] f = front(lot);
        if (f == null) return true;
        return reached(net(level, v), f, lot.back());
    }

    private static boolean reached(Net n, int[] f, int back) {
        boolean alongX = back == TownPlan.NORTH || back == TownPlan.SOUTH;     // the front runs along x
        for (int k = -2; k <= 2; k++) {
            int x = alongX ? f[0] + k : f[0], z = alongX ? f[1] : f[1] + k;
            if (n.has(x, z)) return true;
        }
        return false;
    }

    /** [econ32b] The walk in words, for the sitewhy log: cells reached of the streets, by distance out (in twenties), and where
     *  the walk stopped short on the plan's streets (the first unreached street cell next to a reached one, a few of them, with
     *  what stands there). */
    static String walkWords(ServerLevel level, Villages.Village v) {
        Net n = net(level, v);
        int bands = n.r() / 20 + 1;
        int[] have = new int[bands], all = new int[bands];
        StringBuilder stops = new StringBuilder();
        int shown = 0;
        for (int dx = -n.r(); dx <= n.r(); dx++) {
            for (int dz = -n.r(); dz <= n.r(); dz++) {
                if (!walkway(dx, dz)) continue;
                int b = Math.max(Math.abs(dx), Math.abs(dz)) / 20;
                all[b]++;
                if (n.has(dx, dz)) { have[b]++; continue; }
                if (shown >= 6) continue;
                int[][] steps = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
                for (int[] st : steps) {
                    int h = n.at(dx + st[0], dz + st[1]);
                    if (h == NONE) continue;
                    int x = v.centre().getX() + dx, z = v.centre().getZ() + dz;
                    int f = floorNear(level, x, z, h, UP, DOWN);
                    stops.append(" [").append(dx).append(',').append(dz).append(" from h").append(h).append(": ")
                        .append(f == WET ? "water" : f == NONE ? "no floor (" + level.getBlockState(new BlockPos(x, h + 1, z)).getBlock().getDescriptionId()
                            .replace("block.minecraft.", "") + "/" + level.getBlockState(new BlockPos(x, h + 2, z)).getBlock().getDescriptionId()
                            .replace("block.minecraft.", "") + ", ground " + level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + ")" : "h" + f).append(']');
                    shown++;
                    break;
                }
            }
        }
        StringBuilder sb = new StringBuilder("walk " + n.reached() + " of " + n.cells() + " cells; by twenties out:");
        for (int b = 0; b < bands; b++) sb.append(' ').append(have[b]).append('/').append(all[b]);
        return sb.append("; stops:").append(stops).toString();
    }

    // ------------------------------------------------------------------ the streets laid as the town comes

    /** A side street within this of a building (a lot and a street and a lot: the next block out) is laid. */
    static final int SERVE = TownPlan.PERIOD;

    /** Each town's buildings' and sites' anchors, as offsets, and when they were looked at. */
    private record Served(long at, int[] xs, int[] zs) {}

    private static final Map<UUID, Served> SERVED = new ConcurrentHashMap<>();

    /**
     * Is a side street's cell here by the built-up town: a building standing or going up within a block of it? Looked
     * at afresh every half minute at most.
     */
    public static boolean served(Villages.Village v, int dx, int dz, long now) {
        Served s = SERVED.get(v.id());
        if (s == null || now - s.at() > 600L || now < s.at()) {
            java.util.List<BlockPos> at = new java.util.ArrayList<>();
            for (Ledger.Building b : Ledger.buildings(v.id())) at.add(b.anchor());
            for (Villages.Site site : Villages.sitesOf(v.id()).values()) at.add(site.anchor());
            int[] xs = new int[at.size()], zs = new int[at.size()];
            for (int i = 0; i < at.size(); i++) {
                xs[i] = at.get(i).getX() - v.centre().getX();
                zs[i] = at.get(i).getZ() - v.centre().getZ();
            }
            s = new Served(now, xs, zs);
            SERVED.put(v.id(), s);
        }
        for (int i = 0; i < s.xs().length; i++) {
            if (Math.abs(s.xs()[i] - dx) <= SERVE && Math.abs(s.zs()[i] - dz) <= SERVE) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the audit's view

    /** How a town's buildings stand to its streets: how many plan buildings, how many with their street reached, and
     *  how many of the plan's street cells within the town's reach are walked. For the audit and the tests. */
    public record Report(int planned, int onStreets, int streetCells, int walked) {}

    public static Report report(ServerLevel level, Villages.Village v) {
        NETS.remove(v.id());
        Net n = net(level, v);
        int planned = 0, on = 0;
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            TownPlan.Lot lot = lotOf(v, b);
            if (lot == null || lot.kind() == TownPlan.Kind.SQUARE) continue;
            planned++;
            if (reached(n, front(lot), lot.back())) on++;
        }
        int reach = Villages.townReach(v.id());
        int cells = 0, walked = 0;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (!TownPlan.isStreet(dx, dz)) continue;
                cells++;
                if (n.has(dx, dz)) walked++;
            }
        }
        return new Report(planned, on, cells, walked);
    }

    /** The plan lot a building stands on: the one whose middle is at its anchor (null if none: off the plan). */
    public static TownPlan.Lot lotOf(Villages.Village v, Ledger.Building b) {
        int dx = b.anchor().getX() - v.centre().getX(), dz = b.anchor().getZ() - v.centre().getZ();
        for (TownPlan.Lot l : TownPlan.lots()) if (l.x() == dx && l.z() == dz) return l;
        return null;
    }
}
