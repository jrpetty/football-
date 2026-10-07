package com.jrpetty.mcassistant.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * [caves] The Caves page of the town's books (CityScreen), as the server's CaveDwellers.report has it. On the left a
 * map, north up, the town in the middle, the cave team's range ringed: every cave and ravine, the ore veins, the
 * mineshafts, the dungeons and spawners, the old structures and the lava where they lie, and the team out today. On
 * the right, scrolled as one: the team (each one's level, the leader marked, its card), the trip under way or the last
 * one's plan in words with the reckoning it was planned by, the report in a line and the torches drawn and set, each
 * cave with its list of veins (mined, waiting for a better pick, still to do), the finds with their coordinates (the
 * big ones first), and the hauls brought home. The mouse over a mark on the map gives the find.
 */
public final class CavesPage {

    private CavesPage() {}

    private static final int CAVE = 0xFF6E4B2A, VEIN = 0xFF2E6FBF, DIAMOND = 0xFF16A0A0, SHAFT = 0xFFB7791F, SPAWNER = 0xFFB83227,
        STRUCTURE = 0xFF7D3C98, LAVA = 0xFFE0571B, FOLK = 0xFF23803A, CHEST = 0xFF8A6A2A;

    /** A row of the right-hand column: a heading, or a line of text (with a coloured mark before it, or none). */
    private record Row(String text, int colour, int mark, boolean heading) {}

    /** The page. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        List<CompoundTag> finds = compounds(m, "finds"), folk = compounds(m, "dwellers"), caves = compounds(m, "caves");
        ListTag hauls = m.getList("hauls", Tag.TAG_STRING);
        if (finds.isEmpty() && folk.isEmpty()) {
            Ui.section(g, font, "The caves", x, y, w);
            small(g, font, "Nobody goes down the caves for the town yet.", x + 4, y + 14, Ui.MUTED);
            int cy = y + 24;
            for (String line : wrap(font, "An Iron Age town of " + m.getInt("from") + " folk or more, with a few miners and a watch, picks a small team of"
                + " its most skilled hands (two, three at sixty folk, four at a hundred) to go down the caves round it: armed, in the town's"
                + " armour, with its torches. They seek out every vein, mine what their picks allow, look in the old chests of the mineshafts,"
                + " dungeons and temples, and report it all here.", w - 8)) {
                small(g, font, line, x + 4, cy, Ui.FAINT);
                cy += 9;
            }
            small(g, font, "The town is in " + m.getString("age") + ".", x + 4, cy + 2, Ui.FAINT);
            return null;
        }
        int side = Math.max(90, Math.min(h - 4, w * 2 / 5));
        List<Component> tip = map(g, font, m, finds, folk, x, y, side, mx, my);
        int lx = x + side + 8, lw = w - side - 8;
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("The cave team: " + folk.size() + " of " + m.getInt("wanted") + " wanted, out to " + m.getInt("range") + " blocks", 0, 0, true));
        String trip = m.getString("trip");
        if (!trip.isEmpty()) rows.add(new Row(capital(trip), Ui.GOOD, 0, false));
        for (CompoundTag f : folk) {
            String head = f.getString("name") + " (level " + f.getInt("level") + (f.getBoolean("leader") ? ", leads" : "") + ")" + (f.getBoolean("out") ? ", out" : "");
            for (String line : wrap(font, head + ": " + f.getString("card"), lw - 4)) rows.add(new Row(line, f.getBoolean("out") ? Ui.GOOD : Ui.INK, 0, false));
        }
        String plan = m.getString("plan");
        if (!plan.isEmpty()) {
            rows.add(new Row(trip.isEmpty() ? "The last trip's plan" : "The plan", 0, 0, true));
            for (String line : wrap(font, plan, lw - 4)) rows.add(new Row(line, Ui.INK, 0, false));
            ListTag reck = m.getList("reckoning", Tag.TAG_STRING);
            for (int i = 0; i < reck.size(); i++) for (String line : wrap(font, "  " + reck.getString(i), lw - 4)) rows.add(new Row(line, Ui.FAINT, 0, false));
        }
        rows.add(new Row("The report", 0, 0, true));
        for (String line : wrap(font, "Found: " + m.getString("summary"), lw - 4)) rows.add(new Row(line, Ui.MUTED, 0, false));
        if (m.getInt("torchesDrawn") + m.getInt("torchesSet") > 0) {
            for (String line : wrap(font, "Torches, the last ten trips: " + m.getInt("torchesDrawn") + " drawn from the stores, " + m.getInt("torchesSet")
                + " set", lw - 4)) rows.add(new Row(line, Ui.MUTED, 0, false));
        }
        // Each cave with its veins: mined, waiting for a better pick, still to do.
        if (!caves.isEmpty()) {
            rows.add(new Row("The caves and their veins", 0, 0, true));
            for (CompoundTag c : caves) {
                for (String line : wrap(font, capital(c.getString("label")), lw - 12)) rows.add(new Row(line, Ui.INK, CAVE, false));
                ListTag vl = c.getList("veins", Tag.TAG_STRING);
                if (vl.isEmpty()) {
                    rows.add(new Row("    no veins listed yet", Ui.FAINT, 0, false));
                    continue;
                }
                rows.add(new Row("    " + c.getInt("mined") + " mined, " + c.getInt("waiting") + " waiting for a better pick, " + c.getInt("todo") + " to do",
                    Ui.MUTED, 0, false));
                for (int i = 0; i < vl.size(); i++) {
                    String v = vl.getString(i);
                    int col = v.endsWith("mined") ? Ui.GOOD : v.contains("waiting") ? Ui.WARN : v.contains("left") ? Ui.FAINT : Ui.INK;
                    rows.add(new Row("    " + v, col, 0, false));
                }
            }
        }
        // The finds: the big ones first, then the nearest.
        List<CompoundTag> list = new ArrayList<>(finds);
        list.sort(Comparator.comparingInt(CavesPage::rank).thenComparingDouble(t -> Math.hypot(t.getInt("dx"), t.getInt("dz"))));
        if (!list.isEmpty()) {
            rows.add(new Row("What they found", 0, 0, true));
            for (CompoundTag t : list) rows.add(new Row(line(t), Ui.INK, colour(t), false));
        }
        if (!hauls.isEmpty()) {
            rows.add(new Row("The hauls brought home", 0, 0, true));
            for (int i = 0; i < hauls.size(); i++) for (String line : wrap(font, hauls.getString(i), lw - 4)) rows.add(new Row(line, Ui.MUTED, 0, false));
        }
        // Scrolled as one, headings and all.
        int start = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - 6)));
        int cy = y;
        for (int i = start; i < rows.size(); i++) {
            Row r = rows.get(i);
            int tall = r.heading() ? 11 : 9;
            if (cy + tall > y + h - (i < rows.size() - 1 ? 9 : 0)) {
                small(g, font, "scroll for more (" + (rows.size() - i) + " lines)", lx + 2, y + h - 8, Ui.FAINT);
                break;
            }
            if (r.heading()) {
                Ui.section(g, font, r.text(), lx, cy, lw);
            } else {
                int tx = lx + 2;
                if (r.mark() != 0) {
                    g.fill(lx + 2, cy + 1, lx + 6, cy + 5, r.mark());
                    tx = lx + 9;
                }
                small(g, font, Ui.clip(font, r.text(), (int) ((lx + lw - tx) / 0.75F)), tx, cy, r.colour());
            }
            cy += tall;
        }
        return tip;
    }

    /** Text broken into lines that fit so wide (at the page's small print). */
    private static List<String> wrap(Font font, String text, int width) {
        List<String> out = new ArrayList<>();
        int max = (int) (width / 0.75F);
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (font.width(next) > max && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder("  " + word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /** One find in a line: "Iron vein (5 seen, 4 mined) at 120 64 -40, 70 east". */
    private static String line(CompoundTag t) {
        String kind = t.getString("kind");
        String label = t.getString("label");
        String what = switch (kind) {
            case "VEIN" -> capital(label) + " vein (" + t.getInt("a") + " seen, " + t.getInt("b") + " mined)";
            case "CAVE", "RAVINE" -> capital(label) + ", " + t.getInt("a") + " deep, " + t.getInt("b") + " open";
            case "CHEST" -> capital(label) + ": " + t.getInt("a") + " things taken";
            case "MINESHAFT", "STRUCTURE" -> capital(label) + (t.getInt("a") > 0 ? ", " + t.getInt("a") + " chests looked in" : "");
            case "LAVA" -> capital(label) + ", " + t.getInt("a") + " deep";
            default -> capital(label);
        };
        int dx = t.getInt("dx"), dz = t.getInt("dz");
        return what + " at " + t.getInt("x") + " " + t.getInt("y") + " " + t.getInt("z") + ", " + (int) Math.hypot(dx, dz) / 10 * 10 + " " + direction(dx, dz);
    }

    private static int rank(CompoundTag t) {
        return switch (t.getString("kind")) {
            case "MINESHAFT", "DUNGEON", "STRUCTURE" -> 0;
            case "VEIN" -> "diamond".equals(t.getString("label")) || "emerald".equals(t.getString("label")) ? 0 : 2;
            case "SPAWNER", "CAVE", "RAVINE" -> 1;
            case "LAVA" -> 3;
            default -> 4;
        };
    }

    private static int colour(CompoundTag t) {
        return switch (t.getString("kind")) {
            case "CAVE", "RAVINE" -> CAVE;
            case "VEIN" -> "diamond".equals(t.getString("label")) || "emerald".equals(t.getString("label")) ? DIAMOND : VEIN;
            case "MINESHAFT" -> SHAFT;
            case "DUNGEON", "SPAWNER" -> SPAWNER;
            case "STRUCTURE" -> STRUCTURE;
            case "LAVA" -> LAVA;
            default -> CHEST;
        };
    }

    /** The map: north up, the town in the middle, every find where it lies. */
    @Nullable
    private static List<Component> map(GuiGraphics g, Font font, CompoundTag m, List<CompoundTag> finds, List<CompoundTag> folk,
                                       int x, int y, int side, int mx, int my) {
        g.fill(x, y, x + side, y + side, 0xFF4A4238);                  // the dark under the ground
        g.renderOutline(x, y, side, side, Ui.EDGE);
        double far = Math.max(60, m.getInt("range"));
        for (CompoundTag t : finds) far = Math.max(far, Math.hypot(t.getInt("dx"), t.getInt("dz")));
        double scale = (side / 2.0 - 10) / (far * 1.05);
        int cx = x + side / 2, cy = y + side / 2;
        // Rings every fifty blocks, dotted; the range a little brighter.
        for (int ring = 50; ring <= far * 1.05; ring += 50) {
            int rr = (int) (ring * scale);
            int dot = ring == m.getInt("range") ? 0xFFC8B98A : 0xFF7A6E5A;
            for (int a = 0; a < 72; a++) {
                int px = cx + (int) Math.round(Math.cos(Math.toRadians(a * 5)) * rr), py = cy + (int) Math.round(Math.sin(Math.toRadians(a * 5)) * rr);
                if (px > x && px < x + side - 1 && py > y && py < y + side - 1) g.fill(px, py, px + 1, py + 1, dot);
            }
            small(g, font, ring + "", cx + rr + 1, cy + 1, 0xFFB0A488);
        }
        small(g, font, "N", cx - 2, y + 2, 0xFFE0D8C0);
        List<Component> tip = null;
        for (CompoundTag t : finds) {
            if ("CHEST".equals(t.getString("kind"))) continue;
            int px = cx + (int) (t.getInt("dx") * scale), py = cy + (int) (t.getInt("dz") * scale);
            int col = colour(t);
            int r = "CAVE".equals(t.getString("kind")) || "RAVINE".equals(t.getString("kind")) ? 2 : 1;
            g.fill(px - r, py - r, px + r + 1, py + r + 1, col);
            if (Math.abs(mx - px) <= 3 && Math.abs(my - py) <= 3) {
                tip = List.of(Component.literal(line(t)), Component.literal("found day " + (t.getLong("day") + 1) + " by " + t.getString("by")));
            }
        }
        for (CompoundTag f : folk) {
            if (!f.getBoolean("out")) continue;
            int px = cx + (int) (f.getInt("dx") * scale), py = cy + (int) (f.getInt("dz") * scale);
            g.fill(px - 2, py - 2, px + 3, py + 3, FOLK);
            g.renderOutline(px - 2, py - 2, 5, 5, 0xFFE9E9E9);
            if (Math.abs(mx - px) <= 3 && Math.abs(my - py) <= 3) tip = List.of(Component.literal(f.getString("name") + ": " + f.getString("card")));
        }
        // The town.
        g.fill(cx - 3, cy - 3, cx + 4, cy + 4, 0xFFE9E9E9);
        g.renderOutline(cx - 3, cy - 3, 7, 7, Ui.EDGE);
        small(g, font, "■ caves ■ ore ■ gems ■ shafts ■ spawners ■ ruins ■ lava", x + 3, y + side - 9, 0xFFE0D8C0);
        return tip;
    }

    private static String direction(int dx, int dz) {
        double ang = Math.toDegrees(Math.atan2(dx, -dz));
        String[] names = { "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west" };
        return names[(int) Math.floorMod(Math.round(ang / 45.0), 8L)];
    }

    private static List<CompoundTag> compounds(CompoundTag m, String key) {
        List<CompoundTag> out = new ArrayList<>();
        ListTag l = m.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) out.add(l.getCompound(i));
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
