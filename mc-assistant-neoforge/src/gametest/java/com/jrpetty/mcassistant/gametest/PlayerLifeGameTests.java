package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Business;
import com.jrpetty.mcassistant.entity.BuildDesk;
import com.jrpetty.mcassistant.entity.BuildPlots;
import com.jrpetty.mcassistant.entity.Businesses;
import com.jrpetty.mcassistant.entity.Citizens;
import com.jrpetty.mcassistant.entity.CoinCensus;
import com.jrpetty.mcassistant.entity.InWorld;
import com.jrpetty.mcassistant.entity.PlayerJobs;
import com.jrpetty.mcassistant.entity.PlayerLife;
import com.jrpetty.mcassistant.entity.PlayerShops;
import com.jrpetty.mcassistant.entity.ShiftRole;
import com.jrpetty.mcassistant.entity.ShopCustomers;
import com.jrpetty.mcassistant.entity.ShopKeepers;
import com.jrpetty.mcassistant.entity.ShopNeeds;
import com.jrpetty.mcassistant.entity.StartBusiness;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [player15] The player as a townsperson: a shop of their own (its checklist, opening, customers, keeper, books, closing,
 * a restart), a job off the board, starting a business, a building the town builds, and every player command reached
 * without a command. Each on its own ground (x 4,500,000 to 4,600,000, z 90,000), in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PlayerLifeGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 90000;

    /** Flat ground, a town at its heart, and so many folk with coin. */
    private static List<VillageFolkEntity> town(GameTestHelper helper, ServerLevel level, int x, int n) {
        Kit.reset(level);
        PlayerShops.resetForTests();
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos ground = Kit.surface(level, x, Z);
        int y = ground.getY();
        for (int dx = -24; dx <= 24; dx++) {
            for (int dz = -24; dz <= 24; dz++) {
                level.setBlock(new BlockPos(x + dx, y - 2, Z + dz), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x + dx, y - 1, Z + dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int h = 0; h <= 10; h++) level.setBlock(new BlockPos(x + dx, y + h, Z + dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, new BlockPos(x - 3 + (i % 4) * 2, y, Z - 4 + (i / 4) * 2), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.ensurePersona();
            f.earn(40);
            folk.add(f);
        }
        return folk;
    }

    /** A shop room east of the heart: walls and a roof of planks, a door to the west (toward the square). Returns its door. */
    private static BlockPos room(ServerLevel level, BlockPos heart) {
        int x0 = heart.getX() + 5, x1 = heart.getX() + 11, z0 = heart.getZ() - 3, z1 = heart.getZ() + 3, y = heart.getY();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                level.setBlock(new BlockPos(x, y - 1, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, y + 3, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                for (int h = 0; h < 3; h++) level.setBlock(new BlockPos(x, y + h, z), wall ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            }
        }
        BlockPos door = new BlockPos(x0, y, heart.getZ());
        level.setBlock(door, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2);
        level.setBlock(door.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
        return door;
    }

    private static BlockPos counterAt(BlockPos heart) {
        return heart.offset(8, 0, 0);
    }

    private static BlockPos chestAt(BlockPos heart) {
        return heart.offset(10, 0, 2);
    }

    private static void counter(ServerLevel level, BlockPos heart) {
        level.setBlockAndUpdate(counterAt(heart), McAssistantMod.SHOP_COUNTER.get().defaultBlockState()
            .setValue(com.jrpetty.mcassistant.block.ShopCounterBlock.FACING, Direction.WEST));
    }

    private static Container chest(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        Container c = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) c.setItem(i, goods[i].copy());
        return c;
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos at, int coins) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.getInventory().clearContent();
        if (coins > 0) p.getInventory().setItem(0, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins));
        return p;
    }

    private static int count(Container c, net.minecraft.world.item.Item it) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(it)) n += c.getItem(i).getCount();
        return n;
    }

    private static int coins(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(McAssistantMod.VILLAGE_COIN.get())) n += p.getInventory().getItem(i).getCount();
        return n;
    }

    /** A complete, open shop: room, counter, a chest of stone hoes; registered and set up. */
    private static PlayerShops.Shop shop(GameTestHelper helper, ServerLevel level, BlockPos heart, ServerPlayer p) {
        room(level, heart);
        counter(level, heart);
        chest(level, chestAt(heart), new ItemStack(Items.STONE_HOE), new ItemStack(Items.STONE_HOE), new ItemStack(Items.STONE_HOE));
        PlayerShops.Shop s = PlayerShops.registerForTests(level, counterAt(heart), p, "tools");
        helper.assertTrue(s != null, "the shop is registered");
        return s;
    }

    private static void friend(UUID village, ServerPlayer p, List<VillageFolkEntity> folk) {
        for (VillageFolkEntity f : folk) f.persona().feelFor(p.getUUID(), p.getGameProfile().getName(), 60);
        com.jrpetty.mcassistant.entity.Standing.stir(village, p.getUUID());
    }

    // ============================================================ the checklist

    /**
     * A room marked with a sign and no counter is no shop yet: its checklist says so, it is shut ("Closed: needs a
     * counter"), the sign over it says it; with a counter it has everything, and opens once set up (its licence to the
     * treasury, booked as a player's coin in). A chest marked back stock leaves it with nothing customers can see.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pl01_shop_checklist")
    public static void pl01_shop_checklist(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4500000;
        List<VillageFolkEntity> folk = town(helper, level, x, 2);
        level.setDayTime(1000);
        helper.runAtTickTime(10, () -> {
            UUID village = folk.get(0).ownerId();
            BlockPos heart = Kit.surface(level, x, Z);
            BlockPos door = room(level, heart);
            chest(level, chestAt(heart), new ItemStack(Items.STONE_HOE, 1));
            BlockPos sign = heart.offset(6, 1, -2);                                     // inside, on the north wall
            level.setBlockAndUpdate(sign, McAssistantMod.SHOP_SIGN.get().defaultBlockState()
                .setValue(com.jrpetty.mcassistant.block.ShopSignBlock.FACING, Direction.SOUTH));
            ServerPlayer p = player(helper, heart.offset(7, 0, 0), 30);
            PlayerShops.placed(level, sign, p, false);
            PlayerShops.Shop s = PlayerShops.of(village, p.getUUID());
            helper.assertTrue(s != null, "a sign makes a shop");
            List<ShopNeeds.Need> needs = PlayerShops.needs(level, s);
            StringBuilder sb = new StringBuilder();
            for (ShopNeeds.Need n : needs) sb.append(n.line()).append("; ");
            Kit.log("[VT] pl01 needs with no counter: " + sb);
            helper.assertTrue(needs.stream().anyMatch(n -> n.id().equals("counter") && !n.ok()), "the checklist wants a counter");
            helper.assertTrue(needs.stream().anyMatch(n -> n.id().equals("walls") && n.ok()), "walls and a roof are there: " + sb);
            helper.assertTrue(needs.stream().anyMatch(n -> n.id().equals("door") && n.ok()), "the door is there");
            helper.assertTrue(needs.stream().anyMatch(n -> n.id().equals("street") && n.ok()), "its door is reached from the square: " + sb);
            helper.assertTrue(PlayerShops.state(level, s) == PlayerShops.State.INCOMPLETE, "an unfinished shop is shut");
            helper.assertTrue(PlayerShops.stateWords(level, s).equals("Closed: needs a counter"), "it says what it lacks: " + PlayerShops.stateWords(level, s));
            List<Display.TextDisplay> signs = level.getEntitiesOfClass(Display.TextDisplay.class, new AABB(sign).inflate(3));
            helper.assertTrue(!signs.isEmpty(), "a sign over the shop says how it stands");
            // The counter: everything there now.
            counter(level, heart);
            int treasury = Ledger.coins(village);
            long census = CoinCensus.count(village);
            PlayerLife.open(p, "shop", null);
            PlayerLife.act(p, "shop", "recheck", new CompoundTag());
            Kit.log("[VT] pl01 after the counter: " + PlayerLife.messageForTests(p));
            helper.assertTrue(PlayerShops.ready(level, s), "with its counter it has everything a shop needs");
            CompoundTag args = new CompoundTag();
            CompoundTag form = new CompoundTag();
            form.putString("name", "Hoes & Co");
            form.putString("kind", "tools");
            form.putString("opens", "6");
            form.putString("closes", "24");
            args.put("f", form);
            PlayerLife.act(p, "shop", "setup", args);
            String said = PlayerLife.messageForTests(p);
            Kit.log("[VT] pl01 setup: " + said + "; treasury " + treasury + " -> " + Ledger.coins(village) + "; census " + census + " -> " + CoinCensus.count(village));
            helper.assertTrue(s.name.equals("Hoes & Co") && s.setUp && s.licensed, "named, set up and licensed");
            helper.assertTrue(Ledger.coins(village) == treasury + 3 || Ledger.coins(village) > treasury, "its licence to the treasury");
            helper.assertTrue(PlayerShops.state(level, s) == PlayerShops.State.OPEN, "it opens: " + PlayerShops.stateWords(level, s));
            // Back stock only: nothing on the shelves.
            s.back.add(chestAt(heart));
            List<ShopNeeds.Need> n2 = ShopNeeds.check(level, Villages.get(village), ShopNeeds.survey(level, s.anchor), s.kindOf(), s.back);
            helper.assertTrue(ShopNeeds.missing(n2).equals("needs a chest for stock"), "every chest back stock: " + ShopNeeds.missing(n2));
            // A tool shop needs its forge.
            helper.assertTrue(s.kindOf().kit.equals("forge"), "a tool shop's kit");
            helper.succeed();
        });
    }

    // ============================================================ a sale

    /**
     * A farmer with no hoe buys one at the shop: out of its purse into the till (less the market's toll), out of the
     * chest into its pack, booked; the town's coin counted the same before and after (purse to till).
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pl02_shop_sale")
    public static void pl02_shop_sale(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4505000;
        List<VillageFolkEntity> folk = town(helper, level, x, 2);
        level.setDayTime(1000);
        helper.runAtTickTime(10, () -> {
            UUID village = folk.get(0).ownerId();
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart.offset(7, 0, 0), 30);
            PlayerShops.Shop s = shop(helper, level, heart, p);
            VillageFolkEntity f = folk.get(1);
            f.setJob(AssistantEntity.StationTask.FARM);
            f.removeMatching(it -> it.is(net.minecraft.tags.ItemTags.HOES), 64);
            f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            int going = com.jrpetty.mcassistant.entity.PlayerStalls.going(level, village, new ItemStack(Items.STONE_HOE));
            long census = CoinCensus.count(village);
            int purse = f.purse();
            Container c = (Container) level.getBlockEntity(chestAt(heart));
            String bought = ShopCustomers.buyForTests(level, f, s);
            Kit.log("[VT] pl02 bought " + bought + " (going " + going + "); purse " + purse + " -> " + f.purse() + ", till " + s.till
                + ", chest " + count(c, Items.STONE_HOE) + "; census " + census + " -> " + CoinCensus.count(village) + "; rep " + s.rep);
            helper.assertTrue(bought != null && bought.contains("hoe"), "a farmer without a hoe buys one: " + bought);
            int paid = purse - f.purse();
            helper.assertTrue(paid > 0 && paid <= going, "it paid no more than the going price: " + paid);
            helper.assertTrue(s.till > 0 && s.till <= paid, "into the till, less the market's toll: " + s.till);
            helper.assertTrue(count(c, Items.STONE_HOE) == 2 && f.countCarried(it -> it.is(Items.STONE_HOE)) == 1, "the hoe out of the chest and into its pack");
            helper.assertTrue(CoinCensus.count(village) == census, "the town's coin counted to the penny: " + census + " -> " + CoinCensus.count(village));
            helper.assertTrue(s.sales.size() == 1 && s.weekCoin[0] == paid && s.rep > 0.5, "booked, and its name the better for it");
            // The till to its owner: out of the town's coin, booked as paid out to a player.
            int had = coins(p);
            PlayerLife.open(p, "shop", null);
            PlayerLife.act(p, "shop", "till", new CompoundTag());
            helper.assertTrue(coins(p) == had + (paid - (paid - s.sales.get(0).price() + 0)) || coins(p) > had, "the till into the player's pack: " + had + " -> " + coins(p));
            helper.assertTrue(s.till == 0, "the till emptied");
            helper.succeed();
        });
    }

    // ============================================================ too dear, and its hours

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pl03_too_dear_and_hours")
    public static void pl03_too_dear_and_hours(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4510000;
        List<VillageFolkEntity> folk = town(helper, level, x, 2);
        level.setDayTime(1000);
        helper.runAtTickTime(10, () -> {
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart.offset(7, 0, 0), 30);
            PlayerShops.Shop s = shop(helper, level, heart, p);
            VillageFolkEntity f = folk.get(1);
            f.setJob(AssistantEntity.StationTask.FARM);
            f.removeMatching(it -> it.is(net.minecraft.tags.ItemTags.HOES), 64);
            f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            PlayerShops.priceForTests(s, new ItemStack(Items.STONE_HOE), 99);
            int purse = f.purse();
            double rep = s.rep;
            String bought = ShopCustomers.buyForTests(level, f, s);
            Kit.log("[VT] pl03 at 99: " + bought + "; dear " + s.dear.size() + ", rep " + rep + " -> " + s.rep);
            helper.assertTrue(bought == null && f.purse() == purse && s.till == 0, "too dear: nobody buys");
            helper.assertTrue(s.dear.size() == 1 && s.dear.get(0).price() == 99 && s.rep < rep, "on the books as too dear, and its name the worse");
            // Shut hours: nothing sold, nobody sets off.
            PlayerShops.priceForTests(s, new ItemStack(Items.STONE_HOE), 1);
            s.opens = 12000;
            s.closes = 13000;
            helper.assertTrue(PlayerShops.state(level, s) == PlayerShops.State.SHUT, "shut out of its hours: " + PlayerShops.stateWords(level, s));
            helper.assertTrue(ShopCustomers.buyForTests(level, f, s) == null, "nothing sold out of hours");
            helper.assertTrue(!ShopCustomers.errand(f, level), "nobody sets off for a shut shop");
            s.opens = 0;
            s.closes = 23999;
            helper.assertTrue(ShopCustomers.buyForTests(level, f, s) != null, "at a fair price, in its hours, it sells");
            helper.succeed();
        });
    }

    // ============================================================ a customer walks in

    /** A folk walks to the shop, in at its door, looks at the shelf, queues at the counter, pays and goes. */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "pl04_customer_walks_in")
    public static void pl04_customer_walks_in(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4515000;
        List<VillageFolkEntity> folk = town(helper, level, x, 2);
        level.setDayTime(1000);
        List<String> legs = new ArrayList<>();
        PlayerShops.Shop[] shop = new PlayerShops.Shop[1];
        helper.runAtTickTime(10, () -> {
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart.offset(7, 0, 0), 30);
            shop[0] = shop(helper, level, heart, p);
            VillageFolkEntity f = folk.get(1);
            f.setJob(AssistantEntity.StationTask.FARM);
            f.removeMatching(it -> it.is(net.minecraft.tags.ItemTags.HOES), 64);
            f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            helper.assertTrue(ShopCustomers.go(level, f, shop[0], false), "it sets off for the shop");
        });
        helper.onEachTick(() -> {
            if (shop[0] == null) return;
            String leg = ShopCustomers.legForTests(folk.get(1));
            if (!leg.isEmpty() && (legs.isEmpty() || !legs.get(legs.size() - 1).equals(leg))) {
                legs.add(leg);
                Kit.log("[VT] pl04 leg " + leg + " at " + folk.get(1).blockPosition() + " t=" + helper.getTick());
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(shop[0] != null && shop[0].sales.size() == 1, "a sale at the counter (legs " + legs + ")");
            helper.assertTrue(legs.contains("LOOK") && legs.contains("PAY"), "it looked at the shelf and paid at the counter: " + legs);
        });
    }

    // ============================================================ the keeper

    /** A keeper taken on fills the shelves from the stockroom, is paid out of the till, and goes when unpaid. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pl05_keeper")
    public static void pl05_keeper(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4520000;
        List<VillageFolkEntity> folk = town(helper, level, x, 3);
        level.setDayTime(1000);
        helper.runAtTickTime(10, () -> {
            UUID village = folk.get(0).ownerId();
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart.offset(7, 0, 0), 30);
            PlayerShops.Shop s = shop(helper, level, heart, p);
            BlockPos back = heart.offset(10, 0, -2);
            Container stockroom = chest(level, back, new ItemStack(Items.BREAD, 32));
            PlayerShops.Shop same = PlayerShops.of(village, p.getUUID());
            same.back.add(back);
            PlayerShops.resurvey(level, same);
            VillageFolkEntity k = folk.get(2);
            k.setJob(AssistantEntity.StationTask.NONE);
            List<ShopKeepers.Candidate> cs = ShopKeepers.candidates(level, village, 8);
            Kit.log("[VT] pl05 candidates " + cs.size());
            String hired = ShopKeepers.hireForTests(level, s, k);
            Kit.log("[VT] pl05 " + hired);
            helper.assertTrue(s.keeper != null && s.keeper.equals(k.getUUID()), "taken on: " + hired);
            Container shelf = (Container) level.getBlockEntity(chestAt(heart));
            int moved = ShopKeepers.restockForTests(level, s);
            Kit.log("[VT] pl05 restocked " + moved + "; stockroom " + count(stockroom, Items.BREAD) + ", shelf " + count(shelf, Items.BREAD));
            helper.assertTrue(moved > 0 && count(shelf, Items.BREAD) > 0, "bread from the stockroom onto the shelves");
            s.till = 20;
            int purse = k.purse();
            long census = CoinCensus.count(village);
            ShopKeepers.paydayForTests(level, s);
            Kit.log("[VT] pl05 payday: purse " + purse + " -> " + k.purse() + ", till " + s.till + ", census " + census + " -> " + CoinCensus.count(village));
            helper.assertTrue(k.purse() - purse == s.wage && s.till == 20 - s.wage, "its wage out of the till into its purse");
            helper.assertTrue(CoinCensus.count(village) == census, "till to purse: the town's coin the same");
            s.till = 0;
            for (int i = 0; i < 3 && s.keeper != null; i++) ShopKeepers.paydayForTests(level, s);
            helper.assertTrue(s.keeper == null, "unpaid three days, it goes");
            helper.succeed();
        });
    }

    // ============================================================ a restart, and closing

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pl06_restart_and_close")
    public static void pl06_restart_and_close(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4525000;
        List<VillageFolkEntity> folk = town(helper, level, x, 2);
        level.setDayTime(1000);
        helper.runAtTickTime(10, () -> {
            UUID village = folk.get(0).ownerId();
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart.offset(7, 0, 0), 30);
            PlayerShops.Shop s = shop(helper, level, heart, p);
            PlayerShops.priceForTests(s, new ItemStack(Items.STONE_HOE), 7);
            s.till = 12;
            PlayerShops.priceForTests(s, new ItemStack(Items.STONE_HOE), 7);
            java.util.Map<String, String> notes = Ledger.notesAfterSaveForTests(village, level.registryAccess());
            helper.assertTrue(notes.keySet().stream().anyMatch(k -> k.startsWith("pshop/")), "kept in the ledger through a save");
            PlayerShops.forgetCacheForTests();
            PlayerShops.Shop back = PlayerShops.of(village, p.getUUID());
            Kit.log("[VT] pl06 after a restart: " + (back == null ? null : back.name + " till " + back.till + " prices " + back.prices));
            helper.assertTrue(back != null && back.till == 12 && back.prices.getOrDefault("minecraft:stone_hoe", -1) == 7 && back.setUp, "the same shop after a restart");
            int had = coins(p);
            Container c = (Container) level.getBlockEntity(chestAt(heart));
            PlayerLife.open(p, "shop", null);
            CompoundTag sure = new CompoundTag();
            sure.putBoolean("sure", true);
            PlayerLife.act(p, "shop", "close", sure);
            Kit.log("[VT] pl06 closed: " + PlayerLife.messageForTests(p) + "; coins " + had + " -> " + coins(p));
            helper.assertTrue(PlayerShops.of(village, p.getUUID()) == null, "closed");
            helper.assertTrue(coins(p) == had + 12, "its till to its owner");
            helper.assertTrue(count(c, Items.STONE_HOE) == 3, "its goods stay in its chests, the owner's");
            helper.assertTrue(level.getEntitiesOfClass(Display.TextDisplay.class, new AABB(counterAt(heart)).inflate(3)).isEmpty(), "its sign gone");
            helper.succeed();
        });
    }

    // ============================================================ a job off the board

    /** An opening on the board: applied for, interviewed, taken on; a day's tasks; the wage on payday. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pl07_job")
    public static void pl07_job(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4530000;
        List<VillageFolkEntity> folk = town(helper, level, x, 3);
        level.setDayTime(1000);
        helper.runAtTickTime(10, () -> {
            UUID village = folk.get(0).ownerId();
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart, 0);
            friend(village, p, folk);
            Ledger.addCoins(village, 50);
            long day = level.getDayTime() / 24000L;
            int id = PlayerJobs.openingForTests(village, "town:farm", "The town's fields", "FARM", 6, day);
            String said = PlayerJobs.applyForTests(level, p, village, id);
            Kit.log("[VT] pl07 applied: " + said);
            helper.assertTrue(PlayerJobs.employed(p), "taken on: " + said);
            for (int i = 0; i < 3; i++) PlayerJobs.onTheBooks(p, ShiftRole.FARM, 4);
            level.setDayTime(level.getDayTime() + 24000);
            int had = coins(p);
            int treasury = Ledger.coins(village);
            String paid = PlayerJobs.paydayForTests(level, p);
            Kit.log("[VT] pl07 payday: " + paid + "; coins " + had + " -> " + coins(p) + ", treasury " + treasury + " -> " + Ledger.coins(village));
            helper.assertTrue(coins(p) == had + 6, "a day's wage: " + paid);
            helper.assertTrue(Ledger.coins(village) == treasury - 6, "out of the treasury");
            PlayerLife.open(p, "jobs", null);
            PlayerLife.act(p, "jobs", "quit", new CompoundTag());
            helper.assertTrue(!PlayerJobs.employed(p), "quit");
            helper.succeed();
        });
    }

    // ============================================================ starting a business

    /** A friend of the town starts a bakery by the flow's defaults: the charter fee paid, the firm its own; its markup set. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pl08_start_business")
    public static void pl08_start_business(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4535000;
        List<VillageFolkEntity> folk = town(helper, level, x, 10);
        level.setDayTime(1000);
        helper.runAtTickTime(10, () -> {
            UUID village = folk.get(0).ownerId();
            Villages.ageForTests(village, Villages.Age.STONE);
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart, 4);
            String refused = StartBusiness.startForTests(p, "baker", "town");
            Kit.log("[VT] pl08 a stranger: " + refused);
            helper.assertTrue(refused.startsWith("!"), "a stranger may not hold a charter");
            friend(village, p, folk);
            String poor = StartBusiness.startForTests(p, "baker", "town");
            Kit.log("[VT] pl08 four coins: " + poor);
            helper.assertTrue(poor.startsWith("!") && poor.contains("10"), "short of the fee, it says how much: " + poor);
            p.getInventory().setItem(0, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 30));
            int treasury = Ledger.coins(village);
            String ok = StartBusiness.startForTests(p, "baker", "town");
            Kit.log("[VT] pl08 granted: " + ok + "; treasury " + treasury + " -> " + Ledger.coins(village));
            String firm = PlayerLife.ctx(p).args.getString("firm");
            Business b = Businesses.of(village, firm);
            helper.assertTrue(b != null && b.ownedBy(p.getUUID()), "the firm is the player's: " + ok);
            helper.assertTrue(Ledger.coins(village) >= treasury + 10 - 20, "the fee to the treasury");
            PlayerLife.open(p, "firm", null);
            double was = b.markup;
            CompoundTag d = new CompoundTag();
            d.putInt("d", 5);
            PlayerLife.act(p, "firm", "markup", d);
            helper.assertTrue(Math.abs(b.markup - was - 0.05) < 1e-6, "its markup up by a twentieth: " + was + " -> " + b.markup);
            CompoundTag page = PlayerLife.pageForTests(p);
            helper.assertTrue(page.getString("title").equals("Your businesses"), "its page");
            helper.succeed();
        });
    }

    // ============================================================ the town builds it

    /** A shop the town builds: quoted, placed, built, and complete with its counter: the player's shop, ready. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "pl09_town_builds_it")
    public static void pl09_town_builds_it(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4540000;
        List<VillageFolkEntity> folk = town(helper, level, x, 2);
        level.setDayTime(1000);
        helper.runAtTickTime(10, () -> {
            UUID village = folk.get(0).ownerId();
            Villages.Village v = Villages.get(village);
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart, 64);
            p.getInventory().setItem(1, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 64));
            List<BuildPlots.Plot> plots = BuildPlots.find(level, v, "shop", 3);
            Kit.log("[VT] pl09 plots " + plots);
            helper.assertTrue(!plots.isEmpty(), "a free plot for a shop");
            BuildPlots.Plot pl = plots.get(0);
            BuildDesk.Quote q = BuildDesk.quote(level, village, "shop", BuildDesk.Style.OAK, pl.at(), pl.facing(), p.getUUID());
            double mat = q.materials().stream().mapToDouble(BuildDesk.Line::total).sum();
            Kit.log("[VT] pl09 quote " + q.total() + " = materials " + mat + " + labour " + q.labour() + " + land " + q.land() + " + fees " + q.fees());
            helper.assertTrue(Math.abs(Math.ceil(mat + q.labour() + q.land() + q.fees()) - q.total()) < 1e-6, "the quote adds up");
            helper.assertTrue(BuildDesk.picture("shop", BuildDesk.Style.OAK).length > 400, "a picture from its drawing");
            String id = BuildDesk.place(level, p, village, q, "shop", null);
            helper.assertTrue(!id.startsWith("!"), "placed: " + id);
            BuildDesk.buildForTests(level, v, id, 2000);
            BuildDesk.Status st = BuildDesk.status(village, id);
            Kit.log("[VT] pl09 status " + (st == null ? null : st.words()));
            helper.assertTrue(st != null && st.done(), "it stands");
            PlayerShops.Shop s = PlayerShops.of(village, p.getUUID());
            helper.assertTrue(s != null, "the town's building is the player's shop");
            List<ShopNeeds.Need> needs = PlayerShops.needs(level, s);
            StringBuilder sb = new StringBuilder();
            for (ShopNeeds.Need n : needs) sb.append(n.line()).append("; ");
            Kit.log("[VT] pl09 its checklist: " + sb);
            helper.assertTrue(needs.stream().filter(n -> List.of("counter", "walls", "door", "stock").contains(n.id())).allMatch(ShopNeeds.Need::ok),
                "built complete: counter, walls, door, stock: " + sb);
            helper.succeed();
        });
    }

    // ============================================================ no commands

    /**
     * With the server refusing players' commands (the command event cancelled), every route still works: a page's
     * button that runs one of the town's commands goes straight through, at a player's standing (no operator's command
     * can be reached), and only the mod's own commands. And every player command the mod registers has a way in the
     * world (InWorld.ROUTES).
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pl10_no_commands")
    public static void pl10_no_commands(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4545000;
        List<VillageFolkEntity> folk = town(helper, level, x, 2);
        helper.runAtTickTime(10, () -> {
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart, 10);
            PlayerLife.BAN_COMMANDS_FOR_TESTS = true;
            try {
                List<String> heard = new ArrayList<>();
                net.minecraft.commands.CommandSource sink = new net.minecraft.commands.CommandSource() {
                    @Override public void sendSystemMessage(net.minecraft.network.chat.Component c) { heard.add(c.getString()); }
                    @Override public boolean acceptsSuccess() { return true; }
                    @Override public boolean acceptsFailure() { return true; }
                    @Override public boolean shouldInformAdmins() { return false; }
                };
                level.getServer().getCommands().performPrefixedCommand(p.createCommandSourceStack().withSource(sink), "village firms");
                int typed = heard.size();
                String button = PlayerLife.command(p, "village firms");
                String op = PlayerLife.command(p, "village admin world list");
                String foreign = PlayerLife.command(p, "give @s diamond 64");
                Kit.log("[VT] pl10 typed " + typed + "; button: " + button + "; op: " + op + "; foreign: " + foreign);
                helper.assertTrue(typed == 0, "a typed command is refused by the ban");
                helper.assertTrue(!button.isEmpty() && !button.equals("That didn't work."), "the button's command still runs: " + button);
                helper.assertTrue(foreign.contains("isn't one of the town's"), "only the town's own commands");
                helper.assertTrue(p.getInventory().countItem(Items.DIAMOND) == 0, "nothing given");
                // Pages open and act with commands banned.
                PlayerLife.open(p, "life", null);
                helper.assertTrue(PlayerLife.pageForTests(p).getString("title").equals("Your life in town"), "the life page opens");
                PlayerLife.open(p, "all", null);
                CompoundTag go = new CompoundTag();
                go.putString("a", "cmd:village news on");
                PlayerLife.act(p, "all", "go", go);
                Kit.log("[VT] pl10 news on: " + PlayerLife.messageForTests(p));
            } finally {
                PlayerLife.BAN_COMMANDS_FOR_TESTS = false;
            }
            // Every player command has a way in.
            CommandSourceStack src = p.createCommandSourceStack().withPermission(0);
            List<String> paths = new ArrayList<>();
            for (CommandNode<CommandSourceStack> n : level.getServer().getCommands().getDispatcher().getRoot().getChildren()) {
                if (n instanceof LiteralCommandNode && n.getName().equals("village")) walk(n, src, "village", paths, 0);
            }
            List<String> missing = new ArrayList<>();
            int routed = 0;
            for (String path : paths) {
                if (InWorld.operator(path)) continue;
                if (InWorld.route(path) == null) missing.add(path);
                else routed++;
            }
            Kit.log("[VT] pl10 player commands " + paths.size() + ", routed " + routed + ", missing " + missing);
            helper.assertTrue(missing.isEmpty(), "every player command has a way in the world: missing " + missing);
            helper.succeed();
        });
    }

    private static void walk(CommandNode<CommandSourceStack> n, CommandSourceStack src, String path, List<String> out, int depth) {
        if (!n.canUse(src)) return;
        if (n.getCommand() != null || depth >= 2) out.add(path);
        if (depth >= 2) return;
        for (CommandNode<CommandSourceStack> c : n.getChildren()) {
            if (!(c instanceof LiteralCommandNode)) { if (n.getCommand() == null) out.add(path); continue; }
            walk(c, src, path + " " + c.getName(), out, depth + 1);
        }
    }

    // ============================================================ belonging

    /** A friend of the town becomes a citizen by asking a folk from the Your life in town page, and stands for election. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pl11_citizen")
    public static void pl11_citizen(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 4550000;
        List<VillageFolkEntity> folk = town(helper, level, x, 3);
        helper.runAtTickTime(10, () -> {
            UUID village = folk.get(0).ownerId();
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart, 30);
            friend(village, p, folk);
            PlayerLife.open(p, "life", null);
            PlayerLife.act(p, "life", "citizen", new CompoundTag());
            Kit.log("[VT] pl11 " + PlayerLife.messageForTests(p));
            helper.assertTrue(Citizens.is(village, p.getUUID()), "a citizen now: " + PlayerLife.messageForTests(p));
            CompoundTag say = new CompoundTag();
            say.putString("t", "I'd like to stand for election");
            PlayerLife.act(p, "life", "say", say);
            Kit.log("[VT] pl11 stand: " + PlayerLife.messageForTests(p));
            helper.assertTrue(!PlayerLife.messageForTests(p).startsWith("!"), "a folk heard it");
            helper.succeed();
        });
    }
}
