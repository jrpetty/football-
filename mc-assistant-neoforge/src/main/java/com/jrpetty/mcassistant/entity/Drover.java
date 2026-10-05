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
 * Animals for the pen. A rancher whose pen has no pair to breed goes out and fetches a wild sheep,
 * cow, pig or chicken home, as a player would: it holds out what the animal eats (wheat for sheep
 * and cows, a carrot for a pig, seeds for a hen) and walks slowly home with the animal following
 * its hand; or, with no feed to hand, it puts one of its leads on it. Then another of the same
 * kind, until there is a pair. Only if there is nothing
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
        /** Fetched by its feed held out, not on a lead: what it is holding, and what it held before. */
        @Nullable ItemStack lure;
        ItemStack heldBefore = ItemStack.EMPTY;
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
        PENS.clear();
        PEN_LOOKED.clear();
        OPENED.clear();
    }

    // ------------------------------------------------------------------ the pen

    /**
     * The village's pen, once it is built (a fenced square with a gate): its middle and its gate.
     * The rancher's ground is the pen from then on, every animal brought home goes in through the
     * gate, and strays from the herd are fetched back.
     */
    public record Pen(BlockPos centre, BlockPos gate) {
        public boolean inside(BlockPos p) {
            return Math.abs(p.getX() - centre.getX()) <= 2 && Math.abs(p.getZ() - centre.getZ()) <= 2;
        }
    }

    private static final Map<UUID, Pen> PENS = new ConcurrentHashMap<>();
    /** When each village's pen was last looked for (the gate is minded every half-second by every folk). */
    private static final Map<UUID, Long> PEN_LOOKED = new ConcurrentHashMap<>();

    /** The pen, looked for at most every half-minute. */
    @Nullable
    static Pen penNow(UUID village, long now) {
        Long at = PEN_LOOKED.get(village);
        if (at != null && now - at < 600) return PENS.get(village);
        PEN_LOOKED.put(village, now);
        Pen p = pen(village);
        if (p == null) PENS.remove(village);
        return p;
    }
    /** Gates the folk opened, and when: they shut them behind them. */
    private static final Map<Long, Long> OPENED = new ConcurrentHashMap<>();

    /** The village's pen, or null before it has one. */
    @Nullable
    public static Pen pen(UUID village) {
        Pen cached = PENS.get(village);
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (!"pen".equals(b.structure())) continue;
            if (cached != null && cached.centre().equals(b.anchor())) return cached;
            BlockPos gate = null;
            for (com.jrpetty.mcassistant.entity.goal.BuildGoal.Placement p
                    : com.jrpetty.mcassistant.entity.goal.BuildGoal.plan("pen", b.anchor(), b.facing(), 13)) {
                if (p.part() == com.jrpetty.mcassistant.entity.goal.BuildGoal.Part.GATE) gate = p.pos();
            }
            if (gate == null) continue;
            Pen pen = new Pen(b.anchor().immutable(), gate.immutable());
            PENS.put(village, pen);
            return pen;
        }
        return null;
    }

    /**
     * From a folk's tick: the pen's gate opens for a folk of the village passing through it (or
     * bringing an animal in), and is shut behind them — so the herd stays in. A gate a player
     * opened is the player's to shut.
     */
    public static void gate(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        if (village == null) return;
        Pen p = penNow(village, level.getGameTime());
        if (p == null || !level.isLoaded(p.gate())) return;
        BlockPos g = p.gate();
        net.minecraft.world.level.block.state.BlockState st = level.getBlockState(g);
        if (!(st.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock)) return;
        boolean open = st.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN);
        long now = level.getGameTime();
        if (!open && passing(f, p, g)) {
            level.setBlock(g, st.setValue(net.minecraft.world.level.block.FenceGateBlock.OPEN, true), 10);
            level.playSound(null, g, net.minecraft.sounds.SoundEvents.FENCE_GATE_OPEN, net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1.0F);
            OPENED.put(g.asLong(), now);
            return;
        }
        Long since = OPENED.get(g.asLong());
        if (open && since != null && now - since > 30) {
            for (VillageFolkEntity x : level.getEntitiesOfClass(VillageFolkEntity.class, new AABB(g).inflate(7),
                    x -> village.equals(x.ownerId()))) {
                if (passing(x, p, g)) return;                                  // somebody still on the way through
            }
            level.setBlock(g, st.setValue(net.minecraft.world.level.block.FenceGateBlock.OPEN, false), 10);
            level.playSound(null, g, net.minecraft.sounds.SoundEvents.FENCE_GATE_CLOSE, net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1.0F);
            OPENED.remove(g.asLong());
        }
    }

    /** On its way through this pen's gate: going in or out, or bringing an animal home to it. */
    private static boolean passing(VillageFolkEntity f, Pen p, BlockPos g) {
        if (f.distanceToSqr(g.getX() + 0.5, g.getY(), g.getZ() + 0.5) > 6.0 * 6.0) return false;
        Drive d = DRIVES.get(f.getUUID());
        if (d != null && d.pen.equals(p.centre())) return true;
        BlockPos to = f.getNavigation().getTargetPos();
        return to != null && !f.getNavigation().isDone() && p.inside(to) != p.inside(f.blockPosition());
    }

    /** One of the village's own animals got out: back into the pen with it. Returns whether it set off. */
    static boolean fetchStray(VillageFolkEntity f, ServerLevel level, Pen p) {
        UUID village = f.ownerId();
        if (village == null || busy(f)) return false;
        Animal stray = null;
        double best = Double.MAX_VALUE;
        for (Animal a : level.getEntitiesOfClass(Animal.class, new AABB(p.centre()).inflate(32, 8, 32),
                a -> a.isAlive() && a.getTags().contains(HERD) && !a.isLeashed() && !p.inside(a.blockPosition()))) {
            double dd = a.distanceToSqr(f);
            if (dd < best) { best = dd; stray = a; }
        }
        if (stray == null) return false;
        java.util.function.Predicate<ItemStack> feed = feedFor(stray);
        if (feed != null && f.countCarried(feed) < 1 && f.villageCentre() != null) {
            f.drawFrom(f.villageCentre(), feed, 2, Villages.storesRadius(village));
        }
        Drive d = new Drive(stray.getUUID(), p.centre(), level.getGameTime());
        if (feed != null && f.countCarried(feed) > 0) {
            for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && feed.test(s)) { d.lure = s.copyWithCount(1); break; }
        } else if (f.countCarried(s -> s.is(Items.LEAD)) < 1) {
            return false;
        }
        DRIVES.put(f.getUUID(), d);
        f.clearQueue();
        f.brain("one of the herd got out: bringing it back to the pen");
        return true;
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
        Pen built = pen(village);
        if (built != null && built.centre().equals(pen) && fetchStray(f, level, built)) return true;
        Map<EntityType<?>, Integer> herd = herd(level, pen, Math.max(8, Math.min(16, f.workZone().radius())));
        boolean pair = false;
        for (int n : herd.values()) if (n >= 2) pair = true;
        // A pair to breed is enough — unless none of it is sheep: the wool is the village's beds.
        if (pair && herd.getOrDefault(EntityType.SHEEP, 0) >= 2) return false;
        long today = level.getDayTime() / 24000L;
        Animal wild = wild(level, pen, herd, today, pair ? EntityType.SHEEP : null);
        // Feed held out is how anybody fetches a sheep home: a lead only when there is none to hand.
        if (wild != null) {
            java.util.function.Predicate<ItemStack> feed = feedFor(wild);
            if (feed != null && f.countCarried(feed) < 1 && f.villageCentre() != null) {
                f.drawFrom(f.villageCentre(), feed, 2, Villages.storesRadius(village));
            }
            if (feed != null && f.countCarried(feed) > 0) {
                Drive d = new Drive(wild.getUUID(), pen, level.getGameTime());
                for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && feed.test(s)) { d.lure = s.copyWithCount(1); break; }
                DRIVES.put(f.getUUID(), d);
                f.clearQueue();
                f.brain("off to coax a wild " + kind(wild) + " home with " + Crafts.named(d.lure));
                FolkTalk.speak(f, "There's a " + kind(wild) + " out there with no home. A bit of " + Crafts.named(d.lure)
                    + " and it'll follow me in.");
                return true;
            }
        }
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
        if (d.lure != null) {
            lure(f, a, d);
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
        if (home(f, a, d)) {
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
    /**
     * Coaxing an animal home with its feed held out: up to it, and then slowly home with it
     * following the hand; a step back for it when it lags, as anybody leading a sheep with a sheaf
     * of wheat does. In the pen it is given the feed, and the hand put away.
     */
    private static void lure(VillageFolkEntity f, Animal a, Drive d) {
        if (!d.lure.isEmpty() && !f.getMainHandItem().is(d.lure.getItem())) {
            d.heldBefore = f.getMainHandItem().copy();
            f.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, d.lure.copy());
        }
        double gap = f.distanceToSqr(a);
        if (home(f, a, d)) {
            // Home: it gets what it followed.
            f.removeMatching(s -> s.is(d.lure.getItem()), 1);
            if (a.getAge() == 0 && !a.isBaby()) a.setInLove(null);
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            stop(f, a, d, true);
            return;
        }
        if (gap > 7.0 * 7.0) {
            // Too far for it to smell the feed: walk up to it again.
            if (f.getNavigation().isDone() || f.tickCount - d.walked > 40) {
                f.walkTo(a.blockPosition(), 0.8D);
                d.walked = f.tickCount;
            }
            return;
        }
        // Near enough: it follows the hand.
        a.getNavigation().moveTo(f, 1.1D);
        a.getLookControl().setLookAt(f, 30.0F, 30.0F);
        f.getLookControl().setLookAt(a);
        if (gap > 4.0 * 4.0) {
            f.getNavigation().stop();                                    // let it catch up
            return;
        }
        if (f.getNavigation().isDone() || f.tickCount - d.walked > 40) {
            f.walkTo(d.pen, 0.5D);
            d.walked = f.tickCount;
        }
    }

    /**
     * A hunter (or anybody) who has come on an animal the village's pens are short of brings it
     * home alive instead: with its feed held out, or on a lead. Returns whether it set off.
     */
    public static boolean fetchHome(VillageFolkEntity f, ServerLevel level, Animal a) {
        if (busy(f) || f.ownerId() == null) return false;
        BlockPos pen = penShortOf(level, f.ownerId(), a.getType());
        if (pen == null) return false;
        java.util.function.Predicate<ItemStack> feed = feedFor(a);
        Drive d = new Drive(a.getUUID(), pen, level.getGameTime());
        if (feed != null && f.countCarried(feed) > 0) {
            for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && feed.test(s)) { d.lure = s.copyWithCount(1); break; }
        } else if (f.countCarried(s -> s.is(Items.LEAD)) < 1) {
            return false;
        }
        DRIVES.put(f.getUUID(), d);
        f.clearQueue();
        f.brain("bringing a wild " + kind(a) + " home alive for the pens");
        FolkTalk.speak(f, "The pens are short of " + kind(a) + "s. This one's coming home with me, alive.");
        return true;
    }

    /** A rancher's pen in the village with fewer than a breeding pair of this kind, or null. */
    @Nullable
    static BlockPos penShortOf(ServerLevel level, UUID village, EntityType<?> type) {
        for (AssistantEntity x : Villages.folkOf(village)) {
            if (x.stationTask() != StationTask.RANCH || x.workZone() == null) continue;
            BlockPos pen = x.workZone().center();
            if (!Land.areaLoaded(level, pen, 8)) continue;
            Map<EntityType<?>, Integer> herd = herd(level, pen, Math.max(8, Math.min(16, x.workZone().radius())));
            if (herd.getOrDefault(type, 0) < 2) return pen;
        }
        return null;
    }

    /**
     * Is it home? Inside the fence, when the village has built its pen and this is where it is
     * going; otherwise on the pen's ground (a led animal hangs back a few blocks on its lead).
     */
    private static boolean home(VillageFolkEntity f, Animal a, Drive d) {
        Pen p = f.ownerId() == null ? null : pen(f.ownerId());
        if (p != null && p.centre().equals(d.pen)) return p.inside(a.blockPosition());
        double dx = a.getX() - (d.pen.getX() + 0.5), dz = a.getZ() - (d.pen.getZ() + 0.5);
        return dx * dx + dz * dz < (d.lure != null ? 5.0 * 5.0 : 6.0 * 6.0);
    }

    /** What an animal will follow, or null if it follows nothing a village grows. */
    @Nullable
    static java.util.function.Predicate<ItemStack> feedFor(Animal a) {
        if (a instanceof net.minecraft.world.entity.animal.Sheep || a instanceof net.minecraft.world.entity.animal.Cow) {
            return s -> s.is(Items.WHEAT);
        }
        if (a instanceof net.minecraft.world.entity.animal.Pig) return s -> s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.BEETROOT);
        if (a instanceof net.minecraft.world.entity.animal.Chicken) {
            return s -> s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS) || s.is(Items.MELON_SEEDS) || s.is(Items.PUMPKIN_SEEDS);
        }
        if (a instanceof net.minecraft.world.entity.animal.Rabbit) return s -> s.is(Items.CARROT) || s.is(Items.DANDELION);
        return null;
    }

    private static void stop(VillageFolkEntity f, @Nullable Animal a, Drive d, boolean home) {
        DRIVES.remove(f.getUUID());
        if (d.lure != null && f.getMainHandItem().is(d.lure.getItem())) {
            f.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, d.heldBefore);   // the feed put away
        }
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
                    f.displayNameCap() + " brought a wild " + kind(a) + " home to the pen" + (d.lure != null ? ", coaxed with "
                        + Crafts.named(d.lure) : " on a lead") + ".");
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
