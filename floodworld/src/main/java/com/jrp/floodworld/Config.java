package com.jrp.floodworld;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLE_MOD;
    public static final ModConfigSpec.IntValue RAIN_TICK_INTERVAL;
    public static final ModConfigSpec.IntValue RAIN_SAMPLES_PER_TICK;
    public static final ModConfigSpec.IntValue RAIN_RADIUS;
    public static final ModConfigSpec.IntValue BUILDUP_WATER;
    public static final ModConfigSpec.IntValue BUILDUP_LAND;
    public static final ModConfigSpec.IntValue MAX_FLOOD_HEIGHT;
    public static final ModConfigSpec.IntValue DOWNHILL_SEARCH_RADIUS;
    public static final ModConfigSpec.IntValue SATURATION_THRESHOLD;
    public static final ModConfigSpec.IntValue SATURATION_DECAY_TICKS;
    public static final ModConfigSpec.IntValue EVAP_TICKS;
    public static final ModConfigSpec.IntValue MAX_ACTIVE_CELLS;

    private Config() {
    }

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("FloodWorld — rain-driven flooding (server-side simulation).").push("general");
        ENABLE_MOD = b.comment("Master switch for the flood simulation.")
                .define("enableMod", true);
        b.pop();

        b.comment("Rain sampling").push("rain");
        RAIN_TICK_INTERVAL = b.comment("Run a sampling pass every N server ticks while it is raining.")
                .defineInRange("rainTickInterval", 4, 1, 1200);
        RAIN_SAMPLES_PER_TICK = b.comment("Random surface columns sampled per player, per pass.")
                .defineInRange("rainSamplesPerTick", 16, 1, 1024);
        RAIN_RADIUS = b.comment("Radius (blocks) around each player within which columns are sampled.")
                .defineInRange("rainRadius", 48, 8, 256);
        b.pop();

        b.comment("Water build-up").push("buildup");
        BUILDUP_WATER = b.comment("Fluid levels added per hit to existing water bodies (faster than land).")
                .defineInRange("buildupWater", 2, 1, 8);
        BUILDUP_LAND = b.comment("Fluid levels added per hit to land puddles (slower than water).")
                .defineInRange("buildupLand", 1, 1, 8);
        MAX_FLOOD_HEIGHT = b.comment("Maximum blocks a flood may rise above the natural surface at a column.")
                .defineInRange("maxFloodHeight", 3, 1, 64);
        DOWNHILL_SEARCH_RADIUS = b.comment("Columns searched around a dry hit for the lowest open spot to deposit a puddle.")
                .defineInRange("downhillSearchRadius", 4, 0, 12);
        b.pop();

        b.comment("Soil saturation").push("saturation");
        SATURATION_THRESHOLD = b.comment("Rain hits a dry column must absorb before surface water starts forming.")
                .defineInRange("saturationThreshold", 6, 0, 4096);
        SATURATION_DECAY_TICKS = b.comment("Ticks without a rain hit before a column's saturation counter decays by 1.")
                .defineInRange("saturationDecayTicks", 1200, 20, 1728000);
        b.pop();

        b.comment("Recession").push("recession");
        EVAP_TICKS = b.comment("Ticks per fluid level removed from flood cells while it is not raining.")
                .defineInRange("evapTicks", 30, 1, 6000);
        b.pop();

        b.comment("Limits").push("limits");
        MAX_ACTIVE_CELLS = b.comment("Hard cap on tracked flood positions per dimension; a warning is logged when hit.")
                .defineInRange("maxActiveCells", 8192, 64, 2000000);
        b.pop();

        SPEC = b.build();
    }
}
