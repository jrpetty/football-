package com.jrpetty.mcassistant.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [identity] The Identity page of the town's books (CityScreen), as the server's Identity.report has it: the town in
 * one line; its seven axes as bars from one end to the other (where it stands, and a mark where its founding put it);
 * how it is ruled, by whom, who votes and how a player could come to lead it; its renown, its title and what it is
 * famous for; its traits as badges; and, scrolling under them, its law-book, the stories of its traits, its deeds,
 * how it treats a player, what has changed, and the parts the town's culture and perks add to the page.
 */
public final class IdentityPage {

    private IdentityPage() {}

    private static final int ROW = 10;
    private static final int PLUS = 0xFF2E6FBF, MINUS = 0xFFB7791F;

    private record Line(char kind, FormattedCharSequence text) {}

    /** The page, the scrolling part from the scroll'th line. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag c, int x, int y, int w, int h, int scroll, int mx, int my) {
        List<Component> tip = null;
        int top = y;
        // The town in one line, two at most.
        List<FormattedCharSequence> sum = font.split(Component.literal(c.getString("summary")), w - 4);
        for (int i = 0; i < Math.min(2, sum.size()); i++) {
            g.drawString(font, sum.get(i), x + 2, y, Ui.ACCENT, false);
            y += 10;
        }
        if (!c.contains("axes")) return null;
        y += 2;
        // ---- left: the seven axes
        int half = (w - 8) / 2;
        Ui.section(g, font, "Its character", x, y, half);
        int by = y + 11;
        ListTag axes = c.getList("axes", Tag.TAG_COMPOUND);
        int label = 52;
        for (int i = 0; i < axes.size(); i++) {
            CompoundTag a = axes.getCompound(i);
            int v = a.getInt("value"), seed = a.getInt("seed");
            int rowY = by + i * 12;
            small(g, font, a.getString("plus"), x + 2, rowY + 1, v > 0 ? PLUS : Ui.FAINT);
            String minus = a.getString("minus");
            small(g, font, minus, x + half - (int) (font.width(minus) * 0.75F), rowY + 1, v < 0 ? MINUS : Ui.FAINT);
            int bx = x + label, bw = half - label * 2 + 6;
            if (bw < 30) { bx = x + 2; bw = half - 4; }
            int barY = rowY + 7, mid = bx + bw / 2;
            g.fill(bx, barY, bx + bw, barY + 3, Ui.PANEL_SOFT);
            int len = (int) Math.round(Math.abs(v) / 100.0 * (bw / 2.0));
            if (v > 0) g.fill(mid - len, barY, mid, barY + 3, PLUS);
            else if (v < 0) g.fill(mid, barY, mid + len, barY + 3, MINUS);
            g.fill(mid, barY - 2, mid + 1, barY + 5, Ui.EDGE);
            int sx = mid - (int) Math.round(seed / 100.0 * (bw / 2.0));
            g.fill(sx, barY - 1, sx + 1, barY + 4, Ui.INK);
            if (mx >= x && mx < x + half && my >= rowY && my < rowY + 12) {
                tip = new ArrayList<>();
                tip.add(Component.literal(a.getString("plus") + " or " + a.getString("minus") + ": " + (v == 0 ? "neither"
                    : (v > 0 ? a.getString("plus") : a.getString("minus")) + " " + Math.abs(v))));
                tip.add(Component.literal("At its founding: " + (seed >= 0 ? a.getString("plus") : a.getString("minus")) + " " + Math.abs(seed)));
                tip.add(Component.literal(a.getString("does")));
            }
        }
        int leftEnd = by + axes.size() * 12;
        // ---- right: its government, renown and fame
        int rx = x + half + 8, rw = w - half - 8;
        CompoundTag gov = c.getCompound("gov");
        Ui.section(g, font, "How it is ruled", rx, y, rw);
        int ry = y + 11;
        g.drawString(font, Ui.clip(font, gov.getString("form"), rw - 2), rx + 2, ry, Ui.INK, false);
        ry += 10;
        String leader = gov.getString("leader");
        ry = wrap(g, font, (leader.isEmpty() ? "Nobody leads it just now" : capital(gov.getString("title")) + ": " + leader)
            + (gov.getString("house").isEmpty() ? "" : ", " + gov.getString("house")) + ". " + capital(gov.getString("how")) + ".", rx + 2, ry, rw - 4, 3, Ui.MUTED);
        ry = wrap(g, font, capital(gov.getString("votes")) + (gov.getString("consort").isEmpty() ? "" : "; consort " + gov.getString("consort")) + ".",
            rx + 2, ry, rw - 4, 1, Ui.FAINT);
        CompoundTag fame = c.getCompound("fame");
        ry += 2;
        Ui.section(g, font, "Renown " + fame.getInt("renown") + " · " + capital(fame.getString("rank")), rx, ry, rw);
        ry += 11;
        ListTag famous = fame.getList("famous", Tag.TAG_COMPOUND);
        if (famous.isEmpty()) ry = wrap(g, font, "Not yet famous for anything.", rx + 2, ry, rw - 4, 1, Ui.FAINT);
        for (int i = 0; i < famous.size(); i++) {
            CompoundTag f = famous.getCompound(i);
            ry = wrap(g, font, "Famous for " + f.getString("what") + " (" + f.getString("rate") + " a day; " + f.getString("rival") + ")",
                rx + 2, ry, rw - 4, 2, Ui.GOOD);
        }
        y = Math.max(leftEnd, ry) + 2;
        // ---- its traits, as badges
        ListTag traits = c.getList("traits", Tag.TAG_COMPOUND);
        if (!traits.isEmpty()) {
            int tx = x + 2;
            for (int i = 0; i < traits.size(); i++) {
                CompoundTag t = traits.getCompound(i);
                String name = t.getString("name");
                int tw = font.width(name) + 8;
                if (tx + tw > x + w) { tx = x + 2; y += 13; }
                int colour = t.getBoolean("sombre") ? Ui.BAD : t.getBoolean("founding") ? Ui.FAINT : Ui.GOOD;
                Ui.pill(g, font, name, tx, y + 1, colour);
                if (mx >= tx && mx < tx + tw && my >= y && my < y + 11) {
                    tip = new ArrayList<>();
                    tip.add(Component.literal(name));
                    tip.add(Component.literal(t.getString("story")));
                    tip.add(Component.literal(t.getString("perk")));
                }
                tx += tw + 3;
            }
            y += 14;
        }
        // ---- the rest, scrolling
        int ch = h - (y - top);
        int width = (int) ((w - 8) / 0.75F);
        List<Line> lines = new ArrayList<>();
        heading(lines, "Its law-book");
        for (Tag tg : c.getList("laws", Tag.TAG_COMPOUND)) {
            CompoundTag l = (CompoundTag) tg;
            add(lines, font, l.getBoolean("notable") ? 'G' : 'N', "• " + l.getString("law") + ": " + l.getString("value") + " — " + l.getString("does"), width);
        }
        heading(lines, "Earned traits");
        if (traits.isEmpty()) add(lines, font, 'M', "None yet: floods weathered, raids held off, record harvests, wars won and books written earn them.", width);
        for (int i = 0; i < traits.size(); i++) {
            CompoundTag t = traits.getCompound(i);
            add(lines, font, t.getBoolean("sombre") ? 'W' : 'N', "• " + t.getString("name") + (t.getBoolean("founding") ? " (from its founding)" : "")
                + ": " + t.getString("story") + ". " + capital(t.getString("perk")) + ".", width);
        }
        heading(lines, "Fame and renown");
        add(lines, font, 'N', "Renown " + fame.getInt("renown") + ": " + fame.getString("renownParts") + ".", width);
        add(lines, font, 'M', "Next title: " + fame.getString("next") + ".", width);
        if (!fame.getString("seat").isEmpty()) add(lines, font, 'N', fame.getString("seat"), width);
        for (String s : strings(fame, "perks")) add(lines, font, 'G', "• " + capital(s) + ".", width);
        for (String s : strings(fame, "near")) add(lines, font, 'M', "• Makes " + s + ".", width);
        for (String s : strings(fame, "deeds")) add(lines, font, 'N', "• " + s, width);
        heading(lines, "How it treats you");
        for (String s : strings(c, "treatment")) add(lines, font, 'N', "• " + s, width);
        heading(lines, "Its rulers");
        add(lines, font, 'N', capital(gov.getString("phrase")) + ". " + gov.getString("routes"), width);
        if (!gov.getString("election").isEmpty()) add(lines, font, 'M', "Elections: " + gov.getString("election") + ".", width);
        for (String s : strings(gov, "line")) add(lines, font, 'N', "• " + s, width);
        heading(lines, "What has changed");
        if (!c.getString("founders").isEmpty()) add(lines, font, 'M', "Founded on day " + (c.getLong("seededOn") + 1) + " by " + c.getString("founders") + ".", width);
        for (String s : strings(c, "history")) add(lines, font, 'N', "• " + s, width);
        for (Tag tg : c.getList("more", Tag.TAG_COMPOUND)) {
            CompoundTag m = (CompoundTag) tg;
            heading(lines, m.getString("title"));
            for (String s : strings(m, "lines")) add(lines, font, 'N', "• " + s, width);
        }
        int rows = Math.max(1, ch / ROW);
        int start = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - rows)));
        int cy = y;
        for (int i = start; i < lines.size() && cy + ROW <= y + ch; i++) {
            Line l = lines.get(i);
            switch (l.kind()) {
                case 'H' -> {
                    g.fill(x, cy + 2, x + 2, cy + 9, Ui.ACCENT);
                    g.drawString(font, l.text(), x + 5, cy + 1, Ui.FAINT, false);
                }
                case 'G' -> small(g, font, l.text(), x + 4, cy + 2, Ui.GOOD);
                case 'W' -> small(g, font, l.text(), x + 4, cy + 2, Ui.WARN);
                case 'M' -> small(g, font, l.text(), x + 4, cy + 2, Ui.FAINT);
                default -> small(g, font, l.text(), x + 4, cy + 2, Ui.INK);
            }
            cy += ROW;
        }
        if (lines.size() > rows) small(g, font, FormattedCharSequence.forward("(scroll for more)", Style.EMPTY), x + w - 70, y + ch - 8, Ui.FAINT);
        return tip;
    }

    private static int wrap(GuiGraphics g, Font font, String text, int x, int y, int w, int most, int colour) {
        List<FormattedCharSequence> l = font.split(Component.literal(text), (int) (w / 0.75F));
        for (int i = 0; i < Math.min(most, l.size()); i++) {
            small(g, font, l.get(i), x, y, colour);
            y += 8;
        }
        return y;
    }

    private static void heading(List<Line> lines, String s) {
        lines.add(new Line('H', FormattedCharSequence.forward(s, Style.EMPTY)));
    }

    private static void add(List<Line> lines, Font font, char kind, String text, int width) {
        for (FormattedCharSequence l : font.split(Component.literal(text), width)) lines.add(new Line(kind, l));
    }

    private static List<String> strings(CompoundTag m, String key) {
        List<String> out = new ArrayList<>();
        ListTag l = m.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < l.size(); i++) out.add(l.getString(i));
        return out;
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void small(GuiGraphics g, Font font, String s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    private static void small(GuiGraphics g, Font font, FormattedCharSequence s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }
}
