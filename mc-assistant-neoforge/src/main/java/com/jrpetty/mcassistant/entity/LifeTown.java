package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [player15] Your life in town, on one page (the U key, the village board's "You" button, any folk's "Show me my life in
 * town", the journal): who you are to the town (your standing, citizenship, friends), your purse and what you earned, your
 * job and shift, your shop and its takings, your businesses, your home, the buildings going up for you, the bank; and
 * what you could do next, each a button to the screen it is done on. And "Everything you can do", for the rest.
 */
public final class LifeTown implements PlayerLife.Page {

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        String town = v == null ? "no town" : Villages.name(v.id());
        LifePage page = new LifePage("life", "Your life in town", v == null ? "You're out in the wild: find a town, or start your own" : "In " + town, p.registryAccess());   // [found23]
        if (v == null) {
            FoundingEasy.lifeRows(p, page);                                   // [found23] start a village of your own
            page.text("Or walk till you find a town, then come back here.", 4);
            page.buttons().button("Everything you can do", "all", null);
            return page;
        }
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Standing.View view = Standing.of(id, p.getUUID(), level.getGameTime());
        boolean citizen = Citizens.is(id, p.getUUID());
        page.heading("You and " + town);
        page.row(new ItemStack(Items.PLAYER_HEAD), "You are " + view.title().words + (citizen ? ", and a citizen" : ""),
                view.knownBy() + " folk know you" + (view.bestFriend().isEmpty() ? "" : "; your best friend here is " + view.bestFriend()), "")
            .button(citizen ? "Your vote" : "Become a citizen", citizen ? "council" : "citizen", null);
        page.bar("How the town sees you", Math.max(0, Math.min(1, (view.score() + 100) / 200.0)), view.title().words);
        if (!citizen && !view.title().atLeast(Standing.Title.FRIEND)) {   // [ui29] what lifts it, so "Become a citizen" is not a dead end
            page.text("A friend of the town may live here. Talk with folk each day and give them gifts: how warm they are to you is"
                + " what it reads (" + view.score() + " now; a friend at 12).", 4);
        }
        // [featfolk] The town's respect, named, from its witnesses, and why; and the player's own family here.
        String rating = TownOpinion.ratingOf(id, p.getUUID(), level.getGameTime());
        page.bar("Respect: " + rating, Math.max(0, Math.min(1, (view.score() + 100) / 200.0)), (view.score() > 0 ? "+" : "") + view.score());
        TownOpinion.View seen = TownOpinion.of(id, p.getUUID());
        for (int i = 0; i < Math.min(3, seen.reasons().size()); i++) page.text("• " + TownOpinion.line(seen.reasons().get(i)), 4);
        PlayerFamily.lifeRows(p, page, id);
        int[] earned = Earnings.earned(p, id, day, 7);
        page.row(new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get()), "You carry " + PlayerLife.coins(Market.coinsHeld(p)),
                "Earned here today " + PlayerLife.coins(earned.length > 0 ? earned[0] : 0) + ", this week " + PlayerLife.coins(Earnings.sum(earned))
                    + "; at the bank " + PlayerLife.coins(FolkCredit.balance(id, p.getUUID())), "")
            .button("The Earnings page", "earnings", null).button("Finances", "finances", null);
        // work
        page.heading("Work");
        String job = PlayerJobs.line(p);
        page.row(new ItemStack(Items.CLOCK), job.isEmpty() ? "No job yet" : job, Shifts.onShift(p) ? "On a shift now" : "Openings and day shifts are on the jobs board", "")
            .button("The jobs board", "jobs", null).primary();
        // the shop
        page.heading("Your shop");
        PlayerShops.Shop s = PlayerShops.of(id, p.getUUID());
        List<BuildDesk.Status> goingUp = BuildDesk.mine(level, id, p.getUUID());   // [biz25] a shop on order is not "no shop"
        if (s == null && !goingUp.isEmpty()) {
            BuildDesk.Status g = goingUp.get(0);
            page.row(new ItemStack(com.jrpetty.mcassistant.McAssistantMod.SHOP_COUNTER_ITEM.get()), g.done() ? "Your shop stands" : "Your shop is going up: " + g.percent() + "% built",
                    g.done() ? "Its counter and a chest are being set in: its screen opens for you to name, register and stock it"
                        : (g.waitingOn().isEmpty() ? "" : BizGuide.capital(g.waitingOn()) + ". ") + (g.eta().isEmpty() ? "" : "Done in " + g.eta() + "."), "")
                .button("The property office", "property", LifePage.args("what", "orders"));
        } else if (s == null) {
            page.row(new ItemStack(com.jrpetty.mcassistant.McAssistantMod.SHOP_COUNTER_ITEM.get()), "No shop yet",
                    "A building with a Shop Counter: yours, one the town builds you, or one you buy; you stock it, folk buy", "")
                .button("Open a shop", "start", LifePage.args("kind", "shop")).primary().button("How it works", "shop", null);   // [biz25] one way in, step by step
        } else {
            PlayerShops.roll(s, day);
            page.row(new ItemStack(com.jrpetty.mcassistant.McAssistantMod.SHOP_COUNTER_ITEM.get()), s.name + ": " + PlayerShops.stateWords(level, s),
                    "Till " + PlayerLife.coins(s.till) + "; this week " + PlayerLife.coins(PlayerShops.week(s.weekCoin)) + " for " + PlayerShops.week(s.weekCount)
                        + " sold; " + ShopPage.words(s.rep) + (s.keeper == null ? "" : "; kept by " + s.keeperName), "")
                .button("Open its screen", "shop", null);
            BizGuide.Step next = BizGuide.next(BizGuide.steps(level, v, s));   // [biz25] its next step, till its first sale
            if (next != null) page.row(null, "Next: " + next.label(), next.hint(), "").button(next.button(), "shopstep", LifePage.args("tab", next.action().replace("tab:", "")));
            page.buttons().button("Sell", "property", LifePage.args("what", "sell")).button("Let", "property", LifePage.args("what", "let"))
                .button("Repurpose", "property", LifePage.args("what", "repurpose"));
        }
        // businesses
        page.heading("Your businesses");
        List<Business> firms = MyBusinesses.mine(id, p.getUUID());
        if (firms.isEmpty()) page.text("None yet.", 4);
        for (Business b : firms) page.row(null, b.name, Enterprise.line(level, id, b), "").button("Run it", "firm", LifePage.args("firm", b.id));
        for (PlayerFarms.Farm fm : PlayerFarms.ofOwner(id, p.getUUID())) page.row(new ItemStack(Items.WHEAT), fm.name + " (your " + fm.kind + ")",   // [wpb]
            fm.area() + " blocks, " + fm.hands.size() + " hands" + (fm.paused ? ", paused" : ""), "").button("Run it", "farmpage", LifePage.args("farm", fm.id));
        page.buttons().button("Start a business", "start", null).button("The town's businesses", "books", LifePage.args("page", "Businesses"));
        // building
        List<BuildDesk.Status> builds = BuildDesk.mine(level, id, p.getUUID());
        if (!builds.isEmpty()) {
            page.heading("Going up for you");
            for (BuildDesk.Status b : builds) page.bar(b.title(), b.percent() / 100.0, b.done() ? "finished" : b.percent() + "%" + (b.waitingOn().isEmpty() ? "" : ": " + b.waitingOn()));   // [biz25] not "waiting for waiting for"
        }
        // home
        page.heading("Home");
        int[] houses = Homes.playerHouses(id, p.getUUID());
        page.row(new ItemStack(Items.RED_BED), houses[0] == 0 ? "No house of your own here" : "You own " + houses[0] + (houses[0] == 1 ? " house" : " houses") + " here, worth " + PlayerLife.coins(houses[1]),
                houses[0] == 0 ? "Stand in an empty house and buy it, or ask what's for sale" : "Let it to a household, or collect the rent", "")
            .button("What's for sale?", "say", LifePage.args("t", "Is there a house for sale?"))
            .button("Buy the one I'm in", "buyhouse", null)
            .button("Collect rent", "rent", null, houses[0] > 0, "You own no house here");
        if (houses[0] > 0) {                                                 // [wpa] letting a house: a button, never typed
            page.choice("letrent", "Let it for", new String[]{ "2", "3", "4", "5", "6", "8" },
                new String[]{ "2 coins a day", "3 coins a day", "4 coins a day", "5 coins a day", "6 coins a day", "8 coins a day" }, "3");
            page.buttons().button("Let my house to a household", "let", null);
        }
        // [wpc] The property pages: home, land, the builder, premises, mortgages, debts, the will.
        page.buttons().button("Home", "wpc", LifePage.args("p", "home")).button("Land", "wpc", LifePage.args("p", "land"))
            .button("The builder", "wpc", LifePage.args("p", "build")).button("Premises", "wpc", LifePage.args("p", "premises"));
        String owing = Debts.status(id, p.getUUID(), day);
        page.row(new ItemStack(Items.PAPER), "Money you owe", owing, "").button("Debts", "wpc", LifePage.args("p", "debts")).button("Mortgage", "wpc", LifePage.args("p", "mortgage"))
            .button("Your will", "wpc", LifePage.args("p", "will"));
        // [wpa] The Town Hall: the town's book, your papers, and your first week.
        page.heading("The Town Hall");
        boolean onBook = Belonging.registered(id, p.getUUID());
        Belonging.Rp rp = Belonging.rp(p.getUUID());
        page.row(new ItemStack(Items.LECTERN), onBook ? (rp == null ? "On " + town + "'s book" : rp.full() + ", of " + town) : "Join " + town,
                onBook ? (rp == null ? "Tell the town who you are: a name of your own, on the Join tab." : "Your papers, your vote, the jury, the treasury and the notices.")
                    : "Sign the town's book at the Town Hall: a resident, a bed if you fall, a name of your own. Free.", "")
            .button("The Town Hall", "hall", null).button("My card", "papers", null, rp != null, "Sign a town's book and give your name first");
        if (FirstWeek.town(p.getUUID()) != null && FirstWeek.next(p.getUUID()) != null) {
            FirstWeek.Step nx = FirstWeek.next(p.getUUID());
            page.row(new ItemStack(nx.icon), "Your first week: " + FirstWeek.count(p.getUUID()) + " of " + FirstWeek.firstEight(), "Next: " + nx.label + ". " + nx.why, "")
                .button("The checklist", "hallweek", null);
        }
        // belonging
        page.heading("Belonging");
        page.buttons().button("The town's council and elections", "council", null).button("Taxes", "say", LifePage.args("t", "What are the taxes here?"))
            .button("Feasts and festivals", "say", LifePage.args("t", "When are the next fireworks?"));
        // what next
        page.heading("What you could do next");
        VillageFolkEntity envoy = Envoys.waitingAt(level, v);
        if (envoy != null && envoy.trip() != null && envoy.trip().errand != null && PlayerLeader.leads(id, p.getUUID())) {
            page.row(new ItemStack(Items.WHITE_BANNER), "An envoy waits: " + envoy.displayNameCap() + " has come " + envoy.trip().errand.purpose, "Your answer as the town's leader", "")
                .button("Yes", "envoy", LifePage.args("yes", true)).button("No", "envoy", LifePage.args("yes", false));
        }
        if (!citizen) page.row(new ItemStack(Items.BLUE_BANNER), "Become a citizen", "A home in the town, a vote, a say: ask any folk \"May I live here?\"", "").button("Ask now", "citizen", null);
        if (job.isEmpty()) page.row(new ItemStack(Items.PAPER), "Find work", "Apply for an opening, or take a shift today", "").button("Jobs board", "jobs", null);
        if (s == null) page.row(new ItemStack(Items.BARREL), "Open a shop", "A building with a counter, a door and a chest: yours, built for you, or bought", "").button("Open a shop", "start", LifePage.args("kind", "shop"));   // [biz25]
        if (firms.isEmpty()) page.row(new ItemStack(Items.WRITABLE_BOOK), "Start a business", "A charter, premises, hands of your own", "").button("Start", "start", null);
        page.row(new ItemStack(Items.EMERALD), "Sell what you carry", "The town's counters buy their trades' goods: the store, the smithy, the café", "").button("Selling prices", "earnings", null);
        // [wpe] the law: your record, any case against you, the jail, the court
        List<Justice.JCase> lawCases = Justice.namedAgainst(p.getUUID());
        page.row(new ItemStack(Items.IRON_BARS), "You and the law", lawCases.isEmpty() ? (Justice.convictions(p.getUUID()).isEmpty() ? "A clean record." : "Your record, and what it costs you.")
            : lawCases.size() + " open case" + (lawCases.size() == 1 ? "" : "s") + " against you: your defence, the court's date.", "").button("Your record", "law", null);
        page.buttons().button("Everything you can do (no commands)", "all", null);
        return page;
    }

    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        switch (action) {
            case "jobs", "shop", "start", "all" -> { PlayerLife.open(p, action, args.contains("kind") ? LifePage.args("kind", args.getString("kind")) : null); return "#open:"; }   // [biz25] the start's kind
            case "shopstep" -> { PlayerLife.open(p, "shop", LifePage.args("tab", args.getString("tab"))); return "#open:"; }   // [biz25]
            case "found" -> { FoundingEasy.open(p, "life"); return "#open:"; }   // [found23]
            case "firm" -> { PlayerLife.open(p, "firm", LifePage.args("firm", args.getString("firm"))); return "#open:"; }
            case "farmpage" -> { PlayerLife.open(p, "farm", LifePage.args("farm", args.getString("farm"))); return "#open:"; }   // [wpb]
            case "earnings" -> { books(p, "Earnings"); return "#close"; }
            case "books" -> { books(p, args.getString("page")); return "#close"; }
            case "property" -> { return PropertyDesk.open(p, args.getString("what"), "life"); }
            case "finances" -> { return finances(p); }
            case "citizen" -> {
                // [ui29] a no is a no: shown in the warning ink, not as if it had worked
                String said = sayToNearest(p, "May I live here?");
                return said.startsWith("!") || v == null || Citizens.is(v.id(), p.getUUID()) ? said : "!" + said;
            }
            case "council" -> { return sayToNearest(p, "Who's on the council?"); }
            case "hall" -> { TownHall.open(p, "join"); return "#open:"; }                // [wpa]
            case "wpc" -> { PlayerLife.open(p, args.getString("p"), null); return "#open:"; }   // [wpc] the property pages
            case "law" -> { PlayerLife.open(p, "record", null); return "#open:"; }      // [wpe]
            case "hallweek" -> { TownHall.open(p, "week"); return "#open:"; }
            case "papers" -> { IdCard.openOwn(p); return "#open:"; }
            case "say" -> { return sayToNearest(p, args.getString("t")); }
            case "buyhouse" -> { return Homes.playerBuys(level, p); }
            case "envoy" -> { return PlayerLife.command(p, "village leader envoy " + (args.getBoolean("yes") ? "yes" : "no")); }
            case "rent" -> { return Homes.playerCollects(level, p); }
            case "let" -> { return Homes.playerLets(level, p, ShopPage.parse(args.getCompound("f").getString("letrent"), 3)); }   // [wpa]
            default -> { return null; }
        }
    }

    /**
     * Your finances (net worth, assets and liabilities, income and spending, bills, a ledger): the one place it is opened
     * from here. [join21] purse-20's screen: FinanceSheet.open sends the page and opens it.
     */
    static String finances(ServerPlayer p) {
        FinanceSheet.open(p);
        return "#open:";
    }

    /** The town's books open at a page. */
    static void books(ServerPlayer p, String pageName) {
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return;
        CompoundTag books = Annals.snapshot(p.serverLevel(), v);
        books.putString("page", pageName);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
    }

    /** Said to the nearest folk, as if in talk: its answer comes back as it always does. */
    static String sayToNearest(ServerPlayer p, String text) {
        VillageFolkEntity best = null;
        double bd = 12 * 12;
        for (VillageFolkEntity f : p.serverLevel().getEntitiesOfClass(VillageFolkEntity.class, p.getBoundingBox().inflate(12))) {
            if (f.isBaby() || f.isSleeping()) continue;
            double d = f.distanceToSqr(p);
            if (d < bd) { bd = d; best = f; }
        }
        if (best == null) return "!Nobody's near enough to ask: find a folk of the town.";
        String said = FolkTalk.answer(best, p, TalkTopic.SAY, text);
        FolkTalk.speak(best, FolkTalk.firstSentence(said));
        return best.displayNameCap() + ": " + said;
    }
}
