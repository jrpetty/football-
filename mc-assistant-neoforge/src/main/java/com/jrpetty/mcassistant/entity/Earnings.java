package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.net.EarnPayload;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [earning] A player's earnings: the one place every coin a town pays a player is booked and told.
 * <ul>
 * <li><b>Real payers.</b> Nothing here mints coin: {@link #fromTreasury} moves it out of a town's treasury into the
 *     player's pack (as far as the treasury runs), and every other payer (a folk's purse, a till, the bank) moves its
 *     own coin and then calls {@link #record}.</li>
 * <li><b>The book.</b> What the player earned, by town, by day and by source (selling, wanted orders, shifts, tips,
 *     bounties, contracts, finds, rent, the stall, quests, war), kept on the player for eight days and in all; the
 *     Earnings page draws it (EarnBoard).</li>
 * <li><b>Told at once.</b> Every payment is a chat line in plain words ("+12 coin from Harbour Bakery for 32 wheat")
 *     and a toast in the corner.</li>
 * <li><b>Daily caps.</b> Each payer has a day's purse (a shop's buying, a night's bounty); {@link #spentToday} and
 *     {@link #spend} keep it in the town's ledger, so a restart does not reset it.</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Earnings {

    private Earnings() {}

    /** Where a coin came from: the page's bars, in this order. */
    public enum Source {
        SELL("Selling"), WANTED("Wanted orders"), SHIFT("Shifts"), TIPS("Tips"), BOUNTY("Bounties"), CONTRACT("Contracts"),
        FINDS("Finds"), PROPERTY("Rent and houses"), BANK("Bank and changer"), STALL("Your stall"), QUEST("Quests"), WAR("War");

        public final String label;

        Source(String label) {
            this.label = label;
        }
    }

    /** The player's own tag for all of earning (the book, the job skills, a standing job). */
    static final String ROOT = "mca_earning";
    /** Days the book keeps day by day. */
    static final int BOOK_DAYS = 8;

    /** The player's earning tag (made if it is not there). Kept with the player's own data, so it outlasts a restart. */
    static CompoundTag data(Player p) {
        CompoundTag pd = p.getPersistentData();
        if (!pd.contains(ROOT)) pd.put(ROOT, new CompoundTag());
        return pd.getCompound(ROOT);
    }

    /** A respawned player keeps its book and its skills. */
    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone e) {
        CompoundTag was = e.getOriginal().getPersistentData();
        if (was.contains(ROOT)) e.getEntity().getPersistentData().put(ROOT, was.getCompound(ROOT).copy());
    }

    // ------------------------------------------------------------------ paying and booking

    /**
     * Coin out of this town's treasury into the player's pack, booked under its source and told ("+3 coin from
     * Harbour for serving Ada"). Returns what was paid: no more than the treasury holds.
     */
    public static int fromTreasury(Player p, UUID village, int coins, Source src, String from, String what) {
        if (coins <= 0) return 0;
        int paid = Ledger.takeCoins(village, coins);
        if (paid <= 0) return 0;
        Economy.spent(village, paid);
        giveCoins(p, paid);
        record(p, village, src, paid, from, what);
        return paid;
    }

    /** Coin into the pack (what does not fit, at the player's feet). */
    static void giveCoins(Player p, int n) {
        if (n <= 0) return;
        ItemStack coin = new ItemStack(McAssistantMod.VILLAGE_COIN.get(), n);
        if (!p.getInventory().add(coin)) p.drop(coin, false);
    }

    /** A payment already made by its payer, booked and told. */
    public static void record(Player p, UUID village, Source src, int coins, String from, String what) {
        if (coins <= 0 || !(p.level() instanceof ServerLevel level)) return;
        book(p, village, src, coins, level.getDayTime() / 24000L);
        String line = "+" + coins + (coins == 1 ? " coin" : " coins") + (from.isEmpty() ? "" : " from " + from) + (what.isEmpty() ? "" : " " + what);
        p.sendSystemMessage(Component.literal(line).withStyle(ChatFormatting.GOLD));
        if (p instanceof ServerPlayer sp) {
            CompoundTag t = new CompoundTag();
            t.putString("text", line);
            t.putInt("coins", coins);
            t.putString("source", src.label);
            send(sp, EarnPayload.TOAST, t);
        }
    }

    /** Into the book: the day's line for the town, and the totals. */
    static void book(Player p, UUID village, Source src, int coins, long day) {
        CompoundTag root = data(p);
        CompoundTag book = root.getCompound("book");
        String dk = Long.toString(day);
        CompoundTag d = book.getCompound(dk);
        int[] by = sized(d.getIntArray(village.toString()));
        by[src.ordinal()] += coins;
        d.putIntArray(village.toString(), by);
        book.put(dk, d);
        for (String k : java.util.List.copyOf(book.getAllKeys())) {
            try {
                if (day - Long.parseLong(k) >= BOOK_DAYS) book.remove(k);
            } catch (NumberFormatException e) {
                book.remove(k);
            }
        }
        root.put("book", book);
        int[] all = sized(root.getIntArray("all"));
        all[src.ordinal()] += coins;
        root.putIntArray("all", all);
    }

    private static int[] sized(int[] a) {
        int n = Source.values().length;
        if (a.length == n) return a;
        int[] out = new int[n];
        System.arraycopy(a, 0, out, 0, Math.min(n, a.length));
        return out;
    }

    /** What the player earned by source over the last {@code days} days to today (in one town, or all with null). */
    public static int[] earned(Player p, @javax.annotation.Nullable UUID village, long today, int days) {
        int[] out = new int[Source.values().length];
        CompoundTag book = data(p).getCompound("book");
        for (String k : book.getAllKeys()) {
            long d;
            try {
                d = Long.parseLong(k);
            } catch (NumberFormatException e) {
                continue;
            }
            if (today - d >= days || d > today) continue;
            CompoundTag day = book.getCompound(k);
            for (String town : day.getAllKeys()) {
                if (village != null && !town.equals(village.toString())) continue;
                int[] by = sized(day.getIntArray(town));
                for (int i = 0; i < out.length; i++) out[i] += by[i];
            }
        }
        return out;
    }

    /** The towns the player has earned in this week (the page's balances). */
    static java.util.Set<String> townsEarnedIn(Player p) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        CompoundTag book = data(p).getCompound("book");
        for (String k : book.getAllKeys()) out.addAll(book.getCompound(k).getAllKeys());
        return out;
    }

    public static int sum(int[] a) {
        int n = 0;
        for (int x : a) n += x;
        return n;
    }

    // ------------------------------------------------------------------ war

    /** A sellsword's fee a battle, and its share of the spoils when its side holds the field; within so far of it. */
    static final int MERCENARY_FEE = 6, SPOILS = 6, FIELD_REACH = 160;

    /**
     * A battle written up: each player who joined a side (Armies) and was on the field is paid its fee by the town it
     * fought for, and on a win its share of the spoils out of the beaten town's treasury. As far as each treasury runs.
     */
    public static void battleFought(ServerLevel level, UUID a, UUID b, int winner, net.minecraft.core.BlockPos centre) {
        for (ServerPlayer p : level.players()) {
            UUID side = Armies.sideOf(p);
            if (side == null || (!side.equals(a) && !side.equals(b)) || p.blockPosition().distSqr(centre) > FIELD_REACH * FIELD_REACH) continue;
            UUID foe = side.equals(a) ? b : a;
            fromTreasury(p, side, MERCENARY_FEE, Source.WAR, Villages.name(side), "for fighting at its side");
            boolean won = winner >= 0 && (winner == 0 ? a : b).equals(side);
            if (won) fromTreasury(p, foe, SPOILS, Source.WAR, Villages.name(foe), "as your share of the spoils");
        }
    }

    // ------------------------------------------------------------------ a payer's day

    /** What this payer of this town has paid players today. */
    public static int spentToday(UUID village, String payer, long day) {
        String s = Ledger.note(village, "earn.cap/" + payer);
        if (s == null || s.isEmpty()) return 0;
        int bar = s.indexOf('|');
        try {
            return bar > 0 && Long.parseLong(s.substring(0, bar)) == day ? Integer.parseInt(s.substring(bar + 1)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** This payer paid so much more today. */
    public static void spend(UUID village, String payer, long day, int coins) {
        if (coins <= 0) return;
        Ledger.note(village, "earn.cap/" + payer, day + "|" + (spentToday(village, payer, day) + coins));
    }

    // ------------------------------------------------------------------ the client

    /** To the player's client; a stand-in player with no connection (a test's) is skipped, not crashed. */
    static void send(ServerPlayer p, int kind, CompoundTag t) {
        try {
            PacketDistributor.sendToPlayer(p, new EarnPayload(kind, t));
        } catch (RuntimeException e) {
            LOGGED.computeIfAbsent("send", k -> {
                com.mojang.logging.LogUtils.getLogger().info("[earning] could not send to a player: {}", e.toString());
                return Boolean.TRUE;
            });
        }
    }

    private static final Map<String, Boolean> LOGGED = new ConcurrentHashMap<>();

    /** A button from the client (the Earnings page, the shift HUD's card): checked again here. */
    public static void act(ServerPlayer p, String action, CompoundTag args) {
        if (!(p.level() instanceof ServerLevel level)) return;
        try {
            switch (action) {
                case "page" -> send(p, EarnPayload.PAGE, EarnBoard.page(level, p));
                case "take" -> {
                    String said = Shifts.take(p, uuid(args.getString("town")), args.getString("role"), args.getBoolean("standing"));
                    tell(p, said);
                    send(p, EarnPayload.PAGE, EarnBoard.page(level, p));
                }
                case "quit" -> {
                    tell(p, Shifts.quit(p, args.getBoolean("standing")));
                    send(p, EarnPayload.PAGE, EarnBoard.page(level, p));
                }
                case "contract" -> {
                    tell(p, HaulContracts.accept(level, p, args.getString("id")));
                    send(p, EarnPayload.PAGE, EarnBoard.page(level, p));
                }
                default -> { }
            }
        } catch (RuntimeException e) {
            LOGGED.computeIfAbsent("act/" + action, k -> {
                com.mojang.logging.LogUtils.getLogger().warn("[earning] {} failed", k, e);
                return Boolean.TRUE;
            });
        }
    }

    static void tell(ServerPlayer p, String said) {
        if (said != null && !said.isEmpty()) p.sendSystemMessage(Component.literal(said).withStyle(ChatFormatting.YELLOW));
    }

    @javax.annotation.Nullable
    static UUID uuid(String s) {
        try {
            return s == null || s.isEmpty() ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Tests: what the player earned today from this source in this town. */
    public static int earnedTodayForTests(Player p, UUID village, Source src) {
        return earned(p, village, p.level().getDayTime() / 24000L, 1)[src.ordinal()];
    }
}
