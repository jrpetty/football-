package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [econ4] Trade between towns on the market: the merchants who buy where a thing is cheap and sell where it is dear.
 *
 * <p><b>What a town knows of the others' prices.</b> Each morning every town's market writes its price sheet: its ask and
 * its bid for each of the staples that travel (grain and bread, roots, meat and fish, timber, stone, coal and the metals,
 * wool, leather, glass). A town knows a partner's sheet only as its traders bring it in: the sheet as it stood as many days
 * ago as a laden cart takes on the road between them (two thousand four hundred blocks a day, a day at the least). A
 * partner is a town it has a road to (a neighbour, its colony or its mother: RoadWorks.roads) and is not at war with.
 *
 * <p><b>The merchant's sum.</b> In its morning a town's market, as merchant, looks over what each partner asks for each
 * staple (as known), adds the carriage (a fiftieth of a coin a piece for every thousand blocks of road) and its own gate
 * toll (three in the hundred of the value, ten between rivals), and buys when that comes to under nine tenths of what the
 * thing is worth at home: a tenth for the merchant's trouble and risk. It buys at the seller's ask on the day (its sheet
 * may be stale: the deal is struck at today's price), no more than the seller can spare over what it keeps for itself, no
 * more than half the difference between the two towns' stocks (so the gap narrows rather than turns over), a wagon's load
 * at most (sixty-four) a ware and three wares a partner a day, and only out of a quarter of its own cash.
 *
 * <p><b>Every coin booked.</b> The buyer's market pays the seller's market for the goods and the seller's carters for the
 * carriage there and then (the buyer's IMPORT, the seller's EXPORT in the coin census, both in the same tick), and its own
 * treasury the gate toll (inside the one town). The goods leave the seller's stores on the market's own account (no
 * household or firm charged for them) and go by the town's wagon, or by the road (Shipments): they come into the buyer's
 * stores as the market's own stock when they arrive, and the buyer sells them to its folk at its own ask. A load lost on the
 * road is refunded by the seller (booked back both sides).
 *
 * <p><b>What it does.</b> The importer's stock rises and the exporter's falls, so the next morning's prices (PriceIndex, by
 * supply and demand) move toward each other: arbitrage narrows the gap between linked towns over time, a town short of a
 * thing imports it, one with a glut exports it. A load delivered warms the two towns' relations a little (a point a day
 * between a pair at most): good trade makes good neighbours (Diplomacy). Each town keeps its trade book, ware by ware and
 * partner by partner, for the Economy dashboard and {@code /village economy}.
 */
public final class EconRegion {

    private EconRegion() {}

    private static final Logger LOG = LogUtils.getLogger();

    /** The staples that travel between towns. */
    static final List<Item> GOODS = List.of(Items.WHEAT, Items.BREAD, Items.POTATO, Items.CARROT, Items.BEETROOT, Items.APPLE,
        Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COD, Items.COOKED_COD, Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG,
        Items.OAK_PLANKS, Items.COBBLESTONE, Items.STONE, Items.COAL, Items.CHARCOAL, Items.IRON_INGOT, Items.RAW_IRON,
        Items.COPPER_INGOT, Items.RAW_COPPER, Items.WHITE_WOOL, Items.LEATHER, Items.GLASS, Items.SAND, Items.CLAY_BALL);

    /** How far news and goods go in a day: a laden cart's day on the road. */
    static final int BLOCKS_A_DAY = Shipments.ROAD_BLOCKS_A_DAY;
    /** The carriage, coin a piece for every thousand blocks of road. */
    static final double CARRIAGE = 0.02;
    /** The buyer's gate toll on the value, in the hundred: between friends, and between rivals. */
    static final int TOLL = 3, TOLL_RIVALS = 10;
    /** What the merchant wants for its trouble and risk: it buys at under this share of the thing's worth at home. */
    static final double MARGIN = 0.90;
    /** A wagon's load of a ware, and the wares a partner a day. */
    static final int LOAD = 64, WARES_A_DAY = 3;
    /** The share of its cash a market puts into trade in a morning. */
    static final double CASH_SHARE = 0.25;
    /** The furthest two towns trade over, blocks. */
    static final int FARTHEST = 6000;
    /** The days of price sheets kept. */
    static final int SHEETS_KEPT = 14;
    /** The contract key of a merchant's load (Shipments). */
    public static final String CONTRACT = "econ4/";

    // ------------------------------------------------------------------ the books

    static CompoundTag data(UUID village) {
        return Accounts.sub(Accounts.town(village), "econ4");
    }

    static CompoundTag trade(UUID village) {
        return Accounts.sub(data(village), "trade");
    }

    private static volatile boolean onForTests = false;
    private static volatile boolean plainOff = false;

    /** Tests: inter-town trade at work in the tests' plain towns (it is off there unless a test asks). */
    public static void liveForTests(boolean on) {
        onForTests = on;
    }

    public static void resetForTests() {
        onForTests = false;
        plainOff = !com.jrpetty.mcassistant.SessionReset.opening();
    }

    static boolean enabled() {
        return onForTests || !plainOff;
    }

    static Shipments.Listener LISTENER = new Shipments.Listener() {
        @Override
        public void delivered(ServerLevel level, Shipments.Shipment s, List<ItemStack> arrived, int paid) {
            EconRegion.delivered(level, s, arrived);
        }

        @Override
        public void lost(ServerLevel level, Shipments.Shipment s, String why) {
            EconRegion.lost(level, s, why);
        }
    };

    static {
        Shipments.listen(CONTRACT, LISTENER);
    }

    /** Make sure the listener is registered (class loaded). */
    public static void init() {}

    // ------------------------------------------------------------------ the morning

    /**
     * The town's morning (Market.tick, with the rest of its economy): its price sheet written, then its market's round as
     * merchant among its partners.
     */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        if (!enabled() || TownLod.away(v.id()) || !MarketMaker.live(v.id())) return;
        publish(level, v.id(), day);
        CompoundTag d = data(v.id());
        if (d.getLong("tradedDay") >= day) return;
        d.putLong("tradedDay", day);
        merchant(level, v, day);
    }

    /** Today's sheet: the ask and the bid (pennies) and the stock of every staple, kept for the partners to learn of. */
    public static void publish(ServerLevel level, UUID village, long day) {
        CompoundTag sheets = Accounts.sub(data(village), "sheets");
        Map<Item, Integer> stock = stock(level, village);
        CompoundTag s = new CompoundTag();
        for (Item it : GOODS) {
            ItemStack one = new ItemStack(it);
            CompoundTag g = new CompoundTag();
            g.putInt("ask", (int) Math.round(MarketMaker.ask(level, village, one) * 100));
            g.putInt("bid", (int) Math.round(MarketMaker.bid(level, village, one) * 100));
            g.putInt("mid", (int) Math.round(MarketMaker.mid(level, village, one) * 100));
            g.putInt("n", stock.getOrDefault(it, 0));
            s.put(key(it), g);
        }
        sheets.put(Long.toString(day), s);
        List<String> old = new ArrayList<>();
        for (String k : sheets.getAllKeys()) {
            try { if (Long.parseLong(k) < day - SHEETS_KEPT) old.add(k); } catch (NumberFormatException e) { old.add(k); }
        }
        for (String k : old) sheets.remove(k);
        Accounts.dirty();
    }

    /** What this town knows of a partner's sheet today: the one as many days old as the road takes (or the newest older). */
    @Nullable
    public static CompoundTag known(UUID here, UUID partner, long day) {
        int lag = lagDays(here, partner);
        CompoundTag sheets = Accounts.sub(data(partner), "sheets");
        for (long d = day - lag; d >= day - SHEETS_KEPT; d--) {
            if (sheets.contains(Long.toString(d), Tag.TAG_COMPOUND)) return sheets.getCompound(Long.toString(d));
        }
        return null;
    }

    /** The days news takes between two towns. */
    public static int lagDays(UUID a, UUID b) {
        return Math.max(1, (int) Math.ceil(roadBlocks(a, b) / (double) BLOCKS_A_DAY));
    }

    /** The road between two towns, blocks (the road's own length where one is laid out, else a third over the straight). */
    public static double roadBlocks(UUID a, UUID b) {
        Villages.Village x = Villages.get(a), y = Villages.get(b);
        if (x == null || y == null) return Double.MAX_VALUE;
        RoadWorks.Road r = RoadWorks.road(x, y);
        double straight = Math.sqrt(x.centre().distSqr(y.centre()));
        return r != null && r.line().size() > 0 ? Math.max(straight, r.line().size()) : straight * 1.3;
    }

    /** The towns this one trades with: a road to it (a neighbour, its colony, its mother), the same world, not at war, near enough. */
    public static List<Villages.Village> partners(UUID village) {
        List<Villages.Village> out = new ArrayList<>();
        Villages.Village me = Villages.get(village);
        if (me == null) return out;
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        List<Villages.Village> near = new ArrayList<>();
        for (RoadWorks.Road r : RoadWorks.roads(village)) {
            Villages.Village o = Villages.get(r.a().equals(village) ? r.b() : r.a());
            if (o != null) near.add(o);
        }
        near.addAll(Diplomacy.neighboursOf(village));
        for (Villages.Village o : near) {
            if (o == null || o.id().equals(village) || !seen.add(o.id()) || !o.dim().equals(me.dim())) continue;
            if (Wars.atWar(village, o.id())) continue;
            if (Math.sqrt(me.centre().distSqr(o.centre())) > FARTHEST) continue;
            out.add(o);
        }
        return out;
    }

    /** One look at the stores: how many of each staple. */
    static Map<Item, Integer> stock(ServerLevel level, UUID village) {
        Map<Item, Integer> out = new HashMap<>();
        java.util.Set<Item> want = new java.util.HashSet<>(GOODS);
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && want.contains(s.getItem()) && s.getComponentsPatch().isEmpty()) out.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        return out;
    }

    /** What a town keeps of a staple for itself before it sells any to another town. */
    static int keeps(UUID village, Item it) {
        int folk = Math.max(4, Villages.headcount(village));
        return 32 + folk * 2;
    }

    /** One trade the merchant would make: the ware, how many, from whom, what it pays. */
    public record Deal(Item item, int n, UUID from, long goods, long carriage, long toll) {
        public long total() { return goods + carriage + toll; }
    }

    /**
     * The market's round as merchant: for each partner, the staples it can buy there cheaper, carriage and toll paid, than
     * they are worth at home; bought, paid for and sent. Returns what it bought.
     */
    public static List<Deal> merchant(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        List<Deal> done = new ArrayList<>();
        Map<Item, Integer> mine = null;
        long budget = (long) (MarketMaker.cash(id) * CASH_SHARE);
        for (Villages.Village p : partners(id)) {
            if (budget < 100) break;
            if (!MarketMaker.live(p.id()) || TownLod.away(p.id())) continue;
            CompoundTag sheet = known(id, p.id(), day);
            if (sheet == null) continue;
            if (mine == null) mine = stock(level, id);
            Map<Item, Integer> theirs = null;
            double road = roadBlocks(id, p.id());
            int toll = Ledger.relation(id, p.id()) <= Diplomacy.UNEASY ? TOLL_RIVALS : TOLL;
            // The best buys first: by how far under its worth here it comes.
            List<Object[]> buys = new ArrayList<>();
            for (Item it : GOODS) {
                CompoundTag g = sheet.getCompound(key(it));
                if (g.isEmpty() || g.getInt("ask") <= 0) continue;
                double theirAsk = g.getInt("ask") / 100.0;
                double here = MarketMaker.mid(level, id, new ItemStack(it));
                double landed = theirAsk * (1 + toll / 100.0) + CARRIAGE * road / 1000.0;
                if (landed < here * MARGIN) buys.add(new Object[]{ it, landed / here });
            }
            buys.sort((a, b) -> Double.compare((double) a[1], (double) b[1]));
            int wares = 0;
            for (Object[] b : buys) {
                if (wares >= WARES_A_DAY || budget < 100) break;
                Item it = (Item) b[0];
                if (theirs == null) theirs = stock(level, p.id());
                int spare = theirs.getOrDefault(it, 0) - keeps(p.id(), it);
                int gap = (theirs.getOrDefault(it, 0) - mine.getOrDefault(it, 0)) / 2;
                int n = Math.min(LOAD, Math.min(spare, gap));
                if (n < 4) continue;
                ItemStack one = new ItemStack(it);
                double ask = MarketMaker.ask(level, p.id(), one);                 // struck at the seller's price today
                double here = MarketMaker.mid(level, id, one);
                double landed = ask * (1 + toll / 100.0) + CARRIAGE * road / 1000.0;
                if (landed >= here * MARGIN) continue;                            // the sheet was stale: not worth it now
                // Whole coins: the goods, the carriage, the toll.
                n = (int) Math.min(n, budget / Math.max(1.0, landed * 100));
                if (n < 4) continue;
                long goods = coinsUp(ask * n), carriage = coinsUp(CARRIAGE * road / 1000.0 * n), tax = coinsUp(ask * n * toll / 100.0);
                Deal deal = new Deal(it, n, p.id(), goods, carriage, tax);
                if (MarketMaker.cash(id) < deal.total()) continue;
                if (!strike(level, v, p, deal, day)) continue;
                done.add(deal);
                budget -= deal.total();
                mine.merge(it, n, Integer::sum);
                theirs.merge(it, -n, Integer::sum);
                wares++;
            }
        }
        return done;
    }

    /** Whole coins, in pennies, rounded up (a coin at the least). */
    static long coinsUp(double coins) {
        return Math.max(100, (long) Math.ceil(coins - 1e-9) * 100L);
    }

    /** The deal struck: the goods out of the seller's stores and on the road, the coin paid both sides, the books written. */
    static boolean strike(ServerLevel level, Villages.Village buyer, Villages.Village seller, Deal d, long day) {
        UUID b = buyer.id(), s = seller.id();
        String contract = CONTRACT + b + "/" + key(d.item());
        Shipments.Shipment sh;
        try (Accounts.Scope market = Accounts.as(Accounts.Dept.MARKET, Accounts.Purpose.WORK)) {
            sh = Shipments.dispatch(level, s, b, contract, List.of(new ItemStack(d.item(), d.n())), 0);
        }
        if (sh == null) return false;
        long out = MarketMaker.cashOut(b, d.total());
        if (out < d.total()) {                                                 // never: checked above; back as it was
            MarketMaker.cashIn(b, out);
            LOG.warn("[MCA-ECON4] {} could not pay for {}: the load goes on unpaid", Villages.name(b), d);
            return true;
        }
        // The goods to the seller's market (its export), the carriage to its carters (the treasury), the toll at home.
        MarketMaker.exported(s, (int) (d.goods() / 100));
        Businesses.toTreasury(s, d.carriage());
        Businesses.toTreasury(b, d.toll());
        CoinCensus.flow(b, CoinCensus.Flow.IMPORT, (d.goods() + d.carriage()) / 100);
        CoinCensus.flow(s, CoinCensus.Flow.EXPORT, (d.goods() + d.carriage()) / 100);
        String k = key(d.item());
        MarketMaker.book(b, k, d.n(), d.goods(), true);
        Accounts.add(MarketMaker.day(b), "imports", d.goods());
        TownBudget.in(b, "gate tolls", d.toll());
        book(b, "in", seller.id(), d.item(), d.n(), d.goods() + d.carriage() + d.toll(), day);
        book(s, "out", b, d.item(), d.n(), d.goods() + d.carriage(), day);
        CompoundTag pending = Accounts.sub(data(b), "pending");
        CompoundTag pd = new CompoundTag();
        pd.putString("from", s.toString());
        pd.putLong("goods", d.goods());
        pd.putLong("carriage", d.carriage());
        pd.putInt("n", d.n());
        pending.put(sh.id().toString(), pd);
        Accounts.dirty();
        Villages.tell(b, day, "the market's merchant bought " + d.n() + " " + name(d.item()) + " of " + Villages.name(s) + " at "
            + Businesses.pence(d.goods() / d.n()) + " each (" + Businesses.pence(d.total()) + " with the carriage and the toll), cheaper than at home");
        EconCycle.newRoute(b, s, day);
        return true;
    }

    /** A merchant's load came in: the market's own stock now, the books closed, the two towns a little warmer. */
    static void delivered(ServerLevel level, Shipments.Shipment s, List<ItemStack> arrived) {
        CompoundTag pending = Accounts.sub(data(s.to()), "pending");
        pending.remove(s.id().toString());
        long day = level.getDayTime() / 24000L;
        CompoundTag d = data(s.to());
        String pair = "warm/" + s.from();
        if (d.getLong(pair) < day) {
            d.putLong(pair, day);
            Ledger.relate(s.from(), s.to(), 1);
        }
        Accounts.add(trade(s.to()), "loadsIn", 1);
        Accounts.add(trade(s.from()), "loadsOut", 1);
        Accounts.dirty();
    }

    /** A merchant's load lost on the road: the goods saved back to the seller, the price refunded to the buyer. */
    static void lost(ServerLevel level, Shipments.Shipment s, String why) {
        CompoundTag pending = Accounts.sub(data(s.to()), "pending");
        CompoundTag pd = pending.getCompound(s.id().toString());
        pending.remove(s.id().toString());
        long goods = pd.getLong("goods"), carriage = pd.getLong("carriage");
        if (goods + carriage > 0) {
            // The goods' price out of the seller's market, the carriage out of its treasury (its carters never delivered).
            long back = MarketMaker.cashOut(s.from(), goods), cart = Businesses.fromTreasury(s.from(), carriage);
            MarketMaker.cashIn(s.from(), back % 100);                          // whole coins between towns: the odd pennies stay
            Businesses.toTreasury(s.from(), cart % 100);
            back -= back % 100;
            cart -= cart % 100;
            MarketMaker.cashIn(s.to(), back + cart);
            CoinCensus.flow(s.from(), CoinCensus.Flow.IMPORT, (back + cart) / 100);
            CoinCensus.flow(s.to(), CoinCensus.Flow.EXPORT, (back + cart) / 100);
            Villages.tell(s.to(), level.getDayTime() / 24000L, Villages.name(s.from()) + " paid back " + Businesses.pence(back + cart) + " for the load lost on the road");
        }
        Accounts.dirty();
    }

    // ------------------------------------------------------------------ the trade book

    /** A trade in the town's book: in (bought of another town) or out (sold to one), by ware and by partner, today's and all. */
    static void book(UUID village, String dir, UUID partner, Item it, int n, long pence, long day) {
        CompoundTag t = trade(village);
        CompoundTag days = Accounts.sub(t, "days");
        CompoundTag today = Accounts.sub(days, Long.toString(day));
        Accounts.add(today, dir, pence);
        Accounts.add(Accounts.sub(today, dir + "Goods"), key(it), pence);
        Accounts.add(Accounts.sub(today, dir + "Partners"), partner.toString(), pence);
        Accounts.add(Accounts.sub(today, dir + "N"), key(it), n);
        Accounts.add(t, dir + "All", pence);
        List<String> old = new ArrayList<>();
        for (String k : days.getAllKeys()) {
            try { if (Long.parseLong(k) < day - 30) old.add(k); } catch (NumberFormatException e) { old.add(k); }
        }
        for (String k : old) days.remove(k);
    }

    /** The last {@code n} days' trade (today in): {in, out} pennies. */
    public static long[] lastDays(UUID village, long day, int n) {
        long[] out = new long[2];
        CompoundTag days = Accounts.sub(trade(village), "days");
        for (long d = day - n + 1; d <= day; d++) {
            CompoundTag t = days.getCompound(Long.toString(d));
            out[0] += t.getLong("in");
            out[1] += t.getLong("out");
        }
        return out;
    }

    /** One day's trade {in, out}, pennies. */
    public static long[] onDay(UUID village, long day) {
        CompoundTag t = Accounts.sub(trade(village), "days").getCompound(Long.toString(day));
        return new long[]{ t.getLong("in"), t.getLong("out") };
    }

    /** The last week's trade by ware or partner ("Goods" or "Partners"), one way: pennies by name, the biggest first. */
    public static Map<String, Long> week(UUID village, long day, String dir, String by) {
        Map<String, Long> sum = new HashMap<>();
        CompoundTag days = Accounts.sub(trade(village), "days");
        for (long d = day - 6; d <= day; d++) {
            CompoundTag g = days.getCompound(Long.toString(d)).getCompound(dir + by);
            for (String k : g.getAllKeys()) sum.merge(k, g.getLong(k), Long::sum);
        }
        Map<String, Long> out = new LinkedHashMap<>();
        sum.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).forEach(e -> {
            String name = e.getKey();
            if (by.equals("Partners")) {
                try { name = Villages.name(UUID.fromString(name)); } catch (IllegalArgumentException ignored) { }
            } else {
                Item it = item(name);
                if (it != null) name = name(it);
            }
            out.merge(name, e.getValue(), Long::sum);
        });
        return out;
    }

    /** The town's trade in a few lines (the dashboard, /village economy). */
    public static List<String> lines(ServerLevel level, UUID village) {
        long day = level.getDayTime() / 24000L;
        List<String> out = new ArrayList<>();
        List<Villages.Village> ps = partners(village);
        if (ps.isEmpty()) {
            out.add("No other town within reach by road to trade with yet.");
            return out;
        }
        List<String> names = new ArrayList<>();
        for (Villages.Village p : ps) names.add(Villages.name(p.id()) + " (" + lagDays(village, p.id()) + (lagDays(village, p.id()) == 1 ? " day" : " days") + " off)");
        out.add("Trades with " + String.join(", ", names) + ".");
        long[] w = lastDays(village, day, 7);
        out.add("This week: bought in " + Businesses.pence(w[0]) + ", sold out " + Businesses.pence(w[1]) + "; in all bought "
            + Businesses.pence(trade(village).getLong("inAll")) + ", sold " + Businesses.pence(trade(village).getLong("outAll")) + ".");
        for (String dir : new String[]{ "in", "out" }) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Long> e : week(village, day, dir, "Goods").entrySet()) {
                if (parts.size() >= 5) break;
                parts.add(e.getKey() + " " + Businesses.pence(e.getValue()));
            }
            List<String> who = new ArrayList<>();
            for (Map.Entry<String, Long> e : week(village, day, dir, "Partners").entrySet()) who.add(e.getKey() + " " + Businesses.pence(e.getValue()));
            if (!parts.isEmpty()) out.add((dir.equals("in") ? "Bought in: " : "Sold out: ") + String.join(", ", parts) + (who.isEmpty() ? "" : " (" + (dir.equals("in") ? "from " : "to ") + String.join(", ", who) + ")") + ".");
        }
        // The widest gaps as its traders know them today.
        List<String> gaps = new ArrayList<>();
        for (Villages.Village p : ps) {
            CompoundTag sheet = known(village, p.id(), day);
            if (sheet == null) continue;
            for (Item it : GOODS) {
                CompoundTag g = sheet.getCompound(key(it));
                if (g.getInt("mid") <= 0) continue;
                double here = MarketMaker.mid(level, village, new ItemStack(it)), there = g.getInt("mid") / 100.0;
                if (here <= 0) continue;
                double r = there / here;
                if (r > 1.25 || r < 0.8) gaps.add(String.format(Locale.ROOT, "%s %s at %s (here %s)", name(it), r > 1 ? "dearer" : "cheaper",
                    Villages.name(p.id()), Businesses.pence(Math.round(here * 100))));
            }
        }
        if (!gaps.isEmpty()) out.add("Prices apart: " + String.join("; ", gaps.subList(0, Math.min(4, gaps.size()))) + ".");
        return out;
    }

    // ------------------------------------------------------------------ names

    static String key(Item it) {
        return BuiltInRegistries.ITEM.getKey(it).toString();
    }

    @Nullable
    static Item item(String key) {
        ResourceLocation rl = ResourceLocation.tryParse(key);
        if (rl == null) return null;
        Item it = BuiltInRegistries.ITEM.get(rl);
        return it == Items.AIR ? null : it;
    }

    static String name(Item it) {
        return BuiltInRegistries.ITEM.getKey(it).getPath().replace('_', ' ');
    }

    // ------------------------------------------------------------------ tests

    /** Tests: every merchant's load under way delivered now (the road's time passed). */
    public static int deliverAllForTests(ServerLevel level) {
        int n = 0;
        for (Villages.Village v : Villages.every()) {
            for (Shipments.Shipment s : Shipments.inTransit(v.id())) {
                if (!s.contract().startsWith(CONTRACT) || !s.from().equals(v.id())) continue;
                Shipments.delivered(level, s.id(), s.manifest());
                n++;
            }
        }
        return n;
    }

    /** Tests: the merchant's round in this town now. */
    public static List<Deal> merchantForTests(ServerLevel level, Villages.Village v) {
        return merchant(level, v, level.getDayTime() / 24000L);
    }

    /** Tests: the pending (paid, not yet delivered) loads of a buyer. */
    public static int pendingForTests(UUID village) {
        return Accounts.sub(data(village), "pending").getAllKeys().size();
    }

    /** Tests: the town's whole trade in and out, pennies. */
    public static long[] allForTests(UUID village) {
        return new long[]{ trade(village).getLong("inAll"), trade(village).getLong("outAll") };
    }
}
