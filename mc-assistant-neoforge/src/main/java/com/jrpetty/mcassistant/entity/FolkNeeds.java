package com.jrpetty.mcassistant.entity;

import net.minecraft.world.level.Level;

/**
 * [folk-ui] A folk's four needs, each 0 (desperate) to 100 (fully met), read off what really happened to it rather
 * than kept as numbers of their own: <b>food</b> (its meals, Meals.Book), <b>rest</b> (whether it slept in a bed last
 * night, and how long it has been up), <b>social</b> (its partner, its friends, and when it last had company) and
 * <b>comfort</b> (a bed and a house of its own, the comforts in it, its health, the weather it is out in).
 *
 * <p>They drive what it does: a folk short of rest goes to bed early (VillageFolkEntity.bedtimeTick), a lonely one
 * spends its evening looking for company before anything it would do on its own, and one short of comfort spends its
 * savings on its home first (VillageFolkEntity.socialise); hunger already sends it to the stores (Meals). They show as
 * bars on its profile, and the lowest of them is often what it is thinking about (FolkThought).
 *
 * <p>Cost: worked out at most once every {@link #FRESH_TICKS} per folk and kept on the folk (four ints, no
 * allocation); everything read is a field or a map lookup, no world scans.
 */
public final class FolkNeeds {

    private FolkNeeds() {}

    public static final int FOOD = 0, REST = 1, SOCIAL = 2, COMFORT = 3, COUNT = 4;
    /** The needs' names, in index order, as the profile shows them. */
    public static final String[] NAMES = { "Food", "Rest", "Company", "Comfort" };
    /** Below this a need is pressing: it changes what the folk does, and it says so. */
    public static final int LOW = 35;
    /** How long a worked-out set of needs is good for: five seconds (they move over hours, not ticks). */
    static final int FRESH_TICKS = 100;

    /** Food: what each meal missed in a row costs, and what each hour since it last ate costs. */
    private static final int MISSED_MEAL = 30, HUNGER_PER_HOUR = 3;
    /** Rest: a night in a bed, a night without, and what a whole day awake costs. */
    private static final int SLEPT = 95, NO_BED_NIGHT = 45, AWAKE_DAY = 50;
    /** Company: how long a chat keeps it company, in ticks (an hour), and how long after that it fades to nothing. */
    private static final long COMPANY_FULL = 1000L, COMPANY_FADE = 6000L;

    /** Its four needs now: kept on the folk and worked out afresh at most every five seconds. Never null. */
    public static int[] of(VillageFolkEntity f) {
        int[] kept = f.needsKept();
        long now = f.level().getGameTime();
        if (now - f.needsAt() >= FRESH_TICKS || now < f.needsAt()) {
            work(f, kept, now);
            f.needsAt(now);
        }
        return kept;
    }

    /** One need now (FOOD, REST, SOCIAL or COMFORT). */
    public static int get(VillageFolkEntity f, int need) {
        return of(f)[need];
    }

    /** Is this need pressing? */
    public static boolean low(VillageFolkEntity f, int need) {
        return get(f, need) < LOW;
    }

    /** The lowest need's index, or -1 when none is pressing. */
    public static int pressing(VillageFolkEntity f) {
        int[] n = of(f);
        int worst = -1;
        for (int i = 0; i < COUNT; i++) if (n[i] < LOW && (worst < 0 || n[i] < n[worst])) worst = i;
        return worst;
    }

    /** Tests: its history as the needs read it: the day it came, the night it last slept in a bed, meals missed in a row,
     *  when it last ate (game time) and when it last had company (game time, -1 never). */
    public static void historyForTests(VillageFolkEntity f, long joined, long slept, int missed, long lastAte, long company) {
        f.persona().since = joined;
        f.persona().sleptDay = slept;
        f.meals().missedInRow = missed;
        f.meals().lastAte = lastAte;
        f.companyAt(company);
        f.needsAt(Long.MIN_VALUE / 2);
    }

    /** Tests: work them out now, whatever was kept. */
    public static int[] nowForTests(VillageFolkEntity f) {
        f.needsAt(Long.MIN_VALUE / 2);
        return of(f);
    }

    private static void work(VillageFolkEntity f, int[] out, long gameTime) {
        Level level = f.level();
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        out[FOOD] = food(f, gameTime);
        out[REST] = rest(f, day, t);
        out[SOCIAL] = social(f, gameTime);
        out[COMFORT] = comfort(f);
    }

    static int food(VillageFolkEntity f, long gameTime) {
        Meals.Book b = f.meals();
        if (b.lastAte < 0 && b.missedInRow() == 0) return 70;          // new to the town: nothing eaten here yet, nothing missed
        long hours = Math.max(0, gameTime - b.lastAte) / 1000L;
        return clamp(100 - MISSED_MEAL * b.missedInRow() - (int) Math.min(100, hours * HUNGER_PER_HOUR));
    }

    static int rest(VillageFolkEntity f, long day, long t) {
        if (Health.laidUp(f)) return 20;                                 // ill in bed: worn out, whatever the hour
        if (f.isSleeping()) return clamp(SLEPT - (int) (t >= 12000L ? 0 : AWAKE_DAY * t / 24000L) / 2);
        Persona p = f.persona();
        boolean slept = p.sleptDay >= day - 1 || p.since() < 0 || day <= p.since() + 1;
        int base = slept ? SLEPT : NO_BED_NIGHT;
        return clamp(base - (int) (AWAKE_DAY * t / 24000L));
    }

    static int social(VillageFolkEntity f, long gameTime) {
        Social.Life life = f.life();
        int v = 15;
        if (life.partner() != null) v += 25;
        v += Math.min(30, 10 * life.friends().size());
        long since = gameTime - f.companyAt();
        if (f.companyAt() >= 0 && since >= 0) {
            if (since <= COMPANY_FULL) v += 30;
            else if (since < COMPANY_FULL + COMPANY_FADE) v += (int) (30 * (COMPANY_FULL + COMPANY_FADE - since) / COMPANY_FADE);
        }
        if (life.has(Social.Trait.SHY)) v += 10;                          // the shy need less of it
        if (life.has(Social.Trait.SOCIABLE)) v -= 10;                     // the sociable more
        return clamp(v);
    }

    static int comfort(VillageFolkEntity f) {
        int v = 20;
        if (f.bedPos() != null) v += 25;
        if (f.ownerId() != null && !Homes.lodging(f) && Homes.homeOf(f.ownerId(), f.getUUID()) != null) v += 20;
        v += Math.min(15, 5 * f.comforts());
        v += Math.min(10, 2 * Math.max(0, Decor.moodBonus(f)));
        if (Health.laidUp(f) || !Health.illWord(f).isEmpty()) v -= 30;
        if (f.level().isRainingAt(f.blockPosition())) v -= 10;           // out in the rain
        return clamp(v);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }
}
