package com.jrpetty.mcassistant.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [featfolk] Three pages of the town's books (CityScreen), from the server's FolkLinesReport:
 * <ul>
 * <li><b>Families</b>: the family trees, eldest down (partners side by side, children under them), who is walking out
 *     with whom, what was handed down, the town's room for more and the family news;</li>
 * <li><b>Apprentices</b>: each trade's masters, journeymen and apprentices, how far on each is, the knacks passed on,
 *     and the lines of who taught whom;</li>
 * <li><b>Respect</b>: what the town thinks of you, as a named rating with a bar, from its witnesses only, and why, in
 *     their words; and what it does for you here.</li>
 * </ul>
 * Kept apart from the screen: CityScreen only hands the page over.
 */
public final class FolkLinesPages {

    private FolkLinesPages() {}

    private static final int ROW = 10;

    private record Line(char kind, int indent, FormattedCharSequence text) {}

    @Nullable
    public static List<Component> draw(String page, GuiGraphics g, Font font, CompoundTag data, int x, int y, int w, int h,
                                       int scroll, int mx, int my) {
        List<Line> lines = new ArrayList<>();
        int width = w - 12;
        int top = y;
        switch (page) {
            case "Families" -> {
                CompoundTag c = data.getCompound("families");
                if (c.isEmpty()) return empty(g, font, x, y, w);
                y = header(g, font, c.getString("summary"), c.getString("housing"), x, y, w);
                ListTag trees = c.getList("trees", Tag.TAG_COMPOUND);
                if (trees.isEmpty()) add(lines, font, 'M', 0, "No families yet: nobody here has a partner or a child.", width);
                for (int i = 0; i < trees.size(); i++) {
                    CompoundTag t = trees.getCompound(i);
                    heading(lines, t.getString("name"));
                    ListTag rows = t.getList("rows", Tag.TAG_COMPOUND);
                    for (int j = 0; j < rows.size(); j++) {
                        CompoundTag r = rows.getCompound(j);
                        int d = r.getInt("depth");
                        String kind = r.getString("kind");
                        add(lines, font, kind.equals("couple") ? 'G' : kind.equals("child") ? 'C' : 'N', d,
                            (d == 0 ? "" : "└ ") + r.getString("text"), width - d * 10);
                    }
                }
                list(lines, font, c, "courting", "Walking out", 'N', width);
                list(lines, font, c, "handed", "Handed down", 'N', width);
                list(lines, font, c, "news", "Family news", 'M', width);
            }
            case "Apprentices" -> {
                CompoundTag c = data.getCompound("apprentices");
                if (c.isEmpty()) return empty(g, font, x, y, w);
                y = header(g, font, c.getString("summary"), "Children learn of a morning at a master's side; grown, they qualify as journeymen, then masters.", x, y, w);
                ListTag trades = c.getList("trades", Tag.TAG_COMPOUND);
                if (trades.isEmpty()) add(lines, font, 'M', 0, "Nobody is apprenticed yet: children start at their parents' side once they are old enough.", width);
                for (int i = 0; i < trades.size(); i++) {
                    CompoundTag t = trades.getCompound(i);
                    heading(lines, t.getString("trade"));
                    ListTag rows = t.getList("rows", Tag.TAG_COMPOUND);
                    for (int j = 0; j < rows.size(); j++) {
                        CompoundTag r = rows.getCompound(j);
                        String rank = r.getString("rank");
                        StringBuilder sb = new StringBuilder(r.getString("name")).append(" · ").append(cap(rank));
                        if (rank.equals("apprentice")) sb.append(" · ").append(bar(r.getInt("skill"))).append(' ').append(r.getInt("skill")).append('%');
                        else sb.append(" · level ").append(r.getInt("level"));
                        if (!r.getString("master").isEmpty()) sb.append(rank.equals("apprentice") ? " · under " : " · taught by ").append(r.getString("master"));
                        if (!r.getString("taught").isEmpty()) sb.append(" · learned ").append(r.getString("taught"));
                        if (!r.getString("knacks").isEmpty()) sb.append(" · knacks: ").append(r.getString("knacks"));
                        if (!r.getString("pupils").isEmpty()) sb.append(" · brought on ").append(r.getString("pupils"));
                        add(lines, font, rank.equals("master") ? 'G' : rank.equals("journeyman") ? 'N' : 'C', rank.equals("master") ? 0 : 1, sb.toString(), width - 10);
                    }
                }
                list(lines, font, c, "lines", "Who taught whom", 'N', width);
            }
            default -> {
                CompoundTag c = data.getCompound("respect");
                if (c.isEmpty()) return empty(g, font, x, y, w);
                // The rating, named, with its bar.
                int score = c.getInt("respect");
                String name = c.getString("rating");
                g.drawString(font, name + " in " + c.getString("town"), x + 2, y, score >= 10 ? Ui.GOOD : score <= -15 ? Ui.BAD : Ui.INK, false);
                String num = (score > 0 ? "+" : "") + score;
                g.drawString(font, num, x + w - font.width(num) - 2, y, Ui.MUTED, false);
                y += 11;
                int bw = w - 4, mid = x + 2 + bw / 2;
                g.fill(x + 2, y, x + 2 + bw, y + 5, Ui.PANEL_SOFT);
                int len = (int) Math.round(Math.abs(score) / 100.0 * (bw / 2.0));
                if (score > 0) g.fill(mid, y, mid + len, y + 5, Ui.GOOD);
                else if (score < 0) g.fill(mid - len, y, mid, y + 5, Ui.BAD);
                g.fill(mid, y - 2, mid + 1, y + 7, Ui.EDGE);
                y += 9;
                String sub = c.getInt("witnesses") + (c.getInt("witnesses") == 1 ? " has" : " have") + " seen you or dealt with you; "
                    + c.getInt("heard") + " only heard of you from them. You are " + c.getString("title") + ".";
                for (FormattedCharSequence s : TextCache.split(font, sub, w - 4)) { g.drawString(font, s, x + 2, y, Ui.FAINT, false); y += 10; }
                y += 2;
                if (!c.getString("reasons").isEmpty()) add(lines, font, 'N', 0, c.getString("reasons"), width);
                list(lines, font, c, "good", "In your favour", 'G', width);
                list(lines, font, c, "bad", "Against you", 'W', width);
                if (c.getList("good", Tag.TAG_STRING).isEmpty() && c.getList("bad", Tag.TAG_STRING).isEmpty())
                    add(lines, font, 'M', 0, "Nobody here has seen you do anything worth repeating. Nobody here makes things up, either.", width);
                list(lines, font, c, "effects", "What it means here", 'N', width);
            }
        }
        int ch = h - (y - top);
        int rows = Math.max(1, ch / ROW);
        int start = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - rows)));
        int cy = y;
        for (int i = start; i < lines.size() && cy + ROW <= y + ch; i++) {
            Line l = lines.get(i);
            int lx = x + 4 + l.indent() * 10;
            switch (l.kind()) {
                case 'H' -> {
                    g.fill(x, cy + 2, x + 2, cy + 9, Ui.ACCENT);
                    g.drawString(font, l.text(), x + 5, cy + 1, Ui.FAINT, false);
                }
                case 'G' -> g.drawString(font, l.text(), lx, cy + 1, Ui.GOOD, false);
                case 'W' -> g.drawString(font, l.text(), lx, cy + 1, Ui.BAD, false);
                case 'C' -> g.drawString(font, l.text(), lx, cy + 1, 0xFF2E5C8A, false);
                case 'M' -> g.drawString(font, l.text(), lx, cy + 1, Ui.FAINT, false);
                default -> g.drawString(font, l.text(), lx, cy + 1, Ui.INK, false);
            }
            cy += ROW;
        }
        if (lines.size() > rows) {
            String more = "(scroll for more)";
            g.drawString(font, more, x + w - font.width(more) - 2, y + ch - 9, Ui.FAINT, false);
        }
        return null;
    }

    private static List<Component> empty(GuiGraphics g, Font font, int x, int y, int w) {
        g.drawString(font, "Not in this town's books yet.", x + 2, y, Ui.FAINT, false);
        return null;
    }

    private static int header(GuiGraphics g, Font font, String a, String b, int x, int y, int w) {
        for (FormattedCharSequence s : TextCache.split(font, a, w - 4)) { g.drawString(font, s, x + 2, y, Ui.ACCENT, false); y += 10; }
        List<FormattedCharSequence> sub = TextCache.split(font, b, w - 4);
        for (int i = 0; i < Math.min(3, sub.size()); i++) { g.drawString(font, sub.get(i), x + 2, y, Ui.FAINT, false); y += 10; }
        return y + 3;
    }

    private static void list(List<Line> lines, Font font, CompoundTag c, String key, String title, char kind, int width) {
        ListTag l = c.getList(key, Tag.TAG_STRING);
        if (l.isEmpty()) return;
        heading(lines, title);
        for (int i = 0; i < l.size(); i++) add(lines, font, kind, 0, "• " + l.getString(i), width);
    }

    private static void heading(List<Line> lines, String s) {
        lines.add(new Line('H', 0, FormattedCharSequence.forward(s, net.minecraft.network.chat.Style.EMPTY)));
    }

    private static void add(List<Line> lines, Font font, char kind, int indent, String text, int width) {
        for (FormattedCharSequence l : TextCache.split(font, text, Math.max(40, width))) lines.add(new Line(kind, Math.min(indent, 5), l));
    }

    private static String bar(int pct) {
        int n = Math.max(0, Math.min(10, pct / 10));
        return "■".repeat(n) + "□".repeat(10 - n);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
