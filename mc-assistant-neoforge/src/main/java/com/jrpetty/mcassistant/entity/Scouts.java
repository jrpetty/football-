package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The scouts. A town of forty or more sends one or two of its folk out to learn what lies
 * beyond its fields, and keeps what they learn in its atlas: other towns and the villages of
 * villagers, the old temples and ruins, the pillagers' outposts, the peaks, lakes and coasts,
 * the edges of other lands, iron and coal showing in the rock, pools of lava, good flat ground
 * for a new village, and the players it met on the road.
 *
 * <p><b>How a scout goes about it.</b> Every morning it picks the way the atlas knows least,
 * draws food (and a torch or two) from the stores, and sets off in stages of twenty-odd
 * blocks:
 * <ul>
 * <li>Each stage it looks over the ground ahead, straight on and to either side, and takes
 *     the best line: dry land, an easy slope, and still heading the right way. It checks
 *     the path before it commits.</li>
 * <li>Where the way is blocked it widens its search a step at a time — round the lake,
 *     along the cliff foot. If the whole way is blocked, it notes it in the atlas and comes
 *     back.</li>
 * <li>It drops a breadcrumb every sixteen blocks, and comes home along its own trail: a way
 *     it knows it can walk.</li>
 * <li>It keeps out of the way of anything hostile, turns back if it is hurt, and is home by
 *     dusk. The ground round it stays awake as it goes.</li>
 * </ul>
 *
 * <p><b>What it tells.</b> It calls out what it finds as it finds it, and marks the best of it
 * with a torch. It tells the village when it gets home: the board, the chronicle, and the
 * next morning assembly. It comes running back with news that cannot wait (pillagers near
 * home). Where it reaches another town, the two swap what they know of the land, so each
 * atlas grows by the other's. Where it meets a player, it hails them and says where it is
 * from.
 *
 * <p><b>What the village does with it.</b> A town its scout has found counts as a
 * neighbour twice as far off as one it has not (envoys, trade, diplomacy). Anybody in the
 * village can tell a player what the scouts have found and which way it is. A scout gives
 * the very coordinates, and will walk a player to a find that is near enough.
 */
public final class Scouts {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Scouts() {}

    /** A town needs this many folk before it sends anybody scouting. */
    public static final int FROM = 40;
    /** The furthest out a scout goes in a day. */
    static final int RANGE = 384;
    /** Rings and bearings of the atlas's map of where its scouts have been. */
    static final int RINGS = 6, BEARINGS = 16, RING = 64;
    /** How many finds an atlas keeps. */
    static final int KEEP = 96;

    /** What a scout can find. */
    public enum Kind {
        TOWN("a town"), SETTLEMENT("a village of villagers"), RUIN("a ruin"), DANGER("danger"), PEAK("a peak"),
        WATER("water"), LAND("other lands"), ORE("ore"), LAVA("lava"), SITE("good ground"), PLAYER("a traveller"),
        BLOCKED("the way blocked");

        public final String words;
        Kind(String words) { this.words = words; }
    }

    /** One thing in the atlas: what, where, when, who found it. */
    public record Find(Kind kind, String label, BlockPos at, long day, String by) {
        String encode() {
            return kind.name() + "|" + clean(label) + "|" + at.getX() + "|" + at.getY() + "|" + at.getZ() + "|" + day + "|" + clean(by);
        }

        @Nullable
        static Find decode(String s) {
            String[] p = s.split("\\|", -1);
            if (p.length < 7) return null;
            try {
                return new Find(Kind.valueOf(p[0]), p[1], new BlockPos(Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4])),
                    Long.parseLong(p[5]), p[6]);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private static String clean(String s) {
            return s.replace('|', '/').replace('\n', ' ');
        }
    }

    /** A scout's day out. */
    public static final class Expedition {
        final UUID village;
        final BlockPos home;
        final int bearing;
        final BlockPos target;
        final String heading;
        final List<BlockPos> trail = new ArrayList<>();
        final List<Find> found = new ArrayList<>();
        final Set<UUID> greeted = new HashSet<>();
        boolean returning;
        String why = "";
        int crumb;
        @Nullable BlockPos waypoint;
        int detour;
        double best = Double.MAX_VALUE;
        int gainedTick, walkTick = -1000, surveyTick, startedTick;
        @Nullable BlockPos window;
        int spokeTick = -100000;
        /** [war-scouting] Sent to watch an enemy town, not to explore (Spying): what it is about, and what it counts. */
        @Nullable Spying.Mission mission;
        /** [caves] A cave dweller's day down the caves, not a scout's over the land (CaveDwellers): the cave's part of it. */
        @Nullable CaveDwellers.Delve delve;

        Expedition(UUID village, BlockPos home, int bearing, BlockPos target) {
            this.village = village;
            this.home = home;
            this.bearing = bearing;
            this.target = target;
            this.heading = Guide.direction(home, target);
        }

        public boolean returning() { return returning; }
        public String heading() { return mission != null && mission.going() ? "toward " + mission.name() : heading; }
        public int finds() { return found.size(); }
        /** [war-scouting] The enemy town it was sent to watch, and what it has counted (Spying); null when out exploring. */
        @Nullable public Spying.Mission mission() { return mission; }
        /** [caves] The cave dweller's day in the caves (CaveDwellers); null for a scout's. */
        @Nullable public CaveDwellers.Delve delve() { return delve; }
    }

    /** What the scouts have come home with, for the next morning assembly. */
    private static final Map<UUID, List<String>> REPORTS = new ConcurrentHashMap<>();
    /** The day each scout last went out. */
    private static final Map<UUID, Long> WENT = new ConcurrentHashMap<>();

    public static void resetForTests() {
        REPORTS.clear();
        WENT.clear();
        WarScouting.resetForTests();             // [war-scouting] the spies, the pickets, the captives
    }

    // ------------------------------------------------------------------ the atlas

    /** Everything this village's scouts have found (and heard of), oldest first. */
    public static List<Find> atlas(UUID village) {
        List<Find> out = new ArrayList<>();
        String s = Ledger.note(village, "atlas");
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            Find f = Find.decode(line);
            if (f != null) out.add(f);
        }
        return out;
    }

    static void save(UUID village, List<Find> finds) {
        while (finds.size() > KEEP) {
            // Forget the least of it first: a passing traveller, then the lands, before a town or a ruin.
            int drop = 0;
            for (int i = 0; i < finds.size(); i++) {
                Kind k = finds.get(i).kind();
                if (k == Kind.PLAYER || k == Kind.LAND || k == Kind.BLOCKED) { drop = i; break; }
            }
            finds.remove(drop);
        }
        StringBuilder sb = new StringBuilder();
        for (Find f : finds) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(f.encode());
        }
        Ledger.note(village, "atlas", sb.toString());
    }

    /** Already known (the same thing, near enough the same place)? */
    static boolean known(List<Find> finds, Kind kind, String label, BlockPos at, int near) {
        for (Find f : finds) {
            if (f.kind() != kind) continue;
            if (kind == Kind.LAND || kind == Kind.PLAYER) { if (f.label().equals(label)) return true; continue; }
            if (f.at().distSqr(at) < (double) near * near && (kind != Kind.TOWN || f.label().equals(label))) return true;
        }
        return false;
    }

    /** Into the atlas, if it is new. Returns whether it was. */
    static boolean record(UUID village, Find f) {
        List<Find> all = atlas(village);
        if (known(all, f.kind(), f.label(), f.at(), nearFor(f.kind()))) return false;
        all.add(f);
        save(village, all);
        return true;
    }

    static int nearFor(Kind k) {
        return switch (k) {
            case PEAK, WATER, SITE -> 128;
            case ORE, LAVA -> 48;
            case TOWN, SETTLEMENT, RUIN, DANGER -> 96;
            default -> 64;
        };
    }

    /** The nearest thing of this kind the atlas knows, or null. */
    @Nullable
    public static Find nearest(UUID village, Kind kind, BlockPos from) {
        Find best = null;
        for (Find f : atlas(village)) {
            if (f.kind() != kind) continue;
            if (best == null || f.at().distSqr(from) < best.at().distSqr(from)) best = f;
        }
        return best;
    }

    /** Have this village's scouts found (or been found by) that one? */
    public static boolean met(UUID a, UUID b) {
        return Ledger.note(a, "scouted/" + b) != null || Ledger.note(b, "scouted/" + a) != null;
    }

    // ------------------------------------------------------------------ where they have been

    static long[] explored(UUID village) {
        String s = Ledger.note(village, "explored");
        long[] bits = new long[2];
        if (s == null || s.isEmpty()) return bits;
        String[] p = s.split(",");
        try {
            for (int i = 0; i < Math.min(2, p.length); i++) bits[i] = Long.parseUnsignedLong(p[i], 16);
        } catch (NumberFormatException ignored) { }
        return bits;
    }

    static boolean been(long[] bits, int bearing, int ring) {
        int i = ring * BEARINGS + bearing;
        return (bits[i >> 6] & (1L << (i & 63))) != 0;
    }

    static void mark(UUID village, BlockPos home, BlockPos at) {
        int dx = at.getX() - home.getX(), dz = at.getZ() - home.getZ();
        int ring = (int) (Math.sqrt((double) dx * dx + (double) dz * dz) / RING);
        if (ring >= RINGS) return;
        int bearing = bearingOf(dx, dz);
        long[] bits = explored(village);
        int i = ring * BEARINGS + bearing;
        long bit = 1L << (i & 63);
        if ((bits[i >> 6] & bit) != 0) return;
        bits[i >> 6] |= bit;
        Ledger.note(village, "explored", Long.toHexString(bits[0]) + "," + Long.toHexString(bits[1]));
    }

    static int bearingOf(int dx, int dz) {
        double ang = Math.atan2(dz, dx);
        return Math.floorMod((int) Math.round(ang / (2 * Math.PI / BEARINGS)), BEARINGS);
    }

    /** How much of the land within the scouts' range they have seen, in percent. */
    public static int exploredPercent(UUID village) {
        long[] bits = explored(village);
        return (Long.bitCount(bits[0]) + Long.bitCount(bits[1])) * 100 / (RINGS * BEARINGS);
    }

    // ------------------------------------------------------------------ at home

    /** Is this folk out scouting? */
    public static boolean out(VillageFolkEntity f) {
        return f.expedition() != null;
    }

    /**
     * A scout's day at home (its station brain): in the morning, out it goes; the rest of the
     * day it is at the board, poring over the atlas. Returns whether it did something.
     */
    public static boolean work(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || out(f)) return out(f);
        long time = level.getDayTime() % 24000L, day = level.getDayTime() / 24000L;
        // Somebody left out beyond the fields (a restart, an expedition cut short): home first.
        if (f.blockPosition().distSqr(v.centre()) > 160 * 160) {
            Expedition e = new Expedition(id, v.centre(), 0, f.blockPosition());
            e.returning = true;
            e.crumb = -1;
            e.why = "came home";
            e.startedTick = e.gainedTick = f.tickCount;
            f.expedition(e);
            return true;
        }
        boolean fit = f.getHealth() >= f.getMaxHealth() * 0.7F && !level.isThundering() && !Raids.underAlarm(id);
        boolean morning = time >= 1200 && time < 4200;
        if (morning && fit && WENT.getOrDefault(f.getUUID(), -1L) < day && !Assemblies.attending(f)) {
            // [war-scouting] On a war footing, the scout goes to watch the enemy before it goes exploring (Spying).
            if (Spying.instead(f, level, v, day)) return true;
            if (setOut(f, level, v, day)) return true;
        }
        // At the board, with the atlas.
        BlockPos board = VillageBoards.lectern(id);
        BlockPos at = board != null ? board : v.centre();
        BlockPos spot = at.relative(Direction.from2DDataValue(Math.floorMod(f.getUUID().hashCode() + 1, 4)), 3);
        if (f.blockPosition().distSqr(spot) > 9) {
            if (f.getNavigation().isDone()) f.walkTo(spot, 0.8D);
        } else {
            f.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 1.5, at.getZ() + 0.5);
            f.hobbyNow = "going over the atlas";
        }
        return true;
    }

    /** Plan the day's way, draw what it needs from the stores, and go. */
    static boolean setOut(VillageFolkEntity f, ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        BlockPos home = v.centre();
        long[] bits = explored(id);
        java.util.Random rng = new java.util.Random(f.getUUID().getLeastSignificantBits() ^ day * 31L);
        // The other scouts' bearings today: not the same way twice.
        Set<Integer> taken = new HashSet<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a != f && a instanceof VillageFolkEntity o && o.expedition() != null) taken.add(o.expedition().bearing);
        }
        int bestBearing = -1, bestRing = RINGS + 1;
        int start = rng.nextInt(BEARINGS);
        for (int k = 0; k < BEARINGS; k++) {
            int b = (start + k) % BEARINGS;
            if (taken.contains(b) || blockedThatWay(id, home, b)) continue;
            int ring = 0;
            while (ring < RINGS && been(bits, b, ring)) ring++;
            if (ring < bestRing) { bestRing = ring; bestBearing = b; }
        }
        if (bestBearing < 0) bestBearing = start;
        int range = Math.min(RANGE, Math.max(96, (Math.min(bestRing, RINGS - 1) + 1) * RING + 32));
        range = Math.min(RANGE + RANGE / 2, Perks.scoutRange(f, range));   // [perks] the Surveyors' Office, a Surveyor's Eye: further out
        double ang = bestBearing * (2 * Math.PI / BEARINGS);
        int tx = home.getX() + (int) Math.round(Math.cos(ang) * range);
        int tz = home.getZ() + (int) Math.round(Math.sin(ang) * range);
        BlockPos target = new BlockPos(tx, home.getY(), tz);
        // Supplies: something to eat, a torch or two to mark the way, a blade if there is one.
        int food = 0;
        for (int i = 0; i < 6 && f.countMatching(s -> s.get(net.minecraft.core.component.DataComponents.FOOD) != null) < 6; i++) {
            ItemStack s = Crafts.takeOne(level, v, x -> x.get(net.minecraft.core.component.DataComponents.FOOD) != null
                && !x.is(Items.ROTTEN_FLESH) && !x.is(Items.SPIDER_EYE));
            if (s.isEmpty()) break;
            ItemStack left = f.insertItem(s);
            if (!left.isEmpty()) Crafts.store(level, v, left);
            food++;
        }
        if (f.countMatching(s -> s.get(net.minecraft.core.component.DataComponents.FOOD) != null) == 0) {
            FolkTalk.speak(f, "Nothing in the stores to take on the road. I'll not go hungry into the wilds.");
            WENT.put(f.getUUID(), day);
            return false;
        }
        for (int i = 0; i < 3 && f.countMatching(s -> s.is(Items.TORCH)) < 3; i++) {
            ItemStack s = Crafts.takeOne(level, v, x -> x.is(Items.TORCH));
            if (s.isEmpty()) break;
            ItemStack left = f.insertItem(s);
            if (!left.isEmpty()) Crafts.store(level, v, left);
        }
        if (f.countMatching(s -> s.getItem() instanceof net.minecraft.world.item.SwordItem) == 0) {
            ItemStack s = Crafts.takeOne(level, v, x -> x.getItem() instanceof net.minecraft.world.item.SwordItem);
            if (!s.isEmpty()) {
                ItemStack left = f.insertItem(s);
                if (!left.isEmpty()) Crafts.store(level, v, left);
            }
        }
        f.clearQueue();
        f.getNavigation().stop();
        Expedition e = new Expedition(id, home, bestBearing, target);
        e.startedTick = e.gainedTick = e.surveyTick = f.tickCount;
        e.trail.add(f.blockPosition().immutable());
        f.expedition(e);
        WENT.put(f.getUUID(), day);
        String where = e.heading + (bestRing >= RINGS ? ", as far as I can get" : ", " + range + " blocks or so");
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Off scouting — " + where + ". Back by dusk!",
            "Out " + e.heading + " today. Who knows what's out there?", "Heading " + e.heading + ". Wish me luck!"));
        Villages.tell(id, day, f.displayNameCap() + " went scouting " + e.heading);
        LOG.info("[MCA-SCOUT] {} of {} sets out {} ({} blocks, bearing {}, ring {}, food {})", f.displayNameCap(),
            Villages.name(id), e.heading, range, bestBearing, bestRing, food);
        return true;
    }

    /** Tests: send a scout out toward a given spot, with a loaf for the road. */
    public static void sendForTests(VillageFolkEntity f, ServerLevel level, BlockPos target) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return;
        f.insertItem(new ItemStack(Items.BREAD, 4));
        int bearing = bearingOf(target.getX() - v.centre().getX(), target.getZ() - v.centre().getZ());
        Expedition e = new Expedition(id, v.centre(), bearing, target);
        e.startedTick = e.gainedTick = e.surveyTick = f.tickCount;
        e.trail.add(f.blockPosition().immutable());
        f.clearQueue();
        f.expedition(e);
    }

    /** Was this way found impassable lately? */
    static boolean blockedThatWay(UUID village, BlockPos home, int bearing) {
        for (Find f : atlas(village)) {
            if (f.kind() != Kind.BLOCKED) continue;
            int dx = f.at().getX() - home.getX(), dz = f.at().getZ() - home.getZ();
            if (bearingOf(dx, dz) == bearing) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ on the way

    /** A scout out on the land: walked a stage at a time. Returns true while it is out. */
    public static boolean drive(VillageFolkEntity f, ServerLevel level) {
        Expedition e = f.expedition();
        if (e == null) return false;
        if (e.delve != null) return CaveDwellers.drive(level, f, e);          // [caves] a cave dweller's day down the caves
        keepAwake(level, f, e);
        long time = level.getDayTime() % 24000L;
        // Something hostile close by: away from it, quick.
        Mob foe = foe(level, f);
        if (foe != null) {
            Vec3 away = f.position().subtract(foe.position()).normalize().scale(10);
            BlockPos run = surface(level, BlockPos.containing(f.getX() + away.x, f.getY(), f.getZ() + away.z));
            f.getNavigation().moveTo(run.getX() + 0.5, run.getY(), run.getZ() + 0.5, 1.25D);
            if (f.tickCount - e.spokeTick > 400) {
                e.spokeTick = f.tickCount;
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Not today!", "I'm not stopping to fight that.", "Run!"));
            }
            return true;
        }
        // A horse from the stable to ride its rounds on, and an old chest looked into for a saddle (Riding).
        if (Riding.scout(f, level, e)) return true;
        if (!e.returning) {
            // [war-scouting] One sent to watch an enemy town has further to go and a while to watch when it gets
            // there: it is given the afternoon too (Spying), and comes home in the evening.
            long late = e.mission != null ? Spying.LATE : 7600, longest = e.mission != null ? Spying.LONGEST : 9000;
            if (f.getHealth() < f.getMaxHealth() * 0.45F) turnBack(level, f, e, "I got hurt, so I came back");
            else if (time >= late && time < 23000) turnBack(level, f, e, "it was time to turn back");
            else if (f.tickCount - e.startedTick > longest) turnBack(level, f, e, "it was time to turn back");
        }
        // [war-scouting] At its vantage over the enemy's town it watches and counts, slips closer if it dares, runs
        // if it is seen (Spying); on the way there and back it walks as any scout does.
        if (e.mission != null && Spying.drive(level, f, e)) return true;
        // Look about every couple of seconds.
        if (f.tickCount - e.surveyTick >= 40) {
            e.surveyTick = f.tickCount;
            survey(level, f, e);
            if (f.expedition() != e) return true;
        }
        // Breadcrumbs on the way out.
        if (!e.returning) {
            BlockPos last = e.trail.get(e.trail.size() - 1);
            if (flat(f.blockPosition(), last) > 16 * 16 && f.onGround()) {
                e.trail.add(f.blockPosition().immutable());
                mark(e.village, e.home, f.blockPosition());
            }
        }
        BlockPos dest = destination(e);
        double d = flat(f.blockPosition(), dest);
        if (!e.returning && d <= 10 * 10 && e.mission == null) {
            // As far as it was going: a good look round, a torch to mark it, and home.
            lookRound(level, f, e);
            placeTorch(level, f);
            turnBack(level, f, e, "I went as far as I meant to");
            return true;
        }
        if (e.returning) {
            if (e.crumb < 0 && flat(f.blockPosition(), e.home) <= 14 * 14) {
                home(level, f, e);
                return false;
            }
            if (e.crumb >= 0 && d <= 6 * 6) {
                e.crumb--;
                e.waypoint = null;
                e.best = Double.MAX_VALUE;
                e.gainedTick = f.tickCount;
                return true;
            }
        }
        // Progress, and what to do without it.
        if (d < e.best - 2.0) {
            e.best = d;
            e.gainedTick = f.tickCount;
            if (e.detour > 0 && f.tickCount % 200 == 0) e.detour--;
        } else if (f.tickCount - e.gainedTick > 300) {
            e.detour++;
            e.waypoint = null;
            e.gainedTick = f.tickCount;
            if (e.detour > 5) {
                if (!e.returning) {
                    BlockPos here = f.blockPosition();
                    e.found.add(new Find(Kind.BLOCKED, "no way through " + e.heading, here, level.getDayTime() / 24000L, f.displayNameCap()));
                    turnBack(level, f, e, "the way " + e.heading + " was blocked");
                } else {
                    // Coming home it knows the way: it has walked it. Over the bad patch to the next mark.
                    BlockPos to = surface(level, dest);
                    Riding.carry(f, to.getX() + 0.5, to.getY(), to.getZ() + 0.5);       // (its horse with it: Riding)
                    e.detour = 0;
                    e.best = Double.MAX_VALUE;
                }
                return true;
            }
        }
        // The next stage: the best line over the ground ahead.
        if (e.waypoint == null || flat(f.blockPosition(), e.waypoint) <= 3 * 3) {
            e.waypoint = e.returning && e.crumb >= 0 ? dest : chooseWaypoint(level, f, dest, e.detour);
            e.walkTick = -1000;
        }
        if (e.waypoint != null && (f.getNavigation().isDone() || f.tickCount - e.walkTick > 80)) {
            f.walkTo(e.waypoint, e.returning && time >= 11000 ? 1.2D : 1.05D);
            e.walkTick = f.tickCount;
        }
        f.hobbyNow = e.returning ? "on the way home from scouting" : "scouting " + e.heading;
        if (f.tickCount - e.spokeTick > 3000) {
            e.spokeTick = f.tickCount;
            if (!e.returning) f.say("Scouting " + e.heading + " — " + (int) Math.sqrt(flat(f.blockPosition(), e.home)) + " blocks out.");
        }
        return true;
    }

    /** Where it is heading now: the far point, or back along its own trail, or home. */
    static BlockPos destination(Expedition e) {
        if (!e.returning) return e.target;
        if (e.crumb >= 0 && e.crumb < e.trail.size()) return e.trail.get(e.crumb);
        return e.home;
    }

    /**
     * The best line over the ground ahead: straight on first, then wider and wider to either
     * side as the way proves hard, preferring dry ground and an easy slope, and only a line
     * the path-finder says it can walk.
     */
    @Nullable
    static BlockPos chooseWaypoint(ServerLevel level, VillageFolkEntity f, BlockPos dest, int detour) {
        Vec3 from = f.position();
        double dx = dest.getX() + 0.5 - from.x, dz = dest.getZ() + 0.5 - from.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1) return dest;
        double step = Math.min(dist, 22.0);
        double base = Math.atan2(dz, dx);
        int[] offsets = { 0, 20, -20, 40, -40, 60, -60, 90, -90, 120, -120, 150, -150 };
        int first = Math.min(offsets.length - 1, detour * 2);
        BlockPos best = null;
        double bestScore = -Double.MAX_VALUE;
        int checked = 0;
        int feet = f.blockPosition().getY();
        for (int k = first; k < offsets.length && checked < 5; k++) {
            double ang = base + Math.toRadians(offsets[k]);
            int x = (int) Math.floor(from.x + Math.cos(ang) * step), z = (int) Math.floor(from.z + Math.sin(ang) * step);
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            BlockPos p = surface(level, new BlockPos(x, feet, z));
            FluidState fluid = level.getFluidState(p.below());
            boolean wet = !level.getFluidState(p).isEmpty() || !fluid.isEmpty();
            boolean lava = fluid.is(Fluids.LAVA) || fluid.is(Fluids.FLOWING_LAVA) || level.getFluidState(p).is(Fluids.LAVA);
            if (lava) continue;
            int rise = Math.abs(p.getY() - feet);
            if (wet && detour < 3) continue;
            if (rise > 8 && detour < 2) continue;
            double score = Math.cos(Math.toRadians(offsets[k])) * 10.0 - rise * 0.6 - (wet ? 6 : 0);
            if (score <= bestScore) continue;
            checked++;
            net.minecraft.world.level.pathfinder.Path path = f.getNavigation().createPath(p, 1);
            if (path == null || !path.canReach() && path.getDistToTarget() > 3.0F) continue;
            best = p;
            bestScore = score;
            if (offsets[k] == 0) break;                           // straight on and walkable: no need to look further
        }
        return best != null ? best : surface(level, BlockPos.containing(from.x + Math.cos(base) * 8, feet, from.z + Math.sin(base) * 8));
    }

    /** Turn for home, along its own trail. */
    static void turnBack(ServerLevel level, VillageFolkEntity f, Expedition e, String why) {
        if (e.returning) return;
        e.returning = true;
        e.why = why;
        e.crumb = e.trail.size() - 1;
        e.waypoint = null;
        e.detour = 0;
        e.best = Double.MAX_VALUE;
        e.gainedTick = f.tickCount;
        if (e.mission != null) return;                 // [war-scouting] a spy has its own words for it (Spying)
        FolkTalk.speak(f, why.startsWith("I got hurt") ? "Ow. That's enough for one day — home." : e.found.isEmpty()
            ? FolkTalk.pick(f.getRandom(), "Nothing much out here. Home, then.", "Time to head back.")
            : "Home — I've news for the elder!");
    }

    /** Home: everything it found into the atlas, and told. */
    static void home(ServerLevel level, VillageFolkEntity f, Expedition e) {
        release(level, f, e);
        f.expedition(null);
        long day = level.getDayTime() / 24000L;
        UUID id = e.village;
        int fresh = 0;
        List<String> told = new ArrayList<>();
        for (Find x : e.found) {
            if (!record(id, x)) continue;
            fresh++;
            if (x.kind() == Kind.LAND || x.kind() == Kind.BLOCKED) continue;
            told.add(x.label() + " " + (int) Math.sqrt(x.at().distSqr(e.home)) / 10 * 10 + " blocks " + Guide.direction(e.home, x.at()));
        }
        // [war-scouting] Home from watching an enemy town: its report filed and told (Spying), not the atlas's news.
        if (e.mission != null) {
            Spying.home(level, f, e);
            return;
        }
        String report = told.isEmpty()
            ? f.displayNameCap() + " came back from the " + e.heading + (fresh > 0 ? " with a little more of the map filled in" : " with nothing new to tell")
            : f.displayNameCap() + " came back from the " + e.heading + " and found " + String.join(", ", told.subList(0, Math.min(4, told.size())));
        Villages.tell(id, day, report);
        if (!told.isEmpty()) {
            REPORTS.computeIfAbsent(id, k -> new ArrayList<>()).add("Our scout " + f.displayNameCap() + " found " + String.join(", ", told.subList(0, Math.min(3, told.size()))) + ".");
            f.persona().remember(day, "I went scouting " + e.heading + " and found " + told.get(0), 4);
        }
        FolkTalk.speak(f, told.isEmpty() ? "Home! Nothing much out " + e.heading + ", but now we know." : "Home! I found " + told.get(0) + "!");
        LOG.info("[MCA-SCOUT] {} home from the {} ({}): {} finds, {} new; atlas {} entries, explored {}%",
            f.displayNameCap(), e.heading, e.why, e.found.size(), fresh, atlas(id).size(), exploredPercent(id));
    }

    /** For the morning assembly: what the scouts found (said once). */
    static List<String> reports(UUID village) {
        List<String> list = REPORTS.remove(village);
        return list == null ? List.of() : list;
    }

    // ------------------------------------------------------------------ [war-scouting] sent to watch the enemy

    /** A line for the next morning assembly (Spying: what the scout saw of the enemy; Spies: a spy taken). */
    static void report(UUID village, String line) {
        REPORTS.computeIfAbsent(village, k -> new ArrayList<>()).add(line);
    }

    /** This folk's day out is spoken for (sent to watch the enemy): no exploring as well. */
    static void wentToday(VillageFolkEntity f, long day) {
        WENT.put(f.getUUID(), day);
    }

    /** Out to watch another town (Spying): an expedition whose far point is the vantage over it. */
    static Expedition mission(VillageFolkEntity f, Villages.Village v, BlockPos vantage, Spying.Mission m) {
        int bearing = bearingOf(vantage.getX() - v.centre().getX(), vantage.getZ() - v.centre().getZ());
        Expedition e = new Expedition(v.id(), v.centre(), bearing, vantage);
        e.mission = m;
        e.startedTick = e.gainedTick = e.surveyTick = f.tickCount;
        e.trail.add(f.blockPosition().immutable());
        f.clearQueue();
        f.getNavigation().stop();
        f.expedition(e);
        return e;
    }

    /**
     * Home on foot from wherever it is (a captive let go, Spies): the way a scout comes home from a day
     * cut short, straight for the heart of its own town, with the ground kept awake round it as it goes.
     */
    static void walkHome(VillageFolkEntity f, Villages.Village home, Spying.Mission m) {
        Expedition e = new Expedition(home.id(), home.centre(), 0, f.blockPosition());
        e.mission = m;
        e.returning = true;
        e.crumb = -1;
        e.why = "came home";
        e.startedTick = e.gainedTick = f.tickCount;
        f.clearQueue();
        f.expedition(e);
    }

    // ------------------------------------------------------------------ looking about

    /** What it can see from here: towns, players, ruins, the lie of the land, the rock. */
    static void survey(ServerLevel level, VillageFolkEntity f, Expedition e) {
        BlockPos here = f.blockPosition();
        long day = level.getDayTime() / 24000L;
        String by = f.displayNameCap();
        // Other towns.
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(e.village) || !o.dim().equals(level.dimension())) continue;
            if (flat(here, o.centre()) > 80 * 80) continue;
            if (find(f, e, new Find(Kind.TOWN, Villages.name(o.id()), o.centre(), day, by))) {
                // [war-scouting] Into the atlas, but no swapping news with a town we are at odds with (Spying).
                if (Spying.hostile(e.village, o.id())) continue;
                FolkTalk.speak(f, "A town! That'll be " + Villages.name(o.id()) + ".");
                contact(level, f, e, o, day);
            }
        }
        // Players on the road.
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.distanceToSqr(f) > 40 * 40 || !e.greeted.add(p.getUUID())) continue;
            String name = p.getName().getString();
            FolkTalk.speak(f, "Hail, " + name + "! " + by + ", scout of " + Villages.name(e.village) + ". Fine day for it!");
            f.getLookControl().setLookAt(p, 30.0F, 30.0F);
            f.persona().feelFor(p.getUUID(), name, 1);
            find(f, e, new Find(Kind.PLAYER, name, p.blockPosition(), day, by));
        }
        // Structures: what was built here before, by whom.
        try {
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            for (Map.Entry<Structure, it.unimi.dsi.fastutil.longs.LongSet> en : level.structureManager().getAllStructuresAt(here).entrySet()) {
                ResourceLocation key = registry.getKey(en.getKey());
                if (key == null) continue;
                String[] what = structure(key.getPath());
                if (what == null) continue;
                it.unimi.dsi.fastutil.longs.LongIterator it = en.getValue().iterator();
                while (it.hasNext()) {
                    ChunkPos c = new ChunkPos(it.nextLong());
                    BlockPos at = surface(level, new BlockPos(c.getMiddleBlockX(), here.getY(), c.getMiddleBlockZ()));
                    if (flat(here, at) > 96 * 96) continue;
                    Kind kind = what[1].equals("danger") ? Kind.DANGER : what[1].equals("village") ? Kind.SETTLEMENT : Kind.RUIN;
                    if (find(f, e, new Find(kind, what[0], at, day, by))) {
                        FolkTalk.speak(f, kind == Kind.DANGER ? "Look there — " + what[0] + ". Best keep clear." : "There — " + what[0] + "! That's going in the atlas.");
                        if (kind == Kind.DANGER && !e.returning && flat(at, e.home) < 220 * 220) {
                            turnBack(level, f, e, "I had to warn everybody: " + what[0] + " near home");
                            return;
                        }
                    }
                }
            }
        } catch (RuntimeException ex) {
            LOG.debug("[MCA-SCOUT] structure look failed: {}", ex.toString());
        }
        if ((f.tickCount / 40) % 2 == 0) land(level, f, e, here, day, by);
        else rock(level, f, e, here, day, by);
    }

    /** What is worth noting of a structure, and how: {words, "danger"/"village"/"ruin"}; null for what a scout would not see. */
    @Nullable
    static String[] structure(String path) {
        if (path.startsWith("village_")) return new String[]{ "a village of villagers", "village" };
        if (path.startsWith("ruined_portal")) return new String[]{ "a ruined portal", "ruin" };
        return switch (path) {
            case "desert_pyramid" -> new String[]{ "a desert temple", "ruin" };
            case "jungle_pyramid" -> new String[]{ "a jungle temple", "ruin" };
            case "igloo" -> new String[]{ "an igloo", "ruin" };
            case "swamp_hut" -> new String[]{ "a witch's hut", "danger" };
            case "pillager_outpost" -> new String[]{ "a pillager outpost", "danger" };
            case "mansion" -> new String[]{ "a woodland mansion", "danger" };
            case "monument" -> new String[]{ "an ocean monument", "danger" };
            case "shipwreck_beached" -> new String[]{ "a shipwreck on the shore", "ruin" };
            case "trail_ruins" -> new String[]{ "old ruins", "ruin" };
            default -> null;
        };
    }

    /** The lie of the land: other lands, peaks, lakes and the sea, and good ground for a new village. */
    static void land(ServerLevel level, VillageFolkEntity f, Expedition e, BlockPos here, long day, String by) {
        var biome = level.getBiome(here);
        String key = biome.unwrapKey().map(k -> k.location().getPath()).orElse("");
        if (!key.isEmpty() && !biome.is(BiomeTags.IS_RIVER) && !biome.is(BiomeTags.IS_BEACH)
                && !key.equals("plains") && !key.equals("forest") && !biome.is(BiomeTags.IS_OCEAN)) {
            String words = "the " + key.replace('_', ' ');
            if (find(f, e, new Find(Kind.LAND, words, here, day, by)) && atlasLacks(e.village, Kind.LAND, words)) {
                FolkTalk.speak(f, "So this is " + words + "...");
            }
        }
        int highest = Integer.MIN_VALUE, water = 0, samples = 0;
        BlockPos top = here;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                int x = here.getX() + i * 8, z = here.getZ() + j * 8;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                samples++;
                if (y > highest) { highest = y; top = new BlockPos(x, y, z); }
                if (Math.abs(i) <= 1 && Math.abs(j) <= 1) { lo = Math.min(lo, y); hi = Math.max(hi, y); }
                if (!level.getFluidState(new BlockPos(x, y - 1, z)).isEmpty()) water++;
            }
        }
        if (samples < 20) return;
        if (highest >= e.home.getY() + 36 && find(f, e, new Find(Kind.PEAK, "a high peak", top, day, by))) {
            FolkTalk.speak(f, "What a peak! You could see for miles from up there.");
        }
        if (water >= 16) {
            boolean sea = biome.is(BiomeTags.IS_OCEAN);
            if (find(f, e, new Find(Kind.WATER, sea ? "the sea" : "a lake", here, day, by))) {
                FolkTalk.speak(f, sea ? "The sea! Look at it." : "A lake. Good fishing, I'd bet.");
            }
        }
        // Good ground for a new village: flat, dry, grassy, with water near and no town near.
        if (water == 0 && hi - lo <= 2 && flat(here, e.home) > 200 * 200) {
            BlockState ground = level.getBlockState(level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, here).below());
            if (ground.is(BlockTags.DIRT) && waterNear(level, here, 24) && Villages.nearest(level, here, 250) == null) {
                if (find(f, e, new Find(Kind.SITE, "good flat ground by water", here, day, by))) {
                    FolkTalk.speak(f, "Now this — this is a place a village could stand.");
                }
            }
        }
    }

    private static boolean atlasLacks(UUID village, Kind kind, String label) {
        for (Find x : atlas(village)) if (x.kind() == kind && x.label().equals(label)) return false;
        return true;
    }

    /** Ore showing in the rock round it, and lava. */
    static void rock(ServerLevel level, VillageFolkEntity f, Expedition e, BlockPos here, long day, String by) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -3; dy <= 4; dy++) {
                    m.set(here.getX() + dx, here.getY() + dy, here.getZ() + dz);
                    BlockState st = level.getBlockState(m);
                    FluidState fl = st.getFluidState();
                    if (fl.is(Fluids.LAVA) && fl.isSource()) {
                        if (find(f, e, new Find(Kind.LAVA, "a pool of lava", m.immutable(), day, by))) {
                            FolkTalk.speak(f, "Lava! Mind your feet. Water on that and you'd have obsidian...");
                        }
                        return;
                    }
                    String ore = ore(st);
                    if (ore == null || !exposed(level, m)) continue;
                    if (find(f, e, new Find(Kind.ORE, ore + " showing in the rock", m.immutable(), day, by))) {
                        FolkTalk.speak(f, capital(ore) + " in the rock here! The miners will want to know.");
                        placeTorch(level, f);
                    }
                    return;
                }
            }
        }
    }

    @Nullable
    static String ore(BlockState st) {
        if (st.is(BlockTags.DIAMOND_ORES)) return "diamonds";
        if (st.is(BlockTags.IRON_ORES)) return "iron";
        if (st.is(BlockTags.GOLD_ORES)) return "gold";
        if (st.is(BlockTags.EMERALD_ORES)) return "emeralds";
        if (st.is(BlockTags.LAPIS_ORES)) return "lapis";
        if (st.is(BlockTags.COPPER_ORES)) return "copper";
        if (st.is(BlockTags.COAL_ORES)) return "coal";
        return null;
    }

    private static boolean exposed(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) if (level.getBlockState(p.relative(d)).isAir()) return true;
        return false;
    }

    private static boolean waterNear(ServerLevel level, BlockPos at, int r) {
        for (int dx = -r; dx <= r; dx += 6) {
            for (int dz = -r; dz <= r; dz += 6) {
                int x = at.getX() + dx, z = at.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (level.getFluidState(new BlockPos(x, y - 1, z)).is(Fluids.WATER)) return true;
            }
        }
        return false;
    }

    /** At the furthest point: a long look round, further than it sees as it walks. */
    static void lookRound(ServerLevel level, VillageFolkEntity f, Expedition e) {
        long day = level.getDayTime() / 24000L;
        String by = f.displayNameCap();
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(e.village) || !o.dim().equals(level.dimension())) continue;
            if (flat(f.blockPosition(), o.centre()) > 160 * 160) continue;
            if (find(f, e, new Find(Kind.TOWN, Villages.name(o.id()), o.centre(), day, by))) {
                if (Spying.hostile(e.village, o.id())) continue;          // [war-scouting] (as in survey)
                FolkTalk.speak(f, "Smoke on the horizon — that's " + Villages.name(o.id()) + ".");
                contact(level, f, e, o, day);
            }
        }
        mark(e.village, e.home, f.blockPosition());
    }

    /** Noted, if new to it and to the atlas. Returns whether it was. */
    static boolean find(VillageFolkEntity f, Expedition e, Find x) {
        if (known(e.found, x.kind(), x.label(), x.at(), nearFor(x.kind()))) return false;
        if (known(atlas(e.village), x.kind(), x.label(), x.at(), nearFor(x.kind()))) return false;
        e.found.add(x);
        LOG.info("[MCA-SCOUT] {} found {} at {}", f.displayNameCap(), x.label(), x.at().toShortString());
        return true;
    }

    /**
     * It reaches another town: the two know each other from now on (as neighbours twice as far
     * off), and they swap what they know of the land — each atlas grows by the other's.
     */
    static void contact(ServerLevel level, VillageFolkEntity f, Expedition e, Villages.Village other, long day) {
        UUID mine = e.village, theirs = other.id();
        Ledger.note(mine, "scouted/" + theirs, Long.toString(day));
        boolean first = !Ledger.knowEachOther(mine, theirs);
        if (first) {
            Ledger.relate(mine, theirs, 0);
            String line = "a scout from " + Villages.name(mine) + " found " + Villages.name(theirs);
            Villages.tell(mine, day, line);
            Villages.tell(theirs, day, line);
        }
        int heard = 0, gave = 0;
        for (Find x : atlas(theirs)) {
            if (x.kind() == Kind.PLAYER || x.kind() == Kind.BLOCKED) continue;
            if (x.kind() == Kind.TOWN && x.label().equals(Villages.name(mine))) continue;
            if (find(f, e, new Find(x.kind(), x.label(), x.at(), x.day(), "folk of " + Villages.name(theirs)))) heard++;
        }
        for (Find x : atlas(mine)) {
            if (x.kind() == Kind.PLAYER || x.kind() == Kind.BLOCKED) continue;
            if (x.kind() == Kind.TOWN && x.label().equals(Villages.name(theirs))) continue;
            if (record(theirs, new Find(x.kind(), x.label(), x.at(), x.day(), "a scout of " + Villages.name(mine)))) gave++;
        }
        record(theirs, new Find(Kind.TOWN, Villages.name(mine), e.home, day, "a scout of " + Villages.name(mine)));
        if (heard + gave > 0) {
            FolkTalk.speak(f, "We swapped news of the land, " + Villages.name(theirs) + " and I: " + heard + " new to us, " + gave + " to them.");
        }
    }

    // ------------------------------------------------------------------ helpers

    @Nullable
    private static Mob foe(ServerLevel level, VillageFolkEntity f) {
        Mob best = null;
        double near = 8.0 * 8.0;
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(f.blockPosition()).inflate(8), x -> x instanceof Enemy && x.isAlive())) {
            double d = m.distanceToSqr(f);
            // [war-scouting] A drowned down in the river is no reason to leave the bank: only once it is up out
            // of the water, or right at the edge, is it run from. (A spy sent to watch a town from beside a river
            // ran from one in the water for the whole of its watch, and never counted a thing.)
            if (m.isInWater() && !f.isInWater() && d > 4.0 * 4.0) continue;
            if (d < near) { near = d; best = m; }
        }
        return best;
    }

    /** A torch to mark the spot, if it carries one. */
    static void placeTorch(ServerLevel level, VillageFolkEntity f) {
        BlockPos at = f.blockPosition();
        if (!level.getBlockState(at).canBeReplaced() || !level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) return;
        if (f.removeMatching(s -> s.is(Items.TORCH), 1) < 1) return;
        level.setBlockAndUpdate(at, net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState());
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    static BlockPos surface(ServerLevel level, BlockPos p) {
        if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) return p;
        return new BlockPos(p.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
    }

    static double flat(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static UUID owner(VillageFolkEntity f) {
        return UUID.nameUUIDFromBytes(("mca-scout-" + f.getUUID()).getBytes());
    }

    /** The ground round the scout kept awake as it goes, as a caravan's is. */
    private static void keepAwake(ServerLevel level, VillageFolkEntity f, Expedition e) {
        BlockPos here = f.blockPosition();
        if (e.window != null && e.window.distSqr(here) < 16 * 16) return;
        if (e.window != null) ChunkLoad.setLoaded(level, owner(f), e.window, 2, false);
        ChunkLoad.setLoaded(level, owner(f), here, 2, true);
        e.window = here.immutable();
    }

    static void release(ServerLevel level, VillageFolkEntity f, Expedition e) {
        if (e.window != null) ChunkLoad.setLoaded(level, owner(f), e.window, 2, false);
        e.window = null;
    }

    /** A scout lost on the way (died, or left the village): let the ground go. */
    public static void abandon(ServerLevel level, VillageFolkEntity f) {
        Expedition e = f.expedition();
        if (e == null) return;
        release(level, f, e);
        f.expedition(null);
    }

    // ------------------------------------------------------------------ for talk and the board

    /** "What have the scouts found?" — anybody can say; a scout gives the very spot. */
    public static String tell(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return "I've no village to keep an atlas for.";
        List<Find> all = atlas(village);
        boolean scout = f.stationTask() == AssistantEntity.StationTask.SCOUT;
        Expedition e = f.expedition();
        if (all.isEmpty()) {
            // [war-scouting] On a war footing a town of any size sends somebody to watch the enemy.
            String enemy = WarMap.talk(village, f.level().getDayTime() / 24000L);
            if (enemy != null) return enemy;
            if (Villages.headcount(village) < FROM) {
                return "We don't send anybody scouting yet — that's for a town of " + FROM + " or more. We're " + Villages.headcount(village) + ".";
            }
            return scout ? "Nothing in the atlas yet. Give me a few days out there!" : "The scouts haven't come back with anything yet.";
        }
        BlockPos from = f.blockPosition();
        List<Find> worth = new ArrayList<>();
        for (Find x : all) if (x.kind() != Kind.LAND && x.kind() != Kind.BLOCKED && x.kind() != Kind.PLAYER) worth.add(x);
        worth.sort(java.util.Comparator.comparingDouble(x -> x.at().distSqr(from)));
        StringBuilder sb = new StringBuilder();
        sb.append(scout ? "From my atlas: " : "The scouts have found: ");
        int n = 0;
        for (Find x : worth) {
            if (n == 5) break;
            if (n > 0) sb.append("; ");
            int dist = (int) Math.sqrt(x.at().distSqr(from)) / 10 * 10;
            sb.append(x.label()).append(", ").append(dist).append(" blocks ").append(Guide.direction(from, x.at()));
            if (scout) sb.append(" (").append(x.at().getX()).append(", ").append(x.at().getZ()).append(")");
            n++;
        }
        if (worth.size() > 5) sb.append("; and ").append(worth.size() - 5).append(" more");
        sb.append(". We've seen ").append(exploredPercent(village)).append("% of the land within ").append(RANGE).append(" blocks.");
        if (e != null) sb.append(" I'm out ").append(e.heading()).append(" right now.");
        String enemy = WarMap.talk(village, f.level().getDayTime() / 24000L);      // [war-scouting] what we know of the enemy
        if (enemy != null) sb.append(' ').append(enemy);
        return sb.toString();
    }

    /** For the board: what the scouts have found, in a line. */
    @Nullable
    public static String boardLine(UUID village) {
        // [war-scouting] What the scouts saw of the enemy, and how old it is (WarMap), after the atlas.
        String enemy = WarMap.boardLine(village);
        String atlas = atlasLine(village);
        if (enemy == null) return atlas;
        return atlas == null ? enemy : atlas + " " + enemy;
    }

    @Nullable
    private static String atlasLine(UUID village) {
        List<Find> all = atlas(village);
        if (all.isEmpty()) return Villages.headcount(village) >= FROM ? "Scouts: out exploring — nothing in the atlas yet." : null;
        Villages.Village v = Villages.get(village);
        BlockPos home = v == null ? BlockPos.ZERO : v.centre();
        List<String> latest = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && latest.size() < 3; i--) {
            Find x = all.get(i);
            if (x.kind() == Kind.LAND || x.kind() == Kind.BLOCKED || x.kind() == Kind.PLAYER) continue;
            latest.add(x.label() + " " + (int) Math.sqrt(x.at().distSqr(home)) / 10 * 10 + " " + Guide.direction(home, x.at()));
        }
        return "Scouts: " + all.size() + " things in the atlas" + (latest.isEmpty() ? "" : " — lately " + String.join(", ", latest))
            + "; " + exploredPercent(village) + "% of the land explored.";
    }

    /** Somewhere a scout can walk a player: finds near enough, nearest first. */
    public static List<Guide.Place> places(VillageFolkEntity f, int most) {
        List<Guide.Place> out = new ArrayList<>();
        UUID village = f.ownerId();
        if (village == null || f.stationTask() != AssistantEntity.StationTask.SCOUT) return out;
        List<Find> all = new ArrayList<>(atlas(village));
        all.sort(java.util.Comparator.comparingDouble(x -> x.at().distSqr(f.blockPosition())));
        for (Find x : all) {
            if (out.size() >= most) break;
            if (x.kind() == Kind.LAND || x.kind() == Kind.BLOCKED || x.kind() == Kind.PLAYER) continue;
            if (x.at().distSqr(f.blockPosition()) > (double) Guide.FURTHEST * Guide.FURTHEST) break;
            out.add(new Guide.Place("atlas" + out.size(), x.label(), x.at()));
        }
        return out;
    }

    /** For the tests and /village: the atlas in a line. */
    public static String debug(UUID village) {
        StringBuilder sb = new StringBuilder("atlas " + atlas(village).size() + ", explored " + exploredPercent(village) + "%");
        for (Find x : atlas(village)) sb.append("; ").append(x.kind().name().toLowerCase(Locale.ROOT)).append(' ').append(x.label());
        return sb.toString();
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
