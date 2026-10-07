package com.jrpetty.mcassistant.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.neoforge.common.Tags;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The ages of things: which of the village's ages a thing belongs to, so that what its makers make goes
 * with how far the village has come (the shop's workshop, Workshop; the storekeeper's made-to-order,
 * Stockroom). One rule for everything, read off the game's own recipes, so it holds for every item there
 * is and a modpack's too:
 * <ul>
 * <li><b>The materials.</b> Each age opens its materials. The Wood Age has wood, the fields' and the
 *     pens' plain goods (wool, string, feathers, bone, wheat, cane), sand, clay and gravel. The Stone Age
 *     adds stone, flint, coal, copper, leather and honeycomb, and the furnace: anything fired. The Iron
 *     Age adds iron and gold, redstone, lapis and emerald, gunpowder, slime, pearls, the sea's prismarine,
 *     and anything carried in a bucket. The Diamond Age adds diamonds and obsidian. The Nether Age adds
 *     netherite and whatever comes out of the Nether or the End.</li>
 * <li><b>A thing</b> belongs to the latest age of what goes into it, the whole way down (a stone sword:
 *     cobblestone and a stick, the Stone Age; a shield: planks and an iron ingot, the Iron Age; a
 *     netherite sword: a diamond sword and netherite at the smithing table, the Nether Age), by the
 *     cheapest of its ways of making (sticks of planks are the Wood Age's however they could be made);
 *     anything fired in a furnace is the Stone Age's at the earliest, and the smithing table's work the
 *     Iron Age's. Whatever nobody makes and the rule does not name is gathered, and the Wood Age's.</li>
 * </ul>
 * Worked out once for every item the game's recipes make, and kept till the recipes change.
 */
public final class Tiers {

    private Tiers() {}

    // ------------------------------------------------------------------ the materials

    /** The Nether's and the End's, by a word in the name: the last age. */
    private static final Set<String> NETHER_WORDS = Set.of("netherite", "nether", "netherrack", "blaze", "ghast", "magma", "quartz",
        "glowstone", "soul", "crimson", "warped", "blackstone", "basalt", "shroomlight", "wither", "piglin", "chorus", "purpur",
        "shulker", "dragon", "elytra", "end", "endstone");
    /** Deep and hard: the Diamond Age. */
    private static final Set<String> DIAMOND_WORDS = Set.of("diamond", "obsidian", "echo");
    /** Metal and what is dug or caught with it: the Iron Age. */
    private static final Set<String> IRON_WORDS = Set.of("iron", "gold", "golden", "chainmail", "redstone", "emerald", "lapis",
        "prismarine", "phantom", "nautilus", "scute", "slime", "gunpowder", "tnt", "pearl");
    /** Stone, fire and the tanner: the Stone Age. */
    private static final Set<String> STONE_WORDS = Set.of("copper", "flint", "coal", "charcoal", "leather", "hide", "honeycomb",
        "amethyst", "deepslate", "andesite", "diorite", "granite", "tuff", "calcite", "dripstone");

    /**
     * The age a material belongs to by itself, or null if it is no material the rule names (its age is
     * then what it is made of, or the Wood Age's for a thing gathered). The latest age first: a blackstone
     * is the Nether's though it is stone.
     */
    @Nullable
    static Villages.Age material(Item it) {
        if (it == Items.AIR) return Villages.Age.WOOD;
        Villages.Age set = WorkTools.ageOf(it);                 // [workitems] the rope coil the Wood Age's; the saw and the crate the Stone Age's
        if (set != null) return set;
        ItemStack s = new ItemStack(it);
        String path = BuiltInRegistries.ITEM.getKey(it).getPath().toLowerCase(Locale.ROOT);
        List<String> words = List.of(path.split("_"));
        if (s.is(Tags.Items.INGOTS_NETHERITE) || s.is(Tags.Items.RODS_BLAZE) || s.is(Tags.Items.NETHERRACKS) || s.is(Tags.Items.END_STONES)
                || s.is(Tags.Items.GEMS_QUARTZ) || s.is(Tags.Items.DUSTS_GLOWSTONE) || s.is(Tags.Items.BRICKS_NETHER)
                || it == Items.ANCIENT_DEBRIS || it == Items.NETHER_STAR || it == Items.ENDER_EYE || any(words, NETHER_WORDS)) {
            return Villages.Age.NETHER;
        }
        if (s.is(Tags.Items.GEMS_DIAMOND) || s.is(Tags.Items.OBSIDIANS) || any(words, DIAMOND_WORDS)) return Villages.Age.DIAMOND;
        if (s.is(Tags.Items.INGOTS_IRON) || s.is(Tags.Items.NUGGETS_IRON) || s.is(Tags.Items.RAW_MATERIALS_IRON)
                || s.is(Tags.Items.INGOTS_GOLD) || s.is(Tags.Items.NUGGETS_GOLD) || s.is(Tags.Items.RAW_MATERIALS_GOLD)
                || s.is(Tags.Items.DUSTS_REDSTONE) || s.is(Tags.Items.GEMS_EMERALD) || s.is(Tags.Items.GEMS_LAPIS)
                || s.is(Tags.Items.ENDER_PEARLS) || s.is(Tags.Items.SLIME_BALLS) || s.is(Tags.Items.GUNPOWDERS)
                || s.is(Tags.Items.GEMS_PRISMARINE) || path.endsWith("_bucket") || path.contains("heart_of_the_sea")
                || any(words, IRON_WORDS)) {
            return Villages.Age.IRON;
        }
        if (s.is(Tags.Items.COBBLESTONES) || s.is(Tags.Items.STONES) || s.is(ItemTags.STONE_TOOL_MATERIALS)
                || s.is(ItemTags.STONE_CRAFTING_MATERIALS) || s.is(ItemTags.COALS) || s.is(Tags.Items.LEATHERS)
                || s.is(Tags.Items.INGOTS_COPPER) || s.is(Tags.Items.RAW_MATERIALS_COPPER) || s.is(Tags.Items.GEMS_AMETHYST)
                || it == Items.COBBLESTONE || it == Items.STONE || it == Items.FLINT || any(words, STONE_WORDS)) {
            return Villages.Age.STONE;
        }
        // A modpack's metals and gems, whatever they are called: dug, and worked with iron.
        if (s.is(Tags.Items.INGOTS) || s.is(Tags.Items.NUGGETS) || s.is(Tags.Items.RAW_MATERIALS) || s.is(Tags.Items.ORES)
                || s.is(Tags.Items.GEMS)) {
            return Villages.Age.IRON;
        }
        return null;
    }

    private static boolean any(List<String> words, Set<String> of) {
        for (String w : words) if (of.contains(w)) return true;
        return false;
    }

    /** The age at which a way of working first comes to hand: the bench at once, a fire with the furnace's
     *  stone, the smithing table with iron. */
    static Villages.Age way(RecipeBook.Fire f) {
        return switch (f) {
            case NONE -> Villages.Age.WOOD;
            case FURNACE, SMOKER, CAMPFIRE -> Villages.Age.STONE;
            case SMITHING -> Villages.Age.IRON;
        };
    }

    // ------------------------------------------------------------------ a thing's age

    @Nullable private static RecipeManager builtFor;
    private static final Map<Item, Villages.Age> AGES = new HashMap<>();
    /** Every thing the game's recipes make, and its age (the blueprints). */
    private static Map<Item, Villages.Age> catalogue = Map.of();

    /** The age this thing belongs to (the rule in the class comment). */
    public static synchronized Villages.Age of(ServerLevel level, Item item) {
        ensure(level);
        Villages.Age a = AGES.get(item);
        if (a != null) return a;
        a = work(level, item, new HashSet<>(), 0);
        if (a == null) a = Villages.Age.WOOD;
        AGES.put(item, a);
        return a;
    }

    /** May a village of this age make this thing? */
    public static boolean allows(ServerLevel level, Villages.Age age, Item item) {
        return of(level, item).ordinal() <= age.ordinal();
    }

    /** The latest age of what goes into it, by its cheapest way; null if every way loops back on itself. */
    @Nullable
    private static Villages.Age work(ServerLevel level, Item item, Set<Item> path, int depth) {
        Villages.Age named = material(item);
        if (named != null) return named;
        Villages.Age kitchen = Kitchen.age(item);                         // [kitchen] the lunch and the cake the Wood Age's, the cheese and mead the Stone Age's
        if (kitchen != null) return kitchen;
        Villages.Age known = AGES.get(item);
        if (known != null) return known;
        List<RecipeBook.Way> ways = RecipeBook.waysFor(level, item);
        if (ways.isEmpty()) return Villages.Age.WOOD;                     // gathered, and nothing the rule names
        if (depth > 12 || !path.add(item)) return null;                   // round in a circle: no way through here
        try {
            Villages.Age best = null;
            for (RecipeBook.Way w : ways) {
                Villages.Age at = way(w.fire());
                boolean through = true;
                for (RecipeBook.Part p : w.parts()) {
                    Villages.Age part = null;
                    for (ItemStack k : p.ingredient().getItems()) {
                        Villages.Age ka = work(level, k.getItem(), path, depth + 1);
                        if (ka != null && (part == null || ka.ordinal() < part.ordinal())) part = ka;
                        if (part == Villages.Age.WOOD) break;                     // none earlier
                    }
                    if (part == null) { through = false; break; }
                    if (part.ordinal() > at.ordinal()) at = part;
                }
                if (through && (best == null || at.ordinal() < best.ordinal())) best = at;
                if (best == Villages.Age.WOOD) break;
            }
            // Kept for next time (a way shut off only by the loop it was asked from may make it read a little late).
            if (best != null) AGES.put(item, best);
            return best;
        } finally {
            path.remove(item);
        }
    }

    private static void ensure(ServerLevel level) {
        RecipeManager mgr = level.getRecipeManager();
        if (mgr == builtFor) return;
        AGES.clear();
        catalogue = Map.of();
        builtFor = mgr;
    }

    // ------------------------------------------------------------------ the blueprints

    /**
     * Every thing the game's recipes make — crafted at the bench, fired, or worked at the smithing table —
     * with the age it belongs to: the blueprints the village's makers know. A modpack's are among them.
     */
    public static synchronized Map<Item, Villages.Age> blueprints(ServerLevel level) {
        ensure(level);
        if (!catalogue.isEmpty()) return catalogue;
        Map<Item, Villages.Age> out = new LinkedHashMap<>();
        for (Item it : BuiltInRegistries.ITEM) {
            if (it == Items.AIR || RecipeBook.waysFor(level, it).isEmpty()) continue;
            out.put(it, of(level, it));
        }
        catalogue = Collections.unmodifiableMap(out);
        return catalogue;
    }

    /** How many blueprints there are, and how many of them a village of this age may work. */
    public static int[] counts(ServerLevel level, Villages.Age age) {
        int all = 0, open = 0;
        for (Villages.Age a : blueprints(level).values()) {
            all++;
            if (a.ordinal() <= age.ordinal()) open++;
        }
        return new int[]{ all, open };
    }

    /**
     * What the next age will let the makers make that this one does not, the most wanted first: tools,
     * arms and armour, then the dearest. In the plural, ready for a sentence ("iron swords", "shields").
     */
    public static List<String> nextAge(ServerLevel level, Villages.Age age, int most) {
        if (age == Villages.Age.NETHER) return List.of();
        Villages.Age next = Villages.Age.values()[age.ordinal() + 1];
        List<Item> opens = new ArrayList<>();
        for (Map.Entry<Item, Villages.Age> e : blueprints(level).entrySet()) if (e.getValue() == next) opens.add(e.getKey());
        opens.sort((a, b) -> {
            int ka = Budget.kitOf(new ItemStack(a)) != null ? 0 : 1, kb = Budget.kitOf(new ItemStack(b)) != null ? 0 : 1;
            if (ka != kb) return Integer.compare(ka, kb);
            boolean va = BuiltInRegistries.ITEM.getKey(a).getNamespace().equals("minecraft"),
                vb = BuiltInRegistries.ITEM.getKey(b).getNamespace().equals("minecraft");
            if (va != vb) return va ? -1 : 1;
            return Double.compare(Prices.each(b), Prices.each(a));
        });
        List<String> out = new ArrayList<>();
        for (Item it : opens) {
            if (out.size() >= most) break;
            out.add(Bench.plural(new ItemStack(it).getHoverName().getString().toLowerCase(Locale.ROOT)));
        }
        return out;
    }

    /** How many things the next age opens. */
    public static int nextAgeCount(ServerLevel level, Villages.Age age) {
        if (age == Villages.Age.NETHER) return 0;
        Villages.Age next = Villages.Age.values()[age.ordinal() + 1];
        int n = 0;
        for (Villages.Age a : blueprints(level).values()) if (a == next) n++;
        return n;
    }

    /** "the Iron Age" for the age a thing belongs to: what a maker says when it may not make it yet. */
    public static String refusal(ServerLevel level, Villages.Age age, Item item) {
        Villages.Age a = of(level, item);
        if (a.ordinal() <= age.ordinal()) return "";
        String name = Bench.plural(new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT));
        return name + " are " + a.label + "'s work, and we're in " + age.label;
    }

    /** The rule, a line an age, for the books and /village workshop. */
    public static List<String> rule() {
        return List.of(
            "The Wood Age: wood, wool and string, feathers and bone, the fields' goods, sand, clay and gravel — wooden tools, crafting tables, chests and barrels, fences, doors, beds, ladders, boats",
            "The Stone Age: and stone, flint, coal, copper, leather, honeycomb, and the furnace — stone tools and swords, furnaces, torches, leather armour, glass, bricks, candles",
            "The Iron Age: and iron, gold, redstone, lapis, emerald, gunpowder, slime, pearls, buckets — iron tools, swords and armour, shields, buckets, rails, anvils, lanterns, chains, the smithing table",
            "The Diamond Age: and diamonds and obsidian — diamond tools, swords and armour, enchanting tables",
            "The Nether Age: and netherite and the Nether's and the End's — netherite gear at the smithing table, brewing stands");
    }
}
