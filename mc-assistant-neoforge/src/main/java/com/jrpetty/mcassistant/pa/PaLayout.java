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
}
