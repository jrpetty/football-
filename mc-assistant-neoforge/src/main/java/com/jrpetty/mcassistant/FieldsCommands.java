package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.FieldTools;
import com.jrpetty.mcassistant.entity.FieldsStage;
import com.jrpetty.mcassistant.entity.Villages;
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

/**
 * [fields] /village items fields — the tools of the fields and the pens in the nearest town (entity/FieldTools).
 * Registered on its own (Brigadier joins it to the rest of /village, and the other groups' items beside it).
 *
 * <pre>
 *   /village items fields          each of the eight: in the stores, wanted, its age and worth; the boxes, troughs,
 *                                  traps and barrels set out; and every folk's tools and what they did today
 *   /village items fields stage    (ops) the pictures' scene where it is run: the eight on a wall of frames, the blocks
 *                                  in a row, and each in use (VIEW lines say where to look from)
 * </pre>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class FieldsCommands {

    private FieldsCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("items")
                .then(Commands.literal("fields").executes(FieldsCommands::status)
                    .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(FieldsCommands::stage)))));
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
        List<String> lines = FieldTools.status(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }

    private static int stage(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) {
            ctx.getSource().sendFailure(Component.literal("No village yet."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        List<String> lines = FieldsStage.stage(level, v, BlockPos.containing(ctx.getSource().getPosition()));
        String text = "FIELDS STAGE | " + String.join(" | ", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return lines.size();
    }
}
