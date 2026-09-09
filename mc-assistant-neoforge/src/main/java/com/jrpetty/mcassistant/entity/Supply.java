package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * Supply chains between specialists. A miner's ore belongs in the smelter's
 * chest, a farmer's wheat belongs where the rancher can reach it, and spare
 * food belongs with whoever is about to run out — none of which should require
 * the player to walk a load across the base by hand.
 *
 * <p>When a bot goes to stash a load it asks here first: is there a crewmate
 * whose job actually eats this, with somewhere to put it? If so the load goes
 * there instead of into the nearest chest. If not, nothing changes and it
 * stashes where it always did.
 *
 * <p>Deliberately narrow. Three chains, each one obvious, rather than a general
 * routing system that surprises you by moving things you wanted left alone.
 */
public final class Supply {

    private Supply() {}

    /** How far a load will travel to reach the right chest. */
    private static final double REACH = 96.0;

    /** Fuel a smelter burns. */
    private static final Predicate<ItemStack> FUEL =
        s -> s.is(Items.COAL) || s.is(Items.CHARCOAL) || s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS);

    private static final Predicate<ItemStack> FOOD =
        s -> s.get(DataComponents.FOOD) != null;

    /**
     * Where this load should really go, or null to stash it as usual.
     *
     * @param carrier the bot about to deposit
     */
    @Nullable
    public static BlockPos routeFor(AssistantEntity carrier) {
        if (carrier.getOwnerId() == null) return null;
        if (carrier.stationTask() == AssistantEntity.StationTask.HAUL) return null; // has its own run

        // Somebody asked out loud, and we are holding it. That beats any
        // standing chain: a posted need is a specialist that has actually
        // stopped working, not just a chest that would like to be fuller.
        Requests.Request asked = Requests.matching(carrier.getOwnerId(), carrier, carrier.tickCount);
        if (asked != null) {
            for (AssistantEntity mate : AssistantEntity.allFor(carrier.getOwnerId())) {
                if (!mate.getUUID().equals(asked.asker()) || !mate.isAlive()) continue;
                if (mate.distanceToSqr(carrier) > REACH * REACH) break;
                BlockPos chest = roomIn(mate);
                if (chest != null) return chest;
                break;
            }
        }

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (AssistantEntity mate : AssistantEntity.allFor(carrier.getOwnerId())) {
            if (mate == carrier || !mate.isAlive()) continue;
            double d = mate.distanceToSqr(carrier);
            if (d > REACH * REACH || d >= bestDist) continue;

            Predicate<ItemStack> wanted = wants(carrier, mate);
            if (wanted == null || carrier.countCarried(wanted) <= 0) continue;

            BlockPos chest = roomIn(mate);
            if (chest == null) continue;
            bestDist = d;
            best = chest;
        }
        return best;
    }

    /**
     * What {@code mate} would take off {@code carrier}'s hands. Null when there
     * is no chain between these two jobs.
     */
    @Nullable
    private static Predicate<ItemStack> wants(AssistantEntity carrier, AssistantEntity mate) {
        switch (mate.stationTask()) {
            case SMELT -> {
                // The two things a smeltery is always short of.
                return s -> AssistantEntity.SMELTABLE_ORE.test(s) || FUEL.test(s);
            }
            case RANCH -> {
                return AssistantEntity.BREEDING_FOOD;
            }
            default -> {
                // Anyone with an empty larder gets fed, whatever their trade —
                // an unpaid, unfed specialist stops working, and a farmer three
                // fields away is usually holding exactly what it needs.
                if (carrier.stationTask() != AssistantEntity.StationTask.FARM
                    && carrier.stationTask() != AssistantEntity.StationTask.FISH) {
                    return null;
                }
                if (mate.stationTask() == AssistantEntity.StationTask.NONE) return null;
                if (mate.countCarried(FOOD) > 0) return null;
                if (ZoneChests.anyHolds(mate.linkedChests(), FOOD)) return null;
                return FOOD;
            }
        }
    }

    /** One of this bot's linked chests with space in it, nearest to its post. */
    @Nullable
    private static BlockPos roomIn(AssistantEntity mate) {
        for (ZoneChests.Found f : mate.linkedChests()) {
            if (!f.stillThere()) continue;
            if (f.blockEntity() instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) {
                continue;   // a smelter's furnace is not its input chest
            }
            Container c = f.container();
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (c.getItem(i).isEmpty()) return f.pos();
            }
        }
        return null;
    }
}
