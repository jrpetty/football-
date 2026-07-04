package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/**
 * Hunting: "hunt 3 cows" — picks victims (adults only, never named pets),
 * lets the melee machinery do the killing, sweeps every drop (meat for the
 * cook pot, leather, feathers for arrows). Runs FLAGLESS as a coordinator:
 * it sets the attack target and the normal combat goals chase and swing.
 */
public class HuntGoal extends Goal {

    private final AssistantEntity assistant;
    @Nullable private Job job;
    @Nullable private Animal victim;
    @Nullable private BlockPos lastVictimPos;
    private int hunted;
    private int huntTicks;
    private int myGen;

    public HuntGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        // No flags on purpose: this goal only picks targets and counts kills;
        // BowAttack/MeleeAttack goals do the actual fighting.
    }

    @Nullable
    private static Class<? extends Animal> animalClass(@Nullable String word) {
        if (word == null) return null;
        return switch (word) {
            case "cow", "cows" -> Cow.class;
            case "pig", "pigs" -> Pig.class;
            case "chicken", "chickens" -> Chicken.class;
            case "sheep" -> Sheep.class;
            case "rabbit", "rabbits" -> Rabbit.class;
            default -> null;
        };
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.HUNT;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && assistant.taskGen() == myGen && assistant.peekJob() == job;
    }

    @Override
    public void start() {
        this.job = assistant.peekJob();
        this.myGen = assistant.taskGen();
        this.hunted = 0;
        this.huntTicks = 0;
        this.victim = null;
        assistant.equipBestWeapon();
    }

    @Override
    public void stop() {
        if (victim != null && assistant.getTarget() == victim) {
            assistant.setTarget(null);
        }
        this.job = null;
        this.victim = null;
    }

    private void finish(String message) {
        if (victim != null && assistant.getTarget() == victim) {
            assistant.setTarget(null);
        }
        assistant.say(message);
        assistant.noteJobOutcome(hunted > 0);
        assistant.pollJob();
        autoCook(); // cook the raw meat we just got
        this.job = null;
        this.victim = null;
    }

    /** After a hunt, queue a cook for any raw meat collected, so hunting yields
     *  cooked food (more nutrition) without a separate "cook" order. */
    private void autoCook() {
        cook("beef", net.minecraft.world.item.Items.BEEF);
        cook("porkchop", net.minecraft.world.item.Items.PORKCHOP);
        cook("chicken", net.minecraft.world.item.Items.CHICKEN);
        cook("mutton", net.minecraft.world.item.Items.MUTTON);
        cook("rabbit", net.minecraft.world.item.Items.RABBIT);
    }

    private void cook(String canonical, net.minecraft.world.item.Item raw) {
        int n = assistant.countMatching(s -> s.is(raw));
        if (n > 0) assistant.enqueue(Job.smelt(canonical, n));
    }

    @Override
    public void tick() {
        if (job == null || assistant.isRetreating()) return;

        // Kill confirmed: sweep the drops where it died.
        if (victim != null && (!victim.isAlive() || victim.isRemoved())) {
            if (lastVictimPos != null) sweepDrops(lastVictimPos);
            hunted++;
            victim = null;
        }

        if (hunted >= job.amount()) {
            finish("Hunted " + hunted + " — drops are in my pack.");
            return;
        }

        if (victim == null) {
            victim = findVictim();
            huntTicks = 0;
            if (victim == null) {
                finish(hunted > 0 ? "Hunted " + hunted + " — nothing else nearby."
                    : "No animals to hunt within 24 blocks.");
                return;
            }
        }

        lastVictimPos = victim.blockPosition();
        // Keep the combat goals pointed at the prey (unless a real threat
        // grabbed our attention — monsters outrank dinner).
        if (assistant.getTarget() == null) {
            assistant.setTarget(victim);
        }
        // A hunt that drags on forever (prey in a pen we can't enter) gives up.
        if (++huntTicks > 600) {
            finish("That one's out of reach — hunted " + hunted + ".");
        }
    }

    private void sweepDrops(BlockPos around) {
        for (ItemEntity drop : assistant.level().getEntitiesOfClass(
                ItemEntity.class, new AABB(around).inflate(3.0))) {
            ItemStack leftover = assistant.insertItem(drop.getItem());
            if (leftover.isEmpty()) {
                drop.discard();
            } else {
                drop.setItem(leftover);
            }
        }
    }

    @Nullable
    private Animal findVictim() {
        Class<? extends Animal> wanted = animalClass(job != null ? job.arg() : null);
        Animal best = null;
        double bestDist = Double.MAX_VALUE;
        for (Animal a : assistant.level().getEntitiesOfClass(
                Animal.class, assistant.getBoundingBox().inflate(24.0),
                an -> an.isAlive() && !an.isBaby() && !an.hasCustomName())) {
            if (wanted != null) {
                if (!wanted.isInstance(a)) continue;
            } else {
                // "hunt some animals": food animals only.
                if (!(a instanceof Cow || a instanceof Pig || a instanceof Chicken
                    || a instanceof Sheep || a instanceof Rabbit)) continue;
            }
            double d = a.distanceToSqr(assistant);
            if (d < bestDist) { bestDist = d; best = a; }
        }
        return best;
    }
}
