package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Boat travel (EXPERIMENTAL prototype): to reach a destination across water,
 * the assistant places a boat it carries, boards it, and is driven toward the
 * target across the surface; it dismounts and picks the boat back up on
 * arrival (or if it gets stuck on land). AI vehicle control is inherently
 * rough, so this is a best-effort crossing, not polished sailing.
 */
public class BoatGoal extends Goal {

    private final AssistantEntity assistant;
    @Nullable private Job job;
    @Nullable private BlockPos dest;
    @Nullable private Boat boat;
    private int stuckTicks;
    private double lastDistSq;
    private int myGen;

    public BoatGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    private static boolean isBoatItem(ItemStack s) {
        return !s.isEmpty()
            && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_boat");
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.BOAT && assistant.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && assistant.getTarget() == null
            && assistant.taskGen() == myGen && assistant.peekJob() == job;
    }

    @Override
    public void start() {
        this.job = assistant.peekJob();
        this.myGen = assistant.taskGen();
        this.boat = null;
        this.stuckTicks = 0;
        this.lastDistSq = Double.MAX_VALUE;
        this.dest = job != null ? TravelGoal.parseCoords(job.arg()) : null;
        if (dest == null) {
            finish("Tell me where to sail — coordinates like \"boat to 200 63 -400\".");
            return;
        }
        if (assistant.countMatching(BoatGoal::isBoatItem) == 0) {
            finish("I need a boat to sail — craft one or hand me one.");
            return;
        }
        assistant.say("Looking for water to launch toward " + dest.getX() + " " + dest.getZ() + ".");
    }

    @Override
    public void stop() {
        dismountAndRecover();
        this.job = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        dismountAndRecover();
        assistant.pollJob();
        this.job = null;
        this.dest = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (job == null || dest == null) return;

        // Already there?
        double flatDistSq = flatDistSq(assistant.getX(), assistant.getZ());
        if (flatDistSq < 9.0) {
            finish("Arrived by boat.");
            return;
        }

        if (boat == null || !boat.isAlive() || !assistant.isPassenger()) {
            approachAndLaunch();
            return;
        }

        // Sailing: drive the boat toward the destination across the surface.
        Vec3 dir = new Vec3(dest.getX() + 0.5 - boat.getX(), 0, dest.getZ() + 0.5 - boat.getZ());
        if (dir.lengthSqr() > 0.01) {
            dir = dir.normalize();
            boat.setDeltaMovement(dir.x * 0.35, boat.getDeltaMovement().y, dir.z * 0.35);
            boat.setYRot((float) (Math.atan2(dir.z, dir.x) * (180.0 / Math.PI)) - 90.0F);
        }

        // Stuck (beached, or no progress) — bail and continue on foot.
        double dsq = flatDistSq(boat.getX(), boat.getZ());
        if (dsq < lastDistSq - 0.25) {
            lastDistSq = dsq;
            stuckTicks = 0;
        } else if (++stuckTicks > 100) {
            finish("The boat's stuck — I'll carry on from here.");
        }
    }

    /** Walk to a water edge and launch, or keep walking toward the target. */
    private void approachAndLaunch() {
        BlockPos launch = findLaunchWater();
        if (launch == null) {
            // No water adjacent — walk toward the destination on foot a bit.
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(dest.getX() + 0.5, assistant.getY(), dest.getZ() + 0.5, 1.1D);
            }
            if (++stuckTicks > 200) {
                finish("Couldn't find water to launch — I'll travel on land instead.");
            }
            return;
        }
        double d = assistant.distanceToSqr(launch.getX() + 0.5, launch.getY(), launch.getZ() + 0.5);
        if (d > 4.0) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(launch.getX() + 0.5, launch.getY(), launch.getZ() + 0.5, 1.1D);
            }
            return;
        }
        launchBoat(launch);
    }

    private void launchBoat(BlockPos water) {
        if (assistant.countMatching(BoatGoal::isBoatItem) == 0) {
            finish("Lost my boat somewhere — carrying on on foot.");
            return;
        }
        Boat b = EntityType.BOAT.create(assistant.level());
        if (b == null) {
            finish("Couldn't launch the boat.");
            return;
        }
        b.setPos(water.getX() + 0.5, water.getY() + 0.1, water.getZ() + 0.5);
        assistant.level().addFreshEntity(b);
        if (assistant.startRiding(b)) {
            assistant.removeMatching(BoatGoal::isBoatItem, 1);
            this.boat = b;
            this.stuckTicks = 0;
            this.lastDistSq = Double.MAX_VALUE;
            assistant.say("Setting sail.");
        } else {
            b.discard();
            finish("Couldn't board the boat — going on foot.");
        }
    }

    private void dismountAndRecover() {
        if (assistant.isPassenger()) assistant.stopRiding();
        if (boat != null && boat.isAlive()) {
            ItemStack leftover = assistant.insertItem(new ItemStack(Items.OAK_BOAT));
            if (!leftover.isEmpty()) assistant.spawnAtLocation(leftover);
            boat.discard();
        }
        boat = null;
    }

    @Nullable
    private BlockPos findLaunchWater() {
        BlockPos feet = assistant.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-6, -3, -6), feet.offset(6, 2, 6))) {
            if (!assistant.level().getFluidState(pos).is(FluidTags.WATER)) continue;
            if (!assistant.level().getBlockState(pos.above()).isAir()) continue;
            // Prefer water that's toward the destination.
            double d = pos.distSqr(new BlockPos(dest.getX(), pos.getY(), dest.getZ()));
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    private double flatDistSq(double x, double z) {
        double dx = dest.getX() + 0.5 - x;
        double dz = dest.getZ() + 0.5 - z;
        return dx * dx + dz * dz;
    }
}
