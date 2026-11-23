package com.jrpetty.mcassistant.pa;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * [pa33] The player's own settings for their assistant, kept on their machine (config/mc_assistant-assistant-client.toml):
 * its voice (spoken replies), listening (push-to-talk), and the HUD line. Voice recognition stays off until the
 * player turns it on and agrees to the one-time download of the offline speech model.
 */
public final class PaClientConfig {

    private PaClientConfig() {}

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue SPEAK, LISTEN, AGREED, HUD, TOURED;
    public static final ModConfigSpec.IntValue RATE, VOLUME, VOICE;
    public static final ModConfigSpec.ConfigValue<String> ENGINE, ACCENT;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("voice");
        SPEAK = b.comment("Your assistant speaks its replies out loud (your computer's own voice). Its words always show too.").define("speak", true);
        VOICE = b.comment("Which of your computer's voices it uses (0 = the first; the About tab lists them).").defineInRange("voice", 0, 0, 64);
        RATE = b.comment("How fast it speaks: -5 slow .. 5 fast.").defineInRange("rate", 0, -5, 5);
        VOLUME = b.comment("How loud: 0..100.").defineInRange("volume", 80, 0, 100);
        LISTEN = b.comment("Push-to-talk: hold the talk key and speak. Off until you turn it on in the About tab.").define("listen", false);
        AGREED = b.comment("You agreed to download the offline speech model (about 40 MB, once) into this config folder.").define("modelAgreed", false);
        ENGINE = b.comment("Speech recognition: auto (Windows' own if present, else the offline model), windows, or vosk.").define("engine", "auto");
        ACCENT = b.comment("[pa33b] Which English it listens for and speaks: auto (the game's language, then your computer's), uk, or us.")
            .define("accent", "auto");
        b.pop();
        b.push("display");
        HUD = b.comment("A small line on screen saying what your assistant is doing.").define("hud", true);
        TOURED = b.comment("You have seen the assistant's tour.").define("toured", false);
        b.pop();
        SPEC = b.build();
    }

    public static <T> T get(ModConfigSpec.ConfigValue<T> v, T fallback) {
        try {
            return SPEC.isLoaded() ? v.get() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    public static <T> void set(ModConfigSpec.ConfigValue<T> v, T value) {
        try {
            if (SPEC.isLoaded()) { v.set(value); v.save(); }
        } catch (Exception ignored) {
            // not loaded yet: kept for this session only
        }
    }
}
