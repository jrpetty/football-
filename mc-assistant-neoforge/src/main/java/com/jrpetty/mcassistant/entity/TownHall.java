package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.item.TownHallItems;
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
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * [wpa] The Town Hall (page "hall"): a newcomer's way into the town and a resident's civic life, on one page with its
 * tabs, opened at the hall's desk (TownHallDesk), from Your life in town, the arrival card, any folk's "Where's the
 * Town Hall?" and the U key's buttons. No commands: every button acts here, on the server, through the same code the
 * old commands called.
 * <ul>
 * <li><b>Join:</b> sign the town's book (a resident, the respawn point at the hall), then the identity: a name of the
 *     player's own, a bio, an age, a trade, a look; the papers (CitizenPapersItem); the name over the head on or off; a
 *     change of name for a fee.</li>
 * <li><b>Citizen:</b> the checklist, each part with how far along it is, and the fee asked for before it is paid.</li>
 * <li><b>Duties:</b> the elections (feat-civic's screens, through {@link #ELECTIONS}), a question put to the town (aye or
 *     nay), the jury roll and a juror's verdict (Jury), the militia and the army in the field (Muster.playerJoins), the
 *     court's next sitting.</li>
 * <li><b>Money:</b> taxes owed and paid; the savings club, its loans and the town's bonds before a bank opens
 *     (FolkCredit, TownBonds); premises and property.</li>
 * <li><b>Respect:</b> the reputation card: the title, the score, who knows you, the reasons the witnesses give, the
 *     badges and the record.</li>
 * <li><b>Week:</b> the first week's checklist (FirstWeek).</li>
 * <li><b>Office</b> (a leader's or a knight's): a question put to the town, the minimum wage, the laws, an envoy's
 *     answer; a knight's retinue and manor.</li>
 * </ul>
 * Every action answers on the page: what happened, why, and what's next. A spend over 20 coins, and anything that
 * can't be taken back, is asked for once more first. Signing is done at the desk ("Show me" points the way).
 */
public final class TownHall implements PlayerLife.Page {

    /**
     * The elections' screen: feat-civic's visual elections and ballot, set by that package (player, town): true if it
     * opened its screen. Unset, the hustings page opens.
     */
    public static volatile BiFunction<ServerPlayer, UUID, Boolean> ELECTIONS = null;

    /** The tabs: id and label. Kept short so they fit across the page at GUI scale 3 (ScreenFitTest). */
    public static final String[][] TABS = com.jrpetty.mcassistant.client.HallLayout.TABS;

    /** The amounts the money tab offers. */
    static final String[] AMOUNTS = { "5", "10", "20", "50", "100" };

    // ------------------------------------------------------------------ opening

    /** Open the Town Hall on a tab. */
    public static void open(ServerPlayer p, String tab) {
        PlayerLife.open(p, "hall", LifePage.args("tab", tab.isEmpty() ? "join" : tab));
    }

    /** A click on the hall's desk: the page, on the tab a newcomer wants first. */
    public static void openAtDesk(ServerPlayer p, @Nullable BlockPos desk) {
        Villages.Village v = PlayerLife.here(p);
        String tab = v == null || !Belonging.registered(v.id(), p.getUUID()) || Belonging.rp(p.getUUID()) == null ? "join"
            : Jury.calledTo(p.getUUID()) >= 0 ? "duties" : "citizen";
        open(p, tab);
    }

    /** "Show me": the arrow to a place, with how far. */
    static String showMe(ServerPlayer p, @Nullable BlockPos at, String label) {
        if (at == null) return "!There's nowhere to point you yet.";
        PlayerLife.mark(p, at, label);
        int d = (int) Math.sqrt(p.blockPosition().distSqr(at));
        return d <= 4 ? label + ": you're there." : "The arrow points to " + label.toLowerCase(Locale.ROOT).replaceFirst("^the ", "the ") + ", " + d + " blocks away.";
    }

    // ------------------------------------------------------------------ the page

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) {
            LifePage page = new LifePage("hall", "The Town Hall", "No town near: walk into one", p.registryAccess());
            page.text("A town's hall keeps its book, its papers, its notices and its treasury. Walk into a town and come back here.", 4);
            return page;
        }
        UUID id = v.id();
        String town = Villages.name(id);
        boolean hasHall = TownHallDesk.hall(id) != null;
        BlockPos desk = TownHallDesk.desk(level, id);
        String sub;
        if (desk != null) {
            ServicePoints.Point pt = ServicePoints.nearest(level, id, ServiceRole.HALL, desk);
            sub = ServicePoints.status(level, id, ServiceRole.HALL, pt).words();
        } else sub = hasHall ? "The desk is being set in: the elder keeps the book meanwhile" : "No hall yet: the elder keeps the town's book";
        LifePage page = new LifePage("hall", town + " Town Hall", sub, p.registryAccess());
        boolean office = PlayerLeader.leads(id, p.getUUID()) || Knights.isKnight(id, p.getUUID());
        if (c.tab.isEmpty()) c.tab = "join";
        if (c.tab.equals("office") && !office) c.tab = "duties";
        for (String[] t : TABS) {
            if (t[0].equals("office") && !office) continue;
            page.tab(t[0], t[1], c.tab.equals(t[0]));
        }
        confirmBox(page, c);
        switch (c.tab) {
            case "citizen" -> citizenTab(p, c, v, page);
            case "duties" -> dutiesTab(p, c, v, page);
            case "money" -> moneyTab(p, c, v, page);
            case "respect" -> respectTab(p, v, page);
            case "week" -> { hint(p, page, "hall.week", "Your first week: eight small steps, each teaching one thing about the town, each paying a coin or two. Skip any you like."); FirstWeek.rows(p, page, v, true); }
            case "office" -> officeTab(p, c, v, page);
            default -> joinTab(p, c, v, page);
        }
        page.buttons().button("Show me the Town Hall", "show", null).button("Your life in town", "life", null).button("The Guide", "guide", null);
        return page;
    }

    /** A first-time hint at the top of a tab, till the player says it has it. */
    static void hint(ServerPlayer p, LifePage page, String id, String text) {
        if (Belonging.hintSeen(p.getUUID(), id)) return;
        page.row(new ItemStack(Items.LANTERN), "New here?", text, "").button("Got it", "hint", LifePage.args("id", id));
    }

    /** The question asked before a spend over 20 coins or a step that can't be taken back. */
    static void confirmBox(LifePage page, PlayerLife.Ctx c) {
        if (!c.args.contains("ask")) return;
        CompoundTag ask = c.args.getCompound("ask");
        page.heading("Are you sure?");
        page.text(ask.getString("q"), 2);
        page.buttons().button(ask.getString("yes"), "confirm", null).primary().button("Cancel", "cancel", null);
    }

    /** Ask first: the action and its arguments kept till the player says yes. Returns "" (the page shows the question). */
    static String ask(PlayerLife.Ctx c, String action, CompoundTag args, String question, String yes) {
        CompoundTag a = new CompoundTag();
        a.putString("a", action);
        a.put("args", args.copy());
        a.putString("q", question);
        a.putString("yes", yes);
        c.args.put("ask", a);
        return "";
    }

    // ------------------------------------------------------------------ Join

    private void joinTab(ServerPlayer p, PlayerLife.Ctx c, Villages.Village v, LifePage page) {
        UUID id = v.id(), me = p.getUUID();
        String town = Villages.name(id);
        boolean reg = Belonging.registered(id, me);
        boolean here = TownHallDesk.atDesk(p, v);
        String whyNot = TownHallDesk.whyNotHere(p, v);
        hint(p, page, "hall.join", "This is where you join " + town + ": sign its book, tell it who you are, and collect your papers. Nothing here costs a coin the first time.");
        Belonging.Rp rp = Belonging.rp(me);
        page.heading(reg ? "On " + town + "'s book" : "Join " + town);
        if (!reg) {
            page.text("Signing the town's book makes you a resident: you wake at the hall if you fall, the folk count you one of theirs, "
                + "and the way to citizenship opens. It's free.", 0);
            page.row(new ItemStack(Items.WRITABLE_BOOK), "Sign the town's book", here ? "The clerk's pen is on the desk." : "At the Town Hall desk.", "")
                .button("Join village", "register", null, here, whyNot).primary()
                .button("Show me", "show", null);
            return;
        }
        page.row(new ItemStack(Items.WRITABLE_BOOK), "A resident since day " + Belonging.registeredDay(id, me),
            Citizens.is(id, me) ? "And a citizen." : "Citizenship is the next step: see the Citizen tab.", "");
        boolean edit = rp == null || c.args.getBoolean("edit");
        if (edit) {
            page.heading(rp == null ? "Who are you?" : "Change your details");
            page.text(rp == null ? "Your name in town: your own, not your username. Others see it over your head and in chat, and the folk use it. "
                + "Letters only; nobody else's name." : "A new name costs " + PlayerLife.coins(Belonging.RENAME_FEE) + " at the desk and the old one stays on record. "
                + "The rest is free to change.", 4);
            page.field("first", "First name", rp == null ? "" : rp.first(), Belonging.NAME_MAX);
            page.field("last", "Last name", rp == null ? "" : rp.last(), Belonging.NAME_MAX);
            page.field("bio", "A line about you", rp == null ? "" : rp.bio(), Belonging.BIO_MAX);
            String[] ages = { "0", "18", "21", "25", "30", "35", "40", "45", "50", "60", "70" };
            String[] ageWords = new String[ages.length];
            for (int i = 0; i < ages.length; i++) ageWords[i] = ages[i].equals("0") ? "Not said" : ages[i];
            page.choice("age", "Age", ages, ageWords, rp == null ? "0" : nearest(ages, rp.age()));
            String[] tradeWords = new String[Belonging.TRADES.length];
            for (int i = 0; i < tradeWords.length; i++) tradeWords[i] = Belonging.TRADES[i].isEmpty() ? "Not said" : cap(Belonging.TRADES[i]);
            page.choice("trade", "Trade", Belonging.TRADES, tradeWords, rp == null ? "" : rp.trade());
            String[] lookWords = new String[Belonging.LOOKS.length];
            for (int i = 0; i < lookWords.length; i++) lookWords[i] = cap(Belonging.LOOKS[i]);
            page.choice("look", "Portrait", Belonging.LOOKS, lookWords, rp == null ? "plain" : rp.look());
            LifePage.Row r = page.buttons().button(rp == null ? "Write it in" : "Save my details", "identity", null).primary();
            if (rp != null) r.button("Cancel", "editoff", null);
            return;
        }
        page.heading("Your papers");
        String about = (rp.age() > 0 ? rp.age() + ", " : "") + (rp.trade().isEmpty() ? "" : rp.trade() + ", ") + "in town since day " + rp.since();
        page.row(new ItemStack(TownHallItems.CITIZEN_PAPERS.get()), rp.full(),
            about + (rp.bio().isEmpty() ? "" : ". \"" + rp.bio() + "\""), "")
            .button("See my card", "card", null).button("Change details", "editon", null);
        long issued = Belonging.papersDay(id, me);
        boolean carrying = TownHallItems.carriesPapers(p, id);
        page.row(new ItemStack(TownHallItems.CITIZEN_PAPERS.get()), issued < 0 ? "Collect your papers" : carrying ? "You carry your papers" : "Your papers are not on you",
            issued < 0 ? "Free: an ID card with your name, town, standing and job. Right-click it to show it." : carrying ? "Issued on day " + issued + "."
                : "A replacement costs " + PlayerLife.coins(Belonging.PAPERS_FEE) + ".", "")
            .button(issued < 0 ? "Collect" : "Replace", "papers", null, here && (issued < 0 || !carrying), !here ? whyNot : "You carry them already");
        page.row(new ItemStack(Items.NAME_TAG), rp.plate() ? "Your name shows over your head" : "Your name over your head is hidden",
            rp.plate() ? "Others see " + rp.full() + " above you and in chat." : "Others see your username.", "")
            .button(rp.plate() ? "Hide it" : "Show it", "plate", LifePage.args("on", !rp.plate()));
    }

    static String nearest(String[] vals, int n) {
        String best = vals[0];
        int bd = Integer.MAX_VALUE;
        for (String v : vals) {
            int d = Math.abs(Integer.parseInt(v) - n);
            if (d < bd) { bd = d; best = v; }
        }
        return best;
    }

    static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ Citizen

    private void citizenTab(ServerPlayer p, PlayerLife.Ctx c, Villages.Village v, LifePage page) {
        ServerLevel level = p.serverLevel();
        UUID id = v.id(), me = p.getUUID();
        String town = Villages.name(id);
        hint(p, page, "hall.citizen", "A citizen has a vote on the council and in the town's questions, may stand for office and sit on a jury, gets a tenth off at the shop and the café, and may take from the stores.");
        if (Citizens.is(id, me)) {
            page.heading("A citizen of " + town);
            page.text("You have a vote on the council and on every question put to the town. You may stand for office, sit on the jury, take from the stores, "
                + "and you pay a tenth less at the shop and the café.", 1);
            page.buttons().button("Duties: votes, jury, militia", "tab", LifePage.args("tab", "duties")).button("Elections", "elections", null);
            return;
        }
        Standing.View view = Standing.of(id, me, level.getGameTime());
        boolean reg = Belonging.registered(id, me);
        boolean friend = view.title().atLeast(Standing.Title.FRIEND);
        int owes = Laws.owes(id, me);
        boolean banned = Laws.banished(id, me, level.getDayTime() / 24000L);
        com.jrpetty.mcassistant.village.Chronicle.Guest guest = com.jrpetty.mcassistant.village.Chronicle.guest(id, me);
        int fee = guest != null && guest.built ? 0 : Citizens.FEE;
        int coins = Market.coinsHeld(p);
        page.heading("Becoming a citizen of " + town);
        int ok = (reg ? 1 : 0) + (friend ? 1 : 0) + (owes == 0 && !banned ? 1 : 0) + (coins >= fee ? 1 : 0);
        page.bar("How close you are", ok / 4.0, ok + " of 4");
        page.row(new ItemStack(reg ? Items.LIME_DYE : Items.GRAY_DYE), (reg ? "Done: " : "") + "On the town's book", reg ? "Since day " + Belonging.registeredDay(id, me) + "."
            : "Sign it on the Join tab (free).", "").button("Join", "tab", LifePage.args("tab", "join"), !reg, "Done");
        page.row(new ItemStack(friend ? Items.LIME_DYE : Items.GRAY_DYE), (friend ? "Done: " : "") + "A friend of the town",
            friend ? "The town counts you " + view.title().words + "." : "The town's warmth to you is " + view.score() + "; a friend at 12. Talk with folk each day, "
                + "bring a gift, do what they ask.", friend ? "" : view.score() + " / 12")
            .button("Respect", "tab", LifePage.args("tab", "respect"));
        page.row(new ItemStack(owes == 0 && !banned ? Items.LIME_DYE : Items.GRAY_DYE), (owes == 0 && !banned ? "Done: " : "") + "No fines owed",
            banned ? "You're banished for now." : owes > 0 ? "You owe the town " + PlayerLife.coins(owes) + " in fines. Pay any folk: \"I'll pay my fine\"." : "Your record is clean.", "")
            .button("Pay my fine", "say", LifePage.args("t", "I'll pay my fine"), owes > 0, owes > 0 ? "" : "Nothing owed");
        page.row(new ItemStack(coins >= fee ? Items.LIME_DYE : Items.GRAY_DYE), (coins >= fee ? "Ready: " : "") + (fee == 0 ? "No fee: the town built you a house" : "The fee: " + PlayerLife.coins(fee)),
            fee == 0 ? "" : "To the treasury, once. You carry " + PlayerLife.coins(coins) + ".", "");
        String why = !reg ? "Sign the town's book first (Join tab)." : !friend ? "The town doesn't count you a friend yet." : owes > 0 ? "Pay your fines first."
            : banned ? "Not while you're banished." : coins < fee ? "You need " + PlayerLife.coins(fee) + " on you." : TownHallDesk.whyNotHere(p, v);
        page.buttons().button(fee == 0 ? "Apply for citizenship" : "Apply (" + fee + "c)", "citizen", null, why.isEmpty(), why).primary();
    }

    // ------------------------------------------------------------------ Duties

    private void dutiesTab(ServerPlayer p, PlayerLife.Ctx c, Villages.Village v, LifePage page) {
        ServerLevel level = p.serverLevel();
        UUID id = v.id(), me = p.getUUID();
        long day = level.getDayTime() / 24000L;
        boolean citizen = Citizens.is(id, me), reg = Belonging.registered(id, me);
        hint(p, page, "hall.duties", "The town's notices: what's put to a vote, the jury, the militia and the court. Citizens vote and sit on juries; any resident may drill with the militia.");
        // the jury: a call first, it's the most pressing
        int called = Jury.sittingOn(me);
        if (called >= 0) {
            page.heading("Jury duty");
            for (String line : Jury.hearing(called)) page.text(line, 0);
            boolean open = Jury.calledTo(me) >= 0;
            page.buttons().button("Guilty", "verdict", LifePage.args("case", called, "g", true), open, "You've given your verdict")
                .button("Not guilty", "verdict", LifePage.args("case", called, "g", false), open, "You've given your verdict")
                .button("Show me the court", "showcourt", LifePage.args("case", called));
        }
        // elections
        page.heading("Elections");
        page.text(Elections.line(id, day), 0);
        page.buttons().button("Elections and the ballot", "elections", null).primary()
            .button("Stand for election", "say", LifePage.args("t", "I'd like to stand for election"), citizen, "Only a citizen may stand");
        // a question put to the town
        List<CompoundTag> qs = Referendums.open(id);
        page.heading("Put to the town");
        if (qs.isEmpty()) page.text("Nothing is put to the town just now. The leader's questions and the great works come here when they're called.", 4);
        for (CompoundTag q : qs) {
            CompoundTag mine = q.getCompound("players").getCompound(me.toString());
            boolean voted = mine.contains("aye");
            long when = q.getLong("vote");
            Planner.add(p, when * 24000L + 12000L, "vote", "The town votes on " + q.getString("title"), VillageBoards.lectern(id));
            String why = !citizen ? "Only citizens vote. See the Citizen tab." : "";
            page.row(new ItemStack(Items.PAPER), q.getString("title"), (when <= day ? "The vote is today, at the board this evening." : "The vote is on day " + when + ".")
                + (voted ? " You voted " + (mine.getBoolean("aye") ? "aye." : "nay.") : ""), "")
                .button("Aye", "vote", LifePage.args("aye", true), citizen, why).button("Nay", "vote", LifePage.args("aye", false), citizen, why);
        }
        // the jury roll
        page.heading("The jury");
        boolean roll = Belonging.onJuryRoll(id, me);
        page.row(new ItemStack(Items.WRITABLE_BOOK), roll ? "Your name is on the jury roll" : "The jury roll",
            roll ? "When the council sits on a case and you're in town, you may be called to hear it and give your verdict." : "Citizens hear the town's cases beside the council. Each juror's verdict counts as a councillor's.", "")
            .button(roll ? "Take my name off" : "Put my name down", "jury", LifePage.args("on", !roll), citizen || roll, "Only citizens sit on a jury");
        String court = Trial.boardLine(level, id);
        if (court != null && !court.isEmpty()) page.text("The court: " + court, 4);
        // the militia and the army
        page.heading("The militia");
        boolean militia = Belonging.inMilitia(id, me);
        page.row(new ItemStack(Items.IRON_SWORD), militia ? "You drill with the militia" : "The militia",
            militia ? "When the bell rings you're told, and the arrow points to the square where the town gathers." : "Residents who answer the bell: when raiders come, the town gathers at the square and the militia stands with the watch.", "")
            .button(militia ? "Leave the militia" : "Join the militia", "militia", LifePage.args("on", !militia), reg || militia, "Sign the town's book first");
        if (Muster.called(id)) page.row(new ItemStack(Items.BELL), "The bell is ringing", "The town is gathering at the square.", "").button("Show me the square", "showsquare", null).primary();
        Campaigns.Campaign war = Campaigns.ofTown(id);
        if (war != null) {
            page.row(new ItemStack(Items.SHIELD), "The town's army is in the field", citizen ? "Join it as a volunteer." : "Join it as a sword for hire: " + PlayerLife.coins(Muster.HIRE) + " from the treasury.", "")
                .button("Join the army", "army", null).primary();
        }
    }

    // ------------------------------------------------------------------ Money

    private void moneyTab(ServerPlayer p, PlayerLife.Ctx c, Villages.Village v, LifePage page) {
        ServerLevel level = p.serverLevel();
        UUID id = v.id(), me = p.getUUID();
        long day = level.getDayTime() / 24000L;
        hint(p, page, "hall.money", "Taxes are paid here. Till the town has a bank, the hall keeps its savings club too: put coin by, borrow against it, and lend to the town on its bonds.");
        page.heading("Taxes");
        int owed = TaxBook.owedBy(id, me);
        long next = TaxBook.nextTaxDay(id, day);
        Planner.add(p, next * 24000L + 6000L, "tax", "Tax day in " + Villages.name(id), TownHallDesk.showMe(level, v));
        page.row(new ItemStack(Items.GOLD_NUGGET), owed > 0 ? "You owe " + PlayerLife.coins(owed) + " in taxes" : "No taxes owed",
            owed > 0 ? "Three weeks unpaid and the town thinks less of you. You carry " + PlayerLife.coins(Market.coinsHeld(p)) + "." : "Next tax day: day " + next + ".", "")
            .button("Pay now", "tax", null, owed > 0 && Market.coinsHeld(p) > 0, owed <= 0 ? "Nothing owed" : "You carry no coin");
        page.heading(FolkCredit.bank(id) ? "Savings: the bank" : "The savings club");
        if (FolkCredit.bank(id)) {
            page.text("The town has a bank: your savings, loans and mortgage are kept at its desk.", 0);
            List<ServicePoints.Point> bank = ServicePoints.of(level, id, ServiceRole.BANK);
            page.buttons().button("Show me the bank", "showbank", null, !bank.isEmpty(), "The bank's desk isn't set in yet");
        } else {
            page.text(FolkCredit.playerLine(p, id), 0);
            page.text(TownBonds.playerLine(p, id), 0);
            page.choice("coins", "How much", AMOUNTS, coinsWords(), "10");
            page.buttons().button("Save", "save", null).button("Withdraw", "withdraw", null).button("Borrow", "borrow", null).button("Repay", "repay", null);
            page.buttons().button("Buy bonds", "bonds", null).button("Collect what's due", "collect", null);
            page.text("Bonds lend the town coin at the day's rate; coupons wait for you here. A loan is half of what you hold with the town, at most.", 4);
        }
        page.heading("Premises and property");
        page.text("Shops, workshops and houses: rent, buy, sell and let.", 4);
        page.buttons().button("Premises", "premises", null).button("The property office", "property", null);
        page.buttons().button("Land", "wpc", LifePage.args("p", "land")).button("Your debts", "wpc", LifePage.args("p", "debts"))   // [wpc]
            .button("Your will", "wpc", LifePage.args("p", "will")).button("Home", "wpc", LifePage.args("p", "home"));
    }

    static String[] coinsWords() {
        String[] w = new String[AMOUNTS.length];
        for (int i = 0; i < w.length; i++) w[i] = AMOUNTS[i] + " coins";
        return w;
    }

    // ------------------------------------------------------------------ Respect

    private void respectTab(ServerPlayer p, Villages.Village v, LifePage page) {
        ServerLevel level = p.serverLevel();
        UUID id = v.id(), me = p.getUUID();
        hint(p, page, "hall.respect", "Respect is what the folk who saw you, or dealt with you, make of you, and what they tell others. It sets prices, greetings and the work you're offered.");
        Standing.View view = Standing.of(id, me, level.getGameTime());
        String rating = TownOpinion.ratingOf(id, me, level.getGameTime());
        page.heading("How " + Villages.name(id) + " sees you");
        page.row(new ItemStack(Items.PLAYER_HEAD), "You are " + view.title().words, view.knownBy() + (view.knownBy() == 1 ? " folk knows" : " folk know") + " you"
            + (view.bestFriend().isEmpty() ? "" : "; your best friend here is " + view.bestFriend())
            + (view.worstCritic().isEmpty() ? "" : "; " + view.worstCritic() + " thinks little of you"), rating);
        page.bar("Warmth", Math.max(0, Math.min(1, (view.score() + 100) / 200.0)), (view.score() > 0 ? "+" : "") + view.score());
        TownOpinion.View seen = TownOpinion.of(id, me);
        page.heading("Why: what the witnesses say");
        if (seen.reasons().isEmpty()) page.text("Nobody has seen you do anything worth telling yet. Respect comes from those who were there.", 4);
        for (int i = 0; i < Math.min(6, seen.reasons().size()); i++) {
            TownOpinion.Reason r = seen.reasons().get(i);
            page.text((r.good() ? "+ " : "- ") + TownOpinion.line(r), r.good() ? 1 : 3);
        }
        page.text(seen.witnesses() + " saw for themselves; " + seen.heard() + " only heard.", 4);
        page.heading("Badges and record");
        String best = Citizens.best(p);
        page.text(best == null ? "No title yet." : "Your title: " + best + ".", 0);
        String badges = IdCard.badges(id, me).trim();
        if (!badges.isEmpty()) page.text(badges, 0);
        int offences = Laws.offences(id, me), owes = Laws.owes(id, me);
        page.text(offences == 0 ? "A clean record here." : offences + (offences == 1 ? " offence" : " offences") + " on record" + (owes > 0 ? "; " + PlayerLife.coins(owes) + " in fines owed." : "."), offences == 0 ? 1 : 2);
        page.heading("What lifts it, what lowers it");
        page.text("Lifts: talking with folk each day, gifts they like, work done well, a raid fought off, taxes paid, a jury served on.", 4);
        page.text("Lowers: theft, damage, fights, broken promises, unpaid fines. Folk only count what they saw or were told.", 4);
    }

    // ------------------------------------------------------------------ Office

    private void officeTab(ServerPlayer p, PlayerLife.Ctx c, Villages.Village v, LifePage page) {
        ServerLevel level = p.serverLevel();
        UUID id = v.id(), me = p.getUUID();
        if (PlayerLeader.leads(id, me)) {
            hint(p, page, "hall.office", "The leader's desk: put a question to the town, set the minimum wage and the laws, and answer envoys. The town votes on what you put to it.");
            page.heading("Put a question to the town");
            List<String> qv = new ArrayList<>(), ql = new ArrayList<>();
            qv.add("");
            ql.add("Your own words (below)");
            for (String w : Villages.projectsWanted(id)) { qv.add("should we build " + Villages.spoken(w)); ql.add("Build " + Villages.spoken(w) + "?"); if (qv.size() > 6) break; }
            for (Orders.Order o : Orders.Order.values()) { qv.add(o.title.toLowerCase(Locale.ROOT)); ql.add(o.title + "?"); }
            page.choice("q", "The question", qv.toArray(new String[0]), ql.toArray(new String[0]), qv.size() > 1 ? qv.get(1) : "");
            page.field("qtext", "Or in your words", "", 80);
            page.buttons().button("Put it to the town", "referendum", null).primary();
            page.heading("The minimum wage");
            int min = LabourMarket.minimumWage(id), living = LabourMarket.live(id) ? LabourMarket.livingWage(id) : 0;
            page.text("Now " + PlayerLife.coins(min) + " a day" + (living > 0 ? " (the living wage, the least it can be, is " + PlayerLife.coins(living) + ")" : "") + ".", 0);
            String[] w = { "0", "4", "6", "8", "10", "12", "15", "20" };
            String[] ww = new String[w.length];
            for (int i = 0; i < w.length; i++) ww[i] = w[i].equals("0") ? "None (the living wage)" : w[i] + " coins a day";
            page.choice("wage", "Set it to", w, ww, nearest(w, min));
            page.buttons().button("Set the minimum wage", "minwage", null, LabourMarket.live(id), "The labour market opens with the town's market");
            page.heading("The plan, the tithe and the wages");
            String plan = com.jrpetty.mcassistant.village.Ledger.note(id, "civic.plan");
            page.text("Tithe: " + PlayerLeader.titheRate(id) + " in the hundred. Wages: " + PlayerLeader.wageRate(id) + " in the hundred of the going rate.", 0);
            String[] plans = { "steward", "food", "growth", "defence", "trade", "steady" };
            String[] planWords = { "Left to the steward", "Food first", "Growth", "Defence", "Trade", "Steady" };
            page.choice("plan", "The plan", plans, planWords, plan == null || plan.isEmpty() ? "steward" : plan);
            page.buttons().button("Set the plan", "plan", null);
            page.buttons().button("Tithe -2", "tithe", LifePage.args("d", -2)).button("Tithe +2", "tithe", LifePage.args("d", 2))
                .button("Wages -5", "wages", LifePage.args("d", -5)).button("Wages +5", "wages", LifePage.args("d", 5));
            page.heading("The laws");
            boolean book = Identity.known(id) != null;
            if (!book) page.text("The town's law-book is written on its first morning; the laws can be changed after that.", 4);
            for (LawBook.Law l : LawBook.Law.values()) {
                int now = LawBook.value(id, l);
                int nx = (now + 1) % l.options.length;
                page.row(new ItemStack(Items.BOOK), cap(l.name) + ": " + l.option(now), "Next: " + l.option(nx) + ".", "")
                    .button("Change", "law", LifePage.args("law", l.name(), "n", nx), book, "The law-book is written on the town's first morning");
            }
            VillageFolkEntity envoy = Envoys.waitingAt(level, v);
            if (envoy != null && envoy.trip() != null && envoy.trip().errand != null) {
                page.heading("An envoy waits");
                page.row(new ItemStack(Items.WHITE_BANNER), envoy.displayNameCap() + " has come " + envoy.trip().errand.purpose, "Your answer is the town's, given at the board.", "")
                    .button("Yes", "envoy", LifePage.args("yes", true)).button("No", "envoy", LifePage.args("yes", false));
            }
            page.buttons().button("The leader's page", "leaderpage", null);
        }
        if (Knights.isKnight(id, me)) {
            page.heading("Your knighthood");
            page.text("Service: " + Knights.service(id, me) + " of " + Knights.SERVICE + ". Your retinue: " + Retinue.size(id, me) + " of " + Retinue.MOST
                + " (the town pays for " + Retinue.TOWN_PAID + "; more cost " + PlayerLife.coins(Retinue.WAGE) + " a day each).", 0);
            page.buttons().button("Call my retinue", "retinue", LifePage.args("w", "call")).button("Stand them down", "retinue", LifePage.args("w", "dismiss"));
            page.buttons().button("Hire a man (" + Retinue.WAGE + "c a day)", "retinue", LifePage.args("w", "hire"))
                .button("Release them all", "retinue", LifePage.args("w", "release"));
            page.buttons().button("Where's my manor?", "manor", LifePage.args("grand", false)).button("A grander manor", "manor", LifePage.args("grand", true));
        }
    }

    // ------------------------------------------------------------------ the buttons

    @Nullable
    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (action.equals("confirm")) {
            if (!c.args.contains("ask")) return "!Nothing to confirm.";
            CompoundTag a = c.args.getCompound("ask");
            c.args.remove("ask");
            CompoundTag next = a.getCompound("args").copy();
            next.putBoolean("sure", true);
            if (args.contains("f")) next.put("f", args.getCompound("f"));
            return act(p, c, a.getString("a"), next);
        }
        if (action.equals("cancel")) { c.args.remove("ask"); return "Left as it was."; }
        c.args.remove("ask");
        if (v == null) return "!No town near.";
        UUID id = v.id(), me = p.getUUID();
        CompoundTag f = args.getCompound("f");
        switch (action) {
            case "tab" -> { c.tab = args.getString("tab"); return ""; }
            case "hint" -> { Belonging.dismissHint(me, args.getString("id")); return ""; }
            case "show" -> { return showMe(p, TownHallDesk.showMe(level, v), TownHallDesk.showMeLabel(level, v)); }
            case "life" -> { PlayerLife.open(p, "life", null); return "#open:"; }
            case "guide" -> { return "The Guide (its button at the top of this page) has the Town Hall, citizenship, the jury and the first week under Town government."; }
            case "say" -> { return LifeTown.sayToNearest(p, args.getString("t")); }
            case "card" -> { IdCard.openOwn(p); return "#open:"; }
            case "editon" -> { c.args.putBoolean("edit", true); return ""; }
            case "editoff" -> { c.args.remove("edit"); return ""; }
            // ---------------------------------------------------------------- join
            case "register" -> {
                if (!TownHallDesk.atDesk(p, v)) return "!" + TownHallDesk.whyNotHere(p, v);
                String said = Belonging.register(p, v);
                return said;
            }
            case "identity" -> { return identity(p, c, v, f, args.getBoolean("sure")); }
            case "papers" -> { return papers(p, c, v, args.getBoolean("sure")); }
            case "plate" -> { return Belonging.setPlate(p, args.getBoolean("on")); }
            // ---------------------------------------------------------------- citizen
            case "citizen" -> {
                if (Citizens.is(id, me)) return "You're a citizen already.";
                if (!Belonging.registered(id, me)) return "!Sign the town's book first, on the Join tab.";
                if (!TownHallDesk.atDesk(p, v)) return "!" + TownHallDesk.whyNotHere(p, v);
                com.jrpetty.mcassistant.village.Chronicle.Guest guest = com.jrpetty.mcassistant.village.Chronicle.guest(id, me);
                int fee = guest != null && guest.built ? 0 : Citizens.FEE;
                if (fee > 0 && !args.getBoolean("sure")) return ask(c, "citizen", new CompoundTag(), "Pay " + PlayerLife.coins(fee) + " to "
                    + Villages.name(id) + "'s treasury for citizenship? It isn't refunded.", "Yes, pay " + fee + "c");
                String said = Citizens.apply(level, id, p);
                if (Citizens.is(id, me)) { FirstWeek.check(p); return said + " Next: the Duties tab has your vote and the jury roll."; }
                return "!" + said;
            }
            // ---------------------------------------------------------------- duties
            case "elections" -> {
                BiFunction<ServerPlayer, UUID, Boolean> hook = ELECTIONS;
                if (hook != null && Boolean.TRUE.equals(hook.apply(p, id))) return "#open:";
                Hustings.openPage(p);
                return "#open:";
            }
            case "vote" -> {
                String said = Referendums.playerVote(level, v, p, args.getBoolean("aye"));
                return said.startsWith("Your vote") ? said : "!" + said;
            }
            case "jury" -> {
                boolean on = args.getBoolean("on");
                if (on && !Citizens.is(id, me)) return "!Only citizens sit on a jury. See the Citizen tab.";
                Belonging.setJuryRoll(id, me, on);
                return on ? "Your name is on the jury roll. When the council sits on a case while you're in town, you may be called: the notice comes here."
                    : "Your name is off the jury roll.";
            }
            case "verdict" -> { return Jury.vote(p, args.getInt("case"), args.getBoolean("g")); }
            case "showcourt" -> {
                BlockPos at = Jury.court(args.getInt("case"));
                return showMe(p, at, "The court");
            }
            case "militia" -> {
                boolean on = args.getBoolean("on");
                if (on && !Belonging.registered(id, me)) return "!Sign the town's book first: the militia is the town's own.";
                Belonging.setMilitia(id, me, on);
                return on ? "You're in the militia. When the bell rings you'll be told, and the arrow will point to the square." : "You've left the militia.";
            }
            case "showsquare" -> { return showMe(p, v.centre(), "The square"); }
            case "army" -> {
                if (!args.getBoolean("sure")) return ask(c, "army", new CompoundTag(), "Join the town's army in the field? You'll fight in its line till the campaign ends.", "Yes, join");
                return Muster.playerJoins(level, v, p);
            }
            // ---------------------------------------------------------------- money
            case "tax" -> {
                int owed = TaxBook.owedBy(id, me);
                if (owed <= 0) return "!You owe no taxes.";
                if (owed > 20 && !args.getBoolean("sure")) return ask(c, "tax", new CompoundTag(), "Pay " + PlayerLife.coins(Math.min(owed, Market.coinsHeld(p))) + " of taxes now?", "Yes, pay");
                return TaxBook.pay(level, id, p);
            }
            case "save", "withdraw", "borrow", "repay", "bonds" -> {
                int n = ShopPage.parse(f.getString("coins"), 10);
                boolean spend = action.equals("save") || action.equals("bonds") || action.equals("repay");
                if (spend && n > 20 && !args.getBoolean("sure")) {
                    CompoundTag keep = new CompoundTag();
                    keep.put("f", f.copy());
                    return ask(c, action, keep, switch (action) {
                        case "save" -> "Put " + PlayerLife.coins(n) + " into the savings club?";
                        case "bonds" -> "Lend the town " + PlayerLife.coins(n) + " on its bonds? It comes back over weeks, with coupons.";
                        default -> "Repay " + PlayerLife.coins(n) + " of your loan?";
                    }, "Yes, " + n + "c");
                }
                if (action.equals("borrow") && !args.getBoolean("sure")) {
                    CompoundTag keep = new CompoundTag();
                    keep.put("f", f.copy());
                    return ask(c, action, keep, "Borrow up to " + PlayerLife.coins(n) + "? Interest runs up weekly; four weeks behind and your savings are taken against it.", "Yes, borrow");
                }
                return switch (action) {
                    case "save" -> FolkCredit.playerDeposits(level, p, id, n);
                    case "withdraw" -> FolkCredit.playerWithdraws(level, p, id, n);
                    case "borrow" -> FolkCredit.playerBorrows(level, p, id, n);
                    case "repay" -> FolkCredit.playerRepays(level, p, id, n);
                    default -> TownBonds.playerBuys(level, p, id, n);
                };
            }
            case "collect" -> { return TownBonds.playerCollects(p, id); }
            case "showbank" -> {
                List<ServicePoints.Point> bank = ServicePoints.of(level, id, ServiceRole.BANK);
                return bank.isEmpty() ? "!The bank's desk isn't set in yet." : showMe(p, bank.get(0).pos(), "The bank's desk");
            }
            case "premises" -> {
                if (PlayerLife.has("premises")) { PlayerLife.open(p, "premises", null); return "#open:"; }
                return PropertyDesk.open(p, "orders", "hall");
            }
            case "property" -> { return PropertyDesk.open(p, "orders", "hall"); }
            case "wpc" -> { PlayerLife.open(p, args.getString("p"), null); return "#open:"; }   // [wpc] land, debts, the will, home
            // ---------------------------------------------------------------- office
            case "referendum" -> {
                String q = f.getString("qtext").trim();
                if (q.isEmpty()) q = f.getString("q");
                if (q.isEmpty()) return "!Choose a question, or write one.";
                if (!args.getBoolean("sure")) {
                    CompoundTag keep = new CompoundTag();
                    keep.put("f", f.copy());
                    return ask(c, "referendum", keep, "Put \"" + q + "\" to the whole town? Once called, the vote goes ahead.", "Yes, put it");
                }
                return PlayerLeader.referendum(level, p, q);
            }
            case "minwage" -> {
                if (!PlayerLeader.leads(id, me)) return "!Only the town's leader sets its minimum wage.";
                int n = ShopPage.parse(f.getString("wage"), 0);
                return LabourMarket.setMinimum(id, n, Belonging.called(p));
            }
            case "law" -> {
                if (!PlayerLeader.leads(id, me)) return "!Only the town's leader sets its laws.";
                LawBook.Law l = LawBook.Law.named(args.getString("law"));
                if (l == null) return "!No such law.";
                int n = args.getInt("n");
                if (!args.getBoolean("sure")) return ask(c, "law", LifePage.args("law", l.name(), "n", n), "Make it the law: " + l.name + ", " + l.option(n) + "? The town will feel it.", "Yes, decree it");
                return LawBook.set(level, v, l, n, cap(Government.title(id)) + " " + Belonging.called(p) + " decreed it");
            }
            case "envoy" -> { return PlayerLeader.envoy(level, p, args.getBoolean("yes")); }
            case "plan" -> { return PlayerLeader.plan(level, p, f.getString("plan").isEmpty() ? "steward" : f.getString("plan")); }
            case "tithe" -> {
                int to = Math.max(PlayerLeader.TITHE_LEAST, Math.min(PlayerLeader.TITHE_MOST, PlayerLeader.titheRate(id) + args.getInt("d")));
                return PlayerLeader.tithe(level, p, to);
            }
            case "wages" -> {
                int to = Math.max(PlayerLeader.WAGES_LEAST, Math.min(PlayerLeader.WAGES_MOST, PlayerLeader.wageRate(id) + args.getInt("d")));
                return PlayerLeader.wages(level, p, to);
            }
            case "leaderpage" -> { PlayerLeader.openPage(p); return "#open:"; }
            case "retinue" -> {
                String w = args.getString("w");
                if (w.equals("hire") && !args.getBoolean("sure")) return ask(c, "retinue", LifePage.args("w", "hire"), "Hire another man for your retinue at " + PlayerLife.coins(Retinue.WAGE) + " a day, paid ahead?", "Yes, hire");
                if (w.equals("release") && !args.getBoolean("sure")) return ask(c, "retinue", LifePage.args("w", "release"), "Release your whole retinue? They go back to the watch for good.", "Yes, release");
                return switch (w) {
                    case "dismiss" -> Retinue.dismiss(level, id, p);
                    case "hire" -> Retinue.hire(level, id, p);
                    case "release" -> Retinue.release(level, id, me, "released");
                    default -> Retinue.call(level, id, p);
                };
            }
            case "manor" -> {
                VillageFolkEntity folk = null;
                for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity x && !x.isBaby()) { folk = x; break; }
                if (folk == null) return "!There's nobody of the town to ask.";
                boolean grand = args.getBoolean("grand");
                if (grand && !args.getBoolean("sure")) return ask(c, "manor", LifePage.args("grand", true), "Pay for a grander manor? The builders quote first; the coin goes when they start.", "Yes, ask them");
                String said = CivicLand.talk(folk, p, grand ? "I'll pay for a grander manor" : "Where's my manor?");
                return said == null ? "!You have no land here." : said;
            }
            // ---------------------------------------------------------------- the week
            case "week" -> {
                String a = args.getString("a");
                if (a.startsWith("tab:")) { c.tab = a.substring(4); return ""; }
                if (a.startsWith("life:")) { PlayerLife.open(p, a.substring(5), null); return "#open:"; }
                if (a.startsWith("say:")) return LifeTown.sayToNearest(p, a.substring(4));
                return null;
            }
            case "weekshow" -> {
                FirstWeek.Step s;
                try { s = FirstWeek.Step.valueOf(args.getString("s")); } catch (IllegalArgumentException e) { return "!No such step."; }
                UUID t = FirstWeek.town(me);
                Villages.Village wv = t == null ? v : Villages.get(t);
                if (wv == null) wv = v;
                StringBuilder label = new StringBuilder();
                BlockPos at = FirstWeek.where(level, wv, s, label);
                return showMe(p, at, label.toString());
            }
            case "weekskip" -> {
                FirstWeek.skip(me, !FirstWeek.skipped(me));
                return FirstWeek.skipped(me) ? "The first week stays on this page; no more word of it in chat." : "The first week's word is back in chat.";
            }
            default -> { return null; }
        }
    }

    /** Write the identity in (the first free and anywhere in the town; a new name later at the desk, for the fee). */
    private static String identity(ServerPlayer p, PlayerLife.Ctx c, Villages.Village v, CompoundTag f, boolean sure) {
        UUID me = p.getUUID();
        if (!Belonging.registered(v.id(), me) && Belonging.townsOf(me).isEmpty()) return "!Sign the town's book first: the town gives you a name once you live here.";
        Belonging.Rp rp = Belonging.rp(me);
        String first = Belonging.tidy(f.getString("first")), last = Belonging.tidy(f.getString("last"));
        if (first.isEmpty() || last.isEmpty()) return "!A first name and a last name, both.";
        boolean rename = rp != null && !(rp.first() + " " + rp.last()).equals(first + " " + last);
        if (rename) {
            if (!TownHallDesk.atDesk(p, v)) return "!A new name is written in at the Town Hall desk: press Show me and walk there.";
            String why = Belonging.whyNotName(p.server, me, first, last);
            if (why != null) return "!" + why;
            if (Market.coinsHeld(p) < Belonging.RENAME_FEE) return "!A new name costs " + PlayerLife.coins(Belonging.RENAME_FEE) + ". You carry " + PlayerLife.coins(Market.coinsHeld(p)) + ".";
            if (!sure) {
                CompoundTag keep = new CompoundTag();
                keep.put("f", f.copy());
                return ask(c, "identity", keep, "Change your name from " + rp.full() + " to " + first + " " + last + " for " + PlayerLife.coins(Belonging.RENAME_FEE)
                    + "? The old name stays on record.", "Yes, " + Belonging.RENAME_FEE + "c");
            }
        }
        int age = ShopPage.parse(f.getString("age"), 0);
        String said = Belonging.setIdentity(p, first, last, f.getString("bio"), age, f.getString("trade"), f.getString("look"));
        if (said.startsWith("!")) return said;
        if (rename) {
            Market.payOut(p, Belonging.RENAME_FEE);
            com.jrpetty.mcassistant.village.Ledger.addCoins(v.id(), Belonging.RENAME_FEE);
            CoinCensus.playerIn(v.id(), Belonging.RENAME_FEE, p, PlayerFinance.Cat.FEES, "a change of name at the Town Hall");
        }
        c.args.remove("edit");
        if (rp == null && Belonging.papersDay(v.id(), me) < 0 && TownHallDesk.atDesk(p, v)) {
            TownHallItems.issue(p, v);
            return said + " Your papers are in your pack: right-click them to show them.";
        }
        return said + (rp == null ? " Next: collect your papers at the desk." : "");
    }

    /** Papers issued: the first free, a replacement for a fee. */
    private static String papers(ServerPlayer p, PlayerLife.Ctx c, Villages.Village v, boolean sure) {
        UUID me = p.getUUID();
        if (Belonging.rp(me) == null) return "!Tell the town who you are first: your papers carry your name.";
        if (!TownHallDesk.atDesk(p, v)) return "!" + TownHallDesk.whyNotHere(p, v);
        boolean first = Belonging.papersDay(v.id(), me) < 0;
        if (!first && TownHallItems.carriesPapers(p, v.id())) return "!You carry your papers already.";
        if (!first) {
            if (Market.coinsHeld(p) < Belonging.PAPERS_FEE) return "!Replacement papers cost " + PlayerLife.coins(Belonging.PAPERS_FEE) + ".";
            Market.payOut(p, Belonging.PAPERS_FEE);
            com.jrpetty.mcassistant.village.Ledger.addCoins(v.id(), Belonging.PAPERS_FEE);
            CoinCensus.playerIn(v.id(), Belonging.PAPERS_FEE, p, PlayerFinance.Cat.FEES, "replacement papers at the Town Hall");
        }
        TownHallItems.issue(p, v);
        return (first ? "Your papers, free: in your pack." : "Replacement papers, " + PlayerLife.coins(Belonging.PAPERS_FEE) + ": in your pack.")
            + " Right-click them to show your card.";
    }

    // ------------------------------------------------------------------ the talk menu and the folk

    /** The talk screen's flags for the hall's options: " registered", " referendum" (a vote open, a citizen yet to vote), " juror". */
    public static String contextFlags(UUID village, ServerPlayer p) {
        StringBuilder sb = new StringBuilder();
        if (Belonging.registered(village, p.getUUID())) sb.append(" registered");
        if (Citizens.is(village, p.getUUID())) {
            for (CompoundTag q : Referendums.open(village)) {
                if (!q.getCompound("players").contains(p.getUUID().toString())) { sb.append(" referendum"); break; }
            }
        }
        if (Jury.calledTo(p.getUUID()) >= 0) sb.append(" juror");
        return sb.toString();
    }

    /** "Where's the Town Hall?" and the like, said to a folk: the page opens, the folk says a word. Null for anything else. */
    @Nullable
    public static String heard(VillageFolkEntity f, ServerPlayer p, String t) {
        if (t.contains("town hall") || t.contains("sign the town's book") || t.contains("join the village")) {
            boolean registered = f.ownerId() != null && Belonging.registered(f.ownerId(), p.getUUID());
            open(p, registered ? "citizen" : "join");
            String town = f.ownerId() == null ? "the town" : Villages.name(f.ownerId());
            return FolkTalk.pick(f.getRandom(), "The hall keeps " + town + "'s book. Here, I'll show you what's what.",
                "Signing on? Good. The clerk's desk is in the hall, just inside.", "The Town Hall's where you'll want to be. I'll point the way.",
                "Everything official goes through the hall. Have a look.", "The book, the papers, the notices: all at the hall.",
                "You'll want the hall. Mind the queue, there never is one.");
        }
        if (t.contains("jury duty")) {
            open(p, "duties");
            return FolkTalk.pick(f.getRandom(), "Jury, is it? The notices are at the hall.", "The court likes a fair hearing. Go and give yours.",
                "Listen to all of it before you make up your mind.", "Hear them out. That's all a jury's for.", "The council waits on the jury. Don't keep them long.",
                "A juror? Then you'll want the hall's notices.");
        }
        if (t.contains("my papers") || t.contains("my id card")) {
            IdCard.openOwn(p);
            return FolkTalk.pick(f.getRandom(), "Let's have a look.", "All in order, I'm sure.", "Papers, please. Only joking.", "Very official.",
                "That's you, is it? Fair likeness.", "Keep those somewhere dry.");
        }
        return null;
    }

    /** The bell: every player in the town's militia who is about is told, and the arrow points to the square. Returns how many. */
    public static int militiaCalled(ServerLevel level, Villages.Village v, BlockPos square) {
        int n = 0;
        for (ServerPlayer p : level.players()) {
            if (!Belonging.inMilitia(v.id(), p.getUUID())) continue;
            if (p.blockPosition().distSqr(v.centre()) > 9.0 * Villages.VILLAGE_RANGE * Villages.VILLAGE_RANGE) continue;
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal("The bell! " + Villages.name(v.id())
                + "'s militia gathers at the square: the arrow points the way.").withStyle(net.minecraft.ChatFormatting.GOLD));
            CompoundTag t = new CompoundTag();
            CompoundTag m = new CompoundTag();
            m.putIntArray("at", new int[]{ square.getX(), square.getY(), square.getZ() });
            m.putString("l", "The square: the muster");
            t.put("marker", m);
            t.putBoolean("close", true);
            com.jrpetty.mcassistant.net.LifeNetwork.send(p, t);
            Planner.add(p, level.getDayTime(), "militia", "The bell: the militia at the square", square);
            n++;
        }
        return n;
    }

    /** Tests: a question put to the town, as the council would put one, voted on that day. Returns its id. */
    public static int questionForTests(ServerLevel level, Villages.Village v, String title, long voteDay) {
        return Referendums.call(level, v, "TEST", "test", title, "the council", "", "", "", voteDay, null).getInt("id");
    }

    /** Tests: the tab labels as drawn, in order (every one, the Office's too). */
    public static List<String> tabLabels() {
        List<String> out = new ArrayList<>();
        for (String[] t : TABS) out.add(t[1]);
        return out;
    }
}
