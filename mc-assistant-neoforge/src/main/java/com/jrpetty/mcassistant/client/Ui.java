package com.jrpetty.mcassistant.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * One look for every assistant screen: the same palette, the same panel, the
 * same section headings, so the crew list, the orders sheet and the pack all
 * read as parts of one thing rather than three different mods.
 */
public final class Ui {

    private Ui() {}

    // A dark, slightly green slate — it sits over the world without glaring,
    // and leaves the status colours room to actually mean something.
    public static final int PANEL      = 0xF014170F;
    public static final int PANEL_SOFT = 0xFF1B2015;
    public static final int EDGE       = 0xFF3C4630;
    public static final int EDGE_SOFT  = 0xFF2A3320;
    public static final int HEADER     = 0xFF222A19;
    public static final int ROW        = 0xFF1A1F13;
    public static final int ROW_ALT    = 0xFF1D2316;
    public static final int ROW_PICK   = 0xFF2C3A22;

    public static final int INK        = 0xFFEDF0E3;
    public static final int MUTED      = 0xFF95A07E;
    public static final int FAINT      = 0xFF66714F;

    public static final int GOOD       = 0xFF86D08F;
    public static final int WARN       = 0xFFE2AE45;
    public static final int BAD        = 0xFFE0725A;
    public static final int ACCENT     = 0xFF9BD46A;

    /** The window: soft fill, crisp edge, and a header band across the top. */
    public static void panel(GuiGraphics g, int x, int y, int w, int h, int headerHeight) {
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL);
        if (headerHeight > 0) {
            g.fill(x + 1, y + 1, x + w - 1, y + headerHeight, HEADER);
            g.fill(x + 1, y + headerHeight, x + w - 1, y + headerHeight + 1, EDGE);
        }
        g.renderOutline(x, y, w, h, EDGE);
    }

    /** A small-caps heading over a group of controls, with a hairline rule. */
    public static void section(GuiGraphics g, Font font, String label, int x, int y, int w) {
        String caps = label.toUpperCase();
        g.drawString(font, caps, x, y, FAINT, false);
        int textEnd = x + font.width(caps) + 4;
        if (textEnd < x + w) {
            g.fill(textEnd, y + 3, x + w, y + 4, EDGE_SOFT);
        }
    }

    /** A status badge — the one thing on the screen you should read first. */
    public static void pill(GuiGraphics g, Font font, String text, int x, int y, int colour) {
        int w = font.width(text) + 8;
        g.fill(x, y - 1, x + w, y + 10, PANEL_SOFT);
        g.fill(x, y - 1, x + 2, y + 10, colour);       // a coloured spine
        g.drawString(font, text, x + 5, y + 1, colour, false);
    }

    /** Progress toward the next level. Reads instantly; a number does not. */
    public static void bar(GuiGraphics g, int x, int y, int w, int h, float frac, int colour) {
        g.fill(x, y, x + w, y + h, PANEL_SOFT);
        int fill = (int) (w * Math.max(0F, Math.min(1F, frac)));
        if (fill > 0) g.fill(x, y, x + fill, y + h, colour);
        g.renderOutline(x, y, w, h, EDGE_SOFT);
    }

    /** Right-aligned helper, for putting a value opposite its label. */
    public static void right(GuiGraphics g, Font font, String text, int rightEdge, int y, int colour) {
        g.drawString(font, text, rightEdge - font.width(text), y, colour, false);
    }

    /** Never let a readout run past the panel and onto the world behind it. */
    public static String clip(Font font, String text, int max) {
        if (font.width(text) <= max) return text;
        String cut = text;
        while (cut.length() > 1 && font.width(cut + "…") > max) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    /** Colour for a status line: green working, red blocked, amber setting up. */
    public static int statusColour(String status) {
        if (status.startsWith("Working")) return GOOD;
        if (status.startsWith("Needs") || status.startsWith("Out of")) return BAD;
        return WARN;
    }

    /** Fraction of the way to the next veteran level (level = sqrt(xp/factor)).
     *  Reads the local common config: on a server tuned differently from the
     *  client the bar is a shade off, which is a cosmetic price worth paying
     *  over synching a number for one progress bar. */
    public static float levelProgress(int level, int lifetimeXp) {
        int f = Math.max(1, com.jrpetty.mcassistant.AssistantConfig.levelCurveFactor());
        int here = f * level * level;
        int next = f * (level + 1) * (level + 1);
        if (next <= here) return 1F;
        return (float) (lifetimeXp - here) / (float) (next - here);
    }

    public static Component text(String s) {
        return Component.literal(s);
    }
}
