package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [watch-clears] The town cleared of its monsters, by night and by day, and its folk kept out of their way.
 *
 * <p>The hundred days' plains town grew to a hundred folk and the Diamond Age by day sixty, and was down to
 * seventy-two by day a hundred. Its graves told how: ninety went "when the raiders came", every one of them on
 * the night of the town's own raiding band (every fifth night, three after the long game's dusk monsters), five
 * to eight a night once the band was pillagers and vindicators. The band was set down thirty-one blocks from the
 * heart, which in a town of ninety is among its houses; the bell sent every guard up onto the wall round the
 * square or into its gates, and their pathfinding shut the gates behind them, so the streets where the folk live
 * had no watch at all while the band went through them; a folk set upon fought back with its fists and every
 * folk within earshot came running to help it; and the long game's own monsters, which no vanilla monster's
 * eyes turn on a folk, were left wherever they stood unless one wandered within sixteen blocks of somebody: a
 * creeper was only ever taken on by a guard of twenty years with a bow, and the count of them about the town
 * went from thirteen to thirty-two. Now:
 * <ul>
 * <li><b>The hunt.</b> Once a second every monster up about the town (inside its reach or a little past it, not
 *     one in a cave under it) is the watch's business, and the nearest free guard goes after it at a run: the
 *     ones on a folk first, then raiders, then the ones by a child, then by anybody, then the rest. A creeper
 *     goes to a guard with a bow (one from the stores if none has one: any guard of the watch may draw the
 *     town's bow when it is sent after a creeper), and a raider gets two guards at once. By day as by night: a
 *     monster that lived through the night in the shade, in the water or in a corner is hunted down in the
 *     morning. A guard that cannot get at its monster in three quarters of a minute gives it up, and is not sent
 *     after that one again for a while.</li>
 * <li><b>Under the bell.</b> A third of the watch keeps the wall's posts with its bows; the rest go out among
 *     the houses after whatever has come within twenty blocks of the town's folk, and through the gates to do
 *     it. The band comes at the town's edge now, not into its streets, and goes for a guard before a folk.</li>
 * <li><b>The golem.</b> The town's iron golem goes for the nearest monster about the town when it has nothing
 *     else to fight (not a creeper: no golem will).</li>
 * <li><b>Out of harm's way.</b> A folk who is not the watch, with a monster near (ten blocks; sixteen for a
 *     child; any that is after it), goes indoors, to its home if that is the nearer way and away from the
 *     monster, or the nearest of the town's buildings, and stays there till the monster has gone; a creeper in
 *     its yard keeps it from its work. Set upon, it runs for it rather than trading blows, unless it is
 *     cornered; and nobody but the watch answers a shout for help with a fight. With the bell ringing, a folk
 *     whose bed is across the town goes into the nearest building instead of walking the streets to it, the
 *     children too.</li>
 * <li><b>The books.</b> The monsters the watch and the golem kill are counted, a day at a time, and the folk
 *     lost to them written down with where they fell and what they were doing; the status says how many
 *     monsters are about the town now, the town's books the week's tally, and /village monsters all of it.</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class WatchClears {

    private WatchClears() {}

    /** How far past the town's reach the watch still hunts. */
    static final int EDGE = 16;
    /** With the bell ringing: a monster this near one of the town's folk draws the watch off the wall. */
    static final int NEAR_FOLK = 20;
    /** A guard that has not got at its monster in this long gives it up. */
    static final long GIVE_UP = 900L;
    /** ...and is not sent after that one again for this long. */
    static final long RETRY = 2400L;
    /** How near a monster has to be to send a grown folk indoors. */
    static final int NEAR = 10;
    /** And a child. */
    static final int NEAR_CHILD = 16;
    /** A creeper, or one that is after it. */
    static final int NEAR_CREEPER = 12;
    /** Nothing near for this long, and it comes out again. */
    static final long CLEAR = 60L;
    /** Under cover this long from a monster that is after nobody, a grown folk goes about its day all the same. */
    static final long BRAVE = 2400L;
    /** ...and pays that one no heed for this long. */
    static final long BRAVED_FOR = 12000L;
    /** Getting no nearer its cover in this long, it waits where it is. */
    static final long STALL = 300L;
    /** At its cover (the middle of the house) and under a roof, it is in: not stood in the doorway, where a storm
     *  is kept off (Weather) but a zombie is not. */
    static final double IN = 2.0;
    /** ...or this near, under a roof, with nowhere nearer it can walk (a table in the middle of the room). */
    static final double IN_NEAR = 4.5;

    // ------------------------------------------------------------------ the state

    /** A guard's hunt: after which monster, since when, its health then (to tell whether the guard has got at it). */
    private record Hunt(UUID monster, long since, float health, boolean creeper) {}

    /** By guard. */
    private static final Map<UUID, Hunt> HUNTS = new ConcurrentHashMap<>();
    /** By monster: the guards that gave it up, and till when. */
    private static final Map<UUID, Map<UUID, Long>> GAVE_UP = new ConcurrentHashMap<>();
    /** By guard: when it last said anything of the hunt's. */
    private static final Map<UUID, Long> SAID = new ConcurrentHashMap<>();
    /** By guard: when it was last armed out of the stores for a creeper. */
    private static final Map<UUID, Long> ARMED = new ConcurrentHashMap<>();

    /** A folk under cover: where it is going, from what, and how it is getting on. */
    private static final class Cover {
        final BlockPos to;
        UUID from;
        final long since;
        long lastNear, progress, walkTickAt;
        int walkTick = -1000;
        double best = Double.MAX_VALUE;
        boolean settled;

        Cover(BlockPos to, UUID from, long now) {
            this.to = to;
            this.from = from;
            this.since = now;
            this.lastNear = now;
            this.progress = now;
        }
    }

    private static final Map<UUID, Cover> COVER = new ConcurrentHashMap<>();
    /** By folk: the monster it has stopped minding, and till when. */
    private static final Map<UUID, Map<UUID, Long>> BRAVED = new ConcurrentHashMap<>();

    /** A day's tally: monsters killed by the watch, by the golem and otherwise; folk lost to them. */
    private static final class Tally {
        final long day;
        int watch, golem, other, lost;

        Tally(long day) { this.day = day; }
    }

    /** By village, the last eight days. */
    private static final Map<UUID, Deque<Tally>> TALLY = new ConcurrentHashMap<>();

    /** A folk lost: when, who, how, where and doing what. */
    public record Fallen(long day, String name, String trade, String how, String where, String doing) {
        String line() {
            return name + " the " + trade + " (day " + (day + 1) + "), " + where + ", " + doing + ", " + how;
        }
    }

    /** By village, the last twelve. */
    private static final Map<UUID, Deque<Fallen>> FALLEN = new ConcurrentHashMap<>();

    public static void resetForTests() {
        HUNTS.clear();
        GAVE_UP.clear();
        SAID.clear();
        ARMED.clear();
        COVER.clear();
        BRAVED.clear();
        TALLY.clear();
        FALLEN.clear();
    }

    // ------------------------------------------------------------------ what is about

    /** A monster the watch goes after, alive or (for the tally) just dead: what Patrols.hostile takes, without the
     *  look at whether it is still alive. */
    static boolean kind(Entity e) {
        if (!(e instanceof Mob) || !(e instanceof Enemy)) return false;
        return !(e instanceof net.minecraft.world.entity.monster.EnderMan)
            && !(e instanceof net.minecraft.world.entity.monster.ZombifiedPiglin)
            && !(e instanceof net.minecraft.world.entity.monster.Phantom)
            && !(e instanceof net.minecraft.world.entity.monster.Ghast)
            && !(e instanceof net.minecraft.world.entity.monster.Ravager)
            && !(e instanceof net.minecraft.world.entity.monster.warden.Warden);
    }

    static boolean monster(Entity e) {
        return kind(e) && e.isAlive();
    }

    /** Up where the folk are: out in the open, under a roof or a tree, or about the heart's height; not in a cave
     *  under the town, where it is no danger to anybody and cannot be got at. */
    static boolean upAbout(ServerLevel level, BlockPos heart, Mob m) {
        BlockPos p = m.blockPosition();
        if (p.getY() >= heart.getY() - 8 || level.canSeeSky(p)) return true;
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        return p.getY() >= ground - 8;
    }

    /** The town's ground for the watch: its reach and a little past it. */
    static AABB townBox(Villages.Village v, int past) {
        BlockPos c = v.centre();
        int r = Villages.townReach(v.id()) + past;
        return new AABB(c.getX() - r, c.getY() - 48, c.getZ() - r, c.getX() + r + 1, c.getY() + 48, c.getZ() + r + 1);
    }

    /** Every monster up about the town now. */
    static List<Mob> about(ServerLevel level, Villages.Village v) {
        BlockPos c = v.centre();
        return level.getEntitiesOfClass(Mob.class, townBox(v, EDGE), m -> monster(m) && upAbout(level, c, m));
    }

    /** "2 zombies, 1 creeper", most first. */
    static String kinds(List<Mob> mobs) {
        Map<String, Integer> n = new LinkedHashMap<>();
        for (Mob m : mobs) n.merge(name(m), 1, Integer::sum);
        List<Map.Entry<String, Integer>> all = new ArrayList<>(n.entrySet());
        all.sort((a, b) -> b.getValue() - a.getValue());
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : all) out.add(e.getValue() + " " + e.getKey() + (e.getValue() == 1 ? "" : "s"));
        return String.join(", ", out);
    }

    /** "zombie", "cave spider". */
    static String name(Entity m) {
        return m.getType().getDescription().getString().toLowerCase(Locale.ROOT);
    }

    static String a(String what) {
        return (!what.isEmpty() && "aeiou".indexOf(what.charAt(0)) >= 0 ? "an " : "a ") + what;
    }

    // ------------------------------------------------------------------ the hunt

    /** Is this guard after a monster the hunt sent it after? */
    public static boolean hunting(AssistantEntity g) {
        Hunt h = HUNTS.get(g.getUUID());
        if (h == null) return false;
        LivingEntity t = g.getTarget();
        return t != null && t.isAlive() && t.getUUID().equals(h.monster());
    }

    /** May this guard draw the bow it carries? One sent after a creeper may, whatever its years: the town's bow,
     *  and the only safe way to take a creeper. */
    public static boolean mayDraw(AssistantEntity g) {
        Hunt h = HUNTS.get(g.getUUID());
        return h != null && h.creeper() && hunting(g);
    }

    /** How many of the watch are after this monster just now. */
    static int hunters(List<VillageFolkEntity> watch, Mob m) {
        int n = 0;
        for (VillageFolkEntity g : watch) if (g.getTarget() == m) n++;
        return n;
    }

    /** Free to be sent: up, about the town, not already fighting, not hurt, not with somebody. */
    static boolean free(VillageFolkEntity g, BlockPos c, int reach, boolean bell) {
        if (!g.isAlive() || g.isBaby() || Patrols.away(g) || g.isRetreating()) return false;
        if (g.isSleeping() && !bell) return false;                         // the bell wakes the whole watch
        LivingEntity t = g.getTarget();
        if (t != null && t.isAlive()) return false;
        if (!bell && Patrols.escorting(g)) return false;                   // the leader's, while it walks
        if (g.talkPartner() != null || g.companionPlayer() != null || g.guidePlayer() != null) return false;
        if (g.getHealth() < g.getMaxHealth() * 0.5F || g.shouldDisengage()) return false;
        return Math.max(Math.abs(g.getX() - c.getX()), Math.abs(g.getZ() - c.getZ())) <= reach + Patrols.ABROAD;
    }

    /** Can this guard take that on: a creeper only with a bow and arrows, and never one it has given up on. */
    static boolean canTake(VillageFolkEntity g, Mob m, long now) {
        Map<UUID, Long> gave = GAVE_UP.get(m.getUUID());
        if (gave != null) {
            Long until = gave.get(g.getUUID());
            if (until != null && now < until) return false;
        }
        if (!(m instanceof Creeper)) return true;
        return g.combatStance() != AssistantEntity.Stance.MELEE && archer(g);
    }

    static boolean archer(VillageFolkEntity g) {
        return g.countCarried(s -> s.is(Items.BOW) || s.is(Items.CROSSBOW)) > 0 && g.hasArrows();
    }

    /** What one monster is to the watch just now. */
    private record Quarry(Mob m, double folk, boolean child, boolean after, boolean raider, boolean yard) {
        /** Lower first. */
        double rank() {
            return (after ? 0 : 1000) + (raider ? 0 : 400) + (child ? 0 : 200) + (yard ? 0 : 100) + Math.min(folk, 96.0);
        }
    }

    /**
     * The watch's look round the town, once a second (Raids, beside Patrols): every monster about it is the
     * watch's business, and the golem's.
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        if (!level.isLoaded(v.centre())) return;
        UUID id = v.id();
        long now = level.getGameTime();
        List<VillageFolkEntity> watch = Patrols.watch(id);
        prune(level, watch, now);
        List<Mob> about = about(level, v);
        if (about.isEmpty()) return;
        boolean bell = Raids.underAlarm(id);
        BlockPos c = v.centre();
        int reach = Villages.townReach(id);
        // With the bell ringing, a third of the watch keeps the wall's posts (the first of it, in the watch's own
        // order, that has a bow and a post to go to); the rest are the hunt's.
        int wall = bell ? (watch.size() + 2) / 3 : 0;
        List<VillageFolkEntity> free = new ArrayList<>();
        for (int i = 0; i < watch.size(); i++) {
            VillageFolkEntity g = watch.get(i);
            if (i < wall && Raids.headingForPost(g)) continue;
            if (free(g, c, reach, bell)) free.add(g);
        }
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && f.isAlive() && f.stationTask() != StationTask.GUARD && !Patrols.away(f)) folk.add(f);
        }
        List<Quarry> quarry = new ArrayList<>();
        for (Mob m : about) {
            double near = Double.MAX_VALUE;
            boolean child = false, yard = false;
            for (VillageFolkEntity f : folk) {
                if (Math.abs(f.getY() - m.getY()) > 8) continue;
                double dx = f.getX() - m.getX(), dz = f.getZ() - m.getZ();
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < near) near = d;
                if (f.isBaby() && d <= NEAR_CHILD) child = true;
                if (m instanceof Creeper && !yard && f.workZone() != null && f.workZone().containsColumn(m.blockPosition())) yard = true;
            }
            LivingEntity t = m.getTarget();
            boolean after = t instanceof AssistantEntity a && id.equals(a.ownerId());
            // With the bell ringing, the hunt is for what is among the folk: what is still out on the town's edge
            // is the wall's, and comes to it.
            if (bell && !after && near > NEAR_FOLK) continue;
            quarry.add(new Quarry(m, near, child, after, m instanceof Raider, yard));
        }
        quarry.sort(Comparator.comparingDouble(Quarry::rank));
        // One guard to each, in order; then a second to each raider (a vindicator's axe is more than one guard's
        // match in iron).
        for (int pass = 0; pass < 2 && !free.isEmpty(); pass++) {
            for (Quarry q : quarry) {
                if (free.isEmpty()) break;
                if (pass == 1 && !q.raider()) continue;
                if (hunters(watch, q.m()) > pass) continue;
                VillageFolkEntity g = pick(level, v, free, q.m(), now);
                if (g == null) continue;
                send(g, q.m(), q.folk(), now);
                free.remove(g);
            }
        }
        golems(level, v, about);
    }

    /** The nearest free guard who can take it on; for a creeper with none to hand, the nearest armed out of the stores. */
    @Nullable
    static VillageFolkEntity pick(ServerLevel level, Villages.Village v, List<VillageFolkEntity> free, Mob m, long now) {
        VillageFolkEntity best = null, nearest = null;
        double bd = Double.MAX_VALUE, nd = Double.MAX_VALUE;
        for (VillageFolkEntity g : free) {
            double d = g.distanceToSqr(m);
            if (canTake(g, m, now)) {
                if (d < bd) { bd = d; best = g; }
            } else if (m instanceof Creeper && d < nd && g.combatStance() != AssistantEntity.Stance.MELEE) {
                Map<UUID, Long> gave = GAVE_UP.get(m.getUUID());
                Long until = gave == null ? null : gave.get(g.getUUID());
                if (until == null || now >= until) { nd = d; nearest = g; }
            }
        }
        if (best != null || nearest == null) return best;
        // A bow and arrows out of the stores, if they hold them (not every second: once a minute a guard).
        Long armed = ARMED.get(nearest.getUUID());
        if (armed != null && now - armed < 1200L && now >= armed) return null;
        ARMED.put(nearest.getUUID(), now);
        Raids.arm(level, v, nearest);
        return archer(nearest) ? nearest : null;
    }

    /** A guard sent after a monster, at a run. */
    static void send(VillageFolkEntity g, Mob m, double folkNear, long now) {
        if (g.isSleeping()) g.stopSleeping();
        if (g.peekJob() != null) g.clearQueue();
        if (g.post() != null) Raids.leavePost(g);                       // down off the wall
        boolean creeper = m instanceof Creeper;
        HUNTS.put(g.getUUID(), new Hunt(m.getUUID(), now, m.getHealth(), creeper));
        g.setTarget(m);
        if (creeper) g.equipBow();                                      // the bow out now, not at the next look at its weapons
        else g.equipBestWeapon();
        g.getNavigation().moveTo(m, 1.25D);
        String what = name(m);
        g.brain("hunting " + a(what));
        Long last = SAID.get(g.getUUID());
        if (last != null && now - last < 400L && now >= last) return;
        SAID.put(g.getUUID(), now);
        var r = g.getRandom();
        FolkTalk.speak(g, folkNear <= 16.0
            ? FolkTalk.pick(r, "Everybody in! There's " + a(what) + " about.", "Stand back — I'll see to that " + what + ".",
                "Indoors, all of you! Leave the " + what + " to me.")
            : FolkTalk.pick(r, "There's " + a(what) + " loose in the town. I'm on it.", "That " + what + " isn't stopping here.",
                "One more for the watch: " + a(what) + "."));
    }

    /**
     * Hunts that are over: the monster dead, or the guard on to something else; a guard that has not got at its
     * monster in three quarters of a minute gives it up (on a roof, across the water, in a hole), and another is
     * sent in its place.
     */
    static void prune(ServerLevel level, List<VillageFolkEntity> watch, long now) {
        for (VillageFolkEntity g : watch) {
            Hunt h = HUNTS.get(g.getUUID());
            if (h == null) continue;
            Entity e = level.getEntity(h.monster());
            LivingEntity t = g.getTarget();
            if (!(e instanceof Mob m) || !m.isAlive() || t != m || !g.isAlive()) {
                HUNTS.remove(g.getUUID());
                continue;
            }
            boolean hurt = m.getHealth() < h.health() - 0.5F;
            if (now - h.since() > GIVE_UP && !hurt && g.distanceToSqr(m) > 3.5 * 3.5) {
                HUNTS.remove(g.getUUID());
                g.setTarget(null);
                g.getNavigation().stop();
                GAVE_UP.computeIfAbsent(m.getUUID(), k -> new ConcurrentHashMap<>()).put(g.getUUID(), now + RETRY);
                g.brain("gave up on " + a(name(m)) + " it could not get at");
            } else if (hurt) {
                HUNTS.put(g.getUUID(), new Hunt(h.monster(), now, m.getHealth(), h.creeper()));   // getting at it: the clock starts again
            }
        }
        if (GAVE_UP.size() > 256) GAVE_UP.values().removeIf(x -> { x.values().removeIf(u -> u < now); return x.isEmpty(); });
        // Hunts of guards that are no longer the watch's (another trade, another town, dead) are let go of in time.
        if (HUNTS.size() > 256) HUNTS.values().removeIf(x -> now - x.since() > 24000L || now < x.since());
        if (SAID.size() > 256) SAID.values().removeIf(x -> now - x > 24000L || now < x);
        if (ARMED.size() > 256) ARMED.values().removeIf(x -> now - x > 24000L || now < x);
    }

    /** The town's golem, with nothing to fight, goes for the nearest monster about the town (no golem fights a creeper). */
    static void golems(ServerLevel level, Villages.Village v, List<Mob> about) {
        for (IronGolem golem : level.getEntitiesOfClass(IronGolem.class, townBox(v, 0), LivingEntity::isAlive)) {
            LivingEntity t = golem.getTarget();
            if (t != null && t.isAlive()) continue;
            // Near it: a golem's legs find no way much past twenty blocks, and one sent across the town stands still.
            Mob best = null;
            double bd = 24.0 * 24.0;
            for (Mob m : about) {
                if (m instanceof Creeper || !m.isAlive()) continue;
                double d = golem.distanceToSqr(m);
                if (d < bd) { bd = d; best = m; }
            }
            if (best == null) continue;
            golem.setTarget(best);
            golem.getNavigation().moveTo(best, 1.0D);
        }
    }

    /**
     * Whom a raider of the band goes for (Raids.drive): a guard near it first, so the watch meets the band and not
     * the folk; else the nearest of the town's folk, as before.
     */
    @Nullable
    public static VillageFolkEntity preyFor(Villages.Village v, Entity from, double reach) {
        VillageFolkEntity guard = null;
        double gd = 16.0 * 16.0;
        for (VillageFolkEntity g : Patrols.watch(v.id())) {
            if (!g.isAlive() || Patrols.away(g)) continue;
            double d = g.distanceToSqr(from);
            if (d < gd) { gd = d; guard = g; }
        }
        return guard != null ? guard : Raids.nearestFolk(v, from, reach);
    }

    // ------------------------------------------------------------------ out of harm's way

    /** Is this folk under cover from a monster just now (its work waits: VillageFolkEntity.calledAway)? */
    public static boolean sheltering(AssistantEntity f) {
        return COVER.containsKey(f.getUUID());
    }

    /** Not its to hide from: the watch, a folk asleep, away, hired out or down the mine. */
    static boolean exempt(VillageFolkEntity f, ServerLevel level) {
        if (f.stationTask() == StationTask.GUARD || Patrols.escorting(f) || f.isShowcase()) return true;
        if (f.isSleeping() || f.isHired() || f.trip() != null || f.expedition() != null || Nether.away(f)) return true;
        BlockPos me = f.blockPosition();
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, me.getX(), me.getZ());
        return me.getY() < ground - 6 && !level.canSeeSky(me);           // below ground (the mine), or deep indoors already
    }

    /** Under cover, it waits while a monster is this much further off than the distance that sent it in, seen or
     *  not: from inside the house the zombie in the street is out of sight and past ten blocks, and a folk let out
     *  the moment it was in walked straight back into its way. */
    static final int STAY = 10;

    /** The monster this folk should get away from, or null: the nearest that is after it, a raider, a creeper, or
     *  any in sight close by; and for a hand at work, a creeper in its yard. Under cover ({@code stay}), any still
     *  lingering a little further off, in sight or not. */
    @Nullable
    static Mob danger(VillageFolkEntity f, ServerLevel level, long now, boolean stay) {
        boolean child = f.isBaby();
        int more = stay ? STAY : 0;
        double first = child ? NEAR_CHILD : NEAR_CREEPER + 4;
        double look = first + more;
        Map<UUID, Long> braved = BRAVED.get(f.getUUID());
        Mob worst = null;
        double best = Double.MAX_VALUE;
        for (Mob m : level.getEntitiesOfClass(Mob.class, f.getBoundingBox().inflate(look, 6.0, look), WatchClears::monster)) {
            boolean after = m.getTarget() == f;
            boolean raider = m instanceof Raider;
            if (!after && !raider && braved != null) {
                Long until = braved.get(m.getUUID());
                if (until != null && now < until) continue;
            }
            double d = f.distanceToSqr(m);
            double near = (after || raider ? first : m instanceof Creeper ? NEAR_CREEPER : child ? NEAR_CHILD : NEAR) + more;
            if (d > near * near) continue;
            // Round a corner or through a wall a few blocks off is no danger: in sight, or close, or after it.
            if (!stay && !after && !raider && d > 16.0 && !f.hasLineOfSight(m)) continue;
            if (d < best) { best = d; worst = m; }
        }
        if (worst != null || child || f.workZone() == null || !f.onShift()) return worst;
        WorkZone z = f.workZone();
        int r = Math.min(24, z.workRadius() + 4);
        BlockPos zc = z.center();
        for (Creeper cr : level.getEntitiesOfClass(Creeper.class, new AABB(zc).inflate(r, 8, r), Creeper::isAlive)) {
            if (z.containsColumn(cr.blockPosition())) return cr;
        }
        return null;
    }

    /** Where to go: home if that is the nearer way and not past the monster, else the nearest of the town's
     *  buildings that the monster is not nearer to than it is. A bed out under the sky (the founders' camp) is no
     *  cover at all. */
    static BlockPos coverFor(ServerLevel level, VillageFolkEntity f, Mob from) {
        List<BlockPos> places = new ArrayList<>();
        if (f.bedPos() != null && Weather.roofed(level, f.bedPos())) places.add(f.bedPos());
        BlockPos home = Homes.homeOf(f);
        if (home != null) places.add(home);
        UUID id = f.ownerId();
        if (id != null) {
            for (Ledger.Building b : Ledger.buildings(id)) {
                if (shelters(b.structure())) places.add(b.anchor());
            }
        }
        BlockPos me = f.blockPosition();
        BlockPos best = null;
        double bs = Double.MAX_VALUE;
        for (BlockPos p : places) {
            double mine = Math.sqrt(p.distSqr(me));
            if (mine > 64.0) continue;
            double theirs = Math.sqrt(from.blockPosition().distSqr(p));
            double score = mine + (theirs < mine ? 32.0 : 0.0);
            if (score < bs) { bs = score; best = p; }
        }
        return best != null ? best : Raids.shelterFor(f);
    }

    /** Buildings with a roof and a door that a folk can get into and wait in (Raids.shelterFor took only eleven
     *  kinds, so a town of flats and an inn sent its folk past them to a house across the town). Not the wall, the
     *  market's open stalls, a well, a field, a tower or a monument. */
    public static boolean shelters(String s) {
        return switch (s) {
            case "house", "house2", "flats", "flats4", "hall", "townhall", "tavern", "inn", "guesthouse", "storage",
                 "storehouse", "store", "shop", "cafe", "bakery", "bank", "barracks", "granary", "chapel", "school",
                 "library", "museum", "infirmary", "manor", "villa", "postoffice", "theatre", "workshop", "smithy",
                 "brewery", "armoury", "shelter",
                 "lodge" -> true;                                                 // [caves] the Delvers' Lodge
            default -> false;
        };
    }

    /**
     * From the folk's tick: a monster near and it not one of the watch, indoors, and there till the monster has
     * gone. True while it is (its own day waits).
     */
    public static boolean takeCover(VillageFolkEntity f, ServerLevel level) {
        Cover c = COVER.get(f.getUUID());
        boolean look = (f.tickCount + f.getId()) % 10 == 0;
        if (c == null && !look) return false;                            // looked about twice a second
        if (exempt(f, level)) {
            if (c != null) COVER.remove(f.getUUID());
            return false;
        }
        long now = level.getGameTime();
        if (c == null) {
            Mob m = danger(f, level, now, false);
            if (m == null) return false;
            c = new Cover(coverFor(level, f, m), m.getUUID(), now);
            COVER.put(f.getUUID(), c);
            if (f.peekJob() != null) f.clearQueue();
            f.getNavigation().stop();
            String what = name(m);
            f.brain(a(what) + " near: indoors");
            if (f.getRandom().nextInt(3) == 0) {
                var r = f.getRandom();
                FolkTalk.speak(f, f.isBaby()
                    ? FolkTalk.pick(r, "Mum! There's " + a(what) + "!", "I'm going in — there's " + a(what) + "!", "Run! " + capital(a(what)) + "!")
                    : FolkTalk.pick(r, "There's " + a(what) + " out there. Indoors!", "Not with " + a(what) + " about. Inside, quick.",
                        "I'm not going near that " + what + ". In we go."));
            }
        } else if (look) {
            Mob m = danger(f, level, now, true);
            if (m != null) {
                c.lastNear = now;
                c.from = m.getUUID();
                // A monster after nobody, that the watch has not seen to in two minutes: a grown folk goes about its
                // day (a creeper stuck in a hole must not keep a street indoors for three days).
                if (!f.isBaby() && now - c.since > BRAVE && m.getTarget() == null && !(m instanceof Raider)) {
                    BRAVED.computeIfAbsent(f.getUUID(), k -> new ConcurrentHashMap<>()).put(m.getUUID(), now + BRAVED_FOR);
                    release(f, false);
                    return false;
                }
            } else if (now - c.lastNear >= CLEAR) {
                release(f, true);
                return false;
            }
        }
        BlockPos me = f.blockPosition();
        double dx = f.getX() - (c.to.getX() + 0.5), dz = f.getZ() - (c.to.getZ() + 0.5);
        double d = Math.sqrt(dx * dx + dz * dz);
        boolean roofed = Weather.roofed(level, me);
        boolean stalled = now - c.progress > STALL;
        // Set upon: it runs for it rather than trade blows, unless it is cornered (in, or with nowhere left to run).
        LivingEntity t = f.getTarget();
        if (t != null && monster(t)) {
            boolean cornered = f.distanceToSqr(t) < 2.5 * 2.5 && (c.settled || stalled);
            if (cornered) return true;                                   // its fists, and the watch on its way
            f.setTarget(null);
        }
        if (roofed && (d <= IN || d <= IN_NEAR && f.getNavigation().isDone() && now - c.walkTickAt > 20 || c.settled)) {
            if (!c.settled) f.brain("indoors till the monster has gone");
            c.settled = true;
            f.getNavigation().stop();
            return true;
        }
        c.settled = false;
        if (d < c.best - 0.5) {
            c.best = d;
            c.progress = now;
        } else if (stalled && now - c.progress < STALL * 3) {
            f.getNavigation().stop();                                    // no way in: it waits where it is a while,
            return true;
        } else if (stalled) {
            c.progress = now;                                            // and then tries again (a door shut, a crowd)
            c.best = Double.MAX_VALUE;
        }
        if (f.getNavigation().isDone() || f.tickCount - c.walkTick > 40 || f.tickCount < c.walkTick) {
            f.walkTo(c.to, f.isBaby() ? 1.35D : 1.3D);
            c.walkTick = f.tickCount;
            c.walkTickAt = now;
        }
        return true;
    }

    private static void release(VillageFolkEntity f, boolean gone) {
        COVER.remove(f.getUUID());
        f.brain(gone ? "the monster has gone: back to the day" : "the watch will see to it: back to the day");
        if (gone && f.getRandom().nextInt(5) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Is it gone? Right, back to it.", "The watch must have seen to it.",
                "Safe to come out, I think."));
        }
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * With the bell ringing, is this folk's bed too far across the town to walk to? It goes into the nearest
     * building instead (VillageFolkEntity.bedtime), children and all.
     */
    public static boolean bedFar(VillageFolkEntity f) {
        BlockPos bed = f.bedPos();
        return bed != null && bed.distSqr(f.blockPosition()) > 32.0 * 32.0;
    }

    // ------------------------------------------------------------------ the dead, and how

    /**
     * What took a folk, for its grave and the books (VillageFolkEntity.die). It used to be "when the raiders came"
     * for any death at all while the bell rang, a fall or the lava as much as a vindicator's axe; now it is what
     * took it, and the raid after it when a monster did.
     */
    public static String how(@Nullable UUID village, DamageSource cause) {
        String base = Mishap.how(cause);
        if (!Raids.underAlarm(village)) return base;
        if (base.equals("by misfortune")) return Raids.raided(village) ? "when the raiders came" : "with the bell ringing";
        boolean monster = base.startsWith("fighting a") && !base.contains("player") || base.equals("in an explosion");
        if (!monster) return base;
        return base + (Raids.raided(village) ? " when the raiders came" : " with the bell ringing");
    }

    /** Was this a death to monsters (for the tally)? */
    static boolean toMonsters(String how) {
        return how.startsWith("fighting a") && !how.contains("player") || how.contains("raiders came") || how.contains("bell ringing")
            || how.startsWith("in an explosion");
    }

    /** A folk of the town fell (VillageFolkEntity.die): written down with where it was and what it was doing. */
    public static void fell(VillageFolkEntity f, String how, long day) {
        UUID id = f.ownerId();
        if (id == null || f.isShowcase() || !(f.level() instanceof ServerLevel level)) return;
        if (how.equals("of old age")) return;
        String trade = f.isBaby() ? "child" : f.stationTask().title.toLowerCase(Locale.ROOT);
        Fallen x = new Fallen(day, f.displayNameCap(), trade.isEmpty() ? "folk" : trade, how, where(level, f), doing(f));
        Deque<Fallen> d = fallen(id);
        d.addLast(x);
        while (d.size() > 12) d.pollFirst();
        saveFallen(id);
        if (toMonsters(how)) {
            today(id, day).lost++;
            saveTally(id);
        }
    }

    /** Where a folk is, in words: "at home", "in the hall", "on the wall", "in its field", "in the street 40 blocks
     *  east of the square", "down the mine". */
    static String where(ServerLevel level, VillageFolkEntity f) {
        BlockPos p = f.blockPosition();
        if (f.post() != null) return "on the wall";
        UUID id = f.ownerId();
        BlockPos home = Homes.homeOf(f);
        if (id != null) {
            for (Ledger.Building b : Ledger.buildings(id)) {
                String s = b.structure();
                if (s.equals("fortify") || s.equals("colony") || s.equals("road")) continue;
                int[] half = BuildGoal.footprint(s);
                int r = Math.max(half[0], half[1]);
                BlockPos a = b.anchor();
                if (Math.abs(p.getX() - a.getX()) > r || Math.abs(p.getZ() - a.getZ()) > r || p.getY() < a.getY() - 2 || p.getY() > a.getY() + 14) continue;
                if (a.equals(home)) return "at home";
                return s.startsWith("house") || s.startsWith("flats") ? "in a house" : "in the " + s;
            }
        }
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        if (p.getY() < ground - 6 && !level.canSeeSky(p)) return f.stationTask() == StationTask.MINE ? "down the mine" : "under the ground";
        WorkZone z = f.workZone();
        if (z != null && z.containsColumn(p)) return f.stationTask() == StationTask.FARM ? "in its field" : "at its work";
        Villages.Village v = Villages.get(id);
        if (v == null) return "out in the country";
        BlockPos c = v.centre();
        int dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ();
        int out = Math.max(Math.abs(dx), Math.abs(dz));
        if (out < TownPlan.PLAZA) return "on the square";
        String dir = compass(dx, dz);
        if (out <= Villages.townReach(v.id())) return "in the street, " + out + " blocks " + dir + " of the square";
        return "out past the town, " + out + " blocks " + dir;
    }

    static String compass(int dx, int dz) {
        double a = Math.toDegrees(Math.atan2(dz, dx));                     // east nought, south ninety
        String[] names = { "east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east" };
        return names[Math.floorMod((int) Math.round(a / 45.0), 8)];
    }

    /** What it was doing, in words. */
    static String doing(VillageFolkEntity f) {
        if (f.isSleeping()) return "asleep";
        LivingEntity t = f.getTarget();
        if (f.stationTask() == StationTask.GUARD && HUNTS.containsKey(f.getUUID()) && t != null) return "hunting " + a(name(t));
        if (f.post() != null) return "keeping the wall";
        if (sheltering(f)) return "making for cover";
        if (t != null) return "fighting " + a(name(t));
        Job j = f.peekJob();
        if (j != null) return j.label().toLowerCase(Locale.ROOT);
        String b = f.brainNoteForBooks();                                // [watch-clears]
        if (b != null && !b.isEmpty()) {
            int cut = b.indexOf(" (");
            return (cut > 0 ? b.substring(0, cut) : b).replace('|', ' ').replace('~', ' ');
        }
        return f.onShift() ? "at its work" : "off work";
    }

    // ------------------------------------------------------------------ the tally

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!(dead.level() instanceof ServerLevel level) || !kind(dead)) return;
        com.jrpetty.mcassistant.Guard.run("watch tally", () -> {
            Villages.Village v = townAt(level, dead.blockPosition());
            if (v == null) return;
            Entity killer = event.getSource().getEntity();
            Tally t = today(v.id(), level.getDayTime() / 24000L);
            if (killer instanceof VillageFolkEntity g && v.id().equals(g.ownerId())) {
                t.watch++;
                HUNTS.remove(g.getUUID());
            } else if (killer instanceof IronGolem) {
                t.golem++;
            } else {
                t.other++;
            }
            saveTally(v.id());
        });
    }

    /** The town whose ground this is, its reach and a little past it. */
    @Nullable
    static Villages.Village townAt(ServerLevel level, BlockPos p) {
        Villages.Village best = null;
        double bestD = Double.MAX_VALUE;
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            BlockPos c = v.centre();
            int reach = Villages.townReach(v.id()) + EDGE;
            if (Math.max(Math.abs(p.getX() - c.getX()), Math.abs(p.getZ() - c.getZ())) > reach || Math.abs(p.getY() - c.getY()) > 48) continue;
            double d = c.distSqr(p);
            if (d < bestD) { bestD = d; best = v; }
        }
        return best;
    }

    static Tally today(UUID id, long day) {
        Deque<Tally> d = tally(id);
        Tally last = d.peekLast();
        if (last != null && last.day == day) return last;
        Tally t = new Tally(day);
        d.addLast(t);
        while (d.size() > 8) d.pollFirst();
        return t;
    }

    /** {watch, golem, other, lost} over the days from {@code from} on. */
    static int[] week(UUID id, long from) {
        int[] n = new int[4];
        for (Tally t : tally(id)) {
            if (t.day < from) continue;
            n[0] += t.watch;
            n[1] += t.golem;
            n[2] += t.other;
            n[3] += t.lost;
        }
        return n;
    }

    private static Deque<Tally> tally(UUID id) {
        return TALLY.computeIfAbsent(id, k -> {
            Deque<Tally> d = new ArrayDeque<>();
            String kept = Ledger.note(k, "watch.tally");
            if (kept != null && !kept.isEmpty()) {
                for (String part : kept.split(";")) {
                    String[] f = part.split(":");
                    if (f.length != 5) continue;
                    try {
                        Tally t = new Tally(Long.parseLong(f[0]));
                        t.watch = Integer.parseInt(f[1]);
                        t.golem = Integer.parseInt(f[2]);
                        t.other = Integer.parseInt(f[3]);
                        t.lost = Integer.parseInt(f[4]);
                        d.addLast(t);
                    } catch (NumberFormatException ignored) { }
                }
            }
            return d;
        });
    }

    private static void saveTally(UUID id) {
        List<String> parts = new ArrayList<>();
        for (Tally t : tally(id)) parts.add(t.day + ":" + t.watch + ":" + t.golem + ":" + t.other + ":" + t.lost);
        Ledger.note(id, "watch.tally", String.join(";", parts));
    }

    private static Deque<Fallen> fallen(UUID id) {
        return FALLEN.computeIfAbsent(id, k -> {
            Deque<Fallen> d = new ArrayDeque<>();
            String kept = Ledger.note(k, "watch.fallen");
            if (kept != null && !kept.isEmpty()) {
                for (String part : kept.split("\\|")) {
                    String[] f = part.split("~", -1);
                    if (f.length != 6) continue;
                    try {
                        d.addLast(new Fallen(Long.parseLong(f[0]), f[1], f[2], f[3], f[4], f[5]));
                    } catch (NumberFormatException ignored) { }
                }
            }
            return d;
        });
    }

    private static void saveFallen(UUID id) {
        List<String> parts = new ArrayList<>();
        for (Fallen x : fallen(id)) {
            parts.add(x.day() + "~" + clean(x.name()) + "~" + clean(x.trade()) + "~" + clean(x.how()) + "~" + clean(x.where()) + "~" + clean(x.doing()));
        }
        Ledger.note(id, "watch.fallen", String.join("|", parts));
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('|', ' ').replace('~', ' ');
    }

    // ------------------------------------------------------------------ telling

    /** For the status (VillageCommands): "monsters about: 3 (2 zombies, 1 creeper); the watch has killed 21 this week
     *  and the golem 4". */
    public static String statusLine(ServerLevel level, Villages.Village v) {
        List<Mob> about = level.isLoaded(v.centre()) ? about(level, v) : List.of();
        long day = level.getDayTime() / 24000L;
        int[] w = week(v.id(), day - 6);
        StringBuilder sb = new StringBuilder("monsters about: ").append(about.size());
        if (!about.isEmpty()) sb.append(" (").append(kinds(about)).append(')');
        sb.append("; the watch has killed ").append(w[0]).append(" this week and the golem ").append(w[1]);
        if (w[3] > 0) sb.append(", and ").append(w[3]).append(" of the town were lost to them");
        return sb.toString();
    }

    /** For the town's books (Annals): the week's tally, and who fell where. Empty when there is nothing to say. */
    public static List<String> booksLines(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        int[] w = week(v.id(), day - 6);
        int now = level.isLoaded(v.centre()) ? about(level, v).size() : 0;
        if (w[0] + w[1] + w[3] > 0 || now > 0) {
            String killed = "the watch killed " + w[0] + " monster" + (w[0] == 1 ? "" : "s") + " over the last 7 days and the golem " + w[1];
            out.add(now > 0 ? "-" + now + " monster" + (now == 1 ? "" : "s") + " still about the town; " + killed + "."
                : "+No monsters about the town; " + killed + ".");
        }
        List<String> who = new ArrayList<>();
        for (Fallen x : fallen(v.id())) if (x.day() >= day - 6 && toMonsters(x.how())) who.add(x.line());
        if (!who.isEmpty()) {
            List<String> last = who.subList(Math.max(0, who.size() - 4), who.size());
            out.add("-Lost to monsters this week: " + String.join("; ", last) + ".");
        }
        return out;
    }

    /** /village monsters: what is about the town and where, the week day by day, and the fallen. */
    public static List<String> report(ServerLevel level, Villages.Village v) {
        List<String> lines = new ArrayList<>();
        UUID id = v.id();
        BlockPos c = v.centre();
        List<Mob> about = level.isLoaded(c) ? new ArrayList<>(about(level, v)) : new ArrayList<>();
        lines.add("MONSTERS " + Villages.name(id) + ": " + statusLine(level, v) + ".");
        List<VillageFolkEntity> watch = Patrols.watch(id);
        about.sort(Comparator.comparingDouble(m -> m.distanceToSqr(c.getX(), c.getY(), c.getZ())));
        for (int i = 0; i < Math.min(12, about.size()); i++) {
            Mob m = about.get(i);
            int dx = m.getBlockX() - c.getX(), dz = m.getBlockZ() - c.getZ();
            List<String> by = new ArrayList<>();
            for (VillageFolkEntity g : watch) if (g.getTarget() == m) by.add(g.displayNameCap());
            lines.add("  " + capital(name(m)) + " " + Math.max(Math.abs(dx), Math.abs(dz)) + " blocks " + compass(dx, dz)
                + " of the square (" + m.getBlockX() + ", " + m.getBlockY() + ", " + m.getBlockZ() + ")"
                + (level.canSeeSky(m.blockPosition()) ? "" : ", in the shade") + (m.isInWater() ? ", in the water" : "")
                + ": " + (by.isEmpty() ? "nobody after it yet" : "after it: " + String.join(", ", by)));
        }
        List<String> days = new ArrayList<>();
        for (Tally t : tally(id)) days.add("day " + (t.day + 1) + " watch " + t.watch + ", golem " + t.golem + ", otherwise " + t.other
            + (t.lost > 0 ? ", lost " + t.lost : ""));
        lines.add("By the day: " + (days.isEmpty() ? "nothing yet" : String.join("; ", days)) + ".");
        int under = 0;
        for (AssistantEntity a : Villages.folkOf(id)) if (sheltering(a)) under++;
        lines.add("The watch: " + watch.size() + " guards, " + HUNTS.keySet().stream().filter(u -> watch.stream().anyMatch(g -> g.getUUID().equals(u))).count()
            + " out after one; " + under + " folk indoors out of a monster's way.");
        List<String> who = new ArrayList<>();
        for (Fallen x : fallen(id)) who.add(x.line());
        lines.add("The fallen: " + (who.isEmpty() ? "none written down" : String.join("; ", who)) + ".");
        return lines;
    }

    /** /village monsters (and, for operators, /village monsters now: the watch's look round the town at once). */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("monsters")
            .executes(WatchClears::cmdReport)
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                tick(ctx.getSource().getLevel(), v);
                return cmdReport(ctx);
            }));
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int cmdReport(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        List<String> lines = report(ctx.getSource().getLevel(), v);
        String text = String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return lines.size();
    }

    // ------------------------------------------------------------------ the tests

    /** The watch's look round the town, now. */
    public static void tickForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v != null) tick(level, v);
    }

    /** The monsters about the town now. */
    public static List<Mob> aboutForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        return v == null ? List.of() : about(level, v);
    }

    /** The monster this guard was sent after, or null. */
    @Nullable
    public static UUID huntedByForTests(AssistantEntity g) {
        Hunt h = HUNTS.get(g.getUUID());
        return h == null ? null : h.monster();
    }

    /** {watch, golem, other, lost} over the last seven days. */
    public static int[] weekForTests(ServerLevel level, UUID village) {
        return week(village, level.getDayTime() / 24000L - 6);
    }

    /** The fallen written down for the town. */
    public static List<Fallen> fallenForTests(UUID village) {
        return new ArrayList<>(fallen(village));
    }

    /** Where this folk is going for cover, or null when it is not under cover. */
    @Nullable
    public static BlockPos coverForTests(AssistantEntity f) {
        Cover c = COVER.get(f.getUUID());
        return c == null ? null : c.to;
    }

    /** Is this spot under a roof, as cover reads it? */
    public static boolean roofedForTests(ServerLevel level, BlockPos feet) {
        return Weather.roofed(level, feet);
    }

    /** What the books would read on the watch now. */
    public static List<String> booksForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        return v == null ? List.of() : booksLines(level, v);
    }
}
