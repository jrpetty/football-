package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Every village is its own (ZoneChests.keepOurs): two towns two hundred and twenty blocks apart, each
 * with its stores at its heart, the one's goods all in the west. Asked over a reach that takes in both
 * hearts (a town of fifty looks that far for its stores), each sees its own stores and never the other's,
 * whether the town is counting what it has or a folk of it is looking for something to take; and no
 * goods or coin pass between them outright unless the world is set to let them.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class SeparateVillagesGameTests {

    private static final String EMPTY = "empty";

    private static BlockPos store(ServerLevel level, BlockPos heart) {
        BlockPos at = heart.offset(2, 0, 2);
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        return at;
    }

    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "sv01_separate_stores")
    public static void sv01_separate_stores(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        int ax = 530000, bx = 530220, z = 60000;
        for (int x : new int[] { ax, bx }) {
            Kit.hold(level, x, z, 24);
            Kit.prepare(level, x, z, 24);
        }
        BlockPos a = Kit.surface(level, ax, z), b = Kit.surface(level, bx, z);
        Villages.Village va = Villages.found(level, a), vb = Villages.found(level, b);
        BlockPos ca = store(level, a), cb = store(level, b);
        helper.assertTrue(level.getBlockEntity(ca) instanceof Container, "a chest at the west town's heart");
        Container west = (Container) level.getBlockEntity(ca);
        west.setItem(0, new ItemStack(Items.OAK_LOG, 64));
        west.setItem(1, new ItemStack(Items.BREAD, 32));
        int reach = 260;

        // What each town counts as its own.
        int bLogs = Villages.stock(level, vb.centre(), Villages.Task.LOGS, reach);
        int aLogs = Villages.stock(level, va.centre(), Villages.Task.LOGS, reach);
        Kit.log("sv01 logs counted: west " + aLogs + ", east " + bLogs);
        helper.assertTrue(aLogs >= 64, "the west town counts its own logs: " + aLogs);
        helper.assertTrue(bLogs == 0, "the east town does not count the west town's logs: " + bLogs);

        // What a folk of each town can see to take, looking from where it stands.
        List<BlockPos> eastSees = seen(level, vb.id(), b.offset(5, 0, 0), reach);
        List<BlockPos> westSees = seen(level, va.id(), a.offset(-5, 0, 0), reach);
        Kit.log("sv01 an east folk sees " + eastSees + "; a west folk sees " + westSees);
        helper.assertTrue(eastSees.contains(cb) && !eastSees.contains(ca), "an east folk sees its own stores and not the west's");
        helper.assertTrue(westSees.contains(ca) && !westSees.contains(cb), "a west folk sees its own stores and not the east's");
        // Even standing in the west town (a caravan's driver, an envoy), an east folk's own question is the east's.
        List<BlockPos> visiting = seen(level, vb.id(), a.offset(8, 0, 8), 24);
        helper.assertTrue(!visiting.contains(ca), "an east folk visiting the west takes nothing from its stores: " + visiting);
        helper.assertTrue(ZoneChests.anotherVillages(level, vb.centre(), ca), "the west chest is another village's to the east");

        helper.assertTrue(!AssistantConfig.villagesShareGoods(), "villages send each other nothing outright unless the world says so");
        helper.succeed();
    }

    private static List<BlockPos> seen(ServerLevel level, UUID village, BlockPos from, int reach) {
        boolean settler = ZoneChests.askAs(true);
        UUID before = ZoneChests.askFor(village);
        try {
            return ZoneChests.around(level, from, reach, 32).stream().map(ZoneChests.Found::pos).toList();
        } finally {
            ZoneChests.askAs(settler);
            ZoneChests.askFor(before);
        }
    }
}
