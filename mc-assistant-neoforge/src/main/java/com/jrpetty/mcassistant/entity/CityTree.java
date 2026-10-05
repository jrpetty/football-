package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The city's research: twenty civic improvements in five branches of four, each a slight buff, that a
 * village's leader chooses one at a time and the whole town works toward.
 *
 * <h2>The tree</h2>
 * Five branches — <b>Industry</b>, <b>Land</b>, <b>Homes</b>, <b>Wellbeing</b> and <b>Trade</b> — of four
 * tiers each. A tier is open once the one before it in its branch is done, so a town that wants Watch
 * Drills (Trade, third) must first see to its Market Charter and its Paved Roads. Each costs research
 * points by its tier: 10, 25, 50 and 90, so the whole tree is 875 points.
 *
 * <h2>Points</h2>
 * A village earns its points every morning, with the day's books (Market.tick, straight after the
 * leader's own morning): a point for every four grown folk up to sixteen of them, and a point for
 * every twelve grown folk past that (twelve points at most from its people: a town of two hundred
 * has more hands but not many more scholars); a point for a meeting hall, one for a library and one
 * for a chapel; and a point while its leader holds a mandate for the next age (PROGRESS). Never less
 * than a point a day. A village of twelve grown folk earns three a day and finishes its first civic
 * on its fourth morning; a town of sixty with a hall, a library and a chapel earns nine or ten and
 * finishes all twenty in about ninety days of its own (longer, counting the years it was small).
 *
 * <h2>Who chooses, and why</h2>
 * When nothing is being studied, the leader chooses the next civic among the open ones, by:
 * <ul>
 * <li><b>what it cares about</b> (Values: its seven weights, each civic leaning to one or two of them:
 *     a Provider to the Land, a Homemaker to Homes, a Visionary to Industry, a Merchant to Trade, a
 *     Free Spirit to Wellbeing, a Guardian to Watch Drills and the Healers, a Traditionalist to the
 *     Land and to Wellbeing), and above all what it was elected for (its mandate, +30);</li>
 * <li><b>its nature</b> (a hardworking leader likes Industry, a cheerful or sociable one Wellbeing, a
 *     generous one Homes, a grumpy one the Watch Drills, a shy one the Land...);</li>
 * <li><b>what the village is short of now</b>: food short (the leader's own books) → the Land; houses
 *     short, households waiting or rent owed → Homes; contentment low → Wellbeing; the raiders at the
 *     gate this week → the Watch Drills (and the Healers); the treasury thin → Trade;</li>
 * <li>and half of whatever pulls hardest further up the same branch, so a Guardian who wants the
 *     Watch Drills starts on the Market Charter ("it's the road to Watch Drills"). A little for the
 *     cheaper tiers, too, so the first steps come before the long ones.</li>
 * </ul>
 * The biggest of those is the reason it gives. With no leader, the council chooses what is most
 * pressing (the village's needs alone). The choice goes in the chronicle ("Reeve Bramble set the
 * town to work on Crop Rotation: our fields need it"), the leader says it aloud when it is about, and
 * the morning assembly hears it; so does the day it is finished.
 *
 * <h2>The effects</h2>
 * Each civic touches the real mechanics at one place (named on each below), and each is slight: a
 * civic is worth three to five in the hundred of somebody's pace, a tenth or a fifth off a price.
 *
 * <h2>Where it is kept</h2>
 * In the village's Ledger notes, so it survives a restart: {@code civic.done} (the civics done and
 * the day each was: "COMMON_TOOLS:4,CHEAP_HOMES:9"), {@code civic.now} (the one being studied),
 * {@code civic.points} (points in hand toward it), {@code civic.why} and {@code civic.by} (the
 * leader's reason and its name), {@code civic.since} (the day it was chosen), {@code civic.day}
 * (the last morning counted) and two small carries for the coin effects ({@code civic.fund},
 * {@code civic.counting}).
 *
 * <p>Shown on the village board (a line in "What we're working towards"), on the town's books'
 * Research page (CityScreen, from {@link #report}), by {@code /village research}, and by the folk when
 * asked about the elder's orders.
 */
public final class CityTree {

    private CityTree() {}

    // ------------------------------------------------------------------ the tree

    public enum Branch {
        INDUSTRY("Industry", "the trades: tools, training and guilds"),
        LAND("Land", "the fields, the herds and the larder"),
        HOMES("Homes", "rent, building and buying a house"),
        WELLBEING("Wellbeing", "spirits, health and rest"),
        TRADE("Trade", "the market, the roads and the watch");

        public final String title;
        public final String about;

        Branch(String title, String about) {
            this.title = title;
            this.about = about;
        }
    }

    /** What each tier costs, in research points. */
    static final int[] COST = { 10, 25, 50, 90 };

    public enum Civic {
        // Industry: what the trades work with and how they learn.
        COMMON_TOOLS(Branch.INDUSTRY, 1, "Common Tools", "+3% work pace, every trade",
            "every trade works 3% quicker",
            "A decent tool in every hand, and somebody to keep them sharp: every trade's work goes 3% quicker."),
        APPRENTICE_HALLS(Branch.INDUSTRY, 2, "Apprentice Halls", "+10% trade experience",
            "folk learn their trades a tenth faster",
            "Somewhere the new hands learn from the old: a tenth more experience from every piece of work."),
        GUILD_CHARTERS(Branch.INDUSTRY, 3, "Guild Charters", "+5% pace for the crafts",
            "the smith, tailor, cook, shopkeeper, brewer, enchanter and smelter work 5% quicker",
            "Each craft keeps its own guild and standards: the smith, the tailor, the cook, the shopkeeper, the brewer, "
                + "the enchanter and the smelter work 5% quicker."),
        MASTER_WORKSHOPS(Branch.INDUSTRY, 4, "Master Workshops", "Tools wear 20% slower",
            "tools last a fifth longer",
            "Proper forges and whetstones: a tool is seen to before it breaks, and wears a fifth slower."),
        // Land: the fields, the herds and the larder.
        CROP_ROTATION(Branch.LAND, 1, "Crop Rotation", "+5% farmer pace",
            "farmers work 5% quicker",
            "The fields rested in turn, so they never tire: the farmers work 5% quicker."),
        HERD_BOOKS(Branch.LAND, 2, "Herd Books", "+5% pace: herds, hunt, fish, bees",
            "ranchers, hunters, fishers and beekeepers work 5% quicker",
            "Who sired what, where the fish run and when the bees swarm, written down: ranchers, hunters, fishers "
                + "and beekeepers work 5% quicker."),
        SEED_EXCHANGE(Branch.LAND, 3, "Seed Exchange", "One crop in ten gives one more",
            "a tenth more comes off the fields",
            "The best seed kept and traded field to field: one harvested crop in ten gives one more wheat, carrot, "
                + "potato or beetroot."),
        GRANARIES(Branch.LAND, 4, "Granaries", "Folk eat 10% less",
            "the larder goes a tenth further",
            "Dry stores and a careful hand, so less spoils: folk go a tenth longer between meals, and the larder "
                + "goes a tenth further."),
        // Homes: rent, building and buying.
        CHEAP_HOMES(Branch.HOMES, 1, "Cheap Homes", "Rent a fifth lower",
            "rents fall by a fifth",
            "The village lets its houses cheap: a fifth off every rent of three coins or more, and a rent of a "
                + "coin or two is let off one payday in five."),
        BUILDERS_GUILD(Branch.HOMES, 2, "Builders' Guild", "Building 10% quicker",
            "builders lay their blocks 10% quicker",
            "Builders who know their trade, and a yard to work from: every building goes up 10% quicker."),
        HOME_LOANS(Branch.HOMES, 3, "Home Loans", "House prices 10% lower",
            "houses sell for a tenth less",
            "The village helps a household to its own roof: every house it sells costs a tenth less."),
        HOUSING_FUND(Branch.HOMES, 4, "Housing Fund", "Treasury adds a tenth to house savings",
            "the treasury adds a coin for every ten a household saves toward its house",
            "For every ten coins a renting household puts by toward buying its house, the treasury adds one more "
                + "(while it has the coin)."),
        // Wellbeing: spirits, health and rest.
        FEAST_DAYS(Branch.WELLBEING, 1, "Feast Days", "+3 contentment",
            "the town is 3 points more content",
            "Feast days on the calendar, kept every season: the village's contentment is 3 points higher."),
        TAVERN_SONGS(Branch.WELLBEING, 2, "Tavern Songs", "+3 mood for everyone",
            "everybody's mood is 3 better",
            "Songs at the tavern of an evening, and everybody knows the words: every folk's mood is 3 better."),
        HEALERS(Branch.WELLBEING, 3, "Healers", "Heal twice as fast; live 10% longer",
            "folk mend twice as fast and live a tenth longer",
            "Somebody who knows herbs and bandages: the hurt mend twice as fast between meals, and the old live a "
                + "tenth longer."),
        REST_DAY_CHARTER(Branch.WELLBEING, 4, "Rest Day Charter", "Breaks 10% shorter, +2 mood",
            "breaks are a tenth shorter, and folk feel 2 better for them",
            "Rest kept by the charter — rested, not idle: breaks are a tenth shorter, and every folk's mood is 2 better."),
        // Trade: the market, the roads and the watch.
        MARKET_CHARTER(Branch.TRADE, 1, "Market Charter", "+5% market takings",
            "the village takes 5% more for its work and its goods",
            "A charter for the market, so traders come from further off: the village's daily takings and what the "
                + "market-day traders pay are 5% higher."),
        PAVED_ROADS(Branch.TRADE, 2, "Paved Roads", "Folk walk 5% faster",
            "everybody walks 5% faster",
            "Stone underfoot from door to field: everybody walks 5% faster."),
        WATCH_DRILLS(Branch.TRADE, 3, "Watch Drills", "Guards +1 armour, +1 attack",
            "guards have a point more armour and hit a point harder",
            "The watch drills at dawn: every guard has a point more armour and hits a point harder."),
        COUNTING_HOUSE(Branch.TRADE, 4, "Counting House", "Wages cost 5% less",
            "the wages cost the treasury a twentieth less",
            "A clerk who counts every coin: a twentieth of each payday's wages comes back to the treasury.");

        public final Branch branch;
        public final int tier;
        public final String title;
        /** Short, for the tree's nodes: "+3% work pace, every trade". */
        public final String effect;
        /** As news has it, once done: "every trade works 3% quicker". */
        public final String result;
        /** The whole of it, for the tooltip and the books. */
        public final String about;

        Civic(Branch branch, int tier, String title, String effect, String result, String about) {
            this.branch = branch;
            this.tier = tier;
            this.title = title;
            this.effect = effect;
            this.result = result;
            this.about = about;
        }

        public int cost() {
            return COST[tier - 1];
        }

        /** For commands and the Ledger: "common_tools". */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The civic that must be done first (the tier below in its branch), or null for a first tier. */
        @Nullable
        public Civic before() {
            if (tier == 1) return null;
            for (Civic c : values()) if (c.branch == branch && c.tier == tier - 1) return c;
            return null;
        }

        /**
         * How much each of the seven things a folk can care about leans to this civic, in the hundred:
         * the whole of a branch leans to its own (the Land to a full larder, Homes to homes, Industry
         * to the next age, Trade to wages and trade, Wellbeing to rest and merriment), the old ways
         * to the Land and to Wellbeing a little, and safe streets to the Watch Drills and the Healers.
         */
        int affinity(Values.Value v) {
            if (this == WATCH_DRILLS) return v == Values.Value.SAFETY ? 100 : v == Values.Value.WEALTH ? 20 : 0;
            if (this == HEALERS) return v == Values.Value.SAFETY ? 80 : v == Values.Value.LEISURE ? 60 : v == Values.Value.TRADITION ? 30 : 0;
            if (this == PAVED_ROADS && v == Values.Value.PROGRESS) return 40;
            if (this == GUILD_CHARTERS && v == Values.Value.WEALTH) return 40;
            if (this == APPRENTICE_HALLS && v == Values.Value.TRADITION) return 30;
            if ((this == HOME_LOANS || this == HOUSING_FUND) && v == Values.Value.WEALTH) return 30;
            return switch (branch) {
                case INDUSTRY -> v == Values.Value.PROGRESS ? 100 : 0;
                case LAND -> v == Values.Value.FOOD ? 100 : v == Values.Value.TRADITION ? 40 : 0;
                case HOMES -> v == Values.Value.HOMES ? 100 : 0;
                case WELLBEING -> v == Values.Value.LEISURE ? 100 : v == Values.Value.TRADITION ? 30 : 0;
                case TRADE -> v == Values.Value.WEALTH ? 100 : 0;
            };
        }
    }

    @Nullable
    public static Civic byKey(@Nullable String key) {
        if (key == null || key.isBlank()) return null;
        String k = key.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace("'", "");
        for (Civic c : Civic.values()) if (c.name().equals(k)) return c;
        return null;
    }

    public static final int ALL = Civic.values().length;

    // ------------------------------------------------------------------ the numbers (each slight)

    /** Common Tools: every trade's pace, in percent. */
    static final int TOOLS_PACE = 3;
    /** Guild Charters, Crop Rotation, Herd Books: their trades' pace, in percent. */
    static final int TRADE_PACE = 5;
    /** Builders' Guild: the builders' pace, in percent. */
    static final int BUILD_PACE = 10;
    /** Apprentice Halls: more experience, in percent. */
    static final int MORE_XP = 10;
    /** Master Workshops: one use of a tool in this many is not charged to it (a fifth slower wear). */
    static final int SPARED_ONE_IN = 5;
    /** Seed Exchange: one harvested crop in this many gives one more. */
    static final int SEED_ONE_IN = 10;
    /** Granaries: the time between meals, in percent of the usual (a tenth less eaten). */
    static final int MEALS = 111;
    /** Home Loans: a house's price, in percent. */
    static final int PRICE = 90;
    /** Housing Fund: the treasury adds one coin for every this many put by. */
    static final int FUND_ONE_IN = 10;
    /** Feast Days: contentment, in points. */
    static final int FEAST = 3;
    /** Tavern Songs and the Rest Day Charter: mood, in points. */
    static final int SONGS = 3, RESTED = 2;
    /** Rest Day Charter: a break's length, in percent. */
    static final int REST = 90;
    /** Market Charter: the takings, in percent. */
    static final int TAKINGS = 105;
    /** Paved Roads: walking speed, a share more. */
    static final double ROADS = 0.05;
    /** Watch Drills: a guard's armour and its blow, in points. */
    static final double DRILL_ARMOUR = 1.0, DRILL_HIT = 1.0;
    /** Counting House: one coin of every this many paid in wages comes back. */
    static final int COUNTING_ONE_IN = 20;

    private static final ResourceLocation ROADS_ID = ResourceLocation.fromNamespaceAndPath("mc_assistant", "civic_paved_roads");
    private static final ResourceLocation DRILL_ARMOUR_ID = ResourceLocation.fromNamespaceAndPath("mc_assistant", "civic_watch_drills_armour");
    private static final ResourceLocation DRILL_HIT_ID = ResourceLocation.fromNamespaceAndPath("mc_assistant", "civic_watch_drills_hit");

    // ------------------------------------------------------------------ where it is kept

    static final String DONE = "civic.done", NOW = "civic.now", POINTS = "civic.points", WHY = "civic.why",
        BY = "civic.by", SINCE = "civic.since", DAY = "civic.day", FUND = "civic.fund", COUNTING = "civic.counting";

    /** The civics done, as last read from the Ledger: kept with the note it was read from, so a change is seen at once. */
    private record Read(String raw, Map<Civic, Long> done) {}

    private static final Map<UUID, Read> READ = new ConcurrentHashMap<>();

    public static void resetForTests() {
        READ.clear();
    }

    /**
     * The civics this village has done, in the order it did them, with the day each was done.
     * Asked on every pace sum of every worker, so it is read once and kept until the note changes.
     */
    public static Map<Civic, Long> done(@Nullable UUID village) {
        if (village == null) return Map.of();
        String raw = Ledger.note(village, DONE);
        if (raw == null || raw.isEmpty()) return Map.of();
        Read r = READ.get(village);
        if (r != null && r.raw().equals(raw)) return r.done();
        Map<Civic, Long> m = new LinkedHashMap<>();
        for (String part : raw.split(",")) {
            String[] kv = part.split(":");
            Civic c = byKey(kv[0]);
            if (c == null) continue;
            long day = -1L;
            try { if (kv.length > 1) day = Long.parseLong(kv[1]); } catch (NumberFormatException ignored) { }
            m.put(c, day);
        }
        Map<Civic, Long> kept = Collections.unmodifiableMap(m);
        READ.put(village, new Read(raw, kept));
        return kept;
    }

    private static void saveDone(UUID village, Map<Civic, Long> done) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Civic, Long> e : done.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey().name()).append(':').append(e.getValue());
        }
        if (sb.length() == 0) Ledger.forget(village, DONE);
        else Ledger.note(village, DONE, sb.toString());
    }

    /** Has the village done this civic? */
    public static boolean has(@Nullable UUID village, Civic c) {
        return village != null && done(village).containsKey(c);
    }

    /** What the village is studying now, or null. */
    @Nullable
    public static Civic current(@Nullable UUID village) {
        return village == null ? null : byKey(Ledger.note(village, NOW));
    }

    /** Research points in hand toward the one being studied. */
    public static int points(@Nullable UUID village) {
        return village == null ? 0 : parse(Ledger.note(village, POINTS));
    }

    /** Is this civic open to study: not done, and the one before it in its branch done? */
    public static boolean open(@Nullable UUID village, Civic c) {
        if (village == null || has(village, c)) return false;
        Civic b = c.before();
        return b == null || has(village, b);
    }

    /** Every civic open to study now, in the tree's order. */
    public static List<Civic> openNow(@Nullable UUID village) {
        List<Civic> out = new ArrayList<>();
        for (Civic c : Civic.values()) if (open(village, c)) out.add(c);
        return out;
    }

    // ------------------------------------------------------------------ the effects, at their hooks

    /**
     * What the city's research adds to the pace of this trade's work, in percent (VillageFolkEntity
     * .skillWorkPercent, into every pace sum): Common Tools 3 for every trade; Guild Charters 5 for the
     * crafts; Crop Rotation 5 for farmers; Herd Books 5 for ranchers, hunters, fishers and beekeepers.
     * Eight at the most for any one trade.
     */
    public static int workPercent(@Nullable UUID village, StationTask trade) {
        if (village == null || trade == null || trade == StationTask.NONE) return 0;
        Map<Civic, Long> d = done(village);
        if (d.isEmpty()) return 0;
        int p = 0;
        if (d.containsKey(Civic.COMMON_TOOLS)) p += TOOLS_PACE;
        if (d.containsKey(Civic.GUILD_CHARTERS) && crafts(trade)) p += TRADE_PACE;
        if (d.containsKey(Civic.CROP_ROTATION) && trade == StationTask.FARM) p += TRADE_PACE;
        if (d.containsKey(Civic.HERD_BOOKS) && herds(trade)) p += TRADE_PACE;
        return p;
    }

    /** The crafts the Guild Charters cover: the smith, tailor, cook, shopkeeper, brewer, enchanter and smelter. */
    static boolean crafts(StationTask t) {
        return switch (t) {
            case SMITH, TAILOR, COOK, SHOP, BREW, ENCHANT, SMELT -> true;
            default -> false;
        };
    }

    /** The trades the Herd Books cover: ranchers, hunters, fishers and beekeepers. */
    static boolean herds(StationTask t) {
        return switch (t) {
            case RANCH, HUNT, FISH, BEEKEEP -> true;
            default -> false;
        };
    }

    /** Builders' Guild: what it adds to the pace a builder lays its blocks at, in percent (AssistantEntity.buildPaceTicks). */
    public static int buildPercent(@Nullable UUID village) {
        return has(village, Civic.BUILDERS_GUILD) ? BUILD_PACE : 0;
    }

    /**
     * Apprentice Halls: the experience a piece of work earns, in hundredths of a point, with a tenth
     * more (AssistantEntity.note). A block of stone earns a few hundredths, so the odd hundredth is
     * a matter of chance in proportion: over a shift it comes to a tenth more, neither more nor less.
     */
    public static int moreXp(@Nullable UUID village, int cents, RandomSource random) {
        if (cents <= 0 || !has(village, Civic.APPRENTICE_HALLS)) return cents;
        int extra = cents * MORE_XP;                       // in hundredths of a hundredth
        return cents + extra / 100 + (random.nextInt(100) < extra % 100 ? 1 : 0);
    }

    /** Master Workshops: is this use of a tool spared the wear (AssistantEntity.damageHeldTool)? One in five. */
    public static boolean sparesTool(@Nullable UUID village, RandomSource random) {
        return has(village, Civic.MASTER_WORKSHOPS) && random.nextInt(SPARED_ONE_IN) == 0;
    }

    /**
     * Seed Exchange: a crop just harvested (FarmGoal.doHarvest), and one in ten gives one more of
     * what it grows (wheat, a carrot, a potato, a beetroot), into the farmer's pack — counted as its
     * making, as the rest of the harvest is.
     */
    public static void seedExchange(AssistantEntity a, Block crop) {
        if (!(a instanceof VillageFolkEntity f) || !has(f.ownerId(), Civic.SEED_EXCHANGE)) return;
        if (a.getRandom().nextInt(SEED_ONE_IN) != 0) return;
        Item produce = crop == Blocks.WHEAT ? Items.WHEAT : crop == Blocks.CARROTS ? Items.CARROT
            : crop == Blocks.POTATOES ? Items.POTATO : crop == Blocks.BEETROOTS ? Items.BEETROOT : null;
        if (produce == null) return;
        ItemStack one = new ItemStack(produce);
        ItemStack left = a.insertItem(one.copy());
        Economy.gathered(a, one, 1 - left.getCount());
        if (!left.isEmpty()) a.spawnAtLocation(left);
    }

    /** Granaries: the time between a folk's meals, in percent of the usual (VillageFolkEntity.traitUpkeepPercent). */
    public static int mealPercent(@Nullable UUID village) {
        return has(village, Civic.GRANARIES) ? MEALS : 100;
    }

    /**
     * Cheap Homes: the rent the village asks for a house (Homes.rent), given what it would ask
     * without it: a fifth off. Most rents are a coin or two a day, and a fifth off a coin is nothing,
     * so a rent of one or two coins stays as it is and is let off one payday in five instead
     * ({@link #rentFreeToday}); a rent of three or more (a manor, a two-storey house in a city) is
     * four-fifths of itself, rounded. Either way a tenant pays a fifth less over the week.
     */
    public static int rent(@Nullable UUID village, int rent) {
        if (rent <= 2 || !has(village, Civic.CHEAP_HOMES)) return rent;
        return Math.max(2, Math.round(rent * 0.8F));
    }

    /** Cheap Homes: is a rent of one or two coins (before the cut) let off today, one payday in five (Homes.tenants)? */
    public static boolean rentFreeToday(@Nullable UUID village, int baseRent, long day) {
        return baseRent <= 2 && Math.floorMod(day, 5L) == 0 && has(village, Civic.CHEAP_HOMES);
    }

    /** Home Loans: what a house sells for, in percent (Homes.price). */
    public static int pricePercent(@Nullable UUID village) {
        return has(village, Civic.HOME_LOANS) ? PRICE : 100;
    }

    /**
     * Housing Fund: a renting household put {@code put} coins by toward buying its house today
     * (Homes.tenants); the treasury adds a coin for every ten, as far as {@code room} (what is
     * still wanted of the price) and its own purse go. The odd coins are carried over from one
     * household's saving to the next, so a village of small savers is matched as fully as a rich one.
     * Returns what the treasury added.
     */
    public static int housingFund(@Nullable UUID village, int put, int room) {
        if (village == null || put <= 0 || room <= 0 || !has(village, Civic.HOUSING_FUND)) return 0;
        int carry = parse(Ledger.note(village, FUND)) + put;
        Ledger.note(village, FUND, Integer.toString(carry % FUND_ONE_IN));
        int match = Math.min(room, carry / FUND_ONE_IN);
        if (match <= 0) return 0;
        int paid = Ledger.takeCoins(village, match);
        Economy.spent(village, paid);
        return paid;
    }

    /** Feast Days: what it adds to the village's contentment (Contentment.compute). */
    public static int contentment(@Nullable UUID village) {
        return has(village, Civic.FEAST_DAYS) ? FEAST : 0;
    }

    /** Tavern Songs and the Rest Day Charter: what they add to every folk's mood (VillageFolkEntity.refreshMood). */
    public static int moodBonus(@Nullable UUID village) {
        if (village == null) return 0;
        Map<Civic, Long> d = done(village);
        return (d.containsKey(Civic.TAVERN_SONGS) ? SONGS : 0) + (d.containsKey(Civic.REST_DAY_CHARTER) ? RESTED : 0);
    }

    /** What a folk says of it, asked how it is (FolkTalk.reason, "civic"). */
    public static String moodWords(VillageFolkEntity f) {
        UUID v = f.ownerId();
        boolean songs = has(v, Civic.TAVERN_SONGS), rest = has(v, Civic.REST_DAY_CHARTER);
        RandomSource r = f.getRandom();
        if (songs && rest) return FolkTalk.pick(r, "Songs of an evening and a proper rest by the charter — I'm the better for both.",
            "Between the tavern songs and the charter's rest, life's good here.");
        if (songs) return FolkTalk.pick(r, "There's singing at the tavern of an evening. Lifts the heart.",
            "I know all the tavern songs now. Can't help humming them.");
        if (rest) return "The charter's rest does me good: rested, not idle.";
        return "";
    }

    /** Healers: how long a folk lives, given the years it would have (VillageFolkEntity.lifespan): a tenth more. */
    public static int lifespan(@Nullable UUID village, int years) {
        return has(village, Civic.HEALERS) ? years + years / 10 : years;
    }

    /** Rest Day Charter: a break's length, in percent of what it would be (Leader.restScale). */
    public static int restPercent(@Nullable UUID village) {
        return has(village, Civic.REST_DAY_CHARTER) ? REST : 100;
    }

    /** Market Charter: the village's takings and the market-day traders' prices, in percent (Market.takings, Market.sellSurplus). */
    public static int takingsPercent(@Nullable UUID village) {
        return has(village, Civic.MARKET_CHARTER) ? TAKINGS : 100;
    }

    /**
     * Counting House: this payday paid {@code paid} coins in wages (Market.payWages); a twentieth of
     * it comes back to the treasury (the folk keep every coin of theirs). The odd coins are carried to
     * the next payday. Returns what comes back.
     */
    public static int countingHouse(@Nullable UUID village, int paid) {
        if (village == null || paid <= 0 || !has(village, Civic.COUNTING_HOUSE)) return 0;
        int carry = parse(Ledger.note(village, COUNTING)) + paid;
        Ledger.note(village, COUNTING, Integer.toString(carry % COUNTING_ONE_IN));
        return carry / COUNTING_ONE_IN;
    }

    /**
     * Every eight seconds, for every folk (VillageFolkEntity.aiStep): Paved Roads in its step and the
     * Watch Drills on a guard (kept up to date as it changes trade), and the Healers' care: the hurt
     * get back half a heart, as much again as the slow mending a folk with nothing to eat gets every
     * eight seconds (AssistantEntity), so it mends twice as fast between meals.
     */
    public static void tend(VillageFolkEntity f) {
        UUID v = f.ownerId();
        if (v == null) return;
        dress(f, v);
        if (has(v, Civic.HEALERS) && f.isAlive() && f.getHealth() < f.getMaxHealth() && f.hurtTime == 0
                && f.tickCount - f.getLastHurtByMobTimestamp() > 100) {
            f.heal(1.0F);
        }
    }

    /** Paved Roads and the Watch Drills, as attribute modifiers (transient: put back after a load by the next tend). */
    static void dress(VillageFolkEntity f, @Nullable UUID v) {
        Map<Civic, Long> d = done(v);
        modifier(f, Attributes.MOVEMENT_SPEED, ROADS_ID, d.containsKey(Civic.PAVED_ROADS) ? ROADS : 0.0,
            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        boolean drilled = d.containsKey(Civic.WATCH_DRILLS) && f.stationTask() == StationTask.GUARD && !f.isBaby();
        modifier(f, Attributes.ARMOR, DRILL_ARMOUR_ID, drilled ? DRILL_ARMOUR : 0.0, AttributeModifier.Operation.ADD_VALUE);
        modifier(f, Attributes.ATTACK_DAMAGE, DRILL_HIT_ID, drilled ? DRILL_HIT : 0.0, AttributeModifier.Operation.ADD_VALUE);
    }

    private static void modifier(LivingEntity e, Holder<Attribute> attribute, ResourceLocation id, double amount,
                                 AttributeModifier.Operation op) {
        AttributeInstance a = e.getAttribute(attribute);
        if (a == null) return;
        if (amount == 0.0) {
            if (a.hasModifier(id)) a.removeModifier(id);
            return;
        }
        AttributeModifier m = a.getModifier(id);
        if (m != null && m.amount() == amount) return;
        a.addOrUpdateTransientModifier(new AttributeModifier(id, amount, op));
    }

    // ------------------------------------------------------------------ the points

    /** The points the village earns a morning, and what from: {total} and the words ("12 grown folk 3, the meeting hall 1"). */
    public record Rate(int points, String why) {}

    public static Rate rate(@Nullable UUID village) {
        if (village == null) return new Rate(0, "");
        int grown = 0;
        List<AssistantEntity> folk = Villages.folkOf(village);
        for (AssistantEntity a : folk) if (a instanceof VillageFolkEntity f && !f.isBaby() && !f.isShowcase()) grown++;
        if (folk.isEmpty()) grown = Villages.headcount(village);       // nobody loaded: go by the books
        int people = Math.min(12, Math.min(grown, 16) / 4 + Math.max(0, grown - 16) / 12);
        List<String> why = new ArrayList<>();
        why.add(grown + " grown folk " + people);
        int total = people;
        if (Villages.hasBuilt(village, "hall")) { total++; why.add("the meeting hall 1"); }
        if (Villages.hasBuilt(village, "library")) { total++; why.add("the library 1"); }
        if (Villages.hasBuilt(village, "chapel")) { total++; why.add("the chapel 1"); }
        if (Elections.mandate(village) == Values.Value.PROGRESS) { total++; why.add("a leader elected for the next age 1"); }
        if (total < 1) { why.add("never less than 1"); total = 1; }
        return new Rate(total, String.join(", ", why));
    }

    /** Mornings still to go at this rate: 0 when it is paid for. */
    static int daysLeft(UUID village, Civic c) {
        int need = c.cost() - points(village);
        int r = Math.max(1, rate(village).points());
        return need <= 0 ? 0 : (need + r - 1) / r;
    }

    // ------------------------------------------------------------------ the morning

    /**
     * The village's morning, with its books (Market.tick, after the leader's own morning): the day's
     * points earned; a civic chosen if none is being studied; and once enough points are in hand, the
     * civic is done (said in the chronicle, by the leader and at the assembly) and the next is chosen.
     * The points over go toward the next. Once a day, whatever calls it.
     */
    public static void morning(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (Long.toString(day).equals(Ledger.note(id, DAY))) return;
        Ledger.note(id, DAY, Long.toString(day));
        if (done(id).size() >= ALL) return;
        Civic now = current(id);
        if (now == null || !open(id, now)) now = choose(level, id, day);
        int points = points(id) + rate(id).points();
        if (now != null && points >= now.cost()) {
            points -= now.cost();
            complete(level, id, now, day);
            now = choose(level, id, day);
        }
        Ledger.note(id, POINTS, Integer.toString(now == null ? 0 : points));
    }

    /** It is done: on the books with its day, and said. */
    static void complete(ServerLevel level, UUID village, Civic c, long day) {
        Map<Civic, Long> d = new LinkedHashMap<>(done(village));
        d.put(c, day);
        saveDone(village, d);
        // How long it took, if it was studied (not granted out of turn).
        String took = "";
        if (c == current(village)) {
            Ledger.forget(village, NOW);
            String sinceNote = Ledger.note(village, SINCE);
            long since = sinceNote == null ? day : parse(sinceNote);
            if (day > since) took = " after " + (day - since) + (day - since == 1 ? " day's" : " days'") + " study";
        }
        Villages.tell(village, day, "the town finished " + c.title + took + ": " + c.result);
        Market.assemblyNews(village, "We've finished " + c.title + ": " + c.result + ". Well done, all of you.");
        VillageFolkEntity elder = Orders.elderOf(village);
        if (elder != null) {
            FolkTalk.speak(elder, FolkTalk.pick(level.getRandom(), c.title + "'s done! " + capital(c.result) + ".",
                "We've finished " + c.title + " — " + c.result + ".", c.title + ", done at last. You'll feel the difference."));
            elder.persona().remember(day, "I saw " + c.title + " through for the town on day " + day, 4);
        }
        // What it changes about the folk themselves, at once rather than at their next turn.
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f) dress(f, village);
    }

    // ------------------------------------------------------------------ choosing

    /** What the village is short of now, in the leader's eyes, with the words for each. */
    record Needs(int food, String foodWhy, int homes, String homesWhy, int mood, String moodWhy, int safety, int money) {

        /** How much this civic answers the village's needs, and the words. */
        Object[] of(Civic c) {
            return switch (c) {
                case COMMON_TOOLS, APPRENTICE_HALLS, GUILD_CHARTERS, MASTER_WORKSHOPS -> new Object[]{ 0, "" };
                case CROP_ROTATION, HERD_BOOKS, SEED_EXCHANGE, GRANARIES -> new Object[]{ food, foodWhy };
                case CHEAP_HOMES, BUILDERS_GUILD, HOME_LOANS, HOUSING_FUND -> new Object[]{ homes, homesWhy };
                case FEAST_DAYS, TAVERN_SONGS, REST_DAY_CHARTER -> new Object[]{ mood, moodWhy };
                case HEALERS -> safety / 2 >= mood / 2 ? new Object[]{ safety / 2, "the raiders were at the gate" }
                    : new Object[]{ mood / 2, moodWhy };
                case WATCH_DRILLS -> new Object[]{ safety, "the raiders were at the gate" };
                case MARKET_CHARTER, COUNTING_HOUSE -> new Object[]{ money, "the treasury's thin" };
                case PAVED_ROADS -> new Object[]{ money / 2, "the treasury's thin" };
            };
        }
    }

    static Needs needs(ServerLevel level, UUID village) {
        int food = 0;
        String foodWhy = "";
        Leader.Plan plan = Leader.plan(village);
        if (plan == Leader.Plan.FAMINE) { food = 40; foodWhy = "we're near out of food"; }
        else if (plan == Leader.Plan.SHORT) { food = 25; foodWhy = "our fields need it"; }
        else if (Market.hungry(village)) { food = 15; foodWhy = "the larder's low"; }
        int homes = 0;
        String homesWhy = "";
        int head = Villages.headcount(village);
        int[] counts = Homes.counts(level, village);
        if (Villages.housing(village) < head || (counts.length > 1 && counts[1] > 0)) { homes += 20; homesWhy = "families are waiting for homes"; }
        if (counts.length > 11 && counts[11] > 0) { homes += 10; if (homesWhy.isEmpty()) homesWhy = "rent is owed all over town"; }
        int content = Contentment.score(village);
        int mood = content < 40 ? 25 : content < 55 ? 10 : 0;
        String moodWhy = content < 40 ? "folk are low, and deserve better" : "folk could be happier";
        long day = level.getDayTime() / 24000L;
        int safety = Raids.underAlarm(village) || day - Raids.raidedOn(village) <= 5 ? 30 : 0;
        int money = Ledger.coins(village) < 2 * Math.max(1, Market.wageBill(village)) ? 20 : 0;
        return new Needs(food, foodWhy, homes, homesWhy, mood, moodWhy, safety, money);
    }

    /** One civic's pull on whoever chooses: its parts summed, and the words (chronicle, and its own mouth) for the biggest. */
    static final class Appeal {
        double total, best;
        String why = "", said = "";

        void add(double v, String why, String said) {
            if (v <= 0) return;
            total += v;
            if (v > best) {
                best = v;
                this.why = why;
                this.said = said;
            }
        }
    }

    /** How much a leader of this nature leans to this civic, by its traits. */
    static int traitPull(Civic c, Social.Trait t) {
        return switch (t) {
            case HARDWORKING -> c.branch == Branch.INDUSTRY ? 10 : 0;
            case CURIOUS -> c.branch == Branch.INDUSTRY ? 6 : c.branch == Branch.TRADE ? 4 : 0;
            case CHEERFUL -> c.branch == Branch.WELLBEING ? 10 : 0;
            case SOCIABLE -> c.branch == Branch.WELLBEING ? 6 : c.branch == Branch.TRADE ? 4 : 0;
            case EASYGOING -> c.branch == Branch.WELLBEING ? 6 + (c == Civic.REST_DAY_CHARTER ? 4 : 0) : 0;
            case GENEROUS -> c.branch == Branch.HOMES ? 6 : c.branch == Branch.LAND ? 4 : 0;
            case GRUMPY -> c == Civic.WATCH_DRILLS ? 10 : 0;
            case SHY -> c.branch == Branch.LAND ? 6 : c == Civic.HEALERS ? 4 : 0;
        };
    }

    /** {chronicle, its own words} for a leader choosing out of its nature. */
    static String[] traitWords(Social.Trait t) {
        return switch (t) {
            case HARDWORKING -> new String[]{ "a hardworking leader's choice", "good tools make good work" };
            case CURIOUS -> new String[]{ "a curious leader's choice", "I want to see what it can do for us" };
            case CHEERFUL -> new String[]{ "a cheerful leader's choice", "a happy town's a good town" };
            case SOCIABLE -> new String[]{ "a sociable leader's choice", "folk should have time for each other" };
            case EASYGOING -> new String[]{ "an easygoing leader's choice", "nobody should be worked to the bone" };
            case GENEROUS -> new String[]{ "a generous leader's choice", "I'd see everybody looked after" };
            case GRUMPY -> new String[]{ "a grumpy leader's choice", "somebody has to keep the riff-raff out" };
            case SHY -> new String[]{ "a quiet leader's choice", "the old ways keep us fed" };
        };
    }

    /** What pulls the chooser to this civic on its own account (not counting what it leads to). */
    static Appeal aim(Civic c, @Nullable VillageFolkEntity elder, @Nullable Values.Value mandate, Needs needs) {
        Appeal a = new Appeal();
        if (elder != null) {
            int[] w = Values.of(elder);
            for (Values.Value v : Values.Value.values()) {
                int aff = c.affinity(v);
                if (aff > 0) a.add(w[v.ordinal()] * aff / 200.0, "for " + v.cares, v.cares + " matters most to me");
            }
            for (Social.Trait t : elder.life().traits()) {
                String[] words = traitWords(t);
                a.add(traitPull(c, t), words[0], words[1]);
            }
        }
        if (mandate != null && c.affinity(mandate) >= 80) a.add(30, "what it was elected for, " + mandate.cares,
            "it's what you elected me for: " + mandate.cares);
        Object[] need = needs.of(c);
        String nw = (String) need[1];
        a.add((Integer) need[0], nw, nw);
        return a;
    }

    /** A civic the chooser might take, its score and why. */
    public record Weighed(Civic civic, double score, String why, String said) {}

    /**
     * Every open civic, weighed as the leader (or, with none, the council) would: what it cares about,
     * its nature, its mandate and the village's needs, plus half the pull of the best thing further up
     * the same branch, less a little for the dearer tiers. Best first; ties go to the tree's order.
     */
    public static List<Weighed> weigh(ServerLevel level, UUID village) {
        VillageFolkEntity elder = Orders.elderOf(village);
        if (elder != null && elder.isBaby()) elder = null;
        Values.Value mandate = Elections.mandate(village);
        Needs needs = needs(level, village);
        Map<Civic, Long> done = done(village);
        Map<Civic, Appeal> aims = new LinkedHashMap<>();
        for (Civic c : Civic.values()) if (!done.containsKey(c)) aims.put(c, aim(c, elder, mandate, needs));
        List<Weighed> out = new ArrayList<>();
        for (Civic c : Civic.values()) {
            if (!open(village, c)) continue;
            Appeal a = aims.get(c);
            double path = 0;
            Civic toward = null;
            for (Civic later : Civic.values()) {
                if (later.branch != c.branch || later.tier <= c.tier || done.containsKey(later)) continue;
                double p = aims.get(later).total * 0.5;
                if (p > path) { path = p; toward = later; }
            }
            double score = a.total + path - 2.0 * (c.tier - 1);
            String why, said;
            if (toward != null && path > a.best) {
                why = "the road to " + toward.title;
                said = "it's the road to " + toward.title;
            } else if (!a.why.isEmpty()) {
                why = a.why;
                said = a.said;
            } else {
                why = elder == null ? "the most pressing" : "the next step";
                said = elder == null ? "it's the most pressing" : "it's the next step";
            }
            out.add(new Weighed(c, score, why, said));
        }
        out.sort((x, y) -> Double.compare(y.score(), x.score()) != 0 ? Double.compare(y.score(), x.score())
            : Integer.compare(x.civic().ordinal(), y.civic().ordinal()));
        return out;
    }

    /** Who chooses: "Reeve Bramble", or the council with nobody leading. */
    static String chooser(UUID village) {
        VillageFolkEntity elder = Orders.elderOf(village);
        if (elder != null) return Leader.leaderName(village, elder);
        String name = Villages.elderName(village);
        if (!name.isEmpty()) return capital(Homeland.leaderTitle(village)) + " " + name;
        return "The council";
    }

    /**
     * The leader chooses the next civic and sets the town to work on it: in the chronicle, aloud
     * (when it is about), and to the morning assembly. Returns it, or null when everything is done.
     */
    @Nullable
    static Civic choose(ServerLevel level, UUID village, long day) {
        List<Weighed> all = weigh(level, village);
        if (all.isEmpty()) {
            Ledger.forget(village, NOW);
            return null;
        }
        Weighed w = all.get(0);
        String who = chooser(village);
        begin(village, w.civic(), who, w.why(), day);
        Villages.tell(village, day, who + " set the town to work on " + w.civic().title + ": " + w.why());
        Market.assemblyNews(village, "We'll study " + w.civic().title + " next — " + w.said() + ". When it's done, "
            + w.civic().result + ".");
        VillageFolkEntity elder = Orders.elderOf(village);
        if (elder != null) {
            FolkTalk.speak(elder, FolkTalk.pick(level.getRandom(), "Next we study " + w.civic().title + ": " + w.said() + ".",
                w.civic().title + " is next for this town — " + w.said() + ".", "I've set us to " + w.civic().title + ". " + capital(w.said()) + "."));
        }
        return w.civic();
    }

    private static void begin(UUID village, Civic c, String who, String why, long day) {
        Ledger.note(village, NOW, c.name());
        Ledger.note(village, BY, who);
        Ledger.note(village, WHY, why);
        Ledger.note(village, SINCE, Long.toString(day));
    }

    // ------------------------------------------------------------------ by command (ops and tests)

    /**
     * Set the town to study this civic now (/village research pick): only one that is open (its
     * branch's tier before it done). The points in hand go toward it. Returns what happened.
     */
    public static String pick(ServerLevel level, UUID village, Civic c, long day) {
        if (has(village, c)) return c.title + " is done already.";
        if (!open(village, c)) return "Not yet: " + c.title + " needs " + c.before().title + " first.";
        begin(village, c, "By order", "set by command", day);
        Villages.tell(village, day, "the town turned its study to " + c.title + ", by order");
        return "The town now studies " + c.title + " (" + points(village) + " of " + c.cost() + " points).";
    }

    /**
     * Done at once (/village research grant), as if studied: only one that is open. What it does to
     * the folk is put on them at once. Returns what happened.
     */
    public static String grant(ServerLevel level, UUID village, Civic c, long day) {
        if (has(village, c)) return c.title + " is done already.";
        if (!open(village, c)) return "Not yet: " + c.title + " needs " + c.before().title + " first.";
        complete(level, village, c, day);
        return c.title + " is done: " + c.result + ".";
    }

    // ------------------------------------------------------------------ telling

    /** The research line for the village board, in "What we're working towards" (VillageBoards.compose). */
    public static List<String> board(@Nullable UUID village) {
        List<String> out = new ArrayList<>();
        if (village == null) return out;
        Map<Civic, Long> done = done(village);
        Civic now = current(village);
        String doneWords = doneWords(done);
        if (done.size() >= ALL) {
            out.add("FG|Research: all twenty done" + lastDone(done) + ".");
        } else if (now == null) {
            out.add("FM|Research: nothing chosen yet; " + chooser(village) + " chooses at the morning's books ("
                + plural(rate(village).points(), "point") + " a day)." + (doneWords.isEmpty() ? "" : " Done: " + doneWords + "."));
        } else {
            int days = daysLeft(village, now);
            String by = Ledger.note(village, BY), why = Ledger.note(village, WHY);
            out.add("FG|Researching: " + now.title + " " + Math.min(points(village), now.cost()) + "/" + now.cost()
                + (days > 0 ? ", about " + plural(days, "day") : ", done tomorrow")
                + (by == null || why == null ? "" : " (" + by + ": " + why + ")")
                + (doneWords.isEmpty() ? "." : " · Done: " + doneWords + "."));
        }
        return out;
    }

    /** "Common Tools, Cheap Homes" — or, past three, "7 of 20, lately Herd Books and Cheap Homes". */
    static String doneWords(Map<Civic, Long> done) {
        if (done.isEmpty()) return "";
        List<String> names = new ArrayList<>();
        for (Civic c : done.keySet()) names.add(c.title);
        if (names.size() <= 3) return String.join(", ", names);
        return done.size() + " of " + ALL + ", lately " + names.get(names.size() - 2) + " and " + names.get(names.size() - 1);
    }

    private static String lastDone(Map<Civic, Long> done) {
        Civic last = null;
        for (Civic c : done.keySet()) last = c;
        return last == null ? "" : " — the last, " + last.title + ", on day " + done.get(last);
    }

    /** What a folk adds when asked about the elder's orders (Orders.talk): " We're studying Crop Rotation: our fields need it." */
    public static String talk(VillageFolkEntity f) {
        UUID village = f.ownerId();
        Civic now = current(village);
        if (now == null) return "";
        String why = Ledger.note(village, WHY);
        int days = daysLeft(village, now);
        return " We're studying " + now.title + (why == null || why.isEmpty() || why.equals("set by command") ? "" : " — " + why)
            + (days > 0 ? ", " + plural(days, "day") + " to go." : ", nearly done.");
    }

    /** /village research: the state of the tree, a line at a time. */
    public static List<String> lines(@Nullable UUID village) {
        List<String> out = new ArrayList<>();
        if (village == null) return out;
        Rate r = rate(village);
        Civic now = current(village);
        Map<Civic, Long> done = done(village);
        StringBuilder head = new StringBuilder("RESEARCH " + Villages.name(village) + ": " + done.size() + " of " + ALL + " done; "
            + plural(points(village), "point") + " in hand, +" + r.points() + " a day (" + r.why() + ")");
        if (now != null) {
            head.append("; researching ").append(now.title).append(' ').append(Math.min(points(village), now.cost())).append('/').append(now.cost())
                .append(", about ").append(plural(daysLeft(village, now), "day")).append(" — chosen by ")
                .append(Ledger.note(village, BY)).append(": ").append(Ledger.note(village, WHY));
        } else if (done.size() < ALL) {
            head.append("; nothing chosen yet");
        }
        out.add(head.toString());
        for (Branch b : Branch.values()) {
            StringBuilder sb = new StringBuilder(b.title + ":");
            for (Civic c : Civic.values()) {
                if (c.branch != b) continue;
                sb.append(c.tier == 1 ? " " : " > ").append(c.title).append(" [").append(c.key()).append(", ");
                if (done.containsKey(c)) sb.append("done day ").append(done.get(c));
                else if (c == now) sb.append("researching ").append(Math.min(points(village), c.cost())).append('/').append(c.cost());
                else if (open(village, c)) sb.append("open, ").append(c.cost());
                else sb.append("locked, ").append(c.cost());
                sb.append("] ").append(c.effect);
            }
            out.add(sb.toString());
        }
        return out;
    }

    /**
     * The tree for the town's books' Research page (Annals.snapshot, "research"): the points and the
     * rate, what is being studied and why, every civic with its state, and the history.
     */
    public static CompoundTag report(@Nullable UUID village) {
        CompoundTag t = new CompoundTag();
        if (village == null) return t;
        Rate r = rate(village);
        Map<Civic, Long> done = done(village);
        Civic now = current(village);
        t.putInt("points", points(village));
        t.putInt("rate", r.points());
        t.putString("rate_why", r.why());
        t.putInt("done", done.size());
        t.putInt("total", ALL);
        if (now != null) {
            t.putString("now", now.key());
            t.putString("now_title", now.title);
            t.putInt("now_cost", now.cost());
            t.putInt("days_left", daysLeft(village, now));
            t.putString("by", String.valueOf(Ledger.note(village, BY)));
            t.putString("why", String.valueOf(Ledger.note(village, WHY)));
            t.putLong("since", parse(Ledger.note(village, SINCE)));
        }
        t.putString("chooser", chooser(village));
        ListTag branches = new ListTag();
        for (Branch b : Branch.values()) {
            CompoundTag bt = new CompoundTag();
            bt.putString("title", b.title);
            bt.putString("about", b.about);
            branches.add(bt);
        }
        t.put("branches", branches);
        ListTag civics = new ListTag();
        for (Civic c : Civic.values()) {
            CompoundTag ct = new CompoundTag();
            ct.putString("key", c.key());
            ct.putString("title", c.title);
            ct.putInt("branch", c.branch.ordinal());
            ct.putInt("tier", c.tier);
            ct.putInt("cost", c.cost());
            ct.putString("effect", c.effect);
            ct.putString("about", c.about);
            Civic b = c.before();
            ct.putString("needs", b == null ? "" : b.title);
            String state = done.containsKey(c) ? "done" : c == now ? "now" : open(village, c) ? "open" : "locked";
            ct.putString("state", state);
            ct.putLong("day", done.getOrDefault(c, -1L));
            civics.add(ct);
        }
        t.put("civics", civics);
        ListTag history = new ListTag();
        for (Map.Entry<Civic, Long> e : done.entrySet()) {
            history.add(StringTag.valueOf(e.getKey().title + " — done on day " + e.getValue()));
        }
        t.put("history", history);
        return t;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the village's morning for this day, now. */
    public static void morningForTests(ServerLevel level, Villages.Village v, long day) {
        morning(level, v, day);
    }

    /** Tests: the leader chooses now (announced as ever). Returns its choice. */
    @Nullable
    public static Civic chooseForTests(ServerLevel level, UUID village, long day) {
        return choose(level, village, day);
    }

    /** Tests: this many points in hand. */
    public static void pointsForTests(UUID village, int points) {
        Ledger.note(village, POINTS, Integer.toString(points));
    }

    /** Tests: the village's research forgotten, as if it had never begun. */
    public static void clearForTests(UUID village) {
        for (String k : new String[]{ DONE, NOW, POINTS, WHY, BY, SINCE, DAY, FUND, COUNTING }) Ledger.forget(village, k);
        READ.remove(village);
    }

    // ------------------------------------------------------------------ helpers

    static int parse(@Nullable String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String plural(int n, String one) {
        return n + " " + (n == 1 ? one : one + "s");
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
