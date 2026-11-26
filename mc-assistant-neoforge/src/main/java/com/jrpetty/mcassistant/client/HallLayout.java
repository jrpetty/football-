package com.jrpetty.mcassistant.client;

/**
 * [wpa] The Town Hall page's tabs, and whether a strip of tabs fits across the player's page (client/LifeScreen draws
 * them in one row, a tab its words and 14 pixels wide, 2 apart, from 8 in). Data only, no game classes, so the unit
 * tests can hold every GUI size to it (ScreenFitTest).
 */
public final class HallLayout {

    private HallLayout() {}

    /** The tabs: id and label, kept short. */
    public static final String[][] TABS = { { "join", "Join" }, { "citizen", "Citizen" }, { "duties", "Duties" }, { "money", "Money" },
        { "respect", "Respect" }, { "week", "Week" }, { "office", "Office" } };

    /** The widest a glyph of the game's font is, near enough. */
    public static final int GLYPH = 6;

    /** LifeScreen's page width on a screen this wide (scaled pixels). */
    public static int pageWidth(int screenWidth) {
        return Math.min(460, screenWidth - 16);
    }

    /** How wide a strip of these tabs is. */
    public static int stripWidth(String[][] tabs) {
        int w = 8;
        for (String[] t : tabs) w += t[1].length() * GLYPH + 14 + 2;
        return w;
    }

    /** How wide a row of buttons on its own is (each its words and 12, 3 apart). */
    public static int buttonsWidth(String... labels) {
        int w = 0;
        for (String l : labels) w += l.length() * GLYPH + 12 + 3;
        return w;
    }

    /** The room a row of buttons has on a page this wide (LifeScreen: the page less its margins and the scroll bar). */
    public static int rowRoom(int screenWidth) {
        return pageWidth(screenWidth) - 8 * 2 - 10;
    }
}
