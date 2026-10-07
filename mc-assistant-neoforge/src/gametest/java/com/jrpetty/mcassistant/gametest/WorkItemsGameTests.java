package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.MilestoneBlock;
import com.jrpetty.mcassistant.block.MilestoneBlockEntity;
import com.jrpetty.mcassistant.block.PitPropBlock;
import com.jrpetty.mcassistant.block.RopeBlock;
import com.jrpetty.mcassistant.block.ShippingCrateBlock;
import com.jrpetty.mcassistant.block.ShippingCrateBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.block.WindowBoxBlock;
import com.jrpetty.mcassistant.entity.Ages;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Caravans;
import com.jrpetty.mcassistant.entity.CaveDwellers;
import com.jrpetty.mcassistant.entity.Crates;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Leader;
import com.jrpetty.mcassistant.entity.Makers;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Milestones;
import com.jrpetty.mcassistant.entity.Palettes;
import com.jrpetty.mcassistant.entity.Prices;
import com.jrpetty.mcassistant.entity.RecipeBook;
import com.jrpetty.mcassistant.entity.Ropes;
import com.jrpetty.mcassistant.entity.StreetFurniture;
import com.jrpetty.mcassistant.entity.Thatch;
import com.jrpetty.mcassistant.entity.Tiers;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WindowBoxes;
import com.jrpetty.mcassistant.entity.WorkSites;
import com.jrpetty.mcassistant.entity.WorkTools;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.OreSackItem;
import com.jrpetty.mcassistant.item.WindowBoxItem;
import com.jrpetty.mcassistant.item.WorkItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [workitems] The tools of the mine, the woods and the roads (item/WorkItems, entity/WorkTools and the classes beside it):
 * each made by its own trade out of the stores by its real recipe, and each put to its real use, by the folk and by a
 * player.
 *
 * <ul>
 * <li><b>wi01</b>: the pit prop: Wood Age, a quarter of a coin; the woodcutter makes eight of the stores' logs and planks
 *     for the town's miner, who draws six; along a gallery under a seam of gravel it stands one at the foot and every
 *     five steps; the gravel over the propped stretch stays up when the gravel past it falls; its propped face is dug a
 *     sixth quicker; the mine's report counts them; and a player stands one by hand.</li>
 * <li><b>wi02</b>: the rope coil: the tailor knots one of four string and a leather for the miner, who draws it; at a
 *     shaft it lets the rope down, climbs down it and back up it, and the rope stays as the mine's way down; a player lets
 *     a coil down a tower's side, and breaking a piece halfway down takes the whole rope up as a coil again.</li>
 * <li><b>wi03</b>: the ore sack: Stone Age; the tailor sews one for the miner; a miner with a full pack tips its ore into
 *     the sack and works on; at the chest the sack is unpacked and kept, empty; a cave dweller's haul comes out of its sack
 *     onto its back; a player's sack takes up the ore it walks over and tips it out again.</li>
 * <li><b>wi04</b>: the felling saw: the smith makes one of two bars, two sticks and a string for the woodcutter; with it,
 *     the woodcutter's cut at a tree's foot brings down the whole tree, logs at the stump; a log cabin is never felled;
 *     a player sneaking with a saw fells a tree in one.</li>
 * <li><b>wi05</b>: thatch: a fed Wood Age town with wheat to spare roofs in it; the farmer makes it of the wheat, a builder
 *     cuts the roof's stairs and slabs of it at the bench; it burns like hay, a chimney's spark catches a thatched roof;
 *     and in the Stone Age the town re-roofs the house in tiles, the thatch back into the stores.</li>
 * <li><b>wi06</b>: the milestone: a road between two towns gets its stones, out of the mother's stores (made there of
 *     cobblestone and a sign), at its ends and every hundred blocks, each lettered with both towns and their distance by
 *     the road; a player who asks is told the way; one a player sets by the road is lettered for it.</li>
 * <li><b>wi07</b>: the shipping crate: the woodcutter makes two for the courier, who draws them; at a full chest it packs
 *     eighteen stacks into them past what its pack would hold; at the stores they are unpacked and kept, empty; a crate
 *     broken keeps what is in it, and set down again it is all still there.</li>
 * <li><b>wi08</b>: the window box: the shop's hand makes a box of cornflowers for a well-off household; its gardener carries
 *     it home and hangs it under the window; the household is the happier, the house worth more; dry for days it wilts and
 *     the gardener waters it from a bucket of the stores'; a player waters it too; it dies back in winter and is up again
 *     in the spring.</li>
 * <li><b>wi09</b>: the caravan's crates: a caravan sets out with its loose load and three crates of the town's surplus
 *     besides; the colony buys the lot; the crates come home empty, into the stores.</li>
 * <li><b>wi10</b>: the cave team's rope: two coils out of the stores; with no way down to its cave, the leader lets a rope
 *     down the cliff and climbs down, the other after it; going home they climb back up it, and the last up takes the rope
 *     up, the coil in its pack.</li>
 * </ul>
 *
 * <p>Each on its own ground, x 1,240,000 to 1,258,000 on z 66,000, a batch of its own, the logic called directly where a
 * town would take days to come to it.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class WorkItemsGameTests {

    private static final String EMPTY = "empty";
    private static final long DAY = 24000L * 4;
    private static final int Z = 66000;

    // ------------------------------------------------------------------ the ground and the town

    /** Flat grass round here, clear air above it. Returns the ground's top (the first air). */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        return flat(level, cx - r, cz - r, cx + r, cz + r, Kit.surface(level, cx, cz).getY());
    }

    private static BlockPos flat(ServerLevel level, int x0, int z0, int x1, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 28; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos((x0 + x1) / 2, y, (z0 + z1) / 2);
    }

    /** A clean slate for the work items: the shared statics and the world's props and boxes forgotten. */
    private static void slate(ServerLevel level, long time) {
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        level.setDayTime(time);
        level.updateSkyBrightness();
        level.setWeatherParameters(24000, 0, false, false);
        WorkTools.resetForTests();
        WorkSites.resetForTests(level);
    }

    /** A village's first folk on clean flat ground at x. */
    private static VillageFolkEntity founder(GameTestHelper helper, int x, long time, int hold) {
        ServerLevel level = helper.getLevel();
        slate(level, time);
        Kit.hold(level, x, Z, hold);
        Kit.prepare(level, x, Z, hold);
        BlockPos heart = flat(level, x, Z, 48);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        return f;
    }

    private static VillageFolkEntity another(GameTestHelper helper, BlockPos at, UUID village) {
        ServerLevel level = helper.getLevel();
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, at.getX(), at.getZ()), 0.0F);
        helper.assertTrue(f != null && village.equals(f.ownerId()), "another folk of the village");
        return f;
    }

    /** Its pack emptied (a founder's starter kit, a newcomer's bread): only what the test gives it. */
    private static void clearPack(VillageFolkEntity f) {
        f.getInventoryItems().clear();
    }

    /** The town's stores emptied, and two more marked chests by the heart for the test's goods. */
    private static void emptyStores(GameTestHelper helper, ServerLevel level, UUID village, BlockPos heart) {
        for (int k = 0; k < 2; k++) {
            BlockPos at = new BlockPos(heart.getX() + 4 + 2 * k, heart.getY(), heart.getZ() - 5);
            level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
            ZoneChests.mark(level, at);
        }
        Villages.forgetStores(village);
        List<BlockPos> chests = Villages.storeChests(level, village);
        helper.assertTrue(!chests.isEmpty(), "the town has its stores");
        for (BlockPos p : chests) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
        Villages.forgetStock();
    }

    /** These into the stores, as a player would bring them. */
    private static void stock(GameTestHelper helper, ServerLevel level, UUID village, ItemStack... goods) {
        int g = 0;
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize() && g < goods.length; i++) {
                if (!c.getItem(i).isEmpty()) continue;
                c.setItem(i, goods[g++].copy());
            }
            c.setChanged();
        }
        helper.assertTrue(g == goods.length, "room in the stores for the test's goods: " + (goods.length - g) + " over");
        Villages.forgetStock();
    }

    private static int stores(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        Villages.forgetStock();
        return Market.stock(level, village, what);
    }

    private static int stores(ServerLevel level, UUID village, Item it) {
        return stores(level, village, s -> s.is(it));
    }

    private static int count(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    /** A thing's papers: a real recipe, its age, its worth, a maker named, and (for those folk buy) the market's board. */
    private static void papers(GameTestHelper helper, ServerLevel level, Item it, Villages.Age age, double worth, boolean board) {
        List<RecipeBook.Way> ways = RecipeBook.waysFor(level, it);
        Villages.Age a = Tiers.of(level, it);
        double each = Prices.each(it);
        String name = new ItemStack(it).getHoverName().getString();
        Kit.log("  " + name + ": " + ways.size() + " recipe(s), the " + a.label + ", worth " + each + "c, made by " + Makers.words(it));
        helper.assertTrue(!ways.isEmpty(), "a real recipe for " + name);
        helper.assertTrue(a == age, name + " belongs to the " + age.label + ", not the " + a.label);
        helper.assertTrue(Prices.known(it) && Math.abs(each - worth) < 1e-6, name + " is worth " + worth + ": " + each);
        helper.assertTrue(!Makers.of(it).isEmpty(), "somebody makes " + name);
        if (board) helper.assertTrue(Market.goodFor(new ItemStack(it)) != null, "the market deals in " + name);
    }

    /** A stand-in player, in Survival, here. */
    private static ServerPlayer player(GameTestHelper helper, ServerLevel level, double x, double y, double z) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        p.teleportTo(level, x, y, z, Set.of(), 0.0F, 0.0F);
        return p;
    }

    /** The player uses what it holds on this face of this block, as a right-click would. */
    private static InteractionResult useOn(ServerPlayer p, ServerLevel level, ItemStack held, BlockPos clicked, Direction face) {
        p.setItemInHand(InteractionHand.MAIN_HAND, held);
        Vec3 hit = Vec3.atCenterOf(clicked).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return p.gameMode.useItemOn(p, level, p.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, clicked, false));
    }

    /** What lies on the ground near here of this. */
    private static int lying(ServerLevel level, BlockPos at, double r, Predicate<ItemStack> what) {
        int n = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(r))) {
            if (e.isAlive() && what.test(e.getItem())) n += e.getItem().getCount();
        }
        return n;
    }

    @SafeVarargs
    private static ItemStack[] goods(ItemStack... s) {
        return s;
    }

    private static ItemStack[] many(Item it, int n) {
        List<ItemStack> out = new ArrayList<>();
        while (n > 0) {
            int k = Math.min(n, it.getDefaultMaxStackSize());
            out.add(new ItemStack(it, k));
            n -= k;
        }
        return out.toArray(new ItemStack[0]);
    }

    private static ItemStack[] join(ItemStack[]... parts) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack[] p : parts) out.addAll(List.of(p));
        return out.toArray(new ItemStack[0]);
    }

    /** A block of stone with a hole where {@code hole} says, its corner here. */
    private static void stone(ServerLevel level, BlockPos corner, int dx, int dy, int dz, java.util.function.BiPredicate<BlockPos, BlockPos> hole) {
        for (int x = 0; x < dx; x++) {
            for (int y = 0; y < dy; y++) {
                for (int z = 0; z < dz; z++) {
                    BlockPos rel = new BlockPos(x, y, z), p = corner.offset(x, y, z);
                    level.setBlock(p, hole.test(rel, p) ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState(), 2 | 16);
                }
            }
        }
    }

    // ============================================================ wi01: the pit prop

    /**
     * The woodcutter makes props of the stores' logs and planks while the town has a miner (eight a miner kept); the miner
     * draws six; cutting its gallery under a gravel seam it stands one at the foot and every fifth step; the gravel over the
     * propped stretch stays up where the gravel past it falls in; a propped face is dug a sixth quicker; and a player sets
     * one down by hand, the beam on its post.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "wi01_pit_props")
    public static void wi01_pit_props(GameTestHelper helper) {
        int x = 1240000;
        VillageFolkEntity founder = founder(helper, x, DAY + 3000, 56);
        ServerLevel level = helper.getLevel();
        UUID id = founder.ownerId();
        Villages.ageForTests(id, Villages.Age.STONE);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity cutter = another(helper, heart.south(3), id);
        VillageFolkEntity miner = another(helper, heart.south(5), id);
        Item prop = WorkItems.PIT_PROP_ITEM.get();
        BlockPos[] gallery = new BlockPos[1];
        long[] at = { 0 };
        helper.runAtTickTime(5, () -> {
            founder.setJob(StationTask.FARM);                       // none of the trades the test counts
            cutter.setJob(StationTask.WOOD);
            miner.setJob(StationTask.MINE);
            clearPack(miner);
            papers(helper, level, prop, Villages.Age.WOOD, 0.25, true);
            emptyStores(helper, level, id, heart);
            stock(helper, level, id, join(many(Items.OAK_LOG, 128), many(Items.OAK_PLANKS, 128), goods(new ItemStack(Items.CRAFTING_TABLE))));
            int logs0 = stores(level, id, Items.OAK_LOG), planks0 = stores(level, id, Items.OAK_PLANKS);
            Map<Item, Integer> wanted = WorkTools.wantedForTests(level, id);
            String one = WorkTools.craftForTests(level, cutter), two = WorkTools.craftForTests(level, cutter);
            int props = stores(level, id, prop), logs1 = stores(level, id, Items.OAK_LOG), planks1 = stores(level, id, Items.OAK_PLANKS);
            Kit.log("wi01 the town wants " + wanted + "; the woodcutter: " + one + " / " + two + "; props in the stores " + props + "; logs "
                + logs0 + " -> " + logs1 + ", planks " + planks0 + " -> " + planks1);
            helper.assertTrue(wanted.getOrDefault(prop, 0) == 8, "eight props kept for the one miner: " + wanted);
            helper.assertTrue(one != null && one.contains("pit prop") && props == 8, "the woodcutter made eight props: " + one + ", " + props);
            helper.assertTrue(logs0 - logs1 == 4 && planks0 - planks1 == 6, "two logs and three planks a making of four, out of the stores: "
                + (logs0 - logs1) + " logs, " + (planks0 - planks1) + " planks");
            // The miner's kit.
            boolean took = WorkTools.kitUp(miner);
            helper.assertTrue(took && miner.countCarried(s -> s.is(prop)) == 6 && stores(level, id, prop) == 2,
                "the miner drew six props out of the stores: " + miner.countCarried(s -> s.is(prop)));
            // The gallery: a block of stone with a tunnel two high cut along it, a seam of gravel in its roof.
            BlockPos g = Kit.surface(level, heart.getX() + 14, heart.getZ() - 22);   // the ground there (the heart may stand on a block)
            gallery[0] = g;
            stone(level, g.offset(-1, 0, -2), 24, 5, 5, (rel, p) -> rel.getZ() == 2 && rel.getY() <= 1 && rel.getX() >= 1 && rel.getX() <= 21);
            for (int dx : new int[]{ 2, 3, 7, 8, 18 }) level.setBlock(g.offset(dx, 2, 0), Blocks.GRAVEL.defaultBlockState(), 3);
            WorkTools.newRun(miner);
            for (int i = 1; i <= 11; i++) WorkTools.propStep(miner, g.east(i), Direction.EAST, i);
            List<Integer> stood = new ArrayList<>();
            for (int dx = 0; dx <= 20; dx++) {
                BlockState s = level.getBlockState(g.east(dx));
                if (s.getBlock() instanceof PitPropBlock && s.getValue(PitPropBlock.HALF) == DoubleBlockHalf.LOWER
                        && level.getBlockState(g.east(dx).above()).getBlock() instanceof PitPropBlock) stood.add(dx);
            }
            Kit.log("wi01 props stood at steps " + stood + "; the miner has " + miner.countCarried(s -> s.is(prop)) + " left; mine report: "
                + WorkTools.mineReport(id));
            helper.assertTrue(stood.equals(List.of(0, 5, 10)), "a prop at the foot and every five steps: " + stood);
            helper.assertTrue(miner.countCarried(s -> s.is(prop)) == 3, "three out of the miner's pack");
            helper.assertTrue(String.join(" ", WorkTools.mineReport(id)).contains("Pit props: 3 stood"), "the mine's report counts them");
            // The face propped: dug a sixth quicker. Another face, unpropped: as ever.
            miner.setWorkZone(WorkZone.around(g.east(5), 8, 4));
            boolean propped = WorkTools.proppedForTests(level, miner);
            int pace = WorkTools.propPace(miner, 100);
            miner.setWorkZone(WorkZone.around(heart.offset(-30, 0, 30), 8, 4));
            boolean bare = WorkTools.proppedForTests(level, miner);
            miner.setWorkZone(null);
            Kit.log("wi01 the propped face: " + propped + ", a hundred ticks' block in " + pace + "; an unpropped face propped: " + bare);
            helper.assertTrue(propped && pace == 85 && !bare, "a propped face is dug in 85 of every 100 ticks: " + pace);
            // A player's prop.
            BlockPos floor = Kit.surface(level, heart.getX() - 10, heart.getZ() + 12).below();
            ServerPlayer p = player(helper, level, floor.getX() + 2.5, floor.getY() + 1, floor.getZ() + 0.5);
            InteractionResult r = useOn(p, level, new ItemStack(prop), floor, Direction.UP);
            BlockState lo = level.getBlockState(floor.above()), hi = level.getBlockState(floor.above(2));
            Kit.log("wi01 the player's prop: " + r + "; " + lo + " / " + hi + "; left in hand " + p.getMainHandItem().getCount());
            helper.assertTrue(lo.getBlock() instanceof PitPropBlock && lo.getValue(PitPropBlock.HALF) == DoubleBlockHalf.LOWER
                && hi.getBlock() instanceof PitPropBlock && hi.getValue(PitPropBlock.HALF) == DoubleBlockHalf.UPPER, "the player's prop stands, post and cap");
            helper.assertTrue(p.getMainHandItem().isEmpty(), "out of the player's hand");
            Kit.noLeftoverPlayers(level);
            at[0] = level.getGameTime();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(gallery[0] != null && level.getGameTime() - at[0] >= 25, "the gravel given time to fall");
            BlockPos g = gallery[0];
            List<String> held = new ArrayList<>();
            for (int dx : new int[]{ 2, 3, 7, 8 }) {
                boolean up = level.getBlockState(g.offset(dx, 2, 0)).is(Blocks.GRAVEL);
                held.add(dx + ":" + (up ? "held" : "FELL"));
                helper.assertTrue(up && WorkTools.held(level, g.offset(dx, 2, 0)), "the gravel over the propped stretch stays up at step " + dx);
            }
            boolean fell = level.getBlockState(g.offset(18, 2, 0)).isAir() && !WorkTools.held(level, g.offset(18, 2, 0));
            Kit.log("wi01 the seam: " + held + "; past the props, at step 18: " + (fell ? "fell in" : "still up") + " ("
                + level.getBlockState(g.offset(18, 0, 0)).getBlock() + " on the floor)");
            helper.assertTrue(fell, "the gravel past the props falls in");
        });
    }

    // ============================================================ wi02: the rope coil

    /**
     * The tailor knots a coil of four string and a leather for the miner, who draws it; at a shaft twelve deep the miner lets
     * it down, climbs down, and climbs back up again, the rope left hanging as the mine's way down; and a player lets a
     * coil down the side of a tower and takes it up again by breaking a piece of it halfway down.
     */
    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "wi02_rope")
    public static void wi02_rope(GameTestHelper helper) {
        int x = 1242000;
        VillageFolkEntity founder = founder(helper, x, DAY + 3000, 56);
        ServerLevel level = helper.getLevel();
        UUID id = founder.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity tailor = another(helper, heart.south(3), id);
        VillageFolkEntity miner = another(helper, heart.south(5), id);
        Item coil = WorkItems.ROPE_COIL.get();
        Object[] st = new Object[3];
        int[] phase = { 0 };
        helper.runAtTickTime(5, () -> {
            founder.setJob(StationTask.FARM);
            tailor.setJob(StationTask.TAILOR);
            miner.setJob(StationTask.MINE);
            clearPack(miner);
            papers(helper, level, coil, Villages.Age.WOOD, 1.6, true);
            emptyStores(helper, level, id, heart);
            stock(helper, level, id, new ItemStack(Items.STRING, 16), new ItemStack(Items.LEATHER, 8), new ItemStack(Items.CRAFTING_TABLE));
            int string0 = stores(level, id, Items.STRING), leather0 = stores(level, id, Items.LEATHER);
            String made = WorkTools.craftForTests(level, tailor);
            int coils = stores(level, id, coil), string1 = stores(level, id, Items.STRING), leather1 = stores(level, id, Items.LEATHER);
            Kit.log("wi02 the tailor: " + made + "; coils " + coils + "; string " + string0 + " -> " + string1 + ", leather " + leather0 + " -> " + leather1);
            helper.assertTrue(made != null && made.contains("rope coil") && coils == 1, "the tailor made a coil: " + made);
            helper.assertTrue(string0 - string1 == 4 && leather0 - leather1 == 1, "four string and a leather out of the stores");
            helper.assertTrue(WorkTools.kitUp(miner) && miner.countCarried(s -> s.is(coil)) == 1, "the miner drew it");
            // A shaft twelve deep down the middle of a tower of stone twenty high, the miner on its lip. The shaft's floor
            // is eight blocks up the tower: a shaft whose foot is within ten of the world's floor is the deep lava's, and
            // no miner ropes down into that (CI's flat world is four blocks deep).
            BlockPos g = Kit.surface(level, heart.getX() + 16, heart.getZ() + 16);
            stone(level, g, 5, 20, 5, (rel, p) -> rel.getX() == 2 && rel.getZ() == 2 && rel.getY() >= 8);
            BlockPos cursor = g.offset(1, 20, 2), newFeet = g.offset(2, 19, 2);
            miner.teleportTo(cursor.getX() + 0.5, cursor.getY(), cursor.getZ() + 0.5);
            BlockPos bottom = Ropes.downTheShaft(miner, cursor, newFeet);
            BlockState top = level.getBlockState(newFeet);
            Kit.log("wi02 at the shaft: down to " + bottom + "; the rope's top " + top + "; on it " + java.util.Arrays.toString(Ropes.ridingForTests(miner))
                + "; coils left " + miner.countCarried(s -> s.is(coil)));
            helper.assertTrue(g.offset(2, 8, 2).equals(bottom), "down the shaft to its floor: " + bottom);
            helper.assertTrue(top.getBlock() instanceof RopeBlock && top.getValue(RopeBlock.PART) == RopeBlock.Part.TOP
                && level.getBlockState(g.offset(2, 8, 2)).getBlock() instanceof RopeBlock
                && level.getBlockState(g.offset(2, 8, 2)).getValue(RopeBlock.PART) == RopeBlock.Part.BOTTOM, "the rope let down the shaft to the floor");
            helper.assertTrue(Ropes.ridingForTests(miner)[0] && Ropes.ridingForTests(miner)[1], "the miner climbing down it");
            helper.assertTrue(miner.countCarried(s -> s.is(coil)) == 0, "the coil out of its pack");
            st[0] = g;
            st[1] = cursor;
            st[2] = level.getGameTime();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(st[0] != null, "set up");
            BlockPos g = (BlockPos) st[0], cursor = (BlockPos) st[1];
            if (phase[0] == 0) {
                helper.assertTrue(!Ropes.riding(miner) && miner.getY() < g.getY() + 9.0, "the miner down the rope: at " + miner.blockPosition().toShortString()
                    + ", on it " + Ropes.riding(miner));
                Kit.log("wi02 at the foot of the shaft after " + (level.getGameTime() - (Long) st[2]) + " ticks, at " + miner.blockPosition().toShortString());
                boolean up = Ropes.upTheShaft(miner, miner.blockPosition(), cursor);
                helper.assertTrue(up, "and up it again on the way home");
                phase[0] = 1;
                st[2] = level.getGameTime();
                helper.fail("on to the climb up");
            }
            if (phase[0] == 1) {
                helper.assertTrue(!Ropes.riding(miner) && miner.getY() >= cursor.getY() - 0.1 && miner.blockPosition().distManhattan(cursor) <= 1,
                    "the miner up the rope: at " + miner.blockPosition().toShortString());
                boolean stays = level.getBlockState(g.offset(2, 19, 2)).getBlock() instanceof RopeBlock;
                List<String> report = WorkTools.mineReport(miner.ownerId());
                Kit.log("wi02 up again after " + (level.getGameTime() - (Long) st[2]) + " ticks; the rope still down the shaft: " + stays + "; report " + report);
                helper.assertTrue(stays, "the rope left as the mine's way down");
                helper.assertTrue(String.join(" ", report).contains("Ropes: 1 let down a shaft"), "the mine's report has it: " + report);
                // A player's coil let down the tower's east side, and taken up again.
                ServerPlayer p = player(helper, level, g.getX() + 3.5, g.getY() + 20, g.getZ() + 0.5);
                BlockPos edge = g.offset(4, 19, 0), ropeTop = edge.east();
                InteractionResult r = useOn(p, level, new ItemStack(coil), edge, Direction.EAST);
                int pieces = 0;
                for (int dy = 0; dy < 26; dy++) if (level.getBlockState(ropeTop.below(dy)).getBlock() instanceof RopeBlock) pieces++;
                BlockState t = level.getBlockState(ropeTop);
                Kit.log("wi02 the player's coil: " + r + "; " + pieces + " pieces down the side; its top " + t + "; in hand " + p.getMainHandItem());
                helper.assertTrue(pieces == 20 && t.getBlock() instanceof RopeBlock && t.getValue(RopeBlock.FACING) == Direction.WEST,
                    "twenty pieces down to the ground, hitched to the tower: " + pieces);
                helper.assertTrue(p.getMainHandItem().isEmpty(), "the coil out of the player's hand");
                boolean broke = p.gameMode.destroyBlock(ropeTop.below(5));
                int left = 0;
                for (int dy = 0; dy < 26; dy++) if (level.getBlockState(ropeTop.below(dy)).getBlock() instanceof RopeBlock) left++;
                int dropped = lying(level, ropeTop, 3.0, s -> s.is(coil));
                Kit.log("wi02 a piece broken halfway down (" + broke + "): " + left + " pieces left; coils dropped at the top " + dropped);
                helper.assertTrue(left == 0 && dropped == 1, "the whole rope up, and the coil back: " + left + " left, " + dropped + " dropped");
                Kit.noLeftoverPlayers(level);
            }
        });
    }

    // ============================================================ wi03: the ore sack

    /**
     * Stone Age. The tailor sews a sack of three leathers and two string for the miner, who draws it; with its pack full
     * the miner tips its ore and coal into the sack and works on; at the chest the sack is unpacked, the ore in, and the
     * empty sack kept; a cave dweller's sack comes out onto its back at the stores; and a player's sack takes up the ore
     * it walks over, and tips it out again.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "wi03_ore_sack")
    public static void wi03_ore_sack(GameTestHelper helper) {
        int x = 1244000;
        VillageFolkEntity founder = founder(helper, x, DAY + 3000, 56);
        ServerLevel level = helper.getLevel();
        UUID id = founder.ownerId();
        Villages.ageForTests(id, Villages.Age.STONE);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity tailor = another(helper, heart.south(3), id);
        VillageFolkEntity miner = another(helper, heart.south(5), id);
        VillageFolkEntity caver = another(helper, heart.south(7), id);
        Item sack = WorkItems.ORE_SACK.get();
        helper.runAtTickTime(5, () -> {
            founder.setJob(StationTask.FARM);
            tailor.setJob(StationTask.TAILOR);
            miner.setJob(StationTask.MINE);
            caver.setJob(StationTask.CAVE);
            clearPack(miner);
            clearPack(caver);
            // Each has its ropes already: the tailor's first want is a sack.
            miner.insertItem(new ItemStack(WorkItems.ROPE_COIL.get()));
            caver.insertItem(new ItemStack(WorkItems.ROPE_COIL.get(), 2));
            papers(helper, level, sack, Villages.Age.STONE, 2.5, true);
            emptyStores(helper, level, id, heart);
            stock(helper, level, id, new ItemStack(Items.STRING, 16), new ItemStack(Items.LEATHER, 12), new ItemStack(Items.CRAFTING_TABLE));
            int string0 = stores(level, id, Items.STRING), leather0 = stores(level, id, Items.LEATHER);
            Map<Item, Integer> wanted = WorkTools.wantedForTests(level, id);
            String one = WorkTools.craftForTests(level, tailor), two = WorkTools.craftForTests(level, tailor);
            int sacks = stores(level, id, sack), string1 = stores(level, id, Items.STRING), leather1 = stores(level, id, Items.LEATHER);
            Kit.log("wi03 wanted " + wanted + "; the tailor: " + one + " / " + two + "; sacks " + sacks + "; string " + string0 + " -> " + string1
                + ", leather " + leather0 + " -> " + leather1);
            helper.assertTrue(wanted.getOrDefault(sack, 0) == 2, "a sack for the miner and one for the cave dweller: " + wanted);
            helper.assertTrue(one != null && one.contains("ore sack") && two != null && sacks == 2, "the tailor sewed two: " + one + ", " + two);
            helper.assertTrue(string0 - string1 == 4 && leather0 - leather1 == 6, "two string and three leathers a sack");
            helper.assertTrue(WorkTools.kitUp(miner) && miner.countCarried(s -> s.is(sack)) == 1, "the miner drew one");
            // The miner's pack full: its ore and coal tipped into the sack, and room again.
            ItemStack[] load = { new ItemStack(Items.COAL, 64), new ItemStack(Items.RAW_IRON, 64), new ItemStack(Items.RAW_COPPER, 40),
                new ItemStack(Items.COAL, 30) };
            for (ItemStack s : load) miner.insertItem(s);
            while (miner.insertItem(new ItemStack(Items.COBBLESTONE, 64)).isEmpty()) { }
            int free0 = 0;
            for (ItemStack s : miner.getInventoryItems()) if (s.isEmpty()) free0++;
            boolean stowed = WorkTools.stowOre(miner);
            int free1 = 0, inSack = 0;
            ItemStack theSack = ItemStack.EMPTY;
            for (ItemStack s : miner.getInventoryItems()) {
                if (s.isEmpty()) free1++;
                if (s.is(sack)) theSack = s;
            }
            inSack = OreSackItem.count(theSack);
            Kit.log("wi03 a full pack (" + free0 + " free): stowed " + stowed + ", " + free1 + " free now, " + inSack + " in the sack; card: "
                + WorkTools.cardLine(miner));
            helper.assertTrue(free0 == 0 && stowed && free1 >= 3, "the ore into the sack, and room in the pack: " + free1);
            helper.assertTrue(inSack == 198 && miner.countCarried(s -> s.is(Items.COAL) || s.is(Items.RAW_IRON)) == 0, "all of it in the sack: " + inSack);
            helper.assertTrue(WorkTools.keeps(StationTask.MINE, theSack) == 0, "a loaded sack is the stores', not the miner's to keep");
            helper.assertTrue(WorkTools.cardLine(miner).contains("ore sack with 198"), "its card says so");
            // At the chest: unpacked, the sack kept.
            BlockPos chestAt = heart.offset(-6, 0, -6);
            level.setBlock(chestAt, Blocks.CHEST.defaultBlockState(), 3);
            Container chest = (Container) level.getBlockEntity(chestAt);
            List<ItemStack> booked = new ArrayList<>();
            int moved = WorkTools.unpackInto(miner, chest, booked);
            int coalIn = count(chest, s -> s.is(Items.COAL)), ironIn = count(chest, s -> s.is(Items.RAW_IRON));
            Kit.log("wi03 at the chest: " + moved + " unpacked (coal " + coalIn + ", raw iron " + ironIn + "); the sack holds " + OreSackItem.count(theSack)
                + "; kept: " + WorkTools.keeps(StationTask.MINE, theSack) + "; mine report " + WorkTools.mineReport(id));
            helper.assertTrue(moved == 198 && coalIn == 94 && ironIn == 64, "the sack's load into the chest: " + moved);
            helper.assertTrue(OreSackItem.count(theSack) == 0 && miner.countCarried(s -> s.is(sack)) == 1 && WorkTools.keeps(StationTask.MINE, theSack) == 1,
                "the empty sack kept for the next run");
            helper.assertTrue(String.join(" ", WorkTools.mineReport(id)).contains("Ore sacks: 1 time"), "the mine's report: " + WorkTools.mineReport(id));
            // The cave dweller: its sack drawn with its kit, and its haul out onto its back at the stores.
            List<String> kit = WorkTools.caveKitUp(level, Villages.get(id), caver);
            ItemStack cs = ItemStack.EMPTY;
            for (ItemStack s : caver.getInventoryItems()) if (s.is(sack)) cs = s;
            helper.assertTrue(kit.contains("an ore sack") && !cs.isEmpty(), "the cave dweller's kit has a sack: " + kit);
            OreSackItem.add(cs, new ItemStack(Items.RAW_GOLD, 20));
            int out = WorkTools.unpackSacks(caver);
            helper.assertTrue(out == 20 && caver.countCarried(s -> s.is(Items.RAW_GOLD)) == 20 && OreSackItem.count(cs) == 0,
                "the cave dweller's haul out of its sack: " + out);
            // A player's sack: the ore it walks over goes in, and tips out again.
            ServerPlayer p = player(helper, level, heart.getX() + 8.5, heart.getY(), heart.getZ() + 8.5);
            p.getInventory().setItem(0, new ItemStack(sack));
            p.getInventory().selected = 0;
            ItemEntity ore = new ItemEntity(level, p.getX(), p.getY(), p.getZ(), new ItemStack(Items.RAW_GOLD, 5));
            ore.setNoPickUpDelay();
            level.addFreshEntity(ore);
            ore.playerTouch(p);
            ItemStack ps = p.getInventory().getItem(0);
            int loose = 0;
            for (ItemStack s : p.getInventory().items) if (s.is(Items.RAW_GOLD)) loose += s.getCount();
            Kit.log("wi03 the player walked over five raw gold: the sack holds " + OreSackItem.count(ps) + ", loose " + loose + ", on the ground " + ore.isAlive());
            helper.assertTrue(OreSackItem.count(ps) == 5 && loose == 0 && !ore.isAlive(), "into the player's sack");
            p.gameMode.useItem(p, level, ps, InteractionHand.MAIN_HAND);
            loose = 0;
            for (ItemStack s : p.getInventory().items) if (s.is(Items.RAW_GOLD)) loose += s.getCount();
            helper.assertTrue(loose == 5 && OreSackItem.count(p.getInventory().getItem(0)) == 0, "tipped out into the pack: " + loose);
            Kit.noLeftoverPlayers(level);
            helper.succeed();
        });
    }

    // ============================================================ wi04: the felling saw

    /**
     * The smith makes a saw of two bars, two sticks and a string for the woodcutter, who draws it; its cut at a tree's
     * foot brings the whole tree down, the logs at the stump; a log cabin is never felled; and a player sneaking with a
     * saw fells a tree at a stroke.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "wi04_felling_saw")
    public static void wi04_felling_saw(GameTestHelper helper) {
        int x = 1246000;
        VillageFolkEntity founder = founder(helper, x, DAY + 3000, 56);
        ServerLevel level = helper.getLevel();
        UUID id = founder.ownerId();
        Villages.ageForTests(id, Villages.Age.STONE);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity smith = another(helper, heart.south(3), id);
        VillageFolkEntity cutter = another(helper, heart.south(5), id);
        Item saw = WorkItems.FELLING_SAW.get();
        helper.runAtTickTime(5, () -> {
            founder.setJob(StationTask.FARM);
            smith.setJob(StationTask.SMITH);
            cutter.setJob(StationTask.WOOD);
            clearPack(cutter);
            papers(helper, level, saw, Villages.Age.STONE, 4.0, true);
            emptyStores(helper, level, id, heart);
            stock(helper, level, id, new ItemStack(Items.IRON_INGOT, 40), new ItemStack(Items.STICK, 16), new ItemStack(Items.STRING, 8),
                new ItemStack(Items.CRAFTING_TABLE));
            int iron0 = stores(level, id, Items.IRON_INGOT), sticks0 = stores(level, id, Items.STICK), string0 = stores(level, id, Items.STRING);
            String made = WorkTools.craftForTests(level, smith), again = WorkTools.craftForTests(level, smith);
            int saws = stores(level, id, saw), iron1 = stores(level, id, Items.IRON_INGOT), sticks1 = stores(level, id, Items.STICK),
                string1 = stores(level, id, Items.STRING);
            Kit.log("wi04 the smith: " + made + " / " + again + "; saws " + saws + "; iron " + iron0 + " -> " + iron1 + ", sticks " + sticks0 + " -> "
                + sticks1 + ", string " + string0 + " -> " + string1);
            helper.assertTrue(made != null && made.contains("felling saw") && again == null && saws == 1, "the smith made one saw, for the one woodcutter");
            helper.assertTrue(iron0 - iron1 == 2 && sticks0 - sticks1 == 2 && string0 - string1 == 1, "two bars, two sticks and a string");
            helper.assertTrue(WorkTools.kitUp(cutter) && cutter.countCarried(s -> s.is(saw)) == 1, "the woodcutter drew it");
            // A tree, out past the square.
            int tx = heart.getX() + 30, tz = heart.getZ() - 26;
            Kit.wildTree(level, tx, tz);
            BlockPos base = Kit.surface(level, tx, tz);
            while (level.getBlockState(base.below()).is(BlockTags.LOGS)) base = base.below();
            int n = WorkTools.fellRest(cutter, base);
            int standing = 0;
            for (int dy = 1; dy < 6; dy++) if (level.getBlockState(base.above(dy)).is(BlockTags.LOGS)) standing++;
            int atStump = lying(level, base, 2.5, s -> s.is(Items.OAK_LOG));
            ItemStack inHand = cutter.getMainHandItem();
            Kit.log("wi04 the woodcutter's cut at " + base.toShortString() + ": " + n + " logs felled, " + standing + " still up, " + atStump
                + " at the stump; the saw in hand " + inHand + " (" + inHand.getDamageValue() + " worn); card: " + WorkTools.cardLine(cutter));
            helper.assertTrue(n == 4 && standing == 0 && level.getBlockState(base).is(BlockTags.LOGS), "the whole tree over its foot: " + n);
            helper.assertTrue(atStump >= 4, "the logs at the stump: " + atStump);
            helper.assertTrue(inHand.is(saw) && inHand.getDamageValue() == 4, "the saw in its hands, a cut a log: " + inHand.getDamageValue());
            helper.assertTrue(WorkTools.felled(cutter)[0] == 1 && WorkTools.felled(cutter)[1] == 5 && WorkTools.cardLine(cutter).contains("felled 1 whole tree"),
                "counted on its card: " + WorkTools.cardLine(cutter));
            // A log cabin's corner post: logs, a plank against them, a roof of leaves. Never felled.
            BlockPos post = Kit.surface(level, heart.getX() + 30, heart.getZ() + 26);
            for (int dy = 0; dy < 4; dy++) level.setBlock(post.above(dy), Blocks.OAK_LOG.defaultBlockState(), 3);
            level.setBlock(post.offset(1, 1, 0), Blocks.OAK_PLANKS.defaultBlockState(), 3);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlock(post.offset(dx, 4, dz), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), 3);
                }
            }
            int cabin = WorkTools.fellRest(cutter, post);
            int cabinUp = 0;
            for (int dy = 0; dy < 4; dy++) if (level.getBlockState(post.above(dy)).is(BlockTags.LOGS)) cabinUp++;
            Kit.log("wi04 the cabin's post: " + cabin + " felled, " + cabinUp + " logs standing");
            helper.assertTrue(cabin == 0 && cabinUp == 4, "a build is not a tree");
            // A player sneaking with a saw.
            int px = heart.getX() - 30, pz = heart.getZ() - 26;
            Kit.wildTree(level, px, pz);
            BlockPos pb = Kit.surface(level, px, pz);
            while (level.getBlockState(pb.below()).is(BlockTags.LOGS)) pb = pb.below();
            ServerPlayer p = player(helper, level, pb.getX() + 1.5, pb.getY(), pb.getZ() + 0.5);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(saw));
            p.setShiftKeyDown(true);
            boolean cut = p.gameMode.destroyBlock(pb);
            int left = 0;
            for (int dy = 0; dy < 6; dy++) if (level.getBlockState(pb.above(dy)).is(BlockTags.LOGS)) left++;
            int dropped = lying(level, pb, 2.5, s -> s.is(Items.OAK_LOG));
            Kit.log("wi04 the player's cut (" + cut + "): " + left + " logs left standing, " + dropped + " at the stump; the saw worn "
                + p.getMainHandItem().getDamageValue());
            helper.assertTrue(left == 0 && dropped == 5, "the whole tree down, five logs at the stump: " + left + " left, " + dropped);
            helper.assertTrue(p.getMainHandItem().getDamageValue() >= 4, "the saw worn by it");
            Kit.noLeftoverPlayers(level);
            helper.succeed();
        });
    }

    // ============================================================ wi05: thatch

    /**
     * A fed Wood Age town with wheat to spare roofs in thatch (its roofs' palette takes thatch first); the farmer makes it of
     * the wheat, and a builder cuts the roof's stairs and slabs of it at the bench; thatch burns like hay, and a chimney's
     * spark catches a thatched roof; in the Stone Age the house is re-roofed in tiles, the thatch back into the stores.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "wi05_thatch")
    public static void wi05_thatch(GameTestHelper helper) {
        int x = 1248000;
        VillageFolkEntity founder = founder(helper, x, DAY + 3000, 56);
        ServerLevel level = helper.getLevel();
        UUID id = founder.ownerId();
        Villages.ageForTests(id, Villages.Age.WOOD);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity farmer = another(helper, heart.south(3), id);
        VillageFolkEntity builder = another(helper, heart.south(5), id);
        Item thatch = WorkItems.THATCH_ITEM.get(), stairs = WorkItems.THATCH_STAIRS_ITEM.get(), slab = WorkItems.THATCH_SLAB_ITEM.get();
        helper.runAtTickTime(5, () -> {
            founder.setJob(StationTask.FARM);
            farmer.setJob(StationTask.FARM);
            builder.setJob(StationTask.FARM);
            clearPack(builder);
            papers(helper, level, thatch, Villages.Age.WOOD, 0.18, true);
            papers(helper, level, stairs, Villages.Age.WOOD, 0.3, false);
            papers(helper, level, slab, Villages.Age.WOOD, 0.1, false);
            Villages.Village v = Villages.get(id);
            long day = level.getDayTime() / 24000L;
            Leader.booksForTests(id, new Leader.Books(400, 40, 20, 40, 20, 20.0, Leader.Plan.PLENTY, day));
            emptyStores(helper, level, id, heart);
            stock(helper, level, id, new ItemStack(Items.WHEAT, 64), new ItemStack(Items.WHEAT, 64), new ItemStack(Items.CRAFTING_TABLE));
            boolean thatching = Thatch.thatchingForTests(level, v);
            List<Item> roof = Palettes.ranked(id, Blueprints.Style.ROOF_STAIR);
            Kit.log("wi05 the town roofs in thatch: " + thatching + "; the roof's stairs first choice " + (roof.isEmpty() ? "none" : roof.get(0)));
            helper.assertTrue(thatching, "a fed Wood Age town with wheat to spare roofs in thatch");
            helper.assertTrue(!roof.isEmpty() && roof.get(0) == stairs, "thatch first on the roofs: " + roof);
            // The farmer's thatch, of the wheat.
            int wheat0 = stores(level, id, Items.WHEAT);
            Map<Item, Integer> wanted = WorkTools.wantedForTests(level, id);
            String made = WorkTools.craftForTests(level, farmer);
            int bundles = stores(level, id, thatch), wheat1 = stores(level, id, Items.WHEAT);
            Kit.log("wi05 wanted " + wanted + "; the farmer: " + made + "; thatch " + bundles + "; wheat " + wheat0 + " -> " + wheat1);
            helper.assertTrue(wanted.getOrDefault(thatch, 0) == 24, "two dozen thatch kept while it roofs in it: " + wanted);
            helper.assertTrue(made != null && made.contains("thatch") && bundles == 4 && wheat0 - wheat1 == 6, "six wheat for four thatch: " + made);
            // A builder off to roof a house: stairs, slabs and blocks of thatch into its pack, cut at the bench.
            Thatch.stock(builder, heart, 8, 4, 2, 48);
            int bs = builder.countCarried(s -> s.is(stairs)), bl = builder.countCarried(s -> s.is(slab)), bb = builder.countCarried(s -> s.is(thatch));
            int wheat2 = stores(level, id, Items.WHEAT);
            Kit.log("wi05 the builder carries " + bs + " thatch stairs, " + bl + " slabs, " + bb + " blocks; wheat " + wheat1 + " -> " + wheat2);
            helper.assertTrue(bs == 8 && bl == 4 && bb == 2, "the roof's thatch in the builder's pack");
            helper.assertTrue(wheat2 < wheat1, "cut out of the stores' straw");
            // It burns like hay.
            BlockState ts = WorkItems.THATCH.get().defaultBlockState();
            int flame = ts.getFlammability(level, heart, Direction.UP), spread = ts.getFireSpreadSpeed(level, heart, Direction.UP);
            int hayFlame = Blocks.HAY_BLOCK.defaultBlockState().getFlammability(level, heart, Direction.UP),
                haySpread = Blocks.HAY_BLOCK.defaultBlockState().getFireSpreadSpeed(level, heart, Direction.UP);
            helper.assertTrue(flame == hayFlame && spread == haySpread && spread == 60, "thatch catches like a hay bale: " + flame + ", " + spread
                + " (hay " + hayFlame + ", " + haySpread + ")");
            // A thatched house, and a spark from its chimney.
            BlockPos anchor = Kit.surface(level, heart.getX() - 20, heart.getZ() + 20);
            Showcase.Palette palette = new Showcase.Palette(Blocks.OAK_PLANKS, Blocks.SPRUCE_LOG, WorkItems.THATCH_STAIRS.get(),
                WorkItems.THATCH_SLAB.get(), WorkItems.THATCH.get(), Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE,
                Blocks.SPRUCE_FENCE_GATE, Blocks.RED_BED, Blocks.RED_CARPET);
            BuildGoal.stamp(level, "house", anchor, Direction.NORTH, 13, Showcase.painter(palette));
            Ledger.built(id, "house", anchor, Direction.NORTH);
            int thatched = thatched(level, anchor);
            BlockPos spark = Thatch.sparkNowForTests(level, id);
            BlockState fire = spark == null ? Blocks.AIR.defaultBlockState() : level.getBlockState(spark);
            Kit.log("wi05 a house thatched with " + thatched + " pieces; a chimney's spark at " + spark + ": " + fire + "; report "
                + WorkTools.report(level, v));
            helper.assertTrue(thatched > 20, "the house roofed in thatch: " + thatched);
            helper.assertTrue(spark != null && fire.getBlock() instanceof BaseFireBlock && Thatch.isThatch(level.getBlockState(spark.below())),
                "the spark caught on the thatch: " + fire);
            helper.assertTrue(String.join(" ", WorkTools.report(level, v)).contains("spark from a chimney"), "the town's books have it");
            level.setBlock(spark, Blocks.AIR.defaultBlockState(), 3);
            // The Stone Age: the house re-roofed in tiles, the thatch back into the stores.
            Villages.ageForTests(id, Villages.Age.STONE);
            Villages.Village sv = Villages.get(id);
            stock(helper, level, id, new ItemStack(Items.BRICK_STAIRS, 64), new ItemStack(Items.BRICK_STAIRS, 64), new ItemStack(Items.BRICK_SLAB, 64),
                new ItemStack(Items.BRICKS, 64));
            int stairs0 = stores(level, id, stairs);
            Ledger.Building b = null;
            for (Ledger.Building lb : Ledger.buildings(id)) if (lb.anchor().equals(anchor)) b = lb;
            helper.assertTrue(b != null, "the house on the town's books");
            int laid = 0;
            for (int i = 0; i < 8; i++) {
                int k = Ages.makeOver(level, sv, b, Villages.Age.STONE, 10000, false);
                if (k == 0) break;
                laid += k;
            }
            int still = thatched(level, anchor), tiles = 0;
            for (BlockPos p : BlockPos.betweenClosed(anchor.offset(-9, 0, -9), anchor.offset(9, 14, 9))) {
                if (level.getBlockState(p).is(Blocks.BRICK_STAIRS) || level.getBlockState(p).is(Blocks.BRICK_SLAB)) tiles++;
            }
            int stairs1 = stores(level, id, stairs);
            Kit.log("wi05 the Stone Age: " + laid + " blocks made over; thatch left " + still + ", tiles " + tiles + "; thatch stairs in the stores "
                + stairs0 + " -> " + stairs1);
            helper.assertTrue(still == 0 && tiles > 20, "the roof in tiles now: " + still + " thatch left, " + tiles + " tiles");
            helper.assertTrue(stairs1 > stairs0, "the old thatch into the stores");
            helper.succeed();
        });
    }

    /** The pieces of thatch on the house at this anchor. */
    private static int thatched(ServerLevel level, BlockPos anchor) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(anchor.offset(-9, 0, -9), anchor.offset(9, 14, 9))) {
            if (Thatch.isThatch(level.getBlockState(p))) n++;
        }
        return n;
    }

    // ============================================================ wi06: the milestone

    /**
     * Two towns two hundred and sixty blocks apart, the road between them laid; the Stone Age mother sets its stones, one a
     * round, out of its stores (made at the bench of five cobblestone and a sign each): at both ends and at the hundreds,
     * on the side away from the lamps, faced to the road, each lettered with both towns and how far each is by the road; a
     * player who asks is told; a stone a player sets by the road is lettered for it; and a stone taken away is set again as
     * the road is laid past it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "wi06_milestone")
    public static void wi06_milestone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        slate(level, DAY + 3000);
        int ax = 1250000, bx = 1250260;
        for (int cx = ax - 48; cx <= bx + 48; cx += 48) {
            Kit.hold(level, cx, Z, 40);
            Kit.prepare(level, cx, Z, 40);
        }
        int y = Kit.surface(level, ax, Z).getY();
        flat(level, ax - 30, Z - 14, bx + 30, Z + 14, y);
        BlockPos a = new BlockPos(ax, y, Z), b = new BlockPos(bx, y, Z);
        VillageFolkEntity m1 = VillageFolkSpawnerBlock.raise(level, a, 0.0F);
        VillageFolkEntity c1 = VillageFolkSpawnerBlock.raise(level, b, 0.0F);
        helper.assertTrue(m1 != null && c1 != null && !m1.ownerId().equals(c1.ownerId()), "two towns");
        UUID mother = m1.ownerId(), colony = c1.ownerId();
        Item stone = WorkItems.MILESTONE_ITEM.get();
        helper.runAtTickTime(5, () -> {
            papers(helper, level, stone, Villages.Age.STONE, 0.8, false);
            Ledger.link(mother, colony);
            Villages.ageForTests(mother, Villages.Age.STONE);
            emptyStores(helper, level, mother, a);
            stock(helper, level, mother, join(many(Items.COBBLESTONE, 256), many(Items.COBBLESTONE, 40), goods(new ItemStack(Items.OAK_SIGN, 6),
                new ItemStack(Items.CRAFTING_TABLE))));
            List<Integer> spots = Milestones.spotsForTests(mother, colony);
            Ledger.road(colony, 1000, y, true);
            int cobble0 = stores(level, mother, Items.COBBLESTONE), signs0 = stores(level, mother, Items.OAK_SIGN);
            Villages.Village mv = Villages.get(mother);
            for (int i = 0; i < spots.size() + 1; i++) Milestones.roundsForTests(level, mv);
            int cobble1 = stores(level, mother, Items.COBBLESTONE), signs1 = stores(level, mother, Items.OAK_SIGN);
            String mName = Villages.name(mother), cName = Villages.name(colony);
            List<String> said = new ArrayList<>();
            int set = 0;
            for (int i : spots) {
                BlockPos at = Milestones.placeForTests(level, mother, colony, i);
                helper.assertTrue(at != null, "ground for the stone at step " + i);
                BlockState s = level.getBlockState(at);
                if (!(s.getBlock() instanceof MilestoneBlock)) {
                    Kit.log("wi06 no stone at step " + i + " (" + at.toShortString() + "): " + s);
                    continue;
                }
                set++;
                MilestoneBlockEntity be = (MilestoneBlockEntity) level.getBlockEntity(at);
                List<MilestoneBlockEntity.Way> ways = be.ways();
                Kit.log("wi06 step " + i + " at " + at.toShortString() + " facing " + s.getValue(MilestoneBlock.FACING) + ": " + ways);
                helper.assertTrue(s.getValue(MilestoneBlock.FACING) == Direction.SOUTH && at.getZ() == Z - 2, "beside the road, faced to it");
                helper.assertTrue(ways.size() == 2 && ways.get(0).name().equals(mName) && ways.get(1).name().equals(cName), "both towns on it: " + ways);
                helper.assertTrue(ways.get(0).far() > i && ways.get(1).far() > 0 && Math.abs(ways.get(0).far() + ways.get(1).far() - 260) <= 2,
                    "how far each is by the road: " + ways.get(0).far() + " and " + ways.get(1).far());
                if (said.isEmpty()) said.addAll(Milestones.tell(level, at));
            }
            Kit.log("wi06 the road's stones at " + spots + ": " + set + " set; cobblestone " + cobble0 + " -> " + cobble1 + ", signs " + signs0 + " -> " + signs1
                + "; a player asking is told " + said + "; " + WorkTools.report(level, mv));
            helper.assertTrue(spots.size() == 4 && spots.get(0) == 3 && spots.contains(50) && spots.contains(150), "the ends and the hundreds: " + spots);
            helper.assertTrue(set == 4, "every stone set: " + set);
            helper.assertTrue(cobble0 - cobble1 == 20 && signs0 - signs1 == 4, "five cobblestone and a sign each, out of the stores");
            helper.assertTrue(said.size() == 3 && said.get(1).contains(mName) && said.get(1).contains("blocks by the road")
                && said.get(2).contains(cName), "the way and the distance told: " + said);
            helper.assertTrue(String.join(" ", WorkTools.report(level, mv)).contains("Milestones: 4 set"), "on the town's books");
            // A player's stone beside the road at the hundredth step.
            BlockPos ground = new BlockPos(ax + 41 + 100, y - 1, Z + 4);
            ServerPlayer p = player(helper, level, ground.getX() + 0.5, y, ground.getZ() + 3.5);
            InteractionResult r = useOn(p, level, new ItemStack(stone), ground, Direction.UP);
            BlockState ps = level.getBlockState(ground.above());
            List<MilestoneBlockEntity.Way> pw = level.getBlockEntity(ground.above()) instanceof MilestoneBlockEntity pbe ? pbe.ways() : List.of();
            Kit.log("wi06 the player's stone (" + r + "): " + ps + ", lettered " + pw);
            helper.assertTrue(ps.getBlock() instanceof MilestoneBlock && pw.size() == 2 && pw.get(0).name().equals(mName), "lettered for the road: " + pw);
            Kit.noLeftoverPlayers(level);
            // A stone taken away is set again as the road is laid past its step.
            BlockPos fifty = Milestones.placeForTests(level, mother, colony, 50);
            level.setBlock(fifty, Blocks.AIR.defaultBlockState(), 3);
            boolean again = Milestones.onStep(level, mv, Villages.get(colony), 50);
            helper.assertTrue(again && level.getBlockState(fifty).getBlock() instanceof MilestoneBlock, "set again as the road is laid");
            helper.assertTrue(!Milestones.onStep(level, mv, Villages.get(colony), 51), "and none where no stone goes");
            helper.succeed();
        });
    }

    // ============================================================ wi07: the shipping crate

    /**
     * Stone Age. The woodcutter makes two crates (six planks, two logs and a nail each) for the courier, who draws them; at a
     * chest of twenty-seven stacks it packs eighteen of them into its crates; at the stores the crates are unpacked, every
     * stack in, and kept, empty; a crate broken keeps what is in it, and a player who sets it down finds it all there.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "wi07_crates")
    public static void wi07_crates(GameTestHelper helper) {
        int x = 1252000;
        VillageFolkEntity founder = founder(helper, x, DAY + 3000, 56);
        ServerLevel level = helper.getLevel();
        UUID id = founder.ownerId();
        Villages.ageForTests(id, Villages.Age.STONE);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity cutter = another(helper, heart.south(3), id);
        VillageFolkEntity courier = another(helper, heart.south(5), id);
        Item crate = WorkItems.SHIPPING_CRATE_ITEM.get();
        helper.runAtTickTime(5, () -> {
            founder.setJob(StationTask.FARM);
            cutter.setJob(StationTask.WOOD);
            courier.setJob(StationTask.HAUL);
            clearPack(courier);
            papers(helper, level, crate, Villages.Age.STONE, 1.4, true);
            emptyStores(helper, level, id, heart);
            stock(helper, level, id, join(many(Items.OAK_LOG, 64), many(Items.OAK_PLANKS, 128), goods(new ItemStack(Items.IRON_NUGGET, 4),
                new ItemStack(Items.CRAFTING_TABLE))));
            int logs0 = stores(level, id, Items.OAK_LOG), planks0 = stores(level, id, Items.OAK_PLANKS), nails0 = stores(level, id, Items.IRON_NUGGET);
            Map<Item, Integer> wanted = WorkTools.wantedForTests(level, id);
            String one = WorkTools.craftForTests(level, cutter), two = WorkTools.craftForTests(level, cutter), three = WorkTools.craftForTests(level, cutter);
            int crates = stores(level, id, crate), logs1 = stores(level, id, Items.OAK_LOG), planks1 = stores(level, id, Items.OAK_PLANKS),
                nails1 = stores(level, id, Items.IRON_NUGGET);
            Kit.log("wi07 wanted " + wanted + "; the woodcutter: " + one + " / " + two + " / " + three + "; crates " + crates + "; logs " + logs0 + " -> "
                + logs1 + ", planks " + planks0 + " -> " + planks1 + ", nails " + nails0 + " -> " + nails1);
            helper.assertTrue(wanted.getOrDefault(crate, 0) == 2, "two crates for the courier: " + wanted);
            helper.assertTrue(one != null && one.contains("shipping crate") && two != null && three == null && crates == 2, "two made, and no third");
            helper.assertTrue(logs0 - logs1 == 4 && planks0 - planks1 == 12 && nails0 - nails1 == 2, "six planks, two logs and a nail each");
            helper.assertTrue(WorkTools.kitUp(courier) && courier.countCarried(s -> s.is(crate)) == 2, "the courier drew both");
            // A woodcutter's full chest: the courier fills its pack, then packs the rest into its crates.
            BlockPos at = heart.offset(-20, 0, 20);
            level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
            Container chest = (Container) level.getBlockEntity(at);
            for (int i = 0; i < 27; i++) chest.setItem(i, new ItemStack(i % 3 == 0 ? Items.OAK_LOG : i % 3 == 1 ? Items.BIRCH_LOG : Items.SPRUCE_LOG, 64));
            int packed = Crates.packFrom(courier, at);
            int leftInChest = count(chest, s -> !s.isEmpty());
            String doing = Crates.doingWords(courier);
            Kit.log("wi07 at the chest: " + packed + " packed into the crates, " + leftInChest + " left in the chest; doing \"" + doing + "\"; card: "
                + WorkTools.cardLine(courier));
            helper.assertTrue(packed == 18 * 64 && leftInChest == 9 * 64, "eighteen stacks into two crates: " + packed);
            helper.assertTrue(doing.contains("2 crates packed (18 stacks)"), "its doing line: " + doing);
            helper.assertTrue(WorkTools.keeps(StationTask.HAUL, courier.getInventoryItems().stream().filter(s -> s.is(crate)).findFirst().orElseThrow()) == 0,
                "a packed crate's load is the stores', not the courier's to keep");
            // At the stores: unpacked, the crates kept.
            BlockPos storeAt = heart.offset(8, 0, 6);
            level.setBlock(storeAt, Blocks.CHEST.defaultBlockState(), 3);
            Container store = (Container) level.getBlockEntity(storeAt);
            List<ItemStack> booked = new ArrayList<>();
            int moved = WorkTools.unpackInto(courier, store, booked);
            int[] u = Crates.unpackedForTests(id);
            int emptyCrates = 0;
            for (ItemStack s : courier.getInventoryItems()) if (s.is(crate) && ShippingCrateBlock.contents(s).isEmpty()) emptyCrates++;
            Kit.log("wi07 at the stores: " + moved + " unpacked; crates unpacked " + u[0] + " (" + u[1] + " stacks); empty crates kept " + emptyCrates);
            helper.assertTrue(moved == 18 * 64 && count(store, s -> !s.isEmpty()) == 18 * 64, "every stack into the stores: " + moved);
            helper.assertTrue(u[0] == 2 && u[1] == 18 && emptyCrates == 2, "both crates unpacked and kept, empty");
            // A crate set down, broken, and set down again by a player: its goods go with it.
            BlockPos cAt = Kit.surface(level, heart.getX() - 8, heart.getZ() + 8);
            level.setBlock(cAt, WorkItems.SHIPPING_CRATE.get().defaultBlockState(), 3);
            ShippingCrateBlockEntity be = (ShippingCrateBlockEntity) level.getBlockEntity(cAt);
            for (int i = 0; i < 5; i++) be.setItem(i, new ItemStack(Items.BREAD, 10 + i));
            level.destroyBlock(cAt, true);
            ItemStack picked = ItemStack.EMPTY;
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(cAt).inflate(2.0))) if (e.getItem().is(crate)) picked = e.getItem().copy();
            List<ItemStack> inside = ShippingCrateBlock.contents(picked);
            Kit.log("wi07 the crate broken: " + picked + " holding " + inside);
            helper.assertTrue(!picked.isEmpty() && inside.size() == 5 && inside.get(4).getCount() == 14, "it keeps what is in it: " + inside);
            ServerPlayer p = player(helper, level, cAt.getX() + 2.5, cAt.getY(), cAt.getZ() + 0.5);
            InteractionResult r = useOn(p, level, picked, cAt.below(), Direction.UP);
            int bread = level.getBlockEntity(cAt) instanceof ShippingCrateBlockEntity again ? count(again, s -> s.is(Items.BREAD)) : -1;
            Kit.log("wi07 set down again by a player (" + r + "): " + bread + " bread in it");
            helper.assertTrue(bread == 60, "all there: " + bread);
            Kit.noLeftoverPlayers(level);
            helper.succeed();
        });
    }

    // ============================================================ wi08: the window box

    /**
     * A well-off household's house with bare windows: the town wants a box; the shop's hand makes one of the stores' planks,
     * earth and a cornflower; the household's gardener carries it home and hangs it under a window; the household is the
     * happier and the house worth more; three days dry and it wilts, and the gardener waters it from a bucket of the
     * stores'; a player waters it too; in winter it dies back, and in the spring it is up again.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1500, batch = "wi08_window_box")
    public static void wi08_window_box(GameTestHelper helper) {
        int x = 1254000;
        VillageFolkEntity mother = founder(helper, x, DAY + 3000, 56);
        ServerLevel level = helper.getLevel();
        UUID id = mother.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity father = another(helper, heart.east(2), id);
        VillageFolkEntity shop = another(helper, heart.south(4), id);
        Item boxItem = WorkItems.WINDOW_BOX_ITEM.get();
        Object[] st = new Object[4];
        int[] phase = { 0 };
        helper.runAtTickTime(5, () -> {
            mother.setJob(StationTask.FARM);
            father.setJob(StationTask.FARM);
            shop.setJob(StationTask.SHOP);
            papers(helper, level, boxItem, Villages.Age.WOOD, 0.6, true);
            Villages.Village v = Villages.get(id);
            // The household and its house.
            mother.life().widowed();
            father.life().widowed();
            mother.life().partnerWith(father.getUUID(), father.displayNameCap());
            father.life().partnerWith(mother.getUUID(), mother.displayNameCap());
            BlockPos home = Kit.surface(level, heart.getX() - 16, heart.getZ() + 16);
            BuildGoal.stamp(level, "house", home, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "house", home, Direction.NORTH);
            Villages.recountBeds(id);
            Homes.tickForTests(level, v);
            helper.assertTrue(home.equals(Homes.homeOf(mother)) && home.equals(Homes.homeOf(father)), "the couple's house: " + Homes.homeOf(mother));
            mother.earn(200);
            emptyStores(helper, level, id, heart);
            stock(helper, level, id, new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.DIRT, 4), new ItemStack(Items.CORNFLOWER, 5),
                new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.CRAFTING_TABLE));
            // The town wants a box for the well-off house; the shop's hand makes it.
            WindowBoxes.tendForTests(level, v);
            Map<Item, Integer> wanted = WorkTools.wantedForTests(level, id);
            int planks0 = stores(level, id, Items.OAK_PLANKS), dirt0 = stores(level, id, Items.DIRT), flowers0 = stores(level, id, Items.CORNFLOWER);
            String made = WorkTools.craftForTests(level, shop);
            ItemStack box = ItemStack.EMPTY;
            for (BlockPos p : Villages.storeChests(level, id)) {
                if (level.getBlockEntity(p) instanceof Container c) for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(boxItem)) box = c.getItem(i);
            }
            int planks1 = stores(level, id, Items.OAK_PLANKS), dirt1 = stores(level, id, Items.DIRT), flowers1 = stores(level, id, Items.CORNFLOWER);
            Kit.log("wi08 wanted " + wanted + "; the shop's hand: " + made + "; " + box + " of " + (box.isEmpty() ? "-" : WindowBoxItem.flower(box))
                + "; planks " + planks0 + " -> " + planks1 + ", earth " + dirt0 + " -> " + dirt1 + ", cornflowers " + flowers0 + " -> " + flowers1);
            helper.assertTrue(wanted.getOrDefault(boxItem, 0) == 1, "a box for the well-off house: " + wanted);
            helper.assertTrue(made != null && made.contains("cornflowers") && !box.isEmpty() && WindowBoxItem.flower(box) == WindowBoxBlock.Flower.CORNFLOWER,
                "a box of cornflowers made: " + made);
            helper.assertTrue(planks0 - planks1 == 3 && dirt0 - dirt1 == 1 && flowers0 - flowers1 == 1, "three planks, earth and a flower");
            // Its gardener carries it home.
            boolean sent = WindowBoxes.hangForTests(level, id, home);
            VillageFolkEntity gardener = WindowBoxes.errandForTests(mother) != null ? mother : WindowBoxes.errandForTests(father) != null ? father : null;
            helper.assertTrue(sent && gardener != null && gardener.getMainHandItem().is(boxItem), "the household's gardener off home with the box in its hands");
            Kit.log("wi08 " + gardener.displayNameCap() + " carrying the box home: " + java.util.Arrays.toString(WindowBoxes.errandForTests(gardener)));
            st[0] = home;
            st[1] = gardener;
            st[2] = level.getGameTime();
            st[3] = WindowBoxes.errandForTests(gardener)[1];
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(st[0] != null, "set up");
            BlockPos home = (BlockPos) st[0], at = (BlockPos) st[3];
            VillageFolkEntity g = (VillageFolkEntity) st[1];
            Villages.Village v = Villages.get(id);
            long day = level.getDayTime() / 24000L;
            if (phase[0] == 0) {
                BlockState s = level.getBlockState(at);
                helper.assertTrue(WindowBoxes.errandForTests(g) == null && s.getBlock() instanceof WindowBoxBlock,
                    "the box hung: " + s + " at " + at.toShortString() + "; the gardener at " + g.blockPosition().toShortString());
                int bloom = WindowBoxes.inFlowerForTests(level, home);
                List<Object[]> why = new ArrayList<>();
                int mood = WindowBoxes.mood(mother, day, 0, why);
                double[] worth = WindowBoxes.worthForTests(id, home);
                int[] counts = StreetFurniture.countsForTests(level, v);
                Kit.log("wi08 hung after " + (level.getGameTime() - (Long) st[2]) + " ticks: " + s + "; in flower " + bloom + "; mood +" + mood
                    + "; worth " + worth[0] + " (bare " + worth[1] + "); the town's boxes " + counts[1] + "; " + WindowBoxes.describe(level, at, s));
                helper.assertTrue(s.getValue(WindowBoxBlock.FLOWER) == WindowBoxBlock.Flower.CORNFLOWER && s.getValue(WindowBoxBlock.BLOOM), "cornflowers in flower");
                helper.assertTrue(!g.getMainHandItem().is(boxItem), "out of the gardener's hands");
                helper.assertTrue(bloom == 1 && mood == 3 && why.size() == 1 && "windowbox".equals(why.get(0)[0]), "the household the happier for it");
                helper.assertTrue(worth[0] > worth[1] && Math.abs(worth[0] / worth[1] - 1.04) < 1e-6, "the house worth four in the hundred more");
                helper.assertTrue(counts[1] >= 1, "the town counts it");
                // Dry for five days: it wilts, and the gardener is sent with a bucket.
                WorkSites.water(level, at, day - 5);
                WindowBoxes.tendForTests(level, v);
                Object[] e = WindowBoxes.errandForTests(g);
                Kit.log("wi08 five days dry: " + level.getBlockState(at) + "; the gardener's errand " + java.util.Arrays.toString(e));
                helper.assertTrue(!level.getBlockState(at).getValue(WindowBoxBlock.BLOOM), "wilted");
                helper.assertTrue(e != null && Boolean.FALSE.equals(e[0]) && at.equals(e[1]) && g.getMainHandItem().is(Items.WATER_BUCKET),
                    "the gardener off to water it with the stores' bucket");
                phase[0] = 1;
                st[2] = level.getGameTime();
                helper.fail("on to the watering");
            }
            BlockState s = level.getBlockState(at);
            helper.assertTrue(WindowBoxes.errandForTests(g) == null && s.getValue(WindowBoxBlock.BLOOM), "watered: " + s + "; the gardener at "
                + g.blockPosition().toShortString());
            int buckets = stores(level, id, Items.BUCKET);
            Kit.log("wi08 watered by the gardener after " + (level.getGameTime() - (Long) st[2]) + " ticks: " + s + "; an empty bucket back in the stores "
                + buckets + "; " + WindowBoxes.describe(level, at, s));
            helper.assertTrue(buckets == 1 && WorkSites.wateredOn(level, at) == day, "the bucket back, empty, and watered today");
            // Dry again, and a player waters it.
            WorkSites.water(level, at, day - 5);
            WindowBoxes.tendForTests(level, v);
            helper.assertTrue(!level.getBlockState(at).getValue(WindowBoxBlock.BLOOM) && WindowBoxes.errandForTests(g) == null,
                "wilted again, and no bucket in the stores to send");
            BlockState out = level.getBlockState(at);
            Direction face = out.getValue(WindowBoxBlock.FACING);
            ServerPlayer p = player(helper, level, at.getX() + 0.5 + face.getStepX() * 2, at.getY(), at.getZ() + 0.5 + face.getStepZ() * 2);
            InteractionResult r = useOn(p, level, new ItemStack(Items.WATER_BUCKET), at, face);
            Kit.log("wi08 a player waters it (" + r + "): " + level.getBlockState(at) + "; in hand " + p.getMainHandItem());
            helper.assertTrue(level.getBlockState(at).getValue(WindowBoxBlock.BLOOM) && p.getMainHandItem().is(Items.BUCKET), "the player's water: in flower again");
            Kit.noLeftoverPlayers(level);
            // Winter: it dies back; the spring: watered, it is up again.
            long winter = DAY + 21 * 24000L + 3000;
            level.setDayTime(winter);
            level.updateSkyBrightness();
            WorkSites.water(level, at, winter / 24000L);
            WindowBoxes.tendForTests(level, v);
            BlockState w = level.getBlockState(at);
            String winterWords = WindowBoxes.describe(level, at, w);
            long spring = DAY + 28 * 24000L + 3000;
            level.setDayTime(spring);
            level.updateSkyBrightness();
            WorkSites.water(level, at, spring / 24000L);
            WindowBoxes.tendForTests(level, v);
            BlockState sp = level.getBlockState(at);
            Kit.log("wi08 in winter: " + w + " (\"" + winterWords + "\"); in the spring: " + sp);
            helper.assertTrue(!w.getValue(WindowBoxBlock.BLOOM) && winterWords.contains("winter"), "died back for the winter");
            helper.assertTrue(sp.getValue(WindowBoxBlock.BLOOM), "up again in the spring");
        });
    }

    // ============================================================ wi09: the caravan's crates

    /**
     * A mother town with plenty and three empty crates in its stores sends a caravan to its colony: the carrier takes its
     * loose load and the crates packed with the town's surplus besides; at the colony the crates are unpacked onto its back
     * and the colony buys the lot; home again, the crates go back into the mother's stores.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "wi09_caravan_crates")
    public static void wi09_caravan_crates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        slate(level, DAY + 3000);
        int ax = 1256000, bx = 1256220;
        for (int cx = ax - 48; cx <= bx + 48; cx += 48) {
            Kit.hold(level, cx, Z, 40);
            Kit.prepare(level, cx, Z, 40);
        }
        int y = Kit.surface(level, ax, Z).getY();
        flat(level, ax - 24, Z - 24, ax + 24, Z + 24, y);
        flat(level, bx - 24, Z - 24, bx + 24, Z + 24, Kit.surface(level, bx, Z).getY());
        BlockPos a = Kit.surface(level, ax, Z), b = Kit.surface(level, bx, Z);
        VillageFolkEntity m1 = VillageFolkSpawnerBlock.raise(level, a, 0.0F);
        VillageFolkEntity m2 = VillageFolkSpawnerBlock.raise(level, a.east(2), 0.0F);
        VillageFolkEntity c1 = VillageFolkSpawnerBlock.raise(level, b, 0.0F);
        helper.assertTrue(m1 != null && m2 != null && c1 != null && m1.ownerId().equals(m2.ownerId()) && !m1.ownerId().equals(c1.ownerId()),
            "a town of two and a colony of one");
        UUID mother = m1.ownerId(), colony = c1.ownerId();
        Item crate = WorkItems.SHIPPING_CRATE_ITEM.get();
        helper.runAtTickTime(5, () -> {
            Ledger.link(mother, colony);
            clearPack(m1);
            clearPack(m2);
            emptyStores(helper, level, mother, a);
            emptyStores(helper, level, colony, b);
            ItemStack[] crates = { new ItemStack(crate), new ItemStack(crate), new ItemStack(crate) };
            stock(helper, level, mother, join(many(Items.BREAD, 448), many(Items.COBBLESTONE, 448), many(Items.OAK_LOG, 448), many(Items.OAK_PLANKS, 448),
                many(Items.POTATO, 448), many(Items.COAL, 448), crates));
            Ledger.addCoins(colony, 100000);
            Villages.Village mv = Villages.get(mother), cv = Villages.get(colony);
            boolean out = Caravans.setOut(level, mv, cv);
            VillageFolkEntity carrier = m1.trip() != null ? m1 : m2;
            int packedCrates = 0, crated = 0;
            for (ItemStack s : carrier.getInventoryItems()) {
                if (!s.is(crate)) continue;
                List<ItemStack> in = ShippingCrateBlock.contents(s);
                if (!in.isEmpty()) packedCrates++;
                for (ItemStack i : in) crated += i.getCount();
            }
            int loose = 0;
            for (ItemStack s : carrier.getInventoryItems()) if (!s.isEmpty() && !s.is(crate)) loose += s.getCount();
            int home0 = stores(level, mother, crate);
            Kit.log("wi09 the caravan sets out (" + out + "): " + carrier.displayNameCap() + " with " + loose + " loose and " + packedCrates + " crates packed ("
                + crated + " goods); crates left at home " + home0 + "; says \"" + com.jrpetty.mcassistant.entity.Crates.caravanWords(carrier) + "\"");
            helper.assertTrue(out && carrier.trip() != null, "a caravan sets out");
            helper.assertTrue(packedCrates >= 2 && crated > 64 * 9, "crates of the town's surplus besides its load: " + packedCrates + " crates, " + crated);
            helper.assertTrue(home0 == 3 - packedCrates, "the crates out of the stores");
            Predicate<ItemStack> sent = s -> s.is(Items.BREAD) || s.is(Items.COBBLESTONE) || s.is(Items.OAK_LOG) || s.is(Items.OAK_PLANKS)
                || s.is(Items.POTATO) || s.is(Items.COAL);
            int goods0 = stores(level, colony, sent);
            Caravans.arriveForTests(level, carrier);
            int goods1 = stores(level, colony, sent);
            int stillPacked = 0, onBack = 0;
            for (ItemStack s : carrier.getInventoryItems()) {
                if (s.is(crate)) {
                    onBack++;
                    if (!ShippingCrateBlock.contents(s).isEmpty()) stillPacked++;
                }
            }
            Kit.log("wi09 at the colony: its stores' goods " + goods0 + " -> " + goods1 + "; crates on the carrier's back " + onBack + " (" + stillPacked
                + " still packed)");
            helper.assertTrue(goods1 - goods0 >= crated, "the colony bought the crates' load with the rest: " + (goods1 - goods0) + " of " + (crated + loose));
            helper.assertTrue(onBack == packedCrates, "the crates stay with the carrier, not sold off its back");
            // Home: the crates back into the stores (the home load unpacked out of them first).
            Caravans.arriveForTests(level, carrier);
            int home1 = stores(level, mother, s -> s.is(crate) && ShippingCrateBlock.contents(s).isEmpty());
            int carried = carrier.countCarried(s -> s.is(crate));
            Kit.log("wi09 home again: crates in the stores " + home1 + ", on the carrier " + carried + "; trip " + carrier.trip());
            helper.assertTrue(carrier.trip() == null && home1 == 3 && carried == 0, "every crate home and into the stores, empty");
            helper.succeed();
        });
    }

    // ============================================================ wi10: the cave team's rope

    /**
     * The tailor knots the cave team's coils, and its leader draws two with its kit. With no way down to the cave it is making
     * for (it lies at the foot of a cliff), the leader lets a rope down the cliff and climbs down, and the other follows it
     * down; turned for home, the leader climbs back up and the other after it; and the last one up takes the rope up, the
     * coil back in its pack.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1500, batch = "wi10_cave_rope")
    public static void wi10_cave_rope(GameTestHelper helper) {
        int x = 1258000;
        VillageFolkEntity founder = founder(helper, x, DAY + 3000, 56);
        ServerLevel level = helper.getLevel();
        UUID id = founder.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity tailor = another(helper, heart.south(3), id);
        VillageFolkEntity lead = another(helper, heart.south(5), id);
        VillageFolkEntity mate = another(helper, heart.south(7), id);
        Item coil = WorkItems.ROPE_COIL.get();
        Object[] st = new Object[4];
        int[] phase = { 0 };
        helper.runAtTickTime(5, () -> {
            founder.setJob(StationTask.FARM);
            tailor.setJob(StationTask.TAILOR);
            lead.setJob(StationTask.CAVE);
            mate.setJob(StationTask.CAVE);
            clearPack(lead);
            clearPack(mate);
            emptyStores(helper, level, id, heart);
            stock(helper, level, id, new ItemStack(Items.STRING, 16), new ItemStack(Items.LEATHER, 8), new ItemStack(Items.CRAFTING_TABLE));
            Map<Item, Integer> wanted = WorkTools.wantedForTests(level, id);
            String one = WorkTools.craftForTests(level, tailor), two = WorkTools.craftForTests(level, tailor);
            List<String> kit = WorkTools.caveKitUp(level, Villages.get(id), lead);
            Kit.log("wi10 wanted " + wanted + "; the tailor: " + one + " / " + two + "; the leader's kit " + kit);
            helper.assertTrue(wanted.getOrDefault(coil, 0) == 5, "two coils a cave dweller, and one over: " + wanted);
            helper.assertTrue(one != null && two != null && lead.countCarried(s -> s.is(coil)) == 2, "two coils knotted and in the leader's pack");
            // A cliff eight high; the cave the team is making for at its foot, to the east.
            BlockPos c = Kit.surface(level, heart.getX() + 16, heart.getZ() - 24);
            stone(level, c, 9, 8, 9, (rel, p) -> false);
            BlockPos feet = c.offset(8, 8, 4), target = c.offset(20, 0, 4);
            lead.teleportTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
            mate.teleportTo(feet.getX() - 2 + 0.5, feet.getY(), feet.getZ() + 0.5);
            CaveDwellers.Party party = Ropes.partyForTests(id, level.getDayTime() / 24000L, List.of(lead, mate));
            boolean down = Ropes.lowerToward(level, lead, target, party);
            BlockPos top = Ropes.partyRopeForTests(party);
            Kit.log("wi10 at the cliff's edge: rope down " + down + " at " + top + "; the leader on it " + java.util.Arrays.toString(Ropes.ridingForTests(lead))
                + "; coils left " + lead.countCarried(s -> s.is(coil)));
            helper.assertTrue(down && c.offset(9, 7, 4).equals(top), "the rope let down the cliff's face toward the cave: " + top);
            helper.assertTrue(lead.countCarried(s -> s.is(coil)) == 1, "one coil used");
            st[0] = party;
            st[1] = top;
            st[2] = level.getGameTime();
            st[3] = c;
        });
        helper.onEachTick(() -> {
            if (st[0] == null) return;
            CaveDwellers.Party party = (CaveDwellers.Party) st[0];
            Ropes.follow(level, mate, lead, party);
            if (phase[0] == 1) Ropes.homeward(level, lead, party);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(st[0] != null, "set up");
            CaveDwellers.Party party = (CaveDwellers.Party) st[0];
            BlockPos top = (BlockPos) st[1], c = (BlockPos) st[3];
            if (phase[0] == 0) {
                helper.assertTrue(!Ropes.riding(lead) && !Ropes.riding(mate) && lead.getY() < c.getY() + 1.5 && mate.getY() < c.getY() + 1.5,
                    "both down the rope: the leader at " + lead.blockPosition().toShortString() + ", the other at " + mate.blockPosition().toShortString());
                Kit.log("wi10 both at the cliff's foot after " + (level.getGameTime() - (Long) st[2]) + " ticks; turned for home");
                Ropes.homewardForTests(party);
                phase[0] = 1;
                st[2] = level.getGameTime();
                helper.fail("on to the climb home");
            }
            boolean gone = !(level.getBlockState(top).getBlock() instanceof RopeBlock);
            helper.assertTrue(lead.getY() >= top.getY() + 0.5 && mate.getY() >= top.getY() + 0.5 && gone,
                "both up and the rope taken up: the leader at " + lead.blockPosition().toShortString() + ", the other at " + mate.blockPosition().toShortString()
                    + ", the rope " + (gone ? "up" : "still down"));
            int coils = lead.countCarried(s -> s.is(coil)) + mate.countCarried(s -> s.is(coil));
            Kit.log("wi10 home up the cliff after " + (level.getGameTime() - (Long) st[2]) + " ticks; coils in the team's packs " + coils + "; the party's rope "
                + Ropes.partyRopeForTests(party));
            helper.assertTrue(coils == 2 && mate.countCarried(s -> s.is(coil)) == 1, "the last up has the coil back in its pack");
            helper.assertTrue(Ropes.partyRopeForTests(party) == null, "the party has no rope down any more");
        });
    }
}
