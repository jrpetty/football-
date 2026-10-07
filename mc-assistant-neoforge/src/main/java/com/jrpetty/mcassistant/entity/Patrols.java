package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The watch's day, between the bells: the streets walked, the village's people kept safe,
 * and its leader seen about.
 * <ul>
 * <li><b>The beat.</b> A guard used to walk the four corners of its own plot by day, which
 *     is twelve blocks of grass by the stores, and the ring street by night. Now the watch
 *     walks the town's streets at any hour: the corners of the square, the ring street and
 *     each street out as far as the town reaches, and the street at the door of every
 *     building the village has put up. The stops are cut into as many beats as there are
 *     guards, a slice of the town each, so that four guards are four parts of the town
 *     watched and not one crowd at the stores. At each stop a guard stands a moment and
 *     looks about, and now and then has a word for whoever is passing.</li>
 * <li><b>Help.</b> A monster within sixteen blocks of one of the village's people (a folk,
 *     or a player who is a citizen or a friend) anywhere in the town draws the nearest guard
 *     who is free, at a run. A folk a monster hurts shouts for the watch, and the nearest
 *     guard answers. A guard lets a monster go once it has run thirty-two blocks past the
 *     town's edge: the watch is the village's, not the countryside's.</li>
 * <li><b>The leader's escort.</b> Once the village has two guards (or a barracks), the best
 *     of them walks with the leader whenever it is out and about: to the assembly, to the
 *     board, to the poll, to a wedding. It keeps two to four blocks off, stands by while the
 *     leader speaks, and fights whatever comes at it. When the leader is at its work, at
 *     home or asleep, the escort goes back to its beat. Not at night: the night is the
 *     watch's, and the leader is in bed.</li>
 * </ul>
 * When the bell rings none of this applies: the walls and the gates are the watch's orders
 * then (Raids).
 */
public final class Patrols {

    private Patrols() {}

    /** How near a monster has to be to one of the village's people to send a guard. */
    static final int DANGER = 16;
    /** How far past the town's edge a guard follows a monster before it lets it go. */
    static final int CHASE = 32;
    /** How far past the town's edge the streets still count as its guard's beat. */
    static final int ABROAD = 24;
    /** The least a guard stands at each stop on its beat, looking about (ticks). */
    static final int LOOK = 40;
    /** The longest a leg of the beat is given before the guard gives it up for the next. */
    static final int LEG_MOST = 300;
    /** The most beats a town's streets are cut into. */
    static final int MOST_BEATS = 8;
    /** How near the leader a monster has to be for its escort to go for it. */
    static final int GUARDING = 10;
    /** The leader settled at home or at work this long, and its escort goes back to its beat. */
    static final int STAND_DOWN = 100;

    /** A village's beats, as last worked out: for how many guards, over how many buildings. */
    private record Beats(List<List<BlockPos>> beats, int guards, int buildings, long at) {}

    /** Where one guard is on its round. */
    private static final class Round {
        int beat = -1, size = -1, stop;
        boolean walking;
        long legStart, until, greeted = -100000L, pathAt = -100000L;
        @Nullable BlockPos heading;
    }

    /** Who walks with the leader, chosen when. */
    private record Escort(UUID guard, String name, long at) {}

    /** Which guard went after which monster, for whom, and when. */
    private record Answer(UUID guard, UUID citizen, long at) {}

    private static final Map<UUID, Beats> BEATS = new ConcurrentHashMap<>();
    private static final Map<UUID, Round> ROUNDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Escort> ESCORTS = new ConcurrentHashMap<>();
    /** The guards at their leader's shoulder just now. */
    private static final Set<UUID> ESCORTING = ConcurrentHashMap.newKeySet();
    /** Since when each village's leader has been at home or at its work (the escort stands down after a while). */
    private static final Map<UUID, Long> SETTLED = new ConcurrentHashMap<>();
    /** By monster: the guard sent after it. */
    private static final Map<UUID, Answer> ANSWERED = new ConcurrentHashMap<>();
    /** By folk: when it last said anything of the watch's (a shout, a greeting), so it does not chatter. */
    private static final Map<UUID, Long> SAID = new ConcurrentHashMap<>();
    /** By folk: when it last shouted for the watch. */
    private static final Map<UUID, Long> CRIED = new ConcurrentHashMap<>();
    /** When the list of who was sent after what was last cleared of old news. */
    private static long pruned = -100000L;

    public static void resetForTests() {
        BEATS.clear();
        ROUNDS.clear();
        ESCORTS.clear();
        ESCORTING.clear();
        SETTLED.clear();
        ANSWERED.clear();
        SAID.clear();
        CRIED.clear();
        pruned = -100000L;
    }

    // ------------------------------------------------------------------ who keeps watch

    /** The village's own watch: its grown guards not hired out to anybody, in a fixed order. */
    static List<VillageFolkEntity> watch(@Nullable UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity g && g.stationTask() == StationTask.GUARD && !g.isBaby() && !g.isHired()) out.add(g);
        }
        out.sort(Comparator.comparing(Entity::getUUID));
        return out;
    }

    /** Off on the road, out on the land, through the gateway: not about the village at all. */
    static boolean away(VillageFolkEntity f) {
        return f.isHired() || f.trip() != null || f.expedition() != null || Nether.away(f) || Scouts.out(f);
    }

    /** Is this a monster the watch goes after? The neutral ones are left be, and so is what flies
     *  overhead or would make short work of a guard (the bell is for those). */
    static boolean hostile(Entity e) {
        if (!(e instanceof Mob m) || !m.isAlive() || !(e instanceof Enemy)) return false;
        return !(e instanceof net.minecraft.world.entity.monster.EnderMan)
            && !(e instanceof net.minecraft.world.entity.monster.ZombifiedPiglin)
            && !(e instanceof net.minecraft.world.entity.monster.Phantom)
            && !(e instanceof net.minecraft.world.entity.monster.Ghast)
            && !(e instanceof net.minecraft.world.entity.monster.Ravager)
            && !(e instanceof net.minecraft.world.entity.monster.warden.Warden);
    }

    /** How far out from the heart, the town's way: the larger of the two. */
    private static int out(BlockPos c, double x, double z) {
        return (int) Math.max(Math.abs(x - c.getX()), Math.abs(z - c.getZ()));
    }

    private static double flat(Entity a, double x, double z) {
        double dx = a.getX() - x, dz = a.getZ() - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Is this guard about the town's streets (not off past its edge), so that its beat is its plot? */
    public static boolean onTheStreets(VillageFolkEntity g) {
        if (g.stationTask() != StationTask.GUARD || g.villageCentre() == null || g.ownerId() == null || away(g)) return false;
        return out(g.villageCentre(), g.getX(), g.getZ()) <= Villages.townReach(g.ownerId()) + ABROAD;
    }

    // ------------------------------------------------------------------ the beats

    /**
     * Every stop of the watch's rounds, on the ground: the corners of the square inside the wall;
     * the corners of the ring street and of each street out, and where each crosses an avenue,
     * as far as the town reaches; and the street at the door of each building it has put up.
     */
    static List<BlockPos> stops(ServerLevel level, Villages.Village v) {
        BlockPos c = v.centre();
        int reach = Villages.townReach(v.id());
        List<int[]> offsets = new ArrayList<>();
        int sq = TownPlan.PLAZA - 3;
        for (int[] s : new int[][]{ { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } }) offsets.add(new int[]{ s[0] * sq, s[1] * sq });
        for (int k = 0; k < TownPlan.RINGS; k++) {
            int r = TownPlan.RING + 1 + k * TownPlan.PERIOD;
            if (r > reach) break;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0) offsets.add(new int[]{ dx * r, dz * r });
                }
            }
        }
        for (Ledger.Building b : Ledger.buildings(v.id())) {
            String s = b.structure();
            if (s.equals("fortify") || s.equals("colony") || s.equals("road")) continue;
            int bx = b.anchor().getX() - c.getX(), bz = b.anchor().getZ() - c.getZ();
            if (Math.max(Math.abs(bx), Math.abs(bz)) > reach + 8) continue;
            int[] at = streetBy(bx, bz);
            if (at != null) offsets.add(at);
        }
        List<BlockPos> out = new ArrayList<>();
        for (int[] o : offsets) {
            int x = c.getX() + o[0], z = c.getZ() + o[1];
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;   // not loaded: not walked
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (Math.abs(y - c.getY()) > 24) continue;                                   // a cliff or a pit, not a street
            if (!level.getFluidState(p.below()).isEmpty() || !level.getFluidState(p).isEmpty()) continue;
            boolean near = false;
            for (BlockPos q : out) {
                if (Math.abs(q.getX() - x) + Math.abs(q.getZ() - z) < 6) { near = true; break; }
            }
            if (!near) out.add(p);
        }
        return out;
    }

    /** The street nearest a building (its door is on one, by the plan): an offset from the heart. */
    @Nullable
    static int[] streetBy(int bx, int bz) {
        int[] best = null;
        int bestD = Integer.MAX_VALUE;
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                int d = dx * dx + dz * dz;
                if (d >= bestD || !TownPlan.isStreet(bx + dx, bz + dz)) continue;
                bestD = d;
                best = new int[]{ bx + dx, bz + dz };
            }
        }
        return best;
    }

    /** Which slice of the town, round the heart, a spot is in, of so many. */
    static int sector(BlockPos c, BlockPos p, int n) {
        double a = Math.atan2(p.getZ() - c.getZ(), p.getX() - c.getX());
        int s = (int) Math.floor((a + Math.PI) / (2.0 * Math.PI) * n);
        return Math.max(0, Math.min(n - 1, s));
    }

    /**
     * The stops cut into so many beats, a slice of the town round the heart each, and each beat put
     * in walking order: round the inner streets one way, back round the outer ones the other.
     */
    static List<List<BlockPos>> cut(BlockPos c, List<BlockPos> stops, int n) {
        List<List<BlockPos>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new ArrayList<>());
        for (BlockPos p : stops) out.get(sector(c, p, n)).add(p);
        for (int i = 0; i < n; i++) {
            // More guards than the town has corners: the spare one walks the lot.
            if (out.get(i).isEmpty()) out.set(i, new ArrayList<>(stops));
            out.get(i).sort((a, b) -> {
                int ba = band(c, a), bb = band(c, b);
                if (ba != bb) return Integer.compare(ba, bb);
                double ta = Math.atan2(a.getZ() - c.getZ(), a.getX() - c.getX());
                double tb = Math.atan2(b.getZ() - c.getZ(), b.getX() - c.getX());
                return ba % 2 == 0 ? Double.compare(ta, tb) : Double.compare(tb, ta);
            });
        }
        return out;
    }

    /** Which street out a stop is on: the square and the ring street nought, the next one, and so on. */
    private static int band(BlockPos c, BlockPos p) {
        int d = out(c, p.getX(), p.getZ());
        return d <= TownPlan.RING + 3 ? 0 : 1 + (d - TownPlan.RING - 4) / TownPlan.PERIOD;
    }

    /** The village's beats for a watch of so many, worked out again as the town grows. */
    static List<List<BlockPos>> beats(ServerLevel level, Villages.Village v, int guards) {
        UUID id = v.id();
        int buildings = Ledger.buildings(id).size();
        int n = Math.max(1, Math.min(MOST_BEATS, guards));
        long now = level.getGameTime();
        Beats b = BEATS.get(id);
        if (b != null && b.guards() == n && b.buildings() == buildings && now - b.at() < 6000L && now >= b.at()) return b.beats();
        List<BlockPos> stops = stops(level, v);
        List<List<BlockPos>> cut = stops.isEmpty() ? List.of() : cut(v.centre(), stops, n);
        BEATS.put(id, new Beats(cut, n, buildings, now));
        return cut;
    }

    /** This guard's beat (its stops, in order), or an empty list. */
    static List<BlockPos> beatOf(VillageFolkEntity g, ServerLevel level) {
        Villages.Village v = Villages.get(g.ownerId());
        if (v == null) return List.of();
        List<VillageFolkEntity> watch = watch(v.id());
        int rank = watch.indexOf(g);
        if (rank < 0) return List.of();
        List<List<BlockPos>> beats = beats(level, v, watch.size());
        return beats.isEmpty() ? List.of() : beats.get(rank % beats.size());
    }

    /**
     * The guard's round of its beat, by day and by night: on to the next stop, a stand and a look
     * about when it gets there, a word for whoever is passing. True while it is on its round (its
     * station brain then has nothing else to do).
     */
    public static boolean round(VillageFolkEntity g, ServerLevel level) {
        UUID id = g.ownerId();
        Villages.Village v = Villages.get(id);
        if (v == null || g.villageCentre() == null || g.movementBlocked() || Raids.underAlarm(id) || away(g)) return false;
        List<VillageFolkEntity> watch = watch(id);
        int rank = watch.indexOf(g);
        if (rank < 0) return false;
        List<List<BlockPos>> beats = beats(level, v, watch.size());
        if (beats.isEmpty()) return false;
        int beatNo = rank % beats.size();
        List<BlockPos> beat = beats.get(beatNo);
        if (beat.isEmpty()) return false;
        Round r = ROUNDS.computeIfAbsent(g.getUUID(), k -> new Round());
        long now = level.getGameTime();
        if (r.beat != beatNo || r.size != beat.size()) {
            // A new beat (or the old one grown): start from its nearest stop. A second guard on the
            // same beat starts halfway round it, so the two are not one pair of boots.
            r.beat = beatNo;
            r.size = beat.size();
            int nearest = 0;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < beat.size(); i++) {
                double d = beat.get(i).distSqr(g.blockPosition());
                if (d < best) { best = d; nearest = i; }
            }
            r.stop = Math.floorMod(nearest + (rank / beats.size()) * (beat.size() / 2), beat.size());
            r.walking = false;
            r.until = now;
        }
        PathNavigation nav = g.getNavigation();
        if (r.walking) {
            if (!nav.isDone() && now - r.legStart < LEG_MOST) return true;    // on its way
            if (!nav.isDone()) nav.stop();                                    // a leg it can't finish: on to the next
            r.walking = false;
            r.until = now + LOOK + g.getRandom().nextInt(60);                // a stand, and a look about
        }
        if (now < r.until) {
            lookAbout(g, level);
            reassure(g, level, r, now);
            return true;
        }
        for (int i = 0; i < beat.size(); i++) {
            int k = Math.floorMod(r.stop + i, beat.size());
            BlockPos to = beat.get(k);
            if (to.distSqr(g.blockPosition()) < 9.0) continue;               // standing on it already
            if (nav.moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 0.8D)) {
                r.stop = Math.floorMod(k + 1, beat.size());
                r.walking = true;
                r.legStart = now;
                r.heading = to;
                g.brain("walking the beat");
                return true;
            }
        }
        r.until = now + LOOK;                                                 // nowhere to be got to just now
        return false;
    }

    /** A look up and down the street: at whoever is nearest, or somewhere about. */
    private static void lookAbout(VillageFolkEntity g, ServerLevel level) {
        if (g.getRandom().nextInt(3) != 0) return;
        List<VillageFolkEntity> near = level.getEntitiesOfClass(VillageFolkEntity.class, g.getBoundingBox().inflate(8.0),
            f -> f != g && f.isAlive());
        if (!near.isEmpty() && g.getRandom().nextBoolean()) {
            g.getLookControl().setLookAt(near.get(g.getRandom().nextInt(near.size())), 30.0F, 30.0F);
            return;
        }
        double a = g.getRandom().nextDouble() * Math.PI * 2.0;
        g.getLookControl().setLookAt(g.getX() + Math.cos(a) * 8.0, g.getEyeY(), g.getZ() + Math.sin(a) * 8.0);
    }

    /** Now and then, at a stop, a word for a folk passing by: the watch is about, and all is well. */
    private static void reassure(VillageFolkEntity g, ServerLevel level, Round r, long now) {
        if (now - r.greeted < 2400L || g.getRandom().nextInt(4) != 0) return;
        VillageFolkEntity f = null;
        double best = 7.0 * 7.0;
        for (VillageFolkEntity x : level.getEntitiesOfClass(VillageFolkEntity.class, g.getBoundingBox().inflate(7.0),
                x -> x != g && x.isAlive() && !x.isSleeping() && x.stationTask() != StationTask.GUARD
                    && x.ownerId() != null && x.ownerId().equals(g.ownerId()) && x.talkPartner() == null)) {
            double d = x.distanceToSqr(g);
            if (d < best) { best = d; f = x; }
        }
        if (f == null) return;
        r.greeted = now;
        String you = f.displayNameCap(), me = g.displayNameCap();
        var rnd = g.getRandom();
        String line;
        if (f.isBaby()) {
            line = level.isNight() ? FolkTalk.pick(rnd, "Off home with you, " + you + " — it's no hour to be playing out.",
                    "Bed, young " + you + ". The street's mine till morning.")
                : FolkTalk.pick(rnd, "Mind how you go, little one.", "Stay where folk can see you, " + you + ".");
        } else if (level.isNight()) {
            line = FolkTalk.pick(rnd, "Evening, " + you + ". Sleep easy — I'm about.", "Get yourself home, " + you + "; I'll see the street's safe.",
                "All's well, " + you + ". Go on in.");
        } else {
            String hello = level.getDayTime() % 24000L < 6000L ? "Morning" : "Afternoon";
            line = FolkTalk.pick(rnd, hello + ", " + you + ". All quiet on my beat.", "Don't you fret, " + you + " — I've an eye on the street.",
                "All's well, " + you + ". Nothing's getting past me today.");
        }
        g.getLookControl().setLookAt(f, 30.0F, 30.0F);
        FolkTalk.speak(g, FolkTalk.manner(g, line));
        SAID.put(g.getUUID(), now);
        if (rnd.nextInt(3) == 0) {
            f.sayLater(FolkTalk.pick(rnd, "Thank you, " + me + ".", "Good to see you about, " + me + ".", "Bless you, " + me + "."), 40);
        }
    }

    // ------------------------------------------------------------------ keeping them safe

    /** Is this player one of the village's own: a citizen, or a friend of the village? */
    static boolean belongs(UUID village, Player p, long now) {
        if (p.isSpectator() || !p.isAlive()) return false;
        if (Citizens.is(village, p.getUUID())) return true;
        return Standing.of(village, p.getUUID(), now).title().atLeast(Standing.Title.FRIEND);
    }

    /** Is this guard free to be sent after a monster? */
    static boolean free(VillageFolkEntity g, BlockPos centre, int reach) {
        if (!g.isAlive() || g.isBaby() || g.isSleeping() || away(g) || g.onWatch()) return false;
        if (ESCORTING.contains(g.getUUID())) return false;                       // the leader's, while it walks
        if (Police.engaged(g)) return false;                                     // [police] a chase, a prisoner on the lead, a fight
        if (g.talkPartner() != null || g.companionPlayer() != null || g.guidePlayer() != null) return false;
        LivingEntity t = g.getTarget();
        if (t != null && t.isAlive()) return false;
        if (g.shouldDisengage()) return false;
        return out(centre, g.getX(), g.getZ()) <= reach + ABROAD;
    }

    /** Can this guard take that on? A creeper only from range. */
    static boolean canTake(VillageFolkEntity g, Mob m) {
        return !(m instanceof Creeper) || g.canSnipeCreepers();
    }

    /** Is a guard of the village already after this monster? */
    static boolean taken(UUID village, Mob m) {
        for (VillageFolkEntity g : watch(village)) if (g.getTarget() == m) return true;
        return false;
    }

    /**
     * The watch's look round a village, once a second: a monster near one of its people draws the
     * nearest free guard. And the leader's escort chosen afresh, for the board.
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        if (!level.isLoaded(v.centre())) return;
        UUID id = v.id();
        long now = level.getGameTime();
        // Who was sent after what is kept a few minutes, for the asking, and then forgotten.
        if (now - pruned > 1200L || now < pruned) {
            pruned = now;
            ANSWERED.values().removeIf(a -> now - a.at() > 6000L || now < a.at());
        }
        chooseEscort(id, now);
        if (Raids.underAlarm(id)) return;              // the bell: the walls and the gates (Raids)
        BlockPos c = v.centre();
        int reach = Villages.townReach(id);
        List<VillageFolkEntity> free = new ArrayList<>();
        for (VillageFolkEntity g : watch(id)) if (free(g, c, reach)) free.add(g);
        if (free.isEmpty()) return;
        int edge = reach + DANGER;
        AABB box = new AABB(c.getX() - edge - DANGER, c.getY() - 24, c.getZ() - edge - DANGER,
            c.getX() + edge + DANGER + 1, c.getY() + 24, c.getZ() + edge + DANGER + 1);
        // Up where the folk are: one in a cave under the town is no danger to anybody.
        List<Mob> monsters = level.getEntitiesOfClass(Mob.class, box,
            m -> hostile(m) && (m.getY() >= c.getY() - 8 || level.canSeeSky(m.blockPosition())));
        if (monsters.isEmpty()) return;
        List<LivingEntity> people = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && f.stationTask() != StationTask.GUARD
                    && out(c, f.getX(), f.getZ()) <= edge) people.add(f);
        }
        for (Player p : level.players()) {
            if (out(c, p.getX(), p.getZ()) <= edge && Math.abs(p.getY() - c.getY()) < 24 && belongs(id, p, now)) people.add(p);
        }
        if (people.isEmpty()) return;
        for (Mob m : monsters) {
            if (free.isEmpty()) break;
            if (taken(id, m)) continue;
            LivingEntity near = null;
            double best = DANGER * DANGER;
            for (LivingEntity who : people) {
                if (Math.abs(who.getY() - m.getY()) > 8) continue;
                double d = who.distanceToSqr(m.getX(), who.getY(), m.getZ());
                if (d <= best) { best = d; near = who; }
            }
            if (near == null) continue;
            VillageFolkEntity g = nearestOf(free, m);
            if (g == null) continue;
            send(g, m, near, now, false);
            free.remove(g);
        }
    }

    @Nullable
    private static VillageFolkEntity nearestOf(List<VillageFolkEntity> guards, Mob m) {
        VillageFolkEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (VillageFolkEntity g : guards) {
            if (!canTake(g, m)) continue;
            double d = g.distanceToSqr(m);
            if (d < bestD) { bestD = d; best = g; }
        }
        return best;
    }

    /** A guard sent after a monster, at a run, with a word for whoever it is after. */
    static void send(VillageFolkEntity g, Mob m, LivingEntity forWhom, long now, boolean called) {
        // Whatever it was about (a sweep of the drops, a trip to the stores) waits: a job under
        // way would hold its legs, and the fight could not have them.
        if (g.peekJob() != null) g.clearQueue();
        g.equipBestWeapon();
        g.setTarget(m);
        g.getNavigation().moveTo(m, 1.2D);
        ANSWERED.put(m.getUUID(), new Answer(g.getUUID(), forWhom.getUUID(), now));
        g.brain("after a " + m.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT));
        String who = forWhom instanceof VillageFolkEntity f ? f.displayNameCap() : forWhom.getName().getString();
        var r = g.getRandom();
        say(g, now, 60, called
            ? FolkTalk.pick(r, "Coming, " + who + "!", "Hold on, " + who + " — I'm coming!", "I hear you, " + who + "!")
            : FolkTalk.pick(r, "Stand back, " + who + " — I've got it!", "Leave it to me, " + who + ".", "I see it. Get behind me, " + who + "!"));
    }

    /** A line of the watch's, not more than once in so many ticks from the same folk. */
    private static void say(VillageFolkEntity f, long now, int gap, String line) {
        Long last = SAID.get(f.getUUID());
        if (last != null && now - last < gap && now >= last) return;
        SAID.put(f.getUUID(), now);
        FolkTalk.speak(f, line);
    }

    /**
     * A folk set on by a monster shouts for the watch (VillageFolkEntity.hurt): the nearest free
     * guard answers, at a run. Returns whether there was a guard to shout for (then that is the
     * cry, rather than one for a passing player).
     */
    public static boolean cryForHelp(VillageFolkEntity f, LivingEntity attacker) {
        UUID village = f.ownerId();
        if (village == null || !(f.level() instanceof ServerLevel level) || !(attacker instanceof Mob m) || !hostile(m)) return false;
        if (f.stationTask() == StationTask.GUARD || Raids.underAlarm(village)) return false;
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        long now = level.getGameTime();
        int reach = Villages.townReach(village);
        VillageFolkEntity coming = null;
        for (VillageFolkEntity g : watch(village)) {
            if (g.getTarget() == m) { coming = g; break; }
        }
        if (coming == null) {
            List<VillageFolkEntity> free = new ArrayList<>();
            for (VillageFolkEntity g : watch(village)) {
                if (free(g, v.centre(), reach) && g.distanceToSqr(f) < 64.0 * 64.0) free.add(g);
            }
            coming = nearestOf(free, m);
            if (coming == null) return false;
            send(coming, m, f, now, true);
        }
        Long last = CRIED.get(f.getUUID());
        if (last == null || now - last >= 100L || now < last) {
            CRIED.put(f.getUUID(), now);
            var r = f.getRandom();
            FolkTalk.speak(f, FolkTalk.pick(r, "Guard! Help!", "Help! Guard!", "Guard — over here!", coming.displayNameCap() + "! Help me!"));
        }
        return true;
    }

    /**
     * A chase that has left the village is given up: a guard after a monster that has run off past
     * the town's edge (or that has led it there) lets it go, and its round brings it home.
     */
    static void leash(VillageFolkEntity g, ServerLevel level, UUID village, long now) {
        LivingEntity t = g.getTarget();
        if (t == null || g.onWatch() || away(g) || ESCORTING.contains(g.getUUID())) return;
        Villages.Village v = Villages.get(village);
        if (v == null) return;
        int edge = Villages.townReach(village) + CHASE;
        BlockPos c = v.centre();
        if (out(c, t.getX(), t.getZ()) <= edge && out(c, g.getX(), g.getZ()) <= edge + 8) return;
        g.setTarget(null);
        g.getNavigation().stop();
        ANSWERED.remove(t.getUUID());
        g.brain("let a monster go at the village's edge");
        var r = g.getRandom();
        say(g, now, 200, FolkTalk.pick(r, "Let it go — it's out of the village.", "And stay out!", "Off with you, then."));
    }

    // ------------------------------------------------------------------ the leader's escort

    /**
     * Who walks with the village's leader: the best of its guards (the one already at it, if it is
     * as good as any), once the village has two guards, or a barracks and one. Looked at afresh
     * every ten seconds. Null when it has none. A guard at the butts with its six arrows (Archery) is
     * not taken off them: the walk to the line would run out while it was away, and it shot from where
     * it stood, across the town. Another walks with the leader, or the leader walks alone a while.
     */
    @Nullable
    static VillageFolkEntity chooseEscort(UUID village, long now) {
        Escort e = ESCORTS.get(village);
        if (e != null && now - e.at() < 200L && now >= e.at()) {
            for (VillageFolkEntity g : watch(village)) if (g.getUUID().equals(e.guard()) && !Archery.busy(g) && !Police.engaged(g)) return g;
        }
        VillageFolkEntity elder = Orders.elderOf(village);
        List<VillageFolkEntity> watch = watch(village);
        watch.remove(elder);
        boolean barracks = Villages.hasBuilt(village, "barracks") || Villages.builtAt(village, "barracks") != null;
        boolean enough = watch.size() >= 2 || (!watch.isEmpty() && barracks);
        VillageFolkEntity best = null;
        if (enough && elder != null) {
            for (VillageFolkEntity g : watch) {
                if (!g.isAlive() || away(g) || Archery.busy(g)) continue;
                if (Police.engaged(g)) continue;                      // [police] a prisoner on the lead, a chase: another walks with the leader
                if (best == null || g.veteranLevel() > best.veteranLevel()
                        || (g.veteranLevel() == best.veteranLevel() && e != null && g.getUUID().equals(e.guard()))) best = g;
            }
        }
        if (best == null) {
            if (e != null) ESCORTING.remove(e.guard());
            ESCORTS.remove(village);
            return null;
        }
        if (e != null && !e.guard().equals(best.getUUID())) ESCORTING.remove(e.guard());
        ESCORTS.put(village, new Escort(best.getUUID(), best.displayNameCap(), now));
        return best;
    }

    /** The leader's escort, by id, as last chosen (null if it has none). */
    @Nullable
    public static UUID escortOf(@Nullable UUID village) {
        Escort e = village == null ? null : ESCORTS.get(village);
        return e == null ? null : e.guard();
    }

    /** For the status and the board: "escorted by Bram", or nothing when the leader walks alone. */
    public static String escortLine(@Nullable UUID village) {
        Escort e = village == null ? null : ESCORTS.get(village);
        return e == null ? "" : "escorted by " + e.name();
    }

    /** Is this guard at its leader's shoulder just now? */
    public static boolean escorting(VillageFolkEntity g) {
        return ESCORTING.contains(g.getUUID());
    }

    /**
     * Is the leader out and about (on its way somewhere, at the assembly, at the board, talking to
     * somebody), rather than at its work, at home or asleep?
     */
    static boolean about(VillageFolkEntity elder, UUID village) {
        if (!elder.isAlive() || elder.isSleeping() || elder.isBaby() || away(elder)) return false;
        Villages.Village v = Villages.get(village);
        if (v == null || out(v.centre(), elder.getX(), elder.getZ()) > Villages.townReach(village) + CHASE) return false;
        if (Assemblies.attending(elder) || elder.talkPartner() != null) return true;      // speaking: stood by
        WorkZone z = elder.workZone();
        if (z != null && z.containsColumn(elder.blockPosition())) return false;          // at its work
        BlockPos home = Homes.homeOf(elder);
        if (home != null && flat(elder, home.getX() + 0.5, home.getZ() + 0.5) < 8.0) return false;
        BlockPos bed = elder.bedPos();
        return bed == null || flat(elder, bed.getX() + 0.5, bed.getZ() + 0.5) >= 6.0;
    }

    /**
     * From a guard's tick, every few ticks: the chase let go at the village's edge, and, for the
     * leader's escort, its place at the leader's shoulder. True while it is walking with the leader
     * (the rest of its day waits).
     */
    public static boolean step(VillageFolkEntity g, ServerLevel level) {
        UUID village = g.ownerId();
        if (village == null) return false;
        long now = level.getGameTime();
        leash(g, level, village, now);
        boolean was = ESCORTING.contains(g.getUUID());
        // Only the one chosen (the watch's look round chooses, once a second) has anything more to see to.
        if (!was && !g.getUUID().equals(escortOf(village))) return false;
        VillageFolkEntity elder = Orders.elderOf(village);
        boolean on = escortDuty(g, level, village, elder, now);
        if (on && !was) {
            ESCORTING.add(g.getUUID());
            g.clearQueue();
            g.getNavigation().stop();
            g.brain("walking with the leader");
            String title = capital(Homeland.leaderTitle(village));
            say(g, now, 1200, FolkTalk.pick(g.getRandom(), "I'll walk with you, " + title + ".",
                "After you, " + title + " " + elder.displayNameCap() + ".", "I'm at your shoulder, " + title + "."));
        } else if (!on && was) {
            ESCORTING.remove(g.getUUID());
            g.getNavigation().stop();
            if (g.getRandom().nextInt(3) == 0) say(g, now, 1200, FolkTalk.pick(g.getRandom(), "Back to my rounds.", "I'll leave you to it, then."));
        }
        if (on) walkWith(g, level, elder, now);
        return on;
    }

    private static boolean escortDuty(VillageFolkEntity g, ServerLevel level, UUID village, @Nullable VillageFolkEntity elder, long now) {
        if (elder == null || elder == g || chooseEscort(village, now) != g) return false;
        if (!g.isAlive() || g.isSleeping() || away(g) || g.onWatch() || Raids.underAlarm(village)) return false;
        if (Interviews.busy(g)) return false;                         // [interviews] at an interview (on the panel, or a candidate)
        if (Police.engaged(g)) return false;                            // [police] the watch's business comes first
        if (level.isNight()) return false;                               // the night is the watch's
        if (g.talkPartner() != null || g.companionPlayer() != null || g.guidePlayer() != null) return false;
        if (Elections.dueToVote(g, level)) return false;                // its own vote, and straight back
        if (about(elder, village)) {
            SETTLED.remove(village);
            return true;
        }
        // Settled at home or at work a little while before the escort stands down, not at every stop at a door.
        long since = SETTLED.computeIfAbsent(village, k -> now);
        return ESCORTING.contains(g.getUUID()) && now - since < STAND_DOWN && now >= since;
    }

    /** Two to four blocks off the leader, behind it and to one side; at whatever comes at it. */
    private static void walkWith(VillageFolkEntity g, ServerLevel level, VillageFolkEntity elder, long now) {
        LivingEntity t = g.getTarget();
        if (t != null && t.isAlive()) return;                            // at it already: the fight has its legs
        Mob threat = null;
        double best = GUARDING * GUARDING;
        for (Mob m : level.getEntitiesOfClass(Mob.class, elder.getBoundingBox().inflate(GUARDING), Patrols::hostile)) {
            if (!canTake(g, m)) continue;
            double d = m.distanceToSqr(elder);
            if (d < best) { best = d; threat = m; }
        }
        if (threat != null && !g.shouldDisengage()) {
            g.equipBestWeapon();
            g.setTarget(threat);
            g.getNavigation().moveTo(threat, 1.2D);
            ANSWERED.put(threat.getUUID(), new Answer(g.getUUID(), elder.getUUID(), now));
            say(g, now, 100, FolkTalk.pick(g.getRandom(), "Stay behind me, " + capital(Homeland.leaderTitle(elder.ownerId())) + "!",
                "Not while I'm here!", "Back, you!"));
            return;
        }
        Round r = ROUNDS.computeIfAbsent(g.getUUID(), k -> new Round());
        r.walking = false;                                                // its round starts afresh after
        double d = flat(g, elder.getX(), elder.getZ());
        PathNavigation nav = g.getNavigation();
        if (d > 4.0) {
            if (nav.isDone() || now - r.pathAt >= 10L || now < r.pathAt) {
                r.pathAt = now;
                Vec3 spot = besideOf(elder);
                if (!nav.moveTo(spot.x, spot.y, spot.z, d > 12.0 ? 1.15D : 0.9D)) nav.moveTo(elder, 1.0D);
            }
        } else if (d < 1.8) {
            // Not under its feet: a step back.
            double dx = g.getX() - elder.getX(), dz = g.getZ() - elder.getZ();
            double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
            nav.moveTo(elder.getX() + dx / len * 3.0, elder.getY(), elder.getZ() + dz / len * 3.0, 0.7D);
        } else {
            nav.stop();
            // Its eyes on the street, not on the leader: out the way it is standing.
            double dx = g.getX() - elder.getX(), dz = g.getZ() - elder.getZ();
            if (g.getRandom().nextInt(4) == 0) {
                double a = Math.atan2(dz, dx) + (g.getRandom().nextDouble() - 0.5) * 2.0;
                g.getLookControl().setLookAt(g.getX() + Math.cos(a) * 8.0, g.getEyeY(), g.getZ() + Math.sin(a) * 8.0);
            }
        }
    }

    /** A respectful place by the leader: two and a half blocks behind it and a step and a half to its
     *  right (three blocks off, all told). */
    static Vec3 besideOf(VillageFolkEntity elder) {
        double yaw = Math.toRadians(elder.getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);                  // the way it faces
        double rx = -fz, rz = fx;                                         // its right hand
        return new Vec3(elder.getX() - fx * 2.5 + rx * 1.5, elder.getY(), elder.getZ() - fz * 2.5 + rz * 1.5);
    }

    // ------------------------------------------------------------------ telling

    /** The part of the town a beat walks, and a street on it by name: "the north-east streets, round
     *  Mill Lane"; "the whole town" for a watch of one. */
    static String beatName(UUID village, BlockPos c, List<BlockPos> beat, int beats) {
        if (beats <= 1 || beat.isEmpty()) return "the whole town";
        double x = 0, z = 0;
        for (BlockPos p : beat) { x += p.getX() - c.getX(); z += p.getZ() - c.getZ(); }
        double a = Math.toDegrees(Math.atan2(z / beat.size(), x / beat.size()));      // east nought, south ninety
        String[] names = { "east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east" };
        int k = Math.floorMod((int) Math.round(a / 45.0), 8);
        String street = null;
        for (BlockPos p : beat) {
            int dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ();
            int alongX = TownLife.lineAt(dz), alongZ = TownLife.lineAt(dx);
            if (alongX != Integer.MIN_VALUE && alongX != 0) street = TownLife.streetName(village, true, alongX, dx);
            else if (alongZ != Integer.MIN_VALUE && alongZ != 0) street = TownLife.streetName(village, false, alongZ, dz);
            if (street != null) break;
        }
        return "the " + names[k] + " streets" + (street == null ? "" : ", round " + street);
    }

    /** What a guard says it is about (FolkTalk, Trades): its beat, or the leader it walks with. */
    public static String line(VillageFolkEntity g) {
        UUID village = g.ownerId();
        if (village == null || g.stationTask() != StationTask.GUARD) return "";
        String title = Homeland.leaderTitle(village);
        if (escorting(g)) {
            VillageFolkEntity elder = Orders.elderOf(village);
            return "I'm walking with " + (elder == null ? "the " + title : "the " + title + ", " + elder.displayNameCap()) + ".";
        }
        String beat = "";
        if (g.level() instanceof ServerLevel level) {
            Villages.Village v = Villages.get(village);
            List<VillageFolkEntity> watch = watch(village);
            if (v != null && watch.contains(g)) {
                List<List<BlockPos>> beats = beats(level, v, watch.size());
                if (!beats.isEmpty()) beat = "My beat's " + beatName(v.id(), v.centre(), beats.get(watch.indexOf(g) % beats.size()), beats.size()) + ".";
            }
        }
        UUID escort = escortOf(village);
        String shoulder = g.getUUID().equals(escort) ? " And when the " + title + " is out and about, I walk at its shoulder." : "";
        return (beat + shoulder).trim();
    }

    /** What a guard says it is doing just now. */
    public static String doing(VillageFolkEntity g) {
        var r = g.getRandom();
        if (escorting(g)) {
            String title = Homeland.leaderTitle(g.ownerId());
            return FolkTalk.pick(r, "Walking with the " + title + ". Somebody has to.", "Seeing the " + title + " safe about the town.");
        }
        String beat = "";
        if (g.level() instanceof ServerLevel level && g.ownerId() != null) {
            Villages.Village v = Villages.get(g.ownerId());
            List<VillageFolkEntity> watch = watch(g.ownerId());
            if (v != null && watch.contains(g)) {
                List<List<BlockPos>> beats = beats(level, v, watch.size());
                if (!beats.isEmpty()) beat = " — " + beatName(v.id(), v.centre(), beats.get(watch.indexOf(g) % beats.size()), beats.size());
            }
        }
        return g.level().isNight() ? "The night watch" + beat + ". Quiet so far." : "Walking my beat" + beat + ". Nothing gets past me.";
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the tests

    /** Every beat the village's watch walks, for a watch of the size it has. */
    public static List<List<BlockPos>> beatsForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        return v == null ? List.of() : beats(level, v, watch(village).size());
    }

    /** This guard's own beat. */
    public static List<BlockPos> beatForTests(VillageFolkEntity g) {
        return g.level() instanceof ServerLevel level ? beatOf(g, level) : List.of();
    }

    /** The stop this guard last set off for, or null. */
    @Nullable
    public static BlockPos headingForTests(VillageFolkEntity g) {
        Round r = ROUNDS.get(g.getUUID());
        return r == null ? null : r.heading;
    }

    /** The guard sent after this monster, or null. */
    @Nullable
    public static UUID answeredByForTests(UUID monster) {
        Answer a = ANSWERED.get(monster);
        return a == null ? null : a.guard();
    }

    /** Choose the escort now, whenever it was last chosen. */
    @Nullable
    public static UUID chooseEscortForTests(ServerLevel level, UUID village) {
        ESCORTS.remove(village);
        VillageFolkEntity g = chooseEscort(village, level.getGameTime());
        return g == null ? null : g.getUUID();
    }

    /** Is the leader out and about, as its escort sees it? */
    public static boolean leaderAboutForTests(UUID village) {
        VillageFolkEntity elder = Orders.elderOf(village);
        return elder != null && about(elder, village);
    }
}
