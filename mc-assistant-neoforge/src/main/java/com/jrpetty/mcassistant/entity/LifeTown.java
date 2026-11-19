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
        LifePage page = new LifePage("life", "Your life in town", v == null ? "You're out in the wild: find a town" : "In " + town, p.registryAccess());
        if (v == null) {
            page.text("Towns grow wherever folk settle. Walk till you find one, then come back here.", 4);
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
        if (s == null) {
            page.row(new ItemStack(com.jrpetty.mcassistant.McAssistantMod.SHOP_COUNTER_ITEM.get()), "No shop yet",
                    "Build a room, set a Shop Counter in it, and folk will come to buy", "").button("How to open one", "shop", null);
        } else {
            PlayerShops.roll(s, day);
            page.row(new ItemStack(com.jrpetty.mcassistant.McAssistantMod.SHOP_COUNTER_ITEM.get()), s.name + ": " + PlayerShops.stateWords(level, s),
                    "Till " + PlayerLife.coins(s.till) + "; this week " + PlayerLife.coins(PlayerShops.week(s.weekCoin)) + " for " + PlayerShops.week(s.weekCount)
                        + " sold; " + ShopPage.words(s.rep) + (s.keeper == null ? "" : "; kept by " + s.keeperName), "")
                .button("Open its screen", "shop", null);
            page.buttons().button("Sell", "property", LifePage.args("what", "sell")).button("Let", "property", LifePage.args("what", "let"))
                .button("Repurpose", "property", LifePage.args("what", "repurpose"));
        }
        // businesses
        page.heading("Your businesses");
        List<Business> firms = MyBusinesses.mine(id, p.getUUID());
        if (firms.isEmpty()) page.text("None yet.", 4);
        for (Business b : firms) page.row(null, b.name, Enterprise.line(level, id, b), "").button("Run it", "firm", LifePage.args("firm", b.id));
        page.buttons().button("Start a business", "start", null).button("The town's businesses", "books", LifePage.args("page", "Businesses"));
        // building
        List<BuildDesk.Status> builds = BuildDesk.mine(id, p.getUUID());
        if (!builds.isEmpty()) {
            page.heading("Going up for you");
            for (BuildDesk.Status b : builds) page.bar(b.title(), b.percent() / 100.0, b.done() ? "finished" : b.percent() + "%" + (b.waitingOn().isEmpty() ? "" : ": waiting for " + b.waitingOn()));
        }
        // home
        page.heading("Home");
        int[] houses = Homes.playerHouses(id, p.getUUID());
        page.row(new ItemStack(Items.RED_BED), houses[0] == 0 ? "No house of your own here" : "You own " + houses[0] + (houses[0] == 1 ? " house" : " houses") + " here, worth " + PlayerLife.coins(houses[1]),
                houses[0] == 0 ? "Stand in an empty house and buy it, or ask what's for sale" : "Let it to a household, or collect the rent", "")
            .button("What's for sale?", "say", LifePage.args("t", "Is there a house for sale?"))
            .button("Buy the one I'm in", "buyhouse", null)
            .button("Collect rent", "rent", null, houses[0] > 0, "You own no house here");
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
        if (s == null) page.row(new ItemStack(Items.BARREL), "Open a shop", "Any building of yours with a counter, a door and a chest", "").button("How", "shop", null);
        if (firms.isEmpty()) page.row(new ItemStack(Items.WRITABLE_BOOK), "Start a business", "A charter, premises, hands of your own", "").button("Start", "start", null);
        page.row(new ItemStack(Items.EMERALD), "Sell what you carry", "The town's counters buy their trades' goods: the store, the smithy, the café", "").button("Selling prices", "earnings", null);
        page.buttons().button("Everything you can do (no commands)", "all", null);
        return page;
    }

    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        switch (action) {
            case "jobs", "shop", "start", "all" -> { PlayerLife.open(p, action, null); return "#open:"; }
            case "firm" -> { PlayerLife.open(p, "firm", LifePage.args("firm", args.getString("firm"))); return "#open:"; }
            case "earnings" -> { books(p, "Earnings"); return "#close"; }
            case "books" -> { books(p, args.getString("page")); return "#close"; }
            case "property" -> { return PropertyDesk.open(p, args.getString("what"), "life"); }
            case "finances" -> { return finances(p); }
            case "citizen" -> { return sayToNearest(p, "May I live here?"); }
            case "council" -> { return sayToNearest(p, "Who's on the council?"); }
            case "say" -> { return sayToNearest(p, args.getString("t")); }
            case "buyhouse" -> { return Homes.playerBuys(level, p); }
            case "envoy" -> { return PlayerLife.command(p, "village leader envoy " + (args.getBoolean("yes") ? "yes" : "no")); }
            case "rent" -> { return Homes.playerCollects(level, p); }
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
