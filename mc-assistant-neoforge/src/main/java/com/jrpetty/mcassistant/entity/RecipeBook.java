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

    // ---- every way the game makes a thing (the village's benches: Bench) ----

    /** Where a way is worked: at the bench (or in the hand), or over a fire of which kind. */
    public enum Fire { NONE, FURNACE, SMOKER, CAMPFIRE }

    /**
     * One way the game makes a thing: a crafting recipe, or a firing in a furnace, a smoker or over
     * a campfire (one in, one out, a firing at a time). What goes in, grouped as for craftFor, and
     * how many it makes.
     */
    public record Way(ResourceLocation id, Item result, int yield, List<Part> parts, Fire fire, boolean needsTable) {
        /** The game's own name for the thing's recipe ("minecraft:bread" for bread): the usual way. */
        public boolean canonical() {
            return id.getPath().equals(BuiltInRegistries.ITEM.getKey(result).getPath());
        }
    }

    @Nullable private static RecipeManager waysManager;
    private static Map<Item, List<Way>> waysCache = Map.of();

    /**
     * Every way the game makes this item: its crafting recipes (all of them, not only the first:
     * sticks of planks or of bamboo, sugar of cane or of honey) and its firings. Empty if nothing
     * makes it (a log, an ore, an egg: those are gathered).
     */
    public static synchronized List<Way> waysFor(Level level, Item item) {
        RecipeManager mgr = level.getRecipeManager();
        if (mgr != waysManager) buildWays(level, mgr);
        return waysCache.getOrDefault(item, List.of());
    }

    private static void buildWays(Level level, RecipeManager mgr) {
        Map<Item, List<Way>> map = new HashMap<>();
        var registries = level.registryAccess();
        for (RecipeHolder<CraftingRecipe> holder : mgr.getAllRecipesFor(RecipeType.CRAFTING)) {
            try {
                CraftingRecipe recipe = holder.value();
                if (recipe.isSpecial()) continue;
                ItemStack result = recipe.getResultItem(registries);
                // Only plain things: a recipe whose result carries more than the item (a firework's
                // charge, a dyed piece) is no village bench's.
                if (result.isEmpty() || !result.getComponentsPatch().isEmpty()) continue;
                List<Part> parts = parts(recipe.getIngredients());
                if (parts.isEmpty()) continue;
                map.computeIfAbsent(result.getItem(), k -> new ArrayList<>()).add(new Way(holder.id(), result.getItem(),
                    Math.max(1, result.getCount()), parts, Fire.NONE, !recipe.canCraftInDimensions(2, 2)));
            } catch (RuntimeException e) {
                // A modpack's recipe that will not say what it takes or makes: not one for the village's benches.
            }
        }
        fires(map, registries, mgr.getAllRecipesFor(RecipeType.SMELTING), Fire.FURNACE);
        fires(map, registries, mgr.getAllRecipesFor(RecipeType.SMOKING), Fire.SMOKER);
        fires(map, registries, mgr.getAllRecipesFor(RecipeType.CAMPFIRE_COOKING), Fire.CAMPFIRE);
        // The usual way first, then the bench before the fire, then the fewest different parts.
        for (List<Way> ways : map.values()) {
            ways.sort(java.util.Comparator.<Way>comparingInt(w -> w.canonical() ? 0 : 1)
                .thenComparingInt(w -> w.fire().ordinal())
                .thenComparingInt(w -> w.parts().size())
                .thenComparing(w -> w.id().toString()));
        }
        waysCache = map;
        waysManager = mgr;
    }

    private static <T extends net.minecraft.world.item.crafting.AbstractCookingRecipe> void fires(Map<Item, List<Way>> map,
            net.minecraft.core.HolderLookup.Provider registries, List<RecipeHolder<T>> recipes, Fire fire) {
        for (RecipeHolder<T> holder : recipes) {
            try {
                T recipe = holder.value();
                ItemStack result = recipe.getResultItem(registries);
                if (result.isEmpty() || !result.getComponentsPatch().isEmpty()) continue;
                List<Part> parts = parts(recipe.getIngredients());
                if (parts.size() != 1) continue;
                map.computeIfAbsent(result.getItem(), k -> new ArrayList<>()).add(new Way(holder.id(), result.getItem(),
                    Math.max(1, result.getCount()), parts, fire, false));
            } catch (RuntimeException e) {
                // As above: a firing the village cannot read is left out.
            }
        }
    }

    /** A grid's (or a fire's) ingredients grouped into (ingredient, how many). */
    private static List<Part> parts(List<Ingredient> ingredients) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        LinkedHashMap<String, Ingredient> reps = new LinkedHashMap<>();
        for (Ingredient ing : ingredients) {
            ItemStack[] items = ing.getItems();
            if (items.length == 0) continue;
            String sig = signature(items);
            counts.merge(sig, 1, Integer::sum);
            reps.putIfAbsent(sig, ing);
        }
        List<Part> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            Ingredient ing = reps.get(e.getKey());
            parts.add(new Part(ing, e.getValue(), labelFor(ing)));
        }
        return parts;
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

    /** Word match with light plural slop — matches if any singular/plural forms
     *  agree, so "swords"~"sword", "axes"~"axe", and "torches"~"torch" all hit. */
    private static boolean wordEq(String a, String b) {
        if (a.equals(b)) return true;
        for (String x : forms(a)) {
            for (String y : forms(b)) {
                if (x.equals(y)) return true;
            }
        }
        return false;
    }

    /** The word plus its plausible de-pluralised forms ("axes" -> axe, ax). */
    private static List<String> forms(String w) {
        List<String> out = new ArrayList<>(3);
        out.add(w);
        if (w.endsWith("s") && w.length() > 2) out.add(w.substring(0, w.length() - 1));   // swords->sword, axes->axe
        if (w.endsWith("es") && w.length() > 3) out.add(w.substring(0, w.length() - 2));  // torches->torch
        return out;
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
