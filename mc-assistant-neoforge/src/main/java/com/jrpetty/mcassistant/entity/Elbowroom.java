package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * [vision27] Folk keep a little room between them. Two folk sent to the same spot (a stall, the board, the bench, the
 * middle of the square) both walked onto the very same block and stood in each other, one body through the other,
 * their two names written over each other above one head; the game's own shove between bodies is too weak to part two
 * folk whose legs keep carrying them back to the same spot. Now a folk standing still with another inside its elbows
 * takes a short step aside, onto open ground half a block to a block away (away from the other, or to one side), as a
 * person does in a crowd: once a second at most, and only the one of the two with the later number, so they never
 * both step into the same new place.
 *
 * <p>Not while it sleeps, sits or rides, or is on its way somewhere (only a folk come to a stop), nor out of everybody's
 * sight (FolkLod: nobody to see it, nothing to fix), nor on the game-test server, whose towns are laid out by hand.
 */
public final class Elbowroom {

    private Elbowroom() {}

    /** Closer than this (blocks, between their middles, across the ground) is standing in each other. */
    static final double TOO_CLOSE = 0.5;
    /** How often a folk looks (ticks), each on its own beat. */
    static final int EVERY = 20;

    public static void tick(VillageFolkEntity f) {
        if ((f.tickCount + f.getId()) % EVERY != 0) return;
        if (!(f.level() instanceof ServerLevel level)) return;
        if (!FolkLod.scaling(level) || FolkLod.unseen(f)) return;   // somebody there to see it (and not the tests' bare towns)
        if (f.isSleeping() || f.isPassenger() || f.isNoAi() || f.getPose() == Pose.SITTING || f.getPose() == Pose.CROUCHING) return;
        if (!f.getNavigation().isDone() || f.getDeltaMovement().horizontalDistanceSqr() > 0.0025) return;
        if (!f.onGround() || f.isInWater()) return;
        AABB box = f.getBoundingBox().inflate(TOO_CLOSE, 0.2, TOO_CLOSE);
        List<VillageFolkEntity> near = level.getEntitiesOfClass(VillageFolkEntity.class, box,
            o -> o != f && o.isAlive() && !o.isSleeping() && !o.isPassenger());
        VillageFolkEntity other = null;
        double best = TOO_CLOSE * TOO_CLOSE;
        for (VillageFolkEntity o : near) {
            double dx = o.getX() - f.getX(), dz = o.getZ() - f.getZ();
            double d = dx * dx + dz * dz;
            if (d < best && Math.abs(o.getY() - f.getY()) < 1.0) { best = d; other = o; }
        }
        if (other == null || f.getId() < other.getId()) return;      // the other one is the one to stay
        Vec3 away = new Vec3(f.getX() - other.getX(), 0.0, f.getZ() - other.getZ());
        if (away.lengthSqr() < 1.0E-4) {
            double a = (f.getId() * 2.399963) % (Math.PI * 2.0);    // the same spot exactly: a direction of its own
            away = new Vec3(Math.cos(a), 0.0, Math.sin(a));
        }
        away = away.normalize();
        Vec3 side = new Vec3(-away.z, 0.0, away.x);
        Vec3[] tries = {away, away.add(side).normalize(), away.subtract(side).normalize(), side, side.scale(-1.0)};
        for (double step : new double[] {0.9, 0.6, 1.2}) {
            for (Vec3 d : tries) {
                Vec3 to = f.position().add(d.scale(step));
                if (standable(level, to, f)) {
                    f.getMoveControl().setWantedPosition(to.x, f.getY(), to.z, 0.45);
                    return;
                }
            }
        }
    }

    /** Open ground there: something to stand on, room for its body, and no other folk already in it. */
    private static boolean standable(ServerLevel level, Vec3 at, VillageFolkEntity f) {
        BlockPos feet = BlockPos.containing(at.x, f.getY() + 0.01, at.z);
        if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) return false;
        if (!level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) return false;
        BlockPos below = feet.below();
        if (level.getBlockState(below).getCollisionShape(level, below).isEmpty()) return false;
        if (!level.getFluidState(feet).isEmpty()) return false;
        AABB there = f.getDimensions(f.getPose()).makeBoundingBox(at.x, f.getY(), at.z).deflate(0.05);
        return level.getEntitiesOfClass(VillageFolkEntity.class, there, o -> o != f).isEmpty();
    }
}
