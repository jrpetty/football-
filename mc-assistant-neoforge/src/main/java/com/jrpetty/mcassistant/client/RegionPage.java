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
 * [links12] The Region page of the town's books (CityScreen), as the server's Region.report has it. On the left, a map of
 * the towns round about: each town a dot in the colour of how it stands with us (allies green, friendly blue, neutral
 * grey, rivals amber, hostile red; ours gold), the roads between them drawn along the way they were surveyed (dotted
 * where nothing is laid yet, brown a path, grey gravel, dark stone; each half as far as its gang has got), a railway as a
 * line of sleepers beside it, an embargo crossed in red. The mouse over a town or a road says all about it. On the right:
 * the towns and how we stand, the roads and rails with their state and what going costs, the league table, the news.
 */
public final class RegionPage {

    private RegionPage() {}

    private static final int PAPER = 0xFFE4D6B0, GOLD = 0xFFC9A227, TRACK = 0xFF8C7B5A, PATH = 0xFF8B5A2B, GRAVEL = 0xFF7A7A7A,
        STONE = 0xFF3E3E46, RAIL = 0xFF2B2B2B, EMBARGO = 0xFFB83227;

    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        ListTag towns = m.getList("towns", Tag.TAG_COMPOUND);
        if (towns.isEmpty()) {
            Ui.section(g, font, "The region", x, y, w);
            small(g, font, "The books have no word of the region yet.", x + 4, y + 14, Ui.MUTED);
            return null;
        }
        int side = Math.max(90, Math.min(h - 4, w * 9 / 20));
        List<Component> tip = map(g, font, m, x, y, side, mx, my);
        int lx = x + side + 8, lw = w - side - 8, max = (int) (lw / 0.75F) - 6;
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{ "H", "Towns round " + m.getString("name") });
        if (towns.size() <= 1) rows.add(new String[]{ "M", "No other town within reach yet." });
        for (int i = 0; i < towns.size(); i++) {
            CompoundTag t = towns.getCompound(i);
            if (t.getBoolean("me")) continue;
            String s = t.getString("name") + ": " + t.getString("stance") + " (relation " + t.getInt("rel") + ")"
                + (t.getBoolean("pact") ? ", trade pact" : "") + (t.getBoolean("embargo") ? ", EMBARGO" : "")
                + (t.getInt("rivalry") > 0 ? ", rivalry " + t.getInt("rivalry") + (t.getString("why").isEmpty() ? "" : " over " + t.getString("why")) : "")
                + "; " + t.getInt("folk") + " folk, " + t.getString("age");
            String kind = t.getBoolean("embargo") || "hostile".equals(t.getString("stance")) ? "W" : "rivals".equals(t.getString("stance")) ? "A"
                : "allies".equals(t.getString("stance")) ? "G" : "N";
            rows.add(new String[]{ kind, s });
        }
        ListTag rs = m.getList("routes", Tag.TAG_COMPOUND);
        rows.add(new String[]{ "H", "Roads and railways" });
        if (rs.isEmpty()) rows.add(new String[]{ "M", "No roads yet: a road is surveyed once two towns have dealings." });
        for (int i = 0; i < rs.size(); i++) {
            CompoundTag r = rs.getCompound(i);
            rows.add(new String[]{ r.getBoolean("embargo") ? "W" : "N", routeWords(towns, r) });
        }
        ListTag lg = m.getList("league", Tag.TAG_COMPOUND);
        rows.add(new String[]{ "H", "League table (standing)" });
        for (int i = 0; i < lg.size(); i++) {
            CompoundTag t = lg.getCompound(i);
            rows.add(new String[]{ t.getBoolean("me") ? "G" : "N", (i + 1) + ". " + t.getString("name") + "  " + t.getInt("standing")
                + "   (folk " + t.getInt("folk") + ", renown " + t.getInt("renown") + ", links " + t.getInt("links") + ", trade " + t.getInt("trade") + ")" });
        }
        ListTag ns = m.getList("news", Tag.TAG_STRING);
        rows.add(new String[]{ "H", "News of the region" });
        if (ns.isEmpty()) rows.add(new String[]{ "M", "Nothing yet." });
        for (int i = 0; i < ns.size(); i++) rows.add(new String[]{ "M", ns.getString(i) });
        List<Object[]> flat = new ArrayList<>();
        for (String[] r : rows) {
            if (r[0].equals("H")) { flat.add(new Object[]{ "H", r[1] }); continue; }
            for (FormattedCharSequence part : font.split(Component.literal(r[1]), max)) flat.add(new Object[]{ r[0], part });
        }
        int fit = Math.max(1, (h - 2) / 9);
        int start = Math.max(0, Math.min(scroll, Math.max(0, flat.size() - fit)));
        int cy = y;
        for (int i = start; i < flat.size() && cy < y + h - 8; i++) {
            Object[] r = flat.get(i);
            if (r[0].equals("H")) {
                if (cy > y) cy += 2;
                Ui.section(g, font, Ui.clip(font, (String) r[1], lw - 4), lx, cy, lw);
                cy += 11;
                continue;
            }
            int colour = switch ((String) r[0]) {
                case "W" -> Ui.WARN;
                case "G" -> Ui.GOOD;
                case "A" -> 0xFFB7791F;
                case "M" -> Ui.FAINT;
                default -> Ui.INK;
            };
            g.pose().pushPose();
            g.pose().translate(lx + 4, cy, 0);
            g.pose().scale(0.75F, 0.75F, 1F);
            g.drawString(font, (FormattedCharSequence) r[1], 0, 0, colour, false);
            g.pose().popPose();
            cy += 9;
        }
        if (flat.size() > fit) small(g, font, "(scroll for more)", x + w - 64, y + h - 8, Ui.FAINT);
        return tip;
    }

    private static String routeWords(ListTag towns, CompoundTag r) {
        String a = name(towns, r.getInt("a")), b = name(towns, r.getInt("b"));
        int[] half = r.getIntArray("half");
        String grades = "";
        if (half.length >= 6) {
            grades = "; " + a + "'s half " + GRADE[Math.min(3, half[0])] + (half[1] > 0 ? " (" + half[1] + " of " + half[2] + " steps on to the next)" : "")
                + ", " + b + "'s " + GRADE[Math.min(3, half[3])] + (half[4] > 0 ? " (" + half[4] + " of " + half[5] + " on)" : "");
        }
        int rail = r.getInt("rail");
        return a + " - " + b + ": " + r.getInt("length") + " blocks, " + GRADE[Math.min(3, r.getInt("grade"))] + grades
            + (r.getInt("bridges") > 0 ? "; bridging " + r.getInt("bridges") + " blocks of water" : "")
            + (rail == 2 ? "; RAILWAY open" : rail == 1 ? "; a railway being laid" : "")
            + "; going takes " + time(r.getLong("ticks")) + ", a load's road money " + r.getInt("money")
            + (r.getInt("toll") > 0 ? " (a rival's toll " + r.getInt("toll") + ")" : "") + (r.getBoolean("embargo") ? "; shut by an EMBARGO" : "");
    }

    private static final String[] GRADE = { "a track", "an earth path", "gravel", "stone" };

    private static String time(long ticks) {
        double hours = ticks / 1000.0;
        return hours >= 1.5 ? Math.round(hours) + " hours" : Math.max(1, Math.round(hours * 60)) + " minutes";
    }

    private static String name(ListTag towns, int i) {
        return i >= 0 && i < towns.size() ? towns.getCompound(i).getString("name") : "?";
    }

    /** The map: the towns, the roads between them along their surveys, the railways beside them. */
    @Nullable
    private static List<Component> map(GuiGraphics g, Font font, CompoundTag m, int x, int y, int side, int mx, int my) {
        g.fill(x, y, x + side, y + side, PAPER);
        g.renderOutline(x, y, side, side, Ui.EDGE);
        ListTag towns = m.getList("towns", Tag.TAG_COMPOUND);
        ListTag rs = m.getList("routes", Tag.TAG_COMPOUND);
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (int i = 0; i < towns.size(); i++) {
            CompoundTag t = towns.getCompound(i);
            x0 = Math.min(x0, t.getInt("x")); x1 = Math.max(x1, t.getInt("x"));
            z0 = Math.min(z0, t.getInt("z")); z1 = Math.max(z1, t.getInt("z"));
        }
        for (int i = 0; i < rs.size(); i++) {
            int[] p = rs.getCompound(i).getIntArray("pts");
            for (int k = 0; k + 1 < p.length; k += 2) { x0 = Math.min(x0, p[k]); x1 = Math.max(x1, p[k]); z0 = Math.min(z0, p[k + 1]); z1 = Math.max(z1, p[k + 1]); }
        }
        int span = Math.max(160, Math.max(x1 - x0, z1 - z0)) + 80;
        int cx = (x0 + x1) / 2, cz = (z0 + z1) / 2;
        float scale = (side - 8) / (float) span;
        List<Component> tip = null;
        for (int i = 0; i < rs.size(); i++) {
            CompoundTag r = rs.getCompound(i);
            int[] p = r.getIntArray("pts");
            int[] half = r.getIntArray("half");
            int n = p.length / 2;
            boolean near = false;
            if (r.getInt("rail") > 0) {
                int[] q = r.getIntArray("railPts");
                for (int k = 2; k + 1 < q.length; k += 2) {
                    line(g, sx(q[k - 2], cx, scale, x, side), sy(q[k - 1], cz, scale, y, side), sx(q[k], cx, scale, x, side), sy(q[k + 1], cz, scale, y, side),
                        r.getInt("rail") == 2 ? RAIL : Ui.lighten(RAIL, 0.5F), 2);
                }
            }
            for (int k = 1; k < n; k++) {
                // Which half this stretch is of, and how good that half is.
                boolean firstHalf = k < n / 2;
                int grade = half.length >= 6 ? (firstHalf ? half[0] : half[3]) : r.getInt("grade");
                int colour = grade >= 3 ? STONE : grade == 2 ? GRAVEL : grade == 1 ? PATH : TRACK;
                int ax = sx(p[2 * k - 2], cx, scale, x, side), ay = sy(p[2 * k - 1], cz, scale, y, side);
                int bx = sx(p[2 * k], cx, scale, x, side), by = sy(p[2 * k + 1], cz, scale, y, side);
                line(g, ax, ay, bx, by, colour, grade == 0 ? 3 : 1);
                if (Math.abs(mx - (ax + bx) / 2) <= 3 && Math.abs(my - (ay + by) / 2) <= 3) near = true;
            }
            if (r.getBoolean("embargo") && n > 1) {
                int ex = sx(p[2 * (n / 2)], cx, scale, x, side), ey = sy(p[2 * (n / 2) + 1], cz, scale, y, side);
                line(g, ex - 3, ey - 3, ex + 3, ey + 3, EMBARGO, 1);
                line(g, ex - 3, ey + 3, ex + 3, ey - 3, EMBARGO, 1);
            }
            if (near) tip = List.of(Component.literal(routeWords(towns, r)));
        }
        for (int i = 0; i < towns.size(); i++) {
            CompoundTag t = towns.getCompound(i);
            int tx = sx(t.getInt("x"), cx, scale, x, side), ty = sy(t.getInt("z"), cz, scale, y, side);
            int colour = t.getBoolean("me") ? GOLD : 0xFF000000 | t.getInt("colour");
            g.fill(tx - 3, ty - 3, tx + 4, ty + 4, 0xFF000000);
            g.fill(tx - 2, ty - 2, tx + 3, ty + 3, colour);
            int nameW = (int) (font.width(t.getString("name")) * 0.75F);
            small(g, font, t.getString("name"), tx + 5 + nameW > x + side - 2 ? tx - 5 - nameW : tx + 5, ty - 3, Ui.INK);
            if (Math.abs(mx - tx) <= 4 && Math.abs(my - ty) <= 4) {
                tip = t.getBoolean("me") ? List.of(Component.literal(t.getString("name") + " (us)"), Component.literal(t.getInt("folk") + " folk, " + t.getString("age")))
                    : List.of(Component.literal(t.getString("name") + ": " + t.getString("stance")),
                        Component.literal("Relation " + t.getInt("rel") + (t.getInt("rivalry") > 0 ? ", rivalry " + t.getInt("rivalry") : "")
                            + (t.getString("why").isEmpty() ? "" : " over " + t.getString("why"))),
                        Component.literal(t.getInt("folk") + " folk, " + t.getString("age") + (t.getBoolean("pact") ? ", trade pact" : "")
                            + (t.getBoolean("embargo") ? ", under embargo" : "")));
            }
        }
        small(g, font, "N", x + side / 2 - 2, y + 2, Ui.FAINT);
        small(g, font, span + " blocks across", x + 3, y + side - 8, Ui.FAINT);
        return tip;
    }

    private static int sx(int wx, int cx, float scale, int x, int side) { return x + side / 2 + Math.round((wx - cx) * scale); }

    private static int sy(int wz, int cz, float scale, int y, int side) { return y + side / 2 + Math.round((wz - cz) * scale); }

    /** A line of dots (every {@code gap} pixels; 1 for solid), two pixels thick. */
    private static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int colour, int gap) {
        int n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int k = 0; k <= n; k++) {
            if (gap > 1 && k % gap != 0) continue;
            int px = n == 0 ? x0 : x0 + (x1 - x0) * k / n, py = n == 0 ? y0 : y0 + (y1 - y0) * k / n;
            g.fill(px, py, px + 2, py + 2, colour);
        }
    }

    private static void small(GuiGraphics g, Font font, String s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }
}
