package com.jrp.floodworld.command;

import com.jrp.floodworld.FloodWorld;
import com.jrp.floodworld.flow.FloodWorldFluids;
import com.jrp.floodworld.sim.FloodEngine;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class FloodWorldCommand {

    private FloodWorldCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("floodworld")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status").executes(FloodWorldCommand::status))
                .then(Commands.literal("pause").executes(FloodWorldCommand::pause))
                .then(Commands.literal("resume").executes(FloodWorldCommand::resume))
                .then(Commands.literal("clear").executes(FloodWorldCommand::clear)));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        boolean raining = false;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.isRaining()) {
                raining = true;
                break;
            }
        }
        boolean active = FloodWorld.flowingFluidsLoaded() && FloodWorldFluids.isModifyingWater();
        int floodCells = FloodEngine.totalFloodCells(server);
        int satColumns = FloodEngine.totalSaturationColumns(server);
        boolean rainingFinal = raining;
        ctx.getSource().sendSuccess(() -> Component.literal("FloodWorld — active: " + active
                + ", raining: " + rainingFinal
                + ", flood cells: " + floodCells
                + ", saturating columns: " + satColumns
                + ", paused: " + FloodEngine.isPaused()), false);
        return 1;
    }

    private static int pause(CommandContext<CommandSourceStack> ctx) {
        FloodEngine.setPaused(true);
        ctx.getSource().sendSuccess(() -> Component.literal("FloodWorld simulation paused."), true);
        return 1;
    }

    private static int resume(CommandContext<CommandSourceStack> ctx) {
        FloodEngine.setPaused(false);
        ctx.getSource().sendSuccess(() -> Component.literal("FloodWorld simulation resumed."), true);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        if (!FloodWorld.flowingFluidsLoaded()) {
            ctx.getSource().sendFailure(Component.literal("FloodWorld is inactive: Flowing Fluids is not present."));
            return 0;
        }
        int removed = FloodEngine.clearAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() ->
                Component.literal("FloodWorld removed " + removed + " mod-placed flood cell(s) in loaded chunks."), true);
        return 1;
    }
}
