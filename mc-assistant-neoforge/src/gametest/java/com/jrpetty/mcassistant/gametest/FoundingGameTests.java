package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageBoardBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Founding;
import com.jrpetty.mcassistant.entity.Terraform;
import com.jrpetty.mcassistant.entity.VillageBoards;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.FoundingPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * Founding a village of a size the player chooses (entity/Founding): the spawner puts the board up
 * and nobody comes; the founders chosen at it, the ground round about is made level for them (the
 * heart flat, the edges sloped into the land, nothing anybody built touched) and they come.
 *
 * <p>Like the village tests, each runs on its own ground, far from the others, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class FoundingGameTests {

    private static final String EMPTY = "empty";

    private static AABB around(BlockPos p, double r) {
        return new AABB(p.getX() - r, p.getY() - 30, p.getZ() - r, p.getX() + r, p.getY() + 40, p.getZ() + r);
    }

    /** A pit dug into the ground, {@code depth} deep and {@code half} out from its middle. */
    private static void hollow(ServerLevel level, int cx, int cz, int half, int depth) {
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                BlockPos top = Kit.surface(level, cx + dx, cz + dz).below();
                for (int i = 0; i < depth; i++) level.setBlock(top.below(i), Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    /** A hut somebody built: walls of planks, three high, with a roof. */
    private static BlockPos hut(ServerLevel level, int x, int z) {
        BlockPos base = Kit.surface(level, x, z);
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                for (int dy = 0; dy < 3; dy++) {
                    boolean wall = dx != 1 || dz != 1 || dy == 2;
                    level.setBlock(base.offset(dx, dy, dz), wall ? Blocks.OAK_PLANKS.defaultBlockState()
                        : Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        return base;
    }

    /** How the ground stands round the heart, read back: every column's earth, nearest the heart first. */
    private static int ground(ServerLevel level, int x, int z) {
        return Terraform.groundY(level, x, z);
    }

    // ============================================================ the whole of it

    /**
     * On rough ground — a stone hill across what will be the edge, a knoll and a hollow inside, a
     * pond, trees, a hut somebody built — the spawner puts the board up and nobody comes; a count
     * outside two to five hundred is refused and one over the server's cap brought down to it;
     * then twenty are chosen. When it is done the levelled square is flat (no column more than a
     * block from another), the edge slopes (no step of more than two between neighbours), the
     * pond is filled, the trees are gone, the hut stands untouched, and twenty folk live there.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "f01_founding")
    public static void f01_founding(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        level.setDayTime(1000);
        final int cx = 110000, cz = 12000, folk = 20;
        final int radius = FoundingPlan.coreRadius(folk), outer = radius + FoundingPlan.BAND_MAX;
        Kit.hold(level, cx, cz, outer + 24);
        Kit.prepare(level, cx, cz, outer + 24);
        // The rough ground.
        Kit.hill(level, cx + radius, cz, 16, 12, 701);          // across the east edge: cut, then sloped
        Kit.hill(level, cx - 12, cz + 14, 6, 5, 702);           // a knoll inside: cut down
        hollow(level, cx + 10, cz - 14, 2, 2);                  // a hollow inside: filled
        Kit.pond(level, cx - 16, cz - 6, 2);                    // a pond inside: filled
        Kit.wildTree(level, cx + 6, cz + 6);
        Kit.wildTree(level, cx - 8, cz - 20);
        Kit.wildTree(level, cx + 18, cz + 16);
        BlockPos hut = hut(level, cx - 24, cz + 2);             // somebody's: left as it is
        BlockPos post = Kit.surface(level, cx + outer + 6, cz + 3);
        for (int i = 0; i < 4; i++) level.setBlock(post.above(i), Blocks.COBBLESTONE.defaultBlockState(), 3);
        int hillTop = ground(level, cx + radius, cz);
        Kit.log("f01 R=" + radius + " outer=" + outer + ", the hill's top at y=" + hillTop + ", the plain at y="
            + ground(level, cx, cz));

        // The spawner, set down as a player would (yaw 0: facing south, so the board goes up south of the heart).
        BlockPos heart = Kit.surface(level, cx, cz);
        BlockState spawner = McAssistantMod.FOLK_SPAWNER.get().defaultBlockState();
        level.setBlock(heart, spawner, 3);
        VillageFolkSpawnerBlock.placed(level, heart, spawner, null, 0.0F);
        BlockPos board = Founding.boardNear(level, heart, 64);
        int nobody = level.getEntitiesOfClass(VillageFolkEntity.class, around(heart, outer)).size();
        Kit.log("f01 the board waits at " + board + "; folk " + nobody + "; villages " + Villages.every().size());
        helper.assertTrue(board != null, "the spawner puts a village board up");
        helper.assertTrue(nobody == 0 && Villages.every().isEmpty(), "nobody comes until the founders are chosen");
        helper.assertTrue(level.getBlockEntity(board) instanceof VillageBoardBlockEntity be && be.founding() == Founding.PENDING,
            "the board knows it waits for a founding");
        // The board remembers it is waiting across a save and a load.
        VillageBoardBlockEntity be = (VillageBoardBlockEntity) level.getBlockEntity(board);
        CompoundTag saved = be.saveWithoutMetadata(level.registryAccess());
        VillageBoardBlockEntity again = new VillageBoardBlockEntity(board, be.getBlockState());
        again.loadWithComponents(saved, level.registryAccess());
        helper.assertTrue(again.founding() == Founding.PENDING && heart.equals(again.foundingHeart()),
            "a waiting board is still waiting after a restart");

        // What is refused, and what is brought down to the cap.
        helper.assertTrue(!Founding.confirm(level, board, 1, null).ok(), "one is too few to found a village");
        helper.assertTrue(!Founding.confirm(level, board, 501, null).ok(), "five hundred and one is too many");
        int cap = AssistantConfig.villageFoundingMost();
        helper.assertTrue(Founding.allowed(600) == Math.min(500, cap) && Founding.allowed(150) == Math.min(150, cap),
            "a count over the founding limit (" + cap + ") is brought down to it: " + Founding.allowed(150));
        List<String> said = Kit.command(level, "village found 900 " + cx + " " + cz);
        Kit.log("f01 /village found 900: " + said);
        helper.assertTrue(Founding.status(level.getServer()).size() == 1
                && level.getEntitiesOfClass(VillageFolkEntity.class, around(heart, outer)).isEmpty(),
            "the command refuses nine hundred too");
        helper.assertTrue(be.founding() == Founding.PENDING, "the board still waits after the refusals");

        // Twenty, chosen.
        Founding.Outcome chosen = Founding.confirm(level, board, folk, null);
        Kit.log("f01 confirmed: " + chosen.message());
        helper.assertTrue(chosen.ok(), "twenty can be chosen: " + chosen.message());
        helper.assertTrue(be.founding() == Founding.UNDER_WAY, "the board knows the founding is under way");
        final boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0]) return;
            long t = helper.getTick();
            if (Founding.near(level, heart, 8)) {
                if (t % 100 == 0) Kit.log("f01 @" + t + " " + Founding.status(level.getServer()));
                return;
            }
            done[0] = true;
            Kit.Expect expect = new Kit.Expect();
            Villages.Village v = Villages.nearest(level, heart, 64);
            expect.that(v != null, "a village is founded at the heart");
            if (v == null) {
                helper.fail(expect.summary());
                return;
            }
            int level0 = v.centre().getY() - 1;
            Kit.log("f01 done at " + t + ": " + Villages.name(v.id()) + " at " + v.centre().toShortString() + ", levelled to y=" + level0);
            // The square: flat.
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, columns = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (FoundingPlan.reach(dx, dz) > radius - 4) continue;
                    int x = cx + dx, z = cz + dz;
                    if (Math.abs(x - (hut.getX() + 1)) <= 4 && Math.abs(z - (hut.getZ() + 1)) <= 4) continue;
                    int y = ground(level, x, z);
                    lo = Math.min(lo, y);
                    hi = Math.max(hi, y);
                    columns++;
                }
            }
            Kit.log("f01 the square: " + columns + " columns from y=" + lo + " to y=" + hi);
            expect.that(hi - lo <= 1, "the levelled square is flat: from y=" + lo + " to y=" + hi);
            // And solid five deep: its top and four of earth under it, nothing hollow, nothing growing on it.
            int hollow = 0, growing = 0;
            String firstHollow = "";
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (FoundingPlan.reach(dx, dz) > radius - 4) continue;
                    int x = cx + dx, z = cz + dz;
                    if (Math.abs(x - (hut.getX() + 1)) <= 4 && Math.abs(z - (hut.getZ() + 1)) <= 4) continue;
                    int y = ground(level, x, z);
                    for (int d = 0; d < com.jrpetty.mcassistant.entity.Terraform.SOLID_DEPTH; d++) {
                        // (The test world's flat ground is only four deep over the bottom of the world.)
                        if (y - d < level.getMinBuildHeight()) break;
                        net.minecraft.world.level.block.state.BlockState b = level.getBlockState(new BlockPos(x, y - d, z));
                        if (b.isAir() || !b.getFluidState().isEmpty() || !b.isSolid()) {
                            if (hollow++ == 0) firstHollow = x + "," + (y - d) + "," + z + " " + b;
                        }
                    }
                    net.minecraft.world.level.block.state.BlockState on = level.getBlockState(new BlockPos(x, y + 1, z));
                    if (!on.isAir() && com.jrpetty.mcassistant.entity.Terraform.growth(on)) growing++;
                }
            }
            Kit.log("f01 five deep: " + hollow + " hollow blocks" + (hollow > 0 ? " (first " + firstHollow + ")" : "") + ", " + growing + " plants left on it");
            expect.that(hollow == 0, "the square is solid five deep: " + hollow + " hollow, first " + firstHollow);
            // The edge: sloped, never a cliff.
            int worst = 0;
            String where = "";
            for (int dx = -outer; dx <= outer; dx++) {
                for (int dz = -outer; dz <= outer; dz++) {
                    double r = FoundingPlan.reach(dx, dz);
                    if (r <= radius - 4 || r > outer - 1) continue;
                    int x = cx + dx, z = cz + dz;
                    if (Math.abs(x - (hut.getX() + 1)) <= 5 && Math.abs(z - (hut.getZ() + 1)) <= 5) continue;
                    int y = ground(level, x, z);
                    for (int[] n : new int[][]{ {1, 0}, {0, 1} }) {
                        if (FoundingPlan.reach(dx + n[0], dz + n[1]) > outer - 1) continue;
                        int step = Math.abs(ground(level, x + n[0], z + n[1]) - y);
                        if (step > worst) {
                            worst = step;
                            where = x + "," + z + " (" + (int) (r - radius) + " out)";
                        }
                    }
                }
            }
            Kit.log("f01 the edge: the steepest step " + worst + " at " + where);
            expect.that(worst <= 2, "the edge slopes: no step between neighbours over two, worst " + worst + " at " + where);
            // The hill: cut where the square is, still a hill out past the edge.
            int inside = ground(level, cx + radius - 6, cz), beyond = ground(level, cx + radius + 12, cz);
            Kit.log("f01 the hill: y=" + inside + " inside, y=" + beyond + " twelve out (its top was y=" + hillTop + ")");
            expect.that(inside == level0, "the hill is cut down inside the square");
            expect.that(beyond > level0, "and still a hill out past the edge");
            // The pond, the trees, the hut, the post.
            expect.that(level.getFluidState(new BlockPos(cx - 16, level0, cz - 6)).isEmpty()
                && level.getFluidState(new BlockPos(cx - 16, level0 + 1, cz - 6)).isEmpty(), "the pond is filled");
            boolean logs = false;
            for (int[] tr : new int[][]{ {6, 6}, {-8, -20}, {18, 16} }) {
                for (int dy = 1; dy <= 6; dy++) {
                    if (level.getBlockState(new BlockPos(cx + tr[0], level0 + dy, cz + tr[1])).is(BlockTags.LOGS)) logs = true;
                }
            }
            expect.that(!logs, "the trees are cleared off the square");
            expect.that(level.getBlockState(hut).is(Blocks.OAK_PLANKS) && level.getBlockState(hut.offset(2, 2, 2)).is(Blocks.OAK_PLANKS),
                "the hut somebody built is untouched");
            expect.that(level.getBlockState(post.above(3)).is(Blocks.COBBLESTONE), "the post outside is untouched");
            // The folk, the stores, the board.
            int here = Villages.headcount(v.id());
            List<VillageFolkEntity> stood = level.getEntitiesOfClass(VillageFolkEntity.class, around(heart, radius + 16),
                VillageFolkEntity::isAlive);
            Kit.log("f01 folk: " + here + " on the roll, " + stood.size() + " standing in the village");
            expect.that(here == folk && stood.size() == folk, "twenty folk live there: " + here + " / " + stood.size());
            expect.that(level.getBlockState(v.centre()).is(Blocks.CHEST), "the founding stores stand at the heart");
            expect.that(VillageBoards.boardOf(v.id()) != null, "the village has its board up");
            expect.that(!Founding.near(level, heart, 200), "the founding is done and forgotten");
            Kit.dashboard(level, heart, "f01 founded");
            if (expect.clean()) helper.succeed();
            else helper.fail(expect.summary());
        });
    }

    // ============================================================ flat beside water

    /**
     * Flat to the edge whatever water stands about it. On a plateau with a tarn on a hill just past
     * its west edge (water well above the level, which once held the ground up a block for every
     * block from it, terraces into the square) and, to the east, a low hollow running into the square
     * beside a lake well under the level (which once let the fill down to the lake's shore, terraces
     * again); with a pit and sand over a cave inside: when it is done every column of the square
     * stands at the level, solid five deep, nothing wet or growing on it, and the tarn and the lake
     * are both still there.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "f04_flat_by_water")
    public static void f04_flat_by_water(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        level.setDayTime(1000);
        final int cx = 118000, cz = 12000, folk = 20;
        final int radius = FoundingPlan.coreRadius(folk), outer = radius + FoundingPlan.BAND_MAX;
        Kit.hold(level, cx, cz, outer + 24);
        Kit.prepare(level, cx, cz, outer + 24);
        int base = Kit.surface(level, cx, cz).getY();               // the free block over the flat ground
        // The plateau: six blocks of earth over the flat, all but a low strip down the east side.
        int g0 = base + 5;
        for (int dx = -radius - 20; dx <= radius + 20; dx++) {
            for (int dz = -radius - 20; dz <= radius + 20; dz++) {
                if (dx > radius - 10) continue;
                for (int y = base; y <= g0; y++) {
                    level.setBlock(new BlockPos(cx + dx, y, cz + dz), (y == g0 ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), 2);
                }
            }
        }
        // East: a lake in the low ground, its water six under the plateau, three blocks past the square.
        final int lakeX = cx + radius + 8;
        Kit.pond(level, lakeX, cz, 7);
        // West: a hill just past the edge with a tarn in its top, its water nine over the plateau.
        final int tarnX = cx - radius - 3, tarnZ = cz + 8;
        Kit.hill(level, tarnX, tarnZ, 10, 10, 731);
        // (Sunk a block into the hilltop, so the ring of the hill round it holds its water in.)
        int hillTop = Kit.surface(level, tarnX, tarnZ).getY() - 1;
        level.setBlock(new BlockPos(tarnX, hillTop, tarnZ), Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(new BlockPos(tarnX, hillTop - 1, tarnZ), Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(new BlockPos(tarnX, hillTop - 2, tarnZ), Blocks.WATER.defaultBlockState(), 2);
        final int tarnY = hillTop - 1;
        // Inside: a pit, and sand lying over a cave.
        hollow(level, cx + 8, cz - 10, 2, 3);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(new BlockPos(cx - 10 + dx, g0, cz + 12 + dz), Blocks.SAND.defaultBlockState(), 2);
                level.setBlock(new BlockPos(cx - 10 + dx, g0 - 1, cz + 12 + dz), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(new BlockPos(cx - 10 + dx, g0 - 2, cz + 12 + dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        Kit.log("f04 R=" + radius + ": the plateau at y=" + g0 + ", the low strip and the lake at y=" + (base - 1)
            + ", the tarn's water at y=" + tarnY);

        BlockPos heart = Kit.surface(level, cx, cz);
        BlockState spawner = McAssistantMod.FOLK_SPAWNER.get().defaultBlockState();
        level.setBlock(heart, spawner, 3);
        VillageFolkSpawnerBlock.placed(level, heart, spawner, null, 0.0F);
        BlockPos board = Founding.boardNear(level, heart, 64);
        helper.assertTrue(board != null, "the spawner puts a village board up");
        Founding.Outcome chosen = Founding.confirm(level, board, folk, null);
        Kit.log("f04 confirmed: " + chosen.message());
        helper.assertTrue(chosen.ok(), "twenty can be chosen: " + chosen.message());
        final boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0]) return;
            long t = helper.getTick();
            if (Founding.near(level, heart, 8)) {
                if (t % 100 == 0) Kit.log("f04 @" + t + " " + Founding.status(level.getServer()));
                return;
            }
            done[0] = true;
            Kit.Expect expect = new Kit.Expect();
            Villages.Village v = Villages.nearest(level, heart, 64);
            expect.that(v != null, "a village is founded at the heart");
            if (v == null) {
                helper.fail(expect.summary());
                return;
            }
            int level0 = v.centre().getY() - 1;
            Kit.log("f04 done at " + t + ": levelled to y=" + level0 + " (the plateau is at y=" + g0 + ")");
            expect.that(level0 == g0, "levelled to the plateau's height: y=" + level0 + " against " + g0);
            int columns = 0, off = 0, hollowBlocks = 0, wetOrGrowing = 0, built = 0;
            StringBuilder first = new StringBuilder(), growing = new StringBuilder(), builtOn = new StringBuilder();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (FoundingPlan.reach(dx, dz) > radius - 4) continue;
                    int x = cx + dx, z = cz + dz;
                    // What the town itself set down on the square (its board, the camp's beds, the stores) is not the ground.
                    BlockState at = level.getBlockState(new BlockPos(x, level0, z)), over = level.getBlockState(new BlockPos(x, level0 + 1, z));
                    if (!Terraform.natural(at) || !Terraform.natural(over)) {
                        if (built++ < 4) builtOn.append(' ').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey((Terraform.natural(over) ? at : over).getBlock()).getPath());
                        continue;
                    }
                    int y = ground(level, x, z);
                    columns++;
                    if (y != level0) {
                        if (off++ < 8) first.append(' ').append(dx).append(',').append(dz).append(" y=").append(y).append(' ')
                            .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(at.getBlock()).getPath());
                        continue;
                    }
                    for (int d = 0; d < Terraform.SOLID_DEPTH; d++) {
                        if (y - d < level.getMinBuildHeight()) break;
                        BlockState b = level.getBlockState(new BlockPos(x, y - d, z));
                        if (b.isAir() || !b.getFluidState().isEmpty() || !b.isSolid()) hollowBlocks++;
                    }
                    BlockState on = level.getBlockState(new BlockPos(x, y + 1, z));
                    // The village board stands on posts of dark oak logs: the town's, not a tree's.
                    boolean post = false;
                    for (int up = 2; up <= 8 && on.is(BlockTags.LOGS) && !post; up++) {
                        post = level.getBlockState(new BlockPos(x, y + up, z)).getBlock() instanceof com.jrpetty.mcassistant.block.VillageBoardBlock;
                    }
                    if (post) continue;
                    if (!on.getFluidState().isEmpty() || (!on.isAir() && Terraform.growth(on))) {
                        if (wetOrGrowing++ < 6) growing.append(' ').append(dx).append(',').append(dz).append(' ')
                            .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(on.getBlock()).getPath());
                    }
                }
            }
            Kit.log("f04 the square: " + columns + " columns of ground (" + built + " with the town's own things on them:" + builtOn
                + "), " + off + " off the level" + (off > 0 ? ":" + first : "")
                + "; " + hollowBlocks + " hollow blocks within five of the top; " + wetOrGrowing + " wet or growing on top" + growing);
            // The east edge and the west edge, column by column across the square, for the log.
            StringBuilder row = new StringBuilder();
            for (int dx = -radius; dx <= radius; dx += 2) row.append(ground(level, cx + dx, cz + 8) - level0).append(' ');
            Kit.log("f04 heights across, west to east, against the level: " + row);
            expect.that(off == 0, "every column of the square at the level: " + off + " off," + first);
            expect.that(hollowBlocks == 0, "the square is solid five deep: " + hollowBlocks + " hollow");
            expect.that(wetOrGrowing == 0, "nothing wet or growing on it: " + wetOrGrowing + growing);
            expect.that(!level.getFluidState(new BlockPos(tarnX, tarnY, tarnZ)).isEmpty(), "the tarn on the hill is still there");
            expect.that(!level.getFluidState(new BlockPos(lakeX, base - 1, cz)).isEmpty(), "the lake is still there");
            if (expect.clean()) helper.succeed();
            else helper.fail(expect.summary());
        });
    }

    // ============================================================ a spawner in a village

    /**
     * A spawner set down within reach of a village adds one settler to it, as it always has, and puts
     * no board up; one set down far from any village puts a board up, and another set down by that
     * board puts up no second one. Taking the waiting board down calls the founding off.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "f02_spawner_joins")
    public static void f02_spawner_joins(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        final int cx = 112000, cz = 12000;
        Kit.hold(level, cx, cz, 48);
        Kit.prepare(level, cx, cz, 48);
        BlockPos heart = Kit.surface(level, cx, cz);
        int stood = VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, 3);
        Villages.Village v = Villages.nearest(level, heart, 64);
        helper.assertTrue(stood == 3 && v != null, "a village of three");
        BlockState spawner = McAssistantMod.FOLK_SPAWNER.get().defaultBlockState();
        BlockPos by = Kit.surface(level, cx + 9, cz + 4);
        level.setBlock(by, spawner, 3);
        VillageFolkSpawnerBlock.placed(level, by, spawner, null, 0.0F);
        Kit.log("f02 after a spawner in the village: " + Villages.headcount(v.id()) + " folk; a founding near: "
            + Founding.near(level, heart, 400));
        helper.assertTrue(Villages.headcount(v.id()) == 4, "a spawner in a village adds one settler");
        helper.assertTrue(!Founding.near(level, heart, 400), "and puts no founding board up");

        // Far out: a board, and only one.
        final int fx = cx + 600;
        Kit.hold(level, fx, cz, 48);
        Kit.prepare(level, fx, cz, 48);
        BlockPos far = Kit.surface(level, fx, cz);
        level.setBlock(far, spawner, 3);
        VillageFolkSpawnerBlock.placed(level, far, spawner, null, 90.0F);
        BlockPos board = Founding.boardNear(level, far, 64);
        BlockPos second = Kit.surface(level, fx - 20, cz + 10);
        Founding.Outcome again = Founding.propose(level, second, null, 0.0F);
        Kit.log("f02 the board far out at " + board + "; a second spawner by it: " + again.message());
        helper.assertTrue(board != null, "a spawner far from any village puts a board up");
        helper.assertTrue(!again.ok() && Founding.status(level.getServer()).size() == 1, "a second by it puts up no other");
        // Taken down (as VillageBoardBlock does when a player breaks it): the founding is called off.
        level.setBlock(board, Blocks.AIR.defaultBlockState(), 3);
        Founding.calledOff(level, board);
        helper.assertTrue(!Founding.near(level, far, 200), "taking the waiting board down calls the founding off");
        helper.succeed();
    }

    // ============================================================ the command

    /**
     * /village found, as CI drives it: a village of twelve founded on open ground the way the
     * founding screen does it; nine hundred refused; a second founding by a village refused; and
     * /village spawnat as it always was.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "f03_found_command")
    public static void f03_found_command(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Founding.resetForTests(level.getServer());
        final int cx = 114000, cz = 12000;
        int outer = FoundingPlan.coreRadius(12) + FoundingPlan.BAND_MAX;
        Kit.hold(level, cx, cz, outer + 16);
        Kit.prepare(level, cx, cz, outer + 16);
        Kit.hill(level, cx - 20, cz - 20, 8, 6, 703);
        BlockPos heart = Kit.surface(level, cx, cz);
        List<String> refused = Kit.command(level, "village found 900 " + cx + " " + cz);
        Kit.log("f03 found 900: " + refused);
        helper.assertTrue(!Founding.near(level, heart, 200), "nine hundred is refused, and nothing is put up");
        List<String> said = Kit.command(level, "village found 12 " + cx + " " + cz);
        Kit.log("f03 found 12: " + said);
        helper.assertTrue(String.join(" ", said).contains("FOUNDING"), "the command founds a village: " + said);
        final boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0]) return;
            if (Founding.near(level, heart, 8)) {
                if (helper.getTick() % 100 == 0) Kit.log("f03 " + Kit.command(level, "village found status"));
                return;
            }
            done[0] = true;
            Villages.Village v = Villages.nearest(level, heart, 64);
            int folk = v == null ? 0 : Villages.headcount(v.id());
            Kit.log("f03 founded: " + (v == null ? "nothing" : Villages.name(v.id()) + " of " + folk));
            helper.assertTrue(v != null && folk == 12, "a village of twelve is founded: " + folk);
            List<String> twice = Kit.command(level, "village found 12 " + cx + " " + cz);
            Kit.log("f03 a second founding there: " + twice);
            helper.assertTrue(Villages.headcount(v.id()) == 12 && !Founding.near(level, heart, 200),
                "a second founding by the village is refused");
            List<String> spawnat = Kit.command(level, "village spawnat " + cx + " " + (cz + 6) + " 2");
            Kit.log("f03 spawnat: " + spawnat);
            helper.assertTrue(Villages.headcount(v.id()) == 14, "/village spawnat adds to the village as it always did");
            helper.succeed();
        });
    }
}
