package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Crime.Kind;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [crime] Temptation, and the deed (Crime).
 *
 * <p>Every morning each grown folk's lot is weighed: poor, in debt, hungry, low, living in a miserable town,
 * bearing a grudge, greedy. Against that, its honesty: its nature (the generous and the hard-working are honest,
 * the grumpy and the easygoing less so, the folk who care most for the old ways the most, the greedy least), and
 * its record (a conviction makes the next easier; a folk that has turned over a new leaf is a good deal harder to
 * tempt). A content folk lets it go whatever it is. Where the lot outweighs the honesty, there is a chance, a little
 * less in a town with a watch and a good deal less in a contented one, that today it does something about it, and
 * a town has at most one such folk a day.
 *
 * <p>What it does follows from why: the poor and the greedy pick a purse at the market or the tavern or take
 * something from a better-off neighbour's chest while the neighbour is out; the hungry go to the stores after dark,
 * or the pen; the bitter break their rival's window, or a lamp, or a fence; a smelter or a smith with copper casts
 * a forged coin and passes it at the stores for a treat; a carrier slips a lot of the stores' goods home. It goes
 * about it in its own free time, and only when nobody is close enough to see and the watch is not about (after dark,
 * not where the lamps light it up), and if the moment never comes it thinks better of it. Then it does it for real,
 * and goes home, leaving its footprints, sometimes something it dropped, and now and then a witness further off
 * than it thought.
 */
final class Mischief {

    private Mischief() {}

    /** At or over this (its lot less half its honesty), a folk may do something about it. */
    static final int TEMPTED = 15;
    /** A folk who did something lies low this many days before it is tempted again. */
    static final int LIE_LOW = 3;
    /** How long it waits at the spot for the moment before it thinks better of it (ticks). */
    static final int WAIT = 500;
    /** The most a deed takes, in coin: never a town-ruining amount. */
    static final int MOST = 12;
    /** How far off a folk can see a deed, and how near is too near for the culprit to try. */
    static final int SIGHT = 24, TOO_NEAR = 6, WATCH_NEAR = 14;

    /** A folk's intention for the day. */
    static final class Plan {
        final Kind kind;
        final UUID village;
        final long from, until;
        final boolean forced;
        final String motive;
        @Nullable UUID victim, animal;
        @Nullable BlockPos target, place;
        String placeName = "";
        /** 0 on its way, 1 there and waiting for its moment, 2 away home after it. */
        int stage;
        long waitSince = -1, awaySince = -1, lastTrail = -1;
        @Nullable Crime.Case done;
        @Nullable BlockPos away;
        String lastDeterred = "";

        Plan(Kind kind, UUID village, long from, long until, boolean forced, String motive) {
            this.kind = kind;
            this.village = village;
            this.from = from;
            this.until = until;
            this.forced = forced;
            this.motive = motive;
        }
    }

    static final Map<UUID, Plan> PLANS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> PATHED = new ConcurrentHashMap<>();

    static void resetForTests() {
        PLANS.clear();
        PATHED.clear();
    }

    // ------------------------------------------------------------------ honesty and temptation

    /** Its honesty, 0 to 100: its nature, what it cares about, and its record. */
    static int honesty(VillageFolkEntity f) {
        Integer set = Crime.HONESTY_FOR_TESTS.get(f.getUUID());
        if (set != null) return set;
        UUID id = f.getUUID();
        long h = id.getLeastSignificantBits() ^ (id.getMostSignificantBits() >>> 21);
        int x = 38 + (int) Math.floorMod(h, 33L);
        Social.Life life = f.life();
        if (life.has(Social.Trait.GENEROUS)) x += 15;
        if (life.has(Social.Trait.HARDWORKING)) x += 5;
        if (life.has(Social.Trait.CHEERFUL)) x += 4;
        if (life.has(Social.Trait.GRUMPY)) x -= 8;
        if (life.has(Social.Trait.EASYGOING)) x -= 4;
        x += Math.max(0, Values.weight(f, Values.Value.TRADITION) - 40) / 3;
        x -= Math.max(0, Values.weight(f, Values.Value.WEALTH) - 45) / 2;   // the greedy
        if (Crime.known(id)) {
            CompoundTag r = Crime.folk(id);
            x -= 6 * r.getInt("convictions");
            if (r.getLong("reformed") > 0) x += 25;
        }
        return Math.max(0, Math.min(100, x));
    }

    /** What weighs on it, and why, and whom it has a grudge against. */
    record Motive(int score, List<String> why, @Nullable VillageFolkEntity grudge) {
        boolean has(String w) { return why.contains(w); }

        String main() { return why.isEmpty() ? "nothing much" : why.get(0); }
    }

    static Motive motive(ServerLevel level, VillageFolkEntity f) {
        List<String> why = new ArrayList<>();
        int m = 0;
        Wealth.Tier tier = Wealth.tier(f);
        if (tier == Wealth.Tier.POOR) { m += 30; why.add("poor"); }
        else if (tier == Wealth.Tier.GETTING_BY && f.purse() < 3) { m += 8; why.add("short of coin"); }
        if (Bank.worthOf(f) < 0) { m += 12; why.add("in debt"); }
        int missed = f.meals().missedInRow();
        if (missed >= 2) { m += 10 + 3 * missed; why.add("hungry"); }
        int mood = f.persona().mood();
        if (mood < 50) { m += 50 - mood; why.add("low"); }
        UUID village = f.ownerId();
        int content = Contentment.score(village);
        if (content < 25) { m += 16; why.add("the town's low"); }
        else if (content < 40) { m += 8; why.add("the town's low"); }
        VillageFolkEntity grudge = null;
        UUID worst = f.life().worstRival();
        if (worst != null && f.life().affinity(worst) <= -40) {
            VillageFolkEntity g = Civics.find(level, worst);
            if (g != null && village != null && village.equals(g.ownerId()) && !g.isBaby()) { grudge = g; m += 12; why.add("a grudge"); }
        }
        int wealth = Values.weight(f, Values.Value.WEALTH);
        if (wealth > 55) { m += (wealth - 55) / 2; why.add("greedy"); }
        // A contented folk lets things go, whatever it is short of.
        if (mood >= 60 && tier != Wealth.Tier.POOR && missed < 2) m = Math.min(m, 12);
        return new Motive(m, why, grudge);
    }

    /** Its lot less half its honesty: at TEMPTED or over, it may do something about it. */
    static int temptation(ServerLevel level, VillageFolkEntity f) {
        return motive(level, f).score() - honesty(f) / 2;
    }

    /** A grown folk of the town, not the watch or the leader, not lying low, not up to anything already. */
    static boolean eligible(VillageFolkEntity f, long day) {
        if (f.isBaby() || f.isShowcase() || f.isHired() || !f.isAlive() || f.ownerId() == null) return false;
        if (f.stationTask() == StationTask.GUARD || f.getUUID().equals(Villages.elder(f.ownerId()))) return false;
        if (PLANS.containsKey(f.getUUID()) || Trial.sentenced(f)) return false;
        if (Crime.known(f.getUUID()) && day - Crime.folk(f.getUUID()).getLong("lastDeed") < LIE_LOW
                && Crime.folk(f.getUUID()).contains("lastDeed")) return false;
        return true;
    }

    /** How much a town keeps crime down by itself: content folk, and a watch for its size. */
    static double prevention(Villages.Village v) {
        double x = 1.0;
        int content = Contentment.score(v.id());
        if (content >= 80) x *= 0.3;
        else if (content >= 60) x *= 0.5;
        int guards = Patrols.watch(v.id()).size(), head = Math.max(1, Villages.headcount(v.id()));
        if (guards > 0) x *= Math.max(0.5, 1.0 - guards * 6.0 / head);
        return x;
    }

    // ------------------------------------------------------------------ the morning's temptations

    /** The morning's look at a town's folk (Crime.tick): who is tempted today, and what it means to do. Returns the plans made. */
    static int daily(ServerLevel level, Villages.Village v, long day) {
        if (Villages.headcount(v.id()) < 3) return 0;
        double prevent = prevention(v);
        RandomSource r = level.getRandom();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !eligible(f, day)) continue;
            Motive m = motive(level, f);
            int t = m.score() - honesty(f) / 2;
            if (t < TEMPTED) continue;
            double chance = Math.min(0.35, (t - 10) / 120.0) * prevent;
            if (r.nextDouble() >= chance) continue;
            for (int tries = 0; tries < 3; tries++) {
                Kind k = choose(level, v, f, m, r);
                if (plan(level, v, f, k, m, day, false, m.grudge(), null)) return 1;      // one a day in a town at most
            }
        }
        return 0;
    }

    /** What it means to do, by why. */
    static Kind choose(ServerLevel level, Villages.Village v, VillageFolkEntity f, Motive m, RandomSource r) {
        Map<Kind, Integer> w = new EnumMap<>(Kind.class);
        w.put(Kind.PICKPOCKET, 1);
        boolean poor = m.has("poor") || m.has("in debt") || m.has("short of coin");
        if (poor || m.has("greedy")) { w.merge(Kind.PICKPOCKET, 4, Integer::sum); w.merge(Kind.BURGLARY, 2, Integer::sum); }
        if (m.has("hungry")) { w.merge(Kind.STORES, 4, Integer::sum); w.merge(Kind.POACHING, 2, Integer::sum); }
        if (m.grudge() != null) { w.merge(Kind.VANDALISM, 3, Integer::sum); w.merge(Kind.BURGLARY, 2, Integer::sum); }
        if (m.has("low") || m.has("the town's low")) w.merge(Kind.VANDALISM, 2, Integer::sum);
        StationTask t = f.stationTask();
        if ((poor || m.has("greedy")) && (t == StationTask.SMELT || t == StationTask.SMITH || f.countCarried(s -> s.is(Items.COPPER_INGOT)) > 0)) {
            w.merge(Kind.FORGERY, 1, Integer::sum);
        }
        if ((poor || m.has("greedy")) && (t == StationTask.HAUL || t == StationTask.STORE)) w.merge(Kind.SMUGGLING, 1, Integer::sum);
        int total = 0;
        for (int n : w.values()) total += n;
        int pick = r.nextInt(Math.max(1, total));
        for (Map.Entry<Kind, Integer> e : w.entrySet()) {
            pick -= e.getValue();
            if (pick < 0) return e.getKey();
        }
        return Kind.PICKPOCKET;
    }

    /**
     * Its intention for the day: what, where and when (a purse in the day's free time, a house while its people are
     * at work, the rest after dark), the victim or the spot chosen now. False if there is nothing of the kind to be had.
     */
    static boolean plan(ServerLevel level, Villages.Village v, VillageFolkEntity f, Kind kind, Motive m, long day, boolean forced,
                        @Nullable VillageFolkEntity victim, @Nullable BlockPos target) {
        long now = level.getDayTime(), base = day * 24000L;
        boolean dark = kind.night();
        long from = forced ? now : dark ? base + 11000L : Math.max(now, base + 2000L);
        long until = forced ? now + 48000L : dark ? base + 14000L : base + 11800L;
        if (!forced && until <= now) return false;
        Plan p = new Plan(kind, v.id(), from, until, forced, m.main());
        UUID id = v.id();
        switch (kind) {
            case PICKPOCKET -> {
                p.place = where(level, v, now);
                p.placeName = placeName(level, v, p.place);
                if (victim != null && victim.purse() > 0) p.victim = victim.getUUID();
            }
            case BURGLARY -> {
                VillageFolkEntity mark = victim != null && chestOf(level, victim) != null ? victim : neighbour(level, v, f);
                if (mark == null) return false;
                BlockPos chest = chestOf(level, mark);
                if (chest == null || target != null && !target.equals(chest)) return false;
                p.victim = mark.getUUID();
                p.target = chest;
            }
            case STORES, SMUGGLING -> {
                p.target = target != null ? target : storeChest(level, v, f, kind == Kind.STORES && m.has("hungry"));
                if (p.target == null) return false;
            }
            case VANDALISM -> {
                p.target = target != null ? target : toBreak(level, v, f, m.grudge());
                if (p.target == null) return false;
                Homes.Home h = Homes.homeAt(id, p.target);
                if (h != null && !h.members.isEmpty()) p.victim = h.members.contains(f.getUUID()) ? null : h.members.get(0);
            }
            case POACHING -> {
                Animal a = beast(level, v, f);
                if (a == null) return false;
                p.animal = a.getUUID();
            }
            case FORGERY -> {
                if (f.countCarried(s -> s.is(Items.COPPER_INGOT)) == 0 && Market.stock(level, id, s -> s.is(Items.COPPER_INGOT)) == 0) return false;
                if (Market.stock(level, id, Mischief::treat) == 0) return false;
                BlockPos depot = Villages.depot(level, id);
                p.place = depot != null ? depot : v.centre();
                p.placeName = "the stores";
            }
        }
        PLANS.put(f.getUUID(), p);
        f.brain("has something on its mind (" + kind.word + ")");
        return true;
    }

    /** A plan set now and done as soon as it can be: the tests and the operators' command. */
    static boolean force(ServerLevel level, VillageFolkEntity f, Kind kind, @Nullable VillageFolkEntity victim, @Nullable BlockPos target) {
        Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
        if (v == null) return false;
        PLANS.remove(f.getUUID());
        return plan(level, v, f, kind, motive(level, f), level.getDayTime() / 24000L, true, victim, target);
    }

    static boolean planning(VillageFolkEntity f) {
        return PLANS.containsKey(f.getUUID());
    }

    static int plans(UUID village) {
        int n = 0;
        for (Plan p : PLANS.values()) if (p.village.equals(village)) n++;
        return n;
    }

    /** "/village crime now": the town's most tempted folk sent to do what it would do, now. */
    static String tempt(ServerLevel level, Villages.Village v) {
        VillageFolkEntity best = null;
        int most = Integer.MIN_VALUE;
        long day = level.getDayTime() / 24000L;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !eligible(f, day)) continue;
            int t = temptation(level, f);
            if (t > most) { most = t; best = f; }
        }
        if (best == null) return "nobody in " + Villages.name(v.id()) + " could be tempted";
        Motive m = motive(level, best);
        for (int tries = 0; tries < 6; tries++) {
            Kind k = choose(level, v, best, m, level.getRandom());
            if (plan(level, v, best, k, m, day, true, m.grudge(), null)) {
                return best.displayNameCap() + " (temptation " + most + ", honesty " + honesty(best) + ", " + String.join(", ", m.why()) + ") means to do "
                    + k.word;
            }
        }
        return best.displayNameCap() + " found nothing to be had";
    }

    /** The plans' upkeep (Crime.tick): one whose folk is gone, or whose time has passed while it was away, let go. */
    static void tick(ServerLevel level, Villages.Village v) {
        long now = level.getDayTime();
        for (Map.Entry<UUID, Plan> e : PLANS.entrySet()) {
            Plan p = e.getValue();
            if (!p.village.equals(v.id())) continue;
            VillageFolkEntity f = Civics.find(level, e.getKey());
            if (f == null || !v.id().equals(f.ownerId())) { PLANS.remove(e.getKey(), p); continue; }
            if (p.stage < 2 && now > p.until + 200) PLANS.remove(e.getKey(), p);
        }
    }

    // ------------------------------------------------------------------ the folk's part

    /** From Crime.hold: a folk with a plan about it, in its own free time. What it seems to be doing, or null. */
    @Nullable
    static String hold(VillageFolkEntity f, ServerLevel level) {
        Plan p = PLANS.get(f.getUUID());
        if (p == null) return null;
        Villages.Village v = Villages.get(p.village);
        if (v == null || !p.village.equals(f.ownerId())) {
            PLANS.remove(f.getUUID());
            return null;
        }
        long now = level.getDayTime(), gt = level.getGameTime();
        if (p.stage >= 2) {
            Crime.takeOver(f);
            return away(level, v, f, p, gt);
        }
        if (f.isSleeping()) return null;
        if (now > p.until) {
            lapse(level, v, f, p, p.lastDeterred.isEmpty() ? null : p.lastDeterred);
            return null;
        }
        if (!p.forced && (now < p.from || !Civics.free(f))) return null;
        Crime.takeOver(f);
        VillageFolkEntity victim = p.victim == null ? null : Civics.find(level, p.victim);
        BlockPos to;
        switch (p.kind) {
            case PICKPOCKET -> {
                if (victim != null && (!victim.isAlive() || victim.purse() <= 0 || victim.isSleeping())) { victim = null; p.victim = null; }
                if (victim == null && p.place != null && Civics.flat(f, p.place) <= 10.0 * 10.0) {
                    victim = mark(level, v, f);
                    if (victim != null) p.victim = victim.getUUID();
                }
                to = victim != null ? victim.blockPosition() : p.place;
            }
            case POACHING -> {
                Entity a = p.animal == null ? null : level.getEntity(p.animal);
                if (!(a instanceof Animal beast) || !beast.isAlive()) { lapse(level, v, f, p, null); return null; }
                to = beast.blockPosition();
            }
            case FORGERY -> to = p.place;
            default -> to = p.target;
        }
        if (to == null) {
            lapse(level, v, f, p, null);
            return null;
        }
        double reach = p.kind == Kind.PICKPOCKET ? 1.9 : p.kind == Kind.VANDALISM ? 3.2 : 2.6;
        if (!near(f, to, reach)) {
            if (p.kind == Kind.PICKPOCKET && victim == null && p.place != null && Civics.flat(f, p.place) <= 4.0 * 4.0) {
                // At the market and nobody worth the trouble yet: a look round the stalls.
                return waitFor(level, v, f, p, gt, null);
            }
            walk(f, to, p.kind == Kind.PICKPOCKET ? 0.75D : 0.7D);
            p.stage = 0;
            return cover(p);
        }
        p.stage = 1;
        f.getNavigation().stop();
        // The burglar waits for the house to be empty.
        if (p.kind == Kind.BURGLARY && victim != null && victim.distanceTo(f) < 12.0F) return waitFor(level, v, f, p, gt, "home");
        String eyes = deterrent(level, f, p.kind, victim, to);
        if (eyes != null) return waitFor(level, v, f, p, gt, eyes);
        Crime.Case c = commit(level, v, f, p.kind, victim, p.target, p.animal, p.place);
        if (c == null) {
            lapse(level, v, f, p, null);
            return null;
        }
        p.done = c;
        p.stage = 2;
        p.awaySince = gt;
        p.lastTrail = gt;
        p.away = escape(level, v, f, c);
        return "on its way";
    }

    /** Waiting about for its moment; given up after a while, and the town's books count what put it off. */
    @Nullable
    private static String waitFor(ServerLevel level, Villages.Village v, VillageFolkEntity f, Plan p, long gt, @Nullable String why) {
        if (p.waitSince < 0) p.waitSince = gt;
        if (why != null) p.lastDeterred = why;
        if (gt - p.waitSince > (p.forced ? WAIT * 2 : WAIT)) {
            // Put off by somebody about (counted in the town's books), or simply nobody worth the trouble (not).
            lapse(level, v, f, p, p.lastDeterred.isEmpty() ? null : p.lastDeterred);
            return null;
        }
        if (f.getRandom().nextInt(10) == 0) {
            double a = f.getRandom().nextDouble() * Math.PI * 2.0;
            f.getLookControl().setLookAt(f.getX() + Math.cos(a) * 6.0, f.getEyeY(), f.getZ() + Math.sin(a) * 6.0);
        }
        return cover(p);
    }

    /** What it seems to be about, to anybody who asks: never the truth. */
    static String cover(Plan p) {
        return switch (p.kind) {
            case PICKPOCKET -> "strolling about " + (p.placeName.isEmpty() ? "the square" : p.placeName);
            case FORGERY -> "going to buy something at the stores";
            case BURGLARY -> "taking a walk round the houses";
            default -> "taking the evening air";
        };
    }

    /** Off home after it, leaving footprints, the stolen thing put away in its chest when it gets there. */
    @Nullable
    private static String away(ServerLevel level, Villages.Village v, VillageFolkEntity f, Plan p, long gt) {
        Crime.Case c = p.done;
        if (c == null) {
            PLANS.remove(f.getUUID());
            return null;
        }
        BlockPos here = f.blockPosition();
        if (gt - p.lastTrail >= 10 && c.trail.size() < 16
                && (c.trail.isEmpty() || c.trail.get(c.trail.size() - 1).distSqr(here) >= 2.25)) {
            c.trail.add(onTheGround(level, here));
            p.lastTrail = gt;
        }
        if (p.away == null || near(f, p.away, 2.5) || gt - p.awaySince > 700) {
            stash(level, v, f, c);
            finish(level, f, p);
            return null;
        }
        walk(f, p.away, 0.85D);
        return "on its way home";
    }

    private static BlockPos onTheGround(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).isAir() || level.getBlockState(p).canBeReplaced() ? p.immutable() : p.above().immutable();
    }

    /** Where it makes for afterwards: its chest with the thing (or its door), or the street away from it all. */
    @Nullable
    private static BlockPos escape(ServerLevel level, Villages.Village v, VillageFolkEntity f, Crime.Case c) {
        Homes.Home h = Homes.homeOf(v.id(), f.getUUID());
        if (h != null) {
            BlockPos chest = Homes.chestOf(level, v.id(), h);
            if (chest != null && carriesTheCase(f, c.id)) return chest;
            return h.anchor;
        }
        if (f.bedPos() != null) return f.bedPos();
        BlockPos at = f.blockPosition();
        int dx = at.getX() - v.centre().getX(), dz = at.getZ() - v.centre().getZ();
        double len = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
        return at.offset((int) Math.round(dx / len * 14), 0, (int) Math.round(dz / len * 14));
    }

    private static boolean carriesTheCase(VillageFolkEntity f, int id) {
        for (ItemStack s : f.getInventoryItems()) if (Crime.stolenCase(s) == id) return true;
        return false;
    }

    /** The case's things out of its pack and into its own chest, if it is by it. */
    static void stash(ServerLevel level, Villages.Village v, VillageFolkEntity f, Crime.Case c) {
        Homes.Home h = Homes.homeOf(v.id(), f.getUUID());
        BlockPos chest = h == null ? null : Homes.chestOf(level, v.id(), h);
        if (chest == null || !near(f, chest, 4.0) || !(level.getBlockEntity(chest) instanceof Container box)) return;
        var pack = f.getInventoryItems();
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (Crime.stolenCase(s) != c.id) continue;
            ItemStack left = into(box, s.copy());
            s.setCount(left.getCount());
            if (s.isEmpty()) pack.set(i, ItemStack.EMPTY);
        }
        box.setChanged();
    }

    /** Into a chest: onto a like stack, then an empty slot. What would not go in. */
    static ItemStack into(Container box, ItemStack s) {
        for (int i = 0; i < box.getContainerSize() && !s.isEmpty(); i++) {
            ItemStack in = box.getItem(i);
            if (in.isEmpty() || !ItemStack.isSameItemSameComponents(in, s)) continue;
            int n = Math.min(s.getCount(), in.getMaxStackSize() - in.getCount());
            if (n > 0) { in.grow(n); s.shrink(n); }
        }
        for (int i = 0; i < box.getContainerSize() && !s.isEmpty(); i++) {
            if (!box.getItem(i).isEmpty()) continue;
            box.setItem(i, s.copy());
            s.setCount(0);
        }
        return s;
    }

    private static void finish(ServerLevel level, VillageFolkEntity f, Plan p) {
        PLANS.remove(f.getUUID(), p);
        Crime.changed();
    }

    /** It thought better of it (or there was nothing to be had); what put it off is counted. */
    private static void lapse(ServerLevel level, Villages.Village v, VillageFolkEntity f, Plan p, @Nullable String why) {
        PLANS.remove(f.getUUID(), p);
        if (why != null) {
            Crime.deterred(v.id(), level.getDayTime() / 24000L, why);
            f.brain("thought better of " + p.kind.word + " (" + why + ")");
        }
    }

    // ------------------------------------------------------------------ eyes, the watch and the lamps

    /**
     * Who or what would see it: anybody awake close by with a clear view, the watch within sight, a player at its
     * elbow; after dark, a spot the lamps light up. Null when the coast is clear.
     */
    @Nullable
    static String deterrent(ServerLevel level, VillageFolkEntity f, Kind kind, @Nullable VillageFolkEntity victim, BlockPos to) {
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(WATCH_NEAR),
                o -> o != f && o != victim && o.isAlive() && !o.isSleeping())) {
            double d = o.distanceTo(f);
            boolean guard = o.stationTask() == StationTask.GUARD && !o.isBaby();
            if ((d <= TOO_NEAR || guard && d <= WATCH_NEAR) && o.hasLineOfSight(f)) return guard ? "the watch" : "eyes";
        }
        for (Player p : level.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            if (p.distanceTo(f) <= TOO_NEAR && p.hasLineOfSight(f)) return "eyes";
        }
        long t = level.getDayTime() % 24000L;
        boolean dark = t >= 12500L && t < 23500L;
        boolean lamp = kind == Kind.VANDALISM && isLight(level.getBlockState(to));
        if (kind.night() && dark && !lamp && level.getBrightness(LightLayer.BLOCK, f.blockPosition()) >= 10) return "the lamps";
        return null;
    }

    // ------------------------------------------------------------------ the deed

    /**
     * The deed, done: the coin or the thing really taken, the block really broken; and the case opened on it, with
     * who was about, who saw it (and how well), what it dropped, and when it will be noticed. Null if, after all,
     * there was nothing to be had.
     */
    @Nullable
    static Crime.Case commit(ServerLevel level, Villages.Village v, VillageFolkEntity f, Kind kind, @Nullable VillageFolkEntity victim,
                             @Nullable BlockPos target, @Nullable UUID animal, @Nullable BlockPos place) {
        UUID id = v.id();
        long now = level.getDayTime(), day = now / 24000L;
        RandomSource r = f.getRandom();
        int purseBefore = f.purse();
        int caseId = Crime.nextId();
        BlockPos where;
        String placeName;
        UUID victimId = null;
        String victimName = "the town";
        int worth, coins = 0, count = 0;
        String goods = "", goodsId = "", broke = "", brokeWhat = "";
        switch (kind) {
            case PICKPOCKET -> {
                if (victim == null || victim.purse() <= 0) return null;
                int n = Math.min(victim.purse(), 1 + r.nextInt(Math.max(1, Math.min(4, victim.purse() / 2))));
                n = Math.min(n, 5);
                if (!victim.spend(n)) return null;
                f.earn(n);
                f.swing(InteractionHand.MAIN_HAND);
                coins = n;
                worth = n;
                goods = n + (n == 1 ? " coin" : " coins");
                where = victim.blockPosition().immutable();
                placeName = placeName(level, v, where);
                victimId = victim.getUUID();
                victimName = victim.displayNameCap();
            }
            case BURGLARY, STORES, SMUGGLING -> {
                if (target == null || !(level.getBlockEntity(target) instanceof Container box)) return null;
                int most = kind == Kind.SMUGGLING ? 16 : MOST;
                Predicate<ItemStack> want = kind == Kind.STORES && f.meals().missedInRow() >= 2
                    ? s -> s.get(DataComponents.FOOD) != null : s -> true;
                ItemStack took = lift(box, want, most, kind == Kind.SMUGGLING, caseId);
                if (took.isEmpty() && kind == Kind.STORES) took = lift(box, s -> true, most, false, caseId);
                if (took.isEmpty()) return null;
                ItemStack left = f.insertItem(took.copy());
                if (!left.isEmpty()) {
                    into(box, left);
                    took.shrink(left.getCount());
                    if (took.isEmpty()) return null;
                }
                box.setChanged();
                level.playSound(null, target, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.4F, 1.1F);
                level.playSound(null, target, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.4F, 1.1F);
                f.swing(InteractionHand.MAIN_HAND);
                count = took.getCount();
                worth = Math.max(1, (int) Math.round(Prices.of(took)));
                goods = Crafts.named(took);
                goodsId = BuiltInRegistries.ITEM.getKey(took.getItem()).toString();
                where = target.immutable();
                if (kind == Kind.BURGLARY && victim != null) {
                    victimId = victim.getUUID();
                    victimName = victim.displayNameCap();
                    placeName = victimName + "'s house";
                } else {
                    placeName = "the stores";
                }
            }
            case VANDALISM -> {
                if (target == null) return null;
                BlockState st = level.getBlockState(target);
                if (!breakable(st)) return null;
                brokeWhat = isLight(st) ? "a lamp" : st.getBlock() instanceof FenceBlock ? "a fence" : "a window";
                broke = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                worth = Math.max(1, (int) Math.round(Prices.of(new ItemStack(st.getBlock().asItem()))));
                f.swing(InteractionHand.MAIN_HAND);
                level.destroyBlock(target, false, f);
                where = target.immutable();
                Homes.Home h = Homes.homeAt(id, target);
                if (h != null && !h.members.isEmpty() && !h.members.contains(f.getUUID())) {
                    victimId = h.members.get(0);
                    victimName = nameOf(level, id, victimId);
                    placeName = victimName + "'s house";
                } else {
                    placeName = brokeWhat.equals("a lamp") ? "the lamp post " + streetOf(v, target) : placeName(level, v, target);
                }
            }
            case POACHING -> {
                Entity e = animal == null ? null : level.getEntity(animal);
                if (!(e instanceof Animal beast) || !beast.isAlive()) return null;
                where = beast.blockPosition().immutable();
                String what = beast.getType().getDescription().getString().toLowerCase(Locale.ROOT);
                f.swing(InteractionHand.MAIN_HAND);
                beast.hurt(level.damageSources().mobAttack(f), Float.MAX_VALUE);
                double sum = 0;
                for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(where).inflate(2.5),
                        d -> d.isAlive() && d.getAge() < 5 && !d.getItem().isEmpty())) {
                    ItemStack s = drop.getItem().copy();
                    Crime.mark(s, Crime.STOLEN, caseId);
                    if (goodsId.isEmpty()) goodsId = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
                    sum += Prices.of(s);
                    count += s.getCount();
                    ItemStack left = f.insertItem(s);
                    if (left.isEmpty()) drop.discard();
                    else drop.setItem(left);
                }
                worth = Math.max(2, (int) Math.round(sum));
                goods = (what.matches("^[aeiou].*") ? "an " : "a ") + what;
                placeName = "the pen";
                VillageFolkEntity rancher = rancher(id);
                if (rancher != null) {
                    victimId = rancher.getUUID();
                    victimName = rancher.displayNameCap();
                }
            }
            case FORGERY -> {
                if (f.countCarried(s -> s.is(Items.COPPER_INGOT)) > 0) f.removeMatching(s -> s.is(Items.COPPER_INGOT), 1);
                else if (!TownWork.take(level, v, s -> s.is(Items.COPPER_INGOT), 1)) return null;
                ItemStack treat = Crafts.takeOne(level, v, Mischief::treat);
                // Three coins cast of the ingot, as the recipe has it: one passed at the stores, two kept for another day.
                ItemStack passed = new ItemStack(McAssistantMod.FORGED_COIN.get(), 1);
                Crafts.store(level, v, passed);
                ItemStack kept = new ItemStack(McAssistantMod.FORGED_COIN.get(), 2);
                Crime.mark(kept, Crime.STOLEN, caseId);
                f.insertItem(kept);
                if (!treat.isEmpty()) {
                    goods = Crafts.named(treat);
                    worth = Math.max(1, (int) Math.round(Prices.of(treat)));
                    f.insertItem(treat);
                } else {
                    goods = "";
                    worth = 1;
                }
                goodsId = BuiltInRegistries.ITEM.getKey(McAssistantMod.FORGED_COIN.get()).toString();
                count = 2;
                where = place != null ? place.immutable() : f.blockPosition().immutable();
                placeName = "the stores";
            }
            default -> { return null; }
        }
        Crime.Case c = new Crime.Case(caseId, id, kind, day, now % 24000L, where, placeName, f.getUUID(), f.displayNameCap(), victimId, victimName);
        c.worth = worth;
        c.coins = coins;
        c.goods = goods;
        c.goodsId = goodsId;
        c.goodsCount = count;
        c.broke = broke;
        c.brokeWhat = brokeWhat;
        c.noticeAt = noticeAt(kind, now, r);
        c.trail.add(onTheGround(level, f.blockPosition()));
        BlockPos home = Homes.homeOf(f);
        seen(level, v, f, c, kind == Kind.PICKPOCKET ? victim : null, purseBefore, home != null ? home : f.bedPos());
        drop(level, f, c);
        Crime.add(c);
        CompoundTag rec = Crime.folk(f.getUUID());
        rec.putLong("lastDeed", day);
        rec.putInt("offences", rec.getInt("offences") + 1);
        f.persona().remember(day, "I did something on day " + day + " I'm not proud of", 3);
        f.brain(kind.word + " at " + placeName);
        Crime.changed();
        return c;
    }

    /**
     * The deed done at once where the culprit stands, then off home with it (the tests, and the pictures' stage):
     * the same deed as a plan's.
     */
    @Nullable
    static Crime.Case commitNow(ServerLevel level, VillageFolkEntity f, Kind kind, @Nullable VillageFolkEntity victim, @Nullable BlockPos target) {
        Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
        if (v == null) return null;
        UUID animal = null;
        if (kind == Kind.POACHING) {
            Animal a = beast(level, v, f);
            if (a == null) return null;
            animal = a.getUUID();
        }
        if (target == null && kind == Kind.BURGLARY && victim != null) target = chestOf(level, victim);
        if (target == null && (kind == Kind.STORES || kind == Kind.SMUGGLING)) target = storeChest(level, v, f, false);
        BlockPos place = kind == Kind.FORGERY ? Villages.depot(level, v.id()) : null;
        Crime.Case c = commit(level, v, f, kind, victim, target, animal, place);
        if (c == null) return null;
        Plan p = new Plan(kind, v.id(), level.getDayTime(), level.getDayTime() + 24000L, true, "a test");
        p.done = c;
        p.stage = 2;
        p.awaySince = level.getGameTime();
        p.lastTrail = level.getGameTime();
        p.away = escape(level, v, f, c);
        PLANS.put(f.getUUID(), p);
        return c;
    }

    /** When the deed is found out: a purse soon, a chest when its people are next home, the rest at the next morning's count. */
    static long noticeAt(Kind kind, long now, RandomSource r) {
        long day = now / 24000L, t = now % 24000L;
        long morning = (day + 1) * 24000L + 1000L + r.nextInt(1500);
        return switch (kind) {
            case PICKPOCKET -> now + 400 + r.nextInt(2000);
            case BURGLARY -> now + 2000 + r.nextInt(4000);
            case VANDALISM -> t >= 12000L ? morning : now + 200 + r.nextInt(600);
            default -> morning;
        };
    }

    /** Out of a chest: the dearest single thing, as many of it as stays under the most a deed takes (at least one). */
    private static ItemStack lift(Container box, Predicate<ItemStack> want, int most, boolean bulk, int caseId) {
        int best = -1;
        double bestEach = -1;
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack s = box.getItem(i);
            if (s.isEmpty() || !want.test(s) || Crime.stolenCase(s) > 0 || Crime.clueCase(s) > 0) continue;
            double each = Prices.of(s.copyWithCount(1));
            if (each <= 0.05 || each > most) continue;
            double score = bulk ? Math.min(s.getCount(), most / each) * each : each;
            if (score > bestEach) { bestEach = score; best = i; }
        }
        if (best < 0) return ItemStack.EMPTY;
        ItemStack s = box.getItem(best);
        double each = Math.max(0.05, Prices.of(s.copyWithCount(1)));
        int n = (int) Math.max(1, Math.min(s.getCount(), Math.floor(most / each)));
        if (!bulk) n = Math.min(n, Math.max(1, (int) Math.ceil(4 / each)));
        ItemStack took = s.split(n);
        if (s.isEmpty()) box.setItem(best, ItemStack.EMPTY);
        Crime.mark(took, Crime.STOLEN, caseId);
        return took;
    }

    /** Something a folk would pass a coin for at the stores: a sweet thing, or bread. */
    static boolean treat(ItemStack s) {
        return s.is(Items.CAKE) || s.is(Items.COOKIE) || s.is(Items.PUMPKIN_PIE) || s.is(Items.HONEY_BOTTLE) || s.is(Items.BREAD)
            || s.is(Items.APPLE) || s.is(Items.MELON_SLICE);
    }

    // ------------------------------------------------------------------ who saw, who was about, what it dropped

    /**
     * Everybody of the town within a stone's throw, with what was in its purse and what it was doing; and anybody
     * who saw it: from how far, how well (the light, its attention, its eyes), who it thinks it was, what it saw. A
     * player who saw it is told what it saw.
     */
    static void seen(ServerLevel level, Villages.Village v, VillageFolkEntity f, Crime.Case c, @Nullable VillageFolkEntity unseeing,
                     int purseBefore, @Nullable BlockPos home) {
        long day = c.day;
        String heading = home == null ? "" : heading(c.where, home, v.centre());
        String outfit = outfit(f.stationTask());
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity w) || w.isShowcase() || !w.isAlive()) continue;
            double d = w.distanceTo(f);
            if (d <= 32 && !w.isBaby()) {
                c.near.add(new Crime.Near(w.getUUID(), w.displayNameCap(), (int) Math.round(d), w == f ? purseBefore : w.purse(), doing(w),
                    w.stationTask().name()));
            }
            if (w == f || w == unseeing || w.isSleeping()) continue;
            int cert = sight(level, w, f);
            if (cert < 0) continue;
            if (w.life().has(Social.Trait.CURIOUS)) cert += 10;
            if (w.isBaby()) cert -= 10;
            cert = Math.max(5, Math.min(95, cert));
            UUID thinks = null;
            String thinksName = "";
            boolean knows = Villages.headcount(v.id()) <= 40 || w.life().affinity(f.getUUID()) != 0;
            if (cert >= 65 && knows) {
                thinks = f.getUUID();
                thinksName = f.displayNameCap();
            } else if (cert >= 40) {
                // Half-seen: it thinks it knows who, rightly the nearer to plain it saw, else one of the same trade.
                VillageFolkEntity guess = w.getRandom().nextInt(25) < cert - 40 ? f : lookAlike(v.id(), f, w);
                if (guess != null && knows) {
                    thinks = guess.getUUID();
                    thinksName = guess.displayNameCap();
                }
            }
            String saw = sawWords(c, thinksName, cert, outfit, heading);
            c.witnesses.add(new Crime.Witness(w.getUUID(), w.displayNameCap(), false, (int) Math.round(d), cert, saw, thinks, thinksName,
                cert >= 35 ? f.stationTask().name() : ""));
            w.persona().remember(day, "I saw " + saw + " at " + c.place + " on day " + day, 3);
        }
        for (Player p : level.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            int cert = sight(level, p, f);
            if (cert < 0) continue;
            cert = Math.max(5, Math.min(95, cert + 10));
            UUID thinks = cert >= 50 ? f.getUUID() : null;
            String saw = sawWords(c, thinks == null ? "" : f.displayNameCap(), cert, outfit, heading);
            c.witnesses.add(new Crime.Witness(p.getUUID(), p.getName().getString(), true, (int) Math.round(p.distanceTo(f)), cert, saw, thinks,
                thinks == null ? "" : f.displayNameCap(), cert >= 35 ? f.stationTask().name() : ""));
            Crime.tellPlayer(p, "You saw " + saw + " at " + c.place + ". (Tell the watch: a guard will want to hear it.)");
        }
    }

    /** How well this one saw that one, 5 to 95, or -1 for not at all: how far, whether it was looking that way, the light. */
    static int sight(ServerLevel level, LivingEntity w, LivingEntity c) {
        double d = w.distanceTo(c);
        if (d > SIGHT || !w.hasLineOfSight(c)) return -1;
        Vec3 look = w.getViewVector(1.0F).normalize();
        Vec3 to = c.getEyePosition().subtract(w.getEyePosition()).normalize();
        double dot = look.dot(to);
        if (d > 10 && dot < 0.4) return -1;
        double cert = 100 - d * 4;
        if (dot < 0.4) cert *= 0.7;
        if (level.getMaxLocalRawBrightness(c.blockPosition()) < 7) cert *= 0.5;
        return Math.max(5, Math.min(95, (int) Math.round(cert)));
    }

    /** Another grown folk of the same trade, who might be taken for it at a distance (or null). */
    @Nullable
    private static VillageFolkEntity lookAlike(UUID village, VillageFolkEntity f, VillageFolkEntity w) {
        List<VillageFolkEntity> same = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity o && o != f && o != w && !o.isBaby() && !o.isShowcase() && o.stationTask() == f.stationTask()) same.add(o);
        }
        return same.isEmpty() ? null : same.get(w.getRandom().nextInt(same.size()));
    }

    /** What a witness saw, in its words. */
    static String sawWords(Crime.Case c, String thinksName, int cert, String outfit, String heading) {
        String act = switch (c.kind) {
            case PICKPOCKET -> "slip a hand into " + c.victimName + "'s purse";
            case BURGLARY -> "come out of " + c.place + " with something under their arm";
            case STORES -> "at the stores' chests after dark";
            case VANDALISM -> "break " + c.brokeWhat;
            case POACHING -> "take a beast from the pen";
            case FORGERY -> "pay at the stores with a coin that didn't look right";
            case SMUGGLING -> "carry a heavy load out of the stores after dark";
        };
        boolean bare = c.kind == Kind.STORES;
        if (cert >= 65 && !thinksName.isEmpty()) return thinksName + (bare ? " " : " ") + act;
        String who = cert >= 35 ? "somebody in " + outfit : "somebody, too far off to tell who,";
        return who + " " + act + (heading.isEmpty() ? "" : ", " + heading);
    }

    /** The clothes of a trade, as a witness would put them. */
    static String outfit(StationTask t) {
        return switch (t) {
            case FARM -> "a farmer's smock";
            case WOOD -> "a woodcutter's jerkin";
            case MINE -> "a miner's gear";
            case RANCH -> "a herder's coat";
            case GUARD -> "the watch's colours";
            case SMELT -> "a smelter's apron";
            case FISH -> "a fisher's oilskin";
            case STORE -> "a storekeeper's coat";
            case HAUL -> "a carrier's harness";
            case SMITH -> "a smith's apron";
            case TAILOR -> "a tailor's waistcoat";
            case BEEKEEP -> "a beekeeper's veil";
            case BREW -> "a brewer's robe";
            case ENCHANT -> "an enchanter's robe";
            case COOK -> "a cook's whites";
            case SHOP -> "a shopkeeper's apron";
            case SCOUT -> "a scout's cloak";
            case HUNT -> "a hunter's leathers";
            case BANK -> "a banker's coat";
            case CAVE -> "a cave dweller's kit";
            case FLETCHER -> "a fletcher's apron and quiver";      // [fletcher]
            case GOLEMS -> "a golem keeper's riveted apron";      // [golems]
            default -> "plain clothes";
        };
    }

    /** What it was doing, as the town would say (an alibi, or not). */
    static String doing(VillageFolkEntity f) {
        if (f.isSleeping()) return "asleep";
        String h = f.hobbyNow();
        if (h != null && f.tickCount - f.lastLeisureTick < 200) return h;
        return f.offWorkNow() ? "about the town" : "at work, " + f.stationTask().label;
    }

    /** "off east, toward the east houses", "toward the square". */
    static String heading(BlockPos from, BlockPos to, BlockPos heart) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        if (dx * dx + dz * dz < 9) return "";
        int ox = to.getX() - heart.getX(), oz = to.getZ() - heart.getZ();
        if (Math.abs(ox) < TownPlan.PLAZA && Math.abs(oz) < TownPlan.PLAZA) return "going toward the square";
        return "going off " + compass(dx, dz) + ", toward the " + compass(ox, oz) + " houses";
    }

    static String compass(int dx, int dz) {
        double ang = Math.toDegrees(Math.atan2(dx, -dz));
        String[] names = { "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west" };
        return names[(int) Math.floorMod(Math.round(ang / 45.0), 8L)];
    }

    /**
     * Something it dropped at the scene, as the nervous and the careless do: a keepsake with its owner's mark, a
     * tool of its trade, or some odd thing out of its pack. Left lying where it fell (no folk picks it up, nor the
     * sweeper) till the watch takes it in or a player picks it up.
     */
    static void drop(ServerLevel level, VillageFolkEntity f, Crime.Case c) {
        boolean nervous = f.life().has(Social.Trait.SHY) || honesty(f) < 35;
        if (f.getRandom().nextInt(100) >= (nervous ? 35 : 15)) return;
        ItemStack chosen = ItemStack.EMPTY;
        String trade = "";
        UUID owner = null;
        for (ItemStack s : f.getInventoryItems()) {
            if (s.isEmpty() || !Homes.isKeepsake(s)) continue;
            chosen = s;
            String mark = s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getString("mca_keepsake");
            try { owner = UUID.fromString(mark); } catch (IllegalArgumentException e) { owner = null; }
            break;
        }
        if (chosen.isEmpty()) {
            for (ItemStack s : f.getInventoryItems()) {
                if (s.isEmpty() || !tradeTool(s)) continue;
                chosen = s;
                trade = f.stationTask().name();
                break;
            }
        }
        if (chosen.isEmpty()) {
            for (ItemStack s : f.getInventoryItems()) {
                if (s.isEmpty() || s.get(DataComponents.FOOD) != null || Crime.stolenCase(s) > 0 || Market.isCoin(s)) continue;
                chosen = s;
                break;
            }
        }
        if (chosen.isEmpty()) return;
        ItemStack one = chosen.copyWithCount(1);
        chosen.shrink(1);
        Crime.mark(one, Crime.CLUE, c.id);
        ItemEntity e = new ItemEntity(level, c.where.getX() + 0.5, c.where.getY() + 0.3, c.where.getZ() + 0.5, one);
        e.setPickUpDelay(40);
        e.setUnlimitedLifetime();
        e.addTag(Sweepers.PLAYERS);                 // no folk's magnet and no broom takes it up
        e.addTag(Crime.CLUE);
        level.addFreshEntity(e);
        c.droppedEntity = e.getUUID();
        c.dropped = Crafts.named(one.copy());
        c.droppedOwner = owner;
        c.droppedTrade = trade;
    }

    /** A tool that tells its trade: a hoe, a pick, an axe, shears, a rod, a hammer of a smith's. */
    static boolean tradeTool(ItemStack s) {
        return s.getItem() instanceof net.minecraft.world.item.DiggerItem || s.getItem() instanceof net.minecraft.world.item.ShearsItem
            || s.getItem() instanceof net.minecraft.world.item.FishingRodItem || s.is(Items.BRUSH) || s.is(Items.FLINT_AND_STEEL);
    }

    // ------------------------------------------------------------------ targets

    /** Where a pickpocket goes: the market on market day, the tavern of an evening, else the square. */
    static BlockPos where(ServerLevel level, Villages.Village v, long now) {
        long day = now / 24000L, t = now % 24000L;
        UUID id = v.id();
        BlockPos market = Villages.builtAt(id, "market");
        if (market != null && Market.marketDay(id, day)) return market;
        BlockPos tavern = Villages.builtAt(id, "tavern");
        if (tavern != null && t >= 9000L) return tavern;
        if (market != null) return market;
        return v.centre();
    }

    /** "the market", "the tavern", "Bree's house", "the stores", "the square", "the east side of town". */
    static String placeName(ServerLevel level, Villages.Village v, BlockPos at) {
        UUID id = v.id();
        BlockPos market = Villages.builtAt(id, "market");
        if (market != null && market.distSqr(at) <= 12 * 12) return "the market";
        BlockPos tavern = Villages.builtAt(id, "tavern");
        if (tavern != null && tavern.distSqr(at) <= 10 * 10) return "the tavern";
        Homes.Home h = Homes.homeAt(id, at);
        if (h != null && !h.members.isEmpty()) return nameOf(level, id, h.members.get(0)) + "'s house";
        BlockPos depot = Villages.depot(level, id);
        if (depot != null && depot.distSqr(at) <= 7 * 7) return "the stores";
        int dx = at.getX() - v.centre().getX(), dz = at.getZ() - v.centre().getZ();
        if (TownPlan.isSquare(dx, dz)) return "the square";
        return "the " + compass(dx, dz) + " side of town";
    }

    /** "on the east avenue", "on the ring street". */
    static String streetOf(Villages.Village v, BlockPos at) {
        int dx = at.getX() - v.centre().getX(), dz = at.getZ() - v.centre().getZ();
        if (Math.abs(dx) <= TownPlan.AVENUE + 1) return "on the " + (dz < 0 ? "north" : "south") + " avenue";
        if (Math.abs(dz) <= TownPlan.AVENUE + 1) return "on the " + (dx < 0 ? "west" : "east") + " avenue";
        return "on the " + compass(dx, dz) + " streets";
    }

    static String nameOf(ServerLevel level, UUID village, UUID folk) {
        for (AssistantEntity a : Villages.folkOf(village)) if (a.getUUID().equals(folk)) return a.displayNameCap();
        return "a neighbour";
    }

    /** A purse worth lifting near it: grown, awake, with a few coins, not the watch, not anybody it loves (unless it hates them). */
    @Nullable
    static VillageFolkEntity mark(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        VillageFolkEntity best = null;
        double bd = Double.MAX_VALUE;
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, f.getBoundingBox().inflate(10.0),
                o -> o != f && o.isAlive() && !o.isBaby() && !o.isSleeping() && v.id().equals(o.ownerId()))) {
            if (o.purse() < 3 || o.stationTask() == StationTask.GUARD) continue;
            if (o.getUUID().equals(f.life().partner()) || f.life().affinity(o.getUUID()) >= Social.CLOSE) continue;
            double d = o.distanceToSqr(f) - o.purse() * 2.0;
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }

    /** A better-off neighbour with a chest at home, not anybody it loves. */
    @Nullable
    static VillageFolkEntity neighbour(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        VillageFolkEntity best = null;
        int richest = 20;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity o) || o == f || o.isBaby() || o.isShowcase()) continue;
            if (o.getUUID().equals(f.life().partner()) || f.life().affinity(o.getUUID()) >= Social.FRIEND) continue;
            Homes.Home h = Homes.homeOf(v.id(), o.getUUID());
            if (h == null || h.members.contains(f.getUUID())) continue;
            int w = Wealth.worth(o);
            if (w > richest && chestOf(level, o) != null) { richest = w; best = o; }
        }
        return best;
    }

    /** A folk's household chest, if it has one standing. */
    @Nullable
    static BlockPos chestOf(ServerLevel level, VillageFolkEntity o) {
        UUID village = o.ownerId();
        if (village == null) return null;
        Homes.Home h = Homes.homeOf(village, o.getUUID());
        return h == null ? null : Homes.chestOf(level, village, h);
    }

    /** One of the stores' chests with something worth taking in it (food, for the hungry). */
    @Nullable
    static BlockPos storeChest(ServerLevel level, Villages.Village v, VillageFolkEntity f, boolean food) {
        BlockPos best = null;
        double nearest = Double.MAX_VALUE;
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (!(level.getBlockEntity(p) instanceof Container box)) continue;
            boolean any = false;
            for (int i = 0; i < box.getContainerSize() && !any; i++) {
                ItemStack s = box.getItem(i);
                any = !s.isEmpty() && (!food || s.get(DataComponents.FOOD) != null) && Prices.of(s.copyWithCount(1)) > 0.05;
            }
            if (!any) continue;
            double d = p.distSqr(f.blockPosition());
            if (d < nearest) { nearest = d; best = p; }
        }
        return best;
    }

    /** Something to break: the rival's window, else a lamp on a post, else a garden fence, the nearest first. */
    @Nullable
    static BlockPos toBreak(ServerLevel level, Villages.Village v, VillageFolkEntity f, @Nullable VillageFolkEntity grudge) {
        if (grudge != null) {
            BlockPos home = Homes.homeOf(grudge);
            if (home != null) {
                BlockPos pane = nearest(level, home, 7, 0, 6, Mischief::glass, f.blockPosition());
                if (pane != null) return pane;
            }
        }
        BlockPos from = f.blockPosition();
        BlockPos lamp = nearest(level, from, 20, -4, 8, st -> isLight(st), from);
        if (lamp != null && level.getBlockState(lamp.below()).getBlock() instanceof FenceBlock) return lamp;
        return nearest(level, from, 16, -3, 4, st -> st.getBlock() instanceof FenceBlock && st.is(BlockTags.WOODEN_FENCES), from);
    }

    private static boolean glass(BlockState st) {
        return st.is(Blocks.GLASS_PANE) || st.getBlock() instanceof net.minecraft.world.level.block.StainedGlassPaneBlock;
    }

    static boolean isLight(BlockState st) {
        return st.is(Blocks.LANTERN) || st.is(Blocks.TORCH) || st.is(Blocks.SOUL_LANTERN);
    }

    /** A window, a lamp or a wooden fence: what a vandal breaks. */
    static boolean breakable(BlockState st) {
        return glass(st) || isLight(st) || st.getBlock() instanceof FenceBlock && st.is(BlockTags.WOODEN_FENCES);
    }

    @Nullable
    private static BlockPos nearest(ServerLevel level, BlockPos c, int r, int dy0, int dy1, Predicate<BlockState> what, BlockPos from) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = dy0; dy <= dy1; dy++) {
                    m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    if (!level.isLoaded(m) || !what.test(level.getBlockState(m))) continue;
                    double d = m.distSqr(from);
                    if (d < bd) { bd = d; best = m.immutable(); }
                }
            }
        }
        return best;
    }

    /** A grown farm beast of the town's, in its pen or about the town. */
    @Nullable
    static Animal beast(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        int reach = Math.max(24, Villages.townReach(v.id()));
        Animal best = null;
        double bd = Double.MAX_VALUE;
        for (Animal a : level.getEntitiesOfClass(Animal.class, new AABB(v.centre()).inflate(reach, 12, reach),
                a -> a.isAlive() && !a.isBaby() && (a instanceof Sheep || a instanceof Chicken || a instanceof Pig || a instanceof Cow
                    || a instanceof Rabbit))) {
            if (a.isLeashed() || a.hasCustomName()) continue;
            double d = a.distanceToSqr(f);
            if (d < bd) { bd = d; best = a; }
        }
        return best;
    }

    @Nullable
    static VillageFolkEntity rancher(UUID village) {
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == StationTask.RANCH && !f.isBaby()) return f;
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    static boolean near(VillageFolkEntity f, BlockPos to, double reach) {
        double dx = f.getX() - (to.getX() + 0.5), dz = f.getZ() - (to.getZ() + 0.5);
        return dx * dx + dz * dz <= reach * reach && Math.abs(f.getY() - to.getY()) <= 3.5;
    }

    /** On its way somewhere: a fresh path every two seconds, or when the last is done. */
    static void walk(VillageFolkEntity f, BlockPos to, double speed) {
        Integer last = PATHED.get(f.getUUID());
        if (last == null || f.tickCount - last > 40 || f.tickCount < last || f.getNavigation().isDone()) {
            f.walkTo(to, speed);
            PATHED.put(f.getUUID(), f.tickCount);
        }
    }

    static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
