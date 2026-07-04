package com.jrpetty.mcassistant.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The window onto the ACTUAL Minecraft recipe database. Instead of a hand-typed
 * recipe list, we read the game's own {@link RecipeManager} — so anything the
 * game can craft, the assistant can craft: every tool, block, redstone part,
 * decoration, food, and gadget, with the game's real ingredients, yields, and
 * grid sizes. That's the "million craftable things".
 *
 * Two jobs:
 *   1. resolvePath(clause) — turn a spoken phrase ("make me a piston") into an
 *      item id ("piston"), purely from the static item registry (no world).
 *   2. craftFor(level, item) — look up how the game crafts that item, grouped
 *      into (ingredient, count) parts, with a flag for whether it needs a table.
 */
public final class RecipeBook {

    private RecipeBook() {}

    /** One grouped ingredient of a recipe: this stack-matcher, N times. */
    public record Part(Ingredient ingredient, int count, String label) {}

    /** How the game crafts an item: result, yield per craft, parts, table? */
    public record Craft(Item result, int yield, List<Part> parts, boolean needsTable) {}

    // ---- recipe lookup (needs a world; cached per recipe-manager) ----

    @Nullable private static RecipeManager cachedManager;
    private static Map<Item, Craft> craftCache = Map.of();

    /** How the game crafts this item (first recipe wins), or null if it can't. */
    @Nullable
    public static synchronized Craft craftFor(Level level, Item item) {
        RecipeManager mgr = level.getRecipeManager();
        if (mgr != cachedManager) buildCraftCache(level, mgr);
        return craftCache.get(item);
    }

    private static void buildCraftCache(Level level, RecipeManager mgr) {
        Map<Item, Craft> map = new HashMap<>();
        var registries = level.registryAccess();
        for (RecipeHolder<CraftingRecipe> holder : mgr.getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            if (recipe.isSpecial()) continue; // dyeing/fireworks/etc. — no fixed inputs
            ItemStack result = recipe.getResultItem(registries);
            if (result.isEmpty()) continue;
            Item out = result.getItem();
            if (map.containsKey(out)) continue; // keep the first (usually the canonical) recipe

            // Group the grid's non-empty slots into (ingredient -> how many slots).
            LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
            LinkedHashMap<String, Ingredient> reps = new LinkedHashMap<>();
            for (Ingredient ing : recipe.getIngredients()) {
                ItemStack[] items = ing.getItems();
                if (items.length == 0) continue; // empty slot
                String sig = signature(items);
                counts.merge(sig, 1, Integer::sum);
                reps.putIfAbsent(sig, ing);
            }
            if (counts.isEmpty()) continue;

            List<Part> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                Ingredient ing = reps.get(e.getKey());
                parts.add(new Part(ing, e.getValue(), labelFor(ing)));
            }
            boolean needsTable = !recipe.canCraftInDimensions(2, 2);
            map.put(out, new Craft(out, Math.max(1, result.getCount()), parts, needsTable));
        }
        craftCache = map;
        cachedManager = mgr;
    }

    /** Stable key for "which items does this ingredient accept". */
    private static String signature(ItemStack[] items) {
        String[] ids = new String[items.length];
        for (int i = 0; i < items.length; i++) {
            ids[i] = BuiltInRegistries.ITEM.getKey(items[i].getItem()).toString();
        }
        Arrays.sort(ids);
        return String.join(",", ids);
    }

    private static String labelFor(Ingredient ing) {
        ItemStack[] items = ing.getItems();
        if (items.length == 0) return "?";
        return BuiltInRegistries.ITEM.getKey(items[0].getItem()).getPath().replace('_', ' ');
    }

    // ---- phrase -> item id (static; item registry only, no world needed) ----

    private record Named(String[] words, String path) {}
    @Nullable private static volatile List<Named> nameIndex;

    /**
     * Longest item name (by word count, then length) whose words appear as a
     * contiguous run in the clause. "make me an iron sword" -> "iron_sword".
     * Returns the registry path, the abstract base "planks"/"sticks", or null.
     */
    @Nullable
    public static String resolvePath(String clause) {
        String[] toks = clause.toLowerCase().replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+");
        for (Named n : index()) {
            if (containsSeq(toks, n.words)) return n.path;
        }
        return null;
    }

    private static List<Named> index() {
        List<Named> idx = nameIndex;
        if (idx != null) return idx;
        synchronized (RecipeBook.class) {
            if (nameIndex != null) return nameIndex;
            List<Named> list = new ArrayList<>();
            for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
                if (!id.getNamespace().equals("minecraft")) continue;
                String path = id.getPath();
                if (path.equals("air")) continue;
                list.add(new Named(path.replace('_', ' ').split(" "), path));
            }
            // Abstract wood-chain targets: "make planks/sticks" is handled
            // generically (any wood) rather than by a specific plank id.
            list.add(new Named(new String[] {"planks"}, "planks"));
            list.add(new Named(new String[] {"sticks"}, "sticks"));
            // Prefer more-specific names: more words first, then longer.
            list.sort((a, b) -> {
                if (a.words.length != b.words.length) return b.words.length - a.words.length;
                return joinLen(b.words) - joinLen(a.words);
            });
            nameIndex = list;
            return list;
        }
    }

    private static int joinLen(String[] w) {
        int n = 0;
        for (String s : w) n += s.length();
        return n;
    }

    private static boolean containsSeq(String[] hay, String[] needle) {
        if (needle.length == 0 || needle.length > hay.length) return false;
        for (int i = 0; i + needle.length <= hay.length; i++) {
            boolean ok = true;
            for (int j = 0; j < needle.length; j++) {
                if (!wordEq(hay[i + j], needle[j])) { ok = false; break; }
            }
            if (ok) return true;
        }
        return false;
    }

    /** Word match with light plural slop ("swords"~"sword", "planks"~"plank"). */
    private static boolean wordEq(String a, String b) {
        if (a.equals(b)) return true;
        String as = a.endsWith("s") && a.length() > 2 ? a.substring(0, a.length() - 1) : a;
        String bs = b.endsWith("s") && b.length() > 2 ? b.substring(0, b.length() - 1) : b;
        return as.equals(bs) || as.equals(b) || a.equals(bs);
    }

    /** The item for a registry path ("iron_sword"), or AIR if unknown. */
    public static Item item(String path) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(path));
    }

    /** Spoken form of an item id, e.g. "iron_sword" -> "iron sword". */
    public static String words(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ');
    }
}
