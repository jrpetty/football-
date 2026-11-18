package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Job;
import com.jrpetty.mcassistant.entity.MineDemand;
import com.jrpetty.mcassistant.entity.MineGround;
import com.jrpetty.mcassistant.entity.MineWorks;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.TownDigs;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.goal.WorksGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [mine8] The town's mine as a mine (MineWorks): the main shaft and the pithead, the levels shared out by what the town is
 * short of, a drift driven straight with its branches and turns, two miners on one level, water and lava stopped, the work
 * kept inside the mine's ground, and the whole kept over a save and a load. The rock is laid by the test (the test world is
 * flat turf over bedrock): a pillar round the shaft and a bed of rock at the level worked. x band 3,800,000 to 3,900,000,
 * z 90000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class MineWorksGameTests {

    private static final int Z = 90000;
    /** The shaft's first landing: high enough for all five levels to be planned. */
    private static final int TOP = 100;

    record Rig(ServerLevel level, List<VillageFolkEntity> miners, UUID v, BlockPos heart, int sx, int sz) {}

    /** A town of miners on the flat; its mine's site set out past its ground, the ground set aside, the works laid out. */
    static Rig rig(GameTestHelper helper, int x, int n) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> miners = MineSiteGameTests.town(level, x, 190, n, StationTask.MINE);
        helper.assertTrue(miners.size() == n, "the miners");
        UUID v = miners.get(0).ownerId();
        BlockPos heart = miners.get(0).villageCentre();
        MineWorks.forgetForTests(v);
        int sx = heart.getX() + 136, sz = heart.getZ();
        Ledger.note(v, "mine.site", sx + "," + sz);
        MineGround.reserve(level, v, heart, new BlockPos(sx, 0, sz), false);
        MineWorks.layOutForTests(level, v, TOP);
        level.setDayTime(1000);
        WorksGoal.resetCountsForTests();
        MineWorks.JOINS = 0;
        return new Rig(level, miners, v, heart, sx, sz);
    }

    static void stone(ServerLevel level, int x0, int y0, int z0, int x1, int y1, int z1) {
        BlockState st = Blocks.STONE.defaultBlockState();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) level.setBlock(p.set(x, y, z), st, 2);
    }

    /** The pillar of rock the shaft is cut in, from here up to the top. */
    static void tower(Rig r, int bottom) {
        stone(r.level, r.sx - 6, bottom, r.sz - 6, r.sx + 6, TOP - 1, r.sz + 6);
    }

    /** A bed of rock at a level (two under its floor to three over), with veins of this ore in it. */
    static void bed(Rig r, int y, int half, Block ore, int veins, long seed) {
        stone(r.level, r.sx - half, y - 2, r.sz - half, r.sx + half, y + 3, r.sz + half);
        RandomSource rnd = RandomSource.create(seed);
        for (int i = 0; i < veins; i++) {
            int x = r.sx + rnd.nextInt(2 * half - 4) - half + 2, z = r.sz + rnd.nextInt(2 * half - 4) - half + 2;
            int yy = y - 1 + rnd.nextInt(4);
            for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) for (int c = 0; c < 2; c++)
                if (rnd.nextInt(3) > 0 && yy + c <= y + 2) r.level.setBlock(new BlockPos(x + a, yy + c, z + b), ore.defaultBlockState(), 2);
        }
    }

    static void kit(VillageFolkEntity m, Item pick) {
        m.removeMatching(st -> st.is(net.minecraft.tags.ItemTags.PICKAXES), 64);
        if (m.getMainHandItem().is(net.minecraft.tags.ItemTags.PICKAXES)) m.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        m.insertItem(new ItemStack(pick));
        m.insertItem(new ItemStack(Items.TORCH, 64));
        m.insertItem(new ItemStack(Items.COBBLESTONE, 64));
        m.insertItem(new ItemStack(Items.BREAD, 16));
    }

    static void zone(Rig r, VillageFolkEntity m, int depth) {
        m.assignPlot(WorkZone.around(new BlockPos(r.sx, TOP, r.sz), 8, depth), "Mine");
    }

    /** The miner kept at it: daylight, and a shift in the works whenever it has nothing in hand. */
    static void keepAtIt(Rig r, VillageFolkEntity m) {
        if (r.level.getDayTime() % 24000L > 10000L) r.level.setDayTime(1000);
        // Its spoil banked for it: the test's rock stands in the air, with no walk home to the stores from it.
        int spoil = m.countMatching(s -> s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.DIRT) || s.is(Items.GRAVEL));
        if (spoil > 96) m.removeMatching(s -> s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.DIRT) || s.is(Items.GRAVEL) || s.is(Items.COBBLESTONE), spoil - 64);
        if (m.peekJob() == null) m.enqueue(Job.mine(m.workZone() != null ? m.workZone().depth() : 16, MineWorks.ARG));
    }

    static Map<MineDemand.Ore, Integer> plenty() {
        Map<MineDemand.Ore, Integer> m = new EnumMap<>(MineDemand.Ore.class);
        for (MineDemand.Ore o : MineDemand.Ore.values()) m.put(o, 9999);
        return m;
    }

    static void fast(ServerLevel level) {
        level.getServer().tickRateManager().setTickRate(60.0F);
    }

    /** Passed: in the test's own tick (a pass called from a server tick listener is never reported, and the run stalls). */
    static void done(GameTestHelper helper) {
        MineSiteGameTests.standDown(helper.getLevel());
        helper.runAfterDelay(1, helper::succeed);
    }

    /**
     * The main shaft sunk by a miner from the pithead: its steps open three high and dressed as stairs, the landings level,
     * one turn of it a quarter of the way to the iron; water and lava met in its walls stopped with cobblestone; then the
     * headframe over it and the railings round its open top. The five levels planned at the ores' depths.
     */
    @GameTest(template = "empty", timeoutTicks = 14000, batch = "mw01_shaft")
    public static void mw01_shaft(GameTestHelper helper) {
        Rig r = rig(helper, 3802000, 1);
        ServerLevel level = r.level;
        VillageFolkEntity m = r.miners.get(0);
        MineDemand.stockForTests(r.v, plenty());
        List<String> plan = MineWorks.planForTests(level, r.v);
        Kit.log("[VT] mw01 the levels planned from Y" + TOP + ": " + plan);
        helper.assertTrue(plan.size() == 5, "five levels at the ores' depths: " + plan);
        tower(r, TOP - 20);
        // Water and lava behind the walls of the steps of the second and third sides.
        BlockPos water = MineWorks.stepForTests(level, r.v, 6).get(1).east().above();
        BlockPos lava = MineWorks.stepForTests(level, r.v, 10).get(1).south().above();
        level.setBlock(water, Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(lava, Blocks.LAVA.defaultBlockState(), 2);
        kit(m, Items.IRON_PICKAXE);
        zone(r, m, 16);
        m.teleportTo(r.sx - 5.5, TOP, r.sz - 1.5);
        fast(level);
        final int[] tick = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            keepAtIt(r, m);
            int sunk = MineWorks.shaftForTests(level, r.v)[0];
            if (sunk < 16 && tick[0] < 13000) return false;
            Villages.Village vil = Villages.get(r.v);
            MineWorks.tick(level, vil);
            int open = 0, cells = 0, stairs = 0, slices = 0;
            for (int i = 0; i < 16; i++) {
                List<BlockPos> step = MineWorks.stepForTests(level, r.v, i);
                for (BlockPos c : step) {
                    cells++;
                    if (clear(level, c) && clear(level, c.above()) && clear(level, c.above(2))) open++;
                    if (i % 4 != 0) {
                        slices++;
                        if (level.getBlockState(c.below()).getBlock() instanceof StairBlock) stairs++;
                    }
                }
            }
            int[] s = MineWorks.shaftForTests(level, r.v);
            boolean head = level.getBlockState(new BlockPos(r.sx - 1, TOP, r.sz - 1)).is(BlockTags.LOGS)
                && level.getBlockState(new BlockPos(r.sx, TOP + 3, r.sz)).is(Blocks.LANTERN);
            int fences = 0;
            for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++)
                if (level.getBlockState(new BlockPos(r.sx + dx, TOP, r.sz + dz)).is(BlockTags.FENCES)) fences++;
            boolean entry = !level.getBlockState(new BlockPos(r.sx - 2, TOP, r.sz - 2)).is(BlockTags.FENCES);
            BlockState w = level.getBlockState(water), l = level.getBlockState(lava);
            int[] counts = WorksGoal.countsForTests();
            Kit.log("[VT] mw01 sunk " + s[0] + " steps in " + tick[0] + " ticks (Y" + TOP + " to " + (TOP - 12) + "): " + open + " of " + cells
                + " cells open three high, " + stairs + " of " + slices + " steps laid as stairs; headframe " + head + ", " + fences + " fences round the top, the way in clear "
                + entry + "; water now " + w.getBlock() + ", lava now " + l.getBlock() + "; broken " + counts[0] + ", sealed " + counts[1] + ", put on the way when stuck " + counts[3]
                + " | " + String.join(" | ", MineWorks.report(level, r.v)));
            helper.assertTrue(s[0] >= 16, "the shaft sunk a whole turn: " + s[0]);
            helper.assertTrue(open == cells, "every step open three high: " + open + "/" + cells);
            helper.assertTrue(stairs >= slices - 2, "the steps laid as stairs: " + stairs + "/" + slices);
            helper.assertTrue(head && fences >= 4 && entry, "the headframe over it, the railings round the open top, the way in clear");
            helper.assertTrue(w.getFluidState().isEmpty() && l.getFluidState().isEmpty() && counts[1] >= 2, "water and lava stopped with cobblestone");
            done(helper);
            return true;
        });
    }

    /** A level laid ready in the test's rock: the shaft sunk to it and its landing cut (by the test, for nothing). */
    static int level(Rig r, int id, Block ore, int veins, VillageFolkEntity dresser) {
        int y = MineWorks.levelY(r.level, r.v, id);
        tower(r, y - 3);
        bed(r, y, 36, ore, veins, 4242L + id);
        int corner = 4 * ((TOP - y) / 3);
        MineWorks.sinkForTests(r.level, r.v, corner + 1, dresser);
        MineWorks.roomForTests(r.level, r.v, id);
        return y;
    }

    /** Nothing to bump into here (air, a torch on the wall). */
    static boolean clear(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getFluidState(p).isEmpty();
    }

    static int carried(VillageFolkEntity m, java.util.function.Predicate<ItemStack> what) {
        return m.countCarried(what);
    }

    /** A town short of one ore (the rest in plenty) sends its miner to the level where that ore lies, and it is dug. */
    static void shortOf(GameTestHelper helper, int x, MineDemand.Ore ore, Villages.Age age, Item pick, int wantLevel, Block oreBlock,
                        java.util.function.Predicate<ItemStack> got, String tag) {
        Rig r = rig(helper, x, 3);
        ServerLevel level = r.level;
        Villages.ageForTests(r.v, age);
        Map<MineDemand.Ore, Integer> stock = plenty();
        stock.put(ore, 0);
        MineDemand.stockForTests(r.v, stock);
        for (VillageFolkEntity m : r.miners) { kit(m, pick); zone(r, m, -50); }
        int[] lv = new int[3];
        for (int i = 0; i < 3; i++) lv[i] = MineWorks.levelForTests(level, r.miners.get(i));
        int on = 0;
        for (int l : lv) if (l == wantLevel) on++;
        boolean told = false;
        for (Villages.News n : Villages.news(r.v)) if (n.text().contains("turned") && n.text().contains(ore.word)) told = true;
        Kit.log("[VT] " + tag + " " + age + ", short of " + ore.word + " only: the three miners sent to levels " + lv[0] + ", " + lv[1] + ", " + lv[2]
            + " (the " + ore.word + "'s is " + wantLevel + "); the news says so " + told);
        helper.assertTrue(on >= 1 && on < 3, "one or two of the three to the " + ore.word + ", the rest where the age sends them: " + on);
        helper.assertTrue(told, "the town's news tells of it");
        // The ore dug: one miner, its level laid ready in rock with veins of the ore, the miner set down on the landing.
        VillageFolkEntity m = null;
        for (int i = 0; i < 3; i++) if (lv[i] == wantLevel) m = r.miners.get(i);
        for (VillageFolkEntity o : r.miners) if (o != m) o.discard();
        VillageFolkEntity miner = m;
        int y = level(r, wantLevel, oreBlock, 260, miner);
        int[] mid = MineWorks.shaftForTests(level, r.v);
        int lvl = wantLevel;
        // The middle of the landing, corner-ways off the shaft (MineWorks.roomMiddle).
        int side = ((TOP - y) / 3) % 4;
        int ox = side == 0 || side == 3 ? -1 : 1, oz = side < 2 ? -1 : 1;
        miner.teleportTo(mid[2] + 6 * ox + 0.5, y, mid[3] + 6 * oz + 0.5);
        int before = carried(miner, got);
        fast(level);
        final int[] tick = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            keepAtIt(r, miner);
            int now = carried(miner, got);
            if (now - before < 4 && tick[0] < 5500) return false;
            Kit.log("[VT] " + tag + " the " + ore.word + " carried by the miner sent for it: " + before + " -> " + now + " in " + tick[0] + " ticks; level "
                + lvl + " at Y" + y + " | " + String.join(" | ", MineWorks.report(level, r.v)));
            helper.assertTrue(now - before >= 4, "the " + ore.word + " rises: " + before + " -> " + now);
            done(helper);
            return true;
        });
    }

    /** A Diamond Age town with iron and diamonds to spare and no coal sends a miner back up to the coal level, and coal comes in. */
    @GameTest(template = "empty", timeoutTicks = 7000, batch = "mw02_coal_short")
    public static void mw02_coal_short(GameTestHelper helper) {
        shortOf(helper, 3806000, MineDemand.Ore.COAL, Villages.Age.DIAMOND, Items.IRON_PICKAXE, MineWorks.COAL, Blocks.COAL_ORE,
            s -> s.is(Items.COAL), "mw02");
    }

    /** An Iron Age town short of copper (and nothing else) sends a miner to the copper level, and copper comes in. */
    @GameTest(template = "empty", timeoutTicks = 7000, batch = "mw03_copper_short")
    public static void mw03_copper_short(GameTestHelper helper) {
        shortOf(helper, 3810000, MineDemand.Ore.COPPER, Villages.Age.IRON, Items.STONE_PICKAXE, MineWorks.COPPER, Blocks.COPPER_ORE,
            s -> s.is(Items.RAW_COPPER), "mw03");
    }

    /**
     * The levels by tools and by want: a wooden pick on the top level only; a stone pick never on the deep level; short of
     * redstone, an iron pick to the deep level; with nothing short, where the age sends it (the depth of its plot).
     */
    @GameTest(template = "empty", timeoutTicks = 300, batch = "mw04_levels")
    public static void mw04_levels(GameTestHelper helper) {
        Rig r = rig(helper, 3814000, 3);
        ServerLevel level = r.level;
        Villages.ageForTests(r.v, Villages.Age.DIAMOND);
        VillageFolkEntity wood = r.miners.get(0), stoneP = r.miners.get(1), iron = r.miners.get(2);
        kit(wood, Items.WOODEN_PICKAXE);
        kit(stoneP, Items.STONE_PICKAXE);
        kit(iron, Items.IRON_PICKAXE);
        for (VillageFolkEntity m : r.miners) zone(r, m, -50);
        MineDemand.stockForTests(r.v, plenty());
        int w0 = MineWorks.levelForTests(level, wood), s0 = MineWorks.levelForTests(level, stoneP), i0 = MineWorks.levelForTests(level, iron);
        Map<MineDemand.Ore, Integer> red = plenty();
        red.put(MineDemand.Ore.REDSTONE, 0);
        MineDemand.stockForTests(r.v, red);
        int w1 = MineWorks.levelForTests(level, wood), s1 = MineWorks.levelForTests(level, stoneP), i1 = MineWorks.levelForTests(level, iron);
        Kit.log("[VT] mw04 nothing short: wooden pick to level " + w0 + ", stone " + s0 + ", iron " + i0 + "; short of redstone: wooden " + w1 + ", stone " + s1
            + ", iron " + i1 + " | " + String.join(" | ", MineWorks.report(level, r.v)));
        helper.assertTrue(w0 == MineWorks.COAL && w1 == MineWorks.COAL, "a wooden pick works the top level only");
        helper.assertTrue(s0 != MineWorks.DEEP && s1 != MineWorks.DEEP, "a stone pick never the deep level");
        helper.assertTrue(i0 == MineWorks.DEEP, "nothing short: where the age sends it (down to the deep level for this one)");
        helper.assertTrue(i1 == MineWorks.DEEP, "short of redstone: the iron pick to the deep level");
        MineDemand.stockForTests(r.v, null);
        helper.succeed();
    }

    /**
     * A drift driven out from the landing by one miner: straight, with branches off it every fourth block, a turn where it
     * met water, all of it inside the mine's ground and none in the town's; the veins it met followed; then kept over a save
     * and a load and carried on from where it stopped.
     */
    @GameTest(template = "empty", timeoutTicks = 12000, batch = "mw05_drift")
    public static void mw05_drift(GameTestHelper helper) {
        Rig r = rig(helper, 3818000, 1);
        ServerLevel level = r.level;
        VillageFolkEntity m = r.miners.get(0);
        MineDemand.stockForTests(r.v, plenty());
        kit(m, Items.IRON_PICKAXE);
        zone(r, m, 88);
        MineWorks.pinForTests(r.v, Direction.NORTH);          // out across the mine's ground, into the water put in its way
        int y = level(r, MineWorks.COAL, Blocks.IRON_ORE, 90, m);
        m.teleportTo(r.sx - 6 + 0.5, y, r.sz - 6 + 0.5);
        fast(level);
        final int[] tick = { 0 };
        final boolean[] wet = { false };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            keepAtIt(r, m);
            List<int[]> drifts = MineWorks.driftsForTests(level, r.v);
            int[] d = drifts.isEmpty() ? null : drifts.get(0);
            if (d != null && !wet[0] && d[3] >= 10) {
                // Water three blocks ahead of the face, in the way it is going.
                List<BlockPos> cells = MineWorks.cellsForTests(level, r.v, MineWorks.COAL);
                BlockPos tip = cells.get(d[3] - 1), prev = cells.get(d[3] - 2);
                Direction way = Direction.fromDelta(tip.getX() - prev.getX(), 0, tip.getZ() - prev.getZ());
                if (way != null) {
                    level.setBlock(tip.relative(way, 3), Blocks.WATER.defaultBlockState(), 2);
                    wet[0] = true;
                }
            }
            boolean enough = d != null && d[3] >= 20 && d[4] >= 1 && d[6] >= 2;
            if (!enough && tick[0] < 11000) return false;
            List<BlockPos> cells = MineWorks.cellsForTests(level, r.v, MineWorks.COAL);
            int out = 0, inTown = 0, open = 0;
            for (BlockPos c : cells) {
                if (!MineGround.reserved(r.v, c.getX(), c.getZ())) out++;
                if (TownDigs.inTownGroundRaw(level, r.v, r.heart, c, 0)) inTown++;
                if (clear(level, c) && clear(level, c.above())) open++;
            }
            String path = d == null ? "" : String.valueOf(d[3]);
            int longest = 0;
            if (d != null) {
                // The longest straight run of the drift (its first cells are its own: the branches come after).
                BlockPos a = null, b = null;
                int run = 0;
                for (int i = 0; i < d[3]; i++) {
                    BlockPos c = cells.get(i);
                    if (a != null && b != null && c.getX() - b.getX() == b.getX() - a.getX() && c.getZ() - b.getZ() == b.getZ() - a.getZ()) run++;
                    else run = 1;
                    longest = Math.max(longest, run);
                    a = b;
                    b = c;
                }
            }
            int[] counts = WorksGoal.countsForTests();
            String before = Ledger.note(r.v, "mine.works");
            Ledger.reloadForTests();
            MineWorks.forgetCache();
            MineGround.forgetCache();
            List<int[]> after = MineWorks.driftsForTests(level, r.v);
            boolean same = after.size() == drifts.size() && d != null && after.get(0)[3] == d[3] && after.get(0)[6] == d[6];
            Kit.log("[VT] mw05 after " + tick[0] + " ticks: drift " + (d == null ? "none" : "length " + path + ", turned " + d[4] + ", bent " + d[5] + ", branches " + d[6]
                + ", ended " + (char) d[7]) + ", longest straight run " + longest + "; " + cells.size() + " cells of tunnel, " + open + " open, " + out
                + " outside the mine's ground, " + inTown + " in the town's; broken " + counts[0] + ", ore left " + counts[2] + "; after a save and a load the same "
                + same + " (" + (before == null ? 0 : before.length()) + " chars); the miner " + WorksGoal.stateForTests(m) + " — " + m.debugLine() + " | " + String.join(" | ", MineWorks.report(level, r.v)) + " || " + String.join(" | ", MineWorks.eventsForTests()) + " || paths " + MineWorks.pathsForTests(level, r.v));
            helper.assertTrue(d != null && d[3] >= 20, "a drift driven twenty blocks and more");
            helper.assertTrue(longest >= 8, "straight, in long runs: " + longest);
            helper.assertTrue(d[4] >= 1, "it turned where it met the water");
            helper.assertTrue(d[6] >= 2, "branches cut off it: " + d[6]);
            helper.assertTrue(out == 0 && inTown == 0, "all of it in the mine's ground, none in the town's");
            helper.assertTrue(open >= cells.size() - 2, "its cells open two high");
            helper.assertTrue(same, "kept over a save and a load");
            done(helper);
            return true;
        });
    }

    /** Two miners on one level: each drives a drift its own way, or the sociable one joins the other's and cuts its branches. */
    @GameTest(template = "empty", timeoutTicks = 10000, batch = "mw06_two_miners")
    public static void mw06_two_miners(GameTestHelper helper) {
        Rig r = rig(helper, 3822000, 2);
        ServerLevel level = r.level;
        MineDemand.stockForTests(r.v, plenty());
        VillageFolkEntity a = r.miners.get(0), b = r.miners.get(1);
        b.life().setTraitsForTests(Social.Trait.SOCIABLE);
        for (VillageFolkEntity m : r.miners) { kit(m, Items.IRON_PICKAXE); zone(r, m, 88); }
        int y = level(r, MineWorks.COAL, Blocks.COAL_ORE, 60, a);
        a.teleportTo(r.sx - 6 + 0.5, y, r.sz - 6 + 0.5);
        b.teleportTo(r.sx - 5 + 0.5, y, r.sz - 6 + 0.5);
        fast(level);
        final int[] tick = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            keepAtIt(r, a);
            keepAtIt(r, b);
            List<int[]> drifts = MineWorks.driftsForTests(level, r.v);
            int byA = 0, byB = 0;
            for (int[] d : drifts) {
                if (d[2] == a.getUUID().hashCode() && d[3] >= 6) byA++;
                if (d[2] == b.getUUID().hashCode() && d[3] >= 6) byB++;
            }
            boolean shared = byA >= 1 && (byB >= 1 || MineWorks.JOINS >= 1);
            if (!shared && tick[0] < 9000) return false;
            Kit.log("[VT] mw06 after " + tick[0] + " ticks: drifts " + drifts.size() + " (by the first " + byA + ", by the sociable one " + byB + "), branches cut in a mate's drift "
                + MineWorks.JOINS + " | " + String.join(" | ", MineWorks.report(level, r.v)));
            helper.assertTrue(shared, "both at work on the level: their own drifts, or one in with the other");
            done(helper);
            return true;
        });
    }

    /** On low ground (a flat world's hill, as in a village test) the works still plan a level and sink their shaft. */
    @GameTest(template = "empty", timeoutTicks = 5000, batch = "mw07_low_ground")
    public static void mw07_low_ground(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<VillageFolkEntity> miners = MineSiteGameTests.town(level, 3826000, 190, 1, StationTask.MINE);
        VillageFolkEntity m = miners.get(0);
        UUID v = m.ownerId();
        BlockPos heart = m.villageCentre();
        MineWorks.forgetForTests(v);
        int sx = heart.getX() + 124, sz = heart.getZ();
        Kit.hill(level, sx, sz, 14, 12, 77L);
        Ledger.note(v, "mine.site", sx + "," + sz);
        MineGround.reserve(level, v, heart, new BlockPos(sx, 0, sz), false);
        MineDemand.stockForTests(v, plenty());
        WorksGoal.resetCountsForTests();
        Rig r = new Rig(level, miners, v, heart, sx, sz);
        kit(m, Items.IRON_PICKAXE);
        BlockPos head = Kit.surface(level, sx, sz);
        m.assignPlot(WorkZone.around(head, 8, 16), "Mine");
        m.teleportTo(sx - 4.5, Kit.surface(level, sx - 5, sz - 2).getY(), sz - 1.5);
        level.setDayTime(1000);
        List<String> plan = MineWorks.planForTests(level, v);
        final int[] tick = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            keepAtIt(r, m);
            int[] c = WorksGoal.countsForTests();
            if (tick[0] % 500 == 0) Kit.log("[VT] mw07 @" + tick[0] + " broken " + c[0] + ", at " + m.blockPosition().toShortString() + ": " + WorksGoal.stateForTests(m));
            if (c[0] < 40 && tick[0] < 4500) return false;
            Kit.log("[VT] mw07 a hill at Y" + head.getY() + ": levels " + MineWorks.planForTests(level, v) + " (at first " + plan + "); broken " + c[0] + " in " + tick[0]
                + " ticks | " + String.join(" | ", MineWorks.report(level, v)) + " || " + String.join(" | ", MineWorks.eventsForTests()));
            helper.assertTrue(!plan.isEmpty(), "a level planned on low ground");
            helper.assertTrue(c[0] >= 40, "the shaft sunk into the hill: " + c[0] + " blocks");
            done(helper);
            return true;
        });
    }

    /** The landing of a level cut by a miner once the shaft reaches it: furnished, and the level's first drift begun. */
    @GameTest(template = "empty", timeoutTicks = 5000, batch = "mw08_landing")
    public static void mw08_landing(GameTestHelper helper) {
        Rig r = rig(helper, 3830000, 1);
        ServerLevel level = r.level;
        VillageFolkEntity m = r.miners.get(0);
        MineDemand.stockForTests(r.v, plenty());
        kit(m, Items.IRON_PICKAXE);
        zone(r, m, 88);
        int y = MineWorks.levelY(level, r.v, MineWorks.COAL);
        tower(r, y - 3);
        bed(r, y, 20, Blocks.COAL_ORE, 30, 99L);
        MineWorks.sinkForTests(level, r.v, 4 * ((TOP - y) / 3) + 1, m);
        m.teleportTo(r.sx - 5.5, TOP, r.sz - 1.5);
        fast(level);
        final int[] tick = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            keepAtIt(r, m);
            int[] s = MineWorks.shaftForTests(level, r.v);
            boolean room = (s[5] & 1) != 0;
            List<int[]> drifts = MineWorks.driftsForTests(level, r.v);
            if ((!room || drifts.isEmpty() || drifts.get(0)[3] < 2) && tick[0] < 4500) {
                if (tick[0] % 500 == 0) Kit.log("[VT] mw08 @" + tick[0] + " at " + m.blockPosition().toShortString() + ": " + WorksGoal.stateForTests(m));
                return false;
            }
            int[] o = { -1, -1 };
            BlockPos chest = new BlockPos(r.sx + 8 * o[0], y, r.sz + 8 * o[1]);
            boolean furnished = level.getBlockState(chest).is(Blocks.CHEST) && level.getBlockState(new BlockPos(r.sx - 6, y + 2, r.sz - 6)).is(Blocks.LANTERN);
            Kit.log("[VT] mw08 after " + tick[0] + " ticks: the landing cut " + room + ", furnished " + furnished + ", drifts " + drifts.size()
                + " | " + String.join(" | ", MineWorks.eventsForTests()));
            helper.assertTrue(room && furnished, "the landing cut and furnished");
            helper.assertTrue(!drifts.isEmpty() && drifts.get(0)[3] >= 2, "a drift begun from it");
            done(helper);
            return true;
        });
    }
}
