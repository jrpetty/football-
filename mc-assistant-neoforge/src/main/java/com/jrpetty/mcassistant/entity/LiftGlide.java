package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * [film39] Stepping into the cage and out of it at the pithead (MineLift). The cage hangs open in the headframe there, and
 * the riders used to vanish from the corner of the stair and appear in it the tick it set off (and pop out onto the stair
 * the tick it came up): seen from the pithead, a miner walked away from the cage and was snatched into it. Now each rider
 * walks the last few steps: out from where it waited to the opening between two posts of the headframe nearest it, and in
 * to the middle of the cage; coming up, the same the other way to where it steps off. Only at the pithead: at a stop below,
 * the well is behind the bars of the stop's window, and a rider passes through it as before. Never a player (carried as
 * before, the game's own movement being the player's).
 */
final class LiftGlide {

    private LiftGlide() {}

    /** Blocks a tick a rider walks in or out (a folk's walk). */
    static final double PACE = 0.22;

    /** Do these riders step in (or out) on foot at this stop? */
    static boolean onFoot(ServerLevel level, MineLift.Lift l, MineLift.Stop s) {
        if (s.index() != 0 || l.riding.isEmpty()) return false;
        for (MineLift.Ask a : l.riding) if (level.getEntity(a.who) instanceof Player) return false;
        return true;
    }

    /** Where each rider starts its walk, noted as the walk begins. */
    static void begin(ServerLevel level, MineLift.Lift l) {
        l.glideFrom.clear();
        l.step = 0;
        for (MineLift.Ask a : l.riding) {
            Entity e = level.getEntity(a.who);
            if (e != null) l.glideFrom.put(a.who, e.position());
        }
    }

    /** A tick of the riders' walk into the cage at this stop; true once all of them stand in it. */
    static boolean in(ServerLevel level, MineLift.Lift l, MineLift.Stop s) {
        Vec3 well = new Vec3(l.sx + 0.5, s.y(), l.sz + 0.5);
        return walk(level, l, p -> new Vec3[]{ p, gap(l, s, p), well });
    }

    /** A tick of the riders' walk out of the cage to where they step off; true once all of them are there. */
    static boolean out(ServerLevel level, MineLift.Lift l, MineLift.Stop s, net.minecraft.core.BlockPos off) {
        Vec3 well = new Vec3(l.sx + 0.5, s.y(), l.sz + 0.5);
        Vec3 to = new Vec3(off.getX() + 0.5, off.getY(), off.getZ() + 0.5);
        return walk(level, l, p -> new Vec3[]{ well, gap(l, s, to), to });
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

    private static boolean walk(ServerLevel level, MineLift.Lift l, java.util.function.Function<Vec3, Vec3[]> route) {
        l.step++;
        boolean all = true;
        for (MineLift.Ask a : l.riding) {
            Entity e = level.getEntity(a.who);
            if (e == null || !e.isAlive()) continue;
            Vec3 start = l.glideFrom.getOrDefault(a.who, e.position());
            Vec3[] p = route.apply(start);
            double first = p[0].distanceTo(p[1]), length = first + p[1].distanceTo(p[2]);
            double gone = Math.min(length, l.step * PACE);
            if (gone < length) all = false;
            Vec3 at = gone <= first ? lerp(p[0], p[1], first < 1.0E-4 ? 1.0 : gone / first)
                : lerp(p[1], p[2], length - first < 1.0E-4 ? 1.0 : (gone - first) / (length - first));
            Vec3 ahead = gone <= first ? p[1].subtract(p[0]) : p[2].subtract(p[1]);
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
