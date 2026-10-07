package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The archery range [batchC]: a lot of the watch's own just outside a corner of the wall, from the Iron Age,
 * once the town keeps two guards or more.
 *
 * <p>The builders put up what is drawn (blueprints/range.txt): three butts of hay at the back, a wall of boards
 * behind them, a line of stone slabs at the front, eight blocks off. Its keepers see to the butts out of the
 * stores: a bale where one is missing (a bale from the stores, or nine of their wheat), and the top bale of
 * each made into a target with four of the stores' redstone (the target's own recipe); a town with no redstone
 * to spare shoots at the hay.
 *
 * <p><b>Practice.</b> On a working morning a guard not needed elsewhere (no alarm, not walking with the leader,
 * nothing to fight) goes down to the range once a day, up to one a butt and never more than half the watch at
 * once. It takes six arrows out of the stores and its bow (its own, or one borrowed from the stores), toes the
 * line and shoots them one by one at its butt, real arrows flying, holding its fire while anybody is in the
 * way. Each is judged where it stands: in the target (or the top bale) two, in the bale under it one, wide
 * nothing. Then it walks down to the butts, pulls its arrows (and any that went into the boards or the grass)
 * and they go back into the stores; a lost one is lost. A morning at the butts is worth a little to a guard's
 * standing at its trade, the more for a good score.
 *
 * <p><b>The contest.</b> On the day of rest the watch shoots it out: six arrows each, the most points wins,
 * into the chronicle and the books.
 */
public final class Archery {

    private Archery() {}

    public static final String STRUCTURE = "range";
    /** Guards a town keeps before it wants a range. */
    static final int GUARDS = 2;
    /** Practice of a working morning. */
    static final long PRACTICE_FROM = 1500L, PRACTICE_TILL = 4500L;
    /** The contest's morning on the rest day, and how long it may run. */
    static final long CONTEST_AT = 1500L, CONTEST_TIME = 4000L;
    public static final int ARROWS = 6;
    static final String WORKS = "range";

    public static void resetForTests() {
        SESSIONS.clear();
        PRACTISED.clear();
        CONTESTS.clear();
        TENDED.clear();
    }

    /** Does the village want a range: the Iron Age, two guards or more, and none yet. (Villages, with the Iron Age's amenities.) */
    public static boolean wanted(UUID village) {
        return Villages.ageOf(village).ordinal() >= Villages.Age.IRON.ordinal() && guards(village) >= GUARDS
            && !Villages.hasBuilt(village, STRUCTURE) && of(village) == null;
    }

    public static String why(UUID village) {
        return "an archery range by the wall for the watch, three butts and a backstop: " + guards(village)
            + " guards to keep their aim in";
    }

    static int guards(UUID village) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (!a.isBaby() && a.stationTask() == StationTask.GUARD) n++;
        return n;
    }

    @Nullable
    public static Ledger.Building of(@Nullable UUID village) {
        if (village == null) return null;
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals(STRUCTURE)) return b;
        return null;
    }

    static BlockPos at(Ledger.Building b, int dx, int dz) {
        return b.anchor().relative(b.facing().getClockWise(), dx).relative(b.facing(), dz);
    }

    /** One lane: where the archer stands, the target (the butt's top block) and the bale under it. */
    public record Lane(BlockPos stand, BlockPos target, BlockPos hay) {}

    public static List<Lane> lanes(Ledger.Building b) {
        List<Lane> out = new ArrayList<>();
        for (int dx : new int[]{ -3, 0, 3 }) out.add(new Lane(at(b, dx, -4), at(b, dx, 4).above(), at(b, dx, 4)));
        return out;
    }

    // ------------------------------------------------------------------ keeping the butts

    private static final Map<UUID, Long> TENDED = new ConcurrentHashMap<>();

    /** Every quarter of a minute (Sport's clock): a butt made up, or a target made, by a hand of the village out of the stores. */
    public static void tend(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        if (now - TENDED.getOrDefault(v.id(), -100000L) < 300L) return;
        TENDED.put(v.id(), now);
        Ledger.Building b = of(v.id());
        if (b == null || !Land.areaLoaded(level, b.anchor(), 8)) return;
        tendOne(level, v, b);
    }

    /** A bale from the stores, or nine of their wheat made into one. */
    static boolean bale(ServerLevel level, Villages.Village v) {
        return Crafts.take(level, v, s -> s.is(Items.HAY_BLOCK), 1) || Crafts.take(level, v, s -> s.is(Items.WHEAT), 9);
    }

    static boolean baleToHand(ServerLevel level, Villages.Village v) {
        return Market.stock(level, v.id(), s -> s.is(Items.HAY_BLOCK)) > 0 || Market.stock(level, v.id(), s -> s.is(Items.WHEAT)) >= 9;
    }

    /** One butt seen to: a bale where one is missing, else a target on top. What was done, or null. */
    @Nullable
    public static String tendOne(ServerLevel level, Villages.Village v, Ledger.Building b) {
        for (Lane l : lanes(b)) {
            for (BlockPos p : new BlockPos[]{ l.hay(), l.target() }) {
                BlockState s = level.getBlockState(p);
                if (!s.canBeReplaced() || !s.getFluidState().isEmpty()) continue;
                if (p.equals(l.target()) && !level.getBlockState(l.hay()).is(Blocks.HAY_BLOCK)) continue;
                if (!baleToHand(level, v)) return null;
                if (!TownJobs.atWork(level, v, WORKS, p, "making up the butts at the range")) return null;
                if (!bale(level, v)) return null;
                level.setBlock(p, Blocks.HAY_BLOCK.defaultBlockState(), 3);
                level.playSound(null, p, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
                return "made up a butt at the range";
            }
        }
        // The top bale of each butt made a target: the stores' target, or four of their redstone and the bale itself.
        for (Lane l : lanes(b)) {
            if (!level.getBlockState(l.target()).is(Blocks.HAY_BLOCK)) continue;
            boolean ready = Market.stock(level, v.id(), s -> s.is(Items.TARGET)) > 0;
            boolean makings = !ready && Market.stock(level, v.id(), s -> s.is(Items.REDSTONE)) >= 4;
            if (!ready && !makings) return null;
            if (!TownJobs.atWork(level, v, WORKS, l.target(), "putting a target on the butts")) return null;
            if (ready) {
                if (!Crafts.take(level, v, s -> s.is(Items.TARGET), 1)) return null;
                Crafts.store(level, v, new ItemStack(Items.HAY_BLOCK));               // the bale it stood in for
            } else if (!Crafts.take(level, v, s -> s.is(Items.REDSTONE), 4)) {
                return null;
            }
            level.setBlock(l.target(), Blocks.TARGET.defaultBlockState(), 3);
            return "put a target on a butt at the range";
        }
        return null;
    }

    // ------------------------------------------------------------------ a guard at the butts

    static final int TO_LINE = 0, SHOOTING = 1, JUDGING = 2, FETCHING = 3;

    static final class Session {
        final UUID guard, village;
        final ResourceKey<Level> dim;
        final Ledger.Building range;
        final int lane;
        final boolean contest;
        final int toShoot;
        int shot, points, hits, stage = TO_LINE;
        long nextShot, since;
        boolean borrowedBow;
        final Map<UUID, Long> loosed = new LinkedHashMap<>();
        final Set<UUID> judged = new HashSet<>();

        Session(UUID guard, UUID village, ResourceKey<Level> dim, Ledger.Building range, int lane, boolean contest, int toShoot) {
            this.guard = guard;
            this.village = village;
            this.dim = dim;
            this.range = range;
            this.lane = lane;
            this.contest = contest;
            this.toShoot = toShoot;
        }
    }

    static final class Contest {
        final UUID village;
        final long started;
        final List<UUID> entrants = new ArrayList<>();
        final Map<UUID, int[]> scores = new LinkedHashMap<>();

        Contest(UUID village, long started) {
            this.village = village;
            this.started = started;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    /** The day each guard last practised. */
    private static final Map<UUID, Long> PRACTISED = new ConcurrentHashMap<>();
    private static final Map<UUID, Contest> CONTESTS = new ConcurrentHashMap<>();

    static boolean busy(VillageFolkEntity f) {
        return SESSIONS.containsKey(f.getUUID());
    }

    /** A guard at the butts, or one that might go down to them: about it. True while it is. */
    static boolean hold(VillageFolkEntity f, ServerLevel level) {
        Session s = SESSIONS.get(f.getUUID());
        if (s == null) {
            if (f.stationTask() != StationTask.GUARD || f.isBaby() || f.tickCount % 40 != 7) return false;
            s = begin(f, level);
            if (s == null) return false;
        }
        if (!s.dim.equals(level.dimension())) return false;
        if (f.getTarget() != null || Raids.underAlarm(s.village) || f.isSleeping()) {
            stop(level, s, "called away");
            return false;
        }
        drive(f, level, s);
        return true;
    }

    /** Free for the butts: awake, no alarm, nothing to fight, not walking with the leader. */
    static boolean free(VillageFolkEntity f, ServerLevel level) {
        return f.isAlive() && !f.isSleeping() && f.getTarget() == null && !f.onWatch() && !Patrols.escorting(f)
            && f.trip() == null && f.talkPartner() == null && !Weather.stormy(level) && !Sport.busyElsewhere(f, "archery");
    }

    /** A session begun, if this guard is due one: its contest turn on the rest day, or its morning's practice. */
    @Nullable
    static Session begin(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        if (village == null || !free(f, level)) return null;
        Ledger.Building range = of(village);
        if (range == null || !level.isLoaded(range.anchor())) return null;
        long day = level.getDayTime() / 24000L, t = Math.floorMod(level.getDayTime(), 24000L);
        Contest c = CONTESTS.get(village);
        boolean contest = c != null && c.entrants.contains(f.getUUID()) && !c.scores.containsKey(f.getUUID());
        if (!contest) {
            if (t < PRACTICE_FROM || t >= PRACTICE_TILL || RestDay.today(village, day)) return null;
            if (PRACTISED.getOrDefault(f.getUUID(), -1L) >= day) return null;
            int at = 0;
            for (Session o : SESSIONS.values()) if (o.village.equals(village)) at++;
            if (at >= Math.max(1, guards(village) / 2)) return null;            // half the watch stays on its rounds
        }
        int lane = freeLane(village, range);
        if (lane < 0) return null;
        return open(f, level, village, range, lane, contest);
    }

    static int freeLane(UUID village, Ledger.Building range) {
        boolean[] used = new boolean[3];
        for (Session o : SESSIONS.values()) if (o.village.equals(village)) used[o.lane] = true;
        for (int i = 0; i < 3; i++) if (!used[i]) return i;
        return -1;
    }

    /** Arrows out of the stores and a bow to hand: the session open. Null without them. */
    @Nullable
    static Session open(VillageFolkEntity f, ServerLevel level, UUID village, Ledger.Building range, int lane, boolean contest) {
        Villages.Village v = Villages.get(village);
        if (v == null) return null;
        long day = level.getDayTime() / 24000L;
        int n = Math.min(ARROWS, Market.stock(level, village, s -> s.is(Items.ARROW)));
        if (n < 2) {
            if (contest) score(level, village, f.getUUID(), 0, 0, "no arrows in the stores");
            else PRACTISED.put(f.getUUID(), day);                       // no practice this morning: asked again tomorrow
            return null;
        }
        boolean borrowed = false;
        if (!holdBow(f)) {
            ItemStack bow = Crafts.takeOne(level, v, s -> s.is(Items.BOW));
            if (bow.isEmpty()) {
                if (contest) score(level, village, f.getUUID(), 0, 0, "no bow to shoot with");
                PRACTISED.put(f.getUUID(), day);
                return null;
            }
            ItemStack left = f.insertItem(bow);
            if (!left.isEmpty()) { Crafts.store(level, v, left); return null; }
            borrowed = true;
            holdBow(f);
        }
        if (!Crafts.take(level, v, s -> s.is(Items.ARROW), n)) {
            if (borrowed) giveBackBow(level, v, f);
            return null;
        }
        Session s = new Session(f.getUUID(), village, level.dimension(), range, lane, contest, n);
        s.borrowedBow = borrowed;
        s.since = level.getGameTime();
        SESSIONS.put(f.getUUID(), s);
        if (!contest) PRACTISED.put(f.getUUID(), day);
        f.clearQueue();
        f.getNavigation().stop();
        return s;
    }

    /** Its bow in its hand, out of its pack. False if it has none. */
    static boolean holdBow(VillageFolkEntity f) {
        if (f.getMainHandItem().is(Items.BOW)) return true;
        var inv = f.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).is(Items.BOW)) {
                ItemStack old = f.getMainHandItem();
                f.setItemSlot(EquipmentSlot.MAINHAND, inv.get(i));
                inv.set(i, old);
                return true;
            }
        }
        return false;
    }

    static void giveBackBow(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (f.getMainHandItem().is(Items.BOW)) {
            Crafts.store(level, v, f.getMainHandItem().copy());
            f.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            return;
        }
        var inv = f.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).is(Items.BOW)) {
                Crafts.store(level, v, inv.get(i).copy());
                inv.set(i, ItemStack.EMPTY);
                return;
            }
        }
    }

    static void drive(VillageFolkEntity f, ServerLevel level, Session s) {
        f.hobbyNow = s.contest ? "shooting in the archery contest at the range" : "at archery practice at the range";
        f.lastLeisureTick = f.tickCount;
        Lane l = lanes(s.range).get(s.lane);
        long now = level.getGameTime();
        Vec3 target = Vec3.atCenterOf(l.target());
        switch (s.stage) {
            case TO_LINE -> {
                Vec3 at = Vec3.atBottomCenterOf(l.stand());
                if (f.position().distanceToSqr(at.x, f.getY(), at.z) > 1.0 && now - s.since < 600) {
                    if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.getNavigation().moveTo(at.x, at.y, at.z, 1.0);
                    return;
                }
                f.getNavigation().stop();
                s.stage = SHOOTING;
                s.nextShot = now + 30;
            }
            case SHOOTING -> {
                f.getNavigation().stop();
                holdBow(f);
                f.getLookControl().setLookAt(target.x, target.y, target.z);
                if (now >= s.nextShot && s.shot < s.toShoot) {
                    if (!clear(level, f, l)) {
                        s.nextShot = now + 20;
                        if (f.getRandom().nextInt(4) == 0) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Clear the range!", "Out of the way, please!"));
                    } else {
                        loose(level, f, s, l);
                        s.nextShot = now + 30 + f.getRandom().nextInt(12);
                    }
                }
                judge(level, f, s, l, now);
                if (s.shot >= s.toShoot) s.stage = JUDGING;
            }
            case JUDGING -> {
                judge(level, f, s, l, now);
                if (s.judged.size() >= s.loosed.size()) {
                    s.stage = FETCHING;
                    s.since = now;
                }
            }
            case FETCHING -> {
                BlockPos pull = l.hay().relative(s.range.facing().getOpposite());
                Vec3 at = Vec3.atBottomCenterOf(pull);
                if (f.position().distanceToSqr(at.x, f.getY(), at.z) > 2.25 && now - s.since < 400) {
                    if (f.getNavigation().isDone() || f.tickCount % 40 == 0) f.getNavigation().moveTo(at.x, at.y, at.z, 1.0);
                    return;
                }
                f.getNavigation().stop();
                f.swing(InteractionHand.MAIN_HAND);
                finish(level, f, s);
            }
            default -> { }
        }
    }

    /** Nobody but the archer between the line and the butt. */
    static boolean clear(ServerLevel level, VillageFolkEntity f, Lane l) {
        AABB lane = new AABB(Vec3.atCenterOf(l.stand()), Vec3.atCenterOf(l.target())).inflate(1.2, 1.5, 1.2);
        return level.getEntitiesOfClass(LivingEntity.class, lane, e -> e != f && e.isAlive()).isEmpty();
    }

    /**
     * An arrow loosed at the butt: a real arrow, out of the six from the stores, aimed at the target's middle
     * and dropping as arrows do (the aim a little over it, more for a longer shot), off true by less the better
     * the archer.
     */
    static void loose(ServerLevel level, VillageFolkEntity f, Session s, Lane l) {
        ItemStack bow = f.getMainHandItem().is(Items.BOW) ? f.getMainHandItem() : new ItemStack(Items.BOW);
        Arrow arrow = new Arrow(level, f, new ItemStack(Items.ARROW), bow.copy());
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;                // the town's, to be pulled and put back
        Vec3 t = Vec3.atCenterOf(l.target());
        double dx = t.x - f.getX(), dz = t.z - f.getZ(), dy = t.y - arrow.getY();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float speed = 1.6F;
        double drop = 0.5 * 0.05 * (horiz / speed) * (horiz / speed);
        float inaccuracy = (float) Math.max(2.5, Math.min(10.0, 10.0 - f.tradeLevel(StationTask.GUARD) * 0.2));
        arrow.shoot(dx, dy + drop, dz, speed, inaccuracy);
        level.addFreshEntity(arrow);
        level.playSound(null, f.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 1.0F, 1.0F / (f.getRandom().nextFloat() * 0.4F + 0.8F));
        f.swing(InteractionHand.MAIN_HAND);
        s.loosed.put(arrow.getUUID(), level.getGameTime());
        s.shot++;
        if (bow.is(Items.BOW) && f.getMainHandItem() == bow && f.getRandom().nextInt(3) == 0) bow.hurtAndBreak(1, f, EquipmentSlot.MAINHAND);
    }

    /** Each arrow judged where it stopped, a second and a half after it was loosed: two in the top of the butt, one in the bale under it. */
    static void judge(ServerLevel level, VillageFolkEntity f, Session s, Lane l, long now) {
        for (Map.Entry<UUID, Long> e : s.loosed.entrySet()) {
            if (s.judged.contains(e.getKey()) || now - e.getValue() < 30) continue;
            s.judged.add(e.getKey());
            int pts = 0;
            if (level.getEntity(e.getKey()) instanceof Arrow a) pts = points(l, a.position());
            s.points += pts;
            if (pts > 0) s.hits++;
            if (f.getRandom().nextInt(3) == 0) {
                FolkTalk.speak(f, pts == 2 ? FolkTalk.pick(f.getRandom(), "Dead centre!", "In the gold!", "Ha! Right in it.")
                    : pts == 1 ? FolkTalk.pick(f.getRandom(), "Low. Still in the butt.", "Bit low.")
                    : FolkTalk.pick(f.getRandom(), "Wide!", "Blast. The wind.", "Where did that go?"));
            }
        }
    }

    /** What an arrow stopped here scores: two in the target (the butt's top block), one in the bale under it. */
    public static int points(Lane l, Vec3 p) {
        if (new AABB(l.target()).inflate(0.2).contains(p)) return 2;
        if (new AABB(l.hay()).inflate(0.2).contains(p)) return 1;
        return 0;
    }

    /**
     * At the butts: its arrows pulled, out of the butt, the boards and the grass, and back into the stores (a lost
     * one is lost); the bow given back if it was borrowed; the morning counted to its trade.
     */
    static void finish(ServerLevel level, VillageFolkEntity f, Session s) {
        SESSIONS.remove(f.getUUID(), s);
        Villages.Village v = Villages.get(s.village);
        int pulled = pull(level, s, f);
        int unshot = s.toShoot - s.shot;
        if (v != null) {
            if (pulled + unshot > 0) Crafts.store(level, v, new ItemStack(Items.ARROW, pulled + unshot));
            if (s.borrowedBow) giveBackBow(level, v, f);
        }
        long day = level.getDayTime() / 24000L;
        f.awardXp(1 + s.points / 2);                                     // a little to its standing at its trade
        String how = s.hits + " of " + s.shot + " in the butts, " + s.points + " points";
        if (s.contest) {
            score(level, s.village, f.getUUID(), s.points, s.hits, null);
            f.persona().remember(day, "I shot " + how + " in the archery contest", 2);
        } else {
            f.persona().remember(day, "I put " + s.hits + " of " + s.shot + " in the butts at practice", 1);
            if (s.points >= s.shot * 3 / 2) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Not bad, that.", "My eye's in today."));
        }
        LAST.put(f.getUUID(), new int[]{ s.shot, s.hits, s.points, pulled });
    }

    /** The arrows it loosed, and any of its own lying in the range: off the field, counted. */
    static int pull(ServerLevel level, Session s, VillageFolkEntity f) {
        int n = 0;
        Set<UUID> mine = new HashSet<>(s.loosed.keySet());
        for (UUID u : mine) {
            if (level.getEntity(u) instanceof Arrow a && a.isAlive()) { a.discard(); n++; }
        }
        AABB lot = new AABB(s.range.anchor()).inflate(7, 6, 7);
        for (Arrow a : level.getEntitiesOfClass(Arrow.class, lot, a -> a.isAlive() && a.getOwner() == f)) { a.discard(); n++; }
        return Math.min(n, s.shot);
    }

    /** Stopped before it was done (a fight, the bell): what can be had back goes back. */
    static void stop(ServerLevel level, Session s, String why) {
        SESSIONS.remove(s.guard, s);
        Villages.Village v = Villages.get(s.village);
        VillageFolkEntity f = Football.live(level, s.guard);
        if (v == null) return;
        int back = s.toShoot - s.shot + (f == null ? 0 : pull(level, s, f));
        if (back > 0) Crafts.store(level, v, new ItemStack(Items.ARROW, back));
        if (f != null && s.borrowedBow) giveBackBow(level, v, f);
        if (s.contest) score(level, s.village, s.guard, s.points, s.hits, why);
    }

    // ------------------------------------------------------------------ the contest

    /** The rest day's contest called (Sport's programme): the watch, two at least, each to shoot six. */
    static String startContest(ServerLevel level, Villages.Village v) {
        if (CONTESTS.containsKey(v.id())) return "already on";
        Ledger.Building range = of(v.id());
        if (range == null) return "no range";
        Contest c = new Contest(v.id(), level.getGameTime());
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.stationTask() == StationTask.GUARD && free(f, level)) c.entrants.add(f.getUUID());
            if (c.entrants.size() >= 6) break;
        }
        if (c.entrants.size() < 2) return "too few of the watch free: " + c.entrants.size();
        if (Market.stock(level, v.id(), s -> s.is(Items.ARROW)) < 2) return "no arrows in the stores";
        CONTESTS.put(v.id(), c);
        VillageFolkEntity first = Football.live(level, c.entrants.get(0));
        if (first != null) FolkTalk.speak(first, FolkTalk.pick(level.getRandom(), "The archery contest! Six arrows each.",
            "Right, you lot. Let's see who can shoot."));
        return "the archery contest is on: " + c.entrants.size() + " of the watch";
    }

    static void score(ServerLevel level, UUID village, UUID guard, int points, int hits, @Nullable String why) {
        Contest c = CONTESTS.get(village);
        if (c != null && c.entrants.contains(guard)) c.scores.put(guard, new int[]{ points, hits });
    }

    /** Every second (Sport's clock): a contest every entrant has shot in (or out of time) is judged. */
    static void referee(ServerLevel level, Contest c) {
        boolean all = c.scores.keySet().containsAll(c.entrants);
        if (!all && level.getGameTime() - c.started < CONTEST_TIME) return;
        result(level, c);
    }

    @Nullable
    static String result(ServerLevel level, Contest c) {
        if (CONTESTS.remove(c.village) == null) return null;
        long day = level.getDayTime() / 24000L;
        UUID best = null;
        int[] top = null;
        for (Map.Entry<UUID, int[]> e : c.scores.entrySet()) {
            int[] s = e.getValue();
            if (top == null || s[0] > top[0] || s[0] == top[0] && s[1] > top[1]) { top = s; best = e.getKey(); }
        }
        VillageFolkEntity w = Football.live(level, best);
        if (w == null || top == null || top[0] == 0) {
            String line = "the archery contest at the range: nobody found the butts";
            Villages.tell(c.village, day, line);
            League.record(c.village, "archery", "day " + day + ", no winner");
            return line;
        }
        String line = w.displayNameCap() + " won the archery contest at the range, " + top[0] + " points with " + top[1] + " of six in the butts ("
            + c.scores.size() + " of the watch shot)";
        Villages.tell(c.village, day, line);
        League.record(c.village, "archery", "day " + day + ", " + w.displayNameCap() + " with " + top[0] + " points");
        w.persona().remember(day, "I won the archery contest", 5);
        w.awardXp(3);
        FolkTalk.speak(w, FolkTalk.pick(level.getRandom(), "Best shot in the watch, that's me.", "Steady hand, keen eye.", "Who's buying, then?"));
        return line;
    }

    /** Every second: the contests judged. */
    static void tick(ServerLevel level) {
        if (level.getGameTime() % 20 != 5) return;
        for (Contest c : new ArrayList<>(CONTESTS.values())) {
            Villages.Village v = Villages.get(c.village);
            if (v != null && v.dim().equals(level.dimension())) referee(level, c);
        }
    }

    /** The board's word on the range just now, or null. */
    @Nullable
    static String now(UUID village) {
        if (CONTESTS.containsKey(village)) return "the archery contest at the range";
        int at = 0;
        for (Session s : SESSIONS.values()) if (s.village.equals(village)) at++;
        return at == 0 ? null : at + " of the watch at practice at the range";
    }

    /** How the range is, in a line (the books). */
    static String status(ServerLevel level, UUID village) {
        Ledger.Building b = of(village);
        if (b == null) {
            return Villages.projectsWanted(village).contains(STRUCTURE) ? "The archery range is on the builders' list."
                : "No archery range yet (an Iron Age town with " + GUARDS + " guards or more builds one).";
        }
        if (!level.isLoaded(b.anchor())) return "The archery range: out of sight just now.";
        int targets = 0, bales = 0;
        for (Lane l : lanes(b)) {
            if (level.getBlockState(l.target()).is(Blocks.TARGET)) targets++;
            else if (level.getBlockState(l.target()).is(Blocks.HAY_BLOCK)) bales++;
        }
        return "The archery range: " + targets + " of its 3 butts with targets" + (bales > 0 ? ", " + bales + " with only hay (the stores' redstone makes targets)" : "")
            + "; " + Market.stock(level, village, s -> s.is(Items.ARROW)) + " arrows in the stores for practice.";
    }

    // ------------------------------------------------------------------ tests

    private static final Map<UUID, int[]> LAST = new ConcurrentHashMap<>();

    /** Tests: a practice session for this guard now, whatever the hour (null if it cannot: no range, arrows, bow or lane). */
    public static boolean practiseForTests(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        Ledger.Building range = of(village);
        if (village == null || range == null) return false;
        int lane = freeLane(village, range);
        return lane >= 0 && open(f, level, village, range, lane, false) != null;
    }

    /** Tests: {shot, hits, points, pulled} of this guard's last session, or null. */
    @Nullable
    public static int[] lastForTests(UUID guard) {
        return LAST.get(guard);
    }

    public static boolean sessionForTests(UUID guard) {
        return SESSIONS.containsKey(guard);
    }

    public static String contestForTests(ServerLevel level, Villages.Village v) {
        return startContest(level, v);
    }

    /** Tests: the contest judged now (whoever has shot). */
    @Nullable
    public static String resultForTests(ServerLevel level, UUID village) {
        Contest c = CONTESTS.get(village);
        return c == null ? null : result(level, c);
    }

    public static boolean contestDoneForTests(UUID village) {
        Contest c = CONTESTS.get(village);
        return c == null || c.scores.keySet().containsAll(c.entrants);
    }
}
