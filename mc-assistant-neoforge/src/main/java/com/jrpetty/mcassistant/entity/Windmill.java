package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The windmill [batchE]: a stone and timber tower with sails, out by the fields (windmill.txt), the landmark
 * of the farmland. A farming town of sixteen builds one in the Stone Age.
 * <ul>
 * <li><b>Its sails.</b> The builders put up the axle and the four arms of fence; the cloth is hung after,
 *     three pieces to an arm, of the stores' wool (any colour the stores have), a few at a time by a hand at
 *     the works.</li>
 * <li><b>The town's grain.</b> Its two chests are where the town keeps its wheat put by: a farmer who comes
 *     past the mill with a load of wheat on its back leaves it there, and when the stores hold more wheat
 *     than the larder and the café want (sixty-four), a hand (a farmer for choice) carries the rest up to the
 *     mill, so the storehouse has room for everything else.</li>
 * <li><b>The bakery's flour.</b> The baker fetches its wheat from the mill first, and with a mill to grind
 *     it bakes twice the batch at a time (Bakery).</li>
 * </ul>
 */
public final class Windmill {

    private Windmill() {}

    /** A farming town of this many builds a windmill. */
    static final int FROM = 16;
    /** The wheat the stores keep for the larder, the café and the bake errands before any goes up to the mill. */
    static final int STORES_KEEP = 64;
    /** What the mill holds at most (its two chests could hold a great deal more: the rest is the stores'). */
    static final int GRAIN = 256;
    /** A load: what one hand carries up at a time. */
    static final int LOAD = 32;
    /** A farmer passing within this of the mill's door with this much wheat leaves it there. */
    static final int PASSING = 6, PASSING_LOAD = 16;

    /** The sails' cloth, in the drawing's terms {across, up}, on the front at the axle's depth: three to an arm. */
    static final int[][] CLOTH = {
        { 1, 8 }, { 1, 9 }, { 1, 10 },          // beside the arm going up
        { 2, 5 }, { 3, 5 }, { 4, 5 },           // under the arm going right
        { -1, 2 }, { -1, 3 }, { -1, 4 },        // beside the arm going down
        { -2, 7 }, { -3, 7 }, { -4, 7 } };      // over the arm going left
    /** The depth of the sails (the front of the tower is -2; the axle sticks out one more). */
    static final int SAILS = -3;

    static void resetForTests() {
    }

    @Nullable
    static Ledger.Building mill(UUID village) {
        return TownLook.building(village, TownLook.WINDMILL);
    }

    /** The mill's chests, as they stand. */
    static List<Container> chests(ServerLevel level, Ledger.Building b) {
        List<Container> out = new ArrayList<>();
        if (!level.isLoaded(b.anchor())) return out;
        for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
            if (p.part() != BuildGoal.Part.CHEST) continue;
            if (level.getBlockState(p.pos()).getBlock() instanceof ChestBlock && level.getBlockEntity(p.pos()) instanceof Container c) out.add(c);
        }
        return out;
    }

    static BlockPos door(Ledger.Building b) {
        return TownLook.cell(b, 0, 0, -3);
    }

    /** How much wheat the mill holds. */
    static int grain(ServerLevel level, UUID village) {
        Ledger.Building b = mill(village);
        if (b == null) return 0;
        int n = 0;
        for (Container c : chests(level, b)) n += count(c, s -> s.is(Items.WHEAT));
        return n;
    }

    private static int count(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    /** Up to so many wheat out of the mill's chests (for the bakery). Returns how many came out. */
    static int draw(ServerLevel level, UUID village, int n) {
        Ledger.Building b = mill(village);
        if (b == null) return 0;
        int got = 0;
        for (Container c : chests(level, b)) {
            for (int i = 0; i < c.getContainerSize() && got < n; i++) {
                ItemStack s = c.getItem(i);
                if (!s.is(Items.WHEAT) || !s.getComponentsPatch().isEmpty()) continue;
                int k = Math.min(n - got, s.getCount());
                s.shrink(k);
                got += k;
            }
            c.setChanged();
        }
        return got;
    }

    /** Wheat into the mill's chests; returns what would not go in. */
    static ItemStack store(ServerLevel level, Ledger.Building b, ItemStack wheat) {
        ItemStack left = wheat;
        for (Container c : chests(level, b)) {
            if (left.isEmpty()) break;
            left = Homes.insertInto(c, left);
            c.setChanged();
        }
        return left;
    }

    /** The wheat in the stores proper (the chests at the heart and the storehouse), not counting the mill's. */
    static int inStores(ServerLevel level, UUID village, Ledger.Building b) {
        List<Container> mine = chests(level, b);
        int n = 0;
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c) || mine.contains(c)) continue;
            n += count(c, s -> s.is(Items.WHEAT) && s.getComponentsPatch().isEmpty());
        }
        return n;
    }

    /** So much wheat out of the stores proper (never out of the mill itself). Returns how many came out. */
    static int fromStores(ServerLevel level, UUID village, Ledger.Building b, int n) {
        List<Container> mine = chests(level, b);
        int got = 0;
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (got >= n) break;
            if (!(level.getBlockEntity(p) instanceof Container c) || mine.contains(c)) continue;
            for (int i = 0; i < c.getContainerSize() && got < n; i++) {
                ItemStack s = c.getItem(i);
                if (!s.is(Items.WHEAT) || !s.getComponentsPatch().isEmpty()) continue;
                int k = Math.min(n - got, s.getCount());
                Economy.storesOut(village, s, k);
                if (level.getBlockEntity(p) instanceof com.jrpetty.mcassistant.block.StorehouseBlockEntity) {
                    Storekeeping.bookOut(level, village, null, s, k, null);
                }
                s.shrink(k);
                got += k;
            }
            c.setChanged();
        }
        return got;
    }

    /** Wheat brought down from the mill into the stores proper (never back into the mill's own chests). */
    static void toStores(ServerLevel level, Villages.Village v, Ledger.Building b, ItemStack wheat) {
        List<Container> mine = chests(level, b), boxes = new ArrayList<>();
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (level.getBlockEntity(p) instanceof Container c && !mine.contains(c)) boxes.add(c);
        }
        ItemStack left = Stacking.insert(boxes, wheat, new int[boxes.size()]);
        for (Container c : boxes) c.setChanged();
        if (!left.isEmpty()) Crafts.store(level, v, left);
    }

    /** One visit: the sails' cloth, a farmer's wheat left at the mill, or a load of the stores' carried up. */
    static boolean tick(ServerLevel level, Villages.Village v) {
        Ledger.Building b = mill(v.id());
        if (b == null || !Land.areaLoaded(level, b.anchor(), 8)) return false;
        if (sails(level, v, b, 3) > 0) return true;
        if (passing(level, v, b) > 0) return true;
        return carryUp(level, v, b) > 0;
    }

    /** The cloth cells of the sails, in the world. */
    static List<BlockPos> cloth(Ledger.Building b) {
        List<BlockPos> out = new ArrayList<>();
        for (int[] c : CLOTH) out.add(TownLook.cell(b, c[0], c[1], SAILS));
        return out;
    }

    /** Up to so many pieces of the sails' cloth hung, of the stores' wool. Returns how many. */
    static int sails(ServerLevel level, Villages.Village v, Ledger.Building b, int most) {
        // Only on arms that stand: the axle first (the builders' last piece of the sails).
        if (!level.getBlockState(TownLook.cell(b, 0, 6, SAILS)).is(BlockTags.LOGS)) return 0;
        List<BlockPos> bare = new ArrayList<>();
        for (BlockPos p : cloth(b)) if (level.getBlockState(p).isAir()) bare.add(p);
        if (bare.isEmpty() || Market.stock(level, v.id(), s -> s.is(ItemTags.WOOL)) < 1) return 0;
        if (Market.bedsShort(v.id()) > 0) return 0;                      // the beds' wool first
        if (!TownJobs.atWork(level, v, "mill", door(b), "hanging the windmill's sails")) return 0;
        int n = 0;
        for (BlockPos p : bare) {
            if (n >= most) break;
            ItemStack wool = Crafts.takeOne(level, v, s -> s.is(ItemTags.WOOL));
            if (wool.isEmpty()) break;
            Block block = Block.byItem(wool.getItem());
            level.setBlock(p, block.defaultBlockState(), 3);
            n++;
        }
        if (n > 0) {
            level.playSound(null, p0(bare), SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
            if (bare.size() == n) Villages.tell(v.id(), level.getDayTime() / 24000L, "the windmill's sails were hung");
        }
        return n;
    }

    private static BlockPos p0(List<BlockPos> l) {
        return l.get(0);
    }

    /** A farmer coming past the mill with a load of wheat on its back leaves it there. Returns how many. */
    static int passing(ServerLevel level, Villages.Village v, Ledger.Building b) {
        BlockPos at = door(b);
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != AssistantEntity.StationTask.FARM || f.isBaby()) continue;
            if (f.blockPosition().distSqr(at) > (double) PASSING * PASSING) continue;
            int held = f.countCarried(s -> s.is(Items.WHEAT) && s.getComponentsPatch().isEmpty());
            if (held < PASSING_LOAD || grain(level, v.id()) >= GRAIN) continue;
            int took = f.removeMatching(s -> s.is(Items.WHEAT) && s.getComponentsPatch().isEmpty(), held);
            ItemStack left = store(level, b, new ItemStack(Items.WHEAT, took));
            if (!left.isEmpty()) f.insertItem(left);
            n += took - left.getCount();
            if (took - left.getCount() > 0) f.brain("left its wheat at the windmill");
        }
        return n;
    }

    /** The stores' wheat past what they keep, carried up to the mill a load at a time. Returns how many. */
    static int carryUp(ServerLevel level, Villages.Village v, Ledger.Building b) {
        int spare = inStores(level, v.id(), b) - STORES_KEEP;
        int room = GRAIN - grain(level, v.id());
        int load = Math.min(LOAD, Math.min(spare, room));
        if (load < 8 || chests(level, b).isEmpty()) return 0;
        if (!TownJobs.atWork(level, v, "mill", door(b), "carrying the wheat up to the windmill", AssistantEntity.StationTask.FARM)) return 0;
        int got = fromStores(level, v.id(), b, load);
        if (got <= 0) return 0;
        ItemStack left = store(level, b, new ItemStack(Items.WHEAT, got));
        if (!left.isEmpty()) Crafts.store(level, v, left);
        return got - left.getCount();
    }

    // ------------------------------------------------------------------ where a player sees it

    @Nullable
    static String boardPart(ServerLevel level, UUID village) {
        Ledger.Building b = mill(village);
        if (b == null || !level.isLoaded(b.anchor())) return null;
        int hung = 0;
        for (BlockPos p : cloth(b)) if (level.getBlockState(p).is(BlockTags.WOOL)) hung++;
        return "the windmill " + (hung >= CLOTH.length ? "with its sails up" : "waiting on its sails") + ", " + grain(level, village) + " wheat in it";
    }

    static String line(ServerLevel level, Villages.Village v) {
        String part = boardPart(level, v.id());
        return part == null ? "none yet" : part;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the mill's rounds so many times over. Returns how many did something. */
    public static int roundsForTests(ServerLevel level, Villages.Village v, int rounds) {
        int n = 0;
        for (int i = 0; i < rounds; i++) if (tick(level, v)) n++;
        return n;
    }

    /** Tests: the wheat in the mill. */
    public static int grainForTests(ServerLevel level, UUID village) {
        return grain(level, village);
    }

    /** Tests: the sails' cloth cells. */
    public static List<BlockPos> clothForTests(UUID village) {
        Ledger.Building b = mill(village);
        return b == null ? List.of() : cloth(b);
    }

    /** Tests: the mill's door (where a farmer passing leaves its wheat). */
    @Nullable
    public static BlockPos doorForTests(UUID village) {
        Ledger.Building b = mill(village);
        return b == null ? null : door(b);
    }
}
