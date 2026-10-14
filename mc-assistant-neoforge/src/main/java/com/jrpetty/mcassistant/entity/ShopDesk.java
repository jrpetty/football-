package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [shops-homes] The shop's (and the store's, and the café's) desk: what is for sale and at what price, a basket kept on
 * the server, buying it, selling to the town, ordering what is not in, and haggling. The prices are the town's one
 * asking price (ShopStock.lotPrice: Market.askLot, the keeper's margin, the slow-seller markdown) with the player's
 * standing on top (a friend and a citizen a tenth off each, a haggle, a quest's medal; the unwelcome pay double), and
 * never under the least the town lets a lot go for (Market.leastLot: nine tenths). Goods leave the shop's own casks
 * and chests (or, short, the stores', booked as a stock-out); the coin goes to the treasury. What the town buys is
 * paid at its one paying price (Market.bidLot: four fifths). The same desk serves the folk (folkBuy).
 */
public final class ShopDesk implements ServicePoints.Desk {

    static final ShopDesk INSTANCE = new ShopDesk();

    private ShopDesk() {}

    /** The most of one line a basket holds, and the most lines. */
    static final int MOST_OF_ONE = 64, MOST_LINES = 12;
    /** The receipts a player's ledger keeps, per town. */
    static final int RECEIPTS = 6;

    @Override
    public String kind(ServiceRole role) {
        return "shop";
    }

    // ------------------------------------------------------------------ what is for sale

    /** One line of the shop's list: a thing, how many there are, what one costs, and what can be done about it. */
    record Line(String id, ItemStack sample, int stock, boolean own, @Nullable Item orderable) {}

    /** The shop's list now: its own stock first, then what the crafts have made for it in the stores, then the rest of
     *  its range (none in: an order). The café's: its menu. */
    static List<Line> lines(ServerLevel level, Villages.Village v, ServiceRole role) {
        UUID id = v.id();
        Map<String, Line> out = new LinkedHashMap<>();
        if (role == ServiceRole.CAFE) {
            for (ItemStack s : Cafe.menuGoods(level, id)) {
                ItemStack one = s.copyWithCount(1);
                int n = Math.min(Budget.spare(level, id, one), Market.stock(level, id, same(one)));
                out.putIfAbsent(Goods.lineId(one), new Line(Goods.lineId(one), one, Math.max(0, n), false, null));
            }
            return new ArrayList<>(out.values());
        }
        Map<String, int[]> own = new LinkedHashMap<>();
        Map<String, ItemStack> samples = new LinkedHashMap<>();
        for (Container c : Store.containers(level, id, true)) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || s.isDamaged() || Market.isCoin(s)) continue;
                String key = Goods.lineId(s);
                own.computeIfAbsent(key, k -> new int[1])[0] += s.getCount();
                samples.putIfAbsent(key, s.copyWithCount(1));
            }
        }
        for (Map.Entry<String, int[]> e : own.entrySet()) {
            ItemStack one = samples.get(e.getKey());
            out.put(e.getKey(), new Line(e.getKey(), one, e.getValue()[0] + spareInStores(level, id, one), true, null));
        }
        for (ItemStack s : Cafe.shopGoods(level, id)) {
            ItemStack one = s.copyWithCount(1);
            out.putIfAbsent(Goods.lineId(one), new Line(Goods.lineId(one), one, spareInStores(level, id, one), false, null));
        }
        for (StockKeeper.Ware w : StockKeeper.range(level, v, StockKeeper.book(level, id))) {
            ItemStack one = w.sample().copyWithCount(1);
            String key = Goods.lineId(one);
            if (out.containsKey(key) || Market.isCoin(one)) continue;
            int n = spareInStores(level, id, one);
            out.put(key, new Line(key, one, n, false, w.make()));
        }
        return new ArrayList<>(out.values());
    }

    private static int spareInStores(ServerLevel level, UUID village, ItemStack one) {
        return Math.max(0, Math.min(Budget.spare(level, village, one), Market.stock(level, village, same(one))));
    }

    static Predicate<ItemStack> same(ItemStack one) {
        return s -> ItemStack.isSameItemSameComponents(s, one);
    }

    @Nullable
    private static Line line(List<Line> lines, String id) {
        for (Line l : lines) if (l.id().equals(id)) return l;
        return null;
    }

    // ------------------------------------------------------------------ the price, the same for everybody

    /** A basket priced: each line, the sum, every discount (or surcharge) as its own line, and the total. */
    record Quote(List<int[]> each, int subtotal, List<String> adjustLabels, List<Integer> adjustments, int total, int floor) {}

    /**
     * What a basket comes to for this player (null: a folk, at the town's price): the town's asking price for each lot,
     * then the player's standing (unwelcome: double; a friend a tenth off; a citizen a tenth off), a haggle or a quest's
     * medal, and never under the town's least for the lot. One function for the screen's running total and the sale.
     */
    static Quote quote(ServerLevel level, UUID village, @Nullable ServerPlayer p, List<ItemStack> ones, List<Integer> counts) {
        List<int[]> each = new ArrayList<>();
        int subtotal = 0, floor = 0;
        for (int i = 0; i < ones.size(); i++) {
            int n = counts.get(i);
            int price = ShopStock.lotPrice(level, village, ones.get(i), n);
            each.add(new int[]{ n, price });
            subtotal += price;
            floor += Market.leastLot(level, village, ones.get(i), n);
        }
        List<String> labels = new ArrayList<>();
        List<Integer> amounts = new ArrayList<>();
        int total = subtotal;
        if (p != null && subtotal > 0) {
            long day = level.getDayTime() / 24000L;
            if (Market.marketDay(village, day)) { labels.add("Market day prices (already in)"); amounts.add(0); }
            Standing.Title title = Standing.of(village, p.getUUID(), level.getGameTime()).title();
            if (title == Standing.Title.UNWELCOME) {
                labels.add("Unwelcome here: double");
                amounts.add(total);
                total *= 2;
            } else if (title.atLeast(Standing.Title.FRIEND)) {
                int off = total / 10;
                if (off > 0) { labels.add("A friend of the town: a tenth off"); amounts.add(-off); total -= off; }
            }
            if (Citizens.is(village, p.getUUID())) {
                int off = Math.max(1, total / 10);
                labels.add("Citizen: a tenth off");
                amounts.add(-off);
                total = Math.max(1, total - off);
            }
            int haggled = Dealings.haggled(village, p.getUUID(), day, total);
            if (haggled < total) { labels.add("Haggled, or a medal of the town's"); amounts.add(haggled - total); total = haggled; }
            if (total < floor) { labels.add("Never under nine tenths of the price"); amounts.add(floor - total); total = floor; }
        }
        return new Quote(each, subtotal, labels, amounts, Math.max(total, floor), floor);
    }

    // ------------------------------------------------------------------ the screen

    @Override
    public int fingerprint(ServicePoints.Visit visit) {
        int h = visit.state().getCompound("basket").hashCode();
        for (Container c : Store.containers(visit.level(), visit.village().id(), true)) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty()) h = h * 31 + s.getItem().hashCode() * 7 + s.getCount();
            }
        }
        if (visit.player() != null) {
            for (ItemStack s : visit.player().getInventory().items) if (!s.isEmpty()) h = h * 17 + s.getCount() + s.getItem().hashCode();
        }
        return h;
    }

    @Override
    public CompoundTag body(ServicePoints.Visit visit, String tab) {
        ServerLevel level = visit.level();
        UUID id = visit.village().id();
        var reg = level.registryAccess();
        CompoundTag t = new CompoundTag();
        List<Line> lines = lines(level, visit.village(), visit.role());
        ListTag items = new ListTag();
        java.util.EnumSet<Goods.Category> cats = java.util.EnumSet.noneOf(Goods.Category.class);
        for (Line l : lines) {
            CompoundTag e = new CompoundTag();
            e.putString("id", l.id());
            e.put("item", l.sample().save(reg));
            Goods.Category cat = Goods.of(l.sample());
            cats.add(cat);
            e.putString("cat", cat.name());
            e.putString("name", Goods.name(l.sample()));
            e.putInt("stock", l.stock());
            e.putInt("each", ShopStock.lotPrice(level, id, l.sample(), 1));
            e.putInt("trend", PriceIndex.trend(id, l.sample()));
            e.putString("hint", PriceIndex.hint(id, l.sample()));
            e.putBoolean("own", l.own());
            if (l.stock() <= 0) {
                String why = l.orderable() == null ? "None in, and nobody here makes it." : orderWhy(level, visit.village(), l.orderable());
                e.putString("why", why);
                e.putBoolean("order", l.orderable() != null && why.startsWith("Order it"));
            }
            e.putString("tip", tip(level, id, l));
            items.add(e);
        }
        t.put("items", items);
        ListTag catList = new ListTag();
        for (Goods.Category c : Goods.Category.values()) {
            if (!cats.contains(c)) continue;
            CompoundTag ct = new CompoundTag();
            ct.putString("id", c.name());
            ct.putString("label", c.label);
            catList.add(ct);
        }
        t.put("cats", catList);
        t.put("quote", quoteTag(visit, lines));
        if (visit.player() != null && visit.role() == ServiceRole.SHOP) t.put("sell", sellTag(visit));
        t.putBoolean("buys", visit.role() == ServiceRole.SHOP);
        t.putBoolean("haggles", visit.keeper() != null);
        ListTag receipts = new ListTag();
        if (visit.player() != null) for (String r : receipts(id, visit.player().getUUID())) receipts.add(net.minecraft.nbt.StringTag.valueOf(r));
        t.put("receipts", receipts);
        return t;
    }

    /** "Order it: ready in about a day and a half", or why it cannot be made yet. */
    private static String orderWhy(ServerLevel level, Villages.Village v, Item it) {
        String no = Workshop.refuse(level, v, it);
        if (!no.isEmpty()) return "Not yet: " + no + ".";
        return "Order it: made at the bench in about " + days(StockKeeper.Route.CRAFTERS.lead) + ".";
    }

    private static String days(double d) {
        return d <= 1.0 ? "a day" : d <= 1.6 ? "a day and a half" : Math.round(d) + " days";
    }

    private static String tip(ServerLevel level, UUID id, Line l) {
        StringBuilder sb = new StringBuilder(Goods.name(l.sample()));
        sb.append(": ").append(Goods.of(l.sample()).label.toLowerCase(Locale.ROOT)).append(". ");
        double usual = PriceIndex.usual(l.sample());
        if (usual > 0) sb.append(String.format(Locale.ROOT, "Usually worth %.1f; ", usual));
        sb.append(l.own() ? "in the shop's own stock" : l.stock() > 0 ? "fetched from the stores at your asking" : "none in");
        sb.append(". Buying it back, the town pays four fifths.");
        return sb.toString();
    }

    /** The basket, priced: its lines, the sum, each discount, the total. */
    private static CompoundTag quoteTag(ServicePoints.Visit visit, List<Line> lines) {
        CompoundTag basket = visit.state().getCompound("basket");
        List<ItemStack> ones = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (String key : basket.getAllKeys()) {
            Line l = line(lines, key);
            int n = Math.min(basket.getInt(key), l == null ? 0 : l.stock());
            if (l == null || n <= 0) continue;
            ones.add(l.sample());
            counts.add(n);
            ids.add(key);
        }
        Quote q = quote(visit.level(), visit.village().id(), visit.player(), ones, counts);
        CompoundTag t = new CompoundTag();
        ListTag ls = new ListTag();
        for (int i = 0; i < ids.size(); i++) {
            CompoundTag e = new CompoundTag();
            e.putString("id", ids.get(i));
            e.putInt("n", q.each().get(i)[0]);
            e.putInt("price", q.each().get(i)[1]);
            ls.add(e);
        }
        t.put("lines", ls);
        t.putInt("subtotal", q.subtotal());
        ListTag adj = new ListTag();
        for (int i = 0; i < q.adjustLabels().size(); i++) {
            CompoundTag e = new CompoundTag();
            e.putString("label", q.adjustLabels().get(i));
            e.putInt("amount", q.adjustments().get(i));
            adj.add(e);
        }
        t.put("adjust", adj);
        t.putInt("total", q.total());
        return t;
    }

    /** What the player carries that the town buys: a line a kind, how many it has, what a lot fetches. */
    private static ListTag sellTag(ServicePoints.Visit visit) {
        ServerLevel level = visit.level();
        UUID id = visit.village().id();
        ServerPlayer p = visit.player();
        Map<String, int[]> have = new LinkedHashMap<>();
        Map<String, ItemStack> samples = new LinkedHashMap<>();
        for (ItemStack s : p.getInventory().items) {
            if (s.isEmpty() || Market.isCoin(s) || s.isDamaged()) continue;
            String key = Goods.lineId(s);
            have.computeIfAbsent(key, k -> new int[1])[0] += s.getCount();
            samples.putIfAbsent(key, s.copyWithCount(1));
        }
        ListTag out = new ListTag();
        for (Map.Entry<String, int[]> e : have.entrySet()) {
            ItemStack one = samples.get(e.getKey());
            int lot = sellLot(one);
            int bid = Market.bidLot(level, id, one, lot);
            CompoundTag t = new CompoundTag();
            t.putString("id", e.getKey());
            t.put("item", one.save(level.registryAccess()));
            t.putString("name", Goods.name(one));
            t.putString("cat", Goods.of(one).name());
            t.putInt("have", e.getValue()[0]);
            t.putInt("lot", lot);
            t.putInt("bid", bid);
            t.putString("why", bid <= 0 ? "Not worth a coin to the town just now." : "");
            out.add(t);
        }
        return out;
    }

    /** How many the town buys at once: the board's lot (eight loaves), else one. */
    static int sellLot(ItemStack one) {
        Market.Good g = Market.goodFor(one);
        return g == null ? 1 : Math.max(1, g.bundle());
    }

    // ------------------------------------------------------------------ the buttons

    @Override
    public ServicePoints.Result act(ServicePoints.Visit visit, String action, CompoundTag args) {
        return switch (action) {
            case "basket" -> basket(visit, args);
            case "buy" -> buy(visit);
            case "sell" -> sell(visit, args);
            case "order" -> order(visit, args);
            case "haggle" -> haggle(visit);
            default -> ServicePoints.Result.no("");
        };
    }

    /** The basket as the screen has it now: so many of each line (kept within the stock and the limits). */
    private static ServicePoints.Result basket(ServicePoints.Visit visit, CompoundTag args) {
        CompoundTag want = args.getCompound("basket");
        CompoundTag kept = new CompoundTag();
        int lines = 0;
        for (String key : want.getAllKeys()) {
            int n = Math.max(0, Math.min(MOST_OF_ONE, want.getInt(key)));
            if (n <= 0 || lines >= MOST_LINES || key.length() > 160) continue;
            kept.putInt(key, n);
            lines++;
        }
        visit.state().put("basket", kept);
        return ServicePoints.Result.said("");
    }

    /**
     * The basket bought: every line checked first (in stock, coin enough for the total), then taken (out of the shop's
     * casks and chests; short, the stores', as a stock-out), paid for (the player's coin to the treasury) and handed
     * over (into the pack; what does not fit, at the player's feet). All of it, or none of it.
     */
    private static ServicePoints.Result buy(ServicePoints.Visit visit) {
        ServerLevel level = visit.level();
        Villages.Village v = visit.village();
        UUID id = v.id();
        ServerPlayer p = visit.player();
        if (p == null) return ServicePoints.Result.no("");
        long day = level.getDayTime() / 24000L;
        Standing.Title title = Standing.of(id, p.getUUID(), level.getGameTime()).title();
        if (title == Standing.Title.OUTCAST) return ServicePoints.Result.no("Nobody here will trade with you.");
        if (Laws.banished(id, p.getUUID(), day)) return ServicePoints.Result.no("You're banished from " + Villages.name(id) + ". Nobody will serve you.");
        CompoundTag basket = visit.state().getCompound("basket");
        if (basket.isEmpty()) return ServicePoints.Result.no("Your basket is empty.");
        List<Line> lines = lines(level, v, visit.role());
        List<ItemStack> ones = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (String key : basket.getAllKeys()) {
            Line l = line(lines, key);
            int n = basket.getInt(key);
            if (l == null || l.stock() < n) {
                return ServicePoints.Result.no("Not enough " + (l == null ? "of that" : Goods.name(l.sample())) + " in just now ("
                    + (l == null ? 0 : l.stock()) + "). Take some out of your basket.");
            }
            ones.add(l.sample());
            counts.add(n);
        }
        Quote q = quote(level, id, p, ones, counts);
        int held = Market.coinsHeld(p);
        if (held < q.total()) return ServicePoints.Result.no("That comes to " + coins(q.total()) + ". You have " + held + ".");
        // Take every line, or put back what was taken and stop.
        List<List<ItemStack>> got = new ArrayList<>();
        for (int i = 0; i < ones.size(); i++) {
            List<ItemStack> took = take(level, v, visit.role(), ones.get(i), counts.get(i));
            if (took.isEmpty()) {
                for (List<ItemStack> back : got) for (ItemStack s : back) putBack(level, v, visit.role(), s);
                return ServicePoints.Result.no(Goods.name(ones.get(i)) + " sold out just now. Nothing was taken.");
            }
            got.add(took);
        }
        Market.payOut(p, q.total());
        Ledger.addCoins(id, q.total());
        Economy.sold(id, q.total());
        Budget.forget(id);
        Stockroom.Seller seller = visit.role() == ServiceRole.CAFE ? Stockroom.Seller.CAFE : Stockroom.Seller.SHOP;
        int dropped = 0;
        StringBuilder what = new StringBuilder();
        for (int i = 0; i < ones.size(); i++) {
            int share = q.subtotal() <= 0 ? 0 : (int) Math.round(q.total() * (double) q.each().get(i)[1] / q.subtotal());
            Stockroom.sold(level, id, seller, ones.get(i), counts.get(i), share);
            for (ItemStack s : got.get(i)) {
                ItemStack give = s.copy();
                if (!p.getInventory().add(give)) {
                    dropped += give.getCount();
                    p.drop(give, false);
                }
            }
            if (what.length() > 0) what.append(", ");
            what.append(Goods.words(ones.get(i), counts.get(i)));
        }
        visit.state().put("basket", new CompoundTag());
        receipt(level, id, p.getUUID(), "Day " + day + ": " + what + " for " + coins(q.total()));
        VillageFolkEntity k = visit.keeper();
        if (k != null && k.distanceToSqr(p) < 10 * 10) {
            FolkTalk.speak(k, FolkTalk.pick(level.getRandom(), "Thank you kindly!", "There you are. Mind how you go!",
                "A pleasure, " + p.getName().getString() + ". Come again!"));
        }
        return ServicePoints.Result.done("Bought " + what + " for " + coins(q.total()) + "."
            + (dropped > 0 ? " Your pack was full: " + dropped + " at your feet." : ""));
    }

    /** So many of this line, out of the shop (its stock, else the stores'), or the café's (the stores'). Nothing if it
     *  has not that many. */
    private static List<ItemStack> take(ServerLevel level, Villages.Village v, ServiceRole role, ItemStack one, int n) {
        if (role == ServiceRole.CAFE) {
            if (!TownWork.take(level, v, same(one), n)) return List.of();
            List<ItemStack> out = new ArrayList<>();
            int left = n;
            while (left > 0) {
                int k = Math.min(left, one.getMaxStackSize());
                out.add(one.copyWithCount(k));
                left -= k;
            }
            return out;
        }
        int own = Store.count(level, v.id(), same(one));
        if (n > own && Budget.spare(level, v.id(), one) < n - own) return List.of();
        return ShopStock.takeStacks(level, v, same(one), n, null);
    }

    private static void putBack(ServerLevel level, Villages.Village v, ServiceRole role, ItemStack s) {
        if (role == ServiceRole.CAFE) { TownWork.give(level, v, s.copy()); return; }
        ItemStack left = Store.put(level, v.id(), s.copy());
        if (!left.isEmpty()) TownWork.give(level, v, left);
    }

    /** The player sells the town so many lots of a thing it carries, at the town's paying price, into its stores. */
    private static ServicePoints.Result sell(ServicePoints.Visit visit, CompoundTag args) {
        ServerLevel level = visit.level();
        UUID id = visit.village().id();
        ServerPlayer p = visit.player();
        if (p == null || visit.role() != ServiceRole.SHOP) return ServicePoints.Result.no("");
        if (Standing.of(id, p.getUUID(), level.getGameTime()).title() == Standing.Title.OUTCAST) return ServicePoints.Result.no("Nobody here will trade with you.");
        String key = args.getString("id");
        int lots = Math.max(1, Math.min(64, args.getInt("lots")));
        ItemStack one = ItemStack.EMPTY;
        int have = 0;
        for (ItemStack s : p.getInventory().items) {
            if (s.isEmpty() || s.isDamaged() || !Goods.lineId(s).equals(key)) continue;
            if (one.isEmpty()) one = s.copyWithCount(1);
            have += s.getCount();
        }
        if (one.isEmpty()) return ServicePoints.Result.no("You've none of that on you now.");
        if (Services.given(id, p.getUUID(), level.getDayTime() / 24000L, one.getItem()) > 0) {
            return ServicePoints.Result.no("We gave you that this morning, friend: keep it, it's yours.");
        }
        int lot = sellLot(one);
        int n = Math.min(have / lot, lots) * lot;
        if (n <= 0) return ServicePoints.Result.no("The town buys " + Goods.name(one).toLowerCase(Locale.ROOT) + " by the " + lot + ".");
        int price = Market.bidLot(level, id, one, n);
        if (price <= 0) return ServicePoints.Result.no(Goods.words(one, n) + " isn't worth a coin to the town just now.");
        if (Ledger.coins(id) < price) return ServicePoints.Result.no("The treasury hasn't the coin to pay for that just now.");
        ItemStack lotStack = one.copyWithCount(n);
        ItemStack left = Market.intoStores(level, id, lotStack);
        int stored = n - left.getCount();
        if (stored < n) {
            if (stored > 0) TownWork.take(level, visit.village(), same(one), stored);
            return ServicePoints.Result.no("The stores are full; they can't take any more just now.");
        }
        int toTake = n;
        for (ItemStack s : p.getInventory().items) {
            if (toTake <= 0) break;
            if (s.isEmpty() || s.isDamaged() || !Goods.lineId(s).equals(key)) continue;
            int k = Math.min(toTake, s.getCount());
            s.shrink(k);
            toTake -= k;
        }
        p.getInventory().setChanged();
        int paid = Ledger.takeCoins(id, price);
        Economy.spent(id, paid);
        ItemStack coin = new ItemStack(McAssistantMod.VILLAGE_COIN.get(), paid);
        if (!p.getInventory().add(coin)) p.drop(coin, false);
        receipt(level, id, p.getUUID(), "Day " + level.getDayTime() / 24000L + ": sold " + Goods.words(one, n) + " for " + coins(paid));
        return ServicePoints.Result.done("Sold " + Goods.words(one, n) + " for " + coins(paid) + ".");
    }

    /** A thing not in: on the workshop's book for this player (the café's: the cook told), with when to come back. */
    private static ServicePoints.Result order(ServicePoints.Visit visit, CompoundTag args) {
        ServerLevel level = visit.level();
        Villages.Village v = visit.village();
        String key = args.getString("id");
        Line l = line(lines(level, v, visit.role()), key);
        if (l == null) return ServicePoints.Result.no("That isn't on the list any more.");
        if (l.stock() > 0) return ServicePoints.Result.no("There's some in: put it in your basket.");
        String who = visit.player() == null ? "a customer" : visit.player().getName().getString();
        if (visit.role() == ServiceRole.CAFE || l.orderable() == null) {
            Stockroom.missed(level, v.id(), visit.role() == ServiceRole.CAFE ? Stockroom.Seller.CAFE : Stockroom.Seller.SHOP, l.sample());
            StockKeeper.missed(level, v.id(), l.sample(), 1);
            return ServicePoints.Result.said("Noted: more " + Goods.name(l.sample()).toLowerCase(Locale.ROOT) + " wanted. Come back tomorrow.");
        }
        String said = Workshop.order(level, v, l.orderable(), 1, who);
        boolean ok = said.startsWith("On the workshop's book");
        if (ok) StockKeeper.missed(level, v.id(), l.sample(), 1);
        return ok ? ServicePoints.Result.said(said + " Ready in about " + days(StockKeeper.Route.CRAFTERS.lead) + ".")
            : ServicePoints.Result.no(said);
    }

    /** "Can you do it any cheaper?": the keeper's answer (Dealings.haggle), which the basket's total then shows. */
    private static ServicePoints.Result haggle(ServicePoints.Visit visit) {
        VillageFolkEntity k = visit.keeper();
        if (k == null || visit.player() == null) return ServicePoints.Result.no("There's nobody behind the counter to haggle with.");
        String said = Dealings.haggle(k, visit.player());
        FolkTalk.speak(k, said);
        boolean got = said.contains(" off");
        return got ? ServicePoints.Result.said(k.displayNameCap() + ": \"" + said + "\"") : ServicePoints.Result.no(k.displayNameCap() + ": \"" + said + "\"");
    }

    // ------------------------------------------------------------------ the folk at the same desk

    /**
     * A folk at the counter buys so many of a thing out of its own purse, at the same price a player pays before any
     * standing (the town's asking price; a thrifty folk a little less: FolkSkills), out of the shop's stock (else the
     * stores', a stock-out), into its pack. The shop's own books and the treasury as every sale. Null if it did not
     * (none in, or not the coin): what is wanted and not there goes down as a sale the shop missed, so it makes more.
     */
    @Nullable
    public static ItemStack folkBuy(ServerLevel level, Villages.Village v, VillageFolkEntity f, ItemStack one, int n) {
        StoreFloor.Sale sale = StoreFloor.folkBuys(level, v, f, same(one), n);
        if (!sale.ok()) {
            if (sale.why().equals("none")) StockKeeper.missed(level, v.id(), one, n);
            return null;
        }
        return sale.bought();
    }

    // ------------------------------------------------------------------ the player's ledger

    /** A line in the player's ledger at this town (its last few dealings over the counter). */
    static void receipt(ServerLevel level, UUID village, UUID player, String line) {
        List<String> all = new ArrayList<>(receipts(village, player));
        all.add(0, line.replace("|", "/"));
        while (all.size() > RECEIPTS) all.remove(all.size() - 1);
        Ledger.note(village, "receipt/" + player, String.join("|", all));
    }

    /** The player's last dealings over this town's counters, the latest first. */
    public static List<String> receipts(UUID village, UUID player) {
        String s = Ledger.note(village, "receipt/" + player);
        if (s == null || s.isEmpty()) return List.of();
        return List.of(s.split("\\|"));
    }

    private static String coins(int n) {
        return n + (n == 1 ? " coin" : " coins");
    }

    /** Tests: the shop's list as ids with their stock, "id=stock". */
    public static List<String> linesForTests(ServerLevel level, Villages.Village v, ServiceRole role) {
        List<String> out = new ArrayList<>();
        for (Line l : lines(level, v, role)) out.add(l.id() + "=" + l.stock() + (l.orderable() != null ? "+order" : ""));
        return out;
    }

    /** Tests: a line's id for a thing. */
    public static String idForTests(ItemStack s) {
        return Goods.lineId(s);
    }
}
