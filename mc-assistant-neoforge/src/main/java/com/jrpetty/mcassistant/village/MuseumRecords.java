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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The museum's own books, kept with the world: what each village has on show and who found it,
 * the finds its folk brought home (the first of each kind, so a label can say who found it and
 * when), its curator, the volumes of its chronicle bound into books and where they stand, and the
 * years of the chronicle written down out of it before the chronicle forgets them (it keeps four
 * hundred lines, and loses the middle first).
 *
 * <p>Nothing here is the thing itself: the diamond is in its frame, the volume on its shelf. This is
 * the curator's catalogue of them (entity/Museum, entity/Archive).
 */
public final class MuseumRecords extends SavedData {

    private static final String ID = "mc_assistant_museum";
    /** The finds kept of each kind: the first few, so the first is never lost. */
    private static final int FINDS_A_KIND = 3;
    /** The years of notes kept waiting for the binder, and the volumes whose text is kept to copy from. */
    public static final int MOST_NOTES = 24, MOST_VOLUMES = 60;

    /** Something on show: what it is, who found it and when, and where it stands. */
    public static final class Shown {
        public String kind = "";
        /** The item on show, by its registry id. */
        public String item = "";
        /** What it is, in words: "the town's first diamond". */
        public String words = "";
        /** The first line of its label: "Diamond". */
        public String label = "";
        public String finder = "";
        @Nullable public UUID finderId;
        /** The finder's trade, in a word ("miner"), or empty. */
        public String trade = "";
        /** How it came: "found", "mined", "fished up", "made". */
        public String how = "";
        public long found = -1;
        public long shown;
        public int renown;
        /** Which of the museum's places it stands in (entity/Museum.PLACES). */
        public int place = -1;
        /** The frame or stand holding it, if it is held by one. */
        @Nullable public UUID holder;
        /** Looked for where it should be and not there, in a row. */
        public int misses;
    }

    /** One of a village's finds: what, who found it, at what trade, how, and when. */
    public static final class Found {
        public String kind = "";
        @Nullable public UUID finderId;
        public String finder = "";
        public String trade = "";
        public String how = "";
        public long day;
    }

    /** A year of the chronicle, written down out of it: waiting to be bound. */
    public static final class Notes {
        public int year;
        public long from, to;
        public final List<Chronicle.Entry> entries = new ArrayList<>();
    }

    /** A volume of the chronicle bound into a book: its year, its title, its pages and where it stands. */
    public static final class Volume {
        public int year, part = 1, parts = 1;
        public String title = "", author = "";
        public long from, to, written;
        public int entries;
        /** 0 for the one first written, 1 for a fair copy written out again. */
        public int generation;
        /** "lectern", "shelf N/S" (the Nth shelf, slot S), or "" while it is nowhere. */
        public String where = "";
        public boolean missing;
        public final List<String> pages = new ArrayList<>();
    }

    /** One village's museum. */
    public static final class Book {
        public final List<Shown> shown = new ArrayList<>();
        public final List<Found> finds = new ArrayList<>();
        public final List<Volume> volumes = new ArrayList<>();
        public final List<Notes> notes = new ArrayList<>();
        /** The last year of the chronicle written down (0: none yet). */
        public int yearsNoted;
        @Nullable public UUID curator;
        public String curatorName = "";
        public long curatorSince = -1;
        /** What the curator has out of the stores, while it carries it (put back if the world stops). */
        public final List<CompoundTag> carrying = new ArrayList<>();
        /** The atlas's finds already drawn as maps (by label and day). */
        public final List<String> mapped = new ArrayList<>();
        /** How many exhibits have ever gone missing. */
        public int lost;
    }

    private final Map<UUID, Book> books = new HashMap<>();

    @Nullable
    private static MuseumRecords of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return server.overworld().getDataStorage()
            .computeIfAbsent(new SavedData.Factory<>(MuseumRecords::new, MuseumRecords::load, null), ID);
    }

    /** This village's museum books (an empty one, kept nowhere, with no world to keep it in). */
    public static Book book(UUID village) {
        MuseumRecords r = of();
        if (r == null) return new Book();
        return r.books.computeIfAbsent(village, k -> new Book());
    }

    /** Has this village any museum books at all? */
    public static boolean has(UUID village) {
        MuseumRecords r = of();
        return r != null && r.books.containsKey(village);
    }

    /** Something in a book changed: write it down. */
    public static void touch() {
        MuseumRecords r = of();
        if (r != null) r.setDirty();
    }

    /**
     * A find, into the village's books. Returns true if it was the first of its kind the village
     * ever had. Only the first few of each kind are kept.
     */
    public static boolean noteFind(UUID village, String kind, @Nullable UUID finderId, String finder, String trade, String how, long day) {
        Book b = book(village);
        int same = 0;
        for (Found f : b.finds) if (f.kind.equals(kind)) same++;
        if (same >= FINDS_A_KIND) return false;
        Found f = new Found();
        f.kind = kind;
        f.finderId = finderId;
        f.finder = finder;
        f.trade = trade;
        f.how = how;
        f.day = day;
        b.finds.add(f);
        touch();
        return same == 0;
    }

    /** The first find of this kind the village has a record of, or null. */
    @Nullable
    public static Found firstFind(UUID village, String kind) {
        for (Found f : book(village).finds) if (f.kind.equals(kind)) return f;
        return null;
    }

    // ------------------------------------------------------------------ keeping

    public static MuseumRecords load(CompoundTag tag, HolderLookup.Provider registries) {
        MuseumRecords r = new MuseumRecords();
        for (Tag t : tag.getList("Villages", Tag.TAG_COMPOUND)) {
            CompoundTag v = (CompoundTag) t;
            if (!v.hasUUID("Id")) continue;
            Book b = new Book();
            for (Tag e : v.getList("Shown", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) e;
                Shown s = new Shown();
                s.kind = c.getString("Kind");
                s.item = c.getString("Item");
                s.words = c.getString("Words");
                s.label = c.getString("Label");
                s.finder = c.getString("Finder");
                if (c.hasUUID("FinderId")) s.finderId = c.getUUID("FinderId");
                s.trade = c.getString("Trade");
                s.how = c.getString("How");
                s.found = c.getLong("Found");
                s.shown = c.getLong("Shown");
                s.renown = c.getInt("Renown");
                s.place = c.getInt("Place");
                if (c.hasUUID("Holder")) s.holder = c.getUUID("Holder");
                s.misses = c.getInt("Misses");
                b.shown.add(s);
            }
            for (Tag e : v.getList("Finds", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) e;
                Found f = new Found();
                f.kind = c.getString("Kind");
                if (c.hasUUID("FinderId")) f.finderId = c.getUUID("FinderId");
                f.finder = c.getString("Finder");
                f.trade = c.getString("Trade");
                f.how = c.getString("How");
                f.day = c.getLong("Day");
                b.finds.add(f);
            }
            for (Tag e : v.getList("Volumes", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) e;
                Volume vol = new Volume();
                vol.year = c.getInt("Year");
                vol.part = Math.max(1, c.getInt("Part"));
                vol.parts = Math.max(1, c.getInt("Parts"));
                vol.title = c.getString("Title");
                vol.author = c.getString("Author");
                vol.from = c.getLong("From");
                vol.to = c.getLong("To");
                vol.written = c.getLong("Written");
                vol.entries = c.getInt("Entries");
                vol.generation = c.getInt("Generation");
                vol.where = c.getString("Where");
                vol.missing = c.getBoolean("Missing");
                for (Tag p : c.getList("Pages", Tag.TAG_STRING)) vol.pages.add(p.getAsString());
                b.volumes.add(vol);
            }
            for (Tag e : v.getList("Notes", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) e;
                Notes n = new Notes();
                n.year = c.getInt("Year");
                n.from = c.getLong("From");
                n.to = c.getLong("To");
                for (Tag l : c.getList("Lines", Tag.TAG_COMPOUND)) {
                    CompoundTag one = (CompoundTag) l;
                    n.entries.add(new Chronicle.Entry(one.getLong("Day"), one.getString("Text")));
                }
                b.notes.add(n);
            }
            b.yearsNoted = v.getInt("YearsNoted");
            if (v.hasUUID("Curator")) b.curator = v.getUUID("Curator");
            b.curatorName = v.getString("CuratorName");
            b.curatorSince = v.contains("CuratorSince") ? v.getLong("CuratorSince") : -1;
            for (Tag e : v.getList("Carrying", Tag.TAG_COMPOUND)) b.carrying.add(((CompoundTag) e).copy());
            for (Tag e : v.getList("Mapped", Tag.TAG_STRING)) b.mapped.add(e.getAsString());
            b.lost = v.getInt("Lost");
            r.books.put(v.getUUID("Id"), b);
        }
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag all = new ListTag();
        for (Map.Entry<UUID, Book> e : books.entrySet()) {
            Book b = e.getValue();
            CompoundTag v = new CompoundTag();
            v.putUUID("Id", e.getKey());
            ListTag shown = new ListTag();
            for (Shown s : b.shown) {
                CompoundTag c = new CompoundTag();
                c.putString("Kind", s.kind);
                c.putString("Item", s.item);
                c.putString("Words", s.words);
                c.putString("Label", s.label);
                c.putString("Finder", s.finder);
                if (s.finderId != null) c.putUUID("FinderId", s.finderId);
                c.putString("Trade", s.trade);
                c.putString("How", s.how);
                c.putLong("Found", s.found);
                c.putLong("Shown", s.shown);
                c.putInt("Renown", s.renown);
                c.putInt("Place", s.place);
                if (s.holder != null) c.putUUID("Holder", s.holder);
                c.putInt("Misses", s.misses);
                shown.add(c);
            }
            v.put("Shown", shown);
            ListTag finds = new ListTag();
            for (Found f : b.finds) {
                CompoundTag c = new CompoundTag();
                c.putString("Kind", f.kind);
                if (f.finderId != null) c.putUUID("FinderId", f.finderId);
                c.putString("Finder", f.finder);
                c.putString("Trade", f.trade);
                c.putString("How", f.how);
                c.putLong("Day", f.day);
                finds.add(c);
            }
            v.put("Finds", finds);
            ListTag vols = new ListTag();
            for (Volume vol : b.volumes) {
                CompoundTag c = new CompoundTag();
                c.putInt("Year", vol.year);
                c.putInt("Part", vol.part);
                c.putInt("Parts", vol.parts);
                c.putString("Title", vol.title);
                c.putString("Author", vol.author);
                c.putLong("From", vol.from);
                c.putLong("To", vol.to);
                c.putLong("Written", vol.written);
                c.putInt("Entries", vol.entries);
                c.putInt("Generation", vol.generation);
                c.putString("Where", vol.where);
                c.putBoolean("Missing", vol.missing);
                ListTag pages = new ListTag();
                for (String p : vol.pages) pages.add(StringTag.valueOf(p));
                c.put("Pages", pages);
                vols.add(c);
            }
            v.put("Volumes", vols);
            ListTag notes = new ListTag();
            for (Notes n : b.notes) {
                CompoundTag c = new CompoundTag();
                c.putInt("Year", n.year);
                c.putLong("From", n.from);
                c.putLong("To", n.to);
                ListTag lines = new ListTag();
                for (Chronicle.Entry en : n.entries) {
                    CompoundTag one = new CompoundTag();
                    one.putLong("Day", en.day());
                    one.putString("Text", en.text());
                    lines.add(one);
                }
                c.put("Lines", lines);
                notes.add(c);
            }
            v.put("Notes", notes);
            v.putInt("YearsNoted", b.yearsNoted);
            if (b.curator != null) v.putUUID("Curator", b.curator);
            v.putString("CuratorName", b.curatorName);
            v.putLong("CuratorSince", b.curatorSince);
            ListTag carrying = new ListTag();
            for (CompoundTag c : b.carrying) carrying.add(c.copy());
            v.put("Carrying", carrying);
            ListTag mapped = new ListTag();
            for (String m : b.mapped) mapped.add(StringTag.valueOf(m));
            v.put("Mapped", mapped);
            v.putInt("Lost", b.lost);
            all.add(v);
        }
        tag.put("Villages", all);
        return tag;
    }
}
