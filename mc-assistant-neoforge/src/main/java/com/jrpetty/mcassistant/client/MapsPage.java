package com.jrpetty.mcassistant.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.MapColor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [cartographer] The Maps page of the town's books (CityScreen), as the server's Cartographers.report has it. On the
 * left the map itself, pixel for pixel as the cartographer drew it: the country round the town (the region's sheet)
 * or, before there is one, the hall's map put together; what nobody has walked near is left the colour of the paper.
 * On it the town's heart and every place the cartographer has found, each by its own mark and colour (the mouse over
 * one says what and where, and who has its map). On the right: who keeps the map room, the hall's map and when it is
 * next due, the country's, the survey under way, the archive of old walls, the commissions, the numbers and the
 * map room's day book.
 */
public final class MapsPage {

    private MapsPage() {}

    private static final int PAPER = 0xFFE4D6B0, PAPER_EDGE = 0xFF8A7650, HEART = 0xFFFFFFFF;

    /** The page. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        int side = Math.max(96, Math.min(h - 4, w * 9 / 20));
        List<Component> tip = picture(g, font, m, x, y, side, mx, my);
        int lx = x + side + 8, lw = w - side - 8, max = (int) (lw / 0.75F) - 6;
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{ "H", m.getString("by").isEmpty() ? "The map room" : "The map room: " + m.getString("by") });
        ListTag lines = m.getList("lines", Tag.TAG_STRING);
        boolean book = false;
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.getString(i);
            if (s.startsWith("Day ") && !book) {
                rows.add(new String[]{ "H", "The map room's day book" });
                book = true;
            }
            rows.add(new String[]{ s.startsWith("Waits on") ? "W" : "M", s });
        }
        ListTag found = m.getList("found", Tag.TAG_COMPOUND);
        if (!found.isEmpty()) {
            rows.add(new String[]{ "H", "Found round the town" });
            int hx = m.getInt("hx"), hz = m.getInt("hz");
            for (int i = found.size() - 1; i >= 0; i--) {
                CompoundTag t = found.getCompound(i);
                int dx = t.getInt("x") - hx, dz = t.getInt("z") - hz;
                rows.add(new String[]{ "F", cap(t.getString("what")) + ", " + (int) Math.sqrt((double) dx * dx + (double) dz * dz) + " blocks "
                    + way(dx, dz) + " (day " + (t.getLong("day") + 1) + "): " + (t.getBoolean("mapped") ? "its map to " : "told ") + t.getString("to"),
                    t.getString("kind") });
            }
        }
        List<String[]> flat = new ArrayList<>();
        for (String[] r : rows) {
            if (r[0].equals("H")) { flat.add(r); continue; }
            for (String part : wrap(font, r[1], max)) flat.add(new String[]{ r[0], part, r.length > 2 ? r[2] : "" });
        }
        int fit = Math.max(1, (h - 2) / 9);
        int start = Math.max(0, Math.min(scroll, Math.max(0, flat.size() - fit)));
        int cy = y;
        for (int i = start; i < flat.size() && cy < y + h - 8; i++) {
            String[] r = flat.get(i);
            switch (r[0]) {
                case "H" -> {
                    if (cy > y) cy += 2;
                    Ui.section(g, font, Ui.clip(font, r[1], lw - 4), lx, cy, lw);
                    cy += 11;
                }
                case "W" -> {
                    small(g, font, r[1], lx + 4, cy, Ui.WARN);
                    cy += 9;
                }
                case "F" -> {
                    if (!r[2].isEmpty()) g.fill(lx + 1, cy + 1, lx + 4, cy + 4, colour(r[2]));
                    small(g, font, r[1], lx + 6, cy, Ui.MUTED);
                    cy += 9;
                }
                default -> {
                    small(g, font, r[1], lx + 4, cy, Ui.MUTED);
                    cy += 9;
                }
            }
        }
        if (flat.size() > fit) small(g, font, "(scroll for more)", lx + lw - 60, y + h - 8, Ui.FAINT);
        return tip;
    }

    /** The map as drawn, the heart and the finds on it. */
    @Nullable
    private static List<Component> picture(GuiGraphics g, Font font, CompoundTag m, int x, int y, int side, int mx, int my) {
        g.fill(x - 2, y - 2, x + side + 2, y + side + 2, PAPER_EDGE);
        g.fill(x, y, x + side, y + side, PAPER);
        byte[] c = m.getByteArray("colours");
        if (c.length != 128 * 128) {
            String why = m.getBoolean("room") ? "No map drawn yet: the cartographer's first walk is to come."
                : m.getBoolean("wanted") ? "The town wants a map room for a cartographer." : "No cartographer yet.";
            int cy = y + side / 2 - 10;
            for (String part : wrap(font, why, (int) ((side - 12) / 0.75F))) {
                small(g, font, part, x + 6, cy, 0xFF5A4A2A);
                cy += 9;
            }
            return null;
        }
        double cell = side / 128.0;
        for (int pz = 0; pz < 128; pz++) {
            int y0 = y + (int) (pz * cell), y1 = y + (int) ((pz + 1) * cell);
            if (y1 <= y0) continue;
            int run = 0;
            int runColour = argb(c[pz * 128] & 0xFF);
            for (int px = 1; px <= 128; px++) {
                int col = px < 128 ? argb(c[px + pz * 128] & 0xFF) : 0;
                if (px < 128 && col == runColour) continue;
                if (runColour != 0) g.fill(x + (int) (run * cell), y0, x + (int) (px * cell), y1, runColour);
                run = px;
                runColour = col;
            }
        }
        int cx = m.getInt("cx"), cz = m.getInt("cz"), span = Math.max(1, m.getInt("span"));
        double k = side / (double) span;
        int hx = x + (int) ((m.getInt("hx") - (cx - span / 2.0)) * k), hy = y + (int) ((m.getInt("hz") - (cz - span / 2.0)) * k);
        g.fill(hx - 2, hy - 2, hx + 3, hy + 3, HEART);
        g.renderOutline(hx - 3, hy - 3, 7, 7, Ui.EDGE);
        List<Component> tip = null;
        if (Math.abs(mx - hx) <= 3 && Math.abs(my - hy) <= 3) tip = List.of(Component.literal("The town's heart"));
        ListTag found = m.getList("found", Tag.TAG_COMPOUND);
        for (int i = 0; i < found.size(); i++) {
            CompoundTag t = found.getCompound(i);
            int px = x + (int) ((t.getInt("x") - (cx - span / 2.0)) * k), py = y + (int) ((t.getInt("z") - (cz - span / 2.0)) * k);
            if (px < x + 2 || py < y + 2 || px > x + side - 3 || py > y + side - 3) continue;
            int col = colour(t.getString("kind"));
            g.fill(px - 2, py - 2, px + 3, py + 3, col);
            g.renderOutline(px - 2, py - 2, 5, 5, 0xFF1A1A1A);
            if (Math.abs(mx - px) <= 3 && Math.abs(my - py) <= 3) {
                tip = List.of(Component.literal(cap(t.getString("what"))), Component.literal("at " + t.getInt("x") + ", " + t.getInt("z")
                    + ", found day " + (t.getLong("day") + 1)), Component.literal((t.getBoolean("mapped") ? "its map to " : "told ") + t.getString("to")));
            }
        }
        small(g, font, "N", x + side / 2 - 2, y + 2, 0xFF3A2E1A);
        small(g, font, Ui.clip(font, m.getString("title") + " (" + span + " blocks across)", (int) ((side - 6) / 0.75F)), x + 3, y + side - 9,
            0xFF3A2E1A);
        return tip;
    }

    /** A map pixel's colour (the game's packed map colour), as the screen draws it; 0 for paper. */
    private static int argb(int packed) {
        if (packed == 0 || packed >> 2 == 0) return 0;
        int abgr = MapColor.getColorFromPackedId(packed);
        int r = abgr & 0xFF, gr = (abgr >> 8) & 0xFF, b = (abgr >> 16) & 0xFF;
        return 0xFF000000 | r << 16 | gr << 8 | b;
    }

    /** A place's mark: the cave team's red, the scouts' green, the sea's blue, the Nether's purple, treasure gold. */
    private static int colour(String kind) {
        return switch (kind) {
            case "MINESHAFT", "DUNGEON", "TRIAL_CHAMBERS", "ANCIENT_CITY", "STRONGHOLD" -> 0xFFB83227;
            case "VILLAGE", "OUTPOST", "DESERT_TEMPLE", "JUNGLE_TEMPLE", "SWAMP_HUT", "MANSION" -> 0xFF23803A;
            case "MONUMENT" -> 0xFF2E6FBF;
            case "RUINED_PORTAL" -> 0xFF7D3C98;
            case "TREASURE" -> 0xFFE0B020;
            default -> 0xFF5A5A5A;
        };
    }

    private static String way(int dx, int dz) {
        double a = Math.toDegrees(Math.atan2(dx, -dz));
        String[] w = { "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west" };
        return w[(int) Math.floorMod(Math.round(a / 45.0), 8L)];
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
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

    private static void small(GuiGraphics g, Font font, String s, int x, int y, int colour) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, s, 0, 0, colour, false);
        g.pose().popPose();
    }
}
