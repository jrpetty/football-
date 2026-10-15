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
 * [tech-tree] The Inventions page of the town's books (CityScreen, in the Civic section beside Research), as the server's
 * Inventions.report has it. Across the top, the work in hand: what the scholars are at, a bar of their learning, the
 * days left, who chose it and why, and whether its working model is built or waits on the stores. Below on the left the
 * tree, a column a branch (Agriculture, Engineering, Military, Learning), each invention a box at its depth: green known,
 * gold the one in hand (a bar across it), white open, grey locked; the mouse over one gives the whole of it (its age, its
 * cost and days, what it needs, the model's makings, what it unlocks and does, why it is locked, who was first to it in
 * the world). On the right the day's learning and its parts, the scholars with their heads for it and their evenings at
 * the desk, the eurekas and failures, the notes, the patrons and the suggestion waiting.
 */
public final class InventionsPage {

    private InventionsPage() {}

    private static final int KNOWN = 0xFF5E9B4E, NOW = 0xFFD9A63A, OPEN = 0xFFEDEDED, LOCKED = 0xFF9A9A9A, BAR = 0xFF3F7F2F;
    private static final String[] BRANCHES = { "Agriculture", "Engineering", "Military", "Learning" };

    /** The page. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        ListTag tree = m.getList("tree", Tag.TAG_COMPOUND);
        if (tree.isEmpty()) {
            Ui.section(g, font, "Inventions", x, y, w);
            small(g, font, "No inventions in these books yet.", x + 4, y + 14, Ui.MUTED);
            return null;
        }
        List<Component> tip = null;
        // ---- the work in hand
        Ui.section(g, font, "Inventions: " + m.getInt("known") + " of " + m.getInt("all") + " known", x, y, w);
        int cy = y + 13;
        if (m.contains("now")) {
            int prog = m.getInt("prog"), cost = Math.max(1, m.getInt("cost"));
            int left = m.getInt("daysLeft");
            String head = "Researching " + m.getString("now") + ": " + (prog * 100 / cost) + "%" + (left > 0 ? ", about " + left + (left == 1 ? " day" : " days") + " to go" : ", done at the next morning");
            small(g, font, Ui.clip(font, head, (int) ((w - 8) / 0.75F)), x + 4, cy, Ui.INK);
            cy += 9;
            Ui.bar(g, x + 4, cy, w - 8, 5, Math.min(1F, prog / (float) cost), BAR);
            cy += 8;
            String by = m.getString("by").isEmpty() ? "" : "Chosen by " + m.getString("by") + ": " + m.getString("why") + ". ";
            String model = m.getBoolean("paid") ? "Its working model is built." : m.getString("short").isEmpty()
                ? "The model is built a quarter of the way in, out of the stores." : "The model waits on the stores for " + m.getString("short") + ".";
            for (String line : wrap(font, by + model, w - 8)) {
                small(g, font, line, x + 4, cy, Ui.MUTED);
                cy += 8;
            }
        } else {
            small(g, font, "Nothing in hand: the leader chooses the scholars' next at the morning's books.", x + 4, cy, Ui.MUTED);
            cy += 9;
        }
        cy += 3;
        // ---- the tree, a column a branch
        int side = Math.max(110, w * 3 / 10);
        int tw = w - side - 6, colW = Math.max(40, tw / BRANCHES.length);
        int maxDepth = 1;
        for (int i = 0; i < tree.size(); i++) maxDepth = Math.max(maxDepth, tree.getCompound(i).getInt("depth"));
        int boxH = 17, gap = 3;
        int[] perRow = new int[BRANCHES.length * (maxDepth + 1)];
        for (int b = 0; b < BRANCHES.length; b++) small(g, font, BRANCHES[b], x + b * colW + 3, cy, Ui.INK);
        int ty = cy + 9;
        // Boxes placed by branch and depth, several to a depth stacked.
        List<int[]> boxes = new ArrayList<>();
        int bottom = ty;
        for (int i = 0; i < tree.size(); i++) {
            CompoundTag n = tree.getCompound(i);
            int b = Math.max(0, Math.min(BRANCHES.length - 1, n.getInt("b")));
            int d = n.getInt("depth");
            boxes.add(new int[]{ i, b, d });
        }
        // Rows: for each depth, as many as the busiest branch has at that depth.
        int[] rowsAt = new int[maxDepth + 1];
        for (int[] bx : boxes) rowsAt[bx[2]] = Math.max(rowsAt[bx[2]], ++perRow[bx[1] * (maxDepth + 1) + bx[2]]);
        int[] depthY = new int[maxDepth + 2];
        depthY[1] = ty;
        for (int d = 1; d <= maxDepth; d++) depthY[d + 1] = depthY[d] + rowsAt[d] * (boxH + gap) + 2;
        int[] placed = new int[BRANCHES.length * (maxDepth + 1)];
        for (int[] bx : boxes) {
            CompoundTag n = tree.getCompound(bx[0]);
            int b = bx[1], d = bx[2];
            int slot = placed[b * (maxDepth + 1) + d]++;
            int bxX = x + b * colW + 2, bxY = depthY[d] + slot * (boxH + gap) - scroll * 4;
            if (bxY + boxH > y + h || bxY < ty - 2) continue;
            String state = n.getString("state");
            int fill = switch (state) { case "known" -> KNOWN; case "researching" -> NOW; case "open" -> OPEN; default -> LOCKED; };
            g.fill(bxX, bxY, bxX + colW - 4, bxY + boxH, fill);
            g.renderOutline(bxX, bxY, colW - 4, boxH, Ui.EDGE);
            if (state.equals("researching") && m.getInt("cost") > 0) {
                float f = Math.min(1F, m.getInt("prog") / (float) m.getInt("cost"));
                g.fill(bxX + 1, bxY + boxH - 3, bxX + 1 + (int) ((colW - 6) * f), bxY + boxH - 1, BAR);
            }
            String title = n.getString("title").replace("The ", "");
            small(g, font, Ui.clip(font, title, (int) ((colW - 8) / 0.75F)), bxX + 2, bxY + 2, state.equals("locked") ? Ui.FAINT : Ui.INK);
            String sub = state.equals("known") ? "known" : state.equals("researching") ? "researching" : state.equals("open") ? n.getInt("cost") + " learning"
                : n.getString("age").replace("the ", "");
            small(g, font, Ui.clip(font, sub, (int) ((colW - 8) / 0.75F)), bxX + 2, bxY + 9, Ui.MUTED);
            bottom = Math.max(bottom, bxY + boxH);
            if (mx >= bxX && mx < bxX + colW - 4 && my >= bxY && my < bxY + boxH) tip = tip(n);
        }
        // ---- the side: the learning, the scholars, the rest
        int sx = x + w - side, sy = cy;
        Ui.section(g, font, "Learning: " + m.getString("rate") + " a day", sx, sy, side);
        sy += 12;
        for (String line : wrap(font, m.getString("rateWhy"), side - 6)) {
            if (sy > y + h - 8) break;
            small(g, font, line, sx + 2, sy, Ui.MUTED);
            sy += 8;
        }
        sy += 3;
        ListTag scholars = m.getList("scholars", Tag.TAG_COMPOUND);
        Ui.section(g, font, "Scholars (" + scholars.size() + ")" + (m.getBoolean("academy") ? ", at the academy" : ""), sx, sy, side);
        sy += 12;
        if (scholars.isEmpty()) {
            small(g, font, "None yet: a town of six keeps one.", sx + 2, sy, Ui.FAINT);
            sy += 8;
        }
        for (int i = 0; i < scholars.size() && sy < y + h - 16; i++) {
            CompoundTag s = scholars.getCompound(i);
            small(g, font, Ui.clip(font, s.getString("name") + " (" + s.getInt("aptitude") + ")", (int) ((side - 6) / 0.75F)), sx + 2, sy, Ui.INK);
            sy += 8;
            small(g, font, Ui.clip(font, s.getString("why") + "; " + s.getInt("beats") + " beats at the desk yesterday", (int) ((side - 8) / 0.75F)),
                sx + 6, sy, Ui.FAINT);
            sy += 9;
        }
        sy += 3;
        List<String> rest = new ArrayList<>();
        rest.add("Eurekas " + m.getInt("eurekas") + ", failed experiments " + m.getInt("fails") + ", pages of notes " + m.getInt("notes") + ".");
        if (!m.getString("suggest").isEmpty()) rest.add("Suggested: " + m.getString("suggest") + ".");
        if (!m.getString("patrons").isEmpty()) rest.add("Patrons: " + m.getString("patrons") + ".");
        rest.add("Suggest, sponsor or give books: /village inventions, or the academy's lectern.");
        for (String r : rest) {
            for (String line : wrap(font, r, side - 6)) {
                if (sy > y + h - 8) break;
                small(g, font, line, sx + 2, sy, Ui.MUTED);
                sy += 8;
            }
        }
        // The key, under the tree.
        if (bottom + 10 < y + h) {
            int kx = x + 2, ky = bottom + 4;
            int[] cols = { KNOWN, NOW, OPEN, LOCKED };
            String[] words = { "known", "in hand", "open", "locked" };
            for (int i = 0; i < cols.length; i++) {
                g.fill(kx, ky, kx + 6, ky + 6, cols[i]);
                g.renderOutline(kx, ky, 6, 6, Ui.EDGE);
                small(g, font, words[i], kx + 8, ky, Ui.MUTED);
                kx += 12 + (int) (font.width(words[i]) * 0.75F) + 6;
            }
        }
        return tip;
    }

    /** The whole of one invention, for the mouse. */
    static List<Component> tip(CompoundTag n) {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal(n.getString("title")));
        out.add(Component.literal(n.getString("branch") + ", " + n.getString("age") + "; " + n.getInt("cost") + " learning, " + n.getInt("days") + " days at least"));
        if (!n.getString("needs").isEmpty()) out.add(Component.literal("Needs: " + n.getString("needs")));
        out.add(Component.literal("Model: " + n.getString("model")));
        out.add(Component.literal("Unlocks: " + n.getString("unlocks")));
        out.add(Component.literal(capital(n.getString("effect")) + "."));
        if (n.contains("why")) out.add(Component.literal("Locked: " + n.getString("why")));
        if (n.contains("knownDay")) out.add(Component.literal("Known since day " + (n.getLong("knownDay") + 1)));
        if (n.contains("first")) out.add(Component.literal("First in the world: " + n.getString("first")));
        return out;
    }

    private static List<String> wrap(Font font, String text, int width) {
        List<String> out = new ArrayList<>();
        int max = (int) (width / 0.75F);
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (font.width(next) > max && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
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
