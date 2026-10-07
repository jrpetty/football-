package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [disasters] Rebuilding after a fire, and a bed for the night while it is done.
 *
 * <ul>
 * <li><b>What stood.</b> The moment the fire brigade sees a fire on or beside one of the town's buildings, it
 *     takes that building down as it stands, block by block, from the building's own drawing (Blueprints, as
 *     BuildGoal lays it out): what is at each of its places now. When the fire is out, every place where
 *     something stood and now there is nothing (or a flame) is a burnt block.</li>
 * <li><b>Put back from the stores.</b> The burnt blocks go back as a job on the town's works (TownJobs), the
 *     lowest first, a few every couple of seconds, by a hand there at the building, each paid for out of the
 *     stores: the same block if the stores hold it; else what it is made of (a plank for a plank, a stair, a
 *     slab, a fence or a door; a log for a log; wool for wool or a rug; nine wheat for a bale of hay). Never for
 *     nothing: short of the makings, the work waits, and the board says what for.</li>
 * <li><b>A bed for the night.</b> The household of a burnt house (or one with the flood on its floor) sleeps
 *     elsewhere till it is done: in a spare bed at a neighbour's (a house with more beds made up than folk),
 *     else at the inn, else by the meeting hall's fire. At dusk it walks there and goes to bed, and in the
 *     morning back to its day.</li>
 * </ul>
 */
public final class Rebuilding {

    private Rebuilding() {}

    /** Blocks put back at a go (every two seconds). */
    static final int STEP = 4;

    /** A displaced folk's night away: the bed (or the spot by the hall's fire), whose it is, and its walk there. */
    static final class Lodge {
        @Nullable final BlockPos bed;
        final BlockPos spot;
        final String with;
        final long night;
        double best = Double.MAX_VALUE;
        long progress;
        int walkTick = -1000;

        Lodge(@Nullable BlockPos bed, BlockPos spot, String with, long night, long now) {
            this.bed = bed == null ? null : bed.immutable();
            this.spot = spot.immutable();
            this.with = with;
            this.night = night;
            this.progress = now;
        }
    }

    private static final Map<UUID, Lodge> LODGE = new ConcurrentHashMap<>();
    /** The nights each folk has spent away from a burnt or flooded home (tests, the card). */
    private static final Map<UUID, Integer> NIGHTS = new ConcurrentHashMap<>();

    static void resetForTests() {
        LODGE.clear();
        NIGHTS.clear();
    }

    // ------------------------------------------------------------------ what stood

    /** The drawing a building stands to now: a house with its second storey, a raised building's tall one. */
    static String drawing(UUID village, Ledger.Building b) {
        if (b.structure().equals("house") && Ledger.grown(village, b.anchor())) return "house2";
        if (Grow.tall(village, b.anchor()) && com.jrpetty.mcassistant.entity.goal.Blueprints.has(b.structure() + com.jrpetty.mcassistant.entity.goal.Blueprints.TALL)) {
            return b.structure() + com.jrpetty.mcassistant.entity.goal.Blueprints.TALL;
        }
        return b.structure();
    }

    /** The building as it stands: what is at each place of its drawing now (nothing, a flame or air left out). */
    static Map<Long, BlockState> snapshot(ServerLevel level, UUID village, Ledger.Building b) {
        Map<Long, BlockState> out = new LinkedHashMap<>();
        for (BuildGoal.Placement p : BuildGoal.plan(drawing(village, b), b.anchor(), b.facing(), 13)) {
            if (p.part() == BuildGoal.Part.CLEAR || !level.isLoaded(p.pos())) continue;
            BlockState st = level.getBlockState(p.pos());
            if (st.isAir() || st.is(BlockTags.FIRE) || !st.getFluidState().isEmpty()) continue;
            out.put(p.pos().asLong(), st);
            // A door's or a bed's other half, with it (the drawing has only the one).
            if (st.getBlock() instanceof DoorBlock) {
                BlockPos up = p.pos().above();
                BlockState u = level.getBlockState(up);
                if (u.getBlock() instanceof DoorBlock) out.put(up.asLong(), u);
            } else if (st.getBlock() instanceof BedBlock) {
                BlockPos other = p.pos().relative(BedBlock.getConnectedDirection(st));
                BlockState o = level.getBlockState(other);
                if (o.getBlock() instanceof BedBlock) out.put(other.asLong(), o);
            }
        }
        return out;
    }

    /** The town's building this spot is on or beside (its footprint and a little round it), or null. */
    @Nullable
    static Ledger.Building buildingAt(UUID village, BlockPos p, int margin) {
        Ledger.Building best = null;
        double bd = Double.MAX_VALUE;
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (b.structure().equals("fortify") || b.structure().equals("wall")) continue;
            int[] half = BuildGoal.footprint(b.structure());
            boolean turned = b.facing().getAxis() == net.minecraft.core.Direction.Axis.X;
            int wx = (turned ? half[1] : half[0]) + margin, wz = (turned ? half[0] : half[1]) + margin;
            if (Math.abs(p.getX() - b.anchor().getX()) > wx || Math.abs(p.getZ() - b.anchor().getZ()) > wz) continue;
            if (p.getY() < b.anchor().getY() - 2 || p.getY() > b.anchor().getY() + 40) continue;
            double d = b.anchor().distSqr(p);
            if (d < bd) { bd = d; best = b; }
        }
        return best;
    }

    // ------------------------------------------------------------------ after the fire

    /**
     * The fire out: each building it touched gone over against what stood when the fire was first seen, and
     * what burned set to be put back. Returns the blocks burnt.
     */
    static int afterFire(ServerLevel level, Villages.Village v, Map<Long, Map<Long, BlockState>> stood, String where, String cause) {
        Disasters.Town t = Disasters.town(v.id());
        long day = level.getDayTime() / 24000L;
        int all = 0;
        for (Map.Entry<Long, Map<Long, BlockState>> e : stood.entrySet()) {
            BlockPos anchor = BlockPos.of(e.getKey());
            Ledger.Building b = null;
            for (Ledger.Building x : Ledger.buildings(v.id())) if (x.anchor().equals(anchor)) b = x;
            if (b == null) continue;
            List<Map.Entry<Long, BlockState>> burnt = new ArrayList<>();
            for (Map.Entry<Long, BlockState> c : e.getValue().entrySet()) {
                BlockPos p = BlockPos.of(c.getKey());
                if (!level.isLoaded(p)) continue;
                BlockState now = level.getBlockState(p);
                if (now.isAir() || now.is(BlockTags.FIRE)) burnt.add(c);
            }
            if (burnt.isEmpty()) continue;
            String name = FireBrigade.named(b.structure());
            Disasters.Rebuild r = null;
            for (Disasters.Rebuild x : t.rebuilds) if (x.anchor.equals(anchor)) r = x;
            if (r == null) {
                r = new Disasters.Rebuild(b.structure(), anchor, name, day, cause);
                t.rebuilds.add(r);
            }
            burnt.sort(Comparator.comparingInt(c -> BlockPos.of(c.getKey()).getY()));
            for (Map.Entry<Long, BlockState> c : burnt) {
                if (r.cells.putIfAbsent(c.getKey(), c.getValue()) == null) r.total++;
            }
            t.burnt += burnt.size();
            all += burnt.size();
            boolean home = Homes.isHome(b.structure());
            Disasters.record(v.id(), day, "the fire burnt " + burnt.size() + (burnt.size() == 1 ? " block" : " blocks") + " of " + name
                + ": it will be rebuilt from the stores" + (home ? ", and its household is staying with the neighbours till it is" : ""));
        }
        Disasters.dirty();
        return all;
    }

    /** Every two seconds: the first burnt building's blocks put back, a few at a time, by hand, out of the stores. */
    static void tick(ServerLevel level, Villages.Village v, Disasters.Town t) {
        if (!t.rebuilds.isEmpty()) work(level, v, t, TownTraits.rebuildPace(v.id(), STEP));   // [identity] Fire-born: twice as fast
    }

    /** Put back so many burnt blocks. Returns how many went back. */
    static int work(ServerLevel level, Villages.Village v, Disasters.Town t, int most) {
        if (t.rebuilds.isEmpty()) return 0;
        Disasters.Rebuild r = t.rebuilds.get(0);
        if (!level.isLoaded(r.anchor) || FireBrigade.burningNear(v.id(), r.anchor)) return 0;   // not while it still burns
        if (r.cells.isEmpty()) {
            finish(level, v, t, r);
            return 0;
        }
        BlockPos first = BlockPos.of(r.cells.keySet().iterator().next());
        if (!TownJobs.atWork(level, v, "rebuild", first, "rebuilding " + r.where + " after the fire")) return 0;
        int n = 0;
        var it = r.cells.entrySet().iterator();
        while (it.hasNext() && n < most) {
            Map.Entry<Long, BlockState> c = it.next();
            BlockPos p = BlockPos.of(c.getKey());
            BlockState now = level.getBlockState(p);
            boolean open = now.isAir() || now.is(BlockTags.FIRE) || BuildGoal.isWildPlant(now);
            if (!open) {                                       // something there now: it was put back, or the place is taken
                it.remove();
                continue;
            }
            BlockState was = c.getValue();
            String short_ = pay(level, v, was);
            if (short_ != null) {
                r.waits = short_;
                Disasters.dirty();
                return n;
            }
            boolean twoPart = was.getBlock() instanceof DoorBlock || was.getBlock() instanceof BedBlock || was.getBlock() instanceof DoublePlantBlock;
            level.setBlock(p, was, twoPart ? 2 | 16 : 3);
            level.playSound(null, p, was.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8F, 1.0F);
            it.remove();
            r.put++;
            t.rebuilt++;
            n++;
        }
        r.waits = "";
        if (r.cells.isEmpty()) finish(level, v, t, r);
        Disasters.dirty();
        return n;
    }

    private static void finish(ServerLevel level, Villages.Village v, Disasters.Town t, Disasters.Rebuild r) {
        t.rebuilds.remove(r);
        long day = level.getDayTime() / 24000L;
        Disasters.record(v.id(), day, Disasters.capital(r.where) + " was rebuilt after the fire of day " + (r.day + 1) + ": "
            + r.put + (r.put == 1 ? " block" : " blocks") + " put back out of the stores");
        Disasters.dirty();
    }

    /**
     * A burnt block paid for out of the stores: the block itself, else what it is made of. Null if paid; else
     * what the work waits for ("planks", "wool").
     */
    @Nullable
    static String pay(ServerLevel level, Villages.Village v, BlockState was) {
        Item item = was.getBlock().asItem();
        // A door's top half and a bed's head came with their other half.
        if (was.getBlock() instanceof DoorBlock && was.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) return null;
        if (was.getBlock() instanceof BedBlock && was.getValue(BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD) return null;
        if (item != Items.AIR && Crafts.take(level, v, s -> s.is(item), 1)) return null;
        if (was.is(BlockTags.WOOL) || was.is(BlockTags.WOOL_CARPETS) || was.getBlock() instanceof BedBlock) {
            if (was.getBlock() instanceof BedBlock) return Crafts.take(level, v, s -> s.is(ItemTags.WOOL), 3) && Crafts.usePlanks(level, v, 3) ? null : "wool and planks for a bed";
            return Crafts.take(level, v, s -> s.is(ItemTags.WOOL), 1) ? null : "wool";
        }
        if (was.is(BlockTags.LOGS)) return Crafts.take(level, v, s -> s.is(ItemTags.LOGS), 1) ? null : "logs";
        if (was.is(Blocks.HAY_BLOCK)) return Crafts.take(level, v, s -> s.is(Items.WHEAT), 9) ? null : "nine wheat for a bale of hay";
        if (was.is(Blocks.BOOKSHELF)) return Crafts.take(level, v, s -> s.is(Items.BOOK), 3) && Crafts.usePlanks(level, v, 6) ? null : "books for a shelf";
        if (wooden(was)) return Crafts.usePlanks(level, v, 1) ? null : "planks";
        return item == Items.AIR ? null : Crafts.named(new net.minecraft.world.item.ItemStack(item)).replaceFirst("^a ", "");
    }

    /** A wooden thing a carpenter makes of the stores' planks: planks, stairs, slabs, fences, gates, doors, trapdoors, ladders. */
    static boolean wooden(BlockState s) {
        return s.is(BlockTags.PLANKS) || s.is(BlockTags.WOODEN_STAIRS) || s.is(BlockTags.WOODEN_SLABS) || s.is(BlockTags.WOODEN_FENCES)
            || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.WOODEN_DOORS) || s.is(BlockTags.WOODEN_TRAPDOORS) || s.is(Blocks.LADDER)
            || s.is(BlockTags.WOODEN_PRESSURE_PLATES) || s.is(BlockTags.WOODEN_BUTTONS) || s.is(BlockTags.SIGNS)
            || s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.BARREL) || s.is(Blocks.COMPOSTER) || s.is(Blocks.LECTERN);
    }

    // ------------------------------------------------------------------ a bed for the night

    /** Is this folk's home burnt and waiting to be rebuilt, or under the flood? */
    static boolean displaced(VillageFolkEntity f) {
        UUID id = f.ownerId();
        BlockPos home = Homes.homeOf(f);
        if (id == null || home == null) return false;
        Disasters.Town t = Disasters.known(id);
        if (t == null) return false;
        for (Disasters.Rebuild r : t.rebuilds) if (r.anchor.equals(home)) return true;
        return t.flood != null && t.flood.homes.contains(home.asLong());
    }

    static boolean lodged(VillageFolkEntity f) {
        return LODGE.containsKey(f.getUUID());
    }

    /**
     * From the folk's tick (Disasters.hold): a folk whose home is burnt or flooded, from dusk to dawn, at a
     * neighbour's spare bed, the inn, or by the hall's fire. True while it is lodging.
     */
    static boolean lodging(VillageFolkEntity f, ServerLevel level) {
        Lodge l = LODGE.get(f.getUUID());
        long dt = level.getDayTime(), t = Math.floorMod(dt, 24000L), night = Math.floorDiv(dt - 12500L, 24000L);
        boolean dark = t >= 12500L && t < 23500L;
        if (!dark || f.isHired() || f.trip() != null || f.expedition() != null || f.getTarget() != null
                || f.stationTask() == AssistantEntity.StationTask.GUARD                // the watch keeps its night where it is
                || (l == null && (f.tickCount % 40 != 23 || !displaced(f)))) {
            if (l != null) {
                LODGE.remove(f.getUUID());
                if (f.isSleeping()) f.stopSleeping();
                if (!dark) f.brain("up and home after a night " + l.with);
            }
            return false;
        }
        long now = level.getGameTime();
        if (l == null) {
            l = find(level, f, night, now);
            if (l == null) return false;
            LODGE.put(f.getUUID(), l);
            NIGHTS.merge(f.getUUID(), 1, Integer::sum);
            if (f.isSleeping()) f.stopSleeping();
            f.clearQueue();
            f.getNavigation().stop();
            f.brain("its home is " + (Floods.homeFlooded(f) ? "under the flood" : "burnt") + ": the night " + l.with);
            if (!f.isBaby() && f.getRandom().nextInt(2) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "We're stopping " + l.with + " till the house is put right.",
                    "Home's no place to sleep just now. I'm " + l.with + ".", "Kind of them to put us up."));
            }
        }
        if (f.isSleeping()) return true;
        double d = f.blockPosition().distSqr(l.spot);
        if (d > 4.0) {
            if (d < l.best - 1.0) {
                l.best = d;
                l.progress = now;
            } else if (now - l.progress > 600 || now < l.progress) {
                BlockPos by = Trades.floorSpot(level, l.spot, 2);   // no nearer in half a minute: set down by it
                if (by != null) f.moveTo(by.getX() + 0.5, by.getY(), by.getZ() + 0.5, f.getYRot(), 0.0F);
                l.progress = now;
            }
            if (f.getNavigation().isDone() || f.tickCount - l.walkTick > 60) {
                f.walkTo(l.spot, 0.9D);
                l.walkTick = f.tickCount;
            }
            f.hobbyNow = "on its way to sleep " + l.with;
            return true;
        }
        f.getNavigation().stop();
        if (l.bed != null && level.getBlockState(l.bed).getBlock() instanceof BedBlock && !level.getBlockState(l.bed).getValue(BedBlock.OCCUPIED)) {
            f.startSleeping(l.bed);
            f.hobbyNow = "asleep " + l.with;
        } else {
            f.hobbyNow = "dozing " + l.with;
        }
        return true;
    }

    /** Where a displaced folk sleeps tonight: a neighbour's spare bed, the inn's, or by the hall. Null with nowhere. */
    @Nullable
    private static Lodge find(ServerLevel level, VillageFolkEntity f, long night, long now) {
        UUID id = f.ownerId();
        if (id == null) return null;
        BlockPos home = Homes.homeOf(f);
        Disasters.Town t = Disasters.known(id);
        java.util.Set<Long> away = new java.util.HashSet<>();
        if (t != null) {
            for (Disasters.Rebuild r : t.rebuilds) away.add(r.anchor.asLong());
            if (t.flood != null) away.addAll(t.flood.homes);
        }
        java.util.Set<BlockPos> taken = new java.util.HashSet<>();
        for (Lodge l : LODGE.values()) if (l.bed != null) taken.add(l.bed);
        BlockPos near = home != null ? home : f.blockPosition();
        BlockPos best = null;
        Homes.Home bestHome = null;
        double bd = Double.MAX_VALUE;
        for (Homes.Home h : Homes.homes(id).values()) {
            if (away.contains(h.anchor.asLong())) continue;
            for (BlockPos bed : Homes.bedsIn(level, id, h)) {
                if (taken.contains(bed) || !Homes.free(level, id, bed)) continue;
                double d = bed.distSqr(near);
                if (d < bd) { bd = d; best = bed; bestHome = h; }
            }
        }
        if (best != null) return new Lodge(best, best, "at the " + household(id, bestHome) + "'s", night, now);
        Ledger.Building inn = Inn.inn(id);
        if (inn != null && level.isLoaded(inn.anchor())) {
            BlockPos bed = Inn.freeBed(level, id, inn);
            if (bed != null && !taken.contains(bed)) return new Lodge(bed, bed, "at the inn", night, now);
        }
        BlockPos hall = Villages.builtAt(id, "hall");
        if (hall == null) hall = Villages.builtAt(id, "townhall");
        if (hall != null) return new Lodge(null, hall, "by the meeting hall's fire", night, now);
        return null;
    }

    /** A household by its first member's family name, or "neighbours". */
    private static String household(UUID village, @Nullable Homes.Home h) {
        if (h == null || h.members.isEmpty()) return "neighbours";
        VillageFolkEntity m = Homes.loaded(village, h.members.get(0));
        if (m == null) return "neighbours";
        String name = m.displayNameCap();
        int sp = name.lastIndexOf(' ');
        return sp > 0 ? name.substring(sp + 1) : name;
    }

    // ------------------------------------------------------------------ the card

    @Nullable
    static String cardPart(VillageFolkEntity f) {
        Lodge l = LODGE.get(f.getUUID());
        if (l != null) return "sleeping " + l.with + " while its home is put right";
        if (displaced(f) && !Floods.homeFlooded(f)) return "its home was burnt: sleeping at a neighbour's till it is rebuilt";
        return null;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the burnt blocks still to put back, by building anchor. */
    public static Map<BlockPos, Integer> waitingForTests(UUID village) {
        Map<BlockPos, Integer> out = new HashMap<>();
        Disasters.Town t = Disasters.known(village);
        if (t != null) for (Disasters.Rebuild r : t.rebuilds) out.put(r.anchor, r.cells.size());
        return out;
    }

    /** Tests: the burnt blocks of the first rebuilding, place by place. */
    public static Map<BlockPos, BlockState> cellsForTests(UUID village) {
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        Disasters.Town t = Disasters.known(village);
        if (t != null && !t.rebuilds.isEmpty()) for (Map.Entry<Long, BlockState> e : t.rebuilds.get(0).cells.entrySet()) out.put(BlockPos.of(e.getKey()), e.getValue());
        return out;
    }

    /** Tests: put back what the stores will pay for, now. Returns the blocks put back. */
    public static int workForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        Disasters.Town t = Disasters.known(village);
        if (v == null || t == null) return 0;
        int n = 0;
        for (int i = 0; i < 100 && !t.rebuilds.isEmpty(); i++) {
            int k = work(level, v, t, 16);
            if (k == 0 && !t.rebuilds.isEmpty() && !t.rebuilds.get(0).waits.isEmpty()) break;
            n += k;
        }
        return n;
    }

    /** Tests: what the first rebuilding waits for ("" if nothing). */
    public static String waitsForTests(UUID village) {
        Disasters.Town t = Disasters.known(village);
        return t == null || t.rebuilds.isEmpty() ? "" : t.rebuilds.get(0).waits;
    }

    /** Tests: is this folk lodging away from a burnt or flooded home tonight, and where? Null if not. */
    @Nullable
    public static String lodgeForTests(VillageFolkEntity f) {
        Lodge l = LODGE.get(f.getUUID());
        return l == null ? null : l.with + (l.bed == null ? "" : " " + l.bed.toShortString());
    }

    /** Tests: is this folk's home burnt or flooded? */
    public static boolean displacedForTests(VillageFolkEntity f) {
        return displaced(f);
    }
}
