package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * [film39] Stepping into the cage and out of it (MineLift). The riders used to vanish from the corner of the stair and
 * appear in it the tick it set off (and pop out onto the stair the tick it came up): seen from the pithead, a miner walked
 * away from the cage and was snatched into it. Now each rider walks the last few steps.
 * <ul>
 * <li>At the pithead the cage hangs open in the headframe: from where it waited to the stair's head, out to the opening
 *     between two posts of the headframe nearest it, and in to the middle of the cage; coming up, the same the other way
 *     to where it steps off.</li>
 * <li>[tp43] At a stop below, the well is behind the bars of the stop's window: the window is swung open (its bars taken
 *     out, with the clank of a gate) while the riders step through it from the landing's corner, and shut behind them;
 *     the same coming out. They were popped through the bars before, the commonest jump of a miner in a player's sight.</li>
 * </ul>
 * Never a player (carried as before, the game's own movement being the player's): a player riding with folk is put in
 * the cage as before while the folk with it walk in.
 */
final class LiftGlide {

    private LiftGlide() {}

    /** Blocks a tick a rider walks in or out (a folk's walk). */
    static final double PACE = 0.22;

    /** Do these riders step in (or out) on foot at this stop? (Any folk among them does.) */
    static boolean onFoot(ServerLevel level, MineLift.Lift l, MineLift.Stop s) {
        if (l.riding.isEmpty()) return false;
        for (MineLift.Ask a : l.riding) {
            Entity e = level.getEntity(a.who);
            if (e != null && !(e instanceof Player)) return true;
        }
        return false;
    }

    /** Where each rider starts its walk, noted as the walk begins; a stop's window opened below the pithead. */
    static void begin(ServerLevel level, MineLift.Lift l, MineLift.Stop s) {
        l.glideFrom.clear();
        l.step = 0;
        for (MineLift.Ask a : l.riding) {
            Entity e = level.getEntity(a.who);
            if (e != null) l.glideFrom.put(a.who, e.position());
        }
        if (s.index() != 0) gate(level, l, s, true);
    }

    /** A tick of the riders' walk into the cage at this stop; true once all of them stand in it (the window shut again). */
    static boolean in(ServerLevel level, MineLift.Lift l, MineLift.Stop s) {
        Vec3 well = new Vec3(l.sx + 0.5, s.y(), l.sz + 0.5);
        Vec3 board = Vec3.atBottomCenterOf(s.board());
        boolean all = walk(level, l, p -> s.index() == 0 ? new Vec3[]{ p, board, gap(l, s, board), well }
            : new Vec3[]{ p, board, corner(l, s), well });
        if (all && s.index() != 0) gate(level, l, s, false);
        return all;
    }

    /** A tick of the riders' walk out of the cage to where they step off; true once all of them are there. */
    static boolean out(ServerLevel level, MineLift.Lift l, MineLift.Stop s, BlockPos off) {
        Vec3 well = new Vec3(l.sx + 0.5, s.y(), l.sz + 0.5);
        Vec3 to = new Vec3(off.getX() + 0.5, off.getY(), off.getZ() + 0.5);
        boolean all = walk(level, l, p -> s.index() == 0 ? new Vec3[]{ well, gap(l, s, to), to }
            : new Vec3[]{ well, corner(l, s), Vec3.atBottomCenterOf(s.board()), to });
        if (all && s.index() != 0) gate(level, l, s, false);
        return all;
    }

    /** Two blocks out from the opening between the headframe's posts nearest this spot (the cage's own side of the pillar). */
    static Vec3 gap(MineLift.Lift l, MineLift.Stop s, Vec3 near) {
        Vec3 best = null;
        double bestD = Double.MAX_VALUE;
        for (int[] d : new int[][]{ { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }) {
            Vec3 c = new Vec3(l.sx + 0.5 + 2 * d[0], s.y(), l.sz + 0.5 + 2 * d[1]);
            double dd = c.distanceToSqr(near.x, s.y(), near.z);
            if (dd < bestD) { bestD = dd; best = c; }
        }
        return best;
    }

    /** [tp43] The corner cell of a stop's window (between the landing's corner and the well). */
    static Vec3 corner(MineLift.Lift l, MineLift.Stop s) {
        int[] o = MineWorks.diag((s.index() / 4) % 4);
        return new Vec3(l.sx + o[0] + 0.5, s.y(), l.sz + o[1] + 0.5);
    }

    /** [tp43] The cells of a stop's window: the pillar's corner toward the landing and the two beside it, two high. [tp48] Not
     *  the deepest stop's doorway over the pool, open for good (never barred when the window shuts). */
    static List<BlockPos> window(MineLift.Lift l, MineLift.Stop s) {
        int[] o = MineWorks.diag((s.index() / 4) % 4);
        BlockPos door = MineLift.doorway(l.stops, l.sx, l.sz);
        List<BlockPos> out = new ArrayList<>(6);
        for (int h = 0; h < 2; h++) {
            for (BlockPos c : new BlockPos[]{ new BlockPos(l.sx + o[0], s.y() + h, l.sz + o[1]), new BlockPos(l.sx + o[0], s.y() + h, l.sz),
                    new BlockPos(l.sx, s.y() + h, l.sz + o[1]) }) {
                if (!MineLift.inDoor(door, c)) out.add(c);
            }
        }
        return out;
    }

    /** [tp43] A stop's window swung open (its bars out) or shut (its bars back). */
    static void gate(ServerLevel level, MineLift.Lift l, MineLift.Stop s, boolean open) {
        if (open == (l.gate == s.index())) return;
        for (BlockPos c : window(l, s)) {
            if (open && level.getBlockState(c).is(Blocks.IRON_BARS)) level.setBlock(c, Blocks.AIR.defaultBlockState(), 3);
            else if (!open && level.getBlockState(c).isAir()) {
                level.setBlock(c, Blocks.IRON_BARS.defaultBlockState(), 3);
                level.setBlock(c, Block.updateFromNeighbourShapes(level.getBlockState(c), level, c), 3);
            }
        }
        level.playSound(null, s.board(), open ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 0.8F, 0.9F);
        l.gate = open ? s.index() : -1;
    }

    /** [tp43] Any window left open (the riders gone, a trip cut short) shut. */
    static void shut(ServerLevel level, MineLift.Lift l) {
        if (l.gate < 0) return;
        MineLift.Stop s = l.stop(l.gate);
        if (s != null) gate(level, l, s, false);
        l.gate = -1;
    }

    private static boolean walk(ServerLevel level, MineLift.Lift l, java.util.function.Function<Vec3, Vec3[]> route) {
        l.step++;
        boolean all = true;
        for (MineLift.Ask a : l.riding) {
            Entity e = level.getEntity(a.who);
            if (e == null || !e.isAlive() || e instanceof Player) continue;
            Vec3 start = l.glideFrom.getOrDefault(a.who, e.position());
            Vec3[] p = route.apply(start);
            double length = 0;
            for (int i = 1; i < p.length; i++) length += p[i - 1].distanceTo(p[i]);
            double gone = Math.min(length, l.step * PACE);
            if (gone < length) all = false;
            Vec3 at = p[p.length - 1], ahead = Vec3.ZERO;
            double left = gone;
            for (int i = 1; i < p.length; i++) {
                double seg = p[i - 1].distanceTo(p[i]);
                if (left <= seg || i == p.length - 1) {
                    at = lerp(p[i - 1], p[i], seg < 1.0E-4 ? 1.0 : left / seg);
                    ahead = p[i].subtract(p[i - 1]);
                    break;
                }
                left -= seg;
            }
            // Never more than a step a tick from where it stands (a rider that was not where the walk begins catches up).
            Vec3 here = e.position();
            if (here.distanceTo(at) > 0.6) {
                at = here.add(at.subtract(here).normalize().scale(PACE * 1.5));
                all = false;
            }
            e.setDeltaMovement(Vec3.ZERO);
            e.fallDistance = 0;
            e.setPos(at.x, at.y, at.z);
            if (ahead.horizontalDistanceSqr() > 1.0E-4) {
                float yaw = (float) Math.toDegrees(Math.atan2(-ahead.x, ahead.z));
                e.setYRot(yaw);
                if (e instanceof LivingEntity le) { le.yBodyRot = yaw; le.yHeadRot = yaw; }
            }
            if (e instanceof Mob m) m.getNavigation().stop();
        }
        return all;
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        t = Mth.clamp(t, 0.0, 1.0);
        return new Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
    }
}
