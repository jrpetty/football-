package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.BuildOrders;
import com.jrpetty.mcassistant.entity.CastleWall;
import com.jrpetty.mcassistant.entity.Monuments;
import com.jrpetty.mcassistant.entity.NightWatch;
import com.jrpetty.mcassistant.entity.Park;
import com.jrpetty.mcassistant.entity.Police;
import com.jrpetty.mcassistant.entity.Quarry;
import com.jrpetty.mcassistant.entity.StoneYard;
import com.jrpetty.mcassistant.entity.TownDanger;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.DangerLevel;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import com.jrpetty.mcassistant.village.WallPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * [featbuild] The walls by the danger and the night watch (TownDanger, CastleWall, NightWatch), the statue gardens
 * (Monuments, Park), and the quarry and the stone yard (Quarry, StoneYard).
 * <ul>
 * <li><b>fb01</b>: a town's danger stages its wall: no wall in an open town; raids seen raise it to a log palisade (in the
 *     Stone Age), then to stone; the towers' stage adds towers along the sides and a portcullis; the gates shut at night
 *     and open in the morning.</li>
 * <li><b>fb02</b>: the ring rebuilt further out when the town grows past it: the east line out a ring, the old line taken
 *     down and its lots given back; the gates on the radial roads.</li>
 * <li><b>fb03</b>: a guard of the walls on the night watch walks its round with a lantern in hand, stop after stop, never
 *     put anywhere; the dusk handover in the night's log; the rota on the board.</li>
 * <li><b>fb04</b>: a statue garden and the statue of the town's most notable folk on its plinth with its plaque; folk near it
 *     the happier, its kin prouder; the ponds full, a rim stone knocked out empties its pond, and no water ever outside
 *     the basins.</li>
 * <li><b>fb05</b>: the quarry sited past the growth line, cut in terraces (no step over a block), fenced and lit, never
 *     next to water; its stone into the yard, the yard's piles showing it, the mason dressing it, the stores kept.</li>
 * <li><b>fb06</b>: a commission draws its stone from the yard; the yard empty, it waits ("waiting on stone from the yard"),
 *     and goes on when the stone comes.</li>
 * <li><b>fb07</b>: a quarryman walks out to the face, cuts with its pick and carts its load to the yard by itself.</li>
 * </ul>
 * Each on its own ground in the band x 8,200,000 to 8,299,999, z 70000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class FeatBuildGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;

    // ------------------------------------------------------------------ helpers (as DefenceGameTests has them)

    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        int y = Kit.surface(level, cx, cz).getY();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -4; dy < 0; dy++) {
                    level.setBlock(p.set(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 24; dy++) {
                    if (!level.getBlockState(p.set(x, y + dy, z)).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    private static void ground(ServerLevel level, int cx, int cz, int r) {
        Kit.hold(level, cx, cz, r);
        Kit.prepare(level, cx, cz, r);
    }

    private static int[] box(TownPlan.Lot l) {
        boolean turned = l.back() == TownPlan.EAST || l.back() == TownPlan.WEST;
        return new int[]{ l.x(), l.z(), turned ? l.halfDeep() : l.halfAcross(), turned ? l.halfAcross() : l.halfDeep() };
    }

    private static TownPlan.Lot lot(java.util.function.Predicate<int[]> where) {
        for (TownPlan.Lot l : TownPlan.lots()) if (l.kind() == TownPlan.Kind.LOT && where.test(box(l))) return l;
        throw new IllegalStateException("no such lot");
    }

    private static void built(UUID village, BlockPos heart, List<TownPlan.Lot> lots) {
        for (TownPlan.Lot l : lots) Ledger.built(village, "house", heart.offset(l.x(), 0, l.z()), Villages.direction(l.back()).getOpposite());
    }

    private static List<TownPlan.Lot> firstBlock() {
        List<TownPlan.Lot> out = new ArrayList<>();
        out.add(lot(b -> b[1] - b[3] <= -38 + 10 && b[1] - b[3] >= -38 && Math.abs(b[0]) < 30));
        out.add(lot(b -> b[0] + b[2] >= 28 && b[0] + b[2] <= 38 && Math.abs(b[1]) < 30));
        out.add(lot(b -> b[1] + b[3] >= 28 && b[1] + b[3] <= 38 && Math.abs(b[0]) < 30));
        out.add(lot(b -> b[0] - b[2] <= -28 && b[0] - b[2] >= -38 && Math.abs(b[1]) < 30));
        out.add(lot(b -> Math.max(Math.abs(b[0]) + b[2], Math.abs(b[1]) + b[3]) <= 27));
        return out;
    }

    private static TownPlan.Lot ringOneEast() {
        return lot(b -> b[0] - b[2] >= 53 && b[0] + b[2] <= 63 && Math.abs(b[1]) < 20);
    }

    private static int count(ServerLevel level, BlockPos heart, WallPlan.Sides s, java.util.function.Predicate<BlockState> what) {
        int n = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int[] col : WallPlan.columns(s, WallPlan.towers(s))) {
            for (int dy = -4; dy <= 16; dy++) {
                if (what.test(level.getBlockState(p.set(heart.getX() + col[0], heart.getY() + dy, heart.getZ() + col[1])))) n++;
            }
        }
        return n;
    }

    private static Container chest(ServerLevel level, BlockPos heart, int dx, int dz, ItemStack... goods) {
        BlockPos at = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        return box;
    }

    private record Town(UUID id, Villages.Village v, BlockPos heart, VillageFolkEntity first) {}

    private static Town town(GameTestHelper helper, ServerLevel level, int x, int r, Villages.Age age) {
        Kit.reset(level);
        ground(level, x, Z, r + 10);
        BlockPos heart = flat(level, x, Z, r);
        level.setDayTime((level.getDayTime() / 24000L + 2) * 24000L + 6000L);
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(a != null && a.ownerId() != null, "a town");
        UUID id = a.ownerId();
        Villages.ageForTests(id, age);
        return new Town(id, Villages.get(id), heart, a);
    }

    // ------------------------------------------------------------------ fb01: the wall by the danger

    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "fb01_wall_stages")
    public static void fb01_wall_stages(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 8_200_000, 62, Villages.Age.STONE);
        UUID id = t.id();
        Kit.Expect ok = new Kit.Expect();
        CastleWall.standDownForTests(false);
        CastleWall.readyForTests(true);
        TownDanger.liveForTests();
        built(id, t.heart(), firstBlock());
        long day = level.getDayTime() / 24000L;
        // An open town: nothing seen, no wall wanted.
        DangerLevel.Reading r0 = TownDanger.reading(level, id);
        int s0 = TownDanger.stage(level, id);
        String lines0 = String.join(" | ", CastleWall.lines(level, t.v()));
        Kit.log("[VT] fb01 open: danger " + r0.score() + " stage " + s0 + " | " + lines0);
        ok.that(s0 == DangerLevel.OPEN, "an open town at first: stage " + s0 + ", danger " + r0.score());
        ok.that(lines0.contains("no wall wanted yet"), "the wall's lines say no wall is wanted yet: " + lines0);
        // A raid seen: the palisade (in the Stone Age, whatever its age's stone).
        TownDanger.alarm(id, "raiders at the north gate", day);
        int s1 = TownDanger.stage(level, id);
        ok.that(s1 == DangerLevel.PALISADE, "one raid: the palisade's stage: " + s1 + " (" + TownDanger.reading(level, id).score() + ")");
        CastleWall.buildNow(level, t.v(), true);
        WallPlan.Sides s = CastleWall.sides(id);
        int logs = count(level, t.heart(), s, st -> st.is(BlockTags.LOGS));
        int cobble1 = count(level, t.heart(), s, st -> st.is(Blocks.COBBLESTONE));
        int[] f1 = CastleWall.figures(id);
        Kit.log("[VT] fb01 palisade: " + logs + " logs, " + cobble1 + " cobble, towers " + f1[3] + ", portcullis " + f1[4]);
        ok.that(logs > 2000 && cobble1 < 50, "a log palisade in the Stone Age: " + logs + " logs, " + cobble1 + " cobblestone");
        ok.that(f1[3] == 12, "the corners and the gatehouses only: " + f1[3] + " towers");
        ok.that(f1[4] == 0, "no portcullis yet");
        // Three raids in the month: a stone wall.
        TownDanger.alarm(id, "raiders at the east gate", day);
        TownDanger.alarm(id, "a raid", day);
        int s2 = TownDanger.stage(level, id);
        ok.that(s2 == DangerLevel.STONE, "three raids: the stone stage: " + s2 + " (" + TownDanger.reading(level, id).score() + ")");
        CastleWall.buildNow(level, t.v(), true);
        int logs2 = count(level, t.heart(), s, st -> st.is(BlockTags.LOGS));
        int cobble2 = count(level, t.heart(), s, st -> st.is(Blocks.COBBLESTONE) || st.is(Blocks.MOSSY_COBBLESTONE));
        Kit.log("[VT] fb01 stone: " + logs2 + " logs, " + cobble2 + " cobble");
        ok.that(cobble2 > 2000 && logs2 < logs / 10, "refaced in stone: " + cobble2 + " cobblestone, " + logs2 + " logs left");
        // The towers' stage (forced: the score to reach it takes a war or a bad month of monsters).
        TownDanger.stageForTests(id, DangerLevel.TOWERS);
        CastleWall.buildNow(level, t.v(), true);
        int[] f3 = CastleWall.figures(id);
        Kit.log("[VT] fb01 towers: " + f3[3] + " towers, portcullis " + f3[4] + " | " + String.join(" | ", CastleWall.lines(level, t.v())));
        ok.that(f3[3] > 12, "towers along the sides: " + f3[3]);
        ok.that(f3[4] == 1, "a portcullis over the gates");
        int bars = 0;
        for (CastleWall.Gate g : CastleWall.gates(level, id)) {
            for (int dy = 0; dy <= 4; dy++) for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
                if (level.getBlockState(g.gateway().offset(dx, dy, dz)).is(Blocks.IRON_BARS)) bars++;
            }
        }
        ok.that(bars >= 4, "the portcullis hung in the gates: " + bars + " bars");
        // The gates: shut at night, open in the morning.
        List<BlockPos> doors = CastleWall.doorsForTests(id);
        ok.that(doors.size() >= 8, "the gates' doors hung: " + doors.size());
        level.setDayTime(day * 24000L + 14000L);
        CastleWall.keep(level, t.v());
        int open = 0;
        for (BlockPos d : doors) if (level.getBlockState(d).getBlock() instanceof DoorBlock && level.getBlockState(d).getValue(DoorBlock.OPEN)) open++;
        ok.that(open == 0, "every gate shut at night: " + open + " open");
        level.setDayTime((day + 1) * 24000L + 1500L);
        CastleWall.keep(level, t.v());
        int openDay = 0;
        for (BlockPos d : doors) if (level.getBlockState(d).getBlock() instanceof DoorBlock && level.getBlockState(d).getValue(DoorBlock.OPEN)) openDay++;
        ok.that(openDay == doors.size(), "the gates open in the morning: " + openDay + " of " + doors.size());
        // The chronicle tells each stage.
        boolean told = false;
        for (Villages.News n : Villages.news(id)) if (n.text().contains("council voted for a stone wall")) told = true;
        ok.that(told, "the news has the council voting for a stone wall");
        CastleWall.standDownForTests(true);
        if (!ok.clean()) helper.fail(ok.summary());
        helper.succeed();
    }

    // ------------------------------------------------------------------ fb02: moved out as the town grows

    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "fb02_ring_moves_out")
    public static void fb02_ring_moves_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 8_204_000, 86, Villages.Age.STONE);
        UUID id = t.id();
        Kit.Expect ok = new Kit.Expect();
        CastleWall.standDownForTests(false);
        CastleWall.readyForTests(true);
        TownDanger.stageForTests(id, DangerLevel.STONE);
        built(id, t.heart(), firstBlock());
        CastleWall.buildNow(level, t.v(), true);
        WallPlan.Sides first = CastleWall.sides(id);
        ok.that(first.equals(WallPlan.first()), "the first ring round the first block: " + first);
        for (CastleWall.Gate g : CastleWall.gates(level, id)) {
            int dx = g.gateway().getX() - t.heart().getX(), dz = g.gateway().getZ() - t.heart().getZ();
            ok.that(dx == 0 || dz == 0, "the " + g.out().getName() + " gate on its radial road: " + dx + "," + dz);
        }
        BlockPos oldEast = t.heart().offset(43, 2, 20);
        ok.that(level.getBlockState(oldEast).is(Blocks.COBBLESTONE), "the east wall up at 43");
        TownPlan.Lot inward = lot(b -> b[0] - b[2] >= 42 && b[0] - b[2] <= 52 && Math.abs(b[1]) < 30);
        ok.that(CastleWall.keptOff(id, inward), "the lot on the wall's ring kept off while it stands");
        // The town takes a lot past the wall: the ring is rebuilt further out, the old line comes down.
        built(id, t.heart(), List.of(ringOneEast()));
        CastleWall.buildNow(level, t.v(), true);
        WallPlan.Sides now = CastleWall.sides(id);
        Kit.log("[VT] fb02 " + first + " -> " + now + " | " + String.join(" | ", CastleWall.lines(level, t.v())));
        ok.that(now.e() > first.e() && now.n() == first.n(), "the east line out a ring, the rest where they were: " + now);
        ok.that(level.getBlockState(t.heart().offset(now.e() + 1, 2, 20)).is(Blocks.COBBLESTONE), "the new east wall up at " + (now.e() + 1));
        int left = 0;
        for (int dz = -30; dz <= 30; dz++) for (int dx = 42; dx <= 48; dx++) for (int dy = 0; dy <= 10; dy++) {
            BlockState b = level.getBlockState(t.heart().offset(dx, dy, dz));
            if (b.is(Blocks.COBBLESTONE) || b.is(Blocks.LADDER) || b.getBlock() instanceof DoorBlock) left++;
        }
        ok.that(left == 0, "the old east line taken down: " + left + " left");
        ok.that(!CastleWall.keptOff(id, inward), "its ground given back to the lots");
        ok.that(CastleWall.oldLine(id) == null, "no old line left standing");
        CastleWall.standDownForTests(true);
        if (!ok.clean()) helper.fail(ok.summary());
        helper.succeed();
    }

    // ------------------------------------------------------------------ fb03: the lantern patrol

    @GameTest(template = EMPTY, timeoutTicks = 4200, batch = "fb03_lantern_patrol")
    public static void fb03_lantern_patrol(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 8_208_000, 62, Villages.Age.STONE);
        UUID id = t.id();
        CastleWall.standDownForTests(false);
        CastleWall.readyForTests(true);
        TownDanger.stageForTests(id, DangerLevel.STONE);
        Police.standDownForTests(false);
        built(id, t.heart(), firstBlock());
        CastleWall.buildNow(level, t.v(), true);
        chest(level, t.heart(), 4, -4, new ItemStack(Items.LANTERN, 8), new ItemStack(Items.TORCH, 16), new ItemStack(Items.BREAD, 32));
        Villages.forgetStores(id);
        // One guard: the walls on an even day (a watch of one: the walls one day, the streets the next).
        VillageFolkEntity g = t.first();
        g.keepTradeForTests();
        if (!g.takeUpTrade(StationTask.GUARD)) g.setJob(StationTask.GUARD);
        g.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        long day = level.getDayTime() / 24000L;
        level.setDayTime(day * 24000L + 12600L);
        if (!"WALLS".equals(Police.dutyForTests(level, g))) {
            day++;
            level.setDayTime(day * 24000L + 12600L);
        }
        Kit.log("[VT] fb03 the guard's duty tonight: " + Police.dutyForTests(level, g));
        final long d = day;
        List<BlockPos> visited = new ArrayList<>();
        double[] worst = { 0 };
        double[] last = { g.getX(), g.getY(), g.getZ() };
        boolean[] lit = { false };
        int[] ticks = { 0 };
        int[] rang = { 0 };
        List<net.minecraft.world.entity.Mob> raiders = new ArrayList<>();
        helper.onEachTick(() -> {
            ticks[0]++;
            double dx = g.getX() - last[0], dy = g.getY() - last[1], dz = g.getZ() - last[2];
            double step = Math.sqrt(dx * dx + dz * dz + dy * dy * 0.25);
            if (ticks[0] > 20 && step > worst[0]) {
                worst[0] = step;
                if (step > 2.0) Kit.log("[VT] fb03 a jump of " + String.format("%.1f", step) + " at tick " + ticks[0] + " to " + g.blockPosition().toShortString());
            }
            last[0] = g.getX(); last[1] = g.getY(); last[2] = g.getZ();
            if (com.jrpetty.mcassistant.entity.NightLight.isLight(g.getItemBySlot(EquipmentSlot.OFFHAND))) lit[0] = true;
            BlockPos stop = NightWatch.stopForTests(g);
            if (stop != null && g.distanceToSqr(stop.getX() + 0.5, stop.getY(), stop.getZ() + 0.5) < 2.6 * 2.6 && !visited.contains(stop)) {
                visited.add(stop);
                Kit.log("[VT] fb03 at stop " + stop.toShortString() + " after " + ticks[0] + " ticks" + (NightWatch.streetForTests(g) ? " (street)" : " (wall walk)"));
            }
            if (visited.size() >= 2 && lit[0] && ticks[0] > 60 && rang[0] == 0) {
                // Trouble seen from the wall: raiders out past it, in the watchman's sight. The bell.
                rang[0] = ticks[0];
                BlockPos at = g.blockPosition();
                int side = NightWatch.streetForTests(g) ? 0 : 0;
                for (int i = 0; i < 3; i++) {
                    net.minecraft.world.entity.monster.Pillager p = net.minecraft.world.entity.EntityType.PILLAGER.create(level);
                    if (p == null) continue;
                    BlockPos out = Kit.surface(level, at.getX() - 6 + 6 * i, at.getZ() - 14);
                    p.moveTo(out.getX() + 0.5, out.getY(), out.getZ() + 0.5, 0F, 0F);
                    p.setNoAi(true);
                    p.setPersistenceRequired();
                    level.addFreshEntity(p);
                    raiders.add(p);
                }
                return;
            }
            if (rang[0] > 0 && ticks[0] - rang[0] < 400 && !com.jrpetty.mcassistant.entity.Raids.underAlarm(id)) {
                NightWatch.tickForTests(level, t.v());
                return;
            }
            if (rang[0] > 0 && visited.size() >= 2 && lit[0]) {
                Kit.Expect ok = new Kit.Expect();
                ok.that(com.jrpetty.mcassistant.entity.Raids.underAlarm(id), "raiders seen from the wall: the bell rung");
                ok.that(String.join(" ", NightWatch.logOf(id)).contains("rang the bell"), "the bell in the night's log: " + NightWatch.logOf(id));
                for (net.minecraft.world.entity.Mob p : raiders) p.discard();
                com.jrpetty.mcassistant.entity.Raids.allClear(level, t.v());
                List<String> log = NightWatch.logOf(id);
                CompoundTag rep = NightWatch.report(level, t.v());
                Kit.log("[VT] fb03 log " + log + " | rota " + rep.getList("rota", 10));
                ok.that(NightWatch.routeForTests(g).size() >= 2, "a round of stops: " + NightWatch.routeForTests(g).size());
                ok.that(worst[0] < 2.0, "walked every step, never put anywhere: the longest step " + String.format("%.2f", worst[0]));
                ok.that(String.join(" ", log).contains("Dusk"), "the dusk handover in the night's log: " + log);
                ok.that(!rep.getList("rota", 10).isEmpty(), "the rota for the board");
                ok.that(d == level.getDayTime() / 24000L, "all in the one night");
                CastleWall.standDownForTests(true);
                Police.standDownForTests(true);
                if (!ok.clean()) helper.fail(ok.summary());
                helper.succeed();
            }
        });
        helper.runAtTickTime(4100, () -> helper.fail("the night watch never walked two stops with a light: stops " + visited
            + ", lit " + lit[0] + ", route " + NightWatch.routeForTests(g).size() + ", street " + NightWatch.streetForTests(g)
            + ", at " + g.blockPosition().toShortString() + ", log " + NightWatch.logOf(id)));
    }

    // ------------------------------------------------------------------ fb04: the statue garden

    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "fb04_statue_garden")
    public static void fb04_statue_garden(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 8_212_000, 50, Villages.Age.STONE);
        UUID id = t.id();
        Kit.Expect ok = new Kit.Expect();
        Monuments.standDownForTests(false);
        // Three more folk; the first a master of its trade with a long record.
        List<VillageFolkEntity> folk = new ArrayList<>();
        folk.add(t.first());
        for (int i = 0; i < 3; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, t.heart().getX() + 3 + 2 * i, t.heart().getZ() + 3), 0.0F);
            helper.assertTrue(f != null && id.equals(f.ownerId()), "folk " + i);
            folk.add(f);
        }
        VillageFolkEntity hero = folk.get(0);
        hero.keepTradeForTests();
        hero.setJob(StationTask.MINE);
        hero.note(com.jrpetty.mcassistant.entity.AssistantEntity.Deed.BLOCKS_MINED, 3000);
        ok.that(Monuments.score(hero, folk) >= 40, "a notable folk: score " + Monuments.score(hero, folk));
        BlockPos anchor = Kit.surface(level, t.heart().getX() + 20, t.heart().getZ() - 20);
        Ledger.Building g = Monuments.putUp(level, id, anchor, Direction.NORTH);
        helper.assertTrue(g != null, "a statue garden");
        boolean raised = Monuments.raiseForTests(level, t.v(), g, hero);
        ok.that(raised, "the statue raised");
        List<ArmorStand> st = level.getEntitiesOfClass(ArmorStand.class, new AABB(g.anchor()).inflate(3, 4, 3), a -> a.getTags().contains("mca_folkstatue"));
        ok.that(st.size() == 1 && st.get(0).getY() >= g.anchor().getY() + 2, "the statue on its plinth: " + st.size());
        boolean plaque = false;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 2; dy++) {
            if (level.getBlockEntity(g.anchor().offset(dx, dy, dz)) instanceof SignBlockEntity sign
                && sign.getFrontText().getMessage(0, false).getString().contains(hero.displayNameCap())) plaque = true;
        }
        ok.that(plaque, "a plaque with its name");
        // The mood: a folk by the statue the happier; one far off not; the hero itself prouder.
        Monuments.forgetMoodForTests();
        VillageFolkEntity near = folk.get(1), far = folk.get(2);
        near.moveTo(g.anchor().getX() + 3.5, g.anchor().getY(), g.anchor().getZ() + 3.5);
        far.moveTo(t.heart().getX() - 40.5, t.heart().getY(), t.heart().getZ() + 40.5);
        long day = level.getDayTime() / 24000L;
        List<Object[]> whyNear = new ArrayList<>(), whyFar = new ArrayList<>(), whyHero = new ArrayList<>();
        int mNear = Monuments.mood(near, day, 50, whyNear), mFar = Monuments.mood(far, day, 50, whyFar), mHero = Monuments.mood(hero, day, 50, whyHero);
        Kit.log("[VT] fb04 mood near " + mNear + " far " + mFar + " hero " + mHero);
        ok.that(mNear > 50 && !whyNear.isEmpty() && "statue".equals(whyNear.get(0)[0]), "a folk by the statue is the happier: " + mNear);
        ok.that(mFar == 50, "a folk far off is not: " + mFar);
        ok.that(mHero > mNear && "kinstatue".equals(whyHero.get(0)[0]), "the honoured folk prouder: " + mHero);
        // The ponds: full, and held.
        List<BlockPos> pond = Monuments.pondForTests(g);
        int water = 0;
        for (BlockPos p : pond) if (!level.getFluidState(p).isEmpty()) water++;
        ok.that(water == pond.size(), "the ponds full: " + water + " of " + pond.size());
        // A rim stone knocked out: that pond emptied before a drop runs.
        BlockPos rim = pond.get(0).relative(Direction.WEST);
        level.setBlock(rim, Blocks.AIR.defaultBlockState(), 3);
        Monuments.pondsForTests(level, t.v(), g);
        int left = 0;
        for (BlockPos p : pond.subList(0, 2)) if (!level.getFluidState(p).isEmpty()) left++;
        ok.that(left == 0, "the broken pond emptied at once: " + left + " left");
        // And no water anywhere in or round the garden but the basins.
        helper.runAfterDelay(100, () -> {
            int stray = 0;
            for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) for (int dy = -2; dy <= 3; dy++) {
                BlockPos p = g.anchor().offset(dx, dy, dz);
                if (!level.getFluidState(p).isEmpty() && !pond.contains(p)) stray++;
            }
            ok.that(stray == 0, "no water outside the basins after five seconds: " + stray);
            ok.that(Park.parks(id).contains(g), "kept and visited as a park is");
            CompoundTag rep = Monuments.report(level, t.v());
            ok.that(rep.getList("greens", 10).size() == 1 && rep.getList("greens", 10).getCompound(0).getString("statue").equals(hero.displayNameCap()),
                "on the board: " + rep);
            Monuments.standDownForTests(true);
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ fb05: the quarry and the yard

    /** A rock outcrop on the site (the test world's ground is three blocks over its floor): stone from the ground up, so
     *  high, its top the quarry's rim; returns the rim's middle. */
    private static BlockPos rock(ServerLevel level, BlockPos ground, int half, int high) {
        for (int x = ground.getX() - half; x <= ground.getX() + half; x++) {
            for (int z = ground.getZ() - half; z <= ground.getZ() + half; z++) {
                for (int y = ground.getY() - 1; y <= ground.getY() + high; y++) level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 2 | 16);
                for (int y = ground.getY() + high + 1; y <= ground.getY() + high + 6; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        }
        return ground.above(high);
    }

    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "fb05_quarry_yard")
    public static void fb05_quarry_yard(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 8_220_000, 112, Villages.Age.STONE);
        UUID id = t.id();
        Kit.Expect ok = new Kit.Expect();
        Quarry.standDownForTests(false);
        Quarry.readyForTests(true);
        int reach = Quarry.growthReach(id);
        Quarry.Site chosen = Quarry.chooseForTests(level, t.v());
        helper.assertTrue(chosen != null, "a quarry site");
        int dx = chosen.centre().getX() - t.heart().getX(), dz = chosen.centre().getZ() - t.heart().getZ();
        int near = Math.max(Math.abs(dx), Math.abs(dz)) - Quarry.WIDEST / 2;
        Kit.log("[VT] fb05 site " + chosen + " growth line " + reach + " (" + dx + "," + dz + ")");
        ok.that(Math.max(Math.abs(dx), Math.abs(dz)) - chosen.size() / 2 > reach, "out past the growth line: " + dx + "," + dz + " vs " + reach);
        ok.that(Math.abs(dx) > 12 && Math.abs(dz) > 12, "off the radial roads");
        // Rock under it, and a pool of water beside the pit's west edge.
        BlockPos c = rock(level, chosen.centre(), 14, 8);
        BlockPos pool = c.offset(0, 0, -5);
        level.setBlock(pool, Blocks.WATER.defaultBlockState(), 3);
        Quarry.Site site = new Quarry.Site(c, Quarry.START, true);
        Quarry.openForTests(level, t.v(), site);
        StoneYard.Site yard = StoneYard.site(id);
        ok.that(yard != null, "a stone yard with it");
        ok.that(StoneYard.layOutForTests(level, t.v()), "the yard laid out");
        Quarry.fenceForTests(level, t.v());
        int posts = 0, lights = 0, gates = 0;
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) for (int y = -1; y <= 3; y++) {
            BlockState b = level.getBlockState(c.offset(x, y, z));
            if (b.is(BlockTags.FENCES)) posts++;
            if (b.is(BlockTags.FENCE_GATES)) gates++;
            if (b.is(Blocks.LANTERN) || b.is(Blocks.TORCH)) lights++;
        }
        Kit.log("[VT] fb05 fence " + posts + " posts, " + gates + " gates, " + lights + " lights");
        ok.that(posts + gates >= 36 && gates == 1, "fenced all round with a gate: " + posts + " posts, " + gates + " gate");
        ok.that(lights >= 4, "lit: " + lights + " lanterns");
        int cut = Quarry.cutForTests(level, t.v(), 600);
        long stone = StoneYard.count(id, Items.COBBLESTONE);
        Kit.log("[VT] fb05 cut " + cut + ", the yard holds " + StoneYard.stock(id));
        ok.that(cut > 150, "the pit cut: " + cut + " blocks");
        Quarry.Site wider = Quarry.site(id);
        ok.that(wider != null && wider.size() > Quarry.START, "opened wider as it was worked: " + (wider == null ? "closed" : wider.size() + " across"));
        ok.that(stone > 150, "its stone in the yard: " + stone + " cobblestone");
        // Terraces: no step of more than a block anywhere in the pit or onto its rim.
        int worst = 0;
        int h = Quarry.START / 2 + 1;
        for (int x = -h; x <= h; x++) for (int z = -h; z <= h; z++) {
            int a = top(level, c.getX() + x, c.getZ() + z, c.getY());
            if (x < h) worst = Math.max(worst, Math.abs(a - top(level, c.getX() + x + 1, c.getZ() + z, c.getY())));
            if (z < h) worst = Math.max(worst, Math.abs(a - top(level, c.getX() + x, c.getZ() + z + 1, c.getY())));
        }
        ok.that(worst <= 1, "terraced: no step over a block (" + worst + ")");
        int deepest = c.getY() - top(level, c.getX(), c.getZ(), c.getY());
        ok.that(deepest >= 3, "dug down: " + deepest + " deep in the middle");
        // The water: still in its pool, none in the pit.
        ok.that(!level.getFluidState(pool).isEmpty(), "the pool still where it was");
        int wet = 0;
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = -6; y <= 1; y++) {
            if (!level.getFluidState(c.offset(x, y, z)).isEmpty()) wet++;
        }
        ok.that(wet == 0, "no water in the pit: " + wet);
        ok.that(level.getBlockState(c.offset(0, 0, -4)).is(Blocks.STONE), "the stone by the water left standing");
        // The piles show the stock; the mason dresses some; the stores are kept.
        StoneYard.pilesForTests(level, id);
        int piles = 0;
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) for (int y = 0; y <= 3; y++) {
            if (level.getBlockState(yard.centre().offset(x, y, z)).is(Blocks.COBBLESTONE)) piles++;
        }
        ok.that(piles >= Math.min(15, (int) (stone / 16) - 1), "the yard's piles show its stock: " + piles + " blocks for " + stone);
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (level.getBlockEntity(p) instanceof Container box) {
                for (int i = 0; i < box.getContainerSize(); i++) if (box.getItem(i).is(Items.COBBLESTONE)) box.setItem(i, ItemStack.EMPTY);
                box.setChanged();
            }
        }
        chest(level, t.heart(), 4, -4, new ItemStack(Items.COAL, 8), new ItemStack(Items.CLAY_BALL, 16));
        Villages.forgetStores(id);
        int dressed = StoneYard.dressForTests(level, t.v());
        ok.that(dressed >= 8 && StoneYard.count(id, Items.STONE_BRICKS) >= 8, "the mason dressed stone bricks: " + dressed);
        ItemStack due = StoneYard.deliveryForTests(level, t.v());
        ok.that(due != null && due.is(Items.COBBLESTONE), "the stores short of cobblestone: a load due from the yard: " + due);
        CompoundTag rep = StoneYard.report(level, id);
        ok.that(rep.getInt("rough") > 0 && !rep.getList("flow", 10).isEmpty(), "the stock and the flow for the board: " + rep);
        Quarry.standDownForTests(true);
        if (!ok.clean()) helper.fail(ok.summary());
        helper.succeed();
    }

    private static int top(ServerLevel level, int x, int z, int rim) {
        for (int y = rim + 3; y >= rim - 10; y--) {
            BlockState st = level.getBlockState(new BlockPos(x, y, z));
            if (st.isAir() || st.is(BlockTags.FENCES) || st.is(BlockTags.FENCE_GATES) || st.is(Blocks.LANTERN) || st.is(Blocks.TORCH)
                || !st.getFluidState().isEmpty() || st.canBeReplaced()) continue;
            return y;
        }
        return rim - 10;
    }

    // ------------------------------------------------------------------ fb06: a commission draws on the yard

    @GameTest(template = EMPTY, timeoutTicks = 1500, batch = "fb06_build_from_yard")
    public static void fb06_build_from_yard(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 8_230_000;
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        com.jrpetty.mcassistant.entity.Bank.resetForTests();
        com.jrpetty.mcassistant.entity.HousingMarket.resetForTests();
        BuildOrders.resetForTests();
        com.jrpetty.mcassistant.entity.PropertyMarket.resetForTests();
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(true);
        level.setDayTime(24000L * 8 + 7000);
        ground(level, x, Z, 64);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        UUID id = first.ownerId();
        Villages.Village v = Villages.get(id);
        for (int i = 1; i < 6; i++) VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x + 2 * i, Z + 3), 0.0F);
        Villages.ageForTests(id, Villages.Age.STONE);
        for (com.jrpetty.mcassistant.entity.AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f) { f.spend(f.purse()); f.rentFree(false); f.setJob(StationTask.FARM); }
        }
        // The stores with everything a cottage takes but its stone; the yard with a little stone.
        Item[] goods = { Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS,
            Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_LOG, Items.OAK_LOG, Items.GLASS, Items.GLASS_PANE, Items.TORCH, Items.LANTERN,
            Items.WHITE_WOOL, Items.RED_CARPET, Items.POPPY, Items.BARREL, Items.FURNACE, Items.SMOKER, Items.CRAFTING_TABLE, Items.CHEST, Items.RED_BED };
        List<ItemStack> stacks = new ArrayList<>();
        for (Item it : goods) stacks.add(new ItemStack(it, new ItemStack(it).getMaxStackSize()));
        chest(level, heart, 4, -4, stacks.toArray(new ItemStack[0]));
        Villages.forgetStores(id);
        StoneYard.siteForTests(id, heart.offset(-40, 0, 40), Direction.NORTH);
        StoneYard.stockForTests(id, Items.COBBLESTONE, 12);
        StoneYard.stockForTests(id, Items.STONE_BRICKS, 0);
        helper.runAtTickTime(5, () -> {
            Kit.Expect ok = new Kit.Expect();
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            for (int c = 600; c > 0; c -= 64) p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), Math.min(64, c)));
            BlockPos at = BuildOrders.suggestPlot(level, id, "house", heart.offset(30, 0, 30), Direction.NORTH, 30);
            helper.assertTrue(at != null, "a plot");
            BuildOrders.Quote q = BuildOrders.quote(level, id, "house", "oak", at, Direction.NORTH, p.getUUID());
            BuildOrders.Result r = BuildOrders.place(level, p, q);
            ok.that(r.ok(), "placed: " + r.message());
            String waiting = "";
            for (int i = 0; i < 60; i++) {
                BuildOrders.hoursForTests(level, v, 2);
                waiting = BuildOrders.orderForTests(r.orderId()).getString("waiting");
                if (waiting.contains("stone from the yard")) break;
            }
            long drawn = 0;
            for (long[] f : StoneYard.flows(id)) drawn += f[4];
            int laidShort = BuildOrders.laidForTests(r.orderId());
            Kit.log("[VT] fb06 waiting: " + waiting + "; drawn from the yard " + drawn + "; laid " + laidShort + " of " + BuildOrders.cellsForTests(r.orderId())
                + "; yard " + StoneYard.stock(id) + " | " + StoneYard.shortages(id));
            ok.that(drawn >= 12, "the commission drew the yard's stone: " + drawn);
            ok.that(waiting.startsWith("waiting on stone from the yard"), "short of stone, it waits on the yard: " + waiting);
            ok.that(laidShort < BuildOrders.cellsForTests(r.orderId()), "not finished while it waits");
            ok.that(!StoneYard.shortages(id).isEmpty(), "the shortage counted for the board");
            // The quarry's stone comes in: the work goes on to the end.
            StoneYard.stockForTests(id, Items.COBBLESTONE, 2000);
            StoneYard.stockForTests(id, Items.STONE_BRICKS, 2000);
            for (int i = 0; i < 240; i++) {
                CompoundTag o = BuildOrders.orderForTests(r.orderId());
                if (o == null || !o.getString("status").equals("open")) break;
                BuildOrders.hoursForTests(level, v, 2);
            }
            CompoundTag o = BuildOrders.orderForTests(r.orderId());
            ok.that("done".equals(o.getString("status")), "finished once the stone came: " + o.getString("status") + " " + o.getString("waiting"));
            ok.that(StoneYard.count(id, Items.COBBLESTONE) < 2000, "drawn from the yard: " + StoneYard.stock(id));
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ fb07: a quarryman at work

    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "fb07_quarryman")
    public static void fb07_quarryman(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 8_240_000, 70, Villages.Age.STONE);
        UUID id = t.id();
        Quarry.standDownForTests(false);
        Quarry.readyForTests(true);
        // A site near enough for the test's ground (the growth line is the real game's: this one is set by hand).
        BlockPos c = rock(level, Kit.surface(level, t.heart().getX() + 40, t.heart().getZ() + 40), 14, 0);   // flush with the ground: walked onto
        Quarry.openForTests(level, t.v(), new Quarry.Site(c, Quarry.START, true));
        StoneYard.Site yard = StoneYard.site(id);
        helper.assertTrue(yard != null, "a yard");
        chest(level, t.heart(), 4, -4, new ItemStack(Items.OAK_FENCE, 64), new ItemStack(Items.OAK_FENCE_GATE, 2), new ItemStack(Items.LANTERN, 16),
            new ItemStack(Items.STONE_PICKAXE, 2), new ItemStack(Items.BREAD, 32));
        Villages.forgetStores(id);
        VillageFolkEntity m = t.first();
        m.keepTradeForTests();
        m.setJob(StationTask.MINE);
        m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
        Ledger.note(id, "quarry.men", m.getStringUUID());
        level.setDayTime((level.getDayTime() / 24000L) * 24000L + 1200L);
        long cut0 = 0;
        double[] last = { m.getX(), m.getY(), m.getZ() };
        double[] worst = { 0 };
        int[] tick = { 0 };
        Set<String> did = new HashSet<>();
        helper.onEachTick(() -> {
            tick[0]++;
            double dx = m.getX() - last[0], dy = m.getY() - last[1], dz = m.getZ() - last[2];
            double step = Math.sqrt(dx * dx + dz * dz + dy * dy * 0.25);
            if (tick[0] > 20 && step > worst[0]) {
                worst[0] = step;
                if (step > 2.0) Kit.log("[VT] fb07 a jump of " + String.format("%.1f", step) + " at tick " + tick[0] + " from " + last[0] + "," + last[1] + "," + last[2]
                    + " to " + m.blockPosition().toShortString() + " doing " + Quarry.doingForTests(m));
            }
            last[0] = m.getX(); last[1] = m.getY(); last[2] = m.getZ();
            String doing = Quarry.doingForTests(m);
            if (doing != null && did.add(doing)) Kit.log("[VT] fb07 tick " + tick[0] + ": " + doing + " at " + m.blockPosition().toShortString());
            long stock = StoneYard.count(id, Items.COBBLESTONE);
            if (stock >= 8 && tick[0] > 40) {
                Kit.Expect ok = new Kit.Expect();
                ok.that(worst[0] < 2.0, "walked there and back, never put anywhere: longest step " + String.format("%.2f", worst[0])
                    + " (jumps by cause " + com.jrpetty.mcassistant.entity.Blink.byCause().keySet() + ")");
                ok.that(Ledger.note(id, "yard.laid") != null, "the yard laid out by the quarry's hand");
                ok.that(Long.parseLong(Ledger.note(id, "quarry.cut")) >= 8, "it cut the stone itself: " + Ledger.note(id, "quarry.cut"));
                Kit.log("[VT] fb07 the yard holds " + StoneYard.stock(id) + " after " + tick[0] + " ticks");
                Quarry.standDownForTests(true);
                if (!ok.clean()) helper.fail(ok.summary());
                helper.succeed();
            }
        });
        com.jrpetty.mcassistant.entity.Blink.setOn(true);
        helper.runAtTickTime(5900, () -> helper.fail("no load reached the yard: " + Quarry.doingForTests(m) + " at " + m.blockPosition().toShortString()
            + ", cut " + Ledger.note(id, "quarry.cut") + ", yard " + StoneYard.stock(id) + ", did " + did + ", jumps " + com.jrpetty.mcassistant.entity.Blink.byCause().keySet()));
    }
}
