package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bank.
 *
 * <p>A town of thirty in the Iron Age builds a bank on one of the trades' lots facing the square
 * (Villages, blueprints/bank.txt): a stone house of business with a counter across it, a lectern by
 * the door where the bank's ledger lies open for anybody to read, and a vault at the back behind
 * iron bars. Until it stands nothing changes. The day it stands it opens, and it is told in the
 * chronicle; and the most careful, shrewdest hand the village can spare gives up its trade to keep
 * it, at a craftsman's wage (Wealth). The banker makes the vault's bars out of six of the village's
 * spare ingots, writes the ledger up in a book and quill made of what the stores hold, and keeps it
 * written up week by week.
 *
 * <ul>
 * <li><b>Savings.</b> Each morning after the wages, the rent and the tithe, a folk with more in its
 *     purse than its week wants (a dozen coins to live on, its share of the week's rent and of any
 *     payment on its house) puts part of the rest in the bank: a thrifty one three parts in four, a
 *     careful one six in ten, most four in ten, one free with its coin a fifth, a spendthrift none of
 *     it. The coin goes out of its purse into the vault, and onto its account. Short of what the rent
 *     wants, it draws it out again before the rent is taken; a household buying its house draws its
 *     savings out toward the price. A household saving up for its house keeps that by itself (Homes).
 *     The tithe is reckoned on what a folk has at the bank as well as in its purse: the bank pays it
 *     to the treasury out of the account, so a saver at the bank gives what it would have given.</li>
 * <li><b>Loans.</b> The bank lends what is deposited with it, never all of it: three coins in ten of
 *     the deposits stay in the vault for whoever comes to draw. It lends to households buying their
 *     houses. One that wants a house of its own and has a fifth of the price put by (a Nest Egg
 *     counts: it goes into what is put by) may borrow the rest, if its wages carry the payment — a
 *     week's payment no more than a third of what its grown folk earn in a week — over the shortest
 *     term from eight weeks to twelve that they carry. The village is paid the whole price there and
 *     then (Homes.buy), and the household owns the house.</li>
 * <li><b>The week.</b> Every seventh day from its opening, the bank's round: each mortgage runs up a
 *     week's interest (four in the hundred on what is still owed), and the week's payment is taken out
 *     of the household's purses, then its savings at the bank. What the loans earned pays the savers
 *     their interest (one in the hundred a week, and never more than six parts in ten of what the loans
 *     earned: no loans, no interest); of what the bank keeps, half goes to the treasury, and half stays
 *     in the vault against a bad debt. No coin is made: a saver's interest is coin a borrower paid in.</li>
 * <li><b>Arrears.</b> A payment missed goes on the arrears, and the banker warns the household, and
 *     warns it again. Three weeks' payments behind, the bank takes the house back: the village buys
 *     it off the bank for what is still owed (the bank writes off what the treasury cannot find), and
 *     the household stays on in it as the village's tenant, paying rent. The chronicle tells it.</li>
 * <li><b>Players.</b> A player keeps an account too: talk to the banker ("deposit 20", "withdraw 10",
 *     "my account") or /village bank deposit|withdraw. Standing in an empty house, "a mortgage on this
 *     house" (or /village bank mortgage) buys it with a fifth down and the bank's loan for the rest,
 *     paid on the bank's round out of the player's account (and the rent its tenants pay it); three
 *     weeks behind and the house goes back to the village.</li>
 * </ul>
 * Everything is kept in the village's ledger notes (under "vault."), beside the rest of its books.
 */
public final class Bank {

    private Bank() {}

    /** A town this big (and into the Iron Age) wants a bank (Villages.projectsWanted). */
    public static final int FROM = 30;
    /** What the vault keeps back, in the hundred of what is on deposit: never lent. */
    static final int RESERVE = 30;
    /** A week's interest on what a mortgage still owes, and on a saver's account, in hundredths of a percent. */
    static final int LOAN_BP = 400, DEPOSIT_BP = 100;
    /** Of what the loans earned in the week, the most the savers may be paid, in the hundred. */
    static final int POOL = 60;
    /** Of what the bank kept in the week, the treasury's share, in the hundred. */
    static final int TREASURY_SHARE = 50;
    /** A fifth of the price down. */
    static final int DOWN_PART = 5;
    /** The terms a mortgage runs to, in weeks: the shortest the household's wages carry. A player's runs ten. */
    static final int SHORTEST = 8, LONGEST = 12, PLAYER_TERM = 10;
    /** A week's payment no more than this part of what the household's grown folk earn in a week. */
    static final int CARRY_PART = 3;
    /** Weeks' payments behind before the bank takes the house back. */
    public static final int ARREARS_LIMIT = 3;
    /** The bank's week, in days. */
    static final int WEEK = 7;
    /** The ledger on the lectern. */
    static final String TITLE = "The Bank's Ledger";

    // ------------------------------------------------------------------ the books

    /** A saver's account: what it has in the bank, and all the interest it has been paid. */
    static final class Account {
        final UUID who;
        boolean player;
        String name;
        int balance;
        int interestAll;
        long since;

        Account(UUID who, boolean player, String name, long since) {
            this.who = who;
            this.player = player;
            this.name = name;
            this.since = since;
        }
    }

    /** A mortgage on a house (by its anchor): who owes it, what was lent, what is still owed, the terms and the arrears. */
    static final class Loan {
        final long anchor;
        boolean player;
        final List<UUID> borrowers = new ArrayList<>();
        String names = "";
        int price, down, lent;
        /** What is still owed of what was lent, and the interest run up and not yet paid. */
        int principal, interest;
        int weekly, term;
        /** Coin behind, and in weeks' payments. */
        int arrears, missed;
        /** The part of a coin of interest not yet whole (in ten-thousandths). */
        int carry;
        long start;
        int paidAll, interestAll, weeksPaid;

        Loan(long anchor) {
            this.anchor = anchor;
        }

        int owed() {
            return principal + interest;
        }

        int weeksLeft() {
            return weekly <= 0 ? 0 : (owed() + weekly - 1) / weekly;
        }
    }

    /** A week's business. */
    static final class Week {
        long from = -1;
        int earned, paid, treasury, lent, repaid, deposited, withdrawn, written, foreclosed, loans;

        String encode() {
            return from + "|" + earned + "|" + paid + "|" + treasury + "|" + lent + "|" + repaid + "|" + deposited + "|" + withdrawn
                + "|" + written + "|" + foreclosed + "|" + loans;
        }

        static Week decode(@Nullable String s) {
            Week w = new Week();
            if (s == null || s.isEmpty()) return w;
            String[] p = s.split("\\|", -1);
            w.from = lng(p, 0, -1);
            w.earned = num(p, 1);
            w.paid = num(p, 2);
            w.treasury = num(p, 3);
            w.lent = num(p, 4);
            w.repaid = num(p, 5);
            w.deposited = num(p, 6);
            w.withdrawn = num(p, 7);
            w.written = num(p, 8);
            w.foreclosed = num(p, 9);
            w.loans = num(p, 10);
            return w;
        }
    }

    /** One village's bank. */
    static final class State {
        /** The coin in the vault. */
        int cash;
        long opened = -1, lastRound = -1;
        /** The odd hundredths of the savers' share of the week's earnings, carried to the next week. */
        int poolCarry;
        final Map<UUID, Account> accounts = new LinkedHashMap<>();
        final Map<Long, Loan> loans = new LinkedHashMap<>();
        Week week = new Week();
        @Nullable Week last;
        /** The round the ledger on the lectern was last written up for. */
        long ledgerRound = Long.MIN_VALUE;
    }

    private static final String HEAD = "vault.head", ACCT = "vault.acct/", LOAN = "vault.loan/", WEEK_NOW = "vault.week",
        WEEK_LAST = "vault.lastweek", TITHED = "vault.tithed";

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> TICKED = new ConcurrentHashMap<>();
    /** Why the bank last said no to a household, by its house; and what the banker wants for its work. */
    private static final Map<Long, String> WHY = new ConcurrentHashMap<>();
    private static final Map<UUID, String> WANTS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        STATES.clear();
        TICKED.clear();
        WHY.clear();
        WANTS.clear();
    }

    static State state(UUID village) {
        return STATES.computeIfAbsent(village, Bank::load);
    }

    private static State load(UUID village) {
        State s = new State();
        Map<String, String> notes = Ledger.notes(village);
        String head = notes.get(HEAD);
        if (head != null && !head.isEmpty()) {
            String[] p = head.split("\\|", -1);
            s.cash = num(p, 0);
            s.opened = lng(p, 1, -1);
            s.lastRound = lng(p, 2, -1);
            s.poolCarry = num(p, 3);
        }
        for (Map.Entry<String, String> e : notes.entrySet()) {
            String v = e.getValue();
            if (v == null || v.isEmpty()) continue;
            try {
                if (e.getKey().startsWith(ACCT)) {
                    String[] p = v.split("\\|", -1);
                    Account a = new Account(UUID.fromString(e.getKey().substring(ACCT.length())), "P".equals(p[1]), p[2], lng(p, 4, 0));
                    a.balance = num(p, 0);
                    a.interestAll = num(p, 3);
                    s.accounts.put(a.who, a);
                } else if (e.getKey().startsWith(LOAN)) {
                    String[] p = v.split("\\|", -1);
                    Loan l = new Loan(Long.parseLong(e.getKey().substring(LOAN.length())));
                    l.player = "P".equals(p[0]);
                    if (!p[1].isEmpty()) for (String u : p[1].split(",")) l.borrowers.add(UUID.fromString(u));
                    l.names = p[2];
                    l.price = num(p, 3);
                    l.down = num(p, 4);
                    l.lent = num(p, 5);
                    l.principal = num(p, 6);
                    l.interest = num(p, 7);
                    l.weekly = num(p, 8);
                    l.term = num(p, 9);
                    l.arrears = num(p, 10);
                    l.missed = num(p, 11);
                    l.carry = num(p, 12);
                    l.start = lng(p, 13, 0);
                    l.paidAll = num(p, 14);
                    l.interestAll = num(p, 15);
                    l.weeksPaid = num(p, 16);
                    s.loans.put(l.anchor, l);
                }
            } catch (RuntimeException ignored) {
                // a line the bank cannot read: left as it is
            }
        }
        s.week = Week.decode(notes.get(WEEK_NOW));
        String last = notes.get(WEEK_LAST);
        s.last = last == null || last.isEmpty() ? null : Week.decode(last);
        return s;
    }

    private static void saveHead(UUID village, State s) {
        Ledger.note(village, HEAD, s.cash + "|" + s.opened + "|" + s.lastRound + "|" + s.poolCarry);
        Ledger.note(village, WEEK_NOW, s.week.encode());
        if (s.last != null) Ledger.note(village, WEEK_LAST, s.last.encode());
    }

    private static void save(UUID village, Account a) {
        if (a.balance <= 0 && a.interestAll <= 0 && !a.player) {
            Ledger.forget(village, ACCT + a.who);
            return;
        }
        Ledger.note(village, ACCT + a.who, a.balance + "|" + (a.player ? "P" : "F") + "|" + a.name.replace("|", "") + "|" + a.interestAll
            + "|" + a.since);
    }

    private static void save(UUID village, Loan l) {
        StringBuilder who = new StringBuilder();
        for (UUID u : l.borrowers) who.append(who.length() == 0 ? "" : ",").append(u);
        Ledger.note(village, LOAN + l.anchor, (l.player ? "P" : "F") + "|" + who + "|" + l.names.replace("|", "") + "|" + l.price + "|" + l.down
            + "|" + l.lent + "|" + l.principal + "|" + l.interest + "|" + l.weekly + "|" + l.term + "|" + l.arrears + "|" + l.missed + "|" + l.carry
            + "|" + l.start + "|" + l.paidAll + "|" + l.interestAll + "|" + l.weeksPaid);
    }

    private static void close(UUID village, State s, Loan l) {
        s.loans.remove(l.anchor);
        Ledger.forget(village, LOAN + l.anchor);
    }

    private static void saveAll(UUID village, State s) {
        saveHead(village, s);
        for (Account a : s.accounts.values()) save(village, a);
        for (Loan l : s.loans.values()) save(village, l);
    }

    static int num(String[] p, int i) {
        try {
            return i < p.length && !p[i].isEmpty() ? Integer.parseInt(p[i].trim()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static long lng(String[] p, int i, long dflt) {
        try {
            return i < p.length && !p[i].isEmpty() ? Long.parseLong(p[i].trim()) : dflt;
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    // ------------------------------------------------------------------ the figures

    /** The bank's building, if the village has one standing. */
    @Nullable
    static Ledger.Building building(@Nullable UUID village) {
        return village == null ? null : Villages.builtStructure(village, "bank");
    }

    /** Is the bank open: built, and opened (Bank.tick, the morning)? Until then nothing changes. */
    public static boolean open(@Nullable UUID village) {
        return village != null && building(village) != null && state(village).opened >= 0;
    }

    /** The coin in the vault (Economy: the village's worth). */
    public static int cash(@Nullable UUID village) {
        return village == null || !STATES.containsKey(village) && building(village) == null ? 0 : state(village).cash;
    }

    static int deposits(State s) {
        int n = 0;
        for (Account a : s.accounts.values()) n += Math.max(0, a.balance);
        return n;
    }

    static int loansOut(State s) {
        int n = 0;
        for (Loan l : s.loans.values()) n += l.owed();
        return n;
    }

    /** What the vault keeps back: three coins in ten of what is on deposit. */
    static int reserve(State s) {
        return (deposits(s) * RESERVE + 99) / 100;
    }

    /** What it may lend: the vault, less what it keeps back. */
    static int lendable(State s) {
        return Math.max(0, s.cash - reserve(s));
    }

    /** What the bank is worth to itself: the vault and what it is owed, less what it owes its savers. */
    static int equity(State s) {
        return s.cash + loansOut(s) - deposits(s);
    }

    /** What is on deposit, all told. */
    public static int deposits(UUID village) {
        return building(village) == null ? 0 : deposits(state(village));
    }

    /** What its mortgages still owe, all told. */
    public static int loansOut(UUID village) {
        return building(village) == null ? 0 : loansOut(state(village));
    }

    /** A week's payment that pays off so much over so many weeks at so much a week (rounded up). */
    static int payment(int lent, int bp, int weeks) {
        if (lent <= 0 || weeks <= 0) return 0;
        double r = bp / 10000.0;
        double w = r == 0 ? lent / (double) weeks : lent * r / (1.0 - Math.pow(1.0 + r, -weeks));
        return Math.max(1, (int) Math.ceil(w - 1e-9));
    }

    // ------------------------------------------------------------------ who saves, and who keeps the bank

    /**
     * How careful a folk is with its coin: a Merchant most of all, a Traditionalist, a Guardian and a
     * Homemaker a little; a Free Spirit spends it. Hardworking and grumpy folk hold on to theirs;
     * easygoing, generous and merry ones let it go. The Thrifty knack counts twice, the Haggler once.
     */
    public static int thrift(VillageFolkEntity f) {
        int t = switch (Values.top(f)) {
            case WEALTH -> 2;
            case TRADITION, SAFETY, HOMES -> 1;
            case LEISURE -> -2;
            default -> 0;
        };
        Social.Life life = f.life();
        if (life.has(Social.Trait.HARDWORKING)) t++;
        if (life.has(Social.Trait.GRUMPY)) t++;
        if (life.has(Social.Trait.EASYGOING)) t--;
        if (life.has(Social.Trait.GENEROUS)) t--;
        if (life.has(Social.Trait.CHEERFUL) && life.has(Social.Trait.SOCIABLE)) t--;
        if (FolkSkills.active(f, FolkSkills.Knack.THRIFTY)) t += 2;
        if (FolkSkills.active(f, FolkSkills.Knack.HAGGLER)) t++;
        return Math.max(-3, Math.min(5, t));
    }

    /** "thrifty", "careful with its coin", "steady with its coin", "free with its coin", "a spendthrift". */
    public static String thriftWord(int t) {
        return t >= 3 ? "thrifty" : t == 2 ? "careful with its coin" : t >= 0 ? "steady with its coin" : t == -1 ? "free with its coin" : "a spendthrift";
    }

    /** Of what its purse holds over its week's needs, what part a folk of this nature puts in the bank, in the hundred. */
    static int share(int thrift) {
        return thrift >= 3 ? 75 : thrift == 2 ? 60 : thrift >= 0 ? 40 : thrift == -1 ? 20 : 0;
    }

    /** How well a folk would keep the bank: careful, shrewd (a Merchant, a haggler), and practised at it. */
    static int bankerScore(VillageFolkEntity f) {
        return 3 * thrift(f) + (Values.top(f) == Values.Value.WEALTH ? 4 : 0) + (FolkSkills.active(f, FolkSkills.Knack.HAGGLER) ? 2 : 0)
            + (f.life().has(Social.Trait.CURIOUS) ? 1 : 0) + f.tradeLevel(StationTask.BANK);
    }

    /** The village's banker, or null. */
    @Nullable
    public static VillageFolkEntity banker(@Nullable UUID village) {
        if (village == null) return null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == StationTask.BANK && !f.isBaby() && !f.isShowcase() && f.isAlive()) return f;
        }
        return null;
    }

    private static int hands(UUID village, StationTask t) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == t) n++;
        return n;
    }

    /**
     * The bank standing with nobody to keep it: the most careful, shrewdest hand the village can spare
     * takes it up — from a trade with hands to spare, never a workshop's only hand, never the storekeeper.
     */
    static void appoint(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Ledger.Building b = building(id);
        if (b == null || banker(id) != null) return;
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int pass = 0; pass < 2 && best == null; pass++) {
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isHired() || !f.isAlive()) continue;
                if (f.trip() != null || f.expedition() != null) continue;
                StationTask t = f.stationTask();
                boolean spare = t == StationTask.NONE || Villages.overStaffed(id, t)
                    || !t.isCraft() && t != StationTask.STORE && t != StationTask.GUARD && hands(id, t) >= 3;
                // The second look: anybody but a workshop's only hand or the storekeeper.
                if (pass == 0 ? !spare : t.isCraft() || t == StationTask.STORE || hands(id, t) < 2) continue;
                int score = bankerScore(f);
                if (score > bestScore) { bestScore = score; best = f; }
            }
        }
        if (best == null) return;
        StationTask was = best.stationTask();
        best.setStation(b.anchor(), StationTask.BANK);
        best.assignPlot(WorkZone.around(b.anchor(), 4, WorkZone.DEFAULT_DEPTH), "The Bank");
        String word = thriftWord(thrift(best));
        Villages.tell(id, day, best.displayNameCap() + " (" + word + (Values.top(best) == Values.Value.WEALTH ? ", and a shrewd Merchant" : "")
            + ") " + (was == StationTask.NONE ? "took up" : "gave up " + was.label + " for") + " keeping the bank");
        best.persona().remember(day, "I became the village's banker", 6);
        FolkTalk.speak(best, FolkTalk.pick(level.getRandom(), "The bank's mine to keep. Every coin counted, and counted twice.",
            "A banker! Somebody careful had to do it, I suppose.", "The vault, the ledger, the loans — I'll keep them straight."));
    }

    // ------------------------------------------------------------------ opening

    /** Every so often for each village (VillageFolkEntity, beside Homes.tick): the bank opens the day it stands, and gets its banker. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = TICKED.get(id);
        if (last != null && now - last < 600L && now >= last) return;
        TICKED.put(id, now);
        Ledger.Building b = building(id);
        if (b == null) return;
        long day = level.getDayTime() / 24000L;
        State s = state(id);
        if (s.opened < 0) openBank(v, b, s, day);
        appoint(level, v, day);
    }

    private static void openBank(Villages.Village v, Ledger.Building b, State s, long day) {
        UUID id = v.id();
        s.opened = day;
        s.lastRound = day;
        s.week = new Week();
        s.week.from = day;
        saveHead(id, s);
        String[] at = TownLife.address(id, v.centre(), b);
        String where = at == null ? "on the square" : "at " + at[0] + ", " + at[1];
        Villages.tell(id, day, "the bank opened its doors " + where + ": folk may put their savings by there, and borrow toward a house of their own");
        Market.assemblyNews(id, "The bank's open " + where + ". Put by what you don't need this week and it'll earn a little; "
            + "and a household with a fifth of a house's price saved can borrow the rest.");
    }

    // ------------------------------------------------------------------ the mornings

    /** What a folk keeps in its purse for the week: a dozen to live on, its share of the week's rent and of the week's payment on its house. */
    static int keep(UUID village, VillageFolkEntity f, State s) {
        int keep = Homes.LIVE_ON;
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        if (h != null && !Homes.seat(h)) {
            List<VillageFolkEntity> household = Homes.loadedMembers(village, h);
            int grown = Math.max(1, Homes.grown(household).size());
            if ((h.tenure == Homes.Tenure.RENTED && !Homes.rentFree(household)) || h.tenure == Homes.Tenure.PLAYER) {
                keep += WEEK * Math.max(0, h.rent) / grown;
            }
            Loan l = s.loans.get(h.anchor.asLong());
            if (l != null && !l.player) keep += (l.weekly + l.arrears + grown - 1) / grown;
        }
        if (thrift(f) <= 0) keep += 6;                                     // a little more loose, for the café and the market
        return keep;
    }

    /**
     * Before the rent (Market.tick, after the wages): a saver short of what the day's rent and the
     * week's payment want draws it out of its account, so the rent is paid out of what it has.
     */
    public static void beforeRent(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (!open(id)) return;
        State s = state(id);
        if (s.accounts.isEmpty()) return;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby()) continue;
            Account acct = s.accounts.get(f.getUUID());
            if (acct == null || acct.balance <= 0) continue;
            int keep = keep(id, f, s);
            if (f.purse() < keep) withdraw(id, s, f, keep - f.purse());
        }
        saveHead(id, s);
    }

    /**
     * The bank's morning (Market.tick, after the rent and the tithe): opened if it stands, a banker if
     * it has none, the mortgages of houses that have changed hands settled, the tithe on what is at the
     * bank, the day's savings put in, and on every seventh day the week's round.
     */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Ledger.Building b = building(id);
        if (b == null) return;
        State s = state(id);
        if (s.opened < 0) openBank(v, b, s, day);
        appoint(level, v, day);
        reconcile(level, v, s, day);
        tithe(v, s, day);
        deposits(level, v, s, day);
        if (s.lastRound < 0) s.lastRound = day;
        if (day - s.lastRound >= WEEK) round(level, v, s, day);
        saveHead(id, s);
    }

    /** Coin from a folk's purse onto its account and into the vault. Returns what went in. */
    static int deposit(UUID village, State s, VillageFolkEntity f, int n) {
        if (n <= 0 || !f.spend(n)) return 0;
        s.cash += n;
        Account a = s.accounts.computeIfAbsent(f.getUUID(), k -> new Account(k, false, f.displayNameCap(), f.level().getDayTime() / 24000L));
        a.name = f.displayNameCap();
        a.balance += n;
        s.week.deposited += n;
        save(village, a);
        return n;
    }

    /** Coin off a folk's account out of the vault into its purse, as far as both go. Returns what came out. */
    static int withdraw(UUID village, State s, VillageFolkEntity f, int n) {
        Account a = s.accounts.get(f.getUUID());
        if (a == null || n <= 0) return 0;
        int k = Math.min(n, Math.min(a.balance, s.cash));
        if (k <= 0) return 0;
        a.balance -= k;
        s.cash -= k;
        f.earn(k);
        s.week.withdrawn += k;
        save(village, a);
        return k;
    }

    /** The day's savings: each folk with more than its week wants puts its nature's share of the rest in the bank. */
    static void deposits(ServerLevel level, Villages.Village v, State s, long day) {
        UUID id = v.id();
        VillageFolkEntity most = null;
        int mostIn = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isHired()) continue;
            if (Homes.rentsAndWantsToOwn(f)) continue;                     // its savings go toward its house (Homes)
            int over = f.purse() - keep(id, f, s);
            if (over < 3) continue;
            int n = deposit(id, s, f, over * share(thrift(f)) / 100);
            if (n > mostIn) { mostIn = n; most = f; }
        }
        if (most != null && level.getRandom().nextInt(4) == 0) {
            FolkTalk.speak(most, FolkTalk.pick(level.getRandom(), "Into the bank with it. Rainy days come.",
                "A bit more put by. It earns while I sleep.", "Off to the bank before I spend it."));
        }
    }

    /**
     * The tithe on what is at the bank: on the rest day the purses gave theirs (Market.tithe), and every
     * account of ten coins or more gives one in ten, out of the vault into the treasury. What a folk
     * has at the bank is tithed as its purse would have been.
     */
    static void tithe(Villages.Village v, State s, long day) {
        UUID id = v.id();
        String today = Long.toString(day);
        if (!RestDay.today(id, day) || !today.equals(Ledger.note(id, "tithe.day")) || today.equals(Ledger.note(id, TITHED))) return;
        Ledger.note(id, TITHED, today);
        int in = 0, gave = 0;
        for (Account a : s.accounts.values()) {
            if (a.player || a.balance < 10) continue;
            int due = Math.min(a.balance / 10, s.cash);
            if (due <= 0) continue;
            a.balance -= due;
            s.cash -= due;
            in += due;
            gave++;
            save(id, a);
        }
        if (in <= 0) return;
        Ledger.addCoins(id, in);
        Economy.tithe(id, in);
        Villages.tell(id, day, gave + " savers gave the tithe on what they have at the bank, " + in + " coin, out of their accounts");
    }

    // ------------------------------------------------------------------ mortgages

    /**
     * A household that wants its house and has not the whole price (Homes.tenants, on payday): its
     * savings at the bank drawn toward the price first; then, with a fifth of the price put by, the
     * bank lends the rest, if its wages carry the payment and the vault has it to lend (less what it
     * keeps back). What it borrows goes into what it has put by, and Homes buys the house there and
     * then: the village has the whole price.
     */
    static void lend(ServerLevel level, Villages.Village v, Homes.Home h, List<VillageFolkEntity> household, long day) {
        UUID id = v.id();
        if (!open(id) || h.price <= 0 || h.saved >= h.price || Homes.seat(h)) return;
        State s = state(id);
        long key = h.anchor.asLong();
        if (s.loans.containsKey(key)) return;
        List<VillageFolkEntity> grown = Homes.grown(household);
        if (grown.isEmpty() || Homes.earners(household) == 0) { WHY.put(key, "nobody in the house earns"); return; }
        int need = h.price - h.saved;
        int savings = 0;
        for (VillageFolkEntity f : grown) {
            Account a = s.accounts.get(f.getUUID());
            if (a != null) savings += Math.max(0, a.balance);
        }
        savings = Math.min(savings, s.cash);
        String where = Homes.address(id, v, h);
        if (savings >= need) {
            drawToward(id, s, h, grown, need);
            Villages.tell(id, day, Homes.names(household) + " drew " + need + Homes.coins(need) + " of their savings out of the bank toward " + where);
            Homes.save(id, h);
            WHY.remove(key);
            return;
        }
        int down = (h.price + DOWN_PART - 1) / DOWN_PART;
        if (h.saved + savings < down) {
            WHY.put(key, "a fifth of the price down wanted: " + (h.saved + savings) + " of " + down + " put by");
            return;
        }
        int borrow = need - savings;
        if (borrow > lendable(s) - savings) {                              // its own savings come out of the vault first
            WHY.put(key, "the bank has only " + lendable(s) + Homes.coins(lendable(s)) + " to lend, keeping a reserve");
            return;
        }
        int wages = 0;
        for (VillageFolkEntity f : grown) wages += Wealth.wage(f);
        int carry = WEEK * wages / CARRY_PART;
        int term = -1, weekly = 0;
        for (int t = SHORTEST; t <= LONGEST; t++) {
            int w = payment(borrow, LOAN_BP, t);
            if (w <= carry) { term = t; weekly = w; break; }
        }
        if (term < 0) {
            WHY.put(key, "its wages (" + wages + " a day) would not carry " + payment(borrow, LOAN_BP, LONGEST) + " a week");
            return;
        }
        drawToward(id, s, h, grown, savings);
        s.cash -= borrow;
        h.saved += borrow;
        Loan l = new Loan(key);
        for (VillageFolkEntity f : grown) l.borrowers.add(f.getUUID());
        l.names = Homes.names(household);
        l.price = h.price;
        l.down = h.price - borrow;
        l.lent = borrow;
        l.principal = borrow;
        l.weekly = weekly;
        l.term = term;
        l.start = day;
        s.loans.put(key, l);
        s.week.lent += borrow;
        s.week.loans++;
        save(id, l);
        saveHead(id, s);
        Homes.save(id, h);
        WHY.remove(key);
        Villages.tell(id, day, "the bank lent " + l.names + " " + borrow + Homes.coins(borrow) + " toward " + where + " (" + l.down
            + " down; " + weekly + " a week for " + term + " weeks)");
        for (VillageFolkEntity f : grown) f.persona().remember(day, "we borrowed " + borrow + " coins from the bank to buy " + where, 6);
        VillageFolkEntity banker = banker(id);
        if (banker != null) FolkTalk.speak(banker, FolkTalk.pick(level.getRandom(), "A mortgage signed: " + weekly + " a week, mind, every week.",
            "There — the house is yours, and the bank's till it's paid."));
    }

    /** Its own savings out of the bank into what the household has put by (out of the vault). */
    private static void drawToward(UUID village, State s, Homes.Home h, List<VillageFolkEntity> grown, int sum) {
        int left = Math.min(sum, s.cash);
        for (VillageFolkEntity f : grown) {
            Account a = s.accounts.get(f.getUUID());
            if (a == null || left <= 0) continue;
            int k = Math.min(left, a.balance);
            if (k <= 0) continue;
            a.balance -= k;
            s.cash -= k;
            h.saved += k;
            s.week.withdrawn += k;
            left -= k;
            save(village, a);
        }
    }

    /** How a house was paid for, for the chronicle (Homes.buy): " put by out of their wages", or with the bank's loan. */
    static String howPaid(UUID village, Homes.Home h) {
        Loan l = STATES.containsKey(village) ? state(village).loans.get(h.anchor.asLong()) : null;
        return l == null || l.weeksPaid > 0 || l.paidAll > 0 ? " put by out of their wages"
            : ": " + l.down + " put by, and " + l.lent + " on a mortgage from the bank";
    }

    /** The mortgage on this folk's house, or null. */
    @Nullable
    static Loan loanOf(UUID village, VillageFolkEntity f) {
        if (!STATES.containsKey(village) && building(village) == null) return null;
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        if (h == null) return null;
        Loan l = state(village).loans.get(h.anchor.asLong());
        return l == null || l.player ? null : l;
    }

    /**
     * Houses that have changed hands, each morning: a mortgaged house its borrowers have left (sold back
     * to the village, moving up, moving in with a partner, all of them gone) has its mortgage called
     * in — out of their purses and savings, and the rest from the treasury, which has the house back;
     * a house whose borrowers are gone and whose grown children live on in it is theirs, and so is
     * what is owed on it.
     */
    static void reconcile(ServerLevel level, Villages.Village v, State s, long day) {
        UUID id = v.id();
        Map<Long, Homes.Home> homes = Homes.homes(id);
        for (Loan l : new ArrayList<>(s.loans.values())) {
            if (l.player) {
                Homes.Home h = homes.get(l.anchor);
                if (h == null || h.tenure != Homes.Tenure.PLAYER || l.borrowers.isEmpty() || !l.borrowers.get(0).equals(h.landlord)) {
                    settle(level, v, s, l, h, day, "the house is no longer theirs");
                }
                continue;
            }
            Homes.Home h = homes.get(l.anchor);
            boolean owned = h != null && h.tenure == Homes.Tenure.OWNED;
            boolean theirs = false;
            if (owned) for (UUID u : l.borrowers) if (h.members.contains(u)) { theirs = true; break; }
            if (owned && !theirs && !h.members.isEmpty()) {
                List<VillageFolkEntity> grown = Homes.grown(Homes.loadedMembers(id, h));
                if (!grown.isEmpty()) {
                    l.borrowers.clear();
                    for (VillageFolkEntity f : grown) l.borrowers.add(f.getUUID());
                    l.names = Homes.names(Homes.loadedMembers(id, h));
                    save(id, l);
                    Villages.tell(id, day, l.names + " took on the mortgage on " + Homes.address(id, v, h) + ", " + l.owed() + Homes.coins(l.owed())
                        + " still owed");
                    continue;
                }
            }
            if (owned && theirs) continue;
            settle(level, v, s, l, h, day, h == null || h.members.isEmpty() ? "the house stands empty" : "they have left the house");
        }
    }

    /** A mortgage called in: its borrowers' purses and savings, then the treasury (it has the house back), then written off. */
    private static void settle(ServerLevel level, Villages.Village v, State s, Loan l, @Nullable Homes.Home h, long day, String why) {
        UUID id = v.id();
        int owed = l.owed();
        int got = 0;
        for (UUID u : l.borrowers) {
            Account a = s.accounts.get(u);
            if (a != null && got < owed) {
                int k = Math.min(owed - got, a.balance);
                a.balance -= k;
                got += k;
                save(id, a);
            }
            VillageFolkEntity f = Homes.loaded(id, u);
            if (f != null && got < owed) {
                int k = Math.min(owed - got, f.purse());
                if (k > 0 && f.spend(k)) {
                    got += k;
                    s.cash += k;
                }
            }
        }
        int fromTreasury = 0;
        if (got < owed && (h == null || h.tenure != Homes.Tenure.OWNED)) {
            fromTreasury = Ledger.takeCoins(id, Math.min(owed - got, spare(id)));
            if (fromTreasury > 0) Economy.spent(id, fromTreasury);
            s.cash += fromTreasury;
        }
        int written = owed - got - fromTreasury;
        book(s, l, got + fromTreasury);
        s.week.written += written;
        close(id, s, l);
        saveHead(id, s);
        Villages.tell(id, day, "the mortgage on " + (h == null ? "a house" : Homes.address(id, v, h)) + " was settled (" + why + "): " + got
            + " from " + l.names + (fromTreasury > 0 ? ", " + fromTreasury + " from the village" : "") + (written > 0 ? ", " + written + " written off" : ""));
    }

    /** What the treasury can spare for the bank: what it holds over a day's wages. */
    private static int spare(UUID village) {
        return Math.max(0, Ledger.coins(village) - Market.wageBill(village));
    }

    /** Coin paid on a loan: the interest first, then what was lent. */
    private static void book(State s, Loan l, int paid) {
        int toInterest = Math.min(paid, l.interest);
        l.interest -= toInterest;
        int toPrincipal = Math.min(paid - toInterest, l.principal);
        l.principal -= toPrincipal;
        l.paidAll += toInterest + toPrincipal;
        l.interestAll += toInterest;
        s.week.earned += toInterest;
        s.week.repaid += toPrincipal;
    }

    // ------------------------------------------------------------------ the week's round

    /** Every seventh day: the mortgages' payments, the savers' interest out of what they earned, the treasury's share, the ledger. */
    static void round(ServerLevel level, Villages.Village v, State s, long day) {
        UUID id = v.id();
        s.lastRound = day;
        for (Loan l : new ArrayList<>(s.loans.values())) collect(level, v, s, l, day);
        payInterest(id, s);
        shareOut(id, s);
        Week done = s.week;
        if (done.earned > 0 || done.paid > 0 || done.treasury > 0 || done.lent > 0) {
            Villages.tell(id, day, "the bank's week: " + done.earned + " earned on its loans, " + done.paid + " paid to its savers, "
                + done.treasury + " to the treasury; " + loansOut(s) + " still lent out on " + s.loans.size()
                + (s.loans.size() == 1 ? " house" : " houses") + ", " + deposits(s) + " on deposit");
        }
        s.last = done;
        s.week = new Week();
        s.week.from = day;
        saveAll(id, s);
        Ledger.Building b = building(id);
        if (b != null && level.isLoaded(b.anchor())) ledger(level, v, b, banker(id), false);      // the ledger written up, if one lies there
    }

    /** A mortgage's week: its interest run up, its payment taken, and what comes of it (paid off, behind, the house taken back). */
    static void collect(ServerLevel level, Villages.Village v, State s, Loan l, long day) {
        UUID id = v.id();
        long x = (long) l.principal * LOAN_BP + l.carry;
        l.interest += (int) (x / 10000L);
        l.carry = (int) (x % 10000L);
        int due = Math.min(l.owed(), l.weekly + l.arrears);
        int paid = 0;
        Homes.Home h = Homes.homes(id).get(l.anchor);
        if (l.player) {
            UUID who = l.borrowers.isEmpty() ? null : l.borrowers.get(0);
            Account a = who == null ? null : s.accounts.get(who);
            if (a != null) {
                int k = Math.min(due, a.balance);
                a.balance -= k;
                paid += k;
                save(id, a);
            }
            if (paid < due && who != null) {
                String key = "rentdue/" + who;                             // the rent its tenants have paid it (Homes)
                int rent = Homes.parse(Ledger.note(id, key));
                int k = Math.min(due - paid, rent);
                if (k > 0) {
                    Ledger.note(id, key, Integer.toString(rent - k));
                    s.cash += k;
                    paid += k;
                }
            }
        } else {
            List<VillageFolkEntity> household = h == null ? List.of() : Homes.loadedMembers(id, h);
            if (h != null && !household.isEmpty()) {
                int k = Homes.take(household, h, due);                     // its purses, the fullest first
                s.cash += k;
                paid += k;
            }
            for (UUID u : l.borrowers) {
                if (paid >= due) break;
                Account a = s.accounts.get(u);
                if (a == null) continue;
                int k = Math.min(due - paid, a.balance);
                a.balance -= k;
                paid += k;
                save(id, a);
            }
        }
        book(s, l, paid);
        if (l.owed() <= 0) {
            close(id, s, l);
            String where = h == null ? "their house" : Homes.address(id, v, h);
            Villages.tell(id, day, l.names + " paid off their mortgage on " + where + ": " + l.paidAll + Homes.coins(l.paidAll)
                + " paid the bank in all, " + l.interestAll + " of it interest; the house is theirs outright");
            if (!l.player) for (UUID u : l.borrowers) {
                VillageFolkEntity f = Homes.loaded(id, u);
                if (f == null) continue;
                f.persona().remember(day, "we paid off the mortgage on " + where, 7);
                FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "Paid off! Every brick of it ours now.", "The last payment — the house is ours outright!"));
            }
            tellPlayer(level, l, "Your mortgage on " + where + " is paid off: the house is yours outright.");
            return;
        }
        int was = l.missed;
        if (paid >= due) {
            l.arrears = 0;
            l.missed = 0;
            l.weeksPaid++;
        } else {
            l.arrears = due - paid;
            l.missed = l.weekly <= 0 ? 1 : (l.arrears + l.weekly - 1) / l.weekly;
        }
        save(id, l);
        if (l.missed >= ARREARS_LIMIT) {
            foreclose(level, v, s, l, h, day);
            return;
        }
        if (l.missed > was) warn(level, v, l, h, day);
    }

    /** A payment missed: the banker writes to them, and the chronicle has it. */
    private static void warn(ServerLevel level, Villages.Village v, Loan l, @Nullable Homes.Home h, long day) {
        UUID id = v.id();
        String where = h == null ? "their house" : Homes.address(id, v, h);
        boolean last = l.missed >= ARREARS_LIMIT - 1;
        Villages.tell(id, day, l.names + (l.missed == 1 ? " missed a payment" : " are " + l.missed + " payments behind") + " on their mortgage on "
            + where + " (" + l.arrears + Homes.coins(l.arrears) + " behind): " + (last ? "the banker's last warning" : "the banker has warned them"));
        VillageFolkEntity banker = banker(id);
        if (!l.player) for (UUID u : l.borrowers) {
            VillageFolkEntity f = Homes.loaded(id, u);
            if (f == null) continue;
            f.persona().remember(day, last ? "the bank gave us its last warning about the house" : "we missed a payment to the bank", last ? 6 : 4);
            if (banker != null && banker != f) f.persona().remember(day, banker.displayNameCap() + " the banker warned us about the payments", 3);
        }
        if (banker != null) FolkTalk.speak(banker, last
            ? FolkTalk.pick(level.getRandom(), "Two weeks behind on " + where + ". One more and the bank takes it back.",
                "A last warning has gone to " + l.names + ". I take no pleasure in it.")
            : FolkTalk.pick(level.getRandom(), "A payment missed on " + where + ". A word in the right ear, I think.",
                l.names + " are behind. I've written to them."));
        tellPlayer(level, l, (last ? "Last warning: " : "") + "you are " + l.missed + (l.missed == 1 ? " payment" : " payments") + " behind on your mortgage on "
            + where + " (" + l.arrears + " coins). Put coin in your account at the bank (/village bank deposit) before its next round.");
    }

    /**
     * Three weeks behind: the bank takes the house back. The village buys it off the bank for what is
     * still owed on it (the bank writing off what the treasury cannot find), and lets it — to the
     * household that lived in it, which stays on as the village's tenant.
     */
    static void foreclose(ServerLevel level, Villages.Village v, State s, Loan l, @Nullable Homes.Home h, long day) {
        UUID id = v.id();
        int owed = l.owed();
        int back = Ledger.takeCoins(id, Math.min(owed, spare(id)));      // never the day's wages
        if (back > 0) Economy.spent(id, back);
        s.cash += back;
        int written = owed - back;
        book(s, l, back);
        s.week.written += written;
        s.week.foreclosed++;
        close(id, s, l);
        saveHead(id, s);
        String where = h == null ? "their house" : Homes.address(id, v, h);
        if (h != null) {
            h.tenure = Homes.Tenure.RENTED;
            h.landlord = null;
            h.landlordName = "";
            h.toLet = false;
            h.price = Homes.price(id, h);
            h.rent = Homes.rent(id, h);
            h.owed = 0;
            h.since = day;
            Homes.save(id, h);
        }
        boolean livedIn = h != null && !h.members.isEmpty();
        Villages.tell(id, day, "the bank took back " + where + " from " + l.names + ", " + ARREARS_LIMIT + " weeks' payments behind: the village bought it "
            + "back for " + (written > 0 ? back + " of the " + owed + " coins still owed (the bank wrote off " + written + ")"
                : "the " + owed + Homes.coins(owed) + " still owed")
            + (livedIn ? ", and they rent it from the village now" : ", and lets it"));
        Market.assemblyNews(id, "The bank has taken back " + where + ". " + (livedIn ? l.names + " stay on as the village's tenants." : "It's the village's to let now."));
        if (!l.player) for (UUID u : l.borrowers) {
            VillageFolkEntity f = Homes.loaded(id, u);
            if (f == null) continue;
            f.persona().remember(day, "we lost " + where + " to the bank", 8);
            FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "The bank's had the house back. We rent it now, like we started.",
                "Lost it. Three weeks behind and that was that.", "It's the village's again. At least they let us stay."));
        }
        tellPlayer(level, l, "The bank has taken back " + where + ": you were " + ARREARS_LIMIT + " weeks' payments behind. The house is the village's again.");
    }

    /** The savers' interest: a coin in a hundred a week, out of six parts in ten of what the loans earned at most, shared by what each has in. */
    static void payInterest(UUID village, State s) {
        int earned = s.week.earned;
        long total = 0;
        for (Account a : s.accounts.values()) total += Math.max(0, a.balance);
        if (earned <= 0 || total <= 0) return;
        int pot = earned * POOL + s.poolCarry;
        int pool = pot / 100;
        s.poolCarry = pot % 100;
        int pay = (int) Math.min(pool, total * DEPOSIT_BP / 10000L);
        if (pay <= 0) return;
        List<Account> savers = new ArrayList<>();
        for (Account a : s.accounts.values()) if (a.balance > 0) savers.add(a);
        int[] got = new int[savers.size()];
        double[] odd = new double[savers.size()];
        int given = 0;
        for (int i = 0; i < savers.size(); i++) {
            double exact = pay * (double) savers.get(i).balance / total;
            got[i] = (int) Math.floor(exact);
            odd[i] = exact - got[i];
            given += got[i];
        }
        // The odd coins to whoever came nearest a whole one.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < savers.size(); i++) order.add(i);
        order.sort((p, q) -> Double.compare(odd[q], odd[p]));
        for (int k = 0; k < order.size() && given < pay; k++) { got[order.get(k)]++; given++; }
        for (int i = 0; i < savers.size(); i++) {
            Account a = savers.get(i);
            a.balance += got[i];
            a.interestAll += got[i];
            save(village, a);
        }
        s.week.paid += pay;
    }

    /** The treasury's share of what the bank kept this week: half, out of the vault, never below what it keeps back nor past what it is worth. */
    static void shareOut(UUID village, State s) {
        int kept = s.week.earned - s.week.paid;
        if (kept <= 0) return;
        int share = (kept * TREASURY_SHARE + 50) / 100;                    // the odd coin to the treasury
        share = Math.min(share, Math.max(0, equity(s)));
        share = Math.min(share, Math.max(0, s.cash - reserve(s)));
        if (share <= 0) return;
        s.cash -= share;
        Ledger.addCoins(village, share);
        s.week.treasury += share;
    }

    // ------------------------------------------------------------------ comings and goings

    /**
     * A folk gone for good (VillageFolkEntity: dead, or moved away). Its savings: if it died, to its
     * partner's account, or a grown child's, or with neither the vault pays them to the treasury; if it
     * moved away, it takes them with it. Its mortgage is settled the next morning (reconcile).
     */
    public static void left(@Nullable UUID village, VillageFolkEntity f, boolean died) {
        if (village == null || !STATES.containsKey(village) && building(village) == null) return;
        State s = state(village);
        Account a = s.accounts.get(f.getUUID());
        if (a == null || a.balance <= 0) {
            if (a != null) { s.accounts.remove(a.who); Ledger.forget(village, ACCT + a.who); }
            return;
        }
        long day = f.level().getDayTime() / 24000L;
        int sum = a.balance;
        s.accounts.remove(a.who);
        Ledger.forget(village, ACCT + a.who);
        if (!died) {
            int k = Math.min(sum, s.cash);
            s.cash -= k;
            f.earn(k);
            s.week.withdrawn += k;
            saveHead(village, s);
            return;
        }
        VillageFolkEntity heir = null;
        UUID partner = f.life().partner();
        if (partner != null) heir = Homes.loaded(village, partner);
        if (heir == null || !heir.isAlive()) {
            heir = null;
            for (AssistantEntity o : Villages.folkOf(village)) {
                if (o instanceof VillageFolkEntity c && c != f && c.isAlive() && !c.isBaby() && c.parentIds().contains(f.getUUID())) { heir = c; break; }
            }
        }
        if (heir != null) {
            VillageFolkEntity to = heir;
            Account h = s.accounts.computeIfAbsent(to.getUUID(), k -> new Account(k, false, to.displayNameCap(), day));
            h.balance += sum;
            save(village, h);
            Villages.tell(village, day, f.displayNameCap() + "'s savings at the bank, " + sum + Homes.coins(sum) + ", went to " + to.displayNameCap());
        } else {
            int k = Math.min(sum, s.cash);
            s.cash -= k;
            Ledger.addCoins(village, k);
            Villages.tell(village, day, f.displayNameCap() + " left " + k + Homes.coins(k) + " at the bank and nobody to leave it to: it went to the village");
        }
        saveHead(village, s);
    }

    // ------------------------------------------------------------------ the banker's work

    /** The vault's gate and grille, in the drawing's terms (across, up, toward the back): bank.txt's gaps in the vault's front. */
    private static final int[][] BARS = { { 0, 0, 1 }, { -1, 1, 1 }, { 0, 1, 1 }, { 1, 1, 1 } };

    static List<BlockPos> barSpots(Ledger.Building b) {
        List<BlockPos> out = new ArrayList<>();
        Direction right = b.facing().getClockWise();
        for (int[] c : BARS) out.add(b.anchor().relative(right, c[0]).relative(b.facing(), c[2]).above(c[1]));
        return out;
    }

    @Nullable
    static BlockPos lectern(Ledger.Building b) {
        for (BuildGoal.Placement p : BuildGoal.plan("bank", b.anchor(), b.facing(), 13)) {
            if (p.part() == BuildGoal.Part.LECTERN) return p.pos();
        }
        return null;
    }

    /** A piece of the banker's work (Crafts.now): the vault's bars set, the ledger made and laid on the lectern, written up each week. */
    static String work(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        UUID id = v.id();
        Ledger.Building b = building(id);
        if (b == null || !open(id) || !level.isLoaded(b.anchor())) return null;
        String bars = bars(level, v, b);
        if (bars != null) return bars;
        return ledger(level, v, b, f, true);
    }

    /** The vault's bars: out of the stores, or made of six of the village's spare iron ingots (sixteen bars, the rest put by). */
    static String bars(ServerLevel level, Villages.Village v, Ledger.Building b) {
        UUID id = v.id();
        List<BlockPos> want = new ArrayList<>();
        for (BlockPos p : barSpots(b)) if (level.getBlockState(p).canBeReplaced()) want.add(p);       // air, or a tuft of grass
        if (want.isEmpty()) return null;
        boolean made = false;
        if (Market.stock(level, id, s -> s.is(Items.IRON_BARS)) < want.size()) {
            // Never the iron the village is putting by for its age, nor the smith's last bars (Crafts, Bench).
            if (Crafts.savingIron(level, v) || Market.stock(level, id, s -> s.is(Items.IRON_INGOT)) < 6 + Crafts.IRON_KEPT + 8) {
                WANTS.put(id, "six iron ingots the village can spare, for the vault's bars");
                return null;
            }
            if (!TownWork.take(level, v, s -> s.is(Items.IRON_INGOT), 6)) return null;
            Crafts.store(level, v, new ItemStack(Items.IRON_BARS, 16));
            made = true;
        }
        int set = 0;
        for (BlockPos p : want) {
            ItemStack bar = Crafts.takeOne(level, v, s -> s.is(Items.IRON_BARS));
            if (bar.isEmpty()) break;
            level.setBlock(p, Block.updateFromNeighbourShapes(Blocks.IRON_BARS.defaultBlockState(), level, p), 3);
            set++;
        }
        if (set == 0) return null;
        WANTS.remove(id);
        return made ? "iron bars for the vault, out of six of the village's ingots" : "the vault's iron bars set";
    }

    /**
     * The ledger: a book and quill made of a book, an ink sac and a feather from the stores, written up
     * and laid on the lectern (the banker's work: make); written up again after each round.
     */
    static String ledger(ServerLevel level, Villages.Village v, Ledger.Building b, @Nullable VillageFolkEntity f, boolean make) {
        UUID id = v.id();
        BlockPos at = lectern(b);
        if (at == null || !level.isLoaded(at)) return null;
        BlockState st = level.getBlockState(at);
        if (!(st.getBlock() instanceof LecternBlock)) return null;
        State s = state(id);
        if (st.getValue(LecternBlock.HAS_BOOK)) {
            if (!(level.getBlockEntity(at) instanceof LecternBlockEntity le) || !isLedger(le.getBook()) || s.ledgerRound == s.lastRound) return null;
            le.setBook(book(id, f));
            le.setChanged();
            s.ledgerRound = s.lastRound;
            return "the week's accounts, in the ledger";
        }
        if (!make) return null;
        if (Market.stock(level, id, x -> x.is(Items.BOOK)) < 1 || Market.stock(level, id, x -> x.is(Items.INK_SAC)) < 1
                || Market.stock(level, id, x -> x.is(Items.FEATHER)) < 1) {
            WANTS.put(id, "a book, an ink sac and a feather for the ledger");
            return null;
        }
        ItemStack paper = Crafts.takeOne(level, v, x -> x.is(Items.BOOK));
        ItemStack ink = Crafts.takeOne(level, v, x -> x.is(Items.INK_SAC));
        ItemStack quill = Crafts.takeOne(level, v, x -> x.is(Items.FEATHER));
        if (paper.isEmpty() || ink.isEmpty() || quill.isEmpty()) {
            for (ItemStack back : List.of(paper, ink, quill)) if (!back.isEmpty()) Crafts.store(level, v, back);
            return null;
        }
        ItemStack ledger = book(id, f);
        if (!LecternBlock.tryPlaceBook(f, level, at, st, ledger)) {
            for (ItemStack back : List.of(paper, ink, quill)) Crafts.store(level, v, back);
            return null;
        }
        s.ledgerRound = s.lastRound;
        WANTS.remove(id);
        return "the bank's ledger, written up and laid on the lectern";
    }

    static boolean isLedger(ItemStack stack) {
        WrittenBookContent c = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        return c != null && TITLE.equals(c.title().raw());
    }

    /** The ledger as it stands: the vault, the deposits and the loans, last week's business, every mortgage and the savers. */
    static ItemStack book(UUID village, @Nullable VillageFolkEntity banker) {
        State s = state(village);
        String front = "The Bank of " + Villages.name(village) + "\n" + (banker == null ? "" : "kept by " + banker.displayNameCap() + "\n")
            + "Opened day " + s.opened + "\n\nIn the vault: " + s.cash + "\nOn deposit: " + deposits(s) + "\nLent out: " + loansOut(s)
            + "\nKept back: " + reserve(s);
        List<String> entries = new ArrayList<>();
        Week w = s.last != null ? s.last : s.week;
        entries.add("§lThe week§r\nEarned " + w.earned + " on its loans; paid its savers " + w.paid + "; the treasury " + w.treasury + "."
            + (w.written > 0 ? " Wrote off " + w.written + "." : ""));
        Villages.Village v = Villages.get(village);
        entries.add("§lMortgages§r" + (s.loans.isEmpty() ? "\nNone." : ""));
        for (Loan l : s.loans.values()) {
            Homes.Home h = Homes.homes(village).get(l.anchor);
            entries.add(l.names + ", " + (h == null || v == null ? "a house" : Homes.address(village, v, h)) + ": owes " + l.owed() + ", " + l.weekly
                + " a week, " + l.weeksLeft() + " weeks to go" + (l.missed > 0 ? "; " + l.missed + " behind" : ""));
        }
        List<Account> savers = new ArrayList<>(s.accounts.values());
        savers.removeIf(a -> a.balance <= 0);
        savers.sort(Comparator.comparingInt((Account a) -> -a.balance));
        StringBuilder sb = new StringBuilder("§lSavers§r");
        for (int i = 0; i < Math.min(12, savers.size()); i++) sb.append("\n").append(savers.get(i).name).append(" ").append(savers.get(i).balance);
        if (savers.isEmpty()) sb.append("\nNone yet.");
        entries.add(sb.toString());
        return Services.book(TITLE, banker == null ? "the banker" : banker.displayNameCap(), front, entries);
    }

    // ------------------------------------------------------------------ players

    /**
     * A player at the bank (Commerce.bank, the "The bank" button and "deposit 20" said to the banker):
     * "deposit 20", "withdraw 10", "a mortgage on this house", "repay 10" (its mortgage), or how its
     * account stands. Null if the bank is not open, or for the treasury's own small loans ("borrow 30",
     * and "repay" with no mortgage), which are the treasury's business still (Commerce).
     */
    @Nullable
    public static String ask(VillageFolkEntity f, Player p, String text) {
        UUID id = f.ownerId();
        if (id == null || !open(id) || !(f.level() instanceof ServerLevel level)) return null;
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        boolean mortgage = t.contains("mortgage");
        boolean repays = t.contains("repay") || t.contains("pay back") || t.contains("pay off");
        if (!mortgage && (t.contains("borrow") || t.contains("a loan") || t.contains("lend") || repays && playerLoan(id, p.getUUID()) == null)) return null;
        VillageFolkEntity banker = banker(id);
        if (banker != null && banker != f) {
            return "That's the bank's business — see " + banker.displayNameCap() + ", our banker, at the bank. Or /village bank, if you're in a hurry.";
        }
        return handle(level, id, p, t);
    }

    /** What a player asked of the bank, done. */
    static String handle(ServerLevel level, UUID id, Player p, String t) {
        State s = state(id);
        String moved = fromTreasury(id, s, p);
        int n = Commerce.number(t, 0);
        long day = level.getDayTime() / 24000L;
        String name = p.getName().getString();
        if (t.contains("mortgage") || t.contains("buy this house")) {
            Loan mine = playerLoan(id, p.getUUID());
            if (mine != null && !t.contains("buy")) return moved + loanLine(id, mine);
            return moved + playerMortgage(level, id, p);
        }
        if (t.contains("deposit") || t.contains("put by") || t.contains("put in ")) {
            n = Math.min(n <= 0 ? 10 : n, Market.coinsHeld(p));
            if (n <= 0) return moved + "You've no coin on you to put in.";
            Market.payOut(p, n);
            s.cash += n;
            Account a = s.accounts.computeIfAbsent(p.getUUID(), k -> new Account(k, true, name, day));
            a.balance += n;
            s.week.deposited += n;
            save(id, a);
            saveHead(id, s);
            return moved + "Into the vault: " + n + Homes.coins(n) + ". " + accountLine(id, p.getUUID());
        }
        if (t.contains("withdraw") || t.contains("take out") || t.contains("draw out")) {
            Account a = s.accounts.get(p.getUUID());
            int have = a == null ? 0 : a.balance;
            n = Math.min(n <= 0 ? have : n, have);
            if (n <= 0) return moved + "You've nothing in the bank.";
            int k = Math.min(n, s.cash);
            if (k <= 0) return moved + "The vault's empty just now — it's all lent out. Come back after the bank's round.";
            a.balance -= k;
            s.cash -= k;
            s.week.withdrawn += k;
            Dealings.giveCoins(p, k);
            save(id, a);
            saveHead(id, s);
            return moved + "Out of the vault: " + k + Homes.coins(k) + (k < n ? " (all it holds just now)" : "") + ". " + accountLine(id, p.getUUID());
        }
        if (t.contains("repay") || t.contains("pay back") || t.contains("pay off")) {
            Loan l = playerLoan(id, p.getUUID());
            if (l == null) return moved + "You've no mortgage with us.";
            n = Math.min(Math.min(n <= 0 ? l.owed() : n, l.owed()), Market.coinsHeld(p));
            if (n <= 0) return moved + "You've no coin on you to pay it with.";
            Market.payOut(p, n);
            s.cash += n;
            book(s, l, n);
            l.arrears = Math.max(0, l.arrears - n);
            l.missed = l.arrears <= 0 || l.weekly <= 0 ? 0 : (l.arrears + l.weekly - 1) / l.weekly;
            if (l.owed() <= 0) {
                close(id, s, l);
                saveHead(id, s);
                Villages.tell(id, day, name + " paid off the mortgage on " + where(id, l));
                return moved + "Paid off. " + capital(where(id, l)) + " is yours outright.";
            }
            save(id, l);
            saveHead(id, s);
            return moved + "Paid " + n + ". " + loanLine(id, l);
        }
        return moved + accountLine(id, p.getUUID()) + " Say \"deposit 20\", \"withdraw 10\", or, standing in an empty house, \"a mortgage on this house\".";
    }

    /** A player's account and mortgage, in a line. */
    static String accountLine(UUID id, UUID player) {
        State s = state(id);
        Account a = s.accounts.get(player);
        Loan l = playerLoan(id, player);
        String acct = a == null || a.balance <= 0 ? "You've nothing in the bank." : "You've " + a.balance + Homes.coins(a.balance)
            + " in the bank" + (a.interestAll > 0 ? ", " + a.interestAll + " of it interest" : "") + "; it earns a coin in a hundred a week, out of what its loans earn.";
        return acct + (l == null ? "" : " " + loanLine(id, l));
    }

    static String loanLine(UUID id, Loan l) {
        return "Your mortgage on " + where(id, l) + ": " + l.owed() + " owed, " + l.weekly + " a week out of your account, " + l.weeksLeft() + " weeks to go"
            + (l.missed > 0 ? "; " + l.missed + (l.missed == 1 ? " payment" : " payments") + " behind (" + l.arrears + " coins)" : "") + ".";
    }

    private static String where(UUID id, Loan l) {
        Villages.Village v = Villages.get(id);
        Homes.Home h = Homes.homes(id).get(l.anchor);
        return h == null || v == null ? "the house" : Homes.address(id, v, h);
    }

    @Nullable
    static Loan playerLoan(UUID id, UUID player) {
        for (Loan l : state(id).loans.values()) if (l.player && l.borrowers.contains(player)) return l;
        return null;
    }

    /** What a player had put by at the treasury before there was a bank (Commerce), moved over to the bank, if the treasury can spare it. */
    static String fromTreasury(UUID id, State s, Player p) {
        String key = "bank/" + p.getUUID();
        String[] b = Commerce.fields(id, key);
        int had = b == null ? 0 : (int) Commerce.num(b[0]);
        if (had <= 0 || Ledger.coins(id) - Market.wageBill(id) < had) return "";
        int took = Ledger.takeCoins(id, had);
        if (took <= 0) return "";
        s.cash += took;
        Account a = s.accounts.computeIfAbsent(p.getUUID(), k -> new Account(k, true, p.getName().getString(), p.level().getDayTime() / 24000L));
        a.balance += took;
        save(id, a);
        saveHead(id, s);
        Ledger.note(id, key, (had - took) + "|" + (b.length >= 3 ? b[1] + "|" + b[2] : "0|0"));
        return "Your " + took + Homes.coins(took) + " put by at the treasury came over to the bank. ";
    }

    /**
     * A player buys the empty house it stands in (or the nearest) with a mortgage: a fifth down, out of
     * its account and then its pack, and the bank's loan for the rest; the village has the whole price.
     * The terms are a player's as Homes.playerBuys has them (a citizen at the price, a friend a quarter over).
     */
    static String playerMortgage(ServerLevel level, UUID id, Player p) {
        Villages.Village v = Villages.get(id);
        if (v == null) return "There's no village here.";
        Homes.enrol(id);
        Homes.Home h = Homes.homeAt(id, p.blockPosition());
        if (h == null || !h.members.isEmpty() || h.tenure == Homes.Tenure.PLAYER || Homes.seat(h)) {
            h = null;
            double best = Double.MAX_VALUE;
            for (Homes.Home o : Homes.homes(id).values()) {
                if (!o.members.isEmpty() || o.tenure == Homes.Tenure.PLAYER || Homes.seat(o)) continue;
                double d = o.anchor.distSqr(p.blockPosition());
                if (d < best && d < 24 * 24) { best = d; h = o; }
            }
        }
        if (h == null) return "There's no empty house near you for sale. Stand in the one you want and ask again.";
        State s = state(id);
        Loan already = playerLoan(id, p.getUUID());
        if (already != null) return "One mortgage at a time: " + loanLine(id, already);
        Standing.Title title = Standing.of(id, p.getUUID(), level.getGameTime()).title();
        boolean citizen = Ledger.citizens(id).containsKey(p.getUUID());
        if (!citizen && !title.atLeast(Standing.Title.FRIEND)) return Villages.name(id) + "'s bank lends to its friends. Be one first.";
        int price = (int) Math.round(Homes.price(id, h) * (citizen ? 1.0 : 1.25));
        int down = (price + DOWN_PART - 1) / DOWN_PART;
        Account a = s.accounts.get(p.getUUID());
        int banked = a == null ? 0 : a.balance, held = Market.coinsHeld(p);
        String where = Homes.address(id, v, h);
        if (banked + held < down) {
            return capital(where) + " is " + price + Homes.coins(price) + ". The bank wants a fifth down, " + down + "; you've " + held
                + " on you and " + banked + " in the bank.";
        }
        int lent = price - down;
        int fromAccount = Math.min(Math.min(banked, down), s.cash);
        if (held < down - fromAccount) return "The vault can't pay out your savings just now; bring the " + down + " down in coin, or ask after the bank's round.";
        if (lent > lendable(s) - fromAccount) {
            return "The bank hasn't " + lent + Homes.coins(lent) + " to lend just now (" + lendable(s) + " over what it keeps back). Ask again after its round.";
        }
        long day = level.getDayTime() / 24000L;
        int weekly = payment(lent, LOAN_BP, PLAYER_TERM);
        if (fromAccount > 0) {
            a.balance -= fromAccount;
            s.cash -= fromAccount;
            s.week.withdrawn += fromAccount;
            save(id, a);
        }
        if (down - fromAccount > 0) Market.payOut(p, down - fromAccount);
        s.cash -= lent;
        Ledger.addCoins(id, price);
        Economy.houseSold(id, price);
        h.tenure = Homes.Tenure.PLAYER;
        h.landlord = p.getUUID();
        h.landlordName = p.getName().getString();
        h.price = price;
        h.rent = 0;
        h.toLet = false;
        h.since = day;
        Homes.save(id, h);
        ItemStack key = new ItemStack(Items.TRIPWIRE_HOOK);
        key.set(DataComponents.CUSTOM_NAME, Component.literal("Key to " + where));
        if (!p.getInventory().add(key)) p.drop(key, false);
        Loan l = new Loan(h.anchor.asLong());
        l.player = true;
        l.borrowers.add(p.getUUID());
        l.names = p.getName().getString();
        l.price = price;
        l.down = down;
        l.lent = lent;
        l.principal = lent;
        l.weekly = weekly;
        l.term = PLAYER_TERM;
        l.start = day;
        s.loans.put(l.anchor, l);
        s.week.lent += lent;
        s.week.loans++;
        save(id, l);
        saveHead(id, s);
        Villages.tell(id, day, l.names + " bought " + where + " for " + price + Homes.coins(price) + ": " + down + " down, and " + lent
            + " on a mortgage from the bank");
        return "It's yours: " + where + ", for " + price + Homes.coins(price) + " — " + down + " down and " + lent + " lent. The bank takes " + weekly
            + " a week out of your account here (and the rent your tenants pay you) for " + PLAYER_TERM + " weeks, every seventh day; keep enough in it. "
            + ARREARS_LIMIT + " weeks behind and the house goes back to the village.";
    }

    private static void tellPlayer(ServerLevel level, Loan l, String text) {
        if (!l.player || l.borrowers.isEmpty()) return;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(l.borrowers.get(0));
        if (p != null) p.sendSystemMessage(Component.literal("[Bank] " + text));
    }

    // ------------------------------------------------------------------ in words

    /** Its card's line (FolkTalk.card): what it has at the bank, what it owes on its house, how careful it is; the banker's bank. */
    public static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby() || !open(id)) return "";
        State s = state(id);
        List<String> parts = new ArrayList<>();
        if (f.stationTask() == StationTask.BANK) {
            parts.add("keeps the bank: " + deposits(s) + " on deposit, " + loansOut(s) + " lent on " + s.loans.size()
                + (s.loans.size() == 1 ? " house" : " houses") + ", " + s.cash + " in the vault");
        }
        Account a = s.accounts.get(f.getUUID());
        if (a != null && a.balance > 0) parts.add(a.balance + " saved at the bank" + (a.interestAll > 0 ? " (" + a.interestAll + " of it interest)" : ""));
        Loan l = loanOf(id, f);
        if (l != null) {
            parts.add("owes the bank " + l.owed() + " on the house: " + l.weekly + " a week, " + l.weeksLeft() + " weeks to go"
                + (l.missed > 0 ? "; " + l.missed + (l.missed == 1 ? " payment" : " payments") + " behind" : ""));
        }
        parts.add(thriftWord(thrift(f)));
        return capital(String.join("; ", parts));
    }

    /** Its worth at the bank (Wealth.worth): its savings there, less its share of what is owed on its house. */
    public static int worthOf(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby() || !STATES.containsKey(id) && building(id) == null) return 0;
        State s = state(id);
        Account a = s.accounts.get(f.getUUID());
        int worth = a == null ? 0 : a.balance;
        Loan l = loanOf(id, f);
        if (l != null) worth -= l.owed() / Math.max(1, l.borrowers.size());
        return worth;
    }

    /** Its savings at the bank (the tests, the card). */
    public static int balance(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || !STATES.containsKey(id) && building(id) == null) return 0;
        Account a = state(id).accounts.get(f.getUUID());
        return a == null ? 0 : a.balance;
    }

    /** For its worth's line (Wealth.line): ", 34 at the bank, 28 owed on the house". */
    public static String worthWords(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby() || !open(id)) return "";
        int bal = balance(f);
        Loan l = loanOf(id, f);
        return (bal > 0 ? ", " + bal + " at the bank" : "") + (l != null ? ", less " + l.owed() / Math.max(1, l.borrowers.size()) + " owed on the house" : "");
    }

    /** "How are you doing for money?" — what it says of the bank (Wealth.talk). */
    public static String talkLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby() || !open(id)) return "";
        int bal = balance(f);
        Loan l = loanOf(id, f);
        String out = "";
        if (l != null) {
            out = l.missed > 0 ? " We're behind with the bank — " + l.missed + (l.missed == 1 ? " payment" : " payments") + " missed. I lie awake over it."
                : " We owe the bank " + l.owed() + " on the house: " + l.weekly + " a week, " + l.weeksLeft() + " weeks to go.";
        }
        if (bal > 0) return out + " I've " + bal + " at the bank, earning a little.";
        if (thrift(f) < 0 && f.stationTask() != StationTask.BANK) return out + " The bank? Coin's for spending.";
        return out;
    }

    /** The banker at work, in its own words (FolkTalk.doing). */
    static String doing(VillageFolkEntity f, RandomSource r) {
        UUID id = f.ownerId();
        if (id == null || !open(id)) return "Waiting on a bank to keep. A banker with no bank is just somebody good at sums.";
        State s = state(id);
        Loan behind = null;
        for (Loan l : s.loans.values()) if (l.missed > 0 && (behind == null || l.missed > behind.missed)) behind = l;
        if (behind != null && r.nextBoolean()) {
            return "Writing to " + behind.names + " about their payments. " + ARREARS_LIMIT + " weeks behind and the bank takes the house — I'd hate that.";
        }
        String wants = WANTS.get(id);
        if (wants != null && r.nextBoolean()) return "Keeping the books. I could do with " + wants + ", if the stores have any.";
        return FolkTalk.pick(r, "Keeping the bank's books. " + deposits(s) + " on deposit, " + loansOut(s) + " lent out on " + s.loans.size()
                + (s.loans.size() == 1 ? " house." : " houses."),
            "Counting the vault: " + s.cash + " coins. Every one's somebody's, so every one's counted twice.",
            "Behind the counter. Come to put something by? It earns a little every week.");
    }

    /** The Economy page's line on the bank, or "". */
    public static String economyLine(UUID village) {
        if (!open(village)) return "";
        State s = state(village);
        Week w = s.last != null ? s.last : s.week;
        return "\nThe bank: " + s.cash + " in the vault, " + deposits(s) + " on deposit, " + loansOut(s) + " lent out on " + s.loans.size()
            + (s.loans.size() == 1 ? " house" : " houses") + "; its last week earned " + w.earned + " in interest, paid its savers " + w.paid
            + " and the treasury " + w.treasury + ".";
    }

    /** "312 on deposit with 14 savers, 96 lent on 3 houses, 140 in the vault (94 kept back)". */
    public static String line(UUID village) {
        if (building(village) == null) return "no bank yet";
        State s = state(village);
        if (s.opened < 0) return "the bank stands, not yet open";
        int savers = 0;
        for (Account a : s.accounts.values()) if (a.balance > 0) savers++;
        return deposits(s) + " on deposit with " + savers + (savers == 1 ? " saver" : " savers") + ", " + loansOut(s) + " lent on " + s.loans.size()
            + (s.loans.size() == 1 ? " house" : " houses") + ", " + s.cash + " in the vault (" + reserve(s) + " kept back)";
    }

    // ------------------------------------------------------------------ the books (Annals: the Money and Homes pages)

    /** The bank for the town's books: its figures, the week, each mortgage, the biggest savers, and why it said no to whom. */
    public static CompoundTag report(ServerLevel level, UUID village) {
        CompoundTag out = new CompoundTag();
        Ledger.Building b = building(village);
        out.putBoolean("stands", b != null);
        if (b == null) {
            out.putBoolean("open", false);
            out.putInt("from", FROM);
            return out;
        }
        State s = state(village);
        out.putBoolean("open", s.opened >= 0);
        out.putLong("opened", s.opened);
        VillageFolkEntity banker = banker(village);
        out.putString("banker", banker == null ? "" : banker.displayNameCap());
        out.putString("banker_nature", banker == null ? "" : thriftWord(thrift(banker)));
        out.putInt("cash", s.cash);
        out.putInt("deposits", deposits(s));
        out.putInt("loans_out", loansOut(s));
        out.putInt("reserve", reserve(s));
        out.putInt("lendable", lendable(s));
        out.putInt("equity", equity(s));
        out.putInt("loan_bp", LOAN_BP);
        out.putInt("deposit_bp", DEPOSIT_BP);
        out.putLong("next_round", s.lastRound < 0 ? -1 : s.lastRound + WEEK);
        out.putString("line", line(village));
        out.putString("wants", WANTS.getOrDefault(village, ""));
        String[] names = { "earned", "paid", "treasury", "lent", "repaid", "deposited", "withdrawn", "written", "foreclosed", "loans" };
        for (int pass = 0; pass < 2; pass++) {
            Week w = pass == 0 ? s.week : s.last == null ? new Week() : s.last;
            int[] vals = { w.earned, w.paid, w.treasury, w.lent, w.repaid, w.deposited, w.withdrawn, w.written, w.foreclosed, w.loans };
            for (int i = 0; i < names.length; i++) out.putInt((pass == 0 ? "week_" : "last_") + names[i], vals[i]);
        }
        Villages.Village v = Villages.get(village);
        ListTag loans = new ListTag();
        for (Loan l : s.loans.values()) {
            CompoundTag r = new CompoundTag();
            Homes.Home h = Homes.homes(village).get(l.anchor);
            r.putLong("anchor", l.anchor);
            r.putString("household", l.names);
            r.putString("address", h == null || v == null ? "" : Homes.address(village, v, h));
            r.putBoolean("player", l.player);
            r.putInt("price", l.price);
            r.putInt("down", l.down);
            r.putInt("lent", l.lent);
            r.putInt("owed", l.owed());
            r.putInt("principal", l.principal);
            r.putInt("interest", l.interest);
            r.putInt("weekly", l.weekly);
            r.putInt("term", l.term);
            r.putInt("weeks_paid", l.weeksPaid);
            r.putInt("weeks_left", l.weeksLeft());
            r.putInt("missed", l.missed);
            r.putInt("arrears", l.arrears);
            r.putInt("paid_all", l.paidAll);
            r.putLong("start", l.start);
            r.putString("status", l.missed >= ARREARS_LIMIT - 1 ? "last warning" : l.missed > 0 ? "behind" : "paying");
            loans.add(r);
        }
        out.put("loans", loans);
        List<Account> savers = new ArrayList<>(s.accounts.values());
        savers.removeIf(a -> a.balance <= 0);
        savers.sort(Comparator.comparingInt((Account a) -> -a.balance));
        out.putInt("savers", savers.size());
        ListTag top = new ListTag();
        for (int i = 0; i < Math.min(8, savers.size()); i++) {
            CompoundTag r = new CompoundTag();
            r.putString("name", savers.get(i).name);
            r.putInt("balance", savers.get(i).balance);
            r.putInt("interest", savers.get(i).interestAll);
            r.putBoolean("player", savers.get(i).player);
            top.add(r);
        }
        out.put("top", top);
        ListTag refused = new ListTag();
        Map<Long, Homes.Home> homes = Homes.homes(village);
        for (Map.Entry<Long, String> e : WHY.entrySet()) {
            if (!homes.containsKey(e.getKey())) continue;
            CompoundTag r = new CompoundTag();
            r.putLong("anchor", e.getKey());
            r.putString("why", e.getValue());
            refused.add(r);
        }
        out.put("refused", refused);
        return out;
    }

    // ------------------------------------------------------------------ /village bank

    /** /village bank [deposit N | withdraw N | mortgage | repay N | week | showcase]. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("bank")
            .executes(Bank::cmdReport)
            .then(Commands.literal("deposit").then(Commands.argument("coins", IntegerArgumentType.integer(1, 100000))
                .executes(ctx -> cmdPlayer(ctx, "deposit " + IntegerArgumentType.getInteger(ctx, "coins")))))
            .then(Commands.literal("withdraw").then(Commands.argument("coins", IntegerArgumentType.integer(1, 100000))
                .executes(ctx -> cmdPlayer(ctx, "withdraw " + IntegerArgumentType.getInteger(ctx, "coins")))))
            .then(Commands.literal("mortgage").executes(ctx -> cmdPlayer(ctx, "mortgage, buy this house")))
            .then(Commands.literal("repay").then(Commands.argument("coins", IntegerArgumentType.integer(1, 100000))
                .executes(ctx -> cmdPlayer(ctx, "repay " + IntegerArgumentType.getInteger(ctx, "coins")))))
            // The week's round now: payments, interest, the treasury's share (ops; for tests and the screenshots).
            .then(Commands.literal("week").requires(src -> src.hasPermission(2)).executes(Bank::cmdWeek))
            // A bank put up where you look, opened, its banker at the counter, the ledger and the bars in (ops; the screenshots).
            .then(Commands.literal("showcase").requires(src -> src.hasPermission(2)).executes(Bank::cmdShowcase));
    }

    @Nullable
    private static Villages.Village villageOf(CommandContext<CommandSourceStack> ctx) {
        return Villages.nearest(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE);
    }

    private static int cmdReport(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = villageOf(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("There's no village here."));
            return 0;
        }
        UUID id = v.id();
        List<String> lines = new ArrayList<>();
        lines.add(Villages.name(id) + "'s bank: " + line(id) + ".");
        if (building(id) == null) {
            lines.add("A town of " + FROM + " in the Iron Age builds one. " + Villages.headcount(id) + " live here, in the " + Villages.ageOf(id).label + ".");
        } else {
            State s = state(id);
            VillageFolkEntity banker = banker(id);
            lines.add("Banker: " + (banker == null ? "none yet" : banker.displayNameCap() + " (" + thriftWord(thrift(banker)) + ")")
                + ". Lending: " + lendable(s) + " (keeps " + RESERVE + " in the hundred of deposits back); worth " + equity(s) + " to itself.");
            Week w = s.last != null ? s.last : s.week;
            lines.add("Last week: earned " + w.earned + ", paid savers " + w.paid + ", the treasury " + w.treasury + ", lent " + w.lent + ", repaid "
                + w.repaid + (w.written > 0 ? ", wrote off " + w.written : "") + ". This week so far: earned " + s.week.earned + ", deposited "
                + s.week.deposited + ", withdrawn " + s.week.withdrawn + ". Next round day " + (s.lastRound + WEEK) + ".");
            for (Loan l : s.loans.values()) {
                lines.add("  " + l.names + ", " + where(id, l) + ": owes " + l.owed() + " of " + l.lent + " lent, " + l.weekly + " a week, "
                    + l.weeksLeft() + " weeks to go" + (l.missed > 0 ? ", " + l.missed + " behind (" + l.arrears + ")" : ""));
            }
            List<Account> savers = new ArrayList<>(s.accounts.values());
            savers.removeIf(a -> a.balance <= 0);
            savers.sort(Comparator.comparingInt((Account a) -> -a.balance));
            StringBuilder sb = new StringBuilder("Savers: ");
            for (int i = 0; i < Math.min(10, savers.size()); i++) sb.append(i == 0 ? "" : ", ").append(savers.get(i).name).append(' ').append(savers.get(i).balance);
            lines.add(savers.isEmpty() ? "No savers yet." : sb.toString());
            if (ctx.getSource().getEntity() instanceof ServerPlayer p) lines.add(accountLine(id, p.getUUID()));
        }
        for (String l : lines) ctx.getSource().sendSuccess(() -> Component.literal(l), false);
        return 1;
    }

    private static int cmdPlayer(CommandContext<CommandSourceStack> ctx, String what) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) {
            ctx.getSource().sendFailure(Component.literal("Only a player keeps an account."));
            return 0;
        }
        Villages.Village v = villageOf(ctx);
        if (v == null || !open(v.id())) {
            ctx.getSource().sendFailure(Component.literal(v == null ? "There's no village here." : Villages.name(v.id()) + " has no bank open yet."));
            return 0;
        }
        String said = handle(ctx.getSource().getLevel(), v.id(), p, what);
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return 1;
    }

    private static int cmdWeek(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = villageOf(ctx);
        if (v == null || !open(v.id())) {
            ctx.getSource().sendFailure(Component.literal("No bank open here."));
            return 0;
        }
        weekForTests(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal("The bank's round: " + line(v.id()) + "."), false);
        return 1;
    }

    /** Put a bank up in front of you (stamped, like the showcase's buildings), open it, and set its banker at the counter. */
    private static int cmdShowcase(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = villageOf(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("There's no village here."));
            return 0;
        }
        UUID id = v.id();
        Ledger.Building b = building(id);
        if (b == null) {
            Direction facing = ctx.getSource().getEntity() != null ? ctx.getSource().getEntity().getDirection() : Direction.NORTH;
            BlockPos from = BlockPos.containing(ctx.getSource().getPosition());
            BlockPos spot = from.relative(facing, 10);
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spot.getX(), spot.getZ());
            BlockPos at = new BlockPos(spot.getX(), y, spot.getZ());
            BuildGoal.stamp(level, "bank", at, facing, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
            Ledger.built(id, "bank", at, facing);
            b = building(id);
        }
        if (b == null) return 0;
        long day = level.getDayTime() / 24000L;
        State s = state(id);
        if (s.opened < 0) openBank(v, b, s, day);
        appoint(level, v, day);
        VillageFolkEntity banker = banker(id);
        if (banker != null) banker.teleportTo(b.anchor().getX() + 0.5, b.anchor().getY(), b.anchor().getZ() + 0.5);
        // Staged for the camera: the bars and the ledger set straight in (a banker makes them of the stores' iron and paper).
        for (BlockPos p : barSpots(b)) {
            if (level.getBlockState(p).canBeReplaced()) level.setBlock(p, Block.updateFromNeighbourShapes(Blocks.IRON_BARS.defaultBlockState(), level, p), 3);
        }
        BlockPos lec = lectern(b);
        if (lec != null && level.getBlockState(lec).getBlock() instanceof LecternBlock && !level.getBlockState(lec).getValue(LecternBlock.HAS_BOOK)) {
            LecternBlock.tryPlaceBook(banker, level, lec, level.getBlockState(lec), book(id, banker));
        }
        Ledger.Building at = b;
        ctx.getSource().sendSuccess(() -> Component.literal("The bank stands at " + at.anchor().toShortString() + ", facing " + at.facing()
            + ": " + line(id) + "."), false);
        return 1;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: open the bank now (it must stand: Ledger.built) and appoint its banker. */
    public static boolean openForTests(ServerLevel level, Villages.Village v) {
        TICKED.remove(v.id());
        tick(level, v);
        return open(v.id());
    }

    /** Tests: the morning's business now, before the rent and after it (no round unless it is due). */
    public static void morningForTests(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        beforeRent(level, v, day);
        morning(level, v, day);
    }

    /** Tests (and /village bank week): the week's round now. */
    public static void weekForTests(ServerLevel level, Villages.Village v) {
        if (!open(v.id())) return;
        State s = state(v.id());
        round(level, v, s, level.getDayTime() / 24000L);
        saveHead(v.id(), s);
    }

    /** Tests: a folk's deposit, as the morning would make it (from its purse). */
    public static int depositForTests(VillageFolkEntity f, int n) {
        UUID id = f.ownerId();
        return id == null || !open(id) ? 0 : deposit(id, state(id), f, n);
    }

    /** Tests: a folk draws on its account. */
    public static int withdrawForTests(VillageFolkEntity f, int n) {
        UUID id = f.ownerId();
        return id == null || !open(id) ? 0 : withdraw(id, state(id), f, n);
    }

    /** Tests: a player at the bank, as if it had said this to the banker. */
    public static String playerForTests(ServerLevel level, UUID village, Player p, String text) {
        return open(village) ? handle(level, village, p, text.toLowerCase(Locale.ROOT)) : "";
    }

    /** Tests: {owed, principal, interest, weekly, term, missed, arrears, lent, weeks paid} of the mortgage on this house, or null. */
    @Nullable
    public static int[] loanForTests(UUID village, BlockPos anchor) {
        Loan l = state(village).loans.get(anchor.asLong());
        return l == null ? null : new int[]{ l.owed(), l.principal, l.interest, l.weekly, l.term, l.missed, l.arrears, l.lent, l.weeksPaid };
    }

    /** Tests: {cash, deposits, loans out, reserve, lendable, equity}. */
    public static int[] figuresForTests(UUID village) {
        State s = state(village);
        return new int[]{ s.cash, deposits(s), loansOut(s), reserve(s), lendable(s), equity(s) };
    }

    /** Tests: last week's books {earned, paid, treasury, lent, repaid, deposited, withdrawn, written, foreclosed}, or zeros. */
    public static int[] lastWeekForTests(UUID village) {
        Week w = state(village).last;
        if (w == null) return new int[9];
        return new int[]{ w.earned, w.paid, w.treasury, w.lent, w.repaid, w.deposited, w.withdrawn, w.written, w.foreclosed };
    }

    /** Tests: a player's balance at the bank. */
    public static int balanceForTests(UUID village, UUID who) {
        Account a = state(village).accounts.get(who);
        return a == null ? 0 : a.balance;
    }

    /** Tests: what this folk keeps in its purse for the week (the rest it may put in the bank). */
    public static int keepForTests(VillageFolkEntity f) {
        UUID id = f.ownerId();
        return id == null ? Homes.LIVE_ON : keep(id, f, state(id));
    }

    /** Tests: where the vault's bars go, and the lectern, in the village's bank (empty / null without one). */
    public static List<BlockPos> barSpotsForTests(UUID village) {
        Ledger.Building b = building(village);
        return b == null ? List.of() : barSpots(b);
    }

    @Nullable
    public static BlockPos lecternForTests(UUID village) {
        Ledger.Building b = building(village);
        return b == null ? null : lectern(b);
    }

    /** Tests: why the bank last said no to the household in this house. */
    public static String whyForTests(BlockPos anchor) {
        return WHY.getOrDefault(anchor.asLong(), "");
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
