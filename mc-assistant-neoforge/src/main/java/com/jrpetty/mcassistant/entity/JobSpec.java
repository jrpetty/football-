package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * What each specialisation needs before it can run on its own: the tools the
 * player hands the bot, and the fixtures its work zone must contain (nearly
 * every job wants a chest to stash its output in). A job whose needs are all
 * met runs unattended; anything missing is reported by name, in the management
 * screen and by the bot itself.
 */
public final class JobSpec {

    private JobSpec() {}

    /** Radius around the work zone's centre we accept fixtures (chest/furnace/water) in. */
    private static final int FIXTURE_RANGE = 12;

    /** Human-readable checklist for a job, shown before it's even assigned. */
    public static List<String> checklist(AssistantEntity.StationTask task) {
        if (task == AssistantEntity.StationTask.NONE) return List.of();
        List<String> all = new ArrayList<>(jobKit(task));
        all.add("food + redstone (upkeep)");
        return all;
    }

    private static List<String> jobKit(AssistantEntity.StationTask task) {
        return switch (task) {
            case FARM -> List.of("a hoe", "a chest");
            case WOOD -> List.of("an axe", "a chest");
            case MINE -> List.of("a pickaxe", "8 torches", "a chest");
            case RANCH -> List.of("shears", "a chest");
            case GUARD -> List.of("a sword", "8 torches");
            case SMELT -> List.of("a furnace in the zone", "fuel (coal or logs)", "a chest");
            case HAUL -> List.of("a chest in the zone", "a home to deliver to");
            case FISH -> List.of("a fishing rod", "water in the zone", "a chest");
            case STORE -> List.of("two chests in the zone");
            case NONE -> List.of();
        };
    }

    /** Everything this bot is still missing for its job — empty means it can work. */
    public static List<String> missing(AssistantEntity a) {
        AssistantEntity.StationTask task = a.stationTask();
        List<String> gaps = new ArrayList<>(3);
        if (task == AssistantEntity.StationTask.NONE) return gaps;

        // Upkeep is not optional: every job is gated on it, so it belongs on
        // every checklist. Silently stalling ten minutes after the player walks
        // away — with a green "Working" status — is the worst failure this mod
        // can have. Carried or stocked at the station both count.
        if (a.countCarried(s -> s.get(net.minecraft.core.component.DataComponents.FOOD) != null) == 0
            && !stockedNearby(a, s -> s.get(net.minecraft.core.component.DataComponents.FOOD) != null)) {
            gaps.add("food (its rations)");
        }
        if (a.countCarried(s -> s.is(Items.REDSTONE)) == 0
            && !stockedNearby(a, s -> s.is(Items.REDSTONE))) {
            gaps.add("redstone (its core charge)");
        }

        switch (task) {
            case FARM -> {
                if (!hasTool(a, "_hoe")) gaps.add("a hoe");
                needChest(a, gaps, 1);
            }
            case WOOD -> {
                if (!hasTool(a, "_axe")) gaps.add("an axe");
                needChest(a, gaps, 1);
            }
            case MINE -> {
                if (!hasTool(a, "_pickaxe")) gaps.add("a pickaxe");
                if (a.countCarried(s -> s.is(Items.TORCH)) < 8) gaps.add("8 torches");
                needChest(a, gaps, 1);
            }
            case RANCH -> {
                if (a.countCarried(s -> s.is(Items.SHEARS)) == 0) gaps.add("shears");
                needChest(a, gaps, 1);
            }
            case GUARD -> {
                if (!hasTool(a, "_sword")) gaps.add("a sword");
                if (a.countCarried(s -> s.is(Items.TORCH)) < 8) gaps.add("8 torches");
            }
            case SMELT -> {
                if (!fixtureNearby(a, FixtureKind.FURNACE)) gaps.add("a furnace in the zone");
                boolean fuel = a.countCarried(s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)) > 0
                    || a.countCarried(s -> s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS)) > 0
                    || stockedNearby(a, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL));
                if (!fuel) gaps.add("fuel (coal or logs)");
                needChest(a, gaps, 1);
            }
            case HAUL -> {
                needChest(a, gaps, 1);
                if (a.getHome() == null) gaps.add("a home to deliver to");
            }
            case FISH -> {
                if (a.countCarried(s -> s.is(Items.FISHING_ROD)) == 0) gaps.add("a fishing rod");
                if (!fixtureNearby(a, FixtureKind.WATER)) gaps.add("water in the zone");
                needChest(a, gaps, 1);
            }
            case STORE -> needChest(a, gaps, 2);
            case NONE -> { }
        }
        return gaps;
    }

    private static void needChest(AssistantEntity a, List<String> gaps, int count) {
        if (countFixtures(a, FixtureKind.CHEST, count) < count) {
            gaps.add(count > 1 ? count + " chests in the zone" : "a chest in the zone");
        }
    }

    /** Tool check by item id suffix — matches any material (wood..netherite). */
    private static boolean hasTool(AssistantEntity a, String suffix) {
        return a.countCarried(s -> !s.isEmpty()
            && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith(suffix)) > 0;
    }

    private enum FixtureKind { CHEST, FURNACE, WATER }

    private static boolean fixtureNearby(AssistantEntity a, FixtureKind kind) {
        return countFixtures(a, kind, 1) >= 1;
    }

    /** Scan the zone centre (or the bot itself) for up to `want` matching fixtures. */
    private static int countFixtures(AssistantEntity a, FixtureKind kind, int want) {
        WorkZone zone = a.workZone();
        BlockPos origin = zone != null ? zone.center() : (a.stationPos() != null ? a.stationPos() : a.feetPos());
        int found = 0;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-FIXTURE_RANGE, -5, -FIXTURE_RANGE),
                origin.offset(FIXTURE_RANGE, 5, FIXTURE_RANGE))) {
            boolean hit = switch (kind) {
                case CHEST -> a.level().getBlockEntity(pos) instanceof Container
                    && !(a.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity);
                case FURNACE -> a.level().getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity
                    || a.level().getBlockState(pos).is(Blocks.FURNACE);
                case WATER -> a.level().getBlockState(pos).is(Blocks.WATER);
            };
            if (hit && ++found >= want) return found;
        }
        return found;
    }

    /** Is a nearby chest already stocked with this? (fuel deliveries, seed stock) */
    private static boolean stockedNearby(AssistantEntity a, Predicate<ItemStack> what) {
        WorkZone zone = a.workZone();
        BlockPos origin = zone != null ? zone.center() : (a.stationPos() != null ? a.stationPos() : a.feetPos());
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-FIXTURE_RANGE, -5, -FIXTURE_RANGE),
                origin.offset(FIXTURE_RANGE, 5, FIXTURE_RANGE))) {
            if (!(a.level().getBlockEntity(pos) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && what.test(s)) return true;
            }
        }
        return false;
    }
}
