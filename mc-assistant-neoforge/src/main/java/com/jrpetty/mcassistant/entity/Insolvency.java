package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [wpc] Personal bankruptcy, for players and folk: when what a person owes is more than it has and it cannot pay.
 * <ul>
 * <li><b>How.</b> Declared by the debtor (the Debts page, at the hall or the bank: a question first that says what follows)
 *     or forced by a creditor through the county court (Debts' ladder, when the bailiff could not cover it).</li>
 * <li><b>The official receiver</b>, a clerk of the town, takes the person's affairs in hand and writes them out on the
 *     page: what it has, what it owes, and what was done. The coin it carries and holds at the bank goes into the estate;
 *     land is sold back to the town; a second house is sold; the main home is sold if there is anything in it over its
 *     mortgage, and the person stays on in it as the town's tenant (rehoused, not put out). The bed, the tools, the clothes
 *     and the food it carries are left alone. Premises held personally are kept and their lease ends.</li>
 * <li><b>Who is paid first.</b> The mortgage lender takes the house's price first (secured); then the town's taxes, rates
 *     and fines; then everybody else (rent, tabs, IOUs, loans) share alike. What is left owing is written off.</li>
 * <li><b>Restrictions</b> for fourteen days: no new credit, no land, no business of its own or office; the town thinks a
 *     little less of it. Then the discharge. The record stays on its credit score for six weeks.</li>
 * <li><b>Folk</b> go through the same: the purse to the estate, an owned house sold to the town with the household staying on
 *     as tenants. The board and the chronicle record it plainly.</li>
 * </ul>
 */
public final class Insolvency {

    private Insolvency() {}

    /** Days of restrictions, and of the record on the score after the discharge. */
    static final int DAYS = 14, RECORD_DAYS = 42;
    /** What a house fetches sold by the receiver, in the hundred of its price. */
    static final int FIRE_SALE = 85;

    static CompoundTag person(UUID village, UUID who) {
        return Debts.person(village, who);
    }

    public static boolean bankrupt(UUID village, UUID who) {
        return person(village, who).getLong("bkUntil") > Accounts.dayNow();
    }

    public static long until(UUID village, UUID who) {
        return person(village, who).getLong("bkUntil");
    }

    public static long recordUntil(UUID village, UUID who) {
        return person(village, who).getLong("bkRecord");
    }

    /** May this person own or direct a business, or stand for office (WP-B, the elections)? */
    public static boolean barred(UUID village, UUID who) {
        return bankrupt(village, who);
    }

    /** What the person has that the receiver could reach: coin carried and banked, land at its buy-back, houses at a fire sale. */
    static int assets(ServerLevel level, UUID village, UUID who) {
        int n = PropertyBook.account(village, who);
        ServerPlayer p = PropertyBook.online(level, who);
        if (p != null) n += Market.coinsHeld(p);
        for (LandPlots.Title t : LandPlots.of(village, who)) n += t.price() * LandPlots.BUY_BACK / 100;
        for (Homes.Home h : Homes.homes(village).values()) if (h.tenure == Homes.Tenure.PLAYER && who.equals(h.landlord)) n += Math.max(h.price, Homes.price(village, h)) * FIRE_SALE / 100;
        return n;
    }

    /** Insolvent: owes more than it could find now (carried and banked), and more than it has in all. */
    static boolean insolvent(ServerLevel level, UUID village, UUID who) {
        int owed = Debts.totalAll(village, who);
        if (owed <= 0) return false;
        int cash = PropertyBook.account(village, who);
        ServerPlayer p = PropertyBook.online(level, who);
        if (p != null) cash += Market.coinsHeld(p);
        return owed > cash && owed > assets(level, village, who);
    }

    /** Declared by the debtor (asks first, saying what follows). */
    static String declare(ServerLevel level, ServerPlayer p, Villages.Village v, boolean sure, PlayerLife.Ctx c) {
        UUID id = v.id(), me = p.getUUID();
        if (bankrupt(id, me)) return "!You are bankrupt already, till day " + until(id, me) + ".";
        int owed = Debts.totalAll(id, me);
        if (owed <= 0) return "!You owe nothing here: there's nothing to declare.";
        if (!insolvent(level, id, me)) return "!You aren't insolvent: you have " + assets(level, id, me) + " in coin, land and houses against " + owed
            + " owed. Sell something, ask for plans, or consolidate instead.";
        if (!sure) return PropertyBook.ask(c, "bk_declare", new CompoundTag(), "Declare yourself bankrupt? The official receiver takes your coin and bank account, sells your land "
            + "and any house with money in it (you stay on in your home as the town's tenant), and pays your creditors: the mortgage first, then the town, then the rest. "
            + "What's left owing is written off. For " + DAYS + " days you can't borrow, buy land, run a business or stand for office, and folk think a little less of you. "
            + "It stays on your credit score for " + RECORD_DAYS / 7 + " weeks. It can't be undone.", "Declare bankruptcy");
        return process(level, v, me, Belonging.called(p), "declared bankrupt at their own asking");
    }

    /** Forced by a creditor through the court (Debts' ladder). */
    static void forced(ServerLevel level, Villages.Village v, UUID debtor, String creditor) {
        if (bankrupt(v.id(), debtor)) return;
        String name = person(v.id(), debtor).getString("name");
        if (name.isEmpty()) name = "a debtor";
        String said = process(level, v, debtor, name, "made bankrupt by the county court on " + creditor + "'s petition");
        PropertyBook.tell(level, debtor, said);
    }

    /** The receiver's work: the estate gathered, the creditors paid in their order, the rest written off; the restrictions begin. */
    static String process(ServerLevel level, Villages.Village v, UUID who, String name, String how) {
        UUID id = v.id();
        long day = PropertyBook.day(level);
        List<String> st = new ArrayList<>();
        VillageFolkEntity clerk = receiver(level, v);
        String rec = clerk == null ? "the town clerk" : clerk.displayNameCap();
        st.add("Official receiver: " + rec + ". Day " + day + ".");
        int owedBefore = Debts.totalAll(id, who);
        st.add("Owed in all: " + owedBefore + " coins.");
        int pot = 0;
        // coin carried and banked
        ServerPlayer p = PropertyBook.online(level, who);
        if (p != null) {
            int held = Market.coinsHeld(p);
            if (held > 0) { Market.payOut(p, held); CoinCensus.playerIn(id, held, p, PlayerFinance.Cat.LOSSES, "to the official receiver"); Ledger.addCoins(id, held); pot += held; st.add("Coin carried: " + held + "."); }
        }
        VillageFolkEntity folk = p == null ? Homes.loaded(id, who) : null;
        if (folk != null && folk.purse() > 0) {
            int k = folk.purse();
            if (folk.spend(k)) { Ledger.addCoins(id, k); pot += k; st.add("Its purse: " + k + "."); }
        }
        int banked = PropertyBook.payOffline(id, who, PropertyBook.account(id, who), PlayerFinance.Cat.LOSSES, "to the official receiver");
        if (banked > 0) { pot += banked; st.add("Bank account: " + banked + "."); }
        // land, back to the town
        for (LandPlots.Title t : LandPlots.of(id, who)) {
            int back = t.price() * LandPlots.BUY_BACK / 100;
            LandPlots.release(level, id, t.id());
            pot += back;                                                     // the town keeps the price against what is owed it
            st.add("Sold to the town: " + t.name().toLowerCase(java.util.Locale.ROOT) + ", " + back + ".");
        }
        // houses: the mortgage lender first out of the price; the debtor stays on in its home as a tenant
        int secured = 0;
        Bank.Loan loan = Bank.playerLoan(id, who);
        boolean housed = false;
        for (Homes.Home h : new ArrayList<>(Homes.homes(id).values())) {
            boolean mine = h.tenure == Homes.Tenure.PLAYER && who.equals(h.landlord) || h.tenure == Homes.Tenure.OWNED && h.members.contains(who);
            if (!mine) continue;
            int price = Math.max(h.price, Homes.price(id, h)) * FIRE_SALE / 100;
            int toBank = 0;
            if (loan != null && loan.anchor == h.anchor.asLong()) {
                toBank = Math.min(price, loan.owed());
                Mortgages.closeBySale(level, v, loan, toBank);
                secured += toBank;
                loan = null;
            }
            h.tenure = Homes.Tenure.RENTED;
            h.landlord = null;
            h.landlordName = "";
            h.toLet = false;
            h.price = Homes.price(id, h);
            h.rent = Homes.rent(id, h);
            h.since = day;
            Homes.save(id, h);
            pot += price - toBank;
            Ledger.addCoins(id, 0);
            String where = Homes.address(id, v, h);
            st.add("Sold to the town: " + where + ", " + price + (toBank > 0 ? " (" + toBank + " of it to the bank for the mortgage)" : "") + ".");
            if (!housed && p != null) {
                Tenancy.start(level, id, who, name, h.anchor, h.rent, true);
                housed = true;
                st.add("Rehoused: you stay on at " + where + " as the town's tenant, at " + h.rent + " a day.");
            } else if (!housed && folk != null) {
                housed = true;
                st.add("The household stays on at " + where + " as the town's tenants.");
            }
        }
        if (secured > 0) st.add("Secured creditor (the bank) paid " + secured + ".");
        // the rest, in order: the town's taxes, rates and fines; then everybody else alike
        List<CompoundTag> first = new ArrayList<>(), rest = new ArrayList<>();
        for (Tag e : Debts.list(id, who)) {
            CompoundTag t = (CompoundTag) e;
            String k = t.getString("kind");
            (k.equals("rates") || k.equals("tax") || k.equals("fine") ? first : rest).add(t);
        }
        // the pot is already in the treasury (the town's share stays there; others are paid out of it)
        pot = payClass(level, id, who, first, pot, st, "Taxes, rates and fines");
        pot = payClass(level, id, who, rest, pot, st, "Everybody else");
        // the savings club's loan, last of the unsecured
        CompoundTag club = FolkCredit.playerLoans(id).getCompound(who.toString());
        if (club.getLong("owed") > 0) { club.putLong("owed", 0); CapitalBook.dirty(); st.add("The savings club's loan written off."); }
        int written = Debts.total(id, who);
        Debts.list(id, who).clear();
        if (written > 0) st.add("Written off: " + written + ".");
        if (pot > 0) {
            PropertyBook.putAccount(id, who, name, Math.min(pot, Ledger.coins(id)));
            Ledger.takeCoins(id, Math.min(pot, Ledger.coins(id)));
            st.add("Left over, back to you: " + pot + ".");
        }
        CompoundTag pr = person(id, who);
        pr.putLong("bkUntil", day + DAYS);
        pr.putLong("bkRecord", day + DAYS + RECORD_DAYS);
        pr.putLong("bkFrom", day);
        ListTag s = new ListTag();
        for (String line : st) s.add(StringTag.valueOf(line));
        pr.put("bkStatement", s);
        Debts.history(id, who, day, -25, "bankruptcy");
        PropertyBook.dirty();
        // the town thinks a little less of it (those who know it)
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && !f.getUUID().equals(who)) {
                try { f.persona().feelFor(who, name, -3); } catch (RuntimeException e) { /* a folk without a persona yet */ }
            }
        }
        if (folk != null) folk.persona().remember(day, "I was made bankrupt: the receiver sold what we had", 8);
        Villages.tell(id, day, name + " was " + how + ": " + rec + " as receiver, " + owedBefore + " owed, " + written + " written off; discharged in " + DAYS + " days");
        Market.assemblyNews(id, name + " has been made bankrupt. The receiver has the books.");
        if (p != null) Planner.add(p, (day + DAYS) * 24000L + 6000, "bankruptcy", "Your discharge from bankruptcy", null);
        return "You are bankrupt. " + rec + ", the official receiver, has your affairs: " + owedBefore + " owed, " + written + " written off. "
            + "Your bed, tools, clothes and food are yours. Discharge on day " + (day + DAYS) + "; till then no credit, land, business or office. "
            + "The receiver's statement is on the Bankruptcy tab.";
    }

    /** Pay a class of creditors out of the pot, alike in proportion. Returns what is left in the pot. */
    static int payClass(ServerLevel level, UUID village, UUID who, List<CompoundTag> debts, int pot, List<String> st, String label) {
        int owed = 0;
        for (CompoundTag t : debts) owed += t.getInt("owed");
        if (owed <= 0) return pot;
        int pay = Math.min(pot, owed);
        int paid = 0;
        for (CompoundTag t : debts) {
            int share = (int) ((long) pay * t.getInt("owed") / owed);
            if (share <= 0) continue;
            Debts.credit(level, village, t, share);
            t.putInt("owed", t.getInt("owed") - share);
            paid += share;
        }
        st.add(label + ": " + paid + " of " + owed + " paid.");
        return pot - paid;
    }

    /** The receiver: a grown folk of the town, the one nearest the hall's desk (or the heart). */
    @Nullable
    static VillageFolkEntity receiver(ServerLevel level, Villages.Village v) {
        net.minecraft.core.BlockPos at = TownHallDesk.desk(level, v.id());
        if (at == null) at = v.centre();
        VillageFolkEntity best = null;
        double bd = Double.MAX_VALUE;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby()) continue;
            double d = f.distanceToSqr(at.getX(), at.getY(), at.getZ());
            if (d < bd) { bd = d; best = f; }
        }
        return best;
    }

    /** Each morning: those whose fourteen days are up are discharged. */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag b = Debts.book(id);
        for (String k : b.getAllKeys()) {
            CompoundTag pr = b.getCompound(k);
            if (pr.getLong("bkUntil") <= 0 || pr.getLong("bkUntil") > day || pr.getBoolean("bkDone")) continue;
            pr.putBoolean("bkDone", true);
            PropertyBook.dirty();
            String name = pr.getString("name").isEmpty() ? "a debtor" : pr.getString("name");
            Villages.tell(id, day, name + " was discharged from bankruptcy");
            try { PropertyBook.tell(level, UUID.fromString(k), "You are discharged from bankruptcy. You may borrow, buy land and run a business again; it stays on your credit score till day " + pr.getLong("bkRecord") + "."); }
            catch (IllegalArgumentException e) { /* not a person */ }
        }
    }

    // ------------------------------------------------------------------ the tab on the Debts page

    static void tab(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page) {
        UUID id = v.id(), me = p.getUUID();
        PropertyBook.hint(p, page, "debts.bankrupt", "Bankruptcy is the way out when you owe more than you have and can't pay: the official receiver sells what you have, pays "
            + "your creditors in order, and writes off the rest. It has real costs for a while.");
        CompoundTag pr = person(id, me);
        if (pr.getLong("bkFrom") > 0) {
            boolean now = bankrupt(id, me);
            page.heading(now ? "You are bankrupt" : "Discharged");
            page.text(now ? "Discharge on day " + until(id, me) + ". Till then: no new credit, no land, no business of your own, no standing for office."
                : "You were discharged on day " + until(id, me) + ". It stays on your credit score till day " + recordUntil(id, me) + ".", now ? 3 : 2);
            page.heading("The receiver's statement");
            for (Tag t : pr.getList("bkStatement", Tag.TAG_STRING)) page.text(t.getAsString(), 0);
            if (now) return;
        }
        int owed = Debts.totalAll(id, me), assets = assets(level, id, me);
        int cash = PropertyBook.account(id, me) + Market.coinsHeld(p);
        page.heading("Where you stand");
        page.row(new ItemStack(Items.PAPER), "You owe " + PropertyBook.coins(owed), "Everything on your ledger, the mortgage and any loan", "");
        page.row(new ItemStack(Items.GOLD_INGOT), "You have " + PropertyBook.coins(assets), "Coin carried and banked (" + cash + "), land at its buy-back price, houses at a forced sale", "");
        boolean ins = insolvent(level, id, me);
        page.text(ins ? "You owe more than you have and can't pay it. Bankruptcy is open to you." : owed <= 0 ? "You owe nothing: bankruptcy isn't for you."
            : "You have more than you owe: pay down, ask for plans or consolidate instead.", ins ? 2 : 1);
        page.heading("What happens");
        page.text("1. The official receiver, a clerk of the town, takes your coin and bank account.", 0);
        page.text("2. Your land is sold back to the town; a house with money in it over its mortgage is sold. You stay on in your home as the town's tenant.", 0);
        page.text("3. Your creditors are paid: the mortgage first, then the town's taxes and rates, then everyone else alike. The rest is written off.", 0);
        page.text("4. For " + DAYS + " days: no new credit, no land, no business of your own, no office; folk think a little less of you. Then your discharge.", 0);
        page.text("Kept: your bed, your tools, your clothes and your food.", 1);
        page.buttons().button("Declare bankruptcy", "bk_declare", null, ins, owed <= 0 ? "You owe nothing" : "You aren't insolvent: you have more than you owe");
    }

    @Nullable
    static String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args, PlayerLife.Page page) {
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return "!No town near.";
        if (action.equals("bk_declare")) return declare(p.serverLevel(), p, v, args.getBoolean("sure"), c);
        return null;
    }

    // ------------------------------------------------------------------ tests

    public static String declareForTests(ServerPlayer p) {
        Villages.Village v = PlayerLife.here(p);
        PlayerLife.open(p, "debts", null);
        return v == null ? "!no town" : declare(p.serverLevel(), p, v, true, PlayerLife.ctx(p));
    }

    public static void forcedForTests(ServerLevel level, Villages.Village v, UUID debtor, String creditor) {
        forced(level, v, debtor, creditor);
    }

    public static List<String> statementForTests(UUID village, UUID who) {
        List<String> out = new ArrayList<>();
        for (Tag t : person(village, who).getList("bkStatement", Tag.TAG_STRING)) out.add(t.getAsString());
        return out;
    }

    public static void dischargeForTests(ServerLevel level, Villages.Village v, UUID who) {
        person(v.id(), who).putLong("bkUntil", PropertyBook.day(level));
        morning(level, v, PropertyBook.day(level));
    }
}
