package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.HousingMarket;
import com.jrpetty.mcassistant.entity.TerraceRows;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Wealth;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TerraceLots;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [house34] Terraces (entity/TerraceRows): rows of houses sharing their side walls along a street.
 *
 * <p>th01: a Stone Age village of sixteen with nobody housed wants a workers' terrace ahead of its next house, finds it a
 * long street front of its plan (village/TerraceLots), its front doors toward a street; raised there, the row stands
 * whole: every party wall solid from the street to the yard and from the footing to the eaves, the front a wall with no
 * gap in it but its doors and windows, each door on the street; four homes on the books with their four beds, and the
 * waiting households moved in. th02: a row on a street climbing a block a house, put up by a builder's own goal (its
 * ground work too): each house's floor at the street before its own door, each party wall unbroken from the lower
 * house's footing to the higher house's eaves, every floor dry and every room clear. th03: who lives where: a poor
 * household gets a workers' cottage and a wealthy one a town house, nobody poor is let a town house, and a tenant grown
 * rich moves up to one. th04: what the town builds: the grand terrace in a town with well-off households waiting, the
 * bay-fronted in a village for the comfortable, the workers' for the poor. th05: a row finished: each door its own
 * colour, never its neighbour's; the grand row's railings in iron; chimney pots; the number and name by every door.
 *
 * <p>On ground of its own: x 9100000 to 9400000, z 91000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TerraceGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 91000;

    private record Town(UUID id, Villages.Village v, BlockPos heart, List<VillageFolkEntity> folk) {}

    /** A village of so many folk in this age, each a farmer with an empty purse, nobody housed. */
    private static Town town(GameTestHelper helper, ServerLevel level, int x, int folk, Villages.Age age, int reach) {
        Kit.reset(level);
        TerraceRows.resetForTests();
        HousingMarket.resetForTests();
        level.setDayTime(24000L * 8 + 7000);
        Kit.hold(level, x, Z, reach);
        Kit.prepare(level, x, Z, reach);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        helper.assertTrue(v != null, "the village is on the books");
        List<VillageFolkEntity> all = new ArrayList<>();
        all.add(first);
        for (int i = 1; i < folk; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x + 2 * (i % 8), Z + 3 + 2 * (i / 8)), 0.0F);
            helper.assertTrue(f != null && id.equals(f.ownerId()), "folk " + i + " of the village");
            all.add(f);
        }
        Villages.ageForTests(id, age);
        Villages.storehouseFirstDoneForTests(id);
        for (VillageFolkEntity f : all) {
            // nothing carried, nothing worn: its standing is its purse (Wealth), which the test sets
            for (int i = 0; i < f.getInventoryItems().size(); i++) f.getInventoryItems().set(i, net.minecraft.world.item.ItemStack.EMPTY);
            for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
                f.setItemSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
            }
            TerraceRows.undressForTests(f);
            f.spend(f.purse());
            f.rentFree(false);
            f.setJob(StationTask.FARM);
        }
        return new Town(id, v, heart, all);
    }

    /** Every folk of the town with nothing carried, worn or held and an empty purse: poor, its standing (Wealth) for the test to set. */
    private static void strip(Town t) {
        for (VillageFolkEntity f : t.folk()) {
            for (int i = 0; i < f.getInventoryItems().size(); i++) f.getInventoryItems().set(i, net.minecraft.world.item.ItemStack.EMPTY);
            for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
                f.setItemSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
            }
            TerraceRows.undressForTests(f);
            f.spend(f.purse());
        }
    }

    /** A place in a row's drawing in the world. */
    static BlockPos cell(BlockPos anchor, Direction facing, int dx, int h, int dz) {
        return anchor.relative(facing.getClockWise(), dx).relative(facing, dz).above(h);
    }

    static boolean solid(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return !st.isAir() && !st.canBeReplaced();
    }

    /** A flat green from x0 to x1, z0 to z1 at standing level y, open sky above. */
    static void flat(ServerLevel level, int x0, int x1, int z0, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int h = y - 4; h <= y + 30; h++) {
                    level.setBlock(new BlockPos(x, h, z), h < y - 1 ? Blocks.DIRT.defaultBlockState() : h == y - 1
                        ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
    }

    // ============================================================ th01: planned on a long street front, built whole

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "th01_row_planned_and_built")
    public static void th01_row_planned_and_built(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9100000;
        Town t = town(helper, level, x, 16, Villages.Age.STONE, 80);
        UUID id = t.id();
        helper.runAtTickTime(20, () -> {
            Kit.Expect ok = new Kit.Expect();
            List<String> wanted = Villages.projectsWanted(id);
            int row = wanted.indexOf("terrace"), house = wanted.indexOf("house");
            Kit.log("th01 wanted: " + wanted + " | why: " + Villages.whyBuild(id, "terrace"));
            ok.that(row >= 0 && (house < 0 || row < house), "a workers' terrace ahead of the next house: " + wanted);
            Villages.Site site = Villages.siteFor(level, id, "terrace");
            Kit.log("th01 site: " + site + " | " + Villages.lotReport(id));
            ok.that(site != null, "a lot for it");
            if (site == null) { helper.fail(ok.summary()); return; }
            int ox = site.anchor().getX() - t.heart().getX(), oz = site.anchor().getZ() - t.heart().getZ();
            TownPlan.Lot lot = null;
            for (TownPlan.Lot l : TerraceLots.lots(21)) if (l.x() == ox && l.z() == oz) lot = l;
            ok.that(lot != null, "on one of the plan's long street fronts: " + ox + ", " + oz + (lot == null ? "" : " (" + lot.use() + ")"));
            BlockPos a = site.anchor();
            Direction f = site.facing();
            int[] street = { ox - 6 * f.getStepX(), oz - 6 * f.getStepZ() };
            ok.that(TownPlan.isStreet(street[0], street[1]), "its front toward a street: " + street[0] + ", " + street[1]);
            // raised there, as the builders would leave it
            TerraceRows.stampForTests(level, "terrace", a, f, TerraceRows.steps(a), id);
            Ledger.built(id, "terrace", a, f);
            Ledger.Building b = new Ledger.Building("terrace", a, f);
            // every party wall solid from the street to the back, footing to eaves
            int gaps = 0;
            String firstGap = "";
            for (int wall : new int[]{ -10, -5, 0, 5, 10 }) {
                for (int dz = -5; dz <= 2; dz++) {
                    for (int h = -1; h <= 6; h++) {
                        BlockPos p = cell(a, f, wall, h, dz);
                        boolean window = level.getBlockState(p).is(Blocks.GLASS_PANE) || level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock;
                        if (!solid(level, p) && !(Math.abs(wall) == 10 && window)) { gaps++; if (firstGap.isEmpty()) firstGap = wall + "," + h + "," + dz; }
                    }
                }
            }
            ok.that(gaps == 0, "the party walls whole, footing to eaves: " + gaps + " gaps" + (firstGap.isEmpty() ? "" : " (first at " + firstGap + ")"));
            // the front: no gap in it but doors and windows, along the whole row
            int holes = 0;
            for (int dx = -10; dx <= 10; dx++) {
                for (int h = 0; h <= 6; h++) {
                    BlockState st = level.getBlockState(cell(a, f, dx, h, -5));
                    if (st.isAir()) holes++;
                }
            }
            ok.that(holes == 0, "the front continuous, nothing open in it but its doors and windows: " + holes + " holes");
            // the roofline continuous: a roof over every column of the row
            int open = 0;
            for (int dx = -10; dx <= 10; dx++) for (int dz = -4; dz <= 1; dz++) if (!solid(level, cell(a, f, dx, 7, dz))) open++;
            ok.that(open == 0, "a roof (and the ceiling under it) over every column: " + open + " open");
            // each front door on the street
            List<BlockPos> houses = TerraceRows.housesForTests(b);
            ok.that(houses.size() == 4, "four houses: " + houses.size());
            for (int i = 0; i < houses.size(); i++) {
                BlockPos door = TerraceRows.doorForTests(b, i);
                BlockPos out = door.relative(f.getOpposite());
                int sx = out.getX() - t.heart().getX(), sz = out.getZ() - t.heart().getZ();
                ok.that(level.getBlockState(door).getBlock() instanceof DoorBlock, "house " + (i + 1) + "'s front door hung at " + door);
                ok.that(level.getBlockState(out).isAir() && solid(level, out.below()) && TownPlan.isStreet(sx, sz),
                    "and it opens straight onto the street: " + out + " (" + sx + ", " + sz + ")");
            }
            // on the books, four beds each, the waiting households in
            Homes.tickForTests(level, t.v());
            int housed = 0;
            for (BlockPos h : houses) {
                int beds = Homes.bedsForTests(level, id, h).size();
                String tenure = Homes.tenureForTests(id, h);
                Kit.log("th01 " + TerraceRows.addressForTests(id, h) + ": " + beds + " beds, " + tenure + ", " + TerraceRows.tierForTests(id, h));
                ok.that(beds == 4, "four beds in " + TerraceRows.addressForTests(id, h) + ": " + beds);
                ok.that("WORKERS".equals(TerraceRows.tierForTests(id, h)), "a workers' cottage on the books");
                ok.that(TerraceRows.addressForTests(id, h).matches("No\\. [1-4], .* (Cottages|Row|Yard)"), "its address: " + TerraceRows.addressForTests(id, h));
                for (VillageFolkEntity fo : t.folk()) if (h.equals(Homes.homeOf(fo))) { housed++; break; }
            }
            ok.that(housed == 4, "a waiting household in each: " + housed);
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ============================================================ th02: a street that climbs

    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "th02_row_steps_down_the_hill")
    public static void th02_row_steps_down_the_hill(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9104000;
        Town t = town(helper, level, x, 2, Villages.Age.STONE, 80);
        UUID id = t.id();
        // the first long street front of the plan, out along an avenue, its street rising a block a house
        TownPlan.Lot lot = null;
        for (TownPlan.Lot l : TerraceLots.lots(21)) if (l.use().equals("row-long")) { lot = l; break; }
        int y0 = t.heart().getY();
        BlockPos a0 = new BlockPos(t.heart().getX() + lot.x(), y0, t.heart().getZ() + lot.z());
        Direction f = Villages.direction(lot.back());
        int[] wallsAt = { -10, -5, 0, 5, 10 };
        for (int dx = -14; dx <= 14; dx++) {
            int house = dx <= -5 ? 0 : dx <= 0 ? 1 : dx <= 5 ? 2 : 3;
            if (dx < -10) house = 0;
            if (dx > 10) house = 3;
            for (int dz = -9; dz <= 7; dz++) {
                BlockPos col = cell(a0.atY(0), f, dx, 0, dz);
                for (int y = y0 - 6; y < y0 + house; y++) level.setBlock(col.atY(y), Blocks.DIRT.defaultBlockState(), 2 | 16);
                level.setBlock(col.atY(y0 + house - 1), Blocks.GRASS_BLOCK.defaultBlockState(), 2 | 16);
                for (int y = y0 + house; y < y0 + 30; y++) level.setBlock(col.atY(y), Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        }
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            BlockPos a = TerraceRows.ground(level, id, "terrace", a0.getX(), a0.getZ(), f);
            int[] proposed = a == null ? null : TerraceRows.proposedForTests(id, a);
            Kit.log("th02 floor " + a + ", steps " + java.util.Arrays.toString(proposed));
            ok.that(a != null && a.getY() == y0, "the row's floor at the lowest house's street: " + a + " (want y " + y0 + ")");
            ok.that(proposed != null && java.util.Arrays.equals(proposed, new int[]{ 0, 1, 2, 3 }), "each house a step up: " + java.util.Arrays.toString(proposed));
            if (a == null || proposed == null) { helper.fail(ok.summary()); return; }
            TerraceRows.chosen(id, "terrace", new Villages.Site(a, f, 0));
            ok.that(java.util.Arrays.equals(TerraceRows.steps(a), new int[]{ 0, 1, 2, 3 }), "the steps kept with the row: " + java.util.Arrays.toString(TerraceRows.steps(a)));
            // raised by a builder's own goal: its plan read with the steps, its ground worked house by house
            VillageFolkEntity builder = t.folk().get(0);
            String said = BuildGoal.buildNowForTests(builder, "terrace", a, f, BuildWorldGameTests::stock);
            Kit.log("th02 the builder: " + said);
            ok.that(said.startsWith("Done") || said.contains("built"), "the builder finished it: " + said);
            Ledger.Building b = new Ledger.Building("terrace", a, f);
            for (int i = 0; i < 4; i++) {
                BlockPos door = TerraceRows.doorForTests(b, i);
                BlockPos out = door.relative(f.getOpposite());
                ok.that(door.getY() == y0 + i && level.getBlockState(door).getBlock() instanceof DoorBlock,
                    "house " + (i + 1) + "'s door at its own street's height: " + door.getY() + " (want " + (y0 + i) + ")");
                ok.that(solid(level, out.below()) && level.getBlockState(out).isAir() && level.getBlockState(out.above()).isAir(),
                    "level with the street before it, no step up or down: " + out);
                BlockPos hall = door.relative(f);
                ok.that(solid(level, hall.below()) && level.getBlockState(hall).isAir() && level.getBlockState(hall.above()).isAir(),
                    "the hall behind it floored and clear: " + hall);
            }
            // each party wall unbroken from the lower house's footing to the higher house's eaves
            int gaps = 0;
            String first = "";
            for (int w = 0; w < wallsAt.length; w++) {
                int lowH = w == 0 ? 0 : w - 1, highH = Math.min(3, w);
                for (int dz = -5; dz <= 2; dz++) {
                    for (int h = lowH - 1; h <= highH + 6; h++) {
                        BlockPos p = cell(a, f, wallsAt[w], h, dz);
                        BlockState st = level.getBlockState(p);
                        boolean glass = st.is(Blocks.GLASS_PANE);
                        if (!solid(level, p) && !(glass && (w == 0 || w == 4))) { gaps++; if (first.isEmpty()) first = wallsAt[w] + "," + h + "," + dz; }
                    }
                }
            }
            ok.that(gaps == 0, "the party walls unbroken through the steps: " + gaps + " gaps" + (first.isEmpty() ? "" : " (first at " + first + ")"));
            // the roof over every house at its own height, the lower roof running into the higher house's wall
            int open = 0;
            for (int i = 0; i < 4; i++) {
                int lo = i == 0 ? -9 : i == 1 ? -4 : i == 2 ? 1 : 6;
                for (int dx = lo; dx < lo + 4; dx++) for (int dz = -4; dz <= 1; dz++) if (!solid(level, cell(a, f, dx, 7 + i, dz))) open++;
            }
            ok.that(open == 0, "every house roofed at its own height: " + open + " open");
            // every room dry and clear: no earth left standing in a room
            int earth = 0;
            for (int i = 0; i < 4; i++) {
                int lo = i == 0 ? -9 : i == 1 ? -4 : i == 2 ? 1 : 6;
                for (int dx = lo; dx < lo + 4; dx++) for (int dz = -4; dz <= 1; dz++) for (int h = 0; h <= 2; h++) {
                    BlockState st = level.getBlockState(cell(a, f, dx, h + i, dz));
                    if (st.is(Blocks.DIRT) || st.is(Blocks.GRASS_BLOCK)) earth++;
                }
            }
            ok.that(earth == 0, "no earth left in the rooms: " + earth);
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ============================================================ th03: who lives where

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "th03_folk_by_their_standing")
    public static void th03_folk_by_their_standing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9108000;
        Town t = town(helper, level, x, 5, Villages.Age.IRON, 70);
        UUID id = t.id();
        int y = t.heart().getY();
        BlockPos workers = new BlockPos(x - 30, y, Z + 40), grand = new BlockPos(x + 30, y, Z + 40);
        helper.runAtTickTime(10, () -> {
            strip(t);
            Kit.Expect ok = new Kit.Expect();
            flat(level, workers.getX() - 12, workers.getX() + 12, workers.getZ() - 8, workers.getZ() + 8, y);
            flat(level, grand.getX() - 12, grand.getX() + 12, grand.getZ() - 8, grand.getZ() + 8, y);
            TerraceRows.stampForTests(level, "terrace", workers, Direction.SOUTH, new int[0], id);
            TerraceRows.stampForTests(level, "terrace3", grand, Direction.SOUTH, new int[0], id);
            Ledger.built(id, "terrace", workers, Direction.SOUTH);
            Ledger.built(id, "terrace3", grand, Direction.SOUTH);
            VillageFolkEntity poor = t.folk().get(0), rich = t.folk().get(1);
            rich.earn(400);
            Kit.log("th03 the poor one " + Wealth.tier(poor).label + ", the rich one " + Wealth.tier(rich).label);
            Homes.tickForTests(level, t.v());
            String poorTier = Homes.homeOf(poor) == null ? "" : TerraceRows.tierForTests(id, Homes.homeOf(poor));
            String richTier = Homes.homeOf(rich) == null ? "" : TerraceRows.tierForTests(id, Homes.homeOf(rich));
            Kit.log("th03 " + poor.displayNameCap() + " -> " + (Homes.homeOf(poor) == null ? "nowhere" : TerraceRows.addressForTests(id, Homes.homeOf(poor)))
                + "; " + rich.displayNameCap() + " -> " + (Homes.homeOf(rich) == null ? "nowhere" : TerraceRows.addressForTests(id, Homes.homeOf(rich))));
            ok.that("WORKERS".equals(poorTier), "a poor household in a workers' cottage: " + poorTier);
            ok.that("GRAND".equals(richTier), "a wealthy household in a town house: " + richTier);
            int poorInGrand = 0;
            for (VillageFolkEntity fo : t.folk()) {
                if (fo == rich) continue;
                BlockPos h = Homes.homeOf(fo);
                if (h != null && "GRAND".equals(TerraceRows.tierForTests(id, h))) poorInGrand++;
            }
            ok.that(poorInGrand == 0, "nobody poor let a town house: " + poorInGrand);
            // grown rich, a tenant of a cottage moves up
            poor.earn(400);
            TerraceRows.tickForTests(level, t.v());
            String now = Homes.homeOf(poor) == null ? "" : TerraceRows.tierForTests(id, Homes.homeOf(poor));
            Kit.log("th03 grown rich, " + poor.displayNameCap() + " -> " + (Homes.homeOf(poor) == null ? "nowhere" : TerraceRows.addressForTests(id, Homes.homeOf(poor))));
            ok.that("GRAND".equals(now), "grown rich, it moved up to a town house: " + now);
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ============================================================ th04: what the town builds

    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "th04_the_row_its_folk_want")
    public static void th04_the_row_its_folk_want(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9112000;
        Town t = town(helper, level, x, 32, Villages.Age.IRON, 60);
        UUID id = t.id();
        helper.runAtTickTime(10, () -> {
            strip(t);
            Kit.Expect ok = new Kit.Expect();
            Kit.log("th04 rank " + Villages.rank(id) + ", " + Villages.headcount(id) + " folk; a folk's standing: " + Wealth.line(t.folk().get(10))
                + " (worth " + Wealth.worth(t.folk().get(10)) + ", belongings " + Wealth.belongings(t.folk().get(10)) + ")");
            ok.that(Villages.rank(id) == Villages.Rank.TOWN, "a town: " + Villages.rank(id));
            String[] poor = TerraceRows.chooseForTests(id);
            Kit.log("th04 all poor: " + java.util.Arrays.toString(poor));
            ok.that(poor != null && poor[0].equals("terrace"), "all poor and waiting: a workers' terrace");
            for (int i = 0; i < 3; i++) t.folk().get(i).earn(50);
            String[] comfy = TerraceRows.chooseForTests(id);
            Kit.log("th04 three comfortable: " + java.util.Arrays.toString(comfy));
            ok.that(comfy != null && comfy[0].equals("terrace2"), "comfortable households waiting: a bay-fronted terrace");
            for (int i = 3; i < 6; i++) t.folk().get(i).earn(400);
            String[] rich = TerraceRows.chooseForTests(id);
            List<String> wanted = Villages.projectsWanted(id);
            Kit.log("th04 three wealthy too: " + java.util.Arrays.toString(rich) + " | wanted " + wanted);
            ok.that(rich != null && rich[0].equals("terrace3"), "well-off households waiting in a town: a grand terrace");
            String grand = wanted.contains("terrace5") ? "terrace5" : "terrace3";      // [house34c] a crescent where a block is free
            ok.that(wanted.contains(grand) && (wanted.indexOf("house") < 0 || wanted.indexOf(grand) < wanted.indexOf("house")),
                "on the builders' list ahead of the next house: " + wanted);
            // a village (not a town) builds no grand terrace however rich its folk
            Villages.ageForTests(id, Villages.Age.STONE);
            String[] village = TerraceRows.chooseForTests(id);
            Kit.log("th04 the same folk in a Stone Age village: " + java.util.Arrays.toString(village));
            ok.that(village != null && !village[0].equals("terrace3"), "no grand terrace in a village: " + java.util.Arrays.toString(village));
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ============================================================ th05: finished, house by house

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "th05_rows_finished")
    public static void th05_rows_finished(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9116000;
        Town t = town(helper, level, x, 2, Villages.Age.IRON, 70);
        UUID id = t.id();
        int y = t.heart().getY();
        String[] kinds = { "terrace", "terrace2", "terrace3" };
        BlockPos[] at = { new BlockPos(x - 40, y, Z + 40), new BlockPos(x, y, Z + 40), new BlockPos(x + 40, y, Z + 40) };
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            for (int k = 0; k < 3; k++) {
                flat(level, at[k].getX() - 13, at[k].getX() + 13, at[k].getZ() - 8, at[k].getZ() + 8, y);
                TerraceRows.stampForTests(level, kinds[k], at[k], Direction.SOUTH, new int[0], id);
                Ledger.built(id, kinds[k], at[k], Direction.SOUTH);
            }
            Homes.tickForTests(level, t.v());
            for (int k = 0; k < 3; k++) {
                Ledger.Building b = new Ledger.Building(kinds[k], at[k], Direction.SOUTH);
                int n = TerraceRows.dress(level, t.v(), b, true);
                List<BlockPos> houses = TerraceRows.housesForTests(b);
                String last = "";
                List<String> doors = new ArrayList<>();
                for (int i = 0; i < houses.size(); i++) {
                    BlockState d = level.getBlockState(TerraceRows.doorForTests(b, i));
                    String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(d.getBlock()).getPath();
                    doors.add(name);
                    ok.that(d.getBlock() instanceof DoorBlock && !name.equals(last), kinds[k] + " house " + (i + 1) + " its own door, not its neighbour's: " + name);
                    last = name;
                    String[] sign = TerraceRows.signForTests(id, b, i);
                    ok.that(sign[0].equals("No. " + (i + 1)), "its number by its door: " + String.join(" / ", sign));
                }
                int signs = 0, pots = 0, bars = 0, fences = 0;
                for (int dx = -11; dx <= 12; dx++) for (int dz = -7; dz <= 6; dz++) for (int h = 0; h <= 24; h++) {
                    BlockState st = level.getBlockState(cell(at[k], Direction.SOUTH, dx, h, dz));
                    if (st.getBlock() instanceof WallSignBlock) signs++;
                    if (st.is(Blocks.FLOWER_POT) || st.is(Blocks.DECORATED_POT) || st.is(Blocks.CAMPFIRE)) pots++;
                    if (st.is(Blocks.IRON_BARS)) bars++;
                    if (st.getBlock() instanceof net.minecraft.world.level.block.FenceBlock && dz <= -3) fences++;
                }
                Kit.log("th05 " + kinds[k] + ": " + n + " pieces; doors " + doors + "; " + signs + " signs, " + pots + " chimney pots, " + bars + " iron bars, "
                    + fences + " fences left");
                ok.that(signs == houses.size(), "a sign by every door: " + signs);
                ok.that(pots >= houses.size(), "a chimney pot (or a fire) for every house: " + pots);
                if (kinds[k].equals("terrace3")) ok.that(bars >= 15 && fences == 0, "the grand row's railings all in iron: " + bars + " bars, " + fences + " fences out front");
            }
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ============================================================ th06: a corner terrace, round where two streets meet

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "th06_corner_terrace")
    public static void th06_corner_terrace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9120000;
        Town t = town(helper, level, x, 2, Villages.Age.IRON, 70);
        UUID id = t.id();
        int y = t.heart().getY();
        BlockPos a = new BlockPos(x, y, Z + 40);
        Direction f = Direction.SOUTH;
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            flat(level, x - 16, x + 16, Z + 30, Z + 62, y);
            TerraceRows.stampForTests(level, "terrace4", a, f, new int[0], id);
            Ledger.built(id, "terrace4", a, f);
            Ledger.Building b = new Ledger.Building("terrace4", a, f);
            Homes.tickForTests(level, t.v());
            List<BlockPos> houses = TerraceRows.housesForTests(b);
            ok.that(houses.size() == 6, "six houses: " + houses.size());
            ok.that(new java.util.HashSet<>(houses).size() == houses.size(), "each on its own anchor: " + houses);
            for (int i = 0; i < houses.size(); i++) {
                BlockPos door = TerraceRows.doorForTests(b, i);
                Direction way = TerraceRows.facingForTests(b, i);
                BlockPos out = door.relative(way.getOpposite());
                ok.that(way == (i < 4 ? f : f.getCounterClockWise()), "house " + (i + 1) + " faces " + (i < 4 ? "the first street" : "the side street") + ": " + way);
                ok.that(level.getBlockState(door).getBlock() instanceof DoorBlock, "house " + (i + 1) + "'s front door hung at " + door);
                ok.that(level.getBlockState(out).isAir() && level.getBlockState(out.above()).isAir(), "and it opens onto its street: " + out);
                for (int j = 0; j < houses.size(); j++) {
                    boolean in = TerraceRows.insideForTests(b, j, houses.get(i));
                    ok.that(in == (i == j), "house " + (i + 1) + "'s hall " + (i == j ? "in its own house" : "not in house " + (j + 1)));
                }
                int beds = Homes.bedsForTests(level, id, houses.get(i)).size();
                ok.that(beds == 4, "four beds in house " + (i + 1) + ": " + beds);
            }
            // the corner house: a front door on each street
            BlockPos side = cell(a, f, 11, 0, -1);
            ok.that(level.getBlockState(side).getBlock() instanceof DoorBlock, "the corner house's door on the side street at " + side);
            ok.that(level.getBlockState(side.relative(f.getClockWise())).isAir(), "opening onto the side street");
            // one roof turning the corner: hipped at the outer corner (no gable over either street), a roof over every room
            int gable = 0;
            for (int h = 8; h <= 10; h++) {
                for (int dz = -4; dz <= 1; dz++) if (level.getBlockState(cell(a, f, 11, h, dz)).is(Blocks.BRICKS)) gable++;
                for (int dx = 5; dx <= 10; dx++) if (level.getBlockState(cell(a, f, dx, h, -5)).is(Blocks.BRICKS)) gable++;
            }
            ok.that(gable == 0, "hipped at the corner: no gable over either street (" + gable + " bricks)");
            int open = 0;
            String firstOpen = "";
            for (int dx = -10; dx <= 11; dx++) for (int dz = -4; dz <= 14; dz++) {
                boolean roofed = dz <= 1 || (dx >= 5 && dx <= 10);
                if (!roofed) continue;
                boolean any = false;
                for (int h = 7; h <= 10; h++) if (solid(level, cell(a, f, dx, h, dz))) any = true;
                if (!any) { open++; if (firstOpen.isEmpty()) firstOpen = dx + "," + dz; }
            }
            ok.that(open == 0, "a roof over every room of the L: " + open + " open" + (firstOpen.isEmpty() ? "" : " (first " + firstOpen + ")"));
            // finished: a sign by every door, a pot on every flue
            int n = TerraceRows.dress(level, t.v(), b, true);
            int signs = 0, pots = 0;
            for (int dx = -12; dx <= 13; dx++) for (int dz = -7; dz <= 17; dz++) for (int h = 0; h <= 14; h++) {
                BlockState st = level.getBlockState(cell(a, f, dx, h, dz));
                if (st.getBlock() instanceof WallSignBlock) signs++;
                if (st.is(Blocks.FLOWER_POT) || st.is(Blocks.DECORATED_POT) || st.is(Blocks.CAMPFIRE)) pots++;
            }
            Kit.log("th06 " + n + " pieces; " + signs + " signs, " + pots + " pots; addresses " + TerraceRows.addressForTests(id, houses.get(3))
                + " / " + TerraceRows.addressForTests(id, houses.get(5)));
            ok.that(signs == 6, "a sign by every door: " + signs);
            ok.that(pots >= 6, "a chimney pot for every house: " + pots);
            ok.that(TerraceRows.addressForTests(id, houses.get(5)).matches("No\\. 6, .* (Corner|Buildings)"), "its address: " + TerraceRows.addressForTests(id, houses.get(5)));
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ============================================================ th07: a crescent round its lawn

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "th07_crescent")
    public static void th07_crescent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9124000;
        Town t = town(helper, level, x, 2, Villages.Age.IRON, 70);
        UUID id = t.id();
        int y = t.heart().getY();
        BlockPos a = new BlockPos(x, y, Z + 40);
        Direction f = Direction.SOUTH;
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            flat(level, x - 16, x + 16, Z + 30, Z + 60, y);
            TerraceRows.stampForTests(level, "terrace5", a, f, new int[0], id);
            Ledger.built(id, "terrace5", a, f);
            Ledger.Building b = new Ledger.Building("terrace5", a, f);
            Homes.tickForTests(level, t.v());
            List<BlockPos> houses = TerraceRows.housesForTests(b);
            ok.that(houses.size() == 3, "three houses: " + houses.size());
            for (int i = 0; i < houses.size(); i++) {
                BlockPos door = TerraceRows.doorForTests(b, i);
                ok.that(level.getBlockState(door).getBlock() instanceof DoorBlock, "house " + (i + 1) + "'s front door hung at " + door);
                ok.that(level.getBlockState(door.relative(f.getOpposite())).isAir(), "its steps up to it from the area");
                int beds = Homes.bedsForTests(level, id, houses.get(i)).size();
                ok.that(beds >= 4, "a town house's beds in house " + (i + 1) + ": " + beds);
                ok.that("GRAND".equals(TerraceRows.tierForTests(id, houses.get(i))), "a town house on the books");
            }
            // the arc: the first floor's front further back toward the middle, a block at a time
            int[] front = new int[3];
            int[] cols = { -9, -5, 0 };
            for (int k = 0; k < 3; k++) {
                front[k] = 99;
                for (int dz = -5; dz <= 8; dz++) if (solid(level, cell(a, f, cols[k], 8, dz))) { front[k] = dz; break; }
            }
            ok.that(front[0] < front[1] && front[1] < front[2] && front[2] - front[0] <= 3, "the fronts on an arc, the ends forward: " + java.util.Arrays.toString(front));
            int mirror = 99;
            for (int dz = -5; dz <= 8; dz++) if (solid(level, cell(a, f, 1 - cols[1], 8, dz))) { mirror = dz; break; }
            ok.that(mirror == front[1], "the same each side of the middle: " + mirror + " and " + front[1]);
            // finished: the lawn's railings and the areas' in iron, a sign by every door
            int n = TerraceRows.dress(level, t.v(), b, true);
            int bars = 0, fences = 0, street = 0, signs = 0;
            for (int dx = -11; dx <= 12; dx++) for (int dz = -6; dz <= 14; dz++) for (int h = 0; h <= 24; h++) {
                BlockState st = level.getBlockState(cell(a, f, dx, h, dz));
                if (st.is(Blocks.IRON_BARS)) { bars++; if (dz == -5) street++; }
                if (st.getBlock() instanceof net.minecraft.world.level.block.FenceBlock && dz <= 3) fences++;
                if (st.getBlock() instanceof WallSignBlock) signs++;
            }
            Kit.log("th07 " + n + " pieces; " + bars + " iron bars (" + street + " along the street), " + fences + " fences; "
                + signs + " signs; " + TerraceRows.addressForTests(id, houses.get(0)));
            ok.that(street >= 15 && fences == 0, "the railings all in iron, the lawn's along the street too: " + street + " there, " + fences + " fences");
            ok.that(signs == 3, "a sign by every door: " + signs);
            ok.that(TerraceRows.addressForTests(id, houses.get(0)).matches("No\\. 1, .* (Crescent|Circus)"), "a crescent's address: "
                + TerraceRows.addressForTests(id, houses.get(0)));
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ============================================================ th08: the plan chooses the corner and the crescent where they fit

    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "th08_forms_where_they_fit")
    public static void th08_forms_where_they_fit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9128000;
        Town t = town(helper, level, x, 32, Villages.Age.IRON, 80);
        UUID id = t.id();
        helper.runAtTickTime(20, () -> {
            Kit.Expect ok = new Kit.Expect();
            ok.that(Villages.rank(id) == Villages.Rank.TOWN, "a town: " + Villages.rank(id));
            // the well-off of a town: a crescent, on a whole block of the plan
            ok.that("terrace5".equals(TerraceRows.formForTests(id, "GRAND")), "a crescent for the well-off of a town with a block free: "
                + TerraceRows.formForTests(id, "GRAND"));
            Villages.Site cres = Villages.siteFor(level, id, "terrace5");
            Kit.log("th08 crescent site: " + cres + " | " + Villages.lotReport(id));
            ok.that(cres != null, "a block for it");
            if (cres != null) {
                int ox = cres.anchor().getX() - t.heart().getX(), oz = cres.anchor().getZ() - t.heart().getZ();
                TownPlan.Lot lot = null;
                for (TownPlan.Lot l : TerraceLots.blocks()) if (l.x() == ox && l.z() == oz) lot = l;
                ok.that(lot != null && lot.cells().length == 4, "on a whole block of the plan: " + ox + ", " + oz);
                Ledger.built(id, "terrace5", cres.anchor(), cres.facing());
            }
            ok.that("terrace3".equals(TerraceRows.formForTests(id, "GRAND")), "one crescent to a town; then the grand terrace: "
                + TerraceRows.formForTests(id, "GRAND"));
            // the working households: the first row straight, the next round a corner where three lots of a block are free
            ok.that("terrace".equals(TerraceRows.formForTests(id, "WORKERS")), "the first workers' row straight: " + TerraceRows.formForTests(id, "WORKERS"));
            Ledger.built(id, "terrace", new BlockPos(x + 2000, t.heart().getY(), Z), Direction.NORTH);
            ok.that("terrace4".equals(TerraceRows.formForTests(id, "WORKERS")), "the next round a corner: " + TerraceRows.formForTests(id, "WORKERS"));
            Villages.Site cor = Villages.siteFor(level, id, "terrace4");
            Kit.log("th08 corner site: " + cor + " | " + Villages.lotReport(id));
            ok.that(cor != null, "a corner for it");
            if (cor != null) {
                int ox = cor.anchor().getX() - t.heart().getX(), oz = cor.anchor().getZ() - t.heart().getZ();
                TownPlan.Lot lot = null;
                for (TownPlan.Lot l : TerraceLots.corners()) if (l.x() == ox && l.z() == oz) lot = l;
                ok.that(lot != null && lot.cells().length == 3, "on three lots of a corner of a block: " + ox + ", " + oz);
                if (lot != null) {
                    // its side street down its right: the street before the side houses' doors
                    Direction r = cor.facing().getClockWise();
                    int sx = ox + 12 * r.getStepX(), sz = oz + 12 * r.getStepZ();
                    ok.that(TownPlan.isStreet(sx, sz), "the side street down its right: " + sx + ", " + sz);
                    int fx = ox - 6 * cor.facing().getStepX(), fz = oz - 6 * cor.facing().getStepZ();
                    ok.that(TownPlan.isStreet(fx, fz), "the first street before it: " + fx + ", " + fz);
                }
            }
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ============================================================ th09: the finishing asks the town for what it lacks

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "th09_finishing_asks_for_its_stock")
    public static void th09_finishing_asks_for_its_stock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 9132000;
        Town t = town(helper, level, x, 2, Villages.Age.IRON, 70);
        UUID id = t.id();
        int y = t.heart().getY();
        BlockPos a = new BlockPos(x, y, Z + 40);
        Direction f = Direction.SOUTH;
        helper.runAtTickTime(10, () -> {
            Kit.Expect ok = new Kit.Expect();
            flat(level, x - 13, x + 13, Z + 32, Z + 48, y);
            TerraceRows.stampForTests(level, "terrace3", a, f, new int[0], id);
            Ledger.built(id, "terrace3", a, f);
            Ledger.Building b = new Ledger.Building("terrace3", a, f);
            // as the builders leave it when the stores are short: its brick in cobble, its roof in timber
            int brick = 0, roof = 0;
            for (BuildGoal.Placement pl : BuildGoal.plan("terrace3", a, f, 13)) {
                if (pl.part() != BuildGoal.Part.BLOCK) continue;
                BlockState there = level.getBlockState(pl.pos());
                if (there.is(Blocks.BRICKS)) { level.setBlock(pl.pos(), Blocks.COBBLESTONE.defaultBlockState(), 2); brick++; }
                else if (there.is(Blocks.DEEPSLATE_TILE_STAIRS)) {
                    level.setBlock(pl.pos(), Blocks.OAK_STAIRS.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.StairBlock.FACING, there.getValue(net.minecraft.world.level.block.StairBlock.FACING)), 2);
                    roof++;
                }
            }
            Homes.tickForTests(level, t.v());
            var wants = TerraceRows.wantsForTests(level, t.v());
            int wb = wants.getOrDefault(net.minecraft.world.item.Items.BRICKS, 0), wt = wants.getOrDefault(net.minecraft.world.item.Items.DEEPSLATE_TILES, 0);
            Kit.log("th09 laid in cobble " + brick + ", timber roof " + roof + "; wants " + wants);
            ok.that(wb >= brick / 2 && wb > 0, "the brick it wants for its fronts: " + wb + " (of " + brick + " in cobble)");
            ok.that(wt > 0, "the slate it wants for its roof: " + wt);
            int shortBricks = com.jrpetty.mcassistant.entity.Masonry.shortOfForTests(level, t.v(), net.minecraft.world.item.Items.BRICKS);
            ok.that(shortBricks >= Math.min(256, wb), "the mason asked for it (Masonry.keep): short of " + shortBricks + " blocks of brick");
            // nothing in the stores to fire or dress: the traders sell it the makings in the morning
            Ledger.addCoins(id, 2000);
            int clay0 = com.jrpetty.mcassistant.entity.Market.stock(level, id, s -> s.is(net.minecraft.world.item.Items.CLAY_BALL));
            int deep0 = com.jrpetty.mcassistant.entity.Market.stock(level, id, s -> s.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE));
            TerraceRows.buyForFinishing(level, t.v(), level.getDayTime() / 24000L);
            int clay = com.jrpetty.mcassistant.entity.Market.stock(level, id, s -> s.is(net.minecraft.world.item.Items.CLAY_BALL));
            int deep = com.jrpetty.mcassistant.entity.Market.stock(level, id, s -> s.is(net.minecraft.world.item.Items.COBBLED_DEEPSLATE));
            Kit.log("th09 bought: clay " + clay0 + " -> " + clay + ", cobbled deepslate " + deep0 + " -> " + deep + "; treasury " + Ledger.coins(id));
            ok.that(clay > clay0, "clay bought for the kiln: " + clay0 + " -> " + clay);
            ok.that(deep > deep0, "cobbled deepslate bought for the slate: " + deep0 + " -> " + deep);
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }
}
