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
 * only if the street before its door is reached ({@link #reaches}). A town with nothing else left (an island) is let
 * onto the rest after a few looks (Villages.siteFor). Worked out again every two minutes at most, and only when a
 * building is being sited: a few thousand columns, never on a folk's tick.
 */
public final class CityPlan {

    private CityPlan() {}

    /** How far out the walk goes: the plan's last street. */
    static final int R = TownPlan.reach() + TownPlan.STREET + 1;
    private static final int SIDE = 2 * R + 1;
    /** Worked out again at most this often (ticks). */
    private static final long EVERY = 2400L;
    /** A step a folk takes: a block up (a jump), three down. */
    private static final int UP = 1, DOWN = 3;
    /** No height known yet (a cell not walked), or ground not loaded (taken as walkable: nothing is known against it). */
    private static final int NONE = Integer.MIN_VALUE;

    /** A town's walked streets: the floor height of each reached cell, NONE where not reached. */
    record Net(long at, int[] floor, int reached, int cells) {
        boolean has(int dx, int dz) {
            if (Math.abs(dx) > R || Math.abs(dz) > R) return false;
            return floor[(dx + R) * SIDE + (dz + R)] != NONE;
        }
    }

    private static final Map<UUID, Net> NETS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        NETS.clear();
        SERVED.clear();
    }

    /** Forget a town's walk, so the next look walks it afresh (a test that changed the ground). */
    public static void forget(UUID village) {
        NETS.remove(village);
    }

    /** Is this offset one the walk goes over: the square, or a street of the plan? */
    static boolean walkway(int dx, int dz) {
        return TownPlan.isSquare(dx, dz) || TownPlan.isStreet(dx, dz);
    }

    /** The town's walk, worked out now if it is older than two minutes. */
    static Net net(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        Net n = NETS.get(v.id());
        if (n != null && now - n.at() < EVERY && now >= n.at()) return n;
        n = walk(level, v.centre(), now);
        NETS.put(v.id(), n);
        return n;
    }

    /**
     * The square and the streets walked from the heart: each cell's floor is found within a step of the cell it was
     * reached from (an arch over a gate or a lamp's post is not a floor; water is never one).
     */
    static Net walk(ServerLevel level, BlockPos heart, long now) {
        int[] floor = new int[SIDE * SIDE];
        java.util.Arrays.fill(floor, NONE);
        int cells = 0;
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) if (walkway(dx, dz)) cells++;
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        int start = floorNear(level, heart.getX(), heart.getZ(), heart.getY() - 1, 3, 4);
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
                int f = floorNear(level, heart.getX() + nx, heart.getZ() + nz, h, UP, DOWN);
                if (f == NONE || f == WET) continue;
                floor[i] = f;
                reached++;
                queue.add(new int[]{ nx, nz });
            }
        }
        return new Net(now, floor, reached, cells);
    }

    private static final int WET = Integer.MIN_VALUE + 1;

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
            if (!st.blocksMotion()) continue;
            if (room(level, p.set(x, y + 1, z)) && room(level, p.set(x, y + 2, z))) return y;
        }
        return NONE;
    }

    private static boolean room(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return !st.blocksMotion() && st.getFluidState().isEmpty();
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
