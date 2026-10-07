package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a thing costs in this town today: the one price every buyer and seller in the town goes by (the shop,
 * the café, the builders' bills, the town's deals with its neighbours, the wages reckoned by what a trade
 * brings in). Prices.each is what a thing is usually worth anywhere; this is what it fetches here, now.
 *
 * <p>[economy] The shared seam of the town's economy. Everybody else only asks it; its reckoning is here.
 *
 * <p>[econ-prices] <b>Supply and demand, town by town.</b> Every morning (Market.tick, once yesterday's books
 * are closed) each town reckons a price for every good on the board and every ware its sellers deal in, as a
 * factor of its usual worth:
 * <ul>
 * <li><b>Supply</b> is what the town has to sell: what its stores and its shop hold (ShopStock), and what it
 *     made yesterday (Economy's books, item by item).</li>
 * <li><b>Demand</b> is what was wanted: sold at its counters to folk and players and asked for and not there
 *     (Stockroom's books), drawn out of the stores by folk who took it free before the shop opened, and used up
 *     making other things (the wheat in the bread).</li>
 * <li><b>Too dear.</b> What folk would not pay for (Purchases' refusals) is demand the price turned away: it
 *     brings the price down, where a thing wanted and not there sends it up.</li>
 * </ul>
 * The days of want the town can meet from what it holds and makes in a day set where the price is heading:
 * three days' cover is the usual worth, less is dear, more is cheap. A thing little wanted goes by the stores'
 * count, as the board always priced it (four lots on hand the usual). It never goes under four tenths of its
 * usual worth nor over three times it, and it moves toward where it is heading a third of the way a day, never
 * more than fifteen in the hundred: bread does not double overnight because the cart was late.
 *
 * <p>The market day's tenth off, a slow ware's markdown and the floor at what a thing cost to make go on top
 * (Market.sellPrice, Stockroom.asked, Purchases.priceEach). The prices are kept with the town (its ledger), and
 * shown on the board, in the gazette, on the shop's price signs, in the town's books (the Prices page) and by
 * {@code /village prices}.
 */
public final class PriceIndex {

    private PriceIndex() {}

    /** The band a price stays in, about its usual worth. */
    static final double LOWEST = 0.4, DEAREST = 3.0;
    /** The most a price moves in a day, and the least it bothers to (a move smaller than this is made whole). */
    static final double MOST_A_DAY = 0.15, LEAST_A_DAY = 0.03;
    /** How much of the way to where it is heading a price goes in a day. */
    static final double PACE = 0.35;
    /** The days of want a town's stock and day's making cover at the usual price. */
    static final double DAYS_COVER = 3.0;
    /** How much of yesterday's smoothed figure is kept against yesterday's own (the rest). */
    static final double KEEP = 0.6;
    /** Days of past prices kept, yesterday first. */
    static final int HISTORY = 8;
    /** A week's move this big (in the hundred) is news: the board, the gazette and the chronicle tell of it. */
    static final int NEWS = 25;

    /** One thing's price in one town. */
    static final class Line {
        final String key;
        /** Today's price against its usual worth; and where it was heading this morning. */
        double factor = 1.0, target = 1.0;
        /** Smoothed, a day: wanted (sold, missed, drawn, used up), made, and refused as too dear; -1 before first seen. */
        double demand = -1, output = -1, refused;
        /** On hand at the morning's count. */
        int stock;
        /** Yesterday's own figures, for the page: sold or drawn, wanted and not there, made, refused, bought more for cheapness. */
        int dayWanted, dayMissed, dayMade, dayRefused, dayBargains;
        /** The factor on the mornings before, yesterday's first (0: no price that day). */
        final double[] past = new double[HISTORY];
        /** Why it moved, in a few words ("the harvest failed"), and the day the chronicle last told of it. */
        String why = "";
        long told = -100;

        Line(String key) {
            this.key = key;
        }

        /** Its price a week ago (or as far back as is kept), or 0. */
        double weekAgo() {
            double w = 0;
            for (int i = 0; i < Math.min(7, HISTORY); i++) if (past[i] > 0) w = past[i];
            return w;
        }

        /** Which way it is going: +1 dearer than yesterday, -1 cheaper, 0 steady (a hundredth either way). */
        int trend() {
            if (past[0] <= 0) return 0;
            double d = factor / past[0] - 1.0;
            return d > 0.02 ? 1 : d < -0.02 ? -1 : 0;
        }
    }

    /** One town's prices: the morning they were last reckoned, a line a thing, and what folk did since, not yet reckoned. */
    static final class Town {
        final UUID village;
        long day = -1;
        final Map<String, Line> lines = new LinkedHashMap<>();
        /** Since the last reckoning, a thing: {drawn free, bought by folk, refused as too dear, bought more for cheapness}. */
        final Map<String, int[]> tally = new HashMap<>();
        /** Are the shop's luxuries cheap, as this morning reckoned them (Luxuries asks often)? */
        boolean luxuriesCheap;

        Town(UUID village) {
            this.village = village;
        }
    }

    static final int DRAWN = 0, BOUGHT = 1, REFUSED = 2, BARGAIN = 3;

    private static final Map<UUID, Town> TOWNS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        TOWNS.clear();
    }

    // ------------------------------------------------------------------ asking

    /** One of this thing, in coin, in this town today. */
    public static double each(ServerLevel level, UUID village, ItemStack what) {
        if (what.isEmpty()) return 0.0;
        ItemStack one = what.copyWithCount(1);
        return usual(one) * factor(village, one);
    }

    /** One of this item, in coin, in this town today. */
    public static double each(ServerLevel level, UUID village, Item item) {
        return each(level, village, new ItemStack(item));
    }

    /** [econ-prices] One of a good on the board, in coin, in this town today (where only the good is known: what the
     *  town buys, what the traders take). */
    public static double each(ServerLevel level, UUID village, Market.Good g) {
        return g.value() * factor(village, "g:" + g.name());
    }

    /** What this thing is usually worth, anywhere: what a buyer expects to pay before it sees the price. */
    public static double usual(ItemStack what) {
        if (what.isEmpty()) return 0.0;
        Market.Good g = Market.goodFor(what);
        return g != null ? g.value() : Prices.of(what.copyWithCount(1));
    }

    /** [econ-prices] Today's price of this thing against its usual worth: 1 is the usual, 2 twice it. */
    public static double factor(UUID village, ItemStack one) {
        return factor(village, keyOf(one));
    }

    static double factor(UUID village, String key) {
        Line l = town(village).lines.get(key);
        // A town not reckoned yet (a new one, before its first morning) sells at the usual worth.
        return l == null ? 1.0 : l.factor;
    }

    /** Which way this thing's price is going: +1, -1 or 0. */
    public static int trend(UUID village, ItemStack one) {
        Line l = town(village).lines.get(keyOf(one));
        return l == null ? 0 : l.trend();
    }

    /** A thing's name in the town's prices: the board's good it is ("g:Bread"), or the sellers' ware ("w:candle"). */
    static String keyOf(ItemStack s) {
        Market.Good g = Market.goodFor(s);
        if (g != null) return "g:" + g.name();
        return "w:" + Stockroom.key(s);
    }

    /** One of a thing, from its name in the prices. */
    static ItemStack sampleOf(String key) {
        if (key.startsWith("w:")) return Stockroom.sampleOf(key.substring(2));
        String name = key.substring(2);
        for (Market.Good g : Market.GOODS) if (g.name().equals(name)) return sampleOfGood(g);
        // A café drink or the brewer's potions (not on the board's list): the sellers' sample of it.
        for (Cafe.Drink d : Cafe.DRINKS) if (d.name().equals(name)) return Cafe.drink(d);
        return name.equals("Potion") ? new ItemStack(Items.POTION) : ItemStack.EMPTY;
    }

    /** One of a good on the board, to show: the first item it matches (looked up once, then kept). */
    static ItemStack sampleOfGood(Market.Good g) {
        ItemStack kept = SAMPLE_OF.get(g.name());
        if (kept != null) return kept.copy();
        ItemStack found = ItemStack.EMPTY;
        for (Item it : SAMPLES) {
            ItemStack s = new ItemStack(it);
            if (g.what().test(s)) { found = s; break; }
        }
        if (found.isEmpty()) {
            for (Item it : BuiltInRegistries.ITEM) {
                ItemStack s = new ItemStack(it);
                if (g.what().test(s)) { found = s; break; }
            }
        }
        SAMPLE_OF.put(g.name(), found);
        return found.copy();
    }

    /** The board's goods' samples, by name, once found. */
    private static final Map<String, ItemStack> SAMPLE_OF = new ConcurrentHashMap<>();

    /** The goods whose first match in the registry is not the one to show (the board's "Wool" is white wool). */
    private static final List<Item> SAMPLES = List.of(Items.WHITE_WOOL, Items.OAK_LOG, Items.OAK_PLANKS, Items.WHITE_BED,
        Items.WHITE_CARPET, Items.WHITE_BANNER);

    // ------------------------------------------------------------------ what folk did (Purchases)

    static Town town(UUID village) {
        return TOWNS.computeIfAbsent(village, PriceIndex::load);
    }

    private static void tally(UUID village, ItemStack what, int n, int which) {
        if (village == null || what.isEmpty() || n <= 0) return;
        Town t = town(village);
        t.tally.computeIfAbsent(keyOf(what.copyWithCount(1)), k -> new int[4])[which] += n;
        saveTally(t);
    }

    /** Taken free out of the stores for a folk's own use (before the shop opened): wanted all the same. */
    static void drawn(UUID village, ItemStack what, int n) {
        tally(village, what, n, DRAWN);
    }

    /** Bought by a folk at the town's price (already in the sellers' books as a sale). */
    static void bought(UUID village, ItemStack what, int n) {
        tally(village, what, n, BOUGHT);
    }

    /** Refused as too dear (or more than it would pay): wanted, but not at that price. */
    static void refused(UUID village, ItemStack what, int n) {
        tally(village, what, n, REFUSED);
    }

    /** Bought more of than it came for, for being cheap. */
    static void bargain(UUID village, ItemStack what, int n) {
        tally(village, what, n, BARGAIN);
    }

    /** Since the last reckoning: {drawn, bought, refused, bargains} of this thing (the tests). */
    public static int[] tallyForTests(UUID village, ItemStack what) {
        int[] t = town(village).tally.get(keyOf(what.copyWithCount(1)));
        return t == null ? new int[4] : t.clone();
    }

    /** Tests: this thing's price set to so many times its usual worth, as though the town had reckoned it so. */
    public static void setForTests(UUID village, ItemStack what, double factor) {
        Town t = town(village);
        Line l = t.lines.computeIfAbsent(keyOf(what.copyWithCount(1)), Line::new);
        l.factor = factor;
        l.target = factor;
        if (t.day < 0) t.day = 0;
    }

    // ------------------------------------------------------------------ the morning's reckoning

    /** The morning: every price in the town reckoned afresh from yesterday's supply and demand (Market.tick). */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        Town t = town(v.id());
        if (t.day >= day) return;
        reckon(level, v, t, day);
        Purchases.morning(level, v, day);               // the cost of living against the lowest wage, the slates seen to
    }

    /** As the morning does it, now, whatever the hour and however lately it was done (/village prices now, the tests). */
    public static void reckonNow(ServerLevel level, Villages.Village v) {
        Town t = town(v.id());
        long day = level.getDayTime() / 24000L;
        reckon(level, v, t, Math.max(day, t.day + 1));
        Purchases.morning(level, v, day);
    }

    private static void reckon(ServerLevel level, Villages.Village v, Town t, long day) {
        UUID id = v.id();
        // What the stores hold, by the name of each thing in the prices: one look at every chest.
        Map<String, Integer> stores = new HashMap<>();
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || Market.isCoin(s)) continue;
                stores.merge(keyOf(s), s.getCount(), Integer::sum);
            }
        }
        // Yesterday's making and using, item by item (Economy).
        Map<String, int[]> made = new HashMap<>();
        for (Map.Entry<String, int[]> e : Economy.yesterdayItems(id).entrySet()) {
            ResourceLocation rl = ResourceLocation.tryParse(e.getKey());
            Item it = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
            if (it == Items.AIR) continue;
            int[] m = made.computeIfAbsent(keyOf(new ItemStack(it)), k -> new int[2]);
            m[0] += e.getValue()[0];
            m[1] += e.getValue()[1];
        }
        // Yesterday at the counters: sold, and asked for and not there (Stockroom), every seller.
        Map<String, int[]> sold = new HashMap<>();
        for (Stockroom.Seller s : Stockroom.Seller.values()) {
            for (Stockroom.Line l : Stockroom.book(level, id, s).lines.values()) {
                if (l.sold[1] + l.missed[1] <= 0) continue;
                ItemStack sample = Stockroom.sampleOf(l.key);
                if (sample.isEmpty()) continue;
                int[] m = sold.computeIfAbsent(keyOf(sample), k -> new int[2]);
                m[0] += l.sold[1];
                m[1] += l.missed[1];
            }
        }
        // Every good on the board is priced; a ware besides only once something has been done with it.
        List<String> keys = new ArrayList<>();
        for (Market.Good g : Market.GOODS) keys.add("g:" + g.name());
        for (String k : t.lines.keySet()) if (!keys.contains(k)) keys.add(k);
        for (String k : sold.keySet()) if (!keys.contains(k)) keys.add(k);
        for (String k : t.tally.keySet()) if (!keys.contains(k)) keys.add(k);
        for (String k : keys) {
            int bundle = 1;
            if (k.startsWith("g:")) {
                Market.Good g = goodNamed(k.substring(2));
                if (g != null) bundle = g.bundle();
            }
            int have = stores.getOrDefault(k, 0);
            // The shop's own shelves, once it keeps them apart from the stores (ShopStock): supply too. While it sells
            // out of the stores they are one stock, counted once.
            int shop = shopCount(level, id, k);
            if (shop != have) have += shop;
            int[] m = made.getOrDefault(k, new int[2]);
            int[] c = sold.getOrDefault(k, new int[2]);
            int[] f = t.tally.getOrDefault(k, new int[4]);
            Line l = t.lines.get(k);
            boolean fresh = l == null;
            if (fresh) {
                l = new Line(k);
                t.lines.put(k, l);
            }
            int wanted = c[0] + c[1] + f[DRAWN] + m[1];
            move(l, have, bundle, m[0], wanted, f[REFUSED], fresh);
            l.dayWanted = c[0] + f[DRAWN] + m[1];
            l.dayMissed = c[1];
            l.dayMade = m[0];
            l.dayRefused = f[REFUSED];
            l.dayBargains = f[BARGAIN];
        }
        t.tally.clear();
        t.day = day;
        t.luxuriesCheap = cheapLuxuries(t);
        tellTheMoves(level, v, t, day);
        save(t);
        saveTally(t);
    }

    /** The shop's count of a thing (ShopStock), by its name in the prices. */
    private static int shopCount(ServerLevel level, UUID village, String key) {
        if (key.startsWith("g:")) {
            Market.Good g = goodNamed(key.substring(2));
            if (g != null) return ShopStock.count(level, village, g.what());
        }
        if (sampleOf(key).isEmpty()) return 0;
        return ShopStock.count(level, village, Stockroom.matcher(key.substring(2)));
    }

    @Nullable
    static Market.Good goodNamed(String name) {
        for (Market.Good g : Market.GOODS) if (g.name().equals(name)) return g;
        return null;
    }

    /**
     * One thing's price, a morning on: its smoothed demand, making and refusals brought up to date, where it is heading
     * worked out from them, and the price moved toward it (a third of the way, fifteen in the hundred at most).
     */
    static void move(Line l, int stock, int bundle, int madeYesterday, int wantedYesterday, int refusedYesterday, boolean fresh) {
        double oldDemand = l.demand, oldOutput = l.output;
        l.demand = l.demand < 0 ? wantedYesterday : KEEP * l.demand + (1 - KEEP) * wantedYesterday;
        l.output = l.output < 0 ? madeYesterday : KEEP * l.output + (1 - KEEP) * madeYesterday;
        l.refused = KEEP * l.refused + (1 - KEEP) * refusedYesterday;
        l.stock = stock;
        double target = target(stock, bundle, l.output, l.demand, l.refused);
        l.target = target;
        System.arraycopy(l.past, 0, l.past, 1, HISTORY - 1);
        l.past[0] = fresh ? 0 : l.factor;
        double was = l.factor;
        double gap = target - was;
        double step = gap * PACE;
        double most = was * MOST_A_DAY, least = was * LEAST_A_DAY;
        if (Math.abs(gap) <= least) step = gap;                                         // near enough: there
        else if (Math.abs(step) < least) step = Math.signum(gap) * least;                // a few in the hundred at the least
        step = Math.max(-most, Math.min(most, step));
        l.factor = clamp(was + step);
        // Why, in a few words, when it is going one way.
        if (l.factor > was * 1.01) {
            l.why = oldOutput > 1 && madeYesterday < oldOutput * 0.6 ? "less made" : oldDemand >= 0 && wantedYesterday > Math.max(2, oldDemand * 1.4)
                ? "more wanted" : refusedYesterday == 0 && stock < bundle * 2 ? "the stores ran low" : "scarce";
        } else if (l.factor < was * 0.99) {
            l.why = refusedYesterday > 0 && refusedYesterday * 2 >= wantedYesterday ? "folk would not pay the price"
                : oldOutput >= 0 && madeYesterday > Math.max(2, oldOutput * 1.4) ? "more made" : "plenty and few buying";
        }
    }

    /**
     * Where a price is heading, against its usual worth: by the days of want the town can meet from what it holds
     * and makes in a day (three days' cover the usual; less dear, more cheap), and for a thing little wanted by
     * what lies in the stores (four lots the usual, as the board always had it); brought down by what folk would
     * not pay for. Within the band.
     */
    static double target(int stock, int bundle, double output, double demand, double refused) {
        double scarcity = Market.scarcity(bundle, stock);
        double t;
        if (demand < 0.5 && output < 0.5) {
            t = scarcity;
        } else {
            double cover = (stock + output) / Math.max(0.25, demand);
            double byDemand = Math.sqrt(DAYS_COVER / Math.max(0.1, cover));
            // A thing wanted by the dozen a day goes by its cover; one wanted now and then mostly by the stores' count.
            double w = Math.min(1.0, demand / (demand + bundle));
            t = Math.pow(scarcity, 1 - w) * Math.pow(byDemand, w);
        }
        if (refused > 0) t *= 1.0 - 0.5 * refused / (refused + demand + 1.0);
        return clamp(t);
    }

    private static double clamp(double f) {
        return Math.max(LOWEST, Math.min(DEAREST, f));
    }

    // ------------------------------------------------------------------ the news

    /** A big move this week, in words: "Bread dear this week: less made (0.42c, up 40%)". */
    record Move(String key, String name, double each, int percent, String why) {
        String words() {
            String dear = percent > 0 ? "dear" : "cheap";
            String reason = switch (why) {
                case "less made" -> percent > 0 ? lessMade(key) : "less made";
                case "more made" -> moreMade(key);
                default -> why;
            };
            return name + " " + dear + " this week" + (reason.isEmpty() ? "" : ": " + reason) + " ("
                + String.format(Locale.ROOT, "%.2fc", each) + ", " + (percent > 0 ? "up " : "down ") + Math.abs(percent) + "%)";
        }
    }

    /** "the harvest failed" for food, "the mine gave less" for stone and ore, and so on. */
    private static String lessMade(String key) {
        Economy.Kind k = Economy.kindOf(sampleOf(key));
        if (k == null) return "less made";
        return switch (k) {
            case FOOD -> "the harvest failed";
            case STONE, ORE -> "the mine gave less";
            case TIMBER -> "the woods gave less";
            case ANIMAL -> "the pens gave less";
            default -> "less made";
        };
    }

    private static String moreMade(String key) {
        Economy.Kind k = Economy.kindOf(sampleOf(key));
        if (k == null) return "more made";
        return switch (k) {
            case FOOD -> "a good harvest";
            case STONE, ORE -> "the mine's doing well";
            case TIMBER -> "plenty of timber";
            default -> "more made";
        };
    }

    /** This week's big moves in this town, the biggest first (three at most). */
    static List<Move> moves(UUID village) {
        List<Move> out = new ArrayList<>();
        Town t = town(village);
        for (Line l : t.lines.values()) {
            double before = l.weekAgo();
            if (before <= 0) continue;
            int pct = (int) Math.round((l.factor / before - 1.0) * 100);
            if (Math.abs(pct) < NEWS) continue;
            ItemStack one = sampleOf(l.key);
            if (one.isEmpty()) continue;
            out.add(new Move(l.key, nameOf(l.key), usual(one) * l.factor, pct, l.why));
        }
        out.sort((a, b) -> Integer.compare(Math.abs(b.percent()), Math.abs(a.percent())));
        return out.size() > 3 ? out.subList(0, 3) : out;
    }

    /** The week's big moves, in words, for the board and the gazette. */
    public static List<String> moveLines(UUID village) {
        List<String> out = new ArrayList<>();
        for (Move m : moves(village)) out.add(m.words());
        return out;
    }

    /** The board's line on prices, or null when nothing has moved much: "Prices: bread dear this week: the harvest failed". */
    @Nullable
    public static String boardLine(UUID village) {
        List<Move> m = moves(village);
        if (m.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(2, m.size()); i++) parts.add(m.get(i).words());
        return "Prices: " + lower(String.join("; ", parts));
    }

    /** Is the board's line on prices a warning (something dear)? */
    public static boolean boardWarns(UUID village) {
        for (Move m : moves(village)) if (m.percent() > 0) return true;
        return false;
    }

    /** A big move told in the chronicle, once a week a thing. */
    private static void tellTheMoves(ServerLevel level, Villages.Village v, Town t, long day) {
        for (Move m : moves(v.id())) {
            Line l = t.lines.get(m.key());
            if (l == null || day - l.told < 7) continue;
            l.told = day;
            Villages.tell(v.id(), day, lower(m.words()));
        }
    }

    // ------------------------------------------------------------------ the books

    /** A thing's name for the books: the board's name for a good, the sellers' for a ware. */
    static String nameOf(String key) {
        if (key.startsWith("g:")) return key.substring(2);
        return Stockroom.nameOf(key.substring(2));
    }

    /** The lines worth showing, the furthest from their usual worth first. */
    private static List<Line> shown(UUID village) {
        List<Line> out = new ArrayList<>();
        for (Line l : town(village).lines.values()) {
            if (l.stock <= 0 && l.demand < 0.2 && l.output < 0.2 && l.dayRefused == 0 && l.dayBargains == 0) continue;
            out.add(l);
        }
        out.sort((a, b) -> Double.compare(Math.abs(Math.log(b.factor)), Math.abs(Math.log(a.factor))));
        return out;
    }

    /**
     * The prices for the town's books (the Prices page): the day they were reckoned, the cost of living against the
     * lowest wage, the slates, the week's moves, and a row a thing (its name and item, its price today and its usual
     * worth, the trend, what is on hand, made and wanted a day, refused and bought cheap yesterday, and why it moved).
     */
    public static CompoundTag report(ServerLevel level, UUID village) {
        CompoundTag out = new CompoundTag();
        Town t = town(village);
        out.putLong("day", t.day);
        out.putBoolean("open", Purchases.open(village));
        out.putBoolean("marketDay", Market.marketDay(village, level.getDayTime() / 24000L));
        double living = Purchases.costOfLiving(level, village);
        out.putInt("living100", (int) Math.round(living * 100));
        out.putInt("meal100", (int) Math.round(Purchases.mealPrice(level, village) * 100));
        out.putInt("rent", Purchases.cheapestRent(village));
        out.putInt("lowest", Purchases.lowestWage(village));
        out.putString("alarm", Purchases.alarm(village) == null ? "" : Purchases.alarm(village));
        int[] slate = Purchases.slates(village);
        out.putInt("slateFolk", slate[0]);
        out.putInt("slateCoins", slate[1]);
        int refused = 0, bargains = 0;
        List<String> dear = new ArrayList<>(), cheap = new ArrayList<>();
        for (Line l : t.lines.values()) {
            refused += l.dayRefused;
            bargains += l.dayBargains;
            if (l.dayRefused > 0 && dear.size() < 4) dear.add(l.dayRefused + " " + lower(nameOf(l.key)));
            if (l.dayBargains > 0 && cheap.size() < 4) cheap.add(l.dayBargains + " " + lower(nameOf(l.key)));
        }
        out.putInt("refused", refused);
        out.putInt("bargains", bargains);
        out.putString("refusedWhat", String.join(", ", dear));
        out.putString("bargainWhat", String.join(", ", cheap));
        ListTag moves = new ListTag();
        for (String m : moveLines(village)) moves.add(StringTag.valueOf(m));
        out.put("moves", moves);
        ListTag rows = new ListTag();
        for (Line l : shown(village)) {
            ItemStack one = sampleOf(l.key);
            if (one.isEmpty()) continue;
            CompoundTag r = new CompoundTag();
            r.putString("key", l.key);
            r.putString("name", nameOf(l.key));
            r.putString("item", BuiltInRegistries.ITEM.getKey(one.getItem()).toString());
            double usual = usual(one);
            r.putInt("usual100", (int) Math.round(usual * 100));
            r.putInt("each100", (int) Math.round(usual * l.factor * 100));
            r.putInt("factor100", (int) Math.round(l.factor * 100));
            r.putInt("target100", (int) Math.round(l.target * 100));
            r.putInt("trend", l.trend());
            double week = l.weekAgo();
            r.putInt("week", week <= 0 ? 0 : (int) Math.round((l.factor / week - 1.0) * 100));
            r.putInt("stock", l.stock);
            r.putInt("made10", (int) Math.round(Math.max(0, l.output) * 10));
            r.putInt("wanted10", (int) Math.round(Math.max(0, l.demand) * 10));
            r.putInt("refused", l.dayRefused);
            r.putInt("bargains", l.dayBargains);
            r.putInt("missed", l.dayMissed);
            r.putString("why", l.why);
            rows.add(r);
            if (rows.size() >= 60) break;
        }
        out.put("goods", rows);
        return out;
    }

    /** The prices as a page of text (/village prices). */
    public static String page(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Town t = town(id);
        long today = level.getDayTime() / 24000L;
        StringBuilder sb = new StringBuilder();
        sb.append(t.day < 0 ? "Not reckoned yet: everything sells at its usual worth until the town's first morning."
            : "Reckoned " + (t.day >= today ? "this morning" : "on day " + (t.day + 1)) + " from yesterday's supply and demand"
                + (Market.marketDay(id, today) ? "; market day: a tenth off at the stalls" : "") + ".");
        sb.append('\n').append(Purchases.livingLine(level, id)).append('\n');
        int[] slate = Purchases.slates(id);
        if (slate[0] > 0) sb.append(slate[0]).append(slate[0] == 1 ? " folk owes " : " folk owe ").append(slate[1])
            .append(slate[1] == 1 ? " coin" : " coins").append(" on the slate, to be paid from their wages.\n");
        List<String> moves = moveLines(id);
        if (!moves.isEmpty()) sb.append("This week: ").append(String.join("; ", moves)).append(".\n");
        int refused = 0, bargains = 0;
        List<String> dear = new ArrayList<>(), cheap = new ArrayList<>();
        for (Line l : t.lines.values()) {
            refused += l.dayRefused;
            bargains += l.dayBargains;
            if (l.dayRefused > 0) dear.add(l.dayRefused + " " + lower(nameOf(l.key)));
            if (l.dayBargains > 0) cheap.add(l.dayBargains + " " + lower(nameOf(l.key)));
        }
        if (refused > 0) sb.append("Too dear for the folk yesterday: ").append(String.join(", ", dear)).append(".\n");
        if (bargains > 0) sb.append("Bought more for being cheap: ").append(String.join(", ", cheap)).append(".\n");
        sb.append("Today against the usual worth (stock, made and wanted a day):\n");
        int n = 0;
        for (Line l : shown(id)) {
            ItemStack one = sampleOf(l.key);
            if (one.isEmpty()) continue;
            double usual = usual(one);
            int tr = l.trend();
            sb.append("  ").append(nameOf(l.key)).append(' ').append(String.format(Locale.ROOT, "%.2fc", usual * l.factor))
                .append(" (usual ").append(String.format(Locale.ROOT, "%.2f", usual)).append(tr > 0 ? ", rising" : tr < 0 ? ", falling" : "")
                .append(") stock ").append(l.stock).append(", made ").append(String.format(Locale.ROOT, "%.1f", Math.max(0, l.output)))
                .append(", wanted ").append(String.format(Locale.ROOT, "%.1f", Math.max(0, l.demand)))
                .append(l.dayRefused > 0 ? ", " + l.dayRefused + " refused" : "").append(l.why.isEmpty() ? "" : " — " + l.why).append('\n');
            if (++n >= 24) break;
        }
        return sb.toString().stripTrailing();
    }

    /** Is a luxury cheap in this town just now (the rugs, the candles, the banners, a fifth or more under their worth)?
     *  Folk buy something for their homes sooner (Luxuries). */
    public static boolean luxuriesCheap(UUID village) {
        return town(village).luxuriesCheap;
    }

    private static boolean cheapLuxuries(Town t) {
        double sum = 0;
        int n = 0;
        for (Line l : t.lines.values()) {
            ItemStack one = sampleOf(l.key);
            if (one.isEmpty() || !Luxuries.isLuxury(one)) continue;
            sum += l.factor;
            n++;
        }
        return n > 0 && sum / n <= 0.8;
    }

    private static String lower(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ kept with the town

    /** Into the town's ledger: "1;day|key~factor~target~demand~output~refused~stock~past,..~told~why|...". */
    private static void save(Town t) {
        StringBuilder sb = new StringBuilder("1;").append(t.day);
        for (Line l : t.lines.values()) {
            sb.append('|').append(l.key).append('~').append(f3(l.factor)).append('~').append(f3(l.target)).append('~')
                .append(f3(l.demand)).append('~').append(f3(l.output)).append('~').append(f3(l.refused)).append('~').append(l.stock).append('~');
            for (int i = 0; i < HISTORY; i++) sb.append(i == 0 ? "" : ",").append(f3(l.past[i]));
            sb.append('~').append(l.told).append('~').append(l.why.replace('~', ' ').replace('|', ' '))
                .append('~').append(l.dayWanted).append(',').append(l.dayMissed).append(',').append(l.dayMade).append(',')
                .append(l.dayRefused).append(',').append(l.dayBargains);
        }
        Ledger.note(t.village, "prices", sb.toString());
    }

    private static void saveTally(Town t) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, int[]> e : t.tally.entrySet()) {
            int[] a = e.getValue();
            sb.append(sb.length() == 0 ? "" : "|").append(e.getKey()).append('~').append(a[0]).append(',').append(a[1]).append(',')
                .append(a[2]).append(',').append(a[3]);
        }
        Ledger.note(t.village, "prices.tally", sb.toString());
    }

    private static Town load(UUID village) {
        Town t = new Town(village);
        String note = Ledger.note(village, "prices");
        try {
            if (note != null && !note.isEmpty()) {
                String[] parts = note.split("\\|");
                t.day = Long.parseLong(parts[0].split(";")[1]);
                for (int i = 1; i < parts.length; i++) {
                    String[] f = parts[i].split("~", -1);
                    if (f.length < 10) continue;
                    Line l = new Line(f[0]);
                    l.factor = clamp(Double.parseDouble(f[1]));
                    l.target = Double.parseDouble(f[2]);
                    l.demand = Double.parseDouble(f[3]);
                    l.output = Double.parseDouble(f[4]);
                    l.refused = Double.parseDouble(f[5]);
                    l.stock = Integer.parseInt(f[6]);
                    String[] past = f[7].split(",");
                    for (int k = 0; k < HISTORY && k < past.length; k++) l.past[k] = Double.parseDouble(past[k]);
                    l.told = Long.parseLong(f[8]);
                    l.why = f[9];
                    if (f.length > 10) {
                        String[] d = f[10].split(",");
                        if (d.length >= 5) {
                            l.dayWanted = Integer.parseInt(d[0]);
                            l.dayMissed = Integer.parseInt(d[1]);
                            l.dayMade = Integer.parseInt(d[2]);
                            l.dayRefused = Integer.parseInt(d[3]);
                            l.dayBargains = Integer.parseInt(d[4]);
                        }
                    }
                    t.lines.put(l.key, l);
                }
            }
            String tally = Ledger.note(village, "prices.tally");
            if (tally != null && !tally.isEmpty()) {
                for (String part : tally.split("\\|")) {
                    String[] f = part.split("~");
                    if (f.length < 2) continue;
                    String[] n = f[1].split(",");
                    int[] a = new int[4];
                    for (int k = 0; k < 4 && k < n.length; k++) a[k] = Integer.parseInt(n[k]);
                    t.tally.put(f[0], a);
                }
            }
        } catch (RuntimeException e) {
            return new Town(village);                            // a torn page: the prices start again at the usual worth
        }
        return t;
    }

    private static String f3(double d) {
        return String.format(Locale.ROOT, "%.3f", d);
    }

    // ------------------------------------------------------------------ /village prices

    /**
     * /village prices — each good's price today against its usual worth, which way it is going, supply against
     * demand, what folk thought too dear and the bargains, and the cost of living against the lowest wage.
     * <pre>
     *   /village prices          the nearest town's prices, in chat
     *   /village prices page     the town's books, opened at the Prices page
     *   /village prices now      (ops) the prices reckoned now, as the morning does
     *   /village prices shop     (ops) the shop's counters dressed afresh, their price signs with them; "SHOP x y z", and
 *                            "SIGN x y z out north: 8 Bread / 3 coins / ↑ dearer" for each sign
     * </pre>
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("prices")
            .executes(ctx -> chat(ctx))
            .then(Commands.literal("page").executes(PriceIndex::openPage))
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = near(ctx);
                if (v == null) return 0;
                reckonNow(ctx.getSource().getLevel(), v);
                return chat(ctx);
            }))
            .then(Commands.literal("shop").requires(src -> src.hasPermission(2)).executes(PriceIndex::shop));
    }

    @Nullable
    private static Villages.Village near(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos here = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, here, Villages.VILLAGE_RANGE * 4);
        if (v == null && ctx.getSource().getPlayer() == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int chat(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        String text = "PRICES " + Villages.name(v.id()) + "\n" + page(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int openPage(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return chat(ctx);
        CompoundTag books = Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "Prices");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    private static int shop(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        BlockPos shop = Villages.builtAt(v.id(), "shop");
        if (shop == null) {
            ctx.getSource().sendFailure(Component.literal(Villages.name(v.id()) + " has no shop yet."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        String kept = Cafe.keepShop(level, v);
        StringBuilder sb = new StringBuilder("SHOP " + shop.getX() + " " + shop.getY() + " " + shop.getZ() + " "
            + (Purchases.open(v.id()) ? "open" : "shut") + (kept == null ? "" : ": " + kept));
        // Each counter's price sign, where it stands and which way it faces out, and what it says.
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            if (!b.structure().equals("shop")) continue;
            for (BlockPos counter : Cafe.counters(b)) {
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                    BlockPos at = counter.relative(d);
                    if (!(level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign)) continue;
                    List<String> said = new ArrayList<>();
                    for (int i = 0; i < 4; i++) {
                        String t = sign.getFrontText().getMessage(i, false).getString();
                        if (!t.isEmpty()) said.add(t);
                    }
                    sb.append("\nSIGN ").append(at.getX()).append(' ').append(at.getY()).append(' ').append(at.getZ()).append(" out ")
                        .append(d.getName()).append(": ").append(String.join(" / ", said));
                }
            }
        }
        String line = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }
}
