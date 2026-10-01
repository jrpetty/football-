package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.village.Chronicle;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trouble, and how a village meets it.
 * <ul>
 * <li><b>The alarm.</b> When monsters get inside the wall (four or more), when a raid comes
 *     to the village, or when a raiding party is sighted, the alarm bell on the square
 *     rings (and the chapel's with it). The gates are shut.</li>
 * <li><b>The watch.</b> Every guard turns out, whichever watch it keeps. A guard with a
 *     bow (from its pack, or the village's stores) climbs to its post on the wall and
 *     shoots from between the battlements; one without holds the nearest gate.</li>
 * <li><b>Everybody else</b> drops what they are doing and gets indoors: home to their
 *     own bed, or into the nearest of the village's buildings.</li>
 * <li><b>Raiding parties.</b> About one night in five a walled village with a watch is
 *     raided: a band comes at one of its gates after dark (zombies and skeletons in the
 *     Stone Age, spiders with them in the Iron Age, pillagers and vindicators from the
 *     Diamond Age), bigger the more guards there are to meet it. What is left of it
 *     slinks off at dawn.</li>
 * <li><b>The gates</b> are shut every night at dusk and opened in the morning; folk let
 *     themselves through, monsters can't.</li>
 * <li>When it is over the bell stops, the guards come down, and the night goes into the
 *     village's history: how many came, how many fell, and who stood with the village.</li>
 * </ul>
 */
public final class Raids {

    private Raids() {}

    /** How often the watch looks round (ticks). */
    static final int EVERY = 20;
    /** A raiding party comes about one night in this many. */
    static final int NIGHTS = 5;
    /** Monsters inside the wall that ring the bell by themselves. */
    static final int TOO_MANY = 4;

    /** A village's alarm: why, since when, the band if it is a raid, and how it went. */
    public static final class Alarm {
        final long since;
        final String why;
        @Nullable final Direction from;
        final List<UUID> band = new ArrayList<>();
        final Set<UUID> armed = new HashSet<>();
        final Set<UUID> helpers = new HashSet<>();
        boolean raid;
        int size, killed, lost;
        long lastRing = -100000L;
        long quietSince = -1L;

        Alarm(long since, String why, @Nullable Direction from) {
            this.since = since;
            this.why = why;
            this.from = from;
        }
    }

    private static final Map<UUID, Alarm> ALARMS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> RAIDED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> KEPT = new ConcurrentHashMap<>();
    /** When each guard started up its ladder. */
    private static final Map<UUID, Long> CLIMB = new ConcurrentHashMap<>();

    public static void resetForTests() {
        ALARMS.clear();
        RAIDED.clear();
        KEPT.clear();
        CLIMB.clear();
        Watch.resetForTests();
    }

    /** Is the bell ringing in this village? */
    public static boolean underAlarm(@Nullable UUID village) {
        return village != null && ALARMS.containsKey(village);
    }

    /** Is the village being raided (a band, not a few stray monsters)? */
    public static boolean raided(@Nullable UUID village) {
        Alarm a = village == null ? null : ALARMS.get(village);
        return a != null && a.raid;
    }

    /** Why the bell is ringing, for the status line; null when it is not. */
    @Nullable
    public static String why(@Nullable UUID village) {
        Alarm a = village == null ? null : ALARMS.get(village);
        return a == null ? null : a.why;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % EVERY != 13) return;
        Guard.run("raids", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (v.dim().equals(level.dimension())) tick(level, v);
                }
            }
        });
    }

    /** The watch's look round one village. */
    public static void tick(ServerLevel level, Villages.Village v) {
        if (!level.isLoaded(v.centre())) return;
        UUID id = v.id();
        long now = level.getGameTime();
        long t = level.getDayTime() % 24000L;
        long day = level.getDayTime() / 24000L;
        // The wall's gates, the posts' ladders and the bell, seen to every minute.
        if (now - KEPT.getOrDefault(id, -100000L) >= 1200L) {
            KEPT.put(id, now);
            if (Watch.wall(id) != null) {
                Watch.keep(level, v, false);
                Watch.bell(level, v, false);
            }
        }
        Alarm a = ALARMS.get(id);
        // The gates: shut at dusk and whenever the bell rings, open again in the morning. Only on
        // the change, so a folk who opens a door to go through isn't fought by the watch.
        boolean shut = (t >= 13000L && t < 23000L) || a != null;
        if (Watch.isShut(id) != shut) Watch.shut(level, id, shut);
        // Trouble?
        int inside = threatsInside(level, v);
        Raid vanilla = level.getRaidAt(v.centre());
        boolean bigRaid = vanilla != null && vanilla.isActive();
        if (a == null && (inside >= TOO_MANY || bigRaid)) {
            a = raise(level, v, bigRaid ? "a raid" : "monsters inside the wall", null);
        }
        if (a == null && raidTonight(level, v, day, t)) a = band(level, v, day);
        if (a == null) return;
        drive(level, v, a);
        // The bell: hard at first, then every so often while it lasts.
        long gap = now - a.since < 200L ? 40L : 300L;
        if (now - a.lastRing >= gap) {
            Watch.ring(level, v);
            a.lastRing = now;
        }
        int left = bandLeft(level, a);
        // At dawn (or after a long night) what is left of a band slinks off.
        if (a.raid && left > 0 && (t >= 23000L || t < 1000L || now - a.since > 9000L)) {
            withdraw(level, a);
            left = 0;
        }
        boolean quiet = inside == 0 && left == 0 && !bigRaid;
        if (!quiet) { a.quietSince = -1L; return; }
        if (a.quietSince < 0) a.quietSince = now;
        else if (now - a.quietSince >= 200L) end(level, v, a);
    }

    /** Hostile things inside the wall (or near the heart of a village without one). */
    static int threatsInside(ServerLevel level, Villages.Village v) {
        int r = Watch.R + 2;
        BlockPos c = v.centre();
        AABB box = new AABB(c.getX() - r, c.getY() - 12, c.getZ() - r, c.getX() + r + 1, c.getY() + 16, c.getZ() + r + 1);
        return level.getEntitiesOfClass(Mob.class, box, m -> m.isAlive() && hostile(m)).size();
    }

    static boolean hostile(Entity e) {
        return (e instanceof Monster || e instanceof Raider) && !(e instanceof net.minecraft.world.entity.monster.EnderMan)
            && !(e instanceof net.minecraft.world.entity.monster.ZombifiedPiglin);
    }

    // ------------------------------------------------------------------ raising the alarm

    static Alarm raise(ServerLevel level, Villages.Village v, String why, @Nullable Direction from) {
        Alarm a = new Alarm(level.getGameTime(), why, from);
        ALARMS.put(v.id(), a);
        Watch.shut(level, v.id(), true);
        Watch.ring(level, v);
        a.lastRing = level.getGameTime();
        String name = Villages.name(v.id());
        tellNear(level, v.centre(), 160, Component.literal("The bell is ringing in " + name + ": " + why + "!")
            .withStyle(ChatFormatting.RED), false);
        // The first to hear it shout it on.
        for (AssistantEntity f : Villages.folkOf(v.id())) {
            if (!(f instanceof VillageFolkEntity folk) || folk.isSleeping()) continue;
            if (folk.getRandom().nextInt(3) != 0) continue;
            FolkTalk.speak(folk, folk.stationTask() == AssistantEntity.StationTask.GUARD
                ? FolkTalk.pick(folk.getRandom(), "To the walls!", "The bell! Bows, everyone!", "Watch, with me!")
                : FolkTalk.pick(folk.getRandom(), "Inside, quick!", "Get indoors!", "The bell — everybody in!"));
        }
        return a;
    }

    /** Raiders come at a walled village with a watch, some nights, after dark. */
    static boolean raidTonight(ServerLevel level, Villages.Village v, long day, long t) {
        if (!AssistantConfig.villageRaids() || level.getDifficulty() == Difficulty.PEACEFUL) return false;
        if (t < 13600L || t > 15500L) return false;
        UUID id = v.id();
        if (RAIDED.getOrDefault(id, -10L) >= day) return false;
        if (Math.floorMod(day * 31L + id.hashCode(), NIGHTS) != 0) return false;
        if (Villages.ageOf(id).ordinal() < Villages.Age.STONE.ordinal() || Watch.wall(id) == null) return false;
        if (Villages.headcount(id) < 10 || guards(id) == 0) return false;
        long founded = Chronicle.foundedOn(id);
        return founded < 0 || day - founded >= 3;
    }

    static int guards(UUID village) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == AssistantEntity.StationTask.GUARD) n++;
        return n;
    }

    /** A raiding party, outside one of the gates. Returns the alarm, or null if none could come. */
    @Nullable
    public static Alarm band(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        RAIDED.put(id, day);
        Direction from = Watch.SIDES[Math.floorMod((int) day + id.hashCode(), 4)];
        Villages.Age age = Villages.ageOf(id);
        int n = Math.max(3, Math.min(10, 2 + 2 * guards(id) + age.ordinal()));
        BlockPos base = v.centre().relative(from, Watch.R + 18);
        Direction across = from.getClockWise();
        List<UUID> band = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BlockPos at = base.relative(across, (i - n / 2) * 2).relative(from, level.getRandom().nextInt(3));
            if (!level.isLoaded(at)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
            Mob m = raider(level, age, i);
            if (m == null) continue;
            m.moveTo(at.getX() + 0.5, y, at.getZ() + 0.5, from.getOpposite().toYRot(), 0.0F);
            m.setPersistenceRequired();
            m.addTag("mca_raider");
            if (level.addFreshEntity(m)) band.add(m.getUUID());
        }
        if (band.isEmpty()) return null;
        Alarm a = raise(level, v, "raiders at the " + from.getName() + " gate", from);
        a.raid = true;
        a.band.addAll(band);
        a.size = band.size();
        return a;
    }

    /** One of a band: what comes depends on how far the village has come. */
    @Nullable
    static Mob raider(ServerLevel level, Villages.Age age, int i) {
        Mob m;
        if (age.ordinal() >= Villages.Age.DIAMOND.ordinal()) {
            if (i % 3 == 0) {
                m = EntityType.PILLAGER.create(level);
                if (m != null) m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
            } else {
                m = EntityType.VINDICATOR.create(level);
                if (m != null) m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            }
        } else if (age.ordinal() >= Villages.Age.IRON.ordinal()) {
            m = switch (i % 3) {
                case 0 -> EntityType.SKELETON.create(level);
                case 1 -> EntityType.SPIDER.create(level);
                default -> EntityType.ZOMBIE.create(level);
            };
            if (m instanceof net.minecraft.world.entity.monster.AbstractSkeleton) m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        } else {
            m = i % 4 == 0 ? EntityType.SKELETON.create(level) : EntityType.ZOMBIE.create(level);
            if (m instanceof net.minecraft.world.entity.monster.AbstractSkeleton) m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        }
        if (m != null) m.setDropChance(EquipmentSlot.MAINHAND, 0.05F);
        return m;
    }

    /** The band goes for the nearest of the village's people, or makes for the heart. */
    static void drive(ServerLevel level, Villages.Village v, Alarm a) {
        for (UUID u : a.band) {
            if (!(level.getEntity(u) instanceof Mob m) || !m.isAlive()) continue;
            LivingEntity target = m.getTarget();
            if (target != null && target.isAlive() && target.distanceToSqr(m) < 24 * 24) continue;
            VillageFolkEntity prey = nearestFolk(v, m, 20.0);
            if (prey != null) {
                m.setTarget(prey);
            } else if (m.getNavigation().isDone()) {
                BlockPos c = v.centre();
                m.getNavigation().moveTo(c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 1.0D);
            }
        }
        // A real raid's raiders go for the folk too, not only for players.
        if (!a.raid) {
            BlockPos c = v.centre();
            AABB box = new AABB(c).inflate(Watch.R + 16, 12, Watch.R + 16);
            for (Raider r : level.getEntitiesOfClass(Raider.class, box, Raider::isAlive)) {
                if (r.getTarget() != null && r.getTarget().isAlive()) continue;
                VillageFolkEntity prey = nearestFolk(v, r, 16.0);
                if (prey != null) r.setTarget(prey);
            }
        }
    }

    @Nullable
    static VillageFolkEntity nearestFolk(Villages.Village v, Entity from, double reach) {
        VillageFolkEntity best = null;
        double bd = reach * reach;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !f.isAlive()) continue;
            double d = f.distanceToSqr(from);
            if (d < bd) { bd = d; best = f; }
        }
        return best;
    }

    static int bandLeft(ServerLevel level, Alarm a) {
        int n = 0;
        for (UUID u : a.band) if (level.getEntity(u) instanceof Mob m && m.isAlive()) n++;
        return n;
    }

    static void withdraw(ServerLevel level, Alarm a) {
        for (UUID u : a.band) {
            if (!(level.getEntity(u) instanceof Mob m) || !m.isAlive()) continue;
            level.sendParticles(ParticleTypes.POOF, m.getX(), m.getY() + 0.8, m.getZ(), 12, 0.3, 0.5, 0.3, 0.02);
            m.discard();
        }
    }

    static void end(ServerLevel level, Villages.Village v, Alarm a) {
        ALARMS.remove(v.id());
        long day = level.getDayTime() / 24000L;
        String name = Villages.name(v.id());
        if (a.raid) {
            String line = a.size + " raiders came at the " + (a.from == null ? "" : a.from.getName() + " ")
                + "gate in the night; the watch " + (a.killed > 0 ? "killed " + a.killed + " of them" : "held them off")
                + (a.lost > 0 ? ", and " + a.lost + " of the village fell" : " and nobody was lost");
            Villages.tell(v.id(), day, line);
            for (AssistantEntity f : Villages.folkOf(v.id())) {
                if (f instanceof VillageFolkEntity folk) folk.persona().remember(day, "the night the raiders came", a.lost > 0 ? 8 : 6);
            }
        } else if (level.getGameTime() - a.since > 600L) {
            Villages.tell(v.id(), day, "the bell rang for " + a.why);
        }
        tellNear(level, v.centre(), 160, Component.literal("The bell has stopped in " + name + ".")
            .withStyle(ChatFormatting.GRAY), true);
        // The watch comes down off the wall.
        for (AssistantEntity f : Villages.folkOf(v.id())) {
            if (f instanceof VillageFolkEntity folk && folk.post() != null) leavePost(folk);
        }
    }

    /** A folk of this village fell (Raids counts the night's losses). */
    public static void fell(@Nullable UUID village) {
        Alarm a = village == null ? null : ALARMS.get(village);
        if (a != null) a.lost++;
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level) || !event.getEntity().getTags().contains("mca_raider")) return;
        UUID dead = event.getEntity().getUUID();
        for (Map.Entry<UUID, Alarm> e : ALARMS.entrySet()) {
            Alarm a = e.getValue();
            if (!a.band.contains(dead)) continue;
            a.killed++;
            Entity killer = event.getSource().getEntity();
            if (killer instanceof VillageFolkEntity guard) {
                guard.note(AssistantEntity.Deed.MOBS_KILLED, 1);
                if (guard.getRandom().nextInt(3) == 0) FolkTalk.speak(guard, FolkTalk.pick(guard.getRandom(), "Got one!", "That's one fewer.", "Next!"));
            } else if (killer instanceof Player p) {
                long day = level.getDayTime() / 24000L;
                boolean first = a.helpers.add(p.getUUID());
                for (AssistantEntity f : Villages.folkOf(e.getKey())) {
                    if (!(f instanceof VillageFolkEntity folk)) continue;
                    folk.persona().feelFor(p.getUUID(), p.getName().getString(), 3);
                    if (first) folk.persona().remember(day, p.getName().getString() + " stood with us when the raiders came", 7);
                }
                if (first) Villages.tell(e.getKey(), day, p.getName().getString() + " stood with the village against the raiders");
                Standing.stir(e.getKey(), p.getUUID());
            }
            return;
        }
    }

    // ------------------------------------------------------------------ the watch

    /**
     * A guard's part when the bell rings: to its post on the wall with a bow, or to the nearest
     * gate without one. Returns whether the guard has its orders (the station brain does
     * nothing else while it does).
     */
    public static boolean guardDuty(VillageFolkEntity g) {
        UUID id = g.ownerId();
        Alarm a = id == null ? null : ALARMS.get(id);
        if (a == null) {
            if (g.post() != null) leavePost(g);
            SENT.remove(g.getUUID());
            return false;
        }
        if (!(g.level() instanceof ServerLevel level)) return false;
        Villages.Village v = Villages.get(id);
        if (v == null) return false;
        if (a.armed.add(g.getUUID())) arm(level, v, g);
        boolean archer = g.countCarried(s -> s.is(Items.BOW) || s.is(Items.CROSSBOW)) > 0 && g.hasArrows();
        if (archer) {
            Watch.Post p = postFor(level, v, g, a);
            if (p != null) return man(level, g, p);
        }
        if (g.post() != null) leavePost(g);
        return holdGate(level, v, g);
    }

    /** Is this guard, with the bell ringing, on its way up to a post on the wall? */
    public static boolean headingForPost(VillageFolkEntity g) {
        UUID id = g.ownerId();
        if (id == null || !ALARMS.containsKey(id) || !(g.level() instanceof ServerLevel level)) return false;
        if (g.countCarried(s -> s.is(Items.BOW) || s.is(Items.CROSSBOW)) == 0 || !g.hasArrows()) return false;
        return !Watch.posts(level, id).isEmpty();
    }

    /** Bow and arrows out of the village's stores, if the guard has none of its own. */
    static void arm(ServerLevel level, Villages.Village v, VillageFolkEntity g) {
        if (g.countCarried(s -> s.is(Items.BOW) || s.is(Items.CROSSBOW)) == 0
                && TownWork.take(level, v, s -> s.is(Items.BOW), 1)) {
            ItemStack left = g.insertItem(new ItemStack(Items.BOW));
            if (!left.isEmpty()) g.spawnAtLocation(left);
        }
        // A healing potion from the brewer, drunk if the fight goes badly (Links.drinkIfHurt).
        if (g.countCarried(Links::healing) == 0) {
            ItemStack potion = Crafts.takeOne(level, v, Links::healing);
            if (!potion.isEmpty()) {
                ItemStack left = g.insertItem(potion);
                if (!left.isEmpty()) g.spawnAtLocation(left);
            }
        }
        int arrows = g.countCarried(s -> s.is(Items.ARROW));
        for (int want : new int[]{ 32, 16, 8 }) {
            if (arrows >= 16) break;
            if (TownWork.take(level, v, s -> s.is(Items.ARROW), want)) {
                ItemStack left = g.insertItem(new ItemStack(Items.ARROW, want));
                if (!left.isEmpty()) g.spawnAtLocation(left);
                break;
            }
        }
    }

    /** Which post this guard takes: the threatened side's first, in a fixed order of guards. */
    @Nullable
    static Watch.Post postFor(ServerLevel level, Villages.Village v, VillageFolkEntity g, Alarm a) {
        List<Watch.Post> posts = new ArrayList<>(Watch.posts(level, v.id()));
        if (posts.isEmpty()) return null;
        Direction from = a.from;
        posts.sort(Comparator.comparingInt((Watch.Post p) -> from != null && p.out() == from ? 0 : 1));
        List<UUID> watch = new ArrayList<>();
        for (AssistantEntity f : Villages.folkOf(v.id())) {
            if (f.stationTask() == AssistantEntity.StationTask.GUARD) watch.add(f.getUUID());
        }
        watch.sort(Comparator.naturalOrder());
        int i = Math.max(0, watch.indexOf(g.getUUID()));
        return posts.get(i % posts.size());
    }

    /** When each guard was sent to its post, this alarm. */
    private static final Map<UUID, Long> SENT = new ConcurrentHashMap<>();

    /** To the post: up the ladder, onto the wall, and shoot from there. */
    static boolean man(ServerLevel level, VillageFolkEntity g, Watch.Post p) {
        BlockPos stand = p.stand();
        double sx = stand.getX() + 0.5, sz = stand.getZ() + 0.5;
        double dx = g.getX() - sx, dz = g.getZ() - sz, dy = g.getY() - stand.getY();
        boolean up = dx * dx + dz * dz < 0.9 && dy > -0.4 && dy < 1.5;
        // Twenty seconds and still not up there (a crowd in the way, a long way round): it gets
        // there somehow — a wall with nobody on it is no use to anybody.
        long sent = SENT.computeIfAbsent(g.getUUID(), k -> level.getGameTime());
        if (!up && level.getGameTime() - sent > 400L) {
            g.teleportTo(sx, stand.getY(), sz);
            g.getNavigation().stop();
            dx = 0;
            dz = 0;
            dy = 0;
            up = true;
        }
        // A step off the middle of the post: back onto it before it walks off the edge.
        if (!up && dx * dx + dz * dz < 4.0 && dy > -0.4 && dy < 1.5) {
            g.teleportTo(sx, stand.getY(), sz);
            up = true;
        }
        if (up) {
            if (dx * dx + dz * dz > 0.12) g.teleportTo(sx, stand.getY(), sz);
            g.setDeltaMovement(0.0, Math.min(0.0, g.getDeltaMovement().y), 0.0);
            g.holdPost(stand);
            CLIMB.remove(g.getUUID());
            g.getNavigation().stop();
            Mob m = targetFrom(level, g, 28.0);
            if (m != null) {
                g.setTarget(m);
            } else {
                BlockPos out = stand.relative(p.out(), 10);
                g.getLookControl().setLookAt(out.getX() + 0.5, out.getY() + 0.5, out.getZ() + 0.5);
            }
            return true;
        }
        g.holdPost(null);
        BlockPos foot = p.foot();
        double fx = g.getX() - (foot.getX() + 0.5), fz = g.getZ() - (foot.getZ() + 0.5);
        boolean atFoot = fx * fx + fz * fz < 1.2 && g.getY() >= foot.getY() - 0.5 && g.getY() < stand.getY();
        if (atFoot || g.onClimbable()) {
            // Up the ladder: pushing into the wall on a ladder is climbing it.
            g.getNavigation().stop();
            // At the top of the ladder: straight onto the post, not over the far side of the wall.
            if (g.getY() >= stand.getY() - 1.2) {
                g.teleportTo(sx, stand.getY(), sz);
                g.setDeltaMovement(0.0, 0.0, 0.0);
                CLIMB.remove(g.getUUID());
                return true;
            }
            g.getMoveControl().setWantedPosition(sx, stand.getY(), sz, 1.0D);
            long started = CLIMB.computeIfAbsent(g.getUUID(), k -> level.getGameTime());
            if (level.getGameTime() - started > 120L) {
                g.teleportTo(sx, stand.getY(), sz);           // (it got up there somehow)
                CLIMB.remove(g.getUUID());
            }
            return true;
        }
        if (g.getNavigation().isDone() || g.tickCount % 40 == 0) {
            g.getNavigation().moveTo(foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5, 1.3D);
        }
        return true;
    }

    /** Something to shoot at from the wall: the band first, then anything hostile near the village. */
    @Nullable
    static Mob targetFrom(ServerLevel level, VillageFolkEntity g, double reach) {
        Mob best = null;
        double bd = reach * reach;
        for (Mob m : level.getEntitiesOfClass(Mob.class, g.getBoundingBox().inflate(reach), x -> x.isAlive() && hostile(x))) {
            double d = m.distanceToSqr(g) * (m.getTags().contains("mca_raider") ? 0.5 : 1.0);
            if (d < bd && g.hasLineOfSight(m)) { bd = d; best = m; }
        }
        return best;
    }

    /** Down off the wall when it is over. */
    static void leavePost(VillageFolkEntity g) {
        SENT.remove(g.getUUID());
        BlockPos at = g.post();
        g.holdPost(null);
        CLIMB.remove(g.getUUID());
        if (at == null || !(g.level() instanceof ServerLevel level)) return;
        UUID id = g.ownerId();
        if (id == null) return;
        for (Watch.Post p : Watch.posts(level, id)) {
            if (!p.stand().equals(at)) continue;
            if (g.distanceToSqr(at.getX() + 0.5, at.getY(), at.getZ() + 0.5) < 4.0) {
                g.teleportTo(p.foot().getX() + 0.5, p.foot().getY(), p.foot().getZ() + 0.5);   // down the ladder
            }
            return;
        }
    }

    /** No bow: hold the nearest gate from inside, and fight whatever comes through. */
    static boolean holdGate(ServerLevel level, Villages.Village v, VillageFolkEntity g) {
        Watch.Gate gate = Watch.nearestGate(level, v.id(), g.blockPosition());
        BlockPos spot = gate != null ? gate.inside() : v.centre();
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(spot).inflate(8, 4, 8), x -> x.isAlive() && hostile(x))) {
            g.setTarget(m);
            return true;
        }
        if (g.blockPosition().distSqr(spot) > 4.0 && (g.getNavigation().isDone() || g.tickCount % 40 == 0)) {
            g.getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.2D);
        }
        return true;
    }

    // ------------------------------------------------------------------ everybody else

    /** Where a folk without a bed goes when the bell rings: the nearest of the village's buildings. */
    public static BlockPos shelterFor(VillageFolkEntity f) {
        UUID id = f.ownerId();
        BlockPos best = f.villageCentre() != null ? f.villageCentre() : f.blockPosition();
        if (id == null) return best;
        double bd = Double.MAX_VALUE;
        for (com.jrpetty.mcassistant.village.Ledger.Building b : com.jrpetty.mcassistant.village.Ledger.buildings(id)) {
            switch (b.structure()) {
                case "house", "house2", "hall", "tavern", "storage", "barracks", "granary", "chapel", "cafe", "shop" -> { }
                default -> { continue; }
            }
            double d = b.anchor().distSqr(f.blockPosition());
            if (d < bd) { bd = d; best = b.anchor(); }
        }
        return best;
    }

    /** Off to shelter (no bed of its own to go to). Returns true while it is going or there. */
    public static boolean shelter(VillageFolkEntity f) {
        BlockPos to = shelterFor(f);
        if (f.blockPosition().distSqr(to) > 4.0) {
            if (f.getNavigation().isDone() || f.tickCount % 60 == 0) f.walkTo(to, 1.25D);
        } else {
            f.getNavigation().stop();
        }
        return true;
    }

    // ------------------------------------------------------------------ the tests

    /** Raise a raiding party now (the tests, the showcase). */
    @Nullable
    public static Alarm raidNow(ServerLevel level, Villages.Village v) {
        return band(level, v, level.getDayTime() / 24000L);
    }

    public static int bandSize(UUID village) {
        Alarm a = ALARMS.get(village);
        return a == null ? 0 : a.band.size();
    }

    public static List<UUID> band(UUID village) {
        Alarm a = ALARMS.get(village);
        return a == null ? List.of() : List.copyOf(a.band);
    }

    // ------------------------------------------------------------------ telling people

    static void tellNear(ServerLevel level, BlockPos at, int reach, Component line, boolean bar) {
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().closerThan(at, reach)) {
                if (bar) p.displayClientMessage(line, true);
                else p.sendSystemMessage(line);
            }
        }
    }
}
