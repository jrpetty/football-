package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.entity.MineLift;
import com.jrpetty.mcassistant.entity.MineWorks;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [tp48] The foot of the mine's cage well (MineLift): the pool one water block a block under the deepest stop's floor, shut
 * in with stone bricks on its four sides and under it; a doorway in the pillar over it, three high on a brick floor, on the
 * side toward the stair coming down to the stop; the stop's other window cells barred and opened as a gate as before.
 * Kept so for minutes of rides and the works' rounds, whatever is put in the doorway; an older mine's pool at the stop's
 * floor, the doorway stoned up, put right; a player who comes down the well climbs out of the pool and walks back to the
 * landing by the game's own movement, never put out by the watch; folk riding the cage at the bottom never in the water,
 * never walked into the doorway, held at its edge; the cage coming down onto a player in the pool steps them up into the
 * doorway. x band 8,500,000 to 8,600,000, z 90000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class MineWellGameTests {

    // ------------------------------------------------------------------ the foot as built

    /** The foot of a rig's well: the deepest stop, the pool, the doorway. */
    record Foot(MineLift.Stop deep, MineLift.Stop coal, MineLift.Stop top, BlockPos well, BlockPos pool, BlockPos door, int bottom) {
        /** The doorway's way out from the well (a unit step). */
        int[] out() { return new int[]{ door.getX() - well.getX(), door.getZ() - well.getZ() }; }
        /** The stair cell the doorway looks out onto. */
        BlockPos outside() { return door.offset(out()[0], 0, out()[1]); }
    }

    static Foot foot(MineWorksGameTests.Rig r) {
        List<MineLift.Stop> stops = MineLift.stops(r.v());
        MineLift.Stop deep = stops.get(stops.size() - 1);
        BlockPos door = MineLift.doorwayForTests(r.v());
        return new Foot(deep, stops.get(1), stops.get(0), new BlockPos(r.sx(), deep.y(), r.sz()), new BlockPos(r.sx(), deep.y() - 1, r.sz()), door, deep.y());
    }

    /** The cells of a stop's window that keep their bars (at the deepest stop, all but the doorway), two high. */
    static List<BlockPos> barCells(MineWorksGameTests.Rig r, MineLift.Stop s, BlockPos door) {
        int[] o = { Integer.signum(s.board().getX() - r.sx()), Integer.signum(s.board().getZ() - r.sz()) };
        List<BlockPos> out = new ArrayList<>();
        for (int h = 0; h < 2; h++) {
            for (BlockPos c : new BlockPos[]{ new BlockPos(r.sx() + o[0], s.y() + h, r.sz() + o[1]), new BlockPos(r.sx() + o[0], s.y() + h, r.sz()),
                    new BlockPos(r.sx(), s.y() + h, r.sz() + o[1]) }) {
                if (door != null && c.getX() == door.getX() && c.getZ() == door.getZ()) continue;
                out.add(c);
            }
        }
        return out;
    }

    static boolean all(ServerLevel level, List<BlockPos> cells, java.util.function.Predicate<BlockState> what) {
        for (BlockPos c : cells) if (!what.test(level.getBlockState(c))) return false;
        return true;
    }

    static boolean doorOpen(ServerLevel level, Foot f) {
        for (int h = 0; h < 3; h++) if (!level.getBlockState(f.door().above(h)).isAir()) return false;
        return true;
    }

    /** Every block of water round the shaft (thirteen across, from under the pool to over the pithead) but the pool. */
    static List<BlockPos> strayWater(MineWorksGameTests.Rig r, Foot f) {
        ServerLevel level = r.level();
        List<BlockPos> out = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = r.sx() - 9; x <= r.sx() + 9; x++)
            for (int z = r.sz() - 9; z <= r.sz() + 9; z++)
                for (int y = f.bottom() - 4; y <= MineWorksGameTests.TOP + 2; y++) {
                    p.set(x, y, z);
                    if (p.equals(f.pool())) continue;
                    if (!level.getFluidState(p).isEmpty()) out.add(p.immutable());
                }
        return out;
    }

    /** What is wrong with the foot of the well, as built: nothing, or each fault. */
    static List<String> faults(MineWorksGameTests.Rig r, Foot f, boolean gatesShut) {
        ServerLevel level = r.level();
        List<String> bad = new ArrayList<>();
        FluidState pool = level.getFluidState(f.pool());
        if (!pool.is(net.minecraft.tags.FluidTags.WATER) || !pool.isSource()) bad.add("the pool not a water source: " + level.getBlockState(f.pool()));
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos s = f.pool().relative(d);
            BlockState st = level.getBlockState(s);
            if (!st.getFluidState().isEmpty() || !st.isCollisionShapeFullBlock(level, s)) bad.add("the pool's " + d + " side open: " + st);
        }
        BlockPos under = f.pool().below();
        if (!level.getBlockState(under).isFaceSturdy(level, under, Direction.UP) || !level.getFluidState(under).isEmpty()) bad.add("no floor under the pool: " + level.getBlockState(under));
        if (!level.getBlockState(f.well()).isAir()) bad.add("the well over the pool not open: " + level.getBlockState(f.well()));
        if (!doorOpen(level, f)) bad.add("the doorway stopped: " + level.getBlockState(f.door()) + " / " + level.getBlockState(f.door().above()) + " / " + level.getBlockState(f.door().above(2)));
        BlockPos df = f.door().below();
        if (!level.getBlockState(df).isFaceSturdy(level, df, Direction.UP) || !level.getFluidState(df).isEmpty()) bad.add("no floor in the doorway: " + level.getBlockState(df));
        // The doorway onto the stair coming down to the stop: its outside cell the inner cell of the step two before the corner,
        // a step over the stop's floor (a stair laid as its floor), never a riser of the corner nor the stair going on down.
        List<BlockPos> before = MineWorks.stepForTests(level, r.v(), f.deep().index() - 2);
        BlockPos out = f.outside();
        if (before.isEmpty() || before.get(0).getX() != out.getX() || before.get(0).getZ() != out.getZ() || before.get(0).getY() != f.bottom() + 1)
            bad.add("the doorway does not look out onto the stair coming down: outside " + out.toShortString() + ", step " + (f.deep().index() - 2) + " " + before);
        if (gatesShut) {
            if (!all(level, barCells(r, f.deep(), f.door()), s -> s.is(Blocks.IRON_BARS))) bad.add("the deepest stop's window not barred");
            if (barCells(r, f.deep(), f.door()).size() != 4) bad.add("the deepest stop's window not four cells");
            if (!all(level, barCells(r, f.coal(), null), s -> s.is(Blocks.IRON_BARS))) bad.add("the coal stop's window not barred");
        }
        List<BlockPos> stray = strayWater(r, f);
        if (!stray.isEmpty()) bad.add(stray.size() + " blocks of water outside the pool, first " + stray.get(0).toShortString() + " " + level.getBlockState(stray.get(0)));
        return bad;
    }

    /** A letter for a block of a section through the foot. */
    static char letter(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (!s.getFluidState().isEmpty()) return s.getFluidState().isSource() ? 'W' : 'w';
        if (s.isAir()) return '.';
        if (s.is(Blocks.STONE_BRICKS) || s.is(Blocks.DEEPSLATE_BRICKS)) return '#';
        if (s.getBlock() instanceof IronBarsBlock) return 'B';
        if (s.getBlock() instanceof StairBlock) return '/';
        if (s.is(Blocks.COBBLESTONE) || s.is(Blocks.COBBLED_DEEPSLATE)) return 'c';
        if (s.is(net.minecraft.tags.BlockTags.LOGS)) return 'L';
        if (s.is(Blocks.LEVER) || s.is(Blocks.STONE_BUTTON)) return 'l';
        if (s.getLightEmission() > 0) return 't';
        if (s.isSolidRender(level, p)) return 'S';
        return '?';
    }

    /** Two sections through the well's foot, logged a row a line (the picture is drawn from the log). */
    static void section(MineWorksGameTests.Rig r, Foot f, String tag) {
        ServerLevel level = r.level();
        int[] a = f.out(), b = { -a[1], a[0] };
        for (int[] ax : new int[][]{ a, b }) {
            String name = ax == a ? "A" : "B";
            Kit.log("[VT] " + tag + " section " + name + " along " + ax[0] + "," + ax[1] + " from the well, door " + f.door().toShortString() + ", well " + f.well().toShortString());
            for (int y = f.bottom() + 5; y >= f.bottom() - 3; y--) {
                StringBuilder sb = new StringBuilder();
                for (int t = -5; t <= 5; t++) sb.append(letter(level, new BlockPos(f.well().getX() + t * ax[0], y, f.well().getZ() + t * ax[1])));
                Kit.log("[VT] " + tag + " section " + name + " y" + (y - f.bottom()) + " " + sb);
            }
        }
    }

    // ------------------------------------------------------------------ riding

    /** A rider taken round the stops in turn (coal, deepest, pithead), a leg asked as the last one is done. */
    static final class Rounds {
        final VillageFolkEntity m;
        final List<MineLift.Stop> legs;
        int leg, done;
        Rounds(VillageFolkEntity m, List<MineLift.Stop> legs) { this.m = m; this.legs = legs; }

        void tick(ServerLevel level, UUID v) {
            MineLift.State st = MineLift.state(m.getUUID());
            MineLift.Stop from = legs.get((leg + legs.size() - 1) % legs.size()), to = legs.get(leg);
            if (st == MineLift.State.ARRIVED) {
                MineLift.done(m.getUUID());
                done++;
                leg = (leg + 1) % legs.size();
                from = to;
                to = legs.get(leg);
                MineLift.ask(m, v, from.index(), to.index());
            } else if (st == MineLift.State.NONE) {
                // Not asked yet, or dropped from the trip (not at the stop): to the stop's corner, and asked.
                m.teleportTo(from.board().getX() + 0.5, from.board().getY(), from.board().getZ() + 0.5);
                MineLift.ask(m, v, from.index(), to.index());
            }
        }
    }

    static MineWorksGameTests.Rig rig(GameTestHelper helper, int x, int miners) {
        MineWorksGameTests.Rig r = SupplyChainGameTests.liftRig(helper, x, miners);
        helper.assertTrue(MineLift.stops(r.v()).size() == 3, "the cage, three stops: " + MineLift.stops(r.v()));
        helper.assertTrue(MineLift.doorwayForTests(r.v()) != null, "a doorway at the foot");
        return r;
    }

    // ============================================================ lw01: kept so

    /**
     * A works fifty-one deep with its cage, three minutes of rides round the stops and the works' rounds every ten seconds;
     * a block of stone, cobblestone, planks and a water source put in the doorway once on the way. The pool one water source
     * shut in on every side and under it, no water anywhere else in the shaft; the doorway open three high on its floor,
     * onto the stair coming down; the deepest stop's other window cells and the coal stop's barred, each opened as a gate
     * for the riders and shut again behind them, the doorway never barred.
     */
    @GameTest(template = "empty", timeoutTicks = 6000, batch = "lw01_kept")
    public static void lw01_kept(GameTestHelper helper) {
        MineWorksGameTests.Rig r = rig(helper, 8502000, 2);
        ServerLevel level = r.level();
        UUID v = r.v();
        Foot f = foot(r);
        Kit.Expect ok = new Kit.Expect();
        List<String> first = faults(r, f, true);
        Kit.log("[VT] lw01 the foot as hung: pool " + f.pool().toShortString() + ", door " + f.door().toShortString() + " (stop " + f.deep().index()
            + " at Y" + f.bottom() + "), faults " + first);
        section(r, f, "lw01-hung");
        ok.that(first.isEmpty(), "the foot as hung: " + first);
        VillageFolkEntity m = r.miners().get(0);
        r.miners().get(1).setNoAi(true);                                  // (only the rider at the stops)
        m.setNoAi(true);                                                  // the cage's walk in and out only
        Rounds rounds = new Rounds(m, List.of(f.coal(), f.deep(), f.top()));
        MineWorksGameTests.fast(level);
        final int[] tick = { 0 }, doorShut = { 0 }, openDeep = { 0 }, openCoal = { 0 }, doorBarred = { 0 };
        final int[] blockedAt = { -1 }, clearedAt = { -1 };
        List<BlockPos> deepBars = barCells(r, f.deep(), f.door()), coalBars = barCells(r, f.coal(), null);
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            if (tick[0] % 200 == 0) MineWorks.tick(level, Villages.get(v));
            if (tick[0] == 900) {
                // Things put in the doorway: rock, rubble, timber, and a spring.
                level.setBlock(f.door(), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(f.door().above(), Blocks.COBBLESTONE.defaultBlockState(), 3);
                level.setBlock(f.door().above(2), Blocks.OAK_PLANKS.defaultBlockState(), 3);
                blockedAt[0] = tick[0];
                Kit.log("[VT] lw01 @" + tick[0] + " the doorway stopped with stone, cobblestone and planks");
            }
            if (tick[0] == 1300) {
                level.setBlock(f.door(), Blocks.WATER.defaultBlockState(), 3);
                blockedAt[0] = tick[0];
                Kit.log("[VT] lw01 @" + tick[0] + " a spring in the doorway");
            }
            if (blockedAt[0] > 0 && doorOpen(level, f) && level.getFluidState(f.door()).isEmpty()) {
                Kit.log("[VT] lw01 @" + tick[0] + " the doorway open again, " + (tick[0] - blockedAt[0]) + " ticks after it was stopped; stray water " + strayWater(r, f).size());
                clearedAt[0] = Math.max(clearedAt[0], tick[0] - blockedAt[0]);
                blockedAt[0] = -1;
            }
            if (blockedAt[0] < 0 && !doorOpen(level, f)) doorShut[0]++;
            for (int h = 0; h < 2; h++) if (level.getBlockState(f.door().above(h)).is(Blocks.IRON_BARS)) doorBarred[0]++;
            if (all(level, deepBars, BlockState::isAir)) openDeep[0]++;
            if (all(level, coalBars, BlockState::isAir)) openCoal[0]++;
            if (tick[0] < 3400) { rounds.tick(level, v); return false; }
            // The rides over: the cage left to stand, the windows shut.
            if (MineLift.state(m.getUUID()) == MineLift.State.ARRIVED) MineLift.done(m.getUUID());
            if (tick[0] < 3700) return false;
            MineWorks.tick(level, Villages.get(v));
            List<String> bad = faults(r, f, true);
            long[] c = MineLift.countsForTests(v);
            Kit.log("[VT] lw01 after " + tick[0] + " ticks: " + rounds.done + " legs ridden (" + c[0] + " runs), the deepest window open " + openDeep[0]
                + " ticks, the coal window " + openCoal[0] + "; the doorway shut " + doorShut[0] + " ticks (outside the stopping), barred " + doorBarred[0]
                + "; cleared " + clearedAt[0] + " ticks after; faults " + bad);
            section(r, f, "lw01-after");
            ok.that(rounds.done >= 6, "rides round the stops: " + rounds.done);
            ok.that(openDeep[0] > 0 && openCoal[0] > 0, "the deepest and the coal stops' windows opened for the riders: " + openDeep[0] + ", " + openCoal[0]);
            ok.that(doorShut[0] == 0 && doorBarred[0] == 0, "the doorway never shut nor barred: " + doorShut[0] + ", " + doorBarred[0]);
            ok.that(clearedAt[0] > 0 && clearedAt[0] <= 400, "what was put in the doorway cleared at the next round: " + clearedAt[0]);
            ok.that(bad.isEmpty(), "the foot after three minutes: " + bad);
            helper.assertTrue(ok.clean(), ok.summary());
            MineWorksGameTests.done(helper);
            return true;
        });
    }

    // ============================================================ lw02: an older mine put right

    /**
     * An older mine: its pool at the deepest stop's own floor behind the bars, a brick floor under it, the doorway's cell
     * stoned up (rubble, rock over it), and the pool's spill when the window opened for riders (the window as the old gate
     * left it: barred again only where it stood empty). The server started on the new build: within the works' rounds the
     * pool is a block lower, shut in, the doorway open, no water anywhere else.
     */
    @GameTest(template = "empty", timeoutTicks = 2400, batch = "lw02_retrofit")
    public static void lw02_retrofit(GameTestHelper helper) {
        MineWorksGameTests.Rig r = rig(helper, 8506000, 1);
        ServerLevel level = r.level();
        UUID v = r.v();
        Foot f = foot(r);
        r.miners().get(0).setNoAi(true);
        Kit.Expect ok = new Kit.Expect();
        List<BlockPos> window = barCells(r, f.deep(), null);
        final int[] tick = { 0 }, spilt = { 0 }, leftOpen = { 0 }, hung = { -1 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            if (tick[0] == 1) {
                // The town's round taken now (the next not for half a minute), then the old layout: its pool at the stop's floor
                // behind the window's bars, a brick floor under it, the doorway's cell part of the window.
                com.jrpetty.mcassistant.entity.TownDigs.tick(level, Villages.get(v));
                level.setBlock(f.pool().below(), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(f.pool(), Blocks.STONE_BRICKS.defaultBlockState(), 2);
                level.setBlock(f.well(), Blocks.WATER.defaultBlockState(), 3);
                for (int h = 0; h < 2; h++) level.setBlock(f.door().above(h), Blocks.IRON_BARS.defaultBlockState(), 3);
                level.setBlock(f.door().above(2), Blocks.STONE.defaultBlockState(), 3);
                section(r, f, "lw02-old");
            }
            // Its window opened for riders, as the old gate opened it (every cell of it): the pool runs out.
            if (tick[0] == 3) for (BlockPos c : window) level.setBlock(c, Blocks.AIR.defaultBlockState(), 3);
            if (tick[0] == 43) {
                spilt[0] = strayWater(r, f).size() - 1;                  // (the pool itself at the floor counted: the stray list skips the new pool's cell)
                for (BlockPos c : window) {
                    if (level.getBlockState(c).isAir()) {
                        level.setBlock(c, Blocks.IRON_BARS.defaultBlockState(), 3);
                        level.setBlock(c, net.minecraft.world.level.block.Block.updateFromNeighbourShapes(level.getBlockState(c), level, c), 3);
                    } else if (!level.getFluidState(c).isEmpty()) leftOpen[0]++;
                }
                Kit.log("[VT] lw02 the old pool, its window opened for riders: " + spilt[0] + " blocks of water out of the well in three seconds; the window shut as the old gate shut it, "
                    + leftOpen[0] + " of its cells left standing open with the water in them");
                section(r, f, "lw02-spilt");
                // And the doorway's cell stoned up (rubble where the water stood, rock over it).
                level.setBlock(f.door(), Blocks.COBBLESTONE.defaultBlockState(), 3);
                level.setBlock(f.door().above(), Blocks.COBBLESTONE.defaultBlockState(), 3);
                level.setBlock(f.door().above(2), Blocks.STONE.defaultBlockState(), 3);
                MineLift.forgetForTests(v);                             // the server stopped, and started on the new build
            }
            if (hung[0] < 0 && tick[0] > 43 && MineLift.has(v)) {
                hung[0] = tick[0];
                Kit.log("[VT] lw02 the lift looked at on its own " + (tick[0] - 43) + " ticks after the start, the doorway's blocks cleared " + MineLift.openedForTests());
            }
            if (tick[0] < 300) return false;
            if (tick[0] == 300) MineWorks.tick(level, Villages.get(v));   // and the works' round
            if (tick[0] < 450) return false;
            List<String> bad = faults(r, f, true);
            Kit.log("[VT] lw02 after the rounds: doorway blocks cleared " + MineLift.openedForTests() + ", faults " + bad);
            section(r, f, "lw02-after");
            ok.that(spilt[0] > 0, "(the old pool ran out when its window opened: " + spilt[0] + ")");
            ok.that(MineLift.has(v), "the cage again");
            ok.that(level.getBlockState(f.well()).isAir(), "no water at the stop's floor: " + level.getBlockState(f.well()));
            ok.that(bad.isEmpty(), "the old foot put right: " + bad);
            helper.assertTrue(ok.clean(), ok.summary());
            MineWorksGameTests.done(helper);
            return true;
        });
    }

    // ============================================================ lw03: a player down the well and out again

    /**
     * A stand-in player moved each tick by the game's own player movement (ServerPlayer.doTick: its travel in water and on
     * land, its step-up, its jump, the climb out of water at a block's edge), steered as a player steers, holding forward,
     * and jump when in the water or against a block. From the landing at the deepest stop it walks round to the doorway and
     * into the well, drops into the pool, and climbs out again through the doorway back to the landing: unhurt, never put
     * out by the cage's watch (watched every tick), and the geometry that lets it so (the pool's top a block under the
     * doorway's floor, the doorway three high, the stair outside it a step up).
     */
    @GameTest(template = "empty", timeoutTicks = 2400, batch = "lw03_climb_out")
    public static void lw03_climb_out(GameTestHelper helper) {
        MineWorksGameTests.Rig r = rig(helper, 8510000, 1);
        ServerLevel level = r.level();
        UUID v = r.v();
        Foot f = foot(r);
        r.miners().get(0).setNoAi(true);                                  // (nobody calls the cage down onto the player)
        Kit.Expect ok = new Kit.Expect();
        // The geometry.
        BlockPos out = f.outside();                                         // (the floor of the step outside: its feet a block up)
        BlockState stair = level.getBlockState(out);
        double poolTop = f.pool().getY() + level.getFluidState(f.pool()).getHeight(level, f.pool());
        double doorFloor = f.door().getY();
        double stairTop = out.getY() + level.getBlockState(out).getCollisionShape(level, out).max(Direction.Axis.Y);
        boolean headroom = true;
        for (int h = 1; h <= 4; h++) headroom &= level.getBlockState(out.above(h)).isAir();
        Kit.log("[VT] lw03 the geometry: the pool's floor Y" + f.pool().getY() + ", its top " + String.format("%.3f", poolTop) + "; the doorway's floor " + doorFloor
            + " (a rise of " + (doorFloor - f.pool().getY()) + " from the pool's floor), open " + doorOpen(level, f) + " three high; outside it " + stair + ", its top "
            + String.format("%.2f", stairTop) + ", four clear over it " + headroom);
        ok.that(doorFloor - f.pool().getY() == 1.0 && doorFloor - poolTop < 0.2, "the doorway's floor a block over the pool's floor, level with the pool's top (the climb out of a pond)");
        ok.that(doorOpen(level, f) && (stair.getBlock() instanceof StairBlock || stair.isFaceSturdy(level, out, Direction.UP)) && stairTop <= doorFloor + 1.0 && headroom,
            "the doorway three high onto the stair outside it, at most a block up, clear over it");
        ServerPlayer p = climber(helper);
        BlockPos c1 = MineWorks.stepForTests(level, v, f.deep().index() - 1).get(0);
        Vec3 board = Vec3.atBottomCenterOf(f.deep().board()), step1 = Vec3.atBottomCenterOf(c1), step2 = Vec3.atBottomCenterOf(out.above()),
            door = Vec3.atBottomCenterOf(f.door()), well = Vec3.atBottomCenterOf(f.well());
        // Its legs: dropped into the well over the pool and left a while (it sinks to the floor); out to the landing; back
        // in from the landing, over the doorway's edge into the pool; and out to the landing again.
        List<List<Vec3>> legs = List.of(List.of(), List.of(door, step2, step1, board), List.of(step1, step2, door, well), List.of(door, step2, step1, board));
        String[] names = { "dropped into the pool", "out to the landing", "in from the landing to the pool", "out to the landing again" };
        p.teleportTo(level, well.x, f.bottom() + 1.5, well.z, 0, 0);
        float hp = p.getHealth();
        final int[] tick = { 0 }, wp = { 0 }, moved = { 0 }, wetTicks = { 0 }, leg = { 0 }, legAt = { 0 };
        final double[] last = { Double.NaN, 0, 0 }, lowest = { 999 };
        final int stepped = MineLift.STEPPED;
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            // Anything that moved it since its own last step (the watch putting it out of the well): counted.
            Vec3 was = new Vec3(last[0], last[1], last[2]);
            if (!Double.isNaN(last[0]) && p.position().distanceTo(was) > 0.01) {
                moved[0]++;
                Kit.log("[VT] lw03 @" + tick[0] + " moved from " + was + " to " + p.position());
            }
            MineLift.watchForTests(level, v);
            if (!Double.isNaN(last[0]) && p.position().distanceTo(was) > 0.01 && moved[0] == 0) {
                moved[0]++;
                Kit.log("[VT] lw03 @" + tick[0] + " put out by the watch to " + p.position());
            }
            if (p.isInWater()) wetTicks[0]++;
            lowest[0] = Math.min(lowest[0], p.getY());
            List<Vec3> way = legs.get(leg[0]);
            boolean legDone;
            Vec3 to;
            if (way.isEmpty()) {                                             // the drop: no hands on the keys for two seconds
                to = p.position();
                legDone = tick[0] - legAt[0] >= 40;
            } else {
                to = way.get(Math.min(wp[0], way.size() - 1));
                double dx = to.x - p.getX(), dz = to.z - p.getZ(), d = Math.sqrt(dx * dx + dz * dz);
                boolean intoPool = to == well;
                if (intoPool) legDone = p.isInWater() && p.getY() < f.bottom() - 0.3;
                else if (d < 0.3 && Math.abs(p.getY() - to.y) < 0.8) { wp[0]++; legDone = wp[0] >= way.size(); }
                else legDone = false;
            }
            if (legDone) {
                Kit.log("[VT] lw03 @" + tick[0] + " " + names[leg[0]] + " in " + (tick[0] - legAt[0]) + " ticks: at " + String.format("%.2f %.2f %.2f", p.getX(), p.getY(), p.getZ())
                    + ", in the water " + p.isInWater() + ", lowest Y" + String.format("%.2f", lowest[0]));
                leg[0]++;
                wp[0] = 0;
                legAt[0] = tick[0];
                if (leg[0] >= legs.size()) {
                    Kit.log("[VT] lw03 done after " + tick[0] + " ticks at " + p.position() + "; in the water " + wetTicks[0] + " ticks, lowest Y" + String.format("%.2f", lowest[0])
                        + ", moved by anything but its own steps " + moved[0] + ", stepped by the cage " + (MineLift.STEPPED - stepped) + "; health " + hp + " -> " + p.getHealth());
                    ok.that(wetTicks[0] > 40 && lowest[0] < f.bottom() - 0.8, "it went down into the pool: " + wetTicks[0] + " ticks wet, lowest " + lowest[0]);
                    ok.that(moved[0] == 0 && MineLift.STEPPED == stepped, "never put out by the watch nor moved by anything but its own steps: " + moved[0]);
                    ok.that(p.getBlockX() == f.deep().board().getX() && p.getBlockZ() == f.deep().board().getZ() && Math.abs(p.getY() - f.bottom()) <= 0.6 && !p.isInWater(),
                        "out on the landing, dry: " + p.position());
                    ok.that(p.getHealth() >= hp, "unhurt: " + p.getHealth());
                    MineStairWalkGameTests.letGo(level, p);
                    helper.assertTrue(ok.clean(), ok.summary());
                    MineWorksGameTests.done(helper);
                    return true;
                }
                way = legs.get(leg[0]);
                to = way.get(0);
            }
            if (tick[0] - legAt[0] > 400) {
                Kit.log("[VT] lw03 STUCK " + names[leg[0]] + " at point " + wp[0] + " at " + p.position() + " (to " + to + "), wet " + p.isInWater() + ", ground " + p.onGround()
                    + ", against " + p.horizontalCollision);
                MineStairWalkGameTests.letGo(level, p);
                helper.fail("the player could not get " + names[leg[0]] + ": at " + p.position() + " short of " + to);
                return true;
            }
            if (tick[0] % 20 == 0) Kit.log("[VT] lw03 @" + tick[0] + " " + names[leg[0]] + " point " + wp[0] + " at " + String.format("%.2f %.2f %.2f", p.getX(), p.getY(), p.getZ())
                + " wet " + p.isInWater() + " ground " + p.onGround() + " against " + p.horizontalCollision);
            if (way.isEmpty()) { p.zza = 0; p.xxa = 0; p.setJumping(false); p.doTick(); }
            else drive(p, to, p.isInWater() || p.horizontalCollision);
            last[0] = p.getX(); last[1] = p.getY(); last[2] = p.getZ();
            return false;
        });
    }

    /** A stand-in player whose movement is the game's own (as the mock player, but in survival). */
    static ServerPlayer climber(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "lw-climber"), false);
        ServerPlayer p = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return false;
            }
        };
        Connection c = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(c);
        level.getServer().getPlayerList().placeNewPlayer(c, p, cookie);
        p.setGameMode(GameType.SURVIVAL);
        p.getAbilities().flying = false;
        p.getAbilities().mayfly = false;
        return p;
    }

    /** One tick of a player's own movement (ServerPlayer.doTick), steered toward a spot holding forward, jumping or not. */
    static void drive(ServerPlayer p, Vec3 to, boolean jump) {
        double dx = to.x - p.getX(), dz = to.z - p.getZ(), d = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
        p.yBodyRot = yaw;
        p.xxa = 0;
        p.zza = d > 0.05 ? (float) Math.min(1.0, d * 3.0) : 0.0F;
        p.setJumping(jump);
        p.doTick();
    }

    // ============================================================ lw04: riders kept dry

    /**
     * Two miners on their shifts at the deepest level (taking the cage by their own work's goal) and a third ridden round the
     * stops again and again, for five minutes: not one tick of any of them in the water, nor in the doorway or the well
     * outside the cage; every rider at the deepest stop got in and out on the cage's floor at the stop's height.
     */
    @GameTest(template = "empty", timeoutTicks = 9000, batch = "lw04_riders_dry")
    public static void lw04_riders_dry(GameTestHelper helper) {
        MineWorksGameTests.Rig r = rig(helper, 8514000, 3);
        ServerLevel level = r.level();
        UUID v = r.v();
        Foot f = foot(r);
        Kit.Expect ok = new Kit.Expect();
        com.jrpetty.mcassistant.entity.MineDemand.stockForTests(v, MineWorksGameTests.plenty());
        for (int i = 0; i < 2; i++) {
            VillageFolkEntity m = r.miners().get(i);
            MineWorksGameTests.kit(m, Items.IRON_PICKAXE);
            MineWorksGameTests.zone(r, m, f.bottom());
            m.teleportTo(f.top().board().getX() + 0.5, f.top().y(), f.top().board().getZ() + 0.5);
        }
        VillageFolkEntity rider = r.miners().get(2);
        rider.clearQueue();
        Rounds rounds = new Rounds(rider, List.of(f.deep(), f.top()));
        com.jrpetty.mcassistant.entity.goal.WorksGoal.resetCountsForTests();
        MineWorksGameTests.fast(level);
        final int[] tick = { 0 }, wet = { 0 }, inFoot = { 0 }, atBottom = { 0 }, lowRide = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            for (int i = 0; i < 2; i++) MineWorksGameTests.keepAtIt(r, r.miners().get(i));
            rider.clearQueue();                                          // (its rides only)
            rounds.tick(level, v);
            for (VillageFolkEntity m : r.miners()) {
                if (m.isInWater()) {
                    wet[0]++;
                    if (wet[0] < 6) Kit.log("[VT] lw04 @" + tick[0] + " " + m.getName().getString() + " in the water at " + m.position() + " (" + MineLift.state(m.getUUID()) + ")");
                }
                BlockPos b = m.blockPosition();
                boolean foot = b.getX() == f.door().getX() && b.getZ() == f.door().getZ() && b.getY() >= f.bottom() - 1 && b.getY() <= f.bottom() + 2
                    || b.getX() == f.well().getX() && b.getZ() == f.well().getZ() && b.getY() <= f.bottom() + 1 && MineLift.state(m.getUUID()) != MineLift.State.RIDING;
                if (foot) inFoot[0]++;
                // A rider in the well at the deepest stop: on the cage's floor at the stop's height, never under it.
                if (MineLift.state(m.getUUID()) == MineLift.State.RIDING && b.getX() == f.well().getX() && b.getZ() == f.well().getZ() && m.getY() < f.bottom() + 1.5) {
                    atBottom[0]++;
                    if (m.getY() < f.bottom() - 0.01) lowRide[0]++;
                }
            }
            if (tick[0] % 1200 == 0) Kit.log("[VT] lw04 @" + tick[0] + ": rides " + rounds.done + ", shift rides " + com.jrpetty.mcassistant.entity.goal.WorksGoal.RIDES
                + ", wet " + wet[0] + ", in the doorway or the well " + inFoot[0] + ", at the bottom in the cage " + atBottom[0] + " (" + lowRide[0] + " under its floor)");
            if (tick[0] < 6000) return false;
            long[] c = MineLift.countsForTests(v);
            Kit.log("[VT] lw04 after " + tick[0] + " ticks: " + rounds.done + " legs by the rider, " + com.jrpetty.mcassistant.entity.goal.WorksGoal.RIDES + " by the miners on shift, "
                + c[0] + " runs, " + c[1] + " carried; miner-ticks in the water " + wet[0] + ", in the doorway or the well outside the cage " + inFoot[0]
                + ", in the cage at the bottom " + atBottom[0] + " (under its floor " + lowRide[0] + "); stray water " + strayWater(r, f).size());
            ok.that(rounds.done >= 10, "the rider down to the deepest stop and back again and again: " + rounds.done);
            ok.that(atBottom[0] > 0, "riders in the cage at the deepest stop: " + atBottom[0]);
            ok.that(wet[0] == 0, "not a tick in the water: " + wet[0]);
            ok.that(inFoot[0] == 0 && lowRide[0] == 0, "never in the doorway, nor in the well outside the cage, nor under the cage's floor: " + inFoot[0] + ", " + lowRide[0]);
            ok.that(faults(r, f, false).isEmpty(), "the foot as it was: " + faults(r, f, false));
            helper.assertTrue(ok.clean(), ok.summary());
            MineWorksGameTests.done(helper);
            return true;
        });
    }

    // ============================================================ lw05: folk kept out, the cage onto a player

    /**
     * A folk at the deepest stop sent to the doorway, and to the pool, again and again: it never sets foot in either (no way
     * for its walk goes there). Stood in the doorway and walked toward the well, it is held at the edge, out of the pool.
     * A player in the pool: the watch leaves them there; the cage called down to the stop steps them up into the doorway,
     * out of the water, not put out on the landing.
     */
    @GameTest(template = "empty", timeoutTicks = 2400, batch = "lw05_kept_out")
    public static void lw05_kept_out(GameTestHelper helper) {
        MineWorksGameTests.Rig r = rig(helper, 8518000, 2);
        ServerLevel level = r.level();
        UUID v = r.v();
        Foot f = foot(r);
        Kit.Expect ok = new Kit.Expect();
        VillageFolkEntity walker = r.miners().get(0), caller = r.miners().get(1);
        caller.setNoAi(true);
        walker.clearQueue();
        walker.teleportTo(f.deep().board().getX() + 0.5, f.deep().y(), f.deep().board().getZ() + 0.5);
        final int[] tick = { 0 }, inFoot = { 0 }, wet = { 0 }, held = { 0 };
        final double[] edge = { 0 };
        final ServerPlayer[] p = { null };
        final int[] stepped = { 0 }, phase = { 0 };
        MineSiteGameTests.each(helper, () -> {
            tick[0]++;
            // Part one: sent to the doorway and the pool, four hundred ticks.
            if (tick[0] <= 400) {
                walker.clearQueue();
                if (tick[0] % 40 == 1) {
                    BlockPos to = (tick[0] / 40) % 2 == 0 ? f.door() : f.pool();
                    walker.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, 1.0);
                    if (tick[0] < 100) {
                        net.minecraft.world.level.pathfinder.Path path = walker.getNavigation().getPath();
                        Kit.log("[VT] lw05 sent to " + to.toShortString() + ": its way " + (path == null ? "none" : path.getNodeCount() + " nodes, ends at " + path.getEndNode() + ", reaches " + path.canReach()));
                    }
                }
                BlockPos b = walker.blockPosition();
                if (b.getX() == f.door().getX() && b.getZ() == f.door().getZ() && b.getY() >= f.bottom() - 1 && b.getY() <= f.bottom() + 2
                    || b.getX() == f.well().getX() && b.getZ() == f.well().getZ() && b.getY() <= f.bottom() + 1) inFoot[0]++;
                if (walker.isInWater()) wet[0]++;
                return false;
            }
            // Part two: stood in the doorway, walked at the well (the folk's own move, its edge guard).
            if (tick[0] == 401) {
                walker.getNavigation().stop();
                walker.setNoAi(true);
                walker.teleportTo(f.door().getX() + 0.5, f.door().getY(), f.door().getZ() + 0.5);
                walker.setDeltaMovement(Vec3.ZERO);
                walker.move(MoverType.SELF, new Vec3(0, -0.1, 0));
                int[] o = f.out();
                for (int i = 0; i < 30; i++) {
                    walker.move(MoverType.SELF, new Vec3(-o[0] * 0.1, -0.08, -o[1] * 0.1));
                    if (walker.blockPosition().getX() == f.door().getX() && walker.blockPosition().getZ() == f.door().getZ() && !walker.isInWater()
                        && walker.getY() >= f.bottom() - 0.01) held[0]++;
                }
                edge[0] = (walker.getX() - (f.door().getX() + 0.5)) * -o[0] + (walker.getZ() - (f.door().getZ() + 0.5)) * -o[1];
                Kit.log("[VT] lw05 walked at the well from the doorway: held " + held[0] + "/30 steps, at " + walker.position() + " (" + String.format("%.2f", edge[0])
                    + " out from the doorway's middle), on the ground " + walker.onGround() + ", in the water " + walker.isInWater());
                walker.teleportTo(f.deep().board().getX() + 0.5, f.deep().y(), f.deep().board().getZ() + 0.5);
                // Part three: a player in the pool.
                p[0] = helper.makeMockServerPlayerInLevel();
                p[0].teleportTo(level, f.pool().getX() + 0.5, f.pool().getY() + 0.05, f.pool().getZ() + 0.5, 0, 0);
                stepped[0] = MineLift.STEPPED;
                return false;
            }
            if (tick[0] == 420) {
                MineLift.watchForTests(level, v);
                boolean left = p[0].blockPosition().equals(f.pool());
                Kit.log("[VT] lw05 the watch with a player in the pool: left there " + left + " at " + p[0].position());
                ok.that(left, "the watch leaves a player in the pool: " + p[0].position());
                caller.teleportTo(f.deep().board().getX() + 0.5, f.deep().y(), f.deep().board().getZ() + 0.5);
                ok.that(MineLift.ask(caller, v, f.deep().index(), f.top().index()), "the cage called down to the deepest stop");
                phase[0] = 1;
                return false;
            }
            if (phase[0] == 1) {
                if (MineLift.cageYForTests(v) > f.bottom() + 0.01 && tick[0] < 1400) return false;
                phase[0] = 2;
                Kit.log("[VT] lw05 the cage down at the deepest stop @" + tick[0] + ": the player at " + p[0].position() + ", stepped " + (MineLift.STEPPED - stepped[0])
                    + ", in the water " + p[0].isInWater());
                ok.that(MineLift.STEPPED - stepped[0] == 1, "the player stepped once out of the cage's way: " + (MineLift.STEPPED - stepped[0]));
                ok.that(p[0].blockPosition().equals(f.door()), "up into the doorway, not put out on the landing: " + p[0].blockPosition().toShortString());
                ok.that(level.getFluidState(p[0].blockPosition()).isEmpty(), "out of the water");
                MineStairWalkGameTests.letGo(level, p[0]);
                Kit.log("[VT] lw05 sent at the doorway and the pool: in either " + inFoot[0] + " ticks, in the water " + wet[0]);
                ok.that(inFoot[0] == 0 && wet[0] == 0, "the folk never set foot in the doorway nor the pool: " + inFoot[0] + ", " + wet[0]);
                ok.that(held[0] == 30 && edge[0] < 0.75, "held at the doorway's edge, out of the pool: " + held[0] + ", " + edge[0]);
                if (MineLift.state(caller.getUUID()) != MineLift.State.NONE) MineLift.cancel(caller.getUUID());
                helper.assertTrue(ok.clean(), ok.summary());
                MineWorksGameTests.done(helper);
                return true;
            }
            return false;
        });
    }
}
