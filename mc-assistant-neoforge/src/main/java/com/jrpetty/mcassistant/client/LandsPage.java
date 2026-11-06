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
 * [borders-map] The Lands page of the town's books (CityScreen), as the server's Borders.report has it. On the left, the
 * country round the town, a square a chunk, each in the colour of the town whose land it is (ours ringed in gold, the
 * contested striped), the town's heart in the middle; the mouse over a chunk says whose. On the right: how much land,
 * and why that much; the neighbours along the border and how we stand with each; the disputes, open and settled; the
 * border banners and stones; the map table; what has changed.
 */
public final class LandsPage {

    private LandsPage() {}

    private static final int PAPER = 0xFFE4D6B0, GOLD = 0xFFC9A227;

    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        if (!m.getBoolean("on")) {
            Ui.section(g, font, "The town's lands", x, y, w);
            small(g, font, "The towns' borders are off on this server (townBorders in the config).", x + 4, y + 14, Ui.MUTED);
            return null;
        }
        int side = Math.max(82, Math.min(h - 4, w * 2 / 5));
        List<Component> tip = grid(g, font, m, x, y, side, mx, my);
        int lx = x + side + 8, lw = w - side - 8, max = (int) (lw / 0.75F) - 6;
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{ "H", "Our lands: " + m.getInt("area") + " chunks" + (m.getInt("contested") > 0 ? ", " + m.getInt("contested") + " of them contested" : "") });
        rows.add(new String[]{ "M", capital(m.getString("why")) + "." });
        ListTag ns = m.getList("neighbours", Tag.TAG_COMPOUND);
        rows.add(new String[]{ "H", "Along the border" });
        if (ns.isEmpty()) rows.add(new String[]{ "M", "Our lands meet nobody's yet: the wilds all round." });
        for (int i = 0; i < ns.size(); i++) {
            CompoundTag n = ns.getCompound(i);
            String s = n.getString("name") + ": " + n.getInt("edge") + " chunks of border; " + n.getString("terms")
                + (n.getBoolean("war") ? ", AT WAR" : "") + (n.getBoolean("agreed") ? "; the line agreed" : "; no line agreed")
                + (n.getInt("contested") > 0 ? "; " + n.getInt("contested") + " chunks both claim" : "");
            rows.add(new String[]{ n.getBoolean("war") || n.getInt("contested") > 0 ? "W" : "N", s });
        }
        ListTag ds = m.getList("disputes", Tag.TAG_COMPOUND);
        rows.add(new String[]{ "H", "Disputes" });
        if (ds.isEmpty()) rows.add(new String[]{ "M", "None. Where two towns' claims overlap, or one digs over the line, they quarrel over it." });
        for (int i = 0; i < ds.size(); i++) {
            CompoundTag d = ds.getCompound(i);
            String s = (d.getBoolean("ours") ? "We claim " : d.getString("with") + " claims ") + d.getString("subject") + " (with "
                + d.getString("with") + ", since day " + d.getLong("began") + "): " + d.getString("state")
                + (d.getString("outcome").isEmpty() ? "" : " — " + d.getString("outcome"));
            rows.add(new String[]{ d.getBoolean("live") ? "W" : "G", s });
        }
        CompoundTag mk = m.getCompound("markers");
        rows.add(new String[]{ "H", "Border markers" });
        rows.add(new String[]{ "N", mk.getInt("banners") + " banners and " + mk.getInt("stones") + " road stones stand"
            + (mk.getInt("again") > 0 ? "; " + mk.getInt("again") + " planted again after they were knocked down" : "")
            + (mk.getInt("waiting") > 0 ? "; " + mk.getInt("waiting") + " more wanted" : "") + "." });
        if (!mk.getString("short").isEmpty()) rows.add(new String[]{ "W", "The next waits on " + mk.getString("short") + "." });
        ListTag where = mk.getList("where", Tag.TAG_STRING);
        for (int i = 0; i < where.size(); i++) rows.add(new String[]{ "M", capital(where.getString(i)) });
        if (!m.getString("table").isEmpty()) {
            rows.add(new String[]{ "H", "The war room" });
            rows.add(new String[]{ "N", m.getString("table") });
        }
        ListTag ch = m.getList("changes", Tag.TAG_STRING);
        if (!ch.isEmpty()) {
            rows.add(new String[]{ "H", "Recent changes" });
            for (int i = 0; i < ch.size(); i++) rows.add(new String[]{ "M", capital(ch.getString(i)) });
        }
        // Each row as it is drawn: a heading, or a line of wrapped text and its kind.
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

    /** The little map: each chunk its owner's colour, ours ringed, the contested striped; the heart in the middle. */
    @Nullable
    private static List<Component> grid(GuiGraphics g, Font font, CompoundTag m, int x, int y, int side, int mx, int my) {
        g.fill(x, y, x + side, y + side, PAPER);
        g.renderOutline(x, y, side, side, Ui.EDGE);
        byte[] grid = m.getByteArray("grid");
        int n = m.getInt("side");
        ListTag who = m.getList("who", Tag.TAG_COMPOUND);
        if (n <= 0 || grid.length < n * n) return null;
        float cell = (side - 2) / (float) n;
        List<Component> tip = null;
        for (int z = 0; z < n; z++) {
            for (int xx = 0; xx < n; xx++) {
                int v = grid[z * n + xx] & 0xFF;
                if (v == 0) continue;
                boolean contested = v >= 64;
                int idx = (v & 63) - 1;
                if (idx < 0 || idx >= who.size()) continue;
                CompoundTag t = who.getCompound(idx);
                int colour = 0xFF000000 | t.getInt("colour");
                int x0 = x + 1 + (int) (xx * cell), y0 = y + 1 + (int) (z * cell), x1 = x + 1 + (int) ((xx + 1) * cell), y1 = y + 1 + (int) ((z + 1) * cell);
                g.fill(x0, y0, Math.max(x0 + 1, x1), Math.max(y0 + 1, y1), t.getBoolean("us") ? Ui.lighten(colour, 0.35F) : Ui.lighten(colour, 0.55F));
                if (contested && ((xx + z) & 1) == 0) g.fill(x0, y0, Math.max(x0 + 1, x1), Math.max(y0 + 1, y1), GOLD);
                if (mx >= x0 && mx < x1 && my >= y0 && my < y1) {
                    tip = List.of(Component.literal((t.getBoolean("us") ? "Our land" : t.getString("name") + "'s land") + (contested ? " (contested)" : "")),
                        Component.literal((xx - n / 2) + ", " + (z - n / 2) + " chunks from our heart"));
                }
            }
        }
        int c = x + 1 + (int) ((n / 2 + 0.5F) * cell), cz = y + 1 + (int) ((n / 2 + 0.5F) * cell);
        g.fill(c - 2, cz - 2, c + 3, cz + 3, 0xFF000000);
        g.fill(c - 1, cz - 1, c + 2, cz + 2, 0xFFFFFFFF);
        small(g, font, "N", x + side / 2 - 2, y + 2, Ui.FAINT);
        return tip;
    }

    private static void small(GuiGraphics g, Font font, String s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : s.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + s.substring(1);
    }
}
