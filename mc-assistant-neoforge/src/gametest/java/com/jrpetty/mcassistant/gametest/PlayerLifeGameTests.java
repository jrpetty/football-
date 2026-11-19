package com.jrpetty.mcassistant.gametest;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PlayerLifeGameTests {

    @GameTest(template = "empty", timeoutTicks = 100, batch = "pl00_dump")
    public static void pl00_dump(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        CommandSourceStack src = p.createCommandSourceStack().withPermission(0);
        List<String> out = new ArrayList<>();
        for (CommandNode<CommandSourceStack> n : level.getServer().getCommands().getDispatcher().getRoot().getChildren()) {
            if (!(n instanceof LiteralCommandNode)) continue;
            String name = n.getName();
            if (!name.equals("village") && !name.equals("assistant") && !name.equals("emotecape")) continue;
            walk(n, src, "/" + name, out, 0);
        }
        for (String s : out) Kit.log("[CMD] " + s);
        helper.succeed();
    }

    private static void walk(CommandNode<CommandSourceStack> n, CommandSourceStack src, String path, List<String> out, int depth) {
        if (!n.canUse(src)) return;
        if (n.getCommand() != null) out.add(path);
        if (depth >= 2) { if (!n.getChildren().isEmpty()) out.add(path + " ..."); return; }
        for (CommandNode<CommandSourceStack> c : n.getChildren()) {
            String seg = c instanceof LiteralCommandNode ? c.getName() : "<" + c.getName() + ">";
            walk(c, src, path + " " + seg, out, depth + 1);
        }
    }
}
