package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [shops-homes] The shop as a business: its till, its weekly look at what it sells, and the makings it wants.
 * <ul>
 * <li><b>The till.</b> What it took over the counter (every sale, the folk's and the players': Stockroom.sold), what
 *     its makings cost (each delivery into its makings chest at the town's price, and what it paid players for them),
 *     and its staff's wages: the day's and the week's, and the profit. On its screen and its stock book.</li>
 * <li><b>The weekly review.</b> Each seventh day the keeper looks over its range (StockKeeper.range: what sells, what
 *     folk asked for and could not have, what their shopping lists want, the fashions, the age's new makes). A line new
 *     since last week is announced ("New at Wren's: cake!" on the board and in the chronicle) and shows NEW on its
 *     screen; a line gone off it since is cleared at a markdown (never under the least the town lets a lot go for).</li>
 * <li><b>Wanted.</b> Makings its chest is short of that the stores cannot send (WorkStock): the shop buys them from
 *     players at a good price (a little over the town's paying price, always under the least it sells for, so no loop
 *     pays), into its makings chest, paid out of the treasury and booked to the till.</li>
 * </ul>
 */
public final class ShopBusiness {

    private ShopBusiness() {}

    /** Days between reviews; how long a line shows NEW; the clearance markdown (a share off, before the floor). */
    static final int REVIEW_DAYS = 7, NEW_DAYS = 7;
    static final double CLEARANCE = 0.15;
    /** What the shop offers for a wanted making against the town's paying price, and never more than this share of the
     *  least it lets a lot go for. */
    static final double WANTED_PREMIUM = 1.15, UNDER_LEAST = 0.98;

    /** One day's till: took, makings, wages. */
    private static final class Day {
        long day;
        int took, makings, wages;
    }

    private static final class Book {
        final Map<Long, Day> days = new LinkedHashMap<>();
        Set<String> lines = Set.of();
        long reviewed = -1;
        final Map<String, Long> fresh = new ConcurrentHashMap<>();
        final Map<String, Long> clearance = new ConcurrentHashMap<>();
        final List<String> lastReview = new ArrayList<>();
    }

    private static final Map<UUID, Book> BOOKS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        BOOKS.clear();
    }

    private static Book book(UUID village) {
        return BOOKS.computeIfAbsent(village, k -> new Book());
    }

    private static Day day(UUID village, long day) {
        Book b = book(village);
        Day d = b.days.computeIfAbsent(day, k -> { Day x = new Day(); x.day = k; return x; });
        while (b.days.size() > REVIEW_DAYS + 1) b.days.remove(b.days.keySet().iterator().next());
        return d;
    }

    // ------------------------------------------------------------------ the till

    /** A sale over the counter, into the till (Stockroom.sold, for the shop). */
    static void took(UUID village, long day, int coin) {
        if (coin > 0) day(village, day).took += coin;
    }

    /** Makings come in, at what they cost. */
    static void makings(UUID village, long day, int coin) {
        if (coin > 0) day(village, day).makings += coin;
    }

    /** The day's wages of the shop's staff (its keeper, hands, assistants and stock keeper). */
    static int wagesToday(UUID village) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == AssistantEntity.StationTask.SHOP && !a.isBaby()) n += Market.wage(a);
        return n;
    }

    /** The till over the last so many days: {took, makings, wages, profit}. */
    public static int[] till(ServerLevel level, UUID village, int days) {
        long today = level.getDayTime() / 24000L;
        int took = 0, makings = 0;
        for (Day d : book(village).days.values()) {
            if (today - d.day >= days) continue;
            took += d.took;
            makings += d.makings;
        }
        int wages = wagesToday(village) * days;
        return new int[]{ took, makings, wages, took - makings - wages };
    }

    /** "This week: took 42, makings 12, wages 21: 9 to the good." */
    public static String tillLine(ServerLevel level, UUID village) {
        int[] w = till(level, village, REVIEW_DAYS), t = till(level, village, 1);
        return "Today: took " + t[0] + ", makings " + t[1] + ", wages " + t[2] + ". This week: took " + w[0] + ", makings " + w[1]
            + ", wages " + w[2] + ": " + (w[3] >= 0 ? w[3] + " to the good." : -w[3] + " short.");
    }

    // ------------------------------------------------------------------ the weekly review

    /** Every seventh day (and the first time it is asked): the range looked over, new lines announced, gone ones cleared. */
    static void review(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        Book b = book(v.id());
        if (b.reviewed >= 0 && day - b.reviewed < REVIEW_DAYS && day >= b.reviewed) return;
        reviewNow(level, v, day);
    }

    /** The review, now: what is new, what has gone. Returns what it said. */
    static List<String> reviewNow(ServerLevel level, Villages.Village v, long day) {
        Book b = book(v.id());
        UUID id = v.id();
        Map<String, ItemStack> now = new LinkedHashMap<>();
        for (StockKeeper.Ware w : StockKeeper.range(level, v, StockKeeper.book(level, id))) now.put(w.key(), w.sample());
        List<String> said = new ArrayList<>();
        boolean first = b.reviewed < 0;
        b.reviewed = day;
        if (!first) {
            String keeper = keeperName(id);
            int told = 0;
            for (Map.Entry<String, ItemStack> e : now.entrySet()) {
                if (b.lines.contains(e.getKey())) continue;
                b.fresh.put(e.getKey(), day);
                b.clearance.remove(e.getKey());
                String name = Goods.name(e.getValue()).toLowerCase(java.util.Locale.ROOT);
                said.add("new: " + e.getKey());
                if (told++ < 3) {
                    Villages.tell(id, day, "new at " + keeper + ": " + name);
                    Market.assemblyNews(id, "New at " + keeper + ": " + name + "!");
                }
            }
            for (String key : b.lines) {
                if (now.containsKey(key)) continue;
                b.clearance.put(key, day);
                said.add("cleared: " + key);
            }
        }
        b.lines = Set.copyOf(now.keySet());
        b.fresh.entrySet().removeIf(e -> day - e.getValue() > NEW_DAYS);
        b.clearance.entrySet().removeIf(e -> now.containsKey(e.getKey()) || day - e.getValue() > 4L * REVIEW_DAYS);
        b.lastReview.clear();
        b.lastReview.addAll(said);
        return said;
    }

    private static String keeperName(UUID village) {
        VillageFolkEntity k = Workshop.keeper(village);
        return k == null ? "the shop" : k.displayNameCap() + "'s";
    }

    /** Is this line new this week (its screen's NEW)? */
    static boolean isNew(UUID village, ItemStack one) {
        return book(village).fresh.containsKey(Stockroom.key(one));
    }

    /** Is this line being cleared (gone off the range: sold off at a markdown)? */
    static boolean onClearance(UUID village, ItemStack one) {
        return book(village).clearance.containsKey(Stockroom.key(one));
    }

    /** A line on clearance, marked down: a share off (the caller holds it to the town's least: ShopStock.lotPrice). */
    static int cleared(UUID village, ItemStack one, int price) {
        return onClearance(village, one) ? Math.max(1, (int) Math.round(price * (1.0 - CLEARANCE))) : price;
    }

    // ------------------------------------------------------------------ wanted

    /** A making the shop wants of players: one of it, how many, and what it pays for that many. */
    public record Wanted(ItemStack sample, int count, int price) {}

    /** What the shop's makings chest is short of and the stores cannot send: wanted, at a good price. */
    public static List<Wanted> wanted(ServerLevel level, Villages.Village v) {
        List<Wanted> out = new ArrayList<>();
        BlockPos chest = WorkStock.chestOf(level, v.id(), AssistantEntity.StationTask.SHOP);
        if (chest == null || !(level.getBlockEntity(chest) instanceof Container c)) return out;
        for (WorkStock.Making k : WorkStock.MAKINGS.get(AssistantEntity.StationTask.SHOP)) {
            int have = 0;
            for (int i = 0; i < c.getContainerSize(); i++) if (k.is().test(c.getItem(i))) have += c.getItem(i).getCount();
            int short_ = k.keep() - have - Market.stock(level, v.id(), k.is()) / 2;
            if (have >= k.keep() * WorkStock.LOW || short_ <= 0) continue;
            ItemStack one = new ItemStack(k.sample());
            int n = Math.min(short_, k.keep() - have);
            int price = wantedPrice(level, v.id(), one, n);
            if (price > 0) out.add(new Wanted(one, n, price));
        }
        return out;
    }

    /** What the shop pays for so many of a wanted making: over the town's bid, under the least it sells for. */
    static int wantedPrice(ServerLevel level, UUID village, ItemStack one, int n) {
        double bid = Market.bidEach(level, village, one) * WANTED_PREMIUM * n;
        double least = Market.leastLot(level, village, one, n) * UNDER_LEAST;
        return (int) Math.floor(Math.min(bid, least) + 1e-9);
    }

    /** A player brings a wanted making: up to what is wanted, into the shop's makings chest, paid out of the treasury. */
    static ServicePoints.Result supply(ServicePoints.Visit visit, String lineId, int n) {
        ServerLevel level = visit.level();
        Villages.Village v = visit.village();
        ServerPlayer p = visit.player();
        if (p == null) return ServicePoints.Result.no("");
        Wanted w = null;
        for (Wanted x : wanted(level, v)) if (Goods.lineId(x.sample()).equals(lineId)) w = x;
        if (w == null) return ServicePoints.Result.no("The shop has enough of that now.");
        int have = 0;
        for (ItemStack s : p.getInventory().items) if (!s.isEmpty() && Goods.lineId(s).equals(lineId)) have += s.getCount();
        int k = Math.min(Math.min(n <= 0 ? w.count() : n, w.count()), have);
        if (k <= 0) return ServicePoints.Result.no("You've none of that on you.");
        int price = wantedPrice(level, v.id(), w.sample(), k);
        if (price <= 0) return ServicePoints.Result.no("That few isn't worth a coin; bring more.");
        if (Ledger.coins(v.id()) < price) return ServicePoints.Result.no("The treasury hasn't the coin just now.");
        BlockPos chest = WorkStock.chestOf(level, v.id(), AssistantEntity.StationTask.SHOP);
        if (chest == null || !(level.getBlockEntity(chest) instanceof Container c)) return ServicePoints.Result.no("The shop has nowhere to put it.");
        ItemStack left = Stacking.insert(c, w.sample().copyWithCount(k));
        int in = k - left.getCount();
        if (in <= 0) return ServicePoints.Result.no("The shop's makings chest is full.");
        if (in < k) price = wantedPrice(level, v.id(), w.sample(), in);
        int toTake = in;
        for (ItemStack s : p.getInventory().items) {
            if (toTake <= 0) break;
            if (s.isEmpty() || !Goods.lineId(s).equals(lineId)) continue;
            int t = Math.min(toTake, s.getCount());
            s.shrink(t);
            toTake -= t;
        }
        p.getInventory().setChanged();
        int paid = Ledger.takeCoins(v.id(), price);
        Economy.spent(v.id(), paid);
        ItemStack coin = new ItemStack(McAssistantMod.VILLAGE_COIN.get(), paid);
        if (!p.getInventory().add(coin)) p.drop(coin, false);
        long day = level.getDayTime() / 24000L;
        makings(v.id(), day, paid);
        ShopDesk.receipt(level, v.id(), p.getUUID(), "Day " + day + ": supplied " + Goods.words(w.sample(), in) + " for " + paid);
        return ServicePoints.Result.done("Just what we wanted: " + Goods.words(w.sample(), in) + " for " + paid + (paid == 1 ? " coin." : " coins."));
    }

    /** The keeper's word on its business (its card, and what it says of its work): what sells best this week, what it
     *  is short of for its bench, what is new, and the till. Null for anybody but the shop's keeper. */
    @javax.annotation.Nullable
    public static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || !(f.level() instanceof ServerLevel level) || f != Workshop.keeper(id)) return null;
        Villages.Village v = Villages.get(id);
        if (v == null) return null;
        StockKeeper.Book sk = StockKeeper.book(level, id);
        String best = null;
        int most = 0;
        for (StockKeeper.Line l : sk.lines.values()) {
            int n = StockKeeper.Line.week(l.sold);
            if (n > most) { most = n; best = l.key; }
        }
        StringBuilder sb = new StringBuilder();
        if (best != null) sb.append("Selling best: ").append(Stockroom.nameOf(best).toLowerCase(java.util.Locale.ROOT)).append(" (").append(most).append(" this week). ");
        List<Wanted> short_ = wanted(level, v);
        if (!short_.isEmpty()) sb.append("Short of ").append(Goods.words(short_.get(0).sample(), short_.get(0).count())).append(" for the bench. ");
        for (String said : book(id).lastReview) {
            if (!said.startsWith("new: ")) continue;
            sb.append("New this week: ").append(Stockroom.nameOf(said.substring(5)).toLowerCase(java.util.Locale.ROOT)).append(". ");
            break;
        }
        sb.append(tillLine(level, id));
        return sb.toString();
    }

    /** Its trade explained, and for the shop's keeper its business in its own words. */
    public static String talk(VillageFolkEntity f, String trade) {
        String b = cardLine(f);
        return b == null ? trade : trade + " As for the business: " + b;
    }

    /** What the last review said (the shop's page, the tests). */
    public static List<String> lastReview(UUID village) {
        return List.copyOf(book(village).lastReview);
    }

    /** Tests: the review now, whatever the day. */
    public static List<String> reviewForTests(ServerLevel level, Villages.Village v) {
        return reviewNow(level, v, level.getDayTime() / 24000L);
    }

    /** Tests: wanted makings, as "item x n for price". */
    public static List<String> wantedForTests(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        for (Wanted w : wanted(level, v)) out.add(Goods.lineId(w.sample()) + " x" + w.count() + " for " + w.price());
        return out;
    }
}
