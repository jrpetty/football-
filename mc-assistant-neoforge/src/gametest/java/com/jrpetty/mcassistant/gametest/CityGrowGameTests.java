package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AdminTools;
import com.jrpetty.mcassistant.entity.CastleWall;
import com.jrpetty.mcassistant.entity.CityGrowth;
import com.jrpetty.mcassistant.entity.CityPlan;
import com.jrpetty.mcassistant.entity.Founding;
import com.jrpetty.mcassistant.entity.FoundingEasy;
import com.jrpetty.mcassistant.entity.MineGround;
import com.jrpetty.mcassistant.entity.SiteFinder;
import com.jrpetty.mcassistant.entity.TerraformAudit;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.net.FoundEasyPayload;
import com.jrpetty.mcassistant.village.FoundingPlan;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.Terraces;
import com.jrpetty.mcassistant.village.TownPlan;
import com.jrpetty.mcassistant.village.WallPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [city26] Big towns keep growing, hill towns stand on terraces, and the founding's loose ends.
 * <ul>
 * <li><b>cg01</b>: a town that has filled the three rings of its plan lays out a fourth, and builds on it: on its
 *     streets (walked from the heart), its wall's line moved out round it, never on the farmland or the mine's ground;
 *     filled again, a fifth.</li>
 * <li><b>cg02</b>: a town founded on a hillside is terraced: the heart level, each lot flat at its street's height, the
 *     drops faced with walls; and the ground audited as any founding's (TerraformAudit): no holes, no sheer edges,
 *     nothing floating.</li>
 * <li><b>cg03</b>: a spot on a flat world's meadow is not "below the sea", and scores as a flat meadow should.</li>
 * <li><b>cg04</b>: right-clicking the board a spawner put up (the way the game hands a player's click on to the
 *     block, an item in hand or not) opens the founding screen.</li>
 * </ul>
 * x 5,800,000 to 5,900,000, z 90000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class CityGrowGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 90000;

    // ============================================================ cg01: the plan grows outward

    @GameTest(template = EMPTY, timeoutTicks = 20000, batch = "cg01_rings")
    public static void cg01_rings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        CityGrowth.resetForTests();
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
        int x = 5800000;
        int reach = TownPlan.reach(5) + 24;
        Kit.hold(level, x, Z, reach);
        Kit.prepare(level, x, Z, reach);
        BlockPos heart = Kit.surface(level, x, Z);
        Founding.Outcome o = Founding.foundNow(level, heart, 40, 0F, null);
        Kit.log("[VT] cg01 founding: " + o.message());
        final int[] stage = { 0 };
        final UUID[] id = { null };
        helper.onEachTick(() -> {
            if (stage[0] != 0) return;
            if (Founding.near(level, heart, 8) || Founding.settling(level, heart, 16)) return;   // founded, and the folk settled in
            stage[0] = 1;
            Villages.Village v = Villages.nearest(level, heart, 96);
            if (v == null) { helper.fail("no village"); return; }
            id[0] = v.id();
            try {
                grow(helper, level, v);
            } catch (RuntimeException e) {
                Kit.log("[VT] cg01 threw " + e);
                helper.fail("threw " + e);
            }
        });
    }

    private static void grow(GameTestHelper helper, ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        BlockPos c = v.centre();
        Villages.ageForTests(id, Villages.Age.IRON);
        Kit.stockForGrowth(level, v);
        // The farmland to the north; the mine's ground out east, across where the fourth ring would run.
        Villages.setFieldsSide(id, TownPlan.NORTH);
        int[] mine = { c.getX() + 92, c.getZ() - 30, c.getX() + 118, c.getZ() + 30 };
        Ledger.note(id, "mine.ground", mine[0] + "," + mine[1] + "," + mine[2] + "," + mine[3]);
        MineGround.forgetCache();
        // A town of fair size: houses put up on its own lots, the rest of its three rings spoken for (as if built on).
        int built = 0;
        for (int k = 0; k < 50 && built < 46; k++) {
            if (AdminTools.buildProject(level, v, "house", false).ok()) built++;
        }
        int held = 0;
        for (TownPlan.Lot l : TownPlan.lots()) {
            if (TownPlan.ringOf(l) >= TownPlan.RINGS || l.kind() == TownPlan.Kind.SQUARE) continue;
            Villages.holdLot(id, c.offset(l.x(), 0, l.z()), 1, 1, Direction.NORTH);
            held++;
        }
        int counted = 0;
        for (String s : Villages.builtList(id)) if (!"colony".equals(s)) counted++;
        Kit.log("[VT] cg01 built " + built + " houses (" + counted + " buildings), held " + held + " lots; rings " + CityGrowth.rings(id)
            + "; " + Villages.lotReport(id));
        helper.assertTrue(counted >= 45, "a town of fair size: " + counted + " buildings");
        helper.assertTrue(CityGrowth.rings(id) == TownPlan.RINGS, "three rings laid out to begin with");
        // Full: three looks for a house find nothing, and the fourth ring is laid out.
        List<String> tries = new ArrayList<>();
        for (int k = 0; k < 3; k++) tries.add(AdminTools.buildProject(level, v, "house", false).text());
        Kit.log("[VT] cg01 full: " + tries + "; rings " + CityGrowth.rings(id));
        helper.assertTrue(CityGrowth.rings(id) == 4, "the fourth ring laid out once the three are full: " + CityGrowth.rings(id));
        helper.assertTrue(Villages.townReach(id) >= TownPlan.reach(4), "the town's reach takes it in: " + Villages.townReach(id));
        // Houses on the fourth ring.
        List<Ledger.Building> fourth = buildOn(level, v, 6);
        helper.assertTrue(!fourth.isEmpty(), "houses go up on the fourth ring");
        for (Ledger.Building b : fourth) {
            TownPlan.Lot lot = CityPlan.lotOf(v, b);
            int dx = b.anchor().getX() - c.getX(), dz = b.anchor().getZ() - c.getZ();
            Kit.log("[VT] cg01 ring-4 house at " + dx + "," + dz + " ring " + (lot == null ? -1 : TownPlan.ringOf(lot)) + " facing " + b.facing());
            helper.assertTrue(lot != null && TownPlan.ringOf(lot) == 3, "on the fourth ring's lots: " + dx + "," + dz);
            helper.assertTrue(CityPlan.reaches(level, v, lot), "its street walked from the heart: " + dx + "," + dz);
            helper.assertTrue(!Villages.onFarmland(id, dx, dz, 5, 5), "not on the farmland: " + dx + "," + dz);
            helper.assertTrue(!MineGround.anyMeets(b.anchor().getX() - 5, b.anchor().getZ() - 5, b.anchor().getX() + 5, b.anchor().getZ() + 5),
                "not on the mine's ground: " + dx + "," + dz);
        }
        // The wall's line goes out round them.
        WallPlan.Sides s = CastleWall.sides(id);
        Kit.log("[VT] cg01 wall " + s.encode());
        boolean out = false;
        for (int side = 0; side < 4; side++) if (s.of(side) >= WallPlan.line(3)) out = true;
        helper.assertTrue(out, "the wall goes out a ring on a side the town grew past: " + s.encode());
        // Every lot of the fourth ring on the farmland or the mine's ground kept off.
        int kept = 0;
        for (TownPlan.Lot l : TownPlan.lots()) {
            if (TownPlan.ringOf(l) != 3 || l.kind() != TownPlan.Kind.LOT) continue;
            int[] b = TownPlan.box(l);
            boolean onMine = MineGround.anyMeets(c.getX() + b[0], c.getZ() + b[1], c.getX() + b[2], c.getZ() + b[3]);
            boolean onFarm = Villages.onFarmland(id, l.x(), l.z(), TownPlan.LOT / 2, TownPlan.LOT / 2);
            if (onMine || onFarm) {
                kept++;
                helper.assertTrue(Villages.lotKeptOff(id, c, l), "a lot of the new ring on the " + (onMine ? "mine's ground" : "farmland") + " is kept off: " + l.x() + "," + l.z());
            }
        }
        helper.assertTrue(kept > 4, "some of the new ring lies on the farmland and the mine's ground, and is kept off: " + kept);
        // Filled again: the fifth.
        for (TownPlan.Lot l : TownPlan.lots()) {
            if (TownPlan.ringOf(l) != 3 || l.kind() == TownPlan.Kind.SQUARE) continue;
            Villages.holdLot(id, c.offset(l.x(), 0, l.z()), 1, 1, Direction.NORTH);
        }
        CityGrowth.sinceForTests(id);                                 // a day on
        for (int k = 0; k < 3; k++) AdminTools.buildProject(level, v, "house", false);
        Kit.log("[VT] cg01 full again: rings " + CityGrowth.rings(id));
        helper.assertTrue(CityGrowth.rings(id) == 5, "the fifth ring laid out: " + CityGrowth.rings(id));
        List<Ledger.Building> fifth = buildOn(level, v, 4);
        for (Ledger.Building b : fifth) {
            TownPlan.Lot lot = CityPlan.lotOf(v, b);
            Kit.log("[VT] cg01 ring-5 house at " + (b.anchor().getX() - c.getX()) + "," + (b.anchor().getZ() - c.getZ()) + " ring " + (lot == null ? -1 : TownPlan.ringOf(lot)));
            helper.assertTrue(lot != null && TownPlan.ringOf(lot) == 4 && CityPlan.reaches(level, v, lot), "on the fifth ring, on its street");
        }
        helper.assertTrue(!fifth.isEmpty(), "houses go up on the fifth ring");
        Kit.log("[VT] cg01 " + Villages.lotReport(id));
        helper.succeed();
    }

    /** Put up to {@code n} houses up on the lots the town's plan gives it now; the buildings put up. */
    private static List<Ledger.Building> buildOn(ServerLevel level, Villages.Village v, int n) {
        List<Ledger.Building> out = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int before = Ledger.buildings(v.id()).size();
            if (!AdminTools.buildProject(level, v, "house", false).ok()) break;
            List<Ledger.Building> all = Ledger.buildings(v.id());
            if (all.size() > before) out.add(all.get(all.size() - 1));
        }
        return out;
    }

    // ============================================================ cg02: a hill town on terraces

    @GameTest(template = EMPTY, timeoutTicks = 24000, batch = "cg02_terraces")
    public static void cg02_terraces(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        level.setDayTime(1000);
        int folk = 40, cx = 5820000, cz = Z;
        int radius = FoundingPlan.coreRadius(folk), outer = radius + FoundingPlan.BAND_MAX, half = outer + 14;
        Kit.hold(level, cx, cz, half + 16);
        Kit.prepare(level, cx, cz, half + 16);
        hillside(level, cx, cz, half);
        BlockPos heart = Kit.surface(level, cx, cz);
        com.jrpetty.mcassistant.entity.TownBlocks.traceForTests(true);
        Founding.Outcome o = Founding.foundNow(level, heart, folk, 0F, null);
        Kit.log("[VT] cg02 founding at " + heart.toShortString() + ": " + o.message());
        final long[] start = { -1 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (Founding.near(level, heart, 8)) {
                if (t % 400 == 0) Kit.log("[VT] cg02 @" + t + " " + Founding.status(level.getServer()));
                return;
            }
            if (start[0] < 0) start[0] = t;
            if (t - start[0] < 40) return;
            TerraformAudit.Done d = TerraformAudit.near(heart, 40);
            if (d == null) { helper.fail("the founding finished with no plan kept"); return; }
            Map<String, Integer> c = TerraformAudit.scan(level, d);
            if (TerraformAudit.FIRST.containsKey("offLevel")) {
                String[] q = TerraformAudit.FIRST.get("offLevel").split("[, ]");
                BlockPos at = new BlockPos(Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2]));
                StringBuilder tr = new StringBuilder();
                for (int dy = -2; dy <= 2; dy++) tr.append(" ").append(dy).append(":").append(com.jrpetty.mcassistant.entity.TownBlocks.setBy(at.above(dy)));
                Kit.log("[VT] cg02 who touched the first off-level column:" + tr);
            }
            com.jrpetty.mcassistant.entity.TownBlocks.traceForTests(false);
            Kit.log("[VT] cg02 AUDIT y=" + d.level() + " " + TerraformAudit.line(c) + (TerraformAudit.FIRST.isEmpty() ? "" : " | first: " + TerraformAudit.FIRST));
            Kit.Expect expect = new Kit.Expect();
            FoundingPlan.Ground g = d.ground();
            expect.that(g.terrace != null && g.terraced > 2000, "the hillside is terraced: " + g.terraced + " columns");
            expect.that(c.get("terraced") > 1000, "terraces off the heart's level: " + c.get("terraced"));
            expect.that(c.get("walls") > 40, "retaining walls between them: " + c.get("walls"));
            // The streets' steps: every riser a stair of dressed stone; no raw face of earth on a terrace or its street.
            int risers = 0, stairs = 0;
            for (int i = 0; i < g.side * g.side; i++) {
                if (Terraces.riser(g, i) < 0 || com.jrpetty.mcassistant.entity.TerraceWalls.wantedForTests(g, i)) continue;
                risers++;
                BlockPos p = new BlockPos(heart.getX() + g.dx(i), g.target[i], heart.getZ() + g.dz(i));
                if (com.jrpetty.mcassistant.entity.TerraceWalls.isStep(level.getBlockState(p))) stairs++;
            }
            Kit.log("[VT] cg02 street risers " + risers + ", laid as stairs " + stairs + "; audit steps " + c.get("steps") + ", pillars "
                + c.get("pillars") + ", raw faces " + c.get("rawFaces"));
            expect.that(risers > 20 && stairs * 100 >= risers * 95, "the streets' risers laid as stairs: " + stairs + " of " + risers);
            expect.that(c.get("rawFaces") <= c.get("square") / 500, "no raw face of earth on a terrace or its street: " + c.get("rawFaces"));
            // Every fault of the levelling nought, but for a handful of columns of the town's own early works: a founding of
            // forty on a hillside takes minutes, the town lives and builds on it meanwhile (at most two in a thousand).
            int square = Math.max(1, c.get("square"));
            for (String key : TerraformGameTests.MUST_BE_NOUGHT) {
                int most = key.equals("offLevel") || key.equals("sheer") || key.equals("pillars") ? square / 500 : 0;
                if (c.getOrDefault(key, 0) > most) expect.that(false, key + "=" + c.get(key) + " (at most " + most + ")");
            }
            expect.that(c.get("streetPillars") == 0, "no pillar or stub on a street: " + c.get("streetPillars"));
            // Each lot wholly on the terraces flat, at its street's height at the door; how many heights.
            int lots = 0, flat = 0, door = 0;
            java.util.Set<Integer> heights = new java.util.TreeSet<>();
            for (TownPlan.Lot l : TownPlan.lots()) {
                if (l.kind() != TownPlan.Kind.LOT) continue;
                int[] b = TownPlan.box(l);
                boolean whole = true;
                java.util.Set<Integer> tops = new java.util.HashSet<>();
                for (int dx = b[0] + 1; dx < b[2] && whole; dx++) {
                    for (int dz = b[1] + 1; dz < b[3]; dz++) {
                        if (Math.abs(dx) > g.outer || Math.abs(dz) > g.outer || g.terrace[g.index(dx, dz)] == Terraces.NONE) { whole = false; break; }
                        tops.add(com.jrpetty.mcassistant.entity.Terraform.groundY(level, heart.getX() + dx, heart.getZ() + dz));
                    }
                }
                if (!whole || tops.isEmpty()) continue;
                lots++;
                if (tops.size() == 1) flat++;
                int h = tops.iterator().next();
                heights.add(h);
                int[] f = TownPlan.front(l);
                int fy = com.jrpetty.mcassistant.entity.Terraform.groundY(level, heart.getX() + f[0], heart.getZ() + f[1]);
                if (Math.abs(fy - h) <= 1) door++;
            }
            Kit.log("[VT] cg02 lots on the terraces " + lots + ", flat " + flat + ", door level with the street " + door + ", heights " + heights);
            expect.that(lots >= 12 && flat * 10 >= lots * 9, "the lots flat: " + flat + " of " + lots);
            expect.that(door * 10 >= lots * 8, "their doors level with their streets: " + door + " of " + lots);
            expect.that(heights.size() >= 4, "on several terraces: " + heights);
            Villages.Village v = Villages.nearest(level, heart, 64);
            expect.that(v != null, "a village stands there");
            if (expect.clean()) helper.succeed();
            else helper.fail(expect.summary());
        });
    }

    /** A hillside rising to the east (three blocks in five) and a little to the south, with a few rolls across it. */
    static void hillside(ServerLevel level, int cx, int cz, int half) {
        TerraformGameTests.Land l = new TerraformGameTests.Land(level);
        int base = level.getMinBuildHeight() + 1;
        int b = base + 40;
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(), stone = Blocks.STONE.defaultBlockState();
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                int h = b + (int) Math.round(0.6 * dx + 0.2 * dz + 2 * Math.sin(dz / 11.0) * Math.cos(dx / 17.0));
                TerraformGameTests.column(l, cx + dx, cz + dz, base, h, grass, dirt, stone);
            }
        }
        l.done();
    }

    // ============================================================ cg03: the sea on a flat world

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cg03_flat_sea")
    public static void cg03_flat_sea(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        int x = 5840000;
        Kit.hold(level, x, Z, 140);
        Kit.prepare(level, x, Z, 140);
        helper.runAtTickTime(5, () -> {
            BlockPos at = Kit.surface(level, x, Z);
            SiteFinder.Pick here = SiteFinder.find(level, at, 0).picks().get(0);
            Kit.log("[VT] cg03 flat world: sea level " + SiteFinder.seaLevel(level) + " (Level says " + level.getSeaLevel() + "), ground y "
                + at.getY() + "; here " + here.score() + " '" + here.words() + "'");
            helper.assertTrue(!here.words().contains("below the sea"), "a flat world's meadow is not below the sea: " + here.words());
            helper.assertTrue(here.score() >= 70, "and scores as a flat meadow: " + here.score());
            helper.assertTrue(here.words().startsWith("flat"), "called flat: " + here.words());
            helper.succeed();
        });
    }

    // ============================================================ cg04: the waiting board, clicked

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "cg04_board_click")
    public static void cg04_board_click(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        int x = 5860000;
        Kit.hold(level, x, Z, 120);
        Kit.prepare(level, x, Z, 120);
        helper.runAtTickTime(5, () -> {
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            BlockPos stand = Kit.surface(level, x, Z - 5);
            p.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
            p.getInventory().clearContent();
            p.getAbilities().instabuild = false;
            try {
                BlockPos at = Kit.surface(level, x, Z);
                level.setBlock(at, McAssistantMod.FOLK_SPAWNER.get().defaultBlockState(), 3);
                VillageFolkSpawnerBlock.placed(level, at, level.getBlockState(at), p, 0F);
                BlockPos board = Founding.boardNear(level, at, 64);
                helper.assertTrue(board != null, "the spawner puts the board up");
                // Each cell of the board, clicked as the game passes a player's click on: bare-handed, with bread, with a block.
                int opened = 0, tried = 0;
                List<String> missed = new ArrayList<>();
                ItemStack[] hands = { ItemStack.EMPTY, new ItemStack(Items.BREAD, 4), new ItemStack(Items.OAK_PLANKS, 8) };
                for (ItemStack hand : hands) {
                    for (int dx = -3; dx <= 3; dx++) {
                        for (int dy = 0; dy <= 3; dy++) {
                            BlockPos cell = board.offset(dx, dy, 0);
                            BlockPos cell2 = board.offset(0, dy, dx);
                            for (BlockPos q : new BlockPos[]{ cell, cell2 }) {
                                if (!level.getBlockState(q).is(McAssistantMod.VILLAGE_BOARD.get())) continue;
                                tried++;
                                List<CompoundTag> sent = new ArrayList<>();
                                FoundingEasy.TEST_SINK = (k, t) -> { if (k == FoundEasyPayload.OPEN) sent.add(t.copy()); };
                                p.setItemInHand(InteractionHand.MAIN_HAND, hand.copy());
                                Vec3 hit = Vec3.atCenterOf(q).add(0, 0, -0.5);
                                p.gameMode.useItemOn(p, level, p.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND,
                                    new BlockHitResult(hit, Direction.NORTH, q, false));
                                if (!sent.isEmpty() && sent.get(0).getString("mode").equals("board")) opened++;
                                else missed.add(q.subtract(board).toShortString() + " with " + hand.getItem());
                            }
                        }
                    }
                }
                Kit.log("[VT] cg04 board at " + board.toShortString() + ": " + opened + " of " + tried + " clicks opened the founding screen; missed " + missed);
                helper.assertTrue(tried > 0 && opened == tried, opened + " of " + tried + " clicks opened it; missed " + missed);
            } finally {
                FoundingEasy.TEST_SINK = null;
            }
            helper.succeed();
        });
    }
}
