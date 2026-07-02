package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Deposit: walk to the nearest chest/barrel within 16 blocks and move the
 * assistant's whole inventory into it (skipping nothing — its kit lives in
 * its equipment slots, not the backpack).
 */
public class DepositGoal extends Goal {
    private static final int SEARCH_RADIUS = 16;

    private final AssistantEntity assistant;
    private boolean active;
    @Nullable private BlockPos chestPos;
    private int stuckTicks;

    public DepositGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return assistant.hasDepositRequest() && assistant.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return active && assistant.getTarget() == null;
    }

    @Override
    public void start() {
        assistant.takeDepositRequest();
        this.active = true;
        this.stuckTicks = 0;
        if (assistant.countItems() == 0) {
            finish("Nothing to stash — my pack is empty.");
            return;
        }
        this.chestPos = findChest();
        if (chestPos == null) {
            finish("No chest or barrel within " + SEARCH_RADIUS + " blocks.");
        }
    }

    @Override
    public void stop() {
        this.active = false;
        this.chestPos = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        this.active = false;
        this.chestPos = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (!active || chestPos == null) return;

        double distSq = assistant.distanceToSqr(
            chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5);
        assistant.getLookControl().setLookAt(
            chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5);

        if (distSq > 3.0 * 3.0) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(
                    chestPos.getX() + 0.5, chestPos.getY(), chestPos.getZ() + 0.5, 1.1D);
            }
            if (++stuckTicks > 120) {
                finish("I couldn't reach the chest.");
            }
            return;
        }

        BlockEntity be = assistant.level().getBlockEntity(chestPos);
        if (!(be instanceof Container container)) {
            finish("The chest is gone.");
            return;
        }

        int moved = 0;
        var items = assistant.getInventoryItems();
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) continue;
            ItemStack leftover = insertInto(container, stack);
            moved += stack.getCount() - leftover.getCount();
            items.set(i, leftover);
        }
        container.setChanged();
        finish(moved > 0 ? "Stashed " + moved + " items." : "That chest is full.");
    }

    private static ItemStack insertInto(Container container, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                container.setItem(i, remaining);
                return ItemStack.EMPTY;
            }
            if (ItemStack.isSameItemSameComponents(slot, remaining)) {
                int room = slot.getMaxStackSize() - slot.getCount();
                if (room > 0) {
                    int n = Math.min(room, remaining.getCount());
                    slot.grow(n);
                    remaining.shrink(n);
                }
            }
        }
        return remaining;
    }

    @Nullable
    private BlockPos findChest() {
        BlockPos feet = assistant.feetPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-SEARCH_RADIUS, -4, -SEARCH_RADIUS),
                feet.offset(SEARCH_RADIUS, 4, SEARCH_RADIUS))) {
            BlockEntity be = assistant.level().getBlockEntity(pos);
            if (!(be instanceof Container)) continue;
            double d = pos.distSqr(feet);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best;
    }
}
