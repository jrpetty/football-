package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Building from blueprints: wall, platform, shelter — placed block by block
 * (bottom-up) from whatever building blocks it carries (planks, logs, cobble,
 * stone, dirt). The blueprint anchors a few blocks ahead of where the
 * assistant stands and faces when the job starts. This is the kernel the
 * town-building layer grows on.
 */
public class BuildGoal extends Goal {

    public static final Set<String> STRUCTURES = Set.of("wall", "platform", "shelter");

    private final AssistantEntity assistant;
    @Nullable private Job job;
    private final List<BlockPos> plan = new ArrayList<>();
    private int cursor;
    private int placed;
    private int workTicks;
    private int stuckTicks;
    private int myGen;

    public BuildGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    public static boolean isBuildingBlock(ItemStack s) {
        if (s.isEmpty() || !(s.getItem() instanceof BlockItem)) return false;
        return s.is(ItemTags.PLANKS) || s.is(ItemTags.LOGS)
            || s.is(Blocks.COBBLESTONE.asItem()) || s.is(Blocks.COBBLED_DEEPSLATE.asItem())
            || s.is(Blocks.STONE.asItem()) || s.is(Blocks.DIRT.asItem());
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.BUILD && assistant.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && assistant.getTarget() == null && assistant.taskGen() == myGen;
    }

    @Override
    public void start() {
        this.job = assistant.peekJob();
        this.myGen = assistant.taskGen();
        this.cursor = 0;
        this.placed = 0;
        this.workTicks = 0;
        this.stuckTicks = 0;
        this.plan.clear();

        String structure = job != null ? job.arg() : null;
        if (structure == null || !STRUCTURES.contains(structure)) {
            finish("I can build: wall, platform, shelter.");
            return;
        }
        Direction facing = Direction.fromYRot(assistant.getYRot());
        this.plan.addAll(layout(structure, assistant.feetPos(), facing));
        // Bottom-up so nothing floats while we work.
        this.plan.sort(Comparator.comparingInt(BlockPos::getY)
            .thenComparingDouble(p -> p.distSqr(assistant.feetPos())));
        int needed = countPending();
        int have = assistant.countMatching(BuildGoal::isBuildingBlock);
        if (needed == 0) {
            finish("Looks already built.");
        } else if (have == 0) {
            finish("I have no building blocks — hand me planks, cobblestone, or dirt (or say \"withdraw cobblestone from the chest\").");
        } else {
            assistant.say("Building a " + structure + " — " + needed + " blocks to place, " + have + " in my pack.");
        }
    }

    @Override
    public void stop() {
        this.job = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.noteJobOutcome(placed > 0);
        assistant.pollJob();
        this.job = null;
        assistant.getNavigation().stop();
    }

    private int countPending() {
        int n = 0;
        for (BlockPos p : plan) {
            if (assistant.level().getBlockState(p).canBeReplaced()) n++;
        }
        return n;
    }

    @Override
    public void tick() {
        if (job == null) return;

        // Advance to the next spot that still needs a block.
        BlockPos target = null;
        while (cursor < plan.size()) {
            BlockPos p = plan.get(cursor);
            BlockState st = assistant.level().getBlockState(p);
            if (st.canBeReplaced() && !assistant.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(p))) {
                target = p;
                break;
            }
            cursor++;
            stuckTicks = 0;
        }
        if (target == null) {
            finish("Done — placed " + placed + " blocks.");
            return;
        }

        double distSq = assistant.getEyePosition().distanceToSqr(
            target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        assistant.getLookControl().setLookAt(
            target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);

        if (distSq > AssistantEntity.BLOCK_REACH * AssistantEntity.BLOCK_REACH) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.1D);
            }
            if (++stuckTicks > 120) {
                cursor++; // can't get there — skip that block
                stuckTicks = 0;
            }
            return;
        }

        if (++workTicks < 6) {
            return;
        }
        workTicks = 0;

        // Pull one building block from the pack and place it.
        ItemStack material = takeBuildingBlock();
        if (material.isEmpty()) {
            finish("Out of building blocks — placed " + placed + " so far.");
            return;
        }
        BlockItem bi = (BlockItem) material.getItem();
        assistant.level().setBlockAndUpdate(target, bi.getBlock().defaultBlockState());
        assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        placed++;
        cursor++;
    }

    private ItemStack takeBuildingBlock() {
        var inv = assistant.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (isBuildingBlock(s)) {
                ItemStack one = s.copyWithCount(1);
                s.shrink(1);
                if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    /** Blueprint geometry, anchored a few blocks ahead of the assistant. */
    private static List<BlockPos> layout(String structure, BlockPos feet, Direction facing) {
        Direction right = facing.getClockWise();
        List<BlockPos> out = new ArrayList<>();
        switch (structure) {
            case "platform" -> {
                BlockPos center = feet.relative(facing, 3);
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        out.add(center.relative(right, dx).relative(facing, dz).below());
                    }
                }
            }
            case "wall" -> {
                BlockPos base = feet.relative(facing, 2);
                for (int w = -2; w <= 2; w++) {
                    for (int h = 0; h <= 2; h++) {
                        out.add(base.relative(right, w).above(h));
                    }
                }
            }
            case "shelter" -> {
                BlockPos center = feet.relative(facing, 4);
                // Perimeter walls, 3 high, with a 1x2 doorway facing the assistant.
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                        BlockPos col = center.relative(right, dx).relative(facing, dz);
                        if (edge) {
                            boolean isDoor = dx == 0 && dz == -2; // the side facing us
                            for (int h = 0; h <= 2; h++) {
                                if (isDoor && h <= 1) continue;
                                out.add(col.above(h));
                            }
                        }
                        // Roof over everything.
                        out.add(col.above(3));
                    }
                }
            }
            default -> { }
        }
        return out;
    }
}
