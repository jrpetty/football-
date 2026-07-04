package com.jrpetty.mcassistant.entity.goal;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Predicate;

/**
 * Night survival — the "I need shelter" instinct. When autonomy is on and night
 * falls out in the open with monsters about, the assistant stops what it's doing
 * and walls itself into a 1x1x2 pocket with whatever full blocks it carries
 * (dirt, cobble, planks, logs...), waits out the night in safety, and breaks
 * back out at dawn, reclaiming the blocks.
 *
 * Priority 1 so it preempts jobs and combat when it matters, but it yields to
 * RetreatGoal (also prio 1) via the !isRetreating() gate — flee first, then
 * shelter. A roofed/underground spot counts as already safe, so it won't seal
 * up when it doesn't need to. Part of the end goal: dropped in the world, it
 * lives through the night on its own.
 */
public class NightShelterGoal extends Goal {

    /** Full solid blocks it's happy to build an emergency box from. */
    public static final Predicate<ItemStack> SHELTER_BLOCK = s -> {
        if (!(s.getItem() instanceof BlockItem)) return false;
        return s.is(ItemTags.PLANKS) || s.is(ItemTags.LOGS)
            || s.is(Items.DIRT) || s.is(Items.COARSE_DIRT) || s.is(Items.GRASS_BLOCK)
            || s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE)
            || s.is(Items.STONE) || s.is(Items.DEEPSLATE) || s.is(Items.NETHERRACK)
            || s.is(Items.TUFF) || s.is(Items.ANDESITE) || s.is(Items.DIORITE) || s.is(Items.GRANITE);
    };

    private static final int THREAT_RADIUS = 20;
    private static final int MIN_BLOCKS = 5;

    private enum Phase { SEAL, OPEN }

    private final AssistantEntity a;
    private Phase phase = Phase.SEAL;
    @Nullable private BlockPos base;
    private boolean announced;

    public NightShelterGoal(AssistantEntity a) {
        this.a = a;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!a.isAutonomous() || a.isRetreating()) return false;
        return a.level().isNight() ? nightNeed() : dayBreakout();
    }

    /** Night, out in the open, threatened, with blocks to build and not sealed yet. */
    private boolean nightNeed() {
        if (a.isNightHome() && a.getHome() != null) return false; // the go-home routine handles it
        if (!a.onGround()) return false;
        if (!a.level().canSeeSky(a.feetPos().above())) return false; // already under a roof
        if (a.countMatching(SHELTER_BLOCK) < MIN_BLOCKS) return false;
        if (monstersNear() == 0) return false;
        return !enclosed(a.feetPos());
    }

    /** Daytime, still boxed into our own pocket, safe to open up. */
    private boolean dayBreakout() {
        BlockPos feet = a.feetPos();
        return enclosed(feet) && roofed(feet) && monstersNear() == 0;
    }

    @Override
    public boolean canContinueToUse() {
        if (!a.isAutonomous() || a.isRetreating()) return false;
        if (phase == Phase.SEAL) return a.level().isNight() || enclosed(a.feetPos());
        return enclosed(a.feetPos());
    }

    @Override
    public void start() {
        this.base = a.feetPos();
        this.announced = false;
        this.phase = a.level().isNight() ? Phase.SEAL : Phase.OPEN;
        a.getNavigation().stop();
    }

    @Override
    public void stop() {
        this.base = null;
        this.announced = false;
        a.getNavigation().stop();
    }

    @Override
    public void tick() {
        // Dawn while sealed: switch to breaking out.
        if (phase == Phase.SEAL && !a.level().isNight()) {
            phase = Phase.OPEN;
            announced = false;
        }
        BlockPos feet = base != null ? base : a.feetPos();
        a.getNavigation().stop();
        a.getLookControl().setLookAt(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
        if (phase == Phase.SEAL) tickSeal(feet);
        else tickOpen(feet);
    }

    private void tickSeal(BlockPos feet) {
        if (!announced) {
            a.say("Night, and monsters about — walling myself in until dawn.");
            announced = true;
        }
        BlockPos head = feet.above();
        List<BlockPos> cells = new ArrayList<>();
        cells.add(feet.below());     // floor, so we don't drop into a cave
        for (Direction d : Direction.Plane.HORIZONTAL) {
            cells.add(feet.relative(d));
            cells.add(head.relative(d));
        }
        cells.add(head.above());     // roof
        int placed = 0;
        for (BlockPos p : cells) {
            if (placed >= 2) break; // a couple per tick — sealed within a second
            if (!a.level().getBlockState(p).canBeReplaced()) continue;
            if (placeBlock(p)) placed++;
        }
        if (placed > 0) a.swing(InteractionHand.MAIN_HAND);
    }

    private void tickOpen(BlockPos feet) {
        if (monstersNear() > 0) return; // still dangerous out there — stay put
        if (!announced) {
            a.say("Morning — breaking back out.");
            announced = true;
        }
        BlockPos head = feet.above();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos wf = feet.relative(d);
            BlockPos wh = head.relative(d);
            if (a.level().getBlockState(wf).isSolid() || a.level().getBlockState(wh).isSolid()) {
                breakBlock(wf);
                breakBlock(wh);
                a.swing(InteractionHand.MAIN_HAND);
                return; // one open side is enough — canContinueToUse ends it next tick
            }
        }
    }

    private boolean placeBlock(BlockPos p) {
        List<ItemStack> inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !SHELTER_BLOCK.test(s)) continue;
            if (!(s.getItem() instanceof BlockItem bi)) continue;
            a.level().setBlockAndUpdate(p, bi.getBlock().defaultBlockState());
            s.shrink(1);
            if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
            return true;
        }
        return false;
    }

    private void breakBlock(BlockPos p) {
        if (!a.level().getBlockState(p).isSolid()) return;
        if (a.level().destroyBlock(p, true, a)) {
            for (ItemEntity drop : a.level().getEntitiesOfClass(
                    ItemEntity.class, new AABB(p).inflate(2.0))) {
                ItemStack left = a.insertItem(drop.getItem());
                if (left.isEmpty()) drop.discard();
                else drop.setItem(left);
            }
        }
    }

    private boolean enclosed(BlockPos feet) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!a.level().getBlockState(feet.relative(d)).isSolid()) return false;
        }
        return true;
    }

    private boolean roofed(BlockPos feet) {
        return a.level().getBlockState(feet.above(2)).isSolid();
    }

    private int monstersNear() {
        return a.level().getEntitiesOfClass(
            Monster.class, a.getBoundingBox().inflate(THREAT_RADIUS), Monster::isAlive).size();
    }
}
