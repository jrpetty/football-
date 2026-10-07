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
 * map, north up, the town in the middle, the cave dwellers' range ringed: every cave and ravine, the ore veins, the
 * mineshafts, the dungeons and spawners, the old structures and the lava where they lie, and the cave dwellers out
 * today. On the right the cave dwellers and their cards, the report in a line, the finds with their coordinates (the
 * big ones first, and the scroll wheel for the rest), and the hauls brought home. The mouse over a mark on the map
 * gives the find.
 */
public final class CavesPage {

    private CavesPage() {}

    private static final int CAVE = 0xFF6E4B2A, VEIN = 0xFF2E6FBF, DIAMOND = 0xFF16A0A0, SHAFT = 0xFFB7791F, SPAWNER = 0xFFB83227,
        STRUCTURE = 0xFF7D3C98, LAVA = 0xFFE0571B, FOLK = 0xFF23803A, CHEST = 0xFF8A6A2A;

    /** The page. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        List<CompoundTag> finds = compounds(m, "finds"), folk = compounds(m, "dwellers");
        ListTag hauls = m.getList("hauls", Tag.TAG_STRING);
        if (finds.isEmpty() && folk.isEmpty()) {
            Ui.section(g, font, "The caves", x, y, w);
            small(g, font, "Nobody goes down the caves for the town yet.", x + 4, y + 14, Ui.MUTED);
            small(g, font, Ui.clip(font, "An Iron Age town of " + m.getInt("from") + " folk or more, with a few miners and a watch, sends a cave dweller"
                + " into the caves round it: armed and in the town's armour, it mines the ore its pick allows, looks in the old chests"
                + " of the mineshafts, dungeons and temples, and reports what it finds here.", (int) ((w - 8) / 0.75F) * 2), x + 4, y + 24, Ui.FAINT);
            small(g, font, "The town is in " + m.getString("age") + ".", x + 4, y + 34, Ui.FAINT);
            return null;
        }
        int side = Math.max(90, Math.min(h - 4, w * 2 / 5));
        List<Component> tip = map(g, font, m, finds, folk, x, y, side, mx, my);
        int lx = x + side + 8, lw = w - side - 8, max = (int) (lw / 0.75F);
        int cy = y;
        Ui.section(g, font, "The caves — " + folk.size() + (folk.size() == 1 ? " cave dweller" : " cave dwellers") + ", " + m.getInt("wanted")
            + " wanted, out to " + m.getInt("range") + " blocks", lx, cy, lw);
        cy += 11;
        for (CompoundTag f : folk) {
            if (cy > y + h - 60) break;
            small(g, font, Ui.clip(font, f.getString("name") + (f.getBoolean("out") ? " (out)" : "") + ": " + f.getString("card"), max), lx + 2, cy,
                f.getBoolean("out") ? Ui.GOOD : Ui.INK);
            cy += 9;
        }
        small(g, font, Ui.clip(font, "Found: " + m.getString("summary"), max), lx + 2, cy, Ui.MUTED);
        cy += 12;
        // The finds: the big ones first, then the nearest.
        List<CompoundTag> list = new ArrayList<>(finds);
        list.sort(Comparator.comparingInt(CavesPage::rank).thenComparingDouble(t -> Math.hypot(t.getInt("dx"), t.getInt("dz"))));
        int haulRows = Math.min(4, hauls.size());
        int rows = Math.max(1, (y + h - cy - 14 - (haulRows > 0 ? 11 + haulRows * 9 : 0)) / 9);
        int start = Math.max(0, Math.min(scroll, Math.max(0, list.size() - rows)));
        Ui.section(g, font, "What they found" + (list.size() > rows ? " (" + (start + 1) + "-" + Math.min(list.size(), start + rows) + " of " + list.size()
            + ", scroll for more)" : ""), lx, cy, lw);
        cy += 11;
        for (int i = start; i < list.size() && i < start + rows; i++) {
            CompoundTag t = list.get(i);
            int col = colour(t);
            g.fill(lx + 2, cy + 1, lx + 6, cy + 5, col);
            small(g, font, Ui.clip(font, line(t), max - 10), lx + 9, cy, Ui.INK);
            cy += 9;
        }
        if (haulRows > 0) {
            cy = Math.max(cy + 2, y + h - 11 - haulRows * 9);
            Ui.section(g, font, "The hauls brought home", lx, cy, lw);
            cy += 11;
            for (int i = 0; i < haulRows; i++) {
                small(g, font, Ui.clip(font, hauls.getString(i), max), lx + 2, cy, Ui.MUTED);
                cy += 9;
            }
        }
        return tip;
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
