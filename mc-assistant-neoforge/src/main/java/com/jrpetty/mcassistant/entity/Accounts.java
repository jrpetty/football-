package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [econ-1a] Who pays. Every time goods come out of a town's stores or go into them, somebody's account is behind it: the
 * business whose hand is at work, the household whose folk is fetching its supper, the town's own departments (its works,
 * its watch, its army, its market, its mint), or, for a while after a town is founded, nobody at all (the founders'
 * commons). This class knows whose account it is at that moment, and moves the money, to the penny.
 *
 * <p><b>Whose account.</b> Whatever is doing the work says so, by opening a scope round it: a maker's craft turn its
 * business's, a builder's lead the town's works, the morning's jobs each its department, a meal the eater's household.
 * Nested, the innermost wins; a scope always closes, however the work ends. Where nothing has said, the folk whose tick
 * it is answers for itself: at its trade, the business it works for (or the department its trade serves: the watch, the
 * market, the works); for its food, clothes and things for the home, its household; a child, its parents. Where there is
 * neither (something outside any folk's tick and any town's rounds), the take is a straggler: the treasury pays, so not a
 * penny goes astray, and the place is written down, so the next round of work can give it a scope of its own.
 *
 * <p><b>The money.</b> Pennies, a hundred to the coin. A business pays out of its till (its owners' savings, then its trade
 * credit with the town, then the bank, when the till is short: Businesses.raise); a folk out of its purse, its odd pennies
 * kept on account here as change; a department out of the treasury, its spending booked in the department's own book. Soft,
 * for now: nothing is ever refused for want of money, so no folk stands at the stores unable to do its work. A folk short
 * of the price runs up what it owes the market, settled first out of its next wages; a poor folk's food and fuel are paid
 * by the town's relief (the poor box first); a department short of coin gives the market an IOU, paid first out of the
 * next taxes. Coin is never made here or lost: what one account pays, another has.
 *
 * <p>Kept with the world (SavedData), a compound for each town: the change, what is owed, the IOUs, the departments' books,
 * the stragglers, and the market's own books (MarketMaker) and the privatisation programme's (Privatisation).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Accounts {

    private Accounts() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The kinds of account. */
    public enum Kind { FIRM, FOLK, PLAYER, DEPT, COMMONS, UNATTRIBUTED, FREE }

    /** The town's departments: what it keeps for everybody, paid for out of the treasury. */
    public enum Dept {
        WORKS("public works"), WATCH("the watch"), ARMY("the army"), SCHOOL("the school"), RELIEF("poor relief"), HALL("the hall"),
        HARBOUR("the harbour"), TRADE("the town's trade"), MARKET("the market"), MINT("the mint"), UPKEEP_ANIMALS("the town's animals");

        public final String words;

        Dept(String words) {
            this.words = words;
        }
    }

    /** Why the goods are wanted: work, a folk's own life, a building, kit, food, relief, an emergency, a gift, a return. */
    public enum Purpose { WORK, PERSONAL, BUILD, KIT, FOOD, RELIEF, REQUISITION, GIFT, RETURN, FREE }

    /**
     * One account: a business (by its id), a folk (its purse: its household's, for a child its parents'), a player, a
     * department (by name), the founders' commons, nobody (a straggler), or free (already paid, a gift, a theft).
     */
    public record Account(Kind kind, @Nullable UUID who, String key) {
        public static Account firm(Business b) {
            return new Account(Kind.FIRM, null, b.id);
        }

        public static Account folk(VillageFolkEntity f) {
            return new Account(Kind.FOLK, f.getUUID(), f.displayNameCap());
        }

        public static Account dept(Dept d) {
            return new Account(Kind.DEPT, null, d.name());
        }

        public static final Account COMMONS = new Account(Kind.COMMONS, null, "the founders' commons");
        public static final Account NOBODY = new Account(Kind.UNATTRIBUTED, null, "nobody");

        public static Account free(String why) {
            return new Account(Kind.FREE, null, why);
        }

        @Nullable
        public Dept deptOf() {
            if (kind != Kind.DEPT) return null;
            try {
                return Dept.valueOf(key);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /** The market's own (the storehouse, its storekeepers and couriers): it does not buy of itself. */
        public boolean market() {
            return kind == Kind.DEPT && Dept.MARKET.name().equals(key) || kind == Kind.FIRM && "storehouse".equals(key);
        }

        /** A key the books keep it under: "firm:cafe", "folk:<uuid>", "dept:WORKS". */
        public String id() {
            return switch (kind) {
                case FIRM -> "firm:" + key;
                case FOLK, PLAYER -> kind.name().toLowerCase(Locale.ROOT) + ":" + who;
                case DEPT -> "dept:" + key;
                default -> kind.name().toLowerCase(Locale.ROOT);
            };
        }

        /** In words, for the books. */
        public String words(UUID village) {
            return switch (kind) {
                case FIRM -> {
                    Business b = Businesses.of(village, key);
                    yield b == null ? key : b.name;
                }
                case FOLK, PLAYER -> key;
                case DEPT -> {
                    Dept d = deptOf();
                    yield d == null ? key : d.words;
                }
                case COMMONS -> "the founders' commons";
                case UNATTRIBUTED -> "nobody (a straggler)";
                case FREE -> "free (" + key + ")";
            };
        }
    }

    // ------------------------------------------------------------------ the scope stack

    /** One scope: whose account and why, or the folk whose tick it is (its account worked out when a take asks). */
    static final class Frame {
        @Nullable final Account account;
        final Purpose purpose;
        @Nullable final VillageFolkEntity actor;
        /** The server's own round of the towns' systems (a tick event), not any one system's own scope. */
        final boolean system;

        Frame(@Nullable Account account, Purpose purpose, @Nullable VillageFolkEntity actor) {
            this(account, purpose, actor, false);
        }

        Frame(@Nullable Account account, Purpose purpose, @Nullable VillageFolkEntity actor, boolean system) {
            this.account = account;
            this.purpose = purpose;
            this.actor = actor;
            this.system = system;
        }
    }

    /** The server's one thread runs every tick, so one stack does; nested scopes push and pop in order. */
    private static final ArrayDeque<Frame> STACK = new ArrayDeque<>();

    /** A scope, closed in a try-with-resources: back to the depth it was opened at, whatever was left open inside it. */
    public static final class Scope implements AutoCloseable {
        private final int depth;

        Scope(int depth) {
            this.depth = depth;
        }

        @Override
        public void close() {
            leave(depth);
        }
    }

    private static Scope push(Frame f) {
        int d = STACK.size();
        STACK.push(f);
        return new Scope(d);
    }

    /** Charged to this account, for this purpose, till the scope closes. */
    public static Scope as(Account a, Purpose p) {
        return push(new Frame(a, p, null));
    }

    /** Charged to this department. */
    public static Scope as(Dept d, Purpose p) {
        return as(Account.dept(d), p);
    }

    /** Charged to nobody: already paid for (a counter's sale), a gift, a theft, a business's own stock. */
    public static Scope free(String why) {
        return push(new Frame(Account.free(why), Purpose.FREE, null));
    }

    /** A folk's own tick: its account worked out from what it is doing when it takes something. Returns the depth to leave. */
    public static int enter(VillageFolkEntity f) {
        int d = STACK.size();
        STACK.push(new Frame(null, Purpose.WORK, f));
        return d;
    }

    /** Back to this depth (closing every scope opened since). */
    public static void leave(int depth) {
        while (STACK.size() > depth) STACK.pop();
    }

    /** The folk whose tick this is (the innermost), or null. */
    @Nullable
    public static VillageFolkEntity actor() {
        for (Frame f : STACK) if (f.actor != null) return f.actor;
        return null;
    }

    /** What a take or a give is booked to: the account and why; {@code system}: by the towns' own round, not a scope of its own. */
    public record Resolved(Account account, Purpose purpose, boolean system) {
        public Resolved(Account account, Purpose purpose) {
            this(account, purpose, false);
        }
    }

    /**
     * Whose account it is now, for this thing in this town: the innermost scope's account, or the folk whose tick it is
     * (Accounts' class comment), or nobody.
     */
    public static Resolved resolve(UUID village, ItemStack what) {
        Frame top = STACK.peek();
        if (top == null) return new Resolved(Account.NOBODY, Purpose.WORK);
        if (top.account != null) return new Resolved(top.account, top.purpose, top.system);
        if (top.actor != null) return forFolk(village, top.actor, what);
        return new Resolved(Account.NOBODY, Purpose.WORK);
    }

    /** The trades that work with food, drink and the like at their trade (the cook's beef is the café's, not its supper). */
    static boolean worksWithGoods(StationTask t) {
        return t == StationTask.COOK || t == StationTask.BREW || t == StationTask.SHOP || t == StationTask.BEEKEEP || t == StationTask.TAILOR;
    }

    /** A folk's own account for what it takes, as the class comment has it. */
    static Resolved forFolk(UUID village, VillageFolkEntity f, ItemStack what) {
        if (f.isBaby()) {
            VillageFolkEntity parent = parentOf(village, f);
            return parent != null ? new Resolved(Account.folk(parent), Purpose.FOOD) : new Resolved(Account.dept(Dept.RELIEF), Purpose.RELIEF);
        }
        Goods.Category c = what.isEmpty() ? Goods.Category.MATERIALS : Goods.of(what);
        boolean personal = c == Goods.Category.FOOD || c == Goods.Category.DRINK || c == Goods.Category.CLOTHES
            || c == Goods.Category.COMFORTS || c == Goods.Category.HOUSEHOLD;
        StationTask t = f.stationTask();
        // Holding the town's building lead: what it draws for the building (beds and chests among it) is the town's works'.
        if (c != Goods.Category.FOOD && c != Goods.Category.DRINK && Villages.holdsTheLead(village, f.getUUID(), f.level().getGameTime())) {
            return new Resolved(Account.dept(Dept.WORKS), Purpose.BUILD);
        }
        if (personal && !worksWithGoods(t)) {
            return new Resolved(Account.folk(f), c == Goods.Category.FOOD || c == Goods.Category.DRINK ? Purpose.FOOD : Purpose.PERSONAL);
        }
        if (Armies.fighter(f.getUUID()) != null) return new Resolved(Account.dept(Dept.ARMY), Purpose.WORK);
        Business b = Businesses.employer(village, f);
        if (b != null && !b.mirror()) return new Resolved(Account.firm(b), Purpose.WORK);
        Dept d = switch (t) {
            case NONE -> null;
            case GUARD, GOLEMS -> Dept.WATCH;
            case STORE, HAUL -> Dept.MARKET;
            case BANK -> Dept.HALL;
            default -> Dept.WORKS;
        };
        if (d == null) return new Resolved(Account.folk(f), Purpose.PERSONAL);
        return new Resolved(Account.dept(d), Purpose.WORK);
    }

    /** A child's parent who can pay: a grown one, alive, in town. */
    @Nullable
    static VillageFolkEntity parentOf(UUID village, VillageFolkEntity child) {
        for (UUID p : child.parentIds()) {
            VillageFolkEntity f = Homes.loaded(village, p);
            if (f != null && f.isAlive() && !f.isBaby()) return f;
        }
        return null;
    }

    /*
     * The towns' own systems that run off the server's tick (their levelling, their docks, their plaques, the culture's
     * works, a hundred others) rather than in any folk's tick: what they take is the town's, on its works account, unless
     * the system opens a scope of its own. The round opens first and closes last on each of the tick's two events; the
     * world's own tick between them (every entity, every block) runs with nothing open, so a take there that no folk's
     * tick answers for is a true straggler. Every scope must have closed by the round's end: one left open would charge
     * the wrong account all next tick.
     */

    private static void openRound() {
        if (!STACK.isEmpty()) {
            LOG.warn("[econ-1a] {} account scope(s) left open between the server's rounds; cleared", STACK.size());
            STACK.clear();
        }
        STACK.push(new Frame(Account.dept(Dept.WORKS), Purpose.WORK, null, true));
    }

    private static void closeRound() {
        if (STACK.size() > 1) LOG.warn("[econ-1a] {} account scope(s) left open at the end of a tick's round; cleared", STACK.size() - 1);
        STACK.clear();
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void onTickPreFirst(ServerTickEvent.Pre event) {
        if (CoinCensus.probing()) CoinCensus.probe("the start of tick " + event.getServer().getTickCount() + " (after the last tick's world)");
        openRound();
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void onTickPreLast(ServerTickEvent.Pre event) {
        closeRound();
        if (CoinCensus.probing()) CoinCensus.probe("the tick's opening events");
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void onTickPostFirst(ServerTickEvent.Post event) {
        if (CoinCensus.probing()) CoinCensus.probe("the world's tick (not a folk's)");
        openRound();
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void onTickPostLast(ServerTickEvent.Post event) {
        closeRound();
        if (CoinCensus.probing()) CoinCensus.probe("the tick's closing events (the towns' systems)");
    }

    // ------------------------------------------------------------------ the books, kept with the world

    static final class Books extends SavedData {
        CompoundTag root = new CompoundTag();

        static Books load(CompoundTag tag, HolderLookup.Provider p) {
            Books b = new Books();
            b.root = tag.getCompound("root");
            return b;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider p) {
            tag.put("root", root.copy());
            return tag;
        }
    }

    @Nullable private static Books loose;

    static Books books() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new Books();
            return loose;
        }
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Books::new, Books::load, null), "mc_assistant_accounts");
    }

    /** A town's compound (made the first time it is asked for). */
    static CompoundTag town(UUID village) {
        return sub(sub(books().root, "towns"), village.toString());
    }

    static void dirty() {
        books().setDirty();
    }

    static CompoundTag sub(CompoundTag t, String key) {
        if (!t.contains(key, Tag.TAG_COMPOUND)) t.put(key, new CompoundTag());
        return t.getCompound(key);
    }

    static long add(CompoundTag t, String key, long n) {
        long v = t.getLong(key) + n;
        t.putLong(key, v);
        return v;
    }

    public static void forget(UUID village) {
        if (sub(books().root, "towns").contains(village.toString())) {
            sub(books().root, "towns").remove(village.toString());
            dirty();
        }
    }

    // ------------------------------------------------------------------ paying, to the penny

    /** Today's day number. */
    static long today(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    /** Today's day number in the overworld (0 with no server). */
    static long dayNow() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? 0 : server.overworld().getDayTime() / 24000L;
    }

    /**
     * So many pennies out of this account, toward the market (the caller puts what comes back in the market's cash).
     * Never refused: a business short of it raises it (Businesses.raise) and failing that the town lends it as trade credit;
     * a folk short of it owes the market the rest (its food and fuel the relief pays); a department short of coin writes the
     * market an IOU. Returns the pennies actually paid now; what is owed is booked as owed.
     */
    public static long debit(ServerLevel level, UUID village, Account a, long p, Purpose why, String what) {
        if (p <= 0) return 0;
        long day = today(level);
        switch (a.kind()) {
            case FIRM -> {
                Business b = Businesses.of(village, a.key());
                if (b == null || b.mirror()) return deptPays(level, village, Dept.WORKS, p, why, what);
                if (b.town()) {
                    // The town's own business: its coin is the treasury's; the cost is the business's in its books.
                    long got = Businesses.fromTreasury(village, p);
                    if (got < p) debt(village, "the town's " + b.name.toLowerCase(Locale.ROOT), p - got, day, what);
                    return got;
                }
                if (b.till < p) Businesses.raise(level, village, b, p - b.till, day);
                long paid = Math.min(p, Math.max(0, b.till));
                b.till -= paid;
                if (paid < p) {
                    long short_ = p - paid;
                    // The town lends it what it can (its trade credit), the rest it owes the market.
                    long lent = Businesses.fromTreasury(village, short_);
                    b.account -= lent;
                    paid += lent;
                    if (paid < p) owe(village, a, p - paid, what);
                    b.note(day, "short of " + Businesses.pence(short_) + " for " + what + ": " + (lent > 0 ? Businesses.pence(lent) + " on the town's credit" : "owed"));
                }
                Businesses.dirty();
                return paid;
            }
            case FOLK -> {
                VillageFolkEntity f = a.who() == null ? null : Homes.loaded(village, a.who());
                if (f == null) return deptPays(level, village, Dept.RELIEF, p, Purpose.RELIEF, what);
                return folkPays(level, village, f, p, why, what);
            }
            case DEPT -> {
                Dept d = a.deptOf();
                return deptPays(level, village, d == null ? Dept.HALL : d, p, why, what);
            }
            case UNATTRIBUTED -> {
                return deptPays(level, village, Dept.HALL, p, why, what);
            }
            default -> {
                return 0;
            }
        }
    }

    /**
     * So many pennies into this account from the market (the caller has taken them out of the market's cash): a business's
     * till (the town's own, the treasury), a folk's purse (whole coins, the odd pennies on account), a department's
     * treasury. The commons, a straggler and the free take nothing: the treasury has it.
     */
    public static void credit(ServerLevel level, UUID village, Account a, long p, String what) {
        if (p <= 0) return;
        switch (a.kind()) {
            case FIRM -> {
                Business b = Businesses.of(village, a.key());
                if (b == null || b.town() || b.mirror()) {
                    Businesses.toTreasury(village, p);
                    if (b == null) deptBook(village, Dept.WORKS, 0, p);
                } else {
                    b.till += p;
                    Businesses.dirty();
                }
            }
            case FOLK -> {
                VillageFolkEntity f = a.who() == null ? null : Homes.loaded(village, a.who());
                if (f == null) {
                    Businesses.toTreasury(village, p);
                    return;
                }
                CompoundTag ch = sub(town(village), "change");
                long c = ch.getLong(f.getUUID().toString()) + p;
                int coins = (int) Math.min(Integer.MAX_VALUE, c / 100);
                if (coins > 0) f.earn(coins);
                c -= coins * 100L;
                if (c == 0) ch.remove(f.getUUID().toString()); else ch.putLong(f.getUUID().toString(), c);
                dirty();
            }
            case DEPT -> {
                Dept d = a.deptOf();
                Businesses.toTreasury(village, p);
                deptBook(village, d == null ? Dept.HALL : d, 0, p);
            }
            default -> Businesses.toTreasury(village, p);
        }
    }

    /** What a department pays (the treasury), its book kept; what the treasury cannot find, an IOU to the market. */
    static long deptPays(ServerLevel level, UUID village, Dept d, long p, Purpose why, String what) {
        long got = Businesses.fromTreasury(village, p);
        deptBook(village, d, p, 0);
        if (got < p) debt(village, d.words, p - got, today(level), what + (why == Purpose.REQUISITION ? " (requisitioned)" : ""));
        return got;
    }

    /**
     * A folk pays: its change first, then whole coins out of its purse (the odd pennies kept on account). Short: its food
     * and fuel paid by the relief (the poor box, then the treasury: two meals and a coal a day), the rest owed to the market.
     */
    static long folkPays(ServerLevel level, UUID village, VillageFolkEntity f, long p, Purpose why, String what) {
        CompoundTag ch = sub(town(village), "change");
        String k = f.getUUID().toString();
        long c = ch.getLong(k);
        long paid;
        if (c >= p) {
            c -= p;
            paid = p;
        } else {
            long need = p - c;
            int coins = (int) Math.min(Integer.MAX_VALUE, (need + 99) / 100);
            int can = Math.min(coins, f.purse());
            long in = can > 0 && f.spend(can) ? can * 100L : 0;
            if (in >= need) {
                c = in - need;
                paid = p;
            } else {
                paid = c + in;
                c = 0;
            }
        }
        if (c == 0) ch.remove(k); else ch.putLong(k, c);
        if (paid < p) {
            long short_ = p - paid;
            if ((why == Purpose.FOOD || why == Purpose.RELIEF || fuel(what)) && reliefRoom(level, village, f)) {
                paid += relief(level, village, f, short_, what);
            }
            if (paid < p) owe(village, Account.folk(f), p - paid, what);
        }
        dirty();
        return paid;
    }

    static boolean fuel(String what) {
        return what.contains("coal") || what.contains("charcoal");
    }

    /** Has the relief more to give this folk today (two meals and a coal)? */
    static boolean reliefRoom(ServerLevel level, UUID village, VillageFolkEntity f) {
        CompoundTag r = sub(sub(town(village), "relief"), f.getUUID().toString());
        long day = today(level);
        if (r.getLong("day") != day) {
            r.putLong("day", day);
            r.putInt("n", 0);
        }
        return r.getInt("n") < 3;
    }

    /** The relief pays for a poor folk's food or fuel: the poor box first, then the treasury. Returns the pennies paid. */
    static long relief(ServerLevel level, UUID village, VillageFolkEntity f, long p, String what) {
        CompoundTag r = sub(sub(town(village), "relief"), f.getUUID().toString());
        r.putInt("n", r.getInt("n") + 1);
        long got = 0;
        int box = PoorBox.coins(village);
        int coins = (int) Math.min(box, (p + 99) / 100);
        if (coins > 0) {
            PoorBox.coins(village, box - coins);
            long in = coins * 100L;
            got = Math.min(p, in);
            if (in > p) Businesses.toTreasury(village, in - p);          // the odd pennies of the box's coin, the town's
        }
        if (got < p) got += Businesses.fromTreasury(village, p - got);
        deptBook(village, Dept.RELIEF, got, 0);
        add(sub(town(village), "reliefAll"), "pence", got);
        dirty();
        return got;
    }

    /** A department's book: spent and received, today and all told. */
    static void deptBook(UUID village, Dept d, long spent, long in) {
        CompoundTag b = sub(sub(town(village), "depts"), d.name());
        long day = dayNow();
        if (b.getLong("day") != day) {
            b.putLong("day", day);
            b.putLong("spentToday", 0);
            b.putLong("inToday", 0);
        }
        add(b, "spentToday", spent);
        add(b, "inToday", in);
        add(b, "spent", spent);
        add(b, "in", in);
        dirty();
    }

    /** A department's spending today and all told, and its receipts: {spentToday, inToday, spent, in}. */
    public static long[] deptBook(UUID village, Dept d) {
        CompoundTag b = sub(sub(town(village), "depts"), d.name());
        return new long[]{ b.getLong("spentToday"), b.getLong("inToday"), b.getLong("spent"), b.getLong("in") };
    }

    // ------------------------------------------------------------------ owed, change and IOUs

    /** What an account owes the market (a folk's or a business's overdraft), in pennies. */
    static void owe(UUID village, Account a, long p, String what) {
        if (p <= 0) return;
        add(sub(town(village), "owed"), a.id(), p);
        add(sub(town(village), "overdrafts"), "count", 1);
        dirty();
    }

    public static long owed(UUID village, Account a) {
        return sub(town(village), "owed").getLong(a.id());
    }

    /** All that is owed the market by folk and businesses, pennies. */
    public static long owedAll(UUID village) {
        long n = 0;
        CompoundTag o = sub(town(village), "owed");
        for (String k : o.getAllKeys()) n += o.getLong(k);
        return n;
    }

    /** The change the market holds for folk (their odd pennies), all told: coin, counted by the census. */
    public static long changeHeld(UUID village) {
        long n = 0;
        CompoundTag ch = sub(town(village), "change");
        for (String k : ch.getAllKeys()) n += ch.getLong(k);
        return n;
    }

    /** Tests: a folk's change on account, pennies. */
    public static long changeForTests(UUID village, VillageFolkEntity f) {
        return sub(town(village), "change").getLong(f.getUUID().toString());
    }

    /**
     * Payday (Market.payWages, Businesses.paysHand): what a folk owes the market paid first out of the wage it has just had,
     * into the market's cash.
     */
    public static void payday(ServerLevel level, VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return;
        Account a = Account.folk(f);
        CompoundTag o = sub(town(village), "owed");
        long due = o.getLong(a.id());
        if (due <= 0) return;
        int coins = (int) Math.min(f.purse(), (due + 99) / 100);
        if (coins <= 0 || !f.spend(coins)) return;
        long in = coins * 100L;
        long paid = Math.min(due, in);
        if (in > paid) add(sub(town(village), "change"), f.getUUID().toString(), in - paid);
        if (due - paid <= 0) o.remove(a.id()); else o.putLong(a.id(), due - paid);
        MarketMaker.cashIn(village, paid);
        dirty();
    }

    /** A business's overdraft at the market paid out of its till over its float (its morning). */
    static void settleFirm(ServerLevel level, UUID village, Business b) {
        Account a = Account.firm(b);
        CompoundTag o = sub(town(village), "owed");
        long due = o.getLong(a.id());
        if (due <= 0 || b.town()) return;
        long spare = b.till - 1000;
        long pay = Math.min(due, Math.max(0, spare));
        if (pay <= 0) return;
        b.till -= pay;
        if (due - pay <= 0) o.remove(a.id()); else o.putLong(a.id(), due - pay);
        MarketMaker.cashIn(village, pay);
        Businesses.dirty();
        dirty();
    }

    /** An IOU from the town to its market: a department short of coin for what it bought (or requisitioned). */
    static void debt(UUID village, String who, long p, long day, String what) {
        if (p <= 0) return;
        ListTag l = list(town(village), "debts");
        CompoundTag t = new CompoundTag();
        t.putString("who", who);
        t.putLong("p", p);
        t.putLong("day", day);
        t.putString("what", what);
        l.add(t);
        while (l.size() > 200) {
            // Very many small ones: the oldest folded into the next.
            CompoundTag a = l.getCompound(0), b = l.getCompound(1);
            b.putLong("p", b.getLong("p") + a.getLong("p"));
            l.remove(0);
        }
        dirty();
    }

    static ListTag list(CompoundTag t, String key) {
        if (!(t.get(key) instanceof ListTag)) t.put(key, new ListTag());
        return (ListTag) t.get(key);
    }

    /** All the town owes its market on IOUs, pennies. */
    public static long debts(UUID village) {
        long n = 0;
        for (Tag e : list(town(village), "debts")) n += ((CompoundTag) e).getLong("p");
        return n;
    }

    /** The IOUs in words, the oldest first. */
    public static List<String> debtLines(UUID village) {
        List<String> out = new ArrayList<>();
        for (Tag e : list(town(village), "debts")) {
            CompoundTag t = (CompoundTag) e;
            out.add("Day " + (t.getLong("day") + 1) + ": " + t.getString("who") + " owes " + Businesses.pence(t.getLong("p")) + " for " + t.getString("what"));
        }
        return out;
    }

    /**
     * Taxes in (Market.payWages, Businesses.paysHand, the tithe): the IOUs paid first, oldest first, out of the coin that has
     * just come in, from the treasury into the market. Returns the pennies paid on them.
     */
    public static long taxIn(UUID village, long pence) {
        if (pence <= 0) return 0;
        ListTag l = list(town(village), "debts");
        long paid = 0;
        while (!l.isEmpty() && paid < pence) {
            CompoundTag t = l.getCompound(0);
            long owed = t.getLong("p");
            long pay = Math.min(owed, pence - paid);
            long got = Businesses.fromTreasury(village, pay);
            if (got <= 0) break;
            MarketMaker.cashIn(village, got);
            paid += got;
            if (got >= owed) l.remove(0); else t.putLong("p", owed - got);
            if (got < pay) break;
        }
        if (paid > 0) {
            add(sub(town(village), "debtsPaid"), "pence", paid);
            dirty();
        }
        return paid;
    }

    /** The IOUs set against the market's surplus (no coin moves: the town's own enterprise and the town): up to so much,
     *  oldest first. Returns the pennies set off. */
    static long netDebts(UUID village, long upTo) {
        ListTag l = list(town(village), "debts");
        long done = 0;
        while (!l.isEmpty() && done < upTo) {
            CompoundTag t = l.getCompound(0);
            long owed = t.getLong("p");
            long k = Math.min(owed, upTo - done);
            done += k;
            if (k >= owed) l.remove(0); else t.putLong("p", owed - k);
        }
        if (done > 0) dirty();
        return done;
    }

    // ------------------------------------------------------------------ the stragglers

    /** Classes whose frames are the choke points themselves: the straggler is whoever called them. */
    private static final java.util.Set<String> CHOKE = java.util.Set.of(
        Accounts.class.getName(), MarketMaker.class.getName(), TownWork.class.getName(), Crafts.class.getName(),
        Market.class.getName(), AssistantEntity.class.getName(), Caravans.class.getName(), Workshop.class.getName(),
        WarWorks.class.getName(), TownLod.class.getName());

    private static final StackWalker WALKER = StackWalker.getInstance();

    /**
     * A take (or a give) nobody's scope answered for: counted under where it came from ("Class.method:line"), the first
     * three a day in a town looked up the stack (the rest counted only), and the treasury pays.
     */
    static void straggler(ServerLevel level, UUID village, String what, int n) {
        sampled(level, village, "stragglers", what, n, true);
    }

    /** A take by the towns' own round with no scope of its own: on the works account, its system counted (the books show which). */
    static void system(ServerLevel level, UUID village, String what, int n) {
        sampled(level, village, "systems", what, n, false);
    }

    private static void sampled(ServerLevel level, UUID village, String book, String what, int n, boolean warn) {
        CompoundTag s = sub(town(village), book);
        long day = today(level);
        if (s.getLong("day") != day) {
            s.putLong("day", day);
            s.put("sites", new CompoundTag());
            s.putInt("looked", 0);
            s.putLong("count", 0);
        }
        add(s, "count", 1);
        add(s, "all", 1);
        String site = "unsampled";
        if (s.getInt("looked") < LOOKS_A_DAY) {
            s.putInt("looked", s.getInt("looked") + 1);
            List<String> chain = WALKER.walk(fr -> fr.filter(f -> !CHOKE.contains(f.getClassName()))
                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber())
                .limit(6).toList());
            site = chain.isEmpty() ? "unknown" : chain.get(0);
            if (!sub(s, "sites").contains(site)) {
                if (warn) LOG.info("[econ-1a] straggler in {}: {} x{} from {}", Villages.name(village), what, n, String.join(" < ", chain));
                else LOG.debug("[econ-1a] the town's systems in {}: {} x{} from {}", Villages.name(village), what, n, String.join(" < ", chain));
            }
        }
        add(sub(s, "sites"), site, 1);
        dirty();
    }

    /** How many takes a day are looked up the stack for where they came from (the rest counted only). */
    static final int LOOKS_A_DAY = 64;

    /** A straggler that turned out paid at a counter the same tick (the take came first): not a straggler after all. */
    static void unstraggle(ServerLevel level, UUID village) {
        CompoundTag s = sub(town(village), "stragglers");
        if (s.getLong("day") == today(level) && s.getLong("count") > 0) add(s, "count", -1);
        if (s.getLong("all") > 0) add(s, "all", -1);
        dirty();
    }

    /** Today's takes by the towns' own round, by the system they came from. */
    public static Map<String, Long> systemSites(ServerLevel level, UUID village) {
        Map<String, Long> out = new LinkedHashMap<>();
        CompoundTag s = sub(town(village), "systems");
        if (s.getLong("day") != today(level)) return out;
        CompoundTag sites = sub(s, "sites");
        for (String k : sites.getAllKeys()) out.put(k, sites.getLong(k));
        return out;
    }

    /** Today's stragglers: how many takes nobody answered for. */
    public static long stragglersToday(ServerLevel level, UUID village) {
        CompoundTag s = sub(town(village), "stragglers");
        return s.getLong("day") == today(level) ? s.getLong("count") : 0;
    }

    /** Today's stragglers by where they came from. */
    public static Map<String, Long> stragglerSites(ServerLevel level, UUID village) {
        Map<String, Long> out = new LinkedHashMap<>();
        CompoundTag s = sub(town(village), "stragglers");
        if (s.getLong("day") != today(level)) return out;
        CompoundTag sites = sub(s, "sites");
        for (String k : sites.getAllKeys()) out.put(k, sites.getLong(k));
        return out;
    }

    /** Every straggler ever. */
    public static long stragglersAll(UUID village) {
        return sub(town(village), "stragglers").getLong("all");
    }

    // ------------------------------------------------------------------ in words

    /** One account's standing in words (/village accounts <folk|firm>). */
    public static String words(ServerLevel level, UUID village, Account a) {
        StringBuilder sb = new StringBuilder(a.words(village) + ": ");
        switch (a.kind()) {
            case FIRM -> {
                Business b = Businesses.of(village, a.key());
                if (b == null) return sb.append("no such business").toString();
                sb.append(b.town() ? "the town's (its coin the treasury's)" : "till " + Businesses.pence(b.till))
                    .append(", trade credit ").append(Businesses.pence(b.account)).append(", owners ").append(b.ownersWords());
            }
            case FOLK -> {
                VillageFolkEntity f = a.who() == null ? null : Homes.loaded(village, a.who());
                if (f != null) sb.append("purse ").append(f.purse()).append(" coin");
                sb.append(", change ").append(Businesses.pence(sub(town(village), "change").getLong(a.who() == null ? "" : a.who().toString())));
            }
            case DEPT -> {
                long[] d = deptBook(village, a.deptOf() == null ? Dept.HALL : a.deptOf());
                sb.append("spent today ").append(Businesses.pence(d[0])).append(", in today ").append(Businesses.pence(d[1]))
                    .append("; all told spent ").append(Businesses.pence(d[2])).append(", in ").append(Businesses.pence(d[3]));
            }
            default -> sb.append("no money of its own");
        }
        long o = owed(village, a);
        if (o > 0) sb.append("; owes the market ").append(Businesses.pence(o));
        return sb.toString();
    }

    // ------------------------------------------------------------------ tests

    public static void resetForTests() {
        STACK.clear();
        if (!com.jrpetty.mcassistant.SessionReset.opening()) {
            books().root = new CompoundTag();
            dirty();
        }
    }

    /** Tests: how deep the scope stack is now. */
    public static int depthForTests() {
        return STACK.size();
    }

    /** Tests: the department pays this, as a take would. */
    public static long debitForTests(ServerLevel level, UUID village, Account a, long p, Purpose why) {
        return debit(level, village, a, p, why, "a test");
    }
}
