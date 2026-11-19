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
    // [purse20] Your finances: the week's summary, bills due, and the button on the inventory screen.
    public static final ModConfigSpec.BooleanValue FINANCE_WEEKLY;
    public static final ModConfigSpec.BooleanValue FINANCE_BILLS;
    public static final ModConfigSpec.BooleanValue FINANCE_INVENTORY_BUTTON;

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
        b.push("finances");                                                                   // [purse20]
        FINANCE_WEEKLY = b.comment("A toast at the start of each week: the week's income and spending, and your net worth.")
            .define("weeklySummary", true);
        FINANCE_BILLS = b.comment("A toast when bills (rent, taxes, loan payments, fines) are due within two days or overdue.")
            .define("billAlerts", true);
        FINANCE_INVENTORY_BUTTON = b.comment("A small coin button beside the inventory screen that opens Your finances.")
            .define("inventoryButton", true);
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

    /** [purse20] */
    public static boolean financeWeekly() {
        try { return FINANCE_WEEKLY.get(); } catch (RuntimeException e) { return true; }
    }

    public static boolean financeBills() {
        try { return FINANCE_BILLS.get(); } catch (RuntimeException e) { return true; }
    }

    public static boolean financeButton() {
        try { return FINANCE_INVENTORY_BUTTON.get(); } catch (RuntimeException e) { return true; }
    }

    public static void setFinance(int which, boolean on) {
        try {
            (which == 0 ? FINANCE_WEEKLY : which == 1 ? FINANCE_BILLS : FINANCE_INVENTORY_BUTTON).set(on);
            SPEC.save();
        } catch (RuntimeException ignored) { }
    }
}
