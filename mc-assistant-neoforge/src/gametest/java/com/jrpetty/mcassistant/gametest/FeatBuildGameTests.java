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

import javax.annotation.Nullable;
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
 * <li><b>fb08</b>: a quarry worked for weeks in fast-forward: wider stage by stage to its widest, then a shaft down to the
 *     bedrock with a ladder, then lengthened only away from the town; the water and lava it met plugged, every drop fenced,
 *     a way to walk and climb from the bottom to the top all the way; and a quarryman climbs out from the bottom with its
 *     load to the yard and back down to cut there, never put anywhere.</li>
 * <li><b>fb09</b>: a wall built from scratch has flights of steps up on every side; a guard of the night watch starts on
 *     the ground, walks to a flight, climbs it, walks the wall walk from one end of its round to the other, and comes
 *     down by the steps when its watch ends: no step over a block a tick, no jump at all.</li>
 * <li><b>fb10</b>: the town's greens by its size: none but statue gardens at thirty-nine folk, a park a block across at
 *     forty, another at seventy, and at ninety the central park, four blocks of the plan set aside where no lot is built
 *     on or claimed (its lots kept off for homes from then on); laid out with its lake, plaza, pitch and benches, and not a
 *     drop of water outside its ponds.</li>
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
        double far = Math.sqrt((double) dx * dx + (double) dz * dz);
        Kit.log("[VT] fb05 site " + chosen + " growth line " + reach + " (" + dx + "," + dz + ") " + String.format("%.0f", far) + " out");
        ok.that(far >= Quarry.NEAR - 1 && far <= Quarry.FAR + 1, "sited 150 to 250 blocks out: " + String.format("%.0f", far));
        ok.that(Math.max(Math.abs(dx), Math.abs(dz)) - chosen.size() / 2 > reach, "out past the growth line: " + dx + "," + dz + " vs " + reach);
        ok.that(Math.abs(dx) > 12 && Math.abs(dz) > 12, "off the radial roads");
        ok.that(!Quarry.wetForTests(level, chosen.centre(), chosen.away()), "no water within " + Quarry.DRY + " blocks of its widest footprint");
        // A lake: a site beside it is turned down; the same ground dry is taken.
        BlockPos lakeSite = chosen.centre().offset(0, 0, 0);
        int[] fin = Quarry.boxForTests(new Quarry.Site(lakeSite, Quarry.WIDEST, true, 5, 0, 0, chosen.away()));
        BlockPos lake = new BlockPos(fin[0] - 6, lakeSite.getY(), lakeSite.getZ());
        for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) level.setBlock(lake.offset(a, 0, b), Blocks.WATER.defaultBlockState(), 2 | 16);
        ok.that(Quarry.wetForTests(level, lakeSite, chosen.away()), "a site with a lake six blocks off its widest edge turned down");
        Quarry.Site again = Quarry.chooseForTests(level, t.v());
        ok.that(again != null && !Quarry.wetForTests(level, again.centre(), again.away()) && !again.centre().equals(lakeSite),
            "a dry site taken instead: " + (again == null ? "none" : again.centre().toShortString()));
        for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) level.setBlock(lake.offset(a, 0, b), Blocks.GRASS_BLOCK.defaultBlockState(), 2 | 16);
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
        ok.that(posts + gates >= 24 && gates == 1, "fenced all round its ragged rim with a gate: " + posts + " posts, " + gates + " gate");
        ok.that(lights >= 2, "lit: " + lights + " lanterns");
        int cut = Quarry.cutForTests(level, t.v(), 600);
        long stone = StoneYard.count(id, Items.COBBLESTONE);
        Quarry.Site now = Quarry.site(id);
        Kit.log("[VT] fb05 cut " + cut + ", the yard holds " + StoneYard.stock(id) + "; the quarry now " + (now == null ? "closed" : Quarry.stageWords(level, now))
            + "; news " + Villages.news(id));
        ok.that(cut > 120, "the pit cut: " + cut + " blocks");
        ok.that(now != null && now.stage() >= 2, "grown a stage as it was worked: " + now);
        ok.that(stone > 120, "its stone in the yard: " + stone + " cobblestone");
        // No drop of more than a block anywhere in or round the pit that is not fenced.
        List<String> drops = unfenced(level, now, c.getY(), 13);
        ok.that(drops.isEmpty(), "no unfenced drop: " + drops);
        int deepest = c.getY() - top(level, c.getX(), c.getZ(), c.getY());
        ok.that(deepest >= 3, "dug down: " + deepest + " deep in the middle");
        // The water: plugged where the cutting met it, none in the pit nor run anywhere.
        int wet = 0;
        for (int x = -14; x <= 14; x++) for (int z = -14; z <= 14; z++) for (int y = -9; y <= 1; y++) {
            if (!level.getFluidState(c.offset(x, y, z)).isEmpty()) wet++;
        }
        ok.that(wet <= 1, "no water run into the pit: " + wet + " (the pool " + (level.getFluidState(pool).isEmpty() ? "plugged" : "kept") + ")");
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

    /** The ground's top in a column, deep (the quarry's fittings, plants and water not ground). */
    private static int ground(ServerLevel level, int x, int z, int from, int to) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = from; y >= to; y--) {
            BlockState st = level.getBlockState(p.set(x, y, z));
            if (st.isAir() || st.is(BlockTags.FENCES) || st.is(BlockTags.FENCE_GATES) || st.is(Blocks.LANTERN) || st.is(Blocks.TORCH)
                || st.is(Blocks.WALL_TORCH) || st.is(Blocks.LADDER) || !st.getFluidState().isEmpty() || st.canBeReplaced()) continue;
            return y;
        }
        return to;
    }

    /** Every drop of more than a block between neighbouring columns in and round the pit, with no fence on its high side
     *  (two high over a deep one) and no ladder down it. Empty when the pit is safe all round. */
    private static List<String> unfenced(ServerLevel level, @Nullable Quarry.Site s, int rim, int within) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        int[] b = Quarry.boxForTests(s);
        int lo = Quarry.bottomForTests(level, s) - 2;
        int cx = s.centre().getX(), cz = s.centre().getZ();
        for (int x = b[0] - 1; x <= b[2] + 1; x++) {
            for (int z = b[1] - 1; z <= b[3] + 1; z++) {
                if (Math.abs(x - cx) > within || Math.abs(z - cz) > within) continue;
                int ta = ground(level, x, z, rim + 4, lo);
                for (int[] d : new int[][]{ { 1, 0 }, { 0, 1 } }) {
                    int nx = x + d[0], nz = z + d[1];
                    if (Math.abs(nx - cx) > within || Math.abs(nz - cz) > within) continue;
                    int tb = ground(level, nx, nz, rim + 4, lo);
                    if (Math.abs(ta - tb) <= 1) continue;
                    int hx = ta > tb ? x : nx, hz = ta > tb ? z : nz, lx = ta > tb ? nx : x, lz = ta > tb ? nz : z;
                    int th = Math.max(ta, tb), tl = Math.min(ta, tb);
                    if (level.getBlockState(new BlockPos(lx, th, lz)).is(Blocks.LADDER) || level.getBlockState(new BlockPos(lx, th - 1, lz)).is(Blocks.LADDER)) continue;
                    boolean f1 = level.getBlockState(new BlockPos(hx, th + 1, hz)).is(BlockTags.FENCES) || level.getBlockState(new BlockPos(hx, th + 1, hz)).is(BlockTags.FENCE_GATES);
                    boolean f2 = th - tl <= 2 || level.getBlockState(new BlockPos(hx, th + 2, hz)).is(BlockTags.FENCES);
                    if (!(f1 && f2) && out.size() < 12) out.add(hx + "," + th + "," + hz + " over " + lx + "," + tl + "," + lz);
                }
            }
        }
        return out;
    }

    /** Can a body walk (and climb) from one spot to another, by the ground, steps of a block and ladders, in this box? */
    private static boolean walkable(ServerLevel level, BlockPos from, BlockPos to, int[] box, int yLo, int yHi) {
        java.util.ArrayDeque<BlockPos> q = new java.util.ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        q.add(from);
        seen.add(from.asLong());
        BlockPos best = from;
        while (!q.isEmpty()) {
            BlockPos p = q.poll();
            if (p.getY() > best.getY()) best = p;
            if (p.distManhattan(to) <= 1) return true;
            boolean onLadder = level.getBlockState(p).is(Blocks.LADDER);
            List<BlockPos> next = new ArrayList<>();
            for (Direction d : Direction.Plane.HORIZONTAL) for (int dy = -1; dy <= 1; dy++) next.add(p.relative(d).above(dy));
            next.add(p.above());
            next.add(p.below());
            for (BlockPos n : next) {
                if (n.getX() < box[0] || n.getX() > box[2] || n.getZ() < box[1] || n.getZ() > box[3] || n.getY() < yLo || n.getY() > yHi) continue;
                if (seen.contains(n.asLong())) continue;
                boolean vertical = n.getX() == p.getX() && n.getZ() == p.getZ();
                boolean nLadder = level.getBlockState(n).is(Blocks.LADDER);
                if (vertical && !(onLadder || nLadder)) continue;
                if (!stand(level, n)) continue;
                // A step up wants headroom over where it stands; a step down wants room over where it lands.
                if (n.getY() > p.getY() && !vertical && !open(level, p.above(2))) continue;
                if (n.getY() < p.getY() && !vertical && !open(level, n.above(2))) continue;
                seen.add(n.asLong());
                q.add(n);
            }
        }
        Kit.log("[VT] walkable: got no higher than " + best.toShortString() + " (" + seen.size() + " spots)");
        return false;
    }

    private static boolean open(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return st.getCollisionShape(level, p).isEmpty() || st.is(BlockTags.FENCE_GATES) || st.is(Blocks.LADDER);
    }

    /** Could a body stand here: room for it, and ground (or a rung) under its feet? */
    private static boolean stand(ServerLevel level, BlockPos p) {
        if (!open(level, p) || !open(level, p.above())) return false;
        if (level.getBlockState(p).is(Blocks.LADDER)) return true;
        BlockState under = level.getBlockState(p.below());
        return under.isFaceSturdy(level, p.below(), Direction.UP) || under.is(Blocks.LADDER) || under.is(BlockTags.STAIRS);
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

    // ------------------------------------------------------------------ fb08: a quarry grown down to the bedrock

    @GameTest(template = EMPTY, timeoutTicks = 8000, batch = "fb08_deep_quarry")
    public static void fb08_deep_quarry(GameTestHelper helper) {
        com.jrpetty.mcassistant.entity.Quarry.SHAFTS = true;   // the shaft under test, whatever the game ships with

        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 8_250_000, 40, Villages.Age.STONE);
        UUID id = t.id();
        Villages.setFieldsSide(id, TownPlan.WEST);             // the farmland on the far side from the quarry (the test is of the pit)
        Quarry.standDownForTests(false);
        Quarry.readyForTests(true);
        // A hill of stone out at a hundred and sixty blocks (the test world is flat: the real one has the rock), the rim
        // forty-four over the floor; water and lava in it where the cutting will meet them.
        int cx = t.heart().getX() + 113, cz = t.heart().getZ() + 113;
        Kit.hold(level, cx, cz, 56);
        Kit.prepare(level, cx, cz, 56);
        int floor = level.getMinBuildHeight() + 1, rim = floor + 44;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int x = cx - 34; x <= cx + 36; x++) {
            for (int z = cz - 21; z <= cz + 21; z++) {
                for (int y = floor; y <= rim; y++) level.setBlock(mp.set(x, y, z), Blocks.STONE.defaultBlockState(), 2 | 16);
                for (int y = rim + 1; y <= rim + 6; y++) level.setBlock(mp.set(x, y, z), Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        }
        BlockPos c = new BlockPos(cx, rim, cz);
        BlockPos water1 = c.offset(8, -6, 8), water2 = c.offset(3, -24, 0), lava = c.offset(0, -32, 1), water3 = c.offset(20, -4, 3);
        for (BlockPos p : new BlockPos[]{ water1, water2, water3 }) level.setBlock(p, Blocks.WATER.defaultBlockState(), 3);
        level.setBlock(lava, Blocks.LAVA.defaultBlockState(), 3);
        Quarry.openForTests(level, t.v(), new Quarry.Site(c, Quarry.START, true));
        Quarry.Site s0 = Quarry.site(id);
        helper.assertTrue(s0 != null && s0.away() == Direction.EAST, "opened, its far side away from the town: " + s0);
        StoneYard.layOutForTests(level, t.v());
        int[] box0 = Quarry.boxForTests(s0);
        List<String> stages = new ArrayList<>();
        int[] cuts = { 0 };
        int[] phase = { 0 };
        boolean[] widestFirst = { true };
        Kit.Expect ok = new Kit.Expect();
        VillageFolkEntity m = t.first();
        double[] last = new double[3];
        double[] worst = { 0 };
        int[] tick = { 0 };
        long[] yardAt = { 0 };
        int[] yard0 = { 0 };
        BlockPos[] mark = new BlockPos[1];
        Set<String> did = new HashSet<>();
        helper.onEachTick(() -> {
            tick[0]++;
            Quarry.Site s = Quarry.site(id);
            if (phase[0] == 0) {
                // Weeks of work in fast-forward, a few hundred blocks a tick.
                Quarry.Site was = Quarry.site(id);
                boolean lastStage = was != null && was.ext() >= 2 * Quarry.LONGER;
                int got = lastStage ? Quarry.cutStageForTests(level, t.v(), 400) : Quarry.cutForTests(level, t.v(), 400);
                cuts[0] += got;
                Quarry.Site n = Quarry.site(id);
                if (n == null) { helper.fail("closed: " + Villages.news(id)); return; }
                String st = n.stage() + ":" + n.size() + "/" + n.deep() + "/" + n.ext();
                if (stages.isEmpty() || !stages.get(stages.size() - 1).equals(st)) {
                    stages.add(st);
                    if (n.deep() > 0 && n.size() < Quarry.WIDEST) widestFirst[0] = false;
                    Kit.log("[VT] fb08 " + Quarry.stageWords(level, n) + " after " + cuts[0] + " cut");
                }
                if (lastStage && got == 0 || tick[0] > 300) phase[0] = 1;
                return;
            }
            if (phase[0] == 1) {
                phase[0] = 2;
                int[] b = Quarry.boxForTests(s);
                Kit.log("[VT] fb08 stages " + stages + "; box " + java.util.Arrays.toString(box0) + " -> " + java.util.Arrays.toString(b) + "; news " + Villages.news(id));
                ok.that(widestFirst[0] && stages.stream().anyMatch(x -> x.contains(":" + Quarry.WIDEST + "/")), "opened wider stage by stage to its widest before a shaft: " + stages);
                ok.that(Quarry.atBedrockForTests(level, s), "the shaft down to the bedrock: " + Quarry.stageWords(level, s));
                ok.that(s.ext() >= 2 * Quarry.LONGER, "lengthened after the bedrock: " + s);
                int h = s.size() / 2;
                ok.that(b[0] == cx - h && b[1] == cz - h && b[3] == cz + h && b[2] > cx + h, "grown only away from the town (east): " + java.util.Arrays.toString(b));
                ok.that(Quarry.stageWords(level, s).matches("Quarry stage \\d+: \\d+\u00d7\\d+, \\d+ deep"), "the board's words: " + Quarry.stageWords(level, s));
                int bottom = Quarry.bottomForTests(level, s);
                int wet = 0;
                List<String> wetAt = new ArrayList<>();
                for (int x = b[0] - 2; x <= b[2] + 2; x++) for (int z = b[1] - 2; z <= b[3] + 2; z++) for (int y = bottom - 1; y <= rim + 1; y++) {
                    if (level.getFluidState(mp.set(x, y, z)).isEmpty()) continue;
                    // Open to the pit or the stair: a neighbour that is air (shut in the rock all round, it runs nowhere).
                    boolean open = false;
                    for (Direction d : Direction.values()) open |= level.getBlockState(mp.immutable().relative(d)).isAir();
                    if (open) { wet++; if (wetAt.size() < 6) wetAt.add(mp.toShortString()); }
                }
                ok.that(wet == 0, "no water or lava open anywhere in or round the pit: " + wet + " " + wetAt);
                ok.that(!Quarry.wetForTests(level, s.centre(), s.away()), "no water within " + Quarry.DRY + " blocks of its footprint at or over its top");
                List<String> drops = unfenced(level, s, rim, 1000);
                ok.that(drops.isEmpty(), "every drop fenced: " + drops);
                // The bottom of the shaft: a way up from it to the gate, all the way, by the stair and the terraces.
                BlockPos foot = Quarry.stairFootForTests(level, s);
                if (foot == null) { helper.fail("no stair down the shaft: " + ok.summary()); return; }
                BlockPos out = Quarry.outsideForTests(level, s);
                int[] area = { b[0] - 4, b[1] - 4, b[2] + 4, b[3] + 4 };
                boolean way = walkable(level, foot, out, area, bottom - 2, rim + 4);
                Kit.log("[VT] fb08 bottom " + foot.toShortString() + " (" + (rim - foot.getY() + 1) + " deep) to " + out.toShortString() + ": " + (way ? "a way" : "NO WAY"));
                ok.that(way, "a way to walk and climb out from the bottom: " + foot.toShortString() + " to " + out.toShortString());
                if (!way) {
                    StringBuilder col = new StringBuilder();
                    for (int y = foot.getY() - 1; y <= rim + 1; y++) {
                        col.append(y).append(':').append(level.getBlockState(new BlockPos(cx - 2, y, cz)).getBlock().getName().getString())
                            .append('/').append(level.getBlockState(new BlockPos(cx - 3, y, cz)).getBlock().getName().getString())
                            .append('/').append(level.getBlockState(new BlockPos(cx - 4, y, cz)).getBlock().getName().getString()).append(' ');
                    }
                    Kit.log("[VT] fb08 the ladder's column (ladder/gap/terrace): " + col);
                }
                List<int[]> steps = Quarry.stairForTests(level, s);
                int torches = 0, gap = 0, worstGap = 0, floors = 0;
                for (int[] st : steps) {
                    if (st[1] == 1) { torches++; gap = 0; } else worstGap = Math.max(worstGap, ++gap);
                    floors += st[2];
                }
                Kit.log("[VT] fb08 the stair: " + steps.size() + " steps, " + torches + " torches, " + floors + " stair floors, longest dark run " + worstGap);
                ok.that(steps.size() >= 16 && steps.get(steps.size() - 1)[0] == foot.getY(), "a stair all the way down: " + steps.size() + " steps to " + foot.toShortString());
                ok.that(torches >= steps.size() / Quarry.TORCH_EVERY && worstGap < Quarry.TORCH_EVERY, "the stair lit, a torch at least every "
                    + Quarry.TORCH_EVERY + " steps: " + torches + " torches, longest dark run " + worstGap);
                ok.that(floors >= steps.size() - 2, "its steps laid as stairs: " + floors + " of " + steps.size());
                ok.that(Quarry.lightsForTests(level, s), "no dark spot in the pit for a monster");
                // A quarryman at the bottom with a load: out to the yard and back down to cut a block left in the stair's foot.
                mark[0] = foot.above(Quarry.HEAD - 1);
                chest(level, t.heart(), 4, -4, new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.STONE_BRICKS, 64),
                    new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.BRICKS, 64));   // the stores full: no load wanted in town
                Villages.forgetStores(id);
                level.setBlock(mark[0], Blocks.COBBLESTONE.defaultBlockState(), 3);
                m.keepTradeForTests();
                m.setJob(StationTask.MINE);
                m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
                Ledger.note(id, "quarry.men", m.getStringUUID());
                m.teleportTo(foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5);
                m.getNavigation().stop();
                Quarry.loadedForTests(m, 48);
                yard0[0] = StoneYard.count(id, Items.COBBLESTONE);
                level.setDayTime((level.getDayTime() / 24000L) * 24000L + 1050L);
                last[0] = m.getX(); last[1] = m.getY(); last[2] = m.getZ();
                com.jrpetty.mcassistant.entity.Blink.reset();
                com.jrpetty.mcassistant.entity.Blink.setOn(true);
                tick[0] = 0;
                return;
            }
            double dx = m.getX() - last[0], dz = m.getZ() - last[2];
            double step = Math.sqrt(dx * dx + dz * dz);
            if (tick[0] > 3 && step > worst[0]) worst[0] = step;
            last[0] = m.getX(); last[1] = m.getY(); last[2] = m.getZ();
            String doing = Quarry.doingForTests(m);
            if (doing != null && did.add(doing)) Kit.log("[VT] fb08 tick " + tick[0] + ": " + doing + " at " + m.blockPosition().toShortString());
            if (tick[0] % 100 == 0) {
                net.minecraft.world.level.pathfinder.Path path = m.getNavigation().getPath();
                Kit.log("[VT] fb08 tick " + tick[0] + " at " + m.blockPosition().toShortString() + " climbing " + m.onClimbable() + " ground " + m.onGround()
                    + " path " + (path == null ? "none" : path.getNodeCount() + " nodes to " + path.getTarget().toShortString() + " end " + (path.getEndNode() == null ? "-" : path.getEndNode().asBlockPos().toShortString())
                    + " reaches " + path.canReach() + " next " + path.getNextNodeIndex()) + " doing " + doing);
            }
            com.jrpetty.mcassistant.entity.TradeWagon cart = com.jrpetty.mcassistant.entity.QuarryCart.cartForTests(level, id);
            boolean up = StoneYard.count(id, Items.COBBLESTONE) >= yard0[0] + 48 || cart != null && com.jrpetty.mcassistant.entity.QuarryCart.aboardForTests(cart) >= 48;
            if (yardAt[0] == 0 && up && doing != null && !doing.startsWith("TO_YARD")) {
                yardAt[0] = tick[0];
                Kit.log("[VT] fb08 at the yard after " + tick[0] + " ticks");
            }
            if (yardAt[0] > 0 && level.getBlockState(mark[0]).isAir()) {
                Kit.log("[VT] fb08 back down and cut the bottom block after " + tick[0] + " ticks; longest step " + String.format("%.2f", worst[0])
                    + "; jumps " + com.jrpetty.mcassistant.entity.Blink.byCause());
                ok.that(worst[0] < 1.1, "walked and climbed, never put anywhere: longest step " + String.format("%.2f", worst[0]));
                ok.that(com.jrpetty.mcassistant.entity.Blink.byCause().isEmpty(), "no jumps: " + com.jrpetty.mcassistant.entity.Blink.byCause());
                Quarry.standDownForTests(true);
                com.jrpetty.mcassistant.entity.Blink.setOn(false);
                if (!ok.clean()) helper.fail(ok.summary());
                helper.succeed();
            }
            if (tick[0] == 4400) {
                com.jrpetty.mcassistant.entity.Blink.setOn(false);
                helper.fail("the quarryman did not make the round: yard " + (yardAt[0] > 0 ? "reached at " + yardAt[0] : "never reached") + ", now "
                    + Quarry.doingForTests(m) + " at " + m.blockPosition().toShortString() + ", did " + did + "; " + ok.summary());
            }
        });
    }

    // ------------------------------------------------------------------ fb11: the quarry's cart

    /**
     * [quarry2] fb11: a quarryman brings a load up to the quarry's handcart at its rim; full, it pushes it to the stone yard
     * and on to the storehouse (the stores low on stone), unloads at each, and pushes it home again. The yard's stock and the
     * stores' rise; the driver walks every step (never put anywhere: no jump, seen or not); the Stone page says where it is.
     */
    @GameTest(template = EMPTY, timeoutTicks = 7200, batch = "fb11_quarry_cart")
    public static void fb11_quarry_cart(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 8_270_000, 70, Villages.Age.STONE);
        UUID id = t.id();
        Villages.setFieldsSide(id, TownPlan.WEST);
        Quarry.standDownForTests(false);
        Quarry.readyForTests(true);
        BlockPos c = rock(level, Kit.surface(level, t.heart().getX() + 40, t.heart().getZ() + 40), 14, 0);
        Quarry.openForTests(level, t.v(), new Quarry.Site(c, Quarry.START, true));
        helper.assertTrue(StoneYard.site(id) != null, "a yard");
        StoneYard.layOutForTests(level, t.v());
        chest(level, t.heart(), 4, -4, new ItemStack(Items.OAK_FENCE, 64), new ItemStack(Items.OAK_FENCE_GATE, 2), new ItemStack(Items.LANTERN, 16),
            new ItemStack(Items.STONE_PICKAXE, 2), new ItemStack(Items.BREAD, 32));
        Villages.forgetStores(id);
        Quarry.fenceForTests(level, t.v());
        com.jrpetty.mcassistant.entity.QuarryCart.ensureForTests(level, t.v());
        com.jrpetty.mcassistant.entity.TradeWagon cart = com.jrpetty.mcassistant.entity.QuarryCart.cartForTests(level, id);
        helper.assertTrue(cart != null, "a handcart stood at the quarry's rim");
        BlockPos station = cart.blockPosition();
        Kit.log("[VT] fb11 the cart at " + station.toShortString() + " (quarry " + c.toShortString() + ", yard " + StoneYard.site(id).centre().toShortString()
            + ", stores " + t.heart().offset(4, 0, -4).toShortString() + ")");
        VillageFolkEntity m = t.first();
        m.keepTradeForTests();
        m.setJob(StationTask.MINE);
        m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
        Ledger.note(id, "quarry.men", m.getStringUUID());
        level.setDayTime((level.getDayTime() / 24000L) * 24000L + 1200L);
        // The stores out of stone (the cart takes them some, and the yard's stone bricks with it), the yard with bricks to pick up.
        for (BlockPos sc : Villages.storeChests(level, id)) {
            if (!(level.getBlockEntity(sc) instanceof Container box)) continue;
            for (int i = 0; i < box.getContainerSize(); i++) if (box.getItem(i).is(Items.COBBLESTONE) || box.getItem(i).is(Items.STONE_BRICKS)) box.setItem(i, ItemStack.EMPTY);
            box.setChanged();
        }
        StoneYard.stockForTests(id, Items.STONE_BRICKS, 64);
        int yard0 = StoneYard.count(id, Items.COBBLESTONE);
        int stores0 = com.jrpetty.mcassistant.entity.Market.stock(level, id, x -> x.is(Items.COBBLESTONE) || x.is(Items.STONE_BRICKS));
        int bricks0 = com.jrpetty.mcassistant.entity.Market.stock(level, id, x -> x.is(Items.STONE_BRICKS));
        Quarry.loadedForTests(m, com.jrpetty.mcassistant.entity.QuarryCart.FULL);
        com.jrpetty.mcassistant.entity.Blink.reset();
        com.jrpetty.mcassistant.entity.Blink.setOn(true);
        double[] last = { m.getX(), m.getY(), m.getZ() };
        double[] worst = { 0 };
        int[] tick = { 0 };
        boolean[] drove = { false };
        List<String> said = new ArrayList<>();
        Set<String> did = new HashSet<>();
        helper.onEachTick(() -> {
            tick[0]++;
            double dx = m.getX() - last[0], dz = m.getZ() - last[2];
            double step = Math.sqrt(dx * dx + dz * dz);
            if (tick[0] > 3 && step > worst[0]) {
                worst[0] = step;
                if (step > 1.0) Kit.log("[VT] fb11 a step of " + String.format("%.2f", step) + " at tick " + tick[0] + " doing " + Quarry.doingForTests(m));
            }
            last[0] = m.getX(); last[1] = m.getY(); last[2] = m.getZ();
            String doing = Quarry.doingForTests(m);
            if (doing != null && did.add(doing.replaceAll("\\d+", "#"))) Kit.log("[VT] fb11 tick " + tick[0] + ": " + doing + " at " + m.blockPosition().toShortString());
            String words = com.jrpetty.mcassistant.entity.QuarryCart.words(id);
            if (words != null && (said.isEmpty() || !said.get(said.size() - 1).equals(words))) {
                said.add(words);
                Kit.log("[VT] fb11 tick " + tick[0] + " " + words + " (cart at " + cart.blockPosition().toShortString() + ", trip " + com.jrpetty.mcassistant.entity.QuarryCart.tripForTests(id) + ")");
            }
            if (m.getUUID().equals(com.jrpetty.mcassistant.entity.QuarryCart.driverForTests(cart))) drove[0] = true;
            if (tick[0] % 200 == 0) Kit.log("[VT] fb11 tick " + tick[0] + " cart " + com.jrpetty.mcassistant.entity.QuarryCart.phaseForTests(cart) + " at "
                + String.format("%.1f,%.1f", cart.getX(), cart.getZ()) + " along " + String.format("%.1f/%.1f", com.jrpetty.mcassistant.entity.Wagons.alongForTests(cart),
                com.jrpetty.mcassistant.entity.Wagons.lengthForTests(cart)) + "; driver at " + String.format("%.1f,%.1f", m.getX(), m.getZ()) + " " + Quarry.doingForTests(m));
            long[] tally = com.jrpetty.mcassistant.entity.QuarryCart.tallyForTests(id);
            int yard = StoneYard.count(id, Items.COBBLESTONE);
            int stores = com.jrpetty.mcassistant.entity.Market.stock(level, id, x -> x.is(Items.COBBLESTONE) || x.is(Items.STONE_BRICKS));
            if (tally[0] >= 1 && "PARKED".equals(com.jrpetty.mcassistant.entity.QuarryCart.phaseForTests(cart))) {
                Kit.Expect ok = new Kit.Expect();
                ok.that(drove[0], "the quarryman pushed the cart");
                ok.that(yard > yard0, "the yard's stock rose: " + yard0 + " -> " + yard);
                ok.that(stores > stores0, "the stores' stone rose: " + stores0 + " -> " + stores);
                int bricks = com.jrpetty.mcassistant.entity.Market.stock(level, id, x -> x.is(Items.STONE_BRICKS));
                ok.that(bricks > bricks0 && StoneYard.count(id, Items.STONE_BRICKS) < 64, "the yard's stone bricks picked up and taken to the stores: stores "
                    + bricks0 + " -> " + bricks + ", yard " + StoneYard.count(id, Items.STONE_BRICKS));
                ok.that(tally[1] >= com.jrpetty.mcassistant.entity.QuarryCart.FULL - 8, "the load carried: " + tally[1]);
                ok.that(cart.blockPosition().distSqr(station) <= 9, "home at its station: " + cart.blockPosition().toShortString() + " (station " + station.toShortString() + ")");
                ok.that(said.stream().anyMatch(x -> x.startsWith("Cart: on the road to the yard, ")), "the board said it was on the road to the yard: " + said);
                ok.that(said.stream().anyMatch(x -> x.matches("Cart: taking \\d+ stone to the storehouse.*")), "the board said where it took the rest: " + said);
                ok.that(worst[0] < 1.1, "the driver walked every step, never put anywhere: longest step " + String.format("%.2f", worst[0]));
                ok.that(com.jrpetty.mcassistant.entity.Blink.byCause().isEmpty(), "no jumps: " + com.jrpetty.mcassistant.entity.Blink.byCause());
                net.minecraft.nbt.CompoundTag rep = Quarry.report(level, t.v());
                ok.that(rep.getString("cart").startsWith("Cart: ") && rep.getLong("cartTrips") >= 1, "the Stone page's cart line: " + rep.getString("cart") + ", " + rep.getLong("cartTrips") + " trips");
                Kit.log("[VT] fb11 home after " + tick[0] + " ticks: yard " + yard0 + " -> " + yard + ", stores " + stores0 + " -> " + stores + ", carried " + tally[1]
                    + "; said " + said + "; last " + rep.getString("cartLast"));
                Quarry.standDownForTests(true);
                com.jrpetty.mcassistant.entity.Blink.setOn(false);
                if (!ok.clean()) helper.fail(ok.summary());
                helper.succeed();
            }
            if (tick[0] == 7000) {
                com.jrpetty.mcassistant.entity.Blink.setOn(false);
                helper.fail("the cart never made the round: " + said + "; cart " + com.jrpetty.mcassistant.entity.QuarryCart.phaseForTests(cart) + " at "
                    + cart.blockPosition().toShortString() + " aboard " + com.jrpetty.mcassistant.entity.QuarryCart.aboardForTests(cart) + "; quarryman "
                    + Quarry.doingForTests(m) + " at " + m.blockPosition().toShortString() + "; yard " + yard + ", stores " + stores + ", did " + did);
            }
        });
    }

    // ------------------------------------------------------------------ fb09: up the wall's steps, never put there

    @GameTest(template = EMPTY, timeoutTicks = 7000, batch = "fb09_wall_stairs")
    public static void fb09_wall_stairs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 8_260_000, 62, Villages.Age.STONE);
        UUID id = t.id();
        Kit.Expect ok = new Kit.Expect();
        CastleWall.standDownForTests(false);
        CastleWall.readyForTests(true);
        TownDanger.stageForTests(id, DangerLevel.STONE);
        Police.standDownForTests(false);
        built(id, t.heart(), firstBlock());
        CastleWall.buildNow(level, t.v(), true);
        // Steps up on every side, each step a stair with room over it, the top one onto the walk.
        int height = CastleWall.heightForTests(id);
        int[] perSide = new int[4];
        List<String> bad = new ArrayList<>();
        for (int[] f : CastleWall.flightsForTests(id)) {
            perSide[f[0]]++;
            for (int k = 0; k < height; k++) {
                BlockPos p = CastleWall.stepForTests(id, f, k);
                if (p == null) continue;
                BlockState st = level.getBlockState(p);
                boolean room = level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                    && level.getBlockState(p.above(2)).getCollisionShape(level, p.above(2)).isEmpty();
                if (!(st.getBlock() instanceof net.minecraft.world.level.block.StairBlock) || !room) {
                    if (bad.size() < 8) bad.add(p.toShortString() + " " + st.getBlock().getName().getString() + (room ? "" : " (no headroom)"));
                }
            }
        }
        Kit.log("[VT] fb09 flights by side " + java.util.Arrays.toString(perSide) + ", height " + height + ", faults " + bad);
        ok.that(perSide[0] >= 2 && perSide[1] >= 2 && perSide[2] >= 2 && perSide[3] >= 2, "flights up on every side: " + java.util.Arrays.toString(perSide));
        ok.that(bad.isEmpty(), "every step a stair with headroom: " + bad);
        chest(level, t.heart(), 4, -4, new ItemStack(Items.LANTERN, 8), new ItemStack(Items.TORCH, 16), new ItemStack(Items.BREAD, 32));
        Villages.forgetStores(id);
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
        int ground = t.heart().getY();
        Kit.log("[VT] fb09 the guard's duty tonight: " + Police.dutyForTests(level, g) + "; on the ground at " + g.blockPosition().toShortString());
        com.jrpetty.mcassistant.entity.Blink.reset();
        com.jrpetty.mcassistant.entity.Blink.setOn(true);
        double[] last = { g.getX(), g.getY(), g.getZ() };
        double[] worst = { 0 };
        int[] ticks = { 0 };
        int[] phase = { 0 };
        int[] downAt = { 0 };
        boolean[] stepped = { false };
        Set<BlockPos> visited = new HashSet<>();
        Set<Integer> ends = new HashSet<>();
        helper.onEachTick(() -> {
            ticks[0]++;
            double dx = g.getX() - last[0], dy = g.getY() - last[1], dz = g.getZ() - last[2];
            double step = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (ticks[0] > 2 && step > worst[0]) {
                worst[0] = step;
                if (step > 1.1) Kit.log("[VT] fb09 a step of " + String.format("%.2f", step) + " at tick " + ticks[0] + " to " + g.blockPosition().toShortString());
            }
            last[0] = g.getX(); last[1] = g.getY(); last[2] = g.getZ();
            if (level.getBlockState(g.blockPosition()).getBlock() instanceof net.minecraft.world.level.block.StairBlock
                || level.getBlockState(g.blockPosition().below()).getBlock() instanceof net.minecraft.world.level.block.StairBlock) {
                if (g.getY() > ground + 0.5) stepped[0] = true;
            }
            if (g.onClimbable() && g.getY() > ground + 0.5) stepped[0] = true;   // (a tower's ladder: as good a way up)
            if (phase[0] == 0) {
                List<BlockPos> route = NightWatch.routeForTests(g);
                BlockPos stop = NightWatch.stopForTests(g);
                if (stop != null && !NightWatch.streetForTests(g) && g.distanceToSqr(stop.getX() + 0.5, stop.getY(), stop.getZ() + 0.5) < 2.6 * 2.6
                        && g.getY() > ground + height - 1 && visited.add(stop)) {
                    int i = route.indexOf(stop);
                    if (i == 0 || i == route.size() - 1) ends.add(i);
                    Kit.log("[VT] fb09 on the wall walk at stop " + i + "/" + route.size() + " " + stop.toShortString() + " after " + ticks[0] + " ticks");
                }
                if (NightWatch.streetForTests(g)) {
                    helper.fail("sent to the street: the wall walk could not be got up to; log " + NightWatch.logOf(id));
                    return;
                }
                if (route.size() >= 2 && ends.size() == 2) {
                    // The round walked end to end: the watch ends (the night's half over), and it comes down.
                    phase[0] = 1;
                    downAt[0] = ticks[0];
                    long d = level.getDayTime() / 24000L;
                    level.setDayTime(d * 24000L + 23400L);
                    Kit.log("[VT] fb09 round walked end to end (" + visited.size() + " stops) after " + ticks[0] + " ticks: the watch ends");
                }
                return;
            }
            if (g.getY() <= ground + 1.0 && g.onGround()) {
                Kit.log("[VT] fb09 down on the ground at " + g.blockPosition().toShortString() + " after " + (ticks[0] - downAt[0]) + " ticks; longest step "
                    + String.format("%.2f", worst[0]) + "; jumps " + com.jrpetty.mcassistant.entity.Blink.byCause());
                ok.that(stepped[0], "it went up (and down) by the steps or a tower's ladder");
                ok.that(worst[0] < 1.1, "never more than a step a tick: " + String.format("%.2f", worst[0]));
                ok.that(com.jrpetty.mcassistant.entity.Blink.byCause().isEmpty(), "no jumps: " + com.jrpetty.mcassistant.entity.Blink.byCause());
                com.jrpetty.mcassistant.entity.Blink.setOn(false);
                CastleWall.standDownForTests(true);
                Police.standDownForTests(true);
                if (!ok.clean()) helper.fail(ok.summary());
                helper.succeed();
            }
        });
        helper.runAtTickTime(6800, () -> {
            com.jrpetty.mcassistant.entity.Blink.setOn(false);
            helper.fail("the guard's round: phase " + phase[0] + ", stops " + visited.size() + "/" + NightWatch.routeForTests(g).size() + " ends " + ends
                + ", at " + g.blockPosition().toShortString() + ", log " + NightWatch.logOf(id) + "; " + ok.summary());
        });
    }

    // ------------------------------------------------------------------ fb10: the parks by the town's size

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "fb10_parks_tiers")
    public static void fb10_parks_tiers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 8_270_000, 74, Villages.Age.STONE);
        UUID id = t.id();
        Kit.Expect ok = new Kit.Expect();
        com.jrpetty.mcassistant.entity.GreatParks.standDownForTests(false);
        List<TownPlan.Lot> homes = firstBlock();
        built(id, t.heart(), homes);
        int[] want = { 39, 40, 70, 90 };
        int[] got = new int[4];
        for (int i = 0; i < want.length; i++) {
            com.jrpetty.mcassistant.entity.GreatParks.folkForTests(id, want[i]);
            com.jrpetty.mcassistant.entity.GreatParks.tickForTests(level, t.v());
            // A tier may want two at once (seventy: the second park); a second look for it.
            com.jrpetty.mcassistant.entity.GreatParks.tickForTests(level, t.v());
            got[i] = com.jrpetty.mcassistant.entity.GreatParks.greens(id).size();
            Kit.log("[VT] fb10 at " + want[i] + " folk: tier " + com.jrpetty.mcassistant.entity.GreatParks.tier(id) + ", greens "
                + com.jrpetty.mcassistant.entity.GreatParks.greens(id));
        }
        ok.that(got[0] == 0, "no big park at 39 folk: " + got[0]);
        ok.that(got[1] == 1, "a park at 40: " + got[1]);
        ok.that(got[2] == 2, "a second park at 70: " + got[2]);
        ok.that(got[3] == 3, "the central park at 90: " + got[3]);
        com.jrpetty.mcassistant.entity.GreatParks.Green central = null;
        for (com.jrpetty.mcassistant.entity.GreatParks.Green g : com.jrpetty.mcassistant.entity.GreatParks.greens(id)) {
            if (g.kind().equals(com.jrpetty.mcassistant.entity.GreatParks.LATE)) central = g;
        }
        helper.assertTrue(central != null, "a central park");
        final com.jrpetty.mcassistant.entity.GreatParks.Green cp = central;
        ok.that(cp.w() >= 40 && cp.d() >= 40, "four blocks across: " + cp.w() + " by " + cp.d());
        // Not one of the homes' lots under any green; every lot under a green kept off for building from now on.
        int under = 0, kept = 0, homesHit = 0;
        for (com.jrpetty.mcassistant.entity.GreatParks.Green g : com.jrpetty.mcassistant.entity.GreatParks.greens(id)) {
            for (TownPlan.Lot l : TownPlan.lots()) {
                int[] b = box(l);
                int lx = t.heart().getX() + b[0], lz = t.heart().getZ() + b[1];
                if (lx < g.x0() || lx > g.x1() || lz < g.z0() || lz > g.z1()) continue;
                under++;
                if (Villages.lotKeptOff(id, t.heart(), l)) kept++;
                for (TownPlan.Lot h : homes) if (h.key() == l.key()) homesHit++;
            }
        }
        Kit.log("[VT] fb10 lots under the greens " + under + ", kept off " + kept + ", homes' lots under them " + homesHit);
        ok.that(under > 0 && kept == under, "every lot under a green kept for it: " + kept + " of " + under);
        ok.that(homesHit == 0, "no home's lot taken: " + homesHit);
        // Laid out: the lake, the plaza, the pitch, the benches; no water anywhere but its ponds.
        com.jrpetty.mcassistant.entity.GreatParks.layAllForTests(level, id);
        Set<BlockPos> ponds = new HashSet<>();
        for (com.jrpetty.mcassistant.entity.GreatParks.Green g : com.jrpetty.mcassistant.entity.GreatParks.greens(id)) ponds.addAll(com.jrpetty.mcassistant.entity.GreatParks.waterForTests(g));
        int lake = 0;
        for (BlockPos p : com.jrpetty.mcassistant.entity.GreatParks.waterForTests(cp)) if (level.getFluidState(p).isSource()) lake++;
        int[] pitch = com.jrpetty.mcassistant.entity.GreatParks.pitchForTests(cp);
        int lines = 0;
        if (pitch != null) for (int x = pitch[0]; x <= pitch[2]; x++) if (level.getBlockState(new BlockPos(x, cp.y() + 1, pitch[1])).is(Blocks.WHITE_CARPET)) lines++;
        Park.Layout lay = com.jrpetty.mcassistant.entity.GreatParks.layoutForTests(cp);
        Kit.log("[VT] fb10 the central park: water " + lake + ", pitch " + java.util.Arrays.toString(pitch) + " (" + lines + " of its line marked), seats "
            + lay.seats().size() + ", lamps " + lay.lights().size() + ", trees " + lay.trees().size() + ", flowers " + lay.flowers().size());
        ok.that(lake >= 60, "a lake: " + lake + " blocks of water");
        ok.that(pitch != null && lines >= pitch[2] - pitch[0], "the sports green's pitch marked: " + lines);
        ok.that(lay.seats().size() >= 8 && lay.lights().size() >= 12 && lay.trees().size() >= 10 && lay.flowers().size() >= 40,
            "benches, lamps, trees and flowers: " + lay.seats().size() + "/" + lay.lights().size() + "/" + lay.trees().size() + "/" + lay.flowers().size());
        ok.that(Park.parks(id).size() >= 3, "visited as parks are: " + Park.parks(id).size());
        helper.runAfterDelay(200, () -> {
            int stray = 0;
            for (com.jrpetty.mcassistant.entity.GreatParks.Green g : com.jrpetty.mcassistant.entity.GreatParks.greens(id)) {
                for (int x = g.x0() - 2; x <= g.x1() + 2; x++) for (int z = g.z0() - 2; z <= g.z1() + 2; z++) for (int y = g.y() - 3; y <= g.y() + 4; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!level.getFluidState(p).isEmpty() && !ponds.contains(p)) stray++;
                }
            }
            ok.that(stray == 0, "no water outside the ponds after ten seconds: " + stray);
            CompoundTag rep = com.jrpetty.mcassistant.entity.GreatParks.report(level, t.v());
            ok.that(rep.getList("great", 10).size() == 3, "on the board: " + rep);
            com.jrpetty.mcassistant.entity.GreatParks.standDownForTests(true);
            com.jrpetty.mcassistant.entity.GreatParks.folkForTests(id, null);
            if (!ok.clean()) helper.fail(ok.summary());
            helper.succeed();
        });
    }
}
