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
