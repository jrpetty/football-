package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/** [pa33] The plain words of an order ("logs", "iron", "food") as the items they mean, and how many it carries. */
public final class PaItems {

    private PaItems() {}

    public static Predicate<ItemStack> of(String word) {
        if (word.startsWith("item:")) {
            net.minecraft.world.item.Item it = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(word.substring(5)));
            return s -> s.is(it);
        }
        return switch (word) {
            case "logs" -> s -> s.is(ItemTags.LOGS);
            case "planks" -> s -> s.is(ItemTags.PLANKS);
            case "stone", "cobblestone" -> s -> s.is(Items.COBBLESTONE) || s.is(Items.STONE) || s.is(Items.COBBLED_DEEPSLATE);
            case "dirt" -> s -> s.is(Items.DIRT);
            case "sand" -> s -> s.is(ItemTags.SAND);
            case "gravel" -> s -> s.is(Items.GRAVEL) || s.is(Items.FLINT);
            case "clay" -> s -> s.is(Items.CLAY_BALL) || s.is(Items.CLAY);
            case "iron" -> s -> s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE) || s.is(Items.DEEPSLATE_IRON_ORE) || s.is(Items.IRON_INGOT);
            case "coal" -> s -> s.is(Items.COAL) || s.is(Items.COAL_ORE) || s.is(Items.DEEPSLATE_COAL_ORE);
            case "copper" -> s -> s.is(Items.RAW_COPPER) || s.is(Items.COPPER_INGOT);
            case "gold" -> s -> s.is(Items.RAW_GOLD) || s.is(Items.GOLD_INGOT);
            case "diamond" -> s -> s.is(Items.DIAMOND);
            case "redstone" -> s -> s.is(Items.REDSTONE);
            case "lapis" -> s -> s.is(Items.LAPIS_LAZULI);
            case "emerald" -> s -> s.is(Items.EMERALD);
            case "obsidian" -> s -> s.is(Items.OBSIDIAN);
            case "sugar cane" -> s -> s.is(Items.SUGAR_CANE);
            case "wheat" -> s -> s.is(Items.WHEAT);
            case "carrots" -> s -> s.is(Items.CARROT);
            case "potatoes" -> s -> s.is(Items.POTATO) || s.is(Items.BAKED_POTATO);
            case "seeds" -> s -> s.is(Items.WHEAT_SEEDS);
            case "food" -> s -> s.get(DataComponents.FOOD) != null && !s.is(Items.ROTTEN_FLESH) && !s.is(Items.SPIDER_EYE) && !s.is(Items.POISONOUS_POTATO) && !s.is(Items.PUFFERFISH);
            case "crops" -> s -> s.is(Items.WHEAT) || s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.BEETROOT);
            case "fish" -> s -> s.is(Items.COD) || s.is(Items.SALMON) || s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON);
            case "anyfish" -> s -> s.is(ItemTags.FISHES);                                   // [pa38] what the game counts as a fish caught
            case "torches" -> s -> s.is(Items.TORCH);
            case "wool" -> s -> s.is(ItemTags.WOOL);
            default -> com.jrpetty.mcassistant.entity.goal.WithdrawGoal.matcherFor(word);
        };
    }

    public static int count(AssistantEntity a, String word) {
        Predicate<ItemStack> m = of(word);
        int n = a.countMatching(m);
        for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[]{
                net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.entity.EquipmentSlot.OFFHAND}) {
            ItemStack h = a.getItemBySlot(slot);
            if (!h.isEmpty() && m.test(h)) n += h.getCount();
        }
        return n;
    }

    /** "item:minecraft:wooden_pickaxe" for a recipe target, to count what was made. */
    public static String itemKey(String target) {
        net.minecraft.world.item.Item it = "planks".equals(target) ? Items.OAK_PLANKS : "sticks".equals(target) ? Items.STICK
            : com.jrpetty.mcassistant.entity.RecipeBook.item(target);
        if (it == Items.AIR) return "";
        if ("planks".equals(target)) return "planks";
        return "item:" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it);
    }

    /** The engine's gathering kind for a word, or null when it is not something the gatherer does. */
    @Nullable
    public static GatherGoal.Kind kind(String word) {
        return switch (word) {
            case "logs" -> GatherGoal.Kind.LOGS;
            case "stone", "cobblestone" -> GatherGoal.Kind.STONE;
            case "dirt" -> GatherGoal.Kind.DIRT;
            case "iron" -> GatherGoal.Kind.IRON;
            case "coal" -> GatherGoal.Kind.COAL;
            case "sand" -> GatherGoal.Kind.SAND;
            case "gravel" -> GatherGoal.Kind.GRAVEL;
            case "sugar cane" -> GatherGoal.Kind.SUGAR_CANE;
            case "obsidian" -> GatherGoal.Kind.OBSIDIAN;
            default -> null;
        };
    }

    /** Best height to dig for an ore. */
    public static int oreY(String ore) {
        return switch (ore) {
            case "diamond", "redstone", "lapis" -> -58;
            case "gold" -> -16;
            case "copper" -> 48;
            case "coal" -> 50;
            case "emerald" -> 100;
            default -> 16;
        };
    }

    /** Does it carry a pickaxe? */
    public static boolean hasPickaxe(AssistantEntity a) {
        return a.countMatching(s -> s.getItem() instanceof net.minecraft.world.item.PickaxeItem) > 0
            || a.getMainHandItem().getItem() instanceof net.minecraft.world.item.PickaxeItem;
    }

    public static boolean hasAxe(AssistantEntity a) {
        return a.countMatching(s -> s.getItem() instanceof net.minecraft.world.item.AxeItem) > 0
            || a.getMainHandItem().getItem() instanceof net.minecraft.world.item.AxeItem;
    }
}
