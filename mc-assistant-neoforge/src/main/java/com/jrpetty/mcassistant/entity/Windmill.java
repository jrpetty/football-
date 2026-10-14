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
 * <li>[roles-c] <b>The miller.</b> One of the farmers keeps the mill: the one living nearest it, chosen once the mill
 *     stands (and again if it leaves the fields). It is first for the mill's work at the town's works (the sails, the
 *     loads carried up), paid an allowance for the office (Offices), dressed for it (Weave), and sells a player a sack
 *     of the mill's wheat when the mill has more than the bakery's two sacks (its talk option).</li>
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

    /** [roles-c] The tag on the town's miller (a farmer), kept on the folk in the world so a restart keeps it. */
    public static final String MILLER = "mca_miller";
    /** [roles-c] The mill keeps this much for the bakery (two sacks) before the miller sells any; a player's sack. */
    static final int MILL_KEEP = 2 * Bakery.SACK, PLAYERS_SACK = 16;

    static void resetForTests() {
    }

    // ------------------------------------------------------------------ [roles-c] the miller

    /** The town's miller: a farmer of its, grown and alive, wearing the tag; or null. */
    @Nullable
    public static VillageFolkEntity miller(@Nullable UUID village) {
        if (village == null) return null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.getTags().contains(MILLER)) {
                if (f.stationTask() == AssistantEntity.StationTask.FARM && !f.isBaby() && f.isAlive()) return f;
                f.removeTag(MILLER);                                      // left the fields: no longer the miller
            }
        }
        return null;
    }

    /** Is this folk the town's miller? */
    public static boolean isMiller(VillageFolkEntity f) {
        return f.getTags().contains(MILLER) && f.stationTask() == AssistantEntity.StationTask.FARM;
    }

    /** A miller chosen when the mill stands and has none: the farmer living nearest it (its home, else where it stands). */
    @Nullable
    static VillageFolkEntity appointMiller(ServerLevel level, Villages.Village v, Ledger.Building b) {
        if (miller(v.id()) != null) return null;
        BlockPos door = door(b);
        VillageFolkEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != AssistantEntity.StationTask.FARM || f.isBaby() || !f.isAlive()) continue;
            BlockPos home = f.getHome() != null ? f.getHome() : f.blockPosition();
            double d = home.distSqr(door);
            if (d < bestD) { bestD = d; best = f; }
        }
        if (best == null) return null;
        best.addTag(MILLER);
        best.brain("became the town's miller");
        FolkTalk.speak(best, "The mill's mine to keep now. Sails, sacks and all.");
        Villages.tell(v.id(), level.getDayTime() / 24000L, best.displayNameCap() + " became the town's miller");
        return best;
    }

    /** [TownJobs.choose] The miller is first for the mill's work. */
    static double lean(VillageFolkEntity f, String works) {
        return works.equals("mill") && isMiller(f) ? 200 : 0;
    }

    /**
     * A sack of the mill's wheat sold to a player by its miller, at the town's price into the treasury: never the
     * bakery's two sacks. Returns what the miller says.
     */
    static String sellSack(ServerLevel level, Villages.Village v, VillageFolkEntity miller, net.minecraft.world.entity.player.Player p, int want) {
        Ledger.Building b = mill(v.id());
        if (b == null) return "There's no mill standing yet.";
        int spare = grain(level, v.id()) - MILL_KEEP;
        if (spare <= 0) return "The mill's only got the bakery's wheat in it. Come back after the harvest.";
        int n = Math.min(Math.max(1, want), Math.min(spare, 64));
        ItemStack sample = new ItemStack(Items.WHEAT);
        int price = Math.max(Market.askLot(level, v.id(), sample, n), Market.leastLot(level, v.id(), sample, n));
        int coins = Market.coinsHeld(p);
        if (coins < price) return n + " wheat would be " + price + " coins. You've " + coins + ".";
        int got = draw(level, v.id(), n);
        if (got <= 0) return "Somebody's just had the last of it.";
        if (got < n) price = Math.max(Market.askLot(level, v.id(), sample, got), Market.leastLot(level, v.id(), sample, got));
        Market.payOut(p, price);
        Ledger.addCoins(v.id(), price);
        Economy.sold(v.id(), price);
        Stockroom.sold(level, v.id(), Stockroom.Seller.STORES, sample, got, price);
        ItemStack sack = new ItemStack(Items.WHEAT, got);
        if (!p.getInventory().add(sack)) p.drop(sack, false);
        miller.persona().remember(level.getDayTime() / 24000L, "sold " + p.getName().getString() + " a sack of the mill's wheat", 1);
        return "There: " + got + " wheat, straight out of the mill. " + price + (price == 1 ? " coin" : " coins") + " to the town's chest.";
    }

    /** The miller's card: its mill, and the wheat in it. */
    @Nullable
    static String cardPart(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || !isMiller(f) || !(f.level() instanceof ServerLevel level) || mill(village) == null) return null;
        return "the town's miller: " + grain(level, village) + " wheat in the mill";
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
        appointMiller(level, v, b);                                       // [roles-c] the mill's keeper, once it stands
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
        VillageFolkEntity miller = miller(village);                      // [roles-c] its miller by name
        return "the windmill " + (hung >= CLOTH.length ? "with its sails up" : "waiting on its sails") + ", " + grain(level, village) + " wheat in it"
            + (miller == null ? "" : ", " + miller.displayNameCap() + " its miller");
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

    /** [roles-c] Tests: the miller chosen now if the mill has none; the town's miller either way. */
    @Nullable
    public static VillageFolkEntity millerForTests(ServerLevel level, Villages.Village v) {
        Ledger.Building b = mill(v.id());
        if (b != null) appointMiller(level, v, b);
        return miller(v.id());
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
