package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.HouseSigns;
import com.jrpetty.mcassistant.entity.TownLife;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * [paths] The town's ways: a sign with its household's name by every home's door, a clear, paved and lit way from every
 * door to the street, folk that never stay stuck (the spot looked at, and the town's own fault in it mended), and lots
 * kept apart. All at x 1700000 and on, z 66000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PathsGameTests {

    private static final String EMPTY = "empty";
    static final int Z = 66000;

    /** A village's first folk on clean ground at x, z, in broad day. */
    static VillageFolkEntity founder(GameTestHelper helper, int x, int z, int radius) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(24000L * 8 + 7000);
        Kit.hold(level, x, z, radius);
        Kit.prepare(level, x, z, radius);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x, z), 0.0F);
        helper.assertTrue(f != null, "a village");
        return f;
    }

    /** The sign within reach of this door, its four lines; null if none. */
    @Nullable
    static String[] signBy(ServerLevel level, BlockPos door) {
        for (BlockPos p : BlockPos.betweenClosed(door.offset(-2, 0, -2), door.offset(2, 2, 2))) {
            if (level.getBlockEntity(p) instanceof SignBlockEntity sign && level.getBlockState(p).getBlock() instanceof WallSignBlock) {
                String[] out = new String[4];
                for (int i = 0; i < 4; i++) out[i] = sign.getFrontText().getMessage(i, false).getString();
                return out;
            }
        }
        return null;
    }

    @Nullable
    static BlockPos signAt(ServerLevel level, BlockPos door) {
        for (BlockPos p : BlockPos.betweenClosed(door.offset(-2, 0, -2), door.offset(2, 2, 2))) {
            if (level.getBlockState(p).getBlock() instanceof WallSignBlock) return p.immutable();
        }
        return null;
    }

    // ============================================================ the name by the door

    /**
     * A house on a street, a farmer moves in: the sign by its door says who (the farmer's name), what (Farmer), and the
     * house's number. The sign is of the town's own wood, beside the door, not on a window. Knocked down, it is put back on
     * the round. The farmer moves out: "To let", the number kept.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pa01_house_sign")
    public static void pa01_house_sign(GameTestHelper helper) {
        int x = 1700000, z = Z;
        VillageFolkEntity f = founder(helper, x, z, 48);
        ServerLevel level = helper.getLevel();
        UUID id = f.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, z);
        helper.runAtTickTime(10, () -> {
            TownPlan.Lot lot = null;
            for (TownPlan.Lot l : TownPlan.candidates("house")) {
                if (l.kind() == TownPlan.Kind.LOT && l.back() == TownPlan.NORTH && l.distance() < 40) { lot = l; break; }
            }
            helper.assertTrue(lot != null, "a lot for a house");
            BlockPos at = Kit.surface(level, heart.getX() + lot.x(), heart.getZ() + lot.z());
            Direction back = Villages.direction(lot.back());
            BuildGoal.stamp(level, "house", at, back, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "house", at, back);
            Villages.recountBeds(id);
            Ledger.Building house = new Ledger.Building("house", at, back);
            f.setStation(heart.offset(30, 0, 30), StationTask.FARM);
            Homes.tickForTests(level, v);
            helper.assertTrue(at.equals(Homes.homeOf(f)), "the farmer lives in the house: " + Homes.homeOf(f));
            BlockPos door = TownLife.fittings(house).door();
            helper.assertTrue(door != null, "the house has a door");
            TownLife.dressNow(level, id, heart, List.of(house), List.of());
            String[] sign = signBy(level, door);
            Kit.log("pa01 the sign by " + f.displayNameCap() + "'s door: " + (sign == null ? "none" : String.join(" / ", sign)));
            helper.assertTrue(sign != null, "a sign by the door");
            helper.assertTrue(sign[0].equals(f.displayNameCap()), "the farmer's name on its first line: " + sign[0]);
            helper.assertTrue(sign[1].equals("Farmer"), "its trade on the second: " + sign[1]);
            helper.assertTrue(sign[2].startsWith("No. "), "the house's number on the third: " + sign[2]);
            BlockPos spot = signAt(level, door);
            helper.assertTrue(spot != null && level.getBlockState(spot).is(HouseSigns.signOf(id)), "a sign of the town's wood ("
                + HouseSigns.signOf(id) + "): " + (spot == null ? "-" : level.getBlockState(spot)));
            Direction front = back.getOpposite();
            helper.assertTrue(spot.getY() == door.getY() + 1 && spot.relative(front.getOpposite()).distManhattan(door.above()) == 1,
                "beside the door at head height, on the wall: sign " + spot.toShortString() + ", door " + door.toShortString());
            helper.assertTrue(!HouseSigns.window(level.getBlockState(spot.relative(front.getOpposite()))), "not on a window");
            helper.assertTrue(level.getBlockState(door.relative(front)).getCollisionShape(level, door.relative(front)).isEmpty(),
                "the doorway's approach is clear");
            // Knocked down: put back on the round.
            level.setBlock(spot, Blocks.AIR.defaultBlockState(), 3);
            TownLife.dressNow(level, id, heart, List.of(house), List.of());
            String[] back2 = signBy(level, door);
            helper.assertTrue(back2 != null && back2[0].equals(f.displayNameCap()), "the sign put back");
            // The farmer moves out.
            Homes.left(id, f.getUUID());
            TownLife.dressNow(level, id, heart, List.of(house), List.of());
            String[] empty = signBy(level, door);
            Kit.log("pa01 after the farmer moved out: " + (empty == null ? "none" : String.join(" / ", empty)));
            helper.assertTrue(empty != null && empty[0].equals("To let") && empty[1].isEmpty(), "\"To let\" when it stands empty");
            helper.assertTrue(empty[2].equals(sign[2]), "the number kept: " + empty[2]);
            helper.succeed();
        });
    }
    // ============================================================ the way to a door

    /**
     * A house on a street lot, the grass before its door and its lanterns taken down (a dark way): on the town's rounds
     * the way from the door to the street is worn into a path, and a lamp (a torch on a post of the town's wood, before
     * the Iron Age) goes up beside it on the lot, never on the street nor the path; the door is reached from the heart.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pa02_door_path")
    public static void pa02_door_path(GameTestHelper helper) {
        int x = 1702000, z = Z;
        VillageFolkEntity f = founder(helper, x, z, 48);
        ServerLevel level = helper.getLevel();
        UUID id = f.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, z);
        helper.runAtTickTime(10, () -> {
            TownPlan.Lot lot = null;
            for (TownPlan.Lot l : TownPlan.candidates("house")) {
                if (l.kind() == TownPlan.Kind.LOT && l.back() == TownPlan.NORTH && l.distance() < 40) { lot = l; break; }
            }
            helper.assertTrue(lot != null, "a lot for a house");
            BlockPos at = Kit.surface(level, heart.getX() + lot.x(), heart.getZ() + lot.z());
            Direction back = Villages.direction(lot.back());
            BuildGoal.stamp(level, "house", at, back, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "house", at, back);
            Ledger.Building house = new Ledger.Building("house", at, back);
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-7, -1, -7), at.offset(7, 8, 7))) {
                if (level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.LanternBlock
                        || level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.TorchBlock)
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            }
            BlockPos chest = heart.offset(3, 0, -3);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            com.jrpetty.mcassistant.entity.ZoneChests.mark(level, chest);
            net.minecraft.world.Container stores = (net.minecraft.world.Container) level.getBlockEntity(chest);
            stores.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TORCH, 8));
            stores.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 16));
            stores.setItem(2, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 16));
            List<com.jrpetty.mcassistant.entity.DoorWays.Door> doors = com.jrpetty.mcassistant.entity.DoorWays.doorsOf(level, house);
            helper.assertTrue(doors.size() == 1, "the house's door: " + doors);
            com.jrpetty.mcassistant.entity.DoorWays.Door d = doors.get(0);
            List<BlockPos> way = com.jrpetty.mcassistant.entity.DoorWays.pathToForTests(v, d);
            helper.assertTrue(way != null && !way.isEmpty(), "a street straight out of the door: " + way);
            helper.assertTrue(!com.jrpetty.mcassistant.entity.DoorWays.lit(level, d, way), "the way is dark to begin with");
            int work = 0;
            for (int i = 0; i < 12; i++) {
                com.jrpetty.mcassistant.entity.DoorWays.mend(level, v, d, 8);
                if (com.jrpetty.mcassistant.entity.DoorWays.approach(level, v, d, 8) > 0) work++;
            }
            StringBuilder floors = new StringBuilder();
            int paved = 0;
            for (BlockPos c : way) {
                BlockPos feet = com.jrpetty.mcassistant.entity.Footways.standNear(level, c.atY(d.pos().getY()), 1, 6);
                net.minecraft.world.level.block.state.BlockState fl = level.getBlockState(feet.below());
                floors.append(feet.below().toShortString()).append('=').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(fl.getBlock()).getPath()).append(' ');
                if (!com.jrpetty.mcassistant.entity.DoorWays.earthForTests(fl)) paved++;
            }
            BlockPos lamp = null;
            for (BlockPos p : BlockPos.betweenClosed(d.pos().offset(-3, -1, -3).relative(d.out(), 1), d.pos().offset(3, 2, 3).relative(d.out(), way.size()))) {
                if (level.getBlockState(p).is(Blocks.TORCH) && level.getBlockState(p.below()).is(net.minecraft.tags.BlockTags.FENCES)) { lamp = p.immutable(); break; }
            }
            Kit.log("pa02 " + work + " rounds of work; the way " + floors + "; lamp " + lamp + "; lit " + com.jrpetty.mcassistant.entity.DoorWays.lit(level, d, way));
            helper.assertTrue(paved == way.size(), "every place on the way to the street worn into a path: " + floors);
            helper.assertTrue(lamp != null, "a lamp by the path");
            helper.assertTrue(!com.jrpetty.mcassistant.entity.Footways.street(v, lamp.getX(), lamp.getZ()) && !way.contains(lamp.atY(way.get(0).getY())),
                "the lamp off the street and the path");
            var walk = com.jrpetty.mcassistant.entity.Footways.reach(level, heart, 60, 16, 200000);
            helper.assertTrue(walk.contains(d.pos().asLong()), "the door reached from the heart");
            // The light is worked out at the end of the tick: looked at on the next.
            helper.runAfterDelay(2, () -> {
                helper.assertTrue(com.jrpetty.mcassistant.entity.DoorWays.lit(level, d, way), "the way lit");
                helper.succeed();
            });
        });
    }

    // ============================================================ stuck

    /**
     * A folk on the avenue, going up onto a bank two blocks high that has no step anywhere: its path comes up short and it
     * gives up there. The spot is written in the town's books ("a missing step"), and the folk cuts a step into the bank
     * where it stood: the bank's top block out, a step it can jump.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pa03_stuck_step")
    public static void pa03_stuck_step(GameTestHelper helper) {
        int x = 1703000, z = Z;
        VillageFolkEntity f = founder(helper, x, z, 48);
        ServerLevel level = helper.getLevel();
        UUID id = f.ownerId();
        BlockPos heart = Kit.surface(level, x, z);
        helper.runAtTickTime(10, () -> {
            int g = heart.getY();
            for (int dx = -6; dx <= 6; dx++) for (int dz = 22; dz <= 30; dz++) for (int h = 0; h <= 1; h++)
                level.setBlock(heart.offset(dx, h, dz), Blocks.DIRT.defaultBlockState(), 3);
            BlockPos stand = heart.offset(0, 0, 21);
            f.moveTo(stand.getX() + 0.5, g, stand.getZ() + 0.5);
            f.getNavigation().stop();
            var path = f.getNavigation().createPath(heart.offset(0, 2, 25), 0);
            if (path != null) f.getNavigation().moveTo(path, 1.0);
            Kit.log("pa03 path " + (path == null ? "none" : path.getNodeCount() + " nodes to " + path.getEndNode() + " reach " + path.canReach()));
            helper.assertTrue(path != null && !path.canReach(), "no way up the bank");
            com.jrpetty.mcassistant.entity.StuckSpots.gaveUp(f, f.blockPosition());
            List<com.jrpetty.mcassistant.entity.StuckSpots.Spot> spots = com.jrpetty.mcassistant.entity.StuckSpots.spots(id);
            Kit.log("pa03 spots " + spots + "; " + com.jrpetty.mcassistant.entity.StuckSpots.line(id));
            helper.assertTrue(spots.size() == 1 && spots.get(0).cause.equals("a missing step"), "the spot, and why: " + spots);
            helper.assertTrue(spots.get(0).mended == 1 && level.getBlockState(heart.offset(0, 1, 22)).isAir()
                && level.getBlockState(heart.offset(0, 0, 22)).is(Blocks.DIRT), "a step cut into the bank");
            helper.assertTrue(com.jrpetty.mcassistant.entity.StuckSpots.count(id, "paths.stuck") == 1
                && com.jrpetty.mcassistant.entity.StuckSpots.count(id, "paths.mended") == 1, "on the town's books");
            helper.succeed();
        });
    }

    /**
     * A folk shut in a glass box on the avenue, its path to a spot a few blocks off going nowhere: it is helped out (set
     * down beside where it was going, as a hand that cannot reach its plot is) and the town's books count it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "pa06_helped_out")
    public static void pa06_helped_out(GameTestHelper helper) {
        int x = 1706000, z = Z;
        VillageFolkEntity f = founder(helper, x, z, 48);
        ServerLevel level = helper.getLevel();
        UUID id = f.ownerId();
        BlockPos heart = Kit.surface(level, x, z);
        helper.runAtTickTime(10, () -> {
            BlockPos box = heart.offset(0, 0, 20);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int h = 0; h <= 2; h++)
                if (dx != 0 || dz != 0 || h == 2) level.setBlock(box.offset(dx, h, dz), Blocks.GLASS.defaultBlockState(), 3);
            f.moveTo(box.getX() + 0.5, box.getY(), box.getZ() + 0.5);
            BlockPos goal = heart.offset(0, 0, 27);
            f.getNavigation().stop();
            var path = f.getNavigation().createPath(goal, 0);
            if (path != null) f.getNavigation().moveTo(path, 1.0);
            helper.assertTrue(path != null && !path.canReach(), "no way out of the box");
            boolean helped = com.jrpetty.mcassistant.entity.StuckSpots.rescueForTests(level, f);
            Kit.log("pa06 helped out " + helped + ", now at " + f.blockPosition().toShortString() + "; " + com.jrpetty.mcassistant.entity.StuckSpots.line(id));
            helper.assertTrue(helped && f.blockPosition().distManhattan(goal) <= 4, "set down by where it was going: " + f.blockPosition().toShortString());
            helper.assertTrue(com.jrpetty.mcassistant.entity.StuckSpots.count(id, "paths.rescued") == 1, "on the town's books");
            helper.succeed();
        });
    }

    // ============================================================ lots apart

    /**
     * Two lots back to back. A villa fills its lot to the edge: a second villa on the lot behind it would stand wall to
     * wall with it, and is not sited there (Footways.crowds); a house, which leaves a block of its lot, is. The audit
     * counts two buildings touching.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "pa04_lots_apart")
    public static void pa04_lots_apart(GameTestHelper helper) {
        int x = 1704000, z = Z;
        VillageFolkEntity f = founder(helper, x, z, 24);
        ServerLevel level = helper.getLevel();
        UUID id = f.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, z);
        TownPlan.Lot a = null, b = null;
        for (TownPlan.Lot l : TownPlan.lots()) {
            if (l.kind() != TownPlan.Kind.LOT) continue;
            for (TownPlan.Lot m : TownPlan.lots()) {
                if (m.kind() == TownPlan.Kind.LOT && m.x() == l.x() && m.z() == l.z() + TownPlan.LOT && l.back() != m.back()) { a = l; b = m; break; }
            }
            if (a != null) break;
        }
        helper.assertTrue(a != null, "two lots back to back");
        BlockPos at = heart.offset(a.x(), 0, a.z()), bt = heart.offset(b.x(), 0, b.z());
        Direction ab = Villages.direction(a.back()), bb = Villages.direction(b.back());
        Ledger.built(id, "villa", at, ab);
        boolean villa = com.jrpetty.mcassistant.entity.Footways.crowds(id, "villa", bt.getX(), bt.getZ(), bb);
        boolean house = com.jrpetty.mcassistant.entity.Footways.crowds(id, "house", bt.getX(), bt.getZ(), bb);
        Kit.log("pa04 lots " + a + " and " + b + ": a villa behind a villa crowds it " + villa + ", a house " + house);
        helper.assertTrue(villa, "a villa wall to wall with the villa behind it: not sited");
        helper.assertTrue(!house, "a house behind the villa keeps a way between: sited");
        Ledger.built(id, "villa", bt, bb);
        var r = com.jrpetty.mcassistant.entity.WayAudit.check(level, v);
        Kit.log("pa04 " + r.lines());
        helper.assertTrue(r.touching() == 1 && r.overlaps() == 0, "the audit sees the two touching: " + r.crowding());
        helper.succeed();
    }

    // ============================================================ walking

    /** A box with a wooden door is walked into; one with an iron door (and no plate) is not. */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "pa05_reach")
    public static void pa05_reach(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        int x = 1705000, z = Z;
        Kit.hold(level, x, z, 24);
        Kit.prepare(level, x, z, 24);
        BlockPos g = Kit.surface(level, x, z);
        BlockPos wood = box(level, g.offset(-6, 0, 0), Blocks.OAK_DOOR);
        BlockPos iron = box(level, g.offset(6, 0, 0), Blocks.IRON_DOOR);
        var walk = com.jrpetty.mcassistant.entity.Footways.reach(level, g.offset(0, 0, 8), 20, 8, 100000);
        Kit.log("pa05 walked " + walk.size() + " places; in the wooden one " + walk.contains(wood.asLong()) + ", the iron one " + walk.contains(iron.asLong()));
        helper.assertTrue(walk.contains(wood.asLong()), "into the box with a wooden door");
        helper.assertTrue(!walk.contains(iron.asLong()), "not into the one with an iron door");
        helper.succeed();
    }

    /** A stone box three across inside, its door on the south; returns the middle of its floor (where one would stand). */
    private static BlockPos box(ServerLevel level, BlockPos c, net.minecraft.world.level.block.Block door) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int h = 0; h <= 3; h++) {
            boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2 || h == 3;
            level.setBlock(c.offset(dx, h, dz), wall ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        }
        var st = door.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.SOUTH);
        level.setBlock(c.offset(0, 0, 2), st.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), 2);
        level.setBlock(c.offset(0, 1, 2), st.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
        return c;
    }

    // ============================================================ the long look

    private static final double DAYS = Double.parseDouble(System.getProperty("paths.days", "8"));
    private static final int FOLK = Integer.getInteger("paths.folk", 40);

    /**
     * A town founded with forty folk and left to grow at full speed, every feature live, for some days (paths.days, eight
     * by default): each morning the town's ways are looked over (WayAudit) and logged, and at the end every door must be
     * reached from the heart, no two buildings may overlap or touch, no door may stand against a wall, the square must be
     * clear, and nobody may have been stuck more than half a minute.
     */
    @GameTest(template = EMPTY, timeoutTicks = 600000, required = false, batch = "pa10_long_growth")
    public static void pa10_long_growth(GameTestHelper helper) {
        grow(helper, 1710000, true, "pa10");
    }

    /** The same town grown without the paths work (Footways.liveForTests(false)): what the town's ways come to on their
     *  own, for the comparison. Never required: it is expected to fail. */
    @GameTest(template = EMPTY, timeoutTicks = 600000, required = false, batch = "pa11_long_growth_without")
    public static void pa11_long_growth_without(GameTestHelper helper) {
        grow(helper, 1714000, false, "pa11");
    }

    private static void grow(GameTestHelper helper, int cx, boolean live, String tag) {
        ServerLevel level = helper.getLevel();
        net.minecraft.server.MinecraftServer server = level.getServer();
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.Founding.resetForTests(server);
        com.jrpetty.mcassistant.entity.StuckSpots.resetForTests();
        com.jrpetty.mcassistant.entity.Police.standDownForTests(false);
        com.jrpetty.mcassistant.entity.Identity.liveForTests();
        com.jrpetty.mcassistant.entity.Perks.liveForTests(true);
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
        com.jrpetty.mcassistant.entity.Footways.liveForTests(live);
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(true, server);
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setWeatherParameters(1000000, 0, false, false);
        level.setDayTime(1000);
        int cz = Z;
        int outer = com.jrpetty.mcassistant.village.FoundingPlan.coreRadius(FOLK) + com.jrpetty.mcassistant.village.FoundingPlan.BAND_MAX;
        Kit.hold(level, cx, cz, outer + 32);
        Kit.prepare(level, cx, cz, outer + 32);
        BlockPos heart = Kit.surface(level, cx, cz);
        Kit.generousTerrain(level, cx, cz);
        Kit.log(tag + " found " + FOLK + ": " + Kit.command(level, "village found " + FOLK + " " + cx + " " + cz));
        server.tickRateManager().setTickRate(10000.0F);
        final long[] state = { 0, 0, 0 };           // [0] 0 founding, 1 growing, 2 over; [1] the tick it began; [2] the next look
        final UUID[] id = new UUID[1];
        java.util.function.Consumer<net.neoforged.neoforge.event.tick.ServerTickEvent.Post> each = e -> {
            if (state[0] == 2) return;
            long t = helper.getTick();
            try {
                if (state[0] == 0) {
                    if (com.jrpetty.mcassistant.entity.Founding.near(level, heart, 8)) return;
                    Villages.Village v = Villages.nearest(level, heart, 64);
                    if (v == null) { state[0] = 2; helper.runAfterDelay(1, () -> helper.fail("no village at " + heart)); return; }
                    id[0] = v.id();
                    Villages.ageForTests(v.id(), Villages.Age.IRON);
                    Kit.stockForGrowth(level, v);
                    state[0] = 1;
                    state[1] = t;
                    state[2] = t + 6000;
                    Kit.log(tag + " town " + Villages.name(v.id()) + " of " + Villages.headcount(v.id()) + " at " + v.centre().toShortString());
                    return;
                }
                if (t < state[2]) return;
                state[2] = t + 12000;
                Villages.Village v = Villages.get(id[0]);
                if (v == null) { state[0] = 2; helper.runAfterDelay(1, () -> helper.fail("the village is gone")); return; }
                Kit.stockForGrowth(level, v);
                double day = (t - state[1]) / 24000.0;
                com.jrpetty.mcassistant.entity.WayAudit.Report r = com.jrpetty.mcassistant.entity.WayAudit.check(level, v);
                Kit.log(String.format(tag + " day %.1f: %d folk, built %s", day, Villages.headcount(v.id()), Villages.builtList(v.id())));
                for (String l : r.lines()) Kit.log(tag + "   " + l);
                for (com.jrpetty.mcassistant.entity.StuckSpots.Spot s : com.jrpetty.mcassistant.entity.StuckSpots.spots(v.id())) {
                    if (s.times >= 2) Kit.log(tag + "   spot " + s);
                }
                Kit.log(tag + "   stuck: " + com.jrpetty.mcassistant.entity.StuckSpots.line(v.id()));
                if (day >= DAYS) {
                    state[0] = 2;
                    server.tickRateManager().setTickRate(20.0F);
                    com.jrpetty.mcassistant.entity.Footways.liveForTests(true);
                    for (Ledger.Building b : Ledger.buildings(v.id())) Kit.log(tag + "   building " + b.structure() + " " + b.anchor().toShortString() + " back " + b.facing());
                    // Ended through the test's own sequence: a failure thrown from a server tick listener stops the server.
                    String why = r.clean() ? null : "the town's ways after " + DAYS + " days: " + r.lines().get(0);
                    helper.runAfterDelay(1, () -> {
                        if (why == null) helper.succeed(); else helper.fail(why);
                    });
                }
            } catch (RuntimeException ex) {
                state[0] = 2;
                server.tickRateManager().setTickRate(20.0F);
                throw ex;
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(each);
    }
}
