package com.jrpetty.mcassistant.pa;

/** [pa33c-2b] Screen layout sums for the assistant's on-screen pieces (pure, so they test on their own). */
public final class PaLayout {

    private PaLayout() {}

    /**
     * Where a bubble goes: centred above the head, or beside it when the assistant is close (under four blocks), and
     * always wholly inside the screen with a small margin. Pure, so it is tested on its own. Returns x, y, width, height.
     */
    public static int[] bubble(float hx, float hy, int bw, int bh, double dist, int w, int h) {
        int m = 4;
        bw = Math.min(bw, w - 2 * m);
        bh = Math.min(bh, h - 2 * m);
        int x, y;
        if (dist < 4.0) {                                                 // close: beside its head, level with it
            x = Math.round(hx) + 18;
            if (x + bw > w - m) x = Math.round(hx) - 18 - bw;            // no room on the right: the left
            y = Math.round(hy) - bh / 2;
        } else {
            x = Math.round(hx) - bw / 2;
            y = Math.round(hy) - bh - 6;
        }
        x = Math.max(m, Math.min(w - m - bw, x));
        y = Math.max(m, Math.min(h - m - bh, y));
        return new int[]{x, y, bw, bh};
    }

    // ------------------------------------------------------------------ the panel's boxes, for the layout test

    /** The panel's tabs, as their labels read (Jobs may carry a count). */
    public static final String[] TABS = {"Talk", "Orders", "Jobs (99)", "Pack", "Progress", "Memory", "Look", "Settings"};   // [pa35] Progress, Memory
    /** [pa35] The Progress tab: its track of ten levels, each column wide enough for an item icon and its level number. */
    public static int trackColumn(int innerW) { return Math.max(26, innerW / 10); }
    /** [pa35] A tab's padding beside its label (9: eight tabs fit the smallest panel). */
    public static final int TAB_PAD = 9;
    /** [pa35] Auto-help, each kind its own switch (Settings > Behaviour): key, label, what it does. */
    public static final String[][] AUTO_HELP = {
        {"fight", "Fight what sneaks up on you", "A mob creeping up on you (behind you first), it takes on, noticed further off as it levels"},
        {"light", "Torch dark ground at night", "Dark ground round you at night, and torches in its pack: it lights it before things spawn"},
        {"pickup", "Pick up drops lying about", "Loot left lying about (not what you threw down) goes in its pack before it vanishes"},
        {"carry", "Carry what you can't", "Your pack's full and things are on the floor round you: it carries them for you"},
        {"tool", "Spare tool before yours breaks", "Your tool nearly worn through and it has the same kind in better nick: it hands it over (or warns you)"},
        {"cook", "Cook its raw food", "Raw meat or fish in its pack and a furnace at home: it cooks it"},
        {"food", "Bring you food when you're out", "You're hungry with nothing to eat and it has food: it brings some (your favourite, if it knows it)"},
        {"stash", "Put its things away when full", "A full pack and a chest nearby: it empties it"},
        {"fuel", "Keep the furnaces going", "A furnace with work in it gone cold: it fuels it"},
        {"farm", "Harvest ripe crops", "Four or more ripe crops near home: it picks and replants them"},
        {"sort", "Sort the chests at home", "Now and then, the chests at home tidied"},
    };

    /** [pa35] The Memory tab's buttons and foot. */
    public static final String[] MEMORY_BUTTONS = {"Keep", "Forget everything...", "Yes, forget it all", "Keep it", "Save", "Cancel"};
    public static final String[] SECTIONS = {"Personality", "Voice", "Behaviour", "Look", "Keys", "Advanced"};
    public static final String[] PACK_BUTTONS = {"Equip best", "Give me everything", "Dump junk", "Sort", "Keep: click items", "Open full pack"};
    public static final String[] ADVANCED_BUTTONS = {"Use the bed I look at", "Clear its bed", "Set home here", "Replay the tour", "Reset settings...",
        "Really reset", "Dismiss...", "Yes, dismiss", "Keep them"};

    /** The panel's width and height in GUI pixels for a GUI-scaled screen and the panel size setting (s, m, l). */
    public static int panelW(int guiW, String size) {
        return Math.min(guiW - 8, "s".equals(size) ? 380 : "l".equals(size) ? 520 : 420);
    }

    public static int panelH(int guiH, String size) {
        return Math.min(guiH - 8, "s".equals(size) ? 236 : "l".equals(size) ? 330 : 262);
    }

    /** Settings: the section list's width, and the section's own width beside it. */
    public static int sectionListW(int panelW) {
        return Math.max(70, Math.min(92, panelW / 5));
    }

    public static int settingsW(int panelW) {
        return Math.min(320, panelW - 30 - sectionListW(panelW));
    }

    /** Two buttons to a row in a column this wide. */
    public static int halfButton(int colW) {
        return (colW - 4) / 2;
    }

    /** The Pack tab's quick buttons: beside the grids when there's room (width > 0), else three to a row under them (negative). */
    public static int packButton(int panelW) {
        int iw = panelW - 22, beside = Math.min(118, iw - (84 + 9 * 18 + 8));
        return beside >= 84 ? beside : -((iw - 8) / 3);
    }

    /** One tab's width (its label and padding), and a row of tabs. */
    public static int tabsW(String[] labels) {
        int w = 0;
        for (String l : labels) w += textW(l) + TAB_PAD + 2;
        return w;
    }

    /**
     * How wide a line of the game's own font is, in GUI pixels (its glyph advances: most letters 6, narrow ones less).
     * Close to Font.width for plain English; used where the font itself isn't to hand (tests).
     */
    public static int textW(String s) {
        int w = 0;
        for (char c : s.toCharArray()) {
            w += switch (c) {
                case ' ', 't', 'I', '[', ']', '(', ')', '{', '}', '"', '*' -> 4;
                case '!', '\'', ',', '.', ':', ';', '|', 'i' -> 2;
                case 'l', '`' -> 3;
                case 'f', 'k', '<', '>' -> 5;
                case '@', '~' -> 7;
                default -> 6;
            };
        }
        return w;
    }
}
