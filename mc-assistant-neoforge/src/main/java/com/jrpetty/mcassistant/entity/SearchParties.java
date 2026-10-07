package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [batchF] Search parties. Every five seconds the town notes where each of its folk is, and whether it is
 * where a folk should be: in the town, at its own work (its fields, its woods, its mine), in its bed, or
 * away on business the town knows of (a caravan, an envoy's errand, scouting, the Nether, the job market,
 * the town's works out on the road, a mine run). A folk not seen in any of those for a whole day — wandered
 * off and lost, stuck in a hole far out, lost underground with no way it could walk — is missed.
 *
 * <p>Its family (its partner, its parents, its grown children) and two or three of its friends make up a
 * party and go out to look: to where it was last seen first, then round about there in widening rings,
 * then the way it was last seen heading, calling its name as they go. A missing folk that hears them calls
 * back, and they go to it. Found underground, it is set on the way up (MineStairs: the nearest stairs, or
 * its own) and they wait for it at the top; found stuck, they cut it a step out of the ground that holds
 * it (into the stores); then they bring it home. The chronicle tells it either way, and the folk remember
 * who came for them. At nightfall the party goes home and goes out again in the morning; after three days
 * the search is given up. A folk that turns up of its own accord calls the search off.
 */
public final class SearchParties {

    private SearchParties() {}

    /** Not seen where it should be for a whole day: missed. */
    public static final long MISSING = 24000L;
    /** How near a searcher must be for a missing folk to hear it, and answer. */
    static final double HEARD = 28.0;

    enum Stage { OUT, UP, HOME, NIGHT }

    /** A search under way: who for, who is out, where they look next, how it stands. */
    static final class Search {
        final UUID village, lost;
        final String lostName;
        final List<UUID> party = new ArrayList<>();
        final List<BlockPos> points = new ArrayList<>();
        final BlockPos lastSeen;
        final long started;
        /** The day it was last seen, and the day the search began (the town's days, as the chronicle counts them). */
        long sinceDay, startedDay;
        Stage stage = Stage.OUT;
        int point;
        int stageAt;
        long stageTime;
        int calledAt = -1000, answeredAt = -1000;
        @Nullable UUID finder;
        String foundWhere = "";

        Search(UUID village, UUID lost, String lostName, BlockPos lastSeen, long started) {
            this.village = village;
            this.lost = lost;
            this.lostName = lostName;
            this.lastSeen = lastSeen;
            this.started = started;
        }

        @Nullable UUID leader() { return party.isEmpty() ? null : party.get(0); }
    }

    /** The searches under way, by the missing folk; and which search each searcher is out on. */
    private static final Map<UUID, Search> SEARCHES = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> OUT_FOR = new ConcurrentHashMap<>();
    /** The ground kept awake round each searcher out past the town, by searcher: where it was last forced. */
    private static final Map<UUID, BlockPos> WINDOWS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        SEARCHES.clear();
        OUT_FOR.clear();
        WINDOWS.clear();
    }

    // ------------------------------------------------------------------ who is where

    /** [caves] Is the town out looking for this folk (a cave dweller that called for help waits for them)? */
    static boolean searchedFor(UUID lost) {
        return SEARCHES.containsKey(lost);
    }

    /** Is this folk where a folk should be (or away on business the town knows of)? */
    static boolean seen(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (f.isSleeping() || f.isHired() || f.isShowcase()) return true;
        if (CaveDwellers.missing(f)) return false;      // [caves] lost in the caves, or a day overdue from a trip: missed till found
        if (f.companionPlayer() != null || f.guidePlayer() != null) return true;     // out walking with a player
        if (f.trip() != null || f.expedition() != null || Nether.away(f) || JobSeekers.busy(f) || Drover.busy(f)
                || Stables.busy(f) || Patrols.escorting(f) || TownJobs.busy(f)) return true;
        Job j = f.peekJob();
        if (j != null && j.type() == Job.Type.MINE) return true;                   // down its own mine, on a run
        BlockPos at = f.blockPosition();
        boolean below = MineStairs.underground(level, at);
        WorkZone z = f.workZone();
        if (z != null) {
            double r = z.radius() + 8;
            double dx = at.getX() - z.center().getX(), dz = at.getZ() - z.center().getZ();
            if (dx * dx + dz * dz <= r * r && (!below || f.stationTask() == AssistantEntity.StationTask.MINE)) return true;
        }
        int reach = Villages.townReach(v.id()) + 12;
        double dx = at.getX() - v.centre().getX(), dz = at.getZ() - v.centre().getZ();
        return dx * dx + dz * dz <= (double) reach * reach && !below;
    }

    // ------------------------------------------------------------------ the round of the town

    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime(), day = Civics.day(level), t = level.getDayTime() % 24000L;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isShowcase()) continue;
            CompoundTag me = Civics.folk(f.getUUID());
            me.putLong("pos", f.blockPosition().asLong());
            Search s = SEARCHES.get(f.getUUID());
            if (seen(level, v, f)) {
                me.putLong("lastSeen", now);
                me.putLong("seenAt", f.blockPosition().asLong());
                if (s != null && (s.stage == Stage.OUT || s.stage == Stage.NIGHT) && s.finder == null) calledOff(level, s, day);
                continue;
            }
            if (!me.contains("lastSeen")) me.putLong("lastSeen", now);
            boolean givenUp = me.contains("givenUp") && day - me.getLong("givenUp") <= 3;     // searched for, three days, lately
            if (s == null && !givenUp && now - me.getLong("lastSeen") >= MISSING && now - me.getLong("lastSeen") < 40L * 24000L) {
                start(level, v, f, now);
            }
        }
        // The searches of this town: out by day, home at nightfall, out again in the morning, given up after three days.
        for (Search s : new ArrayList<>(SEARCHES.values())) {
            if (!s.village.equals(id)) continue;
            if (day - s.startedDay > 3) {
                givenUp(level, s, day);
                continue;
            }
            if (s.stage == Stage.OUT && (t >= 12800 || t < 900)) {
                s.stage = Stage.NIGHT;
                VillageFolkEntity leader = Civics.find(level, s.leader());
                if (leader != null) FolkTalk.speak(leader, "It's too dark to see. We'll go out again at first light.");
            } else if (s.stage == Stage.NIGHT && t >= 900 && t < 12800) {
                s.stage = Stage.OUT;
                s.point = 0;
            }
        }
    }

    /** A missing folk: its family and friends go out to look for it. False if there is nobody to go. */
    static boolean start(ServerLevel level, Villages.Village v, VillageFolkEntity lost, long now) {
        if (SEARCHES.containsKey(lost.getUUID())) return false;
        CompoundTag me = Civics.folk(lost.getUUID());
        BlockPos last = me.contains("seenAt") ? BlockPos.of(me.getLong("seenAt")) : lost.blockPosition();
        Search s = new Search(v.id(), lost.getUUID(), lost.displayNameCap(), last, now);
        s.startedDay = Civics.day(level);
        s.sinceDay = s.startedDay - Math.max(0, now - me.getLong("lastSeen")) / 24000L;
        // The family first, then its friends (its warmest, and those warmest to it), up to five in all.
        List<VillageFolkEntity> grown = Civics.grown(v.id());
        List<VillageFolkEntity> family = new ArrayList<>(), friends = new ArrayList<>();
        for (VillageFolkEntity f : grown) {
            if (f == lost || !fit(f, level)) continue;
            boolean kin = f.getUUID().equals(lost.life().partner()) || lost.parentIds().contains(f.getUUID())
                || f.parentIds().contains(lost.getUUID());
            if (kin) family.add(f);
            else if (lost.life().affinity(f.getUUID()) >= Social.FRIEND || f.life().affinity(lost.getUUID()) >= Social.FRIEND) friends.add(f);
        }
        friends.sort((a, b) -> Integer.compare(b.life().affinity(lost.getUUID()) + lost.life().affinity(b.getUUID()),
            a.life().affinity(lost.getUUID()) + lost.life().affinity(a.getUUID())));
        for (VillageFolkEntity f : family) if (s.party.size() < 3) s.party.add(f.getUUID());
        for (VillageFolkEntity f : friends) if (s.party.size() < 5 && s.party.size() < family.size() + 3) s.party.add(f.getUUID());
        if (s.party.isEmpty()) {
            // Nobody close to it: its nearest neighbours go.
            grown.sort((a, b) -> Double.compare(a.distanceToSqr(lost), b.distanceToSqr(lost)));
            for (VillageFolkEntity f : grown) if (f != lost && fit(f, level) && s.party.size() < 2) s.party.add(f.getUUID());
        }
        if (s.party.isEmpty()) return false;
        // Where they look: where it was last seen, round about there, then the way it was seen heading.
        s.points.add(last);
        for (int r : new int[]{ 16, 32 }) {
            for (int i = 0; i < 6; i++) {
                double a = Math.PI * 2 * i / 6;
                int x = last.getX() + (int) Math.round(Math.cos(a) * r), z = last.getZ() + (int) Math.round(Math.sin(a) * r);
                s.points.add(new BlockPos(x, last.getY(), z));
            }
        }
        if (me.contains("pos")) s.points.add(BlockPos.of(me.getLong("pos")));
        SEARCHES.put(lost.getUUID(), s);
        List<String> names = new ArrayList<>();
        for (UUID u : s.party) {
            OUT_FOR.put(u, lost.getUUID());
            VillageFolkEntity f = Civics.find(level, u);
            if (f != null) {
                names.add(f.displayNameCap());
                f.clearQueue();
                f.getNavigation().stop();
            }
        }
        long day = Civics.day(level);
        Villages.tell(v.id(), day, s.lostName + " has not been seen since day " + (s.sinceDay + 1) + ": " + Civics.names(names) + " went out to look");
        VillageFolkEntity leader = Civics.find(level, s.leader());
        if (leader != null) FolkTalk.speak(leader, "Nobody's seen " + s.lostName + " since yesterday. Come on — we're going to look.");
        return true;
    }

    /** Fit to go out searching: grown, awake, here, not on the watch after dark, not away. */
    private static boolean fit(VillageFolkEntity f, ServerLevel level) {
        if (f.isBaby() || f.isSleeping() || f.isShowcase() || f.isHired() || OUT_FOR.containsKey(f.getUUID())) return false;
        if (f.trip() != null || f.expedition() != null || Nether.away(f) || JobSeekers.busy(f)) return false;
        return !(f.stationTask() == AssistantEntity.StationTask.GUARD && level.isNight());
    }

    // ------------------------------------------------------------------ out looking

    /** The folk's hold (Civics.hold): a searcher out looking, or bringing the found one home; the found one following. */
    @Nullable
    static String hold(VillageFolkEntity f, ServerLevel level) {
        UUID lostId = OUT_FOR.get(f.getUUID());
        Search s = lostId == null ? SEARCHES.get(f.getUUID()) : SEARCHES.get(lostId);
        if (s == null) {
            if (lostId != null) OUT_FOR.remove(f.getUUID());
            return null;
        }
        if (f.isSleeping()) return null;
        boolean isLost = f.getUUID().equals(s.lost);
        if (isLost) return s.stage == Stage.HOME ? follow(f, level, s) : null;           // till found, it is as lost as it was
        VillageFolkEntity lost = Civics.find(level, s.lost);
        Villages.Village v = Villages.get(s.village);
        if (v == null) {
            OUT_FOR.remove(f.getUUID());
            return null;
        }
        if (s.stage == Stage.NIGHT) {
            // Out past the town at nightfall: home, and its own evening; in the town, its evening is its own already.
            int reach = Villages.townReach(v.id());
            if (Civics.flat(f, v.centre()) <= (double) reach * reach) {
                letGo(level, f.getUUID());
                return null;
            }
            keepAwake(level, f, v);
            Civics.goTo(f, v.centre(), 4.0, 0.9);
            return "home for the night from searching for " + s.lostName;
        }
        keepAwake(level, f, v);
        return switch (s.stage) {
            case NIGHT -> null;
            case OUT -> look(f, level, s, lost, v);
            case UP -> waitAbove(f, level, s, lost, v);
            case HOME -> leadHome(f, level, s, lost, v);
        };
    }

    private static UUID owner(UUID folk) {
        return UUID.nameUUIDFromBytes(("mca-search-" + folk).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Out past the town: the ground round a searcher kept awake as it goes (TownJobs keeps its road crews' so). */
    static void keepAwake(ServerLevel level, VillageFolkEntity f, Villages.Village v) {
        BlockPos here = f.blockPosition();
        boolean out = Math.max(Math.abs(here.getX() - v.centre().getX()), Math.abs(here.getZ() - v.centre().getZ()))
            > Villages.townReach(v.id()) + 16;
        BlockPos was = WINDOWS.get(f.getUUID());
        if (!out) {
            letGo(level, f.getUUID());
            return;
        }
        if (was != null && was.distSqr(here) < 16 * 16) return;
        if (was != null) com.jrpetty.mcassistant.ChunkLoad.setLoaded(level, owner(f.getUUID()), was, 1, false);
        com.jrpetty.mcassistant.ChunkLoad.setLoaded(level, owner(f.getUUID()), here, 1, true);
        WINDOWS.put(f.getUUID(), here.immutable());
    }

    /** The ground kept awake round a searcher let go. */
    static void letGo(ServerLevel level, UUID folk) {
        BlockPos was = WINDOWS.remove(folk);
        if (was != null) com.jrpetty.mcassistant.ChunkLoad.setLoaded(level, owner(folk), was, 1, false);
    }

    /** The search over: everybody's ground let go, and nobody out for it any more. */
    private static void disband(ServerLevel level, Search s) {
        SEARCHES.remove(s.lost);
        for (UUID u : s.party) {
            OUT_FOR.remove(u);
            letGo(level, u);
        }
    }

    /** Walk the search's points, calling its name; go to it when it answers; find it. */
    private static String look(VillageFolkEntity f, ServerLevel level, Search s, @Nullable VillageFolkEntity lost, Villages.Village v) {
        if (lost != null && f.distanceToSqr(lost) < HEARD * HEARD) {
            if (f.tickCount - s.answeredAt > 160 || f.tickCount < s.answeredAt) {
                s.answeredAt = f.tickCount;
                FolkTalk.speak(lost, FolkTalk.pick(lost.getRandom(), "Here! I'm over here!", "Help! Over here!", "I'm here — I can't get out!"));
            }
            if (f.distanceToSqr(lost) <= 3.5 * 3.5) {
                found(level, s, f, lost, v);
                return "found " + s.lostName;
            }
            Civics.goTo(f, beside(level, lost, f), 1.5, 1.0);
            return "going to " + s.lostName + ", who called back";
        }
        if (f.tickCount - s.calledAt > 140 || f.tickCount < s.calledAt) {
            s.calledAt = f.tickCount;
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), s.lostName + "! " + s.lostName.toUpperCase(java.util.Locale.ROOT) + "!",
                s.lostName + "! Where are you?", "Can you hear me, " + s.lostName + "?"));
        }
        if (s.point >= s.points.size()) {
            Civics.goTo(f, s.lastSeen, 6.0, 0.9);
            return "searching for " + s.lostName;
        }
        BlockPos p = surface(level, s.points.get(s.point));
        // The party keeps together: each to the point the leader is making for, a step or two apart.
        if (f.getUUID().equals(s.leader())) {
            if (Civics.goTo(f, p, 4.0, 0.95) || f.tickCount - s.stageAt > 900) {
                s.point++;
                s.stageAt = f.tickCount;
            }
        } else {
            int i = Math.max(0, s.party.indexOf(f.getUUID()));
            Civics.goTo(f, p.offset((i % 2 == 0 ? 1 : -1) * (2 + i), 0, (i % 3 - 1) * 3), 4.0, 0.95);
        }
        return "out with the search party for " + s.lostName;
    }

    /** Found: set on its way up if it is underground, a step cut for it if it is stuck; then home. */
    static void found(ServerLevel level, Search s, VillageFolkEntity finder, VillageFolkEntity lost, Villages.Village v) {
        s.finder = finder.getUUID();
        s.foundWhere = where(v, lost.blockPosition());
        FolkTalk.speak(finder, FolkTalk.pick(finder.getRandom(), "There you are! We've been looking everywhere!", s.lostName + "! Thank goodness."));
        lost.sayLater(FolkTalk.pick(lost.getRandom(), "I couldn't find my way back!", "Am I glad to see you!"), 40);
        lost.getLookControl().setLookAt(finder, 30.0F, 30.0F);
        if (MineStairs.underground(level, lost.blockPosition())) {
            // Lost below: the way up, as a folk lost underground takes it (the nearest stairs, mended, or its own).
            lost.clearQueue();
            lost.enqueueFront(Job.mine(lost.blockPosition().getY(), MineStairs.OUT));
            s.stage = Stage.UP;
        } else {
            s.stage = Stage.HOME;
        }
        s.stageAt = finder.tickCount;
        s.stageTime = level.getGameTime();
    }

    /** It is making its way up: the party waits at the top, over where it is. */
    private static String waitAbove(VillageFolkEntity f, ServerLevel level, Search s, @Nullable VillageFolkEntity lost, Villages.Village v) {
        if (lost == null) return null;
        if (!MineStairs.underground(level, lost.blockPosition())) {
            s.stage = Stage.HOME;
            s.stageTime = level.getGameTime();
            return "bringing " + s.lostName + " home";
        }
        Job j = lost.peekJob();
        if (j == null || j.type() != Job.Type.MINE) lost.enqueueFront(Job.mine(lost.blockPosition().getY(), MineStairs.OUT));
        Civics.goTo(f, surface(level, lost.blockPosition()), 4.0, 0.9);
        return "waiting for " + s.lostName + " to come up";
    }

    /** The leader home to the square; the rest with it; the found one following the leader. */
    private static String leadHome(VillageFolkEntity f, ServerLevel level, Search s, @Nullable VillageFolkEntity lost, Villages.Village v) {
        if (lost == null) {
            // Lost to sight again on the way (out of the loaded ground): home, and the tracking takes it up again.
            if (Civics.goTo(f, v.centre(), 5.0, 0.9)) OUT_FOR.remove(f.getUUID());
            return "home from the search";
        }
        if (f.getUUID().equals(s.leader())) {
            double home = Civics.flat(lost, v.centre());
            if (home < 12 * 12) {
                home(level, s, lost, v);
                return null;
            }
            // Fallen well behind (or the leader taken off on other business a while): back for it.
            if (f.distanceToSqr(lost) > 16 * 16) {
                Civics.goTo(f, beside(level, lost, f), 2.5, 0.9);
                return "going back for " + s.lostName;
            }
            // Keep to its pace: wait for it if it has fallen a little behind.
            if (f.distanceToSqr(lost) > 10 * 10) {
                f.getNavigation().stop();
                f.getLookControl().setLookAt(lost, 30.0F, 30.0F);
                return "waiting for " + s.lostName + " to keep up";
            }
            Civics.goTo(f, v.centre(), 4.0, 0.7);
            return "bringing " + s.lostName + " home";
        }
        // The rest keep with the leader (never down the hole after the one they came for).
        VillageFolkEntity leader = Civics.find(level, s.leader());
        Civics.goTo(f, leader != null ? leader.blockPosition() : beside(level, lost, f), 3.0, 0.8);
        return "bringing " + s.lostName + " home";
    }

    /** The found one, after the leader; stuck where it stands, a step cut for it out of the ground. */
    private static String follow(VillageFolkEntity f, ServerLevel level, Search s) {
        VillageFolkEntity leader = Civics.find(level, s.leader());
        Villages.Village v = Villages.get(s.village);
        if (leader == null || v == null) return null;
        if (f.distanceToSqr(leader) > 3.0 * 3.0) {
            Civics.goTo(f, leader.blockPosition(), 2.5, 0.85);
            if (level.getGameTime() - s.stageTime > 100 && (level.getGameTime() - s.stageTime) % 100 < 4 && stuck(f, leader)) {
                cutAStep(level, v, f, leader.blockPosition());
            }
        } else {
            f.getNavigation().stop();
        }
        return "being brought home by " + leader.displayNameCap();
    }

    /** Can it not walk to the leader from where it stands? */
    static boolean stuck(VillageFolkEntity f, VillageFolkEntity to) {
        Path p = f.getNavigation().createPath(to.blockPosition(), 1);
        return p == null || !p.canReach();
    }

    /**
     * A step out of the hole it is in, toward where it is going: the block of earth or rock beside it at head
     * height cut away (the one below left for the step), and the one over that if it is in the way. What is
     * cut goes into the stores. Never anything built, nor bedrock (MineStairs.ground).
     */
    static boolean cutAStep(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos toward) {
        BlockPos feet = f.blockPosition();
        Direction best = null;
        double bd = Double.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = feet.relative(d);
            BlockState step = level.getBlockState(n), head = level.getBlockState(n.above());
            if (step.isAir() || !MineStairs.ground(head)) continue;
            double dist = n.distSqr(toward);
            if (dist < bd) { bd = dist; best = d; }
        }
        if (best == null) return false;
        BlockPos n = feet.relative(best);
        for (BlockPos cut : new BlockPos[]{ n.above(), n.above(2) }) {
            BlockState st = level.getBlockState(cut);
            if (st.isAir() || !MineStairs.ground(st)) continue;
            for (ItemStack drop : Block.getDrops(st, level, cut, null)) TownWork.give(level, v, drop);
            level.destroyBlock(cut, false);
        }
        FolkTalk.speak(f, "Mind out — there's a step now.");
        return true;
    }

    /** Home: the chronicle, the memories, the warmth, and the town's tally. */
    static void home(ServerLevel level, Search s, VillageFolkEntity lost, Villages.Village v) {
        long day = Civics.day(level);
        List<String> names = new ArrayList<>();
        disband(level, s);
        for (UUID u : s.party) {
            VillageFolkEntity f = Civics.find(level, u);
            if (f == null) continue;
            names.add(f.displayNameCap());
            f.life().feel(lost.getUUID(), lost.displayNameCap(), 8);
            lost.life().feel(f.getUUID(), f.displayNameCap(), 12);
            f.persona().remember(day, "we found " + s.lostName + " " + s.foundWhere + " and brought it home", 4);
            CompoundTag me = Civics.folk(f.getUUID());
            me.putInt("searched", me.getInt("searched") + 1);
        }
        lost.persona().remember(day, "I was lost, and " + Civics.names(names) + " came and found me", 7);
        Civics.glad(lost, Civics.FOUND, day);
        CompoundTag me = Civics.folk(lost.getUUID());
        me.putLong("lastSeen", level.getGameTime());
        String line = s.lostName + ", missing since day " + (s.sinceDay + 1) + ", was found " + s.foundWhere + " by the search party ("
            + Civics.names(names) + ") and brought home";
        Villages.tell(v.id(), day, line);
        log(v.id(), "Day " + (day + 1) + ": " + Civics.cap(line) + ".");
        FolkTalk.speak(lost, FolkTalk.pick(lost.getRandom(), "Home! Thank you, all of you.", "I'll not wander off like that again."));
        Civics.changed();
    }

    private static void calledOff(ServerLevel level, Search s, long day) {
        disband(level, s);
        Villages.tell(s.village, day, s.lostName + " turned up of its own accord, and the search was called off");
        log(s.village, "Day " + (day + 1) + ": " + s.lostName + " turned up of its own accord.");
    }

    private static void givenUp(ServerLevel level, Search s, long day) {
        disband(level, s);
        Civics.folk(s.lost).putLong("givenUp", day);
        Villages.tell(s.village, day, "the search for " + s.lostName + " was given up after three days");
        log(s.village, "Day " + (day + 1) + ": the search for " + s.lostName + " was given up.");
        Civics.changed();
    }

    private static void log(UUID village, String line) {
        ListTag log = Civics.list(Civics.town(village), "searchLog");
        log.add(StringTag.valueOf(line));
        while (log.size() > 6) log.remove(0);
    }

    /** "out past the north fields", "down a hole in the woods": where it was, as a folk would put it. */
    static String where(Villages.Village v, BlockPos at) {
        int dx = at.getX() - v.centre().getX(), dz = at.getZ() - v.centre().getZ();
        String way = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north");
        int far = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
        return (far > 200 ? "far out to the " : far > 60 ? "out to the " : "just " ) + way + " of the town";
    }

    /**
     * Where to stand to reach a folk that may be down a hole: the highest ground round it (the rim), the side
     * nearest the one coming; where it is itself, if it stands on open ground.
     */
    static BlockPos beside(ServerLevel level, VillageFolkEntity lost, VillageFolkEntity from) {
        BlockPos at = lost.blockPosition();
        BlockPos best = at;
        int bestY = surface(level, at).getY();
        double bestD = Double.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos top = surface(level, at.relative(d));
            if (top.getY() < bestY || top.getY() > at.getY() + 3) continue;
            double dist = from.distanceToSqr(top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
            if (top.getY() > bestY || dist < bestD) { best = top; bestY = top.getY(); bestD = dist; }
        }
        return best;
    }

    private static BlockPos surface(ServerLevel level, BlockPos p) {
        if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) return p;
        return new BlockPos(p.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
    }

    // ------------------------------------------------------------------ where the player sees it

    static List<String> board(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        for (Search s : SEARCHES.values()) {
            if (!s.village.equals(village)) continue;
            out.add("RW|Missing: " + s.lostName + (s.stage == Stage.OUT ? " — a search party is out looking." : s.stage == Stage.NIGHT
                ? " — the search goes out again at first light." : " — found, and on the way home."));
        }
        return out;
    }

    static List<String> book(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        for (Search s : SEARCHES.values()) if (s.village.equals(village)) out.add("Search party out for " + s.lostName + " (" + s.party.size() + " searchers).");
        ListTag log = Civics.list(Civics.town(village), "searchLog");
        for (int i = log.size() - 1; i >= 0; i--) out.add("  " + log.getString(i));
        return out;
    }

    // ------------------------------------------------------------------ the tests

    /** Tests: this folk last seen where it should be so many ticks ago, at this spot. */
    public static void missingForTests(VillageFolkEntity f, long ticksAgo, BlockPos lastSeenAt) {
        CompoundTag me = Civics.folk(f.getUUID());
        me.putLong("lastSeen", f.level().getGameTime() - ticksAgo);
        me.putLong("seenAt", lastSeenAt.asLong());
        me.remove("givenUp");
    }

    /** Tests: the town's look at who is where (and the search started for anybody missed). */
    public static void lookForTests(ServerLevel level, Villages.Village v) {
        tick(level, v);
    }

    /** Tests: is this folk where a folk should be? */
    public static boolean seenForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        return seen(level, v, f);
    }

    /** Tests: the party out for this folk (their ids), or empty. */
    public static List<UUID> partyForTests(UUID lost) {
        Search s = SEARCHES.get(lost);
        return s == null ? List.of() : new ArrayList<>(s.party);
    }

    /** Tests: how the search for this folk stands ("OUT", "UP", "HOME", "NIGHT"), or "none". */
    public static String stageForTests(UUID lost) {
        Search s = SEARCHES.get(lost);
        return s == null ? "none" : s.stage.name() + (s.finder != null ? " found" : "") + " point " + s.point + "/" + s.points.size();
    }

    /** Tests: a step cut for a stuck folk, toward a spot (true if one was). */
    public static boolean cutAStepForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos toward) {
        return cutAStep(level, v, f, toward);
    }
}
