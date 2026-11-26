package com.jrpetty.mcassistant.entity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featlife] How a folk's character tells in its daily life, every weight in one place: its work (when it turns up, how
 * long it stays, whether it takes extra work and hunts promotion), its money (what it puts by and what it spends, and on
 * what), its company (whether it calls on friends, asks them to dinner, joins a club), its weekends (where it wanders and
 * how it rests) and its Sundays (whether it goes to church).
 *
 * <p>The character is read off what the folk already is, never rolled here: its business marks (Temperament.Mark: the
 * same ambition that opens firms, the hardworking, the easygoing, the thrifty, the spendthrift), its nature (Social.Trait),
 * its quirks (Quirks), what it cares about (Values: the old ways first makes it devout) and its hobby (Persona.Hobby).
 * Whatever a folk chooses by these says why in a word ({@link #why}), and LifeLog keeps the line for its card:
 * "Spent the afternoon at the tavern (easygoing)."
 */
public final class LifeWeights {

    private LifeWeights() {}

    /** What tells in daily life, a mark each. */
    public enum Mark {
        AMBITIOUS("ambitious"), HARDWORKING("hardworking"), EASYGOING("easygoing"), THRIFTY("thrifty"),
        SPENDTHRIFT("spendthrift"), CHATTY("chatty"), SHY("shy"), CHEERFUL("cheerful"), GRUMPY("grumpy"),
        GENEROUS("generous"), CURIOUS("curious"), DEVOUT("devout"), MUSICAL("musical"), SPORTY("sporty"),
        ANGLER("an angler"), BOOKISH("bookish"), HOMEBODY("a homebody"), WANDERER("restless");

        public final String word;

        Mark(String word) {
            this.word = word;
        }
    }

    // ------------------------------------------------------------------ the weights

    /** Work: hours late an easygoing hand turns up; the extra hour the hardworking and the ambitious stay. */
    public static final int LATE_HOURS_EASYGOING = 1;
    /** Work: an ambitious hand takes the Saturday morning's work its employer offers (till noon). */
    public static final int OVERTIME_UNTIL_HOUR = 12;
    /** Owners: the hours a folk owner keeps, by its character (see {@link #ownerHours}). */
    public static final int OWNER_LONG_CLOSE = 20, OWNER_SHORT_OPEN = 8, OWNER_SHORT_CLOSE = 16;

    /** Money: the share of its purse a folk spends on itself of a weekend, by mark (added to {@link #SPEND_BASE}). */
    public static final double SPEND_BASE = 0.20, SPEND_THRIFTY = -0.14, SPEND_SPENDTHRIFT = 0.25, SPEND_GENEROUS = 0.05,
        SPEND_CHEERFUL = 0.03, SPEND_GRUMPY = -0.05;
    /** Money: the coins it always keeps back, by mark. */
    public static final int KEEP_BASE = 3, KEEP_THRIFTY = 8, KEEP_SPENDTHRIFT = 0;

    /** Weekends: how much each kind of outing appeals, before its marks (WeekendLife.Outing). */
    public static final double W_MARKET = 3, W_SHOP = 2, W_TAVERN = 2, W_PARK = 2, W_FRIEND = 2, W_HOME = 1.5, W_FISH = 0, W_SELL = 0;

    /** Church: the chance it goes to the Sunday service, by mark (added to {@link #CHURCH_BASE}). */
    public static final double CHURCH_BASE = 0.45, CHURCH_DEVOUT = 0.50, CHURCH_GRUMPY = -0.15, CHURCH_CURIOUS = -0.05,
        CHURCH_EASYGOING = -0.10;

    /** Company: the chance a day off it calls on a friend or neighbour, by mark. */
    public static final double CALL_BASE = 0.25, CALL_CHATTY = 0.35, CALL_SHY = -0.18, CALL_CHEERFUL = 0.08, CALL_GRUMPY = -0.10;

    /** Clubs: a mark's pull toward each club (Clubs.Club.pull reads these). */
    public static final double CLUB_PULL_STRONG = 3, CLUB_PULL = 1.5, CLUB_PUSH = -2, CLUB_JOIN_AT = 2.5;

    // ------------------------------------------------------------------ a folk's marks

    private static final Map<UUID, Object[]> CACHE = new ConcurrentHashMap<>();

    /** Tests: a folk's marks set by hand. */
    private static final Map<UUID, EnumSet<Mark>> FORCED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        CACHE.clear();
        FORCED.clear();
    }

    public static void setForTests(VillageFolkEntity f, Mark... ms) {
        EnumSet<Mark> m = EnumSet.noneOf(Mark.class);
        for (Mark k : ms) m.add(k);
        FORCED.put(f.getUUID(), m);
        CACHE.remove(f.getUUID());
    }

    /** Its marks, read off what it is now (kept a minute). */
    public static EnumSet<Mark> marks(VillageFolkEntity f) {
        EnumSet<Mark> forced = FORCED.get(f.getUUID());
        if (forced != null) return forced;
        long now = f.level().getGameTime();
        Object[] c = CACHE.get(f.getUUID());
        if (c != null && now - (Long) c[0] < 1200 && now >= (Long) c[0]) {
            @SuppressWarnings("unchecked") EnumSet<Mark> m = (EnumSet<Mark>) c[1];
            return m;
        }
        EnumSet<Mark> m = read(f);
        if (CACHE.size() > 4096) CACHE.clear();
        CACHE.put(f.getUUID(), new Object[]{ now, m });
        return m;
    }

    static EnumSet<Mark> read(VillageFolkEntity f) {
        EnumSet<Mark> m = EnumSet.noneOf(Mark.class);
        if (f.isBaby()) return m;
        EnumSet<Temperament.Mark> t = Temperament.marks(f);
        if (t.contains(Temperament.Mark.AMBITIOUS)) m.add(Mark.AMBITIOUS);
        if (t.contains(Temperament.Mark.HARDWORKING)) m.add(Mark.HARDWORKING);
        if (t.contains(Temperament.Mark.EASYGOING)) m.add(Mark.EASYGOING);
        if (t.contains(Temperament.Mark.THRIFTY)) m.add(Mark.THRIFTY);
        if (t.contains(Temperament.Mark.SPENDTHRIFT)) m.add(Mark.SPENDTHRIFT);
        Social.Life life = f.life();
        if (life.has(Social.Trait.SOCIABLE) || Quirks.has(f, Quirks.Quirk.SMOOTH_TALKER)) m.add(Mark.CHATTY);
        if (life.has(Social.Trait.SHY)) m.add(Mark.SHY);
        if (life.has(Social.Trait.CHEERFUL)) m.add(Mark.CHEERFUL);
        if (life.has(Social.Trait.GRUMPY)) m.add(Mark.GRUMPY);
        if (life.has(Social.Trait.GENEROUS)) m.add(Mark.GENEROUS);
        if (life.has(Social.Trait.CURIOUS)) m.add(Mark.CURIOUS);
        if (f.persona().rolled()) {
            Values.Value top = Values.top(f), second = Values.second(f);
            if (top == Values.Value.TRADITION || second == Values.Value.TRADITION && !life.has(Social.Trait.CURIOUS)) m.add(Mark.DEVOUT);
            Persona.Hobby h = f.persona().hobby();
            if (h == Persona.Hobby.FISHING) m.add(Mark.ANGLER);
            if (h == Persona.Hobby.MUSIC) m.add(Mark.MUSICAL);
            if (h == Persona.Hobby.READING) m.add(Mark.BOOKISH);
            if (h == Persona.Hobby.WALKING) m.add(Mark.WANDERER);
        }
        if (Quirks.has(f, Quirks.Quirk.MUSICAL)) m.add(Mark.MUSICAL);
        if (Quirks.has(f, Quirks.Quirk.BOOKWORM)) m.add(Mark.BOOKISH);
        if (Quirks.has(f, Quirks.Quirk.FLEET_FOOTED) || Quirks.has(f, Quirks.Quirk.BROAD_SHOULDERS) || Quirks.has(f, Quirks.Quirk.HARDY)) m.add(Mark.SPORTY);
        if (Quirks.has(f, Quirks.Quirk.HOMEBODY)) m.add(Mark.HOMEBODY);
        if (Quirks.has(f, Quirks.Quirk.WANDERLUST)) m.add(Mark.WANDERER);
        return m;
    }

    public static boolean has(VillageFolkEntity f, Mark k) {
        return marks(f).contains(k);
    }

    /** The first of these marks it has, in a word, for a reason in brackets ("easygoing"); "" if none. */
    public static String why(VillageFolkEntity f, Mark... ks) {
        EnumSet<Mark> m = marks(f);
        for (Mark k : ks) if (m.contains(k)) return k.word;
        return "";
    }

    /** Its marks in words, for its card: "ambitious, chatty, devout". */
    public static String words(VillageFolkEntity f) {
        List<String> out = new ArrayList<>();
        for (Mark k : marks(f)) out.add(k.word);
        return out.isEmpty() ? "steady" : String.join(", ", out);
    }

    // ------------------------------------------------------------------ work

    /**
     * The hours a folk owner keeps its business, by its character: the ambitious long (open early, shut late, six days, a
     * shop every day); the hardworking six days; the easygoing fewer (four days, or shorter ones); anybody else the type's.
     */
    public static Business.Hours ownerHours(VillageFolkEntity owner, Business.Type type) {
        Business.Hours base = Business.Hours.forType(type);
        EnumSet<Mark> m = marks(owner);
        String name = owner.displayNameCap();
        boolean counter = base.openOn(Calendar.Day.SATURDAY.ordinal());
        if (m.contains(Mark.AMBITIOUS)) {
            int days = counter ? Business.Hours.ALL_WEEK : Business.Hours.SIX_DAYS;
            return new Business.Hours(days, Math.min(base.open(), 6), Math.max(base.close(), OWNER_LONG_CLOSE),
                "set by " + name + " (ambitious): long hours");
        }
        if (m.contains(Mark.HARDWORKING)) {
            return new Business.Hours(Business.Hours.SIX_DAYS, base.open(), base.close(), "set by " + name + " (hardworking): six days");
        }
        if (m.contains(Mark.EASYGOING)) {
            return counter
                ? new Business.Hours(base.days(), Math.max(base.open(), OWNER_SHORT_OPEN), Math.min(base.close(), OWNER_SHORT_CLOSE),
                    "set by " + name + " (easygoing): short days")
                : new Business.Hours(Business.Hours.MON_THU, Math.max(base.open(), 7), Math.min(base.close(), 17),
                    "set by " + name + " (easygoing): four days");
        }
        return base.withWhy("set by " + name + ": the usual hours");
    }

    /** Hours late an employee turns up to work (its character). */
    public static int lateHours(VillageFolkEntity f) {
        EnumSet<Mark> m = marks(f);
        return m.contains(Mark.EASYGOING) && !m.contains(Mark.AMBITIOUS) ? LATE_HOURS_EASYGOING : 0;
    }

    /** Does it take extra work on a day its employer offers it (Saturday morning)? The ambitious do; so do the hardworking poor. */
    public static boolean takesOvertime(VillageFolkEntity f) {
        EnumSet<Mark> m = marks(f);
        return m.contains(Mark.AMBITIOUS) || m.contains(Mark.HARDWORKING) && f.purse() < 10;
    }

    // ------------------------------------------------------------------ money

    /** The share of its purse it will spend on itself today. */
    public static double spendShare(VillageFolkEntity f) {
        EnumSet<Mark> m = marks(f);
        double s = SPEND_BASE;
        if (m.contains(Mark.THRIFTY)) s += SPEND_THRIFTY;
        if (m.contains(Mark.SPENDTHRIFT)) s += SPEND_SPENDTHRIFT;
        if (m.contains(Mark.GENEROUS)) s += SPEND_GENEROUS;
        if (m.contains(Mark.CHEERFUL)) s += SPEND_CHEERFUL;
        if (m.contains(Mark.GRUMPY)) s += SPEND_GRUMPY;
        return Math.max(0.03, Math.min(0.7, s));
    }

    /** The coins it keeps back whatever it sees. */
    public static int keepBack(VillageFolkEntity f) {
        EnumSet<Mark> m = marks(f);
        return m.contains(Mark.THRIFTY) ? KEEP_THRIFTY : m.contains(Mark.SPENDTHRIFT) ? KEEP_SPENDTHRIFT : KEEP_BASE;
    }

    /** What it may spend on itself today, in coins. */
    public static int budget(VillageFolkEntity f) {
        int spare = f.purse() - keepBack(f);
        if (spare <= 0) return 0;
        return Math.max(1, (int) Math.floor(spare * spendShare(f)));
    }

    /** Its reason for spending (or not): "thrifty", "spendthrift"... */
    public static String moneyWhy(VillageFolkEntity f) {
        return why(f, Mark.THRIFTY, Mark.SPENDTHRIFT, Mark.GENEROUS, Mark.GRUMPY, Mark.CHEERFUL);
    }

    // ------------------------------------------------------------------ company and church

    /** The chance a day off it calls on a friend or a neighbour. */
    public static double callChance(VillageFolkEntity f) {
        EnumSet<Mark> m = marks(f);
        double c = CALL_BASE;
        if (m.contains(Mark.CHATTY)) c += CALL_CHATTY;
        if (m.contains(Mark.SHY)) c += CALL_SHY;
        if (m.contains(Mark.CHEERFUL)) c += CALL_CHEERFUL;
        if (m.contains(Mark.GRUMPY)) c += CALL_GRUMPY;
        return Math.max(0.02, Math.min(0.9, c));
    }

    /** The chance it goes to the Sunday service. */
    public static double churchChance(VillageFolkEntity f) {
        EnumSet<Mark> m = marks(f);
        double c = CHURCH_BASE;
        if (m.contains(Mark.DEVOUT)) c += CHURCH_DEVOUT;
        if (m.contains(Mark.GRUMPY)) c += CHURCH_GRUMPY;
        if (m.contains(Mark.CURIOUS)) c += CHURCH_CURIOUS;
        if (m.contains(Mark.EASYGOING)) c += CHURCH_EASYGOING;
        return Math.max(0.05, Math.min(1.0, c));
    }

    /** Its own fixed draw for a thing on a day, 0 to 1: the same all day, different the next. */
    public static double roll(VillageFolkEntity f, long day, int salt) {
        long h = f.getUUID().getLeastSignificantBits() * 0x9E3779B97F4A7C15L ^ (day * 31L + salt) * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 32;
        return (h >>> 11) / (double) (1L << 53);
    }

    @Nullable
    static String bracket(String why) {
        return why == null || why.isEmpty() ? null : why;
    }
}
