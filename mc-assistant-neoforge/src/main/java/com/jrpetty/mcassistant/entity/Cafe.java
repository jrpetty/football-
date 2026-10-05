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

    /** So much of one thing (and never the last {@code reserve} of it: seed and the larder). */
    private record Need(Predicate<ItemStack> what, int n, int reserve) {}

    /** A dish or a drink: what it is, how many the café likes to have ready, what goes into
     *  a batch, whether a batch needs three bottles, and what a batch makes. */
    private record Recipe(Predicate<ItemStack> made, int keep, List<Need> needs, boolean bottled, ItemStack out) {}

    private static Need of(Item it, int n, int reserve) {
        return new Need(s -> s.is(it), n, reserve);
    }

    private static Predicate<ItemStack> is(Item it) {
        return s -> s.is(it);
    }

    private static List<Recipe> menu() {
        List<Recipe> m = new ArrayList<>();
        for (Drink d : DRINKS) {
            int reserve = d.from() == Items.CARROT ? 12 : 0;
            m.add(new Recipe(s -> d.id().equals(drinkOf(s)), 3, List.of(of(d.from(), d.needs(), reserve)), true,
                drink(d).copyWithCount(3)));
        }
        m.add(new Recipe(is(Items.BAKED_POTATO), 16, List.of(of(Items.POTATO, 4, 12)), false, new ItemStack(Items.BAKED_POTATO, 4)));
        m.add(new Recipe(is(Items.COOKED_BEEF), 6, List.of(of(Items.BEEF, 2, 0)), false, new ItemStack(Items.COOKED_BEEF, 2)));
        m.add(new Recipe(is(Items.COOKED_PORKCHOP), 6, List.of(of(Items.PORKCHOP, 2, 0)), false, new ItemStack(Items.COOKED_PORKCHOP, 2)));
        m.add(new Recipe(is(Items.COOKED_MUTTON), 6, List.of(of(Items.MUTTON, 2, 0)), false, new ItemStack(Items.COOKED_MUTTON, 2)));
        m.add(new Recipe(is(Items.COOKED_CHICKEN), 6, List.of(of(Items.CHICKEN, 2, 0)), false, new ItemStack(Items.COOKED_CHICKEN, 2)));
        m.add(new Recipe(is(Items.COOKED_COD), 6, List.of(of(Items.COD, 2, 0)), false, new ItemStack(Items.COOKED_COD, 2)));
        m.add(new Recipe(is(Items.COOKED_SALMON), 6, List.of(of(Items.SALMON, 2, 0)), false, new ItemStack(Items.COOKED_SALMON, 2)));
        m.add(new Recipe(is(Items.COOKIE), 16, List.of(of(Items.WHEAT, 2, 12), of(Items.COCOA_BEANS, 1, 0)), false,
            new ItemStack(Items.COOKIE, 8)));
        m.add(new Recipe(is(Items.PUMPKIN_PIE), 4, List.of(of(Items.PUMPKIN, 1, 0),
            new Need(s -> s.is(Items.SUGAR) || s.is(Items.SUGAR_CANE), 1, 0), of(Items.EGG, 1, 0)), false,
            new ItemStack(Items.PUMPKIN_PIE)));
        m.add(new Recipe(is(Items.BREAD), 12, List.of(of(Items.WHEAT, 3, 12)), false, new ItemStack(Items.BREAD)));
        // A cake: the rancher's milk and eggs, the farmers' wheat and cane (the buckets go back).
        m.add(new Recipe(is(Items.CAKE), 2, List.of(of(Items.MILK_BUCKET, 3, 0),
            new Need(s -> s.is(Items.SUGAR) || s.is(Items.SUGAR_CANE), 2, 0), of(Items.EGG, 1, 0), of(Items.WHEAT, 3, 12)), false,
            new ItemStack(Items.CAKE)));
        return m;
    }

    /** Can the stores stand this batch? */
    private static boolean canMake(ServerLevel level, Villages.Village v, Recipe r) {
        for (Need n : r.needs()) {
            if (Crafts.stock(level, v, n.what()) < n.n() + n.reserve()) return false;
        }
        return !r.bottled() || bottles(level, v);
    }

    private static boolean bottles(ServerLevel level, Villages.Village v) {
        return Crafts.stock(level, v, is(Items.GLASS_BOTTLE)) >= 3 || Crafts.stock(level, v, is(Items.GLASS)) >= 3;
    }

    /**
     * The cook's work: whatever the café is shortest of, against what it likes to have ready,
     * and that the stores have the makings of — then set out on the counter. Returns what it
     * made, or null.
     */
    @Nullable
    public static String cook(ServerLevel level, Villages.Village v) {
        Recipe best = null;
        double bestFill = 1.0;
        for (Recipe r : menu()) {
            double fill = Crafts.stock(level, v, r.made()) / (double) r.keep();
            if (fill >= bestFill || !canMake(level, v, r)) continue;
            best = r;
            bestFill = fill;
        }
        if (best == null) {
            return dress(level, v, "cafe") > 0 ? "the café counter set out" : null;
        }
        for (Need n : best.needs()) {
            if (!Crafts.take(level, v, n.what(), n.n())) return null;
        }
        if (best.bottled() && !Crafts.take(level, v, is(Items.GLASS_BOTTLE), 3)) Crafts.take(level, v, is(Items.GLASS), 3);
        if (best.out().is(Items.CAKE)) Crafts.store(level, v, new ItemStack(Items.BUCKET, 3));   // the milk's buckets, back
        ItemStack out = best.out().copy();
        if (out.getMaxStackSize() == 1) {
            for (int i = 0; i < out.getCount(); i++) Crafts.store(level, v, out.copyWithCount(1));
        } else {
            Crafts.store(level, v, out.copy());
        }
        dress(level, v, "cafe");
        return name(out);
    }

    private static String name(ItemStack s) {
        String n = s.getHoverName().getString();
        if (isDrink(s)) return (s.getCount() > 1 ? s.getCount() + " bottles of " : "a bottle of ") + n;
        return (s.getCount() > 1 ? s.getCount() + " " : "a ") + n.toLowerCase();
    }

    // ------------------------------------------------------------------ the shopkeeper

    /**
     * What a house wants and a hand can make at the shop's bench, out of what the stores can spare:
     * chests and barrels for a household's things, torches and candles for its evenings, a fishing
     * rod, a pot for the windowsill, a painting and a frame for the wall, a bucket, and the plain
     * stone tools a folk whose own wore out comes in for. Each kept to a few on the shelves; never
     * out of the builders' timber and stone (the reserves), nor the smith's iron while it is short.
     */
    private static List<Recipe> wares() {
        List<Recipe> m = new ArrayList<>();
        Predicate<ItemStack> planks = s -> s.is(ItemTags.PLANKS);
        Predicate<ItemStack> fuel = s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
        Predicate<ItemStack> wool = s -> s.is(ItemTags.WOOL);
        Predicate<ItemStack> cobble = s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE);
        m.add(new Recipe(is(Items.TORCH), 24, List.of(new Need(fuel, 2, 8), new Need(planks, 1, 48)), false, new ItemStack(Items.TORCH, 8)));
        m.add(new Recipe(is(Items.CHEST), 2, List.of(new Need(planks, 8, 64)), false, new ItemStack(Items.CHEST)));
        m.add(new Recipe(is(Items.BARREL), 2, List.of(new Need(planks, 7, 64)), false, new ItemStack(Items.BARREL)));
        m.add(new Recipe(s -> s.is(ItemTags.CANDLES), 4, List.of(of(Items.STRING, 1, 2), of(Items.HONEYCOMB, 1, 0)), false,
            new ItemStack(Items.CANDLE)));
        m.add(new Recipe(is(Items.FISHING_ROD), 1, List.of(of(Items.STRING, 2, 2), new Need(planks, 2, 48)), false,
            new ItemStack(Items.FISHING_ROD)));
        m.add(new Recipe(is(Items.FLOWER_POT), 2, List.of(of(Items.BRICK, 3, 0)), false, new ItemStack(Items.FLOWER_POT)));
        m.add(new Recipe(is(Items.PAINTING), 1, List.of(new Need(planks, 4, 64), new Need(wool, 1, 3)), false, new ItemStack(Items.PAINTING)));
        m.add(new Recipe(is(Items.ITEM_FRAME), 1, List.of(new Need(planks, 4, 64), of(Items.LEATHER, 1, 2)), false,
            new ItemStack(Items.ITEM_FRAME)));
        m.add(new Recipe(is(Items.LANTERN), 2, List.of(of(Items.IRON_NUGGET, 8, 0), of(Items.TORCH, 1, 8)), false,
            new ItemStack(Items.LANTERN)));
        m.add(new Recipe(is(Items.BUCKET), 1, List.of(of(Items.IRON_INGOT, 3, 12)), false, new ItemStack(Items.BUCKET)));
        m.add(new Recipe(is(Items.STONE_PICKAXE), 1, List.of(new Need(cobble, 3, 48), new Need(planks, 1, 48)), false,
            new ItemStack(Items.STONE_PICKAXE)));
        m.add(new Recipe(is(Items.STONE_AXE), 1, List.of(new Need(cobble, 3, 48), new Need(planks, 1, 48)), false,
            new ItemStack(Items.STONE_AXE)));
        m.add(new Recipe(is(Items.STONE_HOE), 1, List.of(new Need(cobble, 2, 48), new Need(planks, 1, 48)), false,
            new ItemStack(Items.STONE_HOE)));
        m.add(new Recipe(is(Items.STONE_SHOVEL), 1, List.of(new Need(cobble, 1, 48), new Need(planks, 1, 48)), false,
            new ItemStack(Items.STONE_SHOVEL)));
        return m;
    }

    /** The household goods the shop keeps on its shelves (for the board, the talk and the tests). */
    public static boolean houseware(ItemStack s) {
        for (Recipe r : wares()) if (r.made().test(s)) return true;
        return s.is(ItemTags.BEDS) || s.is(ItemTags.WOOL_CARPETS);
    }

    /**
     * The shopkeeper's work: whatever household good the shelves are shortest of that the stores can
     * spare the makings of, made up at the bench; then the counters set out afresh from what the
     * crafts and the bench have put in the stores. Returns what it did, or null if there was nothing to do.
     */
    @Nullable
    public static String keepShop(ServerLevel level, Villages.Village v) {
        Recipe best = null;
        double bestFill = 1.0;
        for (Recipe r : wares()) {
            double fill = Crafts.stock(level, v, r.made()) / (double) r.keep();
            if (fill >= bestFill || !canMake(level, v, r)) continue;
            best = r;
            bestFill = fill;
        }
        String made = null;
        if (best != null) {
            boolean all = true;
            for (Need n : best.needs()) {
                if (!Crafts.take(level, v, n.what(), n.n())) { all = false; break; }
            }
            if (all) {
                ItemStack out = best.out().copy();
                if (out.getMaxStackSize() == 1) {
                    for (int i = 0; i < out.getCount(); i++) Crafts.store(level, v, out.copyWithCount(1));
                } else {
                    Crafts.store(level, v, out.copy());
                }
                made = name(out) + " for the shelves";
            }
        }
        int set = dress(level, v, "shop");
        return made != null ? made : set > 0 ? "the shop counter set out" : null;
    }

    // ------------------------------------------------------------------ the counters

    private static final Map<Ledger.Building, List<BlockPos>> COUNTERS = new ConcurrentHashMap<>();

    public static void resetForTests() { COUNTERS.clear(); }

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
        return fromStores(level, village, s -> (isDrink(s) || MENU.stream().anyMatch(s::is)) && Budget.spare(level, village, s) > 0, true);
    }

    private static final List<Item> MENU = List.of(Items.BAKED_POTATO, Items.COOKIE, Items.PUMPKIN_PIE, Items.COOKED_BEEF,
        Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN, Items.COOKED_MUTTON, Items.COOKED_COD, Items.COOKED_SALMON,
        Items.BREAD, Items.HONEY_BOTTLE, Items.CAKE, Items.APPLE);

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
        if (houseware(s)) return true;
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
        if (drinksFirst) return isDrink(s) ? 2 : 1;
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

    /**
     * A folk at the shop, buying out of its own savings: the tool its trade wants if it has
     * none, or — doing well — something nice (a potion, a book, a banner, a rug). The coin goes
     * into the treasury. Returns what it bought, or null.
     */
    @Nullable
    public static String folkShops(ServerLevel level, Villages.Village v, VillageFolkEntity f, boolean forWork) {
        if (!open(v.id(), "shop")) return null;
        Predicate<ItemStack> want;
        if (forWork) {
            want = toolFor(f.stationTask());
            if (want == null) return null;
        } else {
            want = s -> shopWorthy(s) && !s.isDamageableItem();
        }
        List<ItemStack> goods = fromStores(level, v.id(), want, false);
        if (goods.isEmpty()) return null;
        ItemStack pick = forWork ? goods.get(0) : goods.get(f.getRandom().nextInt(goods.size()));
        Market.Good g = Market.goodFor(pick);
        int price = g == null ? 3 : Market.sellPrice(g, Market.stock(level, v.id(), s -> ItemStack.isSameItemSameComponents(s, pick)), false);
        price = Math.max(1, pick.isEnchanted() ? price * 3 : price);
        if (f.purse() < price) return null;
        if (!TownWork.take(level, v, s -> ItemStack.isSameItemSameComponents(s, pick), 1)) return null;
        f.spend(price);
        Ledger.addCoins(v.id(), price);
        ItemStack bought = pick.copyWithCount(1);
        // A treat is its own: kept off the stores, and carried along when it moves house (Homes).
        if (!forWork) Homes.keepsake(bought, f);
        ItemStack left = f.insertItem(bought);
        if (!left.isEmpty()) Crafts.store(level, v, left);
        return bought.getHoverName().getString().toLowerCase(java.util.Locale.ROOT) + " for " + price + (price == 1 ? " coin" : " coins");
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
        Market.Good g = Market.goodFor(pick);
        int price = g == null ? 1 : Market.sellPrice(g, Market.stock(level, v.id(), s -> ItemStack.isSameItemSameComponents(s, pick)), false);
        price = Math.max(1, price / Math.max(1, g == null ? 1 : g.bundle()));
        if (f.purse() < price) return null;
        if (!TownWork.take(level, v, s -> ItemStack.isSameItemSameComponents(s, pick), 1)) return null;
        f.spend(price);
        Ledger.addCoins(v.id(), price);
        // Had there and then: a drink does its little good, a bite fills it up.
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
