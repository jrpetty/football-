package com.jrpetty.mcassistant.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * [econ-1c] A business's tools are its capital, not its day's costs.
 *
 * <p>A hunter's bow, a rancher's bucket and lead, a farmer's hoe, a woodcutter's axe and saw: bought once and used till
 * they wear out. Booked as the day's running costs, a homestead that bought its bucket on its first day read nine coins
 * short that day and its hand's wage looked unearned, and a firm that kitted itself out looked bankrupt in its first week.
 * Now what a business buys that lasts (anything that wears with use, a bucket, a lead, a saddle) goes into its kit, an
 * asset at what it cost, and a fourteenth of what the kit stands at is written off each morning as it wears: its books
 * carry the wear, its worth carries the kit. A tool handed back within the day is refunded off the kit.
 *
 * <p>Kept with the town's accounts (Accounts): each business's kit, pennies.
 */
public final class Capital {

    private Capital() {}

    /** The kit is written off over so many days, a share each morning. */
    static final int LIFE_DAYS = 14;

    /** Does this last (a tool, a weapon, armour, a bucket, a lead, a saddle): capital, not a day's cost? */
    public static boolean durable(ItemStack one) {
        if (one.isEmpty()) return false;
        if (one.isDamageableItem()) return true;
        return one.is(Items.BUCKET) || one.is(Items.LEAD) || one.is(Items.SADDLE);
    }

    /** By its key in the books. */
    public static boolean durable(String key) {
        return durable(Stockroom.sampleOf(key));
    }

    static CompoundTag book(UUID village) {
        return Accounts.sub(Accounts.town(village), "capital");
    }

    /** What its kit stands at now, pennies. */
    public static long value(UUID village, Business b) {
        return book(village).getLong(b.id);
    }

    /** Bought: into its kit. */
    static void bought(UUID village, Business b, long pence) {
        if (pence <= 0) return;
        Accounts.add(book(village), b.id, pence);
        Accounts.add(Accounts.sub(book(village), "all"), b.id, pence);
        Accounts.dirty();
    }

    /** Handed back within the day: off its kit (never below nought; what is over, off its costs as before). Returns what
     *  came off the kit. */
    static long refunded(UUID village, Business b, long pence) {
        long has = value(village, b), off = Math.min(has, Math.max(0, pence));
        if (off > 0) {
            book(village).putLong(b.id, has - off);
            Accounts.dirty();
        }
        return off;
    }

    /** Each business's morning: a fourteenth of its kit written off as wear, into its day's costs. */
    static void wear(UUID village, Business b, long day) {
        long has = value(village, b);
        if (has <= 0) return;
        long off = Math.max(1, Math.round(has / (double) LIFE_DAYS));
        off = Math.min(off, has);
        book(village).putLong(b.id, has - off);
        Business.Day d = b.today(day);
        d.other += off;
        b.allTime.other += off;
        Accounts.add(Accounts.sub(book(village), "worn"), b.id, off);
        Accounts.dirty();
    }

    /** All it ever put into its kit, pennies (the books' line). */
    public static long boughtAll(UUID village, Business b) {
        return Accounts.sub(book(village), "all").getLong(b.id);
    }
}
