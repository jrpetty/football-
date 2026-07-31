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
        if (!equipRod()) {
            finish("I need a fishing rod — \"craft a fishing rod\" (3 sticks + 2 string).");
            return;
        }
        this.water = findWater();
        if (water == null) {
            finish("No open water within 12 blocks.");
            return;
        }
        assistant.say("Dropping a line.");
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
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.noteJobOutcome(caught > 0);
        assistant.pollJob();
        this.job = null;
        this.water = null;
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
        if (distSq > 20.0) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(
                    water.getX() + 0.5, water.getY() + 1, water.getZ() + 0.5, 1.1D);
            }
            if (++stuckTicks > 160) {
                finish("Couldn't reach the water.");
            }
            return;
        }
        assistant.getNavigation().stop();

        if (biteTimer <= 0) {
            // Cast and wait a vanilla-ish bite time (5-15 seconds).
            biteTimer = 100 + assistant.getRandom().nextInt(200);
            assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            return;
        }
        if (--biteTimer > 0) return;

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
        ItemStack leftover = assistant.insertItem(loot);
        if (!leftover.isEmpty()) {
            finish("Pack's full — caught " + caught + ".");
            return;
        }
        caught++;
        ItemStack rod = assistant.getMainHandItem();
        if (rod.is(Items.FISHING_ROD)) {
            rod.hurtAndBreak(1, assistant, EquipmentSlot.MAINHAND);
        }
    }

    @Nullable
    private BlockPos findWater() {
        BlockPos feet = assistant.feetPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        // Reach a little further than the stationed fisher's requirement check
        // (water within 12 of its zone centre) so a bot standing off-centre in
        // its zone still finds the pond it was posted to.
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-18, -4, -18), feet.offset(18, 3, 18))) {
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
