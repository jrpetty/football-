package com.jrpetty.mcassistant.village;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The library's own books, kept with the world: the catalogue of every book a town has written (its
 * trades' books and every edition of each, its histories, poems, how-to books, lives and storybooks), the
 * words of each so a lost one can be written out again, where each stands, who has one out and till when,
 * the librarian, and what each folk has read. [library]
 *
 * <p>Nothing here is the book itself: the book is on its shelf, open on the lectern, or in a player's pack.
 * This is the librarian's catalogue of them (entity/Library).
 */
public final class LibraryRecords extends SavedData {

    private static final String ID = "mc_assistant_library";
    /** The most books a town's catalogue keeps (the oldest old editions go first). */
    public static final int MOST_BOOKS = 160;

    /** One book in the catalogue. */
    public static final class Title {
        public int id;
        /** TRADE, HISTORY, POEM, HOWTO, LIFE or STORY. */
        public String kind = "";
        public String title = "", author = "", blurb = "", subject = "";
        @Nullable public UUID authorId;
        /** A trade's book: its trade (StationTask's name) and which edition. */
        public String trade = "";
        public int edition;
        public long written;
        /** "shelf N/S" (the Nth shelf place, its slot S), "lectern", "loan" (a player has it), "stores", or "" (waiting for room). */
        public String where = "";
        /** An old edition, kept for what it shows of how things were. */
        public boolean superseded;
        /** Gone from where it stood, and nobody known to have it: waiting to be written out again. */
        public boolean missing;
        public int reads, lent;
        /** A trade book's facts when it was written, so the next edition can say what changed. */
        public String facts = "";
        /** What was new in this edition, a line each. */
        public final List<String> news = new ArrayList<>();
        public final List<String> pages = new ArrayList<>();
    }

    /** A book a player has out: who, which, from when, and when it is due back. */
    public static final class Loan {
        public UUID player;
        public String name = "";
        public int book;
        public long day, due;
        /** Days it has been late so far, as counted each morning. */
        public int late;
    }

    /** What one folk has read: each trade's book and its edition, the other books by number, and the last it read. */
    public static final class Reader {
        public final Map<String, Integer> trades = new LinkedHashMap<>();
        public final List<Integer> books = new ArrayList<>();
        public long lastDay = -1;
        public String lastTitle = "";
    }

    /** One town's library. */
    public static final class Shelf {
        public final List<Title> books = new ArrayList<>();
        public final List<Loan> loans = new ArrayList<>();
        public final Map<UUID, Reader> readers = new HashMap<>();
        public int nextId = 1;
        @Nullable public UUID librarian;
        public String librarianName = "";
        public long librarianSince = -1;
        /** The day the town last finished a book other than a trade's. */
        public long lastBook = -1;
        /** Fines paid in, and books never brought back. */
        public int fines, lost;

        @Nullable
        public Title byId(int id) {
            for (Title t : books) if (t.id == id) return t;
            return null;
        }

        /** A trade's current book, or null. */
        @Nullable
        public Title tradeBook(String trade) {
            Title best = null;
            for (Title t : books) {
                if (!t.kind.equals("TRADE") || !t.trade.equals(trade) || t.superseded) continue;
                if (best == null || t.edition > best.edition) best = t;
            }
            return best;
        }

        public boolean wrote(String subject) {
            for (Title t : books) if (t.subject.equals(subject)) return true;
            return false;
        }

        public Reader reader(UUID folk) {
            return readers.computeIfAbsent(folk, k -> new Reader());
        }
    }

    private final Map<UUID, Shelf> shelves = new HashMap<>();

    @Nullable
    private static LibraryRecords of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return server.overworld().getDataStorage()
            .computeIfAbsent(new SavedData.Factory<>(LibraryRecords::new, LibraryRecords::load, null), ID);
    }

    /** This town's library (an empty one, kept nowhere, with no world to keep it in). */
    public static Shelf shelf(UUID village) {
        LibraryRecords r = of();
        if (r == null) return new Shelf();
        return r.shelves.computeIfAbsent(village, k -> new Shelf());
    }

    /** Has this town a library's books at all? */
    public static boolean has(UUID village) {
        LibraryRecords r = of();
        return r != null && r.shelves.containsKey(village);
    }

    /** Something changed: write it down. */
    public static void touch() {
        LibraryRecords r = of();
        if (r != null) r.setDirty();
    }

    // ------------------------------------------------------------------ keeping

    public static LibraryRecords load(CompoundTag tag, HolderLookup.Provider registries) {
        LibraryRecords r = new LibraryRecords();
        for (Tag t : tag.getList("Villages", Tag.TAG_COMPOUND)) {
            CompoundTag v = (CompoundTag) t;
            if (!v.hasUUID("Id")) continue;
            Shelf s = new Shelf();
            for (Tag e : v.getList("Books", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) e;
                Title b = new Title();
                b.id = c.getInt("Num");
                b.kind = c.getString("Kind");
                b.title = c.getString("Title");
                b.author = c.getString("Author");
                b.blurb = c.getString("Blurb");
                b.subject = c.getString("Subject");
                if (c.hasUUID("AuthorId")) b.authorId = c.getUUID("AuthorId");
                b.trade = c.getString("Trade");
                b.edition = c.getInt("Edition");
                b.written = c.getLong("Written");
                b.where = c.getString("Where");
                b.superseded = c.getBoolean("Old");
                b.missing = c.getBoolean("Missing");
                b.reads = c.getInt("Reads");
                b.lent = c.getInt("Lent");
                b.facts = c.getString("Facts");
                for (Tag n : c.getList("News", Tag.TAG_STRING)) b.news.add(n.getAsString());
                for (Tag p : c.getList("Pages", Tag.TAG_STRING)) b.pages.add(p.getAsString());
                s.books.add(b);
            }
            for (Tag e : v.getList("Loans", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) e;
                if (!c.hasUUID("Player")) continue;
                Loan l = new Loan();
                l.player = c.getUUID("Player");
                l.name = c.getString("Name");
                l.book = c.getInt("Book");
                l.day = c.getLong("Day");
                l.due = c.getLong("Due");
                l.late = c.getInt("Late");
                s.loans.add(l);
            }
            for (Tag e : v.getList("Readers", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) e;
                if (!c.hasUUID("Folk")) continue;
                Reader rd = new Reader();
                CompoundTag trades = c.getCompound("Trades");
                for (String k : trades.getAllKeys()) rd.trades.put(k, trades.getInt(k));
                for (int id : c.getIntArray("Books")) rd.books.add(id);
                rd.lastDay = c.getLong("LastDay");
                rd.lastTitle = c.getString("LastTitle");
                s.readers.put(c.getUUID("Folk"), rd);
            }
            s.nextId = Math.max(1, v.getInt("NextId"));
            if (v.hasUUID("Librarian")) s.librarian = v.getUUID("Librarian");
            s.librarianName = v.getString("LibrarianName");
            s.librarianSince = v.contains("LibrarianSince") ? v.getLong("LibrarianSince") : -1;
            s.lastBook = v.contains("LastBook") ? v.getLong("LastBook") : -1;
            s.fines = v.getInt("Fines");
            s.lost = v.getInt("Lost");
            r.shelves.put(v.getUUID("Id"), s);
        }
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag all = new ListTag();
        for (Map.Entry<UUID, Shelf> e : shelves.entrySet()) {
            Shelf s = e.getValue();
            CompoundTag v = new CompoundTag();
            v.putUUID("Id", e.getKey());
            ListTag books = new ListTag();
            for (Title b : s.books) {
                CompoundTag c = new CompoundTag();
                c.putInt("Num", b.id);
                c.putString("Kind", b.kind);
                c.putString("Title", b.title);
                c.putString("Author", b.author);
                c.putString("Blurb", b.blurb);
                c.putString("Subject", b.subject);
                if (b.authorId != null) c.putUUID("AuthorId", b.authorId);
                c.putString("Trade", b.trade);
                c.putInt("Edition", b.edition);
                c.putLong("Written", b.written);
                c.putString("Where", b.where);
                c.putBoolean("Old", b.superseded);
                c.putBoolean("Missing", b.missing);
                c.putInt("Reads", b.reads);
                c.putInt("Lent", b.lent);
                c.putString("Facts", b.facts);
                ListTag news = new ListTag();
                for (String n : b.news) news.add(StringTag.valueOf(n));
                c.put("News", news);
                ListTag pages = new ListTag();
                for (String p : b.pages) pages.add(StringTag.valueOf(p));
                c.put("Pages", pages);
                books.add(c);
            }
            v.put("Books", books);
            ListTag loans = new ListTag();
            for (Loan l : s.loans) {
                CompoundTag c = new CompoundTag();
                c.putUUID("Player", l.player);
                c.putString("Name", l.name);
                c.putInt("Book", l.book);
                c.putLong("Day", l.day);
                c.putLong("Due", l.due);
                c.putInt("Late", l.late);
                loans.add(c);
            }
            v.put("Loans", loans);
            ListTag readers = new ListTag();
            for (Map.Entry<UUID, Reader> re : s.readers.entrySet()) {
                CompoundTag c = new CompoundTag();
                c.putUUID("Folk", re.getKey());
                CompoundTag trades = new CompoundTag();
                for (Map.Entry<String, Integer> t : re.getValue().trades.entrySet()) trades.putInt(t.getKey(), t.getValue());
                c.put("Trades", trades);
                int[] ids = new int[re.getValue().books.size()];
                for (int i = 0; i < ids.length; i++) ids[i] = re.getValue().books.get(i);
                c.putIntArray("Books", ids);
                c.putLong("LastDay", re.getValue().lastDay);
                c.putString("LastTitle", re.getValue().lastTitle);
                readers.add(c);
            }
            v.put("Readers", readers);
            v.putInt("NextId", s.nextId);
            if (s.librarian != null) v.putUUID("Librarian", s.librarian);
            v.putString("LibrarianName", s.librarianName);
            v.putLong("LibrarianSince", s.librarianSince);
            v.putLong("LastBook", s.lastBook);
            v.putInt("Fines", s.fines);
            v.putInt("Lost", s.lost);
            all.add(v);
        }
        tag.put("Villages", all);
        return tag;
    }
}
