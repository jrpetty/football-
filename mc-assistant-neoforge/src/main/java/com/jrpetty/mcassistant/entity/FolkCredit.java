package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [capital] The folk's own lending: savings put by, and lent on to the town's firms at a market's rate.
 *
 * <p><b>The savings club.</b> From Market Opening Day, before a town has a bank, its savings club keeps a chest at the
 * market: each morning a folk with more in its purse than its week wants (Bank.keep: its living, its rent, its payments)
 * puts its nature's share of the rest in (a thrifty one three parts in four, a spendthrift none), and draws it out again
 * when its purse runs under half of what it keeps. The chest is coin the census counts; what each saver has in it is a
 * claim on it. Once the town's bank stands and opens, the club is folded into it: every saver's balance becomes its
 * account at the bank, the chest goes into the vault, and the club's loans are the bank's (Bank keeps savings from then).
 *
 * <p><b>The rate.</b> The lender's rate follows its own reserves and its losses: two and a half in the hundred a week with
 * its vault full of what it may lend, up to seven and a half with nothing left to lend, and three points more for every
 * tenth of what it lent in the last four weeks that was never repaid. Its savers are paid two fifths of the lending rate
 * (never under half a point), out of what the loans and its bonds earned in the week, never more: no loans, no
 * interest, and no coin made.
 *
 * <p><b>Credit.</b> A firm short of coin for its orders, its kit or its wages asks the lender (Businesses.raise, after
 * its owners and the town's trade credit). The lender reads its books: its last week's margin, its till against its
 * float, how it stands (thriving, trading, struggling, new) and its record with the lender. A thriving firm may owe up to
 * a week and a half's takings, a trading one a week's, a struggling one a third of a week's, a new one half its founders'
 * capital; a firm losing a fifth of its takings, or one that has defaulted before, nothing. What it lends is lent at the
 * day's rate and repaid out of the till a day at a time (Businesses.repay). A firm whose loan has not come down in a week
 * is behind (and lent no more); two weeks behind and owing more than it is worth, its lender takes it to the court like
 * any creditor (Enterprise.petitionFor), and what the receiver cannot pay is written off: a default, and the rate goes up
 * for everybody.
 *
 * <p><b>Wages on credit.</b> A firm's undrawn credit counts toward what it can afford to pay (LabourMarket.cap): a month's
 * worth of its credit spread over the month.
 *
 * <p>Players save and borrow too (/village capital deposit, withdraw, borrow, repay): a player may borrow up to what it
 * has with the town as security (its savings, its bonds and its shares at their last price), at the day's rate.
 */
public final class FolkCredit {

    private FolkCredit() {}

    /** The rate on a loan, a week, hundredths of a percent: with the vault full, empty, and per tenth defaulted; its bounds. */
    static final int FULL_BP = 250, EMPTY_BP = 750, DEFAULT_BP = 300, LEAST_BP = 200, MOST_BP = 1500;
    /** The savers' share of the lending rate, in the hundred, and the least they are paid, hundredths of a percent. */
    static final int SAVERS_SHARE = 40, LEAST_DEPOSIT_BP = 50;
    /** The share of deposits the club keeps in its chest, never lent, in the hundred. */
    static final int RESERVE = 30;
    /** Days a defaulted loan counts against the rate. */
    static final int DEFAULT_DAYS = 28;
    /** The weeks a firm's loan may stand still before it is behind, and behind before its lender goes to court. */
    static final int BEHIND_WEEKS = 2;

    /** What the last debit was for (Accounts.debit, Businesses.paysHand): the lender writes it in its book. */
    static String purpose = "its orders";

    static CompoundTag club(UUID village) {
        return CapitalBook.part(village, "club");
    }

    static boolean bank(UUID village) {
        return Bank.open(village);
    }

    /** "bank" or "club": who holds the town's bonds for the savers, and lends to its firms. */
    static String bondHolderKind(UUID village) {
        return bank(village) ? "bank" : "club";
    }

    public static String lenderName(UUID village) {
        return bank(village) ? "the bank" : "the savings club";
    }

    // ------------------------------------------------------------------ the figures

    /** The coin in the club's chest, pennies (a holder the census counts; nought once folded into the bank). */
    public static long clubCash(UUID village) {
        return club(village).getLong("cash");
    }

    /** Pennies into the club's chest (it keeps them to the penny), or out of it as far as it has them: returns what moved. */
    static long chestIn(UUID village, long p) {
        if (p <= 0) return 0;
        Accounts.add(club(village), "cash", p);
        CapitalBook.dirty();
        return p;
    }

    static long chestOut(UUID village, long p) {
        CompoundTag c = club(village);
        long k = Math.max(0, Math.min(p, c.getLong("cash")));
        c.putLong("cash", c.getLong("cash") - k);
        CapitalBook.dirty();
        return k;
    }

    /** What the savers have deposited, pennies. */
    public static long deposits(UUID village) {
        if (bank(village)) return Bank.deposits(village) * 100L;
        long n = 0;
        CompoundTag a = Accounts.sub(club(village), "acct");
        for (String k : a.getAllKeys()) n += Math.max(0, a.getInt(k));
        return n * 100L;
    }

    /** What the lender's vault holds, coins. */
    static int vault(UUID village) {
        return bank(village) ? Bank.cash(village) : (int) (club(village).getLong("cash") / 100);
    }

    /** What it may lend, coins: its vault less what it keeps back of the deposits. */
    static int lendable(UUID village) {
        int dep = (int) (deposits(village) / 100);
        return Math.max(0, vault(village) - (dep * RESERVE + 99) / 100);
    }

    /** What the town's firms owe the lender, pennies. */
    public static long loansOut(ServerLevel level, UUID village) {
        long n = 0;
        for (Business b : Businesses.all(village)) if (b.loan > 0 && ("bank".equals(b.lender) || "club".equals(b.lender))) n += b.loan;
        return n;
    }

    /** What was written off in the last four weeks, pennies. */
    static long defaulted(UUID village) {
        long n = 0, day = CapitalBook.dayNow();
        for (Tag e : CapitalBook.list(club(village), "defaults")) {
            CompoundTag d = (CompoundTag) e;
            if (day - d.getLong("day") <= DEFAULT_DAYS) n += d.getLong("p");
        }
        return n;
    }

    /** What it lent in the last four weeks, pennies. */
    static long lentLately(UUID village) {
        long n = 0, day = CapitalBook.dayNow();
        for (Tag e : CapitalBook.list(club(village), "lent")) {
            CompoundTag d = (CompoundTag) e;
            if (day - d.getLong("day") <= DEFAULT_DAYS) n += d.getLong("p");
        }
        return n;
    }

    /** The share of what it lent lately written off. */
    static double defaultRate(UUID village) {
        long lent = Math.max(lentLately(village), loansOutQuick(village));
        return lent <= 0 ? (defaulted(village) > 0 ? 1.0 : 0.0) : Math.min(1.0, defaulted(village) / (double) lent);
    }

    static long loansOutQuick(UUID village) {
        long n = 0;
        for (Business b : Businesses.all(village)) if (b.loan > 0 && ("bank".equals(b.lender) || "club".equals(b.lender))) n += b.loan;
        return n;
    }

    /** The lending rate today, a week, hundredths of a percent: its reserves and its losses. */
    public static int rateBp(UUID village) {
        int dep = (int) (deposits(village) / 100);
        double room = dep <= 0 ? (vault(village) > 0 ? 1.0 : 0.0) : Math.min(1.0, lendable(village) / Math.max(1.0, dep * (100 - RESERVE) / 100.0));
        int bp = (int) Math.round(EMPTY_BP - (EMPTY_BP - FULL_BP) * room + DEFAULT_BP * 10 * defaultRate(village));
        return Math.max(LEAST_BP, Math.min(MOST_BP, bp));
    }

    /** What its savers are paid, a week, hundredths of a percent. */
    public static int depositBp(UUID village) {
        return Math.max(LEAST_DEPOSIT_BP, rateBp(village) * SAVERS_SHARE / 100);
    }

    // ------------------------------------------------------------------ the club's savers

    static CompoundTag accounts(UUID village) {
        return Accounts.sub(club(village), "acct");
    }

    public static int balance(UUID village, UUID who) {
        if (bank(village)) return Bank.balanceForTests(village, who);
        return accounts(village).getInt(who.toString());
    }

    /** What a folk has at the savings club, coins (nought once the bank keeps it: Bank.balance). */
    public static int clubBalance(VillageFolkEntity f) {
        UUID id = f.ownerId();
        return id == null || bank(id) ? 0 : accounts(id).getInt(f.getUUID().toString());
    }

    /** So many coins of a folk's savings out of the club's chest into its purse, as far as both go (a founder's stake). */
    static int drawOut(UUID village, VillageFolkEntity f, int n) {
        CompoundTag a = accounts(village);
        String k = f.getUUID().toString();
        int got = (int) Math.min(Math.min(n, a.getInt(k)), club(village).getLong("cash") / 100);
        if (got <= 0) return 0;
        a.putInt(k, a.getInt(k) - got);
        if (a.getInt(k) <= 0) a.remove(k);
        chestOut(village, got * 100L);
        f.earn(got);
        return got;
    }

    static void credit(UUID village, UUID who, String name, int coins) {
        CompoundTag a = accounts(village);
        a.putInt(who.toString(), a.getInt(who.toString()) + coins);
        Accounts.sub(club(village), "names").putString(who.toString(), name);
        CapitalBook.dirty();
    }

    /** The morning (CapitalBook.morning): folded into the bank once it opens; the club's savings in and out; its week;
     *  the loans looked at. */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag c = club(id);
        if (bank(id)) {
            if (!c.getBoolean("merged")) merge(level, v, day);
        } else {
            if (c.getLong("opened") <= 0) {
                c.putLong("opened", day + 1);
                CapitalBook.tell(id, day, "a savings club opened at the market: folk put by what they can spare, and it is lent to the town's firms");
            }
            saveAndDraw(level, v, day);
            if ((day + 1 - c.getLong("opened")) % 7 == 0 && c.getLong("weekOn") != day + 1) {
                c.putLong("weekOn", day + 1);
                week(level, v, day);
            }
        }
        loans(level, v, day);
        CapitalBook.dirty();
    }

    /** The day's savings: in, over the week's keep; out, a purse run under half of it. */
    static void saveAndDraw(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag c = club(id);
        CompoundTag a = accounts(id);
        VillageFolkEntity most = null;
        int mostIn = 0;
        for (AssistantEntity e : Villages.folkOf(id)) {
            if (!(e instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isHired()) continue;
            String k = f.getUUID().toString();
            int keep = CapitalBook.keep(id, f);
            int bal = a.getInt(k);
            if (bal > 0 && f.purse() < keep / 2) {
                int out = (int) Math.min(Math.min(bal, keep - f.purse()), c.getLong("cash") / 100);
                if (out > 0) {
                    a.putInt(k, bal - out);
                    if (a.getInt(k) <= 0) a.remove(k);
                    chestOut(id, out * 100L);
                    f.earn(out);
                    Accounts.add(c, "weekOut", out);
                }
                continue;
            }
            if (Homes.rentsAndWantsToOwn(f)) continue;                      // its savings go toward its house (Homes)
            int over = f.purse() - keep;
            if (over < 3) continue;
            int n = over * Bank.share(Bank.thrift(f)) / 100;
            if (n <= 0 || !f.spend(n)) continue;
            chestIn(id, n * 100L);
            credit(id, f.getUUID(), f.displayNameCap(), n);
            Accounts.add(c, "weekIn", n);
            if (n > mostIn) { mostIn = n; most = f; }
        }
        if (most != null && level.getRandom().nextInt(5) == 0) {
            FolkTalk.speak(most, FolkTalk.pick(level.getRandom(), "Into the club with it. It earns while I sleep.",
                "A bit more put by at the savings club.", "The club pays " + TownBonds.pct(depositBp(id)) + " a week. Better than my mattress."));
        }
    }

    /** The club's week: its savers paid their interest out of what its loans and bonds earned, never more. */
    static void week(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag c = club(id);
        long earned = c.getLong("earned");
        CompoundTag a = accounts(id);
        long total = 0;
        for (String k : a.getAllKeys()) total += Math.max(0, a.getInt(k));
        int bp = depositBp(id);
        long pot = Math.min(earned * 80 / 100, total * 100L * bp / 10000);   // a fifth of what it earned kept against a bad debt
        int coins = (int) Math.min(pot / 100, c.getLong("cash") / 100);
        int given = 0;
        if (coins > 0 && total > 0) {
            List<String> keys = new ArrayList<>(a.getAllKeys());
            java.util.Collections.sort(keys);
            double[] odd = new double[keys.size()];
            int[] got = new int[keys.size()];
            for (int i = 0; i < keys.size(); i++) {
                double exact = coins * (double) Math.max(0, a.getInt(keys.get(i))) / total;
                got[i] = (int) Math.floor(exact);
                odd[i] = exact - got[i];
                given += got[i];
            }
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) order.add(i);
            order.sort((p, q) -> Double.compare(odd[q], odd[p]));
            for (int k = 0; k < order.size() && given < coins; k++) { got[order.get(k)]++; given++; }
            CompoundTag allI = Accounts.sub(c, "interestAll");
            for (int i = 0; i < keys.size(); i++) {
                if (got[i] <= 0) continue;
                a.putInt(keys.get(i), a.getInt(keys.get(i)) + got[i]);       // the coin is already in the chest: what the loans paid in
                allI.putInt(keys.get(i), allI.getInt(keys.get(i)) + got[i]);
            }
        }
        c.putLong("earned", 0);                                              // what was not paid out stays in the chest, its own
        CompoundTag lw = new CompoundTag();
        lw.putLong("earned", earned);
        lw.putInt("paid", given);
        lw.putLong("in", c.getLong("weekIn"));
        lw.putLong("out", c.getLong("weekOut"));
        c.put("lastWeek", lw);
        c.putLong("weekIn", 0);
        c.putLong("weekOut", 0);
        if (given > 0) CapitalBook.tell(id, day, "the savings club's week: " + given + Homes.coins(given) + " paid to its savers at " + TownBonds.pct(bp)
            + " a week, out of what its loans and bonds earned");
    }

    /** The bank has opened: every saver's balance its account there, the chest into the vault, the club's loans the bank's. */
    static void merge(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag c = club(id);
        c.putBoolean("merged", true);
        CompoundTag a = accounts(id), names = Accounts.sub(c, "names");
        long chest = c.getLong("cash");
        int cash = (int) (chest / 100);
        if (chest - cash * 100L > 0) Businesses.toTreasury(id, chest - cash * 100L);   // the odd pennies: the town's (the vault keeps whole coins)
        java.util.Map<UUID, Integer> bal = new java.util.LinkedHashMap<>();
        java.util.Map<UUID, String> nm = new java.util.HashMap<>();
        for (String k : a.getAllKeys()) {
            try {
                UUID u = UUID.fromString(k);
                bal.put(u, a.getInt(k));
                nm.put(u, names.getString(k));
            } catch (IllegalArgumentException ignored) { }
        }
        Bank.absorbClub(id, bal, nm, cash, day);
        c.putLong("cash", 0);
        c.remove("acct");
        for (Business b : Businesses.all(id)) if ("club".equals(b.lender)) b.lender = "bank";
        for (Tag e : TownBonds.bonds(id)) if ("club".equals(((CompoundTag) e).getString("kind"))) {
            ((CompoundTag) e).putString("kind", "bank");
            ((CompoundTag) e).putString("name", "the bank");
        }
        Businesses.dirty();
        if (cash > 0 || !bal.isEmpty()) CapitalBook.tell(id, day, "the savings club was folded into the new bank: " + bal.size() + " savers' accounts and "
            + cash + Homes.coins(cash) + " in its chest");
    }

    // ------------------------------------------------------------------ the bonds

    /** What the lender puts into the town's bonds today, pennies (whole coins), out of its chest: up to half what it may lend,
     *  if the bond pays a point over its savers' rate. The coin leaves the vault. */
    static long forBonds(UUID village, long want, int bp) {
        if (bp < depositBp(village) + 50) return 0;
        int coins = (int) Math.min((want + 99) / 100, lendable(village) / 2);
        if (coins <= 0) return 0;
        if (bank(village)) coins = Bank.takeForCapital(village, coins);
        else coins = (int) (chestOut(village, coins * 100L) / 100);
        CapitalBook.dirty();
        return coins * 100L;
    }

    /** A coupon or a repayment on its bonds back into the vault (the odd pennies carried); a coupon is earnings. */
    static void bondIncome(UUID village, String kind, long p, boolean coupon) {
        if ("bank".equals(kind) && bank(village)) {
            int coins = (int) (p / 100);
            Bank.giveFromCapital(village, coins, coupon);
            if (p - coins * 100L > 0) Businesses.toTreasury(village, p - coins * 100L);   // the vault keeps whole coins
            return;
        }
        chestIn(village, p);
        if (coupon) Accounts.add(club(village), "earned", p);
        CapitalBook.dirty();
    }

    // ------------------------------------------------------------------ lending to firms

    /** Businesses.raise: a firm short of coin asks the lender; it reads the firm's books and lends what it judges, at the
     *  day's rate. Returns the pennies lent (into the till, onto its loan). */
    static long lend(ServerLevel level, UUID village, Business b, long need) {
        if (need <= 0 || !MarketMaker.live(village) && !bank(village)) return 0;
        long day = Businesses.today(level);
        String why = purpose;
        purpose = "its orders";
        Judgement j = judge(level, village, b, day);
        long ask = (need + 99) / 100 * 100;
        long can = Math.min(ask, Math.min(j.room, lendable(village) * 100L));
        int coins = (int) (can / 100);
        if (coins <= 0) {
            decided(village, day, b.name, ask, 0, j.room <= 0 ? j.why : "the " + (bank(village) ? "bank's vault" : "club's chest") + " has nothing to lend", why);
            return 0;
        }
        int rate = rateBp(village);                                          // the day's rate, before this loan leaves the vault
        if (bank(village)) coins = Bank.lendToFirm(village, coins);
        else coins = (int) (chestOut(village, coins * 100L) / 100);
        if (coins <= 0) return 0;
        long p = coins * 100L;
        if (b.loan <= 0) {
            b.rateBp = rate;
            CompoundTag s = Accounts.sub(Accounts.sub(club(village), "sched"), b.id);
            s.putLong("since", day);
            s.putLong("check", day);
            s.putLong("was", 0);
            s.putInt("behind", 0);
        }
        b.till += p;
        b.loan += p;
        b.lender = bank(village) ? "bank" : "club";
        CompoundTag s = Accounts.sub(Accounts.sub(club(village), "sched"), b.id);
        s.putLong("was", s.getLong("was") + p);
        CompoundTag lent = new CompoundTag();
        lent.putLong("day", day);
        lent.putLong("p", p);
        ListTag ll = CapitalBook.list(club(village), "lent");
        ll.add(lent);
        while (ll.size() > 60) ll.remove(0);
        b.note(day, lenderName(village) + " lent it " + Businesses.pence(p) + " for " + why + " at " + TownBonds.pct(b.rateBp) + " a week (" + j.why + ")");
        decided(village, day, b.name, ask, p, j.why, why);
        Businesses.dirty();
        CapitalBook.dirty();
        return p;
    }

    /** The lender's view of a firm: how much more it may owe, and why. */
    record Judgement(long room, String why) {}

    static Judgement judge(ServerLevel level, UUID village, Business b, long day) {
        if (b.town() || b.mirror()) return new Judgement(0, "the town's own");
        if (Accounts.sub(club(village), "bad").contains(b.id)) return new Judgement(0, "it defaulted on a loan before");
        CompoundTag r = Enterprise.rec(village, b.id);
        if (r != null && r.contains("petition")) return new Judgement(0, "it is before the court");
        Business.Day w = b.last(day - 1, 7);
        double margin = w.revenue <= 0 ? -1 : w.net() / (double) w.revenue;
        String status = r == null ? (day - b.opened < 7 ? "new" : w.net() >= 0 ? "trading" : "struggling") : r.getString("status");
        long limit;
        String why;
        if ("new".equals(status) || day - b.opened < 7) {
            long cap = r == null ? 1000 : Math.max(500, r.getLong("capital"));
            limit = cap / 2;
            why = "a new firm: half its founders' capital";
        } else if (w.revenue > 0 && margin < -0.2) {
            return new Judgement(0, "it lost " + Math.round(-margin * 100) + " in the hundred of its takings last week");
        } else if ("thriving".equals(status)) {
            limit = w.revenue * 3 / 2;
            why = "thriving: a week and a half's takings";
        } else if ("struggling".equals(status)) {
            limit = w.revenue / 3;
            why = "struggling: a third of a week's takings";
        } else {
            limit = w.revenue;
            why = "a week's takings, its margin " + Math.round(margin * 100) + " in the hundred";
        }
        if (b.till < Businesses.floatOf(b, day) / 4 && b.account < -Businesses.creditLimit(b, day) / 2) {
            limit /= 2;
            why += "; its till low and its trade credit half used";
        }
        CompoundTag s = Accounts.sub(club(village), "sched").getCompound(b.id);
        if (s.getInt("behind") > 0) return new Judgement(0, "it is behind with its payments");
        return new Judgement(Math.max(0, limit - b.loan), why + (b.loan > 0 ? "; it owes " + Businesses.pence(b.loan) + " already" : ""));
    }

    /** The lender's book of decisions, the last dozen: the firm, what it asked, what it lent, why, what for. */
    static void decided(UUID village, long day, String firm, long asked, long lent, String why, String what) {
        ListTag l = CapitalBook.list(club(village), "decisions");
        CompoundTag d = new CompoundTag();
        d.putLong("day", day);
        d.putString("firm", firm);
        d.putLong("asked", asked);
        d.putLong("lent", lent);
        d.putString("why", why);
        d.putString("what", what);
        if (!l.isEmpty()) {
            CompoundTag last = l.getCompound(l.size() - 1);
            if (last.getString("firm").equals(firm) && last.getLong("day") == day && last.getLong("lent") == 0 && lent == 0) {
                last.putLong("asked", last.getLong("asked") + asked);
                return;
            }
        }
        l.add(d);
        while (l.size() > 12) l.remove(0);
    }

    /** The undrawn credit a firm may count on for its wages (LabourMarket.cap), coin a day: a month's credit over the month. */
    public static double wageLine(ServerLevel level, UUID village, Business b) {
        if (!MarketMaker.live(village) || lendable(village) <= 0) return 0;
        long day = Businesses.today(level);
        long room = Math.min(judge(level, village, b, day).room, lendable(village) * 100L);
        return Math.max(0, room) / 28.0 / 100.0;
    }

    /** The loans looked at each morning: one that has not come down in a week is behind; two weeks behind, to the court. */
    static void loans(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag sched = Accounts.sub(club(id), "sched");
        for (Business b : Businesses.all(id)) {
            if (!"bank".equals(b.lender) && !"club".equals(b.lender)) continue;
            CompoundTag s = Accounts.sub(sched, b.id);
            if (b.loan <= 0 || !b.open) {
                sched.remove(b.id);
                continue;
            }
            if (!s.contains("check")) { s.putLong("check", day); s.putLong("was", b.loan); }
            if (day - s.getLong("check") < 7) continue;
            boolean down = b.loan < s.getLong("was");
            s.putInt("behind", down ? 0 : s.getInt("behind") + 1);
            s.putLong("check", day);
            s.putLong("was", b.loan);
            if (!down) b.note(day, "behind with its payments to " + lenderName(id) + " (" + Businesses.pence(b.loan) + " owed)");
            // To the court only when its books do not cover what it owes: a firm worth more than its loan is waited for.
            if (s.getInt("behind") >= BEHIND_WEEKS && Enterprise.isFirm(b) && Enterprise.equity(id, b) < 0) {
                Enterprise.petitionFor(level, id, b, day, "it could not keep up its payments to " + lenderName(id));
            }
        }
    }

    /** Businesses.repay: so many coins of a firm's loan paid back, into the lender's vault (its interest the lender's earnings). */
    static void repaid(UUID village, Business b, int coins) {
        if (coins <= 0) return;
        if ("bank".equals(b.lender) && bank(village)) {
            Bank.firmRepaid(village, coins);
            return;
        }
        CompoundTag c = club(village);
        chestIn(village, coins * 100L);
        // The share of it that is interest (what it owes over what it was lent): the savers' earnings.
        CompoundTag s = Accounts.sub(club(village), "sched").getCompound(b.id);
        long lent = Math.max(1, s.getLong("was"));
        long interest = b.loan > lent ? Math.min(coins * 100L, b.loan - lent) : coins * 100L * b.rateBp / 10000 / 7;
        Accounts.add(c, "earned", interest);
        CapitalBook.dirty();
    }

    /** Enterprise.payClaims: the receiver paid the lender so many coins of a bankrupt's loan. */
    static void claimPaid(UUID village, String lender, int coins) {
        if (coins <= 0) return;
        if ("club".equals(lender) && !bank(village)) {
            chestIn(village, coins * 100L);
        } else if (bank(village)) {
            Bank.firmRepaid(village, coins);
        } else {
            Businesses.toTreasury(village, coins * 100L);
        }
        CapitalBook.dirty();
    }

    /** What the receiver could not pay the lender: a default, written off, and the firm's name in the book. */
    static void writtenOff(UUID village, String firmId, String firmName, long p, long day) {
        if (p <= 0) return;
        CompoundTag d = new CompoundTag();
        d.putLong("day", day);
        d.putLong("p", p);
        d.putString("firm", firmName);
        ListTag l = CapitalBook.list(club(village), "defaults");
        l.add(d);
        while (l.size() > 30) l.remove(0);
        Accounts.sub(club(village), "bad").putBoolean(firmId, true);
        Accounts.add(club(village), "writtenOffAll", p);
        CapitalBook.tell(village, day, lenderName(village) + " wrote off " + Businesses.pence(p) + " that " + firmName + " could not pay: its rate goes up to "
            + TownBonds.pct(rateBp(village)) + " a week");
        CapitalBook.dirty();
    }

    /** A bankrupt's claims the receiver could not pay: the lender's written off (Enterprise.bankrupt, stallSold, stallUnsold). */
    static void writeOffClaims(UUID village, Business b, ListTag claims, long day) {
        long off = 0;
        for (Tag t : claims) {
            CompoundTag c = (CompoundTag) t;
            if ("bank".equals(c.getString("kind"))) off += c.getLong("amt");
        }
        writtenOff(village, b.id, b.name, off, day);
    }

    // ------------------------------------------------------------------ players

    static CompoundTag playerLoans(UUID village) {
        return Accounts.sub(club(village), "players");
    }

    /** A player's security with the town, pennies: its savings, its bonds and its shares at their last price. */
    static long security(ServerLevel level, UUID village, Player p) {
        return balance(village, p.getUUID()) * 100L + TownBonds.heldBy(village, p.getUUID()) + Bourse.worthOf(level, village, p.getUUID());
    }

    public static String playerDeposits(ServerLevel level, Player p, UUID village, int n) {
        if (bank(village)) return Bank.playerForTests(level, village, p, "deposit " + n);
        if (!MarketMaker.live(village)) return "The savings club opens with the town's market.";
        n = Math.min(n, Market.coinsHeld(p));
        if (n <= 0) return "You've no coin on you to put by.";
        Market.payOut(p, n);
        CoinCensus.playerIn(village, n);
        chestIn(village, n * 100L);
        credit(village, p.getUUID(), p.getName().getString(), n);
        return "Into the savings club: " + n + Homes.coins(n) + ". You have " + balance(village, p.getUUID()) + Homes.coins(balance(village, p.getUUID()))
            + " with it, earning " + TownBonds.pct(depositBp(village)) + " a week as its loans earn.";
    }

    public static String playerWithdraws(ServerLevel level, Player p, UUID village, int n) {
        if (bank(village)) return Bank.playerForTests(level, village, p, "withdraw " + n);
        CompoundTag a = accounts(village), c = club(village);
        String k = p.getUUID().toString();
        int have = a.getInt(k);
        long owed = playerLoans(village).getCompound(k).getLong("owed");
        int free = (int) Math.max(0, have - (owed + 99) / 100);
        n = Math.min(n <= 0 ? free : n, free);
        if (n <= 0) return have > 0 ? "What you have with the club stands security for your loan." : "You've nothing with the savings club.";
        n = (int) Math.min(n, c.getLong("cash") / 100);
        if (n <= 0) return "The club's chest is all lent out just now. Come back when its loans come in.";
        a.putInt(k, have - n);
        if (a.getInt(k) <= 0) a.remove(k);
        chestOut(village, n * 100L);
        Dealings.giveCoins(p, n);
        CoinCensus.playerOut(village, n);
        CapitalBook.dirty();
        return "Out of the savings club: " + n + Homes.coins(n) + ".";
    }

    public static String playerBorrows(ServerLevel level, Player p, UUID village, int n) {
        if (!MarketMaker.live(village)) return "Nobody lends here till the town's market opens.";
        String k = p.getUUID().toString();
        CompoundTag l = Accounts.sub(playerLoans(village), k);
        long sec = security(level, village, p) / 2 - l.getLong("owed");
        int can = (int) Math.min(Math.max(0, sec / 100), lendable(village));
        if (can <= 0) return "Half of what you have with the town (your savings, bonds and shares: " + Businesses.pence(security(level, village, p))
            + ") is all " + lenderName(village) + " will lend against" + (l.getLong("owed") > 0 ? ", and you owe " + Businesses.pence(l.getLong("owed")) + " already" : "") + ".";
        n = Math.min(n, can);
        if (bank(village)) n = Bank.takeForCapital(village, n);
        else n = (int) (chestOut(village, n * 100L) / 100);
        if (n <= 0) return "The vault has nothing to lend just now.";
        long day = level.getDayTime() / 24000L;
        if (l.getLong("owed") <= 0) l.putLong("day", day);
        l.putLong("owed", l.getLong("owed") + n * 100L);
        l.putInt("bp", rateBp(village));
        l.putString("name", p.getName().getString());
        Dealings.giveCoins(p, n);
        CoinCensus.playerOut(village, n);
        CapitalBook.dirty();
        return lenderName(village).substring(0, 1).toUpperCase(Locale.ROOT) + lenderName(village).substring(1) + " lent you " + n + Homes.coins(n) + " at "
            + TownBonds.pct(l.getInt("bp")) + " a week, against what you hold with the town. Repay with /village capital repay.";
    }

    public static String playerRepays(ServerLevel level, Player p, UUID village, int n) {
        String k = p.getUUID().toString();
        CompoundTag l = playerLoans(village).getCompound(k);
        long owed = l.getLong("owed");
        if (owed <= 0) return "You owe " + lenderName(village) + " nothing.";
        n = (int) Math.min(Math.min(n <= 0 ? (owed + 99) / 100 : n, (owed + 99) / 100), Market.coinsHeld(p));
        if (n <= 0) return "You've no coin on you to repay with.";
        Market.payOut(p, n);
        CoinCensus.playerIn(village, n);
        long p100 = n * 100L;
        long interestPart = Math.min(p100, l.getLong("interest"));
        if (bank(village)) Bank.giveFromCapital(village, n, interestPart > 0);
        else {
            chestIn(village, n * 100L);
            Accounts.add(club(village), "earned", interestPart);
        }
        l.putLong("interest", l.getLong("interest") - interestPart);
        long left = owed - p100;
        if (left <= 0) playerLoans(village).remove(k);                        // (a few pennies over: the lender's, for its trouble)
        else l.putLong("owed", left);
        CapitalBook.dirty();
        return left <= 0 ? "Your loan is paid off." : "Repaid " + n + Homes.coins(n) + "; you owe " + Businesses.pence(left) + ".";
    }

    /** The players' loans' week (FolkCredit.morning via loans): interest run up a day at a time; four weeks of it unpaid
     *  and the lender takes its security. */
    static void playerInterest(ServerLevel level, UUID village, long day) {
        CompoundTag all = playerLoans(village);
        for (String k : new ArrayList<>(all.getAllKeys())) {
            CompoundTag l = all.getCompound(k);
            long owed = l.getLong("owed");
            if (owed <= 0) { all.remove(k); continue; }
            long i = Math.max(1, owed * l.getInt("bp") / 10000 / 7);
            l.putLong("owed", owed + i);
            l.putLong("interest", l.getLong("interest") + i);
            if (l.getLong("interest") > owed / 3 && l.getLong("interest") > 400) {
                // Behind: what it has at the club taken against what it owes.
                CompoundTag a = accounts(village);
                int have = a.getInt(k);
                int take = (int) Math.min(have, (l.getLong("owed") + 99) / 100);
                if (take > 0) {
                    a.putInt(k, have - take);
                    if (a.getInt(k) <= 0) a.remove(k);
                    l.putLong("owed", Math.max(0, l.getLong("owed") - take * 100L));
                    l.putLong("interest", 0);
                    CapitalBook.tell(village, day, lenderName(village) + " took " + take + Homes.coins(take) + " of " + l.getString("name")
                        + "'s savings against the loan it had not paid");
                }
            }
        }
    }

    public static String playerLine(Player p, UUID village) {
        CompoundTag l = playerLoans(village).getCompound(p.getUUID().toString());
        return "With " + lenderName(village) + ": " + balance(village, p.getUUID()) + Homes.coins(balance(village, p.getUUID())) + " saved"
            + (l.getLong("owed") > 0 ? ", " + Businesses.pence(l.getLong("owed")) + " owed at " + TownBonds.pct(l.getInt("bp")) + " a week" : "") + ".";
    }

    // ------------------------------------------------------------------ words

    /** The folk card's line, or "". */
    public static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || bank(id)) return "";
        int b = accounts(id).getInt(f.getUUID().toString());
        return b <= 0 ? "" : "Has " + b + Homes.coins(b) + " at the savings club";
    }

    public static String line(ServerLevel level, UUID village) {
        return lenderName(village) + ": deposits " + Businesses.pence(deposits(village)) + ", vault " + vault(village) + "c (may lend " + lendable(village)
            + "c), firms owe " + Businesses.pence(loansOut(level, village)) + " at " + TownBonds.pct(rateBp(village)) + " a week, savers paid "
            + TownBonds.pct(depositBp(village)) + "; written off " + Businesses.pence(defaulted(village)) + " in four weeks";
    }

    static CompoundTag report(ServerLevel level, UUID village) {
        CompoundTag t = new CompoundTag();
        t.putString("lender", lenderName(village));
        t.putLong("deposits", deposits(village));
        t.putLong("vault", vault(village) * 100L);
        t.putLong("lendable", lendable(village) * 100L);
        t.putLong("loans", loansOut(level, village));
        t.putInt("bp", rateBp(village));
        t.putInt("depositBp", depositBp(village));
        t.putLong("defaulted", defaulted(village));
        t.putLong("writtenOffAll", club(village).getLong("writtenOffAll"));
        t.put("lastWeek", club(village).getCompound("lastWeek").copy());
        ListTag loans = new ListTag();
        long day = Businesses.today(level);
        for (Business b : Businesses.all(village)) {
            if (b.loan <= 0 || !"bank".equals(b.lender) && !"club".equals(b.lender)) continue;
            CompoundTag l = new CompoundTag();
            l.putString("firm", b.name);
            l.putLong("owed", b.loan);
            l.putInt("bp", b.rateBp);
            l.putInt("behind", Accounts.sub(club(village), "sched").getCompound(b.id).getInt("behind"));
            loans.add(l);
        }
        t.put("loansList", loans);
        t.put("decisions", CapitalBook.list(club(village), "decisions").copy());
        t.put("defaults", CapitalBook.list(club(village), "defaults").copy());
        int savers = 0;
        for (String k : accounts(village).getAllKeys()) if (accounts(village).getInt(k) > 0) savers++;
        t.putInt("savers", bank(village) ? -1 : savers);
        return t;
    }

    // ------------------------------------------------------------------ tests

    public static long lendForTests(ServerLevel level, UUID village, Business b, long need) {
        return lend(level, village, b, need);
    }

    public static long judgeForTests(ServerLevel level, UUID village, Business b) {
        return judge(level, village, b, Businesses.today(level)).room;
    }

    public static void depositForTests(UUID village, VillageFolkEntity f, int coins) {
        if (coins <= 0 || !f.spend(coins)) return;
        chestIn(village, coins * 100L);
        credit(village, f.getUUID(), f.displayNameCap(), coins);
    }

    public static void morningForTests(ServerLevel level, Villages.Village v, long day) {
        morning(level, v, day);
    }

    /** Tests: so many coins of a firm's loan paid back out of its till, as Businesses.repay does. */
    public static void repaidForTests(UUID village, Business b, int coins) {
        b.till -= coins * 100L;
        repaid(village, b, coins);
        b.loan -= coins * 100L;
        if (b.loan <= 0) { b.loan = 0; b.lender = ""; }
    }

    public static long earnedForTests(UUID village) {
        return club(village).getLong("earned");
    }

    public static void weekForTests(ServerLevel level, Villages.Village v, long day) {
        week(level, v, day);
    }

    public static void loansForTests(ServerLevel level, Villages.Village v, long day) {
        loans(level, v, day);
    }

    static void resetForTests() {
        purpose = "its orders";
    }
}
