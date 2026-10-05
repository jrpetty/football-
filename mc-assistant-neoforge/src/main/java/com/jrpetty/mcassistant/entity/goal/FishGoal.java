package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Fishing (simplified but honest-ish): needs a real fishing rod, stands at
 * the water's edge, waits real bite times, and pulls vanilla-like catches —
 * mostly fish, some junk, the odd treasure. The rod wears out per catch.
 */
public class FishGoal extends Goal {

    private final AssistantEntity assistant;
    @Nullable private Job job;
    @Nullable private BlockPos water;
    /** Where it stands to fish: dry ground on the bank by the water, not the water itself. */
    @Nullable private BlockPos shore;
    @Nullable private BlockPos bobber;   // where the cast landed — the visible wait
    private int reelTicks;               // the bite is on; a beat before the yank
    private int caught;
    private int biteTimer;
    private int stuckTicks;
    private int myGen;

    public FishGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.FISH && assistant.getTarget() == null;
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
        this.caught = 0;
        this.biteTimer = 0;
        this.stuckTicks = 0;
        if (!equipRod() && !(assistant.topUpKit() && equipRod())) {
            finish("I need a fishing rod — \"craft a fishing rod\" (3 sticks + 2 string).");
            return;
        }
        this.water = findWater();
        // Off the end of the village's jetty, if this fisher has one.
        com.jrpetty.mcassistant.entity.Waterfront.Dock dock =
            com.jrpetty.mcassistant.entity.Waterfront.dockOf(assistant.ownerId(), assistant);
        if (dock != null) {
            BlockPos past = dock.end().relative(dock.out(), 2);
            if (assistant.level().getFluidState(past).is(FluidTags.WATER)) this.water = past;
        }
        if (water == null) {
            finish("No open water within 12 blocks.");
            return;
        }
        this.shore = bankBy(water);
        assistant.sayRoutine("Dropping a line.");
    }

    private boolean equipRod() {
        if (assistant.getMainHandItem().is(Items.FISHING_ROD)) return true;
        var inv = assistant.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).is(Items.FISHING_ROD)) {
                ItemStack old = assistant.getMainHandItem();
                assistant.setItemSlot(EquipmentSlot.MAINHAND, inv.get(i));
                inv.set(i, old);
                return true;
            }
        }
        return false;
    }

    @Override
    public void stop() {
        this.job = null;
        this.water = null;
        this.bobber = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.noteJobOutcome(caught > 0);
        assistant.pollJob();
        this.job = null;
        this.water = null;
        this.bobber = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (job == null || water == null) return;

        if (caught >= job.amount()) {
            finish("Caught " + caught + " — good haul.");
            return;
        }
        if (!assistant.getMainHandItem().is(Items.FISHING_ROD) && !equipRod()) {
            finish("My rod broke — caught " + caught + ".");
            return;
        }

        assistant.getLookControl().setLookAt(
            water.getX() + 0.5, water.getY() + 0.5, water.getZ() + 0.5);
        double distSq = assistant.distanceToSqr(water.getX() + 0.5, water.getY() + 0.5, water.getZ() + 0.5);
        // A line casts six blocks: near enough is the bank, not the water's edge.
        if (distSq > 36.0) {
            if (assistant.getNavigation().isDone()) {
                BlockPos to = shore != null ? shore : water.above();
                assistant.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 1.1D);
            }
            if (++stuckTicks > 160) {
                // Another bit of the bank, nearer to hand, before giving up on the water.
                BlockPos other = findWater();
                if (other != null && !other.equals(water) && stuckTicks < 400) {
                    water = other;
                    shore = bankBy(other);
                    return;
                }
                finish("Couldn't reach the water.");
            }
            return;
        }
        assistant.getNavigation().stop();

        net.minecraft.server.level.ServerLevel sl =
            (net.minecraft.server.level.ServerLevel) assistant.level();
        if (bobber == null) {
            // THE CAST: rod swings, the line whistles out, and the bobber
            // lands ON the water — the whole wait is visible and audible,
            // the way a player's cast is.
            bobber = water.immutable();
            // Five to twenty seconds for a bite, like a player's, less for a practised hand: half
            // its pace counts here (pacedTicks), since the fish bite when they bite and an old
            // fisher only reads the water, the weather and the hour better. Up to a quarter off.
            biteTimer = assistant.pacedTicks(100 + assistant.getRandom().nextInt(300), 50);
            reelTicks = 0;
            assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            sl.playSound(null, assistant.blockPosition(),
                net.minecraft.sounds.SoundEvents.FISHING_BOBBER_THROW,
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.5F,
                0.4F / (assistant.getRandom().nextFloat() * 0.4F + 0.8F));
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH,
                bobber.getX() + 0.5, bobber.getY() + 1.0, bobber.getZ() + 0.5,
                4, 0.1, 0.0, 0.1, 0.1);
            return;
        }
        if (reelTicks > 0) {
            if (--reelTicks > 0) return;
            catchOne(sl);
            return;
        }
        if (--biteTimer > 0) {
            // The bobber rides the water while the wait runs — a ripple every
            // half-second, so the cast reads as a cast from across the pond.
            if (biteTimer % 10 == 0) {
                sl.sendParticles(net.minecraft.core.particles.ParticleTypes.FISHING,
                    bobber.getX() + 0.5, bobber.getY() + 0.95, bobber.getZ() + 0.5,
                    1, 0.05, 0.0, 0.05, 0.0);
            }
            return;
        }
        // THE BITE: the splash every player knows, then a beat to reel.
        reelTicks = 8;
        sl.playSound(null, bobber,
            net.minecraft.sounds.SoundEvents.FISHING_BOBBER_SPLASH,
            net.minecraft.sounds.SoundSource.NEUTRAL, 0.6F,
            1.0F + (assistant.getRandom().nextFloat() - 0.4F) * 0.4F);
        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH,
            bobber.getX() + 0.5, bobber.getY() + 1.0, bobber.getZ() + 0.5,
            10, 0.15, 0.1, 0.15, 0.2);
        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.BUBBLE,
            bobber.getX() + 0.5, bobber.getY() + 0.9, bobber.getZ() + 0.5,
            8, 0.1, 0.05, 0.1, 0.1);
    }

    /** THE YANK: the catch flies out of the water toward the rod, the reel
     *  clicks, and the loot lands in the pack. */
    private void catchOne(net.minecraft.server.level.ServerLevel sl) {
        // The catch, weighted roughly like vanilla fishing.
        int roll = assistant.getRandom().nextInt(100);
        ItemStack loot;
        if (roll < 55) loot = new ItemStack(Items.COD);
        else if (roll < 78) loot = new ItemStack(Items.SALMON);
        else if (roll < 84) loot = new ItemStack(Items.PUFFERFISH);
        else if (roll < 88) loot = new ItemStack(Items.TROPICAL_FISH);
        else if (roll < 92) loot = new ItemStack(Items.STRING, 1 + assistant.getRandom().nextInt(2));
        else if (roll < 95) loot = new ItemStack(Items.BONE, 1 + assistant.getRandom().nextInt(2));
        else if (roll < 97) loot = new ItemStack(Items.LEATHER);
        else if (roll < 99) loot = new ItemStack(Items.BOWL);
        else loot = new ItemStack(Items.SADDLE);

        assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (bobber != null) {
            sl.playSound(null, bobber,
                net.minecraft.sounds.SoundEvents.FISHING_BOBBER_RETRIEVE,
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.6F,
                1.0F + (assistant.getRandom().nextFloat() - 0.4F) * 0.4F);
            double ax = assistant.getX() - (bobber.getX() + 0.5);
            double az = assistant.getZ() - (bobber.getZ() + 0.5);
            for (int i = 0; i < 3; i++) {
                sl.sendParticles(new net.minecraft.core.particles.ItemParticleOption(
                        net.minecraft.core.particles.ParticleTypes.ITEM, loot),
                    bobber.getX() + 0.5, bobber.getY() + 1.1, bobber.getZ() + 0.5,
                    0, ax * 0.08, 0.25, az * 0.08, 1.0);
            }
        }
        bobber = null;   // the next tick casts again
        ItemStack picked = loot.copy();
        ItemStack leftover = assistant.insertItem(loot);
        com.jrpetty.mcassistant.entity.Economy.gathered(assistant, picked, picked.getCount() - leftover.getCount());
        if (!leftover.isEmpty()) {
            finish("Pack's full — caught " + caught + ".");
            return;
        }
        caught++;
        assistant.note(AssistantEntity.Deed.FISH_CAUGHT, 1);
        ItemStack rod = assistant.getMainHandItem();
        if (rod.is(Items.FISHING_ROD)) {
            rod.hurtAndBreak(1, assistant, EquipmentSlot.MAINHAND);
        }
    }

    /** Dry ground on the bank by this water, two blocks of air over it, to stand and fish from. */
    @Nullable
    private BlockPos bankBy(BlockPos w) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(w.offset(-3, -1, -3), w.offset(3, 2, 3))) {
            var level = assistant.level();
            if (!level.getFluidState(p).isEmpty() || !level.getFluidState(p.below()).isEmpty()) continue;
            if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)) continue;
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) continue;
            if (!level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) continue;
            double d = p.distSqr(w) * 4 + p.distSqr(assistant.blockPosition()) * 0.05;
            if (d < bestDist) { bestDist = d; best = p.immutable(); }
        }
        return best;
    }

    @Nullable
    private BlockPos findWater() {
        // Search from the same place the checklist verified the water: the
        // middle of the patch. Searching from the BOT meant that after one
        // drift to a far corner the pond it was posted to fell out of range and
        // the fisher stopped fishing for good.
        BlockPos feet = assistant.stationSearchOrigin();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-16, -5, -16), feet.offset(16, 5, 16))) {
            if (!assistant.inZone(pos)) continue; // fish our own patch, not next door
            if (!assistant.level().getFluidState(pos).is(FluidTags.WATER)) continue;
            if (!assistant.level().getBlockState(pos.above()).canBeReplaced()) continue;
            double d = pos.distSqr(feet);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best;
    }
}
