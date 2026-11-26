package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [till] Who a till rings up for, and how each of them sells: the town's own shop and café (ShopDesk's lines, prices,
 * discounts and sale: the same code the old counter used), a player's own shop (PlayerShops: its shelves, its prices by
 * the lot, its till and its books), and a firm of the town's (Enterprise: its wares bonded in the stores, Sellers' price
 * with its owner's markup and discount day, its books). Each answers the checkout the same way: its wares and what one
 * costs, what a basket comes to, the sale (a player's or a folk's), what it buys back, and who is behind the counter.
 */
final class TillShops {

    private TillShops() {}

    /** One line on the checkout: a thing, what it costs (the lot's price, {@code lot} of them), how many are left. */
    record Ware(String id, ItemStack sample, int price, int lot, int stock, String note) {
        /** What {@code n} of it come to. */
        int cost(int n) {
            return n <= 0 ? 0 : Math.max(1, (int) Math.ceil(price * (double) n / Math.max(1, lot) - 1e-9));
        }

        String priceWords() {
            return lot <= 1 ? price + "c" : price + "c for " + lot;
        }
    }

    /** A basket priced: each line (how many, what they come to), the sum, every discount as its own line, the total. */
    record Quote(Map<String, int[]> lines, int subtotal, List<String> labels, List<Integer> amounts, int total) {}

    /** What a sale came to: did it go through, what to say, the coin that moved. */
    record Outcome(boolean ok, String message, int coins) {
        static Outcome no(String why) { return new Outcome(false, why, 0); }
    }

    /** Who is behind the counter: the keeper (null: nobody), open at all, somebody there to serve, the words, when they're back. */
    record Staff(@Nullable VillageFolkEntity keeper, boolean open, boolean staffed, String words, String backAt) {}

    /** A business at a till. */
    interface Source {
        UUID village();

        String key();

        String name(ServerLevel level);

        List<Ware> wares(ServerLevel level, @Nullable ServerPlayer p);

        Quote quote(ServerLevel level, @Nullable ServerPlayer p, Map<String, Integer> basket, List<Ware> wares);

        Outcome buy(ServerLevel level, ServerPlayer p, Map<String, Integer> basket, @Nullable BlockPos till, boolean self);

        /** A folk buys {@code n} of this at the counter: what it got, or null. */
        @Nullable
        ItemStack folkBuy(ServerLevel level, VillageFolkEntity f, ItemStack one, int n);

        /** What it buys back of what the player carries (empty: it doesn't). */
        default List<CompoundTag> buys(ServerLevel level, ServerPlayer p, @Nullable BlockPos till) {
            return List.of();
        }

        default Outcome sell(ServerLevel level, ServerPlayer p, String id, int pieces, @Nullable BlockPos till) {
            return Outcome.no("They don't buy anything here.");
        }

        Staff staff(ServerLevel level, @Nullable BlockPos till);

        default boolean ownedBy(UUID player) {
            return false;
        }

        /** Where its stock is kept (its chests and the fittings that count as stock): for the shelves' restocking. */
        List<Container> stockChests(ServerLevel level);

        /** "Discount day: a tenth off" and the like: the price levers in force today, for the checkout to show. */
        default List<String> offers(ServerLevel level, @Nullable ServerPlayer p) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ finding them

    @Nullable
    static Source of(ServerLevel level, @Nullable UUID village, String key) {
        if (village == null || key == null || key.isEmpty()) return null;
        Villages.Village v = Villages.get(village);
        if (v == null) return null;
        if (key.equals("town:shop")) return new Town(v, ServiceRole.SHOP);
        if (key.equals("town:cafe")) return new Town(v, ServiceRole.CAFE);
        if (key.startsWith("pshop:")) {
            PlayerShops.Shop s = PlayerShops.byKey(village, key.substring(6));
            return s == null ? null : new Shop(v, s);
        }
        if (key.startsWith("firm:")) {
            Business b = Businesses.of(village, key.substring(5));
            return b == null || !b.open ? null : new Firm(v, b);
        }
        return null;
    }

    /**
     * The business a till (or a fitting) set here belongs to: a player's shop whose room it stands in, the town's shop or
     * café whose counter is beside it, a firm whose premises or Core it stands by. Null: none (it says so).
     */
    @Nullable
    static String[] resolve(ServerLevel level, BlockPos pos) {
        Villages.Village v = Villages.nearest(level, pos, 160);
        if (v == null) return null;
        for (PlayerShops.Shop s : PlayerShops.all(v.id())) {
            if (!level.isLoaded(s.anchor) || s.anchor.distSqr(pos) > 24 * 24) continue;
            ShopNeeds.Survey sv = PlayerShops.survey(level, s);
            if (sv.room.contains(pos) || sv.room.contains(pos.below()) || sv.counters.contains(pos.below()) || s.anchor.distSqr(pos) <= 4 * 4) {
                return new String[]{ v.id().toString(), "pshop:" + s.key() };
            }
        }
        String town = null;
        double best = 7 * 7;
        for (ServiceRole role : new ServiceRole[]{ ServiceRole.SHOP, ServiceRole.CAFE }) {
            ServicePoints.Point p = ServicePoints.nearest(level, v.id(), role, pos);
            if (p != null && p.pos().distSqr(pos) < best) { best = p.pos().distSqr(pos); town = "town:" + role.id; }
        }
        if (town != null) return new String[]{ v.id().toString(), town };
        for (Business b : Enterprise.open(v.id())) {
            BlockPos at = BusinessCore.siteOf(level, v, b);
            if (at != null && at.distSqr(pos) <= 10 * 10 || b.at != null && b.at.distSqr(pos) <= 12 * 12) {
                return new String[]{ v.id().toString(), "firm:" + b.id };
            }
        }
        for (ServiceRole role : new ServiceRole[]{ ServiceRole.SHOP, ServiceRole.CAFE }) {
            ServicePoints.Point p = ServicePoints.nearest(level, v.id(), role, pos);
            if (p != null && p.pos().distSqr(pos) <= 14 * 14) return new String[]{ v.id().toString(), "town:" + role.id };
        }
        return null;
    }

    static String coins(int n) {
        return n + (n == 1 ? " coin" : " coins");
    }

    /** Within six blocks of the till (or of where the screen was opened). */
    static boolean near(VillageFolkEntity f, @Nullable BlockPos till, double r) {
        return till == null || f.distanceToSqr(till.getX() + 0.5, till.getY(), till.getZ() + 0.5) <= r * r;
    }

    // ================================================================== the town's shop and café

    /** The town's own shop (or store) and café: ShopDesk does the selling, as it always did at the counter. */
    static final class Town implements Source {
        final Villages.Village v;
        final ServiceRole role;

        Town(Villages.Village v, ServiceRole role) {
            this.v = v;
            this.role = role;
        }

        @Override public UUID village() { return v.id(); }

        @Override public String key() { return "town:" + role.id; }

        @Override
        public String name(ServerLevel level) {
            return Villages.name(v.id()) + " " + role.title;
        }

        @Nullable
        ServicePoints.Point point(ServerLevel level, @Nullable BlockPos till) {
            return ServicePoints.nearest(level, v.id(), role, till == null ? v.centre() : till);
        }

        ServicePoints.Visit visit(ServerLevel level, @Nullable ServerPlayer p, @Nullable BlockPos till, CompoundTag state) {
            ServicePoints.Point point = point(level, till);
            VillageFolkEntity k = ServicePoints.status(level, v.id(), role, point).keeper();
            return new ServicePoints.Visit(level, v, role, point, p, k, state);
        }

        @Override
        public List<Ware> wares(ServerLevel level, @Nullable ServerPlayer p) {
            List<Ware> in = new ArrayList<>(), out = new ArrayList<>();
            for (ShopDesk.Line l : ShopDesk.lines(level, v, role)) {
                int each = ShopStock.lotPrice(level, v.id(), l.sample(), 1);
                if (l.stock() > 0) in.add(new Ware(l.id(), l.sample(), each, 1, l.stock(), l.own() ? "" : "from the stores"));
                else if (out.size() < 6) out.add(new Ware(l.id(), l.sample(), each, 1, 0, l.orderable() != null ? "on order at the bench" : ""));
            }
            in.addAll(out);
            return in;
        }

        @Override
        public Quote quote(ServerLevel level, @Nullable ServerPlayer p, Map<String, Integer> basket, List<Ware> wares) {
            List<ItemStack> ones = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            for (Ware w : wares) {
                int n = Math.min(basket.getOrDefault(w.id(), 0), w.stock());
                if (n <= 0) continue;
                ones.add(w.sample());
                counts.add(n);
                ids.add(w.id());
            }
            ShopDesk.Quote q = ShopDesk.quote(level, v.id(), p, ones, counts);
            Map<String, int[]> lines = new LinkedHashMap<>();
            for (int i = 0; i < ids.size(); i++) lines.put(ids.get(i), q.each().get(i));
            return new Quote(lines, q.subtotal(), q.adjustLabels(), q.adjustments(), q.total());
        }

        @Override
        public Outcome buy(ServerLevel level, ServerPlayer p, Map<String, Integer> basket, @Nullable BlockPos till, boolean self) {
            CompoundTag state = new CompoundTag();
            CompoundTag b = new CompoundTag();
            for (Map.Entry<String, Integer> e : basket.entrySet()) if (e.getValue() > 0) b.putInt(e.getKey(), e.getValue());
            state.put("basket", b);
            ServicePoints.Visit visit = visit(level, p, till, state);
            int before = Market.coinsHeld(p);
            ServicePoints.Result r = ShopDesk.INSTANCE.act(visit, "buy", new CompoundTag());
            return new Outcome(r.ok(), r.message(), Math.max(0, before - Market.coinsHeld(p)));
        }

        @Nullable
        @Override
        public ItemStack folkBuy(ServerLevel level, VillageFolkEntity f, ItemStack one, int n) {
            return ShopDesk.folkBuy(level, v, f, one, n);
        }

        @Override
        public List<CompoundTag> buys(ServerLevel level, ServerPlayer p, @Nullable BlockPos till) {
            if (!Buyers.buys(role)) return List.of();
            ListTag t = Buyers.sellTag(visit(level, p, till, new CompoundTag()));
            List<CompoundTag> out = new ArrayList<>();
            for (int i = 0; i < t.size(); i++) out.add(t.getCompound(i));
            return out;
        }

        @Override
        public Outcome sell(ServerLevel level, ServerPlayer p, String id, int pieces, @Nullable BlockPos till) {
            int before = Market.coinsHeld(p);
            ServicePoints.Result r = Buyers.sell(visit(level, p, till, new CompoundTag()), id, pieces);
            return new Outcome(r.ok(), r.message(), Math.max(0, Market.coinsHeld(p) - before));
        }

        @Override
        public Staff staff(ServerLevel level, @Nullable BlockPos till) {
            ServicePoints.Point point = point(level, till);
            ServicePoints.Status st = ServicePoints.status(level, v.id(), role, point);
            long t = level.getDayTime() % 24000L;
            String back = "back at " + com.jrpetty.mcassistant.village.ServiceHours.clock(role.hours.open());
            boolean hours = ServicePoints.HOURS_OFF_FOR_TESTS || role.hours.isOpen(t);
            VillageFolkEntity k = st.keeper();
            boolean there = k != null && !k.isSleeping() && near(k, till, 7);
            return new Staff(k, hours, hours && there, hours ? (there ? k.displayNameCap() + " is at the till." : "Nobody at the till: " + back + ".")
                : "Closed. " + role.hours.nextOpening(t) + ".", back);
        }

        @Override
        public List<Container> stockChests(ServerLevel level) {
            return Store.containers(level, v.id(), false);
        }

        @Override
        public List<String> offers(ServerLevel level, @Nullable ServerPlayer p) {
            List<String> out = new ArrayList<>();
            if (Market.marketDay(v.id(), level.getDayTime() / 24000L)) out.add("Market day: prices are sharper today.");
            if (p != null && Citizens.is(v.id(), p.getUUID())) out.add("Citizen's price: a tenth off.");
            return out;
        }
    }

    // ================================================================== a player's shop

    /** A shop of a player's own: its shelves (and the fittings in its room), its prices by the lot, its till. */
    static final class Shop implements Source {
        final Villages.Village v;
        final PlayerShops.Shop s;

        Shop(Villages.Village v, PlayerShops.Shop s) {
            this.v = v;
            this.s = s;
        }

        @Override public UUID village() { return v.id(); }

        @Override public String key() { return "pshop:" + s.key(); }

        @Override
        public String name(ServerLevel level) {
            return s.name.isEmpty() ? s.ownerName + "'s shop" : s.name;
        }

        @Override
        public boolean ownedBy(UUID player) {
            return s.owner.equals(player);
        }

        @Override
        public List<Ware> wares(ServerLevel level, @Nullable ServerPlayer p) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            Map<String, ItemStack> wares = PlayerShops.wares(level, s, counts, false);
            List<Ware> out = new ArrayList<>();
            for (Map.Entry<String, ItemStack> e : wares.entrySet()) {
                int price = PlayerShops.asking(level, s, e.getKey(), e.getValue());
                if (price <= 0) continue;                                    // 0: kept back
                out.add(new Ware(e.getKey(), e.getValue(), price, Math.max(1, PlayerStalls.lot(e.getValue())), counts.getOrDefault(e.getKey(), 0), ""));
            }
            for (String key : s.soldOut.keySet()) {
                if (wares.containsKey(key) || out.size() > 40) continue;
                ItemStack one = Stockroom.sampleOf(key);
                if (one.isEmpty()) continue;
                Integer set = s.prices.get(key);
                if (set != null && set <= 0) continue;
                out.add(new Ware(key, one, set != null ? set : PlayerStalls.going(level, v.id(), one), Math.max(1, PlayerStalls.lot(one)), 0, ""));
            }
            return out;
        }

        @Override
        public Quote quote(ServerLevel level, @Nullable ServerPlayer p, Map<String, Integer> basket, List<Ware> wares) {
            Map<String, int[]> lines = new LinkedHashMap<>();
            int sum = 0;
            for (Ware w : wares) {
                int n = Math.min(basket.getOrDefault(w.id(), 0), w.stock());
                if (n <= 0) continue;
                int c = w.cost(n);
                lines.put(w.id(), new int[]{ n, c });
                sum += c;
            }
            return new Quote(lines, sum, List.of(), List.of(), sum);
        }

        @Override
        public Outcome buy(ServerLevel level, ServerPlayer p, Map<String, Integer> basket, @Nullable BlockPos till, boolean self) {
            if (s.owner.equals(p.getUUID())) return Outcome.no("It's your own stock. Take it off the shelf.");
            if (PlayerShops.state(level, s) != PlayerShops.State.OPEN) return Outcome.no(s.name + " isn't open: " + PlayerShops.stateWords(level, s) + ".");
            List<Ware> wares = wares(level, p);
            Quote q = quote(level, p, basket, wares);
            if (q.lines().isEmpty()) return Outcome.no("Your basket is empty.");
            for (Map.Entry<String, Integer> e : basket.entrySet()) {
                int[] l = q.lines().get(e.getKey());
                if (e.getValue() > 0 && (l == null || l[0] < e.getValue())) return Outcome.no("Not enough of that on the shelves just now. Take some out of your basket.");
            }
            int held = Market.coinsHeld(p);
            if (held < q.total()) return Outcome.no("That comes to " + coins(q.total()) + ". You have " + held + ".");
            // Off the shelves, every line, or all of it back.
            List<Container> shelves = PlayerShops.shelves(level, s);
            List<Object[]> taken = new ArrayList<>();
            for (Ware w : wares) {
                int[] l = q.lines().get(w.id());
                if (l == null) continue;
                int got = 0;
                for (Container c : shelves) {
                    if (got >= l[0]) break;
                    ItemStack g = ShopCustomers.take(c, w.sample(), l[0] - got);
                    if (g.isEmpty()) continue;
                    taken.add(new Object[]{ c, g });
                    got += g.getCount();
                }
                if (got < l[0]) {
                    for (Object[] t : taken) ShopCustomers.putBack((Container) t[0], (ItemStack) t[1]);
                    return Outcome.no(Goods.name(w.sample()) + " sold out just now. Nothing was taken.");
                }
            }
            Market.payOut(p, q.total());
            CoinCensus.playerIn(v.id(), q.total(), p, PlayerFinance.Cat.PURCHASES, "at " + s.name);
            int dropped = 0;
            StringBuilder what = new StringBuilder();
            for (Ware w : wares) {
                int[] l = q.lines().get(w.id());
                if (l == null) continue;
                ItemStack lot = w.sample().copyWithCount(l[0]);
                PlayerShops.booked(level, s, p.getName().getString() + " (at the till)", lot, l[1],
                    PlayerStalls.going(level, v.id(), w.sample()) * l[0] / (double) Math.max(1, w.lot()));
                dropped += give(p, lot);
                if (what.length() > 0) what.append(", ");
                what.append(Goods.words(w.sample(), l[0]));
            }
            return new Outcome(true, "Bought " + what + " for " + coins(q.total()) + "." + dropped(dropped), q.total());
        }

        @Nullable
        @Override
        public ItemStack folkBuy(ServerLevel level, VillageFolkEntity f, ItemStack one, int n) {
            String key = PlayerStalls.key(one);
            int price = PlayerShops.asking(level, s, key, one);
            if (price <= 0) return null;
            int sold = ShopCustomers.counterSale(level, f, f, s, one, n, price / (double) Math.max(1, PlayerStalls.lot(one)));
            return sold > 0 ? one.copyWithCount(sold) : null;
        }

        @Override
        public Staff staff(ServerLevel level, @Nullable BlockPos till) {
            boolean open = PlayerShops.state(level, s) == PlayerShops.State.OPEN;
            String back = "back at " + com.jrpetty.mcassistant.village.ServiceHours.clock(s.opens);
            VillageFolkEntity k = null;
            if (s.keeper != null && level.getEntity(s.keeper) instanceof VillageFolkEntity f && f.isAlive()) k = f;
            boolean keeperIn = k != null && !k.isSleeping() && near(k, till, 7) && PlayerShops.inHours(level, s.kOpens, s.kCloses);
            Player owner = level.getPlayerByUUID(s.owner);
            boolean ownerIn = owner != null && till != null && owner.distanceToSqr(till.getX() + 0.5, till.getY(), till.getZ() + 0.5) <= 6 * 6;
            String who = keeperIn ? k.displayNameCap() + " is at the till." : ownerIn ? s.ownerName + " is minding the till." : "Nobody at the till: " + back + ".";
            return new Staff(keeperIn ? k : null, open, open && (keeperIn || ownerIn), open ? who : PlayerShops.stateWords(level, s) + ".", back);
        }

        @Override
        public List<Container> stockChests(ServerLevel level) {
            return PlayerShops.stockroom(level, s);
        }
    }

    // ================================================================== a firm of the town's

    /** A firm (a bakery, a butcher's, a pottery...): its wares bonded in the stores, sold at its own price, into its till. */
    static final class Firm implements Source {
        final Villages.Village v;
        final Business b;

        Firm(Villages.Village v, Business b) {
            this.v = v;
            this.b = b;
        }

        @Override public UUID village() { return v.id(); }

        @Override public String key() { return "firm:" + b.id; }

        @Override
        public String name(ServerLevel level) {
            return b.name;
        }

        @Override
        public boolean ownedBy(UUID player) {
            return b.ownedBy(player);
        }

        int each(ServerLevel level, ItemStack one) {
            return Math.max(1, (int) Math.ceil(Sellers.priceOf(level, v.id(), b, one) - 1e-9));
        }

        @Override
        public List<Ware> wares(ServerLevel level, @Nullable ServerPlayer p) {
            List<Ware> out = new ArrayList<>();
            BusinessTypes.BusinessType t = Enterprise.kindOf(v.id(), b);
            List<String> keys = new ArrayList<>(b.stock.keySet());
            if (t != null) for (String k : t.wares()) if (!keys.contains(k)) keys.add(k);
            for (String key : keys) {
                ItemStack one = Stockroom.sampleOf(key);
                if (one.isEmpty() || Market.isCoin(one)) continue;
                long held = Enterprise.held(b, key);
                int stock = (int) Math.max(0, Math.min(held, Market.stock(level, v.id(), Stockroom.matcher(key))));
                if (stock <= 0 && (t == null || !t.wares().contains(key))) continue;
                out.add(new Ware(key, one, each(level, one), 1, stock, ""));
            }
            return out;
        }

        @Override
        public Quote quote(ServerLevel level, @Nullable ServerPlayer p, Map<String, Integer> basket, List<Ware> wares) {
            Map<String, int[]> lines = new LinkedHashMap<>();
            int sum = 0;
            for (Ware w : wares) {
                int n = Math.min(basket.getOrDefault(w.id(), 0), w.stock());
                if (n <= 0) continue;
                int c = w.cost(n);
                lines.put(w.id(), new int[]{ n, c });
                sum += c;
            }
            return new Quote(lines, sum, List.of(), List.of(), sum);
        }

        @Override
        public Outcome buy(ServerLevel level, ServerPlayer p, Map<String, Integer> basket, @Nullable BlockPos till, boolean self) {
            if (b.ownedBy(p.getUUID())) return Outcome.no("It's your own firm's stock. Take it from the stores.");
            List<Ware> wares = wares(level, p);
            Quote q = quote(level, p, basket, wares);
            if (q.lines().isEmpty()) return Outcome.no("Your basket is empty.");
            int held = Market.coinsHeld(p);
            if (held < q.total()) return Outcome.no("That comes to " + coins(q.total()) + ". You have " + held + ".");
            int dropped = 0;
            int paid = 0;
            StringBuilder what = new StringBuilder();
            for (Ware w : wares) {
                int[] l = q.lines().get(w.id());
                if (l == null) continue;
                if (!TownWork.take(level, v, Stockroom.matcher(w.id()), l[0])) {
                    if (paid == 0) return Outcome.no(Goods.name(w.sample()) + " sold out just now. Nothing was taken.");
                    continue;
                }
                Market.payOut(p, l[1]);
                Ledger.addCoins(v.id(), l[1]);
                CoinCensus.playerIn(v.id(), l[1], p, PlayerFinance.Cat.PURCHASES, "at " + b.name);
                Businesses.buyer(p.getName().getString(), true);
                Sellers.sold(level, v.id(), b, w.sample(), l[0], l[1] * 100L);
                Businesses.buyer(null, false);
                paid += l[1];
                dropped += give(p, w.sample().copyWithCount(l[0]));
                if (what.length() > 0) what.append(", ");
                what.append(Goods.words(w.sample(), l[0]));
            }
            return new Outcome(true, "Bought " + what + " for " + coins(paid) + "." + dropped(dropped), paid);
        }

        @Nullable
        @Override
        public ItemStack folkBuy(ServerLevel level, VillageFolkEntity f, ItemStack one, int n) {
            String key = Stockroom.key(one);
            if (Enterprise.held(b, key) < n) return null;
            int price = Math.max(1, (int) Math.ceil(Sellers.priceOf(level, v.id(), b, one) * n - 1e-9));
            price = FolkSkills.thrifty(f, price);
            if (f.purse() < price) return null;
            if (!TownWork.take(level, v, Stockroom.matcher(key), n)) return null;
            f.spend(price);
            Ledger.addCoins(v.id(), price);
            Economy.spentInTown(v.id(), price);
            Businesses.buyer(f.displayNameCap(), false);
            Sellers.sold(level, v.id(), b, one, n, price * 100L);
            Businesses.buyer(null, false);
            ItemStack got = one.copyWithCount(n);
            ItemStack left = f.insertGiven(got.copy());
            if (!left.isEmpty()) Crafts.store(level, v, left);
            return got;
        }

        @Override
        public Staff staff(ServerLevel level, @Nullable BlockPos till) {
            CompoundTag r = Enterprise.rec(v.id(), b.id);
            boolean open = CoreSites.isOpen(level, v.id(), b, r) && (r == null || BusinessCore.openAt(r, level.getDayTime()));
            int opens = r == null ? 7 : BusinessCore.hours(r)[0];
            String back = "back at " + (opens % 12 == 0 ? 12 : opens % 12) + (opens < 12 ? "am" : "pm");
            VillageFolkEntity k = null;
            for (VillageFolkEntity f : Enterprise.hands(v.id(), b)) {
                if (f.isAlive() && !f.isSleeping() && near(f, till, 7)) { k = f; break; }
            }
            return new Staff(k, open, open && k != null, open ? (k != null ? k.displayNameCap() + " is at the till." : "Nobody at the till: " + back + ".")
                : "Closed: " + back + ".", back);
        }

        @Override
        public List<Container> stockChests(ServerLevel level) {
            return List.of();
        }

        @Override
        public List<String> offers(ServerLevel level, @Nullable ServerPlayer p) {
            CompoundTag r = Enterprise.rec(v.id(), b.id);
            List<String> out = new ArrayList<>();
            if (r != null && r.contains("discountDay") && Math.floorMod(level.getDayTime() / 24000L, 7L) == r.getInt("discountDay")) {
                out.add("Discount day: a tenth off everything (already in the prices).");
            }
            if (r != null && r.getString("quality").equals("fine")) out.add("Fine goods: priced to match.");
            return out;
        }
    }

    // ------------------------------------------------------------------ handing it over

    /** Into the pack, or at the player's feet if it is full: how many went on the floor. */
    static int give(Player p, ItemStack lot) {
        int dropped = 0;
        ItemStack left = lot.copy();
        while (!left.isEmpty()) {
            ItemStack part = left.split(Math.min(left.getCount(), left.getMaxStackSize()));
            p.getInventory().add(part);
            if (!part.isEmpty()) {
                dropped += part.getCount();
                p.drop(part, false);
            }
        }
        return dropped;
    }

    static String dropped(int n) {
        return n > 0 ? " Your pack was full: " + n + " at your feet." : "";
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
