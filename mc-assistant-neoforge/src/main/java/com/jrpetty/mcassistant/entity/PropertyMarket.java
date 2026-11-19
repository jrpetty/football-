package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [orders17] The property register and its market: every building a player owns (one it commissioned, one it bought, its
 * own shop put on the register), what it is worth, and the buyers and tenants for it.
 *
 * <p><b>What it is worth.</b> The land (the plot's value, as the builders price it; nothing to a leaseholder), the building
 * at what it would cost to build again today (its materials at the market's ask and its builders' hours at their wages),
 * less its wear (a hundredth a week since it went up, four tenths at most), as the housing market stands for a home, and
 * the goodwill of a business trading in it (four weeks of what it has made over its costs, from its books).
 *
 * <p><b>Selling and letting.</b> The owner asks what it likes. Offers come in over the days from those who want it and can
 * pay: households without a home of their own for a house (out of their purses and savings, and the bank's mortgage, as for
 * any house), the town's firms for premises (more for premises fit for their trade), the council (four-fifths of the value
 * for a home to let, seven tenths for premises), and other players at the office. An offer stands three days. The owner
 * accepts or declines; on a sale the price goes to it (into its pack, or waiting at the hall), the title to the buyer, and
 * the contents of its chests back to it unless sold with it. A home let goes to the next household wanting one, at the rent
 * asked (Homes); premises let go to a firm at so much a week out of its till.
 *
 * <p><b>Folk sell too.</b> A household that owns its house and is saving to build a bigger one, or has outgrown it, puts it
 * on the same market; a player who buys it gets it with the household as sitting tenants, paying the town's rent.
 *
 * <p>Kept in the town's accounts (Accounts, "orders17": "estate", "sales").
 */
public final class PropertyMarket {

    private PropertyMarket() {}

    /** Wear a week, in the thousand, and the most, in the hundred. */
    static final int WEAR_WEEK = 10, WEAR_MOST = 40;
    /** The council's offer, in the hundred of the value: a home, premises. */
    static final int COUNCIL_HOME = 80, COUNCIL_PREMISES = 70;
    /** Days an offer stands; days a folk's own house stays on the market. */
    static final int OFFER_DAYS = 3, FOLK_DAYS = 7;

    // ------------------------------------------------------------------ the register

    static CompoundTag estate(UUID village) {
        return Accounts.sub(BuildOrders.book(village), "estate");
    }

    static String keyAt(BlockPos anchor) {
        return "e" + anchor.asLong();
    }

    /** A property, as the office shows it. */
    public record Property(String key, String name, String use, String drawing, BlockPos anchor, String ownerKind, String ownerName,
                           String listing, int asking, int rent, int offers) {}

    static Property view(String key, CompoundTag t) {
        return new Property(key, t.getString("name"), t.getString("use"), t.getString("drawing"), BlockPos.of(t.getLong("anchor")),
            t.getString("ownerKind"), t.getString("ownerName"), t.getString("listing"), t.getInt("asking"), t.getInt("rent"),
            t.getList("offers", Tag.TAG_COMPOUND).size());
    }

    public static List<Property> ownedBy(UUID village, UUID player) {
        List<Property> out = new ArrayList<>();
        CompoundTag all = estate(village);
        for (String k : all.getAllKeys()) {
            CompoundTag t = all.getCompound(k);
            if ("player".equals(t.getString("ownerKind")) && player.toString().equals(t.getString("owner"))) out.add(view(k, t));
        }
        return out;
    }

    public static List<Property> listed(UUID village) {
        List<Property> out = new ArrayList<>();
        CompoundTag all = estate(village);
        for (String k : all.getAllKeys()) if (!all.getCompound(k).getString("listing").isEmpty()) out.add(view(k, all.getCompound(k)));
        out.sort(Comparator.comparing(Property::listing).thenComparingInt(Property::asking));
        return out;
    }

    @Nullable
    static CompoundTag get(UUID village, String key) {
        CompoundTag all = estate(village);
        return all.contains(key, Tag.TAG_COMPOUND) ? all.getCompound(key) : null;
    }

    static int[] half(CompoundTag t) {
        return new int[]{ t.getInt("hw"), t.getInt("hd") };
    }

    static boolean covers(CompoundTag t, BlockPos p, int margin) {
        BlockPos a = BlockPos.of(t.getLong("anchor"));
        Direction f = Direction.from2DDataValue(t.getInt("facing"));
        boolean turned = f.getAxis() == Direction.Axis.X;
        int wx = turned ? t.getInt("hd") : t.getInt("hw"), wz = turned ? t.getInt("hw") : t.getInt("hd");
        return Math.abs(p.getX() - a.getX()) <= wx + margin && Math.abs(p.getZ() - a.getZ()) <= wz + margin;
    }

    /** Does this player hold the land here (a property of its own, its own title)? */
    public static boolean ownsLandAt(UUID village, UUID player, BlockPos p) {
        CompoundTag all = estate(village);
        for (String k : all.getAllKeys()) {
            CompoundTag t = all.getCompound(k);
            if ("player".equals(t.getString("ownerKind")) && player.toString().equals(t.getString("owner")) && !"lease".equals(t.getString("land"))
                    && covers(t, p, 0)) return true;
        }
        return false;
    }

    /** A property on the register in the way of a footprint: its name, or null. */
    @Nullable
    public static String overlapping(UUID village, BlockPos anchor, int wx, int wz, @Nullable BlockPos ignore) {
        CompoundTag all = estate(village);
        for (String k : all.getAllKeys()) {
            CompoundTag t = all.getCompound(k);
            BlockPos a = BlockPos.of(t.getLong("anchor"));
            if (ignore != null && a.equals(ignore) || a.equals(anchor) && "plot".equals(t.getString("use"))) continue;
            Direction f = Direction.from2DDataValue(t.getInt("facing"));
            boolean turned = f.getAxis() == Direction.Axis.X;
            int bx = turned ? t.getInt("hd") : t.getInt("hw"), bz = turned ? t.getInt("hw") : t.getInt("hd");
            if (Math.abs(a.getX() - anchor.getX()) <= bx + wx + 1 && Math.abs(a.getZ() - anchor.getZ()) <= bz + wz + 1) return t.getString("name");
        }
        return null;
    }

    static CompoundTag fresh(UUID village, String name, String use, String drawing, String style, BlockPos anchor, Direction facing, int hw, int hd) {
        CompoundTag t = new CompoundTag();
        t.putString("name", name);
        t.putString("use", use);
        t.putString("drawing", drawing);
        t.putString("style", style);
        t.putLong("anchor", anchor.asLong());
        t.putInt("facing", facing.get2DDataValue());
        t.putInt("hw", hw);
        t.putInt("hd", hd);
        t.putString("listing", "");
        estate(village).put(keyAt(anchor), t);
        return t;
    }

    static void owner(CompoundTag t, String kind, String who, String name) {
        t.putString("ownerKind", kind);
        t.putString("owner", who);
        t.putString("ownerName", name);
    }

    /** The plot a player's order stands on, bought (or leased) when it was ordered. */
    static void landBought(ServerLevel level, UUID village, UUID player, String name, BlockPos anchor, String drawing, Direction facing,
                           long leasePence, long paidPence, long day) {
        int[] h = BuildGoal.footprint(drawing);
        CompoundTag t = fresh(village, name + "'s plot at " + where(village, anchor), "plot", drawing, "", anchor, facing, h[0], h[1]);
        owner(t, "player", player.toString(), name);
        t.putString("land", leasePence > 0 ? "lease" : "title");
        t.putLong("leaseWeek", leasePence);
        t.putLong("leasePaidTo", day + BuildOrders.LEASE_WEEKS * 7L);
        t.putLong("cost", paidPence);
        t.putLong("since", day);
        Accounts.dirty();
    }

    /** A player-built shop (or any building of its own) put on the register; returns its key. */
    public static String register(ServerLevel level, UUID village, UUID player, String playerName, String name, String use, BlockPos anchor,
                                  Direction facing, String drawing, int hw, int hd) {
        String k = keyAt(anchor);
        CompoundTag t = get(village, k);
        if (t == null) t = fresh(village, name, use, drawing, "", anchor, facing, hw, hd);
        owner(t, "player", player.toString(), playerName);
        if (!t.contains("land")) t.putString("land", "title");
        t.putLong("since", level.getDayTime() / 24000L);
        Villages.holdLot(village, anchor, hw, hd, facing);
        Accounts.dirty();
        return k;
    }

    /** The building of a finished (or called-off, part-built) order on the register, the buyer's; a home on the homes' books too. */
    static void registerBuilt(ServerLevel level, UUID village, CompoundTag o, long day, boolean partial) {
        BlockPos anchor = BlockPos.of(o.getLong("anchor"));
        Direction facing = Direction.from2DDataValue(o.getInt("facing"));
        String drawing = o.getString("drawing");
        int[] h = BuildGoal.footprint(drawing);
        CompoundTag was = get(village, keyAt(anchor));
        String buyerName = o.getString("buyerName");
        UUID buyer = BuildOrders.uuid(o, "buyer");
        CompoundTag t = fresh(village, buyerName + "'s " + BuildOrders.title(drawing).replaceFirst("^an? ", "") + (partial ? " (part-built)" : "")
            + " at " + where(village, anchor), partial ? "unfinished" : o.getString("use"), drawing, o.getString("style"), anchor, facing, h[0], h[1]);
        owner(t, "player", buyer == null ? "" : buyer.toString(), buyerName);
        t.putString("land", was == null ? "own" : was.getString("land"));
        if (was != null) { t.putLong("leaseWeek", was.getLong("leaseWeek")); t.putLong("leasePaidTo", was.getLong("leasePaidTo")); }
        t.putLong("cost", Math.max(0, o.getInt("paid") - o.getInt("refunded")) * 100L);
        t.putLong("since", day);
        t.putLong("built", day);
        Villages.holdLot(village, anchor, h[0], h[1], facing);
        if (!partial && BuildOrders.home(drawing) && buyer != null) {
            String structure = drawing.startsWith("house") ? "house" : drawing;
            Ledger.built(village, structure, anchor, facing);
            if (drawing.equals("house2")) Ledger.grow(village, anchor);
            Homes.enrol(village);
            Homes.Home home = Homes.homes(village).get(anchor.asLong());
            if (home != null) {
                home.tenure = Homes.Tenure.PLAYER;
                home.landlord = buyer;
                home.landlordName = buyerName;
                home.price = (int) (t.getLong("cost") / 100);
                home.rent = 0;
                home.toLet = false;
                home.since = day;
                Homes.save(village, home);
                t.putBoolean("onHomes", true);
                ServerPlayer p = level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(buyer);
                if (p != null) {
                    ItemStack key = new ItemStack(Items.TRIPWIRE_HOOK);
                    key.set(DataComponents.CUSTOM_NAME, Component.literal("Key to " + t.getString("name")));
                    if (!p.getInventory().add(key)) p.drop(key, false);
                }
            }
        }
        Accounts.dirty();
    }

    static String where(UUID village, BlockPos p) {
        Villages.Village v = Villages.get(village);
        if (v == null) return p.getX() + ", " + p.getZ();
        int dx = p.getX() - v.centre().getX(), dz = p.getZ() - v.centre().getZ();
        String ns = dz < -4 ? "north" : dz > 4 ? "south" : "", ew = dx > 4 ? "east" : dx < -4 ? "west" : "";
        String side = ns + (ns.isEmpty() || ew.isEmpty() ? "" : "-") + ew;
        return Math.max(Math.abs(dx), Math.abs(dz)) + " blocks " + (side.isEmpty() ? "from the square" : side + " of the square");
    }

    // ------------------------------------------------------------------ what it is worth

    public record Valuation(int land, int building, int wear, int goodwill, int contents, int total, String basis) {
        public String words() {
            return "land " + land + ", building " + building + " less wear " + wear + (goodwill > 0 ? ", goodwill " + goodwill : "")
                + (contents > 0 ? ", contents " + contents : "") + ": " + total + " (" + basis + ")";
        }
    }

    private static final Map<String, Double> REBUILD = new ConcurrentHashMap<>();

    /** What the building would cost to build again today: its materials at the market's ask, its builders at their wages. */
    static double rebuild(ServerLevel level, UUID village, String drawing, String style) {
        if (!Blueprints.has(drawing)) return 0;
        long day = level.getDayTime() / 24000L;
        String k = village + "|" + drawing + "|" + style + "|" + day;
        Double got = REBUILD.get(k);
        if (got != null) return got;
        Villages.Village v = Villages.get(village);
        if (v == null) return 0;
        BuildOrders.Quote q = new BuildOrders.Quote();
        q.village = village;
        List<BuildOrders.Cell> cells = BuildOrders.structure(drawing, BuildOrders.palette(style), BlockPos.ZERO.above(64), Direction.NORTH, null);
        BuildOrders.price(level, v, q, cells, new BuildOrders.Options());
        double r = q.materialsCost + q.labourCost * (100 + BuildOrders.OVERHEADS) / 100.0;
        REBUILD.put(k, r);
        return r;
    }

    public static Valuation value(ServerLevel level, UUID village, String key) {
        CompoundTag t = get(village, key);
        if (t == null) return new Valuation(0, 0, 0, 0, 0, 0, "not on the register");
        String drawing = t.getString("drawing");
        BlockPos a = BlockPos.of(t.getLong("anchor"));
        int land = "lease".equals(t.getString("land")) ? 0 : (int) Math.round(BuildOrders.landValue(village, drawing, a));
        boolean plot = "plot".equals(t.getString("use"));
        double b = plot ? 0 : rebuild(level, village, drawing, t.getString("style").isEmpty() ? "oak" : t.getString("style"));
        if ("unfinished".equals(t.getString("use"))) b = t.getLong("cost") / 100.0 * 0.5;
        long day = level.getDayTime() / 24000L;
        long age = Math.max(0, day - t.getLong("built"));
        double wear = plot ? 0 : b * Math.min(WEAR_MOST / 100.0, age / 7.0 * WEAR_WEEK / 1000.0);
        boolean home = BuildOrders.home(drawing) && !plot;
        double idx = home ? HousingMarket.index(village) : 1.0;
        int goodwill = goodwill(village, t);
        int contents = t.getBoolean("withContents") ? contentsValue(level, village, t) : 0;
        int total = (int) Math.round((land + b - wear) * idx) + goodwill + contents;
        String basis = plot ? "the plot's value" : (home ? "replacement less wear, as the housing market stands (x" + String.format(Locale.ROOT, "%.2f", idx) + ")"
            : "replacement less wear" + (goodwill > 0 ? ", and the business's goodwill" : ""));
        return new Valuation(land, (int) Math.round(b), (int) Math.round(wear), goodwill, contents, Math.max(1, total), basis);
    }

    /** Four weeks of what the business in it made over its costs, over the days its books have (none if it lost money). */
    static int goodwill(UUID village, CompoundTag t) {
        String biz = t.getString("biz");
        if (biz.isEmpty()) return 0;
        for (Business b : Businesses.all(village)) {
            if (!b.id.equals(biz) || b.days.isEmpty()) continue;
            long net = 0;
            for (Business.Day d : b.days) net += d.net();
            return (int) Math.max(0, net * 28 / b.days.size() / 100);
        }
        return 0;
    }

    /** The containers inside its walls. */
    static List<Container> containers(ServerLevel level, CompoundTag t) {
        List<Container> out = new ArrayList<>();
        BlockPos a = BlockPos.of(t.getLong("anchor"));
        int r = Math.max(t.getInt("hw"), t.getInt("hd"));
        for (BlockPos p : BlockPos.betweenClosed(a.offset(-r, 0, -r), a.offset(r, 12, r))) {
            if (!level.isLoaded(p)) continue;
            if (level.getBlockEntity(p) instanceof Container c) out.add(c);
        }
        return out;
    }

    static int contentsValue(ServerLevel level, UUID village, CompoundTag t) {
        double v = 0;
        for (Container c : containers(level, t)) for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && !Market.isCoin(s)) v += MarketMaker.live(village) ? MarketMaker.bid(level, village, s.copyWithCount(1)) * s.getCount() : PriceIndex.each(level, village, s) * s.getCount() * 0.7;
        }
        return (int) Math.round(v);
    }

    /** The contents back to the seller: into its pack, the rest at its feet; with the seller away, kept for it at the office. */
    static int returnContents(ServerLevel level, UUID village, CompoundTag t, @Nullable ServerPlayer seller) {
        int n = 0;
        ListTag kept = t.getList("kept", Tag.TAG_COMPOUND);
        for (Container c : containers(level, t)) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) continue;
                n += s.getCount();
                ItemStack out = s.copy();
                c.setItem(i, ItemStack.EMPTY);
                if (seller != null) { if (!seller.getInventory().add(out)) seller.drop(out, false); }
                else kept.add(out.save(level.registryAccess()));
            }
            c.setChanged();
        }
        if (!kept.isEmpty()) {
            CompoundTag held = Accounts.sub(Accounts.sub(BuildOrders.book(village), "kept"), t.getString("owner"));
            ListTag l = held.getList("items", Tag.TAG_COMPOUND);
            l.addAll(kept);
            held.put("items", l);
        }
        return n;
    }

    /** What the office kept for a player (its things from a building it sold while away): into its pack. */
    public static String collectKept(ServerLevel level, ServerPlayer p, UUID village) {
        CompoundTag all = Accounts.sub(BuildOrders.book(village), "kept");
        String k = p.getUUID().toString();
        if (!all.contains(k)) return "Nothing kept for you.";
        int n = 0;
        for (Tag tg : all.getCompound(k).getList("items", Tag.TAG_COMPOUND)) {
            ItemStack s = ItemStack.parseOptional(level.registryAccess(), (CompoundTag) tg);
            n += s.getCount();
            if (!p.getInventory().add(s)) p.drop(s, false);
        }
        all.remove(k);
        Accounts.dirty();
        return "The office gave you back " + n + " things from the buildings you sold.";
    }

    // ------------------------------------------------------------------ listing

    /** A player's property put up for sale (or to let, at so much a day for a home, a week for premises). */
    public static String list(ServerLevel level, ServerPlayer p, UUID village, String key, boolean let, int price, boolean withContents) {
        CompoundTag t = get(village, key);
        if (t == null) return "That's not on the register.";
        if (!mine(t, p)) return "That isn't yours to sell.";
        if ("unfinished".equals(t.getString("use")) && let) return "Nobody will rent a half-built shell.";
        if (price <= 0) return "Name a price.";
        t.putString("listing", let ? "let" : "sale");
        t.putInt(let ? "rent" : "asking", price);
        t.putBoolean("withContents", withContents);
        t.putLong("listed", level.getDayTime() / 24000L);
        t.put("offers", new ListTag());
        if (let && t.getBoolean("onHomes")) {
            Homes.Home h = Homes.homes(village).get(BlockPos.of(t.getLong("anchor")).asLong());
            if (h != null) { h.toLet = true; h.rent = Math.min(20, price); Homes.save(village, h); }
        }
        Accounts.dirty();
        Villages.tell(village, level.getDayTime() / 24000L, p.getName().getString() + " put " + t.getString("name") + (let ? " up to let at " + price + " a " + (t.getBoolean("onHomes") ? "day" : "week")
            : " up for sale at " + price + Homes.coins(price)));
        return t.getString("name") + (let ? " is to let at " + price + Homes.coins(price) + " a " + (t.getBoolean("onHomes") ? "day; the next household wanting a home will take it."
            : "week; the town's firms will make offers.") : " is for sale at " + price + Homes.coins(price) + ". Offers will come in over the next days.");
    }

    public static String withdraw(ServerLevel level, ServerPlayer p, UUID village, String key) {
        CompoundTag t = get(village, key);
        if (t == null || !mine(t, p)) return "That isn't yours.";
        if ("let".equals(t.getString("listing")) && t.getBoolean("onHomes")) {
            Homes.Home h = Homes.homes(village).get(BlockPos.of(t.getLong("anchor")).asLong());
            if (h != null && h.members.isEmpty()) { h.toLet = false; Homes.save(village, h); }
        }
        t.putString("listing", "");
        t.put("offers", new ListTag());
        Accounts.dirty();
        return t.getString("name") + " is off the market.";
    }

    static boolean mine(CompoundTag t, ServerPlayer p) {
        return "player".equals(t.getString("ownerKind")) && p.getUUID().toString().equals(t.getString("owner"));
    }

    // ------------------------------------------------------------------ offers

    static void offer(CompoundTag t, String kind, String who, String name, int price, long day, String why) {
        ListTag l = t.getList("offers", Tag.TAG_COMPOUND);
        for (Tag x : l) if (((CompoundTag) x).getString("who").equals(who)) return;
        CompoundTag o = new CompoundTag();
        o.putInt("no", t.getInt("offerNo") + 1);
        t.putInt("offerNo", o.getInt("no"));
        o.putString("kind", kind);
        o.putString("who", who);
        o.putString("name", name);
        o.putInt("price", price);
        o.putLong("day", day);
        o.putString("why", why);
        l.add(o);
        t.put("offers", l);
    }

    /** The morning (Market.payday): offers expire and come in, ground rents and premises' rents fall due, folk list their houses. */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag all = estate(id);
        if (all.isEmpty() && BuildOrders.orders(id).isEmpty()) return;
        CompoundTag book = BuildOrders.book(id);
        if (book.getLong("morning") == day + 1) return;
        book.putLong("morning", day + 1);
        for (String k : all.getAllKeys()) {
            CompoundTag t = all.getCompound(k);
            ListTag offers = t.getList("offers", Tag.TAG_COMPOUND);
            offers.removeIf(x -> day - ((CompoundTag) x).getLong("day") > OFFER_DAYS);
            t.put("offers", offers);
            groundRent(level, id, t, day);
            premisesRent(level, id, t, day);
            String listing = t.getString("listing");
            if (listing.equals("sale")) bids(level, v, k, t, day);
            else if (listing.equals("let") && !t.getBoolean("onHomes")) tenants(level, v, t, day);
            if ("folk".equals(t.getString("ownerKind")) && day - t.getLong("listed") > FOLK_DAYS) all.remove(k);
        }
        folkList(level, v, day);
        Accounts.dirty();
    }

    /** The bids for a property for sale: a household for a home, a firm for premises, the council. */
    static void bids(ServerLevel level, Villages.Village v, String key, CompoundTag t, long day) {
        if (!"player".equals(t.getString("ownerKind"))) return;
        UUID id = v.id();
        Valuation val = value(level, id, key);
        int asking = t.getInt("asking");
        boolean home = BuildOrders.home(t.getString("drawing")) && t.getBoolean("onHomes");
        if (home) {
            List<VillageFolkEntity> best = null;
            int bestScore = Integer.MIN_VALUE;
            for (List<VillageFolkEntity> hh : HousingMarket.households(id)) {
                Homes.Home have = Homes.homeOf(id, hh.get(0).getUUID());
                if (have != null && have.tenure == Homes.Tenure.OWNED) continue;
                HousingMarket.Interest in = HousingMarket.interest(level, id, hh, have, day);
                int score = in.score() + (have == null ? 3 : 0) + hh.size();
                if (score > bestScore) { bestScore = score; best = hh; }
            }
            if (best != null) {
                int bid = asking <= val.total() * 105 / 100 ? asking : Math.min(asking, val.total() * (92 + Math.min(8, Math.max(0, bestScore))) / 100);
                HousingMarket.Means m = HousingMarket.means(level, v, best, bid);
                if (!m.can()) { bid = Math.min(bid, m.cash()); m = HousingMarket.means(level, v, best, bid); }
                if (m.can() && bid > 0) offer(t, "folk", best.get(0).getUUID().toString(), Homes.names(best), bid, day,
                    "a household wanting a home of its own (" + m.why() + ")");
            }
        } else if (!"plot".equals(t.getString("use"))) {
            Business best = null;
            int bestBid = 0;
            for (Business b : Businesses.all(id)) {
                if (b.town() || !b.open || b.mirror()) continue;
                boolean fits = fits(b, t.getString("use"));
                int bid = Math.min(asking, val.total() * (fits ? 100 : 85) / 100);
                if (b.till < bid * 100L) bid = (int) Math.min(bid, b.till * 8 / 1000);
                if (bid > bestBid && bid >= val.total() / 2) { bestBid = bid; best = b; }
            }
            if (best != null) offer(t, "firm", best.id, best.name, bestBid, day, fits(best, t.getString("use"))
                ? "premises fit for its trade" : "premises it would refit for its trade");
        }
        int council = val.total() * (home ? COUNCIL_HOME : COUNCIL_PREMISES) / 100;
        if (day > t.getLong("listed") && Ledger.coins(id) >= council && council > 0) {
            offer(t, "town", "town", "the council", Math.min(asking, council), day, home ? "a house for the council to let" : "premises for the town");
        }
    }

    /** Is this firm's trade the use the premises were built for? */
    static boolean fits(Business b, String use) {
        String kind = b.cards.getOrDefault("kind", "");
        return !use.isEmpty() && (use.contains(kind) && !kind.isEmpty() || b.type.name().toLowerCase(Locale.ROOT).contains(use)
            || use.equals("bakery") && kind.equals("baker") || use.equals("smithy") && (kind.equals("toolsmith") || kind.equals("armourer"))
            || (use.equals("shop") || use.equals("store")) && (kind.equals("greengrocer") || kind.equals("general") || kind.equals("stationer")));
    }

    /** Premises to let: a firm offers the rent asked (or what its till can carry) for them. */
    static void tenants(ServerLevel level, Villages.Village v, CompoundTag t, long day) {
        if (!t.getString("tenant").isEmpty()) return;
        int rent = t.getInt("rent");
        for (Business b : Businesses.all(v.id())) {
            if (b.town() || !b.open || b.mirror() || b.till < rent * 400L) continue;
            offer(t, "firm", b.id, b.name, rent, day, (fits(b, t.getString("use")) ? "premises fit for its trade" : "room to grow") + ", at " + rent + " a week");
            return;
        }
    }

    /** A leasehold's ground rent: due each week, out of the hall's dues to the owner, else owed. */
    static void groundRent(ServerLevel level, UUID village, CompoundTag t, long day) {
        if (!"lease".equals(t.getString("land")) || t.getLong("leaseWeek") <= 0) return;
        if (day < t.getLong("leasePaidTo")) return;
        t.putLong("leasePaidTo", t.getLong("leasePaidTo") + 7);
        t.putLong("leaseOwed", t.getLong("leaseOwed") + t.getLong("leaseWeek"));
    }

    /** A firm's rent for premises let to it: a seventh of the week's rent each morning, from its till to the owner's dues. */
    static void premisesRent(ServerLevel level, UUID village, CompoundTag t, long day) {
        String biz = t.getString("tenant");
        if (biz.isEmpty() || !"player".equals(t.getString("ownerKind"))) return;
        for (Business b : Businesses.all(village)) {
            if (!b.id.equals(biz)) continue;
            if (!b.open) { t.putString("tenant", ""); t.putString("listing", "let"); return; }
            long rent = t.getInt("rent") * 100L / 7;
            long paid = Businesses.payOut(level, village, b, rent, day);
            if (paid > 0) {
                Accounts.add(Accounts.sub(TownBonds.book(village), "due"), t.getString("owner"), paid);
                Accounts.add(t, "rentAll", paid);
                CapitalBook.dirty();
            }
            return;
        }
        t.putString("tenant", "");
    }

    /** A household that owns its house and is saving to build or has outgrown it puts it on the market. */
    static void folkList(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        int listed = 0;
        CompoundTag all = estate(id);
        for (String k : all.getAllKeys()) if ("folk".equals(all.getCompound(k).getString("ownerKind"))) listed++;
        if (listed >= 2) return;
        for (Homes.Home h : Homes.homes(id).values()) {
            if (h.tenure != Homes.Tenure.OWNED || h.members.isEmpty() || Homes.seat(h) || Flats.isFlat(h) || all.contains(keyAt(h.anchor))) continue;
            List<VillageFolkEntity> m = Homes.loadedMembers(id, h);
            if (m.isEmpty()) continue;
            boolean saving = HousingMarket.planOf(id, m.get(0).getUUID()) != null;
            boolean outgrown = h.members.size() > Homes.bedsIn(level, id, h).size();
            if (!saving && !outgrown) continue;
            if (HousingMarket.owedOn(id, h.anchor.asLong()) > 0) continue;
            int price = Math.max(1, Homes.price(id, h));
            int[] half = BuildGoal.footprint(h.structure);
            CompoundTag t = fresh(id, Homes.names(m) + "'s house at " + Homes.address(id, v, h), "home", h.structure, "", h.anchor, Direction.NORTH, half[0], half[1]);
            owner(t, "folk", m.get(0).getUUID().toString(), Homes.names(m));
            t.putString("listing", "sale");
            t.putInt("asking", price);
            t.putLong("listed", day);
            t.putString("why", saving ? "saving to build a house of its own" : "grown too big for it");
            t.putBoolean("onHomes", true);
            Villages.tell(id, day, Homes.names(m) + " put their house at " + Homes.address(id, v, h) + " on the market at " + price + Homes.coins(price)
                + " (" + t.getString("why") + ")");
            return;
        }
    }

    // ------------------------------------------------------------------ deals

    /** The owner takes an offer: the price to it, the title to the buyer, its things back unless sold with it. */
    public static String accept(ServerLevel level, ServerPlayer p, UUID village, String key, int no) {
        CompoundTag t = get(village, key);
        if (t == null || !mine(t, p)) return "That isn't yours.";
        CompoundTag offer = null;
        for (Tag x : t.getList("offers", Tag.TAG_COMPOUND)) if (((CompoundTag) x).getInt("no") == no) offer = (CompoundTag) x;
        if (offer == null) return "That offer has lapsed.";
        Villages.Village v = Villages.get(village);
        if (v == null) return "No town.";
        long day = level.getDayTime() / 24000L;
        int price = offer.getInt("price");
        String kind = offer.getString("kind");
        boolean let = "let".equals(t.getString("listing"));
        String done;
        if (let && "firm".equals(kind)) {
            t.putString("tenant", offer.getString("who"));
            t.putString("tenantName", offer.getString("name"));
            t.putString("listing", "");
            t.put("offers", new ListTag());
            Accounts.dirty();
            Villages.tell(village, day, offer.getString("name") + " took " + t.getString("name") + " on a lease from " + p.getName().getString() + " at " + price + " a week");
            return offer.getString("name") + " has the lease, at " + price + Homes.coins(price) + " a week: the rent waits for you at the hall.";
        }
        switch (kind) {
            case "folk" -> done = sellToHousehold(level, v, t, offer, price, day);
            case "firm" -> done = sellToFirm(level, v, t, offer, price, day);
            case "town" -> done = sellToTown(level, v, t, price, day);
            case "player" -> done = sellToPlayer(level, v, t, offer, price, day);
            default -> done = "No buyer.";
        }
        if (!done.isEmpty()) return done;
        int things = t.getBoolean("withContents") ? 0 : returnContents(level, village, t, p);
        BuildOrders.toPlayer(level, village, p.getUUID(), price);
        Earnings.record(p, village, Earnings.Source.PROPERTY, price, "selling " + t.getString("name"), "for a building sold");
        record(village, day, t.getString("name"), price, p.getName().getString(), offer.getString("name"));
        t.putString("listing", "");
        t.put("offers", new ListTag());
        t.putLong("since", day);
        t.putLong("cost", price * 100L);
        Accounts.dirty();
        Villages.tell(village, day, p.getName().getString() + " sold " + t.getString("name") + " to " + offer.getString("name") + " for " + price + Homes.coins(price));
        return "Sold to " + offer.getString("name") + " for " + price + Homes.coins(price) + (things > 0 ? "; its contents (" + things + ") are back with you" : "") + ".";
    }

    public static String decline(ServerLevel level, ServerPlayer p, UUID village, String key, int no) {
        CompoundTag t = get(village, key);
        if (t == null || !mine(t, p)) return "That isn't yours.";
        ListTag l = t.getList("offers", Tag.TAG_COMPOUND);
        String who = "";
        for (int i = 0; i < l.size(); i++) if (l.getCompound(i).getInt("no") == no) { who = l.getCompound(i).getString("name"); l.remove(i); break; }
        t.put("offers", l);
        Accounts.dirty();
        return who.isEmpty() ? "That offer has lapsed." : "You turned down " + who + ".";
    }

    /** A household buys a home (its purses, its savings, the bank's mortgage, as for any house): "" when done, else why not. */
    static String sellToHousehold(ServerLevel level, Villages.Village v, CompoundTag t, CompoundTag offer, int price, long day) {
        UUID id = v.id();
        Homes.Home h = Homes.homes(id).get(BlockPos.of(t.getLong("anchor")).asLong());
        if (h == null) return "That house is not on the homes' books.";
        List<VillageFolkEntity> household = null;
        UUID lead = UUID.fromString(offer.getString("who"));
        for (List<VillageFolkEntity> hh : HousingMarket.households(id)) for (VillageFolkEntity f : hh) if (f.getUUID().equals(lead)) household = hh;
        if (household == null) return "That household is no longer about.";
        if (!h.members.isEmpty() && !h.members.contains(lead)) {
            for (UUID m : new ArrayList<>(h.members)) { VillageFolkEntity f = Homes.loaded(id, m); if (f != null) f.forgetBed(); }
            h.members.clear();
        }
        h.toLet = false;
        if (!HousingMarket.buy(level, v, h, household, price, day)) return "They couldn't raise the money after all.";
        owner(t, "folk", lead.toString(), offer.getString("name"));
        t.putBoolean("onHomes", true);
        return "";
    }

    static String sellToFirm(ServerLevel level, Villages.Village v, CompoundTag t, CompoundTag offer, int price, long day) {
        for (Business b : Businesses.all(v.id())) {
            if (!b.id.equals(offer.getString("who"))) continue;
            if (b.till < price * 100L) return b.name + " hasn't the money in its till just now.";
            b.till -= price * 100L;
            Businesses.dirty();
            b.note(day, "bought its own premises, " + t.getString("name") + ", for " + price + Homes.coins(price));
            b.cards.put("premises", t.getString("name") + " (its own)");
            owner(t, "firm", b.id, b.name);
            t.putString("biz", b.id);
            return "";
        }
        return "That firm is gone.";
    }

    static String sellToTown(ServerLevel level, Villages.Village v, CompoundTag t, int price, long day) {
        UUID id = v.id();
        if (Ledger.takeCoins(id, price) < price) return "The treasury can't find it just now.";
        owner(t, "town", "town", "the town");
        BlockPos a = BlockPos.of(t.getLong("anchor"));
        if (t.getBoolean("onHomes")) {
            Homes.Home h = Homes.homes(id).get(a.asLong());
            if (h != null) {
                h.tenure = Homes.Tenure.RENTED;
                h.landlord = null;
                h.landlordName = "";
                h.toLet = false;
                h.rent = Homes.rent(id, h);
                Homes.save(id, h);
            }
        } else if (Blueprints.has(t.getString("drawing")) && !"plot".equals(t.getString("use"))) {
            Ledger.built(id, t.getString("drawing"), a, Direction.from2DDataValue(t.getInt("facing")));   // on the town's own books now
        }
        return "";
    }

    static String sellToPlayer(ServerLevel level, Villages.Village v, CompoundTag t, CompoundTag offer, int price, long day) {
        UUID buyer = UUID.fromString(offer.getString("who"));
        ServerPlayer b = level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(buyer);
        if (b == null) return offer.getString("name") + " isn't about to pay just now.";
        if (Market.coinsHeld(b) < price) return offer.getString("name") + " hasn't the coin on it just now.";
        Market.payOut(b, price);
        CoinCensus.playerIn(v.id(), price);
        toNewOwner(v.id(), t, b);
        b.displayClientMessage(Component.literal("You bought " + t.getString("name") + " for " + price + Homes.coins(price) + "."), false);
        return "";
    }

    static void toNewOwner(UUID village, CompoundTag t, ServerPlayer b) {
        owner(t, "player", b.getUUID().toString(), b.getName().getString());
        if (t.getBoolean("onHomes")) {
            Homes.Home h = Homes.homes(village).get(BlockPos.of(t.getLong("anchor")).asLong());
            if (h != null) {
                h.tenure = Homes.Tenure.PLAYER;
                h.landlord = b.getUUID();
                h.landlordName = b.getName().getString();
                h.toLet = !h.members.isEmpty();
                if (h.toLet && h.rent <= 0) h.rent = Homes.rent(village, h);
                Homes.save(village, h);
            }
        }
    }

    /** A player buys a listing at the asking price: another player's, a household's own house (its folk staying on as tenants). */
    public static String buy(ServerLevel level, ServerPlayer p, UUID village, String key) {
        CompoundTag t = get(village, key);
        if (t == null || !"sale".equals(t.getString("listing"))) return "That's not for sale.";
        if (mine(t, p)) return "It's yours already.";
        int price = t.getInt("asking");
        if (Market.coinsHeld(p) < price) return "It's " + price + Homes.coins(price) + "; you have " + Market.coinsHeld(p) + ".";
        long day = level.getDayTime() / 24000L;
        String kind = t.getString("ownerKind");
        if (kind.equals("player")) {
            offer(t, "player", p.getUUID().toString(), p.getName().getString(), price, day, "at the asking price");
            return "Your offer of the asking price (" + price + Homes.coins(price) + ") is with " + t.getString("ownerName") + ".";
        }
        if (!kind.equals("folk")) return "That's not for sale.";
        Market.payOut(p, price);
        CoinCensus.playerIn(village, price);
        // The household's price into its purses: the coin stays in the town.
        List<VillageFolkEntity> m = new ArrayList<>();
        Homes.Home h = Homes.homes(village).get(BlockPos.of(t.getLong("anchor")).asLong());
        if (h != null) m = Homes.grown(Homes.loadedMembers(village, h));
        if (m.isEmpty()) Businesses.toTreasury(village, price * 100L);
        else for (int i = 0; i < m.size(); i++) m.get(i).earn(price / m.size() + (i < price % m.size() ? 1 : 0));
        if (h != null) { h.rent = Homes.rent(village, h); }
        String seller = t.getString("ownerName");
        toNewOwner(village, t, p);
        t.putString("listing", "");
        t.putString("use", "home");
        t.putLong("cost", price * 100L);
        t.putLong("since", day);
        t.putLong("built", day);
        record(village, day, t.getString("name"), price, seller, p.getName().getString());
        Accounts.dirty();
        Villages.tell(village, day, seller + " sold their house to " + p.getName().getString() + " for " + price + Homes.coins(price) + ", and stay on as tenants");
        return "You bought " + t.getString("name") + " for " + price + Homes.coins(price) + ". " + seller + " stay on as your tenants, paying rent.";
    }

    /** A player's offer below the asking price, for the owner to take or leave. */
    public static String bid(ServerLevel level, ServerPlayer p, UUID village, String key, int price) {
        CompoundTag t = get(village, key);
        if (t == null || !"sale".equals(t.getString("listing")) || !"player".equals(t.getString("ownerKind"))) return "That's not for sale by a player.";
        if (mine(t, p)) return "It's yours already.";
        if (Market.coinsHeld(p) < price) return "You'd need the " + price + Homes.coins(price) + " on you to pay.";
        offer(t, "player", p.getUUID().toString(), p.getName().getString(), price, level.getDayTime() / 24000L, "a player's offer");
        Accounts.dirty();
        return "Your offer of " + price + Homes.coins(price) + " is with " + t.getString("ownerName") + ".";
    }

    /** The ground rent owed on a leasehold, paid now. */
    public static String payLease(ServerLevel level, ServerPlayer p, UUID village, String key) {
        CompoundTag t = get(village, key);
        if (t == null || !mine(t, p)) return "That isn't yours.";
        int owed = (int) Math.ceil(t.getLong("leaseOwed") / 100.0);
        if (owed <= 0) return "No ground rent owed.";
        if (Market.coinsHeld(p) < owed) return owed + Homes.coins(owed) + " owed; you have " + Market.coinsHeld(p) + ".";
        Market.payOut(p, owed);
        CoinCensus.playerIn(village, owed);
        Businesses.toTreasury(village, owed * 100L);
        TownBudget.in(village, "fees", owed * 100L);
        t.putLong("leaseOwed", 0);
        Accounts.dirty();
        return "Ground rent paid: " + owed + Homes.coins(owed) + ".";
    }

    static void record(UUID village, long day, String what, int price, String seller, String buyer) {
        ListTag l = BuildOrders.book(village).getList("sales", Tag.TAG_STRING);
        l.add(StringTag.valueOf("Day " + (day + 1) + ": " + what + ", " + seller + " to " + buyer + ", " + price + Homes.coins(price)));
        while (l.size() > 20) l.remove(0);
        BuildOrders.book(village).put("sales", l);
    }

    public static List<String> sales(UUID village) {
        List<String> out = new ArrayList<>();
        for (Tag x : BuildOrders.book(village).getList("sales", Tag.TAG_STRING)) out.add(0, x.getAsString());
        return out;
    }

    /** The property's lines for the office: what it is, its value, its listing, its offers. */
    public static List<String> lines(ServerLevel level, UUID village, String key) {
        CompoundTag t = get(village, key);
        List<String> out = new ArrayList<>();
        if (t == null) return out;
        Valuation val = value(level, village, key);
        int[] h = half(t);
        out.add(t.getString("name") + " (" + t.getString("use") + ", " + (2 * h[0] + 1) + "x" + (2 * h[1] + 1) + ")");
        out.add("Worth about " + val.total() + ": " + val.words());
        if ("lease".equals(t.getString("land"))) out.add("Leasehold: " + t.getLong("leaseWeek") / 100.0 + " a week ground rent" + (t.getLong("leaseOwed") > 0 ? ", " + t.getLong("leaseOwed") / 100.0 + " owed" : ""));
        if (!t.getString("tenant").isEmpty()) out.add("Let to " + t.getString("tenantName") + " at " + t.getInt("rent") + " a week");
        String listing = t.getString("listing");
        if (!listing.isEmpty()) out.add(listing.equals("sale") ? "For sale at " + t.getInt("asking") + (t.getBoolean("withContents") ? ", with its contents" : "") : "To let at " + t.getInt("rent"));
        for (Tag x : t.getList("offers", Tag.TAG_COMPOUND)) {
            CompoundTag o = (CompoundTag) x;
            out.add("Offer " + o.getInt("no") + ": " + o.getString("name") + ", " + o.getInt("price") + " (" + o.getString("why") + ")");
        }
        return out;
    }

    /** LandTitles.morning: a title for each building on the register that is not a house already on the homes' books. */
    static void titles(ServerLevel level, UUID village, CompoundTag all, java.util.Set<String> seen, double growth) {
        CompoundTag est = estate(village);
        for (String k : est.getAllKeys()) {
            CompoundTag t = est.getCompound(k);
            if (t.getBoolean("onHomes") || "folk".equals(t.getString("ownerKind"))) continue;
            String key = "prop:" + k;
            seen.add(key);
            CompoundTag title = Accounts.sub(all, key);
            title.putString("kind", "plot".equals(t.getString("use")) ? "plot" : "premises (" + t.getString("use") + ")");
            title.putString("name", t.getString("name"));
            title.putString("holder", t.getString("ownerKind"));
            title.putString("holderName", t.getString("ownerName"));
            long earns = t.getString("tenant").isEmpty() ? 0 : t.getInt("rent") * 100L;
            title.putLong("earns", earns);
            title.putBoolean("idle", earns == 0);
            LandTitles.value(title, earns, growth, value(level, village, k).total() * 100L);
        }
    }

    // ------------------------------------------------------------------ tests

    public static void resetForTests() {
        REBUILD.clear();
    }

    public static CompoundTag propertyForTests(UUID village, BlockPos anchor) {
        return get(village, keyAt(anchor));
    }

    public static String keyForTests(BlockPos anchor) {
        return keyAt(anchor);
    }

    public static void morningForTests(ServerLevel level, Villages.Village v, long day) {
        BuildOrders.book(v.id()).remove("morning");
        morning(level, v, day);
    }
}
