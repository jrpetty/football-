package com.jrpetty.mcassistant;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * [wpe] The justice system's server settings (serverconfig/mc_assistant-justice-server.toml, per world): how harsh the
 * courts are, how long a jail day lasts in real time, how much of a prisoner's time counts while it is logged off, and
 * whether killing a player counts as a crime. No command touches any of it: an admin edits the file (or a server panel
 * that edits it). Read through safe getters, so a test or early start-up falls back to the defaults.
 */
public final class JusticeConfig {

    private JusticeConfig() {}

    public static final ModConfigSpec SPEC;
    static final ModConfigSpec.BooleanValue ENABLED, PLAYER_KILLS, FOLK_SERIOUS;
    static final ModConfigSpec.DoubleValue HARSHNESS, JAIL_DAY_MINUTES, OFFLINE_RATE, MAX_JAIL_MINUTES, COURT_DELAY_MINUTES;
    static final ModConfigSpec.IntValue APOLOGY_SECONDS, JAIL_POPULATION, FINE_DAYS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("The justice system: crimes by players and folk, arrest, the jail and the court (entity/Justice).").push("justice");
        ENABLED = b.comment("Switch the justice system on. Off, the watch keeps only its old warning, fine and bar ladder for players.")
            .define("justice", true);
        HARSHNESS = b.comment("How harsh the courts are: fines, jail time and the evidence the watch wants before it acts scale with it.",
                "1.0 is the usual; 0.5 lenient; 2.0 harsh.")
            .defineInRange("harshness", 1.0, 0.25, 3.0);
        JAIL_DAY_MINUTES = b.comment("How long one 'day' of a jail sentence lasts, in real minutes of play. A murder is about ten days.")
            .defineInRange("jailDayMinutes", 3.0, 0.25, 20.0);
        MAX_JAIL_MINUTES = b.comment("The longest any one sentence may keep a player in jail, in real minutes, whatever it adds up to.")
            .defineInRange("maxJailMinutes", 60.0, 1.0, 240.0);
        OFFLINE_RATE = b.comment("How much of a prisoner's sentence counts down while it is logged off: 0 freezes it (it only counts",
                "while online), 1 counts it fully. A player is never held for the whole of its time away at less than 1.")
            .defineInRange("offlineJailRate", 0.25, 0.0, 1.0);
        COURT_DELAY_MINUTES = b.comment("How long after an arrest the court sits on the case, in real minutes (the prisoner is on remand meanwhile).")
            .defineInRange("courtDelayMinutes", 3.0, 0.5, 30.0);
        APOLOGY_SECONDS = b.comment("How long a player has, after hurting a folk by accident, to say sorry before it becomes a complaint.")
            .defineInRange("apologySeconds", 90, 10, 600);
        JAIL_POPULATION = b.comment("A town builds a jailhouse once it has a sworn special constable, or reaches this many folk (from the Iron Age).")
            .defineInRange("jailPopulation", 40, 5, 500);
        FINE_DAYS = b.comment("How many in-game days a convicted player has to pay a fine before it becomes a charge of its own.")
            .defineInRange("fineDays", 2, 1, 14);
        PLAYER_KILLS = b.comment("Whether killing another player inside a town counts as murder for its court.")
            .define("killingPlayersCounts", true);
        FOLK_SERIOUS = b.comment("Whether folk now and then commit serious crimes too (a brawl that turns to assault; the most dishonest",
                "silencing a witness). Rare either way.")
            .define("folkSeriousCrime", true);
        b.pop();
        SPEC = b.build();
    }

    // ---- tests may set any of these over the file

    public static volatile Double harshnessForTests, jailDayMinutesForTests, offlineRateForTests, courtDelayForTests;
    public static volatile Boolean enabledForTests;

    public static void resetForTests() {
        harshnessForTests = null;
        jailDayMinutesForTests = null;
        offlineRateForTests = null;
        courtDelayForTests = null;
        enabledForTests = null;
    }

    public static boolean enabled() { Boolean t = enabledForTests; return t != null ? t : read(ENABLED, true); }
    public static double harshness() { Double t = harshnessForTests; return t != null ? t : read(HARSHNESS, 1.0); }
    public static double jailDayMinutes() { Double t = jailDayMinutesForTests; return t != null ? t : read(JAIL_DAY_MINUTES, 3.0); }
    public static double maxJailMinutes() { return read(MAX_JAIL_MINUTES, 60.0); }
    public static double offlineRate() { Double t = offlineRateForTests; return t != null ? t : read(OFFLINE_RATE, 0.25); }
    public static double courtDelayMinutes() { Double t = courtDelayForTests; return t != null ? t : read(COURT_DELAY_MINUTES, 3.0); }
    public static int apologySeconds() { return read(APOLOGY_SECONDS, 90); }
    public static int jailPopulation() { return read(JAIL_POPULATION, 40); }
    public static int fineDays() { return read(FINE_DAYS, 2); }
    public static boolean playerKillsCount() { return read(PLAYER_KILLS, true); }
    public static boolean folkSeriousCrime() { return read(FOLK_SERIOUS, true); }

    private static <T> T read(ModConfigSpec.ConfigValue<T> value, T fallback) {
        try {
            T v = value.get();
            return v == null ? fallback : v;
        } catch (IllegalStateException e) {
            return fallback;
        }
    }
}
