package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.net.PropertyPagePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [orders17] The property office: one screen, composed here and drawn by the client (PropertyScreen), for everything a player
 * does with buildings, with no command typed. Four tabs: <b>Orders</b> (each order's progress, its revised time and why, the
 * payment due, and Pay, Priority, Cancel), <b>My property</b> (each building's worth, its listing and offers, and Sell, Let,
 * Withdraw, Accept, Decline, Refit, Storey, Wing), <b>Market</b> (what is for sale: other players' buildings, the folk's own
 * houses, the town's premises titles; the last sales) and <b>Commission</b> (choose a drawing, a style and which way it faces,
 * priority, a lease, your own materials; the quote line by line; Place order). The plot is the ground in front of you, the
 * building's door toward you.
 *
 * <p>Reached in the world by asking any folk ("I'd like to see the property office", the talk screen's Town group) or from
 * the city books' Property page.
 */
public final class PropertyOffice {

    private PropertyOffice() {}

    /** What a player has chosen on the office's forms (kept while the server runs). */
    static final class Form {
        String tab = "orders";
        int drawing, style, turn = -1;
        boolean priority, lease, bring;
        BlockPos plot;
        Direction facing;
        final Map<String, Integer> price = new HashMap<>();
        final Map<String, Integer> use = new HashMap<>();
        final Map<String, List<String>> quotes = new HashMap<>();
        @Nullable List<String> quote;
        String msg = "";
        /** [join21] The building the player came about (its shop's Sell, Let or Repurpose), shown first. */
        String focus = "";
    }

    private static final Map<UUID, Form> FORMS = new ConcurrentHashMap<>();

    static Form form(Player p) {
        return FORMS.computeIfAbsent(p.getUUID(), k -> new Form());
    }

    @Nullable
    static Villages.Village town(ServerPlayer p) {
        Villages.Village v = Villages.nearest(p.level(), p.blockPosition(), Villages.VILLAGE_RANGE * 2);
        return v;
    }

    public static void open(ServerPlayer p) {
        send(p);
    }

    /** [join21] Open the office on a tab, with one of the player's buildings first and a word on what to do with it
     *  (its shop's Sell, Let and Repurpose buttons). */
    public static void openOn(ServerPlayer p, String tab, @Nullable String key, String what) {
        Form f = form(p);
        f.tab = tab;
        f.focus = key == null ? "" : key;
        if (what.equals("buy")) {                                            // [biz25] buying premises for a shop
            f.msg = "Buildings for sale in town are here: buy one, then set a Shop Counter in it and it is your shop.";
            send(p);
            return;
        }
        f.msg = key == null ? "You hold no building on the register here yet: a shop goes on it when it is set up, a building you commission when it stands."
            : switch (what) {
                case "sell" -> "Your shop's building is first: set its price and put it up for sale (with its contents, or without).";
                case "let" -> "Your shop's building is first: set the rent and offer it to let; a firm looking for premises may take it.";
                default -> "Your shop's building is first: choose its new use for a refit quote, or add a storey or a wing.";
            };
        send(p);
    }

    /** Open the office on a quote (player-15's gallery hands its choice over here). */
    public static void openQuote(ServerPlayer p, BuildOrders.Quote q) {
        Form f = form(p);
        f.tab = "commission";
        f.quote = q.lines();
        f.plot = q.plot;
        f.facing = q.facing;
        List<String> d = BuildOrders.drawings(q.village);
        f.drawing = Math.max(0, d.indexOf(q.drawing));
        List<String> s = BuildOrders.styles(q.village);
        f.style = Math.max(0, s.indexOf(q.style));
        f.priority = q.priority;
        f.lease = q.lease;
        send(p);
    }

    /** "I'd like to see the property office", said to any folk: the office opens. Null when the words are about something else. */
    @Nullable
    public static String talk(VillageFolkEntity f, Player p, String text) {
        if (text == null) return null;
        String low = text.toLowerCase(Locale.ROOT);
        boolean office = low.contains("property office") || low.contains("building order") || low.contains("commission a building")
            || low.contains("have a building built") || low.contains("sell my building") || low.contains("sell my house");
        if (!office || !(p instanceof ServerPlayer sp)) return null;
        open(sp);
        return "The property office? Here — the builders' book, the register, and what's for sale. Mind, the builders are only as quick as the queue.";
    }

    // ------------------------------------------------------------------ the buttons

    public static void act(ServerPlayer p, String op, String arg) {
        if (!(p.level() instanceof ServerLevel level)) return;
        Villages.Village v = town(p);
        Form f = form(p);
        if (v == null) { f.msg = "There's no town here."; send(p); return; }
        UUID id = v.id();
        String[] a = arg.split("\\|", -1);
        String msg = "";
        try {
            switch (op) {
                case "open" -> { }
                case "tab" -> f.tab = a[0];
                case "drawing" -> { f.drawing += Integer.parseInt(a[0]); f.quote = null; }
                case "style" -> { f.style += Integer.parseInt(a[0]); f.quote = null; }
                case "turn" -> { f.turn = (f.turn + 1) % 4; f.quote = null; }
                case "toggle" -> {
                    switch (a[0]) {
                        case "priority" -> f.priority = !f.priority;
                        case "lease" -> f.lease = !f.lease;
                        case "bring" -> f.bring = !f.bring;
                        default -> { }
                    }
                    f.quote = null;
                }
                case "quote" -> f.quote = commission(level, p, v, f, false).lines();
                case "place" -> {
                    BuildOrders.Quote q = commission(level, p, v, f, f.quote != null);
                    BuildOrders.Result r = BuildOrders.place(level, p, q);
                    msg = r.message();
                    if (r.ok()) { f.tab = "orders"; f.quote = null; }
                }
                case "pay" -> msg = BuildOrders.pay(level, p, a[0]);
                case "priority" -> msg = BuildOrders.prioritise(level, p, a[0]);
                case "cancel" -> msg = BuildOrders.cancel(level, p, a[0]);
                case "price" -> f.price.put(a[0], Math.max(1, Integer.parseInt(a[1])));
                case "sell" -> msg = PropertyMarket.list(level, p, id, a[0], false, Integer.parseInt(a[1]), "1".equals(a[2]));
                case "let" -> msg = PropertyMarket.list(level, p, id, a[0], true, Integer.parseInt(a[1]), false);
                case "withdraw" -> msg = PropertyMarket.withdraw(level, p, id, a[0]);
                case "accept" -> msg = PropertyMarket.accept(level, p, id, a[0], Integer.parseInt(a[1]));
                case "decline" -> msg = PropertyMarket.decline(level, p, id, a[0], Integer.parseInt(a[1]));
                case "buy" -> msg = PropertyMarket.buy(level, p, id, a[0]);
                case "bid" -> msg = PropertyMarket.bid(level, p, id, a[0], Integer.parseInt(a[1]));
                case "title" -> msg = LandTitles.playerBuys(level, p, id, Integer.parseInt(a[0]));
                case "lease" -> msg = PropertyMarket.payLease(level, p, id, a[0]);
                case "use" -> { f.use.merge(a[0], Integer.parseInt(a[1]), Integer::sum); f.quotes.remove(a[0]); }
                case "refitq" -> f.quotes.put(a[0], Refits.quote(level, id, a[0], useFor(f, a[0])).lines());
                case "refit" -> msg = BuildOrders.place(level, p, Refits.quote(level, id, a[0], useFor(f, a[0]))).message();
                case "extendq" -> f.quotes.put(a[0], Refits.extension(level, id, a[0], a[1]).lines());
                case "extend" -> msg = BuildOrders.place(level, p, Refits.extension(level, id, a[0], a[1])).message();
                case "collect" -> msg = TownBonds.playerCollects(p, id) + " " + PropertyMarket.collectKept(level, p, id);
                default -> msg = "";
            }
        } catch (RuntimeException e) {
            msg = "The clerk didn't follow that.";
        }
        if (!msg.isEmpty()) f.msg = msg;
        send(p);
    }

    static String useFor(Form f, String key) {
        List<String> uses = Refits.uses();
        return uses.get(Math.floorMod(f.use.getOrDefault(key, 0), uses.size()));
    }

    /** The order the commission tab describes: the drawing, the style and the turn chosen, on the ground in front of the player. */
    static BuildOrders.Quote commission(ServerLevel level, ServerPlayer p, Villages.Village v, Form f, boolean reuse) {
        List<String> ds = BuildOrders.drawings(v.id()), ss = BuildOrders.styles(v.id());
        String drawing = ds.get(Math.floorMod(f.drawing, ds.size()));
        String style = ss.get(Math.floorMod(f.style, ss.size()));
        Direction look = p.getDirection();
        Direction facing = f.turn < 0 ? look.getOpposite() : Direction.from2DDataValue(Math.floorMod(look.getOpposite().get2DDataValue() + f.turn, 4));
        int[] half = BuildGoal.footprint(drawing);
        int depth = facing.getAxis() == look.getAxis() ? half[1] : half[0];
        BlockPos plot = p.blockPosition().relative(look, depth + 3);
        plot = new BlockPos(plot.getX(), groundY(level, plot, p.blockPosition().getY()), plot.getZ());
        if (reuse && f.plot != null && f.facing != null) { plot = f.plot; facing = f.facing; }   // the ground the quote was for
        f.plot = plot;
        f.facing = facing;
        BuildOrders.Options o = new BuildOrders.Options();
        o.priority = f.priority;
        o.lease = f.lease;
        if (f.bring) o.fromInventory = p;
        return BuildOrders.quote(level, v.id(), drawing, style, plot, facing, p.getUUID(), o);
    }

    static int groundY(ServerLevel level, BlockPos at, int near) {
        for (int y = near + 4; y >= near - 6; y--) {
            BlockPos p = new BlockPos(at.getX(), y, at.getZ());
            if (!level.getBlockState(p.below()).isAir() && level.getBlockState(p.below()).getFluidState().isEmpty() && !BuildGoal.soft(level.getBlockState(p.below()))
                    && (level.getBlockState(p).isAir() || BuildGoal.soft(level.getBlockState(p)))) return y;
        }
        return near;
    }

    /** The Property page of the town's books: the orders going up, the builders' pace, what is for sale, the last sales. */
    public static CompoundTag report(ServerLevel level, UUID village) {
        CompoundTag t = new CompoundTag();
        ListTag l = new ListTag();
        Villages.Village v = Villages.get(village);
        if (!Accounts.town(village).contains(BuildOrders.BOOK, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            l.add(StringTag.valueOf("#The builders"));
            l.add(StringTag.valueOf("Nobody has commissioned a building here yet. Ask any folk for the property office: a quote, a time, and the builders at it."));
            t.put("lines", l);
            return t;
        }
        l.add(StringTag.valueOf("#The builders"));
        l.add(StringTag.valueOf(BuildOrders.crews(village) + " commission crew(s) of " + BuildOrders.hands(village) + " hands; "
            + (v != null && BuildOrders.townBusy(level, village) ? "the town is building its own: commissions get the afternoons" : "the town's own crews are free: commissions get the whole day")));
        List<CompoundTag> open = BuildOrders.open(village);
        l.add(StringTag.valueOf("#Going up (" + open.size() + ")"));
        if (open.isEmpty()) l.add(StringTag.valueOf("Nothing on order. Anyone may commission a building at the office."));
        for (CompoundTag o : open) {
            BuildOrders.Status s = BuildOrders.status(level, BuildOrders.idOf(village, o));
            if (s == null) continue;
            l.add(StringTag.valueOf(o.getString("buyerName") + "'s " + o.getString("name") + ": " + s.percent + "%, " + s.stage + ", about day " + (s.etaDay + 1)
                + (s.waitingOn.isEmpty() ? "" : " (" + s.waitingOn + ")")));
        }
        List<PropertyMarket.Property> listed = PropertyMarket.listed(village);
        l.add(StringTag.valueOf("#For sale and to let (" + listed.size() + ")"));
        for (PropertyMarket.Property pr : listed) {
            l.add(StringTag.valueOf(pr.name() + " (" + pr.use() + "), " + pr.ownerName() + "'s: " + (pr.listing().equals("sale") ? "for sale at " + pr.asking() : "to let at " + pr.rent())
                + (pr.offers() > 0 ? ", " + pr.offers() + " offer(s)" : "")));
        }
        CompoundTag est = PropertyMarket.estate(village);
        l.add(StringTag.valueOf("#The register (" + est.size() + ")"));
        for (String k : est.getAllKeys()) {
            CompoundTag p = est.getCompound(k);
            l.add(StringTag.valueOf(p.getString("name") + ": " + p.getString("use") + ", " + p.getString("ownerName") + "'s" + (p.getString("tenant").isEmpty() ? "" : ", let to " + p.getString("tenantName"))));
        }
        List<String> sales = PropertyMarket.sales(village);
        if (!sales.isEmpty()) {
            l.add(StringTag.valueOf("#Sales"));
            for (String s : sales.subList(0, Math.min(6, sales.size()))) l.add(StringTag.valueOf(s));
        }
        t.put("lines", l);
        return t;
    }

    // ------------------------------------------------------------------ the screen

    static void send(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel level)) return;
        PacketDistributor.sendToPlayer(p, new PropertyPagePayload(page(level, p)));
    }

    /** One row of a tab: its lines and its buttons ({label, op, arg}). */
    static CompoundTag row(List<String> lines, String... buttons) {
        CompoundTag r = new CompoundTag();
        ListTag l = new ListTag();
        for (String s : lines) l.add(StringTag.valueOf(s));
        r.put("lines", l);
        ListTag b = new ListTag();
        for (int i = 0; i + 2 < buttons.length; i += 3) {
            CompoundTag t = new CompoundTag();
            t.putString("label", buttons[i]);
            t.putString("op", buttons[i + 1]);
            t.putString("arg", buttons[i + 2]);
            b.add(t);
        }
        r.put("buttons", b);
        return r;
    }

    public static CompoundTag page(ServerLevel level, ServerPlayer p) {
        Form f = form(p);
        Villages.Village v = town(p);
        CompoundTag t = new CompoundTag();
        t.putString("tab", f.tab);
        t.putString("msg", f.msg);
        if (v == null) { t.putString("town", "no town near"); return t; }
        UUID id = v.id();
        t.putString("town", Villages.name(id));
        t.putInt("coins", Market.coinsHeld(p));
        ListTag rows = new ListTag();
        switch (f.tab) {
            case "property" -> property(level, p, id, f, rows);
            case "market" -> market(level, p, id, rows);
            case "commission" -> commissionRows(level, p, v, f, rows);
            default -> orders(level, p, id, rows);
        }
        t.put("rows", rows);
        return t;
    }

    static void orders(ServerLevel level, ServerPlayer p, UUID id, ListTag rows) {
        long due = Accounts.sub(TownBonds.book(id), "due").getLong(p.getUUID().toString());
        boolean kept = Accounts.sub(BuildOrders.book(id), "kept").contains(p.getUUID().toString());
        if (due >= 100 || kept) rows.add(row(List.of("Waiting for you at the hall: " + due / 100 + " coins" + (kept ? ", and things from a building you sold" : "")), "Collect", "collect", ""));
        List<String> mine = BuildOrders.ordersOf(id, p.getUUID());
        if (mine.isEmpty()) rows.add(row(List.of("No building orders yet. The Commission tab gets you a quote for any building, on the ground in front of you.")));
        for (String oid : mine) {
            BuildOrders.Status s = BuildOrders.status(level, oid);
            if (s == null) continue;
            List<String> b = new ArrayList<>();
            if (s.status.equals("open") || s.status.equals("awaiting")) {
                CompoundTag o = BuildOrders.order(oid);
                b.addAll(List.of(s.due > 0 ? "Pay " + s.due : "Pay ahead", "pay", oid));
                if (s.status.equals("open") && o != null && !o.getBoolean("priority")) b.addAll(List.of("Priority", "priority", oid));
                if (s.status.equals("open")) b.addAll(List.of("Cancel", "cancel", oid));
            }
            CompoundTag r = row(s.lines(), b.toArray(new String[0]));
            r.putInt("bar", s.percent);
            rows.add(r);
        }
    }

    static void property(ServerLevel level, ServerPlayer p, UUID id, Form f, ListTag rows) {
        List<PropertyMarket.Property> mine = new ArrayList<>(PropertyMarket.ownedBy(id, p.getUUID()));
        if (!f.focus.isEmpty()) mine.sort(java.util.Comparator.comparingInt(pr -> pr.key().equals(f.focus) ? 0 : 1));   // [join21] the one it came about first
        if (mine.isEmpty()) rows.add(row(List.of("You hold no buildings on the register here. A building you commission is put on it when it stands.")));
        for (PropertyMarket.Property pr : mine) {
            String k = pr.key();
            List<String> lines = new ArrayList<>(PropertyMarket.lines(level, id, k));
            PropertyMarket.Valuation val = PropertyMarket.value(level, id, k);
            int price = f.price.getOrDefault(k, val.total());
            CompoundTag t = PropertyMarket.get(id, k);
            boolean home = t != null && t.getBoolean("onHomes");
            int rent = Math.max(1, home ? Math.min(20, price / 40) : price / 12);
            List<String> b = new ArrayList<>(List.of("-10%", "price", k + "|" + Math.max(1, price * 9 / 10), "+10%", "price", k + "|" + Math.max(price + 1, price * 11 / 10),
                "Sell at " + price, "sell", k + "|" + price + "|0", "Sell with contents", "sell", k + "|" + price + "|1",
                "Let at " + rent + (home ? "/day" : "/week"), "let", k + "|" + rent));
            if (!pr.listing().isEmpty()) b.addAll(List.of("Withdraw", "withdraw", k));
            if (t != null) for (net.minecraft.nbt.Tag x : t.getList("offers", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                CompoundTag o = (CompoundTag) x;
                b.addAll(List.of("Accept #" + o.getInt("no"), "accept", k + "|" + o.getInt("no"), "Decline #" + o.getInt("no"), "decline", k + "|" + o.getInt("no")));
            }
            if (t != null && t.getLong("leaseOwed") > 0) b.addAll(List.of("Pay ground rent", "lease", k));
            rows.add(row(lines, b.toArray(new String[0])));
            if (t != null && !"plot".equals(pr.use()) && !"unfinished".equals(pr.use())) {
                String use = useFor(f, k);
                List<String> rl = new ArrayList<>();
                rl.add("Change its use to: " + use + " (needs " + String.join(", ", Refits.needs(use)) + ")");
                List<String> q = f.quotes.get(k);
                if (q != null) rl.addAll(q);
                rows.add(row(rl, "<", "use", k + "|-1", ">", "use", k + "|1", "Refit quote", "refitq", k, "Order refit", "refit", k,
                    "Storey quote", "extendq", k + "|storey", "Order storey", "extend", k + "|storey", "Wing quote", "extendq", k + "|wing", "Order wing", "extend", k + "|wing"));
            }
        }
    }

    static void market(ServerLevel level, ServerPlayer p, UUID id, ListTag rows) {
        boolean any = false;
        for (PropertyMarket.Property pr : PropertyMarket.listed(id)) {
            if ("player".equals(pr.ownerKind()) && p.getUUID().toString().equals(PropertyMarket.get(id, pr.key()).getString("owner"))) continue;
            any = true;
            List<String> lines = new ArrayList<>();
            PropertyMarket.Valuation val = PropertyMarket.value(level, id, pr.key());
            lines.add(pr.name() + " (" + pr.use() + "), " + pr.ownerName() + "'s");
            lines.add(pr.listing().equals("sale") ? "For sale at " + pr.asking() + "; worth about " + val.total() : "To let at " + pr.rent());
            if (pr.listing().equals("sale")) rows.add(row(lines, "Buy at " + pr.asking(), "buy", pr.key(), "Offer " + Math.max(1, pr.asking() * 9 / 10), "bid", pr.key() + "|" + Math.max(1, pr.asking() * 9 / 10)));
            else rows.add(row(lines));
        }
        int n = 0;
        for (Map.Entry<String, CompoundTag> e : LandTitles.listing(id)) {
            n++;
            if (!LandTitles.buyable(e.getKey(), e.getValue())) continue;
            any = true;
            CompoundTag t = e.getValue();
            rows.add(row(List.of("The town's title to " + t.getString("name") + " (" + t.getString("kind") + "): worth " + Businesses.pence(t.getLong("value"))
                + ", its firm's rent " + Businesses.pence(t.getLong("earns")) + " a week to the holder"), "Buy title", "title", Integer.toString(n)));
        }
        if (!any) rows.add(row(List.of("Nothing for sale just now.")));
        List<String> sales = PropertyMarket.sales(id);
        if (!sales.isEmpty()) rows.add(row(sales.subList(0, Math.min(8, sales.size()))));
    }

    static void commissionRows(ServerLevel level, ServerPlayer p, Villages.Village v, Form f, ListTag rows) {
        List<String> ds = BuildOrders.drawings(v.id()), ss = BuildOrders.styles(v.id());
        String drawing = ds.get(Math.floorMod(f.drawing, ds.size()));
        String style = ss.get(Math.floorMod(f.style, ss.size()));
        int[] half = BuildGoal.footprint(drawing);
        rows.add(row(List.of("Building: " + BuildOrders.title(drawing) + " (" + (2 * half[0] + 1) + " by " + (2 * half[1] + 1) + ")", "Style: " + style
                + "; door " + (f.turn < 0 ? "toward you" : "turned " + (f.turn * 90) + " degrees"),
                "Priority: " + (f.priority ? "yes" : "no") + "; land: " + (f.lease ? "a ground lease" : "bought") + "; your own materials: " + (f.bring ? "what you carry" : "none"),
                "The plot is the ground in front of you; stand where you want it and ask again."),
            "< Building", "drawing", "-1", "Building >", "drawing", "1", "< Style", "style", "-1", "Style >", "style", "1", "Turn", "turn", "",
            "Priority", "toggle", "priority", "Lease", "toggle", "lease", "Bring materials", "toggle", "bring", "Get quote", "quote", ""));
        if (f.quote != null) {
            rows.add(row(f.quote, "Place order", "place", ""));
        }
    }
}
