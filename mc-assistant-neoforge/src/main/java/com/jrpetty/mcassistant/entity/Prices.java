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

    /** The work in a crafted thing: a tenth on top of what went in, and a little for each part. */
    private static final double CRAFT_MARKUP = 1.10, CRAFT_PER_PART = 0.02;
    /** A smelt: the fuel and the time. */
    private static final double SMELT_COST = 0.06;
    /** The stonecutter: next to nothing. */
    private static final double CUT_MARKUP = 1.02;

    private static final Map<Item, Double> VALUE = new HashMap<>();
    private static final Map<Item, Economy.Kind> KIND = new HashMap<>();
    @Nullable private static MinecraftServer builtFor;
    private static int fromBase, fromMarket, fromRecipes, fromRarity;

    /** Forget the list (a new world, new recipes): it is worked out again when next asked. */
    public static synchronized void reset() {
        VALUE.clear();
        KIND.clear();
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
        fromBase = fromMarket = fromRecipes = fromRarity = 0;
        Set<Item> fixed = new HashSet<>();
        // The base list: what is gathered.
        for (String line : BASE) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 3) continue;
            ResourceLocation id = ResourceLocation.tryParse(p[0].contains(":") ? p[0] : "minecraft:" + p[0]);
            if (id == null) continue;
            var item = BuiltInRegistries.ITEM.getOptional(id);
            if (item.isEmpty() || item.get() == Items.AIR) continue;
            VALUE.put(item.get(), Double.parseDouble(p[1]));
            KIND.put(item.get(), kindLetter(p[2].charAt(0)));
            fixed.add(item.get());
            fromBase++;
        }
        // Whole families by tag, where the base list has not named them.
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack one = new ItemStack(item);
            if (fixed.contains(item)) continue;
            if (one.is(ItemTags.SAPLINGS)) put(fixed, item, 0.05, Economy.Kind.PLANT);
            else if (one.is(ItemTags.LEAVES)) put(fixed, item, 0.02, Economy.Kind.PLANT);
            else if (one.is(ItemTags.SMALL_FLOWERS)) put(fixed, item, 0.06, Economy.Kind.PLANT);
            else if (one.is(ItemTags.FLOWERS)) put(fixed, item, 0.08, Economy.Kind.PLANT);
            else if (one.is(ItemTags.LOGS)) put(fixed, item, 0.25, Economy.Kind.TIMBER);
        }
        // The market's board over all of it: the board, the shop and the wages agree.
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack one = new ItemStack(item);
            Market.Good g = Market.goodFor(one);
            if (g == null) continue;
            VALUE.put(item, g.value());
            if (!KIND.containsKey(item)) {
                Economy.Kind k = Economy.kindOfNamed(one);
                if (k != null) KIND.put(item, k);
            }
            fixed.add(item);
            fromMarket++;
        }
        fromBase = Math.max(0, fixed.size() - fromMarket);
        // Everything made, from its recipes, the cheapest way of making it.
        if (server != null) {
            derive(server, fixed);
            builtFor = server;
        }
        LOG.info("[MCA-PRICES] {}", summaryNow());
    }

    private static String summaryNow() {
        return VALUE.size() + " items priced: " + fromMarket + " from the market's board, " + fromBase + " from the base list, "
            + fromRecipes + " from their recipes";
    }

    private static void put(Set<Item> fixed, Item item, double v, Economy.Kind k) {
        VALUE.put(item, v);
        KIND.put(item, k);
        fixed.add(item);
    }

    private static void derive(MinecraftServer server, Set<Item> fixed) {
        var registries = server.registryAccess();
        List<RecipeHolder<?>> all = new ArrayList<>(server.getRecipeManager().getRecipes());
        for (int pass = 0; pass < 24; pass++) {
            boolean changed = false;
            for (RecipeHolder<?> holder : all) {
                Recipe<?> r = holder.value();
                ItemStack out;
                try {
                    out = r.getResultItem(registries);
                } catch (RuntimeException e) {
                    continue;                                 // a recipe that cannot say what it makes
                }
                if (out == null || out.isEmpty() || fixed.contains(out.getItem())) continue;
                double cost = 0;
                int parts = 0;
                Economy.Kind kind = null;
                boolean mixed = false, ok = true;
                for (Ingredient ing : r.getIngredients()) {
                    if (ing.isEmpty()) continue;
                    double best = Double.MAX_VALUE;
                    Economy.Kind bestKind = null;
                    for (ItemStack opt : ing.getItems()) {
                        Double v = VALUE.get(opt.getItem());
                        if (v != null && v < best) { best = v; bestKind = KIND.get(opt.getItem()); }
                    }
                    if (best == Double.MAX_VALUE) { ok = false; break; }
                    cost += best;
                    parts++;
                    if (kind == null) kind = bestKind;
                    else if (bestKind != kind) mixed = true;
                }
                if (!ok || parts == 0) continue;
                RecipeType<?> type = r.getType();
                double total;
                if (type == RecipeType.SMELTING || type == RecipeType.BLASTING || type == RecipeType.SMOKING
                        || type == RecipeType.CAMPFIRE_COOKING) {
                    total = cost + SMELT_COST;
                } else if (type == RecipeType.STONECUTTING) {
                    total = cost * CUT_MARKUP;
                } else {
                    total = cost * CRAFT_MARKUP + CRAFT_PER_PART * parts;
                }
                double each = total / Math.max(1, out.getCount());
                Item made = out.getItem();
                Double have = VALUE.get(made);
                if (have == null || each < have - 1e-9) {
                    if (have == null) fromRecipes++;
                    VALUE.put(made, each);
                    Economy.Kind k = out.get(net.minecraft.core.component.DataComponents.FOOD) != null ? Economy.Kind.FOOD
                        : !mixed && kind != null && kind != Economy.Kind.FOOD && kind != Economy.Kind.CRAFT ? kind
                        : Economy.Kind.CRAFT;
                    KIND.put(made, k);
                    changed = true;
                }
            }
            if (!changed) break;
        }
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
    }

    private static void upgrade(Item from, Item to) {
        Double base = VALUE.get(from), ingot = VALUE.get(Items.NETHERITE_INGOT), plate = VALUE.get(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        if (base == null || ingot == null) return;
        if (!VALUE.containsKey(to)) fromRecipes++;
        VALUE.put(to, (base + ingot + (plate == null ? 0 : plate * 0.25)) * CRAFT_MARKUP);
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

    // ------------------------------------------------------------------ the base list

    /**
     * Everything gathered rather than made: item, coins each, and what sort of goods it is
     * (F food, T timber, S stone and earth, O ore, metal and gems, A from animals and monsters,
     * P plants, C curiosities and finds). What is made from these is priced from its recipe.
     * Anchored on the market's board: a loaf 0.3, a log 0.25, a cobblestone 0.04, an iron ingot
     * 1.5, a gold ingot 9, a diamond 24.
     */
    static final String[] BASE = {
        // ---- stone, earth and sand: the quarry and the spade
        "stone 0.05 S", "cobblestone 0.04 S", "deepslate 0.06 S", "cobbled_deepslate 0.05 S", "granite 0.04 S",
        "diorite 0.04 S", "andesite 0.04 S", "tuff 0.05 S", "calcite 0.08 S", "dripstone_block 0.08 S",
        "pointed_dripstone 0.1 S", "dirt 0.01 S", "coarse_dirt 0.02 S", "rooted_dirt 0.03 S", "grass_block 0.03 S",
        "podzol 0.04 S", "mycelium 0.1 S", "mud 0.03 S", "clay_ball 0.05 S", "clay 0.2 S", "gravel 0.03 S",
        "sand 0.05 S", "red_sand 0.06 S", "snowball 0.01 S", "ice 0.05 S", "flint 0.2 S", "netherrack 0.02 S",
        "soul_sand 0.5 S", "soul_soil 0.3 S", "basalt 0.05 S", "blackstone 0.05 S", "gilded_blackstone 1.2 S",
        "magma_block 0.3 S", "end_stone 0.1 S", "obsidian 3.0 S", "crying_obsidian 4.0 S", "glowstone_dust 0.3 S",
        "amethyst_shard 0.5 S", "amethyst_cluster 1.0 S", "small_amethyst_bud 0.2 S", "medium_amethyst_bud 0.3 S",
        "large_amethyst_bud 0.5 S", "prismarine_shard 0.4 S", "prismarine_crystals 0.5 S", "packed_mud 0.05 S",
        "terracotta 0.1 S", "white_terracotta 0.12 S", "orange_terracotta 0.12 S", "yellow_terracotta 0.12 S",
        "brown_terracotta 0.12 S", "red_terracotta 0.12 S", "light_gray_terracotta 0.12 S", "cobweb 0.3 S",
        "sculk 0.2 S", "sculk_vein 0.1 S", "sculk_sensor 3.0 S", "calibrated_sculk_sensor 5.0 S", "sculk_catalyst 6.0 S",
        "sculk_shrieker 6.0 S", "moss_block 0.1 S", "sponge 8.0 S", "wet_sponge 8.0 S", "ochre_froglight 3.0 S",
        "verdant_froglight 3.0 S", "pearlescent_froglight 3.0 S", "chorus_flower 1.0 P", "chorus_plant 0.1 P",
        "crimson_nylium 0.05 S", "warped_nylium 0.05 S", "exposed_copper 3.6 O", "weathered_copper 3.6 O",
        "oxidized_copper 3.6 O", "bee_nest 1.0 A", "firework_rocket 0.3 C",
        // ---- ores in the block (cut whole), and what they give
        "coal_ore 0.5 O", "deepslate_coal_ore 0.55 O", "iron_ore 1.1 O", "deepslate_iron_ore 1.15 O",
        "copper_ore 0.5 O", "deepslate_copper_ore 0.55 O", "gold_ore 8.0 O", "deepslate_gold_ore 8.2 O",
        "nether_gold_ore 2.0 O", "redstone_ore 1.5 O", "deepslate_redstone_ore 1.6 O", "lapis_ore 2.5 O",
        "deepslate_lapis_ore 2.6 O", "emerald_ore 7.0 O", "deepslate_emerald_ore 7.2 O", "diamond_ore 25.0 O",
        "deepslate_diamond_ore 25.5 O", "nether_quartz_ore 0.6 O", "ancient_debris 40.0 O",
        "coal 0.4 O", "raw_iron 1.0 O", "raw_copper 0.3 O", "raw_gold 7.0 O", "redstone 0.3 O", "lapis_lazuli 0.5 O",
        "quartz 0.5 O", "diamond 24.0 O", "emerald 6.0 O", "echo_shard 10.0 O",
        // ---- the farm, the orchard and the garden
        "wheat 0.1 F", "wheat_seeds 0.02 F", "beetroot 0.1 F", "beetroot_seeds 0.03 F", "carrot 0.15 F",
        "potato 0.15 F", "poisonous_potato 0.01 F", "pumpkin 0.4 F", "melon_slice 0.1 F", "melon 0.8 F",
        "sweet_berries 0.1 F", "glow_berries 0.15 F", "apple 0.3 F", "cocoa_beans 0.3 F", "sugar_cane 0.15 F",
        "chorus_fruit 0.3 F", "brown_mushroom 0.05 F", "red_mushroom 0.05 F", "nether_wart 0.5 F", "honey_bottle 1.0 F",
        "torchflower_seeds 2.0 P", "pitcher_pod 2.0 P", "kelp 0.02 F", "bamboo 0.02 T", "cactus 0.05 P",
        // ---- the pasture, the hunt and the river
        "beef 0.3 F", "porkchop 0.3 F", "mutton 0.25 F", "chicken 0.2 F", "rabbit 0.25 F", "cod 0.25 F",
        "salmon 0.3 F", "tropical_fish 0.4 F", "pufferfish 0.3 F", "egg 0.2 A", "leather 0.6 A", "rabbit_hide 0.15 A",
        "rabbit_foot 1.5 A", "feather 0.1 A", "string 0.2 A", "bone 0.1 A", "gunpowder 0.4 A", "spider_eye 0.2 A",
        "slime_ball 1.5 A", "ink_sac 0.2 A", "glow_ink_sac 0.5 A", "honeycomb 0.6 A", "rotten_flesh 0.02 A",
        "phantom_membrane 1.5 A", "ender_pearl 2.0 A", "blaze_rod 4.0 A", "breeze_rod 4.0 A", "ghast_tear 4.0 A",
        "magma_cream 1.5 A", "shulker_shell 15.0 A", "turtle_scute 3.0 A", "armadillo_scute 1.5 A",
        "nautilus_shell 5.0 A", "goat_horn 8.0 A", "saddle 6.0 A", "name_tag 5.0 A",
        "iron_horse_armor 8.0 A", "golden_horse_armor 10.0 A", "diamond_horse_armor 30.0 A",
        "skeleton_skull 10.0 A", "zombie_head 10.0 A", "creeper_head 10.0 A", "piglin_head 10.0 A",
        "wither_skeleton_skull 30.0 A", "dragon_head 100.0 A", "dragon_breath 5.0 A", "sniffer_egg 20.0 A",
        "turtle_egg 2.0 A", "milk_bucket 5.3 A", "water_bucket 5.05 S", "lava_bucket 5.3 S", "powder_snow_bucket 5.1 S",
        "cod_bucket 5.5 A", "salmon_bucket 5.6 A", "tropical_fish_bucket 6.0 A", "pufferfish_bucket 6.0 A",
        "axolotl_bucket 8.0 A", "tadpole_bucket 6.0 A",
        // ---- plants and the wild
        "vine 0.03 P", "glow_lichen 0.05 P", "lily_pad 0.1 P", "sea_pickle 0.2 P", "seagrass 0.02 P",
        "short_grass 0.01 P", "tall_grass 0.02 P", "fern 0.01 P", "large_fern 0.02 P", "dead_bush 0.02 P",
        "hanging_roots 0.03 P", "spore_blossom 2.0 P", "big_dripleaf 0.2 P", "small_dripleaf 0.2 P",
        "mangrove_roots 0.05 P", "mangrove_propagule 0.05 P", "pink_petals 0.05 P", "wither_rose 2.0 P",
        "torchflower 1.0 P", "pitcher_plant 1.0 P", "crimson_fungus 0.1 P", "warped_fungus 0.1 P",
        "crimson_roots 0.03 P", "warped_roots 0.03 P", "nether_sprouts 0.02 P", "weeping_vines 0.05 P",
        "twisting_vines 0.05 P", "shroomlight 0.5 P", "warped_wart_block 0.2 P", "nether_wart_block 0.2 P",
        "azalea 0.1 P", "flowering_azalea 0.15 P", "moss_carpet 0.07 P", "brown_mushroom_block 0.05 P",
        "red_mushroom_block 0.05 P", "mushroom_stem 0.05 P",
        "tube_coral 0.3 P", "brain_coral 0.3 P", "bubble_coral 0.3 P", "fire_coral 0.3 P", "horn_coral 0.3 P",
        "tube_coral_fan 0.3 P", "brain_coral_fan 0.3 P", "bubble_coral_fan 0.3 P", "fire_coral_fan 0.3 P",
        "horn_coral_fan 0.3 P", "tube_coral_block 0.5 P", "brain_coral_block 0.5 P", "bubble_coral_block 0.5 P",
        "fire_coral_block 0.5 P", "horn_coral_block 0.5 P", "dead_tube_coral 0.05 P", "dead_brain_coral 0.05 P",
        "dead_bubble_coral 0.05 P", "dead_fire_coral 0.05 P", "dead_horn_coral 0.05 P", "dead_tube_coral_fan 0.05 P",
        "dead_brain_coral_fan 0.05 P", "dead_bubble_coral_fan 0.05 P", "dead_fire_coral_fan 0.05 P",
        "dead_horn_coral_fan 0.05 P", "dead_tube_coral_block 0.1 S", "dead_brain_coral_block 0.1 S",
        "dead_bubble_coral_block 0.1 S", "dead_fire_coral_block 0.1 S", "dead_horn_coral_block 0.1 S",
        // ---- the rare finds: treasure, the deep places, the End
        "heart_of_the_sea 50.0 C", "trident 40.0 C", "nether_star 200.0 C", "elytra 150.0 C", "dragon_egg 500.0 C",
        "totem_of_undying 60.0 C", "enchanted_golden_apple 100.0 F", "heavy_core 60.0 C", "trial_key 5.0 C",
        "ominous_trial_key 10.0 C", "ominous_bottle 5.0 C", "disc_fragment_5 3.0 C", "experience_bottle 2.0 C",
        "netherite_upgrade_smithing_template 40.0 C", "sentry_armor_trim_smithing_template 10.0 C",
        "dune_armor_trim_smithing_template 10.0 C", "coast_armor_trim_smithing_template 10.0 C",
        "wild_armor_trim_smithing_template 10.0 C", "ward_armor_trim_smithing_template 15.0 C",
        "eye_armor_trim_smithing_template 15.0 C", "vex_armor_trim_smithing_template 15.0 C",
        "tide_armor_trim_smithing_template 10.0 C", "snout_armor_trim_smithing_template 10.0 C",
        "rib_armor_trim_smithing_template 10.0 C", "spire_armor_trim_smithing_template 15.0 C",
        "wayfinder_armor_trim_smithing_template 10.0 C", "shaper_armor_trim_smithing_template 10.0 C",
        "silence_armor_trim_smithing_template 25.0 C", "raiser_armor_trim_smithing_template 10.0 C",
        "host_armor_trim_smithing_template 10.0 C", "flow_armor_trim_smithing_template 12.0 C",
        "bolt_armor_trim_smithing_template 12.0 C",
        "music_disc_13 20.0 C", "music_disc_cat 20.0 C", "music_disc_blocks 20.0 C", "music_disc_chirp 20.0 C",
        "music_disc_far 20.0 C", "music_disc_mall 20.0 C", "music_disc_mellohi 20.0 C", "music_disc_stal 20.0 C",
        "music_disc_strad 20.0 C", "music_disc_ward 20.0 C", "music_disc_11 20.0 C", "music_disc_wait 20.0 C",
        "music_disc_otherside 30.0 C", "music_disc_5 30.0 C", "music_disc_pigstep 30.0 C", "music_disc_relic 30.0 C",
        "music_disc_creator 30.0 C", "music_disc_creator_music_box 30.0 C", "music_disc_precipice 30.0 C",
        "angler_pottery_sherd 3.0 C", "archer_pottery_sherd 3.0 C", "arms_up_pottery_sherd 3.0 C",
        "blade_pottery_sherd 3.0 C", "brewer_pottery_sherd 3.0 C", "burn_pottery_sherd 3.0 C",
        "danger_pottery_sherd 3.0 C", "explorer_pottery_sherd 3.0 C", "flow_pottery_sherd 3.0 C",
        "friend_pottery_sherd 3.0 C", "guster_pottery_sherd 3.0 C", "heart_pottery_sherd 3.0 C",
        "heartbreak_pottery_sherd 3.0 C", "howl_pottery_sherd 3.0 C", "miner_pottery_sherd 3.0 C",
        "mourner_pottery_sherd 3.0 C", "plenty_pottery_sherd 3.0 C", "prize_pottery_sherd 3.0 C",
        "scrape_pottery_sherd 3.0 C", "sheaf_pottery_sherd 3.0 C", "shelter_pottery_sherd 3.0 C",
        "skull_pottery_sherd 3.0 C", "snort_pottery_sherd 3.0 C",
        "chainmail_helmet 6.0 C", "chainmail_chestplate 10.0 C", "chainmail_leggings 8.0 C", "chainmail_boots 5.0 C",
        "bell 12.0 C", "enchanted_book 4.0 C", "written_book 2.5 C", "filled_map 1.5 C",
        "suspicious_stew 0.4 F", "tipped_arrow 0.5 C", "firework_star 0.5 C", "potion 1.0 C",
        "splash_potion 4.5 C", "lingering_potion 6.0 C",
    };
}
