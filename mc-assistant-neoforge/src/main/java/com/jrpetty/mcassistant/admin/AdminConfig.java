package com.jrpetty.mcassistant.admin;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * [admin] The admin toolkit's server config (serverconfig/mc_assistant-server.toml, per world): whether it is on at all,
 * the permission level each area wants, who may use it without being an operator, and the safety limits. Read through
 * safe getters, so a tool used before the file has loaded (a test, early start-up) falls back to the defaults.
 */
public final class AdminConfig {

    private AdminConfig() {}

    public static final ModConfigSpec SPEC;
    static final ModConfigSpec.BooleanValue ENABLED;
    static final ModConfigSpec.IntValue LEVEL, DESTRUCTIVE_LEVEL, CONFIRM_SECONDS, ENTITY_WARNING;
    static final ModConfigSpec.ConfigValue<List<? extends String>> AREA_LEVELS, ALLOWED, DISABLED_AREAS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("The admin toolkit: /village admin and its panel (an operator's tools for testing and running towns).").push("admin");
        ENABLED = b.comment("Switch the whole toolkit on or off. Off, every /village admin command is refused and the panel stays shut.")
            .define("adminTools", true);
        LEVEL = b.comment("The permission level the toolkit wants (2 is an ordinary operator). The single-player owner always has it.")
            .defineInRange("level", 2, 0, 4);
        DESTRUCTIVE_LEVEL = b.comment("The level at which destructive actions (remove or kill folk, empty the stores, restore a snapshot) run at once.",
                "Below it they print what will happen and a /village admin confirm <id> token, good for confirmSeconds.")
            .defineInRange("destructiveLevel", 3, 0, 4);
        CONFIRM_SECONDS = b.comment("How long a confirm token stays good, in seconds.").defineInRange("confirmSeconds", 30, 5, 600);
        AREA_LEVELS = b.comment("Per-area levels, as \"area=level\" (areas: town, ages, people, army, economy, build, events, world, debug).",
                "An area not listed wants the toolkit's level. Example: [\"debug=0\", \"economy=3\"]. The NeoForge permission nodes",
                "mc_assistant.admin.<area> are checked too, so a permissions mod can grant or deny an area per player.")
            .defineListAllowEmpty("areaLevels", List.of(), () -> "debug=2", o -> o instanceof String s && s.contains("="));
        ALLOWED = b.comment("Players (names or UUIDs) who may use the toolkit without being operators. Destructive actions still ask them to confirm.")
            .defineListAllowEmpty("allowedPlayers", List.of(), () -> "", o -> o instanceof String);
        DISABLED_AREAS = b.comment("Areas switched off for everybody, console included (e.g. [\"world\"] on a live server).")
            .defineListAllowEmpty("disabledAreas", List.of(), () -> "world", o -> o instanceof String);
        ENTITY_WARNING = b.comment("Warn before adding folk when the world already holds this many entities.")
            .defineInRange("entityWarning", 3000, 100, 100000);
        b.pop();
        SPEC = b.build();
    }

    /** Tests: the toolkit switched off (or back on) whatever the file says; null for the file's. */
    private static volatile Boolean enabledForTests;

    public static void enabledForTests(@javax.annotation.Nullable Boolean on) { enabledForTests = on; }

    public static boolean enabled() {
        Boolean t = enabledForTests;
        return t != null ? t : read(ENABLED, true);
    }

    public static int level() { return read(LEVEL, 2); }

    public static int destructiveLevel() { return read(DESTRUCTIVE_LEVEL, 3); }

    public static int confirmSeconds() { return read(CONFIRM_SECONDS, 30); }

    public static int entityWarning() { return read(ENTITY_WARNING, 3000); }

    /** The level this area wants: its own line in areaLevels, else the toolkit's level. */
    public static int levelFor(String area) {
        for (String line : read(AREA_LEVELS, List.<String>of())) {
            int eq = line.indexOf('=');
            if (eq <= 0 || !line.substring(0, eq).trim().equalsIgnoreCase(area)) continue;
            try {
                return Math.max(0, Math.min(4, Integer.parseInt(line.substring(eq + 1).trim())));
            } catch (NumberFormatException ignored) {
                return level();
            }
        }
        return level();
    }

    public static boolean areaOff(String area) {
        for (String s : read(DISABLED_AREAS, List.<String>of())) if (s.trim().equalsIgnoreCase(area)) return true;
        return false;
    }

    /** Is this player (by name or UUID) on the allowed list? */
    public static boolean allowed(String name, String uuid) {
        for (String s : read(ALLOWED, List.<String>of())) {
            String t = s.trim();
            if (!t.isEmpty() && (t.equalsIgnoreCase(name) || t.equalsIgnoreCase(uuid))) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T> T read(ModConfigSpec.ConfigValue<? extends T> value, T fallback) {
        try {
            T v = value.get();
            return v == null ? fallback : v;
        } catch (IllegalStateException e) {
            return fallback;
        }
    }
}
