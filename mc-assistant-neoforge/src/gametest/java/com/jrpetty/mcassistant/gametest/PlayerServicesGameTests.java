package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.PlayerServices;
import com.jrpetty.mcassistant.entity.Sweepers;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Services for players (entity/PlayerServices): mending at the forge with the stores' metal at the
 * market's price; a map of the town for a citizen; every village side by side (/village top); the
 * bounty the board posts on a busy night; the Lost and Found the sweeper keeps by the storehouse; and
 * the milestones the town writes into its chronicle and celebrates on the square.
 *
 * <p>Each on its own ground (x 440000 to 446000, z 50000), in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PlayerServicesGameTests {

    private static final String EMPTY = "empty";

    /** A chest of the village's stores this far from the heart, holding these. */
    private static Container stores(ServerLevel level, BlockPos heart, int dx, int dz, ItemStack... goods) {
        BlockPos at = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        ZoneChests.mark(level, at);
        Container c = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) c.setItem(i, goods[i].copy());
        c.setChanged();
        return c;
    }

    private static int carried(Player p, Item it) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(it)) n += p.getInventory().getItem(i).getCount();
        return n;
    }

    private static int count(Container c, Item it) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(it)) n += c.getItem(i).getCount();
        return n;
    }

    private static List<ItemStack> maps(Player p) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(Items.FILLED_MAP)) out.add(p.getInventory().getItem(i));
        return out;
    }

    // ============================================================ ps01 mending

    /**
     * The smith mends a worn iron sword with three ingots out of the stores (a quarter of the wear each),
     * and the player pays the market's price for them and the fee, into the treasury; the smelter will not
     * while there is a smith; a diamond sword, with no diamond in the stores, is refused untouched; with the
     * smith gone to the fields, the smelter at its furnace mends a helmet, and a chestplate as far as the
     * last two ingots go.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ps_ps01_mending_at_the_forge")
    public static void ps01_mending_at_the_forge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 440000, z = 50000;
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity smith = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity smelter = VillageFolkSpawnerBlock.raise(level, heart.east(3), 0.0F);
        helper.assertTrue(smith != null && smelter != null && smith.ownerId() != null && smith.ownerId().equals(smelter.ownerId()), "a village of two");
        UUID village = smith.ownerId();
        smith.setJob(StationTask.SMITH);
        smelter.setJob(StationTask.SMELT);
        smith.ensurePersona();
        smelter.ensurePersona();
        stores(level, heart, 6, 6, new ItemStack(Items.IRON_INGOT, 8));
        Villages.forgetStores(village);
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        p.getInventory().setItem(30, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 64));
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.setDamageValue(150);                                       // 250 in all: three quarters' worth of wear
        p.setItemInHand(InteractionHand.MAIN_HAND, sword);
        int iron0 = Market.stock(level, village, s -> s.is(Items.IRON_INGOT));
        String notMine = PlayerServices.repair(smelter, p);
        Kit.log("ps01 the smelter, with a smith in the town: " + notMine);
        helper.assertTrue(p.getMainHandItem().getDamageValue() == 150 && notMine.contains(smith.displayNameCap()),
            "the smelter sends the player to the smith: " + notMine);
        int coins0 = Market.coinsHeld(p), treasury0 = Ledger.coins(village);
        String mended = PlayerServices.repair(smith, p);
        int iron1 = Market.stock(level, village, s -> s.is(Items.IRON_INGOT));
        int paid = coins0 - Market.coinsHeld(p), took = Ledger.coins(village) - treasury0;
        Kit.log("ps01 the smith: " + mended + " (iron " + iron0 + " -> " + iron1 + ", paid " + paid + ", treasury +" + took + ")");
        helper.assertTrue(p.getMainHandItem().getDamageValue() == 0, "the sword is mended: " + p.getMainHandItem().getDamageValue() + " — " + mended);
        helper.assertTrue(iron0 - iron1 == 3, "with three ingots out of the stores, a quarter of the wear each: " + iron0 + " -> " + iron1);
        helper.assertTrue(paid == took && paid >= PlayerServices.REPAIR_FEE + 3, "paid at the market's price and the fee, into the treasury: paid "
            + paid + ", treasury +" + took);
        // A diamond sword, and not a diamond in the stores or the pack: refused, untouched, unpaid.
        ItemStack diamond = new ItemStack(Items.DIAMOND_SWORD);
        diamond.setDamageValue(900);
        p.setItemInHand(InteractionHand.MAIN_HAND, diamond);
        int coins1 = Market.coinsHeld(p);
        String refused = PlayerServices.repair(smith, p);
        Kit.log("ps01 a diamond sword: " + refused);
        helper.assertTrue(p.getMainHandItem().getDamageValue() == 900 && Market.coinsHeld(p) == coins1 && refused.contains("diamond"),
            "no diamond in the stores: refused, and nothing charged: " + refused);
        // No smith: a smelter mends, at its forge.
        smith.setJob(StationTask.FARM);
        level.setBlockAndUpdate(smelter.blockPosition().north(), Blocks.FURNACE.defaultBlockState());
        ItemStack helmet = new ItemStack(Items.IRON_HELMET);
        helmet.setDamageValue(100);                                      // 165 in all: three quarters
        p.setItemInHand(InteractionHand.MAIN_HAND, helmet);
        String bySmelter = PlayerServices.repair(smelter, p);
        int iron2 = Market.stock(level, village, s -> s.is(Items.IRON_INGOT));
        Kit.log("ps01 the smelter at its furnace, no smith: " + bySmelter + " (iron " + iron1 + " -> " + iron2 + ")");
        helper.assertTrue(p.getMainHandItem().getDamageValue() == 0 && iron1 - iron2 == 3, "the smelter mends the helmet at its forge: "
            + p.getMainHandItem().getDamageValue() + ", iron " + iron1 + " -> " + iron2 + " — " + bySmelter);
        // The last two ingots: a chestplate (240, four quarters worn) mended as far as they go.
        ItemStack chest = new ItemStack(Items.IRON_CHESTPLATE);
        chest.setDamageValue(200);
        p.setItemInHand(InteractionHand.MAIN_HAND, chest);
        String partly = PlayerServices.repair(smelter, p);
        int iron3 = Market.stock(level, village, s -> s.is(Items.IRON_INGOT));
        Kit.log("ps01 the last of the iron: " + partly + " (damage " + p.getMainHandItem().getDamageValue() + ", iron " + iron3 + ")");
        helper.assertTrue(iron3 == 0 && p.getMainHandItem().getDamageValue() == 200 - 2 * (240 / 4),
            "mended as far as the stores' iron goes: " + p.getMainHandItem().getDamageValue() + " — " + partly);
        helper.succeed();
    }

    // ============================================================ ps02 a map of the town

    /**
     * A stranger is refused a map; a citizen, with no paper in the stores, is told so; with paper, the
     * storekeeper draws a real filled map centred exactly on the heart, coloured from the ground, on a sheet
     * out of the stores; once a day, and again the next day.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ps_ps02_a_map_of_the_town")
    public static void ps02_a_map_of_the_town(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 440600, z = 50000;
        Kit.hold(level, x, z, 72);
        Kit.prepare(level, x, z, 72);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity keeper = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity farmer = VillageFolkSpawnerBlock.raise(level, heart.west(3), 0.0F);
        helper.assertTrue(keeper != null && farmer != null, "a village of two");
        UUID village = keeper.ownerId();
        Villages.Village v = Villages.get(village);
        keeper.setJob(StationTask.STORE);
        farmer.setJob(StationTask.FARM);
        keeper.ensurePersona();
        farmer.ensurePersona();
        Container box = stores(level, heart, 6, -6, new ItemStack(Items.BREAD, 4));
        Villages.forgetStores(village);
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        String stranger = PlayerServices.townMap(keeper, p);
        Kit.log("ps02 a stranger asks: " + stranger);
        helper.assertTrue(maps(p).isEmpty() && stranger.contains("citizens"), "a stranger is refused: " + stranger);
        Ledger.addCitizen(village, p.getUUID(), p.getName().getString());
        int paper0 = Market.stock(level, village, s -> s.is(Items.PAPER));
        String noPaper = PlayerServices.townMap(keeper, p);
        Kit.log("ps02 a citizen, no paper in the stores (" + paper0 + "): " + noPaper);
        helper.assertTrue(paper0 > 0 || maps(p).isEmpty() && noPaper.contains("paper"), "no paper, no map: " + noPaper);
        box.setItem(5, new ItemStack(Items.PAPER, 3));
        box.setChanged();
        int paper1 = Market.stock(level, village, s -> s.is(Items.PAPER));
        String given = PlayerServices.townMap(keeper, p);
        List<ItemStack> got = maps(p);
        helper.assertTrue(got.size() == 1, "a map: " + given);
        MapItemSavedData data = MapItem.getSavedData(got.get(0), level);
        int filled = 0;
        if (data != null) for (byte b : data.colors) if (b != 0) filled++;
        int paper2 = Market.stock(level, village, s -> s.is(Items.PAPER));
        Kit.log("ps02 the map: " + given + " — centre " + (data == null ? "none" : data.centerX + "," + data.centerZ + " scale " + data.scale)
            + ", heart " + v.centre() + ", " + filled + " pixels filled; paper " + paper1 + " -> " + paper2 + "; named "
            + got.get(0).getHoverName().getString());
        helper.assertTrue(data != null && data.centerX == v.centre().getX() && data.centerZ == v.centre().getZ(),
            "centred on the heart: " + (data == null ? "no data" : data.centerX + "," + data.centerZ) + " vs " + v.centre());
        helper.assertTrue(filled >= 2000, "filled in from the ground: " + filled + " pixels");
        helper.assertTrue(paper1 - paper2 == 1, "on a sheet of the stores' paper: " + paper1 + " -> " + paper2);
        String again = PlayerServices.townMap(keeper, p);
        helper.assertTrue(maps(p).size() == 1 && Market.stock(level, village, s -> s.is(Items.PAPER)) == paper2,
            "one a day: " + again);
        if (!farmer.isElder()) {
            String elsewhere = PlayerServices.townMap(farmer, p);
            helper.assertTrue(maps(p).size() == 1 && elsewhere.contains(keeper.displayNameCap()), "the farmer sends you to the storekeeper: " + elsewhere);
        }
        long now = level.getDayTime();
        level.setDayTime(now + 24000L);
        String tomorrow = PlayerServices.townMap(keeper, p);
        level.setDayTime(now);
        Kit.log("ps02 again today: " + again + " / tomorrow: " + tomorrow);
        helper.assertTrue(maps(p).size() == 2, "and another the next day: " + tomorrow);
        helper.succeed();
    }

    // ============================================================ ps03 /village top

    /** Two villages: /village top lists both, the bigger first though the smaller is the richer, with age, worth and renown. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ps_ps03_village_top")
    public static void ps03_village_top(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int xa = 441200, xb = 441500, z = 50000;
        for (int x : new int[]{ xa, xb }) {
            Kit.hold(level, x, z, 24);
            Kit.prepare(level, x, z, 24);
        }
        BlockPos ha = Kit.surface(level, xa, z), hb = Kit.surface(level, xb, z);
        VillageFolkEntity a1 = VillageFolkSpawnerBlock.raise(level, ha, 0.0F);
        VillageFolkSpawnerBlock.raise(level, ha.east(2), 0.0F);
        VillageFolkSpawnerBlock.raise(level, ha.west(2), 0.0F);
        VillageFolkEntity b1 = VillageFolkSpawnerBlock.raise(level, hb, 0.0F);
        helper.assertTrue(a1 != null && b1 != null && !a1.ownerId().equals(b1.ownerId()), "two villages");
        UUID a = a1.ownerId(), b = b1.ownerId();
        Ledger.addCoins(b, 500);
        List<PlayerServices.Row> rows = PlayerServices.table(level.getServer());
        List<String> said = Kit.command(level, "village top");
        for (String l : said) Kit.log("ps03 " + l);
        helper.assertTrue(rows.size() == 2 && rows.get(0).id().equals(a) && rows.get(1).id().equals(b),
            "the bigger village first: " + rows);
        helper.assertTrue(rows.get(0).folk() == 3 && rows.get(1).folk() == 1 && rows.get(1).treasury() == Ledger.coins(b)
            && rows.get(1).treasury() >= 500 && rows.get(1).wealth() >= rows.get(1).treasury()
            && rows.get(1).wealth() > rows.get(0).wealth(), "its folk, its treasury and its worth (the treasury and the stores): " + rows);
        String nameA = Villages.name(a), nameB = Villages.name(b);
        helper.assertTrue(said.size() == 3 && said.get(1).contains(nameA) && said.get(2).contains(nameB),
            "/village top: a heading and a line a village, biggest first: " + said);
        helper.assertTrue(said.get(1).contains("Age") && said.get(1).contains("worth") && said.get(1).contains("renown"),
            "each with its age, its worth and its renown: " + said.get(1));
        helper.succeed();
    }

    // ============================================================ ps04 a bounty on the night

    /** A monster killed as by this killer, here: the death the event handler hears of. */
    private static void kill(ServerLevel level, Entity killer, double x, double y, double z) {
        Spider s = EntityType.SPIDER.create(level);
        s.moveTo(x, y, z, 0.0F, 0.0F);
        var source = killer instanceof Player p ? level.damageSources().playerAttack(p)
            : level.damageSources().mobAttack((net.minecraft.world.entity.LivingEntity) killer);
        PlayerServices.onDeath(new LivingDeathEvent(s, source));
        s.discard();
    }

    /**
     * Before the watch is busy, a player's kill earns nothing; three kills by the town's folk in the night
     * and the board posts a bounty; a player's kills in the town are paid a coin each out of the treasury,
     * one outside the town nothing, and the night's purse runs out at its cap; by day, nothing.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ps_ps04_a_bounty_on_the_night")
    public static void ps04_a_bounty_on_the_night(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(14000);
        int x = 442200, z = 50000;
        Kit.hold(level, x, z, 32);
        Kit.prepare(level, x, z, 32);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity guard = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(guard != null, "a village");
        UUID village = guard.ownerId();
        guard.setJob(StationTask.GUARD);
        guard.ensurePersona();
        Ledger.addCoins(village, 60);
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        double kx = heart.getX() + 5.5, ky = heart.getY(), kz = heart.getZ() + 4.5;
        kill(level, p, kx, ky, kz);
        helper.assertTrue(!PlayerServices.bountyPosted(level, village) && Market.coinsHeld(p) == 0, "a quiet night: no bounty yet");
        for (int i = 0; i < PlayerServices.BUSY; i++) kill(level, guard, kx - i, ky, kz);
        String board = PlayerServices.boardLine(level, village);
        Kit.log("ps04 the watch busy: posted " + PlayerServices.bountyPosted(level, village) + "; the board: " + board);
        helper.assertTrue(PlayerServices.bountyPosted(level, village) && board != null, "a busy night: the board posts a bounty");
        int treasury0 = Ledger.coins(village);
        kill(level, p, kx, ky, kz);
        kill(level, p, kx + 1, ky, kz);
        int coins = Market.coinsHeld(p);
        Kit.log("ps04 two spiders: " + coins + " coins; treasury " + treasury0 + " -> " + Ledger.coins(village));
        helper.assertTrue(coins == 2 && treasury0 - Ledger.coins(village) == 2, "a coin a spider, out of the treasury: " + coins);
        kill(level, p, heart.getX() + Villages.townReach(village) + 40.5, ky, kz);
        helper.assertTrue(Market.coinsHeld(p) == 2, "nothing for one killed outside the town");
        for (int i = 0; i < PlayerServices.NIGHT_PURSE + 4; i++) kill(level, p, kx, ky, kz + 1);
        int all = Market.coinsHeld(p);
        Kit.log("ps04 the night's purse: " + all + " coins paid, " + PlayerServices.bountyPaidForTests(village) + " booked; board: "
            + PlayerServices.boardLine(level, village));
        helper.assertTrue(all == PlayerServices.NIGHT_PURSE && PlayerServices.bountyPaidForTests(village) == PlayerServices.NIGHT_PURSE,
            "paid up to the night's purse and no more: " + all);
        long now = level.getDayTime();
        level.setDayTime(now + 12000L);                                   // the next morning
        kill(level, p, kx, ky, kz);
        level.setDayTime(now);
        helper.assertTrue(Market.coinsHeld(p) == all, "and nothing by day");
        helper.succeed();
    }

    // ============================================================ ps05 the Lost and Found

    /** A Village Storehouse (twenty-seven units, the door to the south) this far from the heart. */
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

    /** A heap lying on the ground this far from the heart, thrown by this player. */
    private static ItemEntity lay(ServerLevel level, BlockPos heart, int dx, int dz, ItemStack what, @Nullable Entity thrower) {
        BlockPos at = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        ItemEntity e = new ItemEntity(level, at.getX() + 0.5, at.getY() + 0.05, at.getZ() + 0.5, what, 0.0, 0.0, 0.0);
        if (thrower != null) e.setThrower(thrower);
        level.addFreshEntity(e);
        return e;
    }

    /** As though this heap had lain this long. */
    private static void age(ItemEntity e, int ticks) {
        CompoundTag t = e.saveWithoutId(new CompoundTag());
        t.putShort("Age", (short) ticks);
        e.load(t);
    }

    /**
     * A player's emeralds, fresh, are left; left lying two minutes, the sweeper takes them into a Lost and
     * Found it sets down by the storehouse (the stores' chest), kept for the player and nobody else; opening
     * the chest hands them back, plain emeralds again; the storekeeper hands back the next thing asked for;
     * and what nobody comes for in three days goes into the stores.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ps_ps05_the_lost_and_found")
    public static void ps05_the_lost_and_found(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 442800, z = 50000;
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity sweeper = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(sweeper != null, "a village");
        UUID village = sweeper.ownerId();
        sweeper.setJob(StationTask.STORE);
        sweeper.ensurePersona();
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        store.setItem(0, new ItemStack(Items.CHEST, 1));
        store.setChanged();
        Villages.forgetStores(village);
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemEntity thrown = lay(level, heart, -6, 6, new ItemStack(Items.EMERALD, 4), p);
        age(thrown, 200);
        boolean fresh = Sweepers.mayTakeForTests(level, village, thrown);
        age(thrown, PlayerServices.LOST_AFTER + 600);
        boolean lying = Sweepers.mayTakeForTests(level, village, thrown);
        int chests0 = Market.stock(level, village, s -> s.is(Items.CHEST));
        Kit.log("ps05 the emeralds: player's " + Sweepers.playersOwn(thrown) + ", owner " + PlayerServices.ownerOf(thrown)
            + " (" + p.getUUID() + "); fresh " + fresh + ", left lying " + lying + "; chests in the stores " + chests0);
        helper.assertTrue(!fresh, "a player's drop, fresh: left where it lies");
        helper.assertTrue(lying, "left lying two minutes, with a Lost and Found to be had: the broom takes it");
        int in = Sweepers.sweepLostForTests(sweeper, level, village, thrown);
        BlockPos lnf = PlayerServices.lostAndFoundAt(level, village);
        Container box = lnf != null && level.getBlockEntity(lnf) instanceof Container c ? c : null;
        Kit.log("ps05 swept in " + in + "; the Lost and Found at " + lnf + " (" + (lnf == null ? "" : level.getBlockState(lnf)) + "); "
            + PlayerServices.lostAndFoundLine(level, village) + "; chests in the stores " + Market.stock(level, village, s -> s.is(Items.CHEST)));
        helper.assertTrue(in == 4 && !thrown.isAlive(), "the sweeper takes the emeralds up: " + in);
        helper.assertTrue(box != null && level.getBlockEntity(lnf) instanceof ChestBlockEntity c && c.getCustomName() != null
            && c.getCustomName().getString().equals(PlayerServices.LOST_AND_FOUND), "a Lost and Found set down by the storehouse: " + lnf);
        helper.assertTrue(lnf.distSqr(store.getBlockPos()) <= 12 * 12, "by the storehouse: " + lnf + " / " + store.getBlockPos());
        helper.assertTrue(count(box, Items.EMERALD) == 4 && Market.stock(level, village, s -> s.is(Items.EMERALD)) == 0,
            "the emeralds in it, not in the stores: " + count(box, Items.EMERALD));
        helper.assertTrue(Market.stock(level, village, s -> s.is(Items.CHEST)) == chests0 - 1, "of a chest out of the stores");
        // Somebody else: nothing of theirs, and nothing of anybody else's for them.
        Player other = helper.makeMockPlayer(GameType.SURVIVAL);
        helper.assertTrue(PlayerServices.reclaim(level, village, other).isEmpty() && count(box, Items.EMERALD) == 4,
            "another player gets none of it");
        // Opening it: the emeralds back, plain emeralds again.
        PlayerInteractEvent.RightClickBlock open = new PlayerInteractEvent.RightClickBlock(p, InteractionHand.MAIN_HAND, lnf,
            new BlockHitResult(Vec3.atCenterOf(lnf), Direction.UP, lnf, false));
        PlayerServices.onUseBlock(open);
        int back = carried(p, Items.EMERALD);
        boolean plain = false;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(Items.EMERALD)) plain = ItemStack.isSameItemSameComponents(s, new ItemStack(Items.EMERALD));
        }
        Kit.log("ps05 opened: cancelled " + open.isCanceled() + ", " + back + " emeralds back, plain " + plain);
        helper.assertTrue(open.isCanceled() && back == 4 && plain && count(box, Items.EMERALD) == 0,
            "opening the Lost and Found hands the emeralds back, as they were: " + back);
        // The storekeeper hands back the next.
        ItemEntity bread = lay(level, heart, -8, 4, new ItemStack(Items.BREAD, 3), p);
        age(bread, PlayerServices.LOST_AFTER + 600);
        int in2 = Sweepers.sweepLostForTests(sweeper, level, village, bread);
        int bread0 = carried(p, Items.BREAD);
        String asked = PlayerServices.lostAndFound(sweeper, p);
        Kit.log("ps05 the storekeeper asked: " + asked + " (" + in2 + " in, " + bread0 + " -> " + carried(p, Items.BREAD) + ")");
        helper.assertTrue(in2 == 3 && carried(p, Items.BREAD) - bread0 == 3, "the storekeeper hands it back: " + asked);
        // Three days and nobody came: into the stores.
        ItemEntity string = lay(level, heart, -4, 9, new ItemStack(Items.STRING, 5), p);
        age(string, PlayerServices.LOST_AFTER + 600);
        int in3 = Sweepers.sweepLostForTests(sweeper, level, village, string);
        int strings0 = Market.stock(level, village, s -> s.is(Items.STRING));
        long now = level.getDayTime();
        level.setDayTime(now + 24000L * (PlayerServices.KEEP_DAYS + 1));
        int moved = PlayerServices.expireForTests(level, village);
        level.setDayTime(now);
        int strings1 = Market.stock(level, village, s -> s.is(Items.STRING));
        Kit.log("ps05 three days on: " + in3 + " in, " + moved + " into the stores (" + strings0 + " -> " + strings1 + "), left " + count(box, Items.STRING));
        helper.assertTrue(in3 == 5 && moved == 5 && strings1 - strings0 == 5 && count(box, Items.STRING) == 0,
            "what nobody came for goes into the stores: " + moved);
        helper.succeed();
    }

    // ============================================================ ps06 milestones

    /**
     * The first look notes where the town stands and celebrates nothing; then the first diamond, a new age
     * and twenty-five folk each go into the chronicle as a milestone, with fireworks of the stores' paper and
     * gunpowder over the square; and none twice.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ps_ps06_milestones")
    public static void ps06_milestones(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 443400, z = 50000;
        Kit.hold(level, x, z, 32);
        Kit.prepare(level, x, z, 32);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity folk = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(folk != null, "a village");
        UUID village = folk.ownerId();
        Container box = stores(level, heart, 6, 6, new ItemStack(Items.PAPER, 10), new ItemStack(Items.GUNPOWDER, 10));
        Villages.forgetStores(village);
        List<String> first = PlayerServices.milestonesForTests(level, village, -1);
        helper.assertTrue(first.isEmpty() && PlayerServices.milestones(village).contains("seen"), "the first look celebrates nothing: " + first);
        box.setItem(2, new ItemStack(Items.DIAMOND));
        box.setChanged();
        List<String> diamond = PlayerServices.milestonesForTests(level, village, -1);
        int paper = count(box, Items.PAPER), powder = count(box, Items.GUNPOWDER);
        Kit.log("ps06 the first diamond: " + diamond + "; paper " + paper + ", gunpowder " + powder);
        helper.assertTrue(diamond.size() == 1 && diamond.get(0).contains("diamond"), "the first diamond is a milestone: " + diamond);
        helper.assertTrue(paper == 10 - 3 && powder == 10 - 3, "three rockets of the stores' paper and gunpowder: " + paper + ", " + powder);
        helper.assertTrue(PlayerServices.milestonesForTests(level, village, -1).isEmpty() && count(box, Items.PAPER) == paper, "and not twice");
        Villages.ageForTests(village, Villages.Age.STONE);
        List<String> age = PlayerServices.milestonesForTests(level, village, -1);
        List<String> folk25 = PlayerServices.milestonesForTests(level, village, 25);
        List<String> chronicle = new ArrayList<>();
        for (Chronicle.Entry e : Chronicle.of(village)) chronicle.add(e.text());
        Kit.log("ps06 the Stone Age: " + age + "; twenty-five folk: " + folk25 + "; the chronicle: " + chronicle);
        helper.assertTrue(age.size() == 1 && age.get(0).contains("Stone Age"), "a new age is a milestone: " + age);
        helper.assertTrue(folk25.size() == 1 && folk25.get(0).contains("twenty-five"), "a town of twenty-five is a milestone: " + folk25);
        boolean written = chronicle.stream().anyMatch(l -> l.startsWith("a milestone") && l.contains("diamond"))
            && chronicle.stream().anyMatch(l -> l.startsWith("a milestone") && l.contains("Stone Age"))
            && chronicle.stream().anyMatch(l -> l.startsWith("a milestone") && l.contains("twenty-five"));
        helper.assertTrue(written, "each written into the chronicle: " + chronicle);
        helper.runAfterDelay(2, () -> {
            int rockets = level.getEntitiesOfClass(FireworkRocketEntity.class, new AABB(heart).inflate(12, 24, 12)).size();
            Kit.log("ps06 rockets over the square: " + rockets + "; paper left " + count(box, Items.PAPER));
            helper.assertTrue(rockets >= 1, "fireworks over the square: " + rockets);
            helper.succeed();
        });
    }
}
