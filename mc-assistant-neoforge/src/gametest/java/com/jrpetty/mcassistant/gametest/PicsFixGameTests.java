package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.CameraCommands;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AdminTools;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.CivicLand;
import com.jrpetty.mcassistant.entity.InventionCommands;
import com.jrpetty.mcassistant.entity.KnightManors;
import com.jrpetty.mcassistant.entity.Knights;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.StageViews;
import com.jrpetty.mcassistant.entity.TaxBook;
import com.jrpetty.mcassistant.entity.TradeWorld;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [pics-fix] What the 0.309 pictures showed wrong: a knight's manor raised round a tree (its crown filling the great
 * room), and the stages' cameras aimed at the middles of buildings, inside their walls, so they ended up inside (the
 * clocktower's shaft, the dark of the foundry, the lighthouse's lamp room); and the protest's crowd at the hall.
 *
 * <p>Each on its own ground (x 2340000 to 2350000, z 70000), in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PicsFixGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;
    private static final Pattern VIEW = Pattern.compile("VIEW (\\S+) (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+)");

    /** A town of so many folk about this spot, all of one village, at their trades (none at the watch). */
    private static List<VillageFolkEntity> town(GameTestHelper helper, ServerLevel level, int x, int n) {
        Kit.reset(level);
        CivicLand.liveForTests(true);
        Kit.hold(level, x, Z, 112);
        Kit.prepare(level, x, Z, 112);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 3 + (i % 4) * 2, Z - 2 + (i / 4) * 2), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.ensurePersona();
            f.setJob(i % 2 == 0 ? StationTask.FARM : StationTask.WOOD);
            folk.add(f);
        }
        UUID village = folk.get(0).ownerId();
        for (VillageFolkEntity f : folk) helper.assertTrue(village != null && village.equals(f.ownerId()), "all of one village");
        return folk;
    }

    /** A chest of the town's stores beside its heart, holding these. */
    private static Container stores(ServerLevel level, UUID village, int x, ItemStack... goods) {
        BlockPos at = Kit.surface(level, x + 6, Z + 6);
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        ZoneChests.mark(level, at);
        Container c = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) c.setItem(i, goods[i].copy());
        c.setChanged();
        Villages.forgetStores(village);
        return c;
    }

    /** The stage's views by name: feet x y z, mark x y z. */
    private static java.util.Map<String, int[]> views(List<String> lines) {
        java.util.Map<String, int[]> out = new java.util.LinkedHashMap<>();
        for (String l : lines) {
            Matcher m = VIEW.matcher(l);
            while (m.find()) {
                int[] v = new int[6];
                for (int i = 0; i < 6; i++) v[i] = Integer.parseInt(m.group(i + 2));
                out.put(m.group(1), v);
            }
        }
        return out;
    }

    /** As smoke.py stands the camera: feet at the block's middle, the mark half a block up in its middle. */
    private static Vec3 feet(int[] v) {
        return new Vec3(v[0] + 0.5, v[1], v[2] + 0.5);
    }

    private static Vec3 mark(int[] v) {
        return new Vec3(v[3] + 0.5, v[4] + 0.5, v[5] + 0.5);
    }

    /** Is the camera in open air with its mark in sight (CameraCommands.clearSpot would leave it where it is)? */
    private static boolean clear(ServerLevel level, int[] v) {
        return CameraCommands.clearSpot(level, feet(v), mark(v)).how().equals("clear");
    }

    // ============================================================ px01 the manor's lot felled

    /**
     * A knight's manor raised at once (the stage, an admin's build) on a plot with trees standing on it: the trees come
     * down first, as the builders take them down, their wood into the stores; not a leaf of them is left in the house.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "px_px01_manor_lot")
    public static void px01_manor_raised_on_a_cleared_lot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2340000;
        level.setDayTime(5 * 24000L + 4000L);
        List<VillageFolkEntity> folk = town(helper, level, x, 8);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        stores(level, id, x, new ItemStack(Items.WHITE_BANNER, 2), new ItemStack(Items.YELLOW_DYE, 2));
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.moveTo(v.centre().getX() + 0.5, v.centre().getY(), v.centre().getZ() + 0.5);
        try {
            Ledger.addCitizen(id, p.getUUID(), p.getName().getString());
            Knights.dub(level, v, p.getUUID(), p.getName().getString(), false);
            BlockPos plot = KnightManors.plotForTests(id, p.getUUID());
            helper.assertTrue(plot != null, "land granted");
            // Three wild trees on the plot: one in the middle of the great room, two by its walls.
            int[][] at = { { 0, 0 }, { 3, 2 }, { -3, -2 } };
            for (int[] t : at) Kit.wildTree(level, plot.getX() + t[0], plot.getZ() + t[1]);
            int leavesBefore = naturalLeaves(level, plot);
            helper.assertTrue(leavesBefore > 40, "the trees stand on the plot: " + leavesBefore + " leaves");
            int logsBefore = Market.stock(level, id, s -> s.is(ItemTags.LOGS));
            String built = KnightManors.buildNow(level, v, p.getUUID(), false);
            helper.assertTrue("built".equals(KnightManors.manorForTests(id, p.getUUID())), "the manor stands: " + built);
            int leavesAfter = naturalLeaves(level, plot);
            helper.assertTrue(leavesAfter == 0, "not a leaf of the trees left on the lot: " + leavesAfter);
            // The trunk in the middle of the great room is gone (the drawing has a carpet and air there).
            for (int dy = 1; dy <= 3; dy++) {
                BlockState st = level.getBlockState(plot.above(dy));
                helper.assertTrue(!st.is(BlockTags.LOGS), "no trunk standing in the great room at +" + dy + ": " + st);
            }
            int logsAfter = Market.stock(level, id, s -> s.is(ItemTags.LOGS));
            helper.assertTrue(logsAfter >= logsBefore + 10, "the trees' wood in the stores: " + logsBefore + " -> " + logsAfter);
            Kit.log("px01 " + built + "; leaves " + leavesBefore + " -> " + leavesAfter + "; logs in the stores " + logsBefore + " -> " + logsAfter);
        } finally {
            Kit.noLeftoverPlayers(level);
        }
        helper.succeed();
    }

    /** A tree's own leaves (not persistent) over a manor's lot and a block round it, its whole height. */
    private static int naturalLeaves(ServerLevel level, BlockPos plot) {
        int[] half = Blueprints.fullHalf(KnightManors.STRUCTURE);
        int r = Math.max(half[0], half[1]) + 1, n = 0;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = 0; dy <= BuildGoal.lotTop(KnightManors.STRUCTURE); dy++) {
            BlockState st = level.getBlockState(plot.offset(dx, dy, dz));
            if (st.is(BlockTags.LEAVES) && !(st.hasProperty(LeavesBlock.PERSISTENT) && st.getValue(LeavesBlock.PERSISTENT))) n++;
        }
        return n;
    }

    // ============================================================ px02 the lot's height

    /**
     * The builders clear a lot of a tree its whole height and two over: a crown over a manor's ridge (thirteen up) or
     * round a lighthouse's lamp room is taken down (it stopped at twelve for every building).
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "px_px02_lot_top")
    public static void px02_lot_cleared_its_whole_height(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2342000;
        Kit.reset(level);
        Kit.hold(level, x, Z, 24);
        Kit.prepare(level, x, Z, 24);
        BlockPos base = Kit.surface(level, x, Z);
        int manor = BuildGoal.lotTop(KnightManors.STRUCTURE), light = BuildGoal.lotTop("seamark"), well = BuildGoal.lotTop("well");
        helper.assertTrue(manor >= StageViews.height(KnightManors.STRUCTURE) + 1 && manor > 12, "the manor's lot to over its ridge: " + manor);
        helper.assertTrue(light >= StageViews.height("seamark") + 1, "the light's lot to over its roof: " + light);
        helper.assertTrue(well == 12, "a small building's lot as before: " + well);
        // A wild tree's crown at fourteen over the floor, over a manor's ridge.
        BlockPos log = base.above(13), leaf = base.above(14);
        level.setBlock(log, Blocks.OAK_LOG.defaultBlockState(), 3);
        level.setBlock(leaf, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false), 3);
        List<BlockPos> tree = BuildGoal.treeOnLot(level, KnightManors.STRUCTURE, base, Direction.NORTH);
        helper.assertTrue(tree.contains(leaf) && tree.contains(log), "the crown over the ridge is on the list: " + tree);
        Kit.log("px02 lot tops: manor " + manor + ", seamark " + light + ", well " + well + "; " + tree.size() + " of the tree");
        helper.succeed();
    }

    // ============================================================ px03 the inventions' views

    /**
     * The inventions' stage: every building's camera stands in open air out in front of it with its mark in sight (the
     * mark in front of the front wall, not inside it), far enough back for the whole of it; the scholar held at its desk.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "px_px03_tech_views")
    public static void px03_inventions_seen_from_the_street(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2344000;
        level.setDayTime(3 * 24000L + 6000L);
        List<VillageFolkEntity> folk = town(helper, level, x, 6);
        Villages.Village v = Villages.get(folk.get(0).ownerId());
        BlockPos at = Kit.surface(level, x + 10, Z + 50);
        List<String> out = InventionCommands.stageForTests(level, v, at);
        java.util.Map<String, int[]> views = views(out);
        helper.assertTrue(views.size() >= 6, "the views: " + out);
        int checked = 0;
        for (java.util.Map.Entry<String, int[]> e : views.entrySet()) {
            int[] w = e.getValue();
            helper.assertTrue(clear(level, w), "a clear view for " + e.getKey() + ": " + java.util.Arrays.toString(w)
                + " -> " + CameraCommands.clearSpot(level, feet(w), mark(w)));
            if (e.getKey().matches("tech-[1-5]-.*")) {
                String drawing = e.getKey().replaceFirst("tech-\\d-", "");
                // Out in front (south: the row faces south), clear of the building, and far enough for its height.
                BlockState there = level.getBlockState(new BlockPos(w[3], w[4], w[5]));
                helper.assertTrue(!there.isSolid(), "the mark in open air before " + drawing + ": " + there);
                double back = feet(w).distanceTo(mark(w));
                helper.assertTrue(back >= StageViews.height(drawing) + 4, "back far enough for the whole of " + drawing + ": " + back);
                helper.assertTrue(w[2] > at.getZ() + Blueprints.fullHalf(drawing)[1] + 4, "the camera out in the street before " + drawing + ": " + w[2]);
                checked++;
            }
        }
        helper.assertTrue(checked == 5, "five buildings framed: " + checked);
        int held = 0;
        for (VillageFolkEntity f : folk) if (f.getTags().contains(InventionCommands.HELD)) {
            held++;
            helper.assertTrue(f.isNoAi(), "the scholar held at its desk for the camera");
            f.setNoAi(false);
            f.removeTag(InventionCommands.HELD);
        }
        Kit.log("px03 views " + views.keySet() + "; scholars held " + held + "; " + out.get(out.size() - 1));
        helper.succeed();
    }

    // ============================================================ px04 the lighthouse

    /** The harbour's stage: the light from over the water before it, the tower whole; the harbour and the ship clear. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "px_px04_light_view")
    public static void px04_lighthouse_seen_from_the_water(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2346000;
        level.setDayTime(3 * 24000L + 6000L);
        List<VillageFolkEntity> folk = town(helper, level, x, 6);
        Villages.Village v = Villages.get(folk.get(0).ownerId());
        BlockPos at = Kit.surface(level, x + 20, Z + 20);
        List<String> out = TradeWorld.harbourStageForTests(level, v, at);
        java.util.Map<String, int[]> views = views(out);
        helper.assertTrue(views.containsKey("light") && views.containsKey("harbour") && views.containsKey("ship"), "the views: " + out);
        for (String name : new String[]{ "harbour", "ship", "light" }) {
            int[] w = views.get(name);
            helper.assertTrue(clear(level, w), "a clear view for " + name + ": " + java.util.Arrays.toString(w)
                + " -> " + CameraCommands.clearSpot(level, feet(w), mark(w)));
        }
        int[] l = views.get("light");
        double back = feet(l).distanceTo(mark(l));
        helper.assertTrue(back >= StageViews.height("seamark") + 4, "back far enough for the whole tower: " + back);
        BlockState eye = level.getBlockState(BlockPos.containing(feet(l).add(0, 1.62, 0)));
        helper.assertTrue(eye.isAir(), "the camera in the open air, not in the tower: " + eye);
        Kit.log("px04 views " + views.keySet() + "; the light from " + java.util.Arrays.toString(l) + ", " + back + " off");
        helper.succeed();
    }

    // ============================================================ px06 the road, the milestone, the inn

    /** The roads' stage: along the road, the milestone and the inn, each camera in open air with its mark in sight. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "px_px06_road_views")
    public static void px06_road_milestone_and_inn_framed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2350000;
        level.setDayTime(3 * 24000L + 6000L);
        List<VillageFolkEntity> folk = town(helper, level, x, 6);
        Villages.Village v = Villages.get(folk.get(0).ownerId());
        BlockPos at = Kit.surface(level, x + 20, Z - 60);
        List<String> out = TradeWorld.roadStageForTests(level, v, at);
        java.util.Map<String, int[]> views = views(out);
        for (String name : new String[]{ "road", "milestone", "inn" }) {
            int[] w = views.get(name);
            helper.assertTrue(w != null, "a view for " + name + ": " + out);
            helper.assertTrue(clear(level, w), "a clear view for " + name + ": " + java.util.Arrays.toString(w)
                + " -> " + CameraCommands.clearSpot(level, feet(w), mark(w)));
        }
        int[] inn = views.get("inn");
        BlockState there = level.getBlockState(new BlockPos(inn[3], inn[4], inn[5]));
        helper.assertTrue(!there.isSolid(), "the inn's mark before its door, not inside it: " + there);
        helper.assertTrue(feet(inn).distanceTo(mark(inn)) >= StageViews.height("inn") + 4, "back far enough for the whole inn");
        Kit.log("px06 views " + views.keySet());
        helper.succeed();
    }

    // ============================================================ px05 the realm's stage: the manor, the protest

    /**
     * The realm's stage as the pictures run it, in the late afternoon: the manor's camera before its door, the whole of
     * it in sight; the protest called at the hall, and within the time the pictures give it (forty seconds) the angry
     * have come and stand in the crowd before the hall, in the camera's sight.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "px_px05_realm_stage")
    public static void px05_manor_and_protest_framed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2348000;
        level.setDayTime(4 * 24000L + 9200L);
        List<VillageFolkEntity> folk = town(helper, level, x, 10);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        stores(level, id, x, new ItemStack(Items.OAK_SIGN, 4), new ItemStack(Items.OAK_PLANKS, 24), new ItemStack(Items.STICK, 8));
        // The meeting hall, a street off, its door to the heart; the folk about the town at their work.
        BlockPos hall = Kit.surface(level, x + 24, Z - 4);
        AdminTools.Done d = AdminTools.place(level, v, "hall", hall, Direction.EAST);
        helper.assertTrue(d.ok(), "the hall: " + d.text());
        for (int i = 0; i < folk.size(); i++) {
            BlockPos to = Kit.surface(level, x - 30 + (i % 5) * 12, Z - 30 + (i / 5) * 50);
            folk.get(i).teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
            TaxBook.arrearsForTests(id, folk.get(i).getUUID(), 4);
        }
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.moveTo(v.centre().getX() + 0.5, v.centre().getY(), v.centre().getZ() + 0.5);
        String said = CivicLand.stage(level, v, p);
        java.util.Map<String, int[]> views = views(List.of(said));
        helper.assertTrue(views.containsKey("manor") && views.containsKey("protest"), "the views: " + said);
        for (String name : new String[]{ "manor", "protest" }) {
            int[] w = views.get(name);
            helper.assertTrue(clear(level, w), "a clear view for " + name + ": " + java.util.Arrays.toString(w)
                + " -> " + CameraCommands.clearSpot(level, feet(w), mark(w)));
        }
        int[] m = views.get("manor");
        helper.assertTrue(feet(m).distanceTo(mark(m)) >= StageViews.height(KnightManors.STRUCTURE) + 4, "back far enough for the whole manor");
        helper.assertTrue(said.contains("gather before the hall"), "the protest called: " + said);
        final long start = level.getGameTime();
        final int[] p0 = views.get("protest");
        helper.onEachTick(() -> {
            long t = level.getGameTime() - start;
            String crowd = CivicLand.crowd(level, v);
            if (t % 100 == 0) Kit.log("px05 t=" + t + " " + crowd);
            Matcher c = Pattern.compile("CROWD (\\d+) of (\\d+)").matcher(crowd);
            int near = c.find() ? Integer.parseInt(c.group(1)) : 0;
            if (near < 3) {
                helper.assertTrue(t < 800, "the crowd at the hall within the pictures' forty seconds: " + crowd);
                return;
            }
            // The crowd in the camera's sight: the protesters stand between the camera and the hall.
            int seen = 0;
            for (VillageFolkEntity f : folk) {
                Vec3 eye = f.position().add(0, 1.0, 0);
                if (f.blockPosition().distSqr(new BlockPos(p0[3], p0[4], p0[5])) <= 14 * 14 && CameraCommands.sees(level, feet(p0), eye)) seen++;
            }
            helper.assertTrue(seen >= 2, "the crowd in the camera's sight: " + seen + " (" + crowd + ")");
            Kit.log("px05 crowd at t=" + t + ": " + crowd + "; " + seen + " in sight");
            Kit.noLeftoverPlayers(level);
            helper.succeed();
        });
    }
}
