package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.GuideFacts;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * [guide31] The Guide's few facts about the player (entity/GuideFacts), as the server writes them for the client: in a
 * town, its name and what the player is to it, not a citizen and no town of their own; made a citizen, both say so; out
 * in the wild, no town at all. (The Guide's screen, its search and its book are the client's and the unit tests': the
 * inventory's Guide button is shown in the client's screenshots.)
 *
 * <p>On its own ground, x 6,300,000 on z 60,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class GuideGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 60000;

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "gd01_facts")
    public static void gd01_facts(GameTestHelper helper) {
        int x = 6_300_000;
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        Kit.hold(level, x, Z, 32);
        Kit.prepare(level, x, Z, 32);
        BlockPos heart = Kit.surface(level, x, Z);
        for (int i = 0; i < 4; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart.east(i * 2 - 3), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.setNoAi(true);
        }
        helper.runAtTickTime(5, () -> {
            Villages.Village v = Villages.nearest(level, heart, 64);
            helper.assertTrue(v != null, "a town at the heart");
            @SuppressWarnings("removal")
            ServerPlayer p = helper.makeMockServerPlayerInLevel();
            p.moveTo(heart.getX() + 0.5, heart.getY(), heart.getZ() + 2.5);
            String line = GuideFacts.line(p);
            Kit.log("[VT] gd01 in town: " + line);
            helper.assertTrue(line.contains("town=" + Villages.name(v.id()).replace(';', ',')), "the town's name: " + line);
            helper.assertTrue(line.contains("title=a stranger"), "a stranger to it: " + line);
            helper.assertTrue(line.contains("citizen=0") && line.contains("own=0") && line.contains("shop=0") && line.contains("firms=0"),
                "no citizen, town, shop or firm yet: " + line);
            Ledger.addCitizen(v.id(), p.getUUID(), p.getName().getString());
            String citizen = GuideFacts.line(p);
            Kit.log("[VT] gd01 a citizen: " + citizen);
            helper.assertTrue(citizen.contains("citizen=1") && citizen.contains("own=1"), "a citizen has a town of its own: " + citizen);
            p.moveTo(heart.getX() + 3000.5, heart.getY(), heart.getZ() + 0.5);
            String wild = GuideFacts.line(p);
            Kit.log("[VT] gd01 in the wild: " + wild);
            helper.assertTrue(!wild.contains("town=") && wild.contains("own=1"), "out in the wild, no town, its own still: " + wild);
            helper.assertTrue(GuideFacts.TITLE.equals("#guide") && GuideFacts.ASK == 5, "the packet's markers the client looks for");
            helper.succeed();
        });
    }
}
