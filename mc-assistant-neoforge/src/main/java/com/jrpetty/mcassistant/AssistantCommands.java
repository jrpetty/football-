package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;
import java.util.List;

/**
 * /assistant spawn | follow | stay | guard | come | status | deposit
 * /assistant gather <logs|stone|dirt> [amount]
 * /assistant dismiss
 */
public final class AssistantCommands {

    private AssistantCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("assistant")
            .then(Commands.literal("spawn").executes(ctx -> spawn(ctx)))
            .then(Commands.literal("follow").executes(ctx -> setMode(ctx, AssistantEntity.Mode.FOLLOW, "Following you.")))
            .then(Commands.literal("stay").executes(ctx -> setMode(ctx, AssistantEntity.Mode.STAY, "Holding position.")))
            .then(Commands.literal("guard").executes(ctx -> setMode(ctx, AssistantEntity.Mode.GUARD, "Guard mode on — I'll watch your back.")))
            .then(Commands.literal("come").executes(AssistantCommands::come))
            .then(Commands.literal("status").executes(AssistantCommands::status))
            .then(Commands.literal("deposit").executes(AssistantCommands::deposit))
            .then(Commands.literal("dismiss").executes(AssistantCommands::dismiss))
            .then(Commands.literal("gather")
                .then(Commands.argument("what", StringArgumentType.word())
                    .executes(ctx -> gather(ctx, 8))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                        .executes(ctx -> gather(ctx, IntegerArgumentType.getInteger(ctx, "amount")))))));
    }

    @Nullable
    public static AssistantEntity findAssistant(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        List<AssistantEntity> found = level.getEntitiesOfClass(
            AssistantEntity.class,
            player.getBoundingBox().inflate(96.0),
            a -> a.isAlive() && a.isOwner(player));
        AssistantEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (AssistantEntity a : found) {
            double d = a.distanceToSqr(player);
            if (d < bestDist) { bestDist = d; best = a; }
        }
        return best;
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        if (findAssistant(player) != null) {
            ctx.getSource().sendFailure(Component.literal("Your assistant is already here — /assistant come."));
            return 0;
        }
        AssistantEntity assistant = McAssistantMod.ASSISTANT.get().create(player.serverLevel());
        if (assistant == null) return 0;
        assistant.moveTo(player.getX() + 1, player.getY(), player.getZ(), player.getYRot(), 0);
        assistant.setOwner(player);
        player.serverLevel().addFreshEntity(assistant);
        assistant.say("Assistant online. Talk to me with ! commands (\"!follow\", \"!gather logs 16\", \"!status\") or /assistant.");
        return 1;
    }

    private static int setMode(CommandContext<CommandSourceStack> ctx, AssistantEntity.Mode mode, String reply) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.setMode(mode);
        a.say(reply);
        return 1;
    }

    private static int come(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        AssistantEntity a = requireAssistant(ctx);
        if (a == null || player == null) return 0;
        a.setMode(AssistantEntity.Mode.STAY);
        a.getNavigation().moveTo(player, 1.25D);
        a.say("Coming to you.");
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.say(String.format("HP %.0f/20, mode %s, carrying %d items, at %d %d %d.",
            a.getHealth(), a.getMode().name().toLowerCase(),
            a.countItems(), a.blockPosition().getX(), a.blockPosition().getY(), a.blockPosition().getZ()));
        return 1;
    }

    private static int deposit(CommandContext<CommandSourceStack> ctx) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        a.requestDeposit();
        return 1;
    }

    private static int dismiss(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        AssistantEntity a = requireAssistant(ctx);
        if (a == null || player == null) return 0;
        a.say("Heading out. Drop my pack here.");
        a.requestDeposit(); // best effort; discard follows either way
        a.discard();
        return 1;
    }

    private static int gather(CommandContext<CommandSourceStack> ctx, int amount) {
        AssistantEntity a = requireAssistant(ctx);
        if (a == null) return 0;
        String what = StringArgumentType.getString(ctx, "what");
        GatherGoal.Kind kind = GatherGoal.Kind.fromWord(what);
        if (kind == null) {
            ctx.getSource().sendFailure(Component.literal("I can gather: logs, stone, dirt."));
            return 0;
        }
        a.requestGather(kind, amount);
        return 1;
    }

    @Nullable
    private static AssistantEntity requireAssistant(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Players only."));
            return null;
        }
        AssistantEntity a = findAssistant(player);
        if (a == null) {
            ctx.getSource().sendFailure(Component.literal("No assistant nearby — /assistant spawn first."));
        }
        return a;
    }
}
