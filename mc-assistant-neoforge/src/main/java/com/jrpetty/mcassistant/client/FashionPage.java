package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.item.Garment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [fashion] The Fashion page of the town's books (CityScreen), as the server's Fashion.report has it. On the left the
 * season's look in a swatch of its colour, who set it and why, how many wear it, the days of its going round (and the
 * last season's look going out), and what the town wears colour by colour. On the right who the town looks to for
 * its fashions, the tailor's book (who ordered what, and what each waits on: a dye, the next age), the price of the
 * season's thing, the shows, the old things passed on, and everybody's style, the scroll wheel for the rest.
 */
public final class FashionPage {

    private FashionPage() {}

    private static final int TRACK = 0xFF8B8B8B;

    /** The page. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        int colour = m.getInt("colour");
        if (colour < 0 || !m.contains("colour")) {
            Ui.section(g, font, "Fashion", x, y, w);
            small(g, font, "No fashion yet: the town sets one each season, once it has three folk.", x + 4, y + 14, Ui.MUTED);
            small(g, font, "Its most admired set it: the wealthiest, the leader, the best liked, the young, a famous visitor.", x + 4, y + 24, Ui.FAINT);
            return null;
        }
        List<Component> tip = null;
        int lw = w * 9 / 20, rx = x + lw + 8, rw = w - lw - 8;
        int maxL = (int) ((lw - 6) / 0.75F), maxR = (int) ((rw - 6) / 0.75F);
        // The season's look.
        Ui.section(g, font, "This " + m.getString("season"), x, y, lw);
        int sy = y + 12;
        int rgb = 0xFF000000 | dye(colour);
        g.fill(x + 2, sy, x + 30, sy + 28, rgb);
        g.renderOutline(x + 2, sy, 28, 28, Ui.EDGE);
        String head = capital(m.getString("colourWord")) + (m.getString("kind").isEmpty() ? "" : " " + m.getString("kind"));
        g.drawString(font, Ui.clip(font, head, lw - 40), x + 36, sy + 1, Ui.INK, false);
        String setter = m.getString("setter");
        small(g, font, Ui.clip(font, setter.isEmpty() ? "set by the season" : "set by " + setter + ", " + m.getString("why"), (int) ((lw - 40) / 0.75F)),
            x + 36, sy + 12, m.getBoolean("byPlayer") ? Ui.GOOD : Ui.MUTED);
        small(g, font, "since day " + m.getLong("since"), x + 36, sy + 20, Ui.FAINT);
        sy += 32;
        int in = m.getInt("wearing"), all = Math.max(1, m.getInt("grown"));
        small(g, font, in + " of " + m.getInt("grown") + " wear it", x + 2, sy, Ui.INK);
        bar(g, x + 70, sy, lw - 74, 6, in / (float) all, rgb);
        sy += 10;
        if (m.getInt("lastColour") >= 0) {
            int lastRgb = 0xFF000000 | dye(m.getInt("lastColour"));
            small(g, font, m.getInt("wearingLast") + " still in " + m.getString("lastWord"), x + 2, sy, Ui.FAINT);
            bar(g, x + 70, sy, lw - 74, 6, m.getInt("wearingLast") / (float) all, lastRgb);
            sy += 10;
        }
        // Day by day: how it has gone round.
        ListTag days = m.getList("days", Tag.TAG_COMPOUND);
        int ch = 40;
        Ui.section(g, font, "Day by day", x, sy + 2, lw);
        int cy = sy + 14;
        g.fill(x + 2, cy, x + lw - 2, cy + ch, Ui.ROW);
        g.renderOutline(x + 2, cy, lw - 4, ch, Ui.EDGE_SOFT);
        if (!days.isEmpty()) {
            int n = days.size();
            int bw = Math.max(2, (lw - 8) / Math.max(1, n));
            for (int i = 0; i < n; i++) {
                CompoundTag d = days.getCompound(i);
                int dAll = Math.max(1, d.getInt("all"));
                int bx = x + 4 + i * bw;
                int hIn = (int) ((ch - 4) * d.getInt("in") / (double) dAll);
                int hLast = (int) ((ch - 4) * d.getInt("last") / (double) dAll);
                g.fill(bx, cy + ch - 2 - hIn, bx + bw - 1, cy + ch - 2, rgb);
                if (m.getInt("lastColour") >= 0 && hLast > 0) {
                    int lastRgb = 0xFF000000 | dye(m.getInt("lastColour"));
                    g.fill(bx + bw / 3, cy + ch - 2 - hLast, bx + bw / 3 + Math.max(1, bw / 3), cy + ch - 2, lastRgb);
                }
                if (mx >= bx && mx < bx + bw && my >= cy && my < cy + ch) {
                    tip = List.of(Component.literal("Day " + d.getLong("day")), Component.literal(d.getInt("in") + " of " + d.getInt("all") + " in "
                        + m.getString("colourWord")), Component.literal(d.getInt("last") + " in " + m.getString("lastWord")));
                }
            }
        } else {
            small(g, font, "The first day's count is taken tomorrow morning.", x + 6, cy + 16, Ui.FAINT);
        }
        // Colour by colour: what the town wears.
        int ky = cy + ch + 4;
        Ui.section(g, font, "What the town wears", x, ky, lw);
        int[] colours = m.getIntArray("colours");
        int most = 1;
        for (int c : colours) most = Math.max(most, c);
        int kh = Math.max(16, Math.min(40, y + h - ky - 16));
        int bwc = Math.max(3, (lw - 8) / 17);
        for (int i = 0; i < colours.length && i < 17; i++) {
            int bx = x + 4 + i * bwc;
            int bh = (int) ((kh - 2) * colours[i] / (double) most);
            int c = i < 16 ? 0xFF000000 | dye(i) : 0xFFE9E1CC;
            g.fill(bx, ky + 12 + kh - bh, bx + bwc - 1, ky + 12 + kh, c);
            if (i == colour) g.renderOutline(bx - 1, ky + 11, bwc + 1, kh + 2, Ui.EDGE);
            if (mx >= bx && mx < bx + bwc && my >= ky + 12 && my < ky + 12 + kh) {
                tip = List.of(Component.literal((i < 16 ? capital(Garment.colourWord(i)) : "Undyed") + ": " + colours[i]
                    + (colours[i] == 1 ? " thing worn" : " things worn")));
            }
        }
        // ---- the right: the town's word on it
        int ry = y;
        ListTag setters = m.getList("setters", Tag.TAG_STRING);
        Ui.section(g, font, "The town looks to", rx, ry, rw);
        ry += 11;
        for (int i = 0; i < Math.min(3, setters.size()); i++) {
            small(g, font, Ui.clip(font, (i + 1) + ". " + setters.getString(i), maxR), rx + 2, ry, i == 0 ? Ui.INK : Ui.MUTED);
            ry += 8;
        }
        if (setters.isEmpty()) { small(g, font, "nobody in particular", rx + 2, ry, Ui.FAINT); ry += 8; }
        ry += 2;
        CompoundTag orders = m.getCompound("orders");
        ListTag book = orders.getList("book", Tag.TAG_COMPOUND);
        Ui.section(g, font, "The tailor's book", rx, ry, rw);
        ry += 11;
        String tailor = orders.getString("tailor");
        small(g, font, Ui.clip(font, (tailor.isEmpty() ? "No tailor yet" : tailor) + " · " + book.size() + " on order · "
            + orders.getInt("week") + " made this week", maxR), rx + 2, ry, Ui.MUTED);
        ry += 8;
        for (int i = 0; i < Math.min(4, book.size()); i++) {
            CompoundTag o = book.getCompound(i);
            String status = o.getString("status");
            int c = o.getInt("colour");
            if (c >= 0 && c < 16) g.fill(rx + 2, ry + 1, rx + 6, ry + 5, 0xFF000000 | dye(c));
            small(g, font, Ui.clip(font, capital(o.getString("what")) + " for " + o.getString("for") + (status.isEmpty() ? "" : ": " + status), maxR - 8),
                rx + 9, ry, status.isEmpty() ? Ui.INK : Ui.WARN);
            ry += 8;
        }
        if (book.size() > 4) { small(g, font, "…and " + (book.size() - 4) + " more", rx + 9, ry, Ui.FAINT); ry += 8; }
        if (!orders.getString("dyes").isEmpty()) {
            small(g, font, Ui.clip(font, "Short of " + orders.getString("dyes") + "; " + orders.getInt("picked") + " flowers picked for it", maxR),
                rx + 2, ry, Ui.WARN);
            ry += 8;
        }
        if (!m.getString("price").isEmpty()) {
            small(g, font, Ui.clip(font, m.getString("price"), maxR), rx + 2, ry, Ui.MUTED);
            ry += 8;
        }
        small(g, font, Ui.clip(font, "This week: " + m.getInt("poorBox") + " old things through the poor box, " + m.getInt("secondHand")
            + " sold second-hand", maxR), rx + 2, ry, Ui.FAINT);
        ry += 10;
        Ui.section(g, font, "The shows", rx, ry, rw);
        ry += 11;
        if (!m.getString("show").isEmpty()) {
            for (String line : wrap(font, m.getString("show"), maxR, 2)) {
                small(g, font, line, rx + 2, ry, Ui.INK);
                ry += 8;
            }
        }
        small(g, font, Ui.clip(font, m.getString("nextShow"), maxR), rx + 2, ry, Ui.MUTED);
        ry += 10;
        // Everybody, the scroll wheel for the rest.
        ListTag folk = m.getList("folk", Tag.TAG_COMPOUND);
        int rows = Math.max(1, (y + h - ry - 12) / 8);
        int start = Math.max(0, Math.min(scroll, Math.max(0, folk.size() - rows)));
        Ui.section(g, font, "Who wears what" + (folk.size() > rows ? " (" + (start + 1) + "-" + Math.min(folk.size(), start + rows) + " of " + folk.size()
            + ", scroll)" : ""), rx, ry, rw);
        ry += 11;
        for (int i = start; i < folk.size() && i < start + rows; i++) {
            CompoundTag f = folk.getCompound(i);
            int main = f.getInt("main");
            if (main >= 0) g.fill(rx + 2, ry + 1, rx + 6, ry + 5, 0xFF000000 | dye(main));
            String mark = f.getBoolean("setter") ? "★ " : f.getBoolean("in") ? "• " : f.getBoolean("holdout") ? "× " : "";
            String wears = f.getString("wears");
            String line = mark + f.getString("name") + ": " + (wears.isEmpty() ? "its trade's clothes" : wears)
                + (f.getString("want").isEmpty() ? "" : "; wants " + f.getString("want"));
            int col = f.getBoolean("in") ? Ui.GOOD : f.getBoolean("holdout") ? Ui.FAINT : Ui.INK;
            small(g, font, Ui.clip(font, line, maxR - 8), rx + 9, ry, col);
            if (mx >= rx && mx < rx + rw && my >= ry && my < ry + 8) {
                tip = List.of(Component.literal(f.getString("name")), Component.literal(wears.isEmpty() ? "Nothing of its own over its trade's clothes" : wears),
                    Component.literal(f.getBoolean("in") ? "In the season's fashion" : f.getBoolean("holdout") ? "Keeps to its own colours"
                        : "Come round to it: " + f.getInt("pull") + "%"));
            }
            ry += 8;
        }
        return tip;
    }

    private static int dye(int id) {
        return DyeColor.byId(Math.max(0, Math.min(15, id))).getTextureDiffuseColor() & 0xFFFFFF;
    }

    private static void bar(GuiGraphics g, int x, int y, int w, int h, float frac, int colour) {
        if (w <= 2) return;
        g.fill(x, y, x + w, y + h, TRACK);
        int fill = (int) (w * Math.max(0F, Math.min(1F, frac)));
        if (fill > 0) g.fill(x, y, x + fill, y + h, colour);
        g.renderOutline(x, y, w, h, Ui.EDGE);
    }

    private static List<String> wrap(Font font, String s, int max, int lines) {
        List<String> out = new ArrayList<>();
        String rest = s;
        while (!rest.isEmpty() && out.size() < lines) {
            if (font.width(rest) <= max || out.size() == lines - 1) {
                out.add(Ui.clip(font, rest, max));
                break;
            }
            int cut = rest.length();
            while (cut > 1 && font.width(rest.substring(0, cut)) > max) cut--;
            int space = rest.lastIndexOf(' ', cut);
            if (space > 0) cut = space;
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut).trim();
        }
        return out;
    }

    private static void small(GuiGraphics g, Font font, String s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
