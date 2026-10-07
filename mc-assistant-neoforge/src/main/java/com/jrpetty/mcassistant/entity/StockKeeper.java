package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The stock keeper's job, and its book: the shop's stock kept so that what folk want is on the shelves when they
 * come for it. The hardest job at the shop, and judged by one thing: how often a customer finds a ware gone.
 * <ul>
 * <li><b>The range.</b> Everything the town's folk buy for themselves: bread, baked potatoes and the roasts, the
 *     fish, the vegetables and the apples, the cookies and the pies; the tools of every trade the age has come
 *     to; the household's chests and casks, torches, candles and lanterns, buckets and shears, rods and pots;
 *     beds, rugs, wool and banners; the homes' comforts (paintings, glass, bookshelves); and whatever else folk
 *     and players have bought or asked for this week.</li>
 * <li><b>Every morning</b> the stock keeper walks the stockroom and counts every ware: on hand, sold yesterday
 *     and this week, wanted and not there, and so how many days' sales it has on its shelves (its cover). From
 *     each ware's rate of sale it sets two numbers: the reorder point (enough to last while more is fetched,
 *     and a little over: a day's for food, half a day's for the rest) and the order-up-to level (that, and two
 *     days' more). Food first, then the ware with the least cover.</li>
 * <li><b>The orders.</b> A ware at or under its reorder point is ordered up to its level: from the storehouse
 *     (the couriers carry it over: StoreDeliveries) if the stores can spare it; else from the crafters (an order
 *     on the workshop's book: Workshop.order), by way of the smelter if it wants firing; else, with nothing to
 *     be had, a notice that the store wants it, for the leader and the quest board (Stockroom.asks).</li>
 * <li><b>Chasing.</b> An order the crafters are two days late with is chased (put on their book again, and the
 *     keeper told), and a second time; after that the stock keeper gives it up as wanted. A delivery no courier
 *     takes it fetches itself.</li>
 * <li><b>What sits.</b> Food with more than four days' cover, and a ware nobody has bought for four days, goes
 *     back to the stores, all but what the shelves keep.</li>
 * <li><b>The prices</b> it sets with the keeper: a margin over what a thing costs in the town (PriceIndex),
 *     starting at a quarter, raised a twentieth when a ware sold out, lowered a twentieth when it sits unsold
 *     three days (to a twentieth over at the least, three fifths at the most, food never more than seven
 *     twentieths over), drifting back to a quarter otherwise. The slow-seller markdown (Stockroom) stays.</li>
 * <li><b>With no stock keeper</b> the keeper notices only what has run out at its counter: a lot of each, from the
 *     storehouse, four wares a day at most. No food first, no orders to the crafters, no chasing, nothing sent
 *     back, the prices left as they are.</li>
 * </ul>
 * The book is kept with the village (its ledger), a week of it, and shown on /village stock, the stock keeper's
 * card, the board and the gazette.
 */
public final class StockKeeper {

    private StockKeeper() {}

    /** Who the stock keeper's orders on the workshop's book are for (Workshop.order). */
    static final String ORDERED_BY = "the stock keeper";
    static final int WEEK = 7;
    /** The margin over what a thing costs in the town. */
    static final double BASE_MARKUP = 1.25, LEAST_MARKUP = 1.05, MOST_MARKUP = 1.60, FOOD_MOST_MARKUP = 1.35, STEP = 0.05;
    /** Days between counts, and a little over (the order-up-to level is this much past the reorder point). */
    static final double REVIEW_DAYS = 2.0;
    /** A ware nobody has bought for this many days goes back to the stores; food with this much cover. */
    static final int RETURN_IDLE = 4, FOOD_COVER_MOST = 4;
    /** An order the crafters are this many days late with is chased, so many times before it is given up. */
    static final int LATE_DAYS = 2, CHASES = 2;
    /** The keeper with no stock keeper sees to so many empty shelves a day. */
    static final int KEEPER_NOTICES = 4;

    /** Where an order goes. */
    enum Route {
        STOREHOUSE("from the storehouse", 0.5), CRAFTERS("from the crafters", 1.5), SMELTER("from the smelter and the crafters", 2.5),
        WANTED("wanted: none to be had", 3.0), RETURN("back to the stores", 0.0);

        final String words;
        /** How long it takes to come, in days. */
        final double lead;

        Route(String words, double lead) {
            this.words = words;
            this.lead = lead;
        }
    }

    /** A ware the store keeps: its name in the books, what counts as it, one to show, whether it is food, what
     *  the crafters would make for it (null: never theirs), how many to a lot, and how many to open with. */
    record Ware(String key, Predicate<ItemStack> is, ItemStack sample, boolean food, @Nullable Item make, int lot, int opening) {}

    /** One ware's week, today first, and what the stock keeper made of it at its last count. */
    static final class Line {
        final String key;
        final int[] sold = new int[WEEK], fromStores = new int[WEEK], missed = new int[WEEK], outs = new int[WEEK];
        final int[] in = new int[WEEK], back = new int[WEEK], made = new int[WEEK];
        double markup = BASE_MARKUP;
        long lastSale = -1, lastIn = -1;
        int onHand, reorder, upTo;
        double rate, cover;
        String lastOrder = "";

        Line(String key) {
            this.key = key;
        }

        static int week(int[] a) {
            int n = 0;
            for (int x : a) n += x;
            return n;
        }

        void roll(int days) {
            for (int[] a : new int[][]{ sold, fromStores, missed, outs, in, back, made }) {
                for (int i = WEEK - 1; i >= 0; i--) a[i] = i - days >= 0 ? a[i - days] : 0;
            }
        }
    }

    /** An order in flight: what, how many, where it went, the day, and how it stands. */
    static final class Order {
        final int id;
        final String key;
        final Route route;
        int count, arrived, chased;
        final long day;
        boolean done;
        @Nullable StoreDeliveries.Delivery delivery;

        Order(int id, String key, Route route, int count, long day) {
            this.id = id;
            this.key = key;
            this.route = route;
            this.count = count;
            this.day = day;
        }
    }

    /** The stock book: a week of every ware, the orders in flight, the wanted notices, and the day's entries. */
    static final class Book {
        final UUID village;
        long day, opened, counted = -1, noticed = -1, told = -1, topped = -1;
        String countedBy = "";
        final Map<String, Line> lines = new LinkedHashMap<>();
        final List<Order> orders = new java.util.concurrent.CopyOnWriteArrayList<>();
        final Map<String, Integer> wanted = new LinkedHashMap<>();
        final List<String> entries = new ArrayList<>();
        long chasedAt = -100000L;

        Book(UUID village, long today) {
            this.village = village;
            this.day = today;
            this.opened = today;
        }

        Line line(String key) {
            return lines.computeIfAbsent(key, Line::new);
        }
    }

    private static final Map<UUID, Book> BOOKS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> DOING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> COUNTING = new ConcurrentHashMap<>();
    private static int nextOrder = 1;

    public static void resetForTests() {
        BOOKS.clear();
        DOING.clear();
        COUNTING.clear();
    }

    static long today(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    /** The book, turned over to today. */
    static Book book(ServerLevel level, UUID village) {
        long today = today(level);
        Book b = BOOKS.computeIfAbsent(village, k -> load(village, today));
        if (today > b.day) {
            int days = (int) Math.min(WEEK, today - b.day);
            for (Line l : b.lines.values()) l.roll(days);
            b.day = today;
            b.entries.clear();
            save(b);
        } else if (today < b.day) {
            b.day = today;                                                 // the clock set back: the week starts again
            b.opened = Math.min(b.opened, today);
        }
        return b;
    }

    // ------------------------------------------------------------------ the ledger

    private static final String NOTE = "store/book";

    /** "1;day;opened;counted;told|key~sold~fromStores~missed~outs~in~back~made~markup~lastSale~lastIn|...". */
    private static void save(Book b) {
        StringBuilder sb = new StringBuilder("1;").append(b.day).append(';').append(b.opened).append(';').append(b.counted).append(';').append(b.told);
        for (Line l : b.lines.values()) {
            int any = Line.week(l.sold) + Line.week(l.missed) + Line.week(l.in) + Line.week(l.back) + Line.week(l.made);
            if (any == 0 && l.lastSale < 0 && Math.abs(l.markup - BASE_MARKUP) < 1e-6) continue;
            sb.append('|').append(l.key);
            for (int[] a : new int[][]{ l.sold, l.fromStores, l.missed, l.outs, l.in, l.back, l.made }) sb.append('~').append(csv(a));
            sb.append('~').append(String.format(Locale.ROOT, "%.3f", l.markup)).append('~').append(l.lastSale).append('~').append(l.lastIn);
        }
        Ledger.note(b.village, NOTE, sb.toString());
    }

    private static Book load(UUID village, long today) {
        Book b = new Book(village, today);
        String note = Ledger.note(village, NOTE);
        if (note == null || note.isEmpty()) return b;
        try {
            String[] parts = note.split("\\|");
            String[] head = parts[0].split(";");
            b.day = Long.parseLong(head[1]);
            b.opened = Long.parseLong(head[2]);
            b.counted = Long.parseLong(head[3]);
            b.told = Long.parseLong(head[4]);
            for (int i = 1; i < parts.length; i++) {
                String[] f = parts[i].split("~");
                if (f.length < 11) continue;
                Line l = new Line(f[0]);
                int k = 1;
                for (int[] a : new int[][]{ l.sold, l.fromStores, l.missed, l.outs, l.in, l.back, l.made }) parse(f[k++], a);
                l.markup = Double.parseDouble(f[k++]);
                l.lastSale = Long.parseLong(f[k++]);
                l.lastIn = Long.parseLong(f[k]);
                b.lines.put(l.key, l);
            }
        } catch (RuntimeException e) {
            b = new Book(village, today);                                  // a torn page: the book starts again
        }
        return b;
    }

    private static String csv(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    private static void parse(String s, int[] into) {
        String[] p = s.split(",");
        for (int i = 0; i < into.length && i < p.length; i++) into[i] = Integer.parseInt(p[i].trim());
    }

    /** An entry in the day's book: "09:10 Ada counted the stock: ...". */
    private static void entry(ServerLevel level, Book b, String text) {
        long t = (level.getDayTime() + 6000L) % 24000L;
        b.entries.add(String.format(Locale.ROOT, "%02d:%02d ", t / 1000L, (t % 1000L) * 60L / 1000L) + text);
        while (b.entries.size() > 40) b.entries.remove(0);
    }

    // ------------------------------------------------------------------ the range

    /** The food the store keeps, the bread first. */
    static final List<Item> FOOD = List.of(Items.BREAD, Items.BAKED_POTATO, Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_MUTTON,
        Items.COOKED_CHICKEN, Items.COOKED_COD, Items.COOKED_SALMON, Items.CARROT, Items.POTATO, Items.BEETROOT, Items.APPLE,
        Items.COOKIE, Items.PUMPKIN_PIE);
    /** The tools of every trade, by kind, best first. */
    static final List<List<Item>> TOOLS = List.of(
        List.of(Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE),
        List.of(Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE),
        List.of(Items.DIAMOND_HOE, Items.IRON_HOE, Items.STONE_HOE),
        List.of(Items.DIAMOND_SHOVEL, Items.IRON_SHOVEL, Items.STONE_SHOVEL),
        List.of(Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD));
    /** The household's things, clothes and comforts kept as one whatever their colour, and the comforts. */
    static final List<String> FAMILIES = List.of("bed", "rug", "wool", "banner", "candle");
    static final List<Item> COMFORTS = List.of(Items.BOOKSHELF, Items.PAINTING, Items.GLASS_PANE, Items.FLOWER_POT, Items.LANTERN);

    /** The store's range in this town today (Ware): the food, the shop's own shelves, the age's tools, the
     *  household's things and comforts, and whatever has sold or been asked for this week. */
    static List<Ware> range(ServerLevel level, Villages.Village v, Book b) {
        Map<String, Ware> out = new LinkedHashMap<>();
        int folk = Math.max(1, Villages.headcount(v.id()));
        for (Item it : FOOD) {
            ItemStack one = new ItemStack(it);
            String key = Stockroom.key(one);
            int opening = it == Items.BREAD ? Math.max(6, folk / 2) : it == Items.BAKED_POTATO ? Math.max(4, folk / 4) : 4;
            out.put(key, new Ware(key, s -> s.is(it), one, true, null, 1, opening));
        }
        for (Stockroom.Ware w : Cafe.shopWares()) {
            if (!Workshop.allows(level, v.id(), w)) continue;
            out.putIfAbsent(w.key(), new Ware(w.key(), w.is(), w.sample(), false, w.make(), Math.max(1, Math.min(8, w.batch())), Math.max(1, w.usual())));
        }
        Villages.Age age = Villages.ageOf(v.id());
        for (List<Item> kind : TOOLS) {
            for (Item it : kind) {
                if (!Tiers.allows(level, age, it)) continue;
                ItemStack one = new ItemStack(it);
                String key = Stockroom.key(one);
                out.putIfAbsent(key, new Ware(key, s -> s.is(it), one, false, it, 1, 1));
            }
        }
        for (String key : FAMILIES) {
            ItemStack one = Stockroom.sampleOf(key);
            if (one.isEmpty()) continue;
            Item make = key.equals("wool") || key.equals("banner") ? null : one.getItem();
            out.putIfAbsent(key, new Ware(key, Stockroom.matcher(key), one, false, make, 1, key.equals("candle") ? 4 : 1));
        }
        for (Item it : COMFORTS) {
            ItemStack one = new ItemStack(it);
            String key = Stockroom.key(one);
            out.putIfAbsent(key, new Ware(key, s -> s.is(it), one, false, it, 1, 1));
        }
        // Whatever folk and players bought or asked for this week, that the range has not got.
        for (Line l : b.lines.values()) {
            if (out.containsKey(l.key) || Line.week(l.sold) + Line.week(l.missed) == 0) continue;
            ItemStack one = Stockroom.sampleOf(l.key);
            if (one.isEmpty() || out.size() >= 72) continue;
            boolean food = one.get(DataComponents.FOOD) != null;
            Item make = food || RecipeBook.waysFor(level, one.getItem()).isEmpty() ? null : one.getItem();
            out.put(l.key, new Ware(l.key, Stockroom.matcher(l.key), one, food, make, 1, 0));
        }
        return new ArrayList<>(out.values());
    }

    /** One of what this matches, out of the range (the books of a ware nobody has any of). */
    static ItemStack sampleFor(Predicate<ItemStack> what) {
        for (Item it : FOOD) if (what.test(new ItemStack(it))) return new ItemStack(it);
        for (List<Item> kind : TOOLS) for (Item it : kind) if (what.test(new ItemStack(it))) return new ItemStack(it);
        for (Stockroom.Ware w : Cafe.shopWares()) if (what.test(w.sample())) return w.sample().copy();
        for (String key : FAMILIES) {
            ItemStack one = Stockroom.sampleOf(key);
            if (!one.isEmpty() && what.test(one)) return one;
        }
        for (Item it : COMFORTS) if (what.test(new ItemStack(it))) return new ItemStack(it);
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ what happens at the counter

    /** Sold at the counter: so many of this, so many of them fetched from the stores (a stock-out). */
    static void sold(ServerLevel level, UUID village, ItemStack one, int n, int fromStores) {
        Book b = book(level, village);
        Line l = b.line(Stockroom.key(one));
        l.sold[0] += n;
        l.fromStores[0] += fromStores;
        if (fromStores > 0) l.outs[0]++;
        l.lastSale = b.day;
        save(b);
    }

    /** Wanted at the counter and none to be had anywhere: a stock-out, and demand all the same. */
    static void missed(ServerLevel level, UUID village, ItemStack one, int n) {
        Book b = book(level, village);
        Line l = b.line(Stockroom.key(one));
        l.missed[0] += n;
        l.outs[0]++;
        save(b);
    }

    /** Made at the bench and put in the stockroom: an order of the stock keeper's the nearer done. */
    static void fromTheBench(ServerLevel level, UUID village, String key, int n) {
        Book b = book(level, village);
        Line l = b.line(key);
        l.made[0] += n;
        l.lastIn = b.day;
        int left = n;
        for (Order o : b.orders) {
            if (o.done || !o.key.equals(key) || (o.route != Route.CRAFTERS && o.route != Route.SMELTER)) continue;
            int k = Math.min(left, o.count - o.arrived);
            o.arrived += k;
            left -= k;
            if (o.arrived >= o.count) o.done = true;
            if (left <= 0) break;
        }
        entry(level, b, "from the bench: " + Bench.words(Stockroom.sampleOf(key).getItem(), n) + " into the stockroom");
        save(b);
    }

    /** A delivery put away (or carried back). */
    static void delivered(ServerLevel level, UUID village, StoreDeliveries.Delivery d, int n) {
        Book b = book(level, village);
        Line l = b.line(d.key);
        if (d.back) l.back[0] += n;
        else {
            l.in[0] += n;
            l.lastIn = b.day;
        }
        for (Order o : b.orders) if (o.delivery == d) { o.arrived = n; o.done = true; }
        entry(level, b, (d.back ? "back to the stores: " : "delivered: ") + Bench.words(d.sample.getItem(), n)
            + (d.carrierName.isEmpty() ? "" : ", by " + d.carrierName));
        save(b);
    }

    /** A delivery that came to nothing: the order is open again for tomorrow's count, and the book says why. */
    static void deliveryFailed(ServerLevel level, UUID village, StoreDeliveries.Delivery d, String why) {
        Book b = book(level, village);
        for (Order o : b.orders) if (o.delivery == d) o.done = true;
        entry(level, b, "no delivery of " + Stockroom.plural(d.key) + ": " + why);
    }

    // ------------------------------------------------------------------ the count

    /** How many a day of this ware sell (and are asked for): the week's average over the days the book has been
     *  open, or today's if today is busier; at a shop new to its books, the old shop's and the stores' sales. */
    static double rate(ServerLevel level, UUID village, Book b, Line l) {
        int closed = (int) Math.min(WEEK - 1, Math.max(0, b.day - b.opened));
        double today = l.sold[0] + l.missed[0];
        double r = today;
        if (closed > 0) {
            int past = 0;
            for (int i = 1; i <= closed; i++) past += l.sold[i] + l.missed[i];
            r = Math.max(past / (double) closed, today);
        }
        if (r <= 0 && closed < 2) {
            // New books: what the shop and the stores sold of it before (Stockroom), a day's worth.
            int before = 0;
            for (Stockroom.Seller s : new Stockroom.Seller[]{ Stockroom.Seller.SHOP, Stockroom.Seller.STORES }) {
                Stockroom.Line sl = Stockroom.book(level, village, s).lines.get(l.key);
                if (sl != null) before += Stockroom.Line.week(sl.sold) + Stockroom.Line.week(sl.missed);
            }
            r = before / (double) WEEK;
        }
        return r;
    }

    /** The orders in flight for a ware (not yet arrived), in goods. */
    static int inFlight(Book b, String key) {
        int n = 0;
        for (Order o : b.orders) {
            if (o.done || !o.key.equals(key) || o.route == Route.RETURN || o.route == Route.WANTED) continue;
            n += Math.max(0, o.count - o.arrived);
        }
        return n;
    }

    /**
     * The morning's count and the orders it places (the class comment). {@code byStockKeeper}: the stock keeper's
     * whole job; else the keeper's noticing. Returns what was ordered, in words.
     */
    public static List<String> takeStock(ServerLevel level, Villages.Village v, boolean byStockKeeper) {
        UUID id = v.id();
        Book b = book(level, id);
        b.orders.removeIf(o -> o.done && b.day - o.day > 1);
        Map<String, Integer> hand = Store.onHand(level, id);
        List<Ware> range = range(level, v, b);
        boolean fresh = b.day - b.opened < 2;
        // Work out every ware first, then order the food first and the rest by their cover, least first.
        record Look(Ware w, Line l, int onHand, int inFlight) {}
        List<Look> looks = new ArrayList<>();
        for (Ware w : range) {
            Line l = b.line(w.key());
            int onHand = hand.getOrDefault(w.key(), 0);
            double rate = rate(level, id, b, l);
            Route likely = w.food() ? Route.STOREHOUSE : w.make() != null ? Route.CRAFTERS : Route.STOREHOUSE;
            double lead = Math.max(Route.STOREHOUSE.lead, Math.min(likely.lead, Market.stock(level, id, w.is()) > 0 ? Route.STOREHOUSE.lead : likely.lead));
            double safety = w.food() ? 1.0 : 0.5;
            int reorder = (int) Math.ceil(rate * (lead + safety) - 1e-9);
            int upTo = (int) Math.ceil(rate * (lead + safety + REVIEW_DAYS) - 1e-9);
            // Something always on show of the shop's own shelves and the bread; and at new books, the opening stock.
            if (rate <= 0) {
                int facing = fresh || l.lastSale < 0 && Line.week(l.in) == 0 ? w.opening() : (w.food() ? 0 : Math.min(1, w.opening()));
                reorder = 0;
                upTo = facing;
            }
            upTo = Math.max(upTo, reorder + (rate > 0 ? Math.max(1, w.lot()) : 0));
            l.onHand = onHand;
            l.rate = rate;
            l.reorder = reorder;
            l.upTo = upTo;
            l.cover = rate > 0 ? onHand / rate : onHand > 0 ? 99 : 0;
            looks.add(new Look(w, l, onHand, inFlight(b, w.key())));
        }
        looks.sort(Comparator.<Look>comparingInt(k -> k.w().food() ? 0 : 1).thenComparingDouble(k -> k.l().cover));
        List<String> placed = new ArrayList<>();
        int noticed = 0;
        for (Look k : looks) {
            Line l = k.l();
            int need = l.upTo - k.onHand() - k.inFlight();
            if (need <= 0) continue;
            if (byStockKeeper) {
                if (k.onHand() + k.inFlight() > l.reorder && !(l.reorder == 0 && k.onHand() == 0)) continue;
                String o = place(level, v, b, k.w(), l, need, true);
                if (o != null) placed.add(o);
            } else {
                // The keeper alone sees only an empty shelf that folk have been coming for (or its opening stock).
                if (k.onHand() > 0 || noticed >= KEEPER_NOTICES) continue;
                if (!fresh && l.sold[1] + l.sold[0] + l.missed[1] + l.missed[0] == 0) continue;
                String o = place(level, v, b, k.w(), l, Math.max(1, Math.min(need, k.w().lot() * (fresh ? Math.max(1, k.w().opening()) : 1))), false);
                if (o != null) { placed.add(o); noticed++; }
            }
        }
        if (byStockKeeper) {
            markups(b, looks.stream().map(Look::l).toList(), hand);
            returns(level, v, b, range, hand);
            b.counted = b.day;
        } else {
            b.noticed = b.day;
        }
        VillageFolkEntity sk = StoreStaff.stockKeeper(id);
        VillageFolkEntity keeper = Workshop.keeper(id);
        b.countedBy = byStockKeeper ? (sk == null ? "the stock keeper" : sk.displayNameCap()) : (keeper == null ? "the keeper" : keeper.displayNameCap());
        int short_ = 0;
        for (Look k : looks) if (k.onHand() <= k.l().reorder && k.l().rate > 0) short_++;
        entry(level, b, (byStockKeeper ? b.countedBy + " counted the stock: " : b.countedBy + " looked over the shelves: ") + hand.size()
            + " wares on hand, " + short_ + " at or under their reorder point; " + (placed.isEmpty() ? "nothing ordered" : "ordered " + String.join("; ", placed)));
        save(b);
        return placed;
    }

    /**
     * An order placed for a ware (the class comment): from the storehouse if the stores can spare it, else (the
     * stock keeper's, not the keeper's) from the crafters or the smelter, else a notice that the store wants it.
     * Returns the order in words, or null.
     */
    @Nullable
    static String place(ServerLevel level, Villages.Village v, Book b, Ware w, Line l, int need, boolean byStockKeeper) {
        UUID id = v.id();
        int inStores = Market.stock(level, id, w.is());
        // The village's own first: half the stores' food at most at a time, and of anything else only what the
        // village can spare past its own needs (Budget: the guards' blades, the larder, the beds' wool).
        int spare = w.food() ? inStores / 2 : Math.min(inStores, Budget.spare(level, id, w.sample()));
        int lot = Math.max(1, w.lot());
        if (spare >= 1) {
            // As much as it wants, as the stores can spare, a packful (a delivery) at a time, four at the most.
            int n = Math.min(StoreDeliveries.MOST * 4, Math.min(spare, Math.max(need, Math.min(lot, spare))));
            for (int left = n; left > 0; left -= StoreDeliveries.MOST) {
                int k = Math.min(StoreDeliveries.MOST, left);
                StoreDeliveries.Delivery d = StoreDeliveries.queue(level, id, w.key(), w.is(), w.sample(), k, false);
                Order o = new Order(nextOrder++, w.key(), Route.STOREHOUSE, k, b.day);
                o.delivery = d;
                d.orderId = o.id;
                b.orders.add(o);
            }
            supplied(b, w.key());
            l.lastOrder = n + " " + Route.STOREHOUSE.words + ", day " + b.day;
            return Bench.words(w.sample().getItem(), n) + " " + Route.STOREHOUSE.words;
        }
        if (!byStockKeeper) return null;
        // The crafters make what sells (the shop's own shelves they keep made anyway: Workshop).
        if (l.rate > 0 && w.make() != null && !w.food() && !RecipeBook.waysFor(level, w.make()).isEmpty() && Workshop.refuse(level, v, w.make()).isEmpty()) {
            int keep = Math.max(1, Math.min(16, l.upTo));
            String said = Workshop.order(level, v, w.make(), keep, ORDERED_BY);
            if (said.startsWith("On the workshop's book")) {
                // What wants firing goes by the smelter first (Workshop.forTheSmelter, when one is at work).
                Route route = Route.CRAFTERS;
                if (Workshop.smelterAtWork(id)) {
                    Bench.Plan p = Bench.plan(level, v, w.make(), 1, Bench.handOf(level, v, null, Store.buildingForShop(id)));
                    for (Bench.Step st : p.steps) if (st.fire() == RecipeBook.Fire.FURNACE) { route = Route.SMELTER; break; }
                }
                Order o = new Order(nextOrder++, w.key(), route, Math.max(1, need), b.day);
                b.orders.add(o);
                supplied(b, w.key());
                l.lastOrder = need + " " + route.words + ", day " + b.day;
                return Bench.words(w.sample().getItem(), need) + " " + route.words;
            }
        }
        // Nothing to be had: wanted, if folk have been coming for it.
        if (Line.week(l.sold) + Line.week(l.missed) > 0) {
            b.wanted.put(w.key(), Math.max(need, b.wanted.getOrDefault(w.key(), 0)));
            boolean open = false;
            for (Order o : b.orders) if (!o.done && o.key.equals(w.key()) && o.route == Route.WANTED) open = true;
            if (!open) b.orders.add(new Order(nextOrder++, w.key(), Route.WANTED, need, b.day));
            l.lastOrder = "wanted, day " + b.day;
            return Stockroom.plural(w.key()) + " wanted (none to be had)";
        }
        return null;
    }

    /** A ware that was wanted is to be had again: its notice comes down. */
    private static void supplied(Book b, String key) {
        b.wanted.remove(key);
        for (Order o : b.orders) if (!o.done && o.key.equals(key) && o.route == Route.WANTED) o.done = true;
    }

    /** The margins set afresh from the count (the class comment). */
    private static void markups(Book b, List<Line> lines, Map<String, Integer> hand) {
        for (Line l : lines) {
            boolean food = FOOD.stream().anyMatch(it -> Stockroom.key(new ItemStack(it)).equals(l.key));
            double most = food ? FOOD_MOST_MARKUP : MOST_MARKUP;
            boolean soldOut = l.outs[1] > 0 || l.onHand == 0 && l.sold[1] > 0;
            long since = l.lastSale >= 0 ? b.day - l.lastSale : l.lastIn >= 0 ? b.day - l.lastIn : 0;
            boolean sits = l.onHand > 0 && since >= 3;
            if (soldOut) l.markup = Math.min(most, l.markup + STEP);
            else if (sits) l.markup = Math.max(LEAST_MARKUP, l.markup - STEP);
            else l.markup += (BASE_MARKUP - l.markup) * 0.2;
            l.markup = Math.max(LEAST_MARKUP, Math.min(most, l.markup));
        }
    }

    /** What has sat too long goes back to the stores (the class comment). */
    private static void returns(ServerLevel level, Villages.Village v, Book b, List<Ware> range, Map<String, Integer> hand) {
        Map<String, Ware> byKey = new HashMap<>();
        for (Ware w : range) byKey.put(w.key(), w);
        for (Map.Entry<String, Integer> e : hand.entrySet()) {
            String key = e.getKey();
            int onHand = e.getValue();
            if (onHand <= 0 || StoreDeliveries.inFlight(v.id(), key, true) > 0) continue;
            Line l = b.line(key);
            Ware w = byKey.get(key);
            boolean food = w != null ? w.food() : Stockroom.sampleOf(key).get(DataComponents.FOOD) != null;
            int keep = Math.max(w == null ? 0 : l.upTo, 1);
            long since = l.lastSale >= 0 ? b.day - l.lastSale : l.lastIn >= 0 ? b.day - l.lastIn : 0;
            boolean stale = food ? l.rate > 0 && onHand / l.rate > FOOD_COVER_MOST || l.rate <= 0 && since >= RETURN_IDLE : since >= RETURN_IDLE;
            if (!stale || onHand <= keep) continue;
            int n = Math.min(StoreDeliveries.MOST, onHand - keep);
            ItemStack sample = Stockroom.sampleOf(key);
            if (sample.isEmpty()) continue;
            StoreDeliveries.Delivery d = StoreDeliveries.queue(level, v.id(), key, w != null ? w.is() : Stockroom.matcher(key), sample, n, true);
            Order o = new Order(nextOrder++, key, Route.RETURN, n, b.day);
            o.delivery = d;
            b.orders.add(o);
            entry(level, b, "sending back " + Bench.words(sample.getItem(), n) + ": unsold " + since + (since == 1 ? " day" : " days"));
        }
    }

    /** The midday look at the food: a food under its reorder point (the morning's) ordered up to its level. */
    static List<String> topUpFood(ServerLevel level, Villages.Village v, Book b) {
        List<String> placed = new ArrayList<>();
        Map<String, Integer> hand = Store.onHand(level, v.id());
        for (Ware w : range(level, v, b)) {
            if (!w.food()) continue;
            Line l = b.line(w.key());
            int onHand = hand.getOrDefault(w.key(), 0), flight = inFlight(b, w.key());
            l.onHand = onHand;
            if (l.rate <= 0 || onHand + flight > l.reorder || l.upTo - onHand - flight <= 0) continue;
            String o = place(level, v, b, w, l, l.upTo - onHand - flight, true);
            if (o != null) placed.add(o);
        }
        if (!placed.isEmpty()) {
            entry(level, b, "the food looked at again after the midday meal: ordered " + String.join("; ", placed));
            save(b);
        }
        return placed;
    }

    /**
     * The orders the crafters are late with chased (twice, then given up as wanted), and the keeper told. At
     * most once a minute. Returns how many were chased.
     */
    static int chase(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity by) {
        Book b = book(level, v.id());
        long now = level.getGameTime();
        if (now - b.chasedAt < 1200L && now >= b.chasedAt) return 0;
        b.chasedAt = now;
        int chased = 0;
        for (Order o : b.orders) {
            if (o.done || (o.route != Route.CRAFTERS && o.route != Route.SMELTER)) continue;
            if (b.day - o.day < (long) LATE_DAYS * (o.chased + 1)) continue;
            String name = Stockroom.plural(o.key);
            if (o.chased >= CHASES) {
                o.done = true;
                b.wanted.put(o.key, Math.max(1, o.count - o.arrived));
                b.orders.add(new Order(nextOrder++, o.key, Route.WANTED, Math.max(1, o.count - o.arrived), b.day));
                entry(level, b, "gave up on the crafters for the " + name + " (ordered day " + o.day + "): wanted");
                continue;
            }
            o.chased++;
            chased++;
            ItemStack sample = Stockroom.sampleOf(o.key);
            Line l = b.line(o.key);
            if (!sample.isEmpty()) Workshop.order(level, v, sample.getItem(), Math.max(1, Math.min(16, Math.max(l.upTo, o.count))), ORDERED_BY);
            entry(level, b, "chased the crafters for the " + name + " ordered day " + o.day + " (" + (o.count - o.arrived) + " still to come)");
            if (by != null) {
                VillageFolkEntity keeper = Workshop.keeper(v.id());
                FolkTalk.speak(by, FolkTalk.pick(level.getRandom(), "The " + name + " I ordered on day " + o.day + " — still not made?"
                        + (keeper != null && keeper != by ? " " + keeper.displayNameCap() + ", a word with your bench, please." : ""),
                    "We're short of " + name + ", and the bench has had the order two days. Chop chop!"));
            }
        }
        if (chased > 0) save(b);
        return chased;
    }

    // ------------------------------------------------------------------ the town's round

    /**
     * The book's part of the shop's round (Store.tick): the count, if the stock keeper has not got to it by
     * mid-morning (it does it at its desk), or the keeper's look at the shelves with no stock keeper; the late
     * orders chased; and on the first day of the week, the week told.
     */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Book b = book(level, id);
        long t = level.getDayTime() % 24000L;
        VillageFolkEntity sk = StoreStaff.stockKeeper(id);
        if (t >= 5000L && t < 12000L) {
            if (sk != null && b.counted != b.day) takeStock(level, v, true);
            else if (sk == null && b.noticed != b.day && b.counted != b.day) takeStock(level, v, false);
        }
        if (sk != null) chase(level, v, null);
        // After the midday meal the stock keeper looks at the food again: what has gone under its reorder point
        // since the morning is ordered then, not tomorrow.
        if (sk != null && b.counted == b.day && b.topped != b.day && t >= 7000L && t < 12000L) {
            b.topped = b.day;
            topUpFood(level, v, b);
        }
        if (b.day % WEEK == 0 && b.told != b.day && b.day - b.opened >= 3) {
            b.told = b.day;
            String week = weekLine(level, id);
            if (week != null) Villages.tell(id, b.day, week);
            save(b);
        }
    }

    /** "the store sold 140 loaves of bread this week; out of iron pickaxes twice", or null with nothing sold. */
    @Nullable
    static String weekLine(ServerLevel level, UUID village) {
        Book b = book(level, village);
        Line best = null, worst = null;
        for (Line l : b.lines.values()) {
            if (Line.week(l.sold) > 0 && (best == null || Line.week(l.sold) > Line.week(best.sold))) best = l;
            if (Line.week(l.outs) > 0 && (worst == null || Line.week(l.outs) > Line.week(worst.outs))) worst = l;
        }
        if (best == null) return null;
        String place = Store.stands(village) ? "the store" : "the shop";
        StringBuilder sb = new StringBuilder(place).append(" sold ").append(lot(best.key, Line.week(best.sold))).append(" this week");
        if (worst != null) {
            int n = Line.week(worst.outs);
            sb.append("; out of ").append(Stockroom.plural(worst.key)).append(' ').append(times(n));
        }
        return sb.toString();
    }

    private static String times(int n) {
        return n == 1 ? "once" : n == 2 ? "twice" : Storekeeping.words(n) + " times";
    }

    /** "140 loaves of bread", "12 torches", "3 beds". */
    static String lot(String key, int n) {
        if (key.equals("minecraft:bread")) return n + (n == 1 ? " loaf" : " loaves") + " of bread";
        return n + " " + (n == 1 ? Stockroom.nameOf(key).toLowerCase(Locale.ROOT) : Stockroom.plural(key));
    }

    // ------------------------------------------------------------------ the stock keeper at work

    /** How long the morning's count takes, in the stockroom (ticks). */
    static final long COUNT_TICKS = 300L;

    /**
     * A beat of the stock keeper's work (StoreStaff.work): of a morning, to the stockroom to count the stock (a
     * look in each chest) and the book written up; then a delivery nobody has taken fetched itself; the late
     * orders chased; and otherwise at its desk in the office with the book.
     */
    static boolean work(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return false;
        Book b = book(level, id);
        long t = level.getDayTime() % 24000L;
        if (b.counted == b.day) COUNTING.remove(f.getUUID());          // counted (at its desk, if it never got there)
        if (b.counted != b.day && t >= 1000L && t < 12000L) {
            BlockPos spot = Store.stockroomSpot(level, id);
            if (spot == null) {
                takeStock(level, v, true);
                return false;
            }
            if (f.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5) > 3.0 * 3.0) {
                if (f.getNavigation().isDone()) f.walkTo(spot, 0.9D);
                DOING.put(f.getUUID(), "Going to the stockroom to count the stock");
                return true;
            }
            long started = COUNTING.computeIfAbsent(f.getUUID(), k -> level.getGameTime());
            if (level.getGameTime() - started < COUNT_TICKS && level.getGameTime() >= started) {
                f.getNavigation().stop();
                Ledger.Building main = Store.main(id);
                List<BlockPos> stock = main == null ? List.of() : Store.layout(main).stockroom();
                if (!stock.isEmpty()) {
                    BlockPos look = stock.get((int) ((level.getGameTime() / 40L) % stock.size()));
                    f.getLookControl().setLookAt(look.getX() + 0.5, look.getY() + 0.5, look.getZ() + 0.5);
                }
                if (level.getGameTime() % 20L == 0L) f.swing(InteractionHand.MAIN_HAND);
                DOING.put(f.getUUID(), "Counting the stock in the stockroom");
                return true;
            }
            COUNTING.remove(f.getUUID());
            List<String> placed = takeStock(level, v, true);
            f.brain("counted the stock: " + (placed.isEmpty() ? "nothing to order" : String.join("; ", placed)));
            FolkTalk.speak(f, placed.isEmpty()
                ? FolkTalk.pick(f.getRandom(), "Everything's in. Not a shelf short this morning.", "Counted. We'll do, today.")
                : FolkTalk.pick(f.getRandom(), "Right: " + placed.get(0) + (placed.size() > 1 ? ", and " + (placed.size() - 1) + " more" : "") + ".",
                    placed.size() + (placed.size() == 1 ? " order" : " orders") + " out this morning. The bread first, always."));
            return true;
        }
        // A delivery nobody has taken: fetched by hand (sooner with no courier in the town to wait for).
        long wait = StoreDeliveries.couriers(id) ? StoreDeliveries.KEEPER_WAIT : 0L;
        if (StoreDeliveries.fetch(f, level, wait)) {
            String d = StoreDeliveries.doing(f);
            if (d != null) DOING.put(f.getUUID(), d);
            return true;
        }
        if (chase(level, v, f) > 0) DOING.put(f.getUUID(), "Chasing the crafters for a late order");
        // At the desk with the book.
        Ledger.Building main = Store.main(id);
        BlockPos desk = main == null ? null : Store.layout(main).desk();
        if (desk == null && main != null && !Store.layout(main).stockroom().isEmpty()) desk = Store.layout(main).stockroom().get(0);
        if (desk == null) return false;
        BlockPos stand = Trades.floorSpot(level, desk, 2);
        if (stand != null && f.distanceToSqr(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5) > 2.5 * 2.5) {
            if (f.getNavigation().isDone()) f.walkTo(stand, 0.8D);
            DOING.put(f.getUUID(), "Going up to the office with the stock book");
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(desk.getX() + 0.5, desk.getY() + 0.8, desk.getZ() + 0.5);
        DOING.put(f.getUUID(), "At the desk with the stock book");
        if (f.getRandom().nextInt(2400) == 0) {
            Line low = lowest(b);
            if (low != null) FolkTalk.speak(f, Stockroom.nameOf(low.key) + ": " + String.format(Locale.ROOT, "%.1f", low.cover)
                + " days' cover. " + (low.onHand <= low.reorder ? "On order." : "We'll do."));
        }
        return true;
    }

    /** The ware with the least cover at the last count, that sells. */
    @Nullable
    private static Line lowest(Book b) {
        Line low = null;
        for (Line l : b.lines.values()) if (l.rate > 0 && (low == null || l.cover < low.cover)) low = l;
        return low;
    }

    @Nullable
    static String doing(VillageFolkEntity f) {
        String d = StoreDeliveries.doing(f);
        return d != null ? d : DOING.get(f.getUUID());
    }

    // ------------------------------------------------------------------ shown

    /** The week's stock-outs at the shop: how the stock keeper is judged (and paid: ShopRoles). */
    public static int stockOuts(ServerLevel level, UUID village) {
        int n = 0;
        for (Line l : book(level, village).lines.values()) n += Line.week(l.outs);
        return n;
    }

    /** Everything sold at the shop this week. */
    static int soldThisWeek(ServerLevel level, UUID village) {
        int n = 0;
        for (Line l : book(level, village).lines.values()) n += Line.week(l.sold);
        return n;
    }

    /** The margin over the town's price on this ware (the class comment). */
    static double markup(UUID village, String key) {
        Book b = BOOKS.get(village);
        Line l = b == null ? null : b.lines.get(key);
        return l == null ? BASE_MARKUP : l.markup;
    }

    /** The stock keeper's card line: its count, its orders, and how it stands by its stock-outs. */
    static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || !(f.level() instanceof ServerLevel level)) return "";
        Book b = book(level, id);
        int out = stockOuts(level, id), open = 0;
        for (Order o : b.orders) if (!o.done && o.route != Route.WANTED) open++;
        String grade = out <= 2 ? "a tidy book" : out <= 6 ? "fair" : "too many";
        return "keeps " + (Store.stands(id) ? "the store's" : "the shop's") + " stock book: " + (b.counted == b.day ? "counted this morning" : "not counted yet today")
            + "; " + open + (open == 1 ? " order" : " orders") + " out; " + out + (out == 1 ? " stock-out" : " stock-outs") + " this week (" + grade + ")";
    }

    /** The stock book for the town's books and /village stock: a row a ware worth a row, the orders, the day. */
    public static CompoundTag report(ServerLevel level, UUID village) {
        CompoundTag t = new CompoundTag();
        Book b = book(level, village);
        VillageFolkEntity sk = StoreStaff.stockKeeper(village);
        t.putString("place", Store.stands(village) ? "the store" : "the shop");
        t.putString("keeper", sk == null ? "" : sk.displayNameCap());
        t.putLong("counted", b.counted);
        t.putString("countedBy", b.countedBy);
        t.putInt("sold7", soldThisWeek(level, village));
        t.putInt("outs7", stockOuts(level, village));
        t.putInt("soldToday", b.lines.values().stream().mapToInt(l -> l.sold[0]).sum());
        t.putInt("soldYesterday", b.lines.values().stream().mapToInt(l -> l.sold[1]).sum());
        t.putInt("inToday", b.lines.values().stream().mapToInt(l -> l.in[0] + l.made[0]).sum());
        Stockroom.Book shop = Stockroom.book(level, village, Stockroom.Seller.SHOP);
        int coin = 0;
        for (Stockroom.Line l : shop.lines.values()) coin += Stockroom.Line.week(l.coin);
        t.putInt("coin7", coin);
        Map<String, Integer> hand = Store.onHand(level, village);
        ListTag rows = new ListTag();
        List<Line> lines = new ArrayList<>(b.lines.values());
        lines.sort(Comparator.comparingDouble((Line l) -> l.rate > 0 ? l.cover : 999).thenComparing(l -> l.key));
        for (Line l : lines) {
            int onHand = hand.getOrDefault(l.key, 0);
            int flight = inFlight(b, l.key);
            if (onHand == 0 && flight == 0 && Line.week(l.sold) + Line.week(l.outs) + l.upTo == 0) continue;
            ItemStack one = Stockroom.sampleOf(l.key);
            if (one.isEmpty()) continue;
            CompoundTag r = new CompoundTag();
            r.putString("id", l.key);
            r.putString("item", BuiltInRegistries.ITEM.getKey(one.getItem()).toString());
            r.putString("name", Stockroom.nameOf(l.key));
            r.putInt("onHand", onHand);
            r.putDouble("rate", l.rate);
            r.putDouble("cover", l.rate > 0 ? onHand / l.rate : -1);
            r.putInt("reorder", l.reorder);
            r.putInt("upTo", l.upTo);
            r.putInt("inFlight", flight);
            r.putInt("sold7", Line.week(l.sold));
            r.putInt("outs7", Line.week(l.outs));
            r.putInt("inToday", l.in[0] + l.made[0]);
            r.putInt("price", ShopStock.price(level, village, one));
            r.putDouble("markup", l.markup);
            r.putString("order", l.lastOrder);
            rows.add(r);
        }
        t.put("wares", rows);
        ListTag orders = new ListTag();
        String place = Store.stands(village) ? "the store" : "the shop";
        for (Order o : b.orders) {
            if (o.done) continue;
            orders.add(StringTag.valueOf(Bench.words(Stockroom.sampleOf(o.key).getItem(), Math.max(1, o.count - o.arrived)) + " " + o.route.words
                + " (day " + o.day + (o.chased > 0 ? ", chased " + times(o.chased) : "") + ")"));
        }
        for (String d : StoreDeliveries.lines(village)) orders.add(StringTag.valueOf("on the way: " + d));
        t.put("orders", orders);
        ListTag wanted = new ListTag();
        for (Map.Entry<String, Integer> e : b.wanted.entrySet()) wanted.add(StringTag.valueOf(Stockroom.plural(e.getKey())));
        t.put("wanted", wanted);
        ListTag day = new ListTag();
        for (String e : b.entries) day.add(StringTag.valueOf(e));
        t.put("day", day);
        ListTag staff = new ListTag();
        for (String s : StoreStaff.lines(village)) staff.add(StringTag.valueOf(s));
        t.put("staff", staff);
        t.putString("week", String.valueOf(weekLine(level, village)));
        return t;
    }

    /** The stock book as a page of text (/village stock). */
    public static String page(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (!Workshop.stands(id) && !Store.stands(id)) return "No shop yet, and so no stock book: the shop comes with a town of eighteen in the Iron Age.";
        CompoundTag t = report(level, id);
        StringBuilder sb = new StringBuilder();
        String place = t.getString("place");
        sb.append(cap(place)).append("'s stock book, ").append(t.getString("keeper").isEmpty() ? "with no stock keeper (the keeper notices what runs out)"
            : "kept by " + t.getString("keeper") + ", the stock keeper").append(t.getLong("counted") >= 0 ? "; counted day " + t.getLong("counted") + " by "
            + t.getString("countedBy") : "; not counted yet").append('\n');
        sb.append("Sold today ").append(t.getInt("soldToday")).append(", yesterday ").append(t.getInt("soldYesterday")).append(", this week ")
            .append(t.getInt("sold7")).append(" (").append(t.getInt("coin7")).append(" coin); stock-outs this week ").append(t.getInt("outs7"))
            .append("; in today ").append(t.getInt("inToday")).append('\n');
        ListTag rows = t.getList("wares", net.minecraft.nbt.Tag.TAG_COMPOUND);
        if (rows.size() > 0) sb.append("Ware: on hand, cover, reorder at / up to, sold this week, stock-outs, in flight, price\n");
        for (int i = 0; i < Math.min(20, rows.size()); i++) {
            CompoundTag r = rows.getCompound(i);
            double cover = r.getDouble("cover");
            sb.append("  ").append(r.getString("name")).append(": ").append(r.getInt("onHand")).append(", ")
                .append(cover < 0 ? "-" : String.format(Locale.ROOT, "%.1fd", cover)).append(", ").append(r.getInt("reorder")).append('/')
                .append(r.getInt("upTo")).append(", sold ").append(r.getInt("sold7")).append(", out ").append(r.getInt("outs7"))
                .append(r.getInt("inFlight") > 0 ? ", " + r.getInt("inFlight") + " coming" : "").append(", ").append(r.getInt("price")).append("c")
                .append(String.format(Locale.ROOT, " (+%d%%)", Math.round((r.getDouble("markup") - 1.0) * 100))).append('\n');
        }
        String[] orders = strings(t, "orders"), wanted = strings(t, "wanted"), day = strings(t, "day"), staff = strings(t, "staff");
        if (orders.length > 0) sb.append("Orders in flight: ").append(String.join("; ", orders)).append('\n');
        if (wanted.length > 0) sb.append("Wanted (none to be had): ").append(String.join(", ", wanted)).append('\n');
        for (int i = Math.max(0, day.length - 6); i < day.length; i++) sb.append("  ").append(day[i]).append('\n');
        if (staff.length > 0) sb.append("Staff: ").append(String.join("; ", staff)).append('\n');
        return sb.toString().stripTrailing();
    }

    private static String[] strings(CompoundTag t, String key) {
        ListTag l = t.getList(key, net.minecraft.nbt.Tag.TAG_STRING);
        String[] out = new String[l.size()];
        for (int i = 0; i < l.size(); i++) out[i] = l.getString(i);
        return out;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The board's line: yesterday's sales and what the store is out of. Null with no shop open. */
    @Nullable
    public static String boardLine(ServerLevel level, UUID village) {
        if (!ShopStock.open(village)) return null;
        Book b = book(level, village);
        int sold = 0;
        for (Line l : b.lines.values()) sold += l.sold[1];
        List<String> out = new ArrayList<>();
        Map<String, Integer> hand = Store.onHand(level, village);
        for (Line l : b.lines.values()) {
            if (l.rate > 0 && hand.getOrDefault(l.key, 0) == 0 && out.size() < 3) out.add(Stockroom.plural(l.key));
        }
        String place = Store.stands(village) ? "The store" : "The shop";
        return place + ": " + sold + " sold yesterday" + (out.isEmpty() ? ", every shelf stocked" : "; out of " + String.join(", ", out))
            + (b.wanted.isEmpty() ? "" : "; wanted: " + String.join(", ", b.wanted.keySet().stream().limit(3).map(Stockroom::plural).toList()));
    }

    /** The gazette's piece on the store: yesterday's best sellers, what ran out, what it wants. Null with nothing to say. */
    @Nullable
    public static String gazette(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (!ShopStock.open(id)) return null;
        Book b = book(level, id);
        List<Line> sold = new ArrayList<>();
        for (Line l : b.lines.values()) if (l.sold[1] > 0) sold.add(l);
        sold.sort(Comparator.comparingInt((Line l) -> -l.sold[1]));
        StringBuilder sb = new StringBuilder("§l").append(Store.stands(id) ? "The store" : "The shop").append("§r");
        if (sold.isEmpty()) sb.append("\nA quiet day at the counter.");
        else {
            int total = 0;
            for (Line l : sold) total += l.sold[1];
            List<String> best = new ArrayList<>();
            for (int i = 0; i < Math.min(3, sold.size()); i++) best.add(lot(sold.get(i).key, sold.get(i).sold[1]));
            sb.append("\nSold ").append(total).append(": ").append(String.join(", ", best)).append('.');
        }
        List<String> outs = new ArrayList<>();
        for (Line l : b.lines.values()) if (l.outs[1] > 0 && outs.size() < 3) outs.add(Stockroom.plural(l.key) + " " + times(l.outs[1]));
        if (!outs.isEmpty()) sb.append("\nRan out of ").append(String.join("; ", outs)).append('.');
        if (!b.wanted.isEmpty()) sb.append("\nWanted: ").append(String.join(", ", b.wanted.keySet().stream().limit(3).map(Stockroom::plural).toList())).append('.');
        String week = b.day % WEEK == 0 ? weekLine(level, id) : null;
        if (week != null) sb.append("\nThis week ").append(week).append('.');
        return sb.toString();
    }

    /** The store's wants for the quest board (Stockroom.asks): the ware wanted most, if any. */
    static List<Stockroom.Ask> asks(ServerLevel level, Villages.Village v) {
        List<Stockroom.Ask> out = new ArrayList<>();
        Book b = BOOKS.get(v.id());
        if (b == null || b.wanted.isEmpty() || !ShopStock.open(v.id())) return out;
        Map.Entry<String, Integer> top = null;
        for (Map.Entry<String, Integer> e : b.wanted.entrySet()) if (top == null || e.getValue() > top.getValue()) top = e;
        ItemStack one = Stockroom.sampleOf(top.getKey());
        if (one.isEmpty()) return out;
        net.minecraft.resources.ResourceLocation rl = BuiltInRegistries.ITEM.getKey(one.getItem());
        if (!rl.getNamespace().equals("minecraft")) return out;
        out.add(new Stockroom.Ask(rl.getPath(), Math.max(1, Math.min(32, top.getValue())), (Store.stands(v.id()) ? "the store's " : "the shop's ")
            + "shelves (" + Stockroom.plural(top.getKey()) + ")"));
        return out;
    }

    /** For "What is the village short of?": what the store wants and cannot get. */
    static List<String> shortages(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        Book b = BOOKS.get(village);
        if (b == null || b.wanted.isEmpty() || !ShopStock.open(village)) return out;
        List<String> names = new ArrayList<>();
        for (String k : b.wanted.keySet()) if (names.size() < 3) names.add(Stockroom.plural(k));
        out.add((Store.stands(village) ? "the store" : "the shop") + " wants " + String.join(", ", names) + " and none are to be had");
        return out;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: a ware's line as "onHand/reorder/upTo/sold7/outs7/markup". */
    public static String lineForTests(ServerLevel level, UUID village, ItemStack one) {
        Line l = book(level, village).line(Stockroom.key(one));
        return l.onHand + "/" + l.reorder + "/" + l.upTo + "/" + Line.week(l.sold) + "/" + Line.week(l.outs) + "/" + String.format(Locale.ROOT, "%.2f", l.markup);
    }

    /** Tests: the orders in flight, as "key:route:count". */
    public static List<String> ordersForTests(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        for (Order o : book(level, village).orders) if (!o.done) out.add(o.key + ":" + o.route + ":" + o.count);
        return out;
    }

    /** Tests: so many sold of this on each of the last few days (today first), as if folk had bought them. */
    public static void soldForTests(ServerLevel level, UUID village, ItemStack one, int... perDay) {
        Book b = book(level, village);
        Line l = b.line(Stockroom.key(one));
        for (int i = 0; i < perDay.length && i < WEEK; i++) l.sold[i] += perDay[i];
        b.opened = Math.min(b.opened, b.day - Math.max(2, perDay.length));
        if (perDay.length > 0) l.lastSale = b.day;
        save(b);
    }
}
