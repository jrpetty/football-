package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The village coming together: the morning assembly at the board, a new building opened,
 * the weekly feast, a wedding, a vigil, a new age celebrated, a hero honoured, the council
 * sitting, an election, a child come of age.
 *
 * <p>Each has its place and its shape. The bell is rung, and folk leave what they are doing
 * (whoever is on watch stays on watch) and walk over — the first there taking the front:
 * rows in a curve facing whoever speaks, for an assembly, an opening, an honour or an
 * election; a ring round the heart, for a feast or a celebration; seats either side of an
 * aisle, for a wedding; a small circle, for the council or a child come of age. The children
 * go to the front. Whoever leads it (the elder, mostly) stands before them and speaks, line
 * by line, and the crowd answers — a cheer, a murmur, a hush. A feast is eaten, out of the
 * stores; a celebration has fireworks, if the stores have the powder and paper for them.
 * When it is over they linger a little, a word with whoever is beside them, and drift back
 * to their day in twos and threes.
 *
 * <p>And it is real: the morning assembly is the village's actual business that day; the
 * council's vote decides what is built next; the election decides who the elder is. None of
 * it takes the village from its work for long — a morning assembly is a minute before work
 * starts, the rest are in the evening, after it — and the watch is never called away.
 */
public final class Assemblies {

    private Assemblies() {}

    public enum Kind {
        MORNING("the morning assembly"), OPENING("an opening"), FEAST("the village feast"), WEDDING("a wedding"),
        VIGIL("a vigil"), CELEBRATION("a celebration"), HONOUR("an honouring"), COUNCIL("the council's meeting"),
        ELECTION("an election"), COMING_OF_AGE("a coming of age"), ENVOY("an envoy's audience"),
        WATCH("the changing of the watch"), FOUNDING("Founding Day");

        public final String label;
        Kind(String label) { this.label = label; }
    }

    enum Layout { ARC, RING, AISLE, CIRCLE }

    enum Phase { CALL, GATHER, SPEECH, MINGLE, CLOSE, DISPERSE }

    /** One line of the proceedings: who says it (null: the host), what, how the crowd takes it, and what it does. */
    record Line(@Nullable UUID by, String text, char react, @Nullable Runnable effect) {}

    static final class Assembly {
        final UUID village;
        final Kind kind;
        final String subject;
        final long day;
        BlockPos focus;
        Direction audience;
        Layout layout;
        @Nullable UUID host;
        final List<UUID> principals = new ArrayList<>();
        @Nullable Set<UUID> invited;
        Phase phase = Phase.CALL;
        long phaseAt;
        final List<Line> script = new ArrayList<>();
        int line;
        long nextLineAt;
        final List<BlockPos> seats = new ArrayList<>();
        final Map<UUID, Integer> seated = new HashMap<>();
        final Set<UUID> called = new HashSet<>();
        final Set<UUID> ate = new HashSet<>();
        final Set<UUID> chatted = new HashSet<>();
        final Map<UUID, Long> lingerUntil = new HashMap<>();
        final Map<UUID, Integer> pathAt = new HashMap<>();
        int expected;
        long lastStep = -1;
        int rockets;
        /** Rockets actually sent up (rockets stands at 99 once the powder or the paper runs out). */
        int fired;

        Assembly(UUID village, Kind kind, String subject, long day, BlockPos focus, Direction audience, Layout layout) {
            this.village = village;
            this.kind = kind;
            this.subject = subject;
            this.day = day;
            this.focus = focus;
            this.audience = audience;
            this.layout = layout;
        }
    }

    /** Stands in a council script for its decision, read out once the vote is taken. */
    private static final String DECISION = "\u0000decision";

    /** What is under way in each village, and what is to come. */
    private static final Map<UUID, Assembly> NOW = new ConcurrentHashMap<>();
    private static final Map<UUID, List<Assembly>> PLANNED = new ConcurrentHashMap<>();
    private static final Map<String, Long> HELD = new ConcurrentHashMap<>();
    /** The building last opened in each village, for the curious to go and look round. */
    public record Opened(String structure, BlockPos at, long day) {}
    private static final Map<UUID, Opened> OPENED = new ConcurrentHashMap<>();

    /** What was last opened, if it was in the last couple of days. */
    @Nullable
    public static Opened lastOpened(UUID village, long day) {
        Opened o = OPENED.get(village);
        return o != null && day - o.day() <= 2 ? o : null;
    }

    /** Who built what, for the opening. */
    private static final Map<String, String> BUILDERS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        OPENED.clear();
        NOW.clear();
        PLANNED.clear();
        HELD.clear();
        BUILDERS.clear();
    }

    // ------------------------------------------------------------------ what is coming

    /** The builder of what is about to be finished (said at its opening). */
    public static void builder(UUID village, String structure, String name) {
        BUILDERS.put(village + "/" + structure, name);
    }

    /** A building is finished: it is opened this evening (or the next, if the day is over). */
    public static void opening(UUID village, String structure, BlockPos anchor, Direction facing, long gameTime) {
        if (structure.equals("fortify") || structure.equals("colony") || structure.equals("house") && Villages.headcount(village) > 20) return;
        long day = -1;                                  // the next evening there is
        int[] half = com.jrpetty.mcassistant.entity.goal.Blueprints.fullHalf(structure);
        int depth = facing.getAxis() == Direction.Axis.X ? half[0] : half[1];
        BlockPos focus = anchor.relative(facing, Math.max(3, depth + 2));
        Assembly a = new Assembly(village, Kind.OPENING, structure, day, focus, facing, Layout.ARC);
        String who = BUILDERS.remove(village + "/" + structure);
        a.invited = null;
        a.principals.clear();
        PLANNED.computeIfAbsent(village, k -> new ArrayList<>()).add(a);
        if (who != null) BUILDERS.put(village + "/opening/" + structure, who);
    }

    /** A child is grown: the family and friends gather round it this evening. */
    public static void cameOfAge(UUID village, VillageFolkEntity f, @Nullable String trade) {
        long day = f.level().getDayTime() / 24000L + (f.level().getDayTime() % 24000L >= 13000L ? 1 : 0);
        BlockPos heart = f.villageCentre();
        if (heart == null) return;
        Assembly a = new Assembly(village, Kind.COMING_OF_AGE, f.displayNameCap() + "|" + (trade == null ? "" : trade), day,
            heart, Direction.SOUTH, Layout.CIRCLE);
        a.principals.add(f.getUUID());
        a.invited = null;
        PLANNED.computeIfAbsent(village, k -> new ArrayList<>()).add(a);
    }

    // ------------------------------------------------------------------ running them

    /** Once every few seconds for each village (from a folk's agenda): start what is due, and keep it moving. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Assembly a = NOW.get(id);
        if (a != null) {
            step(level, a, now);
            return;
        }
        if (Raids.underAlarm(id) || Villages.headcount(id) < 3) return;
        long dayTime = level.getDayTime();
        long t = dayTime % 24000L, day = dayTime / 24000L;
        Assembly next = null;
        if (t >= 150 && t < 1400 && !level.isRaining() && !held(id, Kind.MORNING, day) && TownBell.up(id, dayTime)) {   // (after the dawn bell)
            next = morning(level, v, day);
        } else if (t >= 1400 && t < 11500) {
            VillageFolkEntity guest = Envoys.waitingAt(level, v);
            if (guest != null && guest.trip() != null && guest.trip().errand() != null) next = envoy(level, v, guest);
        } else if (t >= 11500 && t < 12100) {
            // At dusk the watch changes: the guards meet at the bell and the night's watch takes over.
            if (!held(id, Kind.WATCH, day) && guards(id) >= 2) next = watch(level, v, day);
        } else if (t >= 12100 && t < 13200) {
            // Founding Day (FoundingDay): once a year, before any other gathering that evening.
            if (FoundingDay.due(id, day) && !held(id, Kind.FOUNDING, day)) next = FoundingDay.assembly(level, v, day);
            Gatherings.Kind tonight = Gatherings.tonight(id, day);
            if (next == null && tonight != null) next = evening(level, v, tonight, day);
            if (next == null) next = planned(id, day);
            if (next == null && day % 7 == 3 && !held(id, Kind.COUNCIL, day) && Council.members(id).size() >= 3) {
                next = council(level, v, day);
            }
            if (next == null && Elections.countsToday(id, day) && !held(id, Kind.ELECTION, day)) {
                next = election(level, v, day);
            }
            // Rain puts off a feast, not a vigil, the council, or the count of an election.
            if (next != null && level.isRaining() && next.kind != Kind.VIGIL && next.kind != Kind.COUNCIL && next.kind != Kind.ELECTION
                && next.kind != Kind.FOUNDING) next = null;            // (nor Founding Day: it comes once a year)
        }
        boolean several = next != null && (next.kind == Kind.OPENING || next.kind == Kind.ENVOY);
        if (next == null || held(id, next.kind, day) && !several) return;
        String key = id + "/" + next.kind + "/" + day + (several ? "/" + next.subject : "");
        if (HELD.containsKey(key)) return;                  // this envoy has been heard (or could not be) today
        HELD.put(key, day);
        if (HELD.size() > 4096) HELD.entrySet().removeIf(e -> day - e.getValue() > 3);
        next.phaseAt = now;
        NOW.put(id, next);
        step(level, next, now);
    }

    private static boolean held(UUID village, Kind kind, long day) {
        return HELD.containsKey(village + "/" + kind + "/" + day);
    }

    @Nullable
    private static Assembly planned(UUID village, long day) {
        List<Assembly> list = PLANNED.get(village);
        if (list == null || list.isEmpty()) return null;
        list.removeIf(a -> a.day >= 0 && a.day < day - 1);
        while (list.size() > 8) list.remove(0);
        for (Assembly a : list) {
            if (a.day < 0 || a.day <= day) {
                list.remove(a);
                return a;
            }
        }
        return null;
    }

    private static int adults(UUID village) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (!a.isBaby()) n++;
        return n;
    }

    /** Move it on: ring the bell, wait for them, speak, eat, close, let them go. */
    static void step(ServerLevel level, Assembly a, long now) {
        if (a.lastStep == now) return;
        a.lastStep = now;
        RandomSource r = level.getRandom();
        switch (a.phase) {
            case CALL -> {
                prepare(level, a);
                if (a.seats.isEmpty() || a.script.isEmpty()) { NOW.remove(a.village); return; }
                level.playSound(null, a.focus, SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 2.0F, a.kind == Kind.VIGIL ? 0.7F : 1.0F);
                VillageFolkEntity host = host(level, a);
                if (host != null) FolkTalk.speak(host, a.kind == Kind.VIGIL ? "Come, all of you." : FolkTalk.pick(r, "Gather round, everyone!", "Come along, all!", "Over here, everybody!"));
                a.phase = Phase.GATHER;
                a.phaseAt = now;
            }
            case GATHER -> {
                if (now - a.phaseAt == 40) level.playSound(null, a.focus, SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 2.0F, 1.0F);
                long wait = a.kind == Kind.MORNING ? 360 : 600;
                int here = a.seated.size();
                if (a.expected == 0 && now - a.phaseAt > 200) { NOW.remove(a.village); return; }
                if (here >= Math.max(1, a.expected * 7 / 10) || now - a.phaseAt > wait) {
                    a.phase = Phase.SPEECH;
                    a.phaseAt = now;
                    a.nextLineAt = now + 30;
                }
            }
            case SPEECH -> {
                if (now < a.nextLineAt) return;
                if (a.line >= a.script.size()) {
                    a.phase = a.kind == Kind.FEAST || a.kind == Kind.CELEBRATION || a.kind == Kind.HONOUR || a.kind == Kind.FOUNDING
                        ? Phase.MINGLE : Phase.CLOSE;
                    a.phaseAt = now;
                    return;
                }
                Line l = a.script.get(a.line++);
                if (l.effect() != null) com.jrpetty.mcassistant.Guard.run("assembly line", l.effect());
                VillageFolkEntity speaker = l.by() == null ? host(level, a)
                    : level.getEntity(l.by()) instanceof VillageFolkEntity s ? s : host(level, a);
                String text = l.text();
                if (DECISION.equals(text)) {
                    String d = Council.lastDecision(a.village);
                    text = d == null ? "Nothing to decide this week." : capital(d) + ".";
                }
                if (speaker != null && !text.isEmpty()) {
                    FolkTalk.speak(speaker, text);
                    speaker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                }
                react(level, a, l.react(), speaker);
                a.nextLineAt = now + Math.max(60, 40 + text.length() * 2);
            }
            case MINGLE -> {
                if (a.kind == Kind.CELEBRATION || a.kind == Kind.HONOUR || a.kind == Kind.FOUNDING) {
                    if ((now - a.phaseAt) % 40 == 0 && a.rockets < 12) {
                        Villages.Village v = Villages.get(a.village);
                        if (v != null && Crafts.take(level, v, s -> s.is(Items.GUNPOWDER), 1)) {
                            if (Crafts.take(level, v, s -> s.is(Items.PAPER), 1)) {
                                Gatherings.launch(level, a.focus, r);
                                a.rockets++;
                                a.fired++;
                            } else {
                                Crafts.store(level, v, new ItemStack(Items.GUNPOWDER));
                                a.rockets = 99;
                            }
                        } else {
                            a.rockets = 99;            // nothing to make them of: a bonfire glow will do
                        }
                    }
                    if (a.rockets >= 99 && (now - a.phaseAt) % 20 == 0) {
                        level.sendParticles(ParticleTypes.FLAME, a.focus.getX() + 0.5, a.focus.getY() + 0.3, a.focus.getZ() + 0.5, 6, 0.3, 0.2, 0.3, 0.01);
                        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, a.focus.getX() + 0.5, a.focus.getY() + 1, a.focus.getZ() + 0.5, 1, 0.1, 0.1, 0.1, 0.01);
                    }
                }
                if (now - a.phaseAt > (a.kind == Kind.FEAST || a.kind == Kind.FOUNDING ? 1200 : 700)) {
                    a.phase = Phase.CLOSE;
                    a.phaseAt = now;
                }
            }
            case CLOSE -> {
                if (now == a.phaseAt || a.phaseAt + 1 == now) close(level, a);
                if (now - a.phaseAt > 60) {
                    a.phase = Phase.DISPERSE;
                    a.phaseAt = now;
                    for (UUID u : a.seated.keySet()) a.lingerUntil.put(u, now + 60 + r.nextInt(240));
                }
            }
            case DISPERSE -> {
                if (now - a.phaseAt > 320) NOW.remove(a.village);
            }
        }
    }

    /** How the crowd takes a line: '!' a cheer, '?' a murmur, '~' a hush, '*' a greeting back. */
    private static void react(ServerLevel level, Assembly a, char how, @Nullable VillageFolkEntity speaker) {
        if (how == ' ') return;
        RandomSource r = level.getRandom();
        int voices = 0;
        for (UUID u : a.seated.keySet()) {
            if (!(level.getEntity(u) instanceof VillageFolkEntity f) || f == speaker) continue;
            switch (how) {
                case '!' -> {
                    f.swing(r.nextBoolean() ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND);
                    if (r.nextInt(3) == 0 && f.onGround()) f.getJumpControl().jump();
                    if (voices < 3 && r.nextInt(3) == 0) {
                        FolkTalk.speak(f, FolkTalk.pick(r, "Hooray!", "Hear, hear!", "Well said!", "Huzzah!", "Yes!"));
                        voices++;
                    }
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, f.getX(), f.getY() + 2.1, f.getZ(), 2, 0.2, 0.1, 0.2, 0.0);
                }
                case '?' -> {
                    if (voices < 2 && r.nextInt(4) == 0) {
                        FolkTalk.speak(f, FolkTalk.pick(r, "Hmm.", "True enough.", "Quite right.", "About time."));
                        voices++;
                    }
                }
                case '*' -> {
                    if (voices < 3 && r.nextInt(3) == 0) {
                        FolkTalk.speak(f, FolkTalk.pick(r, "Morning!", "Good morning!", "Morning, all."));
                        voices++;
                    }
                }
                case '~' -> f.setXRot(25.0F);
                default -> { }
            }
        }
    }

    /** At the end: what it means to the village and to everybody who was there. */
    private static void close(ServerLevel level, Assembly a) {
        RandomSource r = level.getRandom();
        long day = level.getDayTime() / 24000L;
        String what = describe(a);
        for (UUID u : a.seated.keySet()) {
            if (!(level.getEntity(u) instanceof VillageFolkEntity f)) continue;
            if (a.kind != Kind.MORNING) f.persona().remember(day, "I was at " + what, a.kind == Kind.WEDDING || a.kind == Kind.VIGIL ? 4 : 2);
            if (a.kind == Kind.FEAST || a.kind == Kind.CELEBRATION || a.kind == Kind.HONOUR || a.kind == Kind.WEDDING
                    || a.kind == Kind.FOUNDING) {
                f.persona().feasted(day);
            }
            if (a.kind != Kind.VIGIL && a.kind != Kind.MORNING && a.kind != Kind.COUNCIL) {
                f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                if (r.nextInt(4) == 0 && f.onGround()) f.getJumpControl().jump();
            }
        }
        if (a.kind == Kind.WEDDING) {
            Gatherings.Wedding w = Gatherings.wedding(a.village);
            if (w != null) Villages.tell(a.village, day, w.names() + " were wed");
            Gatherings.wed(a.village);
        }
        if (a.kind == Kind.OPENING) {
            Villages.tell(a.village, day, Villages.spoken(a.subject) + " was opened");
            OPENED.put(a.village, new Opened(a.subject, a.focus.immutable(), day));
        }
        if (a.kind == Kind.COMING_OF_AGE) Villages.tell(a.village, day, a.subject.split("\\|", 2)[0] + " was welcomed among the grown folk");
        if (a.kind == Kind.FOUNDING) FoundingDay.kept(level, a);
    }

    // ------------------------------------------------------------------ being there

    /** Is this folk wanted at what is under way now (so its own day waits)? */
    public static boolean attending(VillageFolkEntity f) {
        UUID village = Envoys.visiting(f) != null ? Envoys.visiting(f) : f.ownerId();
        if (village == null) return false;
        Assembly a = NOW.get(village);
        if (a == null) return false;
        if (a.phase == Phase.DISPERSE) {
            Long until = a.lingerUntil.get(f.getUUID());
            return until != null && f.level().getGameTime() < until;
        }
        return invited(a, f);
    }

    static boolean invited(Assembly a, VillageFolkEntity f) {
        if (!f.isAlive() || f.isSleeping() || f.isHired() || Nether.away(f) || Drover.busy(f)) return false;
        if (f.getTarget() != null) return false;
        if (Scouts.out(f) || f.trip() != null && Envoys.visiting(f) == null) return false;    // away on the land or the road
        if (f.stationTask() == AssistantEntity.StationTask.GUARD && (f.level().isNight() || Raids.underAlarm(a.village))) return false;
        if (f.isBaby() && (a.kind == Kind.COUNCIL || a.kind == Kind.ELECTION || a.kind == Kind.VIGIL)) return false;
        if (a.host != null && a.host.equals(f.getUUID())) return true;
        if (a.principals.contains(f.getUUID())) return true;
        if (a.invited != null && !a.invited.contains(f.getUUID())) return false;
        return f.blockPosition().distSqr(a.focus) < 128 * 128;
    }

    /**
     * Called every few ticks for each folk: if it is wanted at what is under way, it goes
     * there, finds a place, and takes part. True while it is busy with it.
     */
    public static boolean attend(VillageFolkEntity f, ServerLevel level) {
        UUID village = Envoys.visiting(f) != null ? Envoys.visiting(f) : f.ownerId();
        if (village == null) return false;
        Assembly a = NOW.get(village);
        if (a == null) return false;
        long now = level.getGameTime();
        step(level, a, now);
        if (a.phase == Phase.CALL) return false;
        UUID me = f.getUUID();
        if (a.phase == Phase.DISPERSE) return linger(f, level, a, now);
        if (!invited(a, f)) return false;
        if (a.called.add(me)) {
            // The bell: what it was doing waits.
            f.clearQueue();
            f.getNavigation().stop();
        }
        RandomSource r = f.getRandom();
        boolean isHost = me.equals(a.host);
        BlockPos spot;
        if (isHost) {
            spot = a.focus;
        } else if (a.principals.contains(me)) {
            spot = principalSpot(a, a.principals.indexOf(me));
        } else {
            Integer seat = a.seated.get(me);
            if (seat == null) {
                // On the way: once near enough to see where there's room, take the best place left.
                double reach = 6.0 + rowsDepth(a);
                if (f.blockPosition().distSqr(a.focus) < reach * reach) {
                    seat = freeSeat(a, f.isBaby());
                    if (seat != null) a.seated.put(me, seat);
                }
            }
            spot = seat == null ? approach(a) : a.seats.get(seat);
        }
        double d = horizontal(f, spot);
        if (d > 0.9) {
            Integer last = a.pathAt.get(me);
            if (last == null || f.tickCount - last > 40 || f.getNavigation().isDone()) {
                f.walkTo(spot, a.kind == Kind.MORNING ? 0.95D : 0.8D);
                a.pathAt.put(me, f.tickCount);
            }
            f.hobbyNow = "on the way to " + describe(a);
            return true;
        }
        f.getNavigation().stop();
        f.hobbyNow = "at " + describe(a);
        f.lastLeisureTick = f.tickCount;
        // Facing the right way: the host to the crowd, the crowd to the host.
        if (isHost) {
            BlockPos crowd = a.focus.relative(a.audience, 4);
            f.getLookControl().setLookAt(crowd.getX() + 0.5, crowd.getY() + 1.5, crowd.getZ() + 0.5);
        } else if (a.kind == Kind.WEDDING && a.principals.contains(me) && a.principals.size() == 2) {
            UUID other = a.principals.get(0).equals(me) ? a.principals.get(1) : a.principals.get(0);
            if (level.getEntity(other) instanceof VillageFolkEntity o) f.getLookControl().setLookAt(o, 30.0F, 30.0F);
        } else {
            VillageFolkEntity host = host(level, a);
            if (host != null && r.nextInt(12) != 0) f.getLookControl().setLookAt(host, 20.0F, 20.0F);
            else f.getLookControl().setLookAt(a.focus.getX() + 0.5, a.focus.getY() + 1.6, a.focus.getZ() + 0.5);
        }
        if (a.kind == Kind.WEDDING && a.principals.contains(me) && f.tickCount % 20 == 0) {
            level.sendParticles(ParticleTypes.HEART, f.getX(), f.getY() + 2.1, f.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
        }
        if (a.phase == Phase.MINGLE) mingle(f, level, a, r);
        if (a.kind == Kind.VIGIL && f.tickCount % 40 == 0) {
            level.sendParticles(ParticleTypes.SMOKE, f.getX(), f.getY() + 1.9, f.getZ(), 1, 0.05, 0.05, 0.05, 0.0);
        }
        return true;
    }

    /** The feast and the celebration: eat (out of the stores), dance, raise a cup. */
    private static void mingle(VillageFolkEntity f, ServerLevel level, Assembly a, RandomSource r) {
        if ((a.kind == Kind.FEAST || a.kind == Kind.FOUNDING) && !a.ate.contains(f.getUUID()) && r.nextInt(30) == 0) {
            a.ate.add(f.getUUID());
            Villages.Village v = Villages.get(a.village);
            ItemStack food = v == null ? ItemStack.EMPTY : Crafts.takeOne(level, v,
                s -> s.get(net.minecraft.core.component.DataComponents.FOOD) != null && !s.is(Items.ROTTEN_FLESH) && !s.is(Items.SPIDER_EYE));
            if (!food.isEmpty()) {
                level.sendParticles(new net.minecraft.core.particles.ItemParticleOption(ParticleTypes.ITEM, food),
                    f.getX(), f.getEyeY(), f.getZ(), 6, 0.15, 0.1, 0.15, 0.03);
                f.playSound(SoundEvents.GENERIC_EAT, 0.6F, 0.9F + r.nextFloat() * 0.2F);
                f.heal(2.0F);
            }
        }
        if (r.nextInt(40) == 0) {
            if (f.onGround()) f.getJumpControl().jump();
            f.setYRot(f.getYRot() + 45.0F * (r.nextBoolean() ? 1 : -1));
            f.swing(r.nextBoolean() ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND);
        }
        if (r.nextInt(160) == 0) {
            FolkTalk.speak(f, a.kind == Kind.FEAST || a.kind == Kind.FOUNDING
                ? FolkTalk.pick(r, "Pass the bread!", "To " + Villages.name(a.village) + "!", "Best feast in years.", "Another slice? Go on then.")
                : FolkTalk.pick(r, "Ooooh!", "Look at that one!", "To " + Villages.name(a.village) + "!"));
        }
        // A word with whoever is beside it.
        if (r.nextInt(300) == 0 && !a.chatted.contains(f.getUUID())) {
            VillageFolkEntity near = neighbour(f, level, a);
            if (near != null && Smalltalk.chat(f, near, level)) {
                a.chatted.add(f.getUUID());
                a.chatted.add(near.getUUID());
            }
        }
    }

    /** After it is over: a word with a neighbour, then back to its day. */
    private static boolean linger(VillageFolkEntity f, ServerLevel level, Assembly a, long now) {
        Long until = a.lingerUntil.get(f.getUUID());
        if (until == null || now >= until) return false;
        f.getNavigation().stop();
        if (!a.chatted.contains(f.getUUID()) && f.getRandom().nextInt(8) == 0) {
            VillageFolkEntity near = neighbour(f, level, a);
            a.chatted.add(f.getUUID());
            if (near != null && !a.chatted.contains(near.getUUID()) && Smalltalk.chat(f, near, level)) a.chatted.add(near.getUUID());
        }
        return true;
    }

    @Nullable
    private static VillageFolkEntity neighbour(VillageFolkEntity f, ServerLevel level, Assembly a) {
        VillageFolkEntity best = null;
        double nearest = 9.0;
        for (UUID u : a.seated.keySet()) {
            if (u.equals(f.getUUID()) || !(level.getEntity(u) instanceof VillageFolkEntity o)) continue;
            double d = o.distanceToSqr(f);
            if (d < nearest) { nearest = d; best = o; }
        }
        return best;
    }

    // ------------------------------------------------------------------ places

    private static double horizontal(VillageFolkEntity f, BlockPos p) {
        double dx = f.getX() - (p.getX() + 0.5), dz = f.getZ() - (p.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Nullable
    private static VillageFolkEntity host(ServerLevel level, Assembly a) {
        return a.host != null && level.getEntity(a.host) instanceof VillageFolkEntity h && h.isAlive() ? h : null;
    }

    /** Where those who come to it gather before they find a place: behind the last rows. */
    private static BlockPos approach(Assembly a) {
        if (a.seats.isEmpty()) return a.focus;
        return a.seats.get(Math.min(a.seats.size() - 1, a.seats.size() / 2));
    }

    private static double rowsDepth(Assembly a) {
        return switch (a.layout) {
            case ARC -> 3.0 + 1.6 * 6;
            case RING -> 4.5 + 1.8 * 3;
            case AISLE -> 3.0 + 1.4 * 7;
            case CIRCLE -> 4.0;
        };
    }

    /** The best place left: the front for the children, then the nearest the front. */
    @Nullable
    private static Integer freeSeat(Assembly a, boolean child) {
        Set<Integer> taken = new HashSet<>(a.seated.values());
        for (int i = 0; i < a.seats.size(); i++) if (!taken.contains(i)) return i;
        return null;
    }

    /** The couple before the officiant; the child in the middle of its circle. */
    private static BlockPos principalSpot(Assembly a, int i) {
        Direction right = a.audience.getClockWise();
        if (a.kind == Kind.WEDDING) return a.focus.relative(a.audience, 2).relative(right, i == 0 ? -1 : 1);
        return a.focus.relative(a.audience, 1);
    }

    /** Work out where everybody can stand: the shape, laid on the ground, wherever there is room. */
    private static void prepare(ServerLevel level, Assembly a) {
        Direction fwd = a.audience;
        Direction right = fwd.getClockWise();
        List<double[]> offsets = offsets(a.layout);
        Set<Long> used = new HashSet<>();
        used.add(a.focus.asLong());
        for (double[] o : offsets) {
            double x = a.focus.getX() + 0.5 + fwd.getStepX() * o[1] + right.getStepX() * o[0];
            double z = a.focus.getZ() + 0.5 + fwd.getStepZ() * o[1] + right.getStepZ() * o[0];
            BlockPos col = BlockPos.containing(x, a.focus.getY(), z);
            BlockPos stand = standable(level, col, a.focus.getY());
            if (stand == null || !used.add(stand.asLong())) continue;
            a.seats.add(stand);
            if (a.seats.size() >= 140) break;
        }
        BlockPos podium = standable(level, a.focus, a.focus.getY());
        if (podium != null) a.focus = podium;
        // Who leads it.
        UUID elder = Villages.elder(a.village);
        a.host = elder;
        if (a.kind == Kind.WATCH) {
            // The changing of the watch is the senior guard's to lead.
            VillageFolkEntity senior = null;
            for (AssistantEntity x : Villages.folkOf(a.village)) {
                if (x instanceof VillageFolkEntity g && g.stationTask() == AssistantEntity.StationTask.GUARD
                        && (senior == null || g.veteranLevel() > senior.veteranLevel())) senior = g;
            }
            if (senior != null) a.host = senior.getUUID();
        }
        if (a.kind == Kind.FOUNDING && a.host == null) a.host = FoundingDay.eldest(a.village);   // nobody leads: the eldest reads
        if (a.kind == Kind.COMING_OF_AGE || a.kind == Kind.OPENING || a.host == null) {
            if (a.host == null) a.host = oldest(level, a.village);
        }
        // Who is expected.
        int n = 0;
        for (AssistantEntity x : Villages.folkOf(a.village)) {
            if (x instanceof VillageFolkEntity f && invited(a, f)) n++;
        }
        a.expected = n;
        script(level, a);
    }

    @Nullable
    private static UUID oldest(ServerLevel level, UUID village) {
        VillageFolkEntity best = null;
        for (AssistantEntity x : Villages.folkOf(village)) {
            if (!(x instanceof VillageFolkEntity f) || f.isBaby()) continue;
            if (best == null || f.persona().since() < best.persona().since()) best = f;
        }
        return best == null ? null : best.getUUID();
    }

    /** The places, front first: (across, out from the front). */
    static List<double[]> offsets(Layout layout) {
        List<double[]> out = new ArrayList<>();
        switch (layout) {
            case ARC -> {
                for (int row = 0; row < 7; row++) {
                    double rad = 3.0 + 1.6 * row, span = Math.toRadians(62);
                    int n = Math.max(3, (int) (2 * rad * Math.sin(span) / 1.25) + 1);
                    List<double[]> rowSeats = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        double ang = -span + 2 * span * i / (n - 1);
                        rowSeats.add(new double[]{ rad * Math.sin(ang), rad * Math.cos(ang), Math.abs(ang) });
                    }
                    rowSeats.sort((p, q) -> Double.compare(p[2], q[2]));       // the middle of each row first
                    out.addAll(rowSeats);
                }
            }
            case RING -> {
                for (int ring = 0; ring < 4; ring++) {
                    double rad = 4.5 + 1.8 * ring;
                    int n = (int) (2 * Math.PI * rad / 1.35);
                    for (int i = 0; i < n; i++) {
                        double ang = 2 * Math.PI * i / n;
                        out.add(new double[]{ rad * Math.sin(ang), rad * Math.cos(ang) });
                    }
                }
            }
            case AISLE -> {
                for (int row = 0; row < 8; row++) {
                    double d = 3.0 + 1.4 * row;
                    for (int k = 0; k < 4; k++) {
                        out.add(new double[]{ -(2.0 + 1.1 * k), d });
                        out.add(new double[]{ 2.0 + 1.1 * k, d });
                    }
                }
            }
            case CIRCLE -> {
                for (double rad : new double[]{ 2.6, 3.8 }) {
                    int n = rad < 3 ? 8 : 12;
                    for (int i = 0; i < n; i++) {
                        double ang = 2 * Math.PI * i / n;
                        out.add(new double[]{ rad * Math.sin(ang), rad * Math.cos(ang) });
                    }
                }
            }
        }
        return out;
    }

    /** Somewhere a folk can stand on this column, near this height: a floor, room for its head, dry. */
    @Nullable
    static BlockPos standable(ServerLevel level, BlockPos col, int y) {
        if (!level.isLoaded(col)) return null;
        for (int dy : new int[]{ 0, 1, -1, 2, -2, 3, -3 }) {
            BlockPos p = new BlockPos(col.getX(), y + dy, col.getZ());
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) continue;
            if (!level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) continue;
            if (!level.getFluidState(p).isEmpty()) continue;
            BlockPos floor = p.below();
            if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) continue;
            return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ the kinds

    @Nullable
    private static Assembly morning(ServerLevel level, Villages.Village v, long day) {
        BlockPos lectern = VillageBoards.lectern(v.id());
        Direction facing = VillageBoards.facingOf(v.id());
        Assembly a = lectern != null && facing != null
            ? new Assembly(v.id(), Kind.MORNING, "", day, lectern, facing, Layout.ARC)
            : new Assembly(v.id(), Kind.MORNING, "", day, v.centre(), Direction.SOUTH, Layout.ARC);
        return a;
    }

    @Nullable
    private static Assembly evening(ServerLevel level, Villages.Village v, Gatherings.Kind tonight, long day) {
        UUID id = v.id();
        return switch (tonight) {
            case WEDDING -> {
                Gatherings.Wedding w = Gatherings.wedding(id);
                if (w == null || held(id, Kind.WEDDING, day)) yield null;
                BlockPos chapel = Villages.builtAt(id, "chapel");
                Direction face = Direction.SOUTH;
                BlockPos at = v.centre().relative(Direction.NORTH, 3);
                // In the courtyard before the board, once it is laid: the couple at the board, the
                // village down the aisle between the benches. Else at the chapel, else on the square.
                BlockPos lectern = VillageBoards.lectern(id);
                Direction boardFacing = VillageBoards.facingOf(id);
                if (Villages.builtAt(id, "court") != null && lectern != null && boardFacing != null) {
                    at = lectern;
                    face = boardFacing;
                } else if (chapel != null) {
                    for (com.jrpetty.mcassistant.village.Ledger.Building b : com.jrpetty.mcassistant.village.Ledger.buildings(id)) {
                        if (b.structure().equals("chapel")) { face = b.facing(); at = b.anchor().relative(face, 6); break; }
                    }
                }
                Assembly a = new Assembly(id, Kind.WEDDING, w.names(), day, at, face, Layout.AISLE);
                a.principals.add(w.a());
                a.principals.add(w.b());
                yield a;
            }
            case VIGIL -> {
                if (held(id, Kind.VIGIL, day)) yield null;
                BlockPos yard = Villages.builtAt(id, "graveyard");
                yield new Assembly(id, Kind.VIGIL, Gatherings.describe(tonight, id), day,
                    yard != null ? yard : v.centre(), Direction.SOUTH, yard != null ? Layout.ARC : Layout.RING);
            }
            case CELEBRATION -> {
                if (held(id, Kind.CELEBRATION, day)) yield null;
                BlockPos court = Villages.builtAt(id, "court");
                yield new Assembly(id, Kind.CELEBRATION, Villages.ageOf(id).label, day, court != null ? court : v.centre(), Direction.SOUTH, Layout.RING);
            }
            case HONOUR -> {
                if (held(id, Kind.HONOUR, day)) yield null;
                BlockPos lectern = VillageBoards.lectern(id);
                Direction f = VillageBoards.facingOf(id);
                yield new Assembly(id, Kind.HONOUR, Gatherings.describe(tonight, id), day,
                    lectern != null ? lectern : v.centre(), f != null ? f : Direction.SOUTH, Layout.ARC);
            }
            case FEAST -> {
                if (held(id, Kind.FEAST, day)) yield null;
                yield new Assembly(id, Kind.FEAST, "", day, v.centre(), Direction.SOUTH, Layout.RING);
            }
        };
    }

    private static Assembly council(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        // In the council chamber of the leader's hall, once there is one; else the meeting hall.
        BlockPos seat = Villages.builtAt(id, "townhall");
        BlockPos hall = seat != null ? seat : Villages.builtAt(id, "hall");
        BlockPos lectern = VillageBoards.lectern(id);
        Direction facing = VillageBoards.facingOf(id);
        BlockPos at = hall != null ? hall : lectern != null ? lectern : v.centre();
        Assembly a = new Assembly(id, Kind.COUNCIL, "", day, at, facing != null ? facing : Direction.SOUTH, Layout.CIRCLE);
        Set<UUID> members = new HashSet<>();
        for (VillageFolkEntity m : Council.members(id)) members.add(m.getUUID());
        a.invited = members;
        return a;
    }

    /** An envoy from a neighbour is heard before the board: the village gathers to listen. */
    private static Assembly envoy(ServerLevel level, Villages.Village v, VillageFolkEntity guest) {
        UUID id = v.id();
        BlockPos lectern = VillageBoards.lectern(id);
        Direction facing = VillageBoards.facingOf(id);
        long day = level.getDayTime() / 24000L;
        Caravans.Trip trip = guest.trip();
        String from = trip == null ? "" : Villages.name(trip.from);
        Assembly a = new Assembly(id, Kind.ENVOY, from + "|" + guest.getUUID(), day, lectern != null ? lectern : v.centre(),
            facing != null ? facing : Direction.SOUTH, Layout.ARC);
        a.principals.add(guest.getUUID());
        return a;
    }

    private static int guards(UUID village) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == AssistantEntity.StationTask.GUARD && !a.isBaby()) n++;
        return n;
    }

    /** The changing of the watch, before the board: the guards only. */
    private static Assembly watch(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        BlockPos at = VillageBoards.lectern(id);
        Direction facing = VillageBoards.facingOf(id);
        Assembly a = new Assembly(id, Kind.WATCH, "", day, at != null ? at : v.centre(), facing != null ? facing : Direction.SOUTH, Layout.CIRCLE);
        Set<UUID> guards = new HashSet<>();
        for (AssistantEntity x : Villages.folkOf(id)) if (x.stationTask() == AssistantEntity.StationTask.GUARD && !x.isBaby()) guards.add(x.getUUID());
        a.invited = guards;
        return a;
    }

    private static Assembly election(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        BlockPos lectern = VillageBoards.lectern(id);
        Direction facing = VillageBoards.facingOf(id);
        return new Assembly(id, Kind.ELECTION, "", day, lectern != null ? lectern : v.centre(),
            facing != null ? facing : Direction.SOUTH, Layout.ARC);
    }

    /** What is said, line by line — written from the village as it is today. */
    private static void script(ServerLevel level, Assembly a) {
        UUID id = a.village;
        String name = Villages.name(id);
        RandomSource r = level.getRandom();
        List<Line> s = a.script;
        switch (a.kind) {
            case MORNING -> {
                s.add(new Line(null, FolkTalk.pick(r, "Good morning, " + name + "!", "Morning, all!", "Good morning, everyone!"), '*', null));
                String building = null;
                for (AssistantEntity x : Villages.folkOf(id)) {
                    Job j = x.peekJob();
                    if (j != null && j.type() == Job.Type.BUILD && j.arg() != null) { building = j.arg().split("\\|", 2)[0]; break; }
                }
                String next = Villages.nextProject(id);
                if (building != null) s.add(new Line(null, "The builders carry on with " + Villages.spoken(building) + " today.", ' ', null));
                else if (next != null) s.add(new Line(null, "Next to go up is " + Villages.spoken(next) + ".", '?', null));
                List<String> shorts = new ArrayList<>();
                for (Villages.Need n : Villages.needs(level, id)) {
                    if (n.task() == Villages.Task.BUILD || n.task() == Villages.Task.NONE) continue;
                    shorts.add(n.what());
                    if (shorts.size() >= 2) break;
                }
                if (!shorts.isEmpty()) s.add(new Line(null, "We're short of " + String.join(" and ", shorts) + " — put your backs into it!", '?', null));
                Orders.Order o = Orders.current(id);
                if (o != null) s.add(new Line(null, "The orders stand: " + o.title.toLowerCase(java.util.Locale.ROOT) + ".", ' ', null));
                for (String[] rep : Envoys.reports(id)) {
                    UUID by = null;
                    try { by = UUID.fromString(rep[0]); } catch (IllegalArgumentException ignored) { }
                    s.add(new Line(by, rep.length > 1 ? rep[1] : rep[0], '?', null));
                }
                for (String found : Scouts.reports(id)) s.add(new Line(null, found, '?', null));
                for (String money : Market.reports(id)) s.add(new Line(null, money, '!', null));
                long dayNow = level.getDayTime() / 24000L;
                Gatherings.Kind tonight = Gatherings.tonight(id, dayNow);
                if (tonight != null) s.add(new Line(null, "Tonight: " + Gatherings.describe(tonight, id) + ". Everyone welcome!", '!', null));
                else if (Market.daysToMarket(id, dayNow) == 0) s.add(new Line(null, "It's market day! Bring your coins to the square.", '!', null));
                s.add(new Line(null, FolkTalk.pick(r, "That's all. Off you go — and work safely!", "Right. To work, all of you!",
                    "Have a good day, everybody."), '!', null));
            }
            case OPENING -> {
                String what = Villages.spoken(a.subject);
                String builder = BUILDERS.remove(id + "/opening/" + a.subject);
                s.add(new Line(null, "Friends! " + capital(what) + " is finished.", '!', null));
                if (builder != null) s.add(new Line(null, "Our thanks to " + builder + ", who built it, and to everyone who carried a stone for it.", '!', null));
                String why = Villages.whyBuild(id, a.subject);
                s.add(new Line(null, "It is " + why + ".", '?', null));
                s.add(new Line(null, "I declare " + what + " open!", '!', null));
            }
            case FEAST -> {
                s.add(new Line(null, "Another week's work done, " + name + "!", '!', null));
                s.add(new Line(null, "Eat, drink and be merry — it's all out of our own stores, and well earned!", '!', null));
            }
            case WEDDING -> {
                String[] names = a.subject.split(" and ");
                s.add(new Line(null, "We are here to see " + a.subject + " wed.", '~', null));
                if (a.principals.size() == 2) {
                    s.add(new Line(a.principals.get(0), FolkTalk.pick(r, "I will.", "With all my heart."), ' ', null));
                    s.add(new Line(a.principals.get(1), FolkTalk.pick(r, "I will!", "I do."), ' ', null));
                }
                s.add(new Line(null, "Then before all of " + name + " — you are wed!", '!', null));
            }
            case VIGIL -> {
                s.add(new Line(null, "We are here for " + a.subject.replace("a vigil for ", "") + ".", '~', null));
                s.add(new Line(null, "A good soul, and a friend to many of us.", '~', null));
                s.add(new Line(null, "Rest well.", '~', null));
            }
            case CELEBRATION -> {
                s.add(new Line(null, "Friends — today " + name + " comes into " + a.subject + "!", '!', null));
                s.add(new Line(null, "We built it with our own hands. To " + name + "!", '!', null));
            }
            case HONOUR -> {
                s.add(new Line(null, "We are here for " + a.subject.replace("a celebration for ", "") + ", a true friend of " + name + ".", '?', null));
                s.add(new Line(null, "Three cheers! Hip hip —", '!', null));
            }
            case COUNCIL -> { }                                  // written below
            case WATCH -> {
                List<UUID> guards = new ArrayList<>(a.invited == null ? Set.of() : a.invited);
                guards.remove(a.host);
                guards.sort(java.util.Comparator.comparing(UUID::toString));
                s.add(new Line(null, "The watch changes. Report.", '~', null));
                String seen = Raids.why(id) != null ? "Trouble at the walls today." : FolkTalk.pick(r, "Quiet day on the walls.",
                    "All quiet. A fox by the east gate, nothing worse.", "Nothing moving out there but the sheep.");
                if (!guards.isEmpty()) s.add(new Line(guards.get(0), seen, ' ', null));
                if (guards.size() > 1) s.add(new Line(guards.get(1), FolkTalk.pick(r, "I have the watch.", "I'll take the walls tonight."), ' ', null));
                s.add(new Line(null, FolkTalk.pick(r, "Stay sharp. Goodnight, all.", "Eyes open. The village sleeps on us."), '~', null));
            }
            case ENVOY -> {
                if (a.principals.isEmpty() || !(level.getEntity(a.principals.get(0)) instanceof VillageFolkEntity e)) return;
                Caravans.Trip t = e.trip();
                if (t == null || t.errand == null) return;
                UUID guest = e.getUUID();
                s.add(new Line(null, "We have a visitor: " + e.displayNameCap() + ", from " + Villages.name(t.from) + ".", '*', null));
                s.add(new Line(guest, Envoys.asks(t.from, id, t.errand, e, t), '?', null));
                Envoys.Answer ans = Envoys.answer(level, id, t.from, t.errand, e, t);
                s.add(new Line(null, ans.said(), ans.yes() ? '!' : '?', ans.effect()));
                s.add(new Line(guest, ans.yes() ? FolkTalk.pick(r, "Thank you! They'll be glad to hear it.", "I'll tell them at once!")
                    : FolkTalk.pick(r, "I'll take your answer home, then.", "So be it. I'll tell them."), ' ', () -> Envoys.heard(guest)));
            }
            case ELECTION -> Elections.script(level, id, s, r);
            case FOUNDING -> FoundingDay.script(level, a, s, r);
            case COMING_OF_AGE -> {
                String[] parts = a.subject.split("\\|", -1);
                String who = parts[0];
                String trade = parts.length > 1 ? parts[1] : "";
                s.add(new Line(null, who + " is grown!", '!', null));
                if (!a.principals.isEmpty()) {
                    s.add(new Line(a.principals.get(0), trade.isEmpty() ? "I'll find my trade soon, you'll see!"
                        : "I'll be a " + trade + " — I've learned from the best!", '!', null));
                }
                s.add(new Line(null, "Welcome among the grown folk, " + who + ".", '!', null));
            }
        }
        // The council's decision, read out once the vote is taken.
        if (a.kind == Kind.COUNCIL) {
            s.clear();
            s.add(new Line(null, "The council is sitting.", ' ', null));
            Contentment.View view = Contentment.of(level, id);
            if (!view.bad().isEmpty()) {
                String petition = view.bad().get(0);
                s.add(new Line(null, "There are complaints: " + petition + ".", '?', () -> {
                    if (petition.contains("home") || petition.contains("bed") || petition.contains("room")) Villages.request(id, "house");
                }));
            }
            s.add(new Line(null, "To the vote: what do we build next?", '?', () -> Council.revote(id)));
            s.add(new Line(null, DECISION, '!', null));          // read out once the vote is in (step)
            s.add(new Line(null, "So it is decided. Thank you, all.", ' ', null));
        }
    }

    /** What it is, in a few words: "the opening of the smithy". */
    public static String describe(Assembly a) {
        return switch (a.kind) {
            case OPENING -> "the opening of " + Villages.spoken(a.subject);
            case WEDDING -> "the wedding of " + a.subject;
            case VIGIL -> a.subject;
            case COMING_OF_AGE -> a.subject.split("\\|", 2)[0] + "'s coming of age";
            case ENVOY -> "the envoy from " + a.subject.split("\\|", 2)[0];
            case CELEBRATION -> "the celebration of " + a.subject;
            case HONOUR -> a.subject;
            default -> a.kind.label;
        };
    }

    /** For the board: what is under way, or null. */
    @Nullable
    public static String now(UUID village) {
        Assembly a = NOW.get(village);
        return a == null ? null : describe(a);
    }

    /** For the board: what is planned for this evening (openings, comings of age). */
    public static List<String> planned(UUID village) {
        List<String> out = new ArrayList<>();
        List<Assembly> list = PLANNED.get(village);
        if (list != null) for (Assembly a : list) out.add(describe(a));
        return out;
    }

    /** For the tests: the phase of what is under way, how many have found a place, and the line reached. */
    public static String debug(UUID village) {
        Assembly a = NOW.get(village);
        return a == null ? "none" : a.kind + " " + a.phase + " seated=" + a.seated.size() + "/" + a.expected
            + " seats=" + a.seats.size() + " line=" + a.line + "/" + a.script.size();
    }

    /** For the tests: {phase, seated, expected, line, lines, seats}, or null when nothing is under way. */
    @Nullable
    public static int[] progress(UUID village) {
        Assembly a = NOW.get(village);
        return a == null ? null : new int[]{ a.phase.ordinal(), a.seated.size(), a.expected, a.line, a.script.size(), a.seats.size() };
    }

    /** Start one now (the tests, and /village gather). */
    public static boolean startNow(ServerLevel level, Villages.Village v, Kind kind) {
        long day = level.getDayTime() / 24000L;
        Assembly a = switch (kind) {
            case MORNING -> morning(level, v, day);
            case COUNCIL -> council(level, v, day);
            case ELECTION -> election(level, v, day);
            case FEAST -> new Assembly(v.id(), Kind.FEAST, "", day, v.centre(), Direction.SOUTH, Layout.RING);
            case FOUNDING -> FoundingDay.assembly(level, v, day);
            default -> null;
        };
        if (a == null) return false;
        a.phaseAt = level.getGameTime();
        NOW.put(v.id(), a);
        step(level, a, level.getGameTime());
        return NOW.get(v.id()) == a;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
