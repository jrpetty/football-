package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The town store: the big store a town builds once it has outgrown its little shop, and the stock the shop and
 * the store keep of their own.
 * <ul>
 * <li><b>The building</b> (blueprints/store.txt): two storeys on a long lot by the square. The shop floor at the
 *     front with six counters of casks (each with its frame and price tag), the stockroom behind it (chests and
 *     casks), the crafters' workshop beside the stockroom (the bench, the furnace, the loom, the grindstone and
 *     the anvil), and up the stair the loft (the stockroom's overflow) and the stock keeper's office (its desk
 *     and the stock book on its lectern). It is wanted in the Iron Age by a town of forty, or a town of
 *     twenty-eight whose shop is selling a hundred things a week.</li>
 * <li><b>The old shop</b> keeps going as the store's branch: one business, one stock. Its counters and its back
 *     room's chests and casks (its stockroom) are part of the stock the stock keeper keeps; its crafting table is
 *     the crafters' too. The shop's trade moves its staff into the store (they are stationed there and work at
 *     its benches); folk who come to the old shop are still served at its counter. Nothing is stranded and
 *     nothing built is thrown away.</li>
 * <li><b>The stock.</b> What is in the stockroom's chests and casks and in the counters is the shop's own, out of
 *     the stores and no longer counted in them (a chest or a cask in a building is furniture, not one of the
 *     village's stores: ZoneChests). It comes in by the couriers' and the stock keeper's deliveries from the
 *     storehouse (StoreDeliveries) and from the crafters' bench ({@link #fromTheBench}); it goes out at the
 *     counter (ShopStock.take), and what sits too long goes back to the stores.</li>
 * </ul>
 */
public final class Store {

    private Store() {}

    /** The building's name in the drawings and the ledger. */
    public static final String STRUCTURE = "store";
    /** The town that wants a store: this many folk, or this many and its shop's week of sales. */
    static final int FROM_FOLK = 40, BUSY_FOLK = 28, BUSY_WEEK = 100;

    // ------------------------------------------------------------------ the building

    /**
     * What one building is made of, for the trade: its counters (the shop floor's, nearest the door first), its
     * stockroom (every chest and cask that is not a counter, the ground floor before the loft), its door, the
     * stock keeper's desk (the lectern) and the crafters' benches.
     */
    record Layout(List<BlockPos> counters, List<BlockPos> stockroom, @Nullable BlockPos door, @Nullable BlockPos desk,
                  List<BlockPos> benches) {}

    private static final Map<Ledger.Building, Layout> LAYOUTS = new ConcurrentHashMap<>();

    /** Tests (and Villages.resetForTests): the store's books, its staff's day and its deliveries forgotten. */
    public static void resetForTests() {
        LAYOUTS.clear();
        SALES.clear();
        TICKED.clear();
        INSTANT = false;
        StockKeeper.resetForTests();
        StoreDeliveries.resetForTests();
        StoreFloor.resetForTests();
        StoreStaff.resetForTests();
    }

    /** A building's layout, worked out from its drawing (the same every time it is looked at). */
    static Layout layout(Ledger.Building b) {
        return LAYOUTS.computeIfAbsent(b, k -> {
            BlockPos door = TownLife.fittings(k).door();
            List<BuildGoal.Placement> plan = BuildGoal.plan(k.structure(), k.anchor(), k.facing(), 13);
            List<BlockPos> counters = new ArrayList<>(), stock = new ArrayList<>(), benches = new ArrayList<>();
            BlockPos desk = null;
            if (k.structure().equals(STRUCTURE)) {
                // The store's drawing says which is which: a cask on the shop floor (in front of the partition)
                // with nothing over it is a counter; every other chest and cask is the stockroom's.
                List<Blueprints.Cell> cells = Blueprints.cells(STRUCTURE);
                Set<BlockPos> planned = new HashSet<>();
                for (BuildGoal.Placement p : plan) if (p.part() != BuildGoal.Part.CLEAR) planned.add(p.pos());
                for (int i = 0; i < plan.size() && i < cells.size(); i++) {
                    BuildGoal.Placement p = plan.get(i);
                    Blueprints.Cell c = cells.get(i);
                    switch (p.part()) {
                        case BARREL -> (c.dz() < 0 && c.h() == 0 && !planned.contains(p.pos().above()) ? counters : stock).add(p.pos());
                        case CHEST -> stock.add(p.pos());
                        case LECTERN -> desk = p.pos();
                        case CRAFTING_TABLE, FURNACE, LOOM, GRINDSTONE, ANVIL -> benches.add(p.pos());
                        default -> { }
                    }
                }
            } else {
                // The little shop: its counters are what they always were (Cafe), and the rest of its chests and
                // casks, the back room's, are its stockroom.
                counters.addAll(Cafe.counters(k));
                Set<BlockPos> isCounter = new HashSet<>(counters);
                for (BuildGoal.Placement p : plan) {
                    if ((p.part() == BuildGoal.Part.CHEST || p.part() == BuildGoal.Part.BARREL) && !isCounter.contains(p.pos())) stock.add(p.pos());
                    if (p.part() == BuildGoal.Part.CRAFTING_TABLE) benches.add(p.pos());
                }
            }
            BlockPos toward = door != null ? door : k.anchor();
            counters.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(p -> p.distSqr(toward))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
            // The stockroom: the ground floor first, nearest the shop floor; the loft after.
            BlockPos anchor = k.anchor();
            stock.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(p -> p.distSqr(anchor))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
            return new Layout(List.copyOf(counters), List.copyOf(stock), door, desk, List.copyOf(benches));
        });
    }

    /** Is the town store standing? */
    public static boolean stands(UUID village) {
        return Villages.hasBuilt(village, STRUCTURE) || Villages.builtStructure(village, STRUCTURE) != null;
    }

    /** The shop's premises: the store first (if it stands), then the little shop. */
    static List<Ledger.Building> premises(UUID village) {
        List<Ledger.Building> out = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals(STRUCTURE)) out.add(b);
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals("shop")) out.add(b);
        return out;
    }

    /** The main building: the store if it stands, else the shop. */
    @Nullable
    static Ledger.Building main(UUID village) {
        List<Ledger.Building> all = premises(village);
        return all.isEmpty() ? null : all.get(0);
    }

    /** The building the shop's trade works out of: the store once it stands, else the shop. */
    public static String buildingForShop(@Nullable UUID village) {
        return village != null && stands(village) && Villages.builtAt(village, STRUCTURE) != null ? STRUCTURE : "shop";
    }

    // ------------------------------------------------------------------ the stock

    /** The stockroom's chests and casks (and, with {@code counters}, the counters'), the store's first: those that
     *  stand and hold things now. Never one of the village's own stores (a chest marked as the stores'). */
    static List<Container> containers(ServerLevel level, UUID village, boolean counters) {
        List<Container> out = new ArrayList<>();
        for (Ledger.Building b : premises(village)) {
            if (!level.isLoaded(b.anchor())) continue;
            Layout l = layout(b);
            if (counters) for (BlockPos p : l.counters()) add(level, p, out);
            for (BlockPos p : l.stockroom()) add(level, p, out);
        }
        return out;
    }

    private static void add(ServerLevel level, BlockPos p, List<Container> out) {
        if (!level.isLoaded(p)) return;
        BlockEntity be = level.getBlockEntity(p);
        if (!(be instanceof Container c) || ZoneChests.isVillageStore(be)) return;
        if (!out.contains(c)) out.add(c);
    }

    /** How many of what matches the shop holds of its own: the stockroom and the counters. */
    static int count(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        int n = 0;
        for (Container c : containers(level, village, true)) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && what.test(s)) n += s.getCount();
            }
        }
        return n;
    }

    /** Everything the shop holds, by its name in the books (Stockroom.key), in one look. */
    static Map<String, Integer> onHand(ServerLevel level, UUID village) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Container c : containers(level, village, true)) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty()) out.merge(Stockroom.key(s), s.getCount(), Integer::sum);
            }
        }
        return out;
    }

    /** One of each thing the shop holds, the dearest first: what goes out on its counters. */
    static List<ItemStack> kinds(ServerLevel level, UUID village) {
        List<ItemStack> out = new ArrayList<>();
        for (Container c : containers(level, village, true)) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || s.isDamaged()) continue;
                boolean seen = false;
                for (ItemStack o : out) if (ItemStack.isSameItemSameComponents(o, s)) { seen = true; break; }
                if (!seen) out.add(s.copyWithCount(1));
            }
        }
        return out;
    }

    /**
     * Take up to so many of what matches out of the shop's own stock (the counters first, then the stockroom),
     * and give back what was taken. Takes nothing if {@code all} and it has not that many.
     */
    static List<ItemStack> take(ServerLevel level, UUID village, Predicate<ItemStack> what, int n, boolean all) {
        List<ItemStack> out = new ArrayList<>();
        if (n <= 0) return out;
        List<Container> boxes = containers(level, village, true);
        if (all) {
            int have = 0;
            for (Container c : boxes) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.getItem(i);
                    if (!s.isEmpty() && what.test(s)) have += s.getCount();
                }
            }
            if (have < n) return out;
        }
        int left = n;
        for (Container c : boxes) {
            boolean changed = false;
            for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !what.test(s)) continue;
                int k = Math.min(left, s.getCount());
                out.add(s.copyWithCount(k));
                s.shrink(k);
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                left -= k;
                changed = true;
            }
            if (changed) c.setChanged();
            if (left <= 0) break;
        }
        return out;
    }

    /** Into the stockroom (onto stacks of the same first: Stacking): what would not fit comes back. */
    static ItemStack put(ServerLevel level, UUID village, ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        List<Container> boxes = containers(level, village, false);
        if (boxes.isEmpty()) return stack.copy();
        return Stacking.insert(boxes, stack);
    }

    /** Has the shop a stockroom at all (a chest or a cask of its own standing)? */
    static boolean hasStockroom(ServerLevel level, UUID village) {
        return !containers(level, village, false).isEmpty();
    }

    /**
     * Out of the village's stores, up to so many of what matches, for the shop: booked out of the storehouse's
     * books to the store, and not counted against anybody's making (it was made already: Economy).
     */
    static List<ItemStack> fromStores(ServerLevel level, UUID village, Predicate<ItemStack> what, int n) {
        List<ItemStack> out = new ArrayList<>();
        int left = n;
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (left <= 0) break;
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            boolean changed = false;
            for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !what.test(s)) continue;
                int k = Math.min(left, s.getCount());
                ItemStack lot = s.copyWithCount(k);
                if (c instanceof com.jrpetty.mcassistant.block.StorehouseBlockEntity) Storekeeping.bookOut(level, village, "the store", lot, k, null);
                out.add(lot);
                s.shrink(k);
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                left -= k;
                changed = true;
            }
            if (changed) c.setChanged();
        }
        if (!out.isEmpty()) Budget.forget(village);
        return out;
    }

    /** Where a carrier stands to load or unload at the shop: by the stockroom's first chest, on the floor. */
    @Nullable
    static BlockPos stockroomSpot(ServerLevel level, UUID village) {
        Ledger.Building b = main(village);
        if (b == null) return null;
        Layout l = layout(b);
        BlockPos near = !l.stockroom().isEmpty() ? l.stockroom().get(0) : l.door() != null ? l.door() : b.anchor();
        BlockPos spot = Trades.floorSpot(level, near, 2);
        return spot != null ? spot : near;
    }

    // ------------------------------------------------------------------ the bench's work

    /**
     * What the crafters made at the shop's bench (Cafe.keepShop: it went into the stores, booked as made) goes
     * into the stockroom if it was made to be sold: for the shelves, or on the stock keeper's order. What the
     * town needs of it (the watch's armour, the storehouse's rack, a player's order, another trade's wants) stays
     * in the stores where they look for it. Moved out of the stores without undoing the making.
     */
    static void fromTheBench(ServerLevel level, Villages.Village v, ItemStack made) {
        if (made.isEmpty() || !ShopStock.open(v.id()) || !hasStockroom(level, v.id())) return;
        String key = Stockroom.key(made);
        String why = Workshop.why(v.id(), key);
        if (!why.equals("the shelves") && !why.contains(StockKeeper.ORDERED_BY)) return;
        List<ItemStack> lots = fromStores(level, v.id(), s -> ItemStack.isSameItemSameComponents(s, made), made.getCount());
        int in = 0;
        for (ItemStack lot : lots) {
            ItemStack left = put(level, v.id(), lot);
            in += lot.getCount() - left.getCount();
            if (!left.isEmpty()) TownWork.give(level, v, left);           // no room: back where it was
        }
        if (in > 0) StockKeeper.fromTheBench(level, v.id(), key, in);
    }

    // ------------------------------------------------------------------ when the town wants one

    /** The week's sales at the shop, as last counted (for the building list, which has no level to count by). */
    private static final Map<UUID, Integer> SALES = new ConcurrentHashMap<>();

    /** The shop's week of sales, as last counted. */
    static int weekSales(UUID village) {
        return SALES.getOrDefault(village, 0);
    }

    /** Does the town want its store: the Iron Age, a shop standing, and a town of forty (or a busy shop's town
     *  of twenty-eight)? */
    public static boolean wanted(UUID village, int folk) {
        if (Villages.ageOf(village).ordinal() < Villages.Age.IRON.ordinal() || !Workshop.stands(village) || stands(village)) return false;
        return folk >= FROM_FOLK || folk >= BUSY_FOLK && SALES.getOrDefault(village, 0) >= BUSY_WEEK;
    }

    /** Why the town builds its store, in words. */
    public static String why(UUID village) {
        int week = SALES.getOrDefault(village, 0);
        return "a town store: the little shop sold " + week + " things this week and has no room for its stock, its crafters or its counters; "
            + "a store has a shop floor, a stockroom, a workshop and an office for the stock keeper";
    }

    // ------------------------------------------------------------------ the town's round

    private static final Map<UUID, Long> TICKED = new ConcurrentHashMap<>();
    /** Tests: carriers are put beside where they are going rather than walking. */
    static volatile boolean INSTANT;

    public static void instantForTests(boolean on) {
        INSTANT = on;
    }

    /**
     * The shop's round, from the town's (TownWork.tick, every fifteen seconds): the staff looked over and taken
     * on (StoreStaff), the morning's stock-take and the orders it places (StockKeeper), the deliveries seen to
     * (StoreDeliveries), and the counters set out from the stock.
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (!ShopStock.open(id)) return;
        long now = level.getGameTime();
        Long last = TICKED.get(id);
        if (last != null && now - last < 200L && now >= last) return;
        TICKED.put(id, now);
        StoreStaff.tick(level, v);
        StockKeeper.tick(level, v);
        StoreDeliveries.tick(level, v);
        SALES.put(id, StockKeeper.soldThisWeek(level, id));
        if (Math.floorMod(now / 200L, 3L) == 0) StoreFloor.dress(level, v);
    }

    // ------------------------------------------------------------------ the smoke and the tests

    /** Tests: this into the shop's stockroom; how many would not fit. */
    public static int putForTests(ServerLevel level, UUID village, ItemStack stack) {
        return put(level, village, stack).getCount();
    }

    /** Tests: a building's layout in numbers: {counters, stockroom's chests and casks, benches, a desk (1) or not}. */
    public static int[] layoutForTests(Ledger.Building b) {
        Layout l = layout(b);
        return new int[]{ l.counters().size(), l.stockroom().size(), l.benches().size(), l.desk() == null ? 0 : 1 };
    }

    /**
     * A store stood up by this spot if the village has none (put up at once, as the showcase's are), its shop
     * too if it has none, staffed (a keeper, assistants at the counters, a crafter at the bench and a stock
     * keeper at the desk) and stocked out of the stores; for the client smoke. Says where: "STORE x y z",
     * "FLOOR x y z", "STOCKROOM x y z", "DESK x y z", and a line a member of staff.
     */
    public static List<String> stage(ServerLevel level, Villages.Village v, BlockPos near) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        if (Villages.builtAt(id, "shop") == null) Workshop.stage(level, v, near);
        if (Villages.builtAt(id, STRUCTURE) == null) {
            // Beside the shop, clear of it, on the flattest dry ground near there (the store is eleven across and
            // seventeen deep under a wider roof), cleared of what grows on it first.
            BlockPos shop = Villages.builtAt(id, "shop");
            BlockPos at = flatGround(level, (shop != null ? shop : near).offset(24, 0, 0));
            clearSite(level, at);
            BuildGoal.stamp(level, STRUCTURE, at, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
            Ledger.built(id, STRUCTURE, at, Direction.NORTH);
            Villages.builtAtForTests(id, STRUCTURE, at);
        }
        Ledger.Building b = Villages.builtStructure(id, STRUCTURE);
        if (b == null) return out;
        Layout l = layout(b);
        out.add("STORE " + b.anchor().getX() + " " + b.anchor().getY() + " " + b.anchor().getZ());
        if (l.door() != null) out.add("DOOR " + l.door().getX() + " " + l.door().getY() + " " + l.door().getZ());
        if (!l.counters().isEmpty()) {
            BlockPos c = l.counters().get(0);
            out.add("FLOOR " + c.getX() + " " + c.getY() + " " + c.getZ());
        }
        if (!l.stockroom().isEmpty()) {
            BlockPos c = l.stockroom().get(0);
            out.add("STOCKROOM " + c.getX() + " " + c.getY() + " " + c.getZ());
        }
        if (l.desk() != null) out.add("DESK " + l.desk().getX() + " " + l.desk().getY() + " " + l.desk().getZ());
        out.addAll(StoreStaff.staffNow(level, v));
        // Its stock: what the stores can spare, delivered at once (a delivery each, as the couriers would bring it).
        StockKeeper.takeStock(level, v, true);
        StoreDeliveries.deliverAllNow(level, v);
        StoreFloor.dress(level, v);
        out.addAll(standForTheCamera(level, v, b));
        for (String st : StoreStaff.lines(v.id())) out.add("STAFF " + st);
        return out;
    }

    /** The tag on a folk held in its place for the camera (Store.stage), till the stage is done. */
    public static final String STAGED = "mca_store_staged";

    /** A place in the store's drawing, in the world: across (right is +) and deep (the back is +). */
    private static BlockPos at(Ledger.Building b, int dx, int dz) {
        Direction back = b.facing(), right = back.getClockWise();
        return b.anchor().relative(right, dx).relative(back, dz);
    }

    /** A camera's place and aim, for the smoke ("SHOT name feet-x y z aim-x y z"): its feet at a place in the drawing
     *  so many blocks over the floor, looking at another place so many over the floor. */
    private static String shot(Ledger.Building b, String name, int dx, int dz, double up, int ax, int az, double aup) {
        return shot(name, at(b, dx, dz), up, at(b, ax, az), aup);
    }

    private static String shot(String name, BlockPos eye, double up, BlockPos aim, double aup) {
        return String.format(java.util.Locale.ROOT, "SHOT %s %.1f %.1f %.1f %.1f %.1f %.1f", name, eye.getX() + 0.5, eye.getY() + up,
            eye.getZ() + 0.5, aim.getX() + 0.5, aim.getY() + aup, aim.getZ() + 0.5);
    }

    /**
     * The staff at their places and held there for the camera, as at a busy hour: the assistants and the keeper
     * behind the counters, customers in front of them, the stock keeper in the stockroom's aisle among the chests,
     * a crafter at the workshop's bench; and where the camera stands for each picture (SHOT lines): the whole
     * building from outside, the shop floor, the stockroom, the workshop, an assistant at its counter.
     */
    static List<String> standForTheCamera(ServerLevel level, Villages.Village v, Ledger.Building b) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        List<StoreFloor.Post> manned = new ArrayList<>();
        for (StoreFloor.Post p : StoreFloor.posts(level, id)) if (p.stand() != null && p.building().equals(b)) manned.add(p);
        List<VillageFolkEntity> held = new ArrayList<>();
        int post = 0;
        for (VillageFolkEntity a : StoreStaff.in(id, ShopRoles.Role.ASSISTANT)) {
            if (post >= manned.size()) break;
            StoreFloor.Post p = manned.get(post++);
            hold(a, p.stand(), p.counter().relative(p.front(), 3));
            held.add(a);
        }
        VillageFolkEntity keeper = Workshop.keeper(id);
        if (keeper != null && post < manned.size()) {
            StoreFloor.Post p = manned.get(post++);
            hold(keeper, p.stand(), p.counter().relative(p.front(), 3));
            held.add(keeper);
        }
        VillageFolkEntity sk = StoreStaff.stockKeeper(id);
        if (sk != null) {
            hold(sk, at(b, -3, 5), at(b, -4, 5));
            held.add(sk);
        }
        List<VillageFolkEntity> hands = Workshop.hands(id);
        if (!hands.isEmpty()) {
            hold(hands.get(0), at(b, 2, 6), at(b, 1, 7));
            held.add(hands.get(0));
        }
        // Customers: grown folk of the town, not of the shop, nearest first, at the counters after the first (the
        // first is the close-up's), facing whoever serves them.
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.isAlive() && !f.isShowcase()
                    && f.stationTask() != AssistantEntity.StationTask.SHOP && !held.contains(f)) folk.add(f);
        }
        BlockPos mid = b.anchor();
        folk.sort(Comparator.comparingDouble(f -> f.distanceToSqr(mid.getX(), mid.getY(), mid.getZ())));
        int customers = 0;
        for (int i = 1; i < manned.size() && customers < 3 && customers < folk.size(); i++) {
            StoreFloor.Post p = manned.get(i);
            BlockPos spot = p.counter().relative(p.front(), 2);
            if (!StoreFloor.standable(level, spot)) spot = p.counter().relative(p.front());
            if (!StoreFloor.standable(level, spot)) continue;
            VillageFolkEntity c = folk.get(customers++);
            hold(c, spot, p.counter());
            out.add("CUSTOMER " + c.displayNameCap() + " " + spot.getX() + " " + spot.getY() + " " + spot.getZ());
        }
        // The cameras: outside, the shop floor from just inside the door, the stockroom and the workshop from
        // their doorways in the partition, and an assistant face to face across its counter.
        out.add(shot(b, "24-store-0-outside", 13, -25, 7, 0, -1, 5));
        out.add(shot(b, "24-store-1-shop-floor", -3, -7, 0, 2, -3, 1.2));
        out.add(shot(b, "24-store-2-stockroom", -3, 0, 0, -3, 6, 0.8));
        out.add(shot(b, "24-store-3-workshop", 3, 2, 0, 1, 6, 1.0));
        if (!manned.isEmpty()) {
            StoreFloor.Post p = manned.get(0);
            out.add(shot("24-store-4-assistant", p.counter().relative(p.front(), 3), 0, p.stand(), 1.4));
        }
        return out;
    }

    /** Held at this spot, facing that one, for the camera (let go by {@link #releaseStaged}). */
    private static void hold(VillageFolkEntity f, BlockPos spot, BlockPos face) {
        f.getNavigation().stop();
        f.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        float yaw = (float) (Math.atan2(face.getZ() - spot.getZ(), face.getX() - spot.getX()) * (180.0 / Math.PI)) - 90.0F;
        f.setYRot(yaw);
        f.setYHeadRot(yaw);
        f.setYBodyRot(yaw);
        f.setNoAi(true);
        f.addTag(STAGED);
    }

    /** Everybody held in the store for the camera about its business again. Returns how many. */
    public static int releaseStaged(Villages.Village v) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !f.getTags().contains(STAGED)) continue;
            f.setNoAi(false);
            f.removeTag(STAGED);
            n++;
        }
        return n;
    }

    /** The flattest dry spot near here for the store (its corners and middle within a block of one another), within
     *  thirty blocks; else here. */
    private static BlockPos flatGround(ServerLevel level, BlockPos near) {
        for (int ring = 0; ring <= 30; ring += 3) {
            for (int dx = -ring; dx <= ring; dx += 3) {
                for (int dz = -ring; dz <= ring; dz += 3) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    BlockPos at = ground(level, near.getX() + dx, near.getZ() + dz);
                    boolean flat = true;
                    for (int[] c : new int[][]{ { 0, 0 }, { -6, -10 }, { 6, -10 }, { -6, 10 }, { 6, 10 }, { 0, -10 }, { 0, 10 } }) {
                        BlockPos q = ground(level, at.getX() + c[0], at.getZ() + c[1]);
                        if (!level.getFluidState(q.below()).isEmpty() || Math.abs(q.getY() - at.getY()) > 1) { flat = false; break; }
                    }
                    if (flat) return at;
                }
            }
        }
        return ground(level, near.getX(), near.getZ());
    }

    /** The first free block over the ground here, seeing through trees. */
    private static BlockPos ground(ServerLevel level, int x, int z) {
        return new BlockPos(x, BuildGoal.groundTop(level, x, z), z);
    }

    /** The store's ground cleared of what the world grew on it (trees, leaves, grass, flowers) so its air is clear. */
    private static void clearSite(ServerLevel level, BlockPos at) {
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-7, 0, -11), at.offset(7, 16, 11))) {
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(p);
            if (st.isAir()) continue;
            if (BuildGoal.isWildPlant(st) || st.is(net.minecraft.tags.BlockTags.LEAVES) || st.is(net.minecraft.tags.BlockTags.LOGS)
                    || st.canBeReplaced() && st.getFluidState().isEmpty()) {
                level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        }
    }
}
