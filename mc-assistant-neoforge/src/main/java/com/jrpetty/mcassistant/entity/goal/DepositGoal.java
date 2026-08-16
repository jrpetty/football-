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
    private double bestDistSq = Double.MAX_VALUE;
    private int myGen;

    public DepositGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    private static boolean isDeposit(com.jrpetty.mcassistant.entity.Job j) {
        return j != null && j.type() == com.jrpetty.mcassistant.entity.Job.Type.DEPOSIT;
    }

    @Override
    public boolean canUse() {
        return isDeposit(assistant.peekJob()) && assistant.getTarget() == null;
    }

    @Override
    public boolean canContinueToUse() {
        return active && assistant.getTarget() == null && assistant.taskGen() == myGen;
    }

    @Override
    public void start() {
        this.myGen = assistant.taskGen();
        this.active = true;
        this.stuckTicks = 0;
        this.bestDistSq = Double.MAX_VALUE;
        if (assistant.countItems() == 0) {
            finish("Nothing to stash — my pack is empty.");
            return;
        }
        // A town-work deposit names its depot. Otherwise: does a crewmate
        // actually need what we are carrying? Ore belongs in the smelter's
        // chest and wheat where the rancher can reach it, not in whichever
        // chest happens to be closest to where we finished working.
        BlockPos targeted = targetedChest();
        if (targeted == null) {
            targeted = com.jrpetty.mcassistant.entity.Supply.routeFor(assistant);
            if (targeted != null) {
                assistant.sayRoutine("Running this load over to where it's wanted.");
            }
        }
        this.chestPos = targeted != null ? targeted : findChest();
        if (chestPos == null) {
            // No chest here — but maybe we remember one (home base, the depot).
            this.chestPos = assistant.nearestRememberedChest(160);
            if (chestPos != null) {
                assistant.say("No chest nearby — heading to the one I remember.");
            }
        }
        if (chestPos == null) {
            finish("No chest or barrel within " + SEARCH_RADIUS + " blocks, and I don't remember one.");
        }
    }

    @Override
    public void stop() {
        this.active = false;
        this.chestPos = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) { finish(message, false); }

    /** End the job without narrating it — routine stashing is not news. */
    private void finishQuiet(boolean productive) {
        assistant.noteJobOutcome(productive);
        assistant.pollJob();
        this.active = false;
        this.chestPos = null;
        assistant.getNavigation().stop();
    }

    /** Job finished (or couldn't run) — drop it from the queue and move on.
     *  productive=false cools off the idle brain so a no-chest/full-chest deposit
     *  doesn't get re-attempted every idle cycle. */
    private void finish(String message, boolean productive) {
        assistant.say(message);
        assistant.noteJobOutcome(productive);
        assistant.pollJob();
        this.active = false;
        this.chestPos = null;
        assistant.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (!active || chestPos == null) return;

        // Player-parity reach: use a chest from 4.5 blocks, like a player would.
        double distSq = assistant.getEyePosition().distanceToSqr(
            chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5);
        assistant.getLookControl().setLookAt(
            chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5);

        if (distSq > AssistantEntity.BLOCK_REACH * AssistantEntity.BLOCK_REACH) {
            if (assistant.getNavigation().isDone()) {
                assistant.getNavigation().moveTo(
                    chestPos.getX() + 0.5, chestPos.getY(), chestPos.getZ() + 0.5, 1.1D);
            }
            // Progress-based watchdog: long walks to a remembered chest are
            // fine; standing still without getting closer is not.
            if (distSq < bestDistSq - 1.0) {
                bestDistSq = distSq;
                stuckTicks = 0;
            } else if (++stuckTicks > 300) {
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
        // Station reserve: a stationed farmer keeps its seed stock, a stationed
        // lumberjack its saplings — only the surplus above the reserve is stashed.
        java.util.Map<net.minecraft.world.item.Item, Integer> kept = new java.util.HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) continue;
            int keep = 0;
            int reserve = assistant.depositReserve(stack);
            if (reserve > 0) {
                int already = kept.getOrDefault(stack.getItem(), 0);
                keep = Math.max(0, Math.min(stack.getCount(), reserve - already));
                kept.put(stack.getItem(), already + keep);
                if (keep >= stack.getCount()) continue; // whole stack is reserve
            }
            ItemStack toMove = stack.copyWithCount(stack.getCount() - keep);
            ItemStack leftover = insertInto(container, toMove);
            int stashed = toMove.getCount() - leftover.getCount();
            moved += stashed;
            // Tally it for the daily production report / crew roster.
            assistant.noteProduced(stack.getItem(), stashed);
            int remain = keep + leftover.getCount();
            items.set(i, remain == 0 ? ItemStack.EMPTY : stack.copyWithCount(remain));
        }
        container.setChanged();
        assistant.rememberChest(chestPos, container); // storage memory: learn what's where
        if (moved == 0) assistant.noteDepositBlocked(); else assistant.noteStashed();
        if (moved > 0) { assistant.sayRoutine("Stashed " + moved + " items."); finishQuiet(true); }
        else finish("That chest is full.", false);
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

    /** The specific depot named by a town-work deposit job ("x y z"), or null. */
    @Nullable
    private BlockPos targetedChest() {
        com.jrpetty.mcassistant.entity.Job j = assistant.peekJob();
        if (j == null || j.arg() == null) return null;
        String[] p = j.arg().split(" ");
        if (p.length != 3) return null;
        try {
            return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private BlockPos findChest() {
        BlockPos feet = assistant.feetPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (com.jrpetty.mcassistant.entity.ZoneChests.Found found
                : com.jrpetty.mcassistant.entity.ZoneChests.around(
                    assistant.level(), feet, SEARCH_RADIUS, 4)) {
            if (!found.stillThere()) continue;
            // Never stash into a furnace: it is a Container, so a smelter was
            // posting its finished ingots straight back into the furnace they
            // came out of, and its output chest never saw a single item.
            if (found.blockEntity()
                    instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) {
                continue;
            }
            double d = found.pos().distSqr(feet);
            if (d < bestDist) {
                bestDist = d;
                best = found.pos();
            }
        }
        return best;
    }
}
