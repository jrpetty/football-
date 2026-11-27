package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * [rota] A business's staff rota: who works which hours on which day of the week.
 *
 * <ul>
 * <li><b>The grid.</b> A line for each member of staff (folk or player): seven days, each with up to two shifts (a split
 *     shift), a start and an end by the clock (5:00 to 24:00), or the day off. One-off changes for a date sit over it
 *     (a day off approved, a swap), and a "busy week" template can stand in for the usual one on the fair's week.</li>
 * <li><b>Who sets it.</b> A player's business: its owner, the managing partner, or the largest owner (Partners.canDecide);
 *     its deputy manager when the owner allows it (the "Adjust the rota" task) and is away. A folk-owned business: its
 *     owner builds it by its character, once a week ({@link #forFolkFirm}). Staff only see their own shifts, and ask for a
 *     day off or a swap, which the manager approves.</li>
 * <li><b>Its hands follow it.</b> A folk arrives for its shift and leaves at its end (WorkHours.shiftNow reads
 *     {@link #slotsToday}); an easygoing hand still turns up late, an ambitious one still takes extra; a day off is its own
 *     (WeekendLife's outings). With no rota, or no line for a hand, its employer's hours stand as before.</li>
 * <li><b>Pay.</b> A salaried folk on a player's rota is paid by the hours rostered: forty a week is its full wage, hours
 *     past the business's overtime line cost half as much again ({@link #wageFactor}).</li>
 * <li><b>What they think of it.</b> The easygoing like fewer hours, the ambitious more ({@link #prefHours}); rostered well
 *     against that, their spirits drop (VillageFolkEntity's mood: {@link #mood}) and the rota page says why.</li>
 * </ul>
 * Kept on the Business record (saved with it).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = com.jrpetty.mcassistant.McAssistantMod.MODID)
public final class Rota {

    private static long lastMorning = -1;

    /**
     * Each morning (checked every ten seconds): a player's rota brought up to date with its staff, its folk asking for
     * their club night or a family day off (the owner told), the old one-off changes and answered requests let go.
     */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 200 != 77) return;
        ServerLevel level = e.getServer().overworld();
        long day = level.getDayTime() / 24000L;
        if (day == lastMorning) return;
        lastMorning = day;
        for (Villages.Village v : Villages.every()) {
            try {
                for (Business b : Businesses.all(v.id())) {
                    Rota r = b.rota;
                    if (r == null || r.auto || !r.active() || !b.open) continue;
                    sync(level, v.id(), b, r);
                    folkAsk(v.id(), b, r, day);
                    r.overrides.keySet().removeIf(d -> d < day - 7);
                    r.requests.removeIf(q -> q.day < day - 7);
                    pushPlanner(level, v.id(), b);
                }
            } catch (RuntimeException ex) {
                com.mojang.logging.LogUtils.getLogger().warn("[rota] the morning's rotas failed for a town", ex);
            }
        }
    }

    // ------------------------------------------------------------------ the rules

    /** The hours a week that are a full wage; overtime past the business's line is paid at half as much again. */
    public static final int FULL_WEEK = 40, OVERTIME_DEFAULT = 48;
    public static final double OVERTIME_RATE = 1.5;
    /** Every member of staff has two days off a week at least. */
    public static final int MOST_DAYS = 5;
    /** The longest a single shift may be. */
    public static final int LONGEST_SHIFT = 12;
    public static final String[] DAY_SHORT = { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };

    // ------------------------------------------------------------------ what it keeps

    /** A member of staff's line: who, its name, a player or not, and four hours a day (start, end, second start, second end; 0 for none). */
    public static final class Line {
        public final UUID who;
        public String name;
        public boolean player, owner;
        /** New to the rota, not yet rostered by anyone: on the opening hours (five days) till they are. */
        public boolean fresh;
        public final int[] h = new int[28];

        Line(UUID who, String name, boolean player) {
            this.who = who;
            this.name = name;
            this.player = player;
        }

        public int[] day(int dow) {
            int i = Math.floorMod(dow, 7) * 4;
            return new int[]{ h[i], h[i + 1], h[i + 2], h[i + 3] };
        }

        public void set(int dow, int[] s) {
            int i = Math.floorMod(dow, 7) * 4;
            int[] c = clean(s);
            System.arraycopy(c, 0, h, i, 4);
        }

        public int weekHours() {
            int n = 0;
            for (int d = 0; d < 7; d++) n += hours(day(d));
            return n;
        }

        public int daysWorked() {
            int n = 0;
            for (int d = 0; d < 7; d++) if (works(day(d))) n++;
            return n;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("w", who);
            t.putString("n", name);
            t.putBoolean("p", player);
            if (owner) t.putBoolean("o", true);
            if (fresh) t.putBoolean("f", true);
            t.putIntArray("h", h);
            return t;
        }

        static Line load(CompoundTag t) {
            Line l = new Line(t.getUUID("w"), t.getString("n"), t.getBoolean("p"));
            l.owner = t.getBoolean("o");
            l.fresh = t.getBoolean("f");
            int[] a = t.getIntArray("h");
            System.arraycopy(a, 0, l.h, 0, Math.min(28, a.length));
            return l;
        }
    }

    /** A request: a day off (for a reason) or a swap (another to cover the shift), asked by a member of staff. */
    public static final class Request {
        public int id;
        public UUID who;
        public String name = "", why = "", kind = "off", state = "asked";
        @Nullable public UUID with;
        public String withName = "";
        public long day;
        public boolean player;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("id", id);
            t.putUUID("w", who);
            t.putString("n", name);
            t.putString("y", why);
            t.putString("k", kind);
            t.putString("s", state);
            if (with != null) t.putUUID("x", with);
            t.putString("xn", withName);
            t.putLong("d", day);
            t.putBoolean("p", player);
            return t;
        }

        static Request load(CompoundTag t) {
            Request r = new Request();
            r.id = t.getInt("id");
            r.who = t.getUUID("w");
            r.name = t.getString("n");
            r.why = t.getString("y");
            r.kind = t.getString("k");
            r.state = t.getString("s");
            if (t.hasUUID("x")) r.with = t.getUUID("x");
            r.withName = t.getString("xn");
            r.day = t.getLong("d");
            r.player = t.getBoolean("p");
            return r;
        }
    }

    public final Map<UUID, Line> lines = new LinkedHashMap<>();
    /** One-off changes by date: day → who → its hours that day (all naught: off). */
    public final Map<Long, Map<UUID, int[]>> overrides = new HashMap<>();
    /** Saved weeks: "usual" and "busy" (the fair's week), by name → who → hours. */
    public final Map<String, Map<UUID, int[]>> templates = new LinkedHashMap<>();
    public final List<Request> requests = new ArrayList<>();
    /** The timesheet: who → {day, clocked in (the clock's hour ×60 + minute), out, the shift's start (hour), late (minutes)}, the last fourteen. */
    public final Map<UUID, List<long[]>> clock = new HashMap<>();
    /** Who set it last and when, in words; built by a folk owner's character (rebuilt each week), and that week. */
    public String setBy = "";
    public long setDay = -1;
    public boolean auto;
    public long builtWeek = -1;
    /** Use the busy template on the fair's week. */
    public boolean busyOnFair = true;
    /** Hours a week past which a hand is paid overtime. */
    public int overtimeAt = OVERTIME_DEFAULT;
    int seq;

    public boolean active() {
        return !lines.isEmpty();
    }

    public Line line(UUID who, String name, boolean player) {
        Line l = lines.get(who);
        if (l == null) { l = new Line(who, name, player); lines.put(who, l); }
        else if (name != null && !name.isEmpty()) l.name = name;
        return l;
    }

    // ------------------------------------------------------------------ keeping

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        ListTag ls = new ListTag();
        for (Line l : lines.values()) ls.add(l.save());
        t.put("lines", ls);
        ListTag ov = new ListTag();
        for (Map.Entry<Long, Map<UUID, int[]>> e : overrides.entrySet()) {
            for (Map.Entry<UUID, int[]> x : e.getValue().entrySet()) {
                CompoundTag o = new CompoundTag();
                o.putLong("d", e.getKey());
                o.putUUID("w", x.getKey());
                o.putIntArray("h", x.getValue());
                ov.add(o);
            }
        }
        t.put("over", ov);
        CompoundTag tp = new CompoundTag();
        for (Map.Entry<String, Map<UUID, int[]>> e : templates.entrySet()) {
            CompoundTag one = new CompoundTag();
            for (Map.Entry<UUID, int[]> x : e.getValue().entrySet()) one.putIntArray(x.getKey().toString(), x.getValue());
            tp.put(e.getKey(), one);
        }
        t.put("tpl", tp);
        ListTag rq = new ListTag();
        for (Request r : requests) rq.add(r.save());
        t.put("req", rq);
        CompoundTag ck = new CompoundTag();
        for (Map.Entry<UUID, List<long[]>> e : clock.entrySet()) {
            ListTag l = new ListTag();
            for (long[] a : e.getValue()) l.add(new net.minecraft.nbt.LongArrayTag(a));
            ck.put(e.getKey().toString(), l);
        }
        t.put("clock", ck);
        t.putString("by", setBy);
        t.putLong("on", setDay);
        t.putBoolean("auto", auto);
        t.putLong("week", builtWeek);
        t.putBoolean("busy", busyOnFair);
        t.putInt("ot", overtimeAt);
        t.putInt("seq", seq);
        return t;
    }

    public static Rota load(CompoundTag t) {
        Rota r = new Rota();
        for (Tag e : t.getList("lines", Tag.TAG_COMPOUND)) { Line l = Line.load((CompoundTag) e); r.lines.put(l.who, l); }
        for (Tag e : t.getList("over", Tag.TAG_COMPOUND)) {
            CompoundTag o = (CompoundTag) e;
            r.overrides.computeIfAbsent(o.getLong("d"), k -> new HashMap<>()).put(o.getUUID("w"), clean(o.getIntArray("h")));
        }
        CompoundTag tp = t.getCompound("tpl");
        for (String k : tp.getAllKeys()) {
            CompoundTag one = tp.getCompound(k);
            Map<UUID, int[]> m = new LinkedHashMap<>();
            for (String u : one.getAllKeys()) {
                try { m.put(UUID.fromString(u), java.util.Arrays.copyOf(one.getIntArray(u), 28)); } catch (IllegalArgumentException ignored) { }
            }
            r.templates.put(k, m);
        }
        for (Tag e : t.getList("req", Tag.TAG_COMPOUND)) r.requests.add(Request.load((CompoundTag) e));
        CompoundTag ck = t.getCompound("clock");
        for (String k : ck.getAllKeys()) {
            try {
                List<long[]> l = new ArrayList<>();
                for (Tag x : ck.getList(k, Tag.TAG_LONG_ARRAY)) l.add(((net.minecraft.nbt.LongArrayTag) x).getAsLongArray());
                r.clock.put(UUID.fromString(k), l);
            } catch (IllegalArgumentException ignored) { }
        }
        r.setBy = t.getString("by");
        r.setDay = t.contains("on") ? t.getLong("on") : -1;
        r.auto = t.getBoolean("auto");
        r.builtWeek = t.contains("week") ? t.getLong("week") : -1;
        r.busyOnFair = !t.contains("busy") || t.getBoolean("busy");
        r.overtimeAt = t.contains("ot") ? t.getInt("ot") : OVERTIME_DEFAULT;
        r.seq = t.getInt("seq");
        return r;
    }

    // ------------------------------------------------------------------ shifts

    /** A day's hours put right: each shift inside 5:00–24:00, at least an hour, the second after the first; none if nonsense. */
    static int[] clean(int[] s) {
        int[] a = java.util.Arrays.copyOf(s == null ? new int[4] : s, 4);
        int[] out = new int[4];
        int n = 0;
        for (int k = 0; k < 2; k++) {
            int st = a[k * 2], en = a[k * 2 + 1];
            if (st <= 0 && en <= 0) continue;
            st = Math.max(5, Math.min(23, st));
            en = Math.max(st + 1, Math.min(24, en));
            if (en - st > LONGEST_SHIFT) en = st + LONGEST_SHIFT;
            if (n == 1 && st < out[1]) { st = out[1]; if (en <= st) continue; }
            out[n * 2] = st;
            out[n * 2 + 1] = en;
            n++;
        }
        return out;
    }

    public static boolean works(int[] s) {
        return s[1] > s[0] && s[0] > 0;
    }

    public static int hours(int[] s) {
        int n = 0;
        if (s[1] > s[0] && s[0] > 0) n += s[1] - s[0];
        if (s[3] > s[2] && s[2] > 0) n += s[3] - s[2];
        return n;
    }

    public static boolean at(int[] s, int hour) {
        return s[0] > 0 && hour >= s[0] && hour < s[1] || s[2] > 0 && hour >= s[2] && hour < s[3];
    }

    /** "07:00–15:00", "07:00–11:00 & 15:00–19:00", "Off". */
    public static String words(int[] s) {
        if (!works(s)) return "Off";
        String w = hh(s[0]) + "–" + hh(s[1]);
        if (s[2] > 0 && s[3] > s[2]) w += " & " + hh(s[2]) + "–" + hh(s[3]);
        return w;
    }

    public static String hh(int h) {
        return String.format(Locale.ROOT, "%02d:00", h);
    }

    /** Is it the fair's week (the fourth week of the month)? */
    public static boolean fairWeek(long day) {
        return Calendar.weekOfMonth(day) == 4;
    }

    public static long weekOf(long day) {
        return Math.floorDiv(day - Calendar.dow(day), 7);
    }

    /** Its hours on this date: a one-off change first, then the busy week's on the fair's week, then its line. Null: not on the rota. */
    @Nullable
    public int[] slotsOn(UUID who, long day) {
        Map<UUID, int[]> o = overrides.get(day);
        if (o != null && o.containsKey(who)) return o.get(who);
        Line l = lines.get(who);
        if (l == null) return null;
        int dow = Calendar.dow(day);
        if (busyOnFair && fairWeek(day)) {
            Map<UUID, int[]> busy = templates.get("busy");
            if (busy != null && busy.containsKey(who)) {
                int[] a = busy.get(who);
                return clean(new int[]{ a[dow * 4], a[dow * 4 + 1], a[dow * 4 + 2], a[dow * 4 + 3] });
            }
        }
        return l.day(dow);
    }

    // ------------------------------------------------------------------ the rota that applies

    /** The rota a business keeps, if any (a folk owner's built by its character, once a week). */
    @Nullable
    public static Rota of(@Nullable UUID village, @Nullable Business b) {
        if (b == null) return null;
        if (b.rota != null && b.rota.active() && !b.rota.auto) return b.rota;
        if (village == null || b.town() || b.mirror() || !b.open) return b.rota != null && b.rota.active() ? b.rota : null;
        if (playerOwned(b)) return b.rota != null && b.rota.active() ? b.rota : null;
        return forFolkFirm(village, b);
    }

    public static boolean playerOwned(Business b) {
        for (Business.Stake s : b.owners) if (s.holder() == Business.Holder.PLAYER) return true;
        return false;
    }

    /** Its hours today for a folk on its employer's rota: null if the rota says nothing for it (no rota, no line). */
    @Nullable
    public static int[] slotsToday(UUID village, Business b, UUID who, long day) {
        Rota r = of(village, b);
        return r == null ? null : r.slotsOn(who, day);
    }

    /**
     * A folk-owned business's rota, built by its owner's character the first time it is asked each week: the ambitious
     * keep a lean shop and long shifts, the easygoing short ones and a spare hand on, everyone two days off; the owner,
     * working its own business, keeps its own hours. Null with nobody to roster (a firm of one: its owner's hours stand).
     */
    @Nullable
    static Rota forFolkFirm(UUID village, Business b) {
        long now = currentDay();
        if (now < 0) return b.rota != null && b.rota.active() ? b.rota : null;
        long week = weekOf(now);
        if (b.rota != null && b.rota.auto && b.rota.builtWeek == week) return b.rota.active() ? b.rota : null;
        VillageFolkEntity owner = WorkHours.folkOwner(village, b);
        List<VillageFolkEntity> staff = Businesses.staff(village, b);
        Rota r = b.rota != null && b.rota.auto ? b.rota : new Rota();
        r.auto = true;
        r.builtWeek = week;
        b.rota = r;
        if (owner == null || staff.size() < 2) {
            r.lines.clear();
            return null;
        }
        Business.Hours h = WorkHours.hoursOf(village, b);
        r.lines.clear();
        List<Staff> people = new ArrayList<>();
        for (VillageFolkEntity f : staff) people.add(Staff.of(f, village, now));
        EnumSet<LifeWeights.Mark> m = LifeWeights.marks(owner);
        int[][] need = need(village, b, h, people.size(), now);
        if (m.contains(LifeWeights.Mark.EASYGOING)) for (int[] d : need) for (int i = 0; i < 24; i++) if (d[i] > 0 && people.size() >= 3) d[i] = Math.max(d[i], 2);
        if (m.contains(LifeWeights.Mark.AMBITIOUS) || m.contains(LifeWeights.Mark.THRIFTY)) for (int[] d : need) for (int i = 0; i < 24; i++) if (d[i] > 1) d[i] = 1;
        fill(r, h, people, need, owner.getUUID());
        r.setBy = owner.displayNameCap() + " (" + (m.contains(LifeWeights.Mark.AMBITIOUS) ? "ambitious: a lean shop" : m.contains(LifeWeights.Mark.EASYGOING)
            ? "easygoing: a spare hand on" : m.contains(LifeWeights.Mark.HARDWORKING) ? "hardworking: every hour covered" : "the usual way") + ")";
        r.setDay = now;
        decideRequests(village, b, r, owner, now);
        Businesses.dirty();
        return r;
    }

    static volatile long dayForTests = -1;

    static long currentDay() {
        if (dayForTests >= 0) return dayForTests;
        net.minecraft.server.MinecraftServer s = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return s == null ? -1 : s.overworld().getDayTime() / 24000L;
    }

    // ------------------------------------------------------------------ preferences

    /** The hours a week this folk would like: the easygoing fewer, the ambitious and the hardworking more. */
    public static int prefHours(VillageFolkEntity f) {
        EnumSet<LifeWeights.Mark> m = LifeWeights.marks(f);
        if (m.contains(LifeWeights.Mark.AMBITIOUS)) return 50;
        if (m.contains(LifeWeights.Mark.EASYGOING)) return 28;
        if (m.contains(LifeWeights.Mark.HARDWORKING)) return 46;
        return FULL_WEEK;
    }

    /** Why it prefers those hours, in a word ("easygoing"), "" for the ordinary. */
    public static String prefWhy(VillageFolkEntity f) {
        return LifeWeights.why(f, LifeWeights.Mark.AMBITIOUS, LifeWeights.Mark.EASYGOING, LifeWeights.Mark.HARDWORKING);
    }

    /** How it takes its rostered hours: negative for strain (morale lost), with the reason. */
    public record Feeling(int mood, String why) {}

    public static Feeling feeling(VillageFolkEntity f, int rostered) {
        int pref = prefHours(f);
        String w = prefWhy(f);
        if (rostered > pref + 6) {
            int hit = Math.min(8, (rostered - pref) / 3);
            return new Feeling(-hit, f.displayNameCap() + (w.isEmpty() ? "" : " is " + w + ":") + " likes about " + pref + " hours, rostered " + rostered);
        }
        if (rostered < pref - 12 && (LifeWeights.has(f, LifeWeights.Mark.AMBITIOUS) || LifeWeights.has(f, LifeWeights.Mark.HARDWORKING) && f.purse() < 10)) {
            return new Feeling(-2, f.displayNameCap() + " is " + w + (f.purse() < 10 && !w.equals("ambitious") ? " and short of money" : "") + ": asks for more hours ("
                + rostered + " of the " + pref + " wanted)");
        }
        if (Math.abs(rostered - pref) <= 4 && rostered > 0) return new Feeling(1, f.displayNameCap() + " is happy with " + rostered + " hours");
        return new Feeling(0, "");
    }

    /** VillageFolkEntity's mood: rostered well against its liking. */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || f.isHired() || f.stationTask() == AssistantEntity.StationTask.NONE) return m;
        Business b = Businesses.employer(village, f);
        if (b == null || b.rota == null || !b.rota.active() || !WorkHours.kept(village, day)) return m;
        Line l = b.rota.lines.get(f.getUUID());
        if (l == null || l.owner) return m;
        Feeling fe = feeling(f, l.weekHours());
        if (fe.mood() < -2) { why.add(new Object[]{ "rotaheavy", -fe.mood() }); return m + fe.mood(); }
        if (fe.mood() < 0) { why.add(new Object[]{ "rotashort", -fe.mood() }); return m + fe.mood(); }
        return m;
    }

    /** The staff's morale on the History tab: its hours against its liking (points out of a hundred). */
    public static int moraleShift(UUID village, Business b, VillageFolkEntity f) {
        if (b.rota == null || !b.rota.active()) return 0;
        Line l = b.rota.lines.get(f.getUUID());
        return l == null || l.owner ? 0 : 3 * feeling(f, l.weekHours()).mood();
    }

    // ------------------------------------------------------------------ pay

    /** The share of its day's wage a hand on this rota is paid: its week's hours over forty, overtime at half as much again. */
    public double wageFactor(UUID who) {
        Line l = lines.get(who);
        if (l == null || l.owner) return 1.0;
        int h = l.weekHours();
        int ot = Math.max(0, h - overtimeAt);
        return (h - ot + OVERTIME_RATE * ot) / (double) FULL_WEEK;
    }

    /** Businesses.paysHand: a hand's day's wage on a player's rota, scaled by the hours rostered. */
    public static int scaleWage(UUID village, Business b, VillageFolkEntity f, int wage) {
        if (b.rota == null || !b.rota.active() || b.rota.auto || !WorkHours.kept(village, currentDay() < 0 ? 0 : currentDay())) return wage;
        if (!b.rota.lines.containsKey(f.getUUID())) return wage;
        return (int) Math.round(wage * b.rota.wageFactor(f.getUUID()));
    }

    /** The week's wage bill (coins): each folk's wage scaled by its hours, a player hand's wage for each day rostered. */
    public record Bill(long coins, long overtime, int hours, List<String> lines) {}

    public static Bill bill(UUID village, Business b, Rota r) {
        long total = 0, ot = 0;
        int hours = 0;
        List<String> out = new ArrayList<>();
        Map<UUID, VillageFolkEntity> folk = new HashMap<>();
        for (VillageFolkEntity f : staffOf(village, b)) folk.put(f.getUUID(), f);
        for (Line l : r.lines.values()) {
            int h = l.weekHours();
            hours += h;
            if (l.owner) continue;
            VillageFolkEntity f = folk.get(l.who);
            if (f != null) {
                int wage = Wealth.wage(f);
                long week = Math.round(wage * 7 * r.wageFactor(l.who));
                int over = Math.max(0, h - r.overtimeAt);
                long otc = Math.round(wage * 7 * (OVERTIME_RATE * over) / FULL_WEEK);
                total += week;
                ot += otc;
                out.add(l.name + ": " + h + "h, " + week + "c" + (over > 0 ? " (" + over + "h overtime, " + otc + "c of it)" : ""));
            } else if (l.player) {
                int wage = playerWage(b, l.who);
                long week = (long) wage * l.daysWorked();
                total += week;
                out.add(l.name + ": " + h + "h over " + l.daysWorked() + " days, " + week + "c" + (wage == 0 ? " (on commission)" : ""));
            }
        }
        return new Bill(total, ot, hours, out);
    }

    /** A player hand's day's wage (salaried; commission: nought), read off its job while it is online. */
    static int playerWage(Business b, UUID who) {
        net.minecraft.server.MinecraftServer s = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        ServerPlayer p = s == null ? null : s.getPlayerList().getPlayer(who);
        if (p == null) return 0;
        CompoundTag j = PlayerJobs.job(p);
        if (!("firm:" + b.id).equals(j.getString("employer")) || "commission".equals(j.getString("pay"))) return 0;
        return j.getInt("wage");
    }

    // ------------------------------------------------------------------ staff

    /** Its folk staff (loaded): its firm's books and whoever works for it. */
    static List<VillageFolkEntity> staffOf(UUID village, Business b) {
        List<VillageFolkEntity> out = new ArrayList<>(Enterprise.hands(village, b));
        Set<UUID> seen = new HashSet<>();
        for (VillageFolkEntity f : out) seen.add(f.getUUID());
        for (VillageFolkEntity f : Businesses.staff(village, b)) if (seen.add(f.getUUID())) out.add(f);
        return out;
    }

    /** The rota's lines brought up to date with who works there: new hands added (off till rostered), the gone taken off. */
    public static void sync(ServerLevel level, UUID village, Business b, Rota r) {
        int before = r.lines.size();
        Set<UUID> here = new HashSet<>();
        Business.Hours hrs = WorkHours.hoursOf(village, b);
        for (VillageFolkEntity f : staffOf(village, b)) { here.add(f.getUUID()); join(r, hrs, f.getUUID(), f.displayNameCap(), false); }
        for (ServerPlayer p : PlayerHiring.hands(level, "firm:" + b.id)) { here.add(p.getUUID()); join(r, hrs, p.getUUID(), p.getGameProfile().getName(), true); }
        CompoundTag rec = Enterprise.rec(village, b.id);
        Set<UUID> books = new HashSet<>(rec == null ? List.of() : Enterprise.staffOf(rec));
        r.lines.values().removeIf(l -> {
            if (here.contains(l.who)) return false;
            if (l.player) {
                ServerPlayer p = level.getServer().getPlayerList().getPlayer(l.who);
                return p != null && !("firm:" + b.id).equals(WorkLife.employerKey(p));   // offline: kept till they're back
            }
            return !books.contains(l.who);                                                 // unloaded folk on its books: kept
        });
        if (r.lines.size() != before) Businesses.dirty();
    }

    /** A new hand on the rota: the opening hours on its first five open days, marked new till someone rosters it. */
    static void join(Rota r, Business.Hours h, UUID who, String name, boolean player) {
        boolean isNew = !r.lines.containsKey(who);
        Line l = r.line(who, name, player);
        if (!isNew) return;
        l.fresh = true;
        int k = 0;
        for (int d = 0; d < 7; d++) if (h.openOn(d) && k < MOST_DAYS) { l.set(d, preset("full", h)); k++; }
    }

    /** One of the staff, as the filling sees it: who, its liking, the days it can't work and why. */
    record Staff(UUID who, String name, boolean player, int pref, Map<Integer, String> busy, boolean folk) {
        static Staff of(VillageFolkEntity f, UUID village, long today) {
            return new Staff(f.getUUID(), f.displayNameCap(), false, prefHours(f), commitments(f, village, today), true);
        }
    }

    /** The days of the coming week a folk has somewhere to be (a club's meeting, a christening, a funeral), by day of the week. */
    static Map<Integer, String> commitments(VillageFolkEntity f, UUID village, long today) {
        Map<Integer, String> out = new HashMap<>();
        for (Clubs.Club c : Clubs.clubsOf(f)) out.put(c.day.ordinal(), WorkHours.hour(c.from) + "|" + c.title + " " + WorkHours.hour(c.from) + ":00");
        for (int i = 0; i < 7; i++) {
            String e = Church.familyEvent(village, f.getUUID(), today + i);
            if (e != null) out.put(Calendar.dow(today + i), "-1|" + e);     // a family day: the whole day
        }
        return out;
    }

    /** Does a commitment ("hour|words", hour -1 for all day) fall in this shift? */
    static boolean clash(@Nullable String c, int from, int to) {
        if (c == null) return false;
        int hr;
        try { hr = Integer.parseInt(c.substring(0, c.indexOf('|'))); } catch (RuntimeException e) { return true; }
        return hr < 0 || hr >= from && hr < to;
    }

    // ------------------------------------------------------------------ coverage

    /** How many are on, by day of the week and the clock's hour (the usual week, no one-off changes). */
    public static int[][] coverage(Rota r) {
        int[][] c = new int[7][24];
        for (Line l : r.lines.values()) {
            for (int d = 0; d < 7; d++) {
                int[] s = l.day(d);
                for (int h = 0; h < 24; h++) if (at(s, h)) c[d][h]++;
            }
        }
        return c;
    }

    /**
     * How many it wants on each hour it is open: one, and two in its busy hours (read off its own sales by the hour, the
     * last eight weeks; with none yet, a counter's market-day middle) when it has three or more staff.
     */
    public static int[][] need(UUID village, Business b, Business.Hours h, int staff, long today) {
        int[][] n = new int[7][24];
        boolean[][] busy = busyHours(b, h);
        for (int d = 0; d < 7; d++) {
            if (!h.openOn(d)) continue;
            for (int hr = h.open(); hr < h.close() && hr < 24; hr++) n[d][hr] = busy[d][hr] && staff >= 3 ? 2 : 1;
        }
        return n;
    }

    /** Its busy hours, from its sales by the clock hour (a day of the week's hour well above its usual), else market day's middle. */
    public static boolean[][] busyHours(Business b, Business.Hours h) {
        boolean[][] busy = new boolean[7][24];
        double[][] sum = new double[7][24];
        int[] count = new int[7];
        double total = 0;
        int cells = 0;
        for (Business.Day d : b.days) {
            int dow = Calendar.dow(d.day);
            count[dow]++;
            for (int i = 0; i < 24; i++) sum[dow][i] += d.hours[i];
        }
        for (int d = 0; d < 7; d++) {
            if (count[d] == 0) continue;
            for (int i = 0; i < 24; i++) { sum[d][i] /= count[d]; if (sum[d][i] > 0) { total += sum[d][i]; cells++; } }
        }
        if (cells >= 6 && total > 0) {
            double mean = total / cells;
            for (int d = 0; d < 7; d++) for (int i = 0; i < 24; i++) busy[d][i] = sum[d][i] >= Math.max(2, mean * 1.5);
            return busy;
        }
        boolean counter = Business.Hours.forType(b.type).openOn(Calendar.Day.SATURDAY.ordinal());
        if (counter && h.openOn(Calendar.Day.SATURDAY.ordinal())) for (int i = 10; i < 16; i++) busy[Calendar.Day.SATURDAY.ordinal()][i] = true;
        return busy;
    }

    /** A warning on the rota: what, and how bad (2 a caution, 3 a gap). */
    public record Warning(String text, int colour) {}

    /** Gaps, short-handed busy hours, too many on, shifts while it's shut, too few days off, and hands worn down by their hours. */
    public static List<Warning> warnings(UUID village, Business b, Rota r, Business.Hours h) {
        List<Warning> out = new ArrayList<>();
        int[][] c = coverage(r);
        int[][] need = need(village, b, h, r.lines.size(), currentDay() < 0 ? 0 : currentDay());
        for (int d = 0; d < 7; d++) {
            String day = Calendar.Day.values()[d].word;
            runs(c[d], need[d], (x, n) -> n > 0 && x == 0, (a, z) -> out.add(new Warning("No one on " + day + " " + hh(a) + "–" + hh(z) + ".", 3)));
            runs(c[d], need[d], (x, n) -> n > 1 && x == 1, (a, z) -> out.add(new Warning("Only one on " + day + " " + hh(a) + "–" + hh(z) + ": its busy hours want two.", 2)));
            runs(c[d], need[d], (x, n) -> n > 0 && x >= n + 2, (a, z) -> out.add(new Warning("Over-staffed " + day + " " + hh(a) + "–" + hh(z) + ": more on than the trade needs.", 2)));
            runs(c[d], need[d], (x, n) -> n == 0 && x > 0, (a, z) -> out.add(new Warning("Staff rostered " + day + " " + hh(a) + "–" + hh(z) + " while it's shut.", 2)));
        }
        for (Line l : r.lines.values()) {
            if (l.owner) continue;
            if (l.fresh) out.add(new Warning(l.name + " is new on the rota: on the opening hours, five days, till you set their week.", 2));
            if (l.daysWorked() > MOST_DAYS) out.add(new Warning(l.name + " works " + l.daysWorked() + " days: everyone needs two days off a week.", 3));
            if (l.weekHours() > r.overtimeAt) out.add(new Warning(l.name + " is on " + l.weekHours() + " hours: " + (l.weekHours() - r.overtimeAt) + " of them overtime at time and a half.", 2));
            VillageFolkEntity f = Homes.loaded(village, l.who);
            if (f != null) {
                Feeling fe = feeling(f, l.weekHours());
                if (fe.mood() < 0) out.add(new Warning(fe.why() + " (morale " + fe.mood() + ").", 2));
            }
        }
        return out;
    }

    interface Test { boolean ok(int have, int need); }
    interface Run { void run(int from, int to); }

    static void runs(int[] have, int[] need, Test t, Run r) {
        int start = -1;
        for (int h = 0; h <= 24; h++) {
            boolean in = h < 24 && t.ok(have[h], need[h]);
            if (in && start < 0) start = h;
            if (!in && start >= 0) { r.run(start, h); start = -1; }
        }
    }

    // ------------------------------------------------------------------ filling it

    /**
     * Fill the rota: the opening hours covered (a long day in two shifts, morning and afternoon), more on its busy hours,
     * days off spread and two a week for everyone, weekends and evenings shared fairly, a hand's club night and family
     * days kept clear; then the ambitious given extra where it helps. The owner of a folk business keeps its own hours.
     * Returns the gaps it couldn't fill, in words.
     */
    static List<String> fill(Rota r, Business.Hours h, List<Staff> staff, int[][] need, @Nullable UUID ownerHand) {
        List<String> gaps = new ArrayList<>();
        Map<UUID, int[]> week = new LinkedHashMap<>();
        Map<UUID, Integer> hrs = new HashMap<>(), days = new HashMap<>(), weekends = new HashMap<>(), evenings = new HashMap<>();
        for (Staff s : staff) {
            Line l = r.line(s.who(), s.name(), s.player());
            java.util.Arrays.fill(l.h, 0);
            l.fresh = false;
            l.owner = s.who().equals(ownerHand);
            week.put(s.who(), l.h);
            hrs.put(s.who(), 0);
            days.put(s.who(), 0);
            weekends.put(s.who(), 0);
            evenings.put(s.who(), 0);
        }
        int[][] left = new int[7][24];
        for (int d = 0; d < 7; d++) left[d] = need[d].clone();
        if (ownerHand != null && week.containsKey(ownerHand)) {
            for (int d = 0; d < 7; d++) {
                if (!h.openOn(d)) continue;
                // The owner keeps its own hours, and its hands work beside it (it doesn't count against the cover).
                r.lines.get(ownerHand).set(d, new int[]{ h.open(), Math.min(h.close(), h.open() + LONGEST_SHIFT), 0, 0 });
            }
        }
        // The days, the weekend first (the hardest to staff), then the rest.
        int[] order = { 5, 6, 0, 1, 2, 3, 4 };
        for (int d : order) {
            if (!h.openOn(d)) continue;
            int open = h.open(), close = Math.min(24, h.close()), len = close - open;
            List<int[]> blocks = new ArrayList<>();
            if (len <= 9) blocks.add(new int[]{ open, close });
            else { int mid = open + (len + 1) / 2; blocks.add(new int[]{ open, mid }); blocks.add(new int[]{ mid, close }); }
            for (int[] bl : blocks) {
                int want = 0;
                for (int i = bl[0]; i < bl[1]; i++) want = Math.max(want, left[d][i]);
                for (int k = 0; k < want; k++) {
                    Staff best = null;
                    double bestScore = Double.MAX_VALUE;
                    for (Staff s : staff) {
                        if (s.who().equals(ownerHand)) continue;
                        Line l = r.lines.get(s.who());
                        int[] today = l.day(d);
                        if (at(today, bl[0])) continue;                                   // on already
                        if (clash(s.busy().get(d), bl[0], bl[1])) continue;             // somewhere to be: its club, a family day
                        boolean onToday = works(today);
                        if (!onToday && days.get(s.who()) >= MOST_DAYS) continue;        // two days off
                        if (onToday && hours(today) + (bl[1] - bl[0]) > LONGEST_SHIFT) continue;
                        double score = (hrs.get(s.who()) + (bl[1] - bl[0])) / (double) Math.max(8, s.pref());
                        if (onToday) score += 0.35;                                      // a second shift the same day: last
                        if (d >= 5) score += 0.12 * weekends.get(s.who());
                        if (bl[1] >= 19) score += 0.12 * evenings.get(s.who());
                        if (hrs.get(s.who()) + (bl[1] - bl[0]) > s.pref() + 6) score += 1.0;
                        if (score < bestScore) { bestScore = score; best = s; }
                    }
                    if (best == null) {
                        gaps.add(Calendar.Day.values()[d].word + " " + hh(bl[0]) + "–" + hh(bl[1]));
                        break;
                    }
                    Line l = r.lines.get(best.who());
                    int[] today = l.day(d);
                    int[] now;
                    if (!works(today)) now = new int[]{ bl[0], bl[1], 0, 0 };
                    else if (today[1] == bl[0]) now = new int[]{ today[0], bl[1], 0, 0 };   // the morning and the afternoon: a full day
                    else now = new int[]{ today[0], today[1], bl[0], bl[1] };
                    if (!works(today)) days.merge(best.who(), 1, Integer::sum);
                    l.set(d, now);
                    hrs.merge(best.who(), bl[1] - bl[0], Integer::sum);
                    if (d >= 5) weekends.merge(best.who(), 1, Integer::sum);
                    if (bl[1] >= 19) evenings.merge(best.who(), 1, Integer::sum);
                    for (int i = bl[0]; i < bl[1]; i++) if (left[d][i] > 0) left[d][i]--;
                }
            }
        }
        // The ambitious, still well short of the hours they want, given a shift beside another on its busiest open day.
        for (Staff s : staff) {
            if (s.who().equals(ownerHand) || s.pref() < 46) continue;
            Line l = r.lines.get(s.who());
            for (int d : order) {
                if (l.weekHours() >= s.pref() - 6 || l.daysWorked() >= MOST_DAYS) break;
                if (!h.openOn(d) || works(l.day(d)) || s.busy().containsKey(d)) continue;   // its club's day too: kept clear
                int open = h.open(), close = Math.min(24, h.close());
                l.set(d, new int[]{ open, Math.min(close, open + 8), 0, 0 });
            }
        }
        return gaps;
    }

    /** New hands still short of their hours after the gaps: a shift on the thinnest-covered open days, up to their liking. */
    static void topUp(Rota r, Business.Hours h, List<Staff> staff) {
        for (Staff s : staff) {
            Line l = r.lines.get(s.who());
            if (l == null) continue;
            int open = h.open(), close = Math.min(24, h.close()), len = close - open, half = len > 9 ? (len + 1) / 2 : len;
            for (int guard = 0; guard < 7 && l.weekHours() < s.pref() - 4 && l.daysWorked() < MOST_DAYS; guard++) {
                int[][] c = coverage(r);
                int best = -1, bestCov = Integer.MAX_VALUE, bestFrom = open;
                for (int d = 0; d < 7; d++) {
                    if (!h.openOn(d) || works(l.day(d)) || clash(s.busy().get(d), open, close)) continue;
                    for (int from : len > 9 ? new int[]{ open, open + half } : new int[]{ open }) {
                        int sum = 0;
                        for (int i = from; i < Math.min(close, from + half); i++) sum += c[d][i];
                        if (sum < bestCov) { bestCov = sum; best = d; bestFrom = from; }
                    }
                }
                if (best < 0) break;
                l.set(best, new int[]{ bestFrom, Math.min(close, bestFrom + half), 0, 0 });
            }
        }
    }

    /** Fill a player's rota for it: its staff now, its hours, its busy hours. What it says. */
    public static String autoFill(ServerLevel level, UUID village, Business b, String by) {
        Rota r = b.rota != null && !b.rota.auto ? b.rota : new Rota();
        b.rota = r;
        sync(level, village, b, r);
        if (r.lines.isEmpty()) return "!Nobody works there yet: take on staff first (Staff tab).";
        long today = level.getDayTime() / 24000L;
        Business.Hours h = WorkHours.hoursOf(village, b);
        List<Staff> people = new ArrayList<>();
        Map<UUID, VillageFolkEntity> folk = new HashMap<>();
        for (VillageFolkEntity f : staffOf(village, b)) folk.put(f.getUUID(), f);
        for (Line l : r.lines.values()) {
            VillageFolkEntity f = folk.get(l.who);
            people.add(f != null ? Staff.of(f, village, today) : new Staff(l.who, l.name, l.player, FULL_WEEK, Map.of(), false));
        }
        int[][] need = need(village, b, h, people.size(), today);
        List<String> gaps = fill(r, h, people, need, null);
        r.setBy = by;
        r.setDay = today;
        r.auto = false;
        Businesses.dirty();
        pushPlanner(level, village, b);
        b.note(today, "the rota filled by " + by);
        return "Filled the rota for " + b.name + " around its hours (" + h.words() + "): " + people.size() + (people.size() == 1 ? " person" : " people") + ", two days off each."
            + (gaps.isEmpty() ? " Every opening hour is covered." : " Still uncovered: " + String.join(", ", gaps.subList(0, Math.min(3, gaps.size())))
            + (gaps.size() > 3 ? "…" : "") + ". Take on more staff, or shorten its hours.") + " Folk follow it from their next shift.";
    }

    // ------------------------------------------------------------------ editing

    /** Who may change a business's rota: the owner who decides (Partners.canDecide). "" if this player may, else why not. */
    public static String whyNotEdit(UUID village, Business b, UUID player) {
        if (!b.ownedBy(player)) return "Only the owner or manager sets the rota";
        if (!Partners.canDecide(village, b, player)) return "Only the owner or manager sets the rota (" + Partners.whyNot(village, b) + ")";
        return "";
    }

    /** Who may change it, in words: the rota page's line. */
    public static String whoMay(UUID village, Business b) {
        CompoundTag rec = Enterprise.rec(village, b.id);
        String owner = "";
        if (rec != null && rec.hasUUID("managing")) {
            for (Business.Stake s : b.owners) if (rec.getUUID("managing").equals(s.who())) owner = s.name() + " (managing partner)";
        }
        if (owner.isEmpty()) {
            Business.Stake top = null;
            for (Business.Stake s : b.owners) if (top == null || s.parts() > top.parts()) top = s;
            owner = top == null ? "its owner" : top.name() + (b.owners.size() > 1 ? " (largest owner)" : " (owner)");
        }
        String dep = "";
        if (rec != null && Deputy.deputyId(rec) != null && Deputy.on(rec) && Deputy.may(rec, "rota")) dep = "; " + Deputy.name(village, rec) + ", the deputy, while you're away";
        return owner + dep;
    }

    /** Put one day of a line, from the page. */
    public void put(UUID who, int dow, int[] s, String by, long day) {
        Line l = lines.get(who);
        if (l == null) return;
        l.set(dow, s);
        l.fresh = false;
        setBy = by;
        setDay = day;
        auto = false;
    }

    /** Presets for a day, inside its opening hours: morning, afternoon, evening, a full day, a split shift. */
    public static int[] preset(String name, Business.Hours h) {
        int open = h.open(), close = Math.min(24, h.close()), mid = open + (close - open + 1) / 2;
        return switch (name) {
            case "morning" -> new int[]{ open, Math.min(close, Math.max(open + 4, mid)), 0, 0 };
            case "afternoon" -> new int[]{ Math.max(open, Math.min(mid, close - 4)), close, 0, 0 };
            case "evening" -> new int[]{ Math.max(open, close - 4), close, 0, 0 };
            case "full" -> new int[]{ open, Math.min(close, open + LONGEST_SHIFT), 0, 0 };
            case "split" -> new int[]{ open, Math.min(close, open + 4), Math.max(open + 5, close - 4), close };
            default -> new int[4];
        };
    }

    // ------------------------------------------------------------------ requests

    /** A member of staff asks: a day off, or a swap with another. What it says. */
    public static String ask(UUID village, Business b, UUID who, String name, boolean player, long day, String kind, @Nullable UUID with, String withName, String why) {
        Rota r = b.rota;
        if (r == null || !r.active()) return "!" + b.name + " keeps no rota: you work its hours.";
        int[] s = r.slotsOn(who, day);
        if (s == null || !works(s)) return "!You're not rostered on " + Calendar.name(day) + ": nothing to ask for.";
        for (Request q : r.requests) if (q.who.equals(who) && q.day == day && q.state.equals("asked")) return "!You've asked about that day already: the manager hasn't answered yet.";
        Request q = new Request();
        q.id = ++r.seq;
        q.who = who;
        q.name = name;
        q.player = player;
        q.day = day;
        q.kind = kind;
        q.with = with;
        q.withName = withName;
        q.why = why;
        r.requests.add(q);
        while (r.requests.size() > 30) r.requests.remove(0);
        Businesses.dirty();
        net.minecraft.server.MinecraftServer srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (srv != null && !r.auto) {
            for (Business.Stake st : b.owners) {
                ServerPlayer o = st.holder() == Business.Holder.PLAYER && st.who() != null ? srv.getPlayerList().getPlayer(st.who()) : null;
                if (o != null && Partners.canDecide(village, b, o.getUUID())) WorkLife.toast(o, name + " asks for " + (kind.equals("swap") ? "a swap" : "a day off") + " on "
                    + Calendar.name(day) + " at " + b.name + ". See its Rota tab.");
            }
        }
        return kind.equals("swap") ? "Asked for " + withName + " to cover your " + Calendar.name(day) + " shift (" + words(s) + "). The manager decides; you'll hear here."
            : "Asked for " + Calendar.name(day) + " off (" + why + "). The manager decides; till then the shift stands.";
    }

    /** The manager's answer. What it says. */
    public static String answer(@Nullable ServerLevel level, UUID village, Business b, int id, boolean yes, String by) {
        Rota r = b.rota;
        if (r == null) return "!It keeps no rota.";
        Request q = null;
        for (Request x : r.requests) if (x.id == id) q = x;
        if (q == null || !q.state.equals("asked")) return "!That request has been answered already.";
        q.state = yes ? "approved" : "declined";
        if (yes) {
            int[] s = r.slotsOn(q.who, q.day);
            Map<UUID, int[]> o = r.overrides.computeIfAbsent(q.day, k -> new HashMap<>());
            o.put(q.who, new int[4]);
            if (q.kind.equals("swap") && q.with != null && s != null) {
                int[] theirs = r.slotsOn(q.with, q.day);
                o.put(q.with, theirs != null && works(theirs) ? clean(new int[]{ theirs[0], theirs[1], s[0], s[1] }) : s);
            }
            long keepFrom = q.day - 14;
            r.overrides.keySet().removeIf(d -> d < keepFrom);
        }
        Businesses.dirty();
        String what = q.kind.equals("swap") ? q.withName + " covering " + q.name + "'s " + Calendar.name(q.day) : q.name + "'s " + Calendar.name(q.day) + " off";
        if (level != null) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(q.who);
            if (p != null) WorkLife.toast(p, by + (yes ? " approved " : " declined ") + (q.kind.equals("swap") ? "your swap for " : "your day off on ") + Calendar.name(q.day) + ".");
            if (q.with != null && yes) {
                ServerPlayer w = level.getServer().getPlayerList().getPlayer(q.with);
                if (w != null) WorkLife.toast(w, "You're covering " + q.name + "'s shift on " + Calendar.name(q.day) + " at " + b.name + ".");
            }
            pushPlanner(level, village, b);
        }
        return (yes ? "Approved: " : "Declined: ") + what + "." + (yes ? " The rota for that day is changed; the rest of the week stands." : " The shift stands as rostered.");
    }

    /** Folk ask for their club night or a family day off when it falls on a shift (once a request). */
    static void folkAsk(UUID village, Business b, Rota r, long today) {
        for (VillageFolkEntity f : staffOf(village, b)) {
            Line l = r.lines.get(f.getUUID());
            if (l == null || l.owner) continue;
            for (int i = 1; i <= 6; i++) {
                long d = today + i;
                int[] s = r.slotsOn(f.getUUID(), d);
                if (s == null || !works(s)) continue;
                String why = Church.familyEvent(village, f.getUUID(), d);
                if (why == null) {
                    for (Clubs.Club c : Clubs.clubsOf(f)) {
                        if (c.day.ordinal() == Calendar.dow(d) && at(s, WorkHours.hour(c.from))) why = c.title.toLowerCase(Locale.ROOT) + " at " + WorkHours.hour(c.from) + ":00";
                    }
                }
                if (why == null) continue;
                boolean asked = false;
                for (Request q : r.requests) if (q.who.equals(f.getUUID()) && q.day == d) asked = true;
                if (!asked) ask(village, b, f.getUUID(), f.displayNameCap(), false, d, "off", null, "", why);
            }
        }
    }

    /**
     * A folk manager answers its staff's requests by character: the easygoing and the generous say yes; anybody says yes
     * when someone else is on that day; the ambitious say no when it would leave the counter empty.
     */
    static int decideRequests(UUID village, Business b, Rota r, VillageFolkEntity boss, long today) {
        folkAsk(village, b, r, today);
        int n = 0;
        for (Request q : new ArrayList<>(r.requests)) {
            if (!q.state.equals("asked")) continue;
            int[] s = r.slotsOn(q.who, q.day);
            boolean cover = false;
            for (Line l : r.lines.values()) {
                if (l.who.equals(q.who)) continue;
                int[] o = r.slotsOn(l.who, q.day);
                if (o != null && s != null && works(o) && works(s) && (at(o, s[0]) || at(o, Math.max(s[0], s[1] - 1)))) cover = true;
            }
            EnumSet<LifeWeights.Mark> m = LifeWeights.marks(boss);
            boolean yes = q.kind.equals("swap") || cover || m.contains(LifeWeights.Mark.EASYGOING) || m.contains(LifeWeights.Mark.GENEROUS)
                || !m.contains(LifeWeights.Mark.AMBITIOUS) && q.why.contains("funeral");
            answer(null, village, b, q.id, yes, boss.displayNameCap());
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ the deputy

    /**
     * The deputy's turn at the rota while the owner is away (Deputy.day, the "Adjust the rota" task): new hands put on
     * where the hours are short, the gone taken off, the gaps filled where somebody can take them, the staff's requests
     * answered as its character would. What it did, in a line for the report (null for nothing).
     */
    @Nullable
    static String deputyAdjust(ServerLevel level, UUID village, Business b, @Nullable VillageFolkEntity deputy, String name, long day) {
        Rota r = b.rota;
        if (r == null || !r.active() || r.auto) return null;
        int before = r.lines.size();
        sync(level, village, b, r);
        List<String> did = new ArrayList<>();
        if (r.lines.size() != before) did.add((r.lines.size() > before ? "put the new hands on" : "took the gone off"));
        Business.Hours h = WorkHours.hoursOf(village, b);
        List<Staff> fresh = new ArrayList<>();
        Map<UUID, VillageFolkEntity> folk = new HashMap<>();
        for (VillageFolkEntity f : staffOf(village, b)) folk.put(f.getUUID(), f);
        for (Line l : r.lines.values()) if ((l.fresh || l.weekHours() == 0) && !l.player && folk.containsKey(l.who)) fresh.add(Staff.of(folk.get(l.who), village, day));
        if (!fresh.isEmpty()) {
            // Only the new (or idle) hands rostered, into what the rest leave uncovered: the owner's shifts for the rest stand.
            for (Staff s : fresh) java.util.Arrays.fill(r.lines.get(s.who()).h, 0);
            {
                int[][] need = need(village, b, h, r.lines.size(), day);
                int[][] c = coverage(r);
                for (int d = 0; d < 7; d++) for (int i = 0; i < 24; i++) need[d][i] = Math.max(0, need[d][i] - c[d][i]);
                Map<UUID, Line> keep = new LinkedHashMap<>(r.lines);
                fill(r, h, fresh, need, null);
                for (Map.Entry<UUID, Line> e : keep.entrySet()) if (!r.lines.containsKey(e.getKey())) r.lines.put(e.getKey(), e.getValue());
                topUp(r, h, fresh);
                did.add("rostered " + fresh.size() + " new " + (fresh.size() == 1 ? "hand" : "hands") + " where the cover was thinnest");
            }
        }
        if (deputy != null) {
            int n = decideRequests(village, b, r, deputy, day);
            if (n > 0) did.add("answered " + n + (n == 1 ? " request" : " requests") + " for time off");
        }
        if (did.isEmpty()) return null;
        r.setBy = name + " (deputy)";
        r.setDay = day;
        Businesses.dirty();
        pushPlanner(level, village, b);
        return String.join(", ", did);
    }

    // ------------------------------------------------------------------ the clock

    /** A folk at work in its shift (WorkHours.shiftNow): clocked in the first time, out at the latest seen. */
    static void seenAtWork(VillageFolkEntity f, Business b, long dayTime, int[] s) {
        Rota r = b.rota;
        if (r == null) return;
        long day = dayTime / 24000L;
        int minute = (int) (Math.floorMod(dayTime, 24000L) * 60 / 1000);
        int clockMin = (minute + 6 * 60) % (24 * 60);
        punch(r, f.getUUID(), day, clockMin, s, false);
    }

    /** Clock in (or out): the timesheet's line for today. */
    static long[] punch(Rota r, UUID who, long day, int clockMin, int[] s, boolean out) {
        List<long[]> l = r.clock.computeIfAbsent(who, k -> new ArrayList<>());
        long[] t = null;
        for (long[] x : l) if (x[0] == day) t = x;
        if (t == null) {
            if (out) return null;
            int start = s == null || !works(s) ? -1 : s[0];
            int late = start < 0 ? 0 : Math.max(0, clockMin - start * 60);
            t = new long[]{ day, clockMin, clockMin, start, late };
            l.add(t);
            while (l.size() > 14) l.remove(0);
            Businesses.dirty();
        } else if (clockMin > t[2] && (out || clockMin - t[2] >= 30)) {
            t[2] = clockMin;
            if (out) Businesses.dirty();
        }
        return t;
    }

    /** A player hand clocks in or out at the Core. What it says. */
    public static String clock(ServerPlayer p, UUID village, Business b, boolean in) {
        Rota r = b.rota;
        long dt = p.level().getDayTime(), day = dt / 24000L;
        int minute = (int) (Math.floorMod(dt, 24000L) * 60 / 1000), clockMin = (minute + 360) % 1440;
        if (r == null || !r.active()) return "!" + b.name + " keeps no rota: there's nothing to clock against. Your shift is its hours.";
        int[] s = r.slotsOn(p.getUUID(), day);
        if (in) {
            long[] t = punch(r, p.getUUID(), day, clockMin, s, false);
            CompoundTag j = PlayerJobs.job(p);
            if (j.contains("role")) { WorkLife.day(j, day).putBoolean("ci", true); WorkLife.save(p, j); }
            if (s == null || !works(s)) return "Clocked in at " + time(clockMin) + ". You're not rostered today: it counts as extra.";
            return "Clocked in at " + time(clockMin) + " for your " + words(s) + " shift" + (t != null && t[4] > 10 ? ", " + t[4] + " minutes late." : ", on time.")
                + " Clock out at the Core when you're done.";
        }
        long[] t = punch(r, p.getUUID(), day, clockMin, s, true);
        if (t == null) return "!You haven't clocked in today.";
        int worked = (int) (t[2] - t[1]);
        return "Clocked out at " + time(clockMin) + ": " + worked / 60 + "h " + worked % 60 + "m on the timesheet today.";
    }

    public static String time(long clockMin) {
        return String.format(Locale.ROOT, "%02d:%02d", clockMin / 60 % 24, clockMin % 60);
    }

    /** A person's timesheet, newest first, in lines. */
    public static List<String> timesheet(Rota r, UUID who, int most) {
        List<String> out = new ArrayList<>();
        List<long[]> l = r.clock.getOrDefault(who, List.of());
        for (int i = l.size() - 1; i >= 0 && out.size() < most; i--) {
            long[] t = l.get(i);
            out.add(Calendar.name(t[0]).substring(0, 3) + " (day " + (t[0] + 1) + "): in " + time(t[1]) + ", out " + time(t[2])
                + (t[3] >= 0 ? (t[4] > 10 ? ", " + t[4] + " min late" : ", on time") : ", not rostered"));
        }
        return out;
    }

    /** Days late against the rota in the last two weeks. */
    public static int lateDays(Rota r, UUID who) {
        int n = 0;
        for (long[] t : r.clock.getOrDefault(who, List.of())) if (t[3] >= 0 && t[4] > 10) n++;
        return n;
    }

    // ------------------------------------------------------------------ the player's side

    /** A player hand's rota: its employer's, if it keeps one and has a line for it. */
    @Nullable
    public static Rota forHand(ServerPlayer p) {
        CompoundTag j = PlayerJobs.job(p);
        String e = j.getString("employer");
        if (!e.startsWith("firm:") || !j.hasUUID("town")) return null;
        Business b = Businesses.of(j.getUUID("town"), e.substring(5));
        if (b == null || b.rota == null || !b.rota.active() || !b.rota.lines.containsKey(p.getUUID())) return null;
        return b.rota;
    }

    @Nullable
    public static Business employerOf(ServerPlayer p) {
        CompoundTag j = PlayerJobs.job(p);
        String e = j.getString("employer");
        if (!e.startsWith("firm:") || !j.hasUUID("town")) return null;
        return Businesses.of(j.getUUID("town"), e.substring(5));
    }

    /** The coming week's shifts on the player's planner (the old ones from this rota cleared first). */
    static void pushPlanner(ServerLevel level, UUID village, Business b) {
        if (b.rota == null) return;
        long today = level.getDayTime() / 24000L;
        for (ServerPlayer p : PlayerHiring.hands(level, "firm:" + b.id)) {
            Rota r = b.rota;
            if (!r.lines.containsKey(p.getUUID())) continue;
            Planner.clear(p.getUUID(), "rota");
            Villages.Village vv = Villages.get(village);
            BlockPos at = vv == null ? null : BusinessCore.siteOf(level, vv, b);
            for (int i = 0; i < 7; i++) {
                int[] s = r.slotsOn(p.getUUID(), today + i);
                if (s == null || !works(s)) continue;
                Planner.add(p, today + i, s[0], "rota", "Shift at " + b.name + ", " + words(s), at);
            }
        }
    }

    /**
     * WorkLife's clock for a player hand on a rota (every five seconds): the toast ten minutes before a shift, the planner
     * kept up each morning, and online during a rostered shift noted (a shift missed while online counts as absence; offline
     * never does). True if the rota decides the day (WorkLife then leaves its own presence alone).
     */
    static boolean tickHand(ServerLevel level, ServerPlayer p, CompoundTag j, long day, long time) {
        Rota r = forHand(p);
        if (r == null) return false;
        Business b = employerOf(p);
        if (b == null) return false;
        int[] s = r.slotsOn(p.getUUID(), day);
        CompoundTag d = WorkLife.day(j, day);
        if (!d.getBoolean("rp")) {
            d.putBoolean("rp", true);
            WorkLife.save(p, j);
            pushPlanner(level, j.getUUID("town"), b);
        }
        if (s == null || !works(s)) {
            if (!d.getBoolean("roff")) { d.putBoolean("roff", true); WorkLife.save(p, j); }
            return true;
        }
        long startTick = Math.floorMod((s[0] - 6) * 1000L, 24000L);
        if (!d.getBoolean("rt") && time >= startTick - 167 && time < startTick + 200) {
            d.putBoolean("rt", true);
            WorkLife.save(p, j);
            WorkLife.toast(p, "Your shift at " + b.name + " starts at " + hh(s[0]) + " (" + words(s) + "). Clock in at its Business Core.");
        }
        int hr = WorkHours.hour(time);
        if (at(s, hr) && !WorkLife.onLeave(j, day) && !d.getBoolean("ro")) {
            d.putBoolean("ro", true);
            WorkLife.save(p, j);
        }
        return true;
    }

    /** A rostered day missed while online (not worked, not clocked in, no leave): absence, for WorkLife's review. */
    static boolean missedRostered(CompoundTag dayRec) {
        return dayRec.getBoolean("ro") && !dayRec.getBoolean("ci");
    }

    /** Lines for "My week": this player's shifts for the coming days. */
    public static String shiftWords(ServerPlayer p, long day) {
        Rota r = forHand(p);
        if (r == null) return null;
        int[] s = r.slotsOn(p.getUUID(), day);
        return s == null ? null : works(s) ? "Rostered " + words(s) : "Day off (rota)";
    }

    // ------------------------------------------------------------------ templates

    public static String saveTemplate(Rota r, String name) {
        Map<UUID, int[]> m = new LinkedHashMap<>();
        for (Line l : r.lines.values()) m.put(l.who, l.h.clone());
        r.templates.put(name, m);
        Businesses.dirty();
        return name.equals("busy") ? "Saved this week as the busy week: it's used on the fair's week" + (r.busyOnFair ? "." : " once you switch that on.")
            : "Saved this week as the usual week. Load it any time to put the rota back.";
    }

    public static String loadTemplate(Rota r, String name, String by, long day) {
        Map<UUID, int[]> m = r.templates.get(name);
        if (m == null) return "!No " + name + " week saved yet: set the rota, then Save as " + name + " week.";
        int n = 0;
        for (Line l : r.lines.values()) {
            int[] a = m.get(l.who);
            if (a == null) continue;
            System.arraycopy(a, 0, l.h, 0, 28);
            l.fresh = false;
            n++;
        }
        r.setBy = by;
        r.setDay = day;
        Businesses.dirty();
        return "Loaded the " + name + " week for " + n + (n == 1 ? " person" : " people") + (n < r.lines.size() ? "; the rest (new since it was saved) keep theirs." : ".");
    }

    // ------------------------------------------------------------------ the public face

    /** "Who's on today": each on the rota today with its hours, by its first hour. Empty without a rota. */
    public static List<String> onToday(UUID village, Business b, long day) {
        Rota r = of(village, b);
        List<String> out = new ArrayList<>();
        if (r == null) return out;
        List<Object[]> rows = new ArrayList<>();
        for (Line l : r.lines.values()) {
            int[] s = r.slotsOn(l.who, day);
            if (s != null && works(s)) rows.add(new Object[]{ s[0], l.name + " " + words(s) + (l.owner ? " (owner)" : "") });
        }
        rows.sort(Comparator.comparingInt(o -> (Integer) o[0]));
        for (Object[] o : rows) out.add((String) o[1]);
        return out;
    }

    /** "Who's on today", as lines (for their faces), by their first hour. */
    public static List<Line> onTodayLines(UUID village, Business b, long day) {
        Rota r = of(village, b);
        List<Line> out = new ArrayList<>();
        if (r == null) return out;
        for (Line l : r.lines.values()) {
            int[] s = r.slotsOn(l.who, day);
            if (s != null && works(s)) out.add(l);
        }
        out.sort(Comparator.comparingInt(l -> r.slotsOn(l.who, day)[0]));
        return out;
    }

    public static void resetForTests() {
        dayForTests = -1;
    }

    /** Tests: the business's own rota (a player's), made if need be, with its staff on it (all days off). */
    public static Rota ensureForTests(ServerLevel level, UUID village, Business b) {
        if (b.rota == null || b.rota.auto) b.rota = new Rota();
        sync(level, village, b, b.rota);
        for (Line l : b.rota.lines.values()) { java.util.Arrays.fill(l.h, 0); l.fresh = false; }
        return b.rota;
    }

    /** Tests: WorkLife's five-second look at a player hand on a rota. */
    public static boolean tickHandForTests(ServerLevel level, ServerPlayer p) {
        CompoundTag j = PlayerJobs.job(p);
        long dt = level.getDayTime();
        boolean b = tickHand(level, p, j, dt / 24000L, dt % 24000L);
        return b;
    }

    /** Tests: the planner's shifts put up now. */
    public static void plannerForTests(ServerLevel level, UUID village, Business b) {
        pushPlanner(level, village, b);
    }

    /** Tests: the deputy's turn at the rota. */
    @Nullable
    public static String deputyForTests(ServerLevel level, UUID village, Business b, @Nullable VillageFolkEntity d, long day) {
        return deputyAdjust(level, village, b, d, d == null ? "the deputy" : d.displayNameCap(), day);
    }
}
