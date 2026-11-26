package com.jrpetty.mcassistant.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [featbuild] The board's Defences, Parks and Stone pages (DefencesPage, ParksPage, StonePage), and what they share: a page
 * is built as a list of rows (headings, wrapped text, bars, chips, rows with a "Show me" button), scrolled with the wheel,
 * on UiStyle's tokens. A "Show me" sets the marker (EarnClient: an arrow at the top of the screen and sparks over the
 * place), and the banner at the top of the page says what was marked, why, and what to do next; a button that cannot be
 * used is drawn disabled, and its tooltip says why. CityScreen only hands the page over.
 */
public final class WorksPages {

    private WorksPages() {}

    static final int ROW = 11, BTN_H = 11;

    /** One row of a page. */
    static final class Row {
        final char kind;                      // H heading, T text, B bar, C chips, S spacer
        final String text;
        final int colour;
        @Nullable String button;
        boolean enabled = true;
        String why = "";
        @Nullable int[] at;
        String markLabel = "";
        String markWhy = "";
        float frac;
        int fill;
        String right = "";
        List<String> chips = List.of();
        List<Integer> tints = List.of();
        int indent;
        @Nullable List<String> tip;

        Row(char kind, String text, int colour) {
            this.kind = kind;
            this.text = text;
            this.colour = colour;
        }
    }

    /** A page's rows, built by its page class. */
    static final class Rows {
        final List<Row> rows = new ArrayList<>();
        final Font font;
        final int width;

        Rows(Font font, int width) {
            this.font = font;
            this.width = width;
        }

        void heading(String s) {
            if (!rows.isEmpty()) rows.add(new Row('S', "", 0));
            rows.add(new Row('H', s, UiStyle.TEXT_FAINT));
        }

        /** Wrapped text in this ink. */
        void text(String s, int colour) {
            text(s, colour, 0);
        }

        void text(String s, int colour, int indent) {
            if (s == null || s.isEmpty()) return;
            for (FormattedCharSequence line : TextCache.split(font, s, width - 8 - indent * 10)) {
                Row r = new Row('T', "", colour);
                r.indent = indent;
                rows.add(r);
                rowsSeq.add(line);
                lineOf.put(rows.size() - 1, line);
            }
        }

        private final List<FormattedCharSequence> rowsSeq = new ArrayList<>();
        private final java.util.Map<Integer, FormattedCharSequence> lineOf = new java.util.HashMap<>();

        /** A line with a "Show me" button at its right (disabled, with why, when there is no place to mark). */
        Row showMe(String s, int colour, @Nullable int[] at, String markLabel, String markWhy, String whyNot) {
            Row r = new Row('T', s, colour);
            r.button = "Show me";
            r.at = at;
            r.enabled = at != null && at.length >= 3;
            r.why = whyNot;
            r.markLabel = markLabel;
            r.markWhy = markWhy;
            rows.add(r);
            return r;
        }

        Row bar(String label, float frac, int fill, String right) {
            Row r = new Row('B', label, UiStyle.TEXT);
            r.frac = frac;
            r.fill = fill;
            r.right = right;
            rows.add(r);
            return r;
        }

        void chips(List<String> words, List<Integer> tints) {
            Row r = new Row('C', "", UiStyle.TEXT);
            r.chips = words;
            r.tints = tints;
            rows.add(r);
        }
    }

    // ------------------------------------------------------------------ the banner: what was done, why, what next

    private static String notice = "";
    private static long noticeAt;

    static void notice(String s) {
        notice = s;
        noticeAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ the page

    @Nullable
    public static List<Component> draw(String page, GuiGraphics g, Font font, CompoundTag data, int x, int y, int w, int h, int scroll,
                                       int mx, int my, EarningsPage.Clicks clicks) {
        Rows rows = new Rows(font, w);
        String title;
        switch (page) {
            case "Defences" -> { title = DefencesPage.build(rows, data.getCompound("defences")); }
            case "Parks" -> { title = ParksPage.build(rows, data.getCompound("greens")); }
            default -> { title = StonePage.build(rows, data.getCompound("stone")); }
        }
        if (rows.rows.isEmpty()) {
            String[] e = switch (page) {
                case "Defences" -> new String[]{ "No word from the watch yet", "The Defences page fills in once the town has folk and a watch. Come back in a moment." };
                case "Parks" -> new String[]{ "No parks or gardens yet", "A park comes at twenty folk; statue gardens once the town builds in stone and has somebody to honour." };
                default -> new String[]{ "No quarry yet", "A quarry and a stone yard open once the town has ten folk and its storehouse." };
            };
            UiStyle.empty(g, font, e[0], e[1], x, y, w, h);
            return null;
        }
        int top = y;
        // The title line.
        g.drawString(font, UiStyle.clip(font, title, w - 4), x + 2, y, UiStyle.TEXT, false);
        y += ROW + 2;
        // The banner, for six seconds after an action.
        if (!notice.isEmpty() && System.currentTimeMillis() - noticeAt < 6000L) {
            List<FormattedCharSequence> lines = TextCache.split(font, notice, w - 12);
            int bh = lines.size() * 10 + 4;
            g.fill(x, y, x + w, y + bh, UiStyle.HIGHLIGHT);
            g.fill(x, y, x + 2, y + bh, UiStyle.HIGHLIGHT_SPINE);
            int ly = y + 2;
            for (FormattedCharSequence l : lines) { g.drawString(font, l, x + 6, ly, UiStyle.TEXT, false); ly += 10; }
            y += bh + 3;
        }
        int ch = h - (y - top);
        int visible = Math.max(1, ch / ROW);
        int start = Math.max(0, Math.min(scroll, Math.max(0, rows.rows.size() - visible)));
        List<Component> tip = null;
        int cy = y;
        for (int i = start; i < rows.rows.size() && cy + ROW <= y + ch; i++) {
            Row r = rows.rows.get(i);
            int lx = x + 4 + r.indent * 10;
            switch (r.kind) {
                case 'H' -> UiStyle.heading(g, font, r.text, x + 2, cy + 1, w - 6);
                case 'S' -> { }
                case 'B' -> {
                    int lw = Math.min(150, w / 3);
                    g.drawString(font, UiStyle.clip(font, r.text, lw - 4), lx, cy + 1, UiStyle.TEXT_MUTED, false);
                    int rw = font.width(r.right) + 6;
                    int bx = x + lw + 4, bw = Math.max(20, w - lw - rw - 12);
                    UiStyle.bar(g, bx, cy + 3, bw, 5, r.frac, r.fill);
                    g.drawString(font, r.right, x + w - rw, cy + 1, UiStyle.TEXT_MUTED, false);
                }
                case 'C' -> {
                    int cx = lx;
                    for (int k = 0; k < r.chips.size(); k++) {
                        cx += UiStyle.chip(g, font, r.chips.get(k), cx, cy - 1, r.tints.get(k)) + 3;
                    }
                }
                default -> {
                    int textR = x + w - 4;
                    if (r.button != null) {
                        int bw = font.width(r.button) + 10;
                        int bx = x + w - bw - 2;
                        boolean over = mx >= bx && mx < bx + bw && my >= cy && my < cy + BTN_H;
                        UiStyle.button(g, font, r.button, bx, cy, bw, BTN_H, UiStyle.State.of(r.enabled, false, over));
                        textR = bx - 4;
                        final Row row = r;
                        if (r.enabled) {
                            clicks.add(bx, cy, bx + bw, cy + BTN_H, () -> {
                                EarnClient.mark(row.at, row.markLabel);
                                UiStyle.click();
                                notice("Marked " + row.markLabel + " (" + row.at[0] + ", " + row.at[1] + ", " + row.at[2] + "): " + row.markWhy
                                    + " Follow the arrow at the top of your screen; the sparks over it show the place.");
                            });
                        }
                        if (over) tip = List.of(Component.literal(r.enabled ? "Show me: an arrow at the top of the screen and sparks over " + r.markLabel + "."
                            : "Can't show it: " + r.why));
                    }
                    FormattedCharSequence seq = rows.lineOf.get(i);
                    if (seq != null) g.drawString(font, seq, lx, cy + 1, r.colour, false);
                    else g.drawString(font, UiStyle.clip(font, r.text, textR - lx), lx, cy + 1, r.colour, false);
                    if (r.tip != null && mx >= lx && mx < textR && my >= cy && my < cy + ROW && tip == null) {
                        List<Component> t = new ArrayList<>();
                        for (String s : r.tip) t.add(Component.literal(s));
                        tip = t;
                    }
                }
            }
            cy += ROW;
        }
        if (rows.rows.size() > visible) {
            UiStyle.scrollBar(g, x + w - 2, y, ch, start * ROW, (rows.rows.size() - visible) * ROW, ch);
            if (start + visible < rows.rows.size()) {
                String more = "scroll for more";
                g.drawString(font, more, x + w - font.width(more) - 6, top, UiStyle.TEXT_FAINT, false);
            }
        }
        return tip;
    }

    // ------------------------------------------------------------------ small things the pages share

    static String num(long n) {
        return String.format(java.util.Locale.ROOT, "%,d", n);
    }

    static float frac(long a, long b) {
        return b <= 0 ? 0F : Math.max(0F, Math.min(1F, a / (float) b));
    }

    @Nullable
    static int[] at(CompoundTag c) {
        int[] a = c.getIntArray("at");
        return a.length >= 3 ? a : null;
    }
}
