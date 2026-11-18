package com.jrpetty.mcassistant;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * [ui] The player's own settings for its favourites (config/mc_assistant-ui-client.toml): a small toast when a folk it
 * follows reaches a milestone (on: it is small and brief), and a small tracker in a corner of the screen for one pinned
 * favourite (off until the player turns it on and pins somebody, from the favourites screen).
 */
public final class UiConfig {

    private UiConfig() {}

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue TOASTS;
    public static final ModConfigSpec.BooleanValue TRACKER;
    public static final ModConfigSpec.ConfigValue<String> PINNED;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("favourites");
        TOASTS = b.comment("A small toast when a folk you follow reaches a milestone (a new trade, a rise, married, a child, ill, died).")
            .define("toasts", true);
        TRACKER = b.comment("A small tracker in the top-right corner for your pinned favourite (pin one on the favourites screen).")
            .define("tracker", false);
        PINNED = b.comment("The pinned favourite's id (set from the favourites screen).")
            .define("pinned", "");
        b.pop();
        SPEC = b.build();
    }

    /** Safe before the file is read: the defaults. */
    public static boolean toasts() {
        try { return TOASTS.get(); } catch (RuntimeException e) { return true; }
    }

    public static boolean tracker() {
        try { return TRACKER.get(); } catch (RuntimeException e) { return false; }
    }

    public static String pinned() {
        try { return PINNED.get(); } catch (RuntimeException e) { return ""; }
    }

    public static void setTracker(boolean on) {
        try { TRACKER.set(on); SPEC.save(); } catch (RuntimeException ignored) { }
    }

    public static void setPinned(String id) {
        try { PINNED.set(id == null ? "" : id); SPEC.save(); } catch (RuntimeException ignored) { }
    }
}
