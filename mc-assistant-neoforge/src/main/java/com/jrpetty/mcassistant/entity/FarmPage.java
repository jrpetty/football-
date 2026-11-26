package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.WorkMath;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [wpb] The page of a player's own farm ("farm"): registering one, its fields (outlined, Show me, add another), selling
 * through its outlets with the price before you confirm, its hands (folk on either pay; players through the jobs board;
 * a manager for when you are away), and its books (revenue by outlet, costs, wages, rates, tax, profit).
 */
public final class FarmPage implements PlayerLife.Page {

    @Nullable
    static PlayerFarms.Farm chosen(ServerPlayer p, Villages.Village v, PlayerLife.Ctx c) {
        List<PlayerFarms.Farm> mine = PlayerFarms.ofOwner(v.id(), p.getUUID());
        for (PlayerFarms.Farm f : mine) if (f.id.equals(c.args.getString("farm"))) return f;
        return mine.isEmpty() ? null : mine.get(0);
    }

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        LifePage page = new LifePage("farm", "Your farm", v == null ? "No town near" : "In " + Villages.name(v.id()) + ": a farm, orchard, ranch, woodlot, mine or fishery of your own",
            p.registryAccess());
        if (v == null) return page;
        PlayerFarms.Farm f = chosen(p, v, c);
        if (f == null || c.args.getBoolean("new")) return registerPage(level, p, v, page, c);
        c.args.putString("farm", f.id);
        if (c.tab.isEmpty()) c.tab = "overview";
        page.tab("overview", "Overview", c.tab.equals("overview")).tab("sell", "Sell", c.tab.equals("sell")).tab("hands", "Hands", c.tab.equals("hands"))
            .tab("books", "Books", c.tab.equals("books"));
        if (!Earnings.data(p).getBoolean("wpb/hint/farm")) {
            page.box("First time here", List.of("Your farm is a business: what it sells goes into its till, it pays land rates weekly and tax on its profit, "
                + "and you take your share as drawings. Only produce grown on its fields sells through it."), 4);
            page.buttons().button("Got it", "hint", null);
        }
        Business b = PlayerFarms.business(f.id);
        switch (c.tab) {
            case "sell" -> sellTab(level, p, f, page, c);
            case "hands" -> handsTab(level, p, f, b, page);
            case "books" -> booksTab(level, p, f, b, page);
            default -> overview(level, p, f, b, page);
        }
        return page;
    }

    LifePage registerPage(ServerLevel level, ServerPlayer p, Villages.Village v, LifePage page, PlayerLife.Ctx c) {
        page.heading("Register a farm of your own");
        page.text("Hoe a field (off the town's fields), stand on it, and register it. It becomes a business: books, land rates ("
            + Math.round(PlayerFarms.RATE_PER_BLOCK_WEEK * 100) + "c a hundred blocks a week), tax on its profit, and outlets to sell through. Buying land is coming with the land office; for now, a field you made counts as yours.");
        page.choice("kind", "Kind", PlayerFarms.KINDS, new String[]{ "Farm (crops)", "Orchard", "Ranch", "Woodlot", "Mine", "Fishery" }, "farm");
        page.field("name", "Its name", "", 32);
        int[] r = PlayerFarms.fieldAround(level, p.blockPosition());
        List<String> checks = new ArrayList<>();
        checks.add(r != null ? "✔ Standing on farmland: a field of " + ((r[2] - r[0] + 1) * (r[3] - r[1] + 1)) + " blocks" : "✘ Not on farmland (for a crop farm: stand on the field)");
        String no = r == null ? "" : PlayerFarms.whyNotField(level, v, r);
        if (r != null) checks.add(no.isEmpty() ? "✔ Nobody else's: not the town's, not another farm's" : "✘ Not yours: " + no);
        checks.add(Government.allowsPrivateFirms(v.id()) ? "✔ Private firms are allowed here" : "✘ The state owns the farms here");
        checks.add(Market.coinsHeld(p) >= PlayerFarms.FEE ? "✔ The fee: " + PlayerFarms.FEE + "c (you carry " + Market.coinsHeld(p) + "c)" : "✘ The fee: " + PlayerFarms.FEE + "c (you carry " + Market.coinsHeld(p) + "c)");
        page.box("Checks", checks, 4);
        page.buttons().button("Register here (" + PlayerFarms.FEE + "c)", "register", null).primary()
            .button("Your life in town", "life", null);
        if (!PlayerFarms.ofOwner(v.id(), p.getUUID()).isEmpty()) page.buttons().button("Back to your farm", "back", null);
        return page;
    }

    void overview(ServerLevel level, ServerPlayer p, PlayerFarms.Farm f, @Nullable Business b, LifePage page) {
        long day = Businesses.today(level);
        Business.Day w = b == null ? new Business.Day() : b.last(day, 7);
        page.box(f.name + " (" + f.kind + ")", List.of("Till: " + (b == null ? "0" : Businesses.pence(b.till)) + ". This week: sold " + Businesses.pence(w.revenue)
                + ", wages " + Businesses.pence(w.wages) + ", rates " + Businesses.pence(w.rent) + ", tax " + Businesses.pence(w.taxes) + ", profit " + Businesses.pence(w.net()) + ".",
            "Owned by " + (b == null ? f.ownerName : b.ownersWords()) + ". " + f.hands.size() + (f.hands.size() == 1 ? " hand" : " hands") + (f.manager != null ? ", a manager" : "") + ".",
            f.paused ? "Paused: you were away with no manager, so the hands stood down. Appoint a manager on Hands to keep it going." : "Working."), f.paused ? 2 : 1);
        page.heading("Its fields (" + f.area() + " blocks)");
        for (int i = 0; i < f.fields.size(); i++) {
            int[] r = f.fields.get(i);
            page.row(new ItemStack(Items.WHEAT), "Field " + (i + 1) + ": " + (r[2] - r[0] + 1) + " by " + (r[3] - r[1] + 1), "From " + r[0] + ", " + r[1] + " to " + r[2] + ", " + r[3], "")
                .button("Show me", "showfield", LifePage.args("i", i));
        }
        page.buttons().button("Add the field I'm standing on", "addfield", null);
        page.heading("Bills");
        page.text("Land rates: " + PlayerFarms.ratesFor(f) + "c a week (agricultural), next on day " + (f.ratesDay + 7) + ". Profit tax: " + TaxBook.profitsPercent(f.town)
            + "% of the week's profit, taken as it is earned.", 4);
        page.buttons().button("Sell", "tab:sell", null).primary().button("Hands", "tab:hands", null).button("Books", "tab:books", null)
            .button("Register another", "new", null);
    }

    void sellTab(ServerLevel level, ServerPlayer p, PlayerFarms.Farm f, LifePage page, PlayerLife.Ctx c) {
        Map<String, Integer> have = PlayerFarms.sellable(p, f);
        page.heading("What the farm has to sell");
        if (have.isEmpty()) {
            page.text("Nothing yet. Harvest your fields: what you bring in is marked as the farm's. Bought or found goods can't be sold through it.", 4);
            return;
        }
        String[] keys = have.keySet().toArray(new String[0]);
        String[] labels = new String[keys.length];
        for (int i = 0; i < keys.length; i++) labels[i] = have.get(keys[i]) + " " + Stockroom.plural(keys[i]).toLowerCase(Locale.ROOT);
        String key = c.args.contains("k") && have.containsKey(c.args.getString("k")) ? c.args.getString("k") : keys[0];
        page.choice("k", "Goods", keys, labels, key);
        page.choice("n", "How many", new String[]{ "8", "16", "32", "64", "all" }, new String[]{ "8", "16", "32", "64", "All" }, c.args.contains("n") ? c.args.getString("n") : "all");
        page.buttons().button("Get prices", "quote", null).primary();
        int n = count(c.args.getString("n"), have.getOrDefault(key, 0));
        page.heading("Outlets for " + n + " " + Stockroom.plural(key).toLowerCase(Locale.ROOT));
        String pending = c.args.getString("pending");
        for (PlayerFarms.Quote q : PlayerFarms.quotes(level, p, f, key, n)) {
            String sub = q.total() > 0 ? WorkMath.coin(q.each()) + " each: " + q.total() + "c for this load" : q.outlet().equals("shop") ? "Into your shop's stock chest: you set the price there" : "";
            if (!q.whyNot().isEmpty()) sub = (sub.isEmpty() ? "" : sub + " · ") + "not now: " + q.whyNot();
            boolean sure = pending.equals(q.outlet());
            page.row(null, q.buyer(), sub, q.total() > 0 ? q.total() + "c" : "")
                .button(sure ? "Confirm: sell for " + q.total() + "c" : "Sell here", "sell", LifePage.args("o", q.outlet(), "k", key, "n", n, "sure", sure),
                    q.whyNot().isEmpty(), q.whyNot());
        }
        page.text("Every sale goes into the farm's till and its books. Nothing moves until you confirm.", 4);
    }

    static int count(String n, int have) {
        if (n.isEmpty() || n.equals("all")) return have;
        try { return Math.min(have, Integer.parseInt(n)); } catch (NumberFormatException e) { return have; }
    }

    void handsTab(ServerLevel level, ServerPlayer p, PlayerFarms.Farm f, @Nullable Business b, LifePage page) {
        page.heading("Its hands");
        if (f.hands.isEmpty()) page.text("Nobody works the farm yet. A hand's morning round brings the ripe crops into the farm's stock.", 4);
        for (Map.Entry<UUID, String[]> e : f.hands.entrySet()) {
            boolean comm = e.getValue()[1].equals("c");
            boolean mgr = e.getKey().equals(f.manager);
            page.row(new ItemStack(Items.IRON_HOE), e.getValue()[0] + (mgr ? " (manager)" : ""), comm ? "On commission: a share of the round's harvest" : "On " + e.getValue()[2] + "c a day", "")
                .button(mgr ? "Not manager" : "Make manager", "manager", LifePage.args("h", e.getKey().toString()))
                .button("Let go", "letgo", LifePage.args("h", e.getKey().toString()));
        }
        page.buttons().button("Take on a folk hand", "hire", null).primary();
        page.text("A folk chooses salary or commission by its character. A manager sells the stock to the stores each morning while you're away; "
            + "with nobody in charge the farm pauses safely instead.", 4);
        page.heading("Hire a player");
        for (PlayerHiring.Post x : PlayerHiring.postsOf(f.town, "farm:" + f.id)) {
            page.row(null, "Advert: " + JobCards.of(x.card).title() + ", " + x.wage + "c a day", x.apps.size() + " applied", "").button("Take it down", "unpost", LifePage.args("id", x.id));
            for (Map.Entry<UUID, String[]> a : x.apps.entrySet()) {
                if (!a.getValue()[2].equals("APPLIED")) continue;
                page.row(null, "  " + a.getValue()[0], "applied " + (a.getValue()[1].equals("c") ? "on commission" : "on salary"), "")
                    .button("Offer the job", "offerjob", LifePage.args("id", x.id, "u", a.getKey().toString())).button("Turn down", "turndown", LifePage.args("id", x.id, "u", a.getKey().toString()));
            }
        }
        page.choice("pw", "Pay", new String[]{ "0", "1", "2" }, new String[]{ "Either", "Salary", "Commission" }, "0");
        page.buttons().button("Post an advert for a farm hand", "post", null);
        for (ServerPlayer h : PlayerHiring.hands(level, "farm:" + f.id)) {
            page.row(null, h.getGameProfile().getName() + " (a player hand)", PlayerJobs.line(h), "")
                .button("Warn", "warn", LifePage.args("u", h.getUUID().toString())).button("Let go", "dismiss", LifePage.args("u", h.getUUID().toString()));
        }
    }

    void booksTab(ServerLevel level, ServerPlayer p, PlayerFarms.Farm f, @Nullable Business b, LifePage page) {
        if (b == null) return;
        long day = Businesses.today(level);
        Business.Day w = b.last(day, 7), all = b.allTime;
        page.heading("Revenue by outlet");
        for (String o : new String[]{ "stores", "firm", "contract" }) {
            page.bar(o.equals("stores") ? "The town stores" : o.equals("firm") ? "Firms" : "Contracts", all.revenue <= 0 ? 0 : f.outletAll.getOrDefault(o, 0L) / (double) Math.max(1, all.revenue),
                "week " + Businesses.pence(f.outletWeek.getOrDefault(o, 0L)) + " · all " + Businesses.pence(f.outletAll.getOrDefault(o, 0L)));
        }
        page.heading("This week, and all told");
        page.text("Revenue " + Businesses.pence(w.revenue) + " · all " + Businesses.pence(all.revenue));
        page.text("Costs " + Businesses.pence(w.cogs + w.other) + " · all " + Businesses.pence(all.cogs + all.other));
        page.text("Wages " + Businesses.pence(w.wages) + " · all " + Businesses.pence(all.wages));
        page.text("Land rates " + Businesses.pence(w.rent) + " · all " + Businesses.pence(all.rent));
        page.text("Tax " + Businesses.pence(w.taxes) + " · all " + Businesses.pence(all.taxes));
        page.text("Profit " + Businesses.pence(w.net()) + " · all " + Businesses.pence(all.net()), w.net() >= 0 ? 1 : 3);
        page.text("Till " + Businesses.pence(b.till) + ".", 0);
        page.buttons().button("Take my drawings", "draw", null, b.till >= 300, "Leave a little in the till: it needs 3c or more").button("Open its full books", "books", null);
    }

    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return "!No town here.";
        CompoundTag f0 = args.getCompound("f");
        if (action.startsWith("tab:")) { c.tab = action.substring(4); return ""; }
        switch (action) {
            case "hint" -> { Earnings.data(p).putBoolean("wpb/hint/farm", true); return ""; }
            case "life" -> { PlayerLife.open(p, "life", null); return "#open:"; }
            case "new" -> { c.args.putBoolean("new", true); return ""; }
            case "back" -> { c.args.remove("new"); return ""; }
            case "register" -> {
                String said = PlayerFarms.register(level, p, v, f0.getString("kind").isEmpty() ? "farm" : f0.getString("kind"), f0.getString("name"), true);
                if (!said.startsWith("!")) {
                    c.args.remove("new");
                    List<PlayerFarms.Farm> mine = PlayerFarms.ofOwner(v.id(), p.getUUID());
                    PlayerFarms.Farm made = mine.get(mine.size() - 1);
                    c.args.putString("farm", made.id);
                    c.tab = "overview";
                    WorkWay.showMe(p, made.centre(), made.name);
                }
                return said;
            }
            default -> { }
        }
        PlayerFarms.Farm f = chosen(p, v, c);
        if (f == null) return "!You have no farm here.";
        Business b = PlayerFarms.business(f.id);
        switch (action) {
            case "showfield" -> {
                int i = args.getInt("i");
                if (i < 0 || i >= f.fields.size()) return "!No such field.";
                int[] r = f.fields.get(i);
                WorkWay.showMe(p, new BlockPos((r[0] + r[2]) / 2, r[4], (r[1] + r[3]) / 2), f.name + ", field " + (i + 1));
                return "#close";
            }
            case "addfield" -> { return PlayerFarms.addField(level, p, f); }
            case "quote" -> {
                if (f0.contains("k")) c.args.putString("k", f0.getString("k"));
                if (f0.contains("n")) c.args.putString("n", f0.getString("n"));
                c.args.remove("pending");
                return "Prices for this load are below. Nothing is sold until you confirm.";
            }
            case "sell" -> {
                String o = args.getString("o");
                if (!args.getBoolean("sure")) { c.args.putString("pending", o); return "Press Confirm to sell, or pick another outlet."; }
                c.args.remove("pending");
                return PlayerFarms.sell(level, p, f, o, args.getString("k"), args.getInt("n"));
            }
            case "hire" -> { return PlayerFarms.hire(level, f); }
            case "letgo" -> { return PlayerFarms.letGo(level, f, UUID.fromString(args.getString("h"))); }
            case "manager" -> {
                UUID h = UUID.fromString(args.getString("h"));
                f.manager = h.equals(f.manager) ? null : h;
                PlayerFarms.dirty();
                return f.manager == null ? "No manager now: with you away, the farm pauses." : f.hands.get(h)[0] + " manages " + f.name + " while you're away: the round, the wages, the stock sold to the stores.";
            }
            case "post" -> {
                JobCards.Card card = JobCards.of(f.kind.equals("mine") ? "MINE" : f.kind.equals("fishery") ? "FISH" : f.kind.equals("woodlot") ? "WOOD" : "FARM");
                return PlayerHiring.post(level, p, f.town, "farm:" + f.id, f.name, card.key(), PayTerms.goingWage(level, f.town, card, 0), ShopPage.parse(f0.getString("pw"), 0));
            }
            case "unpost" -> { return PlayerHiring.withdraw(p, args.getInt("id")); }
            case "offerjob" -> { return PlayerHiring.answer(p, args.getInt("id"), UUID.fromString(args.getString("u")), true); }
            case "turndown" -> { return PlayerHiring.answer(p, args.getInt("id"), UUID.fromString(args.getString("u")), false); }
            case "warn" -> { return PlayerHiring.review(level, p, UUID.fromString(args.getString("u")), false, "a word from the owner about the work"); }
            case "dismiss" -> { return PlayerHiring.review(level, p, UUID.fromString(args.getString("u")), true, "the owner let you go"); }
            case "draw" -> { return b == null ? "!No books." : Businesses.takeDrawings(f.town, b, p); }
            case "books" -> { if (b != null) BusinessBooks.show(p, f.town, b); return "#open:"; }
            default -> { return null; }
        }
    }
}
