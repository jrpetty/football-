package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [shops-homes] A workshop's own chest, holding what its maker works with. The smithy's chest has the iron, the coal,
 * the sticks and a spare pick and axe; the tailor's the wool, the string, the leather and the dyes; the café's the
 * wheat, the sugar, the eggs and the cocoa; the fletcher's the sticks, the flint and the feathers; the brewery's the
 * bottles, the wart and the blaze powder; the library's the lapis, the books and the paper. Out on the plots, a
 * producer's own work chest keeps its kit by its harvest: a miner's torches and a spare pick, a farmer's seed and a
 * spare hoe, a woodcutter's spare axe, a fisher's spare rod (the couriers leave the kit when they empty it).
 * <ul>
 * <li><b>Filled from the stores, by the couriers.</b> Each minute the town looks at its workshops' chests; one
 *     running low of a making (under half of what it keeps) has it sent for: a delivery from the storehouse
 *     (StoreDeliveries), carried over by a courier, else by the maker itself once it has waited.</li>
 * <li><b>Worked from.</b> While a maker is at its work (Crafts.now, Fletchers.work), what it takes comes out of its
 *     own chest first, then the stores; what it counts on having counts its chest (Market.stock, Bench.held).</li>
 * <li><b>One truth.</b> The chest is the store of it: nothing is kept twice. Named for its workshop ("Smithy:
 *     makings"), it is never the stores' to reach into.</li>
 * </ul>
 */
public final class WorkStock {

    private WorkStock() {}

    /** One making a workshop keeps: what counts, one to show, how many it keeps. */
    record Making(Predicate<ItemStack> is, Item sample, int keep, String words) {}

    private static Making m(Item it, int keep) {
        return new Making(s -> s.is(it) && s.getComponentsPatch().isEmpty(), it, keep, Bench.words(it, keep));
    }

    private static Making tag(net.minecraft.tags.TagKey<Item> tag, Item sample, int keep, String words) {
        return new Making(s -> s.is(tag) && s.getComponentsPatch().isEmpty(), sample, keep, words);
    }

    /** What each trade's workshop keeps in its chest. Data: a trade's line is its makings. */
    static final Map<StationTask, List<Making>> MAKINGS = Map.of(
        StationTask.SMITH, List.of(m(Items.IRON_INGOT, 16), m(Items.COAL, 16), m(Items.STICK, 16), m(Items.IRON_PICKAXE, 1), m(Items.IRON_AXE, 1)),
        StationTask.TAILOR, List.of(tag(ItemTags.WOOL, Items.WHITE_WOOL, 16, "16 wool"), m(Items.STRING, 16), m(Items.LEATHER, 8),
            m(Items.RED_DYE, 4), m(Items.YELLOW_DYE, 4), m(Items.BLUE_DYE, 4)),
        StationTask.COOK, List.of(m(Items.WHEAT, 32), m(Items.SUGAR, 16), m(Items.EGG, 8), m(Items.COCOA_BEANS, 8)),
        StationTask.FLETCHER, List.of(m(Items.STICK, 32), m(Items.FLINT, 16), m(Items.FEATHER, 16)),
        StationTask.BREW, List.of(m(Items.GLASS_BOTTLE, 9), m(Items.NETHER_WART, 8), m(Items.BLAZE_POWDER, 4), m(Items.SUGAR, 4)),
        StationTask.ENCHANT, List.of(m(Items.LAPIS_LAZULI, 16), m(Items.BOOK, 8), m(Items.PAPER, 16)),
        // The shop's bench: what its keeper and hands make its wares of, kept apart from the wares on its shelves.
        StationTask.SHOP, List.of(tag(ItemTags.PLANKS, Items.OAK_PLANKS, 32, "32 planks"), m(Items.STICK, 16), m(Items.COBBLESTONE, 16),
            m(Items.IRON_INGOT, 8), m(Items.STRING, 8), m(Items.COAL, 8)));

    /** The kit a producer keeps in its own work chest out on its plot: never carried off by the couriers. */
    static final Map<StationTask, List<Making>> KIT = Map.of(
        StationTask.MINE, List.of(m(Items.TORCH, 32), new Making(s -> s.getItem() instanceof net.minecraft.world.item.PickaxeItem, Items.STONE_PICKAXE, 1, "a spare pick")),
        StationTask.FARM, List.of(m(Items.WHEAT_SEEDS, 16), new Making(s -> s.getItem() instanceof net.minecraft.world.item.HoeItem, Items.STONE_HOE, 1, "a spare hoe")),
        StationTask.WOOD, List.of(new Making(s -> s.getItem() instanceof net.minecraft.world.item.AxeItem, Items.STONE_AXE, 1, "a spare axe")),
        StationTask.FISH, List.of(m(Items.FISHING_ROD, 1)));

    /** How often the town looks at its workshops' chests (ticks), and the share of a making under which it sends. */
    static final long EVERY = 1200L;
    static final double LOW = 0.5;

    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LOOKED.clear();
        MAKER.remove();
    }

    // ------------------------------------------------------------------ where

    /** A trade's workshop chest in this town: the first chest of its building's drawing, standing. Null if none. */
    @Nullable
    public static BlockPos chestOf(ServerLevel level, UUID village, StationTask trade) {
        if (!MAKINGS.containsKey(trade)) return null;
        String building = VillageFolkEntity.buildingFor(trade);
        if (building == null) return null;
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (!b.structure().equals(building) || !level.isLoaded(b.anchor())) continue;
            for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
                if (p.part() == BuildGoal.Part.CHEST && level.getBlockState(p.pos()).getBlock() instanceof ChestBlock) return p.pos();
            }
        }
        return null;
    }

    /** Is this a workshop's makings chest (named so)? The shop's is no part of its wares (Store.containers). */
    public static boolean isMakings(net.minecraft.world.level.block.entity.BlockEntity be) {
        return be instanceof net.minecraft.world.Nameable n && n.getCustomName() != null && n.getCustomName().getString().endsWith(": makings");
    }

    /** A delivery put away in a workshop's chest: the shop's makings are booked to its till at the town's price. */
    static void delivered(ServerLevel level, Villages.Village v, BlockPos dest, ItemStack sample, int n) {
        if (n <= 0 || !dest.equals(chestOf(level, v.id(), StationTask.SHOP))) return;
        ShopBusiness.makings(v.id(), level.getDayTime() / 24000L, (int) Math.round(PriceIndex.each(level, v.id(), sample) * n));
    }

    /** The name a workshop's chest bears: "Smithy: makings". */
    static String nameFor(StationTask trade) {
        String b = VillageFolkEntity.buildingFor(trade);
        String place = b == null ? trade.title : Character.toUpperCase(b.charAt(0)) + b.substring(1);
        return place + ": makings";
    }

    /** Name it for its workshop (only one bearing the village's mark, or none): the stores never reach in again. */
    private static void claim(ServerLevel level, BlockPos at, StationTask trade) {
        BlockEntity be = level.getBlockEntity(at);
        if (!(be instanceof net.minecraft.world.Nameable n)) return;
        String want = nameFor(trade);
        Component name = n.getCustomName();
        if (name != null && name.getString().equals(want)) return;
        if (name != null && !ZoneChests.isVillageStore(be)) return;               // somebody else's: let be
        HomeStores.rename(be, want);
        // What was in it that is not the workshop's makings goes where it belongs: the shop's wares to its shelves, the
        // rest back to the stores (one truth: nothing kept twice, nothing lost).
        if (be instanceof Container c) {
            Villages.Village v = Villages.nearest(level, at, 96);
            if (v == null) return;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || keeps(trade, s)) continue;
                ItemStack left = trade == StationTask.SHOP ? Store.put(level, v.id(), s.copy()) : s.copy();
                if (!left.isEmpty()) TownWork.give(level, v, left);
                c.setItem(i, left);
            }
            c.setChanged();
        }
    }

    private static boolean keeps(StationTask trade, ItemStack s) {
        for (Making k : MAKINGS.getOrDefault(trade, List.of())) if (k.is().test(s)) return true;
        return false;
    }

    // ------------------------------------------------------------------ filled

    /**
     * Each minute: every workshop's chest named and looked at; a making under half of what it keeps is sent for from the
     * stores (what the stores can spare of it, up to what the chest keeps): a delivery for the couriers.
     */
    static void tick(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        if (now - LOOKED.getOrDefault(v.id(), -100000L) < EVERY && now >= LOOKED.getOrDefault(v.id(), 0L)) return;
        LOOKED.put(v.id(), now);
        java.util.EnumSet<StationTask> seen = java.util.EnumSet.noneOf(StationTask.class);
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !MAKINGS.containsKey(f.stationTask()) || !seen.add(f.stationTask())) continue;
            BlockPos chest = chestOf(level, v.id(), f.stationTask());
            if (chest == null) continue;
            claim(level, chest, f.stationTask());
            if (level.getBlockEntity(chest) instanceof Container c) sendFor(level, v, MAKINGS.get(f.stationTask()), nameFor(f.stationTask()), chest, c);
        }
        // Out on the plots: each producer's own work chest keeps its kit (a spare from what it carries, else sent for).
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !KIT.containsKey(f.stationTask())) continue;
            BlockPos chest = f.productionChest();
            if (chest == null || !level.isLoaded(chest) || !(level.getBlockEntity(chest) instanceof Container c)) continue;
            if (f.blockPosition().closerThan(chest, 4.0)) stowKit(f, c);
            sendFor(level, v, KIT.get(f.stationTask()), f.displayNameCap() + "'s work chest", chest, c);
        }
    }

    /** A maker's own delivery for its workshop's chest, fetched itself when no courier has taken it (StoreDeliveries). */
    static boolean fetchOwn(VillageFolkEntity f, ServerLevel level) {
        if (f.ownerId() == null || !MAKINGS.containsKey(f.stationTask())) return false;
        BlockPos chest = chestOf(level, f.ownerId(), f.stationTask());
        return chest != null && StoreDeliveries.fetchOwn(f, level, chest);
    }

    private static void sendFor(ServerLevel level, Villages.Village v, List<Making> makings, String destName, BlockPos chest, Container c) {
        for (Making k : makings) {
            int have = count(c, k.is());
            String key = deliveryKey(chest, k);
            int coming = StoreDeliveries.inFlight(v.id(), key, false);
            if (have + coming >= k.keep() * LOW) continue;
            // A move within the town, not a sale: never more than half of what the stores hold of it, so the larder's wheat
            // and the builders' sticks are not all carried off to one workshop.
            int spare = Market.stock(level, v.id(), k.is()) / 2;
            int n = Math.min(k.keep() - have - coming, spare);
            if (n <= 0) continue;
            StoreDeliveries.queueTo(level, v.id(), key, k.is(), new ItemStack(k.sample()), n, chest, destName);
        }
    }

    private static String deliveryKey(BlockPos chest, Making k) {
        return "work:" + chest.asLong() + ":" + Stockroom.key(new ItemStack(k.sample()));
    }

    private static int count(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && what.test(s)) n += s.getCount();
        }
        return n;
    }

    // ------------------------------------------------------------------ worked from

    /** The workshop chest of the maker at its work now (this thread), or null. */
    private static final ThreadLocal<Container> MAKER = new ThreadLocal<>();

    /** A maker sets to work: its own chest is where its makings come from first, till end(). */
    static void begin(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        BlockPos chest = chestOf(level, v.id(), f.stationTask());
        MAKER.set(chest != null && level.getBlockEntity(chest) instanceof Container c ? c : null);
    }

    static void end() {
        MAKER.remove();
    }

    /** How many of what matches the maker at work has in its own chest (0 when nobody is at work). */
    static int own(Predicate<ItemStack> what) {
        Container c = MAKER.get();
        return c == null ? 0 : count(c, what);
    }

    /** Take up to so many of what matches out of the working maker's chest; what was taken. */
    static List<ItemStack> takeOwn(Predicate<ItemStack> what, int n) {
        Container c = MAKER.get();
        List<ItemStack> out = new ArrayList<>();
        if (c == null || n <= 0) return out;
        for (int i = 0; i < c.getContainerSize() && n > 0; i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || !what.test(s)) continue;
            int k = Math.min(n, s.getCount());
            out.add(s.copyWithCount(k));
            s.shrink(k);
            if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
            n -= k;
        }
        if (!out.isEmpty()) c.setChanged();
        return out;
    }

    /** What was taken out of the maker's chest for a piece that came to nothing: back in. */
    static void putBackOwn(List<ItemStack> took) {
        Container c = MAKER.get();
        if (c == null) return;
        for (ItemStack s : took) Stacking.insert(c, s.copy());
        c.setChanged();
    }

    /** The working maker's chest, by item, onto what the stores hold (Bench.held: plain stacks). */
    static void addHeld(Map<Item, Integer> held) {
        Container c = MAKER.get();
        if (c == null) return;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || !s.getComponentsPatch().isEmpty() || s.isDamageableItem()) continue;
            held.merge(s.getItem(), s.getCount(), Integer::sum);
        }
    }

    // ------------------------------------------------------------------ the plots' kit

    /** Is this, in a producer's work chest out on its plot, its kit (left there by the couriers)? Within what it keeps. */
    public static boolean kit(ServerLevel level, BlockPos chest, ItemStack s) {
        VillageFolkEntity owner = ownerOf(level, chest);
        if (owner == null) return false;
        for (Making k : KIT.getOrDefault(owner.stationTask(), List.of())) if (k.is().test(s)) return true;
        return false;
    }

    @Nullable
    private static VillageFolkEntity ownerOf(ServerLevel level, BlockPos chest) {
        Villages.Village v = Villages.nearest(level, chest, 256);
        if (v == null) return null;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f && chest.equals(f.productionChest())) return f;
        }
        return null;
    }

    /**
     * A producer at its plot chest (its noon or dusk banking: PutAway): its kit topped up out of what it carries over
     * what it works with (torches past the stack it keeps, a second pick), so the morning finds a spare by the chest.
     * Returns how many went in.
     */
    public static int stowKit(VillageFolkEntity f, Container chest) {
        int put = 0;
        for (Making k : KIT.getOrDefault(f.stationTask(), List.of())) {
            int have = count(chest, k.is());
            int carried = f.countCarried(k.is());
            int spare = Math.min(k.keep() - have, carried - (k.keep() == 1 ? 1 : k.keep()));
            if (spare <= 0) continue;
            for (ItemStack s : f.getInventoryItems()) {
                if (spare <= 0) break;
                if (s.isEmpty() || !k.is().test(s)) continue;
                int n = Math.min(spare, s.getCount());
                ItemStack left = Stacking.insert(chest, s.copyWithCount(n));
                int in = n - left.getCount();
                s.shrink(in);
                spare -= in;
                put += in;
                if (in < n) break;
            }
        }
        if (put > 0) chest.setChanged();
        return put;
    }

    /** Tests: a trade's workshop chest, named and stocked as the round would (its sending for what is low). */
    public static BlockPos roundForTests(ServerLevel level, Villages.Village v, StationTask trade) {
        LOOKED.remove(v.id());
        tick(level, v);
        return chestOf(level, v.id(), trade);
    }

    /** Tests: what a trade's workshop keeps, as "item x keep". */
    public static List<String> makingsForTests(StationTask trade) {
        List<String> out = new ArrayList<>();
        for (Making k : MAKINGS.getOrDefault(trade, List.of())) out.add(k.words());
        return out;
    }
}
