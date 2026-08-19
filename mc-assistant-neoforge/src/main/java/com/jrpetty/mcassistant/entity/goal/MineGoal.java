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

    private enum Phase { DESCEND, TUNNEL, RETURN }

    private final AssistantEntity assistant;
    @Nullable private Job job;
    private Phase phase = Phase.DESCEND;
    private Direction dir = Direction.NORTH;
    private BlockPos cursor = BlockPos.ZERO;          // the cell we stand in
    private final ArrayDeque<BlockPos> digQueue = new ArrayDeque<>();
    private final ArrayDeque<BlockPos> veinQueue = new ArrayDeque<>();
    @Nullable private BlockPos currentDig;
    private boolean saidCapped;   // one liquid announcement per run, not per block
    // Every step of the staircase, in order, so a full pack walks back UP the
    // stairs it dug instead of pathfinding blind through whatever caves the
    // dig happened to breach.
    private final java.util.ArrayList<BlockPos> stairPath = new java.util.ArrayList<>();
    private int returnIndex = -1;
    // Quarry mode: the Y of the gallery being cut right now. Each finished
    // level drops four and turns a quarter, so the shaft comes out as terraced
    // floors down to the depth the player set rather than one lonely tunnel.
    private int levelFloor;
    private int levelsCut;
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
        this.saidCapped = false;
        this.stairPath.clear();
        this.stairPath.add(this.cursor.immutable());   // the shaft head itself
        this.returnIndex = -1;
        this.oresMined = 0;
        this.blocksMined = 0;
        int target = job != null ? job.amount() : cursor.getY();
        // A quarry works DOWN in four-block terraces from just under the
        // surface; a single gallery drops straight to the target and cuts once.
        this.levelFloor = assistant.quarry()
            ? Math.max(target, cursor.getY() - 4) : target;
        this.levelsCut = 0;
        this.phase = cursor.getY() <= levelFloor ? Phase.TUNNEL : Phase.DESCEND;

        // No pickaxe, no mine — player rules.
        assistant.equipBestTool(Blocks.STONE.defaultBlockState());
        if (!assistant.getMainHandItem().isCorrectToolForDrops(Blocks.STONE.defaultBlockState())) {
            // The chests on the patch before the player. A spare pickaxe left in
            // the mine chest is the player having already answered this.
            assistant.topUpKit();
            assistant.equipBestTool(Blocks.STONE.defaultBlockState());
        }
        if (!assistant.getMainHandItem().isCorrectToolForDrops(Blocks.STONE.defaultBlockState())) {
            finish("I need a pickaxe before I can dig a mine — \"craft a wooden pickaxe\".");
            return;
        }
        if (assistant.isPackFull()) {
            finish("Pack's full — let me deposit first.");
            return;
        }
        assistant.sayRoutine("Digging a mine down to Y" + (job != null ? job.amount() : 12)
            + " — I'll torch it and grab every vein I pass.");
    }

    @Override
    public void stop() {
        if (job != null) {
            assistant.sayRoutine("Paused mining (" + oresMined + " ore so far).");
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

        // Full pack: walk back up our own staircase first — the known, lit,
        // dug route — and only then stash. Finishing on the spot handed the
        // pathfinder a bot at Y-50 and let it wander whatever caves the dig
        // had breached on the way.
        if (assistant.isPackFull() && phase != Phase.RETURN) {
            if (stairPath.size() > 1) {
                phase = Phase.RETURN;
                returnIndex = stairPath.size() - 1;
                digQueue.clear();
                veinQueue.clear();
                currentDig = null;
                moveTarget = null;
                assistant.sayRoutine("Pack's full — heading back up my own stairs.");
            } else {
                finish("Pack's full — got " + oresMined + " ore blocks. Stashing now.");
                assistant.enqueueFront(Job.deposit());
                return;
            }
        }

        if (currentDig != null) {
            digTick();
            return;
        }
        if (!digQueue.isEmpty()) {
            beginDig(digQueue.pollFirst());
            return;
        }
        // Veins BEFORE the tunnel advances: every exposed ore face gets dug
        // before the gallery grows, so a run never walks past visible ore.
        // Stale entries (mined out, or left behind) drain in one pass rather
        // than burning a whole tick discarding each.
        while (!veinQueue.isEmpty()) {
            BlockPos vein = veinQueue.pollFirst();
            if (isOre(assistant.level().getBlockState(vein))
                && vein.distSqr(cursor) <= 20.0) {
                beginDig(vein);
                return;
            }
        }
        if (moveTarget != null) {
            moveTick();
            return;
        }

        // Plan the next step.
        if (phase == Phase.RETURN) {
            if (returnIndex < 0) {
                finish("Back at the shaft head — got " + oresMined + " ore. Stashing now.");
                assistant.enqueueFront(Job.deposit());
                return;
            }
            moveTarget = stairPath.get(returnIndex--);
            moveStuck = 0;
            return;
        }
        if (phase == Phase.DESCEND) {
            if (cursor.getY() <= levelFloor) {
                phase = Phase.TUNNEL;
                tunnelSteps = 0;
                assistant.sayRoutine("At Y" + cursor.getY() + " — opening the gallery.");
                return;
            }
            // The descent must stay on the patch too. Left unchecked a straight
            // ramp walked the miner ~50 blocks off its own ground on the way down.
            if (!assistant.inZoneColumn(cursor.relative(dir))) {
                dir = dir.getClockWise(); // switchback rather than leave the zone
                if (!assistant.inZoneColumn(cursor.relative(dir))) {
                    phase = Phase.TUNNEL; // boxed in: start the gallery here
                    return;
                }
            }
            planStep(cursor.relative(dir).below());
        } else {
            // Stop at the edge of the assigned patch as well as at length — a
            // stationed miner's gallery must not tunnel out from under a
            // neighbour's farm.
            boolean leavingZone = !assistant.inZoneColumn(cursor.relative(dir));
            if (tunnelSteps >= TUNNEL_LENGTH || leavingZone) {
                // Quarry: this floor is cut — drop four, turn a quarter, and
                // open the next one, until the depth the player set is reached.
                int target = job.amount();
                if (assistant.quarry() && levelFloor > target) {
                    levelFloor = Math.max(target, levelFloor - 4);
                    levelsCut++;
                    dir = dir.getClockWise();
                    phase = Phase.DESCEND;
                    tunnelSteps = 0;
                    assistant.sayRoutine("Level " + levelsCut + " cut — dropping to Y"
                        + levelFloor + " for the next.");
                    return;
                }
                finish((assistant.quarry() && levelsCut > 0
                        ? "Quarry's down to Y" + levelFloor + " — " + (levelsCut + 1)
                          + " levels, "
                        : "Mine's done — ")
                    + blocksMined + " blocks dug, " + oresMined + " ore collected"
                    + (leavingZone ? " (that's the edge of my patch)." : "."));
                return;
            }
            planStep(cursor.relative(dir));
        }
    }

    /**
     * May this block be broken at all? The patch's footprint in X/Z, and
     * between the ground it was marked from and the depth the player set —
     * so "dig down to Y-24 in this square" means exactly that, and a cellar or
     * a floor above the plot is not something the miner is entitled to.
     */
    private boolean mayDig(BlockPos pos) {
        if (!assistant.inZoneColumn(pos)) return false;
        com.jrpetty.mcassistant.entity.WorkZone zone = assistant.workZone();
        if (zone == null) return true;               // unzoned: old behaviour
        int floor = Math.min(zone.depth(), zone.min().getY());
        int ceiling = zone.max().getY() + 4;         // headroom to stand and swing
        return pos.getY() >= floor && pos.getY() <= ceiling;
    }

    /** Queue the digs for one step of shaft/gallery ending at newFeet. */
    private void planStep(BlockPos newFeet) {
        // Never dig into fluids — check every cell we'll open plus what's
        // behind them and under our new floor.
        BlockPos[] cells = phase == Phase.DESCEND
            ? new BlockPos[] { newFeet.above(2), newFeet.above(), newFeet }
            : new BlockPos[] { newFeet.above(), newFeet };
        for (BlockPos cell : cells) {
            if (touchesFluid(cell) && !capFluid(cell)) {
                finish("Hit liquid and I'm out of blocks to wall it off — stopping here ("
                    + oresMined + " ore so far).");
                return;
            }
        }
        if (touchesFluid(newFeet.below()) && !capFluid(newFeet.below())) {
            finish("Liquid underfoot and nothing to cap it with — stopping here ("
                + oresMined + " ore so far).");
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

    /** Wall off every fluid block touching this cell — the cell itself, the
     *  block beyond it in the digging direction, and above and below — so the
     *  gallery carries on PAST a pocket instead of abandoning the whole run at
     *  the first drip. Returns false only when the filler runs out; anything
     *  that seeps through a gap this misses is caught by the after-dig sweep. */
    private boolean capFluid(BlockPos cell) {
        BlockPos[] suspects = { cell, cell.relative(dir), cell.above(), cell.below() };
        for (BlockPos f : suspects) {
            if (!assistant.level().getFluidState(f).isEmpty()) {
                if (!placeFiller(f)) return false;
                if (!saidCapped) {
                    saidCapped = true;
                    assistant.sayRoutine("Walled off liquid in the tunnel.");
                }
            }
        }
        return assistant.level().getFluidState(cell).isEmpty()
            && assistant.level().getFluidState(cell.relative(dir)).isEmpty();
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
                assistant.placeSound(pos);
                return true;
            }
        }
        return false;
    }

    private void beginDig(BlockPos pos) {
        if (!mayDig(pos)) return;          // never off the marked patch
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
                assistant.workHit(pos);   // a pick on stone SOUNDS like one
            }
            return;
        }

        // Last line of defence, at the only place this goal breaks anything.
        // The planning checks look one step ahead and only at X/Z, because
        // inZoneColumn deliberately ignores Y so a miner can go down its own
        // shaft — which also means a base built above or below the patch was
        // fair game. Vein-chasing then dug sideways off the plan entirely.
        // Nothing outside the marked patch gets broken, however we got here.
        if (!mayDig(pos)) {
            currentDig = null;      // drop it and take the next queued cell
            workTicks = 0;
            return;
        }

        boolean ore = isOre(state);
        if (assistant.level().destroyBlock(pos, true, assistant)) {
            blocksMined++;
        assistant.note(AssistantEntity.Deed.BLOCKS_MINED, 1);
            if (ore) {
                oresMined++;
            assistant.note(AssistantEntity.Deed.ORE_FOUND, 1);
                assistant.noteRichSpot(pos);   // a vein rarely comes alone

                veinMined++;
                assistant.awardXp(2); // fair XP toward enchanting
            }
            assistant.damageHeldTool();
            // A dug face can open onto lava or water SIDEWAYS — the planning
            // checks only ever looked ahead. Wall the pocket off with filler
            // before it pours into the gallery.
            for (Direction d : Direction.values()) {
                BlockPos side = pos.relative(d);
                if (!assistant.level().getFluidState(side).isEmpty() && placeFiller(side)
                    && !saidCapped) {
                    saidCapped = true;
                    assistant.sayRoutine("Walled off liquid in the tunnel.");
                }
            }
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
                    // Vein-chasing is the one thing that leaves the plan, so it
                    // is the one that walked a miner through a wall into a base.
                    if (isOre(assistant.level().getBlockState(n)) && mayDig(n)
                            && !veinQueue.contains(n)) {
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
            if (phase == Phase.DESCEND && stairPath.size() < 400) {
                stairPath.add(dest.immutable());
            } else if (phase == Phase.TUNNEL && assistant.quarry()
                && stairPath.size() < 400 && tunnelSteps % 4 == 0) {
                // In a quarry the way home runs along the galleries too, so
                // breadcrumb them sparsely — every fourth step is enough of a
                // trail to walk back without filling the list.
                stairPath.add(dest.immutable());
            }
            // A step can pass through open cave: those walls were never dug,
            // so the after-dig scan never saw them. Check around the two cells
            // now occupied, so ore in a cavity wall is chased like any other.
            if (phase != Phase.RETURN && veinMined < MAX_VEIN_BLOCKS) {
                for (BlockPos cell : new BlockPos[] { cursor, cursor.above() }) {
                    for (Direction d : Direction.values()) {
                        BlockPos ore = cell.relative(d);
                        if (isOre(assistant.level().getBlockState(ore)) && mayDig(ore)
                                && !veinQueue.contains(ore)) {
                            veinQueue.addLast(ore);
                        }
                    }
                }
            }
            // Torch the path — but only where it is actually DARK. A stretch
            // already lit by the last torch (or an old tunnel's) doesn't get
            // one on a counter; the counter just says when to look again.
            if (++sinceTorch >= 6) {
                BlockPos floor = cursor.below();
                if (assistant.level().getBrightness(
                        net.minecraft.world.level.LightLayer.BLOCK, cursor) == 0
                    && assistant.level().getBlockState(cursor).canBeReplaced()
                    && assistant.level().getBlockState(floor).isFaceSturdy(assistant.level(), floor, Direction.UP)
                    && assistant.removeMatching(s -> s.is(Items.TORCH), 1) == 1) {
                    assistant.level().setBlockAndUpdate(cursor, Blocks.TORCH.defaultBlockState());
                    assistant.placeSound(cursor);
                    sinceTorch = 0;
                }
            }
            return;
        }
        if (assistant.getNavigation().isDone()) {
            assistant.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, 1.0D);
        }
        if (++moveStuck > 100) {
            finish("Got stuck in the shaft — stopping here (" + oresMined + " ore so far).");
            if (assistant.isPackFull()) assistant.enqueueFront(Job.deposit());
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
