package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.Business.Day;
import com.jrpetty.mcassistant.entity.Business.Lot;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import com.jrpetty.mcassistant.village.Ledger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [business-books] A business's books, to read: on its screen (client/BusinessScreen, sent whole as one tag), on the
 * town's books' Businesses page (every business, a line each, a click to its books), in words for the commands, and in a
 * folk's own words when it is asked about its business or its pay.
 *
 * <p>Who sees what: anybody the summary (who owns it, who works there, what it took this week and whether it made money);
 * its owners, the town's citizens and friends, and admins (creative) the whole of it. The town's own (its stores, its
 * market) are open books.
 */
public final class BusinessBooks {

    private BusinessBooks() {}

    // ------------------------------------------------------------------ the town's list

    /** Every business of the town, a line each (the Businesses page of the town's books). */
    public static CompoundTag list(ServerLevel level, UUID village) {
        long day = Businesses.today(level);
        CompoundTag out = new CompoundTag();
        out.putString("town", Villages.name(village));
        ListTag rows = new ListTag();
        for (Business b : Businesses.all(village)) {
            CompoundTag r = new CompoundTag();
            Day w = b.last(day, 7);
            r.putString("id", b.id);
            r.putString("name", b.name);
            r.putString("type", b.type.title);
            r.putString("owner", b.ownersWords());
            r.putBoolean("town", b.town());
            r.putBoolean("mirror", b.mirror());
            r.putBoolean("open", b.open);
            r.putInt("staff", Businesses.staff(village, b).size());
            r.putLong("rev7", w.revenue);
            r.putLong("net7", w.net());
            r.putLong("revAll", b.allTime.revenue);
            r.putLong("netAll", b.allTime.net());
            r.putLong("till", b.town() ? Businesses.treasuryPence(village) : b.till);
            rows.add(r);
        }
        out.put("rows", rows);
        out.putString("census", CoinCensus.line(village));
        return out;
    }

    // ------------------------------------------------------------------ one business

    /** One business's books for its screen; {@code full} false gives only the public summary. */
    public static CompoundTag books(ServerLevel level, UUID village, Business b, @Nullable Player viewer) {
        long day = Businesses.today(level);
        boolean full = viewer == null || Businesses.mayRead(level, village, b, viewer);
        CompoundTag t = new CompoundTag();
        t.putString("village", village.toString());
        t.putString("town", Villages.name(village));
        t.putString("id", b.id);
        t.putString("name", b.name);
        t.putString("type", b.type.title);
        t.putBoolean("full", full);
        t.putBoolean("public", b.town());
        t.putBoolean("open", b.open);
        t.putLong("opened", b.opened + 1);
        t.putLong("day", day + 1);
        ListTag owners = new ListTag();
        for (Business.Stake s : b.owners) {
            CompoundTag o = new CompoundTag();
            o.putString("name", s.name());
            o.putString("holder", s.holder().name().toLowerCase(Locale.ROOT));
            o.putInt("parts", s.parts());
            owners.add(o);
        }
        t.put("owners", owners);
        t.putString("ownersWords", b.ownersWords());
        if (viewer != null) {
            t.putBoolean("mine", b.ownedBy(viewer.getUUID()));
            t.putLong("due", b.ownersDue.getOrDefault(viewer.getUUID(), 0L));
            t.putBoolean("admin", viewer.isCreative());
        }
        ListTag staff = new ListTag();
        Day today = b.last(day, 1), week = b.last(day, 7);
        for (VillageFolkEntity f : Businesses.staff(village, b)) {
            CompoundTag s = new CompoundTag();
            s.putString("name", f.displayNameCap());
            s.putString("job", JobWorth.postOf(f).noun());
            s.putInt("wage", Wealth.wage(f));
            s.putLong("paidToday", today.staffPay.getOrDefault(f.getUUID(), 0L));
            s.putLong("paidWeek", week.staffPay.getOrDefault(f.getUUID(), 0L));
            s.putBoolean("short", b.shortTime.contains(f.getUUID()));
            staff.add(s);
        }
        t.put("staff", staff);
        t.put("week", period(week));
        if (!full) return t;
        t.put("today", period(today));
        t.put("all", period(b.allTime));
        t.putLong("till", b.town() ? Businesses.treasuryPence(village) : b.till);
        t.putLong("account", b.account);
        t.putLong("loan", b.loan);
        t.putString("lender", b.lender);
        t.putLong("credit", Businesses.creditLimit(b, day));
        t.putDouble("markup", b.markup);
        t.putLong("worth", Businesses.worth(level, village, b));
        t.putLong("stockCost", b.stockCost());
        t.putLong("stockUnits", b.stockUnits());
        t.putLong("rent", b.town() ? 0 : Businesses.rent(village, b));
        // The wares, the week's, item by item: sold, takings, cost of goods, margin, what one cost and what it asks, and how
        // one is made (its last batch's card, and the standard cost with its labour).
        ListTag items = new ListTag();
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(week.lines.keySet());
        for (String k : b.stock.keySet()) if (b.stock.get(k).qty > 0) keys.add(k);
        for (String k : keys) {
            Business.Line l = week.lines.getOrDefault(k, new Business.Line());
            Lot lot = b.stock.get(k);
            CompoundTag r = new CompoundTag();
            r.putString("key", k);
            r.putString("name", k.startsWith("service/") ? "Service" : Businesses.nameOf(k));
            r.putLong("units", l.units);
            r.putLong("revenue", l.revenue);
            r.putLong("cogs", l.cogs);
            r.putLong("used", l.used);
            r.putLong("made", l.made);
            r.putLong("onHand", lot == null ? 0 : lot.qty);
            r.putLong("stockCost", lot == null ? 0 : lot.cost);
            r.putDouble("each", lot != null && lot.qty > 0 ? lot.each() / 100.0 : l.units > 0 ? l.cogs / 100.0 / l.units : 0);
            ItemStack one = k.startsWith("service/") ? ItemStack.EMPTY : Stockroom.sampleOf(k);
            if (!one.isEmpty()) {
                CostBook.Basis std = CostBook.of(one);
                r.putString("standard", std.words());
                r.putDouble("stdTotal", std.total());
                r.putDouble("stdLabour", std.labour() + std.overhead() + std.tools());
                r.putDouble("price", Purchases.priceEach(level, village, one, null));
                r.putDouble("full", Businesses.fullCost(level, village, b, one, day));
                r.putInt("trend", PriceIndex.trend(village, one));
            }
            String card = b.cards.get(k);
            if (card != null) r.putString("card", card);
            r.putBoolean("dropped", b.dropped.contains(k));
            items.add(r);
        }
        t.put("items", items);
        ListTag orders = new ListTag();
        for (int i = b.orders.size() - 1; i >= 0; i--) {
            Business.Order o = b.orders.get(i);
            CompoundTag r = new CompoundTag();
            r.putLong("day", o.day() + 1);
            r.putString("from", o.supplier());
            r.putString("item", Businesses.nameOf(o.item()));
            r.putLong("qty", o.qty());
            r.putDouble("unit", o.unitHundredths() / 10000.0);
            r.putLong("discount", o.discount());
            r.putLong("total", o.total());
            orders.add(r);
        }
        t.put("orders", orders);
        // The stores' sales to each business (the town's own books show what it sold to whom).
        if (b.id.equals("storehouse")) {
            ListTag to = new ListTag();
            for (Business o : Businesses.all(village)) {
                if (o.town() || o.mirror()) continue;
                long n = 0, coin = 0;
                for (Business.Order x : o.orders) if (x.supplier().equals("the town stores") && day - x.day() < 7) { n += x.qty(); coin += x.total(); }
                if (n == 0) continue;
                CompoundTag r = new CompoundTag();
                r.putString("to", o.name);
                r.putLong("qty", n);
                r.putLong("total", coin);
                to.add(r);
            }
            t.put("soldTo", to);
        }
        ListTag notes = new ListTag();
        for (int i = b.notes.size() - 1; i >= 0; i--) {
            String n = b.notes.get(i);
            int bar = n.indexOf('|');
            String when = bar > 0 ? "Day " + (Long.parseLong(n.substring(0, bar)) + 1) + ": " : "";
            notes.add(StringTag.valueOf(when + (bar >= 0 ? n.substring(bar + 1) : n)));
        }
        t.put("notes", notes);
        ListTag chart = new ListTag();
        for (long d = day - 13; d <= day; d++) {
            Day one = null;
            for (Day x : b.days) if (x.day == d) one = x;
            CompoundTag r = new CompoundTag();
            r.putLong("day", d + 1);
            r.putLong("rev", one == null ? 0 : one.revenue);
            r.putLong("net", one == null ? 0 : one.net());
            chart.add(r);
        }
        t.put("chart", chart);
        t.putString("census", b.town() ? CoinCensus.line(village) : "");
        return t;
    }

    static CompoundTag period(Day d) {
        CompoundTag t = new CompoundTag();
        t.putLong("revenue", d.revenue);
        t.putLong("cogs", d.cogs);
        t.putLong("gross", d.revenue - d.cogs);
        t.putLong("wages", d.wages);
        t.putLong("rent", d.rent);
        t.putLong("other", d.other);
        t.putLong("taxes", d.taxes);
        t.putLong("losses", d.losses);
        t.putLong("interest", d.interest);
        t.putLong("net", d.net());
        t.putLong("drawings", d.drawings);
        t.putLong("capital", d.capital);
        t.putLong("bought", d.bought);
        t.putLong("toTown", d.toTown);
        long units = 0;
        for (Business.Line l : d.lines.values()) units += l.units;
        t.putLong("units", units);
        return t;
    }

    /** Open a business's books on a player's screen. */
    public static void show(ServerPlayer p, UUID village, Business b) {
        ServerLevel level = p.serverLevel();
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
            new com.jrpetty.mcassistant.net.BusinessScreenPayload(books(level, village, b, p)));
    }

    /** Open the town's list of businesses on a player's screen. */
    public static void showList(ServerPlayer p, UUID village) {
        CompoundTag t = list(p.serverLevel(), village);
        t.putBoolean("list", true);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.BusinessScreenPayload(t));
    }

    // ------------------------------------------------------------------ in words

    /** A business's books in words (the command, the admin's look). */
    public static String words(ServerLevel level, UUID village, Business b) {
        long day = Businesses.today(level);
        StringBuilder sb = new StringBuilder();
        sb.append(b.name).append(" of ").append(Villages.name(village)).append(" (").append(b.type.title).append("), owned by ").append(b.ownersWords())
            .append(b.open ? "" : " (closed)").append(".\n");
        List<String> staff = new ArrayList<>();
        for (VillageFolkEntity f : Businesses.staff(village, b)) staff.add(f.displayNameCap() + " " + Wealth.wage(f) + "c/day" + (b.shortTime.contains(f.getUUID()) ? " (short time)" : ""));
        sb.append("Staff: ").append(staff.isEmpty() ? "none" : String.join(", ", staff)).append(".\n");
        for (String[] p : new String[][]{ { "Today", "1" }, { "This week", "7" } }) {
            Day d = b.last(day, Integer.parseInt(p[1]));
            sb.append(p[0]).append(": ").append(periodWords(d)).append("\n");
        }
        sb.append("All time: ").append(periodWords(b.allTime)).append("\n");
        if (!b.town()) {
            sb.append("Till ").append(Businesses.pence(b.till)).append(b.account < 0 ? ", owes the stores " + Businesses.pence(-b.account) : "")
                .append(b.loan > 0 ? ", owes the " + b.lender + " " + Businesses.pence(b.loan) : "").append("; stock ").append(b.stockUnits())
                .append(" at cost ").append(Businesses.pence(b.stockCost())).append("; markup ").append(Businesses.pct(b.markup)).append("; worth ")
                .append(Businesses.pence(Businesses.worth(level, village, b))).append(".\n");
        }
        Day w = b.last(day, 7);
        for (Map.Entry<String, Business.Line> e : w.lines.entrySet()) {
            Business.Line l = e.getValue();
            if (l.units <= 0 && l.used <= 0 && l.made <= 0) continue;
            sb.append("  ").append(e.getKey().startsWith("service/") ? "service" : Businesses.nameOf(e.getKey())).append(": sold ").append(l.units)
                .append(" for ").append(Businesses.pence(l.revenue)).append(", cost ").append(Businesses.pence(l.cogs)).append(", margin ")
                .append(Businesses.pence(l.revenue - l.cogs)).append(l.made > 0 ? ", made " + l.made : "").append(l.used > 0 ? ", used " + l.used : "")
                .append("\n");
            String card = b.cards.get(e.getKey());
            if (card != null) sb.append("     last batch: ").append(card).append("\n");
        }
        for (int i = Math.max(0, b.orders.size() - 5); i < b.orders.size(); i++) {
            Business.Order o = b.orders.get(i);
            sb.append("  bought day ").append(o.day() + 1).append(": ").append(o.qty()).append(" ").append(Businesses.nameOf(o.item()).toLowerCase(Locale.ROOT))
                .append(" from ").append(o.supplier()).append(" at ").append(Businesses.coins(o.unitHundredths() / 10000.0)).append(" = ")
                .append(Businesses.pence(o.total())).append(o.discount() > 0 ? " (" + Businesses.pence(o.discount()) + " off)" : "").append("\n");
        }
        for (int i = Math.max(0, b.notes.size() - 4); i < b.notes.size(); i++) {
            String n = b.notes.get(i);
            int bar = n.indexOf('|');
            sb.append("  ").append(bar >= 0 ? "day " + (Long.parseLong(n.substring(0, bar)) + 1) + ": " + n.substring(bar + 1) : n).append("\n");
        }
        return sb.toString().trim();
    }

    public static String periodWords(Day d) {
        return "took " + Businesses.pence(d.revenue) + ", cost of goods " + Businesses.pence(d.cogs) + ", wages " + Businesses.pence(d.wages) + ", rent "
            + Businesses.pence(d.rent) + ", running " + Businesses.pence(d.other) + ", taxes " + Businesses.pence(d.taxes)
            + (d.losses > 0 ? ", losses " + Businesses.pence(d.losses) : "") + (d.interest > 0 ? ", interest " + Businesses.pence(d.interest) : "")
            + ": net " + Businesses.pence(d.net()) + (d.drawings > 0 ? "; drawn " + Businesses.pence(d.drawings) : "");
    }

    /** The folk's card: a proprietor's business and what it draws; a hand's employer and its pay. Empty if neither. */
    /** [econ-1a] A business by name in a sentence: "the café", but "Wren's Farm" as it is. */
    static String called(Business b) {
        String n = b.name.toLowerCase(Locale.ROOT);
        return b.name.contains("'") ? b.name : n.startsWith("the ") ? n : "the " + n;
    }

    public static String cardLine(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby()) return "";
        List<String> parts = new ArrayList<>();
        for (Business b : Businesses.all(village)) {
            if (!b.ownedBy(f.getUUID())) continue;
            int pct = 0;
            for (Business.Stake s : b.owners) if (f.getUUID().equals(s.who())) pct += s.parts() * 100 / Business.WHOLE;
            parts.add("owns " + (pct >= 100 ? "" : pct + "% of ") + called(b) + " (drew " + Businesses.pence(b.allTime.drawings * pct / 100)
                + " so far)");
        }
        Business works = Businesses.employer(village, f);
        if (works != null) {
            parts.add(works.town() ? "works at " + called(works) + ", paid by the town"
                : works.ownedBy(f.getUUID()) && works.owners.size() == 1 ? "works it itself, paid out of its till"
                : "works at " + called(works) + ", paid out of its till by " + works.ownersWords()
                + (works.shortTime.contains(f.getUUID()) ? " (on short time)" : ""));
        }
        return parts.isEmpty() ? "" : capital(String.join("; ", parts)) + ".";
    }

    /** "How's business?", "Could I see your books?": a proprietor or a hand says how its business stands, and the books
     *  open for a player who may read them. Null when the words are not about it. */
    @Nullable
    public static String talk(VillageFolkEntity f, Player p, String text) {
        String q = " " + text.toLowerCase(Locale.ROOT) + " ";
        boolean books = q.contains("your books") || q.contains("business books") || q.contains("see the accounts") || q.contains("your accounts");
        boolean how = q.contains("how's business") || q.contains("how is business") || q.contains("how's trade") || q.contains("your business");
        if (!books && !how) return null;
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level)) return null;
        Business b = null;
        for (Business o : Businesses.all(village)) if (o.ownedBy(f.getUUID())) { b = o; break; }
        if (b == null) b = Businesses.employer(village, f);
        if (b == null) return how && !books ? "Business? I don't keep one. You'd want the café or the shop for that." : null;
        long day = Businesses.today(level);
        Day w = b.last(day, 7);
        String mine = b.ownedBy(f.getUUID()) ? "my " : "the ";
        String said = w.revenue <= 0 ? "Quiet so far. Nothing through " + mine + b.name.toLowerCase(Locale.ROOT) + "'s till this week yet."
            : "This week " + mine + b.name.toLowerCase(Locale.ROOT) + " took " + Businesses.pence(w.revenue) + "; after the stock, the wages and the rent, "
              + (w.net() >= 0 ? Businesses.pence(w.net()) + " to the good." : Businesses.pence(-w.net()) + " short. We'll have to do better.");
        if (books && p instanceof ServerPlayer sp) {
            show(sp, village, b);
            return said + (Businesses.mayRead(level, village, b, p) ? " Here, the books — have a look." : " You can see the summary; the rest is for friends of the town.");
        }
        return said;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ commands

    /** /village business: the town's businesses; books, order, buy, invest, draw; and the coin audit. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("business")
            .executes(c -> listCmd(c))
            .then(Commands.literal("books").then(Commands.argument("id", StringArgumentType.word()).executes(c -> booksCmd(c, false))))
            .then(Commands.literal("text").then(Commands.argument("id", StringArgumentType.word()).executes(c -> booksCmd(c, true))))
            .then(Commands.literal("audit").requires(s -> s.hasPermission(2)).executes(BusinessBooks::auditCmd))
            .then(Commands.literal("buy").then(Commands.argument("id", StringArgumentType.word()).executes(c -> deal(c, "buy", 0))))
            .then(Commands.literal("invest").then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("coins", IntegerArgumentType.integer(1, 100000)).executes(c -> deal(c, "invest", IntegerArgumentType.getInteger(c, "coins"))))))
            .then(Commands.literal("draw").then(Commands.argument("id", StringArgumentType.word()).executes(c -> deal(c, "draw", 0))))
            .then(Commands.literal("order").then(Commands.argument("id", StringArgumentType.word()).then(Commands.argument("item", StringArgumentType.word())
                .then(Commands.argument("qty", IntegerArgumentType.integer(1, 100000)).executes(BusinessBooks::orderCmd)))))
            .then(Commands.literal("page").executes(BusinessBooks::pageCmd))
            .then(Commands.literal("stage").requires(s -> s.hasPermission(2)).executes(BusinessBooks::stageCmd))
            .then(Commands.literal("morning").requires(s -> s.hasPermission(2)).executes(c -> {
                Villages.Village v = near(c);
                if (v == null) return 0;
                Businesses.morningForTests(c.getSource().getLevel(), v);
                c.getSource().sendSuccess(() -> Component.literal("The businesses of " + Villages.name(v.id()) + " had their morning."), true);
                return 1;
            }));
    }

    @Nullable
    static Villages.Village near(CommandContext<CommandSourceStack> c) {
        Villages.Village v = Villages.nearest(c.getSource().getLevel(), net.minecraft.core.BlockPos.containing(c.getSource().getPosition()), 400);
        if (v == null) c.getSource().sendFailure(Component.literal("No village near."));
        return v;
    }

    /** The town's books open at the Businesses page. */
    private static int pageCmd(CommandContext<CommandSourceStack> c) {
        Villages.Village v = near(c);
        if (v == null || !(c.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        CompoundTag books = Annals.snapshot(c.getSource().getLevel(), v);
        books.putString("page", "Businesses");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    /**
     * For the camera (operators): the nearest town's café made a business if it has none standing (at this spot), and a
     * day's trade put through its books from the folk's own purses: a bulk order of wheat from the stores, three batches
     * of bread baked of it, a dozen loaves sold over the counter, so its books have something to show. Says STAGED.
     */
    private static int stageCmd(CommandContext<CommandSourceStack> c) {
        Villages.Village v = near(c);
        if (v == null) return 0;
        ServerLevel level = c.getSource().getLevel();
        UUID id = v.id();
        if (Businesses.premises(id, Business.Type.CAFE) == null) {
            Villages.builtAtForTests(id, "cafe", net.minecraft.core.BlockPos.containing(c.getSource().getPosition()));
        }
        Businesses.ensure(level, id);
        Business cafe = Businesses.of(id, "cafe");
        if (cafe == null) return 0;
        ItemStack wheat = new ItemStack(net.minecraft.world.item.Items.WHEAT), bread = new ItemStack(net.minecraft.world.item.Items.BREAD);
        Businesses.OrderResult r = Businesses.order(level, id, cafe, wheat, 18, true);
        java.util.Map<String, ItemStack> samples = java.util.Map.of("minecraft:wheat", wheat, "minecraft:bread", bread);
        for (int i = 0; i < 3; i++) Businesses.made(level, id, cafe, java.util.Map.of("minecraft:wheat", 6L), java.util.Map.of("minecraft:bread", 2L), samples);
        int sold = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (sold >= 12 || !(a instanceof VillageFolkEntity f) || f.isBaby() || !f.spend(1)) continue;
            Ledger.addCoins(id, 1);
            Stockroom.sold(level, id, Stockroom.Seller.CAFE, bread, 1, 1);
            sold++;
        }
        int n = sold;
        c.getSource().sendSuccess(() -> Component.literal("STAGED cafe: " + r.words() + "; " + n + " loaves sold; owned by " + cafe.ownersWords()), true);
        return 1;
    }

    private static int listCmd(CommandContext<CommandSourceStack> c) {
        Villages.Village v = near(c);
        if (v == null) return 0;
        if (c.getSource().getEntity() instanceof ServerPlayer p) {
            showList(p, v.id());
            return 1;
        }
        CompoundTag t = list(c.getSource().getLevel(), v.id());
        StringBuilder sb = new StringBuilder("The businesses of " + Villages.name(v.id()) + ":");
        for (net.minecraft.nbt.Tag e : t.getList("rows", 10)) {
            CompoundTag r = (CompoundTag) e;
            sb.append("\n").append(r.getString("id")).append(" — ").append(r.getString("name")).append(", ").append(r.getString("owner")).append(": week took ")
                .append(Businesses.pence(r.getLong("rev7"))).append(", net ").append(Businesses.pence(r.getLong("net7")));
        }
        c.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int booksCmd(CommandContext<CommandSourceStack> c, boolean text) {
        Villages.Village v = near(c);
        if (v == null) return 0;
        Businesses.ensure(c.getSource().getLevel(), v.id());
        Business b = Businesses.of(v.id(), StringArgumentType.getString(c, "id"));
        if (b == null) {
            c.getSource().sendFailure(Component.literal("No business called " + StringArgumentType.getString(c, "id") + " here. /village business lists them."));
            return 0;
        }
        if (!text && c.getSource().getEntity() instanceof ServerPlayer p) {
            show(p, v.id(), b);
            return 1;
        }
        boolean may = !(c.getSource().getEntity() instanceof Player pl) || Businesses.mayRead(c.getSource().getLevel(), v.id(), b, pl);
        String w = may ? words(c.getSource().getLevel(), v.id(), b) : b.name + ", owned by " + b.ownersWords() + ": its full books are for friends of the town.";
        c.getSource().sendSuccess(() -> Component.literal(w), false);
        return 1;
    }

    private static int auditCmd(CommandContext<CommandSourceStack> c) {
        Villages.Village v = near(c);
        if (v == null) return 0;
        String page = CoinCensus.page(v.id());
        c.getSource().sendSuccess(() -> Component.literal(page), false);
        return 1;
    }

    private static int deal(CommandContext<CommandSourceStack> c, String what, int coins) {
        Villages.Village v = near(c);
        if (v == null || !(c.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        Businesses.ensure(c.getSource().getLevel(), v.id());
        Business b = Businesses.of(v.id(), StringArgumentType.getString(c, "id"));
        if (b == null) {
            c.getSource().sendFailure(Component.literal("No business by that name here."));
            return 0;
        }
        ServerLevel level = c.getSource().getLevel();
        String said = switch (what) {
            case "buy" -> Businesses.buy(level, v.id(), b, p);
            case "invest" -> Businesses.invest(level, v.id(), b, p, coins);
            default -> Businesses.takeDrawings(v.id(), b, p);
        };
        c.getSource().sendSuccess(() -> Component.literal(said), false);
        return 1;
    }

    private static int orderCmd(CommandContext<CommandSourceStack> c) {
        Villages.Village v = near(c);
        if (v == null || !(c.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        ServerLevel level = c.getSource().getLevel();
        Businesses.ensure(level, v.id());
        String id = StringArgumentType.getString(c, "id");
        Business b = id.equals("stall") ? null : Businesses.of(v.id(), id);
        if (b == null && !id.equals("stall")) {
            c.getSource().sendFailure(Component.literal("No business by that name here (or say \"stall\" for your stall)."));
            return 0;
        }
        String item = StringArgumentType.getString(c, "item");
        net.minecraft.world.item.Item it = CostBook.itemOf(item.contains(":") ? item : "minecraft:" + item);
        if (it == null || it == net.minecraft.world.item.Items.AIR) {
            c.getSource().sendFailure(Component.literal("No item called " + item + "."));
            return 0;
        }
        String said = Businesses.playerOrder(level, v.id(), p, b, new ItemStack(it), IntegerArgumentType.getInteger(c, "qty"));
        c.getSource().sendSuccess(() -> Component.literal(said), false);
        return 1;
    }
}
