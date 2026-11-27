package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * [pa36] The things a logistics order names, as items: "stone" (cobblestone and its like), "oak log", "cooked food",
 * "ore", "fuel", "compostables", "item:minecraft:bread" (exact, from a click in a screen), and the plain words PaItems
 * already knows; with the item that stands for each (for a stack's size and for its name in a reply), and how it is
 * got (a gatherer's kind, a recipe, a furnace).
 */
public final class PaLogiItems {

    private PaLogiItems() {}

    /** Does this stack count as the thing named? ("*" is everything but its own gear; see {@link #gear}.) */
    public static Predicate<ItemStack> of(String word) {
        String w = word == null ? "" : word.toLowerCase(Locale.ROOT).trim();
        if (w.startsWith("item:")) {
            Item it = BuiltInRegistries.ITEM.get(ResourceLocation.parse(w.substring(5)));
            return s -> s.is(it);
        }
        return switch (w) {
            case "*", "**", "everything", "" -> s -> !s.isEmpty();
            case "cooked food" -> s -> s.get(DataComponents.FOOD) != null && cooked(s);
            case "ore" -> s -> com.jrpetty.mcassistant.entity.AssistantEntity.SMELTABLE_ORE.test(s) || path(s).endsWith("_ore") || path(s).startsWith("raw_");
            case "raw iron" -> s -> s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE) || s.is(Items.DEEPSLATE_IRON_ORE);
            case "raw gold" -> s -> s.is(Items.RAW_GOLD) || s.is(Items.GOLD_ORE) || s.is(Items.DEEPSLATE_GOLD_ORE);
            case "raw copper" -> s -> s.is(Items.RAW_COPPER) || s.is(Items.COPPER_ORE) || s.is(Items.DEEPSLATE_COPPER_ORE);
            case "iron ingot" -> s -> s.is(Items.IRON_INGOT);
            case "gold ingot" -> s -> s.is(Items.GOLD_INGOT);
            case "copper ingot" -> s -> s.is(Items.COPPER_INGOT);
            case "ingots" -> s -> path(s).endsWith("_ingot");
            case "fuel" -> s -> fuel(s);
            case "seeds" -> s -> s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS) || s.is(Items.MELON_SEEDS) || s.is(Items.PUMPKIN_SEEDS);
            case "compostables" -> s -> ComposterBlock.COMPOSTABLES.containsKey(s.getItem()) && s.get(DataComponents.FOOD) == null;
            case "valuables" -> s -> s.is(Items.DIAMOND) || s.is(Items.EMERALD) || s.is(Items.GOLD_INGOT) || s.is(Items.NETHERITE_INGOT)
                || s.is(Items.NETHERITE_SCRAP) || s.is(Items.LAPIS_LAZULI) || s.is(Items.AMETHYST_SHARD);
            case "blocks" -> s -> s.getItem() instanceof net.minecraft.world.item.BlockItem && !(path(s).contains("chest") || path(s).contains("bed"));
            case "tools" -> s -> s.getItem() instanceof DiggerItem || s.getItem() instanceof SwordItem || s.getItem() instanceof ShearsItem;
            case "torch", "torches" -> s -> s.is(Items.TORCH);
            case "bread" -> s -> s.is(Items.BREAD);
            case "food" -> PaItems.of("food");
            case "cooked beef", "steak" -> s -> s.is(Items.COOKED_BEEF);
            case "cooked fish" -> s -> s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON);
            case "glass" -> s -> s.is(Items.GLASS);
            case "charcoal" -> s -> s.is(Items.CHARCOAL);
            default -> {
                if (w.endsWith(" log") || w.endsWith(" logs")) {
                    String kind = w.replaceAll(" logs?$", "").replace(' ', '_');
                    yield s -> s.is(ItemTags.LOGS) && path(s).startsWith(kind);
                }
                Item exact = exact(w);
                if (exact != null && !GROUPS.contains(w)) {
                    final Item e = exact;
                    yield s -> s.is(e);
                }
                yield PaItems.of(w);
            }
        };
    }

    /** Words PaItems reads as a group (raw and smelted, ore and all), not as the one item of that name. */
    private static final java.util.Set<String> GROUPS = java.util.Set.of("logs", "planks", "stone", "cobblestone", "dirt", "sand", "gravel", "clay",
        "iron", "coal", "copper", "gold", "food", "crops", "fish", "potatoes", "carrots", "wool", "wheat");

    /** An item whose registry name is the word ("bread", "iron_pickaxe" for "iron pickaxe", "torch" for "torches"). */
    @Nullable
    public static Item exact(String w) {
        String base = w.replace(' ', '_');
        for (String c : new String[]{base, base.replaceAll("es$", ""), base.replaceAll("s$", "")}) {
            if (c.isEmpty()) continue;
            ResourceLocation rl = ResourceLocation.tryParse(c.contains(":") ? c : "minecraft:" + c);
            if (rl == null) continue;
            Item it = BuiltInRegistries.ITEM.get(rl);
            if (it != Items.AIR) return it;
        }
        return null;
    }

    /** The item that stands for the word: its stack size and its name. */
    public static Item sample(String w) {
        String word = w == null ? "" : w.toLowerCase(Locale.ROOT);
        if (word.startsWith("item:")) return BuiltInRegistries.ITEM.get(ResourceLocation.parse(word.substring(5)));
        return switch (word) {
            case "stone", "cobblestone" -> Items.COBBLESTONE;
            case "logs" -> Items.OAK_LOG;
            case "planks" -> Items.OAK_PLANKS;
            case "iron", "raw iron" -> Items.RAW_IRON;
            case "gold", "raw gold" -> Items.RAW_GOLD;
            case "copper", "raw copper" -> Items.RAW_COPPER;
            case "coal" -> Items.COAL;
            case "torches", "torch" -> Items.TORCH;
            case "food", "cooked food" -> Items.BREAD;
            case "fuel" -> Items.COAL;
            case "seeds", "compostables" -> Items.WHEAT_SEEDS;
            case "ore" -> Items.RAW_IRON;
            case "ingots" -> Items.IRON_INGOT;
            case "sugar cane" -> Items.SUGAR_CANE;
            default -> {
                if (word.endsWith(" log")) {
                    Item it = exact(word);
                    yield it != null ? it : Items.OAK_LOG;
                }
                Item it = exact(word);
                yield it != null ? it : Items.COBBLESTONE;
            }
        };
    }

    /** "64 cobblestone" style name for a word: the plain word, or the item's own name. */
    public static String name(String word, int n) {
        if (word == null || word.isEmpty() || word.startsWith("*")) return n == 1 ? "item" : "items";
        if (word.startsWith("item:")) {
            String p = word.substring(word.indexOf(':', 5) + 1).replace('_', ' ');
            return n == 1 || p.endsWith("s") ? p : plural(p);
        }
        return word;
    }

    static String plural(String p) {
        if (p.endsWith("ch") || p.endsWith("sh") || p.endsWith("x")) return p + "es";
        if (p.matches(".*(?:stone|dirt|sand|gravel|wool|glass|bread|iron|gold|copper|coal|redstone|lapis|cobblestone|deepslate|clay|ice|snow|netherrack|wheat|food|fuel|charcoal)$")) return p;
        return p + "s";
    }

    /** The gatherer's kind for the word (logs, stone, dirt, sand, gravel, coal, iron, sugar cane, obsidian), or null. */
    @Nullable
    public static GatherGoal.Kind kind(String word) {
        String g = PaLogiParse.gatherWord(word);
        return g == null ? null : PaItems.kind(g);
    }

    /** A block that stands for a gatherer's kind, to ask the mining ladder about. */
    @Nullable
    public static BlockState block(GatherGoal.Kind k) {
        return switch (k) {
            case IRON -> Blocks.IRON_ORE.defaultBlockState();
            case COAL -> Blocks.COAL_ORE.defaultBlockState();
            case OBSIDIAN -> Blocks.OBSIDIAN.defaultBlockState();
            case STONE -> Blocks.STONE.defaultBlockState();
            default -> null;
        };
    }

    /** Its own gear, which "everything" never includes: tools, weapons, armour, and anything its player told it to keep. */
    public static boolean gear(PlayerAssistantEntity a, ItemStack s) {
        if (s.isEmpty()) return false;
        Item it = s.getItem();
        if (it instanceof DiggerItem || it instanceof SwordItem || it instanceof BowItem || it instanceof CrossbowItem || it instanceof ShieldItem
            || it instanceof ArmorItem || it instanceof ShearsItem || it instanceof FishingRodItem || it instanceof net.minecraft.world.item.TridentItem
            || s.is(Items.ARROW) || s.is(Items.ELYTRA) || s.is(Items.FIREWORK_ROCKET) || s.is(Items.WATER_BUCKET) || s.is(Items.ENDER_PEARL)) return true;
        PaData.Rec r = a.rec();
        return r != null && PaPack.kept(r.keep, s);
    }

    public static boolean cooked(ItemStack s) {
        String p = path(s);
        return p.startsWith("cooked_") || p.equals("bread") || p.equals("baked_potato") || p.equals("pumpkin_pie") || p.equals("cake")
            || p.equals("golden_carrot") || p.equals("mushroom_stew") || p.equals("rabbit_stew") || p.equals("beetroot_soup") || p.equals("cookie");
    }

    public static boolean fuel(ItemStack s) {
        if (s.isEmpty()) return false;
        if (s.is(Items.COAL) || s.is(Items.CHARCOAL) || s.is(Items.COAL_BLOCK) || s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS)) return true;
        try {
            return s.getBurnTime(RecipeType.SMELTING) > 0 && !s.is(Items.LAVA_BUCKET) && !(s.getItem() instanceof DiggerItem);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** What smelting this stack makes, or empty (the server's own recipes). */
    public static ItemStack smelts(net.minecraft.world.level.Level level, ItemStack s, RecipeType<?> type) {
        try {
            @SuppressWarnings("unchecked")
            RecipeType<net.minecraft.world.item.crafting.AbstractCookingRecipe> t = (RecipeType<net.minecraft.world.item.crafting.AbstractCookingRecipe>) type;
            var r = level.getRecipeManager().getRecipeFor(t, new net.minecraft.world.item.crafting.SingleRecipeInput(s), level);
            return r.map(h -> h.value().getResultItem(level.registryAccess()).copy()).orElse(ItemStack.EMPTY);
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    static String path(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    public static String id(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    /** "cobblestone" for minecraft:cobblestone, plural for a count. */
    public static String nameOf(Item it, int n) {
        String p = BuiltInRegistries.ITEM.getKey(it).getPath().replace('_', ' ');
        return n == 1 ? p : plural(p);
    }
}
