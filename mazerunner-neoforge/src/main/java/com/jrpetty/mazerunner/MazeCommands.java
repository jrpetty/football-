package com.jrpetty.mazerunner;

import com.jrpetty.mazerunner.config.MazeConfigData;
import com.jrpetty.mazerunner.config.MazeConfigs;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /maze start                 — start the server-wide escape timer
 * /maze stop                  — stop it manually
 * /maze status                — day, layout, doors, timer, schedule
 * /maze validate <1-7>        — BFS-check a layout is solvable (debug)
 * /maze section               — which of the 8 maze sections you're in
 * /maze skip                  — jump to next dawn (debug; runs dusk/shift on the way)
 */
public final class MazeCommands {

    private MazeCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("maze")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("start").executes(ctx -> start(ctx.getSource())))
                .then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource())))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("section").executes(ctx -> section(ctx.getSource())))
                .then(Commands.literal("skip").executes(ctx -> skip(ctx.getSource())))
                .then(Commands.literal("tp").executes(ctx -> tpExit(ctx.getSource())))
                .then(Commands.literal("endday").executes(ctx -> skip(ctx.getSource())))
                .then(Commands.literal("morning").executes(ctx -> skip(ctx.getSource())))
                .then(Commands.literal("night").executes(ctx -> night(ctx.getSource())))
                .then(Commands.literal("shift").executes(ctx -> forceShift(ctx.getSource())))
                .then(Commands.literal("griever").executes(ctx -> spawnGriever(ctx.getSource())))
                .then(Commands.literal("validate")
                    .then(Commands.argument("layout", IntegerArgumentType.integer(1, 7))
                        .executes(ctx -> validate(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "layout"))))));
    }

    private static ServerLevel maze(CommandSourceStack source) {
        ServerLevel level = source.getServer().getLevel(Level.OVERWORLD);
        if (level == null || !MazeRuntime.isMazeLevel(level)) {
            source.sendFailure(Component.literal(
                "This world is not a Maze Runner world — create one with the \"Maze Runner\" world type."));
            return null;
        }
        return level;
    }

    private static int start(CommandSourceStack source) {
        ServerLevel level = maze(source);
        if (level == null) return 0;
        MazeWorldState state = MazeWorldState.get(level);
        if (state.timerRunning()) {
            source.sendFailure(Component.literal("The escape timer is already running."));
            return 0;
        }
        state.startTimer(System.currentTimeMillis());
        source.sendSuccess(() -> Component.literal(
            "⏱ Escape timer started — first runner through the active exit portal stops it.")
            .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        ServerLevel level = maze(source);
        if (level == null) return 0;
        MazeWorldState state = MazeWorldState.get(level);
        if (!state.timerRunning()) {
            source.sendFailure(Component.literal("No escape timer is running."));
            return 0;
        }
        long elapsed = state.stopTimer(System.currentTimeMillis());
        source.sendSuccess(() -> Component.literal(
            "⏱ Escape timer stopped at " + MazeRuntime.formatMs(elapsed) + ".")
            .withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        ServerLevel level = maze(source);
        if (level == null) return 0;
        MazeConfigData cfg = MazeConfigs.get();
        MazeWorldState state = MazeWorldState.get(level);
        MazeConfigData.LayoutDef layout = cfg.layout(state.physicalLayout());
        int t = (int) ((state.virtualSixths() / 6) % 24000);

        StringBuilder schedule = new StringBuilder();
        int[] order = state.schedule();
        for (int i = 0; i < order.length; i++) {
            if (i > 0) schedule.append(" → ");
            schedule.append(cfg.layout(order[i]).name());
        }
        String timerBase = state.timerRunning()
            ? "running (" + MazeRuntime.formatMs(System.currentTimeMillis() - state.timerStartMs()) + ")"
            : state.lastRunMs() >= 0 ? "stopped — last run " + MazeRuntime.formatMs(state.lastRunMs()) : "stopped";
        String timer = state.bestRunMs() >= 0
            ? timerBase + " · best " + MazeRuntime.formatMs(state.bestRunMs())
            : timerBase; // effectively final for the lambda below

        source.sendSuccess(() -> Component.literal(String.join("\n",
            "Maze — day " + state.dayNumber() + ", layout " + layout.name()
                + " · fixed exit " + cfg.fixedExitId + " (only walls change)",
            "Clock: " + t + "/24000 (" + (t < 12000 ? "day" : "night") + ") · doors "
                + (state.doorsOpen() ? "OPEN" : "sealed") + " · wall animations queued: "
                + WallAnimator.queuedCount(),
            "Grievers loaded: " + MazeRuntime.countGrievers(level),
            "Timer: " + timer,
            "Week schedule: " + schedule)).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int validate(CommandSourceStack source, int layoutNumber) {
        MazeConfigData cfg = MazeConfigs.get();
        int dist = cfg.validate(layoutNumber - 1);
        MazeConfigData.LayoutDef layout = cfg.layout(layoutNumber - 1);
        if (dist < 0) {
            source.sendFailure(Component.literal(
                layout.name() + " is NOT solvable — no path Glade → " + cfg.fixedExitId + "!"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
            layout.name() + " solvable: Glade → fixed exit " + cfg.fixedExitId + " in " + dist
                + " cells (~" + dist * cfg.cellSize + " blocks).").withStyle(ChatFormatting.GREEN), false);
        return dist;
    }

    private static int section(CommandSourceStack source) {
        ServerLevel level = maze(source);
        if (level == null) return 0;
        MazeConfigData cfg = MazeConfigs.get();
        BlockPos pos = BlockPos.containing(source.getPosition());
        int cellX = Math.floorDiv(pos.getX(), cfg.cellSize);
        int cellZ = Math.floorDiv(pos.getZ(), cfg.cellSize);
        String where = !cfg.inGrid(cellX, cellZ) ? "outside the Maze"
            : cfg.inGlade(cellX, cellZ) ? "the Glade"
            : "Section " + cfg.sectionOf(pos.getX(), pos.getZ());
        source.sendSuccess(() -> Component.literal(
            "You are in " + where + " (cell " + cellX + "," + cellZ + ").")
            .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int tpExit(CommandSourceStack source) {
        ServerLevel level = maze(source);
        if (level == null) return 0;
        if (!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            source.sendFailure(Component.literal("Only a player can teleport."));
            return 0;
        }
        MazeConfigData cfg = MazeConfigs.get();
        MazeConfigData.ExitDef exit = cfg.fixedExit();
        player.teleportTo(level, exit.portalX() + 0.5, cfg.floorY + 2, exit.portalZ() + 0.5,
            player.getYRot(), player.getXRot());
        source.sendSuccess(() -> Component.literal(
            "Teleported to the fixed exit (" + exit.id() + ", facing " + exit.facing() + ").")
            .withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    private static int skip(CommandSourceStack source) {
        ServerLevel level = maze(source);
        if (level == null) return 0;
        MazeWorldState state = MazeWorldState.get(level);
        MazeRuntime.jumpToDayTick(level, state, 1100); // next morning: dusk seal → shift → dawn → doors open
        source.sendSuccess(() -> Component.literal(
            "Skipped to morning of day " + state.dayNumber()
                + " — the doors sealed, the maze reshaped overnight, and the doors are open again.")
            .withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    private static int night(CommandSourceStack source) {
        ServerLevel level = maze(source);
        if (level == null) return 0;
        MazeWorldState state = MazeWorldState.get(level);
        MazeRuntime.jumpToDayTick(level, state, 12600); // just past dusk — doors seal
        source.sendSuccess(() -> Component.literal(
            "Skipped to nightfall — the Glade doors are sealing. The maze reshapes at deep night.")
            .withStyle(ChatFormatting.DARK_PURPLE), true);
        return 1;
    }

    private static int spawnGriever(CommandSourceStack source) {
        ServerLevel level = maze(source);
        if (level == null) return 0;
        if (!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            source.sendFailure(Component.literal("Only a player can summon a Griever nearby."));
            return 0;
        }
        if (!MazeRuntime.spawnGrieverNear(level, player)) {
            source.sendFailure(Component.literal(
                "No open corridor nearby to spawn a Griever — try deeper in the Maze."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("A Griever stirs in the corridors nearby…")
            .withStyle(ChatFormatting.DARK_PURPLE), true);
        return 1;
    }

    private static int forceShift(CommandSourceStack source) {
        ServerLevel level = maze(source);
        if (level == null) return 0;
        MazeWorldState state = MazeWorldState.get(level);
        MazeRuntime.jumpToDayTick(level, state, 18000); // deep night — seals doors and moves the walls
        source.sendSuccess(() -> Component.literal(
            "Forcing the overnight shift — the doors seal and the walls are moving now.")
            .withStyle(ChatFormatting.DARK_AQUA), true);
        return 1;
    }
}
