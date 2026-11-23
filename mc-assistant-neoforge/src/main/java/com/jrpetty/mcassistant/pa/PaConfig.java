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
    static final ModConfigSpec.IntValue START_HEARTS, MAX_HEARTS;             // [pa33c-2b]
    static final ModConfigSpec.BooleanValue KEEP_PACK;
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
        START_HEARTS = b.comment("[pa33c-2b] Hearts an assistant has at level 1 (it gains more as it levels up, to maxHearts at level 10).")
            .defineInRange("startHearts", 3, 1, 50);
        MAX_HEARTS = b.comment("[pa33c-2b] Hearts an assistant has at level 10.").defineInRange("maxHearts", 10, 1, 50);
        KEEP_PACK = b.comment("[pa33c-2b] Does a fallen assistant keep its pack (true), or drop it where it fell like a player (false)?")
            .define("keepPackOnDeath", true);
        ROAST_OTHERS = b.comment("[pa33b] May an assistant roast another player back when that player roasts it or its owner first? (Never unprompted.)")
            .define("allowRoastOthers", true);
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

    public static int startHearts() { return read(START_HEARTS, 3); }

    public static int maxHearts() { return read(MAX_HEARTS, 10); }

    public static boolean keepPack() { return read(KEEP_PACK, true); }

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
