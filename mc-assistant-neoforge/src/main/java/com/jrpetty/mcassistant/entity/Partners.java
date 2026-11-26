package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [wpb] Co-ownership: a business with several player owners, each with a share (its stake, in ten-thousandths: Business).
 * <ul>
 * <li><b>Selling a share.</b> An owner offers part of its stake (a tenth, a quarter, a half) to another player in town at a
 *     price; the other accepts on its Your businesses page, pays, and is a partner.</li>
 * <li><b>The split.</b> Profit is paid out by share: the till over a working reserve, each owner's part into its drawings
 *     (players), purse (folk) or the treasury (the town). The weekly draw does the same (Businesses.draw).</li>
 * <li><b>Decisions</b> follow the shares: an owner with half or more decides, or the managing partner the majority names.
 *     The others see the buttons rested, with who decides.</li>
 * <li><b>Buyout.</b> A partner buys another out by the same share offer, the other way.</li>
 * </ul>
 * The offers are kept in the firm's own record (Enterprise.rec, "shareOffers").
 */
public final class Partners {

    private Partners() {}

    static final long RESERVE = 300;

    /** May this player make the business's decisions (the majority, or the managing partner)? */
    public static boolean canDecide(UUID village, Business b, UUID player) {
        if (!b.ownedBy(player)) return false;
        CompoundTag r = Enterprise.rec(village, b.id);
        if (r != null && r.hasUUID("managing")) return r.getUUID("managing").equals(player);
        int mine = 0, most = 0;
        for (Business.Stake s : b.owners) {
            if (player.equals(s.who())) mine += s.parts();
            most = Math.max(most, s.parts());
        }
        return mine * 2 >= Business.WHOLE || mine == most;
    }

    /** Who decides, in words. */
    static String whyNot(UUID village, Business b) {
        CompoundTag r = Enterprise.rec(village, b.id);
        if (r != null && r.hasUUID("managing")) {
            for (Business.Stake s : b.owners) if (r.getUUID("managing").equals(s.who())) return "Only the managing partner, " + s.name() + ", decides that";
        }
        Business.Stake top = null;
        for (Business.Stake s : b.owners) if (top == null || s.parts() > top.parts()) top = s;
        return top == null ? "Not yours to decide" : "Only the largest owner, " + top.name() + " (" + top.parts() * 100 / Business.WHOLE + "%), decides that";
    }

    static int partsOf(Business b, UUID who) {
        int n = 0;
        for (Business.Stake s : b.owners) if (who.equals(s.who())) n += s.parts();
        return n;
    }

    /** Offer part of your stake to another player at a price. */
    static String offer(ServerPlayer from, UUID village, Business b, ServerPlayer to, int parts, int coins) {
        int have = partsOf(b, from.getUUID());
        if (have <= 0) return "!You hold no share of " + b.name + ".";
        if (from.getUUID().equals(to.getUUID())) return "!That's you.";
        parts = Math.min(parts, have);
        CompoundTag r = Enterprise.rec(village, b.id);
        if (r == null) return "!Its record is missing.";
        ListTag l = r.getList("shareOffers", Tag.TAG_COMPOUND);
        CompoundTag o = new CompoundTag();
        o.putUUID("from", from.getUUID());
        o.putString("fromName", from.getGameProfile().getName());
        o.putUUID("to", to.getUUID());
        o.putInt("parts", parts);
        o.putInt("coins", Math.max(0, coins));
        l.add(o);
        r.put("shareOffers", l);
        Accounts.dirty();
        WorkLife.toast(to, from.getGameProfile().getName() + " offers you " + parts * 100 / Business.WHOLE + "% of " + b.name + " for " + coins + "c. See Your businesses.");
        return "Offered " + parts * 100 / Business.WHOLE + "% of " + b.name + " to " + to.getGameProfile().getName() + " for " + coins + "c. They accept on their Your businesses page.";
    }

    /** The share offers waiting for this player, in any business of the town: {business, index, offer}. */
    static List<Object[]> offersFor(UUID village, UUID player) {
        List<Object[]> out = new ArrayList<>();
        for (Business b : Enterprise.all(village)) {
            CompoundTag r = Enterprise.rec(village, b.id);
            if (r == null || !b.open) continue;
            ListTag l = r.getList("shareOffers", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) if (l.getCompound(i).getUUID("to").equals(player)) out.add(new Object[]{ b, i, l.getCompound(i) });
        }
        return out;
    }

    /** Accept a share offer: pay, the stake moved. */
    static String accept(ServerLevel level, ServerPlayer buyer, UUID village, Business b, int i) {
        CompoundTag r = Enterprise.rec(village, b.id);
        if (r == null) return "!Its record is missing.";
        ListTag l = r.getList("shareOffers", Tag.TAG_COMPOUND);
        if (i < 0 || i >= l.size() || !l.getCompound(i).getUUID("to").equals(buyer.getUUID())) return "!That offer has gone.";
        CompoundTag o = l.getCompound(i);
        UUID from = o.getUUID("from");
        int parts = Math.min(o.getInt("parts"), partsOf(b, from)), coins = o.getInt("coins");
        if (parts <= 0) { l.remove(i); return "!They no longer hold that share."; }
        if (Market.coinsHeld(buyer) < coins) return "!The price is " + coins + "c; you carry " + Market.coinsHeld(buyer) + "c.";
        if (coins > 0) {
            Market.payOut(buyer, coins);
            ServerPlayer seller = level.getServer().getPlayerList().getPlayer(from);
            if (seller != null) Earnings.giveCoins(seller, coins);
            else b.ownersDue.merge(from, coins * 100L, Long::sum);
        }
        move(b, from, o.getString("fromName"), buyer.getUUID(), buyer.getGameProfile().getName(), parts);
        l.remove(i);
        r.put("shareOffers", l);
        b.note(Businesses.today(level), buyer.getGameProfile().getName() + " bought " + parts * 100 / Business.WHOLE + "% from " + o.getString("fromName") + " for " + coins + "c");
        Accounts.dirty();
        Businesses.dirty();
        return "You own " + partsOf(b, buyer.getUUID()) * 100 / Business.WHOLE + "% of " + b.name + " now. Profit is split by share; " + whoDecides(village, b) + ".";
    }

    static String whoDecides(UUID village, Business b) {
        Business.Stake top = null;
        for (Business.Stake s : b.owners) if (top == null || s.parts() > top.parts()) top = s;
        CompoundTag r = Enterprise.rec(village, b.id);
        if (r != null && r.hasUUID("managing")) for (Business.Stake s : b.owners) if (r.getUUID("managing").equals(s.who())) return s.name() + " is the managing partner";
        return top == null ? "nobody decides" : top.name() + " decides, holding the most";
    }

    /** Parts moved from one owner to another (merged into its stake if it has one). */
    static void move(Business b, UUID from, String fromName, UUID to, String toName, int parts) {
        List<Business.Stake> next = new ArrayList<>();
        int toHad = 0;
        for (Business.Stake s : b.owners) {
            if (from.equals(s.who())) {
                if (s.parts() - parts > 0) next.add(new Business.Stake(s.holder(), s.who(), s.name(), s.parts() - parts));
            } else if (to.equals(s.who())) toHad += s.parts();
            else next.add(s);
        }
        next.add(new Business.Stake(Business.Holder.PLAYER, to, toName, toHad + parts));
        b.owners.clear();
        b.owners.addAll(next);
    }

    /** Name the managing partner (the majority's call). */
    static String setManaging(ServerPlayer p, UUID village, Business b, UUID who) {
        int mine = partsOf(b, p.getUUID());
        if (mine * 2 < Business.WHOLE) return "!Only an owner of half or more names the managing partner.";
        CompoundTag r = Enterprise.rec(village, b.id);
        if (r == null || !b.ownedBy(who)) return "!They're not an owner.";
        r.putUUID("managing", who);
        Accounts.dirty();
        return whoDecides(village, b) + ".";
    }

    /** The profit paid out now by share: the till over a working reserve. Returns the pennies paid out. */
    static long payout(ServerLevel level, UUID village, Business b) {
        long pot = b.till - RESERVE;
        if (pot < 100) return 0;
        long paid = 0;
        for (Business.Stake s : b.owners) {
            long share = pot * s.parts() / Business.WHOLE / 100 * 100;
            if (share <= 0) continue;
            switch (s.holder()) {
                case PLAYER -> b.ownersDue.merge(s.who(), share, Long::sum);
                case FOLK -> { VillageFolkEntity f = Homes.loaded(village, s.who()); if (f != null) f.earn((int) (share / 100)); else continue; }
                default -> Businesses.toTreasury(village, share);
            }
            paid += share;
        }
        b.till -= paid;
        Business.Day d = b.today(Businesses.today(level));
        d.drawings += paid;
        b.allTime.drawings += paid;
        b.note(Businesses.today(level), "paid out " + Businesses.pence(paid) + " of profit by share");
        Businesses.dirty();
        return paid;
    }

    // ------------------------------------------------------------------ tests

    public static long payoutForTests(ServerLevel level, UUID village, Business b) {
        return payout(level, village, b);
    }

    public static String offerForTests(ServerPlayer from, UUID village, Business b, ServerPlayer to, int parts, int coins) {
        return offer(from, village, b, to, parts, coins);
    }

    public static String acceptForTests(ServerLevel level, ServerPlayer buyer, UUID village, Business b) {
        List<Object[]> o = offersFor(village, buyer.getUUID());
        return o.isEmpty() ? "!none" : accept(level, buyer, village, (Business) o.get(0)[0], (int) o.get(0)[1]);
    }

    public static boolean canDecideForTests(UUID village, Business b, UUID player) {
        return canDecide(village, b, player);
    }
}
