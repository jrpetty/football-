package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.BaseWorth;
import com.jrpetty.mcassistant.village.Worth;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * [business-books] What a thing costs to have, the whole way back: the cost basis of every item in the game.
 *
 * <p>A price says what a thing fetches; a cost says what went into it. Every item is costed by how it is really had:
 * <ul>
 * <li><b>Gathered</b> (wheat, a log, cobble, raw iron, a fish, wool): the hand's time, at its trade's day's wage over
 *     what a hand of that trade brings in a day (a farmer some ninety-six wheat, a miner a couple of dozen raw iron),
 *     the wear on its tools (a pick worn down, a rod's line), and a tenth for the yard, the barn or the boat.</li>
 * <li><b>Made</b> (bread, an iron pick, stone bricks, a dyed rug, a cooked steak): the cheapest of the game's own recipes
 *     (crafting, the furnace, the smoker and the blast furnace with the fuel's share, the stonecutter, the smithing
 *     table, a modpack's recipes as well), every part at its own cost basis, again the whole way back, and the maker's
 *     time at its trade's wage and the workshop's share of its overheads on top.</li>
 * <li><b>Brewed</b>: the potions at the stand: a bottle of water, a nether wart to make it awkward, the ingredient,
 *     redstone or glowstone to draw it out or make it strong, gunpowder to splash it, and a share of the blaze powder that
 *     fires the stand; the café's drinks of their fruit and three bottles.</li>
 * <li><b>Enchanted</b>: the plain thing, the lapis, and the enchanter's time a level.</li>
 * <li><b>Bought in</b>: what nobody here can gather or make (a saddle, a nether star) costs what the traders ask.</li>
 * </ul>
 * This is the standard cost, the same for every town; a business's own books cost what it actually paid and used
 * (Business stock lots, Businesses.made), so bread baked of cheap wheat costs less than bread of dear. The standard is
 * what a thing with no history is booked at (a player's goods at a stall), and the yardstick the books show beside it.
 */
public final class CostBook {

    private CostBook() {}

    /** One part of a thing's cost: so many of something, at so much a piece. */
    public record Part(String id, double n, double each) {
        public double total() { return n * each; }
    }

    /**
     * One thing's cost basis, a piece, in coin: its materials (what went into it, at their own cost), the fuel, the hands'
     * time, the tools' wear and the overheads; how it is had ("gathered by a farmer", "crafted", "smelted"), its parts, and
     * how many steps back the chain goes.
     */
    public record Basis(String id, double materials, double fuel, double labour, double tools, double overhead, String how,
                        List<Part> parts, int depth) {
        public double total() { return materials + fuel + labour + tools + overhead; }

        /** "0.31c: 0.25c materials (3 wheat at 0.08c), 0.04c labour, ..." */
        public String words() {
            StringBuilder sb = new StringBuilder(Businesses.coins(total())).append(" (").append(how).append(")");
            List<String> bits = new ArrayList<>();
            if (materials > 0) bits.add(Businesses.coins(materials) + " materials");
            if (fuel > 0) bits.add(Businesses.coins(fuel) + " fuel");
            if (labour > 0) bits.add(Businesses.coins(labour) + " labour");
            if (tools > 0) bits.add(Businesses.coins(tools) + " tool wear");
            if (overhead > 0) bits.add(Businesses.coins(overhead) + " overheads");
            if (!bits.isEmpty()) sb.append(": ").append(String.join(", ", bits));
            if (!parts.isEmpty()) {
                List<String> p = new ArrayList<>();
                for (Part x : parts) p.add(trimN(x.n()) + " " + shortName(x.id()) + " at " + Businesses.coins(x.each()));
                sb.append(" — ").append(String.join(", ", p));
            }
            return sb.toString();
        }
    }

    /** The maker's batches a day, by the way it is made (a furnace minds itself; a smithing table is slow work). */
    static final double CRAFTS_A_DAY = 96, SMELTS_A_DAY = 160, CUTS_A_DAY = 256, SMITHS_A_DAY = 48, BREWS_A_DAY = 24;
    /** The workshop's share of its overheads, on the maker's time; the yard's, barn's or boat's on a gatherer's. */
    static final double SHOP_OVERHEAD = 0.15, YARD_OVERHEAD = 0.10;
    /** How deep a chain is followed before it is given up (no recipe in the game goes near it). */
    static final int MOST_DEPTH = 24;

    private static final Map<String, Basis> MEMO = new HashMap<>();
    @Nullable private static MinecraftServer builtFor;

    /** Forget every basis (a new world, new recipes, new wages). */
    public static synchronized void reset() {
        MEMO.clear();
        builtFor = null;
    }

    private static synchronized void fresh() {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != builtFor) {
            MEMO.clear();
            builtFor = server;
        }
    }

    /** The cost basis of one of this item. */
    public static Basis of(Item item) {
        return of(id(item));
    }

    /** The cost basis of one of this stack's thing: its brew, its enchantments, the café's drink. */
    public static Basis of(ItemStack s) {
        if (s.isEmpty()) return new Basis("minecraft:air", 0, 0, 0, 0, 0, "nothing", List.of(), 0);
        String drink = Cafe.drinkOf(s);
        if (drink != null) return drinkBasis(drink);
        if (s.is(Items.POTION) || s.is(Items.SPLASH_POTION) || s.is(Items.LINGERING_POTION)) {
            PotionContents pc = s.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            Basis b = brewed(s.getItem(), pc.potion().map(h -> h.value()).orElse(null));
            if (b != null) return b;
        }
        Basis plain = of(s.getItem());
        var ench = s.getOrDefault(DataComponents.ENCHANTMENTS, net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        var stored = s.getOrDefault(DataComponents.STORED_ENCHANTMENTS, net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
        int levels = 0;
        for (var e : ench.entrySet()) levels += e.getIntValue();
        for (var e : stored.entrySet()) levels += e.getIntValue();
        if (levels <= 0) return plain;
        Basis lapis = of(Items.LAPIS_LAZULI);
        double wage = dayWage(StationTask.ENCHANT);
        List<Part> parts = new ArrayList<>();
        parts.add(new Part(plain.id(), 1, plain.total()));
        parts.add(new Part(lapis.id(), levels, lapis.total()));
        double labour = wage * levels / 12.0;
        return new Basis(plain.id(), plain.total() + lapis.total() * levels, 0, labour, 0, labour * SHOP_OVERHEAD,
            "enchanted, " + levels + (levels == 1 ? " level" : " levels"), parts, plain.depth() + 1);
    }

    /** The cost basis of the thing with this id ("minecraft:bread"). */
    public static synchronized Basis of(String id) {
        fresh();
        Basis b = MEMO.get(id);
        if (b != null) return b;
        b = work(id, new HashSet<>(), 0);
        MEMO.put(id, b);
        return b;
    }

    /** Tests: every item the game knows, costed: how many by each way, and any with no chain behind it. */
    public static synchronized Map<String, Integer> coverage(List<String> unbacked) {
        Map<String, Integer> ways = new java.util.TreeMap<>();
        for (Item it : BuiltInRegistries.ITEM) {
            if (it == Items.AIR) continue;
            Basis b = of(it);
            String way = b.how().split("[ ,]")[0];
            ways.merge(way, 1, Integer::sum);
            if (!(b.total() >= 0) || Double.isNaN(b.total())) unbacked.add(b.id() + " NaN");
        }
        return ways;
    }

    // ------------------------------------------------------------------ the working out

    private static Basis work(String id, Set<String> going, int depth) {
        Basis known = MEMO.get(id);
        if (known != null) return known;
        Item item = itemOf(id);
        if (item == null || item == Items.AIR) return new Basis(id, 0, 0, 0, 0, 0, "nothing", List.of(), 0);
        ItemStack one = new ItemStack(item);
        // Gathered: the base list's things (what is mined, grown, caught and shorn), and whole families by tag.
        boolean gathered = BASE.containsKey(id) || one.is(ItemTags.LOGS) || one.is(ItemTags.SAPLINGS) || one.is(ItemTags.LEAVES)
            || one.is(ItemTags.FLOWERS) || one.is(ItemTags.WOOL) && item == Items.WHITE_WOOL;
        Basis best = null;
        if (depth < MOST_DEPTH && !going.contains(id)) {
            going.add(id);
            for (Worth.Recipe r : Prices.recipesFor(item)) {
                if (!Worth.makes(r) || Worth.unmaking(r, Prices.recipes())) continue;
                Basis b = fromRecipe(id, item, r, going, depth);
                if (b != null && (best == null || b.total() < best.total())) best = b;
            }
            going.remove(id);
        }
        Basis g = gathered ? gatheredBasis(id, item) : null;
        // What can be had either way (dyed wool, a cut log) is had the cheaper way.
        if (g != null && (best == null || g.total() <= best.total())) return g;
        if (best != null) return best;
        // A modpack's or the town's own make with no recipe the game knows (its makers' own code): what the town makes it
        // of is its board price's makings, a hand's time; else bought in from the traders at what they ask.
        double worth = Prices.each(item);
        if (worth <= 0) return new Basis(id, 0, 0, 0, 0, 0, "not to be had", List.of(), 0);
        if (!id.startsWith("minecraft:")) {
            double labour = worth * 0.35, overhead = labour * SHOP_OVERHEAD;
            return new Basis(id, worth - labour - overhead, 0, labour, 0, overhead, "made by the town's makers to their own way",
                List.of(new Part("makings", 1, worth - labour - overhead)), 1);
        }
        return new Basis(id, worth, 0, 0, 0, 0, "bought in from the traders", List.of(new Part("traders", 1, worth)), 1);
    }

    @Nullable
    private static Basis fromRecipe(String id, Item item, Worth.Recipe r, Set<String> going, int depth) {
        double materials = 0;
        int deepest = 0;
        List<Part> parts = new ArrayList<>();
        for (Worth.Part p : r.parts()) {
            Basis cheapest = null;
            for (String o : p.options()) {
                if (going.contains(o)) continue;
                Basis b = MEMO.containsKey(o) ? MEMO.get(o) : work(o, going, depth + 1);
                if (!going.contains(o) && !MEMO.containsKey(o)) MEMO.put(o, b);
                if (b.how().equals("not to be had")) continue;
                if (cheapest == null || b.total() < cheapest.total()) cheapest = b;
            }
            if (cheapest == null) return null;                     // a part that cannot be had: not this way
            materials += cheapest.total() * p.n();
            deepest = Math.max(deepest, cheapest.depth());
            mergePart(parts, cheapest.id(), p.n(), cheapest.total());
        }
        for (String back : r.back()) {
            Basis b = MEMO.get(back);
            if (b != null) {
                materials -= b.total();
                mergePart(parts, back, -1, b.total());
            }
        }
        materials = Math.max(0, materials);
        int count = Math.max(1, r.count());
        StationTask maker = makerOf(item, r.way());
        double wage = dayWage(maker);
        double fuel = 0, labour;
        String how;
        switch (r.way()) {
            case SMELT -> {
                // A piece of coal burns eight smelts: its eighth, at the coal's own cost.
                fuel = MEMO.containsKey("minecraft:coal") ? MEMO.get("minecraft:coal").total() / 8.0 : gatheredBasis("minecraft:coal", Items.COAL).total() / 8.0;
                labour = wage / SMELTS_A_DAY;
                how = maker == StationTask.COOK ? "cooked" : "smelted";
            }
            case CUT -> { labour = wage / CUTS_A_DAY; how = "cut"; }
            case SMITH -> { labour = wage / SMITHS_A_DAY; how = "forged at the smithing table"; }
            default -> { labour = wage * (1 + r.pieces() * 0.1) / CRAFTS_A_DAY; how = "crafted"; }
        }
        double overhead = labour * SHOP_OVERHEAD;
        return new Basis(id, materials / count, fuel / count, labour / count, 0, overhead / count,
            how + " by " + JobWorthWords.a(maker) + (count > 1 ? ", " + count + " a batch" : ""), scale(parts, 1.0 / count), deepest + 1);
    }

    private static void mergePart(List<Part> parts, String id, double n, double each) {
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).id().equals(id)) {
                parts.set(i, new Part(id, parts.get(i).n() + n, each));
                return;
            }
        }
        parts.add(new Part(id, n, each));
    }

    private static List<Part> scale(List<Part> parts, double k) {
        if (k == 1.0) return List.copyOf(parts);
        List<Part> out = new ArrayList<>();
        for (Part p : parts) out.add(new Part(p.id(), p.n() * k, p.each()));
        return out;
    }

    // ------------------------------------------------------------------ gathered

    /** The base list, by id: what is gathered rather than made (BaseWorth), with its kind. */
    private static final Map<String, Character> BASE = new HashMap<>();

    static {
        for (BaseWorth.Entry e : BaseWorth.entries()) {
            ResourceLocation rl = ResourceLocation.tryParse(e.id());
            if (rl != null) BASE.put(rl.toString(), e.kind());
        }
    }

    /** A gathered thing: the hand's time, its tools' wear and the yard's share. */
    static Basis gatheredBasis(String id, Item item) {
        StationTask t = gatherer(item);
        double perDay = perDay(item);
        double wage = dayWage(t);
        double labour = wage / perDay;
        double tools = TOOL_WEAR.getOrDefault(t, 0.1) / perDay;
        double overhead = labour * YARD_OVERHEAD;
        return new Basis(id, 0, 0, labour, tools, overhead, "gathered by " + JobWorthWords.a(t) + ", about " + trimN(perDay) + " a day",
            List.of(), 0);
    }

    /** What a hand's tools wear down by in a day's work, in coin (a pick a week or so, a hoe a month). */
    private static final Map<StationTask, Double> TOOL_WEAR = Map.of(
        StationTask.MINE, 0.6, StationTask.WOOD, 0.4, StationTask.FARM, 0.1, StationTask.FISH, 0.15,
        StationTask.HUNT, 0.3, StationTask.RANCH, 0.1, StationTask.BEEKEEP, 0.1, StationTask.DIVER, 0.2,
        StationTask.NETHER, 0.8, StationTask.CAVE, 0.7);

    /** Who gathers it: the farmer its crops, the miner its stone and ore, the woodcutter its logs... */
    static StationTask gatherer(Item item) {
        ItemStack s = new ItemStack(item);
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        if (s.is(ItemTags.LOGS) || s.is(ItemTags.SAPLINGS) || s.is(ItemTags.LEAVES) || item == Items.APPLE || item == Items.STICK) return StationTask.WOOD;
        if (item == Items.COD || item == Items.SALMON || item == Items.TROPICAL_FISH || item == Items.PUFFERFISH || item == Items.INK_SAC
            || item == Items.NAUTILUS_SHELL || item == Items.SADDLE) return StationTask.FISH;
        if (item == Items.KELP || item == Items.SEAGRASS || item == Items.SEA_PICKLE || item == Items.TURTLE_SCUTE || path.contains("coral")
            || item == Items.PRISMARINE_SHARD || item == Items.PRISMARINE_CRYSTALS || item == Items.CLAY_BALL) return StationTask.DIVER;
        if (item == Items.HONEYCOMB || item == Items.HONEY_BOTTLE) return StationTask.BEEKEEP;
        if (s.is(ItemTags.WOOL) || item == Items.MILK_BUCKET || item == Items.EGG || item == Items.LEATHER && false) return StationTask.RANCH;
        if (item == Items.BEEF || item == Items.PORKCHOP || item == Items.MUTTON || item == Items.CHICKEN || item == Items.RABBIT
            || item == Items.LEATHER || item == Items.FEATHER || item == Items.RABBIT_HIDE || item == Items.RABBIT_FOOT) return StationTask.HUNT;
        if (item == Items.STRING || item == Items.BONE || item == Items.ROTTEN_FLESH || item == Items.GUNPOWDER || item == Items.SPIDER_EYE
            || item == Items.ARROW || item == Items.ENDER_PEARL || item == Items.SLIME_BALL) return StationTask.GUARD;
        if (path.startsWith("nether") || path.contains("quartz") || path.contains("blaze") || path.contains("ghast") || path.contains("magma")
            || path.contains("soul") || path.contains("crimson") || path.contains("warped") || path.contains("glowstone")
            || path.contains("ancient_debris") || path.contains("basalt") || path.contains("blackstone") || path.contains("shroomlight")) return StationTask.NETHER;
        Character k = BASE.get(BuiltInRegistries.ITEM.getKey(item).toString());
        if (k != null && (k == 'S' || k == 'O')) return StationTask.MINE;
        if (k != null && k == 'A') return StationTask.RANCH;
        if (k != null && k == 'T') return StationTask.WOOD;
        if (item == Items.DIAMOND || item == Items.EMERALD || item == Items.COAL || path.startsWith("raw_") || path.endsWith("_ore")
            || item == Items.REDSTONE || item == Items.LAPIS_LAZULI || item == Items.OBSIDIAN || item == Items.AMETHYST_SHARD) return StationTask.MINE;
        return StationTask.FARM;
    }

    /** What a hand of its trade brings in of it in a day. */
    static double perDay(Item item) {
        ItemStack s = new ItemStack(item);
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        if (item == Items.DIAMOND || item == Items.EMERALD) return 2;
        if (item == Items.NETHER_STAR || item == Items.HEART_OF_THE_SEA || item == Items.DRAGON_EGG || item == Items.ELYTRA
            || item == Items.TOTEM_OF_UNDYING || item == Items.TRIDENT || path.contains("music_disc") || path.contains("template")) return 0.2;
        if (item == Items.ANCIENT_DEBRIS || item == Items.NAUTILUS_SHELL || item == Items.SADDLE || item == Items.NAME_TAG
            || item == Items.GHAST_TEAR || item == Items.SHULKER_SHELL || item == Items.TURTLE_SCUTE || item == Items.RABBIT_FOOT) return 1.5;
        if (item == Items.OBSIDIAN || item == Items.BLAZE_ROD || item == Items.ENDER_PEARL || item == Items.SLIME_BALL) return 12;
        if (path.startsWith("raw_") || path.endsWith("_ore") || item == Items.COAL || item == Items.REDSTONE || item == Items.LAPIS_LAZULI
            || item == Items.QUARTZ || item == Items.AMETHYST_SHARD || item == Items.GLOWSTONE_DUST) return 24;
        if (item == Items.BEEF || item == Items.PORKCHOP || item == Items.MUTTON || item == Items.CHICKEN || item == Items.RABBIT
            || item == Items.LEATHER || item == Items.RABBIT_HIDE) return 12;
        if (item == Items.COD || item == Items.SALMON || item == Items.TROPICAL_FISH || item == Items.PUFFERFISH) return 24;
        if (item == Items.HONEYCOMB || item == Items.HONEY_BOTTLE || item == Items.MILK_BUCKET) return 8;
        if (s.is(ItemTags.LOGS)) return 64;
        if (s.is(ItemTags.WOOL) || item == Items.EGG || item == Items.FEATHER || item == Items.STRING || item == Items.BONE
            || item == Items.GUNPOWDER || item == Items.SPIDER_EYE || item == Items.ROTTEN_FLESH || item == Items.INK_SAC) return 24;
        Character k = BASE.get(BuiltInRegistries.ITEM.getKey(item).toString());
        if (k != null && k == 'S') return 192;
        if (s.is(ItemTags.SAPLINGS) || s.is(ItemTags.LEAVES) || s.is(ItemTags.FLOWERS) || k != null && k == 'P') return 128;
        return 96;                                                         // a field's crops, a day's picking
    }

    /** A trade's day's wage, a journeyman's in a hamlet (JobWorth), or the trades' old rates when nothing can be asked. */
    static double dayWage(StationTask t) {
        try {
            int w = JobWorth.rate(t, null);
            if (w > 0) return w;
        } catch (RuntimeException ignored) {
            // no town to ask (a plain unit test): the table below
        }
        return switch (t) {
            case SMITH, BREW, ENCHANT, BANK -> 3;
            case MINE, COOK, TAILOR, SHOP, NETHER, CAVE -> 2.5;
            default -> 2;
        };
    }

    /** Who makes it at the bench: the cook its food, the smith its tools and armour, the tailor its cloth, else the shop. */
    static StationTask makerOf(Item item, Worth.Way way) {
        ItemStack s = new ItemStack(item);
        if (s.get(DataComponents.FOOD) != null) return StationTask.COOK;
        if (s.isDamageableItem() || s.is(ItemTags.ANVIL) || item == Items.IRON_BLOCK || item == Items.CHAIN || item == Items.IRON_BARS
            || item == Items.BUCKET || item == Items.CAULDRON || item == Items.LANTERN) return StationTask.SMITH;
        if (s.is(ItemTags.WOOL) || s.is(ItemTags.WOOL_CARPETS) || s.is(ItemTags.BEDS) || s.is(ItemTags.BANNERS)) return StationTask.TAILOR;
        if (way == Worth.Way.SMELT) return StationTask.SMELT;
        return StationTask.SHOP;
    }

    // ------------------------------------------------------------------ brewed

    /** The café's drink: its fruit and three bottles make three. */
    static Basis drinkBasis(String drinkId) {
        Cafe.Drink d = Cafe.drinkFor(drinkId);
        if (d == null) return of(Items.POTION);
        Basis fruit = of(d.from()), bottle = of(Items.GLASS_BOTTLE);
        double materials = (fruit.total() * d.needs() + bottle.total() * 3) / 3.0;
        double labour = dayWage(StationTask.COOK) / CRAFTS_A_DAY;
        return new Basis("drink/" + drinkId, materials, 0, labour, 0, labour * SHOP_OVERHEAD, "made at the café, three a batch",
            List.of(new Part(id(d.from()), d.needs() / 3.0, fruit.total()), new Part("minecraft:glass_bottle", 1, bottle.total())),
            Math.max(fruit.depth(), bottle.depth()) + 1);
    }

    /** What each potion is brewed from, over an awkward potion (or over water for the plain ones). */
    private static final Map<String, Item> INGREDIENT = new HashMap<>();

    static {
        put(Potions.SWIFTNESS, Items.SUGAR);
        put(Potions.LEAPING, Items.RABBIT_FOOT);
        put(Potions.FIRE_RESISTANCE, Items.MAGMA_CREAM);
        put(Potions.HEALING, Items.GLISTERING_MELON_SLICE);
        put(Potions.NIGHT_VISION, Items.GOLDEN_CARROT);
        put(Potions.WATER_BREATHING, Items.PUFFERFISH);
        put(Potions.REGENERATION, Items.GHAST_TEAR);
        put(Potions.STRENGTH, Items.BLAZE_POWDER);
        put(Potions.POISON, Items.SPIDER_EYE);
        put(Potions.SLOW_FALLING, Items.PHANTOM_MEMBRANE);
        put(Potions.TURTLE_MASTER, Items.TURTLE_HELMET);
        put(Potions.WEAKNESS, Items.FERMENTED_SPIDER_EYE);
    }

    private static void put(net.minecraft.core.Holder<Potion> p, Item in) {
        INGREDIENT.put(p.getRegisteredName(), in);
    }

    /** A potion at the stand: three bottles of water, a wart, the ingredient, a modifier, the blaze powder's share. */
    @Nullable
    static Basis brewed(Item form, @Nullable Potion potion) {
        Basis bottle = of(Items.GLASS_BOTTLE), wart = of(Items.NETHER_WART), blaze = of(Items.BLAZE_POWDER);
        List<Part> parts = new ArrayList<>();
        parts.add(new Part("minecraft:glass_bottle", 1, bottle.total()));
        double materials = bottle.total();
        String name = "a plain potion";
        if (potion != null) {
            String key = BuiltInRegistries.POTION.getKey(potion) == null ? "" : BuiltInRegistries.POTION.getKey(potion).toString();
            String base = key.replace("long_", "").replace("strong_", "");
            Item in = INGREDIENT.get(base);
            if (in != null || !base.endsWith(":water")) {
                materials += wart.total() / 3.0;
                parts.add(new Part("minecraft:nether_wart", 1 / 3.0, wart.total()));
            }
            if (in != null) {
                Basis b = of(in);
                materials += b.total() / 3.0;
                parts.add(new Part(id(in), 1 / 3.0, b.total()));
            }
            Item mod = key.contains(":long_") ? Items.REDSTONE : key.contains(":strong_") ? Items.GLOWSTONE_DUST : null;
            if (mod != null) {
                Basis b = of(mod);
                materials += b.total() / 3.0;
                parts.add(new Part(id(mod), 1 / 3.0, b.total()));
            }
            name = key.substring(key.indexOf(':') + 1).replace('_', ' ');
        }
        if (form == Items.SPLASH_POTION || form == Items.LINGERING_POTION) {
            Basis gp = of(Items.GUNPOWDER);
            materials += gp.total() / 3.0;
            parts.add(new Part("minecraft:gunpowder", 1 / 3.0, gp.total()));
            if (form == Items.LINGERING_POTION) {
                Basis db = of(Items.DRAGON_BREATH);
                materials += db.total() / 3.0;
                parts.add(new Part("minecraft:dragon_breath", 1 / 3.0, db.total()));
            }
        }
        // A blaze powder fires twenty brews of three.
        double fuel = blaze.total() / 60.0;
        double labour = dayWage(StationTask.BREW) / (BREWS_A_DAY * 3);
        return new Basis(id(form) + (potion == null ? "" : "/" + name), materials, fuel, labour, 0, labour * SHOP_OVERHEAD,
            "brewed by a brewer (" + name + "), three a stand", parts, 3);
    }

    // ------------------------------------------------------------------ words

    static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    @Nullable
    static Item itemOf(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        return BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    static String shortName(String id) {
        Item it = itemOf(id);
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        if (it == null || it == Items.AIR) return path.replace('_', ' ');
        String name = new ItemStack(it).getHoverName().getString();
        return (name.startsWith("item.") || name.startsWith("block.") ? path.replace('_', ' ') : name).toLowerCase(Locale.ROOT);
    }

    static String trimN(double n) {
        if (Math.abs(n - Math.rint(n)) < 1e-6) return Long.toString(Math.round(n));
        return String.format(Locale.ROOT, "%.2f", n).replaceAll("0+$", "");
    }

    /** "a farmer", "an enchanter": a trade in the words of the books. */
    static final class JobWorthWords {
        static String a(StationTask t) {
            String w = t.title.toLowerCase(Locale.ROOT);
            return ("aeiou".indexOf(w.charAt(0)) >= 0 ? "an " : "a ") + w;
        }
    }
}
