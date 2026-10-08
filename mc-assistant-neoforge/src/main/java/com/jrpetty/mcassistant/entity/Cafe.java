package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The café and the shop.
 * <ul>
 * <li><b>The cook</b> (Stone Age, at the café) cooks what the farms, the pens and the
 *     boats bring in — baked potatoes, roast meat and fish, bread, cookies, pumpkin pie —
 *     and makes drinks in bottles: apple cider, berry juice, honey tea, hot cocoa, melon
 *     and carrot juice, each with a little good in it. Whatever is ready goes out on the
 *     café's counter.</li>
 * <li><b>The shopkeeper</b> (Iron Age, at the shop) sets out what the crafts have made —
 *     tools and armour from the smithy, potions from the brewery, beds, rugs and banners
 *     from the tailor, books and enchanted things from the library, honey from the hives.</li>
 * </ul>
 * Each counter has one thing on it in a frame and a price tag in front. Right-click a
 * counter to buy what is on it with village coin (crouch to ask the price first); folk
 * drop in to the café on their break and spend their wages there.
 * <p>Both know their trade the whole way: each makes what it sells by the game's own recipes from
 * what the stores hold (Bench: logs to planks to sticks to a pick, wheat to bread, cane to sugar to a
 * pie), and keeps its books (Stockroom): what sells, how many to keep of each, what to make next,
 * what is slow and marked down, and what it is short of.
 */
public final class Cafe {

    private Cafe() {}

    // ------------------------------------------------------------------ the drinks

    /** A drink the café makes: what it is called, what it is made from (and how many of
     *  it a batch of three takes), its colour, and the good it does. */
    public record Drink(String id, String name, Item from, int needs, int colour, Holder<MobEffect> effect, int ticks) {}

    public static final List<Drink> DRINKS = List.of(
        new Drink("apple_cider", "Apple Cider", Items.APPLE, 2, 0xE0A030, MobEffects.ABSORPTION, 1200),
        new Drink("berry_juice", "Berry Juice", Items.SWEET_BERRIES, 6, 0xB0203A, MobEffects.JUMP, 1200),
        new Drink("honey_tea", "Honey Tea", Items.HONEY_BOTTLE, 1, 0xE8B830, MobEffects.REGENERATION, 200),
        new Drink("hot_cocoa", "Hot Cocoa", Items.COCOA_BEANS, 2, 0x6B3A1E, MobEffects.SATURATION, 1),
        new Drink("melon_juice", "Melon Juice", Items.MELON_SLICE, 4, 0xF05A5A, MobEffects.MOVEMENT_SPEED, 1200),
        new Drink("carrot_juice", "Carrot Juice", Items.CARROT, 4, 0xF08A20, MobEffects.NIGHT_VISION, 1800));

    /** One of a drink, in its bottle. */
    public static ItemStack drink(Drink d) {
        ItemStack s = new ItemStack(Items.POTION);
        s.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), Optional.of(d.colour()),
            List.of(new MobEffectInstance(d.effect(), d.ticks(), 0))));
        s.set(DataComponents.ITEM_NAME, Component.literal(d.name()));
        CompoundTag t = new CompoundTag();
        t.putString("mca_drink", d.id());
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        return s;
    }

    /** Which of the café's drinks this is, or null. */
    @Nullable
    public static String drinkOf(ItemStack s) {
        if (!s.is(Items.POTION)) return null;
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        if (cd == null) return null;
        CompoundTag t = cd.copyTag();
        return t.contains("mca_drink") ? t.getString("mca_drink") : null;
    }

    public static boolean isDrink(ItemStack s) {
        return drinkOf(s) != null;
    }

    @Nullable
    public static Drink drinkFor(String id) {
        for (Drink d : DRINKS) if (d.id().equals(id)) return d;
        return null;
    }

    // ------------------------------------------------------------------ the cook

    private static volatile List<Stockroom.Ware> MENU_WARES;
    private static volatile List<Stockroom.Ware> SHOP_WARES;

    /**
     * The café's menu, and how much of each the cook usually keeps ready (Stockroom moves it with what
     * sells): its six drinks, three to a batch, of its own recipes (the fruit, and three glass bottles,
     * blown from the smelter's glass if the stores have none); then the kitchen's dishes, by the game's
     * own recipes, the whole way from what the stores hold (Bench) — potatoes baked and the pens' and
     * the boats' meat and fish roasted in the café's smoker (or a furnace, or the tavern's hearth), bread
     * of the farmers' wheat, cookies of wheat and cocoa, pumpkin pie of a pumpkin, an egg and sugar
     * pressed from cane, and a cake of the rancher's milk (the buckets go back), eggs, sugar and wheat.
     * Never the last twelve of a seed crop (Bench). Bread, potatoes and the roasts are the larder's own,
     * and never kept under the usual.
     */
    public static List<Stockroom.Ware> cafeWares() {
        List<Stockroom.Ware> m = MENU_WARES;
        if (m != null) return m;
        List<Stockroom.Ware> out = new ArrayList<>();
        for (Drink d : DRINKS) {
            ItemStack one = drink(d);
            out.add(new Stockroom.Ware("drink/" + d.id(), s -> d.id().equals(drinkOf(s)), one, null,
                new Stockroom.Own(List.of(Bench.Want.of(d.from(), d.needs()), Bench.Want.of(Items.GLASS_BOTTLE, 3)), one.copyWithCount(3)),
                3, 1, 12, 3, false));
        }
        out.add(Stockroom.ware(Items.BAKED_POTATO, 16, 4, 48, 4, true));
        for (Item roast : new Item[]{ Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_MUTTON, Items.COOKED_CHICKEN,
                Items.COOKED_COD, Items.COOKED_SALMON }) {
            out.add(Stockroom.ware(roast, 6, 2, 24, 2, true));
        }
        out.add(Stockroom.ware(Items.COOKIE, 16, 8, 48, 8, false));
        out.add(Stockroom.ware(Items.PUMPKIN_PIE, 4, 1, 12, 1, false));
        out.add(Stockroom.ware(Items.BREAD, 12, 4, 48, 1, true));
        out.add(Stockroom.ware(Items.CAKE, 2, 1, 4, 1, false));
        out.addAll(Kitchen.cafeWares());                     // [kitchen] the fish pie, the tea; the lunches, cheese and cakes on its books
        MENU_WARES = m = List.copyOf(out);
        return m;
    }

    /**
     * The cook's work: of the menu, whatever the café's shelf is emptiest of against what it means to
     * keep (the drinks and dishes that sell, kept two and a half days deep: Stockroom) that the stores
     * can run to, the whole way through; then set out on the counter. Returns what it made, or null.
     */
    @Nullable
    public static String cook(ServerLevel level, Villages.Village v) {
        return cook(level, v, null);
    }

    @Nullable
    public static String cook(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity f) {
        VillageFolkEntity hand = f != null ? f : Stockroom.keeperOf(v.id(), Stockroom.Seller.CAFE);
        Stockroom.Made made = Stockroom.restock(level, v, Stockroom.Seller.CAFE, cafeWares(), hand);
        if (made == null) {
            return dress(level, v, "cafe") > 0 ? "the café counter set out" : null;
        }
        dress(level, v, "cafe");
        return name(made.out());
    }

    private static String name(ItemStack s) {
        String n = s.getHoverName().getString();
        if (isDrink(s)) return (s.getCount() > 1 ? s.getCount() + " bottles of " : "a bottle of ") + n;
        return Bench.words(s.getItem(), s.getCount());
    }

    // ------------------------------------------------------------------ the shopkeeper

    /**
     * What a house wants and a hand can make at the shop's bench, by the game's own recipes, the whole
     * way from what the stores hold (Bench: logs sawn to planks, planks to sticks and slabs, an ingot
     * beaten to nuggets, a log burnt to charcoal for torches when there is no coal): chests and barrels
     * for a household's things, torches and candles for its evenings, a fishing rod, a pot for the
     * windowsill, a painting and a frame for the wall, a lantern, a bucket, shears, the plain stone
     * tools and blade a folk whose own wore out comes in for, and — of iron the village can spare — the
     * iron tools. Each with how many the shop usually keeps, the fewest and the most (Stockroom moves it
     * with what sells; the iron tools are not kept at all once they stop selling). Never out of the
     * builders' timber and stone, nor the smith's iron, nor anything the age is putting by (Bench).
     */
    public static List<Stockroom.Ware> shopWares() {
        List<Stockroom.Ware> m = SHOP_WARES;
        if (m != null) return m;
        List<Stockroom.Ware> out = new ArrayList<>();
        out.add(Stockroom.ware(Items.TORCH, 24, 8, 64, 8, true));
        out.add(Stockroom.ware(Items.CHEST, 2, 1, 6, 1, false));
        out.add(Stockroom.ware(Items.BARREL, 2, 1, 6, 1, false));
        out.add(new Stockroom.Ware("candle", s -> s.is(ItemTags.CANDLES), new ItemStack(Items.CANDLE), Items.CANDLE, null, 4, 1, 12, 1, false));
        out.add(Stockroom.ware(Items.FISHING_ROD, 1, 1, 4, 1, false));
        out.add(Stockroom.ware(Items.FLOWER_POT, 2, 1, 8, 1, false));
        out.add(Stockroom.ware(Items.PAINTING, 1, 1, 4, 1, false));
        out.add(Stockroom.ware(Items.ITEM_FRAME, 1, 1, 4, 1, false));
        out.add(Stockroom.ware(Items.LANTERN, 2, 1, 8, 1, false));
        // Glass for a household's windows (Luxuries): sixteen panes cut from six of the smelter's glass.
        out.add(Stockroom.ware(Items.GLASS_PANE, 8, 0, 24, 16, false));
        out.add(Stockroom.ware(Items.BUCKET, 1, 1, 4, 1, false));
        out.add(Stockroom.ware(Items.SHEARS, 1, 1, 3, 1, false));
        // [diver] The diver's kelp blocks for a player's furnace, and a turtle helmet of the beach's scutes (Divers).
        out.add(Stockroom.ware(Items.DRIED_KELP_BLOCK, 4, 0, 16, 1, false));
        out.add(Stockroom.ware(Items.TURTLE_HELMET, 1, 0, 1, 1, false));
        for (Item tool : new Item[]{ Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_HOE, Items.STONE_SHOVEL, Items.STONE_SWORD }) {
            out.add(Stockroom.ware(tool, 1, 1, 4, 1, false));
        }
        for (Item tool : new Item[]{ Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL, Items.IRON_HOE }) {
            out.add(Stockroom.ware(tool, 1, 0, 3, 1, false));
        }
        SHOP_WARES = m = List.copyOf(out);
        return m;
    }

    /** The household goods the shop keeps on its shelves (for the board, the talk and the tests). */
    public static boolean houseware(ItemStack s) {
        for (Stockroom.Ware w : shopWares()) if (w.is().test(s)) return true;
        return s.is(ItemTags.BEDS) || s.is(ItemTags.WOOL_CARPETS);
    }

    /**
     * The shopkeeper's work: of the household goods, whatever the shelves are emptiest of against what
     * the shop means to keep (Stockroom: what sells, kept two and a half days deep) that the stores can
     * spare the makings of, made the whole way at the bench; then the counters set out afresh from what
     * the crafts and the bench have put in the stores. Returns what it did, or null if there was nothing to do.
     */
    @Nullable
    public static String keepShop(ServerLevel level, Villages.Village v) {
        return keepShop(level, v, null);
    }

    @Nullable
    public static String keepShop(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity f) {
        VillageFolkEntity hand = f != null ? f : Stockroom.keeperOf(v.id(), Stockroom.Seller.SHOP);
        // The shop's workshop (Workshop): its round (its staff, its order book, a hand taken on, the watch fitted
        // out), and a piece off its order book — the town's needs, the shelves, what sells — at what the age
        // lets it make. Its hands make as the keeper does; the counter is the keeper's.
        Workshop.tick(level, v);
        Stockroom.Made made = Stockroom.restock(level, v, Stockroom.Seller.SHOP, Workshop.wares(level, v.id()), hand);
        if (made != null) Workshop.made(level, v, hand, made);
        if (made != null) Store.fromTheBench(level, v, made.out());        // [econ-store] made to sell: into the stockroom
        if (Workshop.isHand(hand)) return made != null ? name(made.out()) + " for the shop" : null;
        int set = dress(level, v, "shop");
        return made != null ? name(made.out()) + " for the shelves" : set > 0 ? "the shop counter set out" : null;
    }
    // ------------------------------------------------------------------ the counters

    private static final Map<Ledger.Building, List<BlockPos>> COUNTERS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        COUNTERS.clear();
        Stockroom.resetForTests();
    }

    /** A building's counters: the casks in its drawing with nothing drawn on top of them
     *  (the casks under the shelves are the stock, not the counter). */
    public static List<BlockPos> counters(Ledger.Building b) {
        return COUNTERS.computeIfAbsent(b, k -> {
            List<BuildGoal.Placement> plan = BuildGoal.plan(k.structure(), k.anchor(), k.facing(), 13);
            Set<BlockPos> planned = new HashSet<>();
            for (BuildGoal.Placement p : plan) if (p.part() != BuildGoal.Part.CLEAR) planned.add(p.pos());
            List<BlockPos> out = new ArrayList<>();
            for (BuildGoal.Placement p : plan) {
                if (p.part() == BuildGoal.Part.BARREL && !planned.contains(p.pos().above())) out.add(p.pos());
            }
            // The counter proper first (low, and nearest the door), then the shelves behind it.
            BlockPos door = TownLife.fittings(k).door();
            BlockPos toward = door != null ? door : k.anchor();
            out.sort(java.util.Comparator.<BlockPos>comparingInt(BlockPos::getY)
                .thenComparingDouble(p -> p.distSqr(toward))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
            return List.copyOf(out);
        });
    }

    /** Is the café (or the shop) open: is there a cook (a shopkeeper) in the village? */
    static boolean open(UUID village, String structure) {
        AssistantEntity.StationTask who = structure.equals("cafe") ? AssistantEntity.StationTask.COOK : AssistantEntity.StationTask.SHOP;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == who) return true;
        return false;
    }

    /** Set out every café (or shop) the village has, from its stores. Returns how many
     *  counters changed. */
    static int dress(ServerLevel level, Villages.Village v, String structure) {
        if (structure.equals("shop") && StoreFloor.dresses(v.id())) return StoreFloor.dress(level, v);   // [econ-store]
        int changed = 0;
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            if (!b.structure().equals(structure) || !level.isLoaded(b.anchor())) continue;
            List<ItemStack> goods = open(v.id(), structure)
                ? (structure.equals("cafe") ? menuGoods(level, v.id()) : shopGoods(level, v.id())) : List.of();
            changed += setOut(level, v, b, goods, false);
        }
        return changed;
    }

    /**
     * These goods on this building's counters, one to a counter, with a price tag in front
     * of each, the tags for nothing (the showcase and the tests). Returns how many counters changed.
     */
    public static int setOut(ServerLevel level, UUID village, Ledger.Building b, List<ItemStack> goods) {
        return setOut(level, village, null, b, goods, true);
    }

    /** As setOut, a new price tag paid out of the village's stores (a sign, or two planks). */
    static int setOut(ServerLevel level, Villages.Village v, Ledger.Building b, List<ItemStack> goods, boolean free) {
        return setOut(level, v.id(), v, b, goods, free);
    }

    private static int setOut(ServerLevel level, UUID village, @Nullable Villages.Village v, Ledger.Building b,
                              List<ItemStack> goods, boolean free) {
        List<BlockPos> tops = counters(b);
        TownLife.Fittings f = TownLife.fittings(b);
        int changed = 0;
        for (int k = 0; k < tops.size(); k++) {
            BlockPos at = tops.get(k);
            if (!level.getBlockState(at).is(Blocks.BARREL)) continue;          // not built yet
            ItemStack want = k < goods.size() ? goods.get(k) : ItemStack.EMPTY;
            if (TownLife.frameOn(level, at.above(), want, true)) changed++;
            priceTag(level, village, v, at, f.door(), want, free);
        }
        return changed;
    }

    /** A price tag on the front of a counter, the side that faces the door: a sign out of the
     *  stores (or two planks), unless {@code free}; no tag till there is one. */
    private static void priceTag(ServerLevel level, UUID village, @Nullable Villages.Village v, BlockPos counter,
                                 @Nullable BlockPos door, ItemStack shown, boolean free) {
        if (door == null) return;
        int dx = door.getX() - counter.getX(), dz = door.getZ() - counter.getZ();
        Direction front = Math.abs(dz) >= Math.abs(dx)
            ? (dz >= 0 ? Direction.SOUTH : Direction.NORTH)
            : (dx >= 0 ? Direction.EAST : Direction.WEST);
        BlockPos at = counter.relative(front);
        BlockState there = level.getBlockState(at);
        if (!(there.getBlock() instanceof WallSignBlock)) {
            if (shown.isEmpty() || !there.isAir()) return;
            if (!free && (v == null || !Crafts.sign(level, v))) return;
            level.setBlock(at, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, front), 3);
        }
        if (level.getBlockEntity(at) instanceof SignBlockEntity sign) TownLife.write(sign, Market.tagLines(level, village, shown));
    }

    /** What the café has ready, drinks first: one of each, up to a counterful. */
    public static List<ItemStack> menuGoods(ServerLevel level, UUID village) {
        // What the village can spare (Budget): no bread off the counter while the larder is low.
        return Cuisine.ownFirst(village, fromStores(level, village, s -> (isDrink(s) || MENU.stream().anyMatch(s::is)
            || Kitchen.onMenu(level, village, s)                                                   // [kitchen] the fish pie; cider, tea in season
            || Cuisine.isDish(s)) && Budget.spare(level, village, s) > 0, true));                  // [culture2] the town's dish, by the door
    }

    private static final List<Item> MENU = List.of(Items.BAKED_POTATO, Items.COOKIE, Items.PUMPKIN_PIE, Items.COOKED_BEEF,
        Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN, Items.COOKED_MUTTON, Items.COOKED_COD, Items.COOKED_SALMON,
        Items.BREAD, Items.HONEY_BOTTLE, Items.CAKE, Items.APPLE);

    /** The café's drinks the stores hold, one of each (what the tavern pours). */
    public static List<ItemStack> drinksInStores(ServerLevel level, UUID village) {
        return fromStores(level, village, Cafe::isDrink, true);
    }

    /** What the crafts have made for the shop: the best of it first. */
    public static List<ItemStack> shopGoods(ServerLevel level, UUID village) {
        // What the village can spare (Budget): never the guards' only swords or the miners' picks.
        return fromStores(level, village, s -> shopWorthy(s) && Budget.spare(level, village, s) > 0, false);
    }

    /** What a shop sells: the things the village's crafts make, whole and unworn — and, once it has
     *  more than its own hands need, its armour, arms and tools of every kind (Budget). */
    public static boolean shopWorthy(ItemStack s) {
        if (s.isDamaged() || isDrink(s) || Budget.goodFor(s) == null) return false;
        if (s.is(Items.POTION)) return true;
        if (Budget.kitOf(s) != null && Prices.each(s.getItem()) >= 2.0) return true;
        if (FireworksMaker.elytra(s)) return true;                    // [fireworks] the maker's elytra rockets, by the eight
        if (houseware(s)) return true;
        // The workshop's tools, arms and armour (Workshop), whatever their price: the village keeps its own first (Budget).
        if (Budget.kitOf(s) != null && Workshop.wareFor(Stockroom.key(s)) != null) return true;
        if (com.jrpetty.mcassistant.item.Garment.of(s) != null) return true;     // [fashion] the tailor's garments, new and second-hand
        return s.isEnchanted() || s.is(ItemTags.BEDS) || s.is(ItemTags.WOOL_CARPETS) || s.is(ItemTags.BANNERS)
            || s.is(Items.BOOK) || s.is(Items.HONEY_BOTTLE) || s.is(Items.HONEYCOMB) || s.is(Items.SHEARS)
            || s.is(Items.BUCKET) || s.is(Items.IRON_PICKAXE) || s.is(Items.IRON_SWORD) || s.is(Items.IRON_AXE)
            || s.is(Items.IRON_SHOVEL) || s.is(Items.IRON_HOE) || s.is(Items.IRON_HELMET)
            || s.is(Items.IRON_CHESTPLATE) || s.is(Items.IRON_LEGGINGS) || s.is(Items.IRON_BOOTS);
    }

    private static List<ItemStack> fromStores(ServerLevel level, UUID village, Predicate<ItemStack> what, boolean drinksFirst) {
        List<ItemStack> out = new ArrayList<>();
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !what.test(s)) continue;
                boolean seen = false;
                for (ItemStack o : out) if (ItemStack.isSameItemSameComponents(o, s)) { seen = true; break; }
                if (!seen) out.add(s.copyWithCount(1));
            }
        }
        out.sort((a, c) -> Integer.compare(rank(c, drinksFirst), rank(a, drinksFirst)));
        return out.size() > 8 ? out.subList(0, 8) : out;
    }

    private static int rank(ItemStack s, boolean drinksFirst) {
        if (drinksFirst) return isDrink(s) || Cuisine.isDish(s) ? 2 : 1;   // [culture2] a dish is never cut off a full counter
        if (s.isEnchanted()) return 4;
        if (s.is(Items.POTION)) return 3;
        if (s.isDamageableItem()) return 2;
        return 1;
    }

    /** For the board: what is open, and what it has — "the café (cider, bread...), the shop (6 things)". */
    @Nullable
    public static String openLine(ServerLevel level, UUID village) {
        List<String> parts = new ArrayList<>();
        if (Villages.builtAt(village, "cafe") != null) {
            if (open(village, "cafe")) {
                List<String> menu = new ArrayList<>();
                for (ItemStack s : menuGoods(level, village)) {
                    menu.add(s.getHoverName().getString().toLowerCase(java.util.Locale.ROOT));
                    if (menu.size() >= 3) break;
                }
                parts.add("the café" + (menu.isEmpty() ? " (nothing ready yet)" : " (" + String.join(", ", menu) + ")"));
            } else {
                parts.add("the café wants a cook");
            }
        }
        if (Villages.builtAt(village, "shop") != null) {
            parts.add(open(village, "shop") ? "the shop (" + shopGoods(level, village).size() + " things for sale)" : "the shop wants a keeper");
        }
        if (Tavern.of(village) != null) parts.add("the tavern of an evening");
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    // ------------------------------------------------------------------ folk at the shop

    /** The tool a trade works with, as the shop would sell it. */
    @Nullable
    static Predicate<ItemStack> toolFor(AssistantEntity.StationTask t) {
        return switch (t) {
            case MINE -> s -> s.getItem() instanceof net.minecraft.world.item.PickaxeItem;
            case WOOD -> s -> s.getItem() instanceof net.minecraft.world.item.AxeItem;
            case GUARD -> s -> s.getItem() instanceof net.minecraft.world.item.SwordItem;
            case RANCH -> s -> s.is(Items.SHEARS);
            case FISH -> s -> s.is(Items.FISHING_ROD);
            case FARM -> s -> s.getItem() instanceof net.minecraft.world.item.HoeItem;
            default -> null;
        };
    }

    /** The plainest of the tool a trade works with, that the shop's bench makes: what a folk that found
     *  none on the shelves was after, in the shop's books (Stockroom). */
    @Nullable
    static Item plainToolFor(AssistantEntity.StationTask t) {
        return switch (t) {
            case MINE -> Items.STONE_PICKAXE;
            case WOOD -> Items.STONE_AXE;
            case GUARD -> Items.STONE_SWORD;
            case RANCH -> Items.SHEARS;
            case FISH -> Items.FISHING_ROD;
            case FARM -> Items.STONE_HOE;
            default -> null;
        };
    }

    /**
     * A folk at the shop, buying out of its own savings: the tool its trade wants if it has
     * none, or — doing well — something nice (a potion, a book, a banner, a rug). The coin goes
     * into the treasury. Returns what it bought, or null.
     */
    @Nullable
    public static String folkShops(ServerLevel level, Villages.Village v, VillageFolkEntity f, boolean forWork) {
        return folkShops(level, v, f, forWork, null);
    }

    /**
     * As folkShops, for the one thing it came in for ({@code after}: a luxury for its home, Luxuries), priced
     * by the price list as a player's would be; null for whatever takes its fancy. The same sale either way.
     */
    @Nullable
    public static String folkShops(ServerLevel level, Villages.Village v, VillageFolkEntity f, boolean forWork,
                                   @Nullable Predicate<ItemStack> after) {
        if (!open(v.id(), "shop")) return null;
        if (forWork && f.stationTask() == AssistantEntity.StationTask.GUARD) return null;   // [guard-kit] the watch's blade is issued, never bought
        Predicate<ItemStack> want;
        if (forWork) {
            want = toolFor(f.stationTask());
            if (want == null) return null;
        } else {
            want = after != null ? after : s -> shopWorthy(s) && !s.isDamageableItem();
        }
        List<ItemStack> goods = fromStores(level, v.id(), want, false);
        if (goods.isEmpty()) {
            // Come for the tool of its trade and found none: the shop's books count it as a sale it
            // had not got (Stockroom), and the shelf it was after is kept fuller from now on.
            Item plain = forWork ? plainToolFor(f.stationTask()) : null;
            if (plain != null) Stockroom.missed(level, v.id(), Stockroom.Seller.SHOP, new ItemStack(plain));
            return null;
        }
        ItemStack pick = forWork ? goods.get(0) : goods.get(f.getRandom().nextInt(goods.size()));
        // [econ-prices] One of it at the town's price (Purchases.priceEach: the town's price, an enchanted thing and a
        // master's work dearer, slow stock marked down, never under cost, the Thrifty a tenth off). It was a whole
        // lot's price for the one thing: four rugs' price for one rug. The tool of its trade it buys whatever the price;
        // a treat or a luxury it weighs against what it expects to pay, and leaves on the shelf if it is too dear.
        double each = Purchases.priceEach(level, v.id(), pick, f);
        Purchases.Need need = forWork ? Purchases.Need.TOOL : after != null || Luxuries.isLuxury(pick) ? Purchases.Need.LUXURY
            : Purchases.Need.TREAT;
        if (Purchases.decide(level, f, pick, each, need, 1) <= 0) return null;
        if (!Purchases.canPay(f, each)) return null;
        if (!TownWork.take(level, v, s -> ItemStack.isSameItemSameComponents(s, pick), 1)) return null;
        int price = Math.max(0, Purchases.charge(level, f, v.id(), each, false, pick, 1));
        PriceIndex.bought(v.id(), pick, 1);
        price += FolkSkills.tip(v.id(), AssistantEntity.StationTask.SHOP, f, Math.max(1, price));   // a Friendly Face at the counter
        Stockroom.sold(level, v.id(), Stockroom.Seller.SHOP, pick, 1, price);
        ItemStack bought = pick.copyWithCount(1);
        // A treat is its own: kept off the stores, and carried along when it moves house (Homes).
        if (!forWork) Homes.keepsake(bought, f);
        ItemStack left = f.insertItem(bought);
        if (!left.isEmpty()) Crafts.store(level, v, left);
        return bought.getHoverName().getString().toLowerCase(java.util.Locale.ROOT) + " for " + String.format(java.util.Locale.ROOT, "%.2f", each)
            + " coins";
    }

    // ------------------------------------------------------------------ folk at the café

    /**
     * A folk on its break buys a drink (or, failing that, a bite) at the café out of its
     * savings. Returns what it had, or null.
     */
    @Nullable
    public static String folkBuys(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (!open(v.id(), "cafe")) return null;
        List<ItemStack> menu = menuGoods(level, v.id());
        if (menu.isEmpty()) return null;
        ItemStack pick = menu.get(f.getRandom().nextInt(menu.size()));
        // [econ-prices] At the town's price, weighed against what it expects to pay: too dear and it does without.
        double each = Purchases.priceEach(level, v.id(), pick, f);
        if (Purchases.decide(level, f, pick, each, Purchases.Need.TREAT, 1) <= 0 || !Purchases.canPay(f, each)) return null;
        if (!TownWork.take(level, v, s -> ItemStack.isSameItemSameComponents(s, pick), 1)) return null;
        int price = Math.max(0, Purchases.charge(level, f, v.id(), each, false, pick, 1));
        PriceIndex.bought(v.id(), pick, 1);
        price += FolkSkills.tip(v.id(), AssistantEntity.StationTask.COOK, f, Math.max(1, price));   // a Friendly Face at the counter
        Stockroom.sold(level, v.id(), Stockroom.Seller.CAFE, pick, 1, price);
        // Had there and then: a drink does its little good, a bite fills it up.
        if (Kitchen.had(level, v, f, pick)) return pick.getHoverName().getString();   // [kitchen] cider or tea, the bottle back
        String drink = drinkOf(pick);
        if (drink != null) {
            Drink d = drinkFor(drink);
            if (d != null) f.addEffect(new MobEffectInstance(d.effect(), d.ticks(), 0));
            ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE);
            Crafts.store(level, v, bottle);
        } else {
            f.heal(2.0F);
        }
        return pick.getHoverName().getString();
    }
}
