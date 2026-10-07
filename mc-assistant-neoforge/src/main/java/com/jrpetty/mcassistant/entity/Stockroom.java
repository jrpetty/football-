package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
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
 * The sellers' books: what each of the village's sellers has on its shelves, what sells, what it makes,
 * and what it means to make next.
 * <ul>
 * <li><b>The sellers.</b> The shop (its keeper makes what a house wants at its bench, and sells the
 *     crafts' work beside it), the café (the cook's food and drinks), the tavern (the cook's drinks,
 *     poured for a round or bought of an evening), the market's stalls (the folk's market-day treats,
 *     players' lots), and the stores (the storekeeper, who sells what the village can spare and makes
 *     to order what it has not got).</li>
 * <li><b>The books.</b> For every ware, a week of what was sold (to folk and to players), what was
 *     asked for and not there, what was made, and the coin it took; what one cost to make, at the price
 *     list's worth of what went into it; and what the maker is short of to make more. Kept with the
 *     village (its ledger), and turned over day by day.</li>
 * <li><b>How many to keep.</b> For its first two days a seller keeps the usual of each ware. After that
 *     it keeps two and a half days' sales (sales and asks, a day: the week's average, or today's if
 *     today is busier), never fewer than the ware's fewest (one, for most things) nor more than its
 *     shelf holds; a ware nothing has sold of all week is cut to its fewest, and at its fewest no more
 *     is made. What the village lives on (bread, baked potatoes, the roasts, torches) never goes under
 *     the usual, sold or not: the larder eats it.</li>
 * <li><b>What first.</b> The ware whose shelf is emptiest against what it means to keep, that the
 *     stores can run to (Bench: the whole way, from what they hold, and nothing the village keeps back).
 *     Short of the makings, it passes to the next ware, writes down what it is short of, says so (once
 *     a day), and asks the village for it on the quest board.</li>
 * <li><b>Prices.</b> A ware over what the seller means to keep that has not sold for three days is
 *     marked down a tenth, and another tenth every two days after, to four tenths off; a sale ends it.
 *     Nothing a seller makes is sold for less than it cost.</li>
 * </ul>
 * The town's books (the analytics screen) read all of it from {@link #inventoryReport}.
 */
public final class Stockroom {

    private Stockroom() {}

    /** Days of sales kept, today first. */
    static final int WEEK = 7;
    /** Days of sales a seller keeps on its shelves. */
    static final double DAYS_KEPT = 2.5;
    /** The most off a slow ware is marked down, in the hundred. */
    static final int MOST_OFF = 40;
    /** How long a ware the stores could not run to waits before it is tried again (ticks). */
    private static final long RETRY = 600L;

    /** The village's sellers: its name in the books, in words, its building, and the trade that keeps it. */
    public enum Seller {
        SHOP("shop", "the shop", "shop", AssistantEntity.StationTask.SHOP),
        CAFE("cafe", "the café", "cafe", AssistantEntity.StationTask.COOK),
        TAVERN("tavern", "the tavern", "tavern", AssistantEntity.StationTask.COOK),
        MARKET("market", "the market", "market", null),
        STORES("stores", "the stores", "storage", AssistantEntity.StationTask.STORE);

        public final String id;
        public final String words;
        public final String building;
        @Nullable public final AssistantEntity.StationTask keeper;

        Seller(String id, String words, String building, @Nullable AssistantEntity.StationTask keeper) {
            this.id = id;
            this.words = words;
            this.building = building;
            this.keeper = keeper;
        }
    }

    /**
     * A ware a seller keeps: its name in the books, what counts as it, one to show, what is made for it
     * (by the game's recipe; null for one of the seller's own, or one the crafts make), its own recipe
     * if it has one (the café's drinks), how many it usually keeps, the fewest and the most, how many a
     * piece of work makes, and whether the village lives on it (never kept under the usual).
     */
    public record Ware(String key, Predicate<ItemStack> is, ItemStack sample, @Nullable Item make, @Nullable Own own,
                       int usual, int fewest, int most, int batch, boolean staple) {
        public boolean made() { return make != null || own != null; }
    }

    /** A ware of the seller's own recipe: what goes into a batch (out of the stores, or made), and what it makes. */
    public record Own(List<Bench.Want> wants, ItemStack out) {}

    /** A ware made by the game's recipe, of the given item, keyed by it. */
    static Ware ware(Item it, int usual, int fewest, int most, int batch, boolean staple) {
        ItemStack one = new ItemStack(it);
        return new Ware(key(one), s -> s.is(it), one, it, null, usual, fewest, most, batch, staple);
    }

    // ------------------------------------------------------------------ the books

    /** One ware's week: what was sold, asked for and not there, made, and the coin, today first. */
    static final class Line {
        final String key;
        final int[] sold = new int[WEEK];
        final int[] missed = new int[WEEK];
        final int[] made = new int[WEEK];
        final int[] coin = new int[WEEK];
        long lastSale = -1;
        /** What one cost to make here, at the price list's worth of what went in (-1: never made here). */
        double cost = -1;
        /** What the maker is short of to make more ("2 string"), why ("put by for the age"), and the thing. */
        String shortOf = "";
        String why = "";
        @Nullable Item missing;
        int missingCount;
        /** The way it was made last ("2 oak logs, into 8 oak planks, a chest"). */
        String how = "";
        long triedAt = -100000L;

        Line(String key) {
            this.key = key;
        }

        static int week(int[] a) {
            int n = 0;
            for (int x : a) n += x;
            return n;
        }

        /** So many days on: the week moves along. */
        void roll(int days) {
            for (int[] a : new int[][]{ sold, missed, made, coin }) {
                for (int i = WEEK - 1; i >= 0; i--) a[i] = i - days >= 0 ? a[i - days] : 0;
            }
        }
    }

    /** A seller's books: the day its first slot is, the day they were opened, and a line a ware. */
    static final class Book {
        final UUID village;
        final Seller seller;
        long day;
        long opened;
        long grumbled = -1;
        final Map<String, Line> lines = new LinkedHashMap<>();

        Book(UUID village, Seller seller, long today) {
            this.village = village;
            this.seller = seller;
            this.day = today;
            this.opened = today;
        }
    }

    private static final Map<String, Book> BOOKS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        BOOKS.clear();
        Bench.resetForTests();
    }

    static long today(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    /** A seller's books, turned over to today. */
    static Book book(ServerLevel level, UUID village, Seller s) {
        long today = today(level);
        Book b = BOOKS.computeIfAbsent(village + "/" + s.id, k -> load(village, s, today));
        if (today > b.day) {
            int days = (int) Math.min(WEEK, today - b.day);
            for (Line l : b.lines.values()) l.roll(days);
            b.day = today;
            save(b);
        } else if (today < b.day) {
            // The clock set back (a command, a test): the week starts again from here.
            b.day = today;
            b.opened = Math.min(b.opened, today);
        }
        return b;
    }

    static Line line(Book b, String key) {
        return b.lines.computeIfAbsent(key, Line::new);
    }

    private static String note(Seller s) {
        return "stock/" + s.id;
    }

    /** Into the village's ledger: "1;day;opened|key~sold~made~missed~coin~lastSale~cost|...". */
    private static void save(Book b) {
        StringBuilder sb = new StringBuilder("1;").append(b.day).append(';').append(b.opened);
        for (Line l : b.lines.values()) {
            if (Line.week(l.sold) + Line.week(l.made) + Line.week(l.missed) == 0 && l.cost < 0 && l.lastSale < 0) continue;
            sb.append('|').append(l.key).append('~').append(csv(l.sold)).append('~').append(csv(l.made)).append('~')
                .append(csv(l.missed)).append('~').append(csv(l.coin)).append('~').append(l.lastSale).append('~')
                .append(String.format(Locale.ROOT, "%.3f", l.cost));
        }
        Ledger.note(b.village, note(b.seller), sb.toString());
    }

    private static Book load(UUID village, Seller s, long today) {
        Book b = new Book(village, s, today);
        String note = Ledger.note(village, note(s));
        if (note == null || note.isEmpty()) return b;
        try {
            String[] parts = note.split("\\|");
            String[] head = parts[0].split(";");
            b.day = Long.parseLong(head[1]);
            b.opened = Long.parseLong(head[2]);
            for (int i = 1; i < parts.length; i++) {
                String[] f = parts[i].split("~");
                if (f.length < 7) continue;
                Line l = new Line(f[0]);
                parse(f[1], l.sold);
                parse(f[2], l.made);
                parse(f[3], l.missed);
                parse(f[4], l.coin);
                l.lastSale = Long.parseLong(f[5]);
                l.cost = Double.parseDouble(f[6]);
                b.lines.put(l.key, l);
            }
        } catch (RuntimeException e) {
            b = new Book(village, s, today);                   // a torn page: the books start again
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

    // ------------------------------------------------------------------ what a ware is called

    /** A ware's name in the books: a café drink by its name, the colours of a thing as one (candles, beds,
     *  rugs, banners, wool), the brewer's potions together, and anything else by its item. */
    public static String key(ItemStack s) {
        String drink = Cafe.drinkOf(s);
        if (drink != null) return "drink/" + drink;
        if (s.is(Items.POTION) || s.is(Items.SPLASH_POTION) || s.is(Items.LINGERING_POTION)) return "potion";
        if (s.is(ItemTags.CANDLES)) return "candle";
        if (s.is(ItemTags.BEDS)) return "bed";
        if (s.is(ItemTags.WOOL_CARPETS)) return "rug";
        if (s.is(ItemTags.BANNERS)) return "banner";
        if (s.is(ItemTags.WOOL)) return "wool";
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    /** One of a ware, from its name in the books. */
    static ItemStack sampleOf(String key) {
        if (key.startsWith("drink/")) {
            Cafe.Drink d = Cafe.drinkFor(key.substring(6));
            return d == null ? ItemStack.EMPTY : Cafe.drink(d);
        }
        return switch (key) {
            case "potion" -> new ItemStack(Items.POTION);
            case "candle" -> new ItemStack(Items.CANDLE);
            case "bed" -> new ItemStack(Items.WHITE_BED);
            case "rug" -> new ItemStack(Items.WHITE_CARPET);
            case "banner" -> new ItemStack(Items.WHITE_BANNER);
            case "wool" -> new ItemStack(Items.WHITE_WOOL);
            default -> {
                ResourceLocation rl = ResourceLocation.tryParse(key);
                Item it = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
                yield it == Items.AIR ? ItemStack.EMPTY : new ItemStack(it);
            }
        };
    }

    /** What counts as a ware, from its name in the books. */
    static Predicate<ItemStack> matcher(String key) {
        if (key.startsWith("drink/")) {
            String id = key.substring(6);
            return s -> id.equals(Cafe.drinkOf(s));
        }
        return switch (key) {
            case "potion" -> s -> (s.is(Items.POTION) || s.is(Items.SPLASH_POTION) || s.is(Items.LINGERING_POTION)) && !Cafe.isDrink(s);
            case "candle" -> s -> s.is(ItemTags.CANDLES);
            case "bed" -> s -> s.is(ItemTags.BEDS);
            case "rug" -> s -> s.is(ItemTags.WOOL_CARPETS);
            case "banner" -> s -> s.is(ItemTags.BANNERS);
            case "wool" -> s -> s.is(ItemTags.WOOL);
            default -> {
                ItemStack one = sampleOf(key);
                Item it = one.getItem();
                yield s -> !one.isEmpty() && s.is(it);
            }
        };
    }

    /** "Candles", "Apple Cider", "Iron Pickaxe". */
    static String nameOf(String key) {
        return switch (key) {
            case "potion" -> "Potions";
            case "candle" -> "Candles";
            case "bed" -> "Beds";
            case "rug" -> "Rugs";
            case "banner" -> "Banners";
            case "wool" -> "Wool";
            default -> {
                ItemStack one = sampleOf(key);
                yield one.isEmpty() ? key : one.getHoverName().getString();
            }
        };
    }

    /** The wares kept as one whatever their colour (or brew), already named in the plural. */
    private static final java.util.Set<String> FAMILIES = java.util.Set.of("potion", "candle", "bed", "rug", "banner", "wool");

    /** "candles", "apple cider", "iron pickaxes": a ware in a sentence. */
    static String plural(String key) {
        String n = nameOf(key).toLowerCase(Locale.ROOT);
        return key.startsWith("drink/") || FAMILIES.contains(key) ? n : Bench.plural(n);
    }

    // ------------------------------------------------------------------ which seller

    /** The wares a seller makes (or, for the tavern, pours), in this village: the shop's are its workshop's
     *  order book (Workshop: the town's needs, the shelves and what sells, at what the age lets it make). */
    static List<Ware> wares(Seller s, ServerLevel level, UUID village) {
        return s == Seller.SHOP ? Workshop.wares(level, village) : wares(s);
    }

    /** The wares a seller makes (or, for the tavern, pours). */
    static List<Ware> wares(Seller s) {
        return switch (s) {
            case SHOP -> Cafe.shopWares();
            case CAFE -> Cafe.cafeWares();
            case TAVERN -> {
                List<Ware> drinks = new ArrayList<>();
                for (Ware w : Cafe.cafeWares()) if (w.key().startsWith("drink/")) drinks.add(w);
                yield drinks;
            }
            default -> List.of();
        };
    }

    /** Whose sales count toward what a maker keeps: the shop's own and the storekeeper's; the café's,
     *  the tavern's and the market's for the cook. */
    static List<Seller> demandFrom(Seller maker) {
        return switch (maker) {
            case SHOP -> List.of(Seller.SHOP, Seller.STORES);
            case CAFE -> List.of(Seller.CAFE, Seller.TAVERN, Seller.MARKET);
            default -> List.of(maker);
        };
    }

    /** A ware some seller makes, and which: the shop's bench, or the cook's. */
    record Found(Seller maker, Ware ware) {}

    @Nullable
    static Found find(String key) {
        for (Ware w : Cafe.shopWares()) if (w.key().equals(key)) return new Found(Seller.SHOP, w);
        for (Ware w : Cafe.cafeWares()) if (w.key().equals(key)) return new Found(Seller.CAFE, w);
        Ware made = Workshop.wareFor(key);                       // the shop's workshop's own (Workshop)
        if (made != null) return new Found(Seller.SHOP, made);
        return null;
    }

    @Nullable
    static Found find(ItemStack s) {
        if (s.isEmpty()) return null;
        return find(key(s));
    }

    /** Who sold this at a counter: the café's menu, the shop's wares, else the market's stalls. */
    public static Seller sellerFor(ItemStack s) {
        Found f = find(s);
        if (f != null && f.maker() == Seller.CAFE) return Seller.CAFE;
        if (Cafe.shopWorthy(s)) return Seller.SHOP;
        return Seller.MARKET;
    }

    /** The folk who keeps this seller, if one is at work. */
    @Nullable
    static VillageFolkEntity keeperOf(UUID village, Seller s) {
        if (s.keeper == null) return null;
        if (s == Seller.SHOP) return Workshop.keeper(village);       // not one of its hands (Workshop)
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.stationTask() == s.keeper) return f;
        }
        return null;
    }

    // ------------------------------------------------------------------ what is sold, asked for, made

    /** Sold: so many of this, for so much coin in all. */
    public static void sold(ServerLevel level, UUID village, Seller s, ItemStack what, int count, int coin) {
        if (what.isEmpty() || count <= 0) return;
        Book b = book(level, village, s);
        Line l = line(b, key(what));
        l.sold[0] += count;
        l.coin[0] += Math.max(0, coin);
        l.lastSale = b.day;
        save(b);
    }

    /** Asked for and not there: a sale the shelf did not have. It counts toward what is kept, like a sale. */
    public static void missed(ServerLevel level, UUID village, Seller s, ItemStack what) {
        if (what.isEmpty()) return;
        Book b = book(level, village, s);
        line(b, key(what)).missed[0]++;
        save(b);
    }

    /** Made: so many, and what one cost. */
    static void made(Book b, Line l, int n, double each) {
        l.made[0] += n;
        l.cost = l.cost < 0 ? each : 0.6 * l.cost + 0.4 * each;
        save(b);
    }

    /** How many of a ware were sold and asked for, today, and on each of the days before (as far as kept). */
    private static int[] demand(ServerLevel level, UUID village, Seller maker, String key) {
        int[] d = new int[WEEK];
        for (Seller s : demandFrom(maker)) {
            Line l = book(level, village, s).lines.get(key);
            if (l == null) continue;
            for (int i = 0; i < WEEK; i++) d[i] += l.sold[i] + l.missed[i];
        }
        return d;
    }

    /** How many of this ware its maker means to keep (the rules in the class comment). The shop's workshop
     *  (Workshop) keeps nothing the village's age has not come to, and never fewer than the town needs of it
     *  (the watch's armour, the storehouse's rack, an order). */
    public static int target(ServerLevel level, UUID village, Seller maker, Ware w) {
        if (!w.made()) return 0;
        if (maker == Seller.SHOP) {
            if (!Workshop.allows(level, village, w)) return 0;
            return Math.max(booked(level, village, maker, w), Workshop.need(level, village, w.key()));
        }
        return booked(level, village, maker, w);
    }

    /** What the books say to keep: the usual at first, then two and a half days' sales. */
    private static int booked(ServerLevel level, UUID village, Seller maker, Ware w) {
        Book b = book(level, village, maker);
        int closed = (int) Math.min(WEEK - 1, Math.max(0, b.day - b.opened));
        if (closed < 2) return w.usual();
        int[] d = demand(level, village, maker, w.key());
        int past = 0;
        for (int i = 1; i <= closed; i++) past += d[i];
        double rate = Math.max(past / (double) closed, d[0]);
        int t = past + d[0] == 0 ? w.fewest() : (int) Math.ceil(rate * DAYS_KEPT - 1e-9);
        t = Math.max(w.fewest(), Math.min(w.most(), t));
        if (w.staple()) t = Math.max(t, w.usual());
        return t;
    }

    /** As target, for whatever ware this is (the tests and the screens): 0 if no seller makes it. */
    public static int target(ServerLevel level, UUID village, ItemStack s) {
        Found f = find(s);
        return f == null ? 0 : target(level, village, f.maker(), f.ware());
    }

    /** How much a slow ware is marked down, in the hundred: over what its maker means to keep, and unsold
     *  three days, a tenth; another every two days more, to four tenths. */
    static int markdown(ServerLevel level, UUID village, Seller maker, Ware w) {
        if (!w.made()) return 0;
        int have = Market.stock(level, village, w.is())
            + (maker == Seller.SHOP ? ShopStock.held(level, village, w.is()) : 0);   // [econ-store] its stockroom too
        if (have <= Math.max(1, target(level, village, maker, w))) return 0;
        Book b = book(level, village, maker);
        long last = b.opened;
        for (Seller s : demandFrom(maker)) {
            Line l = book(level, village, s).lines.get(w.key());
            if (l != null && l.lastSale > last) last = l.lastSale;
        }
        long idle = b.day - last;
        if (idle < 3) return 0;
        return (int) Math.min(MOST_OFF, 10 + 10 * ((idle - 3) / 2));
    }

    /** What one of a ware cost: what its maker paid at the price list's worth, or the list's own worth
     *  of it if it was never made here (nothing, for a drink never made). */
    static double costEach(ServerLevel level, UUID village, Seller maker, Ware w) {
        Line l = book(level, village, maker).lines.get(w.key());
        if (l != null && l.cost >= 0) return l.cost;
        if (w.own() != null) return 0;
        Item it = w.sample().getItem();
        return Prices.known(it) ? Prices.each(it) : 0;
    }

    /** What a seller asks for a lot of what it makes, from what the market would ask: marked down if it is
     *  slow, and never under what it cost. Anything else, the market's price as it is. */
    public static int asked(ServerLevel level, UUID village, ItemStack shown, int price, int lot) {
        Found f = find(shown);
        if (f == null || !f.ware().made()) return price;
        int off = markdown(level, village, f.maker(), f.ware());
        int p = off > 0 ? (int) Math.round(price * (100 - off) / 100.0) : price;
        double cost = costEach(level, village, f.maker(), f.ware());
        int floor = cost > 0 ? (int) Math.ceil(cost * Math.max(1, lot) - 1e-6) : 1;
        return Math.max(1, Math.max(floor, p));
    }

    // ------------------------------------------------------------------ the work

    /** What a piece of work made, and the way it went. */
    record Made(ItemStack out, Bench.Plan plan) {}

    /**
     * A seller's piece of work at its bench: of the wares it makes, the one whose shelf is emptiest
     * against what it means to keep, that the stores can run to, made the whole way through (Bench). A
     * ware it is short of the makings for is passed over for the next, and what it is short of written
     * in the books; if nothing could be made, it says what it is short of (once a day). Null if it made
     * nothing.
     */
    @Nullable
    static Made restock(ServerLevel level, Villages.Village v, Seller s, List<Ware> wares, @Nullable VillageFolkEntity f) {
        Book b = book(level, v.id(), s);
        long now = level.getGameTime();
        List<Ware> order = new ArrayList<>();
        Map<String, Double> fill = new HashMap<>();
        for (Ware w : wares) {
            if (!w.made()) continue;
            int target = target(level, v.id(), s, w);
            int have = Market.stock(level, v.id(), w.is())
                + (s == Seller.SHOP ? ShopStock.held(level, v.id(), w.is()) : 0);   // [econ-store] its stockroom too
            if (target <= 0 || have >= target) {
                Line l = b.lines.get(w.key());
                if (l != null) clearShort(l);
                continue;
            }
            order.add(w);
            fill.put(w.key(), have / (double) target);
        }
        if (order.isEmpty()) return null;
        order.sort(Comparator.comparingDouble(w -> fill.get(w.key())));     // stable: the list's order breaks ties
        Bench.Hand hand = Bench.handOf(level, v, f, s.building);
        Line firstShort = null;
        String firstWare = null;
        for (Ware w : order) {
            Line l = line(b, w.key());
            if (!l.shortOf.isEmpty() && now - l.triedAt < RETRY && now >= l.triedAt) {
                if (firstShort == null) { firstShort = l; firstWare = w.key(); }
                continue;
            }
            l.triedAt = now;
            Bench.Plan p = w.own() != null ? Bench.plan(level, v, w.own().wants(), hand) : Bench.plan(level, v, w.make(), w.batch(), hand);
            if (!p.ok()) {
                l.shortOf = p.shortOf == null ? "" : p.shortOf;
                l.why = p.why;
                l.missing = p.missing;
                l.missingCount = p.missingCount;
                if (firstShort == null) { firstShort = l; firstWare = w.key(); }
                continue;
            }
            // The shop's firing is the smeltery's, with a smelter at work (Workshop): the piece waits for it.
            String fired = s == Seller.SHOP ? Workshop.forTheSmelter(level, v, p) : null;
            if (fired != null) {
                l.shortOf = fired;
                l.why = Workshop.WAITS_ON_THE_SMELTER;
                l.missing = null;
                l.missingCount = 0;
                continue;
            }
            ItemStack out;
            if (w.own() != null) {
                if (!Bench.take(level, v, p, f)) continue;
                out = w.own().out().copy();
                if (out.getMaxStackSize() == 1) {
                    for (int i = 0; i < out.getCount(); i++) Crafts.store(level, v, out.copyWithCount(1));
                } else {
                    Crafts.store(level, v, out.copy());
                }
            } else {
                out = Bench.make(level, v, p, f, hand);
                if (out.isEmpty()) continue;
            }
            clearShort(l);
            l.how = p.chain();
            made(b, l, out.getCount(), p.cost() / Math.max(1, out.getCount()));
            return new Made(out, p);
        }
        if (firstShort != null && f != null && b.grumbled != b.day) {
            b.grumbled = b.day;
            String line = "Short of " + firstShort.shortOf + " for the " + plural(firstWare)
                + (firstShort.why.isEmpty() ? " — if anybody's passing the stores with some…" : " (" + firstShort.why + ").");
            FolkTalk.speak(f, line);
            f.brain("short of " + firstShort.shortOf + " for the " + plural(firstWare));
        }
        return null;
    }

    private static void clearShort(Line l) {
        l.shortOf = "";
        l.why = "";
        l.missing = null;
        l.missingCount = 0;
    }

    /** What the storekeeper made to order: how many, or why it could not (a clause, ready to say). */
    public record Order(int made, String cannot) {}

    /**
     * Made to order, for a player at the stores: so many of a thing the stores have not got, made the
     * whole way at the bench out of what they can spare (Bench), at the storekeeper's hand. What it
     * could not make, it says why.
     */
    public static Order makeToOrder(ServerLevel level, Villages.Village v, VillageFolkEntity keeper, Item it, int n) {
        if (n <= 0 || RecipeBook.waysFor(level, it).isEmpty()) return new Order(0, "");
        String age = Workshop.refuse(level, v, it);                  // nothing the village's age has not come to (Tiers)
        if (!age.isEmpty()) return new Order(0, age);
        ItemStack one = new ItemStack(it);
        int most = one.getMaxStackSize() == 1 ? 1 : Math.min(n, one.getMaxStackSize());
        Bench.Hand hand = Bench.handOf(level, v, keeper, Seller.STORES.building);
        Bench.Plan p = Bench.plan(level, v, it, most, hand);
        if (!p.ok()) {
            if (p.why.startsWith("it's level")) return new Order(0, "that's beyond my hand yet: " + p.why);
            return new Order(0, "we're short of " + p.shortOf + (p.why.isEmpty() ? "" : ", or what we have is " + p.why));
        }
        ItemStack out = Bench.make(level, v, p, keeper, hand);
        if (out.isEmpty()) return new Order(0, "");
        Book b = book(level, v.id(), Seller.STORES);
        Line l = line(b, key(out));
        l.how = p.chain();
        made(b, l, out.getCount(), p.cost() / Math.max(1, out.getCount()));
        return new Order(out.getCount(), "");
    }

    // ------------------------------------------------------------------ asking the village

    /** Something a seller asks the village for on the quest board: what (a registry path), how many, and for what. */
    public record Ask(String item, int count, String purpose) {}

    /**
     * What the sellers ask the village to bring: for each seller at work, the makings of the ware it sold
     * (or was asked for) most of and has none of on its shelf, if the stores have none to spare and the
     * village keeps none of it back for itself. At most one a seller.
     */
    public static List<Ask> asks(ServerLevel level, Villages.Village v) {
        List<Ask> out = new ArrayList<>();
        for (Seller s : new Seller[]{ Seller.SHOP, Seller.CAFE }) {
            if (keeperOf(v.id(), s) == null) continue;
            Book b = book(level, v.id(), s);
            Line best = null;
            String bestKey = null;
            int most = -1;
            for (Ware w : wares(s, level, v.id())) {
                Line l = b.lines.get(w.key());
                if (l == null || l.missing == null || !l.why.isEmpty()) continue;
                if (Market.stock(level, v.id(), w.is()) > 0) continue;
                int d = Line.week(demand(level, v.id(), s, w.key()));
                if (d > most) {
                    most = d;
                    best = l;
                    bestKey = w.key();
                }
            }
            if (best == null || best.missing == null) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(best.missing);
            if (!id.getNamespace().equals("minecraft")) continue;
            // A table or a furnace is one thing; makings come by the handful.
            boolean tool = best.missing == Items.CRAFTING_TABLE || best.missing == Items.FURNACE || best.missing == Items.SMOKER;
            int n = tool ? 1 : Math.max(4, Math.min(32, best.missingCount * 4));
            out.add(new Ask(id.getPath(), n, s.words + "'s " + plural(bestKey)));
        }
        out.addAll(StockKeeper.asks(level, v));                    // [econ-store] what the store wants and cannot get
        return out;
    }

    // ------------------------------------------------------------------ talk

    /** A seller's own account of its shelves, for "How does your trade work?": what sells, what is low,
     *  what is marked down, what it is short of. Empty for anybody who keeps no shelves. */
    public static String talk(VillageFolkEntity f) {
        Seller s = switch (f.stationTask()) {
            case SHOP -> Seller.SHOP;
            case COOK -> Seller.CAFE;
            case STORE -> Seller.STORES;
            default -> null;
        };
        UUID village = f.ownerId();
        if (s == null || village == null || !(f.level() instanceof ServerLevel level)) return "";
        StringBuilder sb = new StringBuilder();
        // What sells: the week's best, wherever it was sold.
        Map<String, Integer> sold = new HashMap<>();
        for (Seller d : demandFrom(s)) {
            for (Line l : book(level, village, d).lines.values()) sold.merge(l.key, Line.week(l.sold), Integer::sum);
        }
        List<Map.Entry<String, Integer>> best = new ArrayList<>(sold.entrySet());
        best.removeIf(e -> e.getValue() <= 0);
        best.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        if (best.isEmpty()) {
            sb.append("Nothing's sold this week yet.");
        } else {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < Math.min(3, best.size()); i++) names.add(plural(best.get(i).getKey()) + " (" + best.get(i).getValue() + ")");
            sb.append("Selling best this week: ").append(String.join(", ", names)).append('.');
        }
        if (s == Seller.STORES) {
            sb.append(" Whatever the stores haven't got, I'll make to order at the bench if they can spare the makings.");
            return sb.toString();
        }
        // What is low, against what it means to keep, and what it is short of.
        List<String> low = new ArrayList<>();
        List<String> cheap = new ArrayList<>();
        String short_ = null;
        Book b = book(level, village, s);
        for (Ware w : wares(s, level, village)) {
            if (!w.made()) continue;
            int t = target(level, village, s, w), have = Market.stock(level, village, w.is());
            if (have < t && low.size() < 3) low.add(plural(w.key()) + " (" + have + " of " + t + ")");
            int off = markdown(level, village, s, w);
            if (off > 0 && cheap.size() < 2) cheap.add(plural(w.key()) + ", " + off + " off");
            Line l = b.lines.get(w.key());
            if (short_ == null && l != null && !l.shortOf.isEmpty()) {
                short_ = l.shortOf + " for the " + plural(w.key()) + (l.why.isEmpty() ? "" : " (" + l.why + ")");
            }
        }
        sb.append(low.isEmpty() ? " The shelves are full." : " Running low: " + String.join(", ", low) + ".");
        if (!cheap.isEmpty()) sb.append(" Marked down: ").append(String.join("; ", cheap)).append('.');
        if (short_ != null) sb.append(" I'm short of ").append(short_).append('.');
        return sb.toString();
    }

    /** For "What is the village short of?": what the sellers cannot make for want of something. */
    public static List<String> shortages(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        for (Seller s : new Seller[]{ Seller.SHOP, Seller.CAFE }) {
            if (keeperOf(village, s) == null) continue;
            Book b = book(level, village, s);
            for (Ware w : wares(s, level, village)) {
                Line l = b.lines.get(w.key());
                if (l == null || l.shortOf.isEmpty()) continue;
                out.add(s.words + " needs " + l.shortOf + " for its " + plural(w.key()));
                break;
            }
        }
        out.addAll(StockKeeper.shortages(level, village));         // [econ-store]
        return out;
    }

    // ------------------------------------------------------------------ the town's books

    /**
     * The sellers' books for the analytics screen: for each seller (the shop, the café, the tavern, the
     * market, the stores), whether it stands and has a keeper at work, its week's sales and takings,
     * what it is short of, and a row a ware: its name and item, what the stores hold of it and can
     * spare, what its maker means to keep (and the usual, the fewest, the most), what sold today,
     * yesterday and this week, what was asked for and not there, what was made today and this week, the
     * price of a lot and how many to a lot, what one cost, any markdown, what it is short of to make more,
     * the way it was last made, who makes it, and how it stands.
     */
    public static CompoundTag inventoryReport(ServerLevel level, UUID village) {
        CompoundTag out = new CompoundTag();
        out.putString("village", Villages.name(village));
        out.putLong("day", today(level));
        out.putBoolean("marketDay", Market.marketDay(village, today(level)));
        ListTag sellers = new ListTag();
        for (Seller s : Seller.values()) sellers.add(sellerTag(level, village, s));
        out.put("sellers", sellers);
        return out;
    }

    /**
     * The sellers' books as a page of text (/village shop): each seller that stands, keeps a keeper or
     * has sold anything, with its week, and a line a ware worth a line (on its shelf, wanted, sold or
     * made): on hand against what it keeps, what sold today and this week, what was made, the price,
     * any markdown, and what it is short of.
     */
    public static String page(ServerLevel level, Villages.Village v) {
        CompoundTag report = inventoryReport(level, v.id());
        StringBuilder sb = new StringBuilder();
        ListTag sellers = report.getList("sellers", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < sellers.size(); i++) {
            CompoundTag s = sellers.getCompound(i);
            ListTag rows = s.getList("wares", net.minecraft.nbt.Tag.TAG_COMPOUND);
            if (!s.getBoolean("built") && !s.getBoolean("open") && s.getInt("sold7") == 0 && s.getInt("made7") == 0) continue;
            String keeper = s.getString("keeper");
            sb.append(cap(s.getString("name"))).append(keeper.isEmpty() ? "" : " (" + keeper + ")")
                .append(": sold ").append(s.getInt("sold7")).append(" this week for ").append(s.getInt("coin7"))
                .append(" coin, made ").append(s.getInt("made7")).append('\n');
            int shown = 0;
            for (int k = 0; k < rows.size() && shown < 14; k++) {
                CompoundTag r = rows.getCompound(k);
                if (r.getInt("onHand") == 0 && r.getInt("target") == 0 && r.getInt("sold7") == 0 && r.getInt("made7") == 0) continue;
                shown++;
                sb.append("  ").append(r.getString("name")).append(": ").append(r.getInt("onHand"));
                if (r.getInt("target") > 0) sb.append(" of ").append(r.getInt("target"));
                sb.append(", sold ").append(r.getInt("soldToday")).append(" today, ").append(r.getInt("sold7")).append(" this week");
                if (r.getInt("missed7") > 0) sb.append(" (").append(r.getInt("missed7")).append(" wanted and not there)");
                if (r.getInt("made7") > 0) sb.append(", made ").append(r.getInt("made7"));
                if (r.getInt("price") > 0) {
                    sb.append(", ").append(r.getInt("lot") > 1 ? r.getInt("lot") + " for " : "").append(r.getInt("price")).append('c');
                }
                if (r.getInt("markdown") > 0) sb.append(" (").append(r.getInt("markdown")).append(" off)");
                if (!r.getString("short").isEmpty()) sb.append(" — short of ").append(r.getString("short"));
                sb.append('\n');
            }
        }
        return sb.length() == 0 ? "No shop, café or tavern yet, and nothing sold." : sb.toString().stripTrailing();
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static CompoundTag sellerTag(ServerLevel level, UUID village, Seller s) {
        CompoundTag t = new CompoundTag();
        t.putString("id", s.id);
        t.putString("name", s.words);
        boolean built = s == Seller.STORES ? Villages.hasStores(level, village)
            : s == Seller.TAVERN ? Tavern.of(village) != null : Villages.builtAt(village, s.building) != null;
        t.putBoolean("built", built);
        VillageFolkEntity keeper = keeperOf(village, s);
        t.putBoolean("open", s == Seller.MARKET || s == Seller.TAVERN ? built : keeper != null);
        t.putString("keeper", keeper == null ? "" : keeper.displayNameCap());
        Book b = book(level, village, s);
        t.putInt("daysKept", (int) (b.day - b.opened));
        // The rows: the wares it makes (or pours) first, then anything else sold or asked for here.
        List<String> keys = new ArrayList<>();
        for (Ware w : wares(s, level, village)) keys.add(w.key());
        for (Line l : b.lines.values()) if (!keys.contains(l.key)) keys.add(l.key);
        ListTag rows = new ListTag();
        ListTag shorts = new ListTag();
        int soldToday = 0, sold7 = 0, coin7 = 0, madeToday = 0, made7 = 0;
        for (String key : keys) {
            CompoundTag r = row(level, village, s, key);
            if (r == null) continue;
            rows.add(r);
            soldToday += r.getInt("soldToday");
            sold7 += r.getInt("sold7");
            coin7 += r.getInt("coin7");
            madeToday += r.getInt("madeToday");
            made7 += r.getInt("made7");
            String sh = r.getString("short");
            if (!sh.isEmpty() && shorts.size() < 4) shorts.add(StringTag.valueOf(sh + " for the " + plural(key)));
        }
        t.put("wares", rows);
        t.put("short", shorts);
        t.putBoolean("makes", s == Seller.SHOP || s == Seller.CAFE || s == Seller.STORES);
        t.putInt("soldToday", soldToday);
        t.putInt("sold7", sold7);
        t.putInt("coin7", coin7);
        t.putInt("madeToday", madeToday);
        t.putInt("made7", made7);
        return t;
    }

    @Nullable
    private static CompoundTag row(ServerLevel level, UUID village, Seller s, String key) {
        ItemStack sample = sampleOf(key);
        if (sample.isEmpty()) return null;
        Found found = find(key);
        Ware w = found == null ? null : found.ware();
        Seller maker = found == null ? s : found.maker();
        Predicate<ItemStack> is = w != null ? w.is() : matcher(key);
        CompoundTag r = new CompoundTag();
        r.putString("id", key);
        r.putString("item", BuiltInRegistries.ITEM.getKey(sample.getItem()).toString());
        r.putString("name", nameOf(key));
        int have = Market.stock(level, village, is);
        r.putInt("onHand", have);
        r.putInt("forSale", Budget.spare(level, village, sample));
        boolean makes = w != null && w.made();
        int target = makes ? target(level, village, maker, w) : 0;
        r.putInt("target", target);
        r.putInt("usual", w == null ? 0 : w.usual());
        r.putInt("fewest", w == null ? 0 : w.fewest());
        r.putInt("most", w == null ? 0 : w.most());
        Line l = book(level, village, s).lines.get(key);
        r.putInt("soldToday", l == null ? 0 : l.sold[0]);
        r.putInt("soldYesterday", l == null ? 0 : l.sold[1]);
        r.putInt("sold7", l == null ? 0 : Line.week(l.sold));
        r.putInt("missed7", l == null ? 0 : Line.week(l.missed));
        r.putInt("coin7", l == null ? 0 : Line.week(l.coin));
        // Made: at its maker's bench, and (at the stores) to order as well.
        Line ml = book(level, village, maker).lines.get(key);
        Line own = maker == s ? null : l;
        r.putInt("madeToday", (ml == null ? 0 : ml.made[0]) + (own == null ? 0 : own.made[0]));
        r.putInt("made7", (ml == null ? 0 : Line.week(ml.made)) + (own == null ? 0 : Line.week(own.made)));
        Market.Good g = Budget.goodFor(sample);
        int lot = g == null ? 1 : g.bundle();
        boolean md = Market.marketDay(village, today(level));
        r.putInt("lot", lot);
        r.putInt("price", g == null ? 0 : Market.price(level, village, g, sample, have, md));
        r.putDouble("cost", w == null ? (Prices.known(sample.getItem()) ? Prices.each(sample.getItem()) : 0)
            : costEach(level, village, maker, w));
        r.putInt("markdown", makes ? markdown(level, village, maker, w) : 0);
        String sh = ml == null || ml.shortOf.isEmpty() ? "" : ml.shortOf + (ml.why.isEmpty() ? "" : " (" + ml.why + ")");
        r.putString("short", sh);
        r.putString("how", ml == null ? "" : ml.how);
        r.putString("maker", !makes ? "the crafts" : maker == Seller.SHOP ? "the shopkeeper" : "the cook");
        String status;
        if (!makes) status = "not made here";
        else if (have >= target) status = "stocked";
        else if (!sh.isEmpty()) status = "short";
        else status = "to make";
        if (makes && target <= w.fewest() && !w.staple() && Line.week(demand(level, village, maker, key)) == 0
                && book(level, village, maker).day - book(level, village, maker).opened >= 2) {
            status = have >= target ? "slow" : status;
        }
        r.putString("status", status);
        return r;
    }
}
