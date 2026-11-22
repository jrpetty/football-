package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [econ32] A new mine, when the old one is worked out to the edge of all the ground it may have.
 *
 * <p>The mine's ground is widened a ring at a time as its levels are worked out (MineReach), out to the eighth ring; past
 * that, or where no face of the next ring is clear of the town, a level of an ore worked out to its edge was the last of it:
 * "there is no more of its iron within the shaft's reach", and the iron (or the diamonds) stopped for good, a few hundred
 * days into a long game. A real town short of ore sinks a new shaft elsewhere. Now, when every level of the iron (or of the
 * deep level's diamonds) is worked out and the ground can be widened no more:
 * <ul>
 * <li>The town looks for a new site as it chose its first ({@link #find}): on the best rock a hundred-odd blocks out, clear
 *     of its whole plan, of its neighbours' mines and borders, and clear of the old mine's ground.</li>
 * <li>It waits till nobody is down the old works (never a miner left below with the shaft it knows gone), then opens the new
 *     one: the old mine's ground stays set aside for good (nothing is built over its stairs and drifts; its headframe and
 *     its railings stand as they were), the new ground is set aside round the new site, and the miners walk out to the new
 *     pithead, where the works are begun afresh (a new main shaft, its headframe and railings, its levels: MineWorks).</li>
 * <li>The town is told, and the old mine kept in the ledger's notes ({@code mine.old}).</li>
 * </ul>
 * At most {@link #MOST} mines a town.
 */
public final class MineSites {

    private MineSites() {}

    /** The most mines a town opens in all (the first and its successors). */
    public static final int MOST = 6;
    /** How often a town waiting on a new mine looks for one (ticks). */
    static final long EVERY = 2400L;

    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    private static final Map<UUID, int[][]> OLD = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LOOKED.clear();
        OLD.clear();
    }

    /** The ore a level's base is cut for, in the town's words, when its working out may call for a new mine; else null. */
    @Nullable
    static String worth(int base) {
        return base == MineWorks.IRON ? "iron" : base == MineWorks.DEEP ? "diamonds" : null;
    }

    /** How many mines this town has worked out and left (its old mines). */
    public static int oldMines(UUID village) {
        String s = Ledger.note(village, "mine.old");
        return s == null || s.isEmpty() ? 0 : s.split(";").length;
    }

    /** Is the town waiting on a new mine, and for what (null: not)? */
    @Nullable
    public static String pending(UUID village) {
        String s = Ledger.note(village, "mine.next");
        return s == null || s.isEmpty() ? null : s;
    }

    /**
     * A level of this base worked out with no further level of its ore and the ground widened no more (MineLevels.markSpent):
     * if every level of its ore in the plan is worked out and it is the iron or the diamonds, a new mine is wanted.
     */
    static void exhausted(ServerLevel level, MineWorks.Works w, int base) {
        String what = worth(base);
        if (what == null || pending(w.village) != null || oldMines(w.village) + 1 >= MOST) return;
        for (MineWorks.Lvl l : MineWorks.plan(level, w)) {
            if (MineLevels.base(l.id()) == base && !MineLevels.spent(w, l.id())) return;
        }
        Ledger.note(w.village, "mine.next", what);
        long day = level.getDayTime() / 24000L;
        Villages.tell(w.village, day, "the town's mine is worked out to the edge of its ground for " + what
            + ": the miners are to sink a new shaft elsewhere");
        com.mojang.logging.LogUtils.getLogger().info("[MCA-MINE] {}: the mine worked out for {}; a new mine wanted", Villages.name(w.village), what);
    }

    /** The old mines' ground, as rects {x0, z0, x1, z1}. */
    static int[][] old(UUID village) {
        int[][] g = OLD.get(village);
        if (g != null) return g;
        List<int[]> out = new ArrayList<>();
        String s = Ledger.note(village, "mine.ground.old");
        if (s != null && !s.isEmpty()) {
            for (String part : s.split(";")) {
                String[] p = part.split(",");
                try {
                    out.add(new int[]{ Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()) });
                } catch (RuntimeException ignored) {
                    // a damaged entry: left out
                }
            }
        }
        g = out.toArray(new int[0][]);
        OLD.put(village, g);
        return g;
    }

    /** Does this box (inclusive) touch the ground of one of this town's old mines? */
    public static boolean meetsOld(@Nullable UUID village, int x0, int z0, int x1, int z1) {
        if (village == null) return false;
        for (int[] q : old(village)) if (x1 >= q[0] && x0 <= q[2] && z1 >= q[1] && z0 <= q[3]) return true;
        return false;
    }

    /** The old mines' ground and the current mine's ground, as rects (MineGround reads both for what is set aside). */
    static String oldRects(UUID village) {
        String s = Ledger.note(village, "mine.ground.old");
        return s == null ? "" : s;
    }

    /** Once in a while a town (MineWorks.tick): a town waiting on a new mine opens one, when nobody is down the old works. */
    public static void tick(ServerLevel level, Villages.Village v) {
        String what = pending(v.id());
        if (what == null) return;
        long now = level.getGameTime();
        Long last = LOOKED.get(v.id());
        if (last != null && now - last < EVERY && now >= last) return;
        LOOKED.put(v.id(), now);
        open(level, v, what);
    }

    /** A new site, clear of the town's plan, its neighbours and its old mines' ground; or null if none loaded is fit. */
    @Nullable
    static BlockPos find(ServerLevel level, Villages.Village v) {
        UUID village = v.id();
        BlockPos heart = v.centre();
        int near = Math.max(Math.max(TownMine.NEAREST, Villages.townReach(village) + TownMine.PITCH / 2 + 8),
            TownDigs.PLAN_REACH + TownDigs.MARGIN + TownMine.PITCH / 2 + 2);
        int far = Math.max(TownMine.FARTHEST, near + 60) + 120;              // further out than the first: the old ground lies near
        int r = TownMine.PITCH / 2 + MineGround.MARGIN;
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int d = near; d <= far; d += 8) {
            for (int b = 0; b < 24; b++) {
                double a = b * Math.PI / 12.0;
                int x = heart.getX() + (int) Math.round(Math.cos(a) * d);
                int z = heart.getZ() + (int) Math.round(Math.sin(a) * d);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                // Clear of the old mines by a ring of faces: the new drifts never run into the old.
                int m = r + TownMine.PITCH;
                if (meetsOld(village, x - m, z - m, x + m, z + m) || MineGround.meets(village, x - m, z - m, x + m, z + m)) continue;
                BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
                if (!TownMine.fit(level, village, heart, top)) continue;
                boolean stony = level.getBlockState(top.below()).is(BlockTags.BASE_STONE_OVERWORLD)
                    || level.getBlockState(top.below(4)).is(BlockTags.BASE_STONE_OVERWORLD);
                double score = (stony ? 100.0 : 0.0) + (top.getY() - heart.getY()) * 2.0 - (d - near) * 0.25;
                if (score > bestScore) {
                    bestScore = score;
                    best = top;
                }
            }
        }
        return best;
    }

    /** Open the new mine now, if nobody is down the old works and a site is found. Whether it did. */
    public static boolean open(ServerLevel level, Villages.Village v, String what) {
        UUID village = v.id();
        BlockPos oldSite = TownMine.siteOf(village);
        if (oldSite == null) return false;
        List<VillageFolkEntity> miners = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != AssistantEntity.StationTask.MINE) continue;
            if (MineWorks.below(f)) return false;                         // somebody still down the old works: later
            miners.add(f);
        }
        BlockPos site = find(level, v);
        if (site == null) {
            com.mojang.logging.LogUtils.getLogger().info("[MCA-MINE] {}: no ground fit for a new mine loaded yet", Villages.name(village));
            return false;
        }
        long day = level.getDayTime() / 24000L;
        // The old mine's ground kept set aside for good; its works and its faces let go.
        String ground = Ledger.note(village, "mine.ground");
        String old = oldRects(village);
        if (ground != null && !ground.isEmpty()) Ledger.note(village, "mine.ground.old", old.isEmpty() ? ground : old + ";" + ground);
        String mines = Ledger.note(village, "mine.old");
        String entry = oldSite.getX() + "," + oldSite.getZ() + "," + day;
        Ledger.note(village, "mine.old", mines == null || mines.isEmpty() ? entry : mines + ";" + entry);
        OLD.remove(village);
        Ledger.note(village, "mine.next", "");
        Ledger.note(village, "mine.spent", "");
        Ledger.note(village, "mine.faces", "");
        Ledger.note(village, "mine.ground", "");
        MineGround.forgetCache();
        MineWorks.forgetForTests(village);
        // The new site, and its ground set aside round it.
        Ledger.note(village, "mine.site", site.getX() + "," + site.getZ());
        MineGround.reserve(level, village, v.centre(), site, false);
        MineDemand.reshare(village);
        TownMine.redraw(village);
        BlockPos head = MineWorks.pithead(level, village, site);
        int moved = 0;
        if (head != null) for (VillageFolkEntity f : miners) if (f.toNewPithead(head)) moved++;
        Villages.tell(village, day, "the town's old mine at " + oldSite.getX() + ", " + oldSite.getZ() + " was left, worked out for " + what
            + ", and a new mine opened at " + site.getX() + ", " + site.getZ() + "; " + moved + " miners went out to sink its shaft");
        com.mojang.logging.LogUtils.getLogger().info("[MCA-MINE] {}: a new mine at {}, {} (the old at {}, {} worked out for {}); {} miners moved",
            Villages.name(village), site.getX(), site.getZ(), oldSite.getX(), oldSite.getZ(), what, moved);
        return true;
    }

    /** Tests: the town wants a new mine for this, now (as a worked-out mine would ask). */
    public static void wantForTests(UUID village, String what) {
        Ledger.note(village, "mine.next", what);
        LOOKED.remove(village);
    }
}
