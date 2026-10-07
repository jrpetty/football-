package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Two meals a day for every folk: the midday meal and supper. (There were three, with breakfast at
 * dawn; a town eats a third less without it, and its folk are content on two.)
 *
 * <p>A hand at its work has always eaten a ration every couple of minutes of labour (AssistantEntity's
 * upkeep), but that was the only eating there was: a child, an elder past work, the leader, a folk with
 * no trade yet, and every hand off its shift or on the rest day never ate at all, and the town's books
 * counted the food that left the stores as "eaten" whoever had it. Now each folk keeps its own meals:
 * at each mealtime it eats, from its own pack first, then its household's chest at home, then the
 * village's stores (the town feeds its own: a child, an elder and a hand between trades as much as a
 * worker). A meal eaten at its work while the mealtime is on counts as that meal, so nobody eats twice.
 * Supper is a household's (Families): one that lives with its family eats it at home, out of the
 * household's chest first, and the meal waits for it to get there while there is time to.
 *
 * <p>Nothing comes from nowhere: every meal is a real item out of a pack, a chest or the stores, and the
 * stores' share is booked in the storehouse's books. A folk that finds nothing to eat misses the meal:
 * it is hungry (its contentment falls, more with every meal missed, and it says so); a whole day missed
 * and it works poorly; two days and it grows weak (it loses health, never past three hearts: the
 * village is meant to see to it before that). The town's books show the meals eaten and missed each
 * day, and a folk's card when it last ate and what.
 */
public final class Meals {

    private Meals() {}

    /** The two meals, and the hours of the day (day time, 0 at six in the morning) each is eaten in. */
    public enum Meal {
        LUNCH("the midday meal", 5400, 7800),
        SUPPER("supper", 11000, 13400);

        public final String label;
        final int from, to;

        Meal(String label, int from, int to) {
            this.label = label;
            this.from = from;
            this.to = to;
        }

        /** The meal whose time this is, or null between meals. */
        @Nullable
        static Meal at(int timeOfDay) {
            for (Meal m : values()) if (timeOfDay >= m.from && timeOfDay < m.to) return m;
            return null;
        }
    }

    /** What a folk eats at a meal: food, but nothing that would poison it (WithdrawGoal's "ration"). */
    static final Predicate<ItemStack> FOOD = com.jrpetty.mcassistant.entity.goal.WithdrawGoal.matcherFor("ration");

    /** How far from its house a folk will go home to eat out of the household's chest. */
    static final int HOME_REACH = 64;
    /** How far from the stores a hand at its work will walk in to eat out of them (a far one carries rations). */
    static final int STORES_REACH = 64;
    /** Meals missed in a row before a folk works poorly (a whole day), and before it grows weak (two days). */
    static final int POOR_AFTER = 2, WEAK_AFTER = 4;

    // ------------------------------------------------------------------ one folk's meals

    /** A folk's meals, kept on it and saved with it (VillageFolkEntity "Meals"). */
    public static final class Book {
        long day = -1;
        int taken;                  // the meals of the day eaten or past, one bit each
        int eaten;                  // eaten today
        int missedInRow;            // meals missed one after another
        long lastAte = -100000L;    // game time of the last thing eaten, meal or ration
        String lastWhat = "";
        int lastMeal = -1;          // which meal it was, or -1 a ration at its work

        public int missedInRow() { return missedInRow; }
        public int eatenToday() { return eaten; }
        public String lastWhat() { return lastWhat; }

        /** Anything eaten, at a meal or at its work: the meal on now counts as eaten if this falls in it. */
        public void ate(long gameTime, String what) {
            lastAte = gameTime;
            lastWhat = what;
        }

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("Day", day);
            t.putInt("Taken", taken);
            t.putInt("Eaten", eaten);
            t.putInt("Missed", missedInRow);
            t.putLong("LastAte", lastAte);
            t.putString("LastWhat", lastWhat);
            t.putInt("LastMeal", lastMeal);
            return t;
        }

        public void load(CompoundTag t) {
            day = t.getLong("Day");
            taken = t.getInt("Taken");
            eaten = t.getInt("Eaten");
            missedInRow = t.getInt("Missed");
            lastAte = t.contains("LastAte") ? t.getLong("LastAte") : -100000L;
            lastWhat = t.getString("LastWhat");
            lastMeal = t.contains("LastMeal") ? t.getInt("LastMeal") : -1;
        }
    }

    // ------------------------------------------------------------------ the town's books

    /** Each village's meals today and yesterday: {eaten, missed, folk who missed one}. */
    private static final Map<UUID, long[]> TODAY = new HashMap<>();
    private static final Map<UUID, int[]> YESTERDAY = new HashMap<>();
    private static final Map<UUID, java.util.Set<UUID>> MISSED_BY = new HashMap<>();

    private static void book(UUID village, long day, boolean ate, UUID who) {
        long[] t = TODAY.computeIfAbsent(village, k -> new long[]{ day, 0, 0 });
        if (t[0] != day) {
            int folk = MISSED_BY.getOrDefault(village, java.util.Set.of()).size();
            YESTERDAY.put(village, new int[]{ (int) t[1], (int) t[2], folk });
            MISSED_BY.remove(village);
            t[0] = day;
            t[1] = 0;
            t[2] = 0;
        }
        if (ate) t[1]++;
        else {
            t[2]++;
            MISSED_BY.computeIfAbsent(village, k -> new java.util.HashSet<>()).add(who);
        }
    }

    /** Yesterday's meals in this village: {eaten, missed, folk who missed any}, or null before a day has closed. */
    @Nullable
    public static int[] yesterday(UUID village) {
        return YESTERDAY.get(village);
    }

    /** Today's so far: {eaten, missed}. */
    public static int[] today(UUID village) {
        long[] t = TODAY.get(village);
        return t == null ? new int[]{ 0, 0 } : new int[]{ (int) t[1], (int) t[2] };
    }

    public static void resetForTests() {
        TODAY.clear();
        YESTERDAY.clear();
        MISSED_BY.clear();
    }

    // ------------------------------------------------------------------ mealtimes

    /** A look at the clock, every few seconds: if it is a mealtime and this meal is not had, it is eaten now. */
    /** Tests: what, if anything, holds this folk's meal back at this moment (the crier's news, the noon bell,
     *  the meal already taken), or "" when nothing does. */
    public static String heldForTests(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return "no village";
        long time = f.level().getDayTime();
        Meal m = Meal.at((int) (time % 24000L));
        if (m == null) return "no mealtime";
        if (Crier.busy(f)) return "crying the news";
        if (m == Meal.LUNCH && TownBell.lunchWaits(village, time)) return "waiting for the noon bell";
        Book b = f.meals();
        if (b.day == time / 24000L && (b.taken & (1 << m.ordinal())) != 0) return "taken already";
        return "";
    }

    public static void tick(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level) || !f.isAlive()) return;
        UUID village = f.ownerId();
        if (village == null || f.showcaseFolk()) return;
        Book b = f.meals();
        long time = level.getDayTime(), day = time / 24000L;
        int tod = (int) (time % 24000L);
        if (b.day != day) {
            b.day = day;
            b.taken = 0;
            b.eaten = 0;
        }
        Meal m = Meal.at(tod);
        if (m == null) return;
        // The crier reading the noon news has its meal after (Crier): with nothing in its pack it was sent in
        // to the stores for food, and read the rest of the news twenty blocks off the square.
        if (Crier.busy(f)) return;
        // A town that keeps the bell sits down to its midday meal at the noon bell (TownBell).
        if (m == Meal.LUNCH && TownBell.lunchWaits(village, time)) return;
        int bit = 1 << m.ordinal();
        if ((b.taken & bit) != 0) return;
        // A ration eaten at its work since this mealtime began is this meal.
        long began = level.getGameTime() - (tod - m.from);
        if (b.lastAte >= began) {
            had(f, b, m, bit, village, day);
            return;
        }
        // [families] Supper is the household's: eaten at home round its own table, out of its own chest first
        // (Families). On its way home, or still at its work, the meal waits for the table while there is time.
        if (m == Meal.SUPPER) {
            Families.Table table = Families.table(level, f, village, tod, m.to);
            if (table == Families.Table.WAIT) return;
            if (table == Families.Table.HOME && eatAtHome(level, f, village)) {
                had(f, b, m, bit, village, day);
                Families.supped(f, day);
                return;
            }
        }
        if (eat(level, f, village)) {
            had(f, b, m, bit, village, day);
            return;
        }
        PackedLunch.sendFor(f, day * 4 + m.ordinal());      // [economy] food sent for, or walked in for, once a meal
        // Nothing to eat yet: it keeps looking while the mealtime lasts, and only at its end is the meal missed.
        if (tod + 100 < m.to) return;
        b.taken |= bit;
        b.missedInRow++;
        book(village, day, false, f.getUUID());
        f.brain("missed " + m.label + ": nothing to eat in reach");
        if (b.missedInRow >= WEAK_AFTER) {
            // Two days without: it grows weak, but the village is meant to see to it before it comes to more.
            if (f.getHealth() > 6.0F) f.hurt(f.damageSources().starve(), 1.0F);
            FolkTalk.speak(f, "I've not eaten in two days. I can hardly stand.");
        } else if (b.missedInRow >= POOR_AFTER) {
            FolkTalk.speak(f, "Not a bite all day. I can't work like this.");
        } else if (f.getRandom().nextInt(2) == 0) {
            FolkTalk.speak(f, f.isBaby() ? "I'm hungry." : "No " + m.label + " for me today. Is there nothing in the stores?");
        }
        f.refreshMood();
    }

    private static void had(VillageFolkEntity f, Book b, Meal m, int bit, UUID village, long day) {
        boolean wasHungry = b.missedInRow > 0;
        b.taken |= bit;
        b.eaten++;
        b.missedInRow = 0;
        b.lastMeal = m.ordinal();
        book(village, day, true, f.getUUID());
        if (wasHungry) f.refreshMood();
    }

    /**
     * Something eaten now, if there is anything: out of its own pack; else out of its household's chest,
     * if it is near home (the family's food); else out of the village's stores, if it is near them or is
     * one the town feeds where it stands (a child, an elder, a folk with no trade). A far hand at its work
     * has its rations sent out by the storehouse instead (VillageFolkEntity.rationsAhead).
     */
    static boolean eat(ServerLevel level, VillageFolkEntity f, UUID village) {
        if (f.eatFromPack()) return true;
        boolean fedWhereItStands = f.isBaby() || f.stationTask() == AssistantEntity.StationTask.NONE;
        Homes.Home home = Homes.homeOf(village, f.getUUID());
        if (home != null && (fedWhereItStands || f.blockPosition().closerThan(home.anchor, HOME_REACH))) {
            BlockPos chest = Homes.chestOf(level, village, home);
            if (chest != null && level.getBlockEntity(chest) instanceof Container c && takeOne(c, f)) {
                if (f.eatFromPack()) return true;
            }
        }
        Villages.Village v = Villages.get(village);
        if (v != null && (fedWhereItStands || f.blockPosition().closerThan(v.centre(), STORES_REACH))) {
            if (f.mealFromTheStores() > 0) return f.eatFromPack();
        }
        return f.eatFromSeed();                             // [economy] a carrot of its seed, before it goes without
    }

    /**
     * [families] Supper at home: out of the household's chest first (the family's food, put by for it), then
     * its own pack. False with neither: the stores, as at any meal (eat).
     */
    static boolean eatAtHome(ServerLevel level, VillageFolkEntity f, UUID village) {
        Homes.Home home = Homes.homeOf(village, f.getUUID());
        BlockPos chest = home == null ? null : Homes.chestOf(level, village, home);
        if (chest != null && level.getBlockEntity(chest) instanceof Container c && takeOne(c, f) && f.eatFromPack()) return true;
        return f.eatFromPack();
    }

    /** Is this meal behind this folk today: eaten (or, its hour over, gone without)? */
    static boolean hadToday(VillageFolkEntity f, Meal m, long day) {
        Book b = f.meals();
        return b.day == day && (b.taken & (1 << m.ordinal())) != 0;
    }

    /** One meal's worth out of a chest and into the pack. */
    private static boolean takeOne(Container c, VillageFolkEntity f) {
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack st = c.getItem(i);
            if (st.isEmpty() || !FOOD.test(st)) continue;
            ItemStack left = f.insertGiven(st.copyWithCount(1));
            if (!left.isEmpty()) return false;                     // the pack is full
            st.shrink(1);
            if (st.isEmpty()) c.setItem(i, ItemStack.EMPTY);
            c.setChanged();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ what a player reads

    /** The folk card's line: when it last ate and what, its meals today, and whether it is going hungry. */
    public static String line(VillageFolkEntity f) {
        Book b = f.meals();
        StringBuilder sb = new StringBuilder();
        if (b.missedInRow > 0) {
            sb.append(b.missedInRow >= WEAK_AFTER ? "Starving: " : "Hungry: ").append("missed ").append(b.missedInRow)
                .append(b.missedInRow == 1 ? " meal" : " meals").append(" in a row. ");
        }
        if (!b.lastWhat.isEmpty()) {
            sb.append("Last ate ").append(b.lastWhat.toLowerCase(java.util.Locale.ROOT));
            if (b.lastMeal >= 0 && b.lastMeal < Meal.values().length) sb.append(" at ").append(Meal.values()[b.lastMeal].label);
            sb.append(". ");
        }
        sb.append(b.eaten).append(b.eaten == 1 ? " meal" : " meals").append(" today");
        return sb.toString();
    }

    /** The town's line for the Why page and the status: yesterday's meals, and who went without. */
    @Nullable
    public static String townLine(UUID village) {
        int[] y = yesterday(village);
        if (y == null) return null;
        if (y[1] == 0) return "+Every folk had its meals yesterday: " + y[0] + " eaten, none missed.";
        return "-" + y[1] + (y[1] == 1 ? " meal" : " meals") + " missed yesterday (" + y[2] + (y[2] == 1 ? " folk" : " folk")
            + " with nothing to eat in reach) against " + y[0] + " eaten. Food to the stores and the homes.";
    }
}
