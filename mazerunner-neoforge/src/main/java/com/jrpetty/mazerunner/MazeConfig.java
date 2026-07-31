package com.jrpetty.mazerunner;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side tuning, written to {@code serverconfig/mazerunner-server.toml} in
 * the world folder. Everything here is a runtime knob — the maze layout itself
 * comes from the bundled dataset and is not configurable.
 *
 * <p>Griever difficulty in particular is guesswork until it is played, so it is
 * all exposed: turn them off entirely, thin them out, or make them slower and
 * weaker if a fresh runner cannot survive the night.
 */
public final class MazeConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue GRIEVERS_ENABLED;
    public static final ModConfigSpec.IntValue GRIEVER_BASE_CAP;
    public static final ModConfigSpec.IntValue GRIEVER_MAX_CAP;
    public static final ModConfigSpec.DoubleValue GRIEVER_HEALTH;
    public static final ModConfigSpec.DoubleValue GRIEVER_SPEED;
    public static final ModConfigSpec.DoubleValue GRIEVER_DAMAGE;
    public static final ModConfigSpec.IntValue DEATH_PENALTY_SECONDS;
    public static final ModConfigSpec.BooleanValue SHOW_BRIEFING;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment("The Maze's nocturnal hunters.").push("grievers");
        GRIEVERS_ENABLED = BUILDER
            .comment("Whether Grievers spawn in the Maze at night at all.")
            .define("enabled", true);
        GRIEVER_BASE_CAP = BUILDER
            .comment("Grievers hunting each runner during the first week.")
            .defineInRange("baseCapPerPlayer", 2, 0, 16);
        GRIEVER_MAX_CAP = BUILDER
            .comment("Ceiling on that count however many weeks pass (it grows by one a week).")
            .defineInRange("maxCapPerPlayer", 7, 0, 32);
        GRIEVER_HEALTH = BUILDER
            .comment("Griever max health. Vanilla spider is 16.")
            .defineInRange("health", 60.0, 4.0, 400.0);
        GRIEVER_SPEED = BUILDER
            .comment("Griever movement speed. Vanilla spider is 0.3; below ~0.25 a runner can outpace one.")
            .defineInRange("speed", 0.33, 0.05, 1.0);
        GRIEVER_DAMAGE = BUILDER
            .comment("Damage per sting, before armour.")
            .defineInRange("attackDamage", 7.0, 0.0, 50.0);
        BUILDER.pop();

        BUILDER.comment("Escape runs and scoring.").push("run");
        DEATH_PENALTY_SECONDS = BUILDER
            .comment("Seconds added to your final time for each death. 0 disables the penalty.")
            .defineInRange("deathPenaltySeconds", 30, 0, 600);
        SHOW_BRIEFING = BUILDER
            .comment("Show newcomers the rules of the Glade the first time they join.")
            .define("showBriefing", true);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private MazeConfig() {}

    // Config is only read at runtime, long after loading — but a missing value
    // must never take the server down, so every read falls back to the default.

    public static boolean grieversEnabled() {
        return get(GRIEVERS_ENABLED, true);
    }

    public static int grieverBaseCap() {
        return get(GRIEVER_BASE_CAP, 2);
    }

    public static int grieverMaxCap() {
        return get(GRIEVER_MAX_CAP, 7);
    }

    public static double grieverHealth() {
        return get(GRIEVER_HEALTH, 60.0);
    }

    public static double grieverSpeed() {
        return get(GRIEVER_SPEED, 0.33);
    }

    public static double grieverDamage() {
        return get(GRIEVER_DAMAGE, 7.0);
    }

    /** Death penalty in milliseconds, as the scorer wants it. */
    public static long deathPenaltyMillis() {
        return get(DEATH_PENALTY_SECONDS, 30) * 1000L;
    }

    public static boolean showBriefing() {
        return get(SHOW_BRIEFING, true);
    }

    private static <T> T get(ModConfigSpec.ConfigValue<T> value, T fallback) {
        try {
            T v = value.get();
            return v == null ? fallback : v;
        } catch (Throwable t) {
            return fallback; // config not loaded yet, or malformed
        }
    }
}
