package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.MapTableBlock;
import com.jrpetty.mcassistant.block.MapTableBlockEntity;
import com.jrpetty.mcassistant.entity.Bonds;
import com.jrpetty.mcassistant.entity.BorderDisputes;
import com.jrpetty.mcassistant.entity.BorderMarkers;
import com.jrpetty.mcassistant.entity.Borders;
import com.jrpetty.mcassistant.entity.MapTerrain;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WarAndPeace;
import com.jrpetty.mcassistant.entity.WarRoom;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.item.BorderItems;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [borders-map] The towns' lands (Borders), their disputes (BorderDisputes), their border markers (BorderMarkers) and the
 * war room's map table (WarRoom, MapTerrain).
 *
 * <p>Each test has its own batch and its own ground: x 2300000 to 2319999, z 70000, two thousand blocks apart. The game
 * test world is superflat grass: what lies on the ground (a lake) is put there by the test, and the map's terrain is
 * forgotten at the start of each so it knows only what the test shows it.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BordersMapGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;

    private static void setUp(ServerLevel level, int x, int r) {
        Kit.reset(level);
        Borders.resetForTests(level);
        Borders.switchForTests(true);
        Kit.hold(level, x, Z, r);
        Kit.prepare(level, x, Z, r);
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 3000L);
    }

    private static Villages.Village town(ServerLevel level, int x, int z) {
        Kit.prepare(level, x, z, 16);
        return Villages.found(level, Kit.surface(level, x, z));
    }

    private static void grow(UUID id, int folk, Villages.Age age) {
        for (int i = 0; i < folk; i++) Villages.recordBirth(id);
        Villages.ageForTests(id, age);
    }

    /** How many of the town's chunks lie along the row through its heart, east and west of it. */
    private static int[] reachEastWest(UUID id, Villages.Village v) {
        int hx = v.centre().getX() >> 4, hz = v.centre().getZ() >> 4;
        LongOpenHashSet t = Borders.territory(id);
        int east = 0, west = 0;
        while (t.contains(ChunkPos.asLong(hx + east + 1, hz))) east++;
        while (t.contains(ChunkPos.asLong(hx - west - 1, hz))) west++;
        return new int[]{ east, west };
    }

    private static boolean told(UUID village, String words) {
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().contains(words)) return true;
        return false;
    }

    /** A marked store chest at exactly this spot, filled with these. */
    private static Container chestAt(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        Villages.forgetStock();
        return box;
    }

    // ============================================================ bm01: the lands grow, and the ground shapes them

    /**
     * A new camp holds the ground round its heart; the same town grown to sixty folk and into the Iron Age holds far
     * more; a lake beside it stops its lands at the shore (they run on the other way); the lands as drawn come back
     * whole after a restart; and a town let go (a ruin) holds nothing.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bm01_lands")
    public static void bm01_lands(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2300000;
        setUp(level, x, 48);
        Villages.Village v = town(level, x, Z);
        UUID id = v.id();
        helper.runAtTickTime(20, () -> {
            MapTerrain.resetForTests(level);
            long day = level.getDayTime() / 24000L;
            Borders.drawNow(level);
            int a0 = Borders.area(id);
            double b0 = Borders.budget(id, day);
            Kit.log(String.format("bm01 a new camp: %d chunks, its word runs %.1f chunks", a0, b0));
            helper.assertTrue(id.equals(Borders.owner(level, v.centre())), "the heart is the town's own land");
            helper.assertTrue(a0 >= 40, "a camp holds the ground round it: " + a0 + " chunks");
            helper.assertTrue(Borders.owner(level, v.centre().offset(600, 0, 0)) == null, "and not the far country");

            // Grown: sixty folk, the Iron Age.
            grow(id, 60, Villages.Age.IRON);
            Borders.drawNow(level);
            int a1 = Borders.area(id);
            double b1 = Borders.budget(id, day);
            Kit.log(String.format("bm01 grown to %d folk in the Iron Age: %d chunks, its word runs %.1f chunks", Villages.headcount(id), a1, b1));
            helper.assertTrue(b1 > b0 + 6, "a bigger, older town's word runs further: " + b0 + " -> " + b1);
            helper.assertTrue(a1 > a0 * 3, "and its lands grow with it: " + a0 + " -> " + a1);
            int[] even = reachEastWest(id, v);
            Kit.log("bm01 on open ground: " + even[0] + " chunks east, " + even[1] + " west");
            helper.assertTrue(Math.abs(even[0] - even[1]) <= 1, "on open ground its lands run as far each way: " + even[0] + " east, " + even[1] + " west");

            // A lake to the east, four chunks across, as the map sees it: the lands stop at its shore.
            int lx0 = ((v.centre().getX() >> 4) + 4) * 16, lz0 = ((v.centre().getZ() >> 4) - 3) * 16;
            Kit.prepare(level, lx0 + 32, lz0 + 48, 64);
            for (int dx = 0; dx < 64; dx++) {
                for (int dz = 0; dz < 112; dz++) {
                    BlockPos top = Kit.surface(level, lx0 + dx, lz0 + dz).below();
                    level.setBlock(top, Blocks.WATER.defaultBlockState(), 2 | 16);
                }
            }
            for (int cx = lx0 >> 4; cx < (lx0 >> 4) + 4; cx++) {
                for (int cz = lz0 >> 4; cz < (lz0 >> 4) + 7; cz++) MapTerrain.lookForTests(level, cx, cz);
            }
            helper.assertTrue(MapTerrain.of(level).ground((lx0 >> 4) + 1, (lz0 >> 4) + 3) == MapTerrain.Ground.WATER,
                "the map knows the lake for water: " + MapTerrain.of(level).ground((lx0 >> 4) + 1, (lz0 >> 4) + 3));
            Borders.drawNow(level);
            int[] lake = reachEastWest(id, v);
            Kit.log("bm01 with the lake east: " + lake[0] + " chunks east, " + lake[1] + " west; " + Borders.area(id) + " chunks in all");
            helper.assertTrue(lake[0] + 3 <= lake[1], "water is hard to hold: the lands stop short to the east: " + lake[0] + " east, " + lake[1] + " west");

            // Kept over a restart, as drawn.
            String back = Borders.roundTripForTests(level);
            Kit.log("bm01 written out and read back: " + (back.isEmpty() ? "all as it was" : back));
            helper.assertTrue(back.isEmpty(), "the lands come back as they were after a restart: " + back);

            // Fallen to ruin: let go.
            Borders.release(id);
            helper.assertTrue(Borders.area(id) == 0 && Borders.fallen(id), "a ruin's lands are let go: " + Borders.area(id));
            helper.assertTrue(Borders.owner(level, v.centre()) == null, "nobody's land now");
            helper.succeed();
        });
    }

    // ============================================================ bm02: a dispute begins, and is settled

    /**
     * Two grown towns close together: their claims overlap and the ground both claim is contested. The one that holds
     * less of it complains (a dispute, in both chronicles and on both Lands pages, and a quarrel over the land in their
     * memories). Their elders on good terms settle it by treaty: the line agreed, nothing contested any more, the line
     * fixed as they grow. Another quarrel, between towns on bad terms, refused twice, sours into bad blood; and a war
     * for the border, won, gives the disputed ground to the winner.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bm02_dispute")
    public static void bm02_dispute(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2302000;
        setUp(level, x, 32);
        Villages.Village a = town(level, x, Z), b = town(level, x + 300, Z);
        helper.runAtTickTime(20, () -> {
            MapTerrain.resetForTests(level);
            grow(a.id(), 40, Villages.Age.STONE);
            grow(b.id(), 40, Villages.Age.STONE);
            Ledger.relate(a.id(), b.id(), 0);                       // they have met
            Borders.drawNow(level);
            long day = level.getDayTime() / 24000L;
            Kit.log("bm02 " + Villages.name(a.id()) + " " + Borders.area(a.id()) + " chunks, " + Villages.name(b.id()) + " "
                + Borders.area(b.id()) + "; contested " + Borders.contested(a.id(), b.id()));
            helper.assertTrue(Borders.contested(a.id(), b.id()), "two grown towns close together both claim the ground between them");

            // The dispute.
            BorderDisputes.lookAndStepForTests(level);
            BorderDisputes.Dispute d = BorderDisputes.open(a.id(), b.id());
            helper.assertTrue(d != null, "the overlap becomes a dispute: " + BorderDisputes.lines());
            Kit.log("bm02 dispute: " + Villages.name(d.claimant()) + " against " + Villages.name(d.against()) + " over " + d.subject()
                + " (" + d.cause() + "), " + d.state());
            helper.assertTrue(told(a.id(), d.subject()) && told(b.id(), d.subject()), "written in both chronicles");
            helper.assertTrue(Borders.disputed(a.id(), b.id()), "a live dispute between them");
            CompoundTag page = Borders.report(level, a);
            helper.assertTrue(page.getList("disputes", Tag.TAG_COMPOUND).size() == 1, "on the Lands page: " + page.getList("disputes", Tag.TAG_COMPOUND));
            int before = Ledger.relation(a.id(), b.id());

            // Their elders on good terms: a treaty, the line agreed.
            Ledger.relate(a.id(), b.id(), 40 - Ledger.relation(a.id(), b.id()));
            String how = BorderDisputes.decideForTests(level, d);
            Kit.log("bm02 heard: " + how);
            helper.assertTrue(d.state() == BorderDisputes.State.SETTLED, "settled: " + how);
            helper.assertTrue(Bonds.border(a.id(), b.id()), "the border agreed, with stones to mark it");
            Borders.drawNow(level);
            helper.assertTrue(!Borders.contested(a.id(), b.id()), "nothing contested between them once the line is agreed");
            BlockPos mid = new BlockPos((a.centre().getX() + b.centre().getX()) / 2, a.centre().getY(), Z);
            UUID midOwner = Borders.owner(level, mid);
            grow(b.id(), 60, Villages.Age.IRON);                      // the one grows on: the line stays where it was agreed
            Borders.drawNow(level);
            Kit.log("bm02 the middle " + (midOwner == null ? "nobody's" : Villages.name(midOwner)) + "'s, after " + Villages.name(b.id())
                + " grew: " + (Borders.owner(level, mid) == null ? "nobody's" : Villages.name(Borders.owner(level, mid))));
            helper.assertTrue(midOwner != null && midOwner.equals(Borders.owner(level, mid)), "the agreed line holds as a town grows");

            // Bad blood: another quarrel, refused twice.
            Ledger.relate(a.id(), b.id(), -30 - Ledger.relation(a.id(), b.id()));
            BorderDisputes.Dispute e = BorderDisputes.raise(level, b.id(), a.id(), BorderDisputes.Cause.BANNER, "a border banner pulled up", mid);
            BorderDisputes.decideForTests(level, e);
            BorderDisputes.decideForTests(level, e);
            Kit.log("bm02 the second quarrel: " + e.state() + " (" + e.outcome() + "), relation " + Ledger.relation(a.id(), b.id()));
            helper.assertTrue(e.state() == BorderDisputes.State.FEUD, "refused twice, it sours into bad blood: " + e.state());
            helper.assertTrue(Ledger.relation(a.id(), b.id()) < -30, "and the towns are the worse for it: " + Ledger.relation(a.id(), b.id()));

            // A war for the border, won by the claimant: the ground is the winner's.
            String land = Borders.afterWar(level, b.id(), a.id(), WarAndPeace.Goal.BORDER, 2, day);
            Borders.drawNow(level);
            UUID won = Borders.owner(level, mid);
            Kit.log("bm02 the war's end: " + land + "; the middle is " + (won == null ? "nobody" : Villages.name(won)) + "'s");
            helper.assertTrue(!land.isEmpty(), "the war settles the land");
            helper.assertTrue(b.id().equals(Borders.owner(level, mid)), "the disputed ground is the winner's");
            helper.assertTrue(e.state() == BorderDisputes.State.SETTLED, "and the dispute over: " + e.state());
            helper.assertTrue(before <= 0, "the quarrel cost them: " + before);
            String back = Borders.roundTripForTests(level);
            helper.assertTrue(back.isEmpty(), "the disputes and the fixed line come back after a restart: " + back);
            helper.succeed();
        });
    }

    // ============================================================ bm03: the map, as a town knows it

    /**
     * Three towns: ours, a neighbour we have met, and a far town we have never heard of. Our map table shows us and the
     * neighbour (their lands, their names), never the far town or its lands, and its country is blank paper; an admin's
     * table shows everything. The town's own table, made of its stores and set by its board, is drawn for it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bm03_fog")
    public static void bm03_fog(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2304000;
        setUp(level, x, 32);
        Villages.Village us = town(level, x, Z), met = town(level, x + 400, Z), far = town(level, x + 1600, Z + 400);
        helper.runAtTickTime(20, () -> {
            MapTerrain.resetForTests(level);
            Ledger.relate(us.id(), met.id(), 10);
            Borders.drawNow(level);
            BlockPos table = us.centre().offset(3, 0, 3);
            CompoundTag ours = WarRoom.buildForTests(level, us.id(), table, false);
            List<String> names = new ArrayList<>();
            ListTag towns = ours.getList("towns", Tag.TAG_COMPOUND);
            for (int i = 0; i < towns.size(); i++) names.add(towns.getCompound(i).getString("name"));
            Kit.log("bm03 our table shows: " + names + "; title " + ours.getString("title"));
            helper.assertTrue(names.contains(Villages.name(us.id())) && names.contains(Villages.name(met.id())), "us and the neighbour we met: " + names);
            helper.assertTrue(!names.contains(Villages.name(far.id())), "never a town we have not met: " + names);
            ListTag claims = ours.getCompound("claims").getList("towns", Tag.TAG_COMPOUND);
            for (int i = 0; i < claims.size(); i++) {
                helper.assertTrue(!claims.getCompound(i).getString("id").equals(far.id().toString()), "nor its lands");
            }
            helper.assertTrue(claims.size() == 2, "our lands and the neighbour's: " + claims.size());
            for (int i = 0; i < towns.size(); i++) {
                CompoundTag t = towns.getCompound(i);
                if (t.getBoolean("us")) helper.assertTrue(t.contains("strength"), "our own strength, counted");
                else helper.assertTrue(t.contains("terms") && t.contains("strength"), "how we stand with them and their strength as we reckon it: " + t);
            }

            // The terrain: our country drawn, the far town's blank.
            BlockPos fc = far.centre(), uc = us.centre();
            MapTerrain.lookForTests(level, uc.getX() >> 4, uc.getZ() >> 4);
            MapTerrain.lookForTests(level, fc.getX() >> 4, fc.getZ() >> 4);
            WarRoom.Known k = WarRoom.knownBy(level, us.id(), table);
            byte[] home = WarRoom.tile(level, k, Math.floorDiv(uc.getX() >> 4, MapTerrain.TILE), Math.floorDiv(uc.getZ() >> 4, MapTerrain.TILE));
            byte[] away = WarRoom.tile(level, k, Math.floorDiv(fc.getX() >> 4, MapTerrain.TILE), Math.floorDiv(fc.getZ() >> 4, MapTerrain.TILE));
            int seenHome = 0, seenAway = 0;
            for (byte p : home) if (p != 0) seenHome++;
            for (byte p : away) if (p != 0) seenAway++;
            Kit.log("bm03 pixels drawn: " + seenHome + " round our heart, " + seenAway + " round the far town");
            helper.assertTrue(seenHome > 0, "the country round our heart is drawn");
            helper.assertTrue(seenAway == 0, "the far town's country is blank paper to us: " + seenAway);
            helper.assertTrue(k.chunk(uc.getX() >> 4, uc.getZ() >> 4) && !k.chunk(fc.getX() >> 4, fc.getZ() >> 4), "we know our ground, not theirs");

            // An admin sees everything.
            CompoundTag all = WarRoom.buildForTests(level, us.id(), table, true);
            helper.assertTrue(all.getList("towns", Tag.TAG_COMPOUND).size() == 3, "an admin's table shows every town: "
                + all.getList("towns", Tag.TAG_COMPOUND).size());

            // The town's own table: made of its stores and set by its board (it has no hall).
            grow(us.id(), 12, Villages.Age.STONE);
            BlockPos stores = us.centre().offset(-4, 0, -6);
            chestAt(level, stores, new ItemStack(Items.PAPER, 4), new ItemStack(Items.COMPASS, 1), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64),
                new ItemStack(Items.CARTOGRAPHY_TABLE, 1), new ItemStack(Items.STICK, 8), new ItemStack(Items.CRAFTING_TABLE, 1));
            helper.assertTrue(WarRoom.wanted(us.id()), "a Stone Age town of a dozen wants a map table");
            boolean made = WarRoom.keepTableForTests(level, us);
            Kit.log("bm03 " + Borders.report(level, us).getString("table"));
            BlockPos at = WarRoom.table(level, us.id());
            Kit.log("bm03 the town's table: " + (at == null ? "none" : at.toShortString()) + "; compasses left " + Market.stock(level, us.id(), s -> s.is(Items.COMPASS)));
            helper.assertTrue(made && at != null && level.getBlockState(at).getBlock() instanceof MapTableBlock, "the town made its table and set it down");
            helper.assertTrue(Market.stock(level, us.id(), s -> s.is(Items.COMPASS)) == 0, "out of its stores: the compass went into it");
            helper.assertTrue(level.getBlockEntity(at) instanceof MapTableBlockEntity be && us.id().equals(be.town()), "drawn for the town");
            CompoundTag drawn = WarRoom.overlay(level, null, at, -1);
            helper.assertTrue(drawn.getString("title").contains(Villages.name(us.id())), "its title: " + drawn.getString("title"));
            helper.assertTrue(told(us.id(), "map table"), "into the chronicle");
            helper.assertTrue(BuiltInRegistries.ITEM.getKey(BorderItems.MAP_TABLE_ITEM.get()).equals(ResourceLocation.fromNamespaceAndPath("mc_assistant", "map_table")),
                "the table's item");
            helper.succeed();
        });
    }

    // ============================================================ bm05: the envoy argues it

    /**
     * The claimant's envoy before the other town's board. With coin in its purse, the other sells its claim: the coin
     * into its treasury, the ground the claimant's. With none, and on cool terms, a well-off steady elder keeps the ground
     * and pays the claimant to drop its claim: the coin in the envoy's purse, carried home.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "bm05_envoy")
    public static void bm05_envoy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2308000;
        setUp(level, x, 32);
        Villages.Village a = town(level, x, Z), b = town(level, x + 300, Z);
        helper.runAtTickTime(20, () -> {
            MapTerrain.resetForTests(level);
            grow(a.id(), 40, Villages.Age.STONE);
            grow(b.id(), 40, Villages.Age.STONE);
            Ledger.relate(a.id(), b.id(), 0);
            Borders.drawNow(level);
            BlockPos mid = new BlockPos(x + 150, a.centre().getY(), Z);

            // An offer: sold.
            Ledger.addCoins(a.id(), 60);
            int bBefore = Ledger.coins(b.id());
            BorderDisputes.Dispute d = BorderDisputes.raise(level, a.id(), b.id(), BorderDisputes.Cause.RESOURCE, "the wood on the line", mid);
            String heard = BorderDisputes.hearForTests(level, d, 20);
            Kit.log("bm05 with an offer: " + heard);
            helper.assertTrue(d.state() == BorderDisputes.State.SETTLED && d.outcome().contains("payment"), "sold for the coin: " + heard);
            helper.assertTrue(Ledger.coins(b.id()) == bBefore + 20, "the coin into the seller's treasury: " + bBefore + " -> " + Ledger.coins(b.id()));
            Borders.drawNow(level);
            helper.assertTrue(a.id().equals(Borders.owner(level, mid)), "and the ground the buyer's");

            // No offer, cool terms, a well-off neighbour: it keeps the ground and pays the claimant off.
            Ledger.relate(a.id(), b.id(), -10 - Ledger.relation(a.id(), b.id()));
            Ledger.addCoins(b.id(), 100);
            int aBefore = Ledger.coins(a.id()), bNow = Ledger.coins(b.id());
            BlockPos other = new BlockPos(x + 150, a.centre().getY(), Z + 48);
            BorderDisputes.Dispute e = BorderDisputes.raise(level, a.id(), b.id(), BorderDisputes.Cause.OVERLAP, "the land between them", other);
            String heard2 = BorderDisputes.hearForTests(level, e, 0);
            Kit.log("bm05 without: " + heard2);
            helper.assertTrue(e.state() == BorderDisputes.State.SETTLED, "settled: " + heard2);
            helper.assertTrue(Ledger.coins(b.id()) < bNow && Ledger.coins(a.id()) > aBefore, "the coin carried from the one treasury to the other: "
                + bNow + " -> " + Ledger.coins(b.id()) + ", " + aBefore + " -> " + Ledger.coins(a.id()));
            Borders.drawNow(level);
            helper.assertTrue(b.id().equals(Borders.owner(level, other)), "and the ground stays the payer's");
            helper.succeed();
        });
    }

    // ============================================================ bm04: the border markers

    /**
     * A town plants its banners at the border, out of its stores, on a post: on the line with its neighbour and out at
     * the points of the compass. One knocked down is planted again. They are kept over a restart.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "bm04_markers")
    public static void bm04_markers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2306000;
        setUp(level, x, 112);
        Kit.prepare(level, x + 140, Z, 112);
        Kit.hold(level, x + 140, Z, 112);
        Villages.Village v = town(level, x, Z), o = town(level, x + 140, Z);
        helper.runAtTickTime(30, () -> {
            MapTerrain.resetForTests(level);
            Ledger.relate(v.id(), o.id(), 0);
            Borders.drawNow(level);
            DyeColor c = BorderMarkers.colourOf(v.id());
            ItemStack banners = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(c.getName() + "_banner")), 16);
            chestAt(level, v.centre().offset(-4, 0, -6), banners, new ItemStack(Items.OAK_LOG, 32), new ItemStack(Items.COBBLESTONE, 16));
            List<String> did = BorderMarkers.workForTests(level, v);
            List<BlockPos> markers = BorderMarkers.markersForTests(level, v.id());
            Kit.log("bm04 " + Villages.name(v.id()) + " (" + c.getName() + ") planted: " + did + "; markers " + markers.size() + "; "
                + Borders.report(level, v).getCompound("markers") + "; banners in the stores " + Market.stock(level, v.id(), s -> s.is(banners.getItem())));
            helper.assertTrue(markers.size() >= 4, "banners at the border: " + markers);
            int onLine = 0;
            for (BlockPos p : markers) {
                helper.assertTrue(level.getBlockEntity(p) instanceof BannerBlockEntity be && be.getBaseColor() == c, "the town's banner at " + p.toShortString());
                helper.assertTrue(level.getBlockState(p.below()).is(Blocks.SPRUCE_LOG), "on a post at " + p.toShortString());
                helper.assertTrue(v.id().equals(Borders.owner(level, p)), "on its own land at " + p.toShortString());
                for (int dx = -16; dx <= 16; dx += 16) {
                    for (int dz = -16; dz <= 16; dz += 16) if (o.id().equals(Borders.owner(level, p.offset(dx, 0, dz)))) { onLine++; dx = 99; break; }
                }
            }
            Kit.log("bm04 on the line with " + Villages.name(o.id()) + ": " + onLine);
            helper.assertTrue(onLine >= 1, "some on the line with the neighbour");
            int left = Market.stock(level, v.id(), s -> s.is(banners.getItem()));
            helper.assertTrue(left == 16 - markers.size(), "every banner out of the stores: " + left + " left of 16 for " + markers.size());

            // Knocked down: planted again.
            BlockPos gone = markers.get(0);
            BorderMarkers.knockForTests(level, gone);
            List<String> again = BorderMarkers.workForTests(level, v);
            Kit.log("bm04 after one was knocked down: " + again);
            helper.assertTrue(BorderMarkers.bannerAt(level, gone), "planted again where it stood: " + again);
            helper.assertTrue(Market.stock(level, v.id(), s -> s.is(banners.getItem())) == left - 1, "with another banner from the stores");
            String back = Borders.roundTripForTests(level);
            helper.assertTrue(back.isEmpty(), "the markers come back after a restart: " + back);
            CompoundTag page = Borders.report(level, v);
            Kit.log("bm04 Lands page markers: " + page.getCompound("markers"));
            helper.assertTrue(page.getCompound("markers").getInt("banners") == markers.size() && page.getCompound("markers").getInt("again") == 1,
                "on the Lands page: " + page.getCompound("markers"));
            helper.succeed();
        });
    }
}
