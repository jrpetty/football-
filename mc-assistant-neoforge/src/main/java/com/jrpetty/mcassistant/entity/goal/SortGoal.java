package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Job;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto-sort storage: gather every item of the same kind into ONE "home"
 * chest (the chest that already holds the most of it), so a storage room
 * ends up one-item-type-per-chest. Moves happen server-side while the
 * assistant stands in the middle of the room, a few stacks at a time.
 */
public class SortGoal extends Goal {

    private static final int RADIUS = 20;
    private static final int MOVES_PER_TICK = 4;

    private final AssistantEntity assistant;
    @Nullable private Job job;
    private final List<BlockPos> chests = new ArrayList<>();
    private final Map<String, BlockPos> homes = new HashMap<>();
    @Nullable private BlockPos center;
    private boolean sorting;
    private int moved;
    private int idleScans;
    private int stuckTicks;
    private int myGen;

    public SortGoal(AssistantEntity assistant) {
        this.assistant = assistant;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Job j = assistant.peekJob();
        return j != null && j.type() == Job.Type.SORT && assistant.getTarget() == null;
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
        this.chests.clear();
        this.homes.clear();
        this.sorting = false;
        this.moved = 0;
        this.idleScans = 0;
        this.stuckTicks = 0;

        collectChests();
        if (chests.size() < 2) {
            finish("I need at least two chests to sort — stand me near your storage room "
                + "(within ~20 blocks) with 2+ chests, then say \"sort the storage\" again.");
            return;
        }
        assignHomes();
        // Stand in the middle of the room while sorting.
        long sx = 0;
        long sz = 0;
        int sy = assistant.getBlockY();
        for (BlockPos p : chests) {
            sx += p.getX();
            sz += p.getZ();
            sy = p.getY();
        }
        this.center = new BlockPos((int) (sx / chests.size()), sy, (int) (sz / chests.size()));
        assistant.say("Organizing " + chests.size() + " chests — one item type per chest.");
    }

    @Override
    public void stop() {
        this.job = null;
        assistant.getNavigation().stop();
    }

    private void finish(String message) {
        assistant.say(message);
        assistant.pollJob();
        this.job = null;
        assistant.getNavigation().stop();
    }

    private void collectChests() {
        BlockPos feet = assistant.feetPos();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        // Every remembered chest that's still loaded — the storage room may be a
        // few blocks past our scan radius, so don't gate remembered ones on range.
        for (BlockPos remembered : assistant.rememberedChests()) {
            if (assistant.level().getBlockEntity(remembered) instanceof Container
                && seen.add(remembered.asLong())) {
                chests.add(remembered.immutable());
            }
        }
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-RADIUS, -4, -RADIUS), feet.offset(RADIUS, 4, RADIUS))) {
            if (assistant.level().getBlockEntity(pos) instanceof Container && seen.add(pos.asLong())) {
                chests.add(pos.immutable());
            }
        }
    }

    /** Each item type's home = the chest that already holds the most of it. */
    private void assignHomes() {
        Map<String, Map<BlockPos, Integer>> tally = new HashMap<>();
        for (BlockPos pos : chests) {
            if (!(assistant.level().getBlockEntity(pos) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) continue;
                String key = keyOf(s);
                tally.computeIfAbsent(key, k -> new HashMap<>()).merge(pos, s.getCount(), Integer::sum);
            }
        }
        for (Map.Entry<String, Map<BlockPos, Integer>> e : tally.entrySet()) {
            BlockPos home = null;
            int best = -1;
            for (Map.Entry<BlockPos, Integer> c : e.getValue().entrySet()) {
                if (c.getValue() > best) {
                    best = c.getValue();
                    home = c.getKey();
                }
            }
            if (home != null) homes.put(e.getKey(), home);
        }
    }

    private static String keyOf(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    @Override
    public void tick() {
        if (job == null || center == null) return;

        if (!sorting) {
            double d = assistant.distanceToSqr(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
            if (d > 9.0) {
                if (assistant.getNavigation().isDone()) {
                    assistant.getNavigation().moveTo(center.getX() + 0.5, center.getY(), center.getZ() + 0.5, 1.1D);
                }
                if (++stuckTicks > 120) sorting = true; // sort from wherever we ended up
                return;
            }
            sorting = true;
        }

        if (assistant.tickCount % 5 != 0) return;
        int did = 0;
        for (int k = 0; k < MOVES_PER_TICK; k++) {
            if (doOneMove()) {
                did++;
            } else {
                break;
            }
        }
        if (did == 0) {
            // Nothing moved this pass; confirm it's really done before finishing.
            if (++idleScans >= 2) {
                for (BlockPos pos : chests) {
                    if (assistant.level().getBlockEntity(pos) instanceof Container c) {
                        assistant.rememberChest(pos, c);
                    }
                }
                finish(moved > 0 ? "Storage sorted — moved " + moved + " stacks into place."
                    : "Storage's already tidy — nothing to move.");
            }
        } else {
            idleScans = 0;
        }
    }

    /** Move one misplaced stack toward its home chest. Returns true if it moved. */
    private boolean doOneMove() {
        for (BlockPos from : chests) {
            if (!(assistant.level().getBlockEntity(from) instanceof Container src)) continue;
            for (int i = 0; i < src.getContainerSize(); i++) {
                ItemStack s = src.getItem(i);
                if (s.isEmpty()) continue;
                BlockPos home = homes.get(keyOf(s));
                if (home == null || home.equals(from)) continue;
                if (!(assistant.level().getBlockEntity(home) instanceof Container dst)) continue;
                ItemStack remaining = insertInto(dst, s.copy());
                int movedCount = s.getCount() - remaining.getCount();
                if (movedCount > 0) {
                    src.setItem(i, remaining.isEmpty() ? ItemStack.EMPTY : remaining);
                    src.setChanged();
                    dst.setChanged();
                    moved++;
                    return true;
                }
            }
        }
        return false;
    }

    private static ItemStack insertInto(Container container, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, remaining)) {
                int room = slot.getMaxStackSize() - slot.getCount();
                if (room > 0) {
                    int n = Math.min(room, remaining.getCount());
                    slot.grow(n);
                    remaining.shrink(n);
                }
            }
        }
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            if (container.getItem(i).isEmpty()) {
                container.setItem(i, remaining);
                return ItemStack.EMPTY;
            }
        }
        return remaining;
    }
}
