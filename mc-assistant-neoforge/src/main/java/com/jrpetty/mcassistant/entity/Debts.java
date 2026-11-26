package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [wpc] Personal debt, for players and folk alike: one ledger a person, a credit score, and the arrears ladder as it runs in
 * life.
 * <ul>
 * <li><b>The ledger.</b> Every debt a person owes here, each with its creditor, what is owed, its interest, when it is due
 *     and where it stands on the ladder: rates and taxes unpaid, rent behind, fines, a shop's tab, an IOU, a bank loan, a
 *     consolidation loan; the bank's mortgage and the savings club's loan are shown beside them from their own books.</li>
 * <li><b>The ladder.</b> A day overdue, a reminder. Three days, a late fee (a twentieth, a coin at least). Six, a letter of
 *     demand (an item in the pack, and the planner) with four days to pay. Ten, the creditor acts: a repayment plan if one
 *     is agreed (asked for from the Debts page: so much a week, the ladder held while it is kept), else a third of each
 *     day's wages taken (an attachment), else a bailiff who walks to the home and takes non-essentials to the value (never
 *     a bed, never a tool), else the court and bankruptcy (Insolvency). While the player is away the ladder runs at half
 *     pace (StandingOrders).</li>
 * <li><b>The credit score</b> (0 to 100, sixty to start): up for a debt paid on time, down a step for each rung of the
 *     ladder, down hard at bankruptcy, and read with income and what is already owed. It sets what the bank lends and at
 *     what rate (Mortgages), and the finance page says why it is what it is. Folk keep one too: the thrifty well, the
 *     spendthrift less so.</li>
 * </ul>
 * Kept with the town's books (PropertyBook "debts": a list a debtor, and its score and history).
 */
public final class Debts implements PlayerLife.Page {

    /** Days overdue for each rung: a reminder, the late fee, the letter of demand, the creditor's action. */
    static final int REMIND = 1, LATE = 3, DEMAND = 6, ACT = 10;
    /** The late fee, in the hundred (a coin at least); a plan's weeks; an attachment's share of a day's wages. */
    static final int LATE_PCT = 5, PLAN_WEEKS = 6, ATTACH_PART = 3;
    /** The score a person starts with, and its bounds. */
    static final int START_SCORE = 60;

    /** The rungs, in the words the page uses. */
    static final String[] STAGES = { "current", "reminder sent", "late fee added", "letter of demand", "creditor acting", "with the bailiff", "in court" };

    /** A debt: its id, what it is, to whom, what is owed, a week's interest (hundredths of a percent), due, rung, plan. */
    public record Debt(String id, String kind, String creditor, String creditorKind, int owed, int original, int rateBp, long since, long due,
                       int stage, int plan, boolean attached, String ref) {
        public boolean late(long day) {
            return day > due && plan <= 0;
        }
    }

    // ------------------------------------------------------------------ the books

    static CompoundTag book(UUID village) {
        return PropertyBook.town(village, "debts");
    }

    static ListTag list(UUID village, UUID debtor) {
        CompoundTag b = Accounts.sub(book(village), debtor.toString());
        if (!b.contains("l", Tag.TAG_LIST)) b.put("l", new ListTag());
        return b.getList("l", Tag.TAG_COMPOUND);
    }

    static CompoundTag person(UUID village, UUID debtor) {
        return Accounts.sub(book(village), debtor.toString());
    }

    static Debt view(CompoundTag t) {
        return new Debt(t.getString("id"), t.getString("kind"), t.getString("cred"), t.getString("ck"), t.getInt("owed"), t.getInt("orig"),
            t.getInt("bp"), t.getLong("since"), t.getLong("due"), t.getInt("stage"), t.getInt("plan"), t.getBoolean("att"), t.getString("ref"));
    }

    /** Everything this person owes here on the ledger (not the bank's mortgage: that is on its own books). */
    public static List<Debt> of(UUID village, UUID debtor) {
        List<Debt> out = new ArrayList<>();
        for (Tag e : list(village, debtor)) out.add(view((CompoundTag) e));
        return out;
    }

    @Nullable
    static CompoundTag raw(UUID village, UUID debtor, String id) {
        for (Tag e : list(village, debtor)) if (((CompoundTag) e).getString("id").equals(id)) return (CompoundTag) e;
        return null;
    }

    /** The total owed on the ledger, and with the bank's mortgage and the club's loan. */
    public static int total(UUID village, UUID debtor) {
        int n = 0;
        for (Debt d : of(village, debtor)) n += d.owed();
        return n;
    }

    public static int totalAll(UUID village, UUID debtor) {
        int n = total(village, debtor);
        Bank.Loan l = Bank.playerLoan(village, debtor);
        if (l != null) n += l.owed();
        n += (int) ((FolkCredit.playerLoans(village).getCompound(debtor.toString()).getLong("owed") + 99) / 100);
        return n;
    }

    /**
     * A debt owed: added to the ledger (or to the one already there for the same thing, {@code ref}), due in {@code days}.
     * Creditor kinds: "town" (the treasury), "bank", "player" or "folk" (its id in {@code credId}).
     */
    public static String owe(ServerLevel level, UUID village, UUID debtor, String debtorName, String kind, String creditor, String credKind,
                             @Nullable UUID credId, int amount, int rateBp, int days, String ref) {
        if (amount <= 0) return "";
        long day = PropertyBook.day(level);
        ListTag l = list(village, debtor);
        person(village, debtor).putString("name", debtorName);
        for (Tag e : l) {
            CompoundTag t = (CompoundTag) e;
            if (!ref.isEmpty() && ref.equals(t.getString("ref"))) {
                t.putInt("owed", t.getInt("owed") + amount);
                t.putInt("orig", t.getInt("orig") + amount);
                PropertyBook.dirty();
                return t.getString("id");
            }
        }
        CompoundTag t = new CompoundTag();
        String id = "D" + day + "_" + (l.size() + person(village, debtor).getInt("n"));
        person(village, debtor).putInt("n", person(village, debtor).getInt("n") + 1);
        t.putString("id", id);
        t.putString("kind", kind);
        t.putString("cred", creditor);
        t.putString("ck", credKind);
        if (credId != null) t.putUUID("cid", credId);
        t.putInt("owed", amount);
        t.putInt("orig", amount);
        t.putInt("bp", rateBp);
        t.putLong("since", day);
        t.putLong("due", day + Math.max(0, days));
        t.putString("ref", ref);
        l.add(t);
        PropertyBook.dirty();
        ServerPlayer p = PropertyBook.online(level, debtor);
        if (p != null) Planner.add(p, (day + Math.max(0, days)) * 24000L + 6000, "bill", capital(kind) + " owed to " + creditor + ": " + PropertyBook.coins(amount), null);
        return id;
    }

    /** So much paid off a debt (to its creditor); the debt gone when nothing is left. Returns what was owed after. */
    static int settle(ServerLevel level, UUID village, UUID debtor, CompoundTag t, int paid) {
        if (paid <= 0) return t.getInt("owed");
        int left = Math.max(0, t.getInt("owed") - paid);
        t.putInt("owed", left);
        credit(level, village, t, paid);
        long day = PropertyBook.day(level);
        if (left <= 0) {
            boolean onTime = t.getInt("stage") == 0;
            history(village, debtor, day, onTime ? 3 : 1, onTime ? "paid " + t.getString("kind") + " on time" : "cleared " + t.getString("kind") + " in arrears");
            ListTag l = list(village, debtor);
            for (int i = 0; i < l.size(); i++) if (l.getCompound(i).getString("id").equals(t.getString("id"))) { l.remove(i); break; }
        } else if (t.getInt("stage") > 0 && t.getInt("plan") <= 0 && paid > 0) {
            t.putLong("due", Math.max(t.getLong("due"), day + 2));             // paying something buys a couple of days
        }
        PropertyBook.dirty();
        return left;
    }

    /** The creditor gets what was paid (the treasury had it already from PropertyBook.pay: a bank or a person's share moves on). */
    static void credit(ServerLevel level, UUID village, CompoundTag t, int paid) {
        switch (t.getString("ck")) {
            case "bank" -> {
                if (Ledger.takeCoins(village, paid) > 0) Bank.state(village).cash += paid;
            }
            case "player" -> {
                if (t.hasUUID("cid") && Ledger.takeCoins(village, paid) > 0) PropertyBook.give(level, village, t.getUUID("cid"), t.getString("cred"), paid, PlayerFinance.Cat.OTHER_IN, "a debt repaid to you");
            }
            case "folk" -> {
                if (t.hasUUID("cid")) {
                    VillageFolkEntity f = Homes.loaded(village, t.getUUID("cid"));
                    if (f != null && Ledger.takeCoins(village, paid) > 0) f.earn(paid);
                }
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ the credit score

    /** The score and its reasons: history (paid on time, rungs, bankruptcy) read with income and what is owed. */
    public record Score(int score, List<String> reasons) {
        public String band() {
            return score >= 80 ? "excellent" : score >= 65 ? "good" : score >= 50 ? "fair" : score >= 35 ? "poor" : "very poor";
        }
    }

    static void history(UUID village, UUID who, long day, int delta, String why) {
        CompoundTag p = person(village, who);
        p.putInt("hist", Math.max(-60, Math.min(35, p.getInt("hist") + delta)));
        ListTag h = p.getList("why", Tag.TAG_STRING);
        h.add(0, StringTag.valueOf("day " + day + ": " + why + " (" + (delta > 0 ? "+" : "") + delta + ")"));
        while (h.size() > 6) h.remove(h.size() - 1);
        p.put("why", h);
        PropertyBook.dirty();
    }

    public static Score score(ServerLevel level, UUID village, UUID who) {
        List<String> why = new ArrayList<>();
        CompoundTag p = person(village, who);
        int s = START_SCORE + p.getInt("hist");
        long day = PropertyBook.day(level);
        Map7 m = income(village, who, day);
        int owed = totalAll(village, who);
        if (m.week > 0) {
            int bonus = Math.min(10, m.week / 10);
            s += bonus;
            if (bonus > 0) why.add("+" + bonus + ": you earn about " + m.week + " a week");
        } else why.add("no earnings in the last week");
        if (owed > 0) {
            int weeks = m.week <= 0 ? 99 : owed / Math.max(1, m.week);
            int pen = weeks > 12 ? 15 : weeks > 6 ? 8 : weeks > 2 ? 3 : 0;
            s -= pen;
            if (pen > 0) why.add("-" + pen + ": you owe " + owed + ", about " + (weeks >= 99 ? "many" : weeks) + " weeks of earnings");
        }
        int late = 0;
        for (Debt d : of(village, who)) if (d.stage() > 0) late++;
        if (late > 0) { s -= 4 * late; why.add("-" + 4 * late + ": " + late + (late == 1 ? " debt" : " debts") + " in arrears"); }
        if (Insolvency.bankrupt(village, who)) { s = Math.min(s, 15); why.add("bankrupt till your discharge"); }
        else if (Insolvency.recordUntil(village, who) > day) { s = Math.min(s, 40); why.add("a bankruptcy on your record till day " + Insolvency.recordUntil(village, who)); }
        for (Tag t : p.getList("why", Tag.TAG_STRING)) why.add(t.getAsString());
        return new Score(Math.max(0, Math.min(100, s)), why);
    }

    record Map7(int week) {}

    /** What the person earned here in the last week (wages, shifts, sales, rent), coins. */
    static Map7 income(UUID village, UUID who, long day) {
        try {
            java.util.Map<PlayerFinance.Cat, Long> m = PlayerFinance.spanTown(who, village.toString(), day, 7);
            long pence = 0;
            for (var e : m.entrySet()) if (e.getKey().kind == PlayerFinance.Kind.INCOME) pence += Math.max(0, e.getValue());
            return new Map7((int) (pence / 100));
        } catch (RuntimeException e) {
            return new Map7(0);
        }
    }

    /** A folk's score: its thrift, and what it owes. */
    public static int folkScore(VillageFolkEntity f) {
        UUID id = f.ownerId();
        int s = START_SCORE + (id == null ? 0 : person(id, f.getUUID()).getInt("hist"));
        try {
            s += Bank.thrift(f) * 4;                                          // the thrifty keep good scores, the spendthrift run up tabs
        } catch (RuntimeException e) {
            // no persona yet
        }
        if (id != null) s -= Math.min(30, total(id, f.getUUID()) / 5);
        return Math.max(0, Math.min(100, s));
    }

    // ------------------------------------------------------------------ the ladder

    /** Each morning (StandingOrders runs it; half pace for a player who is away): every debt in the town a rung on if it is due. */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag b = book(id);
        for (String who : new ArrayList<>(b.getAllKeys())) {
            UUID debtor;
            try { debtor = UUID.fromString(who); } catch (IllegalArgumentException e) { continue; }
            if (Insolvency.bankrupt(id, debtor)) continue;
            ListTag l = list(id, debtor);
            boolean away = StandingOrders.away(level, debtor);
            for (int i = 0; i < l.size(); i++) {
                CompoundTag t = l.getCompound(i);
                interest(t);
                if (t.getInt("plan") > 0) { planDay(level, id, debtor, t, day); continue; }
                long over = day - t.getLong("due");
                if (away) over = over / 2;                                   // offline: the ladder at half pace
                int stage = t.getInt("stage");
                int want = over >= ACT ? 4 : over >= DEMAND ? 3 : over >= LATE ? 2 : over >= REMIND ? 1 : 0;
                if (want > stage) climb(level, v, debtor, t, Math.min(want, stage + 1), day);
                else if (stage >= 4) act(level, v, debtor, t, day);
            }
        }
    }

    /** A week's interest on a debt that carries it, run up a seventh a day (whole coins only). */
    static void interest(CompoundTag t) {
        int bp = t.getInt("bp");
        if (bp <= 0 || t.getInt("owed") <= 0) return;
        long x = (long) t.getInt("owed") * bp / 7 + t.getInt("carry");
        t.putInt("owed", t.getInt("owed") + (int) (x / 10000));
        t.putInt("carry", (int) (x % 10000));
    }

    /** A rung up the ladder: what it means, said to the debtor. */
    static void climb(ServerLevel level, Villages.Village v, UUID debtor, CompoundTag t, int stage, long day) {
        UUID id = v.id();
        t.putInt("stage", stage);
        String what = t.getString("kind") + " owed to " + t.getString("cred");
        int owed = t.getInt("owed");
        switch (stage) {
            case 1 -> PropertyBook.tell(level, debtor, "A reminder: " + what + " (" + PropertyBook.coins(owed) + ") was due on day " + t.getLong("due") + ". Pay it on the Debts page.");
            case 2 -> {
                int fee = Math.max(1, owed * LATE_PCT / 100);
                t.putInt("owed", owed + fee);
                history(id, debtor, day, -3, what + " three days late");
                PropertyBook.tell(level, debtor, "A late fee of " + PropertyBook.coins(fee) + " added to " + what + ": " + PropertyBook.coins(owed + fee) + " now.");
            }
            case 3 -> {
                history(id, debtor, day, -5, "a letter of demand for " + what);
                t.putLong("deadline", day + 4);
                ServerPlayer p = PropertyBook.online(level, debtor);
                if (p != null) {
                    letter(p, v, t, day + 4);
                    Planner.add(p, (day + 4) * 24000L + 6000, "arrears", "Deadline on the letter of demand: " + PropertyBook.coins(t.getInt("owed")) + " to " + t.getString("cred"), null);
                }
                VillageFolkEntity f = Homes.loaded(id, debtor);
                if (f != null) f.persona().remember(day, "a letter of demand came about the " + t.getString("kind"), 4);
            }
            case 4 -> {
                history(id, debtor, day, -6, what + " passed to the creditor's remedies");
                PropertyBook.tell(level, debtor, "The deadline passed: " + t.getString("cred") + " will take " + what + " from your wages, or send the bailiff. "
                    + "Ask for a plan on the Debts page to stop it.");
                act(level, v, debtor, t, day);
            }
            default -> { }
        }
        PropertyBook.dirty();
    }

    /** The letter of demand, as an item: who, what, by when. */
    static void letter(ServerPlayer p, Villages.Village v, CompoundTag t, long deadline) {
        ItemStack s = new ItemStack(Items.PAPER);
        s.set(DataComponents.CUSTOM_NAME, Component.literal("Letter of demand: " + PropertyBook.coins(t.getInt("owed")) + " to " + t.getString("cred")));
        s.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
            Component.literal("For " + t.getString("kind") + ", due day " + t.getLong("due")),
            Component.literal("Pay by day " + deadline + ", or ask for a plan"),
            Component.literal("The Debts page: Your life in town, Finances"))));
        if (!p.getInventory().add(s)) p.drop(s, false);
    }

    /** The creditor's remedy, once a day: wages first, then the bailiff, then the court. */
    static void act(ServerLevel level, Villages.Village v, UUID debtor, CompoundTag t, long day) {
        UUID id = v.id();
        int owed = t.getInt("owed");
        if (owed <= 0) return;
        int wages = wagesYesterday(id, debtor, day);
        if (wages > 0) {
            int take = Math.max(1, wages / ATTACH_PART);
            int got = takeFrom(level, id, debtor, Math.min(take, owed), "a third of your wages, attached for " + t.getString("kind"));
            t.putBoolean("att", true);
            if (got > 0) settle(level, id, debtor, t, got);
            return;
        }
        if (t.getInt("stage") < 5) {
            t.putInt("stage", 5);
            Bailiff.send(level, v, debtor, t.getString("id"));
            return;
        }
        if (t.getInt("stage") == 5 && !Bailiff.busy(debtor) && day - t.getLong("bailiffDay") >= 2) {
            t.putInt("stage", 6);
            t.putLong("court", day + 1);
            ServerPlayer p = PropertyBook.online(level, debtor);
            if (p != null) Planner.add(p, (day + 1) * 24000L + 9000, "court", "A debt hearing in the county court: " + t.getString("cred") + " asks for your bankruptcy", null);
            PropertyBook.tell(level, debtor, t.getString("cred") + " has taken " + t.getString("kind") + " to court. The hearing is tomorrow: pay or agree a plan before then.");
            return;
        }
        if (t.getInt("stage") == 6 && day >= t.getLong("court")) Insolvency.forced(level, v, debtor, t.getString("cred"));
    }

    /** Yesterday's wages (players from their books; a folk none: its purse is taken by the bailiff). */
    static int wagesYesterday(UUID village, UUID who, long day) {
        try {
            java.util.Map<PlayerFinance.Cat, Long> m = PlayerFinance.spanTown(who, village.toString(), day, 1);
            long p = m.getOrDefault(PlayerFinance.Cat.WAGES, 0L) + m.getOrDefault(PlayerFinance.Cat.SHIFTS, 0L);
            return (int) Math.max(0, p / 100);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Coin taken toward a debt: the account, then the pack if the player is here, or a folk's purse. */
    static int takeFrom(ServerLevel level, UUID village, UUID who, int n, String why) {
        if (n <= 0) return 0;
        int got = PropertyBook.payOffline(village, who, n, PlayerFinance.Cat.LOAN_PAY, why);
        ServerPlayer p = PropertyBook.online(level, who);
        if (got < n && p != null) {
            int k = Math.min(n - got, Market.coinsHeld(p));
            if (k > 0) {
                Market.payOut(p, k);
                CoinCensus.playerIn(village, k, p, PlayerFinance.Cat.LOAN_PAY, why);
                Ledger.addCoins(village, k);
                got += k;
            }
        }
        if (got < n && p == null) {
            VillageFolkEntity f = Homes.loaded(village, who);
            if (f != null) {
                int k = Math.min(n - got, f.purse());
                if (k > 0 && f.spend(k)) { Ledger.addCoins(village, k); got += k; }
            }
        }
        return got;
    }

    /** A plan's day: on its weekday, the week's payment by standing order; missed, the plan is off and the letter stands. */
    static void planDay(ServerLevel level, UUID village, UUID debtor, CompoundTag t, long day) {
        if ((day - t.getLong("planFrom")) % 7 != 0 || day == t.getLong("planFrom")) return;
        int want = Math.min(t.getInt("plan"), t.getInt("owed"));
        int got = takeFrom(level, village, debtor, want, "a repayment plan's week for " + t.getString("kind"));
        if (got >= want) {
            history(village, debtor, day, 1, "a plan payment kept");
            settle(level, village, debtor, t, got);
        } else {
            if (got > 0) settle(level, village, debtor, t, got);
            t.putInt("plan", 0);
            t.putInt("stage", 3);
            t.putLong("due", day - DEMAND);
            history(village, debtor, day, -6, "a plan payment missed");
            PropertyBook.tell(level, debtor, "You missed a payment on your plan for " + t.getString("kind") + ". The plan is off; the letter of demand stands.");
        }
    }

    // ------------------------------------------------------------------ what the debtor does

    /** Pay a debt off from the pack and account (as much as there is, or so much). */
    static String pay(ServerLevel level, ServerPlayer p, UUID village, String id, int amount) {
        CompoundTag t = raw(village, p.getUUID(), id);
        if (t == null) return "!That debt isn't on your ledger.";
        int want = amount <= 0 ? t.getInt("owed") : Math.min(amount, t.getInt("owed"));
        int got = PropertyBook.pay(p, village, want, PlayerFinance.Cat.LOAN_PAY, t.getString("kind") + " to " + t.getString("cred"), false);
        if (got <= 0) return "!You've no coin on you or in your account to pay it with.";
        int left = settle(level, village, p.getUUID(), t, got);
        return left <= 0 ? "Paid off: " + PropertyBook.coins(got) + " to " + t.getString("cred") + ". It's off your ledger, and your score is better for it."
            : "Paid " + PropertyBook.coins(got) + " to " + t.getString("cred") + "; " + PropertyBook.coins(left) + " still owed. Paying something holds the ladder a couple of days.";
    }

    /** A repayment plan: so much a week over six weeks, the ladder held while it is kept. */
    static String plan(ServerLevel level, ServerPlayer p, UUID village, String id) {
        CompoundTag t = raw(village, p.getUUID(), id);
        if (t == null) return "!That debt isn't on your ledger.";
        if (t.getInt("plan") > 0) return "!There's a plan on it already: " + PropertyBook.coins(t.getInt("plan")) + " a week.";
        if (t.getInt("stage") == 0) return "!It isn't late: there's nothing to plan yet. Pay it by day " + t.getLong("due") + ".";
        int weekly = Math.max(1, (t.getInt("owed") + PLAN_WEEKS - 1) / PLAN_WEEKS);
        long day = PropertyBook.day(level);
        t.putInt("plan", weekly);
        t.putLong("planFrom", day);
        if (t.getInt("stage") >= 5) Bailiff.recall(level, p.getUUID());
        t.putInt("stage", Math.min(t.getInt("stage"), 3));
        PropertyBook.dirty();
        Planner.add(p, (day + 7) * 24000L + 6000, "bill", "Plan payment: " + PropertyBook.coins(weekly) + " for " + t.getString("kind"), null);
        return t.getString("cred") + " agreed a plan: " + PropertyBook.coins(weekly) + " a week for " + PLAN_WEEKS + " weeks, taken from your account each seventh day. "
            + "Keep to it and nothing more happens; miss one and the letter of demand stands again.";
    }

    /** One loan from the bank for all of it, at its rate for the score (a fair score at least, and the bank open). */
    static String consolidate(ServerLevel level, ServerPlayer p, UUID village, boolean sure, PlayerLife.Ctx c) {
        if (!Bank.open(village)) return "!There's no bank in town yet to consolidate with.";
        List<Debt> all = of(village, p.getUUID());
        int sum = 0, n = 0;
        for (Debt d : all) if (!d.kind().equals("loan")) { sum += d.owed(); n++; }
        if (n < 2) return "!Consolidating is for two debts or more: pay this one, or ask for a plan.";
        Score s = score(level, village, p.getUUID());
        if (s.score() < 45) return "!The bank won't consolidate at a score of " + s.score() + " (" + s.band() + "): it wants 45. Ask each creditor for a plan instead.";
        if (Insolvency.bankrupt(village, p.getUUID())) return "!Not while you're bankrupt.";
        Bank.State st = Bank.state(village);
        if (Bank.lendable(st) < sum) return "!The bank hasn't " + PropertyBook.coins(sum) + " to lend just now.";
        int bp = Mortgages.rateBp(village, s.score(), 0);
        if (!sure) return PropertyBook.ask(c, "consolidate", new CompoundTag(), "Pay off your " + n + " debts (" + PropertyBook.coins(sum) + ") with one bank loan at "
            + TownBonds.pct(bp) + " a week, repaid over " + PLAN_WEEKS + " weeks (about " + PropertyBook.coins(Math.max(1, sum / PLAN_WEEKS)) + " a week)? The late fees stop, and so does the ladder.", "Consolidate");
        st.cash -= sum;
        long day = PropertyBook.day(level);
        ListTag l = list(village, p.getUUID());
        for (int i = l.size() - 1; i >= 0; i--) {
            CompoundTag t = l.getCompound(i);
            if (t.getString("kind").equals("loan")) continue;
            Ledger.addCoins(village, t.getInt("owed"));
            credit(level, village, t, t.getInt("owed"));
            l.remove(i);
        }
        String lid = owe(level, village, p.getUUID(), Belonging.called(p), "loan", "the bank", "bank", null, sum, bp, 7, "consolidation");
        CompoundTag t = raw(village, p.getUUID(), lid);
        if (t != null) { t.putInt("plan", Math.max(1, (sum + PLAN_WEEKS - 1) / PLAN_WEEKS)); t.putLong("planFrom", day); }
        history(village, p.getUUID(), day, 1, "consolidated " + n + " debts with the bank");
        PropertyBook.dirty();
        return "Consolidated: the bank paid off " + n + " debts (" + PropertyBook.coins(sum) + "). You owe it one loan now, " + PropertyBook.coins(Math.max(1, sum / PLAN_WEEKS))
            + " a week from your account. Next: keep enough in your account each seventh day.";
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the page ("debts")

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) {
            LifePage page = new LifePage("debts", "Debts", "No town near", p.registryAccess());
            page.text("Debts are owed to a town and its folk. Walk into one to see yours.", 4);
            return page;
        }
        UUID id = v.id(), me = p.getUUID();
        long day = PropertyBook.day(level);
        List<Debt> all = of(id, me);
        int total = totalAll(id, me);
        Score s = score(level, id, me);
        LifePage page = new LifePage("debts", "Your debts in " + Villages.name(id), total == 0 ? "You owe nothing here" : "You owe " + PropertyBook.coins(total) + " in all", p.registryAccess());
        if (c.tab.isEmpty()) c.tab = "owed";
        page.tab("owed", "What you owe", c.tab.equals("owed")).tab("credit", "Credit score", c.tab.equals("credit")).tab("bankrupt", "Bankruptcy", c.tab.equals("bankrupt"));
        PropertyBook.confirmBox(page, c);
        switch (c.tab) {
            case "credit" -> creditTab(level, p, v, page, s);
            case "bankrupt" -> Insolvency.tab(level, p, v, c, page);
            default -> owedTab(level, p, v, c, page, all, total, s, day);
        }
        page.buttons().button("Your finances", "finances", null).button("The bank", "bank", null).button("Back to your life in town", "life", null);
        return page;
    }

    void owedTab(ServerLevel level, ServerPlayer p, Villages.Village v, PlayerLife.Ctx c, LifePage page, List<Debt> all, int total, Score s, long day) {
        UUID id = v.id(), me = p.getUUID();
        PropertyBook.hint(p, page, "debts.owed", "Every debt you owe here, in one place. A late debt climbs a ladder: a reminder, a late fee, a letter of demand, "
            + "then your wages, the bailiff or the court. Ask for a plan at any rung to stop it climbing.");
        String status = status(id, me, day);
        page.text(status, status.startsWith("Nothing") || status.startsWith("All") ? 1 : status.contains("bailiff") || status.contains("court") ? 3 : 2);
        if (Insolvency.bankrupt(id, me)) page.text("You are bankrupt till day " + Insolvency.until(id, me) + ": the official receiver holds your affairs. Your old debts are frozen.", 3);
        Bank.Loan l = Bank.playerLoan(id, me);
        if (l != null) {
            page.row(new ItemStack(Items.TRIPWIRE_HOOK), "Mortgage: the bank", Bank.loanLine(id, l), PropertyBook.coins(l.owed()))
                .button("The mortgage", "mortgage", null);
        }
        long club = FolkCredit.playerLoans(id).getCompound(me.toString()).getLong("owed");
        if (club > 0) page.row(new ItemStack(Items.PAPER), "Loan: " + FolkCredit.lenderName(id), "Repaid weekly from your savings", PropertyBook.coins((club + 99) / 100));
        if (all.isEmpty() && l == null && club <= 0) page.text("Nothing owed here. Bills you can't pay at once (rates, rent, fines, a tab) land here, with what to do about each.", 4);
        for (Debt d : all) {
            int colour = d.stage() >= 4 ? 3 : d.stage() >= 1 ? 2 : 0;
            String when = d.plan() > 0 ? "On a plan: " + PropertyBook.coins(d.plan()) + " a week" : d.stage() == 0 ? "Due day " + d.due() : "Was due day " + d.due() + ": " + STAGES[Math.min(STAGES.length - 1, d.stage())];
            page.row(new ItemStack(d.stage() >= 4 ? Items.REDSTONE : d.stage() >= 1 ? Items.YELLOW_DYE : Items.PAPER), capital(d.kind()) + " to " + d.creditor(),
                    when + (d.rateBp() > 0 ? "; interest " + TownBonds.pct(d.rateBp()) + " a week" : "") + (d.attached() ? "; taken from your wages" : ""), PropertyBook.coins(d.owed()))
                .button("Pay", "pay", LifePage.args("id", d.id()), Market.coinsHeld(p) + PropertyBook.account(id, me) > 0, "You've no coin on you or in your account")
                .button("Ask for a plan", "plan", LifePage.args("id", d.id()), d.plan() <= 0 && d.stage() > 0, d.plan() > 0 ? "It's on a plan already" : "It isn't late: just pay it when it's due");
            if (colour == 3 && d.stage() == 5) page.text("  The bailiff is on the way to your home for this one. Paying it, or a plan, calls them off.", 3);
        }
        int unsecured = 0;
        for (Debt d : all) if (!d.kind().equals("loan")) unsecured++;
        page.buttons().button("Consolidate (one bank loan)", "consolidate", null, unsecured >= 2 && Bank.open(id), !Bank.open(id) ? "There's no bank yet" : "For two debts or more")
            .button("Declare bankruptcy", "tab", LifePage.args("tab", "bankrupt"));
    }

    /** "2 payments late. Bailiff visit possible on day 34." and the like. */
    public static String status(UUID village, UUID who, long day) {
        int late = 0, worst = 0;
        long next = Long.MAX_VALUE;
        for (Debt d : of(village, who)) {
            if (d.stage() > 0) late++;
            worst = Math.max(worst, d.stage());
            if (d.stage() >= 1 && d.stage() < 4) next = Math.min(next, d.due() + ACT);
        }
        if (of(village, who).isEmpty()) return "Nothing owed on your ledger.";
        if (late == 0) return "All current: nothing late.";
        String s = late + (late == 1 ? " debt late." : " debts late.");
        if (worst >= 6) return s + " A court hearing is set.";
        if (worst == 5) return s + " The bailiff is on the way.";
        if (worst == 4) return s + " The creditor is acting: your wages, or the bailiff.";
        return s + (next < Long.MAX_VALUE ? " Wages or the bailiff possible from day " + next + "." : "");
    }

    void creditTab(ServerLevel level, ServerPlayer p, Villages.Village v, LifePage page, Score s) {
        PropertyBook.hint(p, page, "debts.credit", "Your credit score is what lenders make of you: paying on time lifts it, arrears and bankruptcy drop it. "
            + "It sets what the bank lends you and at what rate.");
        page.bar("Credit score: " + s.band(), s.score() / 100.0, s.score() + " / 100");
        int bp = Mortgages.rateBp(v.id(), s.score(), 75);
        page.text("At this score the bank lends a house at about " + TownBonds.pct(bp) + " a week (three quarters of its price borrowed).", 4);
        page.heading("Why it is what it is");
        for (String r : s.reasons()) page.text("• " + r, 0);
    }

    @Nullable
    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        if (action.startsWith("bk_")) return Insolvency.act(p, c, action, args, this);
        String common = PropertyBook.common(p, c, action, args, this);
        if (common != null) return common;
        ServerLevel level = p.serverLevel();
        Villages.Village v = PlayerLife.here(p);
        if (v == null) return "!No town near.";
        UUID id = v.id();
        switch (action) {
            case "pay" -> { return pay(level, p, id, args.getString("id"), args.getInt("n")); }
            case "plan" -> { return plan(level, p, id, args.getString("id")); }
            case "consolidate" -> { return consolidate(level, p, id, args.getBoolean("sure"), c); }
            case "finances" -> { return LifeTown.finances(p); }
            case "mortgage" -> { PlayerLife.open(p, "mortgage", null); return "#open:"; }
            case "bank" -> { PlayerLife.open(p, "mortgage", null); return "#open:"; }
            case "life" -> { PlayerLife.open(p, "life", null); return "#open:"; }
            default -> { return null; }
        }
    }

    // ------------------------------------------------------------------ tests

    public static void resetForTests(UUID village) {
        Accounts.sub(Accounts.town(village), "wpc").remove("debts");
    }

    public static int stageForTests(UUID village, UUID who, String id) {
        CompoundTag t = raw(village, who, id);
        return t == null ? -1 : t.getInt("stage");
    }

    public static int owedForTests(UUID village, UUID who, String id) {
        CompoundTag t = raw(village, who, id);
        return t == null ? 0 : t.getInt("owed");
    }

    public static void morningForTests(ServerLevel level, Villages.Village v, long day) {
        morning(level, v, day);
    }

    public static String payForTests(ServerPlayer p, UUID village, String id, int n) {
        return pay(p.serverLevel(), p, village, id, n);
    }

    public static String planForTests(ServerPlayer p, UUID village, String id) {
        return plan(p.serverLevel(), p, village, id);
    }

    public static void dueForTests(UUID village, UUID who, String id, long due) {
        CompoundTag t = raw(village, who, id);
        if (t != null) t.putLong("due", due);
    }

    public static void planFromForTests(UUID village, UUID who, String id, long from) {
        CompoundTag t = raw(village, who, id);
        if (t != null) t.putLong("planFrom", from);
    }
}
