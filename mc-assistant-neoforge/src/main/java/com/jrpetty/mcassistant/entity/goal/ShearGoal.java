package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Shearing: "shear the sheep" — needs real shears (craftable: 2 iron),
 * walks sheep to sheep, shears them, sweeps the wool, wears the shears down.
 */
public class ShearGoal extends Goal {

    private final AssistantEntity assistant;
    @Nullable private Job job;
    @Nullable private Sheep target;
    private int sheared;
    private int stuckTicks;
    private int jobTicks; // hard budget: an unreachable animal must not freeze the station
    private int myGen;

    public ShearGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.SHEAR && assistant.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && assistant.getTarget() == null
            && assistant.taskGen() == myGen && assistant.peekJob() == job;
    }

    @Override
    public void start() {
        this.jobTicks = 0;
        this.job = assistant.peekJob();
        this.myGen = assistant.taskGen();
        this.sheared = 0;
        this.stuckTicks = 0;
        this.target = null;
        if (assistant.countMatching(s -> s.is(Items.SHEARS)) == 0
            && !assistant.getMainHandItem().is(Items.SHEARS)) {
            assistant.topUpKit();   // spare shears in the pen chest count as having some
        }
        if (assistant.countMatching(s -> s.is(Items.SHEARS)) == 0
            && !assistant.getMainHandItem().is(Items.SHEARS)) {
            finish("I need shears — \"craft shears\" (2 iron ingots).");
            return;
        }
        equipShears();
        assistant.say("Shearing time.");
    }

    private void equipShears() {
        if (assistant.getMainHandItem().is(Items.SHEARS)) return;
        var inv = assistant.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).is(Items.SHEARS)) {
                ItemStack old = assistant.getMainHandItem();
                assistant.setItemSlot(EquipmentSlot.MAINHAND, inv.get(i));
                inv.set(i, old);
                return;
            }
        }
    }

    @Override
    public void stop() {
        this.job = null;
        this.target = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.noteJobOutcome(sheared > 0);
        assistant.pollJob();
        this.job = null;
        this.target = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        // A job that cannot make progress must end, not spin: the station
        // brain only runs again once the queue is empty.
        if (++jobTicks > 1200) { finish("Giving up on shearing for now."); return; }
        if (job == null) return;
        if (!assistant.actionReady()) return;   // still busy with the last one

        if (sheared >= job.amount()) {
            finish("Sheared " + sheared + " sheep — wool's in my pack.");
            return;
        }
        if (!assistant.getMainHandItem().is(Items.SHEARS)) {
            equipShears();
            if (!assistant.getMainHandItem().is(Items.SHEARS)) {
                finish("My shears broke — sheared " + sheared + ".");
                return;
            }
        }

        if (target == null || !target.isAlive() || !target.readyForShearing()) {
            target = findSheep();
            stuckTicks = 0;
            if (target == null) {
                finish(sheared > 0 ? "Sheared " + sheared + " — no more woolly sheep nearby."
                    : "No sheep ready for shearing within 24 blocks.");
                return;
            }
        }

        assistant.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (assistant.distanceToSqr(target) > 6.25) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(target, 1.15D);
            }
            if (++stuckTicks > 200) {
                target = null; // that one's unreachable, try another
            }
            return;
        }

        target.shear(SoundSource.NEUTRAL);
        assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        ItemStack shears = assistant.getMainHandItem();
        if (shears.is(Items.SHEARS)) {
            shears.hurtAndBreak(1, assistant, EquipmentSlot.MAINHAND);
        }
        sweepWool(target);
        sheared++;
        assistant.note(AssistantEntity.Deed.ANIMALS_SHEARED, 1);
        assistant.noteAction();   // a shear is a job, not an instant
        target = null;
    }

    private void sweepWool(Sheep around) {
        for (ItemEntity drop : assistant.level().getEntitiesOfClass(
                ItemEntity.class, new AABB(around.blockPosition()).inflate(2.5))) {
            ItemStack leftover = assistant.insertItem(drop.getItem());
            if (leftover.isEmpty()) {
                drop.discard();
            } else {
                drop.setItem(leftover);
            }
        }
    }

    @Nullable
    private Sheep findSheep() {
        Sheep best = null;
        double bestDist = Double.MAX_VALUE;
        for (Sheep sheep : assistant.level().getEntitiesOfClass(
                Sheep.class, assistant.getBoundingBox().inflate(24.0),
                s -> s.isAlive() && !s.isBaby() && s.readyForShearing())) {
            double d = sheep.distanceToSqr(assistant);
            if (d < bestDist) { bestDist = d; best = sheep; }
        }
        return best;
    }
}
