package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.MineWorks;
import com.jrpetty.mcassistant.entity.ShaftStair;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * [stairs44] The main shaft's stair walked up and down as a player walks it (ShaftStair): a stand-in player moved by the
 * game's own collision and step-up (no jumping, the step-up a player has) from the way in at the pithead to the deepest
 * landing and back, by the inner and the outer lane; every rise half a block, the corner landings' risers stairs too; four
 * blocks of headroom; no walking cell dark. A shaft sunk in sand shored as it is cut and sand dropped into it cleared; an
 * older shaft (its corners a whole block over the next step, three high) put right by the works' rounds; earth, snow,
 * plants and a stray block in its way taken out, its torches and timbers left; a cave and water beside it walled off, a
 * drop at its open top fenced; its signs. x band 7,200,000 to 7,300,000, z 90000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class MineStairWalkGameTests {

    static final int TOP = MineWorksGameTests.TOP;

    // ------------------------------------------------------------------ the walk

    static final class Walk {
        double maxRise, maxDrop;
        int moves, fullJumpBumps, centreBumps, headBumps;
        String stuck;
        String line() {
            return (stuck == null ? "walked" : "STUCK " + stuck) + " in " + moves + " moves, highest rise " + String.format("%.2f", maxRise)
                + ", deepest drop " + String.format("%.2f", maxDrop) + "; a full jump would touch the roof at " + fullJumpBumps
                + " moves (" + centreBumps + " at a cell's middle), a 1.19 jump at " + headBumps;
        }
    }

    /** The walk by one lane: the way in, then each step's inner (or outer) cell, top first. */
    static List<BlockPos> lane(ServerLevel level, UUID v, int steps, boolean outer) {
        int[] s = MineWorks.shaftForTests(level, v);
        List<BlockPos> out = new ArrayList<>();
        out.add(new BlockPos(s[2] - 4, s[1], s[3] - (outer ? 3 : 2)));
        for (int i = 0; i < steps; i++) {
            List<BlockPos> c = MineWorks.stepForTests(level, v, i);
            out.add(outer ? c.get(c.size() - 1) : c.get(0));
        }
        return out;
    }

    private static boolean collides(ServerLevel level, ServerPlayer p, AABB box) {
        for (VoxelShape s : level.getBlockCollisions(p, box)) if (!s.isEmpty()) return true;
        return false;
    }

    /** Would a jump of this height from where it stands touch anything over it? */
    private static boolean bump(ServerLevel level, ServerPlayer p, double jump) {
        AABB b = p.getBoundingBox();
        return collides(level, p, new AABB(b.minX + 0.001, b.minY + 0.01, b.minZ + 0.001, b.maxX - 0.001, b.maxY + jump, b.maxZ - 0.001));
    }

    /**
     * A stand-in player walked from point to point by Entity.move (the game's collision and its step-up of 0.6), never
     * jumping, pulled down each move: stuck if it makes no way for forty moves.
     */
    static Walk walk(ServerLevel level, ServerPlayer p, List<BlockPos> pts) {
        Walk out = new Walk();
        BlockPos s0 = pts.get(0);
        p.setPos(s0.getX() + 0.5, s0.getY() + 0.3, s0.getZ() + 0.5);
        p.setDeltaMovement(Vec3.ZERO);
        for (int i = 0; i < 10; i++) p.move(MoverType.SELF, new Vec3(0, -0.3, 0));
        for (int k = 1; k < pts.size(); k++) {
            BlockPos t = pts.get(k);
            double tx = t.getX() + 0.5, tz = t.getZ() + 0.5;
            double best = Double.MAX_VALUE;
            int since = 0;
            for (int it = 0; it < 800; it++) {
                double dx = tx - p.getX(), dz = tz - p.getZ(), d = Math.sqrt(dx * dx + dz * dz);
                if (d < 0.06) break;
                double st = Math.min(0.12, d);
                double y0 = p.getY();
                p.move(MoverType.SELF, new Vec3(dx / d * st, -0.3, dz / d * st));
                out.moves++;
                out.maxRise = Math.max(out.maxRise, p.getY() - y0);
                out.maxDrop = Math.max(out.maxDrop, y0 - p.getY());
                if (bump(level, p, 1.2522)) out.fullJumpBumps++;
                if (bump(level, p, 1.19)) out.headBumps++;
                if (d < best - 0.005) { best = d; since = 0; }
                else if (++since > 40) {
                    out.stuck = "short of " + t.toShortString() + " (point " + k + " of " + (pts.size() - 1) + ") at " + String.format("%.2f %.2f %.2f", p.getX(), p.getY(), p.getZ());
                    return out;
                }
            }
            if (Math.abs(p.getY() - t.getY()) > 0.75) {
                out.stuck = "at " + t.toShortString() + " but at height " + String.format("%.2f", p.getY());
                return out;
            }
            if (bump(level, p, 1.2522)) out.centreBumps++;
        }
        return out;
    }

    /** Both lanes, down and up. */
    static List<Walk> walks(ServerLevel level, ServerPlayer p, UUID v, int steps) {
        List<Walk> out = new ArrayList<>();
        for (boolean outer : new boolean[]{ false, true }) {
            List<BlockPos> down = lane(level, v, steps, outer);
            out.add(walk(level, p, down));
            List<BlockPos> up = new ArrayList<>(down);
            Collections.reverse(up);
            out.add(walk(level, p, up));
        }
        return out;
    }

    static boolean allWalked(List<Walk> ws) {
        for (Walk w : ws) if (w.stuck != null || w.maxRise > 0.61 || w.headBumps > 0 || w.centreBumps > 0) return false;
        return true;
    }

    static String lines(List<Walk> ws) {
        String[] names = { "inner down", "inner up", "outer down", "outer up" };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ws.size(); i++) sb.append("\n   ").append(names[i]).append(": ").append(ws.get(i).line());
        return sb.toString();
    }

    static ServerPlayer walker(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setInvulnerable(true);
        return p;
    }

    static void letGo(ServerLevel level, ServerPlayer p) {
        level.getServer().getPlayerList().remove(p);
    }

    // ------------------------------------------------------------------ what the stair is made of

    /** Each step's floors: stairs facing up the stair on the steps and the corners' risers, flat and firm on the rest. */
    static String floors(ServerLevel level, UUID v, int steps) {
        int wrong = 0, risers = 0, stairs = 0, flats = 0;
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < steps; i++) {
            List<BlockPos> riser = i % 4 == 0 ? ShaftStair.risersForTests(level, v, i) : List.of();
            Direction up = ShaftStair.upForTests(i);
            for (BlockPos c : MineWorks.stepForTests(level, v, i)) {
                BlockState f = level.getBlockState(c.below());
                boolean stepped = i % 4 != 0 || riser.contains(c);
                boolean ok = stepped ? f.getBlock() instanceof StairBlock && f.getValue(StairBlock.FACING) == up && f.getValue(StairBlock.HALF) == Half.BOTTOM
                    : f.isFaceSturdy(level, c.below(), Direction.UP);
                if (!ok) { wrong++; if (bad.length() < 300) bad.append(" ").append(i).append("@").append(c.toShortString()).append("=").append(f.getBlock()); }
                else if (stepped && i % 4 == 0) risers++;
                else if (stepped) stairs++;
                else flats++;
            }
        }
        return wrong + "|" + risers + " risers, " + stairs + " step stairs, " + flats + " flat landing cells" + (wrong > 0 ? "; wrong:" + bad : "");
    }

    /** The darkest walking cell (block light at its feet) and how many are dark. */
    static int[] light(ServerLevel level, UUID v, int steps) {
        int min = 99, dark = 0;
        for (int i = 0; i < steps; i++) {
            for (BlockPos c : MineWorks.stepForTests(level, v, i)) {
                int b = level.getBrightness(LightLayer.BLOCK, c);
                min = Math.min(min, b);
                if (b == 0) dark++;
            }
        }
        return new int[]{ min, dark };
    }

    /** What could fall left beside the stair's way (its walls four high, its roof, under its floors), and what stands in its way. */
    static int[] loose(ServerLevel level, UUID v, int steps) {
        // The stair's own cells (its floors and the space over them, the next step down too): not its walls.
        java.util.Set<Long> way = new java.util.HashSet<>();
        for (int i = 0; i <= steps; i++) for (BlockPos c : MineWorks.stepForTests(level, v, i)) for (int h = -1; h < ShaftStair.HEAD; h++) way.add(c.above(h).asLong());
        int falls = 0, inWay = 0;
        for (int i = 0; i < steps; i++) {
            for (BlockPos c : MineWorks.stepForTests(level, v, i)) {
                for (int h = 0; h < ShaftStair.HEAD; h++) {
                    BlockState s = level.getBlockState(c.above(h));
                    if (!s.isAir() && !(s.getBlock() instanceof WallTorchBlock) && !(s.getBlock() instanceof LanternBlock)) inWay++;
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos n = c.relative(d).above(h);
                        if (!way.contains(n.asLong()) && ShaftStair.falls(level.getBlockState(n))) falls++;
                    }
                }
                if (ShaftStair.falls(level.getBlockState(c.above(ShaftStair.HEAD)))) falls++;
                if (ShaftStair.falls(level.getBlockState(c.below()))) falls++;
                if (ShaftStair.falls(level.getBlockState(c.below(2)))) falls++;
            }
        }
        return new int[]{ falls, inWay };
    }

    static String lastEvents(int n) {
        List<String> e = MineWorks.eventsForTests();
        return String.join(" | ", e.subList(Math.max(0, e.size() - n), e.size()));
    }

    static VillageFolkEntity dresser(MineWorksGameTests.Rig r) {
        VillageFolkEntity m = r.miners().get(0);
        MineWorksGameTests.kit(m, Items.IRON_PICKAXE);
        m.setNoAi(true);
        return m;
    }

    // ============================================================ sw01: up and down without a jump

    /**
     * Three turns of the shaft and a step (forty-nine steps, a level's landing on the way) sunk and dressed as a miner
     * dresses them, a few lanterns in its pack: a stand-in player walks from the way in to the deepest step and back up, by
     * either lane, with the game's step-up and never a jump. Every step's floor a stair facing up, the corners' risers
     * stairs, the rest of each landing flat; a jump touches the roof nowhere at a cell's middle; no walking cell dark.
     */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = "sw01_walk_up_and_down")
    public static void sw01_walk_up_and_down(GameTestHelper helper) {
        MineWorksGameTests.Rig r = MineWorksGameTests.rig(helper, 7202000, 1);
        ServerLevel level = r.level();
        ShaftStair.resetForTests(r.v());
        int steps = 49;
        MineWorksGameTests.tower(r, TOP - 50);
        VillageFolkEntity m = dresser(r);
        m.insertItem(new ItemStack(Items.LANTERN, 6));
        MineWorks.sinkForTests(level, r.v(), steps, m);
        MineWorks.roomForTests(level, r.v(), MineWorks.COAL);
        MineWorks.tick(level, Villages.get(r.v()));
        ShaftStair.keepAllForTests(level, r.v());
        ServerPlayer p = walker(helper);
        final int[] tick = { 0 };
        MineSiteGameTests.each(helper, () -> {
            if (++tick[0] < 60) return false;                       // the light worked out
            List<Walk> ws = walks(level, p, r.v(), steps);
            String fl = floors(level, r.v(), steps);
            int[] lt = light(level, r.v(), steps);
            int lanterns = 0;
            for (int i = 0; i < steps; i += 4) for (BlockPos c : MineWorks.stepForTests(level, r.v(), i))
                for (int h = 0; h < ShaftStair.HEAD; h++) if (level.getBlockState(c.above(h)).getBlock() instanceof LanternBlock) lanterns++;
            int[] n = ShaftStair.countsForTests();
            Kit.log("[VT] sw01 " + steps + " steps (Y" + TOP + " to Y" + (TOP - 36) + "): floors " + fl + "; light: darkest walking cell " + lt[0] + ", dark cells " + lt[1]
                + "; lanterns at the landings " + lanterns + "; counts " + java.util.Arrays.toString(n) + lines(ws));
            letGo(level, p);
            dump(level, r, "sw01", TOP - 26, TOP + 6, 9);
            Kit.Expect ok = new Kit.Expect();
            ok.that(allWalked(ws), "walked down and up by both lanes, every rise within the step-up, no jump:" + lines(ws));
            ok.that(fl.startsWith("0|"), "every floor as it should be: " + fl);
            ok.that(lt[1] == 0 && lt[0] >= 1, "no walking cell dark: darkest " + lt[0] + ", dark " + lt[1]);
            ok.that(lanterns >= 6, "a lantern at the landings (six in the pack): " + lanterns);
            helper.assertTrue(ok.clean(), ok.summary());
            MineWorksGameTests.done(helper);
            return true;
        });
    }

    // ============================================================ sw02: a mine on sand

    /**
     * The pithead on sand eight deep, gravel in pockets through it: a miner sinks the shaft through it. As it is cut nothing
     * that could fall is left in the stair's walls, roof or under its floors; the steps are clear and walked up and down.
     * Then sand dropped from the sky onto an open top step: it lands in the way, and the works' next round clears it.
     */
    @GameTest(template = "empty", timeoutTicks = 21000, batch = "sw02_sand")
    public static void sw02_sand(GameTestHelper helper) {
        MineWorksGameTests.Rig r = MineWorksGameTests.rig(helper, 7206000, 1);
        ServerLevel level = r.level();
        ShaftStair.resetForTests(r.v());
        MineWorksGameTests.tower(r, TOP - 30);
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int x = r.sx() - 6; x <= r.sx() + 6; x++) for (int z = r.sz() - 6; z <= r.sz() + 6; z++) for (int y = TOP - 8; y < TOP; y++) {
            boolean gravel = Math.floorMod(x * 7 + z * 13 + y * 5, 9) == 0 || (Math.abs(x - r.sx()) == 4 && y >= TOP - 6 && y <= TOP - 4);
            level.setBlock(q.set(x, y, z), (gravel ? Blocks.GRAVEL : Blocks.SAND).defaultBlockState(), 2);
        }
        VillageFolkEntity m = r.miners().get(0);
        MineWorksGameTests.kit(m, Items.IRON_PICKAXE);
        m.insertItem(new ItemStack(Items.IRON_SHOVEL));
        MineWorksGameTests.zone(r, m, 16);
        m.teleportTo(r.sx() - 5.5, TOP, r.sz() - 1.5);
        MineWorksGameTests.fast(level);
        final int want = 16;
        final int[] tick = { 0 }, phase = { 0 }, at = { 0 };
        final BlockPos[] drop = { null };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            if (phase[0] == 0) {
                MineWorksGameTests.keepAtIt(r, m);
                int sunk = MineWorks.shaftForTests(level, r.v())[0];
                if (tick[0] % 1000 == 0) Kit.log("[VT] sw02 @" + tick[0] + " sunk " + sunk + " | " + lastEvents(3));
                if (sunk < want && tick[0] < 19000) return false;
                int[] cut = loose(level, r.v(), Math.min(sunk, want));
                int[] n0 = ShaftStair.countsForTests();
                MineWorks.tick(level, Villages.get(r.v()));
                int[] after = loose(level, r.v(), Math.min(sunk, want));
                Kit.log("[VT] sw02 sunk " + sunk + " steps in " + tick[0] + " ticks through the sand: as cut, loose beside the way " + cut[0] + ", in the way " + cut[1]
                    + "; after the round " + after[0] + " / " + after[1] + "; counts as cut " + java.util.Arrays.toString(n0) + " | " + lastEvents(4));
                helper.assertTrue(sunk >= want, "the shaft sunk through the sand: " + sunk);
                helper.assertTrue(cut[0] == 0, "nothing loose left beside the stair as it was cut: " + cut[0]);
                helper.assertTrue(after[0] == 0 && after[1] == 0, "nothing loose beside it nor in its way after the round: " + after[0] + " / " + after[1]);
                m.setNoAi(true);
                m.teleportTo(r.sx() + 8.5, TOP, r.sz() + 8.5);
                // Sand from the sky onto the third step (open to it).
                BlockPos c = MineWorks.stepForTests(level, r.v(), 2).get(0);
                drop[0] = c;
                level.setBlock(c.above(8), Blocks.SAND.defaultBlockState(), 3);
                phase[0] = 1;
                at[0] = tick[0];
                return false;
            }
            if (phase[0] == 1) {
                if (tick[0] - at[0] < 60) return false;
                BlockState landed = level.getBlockState(drop[0]);
                Kit.log("[VT] sw02 the sand dropped onto the third step: " + landed.getBlock());
                helper.assertTrue(landed.is(Blocks.SAND), "the sand landed on the step: " + landed);
                MineWorks.tick(level, Villages.get(r.v()));
                BlockState now = level.getBlockState(drop[0]);
                ServerPlayer p = walker(helper);
                List<Walk> ws = walks(level, p, r.v(), want);
                letGo(level, p);
                String fl = floors(level, r.v(), want);
                Kit.log("[VT] sw02 after the round the step holds " + now.getBlock() + "; floors " + fl + lines(ws));
                dump(level, r, "sw02", TOP - 14, TOP + 6, 9);
                Kit.Expect ok = new Kit.Expect();
                ok.that(now.isAir(), "the sand cleared off the step: " + now);
                ok.that(allWalked(ws), "walked up and down:" + lines(ws));
                ok.that(fl.startsWith("0|"), "the floors: " + fl);
                helper.assertTrue(ok.clean(), ok.summary());
                MineWorksGameTests.done(helper);
                return true;
            }
            return false;
        });
    }

    // ============================================================ sw03: an older shaft put right

    /**
     * A shaft dressed the old way (the steps stairs, each corner landing a whole block over the next step, three high, the
     * timber set's cap a block lower, an older stop's lever on what is now a riser): a stand-in player cannot walk up it.
     * The works' rounds put it right: the risers laid, the fourth row cut and the sets raised, the lever taken off without a
     * drop; then it is walked up and down.
     */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = "sw03_retrofit")
    public static void sw03_retrofit(GameTestHelper helper) {
        MineWorksGameTests.Rig r = MineWorksGameTests.rig(helper, 7210000, 1);
        ServerLevel level = r.level();
        ShaftStair.resetForTests(r.v());
        int steps = 33;
        MineWorksGameTests.tower(r, TOP - 30);
        MineWorks.sinkForTests(level, r.v(), steps, null);
        String wood = "spruce";
        for (int i = 0; i < steps; i++) {
            Direction up = ShaftStair.upForTests(i);
            List<BlockPos> cells = MineWorks.stepForTests(level, r.v(), i);
            for (BlockPos c : cells) {
                level.setBlock(c.above(3), Blocks.STONE.defaultBlockState(), 2);                  // three high
                if (i % 4 != 0) level.setBlock(c.below(), Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, up), 2);
                else level.setBlock(c.below(), Blocks.STONE.defaultBlockState(), 2);             // the corner a whole block
            }
            if (i % 4 == 2) for (BlockPos c : cells)
                level.setBlock(c.above(2).above(), Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, up.getClockWise().getAxis()), 2);
        }
        // An older stop's lever on the floor of the second corner's riser (its lever's place before the risers).
        BlockPos oldLever = null;
        for (BlockPos c : ShaftStair.risersForTests(level, r.v(), 4)) {
            if (!c.equals(MineWorks.stepForTests(level, r.v(), 4).get(0))) oldLever = c;
        }
        level.setBlock(oldLever, Blocks.LEVER.defaultBlockState().setValue(net.minecraft.world.level.block.LeverBlock.FACE,
            net.minecraft.world.level.block.state.properties.AttachFace.FLOOR), 3);
        ServerPlayer p = walker(helper);
        List<BlockPos> up = lane(level, r.v(), steps, false);
        Collections.reverse(up);
        Walk before = walk(level, p, up);
        String flBefore = floors(level, r.v(), steps);
        final BlockPos lever = oldLever;
        final int[] tick = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            if (tick[0] <= 4) { MineWorks.tick(level, Villages.get(r.v())); return false; }   // a few of the works' rounds
            if (tick[0] < 40) return false;
            List<Walk> ws = walks(level, p, r.v(), steps);
            String fl = floors(level, r.v(), steps);
            int high = 0, cells = 0;
            for (int i = 0; i < steps; i++) for (BlockPos c : MineWorks.stepForTests(level, r.v(), i)) {
                cells++;
                boolean clear = true;
                for (int h = 0; h < ShaftStair.HEAD; h++) clear &= level.getBlockState(c.above(h)).getCollisionShape(level, c.above(h)).isEmpty();
                if (clear) high++;
            }
            int sets = 0;
            for (int i = 2; i < steps; i += 4) if (level.getBlockState(MineWorks.stepForTests(level, r.v(), i).get(0).above(ShaftStair.HEAD)).is(BlockTags.LOGS)) sets++;
            boolean leverGone = !level.getBlockState(lever).is(Blocks.LEVER);
            int leverItems = level.getEntitiesOfClass(ItemEntity.class, new AABB(lever).inflate(6), e -> e.getItem().is(Items.LEVER)).size();
            Kit.log("[VT] sw03 before: walking up " + before.line() + "; floors " + flBefore);
            Kit.log("[VT] sw03 after the rounds: floors " + fl + "; four high " + high + "/" + cells + ", sets raised " + sets + "; the old lever gone " + leverGone
                + " (lever items about " + leverItems + "); counts " + java.util.Arrays.toString(ShaftStair.countsForTests()) + lines(ws));
            letGo(level, p);
            Kit.Expect ok = new Kit.Expect();
            ok.that(before.stuck != null, "the old stair could not be walked up: " + before.line());
            ok.that(allWalked(ws), "put right, walked down and up by both lanes:" + lines(ws));
            ok.that(fl.startsWith("0|"), "every floor as it should be: " + fl);
            ok.that(high == cells, "four high everywhere: " + high + "/" + cells);
            ok.that(sets >= (steps - 2) / 4, "the timber sets raised to the new roof: " + sets);
            ok.that(leverGone && leverItems == 0, "the old lever taken off its riser, nothing dropped: " + leverGone + " " + leverItems);
            helper.assertTrue(ok.clean(), ok.summary());
            MineWorksGameTests.done(helper);
            return true;
        });
    }

    // ============================================================ sw04: kept clear, safe and signed

    /**
     * A dressed shaft with a level's landing on it; then earth, a drift of snow, a flower, a cobweb, sand and a stray plank
     * in its way, a cave and a spring of water opening beside two of its steps and a drop opened beside its top landing;
     * stone bricks to spare in the stores. The works' rounds take out what is in the way (its torches, lanterns and timber
     * left), wall off the cave and the water, fence the drop, sign the level's landing and the pithead, and lay some of the
     * stair in stone bricks.
     */
    @GameTest(template = "empty", timeoutTicks = 1200, batch = "sw04_clear_safe_signed")
    public static void sw04_clear_safe_signed(GameTestHelper helper) {
        MineWorksGameTests.Rig r = MineWorksGameTests.rig(helper, 7214000, 1);
        ServerLevel level = r.level();
        UUID v = r.v();
        ShaftStair.resetForTests(v);
        int steps = 33;
        MineWorksGameTests.tower(r, TOP - 30);
        VillageFolkEntity m = dresser(r);
        MineWorks.sinkForTests(level, v, steps, m);
        MineWorks.roomForTests(level, v, MineWorks.COAL);
        ShaftStair.keepAllForTests(level, v);
        // The stores: stone bricks to spare.
        BlockPos chest = r.heart().offset(3, 0, 3);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, chest);
        Container box = (Container) level.getBlockEntity(chest);
        for (int i = 0; i < 4; i++) box.setItem(i, new ItemStack(Items.STONE_BRICKS, 64));
        box.setChanged();
        // In the way.
        BlockPos dirt = MineWorks.stepForTests(level, v, 6).get(0), snow = MineWorks.stepForTests(level, v, 8).get(3), flower = MineWorks.stepForTests(level, v, 9).get(1);
        BlockPos web = MineWorks.stepForTests(level, v, 10).get(0).above(2), sand = MineWorks.stepForTests(level, v, 11).get(1), plank = MineWorks.stepForTests(level, v, 13).get(0).above();
        level.setBlock(dirt, Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(snow, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3), 3);
        level.setBlock(flower, Blocks.POPPY.defaultBlockState(), 3);
        level.setBlock(web, Blocks.COBWEB.defaultBlockState(), 3);
        level.setBlock(sand, Blocks.SAND.defaultBlockState(), 3);
        level.setBlock(plank, Blocks.OAK_PLANKS.defaultBlockState(), 3);
        // Kept: the torches, lanterns and timbers the stair has now.
        int keptBefore = kept(level, v, steps);
        // A cave beside the twenty-second step (a drop in it), a spring beside the twenty-sixth, a drop beside the top landing.
        List<BlockPos> s21 = MineWorks.stepForTests(level, v, 21), s25 = MineWorks.stepForTests(level, v, 25);
        Direction out21 = Direction.fromDelta(s21.get(1).getX() - s21.get(0).getX(), 0, s21.get(1).getZ() - s21.get(0).getZ());
        BlockPos caveWall = s21.get(1).relative(out21);
        for (int a = 0; a <= 2; a++) for (int y = -3; y <= 3; y++) for (int b = -1; b <= 1; b++)
            level.setBlock(caveWall.relative(out21, a).relative(out21.getClockWise(), b).above(y), Blocks.AIR.defaultBlockState(), 2);
        Direction out25 = Direction.fromDelta(s25.get(1).getX() - s25.get(0).getX(), 0, s25.get(1).getZ() - s25.get(0).getZ());
        BlockPos spring = s25.get(1).relative(out25).above();
        level.setBlock(spring, Blocks.WATER.defaultBlockState(), 3);
        BlockPos edge = new BlockPos(r.sx() - 2, TOP, r.sz() - 4);
        for (int y = TOP - 4; y < TOP; y++) level.setBlock(new BlockPos(edge.getX(), y, edge.getZ()), Blocks.AIR.defaultBlockState(), 2);
        final int[] tick = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            if (tick[0] <= 5) { MineWorks.tick(level, Villages.get(v)); return false; }
            if (tick[0] < 100) return false;
            if (tick[0] == 100) ShaftStair.keepAllForTests(level, v);       // what the water left on its way dried up, a torch it washed off put back
            String gone = "";
            for (BlockPos x : new BlockPos[]{ dirt, snow, flower, web, sand, plank }) if (!level.getBlockState(x).isAir()) gone += " " + x.toShortString() + "=" + level.getBlockState(x).getBlock();
            int keptAfter = kept(level, v, steps);
            int caveOpen = 0;
            for (int h = 0; h < ShaftStair.HEAD; h++) if (level.getBlockState(caveWall.above(h)).getCollisionShape(level, caveWall.above(h)).isEmpty()) caveOpen++;
            int wet = 0;
            for (int i = 0; i < steps; i++) for (BlockPos c : MineWorks.stepForTests(level, v, i)) for (int h = 0; h < ShaftStair.HEAD; h++) {
                if (!level.getFluidState(c.above(h)).isEmpty()) wet++;
                for (Direction d : Direction.Plane.HORIZONTAL) if (!level.getFluidState(c.relative(d).above(h)).isEmpty()) wet++;
            }
            boolean fenced = level.getBlockState(edge).getBlock() instanceof FenceBlock;
            String levelSign = signNear(level, MineWorks.stepForTests(level, v, 16).get(3), 3);
            String pithead = signNear(level, new BlockPos(r.sx() - 5, TOP, r.sz() - 4), 2);
            int bricks = 0;
            for (int i = 0; i < steps; i++) for (BlockPos c : MineWorks.stepForTests(level, v, i)) {
                Block b = level.getBlockState(c.below()).getBlock();
                if (b == Blocks.STONE_BRICK_STAIRS || b == Blocks.STONE_BRICKS) bricks++;
            }
            int plankItems = level.getEntitiesOfClass(ItemEntity.class, new AABB(plank).inflate(4), e -> e.getItem().is(Items.OAK_PLANKS)).size();
            ServerPlayer p = walker(helper);
            List<Walk> ws = walks(level, p, v, steps);
            letGo(level, p);
            Kit.log("[VT] sw04 still in the way:" + (gone.isEmpty() ? " nothing" : gone) + " (the plank dropped where it stood: " + plankItems + "); torches, lanterns and timber "
                + keptBefore + " before, " + keptAfter + " after; the cave's mouth open " + caveOpen + "/" + ShaftStair.HEAD + ", water by the way " + wet + ", the drop fenced " + fenced
                + "; the level's sign [" + levelSign + "], the pithead's [" + pithead + "]; floors laid in stone brick " + bricks + "; counts "
                + java.util.Arrays.toString(ShaftStair.countsForTests()) + lines(ws));
            Kit.Expect ok = new Kit.Expect();
            ok.that(gone.isEmpty(), "everything in the way taken out:" + gone);
            ok.that(keptAfter >= keptBefore, "the torches, lanterns and timbers left: " + keptBefore + " -> " + keptAfter);
            ok.that(caveOpen == 0, "the cave walled off: " + caveOpen);
            ok.that(wet == 0, "the water sealed: " + wet);
            ok.that(fenced, "the drop by the top landing fenced");
            ok.that(levelSign.contains("Coal Level") && levelSign.contains("Y "), "the level's sign: " + levelSign);
            ok.that(pithead.contains("Main Shaft") && pithead.contains("Stairs"), "the pithead's sign: " + pithead);
            ok.that(bricks >= 8, "some of the stair laid in stone bricks from the stores: " + bricks);
            ok.that(allWalked(ws), "walked down and up:" + lines(ws));
            helper.assertTrue(ok.clean(), ok.summary());
            MineWorksGameTests.done(helper);
            return true;
        });
    }

    /** The blocks round the shaft, a layer a line ("[VT] dump TAG y|x,z,block,facing,half;..."), for a picture of it. */
    static void dump(ServerLevel level, MineWorksGameTests.Rig r, String tag, int y0, int y1, int half) {
        for (int y = y0; y <= y1; y++) {
            StringBuilder sb = new StringBuilder();
            for (int x = r.sx() - half; x <= r.sx() + half; x++) for (int z = r.sz() - half; z <= r.sz() + half; z++) {
                BlockState s = level.getBlockState(new BlockPos(x, y, z));
                if (s.isAir()) continue;
                String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
                String f = s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                    ? s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING).getName() : "-";
                String hh = s.hasProperty(StairBlock.HALF) ? s.getValue(StairBlock.HALF).getSerializedName() : "-";
                if (s.getBlock() instanceof LanternBlock) hh = s.getValue(LanternBlock.HANGING) ? "hanging" : "-";
                sb.append(x - r.sx()).append(',').append(z - r.sz()).append(',').append(name).append(',').append(f).append(',').append(hh).append(';');
            }
            Kit.log("[VT] dump " + tag + " " + (y - TOP) + "|" + sb);
        }
    }

    /** The works' own things in and round the stair: torches, lanterns, timbers. */
    static int kept(ServerLevel level, UUID v, int steps) {
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < steps; i++) for (BlockPos c : MineWorks.stepForTests(level, v, i)) {
            for (int h = -1; h <= ShaftStair.HEAD; h++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                BlockPos p = c.offset(dx, h, dz);
                BlockState s = level.getBlockState(p);
                if ((s.getBlock() instanceof WallTorchBlock || s.getBlock() instanceof LanternBlock || s.is(BlockTags.LOGS)) && seen.add(p.asLong())) {}
            }
        }
        return seen.size();
    }

    /** The text of the first sign within so far of here, or "". */
    static String signNear(ServerLevel level, BlockPos at, int r) {
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-r, -1, -r), at.offset(r, 2, r))) {
            if (level.getBlockEntity(p) instanceof SignBlockEntity sb) {
                StringBuilder s = new StringBuilder();
                for (int i = 0; i < 4; i++) s.append(i == 0 ? "" : " / ").append(sb.getFrontText().getMessage(i, false).getString());
                return s.toString();
            }
        }
        return "";
    }
}
