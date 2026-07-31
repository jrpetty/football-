package com.jrpetty.mazerunner;

import com.jrpetty.mazerunner.config.MazeConfigData;
import com.jrpetty.mazerunner.config.MazeConfigs;
import com.jrpetty.mazerunner.config.RunScoring;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
                .then(Commands.literal("leaderboard").executes(ctx -> leaderboard(ctx.getSource())))
                .then(Commands.literal("top").executes(ctx -> leaderboard(ctx.getSource())))
                .then(Commands.literal("race")
                    .then(Commands.literal("start").executes(ctx -> raceStart(ctx.getSource())))
                    .then(Commands.literal("stop").executes(ctx -> raceStop(ctx.getSource()))))
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

    /** Runs start by themselves when you leave the Glade; this restarts yours. */
    private static int start(CommandSourceStack source) {
        ServerLevel level = maze(source);
        if (level == null) return 0;
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only a player has a run."));
            return 0;
        }
        MazeWorldState state = MazeWorldState.get(level);
        state.abandonRun(player.getUUID());
        state.startRun(player.getUUID(), System.currentTimeMillis());
        source.sendSuccess(() -> Component.literal("⏱ Your run has been restarted from zero.")
            .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        ServerLevel level = maze(source);
        if (level == null) return 0;
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only a player has a run."));
            return 0;
        }
        MazeWorldState state = MazeWorldState.get(level);
        if (!state.isRunning(player.getUUID())) {
            source.sendFailure(Component.literal("You have no run in progress."));
            return 0;
        }
        state.abandonRun(player.getUUID());
        source.sendSuccess(() -> Component.literal("⏱ Run abandoned — nothing recorded.")
            .withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    /** Starts a synchronised race for everyone online. */
    private static int raceStart(CommandSourceStack source) {
        ServerLevel level = maze(source);
        if (level == null) return 0;
        MazeWorldState state = MazeWorldState.get(level);
        if (state.raceRunning()) {
            source.sendFailure(Component.literal(
                "A race is already running — /maze race stop to cancel it."));
            return 0;
        }
        int racers = MazeRuntime.beginRace(level);
        source.sendSuccess(() -> Component.literal(
            "Race started with " + racers + " runner" + (racers == 1 ? "" : "s") + ".")
            .withStyle(ChatFormatting.GOLD), true);
        return racers;
    }

    private static int raceStop(CommandSourceStack source) {
        ServerLevel level = maze(source);
        if (level == null) return 0;
        MazeWorldState state = MazeWorldState.get(level);
        if (!state.raceRunning()) {
            source.sendFailure(Component.literal("No race is running."));
            return 0;
        }
        state.cancelRace();
        source.sendSuccess(() -> Component.literal("Race cancelled — nothing recorded.")
            .withStyle(ChatFormatting.YELLOW), true);
        return 1;
    }

    private static int leaderboard(CommandSourceStack source) {
        ServerLevel level = maze(source);
        if (level == null) return 0;
        var board = MazeWorldState.get(level).leaderboard(10);
        if (board.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                "Nobody has escaped the Maze yet.").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        StringBuilder sb = new StringBuilder("🏆 Fastest escapes");
        MazeWorldState st = MazeWorldState.get(level);
        if (st.raceBestMillis() >= 0) {
            sb.append("\n Race record: ").append(RunScoring.format(st.raceBestMillis()))
                .append(" by ").append(st.raceBestHolder());
        }
        int rank = 1;
        for (var entry : board) {
            sb.append("\n ").append(rank++).append(". ").append(entry.name())
                .append(" — ").append(RunScoring.format(entry.finalMillis()));
            if (entry.deaths() > 0) {
                sb.append(" (").append(entry.deaths()).append(" death")
                    .append(entry.deaths() == 1 ? "" : "s").append(")");
            }
        }
        String text = sb.toString();
        source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GOLD), false);
        return board.size();
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
        String own = "not running — leave the Glade to start one";
        if (source.getEntity() instanceof ServerPlayer player
            && state.isRunning(player.getUUID())) {
            long elapsed = state.runElapsed(player.getUUID(), System.currentTimeMillis());
            int deaths = state.runDeaths(player.getUUID());
            own = RunScoring.format(elapsed) + " on the clock, " + deaths + " death"
                + (deaths == 1 ? "" : "s")
                + " (final would be " + RunScoring.format(
                    RunScoring.finalMillis(elapsed, deaths, MazeConfig.deathPenaltyMillis())) + ")";
        }
        long worldBest = state.worldBestMillis();
        String timer = own + (worldBest >= 0
            ? " · world best " + RunScoring.format(worldBest) : " · no escapes yet");

        long raceElapsed = state.raceElapsed(System.currentTimeMillis());
        String race = raceElapsed >= 0
            ? "RUNNING — " + RunScoring.format(raceElapsed) + " elapsed"
            : "none";
        if (state.raceBestMillis() >= 0) {
            race += " · record " + RunScoring.format(state.raceBestMillis())
                + " by " + state.raceBestHolder();
        }
        String raceLine = race;

        source.sendSuccess(() -> Component.literal(String.join("\n",
            "Maze — day " + state.dayNumber() + ", layout " + layout.name()
                + " · fixed exit " + cfg.fixedExitId + " (only walls change)",
            "Clock: " + t + "/24000 (" + (t < 12000 ? "day" : "night") + ") · doors "
                + (state.doorsOpen() ? "OPEN" : "sealed") + " · wall animations queued: "
                + WallAnimator.queuedCount(),
            "Grievers loaded: " + MazeRuntime.countGrievers(level),
            "Your run: " + timer,
            "Race: " + raceLine,
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
        if (!(source.getEntity() instanceof ServerPlayer player)) {
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
        if (!(source.getEntity() instanceof ServerPlayer player)) {
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
