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
 * Farming, from scratch if need be. First it tends any ripe crops (harvest +
 * replant). If there's nothing to tend, it BOOTSTRAPS a farm: gathers seeds by
 * breaking grass, tills nearby dirt into farmland, and plants a plot — so a
 * companion dropped anywhere can establish renewable food with no prior farm.
 * Runs as a one-shot job ("tend the farm"), the FARMER role's idle work, and
 * the survival brain's food-security step.
 */
public class FarmGoal extends Goal {

    private static final int RANGE = 12;
    private static final int PLOT_TARGET = 9; // plant up to a 3x3-ish plot per run

    private enum Mode { HARVEST, TILL, SEEDS, WATER }

    // Ripe crop block -> seed that replants it.
    private static final Map<Block, Item> REPLANT = Map.of(
        Blocks.WHEAT, Items.WHEAT_SEEDS,
        Blocks.CARROTS, Items.CARROT,
        Blocks.POTATOES, Items.POTATO,
        Blocks.BEETROOTS, Items.BEETROOT_SEEDS);

    // Seed we carry -> crop block it grows.
    private static final Map<Item, Block> PLANT = Map.of(
        Items.WHEAT_SEEDS, Blocks.WHEAT,
        Items.CARROT, Blocks.CARROTS,
        Items.POTATO, Blocks.POTATOES,
        Items.BEETROOT_SEEDS, Blocks.BEETROOTS);

    private final AssistantEntity assistant;
    private boolean active;
    private Mode mode = Mode.HARVEST;
    @Nullable private BlockPos targetPos;
    private int harvested;
    private int planted;
    private int workTicks;
    private int stuckTicks;
    private int emptyScans;
    private int myGen;
    private final java.util.Set<BlockPos> skip = new java.util.HashSet<>(); // blocks we couldn't reach

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
        this.planted = 0;
        this.workTicks = 0;
        this.stuckTicks = 0;
        this.emptyScans = 0;
        this.targetPos = null;
        this.mode = Mode.HARVEST;
        this.skip.clear();
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
        assistant.noteJobOutcome(harvested > 0 || planted > 0);
        assistant.pollJob();
        this.active = false;
        this.targetPos = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (!active) return;

        if (targetPos == null || !targetStillValid()) {
            pickTarget();
            workTicks = 0;
            stuckTicks = 0;
            if (targetPos == null) {
                if (++emptyScans >= 2) finish(summary());
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
                if (targetPos != null) skip.add(targetPos.immutable()); // don't re-pick it
                targetPos = null;
            }
            return;
        }

        if (++workTicks < 10) {
            if (workTicks % 5 == 0) assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            return;
        }
        workTicks = 0;
        BlockPos pos = targetPos;
        targetPos = null;
        switch (mode) {
            case HARVEST -> doHarvest(pos);
            case SEEDS -> doBreakGrass(pos);
            case TILL -> doTillAndPlant(pos);
            case WATER -> doPlaceWater(pos);
        }
    }

    /** Prefer tending ripe crops; else bootstrap a plot. When bootstrapping we
     *  favour ground that's already within range of water so the crops stay
     *  hydrated; failing that, if we're carrying a water bucket we carve a
     *  contained source; otherwise we dry-farm any tillable ground. */
    private void pickTarget() {
        targetPos = findMatureCrop();
        if (targetPos != null) { mode = Mode.HARVEST; return; }
        if (planted < PLOT_TARGET && hasPlantable()) {
            BlockPos water = findWater();
            if (water != null) {
                targetPos = findTillableNear(water);
                if (targetPos != null) { mode = Mode.TILL; return; }
            }
            if (water == null && hasWaterBucket()) {
                targetPos = findWaterSpot();
                if (targetPos != null) { mode = Mode.WATER; return; }
            }
            targetPos = findTillable();
            if (targetPos != null) { mode = Mode.TILL; return; }
        }
        if (!hasPlantable()) {
            targetPos = findGrass();
            if (targetPos != null) { mode = Mode.SEEDS; return; }
        }
        targetPos = null;
    }

    private boolean targetStillValid() {
        if (targetPos == null) return false;
        BlockState st = assistant.level().getBlockState(targetPos);
        return switch (mode) {
            case HARVEST -> isMatureCrop(st);
            case SEEDS -> isGrass(st);
            case TILL -> isTillable(targetPos);
            case WATER -> isTillable(targetPos);
        };
    }

    private void doHarvest(BlockPos pos) {
        BlockState state = assistant.level().getBlockState(pos);
        Block cropBlock = state.getBlock();
        if (assistant.level().destroyBlock(pos, true, assistant)) {
            harvested++;
            sweepDrops(pos);
            Item seed = REPLANT.get(cropBlock);
            if (seed != null && assistant.level().getBlockState(pos.below()).is(Blocks.FARMLAND)
                && assistant.removeMatching(s -> s.is(seed), 1) == 1) {
                assistant.level().setBlockAndUpdate(pos, cropBlock.defaultBlockState());
            }
        }
    }

    private void doBreakGrass(BlockPos pos) {
        if (assistant.level().destroyBlock(pos, true, assistant)) {
            sweepDrops(pos); // grass drops wheat seeds ~1/8 of the time
        }
    }

    private void doTillAndPlant(BlockPos pos) {
        // Till the ground into farmland, then plant the first seed we carry.
        assistant.level().setBlockAndUpdate(pos, Blocks.FARMLAND.defaultBlockState());
        for (Map.Entry<Item, Block> e : PLANT.entrySet()) {
            if (assistant.removeMatching(s -> s.is(e.getKey()), 1) == 1) {
                assistant.level().setBlockAndUpdate(pos.above(), e.getValue().defaultBlockState());
                planted++;
                if (planted == 1) assistant.say("No farm here — starting one from scratch.");
                return;
            }
        }
    }

    /** Carve a 1-deep basin at farmland level and set a contained water source
     *  so the surrounding tilled plot stays hydrated; convert the bucket to empty. */
    private void doPlaceWater(BlockPos pos) {
        if (assistant.removeMatching(s -> s.is(Items.WATER_BUCKET), 1) != 1) return;
        assistant.level().destroyBlock(pos, false);
        assistant.level().setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
        ItemStack leftover = assistant.insertItem(new ItemStack(Items.BUCKET));
        if (!leftover.isEmpty()) {
            assistant.level().addFreshEntity(new ItemEntity(assistant.level(),
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, leftover));
        }
        assistant.say("Set a water source — the plot will stay watered now.");
    }

    private String summary() {
        if (harvested > 0 && planted > 0) return "Farm's tended — harvested " + harvested + ", planted " + planted + ".";
        if (harvested > 0) return "Farm's tended — harvested and replanted " + harvested + " crops.";
        if (planted > 0) return "Planted a new " + planted + "-crop plot — food on the way.";
        return "No crops to tend, and no seeds or plantable ground nearby.";
    }

    private void sweepDrops(BlockPos around) {
        for (ItemEntity drop : assistant.level().getEntitiesOfClass(
                ItemEntity.class, new AABB(around).inflate(2.5))) {
            ItemStack leftover = assistant.insertItem(drop.getItem());
            if (leftover.isEmpty()) drop.discard();
            else drop.setItem(leftover);
        }
    }

    private boolean hasPlantable() {
        for (Item seed : PLANT.keySet()) {
            if (assistant.countMatching(s -> s.is(seed)) > 0) return true;
        }
        return false;
    }

    private boolean hasWaterBucket() {
        return assistant.countMatching(s -> s.is(Items.WATER_BUCKET)) > 0;
    }

    /** Farmland is hydrated by water within 4 blocks horizontally and no more
     *  than one level above — mirror that when choosing where to till. */
    private boolean withinHydration(BlockPos farm, BlockPos water) {
        return Math.abs(farm.getX() - water.getX()) <= 4
            && Math.abs(farm.getZ() - water.getZ()) <= 4
            && (water.getY() - farm.getY()) >= 0 && (water.getY() - farm.getY()) <= 1;
    }

    private static boolean isMatureCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    private static boolean isGrass(BlockState state) {
        return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
            || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN);
    }

    /** Dirt/grass with open space above that we can turn into farmland. */
    private boolean isTillable(BlockPos pos) {
        BlockState g = assistant.level().getBlockState(pos);
        if (!(g.is(Blocks.DIRT) || g.is(Blocks.GRASS_BLOCK) || g.is(Blocks.COARSE_DIRT))) return false;
        return assistant.level().getBlockState(pos.above()).canBeReplaced();
    }

    @Nullable
    private BlockPos findMatureCrop() {
        return nearest(pos -> isMatureCrop(assistant.level().getBlockState(pos)));
    }

    @Nullable
    private BlockPos findGrass() {
        return nearest(pos -> isGrass(assistant.level().getBlockState(pos)));
    }

    @Nullable
    private BlockPos findTillable() {
        return nearest(this::isTillable);
    }

    @Nullable
    private BlockPos findTillableNear(BlockPos water) {
        return nearest(pos -> isTillable(pos) && withinHydration(pos, water));
    }

    @Nullable
    private BlockPos findWater() {
        return nearest(pos -> assistant.level().getBlockState(pos).is(Blocks.WATER));
    }

    /** A dirt block whose neighbours are also tillable — a good centre for a
     *  hand-placed water source. */
    @Nullable
    private BlockPos findWaterSpot() {
        return nearest(pos -> isTillable(pos)
            && isTillable(pos.north()) && isTillable(pos.south())
            && isTillable(pos.east()) && isTillable(pos.west()));
    }

    @Nullable
    private BlockPos nearest(java.util.function.Predicate<BlockPos> match) {
        BlockPos feet = assistant.feetPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-RANGE, -3, -RANGE), feet.offset(RANGE, 3, RANGE))) {
            if (skip.contains(pos)) continue;     // couldn't reach it earlier
            if (!assistant.inZone(pos)) continue; // stay inside the marked work zone
            if (!match.test(pos)) continue;
            double d = pos.distSqr(feet);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best;
    }
}
