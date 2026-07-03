package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Map;

/**
 * Farming: find mature crops within range, harvest them, sweep the drops,
 * and replant the same crop from collected seeds when the farmland is still
 * there. Runs as a one-shot job ("tend the farm") and as the FARMER role's
 * idle work — the town's renewable food loop.
 */
public class FarmGoal extends Goal {

    private static final int RANGE = 14;

    // Crop block -> what replants it.
    private static final Map<Block, Item> SEEDS = Map.of(
        Blocks.WHEAT, Items.WHEAT_SEEDS,
        Blocks.CARROTS, Items.CARROT,
        Blocks.POTATOES, Items.POTATO,
        Blocks.BEETROOTS, Items.BEETROOT_SEEDS);

    private final AssistantEntity assistant;
    private boolean active;
    @Nullable private BlockPos targetPos;
    private int harvested;
    private int workTicks;
    private int stuckTicks;
    private int emptyScans;
    private int myGen;

    public FarmGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.FARM && assistant.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return active && assistant.getTarget() == null && assistant.taskGen() == myGen;
    }

    @Override
    public void start() {
        this.myGen = assistant.taskGen();
        this.active = true;
        this.harvested = 0;
        this.workTicks = 0;
        this.stuckTicks = 0;
        this.emptyScans = 0;
        this.targetPos = null;
        assistant.say("Tending the crops.");
    }

    @Override
    public void stop() {
        this.active = false;
        this.targetPos = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.noteJobOutcome(harvested > 0);
        assistant.pollJob();
        this.active = false;
        this.targetPos = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (!active) return;

        if (targetPos == null || !isMatureCrop(assistant.level().getBlockState(targetPos))) {
            targetPos = findMatureCrop();
            workTicks = 0;
            stuckTicks = 0;
            if (targetPos == null) {
                if (++emptyScans >= 2) {
                    finish(harvested > 0
                        ? "Farm's tended — harvested and replanted " + harvested + " crops."
                        : "No ripe crops within " + RANGE + " blocks of me.");
                }
                return;
            }
            emptyScans = 0;
        }

        double distSq = assistant.getEyePosition().distanceToSqr(
            targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
        assistant.getLookControl().setLookAt(
            targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

        if (distSq > AssistantEntity.BLOCK_REACH * AssistantEntity.BLOCK_REACH) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(
                    targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, 1.1D);
            }
            if (++stuckTicks > 100) {
                targetPos = null; // skip unreachable plot
            }
            return;
        }

        if (++workTicks < 10) {
            if (workTicks % 5 == 0) assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            return;
        }

        BlockPos pos = targetPos;
        targetPos = null;
        workTicks = 0;
        BlockState state = assistant.level().getBlockState(pos);
        Block cropBlock = state.getBlock();
        if (assistant.level().destroyBlock(pos, true, assistant)) {
            harvested++;
            sweepDrops(pos);
            replant(pos, cropBlock);
        }
    }

    private void replant(BlockPos pos, Block cropBlock) {
        Item seed = SEEDS.get(cropBlock);
        if (seed == null) return;
        if (!assistant.level().getBlockState(pos.below()).is(Blocks.FARMLAND)) return;
        if (assistant.removeMatching(s -> s.is(seed), 1) == 1) {
            assistant.level().setBlockAndUpdate(pos, cropBlock.defaultBlockState());
        }
    }

    private void sweepDrops(BlockPos around) {
        for (ItemEntity drop : assistant.level().getEntitiesOfClass(
                ItemEntity.class, new AABB(around).inflate(2.5))) {
            ItemStack leftover = assistant.insertItem(drop.getItem());
            if (leftover.isEmpty()) {
                drop.discard();
            } else {
                drop.setItem(leftover);
            }
        }
    }

    private static boolean isMatureCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    @Nullable
    private BlockPos findMatureCrop() {
        BlockPos feet = assistant.feetPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-RANGE, -3, -RANGE), feet.offset(RANGE, 3, RANGE))) {
            if (!isMatureCrop(assistant.level().getBlockState(pos))) continue;
            double d = pos.distSqr(feet);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best;
    }
}
