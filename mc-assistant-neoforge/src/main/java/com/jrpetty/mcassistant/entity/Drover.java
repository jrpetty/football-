package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Animals for the pen. A rancher whose pen has no pair to breed goes out with one of the leads it
 * brought, finds a wild sheep, cow, pig or chicken, puts the lead on it and walks it home, as a
 * player would; then another of the same kind, until there is a pair. Only if there is nothing
 * wild for fifty blocks round does the village buy a drover's pair (two sheep and two hens) —
 * once, and in the chronicle.
 */
public final class Drover {

    /** The tag on an animal that is the village's own (brought home or bought): not game for the hunters. */
    public static final String HERD = "mca_herd";

    /** What a drover asks for a pair of sheep and a pair of hens. */
    static final int DROVER_PRICE = 12;

    private Drover() {}

    /** One animal being fetched. */
    static final class Drive {
        final UUID animal;
        final BlockPos pen;
        final long started;
        boolean leading;
        int walked = -1000;
        /** Where the animal was last seen, for the lead if it dies or wanders off loaded ground. */
        BlockPos seen;

        Drive(UUID animal, BlockPos pen, long started) {
            this.animal = animal;
            this.pen = pen;
            this.started = started;
            this.seen = pen;
        }
    }

    private static final Map<UUID, Drive> DRIVES = new ConcurrentHashMap<>();
    /** The day each rancher last went looking and found nothing to fetch. */
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    /** Animals a fetch failed on, and the day: not tried again that day. */
    private static final Map<UUID, Long> GAVE_UP = new ConcurrentHashMap<>();

    public static void resetForTests() {
        DRIVES.clear();
        LOOKED.clear();
        GAVE_UP.clear();
    }

    /** Out fetching an animal: the day's work waits. */
    public static boolean busy(VillageFolkEntity f) {
        return DRIVES.containsKey(f.getUUID());
    }

    /** How far the rancher looks for wild animals. */
    static final int RANGE = 48;

    /**
     * The rancher's look round: if the pen has no breeding pair, go and fetch an animal (with a
     * lead), or — nothing wild anywhere near — have the drover's pair brought in. Returns whether
     * it set off (or the pair came).
     */
    public static boolean consider(VillageFolkEntity f, ServerLevel level) {
        if (f.stationTask() != StationTask.RANCH || busy(f) || f.workZone() == null) return false;
        UUID village = f.ownerId();
        if (village == null || !level.isDay() || Raids.underAlarm(village)) return false;
        BlockPos pen = f.workZone().center();
        if (!Land.areaLoaded(level, pen, RANGE)) return false;
        Map<EntityType<?>, Integer> herd = herd(level, pen, Math.max(8, Math.min(16, f.workZone().radius())));
        boolean pair = false;
        for (int n : herd.values()) if (n >= 2) pair = true;
        // A pair to breed is enough — unless none of it is sheep: the wool is the village's beds.
        if (pair && herd.getOrDefault(EntityType.SHEEP, 0) >= 2) return false;
        long today = level.getDayTime() / 24000L;
        Animal wild = wild(level, pen, herd, today, pair ? EntityType.SHEEP : null);
        if (wild != null && f.countCarried(s -> s.is(Items.LEAD)) < 1) {
            // A lead from the stores (the market sells them, and players bring them).
            Villages.Village v = Villages.get(village);
            if (v != null && Crafts.take(level, v, s -> s.is(Items.LEAD), 1)) {
                ItemStack back = f.insertItem(new ItemStack(Items.LEAD));
                if (!back.isEmpty()) Crafts.store(level, v, back);
            }
        }
        if (wild != null && f.countCarried(s -> s.is(Items.LEAD)) > 0) {
            DRIVES.put(f.getUUID(), new Drive(wild.getUUID(), pen, level.getGameTime()));
            f.clearQueue();
            f.brain("off to fetch a wild " + kind(wild) + " home");
            FolkTalk.speak(f, "There's a " + kind(wild) + " out there with no home. I'll fetch it in.");
            return true;
        }
        if (wild != null) return false;                                  // animals, but no lead: the stores may send one
        if (pair) return false;                                          // the drover's pair is for an empty pen
        if (LOOKED.getOrDefault(f.getUUID(), -1L) == today) return false;
        LOOKED.put(f.getUUID(), today);
        // Nothing wild for fifty blocks: the drover's pair, once — bought out of the treasury.
        if (Ledger.note(village, "kit.drove") != null) return false;
        if (Ledger.coins(village) < DROVER_PRICE) {
            Market.saveFor(village, "drover", DROVER_PRICE, level.getGameTime());       // the wages leave it put by
            return false;
        }
        Market.bought(village, "drover");
        Ledger.takeCoins(village, DROVER_PRICE);
        Economy.spent(village, DROVER_PRICE);
        Ledger.note(village, "kit.drove", Long.toString(today));
        int put = 0;
        for (EntityType<? extends Animal> type : List.<EntityType<? extends Animal>>of(EntityType.SHEEP, EntityType.SHEEP,
                EntityType.CHICKEN, EntityType.CHICKEN)) {
            Animal a = type.create(level);
            if (a == null) continue;
            int x = pen.getX() + level.getRandom().nextInt(5) - 2, z = pen.getZ() + level.getRandom().nextInt(5) - 2;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            a.moveTo(x + 0.5, y, z + 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
            a.setPersistenceRequired();
            a.addTag(HERD);
            if (level.addFreshEntity(a)) put++;
        }
        if (put == 0) return false;
        Villages.tell(village, today, "A drover came by and sold " + Villages.name(village)
            + " a pair of sheep and a pair of hens for " + DROVER_PRICE + " coin: there was nothing wild to be had for miles.");
        FolkTalk.speak(f, "No wild animals for miles, so I bought a drover's pair. Sheep and hens!");
        f.brain("bought the drover's pair");
        return true;
    }

    /** Walk the fetch a step: to the animal, the lead on, and home with it. Called every half-second. */
    public static void drive(VillageFolkEntity f, ServerLevel level) {
        Drive d = DRIVES.get(f.getUUID());
        if (d == null) return;
        Entity e = level.getEntity(d.animal);
        Animal a = e instanceof Animal an && an.isAlive() ? an : null;
        if (a != null) d.seen = a.blockPosition();
        boolean tooLong = level.getGameTime() - d.started > 3600L;
        if (a == null || tooLong || !level.isDay() || Raids.underAlarm(f.ownerId())) {
            if (a == null && d.leading) pickUpLeads(f, level, d.seen);   // it died on the lead: the lead lies there
            if (tooLong) GAVE_UP.put(d.animal, level.getDayTime() / 24000L);   // one it couldn't get home: not again today
            stop(f, a, d, false);
            return;
        }
        if (!d.leading) {
            if (f.distanceToSqr(a) < 3.0 * 3.0) {
                if (f.removeMatching(s -> s.is(Items.LEAD), 1) != 1) { stop(f, a, d, false); return; }
                a.setLeashedTo(f, true);
                d.leading = true;
                f.brain("the lead on a " + kind(a) + ", walking it home");
                return;
            }
            if (f.getNavigation().isDone() || f.tickCount - d.walked > 60) {
                f.walkTo(a.blockPosition(), 0.8D);
                d.walked = f.tickCount;
            }
            return;
        }
        if (!a.isLeashed()) {                                            // it slipped the lead: pick it up, after it again
            pickUpLeads(f, level, a.blockPosition());
            d.leading = false;
            return;
        }
        // Not too far ahead: a lead pulled taut snaps. Wait for it to come up.
        if (f.distanceToSqr(a) > 5.0 * 5.0) {
            f.getNavigation().stop();
            f.getLookControl().setLookAt(a);
            return;
        }
        // Home: the animal inside the pen's ground (a led animal hangs back a few blocks on its lead).
        double dx = a.getX() - (d.pen.getX() + 0.5), dz = a.getZ() - (d.pen.getZ() + 0.5);
        if (dx * dx + dz * dz < 6.0 * 6.0) {
            stop(f, a, d, true);
            return;
        }
        if (f.getNavigation().isDone() || f.tickCount - d.walked > 60) {
            f.walkTo(d.pen, 0.6D);
            d.walked = f.tickCount;
        }
    }

    /**
     * An animal still on this folk's lead with no fetch under way — the server restarted mid-fetch
     * (the fetch is not saved, the lead is): let it off where it stands and put the lead away.
     */
    public static void tidy(VillageFolkEntity f, ServerLevel level) {
        if (busy(f)) return;
        for (Animal a : level.getEntitiesOfClass(Animal.class, f.getBoundingBox().inflate(12),
                x -> x.isLeashed() && x.getLeashHolder() == f)) {
            a.dropLeash(true, false);
            ItemStack left = f.insertItem(new ItemStack(Items.LEAD));
            if (!left.isEmpty()) f.spawnAtLocation(left);
        }
    }

    /** The leads lying about a spot, back into the pack. */
    private static void pickUpLeads(VillageFolkEntity f, ServerLevel level, BlockPos at) {
        for (net.minecraft.world.entity.item.ItemEntity lead : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new AABB(at).inflate(12), i -> i.isAlive() && i.getItem().is(Items.LEAD))) {
            ItemStack left = f.insertItem(lead.getItem().copy());
            if (left.isEmpty()) lead.discard();
            else lead.setItem(left);
        }
    }

    /** The fetch is over: the lead off (and back in the pack), home or not. */
    private static void stop(VillageFolkEntity f, @Nullable Animal a, Drive d, boolean home) {
        DRIVES.remove(f.getUUID());
        if (a != null && a.isLeashed() && a.getLeashHolder() == f) {
            a.dropLeash(true, false);
            ItemStack left = f.insertItem(new ItemStack(Items.LEAD));
            if (!left.isEmpty()) f.spawnAtLocation(left);
        }
        if (home && a != null) {
            a.setPersistenceRequired();
            a.addTag(HERD);
            f.brain("brought a wild " + kind(a) + " home to the pen");
            UUID village = f.ownerId();
            if (village != null) {
                Villages.tell(village, f.level().getDayTime() / 24000L,
                    f.displayNameCap() + " brought a wild " + kind(a) + " home to the pen on a lead.");
            }
        }
    }

    /** The adult animals in the pen, by kind. */
    static Map<EntityType<?>, Integer> herd(ServerLevel level, BlockPos pen, int r) {
        Map<EntityType<?>, Integer> out = new HashMap<>();
        for (Animal a : level.getEntitiesOfClass(Animal.class, new AABB(pen).inflate(r, 6, r),
                a -> a.isAlive() && !a.isBaby() && farmed(a))) {
            out.merge(a.getType(), 1, Integer::sum);
        }
        return out;
    }

    private static boolean farmed(Animal a) {
        return a instanceof Sheep || a instanceof Cow || a instanceof Pig || a instanceof Chicken;
    }

    /**
     * The wild animal to fetch: one to make a pair with what the pen has, else the kind most about.
     * Never one with a name or kept on purpose, nor one in somebody's pen (a ranch's ground, or
     * inside fences): a player's animals stay a player's.
     */
    @Nullable
    static Animal wild(ServerLevel level, BlockPos pen, Map<EntityType<?>, Integer> herd, long today,
                       @Nullable EntityType<?> only) {
        List<Animal> about = level.getEntitiesOfClass(Animal.class, new AABB(pen).inflate(RANGE, 16, RANGE),
            a -> a.isAlive() && !a.isBaby() && farmed(a) && !a.isLeashed() && (only == null || a.getType() == only)
                && !a.hasCustomName() && !a.isPersistenceRequired()
                && GAVE_UP.getOrDefault(a.getUUID(), -1L) != today
                && a.distanceToSqr(pen.getX() + 0.5, a.getY(), pen.getZ() + 0.5) > 12.0 * 12.0
                && !penned(level, a));
        if (about.isEmpty()) return null;
        Map<EntityType<?>, Integer> counts = new HashMap<>();
        for (Animal a : about) counts.merge(a.getType(), 1, Integer::sum);
        EntityType<?> best = null;
        int bestScore = -1;
        for (Map.Entry<EntityType<?>, Integer> c : counts.entrySet()) {
            int score = c.getValue() + (herd.containsKey(c.getKey()) ? 100 : 0);
            if (score > bestScore) { bestScore = score; best = c.getKey(); }
        }
        Animal nearest = null;
        double nd = Double.MAX_VALUE;
        for (Animal a : about) {
            if (a.getType() != best) continue;
            double dd = a.distanceToSqr(pen.getX() + 0.5, a.getY(), pen.getZ() + 0.5);
            if (dd < nd) { nd = dd; nearest = a; }
        }
        return nearest;
    }

    /** Is this animal in somebody's pen: on any village's ranch, or with fencing close about it? */
    static boolean penned(ServerLevel level, Animal a) {
        BlockPos at = a.blockPosition();
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            for (AssistantEntity f : Villages.folkOf(v.id())) {
                if (f.stationTask() != StationTask.RANCH || f.workZone() == null) continue;
                BlockPos c = f.workZone().center();
                if (Math.max(Math.abs(c.getX() - at.getX()), Math.abs(c.getZ() - at.getZ())) <= f.workZone().radius() + 2) return true;
            }
        }
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-4, -1, -4), at.offset(4, 1, 4))) {
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(p);
            if (st.is(net.minecraft.tags.BlockTags.FENCES) || st.is(net.minecraft.tags.BlockTags.FENCE_GATES)) return true;
        }
        return false;
    }

    static String kind(Animal a) {
        if (a instanceof Sheep) return "sheep";
        if (a instanceof Cow) return "cow";
        if (a instanceof Pig) return "pig";
        if (a instanceof Chicken) return "hen";
        return a.getType().getDescription().getString().toLowerCase();
    }
}
