package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Herding: "bring 2 cows home" — leashes strays (one lead item per animal,
 * player rules), walks them to the "pen" waypoint (or home), unleashes, and
 * picks the dropped leads back up.
 */
public class HerdGoal extends Goal {

    private final AssistantEntity assistant;
    @Nullable private Job job;
    private final List<Animal> leashed = new ArrayList<>();
    @Nullable private Animal target;
    @Nullable private BlockPos dest;
    private boolean driving; // false = collecting animals, true = walking home
    private int stuckTicks;
    private double lastDistSq = Double.MAX_VALUE;
    private int myGen;

    public HerdGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Nullable
    private static Class<? extends Animal> classFor(@Nullable String word) {
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
        return j != null && j.type() == Job.Type.HERD && assistant.getTarget() == null;
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
        this.leashed.clear();
        this.target = null;
        this.driving = false;
        this.stuckTicks = 0;
        this.lastDistSq = Double.MAX_VALUE;
        this.dest = assistant.getWaypoint("pen") != null ? assistant.getWaypoint("pen") : assistant.getHome();
        if (dest == null) {
            finish("I need somewhere to bring them — set a home, or mark a spot as \"pen\".");
            return;
        }
        if (classFor(job != null ? job.arg() : null) == null) {
            finish("Tell me which animals — cows, pigs, chickens, sheep, or rabbits.");
            return;
        }
        int leads = assistant.countMatching(s -> s.is(Items.LEAD));
        if (leads == 0) {
            finish("I need leads to herd with — \"craft leads\" (string + slimeball), or hand me some.");
        }
    }

    @Override
    public void stop() {
        // Don't strand leashed animals mid-drive: let them go where we stand.
        releaseAll(false);
        this.job = null;
        this.target = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.noteJobOutcome(!leashed.isEmpty() || driving);
        releaseAll(true);
        assistant.pollJob();
        this.job = null;
        this.target = null;
        assistant.getNavigation().stop();
    }

    private void releaseAll(boolean sweepLeads) {
        for (Animal a : leashed) {
            if (a.isAlive() && a.getLeashHolder() == assistant) {
                a.dropLeash(true, true); // drops the lead item
            }
        }
        if (sweepLeads && !leashed.isEmpty()) {
            for (ItemEntity drop : assistant.level().getEntitiesOfClass(
                    ItemEntity.class, assistant.getBoundingBox().inflate(8.0),
                    d -> d.getItem().is(Items.LEAD))) {
                ItemStack leftover = assistant.insertItem(drop.getItem());
                if (leftover.isEmpty()) {
                    drop.discard();
                } else {
                    drop.setItem(leftover);
                }
            }
        }
        leashed.clear();
    }

    @Override
    public void tick() {
        if (job == null || dest == null) return;
        leashed.removeIf(a -> !a.isAlive() || a.getLeashHolder() != assistant);

        if (!driving) {
            if (leashed.size() >= job.amount()) {
                driving = true;
                assistant.say("Got " + leashed.size() + " — walking them over.");
                return;
            }
            if (target == null || !target.isAlive() || target.getLeashHolder() != null) {
                target = findStray();
                stuckTicks = 0;
                if (target == null) {
                    if (leashed.isEmpty()) {
                        finish("No " + job.arg() + " to herd within 24 blocks.");
                    } else {
                        driving = true; // take what we have
                        assistant.say("Only found " + leashed.size() + " — bringing them anyway.");
                    }
                    return;
                }
            }
            assistant.getLookControl().setLookAt(target, 30.0F, 30.0F);
            if (assistant.distanceToSqr(target) > 6.25) {
                if (assistant.getNavigation().isDone()) {
                    assistant.getNavigation().moveTo(target, 1.2D);
                }
                if (++stuckTicks > 200) {
                    target = null;
                }
                return;
            }
            if (assistant.removeMatching(s -> s.is(Items.LEAD), 1) < 1) {
                driving = true; // out of leads — drive what we have
                return;
            }
            ((Mob) target).setLeashedTo(assistant, true);
            leashed.add(target);
            assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            target = null;
            return;
        }

        // Driving home.
        double distSq = assistant.distanceToSqr(dest.getX() + 0.5, dest.getY() + 1, dest.getZ() + 0.5);
        if (distSq < 16.0) {
            int delivered = leashed.size();
            finish("Delivered " + delivered + " " + job.arg() + " — leads recovered.");
            return;
        }
        if (assistant.getNavigation().isDone()) {
            assistant.getNavigation().moveTo(dest.getX() + 0.5, dest.getY() + 1, dest.getZ() + 0.5, 1.0D);
        }
        if (distSq < lastDistSq - 0.5) {
            lastDistSq = distSq;
            stuckTicks = 0;
        } else if (++stuckTicks > 500) {
            finish("Couldn't get them all the way — released the animals here.");
        }
    }

    @Nullable
    private Animal findStray() {
        Class<? extends Animal> type = classFor(job != null ? job.arg() : null);
        if (type == null) return null;
        Animal best = null;
        double bestDist = Double.MAX_VALUE;
        for (Animal a : assistant.level().getEntitiesOfClass(
                type, assistant.getBoundingBox().inflate(24.0),
                an -> an.isAlive() && !an.isBaby() && an.getLeashHolder() == null && !an.hasCustomName())) {
            double d = a.distanceToSqr(assistant);
            if (d < bestDist) { bestDist = d; best = a; }
        }
        return best;
    }
}
