package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * [biz25] "A shop of your own" in the Start a business flow (StartBusiness): the easiest business there is, and the one a
 * newcomer is offered first. No charter and no hands: the player fills its shelves, folk come in and buy at its counter
 * (PlayerShops, ShopCustomers), and a licence is paid when it is registered on its own screen.
 * <ol>
 * <li><b>A kind</b>: the shop's row at the top of the kinds, with what it is and what it costs.</li>
 * <li><b>Premises</b>: your shop if you keep one; a building of your own (a Shop Counter set in it: bought here of the
 *     town's carpenters, or crafted); a building the town puts up for you (the builders' gallery, quote and plot, as for a
 *     firm's premises); or a building that's for sale (the property office's market).</li>
 * <li><b>Confirm</b>: what is paid now (the counter, or the building's deposit), the licence to come, and the button.</li>
 * </ol>
 * Then what to do next, step by step, to the first sale. Every button goes through the same code the rest of the game
 * uses (BuildDesk, BuildOrders, PropertyOffice, PlayerShops): nothing typed.
 */
final class BizShop {

    private BizShop() {}

    /** The shop's row at the head of the kinds. */
    static void kindRow(ServerLevel level, ServerPlayer p, Villages.Village v, String chosen, LifePage page) {
        boolean on = StartBusiness.SHOP.equals(chosen);
        boolean has = PlayerShops.of(v.id(), p.getUUID()) != null;
        page.row(new ItemStack(McAssistantMod.SHOP_COUNTER_ITEM.get()), (on ? "✔ " : "") + "A shop of your own (the easiest)",
                has ? "You keep " + PlayerShops.ofAll(v.id(), p.getUUID()).size() + " here already: open another, its own room, counter and licence (and a keeper, for you can't mind two counters)"   // [biz30]
                    : "You fill its shelves, folk come in and buy at its counter; no charter, no hands to pay; a licence of "
                        + PlayerLife.coins(PlayerShops.licence(v.id())), "")
            .button(on ? "Chosen" : "Choose", "kind", LifePage.args("k", StartBusiness.SHOP), !on, "Chosen");
    }

    /** Step 2: where the shop will be. */
    static void premises(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page) {
        String prem = c.args.getString("prem");
        page.heading("Where will your shop be?");
        PlayerShops.Shop shop = PlayerShops.of(v.id(), p.getUUID());
        if (shop != null) {
            page.row(new ItemStack(McAssistantMod.SHOP_COUNTER_ITEM.get()), (prem.equals("shop") ? "✔ " : "") + "Your shop, " + shop.name + " (no new one: its screen)",
                    PlayerShops.stateWords(level, shop), "")
                .button(prem.equals("shop") ? "Chosen" : "Choose", "prem", LifePage.args("p", "shop"), !prem.equals("shop"), "Chosen");
        }
        boolean carries = BizGuide.carriesCounter(p);
        page.row(new ItemStack(Items.OAK_DOOR), (prem.equals("own") ? "✔ " : "") + "A building of your own",
                "Any room with walls, a roof and a door onto a street: set a Shop Counter in it and its screen says what else it needs",
                carries ? "you have a counter" : "counter " + PlayerLife.coins(BizGuide.counterPrice(level, v.id())))
            .button(prem.equals("own") ? "Chosen" : "Choose", "prem", LifePage.args("p", "own"), !prem.equals("own"), "Chosen");
        page.row(new ItemStack(Items.BRICKS), (prem.equals("build") ? "✔ " : "") + "Have the town build it",
                "Choose from the town's drawings, with a picture of each, its quote and how long", "")
            .button(prem.equals("build") ? "Chosen" : "Choose", "prem", LifePage.args("p", "build"), true, "");
        if (prem.equals("build")) StartBusiness.gallery(level, p, v, c, page, "shop");
        int forSale = PropertyMarket.listed(v.id()).size();
        page.row(new ItemStack(Items.OAK_SIGN), "Buy a building that's for sale",
                forSale == 0 ? "None for sale in " + Villages.name(v.id()) + " just now" : forSale + " for sale in " + Villages.name(v.id()) + ": buy one, then set a counter in it", "")
            .button("See them", "prem", LifePage.args("p", "buy"), forSale > 0, "None for sale just now");
        page.buttons().button("Back", "step", LifePage.args("s", 1)).button("Next: confirm", "step", LifePage.args("s", 3),
            !prem.equals("build") || c.args.contains("px"), "Choose a building and its plot first").primary();
    }

    /** Step 3: what it costs now, and the button. */
    static void confirm(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page) {
        String prem = c.args.getString("prem");
        PlayerShops.Shop shop = PlayerShops.of(v.id(), p.getUUID());
        int have = Market.coinsHeld(p), licence = PlayerShops.licence(v.id());
        page.heading("Your shop in " + Villages.name(v.id()));
        page.row(new ItemStack(McAssistantMod.SHOP_COUNTER_ITEM.get()), "A shop of your own", "you fill its shelves; folk come in and buy", "")
            .button("Change", "step", LifePage.args("s", 1));
        BuildOrders.Quote q = StartBusiness.buildQuote(level, p, v, c);
        String where = prem.equals("shop") && shop != null ? "your shop, " + shop.name
            : prem.equals("build") && q != null ? BuildOrders.title(q.drawing) + " the town builds, " + BuildPlots.label(v.centre(), q.plot)
            : "a building of your own";
        page.row(null, "Premises: " + where, "", "").button("Change", "step", LifePage.args("s", 2));
        if (prem.equals("shop") && shop != null) {
            page.text("Your shop is set up already: its screen has its stock, prices, books and keeper.", 1);
            page.buttons().button("Back", "step", LifePage.args("s", 2)).button("Open your shop's screen", "shop", null).primary();
            return;
        }
        page.heading("The cost");
        int now;
        if (prem.equals("build")) {
            if (q == null) { page.text("Choose a building and its plot first.", 3); page.buttons().button("Back", "step", LifePage.args("s", 2)); return; }
            page.row(null, "The building: its deposit now", "Of " + PlayerLife.coins(q.total) + " all told; " + q.stage + "c more at half built, "
                + q.balance + "c when it stands", PlayerLife.coins(q.deposit));
            for (String line : BuildDesk.quoteLines(q)) page.text(line, 4);
            if (!q.ok) page.text("✘ The builders won't build there: " + q.why + ". Choose another plot.", 3);
            now = q.deposit;
        } else {
            boolean carries = BizGuide.carriesCounter(p);
            now = carries ? 0 : BizGuide.counterPrice(level, v.id());
            page.row(new ItemStack(McAssistantMod.SHOP_COUNTER_ITEM.get()), "A Shop Counter, made by the town's carpenters",
                carries ? "You carry one already" : "Or craft one: three wooden slabs over a chest between two planks, a gold nugget under", carries ? "—" : PlayerLife.coins(now));
        }
        page.row(null, "The licence, when you register it on its screen", "Then " + (TaxBook.ratesCoins(v.id()) > 0 ? PlayerLife.coins(TaxBook.ratesCoins(v.id())) + " a week in rates, out of its till" : "no rates while the town charges none"), PlayerLife.coins(licence));
        page.row(null, "To pay now", "You have " + PlayerLife.coins(have), PlayerLife.coins(now));
        if (prem.equals("own") && now == 0) {
            page.text("Nothing to pay now. Set your counter down inside your building: its screen opens, and says what else it needs.", 1);
            page.buttons().button("Back", "step", LifePage.args("s", 2)).button("Done: what next?", "confirm", null).primary();
            return;
        }
        String label = prem.equals("build") ? "Order it (" + PlayerLife.coins(now) + " now)" : "Buy the counter (" + PlayerLife.coins(now) + ")";
        if (have >= now) {
            page.buttons().button("Back", "step", LifePage.args("s", 2)).button(label, "confirm", null, !prem.equals("build") || q.ok, "Choose another plot").primary();
        } else if (prem.equals("build")) {
            int short_ = now - have;
            page.text("You need " + PlayerLife.coins(now) + " now and have " + have + ": borrow the other " + short_ + " from the bank?", 2);
            page.buttons().button("Back", "step", LifePage.args("s", 2))
                .button("Borrow " + short_ + " and order it", "confirm", LifePage.args("borrow", short_), Bank.open(v.id()) && q.ok, "The town has no bank yet: sell something or take a shift first")
                .primary();
        } else {
            page.text("You need " + PlayerLife.coins(now) + " and have " + have + ": sell something, take a shift (the jobs board), or craft a counter.", 2);
            page.buttons().button("Back", "step", LifePage.args("s", 2));
        }
    }

    /** The button on step 3: the counter bought, or the building ordered; then the next steps. */
    static String confirmed(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, int borrow) {
        String prem = c.args.getString("prem");
        if (prem.equals("shop")) { PlayerLife.open(p, "shop", null); return "#open:"; }
        if (prem.equals("build")) {
            BuildOrders.Quote q = StartBusiness.buildQuote(level, p, v, c);
            if (q == null) return "!Choose a building and its plot first.";
            String bad = !q.ok ? q.why : BuildPlots.whyNot(level, v, q.drawing, q.plot, q.facing);
            if (!bad.isEmpty()) return "!Its plot won't do: " + bad + ".";
            if (borrow > 0 && Market.coinsHeld(p) < q.deposit) {
                String said = FolkCredit.playerBorrows(level, p, v.id(), Math.min(borrow, q.deposit - Market.coinsHeld(p)));
                if (Market.coinsHeld(p) < q.deposit) return "!The bank said: " + said;
            }
            if (Market.coinsHeld(p) < q.deposit) return "!The deposit is " + PlayerLife.coins(q.deposit) + "; you have " + Market.coinsHeld(p) + ".";
            String id = BuildDesk.place(level, p, v.id(), q, "shop", null);
            if (id.startsWith("!")) return id;
            c.args.putString("order", id);
            c.args.putInt("step", 4);
            return "Ordered: the town's builders will put up your shop.";
        }
        if (!BizGuide.carriesCounter(p)) {
            String said = BizGuide.buyCounter(p);
            if (said.startsWith("!")) return said;
            c.args.putInt("step", 4);
            return said;
        }
        c.args.putInt("step", 4);
        return "";
    }

    /** What next, after step 3. */
    static void welcome(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page) {
        String prem = c.args.getString("prem");
        int licence = PlayerShops.licence(v.id());
        if (prem.equals("build")) {
            page.heading("Your shop is on order");
            BuildDesk.Status st = c.args.getString("order").isEmpty() ? null : BuildDesk.status(level, c.args.getString("order"));
            page.text("The town's builders have your order" + (st == null ? "" : ": " + st.stage() + (st.eta().isEmpty() ? "" : ", done in " + st.eta()))
                + ". You'll be told at each quarter; the rest is paid as it goes up (the property office has the order).", 1);
            page.text("When it stands its counter and a chest are set in and it is your shop: its screen opens for you to name it, register it ("
                + PlayerLife.coins(licence) + ") and stock it.", 4);
        } else {
            page.heading("Next: set up your shop");
            page.text("1. Set your Shop Counter down inside your building (walls, a roof, a door onto a street). Its screen opens.", 0);
            page.text("2. Name it and register it (a licence of " + PlayerLife.coins(licence) + "); its checklist says what else it needs.", 0);
            page.text("3. Put what you'd sell in a chest, barrel or shelf inside. Each sells at the going price unless you set one.", 0);
            page.text("4. In its hours folk come in, pay at the counter and the coin goes in its till. A keeper minds it while you're away.", 0);
        }
        String want = BizGuide.wantedWords(level, v.id(), 5);
        if (!want.isEmpty()) page.text(want, 2);
        page.buttons().button("Back to your life in town", "life", null).button("How a shop works", "shophelp", null);
    }
}
