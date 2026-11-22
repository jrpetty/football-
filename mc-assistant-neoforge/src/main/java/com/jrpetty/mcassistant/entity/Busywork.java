package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.FolkAction.Act;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [life28] The minutes between things. A folk with nothing in hand by day — its field growing, its building not due, its
 * break begun out by the plot with no bench in sight, waiting on the stores — used to stand where it was, looking round,
 * till the next thing came: in a grown town by day thirty to forty folk in a hundred stood about at once, and on film the
 * town read as dead. Now, a few seconds of standing so and it finds itself something to do, as anybody would, and drops
 * it the moment real work comes (a job on its list, a goal at work, the town calling it, a gathering, a talk, a seat):
 *
 * <ul>
 * <li><b>At its work</b> (its own ground about it, nothing to do there just now): it walks to a spot on its ground and sees
 *     to it with its trade's hands (a farmer waters and weeds the rows, a woodcutter saws, a miner picks at the face, a
 *     rancher feeds the beasts, a smelter stokes, a fisher fishes, a carrier carries); or it has a word with a workmate
 *     standing by. Away from its ground with nothing to do (a builder whose building is not due), it does a chore where it
 *     is: sweeps, or carries a load across.</li>
 * <li><b>Its own time by day</b> (its break, the day of rest): a word with somebody standing about near it (both walk
 *     together and talk, each in turn), a sit on the grass, a walk to the well to drink, a stroll across the square to
 *     look at what is going on, a bite of what it carries.</li>
 * </ul>
 *
 * Each walks somewhere, does something to be seen doing, and is over in half a minute or so. Nothing is made or spent: it
 * is the look of a town at work in its own time, not the work (that is the trades' and the town's). Never at night.
 */
public final class Busywork {

    private Busywork() {}

    /** On (the tests turn it off to count a town without it, as it was). */
    public static boolean ENABLED = true;

    /** How long a folk stands idle (ticks) before it finds something to do. */
    static final int START = 40;
    /** How far it walks for it at most. */
    static final int REACH = 12;

    public enum Kind { TEND, CHAT, CHORE, CARRY, SIT, WELL, STROLL, SNACK }

    /** What a folk is doing to fill its time: what, where, how far along, and with whom. */
    static final class Doing {
        final Kind kind;
        final Act act;
        final BlockPos spot;
        @Nullable final UUID with;
        final int started;
        int arrived = -1, until;
        @Nullable Vec3 face;
        /** The one of the two that talks first (the other listens): by turns after. */
        boolean speaker;

        Doing(Kind kind, Act act, BlockPos spot, @Nullable UUID with, int started, int until) {
            this.kind = kind;
            this.act = act;
            this.spot = spot;
            this.with = with;
            this.started = started;
            this.until = until;
        }
    }

    private static final Map<UUID, Doing> DOING = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> IDLE_SINCE = new ConcurrentHashMap<>();
    /** Pastimes begun, by kind, since the server started (the tests, the census). */
    static final Map<Kind, Integer> BEGUN = new ConcurrentHashMap<>();

    public static void resetForTests() {
        DOING.clear();
        IDLE_SINCE.clear();
        BEGUN.clear();
    }

    /** Tests: the pastimes begun, by kind. */
    public static Map<Kind, Integer> begunForTests() {
        java.util.EnumMap<Kind, Integer> out = new java.util.EnumMap<>(Kind.class);
        out.putAll(BEGUN);
        return out;
    }

    /** [life28] A hand with nothing at all to do just now: at a pastime, or stood idle a while with its day free (TownJobs). */
    public static boolean spare(VillageFolkEntity f) {
        Doing d = DOING.get(f.getUUID());
        if (d != null) return true;                                    // (seeing to its ground between the real work too)
        Integer since = IDLE_SINCE.get(f.getUUID());
        return since != null && f.tickCount - since >= START / 2;
    }

    /** Tests: this folk stood idle with its day free this long already. */
    public static void idleForTests(VillageFolkEntity f) {
        IDLE_SINCE.put(f.getUUID(), f.tickCount - START);
    }

    /** What it is doing to fill its time, in a word (the tests, the census), or "". */
    public static String doing(VillageFolkEntity f) {
        Doing d = DOING.get(f.getUUID());
        return d == null ? "" : d.kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------------ the body

    /** The act it shows while at its pastime (FolkAction.derive), or NONE: at the spot and still, or carrying on the way. */
    static Act act(VillageFolkEntity f, boolean still) {
        if (!ENABLED) return Act.NONE;
        Doing d = DOING.get(f.getUUID());
        if (d == null) return Act.NONE;
        if (d.kind == Kind.CARRY) return Act.CARRY;                // a crate in its arms, there and back
        if (d.arrived < 0 || !still) return Act.NONE;
        if (d.face != null) f.getLookControl().setLookAt(d.face.x, d.face.y, d.face.z);
        if (d.kind == Kind.CHAT) {
            boolean turn = ((f.tickCount - d.arrived) / 70) % 2 == 0;
            return turn == d.speaker ? Act.TALK : Act.LISTEN;
        }
        if (d.kind == Kind.STROLL) return ((f.tickCount - d.arrived) / 100 + f.getId()) % 2 == 0 ? Act.WATCH : Act.THINK;
        return d.act;
    }

    // ------------------------------------------------------------------ the folk's tick

    /** From the folk's tick (VillageFolkEntity), server side. */
    public static void tick(VillageFolkEntity f) {
        if (!ENABLED || (f.tickCount + f.getId()) % 10 != 0 || !(f.level() instanceof ServerLevel level)) return;
        UUID id = f.getUUID();
        Doing d = DOING.get(id);
        if (d != null) {
            if (!carryOn(f, level, d)) stop(f, d);
            return;
        }
        // (Its legs at rest and nothing on its body: a nudge from the crowd round it (Elbowroom) does not start the count again.)
        boolean idle = FolkAction.now(f) == Act.NONE && f.getNavigation().isDone() && !f.isBaby() && !f.isSleeping();
        if (!idle || !free(f, level)) {
            IDLE_SINCE.remove(id);
            return;
        }
        int since = IDLE_SINCE.computeIfAbsent(id, k -> f.tickCount);
        if (f.tickCount - since < START) return;
        IDLE_SINCE.remove(id);
        begin(f, level);
    }

    /** Free to fill its time: grown, awake, by day, in its town, nothing in hand and nobody's but its own. */
    static boolean free(VillageFolkEntity f, ServerLevel level) {
        if (f.isBaby() || f.isSleeping() || f.isPassenger() || f.isShowcase() || f.isNoAi() || f.ownerId() == null) return false;
        long t = level.getDayTime() % 24000L;
        if (t >= 12000L) return false;                                     // the evening and the night are for rest
        if (f.getTarget() != null || f.isHired() || f.talkPartner() != null || f.companionPlayer() != null || f.guidePlayer() != null) return false;
        if (f.trip() != null || f.expedition() != null || f.getPose() != Pose.STANDING) return false;
        if (f.peekJob() != null || working(f)) return false;
        if (Assemblies.attending(f) || TownJobs.busy(f) || Seats.sitting(f) || Health.laidUp(f)) return false;
        if (f.stationTask() == StationTask.GUARD && f.onShift()) return false;     // the watch is watching
        if (f.isInWater() || !f.onGround()) return false;
        return true;
    }

    /** Why it is not filling its time just now, in a word (the census), or "free". */
    public static String why(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level)) return "client";
        Doing d = DOING.get(f.getUUID());
        if (d != null) return "at " + d.kind + (d.arrived < 0 ? " (on the way)" : "");
        if (f.isPassenger() || f.isShowcase() || f.isNoAi() || f.ownerId() == null) return "not its own";
        if (level.getDayTime() % 24000L >= 12000L) return "evening";
        if (f.getTarget() != null || f.isHired() || f.talkPartner() != null || f.companionPlayer() != null || f.guidePlayer() != null) return "with somebody";
        if (f.trip() != null || f.expedition() != null) return "away";
        if (f.getPose() != Pose.STANDING) return "pose " + f.getPose();
        if (f.peekJob() != null) return "job";
        if (working(f)) return "goal";
        if (Assemblies.attending(f)) return "gathering";
        if (TownJobs.busy(f)) return "town work";
        if (Seats.sitting(f)) return "seat";
        if (Health.laidUp(f)) return "laid up";
        if (f.stationTask() == StationTask.GUARD && f.onShift()) return "watch";
        if (f.isInWater() || !f.onGround()) return "water/air";
        Integer since = IDLE_SINCE.get(f.getUUID());
        return since == null ? "free" : "free " + (f.tickCount - since) + "t";
    }

    /** Is one of its goals at work (anything but looking about)? */
    static boolean working(VillageFolkEntity f) {
        for (WrappedGoal g : f.goalSelector.getAvailableGoals()) {
            if (!g.isRunning()) continue;
            String n = g.getGoal().getClass().getSimpleName();
            if (n.startsWith("RandomLookAround") || n.startsWith("LookAtPlayer") || n.startsWith("Float") || n.startsWith("OpenDoor")) continue;
            return true;
        }
        return false;
    }

    private static boolean carryOn(VillageFolkEntity f, ServerLevel level, Doing d) {
        if (f.tickCount > d.until) return false;
        if (!stillFree(f, level)) return false;
        if (d.with != null) {
            Doing o = DOING.get(d.with);
            if (o == null || !f.getUUID().equals(o.with)) return false;
        }
        boolean there = f.blockPosition().distSqr(d.spot) <= (d.kind == Kind.CHAT ? 4.0 : 2.5);
        if (d.arrived < 0) {
            if (there || f.getNavigation().isDone() && f.tickCount - d.started > 40 && f.blockPosition().distSqr(d.spot) <= 9.0) {
                d.arrived = f.tickCount;
                f.getNavigation().stop();
                arrived(f, level, d);
                return true;
            }
            if (f.tickCount - d.started > 400) return false;                // could not get there
            if (f.getNavigation().isDone()) f.walkTo(d.spot, d.kind == Kind.CARRY ? 0.75D : 0.65D);
            return true;
        }
        // Walked off by its own work, the town or a friend: whatever it was doing is over.
        if (!f.getNavigation().isDone() && f.blockPosition().distSqr(d.spot) > 9.0) return false;
        if (d.kind == Kind.CHAT && d.with != null && level.getEntity(d.with) instanceof VillageFolkEntity o) {
            d.face = o.getEyePosition();
        }
        if (d.kind == Kind.CARRY && f.tickCount - d.arrived > 30) return false;   // set it down: done
        return true;
    }

    /** As free, for one at its pastime already (its own goals' looking about allowed, its own pose kept). */
    private static boolean stillFree(VillageFolkEntity f, ServerLevel level) {
        if (f.isSleeping() || f.isPassenger() || f.getTarget() != null || f.talkPartner() != null || f.isHired()) return false;
        if (level.getDayTime() % 24000L >= 12000L) return false;
        if (f.peekJob() != null || working(f)) return false;
        if (Assemblies.attending(f) || TownJobs.busy(f) || Seats.sitting(f) || Health.laidUp(f)) return false;
        if (f.trip() != null || f.expedition() != null) return false;
        return f.getPose() == Pose.STANDING;
    }

    private static void arrived(VillageFolkEntity f, ServerLevel level, Doing d) {
        switch (d.kind) {
            case SNACK -> {
                ItemStack food = snack(f);
                if (!food.isEmpty()) FolkAction.ate(f, food);
                d.until = f.tickCount + 80;
            }
            case CHAT -> {
                if (d.with != null && level.getEntity(d.with) instanceof VillageFolkEntity o) {
                    d.face = o.getEyePosition();
                    if (d.speaker && f.tickCount % 3 == 0) Smalltalk.chat(f, o, level);
                }
            }
            case WELL -> d.face = Vec3.atCenterOf(d.spot.below());
            default -> {
                if (d.face == null) d.face = Vec3.atCenterOf(d.spot.below()).add(f.getLookAngle().scale(1.5));
            }
        }
    }

    private static void stop(VillageFolkEntity f, Doing d) {
        DOING.remove(f.getUUID());
        if (d.with != null && DOING.get(d.with) instanceof Doing o && f.getUUID().equals(o.with)) o.until = Math.min(o.until, f.tickCount + 20);
    }

    /** Over at once (the tests; a folk taken away). */
    public static void end(VillageFolkEntity f) {
        Doing d = DOING.get(f.getUUID());
        if (d != null) stop(f, d);
    }

    // ------------------------------------------------------------------ what to do

    private static void begin(VillageFolkEntity f, ServerLevel level) {
        boolean atWork = f.offWorkWhy().isEmpty() && f.onShift();
        int roll = f.getRandom().nextInt(100);
        Doing d = null;
        if (atWork) {
            WorkZone z = f.workZone();
            boolean onGround = z != null && z.center().distSqr(f.blockPosition()) <= (double) (z.radius() + 6) * (z.radius() + 6);
            if (roll < 25) d = chat(f, level, 10);
            if (d == null && onGround) d = tend(f, level, z);
            if (d == null) d = chore(f, level);
            if (d == null) d = lookRound(f);
        } else {
            if (roll < 45) d = chat(f, level, 16);
            if (d == null && roll < 60) d = sit(f, level);
            if (d == null && roll < 72 && !snack(f).isEmpty()) d = new Doing(Kind.SNACK, Act.EAT, f.blockPosition(), null, f.tickCount, f.tickCount + 80);
            if (d == null && roll < 86) d = well(f, level);
            if (d == null) d = stroll(f, level);
            if (d == null) d = lookRound(f);
        }
        if (d == null) return;
        DOING.put(f.getUUID(), d);
        BEGUN.merge(d.kind, 1, Integer::sum);
        if (d.spot.distSqr(f.blockPosition()) > 2.5) f.walkTo(d.spot, d.kind == Kind.CARRY ? 0.75D : 0.65D);
    }

    /** A spot of open ground near this one (dry, room to stand), within so far; or null. */
    @Nullable
    static BlockPos spotNear(ServerLevel level, VillageFolkEntity f, BlockPos around, int far, int tries) {
        for (int i = 0; i < tries; i++) {
            int x = around.getX() + f.getRandom().nextInt(2 * far + 1) - far, z = around.getZ() + f.getRandom().nextInt(2 * far + 1) - far;
            BlockPos at = Land.surface(level, x, z);
            if (at == null || Math.abs(at.getY() - f.getBlockY()) > 4) continue;
            if (!level.getFluidState(at).isEmpty() || !level.getFluidState(at.below()).isEmpty()) continue;
            if (!level.getBlockState(at).canBeReplaced() || !level.getBlockState(at.above()).isAir()) continue;
            if (!level.getBlockState(at.below()).isFaceSturdy(level, at.below(), net.minecraft.core.Direction.UP)
                && !level.getBlockState(at.below()).is(net.minecraft.world.level.block.Blocks.FARMLAND)) continue;
            return at;
        }
        return null;
    }

    /** Its own ground seen to with its trade's hands, at a spot on it. */
    @Nullable
    private static Doing tend(VillageFolkEntity f, ServerLevel level, WorkZone z) {
        Act act = tradeAct(f.stationTask());
        if (act == Act.NONE) return null;
        BlockPos spot = spotNear(level, f, z.center(), Math.max(2, Math.min(REACH, z.radius())), 8);
        if (spot == null) return null;
        int t = f.tickCount;
        return new Doing(Kind.TEND, act, spot, null, t, t + 300 + f.getRandom().nextInt(300));
    }

    /** What its trade's hands do on its ground between the real work. */
    static Act tradeAct(StationTask task) {
        return switch (task) {
            case FARM -> Act.WATER;
            case WOOD -> Act.SAW;
            case MINE, CAVE, NETHER -> Act.PICK;
            case RANCH -> Act.FEED;
            case SMELT -> Act.STOKE;
            case FISH -> Act.FISH;
            case STORE, HAUL -> Act.STOCK;
            case SMITH -> Act.HAMMER;
            case TAILOR -> Act.SEW;
            case BEEKEEP -> Act.SMOKE_HIVE;
            case BREW -> Act.BREW;
            case COOK -> Act.STIR_POT;
            case FLETCHER -> Act.FLETCH;
            case HEAL, DIVER -> Act.TEND;
            case SHOP, BANK -> Act.COUNT_COINS;
            case SCOUT, HUNT -> Act.SPYGLASS;
            case CARTOGRAPHER -> Act.MAP;
            case REDSTONE, FIREWORKS, GOLEMS -> Act.TINKER;
            default -> Act.SWEEP;
        };
    }

    /** A chore where it is: the ground swept, or a load carried across. */
    @Nullable
    private static Doing chore(VillageFolkEntity f, ServerLevel level) {
        BlockPos spot = spotNear(level, f, f.blockPosition(), 8, 8);
        if (spot == null) return null;
        int t = f.tickCount;
        if (f.getRandom().nextInt(3) == 0) return new Doing(Kind.CARRY, Act.CARRY, spot, null, t, t + 300);
        return new Doing(Kind.CHORE, Act.SWEEP, spot, null, t, t + 240 + f.getRandom().nextInt(240));
    }

    /** A word with somebody standing about near it: both walk together and talk by turns. */
    @Nullable
    private static Doing chat(VillageFolkEntity f, ServerLevel level, int far) {
        List<VillageFolkEntity> near = level.getEntitiesOfClass(VillageFolkEntity.class, new AABB(f.blockPosition()).inflate(far, 4, far),
            o -> o != f && o.isAlive() && f.ownerId() != null && f.ownerId().equals(o.ownerId()) && !DOING.containsKey(o.getUUID())
                && IdleCensus.idle(o) && free(o, level));
        if (near.isEmpty()) return null;
        VillageFolkEntity o = near.get(f.getRandom().nextInt(near.size()));
        // They meet between them: each stands a step off the middle.
        double mx = (f.getX() + o.getX()) / 2.0, mz = (f.getZ() + o.getZ()) / 2.0;
        double dx = o.getX() - f.getX(), dz = o.getZ() - f.getZ(), len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
        BlockPos mine = Land.surface(level, (int) Math.floor(mx - dx / len * 0.9), (int) Math.floor(mz - dz / len * 0.9));
        BlockPos theirs = Land.surface(level, (int) Math.floor(mx + dx / len * 0.9), (int) Math.floor(mz + dz / len * 0.9));
        if (mine == null || theirs == null || Math.abs(mine.getY() - f.getBlockY()) > 3 || Math.abs(theirs.getY() - o.getBlockY()) > 3) {
            mine = f.blockPosition();
            theirs = o.blockPosition();
        }
        int t = f.tickCount, length = 400 + f.getRandom().nextInt(400);
        Doing a = new Doing(Kind.CHAT, Act.TALK, mine, o.getUUID(), t, t + length);
        Doing b = new Doing(Kind.CHAT, Act.TALK, theirs, f.getUUID(), o.tickCount, o.tickCount + length);
        a.speaker = true;
        DOING.put(o.getUUID(), b);
        BEGUN.merge(Kind.CHAT, 1, Integer::sum);
        IDLE_SINCE.remove(o.getUUID());
        if (theirs.distSqr(o.blockPosition()) > 2.5) o.walkTo(theirs, 0.65D);
        return a;
    }

    /** A sit on the grass for a while, somewhere near. */
    @Nullable
    private static Doing sit(VillageFolkEntity f, ServerLevel level) {
        if (level.isRaining()) return null;
        BlockPos spot = spotNear(level, f, f.blockPosition(), 8, 8);
        if (spot == null || !level.canSeeSky(spot)) return null;
        int t = f.tickCount;
        return new Doing(Kind.SIT, Act.SIT_GROUND, spot, null, t, t + 400 + f.getRandom().nextInt(400));
    }

    /** To the well on the square for a drink, if the town has one near enough. */
    @Nullable
    private static Doing well(VillageFolkEntity f, ServerLevel level) {
        Villages.Village v = Villages.get(f.ownerId());
        if (v == null) return null;
        BlockPos well = v.centre().offset(0, 0, 8);
        if (well.distSqr(f.blockPosition()) > 40.0 * 40.0) return null;
        BlockPos spot = spotNear(level, f, well, 3, 8);
        if (spot == null) return null;
        int t = f.tickCount;
        Doing d = new Doing(Kind.WELL, Act.DRINK, spot, null, t, t + 160 + f.getRandom().nextInt(120));
        d.face = Vec3.atCenterOf(Land.surface(level, well.getX(), well.getZ()) == null ? well : Land.surface(level, well.getX(), well.getZ()));
        return d;
    }

    /** A stroll across to look at what is going on: the square, if near, else round about. */
    @Nullable
    private static Doing stroll(VillageFolkEntity f, ServerLevel level) {
        Villages.Village v = Villages.get(f.ownerId());
        BlockPos around = v != null && v.centre().distSqr(f.blockPosition()) <= 48.0 * 48.0 ? v.centre() : f.blockPosition();
        BlockPos spot = spotNear(level, f, around, REACH, 10);
        if (spot == null) return null;
        int t = f.tickCount;
        return new Doing(Kind.STROLL, Act.THINK, spot, null, t, t + 300 + f.getRandom().nextInt(200));
    }

    /** Nowhere to go: a look round where it stands, as at the town going about its work. */
    private static Doing lookRound(VillageFolkEntity f) {
        int t = f.tickCount;
        return new Doing(Kind.STROLL, Act.THINK, f.blockPosition(), null, t, t + 200 + f.getRandom().nextInt(200));
    }

    /** Something to eat it carries, or empty. */
    private static ItemStack snack(VillageFolkEntity f) {
        for (ItemStack s : f.getInventoryItems()) {
            if (!s.isEmpty() && s.getFoodProperties(f) != null) return s;
        }
        return ItemStack.EMPTY;
    }
}
