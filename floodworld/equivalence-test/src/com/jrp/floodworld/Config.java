package com.jrp.floodworld;
import net.neoforged.neoforge.common.ModConfigSpec;
public final class Config {
    public static final ModConfigSpec.BooleanValue ENABLE_MOD = new ModConfigSpec.BooleanValue(true);
    public static final ModConfigSpec.IntValue RAIN_TICK_INTERVAL = new ModConfigSpec.IntValue(4);
    public static final ModConfigSpec.IntValue RAIN_SAMPLES_PER_TICK = new ModConfigSpec.IntValue(16);
    public static final ModConfigSpec.IntValue RAIN_RADIUS = new ModConfigSpec.IntValue(48);
    public static final ModConfigSpec.IntValue BUILDUP_WATER = new ModConfigSpec.IntValue(2);
    public static final ModConfigSpec.IntValue BUILDUP_LAND = new ModConfigSpec.IntValue(1);
    public static final ModConfigSpec.IntValue MAX_FLOOD_HEIGHT = new ModConfigSpec.IntValue(3);
    public static final ModConfigSpec.IntValue DOWNHILL_SEARCH_RADIUS = new ModConfigSpec.IntValue(4);
    public static final ModConfigSpec.IntValue SATURATION_THRESHOLD = new ModConfigSpec.IntValue(6);
    public static final ModConfigSpec.IntValue SATURATION_DECAY_TICKS = new ModConfigSpec.IntValue(20);
    public static final ModConfigSpec.IntValue EVAP_TICKS = new ModConfigSpec.IntValue(30);
    public static final ModConfigSpec.IntValue MAX_ACTIVE_CELLS = new ModConfigSpec.IntValue(8192);
}
