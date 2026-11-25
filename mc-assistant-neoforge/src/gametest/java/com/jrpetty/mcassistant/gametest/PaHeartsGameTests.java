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
 * [hearts] The Player AI Assistant's hearts are its level's alone: ten at level 1, twenty at level 10, whatever trait it
 * rolled. (A sturdy one had a village folk's extra heart on top: twenty-two health at level 1.)
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PaHeartsGameTests {


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

    /** pah01: a sturdy assistant: ten hearts at level 1 and twenty at level 10, no extra heart for the trait. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pah01_sturdy_hearts")
    public static void pah01_sturdy_hearts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 7_965_000, y = ground(level, x);
        ServerPlayer p = player(helper, level, new BlockPos(x, y, Z));
        PlayerAssistantEntity a = assistant(helper, p);
        a.traitForTests(com.jrpetty.mcassistant.entity.AssistantEntity.Trait.STURDY);
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(a.veteranLevel() == 1, "level 1: " + a.veteranLevel());
            helper.assertTrue(Math.abs(a.getMaxHealth() - 20.0F) < 0.01F, "ten hearts at level 1, sturdy or not: " + a.getMaxHealth());
            a.setPaXpForTests(com.jrpetty.mcassistant.pa.PaLevels.xpFor(10));
            a.traitForTests(com.jrpetty.mcassistant.entity.AssistantEntity.Trait.STURDY);
            helper.runAfterDelay(20, () -> {
                helper.assertTrue(a.veteranLevel() == 10, "level 10: " + a.veteranLevel());
                helper.assertTrue(Math.abs(a.getMaxHealth() - 40.0F) < 0.01F, "twenty hearts at level 10, sturdy or not: " + a.getMaxHealth());
                a.setPaXpForTests(0);
                helper.succeed();
            });
        });
    }
}
