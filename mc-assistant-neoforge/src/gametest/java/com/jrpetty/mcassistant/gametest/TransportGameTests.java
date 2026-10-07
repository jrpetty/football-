package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Bridges;
import com.jrpetty.mcassistant.entity.Ferries;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.RailCarts;
import com.jrpetty.mcassistant.entity.Railways;
import com.jrpetty.mcassistant.entity.Transport;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [transport] The railways, the ferry and the stone bridge (Railways, RailCarts, Ferries, Bridges).
 * <ul>
 * <li>tr01: an Iron Age town with iron, gold and redstone in its stores plans a line to its mine (over a stone ridge and
 *     a strip of water on the way), its smith makes the rails, the powered rails and the torches at the bench out of the
 *     stores (which go down), and its works lay the line: every rail joined to the next, something under each, the
 *     powered rails lit, a station at each end with its lever.</li>
 * <li>tr02: a chest minecart of ore sent home from the mine's station rolls down the line to the town's station, where
 *     it is unloaded into the storehouse and sent back.</li>
 * <li>tr03: a ferryman rows a farmer across the river to its fields, and the fare goes from the farmer's purse to the
 *     ferryman's.</li>
 * <li>tr04: the stone bridge, voted, is built across beside the ferry out of the stores' stone, arches and all, and the
 *     ferry retires: its boat back into the stores, its ferryman to another trade, the chronicle told.</li>
 * <li>tr05: in a town big enough for a referendum, the bridge is put to the whole town as a great work, carried, built
 *     by the town's hands beside the ferry and opened, and the ferry retires.</li>
 * </ul>
 * Each on its own ground (x 1160000 to 1168000, z 66000), in a batch of its own. The test world is flat, three blocks of
 * earth over bedrock: the ridge, the water and the river are cut into it here.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TransportGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    /** A village of these trades (the founder first), its stores a storehouse and nothing else, in this age. */
    private record Town(UUID village, Villages.Village v, BlockPos heart, StorehouseBlockEntity store, List<VillageFolkEntity> folk) {}

    private static Town town(GameTestHelper helper, int x, int reach, Villages.Age age, StationTask... trades) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Transport.resetForTests();
        level.setDayTime(2000);
        Kit.hold(level, x + reach / 2, Z, reach / 2 + 40);
        Kit.prepare(level, x + reach / 2, Z, reach / 2 + 40);
        BlockPos heart = Kit.surface(level, x, Z);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < trades.length; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.north(2 + i), 0.0F);
            helper.assertTrue(f != null && (folk.isEmpty() || folk.get(0).ownerId().equals(f.ownerId())), "folk " + i);
            folk.add(f);
        }
        UUID village = folk.get(0).ownerId();
        StorehouseBlockEntity store = storehouse(helper, level, heart, -8, -8);
        onlyTheStorehouse(level, village);
        Villages.ageForTests(village, age);
        for (int i = 0; i < trades.length; i++) if (trades[i] != StationTask.NONE) folk.get(i).setJob(trades[i]);
        return new Town(village, Villages.get(village), heart, store, folk);
    }

    private static StorehouseBlockEntity storehouse(GameTestHelper helper, ServerLevel level, BlockPos heart, int dx, int dz) {
        BlockPos origin = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(3, 4, 3))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        net.minecraft.world.level.block.state.BlockState unit = StorehouseBlock.loose();
        StorehouseBlock.hintFront(Direction.SOUTH);
        try {
            for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
                level.setBlock(origin.offset(x, y, z), unit, 3);
            }
        } finally {
            StorehouseBlock.hintFront(null);
        }
        BlockPos door = origin.offset(StorehouseBlock.doorOffset(Direction.SOUTH));
        helper.assertTrue(level.getBlockEntity(door) instanceof StorehouseBlockEntity s && s.isStore(), "a storehouse stands: " + level.getBlockState(door));
        return (StorehouseBlockEntity) level.getBlockEntity(door);
    }

    /** No chest about but the storehouse (the founders' chest goes). */
    private static void onlyTheStorehouse(ServerLevel level, UUID village) {
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c) {
                c.clearContent();
                level.removeBlock(p, false);
            }
        }
        Villages.forgetStores(village);
    }

    /** The storehouse filled with these, in its first slots, and the village's counts made afresh. */
    private static void fill(Town t, ItemStack... goods) {
        for (int i = 0; i < t.store().getContainerSize(); i++) t.store().setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < goods.length; i++) t.store().setItem(i, goods[i]);
        Villages.forgetStock();
        Villages.forgetStores(t.village());
        com.jrpetty.mcassistant.entity.Budget.forget(t.village());
    }

    private static int stock(ServerLevel level, UUID village, Item it) {
        return Market.stock(level, village, s -> s.is(it));
    }

    /** No break, no meal and no bell in the middle of a test of something else. */
    private static void keepAtWork(ServerLevel level, VillageFolkEntity f) {
        if (f.breakNowForTests()) level.setDayTime(level.getDayTime() + 200);
    }

    // ================================================================== tr01: the line to the mine

    /**
     * An Iron Age town with its mine a hundred and ten blocks east: a stone ridge four high across the way at seventy
     * blocks out, and a strip of water at eighty. The line is planned (down the east avenue from a station by the trades'
     * lots, then across to a station short of the mine), the smith makes its rails, powered rails, torches and levers
     * at the bench out of the stores' iron, gold, redstone and sticks, a batch at a time, and the works lay it, rail by
     * rail, out of the stores. Every rail is joined to the next and has something under it; the line rises and falls
     * over the ridge and the water; its powered rails are lit; a station stands at each end with a lever on its buffer;
     * and the stores' iron, gold and redstone have gone down.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "tr01_line")
    public static void tr01_line(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1160000;
        Town t = town(helper, x, 150, Villages.Age.IRON, StationTask.NONE, StationTask.SMITH, StationTask.HAUL);
        UUID id = t.village();
        VillageFolkEntity smith = t.folk().get(1);
        int y = t.heart().getY();
        // The ridge: stone four high, seven across, the whole width of the way.
        for (int dx = 58; dx <= 64; dx++) {
            for (int dz = -60; dz <= 60; dz++) {
                for (int h = 0; h < 4; h++) level.setBlock(new BlockPos(x + dx, y + h, Z + dz), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        // The water: a strip five wide, two deep.
        for (int dx = 76; dx <= 80; dx++) {
            for (int dz = -60; dz <= 60; dz++) {
                level.setBlock(new BlockPos(x + dx, y - 1, Z + dz), Blocks.WATER.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x + dx, y - 2, Z + dz), Blocks.WATER.defaultBlockState(), 2);
            }
        }
        Ledger.note(id, "mine.site", (x + 110) + "," + Z);
        fill(t, new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.IRON_INGOT, 64),
            new ItemStack(Items.GOLD_INGOT, 64), new ItemStack(Items.GOLD_INGOT, 32), new ItemStack(Items.REDSTONE, 32),
            new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64),
            new ItemStack(Items.STICK, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64),
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.STONE_BRICKS, 32),
            new ItemStack(Items.LANTERN, 4), new ItemStack(Items.CRAFTING_TABLE), new ItemStack(Items.BREAD, 32));
        int iron0 = stock(level, id, Items.IRON_INGOT), gold0 = stock(level, id, Items.GOLD_INGOT), red0 = stock(level, id, Items.REDSTONE);
        Railways.Line l = Railways.planMineForTests(level, t.v());
        helper.assertTrue(l != null, "a line planned to the mine at " + (x + 110) + ", " + Z);
        Kit.log("tr01 planned: " + l.length() + " rails from " + l.rail(0).toShortString() + " to " + l.rail(l.length() - 1).toShortString()
            + "; kinds " + Railways.kindsForTests(l));
        // The smith at its bench and the works at the line, turn about, till it is all down.
        List<String> made = new ArrayList<>();
        for (int k = 0; k < 80; k++) {
            String m = Railways.smithForTests(level, t.v(), smith);
            if (m != null) made.add(m);
            Railways.layForTests(level, t.v(), l, 64);
            if (l.state() == Railways.State.OPEN && m == null) break;     // down, and nothing more wanted of the smith
        }
        // The torches made after the rails they light were laid: walked and put in.
        Railways.mendForTests(level, t.v(), l);
        int[] laid = Railways.laidForTests(l);
        int iron1 = stock(level, id, Items.IRON_INGOT), gold1 = stock(level, id, Items.GOLD_INGOT), red1 = stock(level, id, Items.REDSTONE);
        int[] power = Railways.powerForTests(level, l);
        String broken = Railways.brokenAtForTests(level, l);
        int ups = 0, highest = Integer.MIN_VALUE, lowest = Integer.MAX_VALUE, overRidge = 0, overWater = 0;
        for (int i = 0; i < l.length(); i++) {
            BlockPos r = l.rail(i);
            highest = Math.max(highest, r.getY());
            lowest = Math.min(lowest, r.getY());
            if (i > 0 && r.getY() != l.rail(i - 1).getY()) ups++;
            if (r.getX() >= x + 58 && r.getX() <= x + 64) overRidge++;
            if (r.getX() >= x + 76 && r.getX() <= x + 80 && level.getBlockState(r.below()).is(Blocks.SPRUCE_PLANKS)) overWater++;
        }
        BlockPos lever0 = Railways.bufferForTests(l, 0).above(), lever1 = Railways.bufferForTests(l, 1).above();
        Kit.log("tr01 the smith made: " + String.join("; ", made));
        Kit.log("tr01 laid " + laid[0] + " of " + laid[1] + ", " + l.state() + "; iron " + iron0 + " -> " + iron1 + ", gold " + gold0 + " -> " + gold1
            + ", redstone " + red0 + " -> " + red1 + "; powered " + power[2] + " (" + power[3] + " live), runs " + power[0] + " (" + power[1] + " lit); "
            + ups + " changes of level, " + lowest + ".." + highest + " (ground " + y + "), " + overRidge + " rails through the ridge, " + overWater
            + " on the deck over the water; stations " + Railways.stationForTests(l, 0) + "/" + Railways.stationForTests(l, 1) + ", levers "
            + level.getBlockState(lever0).getBlock() + "/" + level.getBlockState(lever1).getBlock() + "; broken: " + broken);
        for (String s : Transport.page(level, t.v())) Kit.log("tr01 page: " + s);
        helper.assertTrue(l.state() == Railways.State.OPEN, "the line open: " + laid[0] + " of " + laid[1] + " laid, stations "
            + Railways.stationForTests(l, 0) + "/" + Railways.stationForTests(l, 1));
        helper.assertTrue(broken == null, "every rail there, joined to the next, with something under it: " + broken);
        helper.assertTrue(iron0 - iron1 >= 12 && gold0 - gold1 >= 6 && red0 - red1 >= 1, "the rails made of the stores' own iron, gold and redstone: iron "
            + iron0 + " -> " + iron1 + ", gold " + gold0 + " -> " + gold1 + ", redstone " + red0 + " -> " + red1);
        helper.assertTrue(power[0] > 0 && power[1] == power[0] && power[3] == power[2], "every run of powered rails lit and live: " + power[1] + "/"
            + power[0] + " lit, " + power[3] + "/" + power[2] + " live");
        helper.assertTrue(ups >= 4 && highest > y && overRidge >= 7, "over the ridge and the water: " + ups + " changes of level, up to " + highest);
        helper.assertTrue(overWater >= 3, "a deck of planks over the water: " + overWater);
        helper.assertTrue(level.getBlockState(lever0).is(Blocks.LEVER) && level.getBlockState(lever1).is(Blocks.LEVER),
            "a lever on each buffer: " + level.getBlockState(lever0) + " / " + level.getBlockState(lever1));
        helper.assertTrue(Railways.shapeForTests(l, 0) == level.getBlockState(l.rail(0)).getValue(net.minecraft.world.level.block.PoweredRailBlock.SHAPE),
            "the station's rail shaped to the plan");
        boolean told = false;
        for (Villages.News n : Villages.news(id)) if (n.text().contains("railway to the mine was opened")) told = true;
        helper.assertTrue(told, "the chronicle has the opening");
        helper.succeed();
    }

    // ================================================================== tr02: a cart of ore home

    /**
     * A line laid (for nothing, the showcase's way: what is under test is the cart) from the town to a station short of its
     * mine. A chest minecart of the town's, at the mine's station with sixty-four raw iron and thirty-two coal in it, is
     * sent home: the lever thrown, it rolls down the line on its powered rails and stops against the town's buffer. A hand
     * from the storehouse unloads it into the stores, and it is sent back to the mine, empty. The books have the cart.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "tr02_ore")
    public static void tr02_ore(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1162000;
        Town t = town(helper, x, 140, Villages.Age.IRON, StationTask.NONE, StationTask.HAUL);
        UUID id = t.village();
        Ledger.note(id, "mine.site", (x + 105) + "," + (Z - 6));
        fill(t, new ItemStack(Items.BREAD, 16));
        Railways.planAnywayForTests(true);
        Railways.Line l = Railways.planMineForTests(level, t.v());
        Railways.planAnywayForTests(false);
        helper.assertTrue(l != null, "a line planned");
        Railways.layFreeForStage(level, t.v(), l);
        helper.assertTrue(l.state() == Railways.State.OPEN && Railways.brokenAtForTests(level, l) == null, "the line laid and open: "
            + Railways.brokenAtForTests(level, l));
        int n = l.length();
        BlockPos at = l.rail(n - 2);
        AbstractMinecart cart = AbstractMinecart.createMinecart(level, at.getX() + 0.5, at.getY() + 0.0625, at.getZ() + 0.5,
            AbstractMinecart.Type.CHEST, ItemStack.EMPTY, null);
        helper.assertTrue(cart instanceof MinecartChest, "a chest minecart");
        MinecartChest box = (MinecartChest) cart;
        box.setItem(0, new ItemStack(Items.RAW_IRON, 64));
        box.setItem(1, new ItemStack(Items.COAL, 32));
        level.addFreshEntity(cart);
        RailCarts.adoptForTests(id, l, cart, RailCarts.Role.ORE, 1);
        int raw0 = stock(level, id, Items.RAW_IRON), coal0 = stock(level, id, Items.COAL);
        boolean sent = RailCarts.dispatchForTests(level, id, l.key(), RailCarts.Role.ORE, 0);
        Kit.log("tr02 a line of " + n + " rails; the cart sent " + sent + " from " + cart.blockPosition().toShortString() + " with " + 96 + " goods");
        helper.assertTrue(sent, "the cart sent home");
        long[] arrived = { -1 };
        boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0]) return;
            long tick = helper.getTick();
            int[] st = RailCarts.cartStateForTests(id, l.key(), RailCarts.Role.ORE);
            if (tick % 40 == 0) {
                Kit.log("tr02 tick " + tick + ": cart at " + cart.blockPosition().toShortString() + ", moving "
                    + String.format("%.2f", cart.getDeltaMovement().horizontalDistance()) + ", state " + st[0] + "/" + st[1]);
            }
            // At the town's station: unloaded by the storehouse's hand (the town's own round may have done it already), and sent back.
            if (RailCarts.statsFor(id, l.key()).carts == 0) {
                if (st[0] != 0) return;
                if (arrived[0] < 0) {
                    arrived[0] = tick;
                    Kit.log("tr02 the cart came to the town's station at tick " + tick + ", at " + cart.blockPosition().toShortString()
                        + " (the buffer at " + Railways.bufferForTests(l, 0).toShortString() + ")");
                }
                RailCarts.workForTests(level, t.v());
                if (RailCarts.statsFor(id, l.key()).carts == 0) return;
            }
            int raw = stock(level, id, Items.RAW_IRON) - raw0, coal = stock(level, id, Items.COAL) - coal0;
            int left = 0;
            for (int i = 0; i < box.getContainerSize(); i++) left += box.getItem(i).getCount();
            int[] after = RailCarts.cartStateForTests(id, l.key(), RailCarts.Role.ORE);
            RailCarts.Stats s = RailCarts.statsFor(id, l.key());
            Kit.log("tr02 unloaded: stores +" + raw + " raw iron, +" + coal + " coal; " + left + " left in the cart; cart " + after[0] + "/" + after[1]
                + "; the books: " + s.carts + " carts, " + s.goods + " goods");
            for (String p : Transport.page(level, t.v())) Kit.log("tr02 page: " + p);
            done[0] = true;
            helper.assertTrue(raw == 64 && coal == 32 && left == 0, "the ore into the stores: +" + raw + " raw iron, +" + coal + " coal, " + left + " left");
            helper.assertTrue(s.carts == 1 && s.goods == 96, "the cart in the books: " + s.carts + ", " + s.goods);
            helper.assertTrue(after[1] == 1 || after[0] == 1, "the empty cart sent back to the mine: " + after[0] + "/" + after[1]);
            helper.assertTrue(cart.isAlive(), "the cart still on the line");
            helper.succeed();
        });
    }

    // ================================================================== the river

    /** A river across the world from north to south, ten wide and two deep, this far east of the heart. Returns its west bank's x. */
    private static int river(ServerLevel level, BlockPos heart, int east) {
        int y = heart.getY();
        int x0 = heart.getX() + east;
        for (int dx = 0; dx < 10; dx++) {
            for (int dz = -70; dz <= 70; dz++) {
                level.setBlock(new BlockPos(x0 + dx, y - 1, heart.getZ() + dz), Blocks.WATER.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x0 + dx, y - 2, heart.getZ() + dz), Blocks.WATER.defaultBlockState(), 2);
            }
        }
        return x0 - 1;
    }

    /** A farmer whose field is across the river: its plot over there. */
    private static void fieldOver(VillageFolkEntity f, BlockPos at) {
        f.setJob(StationTask.FARM);
        f.assignPlot(WorkZone.around(at, 4, WorkZone.DEFAULT_DEPTH), "The far field");
    }

    // ================================================================== tr03: across on the ferry

    /**
     * A river ten wide fifty blocks east of the heart, and a farmer whose field is over it. The town finds the crossing
     * (on the way to the field), builds a landing each side out of its stores (planks, fences, a lantern, the ferry bell
     * made at the bench of the stores' copper) and puts its boat in; a ferryman is taken on. The farmer, with five coins,
     * wants to cross: it walks onto the landing, steps into the boat behind the ferryman, is rowed over and set down on the
     * far landing; a coin goes from its purse to the ferryman's, and the books have the crossing and the fare.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1800, batch = "tr03_ferry")
    public static void tr03_ferry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1164000;
        Town t = town(helper, x, 100, Villages.Age.STONE, StationTask.NONE, StationTask.NONE);
        UUID id = t.village();
        VillageFolkEntity man = t.folk().get(0), farmer = t.folk().get(1);
        int bank = river(level, t.heart(), 50);
        fieldOver(farmer, Kit.surface(level, x + 75, Z));
        fill(t, new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.STICK, 16),
            new ItemStack(Items.COPPER_INGOT, 8), new ItemStack(Items.LANTERN, 4), new ItemStack(Items.OAK_BOAT), new ItemStack(Items.CRAFTING_TABLE),
            new ItemStack(Items.BREAD, 32), new ItemStack(Items.COBBLESTONE, 64));
        Ferries.weatherForTests(false);
        com.jrpetty.mcassistant.entity.Weather.stormForTests(false);
        Ferries.Crossing c = Ferries.surveyForTests(level, t.v());
        helper.assertTrue(c != null, "a crossing found over the river at x " + bank);
        Ferries.appointForTests(level, t.v(), man);
        Ferries.buildForTests(level, t.v());
        Boat boat = Ferries.boatForTests(level, id);
        boolean bellA = level.getBlockState(c.bankA().relative(c.way().getClockWise()).above()).is(com.jrpetty.mcassistant.McAssistantMod.FERRY_BELL.get());
        Kit.log("tr03 the crossing: " + c.bankA().toShortString() + " to " + c.bankB().toShortString() + " (" + c.width() + " wide, " + c.way() + "), "
            + c.state() + "; boat " + (boat == null ? "none" : boat.blockPosition().toShortString()) + "; bell " + bellA + "; copper left "
            + stock(level, id, Items.COPPER_INGOT));
        helper.assertTrue(c.state() == Ferries.State.RUNNING && boat != null, "the landings built and the boat in: " + c.state());
        helper.assertTrue(bellA, "the ferry bell beside the town's landing, made of the stores' copper");
        helper.assertTrue(man.stationTask() == StationTask.FERRY && farmer.stationTask() == StationTask.FARM,
            "a ferryman, and the farmer still a farmer: " + man.stationTask() + ", " + farmer.stationTask());
        BlockPos stand = c.bankA().relative(c.way(), 3).above();
        man.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.0F, 0.0F);
        BlockPos near = c.bankA().relative(c.way().getOpposite(), 2).above();
        farmer.moveTo(near.getX() + 0.5, near.getY(), near.getZ() + 0.5, 0.0F, 0.0F);
        farmer.earn(5);
        int purse0 = farmer.purse(), manPurse0 = man.purse();
        Ferries.bookForTests(farmer, level);
        helper.assertTrue(Ferries.passageForTests(farmer) == 0, "the farmer off to the ferry");
        boolean[] done = { false };
        int[] seen = { -1, -1 };
        helper.onEachTick(() -> {
            if (done[0]) return;
            keepAtWork(level, man);
            keepAtWork(level, farmer);
            long tick = helper.getTick();
            int stage = Ferries.passageForTests(farmer);
            if (stage != seen[0] || tick % 60 == 0) {
                seen[0] = stage;
                Boat b = Ferries.boatForTests(level, id);
                Kit.log("tr03 tick " + tick + ": farmer stage " + stage + " at " + farmer.blockPosition().toShortString() + " riding "
                    + (farmer.getVehicle() != null) + "; ferryman at " + man.blockPosition().toShortString() + " riding " + (man.getVehicle() != null)
                    + "; boat " + (b == null ? "none" : b.blockPosition().toShortString() + " with " + b.getPassengers().size()) + "; crossings " + c.crossings);
            }
            if (stage != -1 || c.crossings == 0) return;
            done[0] = true;
            int side = farmer.blockPosition().getX() > c.bankA().getX() + c.width() / 2 ? 1 : 0;
            Kit.log("tr03 across at tick " + tick + ": the farmer at " + farmer.blockPosition().toShortString() + " (side " + side + "), purse "
                + purse0 + " -> " + farmer.purse() + "; the ferryman's " + manPurse0 + " -> " + man.purse() + "; " + c.crossings + " crossings, "
                + c.fares + " in fares");
            for (String p : Transport.page(level, t.v())) Kit.log("tr03 page: " + p);
            helper.assertTrue(side == 1 && farmer.getVehicle() == null, "the farmer set down on the far landing: " + farmer.blockPosition().toShortString());
            helper.assertTrue(farmer.purse() == purse0 - Ferries.FARE && man.purse() == manPurse0 + Ferries.FARE,
                "a coin from the farmer's purse to the ferryman's: " + purse0 + " -> " + farmer.purse() + ", " + manPurse0 + " -> " + man.purse());
            helper.assertTrue(c.fares == Ferries.FARE && c.crossings >= 1, "the crossing and the fare in the books: " + c.crossings + ", " + c.fares);
            helper.succeed();
        });
    }

    // ================================================================== tr04: the stone bridge

    /**
     * The same river, the ferry running with its ferryman. The stone bridge in its place is put to the council (its vote
     * logged), then agreed, and built out of the stores' stone bricks beside the ferry: piers from the river bed, arches
     * between them, a deck from bank to bank with a wall either side, and stairs down at each end. Open, the ferry
     * retires: the boat back into the stores, the ferryman to another trade, and the chronicle tells it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "tr04_bridge")
    public static void tr04_bridge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1166000;
        Town t = town(helper, x, 100, Villages.Age.STONE, StationTask.NONE, StationTask.NONE, StationTask.NONE);
        UUID id = t.village();
        VillageFolkEntity man = t.folk().get(0), farmer = t.folk().get(1);
        river(level, t.heart(), 50);
        fieldOver(farmer, Kit.surface(level, x + 75, Z));
        List<ItemStack> goods = new ArrayList<>(List.of(new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64),
            new ItemStack(Items.STICK, 16), new ItemStack(Items.COPPER_INGOT, 8), new ItemStack(Items.LANTERN, 16), new ItemStack(Items.OAK_BOAT),
            new ItemStack(Items.CRAFTING_TABLE), new ItemStack(Items.BREAD, 32)));
        for (int i = 0; i < 12; i++) goods.add(new ItemStack(Items.STONE_BRICKS, 64));
        fill(t, goods.toArray(new ItemStack[0]));
        Ferries.weatherForTests(false);
        com.jrpetty.mcassistant.entity.Weather.stormForTests(false);
        Ferries.Crossing c = Ferries.surveyForTests(level, t.v());
        helper.assertTrue(c != null, "a crossing found");
        Ferries.appointForTests(level, t.v(), man);
        Ferries.buildForTests(level, t.v());
        helper.assertTrue(c.state() == Ferries.State.RUNNING && man.stationTask() == StationTask.FERRY, "the ferry running, a ferryman: " + c.state());
        Bridges.Verdict council = Bridges.councilForTests(level, t.v());
        Kit.log("tr04 the council on the bridge: " + council);
        int bricks0 = stock(level, id, Items.STONE_BRICKS), boats0 = stock(level, id, Items.SPRUCE_BOAT);
        boolean voted = Bridges.voteForTests(level, t.v(), Bridges.Verdict.FOR);
        helper.assertTrue(voted && c.bridge() == Bridges.Stage.BUILDING, "the bridge agreed: " + c.bridge());
        int blocks = Bridges.planSizeForTests(level, t.v());
        int laid = 0;
        for (int k = 0; k < 20 && c.bridge() != Bridges.Stage.OPEN; k++) laid += Bridges.buildForTests(level, t.v(), 400);
        int bricks1 = stock(level, id, Items.STONE_BRICKS);
        // The deck from bank to bank, two above the water, its middle line all stone; an arch's shoulder under it; a pier in the water.
        int deckY = c.surface() + 2;
        int deck = 0, span = 0, shoulders = 0, piers = 0;
        Direction way = c.way(), side = way.getClockWise();
        BlockPos[] ends = bridgeEnds(level, c, deckY);
        if (ends != null) {
            BlockPos a = ends[0];
            span = (int) Math.round(Math.sqrt(ends[0].distSqr(ends[1])));
            for (int p = 0; p <= span; p++) {
                BlockPos d = a.relative(way, p);
                if (level.getBlockState(d).is(Blocks.STONE_BRICKS)) deck++;
                BlockState under = level.getBlockState(d.below());
                if (under.getBlock() instanceof StairBlock && under.getValue(StairBlock.HALF) == Half.TOP) shoulders++;
                if (under.is(Blocks.STONE_BRICKS) && level.getBlockState(d.below(2)).is(Blocks.STONE_BRICKS) && p > 0 && p < span) piers++;
            }
        }
        boolean ferrymanMoved = man.stationTask() != StationTask.FERRY;
        Boat boat = Ferries.boatForTests(level, id);
        int boats1 = stock(level, id, Items.SPRUCE_BOAT);
        boolean told = false, last = false;
        for (Villages.News n : Villages.news(id)) {
            if (n.text().contains("stone bridge") && n.text().contains("opened")) told = true;
            if (n.text().contains("last crossing")) last = true;
        }
        Kit.log("tr04 the bridge: " + blocks + " blocks planned, " + laid + " laid, " + c.bridge() + "; stone bricks " + bricks0 + " -> " + bricks1
            + "; deck " + deck + " of " + (span + 1) + " along its middle, " + shoulders + " arch shoulders, " + piers + " pier tops; the ferry "
            + c.state() + ", boat " + (boat == null ? "gone" : "still there") + ", boats in the stores " + boats0 + " -> " + boats1 + "; the ferryman now "
            + man.stationTask() + "; chronicle " + told + "/" + last);
        for (String p : Transport.page(level, t.v())) Kit.log("tr04 page: " + p);
        helper.assertTrue(c.bridge() == Bridges.Stage.OPEN, "the bridge built and open: " + c.bridge() + " (" + laid + " of " + blocks + ")");
        helper.assertTrue(bricks0 - bricks1 >= blocks / 2, "built of the stores' stone: " + bricks0 + " -> " + bricks1 + " for " + blocks + " blocks");
        helper.assertTrue(span >= c.width() && deck == span + 1, "the deck from bank to bank: " + deck + " of " + (span + 1) + " (the water " + c.width() + ")");
        helper.assertTrue(shoulders >= 2 && piers >= 1, "arches on piers: " + shoulders + " shoulders, " + piers + " piers");
        helper.assertTrue(c.state() == Ferries.State.RETIRED && boat == null && boats1 == boats0 + 1, "the ferry retired, its boat in the stores");
        helper.assertTrue(ferrymanMoved, "the ferryman took another trade: " + man.stationTask());
        helper.assertTrue(told && last, "the chronicle tells it: " + told + "/" + last);
        helper.succeed();
    }

    // ================================================================== tr05: the bridge as the town's great work

    /**
     * The same river and ferry in a town of nine, voters enough for a referendum. The bridge in the ferry's place is put
     * to the whole town as a great work (Referendums, BigWorks), drawn beside the ferry's crossing; everybody votes for
     * it and it is carried; the town's hands build it out of the stores' stone and it is opened as a great work is. Then
     * the ferry rows its last crossing: the boat back in the stores, the ferryman to another trade, the chronicle told.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "tr05_referendum")
    public static void tr05_referendum(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 1168000;
        StationTask[] trades = new StationTask[9];
        java.util.Arrays.fill(trades, StationTask.NONE);
        Town t = town(helper, x, 100, Villages.Age.STONE, trades);
        UUID id = t.village();
        for (VillageFolkEntity f : t.folk()) f.ensurePersona();
        VillageFolkEntity man = t.folk().get(0), farmer = t.folk().get(1);
        river(level, t.heart(), 50);
        fieldOver(farmer, Kit.surface(level, x + 75, Z));
        List<ItemStack> goods = new ArrayList<>(List.of(new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.OAK_PLANKS, 64),
            new ItemStack(Items.STICK, 16), new ItemStack(Items.COPPER_INGOT, 8), new ItemStack(Items.LANTERN, 16), new ItemStack(Items.OAK_BOAT),
            new ItemStack(Items.CRAFTING_TABLE), new ItemStack(Items.BREAD, 64), new ItemStack(Items.STRING, 4), new ItemStack(Items.RED_DYE, 2)));
        for (int i = 0; i < 12; i++) goods.add(new ItemStack(Items.STONE_BRICKS, 64));
        fill(t, goods.toArray(new ItemStack[0]));
        Ferries.weatherForTests(false);
        com.jrpetty.mcassistant.entity.Weather.stormForTests(false);
        Ferries.Crossing c = Ferries.surveyForTests(level, t.v());
        helper.assertTrue(c != null, "a crossing found");
        Ferries.appointForTests(level, t.v(), man);
        Ferries.buildForTests(level, t.v());
        helper.assertTrue(c.state() == Ferries.State.RUNNING && man.stationTask() == StationTask.FERRY, "the ferry running, a ferryman: " + c.state());
        // Put to the whole town, as a great work drawn at the ferry's crossing.
        Bridges.Stage asked = Bridges.askForTests(level, t.v());
        List<String> open = com.jrpetty.mcassistant.entity.Referendums.openForTests(id);
        Kit.log("tr05 put to the town: " + asked + "; the questions " + open);
        helper.assertTrue(asked == Bridges.Stage.ASKED && open.size() == 1 && open.get(0).contains("WORKS")
            && open.get(0).contains("stone bridge") && open.get(0).contains("in place of the ferry"), "the bridge put to a referendum: " + open);
        int q = Integer.parseInt(open.get(0).split(" ")[0]);
        for (VillageFolkEntity f : t.folk()) {
            com.jrpetty.mcassistant.entity.Values.setForTests(f, com.jrpetty.mcassistant.entity.Values.Value.PROGRESS, 95);
            com.jrpetty.mcassistant.entity.Values.setForTests(f, com.jrpetty.mcassistant.entity.Values.Value.TRADITION, 5);
        }
        com.jrpetty.mcassistant.entity.Referendums.castAllForTests(level, t.v(), q);
        int[] count = com.jrpetty.mcassistant.entity.Referendums.countForTests(level, t.v(), q);
        Bridges.Stage voted = Bridges.tickForTests(level, t.v());
        String works = com.jrpetty.mcassistant.entity.BigWorks.stateForTests(id);
        Kit.log("tr05 the count: aye " + count[0] + ", nay " + count[1] + (count[2] == 1 ? ", carried" : ", lost") + "; the bridge " + voted
            + "; the great work " + works + "; " + Bridges.report(level, id));
        helper.assertTrue(count[2] == 1 && voted == Bridges.Stage.BUILDING && works.startsWith("BRIDGE|building"),
            "carried, and the great work begun: " + voted + ", " + works);
        // Beside the ferry: every piece of it within a few blocks of the crossing.
        double midX = (c.bankA().getX() + c.bankB().getX()) / 2.0, midZ = (c.bankA().getZ() + c.bankB().getZ()) / 2.0;
        int far = 0;
        List<BlockPos> pieces = com.jrpetty.mcassistant.entity.BigWorks.piecesForTests(id);
        for (BlockPos p : pieces) if (Math.abs(p.getX() - midX) > 24 || Math.abs(p.getZ() - midZ) > 24) far++;
        helper.assertTrue(!pieces.isEmpty() && far == 0, "the great work drawn beside the ferry: " + far + " of " + pieces.size() + " pieces far off");
        // Built by the town's hands and opened; the ferry's round then sees it.
        int bricks0 = stock(level, id, Items.STONE_BRICKS), boats0 = stock(level, id, Items.SPRUCE_BOAT);
        List<VillageFolkEntity> hands = new ArrayList<>(t.folk().subList(1, t.folk().size()));
        java.util.Map<String, Integer> laid = com.jrpetty.mcassistant.entity.BigWorks.buildForTests(level, t.v(), hands, 400);
        String opened = com.jrpetty.mcassistant.entity.BigWorks.finishAndOpenForTests(level, t.v());
        List<String> done = com.jrpetty.mcassistant.entity.BigWorks.doneForTests(id);
        Bridges.Stage after = Bridges.tickForTests(level, t.v());
        int bricks1 = stock(level, id, Items.STONE_BRICKS), boats1 = stock(level, id, Items.SPRUCE_BOAT);
        boolean told = false;
        for (Villages.News n : Villages.news(id)) if (n.text().contains("nobody needs the ferry")) told = true;
        Kit.log("tr05 built by " + laid + "; " + opened + "; done " + done + "; the bridge " + after + ", the ferry " + c.state()
            + "; stone bricks " + bricks0 + " -> " + bricks1 + "; boats " + boats0 + " -> " + boats1 + "; the ferryman now " + man.stationTask());
        helper.assertTrue(done.contains("BRIDGE") && bricks1 < bricks0, "the great work built of the stores' stone and opened: " + done);
        helper.assertTrue(after == Bridges.Stage.OPEN && c.state() == Ferries.State.RETIRED && boats1 == boats0 + 1,
            "the ferry retired, its boat in the stores: " + after + ", " + c.state());
        helper.assertTrue(man.stationTask() != StationTask.FERRY, "the ferryman took another trade: " + man.stationTask());
        helper.assertTrue(told, "the chronicle tells it");
        helper.succeed();
    }

    /** The bridge's two ends along its middle line, at the deck's height: found by walking the deck's stone from the ferry's side. */
    private static BlockPos[] bridgeEnds(ServerLevel level, Ferries.Crossing c, int deckY) {
        Direction way = c.way(), side = way.getClockWise();
        for (int off : new int[]{ 5, -5, 7, -7, 4, -4, 9, -9 }) {
            BlockPos mid = new BlockPos((c.bankA().getX() + c.bankB().getX()) / 2, deckY, (c.bankA().getZ() + c.bankB().getZ()) / 2).relative(side, off);
            if (!level.getBlockState(mid).is(Blocks.STONE_BRICKS)) continue;
            BlockPos a = mid, b = mid;
            while (level.getBlockState(a.relative(way.getOpposite())).is(Blocks.STONE_BRICKS)) a = a.relative(way.getOpposite());
            while (level.getBlockState(b.relative(way)).is(Blocks.STONE_BRICKS)) b = b.relative(way);
            return new BlockPos[]{ a, b };
        }
        return null;
    }
}
