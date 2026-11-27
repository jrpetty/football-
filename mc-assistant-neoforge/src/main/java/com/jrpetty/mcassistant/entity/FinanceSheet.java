package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.net.FinancePayload;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [purse20] What a player has and owes, gathered from every town's books, and the page that shows it (the client's
 * FinanceScreen): its balance sheet, its businesses, properties and investments, the bills coming due, and from
 * PlayerFinance its income and spending by kind, the charts of sixty days and the ledger.
 *
 * <p><b>Assets.</b> The coin it carries (and keeps in its ender chest); what it has in each town's bank, savings club
 * and treasury; its shares at their last price on the bourse; its bonds at their face; its houses at their price and
 * its land titles at their value; its businesses at their book value (the till, what the town owes them, the stock at
 * cost and the kit, less what they owe: Enterprise.equity), by its share; the goods in its stall at the going price;
 * and what is owed to it: rent waiting from its tenants, coupons and rents and share money waiting at the hall,
 * drawings waiting in a business's books, a works investment and a bulk order's deposit.
 * <b>Liabilities.</b> Its mortgage, its loans (the bank's or savings club's, the treasury's), and what it owes the
 * towns: taxes, fines, an outlaw's account, a stall's rent run out. Net worth is the one less the other.
 *
 * <p>Read only: nothing here moves a coin. A player is sent its own page; an operator may ask for another's by name.
 */
public final class FinanceSheet {

    private FinanceSheet() {}

    /** A line of the balance sheet: its group, words, value (pennies), the town, a link (to its own screen), a tooltip. */
    public record Row(String group, String label, long value, String town, String link, String tip, String townId) {
        public Row(String group, String label, long value, String town, String link, String tip) {
            this(group, label, value, town, link, tip, "");
        }

        Row withTown(String id) {
            return new Row(group, label, value, town, link, tip, id);
        }

        CompoundTag tag() {
            CompoundTag t = new CompoundTag();
            t.putString("g", group);
            t.putString("l", label);
            t.putLong("v", value);
            t.putString("t", town);
            t.putString("k", link);
            t.putString("tip", tip);
            t.putString("vid", townId);
            return t;
        }
    }

    /** What the player has and owes, worked out once. */
    public static final class Sheet {
        public final UUID who;
        public final List<Row> assets = new ArrayList<>(), liabilities = new ArrayList<>();
        final ListTag businesses = new ListTag(), properties = new ListTag(), shares = new ListTag(), bonds = new ListTag(), bills = new ListTag();
        public long cash;
        public boolean cashKnown;

        Sheet(UUID who) {
            this.who = who;
        }

        public long assetsTotal() {
            long n = 0;
            for (Row r : assets) n += r.value();
            return n;
        }

        public long liabilitiesTotal() {
            long n = 0;
            for (Row r : liabilities) n += r.value();
            return n;
        }

        public long net() {
            return assetsTotal() - liabilitiesTotal();
        }

        /** The total of one group of assets (or liabilities), pennies. */
        public long group(String g, boolean liability) {
            long n = 0;
            for (Row r : liability ? liabilities : assets) if (r.group().equals(g)) n += r.value();
            return n;
        }

        public int bills() {
            return bills.size();
        }

        /** [purse20] What the player has and owes in each town (by its id): {assets, liabilities} pennies. */
        public Map<String, long[]> byTown() {
            Map<String, long[]> out = new java.util.LinkedHashMap<>();
            for (Row r : assets) if (!r.townId().isEmpty()) out.computeIfAbsent(r.townId(), k -> new long[2])[0] += r.value();
            for (Row r : liabilities) if (!r.townId().isEmpty()) out.computeIfAbsent(r.townId(), k -> new long[2])[1] += r.value();
            return out;
        }

        /** The total of what is in no town (the coin carried), pennies. */
        public long unplaced() {
            long n = 0;
            for (Row r : assets) if (r.townId().isEmpty()) n += r.value();
            for (Row r : liabilities) if (r.townId().isEmpty()) n -= r.value();
            return n;
        }
    }

    public static final String CASH = "Cash", SAVINGS = "Savings", INVESTMENTS = "Investments", PROPERTY = "Property",
        BUSINESSES = "Businesses", OWED_TO_YOU = "Owed to you", LOANS = "Loans", BILLS = "Bills owed";

    // ------------------------------------------------------------------ working it out

    /** Everything the player has and owes, across every town (and today's worth written down). */
    public static Sheet work(ServerLevel level, UUID who, @Nullable Player online) {
        Sheet s = new Sheet(who);
        long day = level.getDayTime() / 24000L;
        String key = who.toString();
        // The coin it carries.
        if (online != null) {
            s.cash = Market.coinsHeld(online) * 100L;
            s.cashKnown = true;
            PlayerFinance.lastCash(who, s.cash);
            s.assets.add(new Row(CASH, "Coin you carry", s.cash, "", "", "The village coin in your pack."));
            int ender = 0;
            for (int i = 0; i < online.getEnderChestInventory().getContainerSize(); i++) {
                ItemStack st = online.getEnderChestInventory().getItem(i);
                if (Market.isCoin(st)) ender += st.getCount();
            }
            if (ender > 0) s.assets.add(new Row(CASH, "Coin in your ender chest", ender * 100L, "", "", "Village coin kept in your ender chest."));
        } else {
            s.cash = PlayerFinance.lastCash(who);
            s.assets.add(new Row(CASH, "Coin carried (when last seen)", s.cash, "", "", "The coin in your pack when you were last about."));
        }
        for (Villages.Village v : Villages.every()) {
            UUID id = v.id();
            String town = Villages.name(id);
            int a0 = s.assets.size(), l0 = s.liabilities.size();
            int[] lists0 = { s.businesses.size(), s.properties.size(), s.shares.size(), s.bonds.size(), s.bills.size() };
            try {
                town(level, s, v, id, town, key, day);
            } catch (RuntimeException e) {
                com.jrpetty.mcassistant.Guard.struck("the finances in " + town, e);
            }
            // [purse20] Every line and list entry made for this town carries its id (the By town view, the filter).
            String vid = id.toString();
            for (int i = a0; i < s.assets.size(); i++) s.assets.set(i, s.assets.get(i).withTown(vid));
            for (int i = l0; i < s.liabilities.size(); i++) s.liabilities.set(i, s.liabilities.get(i).withTown(vid));
            ListTag[] lists = { s.businesses, s.properties, s.shares, s.bonds, s.bills };
            for (int k = 0; k < lists.length; k++) for (int i = lists0[k]; i < lists[k].size(); i++) lists[k].getCompound(i).putString("vid", vid);
        }
        Map<String, Long> nets = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : s.byTown().entrySet()) nets.put(e.getKey(), e.getValue()[0] - e.getValue()[1]);
        PlayerFinance.worth(who, day, s.net(), s.cash, s.assetsTotal(), s.liabilitiesTotal(), nets);
        return s;
    }

    private static void town(ServerLevel level, Sheet s, Villages.Village v, UUID id, String town, String key, long day) {
        UUID who = s.who;
        // ---- savings: the bank, the savings club, the treasury
        Bank.State bs = Bank.state(id);
        Bank.Account acct = bs.accounts.get(who);
        if (acct != null && acct.balance > 0) {
            s.assets.add(new Row(SAVINGS, "Bank account", acct.balance * 100L, town, "page:" + id + ":Capital",
                "In " + town + "'s bank: " + acct.balance + " coins" + (acct.interestAll > 0 ? ", " + acct.interestAll + " of it interest" : "") + "."));
        }
        if (!FolkCredit.bank(id)) {
            int club = FolkCredit.accounts(id).getInt(key);
            if (club > 0) s.assets.add(new Row(SAVINGS, "Savings club", club * 100L, town, "page:" + id + ":Capital",
                "Put by with " + town + "'s savings club, earning " + TownBonds.pct(FolkCredit.depositBp(id)) + " a week as its loans earn."));
        }
        String[] tb = Commerce.fields(id, "bank/" + who);
        if (tb != null) {
            long dep = Commerce.num(tb[0]);
            long loan = tb.length > 1 ? Commerce.num(tb[1]) : 0;
            if (dep > 0) s.assets.add(new Row(SAVINGS, "Put by at the treasury", dep * 100L, town, "page:" + id + ":Money", "Coin you put by with " + town + "'s treasurer."));
            if (loan > 0) {
                s.liabilities.add(new Row(LOANS, "Treasury loan", loan * 100L, town, "page:" + id + ":Money", "What you owe " + town + "'s treasury, interest and all."));
                bill(s, "Treasury loan", town, loan * 100L, -1, false, "Repay it at the treasury when you can.");
            }
        }
        // ---- the mortgage
        Bank.Loan l = Bank.playerLoan(id, who);
        if (l != null && l.owed() > 0) {
            s.liabilities.add(new Row(LOANS, "Mortgage", l.owed() * 100L, town, "page:" + id + ":Homes",
                l.owed() + " owed, " + l.weekly + " a week out of your account, " + l.weeksLeft() + " weeks to go" + (l.arrears > 0 ? "; " + l.arrears + " behind" : "") + "."));
            long next = bs.week.from >= 0 ? bs.week.from + 7 : day + 7 - Math.floorMod(day, 7);
            if (l.arrears > 0) bill(s, "Mortgage arrears", town, l.arrears * 100L, day, true, l.missed + " payments behind.");
            bill(s, "Mortgage payment", town, Math.min(l.owed(), l.weekly) * 100L, Math.max(day, next), false,
                "Taken from your bank account (then the rent waiting for you), each week.");
        }
        // ---- loans against its holdings (the savings club's or the bank's)
        CompoundTag pl = FolkCredit.playerLoans(id).getCompound(key);
        if (pl.getLong("owed") > 0) {
            s.liabilities.add(new Row(LOANS, "Loan from " + FolkCredit.lenderName(id), pl.getLong("owed"), town, "page:" + id + ":Capital",
                "Borrowed against your savings, bonds and shares at " + TownBonds.pct(pl.getInt("bp")) + " a week; " + Businesses.pence(pl.getLong("interest")) + " of it interest."));
            if (pl.getLong("interest") > 0) bill(s, "Loan interest building", town, pl.getLong("interest"), -1, pl.getLong("interest") > pl.getLong("owed") / 4,
                "Unpaid interest past a third of the loan and the lender takes your savings against it.");
        }
        // ---- investments: shares, bonds, the town's works
        for (Business b : Businesses.all(id)) {
            if (b.mirror()) {
                long due = b.ownersDue.getOrDefault(who, 0L);
                if (due > 0 && b.ownedBy(who)) s.assets.add(new Row(OWED_TO_YOU, "Change kept at " + b.name, due, town, "", "Odd pennies kept for you."));
                continue;
            }
            int parts = 0;
            for (Business.Stake st : b.owners) if (st.holder() == Business.Holder.PLAYER && who.equals(st.who())) parts += st.parts();
            long due = b.ownersDue.getOrDefault(who, 0L);
            if (due > 0) s.assets.add(new Row(OWED_TO_YOU, "Drawings waiting: " + b.name, due, town, "books:" + id + ":" + b.id,
                "Your share of what " + b.name + " has made, waiting in its books for you to take."));
            if (parts <= 0) continue;
            boolean listed = b.open && Enterprise.isFirm(b) && !b.town();
            if (parts * 2 < Business.WHOLE && listed) {
                long price = Bourse.last(level, id, b);
                int n = parts / Bourse.SHARE;
                long value = price * parts / Bourse.SHARE;
                long cost = PlayerFinance.cost(who, "share:" + b.id);
                s.assets.add(new Row(INVESTMENTS, "Shares: " + b.name, value, town, "page:" + id + ":Capital",
                    n + " shares at " + Businesses.pence(price) + (cost > 0 ? "; cost you " + Businesses.pence(cost) : "") + "."));
                CompoundTag t = new CompoundTag();
                t.putString("name", b.name);
                t.putString("town", town);
                t.putInt("n", n);
                t.putLong("price", price);
                t.putLong("value", value);
                t.putLong("cost", cost);
                t.putString("k", "page:" + id + ":Capital");
                s.shares.add(t);
            } else {
                long equity = Enterprise.equity(id, b);
                long mine = equity * parts / Business.WHOLE;
                s.assets.add(new Row(BUSINESSES, b.name, mine, town, "books:" + id + ":" + b.id,
                    (parts >= Business.WHOLE ? "Yours" : "Your " + (parts * 100 / Business.WHOLE) + "%") + " at book value: its till, stock at cost and kit, less what it owes."));
                Business.Day w = b.last(day, 7), lw = b.last(day - 7, 7);
                CompoundTag t = new CompoundTag();
                t.putString("name", b.name);
                t.putString("town", town);
                t.putString("type", b.type.title);
                t.putInt("share", parts);
                t.putBoolean("open", b.open);
                t.putLong("takings", w.revenue);
                t.putLong("costs", w.revenue - w.net());
                t.putLong("wages", w.wages);
                t.putLong("profit", w.net());
                t.putLong("lastProfit", lw.net());
                t.putInt("hands", Businesses.staff(id, b).size());
                t.putLong("stock", b.stockCost());
                t.putLong("till", b.till);
                t.putLong("loan", b.loan);
                t.putLong("value", mine);
                t.putString("k", "books:" + id + ":" + b.id);
                s.businesses.add(t);
            }
        }
        long bondFace = TownBonds.heldBy(id, who);
        long hallDue = Accounts.sub(TownBonds.book(id), "due").getLong(key);
        if (bondFace > 0) {
            s.assets.add(new Row(INVESTMENTS, "Town bonds", bondFace, town, "page:" + id + ":Capital",
                "Lent to " + town + " at " + TownBonds.pct(TownBonds.rateBp(id)) + " a week (today's rate), at face."));
        }
        if (bondFace > 0 || hallDue > 0) {
            CompoundTag t = new CompoundTag();
            t.putString("town", town);
            t.putLong("face", bondFace);
            t.putLong("due", hallDue);
            t.putInt("bp", TownBonds.rateBp(id));
            t.putString("k", "page:" + id + ":Capital");
            s.bonds.add(t);
        }
        if (hallDue > 0) {
            Map<PlayerFinance.Cat, Long> mix = PlayerFinance.dueMix(who, id);
            StringBuilder sb = new StringBuilder("Waiting for you at the hall");
            for (Map.Entry<PlayerFinance.Cat, Long> e : mix.entrySet()) sb.append(sb.indexOf(":") < 0 ? ": " : ", ").append(e.getKey().label.toLowerCase(Locale.ROOT)).append(" ").append(Businesses.pence(e.getValue()));
            s.assets.add(new Row(OWED_TO_YOU, "Waiting at the hall", hallDue, town, "page:" + id + ":Capital", sb + "."));
        }
        String[] inv = Commerce.fields(id, "invest/" + who);
        if (inv != null && Commerce.num(inv[0]) > 0) {
            s.assets.add(new Row(INVESTMENTS, "In the town's works", Commerce.num(inv[0]) * 100L, town, "page:" + id + ":Money",
                Commerce.num(inv[0]) + " coins in " + town + "'s works" + (inv.length > 1 ? " until day " + Commerce.num(inv[1]) : "") + "."));
        }
        String[] bulk = Commerce.fields(id, "bulk/" + who);
        if (bulk != null && bulk.length >= 4 && Commerce.num(bulk[2]) > 0) {
            s.assets.add(new Row(OWED_TO_YOU, "Bulk order (deposit paid)", Commerce.num(bulk[2]) * 100L, town, "", "An order being made for you: collect it by day " + Commerce.num(bulk[3]) + "."));
            bill(s, "Collect your bulk order", town, 0, Commerce.num(bulk[3]), false, "The rest is paid when you collect it.");
        }
        // ---- property: houses and land titles
        long rentWeek = 0;
        Map<Long, Homes.Home> homes = Homes.homes(id);
        for (Homes.Home h : homes.values()) {
            if (h.tenure != Homes.Tenure.PLAYER || !who.equals(h.landlord)) continue;
            if (FinanceJoin.registered(id, who, h.anchor)) continue;         // [join21] on the property register: valued there (FinanceJoin)
            int price = h.price > 0 ? h.price : Homes.price(id, h);
            String addr = Homes.address(id, v, h);
            int rent = h.members.isEmpty() ? 0 : h.rent;
            rentWeek += rent * 7L;
            boolean mortgaged = l != null && l.anchor == h.anchor.asLong();
            s.assets.add(new Row(PROPERTY, addr, price * 100L, town, "page:" + id + ":Homes",
                "A house at its price" + (rent > 0 ? "; let at " + rent + " a day" : h.toLet ? "; to let, empty" : "") + (mortgaged ? "; mortgaged" : "") + "."));
            CompoundTag t = new CompoundTag();
            t.putString("name", addr);
            t.putString("town", town);
            t.putString("kind", "House");
            t.putLong("value", price * 100L);
            t.putLong("rentIn", rent * 700L);
            t.putLong("mortgage", mortgaged ? l.owed() * 100L : 0);
            t.putString("k", "page:" + id + ":Homes");
            s.properties.add(t);
        }
        CompoundTag titles = LandTitles.titles(id);
        for (String k : titles.getAllKeys()) {
            CompoundTag t0 = titles.getCompound(k);
            if (!key.equals(t0.getString("landlord"))) continue;
            s.assets.add(new Row(PROPERTY, "Title: " + t0.getString("name"), t0.getLong("value"), town, "page:" + id + ":Lands",
                "A land title at its value; earns " + Businesses.pence(t0.getLong("earns")) + " a week in rent, paid to you at the hall."));
            CompoundTag t = new CompoundTag();
            t.putString("name", t0.getString("name"));
            t.putString("town", town);
            t.putString("kind", "Land title");
            t.putLong("value", t0.getLong("value"));
            t.putLong("rentIn", t0.getLong("earns"));
            t.putLong("mortgage", 0);
            t.putString("k", "page:" + id + ":Lands");
            s.properties.add(t);
        }
        FinanceJoin.property(level, s, id, town, day);                       // [join21] buildings on the register, and going up (orders-17)
        int rentDue = Homes.parse(Ledger.note(id, "rentdue/" + who));
        if (rentDue > 0) s.assets.add(new Row(OWED_TO_YOU, "Rent waiting from your tenants", rentDue * 100L, town, "page:" + id + ":Homes",
            "Your tenants' rent, waiting for you at " + town + "'s bank counter."));
        // ---- the stall
        PlayerStalls.Stall st = PlayerStalls.load(id, who);
        if (st != null) {
            if (st.till > 0) s.assets.add(new Row(OWED_TO_YOU, "Your stall's till", st.till * 100L, town, "stall:" + id, "Takings in your stall's till, waiting for you."));
            PlayerStalls.Booth b = PlayerStalls.boothOf(id, st);
            CompoundTag store = PlayerFinance.sub(PlayerFinance.player(who), "stallStock");
            long stock = store.getLong(id.toString());
            if (b != null && level.isLoaded(b.at()) && level.getBlockEntity(b.at()) instanceof Container box) {
                stock = 0;
                for (int i = 0; i < box.getContainerSize(); i++) {
                    ItemStack it = box.getItem(i);
                    if (it.isEmpty() || Market.isCoin(it)) continue;
                    stock += (long) PlayerStalls.going(level, id, it) * 100L * it.getCount() / Math.max(1, PlayerStalls.lot(it));
                }
                store.putLong(id.toString(), stock);
            }
            if (stock > 0) s.assets.add(new Row(BUSINESSES, "Stall stock", stock, town, "stall:" + id, "The goods in your stall at the going price" + (b != null && level.isLoaded(b.at()) ? "." : " (as last seen).")));
            PlayerStalls.State state = st.state(day);
            int rent = PlayerStalls.rent(id);
            if (state == PlayerStalls.State.DUE) {
                s.liabilities.add(new Row(BILLS, "Stall rent due", rent * 100L, town, "stall:" + id, "Your stall's rent ran out on day " + (st.paidTill + 1) + "; it is shut till you pay."));
                bill(s, "Stall rent", town, rent * 100L, st.paidTill + 1, true, "Your stall is shut till the rent is paid.");
            } else if (state == PlayerStalls.State.OPEN) {
                bill(s, "Stall rent", town, rent * 100L, st.paidTill + 1, false, "Another week's rent, to keep your stall open.");
            }
            CompoundTag t = new CompoundTag();
            t.putString("name", "Your stall");
            t.putString("town", town);
            t.putString("type", "Market stall");
            t.putInt("share", Business.WHOLE);
            t.putBoolean("open", state == PlayerStalls.State.OPEN);
            int week = 0;
            for (int c : st.weekCoin) week += c;
            t.putLong("takings", week * 100L);
            t.putLong("costs", state == PlayerStalls.State.OPEN || state == PlayerStalls.State.DUE ? rent * 100L : 0);
            t.putLong("profit", week * 100L - (state == PlayerStalls.State.OPEN ? rent * 100L : 0));
            t.putLong("lastProfit", Long.MIN_VALUE);
            t.putInt("hands", 0);
            t.putLong("stock", stock);
            t.putLong("till", st.till * 100L);
            t.putLong("value", stock + st.till * 100L);
            t.putString("k", "stall:" + id);
            s.businesses.add(t);
        }
        FinanceJoin.shop(level, s, id, town, day);                           // [join21] the player's own shop (player-15)
        // ---- what it owes the town
        int taxes = TaxBook.owedBy(id, who);
        if (taxes > 0) {
            s.liabilities.add(new Row(BILLS, "Taxes owed", taxes * 100L, town, "page:" + id + ":Taxes", "Owed to " + town + " since tax day; pay at the hall or to any folk."));
            bill(s, "Taxes owed", town, taxes * 100L, day, true, "Three weeks unpaid and the town thinks the less of you.");
        }
        if (Citizens.is(id, who)) {
            int due = TaxBook.head(id) + TaxBook.levy(id) + (Knights.isKnight(id, who) ? TaxBook.KNIGHT_FEE : 0);
            if (due > 0) bill(s, "Tax day", town, due * 100L, TaxBook.nextTaxDay(id, day), false, "The head tax" + (TaxBook.levy(id) > 0 ? " and the war levy" : "") + ", as a citizen.");
        }
        int fine = Ledger.record(id, who)[1];
        if (fine > 0) {
            s.liabilities.add(new Row(BILLS, "Fines owed", fine * 100L, town, "page:" + id + ":Watch", "A fine you could not pay at the time, owed to " + town + "'s watch."));
            bill(s, "Fine owed", town, fine * 100L, day, true, "Pay it to the watch, or at the hall.");
        }
        CompoundTag out = Outlaws.record(id, who, false);
        if (out != null && out.getBoolean("outlaw")) {
            int owe = Outlaws.fine(out) + out.getInt("bounty");
            if (owe > 0) {
                s.liabilities.add(new Row(BILLS, "Outlaw's account", owe * 100L, town, "page:" + id + ":Watch", "Your fine and the bounty on you: pay it to a guard, or give yourself up."));
                bill(s, "Outlaw's account", town, owe * 100L, day, true, "Wanted in " + town + " till it is paid.");
            }
        }
    }

    static void bill(Sheet s, String what, String town, long pence, long day, boolean overdue, String tip) {   // [join21] FinanceJoin too
        CompoundTag t = new CompoundTag();
        t.putString("what", what);
        t.putString("town", town);
        t.putLong("amount", pence);
        t.putLong("day", day);
        t.putBoolean("overdue", overdue);
        t.putString("tip", tip);
        s.bills.add(t);
    }

    // ------------------------------------------------------------------ the page

    /** The player's finances, as its screen draws them. */
    public static CompoundTag page(ServerLevel level, UUID who, @Nullable Player online, boolean self) {
        Sheet s = work(level, who, online);
        long day = level.getDayTime() / 24000L;
        CompoundTag t = new CompoundTag();
        t.putString("name", online != null ? online.getName().getString() : PlayerFinance.player(who).getString("name"));
        t.putLong("day", day);
        t.putBoolean("self", self);
        t.putBoolean("cashKnown", s.cashKnown);
        long[] week = PlayerFinance.inOut(PlayerFinance.span(who, day, 7)), last = PlayerFinance.inOut(PlayerFinance.span(who, day - 7, 7));
        t.putLong("net", s.net());
        t.putLong("assets", s.assetsTotal());
        t.putLong("liabilities", s.liabilitiesTotal());
        t.putLong("cash", s.cash);
        t.putLong("inWeek", week[0]);
        t.putLong("outWeek", week[1]);
        t.putLong("inLast", last[0]);
        t.putLong("outLast", last[1]);
        Map<Long, long[]> hist = PlayerFinance.history(who);
        long[] weekAgo = null;
        for (Map.Entry<Long, long[]> e : hist.entrySet()) if (e.getKey() <= day - 7) weekAgo = e.getValue();
        if (weekAgo != null) t.putLong("netWeekAgo", weekAgo[0]);
        ListTag a = new ListTag(), lb = new ListTag();
        for (Row r : s.assets) a.add(r.tag());
        for (Row r : s.liabilities) lb.add(r.tag());
        t.put("assetRows", a);
        t.put("liabilityRows", lb);
        // The kinds of money, this week and last, and over sixty days.
        Map<PlayerFinance.Cat, Long> w = PlayerFinance.span(who, day, 7), lw = PlayerFinance.span(who, day - 7, 7), all = PlayerFinance.span(who, day, PlayerFinance.DAYS);
        ListTag cats = new ListTag();
        for (PlayerFinance.Cat c : PlayerFinance.Cat.values()) {
            long x = w.getOrDefault(c, 0L), y = lw.getOrDefault(c, 0L), z = all.getOrDefault(c, 0L);
            if (x == 0 && y == 0 && z == 0) continue;
            CompoundTag r = new CompoundTag();
            r.putString("c", c.name());
            r.putString("label", c.label);
            r.putString("kind", c.kind.name());
            r.putLong("w", x);
            r.putLong("lw", y);
            r.putLong("all", z);
            cats.add(r);
        }
        t.put("cats", cats);
        // Sixty days of charts: net worth and cash (carried forward over days not seen), income and spending.
        int n = PlayerFinance.DAYS;
        long[] net = new long[n], cash = new long[n], in = new long[n], outs = new long[n];
        long[] carry = null;
        int firstSeen = -1;
        for (Map.Entry<Long, long[]> e : hist.entrySet()) if (e.getKey() <= day - n) carry = e.getValue();
        for (int i = 0; i < n; i++) {
            long d = day - n + 1 + i;
            long[] h = hist.get(d);
            if (h != null) carry = h;
            if (carry != null && firstSeen < 0) firstSeen = i;
            net[i] = carry == null ? 0 : carry[0];
            cash[i] = carry == null ? 0 : carry[1];
            long[] io = PlayerFinance.inOut(PlayerFinance.day(who, d));
            in[i] = io[0];
            outs[i] = io[1];
        }
        t.putLongArray("sNet", net);
        t.putLongArray("sCash", cash);
        t.putLongArray("sIn", in);
        t.putLongArray("sOut", outs);
        t.putInt("firstSeen", Math.max(0, firstSeen));
        t.put("businesses", s.businesses);
        t.put("properties", s.properties);
        t.put("shares", s.shares);
        t.put("bonds", s.bonds);
        ListTag bills = s.bills.copy();
        List<Tag> sorted = new ArrayList<>(bills);
        sorted.sort((x, y) -> {
            CompoundTag p = (CompoundTag) x, q = (CompoundTag) y;
            if (p.getBoolean("overdue") != q.getBoolean("overdue")) return p.getBoolean("overdue") ? -1 : 1;
            long pd = p.getLong("day") < 0 ? Long.MAX_VALUE : p.getLong("day"), qd = q.getLong("day") < 0 ? Long.MAX_VALUE : q.getLong("day");
            return Long.compare(pd, qd);
        });
        ListTag billsOut = new ListTag();
        billsOut.addAll(sorted);
        t.put("bills", billsOut);
        t.put("ledger", PlayerFinance.ledger(who));
        t.put("towns", towns(level, s, who, day));
        long[] nw = PlayerFinance.inOut(PlayerFinance.spanTown(who, "", day, 7));
        t.putLong("unplacedIn", nw[0]);
        t.putLong("unplacedOut", nw[1]);
        t.putLong("unplacedWorth", s.unplaced());
        return t;
    }

    /**
     * [purse20] One card for each town the player has anything in (a holding, a debt, a bill, money with it in sixty
     * days, a place in it as a citizen, knight or constable): its name, colour and banner; what the player is in it; its
     * net worth there and its groups; income and spending with it, this week and last, by kind; sixty days of charts; and
     * what share it holds of the town's land and of each firm.
     */
    static ListTag towns(ServerLevel level, Sheet s, UUID who, long day) {
        Map<String, long[]> by = s.byTown();
        java.util.Set<String> ids = new java.util.LinkedHashSet<>(by.keySet());
        for (String k : PlayerFinance.townsDealt(who)) if (!k.isEmpty()) ids.add(k);
        for (Villages.Village v : Villages.every()) {
            UUID id = v.id();
            if (Citizens.is(id, who) || Knights.isKnight(id, who) || PlayerLaw.sworn(id, who)) ids.add(id.toString());
        }
        ListTag out = new ListTag();
        for (String vid : ids) {
            UUID id;
            try { id = UUID.fromString(vid); } catch (IllegalArgumentException e) { continue; }
            Villages.Village v = Villages.get(id);
            CompoundTag c = new CompoundTag();
            c.putString("vid", vid);
            c.putString("name", Villages.name(id));
            c.putInt("colour", Villages.colour(id));
            if (v != null) {
                try { c.put("banner", Heraldry.report(level, v)); } catch (RuntimeException ignored) { }
            }
            // What the player is in it.
            ListTag roles = new ListTag();
            if (Citizens.is(id, who)) roles.add(net.minecraft.nbt.StringTag.valueOf("Citizen"));
            if (Knights.isKnight(id, who)) roles.add(net.minecraft.nbt.StringTag.valueOf("Knight"));
            if (PlayerLaw.sworn(id, who)) roles.add(net.minecraft.nbt.StringTag.valueOf("Constable"));
            long[] ab = by.getOrDefault(vid, new long[2]);
            c.putLong("assets", ab[0]);
            c.putLong("liabilities", ab[1]);
            c.putLong("net", ab[0] - ab[1]);
            CompoundTag groups = new CompoundTag();
            for (Row r : s.assets) if (r.townId().equals(vid)) groups.putLong(r.group(), groups.getLong(r.group()) + r.value());
            for (Row r : s.liabilities) if (r.townId().equals(vid)) groups.putLong(r.group(), groups.getLong(r.group()) - r.value());
            c.put("groups", groups);
            boolean home = false, landlord = false, trader = false, owner = false;
            for (Row r : s.assets) if (r.townId().equals(vid)) {
                if (r.group().equals(PROPERTY) && !r.label().startsWith("Title: ")) landlord = true;
                if (r.link().startsWith("stall:")) trader = true;
                if (r.link().startsWith("books:")) owner = true;
            }
            for (Tag e : s.properties) if (((CompoundTag) e).getString("vid").equals(vid) && ((CompoundTag) e).getLong("rentIn") == 0 && "House".equals(((CompoundTag) e).getString("kind"))) home = true;
            if (home) roles.add(net.minecraft.nbt.StringTag.valueOf("Home owner"));
            else if (landlord) roles.add(net.minecraft.nbt.StringTag.valueOf("Landlord"));
            if (owner) roles.add(net.minecraft.nbt.StringTag.valueOf("Business owner"));
            if (trader) roles.add(net.minecraft.nbt.StringTag.valueOf("Stallholder"));
            if (PlayerShops.of(id, who) != null) roles.add(net.minecraft.nbt.StringTag.valueOf("Shopkeeper"));   // [join21]
            c.put("roles", roles);
            // Its share of the land and of the firms.
            CompoundTag titles = LandTitles.titles(id);
            int mine = 0;
            for (String k : titles.getAllKeys()) if (who.toString().equals(titles.getCompound(k).getString("landlord"))) mine++;
            c.putInt("titlesMine", mine);
            c.putInt("titlesAll", titles.getAllKeys().size());
            ListTag firms = new ListTag();
            for (Business b : Businesses.all(id)) {
                if (b.mirror()) continue;
                int parts = 0;
                for (Business.Stake st : b.owners) if (st.holder() == Business.Holder.PLAYER && who.equals(st.who())) parts += st.parts();
                if (parts <= 0) continue;
                CompoundTag f = new CompoundTag();
                f.putString("name", b.name);
                f.putInt("parts", parts);
                firms.add(f);
            }
            c.put("firms", firms);
            // Money with it.
            Map<PlayerFinance.Cat, Long> w = PlayerFinance.spanTown(who, vid, day, 7), lw = PlayerFinance.spanTown(who, vid, day - 7, 7),
                all = PlayerFinance.spanTown(who, vid, day, PlayerFinance.DAYS);
            long[] io = PlayerFinance.inOut(w), lio = PlayerFinance.inOut(lw);
            c.putLong("inWeek", io[0]);
            c.putLong("outWeek", io[1]);
            c.putLong("inLast", lio[0]);
            c.putLong("outLast", lio[1]);
            ListTag cats = new ListTag();
            for (PlayerFinance.Cat cat : PlayerFinance.Cat.values()) {
                long x = w.getOrDefault(cat, 0L), y = lw.getOrDefault(cat, 0L), z = all.getOrDefault(cat, 0L);
                if (x == 0 && y == 0 && z == 0) continue;
                CompoundTag r = new CompoundTag();
                r.putString("c", cat.name());
                r.putString("label", cat.label);
                r.putString("kind", cat.kind.name());
                r.putLong("w", x);
                r.putLong("lw", y);
                r.putLong("all", z);
                cats.add(r);
            }
            c.put("cats", cats);
            int n = PlayerFinance.DAYS;
            long[] net = new long[n], in = new long[n], outs = new long[n];
            Map<Long, Long> hist = PlayerFinance.historyTown(who, vid);
            Long carry = null;
            int firstSeen = -1;
            for (Map.Entry<Long, Long> e : hist.entrySet()) if (e.getKey() <= day - n) carry = e.getValue();
            for (int i = 0; i < n; i++) {
                long d = day - n + 1 + i;
                if (hist.containsKey(d)) carry = hist.get(d);
                if (carry != null && firstSeen < 0) firstSeen = i;
                net[i] = carry == null ? 0 : carry;
                long[] dio = PlayerFinance.inOut(PlayerFinance.spanTown(who, vid, d, 1));
                in[i] = dio[0];
                outs[i] = dio[1];
            }
            c.putLongArray("sNet", net);
            c.putLongArray("sIn", in);
            c.putLongArray("sOut", outs);
            c.putInt("firstSeen", Math.max(0, firstSeen));
            out.add(c);
        }
        return out;
    }

    // ------------------------------------------------------------------ the notices

    /** The week's summary: "This week: +84c income, -31c spending, net worth 1,204c ▲". */
    static CompoundTag weekToast(UUID who, long[] io, Sheet s, long lastDayOfWeek) {
        CompoundTag t = new CompoundTag();
        t.putString("kind", "week");
        long before = Long.MIN_VALUE;
        for (Map.Entry<Long, long[]> e : PlayerFinance.history(who).entrySet()) if (e.getKey() <= lastDayOfWeek - 7) before = e.getValue()[0];
        String arrow = before == Long.MIN_VALUE ? "" : s.net() > before ? " ▲" : s.net() < before ? " ▼" : "";
        t.putString("title", "Your week in coin");
        t.putString("text", "This week: " + (io[0] > 0 ? "+" : "") + coins(io[0]) + " income, " + (io[1] > 0 ? "-" : "") + coins(io[1]) + " spending, net worth " + coins(s.net()) + arrow);
        t.putInt("dir", before == Long.MIN_VALUE ? 0 : Long.compare(s.net(), before));
        return t;
    }

    /** Bills due within two days or overdue, in one notice (null: none). */
    @Nullable
    static CompoundTag billsToast(Sheet s, long day) {
        int overdue = 0, soon = 0;
        long sum = 0;
        String first = "";
        for (Tag e : s.bills) {
            CompoundTag b = (CompoundTag) e;
            if (b.getLong("amount") <= 0) continue;
            boolean od = b.getBoolean("overdue");
            boolean near = !od && b.getLong("day") >= 0 && b.getLong("day") - day <= 2;
            if (!od && !near) continue;
            if (od) overdue++; else soon++;
            sum += b.getLong("amount");
            if (first.isEmpty()) first = b.getString("what") + " in " + b.getString("town");
        }
        if (overdue + soon == 0) return null;
        CompoundTag t = new CompoundTag();
        t.putString("kind", "bills");
        t.putString("title", overdue > 0 ? overdue + (overdue == 1 ? " bill overdue" : " bills overdue") : soon + (soon == 1 ? " bill due soon" : " bills due soon"));
        t.putString("text", first + (overdue + soon > 1 ? " and " + (overdue + soon - 1) + " more" : "") + ": " + coins(sum) + " all told");
        t.putInt("dir", overdue > 0 ? -1 : 0);
        return t;
    }

    static void notify(ServerPlayer p, CompoundTag t) {
        PacketDistributor.sendToPlayer(p, new FinancePayload(FinancePayload.TOAST, t));
    }

    /** "1,204c", whole coins (rounded down) with thousands marked. */
    static String coins(long pence) {
        return String.format(Locale.ROOT, "%,dc", pence / 100);
    }

    // ------------------------------------------------------------------ the buttons

    /** From the screen (FinanceNetwork): the page asked for, or one of its links followed. Never trusted as sent. */
    public static void act(ServerPlayer p, String action, CompoundTag args) {
        ServerLevel level = p.serverLevel();
        switch (action) {
            case "page" -> {
                String name = args.getString("who");
                String refused = refusal(p, name);
                if (refused != null) {
                    send(p, FinancePayload.SAID, said(refused));
                    return;
                }
                UUID who = whose(p, name);
                Player online = who.equals(p.getUUID()) ? p : p.server.getPlayerList().getPlayer(who);
                CompoundTag page = page(level, who, online, who.equals(p.getUUID()));
                page.putBoolean("op", p.hasPermissions(2));
                page.putBoolean("open", args.getBoolean("open"));
                send(p, FinancePayload.PAGE, page);
            }
            case "link" -> link(p, level, args.getString("k"));
            default -> { }
        }
    }

    /** Why this player may not see the finances it asked for (another's, by name), or null if it may. Only an operator
     *  sees another player's. */
    @Nullable
    public static String refusal(ServerPlayer asker, String name) {
        return refusal(asker, name, asker.hasPermissions(2));
    }

    /** The same, with whether the asker is an operator given (tests). */
    @Nullable
    public static String refusal(ServerPlayer asker, String name, boolean operator) {
        if (name == null || name.isEmpty() || name.equalsIgnoreCase(asker.getGameProfile().getName())) return null;
        if (!operator) return "Only an operator may look at another player's finances.";
        if (asker.server.getPlayerList().getPlayerByName(name) == null && PlayerFinance.byName(name) == null) return "No books for a player called " + name + ".";
        return null;
    }

    /** Whose finances a (permitted) ask is for. */
    public static UUID whose(ServerPlayer asker, String name) {
        if (name == null || name.isEmpty() || name.equalsIgnoreCase(asker.getGameProfile().getName())) return asker.getUUID();
        ServerPlayer other = asker.server.getPlayerList().getPlayerByName(name);
        if (other != null) return other.getUUID();
        UUID known = PlayerFinance.byName(name);
        return known != null ? known : asker.getUUID();
    }

    /** A row's link: the town's books at a page, a business's books, the player's stall. Only its own. */
    static void link(ServerPlayer p, ServerLevel level, String k) {
        String[] f = k.split(":", 3);
        if (f.length < 2) return;
        UUID id;
        try { id = UUID.fromString(f[1]); } catch (IllegalArgumentException e) { return; }
        Villages.Village v = Villages.get(id);
        if (v == null) return;
        switch (f[0]) {
            case "page" -> {
                CompoundTag books = Annals.snapshot(level, v);
                CivicLand.viewer(books, level, v, p);
                if (f.length > 2) books.putString("page", f[2]);
                PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
            }
            case "books" -> {
                if (f.length < 3) return;
                Business b = Businesses.of(id, f[2]);
                if (b != null && b.ownedBy(p.getUUID())) BusinessBooks.show(p, id, b);
            }
            case "shop" -> PlayerLife.open(p, "shop", null);                 // [join21] its shop's screen
            case "property" -> PropertyOffice.openOn(p, "property", null, "");   // [join21] the property office
            case "stall" -> {
                PlayerStalls.Booth b = PlayerStalls.boothOf(id, p.getUUID());
                if (b != null) PlayerStalls.open(p, level, v, b);
                else send(p, FinancePayload.SAID, said("You have no booth in " + Villages.name(id) + " just now."));
            }
            default -> { }
        }
    }

    private static CompoundTag said(String words) {
        CompoundTag t = new CompoundTag();
        t.putString("text", words);
        return t;
    }

    static void send(ServerPlayer p, int kind, CompoundTag t) {
        PacketDistributor.sendToPlayer(p, new FinancePayload(kind, t));
    }

    // ------------------------------------------------------------------ the operators' tool

    /** /village finances [player]: an operator opens its own finances, or a player's (for scripts and the admin; the
     *  players themselves open theirs from the game: a key, the inventory, the journal, the books, the bank). */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> command() {
        return net.minecraft.commands.Commands.literal("finances").requires(src -> src.hasPermission(2))
            .executes(ctx -> {
                open(ctx.getSource().getPlayerOrException());
                return 1;
            })
            .then(net.minecraft.commands.Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    CompoundTag args = new CompoundTag();
                    args.putBoolean("open", true);
                    args.putString("who", com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player"));
                    act(p, "page", args);
                    return 1;
                }));
    }

    // ------------------------------------------------------------------ the entry point

    /** [purse20] Open a player's finances on its screen (other pages and buttons call this: the life-in-town page, an
     *  admin tool). Server side; the client's FinanceClient.open does the same from the client. */
    public static void open(ServerPlayer p) {
        CompoundTag args = new CompoundTag();
        args.putBoolean("open", true);
        act(p, "page", args);
    }
}
