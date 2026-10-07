package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [individual] A folk's habits, each done at its own time, and the place in town it likes best.
 *
 * <ul>
 * <li><b>A morning walk</b>, round the edge of the town once the morning assembly is over, before it settles to work.</li>
 * <li><b>Feeding the birds</b> on its break: a handful of seed out of its own pack or the stores (never out of
 *     nothing) scattered by its door, and the town's hens come running.</li>
 * <li><b>A pipe on the step at dusk</b>: sat outside its own door, its pipe in its mouth, the smoke going up.</li>
 * <li><b>Tidying its house</b>: home of an evening, and whatever lies about outside its door picked up and put away
 *     in the household's chest.</li>
 * <li><b>A grave on the day of rest</b>: out to the grave of one it lost, a flower laid if it has one.</li>
 * <li><b>Reading before bed</b>, a reader only: by its bed with a book of its own, the stores' book it took for it;
 *     it reads up on its trade, a little more learned each night.</li>
 * <li><b>An evening at the tavern</b>, every evening, not one in two and a half (Tavern.goingTonight).</li>
 * <li><b>Whittling</b> on its step of an evening: a stick turned over three evenings into a wooden toy, made by the
 *     toy's own recipe out of the stores, and given to a child of the town to keep.</li>
 * </ul>
 * And <b>a favourite place</b> it goes to in its free time: the bench by the well, the quay, the hill above the town,
 * the library window, the park, the tavern hearth, the chapel steps, its own garden gate, the market square.
 *
 * <p>Each folk's part runs from its tick ({@link #hold}), and does nothing at all while it is not free or not the
 * hour: work, the town's gatherings, its family and the alarm all come first.
 */
public final class Habits {

    private Habits() {}

    public enum Habit {
        MORNING_WALK("a morning walk", "out on its morning walk"),
        FEED_BIRDS("feeding the birds", "feeding the birds"),
        PIPE("a pipe on the step at dusk", "smoking its pipe on the step"),
        TIDY("tidying the house", "tidying round its house"),
        GRAVE("a grave on the day of rest", "at a grave"),
        READING("reading before bed", "reading before bed"),
        TAVERN("an evening at the tavern", "at the tavern"),
        WHITTLING("whittling on the step", "whittling on its step");

        public final String word, doing;

        Habit(String word, String doing) {
            this.word = word;
            this.doing = doing;
        }
    }

    public enum Place {
        WELL("the bench by the well", "by the well"),
        QUAY("the quay", "down on the quay"),
        HILL("the hill above the town", "up the hill"),
        LIBRARY("the library window", "at the library window"),
        PARK("the park", "in the park"),
        TAVERN("the tavern hearth", "by the tavern hearth"),
        CHAPEL("the chapel steps", "on the chapel steps"),
        GARDEN("its garden gate", "at its garden gate"),
        MARKET("the market square", "on the market square");

        public final String word, at;

        Place(String word, String at) {
            this.word = word;
            this.at = at;
        }
    }

    /** What a folk is about just now, its spot and its hours (not saved: a habit cut short by a restart is skipped). */
    static final class St {
        @Nullable Habit doing;
        @Nullable BlockPos spot;
        final List<BlockPos> route = new ArrayList<>();
        int until, step, walkTick, held = -100, nextLook;
        final long[] done = new long[Habit.values().length];
        long placeDay = -1;
        int placeUntil;
        long progressDay = -1;
        int whittled;
        boolean sat;

        St() {
            java.util.Arrays.fill(done, -1);
        }
    }

    private static final Map<UUID, St> ST = new ConcurrentHashMap<>();
    /** Each town's hill and its quay, looked for once a day. */
    private static final Map<UUID, BlockPos> HILL = new ConcurrentHashMap<>(), QUAY = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SURVEYED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        ST.clear();
        HILL.clear();
        QUAY.clear();
        SURVEYED.clear();
    }

    static St st(VillageFolkEntity f) {
        return ST.computeIfAbsent(f.getUUID(), k -> new St());
    }

    // ------------------------------------------------------------------ rolled

    /** One or two habits, and a favourite place, by its nature, its trade, its years and whether it reads. */
    static void roll(VillageFolkEntity f, Individual.Self s, RandomSource r) {
        s.habits.clear();
        Social.Life life = f.life();
        boolean old = !f.isBaby() && f.ageYears() >= 50;
        double[] w = new double[Habit.values().length];
        w[Habit.MORNING_WALK.ordinal()] = 2 + (life.has(Social.Trait.HARDWORKING) ? 2 : 0);
        w[Habit.FEED_BIRDS.ordinal()] = 1.5 + (life.has(Social.Trait.GENEROUS) ? 1.5 : 0) + (f.isBaby() ? 3 : 0);
        w[Habit.PIPE.ordinal()] = f.isBaby() ? 0 : 1.2 + (old ? 3 : 0) + (life.has(Social.Trait.EASYGOING) ? 1 : 0);
        w[Habit.TIDY.ordinal()] = f.isBaby() ? 0 : 1.5 + (Values.top(f) == Values.Value.HOMES ? 3 : 0);
        w[Habit.GRAVE.ordinal()] = s.mourns.isEmpty() || f.isBaby() ? 0 : 3;
        w[Habit.READING.ordinal()] = s.literate && !f.isBaby() ? 2 + (f.persona().rolled() && f.persona().hobby() == Persona.Hobby.READING ? 3 : 0) : 0;
        w[Habit.TAVERN.ordinal()] = f.isBaby() ? 0 : 1.2 + (life.has(Social.Trait.SOCIABLE) ? 2.5 : 0);
        w[Habit.WHITTLING.ordinal()] = f.isBaby() ? 0 : 1 + (f.stationTask() == StationTask.WOOD ? 2 : 0) + (old ? 1.5 : 0);
        int n = f.isBaby() ? 1 : r.nextInt(10) < 3 ? 2 : 1;
        for (int i = 0; i < n; i++) {
            int pick = pick(r, w);
            if (pick < 0) break;
            s.habits.add(Habit.values()[pick]);
            w[pick] = 0;
        }
        double[] p = new double[Place.values().length];
        boolean heights = s.fears.contains(Fears.Fear.HEIGHTS);
        Persona.Hobby hobby = f.persona().rolled() ? f.persona().hobby() : Persona.Hobby.WALKING;
        p[Place.WELL.ordinal()] = 3;
        p[Place.QUAY.ordinal()] = s.fears.contains(Fears.Fear.DEEP_WATER) ? 0
            : 1.2 + (f.stationTask() == StationTask.FISH || hobby == Persona.Hobby.FISHING ? 3 : 0);
        p[Place.HILL.ordinal()] = heights ? 0 : 1.5 + (hobby == Persona.Hobby.STARGAZING || hobby == Persona.Hobby.WALKING ? 2.5 : 0);
        p[Place.LIBRARY.ordinal()] = s.literate ? 1 + (hobby == Persona.Hobby.READING ? 3 : 0) : 0;
        p[Place.PARK.ordinal()] = 1.5;
        p[Place.TAVERN.ordinal()] = f.isBaby() ? 0 : 1 + (life.has(Social.Trait.SOCIABLE) ? 1.5 : 0);
        p[Place.CHAPEL.ordinal()] = 0.8;
        p[Place.GARDEN.ordinal()] = f.isBaby() ? 0 : 1 + (hobby == Persona.Hobby.GARDENING ? 3 : 0);
        p[Place.MARKET.ordinal()] = 1 + (life.has(Social.Trait.SOCIABLE) ? 1 : 0);
        if (life.has(Social.Trait.SHY)) {
            p[Place.TAVERN.ordinal()] *= 0.3;
            p[Place.MARKET.ordinal()] *= 0.3;
        }
        int place = pick(r, p);
        s.place = place < 0 ? Place.WELL : Place.values()[place];
    }

    private static int pick(RandomSource r, double[] w) {
        double sum = 0;
        for (double d : w) sum += d;
        if (sum <= 0) return -1;
        double x = r.nextDouble() * sum;
        for (int i = 0; i < w.length; i++) {
            x -= w[i];
            if (x < 0 && w[i] > 0) return i;
        }
        for (int i = w.length - 1; i >= 0; i--) if (w[i] > 0) return i;
        return -1;
    }

    /** Took up a habit (someone close died: the grave on the day of rest, for some). */
    static void takeUp(VillageFolkEntity f, Habit h) {
        List<Habit> hs = f.individual().habits;
        if (hs.contains(h)) return;
        if (hs.size() >= 2) hs.remove(hs.size() - 1);
        hs.add(0, h);
    }

    // ------------------------------------------------------------------ its tick

    /** Free for a habit: awake, off work (or before work, for the walk), nothing of the town's or the family's in hand. */
    static boolean free(VillageFolkEntity f, boolean beforeWork) {
        if (f.isSleeping() || f.isBaby() || f.peekJob() != null && !beforeWork) return false;
        // Not even the walk in the middle of a building: walked off its lot, the builder passed by every cell it could
        // not get at, and a park's lot was filled with its fountain's stone before the bank's earth was dug to fill it.
        if (beforeWork && f.peekJob() != null && f.peekJob().type() == Job.Type.BUILD) return false;
        // Nor before it has a trade and ground to work it: the walk is before the day's work, and a folk with none yet
        // spends its morning choosing one (its agenda waits while it walks: one of a new town of twelve was still out
        // walking, with no trade, when the other eleven had theirs).
        if (beforeWork && (f.stationTask() == StationTask.NONE || f.workZone() == null)) return false;
        UUID village = f.ownerId();
        if (village == null || Raids.underAlarm(village)) return false;
        if (TownJobs.busy(f) || Assemblies.attending(f) || School.teaching(f) || Birthdays.busy(f) || Families.busy(f)
                || Neighbourly.busy(f) || Health.laidUp(f) || TownCalendar.busy(f) || f.getTarget() != null) return false;
        return beforeWork || f.offWorkNow();
    }

    /** The hour each habit wants, or false: tod the time of day, rest the town's day of rest. */
    static boolean hour(VillageFolkEntity f, Habit h, long tod, boolean rest) {
        long bed = f.bedtimeTick();
        return switch (h) {
            case MORNING_WALK -> !rest && tod >= 1400L && tod < 2600L;
            case FEED_BIRDS -> tod >= 3000L && tod < 11800L;
            case PIPE -> tod >= 12000L && tod < Math.min(13800L, bed);
            case TIDY -> tod >= 12000L && tod < Math.min(13000L, bed) || rest && tod >= 4000L && tod < 8000L;
            case GRAVE -> rest && tod >= 8000L && tod < 11400L;
            case READING -> tod >= bed - 1300L && tod < bed;
            case TAVERN -> false;                               // Tavern.goingTonight: every evening (regular)
            case WHITTLING -> tod >= 12200L && tod < Math.min(14000L, bed);
        };
    }

    /** From the folk's tick: a habit at its hour. True while it is about it. */
    static boolean hold(VillageFolkEntity f, ServerLevel level) {
        Individual.Self s = f.individual();
        if (s.habits.isEmpty() || f.villageCentre() == null) return release(f, null);
        St st = st(f);
        long dt = level.getDayTime(), tod = dt % 24000L, day = dt / 24000L;
        boolean rest = RestDay.today(f.ownerId(), day);
        if (st.doing != null) {
            if (!hour(f, st.doing, tod, rest) || !free(f, st.doing == Habit.MORNING_WALK) || f.tickCount > st.until) {
                st.done[st.doing.ordinal()] = day;
                return release(f, st);
            }
            return step(f, level, st, s, day);
        }
        if (f.tickCount < st.nextLook) return false;
        st.nextLook = f.tickCount + 40;
        for (Habit h : s.habits) {
            if (st.done[h.ordinal()] == day || !hour(f, h, tod, rest) || !free(f, h == Habit.MORNING_WALK)) continue;
            if (!begin(f, level, st, s, h, day)) {
                st.done[h.ordinal()] = day;                      // nothing to do it with today: tomorrow, then
                continue;
            }
            return true;
        }
        return false;
    }

    static boolean busy(VillageFolkEntity f) {
        St st = ST.get(f.getUUID());
        return st != null && st.doing != null && f.tickCount - st.held <= 8;
    }

    /** What it is about, for its card's "doing" line, or null. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        St st = ST.get(f.getUUID());
        return st == null || st.doing == null ? null : capFirst(st.doing.doing);
    }

    /** Tests: the habit it is at now, or null. */
    @Nullable
    public static Habit doingForTests(VillageFolkEntity f) {
        St st = ST.get(f.getUUID());
        return st == null ? null : st.doing;
    }

    /** Tests: the day it last did this habit, or -1. */
    public static long doneForTests(VillageFolkEntity f, Habit h) {
        return st(f).done[h.ordinal()];
    }

    private static boolean release(VillageFolkEntity f, @Nullable St st) {
        if (st != null && st.doing != null) {
            if (st.sat && f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
            st.sat = false;
            st.doing = null;
            st.spot = null;
            st.route.clear();
            Manner.idle(f, Manner.NO_IDLE, 0);
        }
        return false;
    }

    private static boolean begin(VillageFolkEntity f, ServerLevel level, St st, Individual.Self s, Habit h, long day) {
        BlockPos home = f.bedPos();
        BlockPos centre = f.villageCentre();
        st.route.clear();
        st.step = 0;
        st.sat = false;
        switch (h) {
            case MORNING_WALK -> {
                int base = Math.floorMod(f.getUUID().hashCode(), 360);
                for (int i = 0; i < 4; i++) {
                    double a = Math.toRadians(base + i * 90);
                    int rad = 14 + Math.floorMod(f.getUUID().hashCode() >> (i * 3), 9);
                    BlockPos p = f.surfaceAt(centre.getX() + (int) (Math.cos(a) * rad), centre.getZ() + (int) (Math.sin(a) * rad));
                    if (p != null) st.route.add(p);
                }
                if (st.route.isEmpty()) return false;
                st.until = f.tickCount + 1400;
            }
            case FEED_BIRDS -> {
                if (!seed(f, level)) return false;
                st.spot = doorstep(f, level, home != null ? home : centre);
                st.until = f.tickCount + 420;
            }
            case PIPE, WHITTLING -> {
                if (home == null) return false;
                if (h == Habit.WHITTLING && !stick(f, level)) return false;
                st.spot = doorstep(f, level, home);
                st.until = f.tickCount + 1600;
            }
            case TIDY -> {
                if (home == null) return false;
                st.spot = home;
                st.until = f.tickCount + 500;
            }
            case GRAVE -> {
                BlockPos[] g = grave(f);
                if (g == null) return false;
                st.spot = g[1];
                st.until = f.tickCount + 900;
            }
            case READING -> {
                if (home == null || !s.literate || !book(f, level)) return false;
                st.spot = home;
                st.until = f.tickCount + 1400;
            }
            case TAVERN -> { return false; }
        }
        if (st.spot == null && st.route.isEmpty()) return false;
        st.doing = h;
        st.held = f.tickCount;
        f.hobbyNow = h.doing;
        f.lastLeisureTick = f.tickCount;
        return true;
    }

    private static boolean step(VillageFolkEntity f, ServerLevel level, St st, Individual.Self s, long day) {
        st.held = f.tickCount;
        f.hobbyNow = st.doing.doing;
        f.lastLeisureTick = f.tickCount;
        Habit h = st.doing;
        RandomSource r = f.getRandom();
        if (h == Habit.MORNING_WALK) {
            if (st.step >= st.route.size()) {
                st.done[h.ordinal()] = day;
                if (r.nextInt(3) == 0) FolkTalk.speak(f, FolkTalk.pick(r, "That's blown the cobwebs off.", "Lovely morning for it.",
                    "Round the town and back — now for work."));
                f.persona().remember(day, "I took my morning walk round " + Individual.town(f), 1);
                return release(f, st);
            }
            BlockPos to = st.route.get(st.step);
            if (walk(f, st, to, Manner.oldWalker(f) ? 0.6D : 0.75D, 2.5)) st.step++;
            return true;
        }
        if (!walk(f, st, st.spot, Manner.oldWalker(f) ? 0.65D : 0.85D, h == Habit.GRAVE ? 1.6 : 1.8)) return true;
        switch (h) {
            case FEED_BIRDS -> {
                if (f.tickCount % 20 == 0) {
                    f.swing(InteractionHand.MAIN_HAND);
                    level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.WHEAT_SEEDS)),
                        f.getX(), f.getY() + 1.0, f.getZ(), 6, 0.6, 0.2, 0.6, 0.05);
                    for (var bird : level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, f.getBoundingBox().inflate(14.0),
                            a -> a instanceof Chicken || a instanceof Parrot)) {
                        bird.getNavigation().moveTo(f.getX() + r.nextInt(3) - 1, f.getY(), f.getZ() + r.nextInt(3) - 1, 1.0D);
                    }
                }
                if (st.step++ == 0) {
                    FolkTalk.speak(f, FolkTalk.pick(r, "Here, chook chook chook!", "Come on then, my lovelies.", "There's plenty for everyone."));
                    st.done[h.ordinal()] = day;
                }
            }
            case PIPE, WHITTLING -> {
                if (!st.sat) {
                    f.getNavigation().stop();
                    f.setPose(Pose.SITTING);
                    st.sat = true;
                    st.done[h.ordinal()] = day;
                }
                if (h == Habit.PIPE) {
                    Manner.idle(f, Manner.PIPE, 60);
                    if (f.tickCount % 25 == 0) {
                        double yaw = Math.toRadians(f.yHeadRot);
                        level.sendParticles(ParticleTypes.SMOKE, f.getX() - Math.sin(yaw) * 0.45 - Math.cos(yaw) * 0.12,
                            f.getEyeY() - (st.sat ? 0.62 : 0.05), f.getZ() + Math.cos(yaw) * 0.45 - Math.sin(yaw) * 0.12, 2, 0.02, 0.05, 0.02, 0.005);
                    }
                    if (st.step++ == 40) FolkTalk.speak(f, FolkTalk.pick(r, "Ahh. That's the day done.", "Nothing like a pipe at dusk.",
                        "Evening, all."));
                } else {
                    whittle(f, level, st, r, day);
                }
            }
            case TIDY -> {
                if (st.step++ == 0) {
                    int put = tidy(f, level);
                    st.done[h.ordinal()] = day;
                    if (put > 0) FolkTalk.speak(f, FolkTalk.pick(r, "Things left lying about… there. Tidy.", "A place for everything."));
                }
                if (f.tickCount % 15 == 0) {
                    f.swing(r.nextBoolean() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
                    f.getLookControl().setLookAt(f.getX() + r.nextInt(5) - 2, f.getY(), f.getZ() + r.nextInt(5) - 2);
                }
            }
            case GRAVE -> {
                if (st.step++ == 0) {
                    st.done[h.ordinal()] = day;
                    layFlower(f, level, st.spot);
                    FolkTalk.speak(f, FolkTalk.pick(r, "I still miss you, " + s.mourns + ".", "Another week gone, " + s.mourns + ".",
                        "The town's well, " + s.mourns + ". I thought you'd want to know."));
                    f.persona().remember(day, "I visited " + s.mourns + "'s grave on the day of rest", 3);
                }
                BlockPos[] g = grave(f);
                if (g != null) f.getLookControl().setLookAt(g[0].getX() + 0.5, g[0].getY() + 0.5, g[0].getZ() + 0.5);
            }
            case READING -> {
                holdProp(f, Items.BOOK);
                Manner.idle(f, Manner.READ, 60);
                if (st.step++ == 0) {
                    st.done[h.ordinal()] = day;
                    StationTask t = f.stationTask();
                    if (t != StationTask.NONE) f.schoolXp(t, 12);       // a little more learned in its trade each night
                }
                if (f.tickCount % 60 == 0) f.playSound(SoundEvents.BOOK_PAGE_TURN, 0.5F, 1.0F);
            }
            default -> { }
        }
        return true;
    }

    private static boolean walk(VillageFolkEntity f, St st, BlockPos to, double speed, double near) {
        if (f.distanceToSqr(to.getX() + 0.5, to.getY(), to.getZ() + 0.5) <= near * near) {
            if (!st.sat) f.getNavigation().stop();
            return true;
        }
        if (st.sat && f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
        st.sat = false;
        if (f.getNavigation().isDone() || f.tickCount - st.walkTick > 80) {
            f.walkTo(to, speed);
            st.walkTick = f.tickCount;
        }
        return false;
    }

    /** Somewhere just outside its door: the nearest open ground under the sky within a few steps of its bed. */
    @Nullable
    static BlockPos doorstep(VillageFolkEntity f, ServerLevel level, BlockPos home) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                BlockPos p = f.surfaceAt(home.getX() + dx, home.getZ() + dz);
                if (p == null || Math.abs(p.getY() - home.getY()) > 2 || !level.canSeeSky(p)) continue;
                if (!level.getFluidState(p).isEmpty() || !level.getFluidState(p.below()).isEmpty()) continue;
                double d = dx * dx + dz * dz;
                if (d < bestD) { bestD = d; best = p; }
            }
        }
        return best != null ? best : home;
    }

    // ------------------------------------------------------------------ what it takes

    /** A handful of seed: its own, else out of the stores. False with none to be had. */
    private static boolean seed(VillageFolkEntity f, ServerLevel level) {
        java.util.function.Predicate<ItemStack> seeds = st -> st.is(Items.WHEAT_SEEDS) || st.is(Items.BEETROOT_SEEDS)
            || st.is(Items.MELON_SEEDS) || st.is(Items.PUMPKIN_SEEDS);
        if (f.removeMatching(seeds, 1) == 1) return true;
        Villages.Village v = Villages.get(f.ownerId());
        return v != null && !Crafts.takeOne(level, v, seeds).isEmpty();
    }

    /** A stick to whittle: its own, else one out of the stores, kept till it is done. */
    private static boolean stick(VillageFolkEntity f, ServerLevel level) {
        if (f.countCarried(st -> st.is(Items.STICK) && !"stick".equals(Keepsakes.role(st))) > 0) return true;
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return false;
        ItemStack one = Crafts.takeOne(level, v, st -> st.is(Items.STICK));
        if (one.isEmpty()) return false;
        Keepsakes.carry(one, f, "whittle");
        return f.insertGiven(one).isEmpty();
    }

    /** A book of its own to read: one it carries, else the stores' book, marked its own. */
    private static boolean book(VillageFolkEntity f, ServerLevel level) {
        if (f.countCarried(st -> st.is(Items.BOOK) || st.is(Items.WRITTEN_BOOK) || st.is(Items.WRITABLE_BOOK)) > 0) return true;
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return false;
        ItemStack one = Crafts.takeOne(level, v, st -> st.is(Items.BOOK));
        if (one.isEmpty()) return false;
        String title = f.individual().book.isEmpty() ? "a book" : f.individual().book;
        one.set(DataComponents.CUSTOM_NAME, Component.literal(f.displayNameCap() + "'s copy of " + title));
        Keepsakes.carry(one, f, "book");
        return f.insertGiven(one).isEmpty();
    }

    /** A pastime's thing in its other hand, for show (Leisure's props: put away when the evening is over). */
    static void holdProp(VillageFolkEntity f, Item item) {
        ItemStack off = f.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!off.isEmpty()) return;
        ItemStack prop = new ItemStack(item);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("mca_prop", true);                       // Leisure.isProp: never dropped, put away after
        prop.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        f.setItemSlot(EquipmentSlot.OFFHAND, prop);
        f.propInHand = true;
    }

    /** Three evenings at it and the stick is a toy (the toy's recipe, out of the stores): to a child of the town. */
    private static void whittle(VillageFolkEntity f, ServerLevel level, St st, RandomSource r, long day) {
        holdProp(f, Items.STICK);
        Manner.idle(f, Manner.WHITTLE, 60);
        if (f.tickCount % 30 == 0) {
            f.swing(InteractionHand.OFF_HAND);
            f.playSound(SoundEvents.AXE_STRIP, 0.25F, 1.7F);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_PLANKS.defaultBlockState()),
                f.getX(), f.getY() + 0.9, f.getZ(), 3, 0.15, 0.05, 0.15, 0.02);
        }
        if (st.progressDay == day) return;
        st.progressDay = day;
        if (++st.whittled < 3) return;
        st.whittled = 0;
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return;
        ItemStack toy = QuestItems.make(level, v, f, McAssistantMod.WOODEN_TOY.get());
        if (toy.isEmpty()) return;
        f.removeMatching(s -> s.is(Items.STICK) && "whittle".equals(Keepsakes.role(s)), 1);   // the stick it turned is the toy's
        VillageFolkEntity child = Keepsakes.childWanting(f, level);
        if (child == null) {
            Crafts.store(level, v, toy);
            FolkTalk.speak(f, "Another toy done. It'll go in the stores for whoever wants it.");
            return;
        }
        Keepsakes.give(child, toy, Keepsakes.Kind.TOY, "a toy " + f.displayNameCap() + " carved for it");
        FolkTalk.speak(f, FolkTalk.pick(r, "There — a little horse, for " + child.displayNameCap() + ".", "Finished! That's for "
            + child.displayNameCap() + "."));
        f.persona().remember(day, "I carved a toy for " + child.displayNameCap(), 4);
        Villages.tell(f.ownerId(), day, f.displayNameCap() + " carved " + child.displayNameCap() + " a wooden toy");
    }

    /** Whatever lies about outside its door picked up, into the household's chest (else its pack). How many. */
    static int tidy(VillageFolkEntity f, ServerLevel level) {
        BlockPos home = f.bedPos();
        UUID village = f.ownerId();
        if (home == null || village == null) return 0;
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        BlockPos chest = h == null ? null : Homes.chestOf(level, village, h);
        net.minecraft.world.Container box = chest != null && level.getBlockEntity(chest) instanceof net.minecraft.world.Container c ? c : null;
        int put = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(home).inflate(7.0, 3.0, 7.0))) {
            if (!e.isAlive() || e.hasPickUpDelay()) continue;
            ItemStack s = e.getItem().copy();
            ItemStack left = box != null ? Homes.insertInto(box, s) : f.insertItem(s);
            put += s.getCount() - left.getCount();
            if (left.isEmpty()) e.discard();
            else e.setItem(left);
        }
        if (box != null) box.setChanged();
        return put;
    }

    @Nullable
    private static BlockPos[] grave(VillageFolkEntity f) {
        UUID village = f.ownerId();
        String who = f.individual().mourns;
        if (village == null || who.isEmpty()) return null;
        List<Ledger.Grave> dead = Ledger.graves(village);
        for (int i = dead.size() - 1; i >= 0; i--) {
            if (dead.get(i).name().equals(who)) return Graves.graveOf(village, i);
        }
        return null;
    }

    /** A flower it carries, laid on the grave. */
    private static void layFlower(VillageFolkEntity f, ServerLevel level, BlockPos at) {
        if (!level.getBlockState(at).isAir()) return;
        for (ItemStack s : f.getInventoryItems()) {
            if (s.isEmpty() || !s.is(ItemTags.SMALL_FLOWERS) || Keepsakes.isTreasure(s)) continue;
            Block b = Block.byItem(s.getItem());
            if (b == Blocks.AIR || !b.defaultBlockState().canSurvive(level, at)) continue;
            level.setBlockAndUpdate(at, b.defaultBlockState());
            s.shrink(1);
            return;
        }
    }

    /** Tavern.goingTonight: one who has the tavern for its habit goes every evening. */
    public static boolean regular(VillageFolkEntity f) {
        return f.individual().habits.contains(Habit.TAVERN);
    }

    /** A habit kept today: a little contentment in it. */
    static int mood(VillageFolkEntity f, long day) {
        St st = ST.get(f.getUUID());
        if (st == null) return 0;
        for (Habit h : f.individual().habits) if (st.done[h.ordinal()] == day || st.done[h.ordinal()] == day - 1) return 3;
        return 0;
    }

    // ------------------------------------------------------------------ the favourite place

    /**
     * In its free time (VillageFolkEntity.socialise), about one free spell in two: off to its favourite place and a
     * while there. The hill is for dusk. True while it is going or there.
     */
    public static boolean favouritePlace(VillageFolkEntity f, ServerLevel level) {
        Individual.Self s = f.individual();
        if (!s.rolled || f.isBaby() || f.villageCentre() == null || f.ownerId() == null) return false;
        St st = st(f);
        long dt = level.getDayTime(), tod = dt % 24000L, half = dt / 12000L;
        if (st.placeDay != half) {
            st.placeDay = half;
            boolean dusk = tod >= 11000L && tod < 14000L;
            boolean go = (s.place == Place.HILL ? dusk : true) && Math.floorMod(f.getUUID().hashCode() + half * 7, 2L) == 0;
            st.placeUntil = go ? f.tickCount + 900 + f.getRandom().nextInt(600) : -1;
        }
        if (f.tickCount > st.placeUntil) return false;
        BlockPos spot = placeSpot(f, level);
        if (spot == null) return false;
        f.hobbyNow = "at " + s.place.word + ", its favourite spot";
        f.lastLeisureTick = f.tickCount;
        if (f.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5) > 2.5 * 2.5) {
            if (f.getNavigation().isDone() || f.tickCount - st.walkTick > 100) {
                f.walkTo(spot, Manner.oldWalker(f) ? 0.65D : 0.8D);
                st.walkTick = f.tickCount;
            }
            return true;
        }
        f.getNavigation().stop();
        if (f.getRandom().nextInt(200) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I could sit here all day.", "My favourite spot, this.",
                "You can see half the town from here.", "Quiet here. Just how I like it."));
        }
        return true;
    }

    /** Tests: the tidying of its doorstep, now: how many things it put away. */
    public static int tidyForTests(VillageFolkEntity f) {
        return f.level() instanceof ServerLevel level ? tidy(f, level) : 0;
    }

    /** Tests: where its favourite place is, as it would go there now. */
    @Nullable
    public static BlockPos placeSpotForTests(VillageFolkEntity f) {
        return f.level() instanceof ServerLevel level ? placeSpot(f, level) : null;
    }

    /** Tests: its favourite place's spell starts now. */
    public static void placeNowForTests(VillageFolkEntity f) {
        St st = st(f);
        st.placeDay = f.level().getDayTime() / 12000L;
        st.placeUntil = f.tickCount + 1200;
    }

    /** Where its favourite place is in its town: the building, or the spot, or the well when the town has none. */
    @Nullable
    static BlockPos placeSpot(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        BlockPos centre = f.villageCentre();
        if (village == null || centre == null) return null;
        int h = f.getUUID().hashCode();
        int ox = Math.floorMod(h, 5) - 2, oz = Math.floorMod(h >> 4, 5) - 2;
        BlockPos at = switch (f.individual().place) {
            case WELL -> Villages.builtAt(village, "well");
            case QUAY -> quay(f, level);
            case HILL -> hill(f, level);
            case LIBRARY -> Villages.builtAt(village, "library");
            case PARK -> Villages.builtAt(village, "park");
            case TAVERN -> Villages.builtAt(village, "tavern");
            case CHAPEL -> Villages.builtAt(village, "chapel");
            case GARDEN -> f.bedPos() == null ? null : doorstep(f, level, f.bedPos());
            case MARKET -> Villages.builtAt(village, "market");
        };
        if (at == null) at = centre.offset(3, 0, 3);
        BlockPos p = f.surfaceAt(at.getX() + ox, at.getZ() + oz);
        return p != null ? p : at;
    }

    /** The town's quay: the water's edge nearest its heart, looked for once a day. */
    @Nullable
    private static BlockPos quay(VillageFolkEntity f, ServerLevel level) {
        survey(f, level);
        return QUAY.get(f.ownerId());
    }

    @Nullable
    private static BlockPos hill(VillageFolkEntity f, ServerLevel level) {
        survey(f, level);
        return HILL.get(f.ownerId());
    }

    private static void survey(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        long day = level.getDayTime() / 24000L;
        if (SURVEYED.getOrDefault(village, -1L) == day) return;
        SURVEYED.put(village, day);
        BlockPos c = f.villageCentre();
        BlockPos high = null, edge = null;
        double edgeD = Double.MAX_VALUE;
        for (int ring = 8; ring <= 40; ring += 4) {
            for (int a = 0; a < 360; a += 20) {
                int x = c.getX() + (int) (Math.cos(Math.toRadians(a)) * ring), z = c.getZ() + (int) (Math.sin(Math.toRadians(a)) * ring);
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos top = new BlockPos(x, y, z);
                if (level.getFluidState(top.below()).is(FluidTags.WATER)) {
                    if (edge == null && ring * ring < edgeD) {
                        edgeD = ring * ring;
                        edge = top;
                    }
                    continue;
                }
                if (level.canSeeSky(top) && (high == null || y > high.getY())) high = top;
            }
        }
        if (high != null) HILL.put(village, high);
        else HILL.remove(village);
        if (edge != null) QUAY.put(village, edge);
        else QUAY.remove(village);
    }

    // ------------------------------------------------------------------ in words

    static String cardLine(VillageFolkEntity f) {
        List<String> out = new ArrayList<>();
        for (Habit h : f.individual().habits) out.add(h.word);
        return String.join("; ", out);
    }

    static String placeLine(VillageFolkEntity f) {
        return f.individual().place.word;
    }

    static String gossip(String n, Habit h, Place p) {
        return switch (h) {
            case PIPE -> "Every evening, there's " + n + " on the step with that pipe. You could set the clocks by it.";
            case MORNING_WALK -> n + " walks right round the town every morning before work. Says it keeps the joints oiled.";
            case FEED_BIRDS -> n + " feeds the hens on the sly. They follow " + n + " about like a lord.";
            case TIDY -> "You'll never find a thing out of place round " + n + "'s door.";
            case GRAVE -> n + " goes out to the graveyard every day of rest. Never misses.";
            case READING -> n + " reads in bed every night. Ruins the eyes, I say.";
            case TAVERN -> n + "'s at the tavern every night. Has a stool with " + n + "'s name on it, near enough.";
            case WHITTLING -> n + " whittles toys for the children. Half the little ones have one.";
        } + (p == Place.HILL ? " And " + n + "'s always up the hill at dusk." : "");
    }

    private static String capFirst(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
