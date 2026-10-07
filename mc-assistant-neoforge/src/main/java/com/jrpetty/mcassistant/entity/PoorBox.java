package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [batchA] The poor box: a box in the chapel (in the meeting hall, until the town has a chapel) that the well-off
 * put a coin in, and the poor are helped out of.
 *
 * <p>Once a week each folk who is well off puts a coin in it, out of its own purse (a wealthy one two, and one with
 * a generous nature one more), walking there to do it in its own time on its own day of the week. The coin is
 * the box's: a balance of its own, kept with the town's ledger, never the treasury's. Out of it the poor are
 * helped two ways. On payday, a household that cannot make its rent has what it is short of paid out of the box
 * (into the treasury, as rent), as far as the box goes, before anything goes on its slate. And a poor folk with
 * next to nothing to eat walks to the café (or the shop, or the stores) and comes away with a loaf the box pays
 * for, at the counter's own price: the coin goes into the treasury and the loaf out of the stores, as any sale.
 * A loaf a day each, three a day for the town at most.
 *
 * <p>The town's books show the box on the Money page: what is in it, what went in this week and from how many,
 * and what came out for rent and bread.
 */
public final class PoorBox {

    private PoorBox() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The ledger's notes: the box's balance, this week's in and out, and all it has ever had in and paid out. */
    static final String BOX = "poorbox", WEEK = "poorbox.week", ALL = "poorbox.all";
    /** A folk keeps this much in its purse over what it gives. */
    static final int KEEPS = 10;
    /** The most loaves the box buys a town in a day. */
    static final int LOAVES_A_DAY = 3;
    /** Bread is fetched from this hour of the day. */
    static final long BREAD_FROM = 5000L;

    /** The week each folk last gave (by the day it was given its errand, so it is not given two). */
    private static final Map<UUID, Long> GAVE = new ConcurrentHashMap<>();
    /** The day each poor folk was last bought a loaf. */
    private static final Map<UUID, Long> FED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> BREAD_LOOKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> GIVING_LOOKED = new ConcurrentHashMap<>();

    static void resetForTests() {
        GAVE.clear();
        FED.clear();
        BREAD_LOOKED.clear();
        GIVING_LOOKED.clear();
    }

    // ------------------------------------------------------------------ the box's books

    /** What is in the box. */
    public static int coins(UUID village) {
        return parse(Ledger.note(village, BOX), 0);
    }

    static void coins(UUID village, int n) {
        Ledger.note(village, BOX, Integer.toString(Math.max(0, n)));
    }

    private static int parse(@Nullable String s, int or) {
        if (s == null || s.isEmpty()) return or;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return or;
        }
    }

    /** This week's book: in (coins, givers), out (rent, loaves, what the loaves cost). */
    static int[] week(UUID village, long day) {
        String s = Ledger.note(village, WEEK);
        int[] w = new int[6];
        w[0] = (int) (day / 7);
        if (s != null && !s.isEmpty()) {
            String[] p = s.split(",");
            if (p.length == 6 && parse(p[0], -1) == w[0]) for (int i = 1; i < 6; i++) w[i] = parse(p[i], 0);
        }
        return w;
    }

    static void week(UUID village, int[] w) {
        Ledger.note(village, WEEK, w[0] + "," + w[1] + "," + w[2] + "," + w[3] + "," + w[4] + "," + w[5]);
    }

    /** All it has ever had: in, out for rent, out for bread. */
    static int[] ever(UUID village) {
        String s = Ledger.note(village, ALL);
        int[] a = new int[3];
        if (s != null && !s.isEmpty()) {
            String[] p = s.split(",");
            for (int i = 0; i < 3 && i < p.length; i++) a[i] = parse(p[i], 0);
        }
        return a;
    }

    static void ever(UUID village, int in, int rent, int bread) {
        int[] a = ever(village);
        Ledger.note(village, ALL, (a[0] + in) + "," + (a[1] + rent) + "," + (a[2] + bread));
    }

    /** Where the box is: the chapel, else the meeting hall; null with neither. */
    @Nullable
    static BlockPos spot(UUID village) {
        BlockPos chapel = Villages.builtAt(village, "chapel");
        return chapel != null ? chapel : Villages.builtAt(village, "hall");
    }

    static String where(UUID village) {
        return Villages.builtAt(village, "chapel") != null ? "the chapel" : "the meeting hall";
    }

    // ------------------------------------------------------------------ the town's round

    /** From the neighbours' round (Neighbourly.tick): the week's givers sent with their coins, and bread for the hungry poor. */
    static void tick(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        if (GIVING_LOOKED.getOrDefault(id, Long.MIN_VALUE) != day && t >= 1000L && t < 11000L) {
            GIVING_LOOKED.put(id, day);
            givers(level, v, day);
        }
        if (BREAD_LOOKED.getOrDefault(id, Long.MIN_VALUE) != day && t >= BREAD_FROM && t < 11000L) {
            BREAD_LOOKED.put(id, day);
            bread(level, v, day);
        }
    }

    /** What a folk puts in the box a week: nothing unless it is well off; one, two if wealthy, one more if generous. */
    static int share(VillageFolkEntity f) {
        if (f.isBaby()) return 0;
        Wealth.Tier t = Wealth.tier(f);
        if (t != Wealth.Tier.WELL_OFF && t != Wealth.Tier.WEALTHY) return 0;
        int n = t == Wealth.Tier.WEALTHY ? 2 : 1;
        if (f.life().has(Social.Trait.GENEROUS)) n++;
        return f.purse() >= n + KEEPS ? n : 0;
    }

    /** The week's givers whose day it is (or has been): each sent to the box with its coin. Returns how many were sent. */
    static int givers(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        BlockPos box = spot(id);
        if (box == null) return 0;
        long week = day / 7;
        int sent = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || !f.isAlive() || f.isShowcase() || f.isHired()) continue;
            if (GAVE.getOrDefault(f.getUUID(), Long.MIN_VALUE) == week || Neighbourly.hasErrand(f)) continue;
            if (Math.floorMod(day, 7L) < Math.floorMod(f.getUUID().hashCode(), 7)) continue;     // its own day of the week, or after
            int n = share(f);
            if (n <= 0) continue;
            GAVE.put(f.getUUID(), week);
            Neighbourly.give(f, giving(level, v, f, box, n));
            sent++;
        }
        return sent;
    }

    /** The errand: to the box, and the coin in. */
    static Neighbourly.Errand giving(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos box, int n) {
        UUID id = v.id();
        long week = level.getDayTime() / 24000L / 7;
        Neighbourly.Errand e = new Neighbourly.Errand("poorbox", id, level.getGameTime());
        e.from = 1000L;
        e.to = 13800L;
        e.startBy = level.getGameTime() + 16000L;
        e.longest = 2400L;
        e.stop("taking " + (n == 1 ? "a coin" : n + " coins") + " to the poor box in " + where(id), box, null, 4.5, 40, false,
            (lv, g, er) -> er.notes.put("gave", give(lv, id, g, n)));
        e.end = (lv, g, er, done) -> {
            if (!(er.notes.get("gave") instanceof Integer k) || k <= 0) GAVE.remove(g.getUUID(), week);    // another day this week
        };
        return e;
    }

    /** Coins out of its purse into the box. Returns how many went in. */
    static int give(ServerLevel level, UUID village, VillageFolkEntity f, int n) {
        if (!f.spend(n)) return 0;
        coins(village, coins(village) + n);
        long day = level.getDayTime() / 24000L;
        int[] w = week(village, day);
        w[1] += n;
        w[2]++;
        week(village, w);
        ever(village, n, 0, 0);
        RandomSource r = f.getRandom();
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        f.persona().remember(day, "I put " + (n == 1 ? "a coin" : n + " coins") + " in the poor box", 2);
        Neighbourly.say(f, FolkTalk.pick(r, "A coin for the poor box. We've been lucky; not everybody has.",
            "There. Somebody'll eat tonight on that.", "For the box. It's little enough."));
        LOG.info("[MCA-POORBOX] {} put {} in the poor box of {}: {} in it now", f.displayNameCap(), n, Villages.name(village), coins(village));
        return n;
    }

    /** What a loaf costs at the counter today. */
    static int loafPrice(ServerLevel level, UUID village) {
        ItemStack loaf = new ItemStack(Items.BREAD);
        Market.Good g = Market.goodFor(loaf);
        int price = g == null ? 1 : Market.sellPrice(g, Market.stock(level, village, s -> s.is(Items.BREAD)), false);
        return Math.max(1, price / Math.max(1, g == null ? 1 : g.bundle()));
    }

    /** Where the poor fetch their bread: the café, else the shop, else the stores; and who sells it. */
    static Object[] counter(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos cafe = Villages.builtAt(id, "cafe");
        if (cafe != null && Cafe.open(id, "cafe")) return new Object[]{ cafe, Stockroom.Seller.CAFE, "the café" };
        BlockPos shop = Villages.builtAt(id, "shop");
        if (shop != null && Cafe.open(id, "shop")) return new Object[]{ shop, Stockroom.Seller.SHOP, "the shop" };
        return new Object[]{ Neighbourly.storesSpot(level, v), Stockroom.Seller.STORES, "the stores" };
    }

    /** Is it poor and going hungry: next to nothing in its purse and no more than a meal in its pack? */
    static boolean hungryPoor(VillageFolkEntity f) {
        return !f.isBaby() && Wealth.tier(f) == Wealth.Tier.POOR && f.countFood() <= 1;
    }

    /** Once a day: the hungry poor sent for a loaf the box pays for, while the box can. Returns how many were sent. */
    static int bread(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (spot(id) == null) return 0;
        int sent = 0, money = coins(id), price = loafPrice(level, id);
        if (Market.stock(level, id, s -> s.is(Items.BREAD)) <= 0) return 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (sent >= LOAVES_A_DAY || money < price * (sent + 1)) break;
            if (!(a instanceof VillageFolkEntity f) || !f.isAlive() || f.isShowcase() || f.isHired()) continue;
            if (!hungryPoor(f) || FED.getOrDefault(f.getUUID(), Long.MIN_VALUE) == day || Neighbourly.hasErrand(f)) continue;
            FED.put(f.getUUID(), day);
            Neighbourly.give(f, fetching(level, v, f));
            sent++;
        }
        return sent;
    }

    /** The errand: to the counter, and a loaf the box pays for. */
    static Neighbourly.Errand fetching(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        Object[] c = counter(level, v);
        Neighbourly.Errand e = new Neighbourly.Errand("bread", v.id(), level.getGameTime());
        e.from = BREAD_FROM;
        e.to = 13800L;
        e.startBy = level.getGameTime() + 8000L;
        e.longest = 2400L;
        e.stop("fetching a loaf from " + c[2] + ", paid for by the poor box", (BlockPos) c[0], null, 4.5, 30, false,
            (lv, g, er) -> buyLoaf(lv, v, g, (Stockroom.Seller) c[1], (String) c[2]));
        return e;
    }

    /** A loaf out of the stores into its pack, its price out of the box and into the treasury. True if it got one. */
    static boolean buyLoaf(ServerLevel level, Villages.Village v, VillageFolkEntity f, Stockroom.Seller seller, String where) {
        UUID id = v.id();
        int price = loafPrice(level, id);
        long day = level.getDayTime() / 24000L;
        if (coins(id) < price || !TownWork.take(level, v, s -> s.is(Items.BREAD), 1)) {
            Neighbourly.say(f, "No bread to be had today. Never mind.");
            return false;
        }
        coins(id, coins(id) - price);
        Ledger.addCoins(id, price);
        Economy.spentInTown(id, price);
        Stockroom.sold(level, id, seller, new ItemStack(Items.BREAD), 1, price);
        ItemStack left = f.insertGiven(new ItemStack(Items.BREAD));
        if (!left.isEmpty()) Crafts.store(level, v, left);
        int[] w = week(id, day);
        w[4]++;
        w[5] += price;
        week(id, w);
        ever(id, 0, 0, price);
        f.persona().remember(day, "the poor box bought me a loaf at " + where + " when I had nothing", 3);
        Neighbourly.say(f, FolkTalk.pick(f.getRandom(), "The poor box paid for a loaf. Bless whoever put the coin in.",
            "Bread, and I hadn't a coin for it. There are good people here."));
        LOG.info("[MCA-POORBOX] {} had a loaf at {} out of the poor box of {} ({}c): {} left in it", f.displayNameCap(), where,
            Villages.name(id), price, coins(id));
        return true;
    }

    /**
     * Payday (Homes.tenants): a household short of its rent has what it is short of paid out of the box, as far as
     * the box goes. Returns what the box paid (the caller books it as rent paid, into the treasury).
     */
    public static int towardRent(UUID village, List<VillageFolkEntity> household, int shortBy, long day) {
        if (shortBy <= 0) return 0;
        int have = coins(village);
        int pay = Math.min(shortBy, have);
        if (pay <= 0) return 0;
        coins(village, have - pay);
        int[] w = week(village, day);
        w[3] += pay;
        week(village, w);
        ever(village, 0, pay, 0);
        List<String> names = new ArrayList<>();
        for (VillageFolkEntity f : household) {
            if (f.isBaby()) continue;
            names.add(f.displayNameCap());
            f.persona().remember(day, "the poor box helped us with the rent", 3);
        }
        LOG.info("[MCA-POORBOX] the poor box of {} paid {} of {}'s rent: {} left in it", Villages.name(village), pay, names, coins(village));
        return pay;
    }

    // ------------------------------------------------------------------ what the player reads

    /** The box, for the books' Money page (inside Health.report's "care"). */
    public static CompoundTag report(UUID village, long day) {
        CompoundTag out = new CompoundTag();
        int[] w = week(village, day), a = ever(village);
        out.putBoolean("stands", spot(village) != null);
        out.putString("where", where(village));
        out.putInt("coins", coins(village));
        out.putInt("week_in", w[1]);
        out.putInt("week_givers", w[2]);
        out.putInt("week_rent", w[3]);
        out.putInt("week_loaves", w[4]);
        out.putInt("week_bread", w[5]);
        out.putInt("all_in", a[0]);
        out.putInt("all_rent", a[1]);
        out.putInt("all_bread", a[2]);
        return out;
    }

    /** One line, as the books and /village care say it. */
    public static String line(UUID village, long day) {
        if (spot(village) == null) return "The poor box: none yet (it goes in the meeting hall, or the chapel).";
        int[] w = week(village, day);
        return "The poor box in " + where(village) + ": " + coins(village) + "c in it. This week " + w[1] + "c given by " + w[2]
            + (w[2] == 1 ? " folk" : " folk") + "; out, " + w[3] + "c toward rent and " + w[4] + (w[4] == 1 ? " loaf" : " loaves")
            + " (" + w[5] + "c) for the hard-up.";
    }

    static List<String> lines(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        int[] a = ever(v.id());
        return List.of("  " + line(v.id(), day) + " In all: " + a[0] + "c in, " + a[1] + "c for rent, " + a[2] + "c for bread.");
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the week's givers sent now (whatever the day of the week). Returns how many. */
    public static int giversForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v == null) return 0;
        GAVE.clear();
        BlockPos box = spot(village);
        if (box == null) return 0;
        int sent = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || Neighbourly.hasErrand(f)) continue;
            int n = share(f);
            if (n <= 0) continue;
            GAVE.put(f.getUUID(), level.getDayTime() / 24000L / 7);
            Neighbourly.give(f, giving(level, v, f, box, n));
            sent++;
        }
        return sent;
    }

    /** Tests: the hungry poor sent for bread now. Returns how many. */
    public static int breadForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v == null) return 0;
        FED.clear();
        return bread(level, v, level.getDayTime() / 24000L);
    }

    public static void coinsForTests(UUID village, int n) {
        coins(village, n);
    }

    public static int[] weekForTests(UUID village, long day) {
        return week(village, day);
    }
}
