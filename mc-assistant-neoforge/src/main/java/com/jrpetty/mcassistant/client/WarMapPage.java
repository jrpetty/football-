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
import java.util.Locale;

/**
 * [war-scouting] The War map page of the town's books (CityScreen), as the server's WarMap.report has it.
 * On the left the map itself, north up, our town in the middle: every town we are at war or at odds with
 * where it lies (red with a report, amber on rumour alone), its last-known guards and how old the report
 * is; where folk of theirs were last seen on our approaches; our pickets on the roads and our spies out.
 * On the right, a card for each: the report, what the leader believes now, the reckoning and its balance,
 * and how many guards we want against it. The mouse over a town on the map gives its whole card.
 */
public final class WarMapPage {

    private WarMapPage() {}

    private static final int RED = 0xFFB83227, AMBER = 0xFFB7791F, GREEN = 0xFF23803A, BLUE = 0xFF2E6FBF, PURPLE = 0xFF7D3C98;

    /** The page. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int mx, int my) {
        List<CompoundTag> rivals = compounds(m, "rivals"), seen = compounds(m, "seen"), pickets = compounds(m, "pickets"),
            spies = compounds(m, "spies");
        String footing = m.getString("footing");
        if (rivals.isEmpty()) {
            Ui.section(g, font, "The war map", x, y, w);
            small(g, font, "At peace, and at odds with nobody: no enemy to watch, no pickets on the roads.", x + 4, y + 14, Ui.MUTED);
            small(g, font, "When a neighbour falls into a feud with us, or war comes, the scouts' reports on it show here.", x + 4, y + 24, Ui.FAINT);
            return null;
        }
        int side = Math.max(90, Math.min(h - 4, w * 2 / 5));
        int mapX = x, mapY = y;
        List<Component> tip = drawMap(g, font, m, rivals, seen, pickets, spies, mapX, mapY, side, mx, my);
        int lx = x + side + 8, lw = w - side - 8;
        int cy = y;
        CompoundTag ours = m.getCompound("ours");
        Ui.section(g, font, ("WAR".equals(footing) ? "At war" : "On our guard") + " — the leader is " + m.getString("temper"), lx, cy, lw);
        cy += 11;
        small(g, font, Ui.clip(font, "Ours (counted): " + ours.getInt("guards") + " guards, " + ours.getInt("armoured") + " in iron, "
            + ours.getInt("archers") + " with bows; wall " + ours.getInt("walls") + ", gates " + ours.getInt("gates") + "; food "
            + ours.getInt("food") + " days" + (ours.getInt("allies") > 0 ? "; " + ours.getInt("allies") + " allies" : ""), (int) (lw / 0.75F)), lx, cy, Ui.INK);
        cy += 9;
        int wanted = m.getInt("wanted");
        small(g, font, "Guards wanted against them as we believe them: " + wanted + " (we have " + ours.getInt("guards") + ")", lx, cy,
            wanted > ours.getInt("guards") ? Ui.BAD : Ui.GOOD);
        cy += 12;
        for (CompoundTag r : rivals) {
            if (cy > y + h - 40) break;
            cy = card(g, font, r, lx, cy, lw);
        }
        List<String> rest = new ArrayList<>();
        for (CompoundTag p : pickets) rest.add("Picket: " + p.getString("name") + " on " + p.getString("road") + (p.getBoolean("running") ? " — running home with word!" : ""));
        for (CompoundTag s : spies) rest.add("Out: " + s.getString("name") + ", " + s.getString("doing"));
        ListTag caps = m.getList("captives", Tag.TAG_STRING);
        for (int i = 0; i < caps.size(); i++) rest.add(caps.getString(i));
        if (!rest.isEmpty() && cy < y + h - 12) {
            Ui.section(g, font, "Pickets, spies, captives", lx, cy, lw);
            cy += 11;
            for (String s : rest) {
                if (cy > y + h - 8) break;
                small(g, font, Ui.clip(font, s, (int) (lw / 0.75F)), lx + 4, cy, Ui.MUTED);
                cy += 9;
            }
        }
        return tip;
    }

    /** One rival's card: its name and standing, the report and its age, the belief, the reckoning with its balance. */
    private static int card(GuiGraphics g, Font font, CompoundTag r, int x, int y, int w) {
        int max = (int) (w / 0.75F);
        boolean war = r.getBoolean("war");
        g.fill(x, y, x + 2, y + 44, war ? RED : AMBER);
        g.drawString(font, Ui.clip(font, r.getString("name") + " — " + r.getString("standing"), w - 6), x + 5, y, Ui.INK, false);
        int cy = y + 10;
        if (r.getBoolean("report")) {
            boolean stale = r.getBoolean("stale");
            small(g, font, Ui.clip(font, "Report (" + r.getString("age_words") + "): " + r.getInt("r_guards") + " guards, " + r.getInt("r_armoured")
                + " in iron, " + r.getInt("r_archers") + " bows; wall " + r.getInt("r_walls") + ", gates " + r.getInt("r_gates") + "; food "
                + r.getInt("r_food") + "d; " + r.getInt("r_folk") + " folk", max - 8), x + 5, cy, stale ? Ui.WARN : Ui.INK);
        } else {
            small(g, font, "No report: nobody has been to look. Rumour only.", x + 5, cy, Ui.WARN);
        }
        cy += 9;
        small(g, font, Ui.clip(font, "The leader believes " + r.getInt("e_guards") + " guards (" + r.getInt("e_armoured") + " in iron, "
            + r.getInt("e_archers") + " bows), from " + r.getString("basis") + ", " + r.getInt("sure") + "% sure", max - 8), x + 5, cy, Ui.MUTED);
        cy += 9;
        small(g, font, Ui.clip(font, capital(r.getString("words")), max - 8), x + 5, cy, Ui.MUTED);
        cy += 9;
        float bal = r.getInt("balance") / 100F;
        int barW = Math.min(120, w - 90);
        Ui.bar(g, x + 5, cy + 1, barW, 5, bal / 2F, bal >= 1.1F ? GREEN : bal < 0.9F ? RED : AMBER);
        g.fill(x + 5 + barW / 2, cy - 1, x + 6 + barW / 2, cy + 8, Ui.EDGE);           // even, in the middle
        small(g, font, String.format(Locale.ROOT, "balance %.2f · want %d guards", bal, r.getInt("wanted")), x + 10 + barW, cy, Ui.FAINT);
        return cy + 13;
    }

    /** The map: north up, our town in the middle, everything else where it lies. */
    @Nullable
    private static List<Component> drawMap(GuiGraphics g, Font font, CompoundTag m, List<CompoundTag> rivals, List<CompoundTag> seen,
                                           List<CompoundTag> pickets, List<CompoundTag> spies, int x, int y, int side, int mx, int my) {
        g.fill(x, y, x + side, y + side, 0xFFD9D2B8);                  // parchment
        g.renderOutline(x, y, side, side, Ui.EDGE);
        double far = 60;
        for (CompoundTag r : rivals) far = Math.max(far, Math.hypot(r.getInt("dx"), r.getInt("dz")));
        for (CompoundTag s : seen) far = Math.max(far, Math.hypot(s.getInt("dx"), s.getInt("dz")));
        double scale = (side / 2.0 - 14) / (far * 1.05);
        int cx = x + side / 2, cy = y + side / 2;
        // Rings every hundred blocks, dotted.
        for (int ring = 100; ring <= far * 1.05; ring += 100) {
            int rr = (int) (ring * scale);
            for (int a = 0; a < 72; a++) {
                int px = cx + (int) Math.round(Math.cos(Math.toRadians(a * 5)) * rr), py = cy + (int) Math.round(Math.sin(Math.toRadians(a * 5)) * rr);
                if (px > x && px < x + side - 1 && py > y && py < y + side - 1) g.fill(px, py, px + 1, py + 1, 0xFF9E957A);
            }
            small(g, font, ring + "", cx + rr + 1, cy + 1, 0xFF7A715A);
        }
        small(g, font, "N", cx - 2, y + 2, Ui.FAINT);
        // Our pickets (blue), our spies out (purple), folk of theirs seen (red crosses).
        for (CompoundTag p : pickets) {
            int px = cx + (int) (p.getInt("dx") * scale), py = cy + (int) (p.getInt("dz") * scale);
            g.fill(px - 1, py - 1, px + 2, py + 2, BLUE);
        }
        for (CompoundTag s : spies) {
            int px = cx + (int) (s.getInt("dx") * scale), py = cy + (int) (s.getInt("dz") * scale);
            g.fill(px - 1, py - 1, px + 2, py + 2, PURPLE);
        }
        List<Component> tip = null;
        for (CompoundTag s : seen) {
            int px = cx + (int) (s.getInt("dx") * scale), py = cy + (int) (s.getInt("dz") * scale);
            for (int d = -2; d <= 2; d++) {
                g.fill(px + d, py + d, px + d + 1, py + d + 1, RED);
                g.fill(px + d, py - d, px + d + 1, py - d + 1, RED);
            }
            if (Math.abs(mx - px) <= 3 && Math.abs(my - py) <= 3) {
                tip = List.of(Component.literal(s.getString("what") + " of " + s.getString("name") + (s.getInt("size") > 1 ? " (" + s.getInt("size") + ")" : "")),
                    Component.literal("seen " + (s.getLong("age") == 0 ? "today" : s.getLong("age") + " days ago") + " by " + s.getString("by")));
            }
        }
        // Us.
        g.fill(cx - 3, cy - 3, cx + 4, cy + 4, GREEN);
        g.renderOutline(cx - 3, cy - 3, 7, 7, Ui.EDGE);
        // The rivals, labelled with their last-known guards and the report's age.
        for (CompoundTag r : rivals) {
            int px = cx + (int) (r.getInt("dx") * scale), py = cy + (int) (r.getInt("dz") * scale);
            int col = r.getBoolean("report") ? RED : AMBER;
            g.fill(px - 3, py - 3, px + 4, py + 4, col);
            g.renderOutline(px - 3, py - 3, 7, 7, Ui.EDGE);
            String label = r.getString("name");
            String sub = r.getBoolean("report") ? r.getInt("r_guards") + " guards · " + r.getString("age_words") : "rumour: ~" + r.getInt("e_guards") + " guards";
            int lx = Math.max(x + 2, Math.min(x + side - 2 - font.width(label) * 3 / 4, px - font.width(label) * 3 / 8));
            small(g, font, label, lx, py + 5, Ui.INK);
            small(g, font, sub, Math.max(x + 2, Math.min(x + side - 2 - font.width(sub) * 3 / 4, px - font.width(sub) * 3 / 8)), py + 12,
                r.getBoolean("stale") ? Ui.WARN : Ui.MUTED);
            if (Math.abs(mx - px) <= 4 && Math.abs(my - py) <= 4) {
                List<Component> t = new ArrayList<>();
                t.add(Component.literal(label + " — " + r.getString("standing")));
                if (r.getBoolean("report")) {
                    t.add(Component.literal("Report " + r.getString("age_words") + ": " + r.getInt("r_guards") + " guards, " + r.getInt("r_armoured")
                        + " in iron, " + r.getInt("r_archers") + " bows"));
                    if (!r.getString("note").isEmpty()) t.add(Component.literal("\"" + r.getString("note") + "\""));
                }
                t.add(Component.literal(capital(r.getString("words"))));
                tip = t;
            }
        }
        small(g, font, "■ us  ■ them  ✕ seen  · pickets  · spies", x + 3, y + side - 9, Ui.FAINT);
        return tip;
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
