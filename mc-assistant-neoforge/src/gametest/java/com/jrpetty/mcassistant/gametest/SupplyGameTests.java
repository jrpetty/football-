package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Ages;
import com.jrpetty.mcassistant.entity.Crafts;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.Links;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Masonry;
import com.jrpetty.mcassistant.entity.TownJobs;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Where everything the village lays comes from (Masonry). Every block a building is made of has
 * a way to it through the village's own trades: cobblestone the smelter fires to stone and cuts to
 * stone bricks, clay it digs and fires to brick, lanterns the smith makes of spare iron (a torch
 * until then), slate of deep stone, copper at nine ingots a block, moss only of the woodcutters'
 * vines; and never iron on a roof.
 *
 * <p>Like the village tests, each runs on its own ground, far from the others, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class SupplyGameTests {

    private static final String EMPTY = "empty";

    /** A marked store chest beside the heart, filled with these. */
    private static Container stores(ServerLevel level, BlockPos heart, int dx, int dz, ItemStack... goods) {
        BlockPos chest = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, chest);
        Container box = (Container) level.getBlockEntity(chest);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        return box;
    }

    private static int stock(ServerLevel level, UUID village, Item item) {
        return Market.stock(level, village, s -> s.is(item));
    }

    /** The house the ledger has standing at this spot. */
    private static Ledger.Building houseAt(UUID village, BlockPos at) {
        for (Ledger.Building b : Ledger.buildings(village)) {
            if (b.structure().equals("house") && b.anchor().equals(at)) return b;
        }
        throw new IllegalStateException("no house at " + at);
    }

    // ============================================================ the smelter as mason

    /**
     * A smelter given the stores' spare cobblestone and some coal fires it to stone in real furnaces,
     * cuts the stone into stone bricks at its bench, and smooths what it kept back: cobblestone, then
     * stone, then stone bricks and smooth stone, none of it out of nothing.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "s01_smelter_dresses_stone")
    public static void s01_smelter_dresses_stone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        Kit.hold(level, 90000, 12000, 32);
        Kit.prepare(level, 90000, 12000, 32);
        BlockPos heart = Kit.surface(level, 90000, 12000);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null, "a village");
        UUID village = f.ownerId();
        Villages.Village v = Villages.get(village);
        f.setJob(StationTask.SMELT);
        f.awardXp(6000);                                                   // an old hand: three furnaces
        // The founding stores' cobblestone is the builders' working stock; twelve more is the village's to spare.
        stores(level, heart, 3, 1, new ItemStack(Items.COBBLESTONE, 12));
        // A row of furnaces, the village's, and the smelter's ground round them.
        BlockPos forge = Kit.surface(level, heart.getX() - 4, heart.getZ());
        for (int i = -1; i <= 1; i++) {
            BlockPos p = forge.offset(0, 0, i);
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);                // a fresh furnace, nothing left in it
            level.setBlock(p, Blocks.FURNACE.defaultBlockState(), 3);
            ZoneChests.mark(level, p);
        }
        f.assignPlot(WorkZone.around(forge, 4, WorkZone.DEFAULT_DEPTH), "The Forge");
        f.moveTo(forge.getX() + 1.5, forge.getY(), forge.getZ() + 0.5, 0.0F, 0.0F);
        int spare = stock(level, village, Items.COBBLESTONE);
        String took = Links.stone(f, level);
        int cobble = f.countCarried(s -> s.is(Items.COBBLESTONE));
        Kit.log("s01 the smelter: " + took + "; carrying " + cobble + " cobblestone (stores held " + spare + ")");
        helper.assertTrue(took != null && cobble >= 12, "the smelter takes the stores' spare cobblestone to its furnaces");
        helper.assertTrue(stock(level, village, Items.COBBLESTONE) >= Masonry.BUILDERS_STONE,
            "and leaves the builders their working stock");
        f.insertItem(new ItemStack(Items.COAL, 16));
        boolean set = Masonry.work(f, level);
        Job first = f.peekJob();
        Kit.log("s01 the mason's work: " + set + ", queued " + (first == null ? "nothing" : first.type() + " " + first.arg()));
        helper.assertTrue(set && first != null && first.type() == Job.Type.SMELT && "cobble".equals(first.arg()),
            "with stone bricks wanted, it fires the cobblestone");
        Predicate<ItemStack> stone = s -> s.is(Items.STONE);
        final int[] seen = { 0, 0, 0, 0 };                               // most stone, stone bricks, smooth stone seen; cobblestone fed in
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000 > 11000) level.setDayTime(1000);
            if (t % 20 != 0) return;
            int fired = f.countCarried(stone);
            int bricks = f.countCarried(s -> s.is(Items.STONE_BRICKS)) + stock(level, village, Items.STONE_BRICKS);
            int smooth = f.countCarried(s -> s.is(Items.SMOOTH_STONE)) + stock(level, village, Items.SMOOTH_STONE);
            for (int i = -1; i <= 1; i++) {
                if (level.getBlockEntity(forge.offset(0, 0, i)) instanceof AbstractFurnaceBlockEntity fb) {
                    ItemStack out = fb.getItem(2);
                    if (out.is(Items.SMOOTH_STONE)) smooth += out.getCount();
                    if (out.is(Items.STONE)) fired += out.getCount();
                    if (fb.getItem(0).is(Items.COBBLESTONE)) seen[3] = Math.max(seen[3], fb.getItem(0).getCount());
                }
            }
            seen[0] = Math.max(seen[0], fired);
            seen[1] = Math.max(seen[1], bricks);
            seen[2] = Math.max(seen[2], smooth);
            // Between firings, the bench and the next firing (as the station brain does when there is no ore).
            if (f.peekJob() == null) Masonry.work(f, level);
            if (t % 400 == 0) Kit.log("s01 @" + t + ": stone " + fired + ", stone bricks " + bricks + ", smooth " + smooth
                + ", cobble " + f.countCarried(s -> s.is(Items.COBBLESTONE)) + " — smelt " + f.smeltStateForTests() + " — " + f.debugLine());
            if (seen[1] >= 4 && seen[2] >= 1) {
                Kit.log("s01 done at " + t + ": stone fired " + seen[0] + ", stone bricks " + seen[1] + ", smooth stone " + seen[2]);
                helper.assertTrue(seen[1] % 4 == 0, "stone bricks come four for four from stone: " + seen[1]);
                // The rest of the founding party fills the stores as it goes, and the smelter may fetch
                // more of the spare: what shows the stone was fired is cobblestone seen in the furnaces.
                helper.assertTrue(seen[3] > 0, "the cobblestone went into the furnaces");
                helper.succeed();
            } else if (t >= 5800) {
                helper.fail("no stone bricks and smooth stone from the smeltery: stone " + seen[0] + ", bricks " + seen[1]
                    + ", smooth " + seen[2] + " — " + f.debugLine());
            }
        });
    }

    // ============================================================ a lantern the village has not got

    /**
     * A monument's lanterns, laid by a builder that has torches and no lantern: a torch where each
     * lantern was drawn. A lantern is iron, the smith's; nobody makes one out of a torch and a log.
     */
    @GameTest(template = EMPTY, timeoutTicks = 8000, batch = "s02_lantern_is_a_torch")
    public static void s02_lantern_is_a_torch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(1000);
        Kit.hold(level, 91000, 12000, 40);
        Kit.prepare(level, 91000, 12000, 40);
        BlockPos heart = Kit.surface(level, 91000, 12000);
        VillageFolkEntity builder = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(builder != null, "a village");
        Villages.Village v = Villages.get(builder.ownerId());
        BlockPos at = Kit.surface(level, 91000, 12014);
        for (int i = 0; i < 4; i++) builder.insertItem(new ItemStack(Items.COBBLESTONE, 64));
        builder.insertItem(new ItemStack(Items.TORCH, 8));
        helper.assertTrue(builder.countCarried(s -> s.is(Items.LANTERN)) == 0, "no lantern to its name");
        builder.enqueue(Job.buildAt("monument", at, Direction.NORTH, 0));
        java.util.List<BlockPos> lights = new java.util.ArrayList<>();
        for (BuildGoal.Placement p : BuildGoal.plan("monument", at, Direction.NORTH, 0)) {
            if (p.part() == BuildGoal.Part.LANTERN) lights.add(p.pos());
        }
        helper.assertTrue(lights.size() >= 4, "the monument has lanterns drawn: " + lights.size());
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (level.getDayTime() % 24000 > 11000) level.setDayTime(1000);
            if (t % 1200 == 0) Kit.log("s02 @" + t + " built=" + Villages.builtList(v.id()) + " — " + builder.debugLine());
            if (Villages.builtList(v.id()).contains("monument")) {
                int torches = 0, lanterns = 0;
                StringBuilder seen = new StringBuilder();
                for (BlockPos q : lights) {
                    BlockState st = level.getBlockState(q);
                    if (st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH)) torches++;
                    if (st.is(Blocks.LANTERN)) lanterns++;
                    seen.append(BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath()).append(' ');
                }
                Kit.log("s02 the monument stands at " + t + ": its lantern cells hold " + seen);
                helper.assertTrue(lanterns == 0, "no lantern out of nothing: " + lanterns);
                helper.assertTrue(torches >= 4, "a torch where each lantern was drawn: " + torches + " of " + lights.size());
                helper.succeed();
            } else if (t >= 7800) {
                helper.fail("the monument was not built: " + Villages.builtList(v.id()) + " — " + builder.debugLine());
            }
        });
    }

    // ============================================================ never iron on a roof

    /**
     * No roof the village builds has iron in it: not the palettes, not the builder's fallbacks, not
     * the Ages' slate and copper, not a storey chosen out of stores full of iron. Iron is the
     * village's tools; copper and stone go on roofs.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "s03_no_iron_roofs")
    public static void s03_no_iron_roofs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        Kit.hold(level, 92000, 12000, 40);
        Kit.prepare(level, 92000, 12000, 40);
        // The palettes and the builder's choices.
        java.util.List<Showcase.Palette> palettes = new java.util.ArrayList<>(List.of(Showcase.PALETTES));
        for (Villages.Age age : Villages.Age.values()) palettes.add(com.jrpetty.mcassistant.entity.Grow.palette(age));
        for (Showcase.Palette p : palettes) {
            for (Block b : new Block[]{ p.roofStair(), p.roofSlab(), p.roofBlock() }) {
                helper.assertTrue(Masonry.fitForARoof(b), "a palette's roof of " + b);
            }
        }
        for (Block iron : new Block[]{ Blocks.IRON_BLOCK, Blocks.IRON_TRAPDOOR, Blocks.IRON_BARS, Blocks.RAW_IRON_BLOCK }) {
            helper.assertTrue(!Masonry.fitForARoof(iron), iron + " is no roof");
            ItemStack s = new ItemStack(iron.asItem());
            for (Blueprints.Style roof : new Blueprints.Style[]{ Blueprints.Style.ROOF_STAIR, Blueprints.Style.ROOF_SLAB,
                    Blueprints.Style.ROOF_BLOCK, Blueprints.Style.ROOF_STAIR_TOP, Blueprints.Style.ROOF_SLAB_TOP }) {
                helper.assertTrue(!BuildGoal.preferred(roof).test(s) || !BuildGoal.isRoof(roof), "a builder puts no " + iron + " on a roof");
            }
            helper.assertTrue(!BuildGoal.isBuildingBlock(s), iron + " is not building stone");
        }
        // A village rich in iron, through the ages: the tavern's roof is slate, stone or copper, never iron.
        BlockPos heart = Kit.surface(level, 92000, 12000);
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(a != null, "a village");
        UUID village = a.ownerId();
        Villages.Village v = Villages.get(village);
        stores(level, heart, 3, 1,
            new ItemStack(Items.IRON_BLOCK, 64), new ItemStack(Items.IRON_BLOCK, 64), new ItemStack(Items.IRON_INGOT, 64),
            new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.IRON_BARS, 64), new ItemStack(Items.IRON_TRAPDOOR, 64),
            new ItemStack(Items.COBBLED_DEEPSLATE, 24), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.STONE_BRICKS, 64),
            new ItemStack(Items.COPPER_INGOT, 64), new ItemStack(Items.COPPER_INGOT, 64), new ItemStack(Items.COBBLESTONE, 64),
            new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_LOG, 64),
            new ItemStack(Items.TORCH, 16), new ItemStack(Items.GLASS_PANE, 32));
        BlockPos at = Kit.surface(level, heart.getX() + 18, heart.getZ() - 18);
        BuildGoal.stamp(level, "tavern", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, "tavern", at, Direction.NORTH);
        TownJobs.instantForTests(true);
        try {
            for (Villages.Age age : new Villages.Age[]{ Villages.Age.STONE, Villages.Age.IRON, Villages.Age.DIAMOND }) {
                Villages.ageForTests(village, age);
                for (int i = 0; i < 30; i++) Ages.work(level, v, 400);
            }
        } finally {
            TownJobs.instantForTests(false);
        }
        String drawing = com.jrpetty.mcassistant.entity.Grow.tall(village, at) ? "tavern" + Blueprints.TALL : "tavern";
        int roof = 0, iron = 0, copper = 0, slate = 0, stoneRoof = 0;
        for (BuildGoal.Placement p : BuildGoal.plan(drawing, at, Direction.NORTH, 13)) {
            if (p.part() != BuildGoal.Part.BLOCK || !BuildGoal.isRoof(p.style())) continue;
            BlockState st = level.getBlockState(p.pos());
            if (st.isAir()) continue;
            roof++;
            String path = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
            if (path.contains("iron")) iron++;
            if (path.contains("copper")) copper++;
            if (path.contains("deepslate")) slate++;
            if (path.contains("stone_brick")) stoneRoof++;
        }
        // Every block in the building's box, whatever its drawing says.
        int ironAnywhere = 0;
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-10, -2, -10), at.offset(10, 16, 10))) {
            if (BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).getPath().contains("iron")) ironAnywhere++;
        }
        Kit.log("s03 the tavern's roof (" + drawing + "): " + roof + " blocks; iron " + iron + ", copper " + copper + ", slate " + slate
            + ", stone bricks " + stoneRoof + "; iron anywhere in the building " + ironAnywhere
            + "; iron blocks left in the stores " + stock(level, village, Items.IRON_BLOCK));
        helper.assertTrue(roof > 0 && iron == 0 && ironAnywhere == 0, "not a block of iron on the roof");
        helper.assertTrue(copper + slate + stoneRoof > 0, "the roof went up in copper, slate or stone");
        helper.assertTrue(stock(level, village, Items.IRON_BLOCK) == 128, "and the iron is still in the stores: "
            + stock(level, village, Items.IRON_BLOCK));
        // A storey chosen out of those stores, for a palette that asks for iron: the roof is not iron.
        Showcase.Palette greedy = new Showcase.Palette(Blocks.STONE_BRICKS, Blocks.OAK_LOG, Blocks.OAK_STAIRS, Blocks.OAK_SLAB,
            Blocks.IRON_BLOCK, Blocks.OAK_PLANKS, Blocks.OAK_DOOR, Blocks.OAK_FENCE, Blocks.OAK_FENCE_GATE, Blocks.RED_BED, Blocks.RED_CARPET);
        stores(level, heart, -3, 1, new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.IRON_BLOCK, 64));
        java.util.List<BuildGoal.Placement> cells = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            cells.add(new BuildGoal.Placement(at.above(12).east(i), BuildGoal.Part.BLOCK, Blueprints.Style.ROOF_BLOCK, Blueprints.Way.UP));
        }
        Masonry.Look look = Masonry.buildIn(level, v, greedy, cells, false);
        Block top = look == null ? null : look.of(Masonry.Role.ROOF_BLOCK);
        Kit.log("s03 a storey for an iron-roofed palette: its roof blocks would be " + top);
        helper.assertTrue(look != null && top != null && Masonry.fitForARoof(top), "a storey's roof is never iron: " + top);
        helper.succeed();
    }

    // ============================================================ clay to brick

    /**
     * Brick has a maker: with the village short of it, the smelter digs clay off a pond bed (four
     * balls a block), fires it, and cuts four fired bricks to a block of brick; a house is refaced
     * in brick only out of brick the stores hold, and in stone bricks when they have none.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "s04_clay_to_brick")
    public static void s04_clay_to_brick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        Kit.hold(level, 93000, 12000, 40);
        Kit.prepare(level, 93000, 12000, 40);
        BlockPos heart = Kit.surface(level, 93000, 12000);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null, "a village");
        UUID village = f.ownerId();
        Villages.Village v = Villages.get(village);
        f.setJob(StationTask.SMELT);
        // A pond with a clay bed, eight blocks out.
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = 0; dx <= 2; dx++) {
                BlockPos top = Kit.surface(level, heart.getX() + 8 + dx, heart.getZ() + dz);
                level.setBlock(top.below(2), Blocks.CLAY.defaultBlockState(), 3);
                level.setBlock(top.below(), Blocks.WATER.defaultBlockState(), 3);
            }
        }
        String dug = Links.clay(f, level);
        int balls = f.countCarried(s -> s.is(Items.CLAY_BALL));
        Kit.log("s04 the smelter: " + dug + ", carrying " + balls + " clay");
        helper.assertTrue(dug != null && balls >= 4 && balls % 4 == 0, "the smelter digs clay off the pond's bed, four balls a block");
        f.insertItem(new ItemStack(Items.COAL, 4));
        f.clearQueue();
        Masonry.work(f, level);
        Job fire = f.peekJob();
        helper.assertTrue(fire != null && fire.type() == Job.Type.SMELT && "clay".equals(fire.arg()), "and fires it for bricks");
        // Fired (the furnace's own recipe: a clay ball makes a brick), the bricks are cut to blocks of brick.
        f.clearQueue();
        f.removeMatching(s -> s.is(Items.CLAY_BALL), balls);
        f.insertItem(new ItemStack(Items.BRICK, 8));
        Masonry.work(f, level);
        int blocks = f.countCarried(s -> s.is(Items.BRICKS));
        Kit.log("s04 at the bench: " + blocks + " blocks of brick, " + f.countCarried(s -> s.is(Items.BRICK)) + " bricks left");
        helper.assertTrue(blocks == 2 && f.countCarried(s -> s.is(Items.BRICK)) == 0, "four fired bricks to a block of brick");
        // A house refaced in the Iron Age: brick only of the stores' brick, stone bricks without it.
        Villages.ageForTests(village, Villages.Age.IRON);
        BlockPos at = Kit.surface(level, heart.getX() - 16, heart.getZ() - 16);
        BuildGoal.stamp(level, "house", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, "house", at, Direction.NORTH);
        Container box = stores(level, heart, 3, 1, new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.BRICK, 20));
        TownJobs.instantForTests(true);
        int refaced;
        try {
            refaced = com.jrpetty.mcassistant.entity.Grow.reface(level, v, houseAt(village, at), Villages.Age.IRON, 400, false, false);
        } finally {
            TownJobs.instantForTests(false);
        }
        int brick = 0, stone = 0;
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-6, 0, -6), at.offset(6, 6, 6))) {
            if (level.getBlockState(q).is(Blocks.BRICKS)) brick++;
            if (level.getBlockState(q).is(Blocks.STONE_BRICKS)) stone++;
        }
        Kit.log("s04 the house refaced: " + refaced + " blocks; " + brick + " of brick, " + stone + " of stone bricks; bricks left "
            + stock(level, village, Items.BRICK));
        helper.assertTrue(brick == 5 && stock(level, village, Items.BRICK) == 0, "five blocks of brick out of twenty bricks: " + brick);
        helper.assertTrue(brick + stone > 5, "and the rest of the wall in the masons' stone bricks");
        box.clearContent();
        helper.succeed();
    }

    // ============================================================ the real price of things

    /**
     * Cut copper at its real price (nine ingots a block), slate only of deep stone, a lantern only of
     * the smith's nuggets, mossy stone only of the woodcutters' vines: what the stores cannot make a
     * thing of, they cannot pay for it with.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "s05_real_prices")
    public static void s05_real_prices(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        Kit.hold(level, 94000, 12000, 32);
        Kit.prepare(level, 94000, 12000, 32);
        BlockPos heart = Kit.surface(level, 94000, 12000);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null, "a village");
        UUID village = f.ownerId();
        Villages.Village v = Villages.get(village);
        // (The Wood Age: the village is not yet putting iron by for an age, so the smith may spare some.)
        Container box = stores(level, heart, 3, 1, new ItemStack(Items.COPPER_INGOT, 36), new ItemStack(Items.COBBLESTONE, 64),
            new ItemStack(Items.STONE_BRICKS, 8));
        // Copper: thirty-six ingots are four blocks of copper, cut into four of cut copper.
        helper.assertTrue(Masonry.can(level, v, Items.CUT_COPPER, 4) && !Masonry.can(level, v, Items.CUT_COPPER, 5),
            "thirty-six ingots make four of cut copper, and not five");
        helper.assertTrue(Masonry.take(level, v, Blocks.CUT_COPPER), "a block of cut copper paid for");
        Kit.log("s05 a block of cut copper: ingots left " + stock(level, village, Items.COPPER_INGOT) + ", cut copper put by "
            + stock(level, village, Items.CUT_COPPER));
        helper.assertTrue(stock(level, village, Items.COPPER_INGOT) == 0 && stock(level, village, Items.CUT_COPPER) == 3,
            "nine ingots a block: the batch's other three go back into the stores");
        // Slate: no deep stone, no slate, whatever cobblestone the stores hold.
        helper.assertTrue(!Masonry.can(level, v, Blocks.DEEPSLATE_TILES), "no slate of plain cobblestone");
        box.setItem(5, new ItemStack(Items.COBBLED_DEEPSLATE, 4));
        helper.assertTrue(!Masonry.can(level, v, Items.DEEPSLATE_TILE_STAIRS, 1), "slate stairs are six tiles to four: four deep stone is not enough");
        helper.assertTrue(Masonry.take(level, v, Blocks.DEEPSLATE_TILES) && stock(level, village, Items.COBBLED_DEEPSLATE) == 0
            && stock(level, village, Items.DEEPSLATE_TILES) == 3, "four cobbled deepslate dressed to four tiles");
        // Lanterns: a torch and no nuggets is no lantern; the smith makes one of spare iron.
        box.setItem(6, new ItemStack(Items.TORCH, 4));
        helper.assertTrue(!Masonry.can(level, v, Blocks.LANTERN), "no lantern of a torch alone");
        helper.assertTrue(Masonry.light(level, v) == Blocks.TORCH, "a lamp post is lit with a torch");
        box.setItem(7, new ItemStack(Items.IRON_INGOT, 20));
        String smithed = Crafts.ironworkForTests(level, v);
        Kit.log("s05 the smith: " + smithed + "; lanterns " + stock(level, village, Items.LANTERN) + ", nuggets "
            + stock(level, village, Items.IRON_NUGGET) + ", iron " + stock(level, village, Items.IRON_INGOT));
        helper.assertTrue(smithed != null && stock(level, village, Items.LANTERN) == 1 && stock(level, village, Items.IRON_NUGGET) == 1
            && stock(level, village, Items.IRON_INGOT) == 19, "a lantern of an ingot's eight nuggets and a torch");
        box.setItem(7, new ItemStack(Items.IRON_INGOT, 16));
        helper.assertTrue(Crafts.ironworkForTests(level, v) == null && stock(level, village, Items.IRON_INGOT) == 16,
            "and never the last sixteen bars");
        helper.assertTrue(Masonry.light(level, v) == Blocks.LANTERN, "a lamp post is lit with a lantern when there is one");
        // Moss: none without vines; with them, a cobblestone and a vine.
        helper.assertTrue(!Masonry.can(level, v, Blocks.MOSSY_COBBLESTONE), "no mossy cobblestone without a vine");
        box.setItem(9, new ItemStack(Items.VINE, 2));
        helper.assertTrue(Masonry.take(level, v, Blocks.MOSSY_STONE_BRICKS) && stock(level, village, Items.VINE) == 1,
            "mossy stone bricks of stone bricks and a vine");
        // Chiselled stone: two slabs a block, the slabs three stone bricks to six.
        int bricksBefore = stock(level, village, Items.STONE_BRICKS);
        helper.assertTrue(Masonry.take(level, v, Blocks.CHISELED_STONE_BRICKS), "chiselled stone bricks of the masons' slabs");
        helper.assertTrue(stock(level, village, Items.STONE_BRICKS) == bricksBefore - 3 && stock(level, village, Items.STONE_BRICK_SLAB) == 4,
            "three stone bricks cut to six slabs, two used: " + stock(level, village, Items.STONE_BRICK_SLAB));
        helper.succeed();
    }

    // ============================================================ moss is a nicety

    /**
     * Mossy footings in the Diamond Age only where the woodcutters brought vines: without, the
     * footing stays plain cobble and nothing is held up; with, a vine goes into each mossy block.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "s06_moss_is_a_nicety")
    public static void s06_moss_is_a_nicety(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        Kit.hold(level, 95000, 12000, 40);
        Kit.prepare(level, 95000, 12000, 40);
        BlockPos heart = Kit.surface(level, 95000, 12000);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null, "a village");
        UUID village = f.ownerId();
        Villages.Village v = Villages.get(village);
        Villages.ageForTests(village, Villages.Age.DIAMOND);
        BlockPos at = Kit.surface(level, heart.getX() - 16, heart.getZ() + 16);
        BuildGoal.stamp(level, "house", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, "house", at, Direction.NORTH);
        Ledger.Building house = houseAt(village, at);
        Container box = stores(level, heart, 3, 1, new ItemStack(Items.COBBLESTONE, 64));
        java.util.function.IntSupplier mossy = () -> {
            int n = 0;
            for (BlockPos q : BlockPos.betweenClosed(at.offset(-6, -2, -6), at.offset(6, 2, 6))) {
                if (level.getBlockState(q).is(Blocks.MOSSY_COBBLESTONE)) n++;
            }
            return n;
        };
        TownJobs.instantForTests(true);
        int without, with;
        try {
            com.jrpetty.mcassistant.entity.Grow.reface(level, v, house, Villages.Age.DIAMOND, 400, false, false);
            without = mossy.getAsInt();
            box.setItem(1, new ItemStack(Items.VINE, 3));
            com.jrpetty.mcassistant.entity.Grow.reface(level, v, house, Villages.Age.DIAMOND, 400, false, false);
            with = mossy.getAsInt();
        } finally {
            TownJobs.instantForTests(false);
        }
        Kit.log("s06 moss in the footings: without vines " + without + ", with three vines " + with
            + "; vines left " + stock(level, village, Items.VINE));
        helper.assertTrue(without == 0, "no moss out of nothing");
        helper.assertTrue(with > 0 && with <= 3 && stock(level, village, Items.VINE) == 3 - with, "a vine in each mossy block");
        helper.succeed();
    }
}
