package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [mine-safety] The town's mine is a place of work, not a trap.
 *
 * <ul>
 * <li><b>Below ground.</b> A folk where the miners have been (a face with its stairs cut, or one beside it)
 *     with rock over its head, or at the bottom of a hole four or more deep with the sky over it, is below
 *     ground in the mine. Only a miner at work has any business there. Anybody else
 *     there is sent up (MineStairs.lookForAWayUp): up the stairs, mending them as it climbs, or up steps it
 *     cuts and lays for itself, as a player would (MineGoal, "out"). The town's other rescues (put back on
 *     its plot, set down by its bed) lift a folk wherever it is; down the mine they send it climbing
 *     instead ({@link #climbInstead}).</li>
 * <li><b>The stair heads.</b> The top steps of a mine's stairs are an open trench in the ground, two and
 *     three blocks deep: walked across in the dark, a step down that a folk cannot climb back out of except
 *     by the stairs. Each is fenced round at the surface, the head itself left open as the way in, with a
 *     sign on the fence by it: "The mine of" the town. The fences and the sign come out of the stores (a
 *     length of fence or two planks each, a sign or two planks), put up by a hand on the town's works. A
 *     miner cutting new stairs takes a fence of these down where it stands in its way (MineGoal.allowed).</li>
 * <li><b>The books.</b> Who is below ground in the mine, by name and trade, and the stair heads fenced
 *     (TownMine.report, "/village mine", the long game's MINE line).</li>
 * </ul>
 *
 * <p>The sixty-day plains game that brought this about lost its farmers to something else (Aboard: they
 * sat in the fishers' boats), but the mine's open stair heads and a hand lent out to cut stone at a miner's
 * face (VillageFolkEntity.groundFor) were ways down that nobody but a miner should take.
 */
public final class MineSafety {

    private MineSafety() {}

    /** How deep a hole open to the sky is before a folk at the bottom of it is below ground: deeper than the
     *  open top steps of a stair (three), which it walks up out of. */
    static final int DOWN = 4;
    /** How far round a stair head the open top of its stairs is looked for. */
    static final int ROUND = 4;
    /** Fences put up in one look, at most. */
    static final int FENCES_A_LOOK = 12;
    /** How often a town looks over its stair heads. */
    static final long EVERY = 600L;

    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    /** Towns whose hand is on its way to a stair head: looked at again on the town's next round of works, so the
     *  call on it does not lapse (TownJobs.HOLD) before it gets there. */
    private static final Set<UUID> WAITING = ConcurrentHashMap.newKeySet();

    public static void resetForTests() {
        LAST.clear();
        WAITING.clear();
    }

    // ------------------------------------------------------------------ below ground

    /**
     * Below ground in this village's mine: under a face that has been dug (its stairs kept), or one beside
     * it that the galleries run on under, and under rock there or down a hole {@link #DOWN} or more deep
     * all round with the sky over it. (The mine's grid of faces runs out over the town and its fields; only
     * where the miners have been is the mine, and a house's cellar never is.)
     */
    public static boolean below(ServerLevel level, @Nullable UUID village, BlockPos feet) {
        if (village == null || TownMine.siteOf(village) == null || !dug(level, village, feet)) return false;
        return MineStairs.underground(level, feet) || inAHole(level, feet);
    }

    /** In a face of the mine with stairs cut, or next to one. */
    static boolean dug(ServerLevel level, UUID village, BlockPos p) {
        BlockPos c = TownMine.faceCentre(village, p);
        if (c == null) return false;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                if (MineStairs.head(level, MineStairs.plotKey(c.offset(i * TownMine.PITCH, 0, j * TownMine.PITCH))) != null) return true;
            }
        }
        return false;
    }

    /**
     * Down a hole with the sky over it: the ground two blocks off stands {@link #DOWN} or more over its feet
     * seven ways of eight. Not a hillside (the ground falls away on the far side of a slope), nor the top
     * steps of a stair (the head is a step or two up from them).
     */
    static boolean inAHole(ServerLevel level, BlockPos feet) {
        int higher = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int x = feet.getX() + 2 * dx, z = feet.getZ() + 2 * dz;
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
                if (level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) >= feet.getY() + DOWN) higher++;
            }
        }
        return higher >= 7;
    }

    /** A folk of a village below ground in its mine (MineGoal: a way out is not over while it is). */
    public static boolean below(AssistantEntity a, BlockPos feet) {
        return a instanceof VillageFolkEntity f && a.level() instanceof ServerLevel level && below(level, f.ownerId(), feet);
    }

    /**
     * Instead of being lifted out (AssistantEntity.rescueToPlot, putBeside): a folk below ground in the mine
     * climbs out. It is sent up the stairs, or to cut its own (a mine job "out"), unless it is on its way up
     * already or is a miner on a run, whose run sees it home. True if it is down there: the rescue is not
     * for it.
     */
    public static boolean climbInstead(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level) || f.isPassenger()) return false;
        BlockPos feet = f.blockPosition();
        if (!below(level, f.ownerId(), feet)) return false;
        Job j = f.peekJob();
        boolean climbing = f.running(com.jrpetty.mcassistant.entity.goal.MineGoal.class);
        if (j != null && j.type() == Job.Type.MINE) {
            // Its way up (or its run) first in the queue but not under way: the goal that called for the rescue (a
            // deposit, a walk to its bed) has its legs, and lets go now. Its watchdog used to set it going again.
            if (!climbing) f.interject(null);
            return true;
        }
        f.interject(Job.mine(feet.getY(), MineStairs.OUT));
        f.brain("below ground in the mine: climbing out");
        return true;
    }

    // ------------------------------------------------------------------ the stair heads

    /** Every half a minute, by day (TownWork.tick): the town's stair heads fenced, a few lengths at a time. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        // A young town has no hand to spare for it yet (TownJobs.SETTLED), nor a mine worth the name.
        if (TownMine.siteOf(id) == null || Villages.headcount(id) < TownJobs.SETTLED) return;
        long now = level.getGameTime();
        if (!WAITING.contains(id) && now - LAST.getOrDefault(id, -100000L) < EVERY) return;
        LAST.put(id, now);
        fence(level, v, false, FENCES_A_LOOK);
    }

    /**
     * The open tops of the mine's stairs fenced, the heads left open, and a sign by each head: no more than
     * so many fences. Out of the stores, by a hand on the town's works (or for nothing: the tests and the
     * showcase). The lengths of fence put up.
     */
    public static int fence(ServerLevel level, Villages.Village v, boolean free, int most) {
        UUID id = v.id();
        WAITING.remove(id);
        int put = 0;
        for (List<BlockPos> top : MineStairs.tops(level, 2)) {
            BlockPos head = top.get(0);
            if (!TownMine.inMine(id, head) || !Land.areaLoaded(level, head, ROUND + 2)) continue;
            int n = fenceRound(level, v, top, free, most - put);
            if (n < 0) { put -= n + 1; break; }                  // the stores, or the hand, ran out: the rest wait
            put += n;
            if (put >= most) break;
        }
        if (put > 0) {
            com.mojang.logging.LogUtils.getLogger().info("[MCA-MINE] {}: {} lengths of fence round the mine's stair heads",
                Villages.name(id), put);
        }
        return put;
    }

    /**
     * One stair head fenced round (no more than so many lengths) and its sign put up. The lengths put up;
     * or, when the stores or the hand ran out part way, minus one less that many.
     */
    private static int fenceRound(ServerLevel level, Villages.Village v, List<BlockPos> top, boolean free, int most) {
        BlockPos head = top.get(0);
        int put = 0;
        for (BlockPos s : spots(level, top)) {
            if (put >= most) return put;
            if (!free && !paid(level, v, head, "fencing the mine stairs", WOODEN_FENCE)) return -put - 1;
            level.setBlock(s, post(level, s), 3);
            put++;
        }
        if (signAt(level, top) == null) sign(level, v, top, free);
        return put;
    }

    private static final java.util.function.Predicate<net.minecraft.world.item.ItemStack> WOODEN_FENCE =
        s -> s.is(net.minecraft.tags.ItemTags.WOODEN_FENCES);
    private static final java.util.function.Predicate<net.minecraft.world.item.ItemStack> A_SIGN =
        s -> s.is(net.minecraft.tags.ItemTags.SIGNS);

    /**
     * A length of fence (or the sign) paid for out of the stores, a hand there to put it up: one put by, or
     * two planks. Nobody is sent till the stores can run to it; while the hand is on its way, false, and
     * the town looks again on its next round of works.
     */
    private static boolean paid(ServerLevel level, Villages.Village v, BlockPos head, String what,
                                java.util.function.Predicate<net.minecraft.world.item.ItemStack> thing) {
        if (Crafts.stock(level, v, thing) == 0 && Crafts.stock(level, v, s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) < 2
                && Crafts.stock(level, v, s -> s.is(net.minecraft.tags.ItemTags.LOGS)) == 0) return false;
        // Any hand the town can spare: not a miner first, whose call would cut its run short at the bottom of the mine.
        if (!TownJobs.atWork(level, v, "mine stairs", head, what)) {
            WAITING.add(v.id());
            return false;
        }
        return thing == A_SIGN ? Crafts.sign(level, v) : Crafts.fence(level, v);
    }

    /**
     * Operators (/village mine showcase): a mine's stairs cut into the ground where it stands, seven steps
     * east, kept as stairs, and fenced round with the sign up, for nothing: to be looked at. Only the
     * ground the world made is cut. Lines, with the views for the smoke's camera.
     */
    public static List<String> showcase(ServerLevel level, Villages.Village v, BlockPos at) {
        List<BlockPos> steps = new ArrayList<>();
        steps.add(at);
        BlockPos cur = at;
        for (int k = 1; k <= 7; k++) {
            cur = cur.east().below();
            steps.add(cur);
        }
        for (BlockPos s : steps) {
            for (int h = 0; h <= 2; h++) {
                BlockPos c = s.above(h);
                BlockState st = level.getBlockState(c);
                if (!st.isAir() && !st.hasBlockEntity() && com.jrpetty.mcassistant.entity.goal.MineGoal.wild(level, c, st)) {
                    level.setBlock(c, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        MineStairs.record(level, MineStairs.plotKey(at), steps);
        List<BlockPos> top = steps.subList(0, 2);
        int put = fenceRound(level, v, top, true, 10000);
        BlockPos sign = signAt(level, top);
        List<String> out = new ArrayList<>();
        out.add("Stairs cut at " + at.toShortString() + ", seven steps east; " + put + " lengths of fence round their open top, the head left open"
            + (sign == null ? "; no post by the head for the sign" : "; the sign at " + sign.toShortString()) + ".");
        BlockPos eye = at.offset(-5, 5, -6), mid = at.offset(2, -1, 0);
        out.add("VIEW 31-mine-stair-head " + eye.getX() + " " + eye.getY() + " " + eye.getZ() + " " + mid.getX() + " " + mid.getY() + " " + mid.getZ());
        if (sign != null) {
            BlockPos near = at.offset(-3, 1, sign.getZ() >= at.getZ() ? 2 : -2);
            out.add("VIEW 32-mine-sign " + near.getX() + " " + near.getY() + " " + near.getZ() + " " + sign.getX() + " " + sign.getY() + " " + sign.getZ());
        }
        return out;
    }

    /** A length of fence here, joined up with the fences beside it. */
    private static BlockState post(ServerLevel level, BlockPos s) {
        return Block.updateFromNeighbourShapes(Blocks.SPRUCE_FENCE.defaultBlockState(), level, s);
    }

    /**
     * Where a stair head wants fencing: every spot of open ground at the head's level beside the open top
     * of the stairs (a column open two blocks down from there), but the head itself, the steps of any
     * stairs, and anything already standing.
     */
    static List<BlockPos> spots(ServerLevel level, List<BlockPos> top) {
        BlockPos head = top.get(0);
        int y = head.getY();
        Set<Long> holes = holes(level, head);
        List<BlockPos> out = new ArrayList<>();
        if (holes.isEmpty()) return out;
        for (int dx = -ROUND - 1; dx <= ROUND + 1; dx++) {
            for (int dz = -ROUND - 1; dz <= ROUND + 1; dz++) {
                if (dx == 0 && dz == 0) continue;                                   // the head: the way in
                BlockPos s = new BlockPos(head.getX() + dx, y, head.getZ() + dz);
                if (holes.contains(BlockPos.asLong(s.getX(), 0, s.getZ())) || !beside(holes, s)) continue;
                if (!level.getBlockState(s).canBeReplaced() || !level.getFluidState(s).isEmpty()) continue;
                // On the ground at the head's level: not the first step down, nor over the hole itself.
                if (!level.getBlockState(s.below()).isFaceSturdy(level, s.below(), Direction.UP)) continue;
                if (MineStairs.isFloor(level, s.below())) continue;                  // a step of somebody's stairs
                out.add(s);
            }
        }
        return out;
    }

    /** The columns round a stair head open two blocks down from its level (as BlockPos longs at y 0). */
    private static Set<Long> holes(ServerLevel level, BlockPos head) {
        Set<Long> holes = new HashSet<>();
        for (int dx = -ROUND; dx <= ROUND; dx++) {
            for (int dz = -ROUND; dz <= ROUND; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos c = new BlockPos(head.getX() + dx, head.getY(), head.getZ() + dz);
                if (hole(level, c)) holes.add(BlockPos.asLong(c.getX(), 0, c.getZ()));
            }
        }
        return holes;
    }

    /** Open two blocks down from the head's level at this column: a drop a folk cannot step back up out of. */
    static boolean hole(ServerLevel level, BlockPos atHead) {
        for (int k = 0; k <= 2; k++) {
            BlockPos p = atHead.below(k);
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) return false;
        }
        return true;
    }

    private static boolean beside(Set<Long> holes, BlockPos s) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && holes.contains(BlockPos.asLong(s.getX() + dx, 0, s.getZ() + dz))) return true;
            }
        }
        return false;
    }

    /** The way down from the head: toward its first step (east when the stairs have none yet). */
    private static Direction down(List<BlockPos> top) {
        if (top.size() < 2) return Direction.EAST;
        BlockPos a = top.get(0), b = top.get(1);
        return Direction.getNearest(b.getX() - a.getX(), 0, b.getZ() - a.getZ());
    }

    /** The sign by this stair head (on a fence post beside it), or null. */
    @Nullable
    static BlockPos signAt(ServerLevel level, List<BlockPos> top) {
        BlockPos head = top.get(0);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos p = head.offset(dx, 1, dz);
                if (level.getBlockState(p).getBlock() instanceof StandingSignBlock
                        && level.getBlockState(p.below()).getBlock() instanceof FenceBlock) return p;
            }
        }
        return null;
    }

    /**
     * The sign: on the fence post beside the head (the nearest to it, of those not over the way down), its
     * face to whoever comes to the head. Out of the stores (a sign, or two planks). False if there is no
     * post for it yet, or the stores cannot pay.
     */
    private static boolean sign(ServerLevel level, Villages.Village v, List<BlockPos> top, boolean free) {
        BlockPos head = top.get(0);
        Direction way = down(top);
        BlockPos best = null;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos p = head.offset(dx, 0, dz);
                if (!(level.getBlockState(p).is(BlockTags.WOODEN_FENCES)) || !level.getBlockState(p.above()).canBeReplaced()) continue;
                if (best == null || (dx == 0 || dz == 0) && !(best.getX() == head.getX() || best.getZ() == head.getZ())) best = p;
            }
        }
        if (best == null) return false;
        if (!free && !paid(level, v, head, "putting up the mine's sign", A_SIGN)) return false;
        BlockPos at = best.above();
        level.setBlock(at, Blocks.SPRUCE_SIGN.defaultBlockState()
            .setValue(StandingSignBlock.ROTATION, RotationSegment.convertToSegment(way.getOpposite())), 3);
        if (level.getBlockEntity(at) instanceof SignBlockEntity s) {
            String[] lines = { "The mine of", Villages.name(v.id()), "Mind the stairs", "" };
            SignText f = new SignText();
            for (int i = 0; i < 4; i++) f = f.setMessage(i, Component.literal(lines[i]));
            s.setText(f, true);
            s.setWaxed(true);
        }
        return true;
    }

    /**
     * A length of the mine's own fence where a miner is cutting stairs: at a stair head's level, round a
     * head in its village's mine. Its to take down (MineGoal.allowed): new stairs off an old head may run
     * where the fence round the old ones stands, and the fence goes up again round the new.
     */
    public static boolean fenceInTheWay(AssistantEntity a, BlockPos pos) {
        if (!(a instanceof VillageFolkEntity f) || f.ownerId() == null || !(a.level() instanceof ServerLevel level)) return false;
        if (!level.getBlockState(pos).is(BlockTags.WOODEN_FENCES) || !TownMine.inMine(f.ownerId(), pos)) return false;
        BlockPos c = TownMine.faceCentre(f.ownerId(), pos);
        BlockPos head = c == null ? null : MineStairs.head(level, MineStairs.plotKey(c));
        return head != null && head.getY() == pos.getY()
            && Math.max(Math.abs(head.getX() - pos.getX()), Math.abs(head.getZ() - pos.getZ())) <= ROUND + 1;
    }

    // ------------------------------------------------------------------ for the books

    /** Who is below ground in the mine, by name and trade, and the stair heads fenced (TownMine.report). */
    public static List<String> report(UUID village) {
        List<String> out = new ArrayList<>();
        ServerLevel level = null;
        List<String> down = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || !(f.level() instanceof ServerLevel s)) continue;
            level = s;
            BlockPos at = f.blockPosition();
            if (!below(s, village, at)) continue;
            Job j = f.peekJob();
            boolean mineJob = j != null && j.type() == Job.Type.MINE;
            String doing = mineJob && MineStairs.OUT.equals(j.arg()) ? "climbing out, "
                : mineJob && f.stationTask() == AssistantEntity.StationTask.MINE ? "at work, " : "";
            down.add(f.displayNameCap() + " the " + f.tradeTitle().toLowerCase(Locale.ROOT) + " (" + doing + "Y" + at.getY() + ")");
        }
        out.add("Below ground in the mine: " + (down.isEmpty() ? "none" : down.size() + ": " + String.join(", ", down)) + ".");
        if (level != null) {
            int heads = 0, open = 0, signed = 0;
            for (List<BlockPos> top : MineStairs.tops(level, 2)) {
                BlockPos head = top.get(0);
                if (!TownMine.inMine(village, head) || !Land.areaLoaded(level, head, ROUND + 2) || holes(level, head).isEmpty()) continue;
                heads++;                                                   // stairs whose top is open to the sky
                if (!spots(level, top).isEmpty()) open++;
                if (signAt(level, top) != null) signed++;
            }
            if (heads > 0) {
                out.add("Stairs into the mine: " + heads + ", " + (open == 0 ? "all fenced round" : open + " still open at the side")
                    + ", " + signed + " with the sign up.");
            }
        }
        int boats = Aboard.steppedOut(village);
        if (boats > 0) out.add("Got out of a boat or a cart they never meant to board: " + boats + " times.");
        return out;
    }

    /** Tests: the stair heads fenced now, for nothing, with no limit. */
    public static int fenceForTests(ServerLevel level, Villages.Village v) {
        return fence(level, v, true, 10000);
    }

    /** Tests: the spots round this staircase still wanting a fence. */
    public static List<BlockPos> spotsForTests(ServerLevel level, List<BlockPos> top) {
        return spots(level, top);
    }

    /** Tests: the sign by this stair head, or null. */
    @Nullable
    public static BlockPos signForTests(ServerLevel level, List<BlockPos> top) {
        return signAt(level, top);
    }
}
