package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Business;
import com.jrpetty.mcassistant.entity.CoinCensus;
import com.jrpetty.mcassistant.entity.Enterprise;
import com.jrpetty.mcassistant.entity.Health;
import com.jrpetty.mcassistant.entity.HomeMaking;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.InputWants;
import com.jrpetty.mcassistant.entity.ItemUses;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Persona;
import com.jrpetty.mcassistant.entity.PlayerStalls;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.WorkItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [uses16] Folk buy what they can use, and use what they buy (entity/ItemUses), at a player's stall.
 * <ul>
 * <li><b>iu01</b>: a farmer whose hoe is nearly worn through buys an iron one and takes it up, the old one to the stores; the
 *     saddle beside it is turned down, with its reason on the stall's books.</li>
 * <li><b>iu02</b>: a guard with no armour buys a breastplate and a helmet and wears them; a third piece it turns down.</li>
 * <li><b>iu03</b>: a household with a dark home buys a lantern, carries it home and hangs it; with its home lit it won't buy the
 *     second.</li>
 * <li><b>iu04</b>: a folk with nothing to eat buys bread (four, what it would eat, not the lot), not the saddle; a second look
 *     buys nothing; the coin only moved (the census).</li>
 * <li><b>iu05</b>: a toolsmith's firm short of iron buys the iron at the stall out of its till, bonded in its name.</li>
 * <li><b>iu06</b>: a reader buys a book and reads it; it won't buy the same book again.</li>
 * <li><b>iu07</b>: a folk laid up with a cold buys a healing potion and drinks it.</li>
 * <li><b>iu08</b>: a farmer with bare furrows buys seed and sows it, no more seed than furrows.</li>
 * <li><b>iu09</b>: things it has never heard of judged by their class and tags; this mod's rope coil bought by a miner.</li>
 * <li><b>iu10</b>: what the town needs, and who would buy each ware and why, on the stall's screen and in the city books.</li>
 * </ul>
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class ItemUsesGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 90000;

    // ------------------------------------------------------------------ the ground

    private static BlockPos flat(ServerLevel level, int x, int z) {
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        BlockPos ground = Kit.surface(level, x, z);
        int y = ground.getY();
        for (int dx = -20; dx <= 20; dx++) {
            for (int dz = -20; dz <= 20; dz++) {
                level.setBlock(new BlockPos(x + dx, y - 2, z + dz), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x + dx, y - 1, z + dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int h = 0; h <= 8; h++) level.setBlock(new BlockPos(x + dx, y + h, z + dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        return new BlockPos(x, y, z);
    }

    private static void ready(ServerLevel level, BlockPos heart, UUID village) {
        Villages.ageForTests(village, Villages.Age.STONE);
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
        BlockPos chest = heart.offset(3, 0, 3);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, chest);
        Container box = (Container) level.getBlockEntity(chest);
        box.setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
        box.setItem(1, new ItemStack(Items.OAK_LOG, 64));
        box.setItem(2, new ItemStack(Items.COBBLESTONE, 64));
        box.setItem(3, new ItemStack(Items.COBBLESTONE, 64));
        box.setItem(4, new ItemStack(Items.RED_WOOL, 8));
        Villages.forgetStock();
    }

    private record Town(UUID id, Villages.Village v, BlockPos heart, VillageFolkEntity f, Player p, PlayerStalls.Booth b, Container barrel) {}

    /** A town of one folk with this trade, and a player's stall rented on its square, empty. */
    private static Town town(GameTestHelper helper, ServerLevel level, BlockPos heart, VillageFolkEntity f, StationTask job) {
        UUID id = f.ownerId();
        Villages.Village v = Villages.get(id);
        ready(level, heart, id);
        f.setJob(job);
        f.keepTradeForTests();
        f.getInventoryItems().clear();
        f.spend(f.purse());
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        p.moveTo(heart.getX() + 1.5, heart.getY(), heart.getZ() + 1.5);
        p.getInventory().setItem(30, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 30));
        p.getInventory().setItem(31, new ItemStack(Items.BARREL));
        p.getInventory().setItem(32, new ItemStack(Items.OAK_SIGN));
        p.getInventory().setItem(33, new ItemStack(Items.OAK_FENCE, 4));
        p.getInventory().setItem(34, new ItemStack(Items.BLUE_WOOL, 3));
        String said = PlayerStalls.rent(level, v, p, null);
        PlayerStalls.Booth b = PlayerStalls.boothOf(id, p.getUUID());
        helper.assertTrue(b != null && level.getBlockEntity(b.at()) instanceof Container, "a stall rented: " + said);
        Container barrel = (Container) level.getBlockEntity(b.at());
        barrel.clearContent();
        ItemUses.forgetTown(id);
        return new Town(id, v, heart, f, p, b, barrel);
    }

    /** These in the stall at a price every folk thinks fair (a little under the going price). */
    private static void stock(ServerLevel level, Town t, ItemStack... goods) {
        int slot = 0;
        for (ItemStack g : goods) {
            t.barrel().setItem(slot++, g.copy());
            int going = PlayerStalls.going(level, t.id(), g);
            if (going > 0) PlayerStalls.priceForTests(level, t.v(), t.p().getUUID(), g, Math.max(1, (int) Math.floor(going * 0.85)));
        }
        t.barrel().setChanged();
    }

    private static int count(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty() && what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    private static String refused(Town t) {
        ListTag l = ItemUses.refusedTag(t.id(), t.p().getUUID());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) sb.append(l.getCompound(i).getString("what")).append(": ").append(l.getCompound(i).getString("why")).append("; ");
        return sb.toString();
    }

    private static VillageFolkEntity raise(GameTestHelper helper, ServerLevel level, BlockPos heart) {
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        return f;
    }

    private static BlockPos start(GameTestHelper helper, int k) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        PlayerStalls.resetForTests();
        ItemUses.resetForTests();
        level.setDayTime(6000);
        return flat(level, 4600000 + 4000 * k, Z);
    }

    // ============================================================ iu01: a worn hoe

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "iu01_worn_hoe")
    public static void iu01_worn_hoe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos heart = start(helper, 0);
        VillageFolkEntity first = raise(helper, level, heart);
        helper.runAtTickTime(10, () -> {
            Town t = town(helper, level, heart, first, StationTask.FARM);
            VillageFolkEntity f = t.f();
            ItemStack worn = new ItemStack(Items.STONE_HOE);
            worn.setDamageValue(worn.getMaxDamage() * 9 / 10);
            f.insertItem(worn);
            f.earn(40);
            stock(level, t, new ItemStack(Items.SADDLE), new ItemStack(Items.IRON_HOE));
            ItemUses.Want w = ItemUses.want(f, new ItemStack(Items.IRON_HOE));
            ItemUses.Want ws = ItemUses.want(f, new ItemStack(Items.SADDLE));
            int purse = f.purse();
            String got = PlayerStalls.browse(level, t.v(), f, t.p().getUUID(), false);
            ItemStack own = ItemUses.ownToolForTests(f);
            int oldInStores = Market.stock(level, t.id(), s -> s.is(Items.STONE_HOE));
            Kit.log("[VT] iu01 want iron hoe: " + w + "; saddle: " + ws.reason() + "; bought " + got + "; purse " + purse + " -> " + f.purse()
                + "; its hoe now " + own + "; stone hoes in the stores " + oldInStores + "; refused " + refused(t) + "; uses " + ItemUses.usesOf(f));
            helper.assertTrue(w.yes() && w.kind() == ItemUses.Kind.TOOL && w.qty() == 1, "it wants a better hoe: " + w);
            helper.assertTrue(!ws.yes(), "and no saddle: " + ws.reason());
            helper.assertTrue(got != null && got.contains("hoe"), "it bought the hoe at the stall: " + got);
            helper.assertTrue(own.is(Items.IRON_HOE), "and took it up for its work: " + own);
            helper.assertTrue(oldInStores == 1, "its worn one to the stores: " + oldInStores);
            helper.assertTrue(count(t.barrel(), s -> s.is(Items.SADDLE)) == 1 && refused(t).contains("saddle"), "the saddle left, turned down on the books: " + refused(t));
            helper.assertTrue(ItemUses.cardLine(f).toLowerCase().contains("hoe"), "on its card: " + ItemUses.cardLine(f));
            helper.succeed();
        });
    }

    // ============================================================ iu02: the guard's armour

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "iu02_guard_armour")
    public static void iu02_guard_armour(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos heart = start(helper, 1);
        VillageFolkEntity first = raise(helper, level, heart);
        helper.runAtTickTime(10, () -> {
            Town t = town(helper, level, heart, first, StationTask.GUARD);
            VillageFolkEntity f = t.f();
            for (EquipmentSlot s : new EquipmentSlot[]{ EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) f.setItemSlot(s, ItemStack.EMPTY);
            f.insertItem(new ItemStack(Items.IRON_SWORD));
            f.earn(120);
            stock(level, t, new ItemStack(Items.IRON_CHESTPLATE), new ItemStack(Items.IRON_HELMET), new ItemStack(Items.IRON_HELMET));
            String one = PlayerStalls.browse(level, t.v(), f, t.p().getUUID(), false);
            String two = PlayerStalls.browse(level, t.v(), f, t.p().getUUID(), false);
            String three = PlayerStalls.browse(level, t.v(), f, t.p().getUUID(), false);
            Kit.log("[VT] iu02 bought " + one + " / " + two + " / " + three + "; wears " + f.getItemBySlot(EquipmentSlot.HEAD) + ", "
                + f.getItemBySlot(EquipmentSlot.CHEST) + "; helmets left " + count(t.barrel(), s -> s.is(Items.IRON_HELMET)) + "; refused " + refused(t));
            helper.assertTrue(f.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), "it wears the breastplate it bought: " + one + " / " + two);
            helper.assertTrue(f.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "and the helmet");
            helper.assertTrue(three == null && count(t.barrel(), s -> s.is(Items.IRON_HELMET)) == 1, "but not a second helmet: " + three);
            helper.succeed();
        });
    }

    // ============================================================ iu03: a dark home's lantern

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "iu03_dark_home_lantern")
    public static void iu03_dark_home_lantern(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos heart = start(helper, 2);
        VillageFolkEntity first = raise(helper, level, heart);
        helper.runAtTickTime(10, () -> {
            Town t = town(helper, level, heart, first, StationTask.FISH);
            VillageFolkEntity f = t.f();
            BlockPos at = Kit.surface(level, heart.getX() - 14, heart.getZ() + 26);
            BuildGoal.stamp(level, "house", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(t.id(), "house", at, Direction.NORTH);
            // A dark house: the builders' lights taken out.
            int dark = 0;
            for (BlockPos q : BlockPos.betweenClosed(at.offset(-8, -2, -8), at.offset(8, 12, 8))) {
                Block blk = level.getBlockState(q).getBlock();
                if (blk instanceof TorchBlock || blk instanceof LanternBlock || blk instanceof CandleBlock) {
                    level.setBlock(q, Blocks.AIR.defaultBlockState(), 3);
                    dark++;
                }
            }
            Homes.tickForTests(level, t.v());
            HomeMaking.dayForTests(level, t.v());
            helper.assertTrue(!HomeMaking.planForTests(t.id(), at).isEmpty(), "it has the house for its home, and a plan for it");
            ItemUses.forget(f);
            f.earn(40);
            ItemUses.Want w = ItemUses.want(f, new ItemStack(Items.LANTERN));
            stock(level, t, new ItemStack(Items.LANTERN, 2));
            String got = PlayerStalls.browse(level, t.v(), f, t.p().getUUID(), false);
            int carried = f.countCarried(s -> s.is(Items.LANTERN));
            HomeMaking.dayForTests(level, t.v());
            BlockPos light = HomeMaking.pieceForTests(t.id(), at, "light");
            ItemUses.forget(f);
            ItemUses.Want again = ItemUses.want(f, new ItemStack(Items.LANTERN));
            String second = PlayerStalls.browse(level, t.v(), f, t.p().getUUID(), false);
            Kit.log("[VT] iu03 took out " + dark + " lights; want " + w + "; bought " + got + " (carried " + carried + "); the plan "
                + HomeMaking.planForTests(t.id(), at) + "; the light at " + light + " " + (light == null ? "" : level.getBlockState(light))
                + "; again " + again.reason() + "; second " + second + "; left " + count(t.barrel(), s -> s.is(Items.LANTERN)) + "; uses " + ItemUses.usesOf(f)
                + "; HOUSE " + at.toShortString());
            helper.assertTrue(w.yes() && w.kind() == ItemUses.Kind.LIGHT && w.qty() == 1, "a dark home wants one light: " + w);
            helper.assertTrue(got != null && got.contains("lantern") && carried == 1, "it bought one lantern: " + got + ", carried " + carried);
            helper.assertTrue(light != null && level.getBlockState(light).getBlock() instanceof LanternBlock, "and hung it in its home: " + light);
            helper.assertTrue(!again.yes() && again.reason().contains("lit"), "lit, it wants no more: " + again.reason());
            helper.assertTrue(second == null && count(t.barrel(), s -> s.is(Items.LANTERN)) == 1, "the second lantern stays in the stall: " + second);
            helper.succeed();
        });
    }

    // ============================================================ iu04: bread, not a saddle

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "iu04_bread_not_saddle")
    public static void iu04_bread_not_saddle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos heart = start(helper, 3);
        VillageFolkEntity first = raise(helper, level, heart);
        helper.runAtTickTime(10, () -> {
            Town t = town(helper, level, heart, first, StationTask.FISH);
            VillageFolkEntity f = t.f();
            f.earn(30);
            stock(level, t, new ItemStack(Items.SADDLE), new ItemStack(Items.BREAD, 16));
            long census = CoinCensus.count(t.id());
            int purse = f.purse();
            String got = PlayerStalls.browse(level, t.v(), f, t.p().getUUID(), false);
            int bread = f.countCarried(s -> s.is(Items.BREAD));
            String again = PlayerStalls.browse(level, t.v(), f, t.p().getUUID(), false);
            long after = CoinCensus.count(t.id());
            ItemUses.Verdict saddle = ItemUses.judge(level, t.id(), new ItemStack(Items.SADDLE));
            Kit.log("[VT] iu04 bought " + got + " (" + bread + " bread carried; purse " + purse + " -> " + f.purse() + ", till "
                + PlayerStalls.till(t.id(), t.p().getUUID()) + "); again " + again + "; the saddle: " + saddle.line() + "; refused " + refused(t)
                + "; census " + census + " -> " + after);
            helper.assertTrue(got != null && got.contains("bread"), "it bought bread: " + got);
            helper.assertTrue(bread >= 2 && bread <= 4, "what it would eat, not the stall's whole lot: " + bread);
            helper.assertTrue(again == null && f.countCarried(s -> s.is(Items.BREAD)) == bread, "and no more on a second look: " + again);
            helper.assertTrue(count(t.barrel(), s -> s.is(Items.SADDLE)) == 1 && !saddle.yes() && saddle.why().contains("rides"),
                "nobody buys the saddle: " + saddle.line());
            helper.assertTrue(refused(t).contains("saddle"), "the saddle on the stall's books, turned down: " + refused(t));
            helper.assertTrue(census == after, "the coin only moved, purse to till: " + census + " -> " + after);
            helper.succeed();
        });
    }

    // ============================================================ iu05: a firm's iron

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "iu05_firm_buys_iron")
    public static void iu05_firm_buys_iron(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos heart = start(helper, 4);
        VillageFolkEntity first = raise(helper, level, heart);
        helper.runAtTickTime(10, () -> {
            InputWants.resetForTests();
            Town t = town(helper, level, heart, first, StationTask.SMITH);
            VillageFolkEntity f = t.f();
            Business b = Enterprise.foundForTests(level, t.v(), "toolsmith", f);
            helper.assertTrue(b != null && Enterprise.employerOf(t.id(), f) == b, "a toolsmith's firm, the smith its hand: " + b);
            b.till += 5000;
            InputWants.wantForTests(t.id(), Items.IRON_INGOT, 8);
            ItemUses.forget(f);
            stock(level, t, new ItemStack(Items.IRON_INGOT, 16));
            long census = CoinCensus.count(t.id());
            long till = b.till;
            int purse = f.purse();
            String got = PlayerStalls.browse(level, t.v(), f, t.p().getUUID(), false);
            Business.Lot lot = b.stock.get("minecraft:iron_ingot");
            int stores = Market.stock(level, t.id(), s -> s.is(Items.IRON_INGOT));
            long after = CoinCensus.count(t.id());
            Kit.log("[VT] iu05 bought " + got + "; the firm's till " + till + " -> " + b.till + "; its iron " + (lot == null ? 0 : lot.qty)
                + "; the stores' iron " + stores + "; the smith's purse " + purse + " -> " + f.purse() + "; census " + census + " -> " + after);
            helper.assertTrue(got != null && got.contains("iron"), "the firm's hand bought the iron: " + got);
            helper.assertTrue(b.till < till && f.purse() == purse, "out of the firm's till, not the smith's purse: " + till + " -> " + b.till);
            helper.assertTrue(lot != null && lot.qty >= 1 && lot.qty <= 8 && stores == lot.qty, "bonded in the stores in the firm's name, no more than it wanted: "
                + (lot == null ? 0 : lot.qty) + " / " + stores);
            helper.assertTrue(census == after, "the coin only moved: " + census + " -> " + after);
            helper.succeed();
        });
    }

    // ============================================================ iu06: a reader's book

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "iu06_reader_reads")
    public static void iu06_reader_reads(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos heart = start(helper, 5);
        VillageFolkEntity first = raise(helper, level, heart);
        helper.runAtTickTime(10, () -> {
            Town t = town(helper, level, heart, first, StationTask.FISH);
            VillageFolkEntity f = t.f();
            ItemUses.tasteForTests(f, Persona.Hobby.READING, Persona.Gift.BOOKS);
            f.earn(30);
            f.insertItem(new ItemStack(Items.BREAD, 8));
            ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
            book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("The Long Road"), "Dev", 0,
                List.of(Filterable.passThrough(Component.literal("Once upon a time..."))), true));
            stock(level, t, book.copyWithCount(2));
            String got = PlayerStalls.browse(level, t.v(), f, t.p().getUUID(), false);
            ItemUses.Want again = ItemUses.want(f, book);
            Kit.log("[VT] iu06 bought " + got + "; uses " + ItemUses.usesOf(f) + "; again " + again.reason());
            helper.assertTrue(got != null && !ItemUses.usesOf(f).isEmpty() && ItemUses.usesOf(f).get(0).contains("read it"), "a reader buys a book and reads it: " + ItemUses.usesOf(f));
            helper.assertTrue(!again.yes(), "and doesn't buy the same book again: " + again.reason());
            helper.succeed();
        });
    }

    // ============================================================ iu07: a remedy for the ill

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "iu07_sick_drinks_potion")
    public static void iu07_sick_drinks_potion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos heart = start(helper, 6);
        VillageFolkEntity first = raise(helper, level, heart);
        helper.runAtTickTime(10, () -> {
            Town t = town(helper, level, heart, first, StationTask.FISH);
            VillageFolkEntity f = t.f();
            f.earn(30);
            f.insertItem(new ItemStack(Items.BREAD, 8));
            Health.catchForTests(f, "out in the rain");
            int cold = ItemUses.coldForTests(f);
            ItemUses.forget(f);
            ItemStack potion = new ItemStack(Items.POTION);
            potion.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.HEALING));
            stock(level, t, new ItemStack(Items.SADDLE), potion);
            String got = PlayerStalls.browse(level, t.v(), f, t.p().getUUID(), false);
            int after = ItemUses.coldForTests(f);
            Kit.log("[VT] iu07 cold " + cold + " -> " + after + "; bought " + got + "; bottles " + f.countCarried(s -> s.is(Items.GLASS_BOTTLE))
                + ", potions " + f.countCarried(s -> s.is(Items.POTION)) + "; uses " + ItemUses.usesOf(f));
            helper.assertTrue(cold > 0, "it has a cold");
            helper.assertTrue(got != null && got.contains("potion"), "it bought the potion: " + got);
            helper.assertTrue(after < cold && f.countCarried(s -> s.is(Items.POTION)) == 0 && f.countCarried(s -> s.is(Items.GLASS_BOTTLE)) == 1,
                "and drank it, its cold eased: " + cold + " -> " + after);
            helper.succeed();
        });
    }

    // ============================================================ iu08: seed sown

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "iu08_seeds_sown")
    public static void iu08_seeds_sown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos heart = start(helper, 7);
        VillageFolkEntity first = raise(helper, level, heart);
        helper.runAtTickTime(10, () -> {
            Town t = town(helper, level, heart, first, StationTask.FARM);
            VillageFolkEntity f = t.f();
            f.insertItem(new ItemStack(Items.STONE_HOE));
            f.insertItem(new ItemStack(Items.BREAD, 8));
            f.earn(30);
            BlockPos field = heart.offset(-12, -1, -12);
            for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 3; dz++) {
                level.setBlock(field.offset(dx, 0, dz), Blocks.FARMLAND.defaultBlockState(), 3);
                level.setBlock(field.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), 3);
            }
            level.setBlock(field.offset(1, 0, 1), Blocks.WATER.defaultBlockState(), 3);
            f.teleportTo(field.getX() + 4.5, field.getY() + 1, field.getZ() + 1.5);
            ItemUses.forget(f);
            stock(level, t, new ItemStack(Items.WHEAT_SEEDS, 32));
            String got = PlayerStalls.browse(level, t.v(), f, t.p().getUUID(), false);
            int crops = 0;
            for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 3; dz++) if (level.getBlockState(field.offset(dx, 1, dz)).getBlock() instanceof CropBlock) crops++;
            int left = count(t.barrel(), s -> s.is(Items.WHEAT_SEEDS));
            Kit.log("[VT] iu08 bought " + got + "; crops " + crops + "; seed left in the stall " + left + ", carried " + f.countCarried(s -> s.is(Items.WHEAT_SEEDS))
                + "; uses " + ItemUses.usesOf(f));
            helper.assertTrue(got != null && got.contains("seed"), "it bought seed: " + got);
            helper.assertTrue(crops == 8, "and sowed the eight bare furrows: " + crops);
            helper.assertTrue(32 - left == 8 && f.countCarried(s -> s.is(Items.WHEAT_SEEDS)) == 0, "no more seed than furrows: " + (32 - left));
            helper.succeed();
        });
    }

    // ============================================================ iu09: things it has never heard of

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "iu09_unknown_by_tags")
    public static void iu09_unknown_by_tags(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos heart = start(helper, 8);
        VillageFolkEntity first = raise(helper, level, heart);
        helper.runAtTickTime(10, () -> {
            Town t = town(helper, level, heart, first, StationTask.MINE);
            VillageFolkEntity f = t.f();
            f.insertItem(new ItemStack(Items.IRON_PICKAXE));
            f.insertItem(new ItemStack(Items.BREAD, 8));
            f.earn(30);
            ItemStack rope = new ItemStack(WorkItems.ROPE_COIL.get());
            var shard = ItemUses.kinds(new ItemStack(Items.PRISMARINE_SHARD));
            var copper = ItemUses.kinds(new ItemStack(Items.RAW_COPPER));
            var ropeKinds = ItemUses.kinds(rope);
            var egg = ItemUses.kinds(new ItemStack(Items.COW_SPAWN_EGG));
            ItemUses.Verdict eggV = ItemUses.judge(level, t.id(), new ItemStack(Items.COW_SPAWN_EGG));
            int going = PlayerStalls.going(level, t.id(), rope);
            ItemUses.forget(f);
            stock(level, t, new ItemStack(Items.COW_SPAWN_EGG), rope);
            String got = PlayerStalls.browse(level, t.v(), f, t.p().getUUID(), false);
            Kit.log("[VT] iu09 prismarine shard " + shard + "; raw copper " + copper + "; rope coil " + ropeKinds + " (going " + going + "); spawn egg "
                + egg + " -> " + eggV.line() + "; the miner bought " + got + "; refused " + refused(t));
            helper.assertTrue(shard.contains(ItemUses.Kind.INPUT) && copper.contains(ItemUses.Kind.INPUT), "makings by their tags: " + shard + " " + copper);
            helper.assertTrue(ropeKinds.contains(ItemUses.Kind.TOOL) && going > 0, "this mod's rope a tool, priced though the counter has no price: " + going);
            helper.assertTrue(egg.isEmpty() && !eggV.yes(), "a spawn egg nobody's: " + eggV.line());
            helper.assertTrue(got != null && got.contains("rope") && f.countCarried(s -> s.is(WorkItems.ROPE_COIL.get())) == 1, "a miner buys the rope: " + got);
            helper.succeed();
        });
    }

    // ============================================================ iu10: what the town needs, on the screens

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "iu10_needs_on_screens")
    public static void iu10_needs_on_screens(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos heart = start(helper, 9);
        VillageFolkEntity first = raise(helper, level, heart);
        VillageFolkEntity second = VillageFolkSpawnerBlock.raise(level, heart.east(2), 0.0F);
        helper.runAtTickTime(10, () -> {
            Town t = town(helper, level, heart, first, StationTask.FARM);
            second.setJob(StationTask.GUARD);
            second.keepTradeForTests();
            for (EquipmentSlot s : new EquipmentSlot[]{ EquipmentSlot.HEAD, EquipmentSlot.CHEST }) second.setItemSlot(s, ItemStack.EMPTY);
            second.earn(80);
            t.f().earn(30);
            ItemUses.forgetTown(t.id());
            stock(level, t, new ItemStack(Items.SADDLE), new ItemStack(Items.IRON_HELMET));
            CompoundTag screen = PlayerStalls.screen(level, t.v(), t.b(), t.p());
            ListTag needs = screen.getList("needs", Tag.TAG_COMPOUND);
            ListTag wares = screen.getList("wares", Tag.TAG_COMPOUND);
            String saddle = "", helmet = "";
            for (int i = 0; i < wares.size(); i++) {
                CompoundTag w = wares.getCompound(i);
                if (w.getString("key").contains("saddle")) saddle = w.getCompound("use").getString("line");
                if (w.getString("key").contains("helmet")) helmet = w.getCompound("use").getString("line");
            }
            StringBuilder nl = new StringBuilder();
            for (int i = 0; i < needs.size(); i++) nl.append(needs.getCompound(i).getString("name")).append(" x").append(needs.getCompound(i).getInt("qty"))
                .append(" ").append(needs.getCompound(i).getInt("most")).append("c (").append(needs.getCompound(i).getString("why")).append("); ");
            Kit.log("[VT] iu10 needs: " + nl + " | saddle: " + saddle + " | helmet: " + helmet + " | farmer's card: " + ItemUses.cardLine(t.f()));
            helper.assertTrue(needs.size() >= 2 && nl.toString().contains("hoe"), "what the town needs, the farmer's hoe among it: " + nl);
            helper.assertTrue(helmet.contains(second.displayNameCap()) && saddle.startsWith("Nobody"), "who would buy each ware and why: " + helmet + " / " + saddle);
            helper.succeed();
        });
    }
}
