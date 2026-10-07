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
 * [perks] The Perks page of the town's books (CityScreen), as the server's Perks.report has it. On the left the wonders
 * of the world, a line each (gold where the town holds it, grey where another town does, an empty frame where nobody
 * has raised it yet), and the town's ways: each pair of civics it may have only one of, the one it took and the one it
 * closed. On the right its leader: its perk in office, its level, its skills (a row for each line, taken, open to take
 * or still out of reach) and the town's legacies; and its folk: the quirks among them and what the town is known for,
 * and their knacks and masters. The mouse over a wonder, a skill or a quirk tells the whole of it.
 */
public final class PerksPage {

    private PerksPage() {}

    private static final int GOLD = 0xFFC9A227, GOLD_BED = 0xFFF1DC98, HELD = 0xFF8A8A8A, TAKEN = 0xFF23803A, OPEN = 0xFF2E6FBF;

    /** The page. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        if (m.isEmpty()) {
            g.drawString(font, "No perks in these books yet: they are written at the next morning's books.", x, y, Ui.MUTED, false);
            return null;
        }
        List<Component> tip = null;
        int left = w * 11 / 20, right = w - left - 8;
        int lmax = (int) ((left - 12) / 0.75F);
        // The wonders of the world.
        List<CompoundTag> wonders = compounds(m, "wonders");
        int ours = 0, raised = 0;
        for (CompoundTag t : wonders) {
            if (t.getBoolean("ours")) ours++;
            if (!t.getString("holder").isEmpty()) raised++;
        }
        Ui.section(g, font, "Wonders of the world: " + ours + " ours, " + raised + " of " + wonders.size() + " raised", x, y, left);
        int cy = y + 12;
        for (CompoundTag t : wonders) {
            boolean mine = t.getBoolean("ours"), held = !t.getString("holder").isEmpty();
            if (mine) {
                g.fill(x + 2, cy, x + 9, cy + 7, GOLD_BED);
                g.renderOutline(x + 2, cy, 7, 7, GOLD);
            } else if (held) g.fill(x + 2, cy, x + 9, cy + 7, HELD);
            else g.renderOutline(x + 2, cy, 7, 7, Ui.EDGE_SOFT);
            String where = mine ? "ours, since day " + t.getLong("day") : held ? t.getString("holder") + ", day " + t.getLong("day")
                : "not yet raised anywhere";
            small(g, font, Ui.clip(font, t.getString("title") + " (" + t.getString("branch") + ") - " + where, lmax), x + 12, cy,
                mine ? Ui.GOOD : held ? Ui.FAINT : Ui.INK);
            if (mx >= x && mx < x + left && my >= cy && my < cy + 9) {
                tip = new ArrayList<>();
                tip.add(Component.literal(t.getString("title")).withColor(0xE0B040));
                tip.add(Component.literal(t.getString("branch") + "'s wonder: " + t.getString("effect")));
                tip.add(Component.literal("Its dues: " + t.getString("dues")).withColor(0xA0A0A0));
                tip.add(Component.literal("Here: " + t.getString("state")));
                tip.add(Component.literal("Only one in the world: the first town to raise it keeps it.").withColor(0xA0A0A0));
            }
            cy += 9;
        }
        // The town's ways: the pairs.
        cy += 4;
        List<CompoundTag> ways = compounds(m, "ways");
        Ui.section(g, font, "The town's ways (one of each pair, for good)", x, cy, left);
        cy += 12;
        for (CompoundTag t : ways) {
            if (cy > y + h - 8) break;
            String a = t.getString("a"), b = t.getString("b"), chosen = t.getString("chosen");
            int ax = x + 4;
            int aw = (int) (font.width(a) * 0.75F), bw = (int) (font.width(b) * 0.75F);
            small(g, font, a, ax, cy, chosen.isEmpty() ? Ui.MUTED : chosen.equals(a) ? Ui.GOOD : Ui.FAINT);
            if (!chosen.isEmpty() && !chosen.equals(a)) g.fill(ax, cy + 3, ax + aw, cy + 4, Ui.BAD);
            int bx = ax + aw + 4;
            small(g, font, "or", bx, cy, Ui.FAINT);
            bx += (int) (font.width("or") * 0.75F) + 4;
            small(g, font, b, bx, cy, chosen.isEmpty() ? Ui.MUTED : chosen.equals(b) ? Ui.GOOD : Ui.FAINT);
            if (!chosen.isEmpty() && !chosen.equals(b)) g.fill(bx, cy + 3, bx + bw, cy + 4, Ui.BAD);
            small(g, font, Ui.clip(font, t.getString("branch"), (int) ((x + left - (bx + bw + 6)) / 0.75F)), bx + bw + 6, cy, Ui.FAINT);
            cy += 9;
        }
        // The leader.
        int rx = x + left + 8, rmax = (int) ((right - 6) / 0.75F);
        int ry = y;
        Ui.section(g, font, "The leader", rx, ry, right);
        ry += 12;
        for (String line : strings(m, "leader")) {
            for (String l : wrapped(font, line, right - 6)) {
                if (ry > y + h / 2) break;
                small(g, font, l, rx + 2, ry, Ui.INK);
                ry += 8;
            }
        }
        // The skills: a row for each line, three tiers across.
        ry += 2;
        List<CompoundTag> skills = compounds(m, "skills");
        int cellW = (right - 40) / 3;
        String lastLine = "";
        int rowY = ry - 10;
        for (CompoundTag s : skills) {
            String line = s.getString("line");
            if (!line.equals(lastLine)) {
                rowY += 10;
                lastLine = line;
                small(g, font, Ui.clip(font, line, (int) (34 / 0.75F)), rx + 2, rowY + 1, Ui.MUTED);
            }
            int tier = Math.max(1, s.getInt("tier"));
            int sx = rx + 38 + (tier - 1) * (cellW + 1);
            boolean has = s.getBoolean("has"), can = s.getBoolean("can");
            g.fill(sx, rowY, sx + cellW, rowY + 9, has ? 0xFFB9D7A8 : can ? 0xFFDADADA : 0xFFABABAB);
            g.renderOutline(sx, rowY, cellW, 9, has ? TAKEN : can ? OPEN : Ui.EDGE_SOFT);
            small(g, font, Ui.clip(font, s.getString("title"), (int) ((cellW - 4) / 0.75F)), sx + 2, rowY + 2, has || can ? Ui.INK : Ui.FAINT);
            if (mx >= sx && mx < sx + cellW && my >= rowY && my < rowY + 9) {
                tip = new ArrayList<>();
                tip.add(Component.literal(s.getString("title")));
                tip.add(Component.literal(capital(s.getString("effect"))));
                tip.add(Component.literal(has ? "Taken." : can ? "Open to take: the leader has a point to spend." : "Out of reach yet: "
                    + (tier > 1 ? "the one before it first, and " : "") + "a level's point to spend.").withColor(has ? 0x7FD67F : can ? 0xFFFFFF : 0xB0B0B0));
            }
        }
        ry = rowY + 12;
        small(g, font, Ui.clip(font, "Level " + m.getInt("level") + ", " + m.getInt("xp") + " experience"
            + (m.getInt("next") > 0 ? " (next at " + m.getInt("next") + ")" : "") + (m.getInt("free") > 0 ? "; " + m.getInt("free") + " to choose" : ""), rmax),
            rx + 2, ry, Ui.MUTED);
        ry += 9;
        List<CompoundTag> legacies = compounds(m, "legacies");
        small(g, font, legacies.isEmpty() ? "Legacies: none yet (a reign of three days or more leaves one)." : "Legacies:", rx + 2, ry, Ui.MUTED);
        ry += 8;
        for (CompoundTag l : legacies) {
            if (ry > y + h * 3 / 4) break;
            small(g, font, Ui.clip(font, l.getString("name") + " (" + l.getString("leader") + ", days " + (l.getLong("from") + 1) + "-"
                + (l.getLong("to") + 1) + "): " + l.getString("effect"), rmax), rx + 4, ry, Ui.INK);
            ry += 8;
        }
        // The folk: their quirks, and their knacks.
        ry += 3;
        Ui.section(g, font, "The folk", rx, ry, right);
        ry += 12;
        String known = m.getString("known");
        if (!known.isEmpty()) {
            small(g, font, Ui.clip(font, "The town is " + known + ".", rmax), rx + 2, ry, Ui.GOOD);
            ry += 8;
        }
        List<CompoundTag> quirks = compounds(m, "quirks");
        int qx = rx + 2;
        small(g, font, "Quirks:", qx, ry, Ui.MUTED);
        qx += (int) (font.width("Quirks: ") * 0.75F);
        for (CompoundTag t : quirks) {
            String word = t.getString("title") + " " + t.getInt("n");
            int ww = (int) (font.width(word) * 0.75F) + 5;
            if (qx + ww > rx + right) {
                qx = rx + 6;
                ry += 8;
                if (ry > y + h - 16) break;
            }
            small(g, font, word, qx, ry, Ui.INK);
            if (mx >= qx && mx < qx + ww && my >= ry && my < ry + 8) {
                tip = new ArrayList<>();
                tip.add(Component.literal(t.getString("title") + ": " + t.getInt("n") + " of the town's folk"));
                tip.add(Component.literal(capital(t.getString("effect"))));
            }
            qx += ww;
        }
        if (quirks.isEmpty()) small(g, font, "none told yet", qx, ry, Ui.FAINT);
        ry += 10;
        List<CompoundTag> knacks = compounds(m, "knacks");
        List<String> top = new ArrayList<>();
        for (int i = 0; i < Math.min(5, knacks.size()); i++) top.add(knacks.get(i).getString("title") + " " + knacks.get(i).getInt("n"));
        if (ry <= y + h - 8) {
            small(g, font, Ui.clip(font, "Knacks: " + (top.isEmpty() ? "none chosen yet" : String.join(", ", top)) + " · masters "
                + m.getInt("masters") + " · " + m.getInt("knack_kinds") + " kinds in all", rmax), rx + 2, ry, Ui.MUTED);
        }
        return tip;
    }

    private static List<String> strings(CompoundTag m, String key) {
        List<String> out = new ArrayList<>();
        ListTag l = m.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < l.size(); i++) out.add(l.getString(i));
        return out;
    }

    private static List<CompoundTag> compounds(CompoundTag m, String key) {
        List<CompoundTag> out = new ArrayList<>();
        ListTag l = m.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) out.add(l.getCompound(i));
        return out;
    }

    private static List<String> wrapped(Font font, String s, int width) {
        List<String> out = new ArrayList<>();
        int max = (int) (width / 0.75F);
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String tried = line.length() == 0 ? word : line + " " + word;
            if (font.width(tried) > max && line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(tried);
            }
        }
        if (line.length() > 0) out.add(line.toString());
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
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
