package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;

/**
 * What a folk is worth, and what it is paid.
 *
 * <p>Every working folk is paid each morning out of the village's treasury (Market), and
 * the wage is the trade's: a hand in the fields or the woods earns a coin a day, a miner,
 * a guard or a cook two, a smith, a tailor, a brewer or an enchanter three; a coin more at
 * level ten and again at twenty-five, one more for the elder — and up to two more for a
 * good day's work, counted from what it actually did since it was last paid. The coin
 * comes from the village's own trade (market days, traders, gold minted into coin): none
 * of it out of thin air.
 *
 * <p>What it is worth is what it has saved, what it carries (valued at the market's
 * prices), and the comforts it has bought for its home. That makes it poor, getting by,
 * comfortable, well off or wealthy — and it shows: the patched coat, the belt, the collar
 * and buttons, the gold chain (client/FolkRenderer), and a home with a rug, a lantern,
 * flowers or a bookshelf in it, bought and carried home with its own savings.
 */
public final class Wealth {

    private Wealth() {}

    public enum Tier {
        POOR("poor", 0), GETTING_BY("getting by", 0), COMFORTABLE("comfortable", 2), WELL_OFF("well off", 4), WEALTHY("wealthy", 7);

        public final String label;
        /** How many comforts a home of this standing runs to. */
        public final int comforts;

        Tier(String label, int comforts) {
            this.label = label;
            this.comforts = comforts;
        }
    }

    /** What a trade pays a day, before level, office and a good day. */
    public static int baseWage(StationTask t) {
        return switch (t) {
            case NONE -> 0;
            case FARM, WOOD, FISH, HAUL, STORE -> 1;
            case MINE, RANCH, GUARD, SMELT, COOK, SHOP, BEEKEEP -> 2;
            case SMITH, TAILOR, BREW, ENCHANT -> 3;
        };
    }

    /** The bonus for what it did since it was last paid: two for a hard day, one for a fair one. */
    public static int bonus(VillageFolkEntity f) {
        int done = f.deedsTotal() - f.paidDeeds();
        return done >= 40 ? 2 : done >= 12 ? 1 : 0;
    }

    /** Today's wage. */
    public static int wage(VillageFolkEntity f) {
        if (f.isBaby() || f.stationTask() == StationTask.NONE) return 0;
        int lv = f.veteranLevel();
        return baseWage(f.stationTask()) + (lv >= 10 ? 1 : 0) + (lv >= 25 ? 1 : 0) + (f.isElder() ? 1 : 0) + bonus(f);
    }

    /** What it carries, at the market's prices (its tools by their metal). */
    public static int belongings(VillageFolkEntity f) {
        double sum = 0;
        for (ItemStack s : f.getInventoryItems()) sum += value(s);
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            sum += value(f.getItemBySlot(slot));
        }
        return (int) Math.round(sum);
    }

    static double value(ItemStack s) {
        if (s.isEmpty()) return 0;
        if (s.getItem() instanceof TieredItem t) {
            double each = t.getTier() == Tiers.WOOD ? 0.5 : t.getTier() == Tiers.STONE ? 1 : t.getTier() == Tiers.IRON ? 4
                : t.getTier() == Tiers.GOLD ? 5 : t.getTier() == Tiers.DIAMOND ? 15 : t.getTier() == Tiers.NETHERITE ? 40 : 2;
            return each * s.getCount();
        }
        if (s.is(net.minecraft.world.item.Items.GOLD_INGOT)) return Market.COINS_PER_GOLD * s.getCount();
        if (s.is(net.minecraft.world.item.Items.DIAMOND)) return 12.0 * s.getCount();
        if (s.is(net.minecraft.world.item.Items.EMERALD)) return 4.0 * s.getCount();
        if (s.is(net.minecraft.world.item.Items.IRON_INGOT)) return 1.5 * s.getCount();
        for (Market.Good g : Market.GOODS) {
            if (g.what().test(s)) return g.value() * s.getCount();
        }
        return 0.02 * s.getCount();
    }

    /** Savings, belongings and the comforts of home, in coin. */
    public static int worth(VillageFolkEntity f) {
        return f.purse() + belongings(f) + f.comforts() * 3;
    }

    public static Tier tier(int worth) {
        return worth >= 250 ? Tier.WEALTHY : worth >= 90 ? Tier.WELL_OFF : worth >= 30 ? Tier.COMFORTABLE
            : worth >= 8 ? Tier.GETTING_BY : Tier.POOR;
    }

    public static Tier tier(VillageFolkEntity f) {
        return f.isBaby() ? Tier.GETTING_BY : tier(worth(f));
    }

    /** Its card line: "Comfortable — 42 coins saved, things worth 18, 2 comforts at home. Paid 3 a day." */
    public static String line(VillageFolkEntity f) {
        if (f.isBaby()) return "A child: its family keeps it.";
        int w = worth(f);
        Tier t = tier(w);
        int wage = wage(f);
        return capital(t.label) + " — worth " + w + " coins: " + f.purse() + " saved, things worth " + belongings(f)
            + (f.comforts() > 0 ? ", " + f.comforts() + (f.comforts() == 1 ? " comfort" : " comforts") + " at home" : "")
            + ". " + (wage > 0 ? "Paid " + wage + (wage == 1 ? " coin" : " coins") + " a day." : "No wage yet: no trade.");
    }

    /** "How are you doing for money?" — in its own words. */
    public static String talk(VillageFolkEntity f) {
        if (f.isBaby()) return "Money? I've got a shiny button. Does that count?";
        int w = worth(f);
        Tier t = tier(w);
        StationTask job = f.stationTask();
        String pay = job == StationTask.NONE ? "I've no trade yet, so no wage."
            : "As a " + job.title.toLowerCase(java.util.Locale.ROOT) + " I get " + baseWage(job) + (baseWage(job) == 1 ? " coin" : " coins")
              + " a day from the treasury" + (wage(f) > baseWage(job) ? ", " + wage(f) + " with what I've earned on top" : "") + ".";
        String how = switch (t) {
            case POOR -> "Truth is, I'm poor. " + f.purse() + (f.purse() == 1 ? " coin" : " coins") + " to my name.";
            case GETTING_BY -> "I get by. " + f.purse() + " coins put away.";
            case COMFORTABLE -> "Comfortable, thank you. " + f.purse() + " coins saved, and a few nice things at home.";
            case WELL_OFF -> "I've done well for myself: " + f.purse() + " coins saved and a home I'm proud of.";
            case WEALTHY -> "I'm one of the wealthiest in the village — " + w + " coins, all told. Hard work, mostly.";
        };
        String next = t == Tier.WEALTHY ? "" : " Next I'm saving for " + (f.comforts() < t.comforts ? "something nice for my home." : "a rainy day.");
        return pay + " " + how + next;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
