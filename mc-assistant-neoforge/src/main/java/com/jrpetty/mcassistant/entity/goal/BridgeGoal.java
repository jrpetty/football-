package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * "bridge forward" — walks the direction it's facing, placing floor blocks
 * under each next step until it reaches solid ground on the far side (or
 * runs out of blocks). Works over ravines, rivers, even lava.
 */
public class BridgeGoal extends Goal {

    private static final int MAX_LENGTH = 48;

    private final AssistantEntity assistant;
    @Nullable private Job job;
    private Direction dir = Direction.NORTH;
    private BlockPos cursor = BlockPos.ZERO;
    @Nullable private BlockPos moveTarget;
    private int steps;
    private int placedCount;
    private int moveStuck;
    private int workTicks;
    private int myGen;

    public BridgeGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.BRIDGE && assistant.getTarget() == null;
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
        this.dir = Direction.fromYRot(assistant.getYRot());
        this.cursor = assistant.feetPos();
        this.moveTarget = null;
        this.steps = 0;
        this.placedCount = 0;
        this.moveStuck = 0;
        this.workTicks = 0;
        if (assistant.countMatching(BuildGoal::isBuildingBlock) == 0) {
            finish("I need building blocks to bridge with — planks, cobble, or dirt.");
            return;
        }
        assistant.say("Bridging " + dir.getName() + " — I'll stop at solid ground.");
    }

    @Override
    public void stop() {
        this.job = null;
        this.moveTarget = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.noteJobOutcome(placedCount > 0);
        assistant.pollJob();
        this.job = null;
        this.moveTarget = null;
        assistant.getNavigation().stop();
    }

    private boolean solidFloor(BlockPos feet) {
        BlockPos floor = feet.below();
        return assistant.level().getBlockState(floor)
            .isFaceSturdy(assistant.level(), floor, Direction.UP);
    }

    @Override
    public void tick() {
        if (job == null) return;

        // Walking onto the step we just secured.
        if (moveTarget != null) {
            double distSq = assistant.distanceToSqr(
                moveTarget.getX() + 0.5, moveTarget.getY(), moveTarget.getZ() + 0.5);
            if (distSq < 2.25) {
                cursor = moveTarget;
                moveTarget = null;
                steps++;
            } else {
                if (assistant.getNavigation().isDone()) {
                    assistant.getNavigation().moveTo(
                        moveTarget.getX() + 0.5, moveTarget.getY(), moveTarget.getZ() + 0.5, 1.0D);
                }
                if (++moveStuck > 100) {
                    finish("Got stuck on the bridge — placed " + placedCount + " blocks.");
                }
                return;
            }
        }

        if (steps >= MAX_LENGTH) {
            finish("That's as far as I bridge in one go — " + placedCount + " blocks placed.");
            return;
        }

        BlockPos next = cursor.relative(dir);
        // A wall ahead means we've arrived (or there was no gap to begin with).
        if (!assistant.level().getBlockState(next).canBeReplaced()
            || !assistant.level().getBlockState(next.above()).canBeReplaced()) {
            finish(placedCount > 0
                ? "Reached the other side — " + placedCount + " blocks."
                : "There's no gap ahead to bridge.");
            return;
        }
        if (solidFloor(next)) {
            if (placedCount > 0) {
                finish("Reached solid ground — bridge is " + placedCount + " blocks long.");
                return;
            }
            // Still on land — keep walking to the edge.
            moveTarget = next;
            moveStuck = 0;
            return;
        }

        // The next step hangs over the void: place its floor, then step out.
        if (++workTicks < 8) return;
        workTicks = 0;
        BlockState state = takeBlock();
        if (state == null) {
            finish("Out of blocks — bridge is " + placedCount + " long. Bring me more and I'll continue.");
            return;
        }
        assistant.getLookControl().setLookAt(
            next.getX() + 0.5, next.getY() - 0.5, next.getZ() + 0.5);
        assistant.level().setBlockAndUpdate(next.below(), state);
        assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        placedCount++;
        moveTarget = next;
        moveStuck = 0;
    }

    @Nullable
    private BlockState takeBlock() {
        var inv = assistant.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (BuildGoal.isBuildingBlock(s)) {
                BlockState state = ((BlockItem) s.getItem()).getBlock().defaultBlockState();
                s.shrink(1);
                if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
                return state;
            }
        }
        return null;
    }
}
