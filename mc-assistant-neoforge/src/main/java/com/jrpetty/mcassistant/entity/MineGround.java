package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [mine7] The mine's ground: set aside for the town's mine, and for nothing else, for good.
 *
 * <p>When the town's mine is opened (TownMine.site), the whole of the ground it may spread over is set aside with it:
 * every face it may work, out to its last ring (TownMine.MOST_RING), that lies clear of the town's own ground as it
 * will grow (TownDigs: its whole plan, its farmland, its buildings, its roads), with the portal over each staircase,
 * and the way out to it from the edge of the town's plan, each with a margin. It is kept with the world (the ledger's
 * "mine.faces" and "mine.ground"). Nothing the town plans goes on it: no lot (houses, flats, firms, halls), no field, no
 * wall, no garden, lamp or seat, no colony, no land title and no sale; the gate every block goes through
 * (TownBlocks) lets only the mine's own work, the way to it, the ground work and what nature does onto it. The mine is
 * never moved because the town has come up to it: it works its own faces however the town grows round it.
 *
 * <p>A mine of an older world (opened before this, perhaps in what is now the town) is set aside as it stands on the
 * first look after a load: its first face, the faces worked or worked out, the faces its stairs go down from, and the
 * faces round it still clear of the town. Whatever the town had already built on that ground stays as it is; nothing
 * new goes on it.
 */
public final class MineGround {

    private MineGround() {}

    /** The margin kept round each face and round the way to the mine. */
    public static final int MARGIN = 3;
    /** Half the width of the way kept to the mine (a path of two, and a block each side). */
    public static final int WAY_HALF = 2;

    /** Each village's ground, as rects {x0, z0, x1, z1} (inclusive), and their bounds first. Read from the ledger once. */
    private static final Map<UUID, int[][]> GROUND = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<Long>> FACES = new ConcurrentHashMap<>();

    /** Forget what was read from the ledger (tests, a load). */
    public static void forgetCache() {
        GROUND.clear();
        FACES.clear();
    }

    // ------------------------------------------------------------------ setting it aside

    /** Has this village's mine its ground set aside? */
    public static boolean has(UUID village) {
        return ground(village).length > 0;
    }

    /**
     * The mine's ground set aside now, round its first face (`site`), for good. A new mine: every face out to the last
     * ring clear of the town's ground. An older world's (`older`): those, and its first face, the faces worked or worked
     * out and the faces its stairs go down from, wherever they lie. The way from the edge of the plan to it, beyond the
     * plan. Faces set aside.
     */
    public static int reserve(ServerLevel level, UUID village, BlockPos heart, BlockPos site, boolean older) {
        Set<Long> faces = new HashSet<>();
        int r = TownMine.PITCH / 2;
        for (int i = -TownMine.MOST_RING; i <= TownMine.MOST_RING; i++) {
            for (int j = -TownMine.MOST_RING; j <= TownMine.MOST_RING; j++) {
                BlockPos c = new BlockPos(site.getX() + i * TownMine.PITCH, 0, site.getZ() + j * TownMine.PITCH);
                if (TownDigs.inTownGroundRaw(level, village, heart, c, r + MARGIN)) continue;
                if (Bonds.overBorder(village, heart, c, r)) continue;
                if (otherMeets(village, c.getX() - r - MARGIN, c.getZ() - r - MARGIN, c.getX() + r + MARGIN, c.getZ() + r + MARGIN)) continue;
                if (MineSites.meetsOld(village, c.getX() - r - MARGIN, c.getZ() - r - MARGIN, c.getX() + r + MARGIN, c.getZ() + r + MARGIN)) continue;   // [econ32]
                faces.add(key(i, j));
            }
        }
        if (older) {
            faces.add(key(0, 0));
            for (int[] c : TownMine.spentFaces(village)) faces.add(key(c[0], c[1]));
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (a.stationTask() != AssistantEntity.StationTask.MINE || a.workZone() == null) continue;
                int[] c = TownMine.faceIndex(site, a.workZone().center());
                if (c != null) faces.add(key(c[0], c[1]));
            }
            for (long k : MineStairs.keys(level)) {
                BlockPos head = MineStairs.head(level, k);
                UUID by = MineStairs.owner(level, k);
                if (head == null || (by == null ? !TownDigs.cutByOurMiner(village, k) : !by.equals(village))) continue;
                int[] c = TownMine.faceIndex(site, head);
                if (c != null) faces.add(key(c[0], c[1]));
            }
        } else {
            faces.add(key(0, 0));                                          // its first face: chosen clear of the town
        }
        List<int[]> rects = new ArrayList<>();
        StringBuilder fs = new StringBuilder();
        for (long k : faces) {
            int i = (int) (k >> 32), j = (int) k;
            int x = site.getX() + i * TownMine.PITCH, z = site.getZ() + j * TownMine.PITCH;
            rects.add(new int[]{ x - r - MARGIN, z - r - MARGIN, x + r + MARGIN, z + r + MARGIN });
            if (fs.length() > 0) fs.append(';');
            fs.append(i).append(':').append(j);
        }
        // The way to it: from the edge of the town's whole plan out to the first face, beyond the plan only (inside it the
        // town's own streets lead out).
        double far = Math.sqrt(site.atY(0).distSqr(heart.atY(0)));
        if (far > 1) {
            double ux = (site.getX() - heart.getX()) / far, uz = (site.getZ() - heart.getZ()) / far;
            for (int d = TownDigs.PLAN_REACH + TownDigs.MARGIN + 1; d < far; d += 4) {
                int x = heart.getX() + (int) Math.round(ux * d), z = heart.getZ() + (int) Math.round(uz * d);
                int cheb = Math.max(Math.abs(x - heart.getX()), Math.abs(z - heart.getZ()));
                if (cheb <= TownDigs.PLAN_REACH + TownDigs.MARGIN) continue;
                rects.add(new int[]{ x - WAY_HALF - 2, z - WAY_HALF - 2, x + WAY_HALF + 2, z + WAY_HALF + 2 });
            }
        }
        StringBuilder gs = new StringBuilder();
        for (int[] q : rects) {
            if (gs.length() > 0) gs.append(';');
            gs.append(q[0]).append(',').append(q[1]).append(',').append(q[2]).append(',').append(q[3]);
        }
        Ledger.note(village, "mine.faces", fs.toString());
        Ledger.note(village, "mine.ground", gs.toString());
        GROUND.remove(village);
        FACES.remove(village);
        long day = level.getDayTime() / 24000L;
        Villages.tell(village, day, "the ground for the town's mine was set aside for good: " + faces.size() + " faces round "
            + site.getX() + ", " + site.getZ() + " and the way out to it; nothing else is to be built there");
        com.mojang.logging.LogUtils.getLogger().info("[MCA-MINE] {}: the mine's ground set aside, {} faces round {}, {} ({})",
            Villages.name(village), faces.size(), site.getX(), site.getZ(), older ? "an older world's mine" : "a new mine");
        return faces.size();
    }

    /** [growth24] The ring of the farthest face set aside (faces round the first, as grid places), or -1 for none. */
    public static int ring(UUID village) {
        int most = -1;
        for (long k : faces(village)) most = Math.max(most, Math.max(Math.abs((int) (k >> 32)), Math.abs((int) k)));
        return most;
    }

    /** [growth24] These faces (grid places round the first face, `site`) set aside with the rest, for good (MineReach). */
    public static void addFaces(UUID village, BlockPos site, List<int[]> add) {
        if (add.isEmpty()) return;
        int r = TownMine.PITCH / 2;
        StringBuilder fs = new StringBuilder(Ledger.note(village, "mine.faces") == null ? "" : Ledger.note(village, "mine.faces"));
        StringBuilder gs = new StringBuilder(Ledger.note(village, "mine.ground") == null ? "" : Ledger.note(village, "mine.ground"));
        for (int[] c : add) {
            int x = site.getX() + c[0] * TownMine.PITCH, z = site.getZ() + c[1] * TownMine.PITCH;
            if (fs.length() > 0) fs.append(';');
            fs.append(c[0]).append(':').append(c[1]);
            if (gs.length() > 0) gs.append(';');
            gs.append(x - r - MARGIN).append(',').append(z - r - MARGIN).append(',').append(x + r + MARGIN).append(',').append(z + r + MARGIN);
        }
        Ledger.note(village, "mine.faces", fs.toString());
        Ledger.note(village, "mine.ground", gs.toString());
        GROUND.remove(village);
        FACES.remove(village);
    }

    /** On a look after a load: a mine opened before its ground was kept is set aside as it stands. Whether it was now. */
    public static boolean ensure(ServerLevel level, Villages.Village v) {
        BlockPos site = TownMine.siteOf(v.id());
        if (site == null || has(v.id())) return false;
        reserve(level, v.id(), v.centre(), site, true);
        return true;
    }

    /** Tests: the mine's ground let go (a fresh mine). */
    public static void forgetForTests(UUID village) {
        Ledger.note(village, "mine.faces", "");
        Ledger.note(village, "mine.ground", "");
        GROUND.remove(village);
        FACES.remove(village);
    }

    // ------------------------------------------------------------------ asking

    private static long key(int i, int j) {
        return ((long) i << 32) | (j & 0xffffffffL);
    }

    /** Is this face of the mine (grid place round the first) set aside for it? */
    public static boolean faceReserved(UUID village, int i, int j) {
        return faces(village).contains(key(i, j));
    }

    /** How many faces are set aside. */
    public static int faceCount(UUID village) {
        return faces(village).size();
    }

    private static Set<Long> faces(UUID village) {
        Set<Long> f = FACES.get(village);
        if (f != null) return f;
        f = new HashSet<>();
        String s = Ledger.note(village, "mine.faces");
        if (s != null && !s.isEmpty()) {
            for (String part : s.split(";")) {
                String[] ij = part.split(":");
                try {
                    f.add(key(Integer.parseInt(ij[0].trim()), Integer.parseInt(ij[1].trim())));
                } catch (RuntimeException ignored) {
                    // a damaged entry: that face is simply not set aside
                }
            }
        }
        FACES.put(village, f);
        return f;
    }

    /** The ground's rects, the first of them its bounds; empty when nothing is set aside. */
    private static int[][] ground(UUID village) {
        int[][] g = GROUND.get(village);
        if (g != null) return g;
        List<int[]> out = new ArrayList<>();
        String s = Ledger.note(village, "mine.ground");
        String old = MineSites.oldRects(village);                          // [econ32] the old mines' ground set aside for good too
        if (!old.isEmpty()) s = s == null || s.isEmpty() ? old : s + ";" + old;
        if (s != null && !s.isEmpty()) {
            int[] b = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
            out.add(b);
            for (String part : s.split(";")) {
                String[] p = part.split(",");
                try {
                    int[] q = { Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()) };
                    out.add(q);
                    b[0] = Math.min(b[0], q[0]); b[1] = Math.min(b[1], q[1]); b[2] = Math.max(b[2], q[2]); b[3] = Math.max(b[3], q[3]);
                } catch (RuntimeException ignored) {
                    // a damaged entry: left out
                }
            }
            if (out.size() == 1) out.clear();
        }
        g = out.toArray(new int[0][]);
        GROUND.put(village, g);
        return g;
    }

    /** Is this column the mine's ground of this village? */
    public static boolean reserved(@Nullable UUID village, int x, int z) {
        return village != null && meets(village, x, z, x, z);
    }

    /** Does this box (inclusive) touch the mine's ground of this village? */
    public static boolean meets(@Nullable UUID village, int x0, int z0, int x1, int z1) {
        if (village == null) return false;
        int[][] g = ground(village);
        if (g.length == 0) return false;
        int[] b = g[0];
        if (x1 < b[0] || x0 > b[2] || z1 < b[1] || z0 > b[3]) return false;
        for (int i = 1; i < g.length; i++) {
            int[] q = g[i];
            if (x1 >= q[0] && x0 <= q[2] && z1 >= q[1] && z0 <= q[3]) return true;
        }
        return false;
    }

    /** Does this box touch any town's mine's ground? */
    public static boolean anyMeets(int x0, int z0, int x1, int z1) {
        for (Villages.Village v : Villages.every()) if (meets(v.id(), x0, z0, x1, z1)) return true;
        return false;
    }

    /** Does this box touch another town's mine's ground (not this one's)? */
    public static boolean otherMeets(UUID village, int x0, int z0, int x1, int z1) {
        for (Villages.Village v : Villages.every()) if (!v.id().equals(village) && meets(v.id(), x0, z0, x1, z1)) return true;
        return false;
    }

    /** The town whose mine's ground this column is, in this world, or null. */
    @Nullable
    public static Villages.Village villageAt(Level level, BlockPos pos) {
        for (Villages.Village v : Villages.every()) {
            if (v.dim().equals(level.dimension()) && reserved(v.id(), pos.getX(), pos.getZ())) return v;
        }
        return null;
    }

    /**
     * What may be done on the mine's ground (TownBlocks): the mine's own work, the way to it, the ground work and what
     * nature does, always; the mending, furnishing and doors of a building already standing there (it stays as it is);
     * nothing else (no new building, field, wall, garden, lamp, seat or sign of the town's).
     */
    public static boolean allows(TownBlocks.Why why, boolean standing) {
        return switch (why) {
            case MINE, ROAD, TERRAFORM, TIDY, NATURE -> true;
            case REPAIR, RENOVATE, FURNISH, DOORWAY, WINDOW_BOX -> standing;
            default -> false;
        };
    }

    /** The ground as rects relative to the heart, for the town's map (Quarters, DistrictMap). */
    public static List<int[]> rectsFrom(UUID village, BlockPos heart) {
        int[][] g = ground(village);
        List<int[]> out = new ArrayList<>();
        for (int i = 1; i < g.length; i++) {
            int[] q = g[i];
            out.add(new int[]{ q[0] - heart.getX(), q[1] - heart.getZ(), q[2] - heart.getX(), q[3] - heart.getZ() });
        }
        return out;
    }

    /** A line for the status and the mine's report. */
    @Nullable
    public static String line(UUID village) {
        BlockPos site = TownMine.siteOf(village);
        if (site == null || !has(village)) return null;
        return "the mine's ground set aside: " + faceCount(village) + " faces round " + site.getX() + ", " + site.getZ() + " and the way to it";
    }
}
