package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/**
 * [soak40] A folk with the ground closing over its head steps out of it, as anybody would. Blocks go down all over a
 * town (a street raised a step, a wall course, a terrace's floor, the earth under a levelled lot, gravel let down in a
 * drift), and a folk standing where one went down took suffocation damage till it died: a child "at its work in the
 * street, trapped in the ground" on day nineteen of the soak, and twenty-six folk the same way over the last
 * four-hundred-day run. The escape goal digs out, but only every six ticks, gives up after ten seconds and waits
 * twenty, and breaks only what the town lets it; a folk dies inside ten. So at the first hurt from the wall, the
 * nearest open place a step or two away where it can stand, head clear and feet on something (beside it at its own
 * height first, then a block higher, and so on: out of a raised floor onto its top), and it is there instead of hurt. Shut in with nowhere within reach, it takes the hurt
 * as before and the escape goal digs.
 */
public final class StepOut {

    private StepOut() {}

    /** [tp47] Step-outs told in the log so far (the first forty). */
    private static int told;

    /** How far it looks: up to three blocks up, two to the side. */
    static final int UP = 3, SIDE = 2;

    /** Hurt from the wall: stepped out instead (true: the hurt is not taken). */
    public static boolean fromTheWall(VillageFolkEntity f, DamageSource source) {
        if (!source.is(DamageTypes.IN_WALL) || !(f.level() instanceof ServerLevel level) || f.isPassenger()) return false;
        BlockPos to = open(level, f);
        if (to == null) return false;
        if (f.isSleeping()) f.stopSleeping();
        if (told < 40) {                                                    // [tp47] the log: what put it in the wall
            told++;
            net.minecraft.world.level.pathfinder.Path p = f.getNavigation().getPath();
            StringBuilder goals = new StringBuilder();
            for (net.minecraft.world.entity.ai.goal.WrappedGoal g : f.goalsForTests().getAvailableGoals())
                if (g.isRunning()) goals.append(g.getGoal().getClass().getSimpleName()).append(',');
            com.mojang.logging.LogUtils.getLogger().info("[MCA-STEPOUT] {} ({}) at {} in {}: {} | path {} | goals {} | moving {} | led {}", f.getAssistantName(),
                f.stationTask(), String.format("%.2f %.2f %.2f", f.getX(), f.getY(), f.getZ()), level.getBlockState(f.blockPosition()).getBlock().getName().getString(),
                f.brainNoteForBooks(), p == null ? "-" : p.getNextNodeIndex() + "/" + p.getNodeCount() + " to " + p.getTarget().toShortString(), goals,
                f.getDeltaMovement(), Stroll.led(f));
        }
        f.getNavigation().stop();
        Blink.safety(f, "step out of the wall (suffocating)");          // [tp43] a step or two, for its life
        f.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        f.resetFallDistance();
        return true;
    }

    /** The nearest place this folk can stand clear of the wall: at each height from its own up, straight up, then a step aside. */
    @Nullable
    static BlockPos open(ServerLevel level, VillageFolkEntity f) {
        BlockPos at = f.blockPosition();
        for (int dy = 0; dy <= UP; dy++) {
            if (dy > 0 && fits(level, f, at.above(dy))) return at.above(dy);
            for (int r = 1; r <= SIDE; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        BlockPos p = at.offset(dx, dy, dz);
                        if (fits(level, f, p)) return p;
                    }
                }
            }
        }
        return null;
    }

    /** Room for the folk standing here: nothing it would collide with, no fluid at its head, and something under it. */
    static boolean fits(ServerLevel level, VillageFolkEntity f, BlockPos p) {
        if (!level.isLoaded(p)) return false;
        AABB box = f.getDimensions(f.getPose()).makeBoundingBox(p.getX() + 0.5, p.getY(), p.getZ() + 0.5).deflate(1.0E-3);
        if (!level.noCollision(f, box)) return false;
        BlockPos head = BlockPos.containing(p.getX() + 0.5, p.getY() + f.getEyeHeight(), p.getZ() + 0.5);
        if (level.getBlockState(head).isSuffocating(level, head) || !level.getFluidState(head).isEmpty()) return false;
        BlockPos below = p.below();
        return level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP);
    }
}
