package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featlife] The chapel's week and the town's life events.
 * <ul>
 * <li><b>The Sunday service</b> (RestDay's morning): the devout go, the rest as their character takes them
 *     (LifeWeights.churchChance); the chaplain (the sexton, else the leader, else the eldest: Beliefs.leader) stands at the
 *     front facing the pews and gives a short sermon, a line at a time; the hymn is played on the note blocks' harp at the
 *     altar between the lines (the choir sings it where the town has one: Music).</li>
 * <li><b>Christenings</b>: a baby born in the town, a player's among them, is christened the Sunday after, straight
 *     after the service: its parents, its grandparents and its parents' best friends at the font (the altar, or the well),
 *     the chaplain's words, water over its head, the bell. On the planner of the player whose child it is, and of any
 *     player its parents count a friend.</li>
 * <li><b>Funerals</b>: the morning after a folk dies (the vigil is the evening it dies: Gatherings), its family, its
 *     friends and the devout gather at the chapel and walk behind the chaplain to the graveyard, where the chaplain says
 *     the words and the stone is set (Graves); then the wake at the tavern, with a drink and the stories about it. Gently:
 *     nobody jokes about the dead. On the planner of every player in town.</li>
 * <li><b>Anniversaries</b>: a couple marks its wedding anniversary (Families) with a meal out together in the afternoon.</li>
 * </ul>
 * Dressed by what the town can afford, as a wedding is: candles at the font and flowers at a grave when the treasury runs
 * to them (and the stores have them), the whole town at a funeral when it is small, the family and friends when it is not.
 */
public final class Church {

    private Church() {}

    // ------------------------------------------------------------------ the service

    /** The chaplain's sermon, a line at a time: dry, short, eight of them. */
    static final String[] SERMON = {
        "Good morning. Those at the back, you can come forward. No? As you were.",
        "This week we were given rain when we wanted rain, and some when we did not.",
        "Be kind to your neighbour. Even that one. Especially that one.",
        "The harvest does not ask whether we deserve it. Neither should we.",
        "Mend what you broke. Return what you borrowed. You know who you are.",
        "Rest today. The fields will still be there tomorrow, unfortunately.",
        "We remember those not with us, and those who are with us but asleep.",
        "That is enough from me. Go well, and walk, do not run, to the games."
    };

    /** A hymn on the harp, in semitones from the low F sharp. */
    static final int[] HYMN = { 6, 6, 8, 10, 10, 8, 6, 3, 1, 1, 3, 6, 6, 3, 3 };

    private static final Map<UUID, Long> WENT = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<UUID>> CONGREGATION = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> CONGREGATION_DAY = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> PREACHED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_LINE = new ConcurrentHashMap<>();

    public static void resetForTests() {
        WENT.clear();
        CONGREGATION.clear();
        CONGREGATION_DAY.clear();
        PREACHED.clear();
        LAST_LINE.clear();
        CHRISTENINGS.clear();
        FUNERALS.clear();
        WAKED.clear();
        OUTINGS.clear();
        forceAll = false;
    }

    /** Tests: everybody goes. */
    public static volatile boolean forceAll = false;

    /** Does this folk go to church this Sunday? Its own draw against its character's odds; the chaplain always. */
    public static boolean attends(VillageFolkEntity f, UUID village, long day) {
        if (forceAll) return true;
        VillageFolkEntity chaplain = chaplain(village);
        if (chaplain != null && chaplain.getUUID().equals(f.getUUID())) return true;
        if (f.isBaby()) {
            for (UUID p : f.parentIds()) if (f.level() instanceof ServerLevel l && l.getEntity(p) instanceof VillageFolkEntity pf && attends(pf, village, day)) return true;
            return false;
        }
        return LifeWeights.roll(f, day, 3) < LifeWeights.churchChance(f);
    }

    /**
     * The service, a beat for this folk (RestDay.service, once it is in its place). True if RestDay should go on with its
     * own (the pews); false if it stays away. The chaplain takes the front from here and preaches.
     */
    public static boolean atService(VillageFolkEntity f, ServerLevel level, UUID village, long day, @Nullable BlockPos altar, @Nullable Direction back) {
        if (!attends(f, village, day)) {
            if (!Long.valueOf(day).equals(WENT.get(f.getUUID()))) {
                WENT.put(f.getUUID(), day);
                LifeLog.note(f, day, "Stayed away from the Sunday service", LifeWeights.why(f, LifeWeights.Mark.GRUMPY, LifeWeights.Mark.EASYGOING, LifeWeights.Mark.CURIOUS));
            }
            return false;
        }
        if (!Long.valueOf(day).equals(CONGREGATION_DAY.get(village))) {
            CONGREGATION_DAY.put(village, day);
            CONGREGATION.put(village, ConcurrentHashMap.newKeySet());
            PREACHED.put(village, 0);
        }
        Set<UUID> in = CONGREGATION.get(village);
        if (in.add(f.getUUID())) {
            LifeLog.count(f, LifeLog.Stat.SERVICE, 1);
            LifeLog.note(f, day, "Went to the Sunday service", LifeWeights.why(f, LifeWeights.Mark.DEVOUT));
        }
        return true;
    }

    /**
     * Who takes the chapel's rites: the sexton, the leader or the eldest (Beliefs.leader), alive and in town; failing that,
     * the eldest grown folk the town has.
     */
    @Nullable
    static VillageFolkEntity chaplain(UUID village) {
        VillageFolkEntity c = Beliefs.leader(village);
        if (c != null && c.isAlive()) return c;
        VillageFolkEntity eldest = null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && !f.isBaby() && !Visitors.is(f) && (eldest == null || f.ageYears() > eldest.ageYears())) eldest = f;
        }
        return eldest;
    }

    /** Not at church: a lie-in, or a quiet morning at home (it keeps the day of rest all the same). */
    static boolean stayAway(VillageFolkEntity f) {
        BlockPos home = Homes.homeOf(f);
        f.hobbyNow = "having a quiet Sunday morning at home";
        if (home != null && f.blockPosition().distSqr(home) > 9 && (f.getNavigation().isDone() || f.tickCount % 100 == 0)) f.walkTo(home, 0.7D);
        f.lastLeisureTick = f.tickCount;
        return true;
    }

    /** The chaplain at the front: true while it is preaching there (RestDay.service lets it be). */
    static boolean preach(VillageFolkEntity f, ServerLevel level, UUID village, long day, BlockPos altar, BlockPos facing) {
        VillageFolkEntity chaplain = chaplain(village);
        if (chaplain == null || !chaplain.getUUID().equals(f.getUUID())) return false;
        if (!RestDay.arrive(f, altar)) {
            f.hobbyNow = "on its way to take the service";
            return true;
        }
        f.hobbyNow = "taking the Sunday service";
        f.getLookControl().setLookAt(facing.getX() + 0.5, facing.getY() + 1.5, facing.getZ() + 0.5);
        long now = level.getGameTime();
        if (now - LAST_LINE.getOrDefault(village, -100000L) >= 140L) {
            LAST_LINE.put(village, now);
            int i = PREACHED.merge(village, 1, Integer::sum) - 1;
            if (i < SERMON.length) FolkTalk.speak(f, SERMON[i]);
            else if (i % 2 == 0) FolkTalk.speak(f, RestDay.sermon(village, day, f.getRandom()));
        }
        if (now % 10 == 0) {
            int n = HYMN[(int) ((now / 10) % HYMN.length)];
            level.playSound(null, altar, SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.RECORDS, 1.2F, (float) Math.pow(2.0, (n - 12) / 12.0));
            if (now % 40 == 0) level.sendParticles(ParticleTypes.NOTE, altar.getX() + 0.5, altar.getY() + 1.6, altar.getZ() + 0.5, 1, 0.3, 0.2, 0.3, n / 24.0);
        }
        return true;
    }

    /** Who was at this Sunday's service (the tests, the week page). */
    public static int congregation(UUID village, long day) {
        Set<UUID> s = CONGREGATION.get(village);
        return s == null || !Long.valueOf(day).equals(CONGREGATION_DAY.get(village)) ? 0 : s.size();
    }

    public static int sermonLinesSaid(UUID village) {
        return PREACHED.getOrDefault(village, 0);
    }

    // ------------------------------------------------------------------ christenings

    /** A christening due: the child, its name, its parents, the day, and its guests. */
    public static final class Christening {
        public final UUID village, child;
        public final String name;
        public final List<UUID> parents = new ArrayList<>();
        public final Set<UUID> guests = new HashSet<>();
        public final long day;
        public boolean done;
        boolean spoken;
        int line;

        Christening(UUID village, UUID child, String name, long day) {
            this.village = village;
            this.child = child;
            this.name = name;
            this.day = day;
        }
    }

    static final long CHRISTEN_FROM = 3700L, CHRISTEN_TO = 5600L;
    private static final Map<UUID, Christening> CHRISTENINGS = new ConcurrentHashMap<>();

    /** A baby born (Individual.born): its christening the coming Sunday, after the service. */
    public static void born(VillageFolkEntity child) {
        UUID village = child.ownerId();
        if (village == null || !(child.level() instanceof ServerLevel level)) return;
        long day = level.getDayTime() / 24000L;
        long sunday = day + Math.max(1, Calendar.daysTo(day, Calendar.Day.SUNDAY));
        if (Calendar.daysTo(day, Calendar.Day.SUNDAY) == 0) sunday = day + 7;
        Christening c = new Christening(village, child.getUUID(), child.displayNameCap(), sunday);
        c.parents.addAll(child.parentIds());
        CHRISTENINGS.put(child.getUUID(), c);
        for (UUID p : c.parents) {
            if (level.getEntity(p) instanceof VillageFolkEntity pf) {
                c.guests.add(p);
                UUID bf = Friends.best(pf);
                if (bf != null) c.guests.add(bf);
                for (UUID gp : pf.parentIds()) c.guests.add(gp);
            } else if (level.getPlayerByUUID(p) instanceof ServerPlayer sp) {
                Planner.add(sp, sunday * 24000L + CHRISTEN_FROM, "christening", "Your child's christening, after the Sunday service", font(level, village));
                sp.sendSystemMessage(Component.literal("The christening is " + Calendar.when(day, sunday) + " after the service, at the "
                    + (Villages.builtAt(village, "chapel") != null ? "chapel's font" : "well") + ". It's on your planner."));
            }
        }
        for (ServerPlayer sp : level.players()) {
            if (c.parents.contains(sp.getUUID())) continue;
            for (UUID p : c.parents) {
                if (level.getEntity(p) instanceof VillageFolkEntity pf && Friends.playerLevel(pf, sp.getUUID()).ordinal() >= Friends.Level.FRIEND.ordinal()) {
                    Planner.add(sp, sunday * 24000L + CHRISTEN_FROM, "christening", pf.displayNameCap() + "'s baby is christened after the Sunday service",
                        font(level, village));
                    break;
                }
            }
        }
    }

    /** Where the font is: the chapel's altar, or the well. */
    @Nullable
    static BlockPos font(ServerLevel level, UUID village) {
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals("chapel")) return b.anchor().relative(b.facing(), 5);
        Villages.Village v = Villages.get(village);
        return v == null ? null : v.centre();
    }

    /** Is this folk wanted at a christening now? */
    @Nullable
    static Christening christeningFor(VillageFolkEntity f, long day, long t) {
        if (t < CHRISTEN_FROM || t >= CHRISTEN_TO) return null;
        for (Christening c : CHRISTENINGS.values()) {
            if (c.done || c.day != day) continue;
            if (c.child.equals(f.getUUID()) || c.guests.contains(f.getUUID())) return c;
            VillageFolkEntity chaplain = chaplain(c.village);
            if (chaplain != null && chaplain.getUUID().equals(f.getUUID())) return c;
        }
        return null;
    }

    /** A christening, a beat for one of its party. True while it is there. */
    static boolean christening(ServerLevel level, VillageFolkEntity f, Christening c, long day) {
        BlockPos at = font(level, c.village);
        if (at == null) return false;
        VillageFolkEntity chaplain = chaplain(c.village);
        boolean leads = chaplain != null && chaplain.getUUID().equals(f.getUUID());
        int h = f.getUUID().hashCode();
        BlockPos spot = leads ? at : c.child.equals(f.getUUID()) ? at.offset(1, 0, 0)
            : at.offset((int) Math.round(Math.cos(Math.floorMod(h, 360) * Math.PI / 180) * 3), 0, (int) Math.round(Math.sin(Math.floorMod(h, 360) * Math.PI / 180) * 3));
        f.hobbyNow = leads ? "christening " + c.name : c.child.equals(f.getUUID()) ? "being christened" : "at " + c.name + "'s christening";
        if (!RestDay.arrive(f, spot)) return true;
        f.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5);
        if (leads) {
            long now = level.getGameTime();
            if (now - LAST_LINE.getOrDefault(c.child, -100000L) >= 120L) {
                LAST_LINE.put(c.child, now);
                String[] words = {
                    "We are here for " + c.name + ". Who brings this child?",
                    "Then we name you " + c.name + ", of " + Villages.name(c.village) + ".",
                    "May you sleep through the night sooner than your parents expect.",
                    "May your boots fit and your roof hold.",
                    "Godparents: you promise to remember the birthdays. Even the small ones.",
                    "Welcome, " + c.name + ". That's the wet part over."
                };
                if (c.line < words.length) {
                    FolkTalk.speak(f, words[c.line]);
                    if (c.line == 1) {
                        level.sendParticles(ParticleTypes.SPLASH, at.getX() + 1.5, at.getY() + 1.4, at.getZ() + 0.5, 20, 0.2, 0.2, 0.2, 0.1);
                        level.playSound(null, at, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 1.5F, 1.4F);
                    }
                    c.line++;
                } else finish(level, c, day);
            }
        }
        return true;
    }

    static void finish(ServerLevel level, Christening c, long day) {
        c.done = true;
        List<String> names = new ArrayList<>();
        for (UUID g : c.guests) if (level.getEntity(g) instanceof VillageFolkEntity f) {
            f.persona().remember(day, "I was at " + c.name + "'s christening", 4);
            LifeLog.note(f, day, "Stood at the font for " + c.name + "'s christening", null);
            if (!c.parents.contains(g)) names.add(f.displayNameCap());
        }
        if (level.getEntity(c.child) instanceof VillageFolkEntity child) child.persona().remember(day, "I was christened", 5);
        Villages.tell(c.village, day, c.name + " was christened" + (names.isEmpty() ? "" : ", with " + String.join(", ", names) + " at the font"));
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, level.getEntity(c.child) != null ? level.getEntity(c.child).getX() : 0,
            level.getEntity(c.child) != null ? level.getEntity(c.child).getY() + 1 : 0, level.getEntity(c.child) != null ? level.getEntity(c.child).getZ() : 0, 8, 0.6, 0.4, 0.6, 0.0);
    }

    @Nullable
    public static Christening christeningOf(UUID child) {
        return CHRISTENINGS.get(child);
    }

    /** Tests: today's christening for this child, now. */
    public static void christenTodayForTests(UUID child, long day) {
        Christening c = CHRISTENINGS.get(child);
        if (c == null) return;
        Christening n = new Christening(c.village, c.child, c.name, day);
        n.parents.addAll(c.parents);
        n.guests.addAll(c.guests);
        CHRISTENINGS.put(child, n);
    }

    // ------------------------------------------------------------------ funerals and wakes

    /** A funeral due: who, from where, to which grave, its mourners, and how far it has got. */
    public static final class Funeral {
        public final UUID village;
        public final String name;
        public final long day;
        public final Set<UUID> mourners = new HashSet<>();
        @Nullable public BlockPos grave;
        public String stage = "gather";            // gather, walk, words, wake, done
        int line;
        long stageAt;

        Funeral(UUID village, String name, long day) {
            this.village = village;
            this.name = name;
            this.day = day;
        }
    }

    static final long FUNERAL_FROM = 2000L, WAKE_FROM = 5000L, WAKE_TO = 9000L;
    private static final Map<String, Funeral> FUNERALS = new ConcurrentHashMap<>();
    private static final Set<String> WAKED = ConcurrentHashMap.newKeySet();

    /**
     * A folk has died (VillageFolkEntity.die, after Gatherings.mourn): its funeral the next morning. Its mourners are its
     * family and friends and the devout; in a small town (a dozen or fewer), everybody.
     */
    public static void died(VillageFolkEntity dead, long day) {
        UUID village = dead.ownerId();
        if (village == null || !(dead.level() instanceof ServerLevel level)) return;
        Funeral fu = new Funeral(village, dead.displayNameCap(), day + 1);
        boolean small = Villages.headcount(village) <= 12;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f == dead || Visitors.is(f)) continue;
            boolean kin = dead.getUUID().equals(f.life().partner()) || f.parentIds().contains(dead.getUUID()) || dead.parentIds().contains(f.getUUID());
            boolean friend = f.life().affinity(dead.getUUID()) >= Friends.FRIEND;
            if (small || kin || friend || LifeWeights.has(f, LifeWeights.Mark.DEVOUT) && !f.isBaby()) fu.mourners.add(f.getUUID());
        }
        VillageFolkEntity chaplain = chaplain(village);
        if (chaplain != null) fu.mourners.add(chaplain.getUUID());
        int index = Ledger.graves(village).size() - 1;
        BlockPos[] g = Graves.graveOf(village, index);
        fu.grave = g != null ? g[1] : Beliefs.vigilAt(village, Villages.builtAt(village, "graveyard"));
        FUNERALS.put(village + "|" + dead.getUUID(), fu);
        for (ServerPlayer p : level.players()) {
            Villages.Village v = Villages.get(village);
            if (v == null || !p.blockPosition().closerThan(v.centre(), 200)) continue;
            Planner.add(p, fu.day * 24000L + FUNERAL_FROM, "funeral", "The funeral of " + fu.name + ", from the "
                + (Villages.builtAt(village, "chapel") != null ? "chapel" : "square") + "; the wake after, at the tavern", font(level, village));
        }
    }

    /** The funeral this folk is wanted at now, if any. */
    @Nullable
    static Funeral funeralFor(VillageFolkEntity f, long day, long t) {
        if (t < FUNERAL_FROM - 200 || t >= WAKE_TO) return null;
        for (Funeral fu : FUNERALS.values()) {
            if (fu.day != day || "done".equals(fu.stage) || !fu.mourners.contains(f.getUUID())) continue;
            if (t >= WAKE_FROM && !"wake".equals(fu.stage)) continue;
            return fu;
        }
        return null;
    }

    /** A funeral, a beat for one mourner. True while it is at it. */
    static boolean funeral(ServerLevel level, VillageFolkEntity f, Funeral fu, long day, long t) {
        VillageFolkEntity chaplain = chaplain(fu.village);
        boolean leads = chaplain != null && chaplain.getUUID().equals(f.getUUID());
        BlockPos from = font(level, fu.village);
        if (from == null) return false;
        BlockPos grave = fu.grave != null ? fu.grave : from;
        if (t >= WAKE_FROM && !"wake".equals(fu.stage) && !"done".equals(fu.stage)) fu.stage = "wake";
        long now = level.getGameTime();
        switch (fu.stage) {
            case "gather" -> {
                f.hobbyNow = "at the chapel for " + fu.name + "'s funeral";
                if (!RestDay.arrive(f, near(from, f, 3))) return true;
                f.getLookControl().setLookAt(from.getX() + 0.5, from.getY() + 1.0, from.getZ() + 0.5);
                if (leads && now - fu.stageAt > 400) {
                    FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "We'll walk with " + fu.name + " now.", "Come, then. Slowly.",
                        "Let's take " + fu.name + " home."));
                    fu.stage = "walk";
                    fu.stageAt = now;
                    level.playSound(null, from, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2.0F, 0.6F);
                } else if (fu.stageAt == 0) fu.stageAt = now;
            }
            case "walk" -> {
                f.hobbyNow = leads ? "leading " + fu.name + "'s funeral" : "walking behind the chaplain to the graveyard";
                BlockPos to = leads ? grave : near(grave, f, 3);
                if (!leads && chaplain != null && chaplain.distanceToSqr(f) > 36) {
                    if (f.getNavigation().isDone() || now % 40 == 0) f.walkTo(chaplain.blockPosition(), 0.55D);   // in step behind it
                    return true;
                }
                if (RestDay.arrive(f, to) && leads) {
                    fu.stage = "words";
                    fu.stageAt = now;
                }
                if (!leads && f.getNavigation().isDone()) f.walkTo(to, 0.55D);
            }
            case "words" -> {
                f.hobbyNow = "at " + fu.name + "'s graveside";
                if (!RestDay.arrive(f, leads ? grave : near(grave, f, 3))) return true;
                f.getLookControl().setLookAt(grave.getX() + 0.5, grave.getY(), grave.getZ() + 0.5);
                if (leads && now - LAST_LINE.getOrDefault(f.getUUID(), -100000L) >= 120L) {
                    LAST_LINE.put(f.getUUID(), now);
                    String[] words = {
                        "We're here for " + fu.name + ".",
                        fu.name + " worked hard, and was fair, and is missed already.",
                        "We give back what we were lent.",
                        "Rest now. The rest of us will see to things.",
                        "Those who'd like to, the tavern's open after. " + fu.name + " would have gone.",
                        "Go gently."
                    };
                    if (fu.line < words.length) FolkTalk.speak(f, words[fu.line++]);
                    else {
                        Graves.tend(level, fu.village);                       // the stone set
                        Villages.tell(fu.village, day, fu.name + " was laid to rest" + (fu.mourners.size() > 1 ? ", " + fu.mourners.size() + " mourners walking behind" : ""));
                        fu.stage = "wake";
                        fu.stageAt = now;
                    }
                }
            }
            case "wake" -> {
                if (t < WAKE_FROM - 1500 && "wake".equals(fu.stage) && now - fu.stageAt < 200) return true;
                Ledger.Building tav = Tavern.of(fu.village);
                BlockPos at = tav != null ? tav.anchor() : Villages.get(fu.village) != null ? Villages.get(fu.village).centre() : from;
                f.hobbyNow = "at " + fu.name + "'s wake";
                if (!RestDay.arrive(f, near(at, f, 3))) return true;
                if (f.getRandom().nextInt(40) == 0) {
                    Villages.Village v = Villages.get(fu.village);
                    if (v != null) Tavern.drink(f, level, v, day);
                    FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "To " + fu.name + ".", "I remember the first day " + fu.name + " came here.",
                        fu.name + " always said the bread was better last year.", "Never once late. Not once.", "Quiet without them.",
                        fu.name + " would have had a second."));
                }
                if (WAKED.add(f.getUUID() + "|" + day)) {
                    LifeLog.note(f, day, "Went to " + fu.name + "'s funeral and the wake", null);
                    f.persona().remember(day, "the funeral of " + fu.name, 6);
                    for (UUID m : fu.mourners) if (level.getEntity(m) instanceof VillageFolkEntity o && o != f && o.distanceToSqr(f) < 36) Friends.chatted(f, o, 2);
                }
                if (t >= WAKE_TO - 100) fu.stage = "done";
            }
            default -> { return false; }
        }
        return true;
    }

    /** A spot round a place for this folk, by its id, so the mourners stand in a ring and not on each other. */
    static BlockPos near(BlockPos at, VillageFolkEntity f, int r) {
        double a = Math.floorMod(f.getUUID().hashCode(), 360) * Math.PI / 180.0;
        return at.offset((int) Math.round(Math.cos(a) * r), 0, (int) Math.round(Math.sin(a) * r));
    }

    @Nullable
    public static Funeral funeralOf(UUID village, String name) {
        for (Funeral fu : FUNERALS.values()) if (fu.village.equals(village) && fu.name.equals(name)) return fu;
        return null;
    }

    /** Tests: this funeral today. */
    public static void funeralTodayForTests(UUID village, String name, long day) {
        for (Map.Entry<String, Funeral> e : FUNERALS.entrySet()) {
            Funeral fu = e.getValue();
            if (!fu.village.equals(village) || !fu.name.equals(name)) continue;
            Funeral n = new Funeral(village, name, day);
            n.mourners.addAll(fu.mourners);
            n.grave = fu.grave;
            e.setValue(n);
        }
    }

    // ------------------------------------------------------------------ anniversaries

    /** A couple's anniversary outing: a meal at the tavern in the afternoon (Families.mark). */
    record Outing(UUID a, UUID b, long day, int years) {}

    private static final Map<UUID, Outing> OUTINGS = new ConcurrentHashMap<>();
    static final long ANNIV_FROM = 8500L, ANNIV_TO = 11000L;

    /** A couple marked its anniversary (Families): out for a meal together this afternoon. */
    public static void anniversary(ServerLevel level, VillageFolkEntity f, VillageFolkEntity p, int years, long day) {
        Outing o = new Outing(f.getUUID(), p.getUUID(), day, years);
        OUTINGS.put(f.getUUID(), o);
        OUTINGS.put(p.getUUID(), o);
        UUID village = f.ownerId();
        if (village != null) Villages.tell(village, day, f.displayNameCap() + " and " + p.displayNameCap() + " marked their " + TownCalendar.ordinal(years)
            + " wedding anniversary");
        for (UUID u : new UUID[]{ f.getUUID(), p.getUUID() }) {
            if (level.getPlayerByUUID(u) instanceof ServerPlayer sp) Planner.add(sp, day * 24000L + ANNIV_FROM, "anniversary",
                "Your wedding anniversary: a meal out this afternoon", village == null || Tavern.of(village) == null ? null : Tavern.of(village).anchor());
        }
    }

    /** The anniversary meal, a beat for one of the couple. */
    static boolean anniversaryMeal(ServerLevel level, VillageFolkEntity f, long day, long t) {
        Outing o = OUTINGS.get(f.getUUID());
        if (o == null || o.day() != day || t < ANNIV_FROM || t >= ANNIV_TO || f.onShift() && !f.offWorkNow()) return false;
        UUID village = f.ownerId();
        Ledger.Building tav = village == null ? null : Tavern.of(village);
        if (tav == null) return false;
        UUID other = o.a().equals(f.getUUID()) ? o.b() : o.a();
        f.hobbyNow = "out for an anniversary meal";
        if (!RestDay.arrive(f, near(tav.anchor(), f, 1))) return true;
        if (level.getEntity(other) instanceof VillageFolkEntity p) {
            f.getLookControl().setLookAt(p, 30.0F, 30.0F);
            if (f.getRandom().nextInt(50) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Same table as last year.", "You've not changed. Well, a bit.", "Order what you like. Within reason.",
                    "To us, then.", "I'd do it again. Probably.", "Remember the rain that day?"));
                level.sendParticles(ParticleTypes.HEART, f.getX(), f.getEyeY() + 0.5, f.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
            }
        }
        if (!Long.valueOf(day).equals(WENT.get(f.getUUID()))) {
            WENT.put(f.getUUID(), day);
            Villages.Village v = Villages.get(village);
            if (v != null) Tavern.drink(f, level, v, day);
            LifeLog.note(f, day, "Out for a meal on its wedding anniversary", null);
        }
        return true;
    }

    // ------------------------------------------------------------------ for LifeTick

    /** Its part in any of the chapel's events now. True while it is about one. */
    static boolean hold(ServerLevel level, VillageFolkEntity f) {
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        Christening c = christeningFor(f, day, t);
        if (c != null && christening(level, f, c, day)) return true;
        Funeral fu = funeralFor(f, day, t);
        if (fu != null && funeral(level, f, fu, day, t)) return true;
        return anniversaryMeal(level, f, day, t);
    }

    /** The week page and the books: what the chapel has coming. */
    public static List<String> coming(UUID village, long today) {
        List<String> out = new ArrayList<>();
        for (Christening c : CHRISTENINGS.values()) if (c.village.equals(village) && !c.done && c.day >= today)
            out.add(c.name + "'s christening, " + Calendar.when(today, c.day) + " after the service");
        for (Funeral fu : FUNERALS.values()) if (fu.village.equals(village) && !"done".equals(fu.stage) && fu.day >= today)
            out.add("The funeral of " + fu.name + ", " + Calendar.when(today, fu.day) + " in the morning; the wake after");
        return out;
    }

    static RandomSource rnd(ServerLevel level) {
        return level.getRandom();
    }
}
