package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Nether expedition (EXPERIMENTAL): build and light an obsidian portal, cross
 * to the Nether, gather a resource (glowstone, quartz, netherrack, soul sand,
 * magma, gold), and come home. A dimension change swaps the entity instance
 * and drops the transient job queue, so the trip resumes from the persisted
 * expedition state on AssistantEntity. Defensive on purpose: fire resistance
 * and a safe landing platform on arrival so it doesn't just die in lava.
 */
public class NetherGoal extends Goal {

    private static final int NEED_OBSIDIAN = 14;
    private static final int GATHER_RADIUS = 20;
    private static final int WORK_TICKS = 24;
    private static final int NETHER_TIMEOUT = 4000; // ~3 min of gathering, then bail home

    private final AssistantEntity assistant;
    @Nullable private Job job;
    private int myGen;

    // overworld build/cross sub-state
    private boolean built;
    @Nullable private BlockPos portalInterior;
    private int crossDelay;

    // nether gathering sub-state
    @Nullable private BlockPos target;
    private int workTicks;
    private int stuckTicks;
    private int arrivedTick = -1;

    public NetherGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private boolean inNether() {
        return assistant.level().dimension() == Level.NETHER;
    }

    @Override
    public boolean canUse() {
        if (assistant.getTarget() != null) return false;
        if (assistant.expeditionActive()) return true;
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.NETHER;
    }

    @Override
    public boolean canContinueToUse() {
        if (assistant.getTarget() != null) return false;
        return assistant.expeditionActive()
            || (job != null && assistant.peekJob() == job && assistant.taskGen() == myGen);
    }

    @Override
    public void start() {
        this.myGen = assistant.taskGen();
        this.built = false;
        this.portalInterior = null;
        this.crossDelay = 0;
        this.target = null;
        this.workTicks = 0;
        this.stuckTicks = 0;
        this.arrivedTick = assistant.tickCount;

        if (!assistant.expeditionActive()) {
            this.job = assistant.peekJob();
            if (assistant.countMatching(s -> s.is(Items.OBSIDIAN)) < NEED_OBSIDIAN) {
                finishAbort("I need " + NEED_OBSIDIAN + " obsidian to build a nether portal.");
            } else if (assistant.countMatching(s -> s.is(Items.FLINT_AND_STEEL)) < 1) {
                finishAbort("I need flint and steel to light the portal.");
            } else {
                assistant.say("Building a nether portal for the " + targetWord() + " run.");
            }
        }
    }

    @Override
    public void stop() {
        assistant.getNavigation().stop();
        // Do NOT clear expedition state here — it must survive combat/interrupts.
        this.job = null;
    }

    /** Overworld pre-cross failure: drop the job, no expedition started. */
    private void finishAbort(String message) {
        assistant.say(message);
        if (assistant.peekJob() != null && assistant.peekJob().type() == Job.Type.NETHER) {
            assistant.pollJob();
        }
        this.job = null;
        assistant.getNavigation().stop();
    }

    private String targetWord() {
        if (assistant.expeditionActive()) return assistant.expeditionTarget();
        Job j = assistant.peekJob();
        return j != null && j.arg() != null ? j.arg() : "glowstone";
    }

    private int targetAmount() {
        Job j = assistant.peekJob();
        return j != null ? j.amount() : 16;
    }

    @Override
    public void tick() {
        if (assistant.expeditionActive()) {
            if (inNether() && assistant.expeditionPhase() == 1) {
                netherTick();
            } else if (!inNether() && assistant.expeditionPhase() == 2) {
                // Made it home with the loot.
                assistant.say("Back from the nether with the goods.");
                assistant.endExpedition();
            } else if (!inNether() && assistant.expeditionPhase() == 1) {
                // Shouldn't happen, but recover gracefully.
                assistant.endExpedition();
            }
            return;
        }
        // Overworld: build the portal, then cross.
        overworldTick();
    }

    // ------------------------------- overworld --------------------------------

    private void overworldTick() {
        if (job == null) return;
        if (!built) {
            buildPortal();
            return;
        }
        if (portalInterior == null) {
            finishAbort("Couldn't finish the portal.");
            return;
        }
        // Step into the portal, then cross.
        assistant.getLookControl().setLookAt(
            portalInterior.getX() + 0.5, portalInterior.getY() + 0.5, portalInterior.getZ() + 0.5);
        double d = assistant.distanceToSqr(portalInterior.getX() + 0.5, portalInterior.getY(), portalInterior.getZ() + 0.5);
        if (d > 2.0) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(
                    portalInterior.getX() + 0.5, portalInterior.getY(), portalInterior.getZ() + 0.5, 1.0D);
            }
            if (++stuckTicks > 100) crossDelay = 1; // close enough / stuck — just cross
            if (crossDelay == 0) return;
        }
        if (++crossDelay < 20) {
            assistant.say("Stepping through...");
            return;
        }
        crossToNether();
    }

    private void buildPortal() {
        Direction face = Direction.fromYRot(assistant.getYRot());
        Direction.Axis axis = (face == Direction.NORTH || face == Direction.SOUTH)
            ? Direction.Axis.X : Direction.Axis.Z;
        // Base a couple blocks in front of the bot, at foot level.
        BlockPos base = assistant.blockPosition().relative(face, 2);

        // Frame 4 wide (w 0..3) x 5 tall (h 0..4); interior w 1..2, h 1..3.
        BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
        for (int w = 0; w < 4; w++) {
            for (int h = 0; h < 5; h++) {
                boolean border = w == 0 || w == 3 || h == 0 || h == 4;
                BlockPos p = cell(base, axis, w, h);
                if (border) {
                    assistant.level().setBlockAndUpdate(p, obsidian);
                } else {
                    assistant.level().setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                }
            }
        }
        // Light it: fill the interior with portal blocks on the right axis.
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, axis);
        BlockPos interiorBottom = cell(base, axis, 1, 1);
        for (int w = 1; w < 3; w++) {
            for (int h = 1; h < 4; h++) {
                assistant.level().setBlockAndUpdate(cell(base, axis, w, h), portal);
            }
        }
        // Pay the costs.
        assistant.removeMatching(s -> s.is(Items.OBSIDIAN), NEED_OBSIDIAN);
        damageFlintAndSteel();
        this.portalInterior = interiorBottom.above(); // stand mid-portal
        this.built = true;
        this.stuckTicks = 0;
        assistant.say("Portal's lit — heading through.");
    }

    private static BlockPos cell(BlockPos base, Direction.Axis axis, int w, int h) {
        return axis == Direction.Axis.X ? base.offset(w, h, 0) : base.offset(0, h, w);
    }

    private void damageFlintAndSteel() {
        for (ItemStack s : assistant.getInventoryItems()) {
            if (s.is(Items.FLINT_AND_STEEL)) {
                s.setDamageValue(s.getDamageValue() + 1);
                if (s.getDamageValue() >= s.getMaxDamage()) s.shrink(1);
                return;
            }
        }
    }

    private void crossToNether() {
        BlockPos here = assistant.blockPosition();
        // Nether coords are overworld/8; begin the expedition BEFORE crossing so
        // the new nether entity resumes from it.
        assistant.beginExpedition(targetWord(), targetAmount(), here);
        if (assistant.peekJob() != null && assistant.peekJob().type() == Job.Type.NETHER) {
            assistant.pollJob();
        }
        this.job = null;
        cross(Level.NETHER, new BlockPos(here.getX() / 8, 64, here.getZ() / 8));
    }

    // -------------------------------- nether ----------------------------------

    private void netherTick() {
        // Safety on arrival: fire resistance + a footing, so we don't melt.
        if (assistant.getEffect(MobEffects.FIRE_RESISTANCE) == null) {
            assistant.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 24000, 0, false, false));
            ensureFooting();
        }
        if (arrivedTick < 0) arrivedTick = assistant.tickCount;

        if (assistant.expeditionRemaining() <= 0
            || assistant.tickCount - arrivedTick > NETHER_TIMEOUT
            || assistant.isPackFull()) {
            returnHome();
            return;
        }

        Block want = targetBlock(assistant.expeditionTarget());
        if (target == null || !assistant.level().getBlockState(target).is(want)) {
            target = findNearest(want);
            workTicks = 0;
            stuckTicks = 0;
            if (target == null) {
                assistant.say("No " + assistant.expeditionTarget() + " within reach — heading home.");
                returnHome();
                return;
            }
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
            if (++stuckTicks > 100) target = null; // unreachable — pick another
            return;
        }
        if (++workTicks < WORK_TICKS) {
            if (workTicks % 8 == 0) assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            return;
        }
        BlockPos pos = target;
        target = null;
        workTicks = 0;
        if (assistant.level().destroyBlock(pos, true, assistant)) {
            sweepDrops(pos);
            assistant.setExpeditionRemaining(assistant.expeditionRemaining() - 1);
        }
    }

    private void returnHome() {
        assistant.setExpeditionPhase(2);
        BlockPos back = assistant.expeditionReturn();
        if (back == null) back = new BlockPos(assistant.getBlockX() * 8, 64, assistant.getBlockZ() * 8);
        cross(Level.OVERWORLD, back);
    }

    /** Place a small platform + clear headroom so we don't spawn in lava/rock. */
    private void ensureFooting() {
        BlockPos feet = assistant.blockPosition();
        BlockState solid = Blocks.OBSIDIAN.defaultBlockState();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos below = feet.offset(dx, -1, dz);
                if (assistant.level().getBlockState(below).isAir()
                    || !assistant.level().getFluidState(below).isEmpty()) {
                    assistant.level().setBlockAndUpdate(below, solid);
                }
            }
        }
        assistant.level().setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
        assistant.level().setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
    }

    // -------------------------------- shared ----------------------------------

    private void cross(net.minecraft.resources.ResourceKey<Level> dim, BlockPos hint) {
        if (!(assistant.level() instanceof ServerLevel sl) || sl.getServer() == null) return;
        ServerLevel dest = sl.getServer().getLevel(dim);
        if (dest == null) {
            assistant.say("I couldn't reach that dimension.");
            assistant.endExpedition();
            return;
        }
        BlockPos safe = safeLanding(dest, hint);
        assistant.getNavigation().stop();
        assistant.changeDimension(new DimensionTransition(dest,
            new Vec3(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5), Vec3.ZERO,
            assistant.getYRot(), assistant.getXRot(), DimensionTransition.DO_NOTHING));
    }

    /** A spot in `dest` with two air blocks over solid, no fluids — building a
     *  small obsidian pad if nothing safe is found. */
    private BlockPos safeLanding(ServerLevel dest, BlockPos hint) {
        int x = hint.getX();
        int z = hint.getZ();
        int top = dest.dimension() == Level.NETHER ? 118 : Math.min(dest.getMaxBuildHeight() - 2, 200);
        int bottom = dest.dimension() == Level.NETHER ? 8 : dest.getMinBuildHeight() + 2;
        for (int y = Math.min(top, hint.getY() + 40); y > bottom; y--) {
            BlockPos p = new BlockPos(x, y, z);
            BlockState below = dest.getBlockState(p.below());
            if (!below.isAir() && dest.getFluidState(p.below()).isEmpty()
                && dest.getBlockState(p).isAir() && dest.getBlockState(p.above()).isAir()) {
                return p;
            }
        }
        // Nothing safe — lay a pad.
        int y = dest.dimension() == Level.NETHER ? 64 : Math.max(bottom, Math.min(top, hint.getY()));
        BlockPos pad = new BlockPos(x, y, z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                dest.setBlockAndUpdate(pad.offset(dx, -1, dz), Blocks.OBSIDIAN.defaultBlockState());
            }
        }
        dest.setBlockAndUpdate(pad, Blocks.AIR.defaultBlockState());
        dest.setBlockAndUpdate(pad.above(), Blocks.AIR.defaultBlockState());
        return pad;
    }

    private static Block targetBlock(String t) {
        return switch (t) {
            case "quartz", "nether quartz" -> Blocks.NETHER_QUARTZ_ORE;
            case "netherrack" -> Blocks.NETHERRACK;
            case "soul sand", "soulsand", "soul" -> Blocks.SOUL_SAND;
            case "magma" -> Blocks.MAGMA_BLOCK;
            case "gold" -> Blocks.NETHER_GOLD_ORE;
            default -> Blocks.GLOWSTONE;
        };
    }

    @Nullable
    private BlockPos findNearest(Block want) {
        BlockPos feet = assistant.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-GATHER_RADIUS, -6, -GATHER_RADIUS),
                feet.offset(GATHER_RADIUS, 6, GATHER_RADIUS))) {
            if (!assistant.level().getBlockState(pos).is(want)) continue;
            if (nearLava(pos)) continue; // don't mine into a lava pocket
            double d = pos.distSqr(feet);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    private boolean nearLava(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (assistant.level().getFluidState(pos.relative(dir)).is(net.minecraft.tags.FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
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
