package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The village school. Once a village of ten or more has three children or more it wants a
 * <b>schoolhouse</b> (Villages: one of the amenities, after what the age asks for), and its builders
 * raise one like any other building, out of the stores: a timber schoolroom with two rows of desks
 * (a top slab for a desk, a stair for a bench), a lectern, a bookshelf either side of a cupboard of
 * barrels, and over the cupboard the blackboard, which the teacher puts up itself out of the stores'
 * black wool or slate (and a book on the lectern, and a lectern or a shelf the builders had no books
 * for). It is made over with the ages like every other building (Ages) and furnished for them
 * (Interiors).
 *
 * <p><b>The teacher</b> is a grown folk with a trade, chosen for it: an old hand first, then the
 * curious, the easygoing (patient) and the readers, the wise (the best at their trade); never the
 * watch or the elder. It teaches the mornings and works its own trade the afternoons, and is paid a
 * teacher's coin a day on top of its trade's wage (Wealth). The Jobs page shows it as the Teacher.
 *
 * <p><b>Lessons</b>, on working mornings (never the day of rest; from a little after the morning
 * assembly to a little before noon): the children of school age (a day old to grown) go to their
 * desks and the teacher stands at the lectern before the blackboard and gives the day's lesson, a
 * trade a day, a line now and then. Each child <b>leans</b> to a trade, from its parents' trades,
 * what it watched at its apprenticeship, its own nature, what it loves doing and what it loves to be
 * given, and what the village wants hands at. Every beat of a lesson at its desk with the teacher
 * in the room it learns a little of that trade, and a little of the day's lesson besides; up to a
 * schooling's worth: level five under a plain teacher, up to eight under a good one (its level at its
 * own trade, and an old, patient or curious one). Two full mornings make a full schooling; a child
 * that misses one (the day of rest, playing truant) leaves with less. An easygoing or grumpy child
 * now and then plays truant; a hardworking one never.
 *
 * <p>When it grows up it takes up the trade it leaned to, with what it learned in hand (unless the
 * village has more than enough hands at it already: then the levels wait for it, and it goes where
 * the village needs it). A child that never went to school starts its trade at nought, as before.
 *
 * <p>Kept with the world, in the village's ledger notes: the teacher, each pupil's desk (its
 * leaning, why, its mornings), the mornings taught and who has left school. What a pupil learned is
 * its own experience (VillageFolkEntity), kept with it.
 */
public final class School {

    private School() {}

    /** Lessons: from after the morning assembly to before noon (day ticks). */
    public static final long LESSON_FROM = 1500L, LESSON_TO = 5500L;
    /** How often a pupil at its desk learns a little (ticks). */
    static final int BEAT = 100;
    /** Beats at the desk that make a full schooling (about two mornings). */
    static final int BEATS_TO_CAP = 60;
    /** A village wants a school once it has this many children, and this many folk. */
    public static final int CHILDREN = 3, FOLK = 10;
    /** A schooling's worth, in levels: five under a plain teacher, eight at most. */
    static final int MIN_CAP = 5, MAX_CAP = 8;
    /** What the day's lessons teach of other trades than a child's own, at most (a level's worth). */
    static final int GENERAL_CAP = 2;

    private static final String TEACHER = "school/teacher", TEACHER_NAME = "school/teacher_name",
        TEACHER_SINCE = "school/teacher_since", TAUGHT = "school/taught", GRADUATES = "school/graduates", PUPIL = "school/pupil/";
    private static final UUID NOBODY = new UUID(0L, 0L);

    /** The teacher at the lectern, until when (game time). */
    private static final Map<UUID, Long> TEACHING = new ConcurrentHashMap<>();
    /** A lesson called for now, whatever the hour (/village school lesson), until when. */
    private static final Map<UUID, Long> FORCED = new ConcurrentHashMap<>();
    /** Each pupil's last beat of learning (game time). */
    private static final Map<UUID, Long> BEAT_AT = new ConcurrentHashMap<>();
    /** Each teacher's morning: {day, in the room yet, fitted out}. */
    private static final Map<UUID, long[]> MORNING = new ConcurrentHashMap<>();
    /** Each village's teacher (NOBODY: none), read from the ledger once. */
    private static final Map<UUID, UUID> TEACHERS = new ConcurrentHashMap<>();
    /** Each village's schoolhouse, looked up now and then: {the building or null, when}. */
    private static final Map<UUID, Object[]> HOUSE = new ConcurrentHashMap<>();
    /** Each village's pupils this morning, looked over now and then. */
    private static final Map<UUID, Object[]> PUPILS = new ConcurrentHashMap<>();
    /** The day's lesson: {day, trade}. */
    private static final Map<UUID, long[]> TOPIC = new ConcurrentHashMap<>();
    /** The last day a morning was counted as taught. */
    private static final Map<UUID, Long> TAUGHT_DAY = new ConcurrentHashMap<>();
    /** Children told off for truancy today (so it is remembered once). */
    private static final Map<UUID, Long> TRUANT_TOLD = new ConcurrentHashMap<>();

    public static void resetForTests() {
        TEACHING.clear();
        FORCED.clear();
        BEAT_AT.clear();
        MORNING.clear();
        TEACHERS.clear();
        HOUSE.clear();
        PUPILS.clear();
        TOPIC.clear();
        TAUGHT_DAY.clear();
        TRUANT_TOLD.clear();
    }

    // ------------------------------------------------------------------ the schoolhouse

    /** Is a school wanted (Villages.projectsWanted): ten folk or more, three children or more. */
    public static boolean wanted(UUID village, int folk) {
        return folk >= FOLK && children(village) >= CHILDREN;
    }

    /** The village's children (the ones about the place). */
    static int children(UUID village) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.isBaby()) n++;
        return n;
    }

    /** Why the village wants one, in a line (Villages.whyBuild). */
    public static String why(UUID village) {
        int n = children(village);
        return "a schoolhouse: " + n + (n == 1 ? " child" : " children") + " in the village, and nobody to teach them a trade";
    }

    /** "a miner", "an enchanter": a trade with its article. */
    public static String a(StationTask t) {
        String w = t.title.toLowerCase(Locale.ROOT);
        return ("aeiou".indexOf(w.charAt(0)) >= 0 ? "an " : "a ") + w;
    }

    /** The village's schoolhouse, if one stands. {@code now} is the game time, or below nought to look afresh. */
    @Nullable
    static Ledger.Building schoolhouse(UUID village, long now) {
        Object[] c = HOUSE.get(village);
        if (now >= 0 && c != null && now - (Long) c[1] < 100L && now >= (Long) c[1]) return (Ledger.Building) c[0];
        Ledger.Building found = null;
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (b.structure().equals("school")) { found = b; break; }
        }
        HOUSE.put(village, new Object[]{ found, Math.max(0L, now) });
        return found;
    }

    /** Does the village have a school standing? */
    public static boolean stands(UUID village) {
        return schoolhouse(village, -1L) != null;
    }

    // ------------------------------------------------------------------ where things are in it

    /** A spot in the schoolhouse by its drawing: across (right +), up, and toward the back (+). */
    static BlockPos at(Ledger.Building b, int dx, int h, int dz) {
        Direction back = b.facing(), right = back.getClockWise();
        return b.anchor().relative(right, dx).relative(back, dz).above(h);
    }

    /** Where the lectern stands in the drawing: {dx, dz} (the teacher stands behind it, the board behind that). */
    private static int[] lecternCell() {
        for (Blueprints.Cell c : Blueprints.cells("school")) {
            if (c.h() == 0 && c.key().part() == BuildGoal.Part.LECTERN) return new int[]{ c.dx(), c.dz() };
        }
        return new int[]{ 0, 1 };
    }

    static BlockPos lectern(Ledger.Building b) {
        int[] l = lecternCell();
        return at(b, l[0], 0, l[1]);
    }

    /** Where the teacher stands: behind the lectern, before the blackboard. */
    static BlockPos teacherSpot(Ledger.Building b) {
        int[] l = lecternCell();
        return at(b, l[0], 0, l[1] + 1);
    }

    /** The blackboard: three across and two high, on the back wall over the cupboard. */
    static List<BlockPos> board(Ledger.Building b) {
        int[] l = lecternCell();
        List<BlockPos> out = new ArrayList<>();
        for (int h = 2; h >= 1; h--) for (int dx = -1; dx <= 1; dx++) out.add(at(b, l[0] + dx, h, l[1] + 2));
        return out;
    }

    /** The benches (the stairs on the schoolroom floor), the front row first. */
    static List<BlockPos> seats(Ledger.Building b) {
        List<int[]> cells = new ArrayList<>();
        for (Blueprints.Cell c : Blueprints.cells("school")) {
            if (c.h() == 0 && c.key().part() == BuildGoal.Part.BLOCK && c.key().style() == Blueprints.Style.ROOF_STAIR) {
                cells.add(new int[]{ c.dx(), c.dz() });
            }
        }
        cells.sort(Comparator.<int[]>comparingInt(c -> -c[1]).thenComparingInt(c -> Math.abs(c[0])).thenComparingInt(c -> c[0]));
        List<BlockPos> out = new ArrayList<>();
        for (int[] c : cells) out.add(at(b, c[0], 0, c[1]));
        return out;
    }

    /** Is it inside the schoolroom (between the walls, on its floor)? */
    static boolean inClass(Entity e, Ledger.Building b) {
        Direction back = b.facing(), right = back.getClockWise();
        int ox = e.getBlockX() - b.anchor().getX(), oz = e.getBlockZ() - b.anchor().getZ();
        int dx = ox * right.getStepX() + oz * right.getStepZ();
        int dz = ox * back.getStepX() + oz * back.getStepZ();
        int dy = e.getBlockY() - b.anchor().getY();
        return Math.abs(dx) <= 2 && dz >= -3 && dz <= 3 && dy >= -1 && dy <= 2;
    }

    // ------------------------------------------------------------------ when

    /** Is it lesson time in this village: a working morning (or a lesson called for), and no alarm? */
    static boolean lessonTime(ServerLevel level, UUID village) {
        if (Raids.underAlarm(village)) return false;
        Long forced = FORCED.get(village);
        if (forced != null && level.getGameTime() < forced) return true;
        long t = level.getDayTime() % 24000L, day = level.getDayTime() / 24000L;
        if (t < LESSON_FROM || t >= LESSON_TO) return false;
        return !RestDay.today(village, day);
    }

    /** Too late to set out for school this morning (the last hour of it; never in a lesson called for). */
    static boolean tooLate(ServerLevel level, UUID village) {
        Long forced = FORCED.get(village);
        if (forced != null && level.getGameTime() < forced) return false;
        return level.getDayTime() % 24000L >= LESSON_TO - 1000L;
    }

    /** A lesson now, whatever the hour, for a while (/village school lesson). */
    public static void callLesson(ServerLevel level, UUID village, int ticks) {
        FORCED.put(village, level.getGameTime() + Math.max(200, ticks));
        PUPILS.remove(village);
    }

    // ------------------------------------------------------------------ the teacher

    /** The village's teacher's id, NOBODY for none (cheap: read from the ledger once). */
    private static UUID teacherId(UUID village) {
        UUID got = TEACHERS.get(village);
        if (got != null) return got;
        String n = Ledger.note(village, TEACHER);
        UUID id = NOBODY;
        try {
            if (n != null && !n.isEmpty()) id = UUID.fromString(n);
        } catch (IllegalArgumentException ignored) { }
        TEACHERS.put(village, id);
        return id;
    }

    /** Is this folk its village's teacher? */
    public static boolean isTeacher(VillageFolkEntity f) {
        UUID village = f.ownerId();
        return village != null && !f.isBaby() && f.getUUID().equals(teacherId(village));
    }

    /** Is it at the lectern just now (its own trade waits: VillageFolkEntity.onShift)? */
    public static boolean teaching(VillageFolkEntity f) {
        Long until = TEACHING.get(f.getUUID());
        return until != null && f.level().getGameTime() < until;
    }

    /** The teacher, if it is about the place (and, with {@code choose}, one chosen if there is none fit). */
    @Nullable
    public static VillageFolkEntity teacher(ServerLevel level, UUID village, boolean choose) {
        UUID id = teacherId(village);
        if (!id.equals(NOBODY)) {
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (a instanceof VillageFolkEntity f && f.getUUID().equals(id)) {
                    if (fit(f)) return f;
                    break;
                }
            }
        }
        if (!choose) return null;
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || !fit(f)) continue;
            int s = score(f);
            if (s > bestScore || s == bestScore && best != null && f.getUUID().compareTo(best.getUUID()) < 0) { bestScore = s; best = f; }
        }
        if (best == null) return null;
        appoint(level, village, best);
        return best;
    }

    /** Can it teach: grown, with a trade (not the watch), the village's own, at home, and not its elder? */
    static boolean fit(VillageFolkEntity f) {
        return f.isAlive() && !f.isBaby() && !f.isShowcase() && !f.isHired() && !f.isElder()
            && f.stationTask() != StationTask.NONE && f.stationTask() != StationTask.GUARD
            && f.trip() == null && !Scouts.out(f);
    }

    /** How good a teacher it would make: an old hand, the curious, the patient, the readers, the wise. */
    static int score(VillageFolkEntity f) {
        Social.Life life = f.life();
        int s = 0;
        if (f.isOld()) s += 4;
        if (life.has(Social.Trait.CURIOUS)) s += 3;
        if (life.has(Social.Trait.EASYGOING)) s += 2;          // patient
        if (life.has(Social.Trait.GENEROUS)) s += 1;
        if (life.has(Social.Trait.CHEERFUL)) s += 1;
        if (life.has(Social.Trait.SOCIABLE)) s += 1;
        if (life.has(Social.Trait.SHY)) s -= 1;
        if (life.has(Social.Trait.GRUMPY)) s -= 3;
        Persona me = f.persona();
        if (me.rolled()) {
            if (me.hobby() == Persona.Hobby.READING) s += 2;
            if (me.loves() == Persona.Gift.BOOKS) s += 1;
        }
        if (life.children() > 0) s += 1;
        s += Math.min(6, bestLevel(f) / 5);                    // the wise: the best at what they do
        // Not the only hand at its trade: a village's one smith is wanted at the forge.
        UUID village = f.ownerId();
        if (village != null && f.stationTask().isCraft()) {
            int same = 0;
            for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == f.stationTask()) same++;
            if (same <= 1) s -= 2;
        }
        return s;
    }

    /** Its best level at any trade it has worked. */
    static int bestLevel(VillageFolkEntity f) {
        return Math.max(f.veteranLevel(), f.tradeLevel(FolkSkills.bestTrade(f)));
    }

    /** Why it was chosen, in a few words: "an old hand, curious and easygoing, fond of reading". */
    static String whyTeacher(VillageFolkEntity f) {
        List<String> why = new ArrayList<>();
        if (f.isOld()) why.add("an old hand of " + f.ageYears());
        List<String> nature = new ArrayList<>();
        if (f.life().has(Social.Trait.CURIOUS)) nature.add("curious");
        if (f.life().has(Social.Trait.EASYGOING)) nature.add("patient");
        if (f.life().has(Social.Trait.GENEROUS)) nature.add("kind");
        if (!nature.isEmpty()) why.add(String.join(" and ", nature));
        if (f.persona().rolled() && f.persona().hobby() == Persona.Hobby.READING) why.add("fond of reading");
        int lv = bestLevel(f);
        if (lv >= 10) why.add("level " + lv + " at its trade");
        return why.isEmpty() ? "the best the village had" : String.join(", ", why);
    }

    /** It takes the school. */
    private static void appoint(ServerLevel level, UUID village, VillageFolkEntity f) {
        long day = level.getDayTime() / 24000L;
        String before = Ledger.note(village, TEACHER_NAME);
        Ledger.note(village, TEACHER, f.getUUID().toString());
        Ledger.note(village, TEACHER_NAME, f.displayNameCap());
        Ledger.note(village, TEACHER_SINCE, Long.toString(day));
        TEACHERS.put(village, f.getUUID());
        f.persona().remember(day, "I was asked to teach the village's children", 7);
        Villages.tell(village, day, before == null || before.isEmpty() || before.equals(f.displayNameCap())
            ? f.displayNameCap() + " is to teach at the school (" + whyTeacher(f) + ")"
            : f.displayNameCap() + " took the school over from " + before);
        FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "Me? Teach the little ones? I'd be honoured.",
            "The school, is it? Well — somebody has to keep them in order.", "I'll teach them what I know. It's the least I can do."));
    }

    /** A schooling's worth under this teacher, in levels: five, and one more for each of a good hand
     *  (level ten), a master (twenty) and an old, patient or curious one; eight at most. */
    public static int capLevel(@Nullable VillageFolkEntity teacher) {
        if (teacher == null) return MIN_CAP;
        int lv = bestLevel(teacher);
        Social.Life life = teacher.life();
        int cap = MIN_CAP + (lv >= 10 ? 1 : 0) + (lv >= 20 ? 1 : 0)
            + (teacher.isOld() || life.has(Social.Trait.EASYGOING) || life.has(Social.Trait.CURIOUS) ? 1 : 0);
        return Math.min(MAX_CAP, cap);
    }

    /** What teaching pays a day on top of its trade's wage (Wealth): a miner's rate at the place's standing. */
    public static int pay(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || !isTeacher(f) || schoolhouse(village, f.level().getGameTime()) == null) return 0;
        return Math.max(1, (2 * Wealth.standing(village) + 5) / 10);
    }

    /** The teacher, if it is about (for the books; no choosing). */
    @Nullable
    private static VillageFolkEntity teacherNow(UUID village) {
        UUID id = teacherId(village);
        if (id.equals(NOBODY)) return null;
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && f.getUUID().equals(id)) return f;
        return null;
    }

    // ------------------------------------------------------------------ the pupils' desks

    /** A pupil's desk, kept with the world: the trade it leans to and why, how many mornings it came, and when last. */
    record Desk(StationTask leaning, String why, int mornings, long lastDay) {
        String encode() {
            return leaning.name() + "\t" + mornings + "\t" + lastDay + "\t" + why.replace('\t', ' ').replace('\n', ' ');
        }

        @Nullable
        static Desk decode(@Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            String[] p = s.split("\t", 4);
            try {
                return new Desk(StationTask.valueOf(p[0]), p.length > 3 ? p[3] : "", p.length > 1 ? Integer.parseInt(p[1]) : 0,
                    p.length > 2 ? Long.parseLong(p[2]) : -1L);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    @Nullable
    static Desk desk(UUID village, VillageFolkEntity f) {
        return Desk.decode(Ledger.note(village, PUPIL + f.getUUID()));
    }

    private static void keep(UUID village, VillageFolkEntity f, Desk d) {
        Ledger.note(village, PUPIL + f.getUUID(), d.encode());
    }

    /** Its first morning: a desk of its own, and the trade it leans to. */
    private static Desk enrol(UUID village, VillageFolkEntity f, long day) {
        Object[] l = leaning(f, village);
        Desk d = new Desk((StationTask) l[0], (String) l[1], 0, -1L);
        keep(village, f, d);
        f.persona().remember(day, "I started school; I want to be " + a(d.leaning()), 6);
        return d;
    }

    /** The trade a child leans to, and why: {trade, why}. */
    static Object[] leaning(VillageFolkEntity f, UUID village) {
        Map<StationTask, Integer> score = new java.util.EnumMap<>(StationTask.class);
        Map<StationTask, List<Object[]>> reasons = new java.util.EnumMap<>(StationTask.class);
        java.util.function.BiConsumer<StationTask, Object[]> add = (t, r) -> {
            score.merge(t, (Integer) r[0], Integer::sum);
            if (r[1] != null) reasons.computeIfAbsent(t, k -> new ArrayList<>()).add(r);
        };
        List<StationTask> trades = new ArrayList<>();
        for (StationTask t : StationTask.values()) {
            if (t == StationTask.NONE || t == StationTask.GUARD || t == StationTask.STORE || t == StationTask.SCOUT) continue;
            trades.add(t);
        }
        // Its parents' trades.
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity p) || p == f || p.isBaby() || !trades.contains(p.stationTask())) continue;
            boolean parent = f.parentIds().contains(p.getUUID())
                || !f.life().parents().isEmpty() && f.life().parents().contains(p.displayNameCap());
            if (parent) add.accept(p.stationTask(), new Object[]{ 8, "its parent " + p.displayNameCap() + " is a "
                + p.stationTask().title.toLowerCase(Locale.ROOT) });
        }
        // What it has watched at a grown-up's side.
        if (trades.contains(f.apprenticedTo())) add.accept(f.apprenticedTo(), new Object[]{ 3, "it has watched it done at a grown-up's side" });
        // Its nature.
        if (f.life().rolled()) {
            for (Social.Trait tr : f.life().traits()) {
                for (StationTask t : trades) {
                    Skill.Fit fit = Skill.fit(tr, t);
                    if (fit.percent() == 0) continue;
                    // (A hard worker is good at everything: that is no reason for one trade more than another.)
                    boolean telling = fit.percent() >= 8 && tr != Social.Trait.HARDWORKING;
                    add.accept(t, new Object[]{ fit.percent() / 3, telling ? tr.label + ": " + fit.why() : null });
                }
            }
        }
        // What it loves doing, and what it loves to be given.
        Persona me = f.persona();
        if (me.rolled()) {
            String loves = "it loves " + me.hobby().doing;
            switch (me.hobby()) {
                case FISHING -> add.accept(StationTask.FISH, new Object[]{ 4, loves });
                case GARDENING -> { add.accept(StationTask.FARM, new Object[]{ 3, loves }); add.accept(StationTask.BEEKEEP, new Object[]{ 2, loves }); }
                case READING -> { add.accept(StationTask.ENCHANT, new Object[]{ 4, loves }); add.accept(StationTask.BREW, new Object[]{ 2, loves }); }
                case WHITTLING -> { add.accept(StationTask.WOOD, new Object[]{ 3, loves }); add.accept(StationTask.SMITH, new Object[]{ 1, null }); }
                case WALKING -> add.accept(StationTask.HUNT, new Object[]{ 3, loves });
                case STARGAZING -> add.accept(StationTask.MINE, new Object[]{ 1, null });
                case MUSIC -> add.accept(StationTask.COOK, new Object[]{ 2, loves });
                case CARDS -> add.accept(StationTask.SHOP, new Object[]{ 3, loves });
            }
            String given = "it loves to be given " + me.loves().words;
            switch (me.loves()) {
                case GEMS -> add.accept(StationTask.MINE, new Object[]{ 3, given });
                case TOOLS -> add.accept(StationTask.SMITH, new Object[]{ 3, given });
                case WOOL -> { add.accept(StationTask.TAILOR, new Object[]{ 2, given }); add.accept(StationTask.RANCH, new Object[]{ 2, given }); }
                case FISH -> add.accept(StationTask.FISH, new Object[]{ 2, given });
                case SWEETS -> { add.accept(StationTask.COOK, new Object[]{ 2, given }); add.accept(StationTask.BEEKEEP, new Object[]{ 2, given }); }
                case FLOWERS -> { add.accept(StationTask.FARM, new Object[]{ 2, given }); add.accept(StationTask.BEEKEEP, new Object[]{ 1, null }); }
                case BOOKS -> add.accept(StationTask.ENCHANT, new Object[]{ 2, given });
                case GOLD -> add.accept(StationTask.SHOP, new Object[]{ 2, given });
                default -> { }
            }
        }
        // And what the village has a use for: a child dreams, but mostly of what it sees about it.
        StationTask needed = Villages.needed(village);
        for (StationTask t : trades) {
            boolean wants = Villages.wants(village, t);
            add.accept(t, new Object[]{ wants ? 2 : -6, null });
            if (!Villages.craftReady(village, t)) add.accept(t, new Object[]{ -3, null });
        }
        if (trades.contains(needed)) add.accept(needed, new Object[]{ 2, null });
        StationTask best = trades.contains(needed) ? needed : StationTask.FARM;
        int bestScore = Integer.MIN_VALUE;
        int salt = Math.floorMod(f.getUUID().hashCode(), 97);
        for (StationTask t : trades) {
            int s = score.getOrDefault(t, 0) * 100 + Math.floorMod(salt + t.ordinal() * 31, 97);
            if (s > bestScore) { bestScore = s; best = t; }
        }
        List<Object[]> why = new ArrayList<>(reasons.getOrDefault(best, List.of()));
        why.sort(Comparator.comparingInt((Object[] r) -> -(Integer) r[0]));
        List<String> words = new ArrayList<>();
        for (Object[] r : why) {
            if ((Integer) r[0] <= 0 || words.contains((String) r[1])) continue;
            words.add((String) r[1]);
            if (words.size() >= 2) break;
        }
        String said = words.isEmpty() ? (best == needed ? "the village needs hands at it" : "it took to it")
            : String.join("; ", words);
        return new Object[]{ best, said };
    }

    /** Does it play truant this morning? Now and then, by its nature: an easygoing or grumpy child,
     *  a sociable one that would rather play; never a hardworking one. The same answer all morning. */
    static boolean truant(VillageFolkEntity f, long day) {
        Social.Life life = f.life();
        if (life.has(Social.Trait.HARDWORKING)) return false;
        int chance = (life.has(Social.Trait.EASYGOING) ? 30 : 0) + (life.has(Social.Trait.GRUMPY) ? 20 : 0)
            + (life.has(Social.Trait.SOCIABLE) ? 10 : 0) - (life.has(Social.Trait.CURIOUS) ? 15 : 0);
        if (chance <= 0) return false;
        long h = f.getUUID().getLeastSignificantBits() * 31L + day * 1_000_003L;
        return Math.floorMod(h ^ (h >>> 17), 100) < Math.min(60, chance);
    }

    /** Of school age today: a day old, not yet grown, at home. */
    static boolean schoolAge(VillageFolkEntity f, long day) {
        long born = f.bornDay();
        return f.isBaby() && born != VillageFolkEntity.UNKNOWN && day - born >= 1 && day - born < VillageFolkEntity.GROW_DAYS;
    }

    /** The village's pupils this morning (of school age, not playing truant), in a fixed order. */
    @SuppressWarnings("unchecked")
    static List<VillageFolkEntity> pupils(ServerLevel level, UUID village) {
        long now = level.getGameTime(), day = level.getDayTime() / 24000L;
        Object[] c = PUPILS.get(village);
        if (c != null && now - (Long) c[1] < 100L && now >= (Long) c[1]) return (List<VillageFolkEntity>) c[0];
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isShowcase() && schoolAge(f, day) && !truant(f, day)) out.add(f);
        }
        out.sort(Comparator.comparing(VillageFolkEntity::getUUID));
        PUPILS.put(village, new Object[]{ out, now });
        return out;
    }

    /** Where this pupil sits: its bench, or (more pupils than benches) a place down the aisle. */
    static BlockPos seatFor(ServerLevel level, UUID village, Ledger.Building b, VillageFolkEntity f) {
        List<VillageFolkEntity> all = pupils(level, village);
        int i = Math.max(0, all.indexOf(f));
        List<BlockPos> seats = seats(b);
        if (i < seats.size()) return seats.get(i);
        int extra = i - seats.size();
        int[][] aisle = { { 0, 0 }, { 0, -1 }, { 0, -2 }, { 0, -3 }, { -1, 2 }, { 1, 2 } };
        int[] a = aisle[extra % aisle.length];
        return at(b, a[0], 0, a[1]);
    }

    // ------------------------------------------------------------------ the morning

    /**
     * The school's morning, for one folk (from its tick, a step at a time): a pupil to its desk and at
     * its lesson, the teacher to the lectern. Returns whether it is about it (and so not at its own
     * play or work).
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        if (village == null || f.isShowcase() || f.isHired()) return false;
        boolean child = f.isBaby();
        if (!child && !f.getUUID().equals(teacherId(village))) return false;      // the quick no, for everybody else
        if (!lessonTime(level, village)) return false;
        Ledger.Building b = schoolhouse(village, level.getGameTime());
        if (b == null || !level.isLoaded(b.anchor())) return false;
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        return child ? attend(f, level, v, b) : teach(f, level, v, b);
    }

    /** A pupil's morning: to its desk, and its lesson once the teacher is in. */
    private static boolean attend(VillageFolkEntity f, ServerLevel level, Villages.Village v, Ledger.Building b) {
        UUID village = v.id();
        long now = level.getGameTime(), day = level.getDayTime() / 24000L;
        if (f.isSleeping() || !schoolAge(f, day)) return false;
        if (truant(f, day)) {
            if (!Long.valueOf(day).equals(TRUANT_TOLD.put(f.getUUID(), day))) {
                f.persona().remember(day, "I skipped school to play", 2);
            }
            return false;
        }
        VillageFolkEntity teacher = teacher(level, village, true);
        if (teacher == null) return false;                      // nobody to teach: it plays
        Desk d = desk(village, f);
        if (d == null) d = enrol(village, f, day);
        BlockPos seat = seatFor(level, village, b, f);
        String learning = "learning to be " + a(d.leaning());
        if (!inClass(f, b) && tooLate(level, village)) return false;    // not there by now: the morning is gone
        f.lastLeisureTick = f.tickCount;
        if (!inClass(f, b)) {
            if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(seat, 1.1D);
            f.hobbyNow = "on the way to school";
            return true;
        }
        if (f.blockPosition().distSqr(seat) > 1.0) {
            if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(seat, 0.8D);
        } else {
            f.getNavigation().stop();
        }
        BlockPos front = teacherSpot(b);
        if (inClass(teacher, b)) f.getLookControl().setLookAt(teacher, 30.0F, 30.0F);
        else f.getLookControl().setLookAt(front.getX() + 0.5, front.getY() + 1.5, front.getZ() + 0.5);
        f.hobbyNow = "at school, " + learning;
        boolean taught = teaching(teacher) && inClass(teacher, b);
        RandomSource r = level.getRandom();
        if (!taught) {
            if (r.nextInt(300) == 0) FolkTalk.speak(f, FolkTalk.pick(r, "Where's our teacher?", "Is it a holiday?",
                "Can we go and play, then?"));
            return true;
        }
        Long last = BEAT_AT.get(f.getUUID());
        if (last == null || now - last >= BEAT || now < last) {
            BEAT_AT.put(f.getUUID(), now);
            if (d.lastDay() != day) {
                d = new Desk(d.leaning(), d.why(), d.mornings() + 1, day);
                keep(village, f, d);
                countTaught(village, day);
            }
            learn(f, teacher, d.leaning(), topic(level, village, day));
        }
        if (r.nextInt(500) == 0) FolkTalk.speak(f, FolkTalk.pick(r, "I know! I know!", "Like this?", "Why, though?",
            "Can we go and see?", "I'm going to be " + a(d.leaning()) + "!"));
        return true;
    }

    /** The teacher's morning: to the lectern, the board put up, and the lesson. */
    private static boolean teach(VillageFolkEntity f, ServerLevel level, Villages.Village v, Ledger.Building b) {
        UUID village = v.id();
        long now = level.getGameTime(), day = level.getDayTime() / 24000L;
        if (f.isSleeping() || f.getTarget() != null || !fit(f)) return false;
        if (TownJobs.busy(f)) return false;                     // called to the town's own work: the class waits for it
        if (pupils(level, village).isEmpty()) return false;     // no class this morning: to its own work
        if (!inClass(f, b) && tooLate(level, village)) return false;    // held up elsewhere all morning: its trade, then
        RandomSource r = level.getRandom();
        long[] m = MORNING.get(f.getUUID());
        if (m == null || m[0] != day) {
            m = new long[]{ day, 0, 0 };
            MORNING.put(f.getUUID(), m);
            f.clearQueue();                                     // its own work waits for the afternoon
            f.getNavigation().stop();
            FolkTalk.speak(f, FolkTalk.pick(r, "To the school — the children will be waiting.", "Lessons this morning. Off I go.",
                "School first; the " + f.stationTask().label + " can wait till noon."));
        }
        TEACHING.put(f.getUUID(), now + 60L);
        f.lastLeisureTick = f.tickCount;
        BlockPos spot = teacherSpot(b);
        if (!inClass(f, b)) {
            if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(spot, 0.9D);
            f.hobbyNow = "on the way to the school";
            return true;
        }
        if (f.blockPosition().distSqr(spot) > 1.0) {
            if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(spot, 0.7D);
        } else {
            f.getNavigation().stop();
        }
        // Facing the class, down the aisle to the door.
        BlockPos door = at(b, 0, 1, -4);
        f.getLookControl().setLookAt(door.getX() + 0.5, door.getY() + 0.5, door.getZ() + 0.5);
        StationTask topic = topic(level, village, day);
        f.hobbyNow = "teaching at the school: the " + topic.title.toLowerCase(Locale.ROOT) + "'s trade";
        if (m[1] == 0) {
            m[1] = 1;
            FolkTalk.speak(f, FolkTalk.pick(r, "Good morning, class! Today: the " + topic.title.toLowerCase(Locale.ROOT) + "'s trade.",
                "Settle down, settle down. This morning, " + topic.label + ".", "Slates out, everybody. Today it's " + topic.label + "."));
            for (VillageFolkEntity p : pupils(level, village)) {
                if (inClass(p, b) && r.nextInt(2) == 0) {
                    p.sayLater(FolkTalk.pick(r, "Good morning, " + f.displayNameCap() + "!", "Good morning!", "Morning, teacher!"), 30 + r.nextInt(30));
                    break;
                }
            }
        }
        if (m[2] == 0) {
            // The schoolroom put to rights before the lesson: the board, the book, a shelf (by its own hands, from the stores).
            m[2] = 1;
            int put = fitOut(level, v, b);
            if (put > 0) f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
        if (r.nextInt(220) == 0) {
            int seated = 0;
            for (VillageFolkEntity p : pupils(level, village)) if (inClass(p, b)) seated++;
            if (seated > 0) {
                FolkTalk.speak(f, lessonLine(topic, r));
                f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                for (VillageFolkEntity p : pupils(level, village)) {
                    if (inClass(p, b) && r.nextInt(3) == 0) {
                        p.sayLater(FolkTalk.pick(r, "Yes, teacher!", "I knew that!", "Why?", "Oh! Like my mother does!",
                            "Can we try it after?"), 50 + r.nextInt(40));
                        break;
                    }
                }
            }
        }
        return true;
    }

    /** A morning counted as taught, once a day. */
    private static void countTaught(UUID village, long day) {
        Long last = TAUGHT_DAY.put(village, day);
        if (last != null && last == day) return;
        String n = Ledger.note(village, TAUGHT + "_day");
        if (n != null && n.equals(Long.toString(day))) return;
        Ledger.note(village, TAUGHT + "_day", Long.toString(day));
        Ledger.note(village, TAUGHT, Integer.toString(taught(village) + 1));
    }

    /** Mornings the school has taught, all told. */
    static int taught(UUID village) {
        try {
            String n = Ledger.note(village, TAUGHT);
            return n == null || n.isEmpty() ? 0 : Integer.parseInt(n.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The day's lesson: one of the pupils' trades, a different one each day. */
    static StationTask topic(ServerLevel level, UUID village, long day) {
        long[] t = TOPIC.get(village);
        if (t != null && t[0] == day) return StationTask.byOrdinal((int) t[1]);
        List<StationTask> leanings = new ArrayList<>();
        for (VillageFolkEntity p : pupils(level, village)) {
            Desk d = desk(village, p);
            if (d != null && !leanings.contains(d.leaning())) leanings.add(d.leaning());
        }
        leanings.sort(Comparator.comparingInt(Enum::ordinal));
        if (leanings.isEmpty()) {
            // Nobody at a desk yet: the teacher's own trade, till the class is in (not kept for the day).
            VillageFolkEntity teacher = teacherNow(village);
            return teacher != null ? teacher.stationTask() : StationTask.FARM;
        }
        StationTask pick = leanings.get((int) Math.floorMod(day, (long) leanings.size()));
        TOPIC.put(village, new long[]{ day, pick.ordinal() });
        return pick;
    }

    /**
     * A beat of the lesson: a little of the trade it leans to (up to a schooling's worth under this
     * teacher), and a little of the day's lesson if that is another trade (up to a level or two).
     */
    static void learn(VillageFolkEntity f, VillageFolkEntity teacher, StationTask leaning, StationTask topic) {
        int cap = AssistantEntity.xpForLevel(capLevel(teacher));
        int rate = rate(teacher, f, cap);
        int have = f.xpInTrade(leaning);
        if (have < cap) f.schoolXp(leaning, Math.min(cap - have, rate));
        if (topic != StationTask.NONE && topic != leaning) {
            int general = AssistantEntity.xpForLevel(GENERAL_CAP);
            int g = f.xpInTrade(topic);
            if (g < general) f.schoolXp(topic, Math.min(general - g, Math.max(1, rate / 6)));
        }
    }

    /** What a beat teaches: a sixtieth of a schooling, more under a curious or patient teacher and for a
     *  hardworking or curious pupil, less under a grumpy one and for an easygoing one. */
    static int rate(VillageFolkEntity teacher, VillageFolkEntity pupil, int cap) {
        int pct = 100;
        Social.Life t = teacher.life(), p = pupil.life();
        if (t.has(Social.Trait.CURIOUS)) pct += 10;
        if (t.has(Social.Trait.EASYGOING)) pct += 10;
        if (t.has(Social.Trait.GRUMPY)) pct -= 25;
        if (p.has(Social.Trait.HARDWORKING)) pct += 15;
        if (p.has(Social.Trait.CURIOUS)) pct += 10;
        if (p.has(Social.Trait.EASYGOING)) pct -= 15;
        return Math.max(1, (cap * pct + BEATS_TO_CAP * 100 - 1) / (BEATS_TO_CAP * 100));     // rounded up: sixty beats fill it
    }

    // ------------------------------------------------------------------ leaving school

    /**
     * Grown up (VillageFolkEntity.childhood): the trade it leaned to at school, if it went and the
     * village has a place for it there; NONE otherwise (the apprenticeship or the leader decides, and
     * what it learned waits for it in that trade). Its desk is cleared, and who has left school noted.
     */
    public static StationTask graduate(VillageFolkEntity f, long day) {
        UUID village = f.ownerId();
        if (village == null) return StationTask.NONE;
        Desk d = desk(village, f);
        if (d == null) return StationTask.NONE;
        Ledger.forget(village, PUPIL + f.getUUID());
        BEAT_AT.remove(f.getUUID());
        StationTask t = d.leaning();
        if (t == StationTask.NONE || d.mornings() <= 0 || f.xpInTrade(t) <= 0) return StationTask.NONE;
        int lv = AssistantEntity.levelFor(f.xpInTrade(t));
        boolean place = !Villages.overStaffed(village, t);
        String teacher = Ledger.note(village, TEACHER_NAME);
        addGraduate(village, f.displayNameCap() + "\t" + t.title + "\t" + lv + "\t" + day + "\t" + d.mornings() + "\t" + (place ? "" : "no place"));
        f.persona().remember(day, place ? "I left school " + a(t) + ", level " + lv
            : "I left school wanting to be " + a(t) + " (level " + lv + "), but there was no place for one", 7);
        VillageFolkEntity by = teacherNow(village);
        if (by != null) {
            by.persona().remember(day, "I saw " + f.displayNameCap() + " through school", 4);
            by.life().feel(f.getUUID(), f.displayNameCap(), 8);
            f.life().feel(by.getUUID(), by.displayNameCap(), 8);
        }
        if (!place) {
            Villages.tell(village, day, f.displayNameCap() + " left school " + a(t) + " of level " + lv
                + (teacher == null || teacher.isEmpty() ? "" : ", taught by " + teacher) + ", but the village has hands enough at it");
        }
        return place ? t : StationTask.NONE;
    }

    private static void addGraduate(UUID village, String line) {
        String n = Ledger.note(village, GRADUATES);
        List<String> all = new ArrayList<>();
        if (n != null && !n.isEmpty()) all.addAll(List.of(n.split("\n")));
        all.add(line.replace('\n', ' '));
        while (all.size() > 12) all.remove(0);
        Ledger.note(village, GRADUATES, String.join("\n", all));
    }

    private static List<String[]> graduates(UUID village) {
        String n = Ledger.note(village, GRADUATES);
        List<String[]> out = new ArrayList<>();
        if (n == null || n.isEmpty()) return out;
        for (String l : n.split("\n")) {
            String[] p = l.split("\t", -1);
            if (p.length >= 4) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ the schoolroom put to rights

    /** What a blackboard is made of, best first: black wool, black terracotta or concrete, then slate. */
    private static final Item[] SLATE = { Items.BLACK_WOOL, Items.BLACK_TERRACOTTA, Items.BLACK_CONCRETE, Items.COAL_BLOCK,
        Items.POLISHED_BLACKSTONE, Items.BLACKSTONE, Items.POLISHED_DEEPSLATE, Items.DEEPSLATE_TILES, Items.COBBLED_DEEPSLATE,
        Items.GRAY_WOOL };

    /** Is this block (one of) a blackboard? */
    static boolean slate(BlockState s) {
        for (Item i : SLATE) if (s.is(Block.byItem(i))) return true;
        return false;
    }

    /**
     * The schoolroom put to rights out of the stores, by the teacher before its lesson: the blackboard
     * (six blocks of black wool or slate; what the stores have the most of, the best first), the lectern
     * if the builders had no books for one (one put by, or eight planks and three books), a book on
     * it (a written one or a book and quill if the stores have one, else a plain book), and a bookshelf
     * where one is missing (one put by, or six planks and three books), one a morning. Nothing out of
     * nothing: what the stores cannot pay for waits. Returns the pieces put in.
     */
    static int fitOut(ServerLevel level, Villages.Village v, Ledger.Building b) {
        if (!Land.areaLoaded(level, b.anchor(), 7)) return 0;
        int done = 0;
        // The blackboard.
        List<BlockPos> bare = new ArrayList<>();
        for (BlockPos p : board(b)) if (level.getBlockState(p).isAir()) bare.add(p);
        if (!bare.isEmpty()) {
            Item best = null;
            for (Item i : SLATE) {
                int n = Crafts.stock(level, v, s -> s.is(i));
                if (n >= bare.size()) { best = i; break; }
                if (n > 0 && best == null) best = i;
            }
            for (BlockPos p : bare) {
                final Item first = best;
                Item use = first != null && Crafts.take(level, v, s -> s.is(first), 1) ? first : null;
                if (use == null) {
                    for (Item i : SLATE) if (Crafts.take(level, v, s -> s.is(i), 1)) { use = i; break; }
                }
                if (use == null) break;
                level.setBlock(p, Block.byItem(use).defaultBlockState(), 3);
                done++;
            }
        }
        // The lectern.
        BlockPos lec = lectern(b);
        if (level.getBlockState(lec).isAir()) {
            boolean paid = Crafts.take(level, v, s -> s.is(Items.LECTERN), 1);
            if (!paid && Crafts.stock(level, v, s -> s.is(Items.BOOK)) >= 3 && Crafts.take(level, v, s -> s.is(Items.BOOK), 3)) {
                paid = Crafts.usePlanks(level, v, 8);
                if (!paid) Crafts.store(level, v, new ItemStack(Items.BOOK, 3));
            }
            if (paid) {
                level.setBlock(lec, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, b.facing().getOpposite()), 3);
                done++;
            }
        }
        // A book on it.
        BlockState ls = level.getBlockState(lec);
        if (ls.getBlock() instanceof LecternBlock && !ls.getValue(LecternBlock.HAS_BOOK)) {
            ItemStack book = Crafts.takeOne(level, v, s -> s.is(Items.WRITTEN_BOOK) || s.is(Items.WRITABLE_BOOK));
            if (book.isEmpty()) book = Crafts.takeOne(level, v, s -> s.is(Items.BOOK));
            if (!book.isEmpty()) {
                if (LecternBlock.tryPlaceBook(null, level, lec, ls, book)) done++;
                else Crafts.store(level, v, book);
            }
        }
        // A bookshelf where the drawing has one and there is none.
        for (BuildGoal.Placement p : BuildGoal.plan("school", b.anchor(), b.facing(), 13)) {
            if (p.part() != BuildGoal.Part.BOOKSHELF || !level.getBlockState(p.pos()).isAir()) continue;
            boolean paid = Crafts.take(level, v, s -> s.is(Items.BOOKSHELF), 1);
            if (!paid && Crafts.stock(level, v, s -> s.is(Items.BOOK)) >= 3 && Crafts.take(level, v, s -> s.is(Items.BOOK), 3)) {
                paid = Crafts.usePlanks(level, v, 6);
                if (!paid) Crafts.store(level, v, new ItemStack(Items.BOOK, 3));
            }
            if (paid) {
                level.setBlock(p.pos(), Blocks.BOOKSHELF.defaultBlockState(), 3);
                done++;
            }
            break;                                              // one a morning
        }
        return done;
    }

    // ------------------------------------------------------------------ the lessons

    private static final Map<StationTask, String[]> LINES = new java.util.EnumMap<>(StationTask.class);
    static {
        LINES.put(StationTask.MINE, new String[]{ "A good miner listens to the stone: a hollow knock means a cave behind it.",
            "Never dig straight down. You don't know what's under your boots.", "Iron lies lower than coal, and diamond lower than both.",
            "Light every turning, and you'll always find your way out." });
        LINES.put(StationTask.FARM, new String[]{ "Wheat wants water within four blocks: dig a channel, not a pond.",
            "Never trample the young wheat. Walk the rows, not the crop.", "Keep back seed for next year: a farmer plants before it eats.",
            "Carrots and potatoes go back into the ground they came out of." });
        LINES.put(StationTask.WOOD, new String[]{ "Fell a tree, plant a sapling. The forest is the village's purse.",
            "Take the whole trunk: a log left floating is a lazy woodcutter's mark.", "Oak for the walls, spruce for the roof, birch when there's nothing else." });
        LINES.put(StationTask.RANCH, new String[]{ "Two of a kind and a handful of wheat: that's how a herd begins.",
            "Shear the sheep, don't eat them. Wool grows back.", "Never take the last two of anything." });
        LINES.put(StationTask.SMELT, new String[]{ "Fuel below, ore above, and patience in between.", "A good smelter never lets a furnace go cold." });
        LINES.put(StationTask.FISH, new String[]{ "Fish bite in their own time. A fisher's work is being there when they do.",
            "Rain brings the fish up. Don't go home because it's raining." });
        LINES.put(StationTask.HAUL, new String[]{ "Carry the heavy things first, while your legs are fresh.", "Every chest back where it belongs, every time." });
        LINES.put(StationTask.SMITH, new String[]{ "Strike while the iron is hot, and not a moment after.", "A good blade is never hurried." });
        LINES.put(StationTask.TAILOR, new String[]{ "Measure twice, cut once.", "Wool from the sheep, leather from the hunt: a tailor wastes nothing." });
        LINES.put(StationTask.BEEKEEP, new String[]{ "Move slowly round the hives, and smoke them before you take the honey.",
            "Flowers by the hives make the sweetest honey." });
        LINES.put(StationTask.BREW, new String[]{ "Water first, then the wart, then the rest: the order is everything.",
            "A good brewer knows every bottle on the shelf." });
        LINES.put(StationTask.ENCHANT, new String[]{ "Books, lapis and patience: the letters only come to those who read.",
            "Shelves round the table, with a gap between: never touching it." });
        LINES.put(StationTask.COOK, new String[]{ "Clean hands, a hot oven, and bread by dawn.", "Taste the stew before you serve it." });
        LINES.put(StationTask.SHOP, new String[]{ "A fair price brings a customer back.", "Count the coin in front of them, every time." });
        LINES.put(StationTask.HUNT, new String[]{ "Walk into the wind and the game never smells you coming.",
            "Take the many and leave the young: there'll be game next year." });
        LINES.put(StationTask.STORE, new String[]{ "A place for everything, and everything in its place." });
        LINES.put(StationTask.SCOUT, new String[]{ "Mark every hill on the map, and always know the way home." });
        LINES.put(StationTask.GUARD, new String[]{ "Keep your back to the wall and your eyes on the dark." });
        LINES.put(StationTask.CAVE, new String[]{ "A torch every few steps: it's the way home.",           // [caves]
            "Never dig the block you stand on, and never dig toward water or lava." });
        LINES.put(StationTask.FLETCHER, new String[]{ "A flint, a stick and a feather make four arrows: count them twice.",   // [fletcher]
            "Gravel gives a flint one time in ten. Patience is half the trade." });
        LINES.put(StationTask.GOLEMS, new String[]{ "Four blocks of iron in a T, and the pumpkin last: never the other way round.",   // [golems]
            "An iron golem never turns on its own town. Be kind to it all the same." });
        LINES.put(StationTask.FIREWORKS, new String[]{ "One gunpowder to a star, one to three to a rocket: no more.",   // [fireworks]
            "Never a flame in the powder hut, and never a rocket in a thunderstorm." });
    }

    private static final String[] ANY_DAY = { "Reading, writing and counting: every trade stands on those three.",
        "Two and two make four, whether it's coins or cows.", "Ask your elders: they've made every mistake already.",
        "Copy it onto your slate. Neatly, now." };

    /** A line of the day's lesson. */
    static String lessonLine(StationTask topic, RandomSource r) {
        String[] lines = LINES.get(topic);
        if (lines == null || r.nextInt(4) == 0) return ANY_DAY[r.nextInt(ANY_DAY.length)];
        return lines[r.nextInt(lines.length)];
    }

    // ------------------------------------------------------------------ what folk see of it

    /** "Teacher · " for the teacher's card header (FolkTalk), else nothing. */
    public static String title(VillageFolkEntity f) {
        return isTeacher(f) ? "Teacher · " : "";
    }

    /** What it is doing at school this minute (FolkTalk.nowDoing), or null. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level) || f.ownerId() == null) return null;
        if (!f.isBaby() && teaching(f)) return "Teaching at the school";
        if (f.isBaby() && f.hobbyNow != null && (f.hobbyNow.startsWith("at school") || f.hobbyNow.equals("on the way to school"))
                && f.tickCount - f.lastLeisureTick < 40 && lessonTime(level, f.ownerId())) {
            return Character.toUpperCase(f.hobbyNow.charAt(0)) + f.hobbyNow.substring(1);
        }
        return null;
    }

    /** Its schooling so far, for a child's card: "at school: learning to be a miner, level 3 (2 mornings; ...)". */
    public static String cardLine(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return "";
        if (!f.isBaby()) {
            if (!isTeacher(f)) return "";
            int pupils = 0;
            long day = f.level().getDayTime() / 24000L;
            for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity c && schoolAge(c, day)) pupils++;
            return "the village school: " + pupils + (pupils == 1 ? " pupil" : " pupils") + ", " + taught(village)
                + (taught(village) == 1 ? " morning" : " mornings") + " taught; a schooling under it is worth up to level "
                + capLevel(f) + "; paid " + pay(f) + " a day for it";
        }
        long day = f.level().getDayTime() / 24000L;
        Desk d = desk(village, f);
        boolean school = stands(village);
        if (d == null) {
            if (!school) return "no school in the village yet: it will start its trade at level 0";
            return f.bornDay() != VillageFolkEntity.UNKNOWN && day - f.bornDay() < 1 ? "too young for school: it starts tomorrow"
                : "not been to school yet";
        }
        int lv = AssistantEntity.levelFor(f.xpInTrade(d.leaning()));
        String s = "at school: learning to be " + a(d.leaning()) + ", level " + lv
            + " (" + d.mornings() + (d.mornings() == 1 ? " morning" : " mornings") + "; " + d.why() + ")";
        if (schoolAge(f, day) && truant(f, day)) s += "; playing truant this morning";
        return s;
    }

    /** What a child says about itself (FolkTalk.knack), or null. */
    @Nullable
    public static String childSays(VillageFolkEntity f) {
        UUID village = f.ownerId();
        Desk d = village == null ? null : desk(village, f);
        if (d == null) return null;
        int lv = AssistantEntity.levelFor(f.xpInTrade(d.leaning()));
        return "I'm learning to be " + a(d.leaning()) + " at school"
            + (lv > 0 ? " — level " + lv + " already!" : "!");
    }

    /** What a councillor makes of a school (Council): parents of the little ones most. */
    static int councilScore(VillageFolkEntity m) {
        int s = 0;
        UUID village = m.ownerId();
        if (village != null) {
            for (AssistantEntity a : Villages.folkOf(village)) {
                if (a instanceof VillageFolkEntity c && c.isBaby() && c.parentIds().contains(m.getUUID())) { s += 3; break; }
            }
        }
        if (m.life().has(Social.Trait.CURIOUS)) s += 2;
        if (m.persona().rolled() && m.persona().hobby() == Persona.Hobby.READING) s += 1;
        return s;
    }

    /** The Teacher's row on the Jobs page (Annals.jobs), or null when there is none. */
    @Nullable
    public static CompoundTag jobsRow(UUID village) {
        VillageFolkEntity t = teacherNow(village);
        if (t == null || !stands(village)) return null;
        CompoundTag c = new CompoundTag();
        c.putString("id", "TEACH");
        c.putString("title", "Teacher");
        c.putInt("ordinal", 98);
        c.putInt("hands", 1);
        c.putInt("level", bestLevel(t));
        int pay = pay(t);
        c.putInt("wage", pay);
        c.putInt("purses", t.purse());
        c.putInt("yesterday", 0);
        c.putInt("week", 0);
        c.putInt("per_head", 0);
        c.putInt("share", 0);
        c.putInt("wage_bill", pay);
        return c;
    }

    /** The School page of the town's books (CityScreen): the schoolhouse, the teacher, the morning, every pupil, who has left. */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        CompoundTag out = new CompoundTag();
        Ledger.Building b = schoolhouse(id, -1L);
        int kids = children(id), folk = Villages.headcount(id);
        out.putInt("children", kids);
        out.putInt("folk", folk);
        out.putBoolean("stands", b != null);
        if (b != null) {
            out.putString("where", b.anchor().getX() + ", " + b.anchor().getZ());
            out.putInt("desks", seats(b).size());
            int board = 0;
            for (BlockPos p : board(b)) if (level.isLoaded(p) && slate(level.getBlockState(p))) board++;
            out.putInt("board", board);
            BlockState ls = level.isLoaded(lectern(b)) ? level.getBlockState(lectern(b)) : Blocks.AIR.defaultBlockState();
            out.putBoolean("lectern", ls.getBlock() instanceof LecternBlock);
            out.putBoolean("book", ls.getBlock() instanceof LecternBlock && ls.getValue(LecternBlock.HAS_BOOK));
        } else {
            out.putBoolean("wanted", Villages.projectsWanted(id).contains("school"));
        }
        VillageFolkEntity t = teacherNow(id);
        String tname = Ledger.note(id, TEACHER_NAME);
        if (t != null) {
            out.putString("teacher", t.displayNameCap());
            out.putString("teacher_trade", t.stationTask().title + ", level " + t.veteranLevel() + ", " + t.ageYears() + " years old");
            out.putString("teacher_why", whyTeacher(t));
            out.putInt("teacher_pay", pay(t));
            out.putInt("cap", capLevel(t));
            out.putBoolean("teaching", teaching(t));
        } else if (tname != null && !tname.isEmpty()) {
            out.putString("teacher", tname + " (away)");
        }
        String since = Ledger.note(id, TEACHER_SINCE);
        if (since != null) out.putString("since", since);
        out.putInt("taught", taught(id));
        // The morning.
        long time = level.getDayTime() % 24000L;
        String today;
        if (b == null) today = "No schoolhouse yet: the children play, and start their trades at level 0.";
        else if (RestDay.today(id, day)) today = "The day of rest: no lessons today.";
        else if (lessonTime(level, id)) today = "Lessons now: the " + topic(level, id, day).title.toLowerCase(Locale.ROOT) + "'s trade.";
        else if (time < LESSON_FROM) today = "Lessons this morning, from half past seven.";
        else today = "Lessons are over for the day; the teacher is back at its trade.";
        out.putString("today", today);
        // Every child.
        ListTag pupils = new ListTag();
        List<VillageFolkEntity> all = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity c && c.isBaby() && !c.isShowcase()) all.add(c);
        all.sort(Comparator.comparingLong(VillageFolkEntity::bornDay).thenComparing(VillageFolkEntity::getUUID));
        Ledger.Building house = b;
        for (VillageFolkEntity c : all) {
            if (pupils.size() >= 60) break;
            CompoundTag p = new CompoundTag();
            p.putString("name", c.displayNameCap());
            p.putInt("years", c.ageYears());
            Desk d = desk(id, c);
            if (d != null) {
                int xp = c.xpInTrade(d.leaning());
                int lv = AssistantEntity.levelFor(xp);
                p.putString("leaning", d.leaning().title);
                p.putInt("ordinal", d.leaning().ordinal());
                p.putString("why", d.why());
                p.putInt("level", lv);
                int cap = capLevel(t);
                p.putInt("cap", cap);
                p.putInt("xp", xp);
                p.putInt("cap_xp", AssistantEntity.xpForLevel(cap));
                p.putInt("mornings", d.mornings());
            }
            String now;
            if (!schoolAge(c, day)) now = c.bornDay() != VillageFolkEntity.UNKNOWN && day - c.bornDay() < 1 ? "too young" : "about grown";
            else if (house == null) now = "no school";
            else if (truant(c, day) && !RestDay.today(id, day)) now = "truant today";
            else if (lessonTime(level, id)) now = inClass(c, house) ? "at its desk" : "on the way";
            else now = "home";
            p.putString("now", now);
            pupils.add(p);
        }
        out.put("pupils", pupils);
        ListTag left = new ListTag();
        List<String[]> grads = graduates(id);
        for (int i = grads.size() - 1; i >= 0; i--) {
            String[] g = grads.get(i);
            left.add(StringTag.valueOf(g[0] + "|" + g[1] + "|" + g[2] + "|" + g[3] + "|" + (g.length > 5 ? g[5] : "")));
        }
        out.put("graduates", left);
        return out;
    }

    /** The school, in lines for the chat (/village school). */
    public static List<String> lines(ServerLevel level, Villages.Village v) {
        CompoundTag r = report(level, v);
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        out.add(Villages.name(id) + " — the school");
        if (r.getBoolean("stands")) {
            out.add("The schoolhouse stands at " + r.getString("where") + ": " + r.getInt("desks") + " desks, blackboard "
                + r.getInt("board") + "/6" + (r.getBoolean("book") ? ", a book on the lectern" : r.getBoolean("lectern") ? ", the lectern bare" : ", no lectern yet") + ".");
        } else {
            out.add("No schoolhouse yet: " + (r.getBoolean("wanted") ? "it is on the list of what the village will build."
                : "wanted once there are " + CHILDREN + " children and " + FOLK + " folk (now " + r.getInt("children")
                    + " and " + r.getInt("folk") + "), from the Stone Age."));
        }
        if (r.contains("teacher")) {
            out.add("Teacher: " + r.getString("teacher") + (r.contains("teacher_trade") ? " (" + r.getString("teacher_trade") + "; "
                + r.getString("teacher_why") + "), paid " + r.getInt("teacher_pay") + " a day; a schooling under it is worth up to level "
                + r.getInt("cap") : "") + ". " + r.getInt("taught") + " mornings taught.");
        }
        out.add(r.getString("today"));
        ListTag pupils = r.getList("pupils", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < pupils.size(); i++) {
            CompoundTag p = pupils.getCompound(i);
            out.add("  " + p.getString("name") + " (" + p.getInt("years") + "): " + (p.contains("leaning")
                ? p.getString("leaning").toLowerCase(Locale.ROOT) + " " + p.getInt("level") + " of " + p.getInt("cap") + ", "
                    + p.getInt("mornings") + " mornings — " + p.getString("why") : "no desk yet") + " [" + p.getString("now") + "]");
        }
        ListTag left = r.getList("graduates", net.minecraft.nbt.Tag.TAG_STRING);
        for (int i = 0; i < Math.min(6, left.size()); i++) {
            String[] g = left.getString(i).split("\\|", -1);
            if (g.length < 4) continue;
            out.add("  Left school on day " + g[3] + ": " + g[0] + ", " + g[1].toLowerCase(Locale.ROOT) + " level " + g[2]
                + (g.length > 4 && !g[4].isEmpty() ? " (no place for one)" : ""));
        }
        return out;
    }

    // ------------------------------------------------------------------ the pictures

    /**
     * A schoolhouse set out at {@code at}, door to the south, in the middle of a lesson: the blackboard up
     * and a book on the lectern, the teacher at it and six children at their desks (folk to be looked at,
     * as the lineup's: /kill @e[tag=folk_lineup] clears them). For the pictures; from a palette, not the
     * stores. It stands on the land itself (only {@code at}'s x and z are taken): the lot levelled to the
     * ground's own height there, built up with earth where it is low and cut down where it is high, the
     * trees on it cleared, and its edges sloped back into the land round about; only where there is no
     * land at all, on a stage at {@code at}'s height. Returns "SCHOOL x y z" and the views, "VIEW name x y
     * z ax ay az": from the street, and from the back of the schoolroom down the aisle (nothing hangs
     * there) to the teacher at the lectern before the blackboard, over the children's heads.
     */
    public static List<String> stage(ServerLevel level, BlockPos at) {
        int x = at.getX(), z = at.getZ();
        int x0 = x - 8, x1 = x + 8, z0 = z - 9, z1 = z + 16;
        int y = lotHeight(level, x0, x1, z0, z1);
        if (y == Integer.MIN_VALUE) {
            y = at.getY();
            com.jrpetty.mcassistant.Showcase.stage(level, x - 9, x + 9, z - 10, z + 18, y);
        } else {
            levelLot(level, x0, x1, z0, z1, y, 6);
        }
        BlockPos anchor = new BlockPos(x, y, z);
        BuildGoal.stamp(level, "school", anchor, Direction.NORTH, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.Building b = new Ledger.Building("school", anchor, Direction.NORTH);
        for (BlockPos p : board(b)) level.setBlock(p, Blocks.BLACK_WOOL.defaultBlockState(), 3);
        BlockPos lec = lectern(b);
        BlockState ls = level.getBlockState(lec);
        if (ls.getBlock() instanceof LecternBlock && !ls.getValue(LecternBlock.HAS_BOOK)) {
            LecternBlock.tryPlaceBook(null, level, lec, ls, new ItemStack(Items.BOOK));
        }
        RandomSource r = level.getRandom();
        VillageFolkEntity teacher = standIn(level, teacherSpot(b), 0.0F, StationTask.MINE, false, "Teacher");
        if (teacher != null) teacher.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.BOOK));
        List<BlockPos> seats = seats(b);
        String[] names = { "Ada", "Tom", "Wren", "Pip", "Nell", "Kit" };
        for (int i = 0; i < Math.min(names.length, seats.size()); i++) standIn(level, seats.get(i), 180.0F, StationTask.NONE, true, names[i]);
        if (teacher != null) FolkTalk.speak(teacher, lessonLine(StationTask.MINE, r));
        BlockPos spot = teacherSpot(b);
        List<String> out = new ArrayList<>();
        out.add("SCHOOL " + x + " " + y + " " + z);
        out.add("VIEW school-front " + (x + 7) + " " + (y + 5) + " " + (z + 15) + " " + x + " " + (y + 3) + " " + z);
        // From the back of the aisle by the door, eyes a little above a grown folk's: over the children at their
        // desks to the teacher at the lectern, the blackboard behind it.
        out.add("VIEW school-lesson " + x + " " + (y + 1) + " " + (z + 3) + " " + spot.getX() + " " + (y + 1) + " " + spot.getZ());
        return out;
    }

    /** Is this where the land is (not a tree, a plant, snow or water standing on it)? */
    private static boolean land(BlockState s) {
        return !s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced()
            && !s.is(net.minecraft.tags.BlockTags.LOGS) && !s.is(net.minecraft.tags.BlockTags.LEAVES);
    }

    /** The first free height above the land in this column (under any tree, plant or water); MIN_VALUE for none. */
    private static int ground(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int bottom = level.getMinBuildHeight();
        while (y > bottom && !land(level.getBlockState(new BlockPos(x, y, z)))) y--;
        return y <= bottom ? Integer.MIN_VALUE : y + 1;
    }

    /** The height to level a lot to: the middle of the land's own heights over it (never under the sea's
     *  surface); MIN_VALUE where there is no land under it at all. */
    private static int lotHeight(ServerLevel level, int x0, int x1, int z0, int z1) {
        List<Integer> heights = new ArrayList<>();
        for (int x = x0; x <= x1; x += 2) {
            for (int z = z0; z <= z1; z += 2) {
                int g = ground(level, x, z);
                if (g != Integer.MIN_VALUE) heights.add(g);
            }
        }
        if (heights.isEmpty()) return Integer.MIN_VALUE;
        heights.sort(Integer::compare);
        return Math.max(heights.get(heights.size() / 2), level.getSeaLevel());
    }

    /**
     * A lot made level at this height: earth (grass on top) where the land is lower, cut down where it is
     * higher, everything standing on it cleared; and round it, so far out, the ground sloped from the lot's
     * height back to the land's own, with any tree standing there taken down.
     */
    private static void levelLot(ServerLevel level, int x0, int x1, int z0, int z1, int y, int margin) {
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(),
            air = Blocks.AIR.defaultBlockState();
        for (int cx = x0 - margin; cx <= x1 + margin; cx++) {
            for (int cz = z0 - margin; cz <= z1 + margin; cz++) {
                int ox = cx < x0 ? x0 - cx : cx > x1 ? cx - x1 : 0;
                int oz = cz < z0 ? z0 - cz : cz > z1 ? cz - z1 : 0;
                int d = Math.max(ox, oz);
                int natural = ground(level, cx, cz);
                if (natural == Integer.MIN_VALUE) continue;
                int target = d == 0 ? y : (int) Math.round(y + (natural - y) * (d / (double) (margin + 1)));
                // Built up: earth from the land (or the water's bed) to the level, grass on top; at most twenty deep.
                for (int h = Math.max(natural, target - 20); h < target; h++) {
                    level.setBlock(new BlockPos(cx, h, cz), h == target - 1 ? grass : dirt, 2 | 16);
                }
                // Cut down: the land above the level taken off, the new top grassed.
                if (natural > target) level.setBlock(new BlockPos(cx, target - 1, cz), grass, 2 | 16);
                // Cleared above: on the lot, everything (trees, rock, water) for the building's height; round it,
                // the cut land and whatever stands on the ground there, but not the water of a shore.
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, cx, cz);
                int to = d == 0 ? Math.max(top, target + 16) : top;
                for (int h = target; h < to; h++) {
                    BlockPos p = new BlockPos(cx, h, cz);
                    BlockState st = level.getBlockState(p);
                    if (st.isAir() || d > 0 && !st.getFluidState().isEmpty()) continue;
                    level.setBlock(p, air, 2 | 16);
                }
            }
        }
    }

    @Nullable
    private static VillageFolkEntity standIn(ServerLevel level, BlockPos pos, float yaw, StationTask trade, boolean child, @Nullable String name) {
        VillageFolkEntity f = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (f == null) return null;
        double up = level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.StairBlock ? 0.5 : 0.0;
        f.moveTo(pos.getX() + 0.5, pos.getY() + up, pos.getZ() + 0.5, yaw, 0.0F);
        f.setYHeadRot(yaw);
        f.setYBodyRot(yaw);
        f.makeShowcase(trade);
        if (child) f.setChild(true);
        if (name != null) f.rename(name);
        f.addTag("folk_lineup");
        return level.addFreshEntity(f) ? f : null;
    }

    /** The nearest school's teacher (or, on the stage, the nearest grown folk) says a line of the lesson. */
    public static String sayNow(ServerLevel level, BlockPos near) {
        List<VillageFolkEntity> folk = level.getEntitiesOfClass(VillageFolkEntity.class, new net.minecraft.world.phys.AABB(near).inflate(32.0),
            f -> f.isAlive() && !f.isBaby());
        folk.sort(Comparator.comparingDouble(f -> f.distanceToSqr(near.getX() + 0.5, near.getY(), near.getZ() + 0.5)));
        VillageFolkEntity who = null;
        for (VillageFolkEntity f : folk) if (isTeacher(f)) { who = f; break; }
        if (who == null && !folk.isEmpty()) who = folk.get(0);
        if (who == null) return "Nobody here to teach.";
        UUID village = who.ownerId();
        StationTask topic = village != null ? topic(level, village, level.getDayTime() / 24000L) : StationTask.MINE;
        String line = lessonLine(topic, level.getRandom());
        FolkTalk.speak(who, line);
        return who.displayNameCap() + ": " + line;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: so many beats of lessons for this child under this teacher, as at its desk (a morning counted). */
    public static void lessonForTests(VillageFolkEntity child, VillageFolkEntity teacher, int beats) {
        UUID village = child.ownerId();
        if (village == null) return;
        long day = child.level().getDayTime() / 24000L;
        Desk d = desk(village, child);
        if (d == null) d = enrol(village, child, day);
        d = new Desk(d.leaning(), d.why(), d.mornings() + 1, day);
        keep(village, child, d);
        StationTask topic = d.leaning();
        for (int i = 0; i < beats; i++) learn(child, teacher, d.leaning(), topic);
    }

    /** Tests: the trade this child leans to (StationTask.NONE if it has no desk yet). */
    public static StationTask leaningOf(VillageFolkEntity child) {
        UUID village = child.ownerId();
        Desk d = village == null ? null : desk(village, child);
        return d == null ? StationTask.NONE : d.leaning();
    }

    /** Tests: the mornings this child has been to school. */
    public static int morningsOf(VillageFolkEntity child) {
        UUID village = child.ownerId();
        Desk d = village == null ? null : desk(village, child);
        return d == null ? 0 : d.mornings();
    }

    /** Tests: this child's desk, leaning to this trade. */
    public static void leanForTests(VillageFolkEntity child, StationTask t) {
        UUID village = child.ownerId();
        if (village == null) return;
        Desk d = desk(village, child);
        keep(village, child, new Desk(t, "a test", d == null ? 0 : d.mornings(), d == null ? -1L : d.lastDay()));
    }

    /** Tests: does it play truant this morning? */
    public static boolean truantForTests(VillageFolkEntity f) {
        return truant(f, f.level().getDayTime() / 24000L);
    }

    /** Tests: the schoolroom put to rights now, out of the stores. Returns the pieces put in. */
    public static int fitOutForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        Ledger.Building b = schoolhouse(village, -1L);
        return v == null || b == null ? 0 : fitOut(level, v, b);
    }

    /** Tests: where the blackboard, the lectern, the benches and the teacher's place are in this village's school. */
    @Nullable
    public static List<BlockPos> boardForTests(UUID village) {
        Ledger.Building b = schoolhouse(village, -1L);
        return b == null ? null : board(b);
    }

    @Nullable
    public static BlockPos lecternForTests(UUID village) {
        Ledger.Building b = schoolhouse(village, -1L);
        return b == null ? null : lectern(b);
    }

    @Nullable
    public static List<BlockPos> seatsForTests(UUID village) {
        Ledger.Building b = schoolhouse(village, -1L);
        return b == null ? null : seats(b);
    }

    /** Tests: is it in the schoolroom of its village's school? */
    public static boolean inClassForTests(VillageFolkEntity f) {
        UUID village = f.ownerId();
        Ledger.Building b = village == null ? null : schoolhouse(village, -1L);
        return b != null && inClass(f, b);
    }
}
