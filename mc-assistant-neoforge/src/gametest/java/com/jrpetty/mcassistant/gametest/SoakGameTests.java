package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * [soak40] What the long soak of the town found, held to. On ground of its own: x 9,430,000 to 9,440,000, z 92000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class SoakGameTests {

    private static final String EMPTY = "empty";

    /**
     * A folk with blocks set down over it (a street raised a course where it stood: stone at its feet and its head) steps
     * up out of them onto the top, unhurt, rather than dying "trapped in the ground".
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "soak_sk01_step_out")
    public static void sk01_step_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 9_430_000, z = 92000;
        Kit.hold(level, x, z, 16);
        Kit.prepare(level, x, z, 16);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null, "a village");
        BlockPos at = heart.offset(6, 0, 6);
        f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        f.getNavigation().stop();
        float full = f.getHealth();
        // The street raised two courses over a 5x5 where it stands: nowhere to step aside at its own height.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlock(at.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(at.offset(dx, 1, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        // At the first hurt from the wall it is out of it, onto the top, and the hurt is not taken.
        boolean took = f.hurt(level.damageSources().inWall(), 1.0F);
        Kit.log("sk01 buried at " + at.toShortString() + ": hurt taken " + took + ", now at " + f.blockPosition().toShortString()
            + ", health " + f.getHealth() + " of " + full + ", in the wall " + f.isInWall());
        helper.assertTrue(!took && f.getHealth() >= full, "the hurt not taken: " + took + ", health " + f.getHealth());
        helper.assertTrue(f.blockPosition().getY() >= at.getY() + 2 && !f.isInWall(),
            "stood on the top of what was set down over it: " + f.blockPosition().toShortString());
        // And left to itself in the town, it is still alive and unhurt a while on.
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(f.isAlive() && f.getHealth() >= full, "alive and unhurt: " + f.getHealth());
            helper.succeed();
        });
    }

    /**
     * The road between two towns surveyed a slice at a time over ground nobody is near: not a road yet while the survey
     * is under way (the server goes on), then the very road a survey made all at once would have given.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "soak_sk02_sliced_survey")
    public static void sk02_sliced_survey(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        com.jrpetty.mcassistant.entity.RoadWorks.resetForTests();
        int x = 9_434_000, z = 92000, far = 260;
        java.util.UUID[] towns = new java.util.UUID[2];
        for (int i = 0; i < 2; i++) {
            int tx = x + i * far;
            Kit.hold(level, tx, z, 24);
            Kit.prepare(level, tx, z, 24);
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, tx, z), 0.0F);
            helper.assertTrue(f != null && f.ownerId() != null, "town " + i);
            towns[i] = f.ownerId();
        }
        com.jrpetty.mcassistant.entity.Routes.sliceForTests(0);
        final int[] calls = { 0, 0 };                                   // calls, of them not a road yet
        helper.onEachTick(() -> {
            calls[0]++;
            java.util.List<int[]> line = com.jrpetty.mcassistant.entity.RoadWorks.lineForTests(towns[0], towns[1]);
            if (line == null) {
                calls[1]++;
                if (calls[0] > 3800) {
                    com.jrpetty.mcassistant.entity.Routes.sliceForTests(30);
                    helper.fail("the survey never finished: " + calls[0] + " calls");
                }
                return;
            }
            com.jrpetty.mcassistant.entity.Routes.sliceForTests(30);
            java.util.List<int[]> once = com.jrpetty.mcassistant.entity.Routes.surveyNowForTests(towns[0], towns[1]);
            boolean same = once != null && once.size() == line.size();
            for (int k = 0; same && k < line.size(); k++) same = once.get(k)[0] == line.get(k)[0] && once.get(k)[1] == line.get(k)[1];
            Kit.log("sk02 the road after " + calls[0] + " calls (" + calls[1] + " of them not yet a road): " + line.size()
                + " steps; all at once: " + (once == null ? "none" : once.size()) + " steps, the same " + same);
            helper.assertTrue(calls[1] >= 2, "surveyed a slice at a time, not all at once: " + calls[1] + " calls before it was a road");
            helper.assertTrue(line.size() > far / 2, "a road between them: " + line.size());
            helper.assertTrue(same, "the very road the survey all at once makes");
            helper.succeed();
        });
    }

    /**
     * A caravan of an awake town crossing a sleeping town's ground keeps the ground round it awake: its window, asked for
     * in its own folk's tick under a name of the trip's own, is its town's, not the sleeper's (whose ground it lies on).
     * Before, the sleeper let it go as it slept (and parked any it asked for after), and the caravan stood frozen there.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "soak_sk03_trip_window")
    public static void sk03_trip_window(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        int ax = 9_438_000, bx = ax + 120, z = 92000;
        Kit.hold(level, ax + 60, z, 110);
        Kit.prepare(level, ax + 60, z, 110);
        BlockPos ah = Kit.surface(level, ax, z), bh = Kit.surface(level, bx, z);
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, ah, 0.0F);
        com.jrpetty.mcassistant.entity.Villages.Village bv = com.jrpetty.mcassistant.entity.Villages.found(level, bh);
        VillageFolkEntity b = VillageFolkSpawnerBlock.raise(level, bh, 0.0F);
        helper.assertTrue(a != null && b != null && bv != null && !a.ownerId().equals(b.ownerId()), "two towns");
        java.util.UUID av = a.ownerId(), bid = b.ownerId();
        com.jrpetty.mcassistant.entity.TownLod.ratesForTests(bid, new com.jrpetty.mcassistant.entity.TownLod.Rates(java.util.Map.of("bread", 1.0), 1.0, 0.0, 5.0));
        java.util.UUID trip = java.util.UUID.nameUUIDFromBytes(("sk03-caravan-" + a.getUUID()).getBytes());
        BlockPos road = bh.offset(-20, 0, 6);
        int depth = com.jrpetty.mcassistant.entity.Accounts.enter(a);       // as in the caravan's own tick
        com.jrpetty.mcassistant.ChunkLoad.setLoaded(level, trip, road, 1, true);
        com.jrpetty.mcassistant.entity.Accounts.leave(depth);
        helper.runAfterDelay(200, () -> {
            for (int x = (ax + 60 - 110) >> 4; x <= (ax + 60 + 110) >> 4; x++)
                for (int zz = (z - 110) >> 4; zz <= (z + 110) >> 4; zz++) level.setChunkForced(x, zz, false);
            level.setChunkForced(ah.getX() >> 4, ah.getZ() >> 4, true);        // the awake town's heart held
            com.jrpetty.mcassistant.entity.TownLod.liveForTests(true);
            com.jrpetty.mcassistant.entity.TownWake.liveForTests(true);
            com.jrpetty.mcassistant.entity.TownLod.lookForTests(level.getServer());
            boolean aAway = com.jrpetty.mcassistant.entity.TownLod.away(av), bAway = com.jrpetty.mcassistant.entity.TownLod.away(bid);
            int held = 0;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) if (com.jrpetty.mcassistant.ChunkLoad.holds(level, trip, (road.getX() >> 4) + dx, (road.getZ() >> 4) + dz)) held++;
            // And a window asked for now, on the sleeper's ground, is forced, not parked for its waking.
            BlockPos on = bh.offset(4, 0, -20);
            askAs(a, level, trip, on);
            int parked = com.jrpetty.mcassistant.entity.TownLod.parkedForTests(bid, trip);
            boolean asked = com.jrpetty.mcassistant.ChunkLoad.holds(level, trip, on.getX() >> 4, on.getZ() >> 4);
            Kit.log("sk03 the awake town " + (aAway ? "asleep" : "awake") + ", the other " + (bAway ? "asleep" : "awake") + "; the caravan's window "
                + held + " of 9 chunks held; a new one parked " + parked + ", asked for " + asked);
            com.jrpetty.mcassistant.entity.TownLod.liveForTests(false);
            com.jrpetty.mcassistant.entity.TownWake.liveForTests(false);
            com.jrpetty.mcassistant.entity.TownLod.forceForTests(level.getServer(), null);
            level.setChunkForced(ah.getX() >> 4, ah.getZ() >> 4, false);
            com.jrpetty.mcassistant.ChunkLoad.setLoaded(level, trip, road, 1, false);
            com.jrpetty.mcassistant.ChunkLoad.setLoaded(level, trip, on, 1, false);
            helper.assertTrue(!aAway && bAway, "the held town awake, the other asleep");
            helper.assertTrue(held == 9, "the caravan's window kept as the town whose ground it is on sleeps: " + held + " of 9");
            helper.assertTrue(parked == 0 && asked, "a window it asks for there now is its own town's: parked " + parked + ", asked for " + asked);
            helper.succeed();
        });
    }

    /** A window asked for in this folk's tick, as its own systems ask. */
    private static void askAs(VillageFolkEntity a, ServerLevel level, java.util.UUID trip, BlockPos at) {
        int depth = com.jrpetty.mcassistant.entity.Accounts.enter(a);
        try {
            com.jrpetty.mcassistant.ChunkLoad.setLoaded(level, trip, at, 1, true);
        } finally {
            com.jrpetty.mcassistant.entity.Accounts.leave(depth);
        }
    }

    /**
     * A town asleep when the server stops is on the map again when it starts: its register is in memory and its folk,
     * who carry it, are all in chunks nobody holds; from its books it is put back, where it was, as far as it had got.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "soak_sk04_sleeper_restored")
    public static void sk04_sleeper_restored(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        int x = 9_442_000, z = 92000;
        Kit.hold(level, x, z, 24);
        Kit.prepare(level, x, z, 24);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        for (int i = 0; i < 2; i++) VillageFolkSpawnerBlock.raise(level, heart.offset(2 + i, 0, 2), 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a town");
        java.util.UUID id = f.ownerId();
        com.jrpetty.mcassistant.entity.Villages.Village v = com.jrpetty.mcassistant.entity.Villages.get(id);
        int roll = com.jrpetty.mcassistant.entity.Villages.headcount(id);
        java.util.List<String> built = com.jrpetty.mcassistant.entity.Villages.builtList(id);
        com.jrpetty.mcassistant.entity.TownLod.ratesForTests(id, new com.jrpetty.mcassistant.entity.TownLod.Rates(java.util.Map.of("bread", 1.0), 1.0, 0.0, 5.0));
        com.jrpetty.mcassistant.entity.TownLod.forceForTests(level.getServer(), com.jrpetty.mcassistant.entity.TownLod.Lod.AWAY);
        helper.assertTrue(com.jrpetty.mcassistant.entity.TownLod.away(id), "with nobody near it sleeps");
        // The server stops and starts: the register in memory gone (none of its folk loaded to put it back), the books kept.
        com.jrpetty.mcassistant.entity.Villages.offTheRegisterForTests(id);
        boolean gone = com.jrpetty.mcassistant.entity.Villages.get(id) == null;
        int back = com.jrpetty.mcassistant.entity.TownRoll.restoreForTests(level.getServer());
        com.jrpetty.mcassistant.entity.Villages.Village again = com.jrpetty.mcassistant.entity.Villages.get(id);
        Kit.log("sk04 a town of " + roll + " asleep, the server restarted: off the register " + gone + "; put back " + back + ": "
            + (again == null ? "none" : again.centre().toShortString() + ", roll " + com.jrpetty.mcassistant.entity.Villages.headcount(id)
            + ", built " + com.jrpetty.mcassistant.entity.Villages.builtList(id)));
        helper.assertTrue(gone, "after the restart it is off the register (its folk unloaded carry it)");
        helper.assertTrue(again != null && again.centre().equals(v.centre()) && again.dim().equals(v.dim()), "put back on the map where it was");
        helper.assertTrue(com.jrpetty.mcassistant.entity.Villages.headcount(id) == roll && com.jrpetty.mcassistant.entity.Villages.builtList(id).equals(built),
            "as far as it had got: roll " + com.jrpetty.mcassistant.entity.Villages.headcount(id) + " of " + roll);
        com.jrpetty.mcassistant.entity.TownLod.forceForTests(level.getServer(), null);
        helper.succeed();
    }

    /**
     * [tp49] A folk set down a hair into a wall (a hundredth of a block: a led walk's step, the glide out of the cage, a seat)
     * and then walking at the wall stops at its face like anybody: the game's own movement takes no notice of a block a body
     * is already in, and the folk walked on into it, was hurt by it and stepped out of it (ClearOfWalls).
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "soak_sk05_hair_into_a_wall")
    public static void sk05_hair_into_a_wall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 9_444_000, z = 92000;
        Kit.hold(level, x, z, 16);
        Kit.prepare(level, x, z, 16);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null, "a village");
        // A floor up in the clear air, and a wall three high along its east side.
        int y = heart.getY() + 12, x0 = x + 6, z0 = z + 6;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            level.setBlock(new BlockPos(x0 + dx, y - 1, z0 + dz), Blocks.STONE.defaultBlockState(), 3);
            for (int dy = 0; dy <= 3; dy++) level.setBlock(new BlockPos(x0 + dx, y + dy, z0 + dz),
                (dx == 2 && dy <= 2 ? Blocks.STONE_BRICKS : Blocks.AIR).defaultBlockState(), 3);
        }
        // Set down with its body a hundredth of a block into the wall, and pressed at it: three steps of its own movement a
        // tick, each with a way at the wall (whatever its own mind has it do besides).
        double at = x0 + 2 - f.getBbWidth() / 2.0 + 0.01;
        f.moveTo(at, y, z0 + 0.5, -90.0F, 0.0F);
        f.getNavigation().stop();
        boolean[] walled = { false };
        double[] most = { 0.0 };                                               // the furthest its body went past the wall's face
        int[] ran = { 0 };
        helper.onEachTick(() -> {
            if (ran[0] < 0) return;
            f.getNavigation().stop();
            for (int k = 0; k < 3; k++) {
                f.setDeltaMovement(0.15, -0.08, 0.0);
                f.travel(net.minecraft.world.phys.Vec3.ZERO);
                if (f.isInWall()) walled[0] = true;
                most[0] = Math.max(most[0], f.getBoundingBox().maxX - (x0 + 2));
            }
            if (++ran[0] < 30) return;
            ran[0] = -1;
            Kit.log("sk05 set a hundredth into the wall at " + String.format("%.3f", at) + ", pressed at it 30 ticks: now at "
                + String.format("%.3f", f.getX()) + " (the wall's face at " + (x0 + 2) + "), the most into it " + String.format("%.3f", most[0])
                + ", in the wall " + walled[0]);
            helper.assertTrue(!walled[0], "never in the wall pressed at it");
            helper.assertTrue(level.noCollision(f, f.getBoundingBox()) && f.getBoundingBox().maxX <= x0 + 2 + 1.0E-6,
                "stopped at the wall's face, clear of it: " + f.getBoundingBox().maxX);
            helper.assertTrue(most[0] < 0.05, "never further into the wall than it was set: " + most[0]);
            helper.succeed();
        });
    }

    /**
     * [tp49] A sapling does not grow into a tree while a folk stands where its trunk would go (a woodsman back at its plot
     * stood on the sapling it had planted, and the oak came up through it); with nobody there it grows.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "soak_sk06_tree_room")
    public static void sk06_tree_room(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 9_446_000, z = 92000;
        Kit.hold(level, x, z, 16);
        Kit.prepare(level, x, z, 16);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null, "a village");
        int y = heart.getY() + 12, x0 = x + 6, z0 = z + 6;
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            level.setBlock(new BlockPos(x0 + dx, y - 1, z0 + dz), Blocks.DIRT.defaultBlockState(), 3);
            for (int dy = 0; dy <= 10; dy++) level.setBlock(new BlockPos(x0 + dx, y + dy, z0 + dz), Blocks.AIR.defaultBlockState(), 3);
        }
        BlockPos sap = new BlockPos(x0, y, z0);
        level.setBlock(sap, Blocks.OAK_SAPLING.defaultBlockState(), 3);
        f.moveTo(x0 + 0.5, y, z0 + 0.5, 0.0F, 0.0F);
        f.getNavigation().stop();
        net.minecraft.world.level.block.SaplingBlock block = (net.minecraft.world.level.block.SaplingBlock) Blocks.OAK_SAPLING;
        for (int i = 0; i < 6; i++) {
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(sap);
            if (st.is(Blocks.OAK_SAPLING)) block.advanceTree(level, sap, st, level.getRandom());
        }
        boolean grewOnIt = !level.getBlockState(sap).is(Blocks.OAK_SAPLING);
        boolean walled = f.isInWall();
        // Gone: it grows.
        f.moveTo(x0 + 3.5, y, z0 + 3.5, 0.0F, 0.0F);
        boolean grew = false;
        for (int i = 0; i < 40 && !grew; i++) {
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(sap);
            if (st.is(Blocks.OAK_SAPLING)) block.advanceTree(level, sap, st, level.getRandom());
            grew = level.getBlockState(sap).is(Blocks.OAK_LOG);
        }
        Kit.log("sk06 the sapling with a folk on it grew " + grewOnIt + " (in the wall " + walled + "); with it gone, grew " + grew);
        helper.assertTrue(!grewOnIt && !walled, "no tree up through the folk standing on its sapling");
        helper.assertTrue(grew, "with nobody on it, the sapling grows");
        helper.succeed();
    }
}
