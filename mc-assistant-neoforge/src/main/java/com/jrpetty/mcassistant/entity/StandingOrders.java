package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [wpc] Bills paid while the player is away, and what happens if it stays away a long time.
 * <ul>
 * <li><b>Standing orders.</b> Rent, rates, the mortgage (the bank's round) and every other bill come out of the player's bank
 *     account (or savings club) when they fall due, online or not, as long as the money lasts; online, the pack makes up what
 *     the account is short. A bill that cannot be met goes on the debts ledger (Debts), whose ladder runs at half pace while
 *     the player is away.</li>
 * <li><b>While you were away.</b> At login, a page of what was paid, what wasn't, and what is owed now.</li>
 * <li><b>A long absence.</b> After {@link #LONG_DAYS} days away with bills in arrears, the player's will is carried out if it
 *     made one (Wills); otherwise the town takes the property back and puts it to use (land to the town, houses to let),
 *     pays the debts out of what it fetches, and keeps the rest in the player's account against its return. The board and
 *     the chronicle record it.</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class StandingOrders implements PlayerLife.Page {

    /** Days away (with arrears) before the long-absence rule. */
    public static int LONG_DAYS = 30;

    static CompoundTag away(UUID player) {
        return Accounts.sub(PropertyBook.players("away"), player.toString());
    }

    /** Is the player away (not on the server)? */
    static boolean away(ServerLevel level, UUID player) {
        return PropertyBook.online(level, player) == null;
    }

    /**
     * A bill due: out of the account, then the pack if the player is here; the rest on the ledger (a debt of its kind to its
     * creditor). Returns what was paid now. Away, it goes in the summary for the player's return.
     */
    static int bill(ServerLevel level, UUID village, UUID player, String name, int amount, PlayerFinance.Cat cat, String why, String kind, String creditor, String ref) {
        if (amount <= 0) return 0;
        ServerPlayer p = PropertyBook.online(level, player);
        int paid = PropertyBook.payOffline(village, player, amount, cat, why);
        if (paid < amount && p != null) {
            int k = Math.min(amount - paid, Market.coinsHeld(p));
            if (k > 0) {
                Market.payOut(p, k);
                CoinCensus.playerIn(village, k, p, cat, why);
                com.jrpetty.mcassistant.village.Ledger.addCoins(village, k);
                paid += k;
            }
        }
        int short_ = amount - paid;
        String credKind = creditor.equals("the town") ? "town" : "town";
        if (short_ > 0) Debts.owe(level, village, player, name, kind, creditor, credKind, null, short_, 0, 0, ref);
        if (p == null) {
            log(player, PropertyBook.day(level), short_ > 0 ? "Couldn't pay all of " + why + ": " + paid + " of " + amount + " (" + short_ + " on your debts)"
                : "Paid " + why + ": " + amount);
            CompoundTag a = away(player);
            a.putInt("paid", a.getInt("paid") + paid);
            a.putInt("short", a.getInt("short") + short_);
            a.putString("town", village.toString());
        } else if (short_ > 0) {
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal("Your account and pack were " + short_ + " short of " + why + ": it's on your debts (Your life in town, Debts)."));
        }
        PropertyBook.dirty();
        return paid;
    }

    static void log(UUID player, long day, String line) {
        CompoundTag a = away(player);
        ListTag l = a.getList("log", Tag.TAG_STRING);
        l.add(StringTag.valueOf("Day " + day + ": " + line));
        while (l.size() > 40) l.remove(0);
        a.put("log", l);
        PropertyBook.dirty();
    }

    // ------------------------------------------------------------------ login and logout

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        CompoundTag a = away(p.getUUID());
        a.putLong("left", p.level().getDayTime() / 24000L);
        a.remove("log");
        a.remove("paid");
        a.remove("short");
        PropertyBook.dirty();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        CompoundTag a = away(p.getUUID());
        if (a.getList("log", Tag.TAG_STRING).isEmpty() && !a.getBoolean("absent")) return;
        p.server.execute(() -> p.server.execute(() -> PlayerLife.open(p, "away", null)));
    }

    // ------------------------------------------------------------------ the long absence

    /** Each morning for a town: anyone with property here away the long-absence limit, with arrears, has its affairs settled. */
    static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        java.util.Set<UUID> owners = new java.util.HashSet<>();
        for (LandPlots.Title t : LandPlots.all(id)) owners.add(t.owner());
        for (Homes.Home h : Homes.homes(id).values()) if (h.tenure == Homes.Tenure.PLAYER && h.landlord != null) owners.add(h.landlord);
        for (UUID who : owners) {
            if (!away(level, who)) continue;
            CompoundTag a = away(who);
            if (!a.contains("left") || a.getBoolean("absent") || day - a.getLong("left") < LONG_DAYS) continue;
            boolean arrears = false;
            for (Debts.Debt d : Debts.of(id, who)) if (d.stage() >= 2) arrears = true;
            Bank.Loan l = Bank.playerLoan(id, who);
            if (l != null && l.missed > 0) arrears = true;
            if (!arrears) continue;
            longAbsence(level, v, who, day);
        }
    }

    /** The will carried out if there is one; else the town takes the property back and keeps the surplus for the player. */
    static void longAbsence(ServerLevel level, Villages.Village v, UUID who, long day) {
        UUID id = v.id();
        CompoundTag a = away(who);
        a.putBoolean("absent", true);
        String name = Debts.person(id, who).getString("name");
        for (LandPlots.Title t : LandPlots.of(id, who)) if (name.isEmpty()) name = t.ownerName();
        if (name.isEmpty()) name = "an absent owner";
        if (Wills.has(id, who)) {
            String said = Wills.execute(level, v, who, name, "a long absence");
            log(who, day, "You were away " + LONG_DAYS + " days with bills unpaid: your will was carried out. " + said);
            return;
        }
        int fetched = 0;
        for (LandPlots.Title t : LandPlots.of(id, who)) {
            fetched += t.price() * LandPlots.BUY_BACK / 100;
            LandPlots.release(level, id, t.id());
        }
        for (Homes.Home h : new ArrayList<>(Homes.homes(id).values())) {
            if (h.tenure != Homes.Tenure.PLAYER || !who.equals(h.landlord)) continue;
            Bank.Loan l = Bank.playerLoan(id, who);
            int price = Homes.price(id, h) * Insolvency.FIRE_SALE / 100;
            int toBank = 0;
            if (l != null && l.anchor == h.anchor.asLong()) { toBank = Math.min(price, l.owed()); Mortgages.closeBySale(level, v, l, toBank); }
            h.tenure = Homes.Tenure.RENTED;
            h.landlord = null;
            h.landlordName = "";
            h.toLet = false;
            h.price = Homes.price(id, h);
            h.rent = Homes.rent(id, h);
            h.since = day;
            Homes.save(id, h);
            fetched += price - toBank;
        }
        if (Tenancy.of(id, who) != null) Tenancy.end(level, id, who);
        // the debts out of what it fetched; the rest kept in the player's account
        for (Tag e : new ArrayList<>(Debts.list(id, who))) {
            CompoundTag t = (CompoundTag) e;
            int k = Math.min(fetched, t.getInt("owed"));
            if (k <= 0) continue;
            Debts.settle(level, id, who, t, k);
            fetched -= k;
        }
        int kept = Math.min(fetched, com.jrpetty.mcassistant.village.Ledger.coins(id));
        if (kept > 0) {
            com.jrpetty.mcassistant.village.Ledger.takeCoins(id, kept);
            PropertyBook.putAccount(id, who, name, kept);
        }
        Villages.tell(id, day, name + " has been away " + LONG_DAYS + " days with bills unpaid: the town took back their property and put it to use, "
            + "paid their debts out of it, and holds " + kept + " coins for them against their return");
        log(who, day, "You were away " + LONG_DAYS + " days with bills unpaid. The town took back your property and put it to use; your debts were paid from it, and "
            + kept + " coins are kept for you in your account.");
    }

    // ------------------------------------------------------------------ the page ("away")

    @Override
    public LifePage build(ServerPlayer p, PlayerLife.Ctx c) {
        CompoundTag a = away(p.getUUID());
        ListTag log = a.getList("log", Tag.TAG_STRING);
        long left = a.getLong("left"), day = p.level().getDayTime() / 24000L;
        LifePage page = new LifePage("away", "While you were away", left > 0 ? (day - left) + " days since you were last here" : "What your standing orders did", p.registryAccess());
        page.row(new ItemStack(Items.CLOCK), "Paid by standing order: " + PropertyBook.coins(a.getInt("paid")), a.getInt("short") > 0
            ? PropertyBook.coins(a.getInt("short")) + " couldn't be met and went on your debts" : "Everything due was met from your account", "");
        if (a.getBoolean("absent")) page.text("You were away a long time with bills unpaid: your affairs were settled (see below).", 2);
        if (log.isEmpty()) page.text("Nothing fell due while you were away.", 4);
        for (int i = log.size() - 1; i >= 0 && i >= log.size() - 14; i--) page.text("• " + log.getString(i), log.getString(i).startsWith("Couldn't") ? 2 : 0);
        Villages.Village v = PlayerLife.here(p);
        if (v != null) page.text(Debts.status(v.id(), p.getUUID(), day), 4);
        page.buttons().button("Got it", "done", null).primary().button("Your debts", "debts", null).button("Your life in town", "life", null);
        return page;
    }

    @Nullable
    @Override
    public String act(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args) {
        CompoundTag a = away(p.getUUID());
        switch (action) {
            case "done" -> { a.remove("log"); a.remove("paid"); a.remove("short"); a.remove("absent"); PropertyBook.dirty(); return "#close"; }
            case "debts" -> { a.remove("log"); PlayerLife.open(p, "debts", null); return "#open:"; }
            case "life" -> { a.remove("log"); PlayerLife.open(p, "life", null); return "#open:"; }
            default -> { return null; }
        }
    }

    // ------------------------------------------------------------------ tests

    public static void leftForTests(UUID player, long day) {
        away(player).putLong("left", day);
    }

    public static List<String> logForTests(UUID player) {
        List<String> out = new ArrayList<>();
        for (Tag t : away(player).getList("log", Tag.TAG_STRING)) out.add(t.getAsString());
        return out;
    }

    public static int billForTests(ServerLevel level, UUID village, UUID player, int amount, String ref) {
        return bill(level, village, player, "a test player", amount, PlayerFinance.Cat.TAXES, "a test bill", "rates", "the town", ref);
    }

    public static void longAbsenceForTests(ServerLevel level, Villages.Village v, UUID who) {
        longAbsence(level, v, who, PropertyBook.day(level));
    }
}
