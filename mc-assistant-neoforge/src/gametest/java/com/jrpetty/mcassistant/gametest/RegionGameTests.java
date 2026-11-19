package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Envoys;
import com.jrpetty.mcassistant.entity.Railways;
import com.jrpetty.mcassistant.entity.Region;
import com.jrpetty.mcassistant.entity.RegionStage;
import com.jrpetty.mcassistant.entity.Rivalry;
import com.jrpetty.mcassistant.entity.RoadWorks;
import com.jrpetty.mcassistant.entity.Routes;
import com.jrpetty.mcassistant.entity.TradeDeals;
import com.jrpetty.mcassistant.entity.TradeWorld;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Visitors;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [links12] The region: roads surveyed over the land between towns (Routes) and laid by both towns' gangs with a bridge
 * over the river, never through a player's building; a railway between towns with a strong trade (Railways); the route's
 * travel time and road money as a caravan reckons them; rivals made by the ground between them and trade lost, an embargo
 * that shuts their trade; an alliance out of good trade (Rivalry); the Region page and its keeping over a save (Region).
 * Towns at x 4,200,000 and on, z 90000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class RegionGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 90000;
    private static final long DAY = 24000L * 4;

    // ------------------------------------------------------------------ helpers

    private static void fresh(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        TradeWorld.resetForTests();
        Visitors.resetForTests();
        level.setDayTime(DAY + 2000);
        level.setWeatherParameters(24000, 0, false, false);
    }

    /** The ground held and made ready from x0 to x1, z0 to z1. */
    private static void hold(ServerLevel level, int x0, int x1, int z0, int z1) {
        for (int x = x0; x <= x1 + 64; x += 64) {
            for (int z = z0; z <= z1 + 64; z += 64) {
                Kit.hold(level, Math.min(x, x1), Math.min(z, z1), 40);
                Kit.prepare(level, Math.min(x, x1), Math.min(z, z1), 40);
            }
        }
    }

    /** Flat grass: its top at y - 1, clear air above. */
    private static void flat(ServerLevel level, int x0, int x1, int z0, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 20; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
    }

    /** A box of one block. */
    private static void box(ServerLevel level, int x0, int x1, int y0, int y1, int z0, int z1, BlockState st) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) level.setBlock(new BlockPos(x, y, z), st, 2 | 16);
    }

    /** A town of so many folk round x, z. */
    private static UUID town(GameTestHelper helper, int x, int z, int y, int folk) {
        ServerLevel level = helper.getLevel();
        BlockPos heart = new BlockPos(x, y, z);
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null && first.ownerId() != null, "a village at " + x);
        for (int i = 1; i < folk; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, new BlockPos(x + 2 + (i % 5), y, z + 2 + i / 5), 0.0F);
            helper.assertTrue(f != null && first.ownerId().equals(f.ownerId()), "folk " + i + " of the village");
            f.setAgeForTests(26 + i);
            f.insertItem(new ItemStack(Items.COOKED_BEEF, 16));
        }
        return first.ownerId();
    }

    private static void chest(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        Villages.forgetStock();
    }

    private static boolean heard(UUID town, String words) {
        for (String s : Region.news(town)) if (s.contains(words)) return true;
        return false;
    }

    private static boolean told(UUID town, String words) {
        for (Chronicle.Entry e : Chronicle.of(town)) if (e.text().contains(words)) return true;
        return false;
    }

    /**
     * Two towns 300 apart on flat ground, with a cliff-sided hill on the straight way between them (x + 90 to 120, z -18 to
     * 18, twelve high), a river across the whole way (x + 160 to 166, three deep) and a player's hut on the straight way
     * (x + 200 to 205). The ground is made before the towns are raised, so the road is surveyed over it.
     */
    private static UUID[] varied(GameTestHelper helper, int x) {
        ServerLevel level = helper.getLevel();
        hold(level, x - 48, x + 348, Z - 64, Z + 64);
        int y = Kit.surface(level, x, Z).getY();
        flat(level, x - 48, x + 348, Z - 62, Z + 62, y);
        box(level, x + 90, x + 120, y, y + 11, Z - 18, Z + 18, Blocks.STONE.defaultBlockState());
        box(level, x + 160, x + 166, y - 3, y - 3, Z - 62, Z + 62, Blocks.SAND.defaultBlockState());
        box(level, x + 160, x + 166, y - 2, y - 1, Z - 62, Z + 62, Blocks.WATER.defaultBlockState());
        box(level, x + 200, x + 205, y, y + 2, Z - 2, Z + 3, Blocks.OAK_PLANKS.defaultBlockState());
        box(level, x + 201, x + 204, y + 1, y + 1, Z - 2, Z - 2, Blocks.GLASS.defaultBlockState());
        UUID a = town(helper, x, Z, y, 7), b = town(helper, x + 300, Z, y, 7);
        return new UUID[]{ a, b };
    }

    // ============================================================ rg01: the road surveyed and laid, with its bridge

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "rg01_road")
    public static void rg01_road(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4200000;
        fresh(helper);
        UUID[] t = varied(helper, x);
        UUID a = t[0], b = t[1];
        int y = Kit.surface(level, x, Z + 30).getY();
        helper.runAtTickTime(5, () -> {
            List<int[]> line = RoadWorks.lineForTests(a, b);
            helper.assertTrue(line != null && line.size() > 150, "a road between them: " + (line == null ? "none" : line.size()));
            List<int[]> turns = Routes.turnsForTests(a, b);
            int far = 0, wet = 0, hill = 0, hut = 0;
            for (int[] p : line) {
                int dx = p[0] - x, dz = p[1] - Z;
                far = Math.max(far, Math.abs(dz));
                if (dx >= 160 && dx <= 166) wet++;
                if (dx >= 90 && dx <= 120 && Math.abs(dz) <= 18) hill++;
                if (dx >= 198 && dx <= 207 && dz >= -4 && dz <= 5) hut++;
            }
            Kit.log("[VT] rg01 road " + line.size() + " steps, " + (turns == null ? 0 : turns.size() - 2) + " turns, out to " + far + " off the line, "
                + wet + " over the river, " + hill + " on the hill, " + hut + " by the hut");
            helper.assertTrue(turns != null && turns.size() > 2, "the road turns round what is in its way: " + (turns == null ? "no survey" : turns.size()));
            helper.assertTrue(hill == 0, "not over the cliff-sided hill: " + hill);
            helper.assertTrue(hut == 0, "not through the player's hut: " + hut);
            helper.assertTrue(far > 18, "out round the hill: " + far);
            helper.assertTrue(wet >= 7, "over the river (no way round it): " + wet);
            helper.assertTrue(heard(a, "surveyed") && heard(b, "surveyed"), "both towns hear of the survey: " + Region.news(a));
            // Both gangs lay their halves to a path, the bridge with it, out of their own stores.
            UUID first = RoadWorks.firstForTests(a, b), other = first.equals(a) ? b : a;
            for (UUID me : new UUID[]{ first, other }) {
                Villages.Village v = Villages.get(me);
                chest(level, new BlockPos(v.centre().getX() - 3, y, Z + 30), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64),
                    new ItemStack(Items.STICK, 64));
                UUID o = me.equals(first) ? other : first;
                RoadWorks.setHalfForTests(me, o, 0, 0, 3);
                int laid = RoadWorks.workForTests(level, v, o, 2000);
                Kit.log("[VT] rg01 " + Villages.name(me) + " laid " + laid + " steps; half " + java.util.Arrays.toString(RoadWorks.halfForTests(me, o)));
                helper.assertTrue(RoadWorks.halfForTests(me, o)[0] == 1, "its half a path: " + java.util.Arrays.toString(RoadWorks.halfForTests(me, o))
                    + " " + RoadWorks.wantsForTests(level, v, o));
            }
            int path = 0, land = 0, deck = 0;
            for (int[] p : line) {
                int dx = p[0] - x;
                if (dx <= 41 || dx >= 259) continue;
                if (dx >= 160 && dx <= 166) {
                    if (level.getBlockState(new BlockPos(p[0], y - 1 + 1, p[1])).is(net.minecraft.tags.BlockTags.PLANKS)
                        || level.getBlockState(new BlockPos(p[0], y, p[1])).is(net.minecraft.tags.BlockTags.PLANKS)) deck++;
                    continue;
                }
                land++;
                if (level.getBlockState(Kit.surface(level, p[0], p[1]).below()).is(Blocks.DIRT_PATH)) path++;
            }
            Kit.log("[VT] rg01 path " + path + " of " + land + " land steps; bridge deck " + deck + " of " + wet);
            helper.assertTrue(path >= land * 9 / 10, "an earth path along the land: " + path + " of " + land);
            helper.assertTrue(deck >= wet - 1, "a plank bridge over the river: " + deck + " of " + wet);
            helper.assertTrue(level.getBlockState(new BlockPos(x + 202, y + 1, Z - 2)).is(Blocks.GLASS)
                && level.getBlockState(new BlockPos(x + 200, y + 2, Z)).is(Blocks.OAK_PLANKS), "the player's hut stands");
            helper.assertTrue(heard(a, "open end to end"), "the road opened, in the region's news: " + Region.news(a));
            Routes.Info info = Routes.between(a, b);
            Kit.log("[VT] rg01 route " + info);
            helper.assertTrue(info != null && info.grade() == 1 && info.bridges() >= 7 && info.length() > 300, "the route as trade sees it: " + info);
            helper.succeed();
        });
    }

    // ============================================================ rg02: the railway, for a strong trade

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "rg02_rail")
    public static void rg02_rail(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4204000;
        fresh(helper);
        hold(level, x - 48, x + 308, Z - 48, Z + 48);
        int y = Kit.surface(level, x, Z).getY();
        flat(level, x - 48, x + 308, Z - 40, Z + 40, y);
        UUID a = town(helper, x, Z, y, 10), b = town(helper, x + 260, Z, y, 10);
        Villages.Village va = Villages.get(a), vb = Villages.get(b);
        helper.runAtTickTime(5, () -> {
            Villages.ageForTests(a, Villages.Age.IRON);
            Villages.ageForTests(b, Villages.Age.IRON);
            helper.assertTrue(!RegionStage.wantsRailForTests(level, va, vb), "no line between towns with no trade");
            Rivalry.setForTests(a, b, 0, 30, 0);
            helper.assertTrue(Rivalry.strongTrade(a, b) && !Envoys.pact(a, b), "a strong trade, and no pact");
            helper.assertTrue(RegionStage.wantsRailForTests(level, va, vb), "a strong trade wants a railway");
            Routes.Info walking = Routes.between(a, b);
            Railways.Line line = RegionStage.planRail(level, va, vb);
            helper.assertTrue(line != null, "the line planned");
            helper.assertTrue(RegionStage.layRailFree(level, va, vb), "both halves laid and the line open");
            Railways.Line la = Railways.linkTo(a, b), lb = Railways.linkTo(b, a);
            helper.assertTrue(Railways.stationForTests(la, 0) && Railways.stationForTests(lb, 0), "a station at each end");
            int rails = 0;
            for (BlockPos r : la.rails()) if (level.getBlockState(r).is(net.minecraft.tags.BlockTags.RAILS)) rails++;
            Routes.Info riding = Routes.between(a, b);
            Kit.log("[VT] rg02 line " + la.length() + " rails, " + rails + " down; walking " + walking + "; riding " + riding);
            helper.assertTrue(rails >= la.length() - 2, "the rails down: " + rails + " of " + la.length());
            helper.assertTrue(riding.rail() && riding.travelTicks() < walking.travelTicks() / 2 && riding.roadMoney() <= walking.roadMoney(),
                "quicker and cheaper by rail: " + riding.travelTicks() + " against " + walking.travelTicks());
            helper.assertTrue(heard(a, "railway") && heard(b, "railway"), "the railway in the region's news: " + Region.news(a));
            helper.succeed();
        });
    }

    // ============================================================ rg03: the route's time and cost, as a caravan and an army go

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "rg03_travel")
    public static void rg03_travel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4208000;
        fresh(helper);
        UUID[] t = varied(helper, x);
        UUID a = t[0], b = t[1];
        Villages.Village va = Villages.get(a), vb = Villages.get(b);
        helper.runAtTickTime(5, () -> {
            List<int[]> line = RoadWorks.lineForTests(a, b);
            helper.assertTrue(line != null, "a road");
            // The caravan's way: along the surveyed road, every step of it near the road.
            List<BlockPos> way = RegionStage.wayForTests(va, vb);
            int off = 0, onRoad = 0;
            for (BlockPos p : way) {
                if (Math.abs(p.getX() - va.centre().getX()) < 45 || Math.abs(p.getX() - vb.centre().getX()) < 45) continue;
                double best = Double.MAX_VALUE;
                for (int[] q : line) best = Math.min(best, Math.hypot(q[0] - p.getX(), q[1] - p.getZ()));
                if (best > 2) off++;
                else onRoad++;
            }
            Kit.log("[VT] rg03 caravan way " + way.size() + " points, " + onRoad + " on the road, " + off + " off it");
            helper.assertTrue(onRoad > 10 && off == 0, "the caravan keeps to the road: " + onRoad + " on, " + off + " off");
            // Time and money: a track, then gravel both halves; a rival's toll.
            Routes.Info track = Routes.between(a, b);
            RoadWorks.setHalfForTests(a, b, 2, 0, 10);
            RoadWorks.setHalfForTests(b, a, 2, 0, 10);
            Routes.Info gravel = Routes.between(a, b);
            Kit.log("[VT] rg03 track " + track + "; gravel " + gravel);
            helper.assertTrue(track.length() > Math.sqrt(va.centre().distSqr(vb.centre())), "longer by the road than as the crow flies: " + track.length());
            helper.assertTrue(gravel.travelTicks() < track.travelTicks() && gravel.roadMoney() < track.roadMoney(), "quicker and cheaper on gravel");
            helper.assertTrue(Routes.travelTicks(a, b) == gravel.travelTicks() && Routes.carriage(a, b, 3) > gravel.roadMoney(), "the API for the market's carriage");
            Rivalry.setForTests(a, b, 50, 0, 0);
            Routes.Info rival = Routes.between(a, b);
            helper.assertTrue(rival.toll() > 0 && rival.roadMoney() == gravel.roadMoney() + rival.toll(), "a rival's toll on the road: " + rival);
            // An army marches along the road toward its camp.
            int[] mid = line.get(line.size() / 2);
            List<BlockPos> march = Routes.march(va, vb, va.centre(), new BlockPos(mid[0], va.centre().getY(), mid[1] + 6));
            helper.assertTrue(march != null && march.size() > 6, "the army marches by the road: " + (march == null ? "none" : march.size()));
            helper.succeed();
        });
    }

    // ============================================================ rg04: rivals from the ground and lost trade; the embargo

    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "rg04_rivals")
    public static void rg04_rivals(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4212000;
        fresh(helper);
        hold(level, x - 48, x + 268, Z - 48, Z + 48);
        int y = Kit.surface(level, x, Z).getY();
        flat(level, x - 48, x + 268, Z - 40, Z + 40, y);
        UUID a = town(helper, x, Z, y, 6), b = town(helper, x + 220, Z, y, 6);
        helper.assertTrue(!a.equals(b), "two towns");
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            Ledger.relate(a, b, 5 - Ledger.relation(a, b));
            int days = 0;
            while (Rivalry.score(a, b) < Rivalry.RIVAL && days < 30) {
                Rivalry.lostTrade(a, b, 12);                              // a's custom taken by b at the market
                Ledger.relate(a, b, Rivalry.dailyForTests(level, a, b, day + ++days));
            }
            Kit.log("[VT] rg04 rivals after " + days + " days: score " + Rivalry.score(a, b) + ", relation " + Ledger.relation(a, b)
                + ", why " + Rivalry.why(a, b) + ", stance " + Rivalry.stance(a, b));
            helper.assertTrue(Rivalry.rivals(a, b) && Rivalry.stance(a, b) == Rivalry.Stance.RIVAL, "rivals: " + Rivalry.score(a, b));
            helper.assertTrue(Rivalry.why(a, b).contains("ground") && Rivalry.why(a, b).contains("trade"), "over the ground and the trade: " + Rivalry.why(a, b));
            helper.assertTrue(heard(a, "rivals now") && told(b, "rivals now"), "the rivalry in the news");
            helper.assertTrue(Rivalry.worth(a, b) < 1.0 && Rivalry.worth(b, a) < 1.0, "a tariff on each other's goods");
            helper.assertTrue(!Rivalry.embargoed(a, b), "no embargo while relations are not soured");
            // Relations sour: the rivalry at its height, an embargo by the town that lost the trade.
            Ledger.relate(a, b, -30 - Ledger.relation(a, b));
            while (!Rivalry.embargoed(a, b) && days < 60) {
                Rivalry.lostTrade(a, b, 12);
                Ledger.relate(a, b, Rivalry.dailyForTests(level, a, b, day + ++days));
                if (Ledger.relation(a, b) > -25) Ledger.relate(a, b, -25 - Ledger.relation(a, b));
            }
            Kit.log("[VT] rg04 embargo after " + days + " days: score " + Rivalry.score(a, b) + ", by " + Villages.name(Rivalry.embargoBy(a, b)));
            helper.assertTrue(Rivalry.embargoed(a, b) && a.equals(Rivalry.embargoBy(a, b)), "the town that lost the trade embargoes the other");
            helper.assertTrue(!TradeDeals.talksDue(level, a, b, day + days + 10) && !TradeDeals.talksDue(level, b, a, day + days + 10), "no trade talks under it");
            helper.assertTrue(Routes.between(a, b).embargo(), "the route shut to trade");
            helper.assertTrue(heard(b, "embargo"), "the embargo in the news: " + Region.news(b));
            // Mended: the embargo lifted.
            Ledger.relate(a, b, 10 - Ledger.relation(a, b));
            Rivalry.dailyForTests(level, a, b, day + ++days);
            helper.assertTrue(!Rivalry.embargoed(a, b) && heard(a, "lifted its embargo"), "relations mended, the embargo lifted");
            helper.succeed();
        });
    }

    // ============================================================ rg05: an alliance out of good trade

    @GameTest(template = EMPTY, timeoutTicks = 900, batch = "rg05_allies")
    public static void rg05_allies(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4216000;
        fresh(helper);
        hold(level, x - 48, x + 468, Z - 48, Z + 48);
        int y = Kit.surface(level, x, Z).getY();
        flat(level, x - 48, x + 48, Z - 40, Z + 40, y);
        flat(level, x + 372, x + 468, Z - 40, Z + 40, y);
        UUID a = town(helper, x, Z, y, 6), b = town(helper, x + 420, Z, y, 6);
        helper.runAtTickTime(5, () -> {
            long day = level.getDayTime() / 24000L;
            Ledger.relate(a, b, 25 - Ledger.relation(a, b));
            int days = 0;
            while (!Envoys.allied(a, b) && days < 40) {
                Rivalry.traded(a, b, 9);                                  // the goods carried between them and paid for
                Ledger.relate(a, b, Rivalry.dailyForTests(level, a, b, day + ++days));
            }
            Kit.log("[VT] rg05 after " + days + " days: relation " + Ledger.relation(a, b) + ", trade " + Rivalry.tradeRun(a, b)
                + ", pact " + Envoys.pact(a, b) + ", allied " + Envoys.allied(a, b) + "; " + Region.news(a));
            helper.assertTrue(Envoys.pact(a, b) && heard(a, "trade pact"), "a pact signed out of the trade");
            helper.assertTrue(Envoys.allied(a, b) && heard(b, "alliance"), "an alliance sworn out of it");
            helper.assertTrue(Rivalry.stance(a, b) == Rivalry.Stance.ALLY && Rivalry.score(a, b) == 0, "allies, and no rivalry");
            helper.succeed();
        });
    }

    // ============================================================ rg06: kept over a save; the page; a sleeping town's gang

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "rg06_keep")
    public static void rg06_keep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4220000;
        fresh(helper);
        UUID[] t = varied(helper, x);
        UUID a = t[0], b = t[1];
        Villages.Village va = Villages.get(a);
        int y = Kit.surface(level, x, Z + 30).getY();
        helper.runAtTickTime(5, () -> {
            List<int[]> before = new ArrayList<>(RoadWorks.lineForTests(a, b));
            Rivalry.setForTests(a, b, 45, 7, 0);
            UUID first = a.compareTo(b) < 0 ? a : b, second = first.equals(a) ? b : a;
            Map<String, String> was = Ledger.notes(first), back = Ledger.notesAfterSaveForTests(first, level.registryAccess());
            helper.assertTrue(was.get("rt/" + second) != null && was.get("rt/" + second).equals(back.get("rt/" + second)), "the survey kept over a save");
            helper.assertTrue(was.get("rv/" + second) != null && was.get("rv/" + second).equals(back.get("rv/" + second)), "the rivalry kept over a save");
            // Forgotten in memory (a restart): the same road from the notes, no new survey.
            RoadWorks.resetForTests();
            List<int[]> after = RoadWorks.lineForTests(a, b);
            boolean same = after.size() == before.size();
            for (int i = 0; same && i < after.size(); i++) same = after.get(i)[0] == before.get(i)[0] && after.get(i)[1] == before.get(i)[1];
            helper.assertTrue(same, "the same road after a restart: " + before.size() + " / " + after.size());
            helper.assertTrue(Rivalry.score(a, b) == 45 && Rivalry.rivals(a, b), "the rivalry after a restart");
            // The page and the command.
            CompoundTag page = Region.report(level, va);
            List<String> lines = Region.lines(level, va);
            Kit.log("[VT] rg06 region: " + String.join(" / ", lines));
            helper.assertTrue(page.getList("towns", 10).size() >= 2 && page.getList("routes", 10).size() >= 1 && page.getList("league", 10).size() >= 2,
                "the page has the towns, the road and the league table");
            helper.assertTrue(String.join(" ", lines).contains("League table") && String.join(" ", lines).contains("rivals"), "the command says it");
            // A sleeping town's gang: the steps it would have laid owed, laid once it wakes.
            chest(level, new BlockPos(va.centre().getX() - 3, y, Z + 30), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64));
            RoadWorks.setHalfForTests(a, b, 0, 0, 3);
            RegionStage.workOnForTests(a, b);
            Routes.slept(a, 0.5);
            double owed = Routes.owedForTests(a);
            RegionStage.catchUpForTests(level, va);
            RegionStage.catchUpForTests(level, va);
            int laid = RoadWorks.halfForTests(a, b)[1];
            Kit.log("[VT] rg06 asleep: owed " + owed + ", laid " + laid + ", owed now " + Routes.owedForTests(a));
            helper.assertTrue(owed >= 59 && laid >= 40, "what the gang did asleep laid on waking: owed " + owed + ", laid " + laid);
            helper.succeed();
        });
    }
}
