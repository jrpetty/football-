package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Cafe;
import com.jrpetty.mcassistant.entity.Craftsmanship;
import com.jrpetty.mcassistant.entity.Crafts;
import com.jrpetty.mcassistant.entity.Economy;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Purchases;
import com.jrpetty.mcassistant.entity.Stockroom;
import com.jrpetty.mcassistant.entity.Toolrack;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WatchKit;
import com.jrpetty.mcassistant.entity.Workshop;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [guard-kit] The watch kitted out by the town (WatchKit): in the Stone Age the tailor cuts a guard a suit of
 * leather out of the stores' leather (twenty-four of it, the game's counts) and its purse is not touched; in the
 * Iron Age the smith's iron replaces the leather a piece at a time and the leather goes back into the stores; in
 * the Diamond Age a guard of level nought ends up in diamond with a diamond sword in its hand, made by the smith
 * of the stores' diamonds once the miners have their pick; and with the shop open a guard buys nothing of its
 * kit while the town's books show what it cost.
 *
 * <p>Each on its own ground (x 860000 to 867000, z 66000), in a batch of its own, everything done before the first
 * tick so that nobody's own agenda takes from the stores meanwhile.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class GuardKitGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    /** A village of these trades (the founder first), its stores a storehouse and nothing else, in this age. */
    private record Town(UUID village, Villages.Village v, BlockPos heart, StorehouseBlockEntity store, List<VillageFolkEntity> folk) {}

    private static Town town(GameTestHelper helper, int x, Villages.Age age, StationTask... trades) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        WatchKit.resetForTests();
        Workshop.resetForTests();
        level.setDayTime(2000);
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = Kit.surface(level, x, Z);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < trades.length; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.south(2 + i), 0.0F);
            helper.assertTrue(f != null && (folk.isEmpty() || folk.get(0).ownerId().equals(f.ownerId())), "folk " + i);
            folk.add(f);
        }
        UUID village = folk.get(0).ownerId();
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        onlyTheStorehouse(level, village);
        Villages.ageForTests(village, age);
        for (int i = 0; i < trades.length; i++) folk.get(i).setJob(trades[i]);
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

    private static int count(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        return Market.stock(level, village, what);
    }

    private static void level(VillageFolkEntity f, int lv) {
        f.tradeXpForTests(f.stationTask(), AssistantEntity.xpForLevel(lv));
    }

    private static final EquipmentSlot[] SUIT = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

    /** Nothing on its back. */
    private static void bare(VillageFolkEntity g) {
        for (EquipmentSlot s : SUIT) g.setItemSlot(s, ItemStack.EMPTY);
    }

    /** "iron helmet / leather tunic / ...": what it wears, head to foot. */
    private static String suit(VillageFolkEntity g) {
        List<String> out = new ArrayList<>();
        for (EquipmentSlot s : SUIT) {
            ItemStack w = g.getItemBySlot(s);
            out.add(w.isEmpty() ? "-" : BuiltInRegistries.ITEM.getKey(w.getItem()).getPath());
        }
        return String.join(" / ", out);
    }

    /** How many of its four slots are of this metal. */
    private static int wearing(VillageFolkEntity g, String metal) {
        int n = 0;
        for (EquipmentSlot s : SUIT) if (BuiltInRegistries.ITEM.getKey(g.getItemBySlot(s).getItem()).getPath().startsWith(metal + "_")) n++;
        return n;
    }

    private static String words(List<ItemStack> given) {
        List<String> out = new ArrayList<>();
        for (ItemStack s : given) out.add(s.getCount() + " " + BuiltInRegistries.ITEM.getKey(s.getItem()).getPath());
        return out.isEmpty() ? "nothing" : String.join(", ", out);
    }

    /** Does this piece carry this maker's mark (Craftsmanship.finish)? */
    private static boolean markedBy(ItemStack s, String maker) {
        if (Craftsmanship.gradeOf(s) == null) return false;
        ItemLore lore = s.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        for (var line : lore.lines()) if (line.getString().contains(maker)) return true;
        return false;
    }

    private static boolean newsSays(UUID village, String words) {
        for (Villages.News n : Villages.news(village)) if (n.text().contains(words)) return true;
        return false;
    }

    // ============================================================ the Stone Age: leather

    /**
     * A Stone Age town with a tailor of level five and a suit's worth of leather in the stores, with what the town
     * keeps back besides: the tailor cuts the guard a tunic, trousers, a cap and boots, a piece a turn, and the
     * guard puts each on as it comes out of the stores. The stores' leather is down by exactly twenty-four
     * (8 + 7 + 5 + 4), the rest kept back; every piece carries the tailor's mark; the guard's purse is just as it
     * was; the kit's books have its cost; and its card and the board say what it wears.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "gk01_leather_in_the_stone_age")
    public static void gk01_leather_in_the_stone_age(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 860500, Villages.Age.STONE, StationTask.FARM, StationTask.TAILOR, StationTask.GUARD);
        VillageFolkEntity tailor = t.folk().get(1), guard = t.folk().get(2);
        UUID village = t.village();
        level(tailor, 5);
        bare(guard);
        guard.earn(30);
        // A suit's worth and what the town keeps back besides (a little, and a piece for each book the library wants).
        int kept = WatchKit.leatherKeptForTests(level, t.v());
        fill(t, new ItemStack(Items.LEATHER, 24 + kept));
        int leather0 = count(level, village, s -> s.is(Items.LEATHER)), purse0 = guard.purse();
        List<String> turns = new ArrayList<>();
        for (int i = 0; i < 16 && wearing(guard, "leather") < 4; i++) {
            boolean worked = Crafts.now(tailor, level, t.v());
            List<ItemStack> got = WatchKit.fit(level, t.v(), guard);
            turns.add(i + ":" + worked + "->" + words(got));
        }
        int leather1 = count(level, village, s -> s.is(Items.LEATHER));
        boolean marked = true;
        for (EquipmentSlot s : SUIT) marked &= markedBy(guard.getItemBySlot(s), tailor.displayNameCap());
        String card = WatchKit.cardLine(guard);
        int[] cost = WatchKit.costForTests(village);
        Kit.log("gk01 the tailor (level " + tailor.veteranLevel() + "): turns " + turns + "; made today " + WatchKit.madeToday(level, village)
            + "; the guard wears " + suit(guard) + "; leather " + leather0 + " -> " + leather1 + " (" + kept + " kept back); purse " + purse0 + " -> "
            + guard.purse()
            + ", charged today " + Purchases.spentTodayForTests(guard) + "; the kit's cost " + cost[0] + " coins, " + cost[1] + " pieces; card: " + card
            + "; board: " + WatchKit.boardLine(village));
        helper.assertTrue(guard.getItemBySlot(EquipmentSlot.HEAD).is(Items.LEATHER_HELMET) && guard.getItemBySlot(EquipmentSlot.CHEST).is(Items.LEATHER_CHESTPLATE)
            && guard.getItemBySlot(EquipmentSlot.LEGS).is(Items.LEATHER_LEGGINGS) && guard.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS),
            "the guard is in four pieces of leather: " + suit(guard));
        helper.assertTrue(leather0 - leather1 == 24 && leather1 == kept, "made of the stores' leather, twenty-four of it, the rest kept back: "
            + leather0 + " -> " + leather1);
        helper.assertTrue(marked, "every piece the tailor's work, with its mark");
        helper.assertTrue(guard.purse() == purse0 && Purchases.spentTodayForTests(guard) == 0 && Purchases.balanceForTests(guard) == 0,
            "the guard paid nothing: purse " + purse0 + " -> " + guard.purse());
        helper.assertTrue(cost[1] >= 4 && cost[0] > 0, "the town's books have the kit's cost: " + cost[0] + " coins, " + cost[1] + " pieces");
        helper.assertTrue(card != null && card.contains("leather tunic") && card.contains("issued by the town"), "its card says so: " + card);
        helper.assertTrue("The watch: 1 guard, 1 in leather.".equals(WatchKit.boardLine(village)), "the board: " + WatchKit.boardLine(village));
        helper.succeed();
    }

    // ============================================================ the Iron Age: iron replaces the leather

    /**
     * An Iron Age town with a smith of level twelve, iron and planks in the stores, and a guard in the leather of
     * the Stone Age with a stone sword of its own: turn by turn the smith forges its picks, swords and armour and
     * the guard is fitted after each turn, the iron replacing the leather a piece at a time (a turn with iron and
     * leather on together), every leather piece going back into the stores; at the end a full suit of iron and an
     * iron sword, the purse untouched, and the chronicle tells that the watch went into iron.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "gk02_iron_replaces_the_leather")
    public static void gk02_iron_replaces_the_leather(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 862500, Villages.Age.IRON, StationTask.FARM, StationTask.SMITH, StationTask.GUARD);
        VillageFolkEntity smith = t.folk().get(1), guard = t.folk().get(2);
        UUID village = t.village();
        level(smith, 12);
        guard.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        guard.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
        guard.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
        guard.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
        guard.earn(30);
        fill(t, new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.IRON_INGOT, 64),
            new ItemStack(Items.OAK_PLANKS, 32));
        Predicate<ItemStack> leatherArmour = s -> s.getItem() instanceof ArmorItem && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().startsWith("leather_");
        int iron0 = count(level, village, s -> s.is(Items.IRON_INGOT)), purse0 = guard.purse();
        List<String> turns = new ArrayList<>();
        String was = suit(guard);
        int changes = 0;
        boolean mixed = false;
        for (int i = 0; i < 30 && (wearing(guard, "iron") < 4 || guard.countCarried(s -> s.is(Items.IRON_SWORD)) == 0); i++) {
            boolean worked = Crafts.now(smith, level, t.v());
            List<ItemStack> got = WatchKit.fit(level, t.v(), guard);
            String now = suit(guard);
            if (!now.equals(was)) changes++;
            was = now;
            if (wearing(guard, "iron") > 0 && wearing(guard, "leather") > 0) mixed = true;
            turns.add(i + ":" + worked + "->" + words(got) + " [" + now + "; leather back in the stores " + count(level, village, leatherArmour) + "]");
        }
        int iron1 = count(level, village, s -> s.is(Items.IRON_INGOT)), leatherBack = count(level, village, leatherArmour);
        Kit.log("gk02 the smith (level " + smith.veteranLevel() + "): " + String.join(" | ", turns) + "; iron " + iron0 + " -> " + iron1
            + "; the guard wears " + suit(guard) + ", iron swords " + guard.countCarried(s -> s.is(Items.IRON_SWORD)) + ", stone swords "
            + guard.countCarried(s -> s.is(Items.STONE_SWORD)) + "; leather pieces back in the stores " + leatherBack + "; suit changed " + changes
            + " times, iron and leather together " + mixed + "; purse " + purse0 + " -> " + guard.purse() + "; news " + Villages.news(village)
            + "; board: " + WatchKit.boardLine(village));
        helper.assertTrue(wearing(guard, "iron") == 4, "the guard ends up in a full suit of iron: " + suit(guard));
        helper.assertTrue(guard.countCarried(s -> s.is(Items.IRON_SWORD)) >= 1, "and carries an iron sword");
        helper.assertTrue(mixed && changes >= 4, "piece by piece: iron and leather on together at a turn, the suit changed " + changes + " times");
        helper.assertTrue(leatherBack == 4, "all four leather pieces went back into the stores: " + leatherBack);
        helper.assertTrue(iron1 < iron0, "made of the stores' iron: " + iron0 + " -> " + iron1);
        helper.assertTrue(guard.purse() == purse0 && Purchases.spentTodayForTests(guard) == 0, "the guard paid nothing: " + purse0 + " -> " + guard.purse());
        helper.assertTrue(newsSays(village, "the watch went into iron"), "the chronicle: the watch went into iron");
        helper.assertTrue("The watch: 1 guard, 1 in iron.".equals(WatchKit.boardLine(village)), "the board: " + WatchKit.boardLine(village));
        helper.succeed();
    }

    // ============================================================ the Diamond Age: diamond on a guard of level nought

    /**
     * A Diamond Age town not short of diamonds (sixty-four in the stores, the age asking eight), a smith of level
     * thirty and a miner with only a stone pick: the smith forges the miners' diamond pick first, then the watch's
     * diamond sword, then the chestplate, leggings, helmet and boots, and a guard of level nought ends up wearing
     * all four and wielding the sword (the rank gate kept for the miner). Each piece carries the smith's mark; the
     * stores' diamonds are down by the pieces' worth; the purse is untouched; the chronicle tells of diamond.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "gk03_diamond_for_a_new_guard")
    public static void gk03_diamond_for_a_new_guard(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 864500, Villages.Age.DIAMOND, StationTask.FARM, StationTask.SMITH, StationTask.GUARD, StationTask.MINE);
        VillageFolkEntity smith = t.folk().get(1), guard = t.folk().get(2), miner = t.folk().get(3);
        UUID village = t.village();
        level(smith, 30);
        bare(guard);
        guard.earn(30);
        fill(t, new ItemStack(Items.DIAMOND, 64), new ItemStack(Items.OAK_PLANKS, 32));
        int diamonds0 = count(level, village, s -> s.is(Items.DIAMOND)), purse0 = guard.purse();
        List<String> turns = new ArrayList<>();
        int pickAt = -1, armourAt = -1;
        for (int i = 0; i < 24 && (wearing(guard, "diamond") < 4 || guard.countCarried(s -> s.is(Items.DIAMOND_SWORD)) == 0); i++) {
            boolean worked = Crafts.now(smith, level, t.v());
            if (pickAt < 0 && count(level, village, s -> s.is(Items.DIAMOND_PICKAXE)) > 0) pickAt = i;
            List<ItemStack> got = WatchKit.fit(level, t.v(), guard);
            if (armourAt < 0 && wearing(guard, "diamond") > 0) armourAt = i;
            turns.add(i + ":" + worked + "->" + words(got));
        }
        guard.equipBestWeapon();
        ItemStack hand = guard.getMainHandItem();
        int diamonds1 = count(level, village, s -> s.is(Items.DIAMOND));
        boolean marked = true;
        for (EquipmentSlot s : SUIT) marked &= markedBy(guard.getItemBySlot(s), smith.displayNameCap());
        boolean minerGate = !miner.mayUseTier(new ItemStack(Items.DIAMOND_SWORD));
        Kit.log("gk03 the smith (level " + smith.veteranLevel() + "): " + String.join(" | ", turns) + "; made today " + WatchKit.madeToday(level, village)
            + "; the miners' pick at turn " + pickAt + ", the first diamond armour at turn " + armourAt + "; diamonds " + diamonds0 + " -> " + diamonds1
            + "; the guard (level " + guard.veteranLevel() + ") wears " + suit(guard) + " and wields " + BuiltInRegistries.ITEM.getKey(hand.getItem())
            + "; the miner (level " + miner.veteranLevel() + ") may not wield a diamond sword: " + minerGate + "; purse " + purse0 + " -> " + guard.purse()
            + "; card: " + WatchKit.cardLine(guard) + "; news " + Villages.news(village));
        helper.assertTrue(guard.veteranLevel() == 0, "a guard new to the watch: level " + guard.veteranLevel());
        helper.assertTrue(wearing(guard, "diamond") == 4, "in four pieces of diamond: " + suit(guard));
        helper.assertTrue(hand.is(Items.DIAMOND_SWORD), "wielding the diamond sword, whatever its level: " + hand);
        helper.assertTrue(pickAt >= 0 && armourAt > pickAt, "the miners' diamond pick came first: pick at " + pickAt + ", armour at " + armourAt);
        helper.assertTrue(marked && markedBy(hand, smith.displayNameCap()), "every piece the smith's work, with its mark");
        helper.assertTrue(diamonds0 - diamonds1 >= 26, "made of the stores' diamonds (24 for the suit and 2 for the sword, at least): "
            + diamonds0 + " -> " + diamonds1);
        helper.assertTrue(minerGate, "the rank gate kept for the other trades' tools");
        helper.assertTrue(guard.purse() == purse0 && Purchases.spentTodayForTests(guard) == 0, "the guard paid nothing: " + purse0 + " -> " + guard.purse());
        helper.assertTrue(newsSays(village, "the watch went into diamond"), "the chronicle: the watch went into diamond");
        helper.succeed();
    }

    // ============================================================ the shop open: the guard never pays

    /** The shop's row for this ware in the town's books. */
    private static CompoundTag shopRow(ServerLevel level, UUID village, String id) {
        ListTag sellers = Stockroom.inventoryReport(level, village).getList("sellers", Tag.TAG_COMPOUND);
        for (int i = 0; i < sellers.size(); i++) {
            CompoundTag s = sellers.getCompound(i);
            if (!s.getString("id").equals("shop")) continue;
            ListTag rows = s.getList("wares", Tag.TAG_COMPOUND);
            for (int k = 0; k < rows.size(); k++) if (rows.getCompound(k).getString("id").equals(id)) return rows.getCompound(k);
        }
        return new CompoundTag();
    }

    /**
     * An Iron Age town with its shop open (folk buy their own from now on) and forty coins in a guard's purse: the
     * smith forges the watch's iron; the guard, with no blade, goes to the shop and is sold nothing (the watch's
     * blade is issued); the storehouse's rack issues it a sword free; the shop's round fits it out in iron. Its
     * purse is just as it was and nothing is on its slate, while the shop's books count the pieces gone to the
     * watch, the kit's books and the town's money page show what it cost, and /village watch tells it all.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "gk04_the_town_pays_with_the_shop_open")
    public static void gk04_the_town_pays_with_the_shop_open(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 866500;
        Town t = town(helper, x, Villages.Age.IRON, StationTask.SHOP, StationTask.GUARD, StationTask.SMITH);
        VillageFolkEntity keeper = t.folk().get(0), guard = t.folk().get(1), smith = t.folk().get(2);
        UUID village = t.village();
        BlockPos shopAt = Kit.surface(level, x - 42, Z - 20);
        BuildGoal.stamp(level, "shop", shopAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, "shop", shopAt, Direction.NORTH);
        Villages.forgetStores(village);
        Purchases.openForTests(village, true);
        try {
            level(smith, 12);
            bare(guard);
            for (int i = 0; i < guard.getInventoryItems().size(); i++) {
                if (guard.getInventoryItems().get(i).getItem() instanceof SwordItem) guard.getInventoryItems().set(i, ItemStack.EMPTY);
            }
            if (guard.getMainHandItem().getItem() instanceof SwordItem) guard.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            guard.earn(40);
            fill(t, new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.IRON_INGOT, 64),
                new ItemStack(Items.OAK_PLANKS, 32));
            List<String> made = new ArrayList<>();
            for (int i = 0; i < 30 && (count(level, village, s -> s.is(Items.IRON_SWORD)) < 1
                    || count(level, village, s -> s.is(Items.IRON_CHESTPLATE)) < 1 || made.size() < 12); i++) {
                made.add(Crafts.now(smith, level, t.v()) ? "y" : "n");
            }
            int purse0 = guard.purse(), treasury0 = Ledger.coins(village);
            int swords0 = count(level, village, s -> s.is(Items.IRON_SWORD)), plates0 = count(level, village, s -> s.is(Items.IRON_CHESTPLATE));
            // The shop: a guard with no blade is sold none.
            String bought = Cafe.folkShops(level, t.v(), guard, true);
            int purse1 = guard.purse();
            // The rack: the watch's blade is issued, free.
            ItemStack racked = Purchases.tool(level, t.v(), guard, Toolrack.Tool.SWORD);
            int purse2 = guard.purse();
            // The shop's round: the watch fitted out.
            int put = Workshop.outfit(level, t.v());
            int purse3 = guard.purse();
            int[] cost = WatchKit.costForTests(village);
            String money = Economy.page(level, t.v());
            CompoundTag row = shopRow(level, village, "minecraft:iron_chestplate");
            List<String> log = Workshop.logForTests(village);
            String said = String.join("\n", Kit.command(level, "village watch"));
            Kit.log("gk04 the smith made " + made + "; in the stores iron swords " + swords0 + ", chestplates " + plates0 + "; the shop sold the guard "
                + bought + " (purse " + purse0 + " -> " + purse1 + "); the rack issued " + racked + " (purse " + purse2 + "); the round put " + put
                + " pieces on the watch: " + suit(guard) + " (purse " + purse3 + ", charged today " + Purchases.spentTodayForTests(guard) + ", slate "
                + Purchases.balanceForTests(guard) + "); treasury " + treasury0 + " -> " + Ledger.coins(village) + "; the kit's cost " + cost[0] + " coins, "
                + cost[1] + " pieces; the shop's books " + row + "; the workshop's day " + log + "; the money page: " + money.replace('\n', ' ')
                + "; /village watch: " + said.replace("\n", " | ") + "; keeper " + keeper.displayNameCap());
            helper.assertTrue(swords0 >= 1 && plates0 >= 1, "the smith forged the watch's iron: swords " + swords0 + ", chestplates " + plates0);
            helper.assertTrue(bought == null && purse1 == purse0, "the shop sells a guard no blade: " + bought + ", purse " + purse0 + " -> " + purse1);
            helper.assertTrue(racked.getItem() instanceof SwordItem && purse2 == purse0, "the rack issues the watch's blade free: " + racked);
            helper.assertTrue(put >= 1 && guard.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "the shop's round fits the guard in iron: "
                + suit(guard));
            helper.assertTrue(purse3 == purse0 && Purchases.spentTodayForTests(guard) == 0 && Purchases.balanceForTests(guard) == 0,
                "the guard's purse never paid for its kit: " + purse0 + " -> " + purse3);
            helper.assertTrue(cost[0] > 0 && cost[1] >= 1, "the kit's cost is in the town's books: " + cost[0] + " coins, " + cost[1] + " pieces");
            helper.assertTrue(money.contains("The watch's kit, paid for by the town"), "and on the town's money page");
            helper.assertTrue(row.getInt("soldToday") >= 1, "the shop's books count the chestplate gone to the watch: " + row);
            helper.assertTrue(log.stream().anyMatch(l -> l.contains("|guard|") && l.contains("iron chestplate")), "the workshop's day says so: " + log);
            helper.assertTrue(said.contains(guard.displayNameCap()) && said.contains("What it has cost the town"), "/village watch tells it: " + said);
        } finally {
            Purchases.openForTests(village, null);
        }
        helper.succeed();
    }
}
