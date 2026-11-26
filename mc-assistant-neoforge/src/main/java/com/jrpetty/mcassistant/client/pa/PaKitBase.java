package com.jrpetty.mcassistant.client.pa;

import com.jrpetty.mcassistant.client.UiStyle;
import com.jrpetty.mcassistant.pa.PaClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * [featpa] What the Blueprints, Errand and Stance screens share, in the mod's own look (UiStyle): the header band and
 * title, buttons as raised tiles (the main one in the accent) that explain themselves when they're off, a strip at the
 * foot that says what the last click did, why, and what's next, a two-click confirm for anything costly, and a hint
 * shown the first time each screen opens.
 */
abstract class PaKitBase extends PaLogiBase {

    /** Which screen this is, for its first-time hint ("bp", "er", "st"). */
    private final String hintKey;
    /** A button waiting for its second click ("" none), and when it was armed. */
    protected String armed = "";
    private long armedAt;

    protected PaKitBase(String title, String hintKey) {
        super(title);
        this.hintKey = hintKey;
    }

    @Override
    protected void frame(GuiGraphics g, String title, int mx, int my) {
        super.frame(g, title, mx, my);                                  // shadow, panel, the close cross's zone
        UiStyle.headerBand(g, left, top, w, 22);
        UiStyle.title(g, font, UiStyle.clip(font, title, w - 40), left + 8, top + 7);
        button(g, "x", left + w - 18, top + 4, 14, 14, mx, my, true, false, "Close (the ghost and any job carry on)", this::onClose);
    }

    /** A button: a raised tile; the main one in the accent; off, it says why on hover. */
    @Override
    protected void button(GuiGraphics g, String label, int x, int y, int bw, int bh, int mx, int my, boolean enabled, boolean primary, String tip, Runnable run) {
        boolean hover = in(mx, my, x, y, bw, bh);
        UiStyle.State st = UiStyle.State.of(enabled, false, hover);
        if (primary && enabled) {
            g.fill(x, y, x + bw, y + bh, UiStyle.EDGE_SOFT);
            g.fill(x + 1, y + 1, x + bw - 1, y + bh - 1, hover ? UiStyle.SUCCESS_FILL : UiStyle.ACCENT_FILL);
            g.fill(x + 1, y + 1, x + bw - 1, y + 2, UiStyle.HI);
            String l = UiStyle.clip(font, label, bw - UiStyle.S2);
            g.drawString(font, l, x + (bw - font.width(l)) / 2, y + (bh - UiStyle.GLYPH) / 2 + 1, UiStyle.TEXT_ON_DARK, false);
            if (hover) g.renderOutline(x, y, bw, bh, UiStyle.EDGE);
        } else {
            if (font.width(label) <= bw - 2) {                            // a short label on a small button: whole, never clipped
                UiStyle.tile(g, x, y, bw, bh, st);
                g.drawString(font, label, x + (bw - font.width(label)) / 2, y + (bh - UiStyle.GLYPH) / 2 + 1, st.ink(), false);
            } else UiStyle.button(g, font, label, x, y, bw, bh, st);
        }
        if (enabled) zone(x, y, bw, bh, run, tip);
        if (hover && !tip.isEmpty()) tipNow = enabled ? tip : "Not now: " + tip;
    }

    /** A selectable tile (a list row, a card). */
    protected void tile(GuiGraphics g, int x, int y, int tw, int th, boolean selected, int mx, int my) {
        UiStyle.tile(g, x, y, tw, th, UiStyle.State.of(true, selected, in(mx, my, x, y, tw, th)));
    }

    /** A costly button: the first click arms it ("Sure? click again"), the second does it; it disarms after 5 s. */
    protected void confirmButton(GuiGraphics g, String key, String label, String sure, int x, int y, int bw, int bh, int mx, int my,
                                 boolean enabled, boolean primary, String tip, Runnable run) {
        boolean live = armed.equals(key) && System.currentTimeMillis() - armedAt < 5000;
        if (!live && armed.equals(key)) armed = "";
        button(g, live ? sure : label, x, y, bw, bh, mx, my, enabled, primary || live, live ? "Click again to go ahead: " + tip : tip, () -> {
            if (live) { armed = ""; run.run(); }
            else { armed = key; armedAt = System.currentTimeMillis(); PaKitClient.note("Click \"" + sure + "\" again to go ahead, or wait and it cancels itself.", "warn"); }
        });
    }

    /** Wrapped text; returns the y under it. */
    protected int para(GuiGraphics g, String text, int x, int y, int pw, int colour) {
        for (FormattedCharSequence l : font.split(Component.literal(text), pw)) {
            g.drawString(font, l, x, y, colour, false);
            y += 10;
        }
        return y + 2;
    }

    /** The strip at the foot: what the last click did, why, and what's next. */
    protected void feedback(GuiGraphics g, int x, int y, int fw, int fh) {
        String m = PaKitClient.msg;
        if (m.isEmpty()) m = idleWords();
        int ink = switch (PaKitClient.tone) { case "ok" -> UiStyle.SUCCESS; case "warn" -> UiStyle.WARNING; case "bad" -> UiStyle.DANGER; default -> UiStyle.TEXT_MUTED; };
        int fill = switch (PaKitClient.tone) { case "ok" -> UiStyle.TILE_SELECTED; case "warn" -> UiStyle.HIGHLIGHT; case "bad" -> UiStyle.DANGER_SOFT; default -> UiStyle.CARD; };
        if (PaKitClient.msg.isEmpty()) { ink = UiStyle.TEXT_MUTED; fill = UiStyle.CARD; }
        UiStyle.well(g, x, y, fw, fh, fill);
        List<FormattedCharSequence> lines = font.split(Component.literal(m), fw - 8);
        int ly = y + 4;
        for (int i = 0; i < lines.size() && ly + 9 <= y + fh; i++, ly += 10) g.drawString(font, lines.get(i), x + 4, ly, ink, false);
        if (lines.size() > (fh - 4) / 10 && in(lastMx, lastMy, x, y, fw, fh)) tipNow = m;
    }

    private int lastMx, lastMy;

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        lastMx = mx;
        lastMy = my;
        super.render(g, mx, my, pt);
    }

    /** What the strip says before anything is clicked. */
    protected abstract String idleWords();

    // ------------------------------------------------------------------ the first-time hint

    protected boolean hintSeen() {
        return ("," + PaClientConfig.get(PaClientConfig.KITHINTS, "") + ",").contains("," + hintKey + ",");
    }

    /** The hint card over the body, until "Got it". Returns true while it shows (the body under it is not drawn). */
    protected boolean hint(GuiGraphics g, int x, int y, int hw, String heading, String[] lines, int mx, int my) {
        if (hintSeen()) return false;
        int hh = 30 + lines.length * 11;
        for (String l : lines) hh += (font.split(Component.literal(l), hw - 20).size() - 1) * 10;
        UiStyle.well(g, x, y, hw, hh + 22, UiStyle.HIGHLIGHT);
        g.fill(x, y, x + 3, y + hh + 22, UiStyle.HIGHLIGHT_SPINE);
        UiStyle.title(g, font, heading, x + 8, y + 6);
        int ly = y + 20;
        for (int i = 0; i < lines.length; i++) ly = para(g, (i + 1) + ". " + lines[i], x + 8, ly, hw - 16, UiStyle.TEXT) - 1;
        button(g, "Got it", x + hw - 58, ly + 2, 50, 15, mx, my, true, true, "Don't show this again (the Guide has it all: press the Guide key and search)", () -> {
            String seen = PaClientConfig.get(PaClientConfig.KITHINTS, "");
            PaClientConfig.set(PaClientConfig.KITHINTS, seen.isEmpty() ? hintKey : seen + "," + hintKey);
        });
        return true;
    }
}
