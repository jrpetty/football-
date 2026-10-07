package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [batchA] Neighbours: what the folk of a town do for one another, over and above their families (Families) and
 * their friends' birthdays (Birthdays).
 *
 * <ul>
 * <li><b>The old looked after.</b> Every day somebody looks in on each of the town's very old (eighty and on)
 *     and frail (in their last few years): one of the family (a partner, a child, a parent) if there is one
 *     free, else a friend, else a neighbour on good terms. In its own time, before supper, it fetches a meal
 *     out of the stores (the old one's favourite, if the stores have it), carries it over, hands it to the old
 *     one and sits down with it a while. They are the fonder of each other, and both remember it.</li>
 * <li><b>The welcome committee.</b> A newcomer (taken on at the job market, taken in after a raid, a villager
 *     the town took over, a folk stood up in a town already standing) is greeted within the day by the elder,
 *     or else the friendliest folk in the town, and walked round: the heart of the town, the stores (where it
 *     is handed a welcome basket out of them: a loaf, a torch and a flower, whatever of them there is) and its
 *     new home, the newcomer at its side.</li>
 * <li><b>A housewarming.</b> The evening a household moves into a house that is new to it (not the founders,
 *     who all move in together), its friends and neighbours call at the door with a small present: a flower,
 *     a loaf or a candle out of their own packs, else bought out of their own purses from the stores. The
 *     household comes out to meet them; a short gathering at the door, and everybody remembers it.</li>
 * </ul>
 *
 * <p>Each of these is an errand, a stop at a time ({@link Errand}): somewhere to go (a place, or a folk wherever
 * it is), what happens on getting there, and how long to stay (stood, or sat down). The poor box's errands go
 * the same way (PoorBox). An errand begins only in its folk's own time (its break, the evening, the day of rest)
 * and is let go if it cannot be got through; whoever goes with it (the newcomer on its tour, the old one sat with
 * its visitor, the household at its door) keeps it company. From each folk's tick: {@link #hold}; the town's
 * round every ten seconds: {@link #tick}.
 */
public final class Neighbourly {

    private Neighbourly() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** Old enough to be looked in on every day (the frail are, whatever their age). */
    public static final int VERY_OLD = 80;
    /** A visitor sits with the old one this long. */
    static final int SIT = 600;
    /** The old are looked in on from late morning (an errand that cannot begin before supper is let go). */
    static final long VISIT_FROM = 5000L, VISIT_TO = 13600L;
    /** A housewarming: after supper, before bed. */
    static final long WARM_FROM = 12300L, WARM_TO = 13800L;
    /** The most callers at a housewarming. */
    static final int CALLERS = 4;
    /** A neighbour: a house within so many blocks of the other's. */
    static final int NEIGHBOUR = 24;

    // ------------------------------------------------------------------ an errand, a stop at a time

    /** What happens on getting to a stop. */
    interface Arrive { void at(ServerLevel level, VillageFolkEntity f, Errand e); }

    /** What it sets out with (a gift bought, a meal fetched); false: it cannot. */
    interface Begin { boolean at(ServerLevel level, VillageFolkEntity f, Errand e); }

    /** When it is over: seen through ({@code done}) or let go. */
    interface End { void at(ServerLevel level, VillageFolkEntity f, Errand e, boolean done); }

    /** A stop on an errand: somewhere to go (a place, or a folk wherever it is), what to do there, how long to stay. */
    record Stop(String doing, @Nullable BlockPos place, @Nullable UUID folk, double near, int stay, boolean sit, @Nullable Arrive arrive) {}

    /** Something a folk has set out to do for a neighbour. */
    static final class Errand {
        final String kind;
        final UUID village;
        final long made;
        /** The hours of the day it may begin in, and the game time by which it must have. */
        long from = 1000L, to = 13800L, startBy;
        /** How long it may take once begun. */
        long longest = 3600L;
        final List<Stop> stops = new ArrayList<>();
        @Nullable Predicate<VillageFolkEntity> ready;
        @Nullable Begin begin;
        @Nullable End end;
        /** Walked round with it (a newcomer on its tour). */
        @Nullable UUID follower;
        final Map<String, Object> notes = new HashMap<>();
        int at;
        boolean begun, arrived;
        long since, stayUntil, progress;
        double best = Double.MAX_VALUE;
        int walkTick = -1000;

        Errand(String kind, UUID village, long made) {
            this.kind = kind;
            this.village = village;
            this.made = made;
            this.startBy = made + 12000L;
        }

        Errand stop(String doing, @Nullable BlockPos place, @Nullable UUID folk, double near, int stay, boolean sit, @Nullable Arrive arrive) {
            stops.add(new Stop(doing, place == null ? null : place.immutable(), folk, near, stay, sit, arrive));
            return this;
        }
    }

    /** Somebody keeping a folk's errand company: walked round with it, sat with it, or at its door. */
    static final class Company {
        final String role;
        final UUID with;
        @Nullable final BlockPos spot;
        final long until;
        final String doing;
        int walkTick = -1000;

        Company(String role, UUID with, @Nullable BlockPos spot, long until, String doing) {
            this.role = role;
            this.with = with;
            this.spot = spot == null ? null : spot.immutable();
            this.until = until;
            this.doing = doing;
        }
    }

    private static final Map<UUID, Errand> ERRANDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Company> COMPANY = new ConcurrentHashMap<>();
    /** When each folk was last held (its own day waits while it is). */
    private static final Map<UUID, Integer> HELD = new ConcurrentHashMap<>();
    /** Sat down for a visit (Park leaves it sat). */
    private static final java.util.Set<UUID> SEATED = ConcurrentHashMap.newKeySet();
    private static final Deque<String> SAID = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final Map<UUID, Long> TICKED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        ERRANDS.clear();
        COMPANY.clear();
        HELD.clear();
        SEATED.clear();
        SAID.clear();
        TICKED.clear();
        VISITED.clear();
        VISITS_LOOKED.clear();
        WELCOMES.clear();
        WARMINGS.clear();
        BOOKS.clear();
        PoorBox.resetForTests();
    }

    /** Give a folk an errand (its old one, if any, let go). */
    static void give(VillageFolkEntity f, Errand e) {
        Errand was = ERRANDS.put(f.getUUID(), e);
        if (was != null && was != e && f.level() instanceof ServerLevel level && was.end != null) was.end.at(level, f, was, false);
    }

    /** Has it an errand in hand (begun or waiting for its time)? */
    static boolean hasErrand(VillageFolkEntity f) {
        return ERRANDS.containsKey(f.getUUID());
    }

    /** Said out loud (FolkTalk), and kept for the tests. */
    static void say(VillageFolkEntity f, String text) {
        FolkTalk.speak(f, text);
        SAID.addLast(f.displayNameCap() + ": " + text);
        while (SAID.size() > 96) SAID.pollFirst();
    }

    public static List<String> saidForTests() {
        return new ArrayList<>(SAID);
    }

    /**
     * From the folk's tick (VillageFolkEntity.aiStep, every fourth tick): its errand for a neighbour, a stop at a
     * time; or keeping somebody's company. True while it is (its own day waits).
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (f.ownerId() == null || f.isShowcase() || f.isHired() || !f.isAlive()) return release(f);
        Errand e = ERRANDS.get(f.getUUID());
        if (e != null && step(level, f, e)) {
            HELD.put(f.getUUID(), f.tickCount);
            return true;
        }
        Company c = COMPANY.get(f.getUUID());
        if (c != null && keep(level, f, c)) {
            HELD.put(f.getUUID(), f.tickCount);
            return true;
        }
        return release(f);
    }

    /** Is it about a neighbour's business just now (between hold's looks)? (VillageFolkEntity.calledAway) */
    public static boolean busy(VillageFolkEntity f) {
        Integer t = HELD.get(f.getUUID());
        return t != null && f.tickCount >= t && f.tickCount - t <= 8;
    }

    /** Sat down for a visit: Park leaves it sat. */
    public static boolean seated(VillageFolkEntity f) {
        return SEATED.contains(f.getUUID());
    }

    /** What it is about, for its card, or null. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        if (!busy(f)) return null;
        Errand e = ERRANDS.get(f.getUUID());
        if (e != null && e.begun && e.at < e.stops.size()) return e.stops.get(e.at).doing();
        Company c = COMPANY.get(f.getUUID());
        return c == null ? null : c.doing;
    }

    private static boolean release(VillageFolkEntity f) {
        if (HELD.remove(f.getUUID()) != null) standUp(f);
        return false;
    }

    private static void sit(VillageFolkEntity f) {
        f.getNavigation().stop();
        if (f.getPose() != Pose.SITTING) f.setPose(Pose.SITTING);
        SEATED.add(f.getUUID());
    }

    private static void standUp(VillageFolkEntity f) {
        if (SEATED.remove(f.getUUID()) && f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
    }

    /** Free for a neighbour's errand: its own time, nothing else in hand, well, and awake. */
    static boolean free(VillageFolkEntity f) {
        if (f.isBaby() || !Families.free(f) || Families.busy(f) || Health.laidUp(f) || f.health().ill()) return false;
        return !Weather.sheltering(f) && !FireBrigade.onIt(f) && !Stables.busy(f) && f.trip() == null && f.expedition() == null
            && f.talkPartner() == null && f.companionPlayer() == null && f.guidePlayer() == null;
    }

    /** One step of an errand. True while it is under way (false: waiting for its time, or over). */
    static boolean step(ServerLevel level, VillageFolkEntity f, Errand e) {
        long now = level.getGameTime(), t = level.getDayTime() % 24000L;
        if (!e.begun) {
            if (now > e.startBy || now < e.made) {
                drop(level, f, e, false, "it never found the time");
                return false;
            }
            if (t < e.from || t >= e.to || !free(f) || e.ready != null && !e.ready.test(f)) return false;
            if (e.begin != null && !e.begin.at(level, f, e)) {
                drop(level, f, e, false, "it had nothing to set out with");
                return false;
            }
            e.begun = true;
            e.since = now;
            e.progress = now;
            if (f.peekJob() != null) f.clearQueue();
            f.getNavigation().stop();
        }
        if (now - e.since > e.longest || now < e.since) {
            drop(level, f, e, false, "it took too long");
            return false;
        }
        if (f.isSleeping() || Raids.underAlarm(e.village) || f.getTarget() != null || Weather.sheltering(f) || FireBrigade.onIt(f)) {
            drop(level, f, e, false, "called away");
            return false;
        }
        if (e.at >= e.stops.size()) {
            drop(level, f, e, true, null);
            return false;
        }
        Stop s = e.stops.get(e.at);
        VillageFolkEntity them = s.folk() == null ? null : level.getEntity(s.folk()) instanceof VillageFolkEntity o && o.isAlive() ? o : null;
        BlockPos to = s.folk() != null ? (them == null ? null : them.blockPosition()) : s.place();
        if (to == null) {
            next(f, e, now);                                     // the one it was going to is gone: on to the next stop
            return true;
        }
        f.hobbyNow = s.doing();
        f.lastLeisureTick = f.tickCount;
        if (e.follower != null && !COMPANY.containsKey(e.follower) && e.notes.containsKey("met")) {
            COMPANY.put(e.follower, new Company("follow", f.getUUID(), null, now + e.longest, "being shown round by " + f.displayNameCap()));
        }
        if (!e.arrived) {
            double dx = f.getX() - (to.getX() + 0.5), dz = f.getZ() - (to.getZ() + 0.5);
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d <= s.near() && Math.abs(f.getY() - to.getY()) <= 3.0) {
                e.arrived = true;
                e.stayUntil = now + s.stay();
                f.getNavigation().stop();
                if (them != null) f.getLookControl().setLookAt(them, 30.0F, 30.0F);
                if (s.sit()) sit(f);
                if (s.arrive() != null) s.arrive().at(level, f, e);
                return true;
            }
            if (d < e.best - 0.75) {
                e.best = d;
                e.progress = now;
            } else if (now - e.progress > 600L || now < e.progress) {
                LOG.info("[MCA-NEIGHBOURS] {} could not get to {} ({}): on to the next", f.displayNameCap(), to.toShortString(), s.doing());
                next(f, e, now);
                return true;
            }
            int every = them != null ? 20 : 50;
            if (f.getNavigation().isDone() || f.tickCount - e.walkTick > every || f.tickCount < e.walkTick) {
                f.walkTo(to, 0.9D);
                e.walkTick = f.tickCount;
            }
            return true;
        }
        if (them != null && f.tickCount % 10 == 0) f.getLookControl().setLookAt(them, 30.0F, 30.0F);
        if (now < e.stayUntil) {
            if (s.sit() && f.getPose() != Pose.SITTING) sit(f);
            return true;
        }
        next(f, e, now);
        return true;
    }

    private static void next(VillageFolkEntity f, Errand e, long now) {
        standUp(f);
        e.at++;
        e.arrived = false;
        e.best = Double.MAX_VALUE;
        e.progress = now;
        e.walkTick = -1000;
    }

    /** The errand over: seen through, or let go (and whoever kept it company let go too). */
    static void drop(ServerLevel level, VillageFolkEntity f, Errand e, boolean done, @Nullable String why) {
        ERRANDS.remove(f.getUUID(), e);
        standUp(f);
        if (e.follower != null) {
            Company c = COMPANY.get(e.follower);
            if (c != null && c.with.equals(f.getUUID())) COMPANY.remove(e.follower, c);
        }
        if (e.end != null) e.end.at(level, f, e, done);
        if (why != null) LOG.info("[MCA-NEIGHBOURS] {} let its {} errand go: {}", f.displayNameCap(), e.kind, why);
    }

    /** Keeping somebody's company: walked round after it, sat with it, or stood at the door. False once it is over. */
    static boolean keep(ServerLevel level, VillageFolkEntity f, Company c) {
        long now = level.getGameTime();
        VillageFolkEntity with = level.getEntity(c.with) instanceof VillageFolkEntity o && o.isAlive() ? o : null;
        boolean over = now > c.until || f.isSleeping() || Raids.underAlarm(f.ownerId()) || f.getTarget() != null || Health.laidUp(f);
        if (c.role.equals("follow")) {
            Errand e = with == null ? null : ERRANDS.get(with.getUUID());
            over |= e == null || !e.begun;
        } else if (c.role.equals("sit")) {
            over |= with == null;
        }
        if (over) {
            COMPANY.remove(f.getUUID(), c);
            standUp(f);
            return false;
        }
        f.hobbyNow = c.doing;
        f.lastLeisureTick = f.tickCount;
        if (f.peekJob() != null) f.clearQueue();
        switch (c.role) {
            case "follow" -> {
                if (f.distanceToSqr(with) > 3.0 * 3.0) {
                    if (f.getNavigation().isDone() || f.tickCount - c.walkTick > 20 || f.tickCount < c.walkTick) {
                        f.walkTo(with.blockPosition(), 1.0D);
                        c.walkTick = f.tickCount;
                    }
                } else {
                    f.getNavigation().stop();
                    if (f.tickCount % 10 == 0) f.getLookControl().setLookAt(with, 30.0F, 30.0F);
                }
            }
            case "sit" -> {
                sit(f);
                if (f.tickCount % 10 == 0) f.getLookControl().setLookAt(with, 30.0F, 30.0F);
            }
            default -> {                                                    // a host at its door
                BlockPos spot = c.spot;
                if (spot != null && f.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5) > 2.0 * 2.0) {
                    if (f.getNavigation().isDone() || f.tickCount - c.walkTick > 40 || f.tickCount < c.walkTick) {
                        f.walkTo(spot, 0.9D);
                        c.walkTick = f.tickCount;
                    }
                } else {
                    f.getNavigation().stop();
                    if (with != null && f.tickCount % 10 == 0) f.getLookControl().setLookAt(with, 30.0F, 30.0F);
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ the town's round

    /** The day's neighbourliness in a town, for its books. */
    static final class Book {
        long day = -1;
        int visits, meals, welcomes, warmings, callers;
        String last = "";
    }

    private static final Map<UUID, Book> BOOKS = new ConcurrentHashMap<>();

    static Book book(UUID village, long day) {
        Book b = BOOKS.computeIfAbsent(village, k -> new Book());
        if (b.day != day) {
            b.day = day;
            b.visits = b.meals = b.welcomes = b.warmings = b.callers = 0;
        }
        return b;
    }

    /**
     * Every ten seconds or so for each village (a folk's agenda): the old looked in on, newcomers welcomed,
     * housewarmings called at, and the poor box (PoorBox).
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = TICKED.get(id);
        if (last != null && now - last < 200L && now >= last) return;
        TICKED.put(id, now);
        // Errands and company left behind by folk who are gone (dead, moved away, unloaded for good) let go of.
        ERRANDS.values().removeIf(e -> now - e.made > 48000L || now < e.made);
        COMPANY.values().removeIf(c -> now > c.until + 1200L);
        if (Raids.underAlarm(id)) return;
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        if (t >= VISIT_FROM && t < VISIT_TO - 2000L && VISITS_LOOKED.getOrDefault(id, Long.MIN_VALUE) != day) {
            VISITS_LOOKED.put(id, day);
            visits(level, v, day);
        }
        welcomes(level, v, day);
        housewarmings(level, v, day, t);
        PoorBox.tick(level, v, day, t);
    }

    // ------------------------------------------------------------------ the old looked after

    /** The day each old one was last looked in on (by whoever was sent). */
    private static final Map<UUID, Long> VISITED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> VISITS_LOOKED = new ConcurrentHashMap<>();

    /** Very old (eighty and on), or frail (in its last few years): looked in on every day. */
    public static boolean frail(VillageFolkEntity f) {
        if (f.isBaby()) return false;
        int age = f.ageYears();
        return age >= VERY_OLD || age >= f.lifespan() - 4;
    }

    /** Once a day: a visitor for each of the town's very old and frail. Returns how many were sent. */
    static int visits(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && !f.isShowcase() && !f.isHired()) folk.add(f);
        }
        int sent = 0;
        for (VillageFolkEntity old : folk) {
            if (!frail(old) || VISITED.getOrDefault(old.getUUID(), Long.MIN_VALUE) == day) continue;
            VillageFolkEntity who = visitorFor(id, old, folk);
            if (who == null) continue;
            give(who, visit(level, v, who, old, day));
            VISITED.put(old.getUUID(), day);
            sent++;
            LOG.info("[MCA-NEIGHBOURS] {} is to look in on {} ({}), day {}", who.displayNameCap(), old.displayNameCap(), old.ageYears(), day);
        }
        return sent;
    }

    /** Who looks in on the old one: its family first, then a friend, then a neighbour on good terms; none of them old themselves. */
    @Nullable
    static VillageFolkEntity visitorFor(UUID village, VillageFolkEntity old, List<VillageFolkEntity> folk) {
        Homes.Home home = Homes.homeOf(village, old.getUUID());
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (VillageFolkEntity c : folk) {
            if (c == old || c.isBaby() || frail(c) || c.health().ill() || hasErrand(c)) continue;
            int feel = Math.max(c.life().affinity(old.getUUID()), old.life().affinity(c.getUUID()));
            int score;
            if (old.getUUID().equals(c.life().partner()) || Homes.childOf(c, old) || Homes.childOf(old, c)) score = 200 + feel;
            else if (feel >= Social.FRIEND) score = 100 + feel;
            else {
                Homes.Home theirs = Homes.homeOf(village, c.getUUID());
                boolean near = home != null && theirs != null && theirs != home && theirs.anchor.closerThan(home.anchor, NEIGHBOUR);
                if (!near || feel < 0) continue;
                score = 50 + feel;
            }
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    /** The visit: a meal out of the stores, carried over to the old one, handed over, and a while sat with it. */
    static Errand visit(ServerLevel level, Villages.Village v, VillageFolkEntity who, VillageFolkEntity old, long day) {
        String name = old.displayNameCap();
        UUID oldId = old.getUUID();
        Errand e = new Errand("visit", v.id(), level.getGameTime());
        e.from = VISIT_FROM;
        e.to = VISIT_TO;
        e.longest = 3000L;
        e.ready = f -> level.getEntity(oldId) instanceof VillageFolkEntity o && o.isAlive() && (!o.isSleeping() || Health.laidUp(o))
            && (o.offWorkNow() || Health.laidUp(o)) && o.distanceToSqr(f) < 96.0 * 96.0;
        e.stop("fetching " + name + "'s supper from the stores", storesSpot(level, v), null, 3.5, 20, false, (lv, f, er) -> {
            ItemStack meal = meal(lv, v, old);
            if (meal.isEmpty()) return;
            ItemStack left = f.insertGiven(meal.copy());
            if (!left.isEmpty()) {
                Crafts.store(lv, v, left);
                return;
            }
            er.notes.put("meal", meal.copy());
        });
        e.stop("sitting with " + name, null, oldId, 2.2, SIT, true, (lv, f, er) -> {
            if (!(lv.getEntity(oldId) instanceof VillageFolkEntity o)) return;
            RandomSource r = f.getRandom();
            ItemStack meal = er.notes.get("meal") instanceof ItemStack m ? m : ItemStack.EMPTY;
            String what = meal.isEmpty() ? "" : Birthdays.a(meal);
            if (!meal.isEmpty() && f.removeMatching(s -> ItemStack.isSameItemSameComponents(s, meal), 1) == 1) {
                ItemStack left = o.insertGiven(meal.copy());
                if (!left.isEmpty()) Crafts.store(lv, v, left);
                er.notes.put("given", what);
                book(v.id(), day).meals++;
            }
            if (!o.isSleeping() && !Health.laidUp(o)) {
                COMPANY.put(oldId, new Company("sit", f.getUUID(), null, er.stayUntil, "sat with " + f.displayNameCap()));
            }
            say(f, what.isEmpty() ? FolkTalk.pick(r, "The stores were bare, " + o.displayNameCap() + ", but I've come to sit with you.",
                    "Nothing to bring today, I'm afraid. How are you keeping?")
                : FolkTalk.pick(r, "I've brought your supper, " + o.displayNameCap() + " — " + what + ". How are you keeping?",
                    "Supper, " + o.displayNameCap() + "! " + capFirst(what) + ", fresh from the stores.", "Here you are: " + what + ". Shall I sit a while?"));
            o.sayLater(FolkTalk.pick(r, "Bless you, " + f.displayNameCap() + ". Sit down, sit down.", "Oh, you're good to an old one.",
                "Come in, come in. Tell me the news."), 40);
            lv.sendParticles(ParticleTypes.HEART, o.getX(), o.getEyeY() + 0.4, o.getZ(), 2, 0.3, 0.2, 0.3, 0.0);
        });
        e.end = (lv, f, er, done) -> {
            VillageFolkEntity o = lv.getEntity(oldId) instanceof VillageFolkEntity x && x.isAlive() ? x : null;
            ItemStack meal = er.notes.get("meal") instanceof ItemStack m ? m : ItemStack.EMPTY;
            if (!er.notes.containsKey("given") && !meal.isEmpty() && f.removeMatching(s -> ItemStack.isSameItemSameComponents(s, meal), 1) == 1) {
                Crafts.store(lv, v, meal.copy());                            // never handed over: back it goes
            }
            if (o != null) {
                Company c = COMPANY.get(oldId);
                if (c != null && c.with.equals(f.getUUID())) {
                    COMPANY.remove(oldId, c);
                    standUp(o);
                }
            }
            if (!done || o == null || er.at < 2) return;
            Object given = er.notes.get("given");
            o.persona().remember(day, f.displayNameCap() + " came round" + (given == null ? "" : " with my supper") + " and sat with me a while", 4);
            f.persona().remember(day, "I took " + o.displayNameCap() + (given == null ? " some company" : " " + given + " for supper") + " and sat with them", 3);
            o.life().feel(f.getUUID(), f.displayNameCap(), 5);
            f.life().feel(o.getUUID(), o.displayNameCap(), 4);
            Book b = book(v.id(), day);
            b.visits++;
            b.last = f.displayNameCap() + " sat with " + o.displayNameCap() + (given == null ? "" : ", and brought " + given);
            say(f, FolkTalk.pick(f.getRandom(), "I'll look in again tomorrow, " + o.displayNameCap() + ".", "Mind you eat that, now. Goodnight!"));
            LOG.info("[MCA-NEIGHBOURS] {} sat with {}{}", f.displayNameCap(), o.displayNameCap(), given == null ? "" : ", bringing " + given);
        };
        return e;
    }

    /**
     * A meal for the old one out of the stores: its favourite if there is one, else a proper dish (bread, something
     * baked or cooked), and only with none of those a ration as it comes. A raw carrot out of the farmers' seed is no
     * supper to carry round to anybody, and the founding stores' carrots came first in the chest.
     */
    static ItemStack meal(ServerLevel level, Villages.Village v, VillageFolkEntity old) {
        String fav = old.persona().food();
        ItemStack got = Crafts.takeOne(level, v, s -> s.get(DataComponents.FOOD) != null && Meals.FOOD.test(s)
            && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(fav));
        if (got.isEmpty()) got = Crafts.takeOne(level, v, s -> Meals.FOOD.test(s) && dish(s));
        if (got.isEmpty()) got = Crafts.takeOne(level, v, Meals.FOOD);
        return got;
    }

    /** A proper dish: bread, or something baked, cooked or made (not a raw crop or raw meat). */
    static boolean dish(ItemStack s) {
        if (s.is(Items.BREAD) || s.is(Items.BAKED_POTATO) || s.is(Items.PUMPKIN_PIE) || s.is(Items.COOKIE) || s.is(Items.CAKE)
            || s.is(Items.MUSHROOM_STEW) || s.is(Items.RABBIT_STEW) || s.is(Items.BEETROOT_SOUP)) return true;
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        return path.startsWith("cooked_");
    }

    /** Where the stores are, to walk to: the storehouse's door, the first store built, a store chest, the heart. */
    static BlockPos storesSpot(ServerLevel level, Villages.Village v) {
        BlockPos door = Storehouses.doorFor(level, v.id());
        if (door != null) return door;
        BlockPos shed = Villages.builtAt(v.id(), "storage");
        if (shed != null) return shed;
        List<BlockPos> chests = Villages.storeChests(level, v.id());
        return chests.isEmpty() ? v.centre() : chests.get(0);
    }

    static String capFirst(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the welcome committee

    /** A newcomer to be welcomed: its town, the day it came, how, and whether somebody has been sent. */
    static final class Welcome {
        final UUID village;
        final long day;
        final String how;
        boolean sent, done;

        Welcome(UUID village, long day, String how) {
            this.village = village;
            this.day = day;
            this.how = how;
        }
    }

    private static final Map<UUID, Welcome> WELCOMES = new ConcurrentHashMap<>();

    /**
     * A newcomer is here (JobMarket.arrived, VillageFolkSpawnerBlock.raise, VillagerTakeover): it is to be welcomed
     * within the day. A child comes with its family, and is shown round with them.
     */
    public static void arrived(ServerLevel level, UUID village, VillageFolkEntity f, String how) {
        if (f.isBaby() || f.isShowcase() || f.isHired()) return;
        WELCOMES.put(f.getUUID(), new Welcome(village, level.getDayTime() / 24000L, how));
        LOG.info("[MCA-NEIGHBOURS] {} came to {} ({}): to be welcomed", f.displayNameCap(), Villages.name(village), how);
    }

    /** Somebody sent to welcome each newcomer not yet seen to; a welcome given up after a day and a half. */
    static void welcomes(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        for (Map.Entry<UUID, Welcome> en : List.copyOf(WELCOMES.entrySet())) {
            Welcome w = en.getValue();
            if (!w.village.equals(id)) continue;
            VillageFolkEntity n = level.getEntity(en.getKey()) instanceof VillageFolkEntity o && o.isAlive() ? o : null;
            if (n == null || !id.equals(n.ownerId()) || day - w.day > 1) {
                WELCOMES.remove(en.getKey(), w);                    // gone, or the day and a half over (done or not)
                continue;
            }
            if (w.sent || w.done) continue;
            VillageFolkEntity g = greeter(level, id, n);
            if (g == null) continue;
            give(g, welcome(level, v, g, n, w, day));
            w.sent = true;
            LOG.info("[MCA-NEIGHBOURS] {} is to welcome {} to {}", g.displayNameCap(), n.displayNameCap(), Villages.name(id));
        }
    }

    /** The elder, if it can; else the friendliest folk in the town (never another newcomer, never one ill). */
    @Nullable
    static VillageFolkEntity greeter(ServerLevel level, UUID village, VillageFolkEntity newcomer) {
        UUID elder = Villages.elder(village);
        if (elder != null && level.getEntity(elder) instanceof VillageFolkEntity e && fitToGreet(e, newcomer)) return e;
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || !fitToGreet(f, newcomer)) continue;
            Social.Life l = f.life();
            int score = l.friends().size() * 5 + (l.has(Social.Trait.SOCIABLE) ? 40 : 0) + (l.has(Social.Trait.CHEERFUL) ? 15 : 0)
                + (l.has(Social.Trait.GENEROUS) ? 10 : 0) - (l.has(Social.Trait.SHY) ? 30 : 0) - (l.has(Social.Trait.GRUMPY) ? 20 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = f;
            }
        }
        return best;
    }

    private static boolean fitToGreet(VillageFolkEntity f, VillageFolkEntity newcomer) {
        return f != newcomer && f.isAlive() && !f.isBaby() && !f.isShowcase() && !f.isHired() && !f.health().ill() && !hasErrand(f)
            && !WELCOMES.containsKey(f.getUUID()) && f.distanceToSqr(newcomer) < 128.0 * 128.0;
    }

    /** The welcome: the newcomer found and greeted, then walked round the heart, the stores and its new home. */
    static Errand welcome(ServerLevel level, Villages.Village v, VillageFolkEntity g, VillageFolkEntity n, Welcome w, long day) {
        UUID nid = n.getUUID();
        String name = n.displayNameCap(), town = Villages.name(v.id());
        Errand e = new Errand("welcome", v.id(), level.getGameTime());
        e.from = 1000L;
        e.to = 13800L;
        e.startBy = level.getGameTime() + 30000L;
        e.longest = 3600L;
        e.follower = nid;
        e.ready = f -> level.getEntity(nid) instanceof VillageFolkEntity o && o.isAlive() && !o.isSleeping() && !JobSeekers.busy(o)
            && !Health.laidUp(o) && o.distanceToSqr(f) < 96.0 * 96.0;
        e.stop("welcoming " + name + " to " + town, null, nid, 2.5, 60, false, (lv, f, er) -> {
            er.notes.put("met", Boolean.TRUE);
            RandomSource r = f.getRandom();
            say(f, f.isElder() ? FolkTalk.pick(r, "Welcome to " + town + ", " + name + "! I'm " + f.displayNameCap() + " — I look after the place. Come, I'll show you round.",
                    "You'll be " + name + ". Welcome! Walk with me, and I'll show you " + town + ".")
                : FolkTalk.pick(r, "Hello! You must be " + name + ". Welcome to " + town + " — let me show you round.",
                    "Welcome, welcome! I'm " + f.displayNameCap() + ". Come and see the place."));
            if (lv.getEntity(nid) instanceof VillageFolkEntity o) {
                o.sayLater(FolkTalk.pick(r, "Thank you! I'd like that.", "That's kind of you. Lead on.", "Hello! Yes, please."), 50);
                COMPANY.put(nid, new Company("follow", f.getUUID(), null, lv.getGameTime() + er.longest, "being shown round by " + f.displayNameCap()));
            }
        });
        e.stop("showing " + name + " the heart of " + town, v.centre(), null, 4.0, 60, false, (lv, f, er) -> {
            er.notes.put("heart", Boolean.TRUE);
            say(f, FolkTalk.pick(f.getRandom(), "This is the heart of the town. The board's here: everything that's going on is on it.",
                "Here's the square. We gather here of a morning, and the news is read at noon."));
        });
        e.stop("showing " + name + " the stores", storesSpot(level, v), null, 4.0, 80, false, (lv, f, er) -> {
            er.notes.put("stores", Boolean.TRUE);
            List<String> basket = basket(lv, v, f, nid);
            er.notes.put("basket", basket);
            say(f, basket.isEmpty() ? "And these are the stores. Bare just now, I'm afraid — but what's here is everybody's."
                : "And these are the stores. Here — a welcome basket: " + JobMarket.join(basket) + ".");
            if (!basket.isEmpty() && lv.getEntity(nid) instanceof VillageFolkEntity o) {
                o.sayLater(FolkTalk.pick(f.getRandom(), "For me? That's very kind.", "Thank you! What a welcome."), 40);
            }
        });
        BlockPos home = homeOf(level, v.id(), n);
        if (home != null) {
            e.stop("showing " + name + " its new home", home, null, 4.0, 60, false, (lv, f, er) -> {
                er.notes.put("home", Boolean.TRUE);
                say(f, FolkTalk.pick(f.getRandom(), "And this is where you'll live. I hope you'll be happy here.", "Here's your new home, " + name + ". Settle in."));
            });
        }
        e.end = (lv, f, er, done) -> {
            Company c = COMPANY.get(nid);
            if (c != null && c.with.equals(f.getUUID())) COMPANY.remove(nid, c);
            if (!er.notes.containsKey("met")) {
                if (!er.begun) w.sent = false;                         // never begun: somebody else, while the day lasts
                return;
            }
            w.done = true;
            VillageFolkEntity o = lv.getEntity(nid) instanceof VillageFolkEntity x && x.isAlive() ? x : null;
            if (o == null) return;
            long d = lv.getDayTime() / 24000L;
            o.persona().remember(d, f.displayNameCap() + " welcomed me to " + town + " and showed me round", 6);
            f.persona().remember(d, "I welcomed " + o.displayNameCap() + " to " + town + " and showed them round", 3);
            o.life().feel(f.getUUID(), f.displayNameCap(), 8);
            f.life().feel(nid, o.displayNameCap(), 5);
            Book b = book(v.id(), d);
            b.welcomes++;
            b.last = f.displayNameCap() + " welcomed " + o.displayNameCap() + " and showed them round";
            LOG.info("[MCA-NEIGHBOURS] {} welcomed {} ({}), basket {}", f.displayNameCap(), o.displayNameCap(), w.how, er.notes.get("basket"));
        };
        return e;
    }

    /** The newcomer's new home: its house's middle, else its bed; null for none yet. */
    @Nullable
    static BlockPos homeOf(ServerLevel level, UUID village, VillageFolkEntity f) {
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        if (h != null) return h.anchor;
        return f.bedPos();
    }

    /** The welcome basket, out of the stores: a loaf, a torch and a flower, whatever of them there is. Returns what. */
    static List<String> basket(ServerLevel level, Villages.Village v, VillageFolkEntity g, UUID newcomer) {
        List<String> got = new ArrayList<>();
        if (!(level.getEntity(newcomer) instanceof VillageFolkEntity n)) return got;
        List<Predicate<ItemStack>> wants = List.of(s -> s.is(Items.BREAD), s -> s.is(Items.TORCH), s -> s.is(ItemTags.SMALL_FLOWERS));
        for (Predicate<ItemStack> want : wants) {
            ItemStack one = Crafts.takeOne(level, v, want);
            if (one.isEmpty()) continue;
            if (one.get(DataComponents.FOOD) == null) Homes.keepsake(one, n);       // its own, not the stores' again
            ItemStack left = n.insertGiven(one.copy());
            if (!left.isEmpty()) {
                Crafts.store(level, v, left);
                continue;
            }
            got.add(Birthdays.a(one));
        }
        return got;
    }

    // ------------------------------------------------------------------ the housewarming

    /** A household's housewarming: its new house, its door, who lives there, the evening, and who came. */
    static final class Warming {
        final UUID village;
        final BlockPos house;
        final List<UUID> household;
        final String names;
        final long day;
        @Nullable BlockPos door;
        boolean called, over;
        long until;
        final List<UUID> callers = new ArrayList<>();
        final List<String> came = new ArrayList<>();

        Warming(UUID village, BlockPos house, List<UUID> household, String names, long day) {
            this.village = village;
            this.house = house.immutable();
            this.household = household;
            this.names = names;
            this.day = day;
        }
    }

    private static final Map<String, Warming> WARMINGS = new ConcurrentHashMap<>();

    /**
     * A household has moved into a house (Homes.settle): its housewarming is this evening (or tomorrow's, if this
     * evening is over). Not the founders, who all move in together and have nobody to call.
     */
    public static void movedIn(ServerLevel level, UUID village, BlockPos house, List<VillageFolkEntity> household) {
        List<UUID> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        boolean founders = true;
        for (VillageFolkEntity f : household) {
            ids.add(f.getUUID());
            if (f.isBaby()) continue;
            names.add(f.displayNameCap());
            if (!f.rentFree()) founders = false;
        }
        if (names.isEmpty() || founders) return;
        long dt = level.getDayTime(), day = dt / 24000L + (dt % 24000L >= WARM_TO ? 1 : 0);
        WARMINGS.put(village + "/" + house.asLong(), new Warming(village, house, ids, JobMarket.join(names), day));
        LOG.info("[MCA-NEIGHBOURS] {} moved into {}: a housewarming on day {}", names, house.toShortString(), day);
    }

    /** This evening's housewarmings: callers sent, and when they have been, everybody remembers it. */
    static void housewarmings(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        long now = level.getGameTime();
        for (Map.Entry<String, Warming> en : List.copyOf(WARMINGS.entrySet())) {
            Warming w = en.getValue();
            if (!w.village.equals(id)) continue;
            if (w.over || day > w.day + 1) {
                WARMINGS.remove(en.getKey(), w);
                continue;
            }
            if (!w.called) {
                if (day == w.day && t >= WARM_FROM && t < WARM_TO) call(level, v, w);
                else if (day > w.day) w.over = true;
                continue;
            }
            boolean anyLeft = false;
            for (UUID c : w.callers) {
                Errand e = ERRANDS.get(c);
                if (e != null && e.kind.equals("housewarming")) anyLeft = true;
            }
            if (!anyLeft || now > w.until) finish(level, v, w, day);
        }
    }

    /** The callers sent (its friends, and the neighbours on good terms), and the household to its door. Returns how many were sent. */
    static int call(ServerLevel level, Villages.Village v, Warming w) {
        UUID id = v.id();
        long now = level.getGameTime();
        w.called = true;
        w.until = now + (WARM_TO - WARM_FROM) + 200L;
        w.door = doorSpot(level, id, w.house);
        List<VillageFolkEntity> hosts = new ArrayList<>();
        for (UUID u : w.household) if (level.getEntity(u) instanceof VillageFolkEntity h && h.isAlive()) hosts.add(h);
        if (hosts.isEmpty()) {
            w.over = true;
            return 0;
        }
        // Who calls: the fondest of the household first, then the neighbours.
        List<Object[]> scored = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity c) || !c.isAlive() || c.isBaby() || c.isShowcase() || c.isHired()) continue;
            if (w.household.contains(c.getUUID()) || c.health().ill() || hasErrand(c)) continue;
            int feel = Integer.MIN_VALUE;
            for (VillageFolkEntity h : hosts) feel = Math.max(feel, Math.max(c.life().affinity(h.getUUID()), h.life().affinity(c.getUUID())));
            Homes.Home theirs = Homes.homeOf(id, c.getUUID());
            boolean near = theirs != null && !theirs.anchor.equals(w.house) && theirs.anchor.closerThan(w.house, NEIGHBOUR);
            int score = feel >= Social.FRIEND ? 100 + feel : near && feel >= 0 ? 50 + feel : Integer.MIN_VALUE;
            if (score == Integer.MIN_VALUE) continue;
            scored.add(new Object[]{ c, score });
        }
        scored.sort((p, q) -> Integer.compare((Integer) q[1], (Integer) p[1]));
        for (int i = 0; i < scored.size() && w.callers.size() < CALLERS; i++) {
            VillageFolkEntity c = (VillageFolkEntity) scored.get(i)[0];
            give(c, caller(level, v, c, w));
            w.callers.add(c.getUUID());
        }
        if (w.callers.isEmpty()) {
            w.over = true;
            return 0;
        }
        for (VillageFolkEntity h : hosts) {
            if (h.isSleeping() || Health.laidUp(h)) continue;
            COMPANY.put(h.getUUID(), new Company("host", w.callers.get(0), w.door, w.until, "at the door of its new house, for the housewarming"));
        }
        say(hosts.get(0), FolkTalk.pick(hosts.get(0).getRandom(), "I think we've visitors! Come on — to the door.", "Somebody's coming to see the new house!"));
        LOG.info("[MCA-NEIGHBOURS] the housewarming at {}: {} calling", w.house.toShortString(), w.callers.size());
        return w.callers.size();
    }

    /** One caller: a present in hand (its own, or bought), to the door, and handed over. */
    static Errand caller(ServerLevel level, Villages.Village v, VillageFolkEntity c, Warming w) {
        Errand e = new Errand("housewarming", v.id(), level.getGameTime());
        e.from = WARM_FROM;
        e.to = WARM_TO;
        e.startBy = w.until - 300L;
        e.longest = 1500L;
        e.begin = (lv, f, er) -> {
            ItemStack gift = fromPack(f);
            boolean bought = false;
            if (gift.isEmpty()) {
                gift = buy(lv, v, f);
                bought = !gift.isEmpty();
            }
            if (!gift.isEmpty()) {
                ItemStack left = f.insertGiven(gift.copy());
                if (!left.isEmpty()) {
                    Crafts.store(lv, v, left);
                    gift = ItemStack.EMPTY;
                }
            }
            er.notes.put("gift", gift.copy());
            er.notes.put("bought", bought);
            return true;
        };
        BlockPos door = w.door == null ? w.house : w.door;
        e.stop("calling at " + w.names + "'s new house for the housewarming", door, null, 3.0, 300, false, (lv, f, er) -> present(lv, v, f, er, w));
        return e;
    }

    /** At the door: the present handed to whoever of the household is there, else left in the house's chest. */
    static void present(ServerLevel level, Villages.Village v, VillageFolkEntity f, Errand e, Warming w) {
        RandomSource r = f.getRandom();
        ItemStack gift = e.notes.get("gift") instanceof ItemStack g ? g : ItemStack.EMPTY;
        VillageFolkEntity host = null;
        double best = 8.0 * 8.0;
        for (UUID u : w.household) {
            if (level.getEntity(u) instanceof VillageFolkEntity h && h.isAlive() && !h.isBaby() && h.distanceToSqr(f) < best) {
                best = h.distanceToSqr(f);
                host = h;
            }
        }
        String what = gift.isEmpty() ? "" : Birthdays.a(gift);
        if (!gift.isEmpty() && f.removeMatching(s -> ItemStack.isSameItemSameComponents(s, gift), 1) == 1) {
            ItemStack one = gift.copyWithCount(1);
            boolean food = one.get(DataComponents.FOOD) != null;
            ItemStack left = one;
            if (host != null) {
                if (!food) Homes.keepsake(one, host);
                left = host.insertGiven(one);
                host.persona().gotAGift(level.getDayTime() / 24000L, f.displayNameCap());
            }
            if (!left.isEmpty()) {
                Homes.Home h = Homes.homes(v.id()).get(w.house.asLong());
                BlockPos chest = h == null ? null : Homes.chestOf(level, v.id(), h);
                if (chest != null && level.getBlockEntity(chest) instanceof Container box) {
                    left = Homes.insertInto(box, left);
                    box.setChanged();
                }
                if (!left.isEmpty()) Crafts.store(level, v, left);
            }
        }
        w.came.add(f.displayNameCap() + (what.isEmpty() ? "" : " (" + what + ")"));
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        say(f, what.isEmpty() ? FolkTalk.pick(r, "Congratulations on the new house! I've come empty-handed, but with every good wish.",
                "Welcome to the street! May you be happy here.")
            : FolkTalk.pick(r, "A little something for the new house: " + what + ".", "Congratulations! I've brought you " + what + ".",
                "Here — " + what + ", to make it feel like home."));
        if (host != null) {
            host.sayLater(FolkTalk.pick(r, "Thank you, " + f.displayNameCap() + "! Come in, come in — well, look round, anyway.",
                "How kind! Thank you for coming.", "Oh, you shouldn't have! Thank you."), 40);
            level.sendParticles(ParticleTypes.HEART, host.getX(), host.getEyeY() + 0.4, host.getZ(), 2, 0.3, 0.2, 0.3, 0.0);
        }
    }

    /**
     * A present out of its own pack, the best it has: a flower, else a candle, else a loaf (not out of its last
     * rations); never a keepsake. (Every founder carries a stack of bread from its starter kit: a flower it picked
     * is the better present, and used to be passed over for the first loaf in the pack.)
     */
    static ItemStack fromPack(VillageFolkEntity f) {
        var pack = f.getInventoryItems();
        int food = f.countFood();
        for (Predicate<ItemStack> kind : List.<Predicate<ItemStack>>of(s -> s.is(ItemTags.SMALL_FLOWERS), s -> s.is(ItemTags.CANDLES),
                s -> s.is(Items.BREAD) && food > 3)) {
            for (int i = 0; i < pack.size(); i++) {
                ItemStack s = pack.get(i);
                if (s.isEmpty() || Homes.isKeepsake(s) || !kind.test(s)) continue;
                ItemStack one = s.split(1);
                if (s.isEmpty()) pack.set(i, ItemStack.EMPTY);
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    /** A present bought from the stores out of its own purse, at the market's price (the coin into the treasury). */
    static ItemStack buy(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (f.purse() < 1) return ItemStack.EMPTY;
        for (Predicate<ItemStack> want : List.<Predicate<ItemStack>>of(s -> s.is(ItemTags.SMALL_FLOWERS), s -> s.is(ItemTags.CANDLES),
                s -> s.is(Items.BREAD))) {
            if (Market.stock(level, v.id(), want) <= 0) continue;
            ItemStack got = Crafts.takeOne(level, v, want);
            if (got.isEmpty()) continue;
            Market.Good g = Market.goodFor(got);
            int price = g == null ? 1 : Market.sellPrice(g, Market.stock(level, v.id(), s -> ItemStack.isSameItemSameComponents(s, got)), false);
            price = FolkSkills.thrifty(f, Math.max(1, price / Math.max(1, g == null ? 1 : g.bundle())));
            if (!f.spend(price)) {
                Crafts.store(level, v, got);
                return ItemStack.EMPTY;
            }
            Ledger.addCoins(v.id(), price);
            Economy.spentInTown(v.id(), price);
            Stockroom.sold(level, v.id(), Cafe.open(v.id(), "shop") ? Stockroom.Seller.SHOP : Stockroom.Seller.STORES, got, 1, price);
            return got;
        }
        return ItemStack.EMPTY;
    }

    /** The evening over: everybody who was there remembers it, and the household and its callers are the fonder of each other. */
    static void finish(ServerLevel level, Villages.Village v, Warming w, long day) {
        w.over = true;
        for (UUID u : w.household) {
            Company c = COMPANY.get(u);
            if (c != null && c.role.equals("host")) COMPANY.remove(u, c);
        }
        if (w.came.isEmpty()) return;
        List<VillageFolkEntity> hosts = new ArrayList<>(), callers = new ArrayList<>();
        for (UUID u : w.household) if (level.getEntity(u) instanceof VillageFolkEntity h && h.isAlive()) hosts.add(h);
        for (UUID u : w.callers) {
            if (!(level.getEntity(u) instanceof VillageFolkEntity c) || !c.isAlive()) continue;
            boolean came = false;
            for (String s : w.came) if (s.startsWith(c.displayNameCap())) came = true;
            if (came) callers.add(c);
        }
        List<String> callerNames = new ArrayList<>();
        for (VillageFolkEntity c : callers) callerNames.add(c.displayNameCap());
        for (VillageFolkEntity h : hosts) {
            h.persona().remember(day, "our housewarming: " + JobMarket.join(callerNames) + " came round to see the new house", 5);
            for (VillageFolkEntity c : callers) {
                h.life().feel(c.getUUID(), c.displayNameCap(), 4);
                c.life().feel(h.getUUID(), h.displayNameCap(), 3);
            }
        }
        for (VillageFolkEntity c : callers) c.persona().remember(day, "I went round to " + w.names + "'s housewarming", 3);
        Book b = book(v.id(), day);
        b.warmings++;
        b.callers += callers.size();
        b.last = "the housewarming at " + w.names + "'s: " + String.join(", ", w.came);
        LOG.info("[MCA-NEIGHBOURS] the housewarming at {}'s is over: {}", w.names, w.came);
    }

    /** Where callers stand: just outside the house's door, else its middle. */
    static BlockPos doorSpot(ServerLevel level, UUID village, BlockPos house) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(house.offset(-5, -1, -5), house.offset(5, 2, 5))) {
            BlockState st = level.getBlockState(p);
            if (!(st.getBlock() instanceof DoorBlock) || st.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) continue;
            Direction d = st.getValue(DoorBlock.FACING);
            BlockPos a = p.relative(d), b = p.relative(d.getOpposite());
            BlockPos out = a.distSqr(house) > b.distSqr(house) ? a : b;
            double dist = p.distSqr(house);
            if (dist < bestDist) {
                bestDist = dist;
                best = out.immutable();
            }
        }
        return best == null ? house : best;
    }

    // ------------------------------------------------------------------ what the player reads

    /** Its card's line: who looked in on it today, or who it welcomed, or the errand it is on. Empty for none. */
    public static String cardLine(VillageFolkEntity f) {
        String now = doing(f);
        if (now != null) return capFirst(now) + ".";
        if (frail(f) && f.level() instanceof ServerLevel) {
            Long seen = VISITED.get(f.getUUID());
            long day = f.level().getDayTime() / 24000L;
            if (seen != null && seen == day) return "Looked in on today, as the town's very old are every day.";
            return "Very old: one of the family, a friend or a neighbour looks in every day with a meal.";
        }
        return "";
    }

    /** The day's neighbourliness for the books (inside Health.report's "care"). */
    public static CompoundTag report(UUID village, long day) {
        Book b = book(village, day);
        CompoundTag out = new CompoundTag();
        out.putInt("visits", b.visits);
        out.putInt("meals", b.meals);
        out.putInt("welcomes", b.welcomes);
        out.putInt("warmings", b.warmings);
        out.putInt("callers", b.callers);
        out.putString("last", b.last);
        int frail = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && frail(f)) frail++;
        out.putInt("frail", frail);
        return out;
    }

    public static List<String> lines(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        Book b = book(v.id(), day);
        List<String> out = new ArrayList<>();
        out.add("  Neighbours today: " + b.visits + " visits to the very old (" + b.meals + " with a meal), " + b.welcomes
            + " newcomers welcomed, " + b.warmings + " housewarmings (" + b.callers + " callers)" + (b.last.isEmpty() ? "" : "; last: " + b.last));
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            String d = doing(f);
            Errand e = ERRANDS.get(f.getUUID());
            if (d != null) out.add("    " + f.displayNameCap() + ": " + d);
            else if (e != null) out.add("    " + f.displayNameCap() + ": " + e.kind + " to come, when it has the time");
        }
        return out;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the day's visits to the old sent now (today's forgotten). Returns how many. */
    public static int visitsForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v == null) return 0;
        VISITED.clear();
        return visits(level, v, level.getDayTime() / 24000L);
    }

    /** Tests: the welcomes looked at now. */
    public static void welcomesForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v != null) welcomes(level, v, level.getDayTime() / 24000L);
    }

    /** Tests: the housewarmings looked at now. */
    public static void housewarmingsForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v != null) housewarmings(level, v, level.getDayTime() / 24000L, level.getDayTime() % 24000L);
    }

    /** Tests: the errand this folk has ("visit", "welcome", ...), begun or not; null for none. */
    @Nullable
    public static String errandForTests(VillageFolkEntity f) {
        Errand e = ERRANDS.get(f.getUUID());
        return e == null ? null : e.kind + (e.begun ? " (begun, stop " + e.at + " of " + e.stops.size() + ")" : " (waiting)");
    }

    /** Tests: is a housewarming planned at this house, called, over? {planned, called, over, callers, came}. */
    public static int[] warmingForTests(UUID village, BlockPos house) {
        Warming w = WARMINGS.get(village + "/" + house.asLong());
        return w == null ? new int[]{ 0, 0, 0, 0, 0 } : new int[]{ 1, w.called ? 1 : 0, w.over ? 1 : 0, w.callers.size(), w.came.size() };
    }

    /** Tests: is this newcomer waiting to be welcomed, and has somebody been sent? {waiting, sent, done}. */
    public static int[] welcomeForTests(UUID newcomer) {
        Welcome w = WELCOMES.get(newcomer);
        return w == null ? new int[]{ 0, 0, 0 } : new int[]{ 1, w.sent ? 1 : 0, w.done ? 1 : 0 };
    }

    /** Tests: is it keeping somebody's company ("follow", "sit", "host"), or null. */
    @Nullable
    public static String companyForTests(VillageFolkEntity f) {
        Company c = COMPANY.get(f.getUUID());
        return c == null ? null : c.role;
    }

    public static int visitsTodayForTests(UUID village, long day) {
        return book(village, day).visits;
    }
}
