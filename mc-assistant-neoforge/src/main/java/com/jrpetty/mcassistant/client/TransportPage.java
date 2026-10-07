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
 * [transport] The Transport page of the town's books (CityScreen), as the server's Transport.report has it. On the
 * left a map, north up, the town in the middle: each railway as it runs (laid, and still to lay), its stations, the
 * mine, the ferry's crossing over its water and the bridge beside it. On the right each line with how far it is laid
 * (and what it waits on), its powered rails and torches, the carts of ore it has brought in, its riders and its
 * mending; where each cart is now; and the ferry (its ferryman, its crossings and fares, whether the weather has
 * stopped it) and the bridge. The mouse over a station gives its line.
 */
public final class TransportPage {

    private TransportPage() {}

    private static final int GROUND = 0xFF6F8F4E, TOWN = 0xFFD8CBA8, LAID = 0xFF3A3A3A, TO_LAY = 0xFFB9B39A, STATION = 0xFFB83227,
        MINE = 0xFF4A4238, WATER = 0xFF2E6FBF, FERRY = 0xFFE9E9E9, BRIDGE = 0xFF9A9A9A;

    /** The page. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        List<CompoundTag> lines = compounds(m, "lines");
        boolean ferry = m.contains("ferry");
        if (lines.isEmpty() && !ferry) {
            Ui.section(g, font, "Getting about", x, y, w);
            int cy = y + 14;
            for (String s : strings(m, "lines_words")) {
                small(g, font, Ui.clip(font, s, (int) ((w - 8) / 0.75F)), x + 4, cy, Ui.MUTED);
                cy += 10;
            }
            small(g, font, "The town is in " + m.getString("age") + ".", x + 4, cy + 4, Ui.FAINT);
            return null;
        }
        int side = Math.max(90, Math.min(h - 4, w * 2 / 5));
        List<Component> tip = map(g, font, m, lines, x, y, side, mx, my);
        int lx = x + side + 8, lw = w - side - 8, max = (int) (lw / 0.75F);
        List<Row> rows = new ArrayList<>();
        if (!lines.isEmpty()) rows.add(new Row('H', "Railways"));
        for (CompoundTag t : lines) {
            int laid = t.getInt("laid"), own = Math.max(1, t.getInt("own"));
            rows.add(new Row('L', t.getString("name") + ": " + t.getInt("length") + " blocks, " + t.getString("state")
                + (t.getLong("opened") >= 0 ? " since day " + t.getLong("opened") : ""), laid, own));
            if (!t.getString("waiting").isEmpty()) rows.add(new Row('W', "Waiting on " + t.getString("waiting") + "."));
            rows.add(new Row('M', t.getInt("powered") + " powered rails, " + t.getInt("torches") + " torches; " + t.getInt("carts")
                + (t.getInt("carts") == 1 ? " cart" : " carts") + " of ore brought in (" + t.getInt("goods") + " goods); " + t.getInt("riders")
                + (t.getInt("riders") == 1 ? " ride" : " rides") + "; " + t.getInt("mended") + " rails mended."));
        }
        List<String> carts = strings(m, "carts");
        if (!carts.isEmpty()) {
            rows.add(new Row('H', "The carts"));
            for (String c : carts) rows.add(new Row('M', c));
        }
        if (ferry) {
            CompoundTag f = m.getCompound("ferry");
            rows.add(new Row('H', "The ferry"));
            rows.add(new Row('L', "To " + f.getString("to") + ", " + f.getInt("width") + " blocks of water: " + f.getString("state")
                + (f.getString("ferryman").isEmpty() ? "" : "; " + f.getString("ferryman") + " the ferryman"), 0, 0));
            if (f.getBoolean("weather")) rows.add(new Row('W', "Stopped for the weather: nobody rows in the rain."));
            rows.add(new Row('M', f.getInt("crossings") + " crossings, " + f.getInt("fares") + " coins in fares (a coin a crossing)"
                + (f.getInt("free") > 0 ? ", " + f.getInt("free") + " carried for nothing" : "")
                + (f.getInt("players") > 0 ? ", " + f.getInt("players") + " travellers from away" : "") + "."));
            if (!f.getString("bridge_words").isEmpty()) {
                rows.add(new Row('H', "The bridge"));
                rows.add(new Row('M', f.getString("bridge_words")));
            }
        }
        int perRow = 10;
        int fit = Math.max(1, (h - 2) / perRow);
        int start = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - fit)));
        int cy = y;
        for (int i = start; i < rows.size() && cy < y + h - 8; i++) {
            Row r = rows.get(i);
            switch (r.kind) {
                case 'H' -> {
                    Ui.section(g, font, r.text, lx, cy, lw);
                    cy += 11;
                }
                case 'L' -> {
                    small(g, font, Ui.clip(font, r.text, max - (r.of > 0 ? 50 : 0)), lx + 2, cy, Ui.INK);
                    if (r.of > 0) {
                        Ui.bar(g, lx + lw - 40, cy + 1, 38, 5, Math.min(1F, r.done / (float) r.of), r.done >= r.of ? Ui.GOOD : 0xFF7A6A2A);
                    }
                    cy += 10;
                }
                case 'W' -> {
                    small(g, font, Ui.clip(font, r.text, max), lx + 6, cy, Ui.WARN);
                    cy += 9;
                }
                default -> {
                    for (String part : wrap(font, r.text, max - 8)) {
                        small(g, font, part, lx + 6, cy, Ui.MUTED);
                        cy += 9;
                    }
                }
            }
        }
        if (rows.size() > fit) small(g, font, "(scroll for more)", lx + lw - 60, y + h - 8, Ui.FAINT);
        return tip;
    }

    private record Row(char kind, String text, int done, int of) {
        Row(char kind, String text) { this(kind, text, 0, 0); }
    }

    /** The map: north up, the town in the middle, the lines, the stations, the mine, the water and the ferry. */
    @Nullable
    private static List<Component> map(GuiGraphics g, Font font, CompoundTag m, List<CompoundTag> lines, int x, int y, int side, int mx, int my) {
        g.fill(x, y, x + side, y + side, GROUND);
        g.renderOutline(x, y, side, side, Ui.EDGE);
        int[] heart = m.getIntArray("heart");
        if (heart.length < 2) return null;
        double far = 60;
        for (CompoundTag t : lines) {
            int[] way = t.getIntArray("way");
            for (int i = 0; i + 1 < way.length; i += 2) far = Math.max(far, Math.max(Math.abs(way[i] - heart[0]), Math.abs(way[i + 1] - heart[1])));
        }
        int[] mine = m.getIntArray("mine");
        if (mine.length == 2) far = Math.max(far, Math.max(Math.abs(mine[0] - heart[0]), Math.abs(mine[1] - heart[1])));
        CompoundTag f = m.contains("ferry") ? m.getCompound("ferry") : null;
        if (f != null) {
            for (String k : new String[]{ "a", "b" }) {
                int[] p = f.getIntArray(k);
                if (p.length == 2) far = Math.max(far, Math.max(Math.abs(p[0] - heart[0]), Math.abs(p[1] - heart[1])));
            }
        }
        double scale = (side / 2.0 - 8) / (far * 1.05);
        int cx = x + side / 2, cy = y + side / 2;
        // The town, its square and its streets out to its edge.
        int town = Math.max(3, (int) (41 * scale));
        g.fill(cx - town, cy - town, cx + town + 1, cy + town + 1, TOWN);
        g.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFFFFFFFF);
        g.renderOutline(cx - 2, cy - 2, 5, 5, Ui.EDGE);
        small(g, font, "N", cx - 2, y + 2, 0xFFE9E9E9);
        if (mine.length == 2) {
            int px = cx + (int) ((mine[0] - heart[0]) * scale), py = cy + (int) ((mine[1] - heart[1]) * scale);
            g.fill(px - 4, py - 4, px + 5, py + 5, MINE);
            small(g, font, "mine", px - 7, py + 6, 0xFFE9E9E9);
        }
        // The ferry's water and crossing, and the bridge.
        if (f != null) {
            int[] a = f.getIntArray("a"), b = f.getIntArray("b");
            if (a.length == 2 && b.length == 2) {
                int ax = cx + (int) ((a[0] - heart[0]) * scale), ay = cy + (int) ((a[1] - heart[1]) * scale);
                int bx = cx + (int) ((b[0] - heart[0]) * scale), by = cy + (int) ((b[1] - heart[1]) * scale);
                boolean alongX = Math.abs(b[0] - a[0]) >= Math.abs(b[1] - a[1]);
                int half = Math.max(4, (int) (10 * scale));
                if (alongX) g.fill(Math.min(ax, bx), Math.min(ay, by) - half, Math.max(ax, bx) + 1, Math.max(ay, by) + half + 1, WATER);
                else g.fill(Math.min(ax, bx) - half, Math.min(ay, by), Math.max(ax, bx) + half + 1, Math.max(ay, by) + 1, WATER);
                boolean retired = f.getString("state").startsWith("retired");
                dashed(g, ax, ay, bx, by, retired ? 0xFF8FB0D8 : FERRY);
                int[] ba = f.getIntArray("ba"), bb = f.getIntArray("bb");
                if (ba.length == 2 && bb.length == 2 && !"NONE".equals(f.getString("bridge")) && !"ASKED".equals(f.getString("bridge"))) {
                    int px = cx + (int) ((ba[0] - heart[0]) * scale), py = cy + (int) ((ba[1] - heart[1]) * scale);
                    int qx = cx + (int) ((bb[0] - heart[0]) * scale), qy = cy + (int) ((bb[1] - heart[1]) * scale);
                    line(g, px, py, qx, qy, BRIDGE, 2);
                }
            }
        }
        // The lines: laid dark, still to lay pale; the stations red.
        List<Component> tip = null;
        for (CompoundTag t : lines) {
            int[] way = t.getIntArray("way");
            int[] head = t.getIntArray("head");
            boolean open = t.getString("state").equals("open");
            for (int i = 0; i + 3 < way.length; i += 2) {
                int px = cx + (int) ((way[i] - heart[0]) * scale), py = cy + (int) ((way[i + 1] - heart[1]) * scale);
                int qx = cx + (int) ((way[i + 2] - heart[0]) * scale), qy = cy + (int) ((way[i + 3] - heart[1]) * scale);
                line(g, px, py, qx, qy, open ? LAID : TO_LAY, 1);
            }
            if (!open && head.length == 2) {
                int hx = cx + (int) ((head[0] - heart[0]) * scale), hy = cy + (int) ((head[1] - heart[1]) * scale);
                g.fill(hx - 1, hy - 1, hx + 2, hy + 2, LAID);
            }
            if (way.length >= 4) {
                int[][] ends = { { way[0], way[1] }, { way[way.length - 2], way[way.length - 1] } };
                for (int[] e : ends) {
                    int px = cx + (int) ((e[0] - heart[0]) * scale), py = cy + (int) ((e[1] - heart[1]) * scale);
                    g.fill(px - 2, py - 2, px + 3, py + 3, STATION);
                    g.renderOutline(px - 2, py - 2, 5, 5, 0xFFE9E9E9);
                    if (Math.abs(mx - px) <= 3 && Math.abs(my - py) <= 3) {
                        tip = List.of(Component.literal(t.getString("name")), Component.literal("a station at " + e[0] + ", " + e[1]));
                    }
                }
            }
        }
        small(g, font, "■ laid ■ to lay ■ station ■ water", x + 3, y + side - 9, 0xFFE9E9E9);
        return tip;
    }

    /** A line of a pixel or two between two points on the map. */
    private static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int colour, int thick) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        for (int guard = 0; guard < 4000; guard++) {
            g.fill(x0, y0, x0 + thick, y0 + thick, colour);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    /** The ferry's way across: a dashed line. */
    private static void dashed(GuiGraphics g, int x0, int y0, int x1, int y1, int colour) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        for (int i = 0; i < 4000; i++) {
            if (i % 4 < 2) g.fill(x0, y0, x0 + 1, y0 + 1, colour);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    /** Words in lines that fit, in the small hand. */
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
