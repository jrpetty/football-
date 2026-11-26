package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * [wpc] What the property package (land, homes, premises, mortgages, debts, wills, standing orders) shares: its books, kept
 * with the world beside the town's accounts (Accounts: a compound per town under "wpc", and one for the players under the
 * books' root); paying a bill out of the pack and then the bank (or savings club) account; and the pages' common manners
 * (a first-time hint, a question before money moves, the arrow to a place), the Town Hall's own (TownHall), so every page
 * reads alike.
 */
public final class PropertyBook {

    private PropertyBook() {}

    /** A town's part of the package's books ("land", "premises", "debts", "wills", "orders"...). */
    static CompoundTag town(UUID village, String part) {
        return Accounts.sub(Accounts.sub(Accounts.town(village), "wpc"), part);
    }

    /** The players' part, whatever the town ("absent", "away"...). */
    static CompoundTag players(String part) {
        return Accounts.sub(Accounts.sub(Accounts.books().root, "wpc"), part);
    }

    static void dirty() {
        Accounts.dirty();
    }

    static long day(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    static String coins(long n) {
        return n == 1 ? "1 coin" : n + " coins";
    }

    // ------------------------------------------------------------------ money

    /** What the player has at the bank, or the savings club before there is one. */
    static int account(UUID village, UUID player) {
        return FolkCredit.balance(village, player);
    }

    /** So much out of the player's account (bank or club), as far as it goes; what was taken (the coin out of the vault). */
    static int takeAccount(UUID village, UUID player, int n) {
        if (n <= 0) return 0;
        if (Bank.open(village)) return Bank.takeFromAccount(village, player, n);
        CompoundTag a = FolkCredit.accounts(village);
        String k = player.toString();
        int k2 = (int) Math.min(Math.min(n, a.getInt(k)), FolkCredit.club(village).getLong("cash") / 100);
        if (k2 <= 0) return 0;
        a.putInt(k, a.getInt(k) - k2);
        if (a.getInt(k) <= 0) a.remove(k);
        FolkCredit.chestOut(village, k2 * 100L);
        CapitalBook.dirty();
        return k2;
    }

    /** So much into the player's account (a surplus kept for it, an inheritance), the coin with it. */
    static void putAccount(UUID village, UUID player, String name, int n) {
        if (n <= 0) return;
        if (Bank.open(village)) { Bank.putInAccount(village, player, name, n); return; }
        FolkCredit.chestIn(village, n * 100L);
        FolkCredit.credit(village, player, name, n);
    }

    /**
     * A bill paid by a player online: out of its pack first, then its account; into the treasury (or wherever {@code to}
     * puts it: null the treasury). Returns what was paid (all of it, or nothing if it could not all be found and
     * {@code whole}).
     */
    static int pay(ServerPlayer p, UUID village, int n, PlayerFinance.Cat cat, String why, boolean whole) {
        if (n <= 0) return 0;
        int held = Market.coinsHeld(p), banked = account(village, p.getUUID());
        if (whole && held + banked < n) return 0;
        int fromPack = Math.min(n, held);
        if (fromPack > 0) {
            Market.payOut(p, fromPack);
            CoinCensus.playerIn(village, fromPack, p, cat, why);
        }
        int fromAcct = takeAccount(village, p.getUUID(), n - fromPack);
        if (fromAcct > 0) PlayerFinance.internal(p.getUUID(), village, -fromAcct * 100L, cat, why + " (from your account)");
        int paid = fromPack + fromAcct;
        Ledger.addCoins(village, paid);
        return paid;
    }

    /** Tests: coin into a player's account (bank or club), and what it holds. */
    public static void putAccountForTests(UUID village, UUID player, int n) {
        putAccount(village, player, "a test player", n);
    }

    public static int accountForTests(UUID village, UUID player) {
        return account(village, player);
    }

    /** A bill paid for a player who is away: out of its account only, as far as it goes; into the treasury. */
    static int payOffline(UUID village, UUID player, int n, PlayerFinance.Cat cat, String why) {
        int k = takeAccount(village, player, n);
        if (k > 0) {
            PlayerFinance.internal(player, village, -k * 100L, cat, why + " (standing order)");
            Ledger.addCoins(village, k);
        }
        return k;
    }

    /** Coin to a player: into its pack if it is here, else its account. */
    static void give(ServerLevel level, UUID village, UUID player, String name, int n, PlayerFinance.Cat cat, String why) {
        if (n <= 0) return;
        ServerPlayer p = level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(player);
        if (p != null) {
            Dealings.giveCoins(p, n);
            CoinCensus.playerOut(village, n, p, cat, why);
        } else {
            putAccount(village, player, name, n);
            PlayerFinance.internal(player, village, n * 100L, cat, why + " (into your account)");
        }
    }

    /** What a thing would fetch here: the market's bid once it is open, else seven tenths of the price index. */
    static double worth(ServerLevel level, UUID village, net.minecraft.world.item.ItemStack one) {
        try {
            return MarketMaker.live(village) ? MarketMaker.bid(level, village, one) : PriceIndex.each(level, village, one) * 0.7;
        } catch (RuntimeException e) {
            return 0.5;
        }
    }

    @Nullable
    static ServerPlayer online(ServerLevel level, UUID player) {
        return level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(player);
    }

    /** A word to the player if it is here (the action bar and chat), else nothing: the summary at login has it. */
    static void tell(ServerLevel level, UUID player, String text) {
        ServerPlayer p = online(level, player);
        if (p != null) p.sendSystemMessage(net.minecraft.network.chat.Component.literal(text));
    }

    // ------------------------------------------------------------------ the pages' manners (the Town Hall's)

    static void hint(ServerPlayer p, LifePage page, String id, String text) {
        TownHall.hint(p, page, id, text);
    }

    static void confirmBox(LifePage page, PlayerLife.Ctx c) {
        TownHall.confirmBox(page, c);
    }

    static String ask(PlayerLife.Ctx c, String action, CompoundTag args, String question, String yes) {
        return TownHall.ask(c, action, args, question, yes);
    }

    static String showMe(ServerPlayer p, @Nullable BlockPos at, String label) {
        return TownHall.showMe(p, at, label);
    }

    /**
     * The common answers every page of the package gives: "confirm" (the question asked first, now answered), "cancel",
     * "hint" (dismissed), "tab". Returns null for anything else; {@code page} re-runs the action once confirmed.
     */
    @Nullable
    static String common(ServerPlayer p, PlayerLife.Ctx c, String action, CompoundTag args, PlayerLife.Page page) {
        switch (action) {
            case "confirm" -> {
                if (!c.args.contains("ask")) return "!Nothing to confirm.";
                CompoundTag a = c.args.getCompound("ask");
                c.args.remove("ask");
                CompoundTag next = a.getCompound("args").copy();
                next.putBoolean("sure", true);
                if (args.contains("f")) next.put("f", args.getCompound("f"));
                return page.act(p, c, a.getString("a"), next);
            }
            case "cancel" -> { c.args.remove("ask"); return "Left as it was. Nothing was paid or changed."; }
            case "hint" -> { Belonging.dismissHint(p.getUUID(), args.getString("id")); return ""; }
            case "tab" -> { c.tab = args.getString("tab"); c.args.remove("ask"); return ""; }
            default -> { return null; }
        }
    }
}
