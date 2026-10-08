package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Riding the village's horses, and leading its donkeys.
 *
 * <p><b>On horseback.</b> A courier with a long run (forty-eight blocks and more) and a scout
 * setting out on its rounds take a saddled horse from the stable, if one is free and quick enough
 * to be worth the walk to the stable: up on it in its stall, out through the gates, and away. The
 * horse goes where the rider would have walked (a rider has the reins, as a skeleton has on its
 * horse: the horse's own path-finding, steered by the folk's), at the pace its speed gives it under
 * a rider — about half again as quick as a folk at a run, for an ordinary horse; a slow one is not
 * worth taking. Nobody rides a horse without a saddle on.
 *
 * <p><b>Off at the door.</b> A courier gets down a few steps short of the chest it has come for and
 * leaves the horse standing there, tied (it does not wander more than a step or two), does its
 * business on foot, and rides back. At the storehouse it leaves the horse tied by the door while it
 * carries the load in, and rides it out again on the next long run; a minute with nothing long to
 * run, or the evening, and it rides the horse home and puts it in its stall. A scout rides all day
 * and puts its horse away when it is home. A rider who cannot get any nearer gets down and goes on
 * foot. A horse its rider could not get back to stands where it was left, and the rancher fetches
 * it home (Stables): none is left lost.
 *
 * <p><b>On a lead.</b> A caravan takes a donkey or a mule with a chest on it, on a lead (a lead
 * from the stores): the carrier fetches it from the stable, ties it, and puts the load in its chest
 * rather than on its own back; at the other end the load comes out of the chest to be sold, and the
 * goods for home go back in. Home again, the carrier leads it back to the stable and hangs the lead
 * up with the stores.
 */
public final class Riding {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Riding() {}

    /** A run this long (in blocks) is worth a horse. */
    public static final int LONG = 48;
    /** A folk's run, as the game moves it: (0.32 × 1.3 for running × 1.1)². */
    static final double FOLK_RUN = 0.2094;
    /** The most a rider asks of a horse (the path-finding keeps up to this). */
    static final double CAP = 0.30;
    /** The follow range a ridden horse is given for the length of the ride, so its path is planned as far as a folk's is. */
    static final ResourceLocation REACH = ResourceLocation.fromNamespaceAndPath("mc_assistant", "ridden_reach");

    /** Where a ride has got to. */
    enum Phase { WALK, OUT, HITCHED, BACK, PARKED, HOME }

    /** Why it is riding. */
    enum Why { COURIER, SCOUT, STRAY }

    /** One folk and the horse it has out. */
    static final class Ride {
        final UUID folk, horse, village;
        final Why why;
        Phase phase = Phase.WALK;
        /** What a walk to the horse is for: riding out, back, or home. */
        Phase then = Phase.OUT;
        long since;
        @Nullable BlockPos dest;
        String where = "";
        double best = Double.MAX_VALUE;
        long progress;
        int walked = -1000;
        final long started;
        /** Its stall, worked out when it is taken home. */
        @Nullable Vec3 stall;

        Ride(UUID folk, UUID horse, UUID village, Why why, long now) {
            this.folk = folk;
            this.horse = horse;
            this.village = village;
            this.why = why;
            this.started = now;
            this.since = now;
            this.progress = now;
        }
    }

    static final Map<UUID, Ride> RIDES = new ConcurrentHashMap<>();
    /** How long a horse waits tied by the storehouse with no long run for it before it is taken home (the tests' is short). */
    private static long parked = 1200L;
    /** A folk whose ride came to nothing lately: not tried again till then. */
    private static final Map<UUID, Long> NOT_BEFORE = new ConcurrentHashMap<>();
    /** Rides set out on today, by village and by folk: {day, rides}. */
    private static final Map<UUID, long[]> TODAY = new ConcurrentHashMap<>();
    private static final Map<UUID, long[]> MINE = new ConcurrentHashMap<>();

    public static void resetForTests() {
        parked = 1200L;
        RIDES.clear();
        NOT_BEFORE.clear();
        TODAY.clear();
        MINE.clear();
        PACKS.clear();
        CHEST_TO.clear();
        LOOKED_IN.clear();
    }

    // ------------------------------------------------------------------ the horse's pace

    /** The horse's pace under a rider, as the game moves a mob: its speed as a player feels it on its back (its speed × 0.98
     *  against a running player's 0.13), kept in proportion to how much quicker than a player a folk runs; at most CAP. */
    static double effective(AbstractHorse h) {
        double s = h.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
        return Math.min(CAP, FOLK_RUN * s * 0.98 / 0.1274);
    }

    /** How many times quicker than a folk at a run. */
    public static double quick(AbstractHorse h) {
        return effective(h) / FOLK_RUN;
    }

    /** What the rider asks of its path-finding for that pace (a mob's pace goes as the square of what it is asked). */
    static double gallop(AbstractHorse h) {
        double s = h.getAttributeValue(Attributes.MOVEMENT_SPEED);
        return s <= 0.01 ? 1.0 : Math.sqrt(effective(h)) / s;
    }

    /** "half again as quick as on foot". */
    static String paceWords(AbstractHorse h) {
        double q = quick(h);
        if (q >= 1.4) return "half again as quick as on foot";
        if (q >= 1.25) return "a third again as quick as on foot";
        if (q >= 1.1) return "a little quicker than on foot";
        return "no quicker than on foot";
    }

    // ------------------------------------------------------------------ up and down

    @Nullable
    static AbstractHorse horse(ServerLevel level, Ride r) {
        Entity e = level.getEntity(r.horse);
        return e instanceof AbstractHorse h && h.isAlive() ? h : null;
    }

    /** Is this animal out with a rider, tied up while its rider works, or on a caravan's lead? */
    public static boolean taken(UUID animal) {
        for (Ride r : RIDES.values()) if (r.horse.equals(animal)) return true;
        for (Pack p : PACKS.values()) if (p.donkey.equals(animal)) return true;
        return false;
    }

    /** A free saddled horse in the stable, the quickest: tamed, the village's, grown, well, and worth riding. */
    @Nullable
    static AbstractHorse freeHorse(ServerLevel level, UUID village, Stables.Stable st) {
        AbstractHorse best = null;
        for (AbstractHorse h : level.getEntitiesOfClass(AbstractHorse.class, new AABB(st.anchor()).inflate(8, 4, 8),
                x -> x instanceof Horse && Stables.ours(x, village) && x.isSaddled() && !x.isBaby() && !x.isVehicle() && !x.isLeashed()
                    && x.getHealth() > x.getMaxHealth() * 0.5F && !taken(x.getUUID()) && !Stables.handled(x.getUUID()))) {
            if (!st.inside(h.position()) || quick(h) < 1.2) continue;
            if (best == null || effective(h) > effective(best)) best = h;
        }
        return best;
    }

    static boolean mount(VillageFolkEntity f, AbstractHorse h) {
        if (f.getVehicle() == h) return true;
        f.getNavigation().stop();
        h.getNavigation().stop();
        h.clearRestriction();
        h.setEating(false);
        if (h.isLeashed() || !f.startRiding(h)) return false;
        reach(h, true);
        return true;
    }

    /** The ridden horse plans its way as far as a folk does; afterwards, as far as a horse. */
    static void reach(AbstractHorse h, boolean on) {
        AttributeInstance range = h.getAttribute(Attributes.FOLLOW_RANGE);
        if (range != null) {
            if (on) range.addOrUpdateTransientModifier(new AttributeModifier(REACH, 96.0, AttributeModifier.Operation.ADD_VALUE));
            else range.removeModifier(REACH);
        }
        h.getNavigation().setMaxVisitedNodesMultiplier(on ? 8.0F : 1.0F);
    }

    static void getOff(VillageFolkEntity f, AbstractHorse h) {
        if (f.getVehicle() == h) f.stopRiding();
        reach(h, false);
        h.getNavigation().stop();
    }

    /** Off, and the horse left standing tied where it is: it does not wander more than a step or two. */
    static void hitch(VillageFolkEntity f, ServerLevel level, Ride r, AbstractHorse h, Phase as) {
        getOff(f, h);
        h.restrictTo(h.blockPosition(), 2);
        r.phase = as;
        r.since = level.getGameTime();
        r.best = Double.MAX_VALUE;
        r.progress = r.since;
        f.brain(as == Phase.PARKED ? Stables.name(h) + " tied up by the storehouse" : "got down; " + Stables.name(h) + " tied up nearby");
    }

    /** The ride over: off the horse (if still on it). */
    static void end(VillageFolkEntity f, ServerLevel level, Ride r, String why) {
        RIDES.remove(f.getUUID());
        AbstractHorse h = horse(level, r);
        if (h != null) {
            if (f.getVehicle() == h) getOff(f, h);
            else reach(h, false);
        }
        f.brain("riding: " + why);
    }

    /** A horse its rider could not get back to: left free where it stands. It walks in itself from the yard, and the rancher fetches it from further. */
    static void loose(ServerLevel level, Ride r, AbstractHorse h) {
        h.clearRestriction();
        reach(h, false);
        LOG.info("[MCA-RIDE] {} left out at {} ({} blocks from home): for the stable to bring in", Stables.name(h),
            h.blockPosition().toShortString(), (int) Math.sqrt(h.blockPosition().distSqr(homeOf(r.village))));
    }

    private static BlockPos homeOf(UUID village) {
        Stables.Stable st = Stables.stable(village);
        if (st != null) return st.anchor();
        BlockPos h = Stables.home(village);
        return h == null ? BlockPos.ZERO : h;
    }

    static double flat(Vec3 a, BlockPos b) {
        double dx = a.x - (b.getX() + 0.5), dz = a.z - (b.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    static boolean late(ServerLevel level, UUID village) {
        long time = level.getDayTime() % 24000L;
        return time >= 11500 || !level.isDay() || Raids.underAlarm(village);
    }

    /** Up and away: the ride counted, and on its way. */
    private static void started(VillageFolkEntity f, ServerLevel level, Ride r, AbstractHorse h) {
        long now = level.getGameTime();
        r.phase = r.then;
        r.since = now;
        r.best = Double.MAX_VALUE;
        r.progress = now;
        r.walked = -1000;
        if (r.then == Phase.OUT) {
            long day = level.getDayTime() / 24000L;
            bump(TODAY, r.village, day);
            bump(MINE, f.getUUID(), day);
            if (r.dest != null) {
                f.walkTo(r.dest, 1.1D);
                r.walked = f.tickCount;
            }
            if (r.why == Why.SCOUT && f.expedition() != null) f.expedition().gainedTick = f.tickCount;
            LOG.info("[MCA-RIDE] {} rides {} out {} ({}, pace {}×)", f.displayNameCap(), Stables.name(h), r.where,
                Stables.what(h), String.format(java.util.Locale.ROOT, "%.2f", quick(h)));
        }
        f.brain("up on " + Stables.name(h));
    }

    private static void bump(Map<UUID, long[]> m, UUID key, long day) {
        long[] t = m.computeIfAbsent(key, k -> new long[]{ day, 0 });
        if (t[0] != day) { t[0] = day; t[1] = 0; }
        t[1]++;
    }

    /** Rides out today, for the village's books. */
    public static int ridesToday(ServerLevel level, UUID village) {
        long[] t = TODAY.get(village);
        return t == null || t[0] != level.getDayTime() / 24000L ? 0 : (int) t[1];
    }

    // ------------------------------------------------------------------ every tick or two

    /** Busy fetching a horse, riding one back, or putting one (or a pack donkey) away: the day's work waits. */
    public static boolean busy(VillageFolkEntity f) {
        Ride r = RIDES.get(f.getUUID());
        if (r != null) return r.phase == Phase.WALK || r.phase == Phase.BACK || r.phase == Phase.HOME;
        Pack p = PACKS.get(f.getUUID());
        return p != null && p.stage == PackStage.HOME;
    }

    /** Leading a pack animal (Drover.tidy leaves its lead alone). */
    public static boolean leading(VillageFolkEntity f) {
        return PACKS.containsKey(f.getUUID());
    }

    /**
     * From the folk's tick, every other tick: a ridden horse kept at its pace, and the ride watched —
     * a rider thrown is a horse tied where it stood; a courier whose run is over, or at dusk, takes
     * it back; a horse left tied at the storehouse a minute with no long run for it goes home. A
     * folk found on a horse of the village's with no ride (a restart keeps the rider on its horse,
     * not the ride) puts it away.
     */
    public static void tick(VillageFolkEntity f, ServerLevel level) {
        Ride r = RIDES.get(f.getUUID());
        long now = level.getGameTime();
        if (r == null) {
            if (f.getVehicle() instanceof AbstractHorse h && h.isTamed() && f.ownerId() != null && f.ownerId().equals(Stables.villageOf(h))
                    && !Stables.busy(f)) {
                Ride back = new Ride(f.getUUID(), h.getUUID(), f.ownerId(), Why.STRAY, now);
                back.phase = Phase.HOME;
                RIDES.put(f.getUUID(), back);
                reach(h, true);
            }
            return;
        }
        AbstractHorse h = horse(level, r);
        if (h == null) {
            end(f, level, r, "the horse was gone");
            return;
        }
        boolean on = f.getVehicle() == h;
        if (on) {
            h.setEating(false);
            if (!h.getNavigation().isDone()) {
                BlockPos target = h.getNavigation().getTargetPos();
                boolean nearStall = r.phase == Phase.HOME && target != null && target.distSqr(h.blockPosition()) < 12 * 12;
                h.getNavigation().setSpeedModifier(nearStall ? 1.0D : gallop(h));
            }
            // Too low overhead for a rider (the horse's way is planned for the horse, not for the one on
            // its back): down at once, before it is hurt, and the horse tied where it stands.
            if (lowOverhead(level, f)) {
                hitch(f, level, r, h, Phase.HITCHED);
                f.brain("too low to ride under: got down");
                return;
            }
        }
        if (busy(f) || f.peekJob() != null) return;
        boolean late = late(level, r.village);
        switch (r.phase) {
            case OUT -> {
                if (!on) {
                    // Thrown, or got down in deep water: the horse stands where it is, and is got back on.
                    if (r.why == Why.SCOUT && f.distanceToSqr(h) < 16 * 16 && !h.isInWater()) {
                        r.phase = Phase.WALK;
                        r.then = Phase.OUT;
                        r.since = now;
                    } else {
                        hitch(f, level, r, h, Phase.HITCHED);
                    }
                    return;
                }
                if (r.why == Why.SCOUT) {
                    if (f.expedition() == null) home(f, r, now);
                } else if (r.why == Why.COURIER) {
                    if (late || !Couriers.onARun(f)) back(f, r, now, late);
                    else if (r.dest != null && flat(f.position(), r.dest) <= 5 * 5) hitch(f, level, r, h, Phase.HITCHED);
                }
            }
            case HITCHED -> {
                if (f.distanceToSqr(h) > 96 * 96 && (r.why != Why.SCOUT || f.expedition() == null)) {
                    loose(level, r, h);
                    end(f, level, r, "too far from the horse to go back for it");
                    return;
                }
                if (r.why == Why.STRAY) {
                    // The rancher could not ride it in: it is coaxed in by hand another time.
                    loose(level, r, h);
                    end(f, level, r, "could not ride " + Stables.name(h) + " in");
                    return;
                }
                if (r.why == Why.SCOUT) {
                    // Left where the way was too low to ride: picked up again on the way home, along its own trail.
                    if (f.expedition() == null) {
                        r.phase = Phase.HOME;
                        r.since = now;
                    } else if (f.expedition().returning() && f.distanceToSqr(h) < 16 * 16 && !lowOverhead(level, h)) {
                        r.phase = Phase.WALK;
                        r.then = Phase.OUT;
                        r.since = now;
                    }
                    return;
                }
                if (late || !Couriers.onARun(f)) back(f, r, now, late);
            }
            case PARKED -> {
                if (f.distanceToSqr(h) > 96 * 96) {
                    loose(level, r, h);
                    end(f, level, r, "too far from the horse to go back for it");
                    return;
                }
                boolean idle = !Couriers.onARun(f) && (now - r.since > parked || now < r.since);
                if (late || idle || !Couriers.employed(f) || f.offWorkNow()) home(f, r, now);
            }
            default -> { }
        }
    }

    /** Something solid where a rider's head is (or would be, on this horse's back): a beam, a branch, a low roof. */
    static boolean lowOverhead(ServerLevel level, Entity e) {
        BlockPos head = e instanceof AbstractHorse ? e.blockPosition().above(2) : e.blockPosition().above();
        return level.getBlockState(head).isSuffocating(level, head) || (e instanceof VillageFolkEntity f && f.isInWall());
    }

    private static void back(VillageFolkEntity f, Ride r, long now, boolean late) {
        r.phase = late ? Phase.HOME : Phase.BACK;
        r.since = now;
        r.best = Double.MAX_VALUE;
        r.progress = now;
        r.walked = -1000;
    }

    private static void home(VillageFolkEntity f, Ride r, long now) {
        r.phase = Phase.HOME;
        r.since = now;
        r.best = Double.MAX_VALUE;
        r.progress = now;
        r.walked = -1000;
        f.brain("taking the horse home to the stable");
    }

    /** A step of a busy ride (every quarter-second): walking to the horse and getting up, riding it back to the storehouse, or home to its stall. */
    static void drive(VillageFolkEntity f, ServerLevel level) {
        Ride r = RIDES.get(f.getUUID());
        if (r == null) {
            Pack p = PACKS.get(f.getUUID());
            if (p != null && p.stage == PackStage.HOME) packHome(f, level, p);
            return;
        }
        long now = level.getGameTime();
        AbstractHorse h = horse(level, r);
        if (h == null) {
            end(f, level, r, "the horse was gone");
            return;
        }
        Stables.Stable st = Stables.stableNow(r.village, now);
        boolean on = f.getVehicle() == h;
        if (st != null && (flat(f.position(), st.door()) < 12 * 12 || flat(h.position(), st.door()) < 12 * 12)) {
            Stables.openGates(level, r.village, st);
        }
        if (!on) {
            // To the horse, and up.
            if (now - r.since > 900 || now < r.since) {
                NOT_BEFORE.put(f.getUUID(), now + 2400);
                if (r.phase != Phase.WALK || r.then != Phase.OUT) loose(level, r, h);
                end(f, level, r, "could not get to " + Stables.name(h));
                return;
            }
            if (f.distanceToSqr(h) <= 2.4 * 2.4) {
                if (lowOverhead(level, h)) {
                    // Too low there to get up on it: left for the rancher, who coaxes it out with a bite in its hand.
                    loose(level, r, h);
                    end(f, level, r, "too low to get up on " + Stables.name(h) + " there");
                    return;
                }
                if (mount(f, h)) {
                    if (r.phase == Phase.WALK) started(f, level, r, h);
                    else { r.best = Double.MAX_VALUE; r.progress = now; r.walked = -1000; }
                }
                return;
            }
            if (f.getNavigation().isDone() || f.tickCount - r.walked > 40) {
                f.walkTo(h.blockPosition(), 1.0D);
                r.walked = f.tickCount;
            }
            return;
        }
        if (r.phase == Phase.WALK) {
            started(f, level, r, h);
            return;
        }
        // On it: back to the storehouse, or home to its stall.
        Vec3 goal;
        if (r.phase == Phase.HOME) {
            if (r.stall == null) {
                if (st != null) r.stall = Stables.placeOf(st, r.village, Stables.byStall(Stables.animals(level, r.village)), h);
                else {
                    BlockPos home = Stables.home(r.village);
                    r.stall = home == null ? h.position() : Vec3.atBottomCenterOf(home);
                }
            }
            goal = r.stall;
        } else {
            BlockPos base = Couriers.base(level, r.village);
            goal = base == null ? h.position() : Vec3.atBottomCenterOf(base);
        }
        double d = Stables.flat(h.position(), goal);
        boolean there = r.phase == Phase.HOME ? d <= (st != null ? 1.3 : 3.5) : d <= 6.0;
        if (there) {
            if (r.phase == Phase.HOME) {
                getOff(f, h);
                h.restrictTo(BlockPos.containing(goal), st != null ? 0 : 3);
                end(f, level, r, "put " + Stables.name(h) + " back in its stall");
                if (level.getRandom().nextInt(3) == 0) {
                    FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "There you go, " + Stables.name(h) + ". Hay's in the rack.",
                        "Back in your stall, " + Stables.name(h) + ". Good horse."));
                }
            } else {
                hitch(f, level, r, h, Phase.PARKED);
            }
            return;
        }
        if (d < r.best - 0.5) {
            r.best = d;
            r.progress = now;
        } else if (now - r.progress > 400 || now < r.progress) {
            // No nearer in twenty seconds: down here, and on foot.
            if (r.phase == Phase.HOME) {
                getOff(f, h);
                end(f, level, r, "could not ride it right in: left it by the stable");
            } else {
                hitch(f, level, r, h, Phase.PARKED);
            }
            return;
        }
        if (h.getNavigation().isDone() || f.tickCount - r.walked > 100) {
            if (r.phase == Phase.HOME) Stables.walkInto(f.getNavigation(), Stables.walkTarget(st, goal), 1.0D);
            else f.getNavigation().moveTo(goal.x, goal.y, goal.z, 1.0D);
            r.walked = f.tickCount;
        }
    }

    /** Has the village a horse out with a rider, or a donkey out with a caravan? */
    static boolean anyOf(UUID village) {
        for (Ride r : RIDES.values()) if (r.village.equals(village)) return true;
        for (Pack p : PACKS.values()) if (p.village.equals(village)) return true;
        return false;
    }

    /** A folk of the village on its way through the stable door (fetching a horse, or taking one or a donkey home)? */
    static boolean comingThrough(ServerLevel level, UUID village, BlockPos door) {
        for (Ride r : RIDES.values()) {
            if (!r.village.equals(village) || !(r.phase == Phase.WALK || r.phase == Phase.HOME || r.phase == Phase.OUT)) continue;
            Entity f = level.getEntity(r.folk);
            if (f != null && flat(f.position(), door) < 10 * 10) return true;
        }
        for (Map.Entry<UUID, Pack> e : PACKS.entrySet()) {
            if (!e.getValue().village.equals(village) || e.getValue().stage == PackStage.ROAD) continue;
            Entity f = level.getEntity(e.getKey());
            if (f != null && flat(f.position(), door) < 10 * 10) return true;
        }
        return false;
    }

    /** Somebody gone: a horse it had out, or a donkey on its lead, is left where it is, for the stable to bring in. */
    public static void fell(ServerLevel level, VillageFolkEntity f) {
        Ride r = RIDES.remove(f.getUUID());
        if (r != null) {
            AbstractHorse h = horse(level, r);
            if (h != null) loose(level, r, h);
        }
        caravanLost(f, level);
    }

    /** Rides whose rider is no longer about (died, or left the world): the horse let go for the stable to bring in. */
    static void sweep(ServerLevel level, UUID village) {
        for (Map.Entry<UUID, Ride> e : RIDES.entrySet()) {
            Ride r = e.getValue();
            if (!r.village.equals(village)) continue;
            Entity f = level.getEntity(r.folk);
            if (f != null && f.isAlive()) continue;
            if (level.getGameTime() - r.since < 200) continue;          // a rider in ground that is not loaded just now
            RIDES.remove(e.getKey());
            AbstractHorse h = horse(level, r);
            if (h != null) loose(level, r, h);
        }
    }

    // ------------------------------------------------------------------ couriers

    /**
     * Couriers.walk: a long run on horseback. With a horse out, ridden on to within a few steps of
     * the place (then down, and on foot); with one tied by the storehouse, got back on for another
     * long run; with none, one fetched from the stable if the run is long and a horse is free and
     * worth the walk to the stable. True while the horse has the walking of it.
     */
    static boolean courier(VillageFolkEntity c, ServerLevel level, BlockPos to, Couriers.Run run) {
        Ride r = RIDES.get(c.getUUID());
        long now = level.getGameTime();
        UUID v = c.ownerId();
        if (v == null) return false;
        if (r != null) {
            if (r.why != Why.COURIER) return false;
            switch (r.phase) {
                case WALK, BACK, HOME -> { return true; }
                case HITCHED -> { return false; }
                case PARKED -> {
                    if (late(level, v) || Math.sqrt(flat(c.position(), to)) < LONG || run.kind == Couriers.Kind.OLD_CHEST) return false;
                    AbstractHorse h = horse(level, r);
                    if (h == null) { end(c, level, r, "the horse was gone"); return false; }
                    r.dest = to.immutable();
                    r.where = where(level, v, to, run);
                    r.phase = Phase.WALK;
                    r.then = Phase.OUT;
                    r.since = now;
                    r.walked = -1000;
                    c.brain("back up on " + Stables.name(h) + " for another long run");
                    return true;
                }
                default -> {
                    AbstractHorse h = horse(level, r);
                    if (h == null || c.getVehicle() != h) return false;
                    if (r.dest == null || !r.dest.equals(to)) {
                        r.dest = to.immutable();
                        r.where = where(level, v, to, run);
                        r.best = Double.MAX_VALUE;
                        r.progress = now;
                    }
                    double d = flat(c.position(), to);
                    if (d <= 5 * 5) {
                        hitch(c, level, r, h, Phase.HITCHED);
                        return false;
                    }
                    if (d < r.best - 1.0) {
                        r.best = d;
                        r.progress = now;
                    } else if (now - r.progress > 400 || now < r.progress) {
                        hitch(c, level, r, h, Phase.HITCHED);
                        c.brain("could not ride any nearer: on foot from here");
                        return false;
                    }
                    if (h.getNavigation().isDone() || c.tickCount - r.walked > 100) {
                        c.walkTo(to, 1.1D);
                        r.walked = c.tickCount;
                    }
                    return true;
                }
            }
        }
        // No horse yet. Worth fetching one?
        if (run.kind == Couriers.Kind.OLD_CHEST || late(level, v) || level.getDayTime() % 24000L > 10000) return false;
        if (NOT_BEFORE.getOrDefault(c.getUUID(), 0L) > now) return false;
        double far = Math.sqrt(flat(c.position(), to));
        if (far < LONG) return false;
        Stables.Stable st = Stables.stableNow(v, now);
        if (st == null || !level.isLoaded(st.anchor())) return false;
        AbstractHorse h = freeHorse(level, v, st);
        if (h == null) return false;
        double toStable = Math.sqrt(flat(c.position(), st.door()));
        double stableTo = Math.sqrt(flat(Vec3.atCenterOf(st.door()), to));
        BlockPos base = Couriers.base(level, v);
        double back = base == null ? far : Math.sqrt(flat(Vec3.atCenterOf(to), base));
        // The walk to the stable and the rest on horseback, out and back to the storehouse, against
        // walking it: worth the horse only if it is quicker so.
        if (toStable + (stableTo + back) / quick(h) >= far + back) return false;
        Ride ride = new Ride(c.getUUID(), h.getUUID(), v, Why.COURIER, now);
        ride.phase = Phase.WALK;
        ride.then = Phase.OUT;
        ride.dest = to.immutable();
        ride.where = where(level, v, to, run);
        RIDES.put(c.getUUID(), ride);
        c.getNavigation().stop();
        c.brain("to the stable for " + Stables.name(h) + ": a long run, " + ride.where);
        if (level.getRandom().nextInt(2) == 0) {
            FolkTalk.speak(c, FolkTalk.pick(level.getRandom(), "That's a long way. I'll take " + Stables.name(h) + ".",
                "Too far to walk — " + Stables.name(h) + " can carry me.", "I'll ride. Back in no time."));
        }
        return true;
    }

    /**
     * Couriers.work and Couriers' loading: the business at the far end done, the courier goes back
     * to its horse and rides it home before it carries the load in. True while that is the work.
     */
    static boolean homeFirst(VillageFolkEntity c, ServerLevel level) {
        Ride r = RIDES.get(c.getUUID());
        if (r == null || r.why != Why.COURIER) return false;
        if (r.phase == Phase.OUT || r.phase == Phase.HITCHED) {
            if (horse(level, r) == null) {
                end(c, level, r, "the horse was gone");
                return false;
            }
            back(c, r, level.getGameTime(), late(level, r.village));
            c.brain("back up on the horse, and home with the load");
            return true;
        }
        return r.phase == Phase.WALK || r.phase == Phase.BACK || r.phase == Phase.HOME;
    }

    /** "to the north mine": which way from the storehouse, and whose place. */
    static String where(ServerLevel level, UUID village, BlockPos to, Couriers.Run run) {
        BlockPos from = Couriers.base(level, village);
        if (from == null) from = to;
        String place;
        if (run.kind == Couriers.Kind.SMELTER) place = "smeltery";
        else {
            VillageFolkEntity w = run.folk != null && level.getEntity(run.folk) instanceof VillageFolkEntity x ? x : null;
            StationTask t = w == null ? StationTask.NONE : w.stationTask();
            place = switch (t) {
                case MINE -> "mine";
                case FARM -> "fields";
                case WOOD -> "woods";
                case FISH -> "fishing water";
                case RANCH -> "pen";
                case HUNT -> "hunting grounds";
                case BEEKEEP -> "hives";
                case SMELT -> "smeltery";
                default -> run.kind == Couriers.Kind.FURNACE ? "furnaces" : "chest";
            };
        }
        return "to the " + Guide.direction(from, to) + " " + place;
    }

    // ------------------------------------------------------------------ strays

    /** The rancher (Stables): a saddled horse of the village's that strayed, walked up to, got on, and ridden home to its stall. */
    static boolean fetchStray(VillageFolkEntity f, ServerLevel level, AbstractHorse h) {
        UUID v = f.ownerId();
        if (v == null || RIDES.containsKey(f.getUUID()) || taken(h.getUUID()) || !h.isSaddled() || h.isVehicle()) return false;
        Ride r = new Ride(f.getUUID(), h.getUUID(), v, Why.STRAY, level.getGameTime());
        r.phase = Phase.WALK;
        r.then = Phase.HOME;
        RIDES.put(f.getUUID(), r);
        f.clearQueue();
        f.getNavigation().stop();
        f.brain("off to ride " + Stables.name(h) + " home to the stable");
        return true;
    }

    // ------------------------------------------------------------------ scouts

    /**
     * Scouts.drive: a scout setting out takes a saddled horse from the stable, if there is one, and
     * rides its rounds; one thrown on the way gets back on. On the way, an old chest found out in the
     * world is looked into for a saddle (or a lead) for the stable. True while that is the work.
     */
    public static boolean scout(VillageFolkEntity f, ServerLevel level, Scouts.Expedition e) {
        if (oldChests(f, level, e)) return true;
        Ride r = RIDES.get(f.getUUID());
        if (r != null) return r.why == Why.SCOUT && busy(f);
        UUID v = f.ownerId();
        long now = level.getGameTime();
        if (v == null || e.returning() || f.tickCount - e.startedTick > 200 || late(level, v)) return false;
        if (NOT_BEFORE.getOrDefault(f.getUUID(), 0L) > now) return false;
        Stables.Stable st = Stables.stableNow(v, now);
        if (st == null || !level.isLoaded(st.anchor()) || flat(f.position(), st.door()) > 64 * 64) return false;
        AbstractHorse h = freeHorse(level, v, st);
        if (h == null) return false;
        Ride ride = new Ride(f.getUUID(), h.getUUID(), v, Why.SCOUT, now);
        ride.phase = Phase.WALK;
        ride.then = Phase.OUT;
        ride.where = "out scouting " + e.heading();
        RIDES.put(f.getUUID(), ride);
        f.getNavigation().stop();
        FolkTalk.speak(f, "I'll take " + Stables.name(h) + " — we'll see twice the country on horseback.");
        f.brain("to the stable for " + Stables.name(h) + " before setting out");
        return true;
    }

    /** Put down over a bad patch of ground (Scouts): the horse and its rider together. */
    public static void carry(VillageFolkEntity f, double x, double y, double z) {
        if (f.getVehicle() instanceof AbstractHorse h) {
            h.teleportTo(x, y, z);
            return;
        }
        f.moveTo(x, y, z, f.getYRot(), 0.0F);
    }

    /** Where a scout is going to look in an old chest, and the chests it has looked in. */
    private static final Map<UUID, long[]> CHEST_TO = new ConcurrentHashMap<>();
    private static final Set<Long> LOOKED_IN = ConcurrentHashMap.newKeySet();

    /** An old chest out in the world (a ruin's, a temple's, a village of villagers'), not yet opened, near the scout: a look in it. */
    static boolean oldChests(VillageFolkEntity f, ServerLevel level, Scouts.Expedition e) {
        long[] going = CHEST_TO.get(f.getUUID());
        long now = level.getGameTime();
        if (going == null) {
            if (e.returning() || f.tickCount % 40 != 0) return false;
            BlockPos c = findChest(level, f.blockPosition());
            if (c == null) return false;
            CHEST_TO.put(f.getUUID(), new long[]{ c.asLong(), now });
            f.brain("an old chest over there: a look in it");
            return true;
        }
        BlockPos c = BlockPos.of(going[0]);
        if (!level.isLoaded(c) || now - going[1] > 400 || now < going[1] || f.expedition() != e) {
            LOOKED_IN.add(c.asLong());
            CHEST_TO.remove(f.getUUID());
            return false;
        }
        if (f.getEyePosition().distanceToSqr(Vec3.atCenterOf(c)) > AssistantEntity.BLOCK_REACH * AssistantEntity.BLOCK_REACH) {
            if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.walkTo(c, 1.0D);
            return true;
        }
        LOOKED_IN.add(c.asLong());
        CHEST_TO.remove(f.getUUID());
        BlockEntity be = level.getBlockEntity(c);
        if (!(be instanceof Container box)) return false;
        if (be instanceof RandomizableContainerBlockEntity loot) loot.unpackLootTable(null);
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        int saddles = 0, leads = 0;
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack s = box.getItem(i);
            if (s.isEmpty() || !(s.is(Items.SADDLE) || s.is(Items.LEAD))) continue;
            int n = s.getCount();
            ItemStack left = f.insertItem(s.copy());
            int took = n - left.getCount();
            if (s.is(Items.SADDLE)) saddles += took; else leads += took;
            box.setItem(i, left);
        }
        box.setChanged();
        if (saddles + leads > 0) {
            FolkTalk.speak(f, saddles > 0 ? "A saddle in this old chest! That's coming home for the stable."
                : "Leads, in an old chest. The rancher will be glad of those.");
            LOG.info("[MCA-SCOUT] {} found {} saddles and {} leads in an old chest at {}", f.displayNameCap(), saddles, leads, c.toShortString());
        } else {
            f.brain("nothing for the stable in the old chest");
        }
        return true;
    }

    /** An unopened chest with the world's loot in it, within sixteen blocks, nowhere near a village. */
    @Nullable
    static BlockPos findChest(ServerLevel level, BlockPos at) {
        for (Villages.Village v : Villages.every()) {
            if (v.dim().equals(level.dimension()) && v.centre().distSqr(at) < 128 * 128) return null;
        }
        BlockPos best = null;
        double near = 16 * 16;
        for (int cx = (at.getX() >> 4) - 1; cx <= (at.getX() >> 4) + 1; cx++) {
            for (int cz = (at.getZ() >> 4) - 1; cz <= (at.getZ() >> 4) + 1; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof RandomizableContainerBlockEntity loot) || loot.getLootTable() == null) continue;
                    BlockPos p = be.getBlockPos();
                    if (LOOKED_IN.contains(p.asLong())) continue;
                    // [emerald] A village of villagers' chests are the villagers': a scout walking through takes nothing.
                    if (VanillaVillages.within(level, p.getX(), p.getZ(), 4)) continue;
                    if (VanillaVillages.sawFrom(level, p) != null && VanillaVillages.within(level, p.getX(), p.getZ(), 4)) continue;
                    double d = p.distSqr(at);
                    if (d < near) { near = d; best = p.immutable(); }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the caravans' donkeys

    enum PackStage { FETCH, ROAD, HOME }

    /** A carrier's pack animal: which, and where it has got to. */
    static final class Pack {
        final UUID donkey, village;
        PackStage stage = PackStage.FETCH;
        long since;
        int walked = -1000;
        long waited = -1;

        Pack(UUID donkey, UUID village, long now) {
            this.donkey = donkey;
            this.village = village;
            this.since = now;
        }
    }

    static final Map<UUID, Pack> PACKS = new ConcurrentHashMap<>();

    @Nullable
    static AbstractChestedHorse donkey(ServerLevel level, Pack p) {
        return level.getEntity(p.donkey) instanceof AbstractChestedHorse d && d.isAlive() ? d : null;
    }

    /**
     * Caravans.setOut: a donkey (or a mule) with a chest on it from the stable to carry the load,
     * if the village has one standing free and a lead for it (the carrier's own, or one from the
     * stores). The carrier fetches it before it sets off.
     */
    public static void packFor(ServerLevel level, Villages.Village v, VillageFolkEntity carrier) {
        if (PACKS.containsKey(carrier.getUUID()) || RIDES.containsKey(carrier.getUUID())) return;
        Stables.Stable st = Stables.stable(v.id());
        BlockPos home = st != null ? st.anchor() : Stables.home(v.id());
        if (home == null || !level.isLoaded(home)) return;
        AbstractChestedHorse best = null;
        for (AbstractChestedHorse d : level.getEntitiesOfClass(AbstractChestedHorse.class, new AABB(home).inflate(12, 4, 12),
                x -> Stables.kind(x) && Stables.ours(x, v.id()) && x.hasChest() && !x.isBaby() && !x.isLeashed() && !x.isVehicle()
                    && !taken(x.getUUID()) && !Stables.handled(x.getUUID()))) {
            if (best == null || d.distanceToSqr(carrier) < best.distanceToSqr(carrier)) best = d;
        }
        if (best == null) return;
        if (carrier.countCarried(s -> s.is(Items.LEAD)) < 1) {
            if (!Crafts.take(level, v, s -> s.is(Items.LEAD), 1)) return;
            ItemStack left = carrier.insertItem(new ItemStack(Items.LEAD));
            if (!left.isEmpty()) { Crafts.store(level, v, left); return; }
        }
        PACKS.put(carrier.getUUID(), new Pack(best.getUUID(), v.id(), level.getGameTime()));
        carrier.brain("to the stable for " + Stables.name(best) + ", to carry the caravan's load");
        LOG.info("[MCA-CARAVAN] {} of {} takes {} ({}) for the caravan's load", carrier.displayNameCap(), Villages.name(v.id()),
            Stables.name(best), Stables.what(best));
    }

    /**
     * Caravans.drive: the pack donkey fetched, tied and loaded before the road, and kept up with on
     * it (a lead that slips is picked up and tied again; one that lags is waited for). True while
     * that is the walking.
     */
    public static boolean caravan(VillageFolkEntity f, ServerLevel level, Caravans.Trip t) {
        return packAlong(f, level, Villages.name(t.destination()));
    }

    /** [emerald] As caravan, for any walk with a pack donkey: a caravan's, or the emerald trader's to a village of villagers. */
    public static boolean packAlong(VillageFolkEntity f, ServerLevel level, String to) {
        Pack p = PACKS.get(f.getUUID());
        if (p == null || p.stage == PackStage.HOME) return false;
        long now = level.getGameTime();
        AbstractChestedHorse d = donkey(level, p);
        if (d == null) {
            PACKS.remove(f.getUUID());
            f.brain("lost the pack donkey");
            LOG.info("[MCA-CARAVAN] {} lost its pack donkey on the way to {}", f.displayNameCap(), to);
            return false;
        }
        if (p.stage == PackStage.FETCH) {
            if (d.isLeashed() && d.getLeashHolder() == f) {
                load(f, d);
                p.stage = PackStage.ROAD;
                return false;
            }
            if (now - p.since > 1200 || now < p.since) {
                PACKS.remove(f.getUUID());
                f.brain("could not get the pack donkey: the load on my own back");
                return false;
            }
            Stables.Stable st = Stables.stableNow(p.village, now);
            if (st != null && (flat(f.position(), st.door()) < 12 * 12 || flat(d.position(), st.door()) < 12 * 12)) {
                Stables.openGates(level, p.village, st);
            }
            if (f.distanceToSqr(d) <= 2.5 * 2.5) {
                if (f.removeMatching(s -> s.is(Items.LEAD), 1) < 1) {
                    PACKS.remove(f.getUUID());
                    return false;
                }
                d.clearRestriction();
                d.getNavigation().stop();
                d.setLeashedTo(f, true);
                int n = load(f, d);
                p.stage = PackStage.ROAD;
                FolkTalk.speak(f, "Come on, " + Stables.name(d) + ". A long road, and you're carrying the load.");
                f.brain("the caravan's load in " + Stables.name(d) + "'s chest: " + n + " goods");
                return true;
            }
            if (f.getNavigation().isDone() || f.tickCount - p.walked > 40) {
                f.walkTo(d.blockPosition(), 1.0D);
                p.walked = f.tickCount;
            }
            return true;
        }
        // On the road.
        if (!d.isLeashed() || d.getLeashHolder() != f) {
            pickUpLeads(f, level, d.blockPosition());
            if (f.distanceToSqr(d) <= 2.5 * 2.5 && f.removeMatching(s -> s.is(Items.LEAD), 1) == 1) {
                d.setLeashedTo(f, true);
                return true;
            }
            if (f.countCarried(s -> s.is(Items.LEAD)) < 1) {
                // No lead to tie it again: the load onto its own back, and the donkey left for the stable to bring in.
                unpack(f, level);
                PACKS.remove(f.getUUID());
                f.brain("the pack donkey slipped its lead: the load on my own back");
                return false;
            }
            if (f.getNavigation().isDone() || f.tickCount - p.walked > 40) {
                f.walkTo(d.blockPosition(), 1.0D);
                p.walked = f.tickCount;
            }
            return true;
        }
        if (f.distanceToSqr(d) > 6.0 * 6.0) {
            // It lags: wait for it to come up.
            f.getNavigation().stop();
            f.getLookControl().setLookAt(d);
            d.getNavigation().moveTo(f, 1.3D);
            if (p.waited < 0) p.waited = now;
            return true;
        }
        p.waited = -1;
        return false;
    }

    /** The caravan set down over a bad patch of the way (Caravans): its donkey with it, so the lead holds. */
    public static void bringAlong(VillageFolkEntity f, ServerLevel level, double x, double y, double z) {
        Pack p = PACKS.get(f.getUUID());
        if (p == null || p.stage != PackStage.ROAD) return;
        AbstractChestedHorse d = donkey(level, p);
        if (d != null && d.isLeashed() && d.getLeashHolder() == f) d.teleportTo(x + 1.5, y, z + 0.5);
    }

    /** The carrier's goods into the donkey's chest: what the market deals in, less what a carrier keeps by it. Returns how many. */
    static int load(VillageFolkEntity f, AbstractChestedHorse d) {
        if (!d.hasChest()) return 0;
        Container box = d.getInventory();
        int moved = 0;
        var inv = f.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || Market.goodFor(s) == null) continue;
            int move = s.getCount() - Caravans.carrierKeeps(f, s);
            if (move <= 0) continue;
            ItemStack left = put(box, s.copyWithCount(move));
            int in = move - left.getCount();
            if (in <= 0) continue;
            s.shrink(in);
            if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
            moved += in;
        }
        box.setChanged();
        return moved;
    }

    /** Into the chest slots of a donkey's inventory: onto the part stacks first. What does not fit. */
    static ItemStack put(Container box, ItemStack stack) {
        ItemStack s = stack.copy();
        for (int i = AbstractHorse.INV_BASE_COUNT; i < box.getContainerSize() && !s.isEmpty(); i++) {
            ItemStack in = box.getItem(i);
            if (in.isEmpty() || !ItemStack.isSameItemSameComponents(in, s)) continue;
            int k = Math.min(s.getCount(), in.getMaxStackSize() - in.getCount());
            if (k <= 0) continue;
            in.grow(k);
            s.shrink(k);
        }
        for (int i = AbstractHorse.INV_BASE_COUNT; i < box.getContainerSize() && !s.isEmpty(); i++) {
            if (!box.getItem(i).isEmpty()) continue;
            box.setItem(i, s.copy());
            s = ItemStack.EMPTY;
        }
        return s;
    }

    /** Caravans.arrive: the load out of the donkey's chest onto the carrier, to be sold (what does not fit stays in the chest). */
    public static void unpack(VillageFolkEntity f, ServerLevel level) {
        Pack p = PACKS.get(f.getUUID());
        AbstractChestedHorse d = p == null ? null : donkey(level, p);
        if (d == null || !d.hasChest()) return;
        Container box = d.getInventory();
        for (int i = AbstractHorse.INV_BASE_COUNT; i < box.getContainerSize(); i++) {
            ItemStack s = box.getItem(i);
            if (s.isEmpty()) continue;
            box.setItem(i, f.insertGiven(s.copy()));
        }
        box.setChanged();
    }

    /** Caravans.arrive: the goods for home (and what was not sold) back into the chest. */
    public static void pack(VillageFolkEntity f, ServerLevel level) {
        Pack p = PACKS.get(f.getUUID());
        AbstractChestedHorse d = p == null ? null : donkey(level, p);
        if (d == null) return;
        int n = load(f, d);
        if (n > 0) f.brain("the goods for home in " + Stables.name(d) + "'s chest: " + n);
    }

    /** Caravans.arrive, home: the donkey led back to the stable (its load already in the stores). */
    public static void caravanHome(VillageFolkEntity f, ServerLevel level) {
        Pack p = PACKS.get(f.getUUID());
        if (p == null) return;
        AbstractChestedHorse d = donkey(level, p);
        if (d == null || !d.isLeashed() || d.getLeashHolder() != f) {
            PACKS.remove(f.getUUID());
            return;
        }
        p.stage = PackStage.HOME;
        p.since = level.getGameTime();
        p.walked = -1000;
        f.brain("leading " + Stables.name(d) + " home to the stable");
    }

    /** A carrier lost on the road (Caravans.abandon) or gone: its donkey let off the lead where it is. */
    public static void caravanLost(VillageFolkEntity f, ServerLevel level) {
        Pack p = PACKS.remove(f.getUUID());
        if (p == null) return;
        AbstractChestedHorse d = donkey(level, p);
        if (d != null && d.isLeashed() && d.getLeashHolder() == f) d.dropLeash(true, true);
    }

    /** Home with the donkey: into the stable, off the lead, and the lead hung up with the stores. */
    private static void packHome(VillageFolkEntity f, ServerLevel level, Pack p) {
        AbstractChestedHorse d = donkey(level, p);
        long now = level.getGameTime();
        if (d == null || now - p.since > 1200 || now < p.since || !d.isLeashed() || d.getLeashHolder() != f) {
            untie(f, level, p, d);
            return;
        }
        Stables.Stable st = Stables.stableNow(p.village, now);
        BlockPos goal = st != null ? st.aisle() : Stables.home(p.village);
        if (goal == null) {
            untie(f, level, p, d);
            return;
        }
        if (st != null && (flat(f.position(), st.door()) < 12 * 12 || flat(d.position(), st.door()) < 12 * 12)) {
            Stables.openGates(level, p.village, st);
        }
        if (flat(d.position(), goal) <= 3.0 * 3.0) {
            untie(f, level, p, d);
            if (level.getRandom().nextInt(2) == 0) FolkTalk.speak(f, "Home, " + Stables.name(d) + ". You've earned your hay.");
            return;
        }
        if (f.distanceToSqr(d) > 5.0 * 5.0) {
            f.getNavigation().stop();
            f.getLookControl().setLookAt(d);
            return;
        }
        if (f.getNavigation().isDone() || f.tickCount - p.walked > 40) {
            f.walkTo(goal, 0.8D);
            p.walked = f.tickCount;
        }
    }

    private static void untie(VillageFolkEntity f, ServerLevel level, Pack p, @Nullable AbstractChestedHorse d) {
        PACKS.remove(f.getUUID());
        if (d == null || !d.isLeashed() || d.getLeashHolder() != f) return;
        d.dropLeash(true, false);
        ItemStack lead = Market.intoStores(level, p.village, new ItemStack(Items.LEAD));
        if (!lead.isEmpty()) {
            ItemStack left = f.insertItem(lead);
            if (!left.isEmpty()) f.spawnAtLocation(left);
        }
        f.brain(Stables.name(d) + " home, and off the lead");
    }

    /** Leads lying about a spot (one that slipped), back into the pack. */
    private static void pickUpLeads(VillageFolkEntity f, ServerLevel level, BlockPos at) {
        for (net.minecraft.world.entity.item.ItemEntity lead : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new AABB(at).inflate(12), i -> i.isAlive() && i.getItem().is(Items.LEAD))) {
            ItemStack left = f.insertItem(lead.getItem().copy());
            if (left.isEmpty()) lead.discard();
            else lead.setItem(left);
        }
    }

    // ------------------------------------------------------------------ shown

    /** What it is doing on (or with) a horse this minute: "Riding Bay to the north mine". Null if nothing. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level)) return null;
        Ride r = RIDES.get(f.getUUID());
        if (r == null) {
            Pack p = PACKS.get(f.getUUID());
            if (p == null) return null;
            AbstractChestedHorse d = donkey(level, p);
            String n = d == null ? "the pack donkey" : Stables.name(d);
            return switch (p.stage) {
                case FETCH -> "Fetching " + n + " from the stable, to carry the caravan's load";
                case ROAD -> "Leading " + n + " with the caravan" + (f.trip() != null ? " to " + Villages.name(f.trip().destination()) : "")
                    + ", the load in its chest";
                case HOME -> "Leading " + n + " home to the stable";
            };
        }
        AbstractHorse h = horse(level, r);
        String n = h == null ? "the horse" : Stables.name(h);
        return switch (r.phase) {
            case WALK -> r.then == Phase.OUT ? "Fetching " + n + " from the stable, to ride " + r.where : "Going to " + n + ", to ride it home";
            case OUT -> "Riding " + n + " " + r.where;
            case HITCHED -> "On foot for a moment; " + n + " is tied up nearby";
            case BACK -> "Riding " + n + " back to the storehouse";
            case PARKED -> n + " is tied up by the storehouse, for the next long run";
            case HOME -> "Riding " + n + " home to the stable";
        };
    }

    /** What a horse out of the stable is doing, for the books: "out with Holt, to the north mine". */
    static String horseDoing(ServerLevel level, AbstractHorse h) {
        for (Ride r : RIDES.values()) {
            if (!r.horse.equals(h.getUUID())) continue;
            Entity f = level.getEntity(r.folk);
            String who = f instanceof VillageFolkEntity x ? x.displayNameCap() : "a rider";
            return switch (r.phase) {
                case WALK, OUT, BACK, HOME -> "out with " + who + (r.where.isEmpty() ? "" : ", " + r.where);
                case HITCHED -> "tied up while " + who + " is at work";
                case PARKED -> "tied up by the storehouse for " + who;
            };
        }
        for (Map.Entry<UUID, Pack> e : PACKS.entrySet()) {
            if (!e.getValue().donkey.equals(h.getUUID())) continue;
            return e.getValue().stage == PackStage.ROAD ? "on the road with the caravan" : "with the caravan's carrier";
        }
        return "about";
    }

    /** A line for the rider's card: the horse it has out and how quick it is, and its rides today. Null if none. */
    @Nullable
    static String card(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level)) return null;
        long[] mine = MINE.get(f.getUUID());
        int today = mine == null || mine[0] != level.getDayTime() / 24000L ? 0 : (int) mine[1];
        Ride r = RIDES.get(f.getUUID());
        AbstractHorse h = r == null ? null : horse(level, r);
        if (h == null && today == 0) return null;
        StringBuilder sb = new StringBuilder();
        if (h != null) sb.append("has ").append(Stables.name(h)).append(" out (").append(Stables.what(h)).append(", ").append(paceWords(h)).append(")");
        if (today > 0) sb.append(sb.length() > 0 ? "; " : "").append(today).append(today == 1 ? " ride" : " rides").append(" today");
        return sb.toString();
    }

    // ------------------------------------------------------------------ tests

    /** Tests: a horse left tied at the storehouse goes home after this long with no long run (ticks). */
    public static void parkedForTests(long ticks) {
        parked = ticks;
    }

    /** Tests: the phase of this folk's ride, or null. */
    @Nullable
    public static String phaseForTests(VillageFolkEntity f) {
        Ride r = RIDES.get(f.getUUID());
        return r == null ? null : r.phase.name();
    }

    /** Tests: the pack animal this carrier has, or null. */
    @Nullable
    public static UUID packForTests(VillageFolkEntity f) {
        Pack p = PACKS.get(f.getUUID());
        return p == null ? null : p.donkey;
    }

    /** Tests: the stage of this carrier's pack, or null. */
    @Nullable
    public static String packStageForTests(VillageFolkEntity f) {
        Pack p = PACKS.get(f.getUUID());
        return p == null ? null : p.stage.name();
    }
}
