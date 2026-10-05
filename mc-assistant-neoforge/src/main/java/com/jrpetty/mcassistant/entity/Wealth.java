package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;

/**
 * What a folk is worth, and what it is paid.
 *
 * <p>Every working folk is paid each morning out of the village's treasury (Market). The
 * wage is the trade's rate — a hand in the fields or the woods a coin a day, a miner, a guard
 * or a cook two, a smith, a tailor, a brewer or an enchanter three — times what the place is:
 * a hamlet pays the rate, a village half as much again, a town twice, a city two and a half
 * times and a capital three. On top: a coin at level ten and another at twenty-five, one for
 * the elder, and up to two for a good day's work, counted from what it actually did since it
 * was last paid. A tenth of it is the village's tax, and stays in the treasury (the poor pay
 * none). The coin comes from the village's own trade (market days, traders, gold minted into
 * coin, the tax, the tithe, the rent): none of it out of thin air.
 *
 * <p>What it is worth is what it has saved (with its share of what its household has put by
 * toward buying its house: Homes), what it carries (valued at the market's
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
            case MINE, RANCH, GUARD, SMELT, COOK, SHOP, BEEKEEP, SCOUT, HUNT -> 2;
            case SMITH, TAILOR, BREW, ENCHANT, BANK -> 3;              // the banker too (Bank)
        };
    }

    /** The bonus for what it did since it was last paid: two for a hard day, one for a fair one. */
    public static int bonus(VillageFolkEntity f) {
        int done = f.deedsTotal() - f.paidDeeds();
        return done >= 40 ? 2 : done >= 12 ? 1 : 0;
    }

    /**
     * What the place is, for its wages, in tenths: a hamlet pays the trades' rates (10), a village
     * half as much again (15), a town twice (20), a city two and a half times (25), a capital
     * three times (30). A bigger place has more to sell and more to buy with.
     */
    public static int standing(@javax.annotation.Nullable java.util.UUID village) {
        if (village == null) return 10;
        return switch (Villages.rank(village)) {
            case HAMLET -> 10;
            case VILLAGE -> 15;
            case TOWN -> 20;
            case CITY -> 25;
            case CAPITAL -> 30;
        };
    }

    /** "half as much again", "twice"... the standing in words. */
    public static String standingWords(int tenths) {
        return switch (tenths) {
            case 10 -> "the trades' own rates";
            case 15 -> "half as much again as a hamlet";
            case 20 -> "twice a hamlet's rates";
            case 25 -> "two and a half times a hamlet's rates";
            default -> (tenths / 10) + " times a hamlet's rates";
        };
    }

    /** A trade's day's wage in this village, before level, office and a good day. */
    public static int tradeWage(StationTask t, @javax.annotation.Nullable java.util.UUID village) {
        int b = baseWage(t);
        return b == 0 ? 0 : Math.max(1, (b * standing(village) + 5) / 10);
    }

    /**
     * Its share of what it made yesterday: a quarter of its output's worth, up to twice its trade's
     * rate. The hardest workers are the best paid, in every trade: a farmer whose field has grown
     * to twenty-five across earns more than one with a few rows.
     */
    public static int madeShare(VillageFolkEntity f) {
        int made = Economy.madeYesterday(f);
        return made <= 0 ? 0 : Math.min(2 * tradeWage(f.stationTask(), f.ownerId()), made / 4);
    }

    /** Today's wage: what its work earns, and a Haggler's twentieth more on top (FolkSkills). */
    public static int wage(VillageFolkEntity f) {
        if (f.isBaby() || f.stationTask() == StationTask.NONE) return 0;
        int w = earned(f);
        return w + FolkSkills.haggled(f, w);
    }

    /** Today's wage before any haggling: its trade's rate, its level, its office, a good day and what it made. */
    static int earned(VillageFolkEntity f) {
        int lv = f.veteranLevel();
        return tradeWage(f.stationTask(), f.ownerId()) + (lv >= 10 ? 1 : 0) + (lv >= 25 ? 1 : 0) + (f.isElder() ? 1 : 0) + bonus(f)
            + madeShare(f) + School.pay(f);                    // the village's teacher: a teacher's wage on top (School)
    }

    /** How today's wage is made up: "6 as a smith in a town, +1 at level ten, +2 for a hard day". */
    public static String breakdown(VillageFolkEntity f) {
        if (f.isBaby() || f.stationTask() == StationTask.NONE) return "no trade, no wage";
        int lv = f.veteranLevel(), b = bonus(f);
        String place = f.ownerId() == null ? "hamlet" : Villages.rank(f.ownerId()).label.replace("a ", "");
        StringBuilder sb = new StringBuilder();
        // The couriers are the storehouse's staff (Couriers), and paid as such: the same rate.
        sb.append(tradeWage(f.stationTask(), f.ownerId())).append(" as a ")
            .append(f.stationTask() == StationTask.HAUL ? "courier of the storehouse"
                : Workshop.isHand(f) ? "hand at the shop's bench"
                : f.stationTask().title.toLowerCase(java.util.Locale.ROOT)).append(" in a ").append(place);
        if (lv >= 10) sb.append(", +1 at level ten");
        if (lv >= 25) sb.append(", +1 at twenty-five");
        if (f.isElder()) sb.append(", +1 as the elder");
        int teaching = School.pay(f);
        if (teaching > 0) sb.append(", +").append(teaching).append(" for teaching the school");
        if (b > 0) sb.append(", +").append(b).append(b == 2 ? " for a hard day's work" : " for a fair day's work");
        int made = madeShare(f);
        if (made > 0) sb.append(", +").append(made).append(" for what it made yesterday");
        int haggled = FolkSkills.haggled(f, earned(f));
        if (haggled > 0) sb.append(", +").append(haggled).append(" haggled (its knack)");
        return sb.toString();
    }

    /** The working folk of a village, best paid first (then the most earned in all). */
    public static java.util.List<VillageFolkEntity> byWage(java.util.UUID village) {
        java.util.List<VillageFolkEntity> out = new java.util.ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.stationTask() != StationTask.NONE) out.add(f);
        }
        java.util.Map<VillageFolkEntity, Integer> w = new java.util.HashMap<>();
        for (VillageFolkEntity f : out) w.put(f, wage(f));
        out.sort((a, b) -> w.get(b).equals(w.get(a)) ? Integer.compare(b.earnedInAll(), a.earnedInAll()) : Integer.compare(w.get(b), w.get(a)));
        return out;
    }

    /** "Bramble the smith 9, Rook the miner 6, Fen the guard 6" — the best paid, for the board. */
    public static String bestPaid(java.util.UUID village, int n) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (VillageFolkEntity f : byWage(village)) {
            if (out.size() >= n) break;
            out.add(f.displayNameCap() + " the " + f.stationTask().title.toLowerCase(java.util.Locale.ROOT) + " " + wage(f));
        }
        return String.join(", ", out);
    }

    /** The pay scale in a village of this standing, a line to each rate. */
    public static java.util.List<String> payScale(java.util.UUID village) {
        java.util.Map<Integer, java.util.List<String>> byPay = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        for (StationTask t : StationTask.values()) {
            int w = tradeWage(t, village);
            if (w <= 0) continue;
            byPay.computeIfAbsent(w, k -> new java.util.ArrayList<>()).add(t.title.toLowerCase(java.util.Locale.ROOT));
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (var e : byPay.entrySet()) {
            out.add(capital(String.join(", ", e.getValue())) + ": " + e.getKey() + (e.getKey() == 1 ? " coin" : " coins") + " a day");
        }
        return out;
    }

    /**
     * The village's wages, on a page (the journal's Wages page, /village wages): what the place
     * pays each trade, then everybody who works, best paid first, with what its wage is made of,
     * what it has earned in all and what it is worth; then the richest.
     */
    public static String wagesPage(net.minecraft.server.level.ServerLevel level, Villages.Village v) {
        java.util.UUID id = v.id();
        StringBuilder sb = new StringBuilder();
        int st = standing(id);
        sb.append("Pay in ").append(Villages.rank(id).label).append(": ").append(standingWords(st)).append(".\n");
        for (String line : payScale(id)) sb.append(line).append(".\n");
        sb.append("On top: a coin at level ten and another at twenty-five, one for the elder, and up to two for a hard day's work.\n");
        sb.append("A tenth of every wage stays in the treasury as the village's tax (the odd part of a coin carried to the next payday), "
            + "while the treasury holds less than a week's wages; the poor pay none"
            + (Market.taxing(id) ? "" : ". The treasury holds a week's wages now: no tax is taken") + ".\n");
        int bill = Market.wageBill(id), coins = com.jrpetty.mcassistant.village.Ledger.coins(id);
        int purses = 0;
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f) purses += f.purse();
        sb.append("Treasury: ").append(coins).append(" coins; ").append(purses).append(" in the folk's purses; the day's wages come to ")
            .append(bill).append(".\n");
        int share = Market.lastShare(id);
        if (share >= 0 && share < 100) sb.append("Short of coin: the last wages were paid at ").append(share).append(" in the hundred.\n");
        sb.append("\n");
        java.util.List<VillageFolkEntity> ranked = byWage(id);
        sb.append("Best paid: ").append(ranked.isEmpty() ? "nobody works here yet" : ranked.size() + " at work").append(".\n");
        int i = 0;
        for (VillageFolkEntity f : ranked) {
            if (++i > 40) { sb.append("... and ").append(ranked.size() - 40).append(" more.\n"); break; }
            sb.append(i).append(". ").append(f.displayNameCap()).append(", ").append(f.stationTask().title.toLowerCase(java.util.Locale.ROOT))
                .append(" L").append(f.veteranLevel()).append(" — ").append(wage(f)).append(" a day (").append(breakdown(f))
                .append("); ").append(f.earnedInAll()).append(" earned in all, worth ").append(worth(f)).append(".\n");
        }
        java.util.List<VillageFolkEntity> rich = new java.util.ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f && !f.isBaby()) rich.add(f);
        java.util.Map<VillageFolkEntity, Integer> worth = new java.util.HashMap<>();
        for (VillageFolkEntity f : rich) worth.put(f, worth(f));
        rich.sort((a, b) -> Integer.compare(worth.get(b), worth.get(a)));
        if (!rich.isEmpty()) {
            sb.append("\nRichest: ");
            java.util.List<String> top = new java.util.ArrayList<>();
            for (int k = 0; k < Math.min(5, rich.size()); k++) {
                top.add(rich.get(k).displayNameCap() + " (" + tier(worth.get(rich.get(k))).label + ", " + worth.get(rich.get(k)) + ")");
            }
            sb.append(String.join(", ", top)).append(".");
        }
        return sb.toString();
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
        return Prices.of(s);
    }

    /** Its net worth, in coin: its loose money (its purse), its share of what its household has put by
     *  toward its house, its share of a house it owns, its belongings and the comforts of home. */
    public static int worth(VillageFolkEntity f) {
        return f.purse() + Homes.savedShare(f) + Homes.ownedShare(f) + belongings(f) + f.comforts() * 3
            + Bank.worthOf(f);                                         // its savings at the bank, less what it owes on its house
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
        int house = Homes.savedShare(f);
        int owned = Homes.ownedShare(f);
        return capital(t.label) + " — worth " + w + " coins: " + f.purse() + " loose" + (house > 0 ? ", " + house + " put by toward the house" : "")
            + (owned > 0 ? ", " + owned + " in the house it owns" : "")
            + Bank.worthWords(f)
            + ", things worth " + belongings(f)
            + (f.comforts() > 0 ? ", " + f.comforts() + (f.comforts() == 1 ? " comfort" : " comforts") + " at home" : "")
            + ". " + (wage > 0 ? "Paid " + wage + (wage == 1 ? " coin" : " coins") + " a day (" + breakdown(f) + "); "
                + f.earnedInAll() + " earned in all." : "No wage yet: no trade.");
    }

    /** "Who earns the most round here?" — the best paid, as a folk would put it. */
    public static String whoEarns(VillageFolkEntity f) {
        java.util.UUID id = f.ownerId();
        if (id == null) return "I couldn't tell you. I'm not from round here.";
        java.util.List<VillageFolkEntity> ranked = byWage(id);
        if (ranked.isEmpty()) return "Nobody's on a wage yet. We're only just starting out.";
        VillageFolkEntity top = ranked.get(0);
        String trade = top.stationTask().title.toLowerCase(java.util.Locale.ROOT);
        String first = top == f ? "Me, as it happens — " + wage(f) + " a day as a " + trade + ". I've earned it, mind."
            : top.displayNameCap() + ", the " + trade + ": " + wage(top) + " a day. "
              + (top.isElder() ? "The elder's pay, on top of the trade." : top.veteranLevel() >= 25 ? "Been at it longer than anyone."
                 : baseWage(top.stationTask()) >= 3 ? "Skilled work pays." : "Works harder than the rest of us put together.");
        String place = Villages.rank(id).label;
        String scale = " In " + place + " like this a field hand gets " + tradeWage(StationTask.FARM, id) + ", a miner "
            + tradeWage(StationTask.MINE, id) + " and a smith " + tradeWage(StationTask.SMITH, id) + ".";
        int rank = ranked.indexOf(f);
        String me = top == f || rank < 0 ? "" : " Me, I'm on " + wage(f) + ".";
        return first + scale + me + " It's all on the journal's wages page, if you want the lot.";
    }

    /** "How are you doing for money?" — in its own words. */
    public static String talk(VillageFolkEntity f) {
        return talk(f, "");
    }

    public static String talk(VillageFolkEntity f, String asked) {
        if (f.isBaby()) return "Money? I've got a shiny button. Does that count?";
        String q = " " + asked.toLowerCase(java.util.Locale.ROOT) + " ";
        if (q.contains("who earn") || q.contains("best paid") || q.contains("highest paid") || q.contains("paid the most")
                || q.contains("earns the most") || q.contains("earns most") || q.contains("richest") || q.contains("most money")) {
            return whoEarns(f);
        }
        int w = worth(f);
        Tier t = tier(w);
        StationTask job = f.stationTask();
        String place = f.ownerId() == null ? "here" : "in " + Villages.rank(f.ownerId()).label;
        int base = tradeWage(job, f.ownerId());
        String pay = job == StationTask.NONE ? "I've no trade yet, so no wage."
            : "As a " + job.title.toLowerCase(java.util.Locale.ROOT) + " " + place + " I get " + base + (base == 1 ? " coin" : " coins")
              + " a day from the treasury" + (wage(f) > base ? ", " + wage(f) + " with what I've earned on top" : "")
              + (!Market.taxing(f.ownerId()) ? "" : t == Tier.POOR ? ", and no tax while I've next to nothing" : ", less a tenth for the village's tax")
              + (f.earnedInAll() > 0 ? " — " + f.earnedInAll() + " all told since I started" : "") + ".";
        String how = switch (t) {
            case POOR -> "Truth is, I'm poor. " + f.purse() + (f.purse() == 1 ? " coin" : " coins") + " to my name.";
            case GETTING_BY -> "I get by. " + f.purse() + " coins put away.";
            case COMFORTABLE -> "Comfortable, thank you. " + f.purse() + " coins saved, and a few nice things at home.";
            case WELL_OFF -> "I've done well for myself: " + f.purse() + " coins saved and a home I'm proud of.";
            case WEALTHY -> "I'm one of the wealthiest in the village — " + w + " coins, all told. Hard work, mostly.";
        };
        int[] house = Homes.savingFor(f);
        String next = house != null ? " Every coin I can spare goes toward buying the house: " + house[0] + " of " + house[1] + " put by."
            : t == Tier.WEALTHY ? "" : " Next I'm saving for " + (f.comforts() < t.comforts ? "something nice for my home." : "a rainy day.");
        return pay + " " + how + next;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
