package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Flats;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Blocks of flats (entity/Flats): an Iron Age town short of homes plans one, a Stone Age town does not;
 * with the makings in its stores a builder raises it, a load and a storey at a time, from the footing
 * up; the young, the couples starting out and the hard-up take its flats first while a family that can
 * afford it takes the house, and they sleep in their own flats' beds, up the stair; a flat's rent falls
 * due every other day; a household in a flat that saves up the price buys a house once one stands
 * empty, a family moves out to one, and the flats are free again; and in the Diamond Age a full block
 * gets a fourth storey.
 *
 * <p>Each runs on its own ground in x 300000 to 307000 at z 50000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class FlatsGameTests {

    private static final String EMPTY = "empty";

    /** A village's first folk on clean ground at x, z, past the morning's payday, in the Iron Age. */
    private static VillageFolkEntity founder(GameTestHelper helper, int x, int z, long time) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Flats.resetForTests();
        level.setDayTime(time);
        Kit.hold(level, x, z, 72);
        Kit.prepare(level, x, z, 72);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x, z), 0.0F);
        helper.assertTrue(f != null, "a village");
        Villages.ageForTests(f.ownerId(), Villages.Age.IRON);
        return f;
    }

    /** Another folk of the village: in work, come since the founding (so it pays its rent), with so much in its purse. */
    private static VillageFolkEntity folk(GameTestHelper helper, BlockPos at, int coins, Values.Value cares) {
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(helper.getLevel(), at, 0.0F);
        helper.assertTrue(f != null, "another folk");
        settle(f, coins, cares);
        return f;
    }

    private static void settle(VillageFolkEntity f, int coins, Values.Value cares) {
        f.setJob(StationTask.FARM);
        f.rentFree(false);
        f.spend(f.purse());
        f.earn(coins);
        Values.setForTests(f, cares, 100);
    }

    private static void couple(VillageFolkEntity a, VillageFolkEntity b) {
        a.life().partnerWith(b.getUUID(), b.displayNameCap());
        b.life().partnerWith(a.getUUID(), a.displayNameCap());
    }

    /** A block of flats stood on the lot the town would give it, on the register as if its builders had raised it. */
    private static BlockPos standFlats(GameTestHelper helper, UUID id) {
        ServerLevel level = helper.getLevel();
        Villages.Site site = Villages.siteFor(level, id, Flats.BLOCK);
        helper.assertTrue(site != null, "a lot for the flats: " + Villages.lotReport(id));
        BuildGoal.stamp(level, Flats.BLOCK, site.anchor(), site.facing(), 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(id, Flats.BLOCK, site.anchor(), site.facing());
        Villages.rememberBuiltAt(id, Flats.BLOCK, site.anchor());
        return site.anchor();
    }

    /** A house stood, furnished, on the next home lot of the plan with nothing on it or going up on it, on the register. */
    private static BlockPos standHouse(GameTestHelper helper, UUID id) {
        ServerLevel level = helper.getLevel();
        Villages.Village v = Villages.get(id);
        for (TownPlan.Lot lot : TownPlan.candidates("house")) {
            if (lot.kind() != TownPlan.Kind.LOT) continue;
            BlockPos at = Kit.surface(level, v.centre().getX() + lot.x(), v.centre().getZ() + lot.z());
            boolean taken = false;
            for (Ledger.Building b : Ledger.buildings(id)) taken |= near(b.anchor(), at);
            for (Villages.Site s : Villages.sitesOf(id).values()) taken |= near(s.anchor(), at);
            if (taken) continue;
            Direction back = Villages.direction(lot.back());
            BuildGoal.stamp(level, "house", at, back, 13, com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
            Ledger.built(id, "house", at, back);
            return at;
        }
        helper.fail("no lot for a house");
        return null;
    }

    private static boolean near(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) <= 6 && Math.abs(a.getZ() - b.getZ()) <= 6;
    }

    /** The flat this folk lives in, or null. */
    private static BlockPos flatOf(VillageFolkEntity f) {
        BlockPos h = Homes.homeOf(f);
        return h != null && f.ownerId() != null && Flats.numberForTests(f.ownerId(), h) != null ? h : null;
    }

    // ============================================================ planning

    /**
     * An Iron Age town with young folk and hard-up households waiting for a home plans a block of flats,
     * ahead of the next house, on a home lot near the square; the same town in the Stone Age does not.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "fl01_plans")
    public static void fl01_plans(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VillageFolkEntity first = founder(helper, 300400, 50000, 24000L * 8 + 7000);
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = v.centre();
        settle(first, 5, Values.Value.LEISURE);
        List<VillageFolkEntity> young = new ArrayList<>(List.of(first));
        for (int i = 0; i < 4; i++) young.add(folk(helper, heart.east(2 + i), 3, Values.Value.LEISURE));
        Villages.ageForTests(id, Villages.Age.STONE);
        Homes.tickForTests(level, v);
        List<String> stone = Villages.projectsWanted(id);
        Kit.log("fl01 the Stone Age: wanted " + stone + "; " + Homes.line(level, id));
        helper.assertTrue(!stone.contains(Flats.BLOCK), "no flats before the Iron Age: " + stone);
        Villages.ageForTests(id, Villages.Age.IRON);
        Homes.tickForTests(level, v);
        List<String> iron = Villages.projectsWanted(id);
        String why = Villages.whyBuild(id, Flats.BLOCK);
        int near = Flats.freeLotsNearForTests(id);
        Kit.log("fl01 the Iron Age: wanted " + iron + " | why: " + why + " | lots free near the square " + near
            + " | want " + Flats.wantForTests(id));
        helper.assertTrue(iron.contains(Flats.BLOCK), "an Iron Age town short of homes plans a block of flats: " + iron);
        int h = iron.indexOf("house");
        helper.assertTrue(h < 0 || iron.indexOf(Flats.BLOCK) < h, "ahead of the next house: " + iron);
        helper.assertTrue(why.contains("waits for a home") || why.contains("wait for a home"), "and says why: " + why);
        Villages.Site site = Villages.siteFor(level, id, Flats.BLOCK);
        Kit.log("fl01 the flats' lot: " + site + " (" + Villages.lotReport(id) + ")");
        helper.assertTrue(site != null, "a lot for it");
        int dx = site.anchor().getX() - heart.getX(), dz = site.anchor().getZ() - heart.getZ();
        int d = Math.max(Math.abs(dx), Math.abs(dz));
        helper.assertTrue(d <= com.jrpetty.mcassistant.village.TownPlan.RING + 2 * com.jrpetty.mcassistant.village.TownPlan.PERIOD,
            "near the square, on the homes' streets: " + dx + ", " + dz);
        helper.assertTrue("home".equals(com.jrpetty.mcassistant.village.TownPlan.placeFor(Flats.BLOCK)),
            "in the homes' part of the plan");
        // Housed in one, the waiting is over and no second block is planned.
        helper.succeed();
    }

    // ============================================================ building it

    /**
     * Raised for real, out of the stores: the builder loads up from the store chests and lays the block
     * bottom-up, a load at a time, each storey's floor and walls in before the next storey's begin; when
     * it stands, its six flats are on the homes' books with their beds, and the town's hands hang the
     * ground floor's doors, nail up the flat numbers and put the iron railings up round the balconies.
     */
    @GameTest(template = EMPTY, timeoutTicks = 40000, batch = "fl02_build")
    public static void fl02_build(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VillageFolkEntity builder = founder(helper, 301600, 50000, 1000L);
        UUID id = builder.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = v.centre();
        // The stores: chests at the heart, with what a block of flats is made of.
        for (int i = 0; i < 4; i++) {
            BlockPos c = Kit.surface(level, heart.getX() - 4 + 2 * i, heart.getZ() - 4);
            level.setBlock(c, Blocks.CHEST.defaultBlockState(), 3);
        }
        Villages.forgetStores(id);
        ItemStack[] makings = {
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64),
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64),
            new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64),
            new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.BRICKS, 64), new ItemStack(Items.BRICKS, 64),
            new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64),
            new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64),
            new ItemStack(Items.OAK_LOG, 32), new ItemStack(Items.GLASS_PANE, 64), new ItemStack(Items.GLASS_PANE, 16),
            new ItemStack(Items.LANTERN, 12), new ItemStack(Items.OAK_DOOR, 8),
            new ItemStack(Items.CHEST, 6), new ItemStack(Items.OAK_FENCE, 12), new ItemStack(Items.RED_CARPET, 8),
            new ItemStack(Items.OAK_STAIRS, 64), new ItemStack(Items.OAK_STAIRS, 64), new ItemStack(Items.OAK_STAIRS, 24),
            new ItemStack(Items.OAK_SLAB, 32), new ItemStack(Items.DIRT, 8), new ItemStack(Items.POPPY, 6),
            new ItemStack(Items.IRON_BARS, 16), new ItemStack(Items.OAK_SIGN, 8) };
        for (ItemStack s : makings) Homes.storeForTests(level, v, s);
        for (int i = 0; i < 9; i++) Homes.storeForTests(level, v, new ItemStack(Items.WHITE_BED));     // a bed doesn't stack
        Villages.Site site = Villages.siteFor(level, id, Flats.BLOCK);
        helper.assertTrue(site != null, "a lot for the flats: " + Villages.lotReport(id));
        Kit.log("fl02 the flats go up at " + site + "; the builder sees " + builder.buildersSeeForTests(BuildGoal::isBuildingBlock)
            + " building blocks in the stores, wants " + builder.blocksToLayForTests(Flats.BLOCK, site));
        Ledger.Building block = new Ledger.Building(Flats.BLOCK, site.anchor(), site.facing());
        // The drawing's own cells, storey by storey (each storey's floor and its three courses of room).
        List<List<BlockPos>> storeys = new ArrayList<>();
        for (int s = 0; s < 3; s++) storeys.add(new ArrayList<>());
        for (BuildGoal.Placement p : BuildGoal.plan(Flats.BLOCK, site.anchor(), site.facing(), 13)) {
            if (p.part() != BuildGoal.Part.BLOCK) continue;
            int h = p.pos().getY() - site.anchor().getY();
            int s = Math.floorDiv(h + 1, 4);
            if (s >= 0 && s < 3) storeys.get(s).add(p.pos());
        }
        Villages.noteAttempt(id, level.getGameTime());                   // its own projects wait: this is the one it builds
        long[] begun = { -1, -1, -1 };
        double[] belowWhenBegun = { 1, 1, 1 };
        int[] loads = { 0 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000 > 11000) level.setDayTime(1000);      // building is day work
            if (t % 300 == 0) Villages.noteAttempt(id, level.getGameTime());      // its own projects wait: this is the one it builds
            double[] done = new double[3];
            for (int s = 0; s < 3; s++) {
                int n = 0, in = 0;
                for (BlockPos p : storeys.get(s)) {
                    n++;
                    if (!level.getBlockState(p).isAir()) in++;
                }
                done[s] = n == 0 ? 0 : in / (double) n;
                if (s > 0 && begun[s] < 0 && done[s] > 0.02) {
                    begun[s] = t;
                    belowWhenBegun[s] = done[s - 1];
                    Kit.log("fl02 @" + t + " storey " + (s + 1) + " begun with the storey below " + Math.round(done[s - 1] * 100) + "% laid");
                }
            }
            if (t % 600 == 0) {
                Kit.log("fl02 @" + t + " storeys " + Math.round(done[0] * 100) + "% / " + Math.round(done[1] * 100) + "% / "
                    + Math.round(done[2] * 100) + "%, loads " + loads[0] + " — " + builder.debugLine());
            }
            boolean up = Villages.builtList(id).contains(Flats.BLOCK);
            if (!up && builder.peekJob() == null && t % 40 == 0) {
                // Another load from the stores, as its lead would take one, and back to it.
                if (builder.stockedForTests(Flats.BLOCK, site)) {
                    loads[0]++;
                    builder.enqueue(Job.buildAt(Flats.BLOCK, site.anchor(), site.facing(), site.radius()));
                }
            }
            if (!up) {
                if (t >= 39000) helper.fail("the flats not up in 39000 ticks: " + Math.round(done[0] * 100) + "/" + Math.round(done[1] * 100)
                    + "/" + Math.round(done[2] * 100) + "% — " + builder.debugLine());
                return;
            }
            Kit.log("fl02 the block stands at tick " + t + " after " + loads[0] + " loads: storeys " + Math.round(done[0] * 100) + "% / "
                + Math.round(done[1] * 100) + "% / " + Math.round(done[2] * 100) + "%");
            for (int s = 1; s < 3; s++) {
                helper.assertTrue(begun[s] > 0 && belowWhenBegun[s] >= 0.85,
                    "storey by storey: storey " + (s + 1) + " begun with the one below " + Math.round(belowWhenBegun[s] * 100) + "% laid");
            }
            for (int s = 0; s < 3; s++) helper.assertTrue(done[s] >= 0.95, "storey " + (s + 1) + " laid: " + Math.round(done[s] * 100) + "%");
            // The stair: steps up the back of the hall, to every floor.
            int steps = 0;
            for (int s = 0; s < 2; s++) {
                for (int[] c : new int[][]{ { 3, 0, 3 }, { 4, 1, 3 }, { 4, 2, 2 }, { 3, 3, 2 } }) {
                    BlockState st = level.getBlockState(cell(block, c[0], 4 * s + c[1], c[2]));
                    if (st.getBlock() instanceof StairBlock || st.isSolid()) steps++;
                }
            }
            helper.assertTrue(steps == 8, "the stair winds up to both floors above: " + steps + " of 8 steps");
            // On the books: six flats. The town's hands see to it: the beds the builder could not carry
            // (a bed is a pack's slot each), the ground floor's doors, the numbers, the balconies' railings.
            Homes.tickForTests(level, v);
            List<BlockPos> flats = Flats.flatsForTests(id);
            int laid = 0;
            for (BlockPos f : flats) laid += Homes.bedsForTests(level, id, f).size();
            Flats.fitForTests(level, v);
            Homes.tickForTests(level, v);
            int beds = 0;
            for (BlockPos f : flats) beds += Homes.bedsForTests(level, id, f).size();
            Kit.log("fl02 " + flats.size() + " flats, " + laid + " beds laid by the builder, " + beds + " made up after; " + Flats.list(level, heart));
            helper.assertTrue(flats.size() == 6, "six flats: " + flats.size());
            helper.assertTrue(beds >= 6, "a bed in every flat at least: " + beds);
            int doors = 0;
            for (int dz : new int[]{ -2, 1 }) if (level.getBlockState(cell(block, 2, 0, dz)).getBlock() instanceof DoorBlock) doors++;
            int signs = 0;
            for (int s = 0; s < 3; s++) {
                for (int dz : new int[]{ -3, 0 }) if (level.getBlockState(cell(block, 3, 4 * s + 1, dz)).getBlock() instanceof WallSignBlock) signs++;
            }
            int bars = 0;
            for (int s = 1; s < 3; s++) {
                for (int dx = -4; dx <= 1; dx++) if (level.getBlockState(cell(block, dx, 4 * s, -5)).is(Blocks.IRON_BARS)) bars++;
            }
            CompoundTag page = Flats.annalsForTests(id, site.anchor());
            Kit.log("fl02 fitted: " + doors + " ground-floor doors, " + signs + " flat numbers, " + bars + " lengths of railing; the Buildings page: " + page
                + "; news " + Villages.news(id));
            helper.assertTrue(doors == 2, "the ground floor's doors hung: " + doors);
            helper.assertTrue(signs == 6, "every flat's number up on its landing: " + signs);
            helper.assertTrue(bars >= 6, "iron railings round the balconies: " + bars);
            helper.assertTrue(page.getInt("storeys") == 3 && page.getString("tenure").matches("\\d of 6 flats let, \\d free"),
                "the Buildings page shows the block and its flats let and free: " + page);
            // Its builder had nowhere of its own: it lives in one now.
            BlockPos home = Homes.homeOf(builder);
            helper.assertTrue(home != null && Flats.numberForTests(id, home) != null, "its builder, a single folk, took a flat: " + home);
            boolean chronicle = false;
            for (Villages.News n : Villages.news(id)) chronicle |= n.text().contains("first block of flats");
            helper.assertTrue(chronicle, "the chronicle notes the town's first block of flats");
            helper.succeed();
        });
    }

    private static BlockPos cell(Ledger.Building b, int dx, int h, int dz) {
        return b.anchor().relative(b.facing().getClockWise(), dx).relative(b.facing(), dz).above(h);
    }

    // ============================================================ who lives there

    /**
     * The flats are for the young and the hard-up: a single folk, a couple with no children yet and a
     * family that cannot afford a house take flats; a family that can takes the house. The single folk
     * says where it lives; the books' Homes page lists the flat tenants; a flat's rent falls due every
     * other day, a house's every day. That night each sleeps in its own flat's bed, up the stair for
     * the ones above the ground floor, and the hard-up family's child in a bed bought for it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 9000, batch = "fl03_tenants")
    public static void fl03_tenants(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long morning = 24000L * 8 + 7000;
        VillageFolkEntity wren = founder(helper, 303000, 50000, morning);
        UUID id = wren.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = v.centre();
        settle(wren, 9, Values.Value.LEISURE);
        BlockPos block = standFlats(helper, id);
        BlockPos house = standHouse(helper, id);
        VillageFolkEntity ash = folk(helper, heart.east(2), 10, Values.Value.LEISURE);
        VillageFolkEntity bea = folk(helper, heart.east(3), 10, Values.Value.LEISURE);
        couple(ash, bea);
        VillageFolkEntity cole = folk(helper, heart.west(2), 0, Values.Value.LEISURE);
        VillageFolkEntity dee = folk(helper, heart.west(3), 0, Values.Value.LEISURE);
        cole.insertItem(new ItemStack(Items.BREAD, 4));
        dee.insertItem(new ItemStack(Items.BREAD, 4));
        VillageFolkEntity coles = cole.raiseChildWith(dee);
        VillageFolkEntity eli = folk(helper, heart.north(2), 60, Values.Value.LEISURE);
        VillageFolkEntity fay = folk(helper, heart.north(3), 60, Values.Value.LEISURE);
        eli.insertItem(new ItemStack(Items.BREAD, 4));
        fay.insertItem(new ItemStack(Items.BREAD, 4));
        VillageFolkEntity elis = eli.raiseChildWith(fay);
        helper.assertTrue(coles != null && elis != null, "two families with a child each");
        // Hard up: a few coins each, a child's bed's worth, not a week's rent over what two live on.
        for (VillageFolkEntity f : List.of(cole, dee)) { f.spend(f.purse()); f.earn(8); }
        // Beds for the children, in the stores, set up at once (no shop to fetch them from).
        Homes.instantBedsForTests(true);
        for (int i = 0; i < 4; i++) Homes.storeForTests(level, v, new ItemStack(Items.WHITE_BED));
        Homes.tickForTests(level, v);
        Homes.tickForTests(level, v);
        Homes.instantBedsForTests(false);
        BlockPos wrens = flatOf(wren), ashs = flatOf(ash), coless = flatOf(cole);
        Kit.log("fl03 housed: " + wren.displayNameCap() + " " + (wrens == null ? Homes.homeOf(wren) : "flat " + Flats.numberForTests(id, wrens))
            + "; " + ash.displayNameCap() + " & " + bea.displayNameCap() + " " + (ashs == null ? Homes.homeOf(ash) : "flat " + Flats.numberForTests(id, ashs))
            + "; " + cole.displayNameCap() + "'s family " + (coless == null ? Homes.homeOf(cole) : "flat " + Flats.numberForTests(id, coless))
            + "; " + eli.displayNameCap() + "'s family " + Homes.homeOf(eli) + " (the house " + house + ") | " + Homes.line(level, id));
        helper.assertTrue(wrens != null, "a single folk takes a flat: " + Homes.homeOf(wren));
        helper.assertTrue(ashs != null && ashs.equals(flatOf(bea)), "a couple starting out shares a flat");
        helper.assertTrue(coless != null && coless.equals(flatOf(dee)) && coless.equals(Homes.homeOf(coles)),
            "a family that can't afford a house takes a flat, the child with them");
        helper.assertTrue(house.equals(Homes.homeOf(eli)) && house.equals(Homes.homeOf(fay)) && house.equals(Homes.homeOf(elis)),
            "a family that can afford one takes the house: " + Homes.homeOf(eli));
        helper.assertTrue(Homes.bedsForTests(level, id, wrens).size() == 1, "the single folk's flat has the one bed");
        helper.assertTrue(Homes.bedsForTests(level, id, ashs).size() == 2, "the couple's has two");
        helper.assertTrue(Homes.bedsForTests(level, id, coless).size() >= 3, "and the family's a bed bought for the child: "
            + Homes.bedsForTests(level, id, coless).size() + " (" + Homes.whyForTests(coless) + ")");
        // What it says, and the books.
        String says = Homes.talk(wren);
        String name = Flats.nameForTests(id, block);
        Kit.log("fl03 " + wren.displayNameCap() + " says: " + says);
        helper.assertTrue(says.startsWith("I live in flat " + Flats.numberForTests(id, wrens) + ", " + name), "it says where: " + says);
        ListTag rows = Homes.report(level, id).getList("rows", Tag.TAG_COMPOUND);
        int flatRows = 0;
        for (int i = 0; i < rows.size(); i++) if ("flat".equals(rows.getCompound(i).getString("kind"))) flatRows++;
        helper.assertTrue(flatRows == 3, "the Homes page lists the three households in flats: " + flatRows);
        CompoundTag page = Flats.annalsForTests(id, block);
        helper.assertTrue(page.getString("tenure").startsWith("3 of 6 flats let"), "the Buildings page: " + page);
        // The rent: a flat's every other day, a house's every day.
        int wrenPurse = wren.purse(), eliPurse = eli.purse() + fay.purse();
        int[] flatPaid = new int[2], housePaid = new int[2];
        for (int d = 0; d < 2; d++) {
            level.setDayTime(morning + 24000L * (d + 1));
            int w0 = wren.purse(), e0 = eli.purse() + fay.purse();
            Homes.paydayForTests(level, v);
            flatPaid[d] = w0 - wren.purse();
            housePaid[d] = e0 - eli.purse() - fay.purse();
        }
        int houseRent = Homes.rentForTests(id, "house");
        Kit.log("fl03 rent over two paydays: the flat " + flatPaid[0] + " then " + flatPaid[1] + ", the house " + housePaid[0] + " then "
            + housePaid[1] + " (a house's rent " + houseRent + ") | " + Homes.talk(wren));
        helper.assertTrue(flatPaid[0] + flatPaid[1] == houseRent && (flatPaid[0] == 0 || flatPaid[1] == 0),
            "a flat's rent falls due every other day: " + flatPaid[0] + ", " + flatPaid[1]);
        helper.assertTrue(housePaid[0] == houseRent && housePaid[1] == houseRent, "a house's every day: " + housePaid[0] + ", " + housePaid[1]);
        helper.assertTrue(wren.purse() == wrenPurse - houseRent && eli.purse() + fay.purse() == eliPurse - 2 * houseRent, "out of their purses");
        // Night: to bed, each in its own flat, up the stair.
        long night = morning + 24000L * 3 - 7000 + 17000;
        level.setDayTime(night);
        List<VillageFolkEntity> tenants = List.of(wren, ash, bea, cole, dee, coles);
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000 > 22000) level.setDayTime(night);       // a long night
            if (t % 20 == 0) {                                                      // and a quiet one: no raid bell to wake them
                for (net.minecraft.world.entity.monster.Monster m : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
                        new net.minecraft.world.phys.AABB(heart).inflate(96))) m.discard();
            }
            int asleep = 0;
            StringBuilder sb = new StringBuilder();
            for (VillageFolkEntity f : tenants) {
                BlockPos home = Homes.homeOf(f), bed = f.bedPos();
                boolean ok = f.isSleeping() && bed != null && home != null && Flats.insideForTests(id, home, bed);
                if (ok) asleep++;
                sb.append(f.displayNameCap()).append(ok ? " asleep" : f.isSleeping() ? " asleep elsewhere" : " up")
                    .append(" (bed ").append(bed == null ? "none" : bed.toShortString()).append(", at ").append(f.blockPosition().toShortString()).append("); ");
            }
            if (t % 200 == 0) Kit.log("fl03 @" + t + " " + asleep + " of " + tenants.size() + " asleep in their flats: " + sb);
            if (asleep == tenants.size()) {
                Kit.log("fl03 every tenant asleep in its own flat at tick " + t + ": " + sb);
                helper.succeed();
            } else if (t >= 8800) {
                helper.fail("not every tenant asleep in its flat: " + sb);
            }
        });
    }

    // ============================================================ moving out

    /**
     * A couple in a flat who want a house of their own save on payday toward a house's price (never
     * the flat's: flats are not sold); with no house empty, the builders are told one is wanted; once
     * one stands, they buy it outright and move out, and a family with a child that can afford a
     * house's rent moves out to another as its tenants. Both flats are free again.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "fl04_move_out")
    public static void fl04_move_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long morning = 24000L * 8 + 7000;
        VillageFolkEntity ash = founder(helper, 304400, 50000, morning);
        UUID id = ash.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = v.centre();
        settle(ash, 200, Values.Value.HOMES);
        VillageFolkEntity bea = folk(helper, heart.east(2), 200, Values.Value.HOMES);
        couple(ash, bea);
        VillageFolkEntity cole = folk(helper, heart.west(2), 0, Values.Value.LEISURE);
        VillageFolkEntity dee = folk(helper, heart.west(3), 0, Values.Value.LEISURE);
        cole.insertItem(new ItemStack(Items.BREAD, 4));
        dee.insertItem(new ItemStack(Items.BREAD, 4));
        VillageFolkEntity kid = cole.raiseChildWith(dee);
        helper.assertTrue(kid != null, "a family");
        cole.spend(cole.purse());
        dee.spend(dee.purse());
        cole.earn(60);
        dee.earn(60);
        BlockPos block = standFlats(helper, id);
        Homes.tickForTests(level, v);
        BlockPos ashFlat = flatOf(ash), coleFlat = flatOf(cole);
        Kit.log("fl04 in flats: " + ash.displayNameCap() + " & " + bea.displayNameCap() + " " + ashFlat + ", " + cole.displayNameCap()
            + "'s family " + coleFlat + " (no house stands) | " + Homes.line(level, id));
        helper.assertTrue(ashFlat != null && ashFlat.equals(flatOf(bea)), "the couple take a flat");
        helper.assertTrue(coleFlat != null && coleFlat.equals(Homes.homeOf(kid)), "with no house, the family takes a flat too");
        // Payday: the couple put by toward a house's price, not the flat's.
        level.setDayTime(morning + 24000L);
        Homes.paydayForTests(level, v);
        int[] terms = Homes.termsForTests(id, ashFlat);
        int housePrice = Homes.priceForTests(id, "house");
        Kit.log("fl04 payday: " + ash.displayNameCap() + "'s flat " + java.util.Arrays.toString(terms) + " (a house costs " + housePrice + ") | "
            + Homes.talk(ash));
        helper.assertTrue("RENTED".equals(Homes.tenureForTests(id, ashFlat)), "a flat is never bought");
        helper.assertTrue(terms[2] >= housePrice && terms[3] == housePrice, "they put by a house's price: " + terms[2] + " of " + terms[3]);
        helper.assertTrue(Homes.talk(ash).contains("saving for a house of our own"), "and say so: " + Homes.talk(ash));
        Homes.tickForTests(level, v);
        helper.assertTrue(ashFlat.equals(flatOf(ash)), "with no house empty, they stay put");
        helper.assertTrue(Villages.projectsWanted(id).contains("house"), "and the builders are asked for a house: " + Villages.projectsWanted(id));
        // Two houses go up.
        int treasury = Ledger.coins(id);
        BlockPos one = standHouse(helper, id), two = standHouse(helper, id);
        Homes.tickForTests(level, v);
        BlockPos ashNow = Homes.homeOf(ash), coleNow = Homes.homeOf(cole);
        Kit.log("fl04 houses up at " + one + ", " + two + ": " + ash.displayNameCap() + " now " + ashNow + " (" + (ashNow == null ? null
            : Homes.tenureForTests(id, ashNow)) + "), " + cole.displayNameCap() + " now " + coleNow + " (" + (coleNow == null ? null
            : Homes.tenureForTests(id, coleNow)) + "); treasury " + treasury + " -> " + Ledger.coins(id) + " | news " + Villages.news(id));
        helper.assertTrue(ashNow != null && (ashNow.equals(one) || ashNow.equals(two)) && ashNow.equals(Homes.homeOf(bea)),
            "the couple move out to a house: " + ashNow);
        helper.assertTrue("OWNED".equals(Homes.tenureForTests(id, ashNow)), "bought with what they put by");
        helper.assertTrue(Ledger.coins(id) >= treasury + housePrice, "its price into the treasury: " + treasury + " -> " + Ledger.coins(id));
        helper.assertTrue(coleNow != null && !coleNow.equals(ashNow) && (coleNow.equals(one) || coleNow.equals(two))
            && coleNow.equals(Homes.homeOf(kid)), "the family moves out to the other house: " + coleNow);
        helper.assertTrue("RENTED".equals(Homes.tenureForTests(id, coleNow)), "as its tenants");
        helper.assertTrue(Homes.membersForTests(id, ashFlat).isEmpty() && Homes.membersForTests(id, coleFlat).isEmpty(),
            "both flats are free again");
        helper.assertTrue(Flats.annalsForTests(id, block).getString("tenure").startsWith("0 of 6"), "all six to let");
        helper.succeed();
    }

    // ============================================================ a fourth storey

    /**
     * In the Diamond Age a block gets a fourth storey: two more flats on the books, twelve beds where
     * there were nine, and the stair carried up to it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "fl05_fourth_storey")
    public static void fl05_fourth_storey(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        VillageFolkEntity first = founder(helper, 305800, 50000, 24000L * 8 + 7000);
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.DIAMOND);
        BlockPos block = standFlats(helper, id);
        Homes.tickForTests(level, v);
        int before = Flats.flatsForTests(id).size(), bedsBefore = Flats.bedsPlannedForTests(id);
        Flats.raiseForTests(level, v, block);
        Homes.tickForTests(level, v);
        Ledger.Building b = null;
        for (Ledger.Building o : Ledger.buildings(id)) if (o.anchor().equals(block)) b = o;
        helper.assertTrue(b != null, "the block on the register");
        int steps = 0;
        for (int[] c : new int[][]{ { 3, 8, 3 }, { 4, 9, 3 }, { 4, 10, 2 }, { 3, 11, 2 } }) {
            if (level.getBlockState(cell(b, c[0], c[1], c[2])).getBlock() instanceof StairBlock) steps++;
        }
        boolean roof = !level.getBlockState(cell(b, 0, 15, 0)).isAir();
        int after = Flats.flatsForTests(id).size(), bedsAfter = Flats.bedsPlannedForTests(id);
        Kit.log("fl05 a fourth storey: " + before + " -> " + after + " flats, beds " + bedsBefore + " -> " + bedsAfter + ", " + steps
            + " new steps, roof " + roof + " | " + Flats.annalsForTests(id, block) + " | news " + Villages.news(id));
        helper.assertTrue(before == 6 && after == 8, "two more flats: " + before + " -> " + after);
        helper.assertTrue(bedsBefore == 9 && bedsAfter == 12, "twelve beds' room: " + bedsBefore + " -> " + bedsAfter);
        helper.assertTrue(steps == 4, "the stair carried up a storey: " + steps + " of 4 steps");
        helper.assertTrue(roof, "and the roof put back on top");
        helper.assertTrue(Flats.annalsForTests(id, block).getInt("storeys") == 4, "four storeys on the Buildings page");
        helper.succeed();
    }
}
