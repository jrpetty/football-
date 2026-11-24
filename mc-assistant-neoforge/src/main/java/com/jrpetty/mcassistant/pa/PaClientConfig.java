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
    public static final ModConfigSpec.IntValue RATE, VOLUME, VOICE, PITCH;
    public static final ModConfigSpec.ConfigValue<String> ENGINE, ACCENT, VOICEKEY, CLOUDKEY, CLOUDURL;
    public static final ModConfigSpec.BooleanValue CLOUD;
    /** [pa33c-2b] Talk mode and voice activation; pings; the panel's look; the heart bar over its head. */
    public static final ModConfigSpec.ConfigValue<String> TALKMODE, PINGMODE, UISCALE, LASTTAB;
    public static final ModConfigSpec.IntValue VASENS;
    public static final ModConfigSpec.BooleanValue SHAREPINGS, ACTQUICK, VOICEPING, CONTRAST, SUBTITLES, HEARTBAR;
    /** [pa35] The natural voice: fetched automatically (auto) unless the player said not to (declined); the basic system voice only if picked; a woman's or a man's voice. */
    public static final ModConfigSpec.ConfigValue<String> VOICEPACK;
    public static final ModConfigSpec.BooleanValue BASICVOICE, VOICEWOMAN, VOICEREADYSAID, PROGRESSTOUR;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("voice");
        SPEAK = b.comment("Your assistant speaks its replies out loud (your computer's own voice). Its words always show too.").define("speak", com.jrpetty.mcassistant.pa.voice.PaVoiceDefaults.SPEAK);
        VOICE = b.comment("Which of your computer's voices it uses (0 = the first; the Settings tab lists them).").defineInRange("voice", 0, 0, 64);
        RATE = b.comment("How fast it speaks: -5 slow .. 5 fast.").defineInRange("rate", 0, -5, 5);
        VOLUME = b.comment("How loud: 0..100.").defineInRange("volume", 80, 0, 100);
        LISTEN = b.comment("Push-to-talk: hold the talk key and speak. Off until you turn it on in the Settings tab (Voice).").define("listen", false);
        AGREED = b.comment("You agreed to download the offline speech model (about 40 MB, once) into this config folder.").define("modelAgreed", false);
        ENGINE = b.comment("Speech recognition: auto (Windows' own if present, else the offline model), windows, or vosk.").define("engine", "auto");
        PITCH = b.comment("[pa33b] Its pitch: -5 lower .. 5 higher.").defineInRange("pitch", 0, -5, 5);
        VOICEKEY = b.comment("[pa33b] The voice picked in About (engine:name); empty: the most natural voice for the accent.").define("voiceKey", "");
        CLOUD = b.comment("[pa33b] An online voice service of your own (off; never needed). Nothing is sent without this AND a key.").define("cloudVoice", false);
        CLOUDKEY = b.comment("[pa33b] Your own API key for the online voice (kept on your computer only).").define("cloudKey", "");
        CLOUDURL = b.comment("[pa33b] The online voice service's address.").define("cloudEndpoint", "");
        ACCENT = b.comment("[pa33b] Which English it listens for and speaks: auto (the game's language, then your computer's), uk, or us.")
            .define("accent", "auto");
        TALKMODE = b.comment("[pa33c-2b] The talk key: hold (hold to talk), toggle (press to start, again to send), or voice (voice activation).")
            .define("talkMode", "hold");
        VASENS = b.comment("[pa33c-2b] Voice activation sensitivity, 0..100 (higher hears quieter speech).").defineInRange("voiceSensitivity", 50, 0, 100);
        VOICEPING = b.comment("[pa33c-2b] Speaking pings what you're looking at when you start, so \"mine that\" means that.").define("voicePings", true);
        VOICEPACK = b.comment("[pa35] The natural voice (Kokoro, about 220 MB, once, into this config folder): auto (fetched in the background the first time you",
            "get an assistant) or declined (never fetched; Settings > Voice can still fetch it). Nothing about you is sent.").define("naturalVoice", com.jrpetty.mcassistant.pa.voice.PaVoiceDefaults.NATURAL);
        BASICVOICE = b.comment("[pa35] Use your computer's basic system voice (Windows' old desktop voices, macOS say) when no natural voice is ready. Off: words only.")
            .define("systemVoiceBasic", com.jrpetty.mcassistant.pa.voice.PaVoiceDefaults.BASIC);
        VOICEWOMAN = b.comment("[pa35] A woman's natural voice (Emma, or Heart in American English) instead of a man's (George, or Michael).").define("womanVoice", false);
        VOICEREADYSAID = b.comment("[pa35] It has told you its natural voice is ready.").define("naturalVoiceAnnounced", false);
        b.pop();
        b.push("pings");
        PINGMODE = b.comment("[pa33c-2b] The middle mouse button: both (tap pings when you have an assistant; sneak for pick block), ping, or pick (pick block only).")
            .define("middleMouse", "both");
        SHAREPINGS = b.comment("[pa33c-2b] Let other players see your ping markers.").define("sharePings", false);
        ACTQUICK = b.comment("[pa33c-2b] A quick tap ping is an order (its default action). Off: a tap is just a marker until you choose or say something.")
            .define("actOnQuickPings", false);
        b.pop();
        b.push("display");
        UISCALE = b.comment("[pa33c-2b] The panel's size: s, m or l (on top of the game's GUI scale).").define("panelScale", "m");
        CONTRAST = b.comment("[pa33c-2b] High-contrast panel colours.").define("highContrast", false);
        SUBTITLES = b.comment("[pa33c-2b] Show what it says as a subtitle too (text-only voice).").define("subtitles", false);
        HEARTBAR = b.comment("[pa33c-2b] A small heart bar over its head (only you see it).").define("heartBar", false);
        LASTTAB = b.comment("[pa33c-2b] The panel tab you last had open.").define("lastTab", "talk");
        HUD = b.comment("A small line on screen saying what your assistant is doing.").define("hud", true);
        TOURED = b.comment("You have seen the assistant's tour.").define("toured", false);
        PROGRESSTOUR = b.comment("[pa35] You have seen (or dismissed) the Progress tab's three tips.").define("progressTips", false);
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
