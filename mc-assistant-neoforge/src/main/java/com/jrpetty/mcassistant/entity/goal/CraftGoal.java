package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.RecipeBook;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Crafting, driven by the game's OWN recipe database ({@link RecipeBook}). Give
 * it any item id — "iron_sword", "piston", "hopper", "chest" — and it looks up
 * the real recipe, checks it has the ingredients, walks to (or places) a table
 * when the recipe needs the 3x3 grid, and produces the item. The wood chain
 * ("planks"/"sticks") is handled generically so gathering any log works.
 *
 * Combined with tool wear and the planner, this closes the self-maintenance
 * loop and unlocks essentially the whole crafting tree.
 */
public class CraftGoal extends Goal {

    /** One resolved requirement: N stacks matching a predicate. */
    private record Step(Predicate<ItemStack> what, int count, String label) {}

    /** A resolved recipe ready to execute. */
    private record Plan(List<Step> steps, Supplier<ItemStack> output, boolean needsTable) {}

    private static final Predicate<ItemStack> LOG = s -> s.is(ItemTags.LOGS);
    private static final Predicate<ItemStack> PLANK = s -> s.is(ItemTags.PLANKS);

    /** Resolve a job's arg to an executable plan, or null if unknown/uncraftable. */
    @Nullable
    private Plan resolve(String arg) {
        // Abstract wood-chain steps: any log -> planks, any planks -> sticks.
        if (arg.equals("planks")) {
            return new Plan(List.of(new Step(LOG, 1, "log")),
                () -> new ItemStack(Items.OAK_PLANKS, 4), false);
        }
        if (arg.equals("sticks")) {
            return new Plan(List.of(new Step(PLANK, 2, "planks")),
                () -> new ItemStack(Items.STICK, 4), false);
        }
        Item item = RecipeBook.item(arg);
        if (item == Items.AIR) return null;
        RecipeBook.Craft craft = RecipeBook.craftFor(assistant.level(), item);
        if (craft == null) return null;
        List<Step> steps = new ArrayList<>();
        for (RecipeBook.Part part : craft.parts()) {
            steps.add(new Step(s -> part.ingredient().test(s), part.count(), part.label()));
        }
        final int yield = craft.yield();
        return new Plan(steps, () -> new ItemStack(item, yield), craft.needsTable());
    }

    private final AssistantEntity assistant;
    @Nullable private Job job;
    @Nullable private Plan plan;
    private int crafted;
    private int workTicks;
    private int stuckTicks;
    @Nullable private BlockPos tablePos;
    private int myGen;

    public CraftGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.CRAFT && assistant.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && assistant.getTarget() == null && assistant.taskGen() == myGen;
    }

    @Override
    public void start() {
        this.job = assistant.peekJob();
        this.myGen = assistant.taskGen();
        this.crafted = 0;
        this.workTicks = 0;
        this.stuckTicks = 0;
        this.tablePos = null;
        this.plan = job != null && job.arg() != null ? resolve(job.arg()) : null;
        if (plan == null) {
            finish("I don't know how to craft " + (job != null ? job.arg() : "that") + ".");
        }
    }

    @Override
    public void stop() {
        this.job = null;
        this.plan = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.pollJob();
        this.job = null;
        this.plan = null;
        assistant.getNavigation().stop();
    }

    /** Natural-language name for a job arg ("iron_sword" -> "iron sword"). */
    private String pretty(@Nullable String arg) {
        if (arg == null) return "that";
        if (arg.equals("planks") || arg.equals("sticks")) return arg;
        Item it = RecipeBook.item(arg);
        return it == Items.AIR ? arg : RecipeBook.words(it);
    }

    @Override
    public void tick() {
        if (job == null || plan == null) return;

        if (crafted >= job.amount()) {
            finish("Crafted " + crafted + " " + pretty(job.arg()) + ".");
            return;
        }

        // Have the materials?
        for (Step step : plan.steps()) {
            if (assistant.countMatching(step.what()) < step.count()) {
                finish("I need " + step.count() + " " + step.label() + " to craft " + pretty(job.arg())
                    + (crafted > 0 ? " (made " + crafted + " so far)" : "")
                    + " — I can gather or you can hand it to me.");
                return;
            }
        }

        // Table logistics for recipes that need the 3x3 grid.
        if (plan.needsTable()) {
            if (tablePos == null || !assistant.level().getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)) {
                tablePos = findTable();
                stuckTicks = 0;
                if (tablePos == null && !placeOwnTable()) {
                    finish("I need a crafting table for " + pretty(job.arg())
                        + " — none nearby, and I lack 4 planks to make one.");
                    return;
                }
                if (tablePos == null) return; // table just placed; re-resolve next tick
            }
            double distSq = assistant.getEyePosition().distanceToSqr(
                tablePos.getX() + 0.5, tablePos.getY() + 0.5, tablePos.getZ() + 0.5);
            assistant.getLookControl().setLookAt(
                tablePos.getX() + 0.5, tablePos.getY() + 0.5, tablePos.getZ() + 0.5);
            if (distSq > AssistantEntity.BLOCK_REACH * AssistantEntity.BLOCK_REACH) {
                if (assistant.getNavigation().isDone()) {
                    assistant.getNavigation().moveTo(
                        tablePos.getX() + 0.5, tablePos.getY(), tablePos.getZ() + 0.5, 1.1D);
                }
                if (++stuckTicks > 140) {
                    finish("I couldn't reach the crafting table.");
                }
                return;
            }
        }

        // Put in the work, then produce.
        if (++workTicks < 20) {
            if (workTicks % 6 == 0) assistant.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            return;
        }
        workTicks = 0;
        for (Step step : plan.steps()) {
            assistant.removeMatching(step.what(), step.count());
        }
        ItemStack out = plan.output().get();
        ItemStack leftover = assistant.insertItem(out);
        if (!leftover.isEmpty()) assistant.spawnAtLocation(leftover);
        crafted++;
    }

    @Nullable
    private BlockPos findTable() {
        BlockPos feet = assistant.feetPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-16, -4, -16), feet.offset(16, 4, 16))) {
            if (!assistant.level().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) continue;
            double d = pos.distSqr(feet);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** No table around: craft one from planks and put it down next to us. */
    private boolean placeOwnTable() {
        if (assistant.countMatching(PLANK) < 4) return false;
        BlockPos feet = assistant.feetPos();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos pos = feet.relative(dir);
            if (assistant.level().getBlockState(pos).canBeReplaced()
                && assistant.level().getBlockState(pos.below()).isSolid()) {
                assistant.removeMatching(PLANK, 4);
                assistant.level().setBlockAndUpdate(pos, Blocks.CRAFTING_TABLE.defaultBlockState());
                assistant.say("Setting up a crafting table here.");
                return true;
            }
        }
        return false;
    }
}
