package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /village — the blunt tool for standing a settlement up and asking how it
 * is doing. Always registered: this is what you reach for when the item or
 * the block is not behaving, so it must not itself be behind a switch.
 *
 * <pre>
 *   /village spawn            one settler where you stand
 *   /village spawn 12         twelve — a full starting village
 *   /village status           who lives here, what age, what they are short of
 * </pre>
 */
public final class VillageCommands {

    private VillageCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("spawn")
                .executes(ctx -> spawn(ctx, 1))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                    .executes(ctx -> spawn(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
            // For the console and for scripts, where there is no player to
            // stand near: /village spawnat <x> <z> [count]
            .then(Commands.literal("spawnat")
                .then(Commands.argument("x", IntegerArgumentType.integer())
                    .then(Commands.argument("z", IntegerArgumentType.integer())
                        .executes(ctx -> spawnAt(ctx, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                            .executes(ctx -> spawnAt(ctx,
                                IntegerArgumentType.getInteger(ctx, "count")))))))
            .then(Commands.literal("folk").executes(VillageCommands::folk))
            .then(Commands.literal("list").executes(VillageCommands::list))
            .then(Commands.literal("anchors").requires(src -> src.hasPermission(2))
                .executes(VillageCommands::anchors))
            .then(Commands.literal("status").executes(VillageCommands::status)));
    }

    /** The free ground-level spot a couple of blocks ahead of this position. */
    private static net.minecraft.core.BlockPos groundAt(net.minecraft.server.level.ServerLevel level,
                                                        int x, int z) {
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new net.minecraft.core.BlockPos(x, y, z);
    }

    private static int spawnAt(CommandContext<CommandSourceStack> ctx, int count) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        level.getChunk(x >> 4, z >> 4);          // make sure the ground is really there
        return raiseMany(ctx, level, groundAt(level, x, z), 0.0F, count);
    }

    private static int raiseMany(CommandContext<CommandSourceStack> ctx,
                                 net.minecraft.server.level.ServerLevel level,
                                 net.minecraft.core.BlockPos ground, float yaw, int count) {
        int stood = 0;
        for (int i = 0; i < count; i++) {
            // Scattered, not stacked. A hundred folk stood up on one square make a
            // crowd, and a crowd of more than twenty-four in one place is crushed
            // by the game's own entity-cramming rule: a hundred settlers on a
            // real-terrain server were seventy-eight by the end of their first
            // minute. A sunflower spiral (the golden angle) puts each on ground of
            // its own, about a block and a half apart, out to a dozen blocks.
            net.minecraft.core.BlockPos at = ground;
            if (i > 0) {
                double angle = i * 2.399963229728653;
                double reach = 1.5 + 1.1 * Math.sqrt(i);
                int x = ground.getX() + (int) Math.round(Math.cos(angle) * reach);
                int z = ground.getZ() + (int) Math.round(Math.sin(angle) * reach);
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null) at = groundAt(level, x, z);
            }
            VillageFolkEntity folk = VillageFolkSpawnerBlock.raise(level, at, yaw);
            if (folk == null) break;
            stood++;
        }
        if (stood == 0) {
            ctx.getSource().sendFailure(Component.literal(
                "Nobody could be settled here — the village is at its cap."));
            return 0;
        }
        Villages.Village v = Villages.nearest(level, ground, Villages.VILLAGE_RANGE * 2);
        final int raised = stood;
        final int total = v == null ? stood : Villages.headcount(v.id());
        ctx.getSource().sendSuccess(() -> Component.literal(
            "Stood " + raised + " up. This village is now " + total + " strong."), false);
        return stood;
    }

    /** One line per folk of the nearest village: who, doing what, and why not more. */
    private static int folk(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.core.BlockPos here = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, here, Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("No village yet."), false);
            return 0;
        }
        java.util.List<AssistantEntity> crew = Villages.folkOf(v.id());
        ctx.getSource().sendSuccess(() -> Component.literal(
            crew.size() + " folk of the village at " + v_centre(crew, level)), false);
        for (AssistantEntity a : crew) {
            final String line = a.debugLine();
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return crew.size();
    }

    private static String v_centre(java.util.List<AssistantEntity> crew,
                                   net.minecraft.server.level.ServerLevel level) {
        if (crew.isEmpty()) return "?";
        Villages.Village v = Villages.get(crew.get(0).ownerId());
        return v == null ? "?" : v.centre().getX() + ", " + v.centre().getZ();
    }

    /** Where this world will found villages of its own near here. */
    private static int anchors(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.core.BlockPos here =
            net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        java.util.List<net.minecraft.core.BlockPos> sites =
            com.jrpetty.mcassistant.VillageSpawner.anchorsNear(level, here, 12);
        if (sites.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No village sites within reach."), false);
            return 0;
        }
        int shown = 0;
        for (net.minecraft.core.BlockPos p : sites) {
            if (shown++ >= 8) break;
            final String line = "Village site at " + p.getX() + ", " + p.getZ()
                + " (" + (int) Math.sqrt(p.distSqr(new net.minecraft.core.BlockPos(here.getX(), p.getY(), here.getZ())))
                + " blocks away" + (AssistantConfig.naturalVillages() ? "" : "; natural villages are OFF") + ")";
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return sites.size();
    }

    /** Every village the game knows of, one line each — where they are, how many
     *  live there, how far along they are. */
    private static int list(CommandContext<CommandSourceStack> ctx) {
        java.util.List<Villages.Village> all = Villages.every();
        if (all.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No villages yet."), false);
            return 0;
        }
        for (Villages.Village v : all) {
            final String line = "Village at " + v.centre().getX() + ", " + v.centre().getZ()
                + " — " + Villages.headcount(v.id()) + " folk (" + Villages.loadedCount(v.id())
                + " loaded), " + Villages.ageOf(v.id()).label + ", built " + Villages.builtList(v.id());
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return all.size();
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, int count) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal(
                "Only a player can do that — from the console use /village spawnat <x> <z> [count]."));
            return 0;
        }
        // A couple of blocks AHEAD of the player, on the ground — never on
        // top of them, and never at head height where a chest would go.
        net.minecraft.core.BlockPos ahead = player.blockPosition().relative(player.getDirection(), 2);
        return raiseMany(ctx, player.serverLevel(), groundAt(player.serverLevel(), ahead.getX(), ahead.getZ()),
            player.getYRot(), count);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        // From wherever the command was run — a player, a command block, or
        // the console (which has no player and used to get NOTHING back).
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.core.BlockPos here =
            net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, here, Villages.VILLAGE_RANGE * 4);
        if (v == null && ctx.getSource().getPlayer() == null && !Villages.every().isEmpty()) {
            v = Villages.every().get(0);
        }
        if (v == null) {
            ctx.getSource().sendSuccess(() -> Component.literal("No village within reach."), false);
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Village at ").append(v.centre().getX()).append(", ").append(v.centre().getZ())
          .append(" — ").append(Villages.headcount(v.id())).append(" folk (")
          .append(Villages.loadedCount(v.id())).append(" loaded), ")
          .append(Villages.ageOf(v.id()).label).append('.');
        java.util.Map<AssistantEntity.StationTask, Integer> trades =
            new java.util.EnumMap<>(AssistantEntity.StationTask.class);
        int idle = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a.stationTask() == AssistantEntity.StationTask.NONE) idle++;
            else trades.merge(a.stationTask(), 1, Integer::sum);
        }
        sb.append(" Trades:");
        trades.forEach((t, n) -> sb.append(' ').append(n).append(' ').append(t.title.toLowerCase()));
        if (idle > 0) sb.append(", ").append(idle).append(" still choosing");
        sb.append(". Stores:");
        for (Villages.Task t : Villages.Task.values()) {
            if (t == Villages.Task.BUILD || t == Villages.Task.HANDS || t == Villages.Task.NONE) continue;
            sb.append(' ').append(t.name().toLowerCase()).append(' ').append(
                Villages.stock(level, v.centre(), t, Villages.storesRadius(v.id())));
        }
        sb.append(". Built: ").append(Villages.builtList(v.id()));
        sb.append(". Room for ").append(Villages.housing(v.id()));
        sb.append(". Growing: ").append(Villages.growthNote(level, v.id()));
        String next = Villages.nextProject(v.id());
        sb.append(". Next: ").append(Villages.whyBuild(v.id(), next));
        sb.append(". Short of:");
        java.util.List<Villages.Need> needs = Villages.needs(level, v.id());
        if (needs.isEmpty()) sb.append(" nothing — about to come of age.");
        for (Villages.Need n : needs) sb.append(' ').append(n.what()).append(';');
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }
}
