package com.jrpetty.mcassistant.pa;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * [pa33] The Player AI Assistant's server settings (serverconfig/mc_assistant-assistant-server.toml, per world):
 * how many each player may have, how long a fallen one takes to come back, the summon button's cooldown, the
 * griefing safety, the server's tick budget for them, and the optional remote-language hook (off, and never needed).
 */
public final class PaConfig {

    private PaConfig() {}

    public static final ModConfigSpec SPEC;
    static final ModConfigSpec.IntValue PER_PLAYER, RESPAWN_SECONDS, SUMMON_COOLDOWN, BUDGET_MICROS, ROAM_MAX, SPEND_MAX;
    static final ModConfigSpec.ConfigValue<String> MAX_LANGUAGE;            // [pa33b]
    static final ModConfigSpec.BooleanValue ROAST_OTHERS;
    static final ModConfigSpec.BooleanValue CHUNKS_ON, CHUNKS_OFFLINE;       // [pa33g]
    static final ModConfigSpec.IntValue CHUNK_RADIUS, CHUNK_CAP;
    static final ModConfigSpec.IntValue START_HEARTS, MAX_HEARTS;             // [pa33c-2b]
    static final ModConfigSpec.BooleanValue KEEP_PACK;
    static final ModConfigSpec.DoubleValue XP_MULT;                           // [pa33d]
    static final ModConfigSpec.IntValue MEM_CAP;                              // [pa35]
    static final ModConfigSpec.BooleanValue LADDER_ON;                        // [pa35] the mining ladder
    static final ModConfigSpec.ConfigValue<String> LADDER;
    static final ModConfigSpec.BooleanValue FREE_FIRST, SAFETY, TOWN_WORK, LLM_ON;
    static final ModConfigSpec.ConfigValue<String> LLM_URL, LLM_KEY, LLM_MODEL;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("The Player AI Assistant: a personal companion that belongs to one player.").push("assistant");
        PER_PLAYER = b.comment("How many Player AI Assistants each player may have at once.").defineInRange("perPlayer", 1, 0, 8);
        FREE_FIRST = b.comment("The first assistant can be had for free from the U page, the welcome card or the assistant panel.",
                "Off, a player crafts an Assistant Whistle or an Assistant Spawner instead.").define("freeFirst", true);
        RESPAWN_SECONDS = b.comment("How long a fallen assistant takes to come back (at its spawner, else beside its player).")
            .defineInRange("respawnSeconds", 60, 5, 3600);
        SUMMON_COOLDOWN = b.comment("Seconds between uses of the summon button (calling it to you from afar).")
            .defineInRange("summonCooldownSeconds", 20, 0, 3600);
        SAFETY = b.comment("Griefing safety: an assistant never breaks or places blocks on a town's ground, or in another player's land.")
            .define("griefSafety", true);
        TOWN_WORK = b.comment("With griefing safety on: may an assistant still farm, dig and build on a town's ground its player lives in?")
            .define("workInOwnTown", false);
        ROAM_MAX = b.comment("The furthest (blocks) a player may let an assistant roam from them or its home on its own.")
            .defineInRange("roamMax", 64, 8, 256);
        SPEND_MAX = b.comment("The most coins a player may let an assistant spend for them at once at a market.")
            .defineInRange("spendMax", 500, 0, 100000);
        BUDGET_MICROS = b.comment("The assistant's own thinking (talk, auto-help, progress) per server tick, in microseconds, for all of them together.")
            .defineInRange("tickBudgetMicros", 400, 50, 20000);
        MAX_LANGUAGE = b.comment("[pa33b] The strongest language an assistant may use back when its player is rude to it (others see its bubbles):",
            "clean (never swears), cheeky (bloody, damn, arse, git), or unfiltered (swears). A player's own Language setting can't go past this.")
            .define("maxLanguage", "cheeky");
        // [pa35] new names, so a config written with the old 3/10 hearts doesn't keep them: ten at level 1 like a player, twenty (two rows) at 10
        START_HEARTS = b.comment("Hearts an assistant has at level 1, like a player's ten (it gains more as it levels up, to heartsAtLevel10).")
            .defineInRange("heartsAtLevel1", 10, 1, 50);
        MAX_HEARTS = b.comment("Hearts an assistant has at level 10 (twenty: two rows).").defineInRange("heartsAtLevel10", 20, 1, 50);
        KEEP_PACK = b.comment("[pa33c-2b] Does a fallen assistant keep its pack (true), or drop it where it fell like a player (false)?")
            .define("keepPackOnDeath", true);
        XP_MULT = b.comment("[pa33d] How fast assistants level up: their experience is multiplied by this (1 = the intended grind, many game days to level 10).",
            "Operators can change it in game from the assistant's panel (Settings, Advanced).").defineInRange("xpMultiplier", 1.0, 0.0, 100.0);
        MEM_CAP = b.comment("[pa35] How many things an assistant may remember for its player (likes, people, rules, reminders). Places are kept apart (32).",
            "Operators can change it in game from the assistant's panel (Settings, Advanced).").defineInRange("memoryCap", 100, 10, 1000);
        LADDER_ON = b.comment("[pa35] The mining ladder: an assistant may break an ore only from its level (coal 2, copper 3, iron 4, gold/redstone/lapis 5,",
            "diamond 6, emerald/obsidian 7, ancient debris 8). Operators can change it in game (Settings, Advanced).").define("miningLadder", true);
        LADDER = b.comment("[pa35] Rungs moved by operators, e.g. \"iron=3,diamond=5\" (keys: coal copper quartz iron gold redstone lapis diamond emerald obsidian debris special).")
            .define("miningLadderLevels", "");
        ROAST_OTHERS = b.comment("[pa33b] May an assistant roast another player back when that player roasts it or its owner first? (Never unprompted.)")
            .define("allowRoastOthers", true);
        CHUNKS_ON = b.comment("[pa33g] An assistant told to stay, on guard, at work or walking over to its player keeps the chunks round it loaded",
            "(a forced, ticking ticket), so it truly stays and keeps working while its player is far away. Operators can change these from the panel.")
            .define("keepChunksLoaded", true);
        CHUNK_RADIUS = b.comment("[pa33g] How many chunks round it: 0 just its own, 1 a 3x3 square, 2 a 5x5.").defineInRange("chunkRadius", 1, 0, 3);
        CHUNK_CAP = b.comment("[pa33g] The most chunks one player's assistants may keep loaded between them.").defineInRange("chunkCapPerPlayer", 25, 1, 200);
        CHUNKS_OFFLINE = b.comment("[pa33g] Keep them loaded (and the assistant working where it was left) while its player is offline.",
            "Off, it goes with its player when they leave, as always.").define("keepChunksWhileOffline", false);
        b.pop();
        b.comment("Optional free conversation through a remote language model. OFF by default and never needed: every order,",
            "question and reply works offline without it. When on, only sentences the assistant could not read are sent, with the",
            "assistant's name and personality; the reply is shown as its words and is never run as an order.").push("remoteChat");
        LLM_ON = b.define("enabled", false);
        LLM_URL = b.comment("An HTTPS endpoint that takes a chat request (the operator's choice of service).").define("endpoint", "");
        LLM_KEY = b.comment("The operator's API key for that endpoint. Kept on the server; never sent to players.").define("apiKey", "");
        LLM_MODEL = b.define("model", "");
        b.pop();
        SPEC = b.build();
    }

    /** Tests: the per-player limit set whatever the file says (null for the file's). */
    private static volatile Integer perPlayerForTests;

    public static void perPlayerForTests(Integer n) { perPlayerForTests = n; }

    public static int perPlayer() {
        Integer t = perPlayerForTests;
        return t != null ? t : read(PER_PLAYER, 1);
    }

    private static volatile Integer respawnForTests;

    public static void respawnSecondsForTests(Integer s) { respawnForTests = s; }

    public static int respawnSeconds() {
        Integer t = respawnForTests;
        return t != null ? t : read(RESPAWN_SECONDS, 60);
    }

    public static boolean freeFirst() { return read(FREE_FIRST, true); }

    public static int summonCooldownSeconds() { return read(SUMMON_COOLDOWN, 20); }

    public static boolean safety() { return read(SAFETY, true); }

    public static boolean workInOwnTown() { return read(TOWN_WORK, false); }

    public static int roamMax() { return read(ROAM_MAX, 64); }

    public static int spendMax() { return read(SPEND_MAX, 500); }

    /** [pa33b] The language cap: 0 clean, 1 cheeky, 2 unfiltered. */
    public static int maxLanguage() { return PaTone.cap(read(MAX_LANGUAGE, "cheeky")); }

    public static int startHearts() { return read(START_HEARTS, 10); }

    public static int maxHearts() { return read(MAX_HEARTS, 20); }

    public static boolean keepPack() { return read(KEEP_PACK, true); }

    /** [pa33d] The server's experience multiplier (operators set it from the panel). */
    public static double xpMultiplier() {
        Double t = xpForTests;
        return t != null ? t : read(XP_MULT, 1.0);
    }

    private static volatile Double xpForTests;

    public static void xpMultiplierForTests(Double m) { xpForTests = m; }

    private static volatile Integer memCapForTests;

    /** [pa35] How many things an assistant may remember for its player. */
    public static int memoryCap() {
        Integer t = memCapForTests;
        return t != null ? t : read(MEM_CAP, 100);
    }

    /** [pa35] An operator's change from the panel (or a test's, with no config loaded). */
    public static void setMemoryCap(int n) {
        int v = Math.max(10, Math.min(1000, n));
        try {
            MEM_CAP.set(v);
            MEM_CAP.save();
            memCapForTests = null;
        } catch (Exception e) {
            memCapForTests = v;
        }
    }

    public static void memoryCapForTests(Integer n) { memCapForTests = n; }

    private static volatile Boolean ladderForTests;
    private static volatile String ladderLevelsForTests;

    /** [pa35] Is the mining ladder on (operators may turn it off)? */
    public static boolean ladderOn() {
        Boolean t = ladderForTests;
        return t != null ? t : read(LADDER_ON, true);
    }

    /** [pa35] The operators' rung overrides. */
    public static java.util.Map<String, Integer> ladderLevels() {
        String t = ladderLevelsForTests;
        return PaMineLadder.parse(t != null ? t : read(LADDER, ""));
    }

    /** [pa35] An operator's change from the panel: the ladder on or off, and a rung moved (null leaves it). */
    public static void setLadder(@javax.annotation.Nullable Boolean on, @javax.annotation.Nullable String key, int level) {
        java.util.Map<String, Integer> m = ladderLevels();
        if (key != null) {
            PaMineLadder.Rung rung = null;
            for (PaMineLadder.Rung r : PaMineLadder.RUNGS) if (r.key().equals(key)) rung = r;
            int shipped = rung != null ? rung.level() : -1;
            for (PaGearLadder.Rung g : PaGearLadder.RUNGS) if (g.key().equals(key)) shipped = g.level();
            if (shipped > 0) { if (level == shipped) m.remove(key); else m.put(key, Math.max(1, Math.min(PaLevels.MAX, level))); }
        }
        try {
            if (on != null) { LADDER_ON.set(on); LADDER_ON.save(); }
            LADDER.set(PaMineLadder.format(m));
            LADDER.save();
        } catch (Exception e) {
            if (on != null) ladderForTests = on;
            ladderLevelsForTests = PaMineLadder.format(m);
        }
    }

    /** Tests: the ladder as shipped (null), or on/off with these overrides. */
    public static void ladderForTests(Boolean on, String levels) { ladderForTests = on; ladderLevelsForTests = levels; }

    /** [pa33d] An operator's change from the panel: kept in the server's config file. */
    public static void setXpMultiplier(double m) {
        try {
            XP_MULT.set(Math.max(0.0, Math.min(100.0, m)));
            XP_MULT.save();
        } catch (Exception e) {
            xpForTests = Math.max(0.0, Math.min(100.0, m));            // no config loaded (a test): for this run
        }
    }

    // [pa33g] chunks: read, and an operator's change from the panel (kept in the config file; for this run when none is loaded)
    private static final java.util.Map<String, Object> CHUNK_OVERRIDE = new java.util.concurrent.ConcurrentHashMap<>();

    public static boolean chunksOn() { return (Boolean) CHUNK_OVERRIDE.getOrDefault("on", read(CHUNKS_ON, true)); }

    public static boolean chunksOffline() { return (Boolean) CHUNK_OVERRIDE.getOrDefault("offline", read(CHUNKS_OFFLINE, false)); }

    public static int chunkRadius() { return (Integer) CHUNK_OVERRIDE.getOrDefault("radius", read(CHUNK_RADIUS, 1)); }

    public static int chunkCap() { return (Integer) CHUNK_OVERRIDE.getOrDefault("cap", read(CHUNK_CAP, 25)); }

    public static void setChunks(@javax.annotation.Nullable Boolean on, @javax.annotation.Nullable Integer radius, @javax.annotation.Nullable Integer cap,
                                 @javax.annotation.Nullable Boolean offline) {
        if (on != null) put(CHUNKS_ON, "on", on);
        if (radius != null) put(CHUNK_RADIUS, "radius", Math.max(0, Math.min(3, radius)));
        if (cap != null) put(CHUNK_CAP, "cap", Math.max(1, Math.min(200, cap)));
        if (offline != null) put(CHUNKS_OFFLINE, "offline", offline);
    }

    private static <T> void put(ModConfigSpec.ConfigValue<T> v, String key, T value) {
        try {
            if (!SPEC.isLoaded()) throw new IllegalStateException();
            v.set(value);
            v.save();
            CHUNK_OVERRIDE.remove(key);
        } catch (Exception e) {
            CHUNK_OVERRIDE.put(key, value);
        }
    }

    /** Tests: every chunk setting back to the file's. */
    public static void chunksForTests() { CHUNK_OVERRIDE.clear(); }

    public static boolean allowRoastOthers() { return read(ROAST_OTHERS, true); }

    public static int budgetMicros() { return read(BUDGET_MICROS, 400); }

    public static boolean remoteChat() { return read(LLM_ON, false) && !read(LLM_URL, "").isBlank() && !read(LLM_KEY, "").isBlank(); }

    private static <T> T read(ModConfigSpec.ConfigValue<T> v, T fallback) {
        try {
            return SPEC.isLoaded() ? v.get() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
