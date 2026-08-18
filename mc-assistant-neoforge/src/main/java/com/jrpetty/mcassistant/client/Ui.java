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

    // Vanilla's own convention: a LIGHT panel with dark text. Every previous
    // attempt here kept a near-black panel and made the text brighter, and the
    // text already measured 17:1 against it — three times the contrast of a
    // vanilla button label, which reads perfectly. Contrast was never the
    // problem. A dark panel with thin light glyphs is simply harder to read
    // than the grey-and-black every Minecraft player's eye is trained on, and
    // the pack screen — the one screen that always used this palette — is the
    // one nobody has ever called unreadable.
    //
    // Every ink below was then measured against every band it can actually land
    // on — panel, header, both row stripes, the selected row and the badge bed.
    // The worst case in the whole set is 4.3:1; a vanilla button's white-on-grey
    // label, the thing on screen that reads perfectly, is 3.4:1.
    public static final int PANEL      = 0xFFC6C6C6;
    public static final int PANEL_SOFT = 0xFF8B8B8B;   // recessed track, never a text bed
    public static final int EDGE       = 0xFF373737;
    public static final int EDGE_SOFT  = 0xFF8B8B8B;
    public static final int HEADER     = 0xFFB8B8B8;
    public static final int ROW        = 0xFFBCBCBC;
    public static final int ROW_ALT    = 0xFFC6C6C6;
    public static final int ROW_PICK   = 0xFFADC98C;

    public static final int INK        = 0xFF191919;
    public static final int MUTED      = 0xFF333333;
    public static final int FAINT      = 0xFF484848;

    public static final int GOOD       = 0xFF11511A;
    public static final int WARN       = 0xFF573D06;
    public static final int BAD        = 0xFF7F2011;
    public static final int ACCENT     = 0xFF24520D;

    // Decorative only — never a text bed. The pack screen reads as "real
    // Minecraft" because of its bevel: a light edge where the light falls,
    // a shadow opposite. These give every panel the same construction.
    public static final int HI         = 0xFFE9E9E9;
    public static final int LO         = 0xFF9E9E9E;

    /** One colour per job, matching the uniforms — used by the map, the crew
     *  list and the header chips, so the same trade is the same colour
     *  everywhere it appears. */
    private static final int[] JOB = {
        0xFF6E7A8A, // unassigned  slate
        0xFFC9A227, // farmer      wheat
        0xFFA6392C, // lumberjack  flannel
        0xFF3C4A6B, // miner       denim
        0xFF4E7A3A, // rancher     green
        0xFF9AA1AC, // guard       iron
        0xFFBA6028, // smelter     ember
        0xFF2F6D9E, // fisher      blue
        0xFF6A4A7A, // storekeeper purple
        0xFFC4692A, // hauler      hi-vis
    };

    public static int job(int ordinal) {
        return JOB[Math.floorMod(ordinal, JOB.length)];
    }

    /** A small job-colour swatch: ink outline, colour core, one pixel of
     *  highlight so it reads as a chip rather than a smudge. */
    public static void chip(GuiGraphics g, int x, int y, int colour) {
        g.fill(x, y, x + 7, y + 7, 0xFF000000);
        g.fill(x + 1, y + 1, x + 6, y + 6, colour);
        g.fill(x + 1, y + 1, x + 6, y + 2, lighten(colour, 0.35F));
    }

    /** Mix a colour toward white — the glossy top edge of a bar or chip. */
    public static int lighten(int argb, float f) {
        int r = (argb >> 16) & 0xFF, gr = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (int) ((255 - r) * f); gr += (int) ((255 - gr) * f); b += (int) ((255 - b) * f);
        return 0xFF000000 | (r << 16) | (gr << 8) | b;
    }

    /** The window: solid fill, a crisp edge and a header band across the top.
     *  The outer black ring is what makes it read as a panel rather than a
     *  smudge — against a bright field or a night forest a single mid-tone
     *  border disappears into whichever it happens to be sitting on. */
    public static void panel(GuiGraphics g, int x, int y, int w, int h, int headerHeight) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);   // hard outer ring
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL);
        if (headerHeight > 0) {
            g.fill(x + 2, y + 2, x + w - 2, y + headerHeight, HEADER);
            // An engraved rule: shadow line, then light — the same trick a
            // vanilla slot uses, and what makes the band read as a header
            // rather than a stripe.
            g.fill(x + 1, y + headerHeight, x + w - 1, y + headerHeight + 1, EDGE_SOFT);
            g.fill(x + 1, y + headerHeight + 1, x + w - 1, y + headerHeight + 2, HI);
        }
        // The bevel: light top and left, shadow bottom and right.
        g.fill(x + 1, y + 1, x + w - 1, y + 2, HI);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, HI);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, LO);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, LO);
        g.renderOutline(x, y, w, h, EDGE);
    }

    /** A small-caps heading over a group of controls, with a hairline rule. */
    public static void section(GuiGraphics g, Font font, String label, int x, int y, int w) {
        String caps = label.toUpperCase();
        g.fill(x, y + 1, x + 2, y + 8, ACCENT);           // a small tick of colour
        g.drawString(font, caps, x + 5, y, FAINT, false);
        int textEnd = x + 5 + font.width(caps) + 4;
        if (textEnd < x + w) {
            g.fill(textEnd, y + 3, x + w, y + 4, EDGE_SOFT);   // engraved rule
            g.fill(textEnd, y + 4, x + w, y + 5, HI);
        }
    }

    /** A status badge — the one thing on the screen you should read first. */
    public static void pill(GuiGraphics g, Font font, String text, int x, int y, int colour) {
        int w = font.width(text) + 8;
        // A light bed, not the recessed grey: these inks are dark, and dark on
        // PANEL_SOFT measures 3:1 — dimmer than the vanilla text beside it.
        g.fill(x, y - 1, x + w, y + 10, ROW_ALT);
        g.renderOutline(x, y - 1, w, 11, EDGE_SOFT);
        g.fill(x, y - 1, x + 2, y + 10, colour);       // a coloured spine
        g.drawString(font, text, x + 5, y + 1, colour, false);
    }

    /** Progress toward the next level. Reads instantly; a number does not. */
    public static void bar(GuiGraphics g, int x, int y, int w, int h, float frac, int colour) {
        g.fill(x, y, x + w, y + h, PANEL_SOFT);        // recessed track
        g.fill(x, y, x + w, y + 1, 0xFF6F6F6F);        // inset shadow along the top
        int fill = (int) (w * Math.max(0F, Math.min(1F, frac)));
        if (fill > 0) {
            g.fill(x, y, x + fill, y + h, colour);
            g.fill(x, y, x + fill, y + 1, lighten(colour, 0.4F));   // glossy edge
        }
        g.renderOutline(x, y, w, h, EDGE);   // EDGE_SOFT is the track's own grey
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
