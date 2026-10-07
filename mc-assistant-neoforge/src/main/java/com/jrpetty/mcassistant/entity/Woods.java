package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wf] The woods kept growing. A woodcutter fells a tree and puts a sapling on the stump (GatherGoal), but
 * only with a sapling in its pack: out of saplings, the stump went on its books and waited for one, and a
 * wood it had felled and could not replant was a clearing for good. The four hundred days' mountain town
 * held no logs at all on most mornings, its houses eating every one that came in. So a village's
 * woodcutter now does what a player keeping a tree farm does, between its fellings:
 * <ul>
 * <li><b>Saplings from the drops.</b> Short of saplings ({@link #KEEP} in hand), it knocks down the crown
 *     of a tree it felled — leaves with no trunk left to hold them, that would drop in a minute or two
 *     anyway — and takes up what falls: saplings, sticks and the odd apple. Nothing else gives it one
 *     (the stores' saplings it fetches as before, VillageFolkEntity.kitFromTheStores).</li>
 * <li><b>A sapling for every tree.</b> Every stump it made (GatherGoal tells it: {@link #felled}) gets a
 *     sapling as soon as it has one, unless another is already growing within two blocks.</li>
 * <li><b>The wood kept thick.</b> With saplings beyond the ones it keeps for the stumps, it plants its own
 *     ground in the open, two blocks clear of any trunk or sapling, till the ground holds a tree or a
 *     sapling to every {@link #GROUND_PER_TREE} blocks of it: a wood it has felled grows back a wood, not
 *     a thicket and not a clearing. Never in the town, on the farmland or on a building's ground.</li>
 * <li><b>Grown on.</b> While the town is short of timber (the age asks for it, or the stores hold under
 *     sixty-four logs), it feeds its saplings bone meal: its own, or the stores', or the watch's bones
 *     crushed into it.</li>
 * </ul>
 * Each is an errand of a few seconds — walk to the spot, do it there — looked for every ten seconds when
 * the woodcutter is at its station's work, and given up after half a minute without getting there.
 */
public final class Woods {

    private Woods() {}

    /** How often a woodcutter looks its wood over for an errand (ticks). */
    static final long LOOK = 200L;
    /** Saplings it keeps in hand for the stumps of its next felling; it shakes a crown down when it has fewer. */
    public static final int KEEP = 4;
    /** One tree, or a sapling, to so many blocks of its ground: the wood is planted this thick and no thicker. */
    public static final int GROUND_PER_TREE = 20;
    /** The most it plants, or feeds, at one go before it goes back to its felling. */
    static final int AT_A_GO = 8;
    /** How near the spot it must stand to work it. */
    static final double REACH = 4.0;
    /** An errand it gets no nearer to in this long is given up; and any errand after this long. */
    static final long NO_NEARER = 200L, TOO_LONG = 600L;
    /** Round a stump or a spot: no other trunk or sapling this near (two saplings side by side choke). */
    static final int ROOM = 2;
    /** How far round itself it looks for a felled crown (and for its saplings to feed). */
    static final int LOOK_ROUND = 10;

    enum Doing { SHAKE, PLANT, FEED }

    /** What it is doing: walking to a spot to do this there. */
    static final class Errand {
        final Doing doing;
        final BlockPos at;
        final long started;
        double best = Double.MAX_VALUE;
        long progress;
        boolean walk = true;

        Errand(Doing doing, BlockPos at, long now) {
            this.doing = doing;
            this.at = at.immutable();
            this.started = now;
            this.progress = now;
        }
    }

    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Errand> ERRANDS = new ConcurrentHashMap<>();
    /** The stumps each woodcutter has made and not yet seen a sapling on. */
    private static final Map<UUID, Set<Long>> STUMPS = new ConcurrentHashMap<>();
    /** Each woodcutter's tally: {saplings from the drops, saplings planted, saplings fed}. */
    private static final Map<UUID, int[]> TALLY = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LOOKED.clear();
        ERRANDS.clear();
        STUMPS.clear();
        TALLY.clear();
    }

    /** GatherGoal: a village's woodcutter took the bottom log of a tree off this stump. */
    public static void felled(AssistantEntity a, BlockPos stump) {
        if (!(a instanceof VillageFolkEntity f) || f.stationTask() != StationTask.WOOD) return;
        Set<Long> s = STUMPS.computeIfAbsent(f.getUUID(), k -> ConcurrentHashMap.newKeySet());
        if (s.size() < 64) s.add(stump.asLong());
    }

    /** Is this woodcutter on an errand in its wood just now? */
    public static boolean busy(VillageFolkEntity f) {
        return ERRANDS.containsKey(f.getUUID());
    }

    /** Its tally: {saplings from the drops, planted, fed}. */
    public static int[] tally(VillageFolkEntity f) {
        int[] t = TALLY.get(f.getUUID());
        return t == null ? new int[3] : t.clone();
    }

    // ------------------------------------------------------------------ the errand

    /**
     * From the woodcutter's station work (AssistantEntity, the woods' case, through woodsWork): carry on
     * with the errand it is on, or look its wood over for one (every ten seconds). True while it is at one.
     */
    public static boolean tend(VillageFolkEntity f, ServerLevel level) {
        if (f.stationTask() != StationTask.WOOD || f.isBaby() || f.ownerId() == null) return false;
        WorkZone z = f.workZone();
        if (z == null) {
            ERRANDS.remove(f.getUUID());
            return false;
        }
        long now = level.getGameTime();
        Errand e = ERRANDS.get(f.getUUID());
        if (e != null && (now - e.started > TOO_LONG || now < e.started)) {
            ERRANDS.remove(f.getUUID());
            e = null;
        }
        if (e == null) {
            Long last = LOOKED.get(f.getUUID());
            if (last != null && now - last < LOOK && now >= last) return false;
            LOOKED.put(f.getUUID(), now);
            e = next(f, level, z, now);
            if (e == null) return false;
            ERRANDS.put(f.getUUID(), e);
        }
        return carryOn(f, level, z, e, now);
    }

    /** The errand there is for it now, if any: a crown to shake down, a spot to plant, a sapling to feed. */
    @Nullable
    private static Errand next(VillageFolkEntity f, ServerLevel level, WorkZone z, long now) {
        int saplings = f.countCarried(s -> s.is(ItemTags.SAPLINGS));
        Kitchen.apples(f, level);                                    // [kitchen] the apples under the felled trees, for the cider
        if (saplings < KEEP || Kitchen.applesWanted(level, f)) {    // [kitchen] a crown shaken for its apples too
            BlockPos crown = nearestCrown(level, f, z);
            if (crown != null) return new Errand(Doing.SHAKE, crown, now);
        }
        Ground g = Ground.of(level, f, z);
        if (saplings > 0) {
            BlockPos spot = plantSpot(level, f, z, g, saplings);
            if (spot != null) return new Errand(Doing.PLANT, spot, now);
        }
        if (!g.saplings.isEmpty() && timberShort(level, f)
                && (f.countCarried(s -> s.is(Items.BONE_MEAL)) > 0 || storesFeed(level, f) > 0)) {
            BlockPos me = f.blockPosition();
            BlockPos best = null;
            for (BlockPos p : g.saplings) if (best == null || p.distSqr(me) < best.distSqr(me)) best = p;
            if (best != null) return new Errand(Doing.FEED, best, now);
        }
        return null;
    }

    private static boolean carryOn(VillageFolkEntity f, ServerLevel level, WorkZone z, Errand e, long now) {
        double dx = f.getX() - (e.at.getX() + 0.5), dz = f.getZ() - (e.at.getZ() + 0.5);
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d > REACH - 1.0 || Math.abs(f.getY() - e.at.getY()) > 3.0) {
            if (d < e.best - 0.5) {
                e.best = d;
                e.progress = now;
            } else if (now - e.progress > NO_NEARER || now < e.progress) {
                ERRANDS.remove(f.getUUID());                        // across water, up a bank: another time
                f.brain("could not get to the spot in its wood; left it a while");
                return false;
            }
            if (e.walk || f.getNavigation().isDone()) {
                f.walkTo(e.at, 1.0D);
                e.walk = false;
            }
            f.brain(switch (e.doing) {
                case SHAKE -> "going to shake a felled tree's crown down for saplings";
                case PLANT -> "going to plant saplings in its wood";
                case FEED -> "going to feed its saplings bone meal";
            });
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(e.at.getX() + 0.5, e.at.getY() + 1.0, e.at.getZ() + 0.5);
        ERRANDS.remove(f.getUUID());
        switch (e.doing) {
            case SHAKE -> shake(f, level, z);
            case PLANT -> plant(f, level, z);
            case FEED -> feed(f, level, z);
        }
        return true;
    }

    // ------------------------------------------------------------------ saplings from the drops

    /** Leaves with nothing left to hold them: a felled tree's crown, that will drop of itself before long. */
    static boolean felledLeaves(BlockState st) {
        return st.getBlock() instanceof LeavesBlock && st.hasProperty(LeavesBlock.PERSISTENT) && st.hasProperty(LeavesBlock.DISTANCE)
            && !st.getValue(LeavesBlock.PERSISTENT) && st.getValue(LeavesBlock.DISTANCE) >= LeavesBlock.DECAY_DISTANCE;
    }

    /** The ground under the nearest felled crown in its wood near it, or null. */
    @Nullable
    private static BlockPos nearestCrown(ServerLevel level, VillageFolkEntity f, WorkZone z) {
        BlockPos me = f.blockPosition();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(me.offset(-LOOK_ROUND, -4, -LOOK_ROUND), me.offset(LOOK_ROUND, 12, LOOK_ROUND))) {
            if (!z.containsColumn(p) || !level.isLoaded(p)) continue;
            if (!felledLeaves(level.getBlockState(p))) continue;
            double d = p.distSqr(me);
            if (d < bd) {
                bd = d;
                best = p.immutable();
            }
        }
        if (best == null) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, best.getX(), best.getZ());
        return new BlockPos(best.getX(), Math.min(y, best.getY()), best.getZ());
    }

    /**
     * Knock the felled crowns round it down and take up what falls: saplings, sticks, apples. As many as
     * sixty-four leaves at a go (an oak's crown is sixty-odd); what falls is the leaves' own drops, as the
     * game makes them.
     */
    private static void shake(VillageFolkEntity f, ServerLevel level, WorkZone z) {
        BlockPos me = f.blockPosition();
        int knocked = 0;
        for (BlockPos p : BlockPos.betweenClosed(me.offset(-5, -2, -5), me.offset(5, 9, 5))) {
            if (knocked >= 64) break;
            if (!z.containsColumn(p) || !felledLeaves(level.getBlockState(p))) continue;
            if (level.destroyBlock(p, true, f)) knocked++;
        }
        if (knocked == 0) return;
        f.swing(InteractionHand.MAIN_HAND);
        int saplings = 0;
        AABB box = new AABB(me).inflate(7.0, 11.0, 7.0);
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, box,
                d -> d.isAlive() && d.getAge() < 40 && leafDrop(d.getItem()) && !Sweepers.playersOwn(d))) {
            ItemStack st = drop.getItem();
            ItemStack picked = st.copy();
            ItemStack left = f.insertItem(st.copy());
            int took = picked.getCount() - left.getCount();
            if (took <= 0) continue;
            Economy.gathered(f, picked, took);
            if (picked.is(ItemTags.SAPLINGS)) saplings += took;
            if (left.isEmpty()) drop.discard();
            else drop.setItem(left);
        }
        TALLY.computeIfAbsent(f.getUUID(), k -> new int[3])[0] += saplings;
        f.brain("shook " + knocked + " leaves down off a felled crown: " + saplings + (saplings == 1 ? " sapling" : " saplings"));
        if (saplings > 0 && f.getRandom().nextInt(3) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There's the next tree, in the leaves of the last.",
                "A sapling or two for the stump. That's how a wood keeps."));
        }
    }

    private static boolean leafDrop(ItemStack s) {
        return s.is(ItemTags.SAPLINGS) || s.is(Items.STICK) || s.is(Items.APPLE) || s.is(Items.MANGROVE_PROPAGULE);
    }

    // ------------------------------------------------------------------ the planting

    /** Its wood as it stands: the trunks and saplings in it, and how many it should hold. */
    static final class Ground {
        int trunks;
        final List<BlockPos> saplings = new ArrayList<>();
        int want;

        boolean thin() {
            return trunks + saplings.size() < want;
        }

        static Ground of(ServerLevel level, VillageFolkEntity f, WorkZone z) {
            Ground g = new Ground();
            g.want = Math.max(1, z.sizeX() * z.sizeZ() / GROUND_PER_TREE);
            for (int x = z.min().getX(); x <= z.max().getX(); x++) {
                for (int zz = z.min().getZ(); zz <= z.max().getZ(); zz++) {
                    if (!level.hasChunk(x >> 4, zz >> 4)) continue;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, zz);
                    BlockPos top = new BlockPos(x, y - 1, zz);
                    BlockState st = level.getBlockState(top);
                    if (st.is(BlockTags.LOGS)) {
                        // Down the trunk to its foot: a trunk standing on the earth is a tree.
                        BlockPos p = top;
                        for (int i = 0; i < 32 && level.getBlockState(p.below()).is(BlockTags.LOGS); i++) p = p.below();
                        BlockState foot = level.getBlockState(p.below());
                        if (foot.is(BlockTags.DIRT)) g.trunks++;
                        // A tree half felled: its foot cut, its stump planted again at once, and the rest of the
                        // trunk still standing over the new sapling. That sapling is one of the wood's: missed, the
                        // wood looked a tree thin and a sapling went on the open ground besides (23 of 22).
                        else if (foot.is(BlockTags.SAPLINGS)) g.saplings.add(p.below().immutable());

                    } else if (level.getBlockState(top.above()).is(BlockTags.SAPLINGS)) {
                        g.saplings.add(top.above());
                    }
                }
            }
            return g;
        }
    }

    /** The town's own ground (its reach, its fields, its building sites), never planted. */
    private static boolean townGround(VillageFolkEntity f, BlockPos p) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return false;
        BlockPos c = v.centre();
        int dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ();
        if (Math.max(Math.abs(dx), Math.abs(dz)) <= Villages.townReach(id) + 2) return true;
        if (Villages.onFarmland(id, dx, dz, 1, 1)) return true;
        for (Villages.Site s : Villages.sitesOf(id).values()) {
            int r = Math.max(6, s.radius() + 3);
            if (Math.abs(p.getX() - s.anchor().getX()) <= r && Math.abs(p.getZ() - s.anchor().getZ()) <= r) return true;
        }
        return false;
    }

    /**
     * Could a sapling be planted here, and grow? Earth under it and nothing growing within two blocks; and
     * on open ground (not a stump) open sky over it and no trunk near it either: under a standing crown
     * it would never grow. A stump's own crown is coming down, and a tree stood there before.
     */
    static boolean plantable(ServerLevel level, BlockPos at, boolean stump) {
        BlockState here = level.getBlockState(at);
        if (!(here.isAir() || here.canBeReplaced() && here.getFluidState().isEmpty() && !here.is(BlockTags.SAPLINGS))) return false;
        BlockState ground = level.getBlockState(at.below());
        if (!ground.is(BlockTags.DIRT) || ground.is(Blocks.FARMLAND)) return false;
        if (!stump) {
            if (!level.canSeeSky(at)) return false;
            for (int i = 1; i <= 4; i++) if (!level.getBlockState(at.above(i)).isAir()) return false;
        }
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-ROOM, -1, -ROOM), at.offset(ROOM, 3, ROOM))) {
            BlockState st = level.getBlockState(q);
            if (st.is(BlockTags.SAPLINGS) || !stump && st.is(BlockTags.LOGS)) return false;
        }
        return true;
    }

    /** The free block on top of this column (the sapling's spot). */
    private static BlockPos topOf(ServerLevel level, int x, int z) {
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /** The nearest stump with room for a sapling, else (with saplings to spare, in a thin wood) the nearest open spot. */
    @Nullable
    private static BlockPos plantSpot(ServerLevel level, VillageFolkEntity f, WorkZone z, Ground g, int saplings) {
        BlockPos me = f.blockPosition();
        Set<Long> stumps = STUMPS.get(f.getUUID());
        if (stumps != null) {
            BlockPos best = null;
            for (Long l : new ArrayList<>(stumps)) {
                BlockPos s = BlockPos.of(l);
                if (!level.isLoaded(s)) continue;
                if (!z.containsColumn(s) || !plantable(level, s, true)) {
                    // A sapling on it already (its own, at the felling), a tree grown back, or no room: done with.
                    if (!level.getBlockState(s).isAir() || !z.containsColumn(s)) stumps.remove(l);
                    continue;
                }
                if (best == null || s.distSqr(me) < best.distSqr(me)) best = s;
            }
            if (best != null) return best;
        }
        if (saplings <= KEEP || !g.thin()) return null;
        List<BlockPos> open = new ArrayList<>();
        for (int x = z.min().getX(); x <= z.max().getX(); x++) {
            for (int zz = z.min().getZ(); zz <= z.max().getZ(); zz++) {
                if (!level.hasChunk(x >> 4, zz >> 4)) continue;
                open.add(topOf(level, x, zz));
            }
        }
        open.sort(Comparator.comparingDouble(p -> p.distSqr(me)));
        int looked = 0;
        for (BlockPos p : open) {
            if (++looked > 400) break;
            if (Math.abs(p.getY() - me.getY()) > 8 || townGround(f, p)) continue;
            if (plantable(level, p, false)) return p;
        }
        return null;
    }

    /** A sapling from its pack that will take here (a dark oak only by fours, which is how it grows). */
    @Nullable
    private static ItemStack saplingFor(VillageFolkEntity f, ServerLevel level, BlockPos at) {
        for (ItemStack s : f.getInventoryItems()) {
            if (s.isEmpty() || !s.is(ItemTags.SAPLINGS) || !(s.getItem() instanceof BlockItem bi)) continue;
            if (s.is(Items.DARK_OAK_SAPLING)) continue;
            if (bi.getBlock().defaultBlockState().canSurvive(level, at)) return s;
        }
        return null;
    }

    /** Plant here, and at the stumps and open spots within reach: up to eight at a go. */
    private static void plant(VillageFolkEntity f, ServerLevel level, WorkZone z) {
        BlockPos me = f.blockPosition();
        Ground g = Ground.of(level, f, z);
        int room = Math.max(0, g.want - g.trunks - g.saplings.size());
        Set<Long> stumps = STUMPS.getOrDefault(f.getUUID(), Set.of());
        List<BlockPos> spots = new ArrayList<>();
        for (Long l : stumps) {
            BlockPos s = BlockPos.of(l);
            if (s.distSqr(me) <= (REACH + 1) * (REACH + 1)) spots.add(s);
        }
        int r = (int) Math.ceil(REACH);
        for (int x = me.getX() - r; x <= me.getX() + r; x++) {
            for (int zz = me.getZ() - r; zz <= me.getZ() + r; zz++) {
                BlockPos p = topOf(level, x, zz);
                if (!spots.contains(p) && z.containsColumn(p) && p.distSqr(me) <= (REACH + 1) * (REACH + 1)) spots.add(p);
            }
        }
        int planted = 0;
        for (BlockPos p : spots) {
            if (planted >= AT_A_GO) break;
            boolean stump = stumps.contains(p.asLong());
            // Open ground only with saplings beyond the stumps' and while the wood is thin; a stump always.
            if (!stump && (room <= 0 || f.countCarried(s -> s.is(ItemTags.SAPLINGS)) <= KEEP || townGround(f, p))) continue;
            if (!plantable(level, p, stump)) {
                if (stump && !level.getBlockState(p).isAir()) stumps.remove(p.asLong());
                continue;
            }
            ItemStack s = saplingFor(f, level, p);
            if (s == null) break;
            BlockState sapling = ((BlockItem) s.getItem()).getBlock().defaultBlockState();
            if (!(level.getBlockState(p).isAir())) level.removeBlock(p, false);   // the grass in the way
            level.setBlockAndUpdate(p, sapling);
            f.placeSound(p);
            s.shrink(1);
            planted++;
            if (stump) stumps.remove(p.asLong());
            else room--;
            f.note(AssistantEntity.Deed.SAPLINGS_PLANTED, 1);
            f.noteRichSpot(p);                                       // come back when it has grown
        }
        if (planted == 0) return;
        f.swing(InteractionHand.MAIN_HAND);
        TALLY.computeIfAbsent(f.getUUID(), k -> new int[3])[1] += planted;
        f.brain("planted " + planted + (planted == 1 ? " sapling" : " saplings") + " in its wood");
        if (f.getRandom().nextInt(4) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "One in for every one out. The wood'll be here when I'm gone.",
                "Saplings in. Give them a season.", "Plant as you fell, my old master said."));
        }
    }

    // ------------------------------------------------------------------ grown on

    /** Is the town short of timber: the age asks for it, or the stores hold under sixty-four logs? */
    static boolean timberShort(ServerLevel level, VillageFolkEntity f) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return false;
        if (Villages.stock(level, v.centre(), Villages.Task.LOGS, Villages.storesRadius(id)) < 64) return true;
        return Fuel.ageWants(level, id, Villages.Task.LOGS);
    }

    /** Bone meal the stores could give it: what they hold, and three for every bone. */
    private static int storesFeed(ServerLevel level, VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return 0;
        return Market.stock(level, id, s -> s.is(Items.BONE_MEAL)) + 3 * Market.stock(level, id, s -> s.is(Items.BONE));
    }

    /** Bone meal on the saplings within reach: its own, else the stores' (the watch's bones crushed if need be). */
    private static void feed(VillageFolkEntity f, ServerLevel level, WorkZone z) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return;
        if (f.countCarried(s -> s.is(Items.BONE_MEAL)) == 0) {
            int r = Math.max(48, Villages.storesRadius(id));
            int got = f.drawFrom(v.centre(), s -> s.is(Items.BONE_MEAL), 8, r);
            if (got == 0 && f.drawFrom(v.centre(), s -> s.is(Items.BONE), 2, r) > 0) {
                int bones = f.removeMatching(s -> s.is(Items.BONE), 2);
                ItemStack left = f.insertItem(new ItemStack(Items.BONE_MEAL, 3 * bones));
                if (!left.isEmpty()) Crafts.store(level, v, left);
            }
            if (f.countCarried(s -> s.is(Items.BONE_MEAL)) == 0) return;
        }
        BlockPos me = f.blockPosition();
        int fed = 0;
        for (BlockPos p : BlockPos.betweenClosed(me.offset(-4, -2, -4), me.offset(4, 3, 4))) {
            if (fed >= 4) break;
            if (!z.containsColumn(p)) continue;
            BlockState st = level.getBlockState(p);
            if (!st.is(BlockTags.SAPLINGS) || !(st.getBlock() instanceof BonemealableBlock b)) continue;
            if (!b.isValidBonemealTarget(level, p, st)) continue;
            if (f.removeMatching(s -> s.is(Items.BONE_MEAL), 1) != 1) break;
            if (b.isBonemealSuccess(level, level.getRandom(), p, st)) b.performBonemeal(level, level.getRandom(), p.immutable(), st);
            level.levelEvent(1505, p, 15);                          // the green sparkle
            fed++;
        }
        if (fed == 0) return;
        f.swing(InteractionHand.MAIN_HAND);
        TALLY.computeIfAbsent(f.getUUID(), k -> new int[3])[2] += fed;
        f.brain("fed " + fed + (fed == 1 ? " sapling" : " saplings") + " bone meal: the town is short of timber");
    }

    // ------------------------------------------------------------------ tests

    /** Tests: look the wood over now, and see the errand through at once if it is within reach. */
    public static boolean tendForTests(VillageFolkEntity f, ServerLevel level) {
        LOOKED.remove(f.getUUID());
        return tend(f, level);
    }

    /** Tests: a stump this woodcutter made (as GatherGoal tells it at a felling). */
    public static void felledForTests(VillageFolkEntity f, BlockPos stump) {
        felled(f, stump);
    }

    /** Tests: the errand it is on, as a word, or "". */
    public static String errandForTests(VillageFolkEntity f) {
        Errand e = ERRANDS.get(f.getUUID());
        return e == null ? "" : e.doing.name().toLowerCase() + " at " + e.at.toShortString();
    }

    /** Tests: its wood as it stands, {trunks, saplings, wanted}. */
    public static int[] groundForTests(VillageFolkEntity f, ServerLevel level) {
        WorkZone z = f.workZone();
        if (z == null) return new int[3];
        Ground g = Ground.of(level, f, z);
        return new int[]{ g.trunks, g.saplings.size(), g.want };
    }

    /** Tests: is a sapling plantable on this spot (open earth, open sky, room round it)? */
    public static boolean plantableForTests(ServerLevel level, BlockPos at) {
        return plantable(level, at, false);
    }
}
