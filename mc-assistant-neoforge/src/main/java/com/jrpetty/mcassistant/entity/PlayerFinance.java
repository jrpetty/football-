package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [purse20] A player's own books: every coin that comes into its pack from the towns or goes out of it to them, written
 * down where it is booked (CoinCensus.playerIn and playerOut, the census's player bookings, carry the player, what
 * kind of money it was and what for), and the few movements that never pass through the pack (the bank's interest on
 * its account, a mortgage's week taken out of it, rent and coupons and share money waiting at the hall).
 *
 * <p>Three records, each kept with the world (SavedData "mc_assistant_player_finance"), a compound to each player:
 * <ul>
 * <li><b>the categories</b>, day by day for sixty days: so much in wages, in shift pay, in rent received, so much out
 *     in taxes, in rent, in fines; and what only moved between its own holdings (into savings, shares and bonds
 *     bought, a loan taken), kept apart so it is neither income nor spending;</li>
 * <li><b>the ledger</b>, its last two hundred entries: the day, what, the town it was with, and the coin, signed;</li>
 * <li><b>the history</b> of what it is worth, day by day for sixty days: net worth, the coin it carries, assets and
 *     liabilities (FinanceSheet works them out; written each morning for the players about, and whenever its
 *     finances are opened).</li>
 * </ul>
 * Everything is in pennies, a hundred to the coin, so a coupon's odd pennies are as exact as a stall's whole coins.
 * The page (FinanceSheet.page) is sent only to the player it belongs to, or to an operator who asks for it by name.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class PlayerFinance {

    private PlayerFinance() {}

    /** Kept so many days (the categories and the history), and so many ledger entries. */
    public static final int DAYS = 60, LEDGER = 200;

    /** Money in, money out, or money only moved between the player's own holdings. */
    public enum Kind { INCOME, SPENDING, MOVE }

    /** What kind of money it was. Saved by name, so the order may change; new ones go anywhere. */
    public enum Cat {
        WAGES(Kind.INCOME, "Wages"), SHIFTS(Kind.INCOME, "Shift pay"), TIPS(Kind.INCOME, "Tips"), SALES(Kind.INCOME, "Sales"),
        STALL(Kind.INCOME, "Stall takings"), CONTRACTS(Kind.INCOME, "Contracts & haulage"), RENT_IN(Kind.INCOME, "Rent received"),
        DIVIDENDS(Kind.INCOME, "Dividends & drawings"), INTEREST(Kind.INCOME, "Interest earned"), COUPONS(Kind.INCOME, "Bond coupons"),
        REWARDS(Kind.INCOME, "Rewards & bounties"), PRIZES(Kind.INCOME, "Prizes & winnings"), REFUNDS(Kind.INCOME, "Refunds"),
        OTHER_IN(Kind.INCOME, "Other income"),
        RENT_OUT(Kind.SPENDING, "Rent paid"), TAXES(Kind.SPENDING, "Taxes"), FEES(Kind.SPENDING, "Fees & services"),
        HANDS(Kind.SPENDING, "Wages to your hands"), PURCHASES(Kind.SPENDING, "Goods bought"), FOOD(Kind.SPENDING, "Food, drink & leisure"),
        LOAN_PAY(Kind.SPENDING, "Loan repayments & interest"), FINES(Kind.SPENDING, "Fines & bail"), GIFTS(Kind.SPENDING, "Gifts & donations"),
        LOSSES(Kind.SPENDING, "Losses & forfeits"), OTHER_OUT(Kind.SPENDING, "Other spending"),
        SAVED(Kind.MOVE, "Into savings"), DRAWN(Kind.MOVE, "Out of savings"), BORROWED(Kind.MOVE, "Borrowed"),
        INVESTED(Kind.MOVE, "Property & investments bought"), CASHED(Kind.MOVE, "Property & investments sold"),
        HELD(Kind.MOVE, "Deposits put down"), RELEASED(Kind.MOVE, "Deposits back");

        public final Kind kind;
        public final String label;

        Cat(Kind kind, String label) {
            this.kind = kind;
            this.label = label;
        }

        @Nullable
        static Cat named(String s) {
            try { return valueOf(s); } catch (IllegalArgumentException e) { return null; }
        }

        /** The earning board's source (Earnings.fromTreasury) as a category. */
        public static Cat of(Earnings.Source src) {
            return switch (src) {
                case SHIFT -> SHIFTS;
                case TIPS -> TIPS;
                case SELL, WANTED, FINDS, BANK -> SALES;
                case STALL -> STALL;
                case CONTRACT -> CONTRACTS;
                case PROPERTY -> CASHED;
                case BOUNTY, QUEST -> REWARDS;
                case WAR -> WAGES;
            };
        }
    }

    // ------------------------------------------------------------------ booking

    /** [purse20] A booked flow of coin: {@code coins} into the player's pack (over nought) or out of it (under). */
    public static void book(@Nullable Player p, @Nullable UUID village, long coins, Cat cat, String what) {
        if (p == null || coins == 0) return;
        record(p.getUUID(), p.getName().getString(), village, coins * 100L, cat, what, true);
    }

    /** The same for a player known only by its id (a prize kept for it, a seller away from the auction). */
    public static void bookId(@Nullable UUID player, @Nullable UUID village, long coins, Cat cat, String what) {
        if (player == null || coins == 0) return;
        record(player, null, village, coins * 100L, cat, what, true);
    }

    /** Money that moved in the player's name without passing through its pack (pennies): interest put on its account, a
     *  mortgage's week taken out of it. In the ledger and the categories, not in the coin it carries. */
    public static void internal(@Nullable UUID player, @Nullable UUID village, long pence, Cat cat, String what) {
        if (player == null || pence == 0) return;
        record(player, null, village, pence, cat, what, false);
    }

    static void record(UUID player, @Nullable String name, @Nullable UUID village, long pence, Cat cat, String what, boolean cash) {
        try {
            long day = today();
            CompoundTag me = player(player);
            if (name != null && !name.isEmpty()) me.putString("name", name);
            // The day's categories.
            CompoundTag days = sub(me, "days");
            CompoundTag d = sub(days, Long.toString(day));
            d.putLong(cat.name(), d.getLong(cat.name()) + pence);
            prune(days, day);
            // The ledger.
            ListTag ledger = me.getList("ledger", Tag.TAG_COMPOUND);
            CompoundTag e = new CompoundTag();
            e.putLong("d", day);
            e.putLong("t", timeOfDay());
            e.putString("c", cat.name());
            e.putString("w", what == null ? "" : what);
            e.putString("v", village == null ? "" : Villages.name(village));
            if (village != null) e.putString("vid", village.toString());
            e.putLong("a", pence);
            if (!cash) e.putBoolean("i", true);
            ledger.add(e);
            while (ledger.size() > LEDGER) ledger.remove(0);
            me.put("ledger", ledger);
            me.putLong("seq", me.getLong("seq") + 1);
            if (cash) me.putLong("cashSum", me.getLong("cashSum") + pence);   // every booked coin through the pack, all time
            dirty();
        } catch (RuntimeException ex) {
            LOG.warn("[purse20] could not book a player's flow: {}", ex.toString());
        }
    }

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    // ------------------------------------------------------------------ what waits at the hall, by kind

    /** Coin put by for the player at the hall (TownBonds' due), and what kind it is: a coupon, a title's rent, the price
     *  of shares sold, a bond repaid. Collected all together, so the kinds are kept here to book it by. */
    public static void accrued(@Nullable UUID player, UUID village, long pence, Cat cat) {
        if (player == null || pence <= 0) return;
        CompoundTag mix = sub(sub(player(player), "due"), village.toString());
        mix.putLong(cat.name(), mix.getLong(cat.name()) + pence);
        dirty();
    }

    /** The same, by the id as the books keep it (a string). */
    public static void accrued(String player, UUID village, long pence, Cat cat) {
        try { accrued(UUID.fromString(player), village, pence, cat); } catch (IllegalArgumentException ignored) { }
    }

    /** What is waiting at the hall for the player, by kind (pennies), as far as it is known. */
    static Map<Cat, Long> dueMix(UUID player, UUID village) {
        Map<Cat, Long> out = new EnumMap<>(Cat.class);
        CompoundTag mix = sub(sub(player(player), "due"), village.toString());
        for (String k : mix.getAllKeys()) {
            Cat c = Cat.named(k);
            if (c != null && mix.getLong(k) > 0) out.put(c, mix.getLong(k));
        }
        return out;
    }

    /** [purse20] The player collected so many coins at the hall: booked in the kinds it was put by in (oldest kinds first
     *  share by what each is of the whole), any odd part as coupons. */
    public static void collected(@Nullable Player p, UUID village, int coins) {
        if (p == null || coins <= 0) return;
        CoinCensus.playerOut(village, coins);
        CompoundTag mix = sub(sub(player(p.getUUID()), "due"), village.toString());
        long whole = 0;
        for (String k : mix.getAllKeys()) whole += Math.max(0, mix.getLong(k));
        long pence = coins * 100L;
        if (whole <= 0) {
            book(p, village, coins, Cat.COUPONS, "collected at the hall");
            return;
        }
        long left = pence;
        List<String> keys = new ArrayList<>(mix.getAllKeys());
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            Cat c = Cat.named(k);
            long part = i == keys.size() - 1 ? left : Math.min(left, pence * Math.max(0, mix.getLong(k)) / whole);
            if (part <= 0) continue;
            left -= part;
            long rest = mix.getLong(k) - part;
            if (rest <= 0) mix.remove(k); else mix.putLong(k, rest);
            record(p.getUUID(), p.getName().getString(), village, part, c == null ? Cat.COUPONS : c, switch (c == null ? Cat.COUPONS : c) {
                case RENT_IN -> "rent on your titles, collected at the hall";
                case CASHED -> "shares sold and bonds repaid, collected at the hall";
                case DIVIDENDS -> "dividends, collected at the hall";
                default -> "coupons on your bonds, collected at the hall";
            }, true);
        }
    }

    // ------------------------------------------------------------------ what the player paid for its shares

    /** [purse20] The cost of a holding (pennies), kept to tell its gain or loss: added on a buy. */
    public static void costAdd(@Nullable UUID player, String key, long pence) {
        if (player == null || pence == 0) return;
        CompoundTag b = sub(player(player), "basis");
        b.putLong(key, Math.max(0, b.getLong(key) + pence));
        dirty();
    }

    /** Taken off in proportion on a sale: {@code sold} parts of {@code had}. */
    public static void costSold(@Nullable UUID player, String key, int sold, int had) {
        if (player == null || had <= 0) return;
        CompoundTag b = sub(player(player), "basis");
        long was = b.getLong(key);
        long now = sold >= had ? 0 : was - was * sold / had;
        if (now <= 0) b.remove(key); else b.putLong(key, now);
        dirty();
    }

    static long cost(UUID player, String key) {
        return sub(player(player), "basis").getLong(key);
    }

    /** A player's id as the books keep it (a string), or null. */
    @Nullable
    static UUID uuid(String s) {
        try { return s == null || s.isEmpty() ? null : UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    // ------------------------------------------------------------------ the records, read

    /** The player's compound (made the first time). */
    static CompoundTag player(UUID player) {
        return sub(sub(store().root, "players"), player.toString());
    }

    static boolean known(UUID player) {
        return sub(store().root, "players").contains(player.toString());
    }

    /** By name (for an operator's look-up of a player who is away). */
    @Nullable
    static UUID byName(String name) {
        CompoundTag all = sub(store().root, "players");
        for (String k : all.getAllKeys()) {
            if (all.getCompound(k).getString("name").equalsIgnoreCase(name)) {
                try { return UUID.fromString(k); } catch (IllegalArgumentException ignored) { }
            }
        }
        return null;
    }

    /** One day's categories (pennies, signed: income over nought, spending under). */
    public static Map<Cat, Long> day(UUID player, long day) {
        Map<Cat, Long> out = new EnumMap<>(Cat.class);
        CompoundTag d = sub(player(player), "days").getCompound(Long.toString(day));
        for (String k : d.getAllKeys()) {
            Cat c = Cat.named(k);
            if (c != null) out.merge(c, d.getLong(k), Long::sum);
        }
        return out;
    }

    /** The categories over the {@code n} days to {@code to} (inclusive). */
    public static Map<Cat, Long> span(UUID player, long to, int n) {
        Map<Cat, Long> out = new EnumMap<>(Cat.class);
        for (long d = to - n + 1; d <= to; d++) for (Map.Entry<Cat, Long> e : day(player, d).entrySet()) out.merge(e.getKey(), e.getValue(), Long::sum);
        return out;
    }

    /** Income (pennies, over nought) and spending (pennies, over nought) in a set of categories. */
    public static long[] inOut(Map<Cat, Long> m) {
        long in = 0, out = 0;
        for (Map.Entry<Cat, Long> e : m.entrySet()) {
            if (e.getKey().kind == Kind.INCOME) in += e.getValue();
            else if (e.getKey().kind == Kind.SPENDING) out -= e.getValue();
        }
        return new long[]{ in, out };
    }

    /** The ledger, oldest first (a copy). */
    public static ListTag ledger(UUID player) {
        return player(player).getList("ledger", Tag.TAG_COMPOUND).copy();
    }

    /** What the player was worth each day: {net, cash, assets, liabilities} pennies, by day. */
    public static Map<Long, long[]> history(UUID player) {
        Map<Long, long[]> out = new java.util.TreeMap<>();
        CompoundTag h = sub(player(player), "worth");
        for (String k : h.getAllKeys()) {
            long[] v = h.getCompound(k).contains("n") ? new long[]{ h.getCompound(k).getLong("n"), h.getCompound(k).getLong("c"),
                h.getCompound(k).getLong("a"), h.getCompound(k).getLong("l") } : null;
            try { if (v != null) out.put(Long.parseLong(k), v); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** Today's worth, written (FinanceSheet, when it works the player's holdings out). */
    static void worth(UUID player, long day, long net, long cash, long assets, long liabilities) {
        CompoundTag h = sub(player(player), "worth");
        CompoundTag w = new CompoundTag();
        w.putLong("n", net);
        w.putLong("c", cash);
        w.putLong("a", assets);
        w.putLong("l", liabilities);
        h.put(Long.toString(day), w);
        prune(h, day);
        dirty();
    }

    /** The coin it carried when last seen (pennies): for a player who is away. */
    static long lastCash(UUID player) {
        return player(player).getLong("cash");
    }

    static void lastCash(UUID player, long pence) {
        player(player).putLong("cash", pence);
    }

    private static void prune(CompoundTag byDay, long today) {
        for (String k : new ArrayList<>(byDay.getAllKeys())) {
            try {
                if (Long.parseLong(k) <= today - DAYS) byDay.remove(k);
            } catch (NumberFormatException e) {
                byDay.remove(k);
            }
        }
    }

    // ------------------------------------------------------------------ each morning, and the notices

    private static long lastDay = Long.MIN_VALUE;

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 100 != 37) return;
        long day = server.overworld().getDayTime() / 24000L;
        if (day == lastDay) return;
        boolean first = lastDay == Long.MIN_VALUE;
        lastDay = day;
        if (first) return;                                   // the server just started: the players' logins do it
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            com.jrpetty.mcassistant.Guard.run("the player's finances", () -> morning(p, day));
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        com.jrpetty.mcassistant.Guard.run("the player's finances", () -> morning(p, today()));
    }

    /** The player's morning: its worth for the day written, the week's summary on the week's first morning (or the
     *  first time it is about after it), and a word about bills due soon or overdue (once a day). */
    static void morning(ServerPlayer p, long day) {
        FinanceSheet.Sheet s = FinanceSheet.work(p.serverLevel(), p.getUUID(), p);
        CompoundTag me = player(p.getUUID());
        long week = Math.floorDiv(day, 7);
        if (me.getLong("summaryWeek") < week && day >= 7 && me.contains("days")) {
            me.putLong("summaryWeek", week);
            long[] io = inOut(span(p.getUUID(), week * 7 - 1, 7));
            if (io[0] != 0 || io[1] != 0) FinanceSheet.notify(p, FinanceSheet.weekToast(p.getUUID(), io, s, week * 7 - 1));
            dirty();
        }
        if (me.getLong("billsDay") != day + 1) {
            me.putLong("billsDay", day + 1);
            CompoundTag alert = FinanceSheet.billsToast(s, day);
            if (alert != null) FinanceSheet.notify(p, alert);
            dirty();
        }
    }

    // ------------------------------------------------------------------ time

    static long today() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? 0 : server.overworld().getDayTime() / 24000L;
    }

    static long timeOfDay() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? 0 : server.overworld().getDayTime() % 24000L;
    }

    // ------------------------------------------------------------------ kept with the world

    static final class Store extends SavedData {
        CompoundTag root = new CompoundTag();

        static Store load(CompoundTag tag, HolderLookup.Provider p) {
            Store s = new Store();
            s.root = tag.getCompound("root");
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider p) {
            tag.put("root", root.copy());
            return tag;
        }
    }

    @Nullable private static Store loose;

    static Store store() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new Store();
            return loose;
        }
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Store::new, Store::load, null), "mc_assistant_player_finance");
    }

    static void dirty() {
        store().setDirty();
    }

    static CompoundTag sub(CompoundTag t, String key) {
        if (!t.contains(key, Tag.TAG_COMPOUND)) t.put(key, new CompoundTag());
        return t.getCompound(key);
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the player's records forgotten. */
    public static void resetForTests(UUID player) {
        sub(store().root, "players").remove(player.toString());
        dirty();
    }

    /** Tests: the whole store as it would be saved, and read back as it would be loaded (a save and a load). */
    public static void roundTripForTests(ServerLevel level) {
        Store s = store();
        CompoundTag saved = s.save(new CompoundTag(), level.registryAccess());
        s.root = Store.load(saved, level.registryAccess()).root;
    }

    /** Tests: so many entries put straight into the ledger (to see it capped). */
    public static void floodForTests(UUID player, int n) {
        for (int i = 0; i < n; i++) record(player, null, null, 100, Cat.OTHER_IN, "test entry " + i, true);
    }

    /** Tests: today's worth written on another day (to see the history kept and capped). */
    public static void worthForTests(UUID player, long day, long net) {
        CompoundTag h = sub(player(player), "worth");
        CompoundTag w = new CompoundTag();
        w.putLong("n", net);
        h.put(Long.toString(day), w);
        prune(h, today());
    }

    /** Tests: a category on another day (to see the days kept and capped). */
    public static void dayForTests(UUID player, long day, Cat cat, long pence) {
        CompoundTag days = sub(player(player), "days");
        CompoundTag d = sub(days, Long.toString(day));
        d.putLong(cat.name(), d.getLong(cat.name()) + pence);
        prune(days, today());
    }

    /** Tests: the name the books know a player by. */
    public static void nameForTests(UUID player, String name) {
        player(player).putString("name", name);
    }

    /** Tests: every booked coin through the player's pack since its records began (pennies, signed). */
    public static long cashSumForTests(UUID player) {
        return player(player).getLong("cashSum");
    }

    /** Tests: how many entries have ever been booked for the player. */
    public static long seqForTests(UUID player) {
        return player(player).getLong("seq");
    }

    public static int daysKeptForTests(UUID player) {
        return sub(player(player), "days").getAllKeys().size();
    }

    public static int worthKeptForTests(UUID player) {
        return sub(player(player), "worth").getAllKeys().size();
    }
}
