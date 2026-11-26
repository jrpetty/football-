package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import com.jrpetty.mcassistant.village.WallPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featbuild] The night watch on the town wall (CastleWall): set rounds on the wall walk and posts at the gates, lanterns in
 * hand, a bell when trouble is seen, and the watch handed over at dusk, midnight and dawn.
 *
 * <ul>
 * <li><b>The posts.</b> The night's posts go round the wall in a fixed order: the north wall walk, the first gate, the east
 *     wall walk, the second gate, and so on. The guards the roster has on the walls today and on their watch now (the first
 *     watch from dusk to midnight, the second from midnight to dawn: VillageFolkEntity.firstWatch) take them in the roster's
 *     order, so as one watch goes to bed the next takes the same posts.</li>
 * <li><b>The rounds.</b> A guard on a wall walk climbs the steps by its gate and walks its side end to end and back, at a
 *     watchman's pace, stopping every eight blocks or so to look out over the battlements. Where the walk cannot be got up
 *     to (half a minute's trying for each of three stops), it walks the street inside that side of the wall instead, for the
 *     night. A guard at a gate stands inside it by the passage, challenging whoever comes to it after dark. Nobody is ever
 *     put anywhere: they walk.</li>
 * <li><b>Lanterns.</b> Every guard on the night watch carries a lantern from the stores in its free hand (Beats.lantern; a
 *     torch from the stores when there is no lantern), its shield in its pack.</li>
 * <li><b>The bell.</b> A watchman who sees raiders, or six monsters or more, from the wall rings the town's bell (Raids: the
 *     gates shut, the muster); one who sees a monster or two calls it, rings its handbell, and the watch's hunt (WatchClears)
 *     goes after it. Each is written in the night's log.</li>
 * <li><b>Handed over.</b> At dusk the captain calls the first watch to its posts; as each guard takes a post from another,
 *     they pass a word (all quiet, or what was seen) and it is logged; at midnight the second watch takes the posts from the
 *     first; at dawn the watch stands down, the night's tally logged, and the gates open.</li>
 * </ul>
 * The rota (who keeps which post on which watch) and the night's log are on the board's Defences page.
 */
public final class NightWatch {

    private NightWatch() {}

    /** Columns between the stops on a wall walk. */
    static final int STEP = 8;
    /** Ticks a watchman stands at each stop, looking out. */
    static final int PAUSE = 80;
    /** Ticks it tries for a stop before giving it up. */
    static final int TRY = 600;
    /** How far a watchman sees trouble from the wall. */
    static final double SIGHT = 22.0;
    /** Monsters seen at once that ring the bell (raiders ring it at one). */
    static final int BELL_AT = 6;
    static final String LOG = "watch.nightlog";

    /** A post of the night watch: a side's wall walk, or a gate. */
    public record Post(String key, boolean wall, int side, int gate, String name) {}

    private static final class Round {
        String key = "";
        List<BlockPos> route = List.of();
        boolean street;
        int at, dir = 1, skips;
        long sent, arrived = -1, lastTick;
        UUID village;
    }

    private static final Map<UUID, Round> ROUNDS = new ConcurrentHashMap<>();
    /** Who holds each post now, by town and post key. */
    private static final Map<UUID, Map<String, UUID>> HOLDERS = new ConcurrentHashMap<>();
    /** Monsters already called tonight, by town. */
    private static final Map<UUID, Set<UUID>> CALLED = new ConcurrentHashMap<>();
    /** The night's tally, by town: {sightings, bells}. */
    private static final Map<UUID, int[]> TALLY = new ConcurrentHashMap<>();
    /** The last phase seen by the tick, by town (0 day, 1 first watch, 2 second watch). */
    private static final Map<UUID, Integer> PHASE = new ConcurrentHashMap<>();
    /** Players challenged at a gate lately. */
    private static final Map<UUID, Long> CHALLENGED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        ROUNDS.clear();
        HOLDERS.clear();
        CALLED.clear();
        TALLY.clear();
        PHASE.clear();
        CHALLENGED.clear();
    }

    // ------------------------------------------------------------------ the posts

    /** The night's posts, in the order they are taken: a side's wall walk, then a gate, round the wall. */
    public static List<Post> posts(ServerLevel level, UUID id) {
        List<Post> out = new ArrayList<>();
        List<CastleWall.Gate> gates = CastleWall.gates(level, id);
        String[] sideNames = { "north", "east", "south", "west" };
        for (int side = 0; side < 4; side++) {
            out.add(new Post("wall" + side, true, side, -1, "the " + sideNames[side] + " wall walk"));
            for (int g = 0; g < gates.size(); g++) {
                if (sideOf(gates.get(g).out()) != side) continue;
                out.add(new Post("gate" + g, false, side, g, "the " + gates.get(g).out().getName() + " gate"));
            }
        }
        return out;
    }

    static int sideOf(Direction out) {
        return switch (out) {
            case EAST -> TownPlan.EAST;
            case SOUTH -> TownPlan.SOUTH;
            case WEST -> TownPlan.WEST;
            default -> TownPlan.NORTH;
        };
    }

    /** Which watch it is: 0 the day, 1 the first watch (dusk to midnight), 2 the second (midnight to dawn). */
    static int phase(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        if (t >= 12500L && t < 18000L) return 1;
        if (t >= 18000L && t < 23200L) return 2;
        return 0;
    }

    /** The guards on the walls today who are on their watch now, in the roster's order. */
    static List<VillageFolkEntity> onNow(ServerLevel level, Villages.Village v) {
        List<VillageFolkEntity> out = new ArrayList<>();
        int ph = phase(level.getDayTime());
        for (VillageFolkEntity g : Roster.on(level, v, Roster.Duty.WALLS)) {
            if (!g.isAlive() || g.isSleeping()) continue;
            if (ph == 1 && !g.firstWatch() && Roster.on(level, v, Roster.Duty.WALLS).size() > 1) continue;
            if (ph == 2 && g.firstWatch() && Roster.on(level, v, Roster.Duty.WALLS).size() > 1) continue;
            out.add(g);
        }
        return out;
    }

    /** Does the night watch keep this town's wall (its works begun)? */
    static boolean keeps(UUID id) {
        return CastleWall.begun(id);
    }

    // ------------------------------------------------------------------ the guard's night (Beats.walls)

    /** A guard between the bells (Raids.guardDuty): on the walls today and its watch on, the night watch's post. */
    static boolean guard(VillageFolkEntity g) {
        if (!Police.active() || !(g.level() instanceof ServerLevel level) || g.ownerId() == null) return false;
        if (phase(level.getDayTime()) == 0 || !keeps(g.ownerId())) return false;
        if (Roster.dutyOf(level, g) != Roster.Duty.WALLS) return false;
        Villages.Village v = Villages.get(g.ownerId());
        return v != null && walls(g, level, v);
    }

    /** A guard of the walls by night, on a town wall (Beats.walls): its post, its round, its lantern. False to leave it
     *  to the old posts (no town wall begun). */
    static boolean walls(VillageFolkEntity g, ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (!keeps(id) || phase(level.getDayTime()) == 0) return false;
        List<Post> posts = posts(level, id);
        if (posts.isEmpty()) return false;
        List<VillageFolkEntity> on = onNow(level, v);
        int k = on.indexOf(g);
        if (k < 0) k = on.size();
        Post post = posts.get(k % posts.size());
        lit(level, v, g);
        Round r = ROUNDS.computeIfAbsent(g.getUUID(), u -> new Round());
        long now = level.getGameTime();
        r.lastTick = now;
        r.village = id;
        if (!post.key().equals(r.key)) {
            r.key = post.key();
            r.street = false;
            r.at = 0;
            r.dir = 1;
            r.skips = 0;
            r.arrived = -1;
            r.sent = now;
            r.route = route(level, v, post, false);
            if (!post.wall() && r.route.isEmpty()) return false;
            r.at = nearest(r.route, g.blockPosition());                    // its round begun from the nearest stop
            takeOver(level, v, g, post);
        }
        if (r.route.isEmpty()) {
            r.route = route(level, v, post, r.street);
            if (r.route.isEmpty() && !r.street) { r.street = true; r.route = route(level, v, post, true); }
            if (r.route.isEmpty()) return false;
        }
        r.at = Math.max(0, Math.min(r.at, r.route.size() - 1));
        BlockPos target = r.route.get(r.at);
        Direction outD = CastleWall.outward(post.side());
        double dx = g.getX() - (target.getX() + 0.5), dz = g.getZ() - (target.getZ() + 0.5), dy = g.getY() - target.getY();
        boolean there = dx * dx + dz * dz < 2.6 && Math.abs(dy) < 1.6;
        if (there) {
            if (r.arrived < 0) r.arrived = now;
            g.getNavigation().stop();
            BlockPos look = target.relative(outD, 10);
            if (g.tickCount % 20 == 0) g.getLookControl().setLookAt(look.getX() + 0.5, look.getY() + 1.0, look.getZ() + 0.5);
            r.skips = 0;
            if (!post.wall()) {
                g.brain("on the night watch at " + post.name());
                challenge(level, v, g);
                return true;
            }
            if (now - r.arrived >= PAUSE) {
                if (r.route.size() > 1) {
                    if (r.at + r.dir < 0 || r.at + r.dir >= r.route.size()) r.dir = -r.dir;
                    r.at += r.dir;
                }
                r.arrived = -1;
                r.sent = now;
            }
            g.brain("on the night watch: " + post.name() + (r.street ? " (from the street)" : ""));
            return true;
        }
        r.arrived = -1;
        if (now - r.sent > TRY) {
            // Half a minute and not there: the next stop; three gone that way, the street inside the wall for tonight.
            r.skips++;
            r.sent = now;
            if (post.wall() && !r.street && r.skips >= 3) {
                r.street = true;
                r.route = route(level, v, post, true);
                r.at = nearest(r.route, g.blockPosition());
                r.skips = 0;
                log(level, id, g.displayNameCap() + " could not get up onto " + post.name() + " and walked the street inside it instead");
                if (r.route.isEmpty()) return false;
            } else if (r.route.size() > 1) {
                if (r.at + r.dir < 0 || r.at + r.dir >= r.route.size()) r.dir = -r.dir;
                r.at += r.dir;
            }
            return true;
        }
        Incidents.walk(g, r.route.get(r.at), post.wall() ? 0.6D : 0.8D);
        g.brain("to " + post.name() + " for the night watch");
        return true;
    }

    static int nearest(List<BlockPos> stops, BlockPos from) {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < stops.size(); i++) {
            double d = stops.get(i).distSqr(from);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    /** The stops of a post's round: a side's wall walk (or the street inside it), or the spot inside a gate. */
    static List<BlockPos> route(ServerLevel level, Villages.Village v, Post post, boolean street) {
        if (!post.wall()) {
            List<CastleWall.Gate> gates = CastleWall.gates(level, v.id());
            if (post.gate() < 0 || post.gate() >= gates.size()) return List.of();
            CastleWall.Gate gate = gates.get(post.gate());
            BlockPos spot = gate.inside().relative(CastleWall.alongward(post.side()), WallPlan.GATE + 1);
            return List.of(spot);
        }
        List<BlockPos> stops = street ? CastleWall.wallStreet(level, v.id(), post.side(), STEP) : CastleWall.walkway(level, v.id(), post.side(), STEP);
        return stops.size() >= 2 ? stops : List.of();
    }

    /** A lantern in its hand for the night (Beats.lantern), from the stores; a torch out of them if there is no lantern. */
    static void lit(ServerLevel level, Villages.Village v, VillageFolkEntity g) {
        if (NightLight.isLight(g.getItemBySlot(EquipmentSlot.OFFHAND))) return;
        if (g.countCarried(NightLight::isLight) == 0 && Market.stock(level, v.id(), s -> s.is(Items.LANTERN)) == 0) {
            ItemStack torch = Crafts.takeOne(level, v, s -> s.is(Items.TORCH));
            if (!torch.isEmpty()) {
                ItemStack left = g.insertItem(torch);
                if (!left.isEmpty()) Crafts.store(level, v, left);
            }
        }
        Beats.lantern(level, v, g, true);
    }

    /** A guard takes a post: from whoever held it, with a word between them if they are together; logged. */
    static void takeOver(ServerLevel level, Villages.Village v, VillageFolkEntity g, Post post) {
        UUID id = v.id();
        Map<String, UUID> holders = HOLDERS.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        UUID was = holders.put(post.key(), g.getUUID());
        if (g.getUUID().equals(was)) return;
        int ph = phase(level.getDayTime());
        String when = ph == 2 ? "Midnight" : "Dusk";
        VillageFolkEntity old = was != null && level.getEntity(was) instanceof VillageFolkEntity o && o.isAlive() ? o : null;
        int[] tally = TALLY.computeIfAbsent(id, k -> new int[2]);
        if (old != null && old.distanceToSqr(g) < 16 * 16) {
            String report = tally[0] == 0 ? "All quiet on " + post.name() + "." : tally[0] + " seen tonight; keep your eyes open.";
            FolkTalk.speak(old, report + " It's yours, " + g.displayNameCap() + ".");
            FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "I have it. Get some sleep.", "Right. Off you go.", "I've got the watch."));
            log(level, id, when + ": " + old.displayNameCap() + " handed " + post.name() + " to " + g.displayNameCap());
        } else {
            log(level, id, when + ": " + g.displayNameCap() + " took " + post.name() + (was == null ? "" : level.getEntity(was) instanceof VillageFolkEntity o2 ? " from " + o2.displayNameCap() : ""));
        }
    }

    /** A stranger at the gate after dark: challenged, once a minute. */
    static void challenge(ServerLevel level, Villages.Village v, VillageFolkEntity g) {
        if (g.tickCount % 20 != 0) return;
        long now = level.getGameTime();
        for (Player p : level.getEntitiesOfClass(Player.class, g.getBoundingBox().inflate(7.0, 3.0, 7.0))) {
            Long last = CHALLENGED.get(p.getUUID());
            if (last != null && now - last < 1200L && now >= last) continue;
            CHALLENGED.put(p.getUUID(), now);
            FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Halt! Who goes there? ... Ah, it's you. Pass, friend.",
                "The gate's shut for the night. State your business at " + Villages.name(v.id()) + ".", "Who's that with no lantern? Oh, you. Mind how you go."));
            g.getLookControl().setLookAt(p, 30.0F, 30.0F);
            return;
        }
    }

    // ------------------------------------------------------------------ the look out (every second, CastleWall.keep)

    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (!keeps(id)) return;
        long dayTime = level.getDayTime();
        int ph = phase(dayTime);
        Integer was = PHASE.put(id, ph);
        if (was != null && was != ph) changed(level, v, was, ph);
        if (ph == 0) return;
        long now = level.getGameTime();
        if (Raids.underAlarm(id)) return;
        // What the watchmen on the wall see.
        Set<UUID> called = CALLED.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
        for (Map.Entry<UUID, Round> e : ROUNDS.entrySet()) {
            Round r = e.getValue();
            if (!id.equals(r.village) || now - r.lastTick > 100L) continue;
            if (!(level.getEntity(e.getKey()) instanceof VillageFolkEntity g) || !g.isAlive()) continue;
            List<Mob> seen = new ArrayList<>();
            boolean raiders = false;
            for (Mob m : level.getEntitiesOfClass(Mob.class, g.getBoundingBox().inflate(SIGHT, 8.0, SIGHT), x -> x.isAlive() && Raids.hostile(x))) {
                if (!g.hasLineOfSight(m)) continue;
                seen.add(m);
                if (m instanceof Raider || m.getTags().contains("mca_raider")) raiders = true;
            }
            if (seen.isEmpty()) continue;
            Direction from = Direction.getNearest(seen.get(0).getX() - v.centre().getX(), 0, seen.get(0).getZ() - v.centre().getZ());
            String what = kinds(seen) + " by the " + from.getName() + " wall";
            if (raiders || seen.size() >= BELL_AT) {
                FolkTalk.speak(g, raiders ? "Raiders! Raiders at the " + from.getName() + " wall! Ring the bell!" : "To arms! " + capital(what) + "!");
                if (Raids.warAlarm(level, v, (raiders ? "raiders seen" : "monsters seen") + " by the watch at the " + from.getName() + " wall", from, 400L)) {
                    TALLY.computeIfAbsent(id, k -> new int[2])[1]++;
                    log(level, id, g.displayNameCap() + " saw " + what + " and rang the bell");
                }
                for (Mob m : seen) called.add(m.getUUID());
                TALLY.computeIfAbsent(id, k -> new int[2])[0] += seen.size();
                return;
            }
            List<Mob> fresh = new ArrayList<>();
            for (Mob m : seen) if (called.add(m.getUUID())) fresh.add(m);
            if (fresh.isEmpty()) continue;
            TALLY.computeIfAbsent(id, k -> new int[2])[0] += fresh.size();
            String said = kinds(fresh) + " by the " + from.getName() + " wall";
            FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), capital(said) + "! Watch, to me!", "Look sharp — " + said + "!", capital(said) + "! I see it!"));
            level.playSound(null, g.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 0.5F, 1.6F);   // its handbell
            log(level, id, g.displayNameCap() + " saw " + said + " and called it");
        }
        // The hours called on the wall walk: midnight, and all's well.
        long t = Math.floorMod(dayTime, 24000L);
        if (t >= 18000L && t < 18020L) {
            for (Map.Entry<UUID, Round> e : ROUNDS.entrySet()) {
                if (!id.equals(e.getValue().village) || now - e.getValue().lastTick > 100L) continue;
                if (level.getEntity(e.getKey()) instanceof VillageFolkEntity g && g.isAlive() && !g.isSleeping()) {
                    FolkTalk.speak(g, "Midnight, and all's well!");
                }
            }
        }
    }

    /** The watch changes: the first watch called at dusk, the posts handed over at midnight, the stand-down at dawn. */
    static void changed(ServerLevel level, Villages.Village v, int was, int now) {
        UUID id = v.id();
        if (now == 1) {
            TALLY.put(id, new int[2]);
            CALLED.remove(id);
            List<VillageFolkEntity> on = onNow(level, v);
            List<Post> posts = posts(level, id);
            List<String> who = new ArrayList<>();
            for (int i = 0; i < on.size() && i < posts.size(); i++) who.add(on.get(i).displayNameCap() + " " + posts.get(i).name());
            VillageFolkEntity captain = Roster.captain(id);
            if (captain != null && captain.isAlive() && !captain.isSleeping()) {
                FolkTalk.speak(captain, who.isEmpty() ? "Dusk, and nobody for the walls tonight. Bar the gates." : "First watch to your posts: " + String.join("; ", who) + ". Lanterns lit.");
            }
            log(level, id, "Dusk: the gates shut and the first watch went on" + (who.isEmpty() ? " (nobody on the walls tonight)" : ": " + String.join("; ", who)));
        } else if (now == 0 && was != 0) {
            int[] tally = TALLY.getOrDefault(id, new int[2]);
            log(level, id, "Dawn: the night watch stood down and the gates opened; " + (tally[0] == 0 ? "a quiet night" : tally[0] + " monster" + (tally[0] == 1 ? "" : "s")
                + " seen") + (tally[1] > 0 ? ", the bell rung " + tally[1] + (tally[1] == 1 ? " time" : " times") : ""));
            HOLDERS.remove(id);
            for (Round r : ROUNDS.values()) if (id.equals(r.village)) r.key = "";
        }
    }

    private static String kinds(List<Mob> mobs) {
        Map<String, Integer> n = new HashMap<>();
        for (Mob m : mobs) n.merge(m.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : n.entrySet()) parts.add(e.getValue() == 1 ? "a " + e.getKey() : e.getValue() + " " + e.getKey() + "s");
        return String.join(" and ", parts);
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the night's log

    static void log(ServerLevel level, UUID id, String line) {
        long day = level.getDayTime() / 24000L;
        Deque<String> d = new ArrayDeque<>(logOf(id));
        d.addLast("Day " + (day + 1) + " · " + line.replace('\n', ' '));
        while (d.size() > 16) d.pollFirst();
        Ledger.note(id, LOG, String.join("\n", d));
    }

    public static List<String> logOf(UUID id) {
        String s = Ledger.note(id, LOG);
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String l : s.split("\n")) if (!l.isEmpty()) out.add(l);
        return out;
    }

    // ------------------------------------------------------------------ the rota (the board)

    /** The night's rota: each post, who keeps it on the first and the second watch, who is on it now and what doing. */
    public static ListTag rota(ServerLevel level, Villages.Village v) {
        ListTag out = new ListTag();
        UUID id = v.id();
        List<Post> posts = posts(level, id);
        List<VillageFolkEntity> walls = Roster.on(level, v, Roster.Duty.WALLS);
        List<VillageFolkEntity> first = new ArrayList<>(), second = new ArrayList<>();
        for (VillageFolkEntity g : walls) {
            if (walls.size() == 1) { first.add(g); second.add(g); }
            else if (g.firstWatch()) first.add(g); else second.add(g);
        }
        Map<String, UUID> holders = HOLDERS.getOrDefault(id, Map.of());
        for (int i = 0; i < posts.size(); i++) {
            Post p = posts.get(i);
            CompoundTag c = new CompoundTag();
            c.putString("post", p.name());
            c.putBoolean("wall", p.wall());
            c.putString("first", i < first.size() ? first.get(i).displayNameCap() : "");
            c.putString("second", i < second.size() ? second.get(i).displayNameCap() : "");
            UUID h = holders.get(p.key());
            if (h != null && level.getEntity(h) instanceof VillageFolkEntity g && g.isAlive()) {
                c.putString("now", g.displayNameCap());
                Round r = ROUNDS.get(h);
                c.putBoolean("lantern", NightLight.isLight(g.getItemBySlot(EquipmentSlot.OFFHAND)));
                c.putString("doing", r == null ? "" : r.street ? "walking the street inside the wall" : p.wall() ? "on the wall walk, stop " + (r.at + 1) + " of " + r.route.size() : "at the gate");
            }
            BlockPos at = null;
            List<BlockPos> stops = route(level, v, p, false);
            if (!stops.isEmpty()) at = stops.get(stops.size() / 2);
            if (at != null) c.putIntArray("at", new int[]{ at.getX(), at.getY(), at.getZ() });
            out.add(c);
        }
        return out;
    }

    /** The town's watch in figures and words for the Defences page. */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        CompoundTag c = new CompoundTag();
        UUID id = v.id();
        c.putBoolean("keeps", keeps(id));
        c.putInt("phase", phase(level.getDayTime()));
        c.put("rota", rota(level, v));
        ListTag beats = new ListTag();
        for (String s : Beats.describe(level, v)) beats.add(StringTag.valueOf(s));
        c.put("beats", beats);
        ListTag log = new ListTag();
        List<String> l = logOf(id);
        for (int i = l.size() - 1; i >= 0; i--) log.add(StringTag.valueOf(l.get(i)));
        c.put("log", log);
        int[] tally = TALLY.getOrDefault(id, new int[2]);
        c.putInt("seen", tally[0]);
        c.putInt("bells", tally[1]);
        int walls = Roster.on(level, v, Roster.Duty.WALLS).size();
        c.putInt("walls", walls);
        c.putInt("watch", Patrols.watch(id).size());
        return c;
    }

    // ------------------------------------------------------------------ the tests

    /** Tests: the stop this guard is making for (or standing at), and its round's stops. */
    @Nullable
    public static BlockPos stopForTests(VillageFolkEntity g) {
        Round r = ROUNDS.get(g.getUUID());
        return r == null || r.route.isEmpty() ? null : r.route.get(Math.max(0, Math.min(r.at, r.route.size() - 1)));
    }

    public static List<BlockPos> routeForTests(VillageFolkEntity g) {
        Round r = ROUNDS.get(g.getUUID());
        return r == null ? List.of() : List.copyOf(r.route);
    }

    public static boolean streetForTests(VillageFolkEntity g) {
        Round r = ROUNDS.get(g.getUUID());
        return r != null && r.street;
    }

    public static void tickForTests(ServerLevel level, Villages.Village v) {
        tick(level, v);
    }

    public static Set<String> heldForTests(UUID id) {
        return new HashSet<>(HOLDERS.getOrDefault(id, Map.of()).keySet());
    }
}
