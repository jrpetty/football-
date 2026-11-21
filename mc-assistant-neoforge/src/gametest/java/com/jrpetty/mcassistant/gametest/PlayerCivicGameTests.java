package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Elections;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Hustings;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Orders;
import com.jrpetty.mcassistant.entity.PlayerCivic;
import com.jrpetty.mcassistant.entity.PlayerLeader;
import com.jrpetty.mcassistant.entity.PlayerTrades;
import com.jrpetty.mcassistant.entity.Pledges;
import com.jrpetty.mcassistant.entity.Standing;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.entity.TradeGoods;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.item.CivicItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [player-civic] A player in a town's civic life: standing for leader and leading it (Hustings, Pledges,
 * PlayerLeader), and learning a trade from a master (PlayerTrades, Lessons, TradeGoods, TrainedRecipe).
 *
 * <p>Each on its own ground (x 1140000 to 1144000, z 66000), in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PlayerCivicGameTests {

    private static final String EMPTY = "empty";

    /** A town of so many folk raised about this spot, all of one village. */
    private static List<VillageFolkEntity> town(GameTestHelper helper, ServerLevel level, int x, int z, int n) {
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 3 + (i % 4) * 2, z - 2 + (i / 4) * 2), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.ensurePersona();
            folk.add(f);
        }
        UUID village = folk.get(0).ownerId();
        for (VillageFolkEntity f : folk) helper.assertTrue(village != null && village.equals(f.ownerId()), "all of one village");
        return folk;
    }

    /** A chest of the town's stores beside its heart, holding these. */
    private static Container stores(ServerLevel level, UUID village, BlockPos heart, ItemStack... goods) {
        BlockPos at = Kit.surface(level, heart.getX() + 6, heart.getZ() + 6);
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        ZoneChests.mark(level, at);
        Container c = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) c.setItem(i, goods[i].copy());
        c.setChanged();
        Villages.forgetStores(village);
        return c;
    }

    private static int carried(Player p, Item it) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(it)) n += p.getInventory().getItem(i).getCount();
        return n;
    }

    // ============================================================ pc01 standing for leader

    /**
     * A citizen the town thinks the world of puts its name forward at the town's heart (it has no board or hall yet)
     * while the election is called, promises a lower tithe and the first building the town wants, canvasses every
     * folk and makes a speech; the count makes it the town's leader over the folk who stood. In office it sets the
     * tithe at five in the hundred (the tithe the town then pays is half what it was) and the plan to food first.
     * The next morning the tithe promise is kept and approval rises; past the building's deadline, with nothing
     * built, it is broken and approval falls.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pc_pc01_stands_and_wins")
    public static void pc01_stands_wins_and_is_held_to_it(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        PlayerCivic.resetForTests();
        level.setDayTime(2000);
        level.updateSkyBrightness();
        int x = 1140000, z = 66000;
        List<VillageFolkEntity> folk = town(helper, level, x, z, 7);
        UUID village = folk.get(0).ownerId();
        Villages.Village v = Villages.get(village);
        BlockPos heart = Kit.surface(level, x, z);
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.moveTo(heart.getX() + 0.5, heart.getY(), heart.getZ() + 1.5);
        // A name is put forward at the board (the founders put one up), or in the hall: the player stands there.
        BlockPos board = com.jrpetty.mcassistant.entity.VillageBoards.lectern(village);
        if (board != null) p.moveTo(board.getX() + 0.5, board.getY(), board.getZ() + 0.5);
        Kit.log("pc01 at the board: " + board + ", the heart " + heart);
        String name = p.getName().getString();
        Ledger.addCitizen(village, p.getUUID(), name);
        for (VillageFolkEntity f : folk) {
            f.persona().feelFor(p.getUUID(), name, 100);
            f.persona().met(p.getUUID());
        }
        Standing.stir(village, p.getUUID());
        long day = level.getDayTime() / 24000L;
        int called = Elections.callForTests(level, v, day + 1);
        Kit.log("pc01 called: " + Elections.standingForTests(village));
        helper.assertTrue(called >= 1, "folk stand: " + Elections.standingForTests(village));
        // The folk who stand are weaker: the town has no great liking for them.
        for (VillageFolkEntity k : folk) {
            for (String s : Elections.standingForTests(village)) {
                if (!s.startsWith(k.displayNameCap() + ":")) continue;
                for (VillageFolkEntity f : folk) if (f != k) f.life().feel(k.getUUID(), k.displayNameCap(), -40);
            }
        }

        // A folk who is not standing hears the player put its name forward.
        VillageFolkEntity voter = null;
        for (VillageFolkEntity f : folk) {
            boolean stands = false;
            for (String s : Elections.standingForTests(village)) if (s.startsWith(f.displayNameCap() + ":")) stands = true;
            if (!stands) { voter = f; break; }
        }
        helper.assertTrue(voter != null, "somebody is not standing");
        String stood = FolkTalk.answer(voter, p, TalkTopic.SAY, "I'd like to stand for election");
        Kit.log("pc01 stand: " + stood);
        boolean onTheList = false;
        for (String s : Elections.standingForTests(village)) if (s.startsWith(name + ":")) onTheList = true;
        helper.assertTrue(onTheList, "the player stands: " + stood + " — " + Elections.standingForTests(village));

        // Promises: a lower tithe (said), and the first building the town wants (or a festival every season).
        String tithe = FolkTalk.answer(voter, p, TalkTopic.SAY, "I promise to lower the tithe");
        Pledges.Pledge second = null;
        for (Pledges.Pledge pl : Pledges.offered(level, village)) if (pl.kind() == Pledges.Kind.BUILD) { second = pl; break; }
        if (second == null) second = new Pledges.Pledge(Pledges.Kind.FESTIVAL, "");
        String built = Hustings.promise(level, village, p.getUUID(), name, second);
        List<String> keys = Hustings.promisesForTests(village, p.getUUID());
        Kit.log("pc01 promises: " + tithe + " | " + built + " -> " + keys);
        helper.assertTrue(keys != null && keys.contains("tithe") && keys.contains(second.key()), "both promised: " + keys);

        // The campaign: every folk asked for its vote, and a speech in the square.
        for (VillageFolkEntity f : folk) Kit.log("pc01 canvass " + f.displayNameCap() + ": " + FolkTalk.answer(f, p, TalkTopic.SAY, "Will you vote for me?"));
        Kit.log("pc01 speech: " + Hustings.speech(level, p));

        // The count.
        Elections.Result r = Elections.countForTests(level, v);
        StringBuilder tally = new StringBuilder();
        r.votes().forEach((k, n) -> tally.append(k.name()).append(' ').append(n).append(", "));
        Kit.log("pc01 count: " + tally + "winner " + (r.winner() == null ? "none" : r.winner().name()));
        helper.assertTrue(r.winner() != null && r.winner().id().equals(p.getUUID()), "the player wins on the folk's votes: " + tally);
        helper.assertTrue(PlayerLeader.leads(village, p.getUUID()) && p.getUUID().equals(Villages.elder(village)),
            "the player leads: elder " + Villages.elderName(village));

        // The leader's powers: the tithe, as the town pays it; the plan.
        String set = PlayerLeader.tithe(level, p, 5);
        for (VillageFolkEntity f : folk) f.spend(f.purse());
        folk.get(0).earn(112);
        int in = Market.tithe(level, v, day);
        Kit.log("pc01 tithe: " + set + " -> " + in + " coin in");
        helper.assertTrue(in == 5, "at five in the hundred, a hundred over a dozen pays five (not ten): " + in);
        String plan = PlayerLeader.plan(level, p, "food");
        Kit.log("pc01 plan: " + plan);
        helper.assertTrue(Orders.current(village) == Orders.Order.LARDER, "food first: " + Orders.current(village));

        // Held to it: the tithe promise kept the next morning; the other broken past its deadline.
        int a0 = PlayerLeader.approval(village);
        PlayerLeader.judgeForTests(level, v, day + 1);
        int a1 = PlayerLeader.approval(village);
        List<String> after1 = PlayerLeader.promisesForTests(village);
        Kit.log("pc01 morning after: " + after1 + " approval " + a0 + " -> " + a1);
        helper.assertTrue(after1.contains("tithe:KEPT") && a1 > a0, "the tithe kept, approval up: " + after1 + ", " + a0 + " -> " + a1);
        PlayerLeader.judgeForTests(level, v, day + 12);
        int a2 = PlayerLeader.approval(village);
        List<String> after2 = PlayerLeader.promisesForTests(village);
        Kit.log("pc01 past the deadline: " + after2 + " approval " + a1 + " -> " + a2);
        helper.assertTrue(after2.contains(second.key() + ":BROKEN") && a2 < a1, "broken past its deadline, approval down: " + after2
            + ", " + a1 + " -> " + a2);
        level.getServer().getPlayerList().remove(p);    // no stand-in player left to despawn other tests' monsters
        helper.succeed();
    }

    // ============================================================ pc02 the smith's apprentice

    /**
     * A player asks a master smith (level thirty) to take it on, pays its fee, brings twenty iron (a pickaxe forged
     * for it, the rest into the stores), smelts sixteen iron with the smith by, and so learns the reinforced pickaxe:
     * the very same crafting grid that made nothing before makes one now. Forging one under the smith's eye makes it
     * a Master Smith, with the smith's axe for a graduation piece. And the master smith makes reinforced pickaxes for
     * the town's miners out of the stores.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pc_pc02_smiths_apprentice")
    public static void pc02_apprentice_learns_the_reinforced_pickaxe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        PlayerCivic.resetForTests();
        level.setDayTime(2000);
        level.updateSkyBrightness();
        int x = 1142000, z = 66000;
        List<VillageFolkEntity> folk = town(helper, level, x, z, 3);
        UUID village = folk.get(0).ownerId();
        Villages.Village v = Villages.get(village);
        BlockPos heart = Kit.surface(level, x, z);
        VillageFolkEntity smith = folk.get(0);
        smith.setJob(StationTask.SMITH);
        smith.tradeXpForTests(StationTask.SMITH, AssistantEntity.xpForLevel(30));
        folk.get(1).setJob(StationTask.MINE);
        stores(level, village, heart, new ItemStack(Items.OAK_PLANKS, 16));
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.moveTo(smith.getX() + 1.5, smith.getY(), smith.getZ() + 0.5);
        p.getInventory().setItem(30, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 40));

        // Before: the master's pattern in a crafting grid makes nothing for a player nobody taught.
        CraftingMenu menu = new CraftingMenu(77, p.getInventory(), ContainerLevelAccess.create(level, heart));
        p.containerMenu = menu;
        menu.getSlot(1).set(new ItemStack(Items.IRON_INGOT));
        menu.getSlot(2).set(new ItemStack(Items.COPPER_INGOT));
        menu.getSlot(3).set(new ItemStack(Items.IRON_INGOT));
        menu.getSlot(5).set(new ItemStack(Items.IRON_INGOT));
        menu.getSlot(8).set(new ItemStack(Items.IRON_PICKAXE));
        ItemStack before = menu.getSlot(0).getItem().copy();
        Kit.log("pc02 before: " + before);
        helper.assertTrue(before.isEmpty(), "untaught, the grid makes nothing: " + before);

        // Taken on, the fee paid.
        int coins0 = Market.coinsHeld(p);
        String taken = FolkTalk.answer(smith, p, TalkTopic.SAY, "Will you take me as your apprentice?");
        Kit.log("pc02 taken on: " + taken + " (coins " + coins0 + " -> " + Market.coinsHeld(p) + ")");
        helper.assertTrue(PlayerTrades.doneForTests(p.getUUID(), StationTask.SMITH)[0] == 0 && Market.coinsHeld(p) < coins0,
            "an apprentice, the fee paid: " + taken);

        // Lesson one: twenty iron brought, a pickaxe forged for the apprentice, the rest into the stores.
        p.getInventory().setItem(20, new ItemStack(Items.IRON_INGOT, 20));
        int picks0 = carried(p, Items.IRON_PICKAXE);
        String one = FolkTalk.answer(smith, p, TalkTopic.SAY, "Here's my lesson");
        int ironInStores = Market.stock(level, village, s -> s.is(Items.IRON_INGOT));
        Kit.log("pc02 lesson one: " + one + " (picks " + picks0 + " -> " + carried(p, Items.IRON_PICKAXE) + ", iron in the stores " + ironInStores + ")");
        helper.assertTrue(PlayerTrades.doneForTests(p.getUUID(), StationTask.SMITH)[0] == 1 && carried(p, Items.IRON_INGOT) == 0
            && carried(p, Items.IRON_PICKAXE) == picks0 + 1 && ironInStores == 17, "lesson one learned: " + one);

        // Lesson two: sixteen iron smelted with the smith by.
        NeoForge.EVENT_BUS.post(new PlayerEvent.ItemSmeltedEvent(p, new ItemStack(Items.IRON_INGOT, 16)));
        int[] two = PlayerTrades.doneForTests(p.getUUID(), StationTask.SMITH);
        Kit.log("pc02 lesson two: done " + two[0] + ", knows " + PlayerTrades.knows(p.getUUID(), "smith"));
        helper.assertTrue(two[0] == 2 && PlayerTrades.knows(p.getUUID(), "smith"), "the recipe learned: " + two[0]);

        // After: the same grid makes the reinforced pickaxe.
        menu.getSlot(8).set(new ItemStack(Items.IRON_PICKAXE));
        ItemStack after = menu.getSlot(0).getItem().copy();
        Kit.log("pc02 after: " + after);
        helper.assertTrue(after.is(CivicItems.REINFORCED_PICKAXE.get()), "taught, the grid makes a reinforced pickaxe: " + after);

        // Lesson three: one forged under the master's eye: Master Smith, and the master's own axe.
        int axes0 = carried(p, Items.IRON_AXE);
        NeoForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(p, after.copy(), new SimpleContainer(9)));
        String title = PlayerTrades.title(p.getUUID());
        Kit.log("pc02 lesson three: done " + PlayerTrades.doneForTests(p.getUUID(), StationTask.SMITH)[0] + ", " + title
            + ", axes " + axes0 + " -> " + carried(p, Items.IRON_AXE));
        helper.assertTrue("Master Smith".equals(title) && carried(p, Items.IRON_AXE) == axes0 + 1, "a master, with the master's piece: " + title);

        // The master smith makes them for the miners, out of the stores: an iron pick, three bars and a copper strap.
        stores(level, village, heart.east(2), new ItemStack(Items.IRON_INGOT, 24), new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.COPPER_INGOT, 2));
        String made = TradeGoods.craftForTests(level, v, smith);
        int reinforced = Market.stock(level, village, s -> s.is(CivicItems.REINFORCED_PICKAXE.get()));
        Kit.log("pc02 the smith's own work: " + made + " (" + reinforced + " in the stores)");
        helper.assertTrue(made != null && reinforced == 1, "the master smith rivets one for the miners: " + made);
        level.getServer().getPlayerList().remove(p);    // no stand-in player left to despawn other tests' monsters
        helper.succeed();
    }

    // ============================================================ pc03 the master warms to its apprentice

    /**
     * A miner of level twenty-eight takes a player on, and likes it the better for it; ten iron ore mined with the
     * master by is the first lesson (mined with the master far off, none of it counts), and the master likes it
     * better again; it speaks of its apprentice's progress when asked about itself, and its card names the
     * apprentice. A miner still learning sends the player to the master.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pc_pc03_master_warms")
    public static void pc03_the_master_warms_to_its_apprentice(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        PlayerCivic.resetForTests();
        level.setDayTime(2000);
        level.updateSkyBrightness();
        int x = 1144000, z = 66000;
        List<VillageFolkEntity> folk = town(helper, level, x, z, 3);
        UUID village = folk.get(0).ownerId();
        VillageFolkEntity master = folk.get(0), learner = folk.get(1);
        master.setJob(StationTask.MINE);
        master.tradeXpForTests(StationTask.MINE, AssistantEntity.xpForLevel(28));
        learner.setJob(StationTask.MINE);
        learner.tradeXpForTests(StationTask.MINE, AssistantEntity.xpForLevel(6));
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.moveTo(master.getX() + 1.5, master.getY(), master.getZ() + 0.5);
        p.getInventory().setItem(30, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 40));
        String name = p.getName().getString();

        String notYet = FolkTalk.answer(learner, p, TalkTopic.SAY, "Will you take me as your apprentice?");
        Kit.log("pc03 a learner: " + notYet);
        helper.assertTrue(notYet.contains(master.displayNameCap()), "a miner still learning sends you to the master: " + notYet);

        int aff0 = master.persona().affinity(p.getUUID());
        String taken = FolkTalk.answer(master, p, TalkTopic.SAY, "Will you take me as your apprentice?");
        int aff1 = master.persona().affinity(p.getUUID());
        Kit.log("pc03 taken on: " + taken + " (liking " + aff0 + " -> " + aff1 + ")");
        helper.assertTrue(PlayerTrades.doneForTests(p.getUUID(), StationTask.MINE)[0] == 0 && aff1 > aff0, "taken on, and liked the better: " + aff0 + " -> " + aff1);

        // Mined far out of town, the master at home: nothing counts.
        BlockPos ore = Kit.surface(level, x + 2, z + 2);
        double mx = master.getX(), my = master.getY(), mz = master.getZ();
        BlockPos far = ore.offset(0, 0, 300);
        p.moveTo(far.getX() + 0.5, far.getY(), far.getZ() + 1.5);
        NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, far, Blocks.IRON_ORE.defaultBlockState(), p));
        int away = PlayerTrades.doneForTests(p.getUUID(), StationTask.MINE)[1];
        Kit.log("pc03 with the master away: progress " + away);
        helper.assertTrue(away == 0, "away from the master, nothing counts: " + away);

        // With the master by: ten iron ore, the first lesson.
        p.moveTo(mx + 1.5, my, mz + 0.5);
        for (int i = 0; i < 10; i++) NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, ore, Blocks.IRON_ORE.defaultBlockState(), p));
        int aff2 = master.persona().affinity(p.getUUID());
        int[] done = PlayerTrades.doneForTests(p.getUUID(), StationTask.MINE);
        Kit.log("pc03 ten ore: done " + done[0] + " (liking " + aff1 + " -> " + aff2 + "), title " + PlayerTrades.title(p.getUUID()));
        helper.assertTrue(done[0] == 1 && aff2 > aff1, "the first lesson, and the master warmer still: " + aff1 + " -> " + aff2);
        helper.assertTrue("Apprentice Miner".equals(PlayerTrades.title(p.getUUID())), "an apprentice miner: " + PlayerTrades.title(p.getUUID()));

        // It speaks of its apprentice, and its card names it.
        // (What FolkTalk.answer adds to its ABOUT answer; called alone, the talk screen's other buttons are not sent.)
        String about = PlayerTrades.mention(master, p, TalkTopic.ABOUT, "I dig.");
        String card = PlayerTrades.cardLine(master);
        Kit.log("pc03 about: " + about + " | card: " + card);
        helper.assertTrue(about.contains("coming along") || about.contains("lesson"), "it speaks of the apprentice's progress: " + about);
        helper.assertTrue(card.contains(name), "its card names the apprentice: " + card);
        level.getServer().getPlayerList().remove(p);    // no stand-in player left to despawn other tests' monsters
        helper.succeed();
    }
}
