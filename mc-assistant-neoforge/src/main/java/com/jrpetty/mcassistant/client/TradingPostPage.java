package com.jrpetty.mcassistant.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [emerald] The Trading Post page of the town's books (CityScreen), as the server's EmeraldTrader.report has it. On the
 * left a map, north up, the town in the middle and every village of villagers it knows round it, green where the trader
 * has traded, red where pillagers were raiding it last. On the right: the emerald account (held in the stores, earned for
 * the town's surplus, spent on what it wanted), the buying list, the traders and what they are doing, each village with
 * its villagers (name, trade, level) and their offers as last seen ("sells Mending for 24 emeralds and a book (3 of 12
 * left)"), and what the trader has brought home lately. The mouse over a village on the map gives its name and how it
 * stands.
 */
public final class TradingPostPage {

    private TradingPostPage() {}

    private static final int GROUND = 0xFF6F8F4E, TOWN = 0xFFD8CBA8, KNOWN = 0xFFB9B39A, TRADED = 0xFF2FA35A, RAIDED = 0xFFB83227,
        EMERALD = 0xFF17A05B;

    /** The page. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        List<CompoundTag> villages = compounds(m, "villages");
        int side = Math.max(90, Math.min(h - 4, w * 2 / 5));
        List<Component> tip = map(g, font, m, villages, x, y, side, mx, my);
        int lx = x + side + 8, lw = w - side - 8, max = (int) (lw / 0.75F);
        List<Row> rows = new ArrayList<>();
        rows.add(new Row('H', "The emerald account"));
        rows.add(new Row('A', m.getInt("held") + " emeralds held in the stores; " + m.getInt("earned") + " earned for the town's surplus, "
            + m.getInt("spent") + " spent on what it wanted, over " + m.getInt("trips") + (m.getInt("trips") == 1 ? " trip." : " trips.")));
        if (!m.getBoolean("open") && !m.getString("why").isEmpty()) rows.add(new Row('W', m.getString("why")));
        if (m.getBoolean("apart") && m.getLong("turned") > 0) {
            rows.add(new Row('M', "Folk and villagers are kept apart: villagers' claims on the town's beds, job blocks and bell undone "
                + m.getLong("turned") + (m.getLong("turned") == 1 ? " time." : " times.")));
        }
        List<String> wants = strings(m, "wants");
        rows.add(new Row('H', "Buying"));
        rows.add(new Row('M', wants.isEmpty() ? "Nothing on the list just now." : String.join("; ", wants) + "."));
        List<String> traders = strings(m, "traders");
        if (!traders.isEmpty()) {
            rows.add(new Row('H', traders.size() == 1 ? "The trader" : "The traders"));
            for (String t : traders) rows.add(new Row('M', t));
        }
        rows.add(new Row('H', "The villagers' villages"));
        if (villages.isEmpty()) rows.add(new Row('M', "None known yet. The scouts' atlas has none, and the trader has found none."));
        for (CompoundTag c : villages) {
            String kind = c.getString("kind");
            long visited = c.getLong("visited"), raided = c.getLong("raided");
            rows.add(new Row('L', c.getString("name") + (kind.isEmpty() ? "" : " (" + kind + ")") + ", " + c.getInt("dist") + " blocks "
                + c.getString("way") + ": " + c.getInt("trips") + (c.getInt("trips") == 1 ? " trip" : " trips")
                + (visited >= 0 ? ", last on day " + (visited + 1) : ", not yet visited")
                + (c.getInt("earned") + c.getInt("spent") > 0 ? "; " + c.getInt("earned") + " earned, " + c.getInt("spent") + " spent" : "")));
            if (raided >= 0) rows.add(new Row('W', "Pillagers were raiding it on day " + (raided + 1) + ": the trader kept away."));
            for (CompoundTag s : compounds(c, "folk")) {
                rows.add(new Row('S', s.getString("who") + (s.getInt("dealt") > 0 ? ", " + s.getInt("dealt") + " trades with us" : "")));
                for (String d : strings(s, "deals")) rows.add(new Row('D', d));
            }
        }
        List<String> log = strings(m, "log");
        if (!log.isEmpty()) {
            rows.add(new Row('H', "Brought home"));
            for (int i = log.size() - 1; i >= 0; i--) rows.add(new Row('M', log.get(i)));
        }
        // Laid out in lines first (wrapped), then the page's share of them drawn.
        List<Row> lines = new ArrayList<>();
        for (Row r : rows) {
            if (r.kind == 'H') { lines.add(r); continue; }
            int indent = r.kind == 'S' ? 6 : r.kind == 'D' ? 12 : 2;
            for (String part : wrap(font, r.text, max - indent - 4)) lines.add(new Row(r.kind, part));
        }
        int perRow = 9;
        int fit = Math.max(1, (h - 2) / perRow);
        int start = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - fit)));
        int cy = y;
        for (int i = start; i < lines.size() && cy < y + h - 8; i++) {
            Row r = lines.get(i);
            switch (r.kind) {
                case 'H' -> {
                    if (cy > y) cy += 2;
                    Ui.section(g, font, r.text, lx, cy, lw);
                    cy += 11;
                }
                case 'A' -> { small(g, font, r.text, lx + 2, cy, Ui.GOOD); cy += 9; }
                case 'L' -> { small(g, font, r.text, lx + 2, cy, Ui.INK); cy += 9; }
                case 'S' -> { small(g, font, r.text, lx + 6, cy, Ui.ACCENT); cy += 9; }
                case 'D' -> { small(g, font, r.text, lx + 12, cy, Ui.MUTED); cy += 9; }
                case 'W' -> { small(g, font, r.text, lx + 2, cy, Ui.WARN); cy += 9; }
                default -> { small(g, font, r.text, lx + 2, cy, Ui.MUTED); cy += 9; }
            }
        }
        if (lines.size() > fit) small(g, font, "(scroll for more)", lx + lw - 60, y + h - 8, Ui.FAINT);
        return tip;
    }

    private record Row(char kind, String text) {}

    /** The map: north up, the town in the middle, the villagers' villages round it. */
    @Nullable
    private static List<Component> map(GuiGraphics g, Font font, CompoundTag m, List<CompoundTag> villages, int x, int y, int side, int mx, int my) {
        g.fill(x, y, x + side, y + side, GROUND);
        g.renderOutline(x, y, side, side, Ui.EDGE);
        int[] heart = m.getIntArray("heart");
        if (heart.length < 2) return null;
        double far = 120;
        for (CompoundTag c : villages) {
            int[] at = c.getIntArray("at");
            if (at.length == 2) far = Math.max(far, Math.max(Math.abs(at[0] - heart[0]), Math.abs(at[1] - heart[1])));
        }
        double scale = (side / 2.0 - 10) / (far * 1.05);
        int cx = x + side / 2, cy = y + side / 2;
        int town = Math.max(3, (int) (41 * scale));
        g.fill(cx - town, cy - town, cx + town + 1, cy + town + 1, TOWN);
        g.renderOutline(cx - town, cy - town, 2 * town + 1, 2 * town + 1, Ui.EDGE);
        small(g, font, "N", cx - 2, y + 2, Ui.HI);
        small(g, font, "town", cx - 7, cy + town + 2, Ui.HI);
        List<Component> tip = null;
        for (CompoundTag c : villages) {
            int[] at = c.getIntArray("at");
            if (at.length != 2) continue;
            int px = cx + (int) ((at[0] - heart[0]) * scale), py = cy + (int) ((at[1] - heart[1]) * scale);
            // The way out to it, dotted; the village, a square of its colour.
            dotted(g, cx, cy, px, py, 0x99E9E9E9);
            int colour = c.getLong("raided") >= 0 ? RAIDED : c.getInt("trips") > 0 ? TRADED : KNOWN;
            g.fill(px - 4, py - 4, px + 5, py + 5, colour);
            g.renderOutline(px - 4, py - 4, 9, 9, Ui.EDGE);
            g.fill(px - 1, py - 1, px + 2, py + 2, EMERALD);
            small(g, font, c.getString("name"), px - 10, py + 6, Ui.HI);
            if (Math.abs(mx - px) <= 5 && Math.abs(my - py) <= 5) {
                List<Component> t = new ArrayList<>();
                t.add(Component.literal(c.getString("name") + (c.getString("kind").isEmpty() ? "" : ", a " + c.getString("kind") + " village")));
                t.add(Component.literal(c.getInt("dist") + " blocks " + c.getString("way") + " of the town"));
                t.add(Component.literal(compounds(c, "folk").size() + " villagers in the book, " + c.getInt("trips") + " trips"));
                tip = t;
            }
        }
        small(g, font, "■ traded ■ known ■ raided", x + 3, y + side - 9, Ui.HI);
        return tip;
    }

    private static void dotted(GuiGraphics g, int x0, int y0, int x1, int y1, int colour) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        for (int i = 0; i < 4000; i++) {
            if (i % 5 == 0) g.fill(x0, y0, x0 + 1, y0 + 1, colour);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    private static List<String> wrap(Font font, String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = cur.length() == 0 ? word : cur + " " + word;
            if (font.width(next) > max && cur.length() > 0) {
                out.add(cur.toString());
                cur = new StringBuilder(word);
            } else {
                cur = new StringBuilder(next);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static List<CompoundTag> compounds(CompoundTag m, String key) {
        List<CompoundTag> out = new ArrayList<>();
        ListTag l = m.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) out.add(l.getCompound(i));
        return out;
    }

    private static List<String> strings(CompoundTag m, String key) {
        List<String> out = new ArrayList<>();
        ListTag l = m.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < l.size(); i++) out.add(l.getString(i));
        return out;
    }

    private static void small(GuiGraphics g, Font font, String s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }
}
