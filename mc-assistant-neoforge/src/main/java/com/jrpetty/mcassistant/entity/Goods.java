package com.jrpetty.mcassistant.entity;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.block.Block;

import java.util.Locale;

/**
 * [shops-homes] What kind of thing a thing is, for the folk who buy it, the shops that sell it and the homes that
 * keep it: one answer everywhere (the shop screen's tabs, a folk's shopping list, a home's belongings, a work chest's
 * makings). Read off the item by its registry identity and its components, never its English name.
 */
public final class Goods {

    private Goods() {}

    /** The kinds of goods, in the order a shop's tabs show them. */
    public enum Category {
        FOOD("Food"), DRINK("Drink"), CLOTHES("Clothes"), TOOLS("Tools"), HOUSEHOLD("Household"),
        COMFORTS("Comforts"), MATERIALS("Materials");

        public final String label;

        Category(String label) {
            this.label = label;
        }

        /** The category called this (its name, any case), or MATERIALS. */
        public static Category of(String name) {
            for (Category c : values()) if (c.name().equalsIgnoreCase(name)) return c;
            return MATERIALS;
        }
    }

    /** What kind of thing this is. */
    public static Category of(ItemStack s) {
        if (s.isEmpty()) return Category.MATERIALS;
        if (drink(s)) return Category.DRINK;
        if (s.get(DataComponents.FOOD) != null) return Category.FOOD;
        if (clothes(s)) return Category.CLOTHES;
        if (tool(s)) return Category.TOOLS;
        if (comfort(s)) return Category.COMFORTS;
        if (household(s)) return Category.HOUSEHOLD;
        return Category.MATERIALS;
    }

    /** Something to drink: the café's drinks, potions, milk, honey. */
    public static boolean drink(ItemStack s) {
        return Cafe.isDrink(s) || s.getItem() instanceof PotionItem || s.is(Items.MILK_BUCKET) || s.is(Items.HONEY_BOTTLE)
            || s.getItem() instanceof com.jrpetty.mcassistant.item.KitchenDrinkItem;
    }

    /** Something to wear: armour of any make, the tailor's garments, a hat of leather. */
    public static boolean clothes(ItemStack s) {
        return s.getItem() instanceof ArmorItem || s.getItem() instanceof com.jrpetty.mcassistant.item.GarmentItem
            || s.is(Items.LEATHER_HORSE_ARMOR);
    }

    /** A tool of somebody's trade: picks, axes, hoes, shovels, swords, shears, rods, buckets, bows. */
    public static boolean tool(ItemStack s) {
        Item it = s.getItem();
        return it instanceof DiggerItem || it instanceof SwordItem || it instanceof TieredItem
            || s.is(Items.SHEARS) || s.is(Items.FISHING_ROD) || s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET)
            || s.is(Items.BOW) || s.is(Items.CROSSBOW) || s.is(Items.FLINT_AND_STEEL) || s.is(Items.BRUSH)
            || s.is(Items.SHIELD) || s.is(Items.COMPASS) || s.is(Items.CLOCK) || s.is(Items.SPYGLASS);
    }

    /** What makes a house a home and a day a pleasure: books, pictures, music, flowers, toys, the pets' things. */
    public static boolean comfort(ItemStack s) {
        Item it = s.getItem();
        if (s.is(ItemTags.FLOWERS) || s.is(ItemTags.WOOL_CARPETS) || s.is(ItemTags.BANNERS)) return true;
        if (s.is(Items.BOOK) || s.is(Items.WRITTEN_BOOK) || s.is(Items.WRITABLE_BOOK) || s.is(Items.ENCHANTED_BOOK)) return true;
        if (s.is(Items.PAINTING) || s.is(Items.ITEM_FRAME) || s.is(Items.GLOW_ITEM_FRAME) || s.is(Items.JUKEBOX)
            || s.is(Items.NOTE_BLOCK) || s.is(Items.BOOKSHELF) || s.is(Items.CHISELED_BOOKSHELF) || s.is(Items.DECORATED_POT)
            || s.is(Items.FLOWER_POT) || s.is(Items.GOAT_HORN) || s.is(Items.BELL)) return true;
        if (BuiltInRegistries.ITEM.getKey(it).getPath().startsWith("music_disc_")) return true;
        String path = BuiltInRegistries.ITEM.getKey(it).getPath();
        // The town's own: the quilt, the lute, the board, the kite, the football, paper lanterns, the slate, the pets'.
        return path.equals("patchwork_quilt") || path.equals("lute") || path.equals("draughts_board") || path.equals("kite")
            || path.equals("leather_football") || path.endsWith("paper_lantern") || path.equals("slate_and_chalk")
            || path.equals("pet_bowl") || path.equals("dog_bed") || path.equals("cat_bed") || path.equals("collar")
            || path.equals("pet_treat") || path.equals("gold_charm");
    }

    /** The household's things: chests and casks, beds, candles and lights, pots and pans, glass, the furniture. */
    public static boolean household(ItemStack s) {
        if (s.is(ItemTags.BEDS) || s.is(ItemTags.CANDLES) || s.is(Items.TORCH) || s.is(Items.LANTERN) || s.is(Items.SOUL_LANTERN)
            || s.is(Items.CHEST) || s.is(Items.BARREL) || s.is(Items.GLASS_PANE) || s.is(Items.GLASS_BOTTLE) || s.is(Items.BOWL)
            || s.is(Items.CAULDRON) || s.is(Items.CAMPFIRE) || s.is(Items.CRAFTING_TABLE) || s.is(Items.FURNACE)
            || s.is(Items.SMOKER) || s.is(Items.LADDER) || s.is(Items.SHULKER_BOX)) return true;
        if (s.getItem() instanceof BlockItem bi) {
            Block b = bi.getBlock();
            String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
            return path.endsWith("_door") || path.endsWith("_trapdoor") || path.endsWith("_bed") || path.endsWith("_chair")
                || path.endsWith("_table") && !path.equals("crafting_table");
        }
        return false;
    }

    /** "Bread", "Iron Pickaxe": a thing's name as the item has it (for lines a player reads). */
    public static String name(ItemStack s) {
        return s.getHoverName().getString();
    }

    /** A shop line's identity: the item, and its components when it has any (a dyed rug, a named book, a master's
     *  mark). Two stacks are the same line exactly when they stack together. */
    public static String lineId(ItemStack s) {
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        return s.getComponentsPatch().isEmpty() ? id : id + "#" + Integer.toHexString(s.getComponentsPatch().hashCode());
    }

    /** Lower-case words for a stack: "a loaf of bread", "3 candles" (Bench.words). */
    public static String words(ItemStack s, int n) {
        return Bench.words(s.getItem(), n).toLowerCase(Locale.ROOT);
    }
}
