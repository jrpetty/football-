package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featlife] Every employer's opening days and working hours, and its hands kept to them.
 *
 * <ul>
 * <li><b>Who sets them.</b> A business a folk owns (its own firm, its privatised field, boat or holt) keeps the hours its
 *     owner's character chooses, once, the first morning it is asked (LifeWeights.ownerHours: the ambitious long hours and a
 *     shop open every day, the hardworking six days, the easygoing four or short ones). A player's business keeps the
 *     hours its owner sets on the week page (WeekPage, "Hours"). The town's own businesses keep their type's (a shop open on
 *     market day and shut on Sunday). The town's works (whoever has no business of its own to work for: the builders, the
 *     town's hands) keep the council's week: Monday to Friday.</li>
 * <li><b>Its hands.</b> A folk works its employer's hours and no others ({@link #shift}): on a day its employer is shut it
 *     has the day to itself (WeekendLife), so on a Saturday most of the works are quiet while the shop, the market and the
 *     tavern keep going. An easygoing hand turns up an hour late; an ambitious one (or a hardworking one short of money)
 *     takes the Saturday morning's work when its employer offers it. The watch, the hired, the children and the
 *     hospitality's own shifts (Hospitality) are not touched.</li>
 * <li><b>When.</b> Only in a town past its first week and out of the Wood Age: a young town works every day, as it keeps
 *     no day of rest either (RestDay).</li>
 * </ul>
 * The Business record keeps the hours (Business.hours / setHours), so the Business Core hub reads and edits them.
 */
public final class WorkHours {

    private WorkHours() {}

    /** The town's works' hours: Monday to Friday, six to six. */
    public static final Business.Hours TOWN_WORKS = new Business.Hours(Business.Hours.WEEKDAYS, 6, 18, "the council's week: Monday to Friday");

    /** Folk who worked overtime today (the day), and who turned up late (the day): the card and the tests. */
    private static final Map<UUID, Long> LATE = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LATE.clear();
        CACHE.clear();
        forceForTests = false;
        cacheOff = false;
    }

    /** Tests: keep the hours in a young town too. */
    public static volatile boolean forceForTests = false;

    /** The clock's hour, 0..23 (the day starts at six). */
    public static int hour(long dayTime) {
        return (int) ((Math.floorMod(dayTime, 24000L) / 1000L + 6) % 24);
    }

    /** Does this town keep a working week yet: out of the Wood Age and past its first week? */
    public static boolean kept(@Nullable UUID village, long day) {
        if (village == null) return false;
        if (forceForTests) return true;
        if (Villages.ageOf(village).ordinal() < Villages.Age.STONE.ordinal()) return false;
        long founded = com.jrpetty.mcassistant.village.Chronicle.foundedOn(village);
        return founded < 0 || day - founded >= 7;
    }

    // ------------------------------------------------------------------ whose hours

    /** The hours a folk works: its employer's, or the town's works'. */
    public static Business.Hours of(UUID village, VillageFolkEntity f) {
        Business b = Businesses.employer(village, f);
        return b != null ? hoursOf(village, b) : town(village);
    }

    /** A business's hours, chosen by its folk owner the first time they are asked. */
    public static Business.Hours hoursOf(UUID village, Business b) {
        if (!b.hoursChosen()) choose(village, b);
        return b.hours();
    }

    /** The town's works' hours (the council's: Ledger "life.hours.town", else Monday to Friday). */
    public static Business.Hours town(UUID village) {
        String s = Ledger.note(village, "life.hours.town");
        if (s != null && !s.isEmpty()) {
            String[] p = s.split(",", 4);
            try {
                return new Business.Hours(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), p.length > 3 ? p[3] : "set by the council");
            } catch (RuntimeException ignored) { }
        }
        return TOWN_WORKS;
    }

    public static void setTown(UUID village, Business.Hours h) {
        Ledger.note(village, "life.hours.town", h.days() + "," + h.open() + "," + h.close() + "," + h.why().replace(',', ';'));
    }

    /** Its owner, if a folk owns the most of it and it is loaded. */
    @Nullable
    static VillageFolkEntity folkOwner(UUID village, Business b) {
        VillageFolkEntity o = Temperament.owner(village, b);
        if (o != null) return o;
        Business.Stake top = null;
        for (Business.Stake s : b.owners) if (s.holder() == Business.Holder.FOLK && s.who() != null && (top == null || s.parts() > top.parts())) top = s;
        return top == null ? null : Homes.loaded(village, top.who());
    }

    /** A folk-owned business's hours, chosen by its owner's character (once; a player's or the town's keep the type's). */
    static void choose(UUID village, Business b) {
        if (b.hoursChosen() || b.town()) return;
        VillageFolkEntity owner = folkOwner(village, b);
        if (owner == null) return;
        b.setHours(LifeWeights.ownerHours(owner, b.type));
        b.note(-1, "hours " + b.hours().words() + ", " + b.hours().why());
    }

    // ------------------------------------------------------------------ its hands

    /**
     * Is this folk off by its employer's hours just now? False (off: its employer is shut, or it is not its hour yet),
     * or null (the hours say nothing: the rest of the day's rules decide). From VillageFolkEntity.onShift.
     */
    @Nullable
    public static Boolean shift(VillageFolkEntity f) {
        if (cacheOff) return shiftNow(f);
        // Asked many times a tick (onShift): the answer kept for a second, the clock's hour being all it turns on.
        long now = f.level().getGameTime();
        Object[] c = CACHE.get(f.getUUID());
        if (c != null && now - (Long) c[0] < 20 && now >= (Long) c[0] && f.stationTask() == c[2]) return (Boolean) c[1];
        Boolean b = shiftNow(f);
        if (CACHE.size() > 8192) CACHE.clear();
        CACHE.put(f.getUUID(), new Object[]{ now, b, f.stationTask() });
        return b;
    }

    private static final Map<UUID, Object[]> CACHE = new ConcurrentHashMap<>();
    /** Tests that move the clock by hand turn the cache off. */
    public static volatile boolean cacheOff = false;

    @Nullable
    static Boolean shiftNow(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || f.isHired() || f.stationTask() == AssistantEntity.StationTask.GUARD) return null;
        long dt = f.level().getDayTime(), day = dt / 24000L;
        if (!kept(village, day) || Raids.underAlarm(village)) return null;
        if (f.stationTask() == AssistantEntity.StationTask.NONE) return Calendar.isWeekend(day) ? Boolean.FALSE : null;   // no trade: the weekend off too
        if (Hospitality.role(f) != Hospitality.Role.NONE) return null;   // the bar's and the kitchen's own shifts
        Business emp = Businesses.employer(village, f);
        int[] rs = emp == null ? null : Rota.slotsToday(village, emp, f.getUUID(), day);   // [rota] its employer's rota first
        if (rs != null) return rotaShift(f, village, emp, rs, dt, day);
        Business.Hours h = emp != null ? hoursOf(village, emp) : town(village);
        int dow = Calendar.dow(day), hr = hour(dt);
        if (!h.openOn(dow)) {
            // Extra work on a Saturday morning: the ambitious, and the hardworking short of money, take it.
            if (Calendar.day(day) == Calendar.Day.SATURDAY && hr < LifeWeights.OVERTIME_UNTIL_HOUR && LifeWeights.takesOvertime(f)) {
                if (!Long.valueOf(day).equals(LifeLog.lastOvertime(f))) {
                    LifeLog.overtime(f, day, LifeWeights.why(f, LifeWeights.Mark.AMBITIOUS, LifeWeights.Mark.HARDWORKING));
                }
                return null;
            }
            return false;
        }
        // Sunday: a business open seven days opens after the morning service.
        if (Calendar.isRestDay(day) && RestDay.today(village, day)) {
            return h.openAt(dow, hr) && dt % 24000L >= RestDay.GAMES_FROM ? Boolean.TRUE : null;
        }
        int late = LifeWeights.lateHours(f);
        if (hr < h.open() + late) {
            if (late > 0 && hr >= h.open() && !Long.valueOf(day).equals(LATE.get(f.getUUID()))) {
                LATE.put(f.getUUID(), day);
                LifeLog.late(f, day, LifeWeights.why(f, LifeWeights.Mark.EASYGOING));
            }
            return false;
        }
        if (hr >= h.close()) return false;
        return null;
    }

    /**
     * [rota] A hand on its employer's rota: in from its shift's start (an easygoing one an hour late), out at its end (an
     * ambitious or a hard-up hardworking one stays the hour after while the business is open); a day off its own, but
     * the Saturday morning's extra work taken as before. True inside its shift, so an evening shift is kept past the bell.
     */
    @Nullable
    static Boolean rotaShift(VillageFolkEntity f, UUID village, Business emp, int[] rs, long dt, long day) {
        int dow = Calendar.dow(day), hr = hour(dt);
        Business.Hours h = hoursOf(village, emp);
        if (!Rota.works(rs)) {
            if (Calendar.day(day) == Calendar.Day.SATURDAY && h.openOn(dow) && hr >= h.open() && hr < LifeWeights.OVERTIME_UNTIL_HOUR && LifeWeights.takesOvertime(f)) {
                if (!Long.valueOf(day).equals(LifeLog.lastOvertime(f))) {
                    LifeLog.overtime(f, day, LifeWeights.why(f, LifeWeights.Mark.AMBITIOUS, LifeWeights.Mark.HARDWORKING));
                }
                return null;
            }
            return false;
        }
        if (Calendar.isRestDay(day) && RestDay.today(village, day)) {
            return Rota.at(rs, hr) && dt % 24000L >= RestDay.GAMES_FROM ? Boolean.TRUE : null;
        }
        int late = LifeWeights.lateHours(f);
        if (late > 0 && hr >= rs[0] && hr < rs[0] + late) {
            if (!Long.valueOf(day).equals(LATE.get(f.getUUID()))) {
                LATE.put(f.getUUID(), day);
                LifeLog.late(f, day, LifeWeights.why(f, LifeWeights.Mark.EASYGOING));
            }
            return false;
        }
        if (Rota.at(rs, hr)) {
            Rota.seenAtWork(f, emp, dt, rs);
            return Boolean.TRUE;
        }
        int end = rs[2] > 0 ? rs[3] : rs[1];
        if (hr == end && h.openAt(dow, hr) && LifeWeights.takesOvertime(f)) {
            if (!Long.valueOf(day).equals(LifeLog.lastOvertime(f))) LifeLog.overtime(f, day, LifeWeights.why(f, LifeWeights.Mark.AMBITIOUS, LifeWeights.Mark.HARDWORKING));
            Rota.seenAtWork(f, emp, dt, rs);
            return Boolean.TRUE;
        }
        return false;
    }

    /** [rota] Its rota's hours today, if its employer keeps one with a line for it (else null: its employer's hours). */
    @Nullable
    public static int[] rotaToday(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return null;
        Business emp = Businesses.employer(village, f);
        return emp == null ? null : Rota.slotsToday(village, emp, f.getUUID(), f.level().getDayTime() / 24000L);
    }

    /** [rota] The hour its work ends today: its rota's last shift's end, else its employer's closing. */
    public static int endsAt(UUID village, VillageFolkEntity f) {
        int[] rs = rotaToday(f);
        if (rs != null && Rota.works(rs)) return rs[2] > 0 ? rs[3] : rs[1];
        return of(village, f).close();
    }

    /** Off today by its employer's days (not by the hour): the weekend's own. */
    public static boolean dayOff(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || f.isHired() || f.stationTask() == AssistantEntity.StationTask.GUARD) return false;
        long day = f.level().getDayTime() / 24000L;
        if (!kept(village, day)) return false;
        if (f.stationTask() == AssistantEntity.StationTask.NONE) return Calendar.isWeekend(day);
        if (Hospitality.role(f) != Hospitality.Role.NONE) return false;
        int[] rs = rotaToday(f);                                           // [rota] a day off on its rota
        if (rs != null) return !Rota.works(rs);
        return !of(village, f).openOn(Calendar.dow(day));
    }

    /** Did it work overtime today? */
    public static boolean overtimeToday(VillageFolkEntity f) {
        Long d = LifeLog.lastOvertime(f);
        return d != null && d == f.level().getDayTime() / 24000L;
    }

    /** Is a town's business of this type open now (its counter serves)? True if it has none on the books. */
    public static boolean openNow(ServerLevel level, UUID village, Business.Type type) {
        long dt = level.getDayTime(), day = dt / 24000L;
        if (!kept(village, day)) return true;
        for (Business b : Businesses.all(village)) {
            if (b.type != type || !b.open) continue;
            return hoursOf(village, b).openAt(Calendar.dow(day), hour(dt));
        }
        return true;
    }

    // ------------------------------------------------------------------ the board and the books

    /** The board's line: what is open today. */
    @Nullable
    public static String boardLine(UUID village, long day) {
        if (!kept(village, day)) return null;
        int dow = Calendar.dow(day), open = 0, shut = 0;
        List<String> openNames = new ArrayList<>();
        for (Business b : Businesses.all(village)) {
            if (!b.open || b.mirror()) continue;
            if (hoursOf(village, b).openOn(dow)) {
                open++;
                if (openNames.size() < 3 && b.type.size != Business.Size.YARD) openNames.add(b.name);
            } else shut++;
        }
        boolean works = town(village).openOn(dow);
        if (open == 0 && shut == 0) return works ? null : "The town's works are shut today.";
        return (works ? "" : "The town's works are shut today. ") + open + " open" + (openNames.isEmpty() ? "" : " (" + String.join(", ", openNames)
            + (open > openNames.size() ? "…" : "") + ")") + ", " + shut + " shut.";
    }

    /** The books' lines: every business and its hours. */
    public static List<String> bookLines(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        out.add("The town's works: " + town(village).words() + " (" + town(village).why() + ").");
        for (Business b : Businesses.all(village)) {
            if (!b.open || b.mirror()) continue;
            Business.Hours h = hoursOf(village, b);
            out.add(b.name + ": " + h.words() + " (" + h.why() + ").");
        }
        return out;
    }
}
