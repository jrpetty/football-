package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a village makes, what it earns and what it is worth.
 *
 * <ul>
 * <li><b>Output.</b> Everything a working folk brings home to the stores is the village's
 *     output, valued at what the market says it is worth: the farmer's wheat and bread, the
 *     woodcutter's logs, the miner's stone and ore, the smelter's iron, the fisher's catch,
 *     the hunter's meat and hides, the crafts' tools and beds and drinks. A carrier makes
 *     nothing: the load it fetches from a far worker is that worker's output, counted when it
 *     changes hands. Each kind is kept apart (food, timber, stone, ore and metal, animal goods,
 *     crafts), and so is each trade and each folk.</li>
 * <li><b>Revenue.</b> What it sells: the passing traders buy what the town makes (never more,
 *     in a day, than yesterday's output was worth: a busy town meets its wages, an idle one
 *     cannot), market day takes the surplus, players buy at the stalls, the café and the shop,
 *     a tenth of every wage stays in the treasury as the village's tax, the tithe brings some
 *     back from the purses, the tenants pay their rent, a household that has saved up buys its
 *     house, and the folk spend their own coin in town. And what it spends: the wages, a trade's
 *     kit, the drover, the wool for the beds.</li>
 * <li><b>Worth.</b> The treasury, everything in the stores at the market's worth, and what the
 *     folk have saved (in their purses, and put by toward their houses).</li>
 * </ul>
 * Each morning (Market.tick) the day before is closed: its output is kept for a week, so the
 * village can tell whether it is making more or less than it was.
 */
public final class Economy {

    private Economy() {}

    public enum Kind {
        FOOD("food"), TIMBER("timber"), STONE("stone"), ORE("ore and metal"), ANIMAL("wool, hides and honey"), CRAFT("crafts"),
        PLANT("plants and flowers");

        public final String word;

        Kind(String word) { this.word = word; }
    }

    /** One day's books. */
    static final class Day {
        final double[] kinds = new double[Kind.values().length];
        final Map<StationTask, Double> trades = new EnumMap<>(StationTask.class);
        final Map<UUID, Double> folk = new HashMap<>();
        final Map<UUID, String> names = new HashMap<>();
        /** Item by item: how many were made {@code [0]} and how many used up in making other things {@code [1]}. */
        final Map<String, int[]> items = new HashMap<>();
        /** Item by item, which trades made them. */
        final Map<String, Map<StationTask, Integer>> by = new HashMap<>();
        int sold, spent, tithe, wages, takings;
        /** Money in from the homes (Homes.payday): the tenants' rent, and the houses sold to the households that saved for them. */
        int rent, houses;
        /** Money back from the purses: the tenth of the wages kept as the village's tax (Market.payWages), and
         *  what the folk spent in town out of their own purses (the market's treats, the café, the shop, the
         *  tavern, the comforts of home, a child's bed). */
        int tax, town;

        double total() {
            double t = 0;
            for (double d : kinds) t += d;
            return t;
        }
    }

    private static final Map<UUID, Day> TODAY = new ConcurrentHashMap<>();
    private static final Map<UUID, Day> YESTERDAY = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> WORTH = new ConcurrentHashMap<>();

    public static void resetForTests() {
        TODAY.clear();
        YESTERDAY.clear();
        WORTH.clear();
        CRAFT_VILLAGE = null;
        CRAFT_NET.clear();
        GATHERED.clear();
        GIVEN.clear();
    }

    // ------------------------------------------------------------------ output

    /** What kind of goods this is, or null if it is not the village's output: by name, then by the
     *  price list (Prices: everything mined, grown, caught or made). */
    @Nullable
    public static Kind kindOf(ItemStack s) {
        Kind named = kindOfNamed(s);
        if (named != null || s.isEmpty() || s.is(Items.TORCH)) return named;
        return Prices.kindOf(s.getItem());
    }

    /** The kinds the village names outright. */
    @Nullable
    static Kind kindOfNamed(ItemStack s) {
        if (s.isEmpty()) return null;
        if (s.get(net.minecraft.core.component.DataComponents.FOOD) != null || s.is(Items.WHEAT) || s.is(Items.SUGAR_CANE)
                || s.is(Items.PUMPKIN) || s.is(Items.MELON) || s.is(Items.COCOA_BEANS) || s.is(Items.SUGAR)) return Kind.FOOD;
        if (s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS)) return Kind.TIMBER;
        if (s.is(Items.COBBLESTONE) || s.is(Items.STONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.ANDESITE)
                || s.is(Items.DIORITE) || s.is(Items.GRANITE) || s.is(Items.TUFF) || s.is(Items.STONE_BRICKS)
                || s.is(Items.SAND) || s.is(Items.GRAVEL) || s.is(Items.CLAY_BALL) || s.is(Items.BRICK) || s.is(Items.FLINT)) return Kind.STONE;
        if (s.is(Items.COAL) || s.is(Items.CHARCOAL) || s.is(Items.RAW_IRON) || s.is(Items.IRON_INGOT) || s.is(Items.RAW_COPPER)
                || s.is(Items.COPPER_INGOT) || s.is(Items.RAW_GOLD) || s.is(Items.GOLD_INGOT) || s.is(Items.DIAMOND)
                || s.is(Items.EMERALD) || s.is(Items.REDSTONE) || s.is(Items.LAPIS_LAZULI) || s.is(Items.OBSIDIAN)
                || s.is(Items.IRON_NUGGET) || s.is(Items.GOLD_NUGGET) || s.is(Items.QUARTZ)) return Kind.ORE;
        if (s.is(ItemTags.WOOL) || s.is(Items.LEATHER) || s.is(Items.FEATHER) || s.is(Items.EGG) || s.is(Items.HONEYCOMB)
                || s.is(Items.STRING) || s.is(Items.RABBIT_HIDE) || s.is(Items.INK_SAC) || s.is(Items.BONE)) return Kind.ANIMAL;
        if (s.isDamageableItem() || s.is(ItemTags.BEDS) || s.is(ItemTags.WOOL_CARPETS) || s.is(ItemTags.BANNERS)
                || s.is(Items.POTION) || s.is(Items.GLASS) || s.is(Items.GLASS_BOTTLE) || s.is(Items.BOOK) || s.is(Items.PAPER)
                || s.is(Items.HONEY_BOTTLE) || s.is(Items.ARROW) || s.is(Items.CAKE)) return Kind.CRAFT;
        return null;
    }

    /** What one of these is worth, as the village's output. */
    static double worthOf(ItemStack s) {
        Market.Good g = Market.goodFor(s);
        if (g != null) return g.value() * s.getCount();
        return Prices.of(s);
    }

    /** [sf] A fish as it comes out of the water. */
    static final java.util.function.Predicate<ItemStack> RAW_FISH = s -> s.is(Items.COD) || s.is(Items.SALMON)
        || s.is(Items.TROPICAL_FISH) || s.is(Items.PUFFERFISH);

    /**
     * A working folk brought this home to the stores (or handed it to the carrier that came out
     * for it): it is the village's output, and its own. Carriers and storekeepers make nothing
     * of their own, and what a folk was given out of the stores is not output when it goes back.
     */
    public static void produced(VillageFolkEntity f, ItemStack s) {
        UUID village = f.ownerId();
        StationTask trade = f.stationTask();
        if (village == null || s.isEmpty() || trade == StationTask.NONE || trade == StationTask.HAUL || trade == StationTask.STORE) return;
        // [sf] A raw fish came off a line, whoever brings it in: a fisher that gave up its water for the woods
        // banks the last of its catch as a woodcutter, and a woodcutter makes no food, so it went down as
        // nothing at all. It is the water's, booked as fish (Larder).
        if (RAW_FISH.test(s)) trade = StationTask.FISH;
        Kind k = kindOf(s);
        if (k == null || !makes(trade, k, s)) return;
        // What it was given (its kit) or fetched out of the stores, put back, was not made by anybody:
        // a founding party's sixteen loaves each went down as forty-eight loaves baked on day nought.
        int fresh = s.getCount() - spendGiven(f, s);
        if (fresh <= 0) return;
        if (fresh < s.getCount()) s = s.copyWithCount(fresh);
        if (k == Kind.FOOD || s.is(Items.WHEAT)) Leader.foodIn(village, s);     // the leader's food books
        if (k == Kind.FOOD || s.is(Items.WHEAT)) Larder.broughtIn(village, trade, s);   // [economy] and by where it came from
        if (k == Kind.FOOD || s.is(Items.WHEAT)) Festivals.broughtIn(f, trade, s);      // [batchB] the year's harvest, farmer by farmer
        // The books, item by item: what it gathered with its own hands was counted when it picked it up
        // (Economy.gathered); only what is new to the books goes in now (a smelter's ingots, a mason's bricks).
        tally(village, trade, s, s.getCount() - credit(f, s), true);
        double v = worthOf(s);
        if (v <= 0) return;
        Day d = TODAY.computeIfAbsent(village, x -> new Day());
        d.kinds[k.ordinal()] += v;
        d.trades.merge(trade, v, Double::sum);
        d.folk.merge(f.getUUID(), v, Double::sum);
        d.names.put(f.getUUID(), f.displayNameCap());
    }

    // ------------------------------------------------------------------ item by item

    /** An item's short name in the books: "oak_log" for the game's own, "mod:thing" for anything else's. */
    public static String id(ItemStack s) {
        net.minecraft.resources.ResourceLocation k = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
        return k.getNamespace().equals("minecraft") ? k.getPath() : k.toString();
    }

    /** So many of this made today (by this trade) or used up making something else. */
    static void tally(UUID village, @Nullable StationTask trade, ItemStack s, int n, boolean made) {
        if (village == null || s.isEmpty() || n <= 0) return;
        Day d = TODAY.computeIfAbsent(village, x -> new Day());
        String key = id(s);
        d.items.computeIfAbsent(key, x -> new int[2])[made ? 0 : 1] += n;
        if (made && trade != null) d.by.computeIfAbsent(key, x -> new EnumMap<>(StationTask.class)).merge(trade, n, Integer::sum);
    }

    /*
     * A maker's piece of work (Crafts.now): everything it takes out of the stores and puts back in
     * is reckoned up, item by item, and what is left over at the end is what it made (more went in
     * than came out) or used (more came out than went in). A lantern: an ingot out, nine nuggets in,
     * eight nuggets and a torch out, a lantern in — a lantern and a nugget made, an ingot and a torch
     * used. What was taken out and put back as it was cancels itself out.
     */
    @Nullable private static UUID CRAFT_VILLAGE;
    @Nullable private static StationTask CRAFT_TRADE;
    private static final Map<String, Integer> CRAFT_NET = new HashMap<>();
    private static final Map<String, ItemStack> CRAFT_KIND = new HashMap<>();

    /** A maker sets to work: from here, what goes in and out of the stores is its making. */
    public static void openCraft(UUID village, StationTask trade) {
        CRAFT_VILLAGE = village;
        CRAFT_TRADE = trade;
        CRAFT_NET.clear();
        CRAFT_KIND.clear();
    }

    /** The piece of work done: what it made and what it used, into the day's books. */
    public static void closeCraft() {
        UUID v = CRAFT_VILLAGE;
        CRAFT_VILLAGE = null;
        if (v == null) return;
        for (Map.Entry<String, Integer> e : CRAFT_NET.entrySet()) {
            ItemStack kind = CRAFT_KIND.get(e.getKey());
            if (kind == null || e.getValue() == 0) continue;
            tally(v, CRAFT_TRADE, kind, Math.abs(e.getValue()), e.getValue() > 0);
        }
        CRAFT_NET.clear();
        CRAFT_KIND.clear();
    }

    /** Into the stores (Market.intoStores). */
    static void storesIn(UUID village, ItemStack s) {
        if (CRAFT_VILLAGE == null || s.isEmpty() || !CRAFT_VILLAGE.equals(village)) return;
        String key = id(s);
        CRAFT_NET.merge(key, s.getCount(), Integer::sum);
        CRAFT_KIND.putIfAbsent(key, s.copyWithCount(1));
    }

    /** Out of the stores (TownWork.take, Crafts.takeOne). */
    static void storesOut(UUID village, ItemStack s, int n) {
        if (CRAFT_VILLAGE == null || s.isEmpty() || n <= 0 || !CRAFT_VILLAGE.equals(village)) return;
        String key = id(s);
        CRAFT_NET.merge(key, -n, Integer::sum);
        CRAFT_KIND.putIfAbsent(key, s.copyWithCount(1));
    }

    /**
     * Picked up off the ground by a working hand: a log from the tree it felled, the cobble from the
     * rock it broke, the wheat from its field, the fish it caught. That is the making, counted there and
     * then — a woodcutter that builds with the logs it felled never brings them to the stores, and its
     * timber was counted as nothing at all. What it later brings home of it is not counted twice.
     */
    public static void gathered(AssistantEntity a, ItemStack s, int n) {
        if (a instanceof VillageFolkEntity finder && n > 0) Museum.found(finder, s, n);    // a rare find, for the museum's labels
        if (!(a instanceof VillageFolkEntity f) || n <= 0 || s.isEmpty()) return;
        UUID village = f.ownerId();
        StationTask trade = f.stationTask();
        if (village == null || trade == StationTask.NONE || trade == StationTask.HAUL || trade == StationTask.STORE) return;
        Kind k = kindOf(s);
        if (k == null || !makes(trade, k, s)) return;
        tally(village, trade, s, n, true);
        GATHERED.computeIfAbsent(f.getUUID(), x -> new HashMap<>()).merge(id(s), n, (x, y) -> Math.min(256, x + y));
    }

    /** How many of these the hand already had counted when it picked them up (and the credit spent). */
    private static int credit(VillageFolkEntity f, ItemStack s) {
        Map<String, Integer> m = GATHERED.get(f.getUUID());
        if (m == null) return 0;
        String key = id(s);
        int have = m.getOrDefault(key, 0);
        int used = Math.min(have, s.getCount());
        if (have - used <= 0) m.remove(key); else m.put(key, have - used);
        return used;
    }

    /**
     * Into a hand's pack as something given or fetched, not made: its kit, the stores' goods it drew,
     * a caravan's load, a present. When it goes into the stores again it is not counted as made. Kept
     * two mornings (Economy.closeTheDay), so a loaf drawn and eaten does not stand against one baked
     * next week.
     */
    public static void given(AssistantEntity a, ItemStack s, int n) {
        if (!(a instanceof VillageFolkEntity) || n <= 0 || s.isEmpty()) return;
        GIVEN.computeIfAbsent(a.getUUID(), x -> new ConcurrentHashMap<>())
            .merge(id(s), new int[]{ n, 0 }, (x, y) -> new int[]{ Math.min(4096, x[0] + y[0]), 0 });
    }

    /** How many of these were given to the hand rather than made (and the credit spent). */
    private static int spendGiven(VillageFolkEntity f, ItemStack s) {
        Map<String, int[]> m = GIVEN.get(f.getUUID());
        if (m == null) return 0;
        String key = id(s);
        int[] have = m.get(key);
        if (have == null) return 0;
        int used = Math.min(have[0], s.getCount());
        if (have[0] - used <= 0) m.remove(key); else m.put(key, new int[]{ have[0] - used, have[1] });
        return used;
    }

    /** So many of what a hand was given used up (eaten): they will not be put back. */
    public static void usedGiven(AssistantEntity a, ItemStack s, int n) {
        Map<String, int[]> m = GIVEN.get(a.getUUID());
        if (m == null || n <= 0 || s.isEmpty()) return;
        String key = id(s);
        int[] have = m.get(key);
        if (have == null) return;
        if (have[0] - n <= 0) m.remove(key); else m.put(key, new int[]{ have[0] - n, have[1] });
    }

    /** The morning: what the village's hands were given two mornings ago and never put back is forgotten. */
    private static void ageGiven(UUID village) {
        for (AssistantEntity a : Villages.folkOf(village)) {
            Map<String, int[]> m = GIVEN.get(a.getUUID());
            if (m == null) continue;
            m.replaceAll((k, v) -> new int[]{ v[0], v[1] + 1 });
            m.values().removeIf(v -> v[1] > 1);
            if (m.isEmpty()) GIVEN.remove(a.getUUID());
        }
    }

    /** What each hand was given and has not put back, by the hand: {how many, mornings since}. */
    private static final Map<UUID, Map<String, int[]>> GIVEN = new ConcurrentHashMap<>();

    /** What each hand picked up and has had counted, not yet brought home (by the hand). */
    private static final Map<UUID, Map<String, Integer>> GATHERED = new ConcurrentHashMap<>();

    /** Yesterday, item by item: {made, used}. */
    static Map<String, int[]> yesterdayItems(UUID village) {
        Day d = YESTERDAY.get(village);
        return d == null ? Map.of() : d.items;
    }

    /** Yesterday, item by item, the trades that made it. */
    static Map<String, Map<StationTask, Integer>> yesterdayMakers(UUID village) {
        Day d = YESTERDAY.get(village);
        return d == null ? Map.of() : d.by;
    }

    /** Tests: today's money so far, {wages, tax, rent, tithe, spent in town, bought in}. */
    public static int[] moneyTodayForTests(UUID village) {
        Day d = TODAY.get(village);
        return d == null ? new int[6] : new int[]{ d.wages, d.tax, d.rent, d.tithe, d.town, d.spent };
    }

    /** Tests: today's tally of an item so far, {made, used}. */
    public static int[] todayForTests(UUID village, String id) {
        Day d = TODAY.get(village);
        int[] t = d == null ? null : d.items.get(id);
        return t == null ? new int[2] : t.clone();
    }

    /**
     * Is this the trade's own work? A farmer's bread is (it bakes), its stone is not (that was
     * the ground it cleared); a miner's coal and stone are, the bread in its pack is not.
     */
    static boolean makes(StationTask t, Kind k, ItemStack s) {
        return switch (t) {
            case FARM -> k == Kind.FOOD || k == Kind.PLANT;
            case WOOD -> k == Kind.TIMBER || k == Kind.PLANT || s.is(Items.APPLE) || s.is(Items.STICK);
            case MINE -> k == Kind.STONE || k == Kind.ORE;
            case SMELT -> k == Kind.ORE || k == Kind.CRAFT || k == Kind.FOOD || s.is(Items.STONE);
            case RANCH -> k == Kind.ANIMAL || k == Kind.FOOD;
            case FISH -> k == Kind.FOOD || k == Kind.ANIMAL;
            case HUNT -> k == Kind.FOOD || k == Kind.ANIMAL;
            case BEEKEEP -> k == Kind.ANIMAL || s.is(Items.HONEY_BOTTLE) || k == Kind.FOOD || k == Kind.PLANT;
            case COOK -> k == Kind.FOOD || k == Kind.CRAFT;
            case GUARD -> k == Kind.ANIMAL;                                    // what the night's monsters drop
            default -> k == Kind.CRAFT || k == Kind.ANIMAL;                     // the crafts: smith, tailor, brewer, enchanter, shop
        };
    }

    // ------------------------------------------------------------------ money

    public static void sold(UUID village, int coins) {
        if (coins > 0) TODAY.computeIfAbsent(village, x -> new Day()).sold += coins;
    }

    /** The day's work, taken into the treasury (Market.takings). */
    static void takings(UUID village, int coins) {
        if (coins > 0) TODAY.computeIfAbsent(village, x -> new Day()).takings += coins;
    }

    public static void spent(UUID village, int coins) {
        if (coins > 0) TODAY.computeIfAbsent(village, x -> new Day()).spent += coins;
    }

    static void tithe(UUID village, int coins) {
        if (coins > 0) TODAY.computeIfAbsent(village, x -> new Day()).tithe += coins;
    }

    static void wages(UUID village, int coins) {
        if (coins > 0) TODAY.computeIfAbsent(village, x -> new Day()).wages += coins;
    }

    /** Rent the village's tenants paid into the treasury (Homes.payday). */
    static void rent(UUID village, int coins) {
        if (coins > 0) TODAY.computeIfAbsent(village, x -> new Day()).rent += coins;
    }

    /** A house the village sold to a household that saved for it, or a manor to one moving up (Homes). */
    static void houseSold(UUID village, int coins) {
        if (coins > 0) TODAY.computeIfAbsent(village, x -> new Day()).houses += coins;
    }

    /** The tenth of the morning's wages kept back in the treasury as the village's tax (Market.payWages). */
    static void tax(UUID village, int coins) {
        if (coins > 0) TODAY.computeIfAbsent(village, x -> new Day()).tax += coins;
    }

    /** A folk spent this out of its own purse in town, into the treasury: a treat, a drink, a tool, a comfort. */
    public static void spentInTown(@Nullable UUID village, int coins) {
        if (village != null && coins > 0) TODAY.computeIfAbsent(village, x -> new Day()).town += coins;
    }

    /** The tax kept yesterday, and what the folk spent in town yesterday (the books' Money page). */
    public static int taxYesterday(UUID village) {
        Day d = YESTERDAY.get(village);
        return d == null ? 0 : d.tax;
    }

    public static int townYesterday(UUID village) {
        Day d = YESTERDAY.get(village);
        return d == null ? 0 : d.town;
    }

    /** The rent taken today so far, and yesterday's (the books' Homes and Money pages). */
    public static int rentToday(UUID village) {
        Day d = TODAY.get(village);
        return d == null ? 0 : d.rent;
    }

    public static int rentYesterday(UUID village) {
        Day d = YESTERDAY.get(village);
        return d == null ? 0 : d.rent;
    }

    /** What the houses the village sold yesterday fetched. */
    public static int housesSoldYesterday(UUID village) {
        Day d = YESTERDAY.get(village);
        return d == null ? 0 : d.houses;
    }

    // ------------------------------------------------------------------ the day's close

    /**
     * The morning: yesterday's books closed and kept (a week of the output in the ledger), and
     * the village's worth counted. Called once a day from Market.tick, before the traders come.
     */
    public static void closeTheDay(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Day d = TODAY.remove(id);
        if (d == null) d = new Day();
        YESTERDAY.put(id, d);
        ageGiven(id);
        List<Integer> week = history(id);
        week.add(0, (int) Math.round(d.total()));
        while (week.size() > 7) week.remove(week.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < week.size(); i++) sb.append(i == 0 ? "" : ",").append(week.get(i));
        Ledger.note(id, "output.week", sb.toString());
        WORTH.put(id, countWorth(level, id));
    }

    /** The output of the last seven days, yesterday first, in coin. */
    public static List<Integer> history(UUID village) {
        List<Integer> out = new ArrayList<>();
        String note = Ledger.note(village, "output.week");
        if (note == null || note.isEmpty()) return out;
        for (String p : note.split(",")) {
            try { out.add(Integer.parseInt(p.trim())); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** Yesterday's output, in coin (what the traders will buy up to today). */
    public static int yesterday(UUID village) {
        List<Integer> h = history(village);
        return h.isEmpty() ? 0 : h.get(0);
    }

    /** The week's output, a day on average. */
    public static int weekAverage(UUID village) {
        List<Integer> h = history(village);
        if (h.isEmpty()) return 0;
        long sum = 0;
        for (int x : h) sum += x;
        return (int) Math.round(sum / (double) h.size());
    }

    /** Making more than it was (+) or less (-): the last three days against the four before, in percent; null without a week. */
    @Nullable
    public static Integer trend(UUID village) {
        List<Integer> h = history(village);
        if (h.size() < 5) return null;
        double recent = 0, before = 0;
        int nr = 0, nb = 0;
        for (int i = 0; i < h.size(); i++) {
            if (i < 3) { recent += h.get(i); nr++; } else { before += h.get(i); nb++; }
        }
        recent /= nr;
        before /= nb;
        if (before < 1) return null;
        return (int) Math.round((recent - before) * 100.0 / before);
    }

    /** The treasury, the stores at the market's worth, and the folk's savings (their purses, and what is put by for houses). */
    static int countWorth(ServerLevel level, UUID village) {
        double stores = 0;
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty()) stores += worthOf(s);
            }
        }
        int purses = Homes.savedTotal(village);
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f) purses += f.purse();
        Ledger.note(village, "worth.stores", Integer.toString((int) Math.round(stores)));
        return (int) Math.round(stores) + Ledger.coins(village) + purses + Bank.cash(village);   // and the coin in the bank's vault
    }

    /** The village's worth as last counted (each morning), or -1 before the first count. */
    public static int worth(UUID village) {
        return WORTH.getOrDefault(village, -1);
    }

    // ------------------------------------------------------------------ telling it

    /** "342 coins' worth yesterday (food 120, timber 80, ...); 300 a day this week, up 12%". */
    public static String line(UUID village) {
        Day d = YESTERDAY.get(village);
        int y = yesterday(village);
        StringBuilder sb = new StringBuilder();
        sb.append(y).append(" coins' worth made yesterday");
        if (d != null && d.total() > 0) {
            List<String> parts = new ArrayList<>();
            for (Kind k : Kind.values()) {
                int v = (int) Math.round(d.kinds[k.ordinal()]);
                if (v > 0) parts.add(k.word + " " + v);
            }
            if (!parts.isEmpty()) sb.append(" (").append(String.join(", ", parts)).append(")");
        }
        int avg = weekAverage(village);
        if (history(village).size() > 1) sb.append("; ").append(avg).append(" a day this week");
        Integer t = trend(village);
        if (t != null) sb.append(t >= 3 ? ", up " + t + "%" : t <= -3 ? ", down " + (-t) + "%" : ", steady");
        if (d != null) {
            sb.append("; takings ").append(d.takings).append(", sold ").append(d.sold).append(", wages ").append(d.wages);
            if (d.tax > 0) sb.append(", tax ").append(d.tax);
            if (d.tithe > 0) sb.append(", tithe ").append(d.tithe);
            if (d.rent > 0) sb.append(", rent ").append(d.rent);
            if (d.houses > 0) sb.append(", houses sold ").append(d.houses);
            if (d.town > 0) sb.append(", spent in town ").append(d.town);
            if (d.spent > 0) sb.append(", bought in ").append(d.spent);
        }
        int w = worth(village);
        if (w >= 0) sb.append("; worth ").append(w);
        return sb.toString();
    }

    /** The journal's Economy page: the week, the kinds, the trades, the best producers and the worth. */
    public static String page(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        StringBuilder sb = new StringBuilder();
        Day d = YESTERDAY.get(id);
        List<Integer> h = history(id);
        sb.append("Made yesterday: ").append(yesterday(id)).append(" coins' worth, at the market's prices.\n");
        if (!h.isEmpty()) {
            List<String> days = new ArrayList<>();
            for (int x : h) days.add(Integer.toString(x));
            sb.append("The week: ").append(String.join(", ", days)).append(" (yesterday first); ").append(weekAverage(id)).append(" a day");
            Integer t = trend(id);
            if (t != null) sb.append(t >= 3 ? ", up " + t + "% on the days before" : t <= -3 ? ", down " + (-t) + "% on the days before" : ", holding steady");
            sb.append(".\n");
        }
        if (d != null) {
            for (Kind k : Kind.values()) {
                int val = (int) Math.round(d.kinds[k.ordinal()]);
                if (val > 0) sb.append(capital(k.word)).append(": ").append(val).append(".\n");
            }
            sb.append("\n");
            List<Map.Entry<StationTask, Double>> trades = new ArrayList<>(d.trades.entrySet());
            trades.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            if (!trades.isEmpty()) {
                List<String> parts = new ArrayList<>();
                for (var e : trades) parts.add(e.getKey().title.toLowerCase(Locale.ROOT) + " " + Math.round(e.getValue()));
                sb.append("By trade: ").append(String.join(", ", parts)).append(".\n");
            }
            List<Map.Entry<UUID, Double>> folk = new ArrayList<>(d.folk.entrySet());
            folk.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            if (!folk.isEmpty()) {
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < Math.min(8, folk.size()); i++) {
                    parts.add(d.names.getOrDefault(folk.get(i).getKey(), "?") + " " + Math.round(folk.get(i).getValue()));
                }
                sb.append("Best producers: ").append(String.join(", ", parts)).append(".\n");
            }
            sb.append("\nMoney in: ").append(d.takings).append(" from the day's work, ").append(d.sold).append(" from sales")
                .append(d.tax > 0 ? ", " + d.tax + " in tax (a tenth of the wages)" : "").append(d.tithe > 0 ? ", " + d.tithe + " from the tithe" : "")
                .append(d.rent > 0 ? ", " + d.rent + " in rent" : "").append(d.houses > 0 ? ", " + d.houses + " for houses sold" : "")
                .append(d.town > 0 ? ", " + d.town + " the folk spent in town" : "").append(".\n");
            sb.append("Money out: ").append(d.wages).append(" in wages").append(d.tax > 0 ? " (" + (d.wages - d.tax) + " paid, " + d.tax + " kept in tax)" : "")
                .append(d.spent > 0 ? ", " + d.spent + " buying in" : "").append(".\n");
        } else {
            sb.append("The books close each morning: come back tomorrow for yesterday's figures.\n");
        }
        int w = worth(id);
        String stores = Ledger.note(id, "worth.stores");
        int put = Homes.savedTotal(id), purses = put;
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f) purses += f.purse();
        sb.append("\nWorth: ").append(w >= 0 ? Integer.toString(w) : "not yet counted").append(" — the stores ")
            .append(stores == null || stores.isEmpty() ? "?" : stores).append(", the treasury ").append(Ledger.coins(id))
            .append(", the folk's savings ").append(purses).append(put > 0 ? " (" + put + " of it put by toward their houses)" : "").append(".");
        sb.append(Bank.economyLine(id));                  // the bank's vault, its deposits and loans, its week (Bank)
        return sb.toString();
    }

    /** Yesterday's books (the town's annals read them each morning), or null before the first close. */
    @Nullable
    static Day yesterdayBooks(UUID village) {
        return YESTERDAY.get(village);
    }

    /** What this folk made yesterday, in coin (0 if nothing, or no books yet). */
    public static int madeYesterday(VillageFolkEntity f) {
        UUID id = f.ownerId();
        Day d = id == null ? null : YESTERDAY.get(id);
        return d == null ? 0 : (int) Math.round(d.folk.getOrDefault(f.getUUID(), 0.0));
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
