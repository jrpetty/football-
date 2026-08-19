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
    // How much gets planted in one run. This was 9 — a 3x3 patch — which read
    // as "planted a handful of seeds and stopped" on any real field. A farmer
    // with seeds and somewhere to put them should keep going.
    private static final int PLOT_TARGET = 64;

    private enum Mode { HARVEST, TILL, SEEDS, WATER }

    // Ripe crop block -> seed that replants it.
    private static final Map<Block, Item> REPLANT = Map.of(
        Blocks.WHEAT, Items.WHEAT_SEEDS,
        Blocks.CARROTS, Items.CARROT,
        Blocks.POTATOES, Items.POTATO,
        Blocks.BEETROOTS, Items.BEETROOT_SEEDS);

    // Seed we carry -> crop block it grows. Melon and pumpkin seeds plant a
    // STEM: the stem stays put for life and sets fruit on the ground beside
    // it, so those two rows of the map are planted once and harvested forever.
    private static final Map<Item, Block> PLANT = Map.of(
        Items.WHEAT_SEEDS, Blocks.WHEAT,
        Items.CARROT, Blocks.CARROTS,
        Items.POTATO, Blocks.POTATOES,
        Items.BEETROOT_SEEDS, Blocks.BEETROOTS,
        Items.MELON_SEEDS, Blocks.MELON_STEM,
        Items.PUMPKIN_SEEDS, Blocks.PUMPKIN_STEM);

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
    private final java.util.Map<Item, Integer> plantedByType = new java.util.HashMap<>();

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
        this.plantedByType.clear();
        assistant.sayRoutine("Tending the crops.");
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
                // Nothing to do with what it is holding is not the same as
                // nothing to do. Before giving up the run, go to the chests on
                // the patch: the usual reason a farmer finds no work is that it
                // is out of seeds, and the seeds are ten blocks away. Only if
                // the chest has nothing either is the field genuinely finished.
                if (assistant.topUpKit()) {
                    pickTarget();
                    if (targetPos != null) { emptyScans = 0; return; }
                }
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
                if (targetPos != null) {
                    skip.add(targetPos.immutable());        // not again this run
                    assistant.noteUnreachable(targetPos);   // nor next run either
                }
                targetPos = null;
            }
            return;
        }

        // Half a second per crop was the farm equivalent of a machine gun. Use
        // the bot's own pace — three seconds for a recruit, down to about one
        // and a half for a well-fed veteran working alongside its crew.
        if (++workTicks < assistant.actionPaceTicks()) {
            if (workTicks % 8 == 0) {
                assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                assistant.workHit(targetPos);   // a worked farm SOUNDS worked
            }
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
        // Gaps first. A bare square of farmland is growth that is not happening,
        // and it stays bare until somebody plants it; a ripe crop keeps until
        // someone gets round to it. So a farmer with seeds in hand fills the
        // holes in its field before taking the harvest.
        if (hasPlantable()) {
            targetPos = findGap();
            if (targetPos != null) { mode = Mode.TILL; return; }
        }
        targetPos = findMatureCrop();
        if (targetPos != null) { mode = Mode.HARVEST; return; }
        if (planted < PLOT_TARGET && hasPlantable()) {
            BlockPos water = findWater();
            if (water != null) {
                targetPos = findTillableNear(water);
                if (targetPos != null) { mode = Mode.TILL; return; }
            }
            if (hasWaterBucket()) {
                // Not just "no water anywhere": a bucket in hand gets spent
                // wherever a big DRY patch of the field sits out of reach of
                // every existing source — proper irrigation, not one hole.
                targetPos = findIrrigationSpot();
                if (targetPos != null) { mode = Mode.WATER; return; }
            }
            // No dry-farming fallback. Farmland with no water within four
            // blocks dries out and reverts to dirt, so tilling it is work that
            // undoes itself — and it left plots of bare dirt all over a zone
            // that looked like the bot had wandered off mid-job. If there is no
            // water and no bucket to make some, the field is not ready and
            // saying so is more use than digging it up.
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
            case HARVEST -> isHarvestable(targetPos, st);
            case SEEDS -> isGrass(st);
            case TILL -> isTillable(targetPos);
            case WATER -> isTillable(targetPos);
        };
    }

    private void doHarvest(BlockPos pos) {
        BlockState state = assistant.level().getBlockState(pos);
        // Never break a crop that is not ready. The target was chosen ripe and
        // re-checked on the way, but this is the only line in the goal that can
        // destroy a growing plant, so it verifies for itself rather than
        // trusting whatever routed it here.
        if (!isHarvestable(pos, state)) {
            targetPos = null;
            return;
        }
        Block cropBlock = state.getBlock();
        if (assistant.level().destroyBlock(pos, true, assistant)) {
            harvested++;
            assistant.note(AssistantEntity.Deed.CROPS_HARVESTED, 1);
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
        // Tilling wears a hoe, like a player's would — so the hoe a stationed
        // farmer asks for is a real, consumable part of running the farm rather
        // than a token in its pack.
        // Never work a square whose space is water. canBeReplaced() says yes to
        // a fluid, so without this a farmer would till the bank of its own pond
        // and plant wheat straight into the source block — destroying the water
        // that was hydrating the field, which is the one block on a farm it must
        // not touch.
        if (!isPlantableSpace(pos.above())) return;

        boolean alreadyTilled = assistant.level().getBlockState(pos).is(Blocks.FARMLAND);
        if (!alreadyTilled) {
            // Only breaking new ground costs a hoe and a till. Re-setting existing
            // farmland would also wipe its moisture level, drying out the plot.
            if (assistant.equipToolNamed("_hoe")) {
                assistant.damageHeldTool();
            }
            assistant.level().setBlockAndUpdate(pos, Blocks.FARMLAND.defaultBlockState());
            assistant.level().playSound(null, pos, net.minecraft.sounds.SoundEvents.HOE_TILL,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        Item seed = pickSeed();
        if (seed != null && assistant.removeMatching(s -> s.is(seed), 1) == 1) {
            assistant.level().setBlockAndUpdate(pos.above(), PLANT.get(seed).defaultBlockState());
            assistant.level().playSound(null, pos.above(), net.minecraft.sounds.SoundEvents.CROP_PLANTED,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
            plantedByType.merge(seed, 1, Integer::sum);
            planted++;
            assistant.note(AssistantEntity.Deed.CROPS_PLANTED, 1);
            if (planted == 1 && !alreadyTilled) assistant.sayRoutine("No farm here — starting one from scratch.");
        }
    }

    /**
     * The field mix is the STOCK mix. Half wheat seeds and half carrots in the
     * station's chests means a field that comes out half and half — the player
     * decides what gets grown by what they stock, not by map iteration order.
     *
     * <p>Picks whichever plantable type is furthest BEHIND its share: stock is
     * counted across pack and linked chests, what's been planted this run is
     * tallied per type, and the biggest deficit goes in the ground next. Only
     * types actually in the pack are candidates — the restock loop is what
     * turns chest stock into pack stock.
     */
    @Nullable
    private Item pickSeed() {
        long totalStock = 0;
        java.util.Map<Item, Integer> stock = new java.util.HashMap<>();
        for (Item type : PLANT.keySet()) {
            int have = assistant.countStocked(s -> s.is(type));
            if (have > 0) { stock.put(type, have); totalStock += have; }
        }
        if (stock.isEmpty()) return null;
        int plantedTotal = 0;
        for (int v : plantedByType.values()) plantedTotal += v;
        Item best = null;
        double bestDeficit = -1e9;
        for (Map.Entry<Item, Integer> e : stock.entrySet()) {
            Item type = e.getKey();
            if (assistant.countMatching(s -> s.is(type)) == 0) continue;  // not in hand yet
            double share = e.getValue() / (double) totalStock;
            double done = plantedTotal == 0 ? 0.0
                : plantedByType.getOrDefault(type, 0) / (double) plantedTotal;
            double deficit = share - done;
            if (deficit > bestDeficit) { bestDeficit = deficit; best = type; }
        }
        return best;
    }

    /** Carve a 1-deep basin at farmland level and set a contained water source
     *  so the surrounding tilled plot stays hydrated; convert the bucket to empty. */
    private void doPlaceWater(BlockPos pos) {
        if (assistant.removeMatching(s -> s.is(Items.WATER_BUCKET), 1) != 1) return;
        assistant.level().destroyBlock(pos, false);
        assistant.level().setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
        assistant.level().playSound(null, pos, net.minecraft.sounds.SoundEvents.BUCKET_EMPTY,
            net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F);
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
            if (leftover.isEmpty()) { drop.discard(); assistant.popSound(); }
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

    /** Everything worth cutting: a ripe crop; a melon or pumpkin FRUIT (the
     *  stem that grew it is never touched, so it just sets another); and
     *  sugar cane standing on more cane — cutting above the root drops the
     *  whole top and the root regrows it, no replanting ever. */
    private boolean isHarvestable(BlockPos pos, BlockState st) {
        if (isMatureCrop(st)) return true;
        if (st.is(Blocks.MELON) || st.is(Blocks.PUMPKIN)) return true;
        return st.is(Blocks.SUGAR_CANE)
            && assistant.level().getBlockState(pos.below()).is(Blocks.SUGAR_CANE);
    }

    private static boolean isGrass(BlockState state) {
        return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
            || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN);
    }

    /** Ground we can plant on: dirt/grass we'd till first, and — crucially —
     *  farmland that is ALREADY tilled but bare. Leaving farmland out meant a
     *  bot handed a real, finished player farm found "nothing plantable" and
     *  idled forever, and any square harvested while the seed stock happened to
     *  be empty became dead ground nothing could ever re-seed. */
    /**
     * Somewhere a crop can actually go: air, or something a plant may replace —
     * but never a fluid. Vanilla's canBeReplaced() returns TRUE for water, so
     * every "is this space free?" test in a farm goal reads a pond as free
     * ground unless it is told otherwise.
     */
    private boolean isPlantableSpace(BlockPos pos) {
        BlockState st = assistant.level().getBlockState(pos);
        return st.canBeReplaced() && st.getFluidState().isEmpty();
    }

    private boolean isTillable(BlockPos pos) {
        BlockState g = assistant.level().getBlockState(pos);
        if (!(g.is(Blocks.DIRT) || g.is(Blocks.GRASS_BLOCK) || g.is(Blocks.COARSE_DIRT)
              || g.is(Blocks.FARMLAND))) return false;
        return isPlantableSpace(pos.above());
    }

    @Nullable
    private BlockPos findMatureCrop() {
        return nearest(pos -> isHarvestable(pos, assistant.level().getBlockState(pos)));
    }

    @Nullable
    private BlockPos findGrass() {
        return nearest(pos -> isGrass(assistant.level().getBlockState(pos)));
    }

    /** A hole in an existing field: farmland already broken, nothing growing on
     *  it, and close enough to water to stay wet. Distinct from tilling new
     *  ground — this is finishing a field rather than starting one. */
    @Nullable
    private BlockPos findGap() {
        BlockPos water = findWater();
        if (water == null) return null;
        return nearest(pos -> assistant.level().getBlockState(pos).is(Blocks.FARMLAND)
            && isPlantableSpace(pos.above())
            && withinHydration(pos, water));
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

    private int irrigationScanTick = -1000;

    /**
     * Where the next water source pays best: the dry-tillable cell whose
     * 9×9 hydration square covers the most other dry cells. One pass collects
     * water and tillable cells; dryness and scoring then run against those
     * lists in memory, so the world is read once however big the field is.
     * Needs a cluster of six-plus to spend a bucket — one damp corner is not
     * worth a source block.
     */
    @Nullable
    private BlockPos findIrrigationSpot() {
        if (assistant.tickCount - irrigationScanTick < 200) return null;
        irrigationScanTick = assistant.tickCount;
        BlockPos feet = assistant.feetPos();
        java.util.List<BlockPos> waterCells = new java.util.ArrayList<>();
        java.util.List<BlockPos> tillables = new java.util.ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-RANGE, -3, -RANGE), feet.offset(RANGE, 3, RANGE))) {
            if (!assistant.inZone(pos)) continue;
            BlockState st = assistant.level().getBlockState(pos);
            if (st.is(Blocks.WATER)) {
                waterCells.add(pos.immutable());
            } else if (isTillable(pos)) {
                tillables.add(pos.immutable());
            }
        }
        java.util.List<BlockPos> dry = new java.util.ArrayList<>();
        outer:
        for (BlockPos t : tillables) {
            for (BlockPos w : waterCells) {
                if (Math.abs(t.getX() - w.getX()) <= 4 && Math.abs(t.getZ() - w.getZ()) <= 4
                    && w.getY() - t.getY() >= 0 && w.getY() - t.getY() <= 1) {
                    continue outer;
                }
            }
            dry.add(t);
        }
        if (dry.size() < 6) return null;
        BlockPos best = null;
        int bestScore = 5;   // strictly more than the threshold-1
        for (BlockPos candidate : dry) {
            int score = 0;
            for (BlockPos other : dry) {
                if (Math.abs(candidate.getX() - other.getX()) <= 4
                    && Math.abs(candidate.getZ() - other.getZ()) <= 4
                    && Math.abs(candidate.getY() - other.getY()) <= 1) {
                    score++;
                }
            }
            if (score > bestScore) { bestScore = score; best = candidate; }
        }
        return best;
    }

    @Nullable
    private BlockPos nearest(java.util.function.Predicate<BlockPos> match) {
        // Spiral outward from the feet, ring by ring, and stop at the first
        // ring with a hit. The old scan tested every position in the whole
        // box for every question asked of it — thousands of block reads to
        // find a crop that was usually two steps away. Worst case (nothing
        // anywhere) costs the same as before; the common case costs a ring.
        BlockPos feet = assistant.feetPos();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int r = 0; r <= RANGE; r++) {
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring only
                    for (int dy = -3; dy <= 3; dy++) {
                        cursor.setWithOffset(feet, dx, dy, dz);
                        if (skip.contains(cursor)) continue;            // this run's failures
                        if (assistant.isUnreachable(cursor)) continue;  // remembered failures
                        if (!assistant.inZone(cursor)) continue;
                        if (!match.test(cursor)) continue;
                        double d = cursor.distSqr(feet);
                        if (d < bestDist) {
                            bestDist = d;
                            best = cursor.immutable();
                        }
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }
}
