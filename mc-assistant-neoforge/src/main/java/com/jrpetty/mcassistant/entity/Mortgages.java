package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [wpc] A player's mortgage, as a bank would write one (page "mortgage", and the bank's desk): it replaces the old fixed
 * terms (a fifth down, four in the hundred a week, ten weeks) with real ones.
 * <ul>
 * <li><b>The deposit</b> is chosen: the loan may be 60, 75, 80 or 90 in the hundred of the price (its loan-to-value, LTV);
 *     the more put down, the lower the rate.</li>
 * <li><b>The rate</b>, a week, by the LTV band (from 2.5 in the hundred at 60 to 4 at 90), a little less when the bank's
 *     vault is flush and more when it is stretched, and by the borrower's credit score (Debts).</li>
 * <li><b>The term</b>, four to twenty-four weeks; the weekly payment the bank's own sum (Bank.payment).</li>
 * <li><b>Affordability</b>: the payment no more than a third of what the borrower earned a week over the last two weeks.</li>
 * <li><b>Arrears</b>: the bank's round takes the payment from the account each seventh day; a payment missed brings a letter
 *     (an item, and the planner); three behind and the bank takes the house, sells it to the town, and what it fetched over
 *     what was owed comes back to the borrower's account.</li>
 * <li><b>Overpay</b> any time; <b>remortgage</b> to a new term and today's rate; <b>port</b> the loan to another house (the old
 *     sold to the town, the equity carried over).</li>
 * </ul>
 * The loan itself is on the bank's books (Bank.Loan) and paid on its round; the terms are kept here (PropertyBook "mortgages").
 */
public final class Mortgages implements PlayerLife.Page {

    /** LTV bands, in the hundred; terms, in weeks. */
    static final int[] LTV = { 60, 75, 80, 90 };
    static final int[] TERMS = { 4, 8, 12, 16, 20, 24 };
    /** A remortgage's fee, coins. */
    static final int REMORTGAGE_FEE = 2;
    /** The lowest score the bank lends to. */
    static final int LEAST_SCORE = 30;

    static CompoundTag book(UUID village) {
        return PropertyBook.town(village, "mortgages");
    }

    /** The week's rate for a player's loan (Bank's round), in hundredths of a percent. */
    static int rateFor(UUID village, long anchor, int fallback) {
        CompoundTag t = book(village).getCompound(Long.toString(anchor));
        return t.contains("bp") ? t.getInt("bp") : fallback;
    }

    /** The rate a week at this score and LTV here, hundredths of a percent: the band, the vault, the score. */
    static int rateBp(UUID village, int score, int ltv) {
        int bp = ltv <= 0 ? 300 : ltv <= 60 ? 250 : ltv <= 75 ? 300 : ltv <= 80 ? 350 : 400;
        if (Bank.open(village)) {
            Bank.State s = Bank.state(village);
            int dep = Bank.deposits(s);
            int free = Bank.lendable(s);
            if (dep > 0 && free * 2 > dep) bp -= 25;                         // a flush vault lends cheaper
            else if (dep > 0 && free * 6 < dep) bp += 50;                    // a stretched one dearer
        }
        bp += score >= 80 ? -25 : score >= 60 ? 0 : score >= 45 ? 50 : 100;
        return Math.max(150, bp);
    }

    /** What a player earned a week here over the last two weeks, coins. */
    static int weeklyEarnings(ServerLevel level, UUID village, UUID who) {
        try {
            java.util.Map<PlayerFinance.Cat, Long> m = PlayerFinance.spanTown(who, village.toString(), PropertyBook.day(level), 14);
            long pence = 0;
            for (var e : m.entrySet()) if (e.getKey().kind == PlayerFinance.Kind.INCOME) pence += Math.max(0, e.getValue());
            return (int) (pence / 100 / 2);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The terms for a house: price, deposit, lent, rate, weekly, total interest; and why not ("" if the bank would). */
    record Terms(int price, int deposit, int lent, int bp, int weekly, int interest, int ltv, int term, int earns, String why) {}

    static Terms terms(ServerLevel level, UUID village, ServerPlayer p, int price, int ltv, int term, int equity) {
        UUID me = p.getUUID();
        int lent = Math.max(0, Math.min(price - equity, price * ltv / 100));
        int deposit = Math.max(0, price - lent - equity);
        int score = Debts.score(level, village, me).score();
        int bp = rateBp(village, score, ltv);
        int weekly = lent <= 0 ? 0 : Bank.payment(lent, bp, term);
        int interest = Math.max(0, weekly * term - lent);
        int earns = weeklyEarnings(level, village, me);
        String why = "";
        boolean citizen = Citizens.is(village, me);
        if (!Bank.open(village)) why = "the town has no bank yet";
        else if (!citizen && !Standing.of(village, me, level.getGameTime()).title().atLeast(Standing.Title.FRIEND)) why = "the bank lends to the town's friends: be one first";
        else if (Insolvency.bankrupt(village, me)) why = "you're bankrupt till your discharge";
        else if (score < LEAST_SCORE) why = "your credit score is " + score + "; the bank wants " + LEAST_SCORE;
        else if (Market.coinsHeld(p) + PropertyBook.account(village, me) < deposit) why = "the deposit is " + deposit + "; you have " + (Market.coinsHeld(p) + PropertyBook.account(village, me));
        else if (weekly * 3 > earns && lent > 0) why = "the payment (" + weekly + " a week) is more than a third of what you earn (" + earns + " a week over the last fortnight)";
        else if (lent > Bank.lendable(Bank.state(village))) why = "the bank hasn't " + lent + " to lend just now";
        return new Terms(price, deposit, lent, bp, weekly, interest, ltv, term, earns, why);
    }

    /** The town's houses for sale: empty, the town's, not a flat or the leader's hall. */
    static List<Homes.Home> forSale(UUID village) {
        Homes.enrol(village);
        List<Homes.Home> out = new ArrayList<>();
        for (Homes.Home h : Homes.homes(village).values()) {
            if (!h.members.isEmpty() || h.tenure == Homes.Tenure.PLAYER || Homes.seat(h) || Flats.isFlat(h) || Tenancy.held(h.anchor)) continue;
            out.add(h);
        }
        out.sort((a, b) -> Integer.compare(Homes.price(village, a), Homes.price(village, b)));
        return out;
    }

    static int priceFor(UUID village, UUID me, Homes.Home h) {
        return (int) Math.round(Homes.price(village, h) * (Citizens.is(village, me) ? 1.0 : 1.25));
    }

    /** Take the mortgage: the deposit paid, the bank's loan for the rest, the house the player's, the keys and the deed. */
    static String take(ServerLevel level, ServerPlayer p, Villages.Village v, long anchor, int ltv, int term, boolean sure, PlayerLife.Ctx c) {
        UUID id = v.id(), me = p.getUUID();
        Homes.Home h = Homes.homes(id).get(anchor);
        if (h == null || !forSale(id).contains(h)) return "!That house isn't for sale now.";
        if (Bank.playerLoan(id, me) != null) return "!One mortgage at a time: overpay or port the one you have.";
        String barred = LawBook.housesBarred(id, Citizens.is(id, me));
        if (barred != null) return "!" + barred;
        int price = priceFor(id, me, h);
        Terms t = terms(level, id, p, price, ltv, term, 0);
        if (!t.why().isEmpty()) return "!The bank won't lend on those terms: " + t.why() + ".";
        String where = Homes.address(id, v, h);
        if (!sure) return PropertyBook.ask(c, "take", LifePage.args("house", Long.toString(anchor), "ltv", ltv, "term", term),
            "Buy " + where + " for " + price + ": " + t.deposit() + " down now, " + t.lent() + " lent at " + TownBonds.pct(t.bp()) + " a week, " + t.weekly() + " a week for "
                + term + " weeks from your account (" + t.interest() + " in interest all told). Three payments behind and the bank takes the house (what it fetches over "
                + "what you owe comes back to you).", "Take the mortgage");
        int paid = PropertyBook.pay(p, id, t.deposit(), PlayerFinance.Cat.INVESTED, "a house bought: the deposit", true);
        if (paid < t.deposit()) return "!You couldn't find the " + t.deposit() + " deposit. Nothing was paid.";
        Bank.State s = Bank.state(id);
        s.cash -= t.lent();
        Ledger.addCoins(id, t.lent());
        Economy.houseSold(id, price);
        long day = PropertyBook.day(level);
        h.tenure = Homes.Tenure.PLAYER;
        h.landlord = me;
        h.landlordName = Belonging.called(p);
        h.price = price;
        h.rent = 0;
        h.toLet = false;
        h.since = day;
        Homes.save(id, h);
        Bank.Loan l = new Bank.Loan(anchor);
        l.player = true;
        l.borrowers.add(me);
        l.names = Belonging.called(p);
        l.price = price;
        l.down = t.deposit();
        l.lent = t.lent();
        l.principal = t.lent();
        l.weekly = t.weekly();
        l.term = term;
        l.start = day;
        s.week.lent += t.lent();
        s.week.loans++;
        if (t.lent() > 0) Bank.keepLoan(id, l);
        CompoundTag m = new CompoundTag();
        m.putInt("bp", t.bp());
        m.putInt("ltv", ltv);
        m.putInt("term", term);
        m.putLong("start", day);
        book(id).put(Long.toString(anchor), m);
        PropertyBook.dirty();
        ItemStack key = new ItemStack(Items.TRIPWIRE_HOOK);
        key.set(DataComponents.CUSTOM_NAME, Component.literal("Key to " + where));
        if (!p.getInventory().add(key)) p.drop(key, false);
        PropertyItems.deed(p, id, "house", Long.toString(anchor), where);
        Villages.tell(id, day, Belonging.called(p) + " bought " + where + " for " + price + ": " + t.deposit() + " down and " + t.lent() + " on a mortgage from the bank");
        Planner.add(p, (day + 7) * 24000L + 6000, "mortgage", "Mortgage payment: " + t.weekly() + " from your account", h.anchor);
        FirstWeek.check(p);
        return "It's yours: " + where + ". " + t.deposit() + " down, " + t.lent() + " lent at " + TownBonds.pct(t.bp()) + " a week; " + t.weekly() + " a week comes out of your account "
            + "on the bank's round for " + term + " weeks. The keys and the deed are in your pack. Next: sleep in its bed to make it home, or let it from the Home page.";
    }

    /** Pay extra off the mortgage now, out of the pack and account. */
    static String overpay(ServerLevel level, ServerPlayer p, UUID village, int n) {
        Bank.Loan l = Bank.playerLoan(village, p.getUUID());
        if (l == null) return "!You've no mortgage here.";
        n = Math.min(n <= 0 ? l.owed() : n, l.owed());
        int got = PropertyBook.pay(p, village, n, PlayerFinance.Cat.LOAN_PAY, "mortgage overpaid", false);
        if (got <= 0) return "!You've no coin on you or in your account.";
        Ledger.takeCoins(village, got);
        Bank.payLoan(village, l, got);
        if (l.owed() <= 0) {
            book(village).remove(Long.toString(l.anchor));
            PropertyBook.dirty();
            return "Paid off. The house is yours outright: no more payments.";
        }
        l.weekly = Math.min(l.weekly, l.owed());
        return "Overpaid " + got + ": " + l.owed() + " still owed, " + l.weeksLeft() + " weeks to go at " + l.weekly + " a week.";
    }

    /** A new term at today's rate for what is owed (a small fee). */
    static String remortgage(ServerLevel level, ServerPlayer p, UUID village, int term, boolean sure, PlayerLife.Ctx c) {
        Bank.Loan l = Bank.playerLoan(village, p.getUUID());
        if (l == null) return "!You've no mortgage to remortgage.";
        Homes.Home h = Homes.homes(village).get(l.anchor);
        int value = h == null ? l.price : Homes.price(village, h);
        int ltv = (int) Math.min(90, Math.ceil(100.0 * l.owed() / Math.max(1, value)));
        int band = 90;
        for (int b : LTV) if (ltv <= b) { band = b; break; }
        int bp = rateBp(village, Debts.score(level, village, p.getUUID()).score(), band);
        int weekly = Bank.payment(l.owed(), bp, term);
        if (weekly * 3 > weeklyEarnings(level, village, p.getUUID())) return "!At " + weekly + " a week that's more than a third of what you earn: choose a longer term.";
        if (!sure) return PropertyBook.ask(c, "remortgage", LifePage.args("term", term), "Remortgage what you owe (" + l.owed() + ", " + ltv + "% of the house's value) at "
            + TownBonds.pct(bp) + " a week over " + term + " weeks: " + weekly + " a week? The bank's fee is " + REMORTGAGE_FEE + ".", "Remortgage");
        if (PropertyBook.pay(p, village, REMORTGAGE_FEE, PlayerFinance.Cat.FEES, "the bank's remortgage fee", true) < REMORTGAGE_FEE) return "!You haven't the " + REMORTGAGE_FEE + " fee.";
        l.weekly = weekly;
        l.term = term;
        l.principal += l.interest;
        l.interest = 0;
        l.start = PropertyBook.day(level);
        Bank.keepLoan(village, l);
        CompoundTag m = book(village).getCompound(Long.toString(l.anchor));
        m.putInt("bp", bp);
        m.putInt("ltv", band);
        m.putInt("term", term);
        book(village).put(Long.toString(l.anchor), m);
        PropertyBook.dirty();
        return "Remortgaged: " + weekly + " a week for " + term + " weeks at " + TownBonds.pct(bp) + ". Next payment on the bank's round.";
    }

    /** The loan carried to another house: the old sold to the town, its equity put toward the new. */
    static String port(ServerLevel level, ServerPlayer p, Villages.Village v, long to, boolean sure, PlayerLife.Ctx c) {
        UUID id = v.id(), me = p.getUUID();
        Bank.Loan l = Bank.playerLoan(id, me);
        if (l == null) return "!You've no mortgage to carry over.";
        Homes.Home old = Homes.homes(id).get(l.anchor), next = Homes.homes(id).get(to);
        if (old == null || next == null || !forSale(id).contains(next)) return "!That house isn't for sale now.";
        int sale = Homes.price(id, old);
        int equity = Math.max(0, sale - l.owed());
        int price = priceFor(id, me, next);
        int need = Math.max(0, price - equity);
        int ltv = (int) Math.ceil(100.0 * need / Math.max(1, price));
        if (ltv > 90) return "!The new house needs " + need + " lent, " + ltv + "% of its price: the bank goes to 90%. Choose a cheaper house or overpay first.";
        int band = 90;
        for (int b : LTV) if (ltv <= b) { band = b; break; }
        int bp = rateBp(id, Debts.score(level, id, me).score(), band);
        int term = Math.max(4, l.weeksLeft());
        int weekly = need <= 0 ? 0 : Bank.payment(need, bp, term);
        if (weekly * 3 > weeklyEarnings(level, id, me)) return "!" + weekly + " a week would be more than a third of what you earn.";
        String from = Homes.address(id, v, old), where = Homes.address(id, v, next);
        if (!sure) return PropertyBook.ask(c, "port", LifePage.args("house", Long.toString(to)), "Sell " + from + " to the town for " + sale + " (" + equity + " of it yours after the "
            + l.owed() + " owed) and carry the mortgage to " + where + " (" + price + "): " + need + " lent at " + TownBonds.pct(bp) + ", " + weekly + " a week for " + term + " weeks?", "Port it");
        long day = PropertyBook.day(level);
        // the old house to the town; the bank paid off out of its price
        old.tenure = Homes.Tenure.RENTED;
        old.landlord = null;
        old.landlordName = "";
        old.toLet = false;
        old.price = sale;
        old.rent = Homes.rent(id, old);
        old.since = day;
        Homes.save(id, old);
        Bank.Loan now = new Bank.Loan(to);
        now.player = true;
        now.borrowers.add(me);
        now.names = l.names;
        now.price = price;
        now.down = 0;
        now.lent = need;
        now.principal = need;
        now.weekly = weekly;
        now.term = term;
        now.start = day;
        // the price moves through the treasury: the old house bought by it, the new sold by it; the bank's loan carried over
        int bankDelta = need - l.owed();
        Bank.State s = Bank.state(id);
        s.cash -= bankDelta;
        Ledger.addCoins(id, bankDelta);
        if (need > 0) Bank.rekey(id, l, now);
        else { Bank.payLoan(id, l, 0); l.principal = 0; l.interest = 0; Bank.payLoan(id, l, 0); }
        next.tenure = Homes.Tenure.PLAYER;
        next.landlord = me;
        next.landlordName = Belonging.called(p);
        next.price = price;
        next.rent = 0;
        next.toLet = false;
        next.since = day;
        Homes.save(id, next);
        book(id).remove(Long.toString(l.anchor));
        CompoundTag m = new CompoundTag();
        m.putInt("bp", bp);
        m.putInt("ltv", band);
        m.putInt("term", term);
        m.putLong("start", day);
        book(id).put(Long.toString(to), m);
        PropertyBook.dirty();
        PropertyItems.deed(p, id, "house", Long.toString(to), where);
        Villages.tell(id, day, Belonging.called(p) + " moved from " + from + " to " + where + ", the mortgage carried over");
        return "Ported: " + from + " is the town's now, and your mortgage is on " + where + " (" + need + " owed, " + weekly + " a week). The new deed is in your pack.";
    }

    // ------------------------------------------------------------------ the bank's seams

    /** A payment missed (Bank.warn): a letter in the pack and the payment on the planner. */
    static void arrearsLetter(ServerLevel level, Villages.Village v, Bank.Loan l, boolean last) {
        UUID who = l.borrowers.isEmpty() ? null : l.borrowers.get(0);
        if (who == null) return;
        Debts.history(v.id(), who, PropertyBook.day(level), last ? -8 : -4, last ? "a mortgage's last warning" : "a mortgage payment missed");
        ServerPlayer p = PropertyBook.online(level, who);
        if (p == null) return;
        ItemStack s = new ItemStack(Items.PAPER);
        s.set(DataComponents.CUSTOM_NAME, Component.literal((last ? "Final notice" : "Arrears letter") + ": your mortgage, " + l.arrears + " behind"));
        s.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
            Component.literal(l.missed + (l.missed == 1 ? " payment" : " payments") + " missed; " + l.weekly + " a week"),
            Component.literal("Put coin in your account before the bank's next round"),
            Component.literal(last ? "One more and the bank takes the house" : "Three behind and the bank takes the house"))));
        if (!p.getInventory().add(s)) p.drop(s, false);
        Planner.add(p, (PropertyBook.day(level) + 7) * 24000L + 6000, "arrears", "The bank's round: " + (l.weekly + l.arrears) + " due on your mortgage", null);
    }

    /** The bank took the house back (Bank.foreclose): what it fetches over what was owed goes to the borrower's account. */
    static void repossessed(ServerLevel level, Villages.Village v, Bank.Loan l, @Nullable Homes.Home h) {
        UUID id = v.id();
        UUID who = l.borrowers.isEmpty() ? null : l.borrowers.get(0);
        book(id).remove(Long.toString(l.anchor));
        PropertyBook.dirty();
        if (who == null || h == null) return;
        int fetched = Homes.price(id, h) * Insolvency.FIRE_SALE / 100;
        int surplus = fetched - l.owed();
        Debts.history(id, who, PropertyBook.day(level), -15, "a house repossessed");
        if (surplus <= 0) return;
        int k = Ledger.takeCoins(id, surplus);
        if (k > 0) {
            PropertyBook.putAccount(id, who, l.names, k);
            PlayerFinance.internal(who, id, k * 100L, PlayerFinance.Cat.CASHED, "what the repossessed house fetched over the mortgage");
            PropertyBook.tell(level, who, "The house fetched " + fetched + "; after the " + l.owed() + " you owed, " + k + " is in your bank account.");
        }
    }

    /** Sold by the receiver: the bank paid so much out of its price (from the treasury); anything still owed written off. */
    static void closeBySale(ServerLevel level, Villages.Village v, Bank.Loan l, int toBank) {
        UUID id = v.id();
        int k = Ledger.takeCoins(id, toBank);
        Bank.payLoan(id, l, k);
        if (l.owed() > 0) { l.principal = 0; l.interest = 0; Bank.payLoan(id, l, 0); }
        book(id).remove(Long.toString(l.anchor));
        PropertyBook.dirty();
    }

    // ------------------------------------------------------------------ the page ("mortgage")

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) {
            LifePage page = new LifePage("mortgage", "Mortgages", "No town near", p.registryAccess());
            page.text("A mortgage is the town's bank's. Walk into a town with a bank.", 4);
            return page;
        }
        UUID id = v.id(), me = p.getUUID();
        Bank.Loan l = Bank.playerLoan(id, me);
        LifePage page = new LifePage("mortgage", "Mortgages at " + Villages.name(id) + "'s bank", Bank.open(id) ? (l == null ? "Buy a house with the bank's help" : "Your mortgage: " + l.owed() + " owed")
            : "No bank in town yet", p.registryAccess());
        if (c.tab.isEmpty()) c.tab = l == null ? "get" : "mine";
        page.tab("get", "Get a mortgage", c.tab.equals("get")).tab("mine", "Your mortgage", c.tab.equals("mine")).tab("how", "How it works", c.tab.equals("how"));
        PropertyBook.confirmBox(page, c);
        switch (c.tab) {
            case "mine" -> mineTab(level, p, v, c, page, l);
            case "how" -> howTab(level, p, v, page);
            default -> getTab(level, p, v, c, page, l);
        }
        page.buttons().button("Your debts", "debts", null).button("Your home", "home", null).button("Back to your life in town", "life", null);
        return page;
    }

    void getTab(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page, @Nullable Bank.Loan l) {
        UUID id = v.id(), me = p.getUUID();
        PropertyBook.hint(p, page, "mortgage.get", "Choose a house, then how much to put down: the more you put down, the cheaper the rate. The bank checks the payment "
            + "is no more than a third of what you earn.");
        if (!Bank.open(id)) { page.text("The town has no bank yet: it builds one at thirty folk in the Iron Age. Till then a house is bought outright on the Home page.", 2); return; }
        if (l != null) { page.text("You have a mortgage already. Port it to another house on the Your mortgage tab.", 2); }
        List<Homes.Home> sale = forSale(id);
        if (sale.isEmpty()) { page.text("No house of the town's is empty and for sale just now. The builders put them up as the town grows; or have one built (the builder).", 4); return; }
        String chosen = c.args.getString("house");
        page.heading("Houses for sale");
        int shown = 0;
        for (Homes.Home h : sale) {
            if (shown++ >= 6) break;
            int price = priceFor(id, me, h);
            boolean on = chosen.equals(Long.toString(h.anchor.asLong()));
            page.row(new ItemStack(Items.OAK_DOOR), (on ? "✔ " : "") + capital(Homes.address(id, v, h)), Homes.bedsIn(level, id, h).size() + " beds; " + (Citizens.is(id, me) ? "a citizen's price" : "a quarter over, as you're not a citizen"),
                    PropertyBook.coins(price))
                .button(on ? "Chosen" : "Choose", "house", LifePage.args("house", Long.toString(h.anchor.asLong())), !on, "Chosen")
                .button("Show me", "show", LifePage.args("house", Long.toString(h.anchor.asLong())));
        }
        Homes.Home h = chosen.isEmpty() ? null : Homes.homes(id).get(Long.parseLong(chosen));
        if (h == null) { page.text("Choose a house to see the terms.", 4); return; }
        int price = priceFor(id, me, h);
        int ltv = parse(c.args.getString("ltv"), 75), term = parse(c.args.getString("term"), 12);
        String[] lv = new String[LTV.length], ll = new String[LTV.length];
        for (int i = 0; i < LTV.length; i++) { lv[i] = Integer.toString(LTV[i]); ll[i] = (100 - LTV[i]) + "% down (" + price * (100 - LTV[i]) / 100 + "c), borrow " + LTV[i] + "%"; }
        String[] tv = new String[TERMS.length], tl = new String[TERMS.length];
        for (int i = 0; i < TERMS.length; i++) { tv[i] = Integer.toString(TERMS[i]); tl[i] = TERMS[i] + " weeks"; }
        page.heading("The terms for " + Homes.address(id, v, h));
        page.choice("ltv", "Deposit", lv, ll, Integer.toString(ltv));
        page.choice("term", "Term", tv, tl, Integer.toString(term));
        page.buttons().button("Work it out", "terms", null);
        Terms t = terms(level, id, p, price, ltv, term, 0);
        Debts.Score s = Debts.score(level, id, me);
        page.row(null, "Price", Citizens.is(id, me) ? "" : "A quarter over the citizens' price", PropertyBook.coins(price));
        page.row(null, "Deposit, now", "From your pack, then your account", PropertyBook.coins(t.deposit()));
        page.row(null, "Borrowed", ltv + "% of the price (loan-to-value)", PropertyBook.coins(t.lent()));
        page.row(null, "Rate", "A week: the " + ltv + "% band, the bank's vault, your credit score (" + s.score() + ", " + s.band() + ")", TownBonds.pct(t.bp()));
        page.row(null, "Each week, " + term + " weeks", "Out of your account on the bank's round", PropertyBook.coins(t.weekly()));
        page.row(null, "Interest, all told", "", PropertyBook.coins(t.interest()));
        page.bar("Affordability: the payment against a third of what you earn", t.earns() <= 0 ? 1 : Math.min(1, t.weekly() * 3.0 / t.earns()),
            t.weekly() + " of " + t.earns() / 3 + " a week");
        page.text(t.why().isEmpty() ? "✔ The bank would lend on these terms." : "✘ " + capital(t.why()) + ".", t.why().isEmpty() ? 1 : 3);
        page.buttons().button("Take this mortgage", "take", LifePage.args("house", chosen, "ltv", ltv, "term", term), t.why().isEmpty() && l == null,
            l != null ? "One mortgage at a time" : capital(t.why())).primary();
    }

    void mineTab(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page, @Nullable Bank.Loan l) {
        UUID id = v.id();
        PropertyBook.hint(p, page, "mortgage.mine", "The bank takes the week's payment from your account each seventh day. Overpay any time; remortgage to a new term; "
            + "or carry the loan to another house (port).");
        if (l == null) { page.text("You have no mortgage here.", 4); page.buttons().button("Get a mortgage", "tab", LifePage.args("tab", "get")).primary(); return; }
        Homes.Home h = Homes.homes(id).get(l.anchor);
        int value = h == null ? l.price : Homes.price(id, h);
        CompoundTag m = book(id).getCompound(Long.toString(l.anchor));
        page.heading(h == null ? "Your house" : capital(Homes.address(id, v, h)));
        page.row(new ItemStack(Items.TRIPWIRE_HOOK), Bank.loanLine(id, l), "Rate " + TownBonds.pct(rateFor(id, l.anchor, Bank.LOAN_BP)) + " a week; worth " + value + " today, "
            + (int) Math.round(100.0 * l.owed() / Math.max(1, value)) + "% borrowed", PropertyBook.coins(l.owed()));
        page.bar("Paid off", l.lent <= 0 ? 1 : 1 - l.principal / (double) l.lent, (l.lent - l.principal) + " of " + l.lent);
        if (l.missed > 0) page.text(l.missed + (l.missed == 1 ? " payment" : " payments") + " behind (" + l.arrears + "). Put coin in your account before the bank's next round: three behind and it takes the house.", 3);
        page.text("Account: " + PropertyBook.account(id, p.getUUID()) + "; the next payment is " + (l.weekly + l.arrears) + ".", PropertyBook.account(id, p.getUUID()) >= l.weekly + l.arrears ? 1 : 2);
        page.heading("Overpay");
        page.choice("over", "Pay off", new String[]{ "10", "25", "50", "100", "0" }, new String[]{ "10 coins", "25 coins", "50 coins", "100 coins", "all of it" }, "10");
        page.buttons().button("Overpay", "overpay", null, Market.coinsHeld(p) + PropertyBook.account(id, p.getUUID()) > 0, "You've no coin on you or in your account");
        page.heading("Remortgage");
        String[] tv = new String[TERMS.length], tl = new String[TERMS.length];
        for (int i = 0; i < TERMS.length; i++) { tv[i] = Integer.toString(TERMS[i]); tl[i] = TERMS[i] + " weeks"; }
        page.choice("term", "New term", tv, tl, Integer.toString(m.contains("term") ? m.getInt("term") : 12));
        page.buttons().button("Remortgage (" + REMORTGAGE_FEE + "c fee)", "remortgage", null);
        page.heading("Port to another house");
        List<Homes.Home> sale = forSale(id);
        if (sale.isEmpty()) page.text("No house of the town's is for sale to port to just now.", 4);
        for (int i = 0; i < Math.min(4, sale.size()); i++) {
            Homes.Home o = sale.get(i);
            page.row(new ItemStack(Items.OAK_DOOR), capital(Homes.address(id, v, o)), "", PropertyBook.coins(priceFor(id, p.getUUID(), o)))
                .button("Port here", "port", LifePage.args("house", Long.toString(o.anchor.asLong()))).button("Show me", "show", LifePage.args("house", Long.toString(o.anchor.asLong())));
        }
    }

    void howTab(ServerLevel level, ServerPlayer p, Villages.Village v, LifePage page) {
        page.heading("A mortgage, in plain words");
        page.text("You put down a deposit and the bank lends the rest. The share it lends is the loan-to-value: 60, 75, 80 or 90 in the hundred.", 0);
        page.text("The rate is a week's interest on what you still owe: about 2.5% at 60, 3% at 75, 3.5% at 80 and 4% at 90; a little less when the bank is flush or your credit is "
            + "excellent, more when it is stretched or your credit is poor.", 0);
        page.text("Each seventh day the bank takes the payment from your account. Miss one and a letter comes; three behind and the bank takes the house, sells it to the town, "
            + "and pays you what it fetched over what you owed.", 0);
        page.text("The payment may be no more than a third of what you earned a week over the last fortnight.", 0);
    }

    static int parse(String s, int d) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return d; }
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Nullable
    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        String common = PropertyBook.common(p, c, action, args, this);
        if (common != null) return common;
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return "!No town near.";
        UUID id = v.id();
        CompoundTag f = args.getCompound("f");
        if (f.contains("ltv")) c.args.putString("ltv", f.getString("ltv"));
        if (f.contains("term")) c.args.putString("term", f.getString("term"));
        switch (action) {
            case "house" -> { c.args.putString("house", args.getString("house")); return ""; }
            case "terms" -> { return ""; }
            case "show" -> {
                Homes.Home h = Homes.homes(id).get(Long.parseLong(args.getString("house")));
                return h == null ? "!That house isn't on the books." : PropertyBook.showMe(p, h.anchor, capital(Homes.address(id, v, h)));
            }
            case "take" -> {
                int ltv = args.contains("ltv") ? args.getInt("ltv") : parse(c.args.getString("ltv"), 75);
                int term = args.contains("term") ? args.getInt("term") : parse(c.args.getString("term"), 12);
                String said = take(level, p, v, Long.parseLong(args.getString("house")), ltv, term, args.getBoolean("sure"), c);
                if (!said.startsWith("!") && !said.isEmpty()) c.tab = "mine";
                return said;
            }
            case "overpay" -> { return overpay(level, p, id, parse(f.getString("over"), 10)); }
            case "remortgage" -> { return remortgage(level, p, id, args.contains("term") ? args.getInt("term") : parse(c.args.getString("term"), 12), args.getBoolean("sure"), c); }
            case "port" -> { return port(level, p, v, Long.parseLong(args.getString("house")), args.getBoolean("sure"), c); }
            case "debts" -> { PlayerLife.open(p, "debts", null); return "#open:"; }
            case "home" -> { PlayerLife.open(p, "home", null); return "#open:"; }
            case "life" -> { PlayerLife.open(p, "life", null); return "#open:"; }
            default -> { return null; }
        }
    }

    // ------------------------------------------------------------------ tests

    public static String takeForTests(ServerPlayer p, long anchor, int ltv, int term) {
        Villages.Village v = PlayerLife.here(p);
        PlayerLife.open(p, "mortgage", null);
        return v == null ? "!no town" : take(p.serverLevel(), p, v, anchor, ltv, term, true, PlayerLife.ctx(p));
    }

    public static int rateForTests(UUID village, int score, int ltv) {
        return rateBp(village, score, ltv);
    }

    public static String overpayForTests(ServerPlayer p, UUID village, int n) {
        return overpay(p.serverLevel(), p, village, n);
    }

    @Nullable
    public static Long firstForSaleForTests(UUID village) {
        List<Homes.Home> s = forSale(village);
        return s.isEmpty() ? null : s.get(0).anchor.asLong();
    }
}
