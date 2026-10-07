package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.LibraryRecords;
import com.jrpetty.mcassistant.village.LibraryRecords.Shelf;
import com.jrpetty.mcassistant.village.LibraryRecords.Title;
import com.jrpetty.mcassistant.village.Quill;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The town library: a building of real books, written in the town, that anybody may read and borrow. [library]
 *
 * <p><b>The building.</b> A Stone Age town of twelve plans a library among its amenities, on a lot facing the square
 * (drawn in townlibrary.txt): a stone-footed hall with tall windows, two bookshelves either side of a lectern at the
 * back, a reading table with four chairs in the middle, and two writing desks by the door. Its real books stand on
 * chiseled bookshelves, sixteen places for them round the walls, each put up out of the stores' planks and slabs as
 * the books want room (six to a shelf); the newest book lies open on the lectern.
 *
 * <p><b>The librarian</b> keeps it: the reader, the curious, or at first the teacher, chosen once and told in the
 * chronicle. It lends the books, takes them back, chases the late ones and writes out again any that are lost.
 *
 * <p><b>The books</b> are written at the desks, in the writers' own time (an evening, or the day of rest), each on a
 * book and quill out of the stores (a book, a feather and an ink sac by the game's own recipe; the book of paper made
 * of the town's cane and leather off its herds, made the whole way if it must be: Bench). Nothing is written on
 * nothing: with no ink, no book. They are:
 * <ul>
 * <li>each trade's book of best practice, kept by its master and brought up to date as the town learns (TradeBooks,
 * TradeBookWriter), every old edition kept on the shelf;</li>
 * <li>the histories, poems, how-to books, lives and children's storybooks the town's writers write of its real events
 * (Authors, Verse, Tales).</li>
 * </ul>
 *
 * <p><b>Reading.</b> Folk come of an evening (a child of an afternoon) and read in the chairs: the apprentice its
 * trade's book (it learns its trade a quarter quicker for having read it while it is below level ten), the master the
 * new edition (a little quicker at the work: four in the hundred for the current edition, two for an older one), the
 * reader whatever is new, a child the teacher's storybooks (a little of the trade it leans to).
 *
 * <p><b>Borrowing.</b> A player asks the librarian for a book ("could I borrow the Farmer's Book?"), or takes one off
 * the shelf in the library, and has three days to bring it back (to the librarian, or onto a shelf). Late, it is two
 * coins a day, ten at most, and the library thinks the less of them; five days late it is given up as lost, the ten
 * put on what they owe the town, and the librarian writes it out again. A copy of its own may be bought.
 */
public final class Library {

    private Library() {}

    private static final Logger LOG = LogUtils.getLogger();

    public static final String STRUCTURE = "townlibrary";
    /** A town plans a library from this many folk (and the Stone Age). */
    public static final int FROM_FOLK = 12;
    /** Days a player may keep a book; days late before it is given up as lost; the fine a day late, and at most; books out at once. */
    public static final int LOAN_DAYS = 3, LOST_AFTER = 5, FINE_A_DAY = 2, MOST_FINE = 10, MOST_LOANS = 2;
    /** What a copy of a book costs a player. */
    public static final int COPY_PRICE = 6;
    /** Below this level at its trade a folk is an apprentice, and learns the quicker for having read its trade's book. */
    public static final int APPRENTICE_LEVEL = 10;
    /** How much quicker an apprentice who has read its trade's book learns, in the hundred. */
    public static final int STUDY_PERCENT = 25;
    /** How much quicker a folk who has read its trade's book works: the current edition, an older one. */
    public static final int PACE_NEW = 4, PACE_OLD = 2;
    /** Days between the town's books (other than a trade's), at the least. */
    static final int BOOK_GAP = 2;
    /** How long a book takes to write, and to read, at the desk and in the chair (ticks). */
    static final int WRITE_TICKS = 900, READ_TICKS = 500;
    /** The mark a library book carries: its town and its number in the catalogue. */
    static final String MARK = "mca_library";

    // ------------------------------------------------------------------ the state

    /** A piece of writing under way: a trade's book, another book, or a lost one written out again. */
    static final class Job {
        final UUID village, folk;
        final String words;
        final long since;
        @Nullable final TradeBooks.Draft draft;
        @Nullable final Authors.Idea idea;
        final int copyOf;
        int stage;
        long writingSince;
        int walkTick = -1000;
        @Nullable BlockPos seat;
        boolean engaged;

        Job(UUID village, UUID folk, String words, long since, @Nullable TradeBooks.Draft draft, @Nullable Authors.Idea idea, int copyOf) {
            this.village = village;
            this.folk = folk;
            this.words = words;
            this.since = since;
            this.draft = draft;
            this.idea = idea;
            this.copyOf = copyOf;
        }
    }

    /** A folk reading in the library: which book, where it sits, and how far it has got. */
    static final class Visit {
        final UUID village;
        final int book;
        final long started;
        int stage;
        long readingSince;
        int walkTick = -1000;
        @Nullable BlockPos seat;
        float yaw;
        boolean engaged;

        Visit(UUID village, int book, long started) {
            this.village = village;
            this.book = book;
            this.started = started;
        }
    }

    private static final Map<UUID, Long> TICKED = new ConcurrentHashMap<>(), VERIFIED = new ConcurrentHashMap<>(), PLANNED = new ConcurrentHashMap<>(),
        OVERDUE = new ConcurrentHashMap<>();
    private static final Map<UUID, Job> JOBS = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> ON = new ConcurrentHashMap<>();
    private static final Map<UUID, Visit> VISITS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> VISITED = new ConcurrentHashMap<>();
    private static final Map<Long, UUID> SEATS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SHORT = new ConcurrentHashMap<>();
    private static final Map<UUID, int[]> PACE = new ConcurrentHashMap<>();
    private static volatile boolean instant;

    public static void resetForTests() {
        TICKED.clear();
        VERIFIED.clear();
        PLANNED.clear();
        OVERDUE.clear();
        JOBS.clear();
        ON.clear();
        VISITS.clear();
        VISITED.clear();
        SEATS.clear();
        SHORT.clear();
        PACE.clear();
        instant = false;
    }

    /** Tests: the library's work done at once, with no walking and no waiting for an evening. */
    public static void instantForTests(boolean on) {
        instant = on;
    }

    // ------------------------------------------------------------------ the building

    /** Is a library wanted (Villages.projectsWantedInOrder, from the Stone Age): twelve folk, and none standing. */
    public static boolean wanted(UUID village, int folk) {
        return folk >= FROM_FOLK && !Villages.hasBuilt(village, STRUCTURE);
    }

    /** Why the town wants one, in a line (Villages.whyBuild). */
    public static String why(UUID village) {
        int trades = 0;
        java.util.Set<StationTask> seen = java.util.EnumSet.noneOf(StationTask.class);
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.stationTask() != StationTask.NONE && seen.add(f.stationTask())) trades++;
        }
        return "a library: " + Villages.headcount(village) + " folk, " + trades + (trades == 1 ? " trade" : " trades")
            + " with all it knows in nobody's head but its master's, and nowhere to keep a book";
    }

    /** The town's library, if one stands. */
    @Nullable
    public static Ledger.Building building(UUID village) {
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals(STRUCTURE)) return b;
        return null;
    }

    /** Does the town have a library standing? */
    public static boolean stands(UUID village) {
        return building(village) != null;
    }

    /** A spot in the library by its drawing: across (right +), up, and toward the back (+). */
    static BlockPos at(Ledger.Building b, int dx, int h, int dz) {
        Direction back = b.facing(), right = back.getClockWise();
        return b.anchor().relative(right, dx).relative(back, dz).above(h);
    }

    /** The drawing's places: the lectern {dx, dz}, the reading chairs and the writing stools {dx, dz, faces right (1) / left (-1) / back (0)}. */
    private static volatile int[] lecternCell;
    private static volatile List<int[]> chairs, stools;

    private static void readDrawing() {
        if (lecternCell != null) return;
        int[] lec = { 0, 3 };
        List<int[]> ch = new ArrayList<>(), st = new ArrayList<>();
        for (Blueprints.Cell c : Blueprints.cells(STRUCTURE)) {
            if (c.h() != 0) continue;
            if (c.key().part() == BuildGoal.Part.LECTERN) lec = new int[]{ c.dx(), c.dz() };
            if (c.key().part() == BuildGoal.Part.BLOCK && c.key().style() == Blueprints.Style.ROOF_STAIR) {
                switch (c.key().way()) {
                    case LEFT -> ch.add(new int[]{ c.dx(), c.dz(), 1 });         // its back to the left: sat facing right
                    case RIGHT -> ch.add(new int[]{ c.dx(), c.dz(), -1 });
                    case FRONT -> st.add(new int[]{ c.dx(), c.dz(), 0 });        // its back to the front: sat facing the desk
                    default -> { }
                }
            }
        }
        chairs = ch;
        stools = st;
        lecternCell = lec;
    }

    static BlockPos lecternAt(Ledger.Building b) {
        readDrawing();
        return at(b, lecternCell[0], 0, lecternCell[1]);
    }

    /**
     * The places for the library's shelves, from the lectern: four either side of it along the back wall (two
     * across, two high), and four down each side wall (two along, two high). Sixteen in all, six books to each.
     */
    static final int[][] SHELF_PLACES = {
        { -2, 0, 0 }, { 2, 0, 0 }, { -3, 0, 0 }, { 3, 0, 0 }, { -2, 1, 0 }, { 2, 1, 0 }, { -3, 1, 0 }, { 3, 1, 0 },
        { -3, 0, -2 }, { 3, 0, -2 }, { -3, 0, -3 }, { 3, 0, -3 }, { -3, 1, -2 }, { 3, 1, -2 }, { -3, 1, -3 }, { 3, 1, -3 } };
    static final int SLOTS = ChiseledBookShelfBlockEntity.MAX_BOOKS_IN_STORAGE;

    static BlockPos shelfAt(Ledger.Building b, int i) {
        readDrawing();
        int[] s = SHELF_PLACES[i];
        return at(b, lecternCell[0] + s[0], s[1], lecternCell[1] + s[2]);
    }

    /** Which way a shelf faces: into the hall from the back wall, across it from a side wall. */
    static Direction shelfFacing(Ledger.Building b, int i) {
        int[] s = SHELF_PLACES[i];
        if (s[2] == 0) return b.facing().getOpposite();
        return s[0] < 0 ? b.facing().getClockWise() : b.facing().getCounterClockWise();
    }

    static boolean lecternStands(ServerLevel level, Ledger.Building b) {
        return level.getBlockState(lecternAt(b)).getBlock() instanceof LecternBlock;
    }

    static ItemStack onLectern(ServerLevel level, Ledger.Building b) {
        return level.getBlockEntity(lecternAt(b)) instanceof LecternBlockEntity l ? l.getBook() : ItemStack.EMPTY;
    }

    /** A free slot on a shelf that stands: {shelf, slot}, or null. */
    @Nullable
    static int[] freeSlot(ServerLevel level, Ledger.Building b) {
        for (int i = 0; i < SHELF_PLACES.length; i++) {
            if (!(level.getBlockEntity(shelfAt(b, i)) instanceof ChiseledBookShelfBlockEntity shelf)) continue;
            for (int s = 0; s < SLOTS; s++) if (shelf.getItem(s).isEmpty()) return new int[]{ i, s };
        }
        return null;
    }

    /** Where a new shelf can go: the first shelf place with nothing in it, or -1. */
    static int shelfToSet(ServerLevel level, Ledger.Building b) {
        for (int i = 0; i < SHELF_PLACES.length; i++) {
            BlockPos p = shelfAt(b, i);
            if (level.getBlockEntity(p) instanceof ChiseledBookShelfBlockEntity) continue;
            if (level.getBlockState(p).canBeReplaced()) return i;
        }
        return -1;
    }

    /** A chiseled bookshelf set in its place, facing the hall. */
    static boolean setShelf(ServerLevel level, Ledger.Building b, int i) {
        BlockPos p = shelfAt(b, i);
        if (!level.getBlockState(p).canBeReplaced()) return false;
        level.setBlock(p, Blocks.CHISELED_BOOKSHELF.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, shelfFacing(b, i)), 3);
        level.playSound(null, p, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
        return true;
    }

    /** The lectern set where the drawing has it, facing the hall (when the builders had none for it). */
    static boolean setLectern(ServerLevel level, Ledger.Building b) {
        BlockPos p = lecternAt(b);
        if (lecternStands(level, b)) return true;
        if (!level.getBlockState(p).canBeReplaced()) return false;
        level.setBlock(p, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, b.facing().getOpposite()), 3);
        return true;
    }

    static String where(int[] slot) {
        return "shelf " + (slot[0] + 1) + "/" + (slot[1] + 1);
    }

    @Nullable
    static int[] slotOf(String where) {
        if (!where.startsWith("shelf ")) return null;
        try {
            String[] p = where.substring(6).split("/");
            int i = Integer.parseInt(p[0]) - 1, s = Integer.parseInt(p[1]) - 1;
            return i >= 0 && i < SHELF_PLACES.length && s >= 0 && s < SLOTS ? new int[]{ i, s } : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** What stands at a place ("lectern", "shelf N/S"), or empty. */
    static ItemStack itemAt(ServerLevel level, Ledger.Building b, String where) {
        if (where.equals("lectern")) return onLectern(level, b);
        int[] at = slotOf(where);
        if (at == null || !(level.getBlockEntity(shelfAt(b, at[0])) instanceof ChiseledBookShelfBlockEntity shelf)) return ItemStack.EMPTY;
        return shelf.getItem(at[1]);
    }

    /** The book taken out of its place, or empty. */
    static ItemStack takeFrom(ServerLevel level, Ledger.Building b, String where) {
        if (where.equals("lectern")) {
            BlockPos p = lecternAt(b);
            BlockState st = level.getBlockState(p);
            if (!(level.getBlockEntity(p) instanceof LecternBlockEntity l) || !l.hasBook()) return ItemStack.EMPTY;
            ItemStack book = l.getBook().copy();
            l.clearContent();
            LecternBlock.resetBookState(null, level, p, st, false);
            return book;
        }
        int[] at = slotOf(where);
        if (at == null || !(level.getBlockEntity(shelfAt(b, at[0])) instanceof ChiseledBookShelfBlockEntity shelf)) return ItemStack.EMPTY;
        ItemStack s = shelf.removeItem(at[1], 1);
        shelf.setChanged();
        if (!s.isEmpty()) level.playSound(null, shelfAt(b, at[0]), SoundEvents.CHISELED_BOOKSHELF_PICKUP, SoundSource.BLOCKS, 0.8F, 1.0F);
        return s;
    }

    static boolean onShelf(ServerLevel level, Ledger.Building b, int[] at, ItemStack book) {
        if (!(level.getBlockEntity(shelfAt(b, at[0])) instanceof ChiseledBookShelfBlockEntity shelf)) return false;
        if (!shelf.getItem(at[1]).isEmpty()) return false;
        shelf.setItem(at[1], book);
        shelf.setChanged();
        level.playSound(null, shelfAt(b, at[0]), SoundEvents.CHISELED_BOOKSHELF_INSERT, SoundSource.BLOCKS, 0.8F, 1.0F);
        return true;
    }

    static boolean onTheLectern(ServerLevel level, Ledger.Building b, ItemStack book, @Nullable LivingEntity by) {
        BlockPos p = lecternAt(b);
        BlockState st = level.getBlockState(p);
        if (!(st.getBlock() instanceof LecternBlock) || st.getValue(LecternBlock.HAS_BOOK)) return false;
        return LecternBlock.tryPlaceBook(by, level, p, st, book.copy());
    }

    /**
     * A book onto the shelves: a new one open on the lectern (the one there before onto a shelf), else onto any free
     * slot; a shelf put up first out of the stores if there is no room (and the stores can run to one). Returns where
     * it went, or "" if nowhere (it waits in the catalogue for room).
     */
    static String shelve(ServerLevel level, Villages.Village v, Ledger.Building b, Shelf shelf, ItemStack book, boolean newest,
                         @Nullable VillageFolkEntity by) {
        boolean lecternFree = lecternStands(level, b) && onLectern(level, b).isEmpty();
        if (freeSlot(level, b) == null && !lecternFree) makeRoom(level, v, b, by);         // no room, and none on the lectern
        if (newest && lecternStands(level, b)) {
            ItemStack old = onLectern(level, b);
            if (old.isEmpty()) {
                if (onTheLectern(level, b, book, by)) return "lectern";
            } else {
                int[] slot = freeSlot(level, b);
                if (slot != null) {
                    ItemStack moved = takeFrom(level, b, "lectern");
                    if (onShelf(level, b, slot, moved)) {
                        Title t = shelf.byId(idOf(moved, v.id()));
                        if (t != null) t.where = where(slot);
                        if (onTheLectern(level, b, book, by)) return "lectern";
                    } else {
                        onTheLectern(level, b, moved, by);
                    }
                }
            }
        }
        int[] slot = freeSlot(level, b);
        if (slot != null && onShelf(level, b, slot, book.copy())) return where(slot);
        if (lecternStands(level, b) && onLectern(level, b).isEmpty() && onTheLectern(level, b, book, by)) return "lectern";
        return "";
    }

    /** A new shelf (or the lectern) put up out of the stores, if the library has no room and a place for one. */
    static boolean makeRoom(ServerLevel level, Villages.Village v, Ledger.Building b, @Nullable VillageFolkEntity by) {
        Bench.Hand hand = Bench.handOf(level, v, by, STRUCTURE);
        if (!lecternStands(level, b) && level.getBlockState(lecternAt(b)).canBeReplaced()) {
            Bench.Plan plan = Bench.plan(level, v, List.of(Bench.Want.of(Items.LECTERN, 1)), hand);
            if (plan.ok() && Bench.take(level, v, plan, by) && setLectern(level, b)) return true;
        }
        int i = shelfToSet(level, b);
        if (i < 0) return false;
        Bench.Plan plan = Bench.plan(level, v, List.of(Bench.Want.of(Items.CHISELED_BOOKSHELF, 1)), hand);
        if (!plan.ok()) {
            SHORT.put(v.id(), plan.shortOf + " for a new shelf");
            return false;
        }
        return Bench.take(level, v, plan, by) && setShelf(level, b, i);
    }

    // ------------------------------------------------------------------ the books as things

    /** A library book: the written book, with the mark that says whose and which. */
    static ItemStack item(UUID village, Title t, int generation) {
        ItemStack book = Archive.book(t.title, t.author, generation, t.pages);
        CompoundTag tag = new CompoundTag();
        tag.putString(MARK, village + "#" + t.id);
        book.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return book;
    }

    /** Is this one of a library's books (any town's)? */
    public static boolean isLibraryBook(ItemStack s) {
        return !s.isEmpty() && s.is(Items.WRITTEN_BOOK) && s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().contains(MARK);
    }

    /** Its number in this town's catalogue, or -1 if it is not this town's. */
    static int idOf(ItemStack s, UUID village) {
        if (!isLibraryBook(s)) return -1;
        String m = s.get(DataComponents.CUSTOM_DATA).copyTag().getString(MARK);
        int hash = m.indexOf('#');
        if (hash < 0 || !m.substring(0, hash).equals(village.toString())) return -1;
        try { return Integer.parseInt(m.substring(hash + 1)); } catch (NumberFormatException e) { return -1; }
    }

    // ------------------------------------------------------------------ the librarian

    /** The librarian: loaded, or null; chosen if there is none (or it is gone for good). */
    @Nullable
    static VillageFolkEntity librarian(ServerLevel level, UUID id, Shelf shelf, long day) {
        if (shelf.librarian != null) {
            if (level.getEntity(shelf.librarian) instanceof VillageFolkEntity f && f.isAlive() && id.equals(f.ownerId())) return f;
            for (AssistantEntity a : Villages.folkOf(id)) if (a.getUUID().equals(shelf.librarian)) return null;   // away, or out of reach
        }
        VillageFolkEntity pick = chooseLibrarian(id);
        if (pick == null) return null;
        if (Interviews.vacancy(level, id, "librarian", pick)) return null;     // [interviews] the post held open for its interview
        String was = shelf.librarianName;
        shelf.librarian = pick.getUUID();
        shelf.librarianName = pick.displayNameCap();
        shelf.librarianSince = day;
        LibraryRecords.touch();
        Villages.tell(id, day, pick.displayNameCap() + " was made the town's librarian" + (School.isTeacher(pick) ? ", besides teaching" : "")
            + (was.isEmpty() || was.equals(pick.displayNameCap()) ? "" : ", in " + was + "'s place"));
        pick.persona().remember(day, "I was made the town's librarian", 5);
        if (!instant) FolkTalk.speak(pick, FolkTalk.pick(pick.getRandom(), "The librarian! I'll look after every book as if I'd written it.",
            "Me, keep the library? Gladly. Mind you bring the books back."));
        return pick;
    }

    /** The folk with the most love of books: a reader, the curious, a diarist, at first the teacher; never the watch. */
    @Nullable
    static VillageFolkEntity chooseLibrarian(UUID village) {
        VillageFolkEntity best = null;
        double bestScore = -Double.MAX_VALUE;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || !f.isAlive() || f.isBaby() || f.isShowcase() || f.isHired()) continue;
            double s = f.ageYears() * 0.3 + Interviews.preferred(village, "librarian", f);   // [interviews] the panel's choice first
            if (f.persona().rolled() && f.persona().hobby() == Persona.Hobby.READING) s += 30;
            if (f.life().has(Social.Trait.CURIOUS)) s += 20;
            if (f.persona().quirk().equals("keeps a diary")) s += 10;
            if (School.isTeacher(f)) s += 15;
            if (f.stationTask() == StationTask.GUARD || f.stationTask() == StationTask.SCOUT) s -= 40;
            if (f.isElder()) s -= 15;
            if (Museum.isCurator(f)) s -= 10;
            if (s > bestScore) { bestScore = s; best = f; }
        }
        return best;
    }

    /** Is this folk the town's librarian? */
    public static boolean isLibrarian(VillageFolkEntity f) {
        UUID id = f.ownerId();
        return id != null && LibraryRecords.has(id) && f.getUUID().equals(LibraryRecords.shelf(id).librarian) && stands(id);
    }

    // ------------------------------------------------------------------ the beat

    /** The library's beat: run from the folk's own, a few times a minute for the whole town. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = TICKED.get(id);
        if (!instant && last != null && now - last < 100 && now >= last) return;
        TICKED.put(id, now);
        try {
            beat(level, v, now);
        } catch (RuntimeException ex) {
            LOG.warn("[MCA-LIBRARY] {}: {}", Villages.name(id), ex.toString());
        }
    }

    /** Tests: one beat now. */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        TICKED.remove(v.id());
        VERIFIED.remove(v.id());
        PLANNED.remove(v.id());
        tick(level, v);
    }

    private static void beat(ServerLevel level, Villages.Village v, long now) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Ledger.Building b = building(id);
        if (b == null || !level.isLoaded(b.anchor())) return;
        Shelf shelf = LibraryRecords.shelf(id);
        VillageFolkEntity librarian = librarian(level, id, shelf, day);
        // The shelves looked over every half minute; every ten seconds with a player about the library, so a book a player
        // takes off a shelf is known for theirs while they are still near.
        Long verified = VERIFIED.get(id);
        boolean playerNear = level.getNearestPlayer(b.anchor().getX() + 0.5, b.anchor().getY() + 1, b.anchor().getZ() + 0.5, 32.0, false) != null;
        if (instant || verified == null || now - verified >= (playerNear ? 200 : 600) || now < verified) {
            VERIFIED.put(id, now);
            verify(level, v, b, shelf, day);
        }
        Long od = OVERDUE.get(id);
        if (od == null || od != day) {
            OVERDUE.put(id, day);
            overdue(level, v, shelf, librarian, day);
        }
        Job job = JOBS.get(id);
        if (job != null && (now - job.since > 24000L || now < job.since || !(level.getEntity(job.folk) instanceof VillageFolkEntity f && f.isAlive()))) {
            abandon(job, "it waited too long");
            job = null;
        }
        Long planned = PLANNED.get(id);
        if (job == null && (instant || planned == null || now - planned >= 600 || now < planned)) {
            PLANNED.put(id, now);
            job = plan(level, v, b, shelf, librarian, day, now);
        }
        if (job != null && instant) {
            VillageFolkEntity f = level.getEntity(job.folk) instanceof VillageFolkEntity x ? x : null;
            if (f != null && fetch(level, v, job, f)) finish(level, v, b, job, f);
            else abandon(job, "the stores could not run to it");
        }
    }

    /** The next piece of writing: a lost book written out again, a trade's book due, or another of the town's books. */
    @Nullable
    private static Job plan(ServerLevel level, Villages.Village v, Ledger.Building b, Shelf shelf, @Nullable VillageFolkEntity librarian,
                            long day, long now) {
        UUID id = v.id();
        // A book that was lost, written out again by the librarian from the catalogue.
        if (librarian != null) {
            for (Title t : shelf.books) {
                if (!t.missing || t.pages.isEmpty()) continue;
                if (!materials(level, v, librarian)) return null;
                return begin(new Job(id, librarian.getUUID(), "writing out " + t.title + " again", now, null, null, t.id), librarian,
                    "\"" + t.title + "\" has gone missing. I'll write it out again.");
            }
        }
        TradeBooks.Ctx c = new TradeBooks.Ctx(level, v, day);
        TradeBooks.Draft d = TradeBooks.due(c, shelf, instant);
        if (d != null) {
            if (!materials(level, v, d.master())) return null;
            String title = bookTitle(d.key(), d.noun());                   // [weave] a trade's, or the librarian's own
            boolean first = shelf.tradeBook(d.key()) == null;
            return begin(new Job(id, d.master().getUUID(), (first ? "writing " : "bringing up to date ") + title, now, d, null, -1), d.master(),
                first ? FolkTalk.pick(d.master().getRandom(), "Somebody has to write down how the " + d.label() + " is done. It'll be me.",
                    "I'll write " + title + ". The young ones need it.") : FolkTalk.pick(d.master().getRandom(), title + " wants bringing up to date.",
                    "We've learned a thing or two since the last edition. Time to write it down."));
        }
        if (!instant && shelf.lastBook >= 0 && day - shelf.lastBook < BOOK_GAP) return null;
        Map.Entry<Authors.Idea, VillageFolkEntity> pick = Authors.choose(c, shelf, f -> f.isAlive() && !f.isBaby() && !ON.containsKey(f.getUUID()));
        if (pick == null) return null;
        VillageFolkEntity author = pick.getValue();
        if (!materials(level, v, author)) return null;
        String what = switch (pick.getKey().kind()) {
            case "POEM" -> "a poem";
            case "HISTORY" -> "a history";
            case "HOWTO" -> "a how-to book";
            case "LIFE" -> "a life";
            case "STORY" -> "a storybook for the children";
            default -> "a book";
        };
        return begin(new Job(id, author.getUUID(), "writing " + what + " at the library", now, null, pick.getKey(), -1), author,
            FolkTalk.pick(author.getRandom(), "I've " + what + " in me. Tonight I'll write it down.", "I'm going to write " + what + ".",
                "There's " + what + " wants writing, and I'm the one to do it."));
    }

    /** Can the stores run to a book and quill (Bench: made the whole way if it must be)? If not, said so. */
    private static boolean materials(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity by) {
        Bench.Plan plan = Bench.plan(level, v, List.of(Bench.Want.of(Items.WRITABLE_BOOK, 1)), Bench.handOf(level, v, by, STRUCTURE));
        if (plan.ok()) return true;
        SHORT.put(v.id(), plan.shortOf + " for a book and quill" + (plan.why.isEmpty() ? "" : " (" + plan.why + ")"));
        return false;
    }

    private static Job begin(Job j, VillageFolkEntity f, String line) {
        JOBS.put(j.village, j);
        ON.put(j.folk, j.village);
        SHORT.remove(j.village);
        if (!instant) FolkTalk.speak(f, line);
        f.brain("the library: " + j.words);
        LOG.info("[MCA-LIBRARY] {}: {} - {}", Villages.name(j.village), f.displayNameCap(), j.words);
        return j;
    }

    private static void abandon(Job j, String why) {
        JOBS.remove(j.village, j);
        ON.remove(j.folk);
        if (j.seat != null) SEATS.remove(j.seat.asLong(), j.folk);
        LOG.info("[MCA-LIBRARY] {}: let go of {}: {}", Villages.name(j.village), j.words, why);
    }

    /** The book and quill out of the stores. False, and nothing taken, if the stores cannot run to it. */
    private static boolean fetch(ServerLevel level, Villages.Village v, Job j, VillageFolkEntity f) {
        Bench.Plan plan = Bench.plan(level, v, List.of(Bench.Want.of(Items.WRITABLE_BOOK, 1)), Bench.handOf(level, v, f, STRUCTURE));
        if (!plan.ok() || !Bench.take(level, v, plan, f)) {
            SHORT.put(v.id(), (plan.ok() ? "a book and quill" : plan.shortOf) + " for a book and quill");
            return false;
        }
        return true;
    }

    /** The writing done: the book written, signed, marked and shelved, the catalogue and the chronicle told. */
    private static void finish(ServerLevel level, Villages.Village v, Ledger.Building b, Job j, VillageFolkEntity f) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Shelf shelf = LibraryRecords.shelf(id);
        JOBS.remove(id, j);
        ON.remove(j.folk);
        if (j.seat != null) SEATS.remove(j.seat.asLong(), j.folk);
        stand(f);
        if (j.copyOf >= 0) {
            Title t = shelf.byId(j.copyOf);
            if (t == null) return;
            String where = shelve(level, v, b, shelf, item(id, t, 1), false, f);
            t.missing = false;
            t.where = where;
            LibraryRecords.touch();
            Villages.tell(id, day, f.displayNameCap() + " wrote out \"" + t.title + "\" again for the library");
            return;
        }
        Quill.Book book = j.draft != null ? TradeBooks.write(j.draft) : j.idea != null ? j.idea.write().apply(f) : null;
        if (book == null || book.pages().isEmpty()) return;
        Title t = new Title();
        t.id = shelf.nextId++;
        t.kind = book.kind();
        t.title = book.title();
        t.author = book.author();
        t.authorId = f.getUUID();
        t.blurb = book.blurb();
        t.subject = book.subject();
        t.written = day;
        t.pages.addAll(book.pages());
        t.news.addAll(book.news());
        Title was = null;
        if (j.draft != null) {
            t.trade = j.draft.key();                                       // [weave]
            t.edition = j.draft.facts().edition();
            t.facts = j.draft.line();
            was = shelf.tradeBook(t.trade);
            if (was != null) was.superseded = true;
        }
        t.where = shelve(level, v, b, shelf, item(id, t, 0), true, f);
        shelf.books.add(t);
        if (j.draft == null) shelf.lastBook = day;
        prune(level, v, b, shelf);
        LibraryRecords.touch();
        Perks.printed(level, v, item(id, t, 1));       // [perks] the Printing Press: a copy for the stores, on a book out of them
        // The news.
        String kind = kindWord(t);
        String line;
        if (j.draft != null) {
            line = was == null ? f.displayNameCap() + " wrote " + t.title + ", the " + j.draft.noun()
                + "s' book of best practice, for the library" : f.displayNameCap() + " brought out the " + Quill.ordinal(t.edition) + " edition of "
                + t.title + (j.draft.changes().isEmpty() ? "" : ", with " + TradeBooks.changeWords(j.draft.changes()) + " in it");
            f.persona().remember(day, was == null ? "I wrote " + t.title : "I brought out the " + Quill.ordinal(t.edition) + " edition of " + t.title, 4);
        } else {
            line = f.displayNameCap() + "'s new " + kind + ", \"" + t.title + "\", is on the library's shelves";
            f.persona().remember(day, "I wrote " + Quill.a(kind) + ", \"" + t.title + "\"", 5);
        }
        Villages.tell(id, day, line);
        if (!instant) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There. Finished!", "Done - and on the shelf.", "That's the last page written."));
            level.playSound(null, f.blockPosition(), SoundEvents.BOOK_PUT, SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
        LOG.info("[MCA-LIBRARY] {}: {} ({} pages, at {})", Villages.name(id), line, t.pages.size(), t.where.isEmpty() ? "nowhere yet" : t.where);
    }

    /** "poem", "history", "how-to book", "life", "storybook", "book". */
    static String kindWord(Title t) {
        return switch (t.kind) {
            case "POEM" -> "poem";
            case "HISTORY" -> "history";
            case "HOWTO" -> "how-to book";
            case "LIFE" -> "life";
            case "STORY" -> "storybook";
            case "TRADE" -> "book of best practice";
            default -> "book";
        };
    }

    /** The catalogue kept to its size: the oldest old editions go first, taken off the shelves into the stores. */
    private static void prune(ServerLevel level, Villages.Village v, Ledger.Building b, Shelf shelf) {
        while (shelf.books.size() > LibraryRecords.MOST_BOOKS) {
            Title oldest = null;
            for (Title t : shelf.books) if (t.superseded && (oldest == null || t.written < oldest.written)) oldest = t;
            if (oldest == null) oldest = shelf.books.get(0);
            if (oldest.where.equals("lectern") || oldest.where.startsWith("shelf ")) {
                ItemStack s = takeFrom(level, b, oldest.where);
                if (!s.isEmpty()) Crafts.store(level, v, s);
            }
            shelf.books.remove(oldest);
        }
    }

    // ------------------------------------------------------------------ the shelves looked over

    /**
     * Every book where the catalogue says it is: a book gone from its place while a player stands in the library is
     * that player's on loan; gone with nobody about, it is missing, and will be written out again. A book of the
     * town's found back on a shelf (brought back, or put back in another place) is put down where it is, and a loan of
     * it closed. A book waiting for room is shelved when there is some.
     */
    static void verify(ServerLevel level, Villages.Village v, Ledger.Building b, Shelf shelf, long day) {
        UUID id = v.id();
        // What stands where, by number.
        Map<Integer, String> found = new HashMap<>();
        if (lecternStands(level, b)) {
            int n = idOf(onLectern(level, b), id);
            if (n >= 0) found.put(n, "lectern");
        }
        for (int i = 0; i < SHELF_PLACES.length; i++) {
            if (!(level.getBlockEntity(shelfAt(b, i)) instanceof ChiseledBookShelfBlockEntity sh)) continue;
            for (int s = 0; s < SLOTS; s++) {
                int n = idOf(sh.getItem(s), id);
                if (n >= 0) found.put(n, where(new int[]{ i, s }));
            }
        }
        boolean changed = false;
        for (Title t : shelf.books) {
            String now = found.get(t.id);
            if (now != null) {
                if (!now.equals(t.where) || t.missing) {
                    if (t.where.equals("loan")) closeLoan(level, v, shelf, t, null, day);
                    t.where = now;
                    t.missing = false;
                    changed = true;
                }
                continue;
            }
            if (!(t.where.equals("lectern") || t.where.startsWith("shelf "))) continue;
            // Gone from its place. A player in the library has it: a loan; else, missing.
            Player p = level.getNearestPlayer(b.anchor().getX() + 0.5, b.anchor().getY() + 1, b.anchor().getZ() + 0.5, 24.0,
                e -> e instanceof Player pl && !pl.isSpectator());
            if (p != null) {
                lend(shelf, t, p, day);
                VillageFolkEntity lib = level.getEntity(shelf.librarian == null ? new UUID(0, 0) : shelf.librarian) instanceof VillageFolkEntity x ? x : null;
                if (lib != null && !instant) FolkTalk.speak(lib, "Taking \"" + t.title + "\"? Bring it back by day " + (day + LOAN_DAYS) + ", mind!");
            } else {
                t.missing = true;
                t.where = "";
                Villages.tell(id, day, "\"" + t.title + "\" went missing from the library");
            }
            changed = true;
        }
        // Books waiting for room.
        for (Title t : shelf.books) {
            if (!t.where.isEmpty() || t.missing || t.pages.isEmpty()) continue;
            String w = shelve(level, v, b, shelf, item(id, t, 0), false, null);
            if (w.isEmpty()) break;
            t.where = w;
            changed = true;
        }
        if (changed) LibraryRecords.touch();
    }

    // ------------------------------------------------------------------ the folk at the library

    /** Free for the library: awake, at home, nothing pressing (Culture.free), and its own time: the evening, the day of rest,
     *  or a child's afternoon. */
    static boolean leisure(VillageFolkEntity f, ServerLevel level) {
        if (!Culture.free(f, level)) return false;
        UUID id = f.ownerId();
        if (id == null) return false;
        long t = level.getDayTime() % 24000L;
        if (f.isBaby()) return t >= 6500L && t < 11000L;
        if (t >= 12000L && t < 13700L) return true;
        return RestDay.now(id, level.getDayTime()) != null && t >= 6000L && t < 11500L;
    }

    /**
     * From the folk's tick: a writer on its way to the stores for its book and quill, then to its desk, and writing; a
     * reader on its way to a chair, and reading. True while it is about it (the rest of its day waits).
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (instant) return false;
        UUID vid = ON.get(f.getUUID());
        if (vid != null) {
            Job j = JOBS.get(vid);
            if (j == null || !j.folk.equals(f.getUUID())) {
                ON.remove(f.getUUID());
                return false;
            }
            j.engaged = write(f, level, j);
            return j.engaged;
        }
        Visit vis = VISITS.get(f.getUUID());
        if (vis != null) {
            vis.engaged = read(f, level, vis);
            return vis.engaged;
        }
        if (f.tickCount % 40 == 2 && startReading(f, level)) return true;
        return false;
    }

    /** Every other tick: a writer or reader kept in its seat, its face to its page. True while it is held. */
    public static boolean busy(VillageFolkEntity f) {
        UUID vid = ON.get(f.getUUID());
        BlockPos seat = null;
        float yaw = 0;
        boolean held;
        if (vid != null) {
            Job j = JOBS.get(vid);
            held = j != null && j.engaged;
            if (held && j.stage == 2) {
                seat = j.seat;
                yaw = j.seat == null ? 0 : stoolYaw(j.village);
            }
        } else {
            Visit vis = VISITS.get(f.getUUID());
            held = vis != null && vis.engaged;
            if (held && vis.stage == 1) {
                seat = vis.seat;
                yaw = vis.yaw;
            }
        }
        if (held && seat != null) {
            // Moved off its seat (a push, a goal of its own): up, and back to it on the next look.
            if (f.position().distanceToSqr(seat.getX() + 0.5, seat.getY() + 0.5, seat.getZ() + 0.5) > 1.5 * 1.5) {
                if (f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
                if (vid != null) {
                    Job j = JOBS.get(vid);
                    if (j != null) j.stage = 1;
                } else {
                    Visit vis = VISITS.get(f.getUUID());
                    if (vis != null) vis.stage = 0;
                }
                return held;
            }
            if (f.getPose() != Pose.SITTING) f.setPose(Pose.SITTING);
            f.getNavigation().stop();
            f.setYBodyRot(yaw);
        }
        return held;
    }

    /** Sat at a desk or in a chair of the library (Park leaves it be). */
    public static boolean seated(VillageFolkEntity f) {
        UUID vid = ON.get(f.getUUID());
        if (vid != null) {
            Job j = JOBS.get(vid);
            return j != null && j.stage == 2 && j.seat != null;
        }
        Visit vis = VISITS.get(f.getUUID());
        return vis != null && vis.stage == 1 && vis.seat != null;
    }

    private static boolean near(VillageFolkEntity f, BlockPos to, double r) {
        double dx = f.getX() - (to.getX() + 0.5), dz = f.getZ() - (to.getZ() + 0.5);
        return dx * dx + dz * dz <= r * r && Math.abs(f.getY() - to.getY()) <= 2.0;
    }

    private static void walk(VillageFolkEntity f, BlockPos to, int[] tick) {
        if (f.getNavigation().isDone() || f.tickCount - tick[0] > 60) {
            f.walkTo(to, 0.9D);
            tick[0] = f.tickCount;
        }
    }

    private static void sit(VillageFolkEntity f, BlockPos seat, float yaw) {
        f.clearQueue();
        f.getNavigation().stop();
        f.moveTo(seat.getX() + 0.5, seat.getY() + 0.5, seat.getZ() + 0.5, yaw, 0.0F);
        f.setYHeadRot(yaw);
        f.setYBodyRot(yaw);
        f.setPose(Pose.SITTING);
    }

    private static void stand(VillageFolkEntity f) {
        if (f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
        if (f.propInHand && Leisure.isProp(f.getItemBySlot(EquipmentSlot.OFFHAND))) {
            f.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            f.propInHand = false;
        }
    }

    /** Something held up in the hand for show (never dropped: Leisure puts it away). */
    private static void prop(VillageFolkEntity f, ItemStack shown) {
        if (shown.isEmpty() || !f.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) return;
        ItemStack p = shown.copyWithCount(1);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("mca_prop", true);
        p.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        f.setItemSlot(EquipmentSlot.OFFHAND, p);
        f.propInHand = true;
    }

    /** The yaw a writer sits at: facing the back of the library, where its desk is. */
    private static float stoolYaw(UUID village) {
        Ledger.Building b = building(village);
        return b == null ? 0 : b.facing().toYRot();
    }

    /** A free seat of these, nearest the door first, and claimed for this folk; or null. */
    @Nullable
    private static BlockPos claim(Ledger.Building b, List<int[]> seats, UUID folk) {
        for (int[] s : seats) {
            BlockPos p = at(b, s[0], 0, s[1]);
            UUID had = SEATS.get(p.asLong());
            if (had == null || had.equals(folk)) {
                SEATS.put(p.asLong(), folk);
                return p;
            }
        }
        return null;
    }

    /** A writer's step: to the stores, to its desk, and at its page. */
    private static boolean write(VillageFolkEntity f, ServerLevel level, Job j) {
        Villages.Village v = Villages.get(j.village);
        Ledger.Building b = building(j.village);
        if (v == null || b == null) {
            abandon(j, "the library is gone");
            return false;
        }
        if (!leisure(f, level) || f.isBaby()) {
            if (j.stage == 2) {
                stand(f);
                j.stage = 1;                                              // back to its desk the next free hour
            }
            return false;
        }
        int[] tick = { j.walkTick };
        try {
            switch (j.stage) {
                case 0 -> {
                    BlockPos stores = Museum.storesSpot(level, v);
                    if (near(f, stores, 4)) {
                        if (!fetch(level, v, j, f)) {
                            abandon(j, "the stores could not run to a book and quill");
                            return false;
                        }
                        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                        j.stage = 1;
                    } else {
                        walk(f, stores, tick);
                    }
                }
                case 1 -> {
                    readDrawing();
                    if (j.seat == null) j.seat = claim(b, stools, f.getUUID());
                    BlockPos to = j.seat != null ? j.seat : lecternAt(b).relative(b.facing().getOpposite());
                    if (near(f, to, 1.2)) {
                        if (j.seat != null) sit(f, j.seat, stoolYaw(j.village));
                        j.stage = 2;
                        j.writingSince = level.getGameTime();
                    } else {
                        walk(f, to, tick);
                    }
                    prop(f, new ItemStack(Items.WRITABLE_BOOK));
                }
                default -> {
                    prop(f, new ItemStack(Items.WRITABLE_BOOK));
                    if (f.tickCount % 40 == 0) {
                        f.swing(net.minecraft.world.InteractionHand.OFF_HAND);
                        level.playSound(null, f.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 0.6F, 1.0F);
                        level.sendParticles(ParticleTypes.ENCHANT, f.getX(), f.getY() + 1.4, f.getZ(), 4, 0.3, 0.2, 0.3, 0.4);
                    }
                    if (level.getGameTime() - j.writingSince >= WRITE_TICKS) {
                        finish(level, v, b, j, f);
                        return false;
                    }
                }
            }
            j.walkTick = tick[0];
            f.hobbyNow = j.words.substring(0, 1).toUpperCase(Locale.ROOT) + j.words.substring(1);
            f.lastLeisureTick = f.tickCount;
            return true;
        } catch (RuntimeException ex) {
            LOG.warn("[MCA-LIBRARY] {}'s writing: {}", f.displayNameCap(), ex.toString());
            abandon(j, "it went wrong");
            stand(f);
            return false;
        }
    }

    /** Should this folk go and read now, and what? Starts the visit if so. */
    private static boolean startReading(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null || !LibraryRecords.has(id) || f.isShowcase()) return false;
        long day = level.getDayTime() / 24000L;
        Long seen = VISITED.get(f.getUUID());
        if (seen != null && seen == day) return false;
        if (!leisure(f, level)) return false;
        Ledger.Building b = building(id);
        if (b == null || !level.isLoaded(b.anchor())) return false;
        VISITED.put(f.getUUID(), day);
        Title book = choose(f, LibraryRecords.shelf(id), day);
        if (book == null) return false;
        VISITS.put(f.getUUID(), new Visit(id, book.id, level.getGameTime()));
        return true;
    }

    /**
     * What a folk would read: an apprentice or a master the edition of its trade's book it has not read; a child the
     * newest storybook (or its leaning's trade book); a reader the newest book it has not read; anybody else, now and
     * then, a new poem or history. Only a book on the shelves.
     */
    @Nullable
    static Title choose(VillageFolkEntity f, Shelf shelf, long day) {
        LibraryRecords.Reader r = shelf.readers.get(f.getUUID());
        if (f.isBaby()) {
            Title best = null;
            for (Title t : shelf.books) if (t.kind.equals("STORY") && onShelves(t) && (r == null || !r.books.contains(t.id))) best = t;
            if (best != null) return best;
            StationTask lean = School.leaningOf(f);
            Title tb = lean == StationTask.NONE ? null : shelf.tradeBook(lean.name());
            return tb != null && onShelves(tb) && (r == null || r.trades.getOrDefault(lean.name(), 0) < tb.edition) ? tb : null;
        }
        StationTask t = f.stationTask();
        if (t != StationTask.NONE) {
            Title tb = shelf.tradeBook(t.name());
            if (tb != null && onShelves(tb) && (r == null || r.trades.getOrDefault(t.name(), 0) < tb.edition)) return tb;
        }
        boolean reader = f.persona().rolled() && f.persona().hobby() == Persona.Hobby.READING;
        if (!reader && Math.floorMod(f.getUUID().hashCode() + day, 4L) != 0) return null;
        Title best = null;
        for (Title b : shelf.books) {
            if (b.kind.equals("TRADE") || b.superseded || !onShelves(b) || r != null && r.books.contains(b.id)) continue;
            if (!reader && !(b.kind.equals("POEM") || b.kind.equals("HISTORY") || b.kind.equals("LIFE"))) continue;
            if (best == null || b.written > best.written) best = b;
        }
        return best;
    }

    private static boolean onShelves(Title t) {
        return !t.missing && (t.where.equals("lectern") || t.where.startsWith("shelf "));
    }

    /** A reader's step: to a chair, and reading. */
    private static boolean read(VillageFolkEntity f, ServerLevel level, Visit vis) {
        Ledger.Building b = building(vis.village);
        Shelf shelf = LibraryRecords.shelf(vis.village);
        Title t = shelf.byId(vis.book);
        if (b == null || t == null || level.getGameTime() - vis.started > 6000L || !leisure(f, level)) {
            endVisit(f, vis);
            return false;
        }
        int[] tick = { vis.walkTick };
        readDrawing();
        if (vis.stage == 0) {
            if (vis.seat == null) {
                vis.seat = claim(b, chairs, f.getUUID());
                if (vis.seat != null) {
                    for (int[] c : chairs) {
                        if (!at(b, c[0], 0, c[1]).equals(vis.seat)) continue;
                        Direction looks = c[2] > 0 ? b.facing().getClockWise() : b.facing().getCounterClockWise();
                        vis.yaw = looks.toYRot();
                    }
                }
            }
            BlockPos to = vis.seat != null ? vis.seat : lecternAt(b).relative(b.facing().getOpposite());
            if (near(f, to, 1.2)) {
                if (vis.seat != null) sit(f, vis.seat, vis.yaw);
                vis.stage = 1;
                vis.readingSince = level.getGameTime();
            } else {
                walk(f, to, tick);
            }
            vis.walkTick = tick[0];
        } else {
            if (f.tickCount % 60 == 0) {
                level.playSound(null, f.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 0.5F, 1.0F);
                f.getLookControl().setLookAt(f.getX(), f.getY() + 0.4, f.getZ());
            }
            if (level.getGameTime() - vis.readingSince >= READ_TICKS) {
                finishReading(level, f, shelf, t, level.getDayTime() / 24000L);
                endVisit(f, vis);
                return false;
            }
        }
        prop(f, item(vis.village, t, 0));
        f.hobbyNow = "Reading \"" + t.title + "\" at the library";
        f.lastLeisureTick = f.tickCount;
        return true;
    }

    private static void endVisit(VillageFolkEntity f, Visit vis) {
        VISITS.remove(f.getUUID(), vis);
        if (vis.seat != null) SEATS.remove(vis.seat.asLong(), f.getUUID());
        stand(f);
    }

    /**
     * A book read to the end: in the folk's books (a trade's book by its edition), the book's reads counted, a memory
     * of it and a word about it; a child learns a little of the trade it leans to (or the trade of the book).
     */
    static void finishReading(ServerLevel level, VillageFolkEntity f, Shelf shelf, Title t, long day) {
        LibraryRecords.Reader r = shelf.reader(f.getUUID());
        if (t.kind.equals("TRADE")) r.trades.merge(t.trade, t.edition, Math::max);
        else if (!r.books.contains(t.id)) r.books.add(t.id);
        r.lastDay = day;
        r.lastTitle = t.title;
        t.reads++;
        PACE.remove(f.getUUID());
        LibraryRecords.touch();
        f.persona().remember(day, t.kind.equals("TRADE") ? "I read the " + Quill.ordinal(t.edition) + " edition of " + t.title
            : "I read \"" + t.title + "\" by " + t.author, t.kind.equals("TRADE") ? 3 : 2);
        if (f.isBaby()) {
            StationTask lean = t.kind.equals("TRADE") ? tradeOf(t) : School.leaningOf(f);
            if (lean != StationTask.NONE) {
                int cap = AssistantEntity.xpForLevel(3), have = f.xpInTrade(lean);
                if (have < cap) f.schoolXp(lean, Math.min(cap - have, Math.max(1, AssistantEntity.xpForLevel(1) / 2)));
            }
        }
        if (!instant) FolkTalk.speak(f, opinion(f, t, f.getRandom()));
    }

    static StationTask tradeOf(Title t) {
        try { return StationTask.valueOf(t.trade); } catch (IllegalArgumentException e) { return StationTask.NONE; }
    }

    /** What a folk says of a book it has read, its own way. */
    static String opinion(VillageFolkEntity f, Title t, RandomSource r) {
        Social.Life life = f.life();
        if (t.kind.equals("TRADE")) {
            if (life.has(Social.Trait.GRUMPY)) return FolkTalk.pick(r, "Hmph. I could have told them all that.", "Not bad. For a book.");
            return FolkTalk.pick(r, "So that's how it's done! I'll try it tomorrow.", "I learned more from that than a month in the work.",
                "The " + Quill.ordinal(t.edition) + " edition's the best yet.");
        }
        if (life.has(Social.Trait.GRUMPY)) return FolkTalk.pick(r, "Too long by half.", "I've read worse.");
        if (life.has(Social.Trait.CHEERFUL)) return FolkTalk.pick(r, "Oh, I loved it!", "What a book! I laughed and laughed.");
        if (life.has(Social.Trait.SHY)) return FolkTalk.pick(r, "That was… lovely.", "I'd like to read it again.");
        return switch (t.kind) {
            case "POEM" -> FolkTalk.pick(r, "That last verse got me.", "I'll have that one by heart soon.");
            case "HISTORY" -> FolkTalk.pick(r, "I never knew half of that.", "So that's how it all began.");
            case "STORY" -> FolkTalk.pick(r, "Again! Read it again!", "I liked the bit with the cat.");
            default -> FolkTalk.pick(r, "Good book, that.", "Well worth the reading.");
        };
    }

    // ------------------------------------------------------------------ what reading does for the work

    /** Has this folk read any edition of this trade's book? */
    public static boolean hasRead(UUID village, UUID folk, String trade) {
        if (!LibraryRecords.has(village)) return false;
        LibraryRecords.Reader r = LibraryRecords.shelf(village).readers.get(folk);
        return r != null && r.trades.containsKey(trade);
    }

    /** How much quicker its work goes for having read its trade's book: the current edition, an older one, or none. */
    public static int workPercent(VillageFolkEntity f) {
        int[] c = PACE.get(f.getUUID());
        if (c != null && f.tickCount - c[1] < 200 && f.tickCount >= c[1]) return c[0];
        int p = 0;
        UUID id = f.ownerId();
        StationTask t = f.stationTask();
        if (id != null && t != StationTask.NONE && LibraryRecords.has(id)) {
            Shelf shelf = LibraryRecords.shelf(id);
            LibraryRecords.Reader r = shelf.readers.get(f.getUUID());
            Integer ed = r == null ? null : r.trades.get(t.name());
            if (ed != null) {
                Title cur = shelf.tradeBook(t.name());
                p = cur != null && ed >= cur.edition ? PACE_NEW : PACE_OLD;
            }
        }
        PACE.put(f.getUUID(), new int[]{ p, f.tickCount });
        return p;
    }

    /** The extra an apprentice learns of its trade for having read its trade's book (VillageFolkEntity.creditTrade). */
    public static int extraXp(VillageFolkEntity f, int amount) {
        StationTask t = f.stationTask();
        UUID id = f.ownerId();
        if (amount <= 0 || t == StationTask.NONE || id == null || f.tradeLevel(t) >= APPRENTICE_LEVEL || !hasRead(id, f.getUUID(), t.name())) return 0;
        int x = amount * STUDY_PERCENT;
        return x / 100 + (f.getRandom().nextInt(100) < x % 100 ? 1 : 0);
    }

    /** Its trade's book's part in the pace, for its card ("the Farmer's Book"), or "". */
    public static String paceWord(VillageFolkEntity f) {
        StationTask t = f.stationTask();
        return t == StationTask.NONE ? "" : "reading " + bookTitle(t).replaceFirst("^The ", "the ");
    }

    // ------------------------------------------------------------------ lending to players

    /** A book out to a player (already in their hands): the loan written down, due back in three days. */
    static void lend(Shelf shelf, Title t, Player p, long day) {
        LibraryRecords.Loan l = new LibraryRecords.Loan();
        l.player = p.getUUID();
        l.name = p.getName().getString();
        l.book = t.id;
        l.day = day;
        l.due = day + LOAN_DAYS;
        shelf.loans.add(l);
        t.where = "loan";
        t.lent++;
        LibraryRecords.touch();
    }

    private static void give(Player p, ItemStack s) {
        if (!p.getInventory().add(s)) p.drop(s, false);
    }

    /** The loan of this book closed: fined if it came back late. Returns what the librarian says of it. */
    static String closeLoan(ServerLevel level, Villages.Village v, Shelf shelf, Title t, @Nullable Player p, long day) {
        LibraryRecords.Loan loan = null;
        for (LibraryRecords.Loan l : shelf.loans) if (l.book == t.id) loan = l;
        if (loan == null) return "";
        shelf.loans.remove(loan);
        int late = (int) Math.max(0, day - loan.due);
        LibraryRecords.touch();
        if (late <= 0) {
            if (p != null) feel(level, shelf, p, 2);
            return "back on time";
        }
        int fine = Math.min(MOST_FINE, late * FINE_A_DAY);
        int paid = 0;
        if (p != null) {
            paid = Math.min(fine, Market.coinsHeld(p));
            if (paid > 0) {
                Market.payOut(p, paid);
                Ledger.addCoins(v.id(), paid);
                shelf.fines += paid;
            }
        }
        if (fine - paid > 0) {
            int[] r = Ledger.record(v.id(), loan.player);
            r[1] += fine - paid;
            Ledger.record(v.id(), loan.player, r);
        }
        Standing.stir(v.id(), loan.player);
        return late + (late == 1 ? " day" : " days") + " late: a fine of " + fine + " coins" + (paid < fine ? " (" + (fine - paid) + " owed)" : "");
    }

    private static void feel(ServerLevel level, Shelf shelf, Player p, int by) {
        if (shelf.librarian != null && level.getEntity(shelf.librarian) instanceof VillageFolkEntity lib) {
            lib.persona().feelFor(p.getUUID(), p.getName().getString(), by);
        }
    }

    /**
     * Each morning: a book a day late costs the borrower the librarian's good opinion; five days late it is given up
     * as lost, the most fine put on what they owe the town, the town told, and the book written out again.
     */
    static void overdue(ServerLevel level, Villages.Village v, Shelf shelf, @Nullable VillageFolkEntity librarian, long day) {
        UUID id = v.id();
        for (LibraryRecords.Loan l : new ArrayList<>(shelf.loans)) {
            int late = (int) (day - l.due);
            if (late <= 0 || late <= l.late) continue;
            l.late = late;
            Title t = shelf.byId(l.book);
            String title = t == null ? "a book" : "\"" + t.title + "\"";
            if (librarian != null) {
                librarian.persona().feelFor(l.player, l.name, -2);
                if (late == 1) librarian.persona().remember(day, l.name + " is late bringing back " + title, 2);
            }
            if (late >= LOST_AFTER) {
                shelf.loans.remove(l);
                shelf.lost++;
                int[] r = Ledger.record(id, l.player);
                r[1] += MOST_FINE;
                Ledger.record(id, l.player, r);
                Villages.tell(id, day, l.name + " never brought back " + title + " to the library");
                for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f) f.persona().feelFor(l.player, l.name, -3);
                Standing.stir(id, l.player);
                if (t != null) {
                    t.missing = true;
                    t.where = "";
                }
            }
            LibraryRecords.touch();
        }
    }

    // ------------------------------------------------------------------ talk

    /**
     * A player's words about books, answered: to the librarian, "could I borrow a book?" (or one by name), "here's
     * your book back", "what's on the shelves?", "could I buy a copy?"; to anybody, "what are you reading?", "what
     * are you writing?". Null for anything else (the rest of the talk answers it).
     */
    @Nullable
    public static String talk(VillageFolkEntity f, Player p, TalkTopic topic, String text) {
        UUID id = f.ownerId();
        if (id == null || f.isBaby() || !(f.level() instanceof ServerLevel level)) return null;
        String t = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9' ]", " ") + " ";
        boolean carrying = carries(p, id);
        boolean returning = has(t, "return", "bring back", "brought back", "bringing back", "here's your book", "heres your book", "finished with",
            "giving back", "give back");
        if (carrying && returning && !isLibrarian(f) && stands(id)) {
            return "Take it to " + LibraryRecords.shelf(id).librarianName + " at the library, or put it back on a shelf there yourself.";
        }
        if (carrying && isLibrarian(f) && (returning || topic == TalkTopic.DELIVER || topic == TalkTopic.OPEN)) {
            String back = giveBack(level, f, p);
            if (!back.isEmpty()) return back;
        }
        if (has(t, "what are you reading", "reading anything", "read any good", "a good book", "what have you read")) return reading(f);
        if (has(t, "what are you writing", "your book", "your poem", "written anything", "write anything", "writing anything")) return writing(f);
        // Only words that are about the library's books: "borrow a pickaxe" is the stores', "the books" the ledger's, the history the chronicle's.
        boolean books = has(t, "library", "something to read", "catalogue", "on the shelves", " poem", "storybook", "best practice", "a copy of",
            "copy of the", "buy a copy")
            || has(t, "borrow", "lend me", "could i read", "can i read", "may i read", "recommend") && has(t, "book", "read", "poem", "story")
            || has(t, " book ", " books ") && has(t, "borrow", "lend", " read", "library", "recommend", "what books", "which books", "your books");
        if (!books || has(t, "ledger", "the accounts", "mortgage", " bank ")) return null;
        if (!stands(id)) {
            return !Villages.hasBuilt(id, STRUCTURE) && Villages.headcount(id) >= FROM_FOLK
                ? "We've no library yet. It's on the builders' list; come back when it's up and I'll find you something good."
                : "We've no library yet. When we're a town of " + FROM_FOLK + " or so we'll build one, and then you'll see some books.";
        }
        Shelf shelf = LibraryRecords.shelf(id);
        if (!isLibrarian(f)) {
            String who = shelf.librarianName.isEmpty() ? "the librarian" : shelf.librarianName;
            return "Books? Ask " + who + " - the library's theirs to keep.";
        }
        if (has(t, "buy a copy", "copy of", "a copy", "buy one", "to keep")) return sell(level, f, p, shelf, t);
        if (has(t, "borrow", "lend me", "something to read", "could i read", "can i read", "may i read", "take out", "check out")) {
            return borrow(level, f, p, shelf, t);
        }
        return catalogue(f, shelf, level.getDayTime() / 24000L);
    }

    private static boolean has(String t, String... words) {
        for (String w : words) if (t.contains(w)) return true;
        return false;
    }

    /** Does the player carry a book of this town's library? */
    static boolean carries(Player p, UUID village) {
        for (ItemStack s : p.getInventory().items) if (idOf(s, village) >= 0) return true;
        for (ItemStack s : p.getInventory().offhand) if (idOf(s, village) >= 0) return true;
        return false;
    }

    /** The book a player's words name: by its title's own words, or a trade's book by the trade ("the farmer's book"). */
    @Nullable
    static Title title(Shelf shelf, String t) {
        Title best = null;
        int bestScore = 0;
        for (Title b : shelf.books) {
            if (b.superseded) continue;
            int score = 0;
            for (String w : b.title.toLowerCase(Locale.ROOT).replaceAll("[^a-z' ]", " ").split(" +")) {
                if (w.length() < 4 || w.equals("book") || w.equals("with") || w.equals("from") || w.equals("town")) continue;
                if (t.contains(" " + w + " ") || t.contains(" " + w + "s ")) score += w.endsWith("'s") ? 3 : 2;
            }
            if (b.kind.equals("TRADE")) {
                StationTask tr = tradeOf(b);
                if (tr != StationTask.NONE && (t.contains(" " + tr.title.toLowerCase(Locale.ROOT) + "'s ") || t.contains(" " + tr.label + " "))) score += 3;
            }
            if (score > bestScore) { bestScore = score; best = b; }
        }
        return bestScore >= 2 ? best : null;
    }

    /** "What's on the shelves?": the catalogue in a breath, and the newest. */
    static String catalogue(VillageFolkEntity f, Shelf shelf, long day) {
        Map<String, Integer> n = new LinkedHashMap<>();
        Title newest = null;
        for (Title t : shelf.books) {
            if (t.superseded || t.missing) continue;
            n.merge(t.kind, 1, Integer::sum);
            if (newest == null || t.written >= newest.written) newest = t;
        }
        if (n.isEmpty()) return "The shelves are bare yet. Give the town a few days: the masters are writing up their trades.";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : n.entrySet()) parts.add(Quill.number(e.getValue()) + " " + plural(e.getKey(), e.getValue()));
        String s = "We have " + Quill.list(parts) + " on the shelves.";
        if (newest != null) s += " The newest is \"" + newest.title + "\" by " + newest.author + ": " + newest.blurb + ".";
        return s + " Ask for any by name, and you can have it for " + Quill.number(LOAN_DAYS) + " days.";
    }

    private static String plural(String kind, int n) {
        String one = switch (kind) {
            case "TRADE" -> "trade's book";
            case "POEM" -> "poem";
            case "HISTORY" -> "history";
            case "HOWTO" -> "how-to book";
            case "LIFE" -> "life";
            case "STORY" -> "storybook";
            default -> "book";
        };
        if (n == 1) return one;
        return switch (kind) {
            case "TRADE" -> "trades' books";
            case "HISTORY" -> "histories";
            case "LIFE" -> "lives";
            default -> one + "s";
        };
    }

    /** "Could I borrow ...?": the book named (or the newest), off its shelf and into the player's hands, due in three days. */
    static String borrow(ServerLevel level, VillageFolkEntity f, Player p, Shelf shelf, String t) {
        UUID id = f.ownerId();
        long day = level.getDayTime() / 24000L;
        if (Laws.banished(id, p.getUUID(), day)) return "Not a page. Not for you.";
        int out = 0;
        for (LibraryRecords.Loan l : shelf.loans) if (l.player.equals(p.getUUID())) out++;
        if (out >= MOST_LOANS) return "You've " + Quill.number(out) + " of our books already. Bring one back first.";
        Title want = title(shelf, t);
        if (want == null) {
            for (Title b : shelf.books) {
                if (b.superseded || !onShelves(b)) continue;
                if (want == null || b.written > want.written) want = b;
            }
            if (want == null) return "There's nothing on the shelves just now. Come back in a day or two.";
        }
        if (!onShelves(want)) {
            return want.where.equals("loan") ? "\"" + want.title + "\" is out on loan. Try again in a day or two."
                : "\"" + want.title + "\" isn't on the shelf. I'll write it out again as soon as I've the paper.";
        }
        Ledger.Building b = building(id);
        if (b == null) return null;
        ItemStack s = takeFrom(level, b, want.where);
        if (idOf(s, id) != want.id) {
            if (!s.isEmpty()) give(p, s);
            want.missing = true;
            want.where = "";
            LibraryRecords.touch();
            return "That's odd - it's not where it should be. I'll find it, or write it out again.";
        }
        s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Borrowed from the library of " + Villages.name(id)),
            Component.literal("Due back on day " + (day + LOAN_DAYS)))));
        lend(shelf, want, p, day);
        give(p, s);
        f.persona().remember(day, "I lent " + p.getName().getString() + " \"" + want.title + "\"", 2);
        return "Here's \"" + want.title + "\" by " + want.author + " - " + want.blurb + ". Bring it back by day " + (day + LOAN_DAYS)
            + ", mind: it's " + FINE_A_DAY + " coins a day after that.";
    }

    /** "Here's your book back": every book of the town's the player carries, back onto the shelves; a fine if late. */
    static String giveBack(ServerLevel level, VillageFolkEntity f, Player p) {
        UUID id = f.ownerId();
        Villages.Village v = Villages.get(id);
        Ledger.Building b = building(id);
        if (v == null || b == null) return "I've nowhere to put it.";
        Shelf shelf = LibraryRecords.shelf(id);
        long day = level.getDayTime() / 24000L;
        List<String> said = new ArrayList<>();
        List<ItemStack> all = new ArrayList<>();
        all.addAll(p.getInventory().items);
        all.addAll(p.getInventory().offhand);
        for (ItemStack s : all) {
            int n = idOf(s, id);
            if (n < 0) continue;
            ItemStack book = s.copy();
            s.setCount(0);
            book.remove(DataComponents.LORE);
            Title t = shelf.byId(n);
            String where = shelve(level, v, b, shelf, book, false, f);
            if (t == null) continue;
            String how = closeLoan(level, v, shelf, t, p, day);
            t.where = where;
            t.missing = false;
            said.add("\"" + t.title + "\"" + (how.isEmpty() ? "" : ", " + how));
        }
        LibraryRecords.touch();
        if (said.isEmpty()) return "";
        boolean late = false;
        for (String s : said) if (s.contains("late")) late = true;
        if (late) f.persona().feelFor(p.getUUID(), p.getName().getString(), -2);
        return (late ? "About time. " : FolkTalk.pick(f.getRandom(), "Thank you! ", "Back safe and sound. ")) + Quill.cap(Quill.list(said)) + "."
            + (late ? " Mind the days next time." : " Come back for another whenever you like.");
    }

    /** "Could I buy a copy?": written out on a book and quill from the stores, for a price, the player's to keep. */
    static String sell(ServerLevel level, VillageFolkEntity f, Player p, Shelf shelf, String t) {
        UUID id = f.ownerId();
        Villages.Village v = Villages.get(id);
        if (v == null) return null;
        Title want = title(shelf, t);
        if (want == null) return "A copy of which book? Say its name, and I'll write you one out for " + COPY_PRICE + " coins.";
        if (Market.coinsHeld(p) < COPY_PRICE) return "A copy is " + COPY_PRICE + " coins - the paper and the ink and an evening's writing. You've "
            + Market.coinsHeld(p) + ".";
        Bench.Plan plan = Bench.plan(level, v, List.of(Bench.Want.of(Items.WRITABLE_BOOK, 1)), Bench.handOf(level, v, f, STRUCTURE));
        if (!plan.ok() || !Bench.take(level, v, plan, f)) return "I've no paper or ink to spare for it: we're short of " + plan.shortOf + ".";
        Market.payOut(p, COPY_PRICE);
        Ledger.addCoins(id, COPY_PRICE);
        Economy.sold(id, COPY_PRICE);
        give(p, Archive.book(want.title, want.author, 1, want.pages));
        return "There - a fair copy of \"" + want.title + "\", yours to keep. " + COPY_PRICE + " coins, and money well spent.";
    }

    /** "What are you reading?" */
    static String reading(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || !LibraryRecords.has(id)) return "Reading? We've no library to read from yet.";
        LibraryRecords.Reader r = LibraryRecords.shelf(id).readers.get(f.getUUID());
        if (r == null || r.lastTitle.isEmpty()) return FolkTalk.pick(f.getRandom(), "Nothing just now. I keep meaning to go to the library.",
            "I've not read a thing in ages. I should.");
        Title t = null;
        for (Title b : LibraryRecords.shelf(id).books) if (b.title.equals(r.lastTitle)) t = b;
        return "I've just read \"" + r.lastTitle + "\"" + (t == null ? "" : " by " + t.author) + ". " + (t == null ? "" : opinion(f, t, f.getRandom()));
    }

    /** "What are you writing?" */
    static String writing(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return "Writing? Me?";
        Job j = JOBS.get(id);
        if (j != null && j.folk.equals(f.getUUID())) return "I'm " + j.words + ". Come back tomorrow and it'll be on the shelf.";
        if (!LibraryRecords.has(id)) return "Me? I'm no writer.";
        List<String> mine = new ArrayList<>();
        for (Title t : LibraryRecords.shelf(id).books) {
            if (f.getUUID().equals(t.authorId) && !t.superseded) mine.add("\"" + t.title + "\"");
        }
        if (mine.isEmpty()) return FolkTalk.pick(f.getRandom(), "Me? I'm no writer.", "Nothing yet. One day, perhaps.");
        return "I wrote " + Quill.list(mine.subList(Math.max(0, mine.size() - 3), mine.size())) + ". " + FolkTalk.pick(f.getRandom(),
            "They're in the library, if you want them.", "Borrow one from the library and tell me what you think.");
    }

    // ------------------------------------------------------------------ folk to folk, the card, the gazette

    /** Two folk passing the time of day about the library (Smalltalk): {opener, answer, last word} each. */
    public static List<String[]> talk(VillageFolkEntity a, VillageFolkEntity b, ServerLevel level, RandomSource r) {
        List<String[]> out = new ArrayList<>();
        UUID id = a.ownerId();
        if (id == null || !LibraryRecords.has(id)) return out;
        long day = level.getDayTime() / 24000L;
        Shelf shelf = LibraryRecords.shelf(id);
        StationTask bt = b.stationTask();
        if (bt != StationTask.NONE) {
            Title tb = shelf.tradeBook(bt.name());
            LibraryRecords.Reader br = shelf.readers.get(b.getUUID());
            if (tb != null && tb.edition > 1 && day - tb.written <= 5 && (br == null || br.trades.getOrDefault(bt.name(), 0) < tb.edition)) {
                String news = tb.news.isEmpty() ? "" : tb.news.get(r.nextInt(tb.news.size()));
                out.add(new String[]{ "Have you read the new edition of " + tb.title + "?", FolkTalk.pick(r, "Not yet. Is it any good?",
                    "No! What's in it?"), news.isEmpty() ? tb.author + "'s put a lot in it. Worth a look." : Quill.cap(news) });
            }
        }
        LibraryRecords.Reader ar = shelf.readers.get(a.getUUID());
        if (ar != null && day - ar.lastDay <= 2 && !ar.lastTitle.isEmpty()) {
            out.add(new String[]{ "I've just read \"" + ar.lastTitle + "\".", FolkTalk.pick(r, "Any good?", "And?"),
                FolkTalk.pick(r, "Read it yourself and see!", "You'll like it.", "Better than I expected.") });
        }
        for (Title t : shelf.books) {
            if (t.kind.equals("TRADE") || day - t.written > 3 || t.authorId != null && t.authorId.equals(b.getUUID())) continue;
            out.add(new String[]{ "Have you seen " + t.author + "'s new " + kindWord(t) + ", \"" + t.title + "\"?",
                FolkTalk.pick(r, "I cried at the end.", "Not yet - is it on the shelf?", "Read it twice!", "Everybody's talking about it."),
                FolkTalk.pick(r, "", "I'll have to borrow it.") });
            break;
        }
        return out;
    }

    /** The library on a folk's card: librarian, author, the trade's book it keeps, what it has read. */
    public static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || !LibraryRecords.has(id) || f.isShowcase()) return "";
        Shelf shelf = LibraryRecords.shelf(id);
        List<String> out = new ArrayList<>();
        if (f.getUUID().equals(shelf.librarian)) {
            int n = 0;
            for (Title t : shelf.books) if (!t.missing) n++;
            out.add("the town's librarian, keeper of " + Quill.count(n, "book", "books"));
        }
        List<String> wrote = new ArrayList<>();
        for (Title t : shelf.books) {
            if (!f.getUUID().equals(t.authorId) || t.superseded) continue;
            wrote.add(t.kind.equals("TRADE") ? "keeps " + t.title + " (" + Quill.ordinal(t.edition) + " edition)" : "wrote \"" + t.title + "\" (" + kindWord(t) + ")");
        }
        if (!wrote.isEmpty()) out.add(String.join(", ", wrote.subList(Math.max(0, wrote.size() - 2), wrote.size()))
            + (wrote.size() > 2 ? " and " + (wrote.size() - 2) + " more" : ""));
        LibraryRecords.Reader r = shelf.readers.get(f.getUUID());
        if (r != null) {
            StationTask t = f.stationTask();
            Integer ed = t == StationTask.NONE ? null : r.trades.get(t.name());
            if (ed != null) {
                int pace = workPercent(f);
                out.add("has read its trade's book (" + Quill.ordinal(ed) + " edition): +" + pace + "% at the work"
                    + (f.tradeLevel(t) < APPRENTICE_LEVEL ? ", and learns a quarter quicker" : ""));
            } else if (!r.lastTitle.isEmpty()) {
                out.add("last read \"" + r.lastTitle + "\"");
            }
        }
        return String.join("; ", out);
    }

    /** The gazette's word from the library: yesterday's new books and editions, and what is out on loan. Null for none. */
    @Nullable
    public static String gazette(UUID village, long day) {
        if (!LibraryRecords.has(village)) return null;
        Shelf shelf = LibraryRecords.shelf(village);
        List<String> lines = new ArrayList<>();
        for (Title t : shelf.books) {
            if (t.written != day - 1) continue;
            if (t.kind.equals("TRADE")) lines.add(t.author + " brought out the " + Quill.ordinal(t.edition) + " edition of " + t.title
                + (t.news.isEmpty() ? "." : ": " + t.news.get(0)));
            else lines.add(t.author + "'s new " + kindWord(t) + ", \"" + t.title + "\", is on the shelves.");
        }
        if (lines.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("§lFrom the library§r");
        for (int i = 0; i < Math.min(3, lines.size()); i++) sb.append('\n').append(lines.get(i));
        return sb.toString();
    }

    // ------------------------------------------------------------------ the town's books and the command

    /** The Library page of the town's books (CityScreen): the catalogue, who wrote what, what is out and with whom. */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        Ledger.Building b = building(id);
        out.putBoolean("stands", b != null);
        out.putString("why", b == null ? (wanted(id, Villages.headcount(id)) ? "Planned: " + why(id) + "." : "None yet: a town of " + FROM_FOLK
            + " in the Stone Age builds one.") : "");
        if (!LibraryRecords.has(id)) return out;
        Shelf shelf = LibraryRecords.shelf(id);
        out.putString("librarian", shelf.librarianName);
        out.putLong("librarian_since", shelf.librarianSince);
        ListTag books = new ListTag();
        Map<String, Integer> authors = new LinkedHashMap<>();
        List<Title> order = new ArrayList<>(shelf.books);
        order.sort((x, y) -> {
            int k = rank(x.kind) - rank(y.kind);
            if (k != 0) return k;
            if (x.kind.equals("TRADE") && y.kind.equals("TRADE")) {
                int tr = x.trade.compareTo(y.trade);
                return tr != 0 ? tr : y.edition - x.edition;
            }
            return Long.compare(y.written, x.written);
        });
        for (Title t : order) {
            CompoundTag c = new CompoundTag();
            c.putString("title", t.title);
            c.putString("kind", t.kind);
            c.putString("author", t.author);
            c.putLong("day", t.written);
            c.putInt("edition", t.edition);
            c.putBoolean("old", t.superseded);
            c.putString("where", t.missing ? "missing" : t.where.equals("loan") ? "on loan" + borrower(shelf, t) : t.where.isEmpty() ? "waiting for room" : t.where);
            c.putInt("reads", t.reads);
            c.putString("blurb", t.blurb);
            c.putInt("pages", t.pages.size());
            books.add(c);
            if (!t.kind.equals("TRADE")) authors.merge(t.author, 1, Integer::sum);
        }
        out.put("books", books);
        ListTag au = new ListTag();
        for (Map.Entry<String, Integer> e : authors.entrySet()) au.add(StringTag.valueOf(e.getKey() + ": " + Quill.count(e.getValue(), "book", "books")));
        out.put("authors", au);
        ListTag loans = new ListTag();
        long day = level.getDayTime() / 24000L;
        for (LibraryRecords.Loan l : shelf.loans) {
            Title t = shelf.byId(l.book);
            loans.add(StringTag.valueOf(l.name + " has \"" + (t == null ? "a book" : t.title) + "\", due back day " + l.due
                + (day > l.due ? " (" + (day - l.due) + " late)" : "")));
        }
        out.put("loans", loans);
        int readers = 0;
        for (LibraryRecords.Reader r : shelf.readers.values()) if (r.lastDay >= 0) readers++;
        out.putInt("readers", readers);
        out.putInt("fines", shelf.fines);
        out.putInt("lost", shelf.lost);
        Job j = JOBS.get(id);
        out.putString("writing", j == null ? "" : (level.getEntity(j.folk) instanceof VillageFolkEntity f ? f.displayNameCap() + " is " : "") + j.words);
        out.putString("short", SHORT.getOrDefault(id, ""));
        return out;
    }

    private static int rank(String kind) {
        return switch (kind) {
            case "TRADE" -> 0;
            case "HISTORY" -> 1;
            case "LIFE" -> 2;
            case "POEM" -> 3;
            case "HOWTO" -> 4;
            case "STORY" -> 5;
            default -> 6;
        };
    }

    private static String borrower(Shelf shelf, Title t) {
        for (LibraryRecords.Loan l : shelf.loans) if (l.book == t.id) return " to " + l.name + " till day " + l.due;
        return "";
    }

    /** /village library: the library in lines. */
    public static List<String> status(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        String name = Villages.name(id);
        Ledger.Building b = building(id);
        if (b == null) {
            out.add(name + " has no library. " + (wanted(id, Villages.headcount(id)) ? "It wants one: " + why(id) + "." : "A town of " + FROM_FOLK
                + " in the Stone Age plans one."));
            return out;
        }
        Shelf shelf = LibraryRecords.shelf(id);
        int shelves = 0;
        for (int i = 0; i < SHELF_PLACES.length; i++) if (level.getBlockEntity(shelfAt(b, i)) instanceof ChiseledBookShelfBlockEntity) shelves++;
        out.add("LIBRARY " + name + " at " + b.anchor().toShortString() + ": librarian " + (shelf.librarianName.isEmpty() ? "none yet" : shelf.librarianName)
            + "; " + shelf.books.size() + " books in the catalogue, " + shelves + " of " + SHELF_PLACES.length + " shelves up, the lectern "
            + (lecternStands(level, b) ? "standing" : "wanted") + "; " + shelf.loans.size() + " out on loan; fines " + shelf.fines + ", lost " + shelf.lost);
        Job j = JOBS.get(id);
        if (j != null) out.add("Writing: " + (level.getEntity(j.folk) instanceof VillageFolkEntity f ? f.displayNameCap() + " - " : "") + j.words + " (stage " + j.stage + ")");
        String s = SHORT.get(id);
        if (s != null) out.add("Short of: " + s);
        for (Title t : shelf.books) {
            out.add("- " + t.title + (t.kind.equals("TRADE") ? " (" + Quill.ordinal(t.edition) + " edition" + (t.superseded ? ", old" : "") + ")"
                : " (" + kindWord(t) + ")") + " by " + t.author + ", day " + t.written + ", " + t.pages.size() + " pages, "
                + (t.missing ? "missing" : t.where.isEmpty() ? "waiting for room" : t.where) + ", read " + t.reads + "x");
        }
        for (LibraryRecords.Loan l : shelf.loans) {
            Title t = shelf.byId(l.book);
            out.add("On loan: " + (t == null ? "#" + l.book : t.title) + " to " + l.name + ", due day " + l.due);
        }
        return out;
    }

    /** /village library write: the next piece of writing done now, out of the stores. Returns what happened. */
    public static String writeNow(ServerLevel level, Villages.Village v) {
        boolean was = instant;
        instant = true;
        try {
            int before = LibraryRecords.shelf(v.id()).books.size();
            JOBS.remove(v.id());
            tickForTests(level, v);
            Shelf shelf = LibraryRecords.shelf(v.id());
            if (shelf.books.size() > before) {
                Title t = shelf.books.get(shelf.books.size() - 1);
                return "written: " + t.title + " by " + t.author + " (" + t.pages.size() + " pages, " + (t.where.isEmpty() ? "waiting for room" : t.where) + ")";
            }
            String s = SHORT.get(v.id());
            return s == null ? "nothing to write" : "short of " + s;
        } finally {
            instant = was;
        }
    }

    /** A library book for an operator to read: a fair copy of the book named (or the newest), into their hands. */
    public static String copyForOp(Villages.Village v, Player p, String words) {
        Shelf shelf = LibraryRecords.shelf(v.id());
        Title t = title(shelf, " " + words.toLowerCase(Locale.ROOT) + " ");
        if (t == null) for (Title b : shelf.books) if (t == null || b.written > t.written) t = b;
        if (t == null) return "The library has no books yet.";
        give(p, Archive.book(t.title, t.author, 1, t.pages));
        return "A copy of " + t.title + " by " + t.author + " (" + t.pages.size() + " pages).";
    }

    /**
     * /village library stage: a library set out where an operator stands, for the pictures (the smoke test): the building
     * stamped and put in the nearest town's register, its lectern and every shelf up, and the town's books written into it
     * at once (staged: the paper is the stage's, not the stores'). Says where to look from.
     */
    public static List<String> stage(ServerLevel level, Villages.Village v, BlockPos at) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        BlockPos ground = at.below();
        for (BlockPos p : BlockPos.betweenClosed(ground.offset(-7, 1, -7), ground.offset(7, 12, 7))) {
            if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        }
        for (BlockPos p : BlockPos.betweenClosed(ground.offset(-7, -2, -7), ground.offset(7, 0, 7))) level.setBlock(p, Blocks.DIRT.defaultBlockState(), 2);
        BlockPos anchor = ground.above();
        BuildGoal.stamp(level, STRUCTURE, anchor, Direction.NORTH, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(id, STRUCTURE, anchor, Direction.NORTH);
        Ledger.Building b = new Ledger.Building(STRUCTURE, anchor.immutable(), Direction.NORTH);
        setLectern(level, b);
        for (int i = 0; i < SHELF_PLACES.length; i++) setShelf(level, b, i);
        Shelf shelf = LibraryRecords.shelf(id);
        long day = level.getDayTime() / 24000L;
        librarian(level, id, shelf, day);
        TradeBooks.Ctx c = new TradeBooks.Ctx(level, v, day);
        int written = 0;
        for (StationTask t : TradeBooks.trades(c)) {
            if (shelf.tradeBook(t.name()) != null) continue;
            TradeBooks.Draft d = TradeBooks.draft(c, t, shelf);
            if (d == null) continue;
            staged(level, v, b, shelf, TradeBooks.write(d), d.master(), d, day);
            written++;
        }
        for (int i = 0; i < 6; i++) {
            Map.Entry<Authors.Idea, VillageFolkEntity> pick = Authors.choose(c, shelf, f -> true);
            if (pick == null) break;
            staged(level, v, b, shelf, pick.getKey().write().apply(pick.getValue()), pick.getValue(), null, day);
            written++;
        }
        LibraryRecords.touch();
        out.add(written + " books written into the library of " + Villages.name(id));
        out.add(view(b, "library-hall", 0, 1.6, -3.5, 0, 1.0, 3));
        out.add(view(b, "library-front", 0, 3.5, -12, 0, 2.5, 0));
        return out;
    }

    private static void staged(ServerLevel level, Villages.Village v, Ledger.Building b, Shelf shelf, Quill.Book book, VillageFolkEntity by,
                               @Nullable TradeBooks.Draft d, long day) {
        Title t = new Title();
        t.id = shelf.nextId++;
        t.kind = book.kind();
        t.title = book.title();
        t.author = book.author();
        t.authorId = by.getUUID();
        t.blurb = book.blurb();
        t.subject = book.subject();
        t.written = day;
        t.pages.addAll(book.pages());
        if (d != null) {
            t.trade = d.key();                                             // [weave]
            t.edition = d.facts().edition();
            t.facts = d.line();
        }
        t.where = shelve(level, v, b, shelf, item(v.id(), t, 0), true, null);
        shelf.books.add(t);
    }

    private static String view(Ledger.Building b, String name, double dx, double h, double dz, double tx, double th, double tz) {
        double[] from = spot(b, dx, h, dz), to = spot(b, tx, th, tz);
        return String.format(Locale.ROOT, "VIEW %s %.1f %.1f %.1f %.1f %.1f %.1f", name, from[0], from[1], from[2], to[0], to[1], to[2]);
    }

    private static double[] spot(Ledger.Building b, double dx, double h, double dz) {
        Direction back = b.facing(), right = back.getClockWise();
        double x = b.anchor().getX() + 0.5 + right.getStepX() * dx + back.getStepX() * dz;
        double z = b.anchor().getZ() + 0.5 + right.getStepZ() * dx + back.getStepZ() * dz;
        return new double[]{ x, b.anchor().getY() + h, z };
    }

    // ------------------------------------------------------------------ the tests

    /** Tests: a trade's book written (or brought up to date) now, out of the stores. Returns its catalogue entry, or null. */
    @Nullable
    public static Title writeTradeBookForTests(ServerLevel level, Villages.Village v, StationTask trade) {
        Ledger.Building b = building(v.id());
        if (b == null) return null;
        Shelf shelf = LibraryRecords.shelf(v.id());
        TradeBooks.Draft d = TradeBooks.draftOf(level, v, trade, shelf);
        if (d == null) return null;
        Job j = new Job(v.id(), d.master().getUUID(), "writing " + bookTitle(trade), level.getGameTime(), d, null, -1);
        if (!fetch(level, v, j, d.master())) return null;
        finish(level, v, b, j, d.master());
        return shelf.tradeBook(trade.name());
    }

    /** Tests: the next of the town's other books written now by its chosen author, out of the stores; its entry or null. */
    @Nullable
    public static Title writeBookForTests(ServerLevel level, Villages.Village v, @Nullable String subjectStart) {
        Ledger.Building b = building(v.id());
        if (b == null) return null;
        Shelf shelf = LibraryRecords.shelf(v.id());
        TradeBooks.Ctx c = new TradeBooks.Ctx(level, v, level.getDayTime() / 24000L);
        List<Authors.Idea> ideas = Authors.ideas(c, shelf);
        for (Authors.Idea i : ideas) {
            if (subjectStart != null && !i.subject().startsWith(subjectStart)) continue;
            VillageFolkEntity best = null;
            double bestScore = -1;
            for (VillageFolkEntity f : c.folk) {
                double s = Authors.fitness(f, i, c);
                if (s > bestScore) { bestScore = s; best = f; }
            }
            if (best == null || bestScore < 0) continue;
            Job j = new Job(v.id(), best.getUUID(), "writing", level.getGameTime(), null, i, -1);
            if (!fetch(level, v, j, best)) return null;
            int before = shelf.books.size();
            finish(level, v, b, j, best);
            return shelf.books.size() > before ? shelf.books.get(shelf.books.size() - 1) : null;
        }
        return null;
    }

    /** Tests: the subjects the town could write about now. */
    public static List<String> ideasForTests(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        for (Authors.Idea i : Authors.ideas(new TradeBooks.Ctx(level, v, level.getDayTime() / 24000L), LibraryRecords.shelf(v.id()))) out.add(i.subject());
        return out;
    }

    /** Tests: this folk reads the current edition of its trade's book to the end, as a visit would. */
    public static boolean readTradeBookForTests(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || !(f.level() instanceof ServerLevel level)) return false;
        Shelf shelf = LibraryRecords.shelf(id);
        Title t = shelf.tradeBook(f.stationTask().name());
        if (t == null) return false;
        finishReading(level, f, shelf, t, level.getDayTime() / 24000L);
        return true;
    }

    /** Tests: the library's books on its shelves and lectern, by title. */
    public static List<String> shelvedForTests(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        Ledger.Building b = building(village);
        if (b == null) return out;
        if (isLibraryBook(onLectern(level, b))) out.add(Archive.titleOf(onLectern(level, b)));
        for (int i = 0; i < SHELF_PLACES.length; i++) {
            if (!(level.getBlockEntity(shelfAt(b, i)) instanceof ChiseledBookShelfBlockEntity sh)) continue;
            for (int s = 0; s < SLOTS; s++) if (isLibraryBook(sh.getItem(s))) out.add(Archive.titleOf(sh.getItem(s)));
        }
        return out;
    }

    /** Tests: where the lectern stands, and the shelf places. */
    public static BlockPos lecternForTests(UUID village) {
        Ledger.Building b = building(village);
        return b == null ? BlockPos.ZERO : lecternAt(b);
    }

    public static BlockPos shelfForTests(UUID village, int i) {
        Ledger.Building b = building(village);
        return b == null ? BlockPos.ZERO : shelfAt(b, i);
    }

    /** Tests: the mornings' look at the loans, on this day. */
    public static void overdueForTests(ServerLevel level, Villages.Village v, long day) {
        Shelf shelf = LibraryRecords.shelf(v.id());
        overdue(level, v, shelf, librarian(level, v.id(), shelf, day), day);
    }

    /** Tests: what the library is short of, or "". */
    public static String shortForTests(UUID village) {
        return SHORT.getOrDefault(village, "");
    }

    /** A trade's book by its short title: "The Farmer's Book". */
    static String bookTitle(StationTask t) {
        return "The " + com.jrpetty.mcassistant.village.TradeBookWriter.craft(t.name(), t.title).possessive() + " Book";
    }

    /** [weave] A book of best practice by its key: a trade's, or the librarian's own ("LIBRARY"). */
    static String bookTitle(String key, String noun) {
        return "The " + com.jrpetty.mcassistant.village.TradeBookWriter.craft(key, noun).possessive() + " Book";
    }

    /** [weave] Tests: the librarian's own book written now, out of the stores. Its catalogue entry, or null. */
    @Nullable
    public static Title writeLibrarianBookForTests(ServerLevel level, Villages.Village v) {
        Ledger.Building b = building(v.id());
        if (b == null) return null;
        Shelf shelf = LibraryRecords.shelf(v.id());
        TradeBooks.Draft d = TradeBooks.librarianOf(level, v, shelf);
        if (d == null) return null;
        Job j = new Job(v.id(), d.master().getUUID(), "writing " + bookTitle(d.key(), d.noun()), level.getGameTime(), d, null, -1);
        if (!fetch(level, v, j, d.master())) return null;
        finish(level, v, b, j, d.master());
        return shelf.tradeBook("LIBRARY");
    }
}
