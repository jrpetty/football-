package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.village.InventionTree;
import com.jrpetty.mcassistant.village.InventionTree.Invention;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * [tech-tree] The Inventions page of the town's books (CityScreen, in the Civic section beside Research), as the server's
 * Inventions.report has it.
 *
 * <p>[screens13] Laid out as a tree that reads at any size. Across the top, the work in hand: what the scholars are at,
 * how far, the days left (the mouse over it: who chose it, why, and the working model). Under it a bar of tools: the
 * tree or the scholars (side by side when the page is wide), a filter by state (all, open, known, locked), what is
 * typed to find one, and the zoom. The tree itself reads down the page: a column for each branch (how many of it are
 * known at its head), a band for each step down the road of needs (the ages it spans written across it), each invention
 * a card in its state's colour (green known, gold in hand with its bar, white open, grey locked), a line from each thing
 * needed to what needs it (green from what is known) running in the channels between the columns, the road to the card
 * under the mouse drawn dark. The wheel scrolls it (Shift across, Ctrl to zoom) and a drag pans it; the mouse over a
 * card gives the whole of it.
 */
public final class InventionsPage {

    private InventionsPage() {}

    private static final String[] BRANCHES = { "Agriculture", "Engineering", "Military", "Learning" };
    private static final String[] FILTERS = { "All", "Open", "Known", "Locked" };
    private static final String[] ROMAN = { "I", "II", "III", "IV", "V", "VI", "VII", "VIII" };
    /** Card sizes and gaps at the three zooms: compact, normal, large. */
    private static final int[] CARD_W = { 66, 82, 112 }, CARD_H = { 13, 22, 26 }, GAP_X = { 10, 10, 16 }, GAP_Y = { 4, 4, 5 };
    private static final int STEP_HEAD = 10, COL_HEAD = 11, PAD = 4;
    private static int hoverBranch = -1;
    private static final int KNOWN_LINE = 0xFF4E9A3A, OPEN_LINE = 0xFF6F6F6F, LOCKED_LINE = 0xFF9A9A9A;

    // What the reader has chosen, kept while the books stay open (and between openings: it is only a view).
    private static int zoom = -1;                 // -1: fit the room
    private static int filter;                    // 0 all, 1 open, 2 known, 3 locked
    private static boolean scholarsView;          // on a narrow page, the scholars instead of the tree
    private static int panX, panY;
    private static String lastFind = "";

    // Where the last frame put things, for the mouse.
    private static int vx, vy, vw, vh, contentW, contentH, zoomShown;
    private static boolean dragging;
    private static double dragFromX, dragFromY;
    private static int panFromX, panFromY;
    private record Zone(int x0, int y0, int x1, int y1, Runnable act) {}
    private static final List<Zone> ZONES = new ArrayList<>();

    /** A card's place in the tree, in the tree's own pixels. */
    private record Place(Invention inv, int x, int y) {}

    /** The page. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        return draw(g, font, m, x, y, w, h, mx, my, "");
    }

    /** The page, with what the reader has typed to find (CityScreen's finds). */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int mx, int my, String find) {
        ZONES.clear();
        vw = 0;
        ListTag tree = m.getList("tree", Tag.TAG_COMPOUND);
        if (tree.isEmpty()) {
            UiStyle.heading(g, font, "Inventions", x, y, w);
            UiStyle.empty(g, font, "No inventions in these books yet", "A town of six keeps a scholar; the leader picks the work.", x, y + 12, w, Math.min(60, h - 12));
            return null;
        }
        Map<String, CompoundTag> byId = new HashMap<>();
        for (int i = 0; i < tree.size(); i++) byId.put(tree.getCompound(i).getString("id"), tree.getCompound(i));
        List<Component> tip = null;

        // ---- the work in hand, one line and its bar
        String known = m.getInt("known") + " of " + m.getInt("all") + " known";
        int kw = font.width(known);
        g.drawString(font, known, x + w - kw, y, UiStyle.TEXT_MUTED, false);
        int room = w - kw - UiStyle.S2;
        if (m.contains("now")) {
            int prog = m.getInt("prog"), cost = Math.max(1, m.getInt("cost"));
            int left = m.getInt("daysLeft");
            String head = "Researching " + m.getString("now") + " · " + (prog * 100 / cost) + "%"
                + (left > 0 ? " · about " + left + (left == 1 ? " day" : " days") : " · done by morning");
            UiStyle.text(g, font, head, x, y, room, UiStyle.TEXT);
            UiStyle.bar(g, x, y + 10, w, 3, Math.min(1F, prog / (float) cost), UiStyle.HIGHLIGHT_SPINE);
            if (mx >= x && mx < x + w && my >= y && my < y + 14) {
                String by = m.getString("by").isEmpty() ? "" : "Chosen by " + m.getString("by") + ": " + m.getString("why") + ".";
                String model = m.getBoolean("paid") ? "Its working model is built." : m.getString("short").isEmpty()
                    ? "The model is built a quarter of the way in, out of the stores." : "The model waits on the stores for " + m.getString("short") + ".";
                tip = new ArrayList<>();
                tip.add(Component.literal(head));
                if (!by.isEmpty()) tip.add(Component.literal(by));
                tip.add(Component.literal(model));
            }
        } else {
            UiStyle.text(g, font, "Nothing in hand: the leader picks the scholars' next at the morning's books.", x, y, room, UiStyle.TEXT_MUTED);
        }
        int ty = y + 17;

        // ---- the tools: the view, the filter, the find, the zoom
        // The scholars beside the tree only when the tree still has room for its cards at their usual size.
        int side = Math.min(w * 3 / 10, w - width(1) - 6 - UiStyle.S2 - 2);
        boolean wide = side >= 100;
        if (!wide) side = 0;
        int treeW = wide ? w - side - UiStyle.S2 : w;
        int bx = x;
        if (!wide) {
            bx += viewTab(g, font, "Tree", bx, ty, !scholarsView, mx, my, () -> scholarsView = false) + 2;
            bx += viewTab(g, font, "Scholars", bx, ty, scholarsView, mx, my, () -> scholarsView = true) + UiStyle.S2;
        }
        if (wide || !scholarsView) {
            for (int i = 0; i < FILTERS.length; i++) {
                final int f = i;
                bx += toggle(g, font, FILTERS[i], bx, ty, filter == i, mx, my, () -> filter = f) + 2;
            }
            // The zoom, at the right.
            int zx = x + treeW - 11;
            smallButton(g, font, "+", zx, ty, zoomShown < 2, mx, my, () -> zoom = Math.min(2, zoomShown + 1));
            smallButton(g, font, "−", zx - 13, ty, zoomShown > 0, mx, my, () -> zoom = Math.max(0, zoomShown - 1));
            int fitW = font.width("Fit") + 6;
            smallButtonW(g, font, "Fit", zx - 15 - fitW, ty, fitW, zoom >= 0, mx, my, () -> zoom = -1);
            // What is typed, between.
            String findLine = find.isEmpty() ? "type to find" : "Find: " + find + "_";
            int fx = bx + UiStyle.S1, fRoom = zx - 15 - fitW - UiStyle.S1 - fx;
            if (fRoom > 30) UiStyle.text(g, font, findLine, fx, ty + 2, fRoom, find.isEmpty() ? UiStyle.TEXT_FAINT : UiStyle.TEXT);
        }
        int top = ty + 15;
        int bodyH = y + h - top;
        if (wide) {
            tip = orTree(tip, drawTree(g, font, m, byId, x, top, treeW, bodyH, mx, my, find));
            drawScholars(g, font, m, x + w - side, ty, side, y + h - ty);
        } else if (scholarsView) {
            drawScholars(g, font, m, x, top, w, bodyH);
        } else {
            tip = orTree(tip, drawTree(g, font, m, byId, x, top, w, bodyH, mx, my, find));
        }
        return tip;
    }

    private static List<Component> orTree(@Nullable List<Component> a, @Nullable List<Component> b) {
        return b != null ? b : a;
    }

    // ------------------------------------------------------------------ the tree

    @Nullable
    private static List<Component> drawTree(GuiGraphics g, Font font, CompoundTag m, Map<String, CompoundTag> byId,
                                            int x, int y, int w, int h, int mx, int my, String find) {
        if (h < 40) return null;
        int maxDepth = 1;
        for (Invention inv : Invention.values()) maxDepth = Math.max(maxDepth, inv.depth());
        int innerW = w - 2 - 4, innerH = h - 2 - COL_HEAD - 4;          // room for the scroll bars
        // The zoom: as asked, or the largest that fits across, or the smallest.
        int z = zoom;
        if (z < 0) {
            z = 0;
            for (int t = 2; t >= 0; t--) if (width(t) <= innerW) { z = t; break; }
        }
        zoomShown = z;
        int cw = CARD_W[z], ch = CARD_H[z], gx = GAP_X[z], gy = GAP_Y[z];
        int padL = gx / 2 + 2;
        // Place every card: its column by its branch, its band by its step down the road of needs, stacked in the band.
        int[] rows = new int[maxDepth + 1];
        int[][] stack = new int[BRANCHES.length][maxDepth + 1];
        for (Invention inv : Invention.values()) rows[inv.depth()] = Math.max(rows[inv.depth()], ++stack[inv.branch.ordinal()][inv.depth()]);
        int[] bandTop = new int[maxDepth + 2];
        int by = 0;
        for (int d = 1; d <= maxDepth; d++) {
            bandTop[d] = by;
            by += STEP_HEAD + rows[d] * (ch + gy);
        }
        bandTop[maxDepth + 1] = by;
        Map<Invention, Place> at = new EnumMap<>(Invention.class);
        int[][] placed = new int[BRANCHES.length][maxDepth + 1];
        for (Invention inv : Invention.values()) {
            int b = inv.branch.ordinal(), d = inv.depth();
            int slot = placed[b][d]++;
            at.put(inv, new Place(inv, padL + b * (cw + gx), bandTop[d] + STEP_HEAD + slot * (ch + gy)));
        }
        contentW = width(z);
        contentH = by + PAD;
        vx = x; vy = y; vw = w; vh = h;
        panX = Math.max(0, Math.min(Math.max(0, contentW - innerW), panX));
        panY = Math.max(0, Math.min(Math.max(0, contentH - innerH), panY));
        // What is typed: the first card that answers it brought into view, once, as it changes.
        String q = find.trim().toLowerCase(Locale.ROOT);
        if (!q.equals(lastFind)) {
            lastFind = q;
            if (!q.isEmpty()) for (Invention inv : Invention.values()) {
                if (!inv.title.toLowerCase(Locale.ROOT).contains(q)) continue;
                Place p = at.get(inv);
                if (p.x() < panX || p.x() + cw > panX + innerW) panX = Math.max(0, Math.min(Math.max(0, contentW - innerW), p.x() - padL));
                if (p.y() < panY || p.y() + ch > panY + innerH) panY = Math.max(0, Math.min(Math.max(0, contentH - innerH), p.y() - STEP_HEAD));
                break;
            }
        }
        UiStyle.well(g, x, y, w, h, UiStyle.PAGE);
        int ox = x + 1 - panX, oy = y + 1 + COL_HEAD - panY;
        int clipX0 = x + 1, clipY0 = y + 1 + COL_HEAD, clipX1 = x + 1 + innerW, clipY1 = y + 1 + COL_HEAD + innerH;
        boolean overTree = mx >= clipX0 && mx < clipX1 && my >= clipY0 && my < clipY1;
        // Which card the mouse is over, and the road to it (all it needs, and what needs it next).
        Invention hovered = null;
        if (overTree && !dragging) {
            for (Place p : at.values()) {
                int px = ox + p.x(), py = oy + p.y();
                if (mx >= px && mx < px + cw && my >= py && my < py + ch) { hovered = p.inv(); break; }
            }
        }
        Set<Invention> anc = EnumSet.noneOf(Invention.class);
        if (hovered != null) ancestors(hovered, anc);
        g.enableScissor(clipX0, clipY0, clipX1, clipY1);
        // The steps: a band for each, every other one shaded, its number and the ages it spans written at its head.
        for (int d = 1; d <= maxDepth; d++) {
            int top = oy + bandTop[d], bottom = oy + bandTop[d + 1];
            if (d % 2 == 0) g.fill(clipX0, top, clipX1, bottom, 0x12000000);
            if (d > 1) g.fill(clipX0, top, clipX1, top + 1, 0x22000000);
            int lo = 9, hi = -1;
            for (Invention inv : Invention.values()) if (inv.depth() == d) { lo = Math.min(lo, inv.age); hi = Math.max(hi, inv.age); }
            String label = ROMAN[Math.min(ROMAN.length - 1, d - 1)] + (hi < 0 ? "" : "  \u00b7  " + ageWord(lo) + (hi > lo ? "\u2013" + ageWord(hi) : "") + " Age");
            small(g, font, label, Math.max(ox + padL, clipX0 + 3), top + 3, UiStyle.TEXT_FAINT);
        }
        // The lines: from the foot of each thing needed, along under it to the channel beside what needs it, down,
        // and into its side with an arrow. The channels run between the columns, where no card stands.
        for (int pass = 0; pass < 2; pass++) {                       // the road to the hovered card last, over the rest
            for (Invention inv : Invention.values()) {
                Place to = at.get(inv);
                for (Invention need : inv.needs()) {
                    boolean onRoad = hovered != null && (need == hovered || (inv == hovered || anc.contains(inv)) && anc.contains(need));
                    if ((pass == 1) != onRoad) continue;
                    Place from = at.get(need);
                    String fs = state(byId, need), ts = state(byId, inv);
                    int colour = onRoad ? UiStyle.ACCENT : fs.equals("known") ? KNOWN_LINE : ts.equals("locked") ? LOCKED_LINE : OPEN_LINE;
                    if (!matches(byId, inv, q) || !matches(byId, need, q)) colour = UiStyle.alpha(colour, 0.35F);
                    int t = onRoad ? 2 : 1;
                    boolean leftward = to.x() < from.x();
                    int chanX = ox + (leftward ? to.x() + cw + gx / 2 : to.x() - gx / 2);
                    int sx = ox + from.x() + cw / 2 + (leftward ? -cw / 4 : -cw / 4 - 2), sy = oy + from.y() + ch;
                    int runY = sy + Math.max(1, gy / 2);
                    int ey = oy + to.y() + ch / 2;
                    int ex = ox + (leftward ? to.x() + cw : to.x());
                    g.fill(sx, sy, sx + t, runY + t, colour);                                  // down from its foot
                    g.fill(Math.min(sx, chanX), runY, Math.max(sx, chanX) + t, runY + t, colour);   // along to the channel
                    g.fill(chanX, Math.min(runY, ey), chanX + t, Math.max(runY, ey) + t, colour);   // down the channel
                    if (leftward) {
                        g.fill(ex + 2, ey, chanX + t, ey + t, colour);
                        g.fill(ex + 1, ey - 1, ex + 3, ey + t + 1, colour);                  // the arrow's head
                    } else {
                        g.fill(chanX, ey, ex - 2, ey + t, colour);
                        g.fill(ex - 3, ey - 1, ex - 1, ey + t + 1, colour);
                    }
                }
            }
        }
        // The cards.
        for (Place p : at.values()) {
            int px = ox + p.x(), py = oy + p.y();
            if (px > clipX1 || px + cw < clipX0 || py > clipY1 || py + ch < clipY0) continue;
            card(g, font, m, byId, p.inv(), px, py, cw, ch, z, p.inv() == hovered, matches(byId, p.inv(), q));
        }
        g.disableScissor();
        // The column heads: the branches, stuck along the top.
        g.fill(x + 1, y + 1, x + w - 1, y + 1 + COL_HEAD, UiStyle.PANEL_HEADER);
        g.fill(x + 1, y + COL_HEAD, x + w - 1, y + 1 + COL_HEAD, UiStyle.EDGE_SOFT);
        g.enableScissor(x + 1, y + 1, x + w - 1, y + 1 + COL_HEAD);
        for (int b = 0; b < BRANCHES.length; b++) {
            int cx = ox + padL + b * (cw + gx);
            String name = BRANCHES[b];
            int known = 0, all = 0;
            for (Invention inv : Invention.values()) if (inv.branch.ordinal() == b) { all++; if (state(byId, inv).equals("known")) known++; }
            String count = known + "/" + all;
            int countW = (int) (font.width(count) * 0.75F);
            boolean both = font.width(name) + countW + 4 <= cw;
            g.drawString(font, UiStyle.clip(font, name, both ? cw - countW - 4 : cw - 2), cx + 1, y + 2, UiStyle.TEXT_MUTED, false);
            if (both) small(g, font, count, cx + cw - countW - 1, y + 3, UiStyle.TEXT_FAINT);
            if (mx >= cx && mx < cx + cw && my >= y + 1 && my < y + 1 + COL_HEAD) hoverBranch = b;
        }
        g.disableScissor();
        // The scroll bars: down the right, along the bottom when it is wider than the room.
        UiStyle.scrollBar(g, x + w - 5, clipY0, innerH, panY, Math.max(0, contentH - innerH), innerH);
        int maxX = Math.max(0, contentW - innerW);
        if (maxX > 0) {
            int track = innerW, thumb = Math.max(8, track * innerW / Math.max(1, contentW));
            int tx = clipX0 + (track - thumb) * panX / maxX;
            g.fill(clipX0, y + h - 5, clipX0 + track, y + h - 2, 0x30000000);
            g.fill(tx, y + h - 5, tx + thumb, y + h - 2, UiStyle.EDGE);
        }
        if (hovered != null) {
            List<Component> tip = tip(byId.getOrDefault(hovered.id(), new CompoundTag()));
            if (!state(byId, hovered).equals("locked")) tip.add(1, Component.literal(stateWords(m, byId, hovered)));   // locked: its own line says why
            return tip;
        }
        if (hoverBranch >= 0) {
            InventionTree.Branch br = InventionTree.Branch.values()[hoverBranch];
            hoverBranch = -1;
            int known = 0, all = 0;
            for (Invention inv : Invention.values()) if (inv.branch == br) { all++; if (state(byId, inv).equals("known")) known++; }
            return List.of(Component.literal(br.title + ": " + br.about + "."), Component.literal(known + " of " + all + " known."));
        }
        return null;
    }

    /** One card: its state's fill and spine, its title, and under it (when there is room) what it costs or that it is known. */
    private static void card(GuiGraphics g, Font font, CompoundTag m, Map<String, CompoundTag> byId, Invention inv,
                             int x, int y, int w, int h, int z, boolean hover, boolean lit) {
        String s = state(byId, inv);
        int fill, spine, ink, subInk;
        String sub;
        switch (s) {
            case "known" -> { fill = UiStyle.TILE_SELECTED; spine = UiStyle.SUCCESS_FILL; ink = UiStyle.TEXT; subInk = UiStyle.SUCCESS; sub = "✓ known"; }
            case "researching" -> {
                fill = UiStyle.HIGHLIGHT; spine = UiStyle.HIGHLIGHT_SPINE; ink = UiStyle.TEXT; subInk = UiStyle.WARNING;
                int cost = Math.max(1, m.getInt("cost"));
                sub = "in hand · " + (m.getInt("prog") * 100 / cost) + "%";
            }
            case "open" -> { fill = 0xFFF2F2F2; spine = UiStyle.INFO_FILL; ink = UiStyle.TEXT; subInk = UiStyle.TEXT_MUTED; sub = inv.cost + " learning · " + inv.days + "d"; }
            default -> { fill = UiStyle.TILE_DISABLED; spine = 0; ink = UiStyle.TEXT_DISABLED; subInk = UiStyle.TEXT_FAINT; sub = "○ " + capital(inv.ageWords().replace("the ", "")); }
        }
        g.fill(x, y, x + w, y + h, hover ? UiStyle.EDGE : s.equals("open") ? UiStyle.EDGE_SOFT : 0xFF9E9E9E);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hover ? Ui.lighten(fill, 0.35F) : fill);
        if (spine != 0) g.fill(x + 1, y + 1, x + 3, y + h - 1, spine);
        if (s.equals("researching") && m.getInt("cost") > 0) {
            float f = Math.min(1F, m.getInt("prog") / (float) m.getInt("cost"));
            g.fill(x + 3, y + h - 3, x + 3 + (int) ((w - 4) * f), y + h - 1, UiStyle.HIGHLIGHT_SPINE);
        }
        String title = inv.title.startsWith("The ") ? inv.title.substring(4) : inv.title;
        int tx = x + 5;
        if (z == 0) {
            small(g, font, UiStyle.clip(font, title, (int) ((w - 7) / 0.75F)), tx, y + (h - 6) / 2, ink);
        } else {
            if (font.width(title) <= w - 7) g.drawString(font, title, tx, y + 3, ink, false);
            else small(g, font, UiStyle.clip(font, title, (int) ((w - 7) / 0.75F)), tx, y + 4, ink);   // a long name, smaller
            small(g, font, UiStyle.clip(font, sub, (int) ((w - 7) / 0.75F)), tx, y + h - 9, subInk);
        }
        if (!lit) g.fill(x, y, x + w, y + h, 0xA0D0D0D0);                // not what is filtered for or found: faded back
    }

    private static void ancestors(Invention inv, Set<Invention> into) {
        for (Invention n : inv.needs()) if (into.add(n)) ancestors(n, into);
    }

    private static String state(Map<String, CompoundTag> byId, Invention inv) {
        CompoundTag n = byId.get(inv.id());
        return n == null ? "locked" : n.getString("state");
    }

    /** Does a card answer the filter and what is typed? */
    private static boolean matches(Map<String, CompoundTag> byId, Invention inv, String q) {
        String s = state(byId, inv);
        boolean f = switch (filter) {
            case 1 -> s.equals("open") || s.equals("researching");
            case 2 -> s.equals("known");
            case 3 -> s.equals("locked");
            default -> true;
        };
        return f && (q.isEmpty() || inv.title.toLowerCase(Locale.ROOT).contains(q));
    }

    private static String stateWords(CompoundTag m, Map<String, CompoundTag> byId, Invention inv) {
        return switch (state(byId, inv)) {
            case "known" -> "Known to the town.";
            case "researching" -> "In hand: " + (m.getInt("prog") * 100 / Math.max(1, m.getInt("cost"))) + "% of the way.";
            case "open" -> "Open: the scholars could take it up next.";
            default -> "Locked.";
        };
    }

    private static int width(int z) {
        return 2 * (GAP_X[z] / 2 + 2) + BRANCHES.length * CARD_W[z] + (BRANCHES.length - 1) * GAP_X[z];
    }

    private static String ageWord(int age) {
        String a = InventionTree.AGES[Math.max(0, Math.min(InventionTree.AGES.length - 1, age))];
        return capital(a.replace("the ", "").replace(" Age", ""));
    }

    // ------------------------------------------------------------------ the scholars

    private static void drawScholars(GuiGraphics g, Font font, CompoundTag m, int sx, int sy, int side, int sh) {
        int bottom = sy + sh;
        UiStyle.heading(g, font, "Learning: " + m.getString("rate") + " a day", sx, sy, side);
        sy += 12;
        for (String line : wrap(font, m.getString("rateWhy"), side - 4)) {
            if (sy > bottom - 8) return;
            small(g, font, line, sx + 2, sy, UiStyle.TEXT_MUTED);
            sy += 8;
        }
        sy += 4;
        ListTag scholars = m.getList("scholars", Tag.TAG_COMPOUND);
        if (sy > bottom - 12) return;
        UiStyle.heading(g, font, "Scholars (" + scholars.size() + ")" + (m.getBoolean("academy") ? ", at the academy" : ""), sx, sy, side);
        sy += 12;
        if (scholars.isEmpty()) {
            small(g, font, "None yet: a town of six keeps one.", sx + 2, sy, UiStyle.TEXT_FAINT);
            sy += 9;
        }
        for (int i = 0; i < scholars.size() && sy < bottom - 16; i++) {
            CompoundTag s = scholars.getCompound(i);
            String name = s.getString("name");
            g.drawString(font, UiStyle.clip(font, name, side - 30), sx + 2, sy, UiStyle.TEXT, false);
            String apt = s.getInt("aptitude") + "";
            g.drawString(font, apt, sx + side - font.width(apt) - 2, sy, UiStyle.levelInk(s.getInt("aptitude"), 60, 35), false);
            sy += 10;
            small(g, font, UiStyle.clip(font, s.getString("why") + "; " + s.getInt("beats") + " beats at the desk yesterday", (int) ((side - 8) / 0.75F)),
                sx + 6, sy, UiStyle.TEXT_FAINT);
            sy += 10;
        }
        sy += 4;
        List<String> rest = new ArrayList<>();
        rest.add("Eurekas " + m.getInt("eurekas") + ", failed experiments " + m.getInt("fails") + ", pages of notes " + m.getInt("notes") + ".");
        if (!m.getString("suggest").isEmpty()) rest.add("Suggested: " + m.getString("suggest") + ".");
        if (!m.getString("patrons").isEmpty()) rest.add("Patrons: " + m.getString("patrons") + ".");
        rest.add("Suggest, sponsor or give books: /village inventions, or the academy's lectern.");
        for (String r : rest) {
            for (String line : wrap(font, r, side - 4)) {
                if (sy > bottom - 8) return;
                small(g, font, line, sx + 2, sy, UiStyle.TEXT_MUTED);
                sy += 8;
            }
            sy += 2;
        }
    }

    // ------------------------------------------------------------------ the tools

    private static int viewTab(GuiGraphics g, Font font, String label, int x, int y, boolean on, int mx, int my, Runnable act) {
        int w = font.width(label) + 10;
        boolean over = mx >= x && mx < x + w && my >= y && my < y + 12;
        UiStyle.topTab(g, x, y, w, 12, UiStyle.State.of(true, on, over));
        g.drawString(font, label, x + 5, y + 2, on ? UiStyle.TEXT : UiStyle.TEXT_MUTED, false);
        ZONES.add(new Zone(x, y, x + w, y + 12, act));
        return w;
    }

    private static int toggle(GuiGraphics g, Font font, String label, int x, int y, boolean on, int mx, int my, Runnable act) {
        int w = (int) (font.width(label) * 0.75F) + 8;
        boolean over = mx >= x && mx < x + w && my >= y && my < y + 11;
        UiStyle.tile(g, x, y, w, 11, UiStyle.State.of(true, on, over));
        small(g, font, label, x + (on ? 5 : 4), y + 3, on ? UiStyle.ACCENT : UiStyle.TEXT_MUTED);
        ZONES.add(new Zone(x, y, x + w, y + 11, act));
        return w;
    }

    private static void smallButton(GuiGraphics g, Font font, String label, int x, int y, boolean enabled, int mx, int my, Runnable act) {
        smallButtonW(g, font, label, x, y, 11, enabled, mx, my, act);
    }

    private static void smallButtonW(GuiGraphics g, Font font, String label, int x, int y, int w, boolean enabled, int mx, int my, Runnable act) {
        boolean over = mx >= x && mx < x + w && my >= y && my < y + 11;
        UiStyle.tile(g, x, y, w, 11, UiStyle.State.of(enabled, false, over && enabled));
        g.drawString(font, label, x + (w - font.width(label)) / 2 + 1, y + 2, enabled ? UiStyle.TEXT : UiStyle.TEXT_DISABLED, false);
        if (enabled) ZONES.add(new Zone(x, y, x + w, y + 11, act));
    }

    // ------------------------------------------------------------------ the mouse (CityScreen hands it on while this page shows)

    /** A click: a tool, or the start of a drag on the tree. True when it was this page's. */
    public static boolean mouseClicked(double mx, double my, int button) {
        for (Zone z : new ArrayList<>(ZONES)) {
            if (mx >= z.x0() && mx < z.x1() && my >= z.y0() && my < z.y1()) {
                UiStyle.click();
                z.act().run();
                return true;
            }
        }
        if (button == 0 && overTree(mx, my)) {
            dragging = true;
            dragFromX = mx;
            dragFromY = my;
            panFromX = panX;
            panFromY = panY;
            return true;
        }
        return false;
    }

    public static boolean mouseDragged(double mx, double my) {
        if (!dragging) return false;
        panX = panFromX - (int) Math.round(mx - dragFromX);
        panY = panFromY - (int) Math.round(my - dragFromY);
        return true;
    }

    public static boolean mouseReleased() {
        boolean was = dragging;
        dragging = false;
        return was;
    }

    /** The wheel over the tree: down and up, across with Shift, the zoom with Ctrl. */
    public static boolean mouseScrolled(double mx, double my, double dy, boolean shift, boolean ctrl) {
        if (!overTree(mx, my) || dy == 0) return false;
        int step = (int) Math.signum(dy);
        if (ctrl) {
            zoom = Math.max(0, Math.min(2, zoomShown + step));
        } else if (shift) {
            panX -= step * 24;
        } else {
            panY -= step * 18;
        }
        return true;
    }

    private static boolean overTree(double mx, double my) {
        return vw > 0 && mx >= vx && mx < vx + vw && my >= vy && my < vy + vh;
    }

    // ------------------------------------------------------------------ text

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
