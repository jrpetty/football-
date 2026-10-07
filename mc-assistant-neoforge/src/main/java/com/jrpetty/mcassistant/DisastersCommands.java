package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.DisasterStage;
import com.jrpetty.mcassistant.entity.Disasters;
import com.jrpetty.mcassistant.entity.Villages;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;
import java.util.function.BiFunction;

/**
 * [disasters] /village disasters — fire, flood and drought in the nearest town (entity/Disasters). Registered on its
 * own (Brigadier joins it to the rest of /village).
 *
 * <pre>
 *   /village disasters                     the town's weather, its fires, floods and droughts, and what it built after
 *   /village disasters fire now            (ops) a spark now from a lit forge with something that burns beside it
 *   /village disasters flood now [1|2]     (ops) the river up over the low ground now (a block, or two)
 *   /village disasters flood drain         (ops) the flood taken up again now, every cell of it
 *   /village disasters levee now           (ops) the levee raised now, as far as the stores pay
 *   /village disasters drought now|end     (ops) a drought now, or broken
 *   /village disasters irrigate now        (ops) the dry fields' channels dug now
 *   /village disasters stage fire|flood|levee|irrigation
 *                                          (ops) a scene set here for the pictures; says where to look from (VIEW)
 * </pre>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class DisastersCommands {

    private DisastersCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("disasters").executes(DisastersCommands::status)
                .then(Commands.literal("fire").requires(src -> src.hasPermission(2))
                    .then(Commands.literal("now").executes(ctx -> act(ctx, (l, v) -> DisasterStage.fireNow(l, v)))))
                .then(Commands.literal("flood").requires(src -> src.hasPermission(2))
                    .then(Commands.literal("now").executes(ctx -> act(ctx, (l, v) -> DisasterStage.floodNow(l, v, 1)))
                        .then(Commands.argument("rise", IntegerArgumentType.integer(1, 2))
                            .executes(ctx -> act(ctx, (l, v) -> DisasterStage.floodNow(l, v, IntegerArgumentType.getInteger(ctx, "rise"))))))
                    .then(Commands.literal("drain").executes(ctx -> act(ctx, DisasterStage::drainNow))))
                .then(Commands.literal("levee").requires(src -> src.hasPermission(2))
                    .then(Commands.literal("now").executes(ctx -> act(ctx, DisasterStage::leveeNow))))
                .then(Commands.literal("drought").requires(src -> src.hasPermission(2))
                    .then(Commands.literal("now").executes(ctx -> act(ctx, (l, v) -> DisasterStage.droughtNow(l, v, true))))
                    .then(Commands.literal("end").executes(ctx -> act(ctx, (l, v) -> DisasterStage.droughtNow(l, v, false)))))
                .then(Commands.literal("irrigate").requires(src -> src.hasPermission(2))
                    .then(Commands.literal("now").executes(ctx -> act(ctx, DisasterStage::irrigateNow))))
                .then(Commands.literal("stage").requires(src -> src.hasPermission(2))
                    .then(Commands.literal("fire").executes(ctx -> stage(ctx, "fire")))
                    .then(Commands.literal("flood").executes(ctx -> stage(ctx, "flood")))
                    .then(Commands.literal("levee").executes(ctx -> stage(ctx, "levee")))
                    .then(Commands.literal("irrigation").executes(ctx -> stage(ctx, "irrigation"))))));
    }

    private static Villages.Village near(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos here = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, here, Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        return v;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        List<String> lines = Disasters.book(ctx.getSource().getLevel(), v.id());
        String text = "DISASTERS " + Villages.name(v.id()) + " | " + String.join(" | ", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int act(CommandContext<CommandSourceStack> ctx, BiFunction<ServerLevel, Villages.Village, String> what) {
        Villages.Village v = near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        String said = what.apply(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(said), true);
        return 1;
    }

    private static int stage(CommandContext<CommandSourceStack> ctx, String which) {
        Villages.Village v = near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        List<String> lines = switch (which) {
            case "fire" -> DisasterStage.stageFire(level, v, at);
            case "flood" -> DisasterStage.stageFlood(level, v, at);
            case "levee" -> DisasterStage.stageLevee(level, v, at);
            default -> DisasterStage.stageIrrigation(level, v, at);
        };
        String text = "DISASTERS STAGE " + which + " | " + String.join(" | ", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return lines.size();
    }
}
