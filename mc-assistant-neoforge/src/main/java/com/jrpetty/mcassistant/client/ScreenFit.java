package com.jrpetty.mcassistant.client;

/**
 * [ui29] Layout sums with no game in them, so a unit test can lay a screen out at every GUI scale and window and check
 * that nothing falls off the screen or lands on something else (ScreenFitTest). The GUI's own size is the window over
 * the scale: 1280x720 is 640x360 at 2 and 427x240 at 3 (the game allows no 4 there); 1920x1080 is 480x270 at 4.
 */
public final class ScreenFit {

    private ScreenFit() {}

    /** The GUI sizes a player meets: 1280x720 at 2 and 3 (auto), 1920x1080 at 2, 3 and 4 (auto). */
    public static final int[][] GUI_SIZES = { { 640, 360 }, { 427, 240 }, { 960, 540 }, { 640, 360 }, { 480, 270 } };

    /** Room kept clear at the foot of the screen for the hotbar (and its hearts) under a screen that shows the world. */
    public static final int HOTBAR = 26;

    /**
     * Boxes round a ring (the emote wheel): box {@code i} at angle {@code i / n} of a turn from the top, on an ellipse at
     * most {@code ringX} by {@code ringY} and never nearer the edges than the boxes need. A box that would land on one
     * already placed is pushed out along its spoke until it is clear; every box is kept on the screen and above the
     * hotbar. Returns each box's centre {x, y} (boxes are {@code boxW[i]} wide and {@code boxH} high).
     */
    public static int[][] ring(int width, int height, int[] boxW, int boxH, int ringX, int ringY) {
        int n = boxW.length;
        int cx = width / 2, cy = height / 2;
        int rx = ringRadiusX(width, ringX), ry = ringRadiusY(height, ringY);
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            double ang = i * Math.PI * 2 / n;
            double sx = Math.sin(ang), sy = -Math.cos(ang);
            int bw = boxW[i], x = cx, y = cy;
            for (int push = 0; push <= 60; push += 2) {
                x = cx + (int) Math.round(sx * (rx + push));
                y = cy + (int) Math.round(sy * (ry + push * 0.6));
                x = Math.max(bw / 2 + 2, Math.min(width - bw / 2 - 2, x));
                y = Math.max(boxH / 2 + 2, Math.min(height - boxH / 2 - HOTBAR, y));
                boolean clear = true;
                for (int k = 0; k < i; k++) {
                    if (overlaps(out[k][0], out[k][1], boxW[k], x, y, bw, boxH)) { clear = false; break; }
                }
                if (clear) break;
            }
            out[i] = new int[] { x, y };
        }
        if (anyOverlap(out, boxW, boxH)) return columns(width, height, boxW, boxH);
        return out;
    }

    /** True when the ring could not be laid without two boxes touching (too many words for a small GUI). */
    public static boolean isColumns(int[][] at, int width) {
        return at.length > 1 && at[0][0] > width / 2;              // the ring starts straight up, the columns to the right
    }

    /**
     * Where the ring does not fit: the boxes in two columns either side of the middle, the first half down the right (as
     * the ring starts at the top and goes round to the right), the rest down the left, all above the hotbar.
     */
    public static int[][] columns(int width, int height, int[] boxW, int boxH) {
        int n = boxW.length, right = (n + 1) / 2, rowH = boxH + 3;
        int widest = 0;
        for (int w : boxW) widest = Math.max(widest, w);
        int cx = width / 2, gap = 32 + 8 + widest / 2;
        int top = Math.max(2, (height - HOTBAR - right * rowH) / 2);
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            boolean r = i < right;
            int row = r ? i : i - right;
            out[i] = new int[] { cx + (r ? gap : -gap), top + row * rowH + boxH / 2 };
        }
        return out;
    }

    private static boolean anyOverlap(int[][] at, int[] boxW, int boxH) {
        for (int i = 0; i < at.length; i++) {
            for (int k = 0; k < i; k++) if (overlaps(at[k][0], at[k][1], boxW[k], at[i][0], at[i][1], boxW[i], boxH)) return true;
        }
        return false;
    }

    /** The ring's half-width: as asked, or less in a narrow GUI. */
    public static int ringRadiusX(int width, int ringX) {
        return Math.min(ringX, width / 2 - 50);
    }

    /** The ring's half-height: as asked, or less in a short GUI, clear of the hotbar. */
    public static int ringRadiusY(int height, int ringY) {
        return Math.min(ringY, height / 2 - 34);
    }

    /** Two boxes by their centres (the same height), with a little air kept between them. */
    public static boolean overlaps(int ax, int ay, int aw, int bx, int by, int bw, int h) {
        return Math.abs(ax - bx) * 2 < aw + bw + 4 && Math.abs(ay - by) < h + 3;
    }

    /** A panel's side: what it wants, or the screen less a margin when the screen is smaller. */
    public static int side(int want, int screen, int margin) {
        return Math.min(want, screen - margin);
    }
}
