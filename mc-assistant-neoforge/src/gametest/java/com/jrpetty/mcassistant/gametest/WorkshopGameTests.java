package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Bench;
import com.jrpetty.mcassistant.entity.Crafts;
import com.jrpetty.mcassistant.entity.Economy;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Stockroom;
import com.jrpetty.mcassistant.entity.Storekeeping;
import com.jrpetty.mcassistant.entity.Tiers;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Workshop;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The shop's workshop (Workshop, Tiers): a hand at the shop's bench makes a stone sword of the stores'
 * cobblestone and planks in the Stone Age, the whole way and booked; an iron chestplate is refused in the
 * Stone Age and made of eight iron ingots in the Iron Age; a pick is made through the sticks and the planks
 * a log is sawn into; the builders' timber is never touched; a guard is fitted out of the shop's stock; a
 * netherite sword is made at the smithing table in the Nether Age and not before; and what wants firing
 * goes to the smelter.
 *
 * <p>Each on its own ground (x 350000 to 357000, z 50000), in a batch of its own, all of it before the first
 * tick so that nobody's own agenda takes from the stores meanwhile.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class WorkshopGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 50000;

    /** A village of a keeper and so many more, its stores a storehouse and nothing else, its shop built
     *  (out of the store area, so its chests and casks are no part of the stores), in this age. */
    private record Town(UUID village, Villages.Village v, BlockPos heart, StorehouseBlockEntity store, VillageFolkEntity keeper,
                        List<VillageFolkEntity> more) {}

    private static Town town(GameTestHelper helper, int x, int others, Villages.Age age) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity keeper = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(keeper != null, "a village");
        UUID village = keeper.ownerId();
        List<VillageFolkEntity> more = new ArrayList<>();
        for (int i = 0; i < others; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart.south(2 + i), 0.0F);
            helper.assertTrue(f != null && village.equals(f.ownerId()), "folk " + i);
            more.add(f);
        }
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        onlyTheStorehouse(level, village);
        BlockPos shopAt = Kit.surface(level, x - 42, Z - 20);
        BuildGoal.stamp(level, "shop", shopAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, "shop", shopAt, Direction.NORTH);
        Villages.forgetStores(village);
        Villages.ageForTests(village, age);
        keeper.setJob(StationTask.SHOP);
        return new Town(village, Villages.get(village), heart, store, keeper, more);
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

    /** Timber in half-planks: a log is eight, a plank two, a stick one. */
    private static int timber(ServerLevel level, UUID village) {
        return count(level, village, s -> s.is(ItemTags.LOGS)) * 8 + count(level, village, s -> s.is(ItemTags.PLANKS)) * 2
            + count(level, village, s -> s.is(Items.STICK));
    }

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

    private static void level(VillageFolkEntity f, int lv) {
        f.tradeXpForTests(f.stationTask(), AssistantEntity.xpForLevel(lv));
    }

    // ============================================================ a hand makes a stone sword

    /**
     * A Stone Age town with a shop: the shop takes on the hand it can spare (one between trades) and that
     * hand, at the shop's bench, makes the stone sword the storehouse's rack wants for the watch, of the
     * stores' cobblestone and planks — exactly two cobblestone, a plank's worth of sticks (the rest back),
     * the sword into the stores — booked in the storehouse's books, the day's production, the shop's books
     * and the workshop's own, and on the hand's card as what it is making.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ws01_hand_makes_a_stone_sword")
    public static void ws01_hand_makes_a_stone_sword(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 350500, 2, Villages.Age.STONE);
        VillageFolkEntity hand = t.more().get(0), guard = t.more().get(1);
        guard.setJob(StationTask.GUARD);
        hand.setJob(StationTask.NONE);
        fill(t, new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64),
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.CRAFTING_TABLE));
        UUID village = t.village();
        // The shop's round: its order book worked out, and a hand taken on.
        Workshop.tick(level, t.v());
        Kit.log("ws01 the shop's staff: keeper " + (Workshop.keeper(village) == null ? "none" : Workshop.keeper(village).displayNameCap())
            + ", hands " + Workshop.hands(village).size() + " (wants " + Workshop.handsWanted(village) + "); the book's top: " + Workshop.topLine(level, t.v()));
        helper.assertTrue(Workshop.keeper(village) == t.keeper(), "the shopkeeper keeps the shop");
        helper.assertTrue(Workshop.isHand(hand) && hand.stationTask() == StationTask.SHOP,
            "the hand between trades is taken on at the shop's bench: " + hand.stationTask() + ", " + hand.getTags());
        helper.assertTrue(Workshop.need(level, village, "minecraft:stone_sword") >= 1, "a stone sword is on the order book, for the rack");
        int cobble0 = count(level, village, s -> s.is(Items.COBBLESTONE)), wood0 = timber(level, village);
        int planks0 = count(level, village, s -> s.is(ItemTags.PLANKS));
        int[] storeOut0 = Storekeeping.itemForTests(level, village, new ItemStack(Items.COBBLESTONE));
        boolean worked = Crafts.now(hand, level, t.v());
        int cobble1 = count(level, village, s -> s.is(Items.COBBLESTONE)), wood1 = timber(level, village);
        int planks1 = count(level, village, s -> s.is(ItemTags.PLANKS)), sticks1 = count(level, village, s -> s.is(Items.STICK));
        int swords = count(level, village, s -> s.is(Items.STONE_SWORD));
        int[] made = Economy.todayForTests(village, "stone_sword"), used = Economy.todayForTests(village, "cobblestone");
        int[] storeOut1 = Storekeeping.itemForTests(level, village, new ItemStack(Items.COBBLESTONE));
        int[] storeIn = Storekeeping.itemForTests(level, village, new ItemStack(Items.STONE_SWORD));
        CompoundTag row = shopRow(level, village, "minecraft:stone_sword");
        List<String> log = Workshop.logForTests(village);
        String doing = Workshop.doing(hand), card = FolkTalk.card(hand);
        Kit.log("ws01 the hand's piece: " + worked + "; cobblestone " + cobble0 + " -> " + cobble1 + ", planks " + planks0 + " -> " + planks1
            + ", sticks " + sticks1 + ", timber " + wood0 + " -> " + wood1 + " half-planks; stone swords " + swords + "; made " + made[0]
            + ", cobblestone used " + used[1] + "; the storehouse's books: cobblestone out " + (storeOut1[1] - storeOut0[1])
            + ", sword in " + storeIn[0] + "; the shop's books " + row + "; the workshop's day " + log + "; doing: " + doing
            + "; now: " + FolkTalk.nowDoing(hand) + "; card: " + card.replace('\n', ' '));
        helper.assertTrue(worked && swords == 1, "a stone sword in the shop's stock: " + swords);
        helper.assertTrue(cobble0 - cobble1 == 2, "two cobblestone out of the stores, no more: " + cobble0 + " -> " + cobble1);
        helper.assertTrue(wood1 < wood0 && planks0 - planks1 == 2 && sticks1 == 3,
            "the timber down by a plank's worth of sticks, the three sticks it did not use back in the stores");
        helper.assertTrue(made[0] >= 1 && used[1] == 2, "the day's production: a stone sword made, two cobblestone used");
        helper.assertTrue(storeOut1[1] - storeOut0[1] == 2 && storeIn[0] >= 1, "and the storehouse's books: the cobblestone out, the sword in");
        helper.assertTrue(row.getInt("madeToday") >= 1 && row.getString("how").contains("stick"), "the shop's books: made today, through the sticks");
        helper.assertTrue(log.stream().anyMatch(l -> l.startsWith(hand.displayNameCap() + "|hand|a stone sword")),
            "the workshop's day: the hand made it");
        helper.assertTrue(doing != null && doing.contains("stone sword for the shop"), "its card: making a stone sword for the shop: " + doing);
        helper.assertTrue(card.contains("Shop hand") && card.contains("Making"), "and says it is the shop's hand");
        helper.succeed();
    }

    // ============================================================ the age's say

    /**
     * An iron chestplate is the Iron Age's work: in the Stone Age a player's order for one is refused and the
     * storekeeper will not make one to order, though the stores hold the iron; in the Iron Age the order goes
     * on the workshop's book and a hand makes it of eight iron ingots, and no more.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ws02_iron_chestplate_waits_for_iron")
    public static void ws02_iron_chestplate_waits_for_iron(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 351500, 1, Villages.Age.STONE);
        VillageFolkEntity hand = t.more().get(0);
        Workshop.appointForTests(hand);
        level(hand, 12);
        UUID village = t.village();
        fill(t, new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.OAK_PLANKS, 64),
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64),
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.CRAFTING_TABLE));
        helper.assertTrue(Tiers.of(level, Items.IRON_CHESTPLATE) == Villages.Age.IRON && Tiers.of(level, Items.STONE_SWORD) == Villages.Age.STONE
            && Tiers.of(level, Items.WOODEN_PICKAXE) == Villages.Age.WOOD && Tiers.of(level, Items.DIAMOND_SWORD) == Villages.Age.DIAMOND
            && Tiers.of(level, Items.SHIELD) == Villages.Age.IRON && Tiers.of(level, Items.LEATHER_CHESTPLATE) == Villages.Age.STONE,
            "the ages of things: " + Tiers.of(level, Items.IRON_CHESTPLATE) + ", " + Tiers.of(level, Items.STONE_SWORD) + ", "
                + Tiers.of(level, Items.WOODEN_PICKAXE) + ", " + Tiers.of(level, Items.DIAMOND_SWORD) + ", " + Tiers.of(level, Items.SHIELD)
                + ", " + Tiers.of(level, Items.LEATHER_CHESTPLATE));
        String refused = Workshop.order(level, t.v(), Items.IRON_CHESTPLATE, 1, "Tester");
        Stockroom.Order toOrder = Stockroom.makeToOrder(level, t.v(), t.keeper(), Items.IRON_CHESTPLATE, 1);
        int iron0 = count(level, village, s -> s.is(Items.IRON_INGOT));
        for (int i = 0; i < 3; i++) Crafts.now(hand, level, t.v());
        int plates = count(level, village, s -> s.is(Items.IRON_CHESTPLATE)), iron1 = count(level, village, s -> s.is(Items.IRON_INGOT));
        Kit.log("ws02 the Stone Age: the order: " + refused + " / to order at the stores: " + toOrder.cannot() + "; iron " + iron0 + " -> "
            + iron1 + ", chestplates " + plates + "; next age: " + Tiers.nextAge(level, Villages.Age.STONE, 6));
        helper.assertTrue(refused.contains("Iron Age") && !refused.startsWith("On the workshop's book"), "refused in the Stone Age: " + refused);
        helper.assertTrue(toOrder.made() == 0 && toOrder.cannot().contains("Iron Age"), "nor made to order at the stores: " + toOrder.cannot());
        helper.assertTrue(plates == 0 && iron1 == iron0, "and not a bar of the iron touched for it");
        // The Iron Age.
        Villages.ageForTests(village, Villages.Age.IRON);
        Villages.forgetStock();
        String taken = Workshop.order(level, t.v(), Items.IRON_CHESTPLATE, 1, "Tester");
        Workshop.lookForTests(level, t.v());
        int iron2 = count(level, village, s -> s.is(Items.IRON_INGOT));
        boolean worked = Crafts.now(hand, level, t.v());
        int plates2 = count(level, village, s -> s.is(Items.IRON_CHESTPLATE)), iron3 = count(level, village, s -> s.is(Items.IRON_INGOT));
        Kit.log("ws02 the Iron Age: " + taken + "; the hand's piece " + worked + ": chestplates " + plates2 + ", iron " + iron2 + " -> " + iron3
            + "; the workshop's day " + Workshop.logForTests(village));
        helper.assertTrue(taken.startsWith("On the workshop's book"), "the order taken in the Iron Age: " + taken);
        helper.assertTrue(plates2 == 1 && iron2 - iron3 == 8, "an iron chestplate made of eight iron ingots: " + plates2 + ", " + (iron2 - iron3));
        helper.succeed();
    }

    // ============================================================ the whole way

    /**
     * Nothing in the stores but logs and cobblestone, and a stone pickaxe on order: the hand saws a log into
     * planks, cuts sticks of two of them and makes the pick, and the planks and sticks left over go back.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ws03_made_through_the_sticks")
    public static void ws03_made_through_the_sticks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 352500, 1, Villages.Age.STONE);
        VillageFolkEntity hand = t.more().get(0);
        Workshop.appointForTests(hand);
        UUID village = t.village();
        fill(t, new ItemStack(Items.OAK_LOG, 20), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64),
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.CRAFTING_TABLE));
        String said = Workshop.order(level, t.v(), Items.STONE_PICKAXE, 1, "Tester");
        Workshop.lookForTests(level, t.v());
        Bench.Plan plan = Bench.plan(level, t.v(), Items.STONE_PICKAXE, 1, Bench.handOf(level, t.v(), hand, "shop"));
        int logs0 = count(level, village, s -> s.is(ItemTags.LOGS)), cobble0 = count(level, village, s -> s.is(Items.COBBLESTONE));
        boolean worked = Crafts.now(hand, level, t.v());
        int picks = count(level, village, s -> s.is(Items.STONE_PICKAXE)), logs1 = count(level, village, s -> s.is(ItemTags.LOGS));
        int planks = count(level, village, s -> s.is(ItemTags.PLANKS)), sticks = count(level, village, s -> s.is(Items.STICK));
        int cobble1 = count(level, village, s -> s.is(Items.COBBLESTONE));
        CompoundTag row = shopRow(level, village, "minecraft:stone_pickaxe");
        Kit.log("ws03 " + said + "; the plan: " + plan.chain() + " (" + plan.steps.size() + " steps); the piece " + worked + ": picks " + picks
            + ", logs " + logs0 + " -> " + logs1 + ", planks " + planks + ", sticks " + sticks + ", cobblestone " + cobble0 + " -> " + cobble1
            + "; the shop's books: " + row.getString("how"));
        helper.assertTrue(plan.ok() && plan.steps.size() == 3 && plan.chain().contains("planks") && plan.chain().contains("sticks"),
            "the whole way: a log into planks, planks into sticks, and the pick: " + plan.chain());
        helper.assertTrue(worked && picks == 1, "a stone pickaxe made");
        helper.assertTrue(logs0 - logs1 == 1 && planks == 2 && sticks == 2 && cobble0 - cobble1 == 3,
            "one log sawn, the two planks and two sticks it did not use back, three cobblestone");
        helper.assertTrue(row.getString("how").contains("stick"), "and the shop's books say how: " + row.getString("how"));
        helper.succeed();
    }

    // ============================================================ the builders' timber

    /**
     * The stores hold the builders' timber and no more (sixteen logs, forty-eight planks): a stone sword the
     * rack wants and a chest a player ordered are both short of it, the hand makes neither, and not a plank
     * or a log is touched; the books say whose timber it is.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ws04_builders_timber_kept")
    public static void ws04_builders_timber_kept(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 353500, 2, Villages.Age.STONE);
        VillageFolkEntity hand = t.more().get(0), guard = t.more().get(1);
        Workshop.appointForTests(hand);
        guard.setJob(StationTask.GUARD);
        UUID village = t.village();
        fill(t, new ItemStack(Items.OAK_LOG, 16), new ItemStack(Items.OAK_PLANKS, 48), new ItemStack(Items.COBBLESTONE, 64),
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64),
            new ItemStack(Items.CRAFTING_TABLE));
        Workshop.order(level, t.v(), Items.CHEST, 1, "Tester");
        Workshop.lookForTests(level, t.v());
        int logs0 = count(level, village, s -> s.is(ItemTags.LOGS)), planks0 = count(level, village, s -> s.is(ItemTags.PLANKS));
        List<Boolean> worked = new ArrayList<>();
        for (int i = 0; i < 5; i++) worked.add(Crafts.now(hand, level, t.v()));
        int logs1 = count(level, village, s -> s.is(ItemTags.LOGS)), planks1 = count(level, village, s -> s.is(ItemTags.PLANKS));
        int sticks = count(level, village, s -> s.is(Items.STICK)), swords = count(level, village, s -> s.is(Items.STONE_SWORD));
        int chests = count(level, village, s -> s.is(Items.CHEST));
        Bench.Plan chest = Bench.plan(level, t.v(), Items.CHEST, 1, Bench.handOf(level, t.v(), hand, "shop"));
        CompoundTag row = shopRow(level, village, "minecraft:chest");
        Kit.log("ws04 the hand's pieces " + worked + "; logs " + logs0 + " -> " + logs1 + ", planks " + planks0 + " -> " + planks1 + ", sticks "
            + sticks + ", swords " + swords + ", chests " + chests + "; a chest: " + chest.chain() + "; the shop's books: " + row.getString("short")
            + "; /village workshop:\n" + Workshop.page(level, t.v()));
        helper.assertTrue(logs1 == 16 && planks1 == 48 && sticks == 0, "not a log nor a plank of the builders' timber: " + logs1 + ", " + planks1);
        helper.assertTrue(swords == 0 && chests == 0, "so no sword and no chest");
        helper.assertTrue(!chest.ok() && chest.why.contains("builders' timber"), "the chest waits on it, and says why: " + chest.chain());
        helper.assertTrue(row.getString("short").contains("builders' timber"), "and so do the shop's books: " + row.getString("short"));
        helper.succeed();
    }

    // ============================================================ the watch fitted out

    /**
     * An Iron Age town with a guard who wears nothing: the shop's hand makes the watch's iron (the blade
     * first, then the helmet and the chestplate), and the shop fits the guard out of its stock — the
     * chestplate on its back, paid for by the village (its own purse untouched), counted in the shop's books.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ws05_guard_fitted_from_the_shop")
    public static void ws05_guard_fitted_from_the_shop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 354500, 2, Villages.Age.IRON);
        VillageFolkEntity hand = t.more().get(0), guard = t.more().get(1);
        Workshop.appointForTests(hand);
        level(hand, 12);
        guard.setJob(StationTask.GUARD);
        UUID village = t.village();
        fill(t, new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.OAK_PLANKS, 64),
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.CRAFTING_TABLE));
        Workshop.lookForTests(level, t.v());
        List<String> made = new ArrayList<>();
        for (int i = 0; i < 8 && count(level, village, s -> s.is(Items.IRON_CHESTPLATE)) == 0; i++) {
            if (Crafts.now(hand, level, t.v())) made.add(Workshop.doing(hand));
        }
        int plates0 = count(level, village, s -> s.is(Items.IRON_CHESTPLATE));
        int purse = guard.purse();
        helper.assertTrue(guard.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "the guard wears no chestplate yet");
        int put = Workshop.outfit(level, t.v());
        int plates1 = count(level, village, s -> s.is(Items.IRON_CHESTPLATE));
        ItemStack chest = guard.getItemBySlot(EquipmentSlot.CHEST);
        CompoundTag row = shopRow(level, village, "minecraft:iron_chestplate");
        Kit.log("ws05 the hand made " + made + "; chestplates in stock " + plates0 + " -> " + plates1 + "; " + put + " pieces to the watch; the guard wears "
            + chest + " (purse " + purse + " -> " + guard.purse() + "); the shop's books: " + row + "; the day: " + Workshop.logForTests(village));
        helper.assertTrue(plates0 >= 1, "the hand made an iron chestplate for the watch: " + made);
        helper.assertTrue(chest.is(Items.IRON_CHESTPLATE) && plates1 == plates0 - 1, "the guard wears it, out of the shop's stock");
        helper.assertTrue(guard.purse() == purse, "paid for by the village, not out of its purse");
        helper.assertTrue(row.getInt("soldToday") >= 1, "and the shop's books count it");
        helper.assertTrue(Workshop.logForTests(village).stream().anyMatch(l -> l.contains("|guard|") && l.contains("iron chestplate")),
            "the workshop's day says so");
        helper.succeed();
    }

    // ============================================================ the smithing table

    /**
     * A netherite sword is the Nether Age's work: ordered in the Diamond Age it is refused; in the Nether Age a
     * master hand makes it the whole way — a diamond sword of two diamonds and a stick, a smithing table of
     * iron and planks (kept), and the sword, the template and an ingot of netherite at the smithing table.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ws06_netherite_at_the_smithing_table")
    public static void ws06_netherite_at_the_smithing_table(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 355500, 1, Villages.Age.DIAMOND);
        VillageFolkEntity hand = t.more().get(0);
        Workshop.appointForTests(hand);
        level(hand, 40);
        UUID village = t.village();
        fill(t, new ItemStack(Items.DIAMOND, 32), new ItemStack(Items.NETHERITE_INGOT, 2), new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2),
            new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.OAK_PLANKS, 64),
            new ItemStack(Items.CRAFTING_TABLE));
        String refused = Workshop.order(level, t.v(), Items.NETHERITE_SWORD, 1, "Tester");
        Villages.ageForTests(village, Villages.Age.NETHER);
        Villages.forgetStock();
        String taken = Workshop.order(level, t.v(), Items.NETHERITE_SWORD, 1, "Tester");
        Workshop.lookForTests(level, t.v());
        Bench.Plan plan = Bench.plan(level, t.v(), Items.NETHERITE_SWORD, 1, Bench.handOf(level, t.v(), hand, "shop"));
        int diamonds0 = count(level, village, s -> s.is(Items.DIAMOND)), ingots0 = count(level, village, s -> s.is(Items.NETHERITE_INGOT));
        int templates0 = count(level, village, s -> s.is(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        List<Boolean> worked = new ArrayList<>();
        for (int i = 0; i < 3 && count(level, village, s -> s.is(Items.NETHERITE_SWORD)) == 0; i++) worked.add(Crafts.now(hand, level, t.v()));
        int swords = count(level, village, s -> s.is(Items.NETHERITE_SWORD));
        int diamonds1 = count(level, village, s -> s.is(Items.DIAMOND)), ingots1 = count(level, village, s -> s.is(Items.NETHERITE_INGOT));
        int templates1 = count(level, village, s -> s.is(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        Kit.log("ws06 the Diamond Age: " + refused + " / the Nether Age: " + taken + "; the plan: " + plan.chain() + " (" + plan.why + "); pieces "
            + worked + ": netherite swords " + swords + ", diamonds " + diamonds0 + " -> " + diamonds1 + ", netherite " + ingots0 + " -> " + ingots1
            + ", templates " + templates0 + " -> " + templates1 + "; the hand carries a smithing table: "
            + (hand.countCarried(s -> s.is(Items.SMITHING_TABLE)) > 0) + "; the day: " + Workshop.logForTests(village));
        helper.assertTrue(refused.contains("Nether Age") && !refused.startsWith("On the workshop's book"), "refused in the Diamond Age: " + refused);
        helper.assertTrue(taken.startsWith("On the workshop's book"), "taken in the Nether Age: " + taken);
        helper.assertTrue(plan.ok() && plan.chain().contains("smithing table"), "the whole way, at the smithing table: " + plan.chain());
        helper.assertTrue(swords == 1, "a netherite sword made");
        helper.assertTrue(diamonds0 - diamonds1 == 2 && ingots0 - ingots1 == 1 && templates0 - templates1 == 1,
            "of two diamonds, an ingot of netherite and the template");
        helper.succeed();
    }

    // ============================================================ the smelter's part

    /**
     * Glass bottles on order, sand in the stores and no glass, and a smelter at work: the shop does not fire
     * the glass at its own bench but sends it to the smeltery, and takes nothing meanwhile; the smelter fires
     * the glass out of the stores' sand, and the hand then blows the bottles of it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ws07_firing_goes_to_the_smelter")
    public static void ws07_firing_goes_to_the_smelter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 356500, 2, Villages.Age.STONE);
        VillageFolkEntity hand = t.more().get(0), smelter = t.more().get(1);
        Workshop.appointForTests(hand);
        smelter.setJob(StationTask.SMELT);
        UUID village = t.village();
        fill(t, new ItemStack(Items.SAND, 16), new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.COBBLESTONE, 64),
            new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.COBBLESTONE, 64),
            new ItemStack(Items.FURNACE), new ItemStack(Items.CRAFTING_TABLE));
        Workshop.order(level, t.v(), Items.GLASS_BOTTLE, 3, "Tester");
        Workshop.lookForTests(level, t.v());
        int sand0 = count(level, village, s -> s.is(Items.SAND));
        Crafts.now(hand, level, t.v());
        int sand1 = count(level, village, s -> s.is(Items.SAND)), glass1 = count(level, village, s -> s.is(Items.GLASS));
        java.util.Map<net.minecraft.world.item.Item, Integer> firing = Workshop.firingForTests(village);
        CompoundTag row = shopRow(level, village, "minecraft:glass_bottle");
        String fired = Workshop.fire(smelter, level);
        int sand2 = count(level, village, s -> s.is(Items.SAND)), glass2 = count(level, village, s -> s.is(Items.GLASS));
        List<Boolean> worked = new ArrayList<>();
        for (int i = 0; i < 3 && count(level, village, s -> s.is(Items.GLASS_BOTTLE)) == 0; i++) {
            Workshop.lookForTests(level, t.v());
            worked.add(Crafts.now(hand, level, t.v()));
        }
        int bottles = count(level, village, s -> s.is(Items.GLASS_BOTTLE)), glass3 = count(level, village, s -> s.is(Items.GLASS));
        Kit.log("ws07 the shop's first piece: sand " + sand0 + " -> " + sand1 + ", glass " + glass1 + "; to the smeltery " + firing
            + "; its books: " + row.getString("short") + "; the smelter fired: " + fired + " (sand " + sand2 + ", glass " + glass2 + "); then "
            + worked + ": bottles " + bottles + ", glass " + glass3 + "; the day: " + Workshop.logForTests(village));
        helper.assertTrue(sand1 == sand0 && glass1 == 0 && firing.getOrDefault(Items.GLASS, 0) >= 1,
            "the glass sent to the smeltery, and nothing taken meanwhile: " + firing);
        helper.assertTrue(fired != null && glass2 >= 1 && sand2 < sand1, "the smelter fired it out of the stores' sand: " + fired);
        helper.assertTrue(bottles >= 3 && glass3 < glass2, "and the hand made the bottles of it: " + bottles);
        helper.succeed();
    }
}
