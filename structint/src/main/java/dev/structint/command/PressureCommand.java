package dev.structint.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.structint.Config;
import dev.structint.StructuralIntegrityMod;
import dev.structint.world.WaterPressure;
import dev.structint.world.WaterPressureScanner;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Telling a player whether a wall is going to hold, before the water tells them.
 *
 * <ul>
 *   <li>{@code /structint pressure} — the full sum for the block you are looking at.</li>
 *   <li>{@code /structint pressure survey} — paint every loaded block around you by how hard it is
 *       working, and report the worst one.</li>
 * </ul>
 */
@EventBusSubscriber(modid = "structint")
public final class PressureCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // Registration must never take the game down with it: an inspection command is a
        // convenience, and the simulation works without it.
        try {
            event.getDispatcher().register(Commands.literal("structint")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.literal("pressure")
                            .executes(PressureCommand::inspect)
                            .then(Commands.literal("survey").executes(PressureCommand::survey))));
        } catch (Throwable t) {
            StructuralIntegrityMod.LOGGER.error(
                    "structint: could not register /structint pressure; the simulation is unaffected", t);
        }
    }

    // ------------------------------------------------------------------ inspect

    private static int inspect(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        try {
            return inspect0(ctx);
        } catch (CommandSyntaxException e) {
            throw e;
        } catch (Throwable t) {
            StructuralIntegrityMod.LOGGER.error("structint: /structint pressure failed", t);
            ctx.getSource().sendFailure(Component.literal("Could not read the pressure here; see the log."));
            return 0;
        }
    }

    private static int inspect0(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BlockPos pos = lookingAt(player);
        if (pos == null) {
            ctx.getSource().sendFailure(Component.literal("Look at a block within 12 blocks first."));
            return 0;
        }
        if (!Config.HYDRO_ENABLE.get()) {
            ctx.getSource().sendFailure(Component.literal("Water pressure is disabled in the server config."));
            return 0;
        }

        ServerLevel level = player.serverLevel();
        WaterPressure.Reading r = WaterPressureScanner.inspect(level, pos);
        String at = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();

        if (r.dry()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No water bears on " + at
                    + ". Either nothing is touching it, water sits on both sides and cancels, or the wall"
                    + " behind it reaches natural ground and is anchored there.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }

        ChatFormatting colour = colourFor(r);
        String verdict = verdictFor(r);
        StringBuilder sb = new StringBuilder();
        sb.append("Water pressure at ").append(at).append(" — ").append(verdict)
          .append(" (").append(fmt(r.stress() * 100.0)).append("% of capacity)\n");
        sb.append("  push   ").append(fmt(r.load())).append("  =  ")
          .append(r.head()).append(" deep x ").append(fmt(r.body())).append(" body")
          .append("  (sampled ").append(r.volume()).append(" water blocks behind it)\n");
        sb.append("  hold   ").append(fmt(r.capacity())).append("  =  ")
          .append("thickness ").append(r.run());
        if (r.bracing() > 0.0) sb.append(" + buttress ").append(fmt(r.bracing()));
        if (r.arch() > 1.0) sb.append(", arch x").append(fmt(r.arch())).append(" (curve ").append(r.curve()).append(")");
        sb.append('\n');
        sb.append("  ").append(advice(r));

        String text = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(text).withStyle(colour), false);
        return 1;
    }

    /** What to actually do about it, in the same units the player can count out in blocks. */
    private static String advice(WaterPressure.Reading r) {
        if (r.stress() < 0.5) return "Comfortable. It would take much deeper water to trouble this.";
        double shortfall = r.load() - r.capacity();
        if (shortfall <= 0.0) {
            return "Holding, but working. Another " + fmt(r.capacity() - r.load())
                    + " of push is all the margin it has left.";
        }
        return "Short by " + fmt(shortfall) + ". Add thickness behind it, put a buttress within "
                + Config.HYDRO_BRACING_REACH.get() + " blocks, bow the wall into the water, or use a stronger"
                + " material — stone holds " + Config.HYDRO_RES_STONE.get() + " per block, concrete "
                + Config.HYDRO_RES_REINFORCED.get() + ", metal " + Config.HYDRO_RES_METAL.get() + ".";
    }

    // ------------------------------------------------------------------- survey

    private static int survey(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        try {
            return survey0(ctx);
        } catch (CommandSyntaxException e) {
            throw e;
        } catch (Throwable t) {
            StructuralIntegrityMod.LOGGER.error("structint: /structint pressure survey failed", t);
            ctx.getSource().sendFailure(Component.literal("Could not run the survey; see the log."));
            return 0;
        }
    }

    private static int survey0(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!Config.HYDRO_ENABLE.get()) {
            ctx.getSource().sendFailure(Component.literal("Water pressure is disabled in the server config."));
            return 0;
        }
        int radius = Config.HYDRO_SURVEY_RADIUS.get();
        int seconds = Config.HYDRO_SURVEY_SECONDS.get();

        WaterPressureScanner.SurveyResult result = WaterPressureScanner.survey(
                player.serverLevel(), player.blockPosition(), radius, seconds * 20);

        if (!result.anyLoad()) {
            ctx.getSource().sendSuccess(() -> Component.literal("Surveyed " + result.examined()
                    + " placed blocks within " + radius + " — none of them is holding water back.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }

        String summary = "Survey, " + radius + " blocks, marked for " + seconds + "s:\n"
                + "  green  " + result.comfortable() + " comfortable\n"
                + "  blue   " + result.loaded() + " working\n"
                + "  orange " + result.straining() + " straining — these will seep\n"
                + "  red    " + result.failing() + " over capacity — these will burst\n"
                + "  worst  " + fmt(result.worstStress() * 100.0) + "% at "
                + result.worstX() + ", " + result.worstY() + ", " + result.worstZ();
        ChatFormatting colour = result.failing() > 0 ? ChatFormatting.RED
                : result.straining() > 0 ? ChatFormatting.GOLD : ChatFormatting.GREEN;
        ctx.getSource().sendSuccess(() -> Component.literal(summary).withStyle(colour), false);
        return 1;
    }

    // -------------------------------------------------------------------- utils

    private static BlockPos lookingAt(ServerPlayer player) {
        try {
            HitResult hit = player.pick(12.0, 0.0F, false);
            if (hit.getType() != HitResult.Type.BLOCK) return null;
            return ((BlockHitResult) hit).getBlockPos();
        } catch (Throwable t) {
            StructuralIntegrityMod.LOGGER.error("structint: could not ray-trace for /structint pressure", t);
            return null;
        }
    }

    private static ChatFormatting colourFor(WaterPressure.Reading r) {
        double weepAt = Config.HYDRO_WEEP_THRESHOLD.get();
        if (r.stress() >= 1.0) return ChatFormatting.RED;
        if (r.stress() >= weepAt) return ChatFormatting.GOLD;
        if (r.stress() >= weepAt * 0.66) return ChatFormatting.AQUA;
        return ChatFormatting.GREEN;
    }

    private static String verdictFor(WaterPressure.Reading r) {
        double weepAt = Config.HYDRO_WEEP_THRESHOLD.get();
        if (r.stress() >= 1.0) return "OVER CAPACITY, this will burst";
        if (r.stress() >= weepAt) return "straining, seeping";
        if (r.stress() >= weepAt * 0.66) return "working";
        return "comfortable";
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }

    private PressureCommand() {
    }
}
