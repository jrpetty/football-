package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.MuseumRecords;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The archive: the village's chronicle kept as real books, on the museum's shelves.
 *
 * <p><b>The years.</b> A village counts its years from the day it was founded, ten days to a year
 * (Year 1 is its first ten days). When a year is over, what the chronicle says of it is written down
 * at once (the chronicle itself keeps only so many lines, and loses the middle first), and waits for
 * the curator to bind it.
 *
 * <p><b>The binding.</b> The curator makes a book and quill out of the stores (a book, a feather and
 * an ink sac, by the game's own recipe, made the whole way if it must be: paper and leather into the
 * book), writes the year into it and signs it: a written book titled for the year ("Chronicle of
 * Oakhollow, Year 2"), by the curator, a title page and then the year day by day. Every line is
 * wrapped to the width of a book's page and every page holds no more than a page shows, so nothing
 * runs off the bottom; a year too long for one book goes into two.
 *
 * <p><b>The shelves.</b> The newest volume lies open on the lectern at the back of the museum, where
 * anybody can read it; the one it replaces goes onto the archive's shelves (chiseled bookshelves,
 * made out of the stores' planks and slabs as they are wanted), six to a shelf. A player may take a
 * volume away; the curator writes it out again, a fair copy, from its own notes.
 */
public final class Archive {

    private Archive() {}

    /** Days to the town's year. */
    public static final int YEAR_DAYS = 10;
    /** A book's page: how wide in pixels, how many lines (the screen shows fourteen; one is spare), and how many pages. */
    public static final int PAGE_PX = 114, LINES = 13, MOST_PAGES = 100;
    /** What lines are wrapped to: a little inside the page, so a letter wider than reckoned never tips one over. */
    static final int WRAP_PX = PAGE_PX - 4;

    // ------------------------------------------------------------------ the years

    /** The year of the town this day falls in (1 from its founding), or 0 if it has no history. */
    public static int yearOf(UUID village, long day) {
        long founded = Chronicle.foundedOn(village);
        if (founded < 0 || day < founded) return 0;
        return (int) ((day - founded) / YEAR_DAYS) + 1;
    }

    /** The first day of a year. */
    public static long yearStart(long founded, int year) {
        return founded + (long) (year - 1) * YEAR_DAYS;
    }

    /**
     * Every year gone by and not yet written down, written down now out of the chronicle: what it
     * says of the year, day by day. Looked at often; does anything only when a year has turned.
     */
    public static void noteYears(UUID village, long today) {
        long founded = Chronicle.foundedOn(village);
        if (founded < 0 || today < founded) return;
        int done = (int) ((today - founded) / YEAR_DAYS);
        MuseumRecords.Book b = MuseumRecords.book(village);
        if (done <= b.yearsNoted) return;
        List<Chronicle.Entry> all = Chronicle.of(village);
        for (int y = Math.max(1, b.yearsNoted + 1); y <= done; y++) {
            MuseumRecords.Notes n = new MuseumRecords.Notes();
            n.year = y;
            n.from = yearStart(founded, y);
            n.to = n.from + YEAR_DAYS - 1;
            for (Chronicle.Entry e : all) if (e.day() >= n.from && e.day() <= n.to) n.entries.add(e);
            b.notes.add(n);
        }
        while (b.notes.size() > MuseumRecords.MOST_NOTES) b.notes.remove(0);
        b.yearsNoted = done;
        MuseumRecords.touch();
    }

    /** The oldest year waiting to be bound, or null. */
    @Nullable
    static MuseumRecords.Notes waiting(MuseumRecords.Book b) {
        return b.notes.isEmpty() ? null : b.notes.get(0);
    }

    // ------------------------------------------------------------------ the page

    /** About how wide this is in the book's font, in pixels: "§l" bold adds one to every letter after it. */
    public static int px(String s) {
        int w = 0;
        boolean bold = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(++i));
                if (code == 'l') bold = true;
                else if (code == 'r') bold = false;
                continue;
            }
            w += glyph(c) + (bold ? 1 : 0);
        }
        return w;
    }

    /** One letter's width with the gap after it, as the game's own font has them. */
    private static int glyph(char c) {
        return switch (c) {
            case '!', '\'', ',', '.', ':', ';', 'i', '|' -> 2;
            case 'l', '`' -> 3;
            case ' ', 'I', 't', '[', ']' -> 4;
            case '(', ')', '{', '}', '<', '>', 'f', 'k', '"', '*' -> 5;
            case '@', '~' -> 7;
            default -> c < 128 ? 6 : 7;
        };
    }

    /** Words the book's font has no trouble with: the long dash and the curly quotes made plain. */
    static String plain(String s) {
        return s.replace('—', '-').replace('–', '-').replace("…", "...").replace('’', '\'').replace('‘', '\'')
            .replace('“', '"').replace('”', '"').replace('\n', ' ');
    }

    /** Text wrapped at its spaces into lines no wider than this; a word too long for a line is cut. */
    public static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : plain(text).trim().split(" +")) {
            if (word.isEmpty()) continue;
            String tried = line.length() == 0 ? word : line + " " + word;
            if (px(tried) <= width) {
                line.setLength(0);
                line.append(tried);
                continue;
            }
            if (line.length() > 0) out.add(line.toString());
            line.setLength(0);
            // A word wider than a whole line: cut, a line at a time.
            String rest = word;
            while (px(rest) > width) {
                int cut = rest.length() - 1;
                while (cut > 1 && px(rest.substring(0, cut)) > width) cut--;
                out.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            line.append(rest);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /** A volume's title: as much of "The Chronicle of Oakhollow, Year 2" as a book's title holds (32). */
    public static String title(String name, int year, int part, int parts) {
        String yr = "Year " + year + (parts > 1 ? " (" + part + " of " + parts + ")" : "");
        String[] tries = { "The Chronicle of " + name + ", " + yr, "Chronicle of " + name + ", " + yr,
            name + " Chronicle, " + yr, name + ", " + yr };
        for (String t : tries) if (t.length() <= WrittenBookContent.TITLE_MAX_LENGTH) return t;
        String t = tries[3];
        return t.substring(0, WrittenBookContent.TITLE_MAX_LENGTH);
    }

    /** One body line, and whether it is a day's heading (kept with the line after it). */
    private record Line(String text, boolean heading) {}

    /**
     * A year laid out as pages, as many books as it takes: each book a title page and then the year,
     * day by day, every line no wider than the page and no more lines to a page than it shows.
     * Returns the books, each a list of its pages.
     */
    static List<List<String>> layout(String name, MuseumRecords.Notes n, String author, String elder, long today) {
        List<Line> body = new ArrayList<>();
        long lastDay = Long.MIN_VALUE;
        for (Chronicle.Entry e : n.entries) {
            if (e.text() == null || e.text().isBlank()) continue;
            if (e.day() != lastDay) {
                body.add(new Line("§lDay " + e.day() + "§r", true));
                lastDay = e.day();
            }
            String t = e.text().trim();
            t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
            if (!t.endsWith(".") && !t.endsWith("!") && !t.endsWith("?")) t += ".";
            for (String l : wrap(t, WRAP_PX)) body.add(new Line(l, false));
        }
        if (body.isEmpty()) {
            for (String l : wrap("Nothing was written of this year that the chronicle still holds.", WRAP_PX)) body.add(new Line(l, false));
        }
        body.add(new Line("", false));
        for (String l : wrap("Here ends Year " + n.year + ".", WRAP_PX)) body.add(new Line(l, false));
        // Pages: a heading never left alone at the foot of one.
        List<String> pages = new ArrayList<>();
        List<String> page = new ArrayList<>();
        for (int i = 0; i < body.size(); i++) {
            Line l = body.get(i);
            boolean full = page.size() >= LINES || (l.heading() && page.size() >= LINES - 1);
            if (full) {
                pages.add(String.join("\n", page));
                page.clear();
            }
            if (page.isEmpty() && l.text().isEmpty()) continue;           // no page starts blank
            page.add(l.text());
        }
        if (!page.isEmpty()) pages.add(String.join("\n", page));
        // Books: so many pages each, every one with its own title page.
        int perBook = MOST_PAGES - 1;
        int parts = Math.max(1, (pages.size() + perBook - 1) / perBook);
        List<List<String>> books = new ArrayList<>();
        for (int p = 0; p < parts; p++) {
            List<String> one = new ArrayList<>();
            one.add(titlePage(name, n, p + 1, parts, author, elder, today));
            one.addAll(pages.subList(p * perBook, Math.min(pages.size(), (p + 1) * perBook)));
            books.add(one);
        }
        return books;
    }

    /** The title page: what it is, which year and days, who set it down and when. */
    static String titlePage(String name, MuseumRecords.Notes n, int part, int parts, String author, String elder, long today) {
        List<String> lines = new ArrayList<>();
        lines.add("§lThe Chronicle§r");
        lines.addAll(wrap("of " + name, WRAP_PX));
        lines.add("");
        lines.add("Year " + n.year + (parts > 1 ? ", book " + part + " of " + parts : ""));
        lines.addAll(wrap("Days " + n.from + " to " + n.to, WRAP_PX));
        lines.add("");
        lines.addAll(wrap("Set down by " + author + (author.isEmpty() ? "" : ",") + " curator of the museum, on day " + today + ".", WRAP_PX));
        if (!elder.isEmpty()) lines.addAll(wrap("Elder: " + elder + ".", WRAP_PX));
        if (lines.size() > LINES) lines = new ArrayList<>(lines.subList(0, LINES));
        return String.join("\n", lines);
    }

    /** A signed book of these pages. */
    public static ItemStack book(String title, String author, int generation, List<String> pages) {
        List<Filterable<Component>> ps = new ArrayList<>();
        for (String p : pages) {
            if (ps.size() >= MOST_PAGES) break;
            ps.add(Filterable.passThrough(Component.literal(p)));
        }
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT,
            new WrittenBookContent(Filterable.passThrough(title), author, Math.max(0, Math.min(2, generation)), ps, true));
        return book;
    }

    /** A book's title, or "". */
    public static String titleOf(ItemStack s) {
        WrittenBookContent c = s.get(DataComponents.WRITTEN_BOOK_CONTENT);
        return c == null ? "" : c.title().raw();
    }

    // ------------------------------------------------------------------ the shelves

    /** The lectern, at the back of the hall, in the middle (the drawing's own). */
    static final int[] LECTERN = { 0, 0, 3 };
    /** The archive's shelves either side of it, two high: filled in this order. */
    static final int[][] SHELVES = { { -2, 0, 3 }, { -3, 0, 3 }, { -2, 1, 3 }, { -3, 1, 3 },
        { 3, 0, 3 }, { 4, 0, 3 }, { 3, 1, 3 }, { 4, 1, 3 } };
    /** A volume to a slot, six to a shelf. */
    static final int SLOTS = ChiseledBookShelfBlockEntity.MAX_BOOKS_IN_STORAGE;

    static BlockPos lecternAt(Ledger.Building b) {
        return Museum.at(b, LECTERN[0], LECTERN[1], LECTERN[2]);
    }

    static BlockPos shelfAt(Ledger.Building b, int i) {
        int[] s = SHELVES[i];
        return Museum.at(b, s[0], s[1], s[2]);
    }

    /** Does a lectern stand where the drawing has it? */
    static boolean lecternStands(ServerLevel level, Ledger.Building b) {
        return level.getBlockState(lecternAt(b)).getBlock() instanceof LecternBlock;
    }

    /** The volume open on the lectern, or empty. */
    static ItemStack onLectern(ServerLevel level, Ledger.Building b) {
        return level.getBlockEntity(lecternAt(b)) instanceof LecternBlockEntity l ? l.getBook() : ItemStack.EMPTY;
    }

    /** A free slot on a shelf that stands: {shelf, slot}, or null. */
    @Nullable
    static int[] freeSlot(ServerLevel level, Ledger.Building b) {
        for (int i = 0; i < SHELVES.length; i++) {
            if (!(level.getBlockEntity(shelfAt(b, i)) instanceof ChiseledBookShelfBlockEntity shelf)) continue;
            for (int s = 0; s < SLOTS; s++) if (shelf.getItem(s).isEmpty()) return new int[]{ i, s };
        }
        return null;
    }

    /** Where a new shelf can go: the first shelf place with nothing in it, or -1. */
    static int shelfToSet(ServerLevel level, Ledger.Building b) {
        for (int i = 0; i < SHELVES.length; i++) {
            BlockPos p = shelfAt(b, i);
            if (level.getBlockEntity(p) instanceof ChiseledBookShelfBlockEntity) continue;
            if (level.getBlockState(p).canBeReplaced()) return i;
        }
        return -1;
    }

    /** A chiseled bookshelf set in its place, its face to the hall. */
    static boolean setShelf(ServerLevel level, Ledger.Building b, int i) {
        BlockPos p = shelfAt(b, i);
        if (!level.getBlockState(p).canBeReplaced()) return false;
        Direction front = b.facing().getOpposite();
        level.setBlock(p, Blocks.CHISELED_BOOKSHELF.defaultBlockState().setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, front), 3);
        level.playSound(null, p, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
        return true;
    }

    /** A lectern set where the drawing has it, facing the hall. */
    static boolean setLectern(ServerLevel level, Ledger.Building b) {
        BlockPos p = lecternAt(b);
        if (lecternStands(level, b)) return true;
        if (!level.getBlockState(p).canBeReplaced()) return false;
        level.setBlock(p, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, b.facing().getOpposite()), 3);
        return true;
    }

    /** A book put on a shelf slot. */
    static boolean onShelf(ServerLevel level, Ledger.Building b, int[] at, ItemStack book) {
        if (!(level.getBlockEntity(shelfAt(b, at[0])) instanceof ChiseledBookShelfBlockEntity shelf)) return false;
        if (!shelf.getItem(at[1]).isEmpty()) return false;
        shelf.setItem(at[1], book);
        shelf.setChanged();
        level.playSound(null, shelfAt(b, at[0]), SoundEvents.CHISELED_BOOKSHELF_INSERT, SoundSource.BLOCKS, 0.8F, 1.0F);
        return true;
    }

    /** A book laid open on the lectern (it must be empty). */
    static boolean onTheLectern(ServerLevel level, Ledger.Building b, ItemStack book, @Nullable LivingEntity by) {
        BlockPos p = lecternAt(b);
        BlockState st = level.getBlockState(p);
        if (!(st.getBlock() instanceof LecternBlock) || st.getValue(LecternBlock.HAS_BOOK)) return false;
        return LecternBlock.tryPlaceBook(by, level, p, st, book.copy());
    }

    /** The book off the lectern (to go on a shelf), or empty. */
    static ItemStack offTheLectern(ServerLevel level, Ledger.Building b) {
        BlockPos p = lecternAt(b);
        BlockState st = level.getBlockState(p);
        if (!(level.getBlockEntity(p) instanceof LecternBlockEntity l) || !l.hasBook()) return ItemStack.EMPTY;
        ItemStack book = l.getBook().copy();
        l.clearContent();
        LecternBlock.resetBookState(null, level, p, st, false);
        return book;
    }

    /** Where a volume stands, in a word for the books ("lectern", "shelf 3/2"). */
    static String where(int[] slot) {
        return "shelf " + (slot[0] + 1) + "/" + (slot[1] + 1);
    }

    /** Is this volume where its record says? */
    static boolean present(ServerLevel level, Ledger.Building b, MuseumRecords.Volume v) {
        if (v.where.equals("lectern")) return titleOf(onLectern(level, b)).equals(v.title);
        if (!v.where.startsWith("shelf ")) return false;
        int[] at = slotOf(v.where);
        if (at == null || !(level.getBlockEntity(shelfAt(b, at[0])) instanceof ChiseledBookShelfBlockEntity shelf)) return false;
        return titleOf(shelf.getItem(at[1])).equals(v.title);
    }

    @Nullable
    static int[] slotOf(String where) {
        try {
            String[] p = where.substring(6).split("/");
            int i = Integer.parseInt(p[0]) - 1, s = Integer.parseInt(p[1]) - 1;
            return i >= 0 && i < SHELVES.length && s >= 0 && s < SLOTS ? new int[]{ i, s } : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Shelve a new volume: it goes open on the lectern, and the one there before onto the shelves;
     * with no lectern, or no room on the shelves for the one it would replace, onto the shelves
     * itself. Returns where it went ("" if nowhere: then nothing was moved).
     */
    static String shelveNewest(ServerLevel level, Ledger.Building b, MuseumRecords.Book book, ItemStack vol, @Nullable LivingEntity by) {
        if (lecternStands(level, b)) {
            ItemStack old = onLectern(level, b);
            if (old.isEmpty()) {
                if (onTheLectern(level, b, vol, by)) return "lectern";
            } else {
                int[] slot = freeSlot(level, b);
                if (slot != null) {
                    ItemStack moved = offTheLectern(level, b);
                    if (onShelf(level, b, slot, moved)) {
                        String t = titleOf(moved);
                        for (MuseumRecords.Volume v : book.volumes) if (v.where.equals("lectern") && v.title.equals(t)) v.where = where(slot);
                        if (onTheLectern(level, b, vol, by)) return "lectern";
                    } else {
                        onTheLectern(level, b, moved, by);              // put back as it was
                    }
                }
            }
        }
        int[] slot = freeSlot(level, b);
        if (slot != null && onShelf(level, b, slot, vol.copy())) return where(slot);
        return "";
    }

    /** A copy put back where the lost one stood if that is free, else onto any free slot. */
    static String shelveCopy(ServerLevel level, Ledger.Building b, MuseumRecords.Volume was, ItemStack vol, @Nullable LivingEntity by) {
        if (was.where.equals("lectern") && lecternStands(level, b) && onLectern(level, b).isEmpty()
                && onTheLectern(level, b, vol, by)) return "lectern";
        int[] at = was.where.startsWith("shelf ") ? slotOf(was.where) : null;
        if (at != null && onShelf(level, b, at, vol.copy())) return where(at);
        int[] slot = freeSlot(level, b);
        if (slot != null && onShelf(level, b, slot, vol.copy())) return where(slot);
        if (lecternStands(level, b) && onLectern(level, b).isEmpty() && onTheLectern(level, b, vol, by)) return "lectern";
        return "";
    }

    /** Does a new volume need a new shelf put up first (no room for it, or for the one it moves off the lectern)? */
    static boolean wantsAShelf(ServerLevel level, Ledger.Building b) {
        if (freeSlot(level, b) != null) return false;
        return !lecternStands(level, b) || !onLectern(level, b).isEmpty();
    }

    /** The whole archive is full: every shelf up and every slot taken, and the lectern too. */
    static boolean full(ServerLevel level, Ledger.Building b) {
        return wantsAShelf(level, b) && shelfToSet(level, b) < 0;
    }

    /** Volumes in words for the status: "3 volumes (Year 1-3), the newest open on the lectern". */
    static String words(MuseumRecords.Book book) {
        if (book.volumes.isEmpty()) return "no volumes yet";
        int first = Integer.MAX_VALUE, last = 0, missing = 0;
        for (MuseumRecords.Volume v : book.volumes) {
            first = Math.min(first, v.year);
            last = Math.max(last, v.year);
            if (v.missing) missing++;
        }
        return book.volumes.size() + (book.volumes.size() == 1 ? " volume" : " volumes") + " (Year " + first
            + (last > first ? " to " + last : "") + ")" + (missing > 0 ? ", " + missing + " taken away" : "");
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
