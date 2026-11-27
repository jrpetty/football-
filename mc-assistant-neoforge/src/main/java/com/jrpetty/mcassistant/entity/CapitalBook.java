package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [capital] The town's capital books: what its savers hold at its savings club, what the club and the bank have lent its
 * firms and at what rate, the shares of its firms and their prices on the bourse, the titles to its land and what the land
 * is worth, and the bonds the town has sold. Each part keeps its own records here, under the town's accounts (Accounts),
 * so they are kept with the world; this class keeps the day book (a line of figures each morning, the last four weeks)
 * and the morning's order.
 *
 * <p>The morning (Market.tick): before the wages, the town sells what bonds it needs (TownBonds.beforeWages) so its
 * essential wages are paid and its IOUs to its market funded; after the bank's morning, the savings club and the lending
 * (FolkCredit), the bourse (Bourse), the land (LandTitles), the bonds' coupons and redemptions (TownBonds), and the day's
 * line written.
 */
public final class CapitalBook {

    private CapitalBook() {}

    /** The days of figures kept. */
    static final int DAYS = 28;

    /** The town's capital compound (made the first time it is asked for). */
    static CompoundTag root(UUID village) {
        return Accounts.sub(Accounts.town(village), "capital");
    }

    /** A part's own compound ("bonds", "club", "bourse", "land"). */
    static CompoundTag part(UUID village, String key) {
        return Accounts.sub(root(village), key);
    }

    static ListTag list(CompoundTag t, String key) {
        return Accounts.list(t, key);
    }

    static void dirty() {
        Accounts.dirty();
    }

    static long dayNow() {
        return Accounts.dayNow();
    }

    /** Before the wages (Market.tick): the bonds the town needs sold, its IOUs funded and its essential wages found. */
    public static void beforeWages(ServerLevel level, Villages.Village v, long day) {
        if (!MarketMaker.live(v.id())) return;
        TownBonds.beforeWages(level, v, day);
    }

    /** The capital morning (Market.tick, after the bank's): the club, the loans, the bourse, the land, the bonds. */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (!MarketMaker.live(id)) return;
        CompoundTag r = root(id);
        if (r.getLong("morning") == day + 1) return;
        r.putLong("morning", day + 1);
        FolkCredit.morning(level, v, day);
        Bourse.morning(level, v, day);
        LandTitles.morning(level, v, day);
        TownBonds.morning(level, v, day);
        snapshot(level, id, day);
        dirty();
    }

    /** The day's figures written in the day book. */
    static void snapshot(ServerLevel level, UUID id, long day) {
        ListTag days = list(root(id), "days");
        CompoundTag t = new CompoundTag();
        t.putLong("day", day);
        t.putLong("deposits", FolkCredit.deposits(id));
        t.putLong("loans", FolkCredit.loansOut(level, id));
        t.putInt("rate", FolkCredit.rateBp(id));
        t.putLong("bonds", TownBonds.outstanding(id));
        t.putInt("bondRate", TownBonds.rateBp(id));
        t.putLong("ious", Accounts.debts(id));
        t.putDouble("land", LandTitles.index(id));
        t.putLong("shares", Bourse.capitalisation(level, id));
        if (!days.isEmpty() && days.getCompound(days.size() - 1).getLong("day") == day) days.remove(days.size() - 1);
        days.add(t);
        while (days.size() > DAYS) days.remove(0);
    }

    /** The day book's figures, oldest first. */
    static List<CompoundTag> days(UUID id) {
        List<CompoundTag> out = new ArrayList<>();
        for (Tag e : list(root(id), "days")) out.add((CompoundTag) e);
        return out;
    }

    /** The figures of so many days ago (0 today), or null. */
    @Nullable
    static CompoundTag daysAgo(UUID id, int ago) {
        List<CompoundTag> d = days(id);
        long want = dayNow() - ago;
        for (int i = d.size() - 1; i >= 0; i--) if (d.get(i).getLong("day") <= want) return d.get(i);
        return null;
    }

    /** Whole coins, the word after them. */
    static String coins(long pence) {
        return Businesses.pence(pence);
    }

    /** What a folk keeps in its purse before it saves or invests: its week's living, rent and payments (Bank.keep). */
    static int keep(UUID village, VillageFolkEntity f) {
        return Bank.keep(village, f, Bank.state(village));
    }

    /** A line for the chronicle. */
    static void tell(UUID village, long day, String text) {
        Villages.tell(village, day, text);
    }

    /**
     * Tests: the world saved and loaded, as far as the capital goes: the accounts' books (where the club, the bonds, the
     * bourse and the land are kept) written out as a save file's bytes and read back, and every business of the town (its
     * owners' shares, its loan, its lender and rate) saved and loaded in its place.
     */
    public static void roundTripForTests(UUID village, net.minecraft.core.HolderLookup.Provider p) {
        try {
            Accounts.Books live = Accounts.books();
            CompoundTag saved = live.save(new CompoundTag(), p);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.writeCompressed(saved, out);
            CompoundTag read = net.minecraft.nbt.NbtIo.readCompressed(new java.io.ByteArrayInputStream(out.toByteArray()),
                net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            live.root = Accounts.Books.load(read, p).root;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        Businesses.Town t = Businesses.town(village);
        for (String k : new ArrayList<>(t.biz.keySet())) t.biz.put(k, Business.load(t.biz.get(k).save()));
    }

    public static void morningForTests(ServerLevel level, Villages.Village v, long day) {
        root(v.id()).remove("morning");
        morning(level, v, day);
    }

    public static List<CompoundTag> daysForTests(UUID village) {
        return days(village);
    }

    public static void resetForTests() {
        TownBonds.resetForTests();
        FolkCredit.resetForTests();
        Bourse.resetForTests();
        LandTitles.resetForTests();
    }
}
