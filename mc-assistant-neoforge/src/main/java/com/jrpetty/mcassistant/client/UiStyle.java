package com.jrpetty.mcassistant.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * The mod's design tokens and common widgets: one palette, one type scale, one spacing grid, and the
 * pieces every screen is built from (panel, header band, recessed well, tile, tab, button, bar,
 * scroll bar, tooltip). Vanilla-friendly: a light bevelled panel with dark text, as {@link Ui} argues,
 * with a green accent for what is selected.
 *
 * <p>The API is stable: screens on other branches build on it. Add to it; never rename or remove.
 */
public final class UiStyle {

    private UiStyle() {}

    // ------------------------------------------------------------------ spacing (GUI pixels, a 4px grid)

    /** The grid's steps: 4, 8, 12, 16. Every gap and padding is one of these. */
    public static final int S1 = 4, S2 = 8, S3 = 12, S4 = 16;
    /** One line of body text, and the font's glyph height inside it. */
    public static final int LINE = 10, GLYPH = 9;
    /** A list row or a button: room for a 16px item icon with a pixel above and below. */
    public static final int ROW = 18;
    /** An item icon. */
    public static final int ICON = 16;
    /** A tab, a little taller than a row so it reads as navigation. */
    public static final int TAB = 22;

    // ------------------------------------------------------------------ palette

    /** Behind the panel: the world, dimmed, so the panel is the one focal point. */
    public static final int BACKDROP = 0x90101014;
    /** The panel and its header band (a step darker, so the band reads as a header, not a stripe). */
    public static final int PANEL = 0xFFC6C6C6, PANEL_HEADER = 0xFFB6B6B6;
    /** A recessed well (a list, a transcript): darker than the panel, an inset shadow along its top and left. */
    public static final int WELL = 0xFFA3A3A3, WELL_SHADOW = 0xFF6F6F6F, WELL_LIGHT = 0xFFD8D8D8;
    /** Raised surfaces: a tile at rest, under the mouse, chosen, and switched off. */
    public static final int TILE = 0xFFD4D4D4, TILE_HOVER = 0xFFE6E6E6, TILE_SELECTED = 0xFFCFE0BC, TILE_DISABLED = 0xFFB7B7B7;
    /** Edges: the hard outer line, the soft engraved one, and the bevel's light and shadow. */
    public static final int EDGE = 0xFF373737, EDGE_SOFT = 0xFF8B8B8B, HI = 0xFFF2F2F2, LO = 0xFF8E8E8E;
    /** The accent: what is selected or primary. Deep green on light grey (6:1), the bright one for fills only. */
    public static final int ACCENT = 0xFF2C6410, ACCENT_FILL = 0xFF5E9E34;

    /** Text: primary, secondary, tertiary, and switched off. All read at 4.5:1 or better on PANEL and TILE. */
    public static final int TEXT = 0xFF191919, TEXT_MUTED = 0xFF3C3C3C, TEXT_FAINT = 0xFF555555, TEXT_DISABLED = 0xFF6E6E6E;
    /** Text on a dark bed (a tooltip, the backdrop). */
    public static final int TEXT_ON_DARK = 0xFFF0F0F0;

    /** Status inks, for text and small marks: good, careful, bad, and plain information. */
    public static final int SUCCESS = 0xFF15591E, WARNING = 0xFF6E4A00, DANGER = 0xFF8E2416, INFO = 0xFF1D4A86;
    /** Status fills, for bars and badges (never a text colour). */
    public static final int SUCCESS_FILL = 0xFF4E9A3A, WARNING_FILL = 0xFFD39B2A, DANGER_FILL = 0xFFC2452F, INFO_FILL = 0xFF4A82C8;

    /** Speech: what the other one says, and what you say. */
    public static final int BUBBLE_THEM = 0xFFF6F3EC, BUBBLE_YOU = 0xFFD5E6C2;

    // ------------------------------------------------------------------ type scale

    /** A title: bold, primary ink. The screen's one focal line. */
    public static void title(GuiGraphics g, Font font, String text, int x, int y) {
        g.drawString(font, Component.literal(text).withStyle(Style.EMPTY.withBold(true)), x, y, TEXT, false);
    }

    /** How wide a title is (bold glyphs are a pixel wider each). */
    public static int titleWidth(Font font, String text) {
        return font.width(Component.literal(text).withStyle(Style.EMPTY.withBold(true)));
    }

    /** A section heading: small capitals in the tertiary ink with an engraved rule to {@code x + w}. */
    public static void heading(GuiGraphics g, Font font, String text, int x, int y, int w) {
        String caps = clip(font, text.toUpperCase(java.util.Locale.ROOT), w);
        g.drawString(font, caps, x, y, TEXT_FAINT, false);
        int rule = x + font.width(caps) + S1;
        if (rule < x + w) {
            g.fill(rule, y + 4, x + w, y + 5, EDGE_SOFT);
            g.fill(rule, y + 5, x + w, y + 6, HI);
        }
    }

    /** Body text, clipped with an ellipsis to {@code max} pixels. Returns true when it had to clip. */
    public static boolean text(GuiGraphics g, Font font, String text, int x, int y, int max, int colour) {
        String shown = clip(font, text, max);
        g.drawString(font, shown, x, y, colour, false);
        return !shown.equals(text);
    }

    /** Text cut to fit {@code max} pixels, with an ellipsis when cut (cached: TextCache). */
    public static String clip(Font font, String text, int max) {
        return Ui.clip(font, text == null ? "" : text, Math.max(0, max));
    }

    // ------------------------------------------------------------------ surfaces

    /** A widget's state, for its fill and its ink. */
    public enum State {
        NORMAL, HOVER, SELECTED, DISABLED;

        public static State of(boolean enabled, boolean selected, boolean hover) {
            return !enabled ? DISABLED : selected ? SELECTED : hover ? HOVER : NORMAL;
        }

        public int fill() {
            return switch (this) {
                case HOVER -> TILE_HOVER;
                case SELECTED -> TILE_SELECTED;
                case DISABLED -> TILE_DISABLED;
                default -> TILE;
            };
        }

        public int ink() {
            return this == DISABLED ? TEXT_DISABLED : TEXT;
        }
    }

    /** The window: a hard black ring (it reads on any world behind it), the panel, and a bevel. */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        g.fill(x, y, x + w, y + h, PANEL);
        bevel(g, x, y, w, h, HI, LO);
    }

    /** A soft drop shadow under a panel: two translucent steps, down and to the right. */
    public static void shadow(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x + 2, y + h + 1, x + w + 3, y + h + 3, 0x40000000);
        g.fill(x + w + 1, y + 2, x + w + 3, y + h + 1, 0x40000000);
    }

    /** A header band across the top of a panel, {@code h} tall, closed by an engraved rule. */
    public static void headerBand(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x + 1, y + 1, x + w - 1, y + h, PANEL_HEADER);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, HI);
        g.fill(x + 1, y + h, x + w - 1, y + h + 1, EDGE_SOFT);
        g.fill(x + 1, y + h + 1, x + w - 1, y + h + 2, HI);
    }

    /** A recessed area: darker, shadowed along the top and left, lit along the bottom and right. */
    public static void well(GuiGraphics g, int x, int y, int w, int h) {
        well(g, x, y, w, h, WELL);
    }

    /** A recessed area in a fill of your own. */
    public static void well(GuiGraphics g, int x, int y, int w, int h, int fill) {
        g.fill(x, y, x + w, y + h, fill);
        bevel(g, x, y, w, h, WELL_SHADOW, WELL_LIGHT);
    }

    /** A raised tile (a list row, a button's face) in its state: the bevel flattens when it is pressed in (selected) or off. */
    public static void tile(GuiGraphics g, int x, int y, int w, int h, State s) {
        g.fill(x, y, x + w, y + h, EDGE_SOFT);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, s.fill());
        if (s == State.SELECTED) {
            g.fill(x + 1, y + 1, x + 3, y + h - 1, ACCENT_FILL);          // the accent spine: this one
        } else if (s != State.DISABLED) {
            g.fill(x + 1, y + 1, x + w - 1, y + 2, HI);
            g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, LO);
        }
        if (s == State.HOVER) g.renderOutline(x, y, w, h, EDGE);
    }

    /** A vertical tab (a column of them down the left of a panel): the chosen one joins the page, an accent bar at its edge. */
    public static void sideTab(GuiGraphics g, int x, int y, int w, int h, State s) {
        if (s == State.SELECTED) {
            g.fill(x, y, x + w, y + h, PANEL);
            g.fill(x, y, x + 2, y + h, ACCENT_FILL);
            g.fill(x, y, x + w, y + 1, EDGE_SOFT);
            g.fill(x, y + h - 1, x + w, y + h, HI);
        } else {
            g.fill(x, y, x + w, y + h, s == State.HOVER ? TILE_HOVER : PANEL_HEADER);
            g.fill(x, y + h - 1, x + w, y + h, EDGE_SOFT);
        }
    }

    /** A horizontal tab (a row across the top of a page): the chosen one lifts, underlined in the accent. */
    public static void topTab(GuiGraphics g, int x, int y, int w, int h, State s) {
        g.fill(x, y, x + w, y + h, s == State.SELECTED ? PANEL : s == State.HOVER ? TILE_HOVER : PANEL_HEADER);
        g.fill(x, y, x + w, y + 1, s == State.SELECTED ? HI : EDGE_SOFT);
        if (s == State.SELECTED) g.fill(x, y + h - 2, x + w, y + h, ACCENT_FILL);
    }

    /** A button: a tile with its label centred and clipped. Returns true when the label had to clip (tooltip it). */
    public static boolean button(GuiGraphics g, Font font, String label, int x, int y, int w, int h, State s) {
        tile(g, x, y, w, h, s);
        String shown = clip(font, label, w - S2);
        g.drawString(font, shown, x + (w - font.width(shown)) / 2, y + (h - GLYPH) / 2 + 1, s.ink(), false);
        return !shown.equals(label);
    }

    /** A thin progress bar: a recessed track, the fill with a glossy top line. */
    public static void bar(GuiGraphics g, int x, int y, int w, int h, float frac, int fill) {
        g.fill(x, y, x + w, y + h, EDGE_SOFT);
        g.fill(x, y, x + w, y + 1, WELL_SHADOW);
        int n = Math.round((w) * Math.max(0F, Math.min(1F, frac)));
        if (n > 0) {
            g.fill(x, y, x + n, y + h, fill);
            if (h > 2) g.fill(x, y, x + n, y + 1, Ui.lighten(fill, 0.4F));
        }
    }

    /** A thin vertical scroll bar at {@code x}: the track, and a thumb sized to what shows. Nothing when it all fits. */
    public static void scrollBar(GuiGraphics g, int x, int y, int h, int offset, int max, int visible) {
        if (max <= 0 || h < 8) return;
        g.fill(x, y, x + 3, y + h, 0x30000000);
        int thumb = Math.max(8, h * visible / Math.max(1, visible + max));
        int ty = y + (h - thumb) * Math.max(0, Math.min(max, offset)) / max;
        g.fill(x, ty, x + 3, ty + thumb, EDGE);
        g.fill(x, ty, x + 1, ty + thumb, LO);
    }

    /** Draw a 1px bevel round a box: {@code light} on the top and left, {@code dark} on the bottom and right. */
    public static void bevel(GuiGraphics g, int x, int y, int w, int h, int light, int dark) {
        g.fill(x, y, x + w, y + 1, light);
        g.fill(x, y, x + 1, y + h, light);
        g.fill(x, y + h - 1, x + w, y + h, dark);
        g.fill(x + w - 1, y, x + w, y + h, dark);
    }

    /** A vanilla tooltip, the text wrapped to {@code maxWidth} (cached: TextCache). */
    public static void tooltip(GuiGraphics g, Font font, String text, int mouseX, int mouseY, int maxWidth) {
        if (text == null || text.isBlank()) return;
        List<FormattedCharSequence> lines = TextCache.splitPlain(font, text, Math.max(60, maxWidth));
        g.renderTooltip(font, lines, mouseX, mouseY);
    }

    /** A small key hint ("1", "Esc"): a light inset cap with the key in faint ink. Returns its width. */
    public static int keyCap(GuiGraphics g, Font font, String key, int x, int y) {
        int w = font.width(key) + 4;
        g.fill(x, y, x + w, y + 10, 0x26000000);
        g.fill(x, y + 9, x + w, y + 10, 0x40000000);
        g.drawString(font, key, x + 2, y + 1, TEXT_FAINT, false);
        return w;
    }

    // ------------------------------------------------------------------ motion and sound

    /** How far through a {@code durationMs} animation begun at {@code startMs} (Util.getMillis), 0 to 1. */
    public static float progress(long startMs, int durationMs) {
        if (durationMs <= 0) return 1F;
        return Math.max(0F, Math.min(1F, (Util.getMillis() - startMs) / (float) durationMs));
    }

    /** Ease out (cubic): quick at first, settling gently. */
    public static float easeOut(float t) {
        float u = 1F - Math.max(0F, Math.min(1F, t));
        return 1F - u * u * u;
    }

    /** A colour with its alpha scaled by {@code a} (0 to 1): for fading a fill in. */
    public static int alpha(int argb, float a) {
        int al = Math.round(((argb >>> 24) & 0xFF) * Math.max(0F, Math.min(1F, a)));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    /** The vanilla button click, for every custom control's feedback. */
    public static void click() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    // ------------------------------------------------------------------ a scroll position

    /** A list's scroll offset in pixels, kept within what the content allows. */
    public static final class Scroll {
        private int offset, max;

        /** The content's height and the room it has: sets the limit and keeps the offset inside it. */
        public void fit(int content, int room) {
            max = Math.max(0, content - room);
            offset = Math.max(0, Math.min(max, offset));
        }

        /** Move by {@code wheel} notches (positive is up, as the mouse wheel reports), {@code step} pixels a notch. */
        public void wheel(double wheel, int step) {
            offset = Math.max(0, Math.min(max, offset - (int) Math.signum(wheel) * step));
        }

        /** Bring the band {@code top}..{@code top + h} (content pixels) into a view {@code room} tall. */
        public void reveal(int top, int h, int room) {
            if (top < offset) offset = top;
            else if (top + h > offset + room) offset = top + h - room;
            offset = Math.max(0, Math.min(max, offset));
        }

        public void reset() { offset = 0; }

        public void toEnd() { offset = max; }

        public int offset() { return offset; }

        public int max() { return max; }
    }

    // ------------------------------------------------------------------ [folk-ui] dashboard pieces (the board, the profile)

    /** The numbers 0 to 999 as text, made once: a reading drawn every frame allocates nothing. */
    private static final String[] NUMBERS = new String[1000];

    /** {@code n} as text, from the made-once table for 0 to 999. */
    public static String number(int n) {
        if (n < 0 || n >= NUMBERS.length) return Integer.toString(n);
        String s = NUMBERS[n];
        if (s == null) NUMBERS[n] = s = Integer.toString(n);
        return s;
    }

    /** A flat information card: a light face with a soft edge, a coloured spine down its left when {@code spine} is not 0. */
    public static void card(GuiGraphics g, int x, int y, int w, int h, int spine) {
        g.fill(x, y, x + w, y + h, EDGE_SOFT);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, TILE);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, HI);
        if (spine != 0) g.fill(x + 1, y + 1, x + 3, y + h - 1, spine);
    }

    /** The fill for a 0..100 reading where more is better: green from {@code good}, amber from {@code fair}, else red. */
    public static int levelFill(int value, int good, int fair) {
        return value >= good ? SUCCESS_FILL : value >= fair ? WARNING_FILL : DANGER_FILL;
    }

    /** The ink for the same reading (for its number). */
    public static int levelInk(int value, int good, int fair) {
        return value >= good ? SUCCESS : value >= fair ? WARNING : DANGER;
    }

    /** A trend mark: ▲ up, ▼ down, ▸ flat, coloured by whether the move is good ({@code upIsGood}). Returns its width. */
    public static int trend(GuiGraphics g, Font font, int delta, boolean upIsGood, int x, int y) {
        String mark = delta > 0 ? "\u25B2" : delta < 0 ? "\u25BC" : "\u25B8";
        int ink = delta == 0 ? TEXT_FAINT : (delta > 0) == upIsGood ? SUCCESS : DANGER;
        g.drawString(font, mark, x, y, ink, false);
        return font.width(mark);
    }

    /**
     * A sparkline of the last {@code n} values (fewer if there are fewer) in a box: a 1px line joined column by column,
     * the last point marked. Draws nothing for fewer than two values. No allocation: it walks the array.
     */
    public static void sparkline(GuiGraphics g, int x, int y, int w, int h, int[] values, int n, int colour) {
        int len = Math.min(n, values.length);
        if (len < 2 || w < 4 || h < 3) return;
        int from = values.length - len;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = from; i < values.length; i++) { lo = Math.min(lo, values[i]); hi = Math.max(hi, values[i]); }
        if (hi == lo) { hi++; lo--; }
        int prevY = -1;
        for (int c = 0; c < w; c++) {
            int i = from + (int) ((long) c * (len - 1) / Math.max(1, w - 1));
            int py = y + h - 1 - (int) ((long) (values[i] - lo) * (h - 1) / (hi - lo));
            if (prevY < 0) prevY = py;
            g.fill(x + c, Math.min(prevY, py), x + c + 1, Math.max(prevY, py) + 1, colour);
            prevY = py;
        }
        g.fill(x + w - 2, prevY - 1, x + w + 1, prevY + 2, colour);
    }

    /** Small bars of the last {@code n} values, one a column group, from a zero baseline. Draws nothing for no values. */
    public static void miniBars(GuiGraphics g, int x, int y, int w, int h, int[] values, int n, int colour) {
        int len = Math.min(n, values.length);
        if (len < 1 || w < 2 || h < 2) return;
        int from = values.length - len, hi = 1;
        for (int i = from; i < values.length; i++) hi = Math.max(hi, values[i]);
        int bw = Math.max(1, w / len);
        for (int i = 0; i < len; i++) {
            int v = Math.max(0, values[from + i]);
            int bh = Math.max(v > 0 ? 1 : 0, (int) ((long) v * h / hi));
            int bx = x + i * w / len;
            g.fill(bx, y + h - bh, bx + Math.max(1, bw - 1), y + h, colour);
        }
    }

    /** A labelled meter: the label on the left, a bar, the value on the right; coloured by how well it stands. */
    public static void meter(GuiGraphics g, Font font, String label, int value, int x, int y, int w, int labelW) {
        g.drawString(font, clip(font, label, labelW - 2), x, y, TEXT_MUTED, false);
        String num = number(value);
        int numW = font.width("100");
        int bx = x + labelW, bw = Math.max(8, w - labelW - numW - S1);
        bar(g, bx, y + 2, bw, 5, value / 100F, levelFill(value, 60, 35));
        g.drawString(font, num, x + w - font.width(num), y, levelInk(value, 60, 35), false);
    }

    /** A small badge: a coloured bed with light text, for a status word. Returns its width. */
    public static int badge(GuiGraphics g, Font font, String text, int x, int y, int fill) {
        int w = font.width(text) + 6;
        g.fill(x, y, x + w, y + 11, fill);
        g.fill(x, y, x + w, y + 1, Ui.lighten(fill, 0.35F));
        g.drawString(font, text, x + 3, y + 2, TEXT_ON_DARK, true);
        return w;
    }

    /** An empty state: a title and a helpful line, centred in the box. */
    public static void empty(GuiGraphics g, Font font, String title, String hint, int x, int y, int w, int h) {
        int cy = y + Math.max(0, (h - 22) / 2);
        String t = clip(font, title, w - S2), d = clip(font, hint, w - S2);
        g.drawString(font, t, x + (w - font.width(t)) / 2, cy, TEXT_MUTED, false);
        g.drawString(font, d, x + (w - font.width(d)) / 2, cy + 12, TEXT_FAINT, false);
    }

    /** A sortable column heading: the label, the arrow when it is the sort, underlined under the mouse. */
    public static void sortHeading(GuiGraphics g, Font font, String label, int x, int y, int w, boolean sorted, boolean down, boolean hover) {
        String shown = clip(font, label + (sorted ? (down ? " \u25BC" : " \u25B2") : ""), w - 2);
        g.drawString(font, shown, x, y, sorted ? ACCENT : TEXT_FAINT, false);
        if (hover) g.fill(x, y + GLYPH, x + font.width(shown), y + GLYPH + 1, sorted ? ACCENT : TEXT_FAINT);
    }
}
