package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.pa.PaConfig;
import com.jrpetty.mcassistant.pa.PaData;
import com.jrpetty.mcassistant.pa.PaLife;
import com.jrpetty.mcassistant.pa.PaServer;
import com.jrpetty.mcassistant.pa.PlayerAssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Set;

/**
 * [tree] The Player AI Assistant fells a tree to the last log before it starts on the next, like a player: no floating
 * crowns, no forest of stumps with their tops left in the air. Asked for fewer logs than the tree holds, it finishes the
 * tree anyway. Only when the rest of a tree can't be had (here, a treehouse in its crown: somebody's build) does it
 * leave it and go on to the next.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PaTreeGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;

    private static int ground(ServerLevel level, int x) {
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        PaConfig.perPlayerForTests(null);
        PaConfig.respawnSecondsForTests(null);
        PaConfig.ladderForTests(false, null);
        level.setDayTime(2000);
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        Kit.live(level, x, Z, 40);
        int y = Kit.surface(level, x, Z).getY();
        for (int dx = -36; dx <= 36; dx++) {
            for (int dz = -36; dz <= 36; dz++) {
                for (int dy = 0; dy < 14; dy++) level.setBlock(new BlockPos(x + dx, y + dy, Z + dz), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x + dx, y - 1, Z + dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            }
        }
        return y;
    }

    private static ServerPlayer player(GameTestHelper helper, ServerLevel level, BlockPos at) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        p.teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, Set.of(), 0.0F, 0.0F);
        return p;
    }

    private static PlayerAssistantEntity assistant(GameTestHelper helper, ServerPlayer p) {
        PaServer.act(p, "summon", new CompoundTag());
        PlayerAssistantEntity a = PaLife.find(p);
        helper.assertTrue(a != null, "an assistant came");
        PaData.Rec r = a.rec();
        r.confirm = false;
        r.autoHelp = false;
        a.setPaXpForTests(0);
        return a;
    }

    private static void say(ServerPlayer p, PlayerAssistantEntity a, String text) {
        CompoundTag t = new CompoundTag();
        t.putString("text", text);
        PaServer.act(p, "talk", t);
    }

    private static String allWords(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        StringBuilder b = new StringBuilder();
        if (r != null) for (PaData.Line l : r.log) b.append(l.who()).append(':').append(l.text()).append(" | ");
        return b.toString();
    }

    /** An oak: a trunk h high and a crown of (kept) leaves round its top. */
    private static void tree(ServerLevel level, BlockPos base, int h) {
        for (int i = 0; i < h; i++) level.setBlock(base.above(i), Blocks.OAK_LOG.defaultBlockState(), 3);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = h - 2; dy <= h; dy++) {
            BlockPos l = base.offset(dx, dy, dz);
            if (level.getBlockState(l).isAir()) level.setBlock(l, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), 3);
        }
    }

    private static int logsIn(ServerLevel level, BlockPos base, int h, int spread) {
        int n = 0;
        for (BlockPos q : BlockPos.betweenClosed(base.offset(-spread, 0, -spread), base.offset(spread, h + 1, spread)))
            if (level.getBlockState(q).is(net.minecraft.tags.BlockTags.LOGS)) n++;
        return n;
    }

    private static int logsHeld(PlayerAssistantEntity a) {
        return a.countMatching(s -> s.is(net.minecraft.tags.ItemTags.LOGS));
    }

    /**
     * pat01: two oaks side by side; "gather 3 logs". The old way took the first tree's bottom two logs and then the next
     * tree's bottom one (the nearest log each time), leaving two trees with their tops in the air. Now: the whole of the
     * first tree (its branch too), and the second untouched.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "pat01_whole_tree")
    public static void pat01_whole_tree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_950_000, y = ground(level, x);
        BlockPos a1 = new BlockPos(x + 3, y, Z), b1 = new BlockPos(x + 7, y, Z);
        tree(level, a1, 6);
        level.setBlock(a1.offset(-1, 4, 0), Blocks.OAK_LOG.defaultBlockState(), 3);      // a branch, joined at the side
        level.setBlock(a1.offset(-2, 5, 1), Blocks.OAK_LOG.defaultBlockState(), 3);      // and out from that, corner to corner
        tree(level, b1, 6);
        int aLogs = logsIn(level, a1, 6, 2), bLogs = logsIn(level, b1, 6, 1);
        helper.assertTrue(aLogs == 8 && bLogs == 6, "the trees stand: " + aLogs + " and " + bLogs);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.insertGiven(new ItemStack(Items.IRON_AXE));
        say(p, a, "gather 3 logs");
        helper.succeedWhen(() -> {
            helper.assertTrue(a.plan.idle(), "done (" + a.state() + "; " + logsHeld(a) + " logs)");
            int leftA = logsIn(level, a1, 6, 2), leftB = logsIn(level, b1, 6, 1);
            helper.assertTrue(leftA == 0, "the first tree is all down, branch and all: " + leftA + " left");
            helper.assertTrue(leftB == 6, "the next tree is untouched: " + leftB + " of 6");
            helper.assertTrue(logsHeld(a) >= 8, "it has the whole tree's logs: " + logsHeld(a));
            Kit.log("pat01 took " + logsHeld(a) + " logs for 3 asked; said " + allWords(a));
        });
    }

    /**
     * pat02: the first oak has a treehouse platform in its crown (planks: somebody's build), so its top can't be had. It
     * takes the trunk up to below the platform, goes on to the next tree for the rest, and finishes that tree.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4500, batch = "pat02_cant_finish")
    public static void pat02_cant_finish(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_955_000, y = ground(level, x);
        BlockPos a1 = new BlockPos(x + 3, y, Z), b1 = new BlockPos(x + 9, y, Z);
        tree(level, a1, 10);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
            if (dx != 0 || dz != 0) level.setBlock(a1.offset(dx, 7, dz), Blocks.OAK_PLANKS.defaultBlockState(), 3);
        tree(level, b1, 6);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.insertGiven(new ItemStack(Items.IRON_AXE));
        say(p, a, "gather 8 logs");
        helper.succeedWhen(() -> {
            helper.assertTrue(a.plan.idle(), "done (" + a.state() + "; " + logsHeld(a) + " logs)");
            int topA = 0, lowA = 0;
            for (int i = 0; i < 10; i++) {
                boolean log = level.getBlockState(a1.above(i)).is(net.minecraft.tags.BlockTags.LOGS);
                if (log && i >= 5) topA++;
                if (log && i < 5) lowA++;
            }
            int leftB = logsIn(level, b1, 6, 1);
            helper.assertTrue(topA == 5, "the treehouse's part of the trunk is left alone: " + topA + " of 5");
            helper.assertTrue(lowA == 0, "what could be had of the first tree was had: " + lowA + " left low down");
            helper.assertTrue(leftB == 0, "the next tree is finished, not left with its top in the air: " + leftB + " left");
            helper.assertTrue(logsHeld(a) >= 8, "it has the count: " + logsHeld(a));
            Kit.log("pat02 took " + logsHeld(a) + " logs for 8 asked; said " + allWords(a));
        });
    }
}
