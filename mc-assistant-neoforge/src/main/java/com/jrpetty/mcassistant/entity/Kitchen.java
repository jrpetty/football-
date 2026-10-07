package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.CheeseWheelBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.BandageItem;
import com.jrpetty.mcassistant.item.KitchenDrinkItem;
import com.jrpetty.mcassistant.item.KitchenItems;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * [kitchen] The kitchen, the cellar and the healer's shelf: what the town makes of its stores to eat, to drink and to
 * bind its wounds with, who makes it, and how the folk use it (the things themselves: item/KitchenItems).
 *
 * <ul>
 * <li><b>The packed lunch.</b> The cook packs one for every hand whose work today lies far off: a plot more than
 *     forty-eight blocks from its home and from the stores, the cave team, the scouts, the fishing fleet, a caravan or
 *     an envoy on the road. The hand picks it up from the stores before it sets out, and at the midday meal eats it
 *     where it is instead of walking back in for food; the town's books count how often (the cook's book, the
 *     Production page, the gazette).</li>
 * <li><b>The cheese wheel.</b> The cook makes it of the rancher's milk (who milks the pen's cows into the stores' buckets
 *     the more while cheese is wanted: Links.milk), the buckets back. It keeps: two put by as the town's reserve, and
 *     one more at the end of the year for the winter. When food runs short (short rations, a drought's rationing, a hungry
 *     winter) the stores cut a wheel into four slices for the meals. In good times the cook sets one out on the café's
 *     and the tavern's tables, and folk taking their meal there eat a slice off it; the last slice gone, the cloth is laid
 *     back.</li>
 * <li><b>The honey cake.</b> Baked for a birthday, a wedding, Founding Day and the festivals, a day or so ahead, of the
 *     beekeeper's honey. Cut at the gathering, eight slices to a cake, a slice each: whoever has one is happier that day,
 *     and its card and the chronicle say whose cake it was.</li>
 * <li><b>Mead and cider.</b> The brewer's (the cook's at the café while there is no brewer, as it brews its own drinks).
 *     Mead at the tavern's bar beside the stout, cider there and at the café in the autumn; both raised in the toasts,
 *     the mead at weddings and Founding Day, the cider at the harvest festival, four cups to a bottle. A drink lifts a
 *     folk's spirits, with a little regeneration. Folk drink in moderation: a sensible one has one of an evening, a merry
 *     one (cheerful, sociable, or a Free Spirit) two.</li>
 * <li><b>The fish pie.</b> When the fish market has a glut (a catch over the usual, FishMarket.glut) the unsold fish go
 *     into the stores at dusk, and the cook bakes them into pies, a dozen at the most, that sell at the market and the
 *     café: "the cook turned yesterday's catch into twelve fish pies". A hearty meal, more than bread.</li>
 * <li><b>Herbal tea.</b> The healer's (the brewer, who sees to the town's care; else the café's): a cup for a folk
 *     laid up with a cold, once a cold, and it is well a day sooner (Health.tend). The café sells it on cold days and
 *     all winter. Its water is drawn at the well into one of the stores' bottles.</li>
 * <li><b>The bandage.</b> The healer's, else the tailor's. Every guard and cave dweller carries two to four from the
 *     stores (WatchKit.fit tops it up to three), and binds its own wound when a fight leaves it hurt; the healer binds
 *     the wounded laid up on its round.</li>
 * </ul>
 * Whatever a trade of the town does not make, the shop's workshop does. Everything out of the stores, made by the game's
 * own recipes (Bench), as its age allows (Tiers).
 */
public final class Kitchen {

    private Kitchen() {}

    private static final Logger LOG = LogUtils.getLogger();

    /** A hand's work this far from its home and from the stores is far: it takes its midday meal with it. */
    static final int FAR = 48;
    /** Slices to a honey cake at a gathering; cups to a bottle of mead or cider in a toast. */
    static final int SLICES_A_CAKE = 8, CUPS_A_BOTTLE = 4;
    /** What the healer's tea takes off a cold: a day. */
    static final int TEA_EASES = 24000;
    /** A guard or a delver below this share of its health, out of the fight, binds a wound; not twice in this long. */
    static final float BIND_BELOW = 0.7F;
    static final long BIND_EVERY = 400L;
    /** The wants worked out at most every ten seconds; the town's look (the reserve) as often. */
    static final long LOOK = 200L;
    /** A merry one's second drink comes this long after its first. */
    static final long SECOND_ROUND = 1200L;
    /** The most the stores keep made of each. */
    static final int LUNCHES_MOST = 12, CAKES_MOST = 4, WHEELS_MOST = 4, BOTTLES_MOST = 8, TEA_MOST = 6, BANDAGES_MOST = 24, PIES_MOST = 12;
    /** Bandages a guard or a delver carries: topped up to three when it has fewer than two. */
    static final int BANDAGES_CARRIED = 3, BANDAGES_LOW = 2;
    /** How long a thing is held in the hand while it is eaten, drunk or bound (a stage holds it longer). */
    static final long HELD = 40L;

    // ------------------------------------------------------------------ the things

    static Item lunch() { return KitchenItems.PACKED_LUNCH.get(); }
    static Item wheel() { return KitchenItems.CHEESE_WHEEL_ITEM.get(); }
    static Item slice() { return KitchenItems.CHEESE_SLICE.get(); }
    static Item cake() { return KitchenItems.HONEY_CAKE.get(); }
    static Item mead() { return KitchenItems.MEAD.get(); }
    static Item cider() { return KitchenItems.CIDER.get(); }
    static Item pie() { return KitchenItems.FISH_PIE.get(); }
    static Item tea() { return KitchenItems.HERBAL_TEA.get(); }
    static Item bandage() { return KitchenItems.BANDAGE.get(); }

    static final Predicate<ItemStack> LUNCH = s -> s.is(lunch());
    static final Predicate<ItemStack> BANDAGE = s -> s.is(bandage());
    /** What the tea is steeped from: sweet berries, or any small flower. */
    static final Predicate<ItemStack> LEAVES = s -> s.is(Items.SWEET_BERRIES) || s.is(ItemTags.SMALL_FLOWERS);

    /** One of the kitchen's own, in a folk's hand while it is eaten, drunk or bound. */
    static boolean prop(ItemStack s) {
        return !s.isEmpty() && (s.is(lunch()) || s.is(bandage()) || s.getItem() instanceof KitchenDrinkItem || s.is(cake()) || s.is(pie()));
    }

    // ------------------------------------------------------------------ the ages

    /**
     * The age each belongs to where the rule of the materials would put it elsewhere (Tiers.work): the packed lunch and
     * the honey cake the Wood Age's (a cooked roast and a honey bottle are the town's from its first days), the cheese and
     * the mead the Stone Age's (a bucket of milk is a bucket's, the Iron Age's, and the honey's way loops on itself).
     */
    @Nullable
    public static Villages.Age age(Item it) {
        if (it == Items.AIR) return null;
        if (it == lunch() || it == cake()) return Villages.Age.WOOD;
        if (it == wheel() || it == slice() || it == mead()) return Villages.Age.STONE;
        return null;
    }

    // ------------------------------------------------------------------ the town's books

    /** A town's day in the kitchen: lunches eaten out, pies from the catch, wheels cut for the stores, cakes cut at the
     *  gatherings, bandages used, cups of the healer's tea, drinks at the bar and in the toasts. */
    static final class Day {
        final long day;
        int lunches, pies, wheels, cakes, bandages, teas, drinks;

        Day(long day) { this.day = day; }

        String code() {
            return day + "," + lunches + "," + pies + "," + wheels + "," + cakes + "," + bandages + "," + teas + "," + drinks;
        }

        @Nullable
        static Day decode(String s) {
            String[] p = s.split(",");
            if (p.length < 8) return null;
            try {
                Day d = new Day(Long.parseLong(p[0]));
                d.lunches = Integer.parseInt(p[1]);
                d.pies = Integer.parseInt(p[2]);
                d.wheels = Integer.parseInt(p[3]);
                d.cakes = Integer.parseInt(p[4]);
                d.bandages = Integer.parseInt(p[5]);
                d.teas = Integer.parseInt(p[6]);
                d.drinks = Integer.parseInt(p[7]);
                return d;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** Kept with the town (Ledger), the last eight days. */
    private static final String DAYS_NOTE = "kitchen.days", GLUT_NOTE = "kitchen.glut", BAKER_NOTE = "kitchen.baker";
    private static final Map<UUID, Deque<Day>> DAYS = new ConcurrentHashMap<>();

    static Deque<Day> days(UUID village) {
        return DAYS.computeIfAbsent(village, k -> {
            Deque<Day> d = new ArrayDeque<>();
            String n = Ledger.note(k, DAYS_NOTE);
            if (n != null) for (String part : n.split(";")) {
                Day x = Day.decode(part);
                if (x != null) d.addLast(x);
            }
            return d;
        });
    }

    static Day day(UUID village, long day) {
        Deque<Day> d = days(village);
        Day last = d.peekLast();
        if (last != null && last.day == day) return last;
        Day n = new Day(day);
        d.addLast(n);
        while (d.size() > 8) d.pollFirst();
        return n;
    }

    static void save(UUID village) {
        List<String> codes = new ArrayList<>();
        for (Day d : days(village)) codes.add(d.code());
        Ledger.note(village, DAYS_NOTE, String.join(";", codes));
    }

    @Nullable
    static Day dayIfAny(UUID village, long day) {
        for (Day d : days(village)) if (d.day == day) return d;
        return null;
    }

    /** The week to today, summed. */
    static int week(UUID village, long today, ToIntFunction<Day> what) {
        int n = 0;
        for (Day d : days(village)) if (today - d.day >= 0 && today - d.day < 7) n += what.applyAsInt(d);
        return n;
    }

    /** Fish waiting in the stores for the cook's pies after a glut at the market, and the day of the glut: {fish, day}. */
    static long[] glutNote(UUID village) {
        String n = Ledger.note(village, GLUT_NOTE);
        if (n == null || n.isEmpty()) return new long[]{ 0, -1 };
        try {
            String[] p = n.split(",");
            return new long[]{ Math.max(0, Integer.parseInt(p[0])), p.length > 1 ? Long.parseLong(p[1]) : -1 };
        } catch (NumberFormatException e) {
            return new long[]{ 0, -1 };
        }
    }

    static int glutPending(UUID village) {
        return (int) glutNote(village)[0];
    }

    static void glutPending(UUID village, int n, long day) {
        Ledger.note(village, GLUT_NOTE, Math.max(0, n) + "," + day);
    }

    /** Whose honey cakes they are: the last to bake them, or the town's cook. */
    static String baker(UUID village) {
        String n = Ledger.note(village, BAKER_NOTE);
        if (n != null && !n.isEmpty()) return n;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == StationTask.COOK) return a.displayNameCap();
        return "the cook";
    }

    // ------------------------------------------------------------------ one folk's day

    /** What a folk had of the kitchen's today, for its spirits and its card: kept a day, not saved. */
    static final class Today {
        long day = -1;
        String cake = "", drink = "", lunch = "", slice = "";
        String cakeAt = "", toastAt = "";
        int drinks, bound;
    }

    private static final Map<UUID, Today> TODAY = new ConcurrentHashMap<>();

    static Today today(VillageFolkEntity f, long day) {
        Today t = TODAY.computeIfAbsent(f.getUUID(), k -> new Today());
        if (t.day != day) {
            t.day = day;
            t.cake = t.drink = t.lunch = t.slice = t.cakeAt = t.toastAt = "";
            t.drinks = t.bound = 0;
        }
        return t;
    }

    // ------------------------------------------------------------------ the rest of the memory

    /** Each village's wants, worked out at most every ten seconds: {time, wants, the trades it has}. */
    private static final Map<UUID, Object[]> WANTS = new ConcurrentHashMap<>();
    /** The day a lunch was last packed for each hand; the last look for one. */
    private static final Map<UUID, Long> PACKED = new ConcurrentHashMap<>(), PACK_LOOKED = new ConcurrentHashMap<>();
    /** What a folk is holding while it eats, drinks or binds: till when. */
    private static final Map<UUID, Long> HOLDS = new ConcurrentHashMap<>();
    /** When each last bound a wound; when a merry one's second drink is due. */
    private static final Map<UUID, Long> BOUND = new ConcurrentHashMap<>(), SECOND = new ConcurrentHashMap<>();
    /** The cold each was last given the healer's tea for (the day it caught it). */
    private static final Map<UUID, Long> TEA_HAD = new ConcurrentHashMap<>();
    /** A gathering's cut cake and opened bottle: {slices left, cups left}; and what was told of it. */
    private static final Map<String, int[]> POOLS = new ConcurrentHashMap<>();
    private static final Set<String> TOLD = ConcurrentHashMap.newKeySet();
    /** The town's look, every ten seconds. */
    private static final Map<UUID, Long> TOWN = new ConcurrentHashMap<>();
    /** A café's and a tavern's tables (the cloth over a fence post, by its drawing); the cloth a wheel stands in place of. */
    private static final Map<Ledger.Building, List<BlockPos>> TABLES = new ConcurrentHashMap<>();
    private static final Map<Long, Block> CLOTHS = new ConcurrentHashMap<>();
    /** Tests: merry (true), sensible (false), or as its nature has it (missing). */
    private static final Map<UUID, Boolean> MERRY_FOR_TESTS = new ConcurrentHashMap<>();
    /** Tests: occasions ahead that want a honey cake, by village. */
    private static final Map<UUID, Integer> OCCASIONS_FOR_TESTS = new ConcurrentHashMap<>();
    private static final String STAGED = "mca_kitchen_staged";

    public static void resetForTests() {
        DAYS.clear();
        TODAY.clear();
        WANTS.clear();
        PACKED.clear();
        PACK_LOOKED.clear();
        HOLDS.clear();
        BOUND.clear();
        SECOND.clear();
        TEA_HAD.clear();
        POOLS.clear();
        TOLD.clear();
        TOWN.clear();
        TABLES.clear();
        CLOTHS.clear();
        MERRY_FOR_TESTS.clear();
        OCCASIONS_FOR_TESTS.clear();
    }

    // ------------------------------------------------------------------ who makes what

    /**
     * The trade whose work each thing is in this town: the cook's lunches, cheese, cakes and pies; the brewer's mead and
     * cider (the café's while there is no brewer); the healer's tea (the brewer sees to the town's care: the café's else)
     * and bandages (the tailor's else); and the shop's workshop's whatever no trade of the town makes. Null: nobody yet.
     */
    @Nullable
    static StationTask makerOf(Set<StationTask> trades, Item it) {
        List<StationTask> order;
        if (it == mead() || it == cider() || it == tea()) order = List.of(StationTask.BREW, StationTask.COOK);
        else if (it == bandage()) order = List.of(StationTask.BREW, StationTask.TAILOR);
        else order = List.of(StationTask.COOK);
        for (StationTask t : order) if (trades.contains(t)) return t;
        return trades.contains(StationTask.SHOP) ? StationTask.SHOP : null;
    }

    /** The grown trades at work in the town. */
    static Set<StationTask> trades(UUID village) {
        Set<StationTask> out = EnumSet.noneOf(StationTask.class);
        for (AssistantEntity a : Villages.folkOf(village)) if (a.isAlive() && !a.isBaby()) out.add(a.stationTask());
        return out;
    }

    /** Is this hand's work today far off: its plot more than FAR from its home and from the stores, or out with the
     *  cave team, the scouts, the fleet, a caravan or an envoy? */
    static boolean farToday(VillageFolkEntity f, Villages.Village v) {
        if (f.isBaby() || f.isHired() || f.isShowcase()) return false;
        if (f.trip() != null || f.expedition() != null || Fleet.out(f)) return true;
        StationTask t = f.stationTask();
        if (t == StationTask.CAVE || t == StationTask.SCOUT) return true;
        if (t == StationTask.NETHER) return true;                    // [nether] through the gateway for the day, or days
        if (t == StationTask.NONE || t.isCraft() || t == StationTask.STORE || t == StationTask.HAUL || t == StationTask.GUARD) return false;
        WorkZone z = f.workZone();
        if (z == null) return false;
        BlockPos c = z.center();
        if (horizontal(c, v.centre()) <= FAR) return false;
        BlockPos home = Homes.homeOf(f);
        return home == null || horizontal(c, home) > FAR;
    }

    static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * What the town wants kept in its stores of each, now (worked out at most every ten seconds): a packed lunch for every
     * far hand and one over; honey cakes for the gatherings of the next day or so; pies for the fish a glut left; the
     * cheese's reserve and the tables' wheels; mead and cider for the bar and the toasts; tea for the colds and the winter;
     * bandages for the watch's and the cave team's kits and the infirmary.
     */
    static Map<Item, Integer> wanted(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Object[] cached = WANTS.get(id);
        if (cached != null && now - (Long) cached[0] < LOOK && now >= (Long) cached[0]) {
            @SuppressWarnings("unchecked") Map<Item, Integer> m = (Map<Item, Integer>) cached[1];
            return m;
        }
        long day = level.getDayTime() / 24000L;
        Map<Item, Integer> out = new LinkedHashMap<>();
        int far = 0, kits = 0, kitted = 0, colds = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || !f.isAlive()) continue;
            if (f.health().ill()) colds++;
            if (f.isBaby()) continue;
            if (farToday(f, v)) far++;
            StationTask t = f.stationTask();
            if (t == StationTask.GUARD || t == StationTask.CAVE || t == StationTask.NETHER) {   // [nether] the runners too
                kitted++;
                int c = f.countMatching(BANDAGE);
                if (c < BANDAGES_LOW) kits += BANDAGES_CARRIED - c;
            }
        }
        if (far > 0) out.put(lunch(), Math.min(LUNCHES_MOST, far + 1));
        int cakes = cakesWanted(level, id, day);
        if (cakes > 0) out.put(cake(), cakes);
        // The glut's fish baked whatever pies the stores hold already: as many again as there are fish (a dozen at most).
        // Fish not baked in two days have gone elsewhere (smoked, eaten, sold): the cook looks for them no longer.
        long[] glut = glutNote(id);
        if (glut[0] > 0 && day - glut[1] > 2) {
            glutPending(id, 0, glut[1]);
            glut[0] = 0;
        }
        int pies = (int) glut[0];
        if (pies > 0) out.put(pie(), Market.stock(level, id, s -> s.is(pie())) + Math.min(PIES_MOST, pies));
        Seasons.Season season = Seasons.season(id, day);
        boolean tavern = Tavern.of(id) != null, cafe = Villages.builtAt(id, "cafe") != null;
        int wheels = 2 + (season == Seasons.Season.AUTUMN ? 1 : 0) + emptyTables(level, id);
        out.put(wheel(), Math.min(WHEELS_MOST, wheels));
        int toasts = toastsAhead(id, day);
        int mead = (tavern ? 3 : 0) + (toasts > 0 ? Math.max(1, Villages.headcount(id) / CUPS_A_BOTTLE / 2) : 0);
        if (mead > 0) out.put(mead(), Math.min(BOTTLES_MOST, mead));
        boolean harvest = Festivals.next(id, day, Festivals.Feast.HARVEST) - day <= 2;
        int cider = (season == Seasons.Season.AUTUMN && (tavern || cafe) ? 3 : 0)
            + (harvest ? Math.max(1, Villages.headcount(id) / CUPS_A_BOTTLE / 2) : 0);
        if (cider > 0) out.put(cider(), Math.min(BOTTLES_MOST, cider));
        int tea = colds + (season == Seasons.Season.WINTER ? 3 : cafe && coldDay(level, id, day) ? 1 : 0);
        if (tea > 0) out.put(tea(), Math.min(TEA_MOST, tea));
        int bandages = kits + (kitted > 0 ? 2 : 0) + (Villages.builtAt(id, "infirmary") != null ? 2 : 0);
        if (bandages > 0) out.put(bandage(), Math.min(BANDAGES_MOST, bandages));
        WANTS.put(id, new Object[]{ now, out, trades(id) });
        return out;
    }

    @SuppressWarnings("unchecked")
    static Set<StationTask> tradesCached(UUID village) {
        Object[] cached = WANTS.get(village);
        return cached == null ? trades(village) : (Set<StationTask>) cached[2];
    }

    /** Honey cakes for the gatherings of today and tomorrow: a birthday one each, a wedding, Founding Day or a festival two. */
    static int cakesWanted(ServerLevel level, UUID id, long day) {
        int n = OCCASIONS_FOR_TESTS.getOrDefault(id, 0);
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isShowcase() || f.isHired()) continue;
            long[] next = Birthdays.next(f, day);
            if (next[0] >= 0 && next[0] - day <= 1 && next[1] <= 100 && TownCalendar.birthdayKept(f.getUUID()) != next[0]) n++;
        }
        if (Gatherings.wedding(id) != null) n += 2;
        long founding = FoundingDay.next(id, day);
        if (founding >= 0 && founding - day <= 1) n += 2;
        for (Festivals.Feast fe : new Festivals.Feast[]{ Festivals.Feast.MAYPOLE, Festivals.Feast.BONFIRE, Festivals.Feast.FAIR, Festivals.Feast.HARVEST }) {
            if (Festivals.next(id, day, fe) - day <= 1) n += 2;
        }
        return Math.min(CAKES_MOST, n);
    }

    /** Toasts in mead ahead: a wedding to come, or Founding Day today or tomorrow. */
    static int toastsAhead(UUID id, long day) {
        int n = 0;
        if (Gatherings.wedding(id) != null) n++;
        long founding = FoundingDay.next(id, day);
        if (founding >= 0 && founding - day <= 1) n++;
        return n;
    }

    /** A cold day: winter, rain, or the town in cold country. */
    static boolean coldDay(ServerLevel level, UUID village, long day) {
        if (Seasons.season(village, day) == Seasons.Season.WINTER || level.isRaining()) return true;
        Villages.Village v = Villages.get(village);
        return v != null && level.getBiome(v.centre()).value().coldEnoughToSnow(v.centre());
    }

    /** Is the town short of food: short rations, a drought's rationing, or a winter with little put by? */
    static boolean shortOfFood(ServerLevel level, UUID id, long day) {
        Leader.Plan p = Leader.plan(id);
        if (p == Leader.Plan.FAMINE || p == Leader.Plan.SHORT || Droughts.rationing(id)) return true;
        return Seasons.season(id, day) == Seasons.Season.WINTER && p != Leader.Plan.PLENTY
            && Market.stock(level, id, Meals.FOOD) < Villages.headcount(id) * 2;
    }

    /** The rancher milks the more while the cook wants cheese (a maker for it, and the age for it): three buckets more
     *  than it keeps (Links.milk), a wheel's worth. */
    public static int milkWanted(ServerLevel level, @Nullable UUID village) {
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null) return 0;
        Integer want = wanted(level, v).get(wheel());
        if (want == null || makerOf(tradesCached(village), wheel()) == null || !Tiers.allows(level, Villages.ageOf(village), wheel())) return 0;
        return Market.stock(level, village, s -> s.is(wheel())) < want ? 3 : 0;
    }

    // ------------------------------------------------------------------ the makers (Crafts.now)

    /**
     * A turn at the kitchen's work (Crafts.now, one turn in two while anything is wanted, every turn while a far hand waits
     * on its lunch or a gathering on its cake): the cook sets a cheese wheel out on a table that has none; then the first
     * thing the stores are short of that is this trade's work and the age allows, made at the bench out of the stores
     * (Bench, by the game's own recipe). What was done, or null.
     */
    @Nullable
    public static String craft(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        StationTask t = f.stationTask();
        if (t != StationTask.COOK && t != StationTask.BREW && t != StationTask.TAILOR && t != StationTask.SHOP) return null;
        Map<Item, Integer> want = wanted(level, v);
        if (want.isEmpty()) return null;
        boolean urgent = short_(level, v, want, lunch()) && level.getDayTime() % 24000L < Meals.Meal.LUNCH.from
            || short_(level, v, want, cake()) && cakesWanted(level, v.id(), level.getDayTime() / 24000L) > 0;
        if (!urgent && Math.floorMod(level.getGameTime() / Crafts.EVERY + f.getUUID().hashCode(), 2L) != 0) return null;
        return craftNow(level, v, f, want);
    }

    private static boolean short_(ServerLevel level, Villages.Village v, Map<Item, Integer> want, Item it) {
        Integer n = want.get(it);
        return n != null && Market.stock(level, v.id(), s -> s.is(it)) < n;
    }

    @Nullable
    static String craftNow(ServerLevel level, Villages.Village v, VillageFolkEntity f, Map<Item, Integer> want) {
        StationTask t = f.stationTask();
        UUID id = v.id();
        Set<StationTask> trades = tradesCached(id);
        if (t == StationTask.COOK) {
            String set = setOut(level, v, f);
            if (set != null) return set;
        }
        Villages.Age age = Villages.ageOf(id);
        Bench.Hand hand = null;
        for (Map.Entry<Item, Integer> e : want.entrySet()) {
            Item it = e.getKey();
            if (makerOf(trades, it) != t || !Tiers.allows(level, age, it)) continue;
            int have = Market.stock(level, id, s -> s.is(it));
            if (have >= e.getValue()) continue;
            if (hand == null) hand = Bench.handOf(level, v, f, VillageFolkEntity.buildingFor(t));
            String made = make(level, v, f, hand, it, e.getValue() - have);
            if (made != null && it == tea()) level.playSound(null, f.blockPosition(), SoundEvents.BOTTLE_FILL, SoundSource.NEUTRAL, 0.6F, 1.0F);
            if (made != null) return made;
        }
        return null;
    }

    /** How many at a go: a bundle of lunches (two to a recipe), a dozen pies four at a time, a roll of bandages. */
    static int batch(Item it, int short_) {
        if (it == lunch()) return Math.min(4, short_);
        if (it == pie()) return Math.min(4, short_);
        if (it == cake() || it == mead() || it == cider()) return Math.min(2, short_);
        return 1;
    }

    /** One of these made at the bench out of the stores, by its recipe; booked; what was done, in words, or null. */
    @Nullable
    static String make(ServerLevel level, Villages.Village v, VillageFolkEntity f, Bench.Hand hand, Item it, int short_) {
        Bench.Plan plan = Bench.plan(level, v, it, batch(it, short_), hand);
        if (!plan.ok()) {
            f.brain("short of " + plan.shortOf + " for " + Bench.plural(new ItemStack(it).getHoverName().getString().toLowerCase(Locale.ROOT)));
            return null;
        }
        ItemStack out = Bench.make(level, v, plan, f, hand);
        if (out.isEmpty()) return null;
        return made(level, v, f, out, plan.cost() / Math.max(1, out.getCount()));
    }

    /** The water bottle a water bottle's ingredient asks for (the herbal tea's), to know it by. */
    private static ItemStack waterSample;

    /**
     * Does this ingredient ask for a bottle of water (Bench.need)? The bench meets it with one of the stores' glass bottles,
     * filled at the well: the water is the world's, drawn by hand, as a player fills one at any water. So the healer's tea is
     * made by its own recipe the whole way from the stores, at any bench that makes it.
     */
    public static boolean waterBottle(Predicate<ItemStack> what, List<Item> kinds) {
        if (!kinds.contains(Items.POTION)) return false;
        ItemStack w = waterSample;
        if (w == null) waterSample = w = net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION,
            net.minecraft.world.item.alchemy.Potions.WATER);
        return what.test(w);
    }

    /** Made: booked in the cook's book, the honey cakes' baker named, the glut's pies counted. Returns the words for it. */
    static String made(ServerLevel level, Villages.Village v, VillageFolkEntity f, ItemStack out, double each) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Item it = out.getItem();
        int n = out.getCount();
        if (f.stationTask() == StationTask.COOK && cookWare(it)) {
            Stockroom.Book b = Stockroom.book(level, id, Stockroom.Seller.CAFE);
            Stockroom.made(b, Stockroom.line(b, Stockroom.key(out)), n, each);
        }
        String why = "";
        if (it == cake()) {
            Ledger.note(id, BAKER_NOTE, f.displayNameCap());
            why = ", for the gatherings ahead";
        } else if (it == pie()) {
            long[] glut = glutNote(id);
            int pending = (int) glut[0];
            if (pending > 0) {
                int took = Math.min(n, pending);
                glutPending(id, pending - took, glut[1]);
                day(id, day).pies += took;
                save(id);
                why = ", of the catch the market could not sell";
            }
        } else if (it == lunch()) {
            why = ", packed for the far hands";
        } else if (it == wheel()) {
            why = ", put by";
        } else if (it == bandage()) {
            why = ", for the watch's kits";
        } else if (it == tea()) {
            why = ", for the sick";
        } else if (it == mead() || it == cider()) {
            why = ", for the tavern and the toasts";
        }
        LOG.info("[MCA-KITCHEN] {} ({}) made {} at {}", f.displayNameCap(), f.stationTask().title, Bench.words(it, n), Villages.name(id));
        return Bench.words(it, n) + why;
    }

    /** The cook's own, kept in its book (the café's wares). */
    static boolean cookWare(Item it) {
        return it == lunch() || it == wheel() || it == cake() || it == pie() || it == tea();
    }

    /**
     * The café's book (the cook's: Cafe.cafeWares): the fish pie, kept by what sells; the herbal tea of its own recipe, made
     * when the café has none from the healer and sells it; and the packed lunch, the cheese wheel and the honey cake, kept
     * for nobody's counter (made for the far hands, the reserve and the gatherings: Kitchen.craft), on its books so the
     * cook's work on them shows.
     */
    static List<Stockroom.Ware> cafeWares() {
        List<Stockroom.Ware> out = new ArrayList<>();
        out.add(Stockroom.ware(pie(), 2, 0, 12, 2, false));
        out.add(Stockroom.ware(tea(), 0, 0, TEA_MOST, 1, false));
        out.add(Stockroom.ware(lunch(), 0, 0, 0, 2, false));
        out.add(Stockroom.ware(wheel(), 0, 0, 0, 1, false));
        out.add(Stockroom.ware(cake(), 0, 0, 0, 1, false));
        return out;
    }

    /** A line of the cook's book (Stockroom.row): what became of the packed lunches, the pies, the cheese and the cakes. */
    @Nullable
    public static String status(ServerLevel level, UUID village, String key) {
        long today = level.getDayTime() / 24000L;
        if (key.equals(Stockroom.key(new ItemStack(lunch())))) {
            int n = week(village, today, d -> d.lunches);
            return n == 0 ? null : n + " eaten out this week";
        }
        if (key.equals(Stockroom.key(new ItemStack(pie())))) {
            int n = week(village, today, d -> d.pies);
            return n == 0 ? null : n + " of the catch this week";
        }
        if (key.equals(Stockroom.key(new ItemStack(wheel())))) {
            int n = week(village, today, d -> d.wheels);
            return n == 0 ? null : n + " cut for the stores this week";
        }
        if (key.equals(Stockroom.key(new ItemStack(cake())))) {
            int n = week(village, today, d -> d.cakes);
            return n == 0 ? null : n + " cut at gatherings this week";
        }
        return null;
    }

    // ------------------------------------------------------------------ the cheese on the tables

    /** A café's or a tavern's tables: where its drawing lays a cloth over a fence post (the cloth's place). */
    static List<BlockPos> tables(Ledger.Building b) {
        return TABLES.computeIfAbsent(b, k -> {
            List<BuildGoal.Placement> plan = BuildGoal.plan(k.structure(), k.anchor(), k.facing(), 13);
            Set<BlockPos> posts = new HashSet<>();
            for (BuildGoal.Placement p : plan) if (p.part() == BuildGoal.Part.FENCE) posts.add(p.pos());
            List<BlockPos> out = new ArrayList<>();
            for (BuildGoal.Placement p : plan) {
                if (p.part() == BuildGoal.Part.CARPET && posts.contains(p.pos().below())) out.add(p.pos().immutable());
            }
            return List.copyOf(out);
        });
    }

    static boolean eatery(Ledger.Building b) {
        return b.structure().equals("cafe") || b.structure().equals("tavern");
    }

    /** The café's and the tavern's that stand and have no wheel on any table. */
    static int emptyTables(ServerLevel level, UUID village) {
        int n = 0;
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (!eatery(b) || !level.isLoaded(b.anchor())) continue;
            boolean has = false, any = false;
            for (BlockPos p : tables(b)) {
                BlockState st = level.getBlockState(p);
                if (st.getBlock() instanceof CheeseWheelBlock) has = true;
                else if (level.getBlockState(p.below()).getBlock() instanceof FenceBlock) any = true;
            }
            if (!has && any) n++;
        }
        return n;
    }

    /**
     * The cook's piece of work: a cheese wheel out of the stores set out on a café's or a tavern's table that has none, in
     * place of its cloth (the cloth into the stores till the wheel is eaten). Not while food is short (the wheels are cut
     * for the stores then), and never the last: one stays put by. What was done, or null.
     */
    @Nullable
    static String setOut(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity cook) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        if (shortOfFood(level, id, day) || Market.stock(level, id, s -> s.is(wheel())) < 2) return null;
        for (Ledger.Building b : Ledger.buildings(id)) {
            if (!eatery(b) || !level.isLoaded(b.anchor())) continue;
            BlockPos free = null;
            boolean has = false;
            for (BlockPos p : tables(b)) {
                BlockState st = level.getBlockState(p);
                if (st.getBlock() instanceof CheeseWheelBlock) { has = true; break; }
                if (free == null && level.getBlockState(p.below()).getBlock() instanceof FenceBlock && (st.isAir() || st.is(BlockTags.WOOL_CARPETS))) free = p;
            }
            if (has || free == null) continue;
            ItemStack w = Crafts.takeOne(level, v, s -> s.is(wheel()));
            if (w.isEmpty()) return null;
            BlockState was = level.getBlockState(free);
            if (was.is(BlockTags.WOOL_CARPETS)) {
                Crafts.store(level, v, new ItemStack(was.getBlock().asItem()));
                CLOTHS.put(free.asLong(), was.getBlock());
            }
            level.setBlock(free, KitchenItems.CHEESE_WHEEL.get().defaultBlockState(), 3);
            String where = b.structure().equals("cafe") ? "the café's" : "the tavern's";
            if (cook != null) {
                cook.getLookControl().setLookAt(free.getX() + 0.5, free.getY() + 0.3, free.getZ() + 0.5);
                FolkTalk.speak(cook, FolkTalk.pick(cook.getRandom(), "A wheel out on " + where + " table. Help yourselves at mealtimes.",
                    "There — cheese on the table. A slice each, mind."));
            }
            LOG.info("[MCA-KITCHEN] a cheese wheel set out on {} table at {} ({})", where, free.toShortString(), Villages.name(id));
            return "a cheese wheel set out on " + where + " table";
        }
        return null;
    }

    /** A wheel on a table of the café or the tavern this folk is in (or at the door of), or null. */
    @Nullable
    static BlockPos wheelNear(ServerLevel level, VillageFolkEntity f, UUID village) {
        BlockPos me = f.blockPosition();
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (!eatery(b) || b.anchor().distSqr(me) > 16 * 16 || !level.isLoaded(b.anchor())) continue;
            for (BlockPos p : tables(b)) if (level.getBlockState(p).getBlock() instanceof CheeseWheelBlock) return p;
        }
        return null;
    }

    /** The wheel eaten to the board: the table's cloth laid back, out of the stores. */
    static void relay(ServerLevel level, @Nullable Villages.Village v, BlockPos at) {
        if (v == null || !level.getBlockState(at).isAir() || !(level.getBlockState(at.below()).getBlock() instanceof FenceBlock)) return;
        Block cloth = CLOTHS.remove(at.asLong());
        Item want = cloth == null ? null : cloth.asItem();
        ItemStack got = want == null ? ItemStack.EMPTY : Crafts.takeOne(level, v, s -> s.is(want));
        if (got.isEmpty()) got = Crafts.takeOne(level, v, s -> s.is(ItemTags.WOOL_CARPETS));
        if (got.isEmpty() || !(got.getItem() instanceof net.minecraft.world.item.BlockItem bi)) return;
        level.setBlock(at, bi.getBlock().defaultBlockState(), 3);
    }

    // ------------------------------------------------------------------ meals (Meals.tick)

    /**
     * A meal had of the kitchen's (Meals.tick, before the pack, the chest and the stores): at the midday meal a packed lunch
     * eaten where the hand is, out at its work, with no walk back; or, at a café's or a tavern's table, a slice off the
     * cheese wheel set out there. True if it was had.
     */
    public static boolean mealOut(ServerLevel level, VillageFolkEntity f, UUID village, Meals.Meal m) {
        long now = level.getGameTime(), day = level.getDayTime() / 24000L;
        Villages.Village v = Villages.get(village);
        if (m == Meals.Meal.LUNCH && f.countMatching(LUNCH) > 0 && f.removeMatching(LUNCH, 1) == 1) {
            boolean out = v != null && horizontal(f.blockPosition(), v.centre()) > Meals.STORES_REACH;
            String where = out ? (f.patchName().isEmpty() ? "out at its work" : "out at " + f.patchName()) : "at its work";
            f.meals().ate(now, "a packed lunch");
            f.heal(2.0F);
            eaten(level, f, new ItemStack(lunch()));
            show(f, new ItemStack(lunch()), now, HELD);                          // in its hand a moment (none if its hand is full)
            today(f, day).lunch = "its packed lunch " + where;
            if (out) {
                day(village, day).lunches++;
                save(village);
            }
            f.brain("ate its packed lunch " + where + (out ? ": no walk back to the stores" : ""));
            if (f.getRandom().nextInt(4) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Bread, a bit of roast and an apple. The cook knows what a body needs.",
                    "No traipsing back to the stores today — lunch came with me.", "A packed lunch in the open. Can't beat it."));
            }
            return true;
        }
        BlockPos w = wheelNear(level, f, village);
        if (w != null && CheeseWheelBlock.slice(level, w)) {
            f.meals().ate(now, "a slice of cheese");
            f.heal(2.0F);
            eaten(level, f, new ItemStack(slice()));
            f.getLookControl().setLookAt(w.getX() + 0.5, w.getY() + 0.3, w.getZ() + 0.5);
            today(f, day).slice = "a slice off the cheese on the table";
            if (!(level.getBlockState(w).getBlock() instanceof CheeseWheelBlock)) relay(level, v, w);
            f.brain("had a slice of the cheese on the table for its meal");
            return true;
        }
        return false;
    }

    /** The look and the sound of something eaten. */
    static void eaten(ServerLevel level, VillageFolkEntity f, ItemStack what) {
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, what), f.getX(), f.getEyeY() - 0.1, f.getZ(), 6, 0.15, 0.1, 0.15, 0.03);
        f.playSound(SoundEvents.GENERIC_EAT, 0.6F, 0.9F + f.getRandom().nextFloat() * 0.2F);
        f.swing(InteractionHand.MAIN_HAND);
    }

    // ------------------------------------------------------------------ in the hand

    /** Held in its free hand a moment while it is eaten, drunk or bound (the thing itself, used up after). False if its
     *  hand is full (a lantern, a shield): it is used at once. */
    static boolean show(VillageFolkEntity f, ItemStack one, long now, long ticks) {
        if (!f.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) return false;
        f.setItemSlot(EquipmentSlot.OFFHAND, one.copyWithCount(1));
        HOLDS.put(f.getUUID(), now + ticks);
        return true;
    }

    /** Done with what it held: eaten, drunk (the bottle back into the stores) or bound, and the hand empty again. */
    static void hold(VillageFolkEntity f, ServerLevel level, @Nullable Villages.Village v, long now) {
        Long until = HOLDS.get(f.getUUID());
        ItemStack off = f.getItemBySlot(EquipmentSlot.OFFHAND);
        if (until != null && now < until && now >= until - 20000L) return;
        HOLDS.remove(f.getUUID());
        if (!prop(off)) return;
        boolean bottle = off.getItem() instanceof KitchenDrinkItem;
        f.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        if (bottle && v != null) Crafts.store(level, v, new ItemStack(Items.GLASS_BOTTLE));
    }

    // ------------------------------------------------------------------ a folk's second (VillageFolkEntity.aiStep)

    /**
     * Once a second, for each folk: what it holds put away when it is done with it; the town's look (every ten seconds,
     * whoever's second it is); and for a grown folk, a wound bound after a fight, a packed lunch picked up before it sets
     * out, and a merry one's second drink at the bar.
     */
    public static void second(VillageFolkEntity f, ServerLevel level) {
        if (f.isShowcase() || !f.isAlive() || f.ownerId() == null) return;
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return;
        long now = level.getGameTime();
        if (!f.getTags().contains(STAGED)) hold(f, level, v, now);
        town(level, v, now);
        if (f.isBaby()) return;
        bind(f, level, v, now);
        pack(f, level, v, now);
        secondRound(f, level, v, now);
    }

    /** The town's look, every ten seconds: a cheese wheel cut for the stores when food runs short. */
    static void town(ServerLevel level, Villages.Village v, long now) {
        Long last = TOWN.get(v.id());
        if (last != null && now - last < LOOK && now >= last) return;
        TOWN.put(v.id(), now);
        reserve(level, v, level.getDayTime() / 24000L);
        if (POOLS.size() > 64) POOLS.clear();
        if (TOLD.size() > 256) TOLD.clear();
    }

    /**
     * The reserve: food short (short rations, a drought, a hungry winter) and the stores nearly bare of it, a cheese wheel
     * cut into its four slices for the meals, four a day at the most. What was done, or null.
     */
    @Nullable
    static String reserve(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (!shortOfFood(level, id, day)) return null;
        if (Market.stock(level, id, Meals.FOOD) >= Math.max(4, Villages.headcount(id))) return null;
        Day d = day(id, day);
        if (d.wheels >= 4) return null;
        ItemStack w = Crafts.takeOne(level, v, s -> s.is(wheel()));
        if (w.isEmpty()) return null;
        Crafts.store(level, v, new ItemStack(slice(), CheeseWheelBlock.SLICES));
        d.wheels++;
        save(id);
        if (d.wheels == 1) Villages.tell(id, day, "the stores cut a cheese wheel: the town's reserve feeds it while food is short");
        LOG.info("[MCA-KITCHEN] {} cut a cheese wheel for the stores ({} today)", Villages.name(id), d.wheels);
        return "a cheese wheel cut into " + CheeseWheelBlock.SLICES + " slices for the stores";
    }

    /** A guard or a cave dweller hurt, out of the fight, binds its wound with one of its bandages. True if it did. */
    static boolean bind(VillageFolkEntity f, ServerLevel level, Villages.Village v, long now) {
        StationTask t = f.stationTask();
        if (t != StationTask.GUARD && t != StationTask.CAVE && t != StationTask.NETHER) return false;   // [nether] and the runners
        if (f.getHealth() >= f.getMaxHealth() * BIND_BELOW || f.getTarget() != null || f.hurtTime > 0) return false;
        if (f.getLastHurtByMob() != null && f.tickCount - f.getLastHurtByMobTimestamp() < 60) return false;
        if (f.hasEffect(MobEffects.REGENERATION)) return false;
        Long last = BOUND.get(f.getUUID());
        if (last != null && now - last < BIND_EVERY && now >= last) return false;
        if (f.removeMatching(BANDAGE, 1) != 1) return false;
        BOUND.put(f.getUUID(), now);
        BandageItem.bind(f);
        show(f, new ItemStack(bandage()), now, HELD);
        f.swing(InteractionHand.OFF_HAND);
        long day = level.getDayTime() / 24000L;
        today(f, day).bound++;
        day(v.id(), day).bandages++;
        save(v.id());
        f.brain("bound a wound with a bandage after the fight");
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Hold still... there. That'll stop the bleeding.", "A bandage round it. I'll mend.",
            "Just a scratch. Well — a deep one. Bandage."));
        LOG.info("[MCA-KITCHEN] {} bound a wound ({} of {} health), {} bandages left", f.displayNameCap(), f.getHealth(), f.getMaxHealth(),
            f.countMatching(BANDAGE));
        return true;
    }

    /**
     * Before it sets out for far work (its plot far off, the cave team, the scouts, the fleet, the road): a packed lunch out
     * of the stores, while it is still within reach of them and the midday meal is to come. Looked for once a minute; one a
     * day. True if it got one.
     */
    static boolean pack(VillageFolkEntity f, ServerLevel level, Villages.Village v, long now) {
        long dt = level.getDayTime(), day = dt / 24000L, tod = dt % 24000L;
        UUID me = f.getUUID();
        if (tod >= Meals.Meal.LUNCH.to || PACKED.getOrDefault(me, Long.MIN_VALUE) == day) return false;
        Long looked = PACK_LOOKED.get(me);
        if (looked != null && now - looked < 1200L && now >= looked) return false;
        PACK_LOOKED.put(me, now);
        if (Meals.hadToday(f, Meals.Meal.LUNCH, day) || !farToday(f, v)) return false;
        if (f.countCarried(LUNCH) > 0) {
            PACKED.put(me, day);
            return false;
        }
        if (horizontal(f.blockPosition(), v.centre()) > Meals.STORES_REACH) return false;      // gone already: too late today
        ItemStack l = Crafts.takeOne(level, v, LUNCH);
        if (l.isEmpty()) return false;
        ItemStack left = f.insertGiven(l);
        if (!left.isEmpty()) {
            Crafts.store(level, v, left);
            return false;
        }
        PACKED.put(me, day);
        f.brain("picked up a packed lunch from the stores for the day's far work");
        if (f.getRandom().nextInt(3) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Lunch packed. I'll not be back till evening.", "The cook's packed me a bundle. Good.",
                "Bread and a bit of roast for later. Off I go."));
        }
        return true;
    }

    /** A merry one's second drink, a while after its first, if it is still at the tavern. */
    static void secondRound(VillageFolkEntity f, ServerLevel level, Villages.Village v, long now) {
        Long due = SECOND.get(f.getUUID());
        if (due == null || now < due) return;
        SECOND.remove(f.getUUID());
        if (!"at the tavern".equals(f.hobbyNow())) return;
        bar(level, v, f, level.getDayTime() / 24000L, true);
    }

    // ------------------------------------------------------------------ the bar (Tavern.drink)

    /** Merry: cheerful, sociable, or one whose heart is in rest and merriment. Two of an evening; the rest have one. */
    static boolean merry(VillageFolkEntity f) {
        Boolean forced = MERRY_FOR_TESTS.get(f.getUUID());
        if (forced != null) return forced;
        return f.life().has(Social.Trait.CHEERFUL) || f.life().has(Social.Trait.SOCIABLE) || Values.top(f) == Values.Value.LEISURE;
    }

    /** At the tavern's bar of an evening (Tavern.drink, after the stout): a mead, or in the autumn a cider. */
    public static boolean atTheBar(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day) {
        return bar(level, v, f, day, false);
    }

    static boolean bar(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day, boolean second) {
        UUID id = v.id();
        Today t = today(f, day);
        int most = merry(f) ? 2 : 1;
        if (t.drinks >= most) return false;
        boolean autumn = Seasons.season(id, day) == Seasons.Season.AUTUMN;
        boolean hasMead = Market.stock(level, id, s -> s.is(mead())) > 0, hasCider = autumn && Market.stock(level, id, s -> s.is(cider())) > 0;
        Item pick = hasMead && hasCider ? (f.getRandom().nextBoolean() ? mead() : cider()) : hasMead ? mead() : hasCider ? cider() : null;
        if (pick == null) {
            // Asked for and not there, in the tavern's book: only where the town has somebody to brew it.
            if (!second && makerOf(tradesCached(id), mead()) != null) Stockroom.missed(level, id, Stockroom.Seller.TAVERN, new ItemStack(autumn ? cider() : mead()));
            return false;
        }
        ItemStack one = new ItemStack(pick);
        int price = Math.max(2, (int) Math.ceil(Prices.each(pick) * 1.2));
        if (f.purse() < price || !Crafts.take(level, v, s -> s.is(pick), 1)) return false;
        if (!f.spend(price)) {
            Crafts.store(level, v, one);
            return false;
        }
        Ledger.addCoins(id, price);
        Economy.spentInTown(id, price);
        Stockroom.sold(level, id, Stockroom.Seller.TAVERN, one, 1, price);
        drink(level, v, f, one, "at the tavern", day);
        if (!second && most > 1) SECOND.put(f.getUUID(), level.getGameTime() + SECOND_ROUND);
        if (f.getRandom().nextInt(3) == 0) {
            FolkTalk.speak(f, second ? FolkTalk.pick(f.getRandom(), "Go on then — just the one more.", "Another, and that's me done.")
                : pick == mead() ? FolkTalk.pick(f.getRandom(), "A mead, please. Honey and a bit of fire.", "The brewer's mead. Lovely stuff.")
                : FolkTalk.pick(f.getRandom(), "Cider, now the apples are in.", "A cider — it tastes of the whole autumn."));
        }
        f.brain("bought " + Bench.words(pick, 1) + " at the tavern for " + price);
        return true;
    }

    /**
     * A bottle of the kitchen's drunk where it is: its good (a little regeneration, a heart of cheer), its spirits lifted
     * for the day, held a moment and then the bottle back into the stores.
     */
    static void drink(ServerLevel level, Villages.Village v, VillageFolkEntity f, ItemStack one, String where, long day) {
        if (one.getItem() instanceof KitchenDrinkItem d) for (MobEffectInstance e : d.effects()) f.addEffect(e);
        Today t = today(f, day);
        t.drinks++;
        t.drink = Bench.words(one.getItem(), 1) + " " + where;
        day(v.id(), day).drinks++;
        save(v.id());
        if (!show(f, one, level.getGameTime(), HELD)) Crafts.store(level, v, new ItemStack(Items.GLASS_BOTTLE));
        level.playSound(null, f.blockPosition(), SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 0.6F, 1.0F);
        f.swing(InteractionHand.MAIN_HAND);
        f.refreshMood();
    }

    // ------------------------------------------------------------------ the café (Cafe.menuGoods, Cafe.folkBuys)

    /** On the café's counter: the fish pie; the cider in the autumn; the herbal tea on a cold day and all winter. */
    public static boolean onMenu(ServerLevel level, UUID village, ItemStack s) {
        if (s.is(pie())) return true;
        long day = level.getDayTime() / 24000L;
        if (s.is(cider())) return Seasons.season(village, day) == Seasons.Season.AUTUMN;
        if (s.is(tea())) return coldDay(level, village, day);
        return false;
    }

    /** One of the kitchen's drinks bought at the café, had there: true (Cafe.folkBuys tells the rest). */
    public static boolean had(ServerLevel level, Villages.Village v, VillageFolkEntity f, ItemStack pick) {
        if (!(pick.getItem() instanceof KitchenDrinkItem)) return false;
        drink(level, v, f, pick.copyWithCount(1), "at the café", level.getDayTime() / 24000L);
        return true;
    }

    // ------------------------------------------------------------------ the gatherings (Assemblies.mingle, Birthdays)

    /** Where it was, in a word: "the wedding", "Founding Day", "the harvest festival". */
    static String occasion(Assemblies.Kind kind, @Nullable Festivals.Feast feast) {
        return switch (kind) {
            case WEDDING -> "the wedding";
            case FOUNDING -> "Founding Day";
            case FEAST -> "the village feast";
            case CELEBRATION -> "the celebration";
            case FESTIVAL -> feast == null ? "the festival" : feast.words;
            default -> kind.label;
        };
    }

    /**
     * At a gathering, while the crowd mingles (Assemblies.mingle): a slice of honey cake at a wedding, Founding Day, the
     * village feast, a celebration or a festival, out of a cake the stores give up (eight slices to a cake); and a cup of
     * mead in the toast at a wedding and Founding Day, of cider at the harvest festival (four cups to a bottle, the bottle
     * back). Once each a gathering, in its own time.
     */
    public static void mingle(VillageFolkEntity f, ServerLevel level, Assemblies.Kind kind, UUID village, String subject, long day, RandomSource r) {
        if (r.nextInt(25) != 0) return;
        serve(f, level, kind, village, subject, day);
    }

    static boolean serve(VillageFolkEntity f, ServerLevel level, Assemblies.Kind kind, UUID village, String subject, long day) {
        Festivals.Feast feast = kind == Assemblies.Kind.FESTIVAL ? Festivals.feastOf(subject) : null;
        boolean wedding = kind == Assemblies.Kind.WEDDING, founding = kind == Assemblies.Kind.FOUNDING;
        boolean cakeToo = wedding || founding || kind == Assemblies.Kind.FEAST || kind == Assemblies.Kind.CELEBRATION
            || feast != null && feast != Festivals.Feast.MIDWINTER;
        boolean cups = wedding || founding || feast == Festivals.Feast.HARVEST;
        if (!cakeToo && !cups) return false;
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        String key = village + "|" + kind + "|" + day;
        String at = occasion(kind, feast);
        Today t = today(f, day);
        if (cakeToo && !key.equals(t.cakeAt) && slice(level, v, f, key, at, kind, day)) return true;
        if (cups && !f.isBaby() && !key.equals(t.toastAt)) return toast(level, v, f, key, at, feast == Festivals.Feast.HARVEST ? cider() : mead(), day);
        return false;
    }

    /** A slice of the gathering's honey cake: a cake cut out of the stores when the last is eaten. */
    static boolean slice(ServerLevel level, Villages.Village v, VillageFolkEntity f, String key, String at, Assemblies.Kind kind, long day) {
        UUID id = v.id();
        int[] pool = POOLS.computeIfAbsent(key, k -> new int[2]);
        String baker = baker(id);
        if (pool[0] <= 0) {
            ItemStack c = Crafts.takeOne(level, v, s -> s.is(cake()));
            if (c.isEmpty()) return false;
            pool[0] = SLICES_A_CAKE;
            day(id, day).cakes++;
            save(id);
            if (TOLD.add(key)) {
                Gatherings.Wedding w = kind == Assemblies.Kind.WEDDING ? Gatherings.wedding(id) : null;
                Villages.tell(id, day, (w != null ? "the wedding of " + w.names() : at) + " had " + baker + "'s honey cake");
            }
        }
        pool[0]--;
        Today t = today(f, day);
        t.cakeAt = key;
        t.cake = "a slice of " + baker + "'s honey cake at " + at;
        f.persona().remember(day, t.cake, 3);
        eaten(level, f, new ItemStack(cake()));
        f.heal(2.0F);
        f.refreshMood();
        if (f.getRandom().nextInt(3) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Honey cake! " + baker + " has outdone themselves.", "Mm. Still warm.",
                "Another slice? Oh, go on then. No — I mustn't."));
        }
        return true;
    }

    /** A cup in the toast: a bottle opened out of the stores when the last is poured, the bottle back. */
    static boolean toast(ServerLevel level, Villages.Village v, VillageFolkEntity f, String key, String at, Item what, long day) {
        UUID id = v.id();
        Today t = today(f, day);
        if (t.drinks >= (merry(f) ? 2 : 1)) return false;                     // one enough for a sensible one
        int[] pool = POOLS.computeIfAbsent(key, k -> new int[2]);
        if (pool[1] <= 0) {
            if (!Crafts.take(level, v, s -> s.is(what), 1)) return false;
            pool[1] = CUPS_A_BOTTLE;
            Crafts.store(level, v, new ItemStack(Items.GLASS_BOTTLE));      // poured round, the bottle back
            if (TOLD.add(key + "|cups")) Villages.tell(id, day, at + " was toasted in the brewer's " + (what == mead() ? "mead" : "cider"));
        }
        pool[1]--;
        t.toastAt = key;
        t.drinks++;
        t.drink = "a cup of " + (what == mead() ? "mead" : "cider") + " in the toast at " + at;
        if (new ItemStack(what).getItem() instanceof KitchenDrinkItem d) for (MobEffectInstance e : d.effects()) f.addEffect(e);
        day(id, day).drinks++;
        save(id);
        level.playSound(null, f.blockPosition(), SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 0.5F, 1.1F);
        f.swing(InteractionHand.MAIN_HAND);
        f.refreshMood();
        if (f.getRandom().nextInt(3) == 0) {
            FolkTalk.speak(f, at.equals("the wedding") ? FolkTalk.pick(f.getRandom(), "To the happy couple!", "Long life and a full larder!")
                : at.equals("Founding Day") ? "To " + Villages.name(id) + "!" : FolkTalk.pick(f.getRandom(), "To a good harvest!", "To the apples!"));
        }
        return true;
    }

    /**
     * A birthday kept (Birthdays.celebrate): a honey cake out of the stores, cut for the folk whose day it is and the ones
     * close to it, a slice each (eight to a cake). The chronicle hears whose cake it was.
     */
    public static void birthday(ServerLevel level, Villages.Village v, VillageFolkEntity f, long day) {
        ItemStack c = Crafts.takeOne(level, v, s -> s.is(cake()));
        if (c.isEmpty()) return;
        UUID id = v.id();
        String baker = baker(id);
        List<VillageFolkEntity> round = new ArrayList<>();
        round.add(f);
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (round.size() >= SLICES_A_CAKE) break;
            if (a instanceof VillageFolkEntity o && o != f && o.isAlive() && !o.isShowcase() && Birthdays.close(o, f)) round.add(o);
        }
        for (VillageFolkEntity o : round) {
            Today t = today(o, day);
            t.cake = o == f ? "a slice of " + baker + "'s honey cake on its birthday" : "a slice of " + baker + "'s honey cake for " + f.displayNameCap() + "'s birthday";
            o.persona().remember(day, o == f ? baker + " baked a honey cake for my birthday" : t.cake, 3);
            o.refreshMood();
        }
        eaten(level, f, new ItemStack(cake()));
        day(id, day).cakes++;
        save(id);
        Villages.tell(id, day, f.displayNameCap() + "'s birthday was kept with " + baker + "'s honey cake");
        if (!f.isSleeping()) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "A honey cake! For me? Somebody remembered.", "Honey cake on my birthday — that's " + baker + "'s doing!"));
        LOG.info("[MCA-KITCHEN] {}'s birthday at {}: {}'s honey cake, {} slices", f.displayNameCap(), Villages.name(id), baker, round.size());
    }

    // ------------------------------------------------------------------ the fish market's glut (FishMarket.putAway)

    /**
     * The market shut on a glut (the catch over the usual: its price under the town's) with fish unsold: the unsold fish went
     * into the stores, and the cook is to bake them into pies, a dozen at the most.
     */
    public static void glut(ServerLevel level, Villages.Village v, double glut, int unsold) {
        if (glut >= 1.0 || unsold <= 0) return;
        int pies = Math.min(PIES_MOST, glutPending(v.id()) + unsold);
        glutPending(v.id(), pies, level.getDayTime() / 24000L);
        LOG.info("[MCA-KITCHEN] a glut at {}'s market: {} fish for the cook's pies", Villages.name(v.id()), pies);
    }

    // ------------------------------------------------------------------ the healer (Health.tend)

    /** The healer's own, out of the stores: a cup of herbal tea for a cold (a day off it), a bandage for a wound. */
    static final Health.Remedy TEA = new Health.Remedy("a cup of the healer's herbal tea", s -> s.is(tea()), TEA_EASES, 1.0F, true);
    static final Health.Remedy BIND = new Health.Remedy("a fresh bandage", s -> s.is(bandage()), Health.COMFORT, 0.0F, false);

    /** What the healer gives first, if the stores have it: a bandage for the wounded; tea for a cold, once a cold. */
    @Nullable
    static Health.Remedy remedy(ServerLevel level, Villages.Village v, VillageFolkEntity p, boolean wounded) {
        if (wounded) return Market.stock(level, v.id(), BANDAGE) > 0 ? BIND : null;
        Health.State s = p.health();
        if (s.cold <= 0 || TEA_HAD.getOrDefault(p.getUUID(), Long.MIN_VALUE) == s.caughtDay) return null;
        return Market.stock(level, v.id(), s2 -> s2.is(tea())) > 0 ? TEA : null;
    }

    static boolean isRemedy(@Nullable Health.Remedy r) {
        return r == TEA || r == BIND;
    }

    /** After the healer gave it: the tea's cold noted (one cup a cold), the bandage's good (regeneration). */
    static void tended(ServerLevel level, Villages.Village v, VillageFolkEntity p, @Nullable Health.Remedy used) {
        long day = level.getDayTime() / 24000L;
        if (used == TEA) {
            TEA_HAD.put(p.getUUID(), p.health().caughtDay);
            day(v.id(), day).teas++;
            save(v.id());
        } else if (used == BIND) {
            BandageItem.bind(p);
            day(v.id(), day).bandages++;
            save(v.id());
        }
    }

    static String careWords(RandomSource r, Health.Remedy used, VillageFolkEntity p) {
        return used == TEA ? FolkTalk.pick(r, "A cup of tea, " + p.displayNameCap() + " — berries and a spoon of sugar. Drink it while it's hot.",
                "Herbal tea. It'll see that cold off a day sooner.")
            : FolkTalk.pick(r, "Let's have that bandage fresh. Hold still.", "There — bound up clean. Rest it now.");
    }

    // ------------------------------------------------------------------ the watch's kit (WatchKit.fit)

    /** A guard or a cave dweller fitted out: its bandages topped up to three out of the stores when it has fewer than two. */
    static void bandages(ServerLevel level, Villages.Village v, VillageFolkEntity g, List<ItemStack> given) {
        int have = g.countMatching(BANDAGE);
        if (have >= BANDAGES_LOW || g.isPackFull()) return;
        int n = Math.min(BANDAGES_CARRIED - have, Market.stock(level, v.id(), BANDAGE));
        if (n <= 0 || !Crafts.take(level, v, BANDAGE, n)) return;
        ItemStack left = g.insertGiven(WatchKit.mark(new ItemStack(bandage(), n)));
        int in = n - left.getCount();
        if (!left.isEmpty()) Crafts.store(level, v, WatchKit.unmarked(left));
        if (in > 0) given.add(new ItemStack(bandage(), in));
    }

    /** The watch's and the cave team's kit lists (WatchKit.kitWords, CaveDwellers.kitWords): its bandages, its lunch. */
    public static void kitWords(VillageFolkEntity f, List<String> out) {
        int b = f.countMatching(BANDAGE);
        if (b > 0) out.add(b + (b == 1 ? " bandage" : " bandages"));
        if (f.countMatching(LUNCH) > 0) out.add("a packed lunch");
    }

    /** On order for the watch (WatchKit.orders, /village watch): bandages for each guard and cave dweller short of them,
     *  less what the stores hold, and whose work they are. */
    public static List<String> watchOrders(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        int wanting = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive()) continue;
            if (f.stationTask() != StationTask.GUARD && f.stationTask() != StationTask.CAVE
                && f.stationTask() != StationTask.NETHER) continue;                                   // [nether]
            int c = f.countMatching(BANDAGE);
            if (c < BANDAGES_LOW) wanting += BANDAGES_CARRIED - c;
        }
        wanting -= Market.stock(level, id, BANDAGE);
        if (wanting <= 0) return List.of();
        StationTask by = makerOf(trades(id), bandage());
        String whose = by == null ? "nobody yet (a healer or a tailor)" : by == StationTask.SHOP ? "the shop's workshop" : null;
        if (whose == null) {
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a.stationTask() == by && !a.isBaby()) { whose = a.displayNameCap() + " the " + by.title.toLowerCase(Locale.ROOT); break; }
            }
        }
        return List.of(Bench.words(bandage(), wanting) + ": " + whose + ", of the stores' paper and string");
    }

    // ------------------------------------------------------------------ the woods' apples (Woods)

    /** Is the brewer short of apples for the cider it is wanted: the woodcutter shakes a felled crown for them too. */
    public static boolean applesWanted(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
        if (v == null) return false;
        Integer want = wanted(level, v).get(cider());
        return want != null && Market.stock(level, v.id(), s -> s.is(Items.APPLE)) < 3 * want;
    }

    /**
     * The woodcutter's look round (Woods, every ten seconds): the apples lying near it, out of a crown it shook or the
     * leaves of a tree it felled, taken up into its production chest for the couriers to bring in (into its pack if it
     * has none). How many.
     */
    public static int apples(VillageFolkEntity f, ServerLevel level) {
        List<ItemEntity> lying = level.getEntitiesOfClass(ItemEntity.class, f.getBoundingBox().inflate(8.0, 4.0, 8.0),
            e -> e.isAlive() && e.getItem().is(Items.APPLE) && !Sweepers.playersOwn(e));
        if (lying.isEmpty()) return 0;
        BlockPos at = f.productionChest();
        Container chest = at != null && level.isLoaded(at) && level.getBlockEntity(at) instanceof Container c ? c : null;
        int took = 0;
        for (ItemEntity e : lying) {
            ItemStack st = e.getItem().copy();
            ItemStack left = chest != null ? HopperBlockEntity.addItem(null, chest, st, null) : f.insertItem(st);
            int n = st.getCount() - left.getCount();
            if (n <= 0) continue;
            took += n;
            if (left.isEmpty()) e.discard();
            else e.setItem(left);
        }
        if (took > 0) {
            Economy.gathered(f, new ItemStack(Items.APPLE, took), took);
            f.brain("took up " + took + (took == 1 ? " apple" : " apples") + " from under the felled trees, for the cider");
        }
        return took;
    }

    // ------------------------------------------------------------------ a folk's spirits and its card

    /** Its spirits (VillageFolkEntity.refreshMood): the happier for a slice of honey cake today, a little for a drink. */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        Today t = TODAY.get(f.getUUID());
        if (t == null || t.day != day) return m;
        if (!t.cake.isEmpty()) {
            m += 6;
            why.add(new Object[]{ "cake", 9 });
        }
        if (t.drinks > 0) {
            m += t.drinks > 1 ? 5 : 3;
            why.add(new Object[]{ "drink", 4 });
        }
        return m;
    }

    /** How it puts it, asked how it is (FolkTalk.reason). */
    public static String moodWords(VillageFolkEntity f, String why) {
        Today t = TODAY.get(f.getUUID());
        if (t == null) return "";
        if (why.equals("cake") && !t.cake.isEmpty()) return "I had " + t.cake.replace(" on its birthday", " on my birthday") + ". Still smiling.";
        if (why.equals("drink") && !t.drink.isEmpty()) return "I had " + t.drink + (t.drinks > 1 ? ", and one more besides" : "") + ".";
        return "";
    }

    /** Its card's line: its packed lunch, its bandages, and what it had of the kitchen's today. Empty if nothing. */
    public static String cardLine(VillageFolkEntity f) {
        List<String> parts = new ArrayList<>();
        int lunch = f.countMatching(LUNCH);
        if (lunch > 0) parts.add("carries a packed lunch for its midday meal" + (lunch > 1 ? " (" + lunch + ")" : ""));
        int b = f.countMatching(BANDAGE);                    // the watch's and the cave team's show in their kit's line
        if (b > 0 && f.stationTask() != StationTask.GUARD && f.stationTask() != StationTask.CAVE
                && f.stationTask() != StationTask.NETHER) {                                          // [nether] in its kit's line
            parts.add(b + (b == 1 ? " bandage" : " bandages") + " in its pack");
        }
        long day = f.level().getDayTime() / 24000L;
        Today t = TODAY.get(f.getUUID());
        if (t != null && t.day == day) {
            if (!t.lunch.isEmpty()) parts.add("ate " + t.lunch);
            if (!t.slice.isEmpty()) parts.add("had " + t.slice);
            if (!t.cake.isEmpty()) parts.add("had " + t.cake);
            if (!t.drink.isEmpty()) parts.add("had " + t.drink + (t.drinks > 1 ? ", and one more" : ""));
            if (t.bound > 0) parts.add("bound a wound after a fight");
        }
        if (parts.isEmpty()) return "";
        String s = String.join("; ", parts);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1) + ".";
    }

    // ------------------------------------------------------------------ the books: the gazette, the Production page

    /** The gazette's piece on yesterday in the kitchen, or null if nothing came of it. */
    @Nullable
    public static String gazette(UUID village, long day) {
        Day y = dayIfAny(village, day - 1);
        if (y == null) return null;
        List<String> lines = new ArrayList<>();
        if (y.pies > 0) lines.add("The cook turned yesterday's catch into " + TownCalendar.inWords(y.pies) + (y.pies == 1 ? " fish pie." : " fish pies."));
        if (y.lunches > 0) lines.add(capital(TownCalendar.inWords(y.lunches)) + (y.lunches == 1 ? " packed lunch" : " packed lunches")
            + " eaten out at the far plots, the boats and the road.");
        if (y.cakes > 0) lines.add(capital(TownCalendar.inWords(y.cakes)) + (y.cakes == 1 ? " honey cake" : " honey cakes") + " cut at the town's gatherings.");
        if (y.wheels > 0) lines.add(capital(TownCalendar.inWords(y.wheels)) + (y.wheels == 1 ? " cheese wheel" : " cheese wheels")
            + " cut for the stores while food was short.");
        if (y.drinks > 0) lines.add(capital(TownCalendar.inWords(y.drinks)) + " of the brewer's mead and cider drunk at the bar and in the toasts.");
        if (y.teas > 0) lines.add(capital(TownCalendar.inWords(y.teas)) + (y.teas == 1 ? " cup" : " cups") + " of the healer's tea for the sick.");
        if (y.bandages > 0) lines.add(capital(TownCalendar.inWords(y.bandages)) + (y.bandages == 1 ? " bandage" : " bandages") + " bound on the hurt.");
        if (lines.isEmpty()) return null;
        return "§lThe kitchen§r\n" + String.join("\n", lines);
    }

    /** The Production page's reading (Annals): the packed lunches eaten out this week, and what else the kitchen did. */
    public static List<String> reading(UUID village, long today) {
        List<String> out = new ArrayList<>();
        int lunches = week(village, today, d -> d.lunches), pies = week(village, today, d -> d.pies), wheels = week(village, today, d -> d.wheels);
        if (lunches > 0) out.add("Packed lunches: " + lunches + " eaten out at the far plots this week — a walk back to the stores saved each time");
        if (pies > 0) out.add("Fish pies: " + pies + " baked of the market's unsold catch this week");
        if (wheels > 0) out.add("Cheese: " + wheels + (wheels == 1 ? " wheel" : " wheels") + " cut for the stores this week while food was short");
        return out;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ /village items kitchen

    /**
     * /village items kitchen: the kitchen's books (what is wanted, what the stores hold, the week). Operators: {@code stage}
     * lays out the kitchen's eight on a wall of frames and the cheese in its four cuts on a table, and brings the town's folk
     * to use them for the camera (made for the pictures, not out of the stores); {@code stage release} lets them go.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("items").then(Commands.literal("kitchen")
            .executes(Kitchen::page)
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    Villages.Village v = here(ctx);
                    if (v == null) return 0;
                    List<String> out = stage(ctx.getSource().getLevel(), v, BlockPos.containing(ctx.getSource().getPosition()));
                    ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                    return out.size();
                })
                .then(Commands.literal("release").executes(ctx -> {
                    Villages.Village v = here(ctx);
                    if (v == null) return 0;
                    int n = release(ctx.getSource().getLevel(), v);
                    ctx.getSource().sendSuccess(() -> Component.literal(n + " let go"), false);
                    return n;
                }))));
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int page(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        List<String> lines = lines(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }

    /** The kitchen's books in lines: each thing wanted against what the stores hold and who makes it; the week. */
    static List<String> lines(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        out.add("The kitchen and the cellar of " + Villages.name(id) + ":");
        Map<Item, Integer> want = wanted(level, v);
        Set<StationTask> trades = tradesCached(id);
        for (Item it : List.of(lunch(), wheel(), slice(), cake(), mead(), cider(), pie(), tea(), bandage())) {
            int have = Market.stock(level, id, s -> s.is(it));
            Integer w = want.get(it);
            StationTask by = makerOf(trades, it);
            out.add("  " + new ItemStack(it).getHoverName().getString() + ": " + have + " in the stores"
                + (w == null ? "" : ", " + w + " wanted") + (it == slice() ? "" : "; made by " + (by == null ? "nobody yet" : by == StationTask.SHOP
                ? "the shop's workshop" : by.title.toLowerCase(Locale.ROOT))));
        }
        long today = level.getDayTime() / 24000L;
        out.add("This week: " + week(id, today, d -> d.lunches) + " lunches eaten out, " + week(id, today, d -> d.pies) + " pies of the catch, "
            + week(id, today, d -> d.wheels) + " wheels cut, " + week(id, today, d -> d.cakes) + " cakes cut, " + week(id, today, d -> d.drinks)
            + " drinks, " + week(id, today, d -> d.teas) + " cups of tea, " + week(id, today, d -> d.bandages) + " bandages bound");
        int pending = glutPending(id);
        if (pending > 0) out.add("The cook has " + pending + " fish of a glut to bake into pies.");
        return out;
    }

    /**
     * The stage for the pictures, here: a wall of frames with the kitchen's eight (and the cheese's slice) on it, the cheese
     * wheel in its four cuts on a table before it, a café table with a wheel set out and a folk at it; and in front, a row
     * of the town's folk each with one in its hand: a packed lunch, a bandage, mead, cider, tea, honey cake and fish pie.
     * VIEW lines for the camera.
     */
    static List<String> stage(ServerLevel level, Villages.Village v, BlockPos at) {
        List<String> out = new ArrayList<>();
        int px = at.getX(), pz = at.getZ();
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
        // Level ground: grass, and clear air over it.
        for (int x = px - 9; x <= px + 9; x++) {
            for (int z = pz - 12; z <= pz + 8; z++) {
                level.setBlock(new BlockPos(x, y - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, y - 2, z), Blocks.DIRT.defaultBlockState(), 2);
                for (int dy = 0; dy < 6; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        // The wall of frames, its face to the north.
        int wz = pz + 6;
        for (int x = px - 5; x <= px + 5; x++) {
            for (int dy = 0; dy < 3; dy++) level.setBlock(new BlockPos(x, y + dy, wz), Blocks.SPRUCE_PLANKS.defaultBlockState(), 3);
        }
        List<ItemStack> shown = new ArrayList<>();
        for (Item it : List.of(lunch(), wheel(), slice(), cake(), mead(), cider(), pie(), tea(), bandage())) shown.add(new ItemStack(it));
        for (net.minecraft.world.entity.Entity e : level.getEntitiesOfClass(ItemFrame.class,
                new net.minecraft.world.phys.AABB(px - 6, y, wz - 2, px + 6, y + 4, wz + 1))) e.discard();
        for (int i = 0; i < shown.size(); i++) {
            ItemFrame frame = new ItemFrame(level, new BlockPos(px - 4 + i, y + 2, wz - 1), Direction.NORTH);
            frame.setItem(shown.get(i), false);
            frame.setInvulnerable(true);
            level.addFreshEntity(frame);
        }
        // The cheese in its four cuts, on a table of top slabs before the wall.
        for (int i = 0; i < CheeseWheelBlock.SLICES; i++) {
            BlockPos top = new BlockPos(px - 3 + i * 2, y, wz - 3);
            level.setBlock(top, Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP), 3);
            level.setBlock(top.above(), KitchenItems.CHEESE_WHEEL.get().defaultBlockState().setValue(CheeseWheelBlock.CUT, i), 3);
        }
        out.add("VIEW kitchen-1-showcase " + px + " " + (y + 2) + " " + (pz - 1) + " " + px + " " + (y + 2) + " " + wz);
        // The café table: a post, a wheel with a slice gone.
        BlockPos post = new BlockPos(px + 7, y, pz + 2);
        level.setBlock(post, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
        level.setBlock(post.above(), KitchenItems.CHEESE_WHEEL.get().defaultBlockState().setValue(CheeseWheelBlock.CUT, 1), 3);
        // The folk, each with one in hand, facing the camera to the south.
        List<VillageFolkEntity> folk = new ArrayList<>();
        VillageFolkEntity guard = null;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !f.isAlive() || f.isBaby() || f.isShowcase() || f.isSleeping() || f.isPassenger()) continue;
            if (guard == null && f.stationTask() == StationTask.GUARD) guard = f;
            else folk.add(f);
        }
        if (guard != null) folk.add(Math.min(1, folk.size()), guard);
        Item[] props = { lunch(), bandage(), mead(), cider(), tea(), cake(), pie() };
        String[] doing = { "a packed lunch", "a bandage", "mead", "cider", "herbal tea", "honey cake", "fish pie" };
        BlockPos face = new BlockPos(px, y, pz - 12);
        long now = level.getGameTime();
        int n = Math.min(props.length, folk.size());
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = folk.get(i);
            BlockPos spot = new BlockPos(px - 6 + i * 2, y, pz - 4);
            stageHold(f, spot, face);
            ItemStack off = f.getItemBySlot(EquipmentSlot.OFFHAND);
            if (!off.isEmpty() && !prop(off)) {
                ItemStack left = f.insertItem(off.copy());
                if (left.isEmpty()) f.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            }
            if (f.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty() || prop(f.getItemBySlot(EquipmentSlot.OFFHAND))) {
                f.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(props[i]));
            }
            if (props[i] == bandage()) f.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 1200, 0));
            out.add("HOLDS " + f.displayNameCap() + " " + doing[i]);
        }
        if (folk.size() > n) {
            VillageFolkEntity f = folk.get(n);
            stageHold(f, post.relative(Direction.WEST), post);
            out.add("AT THE TABLE " + f.displayNameCap());
        }
        if (n == 0) out.add("no folk free to hold the kitchen's things");
        out.add("VIEW kitchen-2-in-hand " + px + " " + (y + 2) + " " + (pz - 11) + " " + px + " " + (y + 1) + " " + (pz - 4));
        out.add("VIEW kitchen-3-cafe-table " + (post.getX() - 3) + " " + (y + 2) + " " + (post.getZ() - 4) + " " + post.getX() + " " + (y + 1) + " " + post.getZ());
        out.add("VIEW kitchen-4-cheese-cuts " + px + " " + (y + 2) + " " + (wz - 6) + " " + px + " " + (y + 1) + " " + (wz - 3));
        out.addAll(lines(level, v));
        LOG.info("[MCA-KITCHEN] stage at {}: {}", at.toShortString(), out);
        return out;
    }

    private static void stageHold(VillageFolkEntity f, BlockPos spot, BlockPos face) {
        f.getNavigation().stop();
        f.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        float yaw = (float) (Math.atan2(face.getZ() - spot.getZ(), face.getX() - spot.getX()) * (180.0 / Math.PI)) - 90.0F;
        f.setYRot(yaw);
        f.setYHeadRot(yaw);
        f.setYBodyRot(yaw);
        f.setNoAi(true);
        f.addTag(STAGED);
    }

    /** The folk held for the camera let go, the things in their hands taken away again (they were the stage's). */
    static int release(ServerLevel level, Villages.Village v) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !f.getTags().contains(STAGED)) continue;
            f.setNoAi(false);
            f.removeTag(STAGED);
            if (prop(f.getItemBySlot(EquipmentSlot.OFFHAND))) f.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ the tests' hooks

    /** Tests: a turn at the kitchen's work for this maker now (the wants worked out afresh). */
    @Nullable
    public static String craftForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
        if (v == null) return null;
        WANTS.remove(v.id());
        return craftNow(level, v, f, wanted(level, v));
    }

    /** Tests: what the town wants kept, worked out now. */
    public static Map<Item, Integer> wantedForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v == null) return Map.of();
        WANTS.remove(village);
        return wanted(level, v);
    }

    /** Tests: is this hand's work today far off? */
    public static boolean farForTests(VillageFolkEntity f) {
        Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
        return v != null && farToday(f, v);
    }

    /** Tests: its look for a packed lunch now (the minute's wait forgotten). */
    public static boolean packForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
        if (v == null) return false;
        PACK_LOOKED.remove(f.getUUID());
        return pack(f, level, v, level.getGameTime());
    }

    /** Tests: the meal had of the kitchen's now, if any (Meals.tick's look). */
    public static boolean mealOutForTests(ServerLevel level, VillageFolkEntity f, boolean lunch) {
        return f.ownerId() != null && mealOut(level, f, f.ownerId(), lunch ? Meals.Meal.LUNCH : Meals.Meal.SUPPER);
    }

    /** Tests: the cook's look at the tables now. */
    @Nullable
    public static String setOutForTests(ServerLevel level, UUID village, @Nullable VillageFolkEntity cook) {
        Villages.Village v = Villages.get(village);
        return v == null ? null : setOut(level, v, cook);
    }

    /** Tests: the reserve's look now. */
    @Nullable
    public static String reserveForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        return v == null ? null : reserve(level, v, level.getDayTime() / 24000L);
    }

    /** Tests: the folk served at a gathering of this kind now (a slice, or a cup), as when it mingles. */
    public static boolean serveForTests(ServerLevel level, VillageFolkEntity f, Assemblies.Kind kind, String subject) {
        return f.ownerId() != null && serve(f, level, kind, f.ownerId(), subject, level.getDayTime() / 24000L);
    }

    /** Tests: at the tavern's bar now. */
    public static boolean barForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
        return v != null && bar(level, v, f, level.getDayTime() / 24000L, false);
    }

    /** Tests: this folk merry (true), sensible (false), or as its nature has it (null). */
    public static void merryForTests(UUID folk, @Nullable Boolean merry) {
        if (merry == null) MERRY_FOR_TESTS.remove(folk);
        else MERRY_FOR_TESTS.put(folk, merry);
    }

    /** Tests: so many gatherings ahead that want a honey cake. */
    public static void occasionsForTests(UUID village, int n) {
        OCCASIONS_FOR_TESTS.put(village, n);
        WANTS.remove(village);
    }

    /** Tests: one second of its day now (the binding's wait forgotten). */
    public static void secondForTests(VillageFolkEntity f) {
        BOUND.remove(f.getUUID());
        if (f.level() instanceof ServerLevel level) second(f, level);
    }

    /** Tests: the healer at this patient's bedside now (Health.tend), with whatever the stores give. */
    public static String tendForTests(ServerLevel level, VillageFolkEntity patient, @Nullable VillageFolkEntity carer) {
        Villages.Village v = patient.ownerId() == null ? null : Villages.get(patient.ownerId());
        if (v == null) return "";
        Health.tend(level, v, patient, carer, level.getDayTime() / 24000L, level.getGameTime());
        return Health.tendedForTests(patient);
    }

    /** Tests: the kitchen's day: {lunches out, pies of the catch, wheels cut, cakes cut, bandages, teas, drinks}. */
    public static int[] dayForTests(UUID village, long day) {
        Day d = dayIfAny(village, day);
        return d == null ? new int[7] : new int[]{ d.lunches, d.pies, d.wheels, d.cakes, d.bandages, d.teas, d.drinks };
    }

    /** Tests: the fish of a glut still waiting on the cook. */
    public static int glutForTests(UUID village) {
        return glutPending(village);
    }

    /** Tests: the woodcutter's look for apples now. */
    public static int applesForTests(ServerLevel level, VillageFolkEntity f) {
        return apples(f, level);
    }

    /** Tests: the tables of a building (the café's, the tavern's). */
    public static List<BlockPos> tablesForTests(Ledger.Building b) {
        return tables(b);
    }

    /** Tests: the stage for the pictures here, and the folk let go. */
    public static List<String> stageForTests(ServerLevel level, UUID village, BlockPos at) {
        Villages.Village v = Villages.get(village);
        return v == null ? List.of() : stage(level, v, at);
    }

    public static int releaseForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        return v == null ? 0 : release(level, v);
    }
}
