package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [econ32d] Nobody left on top of the mine's headframe.
 *
 * <p>The miners' plot (their station, where they come back to and where a stuck walk ends) was the top of the ground over
 * the main shaft by the heightmap: once the headframe stood over the shaft, that was the top of its beams, and miners were
 * put there, and stood stuck on the beams. Now the pithead is its floor ({@link #floor}: a cell beside the well at the
 * pithead's height, open two high on solid ground), a rider steps out on ground and never on a roof, beam, fence or
 * leaves ({@link #standable}), and any folk found standing on the headframe a few seconds is put down on the pithead's
 * floor ({@link #check}, every second for each folk near the works).
 */
public final class MineRoof {

    private MineRoof() {}

    /** Looks (a second apart) a folk may stand on the headframe before it is put down. */
    static final int LOOKS = 3;

    private static final Map<UUID, Integer> ON = new ConcurrentHashMap<>();
    /** Folk put down off the headframe (the tests, the log). */
    public static volatile int DOWNS;

    public static void resetForTests() {
        ON.clear();
        DOWNS = 0;
    }

    /** Open two high on a floor a folk can stand on: never on leaves, a log (a post or a beam), a fence or a wall. */
    static boolean standable(ServerLevel level, BlockPos p) {
        BlockState at = level.getBlockState(p), over = level.getBlockState(p.above()), under = level.getBlockState(p.below());
        if (!at.getCollisionShape(level, p).isEmpty() || at.liquid() || !over.getCollisionShape(level, p.above()).isEmpty()) return false;
        if (under.is(BlockTags.LEAVES) || under.is(BlockTags.LOGS) || under.is(BlockTags.FENCES) || under.is(BlockTags.WALLS)) return false;
        return under.isFaceSturdy(level, p.below(), Direction.UP);
    }

    /** Is this spot on the headframe (over the pillar, two or more above the pithead's floor)? */
    static boolean onHeadframe(MineWorks.Works w, BlockPos feet) {
        return w.head && Math.abs(feet.getX() - w.sx) <= 1 && Math.abs(feet.getZ() - w.sz) <= 1 && feet.getY() >= w.top + 2;
    }

    /**
     * The pithead's floor for the miners' plot: the shaft's middle at the pithead's height if it is open two high on solid
     * ground (a mine with no cage yet), else a cell beside the well (within a block of the middle, as the plot must be); the
     * well's cap itself if none is.
     */
    static BlockPos floor(ServerLevel level, MineWorks.Works w) {
        for (int[] o : new int[][]{ { 0, 0 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 }, { -1, 1 }, { 1, -1 }, { -1, -1 } }) {
            for (int dy = 0; dy >= -1; dy--) {
                BlockPos p = new BlockPos(w.sx + o[0], w.top + dy, w.sz + o[1]);
                if (standable(level, p)) return p;
            }
        }
        return new BlockPos(w.sx, w.top, w.sz);
    }

    /**
     * Where a folk is put down at the pithead: the way in at the top of the stair, then the pithead's floor beside the well,
     * then the ground round the headframe (out to six blocks, no higher than a step over the pithead), the way in at last.
     */
    static BlockPos down(ServerLevel level, MineWorks.Works w) {
        List<BlockPos> tries = new ArrayList<>();
        tries.add(MineWorks.stepPoint(w, 0));
        tries.add(MineWorks.approach(w));
        BlockPos f = floor(level, w);
        tries.add(f);
        for (int r = 2; r <= 6; r++)
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++)
                if (Math.max(Math.abs(dx), Math.abs(dz)) == r) for (int dy = 1; dy >= -3; dy--) tries.add(new BlockPos(w.sx + dx, w.top + dy, w.sz + dz));
        for (BlockPos p : tries) if (!onHeadframe(w, p) && standable(level, p)) return p;
        return MineWorks.approach(w);
    }

    /**
     * Where a folk put on its plot stands, if the plot's middle is at a mine's pithead and the ground's top there (the
     * heightmap's, {@code y}) is the headframe over it: the pithead's floor, or the ground round it. Null otherwise.
     */
    @Nullable
    static BlockPos stand(ServerLevel level, @Nullable UUID village, BlockPos c, int y) {
        if (village == null) return null;
        BlockPos site = TownMine.siteOf(village);
        if (site == null || Math.abs(c.getX() - site.getX()) > 2 || Math.abs(c.getZ() - site.getZ()) > 2) return null;
        MineWorks.Works w = MineWorks.works(level, village);
        if (w == null || !w.head || y < w.top + 2) return null;
        BlockPos f = floor(level, w);
        return standable(level, f) ? f : down(level, w);
    }

    /** Every second, for a folk near the works: on the headframe a few seconds, it is put down on the pithead's floor. */
    static void check(VillageFolkEntity f, ServerLevel level) {
        UUID v = f.ownerId();
        if (v == null || f.isPassenger() || !f.isAlive()) return;
        BlockPos site = TownMine.siteOf(v);
        if (site == null || Math.abs(f.getBlockX() - site.getX()) > 2 || Math.abs(f.getBlockZ() - site.getZ()) > 2) { ON.remove(f.getUUID()); return; }
        MineWorks.Works w = MineWorks.works(level, v);
        if (w == null || !onHeadframe(w, f.blockPosition())) { ON.remove(f.getUUID()); return; }
        if (MineLift.state(f.getUUID()) == MineLift.State.RIDING) return;
        if (ON.merge(f.getUUID(), 1, Integer::sum) < LOOKS) return;
        ON.remove(f.getUUID());
        BlockPos was = f.blockPosition(), to = down(level, w);
        f.getNavigation().stop();
        f.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        DOWNS++;
        MineWhy.note(f, "found on the headframe at " + was.toShortString() + ", put down at " + to.toShortString());
        com.mojang.logging.LogUtils.getLogger().info("[MCA-LIFT] {}: {} found on the headframe at {}, put down at {}", Villages.name(v),
            f.displayNameCap(), was.toShortString(), to.toShortString());
    }

    /** Tests: one look now (as the folk's own second does). */
    public static void checkForTests(VillageFolkEntity f, ServerLevel level) {
        check(f, level);
    }

    /** Tests: the pithead's floor and the put-down cell for this town's works, or null. */
    @Nullable
    public static BlockPos[] cellsForTests(ServerLevel level, UUID village) {
        MineWorks.Works w = MineWorks.works(level, village);
        return w == null ? null : new BlockPos[]{ floor(level, w), down(level, w), new BlockPos(w.sx, w.top, w.sz), MineWorks.approach(w) };
    }

    /** Tests: is this spot on the headframe of this town's works? */
    public static boolean onHeadframeForTests(ServerLevel level, UUID village, BlockPos p) {
        MineWorks.Works w = MineWorks.works(level, village);
        return w != null && onHeadframe(w, p);
    }
}
