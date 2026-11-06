package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [war-scouting] Pickets on the approaches. A town on its guard or at war puts a lookout on the road toward
 * each of its rivals (two at most), thirty blocks out past its last buildings, so that whoever comes from
 * there is seen before they are at the gate.
 * <ul>
 * <li><b>Who stands picket.</b> A guard, if the watch can spare one (half the watch always stays in the
 *     town); else one of the militia, once the town calls one up; else a hunter, who knows how to keep
 *     still and look. One by day and another by night, where there are hands enough; the night's stands
 *     the night through.</li>
 * <li><b>What it watches for.</b> Any grown folk of a town we are at odds with, within forty-four blocks
 *     of it (Sharp Eyes, four more), in plain sight. A spy is pointed out to the watch, who go after it
 *     (Spies). An envoy or a herald on the road is run ahead of: the town's bell rung once, and the leader
 *     told who is coming. Anybody else of theirs on the approach is an enemy coming: the picket runs home
 *     with it, and the alarm bell is rung early (Raids.warAlarm), the gates shut and the watch on the
 *     walls, for as long as they are about; a picket that cannot get home in twenty-five seconds shouts it
 *     ahead and the bell rings anyway.</li>
 * <li><b>Where they were seen</b> goes on the war map (Intel.sighted).</li>
 * </ul>
 * At peace the pickets come home to their trades.
 */
public final class Pickets {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Pickets() {}

    /** How far past the town's reach a picket stands. */
    static final int OUT = 30;
    /** How far a picket can make out who is coming. */
    static final int SIGHT = 44;
    /** The most approaches watched. */
    static final int MOST = 2;
    /** The longest a picket runs before its shout is heard and the bell rung anyway (ticks). */
    static final int RUN_MOST = 500;
    /** The least the alarm rings for an enemy on the approach (ticks), and how long a sighting stands before the same is told again. */
    static final long HOLD = 1200L, AGAIN = 2400L;

    /** A post on an approach: whose town, toward which rival, where, and the road's name ("the east road"). */
    public record Post(UUID village, UUID toward, BlockPos at, String road) {}

    enum Phase { GOING, WATCH, RUNNING }

    /** One picket's watch. */
    static final class Stand {
        final UUID village;
        Post post;
        Phase phase = Phase.GOING;
        long since;
        int lookTick = -100;
        /** What it is running home with. */
        @Nullable UUID them;
        String what = "";
        int many;
        boolean alarm;
        @Nullable BlockPos seenAt;

        Stand(UUID village, Post post) {
            this.village = village;
            this.post = post;
        }
    }

    private static final Map<UUID, List<Post>> POSTS = new ConcurrentHashMap<>();
    /** By folk. */
    private static final Map<UUID, Stand> ON = new ConcurrentHashMap<>();
    /** By "us/them": when that town's folk were last told of. */
    private static final Map<String, Long> TOLD = new ConcurrentHashMap<>();
    /** By village: until when the alarm a picket raised is kept up while the enemy is about. */
    private static final Map<UUID, Long> RAISED = new ConcurrentHashMap<>();

    static void resetForTests() {
        POSTS.clear();
        ON.clear();
        TOLD.clear();
        RAISED.clear();
    }

    /** Is this folk standing picket (or running home from it)? */
    public static boolean on(VillageFolkEntity f) {
        return ON.containsKey(f.getUUID());
    }

    /** This town's pickets now. */
    public static List<VillageFolkEntity> of(UUID village) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f) {
                Stand s = ON.get(f.getUUID());
                if (s != null && s.village.equals(village)) out.add(f);
            }
        }
        return out;
    }

    /** This town's posts as last worked out (none at peace). */
    public static List<Post> posts(UUID village) {
        return POSTS.getOrDefault(village, List.of());
    }

    /** Where this folk stands picket, or null. */
    @Nullable
    public static Post postOf(VillageFolkEntity f) {
        Stand s = ON.get(f.getUUID());
        return s == null ? null : s.post;
    }

    // ------------------------------------------------------------------ the posts, and who stands them (WarScouting)

    /** Every second: the posts kept, and (every ten seconds) who stands them; at peace, everybody home. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Wars.footing(id) == Wars.Footing.PEACE) {
            if (POSTS.remove(id) != null) standDown(id, "peace");
            RAISED.remove(id);
            return;
        }
        long now = level.getGameTime();
        keepAlarm(level, v, now);
        if (POSTS.containsKey(id) && now % 200 >= 20) return;
        List<Post> posts = lay(level, v);
        POSTS.put(id, posts);
        boolean night = level.getDayTime() % 24000L >= 12500L;
        List<VillageFolkEntity> hands = hands(v);
        for (int i = 0; i < posts.size(); i++) {
            Post p = posts.get(i);
            int pick = night && hands.size() >= 2 * posts.size() ? i + posts.size() : i;
            VillageFolkEntity who = pick < hands.size() ? hands.get(pick) : null;
            VillageFolkEntity now0 = standing(id, i);
            if (now0 != null && now0 == who) {
                ON.get(who.getUUID()).post = p;
                continue;
            }
            if (now0 != null) {
                ON.remove(now0.getUUID());
                FolkTalk.speak(now0, "Relieved. Home, and something hot.");
            }
            if (who == null) continue;
            ON.remove(who.getUUID());
            Stand s = new Stand(id, p);
            s.since = now;
            ON.put(who.getUUID(), s);
            who.clearQueue();
            FolkTalk.speak(who, FolkTalk.pick(who.getRandom(), "Out to the picket on " + p.road() + ". I'll keep my eyes open.",
                "My turn on " + p.road() + ". Nobody gets by me unseen."));
            LOG.info("[MCA-PICKET] {} of {} stands picket on {} at {}", who.displayNameCap(), Villages.name(id), p.road(), p.at().toShortString());
        }
    }

    /** Who of this town stands at post i now, or null. */
    @Nullable
    private static VillageFolkEntity standing(UUID village, int i) {
        List<Post> posts = POSTS.getOrDefault(village, List.of());
        if (i >= posts.size()) return null;
        for (VillageFolkEntity f : of(village)) {
            Stand s = ON.get(f.getUUID());
            if (s != null && s.post.toward().equals(posts.get(i).toward())) return f;
        }
        return null;
    }

    /** The posts: on the way toward each rival (at war first), thirty blocks past the town's reach, on the ground. */
    static List<Post> lay(ServerLevel level, Villages.Village v) {
        List<Post> out = new ArrayList<>();
        BlockPos c = v.centre();
        int r = Villages.townReach(v.id()) + OUT;
        for (UUID them : Spying.rivals(v.id())) {
            if (out.size() >= MOST) break;
            Villages.Village o = Villages.get(them);
            if (o == null || !o.dim().equals(v.dim())) continue;
            double ang = Math.atan2(o.centre().getZ() - c.getZ(), o.centre().getX() - c.getX());
            BlockPos at = Scouts.surface(level, new BlockPos(c.getX() + (int) Math.round(Math.cos(ang) * r), c.getY(),
                c.getZ() + (int) Math.round(Math.sin(ang) * r)));
            out.add(new Post(v.id(), them, at, "the " + Guide.direction(c, at) + " road"));
        }
        return out;
    }

    /**
     * Who may stand picket, best first: the guards the watch can spare (it keeps half of them, and at least
     * one, in the town), then the militia, then the hunters. Never the leader, never a folk out on the land.
     */
    static List<VillageFolkEntity> hands(Villages.Village v) {
        UUID elder = Villages.elder(v.id());
        List<VillageFolkEntity> watch = new ArrayList<>(Patrols.watch(v.id()));
        int keep = Math.max(1, (watch.size() + 1) / 2);
        List<VillageFolkEntity> out = new ArrayList<>();
        for (int i = keep; i < watch.size(); i++) if (spare(watch.get(i), elder)) out.add(watch.get(i));
        List<VillageFolkEntity> more = new ArrayList<>();
        for (VillageFolkEntity f : WarFooting.militia(v.id())) {
            if (f.stationTask() != StationTask.GUARD && spare(f, elder)) more.add(f);
        }
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == StationTask.HUNT && spare(f, elder) && !more.contains(f)) more.add(f);
        }
        more.sort(Comparator.comparing(Entity::getUUID));
        out.addAll(more);
        return out;
    }

    private static boolean spare(VillageFolkEntity f, @Nullable UUID elder) {
        return f.isAlive() && !f.isBaby() && !f.isSleeping() && !f.isHired() && !f.getUUID().equals(elder) && f.trip() == null
            && f.expedition() == null && !Spies.held(f) && !Nether.away(f);
    }

    /** At peace: every picket of this town home to its trade. */
    static void standDown(UUID village, String why) {
        for (VillageFolkEntity f : of(village)) {
            ON.remove(f.getUUID());
            FolkTalk.speak(f, "Back to the town. The picket's done with.");
        }
        LOG.info("[MCA-PICKET] {}'s pickets stood down ({})", Villages.name(village), why);
    }

    // ------------------------------------------------------------------ the picket's own step (WarScouting.hold)

    /** On picket: to the post, watching from it, or running home with word. True while that is what it does. */
    static boolean stand(VillageFolkEntity f, ServerLevel level, boolean think) {
        Stand s = ON.get(f.getUUID());
        if (s == null) return false;
        // The bell already ringing: the picket in with everybody else (a guard to the wall).
        if (s.phase != Phase.RUNNING && Raids.underAlarm(s.village)) return false;
        if (!think) return true;
        Villages.Village v = Villages.get(s.village);
        if (v == null || f.isSleeping()) {
            ON.remove(f.getUUID());
            return false;
        }
        long now = level.getGameTime();
        switch (s.phase) {
            case GOING -> {
                BlockPos at = s.post.at();
                if (Scouts.flat(f.blockPosition(), at) > 3 * 3) {
                    if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(at, 1.0D);
                    f.hobbyNow = "out to the picket on " + s.post.road();
                    look(level, f, s, v, now);              // it keeps its eyes open on the way
                    return true;
                }
                s.phase = Phase.WATCH;
                f.getNavigation().stop();
            }
            case WATCH -> {
                if (Scouts.flat(f.blockPosition(), s.post.at()) > 6 * 6) {
                    s.phase = Phase.GOING;
                    return true;
                }
                Villages.Village o = Villages.get(s.post.toward());
                if (o != null && f.getRandom().nextInt(8) != 0) {
                    f.getLookControl().setLookAt(o.centre().getX() + 0.5, f.getEyeY(), o.centre().getZ() + 0.5);
                }
                f.hobbyNow = "on picket on " + s.post.road();
                look(level, f, s, v, now);
            }
            case RUNNING -> run(level, f, s, v, now);
        }
        return true;
    }

    /** A look up the road: folk of a town we are at odds with, in sight. Starts the run home if there is word to take. */
    static void look(ServerLevel level, VillageFolkEntity f, Stand s, Villages.Village v, long now) {
        if (f.tickCount - s.lookTick < 20) return;
        s.lookTick = f.tickCount;
        double sight = SIGHT + FolkSkills.sightBonus(f);
        Map<UUID, List<VillageFolkEntity>> seen = new java.util.HashMap<>();
        for (UUID them : Spying.rivals(v.id())) {
            Villages.Village o = Villages.get(them);
            if (o == null) continue;
            for (AssistantEntity a : Villages.folkOf(them)) {
                if (!(a instanceof VillageFolkEntity x) || x.isBaby() || !x.isAlive() || x.level() != level || Spies.held(x)) continue;
                if (x.distanceToSqr(f) > sight * sight || !f.hasLineOfSight(x)) continue;
                // Nearer our heart than its own: on our side, coming our way, not about its own town's fields.
                if (Scouts.flat(x.blockPosition(), v.centre()) >= Scouts.flat(x.blockPosition(), o.centre())) continue;
                seen.computeIfAbsent(them, k -> new ArrayList<>()).add(x);
            }
        }
        for (Map.Entry<UUID, List<VillageFolkEntity>> e : seen.entrySet()) {
            UUID them = e.getKey();
            List<VillageFolkEntity> who = e.getValue();
            String key = v.id() + "/" + them;
            java.util.Set<UUID> militia = Intel.militia(them);
            int spies = 0, envoys = 0, armed = 0;
            for (VillageFolkEntity x : who) {
                if (Spying.missionOf(x) != null) spies++;
                else if (x.trip() != null) envoys++;
                if (x.stationTask() == StationTask.GUARD || militia.contains(x.getUUID()) || armedNow(x)) armed++;
            }
            int others = who.size() - spies - envoys;
            String name = Villages.name(them);
            BlockPos at = who.get(0).blockPosition();
            long day = level.getDayTime() / 24000L;
            if (others == 0 && envoys == 0) {
                // A spy: pointed out to the watch, who go after it (Spies); no running off and leaving the post.
                if (TOLD.getOrDefault(key + "/spy", -100000L) > now - AGAIN) continue;
                TOLD.put(key + "/spy", now);
                Intel.sighted(v.id(), new Intel.Sighting(them, day, at, spies, "a scout", f.displayNameCap()));
                FolkTalk.speak(f, "A stranger creeping up from " + name + "'s way — a spy, or I'm a turnip!");
                continue;
            }
            if (TOLD.getOrDefault(key, -100000L) > now - AGAIN) continue;
            TOLD.put(key, now);
            s.them = them;
            s.many = others > 0 ? others : envoys;
            s.alarm = others > 0;
            s.what = others > 0 ? (armed > 0 ? TownCalendar.inWords(Math.min(99, others)) + " of " + name + "'s folk under arms"
                    : TownCalendar.inWords(Math.min(99, others)) + " of " + name + "'s folk")
                : "an envoy from " + name;
            s.seenAt = at;
            s.phase = Phase.RUNNING;
            s.since = now;
            Intel.sighted(v.id(), new Intel.Sighting(them, day, at, s.many, others > 0 ? (armed > 0 ? "folk under arms" : "folk") : "an envoy",
                f.displayNameCap()));
            FolkTalk.speak(f, s.alarm ? "Folk of " + name + " on " + s.post.road() + "! Back to warn the town!"
                : "An envoy from " + name + " on the road. I'll run ahead and tell the elder.");
            LOG.info("[MCA-PICKET] {} on {} saw {} ({} blocks off); running home", f.displayNameCap(), s.post.road(), s.what,
                (int) Math.sqrt(f.distanceToSqr(who.get(0))));
            return;
        }
    }

    /** A weapon in its hand. */
    static boolean armedNow(VillageFolkEntity x) {
        var h = x.getMainHandItem();
        return h.getItem() instanceof net.minecraft.world.item.SwordItem || h.getItem() instanceof net.minecraft.world.item.AxeItem
            || h.is(net.minecraft.world.item.Items.BOW) || h.is(net.minecraft.world.item.Items.CROSSBOW);
    }

    /** Home at a run with the word; at the square (or when its shout carries) the bell. */
    static void run(ServerLevel level, VillageFolkEntity f, Stand s, Villages.Village v, long now) {
        BlockPos home = v.centre();
        boolean there = Scouts.flat(f.blockPosition(), home) <= (Watch.R + 8) * (Watch.R + 8);
        if (!there && now - s.since < RUN_MOST) {
            if (f.getNavigation().isDone() || f.tickCount % 20 == 0) f.walkTo(home, 1.4D);
            f.hobbyNow = "running home from the picket with word of " + s.what;
            return;
        }
        warn(level, f, s, v, there);
        s.phase = Phase.GOING;
        s.since = now;
    }

    /** The word brought home: the alarm for an enemy coming, the bell once for an envoy. */
    static void warn(ServerLevel level, VillageFolkEntity f, Stand s, Villages.Village v, boolean there) {
        long day = level.getDayTime() / 24000L;
        String name = s.them == null ? "the enemy" : Villages.name(s.them);
        Direction from = s.seenAt == null ? null
            : Direction.getNearest(s.seenAt.getX() - v.centre().getX(), 0, s.seenAt.getZ() - v.centre().getZ());
        if (s.alarm) {
            String why = s.what + " on " + s.post.road();
            boolean rang = Raids.warAlarm(level, v, why, from, HOLD);
            RAISED.put(v.id(), level.getGameTime() + HOLD);
            FolkTalk.speak(f, there ? "Ring the bell! " + capital(s.what) + ", on " + s.post.road() + "!"
                : "(shouting ahead) " + capital(s.what) + " on " + s.post.road() + "! Ring the bell!");
            Villages.tell(v.id(), day, f.displayNameCap() + ", on picket on " + s.post.road() + ", brought word of " + s.what
                + (rang ? "; the bell was rung and the gates shut" : ""));
            LOG.info("[MCA-PICKET] {}: {} brought word of {}; alarm {}", Villages.name(v.id()), f.displayNameCap(), s.what, rang ? "rung" : "already ringing");
        } else {
            Watch.ring(level, v);
            FolkTalk.speak(f, "An envoy from " + name + " is on the road, elder! Not far behind me.");
            Villages.tell(v.id(), day, f.displayNameCap() + ", on picket, ran ahead with word of an envoy from " + name + " on " + s.post.road());
            Raids.tellNear(level, v.centre(), 120, Component.literal("A picket of " + Villages.name(v.id()) + " brings word: an envoy from "
                + name + " is coming.").withStyle(ChatFormatting.YELLOW), true);
        }
    }

    /** The alarm a picket raised kept up while folk of the enemy are still about the town. */
    static void keepAlarm(ServerLevel level, Villages.Village v, long now) {
        Long until = RAISED.get(v.id());
        if (until == null) return;
        if (!Raids.underAlarm(v.id())) {
            RAISED.remove(v.id());
            return;
        }
        if (now < until - 200) return;
        int near = Villages.townReach(v.id()) + 48;
        for (UUID them : Spying.rivals(v.id())) {
            for (AssistantEntity a : Villages.folkOf(them)) {
                if (a instanceof VillageFolkEntity x && x.level() == level && x.isAlive() && x.trip() == null && Spying.missionOf(x) == null
                        && !Spies.held(x) && Scouts.flat(x.blockPosition(), v.centre()) <= (double) near * near) {
                    Raids.warAlarm(level, v, "folk of " + Villages.name(them) + " about the town", null, 400L);
                    RAISED.put(v.id(), now + 400L);
                    return;
                }
            }
        }
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the pickets put out now (as the town's look round would), and who stands where. */
    public static List<VillageFolkEntity> postNowForTests(ServerLevel level, Villages.Village v) {
        POSTS.remove(v.id());
        tick(level, v);
        return of(v.id());
    }

    /** Tests: is this picket running home with word? */
    public static boolean runningForTests(VillageFolkEntity f) {
        Stand s = ON.get(f.getUUID());
        return s != null && s.phase == Phase.RUNNING;
    }
}
