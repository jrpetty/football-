package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
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
 *
 * <p>A specialist is linked to the chests in its zone, so anything it needs
 * counts as present whether it is in its own pack or sat in one of those
 * chests. Stock the chest and the bot supplies itself; it only asks the player
 * for something that is genuinely nowhere.
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
            case RANCH -> List.of("shears", "2+ animals in the zone", "breeding food", "a chest");
            case GUARD -> List.of("a sword", "torches (it lights the area)");
            case SMELT -> List.of("a furnace in the zone", "raw ore to smelt", "fuel (coal or logs)", "a chest");
            case HAUL -> List.of("a pickup chest (wand-click it)", "a delivery chest (wand-click it second)");
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

        // One look at the stores answers every question below. Everything a job
        // needs counts as held whether it's in the pack or in one of these.
        List<ZoneChests.Found> stores = a.linkedChests();

        // Upkeep is not optional: every job is gated on it, so it belongs on
        // every checklist. Silently stalling ten minutes after the player walks
        // away — with a green "Working" status — is the worst failure this mod
        // can have. Carried or stocked at the station both count.
        if (!held(a, stores, s -> s.get(net.minecraft.core.component.DataComponents.FOOD) != null)) {
            gaps.add("food (its rations)");
        }
        if (!held(a, stores, s -> s.is(Items.REDSTONE))) {
            gaps.add("redstone (its core charge)");
        }

        switch (task) {
            case FARM -> {
                // A hoe only speeds up breaking new ground; the steady harvest
                // and replant loop never uses one, so a worn-out hoe must not
                // stop the whole farm.
                needChest(a, stores, gaps, 1);
            }
            case WOOD -> {
                if (!heldTool(a, stores, "_axe")) gaps.add("an axe");
                needChest(a, stores, gaps, 1);
            }
            case MINE -> {
                if (!heldTool(a, stores, "_pickaxe")) gaps.add("a pickaxe");
                if (a.countCarried(s -> s.is(Items.TORCH))
                    + ZoneChests.countIn(stores, s -> s.is(Items.TORCH)) < 8) {
                    gaps.add("8 torches");
                }
                needChest(a, stores, gaps, 1);
            }
            case RANCH -> {
                if (!held(a, stores, s -> s.is(Items.SHEARS))) gaps.add("shears");
                // Without livestock a rancher is a permanent silent no-op, and
                // it cannot obtain animals itself; without feed every breed job
                // aborts the moment it starts.
                if (a.adultAnimalsNearby(16) < 2) gaps.add("at least 2 adult animals in the zone");
                boolean feed = a.countCarried(AssistantEntity.BREEDING_FOOD) >= 2
                    || ZoneChests.anyHolds(stores, AssistantEntity.BREEDING_FOOD);
                if (!feed) gaps.add("breeding food (wheat, carrots or seeds)");
                needChest(a, stores, gaps, 1);
            }
            case GUARD -> {
                // Torches are what a guard SPENDS lighting its patch, so they
                // are not a precondition — gating on them stopped a guard
                // defending at all once it had used them up.
                if (!heldTool(a, stores, "_sword")) gaps.add("a sword");
            }
            case SMELT -> {
                if (!furnaceIn(stores)) gaps.add("a furnace in the zone");
                boolean fuel = held(a, stores, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL))
                    || a.countCarried(s -> s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS)) > 0;
                if (!fuel) gaps.add("fuel (coal or logs)");
                if (!held(a, stores, AssistantEntity.SMELTABLE_ORE)) {
                    gaps.add("raw ore in the input chest");
                }
                needChest(a, stores, gaps, 1);
            }
            case HAUL -> {
                // The route is two wand-linked chests, nothing else: no zone,
                // no home, no minimum distance. If both ends stand, it can run.
                if (a.preferredChest() == null) {
                    gaps.add("a pickup chest (wand-click it)");
                }
                if (a.deliveryChest() == null) {
                    gaps.add("a delivery chest (wand-click it second)");
                }
            }
            case FISH -> {
                if (!held(a, stores, s -> s.is(Items.FISHING_ROD))) gaps.add("a fishing rod");
                if (!a.waterInZone()) gaps.add("water in the zone");
                needChest(a, stores, gaps, 1);
            }
            case STORE -> needChest(a, stores, gaps, 2);
            case NONE -> { }
        }
        return gaps;
    }

    /** In its pack, or in one of the chests it's linked to. Either counts:
     *  a specialist that can fetch a thing for itself should never ask for it. */
    private static boolean held(AssistantEntity a, List<ZoneChests.Found> stores,
                                Predicate<ItemStack> what) {
        return a.countCarried(what) > 0 || ZoneChests.anyHolds(stores, what);
    }

    /** Tool check by item id suffix — matches any material (wood..netherite). */
    private static boolean heldTool(AssistantEntity a, List<ZoneChests.Found> stores, String suffix) {
        return held(a, stores, s -> !s.isEmpty()
            && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith(suffix));
    }

    /** Is there somewhere to unload at this spot? Used for a hauler's drop-off. */
    private static boolean chestNear(AssistantEntity a, BlockPos where) {
        for (ZoneChests.Found f : ZoneChests.around(a.level(), where, FIXTURE_RANGE, 5)) {
            if (isStorage(f)) return true;
        }
        return false;
    }

    private static void needChest(AssistantEntity a, List<ZoneChests.Found> stores,
                                  List<String> gaps, int count) {
        int found = 0;
        for (ZoneChests.Found f : stores) {
            if (isStorage(f) && ++found >= count) return;
        }
        gaps.add(count > 1 ? count + " chests in the zone" : "a chest in the zone");
    }

    /** A chest, not a furnace — a smelter's furnace must not pass for its output chest. */
    private static boolean isStorage(ZoneChests.Found f) {
        return f.stillThere() && !(f.blockEntity() instanceof AbstractFurnaceBlockEntity);
    }

    private static boolean furnaceIn(List<ZoneChests.Found> stores) {
        for (ZoneChests.Found f : stores) {
            if (f.stillThere() && f.blockEntity() instanceof AbstractFurnaceBlockEntity) return true;
            if (f.stillThere() && f.blockEntity().getBlockState().is(Blocks.FURNACE)) return true;
        }
        return false;
    }
}
