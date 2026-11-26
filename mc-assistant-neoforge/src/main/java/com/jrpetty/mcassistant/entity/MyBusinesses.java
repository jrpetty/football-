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
        for (Business x : Enterprise.all(v.id())) {                         // [biz30] a shut firm's coin still owed to you
            long due = x.open ? 0 : x.ownersDue.getOrDefault(p.getUUID(), 0L);
            if (due >= 100) page.row(new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get()), x.name + " (closed)",
                Businesses.pence(due) + " of it is yours", "").button("Take it", "drawclosed", LifePage.args("firm", x.id));
        }
        forSaleAndOffers(level, p, v, page);                                 // [wpb] firms for sale, shares offered to you
        for (PlayerFarms.Farm fm : PlayerFarms.ofOwner(v.id(), p.getUUID())) page.row(new ItemStack(Items.WHEAT), fm.name + " (your " + fm.kind + ")",
            fm.area() + " blocks · " + fm.hands.size() + " hands" + (fm.paused ? " · paused" : ""), "").button("Run it", "farmpage", LifePage.args("farm", fm.id));
        if (b == null) {
            page.text("You hold no chartered business in " + Villages.name(v.id()) + " yet.", 4);
            for (PlayerShops.Shop sh : PlayerShops.ofAll(v.id(), p.getUUID())) {   // [biz30] its shops, though
                page.row(new ItemStack(com.jrpetty.mcassistant.McAssistantMod.SHOP_COUNTER_ITEM.get()), sh.name + " (your shop)",
                        "till " + PlayerLife.coins(sh.till) + " · " + PlayerShops.stateWords(p.serverLevel(), sh), "")
                    .button("Open it", "shopof", LifePage.args("key", sh.key()))
                    .button("Data", "shopdata", LifePage.args("key", sh.key()));       // [biz30]
            }
            // [ui29] what one is and how it starts, not just a button
            page.text("A shop of your own is the easiest: a room with a Shop Counter, stock in a chest, and folk come to buy. "
                + "Or charter a firm: it hires hands of its own to make and sell what the town is short of. Either starts here, in three steps.", 0);
            page.buttons().button("Start a business", "start", null).primary().button("Open a shop", "shop", null);
            return page;
        }
        c.args.putString("firm", b.id);
        List<Business> mine = mine(v.id(), p.getUUID());
        // [biz30] Every business of its here, a row each: its till, this week's profit, its hands; run it or open its books.
        List<PlayerShops.Shop> shops = PlayerShops.ofAll(v.id(), p.getUUID());
        if (c.tab.isEmpty() || c.tab.equals("summary")) {
            long day = Businesses.today(level);
            page.heading("All your businesses in " + Villages.name(v.id()) + " (" + (mine.size() + shops.size()) + ")");
            for (Business x : mine) {
                BusinessTypes.BusinessType xt = Enterprise.kindOf(v.id(), x);
                page.row(xt == null ? null : StartBusiness.icon(xt), (x == b ? "✔ " : "") + x.name,
                        "till " + Businesses.pence(x.till) + " · this week " + Businesses.pence(x.last(day, 7).net()) + " · " + Enterprise.hands(v.id(), x).size() + " hands", "")
                    .button("Run it", "pick", LifePage.args("firm", x.id), x != b, "Shown below").button("Books", "bookof", LifePage.args("firm", x.id))
                    .button("Data", "dataof", LifePage.args("firm", x.id));            // [biz30] its sales and costs dashboard
            }
            for (PlayerShops.Shop sh : shops) {
                page.row(new ItemStack(com.jrpetty.mcassistant.McAssistantMod.SHOP_COUNTER_ITEM.get()), sh.name + " (your shop)",
                        "till " + PlayerLife.coins(sh.till) + " · " + PlayerShops.stateWords(level, sh) + (sh.keeper != null ? " · keeper " + sh.keeperName : " · no keeper"), "")
                    .button("Open it", "shopof", LifePage.args("key", sh.key()))
                    .button("Data", "shopdata", LifePage.args("key", sh.key()));       // [biz30]
            }
        }
        page.buttons().button("Start another business", "start", null).button("Contracts with players", "contracts", null);   // [wpb]
        if (c.tab.isEmpty()) c.tab = "summary";
        page.tab("summary", "Summary", c.tab.equals("summary")).tab("hands", "Staff", c.tab.equals("hands")).tab("stock", "Stock", c.tab.equals("stock"))
            .tab("prices", "Prices", c.tab.equals("prices")).tab("away", "Deputy", c.tab.equals("away")).tab("money", "Money", c.tab.equals("money"))
            .tab("offers", "Offers", c.tab.equals("offers")).tab("history", "History", c.tab.equals("history")).tab("more", "Owners", c.tab.equals("more"));
        BusinessTypes.BusinessType t = Enterprise.kindOf(v.id(), b);
        switch (c.tab) {
            case "hands" -> hands(level, v, b, t, page, c);
            case "away" -> DeputyPage.build(level, p, v, b, page, c);       // [wpb] the deputy's authority, its standing orders, its record
            case "offers" -> OfferPage.incoming(level, p, v, b, page);      // [wpb] offers for it: accept, refuse, counter
            case "history" -> CoreHub.history(level, p, v, b, page);        // [wpb] its charts, best sellers, customers, staff, timeline
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
        CoreHub.top(level, p, v, b, page, PlayerLife.ctx(p));              // [wpb] the status line, the first-time tour
        DeputyPage.summaryTop(level, p, v, b, page);                        // [wpb] who is in charge, and the report waiting since you were away
        rescueBanner(level, p, v, b, page);                                 // [wpb] before the court: what saves it
        plCard(level, v, b, page);                                          // [wpb] the profit and loss at a glance
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
        for (BuildDesk.Status s : BuildDesk.mine(level, v.id(), p.getUUID())) page.bar(s.title(), s.percent() / 100.0, s.done() ? "finished" : s.percent() + "%" + (s.waitingOn().isEmpty() ? "" : ", " + s.waitingOn()));   // [biz25] not "waiting for waiting for"
        page.heading("Next");
        page.buttons().button("Hands", "tab:hands", null).button("Stock", "tab:stock", null).button("Prices", "tab:prices", null).button("Money", "tab:money", null);
    }

    private void hands(ServerLevel level, Villages.Village v, Business b, @Nullable BusinessTypes.BusinessType t, LifePage page, PlayerLife.Ctx ctx) {
        ServerPlayer p = null;
        for (ServerPlayer x : level.getServer().getPlayerList().getPlayers()) if (b.ownedBy(x.getUUID()) && PlayerLife.ctx(x).page.equals("firm")) p = x;
        boolean decide = p == null || Partners.canDecide(v.id(), b, p.getUUID());
        String no = decide ? "" : Partners.whyNot(v.id(), b);
        page.heading("Its hands");
        List<VillageFolkEntity> hs = Enterprise.hands(v.id(), b);
        if (hs.isEmpty()) page.text("Nobody works there yet.", 2);
        CompoundTag r = Enterprise.rec(v.id(), b.id);
        String pending = r == null ? "" : r.getString("wpbLetGo");
        String appointing = ctx.args.getString("appoint");
        for (VillageFolkEntity f : hs) {                                    // [wpb] each hand: its pay, raise, cut, deputy, let go
            LabourBook.Contract c = LabourMarket.contract(f);
            boolean mgr = r != null && r.hasUUID("manager") && r.getUUID("manager").equals(f.getUUID());
            boolean sure = pending.equals(f.getUUID().toString());
            int sev = severance(f);
            page.row(null, f.displayNameCap() + (mgr ? " (" + Deputy.title(r).toLowerCase(Locale.ROOT) + (Deputy.on(r) ? "" : ", authority off") + ")" : ""),
                    "paid " + Wealth.wage(f) + "c a day · level " + f.tradeLevel(t == null ? f.stationTask() : t.work())
                    + " at the trade" + (c == null ? "" : " · since day " + c.since) + " · " + String.join(", ", DeputyPage.traits(f)), "")
                .button("Raise", "raise", LifePage.args("h", f.getUUID().toString()), decide && c != null, c == null ? "Not on the town's books" : no)
                .button("Cut", "cut", LifePage.args("h", f.getUUID().toString()), decide && c != null, c == null ? "Not on the town's books" : no)
                .button(mgr ? "Remove as deputy" : "Set as deputy manager", mgr ? "undeputy" : "deputy", LifePage.args("h", f.getUUID().toString()), decide, no)
                .button("Show me", "showme", LifePage.args("h", f.getUUID().toString()))
                .button(sure ? "Confirm: let go (" + sev + "c)" : "Let go", "letgo", LifePage.args("h", f.getUUID().toString(), "sure", sure), decide, no);
            if (appointing.equals(f.getUUID().toString()) && !mgr) DeputyPage.appointBox(f, r, page);
        }
        page.text("A raise or a cut is a tenth of its pay; a cut stings, and a hand that feels it may look elsewhere. Letting a hand go pays three days' wages as severance, out of the till."
            + " A deputy manager covers for you while you're away, within your standing orders and as their character allows (Deputy tab). With none, the staff carry on with what's in stock.", 4);
        page.heading("Take one on");
        page.buttons().button("The best the town can spare, now", "hire", null, decide, no).primary();
        page.choice("wage", "An opening at", new String[]{ "0", "10", "25" }, new String[]{ "Its going offer", "A tenth over", "A quarter over" }, "0");
        page.buttons().button("Post an opening", "post", null, LabourMarket.live(v.id()) && decide, decide ? "The town has no labour market yet (its market isn't open)" : no)
            .button("Take the best who applied", "hirebest", null, LabourMarket.live(v.id()) && decide, decide ? "The town has no labour market yet" : no);
        page.heading("Hire a player");                                     // [wpb] on the same jobs board
        for (PlayerHiring.Post x : PlayerHiring.postsOf(v.id(), "firm:" + b.id)) {
            page.row(null, "Advert: " + JobCards.of(x.card).title() + ", " + x.wage + "c a day", x.apps.size() + " applied", "").button("Take it down", "unpost", LifePage.args("id", x.id));
            for (java.util.Map.Entry<java.util.UUID, String[]> a : x.apps.entrySet()) {
                if (!a.getValue()[2].equals("APPLIED")) continue;
                page.row(null, "  " + a.getValue()[0], "applied " + (a.getValue()[1].equals("c") ? "on commission" : "on salary"), "")
                    .button("Offer the job", "offerjob", LifePage.args("id", x.id, "u", a.getKey().toString())).button("Turn down", "turndown", LifePage.args("id", x.id, "u", a.getKey().toString()));
            }
        }
        if (t != null) {
            JobCards.Card card = JobCards.of(t.work().name());
            page.choice("pw", "Pay", new String[]{ "0", "1", "2" }, new String[]{ "Either", "Salary", "Commission" }, "0");
            page.buttons().button("Post an advert for a " + (card == null ? "hand" : card.title().toLowerCase(Locale.ROOT)), "postplayer", null, card != null && decide,
                card == null ? "No player job for this trade" : no);
        }
        for (ServerPlayer h : PlayerHiring.hands(level, "firm:" + b.id)) {
            page.row(null, h.getGameProfile().getName() + " (a player hand)", PlayerJobs.line(h), "")
                .button("Warn", "warn", LifePage.args("u", h.getUUID().toString()), decide, no).button("Let go", "dismiss", LifePage.args("u", h.getUUID().toString()), decide, no);
        }
    }

    /** Three days' wages, the severance for a hand let go. */
    static int severance(VillageFolkEntity f) {
        return 3 * Math.max(1, Wealth.wage(f));
    }

    private void stock(ServerLevel level, Villages.Village v, Business b, @Nullable BusinessTypes.BusinessType t, LifePage page) {
        CoreHub.suppliers(level, v, b, page);                               // [wpb] low stock, its orders and suppliers, where its stock is kept
        page.heading("Its stock, and its makings from the market");
        page.choice("qty", "Order", strings(QTY), strings(QTY), "16");
        if (t == null) return;
        List<String> keys = new ArrayList<>(t.wares());
        for (String k : keys) {
            ItemStack one = Stockroom.sampleOf(k);
            if (one.isEmpty()) continue;
            Business.Lot lot = b.stock.get(k);
            CompoundTag r = Enterprise.rec(v.id(), b.id);                    // [wpb] its shelf target, the firm's or the owner's own
            int target = r == null ? 0 : r.getCompound("stockTarget").contains(k) ? r.getCompound("stockTarget").getInt(k) : r.getCompound("targets").getInt(k);
            boolean own = r != null && r.getCompound("stockTarget").contains(k);
            long have = lot == null ? 0 : lot.qty;
            page.row(one, Stockroom.plural(k) + (target > 0 && have < target / 2 ? "  (low)" : ""), "in stock: " + have + " · keeps " + target + (own ? " (your target)" : " (its own reckoning)"), "")
                .button("Order", "order", LifePage.args("k", k)).button("-8", "target", LifePage.args("k", k, "d", -8)).button("+8", "target", LifePage.args("k", k, "d", 8))
                .button("Auto", "target", LifePage.args("k", k, "d", 0), own, "It keeps its own reckoning already");
        }
    }

    private void prices(ServerLevel level, Villages.Village v, Business b, @Nullable BusinessTypes.BusinessType t, LifePage page) {
        CoreHub.decisions(level, v, b, page);                               // [wpb] hours and days, a discount day, quality, an advert, premises, credit
        page.heading("Its markup: its costs and " + Businesses.pct(b.markup));
        page.buttons().button("-5%", "markup", LifePage.args("d", -5)).button("+5%", "markup", LifePage.args("d", 5));
        page.text("Never over three fifths above the going price, nor under two fifths of it.", 4);
        if (t == null) return;
        for (String k : t.wares()) {
            ItemStack one = Stockroom.sampleOf(k);
            if (one.isEmpty()) continue;
            double going = Market.askEach(level, v.id(), one), price = Sellers.priceOf(level, v.id(), b, one);
            double mk = wareMarkup(v.id(), b, one);                         // [wpb] its own markup, or the firm's
            page.row(one, Stockroom.plural(k), String.format(Locale.ROOT, "yours %.2fc, the going price %.2fc; markup %s%s", price, going, Businesses.pct(mk),
                    hasWareMarkup(v.id(), b, k) ? " (its own)" : ""), ShopPage.judge((int) Math.round(price * 100), (int) Math.round(Math.max(0.01, going) * 100)))
                .button("-5%", "waremk", LifePage.args("k", k, "d", -5)).button("+5%", "waremk", LifePage.args("k", k, "d", 5))
                .button("Firm's", "waremk", LifePage.args("k", k, "d", 0), hasWareMarkup(v.id(), b, k), "It uses the firm's markup already");
        }
    }

    private void money(ServerPlayer p, Villages.Village v, Business b, LifePage page) {
        page.heading("Its money");
        page.text("Its till " + Businesses.pence(b.till) + ". You carry " + Market.coinsHeld(p) + " coins.");
        page.choice("coins", "Put in", strings(AMOUNTS), strings(AMOUNTS), "20");
        page.buttons().button("Invest", "invest", null).button("Take my drawings", "draw", null);
    }

    private void more(ServerLevel level, ServerPlayer p, Villages.Village v, Business b, LifePage page, PlayerLife.Ctx c) {
        boolean decide = Partners.canDecide(v.id(), b, p.getUUID());
        String no = decide ? "" : Partners.whyNot(v.id(), b);
        // [wpb] Partners: who owns what, the split, who decides; a share offered to another player.
        page.heading("Its owners");
        for (Business.Stake s : b.owners) page.text(s.name() + ": " + s.parts() * 100 / Business.WHOLE + "%" + (s.holder() == Business.Holder.PLAYER ? "" : s.holder() == Business.Holder.FOLK ? " (folk)" : " (the town)"));
        page.text(Partners.whoDecides(v.id(), b) + ". Profit is split by share.", 4);
        List<ServerPlayer> others = new ArrayList<>();
        for (ServerPlayer o : level.getServer().getPlayerList().getPlayers()) if (o != p && PlayerLife.here(o) != null && PlayerLife.here(o).id().equals(v.id())) others.add(o);
        if (!others.isEmpty()) {
            String[] ids = new String[others.size()], names = new String[others.size()];
            for (int i = 0; i < others.size(); i++) { ids[i] = others.get(i).getUUID().toString(); names[i] = others.get(i).getGameProfile().getName(); }
            page.choice("partner", "Offer to", ids, names, ids[0]);
            page.choice("share", "A share of", new String[]{ "1000", "2500", "5000" }, new String[]{ "10%", "25%", "50%" }, "2500");
            page.choice("sprice", "For", new String[]{ "5", "10", "25", "50", "100", "250" }, new String[]{ "5c", "10c", "25c", "50c", "100c", "250c" }, "25");
            page.buttons().button("Offer the share", "share", null);
        } else page.text("No other player is in town to offer a share to.", 4);
        boolean multi = b.owners.size() > 1;
        page.buttons().button("Pay out the profit by share", "payout", null, decide && b.till >= Partners.RESERVE + 100, decide ? "The till holds no profit over its working reserve (3c)" : no)
            .button("Take my drawings", "draw", null);
        if (multi) {
            List<String> ids = new ArrayList<>(), names = new ArrayList<>();
            for (Business.Stake s : b.owners) if (s.holder() == Business.Holder.PLAYER && s.who() != null) { ids.add(s.who().toString()); names.add(s.name()); }
            if (!ids.isEmpty()) {
                page.choice("managing", "Managing partner", ids.toArray(new String[0]), names.toArray(new String[0]), ids.get(0));
                page.buttons().button("Name the managing partner", "managing", null, Partners.partsOf(b, p.getUUID()) * 2 >= Business.WHOLE, "Only an owner of half or more names one");
            }
        }
        // [wpb] Selling the whole firm.
        page.heading("Sell the whole firm");
        CompoundTag sale = FirmSale.sale(v.id(), b);
        long worth = FirmSale.worth(v.id(), b);
        if (sale == null) {
            page.text("Worth about " + Businesses.pence(worth) + " (its stock, till and kit, less what it owes). List it, and offers come in over the next days; you accept, refuse or counter each.", 4);
            page.choice("ask", "Asking", new String[]{ "100", "110", "125", "150" }, new String[]{ "Worth", "+10%", "+25%", "+50%" }, "110");
            page.buttons().button("List it for sale", "list", null, decide, no);
        } else {
            page.text("Listed at " + Businesses.pence(sale.getLong("ask")) + " since day " + (sale.getLong("listed") + 1) + " (worth " + Businesses.pence(worth) + ").", 1);
            int n = BusinessOffers.forFirm(v.id(), b.id).size();
            page.text(n == 0 ? "No offers yet. Buyers, player and folk, make theirs over the next days; each shows on the Offers tab." : n + (n == 1 ? " offer" : " offers") + " waiting on the Offers tab.", 4);
            page.buttons().button("The offers", "tab:offers", null);
            page.buttons().button("Take it off the market", "unlist", null, decide, no);
        }
        // [wpb] A branch of your own.
        page.heading("Open a branch");
        String branchNo = branchWhyNot(level, v, b, p);
        page.text(branchNo.isEmpty() ? "It's done well enough to open another of the same trade: half its till (up to 30c) goes to start it." : "Not yet: " + branchNo + ".", branchNo.isEmpty() ? 1 : 4);
        page.buttons().button("Open a branch", "branch", null, branchNo.isEmpty() && decide, branchNo.isEmpty() ? no : branchNo);
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
        if (action.equals("farmpage")) { PlayerLife.open(p, "farm", LifePage.args("farm", args.getString("farm"))); return "#open:"; }   // [wpb]
        if (action.equals("contracts")) { PlayerLife.open(p, "contracts", null); return "#open:"; }
        if (action.equals("buyoffer")) {                                    // [wpb] another's firm for sale: the offer page
            Business x = Businesses.of(v.id(), args.getString("firm"));
            if (x == null) return "!No such business.";
            PlayerLife.open(p, "offer", LifePage.args("firm", x.id));
            return "#open:";
        }
        if (action.equals("oaccept") || action.equals("orefuse") || action.equals("ocounter")) return OfferPage.answer(level, p, action, args);
        if (action.equals("acceptshare")) {
            Business x = Businesses.of(v.id(), args.getString("firm"));
            if (x == null) return "!No such business.";
            if (args.getInt("coins") > 20 && !args.getBoolean("sure")) { c.args.putString("sureShare", x.id + "/" + args.getInt("i")); return "!Press again to pay " + args.getInt("coins") + "c for the share."; }
            c.args.remove("sureShare");
            return Partners.accept(level, p, v.id(), x, args.getInt("i"));
        }
        if (action.equals("shop")) { PlayerLife.open(p, "shop", null); return "#open:"; }   // [ui29] the empty page's second way
        if (action.startsWith("tab:")) { c.tab = action.substring(4); return ""; }
        if (action.equals("pick")) { c.args.putString("firm", args.getString("firm")); return ""; }
        if (action.equals("bookof")) {                                      // [biz30] any of its firms' books, from the list
            Business x = Businesses.of(v.id(), args.getString("firm"));
            if (x == null || !x.ownedBy(p.getUUID())) return "!No such business of yours.";
            BusinessBooks.show(p, v.id(), x);
            return "#open:";
        }
        if (action.equals("dataof")) {                                      // [biz30] a firm's sales and costs dashboard
            Business x = Businesses.of(v.id(), args.getString("firm"));
            if (x == null || !x.ownedBy(p.getUUID())) return "!No such business of yours.";
            BizData.send(p, v.id(), x, 28);
            return "#open:";
        }
        if (action.equals("shopdata")) {                                    // [biz30] a shop's
            PlayerShops.Shop sh = PlayerShops.byKey(v.id(), args.getString("key"));
            if (sh == null || !sh.owner.equals(p.getUUID())) return "!No such shop of yours.";
            BizData.send(p, v.id(), Businesses.shopBook(level, sh), 28);
            return "#open:";
        }
        if (action.equals("shopof")) {                                      // [biz30] one of its shops' screen
            PlayerShops.Shop sh = PlayerShops.byKey(v.id(), args.getString("key"));
            if (sh == null || !sh.owner.equals(p.getUUID())) return "!No such shop of yours.";
            PlayerShops.choose(p.getUUID(), sh);
            PlayerLife.open(p, "shop", null);
            return "#open:";
        }
        if (action.equals("drawclosed")) {                                  // [biz30]
            Business x = Businesses.of(v.id(), args.getString("firm"));
            return x == null ? "!No such business." : Businesses.takeDrawings(v.id(), x, p);
        }
        Business b = chosen(p, v, c);
        if (b == null) return "!You hold no business here.";
        CompoundTag f = args.getCompound("f");
        switch (action) {
            case "books" -> { BusinessBooks.show(p, v.id(), b); return "#open:"; }
            case "hire" -> {
                VillageFolkEntity h = Enterprise.hireForTests(level, v, b);
                if (h != null) BusinessCore.event(Enterprise.rec(v.id(), b.id), Businesses.today(level), h.displayNameCap() + " taken on");   // [wpb] its timeline
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
            case "raise", "cut", "letgo", "manager" -> { return handAction(level, p, v, b, action, args, c); }
            case "deputy", "undeputy", "appoint", "showme", "depon", "depmode", "deptask", "depnotify", "orders", "letprice", "raiseyes", "raiseno",
                 "sack", "report", "readreport", "target" -> { return DeputyPage.act(level, p, v, b, action, args, c); }
            case "hours", "allhours", "discount", "quality", "advert", "premises", "tour", "showstock" -> { return CoreHub.act(level, p, v, b, action, args, c); }
            case "waremk" -> {
                if (!Partners.canDecide(v.id(), b, p.getUUID())) return "!" + Partners.whyNot(v.id(), b);
                return setWareMarkup(level, v.id(), b, args.getString("k"), args.getInt("d"));
            }
            case "postplayer" -> {
                BusinessTypes.BusinessType t = Enterprise.kindOf(v.id(), b);
                JobCards.Card card = t == null ? null : JobCards.of(t.work().name());
                if (card == null) return "!No player job for this trade.";
                return PlayerHiring.post(level, p, v.id(), "firm:" + b.id, b.name, card.key(), PayTerms.goingWage(level, v.id(), card, 0), ShopPage.parse(f.getString("pw"), 0));
            }
            case "unpost" -> { return PlayerHiring.withdraw(p, args.getInt("id")); }
            case "offerjob" -> { return PlayerHiring.answer(p, args.getInt("id"), java.util.UUID.fromString(args.getString("u")), true); }
            case "turndown" -> { return PlayerHiring.answer(p, args.getInt("id"), java.util.UUID.fromString(args.getString("u")), false); }
            case "warn" -> { return PlayerHiring.review(level, p, java.util.UUID.fromString(args.getString("u")), false, "a word from the owner about the work"); }
            case "dismiss" -> { return PlayerHiring.review(level, p, java.util.UUID.fromString(args.getString("u")), true, "the owner let you go"); }
            case "share" -> {
                ServerPlayer to;
                try { to = level.getServer().getPlayerList().getPlayer(java.util.UUID.fromString(f.getString("partner"))); } catch (IllegalArgumentException e) { to = null; }
                if (to == null) return "!They've gone.";
                return Partners.offer(p, v.id(), b, to, ShopPage.parse(f.getString("share"), 2500), ShopPage.parse(f.getString("sprice"), 25));
            }
            case "payout" -> {
                if (!Partners.canDecide(v.id(), b, p.getUUID())) return "!" + Partners.whyNot(v.id(), b);
                long paid = Partners.payout(level, v.id(), b);
                return paid <= 0 ? "!No profit over the working reserve to pay out." : "Paid out " + Businesses.pence(paid) + " by share: each owner's part is waiting as drawings.";
            }
            case "managing" -> { return Partners.setManaging(p, v.id(), b, java.util.UUID.fromString(f.getString("managing"))); }
            case "list" -> {
                int pct = ShopPage.parse(f.getString("ask"), 110);
                return FirmSale.list(level, p, v.id(), b, Math.max(100, FirmSale.worth(v.id(), b) * pct / 100));
            }
            case "unlist" -> { return FirmSale.withdraw(p, v.id(), b); }
            case "branch" -> { return openBranch(level, p, v, b); }
            case "rescue" -> {
                if (!args.getBoolean("sure")) { c.args.putBoolean("sureRescue", true); return "!Press again to pay its debts and take it back from the court."; }
                c.args.remove("sureRescue");
                return rescue(level, p, v, b);
            }
            case "order" -> {
                ItemStack one = Stockroom.sampleOf(args.getString("k"));
                if (one.isEmpty()) return "!No such ware.";
                return Businesses.playerOrder(level, v.id(), p, b, one, ShopPage.parse(f.getString("qty"), 16));
            }
            case "markup" -> {
                if (!Partners.canDecide(v.id(), b, p.getUUID())) return "!" + Partners.whyNot(v.id(), b);
                CompoundTag rr = Enterprise.rec(v.id(), b.id);
                if (rr != null) rr.putLong("ownerPrice", Businesses.today(level));   // [wpb] the owner's price: the deputy leaves it
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
                // [biz30] What was left to its owner paid out now: once shut it is off this page, and its coin was never had.
                String paid = b.ownersDue.getOrDefault(p.getUUID(), 0L) >= 100 ? " " + Businesses.takeDrawings(v.id(), b, p) : "";
                return b.name + " is wound up." + paid;
            }
            default -> { return null; }
        }
    }

    // ------------------------------------------------------------------ [wpb] the owner's tools

    /** Before the court: the banner, what it owes, and the way to save it. */
    static void rescueBanner(ServerLevel level, ServerPlayer p, Villages.Village v, Business b, LifePage page) {
        CompoundTag r = Enterprise.rec(v.id(), b.id);
        if (r == null || !r.contains("petition")) return;
        long owed = rescueCost(b);
        page.box("Before the court: " + r.getString("petition"), List.of("Its creditors have taken it to court. It trades no more, and the case is heard at the next sitting "
            + "(the hall's court this morning, else the council tomorrow). Declared bankrupt, its stock and kit are sold and its creditors paid.",
            "To save it, pay what it owes and a little working coin: " + Businesses.pence(owed) + ". You carry " + Market.coinsHeld(p) + "c."), 3);
        boolean sure = PlayerLife.ctx(p).args.getBoolean("sureRescue");
        page.buttons().button(sure ? "Confirm: pay " + Businesses.pence(owed) + " and save it" : "Rescue it (" + Businesses.pence(owed) + ")", "rescue", LifePage.args("sure", sure),
            Market.coinsHeld(p) * 100L >= owed, "You need " + Businesses.pence(owed)).primary();
    }

    static long rescueCost(Business b) {
        return Math.max(0, -b.account) + Math.max(0, -b.till) + 300;
    }

    static String rescue(ServerLevel level, ServerPlayer p, Villages.Village v, Business b) {
        CompoundTag r = Enterprise.rec(v.id(), b.id);
        if (r == null || !r.contains("petition")) return "!It isn't before the court.";
        long cost = rescueCost(b);
        int coins = (int) ((cost + 99) / 100);
        if (Market.coinsHeld(p) < coins) return "!Saving it takes " + coins + "c; you carry " + Market.coinsHeld(p) + "c.";
        Market.payOut(p, coins);
        CoinCensus.playerIn(v.id(), coins, p, PlayerFinance.Cat.FEES, "saved " + b.name + " from the court");
        long toDebt = Math.max(0, -b.account);
        Businesses.toTreasury(v.id(), toDebt);
        b.account += toDebt;
        b.till = Math.max(0, b.till) + coins * 100L - toDebt;
        long day = Businesses.today(level);
        b.today(day).capital += coins * 100L;
        b.allTime.capital += coins * 100L;
        b.lossDays = 0;
        r.remove("petition");
        r.remove("petitionDay");
        r.putString("status", "trading");
        r.putInt("broke", 0);
        b.note(day, "saved from the court by " + p.getGameProfile().getName() + ": its debts paid");
        Villages.tell(v.id(), level.getDayTime() / 24000L, p.getGameProfile().getName() + " paid " + b.name + "'s debts and took it back from the court");
        Accounts.dirty();
        Businesses.dirty();
        return b.name + " is saved: its debts paid (" + Businesses.pence(toDebt) + ") and " + Businesses.pence(coins * 100L - toDebt) + " in its till. It trades again tomorrow.";
    }

    /** The profit and loss card: this week against last. */
    static void plCard(ServerLevel level, Villages.Village v, Business b, LifePage page) {
        long day = Businesses.today(level);
        Business.Day w = b.last(day, 7), last = b.last(day - 7, 7);
        long net = w.net(), was = last.net();
        String trend = net > was ? "up from " + Businesses.pence(was) : net < was ? "down from " + Businesses.pence(was) : "the same as last week";
        int margin = w.revenue <= 0 ? 0 : (int) Math.round((w.revenue - w.cogs) * 100.0 / w.revenue);
        page.box("This week: " + (net >= 0 ? "a profit of " : "a loss of ") + Businesses.pence(Math.abs(net)) + " (" + trend + ")", List.of(
            "Sales " + Businesses.pence(w.revenue) + " (" + w.trans + " sold), their cost " + Businesses.pence(w.cogs) + ": a gross margin of " + margin + "%.",
            "Wages " + Businesses.pence(w.wages) + " · rent " + Businesses.pence(w.rent) + " · other " + Businesses.pence(w.other) + " · tax " + Businesses.pence(w.taxes)
                + " · interest " + Businesses.pence(w.interest) + ".",
            "Till " + Businesses.pence(b.till) + (b.account < 0 ? ", owing the town " + Businesses.pence(-b.account) : "") + ". Worth about " + Businesses.pence(FirmSale.worth(v.id(), b)) + "."),
            net >= 0 ? 1 : 3);
    }

    /** Firms other players or folk have up for sale, and share offers made to this player. */
    void forSaleAndOffers(ServerLevel level, ServerPlayer p, Villages.Village v, LifePage page) {
        boolean head = false;
        String sure = PlayerLife.ctx(p).args.getString("sureBuy");
        for (Business x : Enterprise.open(v.id())) {
            CompoundTag s = FirmSale.sale(v.id(), x);
            if (s == null || x.ownedBy(p.getUUID())) continue;
            if (!head) { page.heading("Businesses for sale"); head = true; }
            page.row(null, x.name, "asking " + Businesses.pence(s.getLong("ask")) + " · worth about " + Businesses.pence(FirmSale.worth(v.id(), x)) + " · owned by " + x.ownersWords(), "")
                .button("Make an offer", "buyoffer", LifePage.args("firm", x.id));
        }
        List<Object[]> offers = Partners.offersFor(v.id(), p.getUUID());
        if (!offers.isEmpty()) page.heading("Shares offered to you");
        for (Object[] o : offers) {
            Business x = (Business) o[0];
            CompoundTag t = (CompoundTag) o[2];
            boolean on = PlayerLife.ctx(p).args.getString("sureShare").equals(x.id + "/" + o[1]);
            page.row(null, t.getInt("parts") * 100 / Business.WHOLE + "% of " + x.name, "from " + t.getString("fromName") + " for " + t.getInt("coins") + "c; profit split by share", "")
                .button(on ? "Confirm: pay " + t.getInt("coins") + "c" : "Accept (" + t.getInt("coins") + "c)", "acceptshare", LifePage.args("firm", x.id, "i", (int) o[1], "coins", t.getInt("coins"), "sure", on),
                    Market.coinsHeld(p) >= t.getInt("coins"), "You need " + t.getInt("coins") + "c");
        }
    }

    /** A hand raised, cut, made manager, or let go (with severance, after a confirm). */
    String handAction(ServerLevel level, ServerPlayer p, Villages.Village v, Business b, String action, CompoundTag args, PlayerLife.Ctx c) {
        if (!Partners.canDecide(v.id(), b, p.getUUID())) return "!" + Partners.whyNot(v.id(), b);
        java.util.UUID hid = java.util.UUID.fromString(args.getString("h"));
        VillageFolkEntity f = null;
        for (VillageFolkEntity x : Enterprise.hands(v.id(), b)) if (x.getUUID().equals(hid)) f = x;
        if (f == null) return "!They don't work there any more.";
        CompoundTag r = Enterprise.rec(v.id(), b.id);
        long day = Businesses.today(level);
        switch (action) {
            case "raise", "cut" -> {
                LabourBook.Contract k = LabourMarket.contract(f);
                if (k == null) return "!" + f.displayNameCap() + " isn't on the town's books.";
                int was = Wealth.wage(f);
                k.m = Math.max(0.5, Math.min(2.5, k.m * (action.equals("raise") ? 1.1 : 0.9)));
                LabourBook.changed();
                if (action.equals("cut")) {
                    f.persona().remember(level.getDayTime() / 24000L, "my pay was cut at " + b.name, -3);
                    FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "A cut? Right.", "I'll remember that.", "Times are hard, I suppose.", "Less for the same work. Fine.",
                        "I'll be looking about, then.", "Noted."));
                } else FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Thank you. I'll earn it.", "That's kind.", "Much obliged.", "I won't forget it.",
                    "Good. I'll keep it up.", "That makes a difference."));
                b.note(day, f.displayNameCap() + "'s pay " + (action.equals("raise") ? "raised" : "cut") + " a tenth by its owner");
                return f.displayNameCap() + "'s pay is " + (action.equals("raise") ? "raised" : "cut") + " a tenth: from about " + was + "c to " + Wealth.wage(f) + "c a day."
                    + (action.equals("cut") ? " They took it badly; a hand who feels underpaid may leave." : "");
            }
            case "manager" -> {                                             // [wpb] the deputy, by the old button's name
                if (r == null) return "!Its record is missing.";
                if (r.hasUUID("manager") && r.getUUID("manager").equals(hid)) { Deputy.demote(v.id(), b, r); return "No deputy now: while you're away the staff carry on with what's in stock."; }
                return Deputy.appoint(level, v.id(), b, f, Deputy.TITLES[0], 10);
            }
            default -> {
                if (r != null && r.contains("keepStaffUntil") && r.getLong("keepStaffUntil") > day) return "!You agreed to keep the staff on when you bought it: not before day " + (r.getLong("keepStaffUntil") + 1) + ".";
                if (!args.getBoolean("sure")) { if (r != null) { r.putString("wpbLetGo", hid.toString()); Accounts.dirty(); } return "!Letting " + f.displayNameCap() + " go pays " + severance(f) + "c severance: press Confirm."; }
                if (r != null) r.remove("wpbLetGo");
                int sev = severance(f);
                long paid = Businesses.payOut(level, v.id(), b, sev * 100L, day);
                f.earn((int) (paid / 100));
                b.today(day).wages += paid;
                b.allTime.wages += paid;
                LabourMarket.layOff(level, v, LabourBook.town(v.id()), f, "firm:" + b.id, "let go by its owner, with " + (paid / 100) + "c severance", level.getDayTime() / 24000L);
                BusinessCore.event(r, day, f.displayNameCap() + " let go");          // [wpb] its timeline
                if (r != null && r.hasUUID("manager") && r.getUUID("manager").equals(hid)) Deputy.demote(v.id(), b, r);
                Accounts.dirty();
                return f.displayNameCap() + " is let go with " + (paid / 100) + "c severance" + (paid / 100 < sev ? " (all the till had)" : "") + ". Post an opening when you want another.";
            }
        }
    }

    /** Its owner's markup on this ware, else the firm's own. */
    static double wareMarkup(java.util.UUID village, Business b, ItemStack one) {
        CompoundTag r = Enterprise.rec(village, b.id);
        if (r == null || !r.contains("wareMarkup")) return b.markup;
        String k = Stockroom.key(one.copyWithCount(1));
        CompoundTag m = r.getCompound("wareMarkup");
        return m.contains(k) ? m.getDouble(k) : b.markup;
    }

    static boolean hasWareMarkup(java.util.UUID village, Business b, String key) {
        CompoundTag r = Enterprise.rec(village, b.id);
        return r != null && r.getCompound("wareMarkup").contains(key);
    }

    static String setWareMarkup(ServerLevel level, java.util.UUID village, Business b, String key, int d) {
        CompoundTag r = Enterprise.rec(village, b.id);
        if (r == null) return "!Its record is missing.";
        CompoundTag m = r.getCompound("wareMarkup");
        if (d == 0) {
            m.remove(key);
            r.put("wareMarkup", m);
            Accounts.dirty();
            return Stockroom.plural(key) + " go back to the firm's markup (" + Businesses.pct(b.markup) + ").";
        }
        double was = m.contains(key) ? m.getDouble(key) : b.markup;
        double now = Math.max(0.05, Math.min(2.0, Math.round(was * 100 + d) / 100.0));
        m.putDouble(key, now);
        r.put("wareMarkup", m);
        Accounts.dirty();
        b.note(Businesses.today(level), Stockroom.plural(key).toLowerCase(Locale.ROOT) + " marked up " + Businesses.pct(now) + " by its owner");
        return Stockroom.plural(key) + " now carry a markup of " + Businesses.pct(now) + " (the rest of its wares " + Businesses.pct(b.markup) + "). The price is never over three fifths above the going price.";
    }

    /** Why the player can't open a branch of this firm ("" if they can): FirmBranches' own gates. */
    static String branchWhyNot(ServerLevel level, Villages.Village v, Business b, ServerPlayer p) {
        if (!Government.allowsPrivateFirms(v.id())) return "the state owns the firms here";
        long day = Businesses.today(level);
        if (day - b.opened < 21) return "it must trade three weeks first (" + (21 - (day - b.opened)) + " days to go)";
        List<Business> mine = FirmBranches.owned(v.id(), p.getUUID());
        if (mine.size() >= FirmBranches.cap(level, v.id(), p.getUUID())) return "your firms need more good weeks behind them (" + FirmBranches.profitableWeeks(level, v.id(), p.getUUID())
            + " so far; one more firm for every four)";
        if (!FirmBranches.fairShare(v.id(), p.getUUID())) return "you hold your fair share of the town's firms already";
        for (Business x : mine) if (x.last(day, 14).net() < 0) return x.name + " lost money over the fortnight";
        if (b.till < 1000) return "its till needs 10c or more to start one";
        return "";
    }

    static String openBranch(ServerLevel level, ServerPlayer p, Villages.Village v, Business b) {
        String no = branchWhyNot(level, v, b, p);
        if (!no.isEmpty()) return "!Not yet: " + no + ".";
        BusinessTypes.BusinessType t = Enterprise.kindOf(v.id(), b);
        if (t == null) return "!Its trade isn't known.";
        long day = Businesses.today(level);
        Business nb = Enterprise.found(level, v, t, null, p, day, null);
        if (nb == null) return "!The branch couldn't be opened.";
        long capital = Math.min(b.till / 2, 3000);
        b.till -= capital;
        nb.till += capital;
        nb.today(day).capital += capital;
        nb.allTime.capital += capital;
        nb.markup = b.markup;
        b.note(day, "opened a branch, " + nb.name + ", with " + Businesses.pence(capital));
        Businesses.dirty();
        return "Opened " + nb.name + ", a branch, with " + Businesses.pence(capital) + " from " + b.name + "'s till. Take on its hands and stock it from here.";
    }

    // ------------------------------------------------------------------ [wpb] owners away

    /**
     * Shut today? Only when nobody is able to work it: no folk on its staff, and every member of staff (its player owners
     * and player hands) away; or its deputy called a halt yesterday with the till dry. Marked once a day, with its closed sign.
     * A folk-founded firm never: its owner is its staff.
     */
    static boolean pausedToday(@Nullable ServerLevel level, java.util.UUID village, Business b, long day) {
        CompoundTag r = Enterprise.rec(village, b.id);
        if (r == null || r.hasUUID("founder")) return false;
        String why = null;
        if (r.contains("haltDay") && r.getLong("haltDay") == day) why = "shut for the day: its deputy called a halt, the till dry";
        else {
            if (!Enterprise.staffOf(r).isEmpty()) { r.remove("pausedDay"); return false; }   // its folk staff turn up regardless
            boolean anyPlayer = false;
            for (Business.Stake s : b.owners) {
                if (s.holder() != Business.Holder.PLAYER || s.who() == null) continue;
                anyPlayer = true;
                boolean on = level != null ? level.getServer().getPlayerList().getPlayer(s.who()) != null
                    : !r.contains("away/" + s.who()) || r.getLong("away/" + s.who()) >= day;
                if (on) { r.remove("pausedDay"); return false; }
            }
            if (level != null && !PlayerHiring.hands(level, "firm:" + b.id).isEmpty()) { r.remove("pausedDay"); return false; }
            if (!anyPlayer) return false;
            why = "shut for the day: all its staff are players, and none of them about";
        }
        if (r.getLong("pausedDay") != day) {
            r.putLong("pausedDay", day);
            b.note(day, why);
            Accounts.dirty();
        }
        return true;
    }

    static boolean pausedToday(java.util.UUID village, Business b, long day) {
        return pausedToday(null, village, b, day);
    }

    @net.neoforged.fml.common.EventBusSubscriber(modid = com.jrpetty.mcassistant.McAssistantMod.MODID)
    public static final class Away {
        private Away() {}

        @net.neoforged.bus.api.SubscribeEvent
        public static void onLogout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) {
            mark(e.getEntity(), true);
        }

        @net.neoforged.bus.api.SubscribeEvent
        public static void onLogin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e) {
            mark(e.getEntity(), false);
        }

        static void mark(net.minecraft.world.entity.player.Player p, boolean away) {
            if (!(p.level() instanceof ServerLevel level)) return;
            long day = Businesses.today(level);
            for (Villages.Village v : Villages.every()) {
                for (Business b : Enterprise.open(v.id())) {
                    if (!b.ownedBy(p.getUUID())) continue;
                    CompoundTag r = Enterprise.rec(v.id(), b.id);
                    if (r == null) continue;
                    if (away) r.putLong("away/" + p.getUUID(), day);
                    else { r.remove("away/" + p.getUUID()); r.remove("pausedDay"); }
                    Accounts.dirty();
                }
            }
        }
    }

    /** Tests: an owner marked away since this day. */
    public static void awayForTests(java.util.UUID village, Business b, java.util.UUID owner, long since) {
        CompoundTag r = Enterprise.rec(village, b.id);
        if (r != null) r.putLong("away/" + owner, since);
    }

    public static boolean pausedTodayForTests(java.util.UUID village, Business b, long day) {
        return pausedToday(village, b, day);
    }

    public static String handActionForTests(ServerLevel level, ServerPlayer p, Business b, String action, java.util.UUID hand, boolean sure) {
        Villages.Village v = PlayerLife.here(p);
        return v == null ? "!no town" : new MyBusinesses().handAction(level, p, v, b, action, LifePage.args("h", hand.toString(), "sure", sure), PlayerLife.ctx(p));
    }

    public static String setWareMarkupForTests(ServerLevel level, java.util.UUID village, Business b, String key, int d) {
        return setWareMarkup(level, village, b, key, d);
    }

    public static String rescueForTests(ServerLevel level, ServerPlayer p, Business b) {
        Villages.Village v = PlayerLife.here(p);
        return v == null ? "!no town" : rescue(level, p, v, b);
    }

    /** The commands' words, without the commands in them. */
    static String clean(String s) {
        return s.replaceAll(" ?(Take the best with|post one with) /village [^.]*\\.?", "").replace(": .", ".").trim();
    }
}
