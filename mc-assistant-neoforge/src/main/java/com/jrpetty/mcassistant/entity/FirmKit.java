package com.jrpetty.mcassistant.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * [econ-1d] A closing firm's kit sold, not written off.
 *
 * <p>A business's tools are its capital (Capital): the bows, buckets, axes and hoes its hands use, bought at the stores and
 * carried in their packs. A bankrupt firm's kit used to be written off with its books while its hands walked away with
 * the tools. Now the receiver (or the owner winding it up) takes the kit back from the firm's hands and sells it to the
 * market at its bid, a worn tool for as much less as it is worn, and the proceeds go into the till before the creditors
 * are paid (or the owners, for a firm wound up by choice). What the kit fetched under what its books carried it at is a
 * loss in its books; the rest of the kit's book value is written off as before. Only so much kit is taken as the firm
 * bought (its kit's book value at what the market would sell it for), so a hand keeps the tools its own household bought.
 */
public final class FirmKit {

    private FirmKit() {}

    /** The kit sold to the market: tools out of the firm's hands' packs into the stores, the bid into the till. Returns
     *  the pennies it fetched. */
    static long sell(ServerLevel level, UUID village, Business b, long day) {
        long book = Capital.value(village, b);
        Villages.Village v = Villages.get(village);
        if (book <= 0 || v == null) return 0;
        Set<VillageFolkEntity> who = new LinkedHashSet<>(Enterprise.hands(village, b));
        VillageFolkEntity sole = Enterprise.soleTrader(village, b);
        if (sole != null) who.add(sole);
        boolean live = MarketMaker.live(village);
        long fetched = 0, worth = 0;
        int pieces = 0;
        List<String> what = new ArrayList<>();
        for (VillageFolkEntity f : who) {
            var inv = f.getInventoryItems();
            for (int i = 0; i < inv.size() && worth < book; i++) {
                ItemStack s = inv.get(i);
                if (s.isEmpty() || !Capital.durable(s) || s.isEnchanted() || s.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) continue;
                ItemStack one = s.copyWithCount(1);
                double left = s.isDamageableItem() && s.getMaxDamage() > 0 ? 1.0 - s.getDamageValue() / (double) s.getMaxDamage() : 1.0;
                double ask = live ? MarketMaker.ask(level, village, one) : Prices.each(one.getItem());
                double bid = live ? MarketMaker.bid(level, village, one) : ask * 0.8;
                long p = Math.max(0, Math.round(bid * left * 100) * s.getCount());
                worth += Math.round(ask * 100) * s.getCount();
                ItemStack out = s.copy();
                inv.set(i, ItemStack.EMPTY);
                Crafts.store(level, v, out);
                long got = live ? MarketMaker.cashOut(village, p) : Businesses.fromTreasury(village, p);
                b.till += got;
                fetched += got;
                pieces += out.getCount();
                if (live && p > 0) {
                    MarketMaker.book(village, Stockroom.key(out), out.getCount(), p, true);
                    Businesses.storesIn(village, one, out.getCount(), p);
                }
                if (what.size() < 4) what.add(Bench.words(out.getItem(), out.getCount()).toLowerCase(Locale.ROOT));
            }
        }
        // The kit off its books: what it fetched against what it stood at, the shortfall a loss.
        Capital.refunded(village, b, book);
        long loss = Math.max(0, book - fetched);
        if (fetched > 0) {
            Business.Day d = b.today(day);
            d.losses += loss;
            b.allTime.losses += loss;
            b.note(day, "its kit sold to the market: " + String.join(", ", what) + (pieces > what.size() ? " and more" : "") + ", for "
                + Businesses.pence(fetched) + " (it stood at " + Businesses.pence(book) + ")");
            Accounts.add(Accounts.sub(Enterprise.data(village), "kitSold"), "pence", fetched);
        }
        Businesses.dirty();
        Accounts.dirty();
        return fetched;
    }
}
