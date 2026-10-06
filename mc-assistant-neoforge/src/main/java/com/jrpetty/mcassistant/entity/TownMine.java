package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The town's mine: one stretch of rock out beyond the town, fifty to a hundred-odd blocks from the heart,
 * where all its miners work side by side.
 *
 * <p>Each miner used to find its own plot, wherever its look round happened to land, just outside the town
 * as it stood that day. The town grew and the plots did not move: a staircase cut down from the middle of a
 * plot the town had since built over took up the floor of a house, and in one town the square. Now a town
 * has one mine, chosen once on the best rock out of its way, laid out in faces a miner's plot across (a
 * square seventeen blocks wide). Each miner works a face of it; the faces lie side by side, so their stairs
 * and galleries run into one another and the ground is dug out between them, down and across. A face
 * worked out is marked so, and its miner takes the next free one, a ring further out and away from the town:
 * the mine spreads as it is dug. A face the town has grown into is left alone for good. How deep each face
 * goes is the age's and the ore's question (VillageFolkEntity.seekTheSeam): the iron, the coal when the age
 * is short of it, the diamonds only when the age wants them.
 */
public final class TownMine {

    private TownMine() {}

    /** A face is one miner's plot: a square of radius eight. */
    public static final int PITCH = 17;
    /** How far out the faces go round the first one: a mine of nine faces across, at most. */
    public static final int MOST_RING = 4;
    /** The mine's first face is this far from the heart, at the least... */
    public static final int NEAREST = 50;
    /** ...and no further than this, unless the town has grown out to it. */
    public static final int FARTHEST = 110;

    // ------------------------------------------------------------------ the site

    /**
     * The middle of the mine's first face, chosen once and kept with the world: on the best rock in a ring
     * fifty to a hundred and ten blocks out (further when the town is big), outside the town, off the
     * farmland, short of the border with a neighbour, never on water. The rock is weighed by `stony` (the
     * caller's look at the stone under a spot) and by height, a hill having more rock above the seams.
     * Null when no ground round about is loaded and fit.
     */
    @Nullable
    public static BlockPos site(ServerLevel level, UUID village, BlockPos heart, Predicate<BlockPos> stony) {
        BlockPos kept = kept(village);
        if (kept != null) return kept;
        int near = Math.max(NEAREST, Villages.townReach(village) + PITCH / 2 + 8);
        int far = Math.max(FARTHEST, near + 40);
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int d = near; d <= far; d += 8) {
            for (int b = 0; b < 16; b++) {
                double a = b * Math.PI / 8.0;
                int x = heart.getX() + (int) Math.round(Math.cos(a) * d);
                int z = heart.getZ() + (int) Math.round(Math.sin(a) * d);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
                if (!fit(level, village, heart, top)) continue;
                double score = (stony.test(top) ? 100.0 : 0.0) + (top.getY() - heart.getY()) * 2.0 - (d - near) * 0.25;
                if (score > bestScore) {
                    bestScore = score;
                    best = top;
                }
            }
        }
        if (best == null) return null;
        Ledger.note(village, "mine.site", best.getX() + "," + best.getZ());
        Villages.tell(village, level.getDayTime() / 24000L, "the town's mine was opened, out at " + best.getX() + ", " + best.getZ());
        return best;
    }

    /** May a face of the mine be here: outside the town, off the farmland, short of a border, not water. */
    static boolean fit(ServerLevel level, UUID village, BlockPos heart, BlockPos top) {
        int r = PITCH / 2;
        if (!Villages.outsideTown(village, heart, top, r)) return false;
        if (Villages.onFarmland(village, heart, top, r)) return false;
        if (Bonds.overBorder(village, heart, top, r)) return false;
        return level.getFluidState(top.below()).isEmpty() && level.getFluidState(top).isEmpty();
    }

    @Nullable
    private static BlockPos kept(UUID village) {
        String s = Ledger.note(village, "mine.site");
        if (s == null || s.isEmpty()) return null;
        try {
            String[] p = s.split(",");
            return new BlockPos(Integer.parseInt(p[0].trim()), 0, Integer.parseInt(p[1].trim()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the faces

    /** The faces of the mine, as grid places round the first, nearest first and, ring by ring, those farthest
     *  from the town first: the mine spreads away from the houses. */
    private static List<int[]> order(BlockPos site, BlockPos heart) {
        List<int[]> out = new ArrayList<>();
        for (int i = -MOST_RING; i <= MOST_RING; i++) {
            for (int j = -MOST_RING; j <= MOST_RING; j++) out.add(new int[]{ i, j });
        }
        out.sort(Comparator.<int[]>comparingInt(c -> Math.max(Math.abs(c[0]), Math.abs(c[1])))
            .thenComparingDouble(c -> -centre(site, c).distSqr(heart.atY(0))));
        return out;
    }

    private static BlockPos centre(BlockPos site, int[] c) {
        return new BlockPos(site.getX() + c[0] * PITCH, 0, site.getZ() + c[1] * PITCH);
    }

    /** The grid place of the face this spot falls in, or null when it is outside the mine. */
    @Nullable
    private static int[] faceOf(BlockPos site, BlockPos p) {
        int i = Math.floorDiv(p.getX() - site.getX() + PITCH / 2, PITCH);
        int j = Math.floorDiv(p.getZ() - site.getZ() + PITCH / 2, PITCH);
        return Math.max(Math.abs(i), Math.abs(j)) <= MOST_RING ? new int[]{ i, j } : null;
    }

    /** Is this spot in one of the town's mine faces? */
    public static boolean inMine(UUID village, BlockPos p) {
        BlockPos site = kept(village);
        return site != null && faceOf(site, p) != null;
    }

    /** The middle of the face this spot is in (y left as given), or null outside the mine. */
    @Nullable
    public static BlockPos faceCentre(UUID village, BlockPos p) {
        BlockPos site = kept(village);
        int[] c = site == null ? null : faceOf(site, p);
        return c == null ? null : centre(site, c).atY(p.getY());
    }

    /**
     * A face for this miner: the face it already works while it is still good; else the first face of the
     * mine (ring by ring, the far side from the town first) that is not worked out, not another miner's, and
     * fit (outside the town as it is now, off the farmland, with nothing of the town's built on it). Its middle
     * at ground level; null when the mine has no face to give (none fit, or the ground not loaded).
     */
    @Nullable
    public static BlockPos faceFor(ServerLevel level, VillageFolkEntity miner, BlockPos heart, Predicate<BlockPos> stony) {
        UUID village = miner.ownerId();
        if (village == null) return null;
        BlockPos site = site(level, village, heart, stony);
        if (site == null) return null;
        Set<Long> spent = spent(village);
        Set<Long> taken = new HashSet<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a == miner || !(a instanceof VillageFolkEntity f) || f.stationTask() != AssistantEntity.StationTask.MINE) continue;
            WorkZone z = f.workZone();
            if (z == null) continue;
            int[] c = faceOf(site, z.center());
            if (c != null) taken.add(key(c));
        }
        int[][] built = builtGround(village, heart, level.getGameTime());
        WorkZone own = miner.workZone();
        int[] mine = own == null ? null : faceOf(site, own.center());
        List<int[]> faces = order(site, heart);
        if (mine != null) faces.add(0, mine);                         // its own face first, while it is good
        for (int[] c : faces) {
            if (spent.contains(key(c)) || taken.contains(key(c))) continue;
            BlockPos mid = centre(site, c);
            if (!level.hasChunk(mid.getX() >> 4, mid.getZ() >> 4)) continue;
            BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mid);
            if (!fit(level, village, heart, top)) continue;
            if (underTheTown(built, top, PITCH / 2)) continue;       // something of the town's stands on it now
            return top;
        }
        return null;
    }

    /** This face is worked out: no miner is sent to it again. */
    public static void spent(UUID village, BlockPos inFace) {
        BlockPos site = kept(village);
        int[] c = site == null ? null : faceOf(site, inFace);
        if (c == null) return;
        Set<Long> all = spent(village);
        if (!all.add(key(c))) return;
        StringBuilder sb = new StringBuilder();
        for (long k : all) {
            if (sb.length() > 0) sb.append(';');
            sb.append((int) (k >> 32)).append(':').append((int) k);
        }
        Ledger.note(village, "mine.spent", sb.toString());
    }

    private static Set<Long> spent(UUID village) {
        Set<Long> out = new HashSet<>();
        String s = Ledger.note(village, "mine.spent");
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            String[] ij = part.split(":");
            try {
                out.add(key(new int[]{ Integer.parseInt(ij[0].trim()), Integer.parseInt(ij[1].trim()) }));
            } catch (RuntimeException ignored) {
                // a damaged entry: that face is simply not marked
            }
        }
        return out;
    }

    private static long key(int[] c) {
        return ((long) c[0] << 32) | (c[1] & 0xffffffffL);
    }

    // ------------------------------------------------------------------ the town over the ground

    /** The town's built ground, by village: squares (x0, z0, x1, z1) over each building and the square, and
     *  when it was last drawn up. */
    private static final java.util.Map<UUID, int[][]> BUILT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<UUID, Long> BUILT_AT = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The ground the town stands on: each of its buildings with two blocks round it, and the square. No miner
     * of the town cuts a block under any of it, and a plot that has come to lie over any of it is given up
     * for a face of the mine. Drawn up afresh every ten seconds.
     */
    public static int[][] builtGround(UUID village, BlockPos heart, long now) {
        Long at = BUILT_AT.get(village);
        int[][] kept = BUILT.get(village);
        if (kept != null && at != null && now - at < 200L && now >= at) return kept;
        List<int[]> out = new ArrayList<>();
        int sq = com.jrpetty.mcassistant.village.TownPlan.PLAZA + 2;
        out.add(new int[]{ heart.getX() - sq, heart.getZ() - sq, heart.getX() + sq, heart.getZ() + sq });
        for (Ledger.Building b : Ledger.buildings(village)) {
            int half;
            try {
                int[] h = com.jrpetty.mcassistant.entity.goal.Blueprints.has(b.structure())
                    ? com.jrpetty.mcassistant.entity.goal.Blueprints.fullHalf(b.structure())
                    : com.jrpetty.mcassistant.entity.goal.BuildGoal.footprint(b.structure());
                half = Math.max(h[0], h[1]) + 2;
            } catch (RuntimeException e) {
                half = 8;
            }
            BlockPos a = b.anchor();
            out.add(new int[]{ a.getX() - half, a.getZ() - half, a.getX() + half, a.getZ() + half });
        }
        int[][] arr = out.toArray(new int[0][]);
        BUILT.put(village, arr);
        BUILT_AT.put(village, now);
        return arr;
    }

    /** Is this column within so many blocks of the town's built ground? */
    public static boolean underTheTown(int[][] ground, BlockPos p, int margin) {
        for (int[] r : ground) {
            if (p.getX() >= r[0] - margin && p.getX() <= r[2] + margin && p.getZ() >= r[1] - margin && p.getZ() <= r[3] + margin) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ for the books

    /** The mine in a few lines: where it is, its faces, who works which and how deep. */
    public static List<String> report(UUID village, BlockPos heart) {
        List<String> out = new ArrayList<>();
        BlockPos site = kept(village);
        if (site == null) {
            out.add("No mine yet: it is opened when the town's first miner looks for ground.");
            return out;
        }
        int d = (int) Math.round(Math.sqrt(site.atY(0).distSqr(heart.atY(0))));
        out.add("The mine: first face at " + site.getX() + ", " + site.getZ() + ", " + d + " blocks from the heart; "
            + spent(village).size() + " faces worked out.");
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != AssistantEntity.StationTask.MINE) continue;
            WorkZone z = f.workZone();
            if (z == null) { out.add(f.displayNameCap() + ": no face yet"); continue; }
            int[] c = faceOf(site, z.center());
            out.add(f.displayNameCap() + ": " + (c == null ? "a plot outside the mine at " + z.center().getX() + ", " + z.center().getZ()
                : "face " + c[0] + "," + c[1]) + ", down to Y" + z.depth());
        }
        return out;
    }

    /** The town's ground is drawn up afresh on the next look (a building just finished, or a test's). */
    public static void redraw(UUID village) {
        BUILT_AT.remove(village);
    }

    /** Tests: forget the mine (its site and the faces worked out). */
    public static void forgetForTests(UUID village) {
        Ledger.note(village, "mine.site", "");
        Ledger.note(village, "mine.spent", "");
        BUILT.remove(village);
        BUILT_AT.remove(village);
    }
}
