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
 * [library] The Library page of the town's books (CityScreen), as the server's Library.report has it: on the left the
 * catalogue, a line a book with a spine in its kind's colour (the trades' books and their old editions first, then
 * the histories, the lives, the poems, the how-to books and the children's stories), who wrote it, on what day and
 * where it stands or who has it; the mouse over a line gives what the book is about. On the right the librarian,
 * the writers and how many books each, what is out on loan and when it is due, what is being written now, and what
 * the library is short of. Scrolls with the wheel.
 */
public final class LibraryPage {

    private LibraryPage() {}

    private static final int TRADE = 0xFF6B4A2B, HISTORY = 0xFF2E5A8C, LIFE = 0xFF7D3C98, POEM = 0xFFB83255, HOWTO = 0xFF2F7D46,
        STORY = 0xFFC08A1E, OLD = 0xFF8A8A8A;

    /** The page. Returns the tooltip for what the mouse is over, or null. */
    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag m, int x, int y, int w, int h, int scroll, int mx, int my) {
        List<CompoundTag> books = compounds(m, "books");
        if (!m.getBoolean("stands") && books.isEmpty()) {
            Ui.section(g, font, "The town library", x, y, w);
            int cy = y + 14;
            for (String l : wrapped(font, "The town has no library yet. " + m.getString("why")
                + " When one stands, the masters of the trades write their books of best practice there, the town's writers its histories, "
                + "poems, how-to books, lives and children's stories, and anybody may borrow them.", w - 8)) {
                small(g, font, l, x + 4, cy, Ui.MUTED);
                cy += 9;
            }
            return null;
        }
        int left = Math.max(160, w * 3 / 5), right = w - left - 8;
        int lmax = (int) ((left - 14) / 0.75F);
        int trades = 0, others = 0, old = 0;
        for (CompoundTag b : books) {
            if (b.getBoolean("old")) old++;
            else if ("TRADE".equals(b.getString("kind"))) trades++;
            else others++;
        }
        Ui.section(g, font, "On the shelves: " + trades + (trades == 1 ? " trade's book, " : " trades' books, ") + others + " more, "
            + old + (old == 1 ? " old edition" : " old editions"), x, y, left);
        int rows = Math.max(1, (h - 14) / 9);
        int start = Math.max(0, Math.min(scroll, Math.max(0, books.size() - rows)));
        int cy = y + 12;
        List<Component> tip = null;
        for (int i = start; i < books.size() && i < start + rows; i++) {
            CompoundTag b = books.get(i);
            boolean isOld = b.getBoolean("old");
            int col = isOld ? OLD : colour(b.getString("kind"));
            g.fill(x + 2, cy, x + 5, cy + 7, col);
            String line = b.getString("title") + (b.getInt("edition") > 0 ? " (" + ordinal(b.getInt("edition")) + " ed.)" : "") + " - "
                + b.getString("author") + ", day " + b.getLong("day") + " - " + b.getString("where");
            small(g, font, Ui.clip(font, line, lmax), x + 8, cy, isOld ? Ui.FAINT : b.getString("where").startsWith("on loan") ? Ui.WARN
                : b.getString("where").equals("missing") ? Ui.BAD : Ui.INK);
            if (mx >= x && mx < x + left && my >= cy && my < cy + 9) {
                tip = new ArrayList<>();
                tip.add(Component.literal(b.getString("title")));
                tip.add(Component.literal("by " + b.getString("author") + ", day " + b.getLong("day") + ", " + b.getInt("pages") + " pages"));
                tip.add(Component.literal(capital(b.getString("blurb")) + "."));
                tip.add(Component.literal("Read " + b.getInt("reads") + (b.getInt("reads") == 1 ? " time" : " times") + "; " + b.getString("where")));
            }
            cy += 9;
        }
        if (books.size() > rows) small(g, font, "(" + (start + 1) + "-" + Math.min(books.size(), start + rows) + " of " + books.size()
            + ", scroll for more)", x + 8, y + h - 8, Ui.FAINT);
        // The right-hand column.
        int rx = x + left + 8, rmax = (int) ((right - 6) / 0.75F);
        int ry = y;
        Ui.section(g, font, "The library", rx, ry, right);
        ry += 12;
        String lib = m.getString("librarian");
        List<String> about = new ArrayList<>();
        about.add(lib.isEmpty() ? "No librarian yet." : "Librarian: " + lib + (m.getLong("librarian_since") >= 0 ? ", since day " + m.getLong("librarian_since") : "") + ".");
        about.add(m.getInt("readers") + " folk have read here; " + m.getInt("fines") + " coins of fines paid, " + m.getInt("lost") + " books never brought back.");
        if (!m.getString("writing").isEmpty()) about.add("Now: " + m.getString("writing") + ".");
        if (!m.getString("short").isEmpty()) about.add("Short of " + m.getString("short") + ".");
        for (String a : about) {
            for (String l : wrapped(font, a, right - 6)) {
                small(g, font, l, rx + 2, ry, a.startsWith("Short") ? Ui.WARN : a.startsWith("Now") ? Ui.GOOD : Ui.MUTED);
                ry += 9;
            }
        }
        ry += 4;
        Ui.section(g, font, "The writers", rx, ry, right);
        ry += 12;
        ListTag authors = m.getList("authors", Tag.TAG_STRING);
        if (authors.isEmpty()) {
            small(g, font, Ui.clip(font, "Nobody but the trades' masters yet.", rmax), rx + 2, ry, Ui.FAINT);
            ry += 9;
        }
        for (int i = 0; i < authors.size() && ry < y + h - 40; i++) {
            small(g, font, Ui.clip(font, authors.getString(i), rmax), rx + 2, ry, Ui.INK);
            ry += 9;
        }
        ry += 4;
        Ui.section(g, font, "On loan", rx, ry, right);
        ry += 12;
        ListTag loans = m.getList("loans", Tag.TAG_STRING);
        if (loans.isEmpty()) small(g, font, "Every book is on the shelves.", rx + 2, ry, Ui.FAINT);
        for (int i = 0; i < loans.size() && ry < y + h - 9; i++) {
            String l = loans.getString(i);
            small(g, font, Ui.clip(font, l, rmax), rx + 2, ry, l.contains("late") ? Ui.BAD : Ui.INK);
            ry += 9;
        }
        // The key to the spines.
        if (tip == null && mx >= rx && mx < rx + right && my >= y && my < y + h) {
            tip = List.of(Component.literal("Spines: brown the trades' books, blue histories, purple lives, red poems,"),
                Component.literal("green how-to books, gold children's stories, grey old editions."));
        }
        return tip;
    }

    private static int colour(String kind) {
        return switch (kind) {
            case "TRADE" -> TRADE;
            case "HISTORY" -> HISTORY;
            case "LIFE" -> LIFE;
            case "POEM" -> POEM;
            case "HOWTO" -> HOWTO;
            case "STORY" -> STORY;
            default -> OLD;
        };
    }

    private static String ordinal(int n) {
        int t = n % 100;
        String suffix = t >= 11 && t <= 13 ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
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
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
