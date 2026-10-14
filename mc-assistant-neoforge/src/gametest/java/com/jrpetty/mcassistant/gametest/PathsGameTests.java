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
        ServerLevel level = helper.getLevel();
        net.minecraft.server.MinecraftServer server = level.getServer();
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.Founding.resetForTests(server);
        com.jrpetty.mcassistant.entity.StuckSpots.resetForTests();
        com.jrpetty.mcassistant.entity.Police.standDownForTests(false);
        com.jrpetty.mcassistant.entity.Identity.liveForTests();
        com.jrpetty.mcassistant.entity.Perks.liveForTests(true);
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(false);
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(true, server);
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setWeatherParameters(1000000, 0, false, false);
        level.setDayTime(1000);
        int cx = 1710000, cz = Z;
        int outer = com.jrpetty.mcassistant.village.FoundingPlan.coreRadius(FOLK) + com.jrpetty.mcassistant.village.FoundingPlan.BAND_MAX;
        Kit.hold(level, cx, cz, outer + 32);
        Kit.prepare(level, cx, cz, outer + 32);
        BlockPos heart = Kit.surface(level, cx, cz);
        Kit.log("pa10 found " + FOLK + ": " + Kit.command(level, "village found " + FOLK + " " + cx + " " + cz));
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
                    if (v == null) { state[0] = 2; helper.fail("no village at " + heart); return; }
                    id[0] = v.id();
                    Villages.ageForTests(v.id(), Villages.Age.IRON);
                    state[0] = 1;
                    state[1] = t;
                    state[2] = t + 6000;
                    Kit.log("pa10 town " + Villages.name(v.id()) + " of " + Villages.headcount(v.id()) + " at " + v.centre().toShortString());
                    return;
                }
                if (t < state[2]) return;
                state[2] = t + 12000;
                Villages.Village v = Villages.get(id[0]);
                if (v == null) { state[0] = 2; helper.fail("the village is gone"); return; }
                double day = (t - state[1]) / 24000.0;
                com.jrpetty.mcassistant.entity.WayAudit.Report r = com.jrpetty.mcassistant.entity.WayAudit.check(level, v);
                Kit.log(String.format("pa10 day %.1f: %d folk, built %s", day, Villages.headcount(v.id()), Villages.builtList(v.id())));
                for (String l : r.lines()) Kit.log("pa10   " + l);
                for (com.jrpetty.mcassistant.entity.StuckSpots.Spot s : com.jrpetty.mcassistant.entity.StuckSpots.spots(v.id())) {
                    if (s.times >= 2) Kit.log("pa10   spot " + s);
                }
                Kit.log("pa10   stuck: " + com.jrpetty.mcassistant.entity.StuckSpots.line(v.id()));
                if (day >= DAYS) {
                    state[0] = 2;
                    server.tickRateManager().setTickRate(20.0F);
                    if (r.clean()) helper.succeed();
                    else helper.fail("the town's ways after " + DAYS + " days: " + r.lines().get(0));
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
