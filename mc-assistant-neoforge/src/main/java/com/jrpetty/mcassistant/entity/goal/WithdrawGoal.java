package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.function.Predicate;

/**
 * Withdraw: the reverse of deposit — walk to a chest that holds the wanted
 * item (remembered chests first) and load up to N of it into the backpack.
 * "grab 10 iron from the chest", "take logs from storage", "get food from
 * the chest".
 */
public class WithdrawGoal extends Goal {

    private final AssistantEntity assistant;
    @Nullable private Job job;
    @Nullable private BlockPos chestPos;
    private int stuckTicks;
    private int myGen;

    public WithdrawGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** Turn a spoken item word into a stack matcher. */
    public static Predicate<ItemStack> matcherFor(String rawWord) {
        String w = rawWord.trim().toLowerCase();
        if (w.endsWith("s") && w.length() > 3) w = w.substring(0, w.length() - 1);
        final String word = w;
        // "torches" -> "torche" -> also try "torch" so plurals in -es match.
        final String base = word.endsWith("e") && word.length() > 3
            ? word.substring(0, word.length() - 1) : word;
        return switch (word) {
            case "everything", "stuff", "my stuff", "all", "loot", "item" -> s -> true;
            case "log", "wood" -> s -> s.is(ItemTags.LOGS);
            case "plank" -> s -> s.is(ItemTags.PLANKS);
            case "stone", "cobble", "cobblestone", "rock" -> s ->
                BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().contains("cobble")
                    || BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals("stone");
            case "food" -> s -> s.get(DataComponents.FOOD) != null;
            case "tool" -> ItemStack::isDamageableItem;
            default -> s -> {
                String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
                return path.contains(word) || path.contains(base);
            };
        };
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.WITHDRAW && assistant.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return job != null && assistant.getTarget() == null && assistant.taskGen() == myGen;
    }

    @Override
    public void start() {
        this.job = assistant.peekJob();
        this.myGen = assistant.taskGen();
        this.stuckTicks = 0;
        this.chestPos = null;
        if (job == null || job.arg() == null) {
            finish("I didn't catch what to fetch.");
            return;
        }
        this.chestPos = assistant.findChestWith(matcherFor(job.arg()), 24);
        if (chestPos == null) {
            finish("I can't find a chest with " + job.arg() + " within 24 blocks.");
        }
    }

    @Override
    public void stop() {
        this.job = null;
        this.chestPos = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.pollJob();
        this.job = null;
        this.chestPos = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (job == null || chestPos == null) return;

        double distSq = assistant.getEyePosition().distanceToSqr(
            chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5);
        assistant.getLookControl().setLookAt(
            chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5);

        if (distSq > AssistantEntity.BLOCK_REACH * AssistantEntity.BLOCK_REACH) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(
                    chestPos.getX() + 0.5, chestPos.getY(), chestPos.getZ() + 0.5, 1.1D);
            }
            if (++stuckTicks > 140) {
                finish("I couldn't reach the chest.");
            }
            return;
        }

        BlockEntity be = assistant.level().getBlockEntity(chestPos);
        if (!(be instanceof Container container)) {
            finish("The chest is gone.");
            return;
        }

        Predicate<ItemStack> match = matcherFor(job.arg());
        int wanted = job.amount();
        int moved = 0;
        for (int i = 0; i < container.getContainerSize() && moved < wanted; i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty() || !match.test(slot)) continue;
            int take = Math.min(wanted - moved, slot.getCount());
            ItemStack taking = slot.copyWithCount(take);
            ItemStack leftover = assistant.insertItem(taking);
            int actuallyTaken = take - leftover.getCount();
            slot.shrink(actuallyTaken);
            if (slot.isEmpty()) container.setItem(i, ItemStack.EMPTY);
            moved += actuallyTaken;
            if (!leftover.isEmpty()) break; // backpack is full
        }
        container.setChanged();
        assistant.rememberChest(chestPos, container);
        finish(moved > 0
            ? "Got " + moved + " " + job.arg() + " from the chest."
            : "That chest had no " + job.arg() + " (or my pack is full).");
    }
}
