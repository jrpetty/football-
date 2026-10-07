package com.jrpetty.mcassistant;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Every number that used to be a guess baked into the code: how hungry a
 * specialist is, how big a patch it claims, how many you may run, and how long
 * a veteran takes to make. Edit these in config/mc_assistant-common.toml and
 * restart — nobody should have to wait on a new build to rebalance a mod.
 */
public final class AssistantConfig {

    private AssistantConfig() {}

    public static final ModConfigSpec SPEC;

    // --- running costs ---
    public static final ModConfigSpec.IntValue FOOD_INTERVAL;
    public static final ModConfigSpec.IntValue CHARGE_INTERVAL;
    public static final ModConfigSpec.BooleanValue UPKEEP_ENABLED;
    public static final ModConfigSpec.BooleanValue WAGES_ENABLED;
    public static final ModConfigSpec.IntValue WAGE_INTERVAL;
    public static final ModConfigSpec.IntValue FREE_HIRES;

    // --- crew and patches ---
    public static final ModConfigSpec.IntValue MAX_CREW;
    public static final ModConfigSpec.IntValue DEFAULT_ZONE_RADIUS;
    public static final ModConfigSpec.IntValue MAX_ZONE_RADIUS;

    // --- progression ---
    public static final ModConfigSpec.IntValue XP_PER_LEVEL_FACTOR;
    public static final ModConfigSpec.BooleanValue LOYALTY_ENABLED;

    // --- behaviour ---
    public static final ModConfigSpec.BooleanValue CHUNK_LOADING;
    public static final ModConfigSpec.IntValue WORK_TICK_INTERVAL;
    public static final ModConfigSpec.BooleanValue NATURAL_VILLAGES;
    public static final ModConfigSpec.IntValue VILLAGE_SPACING;
    public static final ModConfigSpec.IntValue VILLAGE_MIN_FOLK;
    public static final ModConfigSpec.IntValue VILLAGE_MAX_FOLK;
    public static final ModConfigSpec.BooleanValue VILLAGE_BREEDING;
    public static final ModConfigSpec.BooleanValue VILLAGE_RAIDS;
    public static final ModConfigSpec.IntValue VILLAGE_GROWTH_CAP;
    public static final ModConfigSpec.IntValue VILLAGE_FOUNDING_MOST;
    public static final ModConfigSpec.IntValue VILLAGE_CHARTER_FOLK;
    public static final ModConfigSpec.BooleanValue VILLAGE_WARS;
    public static final ModConfigSpec.IntValue VILLAGE_LOADED_CHUNKS;
    public static final ModConfigSpec.BooleanValue REPLACE_VILLAGERS;
    public static final ModConfigSpec.BooleanValue PROTECT_TRADED_VILLAGERS;
    public static final ModConfigSpec.BooleanValue VILLAGE_COLONIES;
    public static final ModConfigSpec.BooleanValue VILLAGES_SHARE_GOODS;
    public static final ModConfigSpec.IntValue VILLAGE_COLONY_AT;
    public static final ModConfigSpec.IntValue VILLAGE_WORLD_CAP;
    public static final ModConfigSpec.IntValue VILLAGE_BUILD_SPEED;
    public static final ModConfigSpec.BooleanValue VILLAGE_RESHAPE_LAND;
    public static final ModConfigSpec.BooleanValue VILLAGE_PHANTOMS;
    /** [economy] How much faster crops grow on a village's tended fields (Fields). */
    public static final ModConfigSpec.DoubleValue VILLAGE_CROP_GROWTH;
    /** [disasters] Fire from the forges, floods and droughts (entity/Disasters), and how rare. */
    public static final ModConfigSpec.BooleanValue VILLAGE_DISASTERS;
    public static final ModConfigSpec.IntValue VILLAGE_SPARK_DAYS;
    public static final ModConfigSpec.IntValue VILLAGE_FLOOD_RAIN_DAYS;
    public static final ModConfigSpec.IntValue VILLAGE_DROUGHT_DAYS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Running costs. A working specialist eats and burns a core charge;",
                  "raise the intervals to make a crew cheaper, or switch upkeep off entirely.")
         .push("upkeep");
        UPKEEP_ENABLED = b.comment("Do specialists consume food and redstone at all?")
            .define("enabled", true);
        FOOD_INTERVAL = b.comment("Ticks of work per ration eaten (3000 = 2.5 minutes).")
            .defineInRange("foodIntervalTicks", 3000, 200, 200000);
        CHARGE_INTERVAL = b.comment("Ticks of work per redstone core charge (12000 = 10 minutes).")
            .defineInRange("chargeIntervalTicks", 12000, 200, 400000);
        WAGES_ENABLED = b.comment("Do specialists draw a wage in metal on top of food and redstone?",
                                  "A wage is SPENT, not carried: hand over a diamond and it is gone.")
            .define("wagesEnabled", true);
        WAGE_INTERVAL = b.comment("Ticks of work per wage (24000 = one Minecraft day).",
                                  "Iron covers one, gold two, a diamond four.")
            .defineInRange("wageIntervalTicks", 24000, 1200, 1000000);
        b.pop();

        b.comment("How many specialists you may run, and how much ground they claim.")
         .push("crew");
        MAX_CREW = b.comment("Maximum assistants per player.")
            .defineInRange("maxPerPlayer", 10, 1, 64);
        DEFAULT_ZONE_RADIUS = b.comment("Half-width of the patch a bot claims when given a job.")
            .defineInRange("defaultZoneRadius", 12, 4, 60);
        MAX_ZONE_RADIUS = b.comment("Largest patch the -/+ buttons and the wand will allow.")
            .defineInRange("maxZoneRadius", 60, 8, 64);
        FREE_HIRES = b.comment("How many assistants you may hire before they start costing",
                               "diamonds. Past this, the Nth hire wants (N - this) diamonds in hand.")
            .defineInRange("freeHires", 3, 0, 64);
        b.pop();

        b.comment("Veteran levels. Level = sqrt(lifetimeXp / factor), so a bigger",
                  "factor means slower levelling.")
         .push("progression");
        XP_PER_LEVEL_FACTOR = b.comment("Divisor in the level curve: xp needed for level L is",
                                        "factor * L squared. 25 puts level 20 at 10,000 xp and",
                                        "level 35 at 30,625 — a couple of hours of solid work for",
                                        "a real veteran rather than twenty minutes. Drop it to 10",
                                        "for the old, much faster curve.")
            .defineInRange("levelCurveFactor", 25, 1, 400);
        LOYALTY_ENABLED = b.comment("Do long-serving specialists earn small permanent bonuses?")
            .define("loyaltyEnabled", true);
        b.pop();

        b.comment("How the work brain behaves.")
         .push("behaviour");
        CHUNK_LOADING = b.comment("Keep a station's chunks loaded so it works while you're away.",
                                  "Turn off if you'd rather save the server the tickets.")
            .define("keepStationChunksLoaded", true);
        WORK_TICK_INTERVAL = b.comment("Ticks between work decisions for a stationed bot.",
                                       "Lower is more responsive and slightly more expensive.")
            .defineInRange("workTickInterval", 40, 5, 400);
        b.pop();

        b.comment("Settlements that grow on the map by themselves.")
         .push("villages");
        NATURAL_VILLAGES = b.comment(
                "Let Village Folk settlements generate in the world as chunks are explored.",
                "They keep their own chunks loaded and grow whether or not you are watching.")
            .define("naturalVillages", true);
        VILLAGE_SPACING = b.comment(
                "Blocks between candidate settlement sites. One village per grid cell,",
                "its exact spot fixed by the world seed. Bigger means rarer and further apart.")
            .defineInRange("villageSpacing", 768, 256, 8000);
        VILLAGE_MIN_FOLK = b.comment("Fewest folk a settlement the world grows on its own (or a colony) is founded with.")
            .defineInRange("villageMinFolk", 8, 1, 60);
        VILLAGE_MAX_FOLK = b.comment("Most folk a settlement the world grows on its own is founded with.")
            .defineInRange("villageMaxFolk", 12, 1, 60);
        VILLAGE_RAIDS = b.comment(
                "Raiders come at walled villages with a watch, about one night in five:",
                "the bell rings, the guards take the walls, everybody else goes indoors.")
            .define("villageRaids", true);
        VILLAGE_BREEDING = b.comment(
                "Let settlements grow their own people. Two folk who are fed and in work",
                "have a chance of raising a child, which costs them the food it takes.",
                "Without this a village can only ever shrink.")
            .define("villageBreeding", true);
        VILLAGE_GROWTH_CAP = b.comment(
                "How large a settlement may grow by raising children. The trade shares",
                "keep scaling with it: a watch at eleven, a carrier at twelve, a",
                "storekeeper at thirteen, a pen at fourteen and a boat at sixteen,",
                "and past that it is simply more of everything in the same proportion.",
                "A town spreads with the SQUARE ROOT of its population, because what it",
                "needs is area: a hundred folk stake plots up to ~250 blocks out, five",
                "hundred up to ~500. Every one of those people is a ticking entity.")
            .defineInRange("villageGrowthCap", 100, 2, 500);
        VILLAGE_FOUNDING_MOST = b.comment(
                "The most folk a player may found a village with at its board (two to five",
                "hundred). A founding party may be bigger than villageGrowthCap: the cap is on",
                "children, so a village founded over it raises none until it is smaller.")
            .defineInRange("villageFoundingMost", 500, 2, 500);
        VILLAGE_CHARTER_FOLK = b.comment(
                "How many folk a Village Charter, or a Village Folk Spawner set down away from any village,",
                "founds a village with: the founding screen at the board starts here, and the player may",
                "choose anything from two to villageFoundingMost before confirming. (Villages the world grows",
                "on its own are founded with villageMinFolk to villageMaxFolk; colonies with villageMinFolk.)")
            .defineInRange("villageCharterFolk", 70, 2, 500);
        VILLAGE_WARS = b.comment(
                "Let towns go to war with each other: a feud that boils over, a war council, scouts sent to count",
                "the enemy, the town on a war footing, war bands, raids, sieges and peace talks. Off, a feud",
                "stays a feud and nobody marches.")
            .define("villageWars", true);
        VILLAGE_LOADED_CHUNKS = b.comment(
                "How many chunks around its heart a settlement keeps ticking while",
                "nobody is there, as a radius. Six is a 13x13 square (169 chunks): the",
                "heart, the stores and every building lot. Each folk also keeps the",
                "chunks round its own plot awake, so the fields work either way; a",
                "village that generates in every direction as you explore keeps one",
                "such ring apiece for ever, and the cost is the square of the number.",
                "Raise it if your machine can pay for it.")
            .defineInRange("villageLoadedChunks", 6, 2, 24);
        // [emerald] Off by default now: the folk and the game's villagers are two peoples, kept apart (TwoPeoples).
        REPLACE_VILLAGERS = b.comment(
                "The old takeover, OFF by default. Off, the folk and the game's own",
                "villagers are two peoples kept completely apart: villagers stay",
                "villagers, their villages stay theirs (no town is founded near one,",
                "and no folk uses their beds, chests, job blocks or bells), villagers",
                "never claim a town's beds, job blocks or bell, and a town's emerald",
                "trader walks out to trade with them.",
                "On, villagers are turned into Village Folk as you meet them and work",
                "the village they lived in (its houses, beds, chests, furnaces and",
                "fields); the settlement is credited with the buildings it already has.",
                "That REMOVES TRADING with those villagers: they are gone. Wandering",
                "traders are untouched either way.")
            .define("replaceVillagers", false);
        PROTECT_TRADED_VILLAGERS = b.comment(
                "Leave alone any villager you have traded with, named or cured. Off by",
                "default: the swap is meant to be complete, and a swap that skips the",
                "villagers you have actually met looks exactly like one that does not work.",
                "Turn it on to keep a curated trading hall.")
            .define("protectTradedVillagers", false);
        VILLAGE_COLONIES = b.comment(
                "Let a grown village send settlers out to found a new village a couple of",
                "hundred blocks away, which then grows up through the ages of its own.",
                "This is how settlements spread across the map over a long game.")
            .define("villageColonies", true);
        VILLAGES_SHARE_GOODS = b.comment(
                "Let villages on good terms send each other goods and coin outright, with nobody carrying",
                "them: a hand when one is short, food for an ally, tribute to a bigger neighbour, an",
                "envoy's gift. Off (the default), every village is its own: it keeps to its own stores,",
                "its own chests and its own treasury, and never touches another's. Trade between",
                "villages still goes by caravan, on the road, paid for, under a pact.")
            .define("villagesShareGoods", false);
        VILLAGE_COLONY_AT = b.comment(
                "How many people a village (of the Stone Age or later) must have before it",
                "sends a founding party out. It sends one every two game days at most.")
            .defineInRange("villageColonyAt", 40, 10, 500);
        VILLAGE_WORLD_CAP = b.comment(
                "The most Village Folk the whole world may hold. Past it no village raises",
                "a child or sends out a founding party. Every one of them is a ticking",
                "entity: three hundred cost a server about twenty milliseconds a tick.")
            .defineInRange("villageWorldCap", 200, 20, 5000);
        VILLAGE_BUILD_SPEED = b.comment(
                "How fast village builders lay their blocks, as a percentage: 100 is the",
                "usual pace, 200 twice as fast, 50 half. (The materials still have to be",
                "gathered; this is only the laying of them.)")
            .defineInRange("villageBuildSpeed", 100, 25, 400);
        VILLAGE_RESHAPE_LAND = b.comment(
                "Let villages reshape the land round them: level the town's ground (cutting",
                "and filling up to six blocks), dig sand for glass, and cut irrigation",
                "channels to their fields. Turn it off to keep your terrain as it is;",
                "buildings still get the footings they need to stand.")
            .define("villageReshapeLand", true);
        VILLAGE_PHANTOMS = b.comment(
                "Let phantoms trouble the villages. Off by default: no phantom spawns over a",
                "village (its town's reach and twenty-four blocks round it) for a player who",
                "has not slept, and one that strays in from outside is seen off in a puff of",
                "smoke (one from a spawn egg, a spawner or a command is left alone). Either way",
                "a phantom never goes for the folk: it hunts players, as in the game.")
            .define("villagePhantoms", false);
        VILLAGE_CROP_GROWTH = b.comment(
                "How much faster crops grow on a village's tended fields (the farmland inside a",
                "farmer's plot) than in the wild: 2.0 is twice as fast, 1.0 the game's own pace.",
                "A farmer's care (its level, a watered and lit field, a composter) lifts it a",
                "tenth more again at most. Wild crops and your own farms are never touched; the",
                "crops still have to be planted, and the harvest still has to be brought in.")
            .defineInRange("villageCropGrowth", 2.0, 1.0, 8.0);
        // [disasters] Fire, flood and drought (entity/Disasters, FireSafety, Floods, Droughts).
        VILLAGE_DISASTERS = b.comment(
                "Fire, flood and drought in the villages: now and then a lit forge throws a spark into",
                "the timber beside it, a river rises over the low ground in a wet spell, and a long dry",
                "summer withers the fields that have no water. Each is answered (the bell and a bucket",
                "chain, levees, irrigation) and none is ruinous: a fire takes a building or two at most",
                "and is rebuilt from the stores, a flood is only water in empty cells and every block of",
                "it is taken up again, a drought slows the dry fields and starves nobody. Off, none of",
                "them happens (a lightning fire is still put out and rebuilt).")
            .define("villageDisasters", true);
        VILLAGE_SPARK_DAYS = b.comment(
                "About how many days pass between sparks from a forge in a careless town (timber or wool",
                "beside its lit furnaces, no water at hand, in ordinary weather). A dry spell makes them",
                "twice as likely, a drought three times; stone round the forge stops them altogether.")
            .defineInRange("villageSparkDays", 10, 2, 1000);
        VILLAGE_FLOOD_RAIN_DAYS = b.comment(
                "How many of the last seven days must have been wet, in spring or autumn, before rain",
                "on a town by a river brings the river up over its low ground. Higher is rarer;",
                "eight means never.")
            .defineInRange("villageFloodRainDays", 3, 1, 8);
        VILLAGE_DROUGHT_DAYS = b.comment(
                "How many days running without rain, in summer, make a drought: the fields with no",
                "water near them grow at a quarter of their pace until it rains.")
            .defineInRange("villageDroughtDays", 6, 2, 60);
        b.pop();

        SPEC = b.build();
    }

    // --- convenience readers, safe before the config has loaded ---

    public static int foodInterval() { return read(FOOD_INTERVAL, 3000); }
    public static int chargeInterval() { return read(CHARGE_INTERVAL, 12000); }
    public static boolean upkeepEnabled() { return read(UPKEEP_ENABLED, true); }
    public static boolean wagesEnabled() { return read(WAGES_ENABLED, true); }
    public static int wageInterval() { return read(WAGE_INTERVAL, 24000); }
    public static int freeHires() { return read(FREE_HIRES, 3); }
    public static int maxCrew() { return read(MAX_CREW, 10); }
    public static int defaultZoneRadius() { return read(DEFAULT_ZONE_RADIUS, 12); }
    public static int maxZoneRadius() { return read(MAX_ZONE_RADIUS, 60); }
    public static int levelCurveFactor() { return read(XP_PER_LEVEL_FACTOR, 25); }
    public static boolean loyaltyEnabled() { return read(LOYALTY_ENABLED, true); }
    public static boolean chunkLoading() { return read(CHUNK_LOADING, true); }
    public static int workTickInterval() { return read(WORK_TICK_INTERVAL, 40); }
    public static boolean naturalVillages() { return read(NATURAL_VILLAGES, true); }
    public static int villageSpacing() { return read(VILLAGE_SPACING, 768); }
    public static int villageMinFolk() { return read(VILLAGE_MIN_FOLK, 8); }
    public static int villageMaxFolk() { return read(VILLAGE_MAX_FOLK, 12); }
    public static boolean villageBreeding() { return read(VILLAGE_BREEDING, true); }
    public static boolean villageRaids() { return read(VILLAGE_RAIDS, true); }
    public static int villageGrowthCap() { return read(VILLAGE_GROWTH_CAP, 100); }
    public static int villageFoundingMost() { return read(VILLAGE_FOUNDING_MOST, 500); }
    public static int villageCharterFolk() { return read(VILLAGE_CHARTER_FOLK, 70); }
    public static boolean villageWars() { return read(VILLAGE_WARS, true); }
    public static int villageLoadedChunks() { return read(VILLAGE_LOADED_CHUNKS, 6); }
    public static boolean replaceVillagers() {                                            // [emerald] off: two peoples
        Boolean t = replaceForTests;
        return t != null ? t : read(REPLACE_VILLAGERS, false);
    }

    /** [emerald] Tests: the old takeover switched on (or off) for one test whatever the file says; null for the file's. */
    private static volatile Boolean replaceForTests;

    public static void replaceVillagersForTests(@javax.annotation.Nullable Boolean on) { replaceForTests = on; }
    public static boolean protectTradedVillagers() { return read(PROTECT_TRADED_VILLAGERS, false); }
    public static boolean villageColonies() { return read(VILLAGE_COLONIES, true); }
    public static boolean villagesShareGoods() { return read(VILLAGES_SHARE_GOODS, false); }
    public static int villageColonyAt() { return read(VILLAGE_COLONY_AT, 40); }
    public static int villageWorldCap() { return read(VILLAGE_WORLD_CAP, 200); }
    public static int villageBuildSpeed() { return read(VILLAGE_BUILD_SPEED, 100); }
    public static boolean villageReshapeLand() { return read(VILLAGE_RESHAPE_LAND, true); }
    public static boolean villagePhantoms() { return read(VILLAGE_PHANTOMS, false); }
    public static double villageCropGrowth() { return read(VILLAGE_CROP_GROWTH, 2.0); }
    public static boolean villageDisasters() { return read(VILLAGE_DISASTERS, true); }          // [disasters]
    public static int villageSparkDays() { return read(VILLAGE_SPARK_DAYS, 10); }
    public static int villageFloodRainDays() { return read(VILLAGE_FLOOD_RAIN_DAYS, 3); }
    public static int villageDroughtDays() { return read(VILLAGE_DROUGHT_DAYS, 6); }

    /** Config values throw if read before the file is loaded (early world gen,
     *  datagen, a dedicated server still booting) — fall back rather than crash. */
    private static <T> T read(ModConfigSpec.ConfigValue<T> value, T fallback) {
        try {
            T v = value.get();
            return v == null ? fallback : v;
        } catch (IllegalStateException e) {
            return fallback;
        }
    }
}
