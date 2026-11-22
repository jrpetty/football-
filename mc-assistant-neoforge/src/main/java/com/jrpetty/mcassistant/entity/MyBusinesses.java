package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [player15] Your businesses, everything the firm and business commands did, by buttons: its summary and premises; its
 * hands (take on the best the town can spare, post an opening at a wage, take the best who applied); its stock (order its
 * makings from the market); its prices (its markup, beside the going price); its money (invest, take the drawings); and
 * its shares on the bourse, or winding it up. Each button calls the same code the command did (Enterprise, LabourMarket,
 * Businesses, Bourse), so the commands still work as they always did.
 */
public final class MyBusinesses implements PlayerLife.Page {

    static List<Business> mine(java.util.UUID village, java.util.UUID player) {
        List<Business> out = new ArrayList<>();
        for (Business b : Enterprise.open(village)) if (b.ownedBy(player)) out.add(b);
        return out;
    }

    @Nullable
    static Business chosen(ServerPlayer p, Villages.Village v, PlayerLife.Ctx c) {
        List<Business> mine = mine(v.id(), p.getUUID());
        for (Business b : mine) if (b.id.equals(c.args.getString("firm"))) return b;
        return mine.isEmpty() ? null : mine.get(0);
    }

    static final int[] AMOUNTS = { 5, 10, 20, 50, 100 }, QTY = { 8, 16, 32, 64 };

    static String[] strings(int[] a) {
        String[] out = new String[a.length];
        for (int i = 0; i < a.length; i++) out[i] = Integer.toString(a[i]);
        return out;
    }

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        LifePage page = new LifePage("firm", "Your businesses", v == null ? "No town near" : "In " + Villages.name(v.id()), p.registryAccess());
        if (v == null) return page;
        Business b = chosen(p, v, c);
        if (b == null) {
            page.text("You hold no business in " + Villages.name(v.id()) + " yet.", 4);
            page.buttons().button("Start a business", "start", null).primary();
            return page;
        }
        c.args.putString("firm", b.id);
        List<Business> mine = mine(v.id(), p.getUUID());
        if (mine.size() > 1) {
            LifePage.Row r = page.buttons();
            for (Business x : mine) r.button((x == b ? "✔ " : "") + x.name, "pick", LifePage.args("firm", x.id));
        }
        if (c.tab.isEmpty()) c.tab = "summary";
        page.tab("summary", "Summary", c.tab.equals("summary")).tab("hands", "Hands", c.tab.equals("hands")).tab("stock", "Stock", c.tab.equals("stock"))
            .tab("prices", "Prices", c.tab.equals("prices")).tab("money", "Money", c.tab.equals("money")).tab("more", "Shares & closing", c.tab.equals("more"));
        BusinessTypes.BusinessType t = Enterprise.kindOf(v.id(), b);
        switch (c.tab) {
            case "hands" -> hands(level, v, b, t, page);
            case "stock" -> stock(level, v, b, t, page);
            case "prices" -> prices(level, v, b, t, page);
            case "money" -> money(p, v, b, page);
            case "more" -> more(level, p, v, b, page, c);
            default -> summary(level, p, v, b, t, page);
        }
        return page;
    }

    private void summary(ServerLevel level, ServerPlayer p, Villages.Village v, Business b, @Nullable BusinessTypes.BusinessType t, LifePage page) {
        page.heading(b.name);
        page.text(Enterprise.line(level, v.id(), b));
        String warn = BizGuide.firmWarning(v.id(), b);                      // [biz25] what is wrong, and how long it has
        if (!warn.isEmpty()) page.text(warn, 3);
        else if (b.till < 100 && Businesses.today(level) - b.opened < BizGuide.FIRM_GRACE) page.text("Its till is empty: put coin in it (Money) to pay for its stock and wages. A new firm has "
            + BizGuide.FIRM_GRACE + " days before its creditors can take it to court.", 2);
        page.row(new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get()), "Its till: " + Businesses.pence(b.till), "Owned by " + b.ownersWords(), "")
            .button("Open its books", "books", null);
        page.heading("Its premises");
        PlayerShops.Shop shop = PlayerShops.of(v.id(), p.getUUID());
        if (shop != null && b.id.equals(shop.firm)) {
            List<ShopNeeds.Need> needs = ShopNeeds.check(level, v, PlayerShops.survey(level, shop), t == null ? ShopNeeds.Kind.GENERAL : ShopNeeds.Kind.forBusiness(t.key()), shop.back);
            page.text("Your shop, " + shop.name + ": " + (ShopNeeds.ready(needs) ? "it has everything its trade needs." : "still " + ShopNeeds.missing(needs) + "."));
            for (ShopNeeds.Need n : needs) if (!n.ok()) page.text("  " + n.line() + " — " + n.fix(), 2);
        } else if (t != null) {
            List<BuildDesk.Status> going = BuildDesk.mine(level, v.id(), p.getUUID());
            page.text(!going.isEmpty() ? "Its own building, going up (it trades from the square till it stands)."
                : t.stall() ? "A stall on the square, its own." : "A room the town lets it.");
        }
        for (BuildDesk.Status s : BuildDesk.mine(level, v.id(), p.getUUID())) page.bar(s.title(), s.percent() / 100.0, s.done() ? "finished" : s.percent() + "%" + (s.waitingOn().isEmpty() ? "" : ", waiting for " + s.waitingOn()));
        page.heading("Next");
        page.buttons().button("Hands", "tab:hands", null).button("Stock", "tab:stock", null).button("Prices", "tab:prices", null).button("Money", "tab:money", null);
    }

    private void hands(ServerLevel level, Villages.Village v, Business b, @Nullable BusinessTypes.BusinessType t, LifePage page) {
        page.heading("Its hands");
        List<VillageFolkEntity> hs = Enterprise.hands(v.id(), b);
        if (hs.isEmpty()) page.text("Nobody works there yet.", 2);
        for (VillageFolkEntity f : hs) page.row(null, f.displayNameCap(), "paid " + Wealth.wage(f) + " a day", "");
        page.heading("Take one on");
        page.buttons().button("The best the town can spare, now", "hire", null).primary();
        page.choice("wage", "An opening at", new String[]{ "0", "10", "25" }, new String[]{ "Its going offer", "A tenth over", "A quarter over" }, "0");
        page.buttons().button("Post an opening", "post", null, LabourMarket.live(v.id()), "The town has no labour market yet (its market isn't open)")
            .button("Take the best who applied", "hirebest", null, LabourMarket.live(v.id()), "The town has no labour market yet");
    }

    private void stock(ServerLevel level, Villages.Village v, Business b, @Nullable BusinessTypes.BusinessType t, LifePage page) {
        page.heading("Its stock, and its makings from the market");
        page.choice("qty", "Order", strings(QTY), strings(QTY), "16");
        if (t == null) return;
        List<String> keys = new ArrayList<>(t.wares());
        for (String k : keys) {
            ItemStack one = Stockroom.sampleOf(k);
            if (one.isEmpty()) continue;
            Business.Lot lot = b.stock.get(k);
            page.row(one, Stockroom.plural(k), "in stock: " + (lot == null ? 0 : lot.qty), "").button("Order", "order", LifePage.args("k", k));
        }
    }

    private void prices(ServerLevel level, Villages.Village v, Business b, @Nullable BusinessTypes.BusinessType t, LifePage page) {
        page.heading("Its markup: its costs and " + Businesses.pct(b.markup));
        page.buttons().button("-5%", "markup", LifePage.args("d", -5)).button("+5%", "markup", LifePage.args("d", 5));
        page.text("Never over three fifths above the going price, nor under two fifths of it.", 4);
        if (t == null) return;
        for (String k : t.wares()) {
            ItemStack one = Stockroom.sampleOf(k);
            if (one.isEmpty()) continue;
            double going = Market.askEach(level, v.id(), one), price = Sellers.priceOf(level, v.id(), b, one);
            page.row(one, Stockroom.plural(k), String.format(Locale.ROOT, "yours %.2fc, the going price %.2fc", price, going), ShopPage.judge((int) Math.round(price * 100), (int) Math.round(Math.max(0.01, going) * 100)));
        }
    }

    private void money(ServerPlayer p, Villages.Village v, Business b, LifePage page) {
        page.heading("Its money");
        page.text("Its till " + Businesses.pence(b.till) + ". You carry " + Market.coinsHeld(p) + " coins.");
        page.choice("coins", "Put in", strings(AMOUNTS), strings(AMOUNTS), "20");
        page.buttons().button("Invest", "invest", null).button("Take my drawings", "draw", null);
    }

    private void more(ServerLevel level, ServerPlayer p, Villages.Village v, Business b, LifePage page, PlayerLife.Ctx c) {
        page.heading("Its shares on the bourse");
        page.text(Bourse.line(level, v.id()), 4);
        page.choice("n", "Sell", new String[]{ "1", "5", "10", "25" }, new String[]{ "1 share", "5 shares", "10 shares", "25 shares" }, "5");
        page.buttons().button("Sell at the going price", "sell", null);
        page.heading("Wind it up");
        page.text("Its stock and kit sold, its debts paid, what is left to its owners. It can't be undone.", 2);
        boolean sure = c.args.getBoolean("sure");
        page.buttons().button(sure ? "Yes, wind it up" : "Wind it up", "windup", LifePage.args("sure", sure));
    }

    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return "!No town here.";
        if (action.equals("start")) { PlayerLife.open(p, "start", null); return "#open:"; }
        if (action.startsWith("tab:")) { c.tab = action.substring(4); return ""; }
        if (action.equals("pick")) { c.args.putString("firm", args.getString("firm")); return ""; }
        Business b = chosen(p, v, c);
        if (b == null) return "!You hold no business here.";
        CompoundTag f = args.getCompound("f");
        switch (action) {
            case "books" -> { BusinessBooks.show(p, v.id(), b); return "#open:"; }
            case "hire" -> {
                VillageFolkEntity h = Enterprise.hireForTests(level, v, b);
                return h == null ? "!Nobody the town can spare will take the work just now: post an opening." : h.displayNameCap() + " is taken on at " + b.name
                    + ", paid " + Wealth.wage(h) + " a day out of its till.";
            }
            case "post" -> {
                BusinessTypes.BusinessType t = Enterprise.kindOf(v.id(), b);
                int pct = ShopPage.parse(f.getString("wage"), 0);
                int coins = pct == 0 || t == null ? 0 : (int) Math.round(Math.max(1, Enterprise.wageOf(t.work(), v.id())) * (1 + pct / 100.0));
                return clean(LabourMarket.post(level, v.id(), b, coins));
            }
            case "hirebest" -> { return clean(LabourMarket.hireBest(level, v.id(), b)); }
            case "order" -> {
                ItemStack one = Stockroom.sampleOf(args.getString("k"));
                if (one.isEmpty()) return "!No such ware.";
                return Businesses.playerOrder(level, v.id(), p, b, one, ShopPage.parse(f.getString("qty"), 16));
            }
            case "markup" -> {
                double was = b.markup;
                b.markup = Math.max(0.05, Math.min(2.0, Math.round(b.markup * 100 + args.getInt("d")) / 100.0));
                b.note(Businesses.today(level), "markup " + Businesses.pct(was) + " -> " + Businesses.pct(b.markup) + ", set by its owner");
                Businesses.dirty();
                return b.name + " now asks its costs and " + Businesses.pct(b.markup) + ".";
            }
            case "invest" -> { return Businesses.invest(level, v.id(), b, p, ShopPage.parse(f.getString("coins"), 20)); }
            case "draw" -> { return Businesses.takeDrawings(v.id(), b, p); }
            case "sell" -> { return Bourse.playerSells(level, p, v.id(), b.id, ShopPage.parse(f.getString("n"), 5), 0); }
            case "windup" -> {
                if (!args.getBoolean("sure")) { c.args.putBoolean("sure", true); return "!Press again to wind " + b.name + " up for good."; }
                c.args.remove("sure");
                Enterprise.windUp(level, v.id(), b, Businesses.today(level), "wound up by its owner");
                PlayerShops.Shop s = PlayerShops.of(v.id(), p.getUUID());
                if (s != null && b.id.equals(s.firm)) { s.firm = null; PlayerShops.save(s); }
                return b.name + " is wound up.";
            }
            default -> { return null; }
        }
    }

    /** The commands' words, without the commands in them. */
    static String clean(String s) {
        return s.replaceAll(" ?(Take the best with|post one with) /village [^.]*\\.?", "").replace(": .", ".").trim();
    }
}
