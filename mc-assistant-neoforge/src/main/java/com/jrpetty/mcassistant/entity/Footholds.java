package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.NightShelterGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [mines] Footholds: the blocks a folk lays only to get somewhere, and takes up again after.
 *
 * <p>A folk after an ore in a cave's roof, a ledge, a tree, or out of a hole it fell into, built itself a way up out
 * of the cobblestone and earth in its pack (GatherGoal, EscapeGoal, the Nether runners), got what it went for, and
 * walked off. Every cave the town had worked was a forest of one-block pillars, stray steps and half bridges. Now each
 * such block goes on the books here (laid), with whose it is, what it is and what for, and comes down again: the
 * climber comes down its own pillar a block at a time, top to bottom, each block back into its pack (comeDown), and
 * any it left behind (called away by the night, a fight, a full pack, the bell) it takes up the next time it passes
 * within reach (passBy). A day on, anybody of its town passing takes them up.
 *
 * <p>Only what a folk laid itself, and only while it is still the block it laid, ever comes down: never the cave's
 * own rock, another's work, or a block somebody has since built on. Nothing comes down from under anybody standing on
 * it, and nothing comes down that would leave its folk stranded on a ledge with no way off: a way down it cannot
 * safely take (a drop of more than three, water or lava under it) is kept, and booked as a kept route (MineStairs).
 */
public final class Footholds extends SavedData {

    private static final String ID = "mc_assistant_footholds";

    /** What a foothold was laid for. A climb's pillar is come down from on top; an escape's is never taken from under
     *  the folk that climbed out on it (that would drop it back into the hole). */
    public enum Purpose { CLIMB, BRIDGE, ESCAPE }

    /** The most footholds a world keeps on its books; past that the oldest are let go (their blocks stay). */
    static final int MOST = 4096;
    /** The highest a folk builds itself up to reach something. */
    public static final int MOST_HIGH = 8;
    /** The furthest a folk drops coming down: a player's fall without harm. */
    static final int SAFE_DROP = 3;
    /** Leftovers anybody of the same town takes up once they are this old (a day): their own folk never came back. */
    static final long ORPHAN_AGE = 24000L;
    /** Walkable cells round a folk that mean it is free to walk off, not stranded on a ledge. */
    static final int FREE_CELLS = 48;
    /** A climb that has not risen for this long is no longer under way (the goal that began it was cut short). */
    static final long CLIMB_LAPSE = 200L;
    /** How far (eye to the block's middle) a folk takes a block up from: a player's reach. */
    static final double REACH = AssistantEntity.BLOCK_REACH;
    /** How often the routes the town has stopped using are looked over (a quarter of a day). */
    static final long ROUTES_EVERY = 6000L;

    /** One foothold: whose, of which town, the block it was, when, what for, and whether it is kept as a way down. */
    record Hold(UUID owner, @Nullable UUID town, Block block, long laid, Purpose purpose, boolean kept) {
        Hold asKept() { return new Hold(owner, town, block, laid, purpose, true); }
    }

    private final LinkedHashMap<Long, Hold> holds = new LinkedHashMap<>();
    /** The footholds in each chunk, so a look within reach reads a few, not the world's. */
    private final HashMap<Long, Set<Long>> byChunk = new HashMap<>();

    /** Climbs under way: the folk, and the game time it last rose. */
    private static final Map<UUID, Long> CLIMBING = new ConcurrentHashMap<>();
    /** When each level's unused routes were last looked over. */
    private static final Map<net.minecraft.resources.ResourceKey<Level>, Long> ROUTES_LOOKED = new ConcurrentHashMap<>();

    private Footholds() {}

    @Nullable private static Footholds cached;
    @Nullable private static ServerLevel cachedFor;

    static Footholds of(ServerLevel level) {
        if (cachedFor == level && cached != null) return cached;
        Footholds f = level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(Footholds::new, Footholds::load, null), ID);
        cached = f;
        cachedFor = level;
        return f;
    }

    // ------------------------------------------------------------------ the books

    /** A block this folk has just laid at pos to get somewhere, for this purpose: on the books, to come down again. */
    public static void laid(AssistantEntity a, BlockPos pos, Purpose purpose) {
        if (!(a.level() instanceof ServerLevel level)) return;
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) return;
        Footholds f = of(level);
        f.put(pos.asLong(), new Hold(a.getUUID(), a.ownerId(), st.getBlock(), level.getGameTime(), purpose, false));
        f.setDirty();
        if (purpose == Purpose.CLIMB) CLIMBING.put(a.getUUID(), level.getGameTime());   // up there at work: left under it
    }

    /** Is a block of this folk's own, still as it laid it, at pos? */
    public static boolean isOwn(AssistantEntity a, BlockPos pos) {
        if (!(a.level() instanceof ServerLevel level)) return false;
        Hold h = of(level).holds.get(pos.asLong());
        return h != null && h.owner().equals(a.getUUID()) && intact(level, pos, h);
    }

    /** Is the block at pos a foothold kept as somebody's way down? Nobody mining takes it. */
    public static boolean isKept(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) return false;
        Footholds f = of(server);
        if (f.holds.isEmpty()) return false;
        Hold h = f.holds.get(pos.asLong());
        return h != null && h.kept();
    }

    /** This folk's footholds still standing within so many blocks of a spot (kept ones aside). */
    public static List<BlockPos> leftBy(AssistantEntity a, BlockPos near, int within) {
        List<BlockPos> out = new ArrayList<>();
        if (!(a.level() instanceof ServerLevel level)) return out;
        Footholds f = of(level);
        for (long p : f.around(near, within)) {
            Hold h = f.holds.get(p);
            BlockPos pos = BlockPos.of(p);
            if (h != null && !h.kept() && h.owner().equals(a.getUUID()) && intact(level, pos, h)) out.add(pos);
        }
        return out;
    }

    /** Every foothold on the books within so many blocks of a spot, kept or not, whoever laid it (still standing). */
    public static List<BlockPos> standingNear(ServerLevel level, BlockPos near, int within) {
        List<BlockPos> out = new ArrayList<>();
        Footholds f = of(level);
        for (long p : f.around(near, within)) {
            Hold h = f.holds.get(p);
            if (h != null && intact(level, BlockPos.of(p), h)) out.add(BlockPos.of(p));
        }
        return out;
    }

    private void put(long pos, Hold h) {
        if (holds.put(pos, h) == null) byChunk.computeIfAbsent(chunkOf(pos), k -> new HashSet<>()).add(pos);
        while (holds.size() > MOST) forget(holds.keySet().iterator().next());
    }

    private void forget(long pos) {
        if (holds.remove(pos) == null) return;
        Set<Long> in = byChunk.get(chunkOf(pos));
        if (in != null) {
            in.remove(pos);
            if (in.isEmpty()) byChunk.remove(chunkOf(pos));
        }
        setDirty();
    }

    private static long chunkOf(long pos) {
        return ChunkPos.asLong(BlockPos.getX(pos) >> 4, BlockPos.getZ(pos) >> 4);
    }

    /** The footholds within so many blocks of a spot (any height), read from the chunks round it. */
    private List<Long> around(BlockPos at, int within) {
        List<Long> out = new ArrayList<>();
        if (holds.isEmpty()) return out;
        int c0x = (at.getX() - within) >> 4, c1x = (at.getX() + within) >> 4;
        int c0z = (at.getZ() - within) >> 4, c1z = (at.getZ() + within) >> 4;
        long most = (long) within * within;
        for (int cx = c0x; cx <= c1x; cx++) {
            for (int cz = c0z; cz <= c1z; cz++) {
                Set<Long> in = byChunk.get(ChunkPos.asLong(cx, cz));
                if (in == null) continue;
                for (long p : in) if (BlockPos.of(p).distSqr(at) <= most) out.add(p);
            }
        }
        return out;
    }

    /** Still the block it laid? Anything else there now (dug, built over, washed out) is no longer its to take. */
    private static boolean intact(ServerLevel level, BlockPos pos, Hold h) {
        return level.isLoaded(pos) && level.getBlockState(pos).is(h.block());
    }

    // ------------------------------------------------------------------ going up

    /**
     * One block up: the folk lifted onto a block laid under its feet, out of its pack, and the block booked. False when
     * it cannot rise (no block to lay, its roof in the way, or already as high as it builds).
     */
    public static boolean rise(AssistantEntity a, Purpose purpose) {
        if (!(a.level() instanceof ServerLevel level)) return false;
        BlockPos feet = a.feetPos();
        if (!a.onGround()) return true;                                  // still settling on the last one
        if (risen(a, feet) >= MOST_HIGH) return false;
        if (!roomAbove(level, feet)) return false;
        if (!level.getBlockState(feet).canBeReplaced() || !level.getFluidState(feet).isEmpty()) return false;
        BlockState block = takeBlock(a);
        if (block == null) return false;
        a.getNavigation().stop();
        a.setPos(feet.getX() + 0.5, feet.getY() + 1.0, feet.getZ() + 0.5);
        level.setBlockAndUpdate(feet, block);
        a.placeSound(feet);
        a.swing(InteractionHand.MAIN_HAND);
        laid(a, feet, purpose);
        return true;
    }

    /** Is a climb of this folk's under way (it rose a moment ago)? Its blocks are not taken from under it meanwhile. */
    public static boolean climbing(AssistantEntity a) {
        Long at = CLIMBING.get(a.getUUID());
        if (at == null) return false;
        if (a.level().getGameTime() - at > CLIMB_LAPSE) { CLIMBING.remove(a.getUUID()); return false; }
        return true;
    }

    /** How many of its own blocks it stands on, one on another, straight under its feet. */
    static int risen(AssistantEntity a, BlockPos feet) {
        int n = 0;
        for (BlockPos p = feet.below(); n <= MOST_HIGH && isOwn(a, p); p = p.below()) n++;
        return n;
    }

    /** Room over its head to rise into: the two blocks above where it stands, open. */
    private static boolean roomAbove(Level level, BlockPos feet) {
        return level.getBlockState(feet.above(2)).getCollisionShape(level, feet.above(2)).isEmpty();
    }

    /** Could this folk, standing at `from`, build itself up to reach `target` (no higher than it builds, and blocks
     *  enough in its pack for the climb)? */
    public static boolean canClimbTo(AssistantEntity a, BlockPos from, BlockPos target) {
        int up = target.getY() - from.getY() - REACH_ABOVE_FEET;
        return up > 0 && up <= MOST_HIGH && a.countMatching(Footholds::footholdBlock) >= up;
    }

    /** How far above its feet a folk reaches without climbing (its eyes, and a player's reach up from them). */
    public static final int REACH_ABOVE_FEET = 5;

    /** Still at work up there (digging what it climbed for): its blocks are not taken from under it meanwhile. */
    public static void stillUp(AssistantEntity a) {
        if (CLIMBING.containsKey(a.getUUID())) CLIMBING.put(a.getUUID(), a.level().getGameTime());
    }

    /** The blocks it builds footholds of, cheapest first: what it would throw away, before what it would build with. */
    private static final List<net.minecraft.world.item.Item> CHEAPEST = List.of(
        Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.NETHERRACK, Items.COARSE_DIRT);

    private static boolean footholdBlock(ItemStack s) {
        return NightShelterGoal.SHELTER_BLOCK.test(s) && s.getItem() instanceof BlockItem;
    }

    /** One block out of its pack to lay, the cheapest it has; null when it has none. */
    @Nullable
    private static BlockState takeBlock(AssistantEntity a) {
        for (net.minecraft.world.item.Item cheap : CHEAPEST) {
            if (a.removeMatching(s -> s.is(cheap), 1) == 1) return ((BlockItem) cheap).getBlock().defaultBlockState();
        }
        var inv = a.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !footholdBlock(s)) continue;
            BlockState st = ((BlockItem) s.getItem()).getBlock().defaultBlockState();
            s.shrink(1);
            if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
            return st;
        }
        return null;
    }

    // ------------------------------------------------------------------ coming down

    /**
     * One step of the way down, once what it climbed for is done: the block under its feet taken up (it drops onto the
     * next), else the highest of its own in reach. True while it took one; false when there is none left in reach (or
     * the one under it must stay: then it is kept, and booked as a way down).
     */
    public static boolean comeDown(AssistantEntity a) {
        if (!(a.level() instanceof ServerLevel level) || of(level).holds.isEmpty()) return false;
        if (!a.onGround()) return true;                                  // falling onto the next: wait for it
        BlockPos under = a.feetPos().below();
        if (isOwn(a, under)) {
            if (!safeBelow(level, under)) {
                keep(level, under, a);
                return false;
            }
            if (!takeUp(a, level, under)) return false;                  // pack full: left on the books for later
            a.setPos(under.getX() + 0.5, under.getY(), under.getZ() + 0.5);
            CLIMBING.remove(a.getUUID());
            return true;
        }
        CLIMBING.remove(a.getUUID());
        BlockPos next = nextInReach(a, level, false);
        if (next == null) return false;
        if (strands(a, level, next)) {
            keep(level, next, a);
            return true;
        }
        return takeUp(a, level, next);
    }

    /**
     * As it goes about (VillageFolkEntity.aiStep, once a second): a block it left behind and can reach from where it
     * stands, taken up; a day-old one of anybody of its town's too. Never in a fight, a climb, the air or a boat.
     */
    public static void passBy(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level)) return;
        lookOverRoutes(level);
        Footholds books = of(level);
        if (books.holds.isEmpty()) return;
        if (!f.onGround() || f.getTarget() != null || f.isPassenger() || f.isSleeping()) return;
        BlockPos under = f.feetPos().below();
        if (climbing(f) && isOwn(f, under)) return;                      // up its own steps, at work: left to it
        Hold h = books.holds.get(under.asLong());
        // Its own climb's pillar, called away before it came down: down it now, a block at a time.
        if (h != null && h.purpose() == Purpose.CLIMB && !h.kept() && isOwn(f, under)) {
            comeDown(f);
            return;
        }
        BlockPos next = nextInReach(f, level, true);
        if (next == null) return;
        if (strands(f, level, next)) keep(level, next, f);
        else takeUp(f, level, next);
    }

    /**
     * The highest foothold within its reach it may take up now (its own; with others, a day-old one of its town's),
     * that nobody stands on and that is not under its own feet; null for none.
     */
    @Nullable
    private static BlockPos nextInReach(AssistantEntity a, ServerLevel level, boolean orphansToo) {
        Footholds books = of(level);
        Vec3 eye = a.getEyePosition();
        BlockPos feet = a.feetPos();
        long now = level.getGameTime();
        BlockPos best = null;
        for (long p : books.around(feet, (int) Math.ceil(REACH) + 1)) {
            Hold h = books.holds.get(p);
            if (h == null || h.kept()) continue;
            BlockPos pos = BlockPos.of(p);
            boolean mine = h.owner().equals(a.getUUID());
            boolean orphan = orphansToo && !mine && h.town() != null && h.town().equals(a.ownerId()) && now - h.laid() > ORPHAN_AGE;
            if (!mine && !orphan) continue;
            if (!intact(level, pos, h)) { books.forget(p); continue; }
            if (pos.equals(feet.below())) continue;
            if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) continue;
            if (stoodOn(level, pos)) continue;
            if (best == null || pos.getY() > best.getY()) best = pos;
        }
        return best;
    }

    /** Is anybody (a folk, a player, an animal) standing on this block? */
    private static boolean stoodOn(ServerLevel level, BlockPos pos) {
        return !level.getEntitiesOfClass(LivingEntity.class, new AABB(pos.above()).inflate(0.2, 0.0, 0.2)).isEmpty();
    }

    /**
     * The block taken up, and back into the pack (nothing from nothing): the break heard and seen. False, and the block
     * left standing, when the pack will not take it.
     */
    private static boolean takeUp(AssistantEntity a, ServerLevel level, BlockPos pos) {
        Hold h = of(level).holds.get(pos.asLong());
        if (h == null) return false;
        ItemStack back = new ItemStack(h.block().asItem());
        if (!back.isEmpty() && !a.insertItem(back.copy()).isEmpty()) {
            // No room: a single block has to fit somewhere, so a full pack means the whole of it. Left for later.
            return false;
        }
        a.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        level.destroyBlock(pos, false, a);
        a.swing(InteractionHand.MAIN_HAND);
        of(level).forget(pos.asLong());
        return true;
    }

    /** May it drop from on top of this block once it is taken: a fall of three at most, onto dry ground. */
    private static boolean safeBelow(ServerLevel level, BlockPos taken) {
        BlockPos.MutableBlockPos p = taken.mutable();
        for (int drop = 1; drop <= SAFE_DROP; drop++) {
            if (!level.getFluidState(p).isEmpty()) return false;
            BlockPos floor = p.below();
            BlockState fs = level.getBlockState(floor);
            if (!fs.getFluidState().isEmpty() || fs.is(Blocks.MAGMA_BLOCK) || fs.is(Blocks.CAMPFIRE)) return false;
            if (fs.isFaceSturdy(level, floor, Direction.UP)) return true;
            p.move(Direction.DOWN);
        }
        return false;
    }

    /**
     * Would taking this block up leave the folk stranded: the block a step or so under where it stands, and without it
     * fewer than FREE_CELLS places it could walk to (a ledge it reached by the pillar)?
     */
    private static boolean strands(AssistantEntity a, ServerLevel level, BlockPos pos) {
        BlockPos feet = a.feetPos();
        if (pos.getY() >= feet.getY() || pos.getY() < feet.getY() - 1 - SAFE_DROP) return false;
        return walkable(level, feet, pos) < FREE_CELLS;
    }

    /** How many cells (up to FREE_CELLS) a folk could walk to from here, a step up or a safe drop down, without this floor. */
    static int walkable(ServerLevel level, BlockPos from, BlockPos without) {
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        open.add(from);
        seen.add(from.asLong());
        while (!open.isEmpty() && seen.size() < FREE_CELLS) {
            BlockPos c = open.poll();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos side = c.relative(d);
                for (int dy = 1; dy >= -SAFE_DROP; dy--) {
                    BlockPos n = side.above(dy);
                    if (!standable(level, n, without)) continue;
                    if (seen.add(n.asLong())) open.add(n);
                    break;
                }
            }
        }
        return seen.size();
    }

    private static boolean standable(ServerLevel level, BlockPos feet, BlockPos without) {
        if (!level.isLoaded(feet)) return false;
        BlockPos floor = feet.below();
        if (floor.equals(without)) return false;
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
            && level.getFluidState(feet).isEmpty()
            && level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
    }

    /** This foothold, and every one of the same folk's joined to it, kept as its way down, and booked as a route. */
    private static void keep(ServerLevel level, BlockPos pos, AssistantEntity a) {
        Footholds books = of(level);
        List<BlockPos> column = new ArrayList<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        open.add(pos);
        Set<Long> seen = new HashSet<>();
        while (!open.isEmpty() && column.size() < MOST_HIGH * 4) {
            BlockPos p = open.poll();
            if (!seen.add(p.asLong())) continue;
            Hold h = books.holds.get(p.asLong());
            if (h == null || !h.owner().equals(a.getUUID())) continue;
            books.holds.put(p.asLong(), h.asKept());
            column.add(p);
            for (Direction d : Direction.values()) open.add(p.relative(d));
        }
        books.setDirty();
        if (column.isEmpty()) return;
        column.sort((x, y) -> Integer.compare(y.getY(), x.getY()));
        List<BlockPos> steps = new ArrayList<>(column.size());
        for (BlockPos p : column) steps.add(p.above());
        MineStairs.keptRoute(level, steps);
        a.brain("kept my steps at " + pos.toShortString() + " as the way down");
    }

    /** A kept way down let go (the town stopped using it): its blocks come down as leftovers like any other. */
    static void unkeep(ServerLevel level, List<BlockPos> steps) {
        Footholds books = of(level);
        for (BlockPos s : steps) {
            Hold h = books.holds.get(s.below().asLong());
            if (h != null && h.kept()) books.holds.put(s.below().asLong(), new Hold(h.owner(), h.town(), h.block(), 0L, h.purpose(), false));
        }
        books.setDirty();
    }

    /** Every so often, the routes nobody has used for a long while are let go (MineStairs.forgetUnused). */
    private static void lookOverRoutes(ServerLevel level) {
        long now = level.getGameTime();
        Long last = ROUTES_LOOKED.get(level.dimension());
        if (last != null && now - last < ROUTES_EVERY && now >= last) return;
        ROUTES_LOOKED.put(level.dimension(), now);
        MineStairs.forgetUnused(level, now);
    }

    // ------------------------------------------------------------------ kept with the world

    public static Footholds load(CompoundTag tag, HolderLookup.Provider registries) {
        Footholds f = new Footholds();
        for (Tag t : tag.getList("Holds", Tag.TAG_COMPOUND)) {
            CompoundTag one = (CompoundTag) t;
            ResourceLocation id = ResourceLocation.tryParse(one.getString("Block"));
            Block block = id == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(id);
            if (block == Blocks.AIR || !one.hasUUID("Owner")) continue;
            Purpose purpose;
            try { purpose = Purpose.valueOf(one.getString("For")); } catch (IllegalArgumentException e) { purpose = Purpose.CLIMB; }
            f.put(one.getLong("Pos"), new Hold(one.getUUID("Owner"), one.hasUUID("Town") ? one.getUUID("Town") : null, block,
                one.getLong("Laid"), purpose, one.getBoolean("Kept")));
        }
        return f;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag all = new ListTag();
        for (Map.Entry<Long, Hold> e : holds.entrySet()) {
            Hold h = e.getValue();
            CompoundTag one = new CompoundTag();
            one.putLong("Pos", e.getKey());
            one.putUUID("Owner", h.owner());
            if (h.town() != null) one.putUUID("Town", h.town());
            one.putString("Block", BuiltInRegistries.BLOCK.getKey(h.block()).toString());
            one.putLong("Laid", h.laid());
            one.putString("For", h.purpose().name());
            if (h.kept()) one.putBoolean("Kept", true);
            all.add(one);
        }
        tag.put("Holds", all);
        return tag;
    }

    public static void resetForTests(ServerLevel level) {
        Footholds f = of(level);
        f.holds.clear();
        f.byChunk.clear();
        f.setDirty();
        CLIMBING.clear();
        ROUTES_LOOKED.clear();
    }

    /** Tests: every foothold on the books within so many blocks, still standing, whoever laid it. */
    public static int countForTests(ServerLevel level, BlockPos near, int within) {
        return standingNear(level, near, within).size();
    }

    /** Tests: drop the books' memory of climbs (as a restart would). */
    public static void forgetClimbsForTests() {
        CLIMBING.clear();
    }
}
