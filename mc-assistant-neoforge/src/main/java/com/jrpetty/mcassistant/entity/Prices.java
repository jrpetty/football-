package com.jrpetty.mcassistant.entity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import com.jrpetty.mcassistant.village.BaseWorth;
import com.jrpetty.mcassistant.village.Worth;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What everything is worth, in village coin: the price list the village pays its folk by.
 *
 * <p>Three layers, the first that knows wins:
 * <ol>
 *   <li>The market's own goods (Market.GOODS) — what the price board says, so the board, the
 *       shop and the wages never disagree.</li>
 *   <li>The base list below: everything that is <em>gathered</em> rather than made — what is mined
 *       (ores, stone of every kind, the Nether's and the End's), grown and foraged (crops, seeds,
 *       saplings, flowers, mushrooms, coral), taken from animals and monsters (hides, wool, bones,
 *       pearls, heads), fished, and the rare finds (the heart of the sea, a nether star).</li>
 *   <li>Everything <em>made</em> — worked out from the game's own recipes when the world starts
 *       (crafting, smelting, the stonecutter, the smithing table): what goes in, at the cheapest
 *       way of making it, plus the work. A pickaxe is worth its iron and its sticks and the
 *       smith's time; a block of iron nine ingots; a cake its milk, sugar, egg and wheat. A
 *       modpack's items are priced the same way, from their own recipes.</li>
 * </ol>
 * Anything left (a curiosity nobody makes) goes by how rare the game calls it.
 */
public final class Prices {

    private Prices() {}

    private static final Logger LOG = LogUtils.getLogger();

    private static final Map<Item, Double> VALUE = new HashMap<>();
    private static final Map<Item, Economy.Kind> KIND = new HashMap<>();
    /** [market] The board's goods at their worth (Market.Good.value), by the good itself. */
    private static final Map<Market.Good, Double> BOARD = new java.util.IdentityHashMap<>();
    /** [market] Every recipe the engine read, by what it makes (the town's replacement cost: replacement). */
    private static final Map<String, List<Worth.Recipe>> RECIPES = new HashMap<>();
    /** [market] The board's goods whose named price was under their makings, raised to them. */
    private static final Set<String> RAISED = new java.util.TreeSet<>();
    @Nullable private static MinecraftServer builtFor;
    private static int fromBase, fromMarket, fromRecipes, fromRarity;

    /** Forget the list (a new world, new recipes): it is worked out again when next asked. */
    public static synchronized void reset() {
        VALUE.clear();
        KIND.clear();
        BOARD.clear();
        RECIPES.clear();
        builtFor = null;
    }

    /** What one of this item is worth, in coin. */
    public static double each(Item item) {
        ensure();
        Double v = VALUE.get(item);
        return v != null ? v : fallback(item);
    }

    /**
     * What this stack is worth: each one's price, more for its enchantments, less for wear. A
     * village pays for what its folk make by this.
     */
    public static double of(ItemStack s) {
        if (s.isEmpty()) return 0;
        double each = each(s.getItem());
        var ench = s.getOrDefault(net.minecraft.core.component.DataComponents.ENCHANTMENTS,
            net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        var stored = s.getOrDefault(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS,
            net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        int levels = 0;
        for (var e : ench.entrySet()) levels += e.getIntValue();
        for (var e : stored.entrySet()) levels += e.getIntValue();
        each += levels * 2.0;
        // A master's piece is worth more than a beginner's (Craftsmanship: its maker's mark).
        each *= Craftsmanship.worth(s);
        if (s.isDamageableItem() && s.getMaxDamage() > 0) {
            each *= Math.max(0.1, 1.0 - s.getDamageValue() / (double) s.getMaxDamage());
        }
        return each * s.getCount();
    }

    /** What sort of goods this is by the list (for a thing Economy.kindOf does not name itself), or null. */
    @Nullable
    public static Economy.Kind kindOf(Item item) {
        ensure();
        return KIND.get(item);
    }

    /** How many items the list prices, and from where (the log and the tests). */
    public static String summary() {
        ensure();
        return VALUE.size() + " items priced: " + fromMarket + " from the market's board, " + fromBase + " from the base list, "
            + fromRecipes + " from their recipes" + (fromRarity > 0 ? ", " + fromRarity + " by rarity" : "");
    }

    public static int priced() {
        ensure();
        return VALUE.size();
    }

    /**
     * [cartographer] What a map to a place costs a player at the map room (Cartographers): its makings (an empty locator
     * map's: eight paper and a compass, at their worth) and the cartographer's work, a coin for every hundred blocks to
     * where it points (the walk the town made to find it), all of it by how rare the place is (a monument or a mansion
     * four or five times a mineshaft). Never less than the makings and a coin, never more than eighty.
     */
    public static int mapOf(int blocksAway, double rarity) {
        double makings = each(Items.PAPER) * 8 + each(Items.COMPASS);
        double v = (makings + 2.0 + Math.max(0, blocksAway) / 100.0) * Math.max(1.0, rarity);
        return (int) Math.max(Math.ceil(makings) + 1, Math.min(80, Math.round(v)));
    }

    /** [market] What one of a board good is worth: the engine's number for the thing it deals in (its first match), or
     *  its own named price for a good with no plain thing to price (a café drink, a potion). */
    public static double ofGood(Market.Good g) {
        ensure();
        Double v = BOARD.get(g);
        return v != null ? v : g.anchor();
    }

    /** [market] One of this thing as a board good prices it: its own worth on the list when the good deals in the plain
     *  thing, else the good's (a drink is a potion with a name). */
    public static double eachAsGood(Market.Good g, ItemStack one) {
        ensure();
        Double v = VALUE.get(one.getItem());
        return v != null && g.what().test(new ItemStack(one.getItem())) ? v : g.value();
    }

    /** [market] The board's goods whose named price was under their makings and the work, raised to them (the log, the tests). */
    public static Set<String> raised() {
        ensure();
        return java.util.Collections.unmodifiableSet(RAISED);
    }

    /** [market] The recipes that make this thing, as the engine read them. */
    public static List<Worth.Recipe> recipesFor(Item item) {
        ensure();
        return RECIPES.getOrDefault(BuiltInRegistries.ITEM.getKey(item).toString(), List.of());
    }

    /** [market] Every recipe the engine read, by what it makes. */
    public static Map<String, List<Worth.Recipe>> recipes() {
        ensure();
        return java.util.Collections.unmodifiableMap(RECIPES);
    }

    /**
     * [market] The least this thing can be had for from this town, a piece: its price there, or its makings bought there
     * (and theirs), whichever is less (Worth.replacement). The town never pays more than this, so making a thing out of
     * what it sells, or breaking a thing it sells into parts, and selling the result back never comes out ahead.
     */
    public static double replacement(net.minecraft.server.level.ServerLevel level, java.util.UUID village, ItemStack one) {
        ensure();
        if (one.isEmpty()) return 0;
        double own = PriceIndex.each(level, village, one.copyWithCount(1));
        String id = BuiltInRegistries.ITEM.getKey(one.getItem()).toString();
        // A thing with something of its own (an enchantment, a maker's mark) is not to be had from its makings.
        if (!RECIPES.containsKey(id) || !one.getComponentsPatch().isEmpty()) return own;
        double r = Worth.replacement(id, k -> {
            if (k.equals(id)) return own;
            var it = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(k));
            if (it.isEmpty() || !VALUE.containsKey(it.get())) return Double.NaN;
            return PriceIndex.each(level, village, it.get());
        }, RECIPES);
        return Double.isNaN(r) ? own : Math.min(own, r);
    }

    /** Is this item on the list by name or by recipe (not merely guessed at by rarity)? */
    public static boolean known(Item item) {
        ensure();
        return VALUE.containsKey(item);
    }

    // ------------------------------------------------------------------ building it

    private static synchronized void ensure() {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null && builtFor == server && !VALUE.isEmpty()) return;
        if (server == null && !VALUE.isEmpty()) return;
        build(server);
    }

    private static void build(@Nullable MinecraftServer server) {
        VALUE.clear();
        KIND.clear();
        BOARD.clear();
        RECIPES.clear();
        fromBase = fromMarket = fromRecipes = fromRarity = 0;
        // [market] What is gathered: the base list (BaseWorth), and whole families by tag where it has not named them.
        Map<String, Double> gathered = new HashMap<>();
        for (BaseWorth.Entry e : BaseWorth.entries()) {
            ResourceLocation id = ResourceLocation.tryParse(e.id());
            if (id == null) continue;
            var item = BuiltInRegistries.ITEM.getOptional(id);
            if (item.isEmpty() || item.get() == Items.AIR) continue;
            gathered.put(id.toString(), e.worth());
            KIND.put(item.get(), kindLetter(e.kind()));
        }
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack one = new ItemStack(item);
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (gathered.containsKey(id)) continue;
            Economy.Kind k = null;
            double v = 0;
            if (one.is(ItemTags.SAPLINGS)) { v = 0.05; k = Economy.Kind.PLANT; }
            else if (one.is(ItemTags.LEAVES)) { v = 0.02; k = Economy.Kind.PLANT; }
            else if (one.is(ItemTags.SMALL_FLOWERS)) { v = 0.06; k = Economy.Kind.PLANT; }
            else if (one.is(ItemTags.FLOWERS)) { v = 0.08; k = Economy.Kind.PLANT; }
            else if (one.is(ItemTags.LOGS)) { v = 0.25; k = Economy.Kind.TIMBER; }
            else if (one.is(ItemTags.WOOL)) { v = 0.4; k = Economy.Kind.ANIMAL; }        // [market] shorn, not made
            if (k == null) continue;
            gathered.put(id, v);
            KIND.put(item, k);
        }
        fromBase = gathered.size();
        // The market's board: what it names for a gathered thing stands (the board, the shop and the wages agree); what it
        // names for a made thing is an anchor, raised to the thing's makings and the work if it was set under them.
        Map<String, Double> anchors = new HashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack one = new ItemStack(item);
            Market.Good g = Market.goodFor(one);
            if (g == null) continue;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (gathered.containsKey(id)) gathered.put(id, g.anchor());
            else anchors.put(id, g.anchor());
            if (!KIND.containsKey(item)) {
                Economy.Kind k = Economy.kindOfNamed(one);
                if (k != null) KIND.put(item, k);
            }
            fromMarket++;
        }
        // Everything made, from its recipes, the cheapest way of making it (Worth).
        List<Worth.Recipe> recipes = server == null ? List.of() : recipes(server);
        Worth.Derived d = Worth.derive(gathered, anchors, recipes);
        for (Map.Entry<String, Double> e : d.values().entrySet()) {
            var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(e.getKey()));
            if (item.isPresent()) VALUE.put(item.get(), e.getValue());
        }
        fromRecipes = d.fromRecipes();
        RAISED.clear();
        RAISED.addAll(d.raised());
        RECIPES.putAll(Worth.byOut(recipes));
        kinds(recipes, gathered.keySet(), anchors.keySet());
        if (server != null) {
            extras();
            builtFor = server;
        }
        // The board's goods, each at its own worth: the engine's number for what it deals in (Market.Good.value).
        for (Market.Good g : Market.boardGoods()) {
            ItemStack sample = PriceIndex.sampleOfGood(g);
            Double v = sample.isEmpty() ? null : VALUE.get(sample.getItem());
            BOARD.put(g, v != null ? v : g.anchor());
        }
        LOG.info("[MCA-PRICES] {}{}", summaryNow(), RAISED.isEmpty() ? "" : "; " + RAISED.size() + " of the board's raised to their makings: " + RAISED);
    }

    private static String summaryNow() {
        return VALUE.size() + " items priced: " + fromMarket + " from the market's board, " + fromBase + " from the base list, "
            + fromRecipes + " from their recipes";
    }

    /** [market] The game's recipes, as the value engine reads them: crafting, the furnaces, the stonecutter; every
     *  ingredient's choices; what a recipe gives back (a cake's three buckets). */
    private static List<Worth.Recipe> recipes(MinecraftServer server) {
        var registries = server.registryAccess();
        List<Worth.Recipe> out = new ArrayList<>();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            Recipe<?> r = holder.value();
            ItemStack made;
            try {
                made = r.getResultItem(registries);
            } catch (RuntimeException e) {
                continue;                                 // a recipe that cannot say what it makes
            }
            if (made == null || made.isEmpty()) continue;
            RecipeType<?> type = r.getType();
            Worth.Way way = type == RecipeType.SMELTING || type == RecipeType.BLASTING || type == RecipeType.SMOKING
                || type == RecipeType.CAMPFIRE_COOKING ? Worth.Way.SMELT
                : type == RecipeType.STONECUTTING ? Worth.Way.CUT
                : type == RecipeType.SMITHING ? Worth.Way.SMITH : Worth.Way.CRAFT;
            List<Worth.Part> parts = new ArrayList<>();
            List<String> back = new ArrayList<>();
            boolean ok = true;
            for (Ingredient ing : r.getIngredients()) {
                if (ing.isEmpty()) continue;
                List<String> options = new ArrayList<>();
                ItemStack first = ItemStack.EMPTY;
                for (ItemStack opt : ing.getItems()) {
                    if (opt.isEmpty()) continue;
                    if (first.isEmpty()) first = opt;
                    String id = BuiltInRegistries.ITEM.getKey(opt.getItem()).toString();
                    if (!options.contains(id)) options.add(id);
                }
                if (options.isEmpty()) { ok = false; break; }
                parts.add(new Worth.Part(options, 1));
                if (way == Worth.Way.CRAFT && first.hasCraftingRemainingItem()) {
                    ItemStack rest = first.getCraftingRemainingItem();
                    if (!rest.isEmpty()) back.add(BuiltInRegistries.ITEM.getKey(rest.getItem()).toString());
                }
            }
            if (!ok || parts.isEmpty()) continue;
            out.add(new Worth.Recipe(BuiltInRegistries.ITEM.getKey(made.getItem()).toString(), made.getCount(), parts, way, back));
        }
        // [market] What is made in the world rather than at a table: waxed with a honeycomb, weathered by the air, set by
        // water, stripped with an axe, carved with shears. Worth what it came from and next to nothing for the doing.
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (!key.getNamespace().equals("minecraft")) continue;
            String path = key.getPath();
            if (path.startsWith("waxed_")) inWorld(out, path, path.substring(6), "honeycomb", Worth.Way.CRAFT);
            for (String age : new String[]{ "exposed_", "weathered_", "oxidized_" }) {
                if (path.startsWith(age)) inWorld(out, path, path.substring(age.length()), null, Worth.Way.CUT);
                else if (path.startsWith("waxed_" + age)) inWorld(out, path, "waxed_" + path.substring(6 + age.length()), null, Worth.Way.CUT);
            }
            if (path.endsWith("_concrete")) inWorld(out, path, path + "_powder", null, Worth.Way.CUT);
            if (path.startsWith("stripped_")) inWorld(out, path, path.substring(9), null, Worth.Way.CUT);
        }
        inWorld(out, "carved_pumpkin", "pumpkin", null, Worth.Way.CUT);
        // A full thing emptied is a way to the empty one (a bucket of milk drunk leaves a bucket): it prices nothing, but
        // the town reckons it when it buys a bucket back (replacement).
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack full = new ItemStack(item);
            if (!full.hasCraftingRemainingItem()) continue;
            ItemStack empty = full.getCraftingRemainingItem();
            if (empty.isEmpty() || empty.is(item)) continue;
            out.add(Worth.Recipe.of(BuiltInRegistries.ITEM.getKey(empty.getItem()).toString(), 1, Worth.Way.EMPTY,
                Worth.Part.of(BuiltInRegistries.ITEM.getKey(item).toString(), 1)));
        }
        return out;
    }

    /** A making in the world, if both things are in the game: one {@code made} from one {@code from} (and a {@code with}). */
    private static void inWorld(List<Worth.Recipe> out, String made, String from, @Nullable String with, Worth.Way way) {
        if (BuiltInRegistries.ITEM.getOptional(ResourceLocation.withDefaultNamespace(made)).isEmpty()
            || BuiltInRegistries.ITEM.getOptional(ResourceLocation.withDefaultNamespace(from)).isEmpty()) return;
        List<Worth.Part> parts = new ArrayList<>();
        parts.add(Worth.Part.of("minecraft:" + from, 1));
        if (with != null) parts.add(Worth.Part.of("minecraft:" + with, 1));
        out.add(new Worth.Recipe("minecraft:" + made, 1, parts, way, List.of()));
    }

    /** What sort of goods each made thing is: food if it feeds, else what its makings all are, else a craft. */
    private static void kinds(List<Worth.Recipe> recipes, Set<String> gathered, Set<String> anchored) {
        for (Worth.Recipe r : recipes) {
            var made = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(r.out()));
            if (made.isEmpty() || KIND.containsKey(made.get())) continue;
            Double v = VALUE.get(made.get());
            if (v == null) continue;
            double c = Worth.cost(r, id -> {
                var it = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(id));
                Double x = it.isEmpty() ? null : VALUE.get(it.get());
                return x == null ? Double.NaN : x;
            });
            if (Double.isNaN(c) || Math.abs(c - v) > 1e-9) continue;                   // not its cheapest way
            Economy.Kind kind = null;
            boolean mixed = false;
            for (Worth.Part p : r.parts()) {
                var in = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(p.options().get(0)));
                Economy.Kind k = in.isEmpty() ? null : KIND.get(in.get());
                if (kind == null) kind = k;
                else if (k != kind) mixed = true;
            }
            KIND.put(made.get(), new ItemStack(made.get()).get(net.minecraft.core.component.DataComponents.FOOD) != null ? Economy.Kind.FOOD
                : !mixed && kind != null && kind != Economy.Kind.FOOD && kind != Economy.Kind.CRAFT ? kind
                : Economy.Kind.CRAFT);
        }
    }

    /** What the recipes cannot say: dyed shulker boxes, the smithing table's netherite, a garment's tailoring. */
    private static void extras() {
        // A shulker box dyed: the box and a dye (the dyeing is a recipe of its own kind, which says nothing).
        Double box = VALUE.get(Items.SHULKER_BOX);
        if (box != null) {
            for (net.minecraft.world.item.DyeColor c : net.minecraft.world.item.DyeColor.values()) {
                var dyed = BuiltInRegistries.ITEM.getOptional(ResourceLocation.withDefaultNamespace(c.getName() + "_shulker_box"));
                if (dyed.isPresent() && !VALUE.containsKey(dyed.get())) {
                    VALUE.put(dyed.get(), box + 0.2);
                    KIND.put(dyed.get(), Economy.Kind.CRAFT);
                    fromRecipes++;
                }
            }
        }
        // [market] An anvil worn: two thirds of a whole one chipped, a third damaged.
        Double anvil = VALUE.get(Items.ANVIL);
        if (anvil != null) {
            VALUE.putIfAbsent(Items.CHIPPED_ANVIL, anvil * 2 / 3);
            VALUE.putIfAbsent(Items.DAMAGED_ANVIL, anvil / 3);
        }
        // The smithing table's netherite: the diamond piece, an ingot and the template, and the work.
        upgrade(Items.DIAMOND_SWORD, Items.NETHERITE_SWORD);
        upgrade(Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);
        upgrade(Items.DIAMOND_AXE, Items.NETHERITE_AXE);
        upgrade(Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL);
        upgrade(Items.DIAMOND_HOE, Items.NETHERITE_HOE);
        upgrade(Items.DIAMOND_HELMET, Items.NETHERITE_HELMET);
        upgrade(Items.DIAMOND_CHESTPLATE, Items.NETHERITE_CHESTPLATE);
        upgrade(Items.DIAMOND_LEGGINGS, Items.NETHERITE_LEGGINGS);
        upgrade(Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS);
        // [fashion] A garment: its cloth by its recipe, and the tailor's cutting and stitching and its dye on top (Fashion.worth).
        for (com.jrpetty.mcassistant.item.Garment g : com.jrpetty.mcassistant.item.Garment.values()) {
            Double cloth = VALUE.get(g.item());
            if (cloth != null) VALUE.put(g.item(), Fashion.worth(g, cloth, VALUE.getOrDefault(Items.RED_DYE, 0.1)));
        }
    }

    private static void upgrade(Item from, Item to) {
        Double base = VALUE.get(from), ingot = VALUE.get(Items.NETHERITE_INGOT), plate = VALUE.get(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        if (base == null || ingot == null) return;
        if (!VALUE.containsKey(to)) fromRecipes++;
        VALUE.put(to, (base + ingot + (plate == null ? 0 : plate * 0.25)) * Worth.CRAFT_MARKUP);
        KIND.put(to, Economy.Kind.CRAFT);
    }

    /** The guess for a thing nobody lists or makes: how rare the game calls it. Nothing for what
     *  cannot be had in survival at all. */
    private static double fallback(Item item) {
        ItemStack one = new ItemStack(item);
        if (item == Items.AIR || item instanceof net.minecraft.world.item.SpawnEggItem || NOT_FOR_SALE.contains(item)) return 0;
        return switch (one.getRarity()) {
            case EPIC -> 40.0;
            case RARE -> 10.0;
            case UNCOMMON -> 2.0;
            default -> 0.05;
        };
    }

    private static final Set<Item> NOT_FOR_SALE = Set.of(
        Items.COMMAND_BLOCK, Items.CHAIN_COMMAND_BLOCK, Items.REPEATING_COMMAND_BLOCK, Items.COMMAND_BLOCK_MINECART,
        Items.STRUCTURE_BLOCK, Items.STRUCTURE_VOID, Items.JIGSAW, Items.BARRIER, Items.LIGHT, Items.DEBUG_STICK,
        Items.KNOWLEDGE_BOOK, Items.BEDROCK, Items.END_PORTAL_FRAME, Items.SPAWNER, Items.TRIAL_SPAWNER, Items.VAULT,
        Items.REINFORCED_DEEPSLATE, Items.BUDDING_AMETHYST, Items.PETRIFIED_OAK_SLAB, Items.FARMLAND, Items.DIRT_PATH,
        Items.FROGSPAWN, Items.PLAYER_HEAD, Items.INFESTED_STONE, Items.INFESTED_COBBLESTONE, Items.INFESTED_STONE_BRICKS,
        Items.INFESTED_MOSSY_STONE_BRICKS, Items.INFESTED_CRACKED_STONE_BRICKS, Items.INFESTED_CHISELED_STONE_BRICKS,
        Items.INFESTED_DEEPSLATE, Items.SUSPICIOUS_SAND, Items.SUSPICIOUS_GRAVEL);

    private static Economy.Kind kindLetter(char c) {
        return switch (c) {
            case 'F' -> Economy.Kind.FOOD;
            case 'T' -> Economy.Kind.TIMBER;
            case 'S' -> Economy.Kind.STONE;
            case 'O' -> Economy.Kind.ORE;
            case 'A' -> Economy.Kind.ANIMAL;
            case 'P' -> Economy.Kind.PLANT;
            default -> Economy.Kind.CRAFT;
        };
    }
}
