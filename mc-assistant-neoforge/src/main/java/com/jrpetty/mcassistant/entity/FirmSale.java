package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [wpb] Selling a whole firm. Its owner (the majority, or the managing partner: Partners.canDecide) lists it at an asking
 * price, shown beside what it is worth (Enterprise.equity: its stock, till and kit, less what it owes). Offers come in over
 * the days that follow: from folk with the means and the nerve (Temperament), a fifth either side of the ask, and from
 * players at the asking price on their Your businesses page. Each offer stands three days. Accepting one: the buyer pays,
 * each owner gets their share of the price by their stake (in hand if online, else waiting as drawings), the stakes pass
 * to the buyer whole, and its premises go with it (Premises.transferWithFirm, WP-C's seam). Kept in the firm's own record.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class FirmSale {

    private FirmSale() {}

    static final int OFFER_DAYS = 3;

    @Nullable
    static CompoundTag sale(UUID village, Business b) {
        CompoundTag r = Enterprise.rec(village, b.id);
        return r == null || !r.contains("sale") ? null : r.getCompound("sale");
    }

    /** What it is worth, pennies: its equity, never under nothing. */
    static long worth(UUID village, Business b) {
        return Math.max(0, Enterprise.equity(village, b));
    }

    static String list(ServerLevel level, ServerPlayer p, UUID village, Business b, long askPence) {
        if (!Partners.canDecide(village, b, p.getUUID())) return "!" + Partners.whyNot(village, b);
        CompoundTag r = Enterprise.rec(village, b.id);
        if (r == null) return "!Its record is missing.";
        if (r.contains("petition")) return "!It is before the court: rescue it first, or it is sold by the receiver.";
        CompoundTag s = new CompoundTag();
        s.putLong("ask", Math.max(100, askPence));
        s.putLong("listed", Businesses.today(level));
        s.put("offers", new ListTag());
        r.put("sale", s);
        b.note(Businesses.today(level), "put up for sale at " + Businesses.pence(askPence) + " by its owner");
        Accounts.dirty();
        Villages.tell(village, level.getDayTime() / 24000L, b.name + " is up for sale, asking " + Businesses.pence(askPence));
        return b.name + " is listed at " + Businesses.pence(askPence) + " (worth about " + Businesses.pence(worth(village, b))
            + "). Offers come in over the next days and show here; nothing is sold till you accept one.";
    }

    static String withdraw(ServerPlayer p, UUID village, Business b) {
        CompoundTag r = Enterprise.rec(village, b.id);
        if (r == null || !r.contains("sale")) return "!It isn't for sale.";
        if (!Partners.canDecide(village, b, p.getUUID())) return "!" + Partners.whyNot(village, b);
        r.remove("sale");
        Accounts.dirty();
        return b.name + " is off the market.";
    }

    /** A player's offer at the asking price. */
    static String playerOffer(ServerLevel level, ServerPlayer buyer, UUID village, Business b) {
        CompoundTag s = sale(village, b);
        if (s == null) return "!It isn't for sale.";
        if (b.ownedBy(buyer.getUUID())) return "!It's yours already.";
        long ask = s.getLong("ask");
        if (Market.coinsHeld(buyer) * 100L < ask) return "!The asking price is " + Businesses.pence(ask) + "; you carry " + Market.coinsHeld(buyer) + "c.";
        addOffer(s, buyer.getUUID(), buyer.getGameProfile().getName(), "player", ask, Businesses.today(level));
        Accounts.dirty();
        return "Your offer of " + Businesses.pence(ask) + " for " + b.name + " is with its owner. You pay only if they accept.";
    }

    static void addOffer(CompoundTag s, UUID who, String name, String kind, long price, long day) {
        ListTag l = s.getList("offers", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) if (l.getCompound(i).getUUID("who").equals(who)) { l.remove(i); break; }
        CompoundTag o = new CompoundTag();
        o.putUUID("who", who);
        o.putString("name", name);
        o.putString("kind", kind);
        o.putLong("price", price);
        o.putLong("day", day);
        l.add(o);
        s.put("offers", l);
    }

    /** Accept the offer at this index: paid, shared out by stake, the firm the buyer's. What it says. */
    static String accept(ServerLevel level, ServerPlayer p, UUID village, Business b, int i) {
        CompoundTag r = Enterprise.rec(village, b.id);
        CompoundTag s = sale(village, b);
        if (r == null || s == null) return "!It isn't for sale.";
        if (!Partners.canDecide(village, b, p.getUUID())) return "!" + Partners.whyNot(village, b);
        ListTag l = s.getList("offers", Tag.TAG_COMPOUND);
        if (i < 0 || i >= l.size()) return "!That offer has gone.";
        CompoundTag o = l.getCompound(i);
        long price = o.getLong("price");
        int coins = (int) (price / 100);
        UUID who = o.getUUID("who");
        String name = o.getString("name");
        boolean player = o.getString("kind").equals("player");
        if (player) {
            ServerPlayer buyer = level.getServer().getPlayerList().getPlayer(who);
            if (buyer == null) return "!" + name + " isn't here to pay: accept when they're about.";
            if (Market.coinsHeld(buyer) < coins) { l.remove(i); return "!" + name + " can't pay the price any more: the offer is withdrawn."; }
            Market.payOut(buyer, coins);
            CoinCensus.playerIn(village, coins, buyer, PlayerFinance.Cat.FEES, "bought " + b.name);
        } else {
            VillageFolkEntity f = Homes.loaded(village, who);
            if (f == null || !f.spend(coins)) { l.remove(i); return "!" + name + " can't pay now: the offer is withdrawn."; }
        }
        List<Business.Stake> old = new ArrayList<>(b.owners);
        for (Business.Stake st : old) {
            int share = (int) ((long) coins * st.parts() / Business.WHOLE);
            if (share <= 0) continue;
            if (st.holder() == Business.Holder.PLAYER && st.who() != null) {
                ServerPlayer seller = level.getServer().getPlayerList().getPlayer(st.who());
                if (seller != null) {
                    Earnings.giveCoins(seller, share);
                    CoinCensus.playerOut(village, share, seller, PlayerFinance.Cat.DIVIDENDS, "sold " + b.name);
                } else b.ownersDue.merge(st.who(), share * 100L, Long::sum);
            } else if (st.holder() == Business.Holder.FOLK && st.who() != null) {
                VillageFolkEntity f = Homes.loaded(village, st.who());
                if (f != null) f.earn(share);
            } else Businesses.toTreasury(village, share * 100L);
        }
        b.owners.clear();
        b.owners.add(new Business.Stake(player ? Business.Holder.PLAYER : Business.Holder.FOLK, who, name, Business.WHOLE));
        if (!player) r.putUUID("founder", who);
        else r.remove("founder");
        r.remove("sale");
        r.remove("managing");
        long day = Businesses.today(level);
        b.note(day, "sold whole to " + name + " for " + Businesses.pence(price));
        Premises.transferWithFirm(level, village, b, p.getUUID(), who);
        Accounts.dirty();
        Businesses.dirty();
        Villages.tell(village, level.getDayTime() / 24000L, b.name + " was sold to " + name + " for " + coins + " coins");
        return b.name + " is sold to " + name + " for " + coins + "c, shared among its owners by their stakes. It's theirs now: its staff and books go with it.";
    }

    /** Each morning: folk with the means look at the firms for sale; old offers lapse. */
    static void morning(ServerLevel level, UUID village, long day) {
        for (Business b : Enterprise.open(village)) {
            CompoundTag s = sale(village, b);
            if (s == null) continue;
            ListTag l = s.getList("offers", Tag.TAG_COMPOUND);
            for (int i = l.size() - 1; i >= 0; i--) if (day - l.getCompound(i).getLong("day") > OFFER_DAYS) l.remove(i);
            long ask = s.getLong("ask");
            VillageFolkEntity best = null;
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (!(a instanceof VillageFolkEntity f) || f.isBaby() || b.ownedBy(f.getUUID()) || !FirmBranches.owned(village, f.getUUID()).isEmpty()) continue;
                if (Enterprise.means(f) * 100L < ask * 4 / 5 || Temperament.drive(f) < 0) continue;
                if (best == null || Enterprise.means(f) > Enterprise.means(best)) best = f;
            }
            if (best != null && (day + best.getUUID().getLeastSignificantBits()) % 2 == 0) {
                double k = 0.85 + Math.floorMod(best.getUUID().hashCode() + day, 21) / 100.0;   // 0.85 .. 1.05 of the ask
                long price = Math.min(Enterprise.means(best) * 100L, Math.round(ask * k));
                addOffer(s, best.getUUID(), best.displayNameCap(), "folk", price, day);
            }
            s.put("offers", l);
        }
        Accounts.dirty();
    }

    private static long lastDay = -1;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 200 != 113) return;
        ServerLevel level = e.getServer().overworld();
        long day = level.getDayTime() / 24000L;
        if (day == lastDay) return;
        lastDay = day;
        for (Villages.Village v : Villages.every()) {
            try {
                morning(level, v.id(), day);
            } catch (RuntimeException ex) {
                com.mojang.logging.LogUtils.getLogger().warn("[wpb] firm sales failed in {}", v.id(), ex);
            }
        }
    }

    // ------------------------------------------------------------------ tests

    public static String listForTests(ServerLevel level, ServerPlayer p, UUID village, Business b, long ask) {
        return list(level, p, village, b, ask);
    }

    public static void offerForTests(UUID village, Business b, VillageFolkEntity f, long price, long day) {
        CompoundTag s = sale(village, b);
        if (s != null) addOffer(s, f.getUUID(), f.displayNameCap(), "folk", price, day);
    }

    public static String acceptForTests(ServerLevel level, ServerPlayer p, UUID village, Business b, int i) {
        return accept(level, p, village, b, i);
    }
}
