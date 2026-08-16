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
        b.pop();

        b.comment("How many specialists you may run, and how much ground they claim.")
         .push("crew");
        MAX_CREW = b.comment("Maximum assistants per player.")
            .defineInRange("maxPerPlayer", 10, 1, 64);
        DEFAULT_ZONE_RADIUS = b.comment("Half-width of the patch a bot claims when given a job.")
            .defineInRange("defaultZoneRadius", 12, 4, 60);
        MAX_ZONE_RADIUS = b.comment("Largest patch the -/+ buttons and the wand will allow.")
            .defineInRange("maxZoneRadius", 60, 8, 64);
        b.pop();

        b.comment("Veteran levels. Level = sqrt(lifetimeXp / factor), so a bigger",
                  "factor means slower levelling.")
         .push("progression");
        XP_PER_LEVEL_FACTOR = b.comment("Divisor in the level curve (10 = slow, 4 = brisk).")
            .defineInRange("levelCurveFactor", 10, 1, 200);
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

        SPEC = b.build();
    }

    // --- convenience readers, safe before the config has loaded ---

    public static int foodInterval() { return read(FOOD_INTERVAL, 3000); }
    public static int chargeInterval() { return read(CHARGE_INTERVAL, 12000); }
    public static boolean upkeepEnabled() { return read(UPKEEP_ENABLED, true); }
    public static int maxCrew() { return read(MAX_CREW, 10); }
    public static int defaultZoneRadius() { return read(DEFAULT_ZONE_RADIUS, 12); }
    public static int maxZoneRadius() { return read(MAX_ZONE_RADIUS, 60); }
    public static int levelCurveFactor() { return read(XP_PER_LEVEL_FACTOR, 10); }
    public static boolean loyaltyEnabled() { return read(LOYALTY_ENABLED, true); }
    public static boolean chunkLoading() { return read(CHUNK_LOADING, true); }
    public static int workTickInterval() { return read(WORK_TICK_INTERVAL, 40); }

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
