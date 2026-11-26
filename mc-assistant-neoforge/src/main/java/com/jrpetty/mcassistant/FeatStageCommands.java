package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.CastleWall;
import com.jrpetty.mcassistant.entity.FeatStage;
import com.jrpetty.mcassistant.entity.Villages;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;
import java.util.List;

/**
 * [featbuild] Operators' staging for the photographs and the film (never a player's way to anything: the players' ways in
 * are the board's Defences, Parks and Stone pages). Joined to /village by name, operators only.
 *
 * <pre>
 *   /village featbuild quarry [cut]   the quarry opened now (out past the growth line), its yard laid out, its fence up, so
 *                                     many blocks cut into the yard (a negative count: a new site first); prints QUARRY and YARD
 *   /village featbuild garden         a statue garden put up on its lot and the statue of the town's most notable raised
 *   /village featbuild danger N       the wall's stage set (0 open .. 3 towers and a portcullis), and the wall built
 *   /village featbuild watch          where the night watch is now: WATCH name x y z post
 * </pre>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class FeatStageCommands {

    private FeatStageCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("featbuild").requires(src -> src.hasPermission(2))
                .then(Commands.literal("quarry").executes(ctx -> quarry(ctx, 300))
                    .then(Commands.argument("cut", IntegerArgumentType.integer(-5000, 5000)).executes(ctx -> quarry(ctx, IntegerArgumentType.getInteger(ctx, "cut")))))
                .then(Commands.literal("garden").executes(FeatStageCommands::garden))
                .then(Commands.literal("danger").then(Commands.argument("stage", IntegerArgumentType.integer(0, 3)).executes(FeatStageCommands::danger)))
                .then(Commands.literal("watch").executes(FeatStageCommands::watch))));
    }

    @Nullable
    private static Villages.Village near(CommandContext<CommandSourceStack> ctx) {
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), at, Villages.VILLAGE_RANGE * 2);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village near enough."));
        return v;
    }

    private static int say(CommandContext<CommandSourceStack> ctx, List<String> lines) {
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return Math.max(1, lines.size());
    }

    private static int quarry(CommandContext<CommandSourceStack> ctx, int cut) {
        Villages.Village v = near(ctx);
        return v == null ? 0 : say(ctx, FeatStage.quarry(ctx.getSource().getLevel(), v, cut));
    }

    private static int garden(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        return v == null ? 0 : say(ctx, FeatStage.garden(ctx.getSource().getLevel(), v));
    }

    private static int danger(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        List<String> out = FeatStage.danger(ctx.getSource().getLevel(), v, IntegerArgumentType.getInteger(ctx, "stage"));
        CastleWall.buildNow(ctx.getSource().getLevel(), v, true);
        out.addAll(CastleWall.lines(ctx.getSource().getLevel(), v));
        return say(ctx, out);
    }

    private static int watch(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        return v == null ? 0 : say(ctx, FeatStage.watch(ctx.getSource().getLevel(), v));
    }
}
