package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Predicate;

/**
 * Husbandry: "breed the cows" — feeds pairs of adult animals their breeding
 * food from the pack (wheat for cows/sheep, carrots/potatoes for pigs, seeds
 * for chickens, carrots for rabbits) so the herd regrows. Hunt + breed =
 * a ranch instead of an extinction event.
 */
public class BreedGoal extends Goal {

    private record Species(Class<? extends Animal> type, Predicate<ItemStack> food, String label) {}

    private static final List<Species> SPECIES = List.of(
        new Species(Cow.class, s -> s.is(Items.WHEAT), "cows"),
        new Species(Sheep.class, s -> s.is(Items.WHEAT), "sheep"),
        new Species(Pig.class, s -> s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.BEETROOT), "pigs"),
        new Species(Chicken.class, s -> s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS)
            || s.is(Items.MELON_SEEDS) || s.is(Items.PUMPKIN_SEEDS), "chickens"),
        new Species(Rabbit.class, s -> s.is(Items.CARROT) || s.is(Items.DANDELION), "rabbits"));

    private final AssistantEntity assistant;
    @Nullable private Job job;
    @Nullable private Animal first;
    @Nullable private Animal second;
    private int pairsBred;
    private int stuckTicks;
    private int myGen;

    public BreedGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.BREED && assistant.getTarget() == null;
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
        this.pairsBred = 0;
        this.stuckTicks = 0;
        this.first = null;
        this.second = null;
    }

    @Override
    public void stop() {
        this.job = null;
        this.first = null;
        this.second = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.noteJobOutcome(pairsBred > 0);
        assistant.pollJob();
        this.job = null;
        this.first = null;
        this.second = null;
        assistant.getNavigation().stop();
    }

    @Nullable
    private Species speciesFor(@Nullable String word) {
        if (word == null) return null;
        String w = word.endsWith("s") && !word.equals("sheep") ? word.substring(0, word.length() - 1) : word;
        for (Species sp : SPECIES) {
            if (sp.label().startsWith(w)) return sp;
        }
        return null;
    }

    private boolean breedable(Animal a, Species sp) {
        return sp.type().isInstance(a) && a.isAlive() && !a.isBaby()
            && a.getAge() == 0 && !a.isInLove();
    }

    @Override
    public void tick() {
        if (job == null) return;

        if (pairsBred >= job.amount()) {
            finish("Bred " + pairsBred + " pair" + (pairsBred == 1 ? "" : "s") + " — babies incoming.");
            return;
        }

        if (first == null || second == null
            || !first.isAlive() || !second.isAlive()
            || first.isInLove() && second.isInLove()) {
            if (first != null && second != null && first.isInLove() && second.isInLove()) {
                pairsBred++; // that pair is handled
            }
            if (!pickPair()) {
                finish(pairsBred > 0
                    ? "Bred " + pairsBred + " pair" + (pairsBred == 1 ? "" : "s") + " — no more matches (or no more feed)."
                    : "No breedable pairs nearby, or I lack the right feed (wheat/carrots/seeds).");
                return;
            }
        }

        Animal target = !first.isInLove() ? first : second;
        assistant.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (assistant.distanceToSqr(target) > 6.25) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(target, 1.15D);
            }
            if (++stuckTicks > 200) {
                first = null;
                second = null;
                stuckTicks = 0;
            }
            return;
        }
        stuckTicks = 0;

        Species sp = speciesForAnimal(target);
        if (sp == null || assistant.removeMatching(sp.food(), 1) < 1) {
            finish("Ran out of feed — bred " + pairsBred + " pair" + (pairsBred == 1 ? "" : "s") + ".");
            return;
        }
        target.setInLove(null);
        assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    @Nullable
    private Species speciesForAnimal(Animal a) {
        for (Species sp : SPECIES) {
            if (sp.type().isInstance(a)) return sp;
        }
        return null;
    }

    private boolean pickPair() {
        Species wanted = speciesFor(job != null ? job.arg() : null);
        for (Species sp : wanted != null ? List.of(wanted) : SPECIES) {
            if (assistant.countMatching(sp.food()) < 2) continue;
            List<? extends Animal> candidates = assistant.level().getEntitiesOfClass(
                sp.type(), assistant.getBoundingBox().inflate(16.0), a -> breedable(a, sp));
            if (candidates.size() >= 2) {
                first = candidates.get(0);
                second = candidates.get(1);
                return true;
            }
        }
        return false;
    }
}
