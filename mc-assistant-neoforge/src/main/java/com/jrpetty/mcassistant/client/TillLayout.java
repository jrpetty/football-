package com.jrpetty.mcassistant.client;

/**
 * [till] Where the checkout's parts go for a screen of this size: plain arithmetic, no game classes, so the unit tests lay
 * it out at every GUI size a player meets (ScreenFitTest). The window, the header, the list of wares on the left, the
 * basket on the right, the Pay button at the basket's foot, and the status line along the bottom.
 */
public final class TillLayout {

    private TillLayout() {}

    public static final int MAX_W = 470, MAX_H = 280, MARGIN = 6;
    public static final int HEADER = 30, TABS = 15, FOOTER = 14, ROW = 24, PAY_H = 20, BTN = 16;

    /** A box: x, y, width, height. */
    public record Box(int x, int y, int w, int h) {
        public int right() { return x + w; }
        public int bottom() { return y + h; }
        public boolean inside(Box o) { return x >= o.x && y >= o.y && right() <= o.right() && bottom() <= o.bottom(); }
    }

    /** The whole layout for a screen {@code sw} by {@code sh}; {@code tabs}: the Buy / Sell tabs are shown. */
    public record Layout(Box panel, Box header, Box tabs, Box list, Box basket, Box pay, Box footer) {
        /** How many whole rows of wares the list shows at once. */
        public int rows() {
            return Math.max(1, list.h() / ROW);
        }
    }

    public static Layout of(int sw, int sh, boolean tabs) {
        int w = Math.min(MAX_W, sw - 2 * MARGIN), h = Math.min(MAX_H, sh - 2 * MARGIN);
        int x = (sw - w) / 2, y = (sh - h) / 2;
        Box panel = new Box(x, y, w, h);
        Box header = new Box(x, y, w, HEADER);
        int top = y + HEADER + 2;
        Box tabBox = new Box(x + 6, top, w - 12, tabs ? TABS : 0);
        if (tabs) top += TABS + 2;
        int bottom = y + h - FOOTER - 2;
        int basketW = Math.max(128, Math.min(176, w * 37 / 100));
        Box basket = new Box(x + w - 6 - basketW, top, basketW, bottom - top);
        Box list = new Box(x + 6, top, basket.x() - 4 - (x + 6), bottom - top);
        Box pay = new Box(basket.x() + 4, basket.bottom() - PAY_H - 14, basket.w() - 8, PAY_H);
        Box footer = new Box(x + 6, y + h - FOOTER, w - 12, FOOTER - 2);
        return new Layout(panel, header, tabBox, list, basket, pay, footer);
    }

    /** The row's − and + buttons and the quantity between: their x, for a row ending at {@code right}. */
    public static int[] stepper(int right) {
        int plus = right - 4 - BTN;
        int qty = plus - 22;
        int minus = qty - BTN;
        return new int[]{ minus, qty, plus };
    }
}
