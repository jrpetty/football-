package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featfolk] The school day round the lessons (School has the schoolhouse, the teacher and the lessons themselves):
 * <ul>
 * <li><b>In class</b>, the teacher points at the board and the class writes on its slates; now and then a hand goes up
 *     ({@link #teacherBeat}, {@link #pupilBeat}).</li>
 * <li><b>Break</b>: after the lesson the class goes out into the yard in front of the schoolhouse and plays a while
 *     ({@link #recess}), then on to the afternoon (its apprenticeship, the park) and home for supper.</li>
 * <li><b>No school yet, or no teacher</b>: a child of school age is taught at home of a morning, by a retired folk of the
 *     town (the old have the time), else a parent off work, else the elder; slower than at school, but taught
 *     ({@link #homeLesson}). With no teacher the town posts the vacancy (School.teacher, Interviews), as it always did.</li>
 * <li><b>Weekends</b>: no school on the town's day of rest ({@link #noSchool}: the calendar's hook).</li>
 * <li><b>The town's schooling</b>, for the board: how many of its grown folk went to school, and how its school stands
 *     ({@link #educationLine}).</li>
 * </ul>
 */
public final class SchoolDays {

    private SchoolDays() {}

    /** Break, after the lesson: this long in the yard. */
    static final long RECESS = 1200L;
    /** How often a home lesson teaches (it teaches at a third of the school's pace). */
    static final int HOME_BEAT = 300;

    private static final Map<UUID, Long> HOME_AT = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> TUTOR = new ConcurrentHashMap<>();

    public static void resetForTests() {
        HOME_AT.clear();
        TUTOR.clear();
    }

    /**
     * The jobs work's hook (WP-B): a player who has taken the post of teacher in a town (village, player). Nothing sets it
     * yet; when the jobs work does, School can let the player teach in place of a folk (the post is held open meanwhile by
     * Interviews' vacancy, as for a folk).
     */
    public static java.util.function.BiPredicate<UUID, UUID> playerTeacher = (village, player) -> false;

    /** No school today: the town's day of rest (the weekend, when the calendar has one). */
    public static boolean noSchool(UUID village, long day) {
        return Calendar.isWeekend(day) || RestDay.today(village, day);   // [featlife] no school at weekends
    }

    // ------------------------------------------------------------------ in class

    /** The teacher at the front, a beat of the lesson: it points at the board, or turns to the class. */
    static void teacherBeat(VillageFolkEntity teacher, RandomSource r) {
        FolkAction.play(teacher, r.nextBoolean() ? FolkAction.Act.POINT : FolkAction.Act.TEACH, 50);
    }

    /** A pupil at its desk: writing on its slate, and now and then a hand up with an answer. */
    static void pupilBeat(VillageFolkEntity pupil, RandomSource r) {
        if (r.nextInt(5) == 0) {
            FolkAction.play(pupil, FolkAction.Act.WAVE, 30);                // a hand up
            if (r.nextInt(3) == 0) FolkTalk.speak(pupil, FolkTalk.pick(r, "Me! Me!", "I know this one.", "Is it the answer?",
                "Miss! Sir! Teacher!", "Can I say?", "Oh! Oh! Pick me."));
        } else {
            FolkAction.play(pupil, FolkAction.Act.WRITE, 80);
        }
    }

    // ------------------------------------------------------------------ break

    /** The yard: a few steps out of the schoolhouse's door. */
    @Nullable
    static BlockPos yard(Ledger.Building b) {
        BlockPos door = School.at(b, 0, 0, -4), front = School.at(b, 0, 0, 3);
        int dx = Integer.signum(door.getX() - front.getX()), dz = Integer.signum(door.getZ() - front.getZ());
        return door.offset(dx * 5, 0, dz * 5);
    }

    /** After the lesson, before the afternoon: out in the yard, playing. True while it is. */
    public static boolean recess(VillageFolkEntity child, ServerLevel level) {
        UUID village = child.ownerId();
        if (village == null || !child.isBaby()) return false;
        long t = level.getDayTime() % 24000L, day = level.getDayTime() / 24000L;
        if (t < School.LESSON_TO || t >= School.LESSON_TO + RECESS || noSchool(village, day) || !School.schoolAge(child, day)) return false;
        Ledger.Building b = School.schoolhouse(village, level.getGameTime());
        if (b == null || School.teacher(level, village, false) == null) return false;
        BlockPos yard = yard(b);
        if (yard == null || !level.isLoaded(yard)) return false;
        RandomSource r = child.getRandom();
        if (child.blockPosition().distSqr(yard) > 5.0 * 5.0) {
            if (child.getNavigation().isDone() || child.tickCount % 40 == 0) {
                child.walkTo(yard.offset(r.nextInt(5) - 2, 0, r.nextInt(5) - 2), 1.2D);
            }
        } else if (child.getNavigation().isDone()) {
            if (r.nextInt(3) == 0) child.walkTo(yard.offset(r.nextInt(7) - 3, 0, r.nextInt(7) - 3), 1.25D);
            else FolkAction.play(child, r.nextBoolean() ? FolkAction.Act.HOPSCOTCH : FolkAction.Act.TAG, 60);
            if (r.nextInt(40) == 0 && level.getNearestPlayer(child, 16.0) != null) FolkTalk.speak(child, FolkTalk.pick(r, "Break time!",
                "You're it!", "Not it!", "Hopscotch, then. I'll go first.", "That doesn't count, I wasn't ready.", "Ten more minutes. Please."));
        }
        child.hobbyNow = "playing in the school yard";
        return true;
    }

    // ------------------------------------------------------------------ taught at home

    /**
     * Lesson time, and no school for it (none standing, or no teacher): taught at home, a morning at a time, by whoever
     * has the time. True while it is.
     */
    public static boolean homeLesson(VillageFolkEntity child, ServerLevel level) {
        UUID village = child.ownerId();
        if (village == null || !child.isBaby() || child.isSleeping()) return false;
        long t = level.getDayTime() % 24000L, day = level.getDayTime() / 24000L;
        if (t < School.LESSON_FROM || t >= School.LESSON_TO || noSchool(village, day) || Raids.underAlarm(village)) return false;
        if (!School.schoolAge(child, day)) return false;
        if (School.schoolhouse(village, level.getGameTime()) != null && School.teacher(level, village, false) != null) return false;
        VillageFolkEntity tutor = tutor(level, child);
        if (tutor == null) return false;
        TUTOR.put(child.getUUID(), tutor.getUUID());
        if (child.distanceToSqr(tutor) > 2.5 * 2.5) {
            if (child.getNavigation().isDone() || child.tickCount % 40 == 0) child.walkTo(tutor.blockPosition(), 1.0D);
            child.hobbyNow = "on the way to its lesson with " + tutor.displayNameCap();
            return true;
        }
        child.getNavigation().stop();
        child.getLookControl().setLookAt(tutor, 30.0F, 30.0F);
        long now = level.getGameTime();
        Long last = HOME_AT.get(child.getUUID());
        if (last == null || now - last >= HOME_BEAT || now < last) {
            HOME_AT.put(child.getUUID(), now);
            Object[] lean = School.leaning(child, village);
            StationTask leaning = lean != null && lean[0] instanceof StationTask s ? s : StationTask.FARM;
            School.learn(child, tutor, leaning, StationTask.NONE);
            if (last == null || now - last > 6000L) child.lines().homeLessons++;   // a morning's lessons, counted once
            pupilBeat(child, child.getRandom());
            FolkAction.play(tutor, FolkAction.Act.TEACH, 40);
            RandomSource r = child.getRandom();
            if (r.nextInt(4) == 0 && level.getNearestPlayer(child, 16.0) != null) {
                tutor.sayLater(FolkTalk.pick(r, "Letters first, then sums.", "No, the other way up.", "Again. Slower.",
                    "That's the way. You'll have it by Friday.", "In my day we had a proper school. You've got me.", "Good. Now write it ten times."), 20);
            }
        }
        child.hobbyNow = "learning at home with " + tutor.displayNameCap();
        return true;
    }

    /** A retired folk of the town (the old have the time), else a parent or grandparent off work, else the elder. */
    @Nullable
    static VillageFolkEntity tutor(ServerLevel level, VillageFolkEntity child) {
        UUID village = child.ownerId();
        UUID was = TUTOR.get(child.getUUID());
        if (was != null && level.getEntity(was) instanceof VillageFolkEntity w && w.isAlive() && !w.isSleeping()
                && (w.lines().retired() || w.offWorkNow())) return w;
        VillageFolkEntity kin = null, retired = null, elder = null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f == child || f.isBaby() || f.isSleeping() || f.isShowcase() || f.isHired()) continue;
            if (f.distanceToSqr(child) > 64 * 64) continue;
            if (f.lines().retired() && retired == null) retired = f;
            if (kin == null && f.offWorkNow() && (child.parentIds().contains(f.getUUID()) || child.life().parents().contains(f.displayNameCap()))) kin = f;
            if (f.isElder()) elder = f;
        }
        return retired != null ? retired : kin != null ? kin : elder;
    }

    /** Is this folk teaching a child at home this morning (a retired folk's day waits for it)? */
    public static boolean tutoring(VillageFolkEntity f) {
        return TUTOR.containsValue(f.getUUID()) && f.level().getDayTime() % 24000L < School.LESSON_TO;
    }

    // ------------------------------------------------------------------ the board

    /** Did it go to school (or have its lessons at home) as a child? */
    public static boolean schooled(VillageFolkEntity f) {
        return f.lines().schoolMornings > 0 || f.lines().homeLessons > 0;
    }

    /** "Schooling: 6 of 14 grown folk went to school (43%); the school has 9 pupils at 8 desks, Fen teaching". */
    public static String educationLine(ServerLevel level, UUID village) {
        int grown = 0, schooled = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase()) continue;
            grown++;
            if (schooled(f)) schooled++;
        }
        StringBuilder sb = new StringBuilder("Schooling: ").append(schooled).append(" of ").append(grown).append(" grown folk went to school")
            .append(grown == 0 ? "" : " (" + (schooled * 100 / grown) + "%)");
        Ledger.Building b = School.schoolhouse(village, -1L);
        int pupils = School.pupils(level, village).size();
        if (b == null) sb.append("; no schoolhouse yet, so the children are taught at home");
        else {
            VillageFolkEntity t = School.teacher(level, village, false);
            int desks = School.seats(b).size();
            sb.append("; the school has ").append(pupils).append(pupils == 1 ? " pupil" : " pupils").append(" at ").append(desks).append(" desks")
                .append(pupils > desks ? " (full: the rest stand in the aisle)" : "")
                .append(t == null ? ", and the post of teacher is vacant" : ", " + t.displayNameCap() + " teaching");
        }
        return sb.append('.').toString();
    }
}
