package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;

/**
 * Real mining: digs a 1-wide staircase down to a target Y level, then a
 * 2-high gallery, grabbing every ore vein it exposes on the way. It places
 * torches as it goes, bridges over small cavities with carried blocks, and
 * refuses to dig into lava or water (it stops and says so instead of dying).
 * "dig a mine", "mine down to level 12".
 */
public class MineGoal extends Goal {

    private static final int TUNNEL_LENGTH = 24;
    private static final int MAX_VEIN_BLOCKS = 16;
    private static final List<TagKey<Block>> ORE_TAGS = List.of(
        BlockTags.COAL_ORES, BlockTags.IRON_ORES, BlockTags.COPPER_ORES, BlockTags.GOLD_ORES,
        BlockTags.REDSTONE_ORES, BlockTags.LAPIS_ORES, BlockTags.DIAMOND_ORES, BlockTags.EMERALD_ORES);

    private enum Phase { DESCEND, TUNNEL }

    private final AssistantEntity assistant;
    @Nullable private Job job;
    private Phase phase = Phase.DESCEND;
    private Direction dir = Direction.NORTH;
    private BlockPos cursor = BlockPos.ZERO;          // the cell we stand in
    private final ArrayDeque<BlockPos> digQueue = new ArrayDeque<>();
    private final ArrayDeque<BlockPos> veinQueue = new ArrayDeque<>();
    @Nullable private BlockPos currentDig;
    @Nullable private BlockPos moveTarget;
    private int workTicks;
    private int workNeeded;
    private int moveStuck;
    private int tunnelSteps;
    private int sinceTorch;
    private int veinMined;
    private int oresMined;
    private int blocksMined;
    private int myGen;

    public MineGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private static boolean isOre(BlockState state) {
        for (TagKey<Block> tag : ORE_TAGS) {
            if (state.is(tag)) return true;
        }
        return false;
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.MINE && assistant.getTarget() == null;
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
        this.digQueue.clear();
        this.veinQueue.clear();
        this.currentDig = null;
        this.moveTarget = null;
        this.tunnelSteps = 0;
        this.sinceTorch = 0;
        this.veinMined = 0;
        this.oresMined = 0;
        this.blocksMined = 0;
        this.phase = job != null && cursor.getY() <= job.amount() ? Phase.TUNNEL : Phase.DESCEND;

        // No pickaxe, no mine — player rules.
        assistant.equipBestTool(Blocks.STONE.defaultBlockState());
        if (!assistant.getMainHandItem().isCorrectToolForDrops(Blocks.STONE.defaultBlockState())) {
            finish("I need a pickaxe before I can dig a mine — \"craft a wooden pickaxe\".");
            return;
        }
        if (assistant.isPackFull()) {
            finish("Pack's full — let me deposit first.");
            return;
        }
        assistant.say("Digging a mine down to Y" + (job != null ? job.amount() : 12)
            + " — I'll torch it and grab every vein I pass.");
    }

    @Override
    public void stop() {
        if (job != null) {
            assistant.say("Paused mining (" + oresMined + " ore so far).");
        }
        this.job = null;
        this.currentDig = null;
        this.moveTarget = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.noteJobOutcome(oresMined > 0 || blocksMined > 8);
        assistant.pollJob();
        autoSmeltOres(); // turn the raw metal we dug up into ingots automatically
        this.job = null;
        this.currentDig = null;
        this.moveTarget = null;
        assistant.getNavigation().stop();
    }

    /** After a mining run, queue a smelt for any raw iron/gold/copper collected,
     *  so "dig a mine" yields usable ingots without a separate "smelt" order. */
    private void autoSmeltOres() {
        queueSmelt("iron", s -> s.is(Items.RAW_IRON));
        queueSmelt("gold", s -> s.is(Items.RAW_GOLD));
        queueSmelt("copper", s -> s.is(Items.RAW_COPPER));
    }

    private void queueSmelt(String canonical, java.util.function.Predicate<ItemStack> raw) {
        int n = assistant.countMatching(raw);
        if (n > 0) assistant.enqueue(Job.smelt(canonical, n));
    }

    @Override
    public void tick() {
        if (job == null) return;

        // Full pack: stash first, then the player can send us mining again.
        if (assistant.isPackFull()) {
            String msg = "Pack's full — got " + oresMined + " ore blocks. Stashing now.";
            finish(msg);
            assistant.enqueueFront(Job.deposit());
            return;
        }

        if (currentDig != null) {
            digTick();
            return;
        }
        if (!digQueue.isEmpty()) {
            beginDig(digQueue.pollFirst());
            return;
        }
        if (!veinQueue.isEmpty()) {
            BlockPos vein = veinQueue.pollFirst();
            // Only chase veins we can reach from where we stand.
            if (isOre(assistant.level().getBlockState(vein))
                && vein.distSqr(cursor) <= 20.0) {
                beginDig(vein);
            }
            return;
        }
        if (moveTarget != null) {
            moveTick();
            return;
        }

        // Plan the next step.
        if (phase == Phase.DESCEND) {
            if (cursor.getY() <= job.amount()) {
                phase = Phase.TUNNEL;
                assistant.say("At Y" + cursor.getY() + " — opening the gallery.");
                return;
            }
            planStep(cursor.relative(dir).below());
        } else {
            // Stop at the edge of the assigned patch as well as at length — a
            // stationed miner's gallery must not tunnel out from under a
            // neighbour's farm.
            boolean leavingZone = !assistant.inZoneColumn(cursor.relative(dir));
            if (tunnelSteps >= TUNNEL_LENGTH || leavingZone) {
                finish("Mine's done — " + blocksMined + " blocks dug, " + oresMined
                    + " ore collected"
                    + (leavingZone ? " (that's the edge of my patch)." : "."));
                return;
            }
            planStep(cursor.relative(dir));
        }
    }

    /** Queue the digs for one step of shaft/gallery ending at newFeet. */
    private void planStep(BlockPos newFeet) {
        // Never dig into fluids — check every cell we'll open plus what's
        // behind them and under our new floor.
        BlockPos[] cells = phase == Phase.DESCEND
            ? new BlockPos[] { newFeet.above(2), newFeet.above(), newFeet }
            : new BlockPos[] { newFeet.above(), newFeet };
        for (BlockPos cell : cells) {
            if (touchesFluid(cell)) {
                finish("Hit liquid ahead — sealing off and stopping here ("
                    + oresMined + " ore so far).");
                return;
            }
        }
        if (touchesFluid(newFeet.below())) {
            finish("Liquid under the next step — stopping here (" + oresMined + " ore so far).");
            return;
        }

        // Solid footing: bridge small cavities with carried blocks.
        BlockPos floor = newFeet.below();
        BlockState floorState = assistant.level().getBlockState(floor);
        if (!floorState.isFaceSturdy(assistant.level(), floor, Direction.UP)) {
            if (!placeFiller(floor)) {
                finish("The shaft opened into a cavity and I'm out of filler blocks ("
                    + oresMined + " ore so far).");
                return;
            }
        }

        for (BlockPos cell : cells) {
            if (!assistant.level().getBlockState(cell).canBeReplaced()) {
                digQueue.addLast(cell);
            }
        }
        moveTarget = newFeet;
        moveStuck = 0;
    }

    private boolean touchesFluid(BlockPos pos) {
        if (!assistant.level().getFluidState(pos).isEmpty()) return true;
        // Also peek one block beyond in the digging direction.
        return !assistant.level().getFluidState(pos.relative(dir)).isEmpty();
    }

    private boolean placeFiller(BlockPos pos) {
        var inv = assistant.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (BuildGoal.isBuildingBlock(s)) {
                BlockState state = ((net.minecraft.world.item.BlockItem) s.getItem())
                    .getBlock().defaultBlockState();
                s.shrink(1);
                if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
                assistant.level().setBlockAndUpdate(pos, state);
                return true;
            }
        }
        return false;
    }

    private void beginDig(BlockPos pos) {
        BlockState state = assistant.level().getBlockState(pos);
        if (state.canBeReplaced()) return; // already open
        assistant.equipBestTool(state);
        this.currentDig = pos;
        this.workTicks = 0;
        this.workNeeded = assistant.workTicksFor(state);
    }

    private void digTick() {
        BlockPos pos = currentDig;
        if (pos == null) return;
        BlockState state = assistant.level().getBlockState(pos);
        if (state.canBeReplaced()) {
            currentDig = null;
            return;
        }
        assistant.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (++workTicks < workNeeded) {
            if (workTicks % 8 == 0) {
                assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            return;
        }

        boolean ore = isOre(state);
        if (assistant.level().destroyBlock(pos, true, assistant)) {
            blocksMined++;
            if (ore) {
                oresMined++;
                veinMined++;
                assistant.awardXp(2); // fair XP toward enchanting
            }
            assistant.damageHeldTool();
            sweepDrops(pos);
            // Gravel/sand above will fall into the hole — take it down too.
            BlockPos above = pos.above();
            for (int guard = 0; guard < 4
                    && assistant.level().getBlockState(above).getBlock() instanceof FallingBlock; guard++) {
                digQueue.addFirst(above);
                above = above.above();
            }
            // Scan the fresh walls for exposed ore.
            if (veinMined < MAX_VEIN_BLOCKS) {
                for (Direction d : Direction.values()) {
                    BlockPos n = pos.relative(d);
                    if (isOre(assistant.level().getBlockState(n)) && !veinQueue.contains(n)) {
                        veinQueue.addLast(n);
                    }
                }
            }
        }
        currentDig = null;
    }

    private void moveTick() {
        BlockPos dest = moveTarget;
        if (dest == null) return;
        double distSq = assistant.distanceToSqr(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5);
        if (distSq < 2.5) {
            cursor = dest;
            moveTarget = null;
            if (phase == Phase.TUNNEL) tunnelSteps++;
            // Torch the path every few steps so nothing spawns behind us.
            if (++sinceTorch >= 6) {
                sinceTorch = 0;
                BlockPos floor = cursor.below();
                if (assistant.level().getBlockState(cursor).canBeReplaced()
                    && assistant.level().getBlockState(floor).isFaceSturdy(assistant.level(), floor, Direction.UP)
                    && assistant.removeMatching(s -> s.is(Items.TORCH), 1) == 1) {
                    assistant.level().setBlockAndUpdate(cursor, Blocks.TORCH.defaultBlockState());
                }
            }
            return;
        }
        if (assistant.getNavigation().isDone()) {
            assistant.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, 1.0D);
        }
        if (++moveStuck > 100) {
            finish("Got stuck in the shaft — stopping here (" + oresMined + " ore so far).");
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
}
