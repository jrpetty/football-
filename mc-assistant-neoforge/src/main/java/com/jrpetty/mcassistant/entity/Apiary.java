package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [roles-b] The apiary: the beekeeper's fenced bee garden with its honey house (blueprints/apiary.txt), and what the
 * beekeeper does besides the honey itself (Crafts.beekeep).
 * <ul>
 * <li><b>Built</b> once a Stone Age town of twenty keeps a beekeeper, out at the edge of the town. The beekeeper moves
 *     in (CraftScale.houseOf, VillageFolkEntity.workInTheBuilding) and brings its hives from its old meadow at one go,
 *     the bees shut in and let out again in the garden, none lost and none made.</li>
 * <li><b>The candle bench.</b> Comb the hives do not want goes into candles at the honey house's bench, a comb and a
 *     string each (the game's own recipe), up to what the town keeps; the houses, the chapel, the shop and the feasts
 *     burn them.</li>
 * <li><b>Wild bees.</b> A beekeeper with no hive and no swarm (a town no pedlar comes to, or that cannot pay one) looks
 *     about the town for a wild nest, a few chunks at a time, walks out to it, lights a campfire under it to calm the
 *     bees, takes the comb with shears or the honey with a bottle, and carries the nest home as a swarm.</li>
 * </ul>
 * Cost: everything happens in the beekeeper's own piece of work (every twenty seconds); the nest search reads the block
 * entities of six loaded chunks a time, and none at all once a nest is found or the round comes up empty for the day.
 */
public final class Apiary {

    private Apiary() {}

    /** The apiary's drawing (blueprints/apiary.txt). */
    public static final String STRUCTURE = "apiary";
    /** The town's size before it builds one (the beekeeper's own slot starts here). */
    public static final int FROM_FOLK = 20;
    /** Candles the town keeps: the bench stops when the stores have this many. */
    static final int CANDLES_KEPT = 16;
    /** Comb kept back from the bench for new hives (three to a hive). */
    static final int COMB_KEPT = 3;
    /** The most candles dipped in one piece of work. */
    static final int CANDLES_A_TURN = 4;
    /** How far round the town a wild nest is looked for, in chunks; how many chunks a piece of work looks in. */
    static final int SEARCH_CHUNKS = 5, CHUNKS_A_LOOK = 6;
    /** Ticks walking to a nest and getting no nearer before it is given up. */
    static final long GIVE_UP = 900L;
    /** How near the nest the keeper must be to take it. */
    static final double REACH = 4.0;
    /** How far below a nest a campfire still calms its bees (the game's own reach). */
    static final int SMOKE_REACH = 5;
    /** The ledger's note of a keeper's old meadow, its hives to be brought to the apiary ("apiary.meadow.<uuid>"). */
    private static final String MEADOW = "apiary.meadow.";

    /** The chunk offsets round the town, nearest first. */
    private static final List<int[]> SPIRAL = spiral();
    /** How far round the spiral each town has looked, and the day it last came up empty. */
    private static final Map<UUID, Integer> LOOKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> EMPTY_ON = new ConcurrentHashMap<>();
    /** The meadow each keeper was last seen keeping (so the ledger is written only when it moves). */
    private static final Map<UUID, Long> SEEN_AT = new ConcurrentHashMap<>();
    /** A keeper out after a wild nest: where it is, and how near it has got. */
    private static final Map<UUID, Errand> ERRANDS = new ConcurrentHashMap<>();
    /** Tests: walking takes no time. */
    public static boolean INSTANT = false;

    private static final class Errand {
        final BlockPos nest;
        double best = Double.MAX_VALUE;
        long progress;

        Errand(BlockPos nest, long now) {
            this.nest = nest;
            this.progress = now;
        }
    }

    public static void resetForTests() {
        LOOKED.clear();
        EMPTY_ON.clear();
        SEEN_AT.clear();
        ERRANDS.clear();
        INSTANT = false;
    }

    /** Does the town want an apiary built: a Stone Age town of twenty with a beekeeper and none yet (Villages). */
    public static boolean wanted(UUID village, int folk) {
        if (folk < FROM_FOLK || CraftScale.stands(village, STRUCTURE)) return false;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == AssistantEntity.StationTask.BEEKEEP) return true;
        return false;
    }

    // ------------------------------------------------------------------ the candle bench

    /**
     * Candles at the honey house's bench: a comb and a string each, from the stores' spare comb (three kept for a new
     * hive), while the town has fewer than it keeps. Null if there is no apiary, no spare comb, no string, or enough.
     */
    @Nullable
    static String candles(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (!CraftScale.stands(v.id(), STRUCTURE)) return null;
        int have = Crafts.stock(level, v, s -> s.is(Items.CANDLE));
        int comb = Crafts.have(level, v, f, s -> s.is(Items.HONEYCOMB)) - COMB_KEPT;
        int string = Crafts.have(level, v, f, s -> s.is(Items.STRING));
        int n = Math.min(Math.min(CANDLES_A_TURN, CANDLES_KEPT - have), Math.min(comb, string));
        if (n <= 0) return null;
        if (!Crafts.use(level, v, f, s -> s.is(Items.HONEYCOMB), n)) return null;
        if (!Crafts.use(level, v, f, s -> s.is(Items.STRING), n)) {
            Crafts.giveBack(level, v, Items.HONEYCOMB, n);
            return null;
        }
        Crafts.store(level, v, new ItemStack(Items.CANDLE, n));
        return n + (n == 1 ? " candle" : " candles") + " dipped at the honey house's bench, of honeycomb and string";
    }

    // ------------------------------------------------------------------ the hives brought over

    /** A keeper at its own meadow, with hives on it, before the apiary stands: kept, to bring them over when it does. */
    static void meadowSeen(Villages.Village v, VillageFolkEntity f, BlockPos centre) {
        if (CraftScale.stands(v.id(), STRUCTURE)) return;
        Long was = SEEN_AT.get(f.getUUID());
        if (was != null && was == centre.asLong()) return;
        SEEN_AT.put(f.getUUID(), centre.asLong());
        Ledger.note(v.id(), MEADOW + f.getUUID(), Long.toString(centre.asLong()));
    }

    /**
     * A keeper at its apiary with no hive yet: its old meadow's hives brought over, honey and bees and all; else, with
     * no swarm to set down, a wild nest looked for. Null if there was nothing to do.
     */
    @Nullable
    static String noHives(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos centre, int r) {
        String moved = bringHives(level, v, f, centre, r);
        if (moved != null) return moved;
        if (f.countCarried(s -> s.is(Items.BEEHIVE)) > 0) return null;
        return lookForWild(level, v, f);
    }

    @Nullable
    private static String bringHives(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos to, int r) {
        String kept = Ledger.note(v.id(), MEADOW + f.getUUID());
        if (kept == null || kept.isEmpty()) return null;
        BlockPos from;
        try {
            from = BlockPos.of(Long.parseLong(kept));
        } catch (NumberFormatException e) {
            forgetMeadow(v, f);
            return null;
        }
        if (from.distSqr(to) < 8 * 8) { forgetMeadow(v, f); return null; }     // the same ground
        if (!Land.areaLoaded(level, from, 16)) return null;                    // out of sight: another time
        List<BlockPos> old = Crafts.hivesAt(level, from, 6);
        if (old.isEmpty()) { forgetMeadow(v, f); return null; }
        int bees = 0, moved = 0;
        List<BlockPos> set = new ArrayList<>();
        for (BlockPos p : old) {
            if (moved >= Crafts.MAX_HIVES) break;
            BlockPos spot = Crafts.hiveSpot(level, to, r, moved);
            if (spot == null) break;
            BlockState st = level.getBlockState(p);
            int honey = st.hasProperty(BeehiveBlock.HONEY_LEVEL) ? st.getValue(BeehiveBlock.HONEY_LEVEL) : 0;
            if (level.getBlockEntity(p) instanceof BeehiveBlockEntity h) bees += h.getOccupantCount();
            level.removeBlock(p, false);                                       // the bees shut in, and carried with it
            level.setBlock(spot, Blocks.BEEHIVE.defaultBlockState().setValue(BeehiveBlock.FACING, Direction.SOUTH)
                .setValue(BeehiveBlock.HONEY_LEVEL, honey), 3);
            set.add(spot);
            moved++;
        }
        if (moved == 0) return null;
        // The bees out about the old meadow come too: none left behind, none made.
        for (Bee b : level.getEntitiesOfClass(Bee.class, new AABB(from).inflate(12), Bee::isAlive)) {
            b.discard();
            bees++;
        }
        for (int i = 0; i < bees; i++) release(level, set.get(i % set.size()));
        forgetMeadow(v, f);
        f.swing(InteractionHand.MAIN_HAND);
        Villages.tell(v.id(), level.getDayTime() / 24000L, f.displayNameCap() + " brought " + moved + (moved == 1 ? " hive" : " hives")
            + " and " + bees + (bees == 1 ? " bee" : " bees") + " from its old meadow to the new apiary");
        return moved + (moved == 1 ? " hive" : " hives") + " brought over from the old meadow, " + bees + " bees with them";
    }

    private static void forgetMeadow(Villages.Village v, VillageFolkEntity f) {
        SEEN_AT.remove(f.getUUID());
        Ledger.note(v.id(), MEADOW + f.getUUID(), "");
    }

    private static void release(ServerLevel level, BlockPos hive) {
        Bee bee = EntityType.BEE.create(level);
        if (bee == null) return;
        bee.moveTo(hive.getX() + 0.5, hive.getY() + 1.2, hive.getZ() + 1.5, 0.0F, 0.0F);
        bee.setPersistenceRequired();
        level.addFreshEntity(bee);
    }

    // ------------------------------------------------------------------ wild bees

    /** Is this keeper out after a wild nest? A step of the errand (Crafts.work, every tick): true while it is busy. */
    static boolean errand(VillageFolkEntity f, ServerLevel level) {
        Errand e = ERRANDS.get(f.getUUID());
        if (e == null) return false;
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || !level.getBlockState(e.nest).is(Blocks.BEE_NEST) || Raids.underAlarm(id) || !level.isDay()) {
            ERRANDS.remove(f.getUUID());                                       // gone, or not the hour for it: home
            return false;
        }
        if (f.distanceToSqr(e.nest.getX() + 0.5, e.nest.getY(), e.nest.getZ() + 0.5) > REACH * REACH) {
            walk(f, level, e);
            return true;
        }
        ERRANDS.remove(f.getUUID());
        String took = takeNest(level, v, f, e.nest);
        f.brain(took);
        return true;
    }

    private static void walk(VillageFolkEntity f, ServerLevel level, Errand e) {
        if (INSTANT) {
            f.teleportTo(e.nest.getX() + 0.5, e.nest.getY(), e.nest.getZ() + 1.5);
            return;
        }
        long now = level.getGameTime();
        double d = f.distanceToSqr(e.nest.getX() + 0.5, e.nest.getY(), e.nest.getZ() + 0.5);
        if (d < e.best - 1.0) {
            e.best = d;
            e.progress = now;
        } else if (now - e.progress > GIVE_UP || now < e.progress) {
            ERRANDS.remove(f.getUUID());
            f.brain("could not get to the wild nest at " + e.nest.toShortString() + "; left it be");
            return;
        }
        if (f.getNavigation().isDone()) f.walkTo(e.nest, 1.0D);
    }

    /** Look a few chunks further round the town for a wild nest; set out for the nearest found. */
    @Nullable
    static String lookForWild(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (ERRANDS.containsKey(f.getUUID())) return null;
        long day = level.getDayTime() / 24000L;
        Long empty = EMPTY_ON.get(v.id());
        if (empty != null && empty == day) return null;                       // looked all round today: tomorrow
        int from = LOOKED.getOrDefault(v.id(), 0);
        int cx = v.centre().getX() >> 4, cz = v.centre().getZ() >> 4;
        BlockPos best = null;
        int i = from;
        for (; i < SPIRAL.size() && i < from + CHUNKS_A_LOOK && best == null; i++) {
            int[] o = SPIRAL.get(i);
            LevelChunk c = level.getChunkSource().getChunkNow(cx + o[0], cz + o[1]);
            if (c == null) continue;
            for (BlockEntity be : c.getBlockEntities().values()) {
                if (!(be instanceof BeehiveBlockEntity) || !be.getBlockState().is(Blocks.BEE_NEST)) continue;
                if (Land.inABuilding(v.id(), be.getBlockPos())) continue;
                if (best == null || be.getBlockPos().distSqr(f.blockPosition()) < best.distSqr(f.blockPosition())) best = be.getBlockPos().immutable();
            }
        }
        if (best == null) {
            if (i >= SPIRAL.size()) {
                LOOKED.put(v.id(), 0);
                EMPTY_ON.put(v.id(), day);
                f.brain("no wild bees anywhere about the town today");
            } else {
                LOOKED.put(v.id(), i);
            }
            return null;
        }
        LOOKED.put(v.id(), 0);
        ERRANDS.put(f.getUUID(), new Errand(best, level.getGameTime()));
        return "set out for a wild bees' nest it found " + (int) Math.sqrt(best.distSqr(f.blockPosition())) + " blocks off";
    }

    /**
     * At the nest: a campfire set under it to calm the bees (carried, from the stores, or made at the bench), the comb
     * cut with shears or the honey taken with a bottle, the nest taken down with its bees in it, the fire put out and
     * kept, and the swarm carried home in the keeper's pack (Trades.swarm). Says what came of it.
     */
    static String takeNest(ServerLevel level, Villages.Village v, VillageFolkEntity f, BlockPos nest) {
        boolean shears = Crafts.shears(level, v, f);
        boolean bottle = !shears && Crafts.bottle(level, v) && Crafts.take(level, v, s -> s.is(Items.GLASS_BOTTLE), 1);
        if (!shears && !bottle) return "found the wild nest, but had no shears and no bottle to take it with";
        if (!campfireToHand(level, v, f)) {
            if (bottle) Crafts.store(level, v, new ItemStack(Items.GLASS_BOTTLE));
            return "found the wild nest, but had no campfire to calm the bees with (and nothing to make one of)";
        }
        BlockPos fire = fireSpot(level, nest);
        if (fire != null) {
            f.removeMatching(s -> s.is(Items.CAMPFIRE), 1);
            level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true), 3);
            level.playSound(null, fire, SoundEvents.CAMPFIRE_CRACKLE, SoundSource.BLOCKS, 0.8F, 1.0F);
        }
        BlockState st = level.getBlockState(nest);
        boolean full = st.hasProperty(BeehiveBlock.HONEY_LEVEL) && st.getValue(BeehiveBlock.HONEY_LEVEL) >= BeehiveBlock.MAX_HONEY_LEVELS;
        String harvest = "";
        if (full && shears) {
            Crafts.wearShears(f);
            Crafts.store(level, v, new ItemStack(Items.HONEYCOMB, 3));
            harvest = ", and three honeycomb cut from it";
        } else if (bottle) {
            Crafts.store(level, v, full ? new ItemStack(Items.HONEY_BOTTLE) : new ItemStack(Items.GLASS_BOTTLE));
            if (full) harvest = ", and a bottle of honey from it";
        }
        int bees = 0;
        if (level.getBlockEntity(nest) instanceof BeehiveBlockEntity h) bees += h.getOccupantCount();
        for (Bee b : level.getEntitiesOfClass(Bee.class, new AABB(nest).inflate(8), b -> b.isAlive() && nest.equals(b.getHivePos()))) {
            b.discard();
            bees++;
        }
        level.removeBlock(nest, false);
        if (fire != null) {
            level.removeBlock(fire, false);
            ItemStack left = f.insertItem(new ItemStack(Items.CAMPFIRE));
            if (!left.isEmpty()) Crafts.store(level, v, left);
        }
        ItemStack swarm = Trades.swarm();
        ItemStack left = f.insertItem(swarm);
        if (!left.isEmpty()) Crafts.store(level, v, left);
        f.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, nest, SoundEvents.BEEHIVE_SHEAR, SoundSource.NEUTRAL, 0.8F, 1.0F);
        Villages.tell(v.id(), level.getDayTime() / 24000L, f.displayNameCap() + " brought a wild bees' nest home"
            + (bees > 0 ? ", " + bees + (bees == 1 ? " bee" : " bees") + " in it" : "") + ": the town keeps bees of its own");
        return "took the wild nest down under a campfire's smoke" + harvest + "; the swarm is carried home";
    }

    /** A campfire in the keeper's pack: its own, the stores', or made at the bench of their sticks, logs and coal. */
    private static boolean campfireToHand(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (f.countCarried(s -> s.is(Items.CAMPFIRE)) > 0) return true;
        if (!Crafts.take(level, v, s -> s.is(Items.CAMPFIRE), 1)) {
            Bench.Hand hand = Bench.handOf(level, v, f, CraftScale.houseOf(v.id(), AssistantEntity.StationTask.BEEKEEP));
            Bench.Plan plan = Bench.plan(level, v, Items.CAMPFIRE, 1, hand);
            if (!plan.ok() || !Bench.take(level, v, plan, f)) return false;
        }
        ItemStack left = f.insertItem(new ItemStack(Items.CAMPFIRE));
        if (left.isEmpty()) return true;
        Crafts.store(level, v, left);
        return false;
    }

    /** Where a campfire goes under the nest: the first open ground below it, near enough for its smoke. */
    @Nullable
    private static BlockPos fireSpot(ServerLevel level, BlockPos nest) {
        for (int dy = 1; dy <= SMOKE_REACH; dy++) {
            BlockPos p = nest.below(dy);
            BlockState at = level.getBlockState(p);
            if (!at.isAir() && !at.canBeReplaced()) return null;               // a branch in the way
            BlockPos under = p.below();
            if ((at.isAir() || at.canBeReplaced()) && level.getBlockState(under).isFaceSturdy(level, under, Direction.UP)) return p;
        }
        return null;
    }

    /** Tests: a turn at the candle bench. */
    @Nullable
    public static String candlesForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        return candles(level, v, f);
    }

    /** Tests: a keeper with no hive at its ground: the old meadow's brought over, else a wild nest looked for. */
    @Nullable
    public static String noHivesForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        WorkZone z = f.workZone();
        if (z == null) return null;
        return noHives(level, v, f, z.center(), Math.min(6, z.radius()));
    }

    /** Tests: a beat of the errand out to a wild nest. */
    public static boolean errandStepForTests(VillageFolkEntity f, ServerLevel level) {
        return errand(f, level);
    }

    /** Tests: the hives on a keeper's ground. */
    public static int hivesForTests(ServerLevel level, BlockPos centre) {
        return Crafts.hivesAt(level, centre, 6).size();
    }

    /** Tests: the keeper's errand, if it is out after a nest. */
    @Nullable
    public static BlockPos errandForTests(VillageFolkEntity f) {
        Errand e = ERRANDS.get(f.getUUID());
        return e == null ? null : e.nest;
    }

    private static List<int[]> spiral() {
        List<int[]> out = new ArrayList<>();
        for (int dx = -SEARCH_CHUNKS; dx <= SEARCH_CHUNKS; dx++) {
            for (int dz = -SEARCH_CHUNKS; dz <= SEARCH_CHUNKS; dz++) out.add(new int[]{ dx, dz });
        }
        out.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        return List.copyOf(out);
    }
}
